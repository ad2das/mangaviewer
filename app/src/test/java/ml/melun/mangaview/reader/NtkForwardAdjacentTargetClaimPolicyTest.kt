package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkForwardAdjacentTargetClaimPolicyTest {
    private val policy = NtkForwardAdjacentTargetClaimPolicy

    @Test
    fun persistedExactPairRejectsOpaqueNumericAndAttachedListConflicts() {
        for (weaker in listOf(
            NtkForwardAdjacentTargetClaimPolicy.Authority.NUMERIC_OR_LIST_FALLBACK,
            NtkForwardAdjacentTargetClaimPolicy.Authority.ATTACHED_PROVIDER_NEIGHBOR,
        )) {
            assertEquals(
                NtkForwardAdjacentTargetClaimPolicy.Decision.REJECT,
                policy.decide(
                    "/manhwa/34187/1716490",
                    NtkForwardAdjacentTargetClaimPolicy.Authority.PERSISTED_EXACT_PAIR,
                    false,
                    "/manhwa/34187/1716548",
                    weaker,
                ),
            )
        }
    }

    @Test
    fun persistedExactPairReplacesAnUnpublishedNumericFallback() {
        assertEquals(
            NtkForwardAdjacentTargetClaimPolicy.Decision.REPLACE,
            policy.decide(
                "/manhwa/11276/115836",
                NtkForwardAdjacentTargetClaimPolicy.Authority.NUMERIC_OR_LIST_FALLBACK,
                false,
                "/manhwa/11276/115832",
                NtkForwardAdjacentTargetClaimPolicy.Authority.PERSISTED_EXACT_PAIR,
            ),
        )
    }

    @Test
    fun sameTargetJoinsWithoutInvalidatingItsPublicationToken() {
        assertEquals(
            NtkForwardAdjacentTargetClaimPolicy.Decision.JOIN,
            policy.decide(
                "/manhwa/work/episode-b",
                NtkForwardAdjacentTargetClaimPolicy.Authority.NUMERIC_OR_LIST_FALLBACK,
                false,
                "/MANHWA/work/episode-b",
                NtkForwardAdjacentTargetClaimPolicy.Authority.PERSISTED_EXACT_PAIR,
            ),
        )
        assertTrue(policy.sameTarget("/manhwa/work/episode-b", "/MANHWA/work/episode-b"))
        assertFalse(policy.sameTarget("/manhwa/work/episode-b", "/manhwa/work/episode-c"))
        assertFalse(policy.sameTarget("", "/manhwa/work/episode-b"))
    }

    @Test
    fun freshProviderMayReplacePersistedOnlyBeforeStructureCommit() {
        assertEquals(
            NtkForwardAdjacentTargetClaimPolicy.Decision.REPLACE,
            policy.decide(
                "/webtoon/work/persisted",
                NtkForwardAdjacentTargetClaimPolicy.Authority.PERSISTED_EXACT_PAIR,
                false,
                "/webtoon/work/fresh",
                NtkForwardAdjacentTargetClaimPolicy.Authority.FRESH_ORDERED_PROVIDER_NEIGHBOR,
            ),
        )
        assertEquals(
            NtkForwardAdjacentTargetClaimPolicy.Decision.REJECT,
            policy.decide(
                "/webtoon/work/persisted",
                NtkForwardAdjacentTargetClaimPolicy.Authority.PERSISTED_EXACT_PAIR,
                true,
                "/webtoon/work/fresh",
                NtkForwardAdjacentTargetClaimPolicy.Authority.FRESH_ORDERED_PROVIDER_NEIGHBOR,
            ),
        )
    }

    @Test
    fun equalStrengthConflictFailsClosed() {
        assertEquals(
            NtkForwardAdjacentTargetClaimPolicy.Decision.REJECT,
            policy.decide(
                "/manhwa/work/b",
                NtkForwardAdjacentTargetClaimPolicy.Authority.ATTACHED_PROVIDER_NEIGHBOR,
                false,
                "/manhwa/work/c",
                NtkForwardAdjacentTargetClaimPolicy.Authority.ATTACHED_PROVIDER_NEIGHBOR,
            ),
        )
    }
}
