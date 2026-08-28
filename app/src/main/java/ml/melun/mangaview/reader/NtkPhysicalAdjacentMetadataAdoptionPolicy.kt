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

    /** A mixed old-tail/new-p0 frame never owns next-episode metadata. */
    @JvmStatic
    fun shouldAdoptMixedBoundary(
        outgoingPixelsFullyConsumed: Boolean,
        freshPhysicalInputAfterEpisodeLaunch: Boolean = true,
    ): Boolean {
        // A toolbar/picker launch establishes the selected episode as the new metadata owner.
        // Its restored terminal geometry can move when a prepared successor is inserted even
        // though the user has not touched the reader.  Neither an offset delta nor complete
        // outgoing consumption caused by that reflow is permission to leave the selected
        // episode.  One real post-launch MOVE re-enables normal continuous adoption.
        if (!freshPhysicalInputAfterEpisodeLaunch) return false
        // A glimpse of successor p0 at the bottom edge is preparation, not ownership. Advancing
        // title/progress here makes the app report the next episode while the user is still
        // looking at outgoing pixels and can stop an input driver (or a real person's gesture)
        // before the clean successor viewport exists. The immutable completed draw must contain
        // no outgoing pixels at all.
        return outgoingPixelsFullyConsumed
    }
}
