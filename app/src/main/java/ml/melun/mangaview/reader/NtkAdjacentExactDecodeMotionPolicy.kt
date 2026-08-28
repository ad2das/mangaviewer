package ml.melun.mangaview.reader

/** Keeps offscreen exact HardwareBuffer writes out of a physical manga motion interval. */
internal object NtkAdjacentExactDecodeMotionPolicy {
    const val PRIVATE_EXACT_HEAD_STAGING_REASON = "initial_exact_head_staging"

    fun shouldDefer(
        directWifiRunwayProfile: Boolean,
        reason: String,
        adjacentManhwaPages: Boolean,
    ): Boolean = directWifiRunwayProfile &&
        reason != PRIVATE_EXACT_HEAD_STAGING_REASON &&
        (reason == "initial_strict_source" || adjacentManhwaPages)
}

/**
 * Closes the registered-owner liveness gap after an alternate publisher installs and later
 * retires the same exact page. A PageRef flight can legitimately survive that hand-off, but a
 * real physical request must never leave it registered with neither a worker nor a parked wake
 * edge. The caller's atomic scheduling CAS still guarantees one decoder.
 */
internal object NtkAdjacentExactRehydrateLivenessPolicy {
    fun shouldRedriveIdleOwner(
        exactPhysicalIntent: Boolean,
        parked: Boolean,
        scheduledOrRunning: Boolean,
    ): Boolean = exactPhysicalIntent && !parked && !scheduledOrRunning
}

/** Keeps the fixed p0..pN preparation owner alive across its own p0 first-presentation race. */
internal object NtkPublishedInitialRunwayCompletionPolicy {
    fun stillOwnsPixelCompletion(
        firstActualFramePresented: Boolean,
        latestEnteredEpisodeMatches: Boolean,
    ): Boolean = !firstActualFramePresented || latestEnteredEpisodeMatches
}
