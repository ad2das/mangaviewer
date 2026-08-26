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
    const val CURRENT_EPISODE_COMPLETE_IDLE_REASON = "current_episode_complete_idle"
    const val RESUMED_TAIL_DRAWABLE_READY_REASON = "resumed_tail_drawable_ready"

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
     * enough to prepare the adjacent runway before a user reaches the boundary. Once every
     * current-episode drawable is physically installed, that finite wave is also complete and an
     * input-idle adjacent runway can start without lengthening the current episode's deadline.
     */
    fun shouldStartAdjacentPrefetch(
        strictExactColdRolling: Boolean,
        reason: String,
    ): Boolean = !strictExactColdRolling ||
        reason == FORWARD_TAIL_REASON ||
        reason == CURRENT_EPISODE_COMPLETE_IDLE_REASON ||
        reason == RESUMED_TAIL_DRAWABLE_READY_REASON

    /**
     * A resumed tail launch does not need to wait for already-read pages before its restored
     * position to be decoded again. It may yield the bounded adjacent runway only after the full
     * canonical structure and every still-unread page from the restored position are ready.
     */
    fun isCurrentEpisodeReadyForAdjacent(
        completeCurrentStructure: Boolean,
        allCurrentDrawablesReady: Boolean,
        strictExactColdRolling: Boolean,
        resumedFromTail: Boolean,
        resumedForwardDrawablesReady: Boolean,
    ): Boolean = completeCurrentStructure && (
        allCurrentDrawablesReady ||
            (strictExactColdRolling && resumedFromTail && resumedForwardDrawablesReady)
        )

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

    /**
     * Continuous input can keep the normal quiet timer armed forever. It is safe to bypass that
     * timer only after the current episode has no structural or drawable work left and the request
     * is for a different episode in the forward direction.
     */
    fun shouldBypassAdjacentInputQuiet(
        direction: Int,
        differentEpisode: Boolean,
        completeCurrentStructure: Boolean,
        allCurrentDrawablesReady: Boolean,
        currentTailDemandReady: Boolean,
    ): Boolean = direction > 0 &&
        differentEpisode &&
        completeCurrentStructure &&
        (allCurrentDrawablesReady || currentTailDemandReady)

    /**
     * A host-emulator exact runway may finish after the gesture that requested it has already
     * started. Decoded pixels can remain privately staged, but publishing new list structure in
     * that interval invalidates the producer scene and forces multiple full-scene submissions
     * under the finger. Keep the old, completely drawable tail authoritative until the physical
     * gesture and its short hand-off fence have both retired. Other renderers and transports keep
     * their established publication timing.
     */
    fun shouldDeferAtomicStructurePublication(
        hostGpuEmulatorRuntime: Boolean,
        directWifiStrictAdjacent: Boolean,
        supportedEpisodePath: Boolean,
        foregroundMotionActive: Boolean,
        viewportBusy: Boolean,
        physicalQuietRemainingMs: Long,
    ): Boolean = hostGpuEmulatorRuntime &&
        directWifiStrictAdjacent &&
        supportedEpisodePath &&
        (foregroundMotionActive || viewportBusy || physicalQuietRemainingMs > 0L)

    fun mayRetryFileFetch(attemptsCompleted: Int, startedAtMs: Long, nowMs: Long): Boolean {
        if (attemptsCompleted < 0) return false
        return attemptsCompleted < MAX_FILE_FETCH_ATTEMPTS && shouldJoin(startedAtMs, nowMs)
    }
}
