package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Generous upper bounds that separate a stalled port from a slow but live operation. A port that
 * exceeds its deadline stops owning a lane slot; the physical call may still be running and its
 * eventual result is released through the ordinary stale-result paths.
 */
data class PortTimeouts(
    val fetchMillis: Long = 30_000L,
    val decodeMillis: Long = 20_000L,
    val uploadMillis: Long = 20_000L,
    val manifestMillis: Long = 60_000L,
) {
    init {
        require(fetchMillis > 0L && decodeMillis > 0L && uploadMillis > 0L && manifestMillis > 0L)
    }
}

class PortSuspensionTimeoutException(operation: String) :
    IllegalStateException("$operation port did not complete within its deadline")

/**
 * Notifies the actor only when [job] is still physically running after [timeoutMillis]. The
 * watchdog never cancels the job: the actor decides whether the operation can be released, so a
 * port that ignores cancellation cannot keep a lane slot forever.
 */
internal fun CoroutineScope.launchPortWatchdog(
    job: Job,
    timeoutMillis: Long,
    notify: suspend () -> Unit,
): Job = launch {
    if (withTimeoutOrNull(timeoutMillis) { job.join() } == null) notify()
}
