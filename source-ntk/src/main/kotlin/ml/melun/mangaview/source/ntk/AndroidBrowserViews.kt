package ml.melun.mangaview.source.ntk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.webkit.WebView

/** Shared construction boundary for browser sessions; callers own configuration and teardown. */
object AndroidBrowserViews {
    fun create(context: Context): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Browser views must be created on the main thread" }
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        return WebView(context)
    }
}
