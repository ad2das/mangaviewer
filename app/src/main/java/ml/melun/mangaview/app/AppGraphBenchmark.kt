package ml.melun.mangaview.app

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Replays the pre-deferred NTK activation schedule for the debug startup comparison only.
 *
 * Production code never calls this method. The guard prevents an accidental release invocation
 * from changing the activation policy, while the instrumentation APK can compare both schedules
 * against the same installed debug APK and app data.
 */
internal fun activateNtkStartupBenchmark(context: Context, source: () -> DeferredContentSource) {
    check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        "Startup benchmark activation is available only in a debuggable application"
    }
    check(source().start()) {
        "NTK source was already activated; startup comparison requires a fresh target process"
    }
}
