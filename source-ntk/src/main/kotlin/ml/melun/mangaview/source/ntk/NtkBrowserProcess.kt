package ml.melun.mangaview.source.ntk

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

object NtkBrowserProcess {
    const val PROCESS_SUFFIX = ":ntk_browser"

    fun isCurrent(context: Context): Boolean =
        currentProcessName(context) == context.packageName + PROCESS_SUFFIX

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = android.os.Process.myPid()
        return manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
