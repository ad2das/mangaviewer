package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentEpisodeAuthorityPolicyTest {
    @Test
    fun numericTrustedCandidateCannotWalkBackwardDuringForwardContinuation() {
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10073/238730",
                "/manhwa/10073/238729",
                1,
            )
        )
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10073/238730",
                "/manhwa/10073/238731",
                1,
            )
        )
    }

    @Test
    fun decreasingDatabaseIdIsAcceptedWhenVisibleNumbersProveExactNextEpisode() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110268",
                1,
                "4",
                "5",
            )
        )
    }

    @Test
    fun increasingDatabaseIdStillRejectsVisiblePreviousEpisode() {
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110272",
                1,
                "5",
                "4",
            )
        )
    }

    @Test
    fun missingVisibleNumberFallsBackToNumericPathDirection() {
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110268",
                1,
                null,
                "5",
            )
        )
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110272",
                1,
                null,
                "5",
            )
        )
    }

    @Test
    fun increasingDatabaseIdMayStillBeExactPreviousByVisibleNumber() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110268",
                "/manhwa/10928/110270",
                -1,
                "5",
                "4",
            )
        )
    }

    @Test
    fun decreasingDatabaseIdPreservesFractionalAndSplitForwardEpisodes() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110268",
                1,
                "4",
                "4.5",
            )
        )
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/manhwa/10928/110270",
                "/manhwa/10928/110268",
                1,
                "11",
                "11-2",
            )
        )
    }

    @Test
    fun opaqueTrustedCandidateKeepsLegacyOrderingBehavior() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent(
                "/webtoon/work/u-current",
                "/webtoon/work/u-next",
                1,
            )
        )
    }

    @Test
    fun strictAdjacentRunwayAcceptsOnlyExactApiOrTokenBoundGeneratedProofs() {
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.supportsStrictAdjacentManifest(
                NtkExactManifestProofKind.VIEWER_IMAGE_API
            )
        )
        assertTrue(
            NtkAdjacentEpisodeAuthorityPolicy.supportsStrictAdjacentManifest(
                NtkExactManifestProofKind.TOKEN_BOUND_GENERATED
            )
        )
        assertFalse(
            NtkAdjacentEpisodeAuthorityPolicy.supportsStrictAdjacentManifest(
                NtkExactManifestProofKind.EPISODE_DOCUMENT_GENERATED
            )
        )
    }

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
