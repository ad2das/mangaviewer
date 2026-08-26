package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkForwardAdjacentValidatedRecoveryPolicyTest {
    @Test
    fun offViewportExactOwnerRetainsOnlyTheNewestUnconsumedEpoch() {
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.latch(
                validatedEpoch = 7L,
                lastConsumedEpoch = 5L,
                pendingEpoch = 0L,
            ) == 7L,
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.latch(
                validatedEpoch = 6L,
                lastConsumedEpoch = 5L,
                pendingEpoch = 7L,
            ) == 7L,
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.latch(
                validatedEpoch = 7L,
                lastConsumedEpoch = 7L,
                pendingEpoch = 0L,
            ) == 0L,
        )
    }

    @Test
    fun retainedEpochIsConsumableOnceAndOnlyAtItsExactBoundary() {
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch = 9L,
                lastConsumedEpoch = 8L,
                exactBoundary = true,
            ) == 9L,
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch = 9L,
                lastConsumedEpoch = 8L,
                exactBoundary = false,
            ) == 0L,
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch = 9L,
                lastConsumedEpoch = 9L,
                exactBoundary = true,
            ) == 0L,
        )
    }

    @Test
    fun futureBToCWaitsForReportedBInsteadOfPrefetchedCAndThenConsumesOnce() {
        val pendingEpoch = 12L
        val beforeBoundary = NtkAdjacentValidatedEpochCatchUpPolicy.exactBoundaryMatches(
            readingPath = "/webtoon/work/a",
            predecessorPath = "/webtoon/work/b",
            targetPath = "/webtoon/work/c",
        )
        assertFalse(beforeBoundary)
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch,
                lastConsumedEpoch = 11L,
                exactBoundary = beforeBoundary,
            ) == 0L,
        )

        val atBoundary = NtkAdjacentValidatedEpochCatchUpPolicy.exactBoundaryMatches(
            readingPath = "/WEBTOON/WORK/B",
            predecessorPath = "/webtoon/work/b",
            targetPath = "/webtoon/work/c",
        )
        assertTrue(atBoundary)
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch,
                lastConsumedEpoch = 11L,
                exactBoundary = atBoundary,
            ) == pendingEpoch,
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.consume(
                pendingEpoch,
                lastConsumedEpoch = pendingEpoch,
                exactBoundary = atBoundary,
            ) == 0L,
        )
    }

    @Test
    fun activeCatchUpPreservesCountersOnlyWhileItsReplacementFlightExists() {
        assertFalse(
            NtkAdjacentValidatedEpochCatchUpPolicy.shouldResetRequestGeneration(
                terminalCatchUp = false,
                activeCatchUp = true,
                replacementInFlight = true,
            ),
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.shouldResetRequestGeneration(
                terminalCatchUp = false,
                activeCatchUp = true,
                replacementInFlight = false,
            ),
        )
        assertTrue(
            NtkAdjacentValidatedEpochCatchUpPolicy.shouldResetRequestGeneration(
                terminalCatchUp = true,
                activeCatchUp = false,
                replacementInFlight = true,
            ),
        )
    }

    @Test
    fun aCurrentUncommittedClaimCanRearmOncePerValidatedEpoch() {
        assertTrue(
            NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm(
                validatedEpoch = 7L,
                lastValidatedRedriveEpoch = 6L,
                boundaryMatches = true,
                structureCommitted = false,
            ),
        )
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm(
                validatedEpoch = 7L,
                lastValidatedRedriveEpoch = 7L,
                boundaryMatches = true,
                structureCommitted = false,
            ),
        )
    }

    @Test
    fun staleBoundaryAndCommittedStructureNeverRearmTheInitialManifestOwner() {
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm(
                validatedEpoch = 8L,
                lastValidatedRedriveEpoch = 7L,
                boundaryMatches = false,
                structureCommitted = false,
            ),
        )
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm(
                validatedEpoch = 8L,
                lastValidatedRedriveEpoch = 7L,
                boundaryMatches = true,
                structureCommitted = true,
            ),
        )
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm(
                validatedEpoch = 0L,
                lastValidatedRedriveEpoch = 0L,
                boundaryMatches = true,
                structureCommitted = false,
            ),
        )
    }

    @Test
    fun ordinaryFallbackCannotMintAnotherBudgetForTheSameRetiredTarget() {
        assertTrue(
            NtkForwardAdjacentValidatedRecoveryPolicy.blocksOrdinarySameTargetReselection(
                retiredTargetPath = "/webtoon/work/next",
                candidateTargetPath = "/WEBTOON/WORK/NEXT",
            ),
        )
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.blocksOrdinarySameTargetReselection(
                retiredTargetPath = "/webtoon/work/next",
                candidateTargetPath = "/webtoon/work/newer-next",
            ),
        )
    }

    @Test
    fun blankPreStructureEvidencePreservesARegainUntilTheFirstExactBoundaryExists() {
        assertTrue(
            NtkForwardAdjacentValidatedRecoveryPolicy.observedBoundaryMatches(
                observedPath = "",
                predecessorPath = "/webtoon/work/current",
                targetPath = "/webtoon/work/next",
            ),
        )
        assertTrue(
            NtkForwardAdjacentValidatedRecoveryPolicy.observedBoundaryMatches(
                observedPath = "/WEBTOON/WORK/CURRENT",
                predecessorPath = "/webtoon/work/current",
                targetPath = "/webtoon/work/next",
            ),
        )
        assertFalse(
            NtkForwardAdjacentValidatedRecoveryPolicy.observedBoundaryMatches(
                observedPath = "/webtoon/other/chapter",
                predecessorPath = "/webtoon/work/current",
                targetPath = "/webtoon/work/next",
            ),
        )
    }
}
