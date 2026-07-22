package ml.melun.mangaview.reader

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/** Process-wide off-main serial dispatch for callbacks that must never run on a JNI stack. */
internal object NtkReleaseCompletion {
    private val threadSerial = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { command ->
        Thread(command, "ntk-release-completion-${threadSerial.incrementAndGet()}").apply {
            isDaemon = true
        }
    })

    fun dispatch(completion: () -> Unit) {
        executor.execute {
            try {
                completion()
            } catch (_: Throwable) {
                // External completion failures never alter renderer/proof lifecycle state.
            }
        }
    }
}
