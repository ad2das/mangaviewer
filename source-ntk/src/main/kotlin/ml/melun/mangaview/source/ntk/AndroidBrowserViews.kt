package ml.melun.mangaview.source.ntk

import android.content.Context
import android.os.Looper
import android.webkit.WebView

/** Shared construction boundary for browser sessions; callers own configuration and teardown. */
object AndroidBrowserViews {
    fun create(context: Context): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Browser views must be created on the main thread" }
        WebView.setWebContentsDebuggingEnabled(false)
        return WebView(context)
    }
}
