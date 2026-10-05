package ml.melun.mangaview.data.engine

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.EngineStoragePort
import ml.melun.mangaview.engine.api.PreparedPage
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.StorageOwnershipSnapshot
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.StoredPageLease
import ml.melun.mangaview.source.OpenedPage

enum class EnginePublicationStep { FILE_SYNCED, JOURNALED, RENAMED, DIRECTORY_SYNCED, COMMITTED }

class ImmutableRevisionConflictException : IllegalStateException("Content revision has a different immutable body")
class EnginePageInUseException : IllegalStateException("Corrupt publication is still leased")

/** Sole owner of publication transitions, prepared bodies and file lease admission. */
class EngineRawStorage(
    root: File,
    private val index: EnginePublicationIndex,
    private val ioDispatcher: CoroutineDispatcher,
    private val positions: EnginePositionPort,
    fileOps: EngineFilePublication = PosixEngineFilePublication(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val checkpoint: suspend (EnginePublicationStep) -> Unit = {},
) : EngineStoragePort {
    private val mutex = Mutex()
    private val files = EnginePageFiles(root, fileOps)
    private val ownership = EngineStorageOwnership(this)
    private val published = EnginePublishedPages()
    private val batcher = EnginePublicationBatcher(files, index, ownership, ioDispatcher, nowMillis, checkpoint, published)
    @Volatile private var initialized = false

    override suspend fun prepare(pageId: PageId, contentRevision: String, opened: OpenedPage): PreparedPage =
        prepareWithGeometry(pageId, contentRevision, opened) {}

    override suspend fun prepareWithGeometry(pageId: PageId, contentRevision: String, opened: OpenedPage,
        reportGeometry: suspend (PageDimensions) -> Unit,
    ): PreparedPage {
        val caller = currentCoroutineContext()
        var stage: File? = null
        var prepared: EnginePreparedPage? = null
        var failure: Throwable? = null
        try {
            require(contentRevision.isNotBlank())
            withContext(ioDispatcher) {
                stage = mutex.withLock {
                    initializeLocked()
                    files.newStaging().also(ownership::beginTransfer)
                }
                val body = files.transfer(pageId, contentRevision, opened, checkNotNull(stage), reportGeometry)
                prepared = ownership.completeTransfer(body)
            }
        } catch (error: Throwable) {
            failure = error
        }
        // Keep the return from IO inside a non-cancellable caller context. Otherwise cancellation
        // during stream.close() can discard the IO result before this owner hands off PreparedPage.
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                try { opened.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
                if (failure == null) try { caller.ensureActive() } catch (error: Throwable) { failure = error }
                if (failure != null) stage?.let { file ->
                    try {
                        files.delete(file)
                        ownership.discardTransfer(file, prepared)
                    } catch (error: Throwable) {
                        ownership.abandonTransfer(file, prepared)
                        if (failure !== error) failure!!.addSuppressed(error)
                    }
                }
            }
        }
        failure?.let { throw it }
        return checkNotNull(prepared)
    }

    override suspend fun find(pageId: PageId, contentRevision: String): StoredPageLease? = deliver {
        ensureInitialized()
        // The read path deliberately runs outside [mutex]: publication only exposes a row after its
        // bytes are renamed into place, and a lease pins the file against trimming, so a concurrent
        // find cannot observe a half-published page. Serializing every lookup on one lock turned the
        // read-ahead's hundreds of cache hits into a multi-second queue behind the single writer.
        // The lookup reads the process-local mirror instead of Room: the mirror is rebuilt under the
        // mutex after recovery and mutated under the mutex by every commit, eviction and
        // invalidation, so the row it serves is exactly a committed row, and a lookup never queues
        // on the writer's Room transactions.
        val entity = published.get(PageCacheKey.of(pageId), contentRevision) ?: return@deliver null
        val page = entity.stored(files)
        check(page.pageId == pageId && page.contentRevision == contentRevision)
        val lease = ownership.acquire(page)
        try {
            if (!files.valid(lease.page)) {
                // Audit: this branch drops only this lease. It deletes no file and no row, so it can
                // never race a newer publication for the same key; the only paths that unlink are
                // eviction (trimTo/invalidate), and those run under the ownership lock through
                // removeUnpinned, which refuses a file any reader pins first.
                lease.close()
                return@deliver null
            }
            // Only an in-memory hint: the writer side flushes them in one batched statement, so
            // lookups stop opening a Room UPDATE per find. The hint is still gated per page at 30 s.
            published.recordTouch(entity, nowMillis())
            lease
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock { initializeLocked() }
    }

    /**
     * A decode refused bytes this port published. Forget the process-local verification and evict
     * the unleased publication so the next lookup re-verifies and re-fetches instead of serving
     * the same broken file again; a leased file is only forgotten — its holders already own open
     * descriptors and deletion is deferred until the pin is released and the page is re-fetched.
     */
    override suspend fun invalidate(page: StoredPage) = withContext(NonCancellable + ioDispatcher) {
        mutex.withLock {
            files.forgetVerified(page.file)
            val entity = published.get(PageCacheKey.of(page.pageId), page.contentRevision) ?: return@withLock
            val committed = entity.stored(files)
            files.forgetVerified(committed.file)
            // Same atomic rule as trim: never unlink a file some lease pins.
            ownership.removeUnpinned(committed.file) { files.unlink(committed.file); true } ?: return@withLock
            index.remove(entity)
            published.remove(entity)
            files.syncDirectory(committed.file.parentFile!!)
        }
    }

    override suspend fun pin(page: StoredPage): StoredPageLease {
        val lease = find(page.pageId, page.contentRevision)
            ?: throw IllegalArgumentException("Page is not a valid committed publication")
        if (lease.page != page) {
            lease.close()
            throw IllegalArgumentException("Page metadata does not match its publication")
        }
        return lease
    }

    /**
     * Publishes one prepared page through the group-commit batcher. Cancellation semantics: a call
     * that has not enqueued yet does nothing; once enqueued the page always completes -- queued and
     * in-batch pages run NonCancellable end to end -- and a caller cancelled while waiting closes
     * the lease it would have returned, so the bytes stay durable and no ownership leaks.
     */
    override suspend fun publish(prepared: PreparedPage): StoredPageLease = checkNotNull(deliver {
        currentCoroutineContext().ensureActive()
        val request = batcher.enqueue(ownership.requirePrepared(prepared))
        var lease: StoredPageLease? = null
        try {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!request.isCompleted) {
                        initializeLocked()
                        batcher.processBatch(batcher.takeAllPending())
                    }
                    // A batch defers a request that duplicated an earlier request of the same key:
                    // it must resolve against the committed row, not stage beside it. The owner is
                    // usually still waiting here; when the owner led that batch, this pass keeps its
                    // contract. Each pass resolves another request of the contended key, so the loop
                    // is bounded by the duplicates already queued -- never a drain for other streams.
                    while (!request.isCompleted) {
                        val next = batcher.takeAllPending()
                        if (next.isEmpty()) break
                        batcher.processBatch(next)
                    }
                }
                lease = request.result.await()
            }
            currentCoroutineContext().ensureActive()
            lease
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    })

    override suspend fun discard(prepared: PreparedPage) {
        withContext(NonCancellable + ioDispatcher) {
            mutex.withLock {
                val handle = ownership.requirePrepared(prepared)
                if (handle.state == EnginePreparedState.CONSUMED) return@withLock
                val durable = index.journals().any { it.publicationId == handle.publicationId }
                if (!durable) files.delete(handle.page.file)
                ownership.consume(handle)
            }
        }
    }

    override suspend fun recover() = withContext(NonCancellable + ioDispatcher) {
        mutex.withLock {
            files.initialize()
            recoverLocked()
            published.replaceAll(index.pages())
            initialized = true
        }
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        files.initialize()
        recoverLocked()
        // Load after recovery: the journals healed above are already committed to the table, so one
        // read yields exactly the durable rows. Nothing can touch the table between the heal and the
        // load while the mutex is held.
        published.replaceAll(index.pages())
        initialized = true
    }

    private suspend fun recoverLocked() {
        for (file in ownership.abandonedTransfers()) {
            files.delete(file)
            ownership.discardTransfer(file, null)
        }
        for (journal in index.journals()) recoverJournalLocked(journal)
        val journals = index.journals()
        val pages = index.pages()
        val protected = ownership.paths() + journals.flatMap {
            listOf(it.stagingRelativePath, it.destinationRelativePath)
        } + pages.map { it.relativePath }
        files.removeOrphans(protected.toSet())
    }

    private suspend fun recoverJournalLocked(journal: EnginePublicationEntity) {
        val destination = journal.entity().stored(files)
        val stage = files.resolve(journal.stagingRelativePath, "staging")
        require(stage.name == "${journal.publicationId}.part") { "Journal staging identity mismatch" }
        val existing = index.page(journal.cacheKey, journal.contentRevision)
        if (existing != null && !existing.stored(files).sameBody(destination)) {
            files.delete(stage)
            index.forgetJournal(journal.publicationId)
            return
        }
        if (!files.valid(destination)) {
            if (ownership.isPinned(destination.file)) return
            if (!files.valid(destination.copy(file = stage))) {
                files.delete(stage)
                index.forgetJournal(journal.publicationId)
                return
            }
            files.syncFile(stage)
            files.delete(destination.file)
            files.publish(stage, destination.file)
        }
        files.syncDirectory(destination.file.parentFile!!)
        index.commit(journal.publicationId, journal.entity())
        files.delete(stage)
        ownership.completeRecovery(journal.publicationId)
    }

    override suspend fun trimTo(targetBytes: Long): Long {
        require(targetBytes >= 0L)
        return withContext(NonCancellable + ioDispatcher) {
            mutex.withLock {
                initializeLocked()
                // Flush the pending in-memory touches in one transaction before sorting: the LRU
                // order must see them. Losing unflushed touches to a crash is fine -- lastAccess is
                // only an eviction hint, never durable state.
                val flushed = published.drainTouches()
                if (flushed.isNotEmpty()) {
                    index.touchAll(flushed)
                    published.confirmTouches(flushed)
                }
                // Merge the just-flushed durable values with hints recorded while the flush ran, so
                // a concurrent touch cannot drop out of the order.
                val pages = index.pages().sortedBy { published.lastAccess(it) }
                var retained = pages.fold(0L) { sum, page -> Math.addExact(sum, page.byteCount) }
                val pending = index.journals().mapTo(hashSetOf()) { it.destinationRelativePath }
                val unlinkedDirectories = linkedSetOf<File>()
                for (entity in pages) {
                    if (retained <= targetBytes) break
                    val page = entity.stored(files)
                    if (entity.relativePath in pending) continue
                    // The pin check and the unlink share the lease lock: a reader either pins first
                    // (refused here) or observes the missing file during validation, never a lease
                    // for a file this loop just removed.
                    ownership.removeUnpinned(page.file) { files.unlink(page.file); true } ?: continue
                    index.remove(entity)
                    published.remove(entity)
                    retained -= entity.byteCount
                    unlinkedDirectories += page.file.parentFile!!
                }
                for (directory in unlinkedDirectories) files.syncDirectory(directory)
                retained
            }
        }
    }

    override suspend fun savePosition(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) =
        positions.save(anchor, legacyScreenOffsetUnits)
    override suspend fun loadPosition(episodeId: EpisodeId): SourceAnchor? = positions.load(episodeId)
    override suspend fun ownership(): StorageOwnershipSnapshot = withContext(ioDispatcher) {
        mutex.withLock { ownership.snapshot(index.journals().size) }
    }

    /** JVM-test observability for the digest and buffer fast paths; production never reads it. */
    internal fun pageFileStats(): EnginePageFileStats = files.stats()

    /** JVM-test observability for the in-memory mirror; production never reads it. */
    internal fun publishedKeysForTest(): Set<Pair<String, String>> = published.keys()

    private suspend fun deliver(block: suspend () -> StoredPageLease?): StoredPageLease? {
        var lease: StoredPageLease? = null
        try { return withContext(ioDispatcher) { block().also { lease = it } } }
        catch (error: Throwable) { lease?.close(); throw error }
    }
}

internal enum class EnginePreparedState { READY, RECOVERY, CONSUMED }

internal class EnginePreparedPage(val owner: Any, override val page: StoredPage) : PreparedPage {
    val publicationId: String = page.file.name.removeSuffix(".part")
    var state = EnginePreparedState.READY
}

/** Short synchronized operations allow Closeable leases to release from any thread. */
internal class EngineStorageOwnership(private val owner: Any) {
    private val lock = Any()
    private val pins = mutableMapOf<File, Int>()
    private val transferring = hashSetOf<File>()
    private val abandoned = hashSetOf<File>()
    private val prepared = hashSetOf<EnginePreparedPage>()

    fun beginTransfer(file: File) = synchronized(lock) { transferring.add(file); Unit }
    fun completeTransfer(page: StoredPage): EnginePreparedPage = synchronized(lock) {
        check(transferring.remove(page.file))
        EnginePreparedPage(owner, page).also { prepared.add(it) }
    }
    fun discardTransfer(file: File, page: EnginePreparedPage?) = synchronized(lock) {
        transferring.remove(file)
        abandoned.remove(file)
        page?.let { prepared.remove(it); it.state = EnginePreparedState.CONSUMED }
    }
    fun abandonTransfer(file: File, page: EnginePreparedPage?) = synchronized(lock) {
        discardTransfer(file, page)
        abandoned.add(file)
    }
    fun abandonedTransfers(): List<File> = synchronized(lock) { abandoned.toList() }
    fun requirePrepared(page: PreparedPage): EnginePreparedPage = synchronized(lock) {
        require(page is EnginePreparedPage && page.owner === owner) { "Foreign prepared page" }
        check(page in prepared || page.state == EnginePreparedState.CONSUMED)
        page
    }
    fun consume(page: EnginePreparedPage) = synchronized(lock) {
        prepared.remove(page)
        page.state = EnginePreparedState.CONSUMED
    }
    fun completeRecovery(id: String) = synchronized(lock) {
        prepared.filter { it.publicationId == id }.forEach { consume(it) }
    }
    fun isPinned(file: File): Boolean = synchronized(lock) { (pins[file] ?: 0) > 0 }

    /**
     * Runs [unlink] only when [file] has no lease, atomically with the pin check under the same lock
     * [acquire] takes. Either a reader pinned first and the removal is refused, or the unlink lands
     * before the reader's [acquire] and its validation observes a missing file. The old
     * check-then-delete outside this lock could interleave: check, reader pins, delete -- evicting a
     * file a reader holds or handing out a lease for a file already removed.
     */
    fun <R> removeUnpinned(file: File, unlink: () -> R): R? = synchronized(lock) {
        if ((pins[file] ?: 0) > 0) null else unlink()
    }
    fun paths(): Set<String> = synchronized(lock) {
        (pins.keys + transferring + abandoned + prepared.map { it.page.file }).mapTo(hashSetOf()) {
            "${it.parentFile!!.name}/${it.name}"
        }
    }
    fun snapshot(journals: Int) = synchronized(lock) {
        StorageOwnershipSnapshot(pins.values.sum(), prepared.size + transferring.size + abandoned.size, journals)
    }
    fun acquire(page: StoredPage): StoredPageLease = synchronized(lock) {
        pins[page.file] = (pins[page.file] ?: 0) + 1
        object : StoredPageLease {
            private val closed = AtomicBoolean()
            override val page = page
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(lock) {
                    val count = checkNotNull(pins[page.file])
                    if (count == 1) pins.remove(page.file) else pins[page.file] = count - 1
                }
            }
        }
    }
}
