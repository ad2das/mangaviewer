package ml.melun.mangaview.reader

/**
 * Orders competing forward-neighbour evidence before any exact target can mutate reader structure.
 *
 * An exact image manifest proves the bytes for one episode, not that the episode is the immediate
 * neighbour of the current one. In particular, NTK numeric path ids are opaque database keys, so a
 * numeric fallback must never displace the six-field next-episode identity persisted with the exact
 * predecessor. Once structure has been published, changing the winner would interleave episodes.
 */
internal object NtkForwardAdjacentTargetClaimPolicy {
    enum class Authority(val rank: Int) {
        NUMERIC_OR_LIST_FALLBACK(0),
        ATTACHED_PROVIDER_NEIGHBOR(1),
        PERSISTED_EXACT_PAIR(2),
        FRESH_ORDERED_PROVIDER_NEIGHBOR(3),
    }

    enum class Decision {
        ACCEPT,
        JOIN,
        REPLACE,
        REJECT,
    }

    /**
     * Episode objects are mutable repository projections and may be reconstructed while an exact
     * append waits for physical motion to become idle. The canonical provider path, not JVM object
     * identity, owns a forward-target claim.
     */
    fun sameTarget(existingTargetPath: String?, proposedTargetPath: String?): Boolean =
        !existingTargetPath.isNullOrEmpty() &&
            !proposedTargetPath.isNullOrEmpty() &&
            existingTargetPath.equals(proposedTargetPath, ignoreCase = true)

    fun decide(
        existingTargetPath: String?,
        existingAuthority: Authority?,
        existingStructureCommitted: Boolean,
        proposedTargetPath: String,
        proposedAuthority: Authority,
    ): Decision {
        if (existingTargetPath.isNullOrEmpty() || existingAuthority == null) {
            return Decision.ACCEPT
        }
        if (sameTarget(existingTargetPath, proposedTargetPath)) {
            return Decision.JOIN
        }
        if (existingStructureCommitted) return Decision.REJECT
        return if (proposedAuthority.rank > existingAuthority.rank) {
            Decision.REPLACE
        } else {
            Decision.REJECT
        }
    }
}
