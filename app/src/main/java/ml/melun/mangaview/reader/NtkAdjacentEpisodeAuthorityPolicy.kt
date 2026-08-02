package ml.melun.mangaview.reader

/**
 * Decides whether the continuous reader may speculate a numeric adjacent episode path.
 *
 * A non-empty episode list is authoritative even when the current episode is at its boundary.
 * Treating "no candidate in the requested direction" as permission to increment/decrement the
 * database id creates phantom episodes and permanently retries URLs that do not exist.
 */
internal object NtkAdjacentEpisodeAuthorityPolicy {
    private val numericEpisodePath = Regex(
        "^/(manhwa|webtoon)/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Rejects a legacy `nextEp()`/`prevEp()` answer only when both paths provide an unambiguous
     * numeric direction for the same work and that answer points backward. Episode arrays from
     * different NTK works are not consistently ordered; trusting their legacy index here can turn
     * B -> C continuation into B -> A. Opaque/special paths retain the existing legacy behavior.
     */
    @JvmStatic
    fun isTrustedCandidateDirectionallyConsistent(
        sourceEpisodePath: String?,
        candidateEpisodePath: String?,
        direction: Int,
        sourceVisibleEpisodeNumber: String? = null,
        candidateVisibleEpisodeNumber: String? = null,
    ): Boolean {
        if (direction == 0) return false
        val source = numericEpisodePath.matchEntire(sourceEpisodePath?.trim().orEmpty()) ?: return true
        val candidate =
            numericEpisodePath.matchEntire(candidateEpisodePath?.trim().orEmpty()) ?: return true
        if (!source.groupValues[1].equals(candidate.groupValues[1], ignoreCase = true) ||
            source.groupValues[2] != candidate.groupValues[2]
        ) return true
        val sourceId = source.groupValues[3].toLongOrNull() ?: return true
        val candidateId = candidate.groupValues[3].toLongOrNull() ?: return true
        // NTK's final path segment is a database record key, not a chronological episode number.
        // Some valid rows therefore move 110270 -> 110268 while their independently rendered
        // labels move 4 -> 5. Conversely, an increasing key must not authorize a visible 5 -> 4
        // previous episode. Prefer visible ordering whenever both normalized keys are comparable;
        // this also preserves fractional and split chapters such as 4 -> 4.5 and 11 -> 11-2.
        val visibleOrder = compareVisibleEpisodeKeys(
            sourceVisibleEpisodeNumber,
            candidateVisibleEpisodeNumber,
        )
        if (visibleOrder != null && visibleOrder != 0) {
            return if (direction > 0) visibleOrder > 0 else visibleOrder < 0
        }
        return if (direction > 0) candidateId > sourceId else candidateId < sourceId
    }

    /** Candidate relative to source: positive=newer, negative=older, zero=overlap, null=unknown. */
    private fun compareVisibleEpisodeKeys(sourceKey: String?, candidateKey: String?): Int? {
        val source = visibleEpisodeRange(sourceKey) ?: return null
        val candidate = visibleEpisodeRange(candidateKey) ?: return null
        return when {
            candidate.first > source.second + VISIBLE_EPISODE_EPSILON -> 1
            candidate.second < source.first - VISIBLE_EPISODE_EPSILON -> -1
            else -> 0
        }
    }

    private fun visibleEpisodeRange(rawKey: String?): Pair<Double, Double>? {
        val key = rawKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        return try {
            if ('-' in key) {
                val parts = key.split('-', limit = 2)
                if (parts.size != 2) return null
                val major = parts[0].toDouble()
                val part = parts[1].toDouble()
                val value = major + minOf(part, 9999.0) / 10000.0
                value to value
            } else {
                val values = key.split(',')
                    .filter { it.isNotEmpty() }
                    .map { it.toDouble() }
                if (values.isEmpty()) null else values.min() to values.max()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private const val VISIBLE_EPISODE_EPSILON = 0.0001

    /**
     * Both proofs below bind the complete ordered asset list to the exact episode path. A plain
     * episode-document generation is intentionally excluded because it has no token-bound source
     * identity and therefore cannot publish an adjacent strict-body runway.
     */
    @JvmStatic
    fun supportsStrictAdjacentManifest(proofKind: NtkExactManifestProofKind): Boolean =
        proofKind == NtkExactManifestProofKind.VIEWER_IMAGE_API ||
            proofKind == NtkExactManifestProofKind.TOKEN_BOUND_GENERATED

    @JvmStatic
    fun supportsStrictAdjacentManifest(
        authority: NtkAuthoritativeManifest,
        expectedEpisodePath: String,
    ): Boolean {
        val concreteProofSupported = when (authority.proof) {
            is NtkViewerImageApiManifestProof,
            is NtkTokenBoundGeneratedManifestProof,
            is NtkObservedNumericReplicaManifestProof -> true
            is NtkEpisodeDocumentGeneratedManifestProof -> false
        }
        val expectedPath = NtkStripDigests.normalizeEpisodePath(expectedEpisodePath)
        return concreteProofSupported &&
            supportsStrictAdjacentManifest(authority.proof.kind) &&
            authority.isProductionClaimable &&
            authority.seal.revision == authority.proof.discoveryGeneration &&
            expectedPath.isNotEmpty() &&
            authority.seal.normalizedEpisodePath.equals(expectedPath, ignoreCase = true) &&
            authority.seal.normalizedCanonicalAssets.isNotEmpty() &&
            authority.seal.normalizedCanonicalAssets.size == authority.seal.pageCount
    }

    @JvmStatic
    fun maySynthesizeNumericCandidate(
        authoritativeEpisodeCount: Int,
        hasOnlineEpisodeRepository: Boolean
    ): Boolean {
        require(authoritativeEpisodeCount >= 0)
        return authoritativeEpisodeCount == 0 && !hasOnlineEpisodeRepository
    }

    /**
     * Accepts a complete generated manifest from a fresh, exact-path viewer document. When the
     * selected episode row also declares a page count it must agree; some real episode lists omit
     * that optional field, so zero means "no second opinion" rather than a contradiction. A failed
     * legacy image API must not discard the document authority, while conflicting metadata must
     * never expand a speculative adjacent episode.
     */
    @JvmStatic
    fun matchedAuthoritativePageCount(
        clickPayloadCount: Int,
        freshViewerDocumentCount: Int,
        maximumPageCount: Int
    ): Int {
        require(clickPayloadCount >= 0)
        require(freshViewerDocumentCount >= 0)
        require(maximumPageCount > 0)
        if (freshViewerDocumentCount !in 2..maximumPageCount) return 0
        if (clickPayloadCount == 0) return freshViewerDocumentCount
        if (clickPayloadCount !in 2..maximumPageCount) return 0
        return freshViewerDocumentCount.takeIf { it == clickPayloadCount } ?: 0
    }
}
