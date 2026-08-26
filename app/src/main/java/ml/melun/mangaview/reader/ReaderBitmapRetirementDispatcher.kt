package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/** Serial off-main destruction for terminal Surface-owned software bitmap identities. */
internal object ReaderBitmapRetirementDispatcher {
    private val threadSerial = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { command ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                command.run()
            },
            "reader-bitmap-retirement-${threadSerial.incrementAndGet()}",
        ).apply { isDaemon = true }
    })

    /** Returns false only if the process-wide executor can no longer accept work. */
    fun dispatch(bitmaps: List<Bitmap>, completion: () -> Unit): Boolean {
        if (bitmaps.isEmpty()) {
            completion()
            return true
        }
        return try {
            executor.execute {
                try {
                    bitmaps.forEach { bitmap ->
                        runCatching {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                } finally {
                    completion()
                }
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Serial native-reference probes and renderer destruction share this owner with the final
     * recycle. Keeping all three operations in one FIFO prevents a renderer pointer from being
     * destroyed while an off-main JNI ownership query is still using it.
     */
    fun dispatchWork(command: () -> Unit): Boolean {
        return try {
            executor.execute(command)
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
