package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPhysicalAdjacentMetadataAdoptionPolicyTest {
    @Test
    fun stationaryMixedBoundaryCannotUndoManualPreviousNavigation() {
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.shouldAdoptMixedBoundary(
                outgoingPixelsFullyConsumed = false,
            ),
        )
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.shouldAdoptMixedBoundary(
                outgoingPixelsFullyConsumed = false,
            ),
        )
        assertTrue(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.shouldAdoptMixedBoundary(
                outgoingPixelsFullyConsumed = true,
            ),
        )
        assertFalse(
            NtkPhysicalAdjacentMetadataAdoptionPolicy.shouldAdoptMixedBoundary(
                outgoingPixelsFullyConsumed = true,
                freshPhysicalInputAfterEpisodeLaunch = false,
            ),
        )
    }

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
