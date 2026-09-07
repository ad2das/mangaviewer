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
    private var initialized = false

    override suspend fun prepare(pageId: PageId, contentRevision: String, opened: OpenedPage): PreparedPage {
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
                val body = files.transfer(pageId, contentRevision, opened, checkNotNull(stage))
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
        val lease = mutex.withLock {
            initializeLocked()
            val entity = index.page(PageCacheKey.of(pageId), contentRevision) ?: return@withLock null
            val page = entity.stored(files)
            check(page.pageId == pageId && page.contentRevision == contentRevision)
            ownership.acquire(page)
        } ?: return@deliver null
        try {
            if (!files.valid(lease.page)) {
                lease.close()
                return@deliver null
            }
            mutex.withLock {
                val entity = index.page(PageCacheKey.of(pageId), contentRevision)
                if (entity != null) index.touch(entity, nowMillis())
            }
            lease
        } catch (error: Throwable) {
            lease.close()
            throw error
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

    override suspend fun publish(prepared: PreparedPage): StoredPageLease = checkNotNull(deliver {
        mutex.withLock {
            withContext(NonCancellable) {
                initializeLocked()
                publishLocked(ownership.requirePrepared(prepared))
            }
        }
    })

    private suspend fun publishLocked(handle: EnginePreparedPage): StoredPageLease {
        check(handle.state == EnginePreparedState.READY) { "Prepared page is no longer publishable" }
        check(files.valid(handle.page)) { "Prepared page bytes changed before publication" }
        val existing = index.page(PageCacheKey.of(handle.page.pageId), handle.page.contentRevision)
        if (existing != null) {
            val committed = existing.stored(files)
            if (!handle.page.sameBody(committed)) throw ImmutableRevisionConflictException()
            if (files.valid(committed)) {
                files.delete(handle.page.file)
                ownership.consume(handle)
                return ownership.acquire(committed)
            }
            if (ownership.isPinned(committed.file)) throw EnginePageInUseException()
        }
        val entity = handle.page.entity(files.destination(handle.page), nowMillis())
        val journal = entity.journal(handle.publicationId, handle.page.file.name)
        files.syncStaging(handle.page.file)
        checkpoint(EnginePublicationStep.FILE_SYNCED)
        handle.state = EnginePreparedState.RECOVERY
        index.stage(journal)
        checkpoint(EnginePublicationStep.JOURNALED)
        val destination = entity.stored(files)
        if (destination.file.exists()) files.delete(destination.file)
        files.publish(handle.page.file, destination.file)
        checkpoint(EnginePublicationStep.RENAMED)
        files.syncPublished(destination.file)
        checkpoint(EnginePublicationStep.DIRECTORY_SYNCED)
        index.commit(journal.publicationId, entity)
        ownership.consume(handle)
        checkpoint(EnginePublicationStep.COMMITTED)
        return ownership.acquire(destination)
    }

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
            initialized = true
        }
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        files.initialize()
        recoverLocked()
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
            files.syncStaging(stage)
            files.delete(destination.file)
            files.publish(stage, destination.file)
        }
        files.syncPublished(destination.file)
        index.commit(journal.publicationId, journal.entity())
        files.delete(stage)
        ownership.completeRecovery(journal.publicationId)
    }

    override suspend fun trimTo(targetBytes: Long): Long {
        require(targetBytes >= 0L)
        return withContext(NonCancellable + ioDispatcher) {
            mutex.withLock {
                initializeLocked()
                val pages = index.pages().sortedBy { it.lastAccessEpochMillis }
                var retained = pages.fold(0L) { sum, page -> Math.addExact(sum, page.byteCount) }
                val pending = index.journals().mapTo(hashSetOf()) { it.destinationRelativePath }
                for (entity in pages) {
                    if (retained <= targetBytes) break
                    val page = entity.stored(files)
                    if (ownership.isPinned(page.file) || entity.relativePath in pending) continue
                    files.delete(page.file)
                    index.remove(entity)
                    retained -= entity.byteCount
                }
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
