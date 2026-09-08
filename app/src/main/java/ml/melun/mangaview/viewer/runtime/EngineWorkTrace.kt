package ml.melun.mangaview.viewer.runtime

import android.os.Trace

/** Optional timing sections; inactive tracing performs no name formatting or recording. */
internal inline fun <T> traceEngineWork(name: String, work: () -> T): T {
    val enabled = Trace.isEnabled()
    if (enabled) Trace.beginSection(name)
    return try { work() } finally { if (enabled) Trace.endSection() }
}
