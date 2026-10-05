package ml.melun.mangaview.data.engine

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.EnginePageEntity
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.engine.api.StoredPageLease

/**
 * Single-lock group commit for page publications. A publisher enqueues its request and then races
 * for the storage mutex; the first one to take the lock processes exactly one batch -- every request
 * pending at that instant, which always includes its own -- and leaves. There is no drain loop and no
 * timer: a leader's latency is bounded by one batch, and kotlinx's FIFO mutex keeps waiters ordered.
 *
 * The batch keeps the per-page durability sequence byte-for-byte: sync each staging file, one journal
 * transaction, per-page rename (isolated), one directory sync per distinct destination directory,
 * one commit transaction. A page's named failures (length, revision conflict, still-leased corrupt
 * body, staging sync, rename) fail only that page; the batch-wide steps (journal transaction,
 * directory sync, commit transaction) fail the pages they cover, whose journals stay durable and are
 * healed independently by recovery.
 */
internal class EnginePublicationBatcher(
    private val files: EnginePageFiles,
    private val index: EnginePublicationIndex,
    private val ownership: EngineStorageOwnership,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMillis: () -> Long,
    private val checkpoint: suspend (EnginePublicationStep) -> Unit,
) {
    private val queueLock = Any()
    private val pending = ArrayDeque<PublishRequest>()

    class PublishRequest(val handle: EnginePreparedPage) {
        val result = CompletableDeferred<StoredPageLease>()
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
            // A checkpoint or a batch-wide step failed mid-flight: complete every request that left
            // the queue with this batch, so no publisher is left waiting on a request nothing owns.
            batch.forEach { if (!it.isCompleted) it.fail(error) }
            throw error
        }
    }

    private suspend fun processBatchSteps(batch: List<PublishRequest>) {
        val ready = stageReady(batch)
        if (ready.isEmpty()) return

        val synced = syncReadyFiles(ready)
        if (synced.isEmpty()) return
        checkpoint(EnginePublicationStep.FILE_SYNCED)
        synced.forEach { it.handle.state = EnginePreparedState.RECOVERY }
        if (!stageJournals(synced)) return

        val renamed = renameReady(synced)
        if (renamed.isEmpty()) return
        checkpoint(EnginePublicationStep.RENAMED)
        if (!syncDirectories(renamed)) return
        if (!commitRenamed(renamed)) return
        checkpoint(EnginePublicationStep.COMMITTED)
        completeRenamed(renamed)
    }

    /** Validates and dedupes each request; a page's own failure never touches its batch mates. */
    private suspend fun stageReady(batch: List<PublishRequest>): List<ReadyPage> {
        val ready = ArrayList<ReadyPage>(batch.size)
        for (request in batch) {
            if (request.isCompleted) continue
            val handle = request.handle
            try {
                check(handle.state == EnginePreparedState.READY) { "Prepared page is no longer publishable" }
                // transfer() digested exactly the bytes it wrote to a process-private staging name, so
                // the cheap remaining invariant is the size the prepared body records.
                check(handle.page.file.length() == handle.page.byteCount) { "Prepared page bytes changed before publication" }
                val existing = index.page(PageCacheKey.of(handle.page.pageId), handle.page.contentRevision)
                if (existing != null) {
                    val committed = existing.stored(files)
                    if (!handle.page.sameBody(committed)) throw ImmutableRevisionConflictException()
                    if (files.valid(committed)) {
                        files.delete(handle.page.file)
                        ownership.consume(handle)
                        request.complete(ownership.acquire(committed))
                        continue
                    }
                    if (ownership.isPinned(committed.file)) throw EnginePageInUseException()
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
        return ready
    }

    /** Syncs staging files concurrently; syncs on distinct files are independent. */
    private suspend fun syncReadyFiles(ready: List<ReadyPage>): List<ReadyPage> = coroutineScope {
        ready.map { page ->
            page to async(ioDispatcher) {
                try {
                    files.syncFile(page.handle.page.file)
                    true
                } catch (error: Throwable) {
                    page.request.fail(error)
                    false
                }
            }
        }.mapNotNull { (page, attempt) -> if (attempt.await()) page else null }
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
                if (page.destination.exists()) files.delete(page.destination)
                files.publish(page.handle.page.file, page.destination)
                renamed += page
            } catch (error: Throwable) {
                page.request.fail(error)
            }
        }
        return renamed
    }

    /** One directory sync per distinct destination directory; failure covers the renamed pages. */
    private suspend fun syncDirectories(renamed: List<ReadyPage>): Boolean {
        for (directory in renamed.mapTo(linkedSetOf()) { it.destination.parentFile!! }) {
            try {
                files.syncDirectory(directory)
            } catch (error: Throwable) {
                renamed.forEach { it.request.fail(error) }
                return false
            }
        }
        checkpoint(EnginePublicationStep.DIRECTORY_SYNCED)
        return true
    }

    /** One commit transaction for the batch; a failure leaves durable journals for recovery. */
    private suspend fun commitRenamed(renamed: List<ReadyPage>): Boolean {
        try {
            index.commitAll(renamed.map { it.journal.publicationId to it.entity })
        } catch (error: Throwable) {
            renamed.forEach { it.request.fail(error) }
            return false
        }
        return true
    }

    private suspend fun completeRenamed(renamed: List<ReadyPage>) {
        for (page in renamed) {
            ownership.consume(page.handle)
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
