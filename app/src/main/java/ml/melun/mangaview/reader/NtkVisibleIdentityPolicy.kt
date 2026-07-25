package ml.melun.mangaview.reader

/**
 * Validates the image identities carried by one physically committed viewport.
 *
 * Seamless forward reading can legitimately show the last page of one episode and the first page
 * of the next episode in the same frame. Treating that boundary as a wrong binding both hid the
 * useful transition evidence and produced false failures. This policy still rejects malformed
 * manifests, asset substitutions, page-order regressions, skipped boundary pages, and any frame
 * that mixes more than one contiguous episode boundary.
 */
internal object NtkVisibleIdentityPolicy {
    data class Identity(
        val episodePath: String,
        val sourcePageIndex: Int,
        val canonicalAsset: String,
        val manifestDigest: String,
        val manifestPageCount: Int
    )

    data class LaunchManifest(
        val episodePath: String,
        val manifestDigest: String,
        val canonicalAssets: List<String>
    )

    fun isValid(
        identities: List<Identity>,
        launch: LaunchManifest
    ): Boolean {
        if (identities.isEmpty() || launch.episodePath.isBlank() ||
            !NtkStripDigests.isSha256(launch.manifestDigest) ||
            launch.canonicalAssets.isEmpty()
        ) return false

        val episodeBlocks = ArrayList<MutableList<Identity>>(2)
        val seenEpisodePaths = LinkedHashSet<String>()
        for (identity in identities) {
            if (identity.episodePath.isBlank() ||
                !NtkStripDigests.isSha256(identity.manifestDigest) ||
                identity.manifestPageCount <= 0 ||
                identity.sourcePageIndex !in 0 until identity.manifestPageCount ||
                identity.canonicalAsset.isBlank()
            ) return false

            if (identity.episodePath == launch.episodePath) {
                if (identity.manifestDigest != launch.manifestDigest ||
                    identity.manifestPageCount != launch.canonicalAssets.size ||
                    identity.canonicalAsset != launch.canonicalAssets[identity.sourcePageIndex]
                ) return false
            }

            val activeBlock = episodeBlocks.lastOrNull()
            if (activeBlock == null || activeBlock.first().episodePath != identity.episodePath) {
                if (!seenEpisodePaths.add(identity.episodePath) || episodeBlocks.size == 2) {
                    return false
                }
                episodeBlocks.add(arrayListOf(identity))
            } else {
                val previous = activeBlock.last()
                if (identity.manifestDigest != previous.manifestDigest ||
                    identity.manifestPageCount != previous.manifestPageCount ||
                    identity.sourcePageIndex < previous.sourcePageIndex
                ) return false
                activeBlock.add(identity)
            }
        }

        if (episodeBlocks.size == 2) {
            val outgoing = episodeBlocks[0].last()
            val incoming = episodeBlocks[1].first()
            if (outgoing.sourcePageIndex != outgoing.manifestPageCount - 1 ||
                incoming.sourcePageIndex != 0
            ) return false
        }
        return true
    }
}
