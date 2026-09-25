package ml.melun.mangaview.activity

import android.app.AlertDialog
import android.os.Build
import android.os.Process
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.app.AndroidWorkDispatcher
import ml.melun.mangaview.app.EngineAppGraph
import ml.melun.mangaview.app.EngineViewerWork
import ml.melun.mangaview.app.InlinePriorityLane
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.content.DecodeLane
import ml.melun.mangaview.engine.content.DispatcherDecodeLane
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.runtime.ViewerChromeState
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeDiagnostic
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.EngineViewerRuntime
import ml.melun.mangaview.viewer.runtime.EngineViewerRuntimeDiagnosticSnapshot
import ml.melun.mangaview.viewer.runtime.EngineSurfacePresentation
import ml.melun.mangaview.viewer.runtime.EngineViewerDiagnostics
import ml.melun.mangaview.viewer.runtime.EngineInputObservations
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming

/** One reader session that can attach to either the library window or a direct-entry activity. */
internal class EngineViewerScreen(
    private val activity: ComponentActivity,
    val launchSpec: ViewerLaunchSpec,
    private val finishReader: () -> Unit,
    private val openEpisode: (EpisodeId) -> Unit,
) : android.content.ContextWrapper(activity) {
    val window get() = activity.window
    private var closing = false
    private val isFinishing get() = closing || activity.isFinishing
    private val isDestroyed get() = closing || activity.isDestroyed
    fun finish() = finishReader()
    private val sessionJob = SupervisorJob()
    private val sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
    // The speculative decode used to run on this dedicated background-priority pool. It now runs
    // inline on the tile record's own worker with an equivalent priority wrap, which removed the
    // lane's two dispatcher hops (~0.3-0.5ms of a ~5.9ms read-ahead budget on the GPU AVD). The
    // pool stays as the decoder lane's close witness: the capture device test reads
    // engineDecodeWorkersTerminated() after the viewer closes, and closeDecodeWorkers() drains it.
    private val hardDecodeWork = AndroidWorkDispatcher(
        name = "viewer-engine-decode",
        threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
        linuxPriority = Process.THREAD_PRIORITY_BACKGROUND,
    )
    // The lanes are split by work priority instead of running the whole decoder at one priority.
    // During the opening window the read-ahead horizon keeps one background-priority decode busy,
    // and Android gives BACKGROUND threads roughly a tenth of the CPU: a visible tile's decode that
    // shares that lane measured 7-9ms against 1.5ms when it had the CPU. Driving the visible
    // demand's decode on a default-priority lane keeps the horizon on its throttled lane while the
    // tile the reader is actually looking at runs at normal priority. Raising the whole lane instead
    // (measured) let the horizon contend with the owner/render threads and regressed F/V 29 -> 112ms.
    private val visibleDecodeWork = AndroidWorkDispatcher(
        name = "viewer-engine-decode-visible",
        threads = 2,
        linuxPriority = Process.THREAD_PRIORITY_DEFAULT,
    )
    // The horizon's decode no longer pays a lane dispatch: it runs inline on the tile record's own
    // worker under the old lane's background-priority wrap. Measured on the GPU AVD: raising this
    // inline decode's priority to default with the record's worker made it contend — ntk F/V p50
    // 19.8 -> 30.8 and wfwf d2r p50 5.95 -> 6.23 — so the wrap stays background; the display
    // threads (main -10, owner/render -4) preempt it, and admission bounds how many workers park.
    private val inlineBackgroundDecode = InlinePriorityLane()
    private val visibleDecodeLane = DispatcherDecodeLane(visibleDecodeWork.coroutineDispatcher)
    private val decodeLanes: (WorkPriority) -> DecodeLane = { priority ->
        if (priority.background) inlineBackgroundDecode else visibleDecodeLane
    }
    private val episodePicker = EpisodePickerController(
        context = activity,
        scope = sessionScope,
        chromeState = { runtime?.chromeSnapshot() },
        coordinator = { engine.coordinator },
        content = { contentSource },
        launchEpisode = { openEpisode(it) },
        isUnavailable = { isFinishing || isDestroyed },
    )
    private var runtime: EngineViewerRuntime? = null
    private lateinit var ui: ViewerScreenUi
    private val presentationRecorder = ViewerPresentationRecorder()
    private val presentedRegionRecorder = PresentedRegionRecorder()
    private var reportedFailure: Throwable? = null
    private var contentSource: EngineViewerWork? = null
    private lateinit var engine: EngineAppGraph
    private var openingHandoff: ml.melun.mangaview.app.EngineOpeningPreparations.Handoff? = null
    private var rendererLease: ml.melun.mangaview.app.EngineRendererPreparation<
        ml.melun.mangaview.viewer.runtime.EngineSurfaceOwner>.Lease? = null
    private var openingReleased = false
    private var firstFrameReported = false
    private var viewerOpenedAtMillis = 0L
    private val engineClosed = CompletableDeferred<Unit>()
    private val engineDiagnostics = EngineViewerDiagnostics()
    @Volatile private var surfaceRoot: ViewerTouchRoot? = null
    private val engineInputObservations = EngineInputObservations()
    internal fun reserveWholeTraversalInputEvidence() {
        engineInputObservations.reserveCaptureCapacity(32_768)
    }
    private var sessionGateEntered = false
    fun create(): FrameLayout {
        if (!sessionGateEntered) {
            sessionGateEntered = true
            ml.melun.mangaview.app.ViewerSessionActivity.enter(launchSpec.sourceId.value)
        }
        activity.configureViewerWindowInsets()
        val spec = launchSpec
        engine = (activity.application as ViewerApplication).graph.engine
        ui = ViewerScreenUi(activity, sessionScope, ViewerChromeController.Actions(
            back = ::finish,
            previous = { navigateAdjacent(next = false) },
            episodes = episodePicker::load,
            next = { navigateAdjacent(next = true) },
            bookmark = ::bookmarkCurrentPosition,
            split = ::toggleSplitMode,
            immersive = { ui.toggleImmersiveMode() },
            settings = { ui.toggleSettingsPanel() },
        ), retry = { runtime?.retryFailures() })
        val source = engine.session(spec)
        // The engine graph is built lazily on this very call, so a direct reader launch reaches its
        // first scene with every work lane, the native decoder and the coordinator still cold: the
        // opening viewport's tiles then pay class-load/JIT and first-dispatch cost on the demand
        // path. Start the opening prediction here as well as from the library so the same episode's
        // originals and opening bands are already being prepared while the surface and first plan
        // come up. It shares this viewer's coordinator, so plan/page/pixel work is deduplicated
        // rather than repeated, and by the time the plan demands the opening tiles their decode is
        // usually already warm or done.
        engine.openings.warm(spec.episodeId)
        openingHandoff = engine.openings.claim(spec.episodeId)
        rendererLease = engine.renderers.claim()
        // Prepare the next viewer's GL owner while this one reads, so tapping to the next episode
        // attaches an already prepared renderer instead of paying native context setup on its
        // opening frame.
        engine.renderers.warm()
        contentSource = source
        val createdRuntime = buildRuntime(source)
        runtime = createdRuntime
        val root = ui.content(createdRuntime)
        surfaceRoot = root as? ViewerTouchRoot
        ui.observeReaderSettings()
        return root
    }

    private fun buildRuntime(source: EngineViewerWork): EngineViewerRuntime {
        val viewport = initialViewport(activity)
        return EngineViewerRuntime(
            context = this,
            scope = sessionScope,
            coordinator = engine.coordinator,
            source = source,
            positions = engine.positions,
            decodeLanes = decodeLanes,
            episodeId = launchSpec.episodeId,
            initialViewport = EngineViewport(Math.toIntExact(viewport.width.units / 1024),
                Math.toIntExact(viewport.height.units / 1024)),
            reportGestureBoundary = ::recordGestureBoundary,
            reportMotionFrame = presentationRecorder::recordMotionFrame,
            reportSnapshot = { snapshot ->
                engineDiagnostics.snapshot(snapshot, System.nanoTime())
                onViewerOpened()
            },
            reportPresented = { presented ->
                engineDiagnostics.presented(presented)
                if (presented.swapSucceeded && presented.scene.completeCoverage &&
                    presented.scene.placements.isNotEmpty()) {
                    reportFirstFrame()
                    ui.presentationComplete()
                }
            },
            reportRendererClosed = engineDiagnostics::rendererClosed,
            inputObservations = engineInputObservations,
            reportFailure = ::showFailure,
            preparedRenderer = rendererLease?.value,
        )
    }

    /**
     * Publishes the engine-path accessibility contract the old pipeline owned: instrumentation
     * waits for this suffix to measure first-content latency.
     */
    private fun reportFirstFrame() {
        if (firstFrameReported) return
        firstFrameReported = true
        val presentedAtMillis = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("NtkFrame", "first-frame elapsedMs=${presentedAtMillis - viewerOpenedAtMillis}")
        runtime?.surface?.contentDescription = "viewer-frame-presented:$presentedAtMillis"
    }

    fun open() {
        val createdRuntime = requireNotNull(runtime)
        viewerOpenedAtMillis = android.os.SystemClock.elapsedRealtime()
        engineDiagnostics.opened(System.nanoTime())
        sessionScope.launch {
            openingHandoff?.awaitPredecessor()
            if (runtime === createdRuntime) createdRuntime.open()
        }
    }

    internal fun presentedRegionsSince(sequence: Long) = presentedRegionRecorder.since(sequence)

    internal fun presentationNanosSnapshot(): LongArray = presentationRecorder.presentationSnapshot()

    internal fun presentationCadenceNanosSnapshot(): LongArray =
        presentationRecorder.presentationCadenceSnapshot()

    internal fun presentationEvidenceSnapshot(): LongArray =
        presentationRecorder.presentationEvidenceSnapshot()

    internal fun presentationEvidenceSince(sequence: Long): ViewerPresentationBatch =
        presentationRecorder.presentationEvidenceSince(sequence)

    internal fun renderSamplesSnapshot(): LongArray = presentationRecorder.renderSnapshot()

    internal fun motionFrameNanosSnapshot(): LongArray = presentationRecorder.motionFrameSnapshot()

    internal fun motionFramesSince(sequence: Long): ViewerMotionBatch =
        presentationRecorder.motionFramesSince(sequence)

    internal fun presentationRefreshPeriodNanos(): Long {
        val refreshRate = activityRefreshRate(activity)
        return (1_000_000_000.0 / refreshRate).toLong().coerceAtLeast(1L)
    }

    internal fun gestureWindowsSnapshot(): List<LongRange> = presentationRecorder.gestureSnapshot()

    internal fun userInputRevisionSnapshot(): Long = runtime?.userInputRevisionSnapshot() ?: 0L
    internal fun engineInputObservationsSince(ordinal: Long) = engineInputObservations.since(ordinal)
    internal fun engineInputCloseProof() = engineInputObservations.closeProof()
    internal fun engineFramesSince(ordinal: Long) = engineDiagnostics.framesSince(ordinal)
    internal fun engineFrameCloseProof() = engineDiagnostics.frameCloseProof()
    internal fun engineTileTimingsSnapshot() = runtime?.tileTimingsSnapshot().orEmpty()

    // The old global-offset telemetry cannot represent source-anchor coordinates. Keep unknown
    // data absent until callers migrate to the engine's exact snapshot and frame identities.
    internal fun viewerTelemetrySnapshot(): ViewerTelemetrySnapshot? = null

    internal fun viewerStartupTimingSnapshot(): ViewerStartupTiming? = engineDiagnostics.startup()

    internal fun viewerCachedResumeSnapshot(): ViewerCachedResumeDiagnostic? =
        null

    internal fun viewerEngineSnapshot(): EngineRuntimeSnapshot? = engineDiagnostics.state
    internal suspend fun viewerEngineDiagnosticSnapshot(): EngineViewerRuntimeDiagnosticSnapshot? =
        withContext(Dispatchers.Main.immediate) { runtime?.diagnosticSnapshot() }
    internal fun viewerEngineFrameSnapshot(): EngineSurfacePresentation? = engineDiagnostics.frame
    internal suspend fun awaitEngineClosed() = engineClosed.await()
    internal fun engineDecodeWorkersTerminated(): Boolean = hardDecodeWork.isTerminated
    internal fun episodePickerFailureSnapshot(): Throwable? = episodePicker.failureSnapshot()
    internal suspend fun captureNextEngineFrame(top: Int, bottom: Int) = requireNotNull(runtime).captureNextFrame(top, bottom)
    internal suspend fun captureNextEngineViewportFrame() = requireNotNull(runtime).captureNextViewportFrame()

    internal fun viewerFailureSnapshot(): Throwable? = reportedFailure

    fun restorationSpec(): ViewerLaunchSpec {
        val anchor = runtime?.bookmarkSnapshot()?.first ?: return launchSpec
        val episode = anchor.pageId.episodeId
        return ViewerLaunchSpec(episode.seriesId.sourceId, episode.seriesId, episode, initialAnchor = anchor)
    }

    internal fun isViewerInputSurfaceReady(): Boolean = runtime?.surface?.let { surface ->
        surface.isAttachedToWindow && surface.isShown && surface.width > 0 && surface.height > 0
    } == true

    internal fun viewerSurfaceZoomScale(): Float = runtime?.surface?.scaleX ?: 1f

    internal fun viewerSurfaceTapEligible(x: Float, y: Float): Boolean =
        surfaceRoot?.let { root -> !root.excludesSurfaceTap(x, y) } ?: false

    private fun recordPresentation(
        evidence: ml.melun.mangaview.viewer.runtime.NativePresentationEvidence,
    ): Boolean = presentationRecorder.recordPresentation(evidence)

    private fun recordGestureBoundary(started: Boolean, atNanos: Long) {
        presentationRecorder.recordGestureBoundary(started, atNanos)
    }

    fun enterForeground() {
        presentationRecorder.beginUiEpoch()
        ui.enterForeground()
        runtime?.enterForeground()
    }

    fun enterBackground() {
        ui.enterBackground()
        runtime?.enterBackground()
    }

    /** Consumes a volume key when the reader has hardware-key navigation enabled. */
    fun handleVolumeKey(forward: Boolean): Boolean {
        if (!ui.volumeKeysEnabled) return false
        return stepViewport(forward)
    }

    fun handleBack(): Boolean = ::ui.isInitialized && ui.handleBack()

    private fun stepViewport(forward: Boolean): Boolean {
        val surface = runtime?.surface ?: return false
        val height = surface.height
        if (height <= 0) return false
        return surface.stepViewport(if (forward) height.toDouble() else -height.toDouble())
    }

    fun close() {
        if (closing) return
        closing = true
        if (sessionGateEntered) {
            sessionGateEntered = false
            ml.melun.mangaview.app.ViewerSessionActivity.exit(launchSpec.sourceId.value)
        }
        episodePicker.cancel()
        val activeRuntime = runtime
        runtime = null
        sessionScope.launch(NonCancellable) {
            var closeFailure: Throwable? = null
            try {
                activeRuntime?.close()
            } catch (failure: Throwable) {
                closeFailure = failure
            }
            try {
                openingHandoff?.close()
            } catch (failure: Throwable) {
                val primary = closeFailure
                if (primary == null) closeFailure = failure else if (primary !== failure) primary.addSuppressed(failure)
            }
            try {
                rendererLease?.close()
            } catch (failure: Throwable) {
                val primary = closeFailure
                if (primary == null) closeFailure = failure else if (primary !== failure) primary.addSuppressed(failure)
            }
            try {
                closeDecodeWorkers()
                // Bound the engine's raw page cache once this session's page leases are released.
                engine.trimStorageCache()
            } catch (failure: Throwable) {
                val primary = closeFailure
                if (primary == null) closeFailure = failure else if (primary !== failure) primary.addSuppressed(failure)
            } finally {
                val failure = closeFailure
                if (failure == null) engineClosed.complete(Unit) else {
                    reportedFailure = failure
                    engineClosed.completeExceptionally(failure)
                }
                sessionJob.cancel()
            }
        }
    }

    private suspend fun closeDecodeWorkers() {
        visibleDecodeWork.closeAndAwait()
        hardDecodeWork.closeAndAwait()
    }

    private fun onViewerOpened() {
        if (::ui.isInitialized) ui.refreshChrome()
        if (!openingReleased && runtime?.bookmarkSnapshot() != null) {
            openingReleased = true
            sessionScope.launch { openingHandoff?.releasePreparation() }
        }
    }

    private fun navigateAdjacent(next: Boolean) {
        val state = runtime?.chromeSnapshot() ?: return
        val target = if (next) state.nextEpisodeId else state.previousEpisodeId
        target?.let(::launchEpisode)
    }

    private fun toggleSplitMode() {
        val state = runtime?.chromeSnapshot() ?: return
        runtime?.setSplitMode(!state.splitMode)
        if (::ui.isInitialized) ui.refreshChrome()
    }

    private fun launchEpisode(episodeId: EpisodeId) = openEpisode(episodeId)

    private fun bookmarkCurrentPosition() {
        val (anchor, position) = runtime?.bookmarkSnapshot() ?: return
        sessionScope.launch(NonCancellable) {
            try {
                engine.saveBookmark(anchor, position.offsetInPageUnits)
                if (!isFinishing && !isDestroyed) Toast.makeText(this@EngineViewerScreen,
                    "현재 위치를 책갈피에 저장했습니다", Toast.LENGTH_SHORT).show()
            } catch (failure: Throwable) {
                if (!isFinishing && !isDestroyed) Toast.makeText(this@EngineViewerScreen,
                    "책갈피를 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                android.util.Log.e("ViewerActivity", "bookmark save failed", failure)
            }
        }
    }

    private fun showFailure(failure: Throwable) {
        reportedFailure = failure
        ui.showFailure(failure)
    }

}

private fun initialViewport(activity: ComponentActivity): Viewport {
    val metrics = activity.resources.displayMetrics
    return Viewport(
        FixedPx.fromPixels(metrics.widthPixels.coerceAtLeast(1)),
        FixedPx.fromPixels(metrics.heightPixels.coerceAtLeast(1)),
    )
}

private fun activityRefreshRate(activity: ComponentActivity): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    activity.display?.refreshRate?.takeIf { it > 0f } ?: 60f
} else {
    @Suppress("DEPRECATION")
    activity.windowManager.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
}

/**
 * Owns the episode-list dialog: the in-flight catalog request, its failure state, and the picker
 * UI. Lives beside [EngineViewerScreen] so the screen class stays inside the architecture gate.
 */
internal class EpisodePickerController(
    private val context: ComponentActivity,
    private val scope: CoroutineScope,
    private val chromeState: () -> ViewerChromeState?,
    private val coordinator: () -> WorkCoordinatorPort,
    private val content: () -> EngineViewerWork?,
    private val launchEpisode: (EpisodeId) -> Unit,
    private val isUnavailable: () -> Boolean,
) {
    private var job: Job? = null

    @Volatile
    private var failure: Throwable? = null

    fun failureSnapshot(): Throwable? = failure

    fun cancel() {
        job?.cancel()
    }

    fun load() {
        if (job?.isActive == true) return
        val state = chromeState() ?: return
        val source = content() ?: return
        failure = null
        Toast.makeText(context, "회차 목록을 불러오는 중입니다", Toast.LENGTH_SHORT).show()
        job = scope.launch {
            try {
                val subscription = coordinator().submit(
                    source.episodes(state.episodeId.seriesId, WorkPriority.INTERACTIVE))
                val episodes = try {
                    subscription.await().episodes
                } finally {
                    subscription.close()
                    withContext(NonCancellable) { subscription.awaitReleased() }
                }
                show(state, episodes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                this@EpisodePickerController.failure = failure
                android.util.Log.e("ViewerActivity", "episode picker failed", failure)
                showFailureDialog()
            } finally {
                job = null
            }
        }
    }

    private fun showFailureDialog() {
        if (isUnavailable()) return
        runCatching {
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("회차 목록")
                .setMessage("회차 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                .setPositiveButton("다시 시도") { _, _ -> load() }
                .setNegativeButton("닫기", null)
                .show()
        }.onFailure { android.util.Log.e("ViewerActivity", "episode picker failure dialog failed", it) }
    }

    private fun show(current: ViewerChromeState, episodes: List<SourceEpisode>) {
        if (episodes.isEmpty() || isUnavailable()) return
        val currentIndex = episodes.indexOfFirst { it.id == current.episodeId }
        val list = EpisodePickerList(context).apply {
            adapter = EpisodePickerAdapter(episodes.map(SourceEpisode::title), currentIndex)
            // The thumb doubles as the position cue on a long run and is only noise on a short one.
            isFastScrollAlwaysVisible = episodes.size >= FAST_SCROLL_FROM
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * PICKER_HEIGHT_FRACTION).toInt(),
            )
        }
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("회차 선택")
            .setView(list)
            .setNegativeButton("취소", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val target = episodes.getOrNull(position)?.id ?: return@setOnItemClickListener
            if (target != current.episodeId) launchEpisode(target)
        }
        if (currentIndex > 0) {
            dialog.setOnShowListener { list.setSelection(currentIndex) }
        }
        dialog.show()
    }

    private companion object {
        /** Past this many episodes the fast-scroll thumb is a position cue worth keeping visible. */
        const val FAST_SCROLL_FROM = 40

        /** Share of the screen the episode rows take; the title and the button keep the rest. */
        const val PICKER_HEIGHT_FRACTION = 0.6f
    }
}
