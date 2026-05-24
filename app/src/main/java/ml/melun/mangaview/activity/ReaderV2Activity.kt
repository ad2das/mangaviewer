package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderLaunchPreparer
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
    private lateinit var nextButton: Button
    private var session: ReaderSession? = null
    private var pagesReady = false
    private var toolbarVisible = false
    private var pageCount = 0
    private var currentPage = 0
    private var currentManga: Manga? = null
    private var currentTitle: Title? = null
    private var toolbarTouchSlop = 0
    private var toolbarDownRawX = 0f
    private var toolbarDownRawY = 0f
    private var toolbarForwardingScroll = false
    private var lastSavedEpisodeId = -1
    private var lastSavedPage = -1
    private var lastDisplayedPageText = ""
    private var pendingAnchorAfterBusy = -1
    private var adjacentNavigationInFlight = false
    private var episodeListFetchAttempted = false
    private var destroyed = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private var pendingProgressInfo: ReaderSession.PageInfo? = null
    private var pendingBoundaryStatus = false
    private val saveProgressRunnable = Runnable {
        pendingProgressInfo?.let { saveReadingProgressNow(it) }
        pendingProgressInfo = null
    }
    private val showBoundaryStatusRunnable = Runnable {
        if (pendingBoundaryStatus && pagesReady && !destroyed && !isFinishing) {
            status.visibility = TextView.VISIBLE
            status.text = "회차 연결 중"
        }
    }

    private data class AdjacentResolution(
        val target: Manga?,
        val title: Title?,
        val result: Int,
        val fetchedEpisodes: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        val root = FrameLayout(this)
        renderView = ReaderSurfaceView(this).also { it.setWindowListener(this) }
        topBar = LinearLayout(this).apply {
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
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
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
            text = "이전"
            setOnClickListener { openAdjacent(false) }
        }
        nextButton = Button(this).apply {
            text = "다음"
            setOnClickListener { openAdjacent(true) }
        }
        status = TextView(this).apply {
            text = "로딩 중"
            setTextColor(0xffcccccc.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
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
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            60.dp(),
            Gravity.TOP
        ))
        bottomBar.addView(pageView, LinearLayout.LayoutParams(0, 44.dp(), 1f))
        bottomBar.addView(prevButton, LinearLayout.LayoutParams(82.dp(), 44.dp()))
        bottomBar.addView(nextButton, LinearLayout.LayoutParams(82.dp(), 44.dp()))
        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            60.dp(),
            Gravity.BOTTOM
        ))
        installToolbarTouchForwarder(topBar, bottomBar, backButton, prevButton, nextButton, titleView, pageView)
        setContentView(root)
        val title = Gson().fromJson<Title?>(intent.getStringExtra("title"), object : TypeToken<Title?>() {}.type)
        val manga = Gson().fromJson<Manga?>(intent.getStringExtra("manga"), object : TypeToken<Manga?>() {}.type)
        if (manga == null) {
            finish()
            return
        }
        currentManga = manga
        currentTitle = title
        renderView.setPageGapPx(pageGapForBaseMode(manga.baseMode))
        if (title != null) {
            manga.title = title
            manga.titleId = title.id
            title.eps?.let { manga.setEps(it) }
        }
        titleView.text = manga.name ?: title?.name ?: ""
        updateAdjacentButtons()
        status.text = manga.name ?: "로딩 중"
        root.post {
            if (destroyed || isFinishing) return@post
            session = ReaderSession(
                this,
                manga,
                title,
                Utils.getScreenWidth(windowManager.defaultDisplay),
                intent.getStringExtra(ReaderLaunchPreparer.EXTRA_PREPARED_KEY),
                this
            ).also {
                it.start()
            }
        }
        if (intent.getBooleanExtra("recent", false)) setResult(RESULT_OK)
        if (!manga.isOnline) p?.removeViewerBookmark(manga)
    }

    override fun onDestroy() {
        destroyed = true
        progressHandler.removeCallbacks(saveProgressRunnable)
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        pendingProgressInfo?.let { saveReadingProgressNow(it) }
        pendingProgressInfo = null
        pendingBoundaryStatus = false
        renderView.setWindowListener(null)
        renderView.stopRenderingAndClearPages()
        session?.cancel()
        session = null
        super.onDestroy()
    }

    override fun onPagesReady(count: Int) {
        pagesReady = true
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
            hideBoundaryStatus()
            pageCount = count
            currentPage += insertedCount
            renderView.prependPageCount(count, insertedCount)
            updateCurrentEpisode(currentPage)
        }
    }

    override fun onInitialPage(index: Int) {
        if (pagesReady) {
            currentPage = index
            renderView.scrollToPage(index)
            updateCurrentEpisode(index)
        }
    }

    override fun onPageLoading(index: Int) {
        if (pagesReady) renderView.setPageLoading(index)
    }

    override fun onPageBoundsReady(index: Int, width: Int, height: Int) {
        if (pagesReady) renderView.setPageBounds(index, width, height)
    }

    override fun onPageReady(index: Int, bitmap: Bitmap) {
        MainThreadStallMonitor.trace("reader_on_page_ready") {
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageBitmap(index, bitmap)
            }
        }
    }

    override fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        MainThreadStallMonitor.trace("reader_on_page_tiles_ready") {
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageTiles(index, pageWidth, pageHeight, tiles)
            }
        }
    }

    override fun onPageCard(index: Int, title: String) {
        MainThreadStallMonitor.trace("reader_on_page_card") {
            if (pagesReady) {
                hideBoundaryStatus()
                renderView.setPageCard(index, title)
            }
        }
    }

    override fun onPageCleared(index: Int) {
        if (pagesReady) renderView.clearPageBitmap(index)
    }

    override fun onMessage(message: String) {
        pendingBoundaryStatus = false
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        status.visibility = TextView.VISIBLE
        status.text = message
    }

    override fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean) {
        MainThreadStallMonitor.trace("reader_on_window_changed") {
            currentPage = anchorPage
            MainThreadStallMonitor.trace("reader_request_window_async") {
                session?.requestWindowAsync(firstPage, lastPage, anchorPage, busy)
            }
            if (busy) {
                pendingAnchorAfterBusy = anchorPage
                return@trace
            }
            pendingAnchorAfterBusy = -1
            MainThreadStallMonitor.trace("reader_update_current_episode") {
                updateCurrentEpisode(anchorPage)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val lagMs = SystemClock.uptimeMillis() - ev.eventTime
        if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP) {
            MainThreadStallMonitor.warn("reader_touch_delivery_lag", lagMs)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNearBoundary(direction: Int, anchorPage: Int) {
        session?.prepareAdjacentEpisode(anchorPage, direction)
    }

    override fun onBoundaryReached(direction: Int, anchorPage: Int) {
        if (destroyed || isFinishing) return
        session?.pageInfo(anchorPage)?.let {
            if (!it.transitionCard) currentManga = it.manga
        }
        pendingBoundaryStatus = true
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        statusHandler.postDelayed(showBoundaryStatusRunnable, BOUNDARY_STATUS_DELAY_MS)
        session?.appendAdjacentEpisode(anchorPage, direction)
    }

    override fun onTap() {
        setToolbarVisible(!toolbarVisible)
    }

    private fun openAdjacent(next: Boolean) {
        val source = currentManga ?: return
        if (adjacentNavigationInFlight) return
        val immediate = resolveAdjacent(source, next, false)
        if (immediate.target != null) {
            launchAdjacent(source, immediate.target, immediate.title)
            return
        }
        if (!canFetchMissingAdjacent(source, immediate.title, immediate.target)) {
            updateAdjacentButtons()
            return
        }
        adjacentNavigationInFlight = true
        updateAdjacentButtons()
        status.visibility = TextView.VISIBLE
        status.text = "회차 확인 중"
        AppDispatchers.submitUserAction {
            val resolved = resolveAdjacent(source, next, true)
            runOnUiThread {
                finishAdjacentResolution(source, resolved)
            }
        }
    }

    private fun finishAdjacentResolution(source: Manga, resolved: AdjacentResolution) {
        adjacentNavigationInFlight = false
        if (destroyed || isFinishing) return
        if (resolved.fetchedEpisodes) episodeListFetchAttempted = true
        currentTitle = resolved.title ?: currentTitle
        if (resolved.target != null) {
            launchAdjacent(source, resolved.target, resolved.title)
            return
        }
        if (resolved.result == Title.LOAD_CAPTCHA) {
            status.visibility = TextView.VISIBLE
            status.text = "캡차 확인이 필요합니다"
        } else if (pagesReady) {
            status.visibility = TextView.GONE
        }
        updateAdjacentButtons()
    }

    private fun launchAdjacent(source: Manga, target: Manga, title: Title?) {
        target.mode = source.mode
        attachEpisodeList(title, target)
        renderView.stopRenderingAndClearPages()
        session?.cancel()
        session = null
        Utils.openViewerPrepared(this, target, 0, intent.getBooleanExtra("returnToEpisodes", false),
            true, false, title, title != null, true)
        finish()
        overridePendingTransition(0, 0)
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
        return if (next) manga.nextEp() else manga.prevEp()
    }

    private fun canFetchMissingAdjacent(manga: Manga?, title: Title?, target: Manga?): Boolean {
        return target == null && !episodeListFetchAttempted && manga?.isOnline == true && title != null
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

    private fun updateCurrentEpisode(anchorPage: Int) {
        val info = MainThreadStallMonitor.traceResult("reader_page_info") {
            session?.pageInfo(anchorPage)
        }
        if (info != null) {
            val previousManga = currentManga
            val episodeChanged = previousManga == null || !Manga.sameEpisodeIdentity(previousManga, info.manga)
            currentManga = info.manga
            if (episodeChanged || titleView.text.toString() != info.title) {
                titleView.text = info.title
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
            MainThreadStallMonitor.trace("reader_schedule_progress") {
                scheduleSaveReadingProgress(info)
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

    private fun scheduleSaveReadingProgress(info: ReaderSession.PageInfo) {
        if (info.transitionCard || !info.manga.useBookmark()) return
        pendingProgressInfo = info
        progressHandler.removeCallbacks(saveProgressRunnable)
        progressHandler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS)
    }

    private fun saveReadingProgressNow(info: ReaderSession.PageInfo) {
        if (info.transitionCard || !info.manga.useBookmark()) return
        val title = currentTitle ?: info.manga.title ?: return
        info.manga.title = title
        info.manga.titleId = title.id
        title.eps?.let { info.manga.setEps(it) }
        val zeroBasedPage = (info.localPage - 1).coerceAtLeast(0)
        if (lastSavedEpisodeId == info.manga.id && lastSavedPage == zeroBasedPage) return
        lastSavedEpisodeId = info.manga.id
        lastSavedPage = zeroBasedPage
        p?.addRecent(title)
        p?.setBookmark(title, info.manga.id)
        p?.setViewerBookmark(info.manga, zeroBasedPage)
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
        statusHandler.removeCallbacks(showBoundaryStatusRunnable)
        if (status.visibility != TextView.GONE) status.visibility = TextView.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 1000L
        private const val BOUNDARY_STATUS_DELAY_MS = 250L
        private const val DEFAULT_PAGE_GAP_PX = 2
        private const val WEBTOON_PAGE_GAP_PX = 0

        @JvmStatic
        fun pageGapForBaseModeForTest(baseMode: Int): Int = pageGapForBaseMode(baseMode)

        @JvmStatic
        fun shouldEnableAdjacentButtonForTest(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
            return shouldEnableAdjacentButton(hasAdjacent, canFetchMissingAdjacent)
        }

        private fun pageGapForBaseMode(baseMode: Int): Int {
            return if (baseMode == MTitle.base_webtoon) WEBTOON_PAGE_GAP_PX else DEFAULT_PAGE_GAP_PX
        }

        private fun shouldEnableAdjacentButton(hasAdjacent: Boolean, canFetchMissingAdjacent: Boolean): Boolean {
            return hasAdjacent || canFetchMissingAdjacent
        }
    }
}
