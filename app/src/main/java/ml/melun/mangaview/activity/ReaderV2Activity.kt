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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.R
import ml.melun.mangaview.Utils
import ml.melun.mangaview.activity.CaptchaActivity.REQUEST_CAPTCHA
import ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderLaunchPreparer
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
    private var episodeListFetchAttempted = false
    private var destroyed = false
    private var progressSaveArmed = false
    private var progressMovedInGesture = false
    private var pendingInitialRestorePage = -1
    private var pendingInitialRestoreOffset = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private var pendingProgressInfo: ReaderSession.PageInfo? = null
    private var pendingProgressOffset = 0
    private var pendingBoundaryStatus = false
    private var pendingBoundaryCaptchaRetry = false
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
    private val initialDrawGateTimeoutRunnable = Runnable {
        if (!pagesReady && !destroyed && !isFinishing) {
            initialStatusPending = false
            statusHandler.removeCallbacks(showInitialStatusRunnable)
            status.visibility = TextView.VISIBLE
            status.text = displayEpisodeTitle(currentManga, currentTitle)
        }
        releaseInitialDrawGate("timeout")
    }
    private val saveProgressRunnable = Runnable {
        saveCurrentReadingProgress()
        pendingProgressInfo = null
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
        ReaderChromeStyler.applyReaderWindow(this)
        val root = FrameLayout(this)
        renderView = ReaderSurfaceView(this).also {
            it.id = R.id.strip
            it.isClickable = true
            it.setWindowListener(this)
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
        status = TextView(this).apply {
            text = "로딩 중"
            setTextColor(0xffcccccc.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }
        root.addView(renderView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        topBar.addView(backButton, LinearLayout.LayoutParams(52.dp(), 44.dp()))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, 40.dp(), 1f).apply {
            leftMargin = 8.dp()
        })
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            60.dp(),
            Gravity.TOP
        ))
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
        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            60.dp(),
            Gravity.BOTTOM
        ))
        installToolbarTouchForwarder(
            topBar,
            bottomBar,
            titleView,
            pageView
        )
        setContentView(root)
        installInitialDrawGate(root)
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
        if (viewerLaunchStartedAtMs > 0L) {
            Log.d("ViewerPerf", "reader_activity_create_from_launch source=$viewerLaunchSourceSite ms=${SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs}")
        }
        renderView.setPageGapPx(pageGapForBaseMode(manga.baseMode))
        if (title != null) {
            manga.title = title
            manga.titleId = title.id
            title.eps?.let { manga.setEps(it) }
        }
        titleView.text = displayEpisodeTitle(manga, title)
        updateAdjacentButtons()
        status.text = displayEpisodeTitle(manga, title)
        root.post {
            if (destroyed || isFinishing) return@post
            startReaderSession(
                manga,
                title,
                intent.getStringExtra(ReaderLaunchPreparer.EXTRA_PREPARED_KEY),
                intent.getBooleanExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, false)
            )
        }
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
        progressHandler.removeCallbacks(saveProgressRunnable)
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        missingEpisodePromptState.dismiss()
        removeInitialDrawGateListener()
        saveCurrentReadingProgress()
        pendingProgressInfo = null
        pendingBoundaryStatus = false
        pendingBoundaryCaptchaRetry = false
        renderView.setWindowListener(null)
        renderView.stopRenderingAndClearPages()
        session?.cancel()
        session = null
        super.onDestroy()
    }

    override fun onPagesReady(count: Int) {
        pagesReady = true
        initialStatusPending = false
        statusHandler.removeCallbacks(showInitialStatusRunnable)
        hideBoundaryStatus()
        pageCount = count
        renderView.setPageCount(count)
        updateCurrentEpisode(currentPage)
    }

    override fun onPagesAppended(count: Int) {
        MainThreadStallMonitor.trace("reader_on_pages_appended") {
            if (pagesReady) {
                hideBoundaryStatus()
                pageCount = count
                renderView.appendPageCount(count)
                updateCurrentEpisode(currentPage)
            }
        }
    }

    override fun onPagesPrepended(count: Int, insertedCount: Int) {
        if (pagesReady) {
            val revealPrependedBoundary = pendingBoundaryStatus &&
                pendingCaptchaRetryDirection == ReaderSurfaceView.DIRECTION_PREVIOUS
            hideBoundaryStatus()
            pageCount = count
            currentPage += insertedCount
            if (pendingInitialRestorePage >= 0) pendingInitialRestorePage += insertedCount
            renderView.prependPageCount(count, insertedCount, revealPrependedBoundary)
            if (revealPrependedBoundary) {
                currentPage = (insertedCount - 1).coerceIn(0, count - 1)
            }
            Log.d(TAG, "pages_prepended total=$count inserted=$insertedCount reveal=$revealPrependedBoundary currentPage=$currentPage")
            updateCurrentEpisode(currentPage)
        }
    }

    override fun onInitialPage(index: Int) {
        if (pagesReady) {
            currentPage = index
            val initialManga = session?.pageInfo(index)?.manga ?: currentManga
            pendingInitialRestorePage = index
            pendingInitialRestoreOffset = p?.getViewerBookmarkOffset(initialManga) ?: 0
            renderView.holdInitialRestoreRender(index)
            renderView.lockRestoredPageOffset(index, pendingInitialRestoreOffset)
            updateCurrentEpisode(index, pendingInitialRestoreOffset, saveProgress = false)
            applyPendingInitialRestoreIfReady()
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
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageBitmap(index, bitmap)
                logFirstDrawableMetric(index, "bitmap")
                if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
                releaseInitialDrawGate("page")
            }
        }
    }

    override fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        MainThreadStallMonitor.trace("reader_on_page_tiles_ready") {
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageTiles(index, pageWidth, pageHeight, tiles)
                logFirstDrawableMetric(index, "tiles")
                if (index == pendingInitialRestorePage) applyPendingInitialRestoreIfReady()
                releaseInitialDrawGate("tiles")
            }
        }
    }

    override fun onPageCard(index: Int, title: String) {
        MainThreadStallMonitor.trace("reader_on_page_card") {
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageCard(index, title)
                releaseInitialDrawGate("card")
            }
        }
    }

    override fun onPageCleared(index: Int) {
        if (pagesReady) renderView.clearPageBitmap(index)
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
        val elapsed = SystemClock.elapsedRealtime() - viewerLaunchStartedAtMs
        Log.d("ViewerPerf", "reader_open_to_first_drawable source=$viewerLaunchSourceSite kind=$kind page=$index ms=$elapsed")
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
            currentPage = progressPage
            MainThreadStallMonitor.trace("reader_request_window_async") {
                session?.requestWindowAsync(firstPage, lastPage, anchorPage, busy)
            }
            if (busy) {
                pendingAnchorAfterBusy = progressPage
                return@trace
            }
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
            session?.noteUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNearBoundary(direction: Int, anchorPage: Int) {
        session?.prepareAdjacentEpisode(anchorPage, direction)
    }

    override fun onBoundaryReached(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        Log.d(TAG, "boundary_reached direction=$direction anchorPage=$anchorPage")
        session?.pageInfo(anchorPage)?.let {
            if (!it.transitionCard) currentManga = it.manga
        }
        pendingBoundaryStatus = true
        pendingBoundaryCaptchaRetry = true
        pendingCaptchaRetryDirection = direction
        pendingCaptchaRetryAnchor = anchorPage
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.postDelayed(showBoundaryStatusRunnable, BOUNDARY_STATUS_DELAY_MS)
        val startResult = session?.appendAdjacentEpisode(anchorPage, direction)
        if (startResult != ReaderSession.AppendStartResult.STARTED && startResult != ReaderSession.AppendStartResult.BUSY) {
            clearPendingBoundaryCaptchaRetry()
        }
    }

    override fun onTap() {
        setToolbarVisible(!toolbarVisible)
    }

    private fun openAdjacent(next: Boolean) {
        val source = currentManga ?: return
        Log.d(TAG, "open_adjacent next=$next sourceId=${source.id} sourceName=${source.name}")
        if (adjacentNavigationInFlight) return
        adjacentNavigationInFlight = true
        setAdjacentButtonState(false, false)
        statusHandler.removeCallbacks(showAdjacentStatusRunnable)
        statusHandler.postDelayed(showAdjacentStatusRunnable, ADJACENT_STATUS_DELAY_MS)
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
        startReaderSession(
            target,
            currentTitle,
            preparedKey,
            startAtFirstPage = true,
            clearViewImmediately = false
        )
        primeAdjacentLaunchWindow(currentTitle, adjacentEpisode(target, false))
        primeAdjacentLaunchWindow(currentTitle, adjacentEpisode(target, true))
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
        lastDisplayedPageText = ""
        pendingBoundaryStatus = false
        clearPendingBoundaryCaptchaRetry()
        pendingInitialRestorePage = -1
        pendingInitialRestoreOffset = 0
        pendingProgressInfo = null
        pendingProgressOffset = 0
        progressSaveArmed = false
        progressMovedInGesture = false
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
        if (clearViewImmediately) renderView.setPageCount(0)
        session?.cancel()
        session = ReaderSession(
            this,
            manga,
            title,
            readerWidthPx(),
            autoCut,
            p?.getReverse() == true,
            if (autoCut) null else preparedKey,
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
        if (manga != null) attachEpisodeList(title, manga)
        if (adjacentNavigationInFlight) {
            setAdjacentButtonState(false, false)
            return
        }
        val previous = if (manga == null) null else adjacentEpisode(manga, false)
        val next = if (manga == null) null else adjacentEpisode(manga, true)
        primeAdjacentLaunchWindow(title, previous)
        primeAdjacentLaunchWindow(title, next)
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
        if (title == null || target == null) return
        title.ensureProgressEpisodes(target)
        val episodes = Utils.snapshotEpisodes(title)
        for (episode in episodes) {
            episode?.let {
                it.title = title
                it.titleId = title.id
            }
        }
        target.title = title
        target.titleId = title.id
        if (episodes.isNotEmpty() && containsEpisode(episodes, target)) {
            val targetEpisodes = Utils.snapshotEpisodes(target)
            if (targetEpisodes.isEmpty() || episodes.size >= targetEpisodes.size) target.setEps(episodes)
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
                displayEpisodeTitle(info.manga, currentTitle).takeIf { it.isNotBlank() }
                    ?: info.title.takeIf { it.isNotBlank() }
                    ?: "회차"
            }
            if (episodeChanged || titleView.text.toString() != displayTitle) {
                titleView.text = displayTitle
                lastDisplayedEpisodeKey = displayKey
                lastDisplayedEpisodeTitle = displayTitle
            }
            setPageText(if (info.transitionCard) {
                "회차 전환"
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
        val currentInfo = currentPosition?.let { session?.pageInfo(it.page) }
        val info = currentInfo ?: pendingProgressInfo ?: return
        if (!info.layoutReady) return
        saveReadingProgressNow(info, currentPosition?.offset ?: pendingProgressOffset)
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
        val ntkPath = info.manga.ntkEpisodePath ?: ""
        if (title.sourceSite == "ntk" && ntkPath.isNotBlank()) {
            title.resumeNtkEpisodePath = ntkPath
        }
        val episodes = Utils.snapshotEpisodes(title).ifEmpty { Utils.snapshotEpisodes(info.manga) }
        val episodeIndex = episodes.indexOfFirst { it != null && it.id == info.manga.id }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: title.bookmarkEpisodeIndex
        val episodeCount = episodes.size.takeIf { it > 0 } ?: title.episodeCount
        if (episodeCount > 0) {
            title.setReadingProgress(info.manga.id, episodeIndex, episodeCount)
        }
        val zeroBasedPage = info.sourcePageIndex.coerceAtLeast(0)
        if (
            lastSavedEpisodeId == info.manga.id &&
            lastSavedPage == zeroBasedPage &&
            lastSavedOffset == offset &&
            lastSavedSide == info.side
        ) return
        lastSavedEpisodeId = info.manga.id
        lastSavedPage = zeroBasedPage
        lastSavedOffset = offset
        lastSavedSide = info.side
        p?.addRecent(title)
        p?.setBookmark(title, info.manga.id)
        p?.setViewerBookmark(info.manga, zeroBasedPage, offset, info.side)
    }

    private fun updateResultEpisode(manga: Manga?, transitionCard: Boolean = false) {
        if (transitionCard || manga == null || manga.id <= 0) return
        if (!intent.getBooleanExtra("recent", false) && !intent.getBooleanExtra("returnToEpisodes", false)) return
        val result = resultIntent ?: Intent().also { resultIntent = it }
        result.putExtra("id", manga.id)
        setResult(RESULT_OK, result)
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
        val visibility = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomBar.visibility = visibility
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

    private fun installInitialDrawGate(root: View) {
        initialDrawGateOpen = false
        initialDrawGateView = root
        statusHandler.removeCallbacks(initialDrawGateTimeoutRunnable)
        val listener = ViewTreeObserver.OnPreDrawListener {
            initialDrawGateOpen || destroyed || isFinishing
        }
        initialDrawGateListener = listener
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(listener)
        statusHandler.postDelayed(initialDrawGateTimeoutRunnable, INITIAL_DRAW_GATE_TIMEOUT_MS)
    }

    private fun releaseInitialDrawGate(reason: String) {
        if (initialDrawGateOpen) return
        initialDrawGateOpen = true
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
        private const val ADJACENT_BUTTON_REFRESH_DELAY_MS = 350L
        private const val ADJACENT_STATUS_DELAY_MS = 180L
        private const val INITIAL_DRAW_GATE_TIMEOUT_MS = 1600L
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

        private fun pageGapForBaseMode(baseMode: Int): Int {
            return ReaderDisplayPolicy.pageGapForBaseMode(baseMode)
        }

        private fun shouldEnableAdjacentButton(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
            return ReaderDisplayPolicy.shouldEnableAdjacentButton(hasAdjacent, canFetchMissingAdjacent)
        }
    }
}
