package ml.melun.mangaview.source.ntk

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView

object NtkBrowserProcess {
    const val PROCESS_SUFFIX = ":ntk_browser"

    fun isCurrent(context: Context): Boolean = processSuffix(context) != null

    fun configureWebViewStorage(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (processSuffix(context) == null) return
        WebView.setDataDirectorySuffix("ntk_browser")
    }

    private fun processSuffix(context: Context): String? {
        val processName = currentProcessName(context) ?: return null
        return PROCESS_SUFFIX.takeIf { processName == context.packageName + it }
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = android.os.Process.myPid()
        return manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
