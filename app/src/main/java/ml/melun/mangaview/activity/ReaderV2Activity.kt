package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
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
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderLaunchPreparer
import ml.melun.mangaview.reader.ReaderSession
import ml.melun.mangaview.reader.ReaderSurfaceView
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
    private var lastBusyUiUpdateMs = 0L
    private var pendingAnchorAfterBusy = -1
    private var destroyed = false

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
        renderView.setWindowListener(null)
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
        if (pagesReady) {
            pageCount = count
            renderView.appendPageCount(count)
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
        if (pagesReady) {
            status.visibility = TextView.GONE
            renderView.setPageBitmap(index, bitmap)
        }
    }

    override fun onPageCard(index: Int, title: String) {
        if (pagesReady) {
            status.visibility = TextView.GONE
            renderView.setPageCard(index, title)
        }
    }

    override fun onPageCleared(index: Int) {
        if (pagesReady) renderView.clearPageBitmap(index)
    }

    override fun onMessage(message: String) {
        status.visibility = TextView.VISIBLE
        status.text = message
    }

    override fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean) {
        currentPage = anchorPage
        session?.requestWindow(firstPage, lastPage, anchorPage, busy)
        if (busy) {
            pendingAnchorAfterBusy = anchorPage
            val now = SystemClock.uptimeMillis()
            if (now - lastBusyUiUpdateMs >= BUSY_UI_UPDATE_INTERVAL_MS) {
                lastBusyUiUpdateMs = now
                updatePageLabel()
            }
            return
        }
        pendingAnchorAfterBusy = -1
        updateCurrentEpisode(anchorPage)
    }

    override fun onNearEnd(anchorPage: Int) {
        session?.prepareNextEpisode(anchorPage)
    }

    override fun onTap() {
        setToolbarVisible(!toolbarVisible)
    }

    private fun openAdjacent(next: Boolean) {
        val source = currentManga ?: return
        val title = currentTitle ?: source.title
        if (title != null) {
            source.title = title
            source.titleId = title.id
            title.eps?.let { source.setEps(it) }
        }
        val target = if (next) source.nextEp() else source.prevEp()
        if (target == null) return
        target.mode = source.mode
        if (title != null) {
            target.title = title
            target.titleId = title.id
            title.eps?.let { target.setEps(it) }
        }
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
        if (manga != null && title != null) {
            manga.title = title
            manga.titleId = title.id
            title.eps?.let { manga.setEps(it) }
        }
        prevButton.isEnabled = manga?.prevEp() != null
        nextButton.isEnabled = manga?.nextEp() != null
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
    }

    private fun updatePageLabel() {
        pageView.text = if (pageCount > 0) "${currentPage + 1} / $pageCount" else "- / -"
    }

    private fun updateCurrentEpisode(anchorPage: Int) {
        val info = session?.pageInfo(anchorPage)
        if (info != null) {
            currentManga = info.manga
            titleView.text = info.title
            pageView.text = if (info.transitionCard) {
                "회차 전환"
            } else {
                "${info.localPage} / ${info.totalPages}"
            }
            updateAdjacentButtons()
            saveReadingProgress(info)
            return
        }
        updatePageLabel()
    }

    private fun saveReadingProgress(info: ReaderSession.PageInfo) {
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

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val BUSY_UI_UPDATE_INTERVAL_MS = 250L
    }
}
