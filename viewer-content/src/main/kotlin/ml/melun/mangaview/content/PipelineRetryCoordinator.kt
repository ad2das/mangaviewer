package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.PageId

internal class PipelineRetryCoordinator(
    private val scope: CoroutineScope,
    private val clock: PipelineClock,
    private val notifyDue: suspend () -> Unit,
) {
    private val entries = RetryQueue()
    private var wakeup: Job? = null
    private var wakeAt: Long? = null
    private var episodeAt: Long? = null
    private var pressureAt: Long? = null

    /** Counts page/episode retry timers; the pressure cooldown alone carries no retry demand. */
    val wakeupCount: Int
        get() {
            if (wakeup?.isActive != true) return 0
            return if (entries.firstAt() != null || episodeAt != null) 1 else 0
        }

    fun add(pageId: PageId, failureCount: Int): Long {
        val at = clock.nowMillis() + retryDelay(failureCount)
        entries.add(RetryEntry(at, pageId))
        scheduleWakeup()
        return at
    }

    /** Drops a scheduled retry so a promoted page can be scheduled immediately. */
    fun remove(pageId: PageId) {
        if (entries.remove(pageId)) scheduleWakeup()
    }

    fun removeDue(): List<PageId> {
        wakeup = null
        wakeAt = null
        if (episodeAt?.let { it <= clock.nowMillis() } == true) episodeAt = null
        if (pressureAt?.let { it <= clock.nowMillis() } == true) pressureAt = null
        return entries.removeDue(clock.nowMillis()).also { scheduleWakeup() }
    }

    fun clear() {
        wakeup?.cancel()
        wakeup = null
        wakeAt = null
        entries.clear()
        episodeAt = null
        pressureAt = null
    }

    fun episodeRetry(atMillis: Long?) {
        episodeAt = atMillis
        scheduleWakeup()
    }

    /** Wakes the actor once system memory pressure has had time to settle. */
    fun memoryPressureWakeup(atMillis: Long?) {
        pressureAt = atMillis
        scheduleWakeup()
    }

    private fun scheduleWakeup() {
        val first = listOfNotNull(entries.firstAt(), episodeAt, pressureAt).minOrNull() ?: run {
            wakeup?.cancel()
            wakeup = null
            wakeAt = null
            return
        }
        if (wakeAt == first && wakeup?.isActive == true) return
        wakeup?.cancel()
        wakeAt = first
        wakeup = scope.launch {
            delay((first - clock.nowMillis()).coerceAtLeast(0L))
            notifyDue()
        }
    }
}
