package ml.melun.mangaview.activity

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.app.AndroidWorkDispatcher
import ml.melun.mangaview.app.EngineAppGraph
import ml.melun.mangaview.app.EngineViewerWork
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.runtime.ViewerCachedResume
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
    private val windowManager get() = activity.windowManager
    private var closing = false
    private val isFinishing get() = closing || activity.isFinishing
    private val isDestroyed get() = closing || activity.isDestroyed
    fun finish() = finishReader()
    private val sessionJob = SupervisorJob()
    private val sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
    private val hardDecodeWork = AndroidWorkDispatcher(
        name = "viewer-engine-decode",
        threads = 2,
        // Native decode is latency-sensitive but still must yield to input, UI and RenderThread.
        // A dedicated background-priority lane keeps it independent from warm decode without
        // stealing VSYNC CPU time on lower-core emulators and phones.
        linuxPriority = Process.THREAD_PRIORITY_BACKGROUND,
    )
    private var runtime: EngineViewerRuntime? = null
    private val presentationRecorder = ViewerPresentationRecorder()
    private val presentedRegionRecorder = PresentedRegionRecorder()
    private lateinit var loading: ViewerLoadingOverlay
    private lateinit var failureCard: LinearLayout
    private lateinit var failureText: TextView
    private lateinit var dimOverlay: View
    private lateinit var settingsPanel: ViewerReaderSettingsPanel
    private var appliedSettings: ViewerSettings? = null
    private var volumeKeysEnabled = false
    private var foreground = false
    private var reportedFailure: Throwable? = null
    private lateinit var chrome: ViewerChromeController
    private var contentSource: EngineViewerWork? = null
    private lateinit var engine: EngineAppGraph
    private var openingHandoff: ml.melun.mangaview.app.EngineOpeningPreparations.Handoff? = null
    private var rendererLease: ml.melun.mangaview.app.EngineRendererPreparation<
        ml.melun.mangaview.viewer.runtime.EngineSurfaceOwner>.Lease? = null
    private var openingReleased = false
    private val engineClosed = CompletableDeferred<Unit>()
    private val engineDiagnostics = EngineViewerDiagnostics()
    @Volatile private var surfaceRoot: ViewerTouchRoot? = null
    private val engineInputObservations = EngineInputObservations()
    internal fun reserveWholeTraversalInputEvidence() {
        engineInputObservations.reserveCaptureCapacity(32_768)
    }
    private var episodeListJob: Job? = null
    @Volatile private var episodePickerFailure: Throwable? = null
    fun create(): FrameLayout {
        activity.configureViewerWindowInsets()
        val spec = launchSpec
        engine = (activity.application as ViewerApplication).graph.engine
        val source = engine.session(spec)
        val viewport = initialViewport()
        openingHandoff = engine.openings.claim(spec.episodeId)
        rendererLease = engine.renderers.claim()
        contentSource = source
        val createdRuntime = EngineViewerRuntime(
            context = this,
            scope = sessionScope,
            coordinator = engine.coordinator,
            source = source,
            positions = engine.positions,
            decodeDispatcher = hardDecodeWork.coroutineDispatcher,
            episodeId = spec.episodeId,
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
                    loading.complete()
                    if (failureCard.visibility == View.VISIBLE) failureCard.visibility = View.GONE
                }
            },
            reportRendererClosed = engineDiagnostics::rendererClosed,
            inputObservations = engineInputObservations,
            reportFailure = ::showFailure,
            preparedRenderer = rendererLease?.value,
        )
        runtime = createdRuntime
        val root = content(createdRuntime)
        surfaceRoot = root as? ViewerTouchRoot
        observeReaderSettings()
        return root
    }

    fun open() {
        val createdRuntime = requireNotNull(runtime)
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
        val refreshRate = activityRefreshRate()
        return (1_000_000_000.0 / refreshRate).toLong().coerceAtLeast(1L)
    }

    private fun activityRefreshRate(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.refreshRate?.takeIf { it > 0f } ?: 60f
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
    }

    internal fun gestureWindowsSnapshot(): List<LongRange> = presentationRecorder.gestureSnapshot()

    internal fun userInputRevisionSnapshot(): Long = runtime?.userInputRevisionSnapshot() ?: 0L
    internal fun engineInputObservationsSince(ordinal: Long) = engineInputObservations.since(ordinal)
    internal fun engineInputCloseProof() = engineInputObservations.closeProof()
    internal fun engineFramesSince(ordinal: Long) = engineDiagnostics.framesSince(ordinal)
    internal fun engineFrameCloseProof() = engineDiagnostics.frameCloseProof()

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
    internal fun episodePickerFailureSnapshot(): Throwable? = episodePickerFailure
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
        foreground = true
        applyKeepScreenOn(appliedSettings?.keepScreenOn == true)
        applyImmersive(appliedSettings?.immersiveMode == true)
        runtime?.enterForeground()
    }

    fun enterBackground() {
        foreground = false
        applyImmersive(false)
        applyKeepScreenOn(false)
        runtime?.enterBackground()
    }

    /** Consumes a volume key when the reader has hardware-key navigation enabled. */
    fun handleVolumeKey(forward: Boolean): Boolean {
        if (!volumeKeysEnabled) return false
        return stepViewport(forward)
    }

    /** Lets reader-local overlays consume back before the host closes the whole session. */
    fun handleBack(): Boolean {
        if (::settingsPanel.isInitialized && settingsPanel.visible) {
            settingsPanel.dismiss()
            return true
        }
        if (::chrome.isInitialized && chrome.visible) {
            chrome.hide()
            return true
        }
        return false
    }

    private fun stepViewport(forward: Boolean): Boolean {
        val surface = runtime?.surface ?: return false
        val height = surface.height
        if (height <= 0) return false
        return surface.stepViewport(if (forward) height.toDouble() else -height.toDouble())
    }

    private fun observeReaderSettings() {
        val library = (activity.application as ViewerApplication).graph.userLibrary
        sessionScope.launch {
            library.snapshot
                .catch { failure -> android.util.Log.w("ViewerSettings", "reader settings unavailable", failure) }
                .collect { snapshot -> applyReaderPreferences(snapshot.settings) }
        }
    }

    private fun applyReaderPreferences(settings: ViewerSettings) {
        if (appliedSettings == settings) return
        appliedSettings = settings
        volumeKeysEnabled = settings.volumeKeyNavigation
        if (::dimOverlay.isInitialized) dimOverlay.alpha = settings.readerDimPercent / 100f
        if (::chrome.isInitialized) chrome.setImmersiveActive(settings.immersiveMode)
        if (foreground) {
            applyKeepScreenOn(settings.keepScreenOn)
            applyImmersive(settings.immersiveMode)
        }
    }

    private fun persistSettings(transform: (ViewerSettings) -> ViewerSettings) {
        val library = (activity.application as ViewerApplication).graph.userLibrary
        sessionScope.launch {
            try {
                library.updateSettings(transform)
            } catch (failure: Throwable) {
                android.util.Log.e("ViewerSettings", "reader setting not saved", failure)
            }
        }
    }

    private fun toggleSettingsPanel() {
        if (settingsPanel.visible) {
            settingsPanel.dismiss()
        } else {
            settingsPanel.open(appliedSettings ?: ViewerSettings())
        }
    }

    private fun retryFromFailure() {
        failureCard.visibility = View.GONE
        loading.restart()
        runtime?.retryFailures()
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (enabled) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        episodeListJob?.cancel()
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
        hardDecodeWork.closeAndAwait()
    }

    private fun content(runtime: EngineViewerRuntime): FrameLayout =
        ViewerTouchRoot(this).apply {
        onSurfaceTap = {
            // Immersive reading owns plain taps: the reader asked for the chrome to stay hidden
            // until a deliberate long press requests it.
            if (::chrome.isInitialized && appliedSettings?.immersiveMode != true) chrome.toggle()
        }
        onSurfaceLongPress = {
            if (::chrome.isInitialized && appliedSettings?.immersiveMode == true) chrome.toggle()
        }
        onSurfaceDoubleTap = { x, y ->
            // Tap coordinates arrive in root space; zoom transforms are surface-local.
            val surface = runtime.surface
            surface.toggleZoom(x - surface.left, y - surface.top)
        }
        setBackgroundColor(Color.BLACK)
        installSystemBarInsets()
        addView(runtime.surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        dimOverlay = View(this@EngineViewerScreen).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
            alpha = 0f
        }
        addView(dimOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        loading = ViewerLoadingOverlay(this@EngineViewerScreen)
        addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        failureCard = buildFailureCard()
        addView(failureCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(24)
            setMargins(margin, margin, margin, margin + dp(48))
        })
        // Chrome installs before the panel so the panel and its scrim stay above the bars.
        installChrome(this, runtime)
        settingsPanel = ViewerReaderSettingsPanel(this@EngineViewerScreen).apply {
            onDimChanged = { percent -> dimOverlay.alpha = percent / 100f }
            onDimCommitted = { percent -> persistSettings { it.copy(readerDimPercent = percent) } }
            onKeepScreenOn = { enabled -> persistSettings { it.copy(keepScreenOn = enabled) } }
            onVolumeKeys = { enabled -> persistSettings { it.copy(volumeKeyNavigation = enabled) } }
            onClose = { dismiss() }
        }
        addView(settingsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        }

    private fun buildFailureCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "viewer-failure"
        val padH = dp(20)
        setPadding(padH, dp(14), padH, dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(0xF0181A22.toInt())
            setStroke(dp(1), 0x33FFFFFF.toInt())
        }
        visibility = View.GONE
        isClickable = true
        failureText = TextView(this@EngineViewerScreen).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        addView(failureText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        row.addView(actionButton("닫기", accent = false) { finish() }, LinearLayout.LayoutParams(dp(72), dp(48)))
        row.addView(actionButton("다시 시도", accent = true) { retryFromFailure() },
            LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginStart = dp(8) })
        addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
    }

    private fun actionButton(text: String, accent: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (accent) 0xFF7C5CFF.toInt() else 0xFF181C26.toInt())
            setStroke(dp(1), if (accent) 0x669080FF.toInt() else 0x33FFFFFF.toInt())
        }
        setOnClickListener { click() }
    }

    private fun installChrome(root: ViewerTouchRoot, runtime: EngineViewerRuntime) {
        chrome = ViewerChromeController(
            activity = activity,
            surface = runtime.surface,
            snapshot = runtime::chromeSnapshot,
            actions = ViewerChromeController.Actions(
                back = ::finish,
                previous = { navigateAdjacent(next = false) },
                episodes = ::loadEpisodePicker,
                next = { navigateAdjacent(next = true) },
                bookmark = ::bookmarkCurrentPosition,
                split = ::toggleSplitMode,
                immersive = ::toggleImmersiveMode,
                settings = ::toggleSettingsPanel,
            ),
        ).also { controller ->
            controller.install(root)
            controller.setImmersiveActive(appliedSettings?.immersiveMode == true)
        }
        root.excludesSurfaceTap = { x, y ->
            loading.active || chrome.contains(x, y) || settingsPanel.visible ||
                (failureCard.visibility == View.VISIBLE && failureCard.containsPoint(x, y))
        }
    }

    private fun onViewerOpened() {
        if (::chrome.isInitialized) chrome.refresh()
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
        if (::chrome.isInitialized) chrome.refresh()
    }

    private fun toggleImmersiveMode() {
        val enabled = appliedSettings?.immersiveMode != true
        persistSettings { it.copy(immersiveMode = enabled) }
        applyImmersive(enabled)
        if (::chrome.isInitialized) chrome.setImmersiveActive(enabled)
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

    private fun loadEpisodePicker() {
        if (episodeListJob?.isActive == true) return
        val state = runtime?.chromeSnapshot() ?: return
        val source = contentSource ?: return
        episodePickerFailure = null
        Toast.makeText(this, "회차 목록을 불러오는 중입니다", Toast.LENGTH_SHORT).show()
        episodeListJob = sessionScope.launch {
            try {
                val subscription = engine.coordinator.submit(source.episodes(state.episodeId.seriesId, WorkPriority.INTERACTIVE))
                val episodes = try { subscription.await().episodes } finally {
                    subscription.close()
                    withContext(NonCancellable) { subscription.awaitReleased() }
                }
                showEpisodePicker(state, episodes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                episodePickerFailure = failure
                android.util.Log.e("ViewerActivity", "episode picker failed", failure)
                showEpisodePickerFailure()
            } finally {
                episodeListJob = null
            }
        }
    }

    private fun showEpisodePickerFailure() {
        if (isFinishing || isDestroyed) return
        runCatching {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("회차 목록")
                .setMessage("회차 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                .setPositiveButton("다시 시도") { _, _ -> loadEpisodePicker() }
                .setNegativeButton("닫기", null)
                .show()
        }.onFailure { android.util.Log.e("ViewerActivity", "episode picker failure dialog failed", it) }
    }

    private fun showEpisodePicker(current: ViewerChromeState, episodes: List<SourceEpisode>) {
        if (episodes.isEmpty() || isFinishing || isDestroyed) return
        val currentIndex = episodes.indexOfFirst { it.id == current.episodeId }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("회차 선택")
            .setSingleChoiceItems(episodes.map(SourceEpisode::title).toTypedArray(), currentIndex) {
                    dialog, index ->
                dialog.dismiss()
                val target = episodes.getOrNull(index)?.id ?: return@setSingleChoiceItems
                if (target != current.episodeId) launchEpisode(target)
            }
            .setNegativeButton("취소", null)
            .create()
        if (currentIndex > 0) {
            dialog.setOnShowListener { dialog.listView?.setSelection(currentIndex) }
        }
        dialog.show()
    }

    private fun FrameLayout.installSystemBarInsets() {
        setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.viewerSafeDrawingInsets()
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom
            ) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
    }

    private fun initialViewport(): Viewport {
        val metrics = resources.displayMetrics
        return Viewport(
            FixedPx.fromPixels(metrics.widthPixels.coerceAtLeast(1)),
            FixedPx.fromPixels(metrics.heightPixels.coerceAtLeast(1)),
        )
    }

    private fun showFailure(failure: Throwable) {
        reportedFailure = failure
        loading.failed()
        failureText.text = failure.message?.takeIf(String::isNotBlank) ?: "페이지를 불러오지 못했습니다"
        failureCard.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun View.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

}
