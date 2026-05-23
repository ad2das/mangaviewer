package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.reader.ReaderRenderView
import ml.melun.mangaview.reader.ReaderSession

class ReaderV2Activity : Activity(), ReaderSession.Listener, ReaderRenderView.WindowListener {
    private lateinit var renderView: ReaderRenderView
    private lateinit var status: TextView
    private var session: ReaderSession? = null
    private var pagesReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        val root = FrameLayout(this)
        renderView = ReaderRenderView(this).also { it.setWindowListener(this) }
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
        setContentView(root)
        val title = Gson().fromJson<Title?>(intent.getStringExtra("title"), object : TypeToken<Title?>() {}.type)
        val manga = Gson().fromJson<Manga?>(intent.getStringExtra("manga"), object : TypeToken<Manga?>() {}.type)
        if (manga == null) {
            finish()
            return
        }
        status.text = manga.name ?: "로딩 중"
        root.post {
            session = ReaderSession(this, manga, title, Utils.getScreenWidth(windowManager.defaultDisplay), this).also {
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
        status.visibility = TextView.GONE
        renderView.setPageCount(count)
    }

    override fun onPagesAppended(count: Int) {
        if (pagesReady) renderView.appendPageCount(count)
    }

    override fun onPageLoading(index: Int) {
        if (pagesReady) renderView.setPageLoading(index)
    }

    override fun onPageReady(index: Int, bitmap: Bitmap) {
        if (pagesReady) renderView.setPageBitmap(index, bitmap)
    }

    override fun onMessage(message: String) {
        status.visibility = TextView.VISIBLE
        status.text = message
    }

    override fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean) {
        renderView.clearOutside(firstPage - 2, lastPage + 2)
        session?.requestWindow(firstPage, lastPage, anchorPage, busy)
    }

    override fun onNearEnd(anchorPage: Int) {
        session?.prepareNextEpisode(anchorPage)
    }
}
