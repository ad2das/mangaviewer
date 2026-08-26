package ml.melun.mangaview.reader

/**
 * Latest-value, single-wakeup mailbox for a deferred adjacent-episode request.
 *
 * [offer] and [take] deliberately share one monitor. A producer that races a consumer therefore
 * either updates the turn the consumer is about to take, or observes the released token and owns a
 * new wakeup. There is no state in which a request is pending without a scheduled owner.
 */
internal class NtkDeferredAdjacentPrepareMailbox {
    data class Request(
        val anchor: Int,
        val direction: Int,
        val silentMissing: Boolean,
        val revision: Long,
    )

    data class Wakeup(val token: Long)

    private val lock = Any()
    private var pending: Request? = null
    private var scheduledToken = 0L
    private var nextToken = 0L
    private var nextRevision = 0L

    /** Returns the token for the one required wakeup, or null when one already owns the turn. */
    fun offer(anchor: Int, direction: Int, silentMissing: Boolean): Wakeup? = synchronized(lock) {
        val previous = pending
        val revision = nextRevisionLocked()
        // Explicit physical-boundary ownership is stronger than silent look-ahead ownership.
        // Keep it sticky for the coalesced turn even if another near-boundary hint arrives later.
        pending = Request(
            anchor = anchor,
            direction = direction,
            silentMissing = silentMissing && previous?.silentMissing != false,
            revision = revision,
        )
        reserveWakeupLocked(replace = false)
    }

    /** Requeues a BUSY request without allowing it to overwrite a newer producer revision. */
    fun reoffer(request: Request): Wakeup? = synchronized(lock) {
        val current = pending
        if (current == null || current.revision < request.revision) {
            pending = request
        } else if (current.direction == request.direction) {
            // Preserve the newer anchor/revision, but do not let a silent producer erase an
            // explicit Surface boundary owner that was concurrently running and returned BUSY.
            pending = current.copy(
                silentMissing = current.silentMissing && request.silentMissing,
            )
        }
        reserveWakeupLocked(replace = false)
    }

    /** Replaces a delayed wakeup with one immediate token; the old callback becomes a no-op. */
    fun accelerate(): Wakeup? = synchronized(lock) {
        if (pending == null) return@synchronized null
        reserveWakeupLocked(replace = true)
    }

    /** Keeps ownership across a quiet-period deferral while invalidating duplicate callbacks. */
    fun defer(token: Long): Wakeup? = synchronized(lock) {
        if (scheduledToken != token || pending == null) return@synchronized null
        reserveWakeupLocked(replace = true)
    }

    fun take(token: Long): Request? = synchronized(lock) {
        if (scheduledToken != token) return@synchronized null
        val request = pending ?: return@synchronized null
        pending = null
        scheduledToken = 0L
        request
    }

    fun hasPending(): Boolean = synchronized(lock) { pending != null }

    fun clear() = synchronized(lock) {
        pending = null
        scheduledToken = 0L
        nextTokenLocked()
    }

    private fun reserveWakeupLocked(replace: Boolean): Wakeup? {
        if (pending == null) return null
        if (scheduledToken != 0L && !replace) return null
        scheduledToken = nextTokenLocked()
        return Wakeup(scheduledToken)
    }

    private fun nextTokenLocked(): Long {
        nextToken++
        if (nextToken <= 0L) nextToken = 1L
        return nextToken
    }

    private fun nextRevisionLocked(): Long {
        nextRevision++
        if (nextRevision <= 0L) nextRevision = 1L
        return nextRevision
    }
}
