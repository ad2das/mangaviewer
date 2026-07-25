package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentEpisodeAuthorityPolicyTest {
    @Test
    fun offlineSourceWithoutMetadataAllowsBoundedNumericDiscovery() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.maySynthesizeNumericCandidate(
                authoritativeEpisodeCount = 0,
                hasOnlineEpisodeRepository = false
            )
        )
    }

    @Test
    fun loadedEpisodeListBlocksPhantomIdArithmeticAtTerminalEpisode() {
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.maySynthesizeNumericCandidate(
                authoritativeEpisodeCount = 40,
                hasOnlineEpisodeRepository = true
            )
        )
    }

    @Test
    fun onlineSourceWaitsForRealEpisodeMetadataInsteadOfGuessingDatabaseId() {
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.maySynthesizeNumericCandidate(
                authoritativeEpisodeCount = 0,
                hasOnlineEpisodeRepository = true
            )
        )
    }

    @Test
    fun matchingEpisodeRowAndFreshViewerDocumentAcceptCompleteLargeManifest() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.matchedAuthoritativePageCount(
                clickPayloadCount = 270,
                freshViewerDocumentCount = 270,
                maximumPageCount = 300
            ) == 270
        )
    }

    @Test
    fun staleOrMismatchedViewerDocumentCannotExpandAdjacentManifest() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.matchedAuthoritativePageCount(
                clickPayloadCount = 84,
                freshViewerDocumentCount = 83,
                maximumPageCount = 300
            ) == 0
        )
    }

    @Test
    fun freshExactViewerDocumentSuppliesCountWhenEpisodeRowOmitsIt() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.matchedAuthoritativePageCount(
                clickPayloadCount = 0,
                freshViewerDocumentCount = 84,
                maximumPageCount = 300
            ) == 84
        )
    }
}
