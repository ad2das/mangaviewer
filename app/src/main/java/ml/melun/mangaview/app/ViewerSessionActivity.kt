package ml.melun.mangaview.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Which reader sessions are open, keyed by source.
 *
 * Background warm-up work (newxtoon clearance solves and catalog prefetch) spins up
 * software-rendered WebViews and extra window draws on the main thread. Running that while a
 * reader session for another source is scrolling stalls the reader, so speculative work waits
 * for the reader to close instead of competing with it. Demand-driven fetching is untouched.
 */
internal object ViewerSessionActivity {
    private val active = ConcurrentHashMap<String, AtomicInteger>()

    fun enter(sourceId: String) {
        active.computeIfAbsent(sourceId) { AtomicInteger() }.incrementAndGet()
    }

    fun exit(sourceId: String) {
        active.computeIfPresent(sourceId) { _, count -> if (count.decrementAndGet() <= 0) null else count }
    }

    fun foreignActive(sourceId: String): Boolean =
        active.any { (id, count) -> id != sourceId && count.get() > 0 }

    /** Suspends until no reader session for a different source is open. */
    suspend fun awaitForeignIdle(sourceId: String) {
        while (foreignActive(sourceId)) delay(250)
    }
}
