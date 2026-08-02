package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentPartialManifestPolicyTest {
    @Test
    fun unknownOneHundredEightyPageEpisodeCannotSelfSealItsEarlyFourPageRunway() {
        assertFalse(
            NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity(
                ntkEpisode = true,
                candidatePageCount = 4,
                declaredPageCount = 0,
                exactAuthorityPageCount = 0,
                exactAuthorityAssetsMatch = false,
                trustedExactPageCount = 0,
                trustedExactAssetsMatch = false,
            )
        )
    }

    @Test
    fun knownLargerEpisodeCannotSealAnyShortPrefix() {
        assertFalse(
            NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity(
                ntkEpisode = true,
                candidatePageCount = 4,
                declaredPageCount = 180,
                exactAuthorityPageCount = 4,
                exactAuthorityAssetsMatch = true,
                trustedExactPageCount = 4,
                trustedExactAssetsMatch = true,
            )
        )
    }

    @Test
    fun exactPathAndAssetsAuthorityAllowsLegitimateFourPageEpisode() {
        assertTrue(
            NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity(
                ntkEpisode = true,
                candidatePageCount = 4,
                declaredPageCount = 0,
                exactAuthorityPageCount = 4,
                exactAuthorityAssetsMatch = true,
                trustedExactPageCount = 0,
                trustedExactAssetsMatch = false,
            )
        )
    }

    @Test
    fun trustedExactApiAuthorityAllowsLegitimateFourPageEpisode() {
        assertTrue(
            NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity(
                ntkEpisode = true,
                candidatePageCount = 4,
                declaredPageCount = 0,
                exactAuthorityPageCount = 0,
                exactAuthorityAssetsMatch = false,
                trustedExactPageCount = 4,
                trustedExactAssetsMatch = true,
            )
        )
    }

    @Test
    fun countWithoutMatchingAssetsCannotAuthorizeShortEpisode() {
        assertFalse(
            NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity(
                ntkEpisode = true,
                candidatePageCount = 4,
                declaredPageCount = 4,
                exactAuthorityPageCount = 4,
                exactAuthorityAssetsMatch = false,
                trustedExactPageCount = 4,
                trustedExactAssetsMatch = false,
            )
        )
    }

    @Test
    fun mutableSmallCountCannotCompleteWithoutImmutableSeal() {
        assertEquals(
            0,
            NtkAdjacentPartialManifestPolicy.canonicalCompletionPageCount(
                ntkEpisode = true,
                declaredPageCount = 4,
                sealedManifestPageCount = 0,
            )
        )
        assertEquals(
            4,
            NtkAdjacentPartialManifestPolicy.canonicalCompletionPageCount(
                ntkEpisode = true,
                declaredPageCount = 4,
                sealedManifestPageCount = 4,
            )
        )
    }
}
