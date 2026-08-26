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

    /**
     * Returns only the source indexes owned by one episode from a validated physical viewport.
     *
     * A boundary frame deliberately retains both the launch tail and adjacent p0 identities so
     * presentation/readiness evidence can be published for the next episode. Traversal coverage,
     * however, belongs to one manifest and must never reinterpret adjacent p0 as launch source 0.
     */
    fun traversalSourceIndexesForEpisode(
        identities: List<Identity>,
        episodePath: String
    ): List<Int> {
        if (episodePath.isBlank()) return emptyList()
        return identities.asSequence()
            .filter { identity -> identity.episodePath == episodePath }
            .map { identity -> identity.sourcePageIndex }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Allocation-bounded counterpart for the identity objects already captured by Surface.
     *
     * The completed-draw listener used to copy every committed identity into [Identity], then
     * build sequence/distinct/sorted collections on every physical frame. These objects already
     * are immutable exact-token evidence, so scan them directly. The third parameter keeps this
     * overload distinct after JVM generic erasure and makes accidental use with an uncommitted
     * list explicit at the call site.
     */
    fun traversalSourceIndexesForEpisode(
        identities: List<ReaderSurfaceView.CommittedPageIdentity>,
        episodePath: String,
        committedIdentityProof: Boolean,
    ): IntArray {
        if (!committedIdentityProof || episodePath.isBlank() || identities.isEmpty()) {
            return IntArray(0)
        }
        val indexes = IntArray(identities.size)
        var count = 0
        for (identity in identities) {
            if (identity.normalizedEpisodePath != episodePath) continue
            val source = identity.sourcePageIndex
            var insertion = 0
            while (insertion < count && indexes[insertion] < source) insertion++
            if (insertion < count && indexes[insertion] == source) continue
            if (insertion < count) {
                System.arraycopy(indexes, insertion, indexes, insertion + 1, count - insertion)
            }
            indexes[insertion] = source
            count++
        }
        return if (count == indexes.size) indexes else indexes.copyOf(count)
    }

    /** Validates exact Surface identities without per-frame wrapper/block/set allocations. */
    fun isValidCommitted(
        identities: List<ReaderSurfaceView.CommittedPageIdentity>,
        launchEpisodePath: String,
        launchManifestDigest: String,
        launchCanonicalAssets: List<String>,
    ): Boolean {
        if (identities.isEmpty() || launchEpisodePath.isBlank() ||
            !NtkStripDigests.isSha256(launchManifestDigest) || launchCanonicalAssets.isEmpty()
        ) return false

        var blockCount = 0
        var firstBlockPath = ""
        var activePath = ""
        var activeDigest = ""
        var activePageCount = 0
        var previousSource = -1
        for (identity in identities) {
            val path = identity.normalizedEpisodePath
            val source = identity.sourcePageIndex
            val pageCount = identity.manifestPageCount
            if (path.isBlank() || !NtkStripDigests.isSha256(identity.manifestDigest) ||
                pageCount <= 0 || source !in 0 until pageCount ||
                identity.canonicalAsset.isBlank()
            ) return false

            if (path == launchEpisodePath &&
                (identity.manifestDigest != launchManifestDigest ||
                    pageCount != launchCanonicalAssets.size ||
                    identity.canonicalAsset != launchCanonicalAssets[source])
            ) return false

            if (path != activePath) {
                if (blockCount >= 2 || (blockCount > 0 && path == firstBlockPath)) return false
                if (blockCount > 0 &&
                    (previousSource != activePageCount - 1 || source != 0)
                ) return false
                blockCount++
                if (blockCount == 1) firstBlockPath = path
                activePath = path
                activeDigest = identity.manifestDigest
                activePageCount = pageCount
            } else if (identity.manifestDigest != activeDigest ||
                pageCount != activePageCount || source < previousSource
            ) {
                return false
            }
            previousSource = source
        }
        return true
    }

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
