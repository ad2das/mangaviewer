package ml.melun.mangaview

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
 * Persists uncaught-exception stacks and abnormal process exit reasons under files/crashes so
 * device-only crashes leave evidence. Debug builds (which is what CI ships) allow
 * `adb shell run-as ml.melun.mangaview cat files/crashes/latest.txt`; every report is mirrored
 * to the app-specific external directory so a phone without adb can hand it over too.
 * Best-effort only: the writer never throws into the crashing path.
 */
internal object CrashLog {
    private const val MAX_REPORTS = 10
    private const val MAX_TRACE_CHARS = 200_000
    private const val DIR = "crashes"

    private val EXIT_REASONS = mapOf(
        ApplicationExitInfo.REASON_CRASH to "crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "native-crash",
        ApplicationExitInfo.REASON_ANR to "anr",
        ApplicationExitInfo.REASON_LOW_MEMORY to "low-memory",
        ApplicationExitInfo.REASON_SIGNALED to "signaled",
        ApplicationExitInfo.REASON_OTHER to "other",
    )

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { write(app, thread, failure) }
            previous?.uncaughtException(thread, failure)
        }
        runCatching { recordLastExit(app) }
    }

    fun latest(context: Context): File = File(File(context.filesDir, DIR), "latest.txt")

    /**
     * The uncaught-exception handler never sees native crashes or low-memory kills, so the last
     * abnormal exit reason is captured on startup. It is the only evidence a silent, stack-less
     * process death leaves on a phone without adb.
     */
    private fun recordLastExit(app: Context) {
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            .firstOrNull { it.reason in EXIT_REASONS && it.processName == app.packageName } ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(exit.timestamp))
        val trace = runCatching {
            exit.traceInputStream?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val report = buildString {
            append("recorded=").append(Date()).append('\n')
            append("occurred=").append(Date(exit.timestamp)).append('\n')
            append("process=").append(exit.processName).append(" pid=").append(exit.pid).append('\n')
            append("reason=").append(EXIT_REASONS[exit.reason]).append(" (").append(exit.reason).append(")\n")
            append("status=").append(exit.status).append('\n')
            append("importance=").append(exit.importance).append('\n')
            append("pssKb=").append(exit.pss).append(" rssKb=").append(exit.rss).append('\n')
            append("description=").append(exit.description ?: "-").append('\n')
            if (trace.isNotBlank()) append('\n').append(trace.take(MAX_TRACE_CHARS))
        }
        publish(app, "exit-$stamp.txt", "latest-exit.txt", report)
    }

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
        publish(app, "crash-$stamp.txt", "latest.txt", meta)
    }

    /** Writes one report next to a stable latest name, internally and on external storage. */
    private fun publish(app: Context, name: String, latestName: String, report: String) {
        val internal = File(app.filesDir, DIR).apply { mkdirs() }
        File(internal, name).writeText(report)
        File(internal, latestName).writeText(report)
        prune(internal)
        runCatching {
            val external = File(app.getExternalFilesDir(null), DIR).apply { mkdirs() }
            File(external, name).writeText(report)
            File(external, latestName).writeText(report)
            prune(external)
        }
    }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }
}
