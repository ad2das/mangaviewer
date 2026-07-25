package ml.melun.mangaview.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.R
import ml.melun.mangaview.Utils
import ml.melun.mangaview.activity.CaptchaActivity.REQUEST_CAPTCHA
import ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.NtkWebViewFallbackManager
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderLaunchPreparer
import ml.melun.mangaview.reader.ReaderImageCache
import ml.melun.mangaview.reader.AdoptedDrawableRegistry
import ml.melun.mangaview.reader.AdoptedDrawableIdentity
import ml.melun.mangaview.reader.InstalledDrawableQuery
import ml.melun.mangaview.reader.NtkAuthoritativeManifest
import ml.melun.mangaview.reader.NtkAuthoritativeManifestListener
import ml.melun.mangaview.reader.NtkSourceSpoolRegistry
import ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator
import ml.melun.mangaview.reader.NtkStripDigests
import ml.melun.mangaview.reader.NtkVisibleIdentityPolicy
import ml.melun.mangaview.reader.ReaderPreparedStore
import ml.melun.mangaview.reader.ReaderPipelinePolicy
import ml.melun.mangaview.reader.ReaderSessionListenerGate
import ml.melun.mangaview.reader.ReaderWarmupCoordinator
import ml.melun.mangaview.reader.ReaderSession
import ml.melun.mangaview.reader.StrictExactLaunchSeal
import ml.melun.mangaview.reader.ReaderSurfaceView
import ml.melun.mangaview.reader.ReaderStatusOverlayView
import ml.melun.mangaview.reader.ReaderTile
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.PerformanceMonitor
import ml.melun.mangaview.runtime.ViewerTelemetry
import kotlin.math.abs

class ReaderV2Activity : Activity(), ReaderSession.Listener, ReaderSurfaceView.WindowListener {
    data class TouchDeliverySnapshot(
        val samples: Int,
        val moveSamples: Int,
        val downEvents: Int,
        val upEvents: Int,
        val cancelEvents: Int,
        val invalidEventTimes: Int,
        val maxLagMs: Long,
        val downMaxLagMs: Long,
        val moveMaxLagMs: Long,
        val upMaxLagMs: Long,
        val cancelMaxLagMs: Long
    )

    data class FirstPhysicalDrawProof(
        val armedUptimeNanos: Long,
        val drawSequence: Long,
        val completedUptimeNanos: Long,
        val hardwareAccelerated: Boolean,
        val coverage: ReaderSurfaceView.VisibleCoverageSnapshot,
        val readiness: ReaderSurfaceView.PageReadinessSnapshot
    )

    private lateinit var renderView: ReaderSurfaceView
    private lateinit var status: ReaderStatusOverlayView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageView: TextView
    private lateinit var prevButton: Button
    private lateinit var episodeButton: Button
    private lateinit var nextButton: Button
    private lateinit var autoCutButton: Button
    private var readerRoot: FrameLayout? = null
    private var terminalImageFailureDescription: String? = null
    private var toolbarAttached = false
    private var toolbarWindowManager: WindowManager? = null
    private var session: ReaderSession? = null
    private data class StrictEarlySession(
        val path: String,
        val manifestDigest: String,
        val generation: Int,
        val seal: StrictExactLaunchSeal,
        val session: ReaderSession,
        val startClaimed: AtomicBoolean = AtomicBoolean(false)
    )
    private val strictEarlySessionLock = Any()
    @Volatile private var strictEarlySession: StrictEarlySession? = null
    private var strictNtkManifestSubscription: Closeable? = null
    private var strictNtkPendingSessionPath = ""
    private var preparedLaunchLease: ReaderPreparedStore.PreparedLease? = null
    private var preparedSessionBuildTask: AppDispatchers.TaskHandle? = null
    private var preparedSessionStartTask: AppDispatchers.TaskHandle? = null
    @Volatile
    private var preparedBuiltSession: ReaderSession? = null
    @Volatile
    private var preparedSessionStartBegan = false
    @Volatile
    private var preparedSurfaceBitmaps: Map<Int, Bitmap> = emptyMap()
    private var preparedSurfacePageCount = 0
    private var preparedSurfaceStartPage = 0
    private var preparedSurfaceStartOffset = 0
    private var preparedSurfaceViewportWidth = 0
    private var preparedSurfaceViewportHeight = 0
    private var preparedSurfaceKey: String? = null
    private var preparedSurfaceStartAtFirstPage = false
    @Volatile
    private var preparedSurfaceAdoptionActive = false
    private var preparedSessionPipelineStarted = false
    private var preparedFirstDrawFollowupPosted = false
    private val activeReaderSessionGeneration = AtomicInteger(0)
    @Volatile private var strictExactLaunchSeal: StrictExactLaunchSeal? = null
    @Volatile private var strictReaderSessionGeneration = -1
    @Volatile private var strictWorkerHandoffGeneration = -1
    private val strictRenderReadyLock = Any()
    private val strictRenderReadyPages = LinkedHashSet<Int>()
    private var strictRenderReadyGeneration = -1
    @Volatile private var strictAllImagesReadyPublished = false
    @Volatile private var strictRollingHistoricalScene = false
    private val strictAuthoritativeInstallLock = Any()
    private val pendingStrictAuthoritativeInstalls = LinkedHashMap<Int, PendingStrictTileInstall>()
    private var strictAuthoritativeInstallFlushScheduled = false
    private var strictTelemetryOwned = false
    private var strictTelemetryClosed = false
    private var strictTelemetryGeneration = 0L
    private var strictTelemetryEpisodePath = ""
    private var strictTelemetryManifestDigest = ""
    private var strictTelemetryLifecycleEpoch = 0L
    private var strictTelemetryActualInLifecycle = false
    /**
     * A HWUI commit can arrive while the task is animating to the launcher.  Such a commit is
     * useful for neither the user nor lifecycle proof and must not republish `actual:` semantics
     * after [onPause] retired them.  The gate is armed only after this window owns focus again;
     * that focus edge also forces a fresh reader draw.
     */
    private var strictTelemetryForegroundCommitArmed = false
    private var strictTelemetryValidCommittedFrames = 0L
    private var strictTelemetryInvalidCommittedFrames = 0L
    private var strictTelemetryViewportDefectFrames = 0L
    private var strictTelemetryRunwayDefectFrames = 0L
    private var strictTelemetryIdentityInvalidFrames = 0L
    private var strictTelemetryInitialBlankFrames = 0L
    private var strictTelemetryObservedSources = BooleanArray(0)
    private var strictTelemetryLastFirstPage = -1
    private var strictTelemetryLastCleanDisplayPage = -1
    private var strictTelemetryLastCleanSourcePage = -1
    private var strictTelemetryBottomCommitWaitLogs = 0
    private var strictTelemetryLastScrollOffset = Float.NaN
    private var strictTelemetryLastCommitNanos = 0L
    private var strictTelemetryVelocityPxPerSecond = 0f
    private var pagesReady = false
    private var toolbarVisible = false
    private var autoCut = false
    private var pageCount = 0
    private var currentPage = 0
    private var currentManga: Manga? = null
    private var currentTitle: Title? = null
    private var resultIntent: Intent? = null
    private var toolbarTouchSlop = 0
    private var toolbarDownRawX = 0f
    private var toolbarDownRawY = 0f
    private var toolbarForwardingScroll = false
    private var lastSavedEpisodeId = -1
    private var lastSavedPage = -1
    private var lastSavedOffset = Int.MIN_VALUE
    private var lastSavedSide = -1
    private var lastDisplayedPageText = ""
    private var lastDisplayedEpisodeKey = ""
    private var lastDisplayedEpisodeTitle = ""
    private var pendingAnchorAfterBusy = -1
    private var adjacentNavigationInFlight = false
    private var cachedPreviousEpisode: Manga? = null
    private var cachedNextEpisode: Manga? = null
    private var episodeListFetchAttempted = false
    @Volatile private var destroyed = false
    private var progressSaveArmed = false
    private var progressMovedInGesture = false
    @Volatile private var interactionMaintenancePending = false
    @Volatile private var progressSavePending = false
    private var touchDeliveryStatsArmed = false
    private var touchDeliverySamples = 0
    private var touchDeliveryMoveSamples = 0
    private var touchDeliveryDownEvents = 0
    private var touchDeliveryUpEvents = 0
    private var touchDeliveryCancelEvents = 0
    private var touchDeliveryInvalidEventTimes = 0
    private var touchDeliveryMaxLagMs = 0L
    private var touchDeliveryDownMaxLagMs = 0L
    private var touchDeliveryMoveMaxLagMs = 0L
    private var touchDeliveryUpMaxLagMs = 0L
    private var touchDeliveryCancelMaxLagMs = 0L
    @Volatile
    private var firstResumeArmedUptimeNanos = 0L
    @Volatile
    private var firstFocusArmedUptimeNanos = 0L
    @Volatile
    private var firstResumePhysicalDrawProof: FirstPhysicalDrawProof? = null
    @Volatile
    private var firstFocusPhysicalDrawProof: FirstPhysicalDrawProof? = null
    private var pendingInitialRestorePage = -1
    private var pendingInitialRestoreOffset = 0
    @Volatile
    private var blockingStatusForTest = ""
    private val progressHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private val criticalUiHandler = Handler.createAsync(Looper.getMainLooper())
    private var pendingProgressInfo: ReaderSession.PageInfo? = null
    private var pendingProgressOffset = 0
    private var pendingBoundaryStatus = false
    private var pendingBoundaryCaptchaRetry = false
    private var pendingPrependRevealRequests = 0
    private var pendingAppendRevealRequests = 0
    private var readerWindowBusy = false
    private var deferredBoundaryDirection = 0
    private var deferredBoundaryAnchor = -1
    private var deferredAppendPageCount = 0
    private var deferredAppendPageGeneration = 0
    private var lastAppendUntilReadyCount = 0
    private var lastAppendUntilReadyPageCount = 0
    private var lastAppendUntilReadyReadyCount = 0
    private var lastAppendUntilReadyLogMs = 0L
    private var pendingBoundaryStartInteractionMs = 0L
    private var lastReaderInteractionMs = 0L
    private var lastReaderBusyMs = 0L
    private var lastBusyWindowLogMs = 0L
    private var lastLoggedWindowBusy = false
    private var lastInteractionNoteMs = 0L
    private var preparedSessionFirstDrawUptimeMs = 0L
    private val preparedSessionQuietRunnable = Runnable {
        startPreparedSessionWhenInputQuiet()
    }
    private var lastAckQuietExtendMs = 0L
    private var lastNtkWebViewFallbackQuietExtendMs = 0L
    private var deferredEpisodeUpdatePage = -1
    private var deferredEpisodeUpdateOffset = 0
    private var deferredEpisodeUpdateSaveProgress = false
    private val deferredEpisodeUpdateRunnable = Runnable {
        flushDeferredEpisodeUpdate()
    }
    private val missingEpisodePromptState = MissingEpisodeNavigator.PromptState()
    private var pendingCaptchaRetryManga: Manga? = null
    private var pendingCaptchaRetryTitle: Title? = null
    private var pendingCaptchaRetryStartAtFirstPage = false
    private var pendingCaptchaRetryAction = CAPTCHA_RETRY_READER
    private var pendingCaptchaRetryNext = true
    private var pendingCaptchaRetryDirection = 0
    private var pendingCaptchaRetryAnchor = -1
    private var initialStatusPending = false
    private var initialDrawGateOpen = true
    private var initialDrawGateView: View? = null
    private var initialDrawGateListener: ViewTreeObserver.OnPreDrawListener? = null
    private var viewerLaunchStartedAtMs = 0L
    private var viewerLaunchSourceSite = ""
    @Volatile
    private var ntkLaunchPreflightPath: String? = null
    @Volatile
    private var ntkInitialDiscoveryPath: String? = null
    @Volatile
    private var hybridNtkBrowserActive = false
    @Volatile
    private var hybridNtkNativeHandoffStarted = false
    @Volatile
    private var hybridNtkNativeHandoffPending = false
    @Volatile
    private var hybridNtkProgressiveNativeSeedActive = false
    @Volatile
    private var hybridNtkForceBrowserAuthoritative = false
    @Volatile
    private var hybridNtkFirstDrawableReady = false
    @Volatile
    private var hybridNtkViewportReady = false
    @Volatile
    private var hybridNtkPumpAllRequested = false
    private var hybridNtkWebView: WebView? = null
    @Volatile
    private var hybridNtkScrollSnapshot: NtkBrowserSessionBroker.ScrollSnapshot? = null
    @Volatile
    private var hybridNtkCoverageSnapshot: NtkBrowserSessionBroker.VisibleCoverageSnapshot? = null
    @Volatile
    private var lastNativeVisibleCoveragePath = ""
    @Volatile
    private var lastNativeVisibleCoverageSnapshot: ReaderSurfaceView.VisibleCoverageSnapshot? = null
    @Volatile
    private var hybridNtkAutoNextStartedAtMs = 0L
    @Volatile
    private var hybridNtkNextPreparePath = ""
    @Volatile
    private var hybridNtkNextPreparedPath = ""
    @Volatile
    private var hybridNtkNextPrepareInFlight = false
    @Volatile
    private var hybridNtkAckRecoveryPath = ""
    @Volatile
    private var hybridNtkAckRecoveryStartedAtMs = 0L
    @Volatile
    private var hybridNtkNativeImageFetchPath = ""
    private var hybridNtkEarlyUrlPollPath = ""
    private var hybridNtkEarlyUrlPollAttempts = 0
    private var hybridNtkEarlyUrlListenerDisposer: (() -> Unit)? = null
    private val hybridNtkEarlyUrlPollRunnable = object : Runnable {
        override fun run() {
            pollHybridNtkEarlyImageUrls()
        }
    }
    private val deferredAppendPagesRunnable = object : Runnable {
        override fun run() {
            val count = deferredAppendPageCount
            val generation = deferredAppendPageGeneration
            deferredAppendPageCount = 0
            deferredAppendPageGeneration = 0
            if (count > 0 && isDeferredPagePublishActive(generation)) {
                onPagesAppended(count)
            }
        }
    }
    private var deferredCurrentReadyRunwayPageCount = 0
    private var deferredCurrentReadyRunwayScheduled = false
    private var deferredCurrentReadyRunwayGeneration = 0
    private val deferredCurrentReadyRunwayRunnable = object : Runnable {
        override fun run() {
            val count = deferredCurrentReadyRunwayPageCount
            val generation = deferredCurrentReadyRunwayGeneration
            deferredCurrentReadyRunwayPageCount = 0
            deferredCurrentReadyRunwayScheduled = false
            deferredCurrentReadyRunwayGeneration = 0
            if (count > pageCount && isDeferredPagePublishActive(generation)) {
                applyCurrentReadyRunwayPageCount(count, "deferred_ready_runway")
            }
        }
    }

    private fun isReaderSessionGenerationActive(generation: Int): Boolean {
        return !destroyed && !isFinishing && generation == activeReaderSessionGeneration.get()
    }

    private fun isDeferredPagePublishActive(generation: Int): Boolean {
        return pagesReady && session != null && isReaderSessionGenerationActive(generation)
    }

    private fun scheduleDeferredAppendPages(count: Int, delayMs: Long) {
        if (count <= 0 || destroyed || isFinishing) return
        deferredAppendPageCount = maxOf(deferredAppendPageCount, count)
        deferredAppendPageGeneration = activeReaderSessionGeneration.get()
        statusHandler.removeCallbacks(deferredAppendPagesRunnable)
        statusHandler.postDelayed(deferredAppendPagesRunnable, delayMs)
    }

    private fun clearDeferredAppendPublishCallbacks() {
        statusHandler.removeCallbacks(deferredAppendPagesRunnable)
        statusHandler.removeCallbacks(deferredCurrentReadyRunwayRunnable)
        deferredAppendPageCount = 0
        deferredAppendPageGeneration = 0
        deferredCurrentReadyRunwayPageCount = 0
        deferredCurrentReadyRunwayGeneration = 0
        deferredCurrentReadyRunwayScheduled = false
        lastAppendUntilReadyCount = 0
        lastAppendUntilReadyPageCount = 0
        lastAppendUntilReadyReadyCount = 0
        lastAppendUntilReadyLogMs = 0L
    }

    private fun activeReaderSessionListener(generation: Int): ReaderSession.Listener {
        val registry = AdoptedDrawableRegistry(
            policy = if (strictExactLaunchSeal != null) {
                AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE
            } else {
                AdoptedDrawableRegistry.Policy.LEGACY_PREPARED_BITMAP_MATCH
            },
            structurePolicy = if (strictExactLaunchSeal != null) {
                AdoptedDrawableRegistry.StructurePolicy.SHIFT_INDEXES
            } else {
                AdoptedDrawableRegistry.StructurePolicy.INVALIDATE_ALL
            }
        )
        if (preparedSurfaceAdoptionActive) {
            for (index in 0 until preparedSurfacePageCount) {
                val bitmap = preparedSurfaceBitmaps[index]
                if (bitmap != null) {
                    registry.adoptPreparedStoreBitmap(index, bitmap)
                } else {
                    registry.markLegacyPreparedStoreIndex(index)
                }
            }
        }
        return ReaderSessionListenerGate(
            generation = generation,
            isActive = ::isReaderSessionGenerationActive,
            adopted = registry,
            installed = InstalledDrawableQuery { index -> renderView.hasPageDrawable(index) },
            downstream = this
        )
    }
    @Volatile
    private var firstDrawableMetricLogged = false
    @Volatile
    private var firstDrawableLoggedAtMs = 0L
    @Volatile
    private var firstDrawableLoggedElapsedAtMs = 0L
    @Volatile
    private var firstDrawableElapsedMsForTest = -1L
    private var initialDrawGateNtkTimeoutDeferrals = 0
    private var drawableReadyDescriptionPosted = false
    private var initialStartAtFirstPage = false
    private val launchDrawableMetricPages = HashSet<Int>()
    private val launchDrawableElapsedMsByPage = LinkedHashMap<Int, Long>()
    private val pendingPageBitmaps = LinkedHashMap<Int, Bitmap>()
    private val pendingPageTiles = LinkedHashMap<Int, PendingPageTiles>()
    private val pendingPageCards = LinkedHashMap<Int, String>()
    private val pendingPageErrors = LinkedHashMap<Int, String>()
    private var pendingPageCallbackFlushScheduled = false
    private var lastPendingPageCallbackFlushLogMs = 0L
    private var lastPagesAppendedHotPathLogMs = 0L
    private var lastNtkTailDecisionLogMs = 0L
    private var initialSoftwareRunwayPrepareRequested = false
    private var initialSoftwareRunwayPrepareCompleted = false
    private var initialSoftwareRunwayPrepareReason = ""
    private var initialSoftwareRunwayPrepareView: ReaderSurfaceView? = null
    private var initialSoftwareRunwayPrepareLayoutListener: View.OnLayoutChangeListener? = null
    private val initialSoftwareRunwayPrepareRunnable = Runnable {
        prepareInitialSoftwareRunwayIfReady()
    }
    private val pendingPageCallbackFlushRunnable = object : Runnable {
        override fun run() {
            pendingPageCallbackFlushScheduled = false
            flushPendingPageCallbacksNow()
        }
    }
    private val strictAuthoritativeInstallFlushRunnable = Runnable {
        flushStrictAuthoritativeInstallsNow()
    }
    @Volatile
    private var preRenderedInitialDrawableIndex = -1
    @Volatile
    private var preRenderedInitialBitmap: Bitmap? = null
    @Volatile
    private var preRenderedInitialTiles: List<ReaderTile>? = null
    private val preRenderedInitialContinuousBitmaps = LinkedHashMap<Int, Bitmap>()
    private val preRenderedInitialContinuousTiles = LinkedHashMap<Int, List<ReaderTile>>()
    private var deferredNtkAckPreflightManga: Manga? = null
    private var ntkAckCaptchaRequested = false
    private var pendingInitialNtkCaptchaDeferrals = 0
    private var ntkInitialProofRetryPath = ""
    private var ntkInitialProofRetryCount = 0
    @Volatile
    private var ntkImmediateNativeAckProofPath = ""
    @Volatile
    private var strictDirectManifestAckSkipPath = ""
    private var deferredNtkAckPreflightBlockProbeAttempts = 0
    private val ntkAckPreflightGeneration = AtomicInteger(0)
    private val deferredNtkAckPreflightTimeoutRunnable = Runnable {
        startDeferredNtkAckPreflight("timeout")
    }
    private val deferredNtkAckPreflightQuietRunnable = Runnable {
        startDeferredNtkAckPreflight("quiet")
    }
    private val deferredNtkAckPreflightBlockProbeRunnable = Runnable {
        maybeStartDeferredNtkAckForInitialCloudflareProbe()
    }
    private val initialDrawGateTimeoutRunnable: Runnable = object : Runnable {
        override fun run() {
            if (shouldDeferNtkInitialDrawGateTimeout()) {
                initialDrawGateNtkTimeoutDeferrals++
                Log.d(
                    TAG,
                    "initial_draw_gate_timeout_deferred reason=ntk_anchor_pending,count=$initialDrawGateNtkTimeoutDeferrals"
                )
                statusHandler.postDelayed(this, NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MS)
                return
            }
            if (!pagesReady && !destroyed && !isFinishing) {
                initialStatusPending = false
                statusHandler.removeCallbacks(showInitialStatusRunnable)
                status.visibility = TextView.VISIBLE
                status.text = displayEpisodeTitle(currentManga, currentTitle)
            }
            releaseInitialDrawGate("timeout")
        }
    }
    private val saveProgressRunnable = Runnable {
        saveCurrentReadingProgress()
        pendingProgressInfo = null
    }
    private val drawableReadyDescriptionRunnable = object : Runnable {
        override fun run() {
            if (destroyed || isFinishing || drawableReadyDescriptionPosted) return
            if (isVisibleViewportReady()) {
                logVisibleViewportReadyMetric()
            } else {
                if (::renderView.isInitialized) renderView.requestVisibleCoverageFrame()
                statusHandler.postDelayed(this, DRAWABLE_READY_CHECK_INTERVAL_MS)
            }
        }
    }
    private val showInitialStatusRunnable = Runnable {
        if (initialStatusPending && !pagesReady && !destroyed && !isFinishing) {
            status.visibility = TextView.VISIBLE
            status.text = displayEpisodeTitle(currentManga, currentTitle)
        }
    }
    private val showBoundaryStatusRunnable = Runnable {
        if (pendingBoundaryStatus && pagesReady && !destroyed && !isFinishing) {
            status.visibility = TextView.VISIBLE
            status.text = "회차 연결 중"
        }
    }
    private val showAdjacentStatusRunnable = Runnable {
        if (adjacentNavigationInFlight && !destroyed && !isFinishing) {
            status.visibility = TextView.VISIBLE
            status.text = "회차 확인 중"
        }
    }
    private val deferredBoundaryAppendRunnable: Runnable = Runnable {
        val remainingQuietMs = boundaryAppendQuietRemainingMs()
        if (remainingQuietMs > 0L) {
            statusHandler.postDelayed(deferredBoundaryAppendRunnable, remainingQuietMs)
        } else {
            flushDeferredBoundaryAppend()
        }
    }

    private data class PendingPageTiles(
        val pageWidth: Int,
        val pageHeight: Int,
        val tiles: List<ReaderTile>
    )

    private data class PendingStrictTileInstall(
        val generation: Int,
        val pageIndex: Int,
        val sourceIndex: Int,
        val pageWidth: Int,
        val pageHeight: Int,
        val tiles: List<ReaderTile>,
        val proof: ReaderPreparedStore.PreparedOriginalProof,
        val identity: AdoptedDrawableIdentity
    )

    private data class AdjacentResolution(
        val target: Manga?,
        val title: Title?,
        val result: Int,
        val fetchedEpisodes: Boolean,
        val preparedKey: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // The reader has no text input. Keeping it out of the IME focus chain avoids an
        // unnecessary input-connection teardown/start burst exactly when the first physical
        // gesture can arrive after the Activity transition.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        super.onCreate(savedInstanceState)
        PerformanceMonitor.attach(this)
        PerformanceMonitor.screen("viewer")

        val processPayload = ReaderLaunchPayloadStore.take(
            intent.getStringExtra(ReaderLaunchPayloadStore.EXTRA_READER_KEY)
        )
        val launchPayload = processPayload
            ?: ReaderLaunchPayloadStore.restoreCompactReaderPayload(intent)
        val title = launchPayload?.title
            ?: Gson().fromJson<Title?>(intent.getStringExtra("title"), object : TypeToken<Title?>() {}.type)
        val manga = launchPayload?.manga
            ?: Gson().fromJson<Manga?>(intent.getStringExtra("manga"), object : TypeToken<Manga?>() {}.type)
        if (manga == null) {
            releaseInitialDrawGate("no_manga")
            finish()
            return
        }
        currentManga = manga
        currentTitle = title
        viewerLaunchStartedAtMs = intent.getLongExtra("viewerLaunchStartedAtMs", 0L)
        viewerLaunchSourceSite = intent.getStringExtra("viewerLaunchSourceSite")
            ?: title?.sourceSite
            ?: manga.title?.sourceSite
            ?: ""
        if (title != null) {
            manga.title = title
            manga.titleId = title.id
            title.eps?.let { manga.setEps(it) }
        }
        val preparedKey = launchPayload?.preparedKey
            ?: intent.getStringExtra(ReaderLaunchPreparer.EXTRA_PREPARED_KEY)
        val ntkLaunchPreflightStarted = intent.getBooleanExtra("viewerNtkAckPreflightStarted", false)
        val ntkPath = manga.ntkEpisodePath?.trim().orEmpty()
        val strictNtkEpisode = isStrictNtkEpisodePath(ntkPath)
        val startAtFirstPage = intent.getBooleanExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, false)
        if (strictNtkEpisode) {
            strictTelemetryEpisodePath = NtkStripDigests.normalizeEpisodePath(ntkPath)
            strictTelemetryGeneration = if (ViewerTelemetry.hasActiveSession() &&
                (ViewerTelemetry.isActiveEpisode(ntkPath) ||
                    ViewerTelemetry.isActiveEpisode(strictTelemetryEpisodePath))
            ) {
                PerformanceMonitor.viewerStarted(
                    strictTelemetryWorkId(manga),
                    strictTelemetryEpisodePath,
                    manga.mode.toString()
                )
                ViewerTelemetry.activeGeneration()
            } else {
                ViewerTelemetry.viewerOpen(
                    strictTelemetryWorkId(manga),
                    strictTelemetryEpisodePath,
                    manga.mode.toString()
                )
            }
            strictTelemetryOwned = strictTelemetryGeneration > 0L
        }
        if (ntkPath.startsWith("/webtoon/") || ntkPath.startsWith("/manhwa/")) {
            MainApplication.noteNtkForegroundViewerPath(ntkPath)
        }
        if (strictNtkEpisode) {
            startStrictNtkDiscovery(manga, "activity_create_before_surface")
        }
        if (tryStartPreparedNtkSurfaceFastPath(
                manga,
                title,
                preparedKey,
                ntkPath,
                ntkLaunchPreflightStarted,
                startAtFirstPage
            )
        ) {
            return
        }
        val modernProtectedNumericViewer = ntkPath.matches(
            Regex("^/(?:webtoon|manhwa)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
        ) && getHttpClient().isModernNtkGuardRootForPath(ntkPath)
        val nativeEarlyUrls = if (ntkPath.isNotBlank()) {
            ReaderImageCache.earlyNtkImageUrls(ntkPath, SystemClock.elapsedRealtime() - 30000L)
        } else {
            emptyList()
        }
        val nativeTrustedCount = if (ntkPath.isNotBlank()) {
            ReaderImageCache.trustedNtkImageApiCount(ntkPath, SystemClock.elapsedRealtime() - 30000L)
        } else {
            0
        }
        val hasBrowserProtectedManifest = ntkPath.isNotBlank() &&
            nativeEarlyUrls.any { it.contains("/api/m/i?", ignoreCase = true) }
        val kpDirectNativeUrls = if (isNtkKpWebtoonSlugPath(ntkPath)) {
            nativeEarlyUrls
                .filter {
                    !it.contains("/api/m/i?", ignoreCase = true) &&
                        isNtkUploadCdnImageUrl(it) &&
                        !isNtkUploadDescriptorUrl(it)
                }
                .distinct()
        } else {
            emptyList()
        }
        val kpSlugPath = isNtkKpWebtoonSlugPath(ntkPath)
        val kpBoardDirectNativeUrls = kpDirectNativeUrls.any {
            it.contains("/board_uploads/", ignoreCase = true)
        }
        val kpExpectedNativeCount = if (!kpSlugPath) {
            0
        } else if (kpBoardDirectNativeUrls && kpDirectNativeUrls.size >= 4) {
            kpDirectNativeUrls.size
        } else {
            maxOf(
                nativeTrustedCount,
                manga.ntkImageCount.coerceAtLeast(0),
                ntkCachedViewerPayloadImageCount(ntkPath)
            )
        }
        val kpTrustedNativeManifest = isNtkKpWebtoonSlugPath(ntkPath) &&
            kpExpectedNativeCount > 0 &&
            kpDirectNativeUrls.size >= kpExpectedNativeCount
        if (!strictNtkEpisode && kpTrustedNativeManifest) {
            val manifest = kpDirectNativeUrls.take(kpExpectedNativeCount)
            manga.setImgs(ArrayList(manifest))
            manga.ntkImageCount = manifest.size
            Log.d(
                TAG,
                "reader_activity_kp_native_manifest_short_circuit path=$ntkPath," +
                    "count=${manifest.size},expected=$kpExpectedNativeCount,trusted=$nativeTrustedCount"
            )
        }
        val hasPreparedNativeReader = !preparedKey.isNullOrBlank() && !hasBrowserProtectedManifest
        val preferHybridUntrustedNumericPayload = shouldPreferHybridForUntrustedNumericPayload(
            manga,
            ntkPath,
            nativeEarlyUrls,
            nativeTrustedCount
        )
        val nativeDirectPayloadUrls = if (!kpSlugPath && ntkPath.isNotBlank()) {
            nativeEarlyUrls
                .filter {
                    it.isNotBlank() &&
                        !it.contains("/api/m/i?", ignoreCase = true) &&
                        !isNtkUploadDescriptorUrl(it) &&
                        isNtkUploadCdnImageUrl(it)
                }
                .distinct()
        } else {
            emptyList()
        }
        val nativeDirectPayloadExpected = if (nativeDirectPayloadUrls.isNotEmpty()) {
            val knownDirectCount = maxOf(
                nativeTrustedCount,
                manga.ntkImageCount.takeIf { it > 0 && it != 64 && it != 128 } ?: 0
            )
            if (modernProtectedNumericViewer && knownDirectCount > 0) {
                knownDirectCount
            } else {
                maxOf(ntkCachedViewerPayloadImageCount(ntkPath), knownDirectCount)
            }
        } else {
            0
        }
        val nativeDirectPayloadComplete = nativeDirectPayloadExpected > 0 &&
            nativeDirectPayloadUrls.size >= nativeDirectPayloadExpected
        if (!strictNtkEpisode && nativeDirectPayloadComplete) {
            val manifest = nativeDirectPayloadUrls.take(nativeDirectPayloadExpected)
            manga.setImgs(ArrayList(manifest))
            manga.ntkImageCount = manifest.size
            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                ntkPath,
                manifest,
                "native-complete-payload-direct"
            )
            Log.d(
                TAG,
                "reader_activity_native_payload_direct_complete path=$ntkPath," +
                    "count=${manifest.size},webAuth=false"
            )
        }
        val nativeGeneratedOwner = shouldPreferNativeGeneratedNtkReader(manga)
        val preferNativeGeneratedReader =
            modernProtectedNumericViewer ||
                (!preferHybridUntrustedNumericPayload &&
                    (nativeDirectPayloadComplete || nativeGeneratedOwner))
        val useHybridNtkBrowser = if (strictNtkEpisode) {
            false
        } else if (modernProtectedNumericViewer) {
            false
        } else if (kpSlugPath) {
            !kpTrustedNativeManifest
        } else {
            preferHybridUntrustedNumericPayload ||
                (!hasPreparedNativeReader &&
                !kpTrustedNativeManifest &&
                !preferNativeGeneratedReader &&
                shouldUseNtkHybridBrowserReader(manga, allowGeneratedNumeric = true))
        }
        Log.d(
            "ViewerPerf",
            "reader_ntk_hybrid_decision path=$ntkPath,use=$useHybridNtkBrowser," +
                "kp=$kpSlugPath,webAuth=false,kpTrusted=$kpTrustedNativeManifest," +
                "prepared=${!preparedKey.isNullOrBlank()},hasBrowserProtected=$hasBrowserProtectedManifest," +
                "trusted=$nativeTrustedCount,early=${nativeEarlyUrls.size},kpDirect=${kpDirectNativeUrls.size}," +
                "payloadDirect=${nativeDirectPayloadUrls.size},payloadExpected=$nativeDirectPayloadExpected," +
                "preferNative=$preferNativeGeneratedReader,protectedNative=$modernProtectedNumericViewer," +
                "untrustedPayloadHybrid=$preferHybridUntrustedNumericPayload"
        )
        val hasNativeDirectManifest = !strictNtkEpisode &&
            (hasCompleteNativeDirectManifest(manga) || kpTrustedNativeManifest)
        if (strictNtkEpisode) {
            ntkInitialDiscoveryPath = ntkPath
            Log.d(TAG, "reader_ntk_strict_legacy_probe_skip path=$ntkPath")
        } else if (preparedKey.isNullOrBlank() && !useHybridNtkBrowser && !hasNativeDirectManifest) {
            primeSlugWebtoonInitialImageAtActivityStart(manga, ntkLaunchPreflightStarted)
        } else if (useHybridNtkBrowser) {
            Log.d(TAG, "reader_activity_initial_prime_skip_hybrid path=${manga.ntkEpisodePath}")
        } else if (hasNativeDirectManifest) {
            Log.d(TAG, "reader_activity_initial_prime_skip_direct_manifest path=${manga.ntkEpisodePath}")
        } else {
            Log.d(
                TAG,
                "reader_activity_initial_prime_skip_prepared path=${manga.ntkEpisodePath}," +
                "prepared=true"
            )
        }
        if (strictNtkEpisode) {
            ntkInitialDiscoveryPath = ntkPath
        } else if (!useHybridNtkBrowser && !hasNativeDirectManifest) {
            startInitialNtkImageDiscovery(manga, ntkLaunchPreflightStarted)
        } else if (hasNativeDirectManifest) {
            ntkInitialDiscoveryPath = manga.ntkEpisodePath
            Log.d(TAG, "reader_ntk_initial_image_discovery_skip_direct_manifest path=${manga.ntkEpisodePath}")
        } else {
            ntkInitialDiscoveryPath = manga.ntkEpisodePath
            Log.d(TAG, "reader_ntk_initial_image_discovery_skip_hybrid path=${manga.ntkEpisodePath}")
        }
        ReaderChromeStyler.applyReaderWindow(this)
        if (ntkLaunchPreflightStarted) {
            ntkLaunchPreflightPath = manga.ntkEpisodePath
        }
        val naverOriginalLaunchPreflight = ntkLaunchPreflightStarted &&
            isNaverOriginalNtkEpisodePath(manga.ntkEpisodePath)
        if (naverOriginalLaunchPreflight) {
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_skip reason=launch_preflight_naver_original,path=${manga.ntkEpisodePath}")
        }
        if (ntkLaunchPreflightStarted) {
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_parallel reason=launch_preflight,path=${manga.ntkEpisodePath}")
        }
        val allowInitialAckBeforeFirstDrawable = shouldAllowInitialNtkAckBeforeFirstDrawable(manga)
        if (strictNtkEpisode) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_strict_legacy_ack_skip path=$ntkPath")
        } else if (useHybridNtkBrowser) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_ack_preflight_skip_hybrid_initial path=${manga.ntkEpisodePath}")
        } else if (kpSlugPath) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_ack_preflight_skip_kp_native_initial path=${manga.ntkEpisodePath}")
        } else if (hasNativeDirectManifest) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_ack_preflight_skip_initial_direct_manifest path=${manga.ntkEpisodePath}")
        } else if (shouldDeferInitialNtkAckPreflight(manga)) {
            deferredNtkAckPreflightManga = manga
            deferredNtkAckPreflightBlockProbeAttempts = 0
            Log.d(TAG, "reader_ntk_ack_deferred_until_first_drawable path=${manga.ntkEpisodePath}")
            val existing = ntkLaunchPreflightStarted
            Log.d(TAG, "reader_ntk_ack_preflight_deferred path=${manga.ntkEpisodePath},nativeStarted=$existing")
        } else {
            startCurrentNtkAckPreflight(manga, allowInitialAckBeforeFirstDrawable)
        }
        // ReaderChromeStyler owns the single black window clear. Keeping black
        // backgrounds on both the root and the renderer adds two full-screen
        // GPU fills underneath every real bitmap frame.
        val root = FrameLayout(this)
        renderView = ReaderSurfaceView(this).also {
            it.id = R.id.strip
            it.isClickable = true
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.contentDescription = null
            it.setWindowListener(this)
            it.setSurfaceAttachmentDeferredUntilActualPixels(strictNtkEpisode)
            it.setSourceNativeWebtoonCompositingEnabled(
                manga.baseMode == MTitle.base_webtoon || ntkPath.startsWith("/webtoon/")
            )
            // The direct strict session is the production cold path. Keep its established tile
            // identity rules, but make every decoded current/forward tile GPU-resident before it
            // enters the downward viewport. Inline-strip proof semantics are a different concern.
            it.setForwardNativeTexturePrewarmEnabled(strictNtkEpisode)
        }
        status = ReaderStatusOverlayView(this).apply {
            text = "로딩 중"
            setTextColor(0xffcccccc.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }
        renderView.setPageGapPx(pageGapForBaseMode(manga.baseMode))
        status.text = displayEpisodeTitle(manga, title)
        readerRoot = root
        ensureRenderViewAttached()
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        if (
            viewerLaunchStartedAtMs > 0L &&
            manga.isOnline
        ) {
            val ntkLaunch = viewerLaunchSourceSite.equals("ntk", ignoreCase = true) ||
                manga.ntkEpisodePath?.isNotBlank() == true
            val gateTimeoutMs = if (ntkLaunch) {
                0L
            } else if (viewerLaunchSourceSite.equals("ntk", ignoreCase = true)) {
                NTK_INITIAL_DRAW_GATE_TIMEOUT_MS
            } else {
                INITIAL_DRAW_GATE_TIMEOUT_MS
            }
            if (gateTimeoutMs > 0L) {
                installInitialDrawGate(root, gateTimeoutMs)
            } else {
                Log.d(TAG, "initial_draw_gate_skip reason=ntk,path=${manga.ntkEpisodePath}")
            }
        }
        setContentView(root)
        if (
            !useHybridNtkBrowser &&
            ReaderWarmupCoordinator.ownsKnownUrlsDecode(preparedKey)
        ) {
            Log.d(
                TAG,
                "reader_native_direct_anchor_prerender_join_prepared_owner path=${manga.ntkEpisodePath}"
            )
        }
        if (viewerLaunchStartedAtMs > 0L) {
            Log.d("ViewerPerf", "reader_activity_create_from_launch source=$viewerLaunchSourceSite ms=${SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs}")
        }
        val ntkLaunch = viewerLaunchSourceSite.equals("ntk", ignoreCase = true) ||
            manga.ntkEpisodePath?.isNotBlank() == true
        val startReaderPipeline = Runnable {
            if (destroyed || isFinishing) return@Runnable
            if (strictNtkEpisode) {
                startStrictReaderSessionWhenExactReady(
                    manga,
                    title,
                    preparedKey,
                    startAtFirstPage,
                    clearViewImmediately = true
                )
            } else if (useHybridNtkBrowser) {
                startNtkHybridBrowserReader(
                    manga,
                    title,
                    startAtFirstPage
                )
            } else {
                startReaderSession(
                    manga,
                    title,
                    preparedKey,
                    startAtFirstPage
                )
                scheduleInitialNtkApiPrefetchAfterDrawable(manga, naverOriginalLaunchPreflight)
            }
            if (
                !strictNtkEpisode &&
                !useHybridNtkBrowser &&
                deferredNtkAckPreflightManga === manga &&
                !shouldPreferNativeGeneratedNtkReader(manga)
            ) {
                prepareDeferredNtkAckChallenge(manga)
            } else if (!useHybridNtkBrowser && deferredNtkAckPreflightManga === manga) {
                Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_skip_generated_native path=${manga.ntkEpisodePath}")
            }
            updateResultEpisode(manga)
            if (!manga.isOnline) p?.removeViewerBookmark(manga)
        }
        if (ntkLaunch) {
            Log.d(TAG, "reader_activity_start_ntk_pipeline_immediate path=${manga.ntkEpisodePath}")
        }
        startReaderPipeline.run()
    }

    private fun tryStartPreparedNtkSurfaceFastPath(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        ntkPath: String,
        ntkLaunchPreflightStarted: Boolean,
        startAtFirstPage: Boolean
    ): Boolean {
        if (preparedKey.isNullOrBlank() || !manga.isOnline) return false
        if (!ntkPath.startsWith("/webtoon/") && !ntkPath.startsWith("/manhwa/")) return false

        val viewportWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val viewportHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val lease = ReaderPreparedStore.claimWindowReady(preparedKey, manga, viewportWidth) ?: return false
        val snapshot = lease.snapshot
        val images = snapshot.images
        if (images.isNullOrEmpty() ||
            !preparedNtkImagesMatchExactEpisode(manga, ntkPath, images)
        ) {
            Log.d(
                TAG,
                "reader_prepared_surface_reject path=$ntkPath,reason=images,count=${images?.size ?: 0}"
            )
            lease.close()
            return false
        }
        if (isStrictNtkEpisodePath(ntkPath)) {
            val authority = NtkSourceSpoolRegistry.currentAuthoritativeManifest(ntkPath)
            val exactPreparedAssets = images.map(NtkStripDigests::canonicalAsset)
            if (authority?.isProductionClaimable != true ||
                authority.seal.normalizedCanonicalAssets != exactPreparedAssets
            ) {
                Log.d(
                    TAG,
                    "reader_prepared_surface_reject path=$ntkPath," +
                        "reason=strict_exact_authority,count=${images.size}"
                )
                lease.close()
                return false
            }
        }

        val completeBitmaps = LinkedHashMap(snapshot.bitmaps)
        val startPage = if (startAtFirstPage) {
            0
        } else {
            snapshot.startPage.coerceIn(0, images.lastIndex)
        }
        val startOffset = if (startAtFirstPage) {
            0
        } else {
            (p?.getViewerBookmarkOffset(manga) ?: 0).coerceAtLeast(0)
        }

        val candidateRoot = FrameLayout(this)
        val candidateRender = ReaderSurfaceView(this).also {
            it.id = R.id.strip
            it.isClickable = true
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.contentDescription = null
            it.setWindowListener(this)
            it.setPageGapPx(pageGapForBaseMode(manga.baseMode))
            it.setSourceNativeWebtoonCompositingEnabled(
                manga.baseMode == MTitle.base_webtoon ||
                    manga.ntkEpisodePath?.startsWith("/webtoon/") == true
            )
        }
        val adopted = candidateRender.adoptPreparedDrawableBatch(
            images.size,
            completeBitmaps,
            snapshot.tilePages,
            startPage,
            startOffset,
            viewportWidth,
            viewportHeight
        )
        if (!adopted) {
            candidateRender.setWindowListener(null)
            candidateRender.stopRenderingAndClearPages()
            lease.close()
            Log.d(TAG, "reader_prepared_surface_reject path=$ntkPath,reason=surface")
            return false
        }

        renderView = candidateRender
        status = ReaderStatusOverlayView(this).apply {
            text = displayEpisodeTitle(manga, title)
            setTextColor(0xffcccccc.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }
        readerRoot = candidateRoot
        candidateRoot.addView(
            candidateRender,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        candidateRoot.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        manga.setImgs(ArrayList(images))
        manga.ntkImageCount = images.size
        preparedLaunchLease = lease
        preparedSurfaceBitmaps = completeBitmaps
        preparedSurfacePageCount = images.size
        preparedSurfaceStartPage = startPage
        preparedSurfaceStartOffset = startOffset
        preparedSurfaceViewportWidth = viewportWidth
        preparedSurfaceViewportHeight = viewportHeight
        preparedSurfaceKey = preparedKey
        preparedSurfaceStartAtFirstPage = startAtFirstPage
        preparedSurfaceAdoptionActive = true
        preparedSessionPipelineStarted = false
        pageCount = images.size
        pagesReady = true
        currentPage = startPage
        initialStartAtFirstPage = startAtFirstPage
        initialStatusPending = false
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        lastDisplayedPageText = ""
        ntkInitialDiscoveryPath = ntkPath
        if (ntkLaunchPreflightStarted) ntkLaunchPreflightPath = ntkPath
        deferredNtkAckPreflightManga = null

        ReaderChromeStyler.applyReaderWindow(this)
        setContentView(candidateRoot)
        requestInitialSoftwareRunwayPrepare("prepared_surface")
        updatePageLabel()
        updateResultEpisode(manga)
        if (viewerLaunchStartedAtMs > 0L) {
            Log.d(
                "ViewerPerf",
                "reader_activity_create_from_launch source=$viewerLaunchSourceSite " +
                    "ms=${SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs}"
            )
        }
        Log.d(
            "ViewerPerf",
            "reader_prepared_surface_fast_path path=$ntkPath,pages=${images.size}," +
                "start=$startPage:$startOffset,width=$viewportWidth,entryWidth=${lease.entryRequestedWidth}"
        )
        return true
    }

    private fun preparedNtkImagesMatchExactEpisode(
        manga: Manga,
        ntkPath: String,
        images: List<String>
    ): Boolean {
        if (images.isEmpty()) return false
        val normalizedPath = ntkPath.trim().trim('/').lowercase(Locale.ROOT)
        if (normalizedPath.isEmpty()) return false
        val pathParts = normalizedPath.split('/').filter { it.isNotEmpty() }
        if (pathParts.size < 3) return false
        val imageWorkId = manga.ntkImageWorkId?.trim()?.lowercase(Locale.ROOT).orEmpty()
            .ifBlank { pathParts[1] }
        val imageEpisodeId = manga.ntkImageEpisodeId?.trim()?.lowercase(Locale.ROOT).orEmpty()
            .ifBlank { pathParts[2] }
        fun structurallyMatches(image: String): Boolean {
            val normalized = image
                .trim()
                .replace("\\/", "/")
                .replace("\\u002f", "/")
                .lowercase(Locale.ROOT)
            if (normalized.isEmpty() ||
                normalized.contains("/api/m/i?") ||
                isNtkUploadDescriptorUrl(normalized)
            ) {
                return false
            }
            if (normalized.contains("/$normalizedPath/")) return true
            if (imageWorkId.isEmpty() || imageEpisodeId.isEmpty()) return false
            return normalized.contains("/episodes/$imageWorkId/$imageEpisodeId/") ||
                normalized.contains("/${pathParts[0]}/$imageWorkId/$imageEpisodeId/")
        }
        if (images.all(::structurallyMatches)) return true

        if (!ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(ntkPath, images.size, 0L)) {
            return false
        }
        val authoritative = ReaderImageCache.earlyNtkImageUrls(ntkPath, 0L)
        if (authoritative.size < images.size) return false
        return images.indices.all { index ->
            authoritative[index].trim().equals(images[index].trim(), ignoreCase = true)
        }
    }

    private fun startPreparedSessionAfterPhysicalDraw(proof: ReaderSurfaceView.CompletedDrawProof) {
        if (!preparedSurfaceAdoptionActive || preparedSessionPipelineStarted) return
        if ((!proof.hardwareAccelerated && !proof.surfaceQueueSubmissionObserved &&
                !proof.surfaceControlLatchObserved) ||
            proof.coverage.drawableItems <= 0
        ) return
        if (preparedSessionFirstDrawUptimeMs != 0L) return
        preparedSessionFirstDrawUptimeMs = SystemClock.uptimeMillis()
        criticalUiHandler.postDelayed(
            preparedSessionQuietRunnable,
            NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INPUT_QUIET_MS
        )
    }

    private fun startPreparedSessionWhenInputQuiet() {
        if (!preparedSurfaceAdoptionActive || preparedSessionPipelineStarted || destroyed) return
        val now = SystemClock.uptimeMillis()
        val lastActive = maxOf(
            preparedSessionFirstDrawUptimeMs,
            lastReaderInteractionMs,
            lastReaderBusyMs
        )
        val remaining = (lastActive + NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INPUT_QUIET_MS - now)
            .coerceAtLeast(0L)
        if (readerWindowBusy || remaining > 0L) {
            criticalUiHandler.postDelayed(
                preparedSessionQuietRunnable,
                maxOf(remaining, NTK_POST_FIRST_DRAWABLE_FOLLOWUP_RECHECK_MS)
            )
            return
        }
        val manga = currentManga ?: return
        val key = preparedSurfaceKey ?: return
        val title = currentTitle ?: manga.title
        val generation = activeReaderSessionGeneration.incrementAndGet()
        val appContext = applicationContext
        val viewerWidth = preparedSurfaceViewportWidth.coerceAtLeast(1)
        val viewerHeight = preparedSurfaceViewportHeight.coerceAtLeast(1)
        val sessionAutoCut = autoCut
        val sessionReverse = p?.getReverse() == true
        val startAtFirstPage = preparedSurfaceStartAtFirstPage
        preparedSessionPipelineStarted = true
        Log.d(
            "ViewerPerf",
            "reader_prepared_session_background_create_start path=${manga.ntkEpisodePath},generation=$generation"
        )
        preparedSessionBuildTask = AppDispatchers.submitNtkViewerCritical {
            val createStartedAt = SystemClock.elapsedRealtime()
            val built = try {
                ReaderSession(
                    appContext,
                    manga,
                    title,
                    viewerWidth,
                    viewerHeight,
                    sessionAutoCut,
                    sessionReverse,
                    key,
                    startAtFirstPage,
                    activeReaderSessionListener(generation)
                )
            } catch (error: Throwable) {
                Log.e(TAG, "reader_prepared_session_background_create_error path=${manga.ntkEpisodePath}", error)
                null
            }
            if (built == null) return@submitNtkViewerCritical
            criticalUiHandler.post {
                if (!isReaderSessionGenerationActive(generation) || !preparedSurfaceAdoptionActive) {
                    return@post
                }
                preparedSessionBuildTask = null
                preparedBuiltSession = built
                preparedSessionStartBegan = false
                session = built
                Log.d(
                    "ViewerPerf",
                    "reader_prepared_session_background_create_done path=${manga.ntkEpisodePath}," +
                        "ms=${SystemClock.elapsedRealtime() - createStartedAt},generation=$generation"
                )
                preparedSessionStartTask = AppDispatchers.submitNtkViewerCritical {
                    if (generation != activeReaderSessionGeneration.get()) {
                        return@submitNtkViewerCritical
                    }
                    preparedSessionStartBegan = true
                    val startStartedAt = SystemClock.elapsedRealtime()
                    built.start()
                    criticalUiHandler.post {
                        if (!isReaderSessionGenerationActive(generation) || session !== built) {
                            if (preparedSessionStartBegan) built.cancel()
                            return@post
                        }
                        preparedSessionStartTask = null
                        Log.d(
                            "ViewerPerf",
                            "reader_prepared_session_background_start_done path=${manga.ntkEpisodePath}," +
                                "ms=${SystemClock.elapsedRealtime() - startStartedAt},generation=$generation"
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
        saveCurrentReadingProgress()
        if (::renderView.isInitialized) renderView.interruptPhysicalScrollForLifecycle()
        resetStrictPhysicalPresentationCadence()
        strictTelemetryForegroundCommitArmed = false
        if (strictTelemetryOwned) {
            strictTelemetryLifecycleEpoch++
            strictTelemetryActualInLifecycle = false
            if (::renderView.isInitialized) {
                renderView.invalidateCommittedPresentationProof()
                if (renderView.contentDescription?.toString()?.startsWith("actual:") == true) {
                    renderView.contentDescription = null
                }
                if (readerRoot?.contentDescription?.toString()?.startsWith("actual:") == true) {
                    readerRoot?.contentDescription = null
                }
            }
        }
        PerformanceMonitor.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        PerformanceMonitor.resume()
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            readerRoot?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (firstResumeArmedUptimeNanos == 0L) {
            firstResumeArmedUptimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        renderView.requestRender()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_CAPTCHA) return
        ntkAckCaptchaRequested = false
        if (MissingEpisodeNavigator.retryPendingAfterCaptcha(this, missingEpisodePromptState, missingEpisodeHost())) return
        AppDispatchers.runUserAction {
            val pref = p
            if (pref != null) {
                getHttpClient().syncCookiesFromWebView(pref.webtoonUrl, true)
                getHttpClient().syncCookiesFromWebView(pref.url, true)
            }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                val retryManga = pendingCaptchaRetryManga
                val retryTitle = pendingCaptchaRetryTitle
                val retryStartAtFirstPage = pendingCaptchaRetryStartAtFirstPage
                val retryAction = pendingCaptchaRetryAction
                val retryNext = pendingCaptchaRetryNext
                val retryDirection = pendingCaptchaRetryDirection
                val retryAnchor = pendingCaptchaRetryAnchor
                pendingCaptchaRetryManga = null
                pendingCaptchaRetryTitle = null
                pendingCaptchaRetryStartAtFirstPage = false
                pendingCaptchaRetryAction = CAPTCHA_RETRY_READER
                pendingCaptchaRetryDirection = 0
                pendingCaptchaRetryAnchor = -1
                pendingBoundaryCaptchaRetry = false
                if (retryAction == CAPTCHA_RETRY_TOOLBAR_ADJACENT) {
                    adjacentNavigationInFlight = false
                    openAdjacent(retryNext)
                    return@runOnUiThread
                }
                if (retryAction == CAPTCHA_RETRY_BOUNDARY && retryAnchor >= 0 && retryDirection != 0) {
                    pendingBoundaryCaptchaRetry = true
                    pendingCaptchaRetryDirection = retryDirection
                    pendingCaptchaRetryAnchor = retryAnchor
                    val retryStart = session?.appendAdjacentEpisode(retryAnchor, retryDirection, skipStartDelay = true)
                    markPrependRevealRequest(retryDirection, retryStart)
                    if (retryStart != ReaderSession.AppendStartResult.STARTED && retryStart != ReaderSession.AppendStartResult.BUSY) {
                        clearPendingBoundaryCaptchaRetry()
                    }
                    return@runOnUiThread
                }
                val manga = retryManga ?: currentManga ?: return@runOnUiThread
                if (retryManga != null && retryManga !== currentManga) {
                    currentManga = retryManga
                    currentTitle = retryTitle ?: retryManga.title ?: currentTitle
                    updateResultEpisode(retryManga)
                }
                startReaderSession(
                    manga,
                    retryTitle ?: currentTitle ?: manga.title,
                    null,
                    startAtFirstPage = retryStartAtFirstPage,
                    clearViewImmediately = false
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::renderView.isInitialized) {
            if (firstResumeArmedUptimeNanos > 0L && firstFocusArmedUptimeNanos == 0L) {
                firstFocusArmedUptimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            if (!strictTelemetryForegroundCommitArmed) {
                strictTelemetryForegroundCommitArmed = true
                if (strictTelemetryOwned) {
                    // onPause can be followed by an off-screen HWUI commit before the task is
                    // fully hidden. Retire that commit as well and require a focus-owned draw.
                    renderView.invalidateCommittedPresentationProof()
                    if (renderView.contentDescription?.toString()?.startsWith("actual:") == true) {
                        renderView.contentDescription = null
                    }
                    if (readerRoot?.contentDescription?.toString()?.startsWith("actual:") == true) {
                        readerRoot?.contentDescription = null
                    }
                }
            }
            renderView.requestRender()
        } else if (!hasFocus) {
            strictTelemetryForegroundCommitArmed = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (strictTelemetryOwned && ::renderView.isInitialized) {
            strictTelemetryLifecycleEpoch++
            strictTelemetryActualInLifecycle = false
            renderView.invalidateCommittedPresentationProof()
            renderView.contentDescription = null
            readerRoot?.contentDescription = null
            renderView.requestLayout()
            renderView.requestRender()
        }
    }

    override fun onDestroy() {
        val retiringPath = strictTelemetryEpisodePath.ifBlank {
            currentManga?.ntkEpisodePath.orEmpty()
        }
        val retiringSeal = strictExactLaunchSeal?.takeIf {
            it.matchesEpisodePath(retiringPath)
        }
        NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(
            retiringPath,
            strictTelemetryGeneration,
            "reader_destroyed"
        )
        // A completed exact source outlives its worker. The launch seal is the independent,
        // generation-qualified fallback if the coordinator handle was already unavailable.
        retiringSeal?.let {
            NtkSourceSpoolRegistry.retireDiscoveryGenerationForReplacement(
                retiringPath,
                it.discoveryGeneration,
                "reader_destroyed"
            )
        }
        publishStrictTelemetryBeforeClose()
        destroyed = true
        strictNtkPendingSessionPath = ""
        strictNtkManifestSubscription?.close()
        strictNtkManifestSubscription = null
        currentManga?.ntkEpisodePath?.let { path ->
            MainApplication.clearNtkForegroundViewerPath(path)
        }
        activeReaderSessionGeneration.incrementAndGet()
        clearStrictAuthoritativeInstallQueue()
        synchronized(strictRenderReadyLock) {
            strictRenderReadyPages.clear()
            strictRenderReadyGeneration = -1
            strictAllImagesReadyPublished = false
            strictRollingHistoricalScene = false
        }
        preparedSessionBuildTask?.cancel()
        preparedSessionBuildTask = null
        preparedSessionStartTask?.cancel()
        preparedSessionStartTask = null
        ntkAckPreflightGeneration.incrementAndGet()
        currentManga?.ntkEpisodePath?.let { path ->
        }
        if (isCurrentNtkReader()) {
            getHttpClient().cancelNtkWebViewFallbacks()
        }
        progressHandler.removeCallbacks(saveProgressRunnable)
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        statusHandler.removeCallbacks(hybridNtkEarlyUrlPollRunnable)
        clearDeferredAppendPublishCallbacks()
        hybridNtkEarlyUrlListenerDisposer?.invoke()
        hybridNtkEarlyUrlListenerDisposer = null
        missingEpisodePromptState.dismiss()
        detachToolbarWindows()
        removeInitialDrawGateListener()
        saveCurrentReadingProgress()
        pendingProgressInfo = null
        pendingBoundaryStatus = false
        pendingBoundaryCaptchaRetry = false
        pendingPrependRevealRequests = 0
        deferredBoundaryDirection = 0
        deferredBoundaryAnchor = -1
        criticalUiHandler.removeCallbacksAndMessages(null)
        if (::renderView.isInitialized) {
            renderView.setWindowListener(null)
            renderView.stopRenderingAndClearPages()
        }
        clearPendingPageCallbacks()
        val activeSession = session
        if (activeSession != null &&
            (activeSession !== preparedBuiltSession || preparedSessionStartBegan)
        ) {
            activeSession.cancel()
        }
        synchronized(strictEarlySessionLock) {
            strictEarlySession.also { early ->
                if (early != null && early.session !== activeSession) early.session.cancel()
            }
            strictEarlySession = null
        }
        session = null
        preparedBuiltSession = null
        preparedSessionStartBegan = false
        preparedLaunchLease?.close()
        preparedLaunchLease = null
        preparedSurfaceBitmaps = emptyMap()
        preparedSurfaceAdoptionActive = false
        preparedSessionFirstDrawUptimeMs = 0L
        clearInitialSoftwareRunwayPrepareCallback()
        if (strictTelemetryOwned &&
            ViewerTelemetry.activeGeneration() == strictTelemetryGeneration
        ) {
            ViewerTelemetry.viewerClosedAfterDrain(
                "reader_destroyed",
                strictTelemetryGeneration
            )
        }
        PerformanceMonitor.detach()
        super.onDestroy()
    }

    private fun publishStrictTelemetryBeforeClose() {
        if (!strictTelemetryOwned || strictTelemetryClosed ||
            ViewerTelemetry.activeGeneration() != strictTelemetryGeneration
        ) return
        strictTelemetryClosed = true
        ViewerTelemetry.coverageSummary(
            strictTelemetryViewportDefectFrames,
            strictTelemetryRunwayDefectFrames,
            0L,
            strictTelemetryIdentityInvalidFrames,
            strictTelemetryInitialBlankFrames
        )
        val launchSeal = strictExactLaunchSeal
        if (launchSeal != null) {
            val committed = strictTelemetryObservedSources.withIndex()
                .filter { it.value }
                .map { it.index }
            val missing = strictTelemetryObservedSources.withIndex()
                .filterNot { it.value }
                .map { it.index }
            val structureEpoch = if (::renderView.isInitialized) {
                renderView.traversalSnapshot().structureEpoch
            } else {
                0L
            }
            ViewerTelemetry.traversalSummary(
                launchSeal.normalizedEpisodePath,
                launchSeal.manifestDigest,
                launchSeal.pageCount,
                committed.size,
                committed.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "none",
                missing.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "none",
                structureEpoch,
                strictTelemetryValidCommittedFrames,
                strictTelemetryInvalidCommittedFrames,
                strictTelemetryInitialBlankFrames
            )
        }
        PerformanceMonitor.reportNow("reader_destroy")
    }

    override fun onPagesReady(count: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        pagesReady = true
        initialStatusPending = false
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        hideBoundaryStatus()
        val effectiveCount = effectiveNtkPagesReadyCount(count)
        val oldCount = pageCount
        var appliedCount = effectiveCount
        val preservePreRenderedInitial = shouldPreservePreRenderedInitialDrawable(effectiveCount)
        val preservePreparedSurfaceBatch = preparedSurfaceAdoptionActive &&
            effectiveCount == preparedSurfacePageCount &&
            oldCount == preparedSurfacePageCount
        val splitNtkAppendReadyFollowups = isCurrentNtkReader() && oldCount > 0 && effectiveCount > oldCount
        when {
            preservePreparedSurfaceBatch -> {
                pageCount = effectiveCount
                Log.d(
                    TAG,
                    "reader_pages_ready_adopt_prepared_surface count=$effectiveCount,reported=$count"
                )
            }
            oldCount > 0 && effectiveCount > oldCount -> {
                pageCount = effectiveCount
                renderView.appendPageCount(effectiveCount, revealAppendedBoundary = false)
                Log.d(
                    TAG,
                    "reader_pages_ready_append_preserve old=$oldCount new=$effectiveCount,reported=$count"
                )
            }
            oldCount > 0 && effectiveCount == oldCount -> {
                pageCount = effectiveCount
                renderView.setPageCount(effectiveCount, deferInitialEmptyDraw = false)
                if (preservePreRenderedInitial) {
                    Log.d(
                        TAG,
                        "reader_pages_ready_preserve_initial_prerender count=$effectiveCount," +
                            "reported=$count,mode=same"
                    )
                }
            }
            oldCount > 0 && isCurrentNtkReader() && effectiveCount < oldCount -> {
                appliedCount = oldCount
                pageCount = oldCount
                renderView.setPageCount(oldCount, deferInitialEmptyDraw = false)
                Log.d(
                    TAG,
                    "reader_pages_ready_ignore_ntk_shrink old=$oldCount reported=$effectiveCount,raw=$count"
                )
            }
            else -> {
                pageCount = effectiveCount
                renderView.setPageCount(effectiveCount, deferInitialEmptyDraw = false)
                if (preservePreRenderedInitial) {
                    Log.d(
                        TAG,
                        "reader_pages_ready_preserve_initial_prerender count=$effectiveCount," +
                            "reported=$count,mode=set"
                    )
                }
            }
        }
        val runFollowups = {
            if (!preservePreparedSurfaceBatch) {
                clampNtkPartialTailCurrentPageForContinuousWindow(appliedCount, "pages_ready")
                if (
                    isCurrentNtkReader() &&
                    currentPage > 0 &&
                    appliedCount > currentPage &&
                    renderView.hasPageDrawable(currentPage)
                ) {
                    renderView.scrollToPage(currentPage, 0)
                    Log.d(TAG, "reader_pages_ready_anchor_scroll page=$currentPage,count=$appliedCount")
                }
                requestInitialVisibleWindow(appliedCount)
            }
            updateCurrentEpisode(currentPage)
            flushPendingPageCallbacks()
            requestInitialSoftwareRunwayPrepare("pages_ready")
            clearPreRenderedInitialAnchorDrawable()
        }
        if (splitNtkAppendReadyFollowups) {
            val followupCount = appliedCount
            statusHandler.post {
                if (!pagesReady || pageCount < followupCount) return@post
                val before = SystemClock.elapsedRealtime()
                runFollowups()
                val after = SystemClock.elapsedRealtime() - before
                if (after > 16L) {
                    Log.d(TAG, "reader_pages_ready_split_followups_ms count=$followupCount,ms=$after")
                }
            }
        } else {
            runFollowups()
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed > 32L) {
            Log.d(
                TAG,
                "reader_on_pages_ready_ms count=$appliedCount,reported=$count,ms=$elapsed," +
                    "pendingBitmaps=${pendingPageBitmaps.size},pendingTiles=${pendingPageTiles.size}"
            )
        }
    }

    private fun effectiveNtkPagesReadyCount(reportedCount: Int): Int {
        val path = currentManga?.ntkEpisodePath
        if (path.isNullOrBlank() ||
            (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))
        ) {
            return reportedCount
        }
        val known = currentManga?.ntkImageCount ?: 0
        if (known <= reportedCount) return reportedCount
        Log.d(
            TAG,
            "reader_pages_ready_known_count_promoted reported=$reportedCount,known=$known,path=$path"
        )
        return known
    }

    private fun clampNtkPartialTailCurrentPageForContinuousWindow(count: Int, reason: String) {
        if (!shouldClampNtkPartialTailProgress(count)) return
        val maxContinuousAnchor = maxOf(0, count - NTK_ACK_INITIAL_CONTINUOUS_PAGES)
        if (currentPage <= maxContinuousAnchor) return
        val old = currentPage
        currentPage = maxContinuousAnchor
        if (::renderView.isInitialized && count > currentPage) {
            renderView.scrollToPage(currentPage, 0)
        }
        Log.d(
            TAG,
            "reader_ntk_partial_tail_current_clamp reason=$reason,old=$old,new=$currentPage,count=$count"
        )
    }

    private fun adjustedNtkPartialTailProgressPage(progressPage: Int, count: Int): Int {
        if (!shouldClampNtkPartialTailProgress(count)) return progressPage
        return progressPage.coerceAtMost(maxOf(0, count - NTK_ACK_INITIAL_CONTINUOUS_PAGES))
    }

    private fun shouldClampNtkPartialTailProgress(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= NTK_ACK_INITIAL_CONTINUOUS_PAGES) return false
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        val trustedCount = ReaderImageCache.trustedNtkImageApiCount(path, minCreatedAt)
        if (trustedCount >= count) return false
        val known = currentManga?.ntkImageCount ?: 0
        if (known > 0 && known == count) return false
        if (
            known <= 0 &&
            ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(path, count, minCreatedAt)
        ) {
            return false
        }
        if (known > count && trustedCount > 0) return false
        // A complete single-episode manifest is the normal scroll path.  Check its O(1)
        // authority signals before walking every session page (which allocates PageInfo objects).
        if (hasMultipleNtkEpisodesInWindow(count)) return false
        return true
    }

    private fun hasMultipleNtkEpisodesInWindow(count: Int): Boolean {
        val readerSession = session ?: return false
        val limit = count.coerceAtLeast(0)
        var firstManga: Manga? = null
        var firstPath = ""
        for (index in 0 until limit) {
            val info = readerSession.pageInfo(index) ?: continue
            if (info.transitionCard) continue
            val path = info.manga.ntkEpisodePath?.trim().orEmpty()
            if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) continue
            val seed = firstManga
            if (seed == null) {
                firstManga = info.manga
                firstPath = path
                continue
            }
            if (!Manga.sameEpisodeIdentity(seed, info.manga) && firstPath != path) {
                return true
            }
        }
        return false
    }

    override fun onPagesAppended(count: Int) {
        MainThreadStallMonitor.trace("reader_on_pages_appended") {
            if (pagesReady) {
                if (isCurrentNtkReader() && count < pageCount) {
                    Log.d(
                        TAG,
                        "pages_appended_ignore_stale_shrink total=$count current=$pageCount currentPage=$currentPage"
                    )
                    return@trace
                }
                if (isCurrentNtkReader() && count == pageCount) {
                    Log.d(
                        TAG,
                        "pages_appended_ignore_same_count total=$count currentPage=$currentPage"
                    )
                    return@trace
                }
                if (isCurrentNtkManhwaOrWebtoonPath() && count > pageCount) {
                    val oldCount = pageCount
                    pageCount = count
                    renderView.appendPageCount(count, revealAppendedBoundary = false)
                    flushPendingPageCallbacks()
                    updateCurrentEpisode(currentPage)
                    Log.d(
                        TAG,
                        "pages_appended_current_episode_full_surface from=$oldCount total=$count currentPage=$currentPage"
                    )
                    return@trace
                }
                val activeAppendDelayMs = ntkActiveAppendPublishQuietRemainingMs()
                if (shouldDeferSmallTailAppendUntilFullyReady(count, activeAppendDelayMs)) {
                    val retryMs = appendUntilReadyRetryDelayMs(count)
                    scheduleDeferredAppendPages(count, retryMs)
                    if (shouldLogPagesAppendedHotPath()) {
                        Log.d(
                            TAG,
                            "pages_appended_defer_small_tail_until_ready total=$count current=$pageCount " +
                                "ready=${contiguousPendingCurrentEpisodePageCount(count)} retryMs=$retryMs"
                        )
                    }
                    return@trace
                }
                val publishCount = activeNtkAppendChunkCount(count, activeAppendDelayMs)
                if (publishCount > pageCount && publishCount < count) {
                    scheduleDeferredAppendPages(count, NTK_ACTIVE_APPEND_CHUNK_RETRY_MS)
                    if (shouldLogPagesAppendedHotPath()) {
                        Log.d(
                            TAG,
                            "pages_appended_chunk_active_input total=$count immediate=$publishCount " +
                                "currentPage=$currentPage previous=$pageCount"
                        )
                    }
                }
                val currentNtkEpisodeExpansion =
                    shouldApplyCurrentNtkEpisodeExpansionImmediately(publishCount) ||
                        shouldApplyCurrentNtkSessionExpansionImmediately(publishCount)
                val tailAppendExpansion = shouldApplyTailAppendExpansionImmediately(publishCount)
                val cleanNtkAppendExpansion = shouldApplyCleanNtkAppendExpansionImmediately(publishCount)
                val largeNtkAppendExpansion = shouldTreatAsLargeNtkAppendExpansion(publishCount)
                val publishDelayMs = if (currentNtkEpisodeExpansion || tailAppendExpansion || cleanNtkAppendExpansion) {
                    0L
                } else if (largeNtkAppendExpansion) {
                    maxOf(ntkLargeAppendPublishQuietRemainingMs(), activeAppendDelayMs)
                } else {
                    maxOf(ntkAppendPublishQuietRemainingMs(), activeAppendDelayMs)
                }
                if (publishDelayMs > 0L && publishCount > pageCount) {
                    scheduleDeferredAppendPages(count, publishDelayMs)
                    if (shouldLogPagesAppendedHotPath()) {
                        Log.d(
                            TAG,
                            "pages_appended_defer_active_input total=$count immediate=$publishCount currentPage=$currentPage " +
                                "delayMs=$publishDelayMs"
                        )
                    }
                    return@trace
                }
                if (publishCount <= pageCount) {
                    val retryMs = appendUntilReadyRetryDelayMs(count)
                    scheduleDeferredAppendPages(count, retryMs)
                    maybeLogAppendUntilReady(count, retryMs)
                    return@trace
                }
                val logAppendHotPath = shouldLogPagesAppendedHotPath()
                if (tailAppendExpansion && logAppendHotPath) {
                    Log.d(
                        TAG,
                        "pages_appended_tail_immediate total=$publishCount requested=$count previous=$pageCount currentPage=$currentPage"
                    )
                }
                if (cleanNtkAppendExpansion && logAppendHotPath) {
                    Log.d(
                        TAG,
                        "pages_appended_clean_ntk_immediate total=$publishCount requested=$count previous=$pageCount currentPage=$currentPage"
                    )
                }
                val revealAppendedBoundary = if (currentNtkEpisodeExpansion) {
                    pendingAppendRevealRequests = 0
                    false
                } else {
                    consumeAppendedBoundaryReveal()
                }
                hideBoundaryStatus()
                pendingBoundaryStartInteractionMs = 0L
                pageCount = publishCount
                renderView.appendPageCount(publishCount, revealAppendedBoundary)
                if (revealAppendedBoundary) renderView.finishBoundaryDispatch()
                flushPendingPageCallbacks()
                if (logAppendHotPath) {
                    Log.d(
                        TAG,
                        "pages_appended total=$publishCount requested=$count currentPage=$currentPage reveal=$revealAppendedBoundary"
                    )
                }
                updateCurrentEpisode(currentPage)
            }
        }
    }

    private fun shouldLogPagesAppendedHotPath(): Boolean {
        if (!isCurrentNtkManhwaOrWebtoonPath()) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastPagesAppendedHotPathLogMs < NTK_APPEND_HOT_PATH_LOG_MS) return false
        lastPagesAppendedHotPathLogMs = now
        return true
    }

    private fun appendUntilReadyRetryDelayMs(count: Int): Long {
        if (!isCurrentNtkReader()) return NTK_ACTIVE_APPEND_CHUNK_RETRY_MS
        val readyCount = contiguousPendingCurrentEpisodePageCount(count)
        val unchanged = count == lastAppendUntilReadyCount &&
            pageCount == lastAppendUntilReadyPageCount &&
            readyCount == lastAppendUntilReadyReadyCount
        lastAppendUntilReadyCount = count
        lastAppendUntilReadyPageCount = pageCount
        lastAppendUntilReadyReadyCount = readyCount
        return if (unchanged) {
            NTK_APPEND_UNTIL_READY_UNCHANGED_RETRY_MS
        } else {
            NTK_ACTIVE_APPEND_CHUNK_RETRY_MS
        }
    }

    private fun maybeLogAppendUntilReady(count: Int, retryMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (retryMs > NTK_ACTIVE_APPEND_CHUNK_RETRY_MS &&
            now - lastAppendUntilReadyLogMs < NTK_APPEND_UNTIL_READY_LOG_MS
        ) {
            return
        }
        lastAppendUntilReadyLogMs = now
        Log.d(
            TAG,
            "pages_appended_defer_until_ready total=$count currentPage=$currentPage " +
                "pageCount=$pageCount ready=$lastAppendUntilReadyReadyCount retryMs=$retryMs"
        )
    }

    private fun activeNtkAppendChunkCount(count: Int, activeAppendDelayMs: Long): Int {
        if (!isCurrentNtkReader()) return count
        if (count <= pageCount) return count
        val largeExpansion = count - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES
        val readyCount = contiguousPendingCurrentEpisodePageCount(count)
        if (readyCount <= pageCount) return pageCount
        val pageStep = if (isCurrentNtkManhwaOrWebtoonPath()) {
            NTK_CURRENT_READY_RUNWAY_ACTIVE_WEBTOON_CHUNK_PAGES
        } else if (largeExpansion && activeAppendDelayMs <= 0L && !readerWindowBusy) {
            NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES
        } else {
            NTK_ACTIVE_APPEND_IMMEDIATE_RUNWAY_PAGES
        }
        return minOf(count, readyCount, pageCount + pageStep)
    }

    private fun isCurrentNtkManhwaOrWebtoonPath(): Boolean {
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/")
    }

    private fun shouldDeferSmallTailAppendUntilFullyReady(count: Int, activeAppendDelayMs: Long): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount || pageCount <= 0) return false
        if (count - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES) return false
        if (activeAppendDelayMs <= 0L && !readerWindowBusy) return false
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        val readyCount = contiguousPendingCurrentEpisodePageCount(count)
        if (readyCount >= count) return false
        if (readyCount > pageCount && shouldApplyTailAppendExpansionImmediately(readyCount)) {
            return false
        }
        return shouldApplyTailAppendExpansionImmediately(count)
    }

    private fun contiguousPendingCurrentEpisodePageCount(limit: Int): Int {
        var readyCount = pageCount
        while (readyCount < limit && (pendingPageBitmaps.containsKey(readyCount) || pendingPageTiles.containsKey(readyCount))) {
            val info = session?.pageInfo(readyCount) ?: break
            if (info.transitionCard) break
            readyCount++
        }
        return readyCount
    }

    private fun isCurrentNtkEpisodePage(info: ReaderSession.PageInfo): Boolean {
        val target = currentManga ?: return false
        if (info.transitionCard) return false
        if (Manga.sameEpisodeIdentity(info.manga, target)) return true
        val currentPath = target.ntkEpisodePath?.trim().orEmpty()
        val pagePath = info.manga.ntkEpisodePath?.trim().orEmpty()
        return currentPath.isNotEmpty() && currentPath == pagePath
    }

    private fun isCurrentNtkEpisodeExpansion(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount) return true
        for (index in pageCount until count) {
            val info = session?.pageInfo(index) ?: return false
            if (!isCurrentNtkEpisodePage(info)) return false
        }
        return true
    }

    private fun shouldApplyCurrentNtkEpisodeExpansionImmediately(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount) return false
        val known = currentManga?.ntkImageCount ?: 0
        if (known <= pageCount) return false
        return count <= known
    }

    private fun shouldApplyCurrentNtkSessionExpansionImmediately(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount || pageCount < 0) return false
        val target = currentManga ?: return false
        val info = session?.pageInfo(pageCount) ?: return false
        if (info.transitionCard) return false
        if (Manga.sameEpisodeIdentity(info.manga, target)) return true
        val currentPath = target.ntkEpisodePath?.trim().orEmpty()
        val nextPath = info.manga.ntkEpisodePath?.trim().orEmpty()
        return currentPath.isNotEmpty() && currentPath == nextPath
    }

    private fun shouldApplyTailAppendExpansionImmediately(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount || pageCount <= 0) return false
        if (count - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES) return false
        if (!::renderView.isInitialized) return false
        val snapshot = renderView.currentScrollPositionSnapshot() ?: return false
        val coverageSnapshot = renderView.visibleCoverageSnapshot() ?: lastNativeVisibleCoverageSnapshot
        val atTailPage =
            currentPage >= pageCount - NTK_APPEND_PUBLISH_TAIL_PAGE_THRESHOLD ||
                snapshot.page >= pageCount - NTK_APPEND_PUBLISH_TAIL_PAGE_THRESHOLD
        if (snapshot.maxScroll <= 0 || snapshot.contentHeight <= 0) return false
        val averagePageHeight = snapshot.contentHeight / pageCount.coerceAtLeast(1)
        val tailPx = (averagePageHeight * NTK_APPEND_PUBLISH_TAIL_PAGE_THRESHOLD)
            .coerceAtLeast(NTK_APPEND_PUBLISH_TAIL_MIN_PX)
        val atTailOffset = snapshot.maxScroll - snapshot.scrollOffset <= tailPx
        val atTail = atTailPage || atTailOffset
        if (!atTail) return false
        val activePublishDelayMs = ntkActiveAppendPublishQuietRemainingMs(coverageSnapshot)
        if (!isCurrentNtkEpisodeExpansion(count) &&
            (readerWindowBusy || activePublishDelayMs > 0L)
        ) {
            if (isNtkTailAppendVisibleCoverageClean(coverageSnapshot)) {
                if (shouldLogNtkTailDecision()) {
                    Log.d(
                        TAG,
                        "pages_appended_tail_immediate_active_clean total=$count current=$pageCount " +
                            "currentPage=$currentPage busy=$readerWindowBusy delayMs=$activePublishDelayMs " +
                            "drawablePx=${coverageSnapshot?.drawablePx ?: -1} viewportPx=${coverageSnapshot?.viewportPx ?: -1}"
                    )
                }
                return true
            }
            if (shouldLogNtkTailDecision()) {
                Log.d(
                    TAG,
                    "pages_appended_tail_defer_active_input total=$count current=$pageCount " +
                        "currentPage=$currentPage busy=$readerWindowBusy delayMs=$activePublishDelayMs " +
                        "missingPx=${coverageSnapshot?.missingPx ?: -1} " +
                        "placeholderPx=${coverageSnapshot?.placeholderPx ?: -1} " +
                        "visibleLoading=${coverageSnapshot?.visibleLoading ?: -1}"
                )
            }
            return false
        }
        return true
    }

    private fun shouldLogNtkTailDecision(): Boolean {
        if (!isCurrentNtkManhwaOrWebtoonPath()) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastNtkTailDecisionLogMs < NTK_APPEND_HOT_PATH_LOG_MS) return false
        lastNtkTailDecisionLogMs = now
        return true
    }

    private fun isNtkTailAppendVisibleCoverageClean(
        snapshot: ReaderSurfaceView.VisibleCoverageSnapshot?
    ): Boolean {
        return snapshot != null &&
            snapshot.pageCount >= pageCount &&
            isNativeCoverageViewportReady(snapshot) &&
            snapshot.visibleErrors == 0
    }

    private fun shouldApplyCleanNtkAppendExpansionImmediately(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount || pageCount <= 0) return false
        if (count - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES) return false
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        if (!::renderView.isInitialized) return false
        val snapshot = renderView.visibleCoverageSnapshot() ?: lastNativeVisibleCoverageSnapshot
        if (ntkActiveAppendPublishQuietRemainingMs(snapshot) > 0L &&
            !shouldApplyTailAppendExpansionImmediately(count)
        ) {
            return false
        }
        return isNativeCoverageViewportReady(snapshot)
    }

    private fun shouldTreatAsLargeNtkAppendExpansion(count: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (count <= pageCount || pageCount <= 0) return false
        return count - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES
    }

    private fun markAppendRevealRequest(direction: Int, startResult: ReaderSession.AppendStartResult?) {
        if (
            direction == ReaderSurfaceView.DIRECTION_NEXT &&
            (startResult == ReaderSession.AppendStartResult.STARTED ||
                startResult == ReaderSession.AppendStartResult.BUSY)
        ) {
            pendingAppendRevealRequests = 1
        }
    }

    private fun consumeAppendedBoundaryReveal(): Boolean {
        val reveal = pendingAppendRevealRequests > 0 ||
            (pendingBoundaryStatus && pendingCaptchaRetryDirection == ReaderSurfaceView.DIRECTION_NEXT)
        pendingAppendRevealRequests = 0
        return reveal
    }

    override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) {
        preparedSurfaceAdoptionActive = false
        if (pagesReady) {
            val revealPrependedBoundary = consumePrependedBoundaryReveal(insertedCount)
            pendingBoundaryStartInteractionMs = 0L
            hideBoundaryStatus()
            pageCount = count
            currentPage += insertedCount
            if (pendingInitialRestorePage >= 0) pendingInitialRestorePage += insertedCount
            renderView.prependPageCount(count, insertedCount, revealPrependedBoundary, holdUntilReadyCount)
            if (revealPrependedBoundary) {
                currentPage = (insertedCount - 1).coerceIn(0, count - 1)
            }
            Log.d(
                TAG,
                "pages_prepended total=$count inserted=$insertedCount reveal=$revealPrependedBoundary " +
                    "holdUntilReady=$holdUntilReadyCount deferredReveal=false currentPage=$currentPage"
            )
            updateCurrentEpisode(currentPage)
        }
    }

    override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) {
        preparedSurfaceAdoptionActive = false
        if (pagesReady) {
            val previousCount = pageCount
            if (
                removedCount <= 0 ||
                startIndex < 0 ||
                startIndex >= previousCount ||
                totalCount > previousCount
            ) {
                Log.d(
                    TAG,
                    "pages_removed_ignore_stale start=$startIndex removed=$removedCount " +
                        "total=$totalCount previous=$previousCount currentPage=$currentPage"
                )
                return
            }
            val effectiveRemoved = minOf(removedCount, previousCount - startIndex)
            val effectiveTotal = minOf(totalCount, previousCount - effectiveRemoved)
            hideBoundaryStatus()
            pageCount = effectiveTotal
            currentPage = when {
                currentPage >= startIndex + effectiveRemoved -> currentPage - effectiveRemoved
                currentPage >= startIndex -> startIndex.coerceAtMost((effectiveTotal - 1).coerceAtLeast(0))
                else -> currentPage
            }
            if (pendingInitialRestorePage >= startIndex + effectiveRemoved) {
                pendingInitialRestorePage -= effectiveRemoved
            } else if (pendingInitialRestorePage >= startIndex) {
                pendingInitialRestorePage = -1
                pendingInitialRestoreOffset = 0
            }
            renderView.removePageRange(startIndex, effectiveRemoved, immediate = true)
            Log.d(
                TAG,
                "pages_removed start=$startIndex removed=$effectiveRemoved total=$effectiveTotal " +
                    "rawRemoved=$removedCount rawTotal=$totalCount previous=$previousCount currentPage=$currentPage"
            )
            updateCurrentEpisode(currentPage.coerceAtMost((effectiveTotal - 1).coerceAtLeast(0)))
            if (effectiveTotal > 0 && currentPage >= effectiveTotal - 1 && pendingPrependRevealRequests <= 0) {
                startBoundaryAppend(ReaderSurfaceView.DIRECTION_NEXT, currentPage)
            }
        }
    }

    override fun onInitialPage(index: Int) {
        if (pagesReady) {
            currentPage = index
            val initialManga = restoreBookmarkManga(session?.pageInfo(index)?.manga ?: currentManga)
            val offset = p?.getViewerBookmarkOffset(initialManga) ?: 0
            if (needsInitialRestorePosition(index, offset)) {
                pendingInitialRestorePage = index
                pendingInitialRestoreOffset = offset
                renderView.holdInitialRestoreRender(index)
                renderView.lockRestoredPageOffset(index, offset)
                updateCurrentEpisode(index, offset, saveProgress = false)
                applyPendingInitialRestoreIfReady()
            } else {
                pendingInitialRestorePage = -1
                pendingInitialRestoreOffset = 0
                updateCurrentEpisode(index, 0, saveProgress = false)
            }
        }
    }

    private fun needsInitialRestorePosition(index: Int, offset: Int): Boolean {
        return index > 0 || offset > 0
    }

    private fun restoreBookmarkManga(manga: Manga?): Manga? {
        val title = currentTitle ?: manga?.title ?: return manga
        return manga?.also {
            it.title = title
            it.titleId = title.id
            title.eps?.let { episodes -> it.setEps(episodes) }
        }
    }

    override fun onPageLoading(index: Int) {
        if (pagesReady) renderView.setPageLoading(index)
    }

    override fun onPageBoundsReady(index: Int, width: Int, height: Int) {
        if (pagesReady) {
            renderView.setPageBounds(index, width, height)
            if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
        }
    }

    override fun onPageReady(index: Int, bitmap: Bitmap) {
        MainThreadStallMonitor.trace("reader_on_page_ready") {
            if (isPreRenderedInitialBitmap(index, bitmap) && renderView.hasPageDrawable(index)) {
                Log.d(TAG, "page_ready_deferred_skip_prerender index=$index")
                return@trace
            }
            if (isPreRenderedInitialContinuousBitmap(index, bitmap) && renderView.hasPageDrawable(index)) {
                Log.d(TAG, "page_ready_deferred_skip_initial_continuous_prerender index=$index")
                return@trace
            }
            if (pagesReady && index < pageCount) {
                applyPageBitmap(index, bitmap)
            } else {
                rememberPendingPageBitmap(index, bitmap)
                publishContiguousPendingCurrentEpisodePages("bitmap")
            }
        }
    }

    override fun onPageProofReady(index: Int, bitmap: Bitmap) {
        MainThreadStallMonitor.trace("reader_on_page_proof_ready") {
            if ((isPreRenderedInitialBitmap(index, bitmap) ||
                isPreRenderedInitialContinuousBitmap(index, bitmap)) &&
                renderView.hasPageDrawable(index)
            ) {
                return@trace
            }
            if (pagesReady && index < pageCount) {
                if (isStrictContinuousAppendedPage(index)) {
                    // An appended episode has no launch-seal authoritative replacement. Keeping
                    // the learned placeholder height here would stretch its real bitmap for the
                    // entire active fling (most visibly for a short final separator image).
                    renderView.setPageBitmap(index, bitmap, forceImmediateGeometry = true)
                    logLaunchDrawableMetric(index, "continuous-append-bitmap")
                } else {
                    renderView.setPageProofBitmap(index, bitmap)
                    logLaunchDrawableMetric(index, "proof-bitmap")
                }
            } else {
                rememberPendingPageBitmap(index, bitmap)
                publishContiguousPendingCurrentEpisodePages("proof_bitmap")
            }
        }
    }

    override fun onPageProofTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        MainThreadStallMonitor.trace("reader_on_page_proof_tiles_ready") {
            if ((isPreRenderedInitialTiles(index, tiles) ||
                isPreRenderedInitialContinuousTiles(index, tiles)) &&
                renderView.hasPageDrawable(index)
            ) {
                return@trace
            }
            if (pagesReady && index < pageCount) {
                if (isStrictContinuousAppendedPage(index)) {
                    // See the bitmap path above: no later launch-seal write will repair geometry
                    // for a continuously appended episode, so install its encoded aspect now.
                    renderView.setPageTiles(
                        index,
                        pageWidth,
                        pageHeight,
                        tiles,
                        forceImmediateGeometry = true
                    )
                    logLaunchDrawableMetric(index, "continuous-append-tiles")
                } else {
                    renderView.setPageProofTiles(index, pageWidth, pageHeight, tiles)
                    logLaunchDrawableMetric(index, "proof-tiles")
                }
            } else {
                rememberPendingPageTiles(index, pageWidth, pageHeight, tiles)
                publishContiguousPendingCurrentEpisodePages("proof_tiles")
            }
        }
    }

    private fun isStrictContinuousAppendedPage(index: Int): Boolean {
        val launchSeal = strictExactLaunchSeal ?: return false
        val identity = session?.pageIdentity(index) ?: return false
        return identity.normalizedEpisodePath.isNotBlank() &&
            identity.normalizedEpisodePath != launchSeal.normalizedEpisodePath
    }

    override fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        MainThreadStallMonitor.trace("reader_on_page_tiles_ready") {
            if (isPreRenderedInitialTiles(index, tiles) && renderView.hasPageDrawable(index)) {
                Log.d(TAG, "page_tiles_deferred_skip_prerender index=$index")
                return@trace
            }
            if (isPreRenderedInitialContinuousTiles(index, tiles) && renderView.hasPageDrawable(index)) {
                Log.d(TAG, "page_tiles_deferred_skip_initial_continuous_prerender index=$index")
                return@trace
            }
            if (pagesReady && index < pageCount) {
                applyPageTiles(index, pageWidth, pageHeight, tiles)
            } else {
                rememberPendingPageTiles(index, pageWidth, pageHeight, tiles)
                publishContiguousPendingCurrentEpisodePages("tiles")
            }
        }
    }

    override fun onPageDecodedRenderReady(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        val generation = activeReaderSessionGeneration.get()
        val seal = strictExactLaunchSeal ?: return false
        val valid = strictReaderSessionGeneration == generation &&
            index in 0 until seal.pageCount &&
            AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles) != null
        if (!valid) return false
        // Prepared launch pages can already be physically installed before this decode observer
        // runs. Count only an exact Surface identity match; a merely decoded/recyclable result is
        // intentionally not readiness.
        if (renderView.hasAuthoritativeOriginalTiles(index, pageWidth, pageHeight, tiles)) {
            markStrictInstalledPageReady(generation, seal, index)
        }
        return true
    }

    override fun isStrictAuthoritativeWorkerHandoffActive(): Boolean {
        val generation = activeReaderSessionGeneration.get()
        return strictWorkerHandoffGeneration == generation &&
            strictReaderSessionGeneration == generation &&
            strictExactLaunchSeal != null
    }

    override fun onPageAuthoritativeTilesReady(
        index: Int,
        sourceIndex: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        proof: ReaderPreparedStore.PreparedOriginalProof
    ): Boolean {
        val generation = activeReaderSessionGeneration.get()
        val seal = strictExactLaunchSeal
        if (seal == null) {
            Log.e(TAG, "authoritative_tiles_reject page=$index reason=no_seal generation=$generation")
            return false
        }
        val generationMatches = strictReaderSessionGeneration == generation
        val displayInRange = index in 0 until seal.pageCount
        val sourceInRange = sourceIndex in 0 until seal.pageCount
        val expectedAsset = ReaderPreparedStore.canonicalOriginalAssetIdentity(
            seal.canonicalAssets.getOrNull(sourceIndex)
        )
        val proofMatches = proof.canonicalAsset == expectedAsset
        val identity = AdoptedDrawableIdentity.fullQualityTiles(
            pageWidth,
            pageHeight,
            tiles
        )
        val fullQuality = identity != null
        if (!generationMatches || !displayInRange || !sourceInRange || !proofMatches || identity == null) {
            Log.e(
                TAG,
                "authoritative_tiles_reject page=$index source=$sourceIndex reason=precondition," +
                    "generationMatches=$generationMatches,displayInRange=$displayInRange," +
                    "sourceInRange=$sourceInRange,proofMatches=$proofMatches,fullQuality=$fullQuality," +
                    "proofSize=${proof.originalWidth}x${proof.originalHeight}," +
                    "pageSize=${pageWidth}x$pageHeight,tiles=${tiles.size}"
            )
            return false
        }

        // Keep the currently visible anchor synchronous so a cold open never waits for a batching
        // window. Every offscreen immutable original transfers into one generation-qualified
        // queue. This turns a 114-page response wave into one Surface lock/layout/frame operation
        // instead of 114 complete layout rebuilds while preserving exact bitmap identity.
        val currentAnchor = renderView.currentProgressPosition()?.page ?: currentPage
        if (index == currentAnchor) {
            val installed = renderView.setPageAuthoritativeOriginalTiles(
                index,
                pageWidth,
                pageHeight,
                tiles,
                proof
            )
            val exact = installed && renderView.hasAuthoritativeOriginalTiles(
                index,
                pageWidth,
                pageHeight,
                tiles
            )
            if (!exact) {
                Log.e(
                    TAG,
                    "authoritative_tiles_reject page=$index source=$sourceIndex " +
                        "reason=synchronous_surface_ack,pagesReady=$pagesReady,pageCount=$pageCount"
                )
                return false
            }
            markStrictInstalledPageReady(generation, seal, index)
            return true
        }

        val accepted = synchronized(strictAuthoritativeInstallLock) {
            val existing = pendingStrictAuthoritativeInstalls[index]
            if (existing != null && !existing.identity.sameAs(identity)) {
                false
            } else {
                pendingStrictAuthoritativeInstalls[index] = PendingStrictTileInstall(
                    generation,
                    index,
                    sourceIndex,
                    pageWidth,
                    pageHeight,
                    tiles,
                    proof,
                    identity
                )
                true
            }
        }
        if (!accepted) {
            Log.e(TAG, "authoritative_tiles_reject page=$index source=$sourceIndex reason=pending_identity_conflict")
            return false
        }
        scheduleStrictAuthoritativeInstallFlush()
        return true
    }

    private fun scheduleStrictAuthoritativeInstallFlush() {
        // Every item in this queue is already a decoded, manifest-qualified immutable original.
        // Waiting for twelve items and then posting a delayed partial drain created a lost-wakeup
        // race: a worker could enqueue while the first drain was scheduled, the drain could take
        // only that first cohort, and no later worker was required to wake the remaining tail.
        // Publish one main-loop drain immediately. The drain atomically takes every item currently
        // available; anything that arrives concurrently observes the cleared flag and posts the
        // next drain. This is a UI-state handoff only and performs no request, decode, or warm-up.
        val shouldPost = synchronized(strictAuthoritativeInstallLock) {
            if (strictAuthoritativeInstallFlushScheduled ||
                pendingStrictAuthoritativeInstalls.isEmpty() || strictExactLaunchSeal == null
            ) {
                false
            } else {
                strictAuthoritativeInstallFlushScheduled = true
                true
            }
        }
        if (shouldPost && !statusHandler.post(strictAuthoritativeInstallFlushRunnable)) {
            synchronized(strictAuthoritativeInstallLock) {
                strictAuthoritativeInstallFlushScheduled = false
            }
        }
    }

    private fun flushStrictAuthoritativeInstallsNow() {
        val generation = activeReaderSessionGeneration.get()
        val seal = strictExactLaunchSeal
        val batch = synchronized(strictAuthoritativeInstallLock) {
            strictAuthoritativeInstallFlushScheduled = false
            if (seal == null || strictReaderSessionGeneration != generation) {
                pendingStrictAuthoritativeInstalls.clear()
                emptyList()
            } else {
                pendingStrictAuthoritativeInstalls.values
                    .filter { it.generation == generation }
                    .sortedBy { it.pageIndex }
                    .also { selected ->
                        selected.forEach { pendingStrictAuthoritativeInstalls.remove(it.pageIndex) }
                    }
            }
        }
        if (seal == null || batch.isEmpty()) return

        val commands = batch.map {
            ReaderSurfaceView.AuthoritativeTileInstall(
                it.pageIndex,
                it.pageWidth,
                it.pageHeight,
                it.tiles,
                it.proof
            )
        }
        val result = renderView.installAuthoritativeTileBatch(commands)
        var physicallyInstalled = 0
        for (install in batch) {
            val index = install.pageIndex
            var exact = index in result.installedPages &&
                renderView.hasAuthoritativeOriginalTiles(
                    index,
                    install.pageWidth,
                    install.pageHeight,
                    install.tiles
                )
            // A rejection is never silently acknowledged. Retry the same immutable command through
            // the single-page writer once; a lifecycle/manifest mismatch remains a visible failure.
            if (!exact && activeReaderSessionGeneration.get() == generation &&
                strictExactLaunchSeal === seal
            ) {
                exact = renderView.setPageAuthoritativeOriginalTiles(
                    index,
                    install.pageWidth,
                    install.pageHeight,
                    install.tiles,
                    install.proof
                ) && renderView.hasAuthoritativeOriginalTiles(
                    index,
                    install.pageWidth,
                    install.pageHeight,
                    install.tiles
                )
            }
            if (exact) {
                physicallyInstalled++
                markStrictInstalledPageReady(generation, seal, index)
            } else {
                Log.e(
                    TAG,
                    "authoritative_tiles_reject page=$index source=${install.sourceIndex} " +
                        "reason=batch_surface_ack"
                )
            }
        }
        val rejected = batch.size - physicallyInstalled
        // Exact all-page readiness and every rejection are logged elsewhere. The terminal batch
        // is the useful proof that the accumulated offscreen originals crossed one Surface lock.
        if (rejected != 0 || batch.any { it.pageIndex == seal.pageCount - 1 }) {
            Log.d(
                "ViewerPerf",
                "reader_authoritative_tile_batch generation=$generation," +
                    "submitted=${batch.size},installed=$physicallyInstalled,rejected=$rejected"
            )
        }
        scheduleStrictAuthoritativeInstallFlush()
    }

    private fun pendingStrictAuthoritativeMatches(
        index: Int,
        pageWidth: Int? = null,
        pageHeight: Int? = null,
        tiles: List<ReaderTile>? = null
    ): Boolean {
        val generation = activeReaderSessionGeneration.get()
        return synchronized(strictAuthoritativeInstallLock) {
            val pending = pendingStrictAuthoritativeInstalls[index] ?: return@synchronized false
            if (pending.generation != generation) return@synchronized false
            if (pageWidth == null || pageHeight == null || tiles == null) return@synchronized true
            val candidate = AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles)
                ?: return@synchronized false
            pending.identity.sameAs(candidate)
        }
    }

    private fun clearStrictAuthoritativeInstallQueue() {
        statusHandler.removeCallbacks(strictAuthoritativeInstallFlushRunnable)
        val abandoned = synchronized(strictAuthoritativeInstallLock) {
            val values = pendingStrictAuthoritativeInstalls.values.toList()
            pendingStrictAuthoritativeInstalls.clear()
            strictAuthoritativeInstallFlushScheduled = false
            values
        }
        // ReaderSession relinquishes ownership when this queue accepts a canonical tile page.
        // If lifecycle cancellation clears an entry before Surface installation, this queue is the
        // final owner and must release it. Never recycle an identity already adopted by the View.
        for (install in abandoned) {
            if (!renderView.hasAuthoritativeOriginalTiles(
                    install.pageIndex,
                    install.pageWidth,
                    install.pageHeight,
                    install.tiles
                )
            ) {
                install.tiles.forEach { tile ->
                    if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
                }
            }
        }
    }

    private fun markStrictInstalledPageReady(
        generation: Int,
        seal: StrictExactLaunchSeal,
        index: Int
    ) {
        val state = synchronized(strictRenderReadyLock) {
            if (strictRenderReadyGeneration != generation || strictAllImagesReadyPublished) {
                return@synchronized 0 to false
            }
            strictRenderReadyPages.add(index)
            val count = strictRenderReadyPages.size
            val complete = strictRenderReadyPages.size == seal.pageCount &&
                (0 until seal.pageCount).all(strictRenderReadyPages::contains)
            if (complete) strictAllImagesReadyPublished = true
            count to complete
        }
        val readyCount = state.first
        val publish = state.second
        if (readyCount == 0) return
        if (index == 0 || index == seal.pageCount - 1 || publish || readyCount % 32 == 0) {
            Log.d(
                "ViewerPerf",
                "reader_authoritative_scene_progress generation=$generation," +
                    "ready=$readyCount,pageCount=${seal.pageCount},page=$index,complete=$publish"
            )
        }
        if (publish) {
            queueStrictAllImagesRenderReady(generation, seal, index)
        }
    }

    private fun queueStrictAllImagesRenderReady(
        generation: Int,
        seal: StrictExactLaunchSeal,
        lastInstalledPage: Int
    ) {
        if (activeReaderSessionGeneration.get() != generation || strictExactLaunchSeal !== seal) {
            return
        }
        val publishReady = Runnable {
            if (activeReaderSessionGeneration.get() != generation ||
                strictExactLaunchSeal !== seal
            ) return@Runnable
            Log.d(
                "ViewerPerf",
                "reader_all_images_render_ready_progress generation=$generation," +
                    "count=${seal.pageCount},pageCount=${seal.pageCount}," +
                    "page=$lastInstalledPage,nativeRunwayQueued=true"
            )
            ViewerTelemetry.allImagesRenderReady(readerRoot ?: renderView, seal.pageCount)
        }
        val queued = if (strictRollingHistoricalScene) {
            renderView.queueResidentAuthoritativeTextureRunway(publishReady)
        } else {
            renderView.queueAllAuthoritativeOriginalTextures(seal.pageCount, publishReady)
        }
        if (!queued) {
            statusHandler.postDelayed(
                {
                    if (activeReaderSessionGeneration.get() == generation &&
                        strictExactLaunchSeal === seal
                    ) {
                        queueStrictAllImagesRenderReady(generation, seal, lastInstalledPage)
                    }
                },
                STRICT_ALL_IMAGES_NATIVE_QUEUE_RETRY_MS
            )
        }
    }

    override fun isPageAuthoritativeDrawableInstalled(index: Int): Boolean {
        return if (strictExactLaunchSeal != null) {
            renderView.hasAuthoritativeOriginalPage(index) || pendingStrictAuthoritativeMatches(index)
        } else {
            renderView.hasPageDrawable(index) || pendingStrictAuthoritativeMatches(index)
        }
    }

    override fun isPageAuthoritativeDrawableInstalled(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        return renderView.hasAuthoritativeOriginalTiles(index, pageWidth, pageHeight, tiles) ||
            pendingStrictAuthoritativeMatches(index, pageWidth, pageHeight, tiles)
    }

    override fun areAllAuthoritativeDrawablesInstalled(pageCount: Int): Boolean {
        if (pageCount <= 0) return false
        val historicallyComplete = synchronized(strictRenderReadyLock) {
            val seal = strictExactLaunchSeal
            strictAllImagesReadyPublished &&
                seal != null && seal.pageCount == pageCount &&
                strictRenderReadyGeneration == activeReaderSessionGeneration.get()
        }
        return historicallyComplete &&
            (strictRollingHistoricalScene ||
                renderView.hasCompleteAuthoritativeOriginalScene(pageCount))
    }

    override fun onStrictRollingHistoricalSceneActivated() {
        strictRollingHistoricalScene = true
    }

    override fun onPageLaunchRunwayTilesReady(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ) {
        MainThreadStallMonitor.trace("reader_on_page_launch_runway_tiles_ready") {
            if (isPreRenderedInitialContinuousTiles(index, tiles) && renderView.hasPageDrawable(index)) {
                return@trace
            }
            if (pagesReady && index < pageCount) {
                applyPageTiles(index, pageWidth, pageHeight, tiles, forceImmediateGeometry = true)
            } else {
                rememberPendingPageTiles(index, pageWidth, pageHeight, tiles)
                publishContiguousPendingCurrentEpisodePages("launch_runway_tiles")
            }
        }
    }

    private fun publishContiguousPendingCurrentEpisodePages(reason: String) {
        if (!pagesReady) return
        if (!isCurrentNtkReader()) return
        var newCount = pageCount
        while (pendingPageBitmaps.containsKey(newCount) || pendingPageTiles.containsKey(newCount)) {
            val info = session?.pageInfo(newCount) ?: break
            if (info.transitionCard) break
            if (!isCurrentNtkEpisodePage(info)) break
            newCount++
        }
        if (newCount <= pageCount) return
        if (shouldBatchCurrentReadyRunwayPublish(newCount)) {
            val previousPending = deferredCurrentReadyRunwayPageCount
            val wasScheduled = deferredCurrentReadyRunwayScheduled
            scheduleDeferredCurrentReadyRunway(
                newCount,
                NTK_CURRENT_READY_RUNWAY_BATCH_DELAY_MS
            )
            if ((!wasScheduled || newCount > previousPending) && shouldLogPagesAppendedHotPath()) {
                Log.d(
                    TAG,
                    "pages_appended_current_ready_runway_batch reason=$reason " +
                        "from=$pageCount total=$newCount pending=$deferredCurrentReadyRunwayPageCount " +
                        "currentPage=$currentPage scheduled=$wasScheduled"
                )
            }
            return
        }
        val deferMs = currentReadyRunwayPublishDelayMs(newCount)
        if (deferMs > 0L) {
            scheduleDeferredAppendPages(newCount, deferMs)
            if (shouldLogPagesAppendedHotPath()) {
                Log.d(
                    TAG,
                    "pages_appended_current_ready_runway_defer_active_input reason=$reason " +
                        "from=$pageCount total=$newCount pending=$deferredAppendPageCount " +
                        "currentPage=$currentPage delayMs=$deferMs"
                )
            }
            return
        }
        applyCurrentReadyRunwayPageCount(newCount, reason)
    }

    private fun shouldBatchCurrentReadyRunwayPublish(newCount: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (newCount <= pageCount) return false
        if (isCurrentNtkManhwaOrWebtoonPath()) return true
        if (shouldApplyCurrentNtkSessionExpansionImmediately(newCount) ||
            shouldApplyCurrentNtkEpisodeExpansionImmediately(newCount)
        ) {
            return false
        }
        if (deferredAppendPageCount <= newCount && newCount - pageCount <= 1) return false
        if (ntkActiveAppendPublishQuietRemainingMs() <= 0L && !readerWindowBusy) return false
        return true
    }

    private fun applyCurrentReadyRunwayPageCount(newCount: Int, reason: String) {
        if (newCount <= pageCount) return
        val oldCount = pageCount
        val publishCount = currentReadyRunwayPublishCount(newCount)
        if (publishCount < newCount) {
            scheduleDeferredCurrentReadyRunway(
                newCount,
                NTK_CURRENT_READY_RUNWAY_ACTIVE_CHUNK_DELAY_MS
            )
            if (shouldLogPagesAppendedHotPath()) {
                Log.d(
                    TAG,
                    "pages_appended_current_ready_runway_chunk reason=$reason from=$oldCount " +
                        "immediate=$publishCount total=$newCount currentPage=$currentPage"
                )
            }
        }
        pageCount = publishCount
        renderView.appendPageCount(publishCount, false)
        if (shouldLogPagesAppendedHotPath()) {
            Log.d(
                TAG,
                "pages_appended_current_ready_runway reason=$reason from=$oldCount total=$publishCount " +
                    "requested=$newCount currentPage=$currentPage"
            )
        }
        flushPendingPageCallbacks()
    }

    private fun scheduleDeferredCurrentReadyRunway(count: Int, delayMs: Long) {
        deferredCurrentReadyRunwayPageCount = maxOf(deferredCurrentReadyRunwayPageCount, count)
        deferredCurrentReadyRunwayGeneration = activeReaderSessionGeneration.get()
        if (deferredCurrentReadyRunwayScheduled) return
        deferredCurrentReadyRunwayScheduled = true
        statusHandler.postDelayed(deferredCurrentReadyRunwayRunnable, delayMs)
    }

    private fun currentReadyRunwayPublishCount(newCount: Int): Int {
        if (!isCurrentNtkReader()) return newCount
        if (newCount <= pageCount) return newCount
        if (isCurrentNtkManhwaOrWebtoonPath()) {
            return minOf(newCount, pageCount + NTK_CURRENT_READY_RUNWAY_ACTIVE_WEBTOON_CHUNK_PAGES)
        }
        return minOf(newCount, pageCount + NTK_CURRENT_READY_RUNWAY_ACTIVE_CHUNK_PAGES)
    }

    override fun onPageCard(index: Int, title: String) {
        MainThreadStallMonitor.trace("reader_on_page_card") {
            if (pagesReady && index < pageCount) {
                applyPageCard(index, title)
            } else {
                rememberPendingPageCard(index, title)
            }
        }
    }

    override fun onPageError(index: Int, message: String) {
        MainThreadStallMonitor.trace("reader_on_page_error") {
            if (pagesReady && index < pageCount) {
                applyPageError(index, message)
            } else {
                rememberPendingPageError(index, message)
            }
        }
    }

    override fun onPageCleared(index: Int) {
        synchronized(strictRenderReadyLock) {
            strictRenderReadyPages.remove(index)
            strictAllImagesReadyPublished = false
        }
        if (pagesReady) renderView.clearPageBitmap(index)
    }

    override fun onPageRollingEvicted(index: Int) {
        if (pagesReady) renderView.clearRollingAuthoritativePage(index)
    }

    override fun onInitialPageDecoded(index: Int, bitmap: Bitmap): ReaderSession.InitialPrerenderResult {
        if (!canPreRenderInitialDrawable(index) || bitmap.isRecycled) {
            return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
        val coversInitialViewport =
            drawableCoversInitialViewport(bitmap.width, bitmap.height) ||
                shouldCommitNaverOriginalInitialDrawable()
        val countToSet = synchronized(this) {
            if (!canPreRenderInitialDrawableLocked(index)) {
                return ReaderSession.InitialPrerenderResult.NOT_RENDERED
            }
            if (!pagesReady && !initialStartAtFirstPage) {
                currentPage = index
            }
            preRenderedInitialDrawableIndex = index
            preRenderedInitialBitmap = bitmap
            preRenderedInitialTiles = null
            if (pageCount <= index) {
                pageCount = index + 1
                pageCount
            } else {
                0
            }
        }
        return try {
            if (countToSet > 0) renderView.setPageCount(countToSet)
            renderView.setPageBitmap(index, bitmap)
            if (!pagesReady && !initialStartAtFirstPage) {
                renderView.scrollToPage(index, 0)
            }
            if (coversInitialViewport) {
                logFirstDrawableMetricFromAnyThread(index, "bitmap-prerender")
                Log.d(TAG, "reader_initial_anchor_prerender_bitmap index=$index count=$pageCount")
                ReaderSession.InitialPrerenderResult.RENDERED_AND_COMMIT
            } else {
                logFirstDrawableMetricFromAnyThread(index, "bitmap-prerender")
                deferInitialPrerenderUntilViewportReady(index, "bitmap-prerender")
                Log.d(
                    TAG,
                    "reader_initial_anchor_prerender_bitmap_defer_metric index=$index " +
                        "count=$pageCount,width=${bitmap.width},height=${bitmap.height}"
                )
                ReaderSession.InitialPrerenderResult.RENDERED_ONLY
            }
        } catch (e: Throwable) {
            clearPreRenderedInitialDrawable()
            Log.d(TAG, "reader_initial_anchor_prerender_bitmap_error index=$index,error=${e.javaClass.simpleName}")
            ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
    }

    override fun onInitialPageTilesDecoded(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): ReaderSession.InitialPrerenderResult {
        if (!canPreRenderInitialDrawable(index) || tiles.isEmpty() || tiles.any { it.bitmap.isRecycled }) {
            return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
        val coversInitialViewport =
            drawableCoversInitialViewport(pageWidth, pageHeight) ||
                shouldCommitNaverOriginalInitialDrawable()
        val countToSet = synchronized(this) {
            if (!canPreRenderInitialDrawableLocked(index)) {
                return ReaderSession.InitialPrerenderResult.NOT_RENDERED
            }
            if (!pagesReady && !initialStartAtFirstPage) {
                currentPage = index
            }
            preRenderedInitialDrawableIndex = index
            preRenderedInitialBitmap = null
            preRenderedInitialTiles = tiles
            if (pageCount <= index) {
                pageCount = index + 1
                pageCount
            } else {
                0
            }
        }
        return try {
            if (countToSet > 0) renderView.setPageCount(countToSet)
            renderView.setPageTiles(index, pageWidth, pageHeight, tiles)
            if (!pagesReady && !initialStartAtFirstPage) {
                renderView.scrollToPage(index, 0)
            }
            if (coversInitialViewport) {
                logFirstDrawableMetricFromAnyThread(index, "tiles-prerender")
                Log.d(TAG, "reader_initial_anchor_prerender_tiles index=$index count=$pageCount tiles=${tiles.size}")
                ReaderSession.InitialPrerenderResult.RENDERED_AND_COMMIT
            } else {
                logFirstDrawableMetricFromAnyThread(index, "tiles-prerender")
                deferInitialPrerenderUntilViewportReady(index, "tiles-prerender")
                Log.d(
                    TAG,
                    "reader_initial_anchor_prerender_tiles_defer_metric index=$index " +
                        "count=$pageCount,pageWidth=$pageWidth,pageHeight=$pageHeight,tiles=${tiles.size}"
                )
                ReaderSession.InitialPrerenderResult.RENDERED_ONLY
            }
        } catch (e: Throwable) {
            clearPreRenderedInitialDrawable()
            Log.d(TAG, "reader_initial_anchor_prerender_tiles_error index=$index,error=${e.javaClass.simpleName}")
            ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
    }

    override fun onInitialContinuousPageDecoded(index: Int, bitmap: Bitmap): ReaderSession.InitialPrerenderResult {
        if (!canPreRenderInitialContinuousPage(index) || bitmap.isRecycled) {
            return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
        val countToSet = synchronized(this) {
            if (!canPreRenderInitialContinuousPage(index) || bitmap.isRecycled) {
                return ReaderSession.InitialPrerenderResult.NOT_RENDERED
            }
            preRenderedInitialContinuousTiles.remove(index)
            preRenderedInitialContinuousBitmaps[index] = bitmap
            if (!pagesReady && pageCount <= index) {
                pageCount = index + 1
                pageCount
            } else {
                0
            }
        }
        return try {
            if (countToSet > 0) renderView.setPageCount(countToSet)
            if (shouldUseProofGeometryForInitialContinuousPrerender(index)) {
                renderView.setInitialContinuousPageBitmap(index, bitmap)
            } else {
                renderView.setPageBitmap(index, bitmap)
            }
            logInitialContinuousPrerenderMetricFromAnyThread(index, "bitmap-initial-continuous")
            Log.d(TAG, "reader_initial_continuous_prerender_bitmap index=$index count=$pageCount")
            ReaderSession.InitialPrerenderResult.RENDERED_ONLY
        } catch (e: Throwable) {
            synchronized(this) {
                preRenderedInitialContinuousBitmaps.remove(index)
            }
            Log.d(TAG, "reader_initial_continuous_prerender_bitmap_error index=$index,error=${e.javaClass.simpleName}")
            ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
    }

    override fun onInitialContinuousPageTilesDecoded(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): ReaderSession.InitialPrerenderResult {
        if (!canPreRenderInitialContinuousPage(index) || tiles.isEmpty() || tiles.any { it.bitmap.isRecycled }) {
            return ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
        val countToSet = synchronized(this) {
            if (!canPreRenderInitialContinuousPage(index) ||
                tiles.isEmpty() ||
                tiles.any { it.bitmap.isRecycled }
            ) {
                return ReaderSession.InitialPrerenderResult.NOT_RENDERED
            }
            preRenderedInitialContinuousBitmaps.remove(index)
            preRenderedInitialContinuousTiles[index] = tiles
            if (!pagesReady && pageCount <= index) {
                pageCount = index + 1
                pageCount
            } else {
                0
            }
        }
        return try {
            if (countToSet > 0) renderView.setPageCount(countToSet)
            if (shouldUseProofGeometryForInitialContinuousPrerender(index)) {
                renderView.setInitialContinuousPageTiles(index, pageWidth, pageHeight, tiles)
            } else {
                renderView.setPageTiles(index, pageWidth, pageHeight, tiles)
            }
            logInitialContinuousPrerenderMetricFromAnyThread(index, "tiles-initial-continuous")
            Log.d(TAG, "reader_initial_continuous_prerender_tiles index=$index count=$pageCount tiles=${tiles.size}")
            ReaderSession.InitialPrerenderResult.RENDERED_ONLY
        } catch (e: Throwable) {
            synchronized(this) {
                preRenderedInitialContinuousTiles.remove(index)
            }
            Log.d(TAG, "reader_initial_continuous_prerender_tiles_error index=$index,error=${e.javaClass.simpleName}")
            ReaderSession.InitialPrerenderResult.NOT_RENDERED
        }
    }

    private fun shouldUseProofGeometryForInitialContinuousPrerender(index: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        val first = if (!firstDrawableMetricLogged && initialStartAtFirstPage) 0 else currentPage
        return index > first + initialReadyAheadPages()
    }

    private fun canPreRenderInitialContinuousPage(index: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (index < 0) return false
        val first = if (initialStartAtFirstPage) 0 else currentPage.coerceAtLeast(0)
        if (index !in first..(first + initialContinuousPreRenderAheadPages())) return false
        return !pagesReady || pageCount > index
    }

    private fun canPreRenderInitialDrawable(index: Int): Boolean {
        if (!::renderView.isInitialized) {
            Log.d(TAG, "reader_initial_anchor_prerender_skip index=$index,reason=render_uninitialized")
            return false
        }
        if (renderView.parent == null) {
            Log.d(TAG, "reader_initial_anchor_prerender_skip index=$index,reason=render_detached")
            return false
        }
        val allowed = synchronized(this) { canPreRenderInitialDrawableLocked(index) }
        if (!allowed) {
            Log.d(
                TAG,
                "reader_initial_anchor_prerender_skip index=$index,reason=state," +
                    "pagesReady=$pagesReady,firstDrawableMetricLogged=$firstDrawableMetricLogged," +
                    "preRendered=$preRenderedInitialDrawableIndex,currentPage=$currentPage," +
                    "initialStart=$initialStartAtFirstPage"
            )
        }
        return allowed
    }

    private fun canPreRenderInitialDrawableLocked(index: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (firstDrawableMetricLogged) return false
        if (preRenderedInitialDrawableIndex >= 0 && preRenderedInitialDrawableIndex != index) return false
        if (initialStartAtFirstPage) {
            return index == 0
        }
        if (!pagesReady && currentPage == 0 && index == 0 && isCurrentNtkReader()) {
            return true
        }
        if (!pagesReady && isCurrentNtkReader() && index in 0..initialReadyAheadPages()) {
            return true
        }
        if (!pagesReady && isCurrentNtkReader() && !initialStartAtFirstPage && index >= 0) {
            return true
        }
        if (!pagesReady || index < 0 || index >= pageCount) return false
        return true
    }

    private fun shouldPreservePreRenderedInitialDrawable(count: Int): Boolean {
        return synchronized(this) {
            val index = preRenderedInitialDrawableIndex
            index >= 0 &&
                count > index &&
                pageCount == index + 1
        }
    }

    private fun isPreRenderedInitialBitmap(index: Int, bitmap: Bitmap): Boolean {
        return synchronized(this) {
            preRenderedInitialDrawableIndex == index && preRenderedInitialBitmap === bitmap
        }
    }

    private fun isPreRenderedInitialTiles(index: Int, tiles: List<ReaderTile>): Boolean {
        return synchronized(this) {
            preRenderedInitialDrawableIndex == index && preRenderedInitialTiles === tiles
        }
    }

    private fun isPreRenderedInitialContinuousBitmap(index: Int, bitmap: Bitmap): Boolean {
        return synchronized(this) {
            preRenderedInitialContinuousBitmaps[index] === bitmap
        }
    }

    private fun isPreRenderedInitialContinuousTiles(index: Int, tiles: List<ReaderTile>): Boolean {
        return synchronized(this) {
            preRenderedInitialContinuousTiles[index] === tiles
        }
    }

    @Synchronized
    private fun clearPreRenderedInitialAnchorDrawable() {
        preRenderedInitialDrawableIndex = -1
        preRenderedInitialBitmap = null
        preRenderedInitialTiles = null
    }

    @Synchronized
    private fun clearPreRenderedInitialDrawable() {
        clearPreRenderedInitialAnchorDrawable()
        preRenderedInitialContinuousBitmaps.clear()
        preRenderedInitialContinuousTiles.clear()
    }

    private fun rememberPendingPageBitmap(index: Int, bitmap: Bitmap) {
        pendingPageTiles.remove(index)
        pendingPageCards.remove(index)
        pendingPageErrors.remove(index)
        pendingPageBitmaps[index] = bitmap
        if (!bootstrapDeferredInitialContinuousPage(index, bitmap)) {
            bootstrapDeferredFirstPage(index, bitmap)
        }
    }

    private fun rememberPendingPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        pendingPageBitmaps.remove(index)
        pendingPageCards.remove(index)
        pendingPageErrors.remove(index)
        pendingPageTiles[index] = PendingPageTiles(pageWidth, pageHeight, tiles)
        if (!bootstrapDeferredInitialContinuousPageTiles(index, pageWidth, pageHeight, tiles)) {
            bootstrapDeferredFirstPageTiles(index, pageWidth, pageHeight, tiles)
        }
    }

    private fun rememberPendingPageCard(index: Int, title: String) {
        pendingPageBitmaps.remove(index)
        pendingPageTiles.remove(index)
        pendingPageErrors.remove(index)
        pendingPageCards[index] = title
    }

    private fun rememberPendingPageError(index: Int, message: String) {
        pendingPageBitmaps.remove(index)
        pendingPageTiles.remove(index)
        pendingPageCards.remove(index)
        pendingPageErrors[index] = message
    }

    private fun bootstrapDeferredFirstPage(index: Int, bitmap: Bitmap) {
        if (pagesReady || index != currentPage || bitmap.isRecycled) return
        if (pageCount <= index) {
            pageCount = index + 1
            renderView.setPageCount(pageCount)
        }
        Log.d(TAG, "page_ready_deferred_bootstrap index=$index count=$pageCount")
        applyPageBitmap(index, bitmap)
    }

    private fun bootstrapDeferredFirstPageTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ) {
        if (pagesReady || index != currentPage || tiles.isEmpty()) return
        if (tiles.any { it.bitmap.isRecycled }) return
        if (pageCount <= index) {
            pageCount = index + 1
            renderView.setPageCount(pageCount)
        }
        Log.d(TAG, "page_tiles_deferred_bootstrap index=$index count=$pageCount tiles=${tiles.size}")
        applyPageTiles(index, pageWidth, pageHeight, tiles)
    }

    private fun canBootstrapInitialContinuousPage(index: Int): Boolean {
        if (pagesReady || !isCurrentNtkReader() || !initialStartAtFirstPage) return false
        if (currentPage != 0 || index < 0) return false
        return index <= initialContinuousPreRenderAheadPages()
    }

    private fun bootstrapDeferredInitialContinuousPage(index: Int, bitmap: Bitmap): Boolean {
        if (!canBootstrapInitialContinuousPage(index) || bitmap.isRecycled) return false
        if (pageCount <= index) {
            pageCount = index + 1
            renderView.setPageCount(pageCount)
        }
        Log.d(TAG, "page_ready_deferred_bootstrap_initial_continuous index=$index count=$pageCount")
        applyPageBitmap(index, bitmap)
        return true
    }

    private fun bootstrapDeferredInitialContinuousPageTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        if (!canBootstrapInitialContinuousPage(index) || tiles.isEmpty()) return false
        if (tiles.any { it.bitmap.isRecycled }) return false
        if (pageCount <= index) {
            pageCount = index + 1
            renderView.setPageCount(pageCount)
        }
        Log.d(TAG, "page_tiles_deferred_bootstrap_initial_continuous index=$index count=$pageCount tiles=${tiles.size}")
        applyPageTiles(index, pageWidth, pageHeight, tiles)
        return true
    }

    private fun flushPendingPageCallbacks() {
        schedulePendingPageCallbackFlush(0L)
    }

    /**
     * Posts one bounded prepare hint after the production reader surface has its initial runway.
     * This never gates visibility or input: [Bitmap.prepareToDraw] only queues preparation for
     * the already-installed software bitmaps selected by ReaderSurfaceView's 1.5-viewport policy.
     */
    private fun requestInitialSoftwareRunwayPrepare(reason: String) {
        if (initialSoftwareRunwayPrepareRequested || destroyed || !::renderView.isInitialized) return
        initialSoftwareRunwayPrepareRequested = true
        initialSoftwareRunwayPrepareReason = reason
        initialSoftwareRunwayPrepareView = renderView
        // Posting keeps page publication/onCreate off this optional hint's call stack. In the
        // pages-ready path the pending-page flush was posted first on the same main looper.
        renderView.post(initialSoftwareRunwayPrepareRunnable)
    }

    private fun prepareInitialSoftwareRunwayIfReady() {
        if (initialSoftwareRunwayPrepareCompleted || destroyed || isFinishing) {
            clearInitialSoftwareRunwayPrepareCallback()
            return
        }
        val target = initialSoftwareRunwayPrepareView ?: return
        if (target !== renderView) {
            clearInitialSoftwareRunwayPrepareCallback()
            return
        }
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            if (initialSoftwareRunwayPrepareLayoutListener == null) {
                val listener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                    if (view !== target || !view.isAttachedToWindow || right <= left || bottom <= top) {
                        return@OnLayoutChangeListener
                    }
                    target.removeOnLayoutChangeListener(initialSoftwareRunwayPrepareLayoutListener)
                    initialSoftwareRunwayPrepareLayoutListener = null
                    target.post(initialSoftwareRunwayPrepareRunnable)
                }
                initialSoftwareRunwayPrepareLayoutListener = listener
                target.addOnLayoutChangeListener(listener)
            }
            return
        }

        initialSoftwareRunwayPrepareCompleted = true
        clearInitialSoftwareRunwayPrepareCallback()
        val stats = target.prepareInitialSoftwareRunway()
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_v2_initial_software_prepare_count",
            stats.bitmapCount.toLong()
        )
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_v2_initial_software_prepare_bytes",
            stats.bytes
        )
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_v2_initial_software_prepare_us",
            stats.elapsedMicros
        )
        Log.d(
            "ViewerPerf",
            "reader_v2_initial_software_prepare reason=$initialSoftwareRunwayPrepareReason," +
                "pages=${stats.bitmapCount},bytes=${stats.bytes},first=${stats.firstPage}," +
                "last=${stats.lastPage},us=${stats.elapsedMicros}"
        )
    }

    private fun clearInitialSoftwareRunwayPrepareCallback() {
        val target = initialSoftwareRunwayPrepareView
        target?.removeCallbacks(initialSoftwareRunwayPrepareRunnable)
        initialSoftwareRunwayPrepareLayoutListener?.let { listener ->
            target?.removeOnLayoutChangeListener(listener)
        }
        initialSoftwareRunwayPrepareLayoutListener = null
        initialSoftwareRunwayPrepareView = null
    }

    private fun schedulePendingPageCallbackFlush(delayMs: Long) {
        if (!pagesReady || pendingPageCallbackFlushScheduled) return
        if (
            pendingPageBitmaps.isEmpty() &&
            pendingPageTiles.isEmpty() &&
            pendingPageCards.isEmpty() &&
            pendingPageErrors.isEmpty()
        ) return
        pendingPageCallbackFlushScheduled = true
        if (delayMs > 0L) {
            statusHandler.postDelayed(pendingPageCallbackFlushRunnable, delayMs)
        } else {
            statusHandler.post(pendingPageCallbackFlushRunnable)
        }
    }

    private fun flushPendingPageCallbacksNow() {
        if (!pagesReady) return
        if (
            pendingPageBitmaps.isEmpty() &&
                pendingPageTiles.isEmpty() &&
            pendingPageCards.isEmpty() &&
            pendingPageErrors.isEmpty()
        ) return
        val eligibleBitmapIndexes = pendingPageBitmaps.filter { entry ->
            !isPreRenderedInitialBitmap(entry.key, entry.value) &&
                !isPreRenderedInitialContinuousBitmap(entry.key, entry.value) &&
                entry.key < pageCount
        }.keys
        val eligibleTileIndexes = pendingPageTiles.filter { entry ->
            !isPreRenderedInitialTiles(entry.key, entry.value.tiles) &&
                !isPreRenderedInitialContinuousTiles(entry.key, entry.value.tiles) &&
                entry.key < pageCount
        }.keys
        val indexes = (
            eligibleBitmapIndexes +
                eligibleTileIndexes +
                pendingPageCards.keys.filter { it < pageCount } +
                pendingPageErrors.keys.filter { it < pageCount }
            ).distinct()
            .sortedWith(compareBy<Int> { abs(it - currentPage) }.thenBy { it })
        if (indexes.isEmpty()) return
        val batch = indexes.take(pendingPageCallbackFlushBatchSize())
        var bitmaps = 0
        var tiles = 0
        var cards = 0
        var errors = 0
        for (index in batch) {
            val error = pendingPageErrors.remove(index)
            val card = pendingPageCards.remove(index)
            val pendingTiles = pendingPageTiles.remove(index)
            val bitmap = pendingPageBitmaps.remove(index)
            when {
                error != null -> {
                    errors++
                    applyPageError(index, error)
                }
                card != null -> {
                    cards++
                    applyPageCard(index, card)
                }
                pendingTiles != null -> {
                    tiles++
                    applyPageTiles(
                        index,
                        pendingTiles.pageWidth,
                        pendingTiles.pageHeight,
                        pendingTiles.tiles,
                        forceImmediateGeometry = isStrictContinuousAppendedPage(index)
                    )
                }
                bitmap != null -> {
                    bitmaps++
                    applyPageBitmap(
                        index,
                        bitmap,
                        forceImmediateGeometry = isStrictContinuousAppendedPage(index)
                    )
                }
            }
        }
        maybeLogPendingPageCallbackFlush(batch.size, indexes.size - batch.size, bitmaps, tiles, cards, errors)
        if (indexes.size > batch.size) {
            schedulePendingPageCallbackFlush(pendingPageCallbackFlushDelayMs())
        }
    }

    private fun pendingPageCallbackFlushBatchSize(): Int {
        return if (isCurrentNtkManhwaOrWebtoonPath()) {
            PENDING_PAGE_CALLBACK_FLUSH_ACTIVE_NTK_BATCH_SIZE
        } else {
            PENDING_PAGE_CALLBACK_FLUSH_BATCH_SIZE
        }
    }

    private fun pendingPageCallbackFlushDelayMs(): Long {
        return if (isCurrentNtkManhwaOrWebtoonPath()) {
            PENDING_PAGE_CALLBACK_FLUSH_ACTIVE_NTK_BATCH_DELAY_MS
        } else {
            PENDING_PAGE_CALLBACK_FLUSH_BATCH_DELAY_MS
        }
    }

    private fun maybeLogPendingPageCallbackFlush(
        count: Int,
        remaining: Int,
        bitmaps: Int,
        tiles: Int,
        cards: Int,
        errors: Int
    ) {
        val now = SystemClock.uptimeMillis()
        if (isCurrentNtkManhwaOrWebtoonPath() &&
            now - lastPendingPageCallbackFlushLogMs < PENDING_PAGE_CALLBACK_FLUSH_LOG_MS
        ) {
            return
        }
        if (remaining > 0 && now - lastPendingPageCallbackFlushLogMs < PENDING_PAGE_CALLBACK_FLUSH_LOG_MS) {
            return
        }
        lastPendingPageCallbackFlushLogMs = now
        Log.d(
            TAG,
            "page_ready_deferred_flush count=$count remaining=$remaining " +
                "bitmaps=$bitmaps tiles=$tiles cards=$cards errors=$errors"
        )
    }

    private fun clearPendingPageCallbacks() {
        pendingPageBitmaps.clear()
        pendingPageTiles.clear()
        pendingPageCards.clear()
        pendingPageErrors.clear()
    }

    private fun ensureRenderViewAttached() {
        if (!::renderView.isInitialized || renderView.parent != null) return
        val root = readerRoot ?: return
        root.addView(
            renderView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        Log.d(TAG, "reader_render_attached_for_drawable")
    }

    private fun applyPageBitmap(
        index: Int,
        bitmap: Bitmap,
        forceImmediateGeometry: Boolean = false
    ) {
        hideBoundaryStatus()
        ensureRenderViewAttached()
        renderView.setPageBitmap(index, bitmap, forceImmediateGeometry)
        val visibleInitialDrawable = shouldMarkFirstDrawable(index, currentPage)
        val coversInitialViewport = visibleInitialDrawable &&
            drawableCoversInitialViewport(bitmap.width, bitmap.height)
        val waitForContinuous = shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable) &&
            !coversInitialViewport
        if (visibleInitialDrawable && waitForContinuous) {
            recordFirstImageDrawableElapsedForTest(index, "bitmap")
        }
        val launchMetricDeferred = maybePostInitialContinuousDrawableMetric(index, "bitmap")
        if (visibleInitialDrawable && !waitForContinuous) logFirstDrawableMetric(index, "bitmap")
        if (visibleInitialDrawable) logLaunchDrawableMetric(index, "bitmap")
        if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
        if (waitForContinuous) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (visibleInitialDrawable) {
            releaseInitialDrawGate("page")
        } else if (isCurrentNtkReader() && !firstDrawableMetricLogged) {
            logFirstDrawableMetric(index, "bitmap")
            releaseInitialDrawGate("page")
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (!launchMetricDeferred) {
            logLaunchDrawableMetric(index, "bitmap")
        }
    }

    private fun applyPageTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        forceImmediateGeometry: Boolean = false
    ) {
        hideBoundaryStatus()
        ensureRenderViewAttached()
        renderView.setPageTiles(index, pageWidth, pageHeight, tiles, forceImmediateGeometry)
        val visibleInitialDrawable = shouldMarkFirstDrawable(index, currentPage)
        val coversInitialViewport = visibleInitialDrawable &&
            drawableCoversInitialViewport(pageWidth, pageHeight)
        val waitForContinuous = shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable) &&
            !coversInitialViewport
        if (visibleInitialDrawable && waitForContinuous) {
            recordFirstImageDrawableElapsedForTest(index, "tiles")
        }
        val launchMetricDeferred = maybePostInitialContinuousDrawableMetric(index, "tiles")
        if (visibleInitialDrawable && !waitForContinuous) logFirstDrawableMetric(index, "tiles")
        if (visibleInitialDrawable) logLaunchDrawableMetric(index, "tiles")
        if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
        if (waitForContinuous) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (visibleInitialDrawable) {
            releaseInitialDrawGate("tiles")
        } else if (isCurrentNtkReader() && !firstDrawableMetricLogged) {
            logFirstDrawableMetric(index, "tiles")
            releaseInitialDrawGate("tiles")
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (!launchMetricDeferred) {
            logLaunchDrawableMetric(index, "tiles")
        }
    }

    private fun applyPageCard(index: Int, title: String) {
        hideBoundaryStatus()
        renderView.setPageCard(index, title)
        releaseInitialDrawGate("card")
    }

    private fun applyPageError(index: Int, message: String) {
        hideBoundaryStatus()
        Log.d(TAG, "page_error_visible index=$index currentPage=$currentPage message=$message")
        renderView.setPageError(index, message)
        if (isCurrentNtkReader()) {
            val terminalDescription = "viewer-terminal-image-failure:$index"
            terminalImageFailureDescription = terminalDescription
            renderView.contentDescription = terminalDescription
            renderView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            readerRoot?.contentDescription = terminalDescription
            readerRoot?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            renderView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            readerRoot?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        val visibleInitialDrawable = shouldMarkFirstDrawable(index, currentPage)
        logLaunchDrawableMetric(index, "error")
        if (visibleInitialDrawable && !isCurrentNtkReader()) releaseInitialDrawGate("error")
    }

    private fun applyPendingInitialRestoreIfReady() {
        val page = pendingInitialRestorePage
        if (page < 0) return
        val info = session?.pageInfo(page) ?: return
        if (!info.layoutReady) return
        val offset = pendingInitialRestoreOffset
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        currentPage = page
        renderView.lockRestoredPageOffset(page, offset)
        renderView.holdInitialRestoreRender(page)
        updateCurrentEpisode(page, offset, saveProgress = false)
    }

    private fun logFirstDrawableMetric(index: Int, kind: String) {
        if (!markFirstDrawableMetric(index, kind)) return
        afterFirstDrawableMetricOnMain(index)
    }

    private fun logFirstDrawableMetricFromAnyThread(index: Int, kind: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logFirstDrawableMetric(index, kind)
            return
        }
        if (!markFirstDrawableMetric(index, kind)) return
        statusHandler.post { afterFirstDrawableMetricOnMain(index) }
    }

    private fun shouldDeferAfterFirstDrawableFollowupsForNtkDirectManifest(): Boolean {
        if (!isCurrentNtkReader() || hybridNtkBrowserActive) return false
        val manga = currentManga ?: return false
        val path = manga.ntkEpisodePath ?: return false
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        return hasStrictDirectManifestAckAuthority(path) ||
            hasCompleteNativeDirectManifest(manga) ||
            hasForegroundDirectManifestOwnership(manga, path)
    }

    private fun afterFirstDrawableFollowupQuietRemainingMs(): Long {
        val now = SystemClock.uptimeMillis()
        var remaining = 0L
        val firstLoggedAt = firstDrawableLoggedAtMs
        if (firstLoggedAt > 0L) {
            remaining = maxOf(
                remaining,
                firstLoggedAt + NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INITIAL_DELAY_MS - now
            )
        }
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs)
        if (readerWindowBusy || lastActiveMs > firstLoggedAt) {
            remaining = maxOf(
                remaining,
                lastActiveMs + NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INPUT_QUIET_MS - now
            )
        }
        return remaining.coerceAtLeast(0L)
    }

    private fun scheduleAfterFirstDrawableFollowupsWhenQuiet(index: Int, delayMs: Long) {
        statusHandler.postDelayed({
            if (destroyed || isFinishing) return@postDelayed
            if (!shouldDeferAfterFirstDrawableFollowupsForNtkDirectManifest()) {
                runAfterFirstDrawableMetricFollowups(index)
                return@postDelayed
            }
            val remaining = afterFirstDrawableFollowupQuietRemainingMs()
            if (remaining > 0L) {
                scheduleAfterFirstDrawableFollowupsWhenQuiet(
                    index,
                    remaining.coerceAtMost(NTK_POST_FIRST_DRAWABLE_FOLLOWUP_RECHECK_MS)
                )
                return@postDelayed
            }
            Log.d(TAG, "reader_first_drawable_followups_run_after_input_quiet index=$index")
            runAfterFirstDrawableMetricFollowups(index)
        }, delayMs.coerceAtLeast(1L))
    }

    private fun deferInitialPrerenderUntilViewportReady(index: Int, kind: String) {
        statusHandler.post {
            if (destroyed || isFinishing) return@post
            logLaunchDrawableMetric(index, kind)
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_prerender")
            scheduleNtkDrawableReadyPolling()
        }
    }

    private fun ntkAppendPublishQuietRemainingMs(): Long {
        if (!isCurrentNtkReader()) return 0L
        val now = SystemClock.uptimeMillis()
        var remaining = 0L
        val lastInputMs = maxOf(lastReaderInteractionMs, pendingBoundaryStartInteractionMs)
        if (lastInputMs > 0L) {
            remaining = maxOf(remaining, lastInputMs + NTK_APPEND_PUBLISH_INPUT_QUIET_MS - now)
        }
        if (readerWindowBusy && lastReaderBusyMs > 0L) {
            remaining = maxOf(remaining, lastReaderBusyMs + NTK_APPEND_PUBLISH_AFTER_SCROLL_ACTIVE_MS - now)
        }
        if (::renderView.isInitialized && lastInputMs > 0L && now - lastInputMs <= NTK_APPEND_PUBLISH_MAX_DEFER_MS) {
            val programmaticRemaining = renderView.programmaticScrollActiveRemainingMs(now)
            if (programmaticRemaining > 0L) {
                remaining = maxOf(
                    remaining,
                    minOf(programmaticRemaining, NTK_APPEND_PUBLISH_AFTER_SCROLL_ACTIVE_MS)
                )
            }
        }
        return remaining.coerceIn(0L, NTK_APPEND_PUBLISH_MAX_DEFER_MS)
    }

    private fun currentReadyRunwayPublishDelayMs(newCount: Int): Long {
        if (!isCurrentNtkReader()) return 0L
        if (newCount <= pageCount) return 0L
        if (shouldApplyTailAppendExpansionImmediately(newCount)) return 0L
        val pendingLargeExpansion = deferredAppendPageCount - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES
        val largeExpansion = newCount - pageCount > NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES
        if (!pendingLargeExpansion && !largeExpansion) return 0L
        return ntkLargeAppendPublishQuietRemainingMs()
    }

    private fun ntkActiveAppendPublishQuietRemainingMs(
        snapshot: ReaderSurfaceView.VisibleCoverageSnapshot? = null
    ): Long {
        if (!isCurrentNtkReader()) return 0L
        val now = SystemClock.uptimeMillis()
        var remaining = 0L
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs, pendingBoundaryStartInteractionMs)
        if (lastActiveMs > 0L) {
            remaining = maxOf(remaining, lastActiveMs + NTK_ACTIVE_APPEND_PUBLISH_QUIET_MS - now)
        }
        if (readerWindowBusy || snapshot?.busy == true) {
            remaining = maxOf(remaining, NTK_ACTIVE_APPEND_PUBLISH_QUIET_MS)
        }
        if (::renderView.isInitialized) {
            val programmaticRemaining = renderView.programmaticScrollActiveRemainingMs(now)
            if (programmaticRemaining > 0L) {
                remaining = maxOf(
                    remaining,
                    minOf(
                        programmaticRemaining + NTK_APPEND_PUBLISH_AFTER_SCROLL_ACTIVE_MS,
                        NTK_ACTIVE_APPEND_PUBLISH_MAX_DEFER_MS
                    )
                )
            }
        }
        return remaining.coerceIn(0L, NTK_ACTIVE_APPEND_PUBLISH_MAX_DEFER_MS)
    }

    private fun ntkLargeAppendPublishQuietRemainingMs(): Long {
        if (!isCurrentNtkReader()) return 0L
        val now = SystemClock.uptimeMillis()
        var remaining = 0L
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs, pendingBoundaryStartInteractionMs)
        if (lastActiveMs > 0L) {
            remaining = maxOf(remaining, lastActiveMs + NTK_LARGE_APPEND_PUBLISH_QUIET_MS - now)
        }
        if (readerWindowBusy) {
            remaining = maxOf(remaining, NTK_LARGE_APPEND_PUBLISH_QUIET_MS)
        }
        if (::renderView.isInitialized) {
            val programmaticRemaining = renderView.programmaticScrollActiveRemainingMs(now)
            if (programmaticRemaining > 0L) {
                remaining = maxOf(
                    remaining,
                    minOf(
                        programmaticRemaining + NTK_APPEND_PUBLISH_AFTER_SCROLL_ACTIVE_MS,
                        NTK_LARGE_APPEND_PUBLISH_MAX_DEFER_MS
                    )
                )
            }
        }
        return remaining.coerceIn(0L, NTK_LARGE_APPEND_PUBLISH_MAX_DEFER_MS)
    }

    @Synchronized
    private fun markFirstDrawableMetric(index: Int, kind: String): Boolean {
        if (firstDrawableMetricLogged || viewerLaunchStartedAtMs <= 0L) return false
        firstDrawableMetricLogged = true
        firstDrawableLoggedAtMs = SystemClock.uptimeMillis()
        firstDrawableLoggedElapsedAtMs = SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
        if (firstDrawableElapsedMsForTest < 0L || elapsed < firstDrawableElapsedMsForTest) {
            firstDrawableElapsedMsForTest = elapsed
        }
        Log.d("ViewerPerf", "reader_open_to_first_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
        if (launchDrawableMetricPages.add(index)) {
            launchDrawableElapsedMsByPage[index] = elapsed
            Log.d("ViewerPerf", "reader_open_to_near_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
        }
        return true
    }

    @Synchronized
    private fun recordFirstImageDrawableElapsedForTest(index: Int, kind: String) {
        if (firstDrawableMetricLogged) return
        if (viewerLaunchStartedAtMs <= 0L) return
        if (!isCurrentNtkReader() || !initialStartAtFirstPage || index != 0) return
        val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
        Log.d(
            "ViewerPerf",
            "reader_open_to_first_image_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed"
        )
    }

    private fun afterFirstDrawableMetricOnMain(index: Int) {
        if (shouldDeferAfterFirstDrawableFollowupsForNtkDirectManifest()) {
            Log.d(TAG, "reader_first_drawable_followups_defer_for_input_quiet index=$index")
            scheduleAfterFirstDrawableFollowupsWhenQuiet(
                index,
                NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INITIAL_DELAY_MS
            )
            scheduleDrawableReadyDescription(index)
            return
        }
        if (isCurrentNtkReader() && !hybridNtkBrowserActive) {
            val path = currentManga?.ntkEpisodePath
            NtkBrowserSessionBroker.quietForNativeReader(path, "native_first_drawable")
            quietNtkWebViewFallbackForNativeReader("native_first_drawable")
        }
        runAfterFirstDrawableMetricFollowups(index)
    }

    private fun runAfterFirstDrawableMetricFollowups(index: Int) {
        if (isCurrentNtkReader() && !hybridNtkBrowserActive) {
            val path = currentManga?.ntkEpisodePath
            NtkBrowserSessionBroker.quietForNativeReader(path, "native_first_drawable")
            quietNtkWebViewFallbackForNativeReader("native_first_drawable")
        }
        scheduleDrawableReadyDescription(index)
        if (shouldSkipPostDrawableNtkAckForDirectManifest("first_drawable")) {
            clearDeferredNtkAckPreflightAfterValidatedDirectManifest("first_drawable")
        } else {
            startImmediateNtkNativeAckAfterFirstDrawable("first_drawable")
            scheduleDeferredNtkAckAfterFirstDrawable("first_drawable")
        }
        maybeStartDeferredNtkAckAfterInitialContinuous()
    }

    private fun quietNtkWebViewFallbackForNativeReader(
        reason: String,
        now: Long = SystemClock.uptimeMillis()
    ) {
        if (!isCurrentNtkReader() || hybridNtkBrowserActive) return
        val path = currentManga?.ntkEpisodePath ?: return
        if (now - lastNtkWebViewFallbackQuietExtendMs < NTK_WEBVIEW_FALLBACK_QUIET_EXTEND_INTERVAL_MS) {
            return
        }
        lastNtkWebViewFallbackQuietExtendMs = now
        NtkWebViewFallbackManager.quietForForegroundNativeReader(
            applicationContext,
            path,
            reason
        )
    }

    private fun scheduleDrawableReadyDescription(index: Int) {
        if (drawableReadyDescriptionPosted) return
        if (hybridNtkBrowserActive && !isVisibleViewportReady()) {
            scheduleNtkDrawableReadyPolling()
            return
        }
        if (requiresInitialNtkWebtoonViewportReady() && !isVisibleViewportReady()) {
            scheduleNtkDrawableReadyPolling()
            return
        }
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        if (initialStartAtFirstPage && index == 0) {
            postDrawableReadyDescription()
            return
        }
        drawableReadyDescriptionRunnable.run()
    }

    private fun isVisibleViewportReady(): Boolean {
        if (hybridNtkBrowserActive) {
            return hybridNtkFirstDrawableReady &&
                hybridNtkViewportReady &&
                hybridNtkCoverageSnapshot?.let { isHybridCoverageDrawable(it) } == true
        }
        if (!::renderView.isInitialized) return false
        val snapshot = renderView.visibleCoverageSnapshot()
        return isNativeCoverageViewportReady(snapshot)
    }

    private fun isNativeCoverageViewportReady(
        snapshot: ReaderSurfaceView.VisibleCoverageSnapshot?
    ): Boolean {
        if (snapshot == null) return false
        val physicalViewportPx = if (snapshot.physicalViewportPx > 0) {
            snapshot.physicalViewportPx
        } else {
            snapshot.viewportPx
        }
        return (
            snapshot.drawablePx > 0 &&
            snapshot.viewportPx >= (physicalViewportPx - INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX).coerceAtLeast(1) &&
            snapshot.drawablePx >= (physicalViewportPx - INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX).coerceAtLeast(1) &&
            snapshot.visibleLoading == 0 &&
            snapshot.missingPx == 0 &&
            snapshot.placeholderPx == 0
        )
    }

    private fun isTransientEmptyNativeCoverage(
        snapshot: ReaderSurfaceView.VisibleCoverageSnapshot?
    ): Boolean {
        if (snapshot == null) return true
        return snapshot.pageCount <= 0 ||
            snapshot.viewportPx <= 0 ||
            (snapshot.drawablePx <= 0 && snapshot.visibleLoading > 0)
    }

    private fun isHybridCoverageDrawable(snapshot: NtkBrowserSessionBroker.VisibleCoverageSnapshot): Boolean {
        val viewportPx = snapshot.viewportPx.coerceAtLeast(1)
        return snapshot.drawablePx >= (viewportPx - INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX).coerceAtLeast(1) &&
            snapshot.missingPx <= INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX &&
            snapshot.visibleLoading == 0 &&
            snapshot.visibleErrors == 0
    }

    private fun markHybridDrawableCoverageReadyIfPossible(): Boolean {
        val snapshot = hybridNtkCoverageSnapshot ?: return false
        if (!isCurrentHybridNtkPath(snapshot.path)) return false
        if (!isHybridCoverageDrawable(snapshot)) return false
        hybridNtkViewportReady = true
        if (!hybridNtkFirstDrawableReady) return false
        val loggedFirstMetric = if (!firstDrawableMetricLogged) {
            logFirstDrawableMetric(0, "hybrid-webview-ready-coverage")
            true
        } else {
            false
        }
        postDrawableReadyDescription()
        status.visibility = TextView.GONE
        if (loggedFirstMetric) requestHybridNtkPumpAll("first-drawable-ready")
        return true
    }

    private fun requestHybridNtkPumpAll(reason: String, delayMs: Long = 32L) {
        if (!hybridNtkBrowserActive || hybridNtkPumpAllRequested) return
        hybridNtkPumpAllRequested = true
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        NtkBrowserSessionBroker.pumpControlledForegroundFetch(path, reason)
        statusHandler.postDelayed({
            if (!hybridNtkBrowserActive || destroyed || isFinishing) return@postDelayed
            val script = "try{if(window.__mvNtkPumpPromoteAll)window.__mvNtkPumpPromoteAll();}catch(e){}"
            try {
                hybridNtkWebView?.evaluateJavascript(script, null)
                Log.d("ViewerPerf", "reader_ntk_hybrid_pump_all path=$path,reason=$reason")
            } catch (e: Throwable) {
                Log.d(TAG, "reader_ntk_hybrid_pump_all_error path=$path,reason=$reason,$e")
            }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun drawableCoversInitialViewport(pageWidth: Int, pageHeight: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (pageWidth <= 0 || pageHeight <= 0) return false
        val viewWidth = if (renderView.width > 0) renderView.width else resources.displayMetrics.widthPixels
        val viewHeight = if (renderView.height > 0) renderView.height else resources.displayMetrics.heightPixels
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val drawHeight = viewWidth * (pageHeight / pageWidth.toFloat())
        return drawHeight >= viewHeight - INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX
    }

    private fun shouldCommitNaverOriginalInitialDrawable(): Boolean {
        return isNaverOriginalNtkEpisodePath(currentManga?.ntkEpisodePath)
    }

    private fun isNaverOriginalNtkEpisodePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return Regex("^/webtoon/\\d+/(?:nv|naver)-\\d{5,}-\\d+$", RegexOption.IGNORE_CASE)
            .matches(path)
    }

    private fun shouldPreferNtkApiForCanonicalWebtoonPath(
        workSlug: String,
        episodeToken: String
    ): Boolean {
        if (!workSlug.matches(Regex("\\d{1,12}"))) return false
        if (!episodeToken.matches(Regex("\\d{1,12}"))) return false
        return workSlug.toLongOrNull()?.let {
            it >= NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID
        } ?: true
    }

    private fun primeSlugWebtoonInitialImageAtActivityStart(
        manga: Manga,
        launchPreflightStarted: Boolean = false
    ): Boolean {
        if (!manga.isOnline) return false
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "legacy_initial_prime_reroute")
            return true
        }
        val match = Regex("^/webtoon/([^/?#]+)/([^/?#]+)", RegexOption.IGNORE_CASE).find(path)
            ?: return false
        val workSlug = match.groupValues[1].trim()
        val episodeToken = match.groupValues[2].trim()
        if (!episodeToken.matches(Regex("\\d{1,12}"))) return false
        val numericWebtoon = workSlug.all { it.isDigit() }
        if (!numericWebtoon) {
            startStrictNtkDiscovery(manga, "retired_slug_prefetch")
            Log.d(
                TAG,
                "reader_activity_slug_initial_stream_skip_api_first_slug path=$path," +
                    "episode=$episodeToken"
            )
            return true
        }
        if (getHttpClient().isModernNtkGuardRootForPath(path)) {
            startStrictNtkDiscovery(manga, "retired_protected_prefetch")
            Log.d(
                TAG,
                "reader_activity_numeric_initial_stream_skip_protected_api path=$path," +
                    "episode=$episodeToken"
            )
            return true
        }
        val imageWorkId = manga.ntkImageWorkId.trim().ifEmpty { workSlug }
        if (!ReaderImageCache.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                manga.ntkImageCount.coerceAtLeast(1),
                SystemClock.elapsedRealtime() - 30_000L
            )
        ) {
            if (
                numericWebtoon &&
                imageWorkId.matches(Regex("\\d{1,12}")) &&
                imageWorkId != workSlug &&
                episodeToken.matches(Regex("\\d{1,12}"))
            ) {
                val first = "http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$episodeToken/p001.jpeg"
                ReaderImageCache.rememberEarlyNtkImageUrls(path, arrayListOf(first))
                val streamStarted = ReaderImageCache.startForegroundStreamFetch(
                    applicationContext,
                    manga,
                    first,
                    null,
                    false,
                    null,
                    0,
                    true
                )
                Log.d(
                    TAG,
                    "reader_activity_numeric_webtoon_direct_first_probe_start path=$path," +
                        "workId=$imageWorkId,episode=$episodeToken,started=$streamStarted," +
                        "first=${first.substringAfterLast('/')}"
                )
            }
            startStrictNtkDiscovery(manga, "retired_unvalidated_probe")
            Log.d(
                TAG,
                "reader_activity_numeric_webtoon_initial_stream_skip_unvalidated_direct path=$path," +
                    "count=${manga.ntkImageCount},episode=$episodeToken"
            )
            return true
        }
        val generatedExtension = ntkInitialGeneratedExtensionForPath("webtoon", workSlug, imageWorkId)
        if (numericWebtoon && !imageWorkId.matches(Regex("\\d{1,12}"))) return false
        val knownCount = manga.ntkImageCount
        val cachedIdentity = CustomHttpClient.cachedNtkImageIdentity(path)
        if (
            knownCount > 0 &&
            ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(
                path,
                knownCount,
                SystemClock.elapsedRealtime() - 30_000L
            )
        ) {
            Log.d(
                TAG,
                "reader_activity_known_generated_probe_skip_complete_direct_manifest " +
                    "path=$path,count=$knownCount"
            )
            return true
        }
        if (
            launchPreflightStarted &&
            knownCount > 0 &&
            ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(
                path,
                knownCount,
                SystemClock.elapsedRealtime() - 30_000L
            )
        ) {
            Log.d(
                TAG,
                "reader_activity_initial_prime_skip_preflight_ready path=$path,count=$knownCount"
            )
            return true
        }
        if (
            numericWebtoon &&
            knownCount > 0 &&
            imageWorkId == workSlug &&
            (shouldPreferNtkApiForCanonicalWebtoonPath(workSlug, episodeToken) ||
                cachedIdentity == null ||
                cachedIdentity.workId != imageWorkId ||
                cachedIdentity.episodeId != episodeToken)
        ) {
            if (
                cachedIdentity != null &&
                cachedIdentity.workId.matches(Regex("\\d{1,12}")) &&
                cachedIdentity.episodeId == episodeToken &&
                cachedIdentity.count > 0
            ) {
                val count = minOf(cachedIdentity.count, 128)
                val urls = ArrayList<String>(count)
                for (page in 1..count) {
                    urls.add(
                        "https://fifa.worldcup73.xyz/black/episodes/${cachedIdentity.workId}/" +
                            "${cachedIdentity.episodeId}/p%03d.%s".format(Locale.ROOT, page, generatedExtension)
                    )
                }
                ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
                Log.d(
                    TAG,
                    "reader_activity_cached_identity_initial_prime path=$path," +
                        "workId=${cachedIdentity.workId},episodeId=${cachedIdentity.episodeId}," +
                        "count=${urls.size},streamOwner=session"
                )
                return true
            }
            startStrictNtkDiscovery(manga, "retired_canonical_probe")
            Log.d(
                TAG,
                "reader_activity_known_generated_probe_defer_canonical_api_first " +
                    "path=$path,count=$knownCount,workId=$imageWorkId,episodeId=$episodeToken"
            )
            return true
        }
        if (numericWebtoon && knownCount > 0 && imageWorkId.matches(Regex("\\d{1,12}"))) {
            val urls = ArrayList<String>(knownCount)
            for (page in 1..knownCount) {
                urls.add(
                    "http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$episodeToken/" +
                        "p%03d.%s".format(Locale.ROOT, page, generatedExtension)
                )
            }
            ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
            val startIndex = currentPage.coerceIn(0, urls.lastIndex)
            val first = urls[startIndex]
            Log.d(
                TAG,
                "reader_activity_known_generated_probe_start path=$path,count=$knownCount," +
                    "workId=$imageWorkId,episodeId=$episodeToken,page=$startIndex,streamStarted=false,streamOwner=session," +
                    "first=${first.substringAfterLast('/')}"
            )
            return true
        }
        val primePages = ntkActivityInitialContinuousPrimePages(path)
        val launchCount = when {
            knownCount in 1 until primePages -> knownCount
            else -> primePages
        }
        val totalCount = knownCount.takeIf { it > 0 } ?: launchCount
        val urls = ArrayList<String>(totalCount)
        val initialExtension = if (numericWebtoon) generatedExtension else "jpg"
        for (page in 1..totalCount) {
            val pageName = "p%03d.%s".format(Locale.ROOT, page, initialExtension)
            val url = if (numericWebtoon) {
                "http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$episodeToken/$pageName"
            } else {
                "https://fifa.worldcup73.xyz/wt/episodes/$workSlug/$episodeToken/$pageName"
            }
            urls.add(url)
        }
        if (urls.size > 1) ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
        val startIndex = currentPage.coerceIn(0, urls.lastIndex)
        val first = urls.getOrNull(startIndex) ?: return false
        Log.d(
            TAG,
            "reader_activity_slug_initial_stream_start path=$path,count=${urls.size}," +
                "numeric=$numericWebtoon,started=false,streamOwner=session,first=${first.substringAfterLast('/')}"
        )
        return true
    }

    private fun ntkInitialGeneratedExtensionForPath(segment: String, pathWorkId: String, imageWorkId: String): String {
        if (!segment.equals("webtoon", ignoreCase = true)) return "jpg"
        val numeric = Regex("\\d{1,12}")
        return if (
            pathWorkId.matches(numeric) &&
            imageWorkId.matches(numeric) &&
            !pathWorkId.equals(imageWorkId, ignoreCase = true)
        ) {
            "jpg"
        } else {
            "jpeg"
        }
    }

    private fun scheduleInitialNtkApiPrefetchAfterDrawable(manga: Manga, skip: Boolean) {
        if (skip || !manga.isOnline) return
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank() || (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))) return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "legacy_post_drawable_prefetch_reroute")
            return
        }
        if (hasCompleteNativeDirectManifest(manga)) {
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_skip reason=direct_manifest,path=$path")
            return
        }
        if (hasCompleteEarlyGeneratedUrls(manga, path)) {
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_skip reason=early_generated_urls_ready,path=$path")
            return
        }
        if (isNtkKpWebtoonSlugPath(path)) {
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_skip reason=kp_launch_owned_by_initial_discovery,path=$path")
            return
        }
        val scheduledAt = SystemClock.elapsedRealtime()
        val prefetch = object : Runnable {
            override fun run() {
                val activePath = currentManga?.ntkEpisodePath?.trim().orEmpty()
                if (destroyed || isFinishing || (activePath.isNotBlank() && activePath != path)) return
                val elapsed = SystemClock.elapsedRealtime() - scheduledAt
                if (shouldDeferInitialNtkApiPrefetch(manga, elapsed)) {
                    if (::renderView.isInitialized) renderView.requestVisibleCoverageFrame()
                    statusHandler.postDelayed(this, NTK_INITIAL_API_PREFETCH_DEFER_MS)
                    return
                }
                try {
                    startStrictNtkDiscovery(manga, "retired_initial_prefetch")
                    Log.d(
                        TAG,
                        "reader_ntk_early_viewer_api_prefetch_start reason=initial_reader," +
                            "elapsedMs=$elapsed,path=$path"
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_error path=$path,$e")
                }
            }
        }
        if (isSlugWebtoonNtkPath(path)) {
            statusHandler.post(prefetch)
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_immediate path=$path")
        } else {
            statusHandler.postDelayed(prefetch, NTK_INITIAL_API_PREFETCH_DEFER_MS)
            Log.d(TAG, "reader_ntk_early_viewer_api_prefetch_deferred path=$path")
        }
    }

    private fun requestInitialVisibleWindow(count: Int) {
        if (count <= 0) return
        if (isCurrentNtkReader() && !firstDrawableMetricLogged && !initialStartAtFirstPage) {
            Log.d(TAG, "reader_initial_visible_window_skip_ntk_anchor_pending count=$count current=$currentPage")
            return
        }
        val anchor = currentPage.coerceIn(0, count - 1)
        val ahead = if (isCurrentNtkReader()) {
            maxOf(2, INITIAL_GENERATED_RUNWAY_PAGES - 1)
        } else {
            2
        }
        val last = minOf(count - 1, anchor + ahead)
        session?.requestWindowAsync(anchor, last, anchor, true)
        Log.d(TAG, "reader_initial_visible_window_request first=$anchor last=$last anchor=$anchor count=$count")
    }

    private fun startInitialNtkImageDiscovery(
        manga: Manga,
        launchPreflightStarted: Boolean
    ) {
        if (!manga.isOnline) return
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (isStrictNtkEpisodePath(path)) {
            ntkInitialDiscoveryPath = path
            startStrictNtkDiscovery(manga, "activity_initial_discovery")
        } else {
            Log.d(
                TAG,
                "reader_ntk_initial_discovery_retired_non_strict path=$path," +
                    "launchPreflight=$launchPreflightStarted"
            )
        }
    }
    private fun shouldDeferInitialNtkApiPrefetch(manga: Manga, elapsedMs: Long): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank() || (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))) {
            return false
        }
        if (isSlugWebtoonNtkPath(path)) {
            return false
        }
        val knownInitialRunway = hasKnownInitialGeneratedRunway(manga, path)
        if (
            knownInitialRunway &&
            afterFirstDrawableFollowupQuietRemainingMs() > 0L &&
            elapsedMs < NTK_INITIAL_API_PREFETCH_ACTIVE_INPUT_MAX_DEFER_MS
        ) {
            return true
        }
        if (knownInitialRunway && !isInitialContinuousScrollReady()) {
            return elapsedMs < NTK_INITIAL_API_PREFETCH_CONTINUOUS_MAX_DEFER_MS
        }
        return !firstDrawableMetricLogged && elapsedMs < NTK_INITIAL_API_PREFETCH_MAX_DEFER_MS
    }

    private fun hasKnownInitialGeneratedRunway(manga: Manga, path: String): Boolean {
        val count = manga.ntkImageCount
        val workId = manga.ntkImageWorkId?.trim().orEmpty()
        val imageEpisodeId = manga.ntkImageEpisodeId?.trim().orEmpty()
        val minCreatedAt = SystemClock.elapsedRealtime() - 30000L
        fun hasTrustedRunway(required: Int): Boolean {
            if (required <= 0) return false
            return try {
                ReaderImageCache.trustedNtkImageApiCount(path, minCreatedAt) >= required ||
                    ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(path, required, minCreatedAt)
            } catch (_: Exception) {
                false
            }
        }
        if (count >= INITIAL_GENERATED_RUNWAY_PAGES &&
            workId.matches(Regex("\\d{1,12}")) &&
            imageEpisodeId.matches(Regex("\\d{1,12}"))
        ) {
            val required = minOf(INITIAL_GENERATED_RUNWAY_PAGES, count)
            if (hasTrustedRunway(required)) return true
            Log.d(
                TAG,
                "reader_ntk_known_generated_runway_untrusted path=$path," +
                    "count=$count,required=$required"
            )
        }
        val earlyCount = try {
            ReaderImageCache.earlyNtkImageUrls(path, minCreatedAt)
                .take(INITIAL_GENERATED_RUNWAY_PAGES)
                .size
        } catch (_: Exception) {
            0
        }
        val required = minOf(
            INITIAL_GENERATED_RUNWAY_PAGES,
            count.takeIf { it > 0 } ?: INITIAL_GENERATED_RUNWAY_PAGES
        )
        if (earlyCount < required) return false
        if (hasTrustedRunway(required)) return true
        Log.d(
            TAG,
            "reader_ntk_known_generated_runway_early_untrusted path=$path," +
                "early=$earlyCount,required=$required"
        )
        return false
    }

    private fun hasCompleteEarlyGeneratedUrls(manga: Manga, path: String): Boolean {
        val expected = manga.ntkImageCount
        if (expected <= 0) return false
        if (manga.ntkImageWorkId?.trim().orEmpty().matches(Regex("\\d{1,12}")).not()) return false
        if (manga.ntkImageEpisodeId?.trim().orEmpty().matches(Regex("\\d{1,12}")).not()) return false
        return try {
            ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(
                path,
                expected,
                SystemClock.elapsedRealtime() - 30000L
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun hasForegroundNativeDirectUrls(manga: Manga, path: String): Boolean {
        if (!ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path)) return false
        val urls = try {
            ReaderImageCache.earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
        } catch (_: Exception) {
            emptyList()
        }
        if (urls.isEmpty()) return false
        val expected = manga.ntkImageCount
        if (expected <= 0 || urls.size < expected) return false
        val required = expected
        if (required <= 0) return false
        if (!ReaderImageCache.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                required,
                SystemClock.elapsedRealtime() - 30000L
            )
        ) {
            Log.d(
                TAG,
                "reader_ntk_foreground_direct_untrusted path=$path," +
                    "expected=$required,early=${urls.size}"
            )
            return false
        }
        return urls.take(required).none { it.contains("/api/m/i?", ignoreCase = true) }
    }

    private fun hasForegroundDirectManifestOwnership(manga: Manga, path: String): Boolean {
        if (!ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path)) return false
        return hasForegroundNativeDirectUrls(manga, path)
    }

    private fun isInitialContinuousScrollReady(): Boolean {
        if (pageCount <= 0) return launchDrawableMetricPages.isNotEmpty()
        val readyAhead = initialReadyAheadPages()
        val firstRequired = if (!firstDrawableMetricLogged && initialStartAtFirstPage) 0 else currentPage
        val lastRequired = minOf(pageCount - 1, firstRequired + readyAhead)
        for (page in firstRequired..lastRequired) {
            if (!launchDrawableMetricPages.contains(page)) return false
        }
        return true
    }

    private fun shouldTrackLaunchDrawableMetricPage(index: Int): Boolean {
        if (viewerLaunchStartedAtMs <= 0L) return false
        if (isCurrentNtkReader() && firstDrawableMetricLogged && drawableReadyDescriptionPosted) return false
        if (pageCount <= 0) return index >= 0
        val first = if (!firstDrawableMetricLogged && initialStartAtFirstPage) 0 else currentPage
        val behind = if (isCurrentNtkReader()) NTK_INITIAL_METRIC_BEHIND_PAGES else 0
        val firstTracked = maxOf(0, first - behind)
        val lastTracked = minOf(pageCount - 1, first + initialReadyAheadPages())
        return index in firstTracked..lastTracked
    }

    private fun initialReadyAheadPages(): Int {
        return if (currentManga?.baseMode == MTitle.base_webtoon) {
            INITIAL_READY_WEBTOON_AHEAD_PAGES
        } else {
            INITIAL_READY_MANHWA_AHEAD_PAGES
        }
    }

    private fun initialContinuousPreRenderAheadPages(): Int {
        if (!isCurrentNtkReader()) return initialReadyAheadPages()
        return maxOf(
            initialReadyAheadPages(),
            ntkActivityInitialContinuousPrimePages() - 1
        )
    }

    private fun ntkActivityInitialContinuousPrimePages(
        path: String = currentManga?.ntkEpisodePath?.trim().orEmpty()
    ): Int {
        return if (path.startsWith("/webtoon/") || currentManga?.baseMode == MTitle.base_webtoon) {
            NTK_ACTIVITY_INITIAL_CONTINUOUS_WEBTOON_PRIME_PAGES
        } else {
            NTK_ACTIVITY_INITIAL_CONTINUOUS_MANHWA_PRIME_PAGES
        }
    }

    private fun shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable: Boolean): Boolean {
        if (!visibleInitialDrawable || !isCurrentNtkReader()) return false
        return false
    }

    private fun maybeReleaseInitialNtkContinuousGate(reason: String) {
        if (!isCurrentNtkReader()) return
        if (!isInitialContinuousScrollReady()) return
        if (!firstDrawableMetricLogged) {
            val firstDrawablePage = if (initialStartAtFirstPage) 0 else currentPage
            logFirstDrawableMetric(firstDrawablePage, reason)
        }
        releaseInitialDrawGate(reason)
    }

    private fun maybeReleaseInitialNtkContinuousGateOrViewport(reason: String) {
        if (!isCurrentNtkReader()) return
        if (isVisibleViewportReady()) {
            if (!firstDrawableMetricLogged) logVisibleViewportReadyMetric() else postDrawableReadyDescription()
            releaseInitialDrawGate("viewport")
            return
        }
        if (requiresInitialNtkWebtoonViewportReady()) return
        maybeReleaseInitialNtkContinuousGate(reason)
    }

    private fun scheduleNtkDrawableReadyPolling() {
        if (!isCurrentNtkReader() || firstDrawableMetricLogged || drawableReadyDescriptionPosted) return
        renderView.requestVisibleCoverageFrame()
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        statusHandler.postDelayed(drawableReadyDescriptionRunnable, DRAWABLE_READY_CHECK_INTERVAL_MS)
    }

    private fun requiresInitialNtkWebtoonViewportReady(): Boolean {
        val path = currentManga?.ntkEpisodePath
        return isCurrentNtkReader() &&
            !drawableReadyDescriptionPosted &&
            (currentManga?.baseMode == MTitle.base_webtoon ||
                path?.startsWith("/webtoon/", ignoreCase = true) == true ||
                path?.startsWith("/manhwa/", ignoreCase = true) == true)
    }

    private fun postDrawableReadyDescription() {
        if (drawableReadyDescriptionPosted) return
        drawableReadyDescriptionPosted = true
        if (hybridNtkBrowserActive) {
            hybridNtkWebView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            hybridNtkWebView?.contentDescription = READER_DRAWABLE_READY_DESCRIPTION
            return
        }
        renderView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderView.contentDescription = READER_DRAWABLE_READY_DESCRIPTION
    }

    private fun logVisibleViewportReadyMetric() {
        if (!firstDrawableMetricLogged && viewerLaunchStartedAtMs > 0L) {
            firstDrawableMetricLogged = true
            firstDrawableLoggedAtMs = SystemClock.uptimeMillis()
            firstDrawableLoggedElapsedAtMs = SystemClock.elapsedRealtime()
            val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
            if (firstDrawableElapsedMsForTest < 0L || elapsed < firstDrawableElapsedMsForTest) {
                firstDrawableElapsedMsForTest = elapsed
            }
            Log.d("ViewerPerf", "reader_open_to_first_drawable source=$viewerLaunchSourceSite kind=viewport page=$currentPage ms=$elapsed")
            if (shouldSkipPostDrawableNtkAckForDirectManifest("viewport_drawable")) {
                clearDeferredNtkAckPreflightAfterValidatedDirectManifest("viewport_drawable")
            } else {
                startImmediateNtkNativeAckAfterFirstDrawable("viewport_drawable")
                scheduleDeferredNtkAckAfterFirstDrawable("viewport_drawable")
            }
            maybeStartDeferredNtkAckAfterInitialContinuous()
        }
        postDrawableReadyDescription()
    }

    private fun logLaunchDrawableMetric(index: Int, kind: String) {
        if (viewerLaunchStartedAtMs <= 0L) return
        if (!shouldTrackLaunchDrawableMetricPage(index)) return
        val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
        if (kind == "error" && isCurrentNtkReader()) {
            Log.d("ViewerPerf", "reader_open_to_near_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
            return
        }
        if (!launchDrawableMetricPages.add(index)) return
        launchDrawableElapsedMsByPage[index] = elapsed
        Log.d("ViewerPerf", "reader_open_to_near_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
        maybeStartDeferredNtkAckAfterInitialContinuous()
        maybeReleaseInitialNtkContinuousGate("initial_continuous")
    }

    private fun logInitialContinuousPrerenderMetricFromAnyThread(index: Int, kind: String) {
        val logged = synchronized(this) {
            if (viewerLaunchStartedAtMs <= 0L) return@synchronized false
            if (!shouldTrackLaunchDrawableMetricPage(index)) return@synchronized false
            if (!launchDrawableMetricPages.add(index)) return@synchronized false
            val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
            launchDrawableElapsedMsByPage[index] = elapsed
            Log.d("ViewerPerf", "reader_open_to_near_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
            true
        }
        if (!logged) return
        statusHandler.post {
            maybeStartDeferredNtkAckAfterInitialContinuous()
            maybeReleaseInitialNtkContinuousGate("initial_continuous")
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous_prerender")
        }
    }

    private fun maybePostInitialContinuousDrawableMetric(index: Int, kind: String): Boolean {
        if (!isCurrentNtkReader()) return false
        if (!firstDrawableMetricLogged) return false
        if (viewerLaunchStartedAtMs <= 0L) return false
        if (!shouldTrackLaunchDrawableMetricPage(index)) return false
        if (launchDrawableMetricPages.contains(index)) return true
        logLaunchDrawableMetric(index, kind)
        return true
    }

    private fun maybeStartDeferredNtkAckAfterInitialContinuous() {
        if (deferredNtkAckPreflightManga == null) return
        if (!isInitialContinuousScrollReady()) return
        if (!firstDrawableMetricLogged) {
            Log.d(TAG, "reader_ntk_ack_preflight_wait_first_drawable reason=initial_continuous,path=${deferredNtkAckPreflightManga?.ntkEpisodePath}")
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.postDelayed(
                deferredNtkAckPreflightQuietRunnable,
                NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS
            )
            return
        }
        val quietMs = ntkAckPreflightQuietRemainingMs()
        if (quietMs > 0L) {
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, quietMs)
            return
        }
        startDeferredNtkAckPreflight("initial_continuous")
    }

    private fun scheduleDeferredNtkAckAfterFirstDrawable(reason: String) {
        if (deferredNtkAckPreflightManga == null) return
        if (shouldSkipPostDrawableNtkAckForDirectManifest(reason)) {
            clearDeferredNtkAckPreflightAfterValidatedDirectManifest(reason)
            return
        }
        val quietMs = ntkAckPreflightQuietRemainingMs()
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        if (quietMs > 0L) {
            statusHandler.postDelayed(deferredNtkAckPreflightBlockProbeRunnable, quietMs)
            return
        }
        maybeStartDeferredNtkAckForInitialBlock(currentManga, reason)
    }

    private fun shouldSkipPostDrawableNtkAckForDirectManifest(reason: String): Boolean {
        if (!isCurrentNtkReader() || hybridNtkBrowserActive) return false
        val manga = currentManga ?: deferredNtkAckPreflightManga ?: return false
        val path = manga.ntkEpisodePath ?: return false
        if (isNtkKpWebtoonSlugPath(path)) {
            Log.d(TAG, "reader_ntk_ack_post_drawable_skip_kp_slug reason=$reason,path=$path")
            return true
        }
        if (hasStrictDirectManifestAckAuthority(path)) {
            Log.d(TAG, "reader_ntk_ack_post_drawable_skip_strict_direct reason=$reason,path=$path")
            return true
        }
        if (!hasCompleteNativeDirectManifest(manga) && !hasForegroundDirectManifestOwnership(manga, path)) return false
        Log.d(TAG, "reader_ntk_ack_post_drawable_skip_direct_manifest reason=$reason,path=$path")
        return true
    }

    private fun rememberStrictDirectManifestAckAuthority(seal: StrictExactLaunchSeal) {
        // StrictExactLaunchSeal is already a production-claimable, ordered source proof. Compute
        // the direct-CDN property once while discovery owns the seal and before the full Bitmap
        // wave starts. Re-reading ReaderImageCache from the first physical gesture made a
        // 200-asset proof overlap NativeAlloc GC and withheld Surface submissions for 149 ms.
        strictDirectManifestAckSkipPath =
            if (seal.canonicalAssets.none { it.contains("/api/m/i?", ignoreCase = true) }) {
                seal.normalizedEpisodePath
            } else {
                ""
            }
    }

    private fun hasStrictDirectManifestAckAuthority(path: String): Boolean {
        val normalized = NtkStripDigests.normalizeEpisodePath(path)
        return normalized.isNotEmpty() && strictDirectManifestAckSkipPath == normalized
    }

    private fun clearDeferredNtkAckPreflightAfterValidatedDirectManifest(reason: String) {
        val manga = currentManga ?: deferredNtkAckPreflightManga ?: return
        val path = manga.ntkEpisodePath ?: return
        // Every caller reaches this method only after
        // shouldSkipPostDrawableNtkAckForDirectManifest() returned true in the same main-loop
        // turn. Re-scanning a 100-300 item direct manifest here made the first forward gesture
        // repeat the complete URL proof under peak Bitmap pressure. The second scan accounted for
        // a measured 207 ms uninterrupted main-thread slice on a 200-page cold episode.
        //
        // Clearing the owner is also enough to invalidate the two delayed callbacks: both
        // callbacks enter through methods whose first operation reads this nullable owner and
        // returns when it is absent. Handler.removeCallbacks() linearly scans the main MessageQueue,
        // so doing two scans in this hot turn only moves unrelated image-install messages around.
        if (deferredNtkAckPreflightManga?.ntkEpisodePath == path) {
            deferredNtkAckPreflightManga = null
        }
        Log.d(TAG, "reader_ntk_ack_deferred_clear_direct_manifest reason=$reason,path=$path")
    }
    private fun startImmediateNtkNativeAckAfterFirstDrawable(reason: String) {
        val manga = currentManga ?: deferredNtkAckPreflightManga ?: return
        val path = manga.ntkEpisodePath ?: return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_immediate_ack_$reason")
        } else {
            Log.d(TAG, "reader_ntk_immediate_ack_retired reason=$reason,path=$path")
        }
    }
    private fun ntkAckPreflightQuietRemainingMs(): Long {
        val now = SystemClock.uptimeMillis()
        var remaining = 0L
        if (firstDrawableLoggedAtMs > 0L) {
            remaining = maxOf(
                remaining,
                firstDrawableLoggedAtMs + ntkAckPreflightAfterFirstDrawableQuietMs() - now
            )
            if (isCurrentNtkAckPath() && lastReaderInteractionMs <= firstDrawableLoggedAtMs) {
                remaining = maxOf(
                    remaining,
                    firstDrawableLoggedAtMs + NTK_ACK_PREFLIGHT_INITIAL_NO_INTERACTION_QUIET_MS - now
                )
            }
        } else if (firstDrawableMetricLogged && isCurrentNtkAckPath()) {
            remaining = maxOf(remaining, ntkAckPreflightAfterFirstDrawableQuietMs())
        }
        if (readerWindowBusy) {
            val busyAgeMs = if (lastReaderBusyMs > 0L) now - lastReaderBusyMs else 0L
            if (lastReaderBusyMs <= 0L || busyAgeMs < NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS) {
                remaining = maxOf(remaining, NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS - busyAgeMs.coerceAtLeast(0L))
            } else {
                readerWindowBusy = false
                Log.d(TAG, "reader_ntk_ack_preflight_stale_busy_released ageMs=$busyAgeMs,path=${currentManga?.ntkEpisodePath ?: deferredNtkAckPreflightManga?.ntkEpisodePath}")
            }
        }
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs)
        if (lastActiveMs > 0L) {
            remaining = maxOf(
                remaining,
                lastActiveMs + NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS - now
            )
        }
        if (::renderView.isInitialized && isCurrentNtkAckPath()) {
            val programmaticRemaining = renderView.programmaticScrollActiveRemainingMs(now)
            if (programmaticRemaining > 0L) {
                remaining = maxOf(remaining, programmaticRemaining + NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS)
            }
        }
        return remaining.coerceAtLeast(0L)
    }

    private fun ntkAckStrictAfterFirstDrawableFloorRemainingMs(manga: Manga?): Long {
        val path = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath ?: return 0L
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return 0L
        if (!firstDrawableMetricLogged) return 0L
        val loggedAt = firstDrawableLoggedElapsedAtMs
        if (loggedAt <= 0L) return NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS_STRICT_FRESH
        return (loggedAt + NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS_STRICT_FRESH - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
    }

    private fun ntkAckPreflightAfterFirstDrawableQuietMs(): Long {
        return if (isCurrentNtkAckPath()) {
            NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS_STRICT_FRESH
        } else {
            NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS
        }
    }

    private fun isCurrentNtkAckPath(): Boolean {
        val path = currentManga?.ntkEpisodePath ?: deferredNtkAckPreflightManga?.ntkEpisodePath
        return path?.startsWith("/webtoon/") == true || path?.startsWith("/manhwa/") == true
    }

    private fun shouldMarkFirstDrawable(index: Int, currentPage: Int): Boolean {
        if (!firstDrawableMetricLogged && initialStartAtFirstPage && index == 0) return true
        return shouldMarkFirstDrawableForTest(index, currentPage)
    }

    override fun onMessage(message: String) {
        pendingBoundaryStatus = false
        initialStatusPending = false
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        status.visibility = TextView.VISIBLE
        status.text = message
        releaseInitialDrawGate("message")
    }

    override fun onCaptchaRequired(manga: Manga) {
        if (shouldSuppressNtkCaptchaPopupAfterPagesReady(manga, "session")) {
            return
        }
        if (retryInitialNtkAfterAccessProof(manga)) {
            return
        }
        if (shouldDeferInitialNtkCaptcha(manga)) {
            val path = manga.ntkEpisodePath ?: currentManga?.ntkEpisodePath
            pendingInitialNtkCaptchaDeferrals++
            Log.d(TAG, "reader_ntk_captcha_deferred_before_first_drawable path=$path,attempt=$pendingInitialNtkCaptchaDeferrals")
            maybeStartDeferredNtkAckForInitialBlock(manga, "initial_captcha_before_first_drawable")
            statusHandler.postDelayed({
                if (!destroyed && !isFinishing && !firstDrawableMetricLogged) {
                    onCaptchaRequired(manga)
                }
            }, NTK_INITIAL_CAPTCHA_DEFER_MS)
            return
        }
        blockingStatusForTest = "captcha"
        pendingCaptchaRetryManga = manga
        pendingCaptchaRetryTitle = manga.title ?: currentTitle
        pendingCaptchaRetryStartAtFirstPage = manga !== currentManga
        pendingCaptchaRetryAction = if (pendingBoundaryCaptchaRetry && pendingCaptchaRetryAnchor >= 0 && pendingCaptchaRetryDirection != 0) {
            CAPTCHA_RETRY_BOUNDARY
        } else {
            CAPTCHA_RETRY_READER
        }
        pendingBoundaryStatus = false
        pendingBoundaryCaptchaRetry = false
        initialStatusPending = false
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        status.visibility = TextView.VISIBLE
        status.text = "캡차 확인이 필요합니다"
        releaseInitialDrawGate("captcha")
        Utils.showCaptchaPopup(Manga.safeUrl(manga), this, REQUEST_CAPTCHA, p)
    }

    override fun onBoundaryAppendFinished(anchor: Int, direction: Int, silent: Boolean, suppressedCaptcha: Boolean) {
        if (suppressedCaptcha) {
            finishSuppressedBoundaryCaptcha("boundary_finished")
            return
        }
        val samePendingDirection = pendingCaptchaRetryDirection == direction
        if (samePendingDirection || pendingBoundaryStatus) {
            val retryBoundaryAfterSilent = silent && suppressedCaptcha && pendingBoundaryStatus && pendingBoundaryCaptchaRetry && samePendingDirection
            clearPendingBoundaryCaptchaRetry()
            if (retryBoundaryAfterSilent && !destroyed && !isFinishing) {
                pendingBoundaryCaptchaRetry = true
                pendingCaptchaRetryDirection = direction
                pendingCaptchaRetryAnchor = anchor
                val retryStart = session?.appendAdjacentEpisode(anchor, direction, skipStartDelay = true)
                markPrependRevealRequest(direction, retryStart)
                if (retryStart != ReaderSession.AppendStartResult.STARTED && retryStart != ReaderSession.AppendStartResult.BUSY) {
                    clearPendingBoundaryCaptchaRetry()
                }
                return
            }
            hideBoundaryStatus()
            renderView.finishBoundaryDispatch()
        }
    }

    override fun onWindowChanged(
        firstPage: Int,
        lastPage: Int,
        anchorPage: Int,
        progressPage: Int,
        progressOffset: Int,
        busy: Boolean
    ) {
        MainThreadStallMonitor.trace("reader_on_window_changed") {
            val now = SystemClock.uptimeMillis()
            val activeSession = session
            val wasReaderWindowBusy = readerWindowBusy
            if (busy && !wasReaderWindowBusy) {
                activeSession?.noteUserInteraction()
            }
            val suppressActiveNtkWindowLog = isCurrentNtkReader() &&
                (busy || now - lastReaderInteractionMs <= NTK_ACTIVE_SCROLL_LOG_QUIET_MS)
            if (!suppressActiveNtkWindowLog &&
                (!busy || busy != lastLoggedWindowBusy || now - lastBusyWindowLogMs >= BUSY_WINDOW_LOG_INTERVAL_MS)
            ) {
                lastBusyWindowLogMs = now
                lastLoggedWindowBusy = busy
                Log.d(
                    TAG,
                    "window_changed first=$firstPage last=$lastPage anchor=$anchorPage " +
                        "progress=$progressPage offset=$progressOffset busy=$busy current=$currentPage"
                )
            }
            if (
                isCurrentNtkReader() &&
                !firstDrawableMetricLogged &&
                !initialStartAtFirstPage &&
                currentPage >= 4 &&
                progressPage < 4 &&
                pageCount > currentPage
            ) {
                val anchor = currentPage.coerceIn(0, pageCount - 1)
                val last = (anchor + 2).coerceAtMost(pageCount - 1)
                Log.d(
                    TAG,
                    "window_changed_pre_first_jump_ignored progress=$progressPage," +
                        "current=$currentPage,anchor=$anchor,busy=$busy"
                )
                MainThreadStallMonitor.trace("reader_request_window_async") {
                    activeSession?.requestWindowAsync(anchor, last, anchor, busy)
                }
                return@trace
            }
            if (shouldIgnoreImpossibleTopProgress(progressPage, busy, now)) {
                val anchor = currentPage.coerceIn(0, pageCount - 1)
                val last = (anchor + 2).coerceAtMost(pageCount - 1)
                Log.d(
                    TAG,
                    "window_changed_impossible_top_ignored progress=$progressPage," +
                        "current=$currentPage,anchor=$anchor,busy=$busy"
                )
                MainThreadStallMonitor.trace("reader_request_window_async") {
                    activeSession?.requestWindowAsync(anchor, last, anchor, busy)
                }
                return@trace
            }
            val adjustedProgressPage = adjustedNtkPartialTailProgressPage(progressPage, pageCount)
            val adjustedWindow = adjustedProgressPage != progressPage
            if (adjustedWindow) {
                Log.d(
                    TAG,
                    "reader_ntk_partial_tail_window_clamp progress=$progressPage," +
                        "adjusted=$adjustedProgressPage,count=$pageCount,busy=$busy"
                )
            }
            val requestFirstPage = if (adjustedWindow) {
                minOf(firstPage, adjustedProgressPage)
            } else {
                firstPage
            }
            val requestLastPage = if (adjustedWindow) {
                maxOf(lastPage, minOf(pageCount - 1, adjustedProgressPage + NTK_ACK_INITIAL_CONTINUOUS_PAGES - 1))
            } else {
                lastPage
            }
            val requestAnchorPage = if (adjustedWindow) adjustedProgressPage else anchorPage
            val pageChanged = adjustedProgressPage != currentPage
            currentPage = adjustedProgressPage
            MainThreadStallMonitor.trace("reader_request_window_async") {
                activeSession?.requestWindowAsync(requestFirstPage, requestLastPage, requestAnchorPage, busy)
            }
            if (busy) {
                readerWindowBusy = true
                lastReaderBusyMs = now
                lastReaderInteractionMs = now
                if (!wasReaderWindowBusy && deferredBoundaryDirection != 0 && deferredBoundaryAnchor >= 0) {
                    statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
                    statusHandler.postDelayed(deferredBoundaryAppendRunnable, BOUNDARY_APPEND_QUIET_MS)
                }
                pendingAnchorAfterBusy = adjustedProgressPage
                return@trace
            }
            if (pageChanged) {
                lastReaderInteractionMs = now
                interactionMaintenancePending = true
            }
            readerWindowBusy = false
            flushDeferredReaderInteractionMaintenance(now)
            if (wasReaderWindowBusy && deferredBoundaryDirection != 0 && deferredBoundaryAnchor >= 0) {
                statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
                statusHandler.postDelayed(deferredBoundaryAppendRunnable, BOUNDARY_APPEND_QUIET_MS)
            }
            pendingAnchorAfterBusy = -1
            if (shouldDeferNtkEpisodeUpdate(pageChanged, now)) {
                scheduleDeferredEpisodeUpdate(adjustedProgressPage, progressOffset, saveProgress = true)
                return@trace
            }
            MainThreadStallMonitor.trace("reader_update_current_episode") {
                updateCurrentEpisode(adjustedProgressPage, progressOffset, saveProgress = true)
            }
        }
    }

    private fun recordTouchDeliveryForTest(ev: MotionEvent, deliveredAtMs: Long) {
        if (!touchDeliveryStatsArmed) return
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN &&
            action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL
        ) return

        fun recordSample(eventTimeMs: Long) {
            val lagMs = deliveredAtMs - eventTimeMs
            touchDeliverySamples++
            if (lagMs < 0L) {
                touchDeliveryInvalidEventTimes++
            } else {
                touchDeliveryMaxLagMs = maxOf(touchDeliveryMaxLagMs, lagMs)
                when (action) {
                    MotionEvent.ACTION_DOWN -> touchDeliveryDownMaxLagMs = maxOf(touchDeliveryDownMaxLagMs, lagMs)
                    MotionEvent.ACTION_MOVE -> touchDeliveryMoveMaxLagMs = maxOf(touchDeliveryMoveMaxLagMs, lagMs)
                    MotionEvent.ACTION_UP -> touchDeliveryUpMaxLagMs = maxOf(touchDeliveryUpMaxLagMs, lagMs)
                    MotionEvent.ACTION_CANCEL -> touchDeliveryCancelMaxLagMs = maxOf(touchDeliveryCancelMaxLagMs, lagMs)
                }
            }
        }
        for (index in 0 until ev.historySize) {
            recordSample(ev.getHistoricalEventTime(index))
        }
        recordSample(ev.eventTime)
        when (action) {
            MotionEvent.ACTION_DOWN -> touchDeliveryDownEvents++
            MotionEvent.ACTION_MOVE -> touchDeliveryMoveSamples += ev.historySize + 1
            MotionEvent.ACTION_UP -> touchDeliveryUpEvents++
            MotionEvent.ACTION_CANCEL -> touchDeliveryCancelEvents++
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deliveredAtMs = SystemClock.uptimeMillis()
        recordTouchDeliveryForTest(ev, deliveredAtMs)
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            progressSaveArmed = true
            progressMovedInGesture = true
            progressSavePending = true
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            progressMovedInGesture = false
            session?.notePhysicalTouch(false)
        } else if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            progressMovedInGesture = false
            // Mark product input before SurfaceView's coalesced window callback. This closes the
            // one-frame gap in which decoded runway delivery could still front-post ahead of the
            // physical DOWN event.
            session?.notePhysicalTouch(true)
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN ||
            ev.actionMasked == MotionEvent.ACTION_MOVE ||
            ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            lastReaderInteractionMs = deliveredAtMs
            interactionMaintenancePending = true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun flushDeferredReaderInteractionMaintenance(now: Long) {
        if (!interactionMaintenancePending || readerWindowBusy || progressMovedInGesture) return
        interactionMaintenancePending = false
        session?.noteUserInteraction()
        lastInteractionNoteMs = now
        MainApplication.noteNtkForegroundViewerInput(currentManga?.ntkEpisodePath)
        quietNtkWebViewFallbackForNativeReader("post_scroll", now)
        lastAckQuietExtendMs = now
        if (progressSavePending) {
            progressSavePending = false
            progressHandler.removeCallbacks(saveProgressRunnable)
            progressHandler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS)
        }
    }

    private fun shouldIgnoreImpossibleTopProgress(
        progressPage: Int,
        busy: Boolean,
        now: Long
    ): Boolean {
        if (!isCurrentNtkReader()) return false
        if (currentPage < 4 || progressPage > 1) return false
        if (pageCount <= currentPage) return false
        val snapshot = renderView.currentScrollPositionSnapshot() ?: return false
        val deepScroll = snapshot.scrollOffset > readerHeightPx() * 2
        val activeOrRecent = busy ||
            progressMovedInGesture ||
            now - lastReaderInteractionMs in 0..NTK_EPISODE_UPDATE_SCROLL_QUIET_MS
        return deepScroll && activeOrRecent
    }

    private fun shouldDeferNtkEpisodeUpdate(pageChanged: Boolean, now: Long): Boolean {
        if (!isCurrentNtkReader() || !pageChanged) return false
        val sinceInteractionMs = now - lastReaderInteractionMs
        return progressMovedInGesture || sinceInteractionMs in 0..NTK_EPISODE_UPDATE_SCROLL_QUIET_MS
    }

    private fun scheduleDeferredEpisodeUpdate(page: Int, offset: Int, saveProgress: Boolean) {
        deferredEpisodeUpdatePage = page
        deferredEpisodeUpdateOffset = offset
        deferredEpisodeUpdateSaveProgress = deferredEpisodeUpdateSaveProgress || saveProgress
        statusHandler.removeCallbacks(deferredEpisodeUpdateRunnable)
        statusHandler.postDelayed(deferredEpisodeUpdateRunnable, NTK_EPISODE_UPDATE_SCROLL_QUIET_MS)
    }

    private fun flushDeferredEpisodeUpdate() {
        val page = deferredEpisodeUpdatePage
        if (page < 0 || destroyed || isFinishing) return
        val offset = deferredEpisodeUpdateOffset
        val saveProgress = deferredEpisodeUpdateSaveProgress
        deferredEpisodeUpdatePage = -1
        deferredEpisodeUpdateOffset = 0
        deferredEpisodeUpdateSaveProgress = false
        MainThreadStallMonitor.trace("reader_update_current_episode_deferred") {
            updateCurrentEpisode(page, offset, saveProgress)
        }
    }

    override fun onNearBoundary(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        if (!shouldPrepareNearBoundaryForTest(direction)) return
        // ReaderSurfaceView emits the near-edge transition once. Dropping it while the gesture is
        // busy means there is no second callback after the finger lifts, so the next episode can
        // never be prepared. ReaderSession owns the bounded input-quiet deferral and revalidates
        // that the reader is still near the same edge before it publishes anything.
        val prepareAnchor = nearBoundaryPrepareAnchor(direction, anchorPage) ?: return
        session?.prepareAdjacentEpisode(prepareAnchor, direction)
    }

    private fun nearBoundaryPrepareAnchor(direction: Int, anchorPage: Int): Int? {
        if (
            isCurrentNtkReader() &&
            direction == ReaderSurfaceView.DIRECTION_NEXT
        ) {
            if (pageCount <= 0) return null
            val tail = pageCount - 1
            val bounded = anchorPage.coerceIn(0, tail)
            if (bounded != tail) {
                Log.d(
                    TAG,
                    "boundary_prepare_tail_anchor direction=$direction anchor=$anchorPage " +
                        "bounded=$bounded tail=$tail"
                )
            }
            return tail
        }
        return anchorPage
    }

    override fun onBoundaryReached(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        Log.d(TAG, "boundary_reached direction=$direction anchorPage=$anchorPage")
        // A strict cold session is intentionally sealed to one authoritative episode.  Reaching
        // its physical tail is therefore a stable reader edge even if the best-effort adjacent
        // episode lookup is unavailable (for example, because the origin asks for a captcha).
        // Republish the edge from the last identity-valid, defect-free committed frame before
        // touching adjacent discovery so neither an async status update nor repeated flings can
        // hide the already displayed last pixels from accessibility/qualification observers.
        publishStrictCompletedEpisodeBottomEdge(direction, anchorPage)
        session?.pageInfo(anchorPage)?.let {
            if (!it.transitionCard) currentManga = it.manga
        }
        startBoundaryAppend(direction, anchorPage)
    }

    private fun publishStrictCompletedEpisodeBottomEdge(direction: Int, anchorPage: Int) {
        if (direction != ReaderSurfaceView.DIRECTION_NEXT || !strictTelemetryOwned) return
        val seal = strictExactLaunchSeal
        // ReaderSurfaceView emits this callback only at its clamped physical maximum. Keep the
        // marker tied to that real coordinate; the qualification separately requires canonical
        // all-ready, identity-valid commits, and zero viewport defects before it can pass.
        if (strictTelemetryLastCleanDisplayPage < pageCount - 1) {
            // A fast physical fling can clamp the model at the final coordinate before the native
            // bottom frame has been submitted. Repeated edge gestures do not move the coordinate
            // and therefore used to produce no further frame, leaving the last canonical page
            // permanently unqualified even though it was render-ready. Request exactly that real
            // bottom frame; the next callback still has to pass the normal coverage and identity
            // checks above before this method can publish the edge.
            if (strictTelemetryBottomCommitWaitLogs < 3) {
                strictTelemetryBottomCommitWaitLogs++
                val position = renderView.currentScrollPositionSnapshot()
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_strict_bottom_commit_wait anchor=$anchorPage," +
                        "display=$strictTelemetryLastCleanDisplayPage," +
                        "source=$strictTelemetryLastCleanSourcePage,count=$pageCount," +
                        "scroll=${position?.scrollOffset ?: -1}," +
                        "max=${position?.maxScroll ?: -1}," +
                        renderView.renderPipelineDiagnosticSnapshot()
                )
            }
            renderView.requestCurrentPositionCommit()
            return
        }
        publishStrictViewerEdge(false, true)
        Log.d(
            "ViewerPerf",
            "reader_ntk_strict_completed_episode_bottom path=${seal?.normalizedEpisodePath.orEmpty()}," +
                "anchor=$anchorPage,display=$strictTelemetryLastCleanDisplayPage," +
                "source=$strictTelemetryLastCleanSourcePage,count=${seal?.pageCount ?: pageCount}"
        )
    }

    private fun publishStrictViewerEdge(atTop: Boolean, atBottom: Boolean) {
        terminalImageFailureDescription?.let { terminalDescription ->
            val publishTerminal = { view: View ->
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                view.contentDescription = terminalDescription
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            readerRoot?.let(publishTerminal)
            publishTerminal(renderView)
            return
        }
        // Publish on both stable accessibility nodes. Some emulator accessibility bridges cache
        // a SurfaceView child mutation while others coalesce the parent mutation; both values are
        // derived from the same identity-valid physical commit and affect no rendering behavior.
        val root = readerRoot
        if (root != null) {
            ViewerTelemetry.viewerEdge(root, atTop, atBottom)
        }
        ViewerTelemetry.viewerEdge(renderView, atTop, atBottom)
    }

    private fun flushDeferredBoundaryAppend() {
        val direction = deferredBoundaryDirection
        val anchor = deferredBoundaryAnchor
        if (direction == 0 || anchor < 0 || destroyed || isFinishing) return
        val remainingQuietMs = boundaryAppendQuietRemainingMs()
        if (remainingQuietMs > 0L) {
            statusHandler.postDelayed(deferredBoundaryAppendRunnable, remainingQuietMs)
            return
        }
        deferredBoundaryDirection = 0
        deferredBoundaryAnchor = -1
        startBoundaryAppend(direction, anchor)
    }

    private fun boundaryAppendQuietRemainingMs(): Long {
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs)
        if (lastActiveMs <= 0L) return 0L
        val quietForMs = SystemClock.uptimeMillis() - lastActiveMs
        return (BOUNDARY_APPEND_QUIET_MS - quietForMs).coerceAtLeast(0L)
    }

    private fun startBoundaryAppend(direction: Int, anchorPage: Int) {
        if (
            isCurrentNtkReader() &&
            direction == ReaderSurfaceView.DIRECTION_PREVIOUS
        ) {
            Log.d(TAG, "boundary_append_skip_ntk_previous_auto anchor=$anchorPage")
            finishSuppressedBoundaryCaptcha("ntk_previous_auto_disabled")
            return
        }
        if (
            isCurrentNtkReader() &&
            direction == ReaderSurfaceView.DIRECTION_NEXT &&
            hasUnpublishedNtkSessionTail()
        ) {
            deferredBoundaryDirection = direction
            deferredBoundaryAnchor = anchorPage
            statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
            statusHandler.postDelayed(deferredBoundaryAppendRunnable, NTK_ACTIVE_APPEND_CHUNK_RETRY_MS)
            Log.d(
                TAG,
                "boundary_append_wait_unpublished_tail direction=$direction anchor=$anchorPage " +
                    "pageCount=$pageCount"
            )
            return
        }
        if (isCurrentNtkReader() && !shouldStartNtkNextBoundaryImmediately(direction, anchorPage)) {
            val quietMs = boundaryAppendQuietRemainingMs()
            if (quietMs > 0L) {
                deferredBoundaryDirection = direction
                deferredBoundaryAnchor = anchorPage
                statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
                statusHandler.postDelayed(deferredBoundaryAppendRunnable, quietMs)
                Log.d(TAG, "boundary_append_deferred_quiet direction=$direction anchor=$anchorPage quietMs=$quietMs")
                return
            }
        }
        pendingBoundaryStatus = true
        pendingBoundaryCaptchaRetry = true
        pendingCaptchaRetryDirection = direction
        pendingCaptchaRetryAnchor = anchorPage
        pendingBoundaryStartInteractionMs = lastReaderInteractionMs
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.postDelayed(showBoundaryStatusRunnable, BOUNDARY_STATUS_DELAY_MS)
        val effectiveAnchor = if (direction == ReaderSurfaceView.DIRECTION_NEXT) {
            (pageCount - 1).coerceAtLeast(anchorPage.coerceAtLeast(0))
        } else {
            anchorPage
        }
        val startResult = session?.appendAdjacentEpisode(effectiveAnchor, direction, skipStartDelay = true)
        if (
            direction == ReaderSurfaceView.DIRECTION_NEXT &&
            (startResult == ReaderSession.AppendStartResult.STARTED ||
                startResult == ReaderSession.AppendStartResult.BUSY)
        ) {
            renderView.beginNextBoundaryAppendHold(effectiveAnchor)
        }
        markPrependRevealRequest(direction, startResult)
        markAppendRevealRequest(direction, startResult)
        if (startResult != ReaderSession.AppendStartResult.STARTED && startResult != ReaderSession.AppendStartResult.BUSY) {
            finishSuppressedBoundaryCaptcha("append_not_started")
        }
    }

    private fun hasUnpublishedNtkSessionTail(): Boolean {
        if (!isCurrentNtkManhwaOrWebtoonPath()) return false
        if (pageCount < 0) return false
        return session?.pageInfo(pageCount) != null
    }

    override fun onTap() {
        setToolbarVisible(!toolbarVisible)
    }

    override fun onPhysicalScrollGestureStarted() {
        ViewerTelemetry.physicalScrollGestureStarted()
    }

    override fun onPhysicalScrollMotionEnded() {
        resetStrictPhysicalPresentationCadence()
    }

    private fun resetStrictPhysicalPresentationCadence() {
        strictTelemetryLastScrollOffset = Float.NaN
        strictTelemetryLastCommitNanos = 0L
        strictTelemetryVelocityPxPerSecond = 0f
        ViewerTelemetry.physicalScrollMotionEnded()
    }

    override fun onVisibleCoverageChanged(snapshot: ReaderSurfaceView.VisibleCoverageSnapshot) {
        if (isNativeCoverageViewportReady(snapshot)) {
            lastNativeVisibleCoveragePath = currentManga?.ntkEpisodePath?.trim().orEmpty()
            lastNativeVisibleCoverageSnapshot = snapshot
        }
        if (destroyed || isFinishing || drawableReadyDescriptionPosted) return
        if (!preparedSurfaceAdoptionActive && isNativeCoverageViewportReady(snapshot)) {
            logVisibleViewportReadyMetric()
        }
    }

    override fun onCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof) {
        handleStrictRollingCompletedDraw(proof)
        if (preparedSurfaceAdoptionActive &&
            (proof.hardwareAccelerated || proof.surfaceQueueSubmissionObserved ||
                proof.surfaceControlLatchObserved) &&
            proof.coverage.drawableItems > 0
        ) {
            // Keep cache/ACK/accessibility follow-ups out of the HWUI frame that proves the
            // prepared bitmap batch. The actual pixels are already submitted; this only moves
            // non-render work to the next main-loop turn so the first scroll frame is not taxed.
            if (!preparedFirstDrawFollowupPosted) {
                preparedFirstDrawFollowupPosted = true
                criticalUiHandler.post {
                    if (destroyed || isFinishing) return@post
                    logFirstDrawableMetric(currentPage, "prepared-batch-hardware-draw")
                    if (isNativeCoverageViewportReady(proof.coverage)) {
                        logVisibleViewportReadyMetric()
                    }
                }
            }
        }
        val resumeAt = firstResumeArmedUptimeNanos
        val focusAt = firstFocusArmedUptimeNanos
        val needsResumeProof = resumeAt > 0L &&
            proof.completedUptimeNanos >= resumeAt &&
            firstResumePhysicalDrawProof == null
        val needsFocusProof = focusAt > 0L &&
            proof.completedUptimeNanos >= focusAt &&
            firstFocusPhysicalDrawProof == null
        val readiness = if (needsResumeProof || needsFocusProof) {
            renderView.pageReadinessSnapshot()
        } else {
            null
        }
        if (
            needsResumeProof &&
            readiness != null
        ) {
            firstResumePhysicalDrawProof = FirstPhysicalDrawProof(
                resumeAt,
                proof.sequence,
                proof.completedUptimeNanos,
                proof.hardwareAccelerated,
                proof.coverage,
                readiness
            )
        }
        if (
            needsFocusProof &&
            readiness != null
        ) {
            firstFocusPhysicalDrawProof = FirstPhysicalDrawProof(
                focusAt,
                proof.sequence,
                proof.completedUptimeNanos,
                proof.hardwareAccelerated,
                proof.coverage,
                readiness
            )
        }
        startPreparedSessionAfterPhysicalDraw(proof)
    }

    override fun shouldReportVisibleStats(): Boolean {
        return !isCurrentNtkReader() || !drawableReadyDescriptionPosted
    }

    private fun openAdjacent(next: Boolean) {
        val source = currentManga ?: return
        Log.d(TAG, "open_adjacent next=$next sourceId=${source.id} sourceName=${source.name}")
        if (adjacentNavigationInFlight) return
        val localTarget = cachedAdjacentEpisode(source, next) ?: adjacentEpisodeFast(source, next)
        if (localTarget != null) {
            val title = currentTitle ?: source.title
            localTarget.mode = source.mode
            attachEpisodeList(title, localTarget)
            launchAdjacent(source, localTarget, title, null)
            return
        }
        adjacentNavigationInFlight = true
        setAdjacentButtonState(false, false)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        status.visibility = TextView.VISIBLE
        status.text = if (next) "다음 회차 확인 중" else "이전 회차 확인 중"
        AppDispatchers.submitUserAction {
            val resolved = resolveAdjacent(source, next, true).let { resolution ->
                val target = resolution.target
                if (target == null) {
                    resolution
                } else {
                    val width = readerWidthPx()
                    val preparedKey = ReaderWarmupCoordinator.readyKey(
                        applicationContext,
                        target,
                        resolution.title,
                        width,
                        true
                    ) ?: ReaderWarmupCoordinator.openKey(
                        applicationContext,
                        target,
                        resolution.title,
                        width,
                        true
                    )
                    resolution.copy(preparedKey = preparedKey)
                }
            }
            runOnUiThread {
                Log.d(TAG, "open_adjacent resolved next=$next targetId=${resolved.target?.id} targetName=${resolved.target?.name} result=${resolved.result}")
                finishAdjacentResolution(source, next, resolved)
            }
        }
    }

    private fun finishAdjacentResolution(source: Manga, next: Boolean, resolved: AdjacentResolution) {
        adjacentNavigationInFlight = false
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        if (destroyed || isFinishing) return
        if (resolved.fetchedEpisodes && resolved.target == null) {
            episodeListFetchAttempted = hasStableAdjacentResolutionSource(source, resolved.title)
        }
        currentTitle = resolved.title ?: currentTitle
        if (resolved.target != null) {
            if (next && MissingEpisodeNavigator.maybePromptNextEpisode(
                    this,
                    p?.darkTheme == true,
                    source,
                    resolved.target,
                    missingEpisodePromptState,
                    missingEpisodeHost(),
                    Runnable { launchAdjacent(source, resolved.target, resolved.title, resolved.preparedKey) }
                )
            ) {
                return
            }
            launchAdjacent(source, resolved.target, resolved.title, resolved.preparedKey)
            return
        }
        if (resolved.result == Title.LOAD_CAPTCHA) {
            if (shouldSuppressNtkCaptchaPopupAfterPagesReady(source, "toolbar_adjacent")) {
                updateAdjacentButtons()
                return
            }
            pendingCaptchaRetryManga = source
            pendingCaptchaRetryTitle = resolved.title ?: currentTitle
            pendingCaptchaRetryStartAtFirstPage = false
            pendingCaptchaRetryAction = CAPTCHA_RETRY_TOOLBAR_ADJACENT
            pendingCaptchaRetryNext = next
            status.visibility = TextView.VISIBLE
            status.text = "캡차 확인이 필요합니다"
            Utils.showCaptchaPopup(Manga.safeUrl(source), this, REQUEST_CAPTCHA, p)
        } else if (pagesReady) {
            status.visibility = TextView.GONE
        }
        updateAdjacentButtons()
    }

    private fun launchAdjacent(source: Manga, target: Manga, title: Title?, preparedKey: String? = null) {
        Log.d(TAG, "launch_adjacent sourceId=${source.id} targetId=${target.id} targetName=${target.name}")
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        saveCurrentReadingProgress()
        target.mode = source.mode
        attachEpisodeList(title, target)
        currentManga = target
        currentTitle = title ?: target.title ?: currentTitle
        val displayTitle = displayEpisodeTitle(target, currentTitle)
        titleView.text = displayTitle
        status.text = displayTitle
        lastDisplayedEpisodeKey = displayEpisodeKey(target, currentTitle)
        lastDisplayedEpisodeTitle = displayTitle
        updateResultEpisode(target)
        adjacentNavigationInFlight = false
        setAdjacentButtonState(false, false)
        val episodes = ViewerEpisodeResolver.episodeListFor(target, null, currentTitle)
        attachEpisodeList(currentTitle, target, episodes)
        cachedPreviousEpisode = adjacentEpisodeFastPrepared(target, currentTitle, episodes, false)
        cachedNextEpisode = adjacentEpisodeFastPrepared(target, currentTitle, episodes, true)
        val targetPath = target.ntkEpisodePath?.trim().orEmpty()
        if (target.isOnline && isStrictNtkEpisodePath(targetPath)) {
            beginStrictAdjacentEpisodeTransition(source, target, targetPath)
            startStrictNtkDiscovery(target, "adjacent_episode")
            startStrictReaderSessionWhenExactReady(
                target,
                currentTitle,
                preparedKey,
                startAtFirstPage = true,
                clearViewImmediately = true
            )
            primeAdjacentLaunchWindow(currentTitle, cachedNextEpisode)
            statusHandler.postDelayed({
                if (!destroyed && !isFinishing) updateAdjacentButtons()
            }, ADJACENT_BUTTON_REFRESH_DELAY_MS)
            return
        }
        if (target.isOnline && (targetPath.startsWith("/webtoon/") || targetPath.startsWith("/manhwa/"))) {
            startNtkHybridBrowserReader(target, currentTitle, startAtFirstPage = true)
            primeAdjacentLaunchWindow(currentTitle, cachedNextEpisode)
            statusHandler.postDelayed({
                if (!destroyed && !isFinishing) updateAdjacentButtons()
            }, ADJACENT_BUTTON_REFRESH_DELAY_MS)
            return
        }
        startReaderSession(
            target,
            currentTitle,
            preparedKey,
            startAtFirstPage = true,
            clearViewImmediately = false
        )
        primeAdjacentLaunchWindow(currentTitle, cachedNextEpisode)
        statusHandler.postDelayed({
            if (!destroyed && !isFinishing) updateAdjacentButtons()
        }, ADJACENT_BUTTON_REFRESH_DELAY_MS)
    }

    private fun showEpisodePicker() {
        val source = currentManga ?: return
        Log.d(TAG, "show_episode_picker sourceId=${source.id} sourceName=${source.name}")
        val title = currentTitle ?: source.title
        restoreTitleEpisodes(title, source)
        attachEpisodeList(title, source)
        val episodes = Utils.snapshotEpisodes(title).ifEmpty { Utils.snapshotEpisodes(source) }
        if (episodes.isEmpty()) {
            status.visibility = TextView.VISIBLE
            status.text = "회차 목록이 없습니다"
            return
        }
        val labels = episodes.mapIndexed { index, episode ->
            ReaderDisplayPolicy.episodeDisplayName(episode, episodes, index, title)
        }.toTypedArray()
        val currentIndex = ReaderDisplayPolicy.episodeIndex(episodes, source)
        val dialog = AlertDialog.Builder(this)
            .setTitle("회차 선택")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                val target = episodes.getOrNull(which) ?: return@setSingleChoiceItems
                if (Manga.sameEpisodeIdentity(source, target)) return@setSingleChoiceItems
                val preparedKey = ReaderWarmupCoordinator.readyKey(
                    applicationContext,
                    target,
                    title,
                    readerWidthPx(),
                    true
                ) ?: ReaderWarmupCoordinator.openKey(
                    applicationContext,
                    target,
                    title,
                    readerWidthPx(),
                    true
                )
                launchAdjacent(source, target, title, preparedKey)
            }
            .create()
        dialog.setOnShowListener {
            dialog.listView?.apply {
                isVerticalScrollBarEnabled = true
                isFastScrollEnabled = true
                isFastScrollAlwaysVisible = true
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                setPadding(paddingLeft, paddingTop, 6.dp(), paddingBottom)
                clipToPadding = false
            }
            if (currentIndex >= 0) {
                dialog.listView?.post {
                    dialog.listView?.setSelectionFromTop(currentIndex, 96.dp())
                }
            }
        }
        dialog.show()
    }

    private fun toggleAutoCut() {
        val source = currentManga ?: return
        autoCut = !autoCut
        updateAutoCutButton()
        startReaderSession(source, currentTitle ?: source.title, null)
    }

    private fun updateAutoCutButton() {
        if (!::autoCutButton.isInitialized) return
        autoCutButton.text = if (autoCut) "자동분할 ON" else "자동분할 OFF"
        autoCutButton.contentDescription = if (autoCut) "자동분할 켜짐" else "자동분할 꺼짐"
        autoCutButton.setTextColor(Color.WHITE)
        autoCutButton.background = roundedBackground(
            if (autoCut) 0xff2f6df6.toInt() else 0xff2a2a2a.toInt(),
            if (autoCut) 0x88ffffff.toInt() else 0xff555555.toInt(),
            8.dp()
        )
    }
    private fun startCurrentNtkAckPreflight(
        manga: Manga,
        allowBeforeFirstDrawable: Boolean = false
    ) {
        val path = manga.ntkEpisodePath ?: return
        deferredNtkAckPreflightManga = null
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_current_ack")
        } else {
            Log.d(
                TAG,
                "reader_ntk_ack_preflight_retired path=$path," +
                    "allowBeforeFirstDrawable=$allowBeforeFirstDrawable"
            )
        }
    }

    /**
     * Moves every strict owner to a user-selected adjacent episode before its discovery starts.
     * Keeping the old generation, launch seal, session or committed pixels after currentManga is
     * replaced would allow a late callback to be labelled as the new episode. The transition is
     * intentionally fail-closed: the old surface is cleared immediately and the new episode must
     * produce its own exact authority and HWUI commit proof.
     */
    private fun beginStrictAdjacentEpisodeTransition(
        source: Manga,
        target: Manga,
        rawTargetPath: String
    ) {
        val targetPath = NtkStripDigests.normalizeEpisodePath(rawTargetPath)
        require(isStrictNtkEpisodePath(targetPath)) {
            "Strict adjacent transition requires an exact NTK episode path"
        }
        val oldPath = NtkStripDigests.normalizeEpisodePath(source.ntkEpisodePath.orEmpty())
        val alreadyOwned = strictTelemetryOwned && !strictTelemetryClosed &&
            strictTelemetryEpisodePath.equals(targetPath, ignoreCase = true) &&
            ViewerTelemetry.activeGeneration() == strictTelemetryGeneration &&
            ViewerTelemetry.isActiveEpisode(targetPath)
        if (alreadyOwned) return

        // Publish the old episode while its seal/generation are still authoritative. viewerOpen
        // below then supersedes the old telemetry session and closes outstanding operation IDs.
        publishStrictTelemetryBeforeClose()
        val retiringSeal = strictExactLaunchSeal?.takeIf { it.matchesEpisodePath(oldPath) }
        NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(
            oldPath,
            strictTelemetryGeneration,
            "adjacent_episode"
        )
        retiringSeal?.let {
            NtkSourceSpoolRegistry.retireDiscoveryGenerationForReplacement(
                oldPath,
                it.discoveryGeneration,
                "adjacent_episode"
            )
        }

        activeReaderSessionGeneration.incrementAndGet()
        strictReaderSessionGeneration = -1
        strictExactLaunchSeal = null
        strictDirectManifestAckSkipPath = ""
        strictNtkPendingSessionPath = ""
        strictNtkManifestSubscription?.close()
        strictNtkManifestSubscription = null
        preparedSessionBuildTask?.cancel()
        preparedSessionBuildTask = null
        preparedSessionStartTask?.cancel()
        preparedSessionStartTask = null
        session?.cancel()
        session = null

        if (::renderView.isInitialized) {
            renderView.invalidateCommittedPresentationProof()
            renderView.contentDescription = null
            renderView.setPageCount(0)
        }
        pagesReady = false
        pageCount = 0
        currentPage = 0

        strictTelemetryLifecycleEpoch++
        if (strictTelemetryLifecycleEpoch <= 0L) strictTelemetryLifecycleEpoch = 1L
        strictTelemetryActualInLifecycle = false
        strictTelemetryValidCommittedFrames = 0L
        strictTelemetryInvalidCommittedFrames = 0L
        strictTelemetryViewportDefectFrames = 0L
        strictTelemetryRunwayDefectFrames = 0L
        strictTelemetryIdentityInvalidFrames = 0L
        strictTelemetryInitialBlankFrames = 0L
        strictTelemetryObservedSources = BooleanArray(0)
        strictTelemetryLastFirstPage = -1
        strictTelemetryLastCleanDisplayPage = -1
        strictTelemetryLastCleanSourcePage = -1
        strictTelemetryBottomCommitWaitLogs = 0
        strictTelemetryLastScrollOffset = Float.NaN
        strictTelemetryLastCommitNanos = 0L
        strictTelemetryVelocityPxPerSecond = 0f
        strictTelemetryManifestDigest = ""
        strictTelemetryEpisodePath = targetPath
        strictTelemetryClosed = false
        strictTelemetryGeneration = ViewerTelemetry.viewerOpen(
            strictTelemetryWorkId(target),
            targetPath,
            target.mode.toString()
        )
        strictTelemetryOwned = strictTelemetryGeneration > 0L

        if (oldPath.isNotBlank() && !oldPath.equals(targetPath, ignoreCase = true)) {
            MainApplication.clearNtkForegroundViewerPath(oldPath)
        }
        MainApplication.noteNtkForegroundViewerPath(targetPath)
        Log.d(
            "ViewerPerf",
            "reader_ntk_strict_adjacent_owner_rotated path=$targetPath," +
                "generation=$strictTelemetryGeneration"
        )
    }

    private fun handleStrictRollingCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof) {
        if (!strictTelemetryOwned || strictTelemetryClosed || destroyed || isFinishing) return
        if (!strictTelemetryForegroundCommitArmed ||
            !renderView.hasWindowFocus() ||
            !renderView.isShown ||
            renderView.windowVisibility != View.VISIBLE
        ) {
            // Never turn a background/transition buffer into user-visible draw evidence. A new
            // focus edge invalidates this proof and requests a foreground-owned frame.
            return
        }
        val coverage = proof.coverage
        val visible = proof.visiblePageIndexes
            .filter { it >= 0 }
            .distinct()
            .sorted()
        val viewportDefectReasons = ReaderPipelinePolicy.strictViewportDefectReasons(
            coverage.physicalViewportPx,
            coverage.viewportPx,
            coverage.drawablePx,
            coverage.missingPx,
            coverage.placeholderPx,
            coverage.visibleLoading,
            coverage.visibleErrors,
            coverage.visibleCards,
            coverage.widthFillFailures,
            coverage.lowResolutionItems
        )
        val viewportDefect = viewportDefectReasons.isNotEmpty()
        if (viewportDefect) strictTelemetryViewportDefectFrames++
        val blankOrRootCommit = visible.isEmpty() || viewportDefect
        val launchSeal = strictExactLaunchSeal
        val activeSession = session
        if (launchSeal == null || activeSession == null) {
            if (strictTelemetryInvalidCommittedFrames < 3L) {
                Log.w(
                    "ViewerPerf",
                    "reader_ntk_strict_owner_rejected " +
                        "seal=${launchSeal != null},session=${activeSession != null}," +
                        "visible=${visible.joinToString("|")}"
                )
            }
            if (ReaderPipelinePolicy.shouldCountStrictInitialBlankFrame(
                    strictTelemetryActualInLifecycle,
                    blankOrRootCommit
                )
            ) {
                strictTelemetryInitialBlankFrames++
            }
            strictTelemetryInvalidCommittedFrames++
            return
        }
        val identities = visible.mapNotNull { index ->
            activeSession.pageIdentity(index)?.let { index to it }
        }
        val physicalIdentity = identities.firstOrNull()?.second
        val physicalEpisodePath = physicalIdentity?.normalizedEpisodePath.orEmpty()
        val physicalManifestDigest = physicalIdentity?.manifestDigest.orEmpty()
        val physicalManifestPageCount = physicalIdentity?.manifestPageCount ?: 0
        val identityValid = identities.size == visible.size &&
            NtkVisibleIdentityPolicy.isValid(
                identities.map { (_, identity) ->
                    NtkVisibleIdentityPolicy.Identity(
                        episodePath = identity.normalizedEpisodePath,
                        sourcePageIndex = identity.sourcePageIndex,
                        canonicalAsset = identity.canonicalAsset,
                        manifestDigest = identity.manifestDigest,
                        manifestPageCount = identity.manifestPageCount
                    )
                },
                NtkVisibleIdentityPolicy.LaunchManifest(
                    episodePath = launchSeal.normalizedEpisodePath,
                    manifestDigest = launchSeal.manifestDigest,
                    canonicalAssets = launchSeal.canonicalAssets
                )
            )
        if (!identityValid) {
            if (strictTelemetryIdentityInvalidFrames < 3L) {
                Log.w(
                    "ViewerPerf",
                    "reader_ntk_strict_identity_rejected visible=${visible.joinToString("|")}," +
                        "resolved=${identities.size},path=$physicalEpisodePath," +
                        "manifest=$physicalManifestDigest,pages=$physicalManifestPageCount," +
                        "identities=${identities.joinToString("|") { (_, identity) ->
                            "${identity.normalizedEpisodePath}#${identity.sourcePageIndex}/" +
                                "${identity.manifestPageCount}:${identity.manifestDigest.take(8)}"
                        }}"
                )
            }
            strictTelemetryIdentityInvalidFrames++
            strictTelemetryInvalidCommittedFrames++
            return
        }
        // The render thread may already have advanced its desired scroll state while this older
        // SurfaceControl buffer is only now latching. Re-reading the live runway here compares
        // different frames and reports a false gap. Use the immutable verdict captured alongside
        // this exact token's pixels and coverage proof.
        if (proof.runwayDefect) {
            strictTelemetryRunwayDefectFrames++
        }
        val traversalEpoch = renderView.traversalSnapshot().structureEpoch
        // `currentManga` is a toolbar/progress label updated from the main-thread page callback.
        // The dedicated Surface producer can physically present an already identity-qualified
        // forward-adjacent page one frame before that callback. Requiring the UI label to equal
        // the submitted page therefore rejects correct seamless-next pixels. The immutable
        // identities above already prove the episode path, manifest, asset and source order; keep
        // telemetry ownership bound to the launch seal without racing the delayed UI label.
        val telemetryEpisodeOwned =
            ViewerTelemetry.isActiveEpisode(strictTelemetryEpisodePath) &&
                launchSeal.matchesEpisodePath(strictTelemetryEpisodePath)
        val commitValid = ReaderPipelinePolicy.isStrictCommittedFrameValid(
            sessionGenerationMatches =
                activeReaderSessionGeneration.get() == strictReaderSessionGeneration,
            telemetryGenerationMatches =
                ViewerTelemetry.activeGeneration() == strictTelemetryGeneration,
            episodeMatches = telemetryEpisodeOwned,
            hardwareAccelerated = proof.hardwareAccelerated,
            registeredHwuiFrameCommitCallbackObserved =
                proof.registeredHwuiFrameCommitCallbackObserved,
            surfaceQueueSubmissionObserved = proof.surfaceQueueSubmissionObserved,
            frameToken = proof.frameToken,
            drawnVersion = proof.drawnVersion,
            committedVersion = proof.committedVersion,
            proofStructureEpoch = proof.structureEpoch,
            currentStructureEpoch = traversalEpoch,
            hasVisiblePages = visible.isNotEmpty(),
            viewportDefect = viewportDefect,
            surfaceControlLatchObserved = proof.surfaceControlLatchObserved
        )
        if (!commitValid) {
            if (strictTelemetryInvalidCommittedFrames < 3L ||
                (viewportDefect && strictTelemetryViewportDefectFrames <= 3L)
            ) {
                Log.w(
                    "ViewerPerf",
                    "reader_ntk_strict_commit_rejected " +
                        "activeGeneration=${activeReaderSessionGeneration.get()}," +
                        "strictGeneration=$strictReaderSessionGeneration," +
                        "telemetryGeneration=${ViewerTelemetry.activeGeneration()}/$strictTelemetryGeneration," +
                        "hardware=${proof.hardwareAccelerated}," +
                        "hwuiCommitCallback=${proof.registeredHwuiFrameCommitCallbackObserved}," +
                        "surfaceQueue=${proof.surfaceQueueSubmissionObserved}," +
                        "surfaceControlLatch=${proof.surfaceControlLatchObserved}," +
                        "token=${proof.frameToken}," +
                        "versions=${proof.drawnVersion}/${proof.committedVersion}," +
                        "structure=${proof.structureEpoch}/$traversalEpoch," +
                        "visible=${visible.joinToString("|")},viewportDefect=$viewportDefect," +
                        "defectReasons=$viewportDefectReasons," +
                        "coverage=${coverage.drawablePx}/${coverage.physicalViewportPx}," +
                        "viewport=${coverage.viewportPx},missing=${coverage.missingPx}," +
                        "placeholder=${coverage.placeholderPx},loading=${coverage.visibleLoading}," +
                        "errors=${coverage.visibleErrors},cards=${coverage.visibleCards}," +
                        "widthFill=${coverage.widthFillFailures},lowRes=${coverage.lowResolutionItems}"
                )
            }
            if (ReaderPipelinePolicy.shouldCountStrictInitialBlankFrame(
                    strictTelemetryActualInLifecycle,
                    blankOrRootCommit
                )
            ) {
                strictTelemetryInitialBlankFrames++
            }
            strictTelemetryInvalidCommittedFrames++
            return
        }
        if (physicalEpisodePath == launchSeal.normalizedEpisodePath) {
            identities.forEach { (_, identity) ->
                strictTelemetryObservedSources[identity.sourcePageIndex] = true
            }
        }
        strictTelemetryLastCleanSourcePage = identities.maxOf { (_, identity) ->
            identity.sourcePageIndex
        }
        strictTelemetryValidCommittedFrames++
        val first = visible.first()
        val last = visible.last()
        // Surface commit callbacks can be delivered out of order relative to a later latched
        // buffer. Forward qualification must remember the furthest identity-valid pixels ever
        // presented instead of letting an older callback regress the completed tail proof.
        strictTelemetryLastCleanDisplayPage = maxOf(strictTelemetryLastCleanDisplayPage, last)
        val previousPage = strictTelemetryLastFirstPage
        // Bind velocity/direction to the viewport carried by this exact submitted proof. Reading
        // the View's current position here can observe a later frame when main delivery is busy,
        // collapsing several distinct submissions onto one coordinate and manufacturing gaps.
        val scrollOffset = proof.scrollOffsetPx.takeIf { it.isFinite() }
            ?: renderView.currentScrollPositionSnapshot()?.scrollOffset?.toFloat()
            ?: strictTelemetryLastScrollOffset.takeIf { it.isFinite() }
            ?: 0f
        val previousScrollOffset = strictTelemetryLastScrollOffset
        val previousNanos = strictTelemetryLastCommitNanos
        val presentedUptimeNanos = proof.presentedUptimeNanos.takeIf { it > 0L }
            ?: proof.completedUptimeNanos
        val elapsedNanos = presentedUptimeNanos - previousNanos
        strictTelemetryVelocityPxPerSecond = if (
            previousScrollOffset.isFinite() && elapsedNanos > 0L
        ) {
            ((scrollOffset - previousScrollOffset).toDouble() *
                1_000_000_000.0 / elapsedNanos.toDouble()).toFloat()
        } else {
            0f
        }
        val direction = when {
            previousScrollOffset.isFinite() && scrollOffset < previousScrollOffset -> -1
            previousScrollOffset.isFinite() && scrollOffset > previousScrollOffset -> 1
            first < previousPage -> -1
            first > previousPage -> 1
            strictTelemetryVelocityPxPerSecond < -25f -> -1
            else -> 1
        }
        strictTelemetryLastFirstPage = first
        strictTelemetryLastScrollOffset = scrollOffset
        strictTelemetryLastCommitNanos = presentedUptimeNanos

        // Emit the actual committed-frame marker before opening page > 1 source admission. This
        // preserves the cold proof ordering in log/Perfetto evidence.
        val firstVisibleSource = identities.minOf { (_, identity) -> identity.sourcePageIndex }
        val lastVisibleSource = identities.maxOf { (_, identity) -> identity.sourcePageIndex }
        ViewerTelemetry.actualImageDrawCommittedForEpisode(
            renderView,
            strictTelemetryGeneration,
            physicalEpisodePath,
            firstVisibleSource,
            lastVisibleSource,
            presentedUptimeNanos,
            true,
            -1L,
            strictTelemetryVelocityPxPerSecond
        )
        strictTelemetryActualInLifecycle = true
        activeSession.onExactNtkPhysicalDrawPresented(first, last, direction)
        publishStrictViewerEdge(
            first == 0,
            ReaderPipelinePolicy.isStrictBottomEdgeEligible(
                pageCount,
                last,
                viewportDefectReasons
            )
        )
    }
    private fun prepareDeferredNtkAckChallenge(manga: Manga) {
        val path = manga.ntkEpisodePath ?: return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_deferred_ack_prepare")
        } else {
            Log.d(TAG, "reader_ntk_deferred_ack_prepare_retired path=$path")
        }
    }
    private fun startInitialNtkWebViewAckOnlyPreflight(manga: Manga) {
        val path = manga.ntkEpisodePath ?: return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_webview_ack")
        } else {
            Log.d(TAG, "reader_ntk_initial_webview_ack_retired path=$path")
        }
    }
    private fun startPreparedNtkNativeAckPreflight(
        manga: Manga,
        reason: String
    ): Boolean {
        val path = manga.ntkEpisodePath ?: return false
        if (isStrictNtkEpisodePath(path)) {
            return startStrictNtkDiscovery(manga, "retired_prepared_ack_$reason")
        }
        Log.d(TAG, "reader_ntk_prepared_ack_retired reason=$reason,path=$path")
        return false
    }
    private fun requestNtkAckCaptcha(manga: Manga, reason: String) {
        runOnUiThread {
            if (shouldSuppressNtkCaptchaPopupAfterPagesReady(manga, "ack_$reason")) return@runOnUiThread
            if (destroyed || isFinishing || ntkAckCaptchaRequested) return@runOnUiThread
            ntkAckCaptchaRequested = true
            pendingCaptchaRetryManga = manga
            pendingCaptchaRetryTitle = manga.title ?: currentTitle
            pendingCaptchaRetryStartAtFirstPage = false
            pendingCaptchaRetryAction = CAPTCHA_RETRY_READER
            pendingCaptchaRetryDirection = 0
            pendingCaptchaRetryAnchor = -1
            pendingBoundaryCaptchaRetry = false
            Log.d(TAG, "reader_ntk_ack_captcha_required reason=$reason,path=${manga.ntkEpisodePath}")
            Utils.showCaptchaPopup(Manga.safeUrl(manga), this, REQUEST_CAPTCHA, p)
        }
    }

    private fun shouldDeferInitialNtkAckPreflight(manga: Manga): Boolean {
        if (!isCurrentNtkReader()) return false
        val path = manga.ntkEpisodePath ?: return false
        if (isStrictNtkEpisodePath(path)) return false
        if (hasCompleteNativeDirectManifest(manga)) return false
        if (getHttpClient().hasRecentStrictNtkAdAckProof(path)) {
            return false
        }
        if (shouldAllowInitialNtkAckBeforeFirstDrawable(manga)) return false
        return path.startsWith("/webtoon/")
    }

    private fun shouldAllowInitialNtkAckBeforeFirstDrawable(manga: Manga): Boolean {
        val path = manga.ntkEpisodePath ?: return false
        if (isStrictNtkEpisodePath(path)) return false
        return isSlugWebtoonNtkPath(path)
    }

    private fun canRunAutomaticNtkAck(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val client = getHttpClient()
            client.hasRecentStrictNtkAdAckProof(path) ||
                client.isModernNtkGuardRootForPath(path) ||
                client.hasCloudflareClearanceForUrl(client.getUrl(path))
        } catch (_: Exception) {
            false
        }
    }

    private fun isSlugWebtoonNtkPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val match = Regex("^/webtoon/([^/?#]+)/([^/?#]+)").find(path) ?: return false
        return match.groupValues[1].any { !it.isDigit() } ||
            match.groupValues[2].any { !it.isDigit() }
    }

    private fun startDeferredNtkAckPreflight(reason: String, allowBeforeFirstDrawable: Boolean = false) {
        val manga = deferredNtkAckPreflightManga ?: return
        val path = manga.ntkEpisodePath
        if (isStrictNtkEpisodePath(path)) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            startStrictNtkDiscovery(manga, "legacy_deferred_ack_reroute_$reason")
            return
        }
        if (!path.isNullOrBlank() && hasForegroundDirectManifestOwnership(manga, path)) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_ack_deferred_skip_foreground_direct_manifest reason=$reason,path=$path")
            return
        }
        if (!firstDrawableMetricLogged) {
            Log.d(TAG, "reader_ntk_ack_preflight_wait_first_drawable reason=$reason,path=${manga.ntkEpisodePath}")
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            return
        }
        if (!allowBeforeFirstDrawable && shouldWaitForInitialContinuousBeforeNtkAck(manga.ntkEpisodePath)) {
            waitForInitialContinuousBeforeNtkAck("deferred_$reason", manga.ntkEpisodePath)
            return
        }
        if (!allowBeforeFirstDrawable) {
            val strictFloorMs = ntkAckStrictAfterFirstDrawableFloorRemainingMs(manga)
            if (strictFloorMs > 0L) {
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, strictFloorMs)
                Log.d(TAG, "reader_ntk_ack_preflight_deferred_wait_strict_floor reason=$reason,quietMs=$strictFloorMs,path=${manga.ntkEpisodePath}")
                return
            }
            val quietMs = ntkAckPreflightQuietRemainingMs()
            if (quietMs > 0L) {
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, quietMs)
                Log.d(TAG, "reader_ntk_ack_preflight_deferred_wait_quiet reason=$reason,quietMs=$quietMs,path=${manga.ntkEpisodePath}")
                return
            }
        }
        deferredNtkAckPreflightManga = null
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        if (destroyed || isFinishing) return
        Log.d(TAG, "reader_ntk_ack_preflight_deferred_start reason=$reason,allowBeforeFirstDrawable=$allowBeforeFirstDrawable,path=${manga.ntkEpisodePath}")
        startCurrentNtkAckPreflight(manga, allowBeforeFirstDrawable)
    }

    private fun maybeStartDeferredNtkAckForInitialCloudflareProbe() {
        val deferred = deferredNtkAckPreflightManga ?: return
        deferredNtkAckPreflightManga = null
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        if (isStrictNtkEpisodePath(deferred.ntkEpisodePath)) {
            startStrictNtkDiscovery(deferred, "retired_cloudflare_probe")
        }
    }
    private fun maybeStartDeferredNtkAckForInitialBlock(
        manga: Manga?,
        reason: String,
        allowBeforeFirstDrawable: Boolean = false
    ) {
        val deferred = deferredNtkAckPreflightManga ?: return
        val deferredPath = deferred.ntkEpisodePath
        if (!deferredPath.isNullOrBlank() && hasForegroundDirectManifestOwnership(deferred, deferredPath)) {
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            Log.d(TAG, "reader_ntk_ack_initial_block_skip_foreground_direct_manifest reason=$reason,path=$deferredPath")
            return
        }
        val currentPath = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (!deferredPath.isNullOrBlank() && !currentPath.isNullOrBlank() && deferredPath != currentPath) {
            Log.d(TAG, "reader_ntk_ack_preflight_initial_block_skip reason=$reason,deferredPath=$deferredPath,currentPath=$currentPath")
            return
        }
        if (!firstDrawableMetricLogged) {
            Log.d(TAG, "reader_ntk_ack_preflight_wait_first_drawable reason=$reason,path=$deferredPath")
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            return
        }
        if (!allowBeforeFirstDrawable && shouldWaitForInitialContinuousBeforeNtkAck(deferredPath)) {
            waitForInitialContinuousBeforeNtkAck("initial_block_$reason", deferredPath)
            return
        }
        if (!allowBeforeFirstDrawable) {
            val strictFloorMs = ntkAckStrictAfterFirstDrawableFloorRemainingMs(deferred)
            if (strictFloorMs > 0L) {
                Log.d(TAG, "reader_ntk_ack_preflight_initial_block_wait_strict_floor reason=$reason,quietMs=$strictFloorMs,path=$deferredPath")
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, strictFloorMs)
                return
            }
            val quietMs = ntkAckPreflightQuietRemainingMs()
            if (quietMs > 0L) {
                Log.d(TAG, "reader_ntk_ack_preflight_initial_block_wait_quiet reason=$reason,quietMs=$quietMs,path=$deferredPath")
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, quietMs)
                return
            }
        }
        startDeferredNtkAckPreflight(reason, allowBeforeFirstDrawable)
    }

    private fun isStrictNtkEpisodePath(path: String?): Boolean {
        val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty()).lowercase(Locale.ROOT)
        return normalized.matches(Regex("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$"))
    }

    private fun strictTelemetryWorkId(manga: Manga): String {
        val titleId = manga.titleId.takeIf { it > 0 } ?: manga.title?.id ?: -1
        return "$titleId:${manga.id}"
    }

    /**
     * Strict UI entry points may only reserve or join the isolated ACK + exact-manifest flight.
     * This method deliberately does not inspect or publish any Browser broker state.
     */
    private fun startStrictNtkDiscovery(manga: Manga, reason: String): Boolean {
        val path = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        if (!isStrictNtkEpisodePath(path)) return false
        val started = NtkStrictEpisodeDiscoveryCoordinator.startColdRolling(getHttpClient(), manga)
        val joined = started ||
            NtkStrictEpisodeDiscoveryCoordinator.isInFlight(path) ||
            NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null
        Log.d(
            "ViewerPerf",
            "reader_ntk_strict_exact_discovery_ui path=$path,reason=$reason," +
                "started=$started,joined=$joined"
        )
        return joined
    }

    private fun isCurrentNtkReader(): Boolean {
        val path = currentManga?.ntkEpisodePath
        return viewerLaunchSourceSite.equals("ntk", ignoreCase = true) ||
            path?.startsWith("/webtoon/") == true ||
            path?.startsWith("/manhwa/") == true
    }

    private fun isInitialContinuousNtkPath(path: String?): Boolean {
        return path?.startsWith("/webtoon/") == true || path?.startsWith("/manhwa/") == true
    }

    private fun shouldWaitForInitialContinuousBeforeNtkAck(path: String?): Boolean {
        if (!isInitialContinuousNtkPath(path)) return false
        return !isInitialContinuousScrollReady() && !isVisibleViewportReady()
    }

    private fun waitForInitialContinuousBeforeNtkAck(reason: String, path: String?) {
        Log.d(TAG, "reader_ntk_ack_preflight_wait_initial_continuous reason=$reason,path=$path")
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        statusHandler.postDelayed(
            deferredNtkAckPreflightQuietRunnable,
            NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
        )
    }

    private fun shouldSuppressNtkCaptchaPopupAfterPagesReady(manga: Manga?, reason: String): Boolean {
        val path = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (!pagesReady || path.isNullOrBlank()) return false
        if (!isCurrentNtkReader()) return false
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        Log.d(TAG, "reader_ntk_captcha_popup_suppressed reason=$reason,path=$path")
        if (::status.isInitialized && pagesReady) {
            status.visibility = TextView.GONE
        }
        finishSuppressedBoundaryCaptcha(reason)
        return true
    }

    private fun shouldDeferInitialNtkCaptcha(manga: Manga?): Boolean {
        if (firstDrawableMetricLogged || pagesReady) return false
        val path = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (path.isNullOrBlank()) return false
        val client = getHttpClient()
        if (client.hasRecentStrictNtkAdAckProof(path) ||
            client.hasNtkAccessProof() ||
            client.hasRecentNtkAccessVerification()) return false
        val ackOrImageProgress =
            client.hasUsableNtkAdAckCookieForPath(path) || hasInitialNtkDrawableProgress()
        val maxDefers = if (ackOrImageProgress) {
            NTK_INITIAL_CAPTCHA_PROGRESS_MAX_DEFERS
        } else {
            NTK_INITIAL_CAPTCHA_MAX_DEFERS
        }
        if (pendingInitialNtkCaptchaDeferrals >= maxDefers) return false
        val shouldDefer = isCurrentNtkReader() && (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"))
        if (shouldDefer && client.hasRecentCloudflareChallenge()) {
            Log.d(
                TAG,
                "reader_ntk_captcha_defer_despite_recent_cf path=$path," +
                    "attempt=${pendingInitialNtkCaptchaDeferrals + 1},max=$maxDefers," +
                    "ackOrImageProgress=$ackOrImageProgress"
            )
        }
        return shouldDefer
    }

    private fun hasInitialNtkDrawableProgress(): Boolean {
        if (!isCurrentNtkReader()) return false
        if (launchDrawableMetricPages.isNotEmpty()) return true
        if (!::renderView.isInitialized) return false
        val snapshot = renderView.visibleCoverageSnapshot() ?: return false
        return snapshot.drawablePx > 0
    }

    private fun retryInitialNtkAfterAccessProof(manga: Manga?): Boolean {
        val path = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (path.isNullOrBlank()) return false
        if (!isCurrentNtkReader() || firstDrawableMetricLogged || pagesReady) return false
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        val client = getHttpClient()
        val hasProof = client.hasRecentStrictNtkAdAckProof(path) ||
            client.hasNtkAccessProof() ||
            client.hasRecentNtkAccessVerification()
        if (!hasProof) return false
        if (ntkInitialProofRetryPath != path) {
            ntkInitialProofRetryPath = path
            ntkInitialProofRetryCount = 0
        }
        if (ntkInitialProofRetryCount >= 2) return false
        ntkInitialProofRetryCount++
        val retryManga = manga ?: currentManga ?: return false
        if (client.hasRecentStrictNtkAdAckProof(path)) {
        }
        Log.d(TAG, "reader_ntk_captcha_retry_after_proof path=$path,count=$ntkInitialProofRetryCount")
        statusHandler.post {
            if (!destroyed && !isFinishing && !firstDrawableMetricLogged) {
                startReaderSession(
                    retryManga,
                    retryManga.title ?: currentTitle,
                    null,
                    startAtFirstPage = retryManga !== currentManga,
                    clearViewImmediately = false
                )
            }
        }
        return true
    }

    private fun startStrictReaderSessionWhenExactReady(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startAtFirstPage: Boolean,
        clearViewImmediately: Boolean
    ) {
        val path = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        if (!isStrictNtkEpisodePath(path)) {
            startReaderSession(
                manga,
                title,
                preparedKey,
                startAtFirstPage,
                clearViewImmediately
            )
            return
        }
        if (strictNtkPendingSessionPath == path && strictNtkManifestSubscription != null) {
            startStrictNtkDiscovery(manga, "reader_session_wait_join")
            return
        }

        strictNtkManifestSubscription?.close()
        strictNtkManifestSubscription = null
        strictNtkPendingSessionPath = path

        fun acceptExact(manifest: NtkAuthoritativeManifest) {
            if (!manifest.isProductionClaimable ||
                manifest.seal.normalizedEpisodePath != path
            ) return
            val activePath = NtkStripDigests.normalizeEpisodePath(
                currentManga?.ntkEpisodePath.orEmpty()
            )
            val currentAuthority = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)
            if (destroyed || isFinishing || strictNtkPendingSessionPath != path ||
                activePath != path || currentAuthority == null ||
                !currentAuthority.seal.hasSameAuthority(manifest.seal) ||
                currentAuthority.proof.discoveryGeneration != manifest.proof.discoveryGeneration ||
                currentAuthority.proof.proofDigestSha256 != manifest.proof.proofDigestSha256
            ) return

            // A host-GPU emulator's first window buffer can keep the main looper inside
            // ViewRootImpl.postAndWait for well over a second. The immutable manifest and exact
            // body port are already production authority at this point, so create and start the
            // non-visual ReaderSession on this click-owned discovery thread. ReaderSession posts
            // every UI callback to the main looper; enqueueing the adoption runnable first keeps
            // UI state ordered while network-body decode overlaps the unrelated window wait.
            val early = synchronized(strictEarlySessionLock) {
                strictEarlySession?.takeIf {
                    it.path == path && it.manifestDigest == manifest.seal.digestSha256
                } ?: run {
                    val seal = StrictExactLaunchSeal.from(manifest)
                    val exactImages = ArrayList(seal.canonicalAssets)
                    manga.setImgs(exactImages)
                    manga.ntkImageCount = exactImages.size
                    strictExactLaunchSeal = seal
                    rememberStrictDirectManifestAckAuthority(seal)
                    val generation = activeReaderSessionGeneration.incrementAndGet()
                    strictReaderSessionGeneration = generation
                    synchronized(strictRenderReadyLock) {
                        strictRenderReadyPages.clear()
                        strictRenderReadyGeneration = generation
                        strictAllImagesReadyPublished = false
                        strictRollingHistoricalScene = false
                    }
                    ReaderSession(
                        context = applicationContext,
                        manga = manga,
                        title = title,
                        viewerWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1),
                        viewerHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1),
                        autoCut = autoCut,
                        reverse = p?.getReverse() == true,
                        preparedKey = preparedKey,
                        startAtFirstPage = startAtFirstPage,
                        listener = activeReaderSessionListener(generation),
                        strictExactLaunchSeal = seal
                    ).let { built ->
                        StrictEarlySession(
                            path,
                            seal.manifestDigest,
                            generation,
                            seal,
                            built
                        ).also { strictEarlySession = it }
                    }
                }
            }
            if (early.session !== strictEarlySession?.session) return
            if (!early.startClaimed.compareAndSet(false, true)) return

            statusHandler.post {
                if (destroyed || isFinishing || strictNtkPendingSessionPath != path ||
                    strictEarlySession !== early ||
                    activeReaderSessionGeneration.get() != early.generation
                ) {
                    early.session.cancel()
                    synchronized(strictEarlySessionLock) {
                        if (strictEarlySession === early) strictEarlySession = null
                    }
                    return@post
                }
                strictNtkPendingSessionPath = ""
                strictNtkManifestSubscription?.close()
                strictNtkManifestSubscription = null
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_strict_exact_ui_install path=$path,pages=${early.seal.pageCount}," +
                        "manifestDigest=${early.seal.manifestDigest},earlyGeneration=${early.generation}"
                )
                startReaderSession(
                    manga,
                    title,
                    preparedKey,
                    startAtFirstPage,
                    clearViewImmediately,
                    early.seal,
                    early.session,
                    early.generation
                )
            }
            val startAt = SystemClock.elapsedRealtime()
            early.session.start()
            Log.d(
                "ViewerPerf",
                "reader_ntk_strict_early_session_started path=$path,generation=${early.generation}," +
                    "ms=${SystemClock.elapsedRealtime() - startAt}"
            )
        }

        strictNtkManifestSubscription = NtkSourceSpoolRegistry.addAuthoritativeManifestListener(
            NtkAuthoritativeManifestListener { changedPath, manifest ->
                if (changedPath.equals(path, ignoreCase = true)) acceptExact(manifest)
            }
        )
        // Close the listener-registration race without polling or falling back to Browser state.
        NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)?.let(::acceptExact)
        startStrictNtkDiscovery(manga, "reader_session_exact_gate")
        Log.d(TAG, "reader_ntk_strict_session_wait_exact path=$path")
    }

    private fun startReaderSession(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startAtFirstPage: Boolean = false,
        clearViewImmediately: Boolean = true,
        acceptedStrictSeal: StrictExactLaunchSeal? = null,
        prestartedStrictSession: ReaderSession? = null,
        prestartedStrictGeneration: Int = -1
    ) {
        val startingPath = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        var exactLaunchSeal = acceptedStrictSeal
        if (isStrictNtkEpisodePath(startingPath)) {
            val exactAuthority = NtkSourceSpoolRegistry.currentAuthoritativeManifest(startingPath)
            if (exactAuthority?.isProductionClaimable != true) {
                startStrictReaderSessionWhenExactReady(
                    manga,
                    title,
                    preparedKey,
                    startAtFirstPage,
                    clearViewImmediately
                )
                return
            }
            if (exactLaunchSeal == null) {
                exactLaunchSeal = StrictExactLaunchSeal.from(exactAuthority)
            }
            if (!exactLaunchSeal.hasSameAuthority(exactAuthority) ||
                !exactLaunchSeal.matchesEpisodePath(startingPath)
            ) {
                Log.e(TAG, "reader_ntk_strict_launch_seal_stale path=$startingPath")
                return
            }
            val exactImages = ArrayList(exactLaunchSeal.canonicalAssets)
            manga.setImgs(exactImages)
            manga.ntkImageCount = exactImages.size
            strictExactLaunchSeal = exactLaunchSeal
            rememberStrictDirectManifestAckAuthority(exactLaunchSeal)
            strictTelemetryObservedSources = BooleanArray(exactLaunchSeal.pageCount)
            if (strictTelemetryManifestDigest != exactLaunchSeal.manifestDigest) {
                ViewerTelemetry.manifestSummary(
                    exactLaunchSeal.pageCount,
                    exactLaunchSeal.manifestDigest
                )
                strictTelemetryManifestDigest = exactLaunchSeal.manifestDigest
            }
        }
        if (strictNtkPendingSessionPath.isNotEmpty() && strictNtkPendingSessionPath != startingPath) {
            strictNtkPendingSessionPath = ""
            strictNtkManifestSubscription?.close()
            strictNtkManifestSubscription = null
        }
        val sessionGeneration = if (prestartedStrictSession != null) {
            if (prestartedStrictGeneration <= 0 ||
                activeReaderSessionGeneration.get() != prestartedStrictGeneration ||
                strictEarlySession?.session !== prestartedStrictSession
            ) {
                prestartedStrictSession.cancel()
                return
            }
            prestartedStrictGeneration
        } else {
            activeReaderSessionGeneration.incrementAndGet()
        }
        strictReaderSessionGeneration = if (exactLaunchSeal != null) sessionGeneration else -1
        clearStrictAuthoritativeInstallQueue()
        synchronized(strictRenderReadyLock) {
            strictRenderReadyPages.clear()
            strictRenderReadyGeneration = strictReaderSessionGeneration
            strictAllImagesReadyPublished = false
            strictRollingHistoricalScene = false
        }
        // The early ReaderSession can decode before this main-thread adoption point. Enable the
        // worker-to-install-queue fast path only after the old queue/readiness ledger above has
        // been cleared, otherwise an early winner can be discarded and then permanently treated
        // as handed off by the immutable session.
        strictWorkerHandoffGeneration = if (exactLaunchSeal != null) sessionGeneration else -1
        preparedSessionBuildTask?.cancel()
        preparedSessionBuildTask = null
        preparedSessionStartTask?.cancel()
        preparedSessionStartTask = null
        clearDeferredAppendPublishCallbacks()
        hybridNtkBrowserActive = false
        hybridNtkNativeHandoffStarted = false
        hybridNtkNativeHandoffPending = false
        hybridNtkProgressiveNativeSeedActive = false
        hybridNtkForceBrowserAuthoritative = false
        hybridNtkFirstDrawableReady = false
        hybridNtkViewportReady = false
        hybridNtkPumpAllRequested = false
        hybridNtkWebView = null
        hybridNtkEarlyUrlListenerDisposer?.invoke()
        hybridNtkEarlyUrlListenerDisposer = null
        NtkBrowserSessionBroker.detachKeepAlive()
        if (::renderView.isInitialized) {
            renderView.id = R.id.strip
            // Strict cold launches keep the SurfaceView detached from composition until the
            // renderer has authoritative pixels. Making an empty opaque surface visible here
            // blocks the UI thread in the first ViewRoot draw while SurfaceFlinger waits for its
            // first buffer, and it also exposes a white surface before any page can be drawn.
            if (exactLaunchSeal == null) {
                renderView.visibility = View.VISIBLE
            }
        }
        val ntkPath = manga.ntkEpisodePath
        if (lastNativeVisibleCoveragePath.isNotBlank() && lastNativeVisibleCoveragePath != ntkPath?.trim().orEmpty()) {
            lastNativeVisibleCoveragePath = ""
            lastNativeVisibleCoverageSnapshot = null
        }
        var preservePreRenderedLaunchDrawable = synchronized(this) {
            preRenderedInitialDrawableIndex == 0 &&
                !ntkPath.isNullOrBlank() &&
                (currentManga?.ntkEpisodePath == null || currentManga?.ntkEpisodePath == ntkPath)
        }
        pagesReady = false
        pageCount = if (preservePreRenderedLaunchDrawable) maxOf(pageCount, 1) else 0
        currentPage = 0
        pendingInitialNtkCaptchaDeferrals = 0
        if (isStrictNtkEpisodePath(ntkPath)) {
            ntkAckPreflightGeneration.incrementAndGet()
            deferredNtkAckPreflightManga = null
            statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            ReaderImageCache.cancelNtkGeneratedForegroundWorkForEpisode(
                ntkPath,
                manga.baseMode,
                "strict_exact_session_owner"
            )
            getHttpClient().cancelNtkWebViewFallbacks()
            startStrictNtkDiscovery(manga, "native_session_exact_authority")
            Log.d(
                "ViewerPerf",
                "reader_activity_start_strict_exact_session path=$ntkPath," +
                    "clear=$clearViewImmediately"
            )
        } else if (ntkPath?.let { it.startsWith("/webtoon/") || it.startsWith("/manhwa/") } == true) {
            val preserveLaunchPreflight = ntkLaunchPreflightPath == ntkPath ||
                ntkInitialDiscoveryPath == ntkPath
            if (!preserveLaunchPreflight) {
                ntkAckPreflightGeneration.incrementAndGet()
            }
            Log.d(
                "ViewerPerf",
                "reader_activity_start_session path=$ntkPath,clear=$clearViewImmediately,preserveLaunchPreflight=$preserveLaunchPreflight"
            )
            val preserveActiveInitialStream = ReaderImageCache.hasActiveInitialNtkForegroundStream(
                ntkPath,
                manga.baseMode
            ) || ReaderImageCache.hasNtkAnchorAssetForEpisode(manga)
            if (hasForegroundDirectManifestOwnership(manga, ntkPath) || preserveActiveInitialStream) {
                Log.d(
                    "ViewerPerf",
                    "reader_activity_start_session_preserve_foreground_direct_stream path=$ntkPath," +
                        "activeInitial=$preserveActiveInitialStream"
                )
            } else {
                ReaderImageCache.cancelNtkGeneratedForegroundWorkForEpisode(
                    ntkPath,
                    manga.baseMode,
                    "start_session_anchor_priority"
                )
            }
            if (!preserveLaunchPreflight) {
                getHttpClient().cancelNtkWebViewFallbacks()
            }
            Log.d(
                "ViewerPerf",
                "reader_ntk_unsigned_generated_prefetch_skip reason=session_cancel_uses_tokenized_prefetch,path=$ntkPath"
            )
            val slugWebtoonPath = Regex("^/webtoon/([^/?#]+)/([^/?#]+)").find(ntkPath)?.let {
                it.groupValues[1].any { ch -> !ch.isDigit() } ||
                    it.groupValues[2].any { ch -> !ch.isDigit() }
            } == true
            Log.d(TAG, "reader_ntk_ack_session_legacy_hold_retired path=$ntkPath,slug=$slugWebtoonPath")
        }
        initialStartAtFirstPage = startAtFirstPage
        lastDisplayedPageText = ""
        pendingBoundaryStatus = false
        clearPendingBoundaryCaptchaRetry()
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        pendingProgressInfo = null
        pendingProgressOffset = 0
        progressSaveArmed = false
        progressMovedInGesture = false
        clearPendingPageCallbacks()
        progressHandler.removeCallbacks(saveProgressRunnable)
        lastSavedEpisodeId = -1
        lastSavedPage = -1
        lastSavedOffset = Int.MIN_VALUE
        lastSavedSide = Int.MIN_VALUE
        initialStatusPending = false
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        status.visibility = TextView.GONE
        status.text = displayEpisodeTitle(manga, title)
        if (!preservePreRenderedLaunchDrawable) {
            preservePreRenderedLaunchDrawable = synchronized(this) {
                preRenderedInitialDrawableIndex == 0 &&
                    !ntkPath.isNullOrBlank()
            }
            if (preservePreRenderedLaunchDrawable) {
                pageCount = maxOf(pageCount, 1)
            }
        }
        if (!preservePreRenderedLaunchDrawable) {
            clearPreRenderedInitialDrawable()
            firstDrawableMetricLogged = false
            firstDrawableLoggedAtMs = 0L
            firstDrawableLoggedElapsedAtMs = 0L
            firstDrawableElapsedMsForTest = -1L
        } else {
            if (!firstDrawableMetricLogged) {
                firstDrawableLoggedAtMs = 0L
                firstDrawableLoggedElapsedAtMs = 0L
                firstDrawableElapsedMsForTest = -1L
            }
            Log.d(
                TAG,
                "reader_start_session_preserve_initial_prerender path=$ntkPath," +
                    "metricLogged=$firstDrawableMetricLogged,elapsed=$firstDrawableElapsedMsForTest,pageCount=$pageCount"
            )
        }
        ntkImmediateNativeAckProofPath = ""
        initialDrawGateNtkTimeoutDeferrals = 0
        drawableReadyDescriptionPosted = false
        launchDrawableMetricPages.clear()
        launchDrawableElapsedMsByPage.clear()
        if (clearViewImmediately && !preservePreRenderedLaunchDrawable) renderView.setPageCount(0)
        val preserveNtkVolatileForDirectManifest =
            ntkPath?.let { hasForegroundDirectManifestOwnership(manga, it) } == true
        val previousSession = session
        if (previousSession === preparedBuiltSession && !preparedSessionStartBegan) {
            Log.d(TAG, "reader_prepared_session_drop_unstarted path=$ntkPath")
        } else if (preserveNtkVolatileForDirectManifest) {
            previousSession?.cancelPreservingNtkVolatileForPath(ntkPath)
        } else {
            previousSession?.cancel()
        }
        preparedBuiltSession = null
        preparedSessionStartBegan = false
        val createStartedAt = SystemClock.elapsedRealtime()
        Log.d("ViewerPerf", "reader_activity_create_session_start path=$ntkPath")
        if (prestartedStrictSession != null) {
            session = prestartedStrictSession
            synchronized(strictEarlySessionLock) {
                if (strictEarlySession?.session === prestartedStrictSession) {
                    strictEarlySession = null
                }
            }
            Log.d(
                "ViewerPerf",
                "reader_activity_adopt_early_session path=$ntkPath,generation=$sessionGeneration," +
                    "ms=${SystemClock.elapsedRealtime() - createStartedAt}"
            )
            initialStatusPending = true
            statusHandler.postDelayed(showInitialStatusRunnable, INITIAL_STATUS_DELAY_MS)
        } else {
            session = ReaderSession(
                context = this,
                manga = manga,
                title = title,
                viewerWidth = readerWidthPx(),
                viewerHeight = readerHeightPx(),
                autoCut = autoCut,
                reverse = p?.getReverse() == true,
                preparedKey = preparedKey,
                startAtFirstPage = startAtFirstPage,
                listener = activeReaderSessionListener(sessionGeneration),
                strictExactLaunchSeal = exactLaunchSeal
            ).also {
                Log.d(
                    "ViewerPerf",
                    "reader_activity_create_session_done path=$ntkPath,ms=${SystemClock.elapsedRealtime() - createStartedAt}"
                )
                initialStatusPending = true
                statusHandler.postDelayed(showInitialStatusRunnable, INITIAL_STATUS_DELAY_MS)
                val startStartedAt = SystemClock.elapsedRealtime()
                it.start()
                Log.d(
                    "ViewerPerf",
                    "reader_activity_session_start_returned path=$ntkPath,ms=${SystemClock.elapsedRealtime() - startStartedAt}"
                )
            }
        }
        if (exactLaunchSeal != null && ::renderView.isInitialized) {
            // The prestarted strict session can already have decoded pages queued. Do not let
            // those pixels reach SurfaceFlinger until `session` points at the immutable identity
            // provider that proves their episode, manifest and source index. Activating a few
            // statements earlier creates a narrow race where correct pixels are visible but
            // cannot be identity-qualified, especially for very small/fast manga.
            renderView.activateDeferredSurfaceProducer()
        }
    }

    private fun shouldStartNtkNextBoundaryImmediately(direction: Int, anchorPage: Int): Boolean {
        if (direction != ReaderSurfaceView.DIRECTION_NEXT) return false
        if (pageCount <= 0 || anchorPage < pageCount - 1) return false
        if (!::renderView.isInitialized) return false
        val coverage = renderView.visibleCoverageSnapshot() ?: return false
        val viewportReady = coverage.missingPx == 0 &&
            coverage.placeholderPx == 0 &&
            coverage.visibleLoading == 0
        if (!viewportReady) return false
        // Once every canonical image in a strict session is render-ready, deferring the boundary
        // action until input has been quiet for 1.6 s only punishes the normal forward-reading
        // gesture.  Run it immediately: sealed sessions decline adjacent mutation synchronously,
        // while the already committed last image remains visible and interactive.
        if (strictExactLaunchSeal != null) {
            return isCurrentNtkManhwaOrWebtoonPath()
        }
        val now = SystemClock.uptimeMillis()
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs)
        if (
            progressMovedInGesture ||
            readerWindowBusy ||
            (lastActiveMs > 0L && now - lastActiveMs < BOUNDARY_APPEND_QUIET_MS)
        ) {
            Log.d(
                TAG,
                "boundary_append_defer_active_ntk_tail_ready direction=$direction anchor=$anchorPage " +
                    "quietMs=${(BOUNDARY_APPEND_QUIET_MS - (now - lastActiveMs)).coerceAtLeast(0L)}"
            )
            return false
        }
        return isCurrentNtkManhwaOrWebtoonPath()
    }

    private fun shouldPreferHybridForUntrustedNumericPayload(
        manga: Manga,
        path: String,
        nativeEarlyUrls: List<String>,
        nativeTrustedCount: Int
    ): Boolean {
        if (!manga.isOnline) return false
        val match = Regex("^/webtoon/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
            .find(path.trim())
            ?: return false
        val pathWorkId = match.groupValues[1]
        val episodeId = match.groupValues[2]
        val imageWorkId = manga.ntkImageWorkId?.trim().orEmpty()
        val imageEpisodeId = manga.ntkImageEpisodeId?.trim().orEmpty().ifBlank { episodeId }
        if (!imageWorkId.matches(Regex("\\d{1,12}")) || imageWorkId == pathWorkId) return false
        if (imageEpisodeId != episodeId) return false
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        val knownExpected = maxOf(
            manga.ntkImageCount.coerceAtLeast(0),
            nativeEarlyUrls.size,
            ntkCachedViewerPayloadImageCount(path)
        )
        val hasCompleteValidatedNativeManifest = knownExpected > NTK_NUMERIC_PAYLOAD_PARTIAL_HEAD_LIMIT &&
            nativeTrustedCount >= knownExpected &&
            ReaderImageCache.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                knownExpected,
                minCreatedAt
            )
        if (hasCompleteValidatedNativeManifest) return false
        val hasUnvalidatedCanonicalDirect =
            ReaderImageCache.hasUnvalidatedCanonicalDirectEarlyNtkImageUrls(path, minCreatedAt)
        val seededCanonicalHead = nativeEarlyUrls.take(4).any {
            it.contains("/black/episodes/$imageWorkId/$episodeId/p", ignoreCase = true) ||
                it.contains("/blacktoon/episodes/$imageWorkId/$episodeId/p", ignoreCase = true)
        }
        if (!hasUnvalidatedCanonicalDirect && !seededCanonicalHead) return false
        Log.d(
            TAG,
            "reader_ntk_hybrid_prefer_untrusted_numeric_payload path=$path," +
                "pathWork=$pathWorkId,imageWork=$imageWorkId,early=${nativeEarlyUrls.size}," +
                "trusted=$nativeTrustedCount,expected=$knownExpected," +
                "anchor=${ReaderImageCache.hasNtkAnchorAssetForEpisode(manga)}"
        )
        return true
    }

    private fun shouldUseNtkHybridBrowserReader(
        manga: Manga,
        allowGeneratedNumeric: Boolean = false
    ): Boolean {
        if (!manga.isOnline) return false
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        if (isStrictNtkEpisodePath(path)) {
            Log.d(TAG, "reader_ntk_hybrid_gate_strict_exact_owner path=$path")
            return false
        }
        val numericGeneratedPath = path.matches(
            Regex("^/(?:webtoon|manhwa)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
        )
        if (numericGeneratedPath && !allowGeneratedNumeric) {
            Log.d(TAG, "reader_ntk_hybrid_gate_native_numeric_generated path=$path,expected=${manga.ntkImageCount}")
            return false
        }
        if (isNtkKpWebtoonSlugPath(path)) {
            Log.d(TAG, "reader_ntk_hybrid_gate_kp_reader_webview_disabled path=$path,expected=${manga.ntkImageCount}")
            return false
        }
        if (!allowGeneratedNumeric && shouldPreferNativeGeneratedNtkReader(manga)) {
            Log.d(TAG, "reader_ntk_hybrid_gate_native_generated_owner path=$path,expected=${manga.ntkImageCount}")
            return false
        }
        val expected = manga.ntkImageCount
        val nativeAnchorReady = ReaderImageCache.hasNtkAnchorAssetForEpisode(manga)
        if (path.startsWith("/manhwa/") && hasForegroundDirectManifestOwnership(manga, path) && nativeAnchorReady) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_gate_foreground_native_direct_owner path=$path," +
                    "expected=$expected,anchorReady=$nativeAnchorReady"
            )
            return false
        }
        if (path.startsWith("/manhwa/") && hasCompleteNativeDirectManifest(manga) && nativeAnchorReady) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_gate_native_direct_owner path=$path,expected=$expected"
            )
            return false
        }
        if (path.startsWith("/manhwa/") && (hasForegroundDirectManifestOwnership(manga, path) || hasCompleteNativeDirectManifest(manga))) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_gate_native_direct_manifest_owner path=$path," +
                    "expected=$expected,anchorReady=$nativeAnchorReady"
            )
            return false
        }
        Log.d(
            TAG,
            "reader_ntk_hybrid_gate_browser_owner path=$path," +
                "expected=$expected,strictAck=${getHttpClient().hasRecentStrictNtkAdAckProof(path)}"
        )
        return true
    }

    private fun shouldPreferNativeGeneratedNtkReader(manga: Manga): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return false
        if (isNtkKpWebtoonSlugPath(path)) {
            Log.d(TAG, "reader_ntk_native_generated_owner_skip_kp_hybrid path=$path")
            return false
        }
        val urls = ReaderImageCache.earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30_000L)
        val hasAuthoritativeNativeUrls = urls.any { image ->
            image.isNotBlank() &&
                !image.contains("/api/m/i?", ignoreCase = true) &&
                (image.contains("/manhwa/", ignoreCase = true) ||
                    image.contains("/black/episodes/", ignoreCase = true) ||
                    image.contains("/blacktoon/episodes/", ignoreCase = true) ||
                    image.contains("/wt/episodes/", ignoreCase = true) ||
                    isNtkUploadCdnImageUrl(image))
        }
        val numericPath = Regex("^/(manhwa|webtoon)/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
            .find(path)
        if (!manga.ntkImageWorkId.isNullOrBlank() && !manga.ntkImageEpisodeId.isNullOrBlank()) {
            if (numericPath != null &&
                numericPath.groupValues[1].equals("webtoon", ignoreCase = true) &&
                manga.ntkImageWorkId.trim() == numericPath.groupValues[2] &&
                !hasAuthoritativeNativeUrls
            ) {
                Log.d(TAG, "reader_ntk_native_generated_owner_skip_unverified_webtoon_path_id path=$path")
            } else {
                return true
            }
        }
        if (!Regex("^/(?:manhwa|webtoon)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matches(path)) {
            return false
        }
        return hasAuthoritativeNativeUrls
    }

    private fun isNtkKpWebtoonSlugPath(path: String): Boolean {
        return Regex("^/webtoon/\\d{1,12}/kp-[^/?#]+(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matches(path)
    }

    private fun hasCompleteNativeDirectManifest(manga: Manga): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        val expected = manga.ntkImageCount
        if (expected <= 0 || (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))) return false
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        if (ReaderImageCache.trustedNtkImageApiCount(path, minCreatedAt) < expected) return false
        val urls = ReaderImageCache.earlyNtkImageUrls(path, minCreatedAt)
        if (urls.size < expected) return false
        return urls.take(expected).none { it.contains("/api/m/i?", ignoreCase = true) }
    }

    private fun startNtkHybridBrowserReader(
        manga: Manga,
        title: Title?,
        startAtFirstPage: Boolean
    ) {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank()) return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_hybrid_entry")
            Log.d(TAG, "reader_ntk_hybrid_retired_strict_exact path=$path")
            startStrictReaderSessionWhenExactReady(
                manga,
                title,
                null,
                startAtFirstPage = startAtFirstPage,
                clearViewImmediately = true
            )
            return
        }

        // Main-process Browser authority is retired. Non-strict readers retain their native
        // ReaderSession flow without importing a Browser payload or attaching a WebView.
        Log.d(TAG, "reader_ntk_hybrid_retired_native_fallback path=$path")
        startReaderSession(
            manga,
            title,
            null,
            startAtFirstPage = startAtFirstPage,
            clearViewImmediately = true
        )
    }
    private fun isCurrentHybridNtkPath(path: String?): Boolean {
        val currentPath = currentManga?.ntkEpisodePath?.trim().orEmpty()
        val candidate = path?.trim().orEmpty()
        return !isStrictNtkEpisodePath(candidate) &&
            hybridNtkBrowserActive && currentPath.isNotEmpty() && candidate == currentPath
    }

    private fun maybeSwitchHybridToNativeReader(snapshot: NtkBrowserSessionBroker.ImageSnapshot): Boolean {
        if (!hybridNtkBrowserActive || hybridNtkNativeHandoffStarted || destroyed || isFinishing) return false
        if (hybridNtkForceBrowserAuthoritative) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_native_handoff_skip_browser_authoritative path=${snapshot.path}," +
                    "count=${snapshot.images.size},source=${snapshot.source}"
            )
            return false
        }
        val manga = currentManga ?: return false
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank() || snapshot.path != path || snapshot.images.isEmpty()) return false
        if (!isNtkKpWebtoonSlugPath(path) &&
            snapshot.images.any { it.contains("/api/m/i?", ignoreCase = true) }
        ) {
            return false
        }
        val trustedApiExpected = ReaderImageCache.trustedNtkImageApiCount(
            path,
            SystemClock.elapsedRealtime() - 30_000L
        )
        if (isNtkKpWebtoonSlugPath(path)) {
            val manifest = if (isNtkKpWebtoonSlugPath(path)) {
                snapshot.images
            } else {
                snapshot.images.distinct()
            }
            val hasProtectedApi = manifest.any { it.contains("/api/m/i?", ignoreCase = true) }
            val expected = maxOf(
                trustedApiExpected,
                manga.ntkImageCount.coerceAtLeast(0),
                ntkCachedViewerPayloadImageCount(path)
            )
            if (hasProtectedApi && expected >= 4 && manifest.size >= expected) {
                val strictProofReady = try {
                    getHttpClient().hasRecentStrictNtkAdAckProof(path)
                } catch (_: Throwable) {
                    false
                }
                if (strictProofReady) {
                    return switchKpHybridToNativeManifest(
                        path,
                        manifest.take(expected),
                        "protected-api-${snapshot.source}"
                    )
                }
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_native_handoff_defer_protected_no_strict path=$path," +
                        "count=${manifest.size},expected=$expected,source=${snapshot.source}"
                )
            }
            if (snapshot.source.contains("kp-web-primary", ignoreCase = true)) {
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_native_handoff_skip_kp_web_primary_echo path=$path," +
                        "count=${snapshot.images.size},source=${snapshot.source}"
                )
                return false
            }
            val hasDirectCdnManifest = manifest.none { it.contains("/api/m/i?", ignoreCase = true) }
            val hasDirectUploadCdnManifest = hasDirectCdnManifest && manifest.any { isNtkUploadCdnImageUrl(it) }
            if (hasDirectUploadCdnManifest) {
                val directExpected = if (manifest.any { it.contains("/board_uploads/", ignoreCase = true) } &&
                    manifest.size >= 4
                ) {
                    manifest.size
                } else {
                    expected.takeIf { it > 0 } ?: manifest.size
                }
                if (manifest.size < maxOf(4, directExpected)) {
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_native_handoff_defer_kp_direct_partial path=$path," +
                            "count=${manifest.size},expected=$directExpected,source=${snapshot.source}"
                    )
                    return false
                }
                manga.ntkImageCount = directExpected
                pageCount = directExpected
                return switchKpHybridToNativeManifest(
                    path,
                    manifest.take(directExpected),
                    "kp-direct-${snapshot.source}"
                )
            }
            if (hasDirectCdnManifest && trustedApiExpected > 0 && manifest.size >= trustedApiExpected) {
                manga.ntkImageCount = trustedApiExpected
                pageCount = trustedApiExpected
                return switchKpHybridToNativeManifest(
                    path,
                    manifest.take(trustedApiExpected),
                    "kp-native-api-${snapshot.source}"
                )
            }
            if (expected > 0 && snapshot.images.size >= expected) {
                manga.ntkImageCount = manifest.size
                pageCount = manifest.size
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                    path,
                    manifest,
                    "kp-web-primary-${snapshot.source}"
                )
                NtkBrowserSessionBroker.publishAuthoritativeImageUrls(
                    path,
                    manifest,
                    "kp-web-primary-${snapshot.source}"
                )
                if (::pageView.isInitialized) pageView.text = "${currentPage + 1} / $pageCount"
            }
            Log.d(
                TAG,
                "reader_ntk_hybrid_native_handoff_skip_kp_web_primary path=$path," +
                    "count=${snapshot.images.size},trustedExpected=$trustedApiExpected,source=${snapshot.source}"
            )
            return false
        }
        val directCdnImages = snapshot.images.none {
            it.contains("/api/m/i?", ignoreCase = true)
        }
        val trustedKpManifest = isNtkKpWebtoonSlugPath(path) &&
            trustedApiExpected > 0 &&
            snapshot.images.size >= trustedApiExpected
        if (isNtkKpWebtoonSlugPath(path) && !trustedKpManifest && !hasCompleteNativeDirectManifest(manga)) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_native_handoff_skip_kp_browser_owner path=$path," +
                    "count=${snapshot.images.size},trustedExpected=$trustedApiExpected,source=${snapshot.source}"
            )
            return false
        }
        val rawExpected = manga.ntkImageCount.coerceAtLeast(0)
        val expected = when {
            directCdnImages && trustedApiExpected > 0 && snapshot.images.size >= trustedApiExpected -> trustedApiExpected
            directCdnImages && rawExpected > 128 -> snapshot.images.size
            else -> rawExpected
        }
        val enoughImages = if (expected > 0) {
            snapshot.images.size >= expected
        } else {
            snapshot.images.size >= 4
        }
        if (!enoughImages) return false
        hybridNtkNativeHandoffStarted = true
        hybridNtkNativeHandoffPending = false
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            path,
            snapshot.images,
            "native-handoff-${snapshot.source}"
        )
        if (expected <= 0 || manga.ntkImageCount != expected) {
            manga.ntkImageCount = snapshot.images.size
            pageCount = maxOf(pageCount, snapshot.images.size)
        }
        Log.d(
            "ViewerPerf",
            "reader_ntk_hybrid_native_handoff path=$path,count=${snapshot.images.size}," +
                "expected=$expected,rawExpected=$rawExpected,trustedExpected=$trustedApiExpected," +
                "source=${snapshot.source}"
        )
        NtkBrowserSessionBroker.quietForNativeReader(path, "native_handoff")
        stopHybridNtkBackgroundBridges(path, "native_handoff")
        hybridNtkWebView?.visibility = View.GONE
        startReaderSession(
            manga,
            currentTitle ?: manga.title,
            null,
            startAtFirstPage = true,
            clearViewImmediately = true
        )
        session?.requestAllPagesForBrowserManifest()
        return true
    }

    private fun startHybridNtkViewerPayloadBridge(path: String, reason: String) {
        if (!hybridNtkBrowserActive || destroyed || isFinishing || path.isBlank()) return
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return
        val targetManga = currentManga
        AppDispatchers.submitNtkViewerCritical {
            val startedAt = SystemClock.elapsedRealtime()
            val urlsPublished = java.util.concurrent.atomic.AtomicBoolean(false)
            val nativeApiStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            val payloadTokenPrefetchStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            val publishedPayloadKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            fun publishPayload(body: String, code: Int, source: String) {
                val directPayloadUrls = Manga.ntkViewerPayloadImageUrls(body, path).orEmpty()
                val hasTokenPayload = body.contains("imageApiPath") && body.contains("token")
                if (!hasTokenPayload && directPayloadUrls.isEmpty()) return
                val payloadToken = ntkViewerPayloadToken(body)
                val payloadPageCount = maxOf(ntkViewerPayloadImageCount(body), directPayloadUrls.size)
                if (
                    payloadPageCount >= 4 &&
                    payloadToken.isNotBlank() &&
                    isNtkKpWebtoonSlugPath(path) &&
                    payloadTokenPrefetchStarted.compareAndSet(false, true)
                ) {
                    val started = targetManga?.startNtkKpPayloadTokenImagePrefetch(
                        getHttpClient(),
                        path,
                        body,
                        "reader-payload-$reason-$source"
                    ) ?: false
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_payload_token_prefetch_start path=$path," +
                            "started=$started,count=$payloadPageCount,reason=$reason,source=$source," +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    if (!started) {
                        payloadTokenPrefetchStarted.set(false)
                    }
                }
                val payloadKey = "${body.length}:${payloadToken.take(24)}"
                val shouldPublishPayload = publishedPayloadKeys.add(payloadKey)
                if (shouldPublishPayload && hasTokenPayload) {
                    NtkBrowserSessionBroker.publishViewerPayload(
                        path,
                        body,
                        "reader-native-payload-$reason-$source"
                    )
                }
                val parts = path.trim('/').split('/').filter { it.isNotBlank() }
                val kind = parts.getOrNull(0).orEmpty()
                var workId = targetManga?.ntkImageWorkId?.trim().orEmpty()
                    .ifBlank { parts.getOrNull(1).orEmpty() }
                val episodeId = targetManga?.ntkImageEpisodeId?.trim().orEmpty()
                    .ifBlank { parts.getOrNull(2).orEmpty() }
                ntkPayloadCanonicalImageWorkId(path, body, workId).takeIf { it.isNotBlank() }?.let { bodyWorkId ->
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_payload_body_work_id path=$path,old=$workId,body=$bodyWorkId"
                    )
                    workId = bodyWorkId
                    targetManga?.ntkImageWorkId = bodyWorkId
                }
                val partialPayload = (
                    reason.contains("partial", ignoreCase = true) ||
                        source.contains("partial", ignoreCase = true)
                    )
                if (payloadPageCount > 0 && targetManga != null &&
                    !isNtkKpWebtoonSlugPath(path) &&
                    !partialPayload &&
                    (targetManga.ntkImageCount <= 0 || targetManga.ntkImageCount > 128)
                ) {
                    targetManga.ntkImageCount = payloadPageCount
                }
                fun publishUrls(urls: List<String>, urlSource: String) {
                    val verifiedNativeApiSource =
                        urlSource.contains("native-api", ignoreCase = true) ||
                            urlSource.contains("viewer-api-token", ignoreCase = true)
                    val publishCandidates = urls
                    val kpBoardUploadDirect = isNtkKpWebtoonSlugPath(path) &&
                        publishCandidates.size >= 4 &&
                        publishCandidates.any { it.contains("/board_uploads/", ignoreCase = true) } &&
                        publishCandidates.none {
                            it.contains("/api/m/i?", ignoreCase = true) ||
                                isNtkUploadDescriptorUrl(it) ||
                                isNtkKpDescriptorImageUrl(it)
                        }
                    val progressiveNativeSeedCanAcceptPayload =
                        hybridNtkProgressiveNativeSeedActive &&
                            !isNtkKpWebtoonSlugPath(path) &&
                            publishCandidates.isNotEmpty()
                    if (
                        (hybridNtkNativeHandoffStarted || hybridNtkNativeHandoffPending) &&
                        !progressiveNativeSeedCanAcceptPayload
                    ) {
                        if (
                            kpBoardUploadDirect ||
                            (
                                isNtkKpWebtoonSlugPath(path) &&
                            (
                                publishCandidates.any { it.contains("/api/m/i?", ignoreCase = true) } ||
                                    verifiedNativeApiSource
                                )
                                )
                        ) {
                            hybridNtkNativeHandoffPending = false
                        } else {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_skip_handoff_pending path=$path," +
                                "count=${publishCandidates.size},reason=$reason,source=$urlSource"
                        )
                        return
                        }
                    }
                    val knownExpected = targetManga?.ntkImageCount?.coerceAtLeast(0) ?: 0
                    val trustedApiExpected = ReaderImageCache.trustedNtkImageApiCount(
                        path,
                        (startedAt - 1_000L).coerceAtLeast(0L)
                    )
                    val completeKpTokenManifest = isNtkKpWebtoonSlugPath(path) &&
                        payloadPageCount >= 4 &&
                        publishCandidates.size >= payloadPageCount &&
                        publishCandidates.all { it.contains("/api/m/i?", ignoreCase = true) } &&
                        urlSource.contains("token-direct", ignoreCase = true)
                    if (isNtkKpWebtoonSlugPath(path) && trustedApiExpected > 0 && !completeKpTokenManifest) {
                        val trustedManifest = ReaderImageCache.earlyNtkImageUrls(
                            path,
                            (startedAt - 1_000L).coerceAtLeast(0L)
                        ).filterNot { isConstructedNtkViewerCdnUrl(it) }
                        if (trustedManifest.size >= trustedApiExpected) {
                            val canonical = trustedManifest.take(trustedApiExpected)
                            if (
                                isNtkKpWebtoonSlugPath(path) &&
                                canonical.any { isNtkUploadCdnImageUrl(it) } &&
                                !verifiedNativeApiSource &&
                                publishCandidates.size < trustedApiExpected
                            ) {
                                Log.d(
                                    TAG,
                                    "reader_ntk_hybrid_payload_urls_skip_upload_trusted_cache path=$path," +
                                        "incoming=${urls.size},count=${canonical.size},reason=$reason," +
                                        "source=$urlSource"
                                )
                            } else {
                            if (!urlsPublished.compareAndSet(false, true)) return
                            targetManga?.ntkImageCount = canonical.size
                            Log.d(
                                TAG,
                                "reader_ntk_hybrid_payload_urls_publish_trusted_cache path=$path," +
                                    "incoming=${urls.size},count=${canonical.size},reason=$reason," +
                                    "source=$urlSource,ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                            statusHandler.post {
                                if (!isCurrentHybridNtkPath(path) || destroyed || isFinishing) return@post
                                if (switchKpHybridToNativeManifest(
                                        path,
                                        canonical,
                                        "reader-payload-$reason-trusted-api-cache"
                                    )
                                ) {
                                    return@post
                                }
                                handleHybridNtkImagesFromNativePayload(
                                    path,
                                    canonical,
                                    "reader-payload-$reason-trusted-api-cache"
                                )
                            }
                            return
                            }
                        }
                    }
                    val expected = maxOf(knownExpected, trustedApiExpected)
                    val callbackSource = urlSource.contains("callback", ignoreCase = true)
                    val kpHashApiCallback = isNtkKpWebtoonSlugPath(path) &&
                        callbackSource &&
                        publishCandidates.isNotEmpty() &&
                        publishCandidates.none { isConstructedNtkViewerCdnUrl(it) || it.contains("/api/m/i?", ignoreCase = true) }
                    if (publishCandidates.isEmpty()) return
                    if (kpBoardUploadDirect) {
                        if (!urlsPublished.compareAndSet(false, true)) return
                        val canonical = publishCandidates.distinct()
                        targetManga?.ntkImageCount = canonical.size
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_publish_kp_board_direct path=$path," +
                                "count=${canonical.size},payloadPageCount=$payloadPageCount," +
                                "reason=$reason,source=$urlSource,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            path,
                            canonical,
                            "reader-payload-$reason-$urlSource-board-direct"
                        )
                        NtkBrowserSessionBroker.primeImageUrls(
                            path,
                            canonical,
                            "reader-payload-$reason-$urlSource-board-direct"
                        )
                        statusHandler.post {
                            if (!isCurrentHybridNtkPath(path) || destroyed || isFinishing) return@post
                            if (!switchKpHybridToNativeManifest(
                                    path,
                                    canonical,
                                    "reader-payload-$reason-$urlSource-board-direct"
                                )
                            ) {
                                handleHybridNtkImagesFromNativePayload(
                                    path,
                                    canonical,
                                    "reader-payload-$reason-$urlSource-board-direct"
                                )
                            }
                        }
                        return
                    }
                    if (
                        isNtkKpWebtoonSlugPath(path) &&
                        publishCandidates.all { it.contains("/api/m/i?", ignoreCase = true) } &&
                        publishCandidates.size < payloadPageCount &&
                        (
                            urlSource.contains("token-direct", ignoreCase = true) ||
                                urlSource.contains("sourcework-direct", ignoreCase = true)
                            )
                    ) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_skip_kp_protected_direct path=$path," +
                                "count=${publishCandidates.size},reason=$reason,source=$urlSource"
                        )
                        return
                    }
                    val completeProtectedPartial = isNtkKpWebtoonSlugPath(path) &&
                        partialPayload &&
                        payloadPageCount >= 4 &&
                        publishCandidates.size >= payloadPageCount &&
                        publishCandidates.all { it.contains("/api/m/i?", ignoreCase = true) }
                    if (isNtkKpWebtoonSlugPath(path) && partialPayload && !completeProtectedPartial) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_defer_kp_partial_payload path=$path," +
                                "count=${publishCandidates.size},payloadPageCount=$payloadPageCount," +
                                "expected=$expected,knownExpected=$knownExpected,trustedExpected=$trustedApiExpected," +
                                "reason=$reason,source=$urlSource"
                        )
                        return
                    }
                    if (isNtkKpWebtoonSlugPath(path) && publishCandidates.size < maxOf(4, expected)) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_defer_kp_partial path=$path," +
                                "count=${publishCandidates.size},expected=$expected,knownExpected=$knownExpected," +
                                "trustedExpected=$trustedApiExpected,reason=$reason,source=$urlSource"
                        )
                        return
                    }
                    if (callbackSource && trustedApiExpected <= 0 && !kpHashApiCallback) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_defer_untrusted_callback path=$path," +
                                "count=${publishCandidates.size},knownExpected=$knownExpected,reason=$reason," +
                                "source=$urlSource"
                        )
                        return
                    }
                    if (expected > 0 && publishCandidates.size < expected && !kpHashApiCallback) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_urls_defer_partial path=$path," +
                                "count=${publishCandidates.size},expected=$expected,trustedExpected=$trustedApiExpected," +
                                "reason=$reason,source=$urlSource"
                        )
                        return
                    }
                    if (!urlsPublished.compareAndSet(false, true)) return
                    targetManga?.ntkImageCount = publishCandidates.size
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_payload_urls_publish path=$path," +
                            "count=${publishCandidates.size},reason=$reason,source=$urlSource," +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        publishCandidates,
                        "reader-payload-$reason-$urlSource"
                    )
                    handleHybridNtkImagesFromNativePayload(path, publishCandidates, "reader-payload-$reason-$urlSource")
                    NtkBrowserSessionBroker.primeImageUrls(
                        path,
                        publishCandidates,
                        "reader-payload-$reason-$urlSource"
                    )
                    NtkBrowserSessionBroker.publishAuthoritativeImageUrls(
                        path,
                        publishCandidates,
                        "reader-payload-$reason-$urlSource"
                    )
                }
                if (!urlsPublished.get() && payloadPageCount >= 4) {
                    publishUrls(directPayloadUrls, "$source-direct-hint")
                }
                if (!urlsPublished.get() && hasTokenPayload && payloadPageCount >= 4) {
                    val sourceDirectUrls = ntkPayloadSourceWorkDirectImageUrls(
                        path,
                        body,
                        payloadPageCount
                    )
                    publishUrls(sourceDirectUrls, "$source-sourcework-direct")
                }
                if (!urlsPublished.get() && hasTokenPayload && payloadPageCount >= 4) {
                    val tokenDirectUrls = ntkPayloadTokenDirectImageUrls(
                        path,
                        kind,
                        payloadToken,
                        payloadPageCount
                    )
                    publishUrls(tokenDirectUrls, "$source-token-direct")
                }
                val extractedUrls = directPayloadUrls.ifEmpty {
                    CustomHttpClient.extractNtkViewerImageUrlsFromApiBody(
                        body,
                        kind,
                        workId,
                        episodeId
                    ).orEmpty()
                }
                publishUrls(extractedUrls, source)
                if (extractedUrls.isEmpty() && !urlsPublished.get() &&
                    nativeApiStarted.compareAndSet(false, true)
                ) {
                    AppDispatchers.submitNtkViewerCritical {
                        try {
                            val callback =
                                CustomHttpClient.NtkViewerImageUrlsCallback { callbackUrls ->
                                    publishUrls(
                                        callbackUrls.orEmpty(),
                                        "$source-native-api-callback"
                                    )
                                }
                            val nativeUrls = getHttpClient().fetchNtkViewerImageUrls(
                                kind,
                                workId,
                                episodeId,
                                payloadToken,
                                body,
                                path,
                                path,
                                callback
                            ).orEmpty()
                            publishUrls(nativeUrls, "$source-native-api")
                        } catch (t: Throwable) {
                            Log.d(
                                TAG,
                                "reader_ntk_hybrid_payload_bridge_native_api_error path=$path," +
                                    "reason=$reason,source=$source,${t.javaClass.simpleName}:${t.message}"
                            )
                        }
                    }
                }
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_payload_bridge_publish path=$path," +
                        "code=$code,bytes=${body.length},urls=${extractedUrls.size}," +
                        "tokenLen=${payloadToken.length},published=$shouldPublishPayload," +
                        "reason=$reason,source=$source," +
                        "ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
            }
            try {
                targetManga?.ntkViewerPayloadHint?.trim()?.takeIf { it.isNotEmpty() }?.let { hint ->
                    publishPayload(hint, 200, "hint")
                    if (urlsPublished.get()) {
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_payload_bridge_hint_published path=$path," +
                                "reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        return@submitNtkViewerCritical
                    }
                }
                val page = getHttpClient().mgetNtkViewerPayloadPage(path, 0L) { partial ->
                    publishPayload(partial.orEmpty(), 200, "partial")
                }
                val body = page?.body.orEmpty()
                if (body.contains("imageApiPath") && body.contains("token")) {
                    publishPayload(body, page.code, "complete")
                } else {
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_payload_bridge_empty path=$path," +
                            "code=${page?.code ?: 0},bytes=${body.length},reason=$reason," +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            } catch (t: Throwable) {
                Log.d(TAG, "reader_ntk_hybrid_payload_bridge_error path=$path,reason=$reason,$t")
            }
        }
    }

    private fun handleHybridNtkImagesFromNativePayload(path: String, urls: List<String>, source: String) {
        if (urls.isEmpty() || !isCurrentHybridNtkPath(path)) return
        if (isNtkKpWebtoonSlugPath(path) && urls.any { isNtkUploadCdnImageUrl(it) }) {
            statusHandler.post {
                if (!hybridNtkBrowserActive || destroyed || isFinishing) return@post
                if (!isCurrentHybridNtkPath(path)) return@post
                if (switchKpHybridToNativeManifest(path, urls, source)) {
                    return@post
                }
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_hybrid_native_payload_defer_upload_cdn path=$path,count=${urls.size},source=$source"
                )
            }
            return
        }
        startHybridNativePayloadHeadStream(path, urls, source)
        val snapshot = NtkBrowserSessionBroker.ImageSnapshot(
            baseUrl = getHttpClient().getUrl(path),
            path = path,
            documentUrl = getHttpClient().getUrl(path) + path,
            images = urls,
            source = source,
            cloudflare = false,
            createdAtMs = SystemClock.elapsedRealtime()
        )
        statusHandler.postAtFrontOfQueue {
            if (!hybridNtkBrowserActive || destroyed || isFinishing) return@postAtFrontOfQueue
            if (!isCurrentHybridNtkPath(path)) return@postAtFrontOfQueue
            if (isNtkKpWebtoonSlugPath(path) && switchKpHybridToNativeManifest(path, urls, source)) {
                return@postAtFrontOfQueue
            }
            if (hybridNtkProgressiveNativeSeedActive && !isNtkKpWebtoonSlugPath(path)) {
                currentManga?.ntkImageCount = urls.size
                pageCount = maxOf(pageCount, urls.size)
                if (::pageView.isInitialized) pageView.text = "${currentPage + 1} / $pageCount"
                session?.requestAllPagesForBrowserManifest()
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_hybrid_progressive_payload_install path=$path," +
                        "count=${urls.size},source=$source"
                )
                return@postAtFrontOfQueue
            }
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_native_payload_direct path=$path,count=${urls.size},source=$source"
            )
            maybeSwitchHybridToNativeReader(snapshot)
        }
    }

    private fun startHybridNativePayloadHeadStream(path: String, urls: List<String>, source: String) {
        val manga = currentManga ?: return
        if (manga.ntkEpisodePath?.trim().orEmpty() != path) return
        val first = urls.firstOrNull {
            it.isNotBlank() &&
                !it.contains("/api/m/i?", ignoreCase = true) &&
                !isNtkUploadDescriptorUrl(it)
        } ?: return
        val started = ReaderImageCache.startForegroundStreamFetch(
            applicationContext,
            manga,
            first,
            null,
            false,
            null,
            0,
            true
        )
        Log.d(
            "ViewerPerf",
            "reader_ntk_hybrid_native_payload_head_stream path=$path,started=$started," +
                "source=$source,first=${first.substringAfterLast('/')}"
        )
    }

    private fun ntkViewerPayloadToken(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        Regex("\"(?:imagesToken|token)\"\\s*:\\s*\"([^\"]+)\"").find(body)
            ?.groupValues?.getOrNull(1)?.let { return it }
        Regex("\\\\\"(?:imagesToken|token)\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\"").find(body)
            ?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    private fun ntkViewerTokenField(token: String?, field: String): String {
        if (token.isNullOrBlank() || field.isBlank()) return ""
        return try {
            val payload = token.split('.').firstOrNull().orEmpty()
            if (payload.isBlank()) return ""
            val padding = (4 - payload.length % 4) % 4
            val decoded = android.util.Base64.decode(
                payload + "=".repeat(padding),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            org.json.JSONObject(String(decoded, Charsets.UTF_8)).optString(field, "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun ntkPayloadTokenDirectImageUrls(
        path: String,
        kind: String,
        token: String?,
        count: Int
    ): List<String> {
        if (kind != "webtoon" && kind != "manhwa") return emptyList()
        val safeCount = count.coerceIn(1, 128)
        if (isNtkKpWebtoonSlugPath(path) && !token.isNullOrBlank() && token.length > 10) {
            Log.d(
                TAG,
                "reader_ntk_payload_token_direct_skip_kp_protected path=$path,count=$safeCount"
            )
            return emptyList()
        }
        val workId = ntkViewerTokenField(token, "w")
        val episodeId = ntkViewerTokenField(token, "e")
        val numeric = Regex("\\d{1,12}")
        if (!workId.matches(numeric) || !episodeId.matches(numeric)) return emptyList()
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val pathWorkId = parts.getOrNull(1).orEmpty()
        if (kind == "webtoon" && pathWorkId.matches(numeric) && workId == pathWorkId) {
            return emptyList()
        }
        val extension = ntkInitialGeneratedExtensionForPath(kind, pathWorkId, workId)
        return (1..safeCount).map { page ->
            val pageName = "p%03d.%s".format(Locale.ROOT, page, extension)
            if (kind == "manhwa") {
                "https://booktoki9.org/manhwa/$workId/$episodeId/$pageName"
            } else {
                "https://fifa.worldcup73.xyz/black/episodes/$workId/$episodeId/$pageName"
            }
        }
    }

    private fun ntkPayloadSourceWorkDirectImageUrls(
        path: String,
        body: String,
        count: Int
    ): List<String> {
        if (count <= 0) return emptyList()
        val pathMatch = Regex("^/(webtoon|manhwa)/([^/?#]+)/([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(path) ?: return emptyList()
        val kind = pathMatch.groupValues[1].lowercase(Locale.ROOT)
        val pathWorkId = pathMatch.groupValues[2]
        val episodeId = pathMatch.groupValues[3]
        val numeric = Regex("\\d{1,12}")
        if (!episodeId.matches(numeric)) return emptyList()
        val normalized = body
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        val candidates = listOf(
            Regex("""(?i)"refId"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)"sourceWorkId"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)"workId"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)"w"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)"sourceWorkId"\s*:\s*(\d{1,12})""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"refId\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"sourceWorkId\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"workId\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"w\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)/(?:blacktoon|black|wt)/episodes/(\d{1,12})/$episodeId/p\d{3}\.(?:jpg|jpeg|png|webp)""")
                .find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)/(?:blacktoon/)?thumbs/(\d{1,12})\.(?:png|jpg|jpeg|webp)""").find(normalized)?.groupValues?.getOrNull(1)
        )
        val workId = candidates.firstOrNull { !it.isNullOrBlank() && it != pathWorkId }.orEmpty()
        if (!workId.matches(numeric)) return emptyList()
        val directExt = Regex(
            """(?i)/(?:blacktoon|black)/episodes/$workId/$episodeId/p\d{3}\.(jpg|jpeg|png|webp)"""
        ).find(normalized)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        val extension = directExt ?: "jpeg"
        val limit = count.coerceIn(1, 128)
        val base = if (kind == "manhwa") {
            "https://moamoabon.com/blacktoon/episodes/$workId/$episodeId"
        } else {
            "https://fifa.worldcup73.xyz/black/episodes/$workId/$episodeId"
        }
        val urls = (1..limit).map { page ->
            "$base/p${page.toString().padStart(3, '0')}.$extension"
        }
        Log.d(
            TAG,
            "reader_ntk_payload_sourcework_direct path=$path,count=${urls.size}," +
                "workId=$workId,pathWorkId=$pathWorkId,episodeId=$episodeId,ext=$extension"
        )
        return urls
    }

    private fun ntkPayloadCanonicalImageWorkId(path: String, body: String, currentWorkId: String): String {
        val pathMatch = Regex("^/webtoon/(\\d{1,12})/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
            .find(path.trim()) ?: return ""
        val pathWorkId = pathMatch.groupValues[1]
        val current = currentWorkId.trim()
        if (current.isNotEmpty() && current != pathWorkId) return ""
        val normalized = body
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        val candidates = listOf(
            Regex("""(?i)"refId"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)"sourceWorkId"\s*:\s*"?(\d{1,12})"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"refId\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)\\"sourceWorkId\\"\s*:\s*\\"?(\d{1,12})\\"?""").find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)/(?:blacktoon/)?thumbs/(\d{1,12})\.(?:png|jpg|jpeg|webp)""")
                .find(normalized)?.groupValues?.getOrNull(1),
            Regex("""(?i)https?://(?:[^/]+\.)?(?:g\d+cm\.net|scloud\d+\.com|vcloud\d+\.com|cloudfront\.net)/(\d{1,12})/[^"' <>\s]+\.(?:png|jpg|jpeg|webp)""")
                .find(normalized)?.groupValues?.getOrNull(1)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it != pathWorkId }.orEmpty()
    }

    private fun startHybridNtkNativeImageListBridge(manga: Manga, path: String, reason: String) {
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_hybrid_native_bridge_$reason")
            return
        }
        if (!hybridNtkBrowserActive || destroyed || isFinishing || path.isBlank()) return
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return
        if (hybridNtkNativeImageFetchPath == path) return
        hybridNtkNativeImageFetchPath = path
        val target = manga
        AppDispatchers.submitNtkViewerCritical {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val client = getHttpClient()
                if (path.startsWith("/webtoon/") &&
                    !isNtkKpWebtoonSlugPath(path) &&
                    !client.hasRecentStrictNtkAdAckProof(path)
                ) {
                    client.performModernWebtoonDirectAdAckWarmup(path)
                } else if (isNtkKpWebtoonSlugPath(path)) {
                    Log.d(TAG, "reader_ntk_hybrid_native_image_bridge_skip_ack_warm path=$path")
                }
                val result = Manga.fetchWithTemporaryNtkViewerFetchMode(target, client, "api-strict")
                val urls = target.getImgs(applicationContext)
                    .orEmpty()
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()
                if (urls.isEmpty()) {
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_native_image_bridge_empty path=$path," +
                            "result=$result,reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return@submitNtkViewerCritical
                }
                target.ntkImageCount = urls.size
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                    path,
                    urls,
                    "reader-native-image-bridge-$reason"
                )
                NtkBrowserSessionBroker.publishAuthoritativeImageUrls(
                    path,
                    urls,
                    "reader-native-image-bridge-$reason"
                )
                AppDispatchers.runOnMain {
                    if (!isCurrentHybridNtkPath(path) || destroyed || isFinishing) return@runOnMain
                    if (isNtkKpWebtoonSlugPath(path) &&
                        switchKpHybridToNativeManifest(path, urls, "reader-native-image-bridge-$reason")
                    ) {
                        return@runOnMain
                    }
                    pageCount = maxOf(pageCount, urls.size)
                    if (::pageView.isInitialized) pageView.text = "${currentPage + 1} / $pageCount"
                }
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_native_image_bridge_publish path=$path," +
                        "count=${urls.size},result=$result,reason=$reason," +
                        "ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
            } catch (t: Throwable) {
                Log.d(TAG, "reader_ntk_hybrid_native_image_bridge_error path=$path,reason=$reason,$t")
            }
        }
    }

    private fun isHybridNtkViewerApiBlocked(bodySample: String): Boolean {
        val body = bodySample.lowercase(Locale.ROOT)
        if (!body.contains("viewer-api")) return false
        return body.contains(" 403") ||
            body.contains(" 428") ||
            body.contains("browser_key_required") ||
            body.contains("access denied") ||
            body.contains("cloudflare")
    }

    private fun startHybridNtkEarlyImageUrlBridge(manga: Manga, path: String) {
        hybridNtkEarlyUrlPollPath = path
        hybridNtkEarlyUrlPollAttempts = 0
        statusHandler.removeCallbacks(hybridNtkEarlyUrlPollRunnable)
        hybridNtkEarlyUrlListenerDisposer?.invoke()
        hybridNtkEarlyUrlListenerDisposer = ReaderImageCache.addEarlyNtkImageUrlsSourceListener { changedPath, urls, source ->
            if (!hybridNtkBrowserActive || destroyed || isFinishing) return@addEarlyNtkImageUrlsSourceListener
            if (changedPath != hybridNtkEarlyUrlPollPath) return@addEarlyNtkImageUrlsSourceListener
            if (source.startsWith("early-cache-listener-", ignoreCase = true)) return@addEarlyNtkImageUrlsSourceListener
            if (source.contains("kp-web-primary", ignoreCase = true)) return@addEarlyNtkImageUrlsSourceListener
            if (source.contains("kp-native-manifest", ignoreCase = true)) return@addEarlyNtkImageUrlsSourceListener
            if (source.contains("foreground-viewer-images", ignoreCase = true)) return@addEarlyNtkImageUrlsSourceListener
            val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(
                changedPath,
                SystemClock.elapsedRealtime() - 30_000L
            )
            val canonicalKpManifestSource = !isNtkKpWebtoonSlugPath(changedPath) ||
                isCanonicalKpNativeManifestSource(source)
            val directKpUploadHead = if (isNtkKpWebtoonSlugPath(changedPath)) {
                if (!canonicalKpManifestSource) emptyList() else urls
                    .filterNot { isConstructedNtkViewerCdnUrl(it) }
                    .filter { isNtkUploadCdnImageUrl(it) }
                    .filterNot { isNtkUploadDescriptorUrl(it) }
                    .distinct()
            } else {
                emptyList()
            }
            val partialDirectKpHead = directKpUploadHead.isNotEmpty() &&
                directKpUploadHead.size < 4 &&
                (source.contains("partial", ignoreCase = true) ||
                    source.contains("first", ignoreCase = true)) &&
                directKpUploadHead.none { isNtkUploadDescriptorUrl(it) }
            val fullDirectKpHead = directKpUploadHead.size >= 4 &&
                directKpUploadHead.none { isNtkUploadDescriptorUrl(it) }
            val trustedKpApiManifest = isNtkKpWebtoonSlugPath(changedPath) &&
                canonicalKpManifestSource &&
                trustedApiCount > 0 &&
                urls.size >= trustedApiCount
            if (!isHybridEarlyUrlSourceAuthoritative(source) &&
                !trustedKpApiManifest &&
                !partialDirectKpHead &&
                !fullDirectKpHead
            ) {
                return@addEarlyNtkImageUrlsSourceListener
            }
            val authoritativeUrls = if (partialDirectKpHead) {
                directKpUploadHead
            } else if (fullDirectKpHead) {
                directKpUploadHead
            } else if (trustedKpApiManifest) {
                urls
            } else {
                urls.filterNot { isConstructedNtkViewerCdnUrl(it) }
            }
            if (authoritativeUrls.isEmpty()) return@addEarlyNtkImageUrlsSourceListener
            val directKpCdnManifest = isNtkKpWebtoonSlugPath(changedPath) &&
                (authoritativeUrls.size >= 4 || partialDirectKpHead) &&
                authoritativeUrls.none { it.contains("/api/m/i?", ignoreCase = true) } &&
                authoritativeUrls.any { isNtkUploadCdnImageUrl(it) } &&
                authoritativeUrls.none { isNtkUploadDescriptorUrl(it) }
            if (trustedKpApiManifest) {
                hybridNtkNativeHandoffPending = true
            }
            Log.d(
                TAG,
                "reader_ntk_hybrid_early_url_bridge_listener_seen path=$changedPath," +
                    "count=${authoritativeUrls.size},source=$source,partialDirect=$partialDirectKpHead," +
                    "trustedApi=$trustedApiCount"
            )
            val dispatch = Runnable {
                if (!hybridNtkBrowserActive || destroyed || isFinishing) return@Runnable
                if (changedPath != hybridNtkEarlyUrlPollPath && !directKpCdnManifest && !trustedKpApiManifest) {
                    return@Runnable
                }
                if ((directKpCdnManifest || trustedKpApiManifest) && !isCurrentHybridNtkPath(changedPath)) {
                    return@Runnable
                }
                val publishSource = if (source.isBlank() && trustedKpApiManifest) {
                    "trusted-api-early-cache"
                } else {
                    source
                }
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_early_url_bridge_listener_publish path=$changedPath," +
                        "count=${authoritativeUrls.size},source=$publishSource,trustedApi=$trustedApiCount"
                )
                if ((trustedKpApiManifest || directKpCdnManifest) &&
                    switchKpHybridToNativeManifest(changedPath, authoritativeUrls, publishSource)
                ) {
                    return@Runnable
                }
                NtkBrowserSessionBroker.publishAuthoritativeImageUrls(
                    changedPath,
                    authoritativeUrls,
                    "early-cache-listener-$publishSource"
                )
            }
            if (partialDirectKpHead || fullDirectKpHead || directKpCdnManifest || trustedKpApiManifest) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    dispatch.run()
                } else {
                    criticalUiHandler.post(dispatch)
                }
            } else {
                statusHandler.post(dispatch)
            }
            if (directKpCdnManifest || trustedKpApiManifest) {
                try {
                    manga.startNtkKpDirectManifestHeadStream(
                        getHttpClient(),
                        changedPath,
                        authoritativeUrls,
                        "early-url-$source"
                    )
                } catch (e: Throwable) {
                    Log.d(TAG, "reader_ntk_hybrid_early_url_head_stream_error path=$changedPath,$e")
                }
            }
        }
        if (path.startsWith("/manhwa/") || path.startsWith("/webtoon/")) {
            if (isNtkKpWebtoonSlugPath(path)) {
                Log.d(TAG, "reader_ntk_hybrid_early_url_bridge_kp_native_prefetch_skip_browser_owned path=$path")
            }
            Log.d(
                TAG,
                "reader_ntk_hybrid_early_url_bridge_foreground_only path=$path,count=${manga.ntkImageCount}"
            )
        } else {
            try {
                startStrictNtkDiscovery(manga, "retired_hybrid_early_probe")
                Log.d(TAG, "reader_ntk_hybrid_early_url_bridge_start path=$path,count=${manga.ntkImageCount}")
            } catch (e: Exception) {
                Log.d(TAG, "reader_ntk_hybrid_early_url_bridge_error path=$path,$e")
            }
        }
        pollHybridNtkEarlyImageUrls()
    }

    private fun isCanonicalKpNativeManifestSource(source: String): Boolean {
        val lower = source.lowercase(Locale.ROOT)
        if (lower.contains("sourcework-direct")) return false
        return lower.contains("viewer-api-token") ||
            lower.contains("token-prefetch") ||
            lower.contains("kp-synthetic-token") ||
            lower.contains("native-api") ||
            lower.contains("viewer-api-token-result")
    }

    private fun switchKpHybridToNativeManifest(path: String, urls: List<String>, source: String): Boolean {
        if (!isNtkKpWebtoonSlugPath(path)) return false
        if (source.equals("trusted-api-poll-cache", ignoreCase = true)) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_skip_indirect_trusted_cache path=$path," +
                    "count=${urls.size},source=$source"
            )
            hybridNtkNativeHandoffPending = false
            return false
        }
        if (!hybridNtkBrowserActive || hybridNtkNativeHandoffStarted || destroyed || isFinishing) return false
        if (!isCurrentHybridNtkPath(path) || urls.isEmpty()) return false
        val manga = currentManga ?: return false
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(path, minCreatedAt)
        var manifest = ArrayList(if (isNtkKpWebtoonSlugPath(path)) urls else urls.distinct())
        val boardUploadManifest = manifest.any { it.contains("/board_uploads/", ignoreCase = true) }
        var directCdnManifest = manifest.none { it.contains("/api/m/i?", ignoreCase = true) } &&
            manifest.any { isNtkUploadCdnImageUrl(it) }
        var progressiveDirectHeadHandoff = false
        if (!directCdnManifest && manifest.any { it.contains("/api/m/i?", ignoreCase = true) }) {
            val protectedExpected = maxOf(
                trustedApiCount,
                manga.ntkImageCount.coerceAtLeast(0),
                ntkCachedViewerPayloadImageCount(path),
                manifest.size
            )
            val protectedReady = manifest.size >= maxOf(4, protectedExpected) && try {
                getHttpClient().hasRecentStrictNtkAdAckProof(path)
            } catch (_: Throwable) {
                false
            }
            val cached = ReaderImageCache.earlyNtkImageUrls(path, minCreatedAt)
                .filterNot { isConstructedNtkViewerCdnUrl(it) }
                .distinct()
            val cachedDirect = cached.filter { isNtkUploadCdnImageUrl(it) && !isNtkUploadDescriptorUrl(it) }
            if (
                cachedDirect.size >= manifest.size ||
                    (cachedDirect.size >= 4 && cachedDirect.size < manifest.size)
            ) {
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_hybrid_kp_native_manifest_use_cached_upload_head path=$path," +
                        "incoming=${manifest.size},cached=${cachedDirect.size},source=$source"
                )
                manifest = ArrayList(cachedDirect)
                directCdnManifest = manifest.none { it.contains("/api/m/i?", ignoreCase = true) } &&
                    manifest.any { isNtkUploadCdnImageUrl(it) }
                progressiveDirectHeadHandoff = cachedDirect.size < protectedExpected
            }
            if (!directCdnManifest && !protectedReady) {
                val protectedRetry = ArrayList(manifest)
                statusHandler.postDelayed({
                    if (!hybridNtkBrowserActive || hybridNtkNativeHandoffStarted || destroyed || isFinishing) return@postDelayed
                    if (!isCurrentHybridNtkPath(path)) return@postDelayed
                    val retryDirect = ReaderImageCache.earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30_000L)
                        .filterNot { isConstructedNtkViewerCdnUrl(it) }
                        .filter { isNtkUploadCdnImageUrl(it) && !isNtkUploadDescriptorUrl(it) }
                        .distinct()
                    if (retryDirect.size >= 4) {
                        switchKpHybridToNativeManifest(path, retryDirect, "cached-direct-after-protected-$source")
                    } else if (try {
                            getHttpClient().hasRecentStrictNtkAdAckProof(path)
                        } catch (_: Throwable) {
                            false
                        }
                    ) {
                        switchKpHybridToNativeManifest(path, protectedRetry, "protected-after-ack-$source")
                    }
                }, 180L)
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_hybrid_kp_native_manifest_defer_protected_wait_direct path=$path," +
                        "incoming=${manifest.size},cachedDirect=${cachedDirect.size},source=$source"
                )
                hybridNtkNativeHandoffPending = false
                return false
            }
        }
        val rawExpectedCount = if (boardUploadManifest && manifest.size >= 4) {
            manifest.size
        } else {
            maxOf(
                trustedApiCount,
                manga.ntkImageCount.coerceAtLeast(0),
                ntkCachedViewerPayloadImageCount(path),
                manifest.size
            )
        }
        val partialDirectHeadSource =
            source.contains("partial", ignoreCase = true) ||
                source.contains("first", ignoreCase = true)
        val minDirectHeadHandoffCount = if (partialDirectHeadSource) 1 else 4
        if (!progressiveDirectHeadHandoff &&
            directCdnManifest &&
            manifest.size >= minDirectHeadHandoffCount &&
            manifest.size < rawExpectedCount
        ) {
            progressiveDirectHeadHandoff = true
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_progressive_direct_head path=$path," +
                    "count=${manifest.size},expected=$rawExpectedCount,source=$source"
            )
        }
        val expectedCount = if (progressiveDirectHeadHandoff) manifest.size else rawExpectedCount
        if (expectedCount < 4 && !progressiveDirectHeadHandoff) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_defer_unknown_expected path=$path," +
                    "count=${urls.size},expected=$expectedCount,trusted=$trustedApiCount,source=$source"
            )
            return false
        }
        if (manifest.size < expectedCount) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_defer_partial path=$path," +
                    "count=${manifest.size},raw=${urls.size},expected=$expectedCount,source=$source"
            )
            return false
        }
        val protectedApiManifest = manifest.any { it.contains("/api/m/i?", ignoreCase = true) }
        if (protectedApiManifest) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_skip_protected_browser_owned path=$path," +
                    "count=${manifest.size},expected=$expectedCount,source=$source"
            )
            hybridNtkNativeHandoffPending = false
            return false
        }
        val descriptorManifest = manifest.any { isNtkUploadDescriptorUrl(it) || isNtkKpDescriptorImageUrl(it) }
        if (descriptorManifest) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_kp_native_manifest_skip_descriptor_browser_owned path=$path," +
                    "count=${manifest.size},expected=$expectedCount,source=$source"
            )
            hybridNtkNativeHandoffPending = false
            return false
        }
        manga.setImgs(manifest)
        manga.ntkImageCount = manifest.size
        pageCount = manifest.size
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            path,
            manifest,
            "kp-native-manifest-$source"
        )
        Log.d(
            "ViewerPerf",
            "reader_ntk_hybrid_kp_native_manifest path=$path,count=${manifest.size}," +
                "progressiveDirectHead=$progressiveDirectHeadHandoff,source=$source"
        )
        hybridNtkNativeHandoffStarted = true
        hybridNtkNativeHandoffPending = false
        NtkBrowserSessionBroker.quietForNativeReader(path, "kp_native_manifest")
        stopHybridNtkBackgroundBridges(path, "kp_native_manifest")
        hybridNtkWebView?.visibility = View.GONE
        startReaderSession(
            manga,
            currentTitle ?: manga.title,
            null,
            startAtFirstPage = true,
            clearViewImmediately = true
        )
        session?.requestAllPagesForBrowserManifest()
        if (::pageView.isInitialized) pageView.text = "${currentPage + 1} / $pageCount"
        return true
    }

    private fun isNtkUploadCdnImageUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.ROOT).orEmpty()
        if (isNtkUploadDescriptorUrl(lower)) return false
        return Regex(
            "^https?://[^/?#]+/(?:webtoon_uploads|manhwa_uploads|comic_uploads|board_uploads)/[^?#]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#].*)?$"
        ).matches(lower) ||
            Regex("^https?://(?:[^/?#]+\\.)?messiimage\\.online/[^/?#]+\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$").matches(lower) ||
            Regex("^https?://[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#].*)?$").matches(lower)
    }

    private fun isNtkUploadTextImageUrl(url: String?): Boolean {
        return isNtkUploadDescriptorUrl(url)
    }

    private fun isNtkUploadDescriptorUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.ROOT).orEmpty()
        if (isNtkKpDescriptorImageUrl(lower)) return true
        return Regex(".*(?:/webtoon_uploads/|/manhwa_uploads/|/comic_uploads/|messiimage\\.online/|aws-cdn\\d*\\.site/)[^/?#]+\\.(?:txt|xml|json|css|js)(?:[?#].*)?$").matches(lower)
    }

    private fun isNtkKpDescriptorImageUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.ROOT)
            ?.replace("\\/", "/")
            ?.replace("\\u002f", "/")
            .orEmpty()
        return Regex("^(?:https?://)?[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\.(?:txt|xml|json|css|js|woff|woff2)(?:[?#].*)?$")
            .matches(lower)
    }

    private fun isTrustedNtkUploadPayloadImageUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.ROOT).orEmpty()
        return Regex(
            "^https?://(?:[^/?#]+\\.)?(?:hkhk\\d+\\.store|ronald\\d+\\.online|christ\\d+\\.shop|aws-cdn\\d*\\.site|messiimage\\.online|fvcdn\\d*\\.com|flysky\\d*m\\.com|apihost\\d*\\.com)/(?:webtoon_uploads|manhwa_uploads|comic_uploads)/[^/?#]+\\.(?:txt|xml)(?:[?#].*)?$"
        ).matches(lower)
    }

    private fun stopHybridNtkBackgroundBridges(path: String, reason: String) {
        statusHandler.removeCallbacks(hybridNtkEarlyUrlPollRunnable)
        hybridNtkEarlyUrlListenerDisposer?.invoke()
        hybridNtkEarlyUrlListenerDisposer = null
        if (hybridNtkEarlyUrlPollPath == path) {
            hybridNtkEarlyUrlPollPath = ""
        }
        hybridNtkEarlyUrlPollAttempts = 0
        hybridNtkNativeImageFetchPath = ""
        Log.d(TAG, "reader_ntk_hybrid_background_bridges_stop path=$path,reason=$reason")
    }

    private fun isHybridEarlyUrlSourceAuthoritative(source: String): Boolean {
        val s = source.lowercase(Locale.ROOT)
        return s.contains("resolved") ||
            s.contains("verified") ||
            s.contains("native-api") ||
            s.contains("viewer-api-token") ||
            s.contains("kp-signed-token") ||
            s.contains("token-direct") ||
            s.contains("early-cache") ||
            s.contains("episode-visible-prepared") ||
            s.contains("canonical-direct")
    }

    private fun pollHybridNtkEarlyImageUrls() {
        val manga = currentManga ?: return
        val path = hybridNtkEarlyUrlPollPath.ifBlank { manga.ntkEpisodePath?.trim().orEmpty() }
        if (!hybridNtkBrowserActive || destroyed || isFinishing || path.isBlank()) return
        val expected = manga.ntkImageCount.coerceAtLeast(0)
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        val verifiedGeneratedUrls = ReaderImageCache.earlyNtkGeneratedSuccessImageUrls(path, minCreatedAt)
            .filterNot { isConstructedNtkViewerCdnUrl(it) }
        if (verifiedGeneratedUrls.isNotEmpty() && (expected <= 0 || verifiedGeneratedUrls.size >= expected)) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_early_url_bridge_publish_verified path=$path," +
                    "count=${verifiedGeneratedUrls.size},expected=$expected"
            )
            NtkBrowserSessionBroker.publishAuthoritativeImageUrls(path, verifiedGeneratedUrls, "verified-generated")
            return
        }
        val urls = ReaderImageCache.earlyNtkImageUrls(path, minCreatedAt)
        val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(path, minCreatedAt)
        val authoritativeUrls = if (isNtkKpWebtoonSlugPath(path) && trustedApiCount > 0 && urls.size >= trustedApiCount) {
            urls
        } else {
            urls.filterNot { isConstructedNtkViewerCdnUrl(it) }
        }
        val trustedKpApiManifest = isNtkKpWebtoonSlugPath(path) &&
            trustedApiCount > 0 &&
            authoritativeUrls.size >= trustedApiCount
        val directKpUploadHead = if (isNtkKpWebtoonSlugPath(path)) {
            authoritativeUrls
                .filter { isNtkUploadCdnImageUrl(it) && !isNtkUploadDescriptorUrl(it) }
                .distinct()
        } else {
            emptyList()
        }
        val directKpCdnManifest = isNtkKpWebtoonSlugPath(path) &&
            authoritativeUrls.isNotEmpty() &&
            authoritativeUrls.none { it.contains("/api/m/i?", ignoreCase = true) } &&
            authoritativeUrls.any { isNtkUploadCdnImageUrl(it) } &&
            authoritativeUrls.none { isNtkUploadDescriptorUrl(it) } &&
            authoritativeUrls.size >= 4
        val partialDirectKpPollManifest = directKpUploadHead.isNotEmpty() &&
            directKpUploadHead.size < 4 &&
            !authoritativeUrls.any { it.contains("/api/m/i?", ignoreCase = true) }
        val publishableAuthoritativeUrls = if (partialDirectKpPollManifest) {
            directKpUploadHead
        } else {
            authoritativeUrls
        }
        if (publishableAuthoritativeUrls.isNotEmpty() &&
            ((expected <= 0 || publishableAuthoritativeUrls.size >= expected) ||
                directKpCdnManifest ||
                partialDirectKpPollManifest)
        ) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_early_url_bridge_publish path=$path," +
                    "count=${publishableAuthoritativeUrls.size},expected=$expected,trustedApi=$trustedApiCount," +
                    "partialDirect=$partialDirectKpPollManifest"
            )
            val switchSource = if (partialDirectKpPollManifest) {
                "partial-direct-poll-cache"
            } else {
                "trusted-api-poll-cache"
            }
            if ((trustedKpApiManifest || directKpCdnManifest || partialDirectKpPollManifest) &&
                switchKpHybridToNativeManifest(path, publishableAuthoritativeUrls, switchSource)
            ) {
                return
            }
            NtkBrowserSessionBroker.publishAuthoritativeImageUrls(path, publishableAuthoritativeUrls, "early-cache")
            return
        }
        hybridNtkEarlyUrlPollAttempts += 1
        if (hybridNtkEarlyUrlPollAttempts <= 140) {
            val delay = when {
                hybridNtkEarlyUrlPollAttempts <= 12 -> 80L
                hybridNtkEarlyUrlPollAttempts <= 60 -> 180L
                else -> 350L
            }
            statusHandler.postDelayed(hybridNtkEarlyUrlPollRunnable, delay)
        } else {
            Log.d(
                TAG,
                "reader_ntk_hybrid_early_url_bridge_miss path=$path,count=${authoritativeUrls.size}," +
                    "verified=${verifiedGeneratedUrls.size},expected=$expected"
            )
        }
    }

    private fun isConstructedNtkViewerCdnUrl(url: String?): Boolean {
        val lower = url?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return Regex("^https?://apihost\\d*\\.com/manhwa/\\d+/\\d+/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$").matches(lower) ||
            Regex("^https?://[a-z0-9-]+\\.worldcup\\d+\\.xyz/(?:black|blacktoon)/episodes/\\d+/\\d+/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$").matches(lower)
    }

    private fun startHybridNtkAckRecovery(path: String, reason: String) {
        val manga = currentManga
        if (manga != null && isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(manga, "retired_hybrid_ack_$reason")
        } else {
            Log.d(TAG, "reader_ntk_hybrid_ack_retired reason=$reason,path=$path")
        }
    }
    private fun primeHybridNtkImageCandidates(manga: Manga, path: String) {
        val kpSlug = isNtkKpWebtoonSlugPath(path)
        val hasGeneratedNtkPattern = (path.startsWith("/webtoon/") || path.startsWith("/manhwa/")) &&
            !manga.ntkImageWorkId.isNullOrBlank() &&
            !manga.ntkImageEpisodeId.isNullOrBlank()
        val unverifiedCanonicalWebtoonPathId = isUnverifiedCanonicalWebtoonPathId(manga, path)
        val cachedPayloadUrls = ntkCachedViewerPayloadImageUrls(manga, path)
        val hasRenderableCachedPayload = cachedPayloadUrls.any {
            it.isNotBlank() &&
                !it.contains("/api/m/i?", ignoreCase = true) &&
                !isNtkUploadDescriptorUrl(it) &&
                isNtkUploadCdnImageUrl(it)
        }
        val payloadUrls = when {
            !kpSlug && hasGeneratedNtkPattern && hasRenderableCachedPayload -> cachedPayloadUrls
            !kpSlug && hasGeneratedNtkPattern && !unverifiedCanonicalWebtoonPathId -> emptyList()
            else -> cachedPayloadUrls
        }
        val generatedCandidates = if (payloadUrls.isEmpty() && !kpSlug && !unverifiedCanonicalWebtoonPathId) {
            hybridNtkImageCandidates(manga, path)
        } else {
            emptyList()
        }
        val candidates = if (payloadUrls.isNotEmpty()) payloadUrls else generatedCandidates
        if (candidates.isEmpty()) {
            if (unverifiedCanonicalWebtoonPathId) {
                Log.d(
                    "ViewerPerf",
                    "reader_ntk_hybrid_candidate_skip_unverified_webtoon_path_id path=$path," +
                        "imageWork=${manga.ntkImageWorkId},imageEpisode=${manga.ntkImageEpisodeId}"
                )
            }
            return
        }
        if (payloadUrls.isEmpty() && manga.ntkImageCount > candidates.size) {
            Log.d(
                "ViewerPerf",
                "reader_ntk_hybrid_candidate_expected_clamp path=$path," +
                    "from=${manga.ntkImageCount},to=${candidates.size}"
            )
            manga.ntkImageCount = candidates.size
        }
        Log.d(
            "ViewerPerf",
                "reader_ntk_hybrid_candidate_prime path=$path,count=${candidates.size}," +
                "known=${manga.ntkImageCount},imageWork=${manga.ntkImageWorkId}," +
                "imageEpisode=${manga.ntkImageEpisodeId},source=" +
                if (payloadUrls.isNotEmpty()) "payload-src" else "generated-pattern"
        )
        ReaderImageCache.rememberEarlyNtkImageUrls(path, candidates)
        NtkBrowserSessionBroker.primeImageUrls(
            path,
            candidates,
            if (payloadUrls.isNotEmpty()) "payload-src" else "candidate"
        )
    }

    private fun isUnverifiedCanonicalWebtoonPathId(manga: Manga, path: String): Boolean {
        val match = Regex("^/webtoon/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
            .find(path.trim())
            ?: return false
        val pathWorkId = match.groupValues[1]
        val imageWorkId = manga.ntkImageWorkId?.trim().orEmpty()
        if (imageWorkId != pathWorkId) return false
        val expected = manga.ntkImageCount.coerceAtLeast(0)
        val hasAuthoritativeNativeUrls = expected > 0 &&
            ReaderImageCache.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                expected,
                SystemClock.elapsedRealtime() - 30_000L
        )
        return !hasAuthoritativeNativeUrls
    }

    private fun seedCachedNtkPayloadManifestIfAvailable(manga: Manga, path: String, reason: String): Boolean {
        val payload = ntkCachedViewerPayload(path)
        if (payload.isBlank()) return false
        val urls = ntkCachedViewerPayloadImageUrls(manga, path)
            .filter {
                it.isNotBlank() &&
                    !it.contains("/api/m/i?", ignoreCase = true) &&
                    !isNtkUploadDescriptorUrl(it)
            }
        if (urls.isEmpty()) return false
        if (manga.ntkImageCount <= 0 || manga.ntkImageCount > urls.size) {
            manga.ntkImageCount = urls.size
        }
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            path,
            urls,
            "cached-payload-direct-$reason"
        )
        NtkBrowserSessionBroker.publishViewerPayload(path, payload, "reader-cached-payload-$reason")
        NtkBrowserSessionBroker.primeImageUrls(path, urls, "cached-payload-direct-$reason")
        Log.d(
            TAG,
            "reader_ntk_seed_cached_payload_manifest path=$path,count=${urls.size}," +
                "reason=$reason,first=${urls.firstOrNull()?.substringAfterLast('/') ?: ""}"
        )
        return true
    }

    private fun ntkCachedViewerPayloadImageUrls(manga: Manga, path: String): List<String> {
        val payload = ntkCachedViewerPayload(path)
        if (payload.isBlank()) return emptyList()
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 3) return emptyList()
        val kind = parts[0]
        val workId = manga.ntkImageWorkId?.trim().orEmpty().ifBlank { parts[1] }
        val episodeId = manga.ntkImageEpisodeId?.trim().orEmpty().ifBlank { parts[2] }
        return try {
            val extracted = CustomHttpClient.extractNtkViewerImageUrlsFromApiBody(
                payload,
                kind,
                workId,
                episodeId
            ).orEmpty()
            val payloadDirect = Manga.ntkViewerPayloadImageUrls(payload, path).orEmpty()
            fun renderablePayloadDirect(urls: List<String>): List<String> {
                return urls
                    .filter {
                        it.isNotBlank() &&
                            !it.contains("/api/m/i?", ignoreCase = true) &&
                            !isNtkUploadDescriptorUrl(it) &&
                            isNtkUploadCdnImageUrl(it)
                    }
                    .distinct()
            }
            val extractedRenderable = renderablePayloadDirect(extracted)
            val payloadRenderable = renderablePayloadDirect(payloadDirect)
            val extractedHasGeneratedOrDescriptor = extracted.any {
                it.contains("/api/m/i?", ignoreCase = true) ||
                    isNtkUploadDescriptorUrl(it) ||
                    isConstructedNtkViewerCdnUrl(it)
            }
            val urls = if (
                payloadRenderable.isNotEmpty() &&
                (extractedRenderable.isEmpty() ||
                    payloadRenderable.size >= extractedRenderable.size ||
                    extractedHasGeneratedOrDescriptor)
            ) {
                payloadRenderable
            } else if (extractedRenderable.isNotEmpty()) {
                extractedRenderable
            } else if (extracted.isNotEmpty()) {
                extracted
            } else {
                payloadDirect
            }
            val payloadExpected = ntkViewerPayloadImageCount(payload).takeIf { it > 0 && it <= 300 }
            val mangaExpected = manga.ntkImageCount.takeIf {
                it > 0 && it != 64 && it != 128 && it <= 300
            }
            val hasRenderablePayload = urls.any {
                it.isNotBlank() &&
                    !isNtkUploadDescriptorUrl(it) &&
                    isNtkUploadCdnImageUrl(it)
            }
            val expected = if (hasRenderablePayload) {
                payloadExpected ?: mangaExpected?.takeIf { it >= urls.size }
            } else {
                mangaExpected ?: payloadExpected
            }
            if (expected != null && urls.size > expected) {
                Log.d(
                    TAG,
                    "reader_ntk_cached_payload_urls_cap path=$path,from=${urls.size},to=$expected"
                )
                urls.take(expected)
            } else {
                urls
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun hybridNtkImageCandidates(manga: Manga, path: String): List<String> {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 3) return emptyList()
        val segment = parts[0]
        if (segment != "webtoon" && segment != "manhwa") return emptyList()
        val pathWorkId = parts[1]
        val pathEpisodeId = parts[2]
        val numeric = Regex("\\d{1,12}")
        val recordedWorkId = manga.ntkImageWorkId?.trim().orEmpty()
        val imageWorkId = recordedWorkId.ifBlank {
            pathWorkId
        }
        val recordedEpisodeId = manga.ntkImageEpisodeId?.trim().orEmpty()
        val imageEpisodeId = when {
            recordedEpisodeId.matches(numeric) -> recordedEpisodeId
            pathEpisodeId.matches(numeric) -> pathEpisodeId
            else -> ""
        }
        if (!imageWorkId.matches(numeric) || imageEpisodeId.isEmpty()) {
            if (segment == "webtoon" && pathEpisodeId.matches(numeric)) {
                val count = hybridNtkCandidatePageCount(manga)
                return (1..count).map { page ->
                    "https://fifa.worldcup73.xyz/wt/episodes/$pathWorkId/$pathEpisodeId/" +
                        "p%03d.jpeg".format(Locale.ROOT, page)
                }
            }
            return emptyList()
        }
        val count = hybridNtkCandidatePageCount(manga)
        val initialExtension = ntkInitialGeneratedExtensionForPath(segment, pathWorkId, imageWorkId)
        val urls = ArrayList<String>(count)
        for (page in 1..count) {
            val pageName = "p%03d.%s".format(Locale.ROOT, page, initialExtension)
            if (segment == "manhwa") {
                urls.add("https://booktoki9.org/manhwa/$imageWorkId/$imageEpisodeId/$pageName")
            } else if (!pathWorkId.matches(numeric)) {
                urls.add("https://fifa.worldcup73.xyz/wt/episodes/$pathWorkId/$pathEpisodeId/$pageName")
            } else {
                urls.add("https://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$imageEpisodeId/$pageName")
            }
        }
        return urls
    }

    private fun hybridNtkCandidatePageCount(manga: Manga): Int {
        val known = manga.ntkImageCount
        return when {
            known > 0 -> known.coerceIn(1, 128)
            else -> 64
        }
    }

    private fun ntkViewerPayloadImageCount(payload: String?): Int {
        val text = payload?.takeIf { it.isNotBlank() } ?: return 0
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        val counts = listOf(
            Regex(""""page"\s*:""").findAll(normalized).count(),
            Regex("""\\"page\\"\s*:""").findAll(normalized).count(),
            Regex(""""src"\s*:\s*"""").findAll(normalized).count(),
            Regex("""\\"src\\"\s*:\s*\\"""").findAll(normalized).count(),
            ntkViewerImageMetasCount(normalized),
            ntkViewerExplicitImageCount(normalized)
        )
        return (counts.maxOrNull() ?: 0).coerceIn(0, 128)
    }

    private fun ntkViewerExplicitImageCount(text: String): Int {
        val fields = "imageCount|imagesCount|totalImages|totalImageCount|pageCount|totalPages|numberOfPages"
        val plain = Regex(""""(?:$fields)"\s*:\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val escaped = Regex("""\\"(?:$fields)\\"\s*:\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return maxOf(plain, escaped).coerceIn(0, 128)
    }

    private fun ntkViewerImageMetasCount(text: String): Int {
        val marker = text.indexOf("imageMetas", ignoreCase = true)
        if (marker < 0) return 0
        val start = text.indexOf('[', marker)
        if (start < 0) return 0
        var depth = 0
        var count = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth <= 0) return count
                }
                '{' -> if (depth == 1) count++
            }
        }
        return count
    }

    private fun ntkCachedViewerPayload(path: String): String {
        return try {
            getHttpClient().cachedNtkViewerPayloadBody(path, 60_000L).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun ntkCachedViewerPayloadImageCount(path: String): Int {
        return ntkViewerPayloadImageCount(ntkCachedViewerPayload(path))
    }

    private fun updateHybridNtkScrollState(snapshot: NtkBrowserSessionBroker.ScrollSnapshot) {
        val previous = hybridNtkScrollSnapshot
        val viewport = snapshot.viewportHeight.coerceAtLeast(1)
        val content = maxOf(snapshot.contentHeight, previous?.contentHeight ?: 0, viewport)
        val maxScroll = (content - viewport).coerceAtLeast(0)
        val scrollY = snapshot.scrollY.coerceIn(0, maxScroll)
        val stableSnapshot = snapshot.copy(
            scrollY = scrollY,
            contentHeight = content,
            maxScroll = maxScroll,
            nearEnd = maxScroll > 0 && scrollY >= maxScroll - 96
        )
        hybridNtkScrollSnapshot = stableSnapshot
        val expectedPages = currentManga?.ntkImageCount?.coerceAtLeast(0) ?: 0
        val estimatedPages = if (expectedPages > 0) {
            expectedPages
        } else {
            maxOf(pageCount, ((content + viewport - 1) / viewport).coerceAtLeast(1))
        }
        pageCount = estimatedPages
        val page = if (maxScroll <= 0) {
            0
        } else {
            ((scrollY.toLong() * (estimatedPages - 1)) / maxScroll.coerceAtLeast(1)).toInt()
        }.coerceIn(0, (estimatedPages - 1).coerceAtLeast(0))
        currentPage = page
        if (::pageView.isInitialized) pageView.text = "${currentPage + 1} / $pageCount"
        updateCurrentEpisode(currentPage, scrollY, saveProgress = true)
        if (maxScroll > 0 && scrollY >= maxScroll / 2) {
            maybePrimeHybridNtkNextEpisode("scroll-prefetch")
        }
        if (stableSnapshot.nearEnd) {
            maybeStartHybridNtkNextEpisode("scroll-near-end")
        }
    }

    private fun maybePrimeHybridNtkNextEpisode(reason: String): Boolean {
        if (!hybridNtkBrowserActive || destroyed || isFinishing) return false
        if (reason == "viewer-start" && (!firstDrawableMetricLogged || !hybridNtkViewportReady)) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_next_prime_defer_initial_viewer_start " +
                    "firstDrawable=$firstDrawableMetricLogged,viewport=$hybridNtkViewportReady"
            )
            return false
        }
        if (!firstDrawableMetricLogged || !hybridNtkViewportReady) {
            Log.d(
                TAG,
                "reader_ntk_hybrid_next_prime_defer_current_not_ready reason=$reason," +
                    "firstDrawable=$firstDrawableMetricLogged,viewport=$hybridNtkViewportReady"
            )
            return false
        }
        val quietMs = boundaryAppendQuietRemainingMs()
        if (reason != "viewer-start" && quietMs > 0L) {
            Log.d(TAG, "reader_ntk_hybrid_next_prime_defer_active_input reason=$reason,quietMs=$quietMs")
            return false
        }
        val manga = currentManga ?: return false
        val title = currentTitle ?: manga.title
        val episodes = ViewerEpisodeResolver.episodeListFor(manga, null, title)
        val next = cachedNextEpisode ?: adjacentEpisodeFastPrepared(manga, title, episodes, true) ?: return false
        val nextPath = next.ntkEpisodePath?.trim().orEmpty()
        if (nextPath.isBlank() || hybridNtkNextPreparedPath == nextPath) return false
        if (!nextPath.startsWith("/webtoon/") && !nextPath.startsWith("/manhwa/")) return false
        if (isStrictNtkEpisodePath(nextPath)) {
            startStrictNtkDiscovery(next, "hybrid_next_prime_reroute_$reason")
            return true
        }
        if (next.ntkImageCount <= 0 || next.ntkImageCount > 128) next.ntkImageCount = 128
        val generatedUrls = hybridNtkImageCandidates(next, nextPath)
        if (generatedUrls.isEmpty()) return false
        if (isSpeculativeHybridNtkGeneratedCandidate(next, nextPath, generatedUrls)) {
            prepareHybridNtkNextEpisode(manga, next, title, reason)
            Log.d(
                TAG,
                "reader_ntk_hybrid_next_prime_prepare_speculative path=$nextPath," +
                    "count=${generatedUrls.size},reason=$reason"
            )
            return true
        }
        seedPreparedHybridNtkNext(next, nextPath, generatedUrls, "generated-$reason")
        NtkBrowserSessionBroker.prefetchImageUrls(nextPath, generatedUrls, "next-$reason")
        return true
    }

    private fun maybeStartHybridNtkNextEpisode(reason: String): Boolean {
        if (!hybridNtkBrowserActive || adjacentNavigationInFlight || destroyed || isFinishing) return false
        if (!hybridNtkViewportReady || !firstDrawableMetricLogged) return false
        val coverage = hybridNtkCoverageSnapshot
        if (coverage == null || !isHybridCoverageDrawable(coverage)) {
            Log.d(TAG, "reader_ntk_hybrid_next_start_defer_viewport_loading reason=$reason")
            return false
        }
        val quietMs = boundaryAppendQuietRemainingMs()
        if (quietMs > 0L) {
            Log.d(TAG, "reader_ntk_hybrid_next_start_defer_active_input reason=$reason,quietMs=$quietMs")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - hybridNtkAutoNextStartedAtMs < 2_500L) return false
        val manga = currentManga ?: return false
        val title = currentTitle ?: manga.title
        val episodes = ViewerEpisodeResolver.episodeListFor(manga, null, title)
        attachEpisodeList(title, manga, episodes)
        val next = cachedNextEpisode ?: adjacentEpisodeFastPrepared(manga, title, episodes, true) ?: return false
        val nextPath = next.ntkEpisodePath?.trim().orEmpty()
        if (isStrictNtkEpisodePath(nextPath)) {
            hybridNtkAutoNextStartedAtMs = now
            launchAdjacent(manga, next, title)
            return true
        }
        if (nextPath.startsWith("/webtoon/") || nextPath.startsWith("/manhwa/")) {
            val prepared = NtkBrowserSessionBroker.preparedStatus(nextPath)
            if (!prepared.firstDrawable && !prepared.allDecodedReady) {
                maybePrimeHybridNtkNextEpisode("start-defer-$reason")
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_next_start_defer_unprepared reason=$reason," +
                        "next=$nextPath,preparedFirst=${prepared.firstDrawable}," +
                        "preparedAll=${prepared.allDecodedReady},preparedImages=${prepared.imageCount}," +
                        "expected=${prepared.expected}"
                )
                return false
            }
        }
        hybridNtkAutoNextStartedAtMs = now
        Log.d(TAG, "reader_ntk_hybrid_next_start reason=$reason current=${manga.ntkEpisodePath} next=${next.ntkEpisodePath}")
        return startPreparedHybridNtkNextEpisode(manga, next, title, reason)
    }

    private fun startPreparedHybridNtkNextEpisode(
        source: Manga,
        next: Manga,
        title: Title?,
        reason: String
    ): Boolean {
        val nextPath = next.ntkEpisodePath?.trim().orEmpty()
        if (isStrictNtkEpisodePath(nextPath)) {
            startStrictNtkDiscovery(next, "hybrid_next_start_reroute_$reason")
            launchAdjacent(source, next, title)
            return true
        }
        if (!nextPath.startsWith("/webtoon/") && !nextPath.startsWith("/manhwa/")) {
            launchAdjacent(source, next, title)
            return true
        }
        if (hybridNtkNextPreparedPath == nextPath) {
            launchAdjacent(source, next, title)
            return true
        }
        val cachedUrls = ntkCachedViewerPayloadImageUrls(next, nextPath)
        if (cachedUrls.isNotEmpty()) {
            seedPreparedHybridNtkNext(next, nextPath, cachedUrls, "cached-$reason")
            launchAdjacent(source, next, title)
            return true
        }
        if (nextPath.startsWith("/webtoon/") || nextPath.startsWith("/manhwa/")) {
            if (next.ntkImageCount <= 0) next.ntkImageCount = 128
            val generatedUrls = hybridNtkImageCandidates(next, nextPath)
            if (generatedUrls.isNotEmpty()) {
                if (isSpeculativeHybridNtkGeneratedCandidate(next, nextPath, generatedUrls)) {
                    prepareHybridNtkNextEpisode(source, next, title, reason)
                    Log.d(
                        TAG,
                        "reader_ntk_hybrid_next_start_prepare_speculative path=$nextPath," +
                            "count=${generatedUrls.size},reason=$reason"
                    )
                    return true
                }
                seedPreparedHybridNtkNext(next, nextPath, generatedUrls, "generated-$reason")
                launchAdjacent(source, next, title)
                return true
            }
        }
        prepareHybridNtkNextEpisode(source, next, title, reason)
        return true
    }

    private fun isSpeculativeHybridNtkGeneratedCandidate(
        manga: Manga,
        path: String,
        urls: List<String>
    ): Boolean {
        if (!path.matches(Regex("^/(?:manhwa|webtoon)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE))) {
            return false
        }
        if (urls.size < 64) return false
        val payloadUrls = ntkCachedViewerPayloadImageUrls(manga, path)
        if (payloadUrls.isNotEmpty()) return false
        val minCreatedAt = SystemClock.elapsedRealtime() - 30_000L
        val verified = ReaderImageCache.earlyNtkGeneratedSuccessImageUrls(path, minCreatedAt)
            .filterNot { isConstructedNtkViewerCdnUrl(it) }
        if (verified.isNotEmpty() && verified.size >= urls.size) return false
        return true
    }

    private fun seedPreparedHybridNtkNext(
        next: Manga,
        path: String,
        urls: List<String>,
        reason: String
    ) {
        if (urls.isEmpty()) return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(next, "hybrid_next_seed_reroute_$reason")
            return
        }
        next.ntkImageCount = urls.size
        ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(path, urls, "adjacent-$reason")
        NtkBrowserSessionBroker.primeImageUrls(path, urls, "payload-adjacent-$reason")
        hybridNtkNextPreparedPath = path
        Log.d(
            TAG,
            "reader_ntk_hybrid_next_prepared path=$path,count=${urls.size},reason=$reason"
        )
    }

    private fun prepareHybridNtkNextEpisode(
        source: Manga,
        next: Manga,
        title: Title?,
        reason: String
    ) {
        val path = next.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank()) return
        if (isStrictNtkEpisodePath(path)) {
            startStrictNtkDiscovery(next, "hybrid_next_prepare_reroute_$reason")
            return
        }
        if (hybridNtkNextPrepareInFlight && hybridNtkNextPreparePath == path) {
            Log.d(TAG, "reader_ntk_hybrid_next_prepare_join path=$path,reason=$reason")
            return
        }
        hybridNtkNextPrepareInFlight = true
        hybridNtkNextPreparePath = path
        val launchGenerationPath = currentManga?.ntkEpisodePath?.trim().orEmpty()
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            var preparedUrls: List<String> = emptyList()
            try {
                val client = getHttpClient()
                val parts = path.trim('/').split('/').filter { it.isNotBlank() }
                val kind = parts.getOrNull(0).orEmpty()
                val workId = next.ntkImageWorkId?.trim().orEmpty().ifBlank {
                    parts.getOrNull(1).orEmpty()
                }
                val episodeId = next.ntkImageEpisodeId?.trim().orEmpty().ifBlank {
                    parts.getOrNull(2).orEmpty()
                }
                if (kind.equals("manhwa", ignoreCase = true) &&
                    workId.matches(Regex("\\d{1,12}")) &&
                    episodeId.matches(Regex("\\d{1,12}"))
                ) {
                    preparedUrls = resolveNtkManhwaDirectManifestUnknown(path, 240)
                    if (preparedUrls.isNotEmpty()) {
                        next.ntkImageCount = preparedUrls.size
                        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                            path,
                            preparedUrls,
                            "hybrid-next-canonical-direct-resolved"
                        )
                        NtkBrowserSessionBroker.publishAuthoritativeImageUrls(
                            path,
                            preparedUrls,
                            "hybrid-next-canonical-direct-resolved"
                        )
                    }
                }
                val body = if (preparedUrls.isEmpty()) {
                    client.mgetNtkViewerPayloadPage(path, 8_000L).body.orEmpty()
                } else {
                    ""
                }
                if (body.isNotBlank()) {
                    NtkBrowserSessionBroker.publishViewerPayload(path, body, "adjacent-prepare")
                    next.ntkViewerPayloadHint = body
                    preparedUrls = CustomHttpClient.extractNtkViewerImageUrlsFromApiBody(
                        body,
                        kind,
                        workId,
                        episodeId
                    ).orEmpty()
                }
                if (preparedUrls.isEmpty()) {
                    val payloadCount = ntkViewerPayloadImageCount(body)
                    if (payloadCount > 0) {
                        next.ntkImageCount = payloadCount
                        preparedUrls = hybridNtkImageCandidates(next, path)
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_next_prepare_generated_from_payload_count " +
                                "path=$path,count=$payloadCount,urls=${preparedUrls.size}"
                        )
                    }
                }
                if (preparedUrls.isEmpty() && kind == "webtoon") {
                    preparedUrls = client.fetchNtkWebtoonUnsignedViewerImageUrls(
                        path,
                        workId,
                        episodeId,
                        null
                    ).orEmpty()
                }
                if (preparedUrls.isEmpty()) {
                    preparedUrls = client.fetchNtkViewerImageUrls(
                        kind,
                        workId,
                        episodeId,
                        "",
                        body,
                        path,
                        path
                    ).orEmpty()
                }
                Log.d(
                    TAG,
                    "reader_ntk_hybrid_next_prepare_result path=$path,count=${preparedUrls.size}," +
                        "body=${body.length},ms=${SystemClock.elapsedRealtime() - startedAt},reason=$reason"
                )
            } catch (e: Throwable) {
                Log.d(TAG, "reader_ntk_hybrid_next_prepare_error path=$path,reason=$reason,$e")
            }
            runOnUiThread {
                if (hybridNtkNextPreparePath == path) hybridNtkNextPrepareInFlight = false
                if (destroyed || isFinishing) return@runOnUiThread
                if (currentManga?.ntkEpisodePath?.trim().orEmpty() != launchGenerationPath) {
                    return@runOnUiThread
                }
                if (preparedUrls.isEmpty()) {
                    Log.d(TAG, "reader_ntk_hybrid_next_prepare_empty path=$path,reason=$reason")
                    return@runOnUiThread
                }
                seedPreparedHybridNtkNext(next, path, preparedUrls, reason)
                if (currentHybridNtkScrollSnapshot()?.nearEnd == true) {
                    launchAdjacent(source, next, title)
                }
            }
        }, "ntk-hybrid-next-prepare").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun resolveNtkManhwaDirectManifestUnknown(path: String, maxPages: Int): List<String> {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 3 || !parts[0].equals("manhwa", ignoreCase = true) || maxPages <= 0) return emptyList()
        val workId = parts[1]
        val episodeId = parts[2]
        if (!workId.matches(Regex("\\d{1,12}")) || !episodeId.matches(Regex("\\d{1,12}"))) return emptyList()
        val hosts = arrayOf(
            "booktoki9.org",
            "booktoki8.org",
            "mana.apihost93.com",
            "aws-cdn1.site",
        )
        val extensions = arrayOf("jpeg", "jpg", "png", "webp")
        val startedAt = SystemClock.elapsedRealtime()
        var selectedHost = ""
        var selectedExtension = ""
        var firstImage = ""
        for (host in hosts) {
            for (extension in extensions) {
                val candidate = "https://$host/manhwa/$workId/$episodeId/p001.$extension"
                if (isNtkDirectImageReachable(candidate)) {
                    selectedHost = host
                    selectedExtension = extension
                    firstImage = candidate
                    break
                }
            }
            if (firstImage.isNotEmpty()) break
        }
        if (firstImage.isEmpty()) {
            Log.d(TAG, "reader_ntk_hybrid_next_direct_resolve_miss_first path=$path,ms=${SystemClock.elapsedRealtime() - startedAt}")
            return emptyList()
        }
        val images = ArrayList<String>()
        images.add(firstImage)
        val parallelism = minOf(16, maxOf(1, maxPages - 1))
        val chunkSize = maxOf(16, parallelism * 3)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "NtkHybridNextDirectProbe").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        return try {
            var chunkStart = 2
            while (chunkStart <= maxPages && !Thread.currentThread().isInterrupted) {
                val chunkEnd = minOf(maxPages, chunkStart + chunkSize - 1)
                val futures = ArrayList<java.util.concurrent.Future<String?>>()
                for (page in chunkStart..chunkEnd) {
                    futures.add(pool.submit<String?> {
                        val candidate = "https://$selectedHost/manhwa/$workId/$episodeId/p${page.toString().padStart(3, '0')}.$selectedExtension"
                        if (isNtkDirectImageReachable(candidate)) candidate else null
                    })
                }
                for (i in futures.indices) {
                    val found = try {
                        futures[i].get(1600, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: Throwable) {
                        null
                    }
                    if (found.isNullOrEmpty()) {
                        futures.forEach { it.cancel(true) }
                        Log.d(
                            TAG,
                            "reader_ntk_hybrid_next_direct_resolve_tail path=$path,count=${images.size}," +
                                "missingPage=${chunkStart + i},ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        return images
                    }
                    images.add(found)
                }
                chunkStart += chunkSize
            }
            Log.d(
                TAG,
                "reader_ntk_hybrid_next_direct_resolved path=$path,count=${images.size}," +
                    "ms=${SystemClock.elapsedRealtime() - startedAt},first=$firstImage"
            )
            images
        } catch (e: Throwable) {
            Log.d(TAG, "reader_ntk_hybrid_next_direct_resolve_error path=$path,ms=${SystemClock.elapsedRealtime() - startedAt},$e")
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isNtkDirectImageReachable(imageUrl: String): Boolean {
        if (imageUrl.isBlank()) return false
        return isNtkDirectImageReachable(imageUrl, "HEAD") || isNtkDirectImageReachable(imageUrl, "GET")
    }

    private fun isNtkDirectImageReachable(imageUrl: String, method: String): Boolean {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("User-Agent", getHttpClient().agent)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                if (method == "GET") setRequestProperty("Range", "bytes=0-0")
                connectTimeout = 1200
                readTimeout = 1200
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun currentHybridNtkScrollSnapshot(): NtkBrowserSessionBroker.ScrollSnapshot? {
        return refreshHybridNtkScrollFromView()
    }

    private fun refreshHybridNtkScrollFromView(): NtkBrowserSessionBroker.ScrollSnapshot? {
        val view = hybridNtkWebView ?: return hybridNtkScrollSnapshot
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank()) return hybridNtkScrollSnapshot
        val viewport = maxOf(1, view.height)
        val expectedPages = currentManga?.ntkImageCount?.coerceAtLeast(0) ?: 0
        val estimatedContent = if (expectedPages > 0) {
            viewport * expectedPages
        } else {
            viewport
        }
        val previousContent = hybridNtkScrollSnapshot?.contentHeight ?: 0
        val content = maxOf(viewport, estimatedContent, previousContent, (view.contentHeight * view.scale).toInt())
        val maxScroll = (content - viewport).coerceAtLeast(0)
        val scrollY = view.scrollY.coerceIn(0, maxScroll)
        val snapshot = NtkBrowserSessionBroker.ScrollSnapshot(
            path,
            scrollY,
            viewport,
            content,
            maxScroll,
            maxScroll > 0 && scrollY >= maxScroll - 96,
            SystemClock.elapsedRealtime()
        )
        updateHybridNtkScrollState(snapshot)
        return snapshot
    }

    private fun updateAdjacentButtons() {
        if (!::prevButton.isInitialized || !::nextButton.isInitialized) return
        val manga = currentManga
        val title = currentTitle ?: manga?.title
        if (adjacentNavigationInFlight) {
            setAdjacentButtonState(false, false)
            return
        }
        val episodes = if (manga == null) null else ViewerEpisodeResolver.episodeListFor(manga, null, title)
        if (manga != null) attachEpisodeList(title, manga, episodes)
        val previous = if (manga == null) null else adjacentEpisodeFastPrepared(manga, title, episodes, false)
        val next = if (manga == null) null else adjacentEpisodeFastPrepared(manga, title, episodes, true)
        cachedPreviousEpisode = previous
        cachedNextEpisode = next
        if (shouldPrimeAdjacentNow()) primeAdjacentLaunchWindow(title, next)
        prevButton.isEnabled = shouldEnableAdjacentButton(
            previous != null,
            canFetchMissingAdjacent(manga, title, previous)
        )
        nextButton.isEnabled = shouldEnableAdjacentButton(
            next != null,
            canFetchMissingAdjacent(manga, title, next)
        )
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
    }

    private fun setAdjacentButtonState(previous: Boolean, next: Boolean) {
        if (!::prevButton.isInitialized || !::nextButton.isInitialized) return
        prevButton.isEnabled = previous
        nextButton.isEnabled = next
        prevButton.alpha = if (previous) 1f else 0.35f
        nextButton.alpha = if (next) 1f else 0.35f
    }

    private fun primeAdjacentLaunchWindow(title: Title?, target: Manga?) {
        if (target == null) return
        if (isStrictNtkEpisodePath(target.ntkEpisodePath)) {
            startStrictNtkDiscovery(target, "adjacent_prime")
            return
        }
        ReaderWarmupCoordinator.primeAdjacent(
            applicationContext,
            target,
            title ?: target.title
        )
    }

    private fun readerWidthPx(): Int {
        return maxOf(1, renderView.width, resources.displayMetrics.widthPixels)
    }

    private fun readerHeightPx(): Int {
        return maxOf(1, renderView.height, resources.displayMetrics.heightPixels)
    }

    private fun ensureToolbarCreated(manga: Manga? = currentManga, title: Title? = currentTitle): Boolean {
        if (::topBar.isInitialized && ::bottomBar.isInitialized) return true
        val source = manga ?: currentManga ?: return false
        val resolvedTitle = title ?: source.title ?: currentTitle
        toolbarTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        topBar = LinearLayout(this).apply {
            id = R.id.viewerToolbar
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(0xee111111.toInt())
            visibility = View.GONE
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(0xee111111.toInt())
            visibility = View.GONE
        }
        titleView = TextView(this).apply {
            id = R.id.toolbar_title
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(12.dp(), 0, 12.dp(), 0)
            background = roundedBackground(0xff282828.toInt(), 0xff555555.toInt(), 10.dp())
        }
        pageView = TextView(this).apply {
            setTextColor(0xffdddddd.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val backButton = Button(this).apply {
            text = "<"
            setOnClickListener { finish() }
        }
        prevButton = Button(this).apply {
            id = R.id.toolbar_previous
            text = "이전"
            setOnClickListener {
                Log.d(TAG, "toolbar_prev_click")
                openAdjacent(false)
            }
        }
        episodeButton = Button(this).apply {
            id = R.id.toolbar_spinner
            text = "회차"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBackground(0xff2f6df6.toInt(), 0x55ffffff, 8.dp())
            setOnClickListener {
                Log.d(TAG, "toolbar_episode_click")
                showEpisodePicker()
            }
        }
        nextButton = Button(this).apply {
            id = R.id.toolbar_next
            text = "다음"
            setOnClickListener {
                Log.d(TAG, "toolbar_next_click")
                openAdjacent(true)
            }
        }
        autoCutButton = Button(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { toggleAutoCut() }
        }
        updateAutoCutButton()
        titleView.text = displayEpisodeTitle(source, resolvedTitle)
        topBar.addView(backButton, LinearLayout.LayoutParams(52.dp(), 44.dp()))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, 40.dp(), 1f).apply {
            leftMargin = 8.dp()
        })
        bottomBar.addView(pageView, LinearLayout.LayoutParams(0, 44.dp(), 1f))
        bottomBar.addView(autoCutButton, LinearLayout.LayoutParams(108.dp(), 44.dp()).apply {
            rightMargin = 8.dp()
        })
        bottomBar.addView(prevButton, LinearLayout.LayoutParams(64.dp(), 44.dp()))
        bottomBar.addView(episodeButton, LinearLayout.LayoutParams(64.dp(), 44.dp()).apply {
            leftMargin = 6.dp()
        })
        bottomBar.addView(nextButton, LinearLayout.LayoutParams(64.dp(), 44.dp()).apply {
            leftMargin = 6.dp()
        })
        installToolbarTouchForwarder(
            topBar,
            bottomBar,
            titleView,
            pageView
        )
        return true
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        return ReaderChromeStyler.roundedBackground(fill, stroke, radius, resources.displayMetrics.density)
    }

    private fun missingEpisodeHost(): MissingEpisodeNavigator.Host {
        return object : MissingEpisodeNavigator.Host {
            override fun lockUi(lock: Boolean) {
                adjacentNavigationInFlight = lock
                setAdjacentButtonState(!lock, !lock)
                status.visibility = if (lock) TextView.VISIBLE else TextView.GONE
                if (lock) status.text = "다른 소스 확인 중"
                if (!lock) updateAdjacentButtons()
            }

            override fun openAlternateEpisode(title: Title?, episode: Manga?) {
                if (title == null || episode == null || destroyed || isFinishing) return
                markEpisodeSourceSwitched(title)
                openAlternateReaderEpisode(title, episode)
            }

            override fun showCaptcha(episode: Manga?) {
                if (shouldSuppressNtkCaptchaPopupAfterPagesReady(episode ?: currentManga, "missing_episode")) {
                    return
                }
                status.visibility = TextView.VISIBLE
                status.text = "캡차 확인이 필요합니다"
                Utils.showCaptchaPopup(Manga.safeUrl(episode ?: currentManga), this@ReaderV2Activity, REQUEST_CAPTCHA, p)
            }

            override fun onPromptCancelled() {
                adjacentNavigationInFlight = false
                if (pagesReady) status.visibility = TextView.GONE
                updateAdjacentButtons()
            }
        }
    }

    private fun openAlternateReaderEpisode(title: Title, episode: Manga) {
        attachEpisodeList(title, episode)
        episode.mode = currentManga?.mode ?: episode.mode
        episode.setEps(Utils.snapshotEpisodes(title))
        val source = currentManga ?: episode
        launchAdjacent(source, episode, title)
    }

    private fun markEpisodeSourceSwitched(title: Title) {
        val result = resultIntent ?: Intent().also { resultIntent = it }
        ViewerReturnResult.addEpisodeListResult(result, ViewerReturnResult.episodeListTitleJson(title))
        setResult(RESULT_OK, result)
    }

    private fun resolveAdjacent(source: Manga, next: Boolean, fetchEpisodes: Boolean): AdjacentResolution {
        val title = currentTitle ?: source.title
        restoreTitleEpisodes(title, source)
        attachEpisodeList(title, source)
        var target = adjacentEpisode(source, next)
        var result = Title.LOAD_OK
        var fetchedEpisodes = false
        if (target == null && fetchEpisodes && source.isOnline && title != null) {
            result = MangaRepository.fetchEpisodesForeground(title, MangaRepository.cancellation())
            if (result == Title.LOAD_OK) {
                fetchedEpisodes = true
                restoreTitleEpisodes(title, source)
                attachEpisodeList(title, source)
                target = adjacentEpisode(source, next)
            }
        }
        if (target != null) {
            target.mode = source.mode
            attachEpisodeList(title, target)
        }
        return AdjacentResolution(target, title, result, fetchedEpisodes)
    }

    private fun adjacentEpisode(manga: Manga, next: Boolean): Manga? {
        val title = currentTitle ?: manga.title
        restoreTitleEpisodes(title, manga)
        attachEpisodeList(title, manga)
        return if (next) {
            ViewerEpisodeResolver.nextCandidate(manga, null, title, this::sameManga)
        } else {
            ViewerEpisodeResolver.previousCandidate(manga, null, title, this::sameManga)
        }
    }

    private fun adjacentEpisodeFast(manga: Manga, next: Boolean): Manga? {
        val title = currentTitle ?: manga.title
        restoreTitleEpisodes(title, manga)
        val episodes = ViewerEpisodeResolver.episodeListFor(manga, null, title)
        attachEpisodeList(title, manga, episodes)
        return adjacentEpisodeFastPrepared(manga, title, episodes, next)
    }

    private fun adjacentEpisodeFastPrepared(
        manga: Manga,
        title: Title?,
        episodes: List<Manga>?,
        next: Boolean
    ): Manga? {
        return if (next) {
            ViewerEpisodeResolver.nextCandidateFromList(manga, episodes, null, title, this::sameMangaFast)
        } else {
            ViewerEpisodeResolver.previousCandidateFromList(manga, episodes, null, title, this::sameMangaFast)
        }
    }

    private fun cachedAdjacentEpisode(source: Manga, next: Boolean): Manga? {
        val target = if (next) cachedNextEpisode else cachedPreviousEpisode
        if (target == null || sameMangaFast(target, source)) return null
        return target
    }

    private fun sameMangaFast(first: Manga?, second: Manga?): Boolean {
        if (Manga.sameEpisodeIdentity(first, second)) return true
        return first === second
    }

    private fun sameManga(first: Manga?, second: Manga?): Boolean {
        if (Manga.sameEpisodeIdentity(first, second)) return true
        if (first == null || second == null || first === second) return first === second
        val firstImages = MangaRepository.imageUrls(first, applicationContext)
        val secondImages = MangaRepository.imageUrls(second, applicationContext)
        return !firstImages.isNullOrEmpty() && firstImages == secondImages
    }

    private fun canFetchMissingAdjacent(manga: Manga?, title: Title?, target: Manga?): Boolean {
        return target == null && !episodeListFetchAttempted && manga?.isOnline == true && title != null
    }

    private fun hasStableAdjacentResolutionSource(manga: Manga?, title: Title?): Boolean {
        if (manga == null) return false
        val episodes = ViewerEpisodeResolver.episodeListFor(manga, null, title)
        if (ViewerEpisodeResolver.findEpisodeIndex(episodes, manga, this::sameManga) >= 0) return true
        return Manga.visibleEpisodeNumberKey(manga.name).isNotBlank()
    }

    private fun restoreTitleEpisodes(title: Title?, target: Manga?) {
        if (title == null || target == null) return
        val targetEpisodes = Utils.snapshotEpisodes(target)
        val titleEpisodes = Utils.snapshotEpisodes(title)
        if (targetEpisodes.size > 1 && !containsEpisode(titleEpisodes, target) && titleEpisodes.size < targetEpisodes.size) {
            title.setEps(targetEpisodes)
        }
        title.ensureProgressEpisodes(target)
    }

    private fun attachEpisodeList(title: Title?, target: Manga?) {
        attachEpisodeList(title, target, null)
    }

    private fun attachEpisodeList(title: Title?, target: Manga?, preparedEpisodes: List<Manga>?) {
        if (title == null || target == null) return
        title.ensureProgressEpisodes(target)
        val episodes = preparedEpisodes ?: Utils.snapshotEpisodes(title)
        for (episode in episodes) {
            episode?.let {
                it.title = title
                it.titleId = title.id
            }
        }
        target.title = title
        target.titleId = title.id
        if (episodes.isNotEmpty() && containsEpisode(episodes, target)) {
            val targetEpisodeCount = target.eps?.size ?: 0
            if (targetEpisodeCount == 0 || episodes.size >= targetEpisodeCount) target.setEps(episodes)
        }
        currentTitle = title
    }

    private fun containsEpisode(episodes: List<Manga>?, target: Manga?): Boolean {
        if (episodes == null || target == null) return false
        return episodes.any { Manga.sameEpisodeIdentity(it, target) }
    }

    private fun updatePageLabel() {
        setPageText(if (pageCount > 0) "${currentPage + 1} / $pageCount" else "- / -")
    }

    private fun updateCurrentEpisode(anchorPage: Int, anchorOffset: Int = 0, saveProgress: Boolean = true) {
        val info = MainThreadStallMonitor.traceResult("reader_page_info") {
            session?.pageInfo(anchorPage)
        }
            if (info != null) {
            val previousManga = currentManga
            val episodeChanged = previousManga == null || !Manga.sameEpisodeIdentity(previousManga, info.manga)
            currentManga = info.manga
            if (episodeChanged || info.transitionCard) {
                Log.d(TAG, "current_episode page=$anchorPage offset=$anchorOffset transition=${info.transitionCard} mangaId=${info.manga.id} title=${info.title}")
            }
            updateResultEpisode(info.manga, info.transitionCard)
            val displayKey = displayEpisodeKey(info.manga, currentTitle)
            val displayTitle = if (!episodeChanged && lastDisplayedEpisodeKey == displayKey) {
                lastDisplayedEpisodeTitle
            } else {
                info.title.takeIf { it.isNotBlank() }
                    ?: displayEpisodeTitle(info.manga, currentTitle).takeIf { it.isNotBlank() }
                    ?: "회차"
            }
            if (episodeChanged || lastDisplayedEpisodeKey != displayKey) {
                lastDisplayedEpisodeKey = displayKey
                lastDisplayedEpisodeTitle = displayTitle
            }
            if (::titleView.isInitialized && titleView.text.toString() != displayTitle) {
                titleView.text = displayTitle
            }
            setPageText(if (info.transitionCard) {
                "회차 전환"
            } else if (info.totalPages <= 0) {
                "${info.localPage} / ?"
            } else {
                "${info.localPage} / ${info.totalPages}"
            })
            if (episodeChanged) {
                MainThreadStallMonitor.trace("reader_update_adjacent_buttons") {
                    updateAdjacentButtons()
                }
            }
            if (saveProgress && info.layoutReady) {
                MainThreadStallMonitor.trace("reader_schedule_progress") {
                    scheduleSaveReadingProgress(info, anchorOffset)
                }
            }
            return
        }
        updatePageLabel()
    }

    private fun setPageText(text: String) {
        if (lastDisplayedPageText == text) return
        lastDisplayedPageText = text
        if (!::pageView.isInitialized) return
        pageView.text = text
    }

    private fun scheduleSaveReadingProgress(info: ReaderSession.PageInfo, offset: Int) {
        if (info.transitionCard || !info.manga.useBookmark()) return
        if (!progressSaveArmed) return
        pendingProgressInfo = info
        pendingProgressOffset = offset
        progressHandler.removeCallbacks(saveProgressRunnable)
        progressHandler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS)
    }

    private fun saveCurrentReadingProgress() {
        val currentPosition = renderView.currentProgressPosition()
        val currentInfo = currentPosition?.let { position ->
            nearestSaveablePageInfo(position.page)
        }
        val info = currentInfo ?: pendingProgressInfo ?: return
        if (!info.layoutReady) return
        saveReadingProgressNow(info, currentPosition?.offset ?: pendingProgressOffset)
    }

    private fun nearestSaveablePageInfo(page: Int): ReaderSession.PageInfo? {
        val readerSession = session ?: return null
        readerSession.pageInfo(page)?.takeIf { !it.transitionCard && it.manga.useBookmark() }?.let { return it }
        var distance = 1
        while (distance <= 3) {
            readerSession.pageInfo(page + distance)?.takeIf { !it.transitionCard && it.manga.useBookmark() }?.let { return it }
            readerSession.pageInfo(page - distance)?.takeIf { !it.transitionCard && it.manga.useBookmark() }?.let { return it }
            distance++
        }
        return null
    }

    private fun saveReadingProgressNow(info: ReaderSession.PageInfo, offset: Int) {
        if (info.transitionCard || !info.manga.useBookmark()) return
        val title = currentTitle ?: info.manga.title ?: return
        info.manga.title = title
        info.manga.titleId = title.id
        if (Utils.snapshotEpisodes(title).isEmpty()) {
            val episodes = Utils.snapshotEpisodes(info.manga)
            if (episodes.isNotEmpty()) title.setEps(episodes)
        }
        title.eps?.let { info.manga.setEps(it) }
        val episodes = Utils.snapshotEpisodes(title).ifEmpty { Utils.snapshotEpisodes(info.manga) }
        val episodeIndex = progressEpisodeIndex(episodes, info.manga, title.bookmarkEpisodeIndex)
        val progressManga = progressEpisodeForIndex(episodes, episodeIndex) ?: info.manga
        val progressEpisodeId = progressManga.id.takeIf { it > 0 } ?: info.manga.id
        val ntkPath = (progressManga.ntkEpisodePath ?: "").ifBlank { info.manga.ntkEpisodePath ?: "" }
        if (isNtkProgressTitle(title) && ntkPath.isNotBlank()) {
            title.resumeNtkEpisodePath = ntkPath
        }
        val episodeCount = maxOf(episodes.size, title.episodeCount, title.ntkReleaseEpisodeCount)
        if (episodeCount > 0) {
            title.setReadingProgress(progressEpisodeId, episodeIndex, episodeCount)
        }
        val zeroBasedPage = info.sourcePageIndex.coerceAtLeast(0)
        if (
            lastSavedEpisodeId == progressEpisodeId &&
            lastSavedPage == zeroBasedPage &&
            lastSavedOffset == offset &&
            lastSavedSide == info.side
        ) return
        lastSavedEpisodeId = progressEpisodeId
        lastSavedPage = zeroBasedPage
        lastSavedOffset = offset
        lastSavedSide = info.side
        p?.addRecent(title)
        p?.setBookmark(title, progressEpisodeId)
        p?.setViewerBookmark(info.manga, zeroBasedPage, offset, info.side)
    }

    private fun updateResultEpisode(manga: Manga?, transitionCard: Boolean = false) {
        if (transitionCard || manga == null || manga.id <= 0) return
        if (!intent.getBooleanExtra("recent", false) && !intent.getBooleanExtra("returnToEpisodes", false)) return
        val title = currentTitle ?: manga.title
        val episodes = Utils.snapshotEpisodes(title).ifEmpty { Utils.snapshotEpisodes(manga) }
        val episodeIndex = progressEpisodeIndex(episodes, manga, title?.bookmarkEpisodeIndex ?: -1)
        val resultManga = progressEpisodeForIndex(episodes, episodeIndex) ?: manga
        val result = resultIntent ?: Intent().also { resultIntent = it }
        result.putExtra("id", resultManga.id.takeIf { it > 0 } ?: manga.id)
        setResult(RESULT_OK, result)
    }

    private fun isNtkProgressTitle(title: MTitle): Boolean {
        val resolved = p?.resolveSourceSite(title)?.takeIf { it.isNotBlank() }
            ?: title.sourceSite
        return resolved == "ntk"
    }

    private fun displayEpisodeTitle(manga: Manga?, title: Title?): String {
        val episodes = Utils.snapshotEpisodes(title).ifEmpty { Utils.snapshotEpisodes(manga) }
        val index = ReaderDisplayPolicy.episodeIndex(episodes, manga)
        return ReaderDisplayPolicy.episodeDisplayName(manga, episodes, index, title)
            .takeIf { it.isNotBlank() }
            ?: title?.name?.takeIf { it.isNotBlank() }
            ?: manga?.title?.name?.takeIf { it.isNotBlank() }
            ?: "회차"
    }

    private fun displayEpisodeKey(manga: Manga?, title: Title?): String {
        if (manga == null) return ""
        return listOf(
            title?.sourceSite ?: manga.title?.sourceSite ?: "",
            (title?.id ?: manga.titleId).toString(),
            manga.baseMode.toString(),
            manga.id.toString(),
            manga.ntkEpisodePath ?: "",
            manga.name ?: ""
        ).joinToString("|")
    }

    private fun installToolbarTouchForwarder(vararg views: View) {
        for (view in views) {
            view.setOnTouchListener { source, event -> handleToolbarTouch(source, event) }
        }
    }

    private fun handleToolbarTouch(source: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toolbarDownRawX = event.rawX
                toolbarDownRawY = event.rawY
                toolbarForwardingScroll = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!toolbarForwardingScroll &&
                    abs(event.rawY - toolbarDownRawY) > toolbarTouchSlop &&
                    abs(event.rawY - toolbarDownRawY) >= abs(event.rawX - toolbarDownRawX)
                ) {
                    toolbarForwardingScroll = true
                    setToolbarVisible(false)
                    forwardToolbarTouch(MotionEvent.ACTION_DOWN, toolbarDownRawX, toolbarDownRawY, event.downTime, event.downTime)
                }
                if (toolbarForwardingScroll) {
                    forwardToolbarTouch(MotionEvent.ACTION_MOVE, event.rawX, event.rawY, event.downTime, event.eventTime)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (toolbarForwardingScroll) {
                    forwardToolbarTouch(event.actionMasked, event.rawX, event.rawY, event.downTime, event.eventTime)
                    toolbarForwardingScroll = false
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) source.performClick()
                return true
            }
        }
        return true
    }

    private fun forwardToolbarTouch(action: Int, rawX: Float, rawY: Float, downTime: Long, eventTime: Long) {
        val location = IntArray(2)
        renderView.getLocationOnScreen(location)
        val forwarded = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            rawX - location[0],
            rawY - location[1],
            0
        )
        try {
            renderView.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun setToolbarVisible(visible: Boolean) {
        toolbarVisible = visible
        if (visible) {
            if (!ensureToolbarCreated()) return
            attachToolbarIfNeeded()
        }
        if (!::topBar.isInitialized || !::bottomBar.isInitialized) {
            renderView.setScrollbarVisible(visible)
            return
        }
        val visibility = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomBar.visibility = visibility
        renderView.setScrollbarVisible(visible)
    }

    private fun shouldPrimeAdjacentNow(): Boolean {
        return !isCurrentNtkReader() || firstDrawableMetricLogged || toolbarAttached
    }

    private fun attachToolbarIfNeeded() {
        if (toolbarAttached) return
        if (!ensureToolbarCreated()) return
        val token = window.decorView.windowToken ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        fun params(gravity: Int): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                60.dp(),
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                this.gravity = gravity
                this.token = token
            }
        }
        try {
            manager.addView(topBar, params(Gravity.TOP))
            manager.addView(bottomBar, params(Gravity.BOTTOM))
        } catch (failure: RuntimeException) {
            if (topBar.parent != null) runCatching { manager.removeViewImmediate(topBar) }
            if (bottomBar.parent != null) runCatching { manager.removeViewImmediate(bottomBar) }
            Log.e(TAG, "toolbar_window_attach_failed", failure)
            return
        }
        toolbarWindowManager = manager
        toolbarAttached = true
        updateAdjacentButtons()
    }

    private fun detachToolbarWindows() {
        val manager = toolbarWindowManager ?: return
        if (::topBar.isInitialized && topBar.parent != null) {
            runCatching { manager.removeViewImmediate(topBar) }
        }
        if (::bottomBar.isInitialized && bottomBar.parent != null) {
            runCatching { manager.removeViewImmediate(bottomBar) }
        }
        toolbarWindowManager = null
        toolbarAttached = false
    }

    private fun hideBoundaryStatus() {
        if (!pendingBoundaryStatus &&
            !initialStatusPending &&
            status.visibility == TextView.GONE
        ) {
            return
        }
        pendingBoundaryStatus = false
        initialStatusPending = false
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        if (status.visibility != TextView.GONE) status.visibility = TextView.GONE
    }

    private fun clearPendingBoundaryCaptchaRetry() {
        pendingBoundaryCaptchaRetry = false
        pendingCaptchaRetryDirection = 0
        pendingCaptchaRetryAnchor = -1
    }

    private fun finishSuppressedBoundaryCaptcha(reason: String) {
        val hadBoundaryPending =
            pendingBoundaryStatus ||
                pendingBoundaryCaptchaRetry ||
                pendingCaptchaRetryDirection != 0 ||
                pendingCaptchaRetryAnchor >= 0 ||
                deferredBoundaryDirection != 0 ||
                deferredBoundaryAnchor >= 0
        clearPendingBoundaryCaptchaRetry()
        pendingBoundaryStatus = false
        pendingBoundaryStartInteractionMs = 0L
        deferredBoundaryDirection = 0
        deferredBoundaryAnchor = -1
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
        hideBoundaryStatus()
        if (::renderView.isInitialized) {
            renderView.finishBoundaryDispatch()
        }
        Log.d(TAG, "boundary_append_finish_suppressed_captcha reason=$reason,pending=$hadBoundaryPending")
    }

    private fun markPrependRevealRequest(direction: Int, startResult: ReaderSession.AppendStartResult?) {
        if (direction == ReaderSurfaceView.DIRECTION_PREVIOUS && startResult == ReaderSession.AppendStartResult.STARTED) {
            pendingPrependRevealRequests++
        }
    }

    private fun consumePrependedBoundaryReveal(insertedCount: Int): Boolean {
        val reveal = shouldRevealPrependedBoundary(pendingPrependRevealRequests, insertedCount)
        if (pendingPrependRevealRequests > 0) pendingPrependRevealRequests--
        if (reveal && isCurrentNtkReader()) {
            Log.d(TAG, "pages_prepended_reveal_deferred_ntk inserted=$insertedCount")
            return false
        }
        return reveal
    }

    private fun installInitialDrawGate(root: View, timeoutMs: Long) {
        initialDrawGateOpen = false
        initialDrawGateNtkTimeoutDeferrals = 0
        initialDrawGateView = root
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        val listener = ViewTreeObserver.OnPreDrawListener {
            initialDrawGateOpen || destroyed || isFinishing
        }
        initialDrawGateListener = listener
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(listener)
        statusHandler.postDelayed(initialDrawGateTimeoutRunnable, timeoutMs)
    }

    private fun releaseInitialDrawGate(reason: String) {
        if (initialDrawGateOpen) return
        initialDrawGateOpen = true
        initialDrawGateNtkTimeoutDeferrals = 0
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        val view = initialDrawGateView
        removeInitialDrawGateListener()
        view?.invalidate()
        Log.d(TAG, "initial_draw_gate_released reason=$reason")
    }

    private fun removeInitialDrawGateListener() {
        val view = initialDrawGateView
        val listener = initialDrawGateListener
        initialDrawGateListener = null
        if (view != null && listener != null) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        initialDrawGateView = null
    }

    private fun shouldDeferNtkInitialDrawGateTimeout(): Boolean {
        if (destroyed || isFinishing || initialDrawGateOpen) return false
        if (!isCurrentNtkReader() || firstDrawableMetricLogged) return false
        if (initialDrawGateNtkTimeoutDeferrals >= NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MAX) {
            val snapshot = renderView.visibleCoverageSnapshot()
            Log.d(
                TAG,
                "initial_draw_gate_timeout_open reason=ntk_cap_reached," +
                    "loading=${snapshot?.visibleLoading ?: -1}," +
                    "placeholderPx=${snapshot?.placeholderPx ?: -1}," +
                    "missingPx=${snapshot?.missingPx ?: -1}," +
                    "drawablePx=${snapshot?.drawablePx ?: -1}"
            )
            return false
        }
        return true
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    fun testEpisode(episodeNumber: Int): Manga? {
        return findTestEpisode { episode ->
            testEpisodeNumber(episode.name) == episodeNumber
        }
    }

    fun testEpisode(episodeName: String): Manga? {
        return findTestEpisode { episode ->
            episode.name?.contains(episodeName) == true
        }
    }

    fun testSetEpisodeImages(episodeNumber: Int, images: List<String>): Boolean {
        var found = false
        testEpisodeLists().forEach { episodes ->
            episodes.forEach { episode ->
                if (testEpisodeNumber(episode.name) == episodeNumber) {
                    episode.setImgs(images)
                    found = true
                }
            }
        }
        return found
    }

    fun testOpenEpisode(episode: Manga) {
        val source = currentManga ?: episode
        launchAdjacent(source, episode, currentTitle ?: episode.title)
    }

    fun testPrepareForNextLaunch() {
        destroyed = true
        strictNtkPendingSessionPath = ""
        strictNtkManifestSubscription?.close()
        strictNtkManifestSubscription = null
        ntkAckPreflightGeneration.incrementAndGet()
        activeReaderSessionGeneration.incrementAndGet()
        clearStrictAuthoritativeInstallQueue()
        synchronized(strictRenderReadyLock) {
            strictRenderReadyPages.clear()
            strictRenderReadyGeneration = -1
            strictAllImagesReadyPublished = false
            strictRollingHistoricalScene = false
        }
        preparedSessionBuildTask?.cancel()
        preparedSessionBuildTask = null
        preparedSessionStartTask?.cancel()
        preparedSessionStartTask = null
        currentManga?.ntkEpisodePath?.let { path ->
        }
        progressHandler.removeCallbacks(saveProgressRunnable)
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        missingEpisodePromptState.dismiss()
        removeInitialDrawGateListener()
        pendingProgressInfo = null
        pendingBoundaryStatus = false
        pendingBoundaryCaptchaRetry = false
        pendingPrependRevealRequests = 0
        deferredBoundaryDirection = 0
        deferredBoundaryAnchor = -1
        getHttpClient().cancelNtkWebViewFallbacks()
        criticalUiHandler.removeCallbacksAndMessages(null)
        if (::renderView.isInitialized) {
            renderView.setWindowListener(null)
            renderView.stopRenderingAndClearPages()
        }
        clearPendingPageCallbacks()
        val activeSession = session
        if (activeSession != null &&
            (activeSession !== preparedBuiltSession || preparedSessionStartBegan)
        ) {
            activeSession.cancel()
        }
        synchronized(strictEarlySessionLock) {
            strictEarlySession.also { early ->
                if (early != null && early.session !== activeSession) early.session.cancel()
            }
            strictEarlySession = null
        }
        session = null
        preparedBuiltSession = null
        preparedSessionStartBegan = false
        preparedLaunchLease?.close()
        preparedLaunchLease = null
        preparedSurfaceBitmaps = emptyMap()
        preparedSurfaceAdoptionActive = false
    }

    fun testCurrentProgressPosition(): ReaderSurfaceView.ProgressPosition? {
        if (hybridNtkBrowserActive) {
            val snapshot = currentHybridNtkScrollSnapshot()
            return ReaderSurfaceView.ProgressPosition(currentPage, snapshot?.scrollY ?: 0)
        }
        return renderView.currentProgressPosition()
    }

    fun testCurrentScrollPositionSnapshot(): ReaderSurfaceView.ScrollPositionSnapshot? {
        if (hybridNtkBrowserActive) {
            val snapshot = currentHybridNtkScrollSnapshot()
            val viewport = maxOf(1, hybridNtkWebView?.height ?: resources.displayMetrics.heightPixels)
            val content = snapshot?.contentHeight ?: maxOf(viewport, ((hybridNtkWebView?.contentHeight ?: 0) * (hybridNtkWebView?.scale ?: 1f)).toInt())
            val maxScroll = snapshot?.maxScroll ?: (content - viewport).coerceAtLeast(0)
            val scrollY = snapshot?.scrollY ?: (hybridNtkWebView?.scrollY ?: 0).coerceIn(0, maxScroll)
            return ReaderSurfaceView.ScrollPositionSnapshot(
                currentPage,
                scrollY,
                scrollY,
                content,
                maxScroll,
                false
            )
        }
        return renderView.currentScrollPositionSnapshot()
    }

    fun testScrollByPixels(deltaPx: Float) {
        if (hybridNtkBrowserActive) {
            val view = hybridNtkWebView ?: return
            view.scrollBy(0, deltaPx.toInt())
            refreshHybridNtkScrollFromView()
            return
        }
        renderView.testScrollByPixels(deltaPx)
    }

    fun testFirstDrawableElapsedMs(): Long {
        return firstDrawableElapsedMsForTest
    }

    fun testCurrentNtkEpisodePath(): String? {
        return currentManga?.ntkEpisodePath
    }

    fun testCurrentNtkImageCount(): Int {
        return currentManga?.ntkImageCount?.coerceAtLeast(0) ?: 0
    }

    fun testInitialContinuousDrawableElapsedMs(requiredPages: Int): Long {
        if (requiredPages <= 0) return -1L
        val first = if (initialStartAtFirstPage) 0 else currentPage
        var elapsed = 0L
        for (page in first until first + requiredPages) {
            val pageElapsed = launchDrawableElapsedMsByPage[page] ?: return -1L
            if (pageElapsed > elapsed) elapsed = pageElapsed
        }
        return elapsed
    }

    fun testVisibleCoverageSnapshot(): ReaderSurfaceView.VisibleCoverageSnapshot? {
        if (hybridNtkBrowserActive) {
            val viewport = maxOf(1, hybridNtkWebView?.height ?: resources.displayMetrics.heightPixels)
            try {
                hybridNtkWebView?.evaluateJavascript(
                    "try{window.__mvNtkPostCoverage&&window.__mvNtkPostCoverage('test');}catch(e){}",
                    null
                )
            } catch (_: Throwable) {
            }
            val coverage = hybridNtkCoverageSnapshot
                ?: NtkBrowserSessionBroker.latestCoverageSnapshot(currentManga?.ntkEpisodePath)
            if (coverage != null) {
                val scale = if (coverage.viewportPx > 0) {
                    viewport.toFloat() / coverage.viewportPx.toFloat()
                } else {
                    1f
                }
                val drawable = (coverage.drawablePx * scale).toInt().coerceIn(0, viewport)
                val scaledMissing = (coverage.missingPx * scale).toInt().coerceAtLeast(0)
                val missing = maxOf(0, viewport - drawable, scaledMissing)
                return ReaderSurfaceView.VisibleCoverageSnapshot(
                    viewportPx = viewport,
                    drawablePx = drawable,
                    missingPx = missing,
                    placeholderPx = missing,
                    drawableItems = coverage.drawableItems,
                    totalItems = maxOf(coverage.totalItems, coverage.pageCount),
                    visibleLoading = coverage.visibleLoading + if (missing > 0 && coverage.visibleLoading == 0) 1 else 0,
                    visibleErrors = coverage.visibleErrors,
                    visibleCards = 0,
                    busy = coverage.visibleLoading > 0,
                    pageCount = pageCount,
                    physicalViewportPx = viewport
                )
            }
            val ready = hybridNtkFirstDrawableReady && hybridNtkViewportReady
            val drawable = if (ready) viewport else 0
            return ReaderSurfaceView.VisibleCoverageSnapshot(
                viewportPx = viewport,
                drawablePx = drawable,
                missingPx = if (ready) 0 else viewport,
                placeholderPx = if (ready) 0 else viewport,
                drawableItems = if (hybridNtkFirstDrawableReady) 1 else 0,
                totalItems = 1,
                visibleLoading = if (ready) 0 else 1,
                visibleErrors = 0,
                visibleCards = 0,
                busy = false,
                pageCount = pageCount,
                physicalViewportPx = viewport
            )
        }
        val refreshed = renderView.refreshVisibleCoverageSnapshot()
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        val lastNative = lastNativeVisibleCoverageSnapshot
        if (
            isCurrentNtkReader() &&
            lastNative != null &&
            lastNativeVisibleCoveragePath == path &&
            isNativeCoverageViewportReady(lastNative) &&
            isTransientEmptyNativeCoverage(refreshed)
        ) {
            Log.d(
                TAG,
                "test_visible_coverage_use_last_native path=$path," +
                    "refreshedPages=${refreshed?.pageCount ?: -1}," +
                    "refreshedViewport=${refreshed?.viewportPx ?: -1}"
            )
            return lastNative
        }
        return refreshed
    }

    fun testPageReadinessSnapshot(): ReaderSurfaceView.PageReadinessSnapshot {
        if (hybridNtkBrowserActive) return hybridNtkPageReadinessSnapshot()
        return renderView.pageReadinessSnapshot()
    }

    fun testSessionPageReadinessSnapshot(): ReaderSurfaceView.PageReadinessSnapshot {
        if (hybridNtkBrowserActive) return hybridNtkPageReadinessSnapshot()
        val surfaceSnapshot = renderView.pageReadinessSnapshot()
        if (surfaceSnapshot.pageCount > 0) return surfaceSnapshot
        return session?.pageReadinessSnapshotForTest() ?: surfaceSnapshot
    }

    fun testForwardRunwaySnapshot(): ReaderSurfaceView.ForwardRunwaySnapshot? {
        if (hybridNtkBrowserActive) return null
        return renderView.forwardRunwaySnapshot()
    }

    private fun hybridNtkPageReadinessSnapshot(): ReaderSurfaceView.PageReadinessSnapshot {
        val expected = currentManga?.ntkImageCount?.coerceAtLeast(0) ?: 0
        val path = currentManga?.ntkEpisodePath?.trim().orEmpty()
        val decode = NtkBrowserSessionBroker.latestDecodeSnapshot(path)
        val decodedAuthoritativeCount = if (
            decode != null &&
            decode.total > 0 &&
            expected > 0 &&
            decode.total < expected &&
            decode.failed == 0
        ) {
            decode.total
        } else {
            0
        }
        val count = if (decodedAuthoritativeCount > 0) {
            decodedAuthoritativeCount
        } else {
            maxOf(pageCount, expected, decode?.expected ?: 0, decode?.total ?: 0)
        }
        val decoded = (decode?.decoded ?: 0).coerceIn(0, count)
        val failed = (decode?.failed ?: 0).coerceIn(0, count)
        val ready = count > 0 && NtkBrowserSessionBroker.isAllDecodedReady(path, count)
        val drawable = if (ready) count else decoded
        val loading = if (ready) 0 else (count - drawable - failed).coerceAtLeast(0)
        val unresolvedIndexes = if (loading > 0) {
            (drawable until minOf(count, drawable + 32)).joinToString("|")
        } else {
            ""
        }
        return ReaderSurfaceView.PageReadinessSnapshot(
            pageCount = count,
            drawablePages = drawable,
            loadingPages = loading,
            errorPages = failed,
            cardPages = 0,
            unresolvedPages = loading,
            unresolvedIndexes = unresolvedIndexes
        )
    }

    fun testResetTouchDeliveryStats() {
        touchDeliveryStatsArmed = true
        touchDeliverySamples = 0
        touchDeliveryMoveSamples = 0
        touchDeliveryDownEvents = 0
        touchDeliveryUpEvents = 0
        touchDeliveryCancelEvents = 0
        touchDeliveryInvalidEventTimes = 0
        touchDeliveryMaxLagMs = 0L
        touchDeliveryDownMaxLagMs = 0L
        touchDeliveryMoveMaxLagMs = 0L
        touchDeliveryUpMaxLagMs = 0L
        touchDeliveryCancelMaxLagMs = 0L
    }

    fun testTouchDeliverySnapshot(): TouchDeliverySnapshot {
        val snapshot = TouchDeliverySnapshot(
            samples = touchDeliverySamples,
            moveSamples = touchDeliveryMoveSamples,
            downEvents = touchDeliveryDownEvents,
            upEvents = touchDeliveryUpEvents,
            cancelEvents = touchDeliveryCancelEvents,
            invalidEventTimes = touchDeliveryInvalidEventTimes,
            maxLagMs = touchDeliveryMaxLagMs,
            downMaxLagMs = touchDeliveryDownMaxLagMs,
            moveMaxLagMs = touchDeliveryMoveMaxLagMs,
            upMaxLagMs = touchDeliveryUpMaxLagMs,
            cancelMaxLagMs = touchDeliveryCancelMaxLagMs
        )
        touchDeliveryStatsArmed = false
        return snapshot
    }

    fun testFirstResumePhysicalDrawProof(): FirstPhysicalDrawProof? = firstResumePhysicalDrawProof

    fun testFirstFocusPhysicalDrawProof(): FirstPhysicalDrawProof? = firstFocusPhysicalDrawProof

    fun testFirstResumeArmedUptimeNanos(): Long = firstResumeArmedUptimeNanos

    fun testFrameStatsSnapshot(): ReaderSurfaceView.FrameStatsSnapshot? {
        if (hybridNtkBrowserActive) {
            try {
                hybridNtkWebView?.evaluateJavascript(
                    "try{window.__mvNtkPostFrameStats&&window.__mvNtkPostFrameStats('test');}catch(e){}",
                    null
                )
            } catch (_: Throwable) {
            }
            return NtkBrowserSessionBroker.latestFrameStatsSnapshot(currentManga?.ntkEpisodePath)
        }
        return renderView.frameStatsSnapshot()
    }

    fun testResetFrameStatsSnapshot() {
        if (hybridNtkBrowserActive) {
            val path = currentManga?.ntkEpisodePath
            NtkBrowserSessionBroker.resetFrameStats(path)
            try {
                hybridNtkWebView?.evaluateJavascript(
                    "try{window.__mvNtkResetFrameStats&&window.__mvNtkResetFrameStats();}catch(e){}",
                    null
                )
            } catch (_: Throwable) {
            }
            return
        }
        renderView.resetFrameStatsSnapshot()
    }

    fun testPageCount(): Int {
        if (hybridNtkBrowserActive) refreshHybridNtkScrollFromView()
        return pageCount
    }

    fun testCurrentPage(): Int {
        if (hybridNtkBrowserActive) refreshHybridNtkScrollFromView()
        return currentPage
    }

    fun testStatusText(): String {
        return if (::status.isInitialized) status.text?.toString().orEmpty() else ""
    }

    fun testBlockingStatus(): String {
        return blockingStatusForTest
    }

    fun testHasLoadedEpisode(episode: Manga?): Boolean {
        if (hybridNtkBrowserActive) {
            return episode != null &&
                Manga.sameEpisodeIdentity(currentManga, episode) &&
                hybridNtkViewportReady
        }
        return episode != null && session?.containsEpisodeForTest(episode) == true
    }

    private fun findTestEpisode(predicate: (Manga) -> Boolean): Manga? {
        testEpisodeLists().forEach { episodes ->
            episodes.firstOrNull { predicate(it) }?.let { return it }
        }
        return null
    }

    private fun testEpisodeLists(): List<List<Manga>> {
        val lists = ArrayList<List<Manga>>()
        currentTitle?.let { title ->
            Utils.snapshotEpisodes(title).takeIf { it.isNotEmpty() }?.let { lists.add(it) }
        }
        currentManga?.let { manga ->
            Utils.snapshotEpisodes(manga).takeIf { it.isNotEmpty() }?.let { lists.add(it) }
        }
        session?.pageInfo(currentPage)?.manga?.let { manga ->
            Utils.snapshotEpisodes(manga).takeIf { it.isNotEmpty() }?.let { lists.add(it) }
        }
        return lists
    }

    private fun testEpisodeNumber(name: String?): Int {
        if (name == null) return -1
        val match = Regex("""(^|\D)0*(\d+)\s*화""").find(name) ?: return -1
        return match.groupValues.getOrNull(2)?.toIntOrNull() ?: -1
    }

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 1000L
        private const val STRICT_ALL_IMAGES_NATIVE_QUEUE_RETRY_MS = 16L
        private const val INITIAL_STATUS_DELAY_MS = 450L
        private const val BOUNDARY_STATUS_DELAY_MS = 250L
        private const val BOUNDARY_APPEND_QUIET_MS = 1600L
        private const val NTK_APPEND_PUBLISH_INPUT_QUIET_MS = 900L
        private const val NTK_APPEND_PUBLISH_AFTER_SCROLL_ACTIVE_MS = 520L
        private const val NTK_APPEND_PUBLISH_MAX_DEFER_MS = 1600L
        private const val NTK_ACTIVE_APPEND_PUBLISH_QUIET_MS = 2600L
        private const val NTK_ACTIVE_APPEND_PUBLISH_MAX_DEFER_MS = 3600L
        private const val NTK_LARGE_APPEND_PUBLISH_QUIET_MS = 1800L
        private const val NTK_LARGE_APPEND_PUBLISH_MAX_DEFER_MS = 3200L
        private const val NTK_APPEND_PUBLISH_TAIL_PAGE_THRESHOLD = 3
        private const val NTK_APPEND_PUBLISH_TAIL_MIN_PX = 2400
        private const val NTK_CLEAN_APPEND_IMMEDIATE_MAX_PAGES = 8
        private const val NTK_ACTIVE_APPEND_IMMEDIATE_RUNWAY_PAGES = 8
        private const val NTK_ACTIVE_APPEND_CHUNK_RETRY_MS = 160L
        private const val NTK_APPEND_UNTIL_READY_UNCHANGED_RETRY_MS = 900L
        private const val NTK_APPEND_UNTIL_READY_LOG_MS = 1000L
        private const val NTK_APPEND_HOT_PATH_LOG_MS = 250L
        private const val NTK_CURRENT_READY_RUNWAY_ACTIVE_CHUNK_PAGES = 1
        private const val NTK_CURRENT_READY_RUNWAY_ACTIVE_WEBTOON_CHUNK_PAGES = 4
        private const val NTK_CURRENT_READY_RUNWAY_ACTIVE_CHUNK_DELAY_MS = 48L
        private const val BUSY_WINDOW_LOG_INTERVAL_MS = 500L
        private const val INTERACTION_NOTE_INTERVAL_MS = 120L
        private const val ACK_QUIET_TOUCH_EXTEND_INTERVAL_MS = 1800L
        private const val NTK_WEBVIEW_FALLBACK_QUIET_EXTEND_INTERVAL_MS = 500L
        private const val NTK_EPISODE_UPDATE_SCROLL_QUIET_MS = 120L
        private const val NTK_ACTIVE_SCROLL_LOG_QUIET_MS = 600L
        private const val ADJACENT_BUTTON_REFRESH_DELAY_MS = 350L
        private const val ADJACENT_STATUS_DELAY_MS = 180L
        private const val INITIAL_DRAW_GATE_TIMEOUT_MS = 1600L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_MS = 4200L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MS = 700L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MAX = 1
        private const val NTK_INITIAL_CAPTCHA_DEFER_MS = 1800L
        private const val NTK_INITIAL_CAPTCHA_MAX_DEFERS = 2
        private const val NTK_INITIAL_CAPTCHA_PROGRESS_MAX_DEFERS = 5
        private const val DEFERRED_NTK_ACK_PREFLIGHT_TIMEOUT_MS = 45000L
        private const val NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS = 450L
        private const val NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS_STRICT_FRESH = 450L
        private const val NTK_ACK_PREFLIGHT_INITIAL_NO_INTERACTION_QUIET_MS = 30_000L
        private const val NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS = 4_500L
        private const val NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INITIAL_DELAY_MS = 4_500L
        private const val NTK_POST_FIRST_DRAWABLE_FOLLOWUP_INPUT_QUIET_MS = 4_500L
        private const val NTK_POST_FIRST_DRAWABLE_FOLLOWUP_RECHECK_MS = 650L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MS = 220L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS = 220L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MAX_ATTEMPTS = 5
        private const val NTK_ACK_PREFLIGHT_INITIAL_CHALLENGE_WAIT_MAX_ATTEMPTS = 10
        private const val NTK_ACK_PREFLIGHT_INITIAL_IMAGES_READY_HARDBLOCK_ATTEMPTS = 2
        private const val NTK_ACK_INITIAL_CONTINUOUS_PAGES = 3
        private const val NTK_ACK_WEBVIEW_PREFLIGHT_ATTEMPTS = 1
        private const val READER_LOADING_DESCRIPTION = "reader-loading"
        private const val READER_DRAWABLE_READY_DESCRIPTION = "reader-drawable-ready"
        private const val PENDING_PAGE_CALLBACK_FLUSH_BATCH_SIZE = 1
        private const val PENDING_PAGE_CALLBACK_FLUSH_BATCH_DELAY_MS = 16L
        private const val PENDING_PAGE_CALLBACK_FLUSH_ACTIVE_NTK_BATCH_SIZE = 2
        private const val PENDING_PAGE_CALLBACK_FLUSH_ACTIVE_NTK_BATCH_DELAY_MS = 0L
        private const val PENDING_PAGE_CALLBACK_FLUSH_LOG_MS = 250L
        private const val NTK_CURRENT_READY_RUNWAY_BATCH_DELAY_MS = 32L
        private const val DRAWABLE_READY_CHECK_INTERVAL_MS = 80L
        private const val INITIAL_READY_WEBTOON_AHEAD_PAGES = 3
        private const val INITIAL_READY_MANHWA_AHEAD_PAGES = 3
        private const val NTK_INITIAL_METRIC_BEHIND_PAGES = 2
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_WEBTOON_PRIME_PAGES = 24
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_MANHWA_PRIME_PAGES = 18
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_AFTER_ANCHOR_MAX_WAIT_MS = 3200L
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_EARLY_STREAM_MS = 0L
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_STAGGER_MS = 0L
        private const val NTK_ACTIVITY_INITIAL_CONTINUOUS_ANCHOR_POLL_MS = 25L
        private const val NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID = 800000L
        private const val NTK_NUMERIC_PAYLOAD_PARTIAL_HEAD_LIMIT = 20
        private const val NTK_INITIAL_API_PREFETCH_DEFER_MS = 120L
        private const val NTK_INITIAL_API_PREFETCH_MAX_DEFER_MS = 2500L
        private const val NTK_INITIAL_API_PREFETCH_CONTINUOUS_MAX_DEFER_MS = 5200L
        private const val NTK_INITIAL_API_PREFETCH_ACTIVE_INPUT_MAX_DEFER_MS = 12000L
        private const val NTK_HYBRID_NATIVE_FIRST_ATTACH_DELAY_MS = 950L
        private const val INITIAL_GENERATED_RUNWAY_PAGES = 4
        private const val INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX = 2
        private const val CAPTCHA_RETRY_READER = 0
        private const val CAPTCHA_RETRY_TOOLBAR_ADJACENT = 1
        private const val CAPTCHA_RETRY_BOUNDARY = 2
        private const val TAG = "ReaderV2"

        @JvmStatic
        fun pageGapForBaseModeForTest(baseMode: Int): Int = pageGapForBaseMode(baseMode)

        @JvmStatic
        fun shouldEnableAdjacentButtonForTest(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
            return shouldEnableAdjacentButton(hasAdjacent, canFetchMissingAdjacent)
        }

        @JvmStatic
        fun shouldMarkFirstDrawableForTest(index: Int, currentPage: Int): Boolean {
            return index == currentPage
        }

        @JvmStatic
        fun shouldRevealPrependedBoundaryForTest(
            pendingPrependRevealRequests: Int,
            insertedCount: Int
        ): Boolean {
            return shouldRevealPrependedBoundary(
                pendingPrependRevealRequests,
                insertedCount
            )
        }

        @JvmStatic
        fun shouldPrepareNearBoundaryForTest(direction: Int): Boolean {
            return direction != ReaderSurfaceView.DIRECTION_PREVIOUS
        }

        @JvmStatic
        fun progressEpisodeIndexForTest(
            episodes: List<Manga>,
            manga: Manga,
            fallbackIndex: Int
        ): Int {
            return progressEpisodeIndex(episodes, manga, fallbackIndex)
        }

        @JvmStatic
        fun progressEpisodeIdForTest(
            episodes: List<Manga>,
            manga: Manga,
            fallbackIndex: Int
        ): Int {
            val index = progressEpisodeIndex(episodes, manga, fallbackIndex)
            return progressEpisodeForIndex(episodes, index)?.id ?: manga.id
        }

        private fun pageGapForBaseMode(baseMode: Int): Int {
            return ReaderDisplayPolicy.pageGapForBaseMode(baseMode)
        }

        private fun shouldEnableAdjacentButton(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
            return ReaderDisplayPolicy.shouldEnableAdjacentButton(hasAdjacent, canFetchMissingAdjacent)
        }

        private fun shouldRevealPrependedBoundary(
            pendingPrependRevealRequests: Int,
            insertedCount: Int
        ): Boolean {
            return insertedCount > 0 && pendingPrependRevealRequests > 0
        }

        private fun progressEpisodeIndex(
            episodes: List<Manga>,
            manga: Manga,
            fallbackIndex: Int
        ): Int {
            episodes.indexOfFirst { manga.id > 0 && it.id == manga.id }
                .takeIf { it >= 0 }
                ?.let { return it + 1 }
            val path = manga.ntkEpisodePath ?: ""
            if (path.isNotBlank()) {
                episodes.indexOfFirst { path == it.ntkEpisodePath }
                    .takeIf { it >= 0 }
                    ?.let { return it + 1 }
            }
            val number = episodeNumberKey(manga.name)
            if (number.isNotBlank()) {
                episodes.indexOfFirst { number == episodeNumberKey(it.name) }
                    .takeIf { it >= 0 }
                    ?.let { return it + 1 }
            }
            return fallbackIndex
        }

        private fun progressEpisodeForIndex(episodes: List<Manga>, episodeIndex: Int): Manga? {
            val index = episodeIndex - 1
            return episodes.getOrNull(index)
        }

        private fun episodeNumberKey(name: String?): String {
            val visible = Manga.visibleEpisodeNumberKey(name)
            if (visible.isNotBlank()) return visible
            if (name == null) return ""
            return Regex("""\d+(?:\.\d+)?""").findAll(name).lastOrNull()?.value ?: ""
        }
    }
}
