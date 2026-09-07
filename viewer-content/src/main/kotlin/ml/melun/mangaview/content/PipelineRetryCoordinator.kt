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

    val wakeupCount: Int get() = if (wakeup?.isActive == true) 1 else 0

    fun add(pageId: PageId, failureCount: Int): Long {
        val at = clock.nowMillis() + retryDelay(failureCount)
        entries.add(RetryEntry(at, pageId))
        scheduleWakeup()
        return at
    }

    fun removeDue(): List<PageId> {
        wakeup = null
        wakeAt = null
        if (episodeAt?.let { it <= clock.nowMillis() } == true) episodeAt = null
        return entries.removeDue(clock.nowMillis()).also { scheduleWakeup() }
    }

    fun clear() {
        wakeup?.cancel()
        wakeup = null
        wakeAt = null
        entries.clear()
        episodeAt = null
    }

    fun episodeRetry(atMillis: Long?) {
        episodeAt = atMillis
        scheduleWakeup()
    }

    private fun scheduleWakeup() {
        val first = listOfNotNull(entries.firstAt(), episodeAt).minOrNull() ?: run {
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
