package ml.melun.mangaview.reader

/**
 * Qualifies the one metadata update that may bypass the pre-presentation clean-tail gate.
 *
 * The caller already proved a completed, identity-valid physical frame. Keeping the path match in
 * this pure policy prevents an old or cross-episode compositor callback from advancing reader
 * metadata while allowing that stronger proof to complete the transition it was requested for.
 */
object NtkPhysicalAdjacentMetadataAdoptionPolicy {
    @JvmStatic
    fun hasCommittedPhysicalAuthority(
        forwardExactEpisodeChange: Boolean,
        targetEpisodePath: String?,
        physicallyPresentedEpisodePath: String?,
    ): Boolean {
        if (!forwardExactEpisodeChange) return false
        val target = NtkStripDigests.normalizeEpisodePath(targetEpisodePath.orEmpty())
        val presented = NtkStripDigests.normalizeEpisodePath(
            physicallyPresentedEpisodePath.orEmpty(),
        )
        return target.isNotBlank() && target.equals(presented, ignoreCase = true)
    }
}
