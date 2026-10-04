package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.PageId

/**
 * Runtime EWMA of the demand-to-accepted latency of speculative page fetches. Only pages that
 * actually went to the network feed the average: a storage hit completes without a geometry
 * publication for its demand, so it is recorded as neither demand nor sample. The estimate starts
 * at [INITIAL_SECONDS] and stays clamped to [MIN_SECONDS, MAX_SECONDS]; callers pass the monotonic
 * timestamps so tests drive the series deterministically.
 */
internal class FetchLatencyEstimate(private val alpha: Double = SMOOTHING) {
    private class Pending(val demandedAtNanos: Long) {
        var wentToNetwork = false
    }

    private val pending = HashMap<PageId, Pending>()
    private var averageSeconds = INITIAL_SECONDS

    /** Current estimate; always inside the clamp bounds. */
    val seconds: Double get() = averageSeconds

    /** First observation of a speculative demand wins; later rebuilds keep the original instant. */
    fun observe(id: PageId, atNanos: Long) {
        pending.putIfAbsent(id, Pending(atNanos))
    }

    /** True when the speculative demand for [id] already owns an instant and must not re-read one. */
    fun tracks(id: PageId): Boolean = pending.containsKey(id)

    /** The demand's fetch reached the network (its geometry was published by a body transfer). */
    fun network(id: PageId) {
        pending[id]?.wentToNetwork = true
    }

    /**
     * Completes the demand. The clock is read only when a network interval will actually feed the
     * EWMA: a storage hit and an untracked page finish without a timestamp.
     */
    fun complete(id: PageId, atNanos: () -> Long) {
        val entry = pending.remove(id) ?: return
        if (!entry.wentToNetwork) return
        val sample = (atNanos() - entry.demandedAtNanos) / NANOS_PER_SECOND
        if (!sample.isFinite() || sample <= 0.0) return
        averageSeconds = (averageSeconds + alpha * (sample - averageSeconds)).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Convenience for callers that already hold the completion instant. */
    fun complete(id: PageId, atNanos: Long) = complete(id) { atNanos }

    /** Drops a failed or no-longer-wanted demand so a later one starts its own interval. */
    fun abandon(id: PageId) {
        pending.remove(id)
    }

    /** Keeps only demands whose page is still wanted; displaced pages restart their interval. */
    fun retain(ids: Set<PageId>) {
        pending.keys.retainAll(ids)
    }

    fun clear() {
        pending.clear()
    }

    private companion object {
        const val SMOOTHING = 0.2
        const val INITIAL_SECONDS = 0.6
        const val MIN_SECONDS = 0.2
        const val MAX_SECONDS = 3.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
