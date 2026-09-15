package ml.melun.mangaview

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught-exception stacks under files/crashes so device-only crashes leave evidence.
 * Debug builds (which is what CI ships) allow `adb shell run-as ml.melun.mangaview cat
 * files/crashes/latest.txt`; release builds can read the same files through the app data dir.
 * Best-effort only: the writer never throws into the crashing path.
 */
internal object CrashLog {
    private const val MAX_REPORTS = 10
    private const val DIR = "crashes"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { write(app, thread, failure) }
            previous?.uncaughtException(thread, failure)
        }
    }

    fun latest(context: Context): File = File(File(context.filesDir, DIR), "latest.txt")

    private fun write(app: Context, thread: Thread, failure: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stack = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        val meta = buildString {
            append("time=").append(Date()).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("process=").append(Process.myPid()).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("versionName=").append(packageInfo?.versionName).append('\n')
            append("versionCode=").append(packageInfo?.longVersionCode).append('\n')
            append('\n').append(stack)
        }
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        File(dir, "latest.txt").writeText(meta)
        File(dir, "crash-$stamp.txt").writeText(meta)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }
}
