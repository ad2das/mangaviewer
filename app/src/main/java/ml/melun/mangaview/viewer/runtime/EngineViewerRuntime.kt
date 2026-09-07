package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
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
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineTileWork
import ml.melun.mangaview.engine.runtime.EngineRenderRuntime
import ml.melun.mangaview.engine.runtime.EngineSessionRuntime
import ml.melun.mangaview.engine.runtime.EngineTilePlanner
import ml.melun.mangaview.engine.session.EngineSession
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

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
) : ViewerSurfaceSink {
    private val main = Handler(Looper.getMainLooper())
    private val memory = ViewerMemoryEnvironment(context) { }
    private val budget = DeviceMemoryBudget.fromPhysicalRam(memory.totalPhysicalBytes)
    private val saveMutex = Mutex()
    private val closeDone = CompletableDeferred<Unit>()
    private var closing = false
    private var surfaceGeneration = 0L
    private var inputSequence = 0L
    private var gesture = 1L
    private var lastSaved: Pair<SourceAnchor, Long>? = null
    private val renderer: EngineSurfaceOwner = EngineSurfaceOwner(budget.glResidentBytes,
        { value -> onMain { reportPresented(value) } }, { error -> onMain { reportFailure(error) } },
        { onMain { if (!closing) graphics.rendererChanged() } },
        { onMain { if (!closing) { graphics.enabled(false); surface.rendererUnavailable() } } })
    private val reducer = EngineSession(nextSession.incrementAndGet(), episodeId, initialViewport, System::nanoTime)
    private val content: EngineSessionRuntime = EngineSessionRuntime(scope, coordinator, reducer, source, episodeId,
        { value, receipts -> inputObservations.record(value.session, receipts); onContent(value) },
        { _, failure -> reportFailure(failure) })
    private val graphics: EngineRenderRuntime = EngineRenderRuntime(scope, coordinator, EngineTilePlanner(budget.glResidentBytes),
        EngineTileWork(NativeEngineImageDecoder(), decodeDispatcher, renderer), renderer, content::pageRequest,
        renderer::offer, renderer::clearScene, { _, failure -> reportFailure(failure) })
    val surface = ViewerSurfaceHost(context, this)

    init { graphics.enabled(false) }

    fun open() { if (!closing) content.open() }
    fun snapshot(): EngineRuntimeSnapshot = content.snapshot
    fun userInputRevisionSnapshot(): Long = content.snapshot.session.inputRevision
    suspend fun captureNextFrame(top: Int, bottom: Int) = renderer.captureNextFrame(top, bottom)
    suspend fun captureNextViewportFrame() = renderer.captureNextViewportFrame()

    fun chromeSnapshot(): ViewerChromeState? {
        val position = readingPosition() ?: return null
        val manifest = content.snapshot.plans[position.pageId.episodeId]?.manifest ?: return null
        val index = manifest.pages.indexOfFirst { it.id == position.pageId }
        if (index < 0) return null
        return ViewerChromeState(manifest.id, manifest.title, index + 1, manifest.pages.size, position,
            manifest.previousEpisodeId, manifest.nextEpisodeId)
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
        val saved = position()
        graphics.enabled(false)
        content.foreground(false)
        surface.enterBackground()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { persist(saved) } catch (failure: Throwable) { reportFailure(failure) } }
    }

    fun retryFailures() { content.retryFailures(); graphics.retryFailures() }

    suspend fun close() = withContext(NonCancellable) {
        if (!closing) {
            surface.cancelMotion()
            surface.enterBackground()
            closing = true
            surfaceGeneration++
            val saved = position()
            val failures = mutableListOf<Throwable>()
            coroutineScope {
                val visual = async { runCatching { graphics.close() } }
                val source = async { runCatching { content.close() } }
                visual.await().exceptionOrNull()?.let(failures::add)
                source.await().exceptionOrNull()?.let(failures::add)
            }
            try { persist(saved) } catch (failure: Throwable) { failures += failure }
            try { renderer.close() } catch (failure: Throwable) { failures += failure }
            try { memory.close() } catch (failure: Throwable) { failures += failure }
            if (failures.isEmpty()) try {
                flushPresentationCallbacks()
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

    override fun surfaceAvailable(surface: Surface, width: Int, height: Int, refreshRate: Float) {
        val generation = ++surfaceGeneration
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val attached = renderer.attach(surface, width, height, refreshRate)
                if (!closing && generation == surfaceGeneration) {
                    check(attached) { "Viewer surface attach failed" }
                    graphics.enabled(true)
                    graphics.update(content.snapshot)
                }
            } catch (failure: Throwable) { if (!closing && generation == surfaceGeneration) reportFailure(failure) }
        }
    }

    override fun surfaceUnavailable() {
        surfaceGeneration++
        if (closing) return
        graphics.enabled(false)
        // SurfaceHolder may release the buffer queue as soon as its callback returns.
        // The GL owner must finish its current draw and detach before that return.
        // detach only uses the GL dispatcher; it never waits for a main-thread callback.
        try { runBlocking { renderer.detach() } } catch (failure: Throwable) { reportFailure(failure) }
    }

    override fun userScroll(delta: FixedPx, velocityPixelsPerSecond: Float, frameTimeNanos: Long,
        frameTimelineVsyncId: Long, expectedPresentationTimeNanos: Long): Boolean {
        if (closing) return false
        val update = content.input(InputSample(++inputSequence, gesture, frameTimeNanos, delta.units))
        return update.receipts.any { it.appliedScreenUnits != 0L || it.outcome == InputOutcome.DEFERRED }
    }

    override fun interactionChanged(active: Boolean, atNanos: Long) {
        if (active) gesture++
        reportGestureBoundary(active, atNanos)
    }
    override fun motionFrame(sequence: Long, atNanos: Long) = reportMotionFrame(sequence, atNanos)

    private fun onContent(value: EngineRuntimeSnapshot) {
        if (closing) return
        graphics.update(value)
        reportSnapshot(value)
    }

    private fun position(): Pair<SourceAnchor, Long>? {
        val state = content.snapshot.session
        val anchor = state.anchor ?: return null
        val dimensions = state.anchorDimensions ?: return null
        val offset = BigInteger.valueOf(anchor.sourceYQ32).multiply(BigInteger.valueOf(state.viewport.widthPx.toLong()))
            .multiply(BigInteger.valueOf(1024)).divide(BigInteger.valueOf(dimensions.widthPx.toLong())
                .multiply(BigInteger.valueOf(SourceAnchor.SOURCE_UNITS_PER_PIXEL))).toLongExact()
        return anchor to offset
    }

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
