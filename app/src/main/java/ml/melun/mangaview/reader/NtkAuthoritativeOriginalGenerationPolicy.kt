package ml.melun.mangaview.reader

/**
 * Allows one canonical pixel-owner handoff when a retained Surface outlives its ReaderSession.
 *
 * Bitmap identity alone cannot distinguish a stale duplicate from the fresh decode produced by a
 * new session generation. The immutable original proof still has to match, and generations may
 * only move forward; callbacks from an older or equal generation remain unable to replace pixels.
 */
internal object NtkAuthoritativeOriginalGenerationPolicy {
    fun mayReplaceRetainedOriginal(
        existingUsableOriginal: Boolean,
        sameTileIdentity: Boolean,
        sameCanonicalProof: Boolean,
        existingGeneration: Int,
        incomingGeneration: Int,
    ): Boolean = existingUsableOriginal &&
        !sameTileIdentity &&
        sameCanonicalProof &&
        existingGeneration > 0 &&
        incomingGeneration > existingGeneration
}
