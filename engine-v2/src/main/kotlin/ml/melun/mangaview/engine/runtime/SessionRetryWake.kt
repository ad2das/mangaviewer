package ml.melun.mangaview.engine.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owner-thread wake for failed work that is due a retry. The work set only restarts a failed demand
 * when the runtime reconciles, and a reader resting on a blank page sends no input that would cause
 * one; this wake reconciles once the backoff elapsed so the retry happens without a touch.
 */
internal class SessionRetryWake(
    private val scope: CoroutineScope,
    private val wake: () -> Unit,
) {
    private var job: Job? = null
    private var dueAtNanos = Long.MAX_VALUE

    /** Arms one wake [delayNanos] from now; an earlier pending wake already covers a later one. */
    fun schedule(delayNanos: Long) {
        val due = System.nanoTime() + delayNanos
        if (job != null && dueAtNanos <= due) return
        job?.cancel()
        dueAtNanos = due
        val next = scope.launch(start = CoroutineStart.LAZY) {
            delay((delayNanos + WAKE_MARGIN_NANOS) / 1_000_000L)
            job = null
            dueAtNanos = Long.MAX_VALUE
            wake()
        }
        job = next
        next.start()
    }

    fun cancel() { job?.cancel(); job = null; dueAtNanos = Long.MAX_VALUE }
}

// The work set stamps its backoff when the failed attempt finishes, shortly after the failure
// handler ran; waking a little later guarantees the retry is already due.
private const val WAKE_MARGIN_NANOS = 100_000_000L
