package ml.melun.mangaview.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.atomic.AtomicInteger
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.R
import ml.melun.mangaview.Utils
import ml.melun.mangaview.activity.CaptchaActivity.REQUEST_CAPTCHA
import ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderLaunchPreparer
import ml.melun.mangaview.reader.ReaderImageCache
import ml.melun.mangaview.reader.ReaderWarmupCoordinator
import ml.melun.mangaview.reader.ReaderSession
import ml.melun.mangaview.reader.ReaderSurfaceView
import ml.melun.mangaview.reader.ReaderTile
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.AppDispatchers
import kotlin.math.abs

class ReaderV2Activity : Activity(), ReaderSession.Listener, ReaderSurfaceView.WindowListener {
    private lateinit var renderView: ReaderSurfaceView
    private lateinit var status: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageView: TextView
    private lateinit var prevButton: Button
    private lateinit var episodeButton: Button
    private lateinit var nextButton: Button
    private lateinit var autoCutButton: Button
    private var readerRoot: FrameLayout? = null
    private var toolbarAttached = false
    private var session: ReaderSession? = null
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
    private var destroyed = false
    private var progressSaveArmed = false
    private var progressMovedInGesture = false
    private var pendingInitialRestorePage = -1
    private var pendingInitialRestoreOffset = 0
    @Volatile
    private var blockingStatusForTest = ""
    private val progressHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private var pendingProgressInfo: ReaderSession.PageInfo? = null
    private var pendingProgressOffset = 0
    private var pendingBoundaryStatus = false
    private var pendingBoundaryCaptchaRetry = false
    private var pendingPrependRevealRequests = 0
    private var readerWindowBusy = false
    private var deferredBoundaryDirection = 0
    private var deferredBoundaryAnchor = -1
    private var pendingBoundaryStartInteractionMs = 0L
    private var lastReaderInteractionMs = 0L
    private var lastReaderBusyMs = 0L
    private var lastBusyWindowLogMs = 0L
    private var lastLoggedWindowBusy = false
    private var lastInteractionNoteMs = 0L
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
    private var firstDrawableMetricLogged = false
    private var firstDrawableLoggedAtMs = 0L
    private var firstDrawableLoggedElapsedAtMs = 0L
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
    private var deferredNtkAckPreflightManga: Manga? = null
    private var ntkAckCaptchaRequested = false
    private var pendingInitialNtkCaptchaDeferrals = 0
    private var ntkInitialProofRetryPath = ""
    private var ntkInitialProofRetryCount = 0
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

    private data class AdjacentResolution(
        val target: Manga?,
        val title: Title?,
        val result: Int,
        val fetchedEpisodes: Boolean,
        val preparedKey: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTouchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val title = Gson().fromJson<Title?>(intent.getStringExtra("title"), object : TypeToken<Title?>() {}.type)
        val manga = Gson().fromJson<Manga?>(intent.getStringExtra("manga"), object : TypeToken<Manga?>() {}.type)
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
        ReaderChromeStyler.applyReaderWindow(this)
        manga.startNtkVerifiedInitialImageProbe(getHttpClient())
        if (shouldDeferInitialNtkAckPreflight(manga)) {
            deferredNtkAckPreflightManga = manga
            deferredNtkAckPreflightBlockProbeAttempts = 0
            Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_wait_first_drawable path=${manga.ntkEpisodePath}")
            Log.d(TAG, "ntk_ack_blocked_before_first_drawable reason=initial_setup,path=${manga.ntkEpisodePath}")
            statusHandler.postDelayed(
                deferredNtkAckPreflightTimeoutRunnable,
                DEFERRED_NTK_ACK_PREFLIGHT_TIMEOUT_MS
            )
            statusHandler.postDelayed(
                deferredNtkAckPreflightBlockProbeRunnable,
                NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MS
            )
            val existing = intent.getBooleanExtra("viewerNtkAckPreflightStarted", false)
            Log.d(TAG, "reader_ntk_ack_preflight_deferred path=${manga.ntkEpisodePath},nativeStarted=$existing")
        } else {
            startCurrentNtkAckPreflight(manga)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        renderView = ReaderSurfaceView(this).also {
            it.id = R.id.strip
            it.isClickable = true
            it.setBackgroundColor(Color.BLACK)
            it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.contentDescription = null
            it.setLimitScrollToDrawablePrefix(
                viewerLaunchSourceSite.equals("ntk", ignoreCase = true) ||
                    manga.ntkEpisodePath?.isNotBlank() == true
            )
            it.setWindowListener(this)
        }
        status = TextView(this).apply {
            text = "로딩 중"
            setTextColor(0xffcccccc.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }
        val preparedKey = intent.getStringExtra(ReaderLaunchPreparer.EXTRA_PREPARED_KEY)
        val startAtFirstPage = intent.getBooleanExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, false)
        startReaderSession(
            manga,
            title,
            preparedKey,
            startAtFirstPage
        )
        if (deferredNtkAckPreflightManga === manga) {
            prepareDeferredNtkAckChallenge(manga)
        }
        renderView.setPageGapPx(pageGapForBaseMode(manga.baseMode))
        status.text = displayEpisodeTitle(manga, title)
        root.addView(renderView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        readerRoot = root
        if (
            viewerLaunchStartedAtMs > 0L &&
            manga.isOnline
        ) {
            val gateTimeoutMs = if (viewerLaunchSourceSite.equals("ntk", ignoreCase = true)) {
                NTK_INITIAL_DRAW_GATE_TIMEOUT_MS
            } else {
                INITIAL_DRAW_GATE_TIMEOUT_MS
            }
            installInitialDrawGate(root, gateTimeoutMs)
        }
        setContentView(root)
        if (viewerLaunchStartedAtMs > 0L) {
            Log.d("ViewerPerf", "reader_activity_create_from_launch source=$viewerLaunchSourceSite ms=${SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs}")
        }
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
        titleView.text = displayEpisodeTitle(manga, title)
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
        if (!shouldDeferInitialToolbarAttach()) attachToolbarIfNeeded()
        updateResultEpisode(manga)
        if (!manga.isOnline) p?.removeViewerBookmark(manga)
    }

    override fun onPause() {
        saveCurrentReadingProgress()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
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
                    val retryStart = session?.appendAdjacentEpisode(retryAnchor, retryDirection)
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
            renderView.requestRender()
        }
    }

    override fun onDestroy() {
        destroyed = true
        ntkAckPreflightGeneration.incrementAndGet()
        currentManga?.ntkEpisodePath?.let { path ->
            ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "destroy")
            ReaderImageCache.clearNtkAckRecoveryPriority(path, "destroy")
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
        missingEpisodePromptState.dismiss()
        removeInitialDrawGateListener()
        saveCurrentReadingProgress()
        pendingProgressInfo = null
        pendingBoundaryStatus = false
        pendingBoundaryCaptchaRetry = false
        pendingPrependRevealRequests = 0
        deferredBoundaryDirection = 0
        deferredBoundaryAnchor = -1
        renderView.setWindowListener(null)
        renderView.stopRenderingAndClearPages()
        clearPendingPageCallbacks()
        session?.cancel()
        session = null
        super.onDestroy()
    }

    override fun onPagesReady(count: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        pagesReady = true
        initialStatusPending = false
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        hideBoundaryStatus()
        pageCount = count
        renderView.setPageCount(
            count,
            deferInitialEmptyDraw = viewerLaunchSourceSite.equals("ntk", ignoreCase = true) &&
                !firstDrawableMetricLogged
        )
        updateCurrentEpisode(currentPage)
        flushPendingPageCallbacks()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed > 32L) {
            Log.d(TAG, "reader_on_pages_ready_ms count=$count,ms=$elapsed,pendingBitmaps=${pendingPageBitmaps.size},pendingTiles=${pendingPageTiles.size}")
        }
    }

    override fun onPagesAppended(count: Int) {
        MainThreadStallMonitor.trace("reader_on_pages_appended") {
            if (pagesReady) {
                hideBoundaryStatus()
                pageCount = count
                renderView.appendPageCount(count)
                flushPendingPageCallbacks()
                Log.d(TAG, "pages_appended total=$count currentPage=$currentPage")
                updateCurrentEpisode(currentPage)
            }
        }
    }

    override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) {
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
        if (pagesReady) {
            hideBoundaryStatus()
            pageCount = totalCount
            currentPage = when {
                currentPage >= startIndex + removedCount -> currentPage - removedCount
                currentPage >= startIndex -> startIndex.coerceAtMost((totalCount - 1).coerceAtLeast(0))
                else -> currentPage
            }
            if (pendingInitialRestorePage >= startIndex + removedCount) {
                pendingInitialRestorePage -= removedCount
            } else if (pendingInitialRestorePage >= startIndex) {
                pendingInitialRestorePage = -1
                pendingInitialRestoreOffset = 0
            }
            renderView.removePageRange(startIndex, removedCount)
            Log.d(TAG, "pages_removed start=$startIndex removed=$removedCount total=$totalCount currentPage=$currentPage")
            updateCurrentEpisode(currentPage.coerceAtMost((totalCount - 1).coerceAtLeast(0)))
            if (totalCount > 0 && currentPage >= totalCount - 1 && pendingPrependRevealRequests <= 0) {
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
            if (pagesReady && index < pageCount) {
                applyPageBitmap(index, bitmap)
            } else {
                rememberPendingPageBitmap(index, bitmap)
            }
        }
    }

    override fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        MainThreadStallMonitor.trace("reader_on_page_tiles_ready") {
            if (pagesReady && index < pageCount) {
                applyPageTiles(index, pageWidth, pageHeight, tiles)
            } else {
                rememberPendingPageTiles(index, pageWidth, pageHeight, tiles)
            }
        }
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
        if (pagesReady) renderView.clearPageBitmap(index)
    }

    private fun rememberPendingPageBitmap(index: Int, bitmap: Bitmap) {
        pendingPageTiles.remove(index)
        pendingPageCards.remove(index)
        pendingPageErrors.remove(index)
        pendingPageBitmaps[index] = bitmap
        bootstrapDeferredFirstPage(index, bitmap)
    }

    private fun rememberPendingPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        pendingPageBitmaps.remove(index)
        pendingPageCards.remove(index)
        pendingPageErrors.remove(index)
        pendingPageTiles[index] = PendingPageTiles(pageWidth, pageHeight, tiles)
        bootstrapDeferredFirstPageTiles(index, pageWidth, pageHeight, tiles)
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
        pendingPageBitmaps.remove(index)
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
        pendingPageTiles.remove(index)
        Log.d(TAG, "page_tiles_deferred_bootstrap index=$index count=$pageCount tiles=${tiles.size}")
        applyPageTiles(index, pageWidth, pageHeight, tiles)
    }

    private fun flushPendingPageCallbacks() {
        if (!pagesReady) return
        if (
            pendingPageBitmaps.isEmpty() &&
            pendingPageTiles.isEmpty() &&
            pendingPageCards.isEmpty() &&
            pendingPageErrors.isEmpty()
        ) return
        val bitmaps = LinkedHashMap(pendingPageBitmaps)
        val tiles = LinkedHashMap(pendingPageTiles)
        val cards = LinkedHashMap(pendingPageCards)
        val errors = LinkedHashMap(pendingPageErrors)
        clearPendingPageCallbacks()
        val indexes = (bitmaps.keys + tiles.keys + cards.keys + errors.keys).distinct().sorted()
        Log.d(TAG, "page_ready_deferred_flush count=${indexes.size} bitmaps=${bitmaps.size} tiles=${tiles.size} cards=${cards.size} errors=${errors.size}")
        for (index in indexes) {
            when {
                errors.containsKey(index) -> applyPageError(index, errors.getValue(index))
                cards.containsKey(index) -> applyPageCard(index, cards.getValue(index))
                tiles.containsKey(index) -> {
                    val pending = tiles.getValue(index)
                    applyPageTiles(index, pending.pageWidth, pending.pageHeight, pending.tiles)
                }
                bitmaps.containsKey(index) -> applyPageBitmap(index, bitmaps.getValue(index))
            }
        }
    }

    private fun clearPendingPageCallbacks() {
        pendingPageBitmaps.clear()
        pendingPageTiles.clear()
        pendingPageCards.clear()
        pendingPageErrors.clear()
    }

    private fun applyPageBitmap(index: Int, bitmap: Bitmap) {
        hideBoundaryStatus()
        renderView.setPageBitmap(index, bitmap)
        val visibleInitialDrawable = shouldMarkFirstDrawable(index, currentPage)
        logLaunchDrawableMetric(index, "bitmap")
        val coversInitialViewport = visibleInitialDrawable &&
            drawableCoversInitialViewport(bitmap.width, bitmap.height)
        val waitForContinuous = shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable) &&
            !coversInitialViewport
        if (visibleInitialDrawable && !waitForContinuous) logFirstDrawableMetric(index, "bitmap")
        if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
        if (waitForContinuous) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (visibleInitialDrawable) {
            releaseInitialDrawGate("page")
        } else if (isCurrentNtkReader() && !firstDrawableMetricLogged) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        }
    }

    private fun applyPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        hideBoundaryStatus()
        renderView.setPageTiles(index, pageWidth, pageHeight, tiles)
        val visibleInitialDrawable = shouldMarkFirstDrawable(index, currentPage)
        logLaunchDrawableMetric(index, "tiles")
        val coversInitialViewport = visibleInitialDrawable &&
            drawableCoversInitialViewport(pageWidth, pageHeight)
        val waitForContinuous = shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable) &&
            !coversInitialViewport
        if (visibleInitialDrawable && !waitForContinuous) logFirstDrawableMetric(index, "tiles")
        if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
        if (waitForContinuous) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
        } else if (visibleInitialDrawable) {
            releaseInitialDrawGate("tiles")
        } else if (isCurrentNtkReader() && !firstDrawableMetricLogged) {
            maybeReleaseInitialNtkContinuousGateOrViewport("initial_continuous")
            scheduleNtkDrawableReadyPolling()
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
        if (firstDrawableMetricLogged || viewerLaunchStartedAtMs <= 0L) return
        firstDrawableMetricLogged = true
        firstDrawableLoggedAtMs = SystemClock.uptimeMillis()
        firstDrawableLoggedElapsedAtMs = SystemClock.elapsedRealtime()
        scheduleDrawableReadyDescription(index)
        val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
        firstDrawableElapsedMsForTest = elapsed
        Log.d("ViewerPerf", "reader_open_to_first_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
        ReaderImageCache.releaseNtkAckRecoveryAfterFirstDrawable(currentManga?.ntkEpisodePath)
        scheduleDeferredNtkAckAfterFirstDrawable("first_drawable")
        statusHandler.post { attachToolbarIfNeeded() }
        maybeStartDeferredNtkAckAfterInitialContinuous()
    }

    private fun scheduleDrawableReadyDescription(index: Int) {
        if (drawableReadyDescriptionPosted) return
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        if (initialStartAtFirstPage && index == 0) {
            postDrawableReadyDescription()
            return
        }
        drawableReadyDescriptionRunnable.run()
    }

    private fun isVisibleViewportReady(): Boolean {
        val snapshot = renderView.visibleCoverageSnapshot() ?: return false
        if (
            snapshot.drawablePx > 0 &&
            snapshot.visibleLoading == 0 &&
            snapshot.missingPx == 0 &&
            snapshot.placeholderPx == 0
        ) {
            return true
        }
        return false
    }

    private fun drawableCoversInitialViewport(pageWidth: Int, pageHeight: Int): Boolean {
        if (!isCurrentNtkReader()) return false
        if (pageWidth <= 0 || pageHeight <= 0) return false
        val viewWidth = renderView.width
        val viewHeight = renderView.height
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val drawHeight = viewWidth * (pageHeight / pageWidth.toFloat())
        return drawHeight >= viewHeight - INITIAL_VIEWPORT_COVERAGE_TOLERANCE_PX
    }

    private fun isInitialContinuousScrollReady(): Boolean {
        if (pageCount <= 0) return launchDrawableMetricPages.isNotEmpty()
        val readyAhead = if (currentManga?.baseMode == MTitle.base_webtoon) {
            INITIAL_READY_WEBTOON_AHEAD_PAGES
        } else {
            INITIAL_READY_MANHWA_AHEAD_PAGES
        }
        val firstRequired = if (!firstDrawableMetricLogged && initialStartAtFirstPage) 0 else currentPage
        val lastRequired = minOf(pageCount - 1, firstRequired + readyAhead)
        for (page in firstRequired..lastRequired) {
            if (!launchDrawableMetricPages.contains(page)) return false
        }
        return true
    }

    private fun shouldWaitForNtkInitialContinuousDrawable(visibleInitialDrawable: Boolean): Boolean {
        return false
    }

    private fun maybeReleaseInitialNtkContinuousGate(reason: String) {
        if (!isCurrentNtkReader() || firstDrawableMetricLogged) return
        if (!isInitialContinuousScrollReady()) return
        val firstDrawablePage = if (initialStartAtFirstPage) 0 else currentPage
        logFirstDrawableMetric(firstDrawablePage, reason)
        releaseInitialDrawGate(reason)
    }

    private fun maybeReleaseInitialNtkContinuousGateOrViewport(reason: String) {
        if (!isCurrentNtkReader() || firstDrawableMetricLogged) return
        if (isVisibleViewportReady()) {
            logVisibleViewportReadyMetric()
            releaseInitialDrawGate("viewport")
            return
        }
        if (requiresInitialNtkWebtoonViewportReady()) return
        maybeReleaseInitialNtkContinuousGate(reason)
    }

    private fun scheduleNtkDrawableReadyPolling() {
        if (!isCurrentNtkReader() || firstDrawableMetricLogged || drawableReadyDescriptionPosted) return
        statusHandler.removeCallbacks(drawableReadyDescriptionRunnable)
        statusHandler.postDelayed(drawableReadyDescriptionRunnable, DRAWABLE_READY_CHECK_INTERVAL_MS)
    }

    private fun requiresInitialNtkWebtoonViewportReady(): Boolean {
        return isCurrentNtkReader() &&
            !firstDrawableMetricLogged &&
            currentManga?.ntkEpisodePath?.startsWith("/webtoon/", ignoreCase = true) == true
    }

    private fun postDrawableReadyDescription() {
        if (drawableReadyDescriptionPosted) return
        drawableReadyDescriptionPosted = true
        renderView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderView.contentDescription = READER_DRAWABLE_READY_DESCRIPTION
        renderView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun logVisibleViewportReadyMetric() {
        if (!firstDrawableMetricLogged && viewerLaunchStartedAtMs > 0L) {
            firstDrawableMetricLogged = true
            firstDrawableLoggedAtMs = SystemClock.uptimeMillis()
            firstDrawableLoggedElapsedAtMs = SystemClock.elapsedRealtime()
            val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
            firstDrawableElapsedMsForTest = elapsed
            Log.d("ViewerPerf", "reader_open_to_first_drawable source=$viewerLaunchSourceSite kind=viewport page=$currentPage ms=$elapsed")
            ReaderImageCache.releaseNtkAckRecoveryAfterFirstDrawable(currentManga?.ntkEpisodePath)
            scheduleDeferredNtkAckAfterFirstDrawable("viewport_drawable")
            statusHandler.post { attachToolbarIfNeeded() }
            maybeStartDeferredNtkAckAfterInitialContinuous()
        }
        postDrawableReadyDescription()
    }

    private fun logLaunchDrawableMetric(index: Int, kind: String) {
        if (viewerLaunchStartedAtMs <= 0L) return
        val first = if (!firstDrawableMetricLogged && initialStartAtFirstPage) 0 else currentPage
        if (index < first || index > first + 2) return
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
        val quietMs = ntkAckPreflightQuietRemainingMs()
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        if (quietMs > 0L) {
            statusHandler.postDelayed(deferredNtkAckPreflightBlockProbeRunnable, quietMs)
            return
        }
        maybeStartDeferredNtkAckForInitialBlock(currentManga, reason)
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
        if (pendingCaptchaRetryAnchor == anchor && pendingCaptchaRetryDirection == direction) {
            val retryBoundaryAfterSilent = silent && suppressedCaptcha && pendingBoundaryStatus && pendingBoundaryCaptchaRetry
            clearPendingBoundaryCaptchaRetry()
            if (retryBoundaryAfterSilent && !destroyed && !isFinishing) {
                pendingBoundaryCaptchaRetry = true
                pendingCaptchaRetryDirection = direction
                pendingCaptchaRetryAnchor = anchor
                val retryStart = session?.appendAdjacentEpisode(anchor, direction)
                markPrependRevealRequest(direction, retryStart)
                if (retryStart != ReaderSession.AppendStartResult.STARTED && retryStart != ReaderSession.AppendStartResult.BUSY) {
                    clearPendingBoundaryCaptchaRetry()
                }
            }
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
            if (!busy || busy != lastLoggedWindowBusy || now - lastBusyWindowLogMs >= BUSY_WINDOW_LOG_INTERVAL_MS) {
                lastBusyWindowLogMs = now
                lastLoggedWindowBusy = busy
                Log.d(
                    TAG,
                    "window_changed first=$firstPage last=$lastPage anchor=$anchorPage " +
                        "progress=$progressPage offset=$progressOffset busy=$busy current=$currentPage"
                )
            }
            currentPage = progressPage
            MainThreadStallMonitor.trace("reader_request_window_async") {
                session?.requestWindowAsync(firstPage, lastPage, anchorPage, busy)
            }
            if (busy) {
                readerWindowBusy = true
                lastReaderBusyMs = SystemClock.uptimeMillis()
                ReaderImageCache.extendNtkAckRecoveryQuiet(
                    currentManga?.ntkEpisodePath,
                    NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS,
                    "window_busy"
                )
                statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
                pendingAnchorAfterBusy = progressPage
                return@trace
            }
            readerWindowBusy = false
            statusHandler.removeCallbacks(deferredBoundaryAppendRunnable)
            statusHandler.postDelayed(deferredBoundaryAppendRunnable, BOUNDARY_APPEND_QUIET_MS)
            pendingAnchorAfterBusy = -1
            MainThreadStallMonitor.trace("reader_update_current_episode") {
                updateCurrentEpisode(progressPage, progressOffset, saveProgress = true)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val lagMs = SystemClock.uptimeMillis() - ev.eventTime
        if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP) {
            MainThreadStallMonitor.warn("reader_touch_delivery_lag", lagMs)
        }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            progressSaveArmed = true
            progressMovedInGesture = true
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (progressMovedInGesture) {
                progressHandler.removeCallbacks(saveProgressRunnable)
                progressHandler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS)
            }
            progressMovedInGesture = false
        } else if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            progressMovedInGesture = false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN ||
            ev.actionMasked == MotionEvent.ACTION_MOVE ||
            ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val now = SystemClock.uptimeMillis()
            if (ev.actionMasked != MotionEvent.ACTION_MOVE || now - lastInteractionNoteMs >= INTERACTION_NOTE_INTERVAL_MS) {
                session?.noteUserInteraction()
                lastInteractionNoteMs = now
            }
            lastReaderInteractionMs = now
            ReaderImageCache.extendNtkAckRecoveryQuiet(
                currentManga?.ntkEpisodePath,
                ntkAckPreflightAfterFirstDrawableQuietMs(),
                "touch"
            )
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNearBoundary(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        if (!shouldPrepareNearBoundaryForTest(direction)) return
        session?.prepareAdjacentEpisode(anchorPage, direction)
    }

    override fun onBoundaryReached(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        Log.d(TAG, "boundary_reached direction=$direction anchorPage=$anchorPage")
        session?.pageInfo(anchorPage)?.let {
            if (!it.transitionCard) currentManga = it.manga
        }
        startBoundaryAppend(direction, anchorPage)
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
        if (readerWindowBusy) return BOUNDARY_APPEND_QUIET_MS
        val lastActiveMs = maxOf(lastReaderInteractionMs, lastReaderBusyMs)
        if (lastActiveMs <= 0L) return 0L
        val quietForMs = SystemClock.uptimeMillis() - lastActiveMs
        return (BOUNDARY_APPEND_QUIET_MS - quietForMs).coerceAtLeast(0L)
    }

    private fun startBoundaryAppend(direction: Int, anchorPage: Int) {
        pendingBoundaryStatus = true
        pendingBoundaryCaptchaRetry = true
        pendingCaptchaRetryDirection = direction
        pendingCaptchaRetryAnchor = anchorPage
        pendingBoundaryStartInteractionMs = lastReaderInteractionMs
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.postDelayed(showBoundaryStatusRunnable, BOUNDARY_STATUS_DELAY_MS)
        val startResult = session?.appendAdjacentEpisode(anchorPage, direction)
        markPrependRevealRequest(direction, startResult)
        if (startResult != ReaderSession.AppendStartResult.STARTED && startResult != ReaderSession.AppendStartResult.BUSY) {
            clearPendingBoundaryCaptchaRetry()
        }
    }

    override fun onTap() {
        setToolbarVisible(!toolbarVisible)
    }

    override fun onVisibleCoverageChanged(snapshot: ReaderSurfaceView.VisibleCoverageSnapshot) {
        if (destroyed || isFinishing || drawableReadyDescriptionPosted) return
        if (
            snapshot.drawablePx > 0 &&
            snapshot.visibleLoading == 0 &&
            snapshot.missingPx == 0 &&
            snapshot.placeholderPx == 0
        ) {
            logVisibleViewportReadyMetric()
        }
    }

    override fun shouldReportVisibleStats(): Boolean {
        return !isCurrentNtkReader() || initialDrawGateOpen
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
        autoCutButton.text = if (autoCut) "자동분할 ON" else "자동분할 OFF"
        autoCutButton.contentDescription = if (autoCut) "자동분할 켜짐" else "자동분할 꺼짐"
        autoCutButton.setTextColor(Color.WHITE)
        autoCutButton.background = roundedBackground(
            if (autoCut) 0xff2f6df6.toInt() else 0xff2a2a2a.toInt(),
            if (autoCut) 0x88ffffff.toInt() else 0xff555555.toInt(),
            8.dp()
        )
    }

    private fun startCurrentNtkAckPreflight(manga: Manga, allowBeforeFirstDrawable: Boolean = false) {
        val path = manga.ntkEpisodePath
        if (path.isNullOrBlank()) return
        if (!isCurrentNtkReader()) return
        if (!firstDrawableMetricLogged) {
            if (allowBeforeFirstDrawable && (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"))) {
                ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "hardblock_preflight_before_first_drawable")
                Log.d(TAG, "reader_ntk_ack_preflight_before_first_drawable reason=hardblock,path=$path")
            } else if (path.startsWith("/webtoon/") || path.startsWith("/manhwa/")) {
                deferredNtkAckPreflightManga = manga
                ReaderImageCache.holdNtkAckRecoveryUntilFirstDrawable(path)
                statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
                statusHandler.postDelayed(
                    deferredNtkAckPreflightBlockProbeRunnable,
                    NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
                )
                Log.d(TAG, "ntk_ack_blocked_before_first_drawable reason=start_current,path=$path")
                return
            }
        }
        if (!allowBeforeFirstDrawable) {
            val strictFloorMs = ntkAckStrictAfterFirstDrawableFloorRemainingMs(manga)
            if (strictFloorMs > 0L) {
                deferredNtkAckPreflightManga = manga
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightBlockProbeRunnable, strictFloorMs)
                Log.d(TAG, "reader_ntk_ack_preflight_start_wait_strict_floor quietMs=$strictFloorMs,path=$path")
                return
            }
            val holdMs = ReaderImageCache.ntkAckRecoveryLaunchHoldRemainingMs(path)
            if (holdMs > 0L) {
                deferredNtkAckPreflightManga = manga
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, holdMs)
                Log.d(TAG, "reader_ntk_ack_preflight_start_wait_recovery_hold quietMs=$holdMs,path=$path")
                return
            }
        }
        Thread({
            try {
                val ackGeneration = ntkAckPreflightGeneration.get()
                val client = getHttpClient()
                client.prepareNtkNativeAckChallengeForWebView(path)
                if (ackGeneration != ntkAckPreflightGeneration.get() || destroyed || isFinishing) {
                    Log.d(TAG, "reader_ntk_ack_preflight_stale_after_prepare path=$path")
                    return@Thread
                }
                var webViewOk = false
                var attempt = 0
                while (!webViewOk && attempt < NTK_ACK_WEBVIEW_PREFLIGHT_ATTEMPTS &&
                    ackGeneration == ntkAckPreflightGeneration.get() && !destroyed && !isFinishing) {
                    if (attempt > 0) Thread.sleep(2_000L)
                    if (ackGeneration != ntkAckPreflightGeneration.get() || destroyed || isFinishing) {
                        Log.d(TAG, "reader_ntk_ack_preflight_stale_before_webview path=$path,attempt=${attempt + 1}")
                        return@Thread
                    }
                    webViewOk = client.performNtkWebViewAckPreflight(path)
                    Log.d(TAG, "reader_ntk_ack_webview_preflight_attempt path=$path,attempt=${attempt + 1},success=$webViewOk")
                    attempt++
                }
                Log.d(TAG, "reader_ntk_ack_webview_preflight_done path=$path,success=$webViewOk")
                val strictProof = client.hasRecentStrictNtkAdAckProof(path)
                if (webViewOk || strictProof) {
                    ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "ack_preflight_success")
                    ReaderImageCache.clearNtkAckRecoveryPriority(path, "ack_preflight_success")
                }
                if (!webViewOk && client.hasRecentCloudflareChallenge()) {
                    Log.d(TAG, "reader_ntk_ack_captcha_suppressed reason=webview_ack_cloudflare,path=$path")
                }
            } catch (e: Exception) {
                Log.d(TAG, "reader_ntk_ack_preflight_error path=$path,$e")
            }
        }, "reader-ntk-ack-preflight").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "reader_ntk_ack_preflight_start path=$path")
    }

    private fun prepareDeferredNtkAckChallenge(manga: Manga) {
        val path = manga.ntkEpisodePath ?: return
        Thread({
            try {
                val ackGeneration = ntkAckPreflightGeneration.get()
                if (ackGeneration != ntkAckPreflightGeneration.get() || destroyed || isFinishing) return@Thread
                getHttpClient().prepareNtkNativeAckChallengeForWebView(path)
                if (ackGeneration != ntkAckPreflightGeneration.get() || destroyed || isFinishing) {
                    Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_stale path=$path")
                    return@Thread
                }
                Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_done path=$path")
            } catch (e: Exception) {
                Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_error path=$path,$e")
            }
        }, "reader-ntk-ack-prepare").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "reader_ntk_ack_deferred_native_prepare_start path=$path")
    }

    private fun startInitialNtkWebViewAckOnlyPreflight(manga: Manga) {
        if (!shouldDeferInitialNtkAckPreflight(manga)) return
        val path = manga.ntkEpisodePath ?: return
        if (!firstDrawableMetricLogged) {
            Log.d(TAG, "ntk_ack_blocked_before_first_drawable reason=initial_webview_ack_only,path=$path")
            return
        }
        Thread({
            try {
                val ok = getHttpClient().performNtkWebViewAckPreflight(path)
                Log.d(TAG, "reader_ntk_ack_initial_webview_preflight_done path=$path,success=$ok")
            } catch (e: Exception) {
                Log.d(TAG, "reader_ntk_ack_initial_webview_preflight_error path=$path,$e")
            }
        }, "reader-ntk-initial-webview-ack").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "reader_ntk_ack_initial_webview_preflight_start path=$path")
    }

    private fun startPreparedNtkNativeAckPreflight(manga: Manga, reason: String): Boolean {
        val path = manga.ntkEpisodePath ?: return false
        if (!isCurrentNtkReader() || destroyed || isFinishing) return false
        deferredNtkAckPreflightManga = null
        statusHandler.removeCallbacks(deferredNtkAckPreflightTimeoutRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
        statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
        val ackGeneration = ntkAckPreflightGeneration.get()
        Thread({
            try {
                val client = getHttpClient()
                val ok = client.performPreparedNtkNativeAck(path)
                val proof = client.hasRecentStrictNtkAdAckProof(path)
                Log.d(
                    TAG,
                    "reader_ntk_ack_prepared_native_preflight_done reason=$reason,path=$path,success=$ok,proof=$proof"
                )
                if (ok || proof) {
                    ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "prepared_native_ack_success")
                    ReaderImageCache.clearNtkAckRecoveryPriority(path, "prepared_native_ack_success")
                } else if (ackGeneration == ntkAckPreflightGeneration.get() && !destroyed && !isFinishing) {
                    deferredNtkAckPreflightManga = manga
                    startDeferredNtkAckPreflight("${reason}_native_failed")
                }
            } catch (e: Exception) {
                Log.d(TAG, "reader_ntk_ack_prepared_native_preflight_error reason=$reason,path=$path,$e")
                if (ackGeneration == ntkAckPreflightGeneration.get() && !destroyed && !isFinishing) {
                    deferredNtkAckPreflightManga = manga
                    startDeferredNtkAckPreflight("${reason}_native_error")
                }
            }
        }, "reader-ntk-prepared-native-ack").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "reader_ntk_ack_prepared_native_preflight_start reason=$reason,path=$path")
        return true
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
        if (getHttpClient().hasRecentStrictNtkAdAckProof(path)) {
            ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "initial_strict_ack_ready")
            ReaderImageCache.clearNtkAckRecoveryPriority(path, "initial_strict_ack_ready")
            return false
        }
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/")
    }

    private fun startDeferredNtkAckPreflight(reason: String, allowBeforeFirstDrawable: Boolean = false) {
        val manga = deferredNtkAckPreflightManga ?: return
        if (!allowBeforeFirstDrawable && !firstDrawableMetricLogged) {
            Log.d(TAG, "reader_ntk_ack_preflight_wait_first_drawable reason=$reason,path=${manga.ntkEpisodePath}")
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            statusHandler.postDelayed(
                deferredNtkAckPreflightBlockProbeRunnable,
                NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
            )
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
            val holdMs = ReaderImageCache.ntkAckRecoveryLaunchHoldRemainingMs(manga.ntkEpisodePath)
            if (holdMs > 0L) {
                statusHandler.removeCallbacks(deferredNtkAckPreflightQuietRunnable)
                statusHandler.postDelayed(deferredNtkAckPreflightQuietRunnable, holdMs)
                Log.d(TAG, "reader_ntk_ack_preflight_deferred_wait_recovery_hold reason=$reason,quietMs=$holdMs,path=${manga.ntkEpisodePath}")
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
        if (destroyed || isFinishing) return
        if (firstDrawableMetricLogged || pagesReady) {
            maybeStartDeferredNtkAckForInitialBlock(deferred, "first_drawable_ready")
            return
        }
        val path = deferred.ntkEpisodePath
        val hasCloudflare = try {
            getHttpClient().hasRecentCloudflareChallenge()
        } catch (_: Exception) {
            false
        }
        val preparedChallenge = try {
            !path.isNullOrBlank() && getHttpClient().hasRecentPreparedNtkNativeAckChallenge(path)
        } catch (_: Exception) {
            false
        }
        val preparingChallenge = try {
            !path.isNullOrBlank() && getHttpClient().isPreparedNtkNativeAckChallengeInFlight(path)
        } catch (_: Exception) {
            false
        }
        deferredNtkAckPreflightBlockProbeAttempts++
        Log.d(
            TAG,
            "reader_ntk_ack_preflight_initial_cf_probe path=$path,attempt=$deferredNtkAckPreflightBlockProbeAttempts,cf=$hasCloudflare,prepared=$preparedChallenge,preparing=$preparingChallenge"
        )
        val ntkEpisodePath = !path.isNullOrBlank() &&
            (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"))
        val hasStrictProof = try {
            !path.isNullOrBlank() && getHttpClient().hasRecentStrictNtkAdAckProof(path)
        } catch (_: Exception) {
            false
        }
        if (ntkEpisodePath && !hasStrictProof &&
            (hasCloudflare || preparingChallenge || deferredNtkAckPreflightBlockProbeAttempts >= 2)
        ) {
            Log.d(
                TAG,
                "reader_ntk_ack_preflight_initial_hardblock_before_first_drawable path=$path,attempt=$deferredNtkAckPreflightBlockProbeAttempts,cf=$hasCloudflare,prepared=$preparedChallenge,preparing=$preparingChallenge"
            )
            maybeStartDeferredNtkAckForInitialBlock(
                deferred,
                "initial_hardblock_before_first_drawable",
                allowBeforeFirstDrawable = true
            )
            return
        }
        if (preparedChallenge && startPreparedNtkNativeAckPreflight(deferred, "prepared_challenge")) {
            return
        }
        if (hasCloudflare) {
            val hasEarlyImages = try {
                !path.isNullOrBlank() &&
                    ReaderImageCache.earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L).isNotEmpty()
            } catch (_: Exception) {
                false
            }
            if (hasEarlyImages && pendingInitialNtkCaptchaDeferrals <= 0) {
                Log.d(
                    TAG,
                    "reader_ntk_ack_preflight_initial_cf_probe_wait_first_drawable path=$path,attempt=$deferredNtkAckPreflightBlockProbeAttempts"
                )
                maybeStartDeferredNtkAckForInitialBlock(
                    deferred,
                    "initial_cf_probe_images_ready"
                )
                return
            }
            if (pendingInitialNtkCaptchaDeferrals <= 0 &&
                deferredNtkAckPreflightBlockProbeAttempts < NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MAX_ATTEMPTS
            ) {
                Log.d(
                    TAG,
                    "reader_ntk_ack_preflight_initial_cf_probe_wait_images path=$path,attempt=$deferredNtkAckPreflightBlockProbeAttempts"
                )
                statusHandler.postDelayed(
                    deferredNtkAckPreflightBlockProbeRunnable,
                    NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
                )
                return
            }
            maybeStartDeferredNtkAckForInitialBlock(
                deferred,
                "initial_cf_probe"
            )
            return
        }
        val hasEarlyImages = try {
            !path.isNullOrBlank() &&
                ReaderImageCache.earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (hasEarlyImages) {
            if (deferredNtkAckPreflightBlockProbeAttempts >= NTK_ACK_PREFLIGHT_INITIAL_IMAGES_READY_HARDBLOCK_ATTEMPTS &&
                pendingInitialNtkCaptchaDeferrals <= 0
            ) {
                Log.d(
                    TAG,
                    "reader_ntk_ack_preflight_initial_images_ready_wait_first_drawable path=$path,attempt=$deferredNtkAckPreflightBlockProbeAttempts"
                )
                maybeStartDeferredNtkAckForInitialBlock(
                    deferred,
                    "initial_images_ready_slow_anchor"
                )
            } else {
                maybeStartDeferredNtkAckForInitialBlock(deferred, "initial_images_ready")
            }
            return
        }
        if (deferredNtkAckPreflightBlockProbeAttempts < NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MAX_ATTEMPTS ||
            (preparingChallenge &&
                deferredNtkAckPreflightBlockProbeAttempts < NTK_ACK_PREFLIGHT_INITIAL_CHALLENGE_WAIT_MAX_ATTEMPTS)
        ) {
            statusHandler.postDelayed(
                deferredNtkAckPreflightBlockProbeRunnable,
                NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
            )
        }
    }

    private fun maybeStartDeferredNtkAckForInitialBlock(
        manga: Manga?,
        reason: String,
        allowBeforeFirstDrawable: Boolean = false
    ) {
        val deferred = deferredNtkAckPreflightManga ?: return
        val deferredPath = deferred.ntkEpisodePath
        val currentPath = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (!deferredPath.isNullOrBlank() && !currentPath.isNullOrBlank() && deferredPath != currentPath) {
            Log.d(TAG, "reader_ntk_ack_preflight_initial_block_skip reason=$reason,deferredPath=$deferredPath,currentPath=$currentPath")
            return
        }
        if (!allowBeforeFirstDrawable && !firstDrawableMetricLogged) {
            Log.d(TAG, "reader_ntk_ack_preflight_wait_first_drawable reason=$reason,path=$deferredPath")
            statusHandler.removeCallbacks(deferredNtkAckPreflightBlockProbeRunnable)
            statusHandler.postDelayed(
                deferredNtkAckPreflightBlockProbeRunnable,
                NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS
            )
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

    private fun isCurrentNtkReader(): Boolean {
        val path = currentManga?.ntkEpisodePath
        return viewerLaunchSourceSite.equals("ntk", ignoreCase = true) ||
            path?.startsWith("/webtoon/") == true ||
            path?.startsWith("/manhwa/") == true
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
        return true
    }

    private fun shouldDeferInitialNtkCaptcha(manga: Manga?): Boolean {
        if (firstDrawableMetricLogged || pagesReady) return false
        if (pendingInitialNtkCaptchaDeferrals >= NTK_INITIAL_CAPTCHA_MAX_DEFERS) return false
        val path = manga?.ntkEpisodePath ?: currentManga?.ntkEpisodePath
        if (path.isNullOrBlank()) return false
        val client = getHttpClient()
        if (client.hasRecentStrictNtkAdAckProof(path) ||
            client.hasNtkAccessProof() ||
            client.hasRecentNtkAccessVerification()) return false
        val shouldDefer = isCurrentNtkReader() && (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"))
        if (shouldDefer && client.hasRecentCloudflareChallenge()) {
            Log.d(TAG, "reader_ntk_captcha_defer_despite_recent_cf path=$path,attempt=${pendingInitialNtkCaptchaDeferrals + 1}")
        }
        return shouldDefer
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
            ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "retry_strict_ack_ready")
            ReaderImageCache.clearNtkAckRecoveryPriority(path, "retry_strict_ack_ready")
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

    private fun startReaderSession(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startAtFirstPage: Boolean = false,
        clearViewImmediately: Boolean = true
    ) {
        pagesReady = false
        pageCount = 0
        currentPage = 0
        pendingInitialNtkCaptchaDeferrals = 0
        val ntkPath = manga.ntkEpisodePath
        if (ntkPath?.let { it.startsWith("/webtoon/") || it.startsWith("/manhwa/") } == true) {
            ntkAckPreflightGeneration.incrementAndGet()
            Log.d(
                "ViewerPerf",
                "reader_activity_start_session path=$ntkPath,clear=$clearViewImmediately"
            )
            getHttpClient().cancelNtkWebViewFallbacks()
            ReaderImageCache.prioritizeNtkAckRecovery(ntkPath)
            if (getHttpClient().hasRecentStrictNtkAdAckProof(ntkPath)) {
                ReaderImageCache.clearNtkAckRecoveryLaunchHold(ntkPath, "session_strict_ack_ready")
                ReaderImageCache.clearNtkAckRecoveryPriority(ntkPath, "session_strict_ack_ready")
            } else {
                ReaderImageCache.holdNtkAckRecoveryUntilFirstDrawable(ntkPath)
            }
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
        firstDrawableMetricLogged = false
        firstDrawableLoggedAtMs = 0L
        firstDrawableLoggedElapsedAtMs = 0L
        firstDrawableElapsedMsForTest = -1L
        initialDrawGateNtkTimeoutDeferrals = 0
        drawableReadyDescriptionPosted = false
        launchDrawableMetricPages.clear()
        launchDrawableElapsedMsByPage.clear()
        if (clearViewImmediately) renderView.setPageCount(0)
        session?.cancel()
        session = ReaderSession(
            this,
            manga,
            title,
            readerWidthPx(),
            readerHeightPx(),
            autoCut,
            p?.getReverse() == true,
            preparedKey,
            startAtFirstPage,
            this
        ).also {
            initialStatusPending = true
            statusHandler.postDelayed(showInitialStatusRunnable, INITIAL_STATUS_DELAY_MS)
            it.start()
        }
    }

    private fun updateAdjacentButtons() {
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
        prevButton.isEnabled = previous
        nextButton.isEnabled = next
        prevButton.alpha = if (previous) 1f else 0.35f
        nextButton.alpha = if (next) 1f else 0.35f
    }

    private fun primeAdjacentLaunchWindow(title: Title?, target: Manga?) {
        if (target == null) return
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
            if (episodeChanged || titleView.text.toString() != displayTitle) {
                titleView.text = displayTitle
                lastDisplayedEpisodeKey = displayKey
                lastDisplayedEpisodeTitle = displayTitle
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
        if (visible) attachToolbarIfNeeded()
        toolbarVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomBar.visibility = visibility
        renderView.setScrollbarVisible(visible)
    }

    private fun shouldDeferInitialToolbarAttach(): Boolean {
        return isCurrentNtkReader() && !firstDrawableMetricLogged
    }

    private fun shouldPrimeAdjacentNow(): Boolean {
        return !isCurrentNtkReader() || firstDrawableMetricLogged || toolbarAttached
    }

    private fun attachToolbarIfNeeded() {
        if (toolbarAttached) return
        val root = readerRoot ?: return
        if (!::topBar.isInitialized || !::bottomBar.isInitialized) return
        if (topBar.parent == null) {
            root.addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                60.dp(),
                Gravity.TOP
            ))
        }
        if (bottomBar.parent == null) {
            root.addView(bottomBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                60.dp(),
                Gravity.BOTTOM
            ))
        }
        toolbarAttached = true
        updateAdjacentButtons()
    }

    private fun hideBoundaryStatus() {
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
            val hasRenderableFallback = snapshot != null &&
                (snapshot.visibleErrors > 0 || snapshot.visibleCards > 0)
            val stillExposesUndrawnContent = snapshot == null ||
                snapshot.visibleLoading > 0 ||
                snapshot.placeholderPx > 0 ||
                snapshot.missingPx > 0 ||
                snapshot.drawablePx <= 0
            if (stillExposesUndrawnContent && !hasRenderableFallback) {
                Log.d(
                    TAG,
                    "initial_draw_gate_timeout_deferred reason=ntk_viewport_not_ready_after_cap," +
                        "loading=${snapshot?.visibleLoading ?: -1}," +
                        "placeholderPx=${snapshot?.placeholderPx ?: -1}," +
                        "missingPx=${snapshot?.missingPx ?: -1}," +
                        "drawablePx=${snapshot?.drawablePx ?: -1}"
                )
                return true
            }
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

    fun testStartBoundaryAppend(direction: Int, anchorPage: Int): ReaderSession.AppendStartResult? {
        val anchor = anchorPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val startResult = session?.appendAdjacentEpisode(anchor, direction)
        markPrependRevealRequest(direction, startResult)
        return startResult
    }

    fun testPrepareForNextLaunch() {
        destroyed = true
        ntkAckPreflightGeneration.incrementAndGet()
        currentManga?.ntkEpisodePath?.let { path ->
            ReaderImageCache.clearNtkAckRecoveryLaunchHold(path, "test-next-launch")
            ReaderImageCache.clearNtkAckRecoveryPriority(path, "test-next-launch")
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
        renderView.setWindowListener(null)
        renderView.stopRenderingAndClearPages()
        clearPendingPageCallbacks()
        session?.cancel()
        session = null
    }

    fun testCurrentProgressPosition(): ReaderSurfaceView.ProgressPosition? {
        return renderView.currentProgressPosition()
    }

    fun testCurrentScrollPositionSnapshot(): ReaderSurfaceView.ScrollPositionSnapshot? {
        return renderView.currentScrollPositionSnapshot()
    }

    fun testScrollByPixels(deltaPx: Float) {
        renderView.testScrollByPixels(deltaPx)
    }

    fun testFirstDrawableElapsedMs(): Long {
        return firstDrawableElapsedMsForTest
    }

    fun testCurrentNtkEpisodePath(): String? {
        return currentManga?.ntkEpisodePath
    }

    fun testInitialContinuousDrawableElapsedMs(requiredPages: Int): Long {
        if (requiredPages <= 0) return -1L
        if (pageCount < currentPage + requiredPages) return -1L
        var elapsed = 0L
        for (page in currentPage until currentPage + requiredPages) {
            val pageElapsed = launchDrawableElapsedMsByPage[page] ?: return -1L
            if (pageElapsed > elapsed) elapsed = pageElapsed
        }
        return elapsed
    }

    fun testVisibleCoverageSnapshot(): ReaderSurfaceView.VisibleCoverageSnapshot? {
        return renderView.visibleCoverageSnapshot()
    }

    fun testFrameStatsSnapshot(): ReaderSurfaceView.FrameStatsSnapshot? {
        return renderView.frameStatsSnapshot()
    }

    fun testResetFrameStatsSnapshot() {
        renderView.resetFrameStatsSnapshot()
    }

    fun testPageCount(): Int {
        return pageCount
    }

    fun testCurrentPage(): Int {
        return currentPage
    }

    fun testStatusText(): String {
        return if (::status.isInitialized) status.text?.toString().orEmpty() else ""
    }

    fun testBlockingStatus(): String {
        return blockingStatusForTest
    }

    fun testHasLoadedEpisode(episode: Manga?): Boolean {
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
        private const val INITIAL_STATUS_DELAY_MS = 450L
        private const val BOUNDARY_STATUS_DELAY_MS = 250L
        private const val BOUNDARY_APPEND_QUIET_MS = 0L
        private const val BUSY_WINDOW_LOG_INTERVAL_MS = 500L
        private const val INTERACTION_NOTE_INTERVAL_MS = 120L
        private const val ADJACENT_BUTTON_REFRESH_DELAY_MS = 350L
        private const val ADJACENT_STATUS_DELAY_MS = 180L
        private const val INITIAL_DRAW_GATE_TIMEOUT_MS = 1600L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_MS = 4200L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MS = 700L
        private const val NTK_INITIAL_DRAW_GATE_TIMEOUT_DEFER_MAX = 45
        private const val NTK_INITIAL_CAPTCHA_DEFER_MS = 1800L
        private const val NTK_INITIAL_CAPTCHA_MAX_DEFERS = 2
        private const val DEFERRED_NTK_ACK_PREFLIGHT_TIMEOUT_MS = 45000L
        private const val NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS = 450L
        private const val NTK_ACK_PREFLIGHT_AFTER_FIRST_DRAWABLE_QUIET_MS_STRICT_FRESH = 1_500L
        private const val NTK_ACK_PREFLIGHT_INITIAL_NO_INTERACTION_QUIET_MS = 1_500L
        private const val NTK_ACK_PREFLIGHT_SCROLL_QUIET_MS = 1_500L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MS = 650L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_RETRY_MS = 450L
        private const val NTK_ACK_PREFLIGHT_INITIAL_BLOCK_PROBE_MAX_ATTEMPTS = 5
        private const val NTK_ACK_PREFLIGHT_INITIAL_CHALLENGE_WAIT_MAX_ATTEMPTS = 10
        private const val NTK_ACK_PREFLIGHT_INITIAL_IMAGES_READY_HARDBLOCK_ATTEMPTS = 2
        private const val NTK_ACK_INITIAL_CONTINUOUS_PAGES = 3
        private const val NTK_ACK_WEBVIEW_PREFLIGHT_ATTEMPTS = 1
        private const val READER_LOADING_DESCRIPTION = "reader-loading"
        private const val READER_DRAWABLE_READY_DESCRIPTION = "reader-drawable-ready"
        private const val DRAWABLE_READY_CHECK_INTERVAL_MS = 80L
        private const val INITIAL_READY_WEBTOON_AHEAD_PAGES = 2
        private const val INITIAL_READY_MANHWA_AHEAD_PAGES = 2
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
