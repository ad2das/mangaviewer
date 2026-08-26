package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPhysicalAdjacentMetadataAdoptionPolicyTest {
    @Test
    fun matchingCommittedPhysicalAdjacentPathOwnsMetadataTransition() {
        assertTrue(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.hasCommittedPhysicalAuthority(
                forwardExactEpisodeChange = true,
                targetEpisodePath = "/manhwa/26450/1793867",
                physicallyPresentedEpisodePath = "/MANHWA/26450/1793867/",
            ),
        )
    }

    @Test
    fun staleMismatchedOrNonForwardProofCannotOwnMetadataTransition() {
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.hasCommittedPhysicalAuthority(
                forwardExactEpisodeChange = true,
                targetEpisodePath = "/manhwa/26450/1793867",
                physicallyPresentedEpisodePath = "/manhwa/26450/1792170",
            ),
        )
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.hasCommittedPhysicalAuthority(
                forwardExactEpisodeChange = false,
                targetEpisodePath = "/manhwa/26450/1793867",
                physicallyPresentedEpisodePath = "/manhwa/26450/1793867",
            ),
        )
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.hasCommittedPhysicalAuthority(
                forwardExactEpisodeChange = true,
                targetEpisodePath = "",
                physicallyPresentedEpisodePath = "",
            ),
        )
    }
}
