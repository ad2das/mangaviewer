package ml.melun.mangaview.app

import android.os.StrictMode
import android.os.Trace

internal object StartupMainThreadPolicy {
    fun <T> detectUnexpectedDiskIo(enabled: Boolean, block: () -> T): T {
        if (!enabled) return block()
        val previous = StrictMode.getThreadPolicy()
        val detecting = StrictMode.ThreadPolicy.Builder(previous)
            .detectDiskReads()
            .detectDiskWrites()
            .penaltyLog()
            .build()
        StrictMode.setThreadPolicy(detecting)
        return try {
            traced("AppGraph.no-main-io", block)
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    private fun <T> traced(section: String, block: () -> T): T {
        Trace.beginSection(section)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }
}
