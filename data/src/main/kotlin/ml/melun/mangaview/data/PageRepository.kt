package ml.melun.mangaview.data

import java.io.IOException
import java.util.logging.Logger
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.PageTransferPreview
import ml.melun.mangaview.data.cache.RawPageCache
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourcePageUnavailableException

class PageRepository(
    private val scope: CoroutineScope,
    private val sourceFor: (SourceId) -> ContentSource,
    private val cache: RawPageCache,
    private val offline: OfflineEpisodeStore? = null,
) {
    private class Flight(
        var demandWaiters: Int,
        var speculativeWaiters: Int,
        var acceptingWaiters: Boolean = true,
        var transportStarted: Boolean = false,
        var responseStarted: Boolean = false,
        var priority: PageFetchPriority,
        var openedStream: PageByteStream? = null,
        var parkedRetainer: Boolean = false,
        var readGate: CompletableDeferred<Unit>? = null,
        val responseObservers: MutableList<() -> Unit> = mutableListOf(),
        val previewObservers: MutableList<(PageTransferPreview) -> Unit> = mutableListOf(),
        var latestPreview: PageTransferPreview? = null,
    ) {
        val waiterCount: Int
            get() = demandWaiters + speculativeWaiters

        lateinit var deferred: Deferred<CachedPage>
    }

    private sealed interface Acquisition {
        data class Lease(
            val flight: Flight,
            val notifyImmediately: (() -> Unit)?,
            val promoteImmediately: (() -> Unit)?,
            val previewImmediately: (() -> Unit)?,
            val resumeImmediately: (() -> Unit)?,
        ) : Acquisition
        data class AwaitRetirement(
            val job: Job,
            val cancel: Boolean = false,
        ) : Acquisition
    }

    private val lock = Any()
    private val active = mutableMapOf<PageId, Flight>()

    suspend fun get(
        pageId: PageId,
        priority: PageFetchPriority = PageFetchPriority.NORMAL,
        onTransferPreview: ((PageTransferPreview) -> Unit)? = null,
        speculative: Boolean = false,
        onNetworkResponseStarted: (() -> Unit)? = null,
    ): CachedPage {
        if (priority == PageFetchPriority.FOCUS || priority == PageFetchPriority.VISIBLE) {
            preemptUnansweredSpeculation(pageId)
        }
        while (true) {
            when (val acquisition = acquire(
                pageId,
                priority,
                onTransferPreview,
                onNetworkResponseStarted,
                speculative,
            )) {
                is Acquisition.AwaitRetirement -> {
                    if (acquisition.cancel) acquisition.job.cancel()
                    acquisition.job.join()
                }
                is Acquisition.Lease -> {
                    acquisition.promoteImmediately?.let(::notifySafely)
                    acquisition.resumeImmediately?.let(::notifySafely)
                    acquisition.notifyImmediately?.let(::notifySafely)
                    acquisition.previewImmediately?.let(::notifySafely)
                    return await(pageId, acquisition.flight, speculative)
                }
            }
        }
    }

    suspend fun activeRequestCount(): Int = synchronized(lock) { active.size }

    /** Keeps one single-flight and its staging file alive while stopping further body pulls. */
    fun park(pageId: PageId): Boolean = synchronized(lock) {
        val flight = active[pageId] ?: return@synchronized false
        if (!flight.acceptingWaiters || flight.deferred.isCompleted || !flight.responseStarted) {
            return@synchronized false
        }
        if (!flight.parkedRetainer) {
            flight.speculativeWaiters += 1
            flight.parkedRetainer = true
        }
        if (flight.readGate == null) flight.readGate = CompletableDeferred()
        true
    }

    /** Cancels only still-parked flights owned by a viewer generation that is going away. */
    fun discardParked(pageIds: Collection<PageId>) {
        val victims = synchronized(lock) {
            pageIds.distinct().mapNotNull { pageId ->
                val flight = active[pageId]?.takeIf { it.parkedRetainer } ?: return@mapNotNull null
                flight.acceptingWaiters = false
                flight.readGate?.complete(Unit)
                flight.readGate = null
                flight.deferred
            }
        }
        victims.forEach { it.cancel() }
    }

    /** Raises a live body's transport urgency without creating a second page request. */
    fun promote(pageId: PageId, priority: PageFetchPriority) {
        val action = synchronized(lock) {
            val flight = active[pageId] ?: return
            val previousPriority = flight.priority
            flight.priority = stronger(previousPriority, priority)
            if (flight.priority == previousPriority) null else priorityAction(flight)
        }
        action?.let(::notifySafely)
    }

    /** Retires prediction flights outside the viewer's exact opening cohort. */
    fun retireSpeculationOutside(episodeId: ml.melun.mangaview.core.EpisodeId, retained: Set<PageId>) {
        val victims = synchronized(lock) {
            active.asSequence()
                .filter { (pageId, flight) ->
                    pageId.episodeId == episodeId && pageId !in retained &&
                        flight.demandWaiters == 0 && flight.speculativeWaiters > 0 &&
                        flight.priority.ordinal > PageFetchPriority.FORWARD.ordinal &&
                        flight.acceptingWaiters && !flight.deferred.isCompleted
                }
                .map { (_, flight) ->
                    flight.acceptingWaiters = false
                    flight.deferred
                }
                .toList()
        }
        victims.forEach { it.cancel() }
    }

    /**
     * Unrelated detail/background warming yields before it consumes a response. Forward viewer
     * work is deliberately retained: it is the same stream the user is moving into and canceling
     * it at the viewport edge makes fast scrolling repeatedly restart the next image handshake.
     * Once headers have arrived, retaining the usually tiny remaining body is cheaper than
     * throwing it away. Equal-priority visible owners never preempt one another.
     */
    private suspend fun preemptUnansweredSpeculation(visiblePageId: PageId) {
        val victims = synchronized(lock) {
            active.asSequence()
                .filter { (pageId, flight) ->
                    pageId.episodeId == visiblePageId.episodeId &&
                        pageId != visiblePageId &&
                        flight.priority != PageFetchPriority.FOCUS &&
                        flight.priority != PageFetchPriority.VISIBLE &&
                        flight.priority != PageFetchPriority.IMMINENT_FORWARD &&
                        flight.priority != PageFetchPriority.FORWARD &&
                        !flight.responseStarted && flight.acceptingWaiters
                }
                .map { (_, flight) ->
                    flight.acceptingWaiters = false
                    flight.deferred
                }
                .toList()
        }
        victims.forEach { it.cancel() }
        victims.joinAll()
    }

    private fun acquire(
        pageId: PageId,
        priority: PageFetchPriority,
        previewObserver: ((PageTransferPreview) -> Unit)?,
        responseObserver: (() -> Unit)?,
        speculative: Boolean,
    ): Acquisition = synchronized(lock) {
        active[pageId]?.let { flight ->
            if (!flight.acceptingWaiters) {
                return@synchronized Acquisition.AwaitRetirement(flight.deferred)
            }
            val resumeGate = if (!speculative && flight.parkedRetainer) {
                flight.parkedRetainer = false
                flight.speculativeWaiters -= 1
                flight.readGate.also { flight.readGate = null }
            } else {
                null
            }
            if (speculative) flight.speculativeWaiters += 1 else flight.demandWaiters += 1
            val previousPriority = flight.priority
            flight.priority = stronger(previousPriority, priority)
            val notifyImmediately = if (flight.responseStarted) responseObserver else null
            val previewImmediately = previewObserver?.let { observer ->
                flight.latestPreview?.let { preview -> { observer(preview) } }
            }
            val promoteImmediately = if (flight.priority != previousPriority) {
                priorityAction(flight)
            } else {
                null
            }
            if (!flight.responseStarted && responseObserver != null) {
                flight.responseObservers += responseObserver
            }
            if (previewObserver != null) flight.previewObservers += previewObserver
            return@synchronized Acquisition.Lease(
                flight,
                notifyImmediately,
                promoteImmediately,
                previewImmediately,
                resumeGate?.let { gate -> { gate.complete(Unit); Unit } },
            )
        }
        val flight = Flight(
            demandWaiters = if (speculative) 0 else 1,
            speculativeWaiters = if (speculative) 1 else 0,
            priority = priority,
        )
        responseObserver?.let(flight.responseObservers::add)
        previewObserver?.let(flight.previewObservers::add)
        flight.deferred = scope.async(start = CoroutineStart.LAZY) { load(pageId, flight) }
        active[pageId] = flight
        flight.deferred.invokeOnCompletion { removeWhenComplete(pageId, flight) }
        flight.deferred.start()
        Acquisition.Lease(flight, null, null, null, null)
    }

    private suspend fun await(pageId: PageId, flight: Flight, speculative: Boolean): CachedPage = try {
        flight.deferred.await()
    } finally {
        release(pageId, flight, speculative)
    }

    private suspend fun load(pageId: PageId, flight: Flight): CachedPage = supervisorScope {
        offline?.find(pageId)?.let { return@supervisorScope it }
        cache.find(pageId)?.let { return@supervisorScope it }
        val sourceId = pageId.episodeId.seriesId.sourceId
        val source = sourceFor(sourceId)
        require(source.id == sourceId) { "Source resolver returned a mismatched source" }
        synchronized(lock) { flight.transportStarted = true }
        var attempt = 0
        while (true) {
            val priority = synchronized(lock) { flight.priority }
            try {
                return@supervisorScope transferOnce(pageId, flight, source, priority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val retry = synchronized(lock) {
                    flight.latestPreview = null
                    attempt == 0 && flight.priority != PageFetchPriority.BACKGROUND &&
                        failure is IOException && failure !is SourcePageUnavailableException
                }
                PAGE_TRANSFER_LOGGER.warning(
                    "transfer failed page=${pageId.remoteKey} attempt=$attempt " +
                        "priority=$priority retry=$retry reason=${failure.message}",
                )
                if (!retry) throw failure
                attempt += 1
            }
        }
        error("Page transfer loop exited unexpectedly")
    }

    private suspend fun transferOnce(
        pageId: PageId,
        flight: Flight,
        source: ContentSource,
        priority: PageFetchPriority,
    ): CachedPage {
        val startedAtNanos = System.nanoTime()
        logTransfer("phase=open-start page=$pageId priority=$priority")
        val opened = source.openPage(pageId, validation = null, priority = priority)
        logTransfer(
            "phase=open-ready page=$pageId priority=$priority " +
                "elapsedMs=${elapsedMillis(startedAtNanos)} length=${opened.contentLength}",
        )
        try {
            val currentPriority = synchronized(lock) {
                if (active[pageId] === flight) flight.openedStream = opened.stream
                flight.priority
            }
            opened.stream.promote(currentPriority)
            notifyResponseStarted(pageId, flight)
            val controlled = opened.copy(stream = FlightControlledPageByteStream(opened.stream) {
                awaitReadable(pageId, flight)
            })
            return cache.write(pageId, controlled) { preview ->
                notifyPreview(pageId, flight, preview)
            }.also { cached ->
                logTransfer(
                    "phase=complete page=$pageId priority=$priority " +
                        "elapsedMs=${elapsedMillis(startedAtNanos)} bytes=${cached.byteCount}",
                )
            }
        } finally {
            synchronized(lock) {
                if (active[pageId] === flight && flight.openedStream === opened.stream) {
                    flight.openedStream = null
                }
            }
            opened.close()
        }
    }

    private fun priorityAction(flight: Flight): (() -> Unit)? {
        val stream = flight.openedStream ?: return null
        val promotedPriority = flight.priority
        return { stream.promote(promotedPriority) }
    }

    private fun notifyResponseStarted(pageId: PageId, flight: Flight) {
        val observers = synchronized(lock) {
            if (active[pageId] !== flight || flight.responseStarted) return
            flight.responseStarted = true
            flight.responseObservers.toList().also { flight.responseObservers.clear() }
        }
        observers.forEach(::notifySafely)
    }

    private suspend fun awaitReadable(pageId: PageId, flight: Flight) {
        while (true) {
            val gate = synchronized(lock) {
                if (active[pageId] !== flight) return
                flight.readGate
            } ?: return
            gate.await()
        }
    }

    private fun notifyPreview(pageId: PageId, flight: Flight, preview: PageTransferPreview) {
        val observers = synchronized(lock) {
            if (active[pageId] !== flight || !flight.acceptingWaiters) return
            flight.latestPreview = preview
            flight.previewObservers.toList()
        }
        observers.forEach { observer -> notifySafely { observer(preview) } }
    }

    private fun notifySafely(observer: () -> Unit) {
        runCatching(observer)
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun logTransfer(message: String) {
        runCatching { Log.d(PAGE_TRANSFER_TAG, message) }
    }

    private fun stronger(
        current: PageFetchPriority,
        incoming: PageFetchPriority,
    ): PageFetchPriority = if (incoming.ordinal < current.ordinal) incoming else current

    private fun release(pageId: PageId, flight: Flight, speculative: Boolean) {
        val cancel = synchronized(lock) {
            if (active[pageId] !== flight) return
            if (speculative) {
                flight.speculativeWaiters -= 1
            } else {
                flight.demandWaiters -= 1
            }
            require(flight.speculativeWaiters >= 0 && flight.demandWaiters >= 0) {
                "Page flight waiter count became negative"
            }
            if (flight.waiterCount == 0 && !flight.deferred.isCompleted) {
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

private class FlightControlledPageByteStream(
    private val upstream: PageByteStream,
    private val beforeRead: suspend () -> Unit,
) : PageByteStream {
    override suspend fun awaitReadable() = beforeRead()

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        return upstream.readAtMost(destination, offset, byteCount)
    }

    override fun promote(priority: PageFetchPriority) = upstream.promote(priority)

    override fun close() = upstream.close()
}

private val PAGE_TRANSFER_LOGGER = Logger.getLogger("PageTransfer")
private const val PAGE_TRANSFER_TAG = "PageTransfer"
