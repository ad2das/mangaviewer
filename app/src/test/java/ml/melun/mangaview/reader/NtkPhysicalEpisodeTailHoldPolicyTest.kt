package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPhysicalEpisodeTailHoldPolicyTest {

    @Test
    fun acknowledgedEpisodeBoundaryDropsOnlyOldAbsoluteCarry() {
        assertEquals(
            10_480f,
            NtkPhysicalEpisodeTailHoldPolicy.targetAfterAcknowledgedEpisodeBoundary(
                requestedTarget = 27_000f,
                physicalGestureTarget = 10_480f,
                boundaryOffset = 10_000f,
                priorGestureAcknowledgementObservable = true,
                epsilonPx = 0.5f,
            ),
            0f,
        )
        assertEquals(
            27_000f,
            NtkPhysicalEpisodeTailHoldPolicy.targetAfterAcknowledgedEpisodeBoundary(
                requestedTarget = 27_000f,
                physicalGestureTarget = 10_480f,
                boundaryOffset = 10_000f,
                priorGestureAcknowledgementObservable = false,
                epsilonPx = 0.5f,
            ),
            0f,
        )
        assertEquals(
            27_000f,
            NtkPhysicalEpisodeTailHoldPolicy.targetAfterAcknowledgedEpisodeBoundary(
                requestedTarget = 27_000f,
                physicalGestureTarget = 9_900f,
                boundaryOffset = 10_000f,
                priorGestureAcknowledgementObservable = true,
                epsilonPx = 0.5f,
            ),
            0f,
        )
    }

    @Test
    fun sameGestureHoldSurvivesSuccessorStructureChanges() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.retainedSameGestureHoldLimit(
                direction = 1,
                forwardDirection = 1,
                requestedOffset = 1_250f,
                holdLimit = 1_000f,
                holdGestureRevision = 9L,
                currentGestureRevision = 9L,
                epsilonPx = 0.5f,
            ) == 1_000f,
        )
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.retainedSameGestureHoldLimit(
                direction = 1,
                forwardDirection = 1,
                requestedOffset = 1_250f,
                holdLimit = 1_000f,
                holdGestureRevision = 9L,
                currentGestureRevision = 10L,
                epsilonPx = 0.5f,
            ) == null,
        )
    }

    @Test
    fun adjacentAdoptionRequiresAnObservableAcknowledgementFromAPriorGesture() {
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isPriorGestureAcknowledgementObservable(
                acknowledgedGestureRevision = 7L,
                currentGestureRevision = 7L,
                presentedUptimeNanos = 1_000L,
                nowUptimeNanos = 2_000L,
                minimumDwellNanos = 100L,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isPriorGestureAcknowledgementObservable(
                acknowledgedGestureRevision = 7L,
                currentGestureRevision = 8L,
                presentedUptimeNanos = 1_950L,
                nowUptimeNanos = 2_000L,
                minimumDwellNanos = 100L,
            ),
        )
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isPriorGestureAcknowledgementObservable(
                acknowledgedGestureRevision = 7L,
                currentGestureRevision = 8L,
                presentedUptimeNanos = 1_000L,
                nowUptimeNanos = 2_000L,
                minimumDwellNanos = 100L,
            ),
        )
    }
    @Test
    fun cleanTailMustRemainPhysicallyObservableBeforeTheNextGestureCanCross() {
        val presented = 1_000_000_000L
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isAcknowledgementObservable(
                presentedUptimeNanos = presented,
                nowUptimeNanos = presented + 99_999_999L,
                minimumDwellNanos = 100_000_000L,
            ),
        )
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isAcknowledgementObservable(
                presentedUptimeNanos = presented,
                nowUptimeNanos = presented + 100_000_000L,
                minimumDwellNanos = 100_000_000L,
            ),
        )
    }

    @Test
    fun exactManifestTailIsABoundaryBeforeAdjacentStructureExists() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isBoundaryAfterExactTail(
                successorIsSameTailFragment = false,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isBoundaryAfterExactTail(
                successorIsSameTailFragment = true,
            ),
        )
    }

    @Test
    fun adjacentEpisodeAdoptionRequiresAnAcknowledgedExactPredecessorTail() {
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.mayAdoptAdjacentEpisode(
                predecessorIsExactTail = true,
                predecessorTailAcknowledged = false,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.mayAdoptAdjacentEpisode(
                predecessorIsExactTail = false,
                predecessorTailAcknowledged = true,
            ),
        )
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.mayAdoptAdjacentEpisode(
                predecessorIsExactTail = true,
                predecessorTailAcknowledged = true,
            ),
        )
    }

    @Test
    fun firstCrossingIsHeldUntilTheTailHasACompositorAcknowledgement() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing(
                direction = 1,
                forwardDirection = 1,
                currentOffset = 980f,
                requestedOffset = 1040f,
                boundaryOffset = 1000f,
                tailAcknowledged = false,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
    }

    @Test
    fun acknowledgementCannotReleaseTheGestureThatWasAlreadyCrossing() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing(
                direction = 1,
                forwardDirection = 1,
                currentOffset = 1000f,
                requestedOffset = 1060f,
                boundaryOffset = 1000f,
                tailAcknowledged = true,
                heldInCurrentGesture = true,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing(
                direction = 1,
                forwardDirection = 1,
                currentOffset = 1000f,
                requestedOffset = 1060f,
                boundaryOffset = 1000f,
                tailAcknowledged = true,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
    }

    @Test
    fun onlyANewGestureGetsTheAcknowledgedBoundaryFlingContinuation() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isNewAcknowledgedBoundaryCrossing(
                currentOffset = 1_000f,
                requestedOffset = 1_200f,
                boundaryOffset = 1_000f,
                tailAcknowledged = true,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isNewAcknowledgedBoundaryCrossing(
                currentOffset = 1_000f,
                requestedOffset = 1_200f,
                boundaryOffset = 1_000f,
                tailAcknowledged = true,
                heldInCurrentGesture = true,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isNewAcknowledgedBoundaryCrossing(
                currentOffset = 1_000f,
                requestedOffset = 1_200f,
                boundaryOffset = 1_000f,
                tailAcknowledged = false,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
    }

    @Test
    fun lateGeometryThatMovesTheBoundaryBehindTheViewportIsRepaired() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing(
                direction = 1,
                forwardDirection = 1,
                currentOffset = 1120f,
                requestedOffset = 1180f,
                boundaryOffset = 1000f,
                tailAcknowledged = false,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing(
                direction = 1,
                forwardDirection = 1,
                currentOffset = 1120f,
                requestedOffset = 1180f,
                boundaryOffset = 1000f,
                tailAcknowledged = true,
                heldInCurrentGesture = false,
                epsilonPx = 0.5f,
            ),
        )
    }

    @Test
    fun escapedTailRepairCanBypassOnlyTheExactHeldBoundaryBackstep() {
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isEscapedTailGeometryCorrection(
                currentOffset = 38268f,
                boundedOffset = 37095f,
                holdLimit = 37095f,
                epsilonPx = 0.5f,
            ),
        )
        assertTrue(
            NtkPhysicalEpisodeTailHoldPolicy.isEscapedTailGeometryCorrection(
                currentOffset = 58049f,
                boundedOffset = 54928f,
                holdLimit = 55709f,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isEscapedTailGeometryCorrection(
                currentOffset = 38268f,
                boundedOffset = 37095f,
                holdLimit = 36900f,
                epsilonPx = 0.5f,
            ),
        )
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isEscapedTailGeometryCorrection(
                currentOffset = 37095f,
                boundedOffset = 38268f,
                holdLimit = 37095f,
                epsilonPx = 0.5f,
            ),
        )
    }

    @Test
    fun onlyAnExactCleanSingleEpisodeTailCommitAcknowledgesTheBoundary() {
        val clean = NtkPhysicalEpisodeTailHoldPolicy.isCleanTailCommit(
            exactPresentation = true,
            singleEpisodeViewport = true,
            tailSourceIndex = 13,
            manifestPageCount = 14,
            pageBottomReached = true,
            drawablePx = 2340,
            missingPx = 0,
            placeholderPx = 0,
            visibleLoading = 0,
            visibleErrors = 0,
            visibleCards = 0,
            widthFillFailures = 0,
            lowResolutionItems = 0,
        )
        assertTrue(clean)
        assertFalse(
            NtkPhysicalEpisodeTailHoldPolicy.isCleanTailCommit(
                exactPresentation = true,
                singleEpisodeViewport = true,
                tailSourceIndex = 13,
                manifestPageCount = 14,
                pageBottomReached = true,
                drawablePx = 2340,
                missingPx = 0,
                placeholderPx = 0,
                visibleLoading = 0,
                visibleErrors = 0,
                visibleCards = 1,
                widthFillFailures = 0,
                lowResolutionItems = 0,
            ),
        )
    }
}
