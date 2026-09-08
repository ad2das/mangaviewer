package ml.melun.mangaview.app

import kotlinx.coroutines.*
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.engine.api.*

/** Keeps predicted opening work in the same coordinator and storage as the real viewer. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineOpeningPreparations(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val coordinator: WorkCoordinatorPort,
    private val source: (EpisodeId) -> EngineSessionWork,
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private class Entry(val target: EpisodeId, val predecessor: Job?, val job: Job)
    private var pending: Entry? = null
    private var tail: Job? = null
    private var viewers = 0
    private var closed = false

    fun warm(target: EpisodeId) = synchronized(lock) {
        if (closed || viewers > 0 || pending?.target == target) return@synchronized
        pending?.job?.cancel()
        val previous = tail
        val job = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
            try {
                previous?.join()
                prepare(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
            } finally {
                // A cancelled successor must still account for its predecessor's cleanup.
                withContext(NonCancellable) { previous?.join() }
            }
        }
        pending = Entry(target, previous, job)
        tail = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (tail === job) tail = null
                if (pending?.job === job) pending = null
            }
        }
    }

    fun cancelPrediction() = synchronized(lock) {
        pending?.job?.cancel()
        pending = null
    }

    fun claim(target: EpisodeId): Handoff = synchronized(lock) {
        check(!closed)
        val entry = pending
        pending = null
        viewers++
        if (entry?.target == target) Handoff(entry.job, entry.predecessor)
        else {
            entry?.job?.cancel()
            Handoff(null, tail)
        }
    }

    inner class Handoff internal constructor(private val prediction: Job?, private val predecessor: Job?) {
        private var finished = false
        /** Only superseded work is drained; matching preparation continues alongside viewer demand. */
        suspend fun awaitPredecessor() { predecessor?.join() }
        suspend fun releasePreparation() {
            prediction?.cancelAndJoin()
            predecessor?.join()
        }
        suspend fun close() {
            withContext(NonCancellable) { releasePreparation() }
            synchronized(lock) {
                if (!finished) { finished = true; viewers-- }
            }
        }
    }

    suspend fun close() {
        val last = synchronized(lock) {
            closed = true
            pending?.job?.cancel()
            pending = null
            tail
        }
        last?.cancelAndJoin()
    }

    private suspend fun prepare(target: EpisodeId) {
        val work = source(target)
        val held = java.util.Collections.synchronizedList(mutableListOf<WorkSubscription<*>>())
        suspend fun <T : Any> retain(request: WorkRequest<T>): T {
            val subscription = coordinator.submit(request)
            held.add(subscription)
            return subscription.await()
        }
        try { coroutineScope {
            val saved = async {
                val subscription = coordinator.submit(work.position(target))
                try { subscription.await() } finally {
                    subscription.close()
                    withContext(NonCancellable) { subscription.awaitReleased() }
                }
            }
            val plan = retain(work.episode(target, WorkPriority.NEXT_IMAGE))
            val position = saved.await()
            val page = position.anchor?.pageId ?: position.legacy?.pageId
            val pages = plan.manifest.pages
            val start = pages.indexOfFirst { it.id == page }.coerceAtLeast(0)
            pages.drop(start).take(6).map { item ->
                async { retain(work.page(plan, item.id, WorkPriority.NEXT_IMAGE)) }
            }.awaitAll()
            // Retain the immutable authorization plan until the viewer has acquired its own
            // subscriptions. Re-fetching a plan can change signed URLs and cache revisions.
            awaitCancellation()
        }
        } finally {
            withContext(NonCancellable) {
                val subscriptions = synchronized(held) { held.toList() }
                subscriptions.forEach { it.close() }
                subscriptions.forEach { it.awaitReleased() }
            }
        }
    }
}
