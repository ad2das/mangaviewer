package ml.melun.mangaview.ntkack

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks every submitted flight task across the submit-before-publish cancellation race. */
internal class NtkAckFlightTasks {
    private val cancelled = AtomicBoolean(false)
    private val futures = CopyOnWriteArrayList<Future<*>>()

    fun isCancelled(): Boolean = cancelled.get()

    fun <T> track(future: Future<T>): Future<T> {
        futures += future
        // cancel() may have taken its CopyOnWrite snapshot before this add. Rechecking the same
        // terminal bit after publication closes that side of the race.
        if (cancelled.get()) future.cancel(true)
        return future
    }

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        futures.forEach { it.cancel(true) }
        return true
    }
}
