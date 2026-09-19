package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounds how fast decoded tiles reach the GL owner queue. A page arrives as a burst of ~9 MB
 * bands; without pacing the whole burst lands in one compositor frame and the buffered
 * presenter's glFinish pays the full GPU transfer on the owner thread.
 */
internal class TileUploadPacer(
    private val bytesPerWindow: Long = 18L * 1024 * 1024,
    private val windowNanos: Long = 16_666_667L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { nanos -> delay(nanos / 1_000_000L + 1L) },
) {
    init {
        require(bytesPerWindow > 0 && windowNanos > 0)
    }

    private val mutex = Mutex()
    private var freeAtNanos = 0L

    /** Reserves [bytes] of upload bandwidth, suspending until this slot may be queued. */
    suspend fun acquire(bytes: Long) {
        require(bytes > 0) { "Paced uploads must carry bytes" }
        val now = nanoTime()
        val start = mutex.withLock {
            val slot = freeAtNanos.coerceAtLeast(now)
            freeAtNanos = slot + (bytes * windowNanos + bytesPerWindow - 1) / bytesPerWindow
            slot
        }
        val wait = start - now
        if (wait > 0) sleep(wait)
    }
}
