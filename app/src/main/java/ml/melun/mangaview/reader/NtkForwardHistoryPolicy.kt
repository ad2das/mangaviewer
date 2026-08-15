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

    @JvmOverloads
    fun removablePrefix(
        firstCurrentImageIndex: Int,
        currentImageOrdinal: Int,
        forwardReading: Boolean,
        retainedPreviousEpisodeStartIndex: Int = firstCurrentImageIndex,
        terminalShortEpisode: Boolean = false,
    ): Int {
        if (!forwardReading) return 0
        if (firstCurrentImageIndex <= 0) return 0
        if (!mayRetireHistory(currentImageOrdinal, terminalShortEpisode)) return 0
        return retainedPreviousEpisodeStartIndex.coerceIn(0, firstCurrentImageIndex)
    }

    @JvmOverloads
    fun decodedPixelRetireBefore(
        firstCurrentImageIndex: Int,
        currentImageOrdinal: Int,
        forwardReading: Boolean,
        terminalShortEpisode: Boolean = false,
    ): Int {
        if (!forwardReading ||
            !mayRetireHistory(currentImageOrdinal, terminalShortEpisode)
        ) return 0
        return (firstCurrentImageIndex - RETAINED_PREVIOUS_DECODED_TAIL_PAGES)
            .coerceAtLeast(0)
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
