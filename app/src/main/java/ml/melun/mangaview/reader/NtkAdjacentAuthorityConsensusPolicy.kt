package ml.melun.mangaview.reader

/** Requires two independent episode-list authorities to agree before overriding legacy order. */
internal object NtkAdjacentAuthorityConsensusPolicy {
    enum class TargetDecision {
        ACCEPT,
        REJECT,
        DEFER_TO_LEGACY,
    }

    fun <T> agreedCandidate(
        visible: T?,
        canonical: T?,
        sameEpisode: (T, T) -> Boolean,
    ): T? {
        if (visible == null || canonical == null) return null
        return visible.takeIf { sameEpisode(it, canonical) }
    }

    fun <T> decideTarget(
        visible: T?,
        canonical: T?,
        target: T,
        sameEpisode: (T, T) -> Boolean,
    ): TargetDecision {
        val agreed = agreedCandidate(visible, canonical, sameEpisode)
            ?: return TargetDecision.DEFER_TO_LEGACY
        return if (sameEpisode(agreed, target)) {
            TargetDecision.ACCEPT
        } else {
            TargetDecision.REJECT
        }
    }
}
