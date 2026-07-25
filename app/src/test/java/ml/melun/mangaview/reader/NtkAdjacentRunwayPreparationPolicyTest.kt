package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentRunwayPreparationPolicyTest {
    @Test
    fun joinsOnlyWhilePreparationIsWithinItsBoundedWindow() {
        assertTrue(NtkAdjacentRunwayPreparationPolicy.shouldJoin(1_000L, 1_001L))
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.shouldJoin(
                1_000L,
                1_000L + NtkAdjacentRunwayPreparationPolicy.MAX_JOIN_MS - 1L
            )
        )
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.shouldJoin(
                1_000L,
                1_000L + NtkAdjacentRunwayPreparationPolicy.MAX_JOIN_MS
            )
        )
    }

    @Test
    fun rejectsInvalidOrClockReversedPreparationTimes() {
        assertFalse(NtkAdjacentRunwayPreparationPolicy.shouldJoin(-1L, 1_000L))
        assertFalse(NtkAdjacentRunwayPreparationPolicy.shouldJoin(2_000L, 1_000L))
    }

    @Test
    fun authorizedAdjacentAnchorWaitsForItsBoundedForegroundRequest() {
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.shouldFastFailGeneratedAnchor(
                isAuthorizedAdjacentPath = true
            )
        )
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.shouldFastFailGeneratedAnchor(
                isAuthorizedAdjacentPath = false
            )
        )
    }

    @Test
    fun strictColdCurrentEpisodeOwnsResourcesUntilTheRealForwardTail() {
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.shouldStartAdjacentPrefetch(
                strictExactColdRolling = true,
                reason = "first_bitmap",
            )
        )
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.shouldStartAdjacentPrefetch(
                strictExactColdRolling = true,
                reason = "first_actual_frame",
            )
        )
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.shouldStartAdjacentPrefetch(
                strictExactColdRolling = true,
                reason = NtkAdjacentRunwayPreparationPolicy.FORWARD_TAIL_REASON,
            )
        )
    }

    @Test
    fun nonStrictReaderKeepsItsExistingEntryRunwayBehavior() {
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.shouldStartAdjacentPrefetch(
                strictExactColdRolling = false,
                reason = "first_bitmap",
            )
        )
    }

    @Test
    fun activeForegroundWaveCannotUndersupplyAtomicAdjacentRunway() {
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.activeAtomicRunwayRequestPages(
                availablePages = 12,
                atomicPublishPages = 3,
                activeReadyPages = 2,
                activeForegroundPages = 2,
            ) == 3
        )
        assertTrue(
            NtkAdjacentRunwayPreparationPolicy.activeAtomicRunwayRequestPages(
                availablePages = 2,
                atomicPublishPages = 3,
                activeReadyPages = 2,
                activeForegroundPages = 2,
            ) == 2
        )
    }

    @Test
    fun runwayFileFetchRetriesAreFiniteAndDeadlineBound() {
        assertTrue(NtkAdjacentRunwayPreparationPolicy.mayRetryFileFetch(1, 1_000L, 2_000L))
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.mayRetryFileFetch(
                NtkAdjacentRunwayPreparationPolicy.MAX_FILE_FETCH_ATTEMPTS,
                1_000L,
                2_000L
            )
        )
        assertFalse(
            NtkAdjacentRunwayPreparationPolicy.mayRetryFileFetch(
                1,
                1_000L,
                1_000L + NtkAdjacentRunwayPreparationPolicy.MAX_JOIN_MS
            )
        )
    }
}
