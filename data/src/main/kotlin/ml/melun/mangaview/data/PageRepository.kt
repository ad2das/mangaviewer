package ml.melun.mangaview.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.RawPageCache
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PageFetchPriority

class PageRepository(
    private val scope: CoroutineScope,
    private val sourceFor: (SourceId) -> ContentSource,
    private val cache: RawPageCache,
    private val offline: OfflineEpisodeStore? = null,
) {
    private class Flight(
        var waiters: Int = 1,
        var acceptingWaiters: Boolean = true,
        var responseStarted: Boolean = false,
        var priority: PageFetchPriority,
        val responseObservers: MutableList<() -> Unit> = mutableListOf(),
    ) {
        lateinit var deferred: Deferred<CachedPage>
    }

    private sealed interface Acquisition {
        data class Lease(
            val flight: Flight,
            val notifyImmediately: (() -> Unit)?,
        ) : Acquisition
        data class AwaitRetirement(val job: Job) : Acquisition
    }

    private val lock = Any()
    private val active = mutableMapOf<PageId, Flight>()

    suspend fun get(
        pageId: PageId,
        priority: PageFetchPriority = PageFetchPriority.NORMAL,
        onNetworkResponseStarted: (() -> Unit)? = null,
    ): CachedPage {
        while (true) {
            when (val acquisition = acquire(pageId, priority, onNetworkResponseStarted)) {
                is Acquisition.AwaitRetirement -> acquisition.job.join()
                is Acquisition.Lease -> {
                    acquisition.notifyImmediately?.let(::notifySafely)
                    return await(pageId, acquisition.flight)
                }
            }
        }
    }

    suspend fun activeRequestCount(): Int = synchronized(lock) { active.size }

    private fun acquire(
        pageId: PageId,
        priority: PageFetchPriority,
        responseObserver: (() -> Unit)?,
    ): Acquisition = synchronized(lock) {
        active[pageId]?.let { flight ->
            if (!flight.acceptingWaiters) {
                return@synchronized Acquisition.AwaitRetirement(flight.deferred)
            }
            flight.waiters += 1
            flight.priority = stronger(flight.priority, priority)
            val notifyImmediately = if (flight.responseStarted) responseObserver else null
            if (!flight.responseStarted && responseObserver != null) {
                flight.responseObservers += responseObserver
            }
            return@synchronized Acquisition.Lease(flight, notifyImmediately)
        }
        val flight = Flight(priority = priority)
        responseObserver?.let(flight.responseObservers::add)
        flight.deferred = scope.async(start = CoroutineStart.LAZY) { load(pageId, flight) }
        active[pageId] = flight
        flight.deferred.invokeOnCompletion { removeWhenComplete(pageId, flight) }
        flight.deferred.start()
        Acquisition.Lease(flight, null)
    }

    private suspend fun await(pageId: PageId, flight: Flight): CachedPage = try {
        flight.deferred.await()
    } finally {
        release(pageId, flight)
    }

    private suspend fun load(pageId: PageId, flight: Flight): CachedPage {
        offline?.find(pageId)?.let { return it }
        cache.find(pageId)?.let { return it }
        val sourceId = pageId.episodeId.seriesId.sourceId
        val source = sourceFor(sourceId)
        require(source.id == sourceId) { "Source resolver returned a mismatched source" }
        val priority = synchronized(lock) { flight.priority }
        val opened = source.openPage(pageId, validation = null, priority = priority)
        return try {
            notifyResponseStarted(pageId, flight)
            cache.write(pageId, opened)
        } finally {
            opened.close()
        }
    }

    private fun notifyResponseStarted(pageId: PageId, flight: Flight) {
        val observers = synchronized(lock) {
            if (active[pageId] !== flight || flight.responseStarted) return
            flight.responseStarted = true
            flight.responseObservers.toList().also { flight.responseObservers.clear() }
        }
        observers.forEach(::notifySafely)
    }

    private fun notifySafely(observer: () -> Unit) {
        runCatching(observer)
    }

    private fun stronger(
        current: PageFetchPriority,
        incoming: PageFetchPriority,
    ): PageFetchPriority = if (incoming.ordinal < current.ordinal) incoming else current

    private fun release(pageId: PageId, flight: Flight) {
        val cancel = synchronized(lock) {
            if (active[pageId] !== flight) return
            flight.waiters -= 1
            require(flight.waiters >= 0) { "Page flight waiter count became negative" }
            if (flight.waiters == 0 && !flight.deferred.isCompleted) {
                flight.acceptingWaiters = false
                true
            } else {
                false
            }
        }
        if (cancel) flight.deferred.cancel()
    }

    private fun removeWhenComplete(pageId: PageId, flight: Flight) {
        synchronized(lock) {
            if (active[pageId] === flight) active.remove(pageId)
        }
    }
}
