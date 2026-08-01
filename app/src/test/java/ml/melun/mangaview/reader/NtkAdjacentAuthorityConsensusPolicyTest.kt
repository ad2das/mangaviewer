package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkAdjacentAuthorityConsensusPolicyTest {
    private val sameEpisode: (String, String) -> Boolean = { first, second -> first == second }

    @Test
    fun matchingAuthoritiesApproveTheSameTarget() {
        assertEquals(
            NtkAdjacentAuthorityConsensusPolicy.TargetDecision.ACCEPT,
            NtkAdjacentAuthorityConsensusPolicy.decideTarget("next", "next", "next", sameEpisode),
        )
    }

    @Test
    fun matchingAuthoritiesRejectADifferentTarget() {
        assertEquals(
            NtkAdjacentAuthorityConsensusPolicy.TargetDecision.REJECT,
            NtkAdjacentAuthorityConsensusPolicy.decideTarget("next", "next", "old", sameEpisode),
        )
    }

    @Test
    fun conflictingAuthoritiesDeferEvenWhenVisibleMatchesTheTarget() {
        assertEquals(
            NtkAdjacentAuthorityConsensusPolicy.TargetDecision.DEFER_TO_LEGACY,
            NtkAdjacentAuthorityConsensusPolicy.decideTarget("next", "other", "next", sameEpisode),
        )
        assertNull(
            NtkAdjacentAuthorityConsensusPolicy.agreedCandidate("next", "other", sameEpisode)
        )
    }

    @Test
    fun oneOrBothMissingAuthoritiesDeferToLegacyOrder() {
        assertEquals(
            NtkAdjacentAuthorityConsensusPolicy.TargetDecision.DEFER_TO_LEGACY,
            NtkAdjacentAuthorityConsensusPolicy.decideTarget(null, "next", "next", sameEpisode),
        )
        assertEquals(
            NtkAdjacentAuthorityConsensusPolicy.TargetDecision.DEFER_TO_LEGACY,
            NtkAdjacentAuthorityConsensusPolicy.decideTarget(null, null, "next", sameEpisode),
        )
    }
}
