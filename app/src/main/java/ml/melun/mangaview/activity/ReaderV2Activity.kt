package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        }
        titleView.text = manga.name ?: title?.name ?: ""
        updateAdjacentButtons()
        status.text = manga.name ?: "로딩 중"
        root.post {
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
        session?.cancel()
        session = null
        super.onDestroy()
    }

    override fun onPagesReady(count: Int) {
        pagesReady = true
        pageCount = count
        updatePageLabel()
        renderView.setPageCount(count)
    }

    override fun onPagesAppended(count: Int) {
        if (pagesReady) {
            pageCount = count
            updatePageLabel()
            renderView.appendPageCount(count)
        }
    }

    override fun onInitialPage(index: Int) {
        if (pagesReady) {
            currentPage = index
            updatePageLabel()
            renderView.scrollToPage(index)
        }
    }

    override fun onPageLoading(index: Int) {
        if (pagesReady) renderView.setPageLoading(index)
    }

    override fun onPageReady(index: Int, bitmap: Bitmap) {
        if (pagesReady) {
            status.visibility = TextView.GONE
            renderView.setPageBitmap(index, bitmap)
        }
    }

    override fun onMessage(message: String) {
        status.visibility = TextView.VISIBLE
        status.text = message
    }

    override fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean) {
        currentPage = anchorPage
        updatePageLabel()
        session?.requestWindow(firstPage, lastPage, anchorPage, busy)
    }

    override fun onNearEnd(anchorPage: Int) {
        session?.prepareNextEpisode(anchorPage)
    }

    override fun onTap() {
        toolbarVisible = !toolbarVisible
        val visibility = if (toolbarVisible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomBar.visibility = visibility
    }

    private fun openAdjacent(next: Boolean) {
        val source = currentManga ?: return
        val target = if (next) source.nextEp() else source.prevEp()
        if (target == null) return
        val title = currentTitle ?: source.title
        target.mode = source.mode
        if (title != null) {
            target.title = title
            target.titleId = title.id
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
        prevButton.isEnabled = manga?.prevEp() != null
        nextButton.isEnabled = manga?.nextEp() != null
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
    }

    private fun updatePageLabel() {
        pageView.text = if (pageCount > 0) "${currentPage + 1} / $pageCount" else "- / -"
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()
}
