package ml.melun.mangaview.data.engine

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.EnginePageEntity
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.engine.api.StoredPageLease

/**
 * Single-lock group commit for page publications. A publisher enqueues its request and then races
 * for the storage mutex; the first one to take the lock processes exactly one batch -- every request
 * pending at that instant, which always includes its own -- and leaves. There is no drain loop and no
 * timer: a leader's latency is bounded by one batch, and kotlinx's FIFO mutex keeps waiters ordered.
 * A request that duplicates an earlier request of the same key and revision in its batch is not
 * staged alongside it: it goes back to the front of the queue and resolves against the committed
 * row on the owner's next pass, exactly like the serial path.
 *
 * The batch keeps the per-page durability sequence byte-for-byte: one journal transaction, per-page
 * rename (isolated), one commit transaction. No fsync is taken on this path: process-crash
 * protection is the journal/rename/commit order, and power-loss damage is rejected by digest
 * validation on the first find in a new process (see EnginePageFiles.publish). A page's named
 * failures (length, revision conflict, still-leased corrupt body, rename) fail only that page; the
 * batch-wide steps (journal transaction, commit transaction) fail the pages they cover, whose
 * journals stay durable and are healed independently by recovery.
 */
internal class EnginePublicationBatcher(
    private val files: EnginePageFiles,
    private val index: EnginePublicationIndex,
    private val ownership: EngineStorageOwnership,
    private val nowMillis: () -> Long,
    private val checkpoint: suspend (EnginePublicationStep) -> Unit,
    private val published: EnginePublishedPages,
) {
    private val queueLock = Any()
    private val pending = ArrayDeque<PublishRequest>()

    class PublishRequest(val handle: EnginePreparedPage) {
        val result = CompletableDeferred<StoredPageLease>()

        /** True while this request is queued for a later batch instead of the one inspecting it. */
        var deferred: Boolean = false
        val isCompleted: Boolean get() = result.isCompleted
        fun complete(lease: StoredPageLease) { result.complete(lease) }
        fun fail(error: Throwable) { result.completeExceptionally(error) }
    }

    fun enqueue(handle: EnginePreparedPage): PublishRequest = PublishRequest(handle).also {
        synchronized(queueLock) { pending.addLast(it) }
    }

    /** Everything queued at this instant; the caller must already hold the storage mutex. */
    fun takeAllPending(): List<PublishRequest> = synchronized(queueLock) {
        if (pending.isEmpty()) return emptyList()
        val batch = pending.toList()
        pending.clear()
        batch
    }

    /** Runs one batch under the storage mutex; requests completed by an earlier batch are skipped. */
    suspend fun processBatch(batch: List<PublishRequest>) {
        try {
            processBatchSteps(batch)
        } catch (error: Throwable) {
            // A checkpoint or a batch-wide step failed mid-flight: complete every request this
            // batch still owns. A deferred request is back in the queue and keeps its owner.
            batch.forEach { if (!it.isCompleted && !it.deferred) it.fail(error) }
            throw error
        }
    }

    private suspend fun processBatchSteps(batch: List<PublishRequest>) {
        val ready = stageReady(batch)
        if (ready.isEmpty()) return

        // Staging files were already written and digested by transfer(). No fsync is taken here:
        // the journal/rename/commit order below protects against a process crash, and the page
        // cache survives one. A power loss can leave a torn destination, which the next process
        // rejects on its first find because the verification cache starts empty.
        checkpoint(EnginePublicationStep.STAGED)
        ready.forEach { it.handle.state = EnginePreparedState.RECOVERY }
        if (!stageJournals(ready)) return

        val renamed = renameReady(ready)
        if (renamed.isEmpty()) return
        checkpoint(EnginePublicationStep.RENAMED)
        if (!commitRenamed(renamed)) return
        // The committed name is visible to readers without the mutex: seed each destination's
        // verification stamp now, from the post-rename file, so the first find trusts the digest
        // transfer() already took instead of re-reading the body beside visible decodes. Then
        // consume before the checkpoint: a checkpoint failure must not leave committed handles
        // registered as prepared (RECOVERY), where orphan cleanup cannot reach them.
        for (page in renamed) {
            files.rememberVerified(page.destination)
            ownership.consume(page.handle)
        }
        checkpoint(EnginePublicationStep.COMMITTED)
        completeRenamed(renamed)
    }

    /**
     * Validates each request; a page's own failure never touches its batch mates. Two publishes of
     * one (key, revision) can be enqueued before either is in the index; the batch stages only the
     * first and puts the rest back at the front of the queue, where the owners' next passes resolve
     * them against the committed row with exactly the serial path's semantics.
     */
    private suspend fun stageReady(batch: List<PublishRequest>): List<ReadyPage> {
        val ready = ArrayList<ReadyPage>(batch.size)
        val claimed = HashSet<Pair<String, String>>()
        val deferred = ArrayList<PublishRequest>()
        for (request in batch) {
            if (request.isCompleted) continue
            request.deferred = false
            val handle = request.handle
            try {
                check(handle.state == EnginePreparedState.READY) { "Prepared page is no longer publishable" }
                // transfer() digested exactly the bytes it wrote to a process-private staging name, so
                // the cheap remaining invariant is the size the prepared body records.
                check(handle.page.file.length() == handle.page.byteCount) { "Prepared page bytes changed before publication" }
                // The process-local mirror is authoritative under the storage mutex: this check and
                // the lookup fast path see the same committed rows, and a publish candidate no
                // longer spends a Room read. commitAll's in-transaction immutability check stays as
                // the durable backstop.
                val existing = published.get(PageCacheKey.of(handle.page.pageId), handle.page.contentRevision)
                if (existing != null) {
                    val committed = existing.stored(files)
                    if (!handle.page.sameBody(committed)) throw ImmutableRevisionConflictException()
                    if (files.valid(committed)) {
                        // valid() proved (or its own stamp already covered) the committed body; make
                        // the seeding explicit so both resolution paths leave the same proof behind.
                        files.rememberVerified(committed.file)
                        files.unlink(handle.page.file)
                        ownership.consume(handle)
                        request.complete(ownership.acquire(committed))
                        continue
                    }
                    if (ownership.isPinned(committed.file)) throw EnginePageInUseException()
                }
                if (!claimed.add(PageCacheKey.of(handle.page.pageId) to handle.page.contentRevision)) {
                    request.deferred = true
                    deferred += request
                    continue
                }
                val entity = handle.page.entity(files.destination(handle.page), nowMillis())
                ready += ReadyPage(
                    request, handle, entity,
                    entity.journal(handle.publicationId, handle.page.file.name),
                    files.resolve(entity.relativePath, "pages"),
                )
            } catch (error: Throwable) {
                request.fail(error)
            }
        }
        if (deferred.isNotEmpty()) {
            synchronized(queueLock) { pending.addAll(0, deferred) }
        }
        return ready
    }

    /** One journal transaction for the batch; a failure covers every page in it. */
    private suspend fun stageJournals(synced: List<ReadyPage>): Boolean {
        try {
            index.stageAll(synced.map { it.journal })
        } catch (error: Throwable) {
            synced.forEach { it.request.fail(error) }
            return false
        }
        checkpoint(EnginePublicationStep.JOURNALED)
        return true
    }

    /** Per-page rename; a renamed page's journal is already durable. */
    private suspend fun renameReady(synced: List<ReadyPage>): List<ReadyPage> {
        val renamed = ArrayList<ReadyPage>(synced.size)
        for (page in synced) {
            try {
                // Replace path (an invalid unpinned committed body). The unlink takes no directory
                // sync: after a power loss the stale entry reappears and the next find re-validates
                // it, and a process crash loses nothing with the page cache intact.
                if (page.destination.exists()) files.unlink(page.destination)
                files.publish(page.handle.page.file, page.destination)
                renamed += page
            } catch (error: Throwable) {
                page.request.fail(error)
            }
        }
        return renamed
    }

    /** One commit transaction for the batch; a failure leaves durable journals for recovery. */
    private suspend fun commitRenamed(renamed: List<ReadyPage>): Boolean {
        // Piggyback the pending last-access hints on the commit transaction: the writer side
        // flushes them inside the same Room transaction instead of a lookup opening its own UPDATE.
        // A touch-free batch keeps the original commit call shape.
        val touches = published.drainTouches()
        try {
            val entries = renamed.map { it.journal.publicationId to it.entity }
            if (touches.isEmpty()) index.commitAll(entries) else index.commitAll(entries, touches)
        } catch (error: Throwable) {
            renamed.forEach { it.request.fail(error) }
            return false
        }
        published.confirmTouches(touches)
        // Committed rows become visible to lock-free readers here; the file behind each is renamed
        // and committed, so a find that observes one re-validates the body before leasing it.
        for (page in renamed) published.put(page.entity)
        return true
    }

    /** Acquires each committed file's lease and completes its publisher (handles are already consumed). */
    private suspend fun completeRenamed(renamed: List<ReadyPage>) {
        for (page in renamed) {
            page.request.complete(ownership.acquire(page.entity.stored(files)))
        }
    }

    private class ReadyPage(
        val request: PublishRequest,
        val handle: EnginePreparedPage,
        val entity: EnginePageEntity,
        val journal: EnginePublicationEntity,
        val destination: File,
    )
}
