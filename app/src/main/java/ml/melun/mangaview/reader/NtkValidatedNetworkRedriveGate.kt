package ml.melun.mangaview.reader

/**
 * Converts noisy ConnectivityManager callbacks into one bounded recovery ticket per real
 * unvalidated -> validated edge. The ticket itself starts no work; the Activity must still prove
 * its exact viewer/path/generation ownership before asking the discovery coordinator to restart.
 */
internal class NtkValidatedNetworkRedriveGate(
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val hardBudgetMs: Long = DEFAULT_HARD_BUDGET_MS,
) {
    data class Ticket(
        val epoch: Long,
        val createdAtMs: Long,
        val deadlineAtMs: Long,
        val hardDeadlineAtMs: Long,
    )

    private var initialized = false
    private var sawUnvalidated = false
    private var nextEpoch = 0L
    private var pending: Ticket? = null

    @Synchronized
    fun initialize(validated: Boolean) {
        if (initialized) return
        initialized = true
        sawUnvalidated = !validated
    }

    @Synchronized
    fun observe(validated: Boolean, nowMs: Long): Ticket? {
        if (!initialized) {
            initialize(validated)
            return null
        }
        if (!validated) {
            sawUnvalidated = true
            pending = null
            return null
        }
        if (!sawUnvalidated) return null
        sawUnvalidated = false
        return Ticket(
            epoch = ++nextEpoch,
            createdAtMs = nowMs,
            deadlineAtMs = saturatedAdd(nowMs, budgetMs),
            hardDeadlineAtMs = saturatedAdd(nowMs, hardBudgetMs.coerceAtLeast(budgetMs)),
        ).also { pending = it }
    }

    @Synchronized
    fun pendingTicket(): Ticket? = pending

    @Synchronized
    fun renew(ticket: Ticket, nowMs: Long): Ticket? {
        val current = pending?.takeIf { it.epoch == ticket.epoch } ?: return null
        return current.copy(
            deadlineAtMs = saturatedAdd(nowMs, budgetMs).coerceAtMost(current.hardDeadlineAtMs),
        ).also { pending = it }
    }

    /** HOME/background time is not recovery work and must not consume the finite foreground cap. */
    @Synchronized
    fun resumeAfterPause(ticket: Ticket, pausedAtMs: Long, resumedAtMs: Long): Ticket? {
        val current = pending?.takeIf { it.epoch == ticket.epoch } ?: return null
        val effectivePauseStart = maxOf(pausedAtMs, current.createdAtMs)
        val pausedDuration = (resumedAtMs - effectivePauseStart).coerceAtLeast(0L)
        if (pausedDuration == 0L) return current
        return current.copy(
            deadlineAtMs = saturatedAdd(current.deadlineAtMs, pausedDuration),
            hardDeadlineAtMs = saturatedAdd(current.hardDeadlineAtMs, pausedDuration),
        ).also { pending = it }
    }

    @Synchronized
    fun complete(ticket: Ticket): Boolean {
        if (pending?.epoch != ticket.epoch) return false
        pending = null
        return true
    }

    @Synchronized
    fun cancel() {
        pending = null
        sawUnvalidated = false
        initialized = false
    }

    private fun saturatedAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 30_000L
        const val DEFAULT_HARD_BUDGET_MS = 120_000L
    }
}
