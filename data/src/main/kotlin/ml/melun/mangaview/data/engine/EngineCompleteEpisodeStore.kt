package ml.melun.mangaview.data.engine

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.AtomicFilePublisher
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.cache.PosixAtomicFilePublisher
import ml.melun.mangaview.engine.api.*

/** A saved plan becomes usable only after all of its original publications pass full validation. */
class EngineCompleteEpisodeStore(
    private val root: File,
    private val storage: EngineStoragePort,
    private val ioDispatcher: CoroutineDispatcher,
    private val publisher: AtomicFilePublisher = PosixAtomicFilePublisher(),
    private val reportFailure: (Exception) -> Unit = {},
) : EngineEpisodeCachePort {
    private val writes = Mutex()

    override suspend fun remember(plan: EpisodeAccessPlan) {
        if (plan.localOnly || plan.prerequisites.isNotEmpty()) return
        try {
            withContext(ioDispatcher) { writes.withLock {
                require(root.isDirectory || root.mkdirs())
                val stage = File(root, ".plan-${UUID.randomUUID()}")
                try {
                    EngineEpisodePlanCodec.write(stage, plan)
                    publisher.publish(stage, path(plan.manifest.id))
                    root.listFiles().orEmpty().filter { it.extension == "plan" }
                        .sortedByDescending(File::lastModified).drop(128).forEach(File::delete)
                } finally { stage.delete() }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportFailure(failure) }
    }

    override suspend fun open(episodeId: EpisodeId): CachedEngineEpisode? {
        var result: CachedEngineEpisode? = null
        try {
            return withContext(ioDispatcher) {
                acquire(episodeId).also { result = it }
            }
        } catch (failure: Throwable) {
            result?.close()
            throw failure
        }
    }

    private suspend fun acquire(episodeId: EpisodeId): CachedEngineEpisode? {
        val leases = mutableListOf<StoredPageLease>()
        var retained = false
        try {
            val file = path(episodeId)
            if (!file.isFile) return null
            val plan = EngineEpisodePlanCodec.read(file)
            require(plan.manifest.id == episodeId)
            for (spec in plan.manifest.pages) {
                currentCoroutineContext().ensureActive()
                val lease = storage.find(spec.id, plan.contentRevision) ?: return null
                leases += lease
                val page = lease.page
                require(page.pageId == spec.id && page.contentRevision == plan.contentRevision)
                require(spec.dimensions == null || spec.dimensions == page.dimensions)
                require(spec.encodedLength == null || spec.encodedLength == page.byteCount)
                require(spec.fingerprint == null || spec.fingerprint == page.sha256)
            }
            return object : CachedEngineEpisode {
                override val plan = plan
                private val closed = AtomicBoolean()
                override fun close() { if (closed.compareAndSet(false, true)) release(leases) }
            }.also { retained = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportFailure(failure); return null }
        finally { if (!retained) release(leases) }
    }

    private fun path(episode: EpisodeId) =
        File(root, PageCacheKey.of(PageId(episode, "engine-complete-plan")) + ".plan")

    private fun release(leases: List<StoredPageLease>) {
        var failure: Exception? = null
        for (lease in leases) try { lease.close() } catch (error: Exception) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
