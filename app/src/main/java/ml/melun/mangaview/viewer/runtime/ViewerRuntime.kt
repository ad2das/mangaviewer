package ml.melun.mangaview.viewer.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.viewer.DemandPlanner
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlanner
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.PixelBudgetPlanner
import ml.melun.mangaview.viewer.PixelMemoryPolicy
import ml.melun.mangaview.viewer.ScrollController
import ml.melun.mangaview.viewer.ViewerEvent
import ml.melun.mangaview.viewer.ViewerPipelineCoordinator
import ml.melun.mangaview.viewer.ViewerReducer
import ml.melun.mangaview.viewer.ViewerTelemetryPlanner
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.WorkScheduler

internal class ViewerRuntime(
    context: Context,
    scope: CoroutineScope,
    private val sourceDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    hardDecodeDispatcher: CoroutineDispatcher,
    warmDecodeDispatcher: CoroutineDispatcher,
    private val source: ContentSource,
    repository: PageRepository,
    private val episodeId: EpisodeId,
    private val loadPosition: suspend () -> ReadingPosition?,
    private val persistPosition: (ReadingPosition) -> Unit,
    initialViewport: Viewport,
    reportGestureBoundary: (Boolean, Long) -> Unit,
    reportMotionFrame: (sequence: Long, frameTimeNanos: Long) -> Unit,
    private val reportOpened: () -> Unit,
    private val reportPresentedFrame: (NativePresentationEvidence) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(scope.coroutineContext + runtimeJob)
    private val pendingLock = Any()
    private val memoryPolicy = PixelMemoryPolicy()
    private val telemetryPlanner = ViewerTelemetryPlanner()
    private val startup = ViewerStartupTracker()
    private val reducer = ViewerReducer(
        scrollController = ScrollController(),
        workScheduler = WorkScheduler(DemandPlanner(), memoryPolicy),
        pixelBudgetPlanner = PixelBudgetPlanner(memoryPolicy),
    )
    private val tileStore = HardwareTileStore()
    private val tilePool = NativeTilePool(tileStore)
    private lateinit var coordinator: ViewerPipelineCoordinator
    private lateinit var workExecutor: ViewerWorkExecutor
    private val renderPort = CanvasRenderPort(
        tiles = tileStore,
        recycle = { pixel -> workExecutor.recycle(pixel) },
        presented = { evidence ->
            if (evidence.readableActualContent) {
                nearestVisiblePixelPageId()?.let { pageId ->
                    startup.markPresented(
                        pageId,
                        evidence.submittedAtNanos,
                        evidence.presentedNanos,
                    )
                }
            }
            reportPresentedFrame(evidence)
        },
        fatal = { message -> reportFailure(IllegalStateException(message)) },
    )
    val surface = ViewerCanvasView(
        context,
        renderPort,
        ::routeEvent,
        reportGestureBoundary,
        reportMotionFrame,
    )
    private var pendingViewport = initialViewport
    private val loading = PreManifestLoadingController(
        initialViewport = initialViewport,
        planner = LoadingFramePlanner(),
    )
    private var pendingSurfaceAttached = false
    private var pendingVelocity = 0L
    private var pendingInteractionActive = false
    private var foreground = true
    private var opened = false
    private val openStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastPersistedPosition: ReadingPosition? = null

    init {
        renderPort.bind(surface)
        workExecutor = ViewerWorkExecutor(
            scope = runtimeScope,
            ioDispatcher = ioDispatcher,
            hardDecodeDispatcher = hardDecodeDispatcher,
            warmDecodeDispatcher = warmDecodeDispatcher,
            source = source,
            repository = repository,
            tilePool = tilePool,
            eventSink = ::routeEvent,
            retirePixel = renderPort::retire,
        )
        coordinator = ViewerPipelineCoordinator(
            scope = runtimeScope,
            reducer = reducer,
            // Keep the immutable state envelope wider than the one-screen HWUI band. This lets
            // the renderer cross page boundaries without rebuilding scene membership while the
            // pixel scheduler and actual draw still remain viewport-local.
            framePlanner = FramePlanner(overscanScreenfuls = 2),
            renderPort = renderPort,
            workPort = workExecutor,
            // Scroll reduction and native submission share the display thread. This keeps the
            // unbounded event channel from turning a burst of real input into delayed movement
            // after the finger has stopped; network/source work remains on its own dispatcher.
            actorDispatcher = renderPort.dispatcher,
            isActorThread = { android.os.Looper.myLooper() === android.os.Looper.getMainLooper() },
        )
        observeLifecycleState()
    }

    fun open() {
        if (!openStarted.compareAndSet(false, true) || closed.get()) return
        startup.markOpenStarted(System.nanoTime())
        runtimeScope.launch {
            try {
                val (prepared, initialPosition) = coroutineScope {
                    val saved = async(ioDispatcher) { loadPosition() }
                    val loaded = withContext(sourceDispatcher) {
                        source.prepare(episodeId, PreparationIntent.INITIAL_VIEW)
                        val manifest = source.manifest(episodeId)
                        val width = synchronized(pendingLock) { pendingViewport.width }
                        manifest to reducer.prepareEpisode(manifest, width)
                    }
                    loaded to saved.await()
                }
                openManifest(
                    prepared.first,
                    prepared.second,
                    initialPosition?.takeIf { it.pageId.episodeId == episodeId },
                )
            } catch (failure: Throwable) {
                if (!closed.get()) reportFailure(failure)
            }
        }
    }

    fun enterForeground() {
        if (closed.get()) return
        workExecutor.resumeDecodes()
        surface.enterForeground()
        val shouldPost = synchronized(pendingLock) {
            foreground = true
            opened
        }
        if (shouldPost) coordinator.post(ViewerEvent.ReturnForeground(System.nanoTime()))
    }

    fun userInputRevisionSnapshot(): Long = coordinator.state.value?.userInputRevision
        ?: synchronized(pendingLock) { if (loading.hasDisplacedInput) 1L else 0L }

    fun telemetrySnapshot(): ViewerTelemetrySnapshot? = coordinator.state.value?.let { state ->
        telemetryPlanner.snapshot(state, System.nanoTime())
    }

    fun startupTimingSnapshot(): ViewerStartupTiming? = startup.snapshot()

    fun chromeSnapshot(): ViewerChromeState? {
        val state = coordinator.state.value ?: return null
        val position = state.scroll.anchor
        val manifest = state.manifests.firstOrNull { it.id == position.pageId.episodeId }
            ?: return null
        val page = state.pages[position.pageId]?.spec ?: return null
        return ViewerChromeState(
            episodeId = manifest.id,
            title = manifest.title,
            pageNumber = page.ordinal + 1,
            pageCount = manifest.pages.size,
            position = position,
            previousEpisodeId = manifest.previousEpisodeId,
            nextEpisodeId = manifest.nextEpisodeId,
        )
    }

    fun enterBackground() {
        if (closed.get()) return
        workExecutor.pauseDecodes()
        surface.enterBackground()
        val shouldPost = synchronized(pendingLock) {
            foreground = false
            opened
        }
        if (shouldPost) coordinator.post(ViewerEvent.EnterBackground(System.nanoTime()))
        workExecutor.compact()
    }

    fun close(afterClose: () -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        persistCurrentPosition()
        surface.cancelMotion()
        coordinator.close()
        runtimeJob.cancel()
        val closingEdges = AtomicInteger(2)
        val closePool = {
            if (closingEdges.decrementAndGet() == 0) {
                tilePool.close()
                afterClose()
            }
        }
        renderPort.close(closePool)
        workExecutor.shutdown(closePool)
    }

    private fun openManifest(
        manifest: ml.melun.mangaview.core.EpisodeManifest,
        prepared: ml.melun.mangaview.viewer.PreparedViewerEpisode,
        initialPosition: ReadingPosition?,
    ) {
        startup.markManifestReady(System.nanoTime())
        synchronized(pendingLock) {
            if (closed.get()) return
            PendingOpen(
                viewport = pendingViewport,
                surfaceAttached = pendingSurfaceAttached,
                scroll = loading.offset,
                velocity = pendingVelocity,
                interactionActive = pendingInteractionActive,
                foreground = foreground,
            ).also { captured ->
                coordinator.post(ViewerEvent.OpenEpisode(
                    generation = 1L,
                    manifest = manifest,
                    viewport = captured.viewport,
                    atNanos = System.nanoTime(),
                    initialScroll = captured.scroll,
                    initialVelocityUnitsPerSecond = captured.velocity,
                    initialPosition = initialPosition,
                    initialInteractionActive = captured.interactionActive,
                    preparedEpisode = prepared,
                ))
                if (captured.surfaceAttached) coordinator.post(
                    ViewerEvent.SurfaceAttachmentChanged(true, System.nanoTime()),
                )
                if (!captured.foreground) coordinator.post(
                    ViewerEvent.EnterBackground(System.nanoTime()),
                )
                opened = true
                pendingVelocity = 0L
                pendingInteractionActive = false
            }
        }
        reportOpened()
    }

    private fun routeEvent(event: ViewerEvent) {
        recordStartupEvent(event)
        val route = synchronized(pendingLock) {
            if (closed.get()) return
            updatePendingTopology(event)
            PendingRoute(opened, if (opened) null else routeLoadingEvent(event))
        }
        if (route.opened) {
            coordinator.post(event)
        } else {
            route.frame?.let(renderPort::submit)
        }
    }

    private fun updatePendingTopology(event: ViewerEvent) {
        when (event) {
            is ViewerEvent.ViewportChanged -> pendingViewport = event.viewport
            is ViewerEvent.SurfaceAttachmentChanged -> pendingSurfaceAttached = event.attached
            else -> Unit
        }
    }

    private fun routeLoadingEvent(event: ViewerEvent): ml.melun.mangaview.viewer.FramePlan? =
        when (event) {
            is ViewerEvent.ViewportChanged ->
                if (loading.resize(event.viewport)) loading.frame() else null
            is ViewerEvent.SurfaceAttachmentChanged ->
                loading.frame().takeIf { event.attached }
            is ViewerEvent.UserScroll -> routeLoadingScroll(event)
            is ViewerEvent.InteractionChanged -> {
                pendingInteractionActive = event.active
                null
            }
            else -> null
        }

    private fun routeLoadingScroll(event: ViewerEvent.UserScroll): ml.melun.mangaview.viewer.FramePlan? {
        pendingVelocity = event.velocityUnitsPerSecond
        if (!loading.scrollBy(event.delta)) return null
        return loading.frame(
            event.frameTimelineVsyncId,
            event.expectedPresentationTimeNanos,
        )
    }

    private fun recordStartupEvent(event: ViewerEvent) {
        when {
            event is ViewerEvent.FetchResponseStarted ->
                startup.markResponseStarted(event.token.pageId, event.atNanos)
            event is ViewerEvent.FetchSucceeded ->
                startup.markVerified(event.token.pageId, event.atNanos)
            event is ViewerEvent.DecodeSucceeded ->
                startup.markDecoded(event.token.pageId, event.atNanos)
        }
    }

    private fun nearestVisiblePixelPageId(): ml.melun.mangaview.core.PageId? {
        val state = coordinator.state.value ?: return null
        val start = state.scroll.contentOffset.units
        val end = start + state.viewport.height.units
        val center = start + state.viewport.height.units / 2L
        return state.layout.indicesIntersecting(FixedPx(start), FixedPx(end)).mapNotNull { index ->
            val pageId = state.pageOrder[index]
            val pixel = state.pages.getValue(pageId).pixel ?: return@mapNotNull null
            val top = state.layout.topAt(index).units
            val height = state.layout.entries[index].height.units
            val nearest = pixel.tiles.mapNotNull { tile ->
                val tileTop = top + scaleWithinPage(
                    height,
                    tile.sourceTopPx,
                    pixel.dimensions.heightPx,
                )
                val tileBottom = top + scaleWithinPage(
                    height,
                    tile.sourceBottomPx,
                    pixel.dimensions.heightPx,
                )
                if (tileBottom <= start || tileTop >= end) null else when {
                    center < tileTop -> tileTop - center
                    center >= tileBottom -> center - tileBottom + 1L
                    else -> 0L
                }
            }.minOrNull() ?: return@mapNotNull null
            pageId to nearest
        }.minByOrNull { it.second }?.first
    }

    private fun scaleWithinPage(value: Long, numerator: Int, denominator: Int): Long {
        require(value >= 0L && numerator in 0..denominator && denominator > 0)
        return (value / denominator) * numerator +
            (value % denominator) * numerator / denominator
    }

    private fun observeLifecycleState() {
        runtimeScope.launch {
            coordinator.state.filterNotNull()
                .filter { it.visibility == ml.melun.mangaview.viewer.ViewerVisibility.BACKGROUND }
                .map { it.scroll.anchor }
                .distinctUntilChanged()
                .collect(::persistIfChanged)
        }
    }

    private fun persistCurrentPosition() {
        coordinator.state.value?.scroll?.anchor?.let(::persistIfChanged)
    }

    private fun persistIfChanged(position: ReadingPosition) {
        if (lastPersistedPosition == position) return
        lastPersistedPosition = position
        persistPosition(position)
    }

    private data class PendingOpen(
        val viewport: Viewport,
        val surfaceAttached: Boolean,
        val scroll: FixedPx,
        val velocity: Long,
        val interactionActive: Boolean,
        val foreground: Boolean,
    )

    private data class PendingRoute(
        val opened: Boolean,
        val frame: ml.melun.mangaview.viewer.FramePlan?,
    )
}
