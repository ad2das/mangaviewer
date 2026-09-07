package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Trace
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.content.ContentPipelineDispatchers
import ml.melun.mangaview.content.ContentPipelineEvent
import ml.melun.mangaview.content.ContentPipelineSink
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.content.ViewerContentPipeline
import ml.melun.mangaview.content.EpisodeManifestPort
import ml.melun.mangaview.content.adaptiveResidentBudgetBytes
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SessionChange
import ml.melun.mangaview.viewer.session.SessionEffect
import ml.melun.mangaview.viewer.session.ViewerSession
import ml.melun.mangaview.viewer.session.VisualBand

internal class SessionViewerRuntime(
    context: Context,
    scope: CoroutineScope,
    private val sourceDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    hardDecodeDispatcher: CoroutineDispatcher,
    warmDecodeDispatcher: CoroutineDispatcher,
    uploadDispatcher: CoroutineDispatcher,
    private val source: ContentSource,
    rawPages: RawPagePort,
    private val episodeId: EpisodeId,
    private val loadPosition: suspend () -> ReadingPosition?,
    private val persistPosition: (ReadingPosition) -> Unit,
    initialViewport: Viewport,
    private val reportGestureBoundary: (Boolean, Long) -> Unit,
    private val reportMotionFrame: (Long, Long) -> Unit,
    private val reportOpened: () -> Unit,
    private val reportPresentedFrame: (NativePresentationEvidence) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val reportPresentedRegions: (List<PresentedImageRegion>) -> Unit = {},
    private val cachedResume: ViewerCachedResume? = null,
) : ViewerSurfaceSink {
    private val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(scope.coroutineContext + runtimeJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = ViewerSession(initialViewport)
    private val startup = ViewerStartupTracker()
    private val telemetry = SessionTelemetryPlanner(NETWORK_LIMIT)
    private val presentationMapper = ViewerPresentationMapper()
    private val renderer: OwnedSurfaceRenderer = OwnedSurfaceRenderer(
        ::presented, ::fail, reportInvalidated = ::rendererInvalidated,
    )
    private val uploader = OwnedTextureUploadPort(renderer)
    private val memory: ViewerMemoryEnvironment = ViewerMemoryEnvironment(context, ::memoryPressure)
    private val pipeline: ViewerContentPipeline = ViewerContentPipeline(
        runtimeScope.coroutineContext,
        ContentPipelineDispatchers(
            sourceDispatcher,
            hardDecodeDispatcher,
            warmDecodeDispatcher,
            uploadDispatcher,
        ),
        cachedResume ?: rawPages,
        NativeCpuDecodePort(),
        uploader,
        ContentPipelineSink(::contentEvent),
        networkLimit = NETWORK_LIMIT,
        episodeManifests = EpisodeManifestPort(::loadForwardManifest),
        residentMemoryBudgetBytes = adaptiveResidentBudgetBytes(memory.totalPhysicalBytes),
    )
    val surface = ViewerSurfaceHost(context, this)
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastPersisted: ReadingPosition? = null

    fun open() {
        if (!opened.compareAndSet(false, true) || closed.get()) return
        startup.markOpenStarted(System.nanoTime())
        runtimeScope.launch {
            try {
                val (manifest, saved) = coroutineScope {
                    val position = async(ioDispatcher) { loadPosition() }
                    val pages = async(sourceDispatcher) {
                        cachedResume?.open(episodeId) ?: run {
                            source.prepare(episodeId, PreparationIntent.INITIAL_VIEW)
                            source.manifest(episodeId)
                        }
                    }
                    pages.await() to position.await()
                }
                if (closed.get()) return@launch
                startup.markManifestReady(System.nanoTime())
                cachedResume?.manifestResolved(manifest)
                pipeline.setRendererEpoch(renderer.rendererEpoch)
                pipeline.registerManifest(session.state.generation, manifest)
                process(session.savedPositionResolved(saved?.takeIf {
                    it.pageId.episodeId == episodeId
                }))
                process(session.initialManifestResolved(manifest))
                reportOpened()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    fun enterForeground() {
        if (closed.get()) return
        surface.enterForeground()
        runtimeScope.launch { pipeline.setForeground(true) }
        process(session.enterForeground())
    }

    fun enterBackground() {
        if (closed.get()) return
        surface.cancelMotion()
        process(session.enterBackground())
        persistCurrentPosition()
        runtimeScope.launch { pipeline.setForeground(false) }
        surface.enterBackground()
    }

    fun close(afterClose: () -> Unit = {}) {
        if (closed.get()) return
        surface.cancelMotion()
        if (!closed.compareAndSet(false, true)) return
        persistCurrentPosition()
        memory.close()
        runtimeScope.launch {
            try {
                pipeline.closeAndJoin()
            } finally {
                try {
                    withContext(NonCancellable + ioDispatcher) { cachedResume?.close() }
                } catch (failure: Exception) {
                    reportFailure(failure)
                } finally {
                    renderer.closeAsync {
                        runtimeJob.cancel()
                        afterClose()
                    }
                }
            }
        }
    }

    fun userInputRevisionSnapshot(): Long = session.state.userInputRevision

    fun resourceSnapshot(): ml.melun.mangaview.content.ContentPipelineSnapshot = pipeline.currentSnapshot()

    fun telemetrySnapshot(): ViewerTelemetrySnapshot? = telemetry.snapshot(
        session.state,
        pipeline.currentSnapshot(),
        System.nanoTime(),
    )

    fun startupTimingSnapshot(): ViewerStartupTiming? = startup.snapshot()

    fun chromeSnapshot(): ViewerChromeState? {
        val position = session.positionForPersistence() ?: return null
        val manifest = session.state.timeline.episodes.firstOrNull {
            it.manifest.id == position.pageId.episodeId
        }?.manifest ?: return null
        return ViewerChromeState(
            manifest.id,
            manifest.title,
            manifest.pages.indexOfFirst { it.id == position.pageId }.coerceAtLeast(0) + 1,
            manifest.pages.size,
            position,
            manifest.previousEpisodeId,
            manifest.nextEpisodeId,
        )
    }

    override fun viewportChanged(viewport: Viewport) {
        if (!closed.get()) process(session.viewportChanged(viewport))
    }

    override fun surfaceAvailable(
        surface: Surface,
        width: Int,
        height: Int,
        refreshRate: Float,
    ) {
        if (closed.get()) return
        renderer.attach(surface, width, height, refreshRate) { attachment ->
            if (!attachment.attached || closed.get()) return@attach
            refreshRenderer(attachment.rendererEpoch, attachment.invalidated)
        }
    }

    private fun rendererInvalidated(epoch: Long) = refreshRenderer(epoch, invalidated = true)

    private fun refreshRenderer(epoch: Long, invalidated: Boolean) {
        runtimeScope.launch {
            pipeline.setRendererEpoch(epoch)
            onMain {
                if (closed.get()) return@onMain
                if (invalidated) process(session.visualsInvalidated())
                process(session.surfaceAttached())
            }
        }
    }

    override fun surfaceUnavailable() {
        renderer.detach()
        if (!closed.get()) process(session.surfaceDetached())
    }

    override fun userScroll(
        delta: FixedPx,
        velocityPixelsPerSecond: Float,
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ): Boolean {
        if (closed.get()) return false
        val prior = session.state
        val change = session.applyUserInput(delta, velocityPixelsPerSecond)
        process(change, FrameTiming(frameTimelineVsyncId, expectedPresentationTimeNanos))
        return prior.scroll.contentOffset != change.state.scroll.contentOffset ||
            prior.opening.accumulatedInput != change.state.opening.accumulatedInput
    }

    override fun interactionChanged(active: Boolean, atNanos: Long) {
        reportGestureBoundary(active, atNanos)
    }

    override fun motionFrame(sequence: Long, atNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            val state = session.state
            val appliedAtNanos = System.nanoTime()
            Trace.beginSection(
                "viewer_motion_applied:$sequence:$appliedAtNanos:" +
                    "${state.userInputRevision}:${state.generation}:${renderer.rendererEpoch}",
            )
            try {
                reportMotionFrame(sequence, atNanos)
            } finally {
                Trace.endSection()
            }
        } else {
            reportMotionFrame(sequence, atNanos)
        }
    }

    private fun process(change: SessionChange, timing: FrameTiming? = null) {
        change.effects.forEach { effect ->
            when (effect) {
                is SessionEffect.DemandChanged -> pipeline.offerDemand(
                    effect.snapshot,
                    kotlin.math.ceil(change.state.viewport.width.toPixels()).toInt(),
                    kotlin.math.ceil(change.state.viewport.height.toPixels()).toInt(),
                )
                is SessionEffect.SceneChanged -> submitScene(effect.snapshot, change, timing)
                is SessionEffect.PersistPosition -> persistIfChanged(effect.position)
            }
        }
    }

    private fun submitScene(
        scene: SceneSnapshot,
        change: SessionChange,
        timing: FrameTiming?,
    ) {
        val metadata = presentationMapper.frameMetadata(change.state, scene) ?: return
        renderer.offer(
            scene,
            metadata,
            timing?.vsyncId ?: -1L,
            timing?.expectedPresentationNanos ?: 0L,
        )
    }

    private fun contentEvent(event: ContentPipelineEvent): Unit = onMain {
        if (closed.get() || event.generation() != session.state.generation) return@onMain
        when (event) {
            is ContentPipelineEvent.ManifestReady -> forwardManifestLoaded(event.manifest)
            is ContentPipelineEvent.ManifestFailed -> fail(event.cause)
            is ContentPipelineEvent.ResponseStarted -> startup.markResponseStarted(
                event.pageId,
                System.nanoTime(),
            )
            is ContentPipelineEvent.RawVerified -> {
                startup.markVerified(event.encoded.pageId, System.nanoTime())
                process(session.resolvePageDimensions(event.encoded.pageId, event.encoded.dimensions))
                cachedResume?.let { resume ->
                    resume.rawVerified(event.encoded)?.let { snapshot ->
                        runtimeScope.launch(ioDispatcher) { resume.persist(snapshot) }
                    }
                }
            }
            is ContentPipelineEvent.TextureReady -> {
                val texture = event.texture
                if (texture.rendererEpoch != renderer.rendererEpoch) return@onMain
                startup.markDecoded(texture.pageId, System.nanoTime())
                process(session.visualReady(texture.pageId, VisualBand(
                    texture.sourceTopPx,
                    texture.sourceBottomPx,
                    texture.sourceHeightPx,
                    ml.melun.mangaview.viewer.session.VisualKey(texture.key),
                )))
            }
            is ContentPipelineEvent.TextureEvicted -> process(session.visualEvicted(
                event.texture.pageId,
                ml.melun.mangaview.viewer.session.VisualKey(event.texture.key),
            ))
            is ContentPipelineEvent.PageFailed -> if (
                event.demandClass == DemandClass.RESUME_ANCHOR ||
                event.demandClass == DemandClass.VISIBLE
            ) fail(event.cause)
        }
    }

    private suspend fun loadForwardManifest(id: EpisodeId): EpisodeManifest {
        source.prepare(id, PreparationIntent.ADJACENT_FORWARD)
        return source.manifest(id)
    }

    private fun forwardManifestLoaded(manifest: EpisodeManifest): Unit = onMain {
        if (closed.get() || session.state.timeline.lastManifest?.nextEpisodeId != manifest.id) {
            return@onMain
        }
        val generation = session.state.generation
        cachedResume?.manifestResolved(manifest)
        runtimeScope.launch {
            pipeline.registerManifest(generation, manifest)
            onMain {
                if (!closed.get() && session.state.generation == generation &&
                    session.state.timeline.lastManifest?.nextEpisodeId == manifest.id) {
                    process(session.appendEpisode(manifest))
                }
            }
        }
    }

    private fun presented(value: OwnedPresentation) = onMain {
        // Each renderer token terminates once; callback order may differ from submission order.
        // Preserve old-epoch/close terminals for evidence without mutating the active session.
        presentationMapper.evidence(value.rendererEpoch, value)?.let(reportPresentedFrame)
        reportPresentedRegions(PresentedRegionMapper.from(value.rendererEpoch, value))
        if (closed.get() || value.rendererEpoch != renderer.rendererEpoch ||
            value.scene.generation != session.state.generation ||
            value.scene.lifecycleEpoch != session.state.lifecycleEpoch) return@onMain
        val metadata = value.metadata ?: return@onMain
        val presentedPageId = metadata.presentedPageId
        if (presentedPageId != null && startup.needsPresentation()) {
            startup.markPresented(
                presentedPageId,
                value.submittedAtNanos,
                value.presentedAtNanos,
                value.timestampKind,
            )
        }
    }

    private fun ContentPipelineEvent.generation(): Long = when (this) {
        is ContentPipelineEvent.ManifestReady -> generation
        is ContentPipelineEvent.ManifestFailed -> generation
        is ContentPipelineEvent.ResponseStarted -> generation
        is ContentPipelineEvent.RawVerified -> generation
        is ContentPipelineEvent.TextureReady -> generation
        is ContentPipelineEvent.TextureEvicted -> generation
        is ContentPipelineEvent.PageFailed -> generation
    }

    private fun persistCurrentPosition() {
        session.positionForPersistence()?.let(::persistIfChanged)
    }

    private fun memoryPressure(): Unit = pipeline.onMemoryPressure()

    private fun persistIfChanged(position: ReadingPosition) {
        if (position == lastPersisted) return
        lastPersisted = position
        persistPosition(position)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun fail(failure: Throwable) {
        if (!closed.get()) reportFailure(failure)
    }

    private data class FrameTiming(
        val vsyncId: Long,
        val expectedPresentationNanos: Long,
    )

    private companion object {
        const val NETWORK_LIMIT = 4
    }
}
