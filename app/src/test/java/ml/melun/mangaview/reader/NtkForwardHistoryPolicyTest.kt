package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkForwardHistoryPolicyTest {
    private data class Marker(val episode: String, val card: Boolean = false)

    private fun retirementIdentity(
        path: String,
        digestChar: Char,
        revision: Long,
        pageCount: Int = 25,
    ): NtkForwardPixelRetirementIdentity = requireNotNull(
        NtkForwardPixelRetirementIdentity.create(
            episodePath = path,
            manifestDigest = digestChar.toString().repeat(64),
            manifestRevision = revision,
            manifestPageCount = pageCount,
        ),
    )

    @Test
    fun historyMutationRequiresTheExactLatestPhysicalViewportObservation() {
        assertTrue(
            NtkForwardHistoryPolicy.currentViewportAuthorizesHistoryMutation(
                expectedObservationRevision = 7L,
                publishedObservationRevision = 7L,
                latestObservationRevision = 7L,
                candidateMatchesPublishedPage = true,
            )
        )
        assertFalse(
            NtkForwardHistoryPolicy.currentViewportAuthorizesHistoryMutation(
                expectedObservationRevision = 7L,
                publishedObservationRevision = 7L,
                latestObservationRevision = 8L,
                candidateMatchesPublishedPage = true,
            )
        )
        assertFalse(
            NtkForwardHistoryPolicy.currentViewportAuthorizesHistoryMutation(
                expectedObservationRevision = 7L,
                publishedObservationRevision = 8L,
                latestObservationRevision = 8L,
                candidateMatchesPublishedPage = true,
            )
        )
        assertFalse(
            NtkForwardHistoryPolicy.currentViewportAuthorizesHistoryMutation(
                expectedObservationRevision = 7L,
                publishedObservationRevision = 7L,
                latestObservationRevision = 7L,
                candidateMatchesPublishedPage = false,
            )
        )
    }

    @Test
    fun keepsBoundaryUntilThirdRealImageOfNextEpisode() {
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 0, true))
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 1, true))
        assertEquals(86, NtkForwardHistoryPolicy.removablePrefix(86, 2, true))
    }

    @Test
    fun quietSuccessorP0RemovesOnlyHistoryOlderThanTheImmediatePredecessor() {
        assertEquals(
            3,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = 7,
                currentImageOrdinal = 0,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 3,
                allowOlderThanRetainedPredecessorBeforePixelThreshold = true,
            ),
        )
        assertEquals(
            0,
            NtkForwardHistoryPolicy.decodedPixelRetireBefore(
                firstCurrentImageIndex = 7,
                currentImageOrdinal = 0,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 3,
            ),
        )
    }

    @Test
    fun neverTrimsCurrentFirstEpisodeOrReverseReading() {
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(0, 20, true))
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 20, false))
    }

    @Test
    fun retainsOneWholePredecessorEpisodeInsteadOfA24PageStructureTail() {
        assertEquals(
            86,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = 180,
                currentImageOrdinal = 2,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 86,
            ),
        )
        assertEquals(
            0,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = 87,
                currentImageOrdinal = 2,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 0,
            ),
        )
    }

    @Test
    fun decodedPixelsKeepOnlyABoundedPredecessorTail() {
        assertEquals(79, NtkForwardHistoryPolicy.decodedPixelRetireBefore(87, 2, true))
        assertEquals(12, NtkForwardHistoryPolicy.decodedPixelRetireBefore(20, 2, true))
        assertEquals(0, NtkForwardHistoryPolicy.decodedPixelRetireBefore(87, 1, true))
        assertEquals(
            79,
            NtkForwardHistoryPolicy.decodedPixelRetireBefore(
                firstCurrentImageIndex = 87,
                currentImageOrdinal = 0,
                forwardReading = true,
                terminalShortEpisode = true,
            ),
        )
        assertEquals(0, NtkForwardHistoryPolicy.decodedPixelRetireBefore(87, 20, false))
    }

    @Test
    fun ordinaryShortPredecessorKeepsEveryPixelUntilTheBitmapBudgetNeedsSpace() {
        assertEquals(
            0,
            NtkForwardHistoryPolicy.decodedPixelRetireBefore(
                firstCurrentImageIndex = 16,
                currentImageOrdinal = 2,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 0,
            ),
        )
        assertEquals(
            20,
            NtkForwardHistoryPolicy.decodedPixelRetireBefore(
                firstCurrentImageIndex = 28,
                currentImageOrdinal = 2,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 3,
            ),
        )
    }

    @Test
    fun decodedTailCapDoesNotLimitPredecessorStructureOrDeepReverseAccess() {
        val firstCurrentImage = 21
        assertEquals(
            firstCurrentImage - NtkForwardHistoryPolicy.RETAINED_PREVIOUS_DECODED_TAIL_PAGES,
            NtkForwardHistoryPolicy.decodedPixelRetireBefore(
                firstCurrentImageIndex = firstCurrentImage,
                currentImageOrdinal = 2,
                forwardReading = true,
            ),
        )
        // Pixel retirement is deliberately independent from structure retirement.  The complete
        // predecessor starts at zero and remains addressable for an on-demand exact re-decode.
        assertEquals(
            0,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = firstCurrentImage,
                currentImageOrdinal = 2,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = 0,
            ),
        )
    }

    @Test
    fun terminalShortEpisodeProofRequiresExactCompleteCanonicalCoverage() {
        assertTrue(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 1,
                observedSourceIndexes = listOf(0, 0),
                activeSourceIndex = 0,
            ),
        )
        assertTrue(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 2,
                observedSourceIndexes = listOf(0, 0, 1, 1),
                activeSourceIndex = 1,
            ),
        )
        assertFalse(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 2,
                observedSourceIndexes = listOf(0),
                activeSourceIndex = 0,
            ),
        )
        assertFalse(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 2,
                observedSourceIndexes = listOf(0, 1),
                activeSourceIndex = 0,
            ),
        )
        assertFalse(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 2,
                observedSourceIndexes = listOf(0, 2),
                activeSourceIndex = 1,
            ),
        )
        assertFalse(
            NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                authoritativeSourceCount = 3,
                observedSourceIndexes = listOf(0, 1, 2),
                activeSourceIndex = 2,
            ),
        )
    }

    @Test
    fun oneImageEpisodeChainRemovesOnlyAAndRetainsTheWholeBPredecessor() {
        val pages = listOf(
            Marker("A", card = true),
            Marker("A"),
            Marker("B", card = true),
            Marker("B"),
            Marker("C", card = true),
            Marker("C"),
        )
        val retainedBStart = NtkForwardHistoryPolicy.retainedPreviousEpisodeStart(
            pages = pages,
            firstCurrentImageIndex = 5,
            isTransitionCard = Marker::card,
            sameEpisode = { first, second -> first.episode == second.episode },
        )
        val terminalC = NtkForwardHistoryPolicy.terminalShortEpisodeReached(
            authoritativeSourceCount = 1,
            observedSourceIndexes = listOf(0),
            activeSourceIndex = 0,
        )
        val removeCount = NtkForwardHistoryPolicy.removablePrefix(
            firstCurrentImageIndex = 5,
            currentImageOrdinal = 0,
            forwardReading = true,
            retainedPreviousEpisodeStartIndex = retainedBStart,
            terminalShortEpisode = terminalC,
        )

        assertEquals(2, retainedBStart)
        assertEquals(2, removeCount)
        assertEquals(
            listOf("B", "C"),
            pages.drop(removeCount).map(Marker::episode).distinct(),
        )
    }

    @Test
    fun twoImageEpisodeChainRemovesOnlyAAfterCReachesItsTerminalImage() {
        val pages = listOf(
            Marker("A", card = true),
            Marker("A"),
            Marker("A"),
            Marker("B", card = true),
            Marker("B"),
            Marker("B"),
            Marker("C", card = true),
            Marker("C"),
            Marker("C"),
        )
        val retainedBStart = NtkForwardHistoryPolicy.retainedPreviousEpisodeStart(
            pages = pages,
            firstCurrentImageIndex = 7,
            isTransitionCard = Marker::card,
            sameEpisode = { first, second -> first.episode == second.episode },
        )
        assertEquals(3, retainedBStart)
        assertEquals(
            0,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = 7,
                currentImageOrdinal = 0,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = retainedBStart,
                terminalShortEpisode = false,
            ),
        )
        assertEquals(
            3,
            NtkForwardHistoryPolicy.removablePrefix(
                firstCurrentImageIndex = 7,
                currentImageOrdinal = 1,
                forwardReading = true,
                retainedPreviousEpisodeStartIndex = retainedBStart,
                terminalShortEpisode = NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                    authoritativeSourceCount = 2,
                    observedSourceIndexes = listOf(0, 1),
                    activeSourceIndex = 1,
                ),
            ),
        )
    }

    @Test
    fun repeatedOneAndTwoImageChainsStayBoundedToCurrentAndOnePredecessor() {
        for (imagesPerEpisode in 1..2) {
            var pages = emptyList<Marker>()
            for (episode in 'A'..'H') {
                val name = episode.toString()
                pages = pages + Marker(name, card = true) +
                    List(imagesPerEpisode) { Marker(name) }
                val firstCurrentImage = pages.indexOfFirst { marker ->
                    marker.episode == name && !marker.card
                }
                val retainedPrevious = NtkForwardHistoryPolicy.retainedPreviousEpisodeStart(
                    pages = pages,
                    firstCurrentImageIndex = firstCurrentImage,
                    isTransitionCard = Marker::card,
                    sameEpisode = { first, second -> first.episode == second.episode },
                )
                val removeCount = NtkForwardHistoryPolicy.removablePrefix(
                    firstCurrentImageIndex = firstCurrentImage,
                    currentImageOrdinal = imagesPerEpisode - 1,
                    forwardReading = true,
                    retainedPreviousEpisodeStartIndex = retainedPrevious,
                    terminalShortEpisode =
                        NtkForwardHistoryPolicy.terminalShortEpisodeReached(
                            authoritativeSourceCount = imagesPerEpisode,
                            observedSourceIndexes = (0 until imagesPerEpisode).toList(),
                            activeSourceIndex = imagesPerEpisode - 1,
                        ),
                )
                pages = pages.drop(removeCount)
                assertTrue(pages.map(Marker::episode).distinct().size <= 2)
            }
            assertEquals(listOf("G", "H"), pages.map(Marker::episode).distinct())
        }
    }

    @Test
    fun enteringBRetainsTheWholeAIncludingItsBoundaryCard() {
        val pages = listOf(
            Marker("A", card = true),
            Marker("A"),
            Marker("A"),
            Marker("B", card = true),
            Marker("B"),
            Marker("B"),
        )
        assertEquals(
            0,
            NtkForwardHistoryPolicy.retainedPreviousEpisodeStart(
                pages = pages,
                firstCurrentImageIndex = 4,
                isTransitionCard = Marker::card,
                sameEpisode = { first, second -> first.episode == second.episode },
            ),
        )
    }

    @Test
    fun repeatedCurrentEpisodeEntryKeepsClaimEvenWhenFirstReleaseSetWasEmpty() {
        val ledger = NtkForwardPixelRetirementLedger()
        val currentC = retirementIdentity("/manhwa/2/c", 'c', revision = 3L)

        assertTrue(ledger.tryClaim(currentC))
        val firstDestructiveReleaseSet = emptyList<Any>()
        assertTrue(firstDestructiveReleaseSet.isEmpty())

        // Empty/protected is still a completed ownership turn. Reverse rehydrate must not reopen
        // the destructive predecessor clear for the same exact C manifest.
        assertFalse(ledger.tryClaim(currentC))
        assertEquals(setOf(currentC), ledger.snapshotForTest())
    }

    @Test
    fun consumedPathCleanupKeepsLedgerBoundedAcrossAToBToC() {
        val ledger = NtkForwardPixelRetirementLedger()
        val episodeA = retirementIdentity("/manhwa/2/a", 'a', revision = 1L)
        val episodeB = retirementIdentity("/manhwa/2/b", 'b', revision = 2L)
        val episodeC = retirementIdentity("/manhwa/2/c", 'c', revision = 3L)

        assertTrue(ledger.tryClaim(episodeA))
        assertTrue(ledger.tryClaim(episodeB))
        assertEquals(1, ledger.removeEpisodePaths(setOf("https://newtoki.test/manhwa/2/a")))
        assertTrue(ledger.tryClaim(episodeC))

        assertEquals(setOf(episodeB, episodeC), ledger.snapshotForTest())
        assertFalse(ledger.tryClaim(episodeC))
        assertTrue(
            ledger.tryClaim(
                retirementIdentity("/manhwa/2/c", 'd', revision = 4L, pageCount = 26),
            ),
        )
    }
}
