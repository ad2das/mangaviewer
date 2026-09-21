package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineTileWork
import ml.melun.mangaview.engine.runtime.EngineRenderRuntime
import ml.melun.mangaview.engine.runtime.EngineRenderRuntimeDiagnosticSnapshot
import ml.melun.mangaview.engine.runtime.EngineSessionRuntime
import ml.melun.mangaview.engine.runtime.EngineSessionRuntimeDiagnosticSnapshot
import ml.melun.mangaview.engine.runtime.EngineTilePlanner
import ml.melun.mangaview.engine.runtime.NoopEngineWorkTracer
import ml.melun.mangaview.engine.session.EngineSession
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

internal data class EngineViewerRuntimeDiagnosticSnapshot(
    val capturedAtNanos: Long,
    val content: EngineSessionRuntimeDiagnosticSnapshot,
    val render: EngineRenderRuntimeDiagnosticSnapshot,
)

/** Android surface/input adapter for the new session, work and GL owners. */
internal class EngineViewerRuntime(
    context: Context,
    private val scope: CoroutineScope,
    coordinator: WorkCoordinatorPort,
    source: EngineSessionWork,
    private val positions: EnginePositionPort,
    episodeId: EpisodeId,
    initialViewport: EngineViewport,
    decodeDispatcher: CoroutineDispatcher,
    private val reportSnapshot: (EngineRuntimeSnapshot) -> Unit,
    private val reportPresented: (EngineSurfacePresentation) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val reportGestureBoundary: (Boolean, Long) -> Unit = { _, _ -> },
    private val reportMotionFrame: (Long, Long) -> Unit = { _, _ -> },
    private val inputObservations: EngineInputObservations = EngineInputObservations(),
    private val reportRendererClosed: (Long, Long, Long) -> Unit = { _, _, _ -> },
    preparedRenderer: EngineSurfaceOwner? = null,
) : ViewerSurfaceSink {
    private val main = Handler.createAsync(Looper.getMainLooper())
    private val memory = ViewerMemoryEnvironment(context) { }
    private val budget = DeviceMemoryBudget.fromPhysicalRam(memory.totalPhysicalBytes)
    private val frameProvenance = FrameWorkProvenanceLedger()
    private val saveMutex = Mutex()
    private val closeDone = CompletableDeferred<Unit>()
    private var closing = false
    private var surfaceGeneration = 0L
    private var inputSequence = 0L
    private var gesture = 1L
    private var lastSaved: Pair<SourceAnchor, Long>? = null
    private var submittedPosition: Pair<SourceAnchor, Long>? = null
    private var autosave: Job? = null
    private val renderer: EngineSurfaceOwner = (preparedRenderer ?: EngineSurfaceOwner(budget.glResidentBytes,
        {}, {}, {}, bufferedCompositor = android.os.Build.VERSION.SDK_INT >= 31)).also { it.bind(EngineSurfaceCallbacks(
        { value -> onMain { onPresented(value) } }, { error -> onMain { reportFailure(error) } },
        { onMain { if (!closing) { frameProvenance.noteRecovery(System.nanoTime()); graphics.rendererChanged(); forceGraphicsFrame() } } },
        { onMain { if (!closing) { disableGraphics(); surface.rendererUnavailable() } } },
        { value -> onMain { onSubmitted(value) } })) }
    private val reducer = EngineSession(nextSession.incrementAndGet(), episodeId, initialViewport, System::nanoTime)
    private val content: EngineSessionRuntime = EngineSessionRuntime(scope, coordinator, reducer, source, episodeId,
        { value, receipts -> inputObservations.record(value.session, receipts); onContent(value) },
        { _, failure -> reportFailure(failure) }, awaitInitialPresentation = true)
    private val refreshQueue = HandlerRefreshMessageQueue(Handler.createAsync(Looper.getMainLooper()))
    private val refreshScheduler = HandlerRefreshScheduler(refreshQueue) { onGraphicsRefreshFrame() }
    private val graphics: EngineRenderRuntime = EngineRenderRuntime(scope, coordinator,
        EngineTilePlanner(budget.glResidentBytes, preparationViewports = 12, tracer = NoopEngineWorkTracer),
        EngineTileWork(NativeEngineImageDecoder(), decodeDispatcher, renderer), renderer, content::pageRequest,
        { scene -> renderer.offer(frameProvenance.attachTicket(scene)) }, renderer::clearScene, { _, failure -> reportFailure(failure) },
        waitForCompleteViewport = false, reportSceneFailure = reportFailure,
        frameWorkObserver = FrameWorkObserver { kind, atNanos -> frameProvenance.noteWorkTrigger(kind, atNanos) },
        refreshScheduler = refreshScheduler,
        tracer = NoopEngineWorkTracer)
    val surface = ViewerSurfaceHost(context, this)

    init { disableGraphics() }

    fun open() { if (!closing) content.open() }
    fun snapshot(): EngineRuntimeSnapshot = content.snapshot
    fun diagnosticSnapshot() = EngineViewerRuntimeDiagnosticSnapshot(
        System.nanoTime(), content.diagnosticSnapshot(), graphics.diagnosticSnapshot())
    fun userInputRevisionSnapshot(): Long = content.snapshot.session.inputRevision
    suspend fun captureNextFrame(top: Int, bottom: Int) = renderer.captureNextFrame(top, bottom)
    suspend fun captureNextViewportFrame() = renderer.captureNextViewportFrame()

    fun chromeSnapshot(): ViewerChromeState? {
        val position = readingPosition() ?: return null
        val manifest = content.snapshot.plans[position.pageId.episodeId]?.manifest ?: return null
        val index = manifest.pages.indexOfFirst { it.id == position.pageId }
        if (index < 0) return null
        return ViewerChromeState(manifest.id, manifest.title, index + 1, manifest.pages.size, position,
            manifest.previousEpisodeId, manifest.nextEpisodeId, content.snapshot.session.splitMode)
    }

    fun bookmarkSnapshot(): Pair<SourceAnchor, ReadingPosition>? = position()?.let {
        it.first to ReadingPosition(it.first.pageId, it.second, it.first.viewportOffsetUnits)
    }

    fun enterForeground() {
        if (closing) return
        content.foreground(true)
        surface.enterForeground()
    }

    fun enterBackground() {
        if (closing) return
        surface.cancelMotion()
        disableGraphics()
        content.foreground(false)
        surface.enterBackground()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { flushPresentationCallbacks(); persist(position()) } catch (failure: Throwable) { reportFailure(failure) }
        }
    }

    fun retryFailures() { content.retryFailures(); graphics.retryFailures() }

    /** Session-only split reading; saved positions still address original page rows. */
    fun setSplitMode(enabled: Boolean) {
        if (!closing) content.setSplitMode(enabled)
    }

    suspend fun close() = withContext(NonCancellable) {
        if (!closing) {
            surface.cancelMotion()
            surface.enterBackground()
            closing = true
            autosave?.cancelAndJoin()
            surfaceGeneration++
            val failures = mutableListOf<Throwable>()
            coroutineScope {
                val visual = async { runCatching { graphics.close() } }
                val source = async { runCatching { content.close() } }
                visual.await().exceptionOrNull()?.let(failures::add)
                source.await().exceptionOrNull()?.let(failures::add)
            }
            try { renderer.close() } catch (failure: Throwable) { failures += failure }
            try { memory.close() } catch (failure: Throwable) { failures += failure }
            try { flushPresentationCallbacks(); persist(position()) } catch (failure: Throwable) { failures += failure }
            if (failures.isEmpty()) try {
                inputObservations.seal(content.snapshot.session, inputSequence, System.nanoTime())
                reportRendererClosed(renderer.rendererId, checkNotNull(renderer.closedSubmissionCount), System.nanoTime())
            } catch (failure: Throwable) { failures += failure }
            if (failures.isEmpty()) closeDone.complete(Unit) else {
                val failure = failures.first()
                failures.drop(1).filter { it !== failure }.forEach(failure::addSuppressed)
                closeDone.completeExceptionally(failure)
            }
        }
        closeDone.await()
    }

    override fun viewportChanged(viewport: Viewport) {
        if (!closing) content.resize(EngineViewport(Math.toIntExact(viewport.width.units / 1024),
            Math.toIntExact(viewport.height.units / 1024)))
    }

    override fun surfaceAvailable(surface: Surface, width: Int, height: Int, refreshRate: Float,
        reportAttached: (Boolean) -> Unit) {
        val generation = ++surfaceGeneration
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val attachStartedAtNanos = System.nanoTime()
            try {
                val attached = renderer.attach(surface, width, height, refreshRate)
                android.util.Log.d("NtkFrame", "renderer-attach attached=$attached elapsedMs=" +
                    ((System.nanoTime() - attachStartedAtNanos).coerceAtLeast(0L) / 1_000_000L))
                if (!closing && generation == surfaceGeneration) {
                    if (attached) {
                        graphics.enabled(true)
                        graphics.update(content.snapshot)
                        forceGraphicsFrame()
                    }
                    reportAttached(attached)
                } else reportAttached(false)
            } catch (failure: Throwable) {
                if (!closing && generation == surfaceGeneration) reportFailure(failure)
                reportAttached(false)
            }
        }
    }

    override fun surfaceAttachExhausted() {
        if (!closing) reportFailure(IllegalStateException("Viewer surface attach failed"))
    }

    override fun surfaceUnavailable() {
        surfaceGeneration++
        if (closing) return
        frameProvenance.clear()
        disableGraphics()
        // SurfaceHolder may release the buffer queue as soon as its callback returns.
        // The GL owner must finish its current draw and detach before that return.
        // detach only uses the GL dispatcher; it never waits for a main-thread callback.
        try { runBlocking { renderer.detach() } } catch (failure: Throwable) { reportFailure(failure) }
    }

    override fun userScroll(delta: FixedPx, velocityPixelsPerSecond: Float, frameTimeNanos: Long,
        frameTimelineVsyncId: Long, expectedPresentationTimeNanos: Long): Boolean {
        if (closing) return false
        return userScrollStep(delta, velocityPixelsPerSecond, frameTimeNanos, frameTimelineVsyncId,
            expectedPresentationTimeNanos)
    }

    private fun userScrollStep(delta: FixedPx, velocityPixelsPerSecond: Float, frameTimeNanos: Long,
        frameTimelineVsyncId: Long, expectedPresentationTimeNanos: Long): Boolean {
        if (closing) return false
        val sequence = ++inputSequence
        if (frameProvenance.isTracking) {
            val before = content.snapshot.session
            frameProvenance.armInputFrame(frameTimelineVsyncId, expectedPresentationTimeNanos, frameTimeNanos,
                sequence, before.sessionId, before.inputRevision, before.movementRevision)
        }
        val sample = InputSample(sequence, gesture, frameTimeNanos, delta.units)
        val update = content.input(sample)
        // The gesture step just revealed its pixels; rebuild the scene exactly once for this step.
        graphics.refreshInteractionFrame()
        frameProvenance.finishInputFrame(update.receipts.any { it.outcome == InputOutcome.DEFERRED })
        val after = update.snapshot
        viewerInputTrace({
            engineInputTraceName(inputSequence, gesture, frameTimeNanos,
                after.inputRevision, after.movementRevision, after.sessionId)
        }) { }
        return update.receipts.any { it.appliedScreenUnits != 0L || it.outcome == InputOutcome.DEFERRED }
    }

    /** Marks the native present fence that carries this frame identity, joined by inputRevision. */
    private fun onPresented(value: EngineSurfacePresentation) {
        viewerInputTrace({ enginePresentTraceName(value.identity, value.rendererId) }) {
            viewerInputTrace({
                enginePresentFenceTraceName(value.identity, value.timestampKind.ordinal,
                    value.timestampNanos, value.eglFrameId)
            }) {
                reportPresented(value)
            }
        }
    }

    override fun interactionChanged(active: Boolean, atNanos: Long) {
        if (active) gesture++
        // A drag or fling owns every frame until its tail ends; the render owner can then keep its
        // speculative horizon instead of re-walking the same neighbours on each moved viewport.
        // The inline flag is set before the render owner sees the boundary: on release the owner
        // delivers any work result it deferred during the gesture through the ordinary queue, and
        // that message must not run inline inside this boundary call.
        refreshQueue.inlineWhileInteracting = active
        if (!closing) graphics.interactionActive(active)
        // The owner-thread drain submits frames itself, so while a gesture paces the display it must
        // be delivered behind the motion callback rather than ahead of it.
        renderer.interactionActive(active)
        reportGestureBoundary(active, atNanos)
    }
    override fun motionFrame(sequence: Long, atNanos: Long) = reportMotionFrame(sequence, atNanos)

    private fun onContent(value: EngineRuntimeSnapshot) {
        if (closing) return
        frameProvenance.bindInputFrame(value.session.sessionId, value.session.inputRevision,
            value.session.movementRevision)
        traceEngineWork("engine_graphics_update") { graphics.update(value) }
        reportSnapshot(value)
    }

    /** Queued engine drain for one main-looper refresh message, not a vsync callback; the
     * coalesced scene work happens inside. */
    private fun onGraphicsRefreshFrame() {
        traceEngineWork("engine_graphics_frame") { graphics.refreshOnFrame() }
    }

    /** Inline drain that retires pixels before detach; a queued frame would arrive too late. */
    private fun disableGraphics() =
        traceEngineWork("engine_graphics_frame_force") { graphics.enabled(false) }

    /** Inline drain for attach and renderer recovery, so the first scene never waits a frame. */
    private fun forceGraphicsFrame() =
        traceEngineWork("engine_graphics_frame_force") { graphics.refreshNow() }

    private fun onSubmitted(value: EngineSurfaceScene) {
        submittedSourcePosition(value)?.let { submittedPosition = it }
        if (!closing) {
            if (value.completeCoverage) content.initialViewportSubmitted(value.generation)
            reportSnapshot(content.snapshot)
            if (autosave == null && submittedPosition != lastSaved) {
                autosave = scope.launch {
                    try { delay(1_000); persist(position()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { reportFailure(failure) }
                    finally { autosave = null }
                }
            }
        }
    }

    private fun position(): Pair<SourceAnchor, Long>? = submittedPosition

    private suspend fun persist(value: Pair<SourceAnchor, Long>?) {
        if (value == null) return
        saveMutex.withLock {
            if (lastSaved != value) { positions.save(value.first, value.second); lastSaved = value }
        }
    }

    fun readingPosition(): ReadingPosition? = position()?.let { ReadingPosition(it.first.pageId, it.second, it.first.viewportOffsetUnits) }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private suspend fun flushPresentationCallbacks() = suspendCancellableCoroutine<Unit> { continuation ->
        // Use the same ordinary Handler queue as renderer notifications, including across sync barriers.
        check(main.post { continuation.resume(Unit) }) { "Main callback queue is unavailable" }
    }
    private companion object { val nextSession = AtomicLong() }
}

/** Called only for a successful buffer submission; later logical movement must not change resume position. */
internal fun submittedSourcePosition(scene: EngineSurfaceScene): Pair<SourceAnchor, Long>? {
    if (!scene.completeCoverage) return null
    val anchor = scene.anchor ?: return null
    val dimensions = scene.anchorDimensions ?: return null
    val sourceYQ32 = if (scene.splitMode) foldSplitSource(anchor.sourceYQ32, dimensions) else anchor.sourceYQ32
    val offset = BigInteger.valueOf(sourceYQ32).multiply(BigInteger.valueOf(scene.viewport.widthPx.toLong()))
        .multiply(BigInteger.valueOf(1024)).divide(BigInteger.valueOf(dimensions.widthPx.toLong())
            .multiply(BigInteger.valueOf(SourceAnchor.SOURCE_UNITS_PER_PIXEL))).toLongExact()
    return anchor.copy(sourceYQ32 = sourceYQ32) to offset
}

/** Split reading is session-only: a second-half source row folds back onto the original page row. */
internal fun foldSplitSource(sourceYQ32: Long, dimensions: PageDimensions): Long {
    if (!SpreadPages.isSpread(dimensions)) return sourceYQ32
    val half = dimensions.heightPx.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL
    return if (sourceYQ32 >= half) sourceYQ32 - half else sourceYQ32
}

/** Successful submitted source must overlap what the exactly matching session currently exposes. */
internal fun releasesStartupInput(
    value: EngineSurfacePresentation,
    state: EngineSessionSnapshot,
): Boolean {
    if (!value.swapSucceeded || value.scene.sessionId != state.sessionId ||
        value.scene.generation != state.generation ||
        value.scene.anchor != state.anchor || value.scene.viewport != state.viewport
    ) return false
    return value.scene.placements.any { placement ->
        val tile = placement.texture.tile
        val offsetRows = SpreadPages.displayRowOffset(tile.dimensions, tile.cropLeftPx,
            tile.cropRightPx).toLong()
        val tileTopQ32 = (tile.sourceTop.toLong() + offsetRows) * SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val tileBottomQ32 = (tile.sourceBottom.toLong() + offsetRows) * SourceAnchor.SOURCE_UNITS_PER_PIXEL
        state.visibleRegions.any { region ->
            region.pageId == tile.pageId && tileTopQ32 < region.sourceBottomQ32 &&
                tileBottomQ32 > region.sourceTopQ32
        }
    }
}
