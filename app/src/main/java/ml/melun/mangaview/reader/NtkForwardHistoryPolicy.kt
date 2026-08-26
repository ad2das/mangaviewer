package ml.melun.mangaview.reader

/**
 * Keeps continuous reading bounded without making the immediately previous episode unreachable.
 *
 * The transition card and old episode remain available while the first two real images of the
 * next episode cross the viewport. Once the third image is active, decoded pixels outside a small
 * backward tail can be retired. A one- or two-image episode can never reach that ordinal, so its
 * exact complete source structure and active terminal source provide the equivalent proof. The
 * lightweight page table and source claim for exactly one predecessor episode remain available
 * for on-demand re-decode; only older episode structure is removed after the new episode is
 * complete and the viewport is quiet.
 */
internal object NtkForwardHistoryPolicy {
    const val MIN_CURRENT_EPISODE_IMAGE_ORDINAL = 2
    const val SHORT_EPISODE_MAX_SOURCE_COUNT = 2
    // Keep a short instant-backtrack runway, not an entire decoded chapter.  Canonical encoded
    // bodies and the complete predecessor page table remain available for on-demand re-decode,
    // so this cap does not limit how far the user may scroll back.  A 24-page RGBA tail exceeded
    // several hundred MiB on ordinary manhwa and compounded with the current/next GPU copies
    // during uninterrupted reading; eight pages keeps the immediate gesture smooth while making
    // long multi-episode sessions memory-bounded.
    const val RETAINED_PREVIOUS_DECODED_TAIL_PAGES = 8
    const val RETAINED_PREVIOUS_FULL_EPISODE_MAX_NODES = 17

    /**
     * A history mutation is allowed only for the exact physical progress observation that chose
     * its candidate.  The Activity may report a newer reverse position while an older control
     * turn is waiting for [ReaderSession]'s page lock; comparing only numeric display indexes lets
     * that stale turn prune the episode that has just become visible again.
     */
    fun currentViewportAuthorizesHistoryMutation(
        expectedObservationRevision: Long,
        publishedObservationRevision: Long,
        latestObservationRevision: Long,
        candidateMatchesPublishedPage: Boolean,
    ): Boolean =
        expectedObservationRevision > 0L &&
            publishedObservationRevision == expectedObservationRevision &&
            latestObservationRevision == expectedObservationRevision &&
            candidateMatchesPublishedPage

    @JvmOverloads
    fun removablePrefix(
        firstCurrentImageIndex: Int,
        currentImageOrdinal: Int,
        forwardReading: Boolean,
        retainedPreviousEpisodeStartIndex: Int = firstCurrentImageIndex,
        terminalShortEpisode: Boolean = false,
        allowOlderThanRetainedPredecessorBeforePixelThreshold: Boolean = false,
    ): Int {
        if (!forwardReading) return 0
        if (firstCurrentImageIndex <= 0) return 0
        val retainedStart = retainedPreviousEpisodeStartIndex.coerceIn(
            0,
            firstCurrentImageIndex,
        )
        val hasOlderStructureOutsideImmediatePredecessor = retainedStart in 1 until
            firstCurrentImageIndex
        if (!mayRetireHistory(currentImageOrdinal, terminalShortEpisode) &&
            !(allowOlderThanRetainedPredecessorBeforePixelThreshold &&
                hasOlderStructureOutsideImmediatePredecessor)
        ) {
            return 0
        }
        return retainedStart
    }

    @JvmOverloads
    fun decodedPixelRetireBefore(
        firstCurrentImageIndex: Int,
        currentImageOrdinal: Int,
        forwardReading: Boolean,
        terminalShortEpisode: Boolean = false,
        retainedPreviousEpisodeStartIndex: Int = -1,
    ): Int {
        if (!forwardReading ||
            !mayRetireHistory(currentImageOrdinal, terminalShortEpisode)
        ) return 0
        if (retainedPreviousEpisodeStartIndex < 0) {
            return (firstCurrentImageIndex - RETAINED_PREVIOUS_DECODED_TAIL_PAGES)
                .coerceAtLeast(0)
        }
        val retainedStart = retainedPreviousEpisodeStartIndex.coerceIn(0, firstCurrentImageIndex)
        val retainedPredecessorNodes = firstCurrentImageIndex - retainedStart
        if (retainedPredecessorNodes <= RETAINED_PREVIOUS_FULL_EPISODE_MAX_NODES) {
            return retainedStart
        }
        return maxOf(
            retainedStart,
            firstCurrentImageIndex - RETAINED_PREVIOUS_DECODED_TAIL_PAGES,
        )
    }

    /**
     * Fail-closed replacement for the third-image proof when the whole episode has fewer than
     * three canonical sources. Auto-cut may publish more than one display for a source, so only
     * exact, contiguous canonical source coverage and the active final source qualify.
     */
    fun terminalShortEpisodeReached(
        authoritativeSourceCount: Int,
        observedSourceIndexes: Collection<Int>,
        activeSourceIndex: Int,
    ): Boolean {
        if (authoritativeSourceCount !in 1..SHORT_EPISODE_MAX_SOURCE_COUNT) return false
        if (activeSourceIndex != authoritativeSourceCount - 1) return false
        return observedSourceIndexes.toSortedSet().toList() ==
            (0 until authoritativeSourceCount).toList()
    }

    private fun mayRetireHistory(
        currentImageOrdinal: Int,
        terminalShortEpisode: Boolean,
    ): Boolean =
        currentImageOrdinal >= MIN_CURRENT_EPISODE_IMAGE_ORDINAL || terminalShortEpisode

    /**
     * Returns the boundary card (or first image) for exactly one predecessor episode. Forward
     * append cards carry the episode they introduce, so the current episode's card must be
     * skipped before the predecessor tail is identified.
     */
    fun <T> retainedPreviousEpisodeStart(
        pages: List<T>,
        firstCurrentImageIndex: Int,
        isTransitionCard: (T) -> Boolean,
        sameEpisode: (T, T) -> Boolean,
    ): Int {
        if (firstCurrentImageIndex !in pages.indices || firstCurrentImageIndex <= 0) return 0
        val currentPage = pages[firstCurrentImageIndex]
        var previousTail = firstCurrentImageIndex - 1
        while (previousTail >= 0) {
            val candidate = pages[previousTail]
            if (!isTransitionCard(candidate) || !sameEpisode(candidate, currentPage)) break
            previousTail--
        }
        if (previousTail < 0) return 0
        val predecessorTail = pages[previousTail]
        if (isTransitionCard(predecessorTail) || sameEpisode(predecessorTail, currentPage)) return 0

        var predecessorFirstImage = previousTail
        while (predecessorFirstImage > 0) {
            val candidate = pages[predecessorFirstImage - 1]
            if (isTransitionCard(candidate) || !sameEpisode(candidate, predecessorTail)) break
            predecessorFirstImage--
        }
        val possibleBoundaryCard = predecessorFirstImage - 1
        if (possibleBoundaryCard < 0) return predecessorFirstImage
        val card = pages[possibleBoundaryCard]
        return if (isTransitionCard(card) && sameEpisode(card, predecessorTail)) {
            possibleBoundaryCard
        } else {
            predecessorFirstImage
        }
    }
}

/**
 * Exact active-episode identity that owns the one destructive predecessor-pixel retirement.
 * Display indexes are deliberately absent: prefix pruning renumbers them while this identity
 * remains immutable for the lifetime of the manifest generation.
 */
internal data class NtkForwardPixelRetirementIdentity private constructor(
    val normalizedEpisodePath: String,
    val manifestDigest: String,
    val manifestRevision: Long,
    val manifestPageCount: Int,
) {
    companion object {
        fun create(
            episodePath: String,
            manifestDigest: String,
            manifestRevision: Long,
            manifestPageCount: Int,
        ): NtkForwardPixelRetirementIdentity? {
            val path = NtkStripDigests.normalizeEpisodePath(episodePath)
            val digest = manifestDigest.trim().lowercase()
            if (path.isEmpty() || !NtkStripDigests.isSha256(digest) ||
                manifestRevision < 0L || manifestPageCount <= 0
            ) {
                return null
            }
            return NtkForwardPixelRetirementIdentity(
                normalizedEpisodePath = path,
                manifestDigest = digest,
                manifestRevision = manifestRevision,
                manifestPageCount = manifestPageCount,
            )
        }
    }
}

/**
 * Session-scoped ownership for forward-history pixel retirement.
 *
 * A claim is intentionally retained even when the first destructive pass finds no releasable
 * pixels (for example, because the physical window still protects them). Decoded-pixel budget/LRU
 * ownership handles later population; reopening the destructive pass after every reverse visit
 * would otherwise recreate the decode -> clear -> decode loop. Consumed episode paths prune the
 * ledger, so continuous A -> B -> C reading keeps only the current and predecessor identities.
 */
internal class NtkForwardPixelRetirementLedger {
    private val lock = Any()
    private val claimed = LinkedHashSet<NtkForwardPixelRetirementIdentity>()

    fun tryClaim(identity: NtkForwardPixelRetirementIdentity): Boolean = synchronized(lock) {
        claimed.add(identity)
    }

    fun removeEpisodePaths(episodePaths: Collection<String>): Int = synchronized(lock) {
        val normalizedPaths = episodePaths
            .map(NtkStripDigests::normalizeEpisodePath)
            .filter(String::isNotEmpty)
            .toHashSet()
        if (normalizedPaths.isEmpty()) return@synchronized 0
        val before = claimed.size
        claimed.removeAll { identity -> identity.normalizedEpisodePath in normalizedPaths }
        before - claimed.size
    }

    fun clear() = synchronized(lock) {
        claimed.clear()
    }

    internal fun snapshotForTest(): Set<NtkForwardPixelRetirementIdentity> = synchronized(lock) {
        claimed.toSet()
    }
}
