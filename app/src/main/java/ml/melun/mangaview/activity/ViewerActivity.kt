package ml.melun.mangaview.activity

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.app.AndroidWorkDispatcher
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.runtime.ViewerChromeState
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.ViewerRuntime
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import kotlin.math.max

class ViewerActivity : ComponentActivity() {
    private data class SafeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val sessionJob = SupervisorJob()
    private val sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
    private val hardDecodeWork = AndroidWorkDispatcher(
        name = "viewer-decode-hard",
        threads = 1,
        linuxPriority = Process.THREAD_PRIORITY_DEFAULT,
    )
    private val warmDecodeWork = AndroidWorkDispatcher(
        name = "viewer-decode-warm",
        threads = 1,
        linuxPriority = Process.THREAD_PRIORITY_BACKGROUND,
    )
    private var runtime: ViewerRuntime? = null
    private val presentationRecorder = ViewerPresentationRecorder()
    private lateinit var progress: ProgressBar
    private lateinit var failureText: TextView
    private lateinit var chrome: ViewerChromeController
    private var contentSource: ContentSource? = null
    private var saveBookmark: ((ReadingPosition) -> Unit)? = null
    private var episodeListJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowInsets()
        val spec = runCatching { ViewerLaunchSpec.from(intent) }.getOrElse {
            finishWithFailure(it)
            return
        }
        val dependencies = runCatching {
            (application as ViewerApplication).graph.viewer(spec)
        }.getOrElse {
            finishWithFailure(it)
            return
        }
        val viewport = initialViewport()
        contentSource = dependencies.source
        saveBookmark = dependencies.saveBookmark
        val createdRuntime = ViewerRuntime(
            context = this,
            scope = sessionScope,
            sourceDispatcher = dependencies.sourceDispatcher,
            ioDispatcher = dependencies.ioDispatcher,
            hardDecodeDispatcher = hardDecodeWork.coroutineDispatcher,
            warmDecodeDispatcher = warmDecodeWork.coroutineDispatcher,
            source = dependencies.source,
            repository = dependencies.repository,
            episodeId = spec.episodeId,
            loadPosition = dependencies.loadPosition,
            persistPosition = dependencies.persistPosition,
            initialViewport = viewport,
            reportGestureBoundary = ::recordGestureBoundary,
            reportMotionFrame = presentationRecorder::recordMotionFrame,
            reportOpened = { runOnUiThread(::onViewerOpened) },
            reportPresentedFrame = { evidence ->
                if (recordPresentation(evidence)) {
                    val presentedAtMillis = SystemClock.elapsedRealtime()
                    runOnUiThread {
                        runtime?.surface?.contentDescription =
                            "viewer-frame-presented:$presentedAtMillis"
                        progress.visibility = android.view.View.GONE
                    }
                }
            },
            reportFailure = { failure -> runOnUiThread { showFailure(failure) } },
        )
        runtime = createdRuntime
        val root = content(createdRuntime)
        setContentView(root)
        root.requestApplyInsets()
        createdRuntime.open()
    }

    private fun configureWindowInsets() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

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

    internal fun viewerTelemetrySnapshot(): ViewerTelemetrySnapshot? = runtime?.telemetrySnapshot()

    internal fun viewerStartupTimingSnapshot(): ViewerStartupTiming? = runtime?.startupTimingSnapshot()

    internal fun isViewerInputSurfaceReady(): Boolean = runtime?.surface?.let { surface ->
        surface.isAttachedToWindow && surface.isShown && surface.width > 0 && surface.height > 0
    } == true

    private fun recordPresentation(
        evidence: ml.melun.mangaview.viewer.runtime.NativePresentationEvidence,
    ): Boolean = presentationRecorder.recordPresentation(evidence)

    private fun recordGestureBoundary(started: Boolean, atNanos: Long) {
        presentationRecorder.recordGestureBoundary(started, atNanos)
    }

    override fun onStart() {
        super.onStart()
        presentationRecorder.beginUiEpoch()
        runtime?.enterForeground()
    }

    override fun onStop() {
        runtime?.enterBackground()
        super.onStop()
    }

    override fun onDestroy() {
        val activeRuntime = runtime
        runtime = null
        activeRuntime?.close {
            closeDecodeWorkers()
            sessionJob.cancel()
        }
        if (activeRuntime == null) {
            closeDecodeWorkers()
            sessionJob.cancel()
        }
        super.onDestroy()
    }

    private fun closeDecodeWorkers() {
        hardDecodeWork.close()
        warmDecodeWork.close()
    }

    private fun content(runtime: ViewerRuntime): FrameLayout =
        ViewerTouchRoot(this).apply {
        onSurfaceTap = { if (::chrome.isInitialized) chrome.toggle() }
        setBackgroundColor(Color.BLACK)
        installSystemBarInsets()
        addView(runtime.surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        progress = ProgressBar(this@ViewerActivity).apply {
            contentDescription = "viewer-loading"
            isClickable = false
            isFocusable = false
        }
        addView(progress, FrameLayout.LayoutParams(96, 96, Gravity.CENTER))
        failureText = TextView(this@ViewerActivity).apply {
            contentDescription = "viewer-failure"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            gravity = Gravity.CENTER
            textSize = 15f
            visibility = android.view.View.GONE
            isClickable = false
            isFocusable = false
        }
        addView(failureText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        installChrome(this, runtime)
        }

    private fun installChrome(root: ViewerTouchRoot, runtime: ViewerRuntime) {
        chrome = ViewerChromeController(
            activity = this,
            surface = runtime.surface,
            snapshot = runtime::chromeSnapshot,
            actions = ViewerChromeController.Actions(
                back = ::finish,
                previous = { navigateAdjacent(next = false) },
                episodes = ::loadEpisodePicker,
                next = { navigateAdjacent(next = true) },
                bookmark = ::bookmarkCurrentPosition,
            ),
        ).also { controller -> controller.install(root) }
        root.excludesSurfaceTap = chrome::contains
    }

    private fun onViewerOpened() {
        if (::chrome.isInitialized) chrome.refresh()
    }

    private fun navigateAdjacent(next: Boolean) {
        val state = runtime?.chromeSnapshot() ?: return
        val target = if (next) state.nextEpisodeId else state.previousEpisodeId
        target?.let(::launchEpisode)
    }

    private fun launchEpisode(episodeId: EpisodeId) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episodeId.seriesId.sourceId.value)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episodeId.seriesId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episodeId.remoteKey)
        })
        finish()
    }

    private fun bookmarkCurrentPosition() {
        val position = runtime?.chromeSnapshot()?.position ?: return
        saveBookmark?.invoke(position)
        Toast.makeText(this, "현재 위치를 책갈피에 저장했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun loadEpisodePicker() {
        if (episodeListJob?.isActive == true) return
        val state = runtime?.chromeSnapshot() ?: return
        val source = contentSource ?: return
        Toast.makeText(this, "회차 목록을 불러오는 중입니다", Toast.LENGTH_SHORT).show()
        episodeListJob = sessionScope.launch {
            try {
                val episodes = withContext(Dispatchers.Default) {
                    ViewerEpisodeListLoader(source).load(state.episodeId.seriesId)
                }
                showEpisodePicker(state, episodes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.makeText(
                    this@ViewerActivity,
                    failure.message ?: "회차 목록을 불러오지 못했습니다",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                episodeListJob = null
            }
        }
    }

    private fun showEpisodePicker(current: ViewerChromeState, episodes: List<SourceEpisode>) {
        if (episodes.isEmpty() || isFinishing || isDestroyed) return
        val currentIndex = episodes.indexOfFirst { it.id == current.episodeId }
        AlertDialog.Builder(this)
            .setTitle("회차 선택")
            .setSingleChoiceItems(episodes.map(SourceEpisode::title).toTypedArray(), currentIndex) {
                    dialog, index ->
                dialog.dismiss()
                val target = episodes.getOrNull(index)?.id ?: return@setSingleChoiceItems
                if (target != current.episodeId) launchEpisode(target)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun FrameLayout.installSystemBarInsets() {
        setOnApplyWindowInsetsListener { view, insets ->
            val safe = safeDrawingInsets(insets)
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom
            ) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
    }

    private fun safeDrawingInsets(insets: WindowInsets): SafeInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return SafeInsets(safe.left, safe.top, safe.right, safe.bottom)
        }
        @Suppress("DEPRECATION")
        var left = insets.systemWindowInsetLeft
        @Suppress("DEPRECATION")
        var top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        var right = insets.systemWindowInsetRight
        @Suppress("DEPRECATION")
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let { cutout ->
                left = max(left, cutout.safeInsetLeft)
                top = max(top, cutout.safeInsetTop)
                right = max(right, cutout.safeInsetRight)
                bottom = max(bottom, cutout.safeInsetBottom)
            }
        }
        return SafeInsets(left, top, right, bottom)
    }

    private fun initialViewport(): Viewport {
        val metrics = resources.displayMetrics
        return Viewport(
            FixedPx.fromPixels(metrics.widthPixels.coerceAtLeast(1)),
            FixedPx.fromPixels(metrics.heightPixels.coerceAtLeast(1)),
        )
    }

    private fun showFailure(failure: Throwable) {
        progress.visibility = android.view.View.GONE
        failureText.text = failure.message?.takeIf(String::isNotBlank) ?: "페이지를 불러오지 못했습니다"
        failureText.visibility = android.view.View.VISIBLE
    }

    private fun finishWithFailure(failure: Throwable) {
        setResult(RESULT_CANCELED)
        android.util.Log.e("ViewerActivity", "viewer launch failed", failure)
        finish()
    }

}
