package ml.melun.mangaview.reader

/**
 * Keeps continuous forward reading bounded without changing the currently visible episode.
 *
 * The transition card and old episode remain available while the first two real images of the
 * next episode cross the viewport. Once the third image is active, that backward history is no
 * longer needed for the product's forward-only NTK flow. Pixel ownership can be retired at once,
 * while the lightweight page-table prefix is removed only after the new episode's structure is
 * complete and the viewport is quiet.
 */
internal object NtkForwardHistoryPolicy {
    const val MIN_CURRENT_EPISODE_IMAGE_ORDINAL = 2

    fun removablePrefix(
        firstCurrentImageIndex: Int,
        currentImageOrdinal: Int,
        forwardReading: Boolean,
    ): Int {
        if (!forwardReading) return 0
        if (firstCurrentImageIndex <= 0) return 0
        if (currentImageOrdinal < MIN_CURRENT_EPISODE_IMAGE_ORDINAL) return 0
        return firstCurrentImageIndex
    }
}
