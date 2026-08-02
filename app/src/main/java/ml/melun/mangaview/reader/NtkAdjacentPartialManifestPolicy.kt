package ml.melun.mangaview.reader

/**
 * Keeps the bounded adjacent runway provisional until a complete episode authority exists.
 *
 * Four pages are intentionally enough to make the next episode immediately scrollable, but they
 * are not evidence that the episode itself has only four pages. Short NTK episodes therefore need
 * an exact path-and-assets proof before their PageRefs may carry a complete manifest identity.
 */
internal object NtkAdjacentPartialManifestPolicy {
    const val EARLY_RUNWAY_PAGE_LIMIT = 4

    @JvmStatic
    fun canPublishCompleteManifestIdentity(
        ntkEpisode: Boolean,
        candidatePageCount: Int,
        declaredPageCount: Int,
        exactAuthorityPageCount: Int,
        exactAuthorityAssetsMatch: Boolean,
        trustedExactPageCount: Int,
        trustedExactAssetsMatch: Boolean,
    ): Boolean {
        if (candidatePageCount <= 0) return false
        if (!ntkEpisode) return true

        // A known larger episode proves that this list is only a prefix, regardless of where the
        // URLs came from.
        if (declaredPageCount > candidatePageCount) return false

        if (candidatePageCount > EARLY_RUNWAY_PAGE_LIMIT) return true
        return (exactAuthorityAssetsMatch && exactAuthorityPageCount == candidatePageCount) ||
            (trustedExactAssetsMatch && trustedExactPageCount == candidatePageCount)
    }

    /**
     * A small declared count is not independently authoritative: early publication code may have
     * copied the runway size into mutable Manga metadata. Only the immutable PageRef seal can make
     * a one-to-four-page NTK episode complete.
     */
    @JvmStatic
    fun canonicalCompletionPageCount(
        ntkEpisode: Boolean,
        declaredPageCount: Int,
        sealedManifestPageCount: Int,
    ): Int {
        val declared = declaredPageCount.coerceAtLeast(0)
        val sealed = sealedManifestPageCount.coerceAtLeast(0)
        if (ntkEpisode && declared in 1..EARLY_RUNWAY_PAGE_LIMIT && sealed == 0) return 0
        return maxOf(declared, sealed)
    }
}
