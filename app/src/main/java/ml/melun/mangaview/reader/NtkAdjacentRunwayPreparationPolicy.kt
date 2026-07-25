package ml.melun.mangaview.reader

/**
 * Bounds how long an adjacent append may wait for a speculative decoded runway.
 *
 * The normal append path can decode already cached files itself, so an abandoned prefetch must
 * never keep the visible structure in a join loop forever.
 */
internal object NtkAdjacentRunwayPreparationPolicy {
    const val MAX_JOIN_MS = 12_000L
    const val MAX_FILE_FETCH_ATTEMPTS = 3
    const val FORWARD_TAIL_REASON = "window_tail"

    fun shouldJoin(startedAtMs: Long, nowMs: Long): Boolean {
        if (startedAtMs < 0L || nowMs < startedAtMs) return false
        return nowMs - startedAtMs < MAX_JOIN_MS
    }

    fun shouldFastFailGeneratedAnchor(isAuthorizedAdjacentPath: Boolean): Boolean =
        !isAuthorizedAdjacentPath

    /**
     * A strict cold session is already downloading and decoding every canonical current-episode
     * page. Starting the adjacent episode at the first bitmap/frame competes with that finite wave
     * for the same CDN, decoder pool and memory budget. The real forward tail window is early
     * enough to prepare the adjacent runway before a user reaches the boundary without slowing
     * the current episode's all-images deadline.
     */
    fun shouldStartAdjacentPrefetch(
        strictExactColdRolling: Boolean,
        reason: String,
    ): Boolean = !strictExactColdRolling || reason == FORWARD_TAIL_REASON

    /**
     * An initial adjacent runway is published atomically. Active input may lower background
     * preparation depth, but it must never start fewer bodies than that atomic publish gate needs;
     * otherwise a two-request foreground wave can wait forever on a three-image runway.
     */
    fun activeAtomicRunwayRequestPages(
        availablePages: Int,
        atomicPublishPages: Int,
        activeReadyPages: Int,
        activeForegroundPages: Int,
    ): Int {
        if (availablePages <= 0) return 0
        val required = maxOf(
            atomicPublishPages,
            activeReadyPages,
            activeForegroundPages,
            1,
        )
        return minOf(availablePages, required)
    }

    fun mayRetryFileFetch(attemptsCompleted: Int, startedAtMs: Long, nowMs: Long): Boolean {
        if (attemptsCompleted < 0) return false
        return attemptsCompleted < MAX_FILE_FETCH_ATTEMPTS && shouldJoin(startedAtMs, nowMs)
    }
}
