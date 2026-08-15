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
    )

    private val lock = Any()
    private var scheduled = false
    private var anchor = -1
    private var direction = 0
    private var silentMissing = true

    /** Returns true exactly when the caller owns the one required wakeup. */
    fun offer(anchor: Int, direction: Int, silentMissing: Boolean): Boolean = synchronized(lock) {
        this.anchor = anchor
        this.direction = direction
        // Explicit physical-boundary ownership is stronger than silent look-ahead ownership.
        // Keep it sticky for the coalesced turn even if another near-boundary hint arrives later.
        if (!scheduled) {
            this.silentMissing = silentMissing
        } else if (!silentMissing) {
            this.silentMissing = false
        }
        if (scheduled) return@synchronized false
        scheduled = true
        true
    }

    fun take(): Request? = synchronized(lock) {
        if (!scheduled) return@synchronized null
        val request = Request(anchor, direction, silentMissing)
        anchor = -1
        direction = 0
        silentMissing = true
        scheduled = false
        request
    }

    fun hasPending(): Boolean = synchronized(lock) { scheduled }

    fun clear() = synchronized(lock) {
        anchor = -1
        direction = 0
        silentMissing = true
        scheduled = false
    }
}
