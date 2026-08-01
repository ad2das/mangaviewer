package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkWifiWebtoonAdaptiveLaneStateTest {

    @Test
    fun balancedFreshFastEofEvidencePromotesEveryStage() {
        val state = NtkWifiWebtoonAdaptiveLaneState()
        var startedAt = 1_000_000_000L

        repeat(9) { slot ->
            state.recordSuccess(
                pageIndex = 9 + slot,
                attemptOrdinal = 1,
                physicalStartedAtNanos = startedAt,
                physicalCompletedAtNanos = startedAt + 1_000_000_000L,
            )
            startedAt += 1_100_000_000L
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.SECOND_TARGET, state.target)

        repeat(9) { slot ->
            state.recordSuccess(
                pageIndex = 27 + slot,
                attemptOrdinal = 1,
                physicalStartedAtNanos = startedAt,
                physicalCompletedAtNanos = startedAt + 1_000_000_000L,
            )
            startedAt += 1_100_000_000L
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.THIRD_TARGET, state.target)

        repeat(9) { slot ->
            assertNull(
                state.recordSuccess(
                    pageIndex = 36 + slot,
                    attemptOrdinal = 1,
                    physicalStartedAtNanos = startedAt,
                    physicalCompletedAtNanos = startedAt + 1_000_000_000L,
                ),
            )
            startedAt += 1_100_000_000L
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.THIRD_TARGET, state.target)
        assertFalse(state.frozen)
    }

    @Test
    fun oneHealthySessionCannotPromoteTheGlobalWave() {
        val state = NtkWifiWebtoonAdaptiveLaneState()
        var startedAt = 1_000_000_000L

        repeat(20) { ordinal ->
            assertNull(
                state.recordSuccess(
                    pageIndex = 2 + ordinal * 9,
                    attemptOrdinal = 1,
                    physicalStartedAtNanos = startedAt,
                    physicalCompletedAtNanos = startedAt + 500_000_000L,
                )
            )
            startedAt += 600_000_000L
        }

        assertEquals(NtkWifiWebtoonAdaptiveLaneState.INITIAL_TARGET, state.target)
        assertFalse(state.frozen)
    }

    @Test
    fun previousStageInflightSuccessCannotProveTheWiderStage() {
        val state = NtkWifiWebtoonAdaptiveLaneState()
        var startedAt = 1_000_000_000L
        var promotion: NtkWifiWebtoonAdaptiveLaneState.Transition? = null
        repeat(9) { slot ->
            promotion = state.recordSuccess(
                pageIndex = 9 + slot,
                attemptOrdinal = 1,
                physicalStartedAtNanos = startedAt,
                physicalCompletedAtNanos = startedAt + 1_000_000_000L,
            ) ?: promotion
            startedAt += 1_100_000_000L
        }
        val stageBoundary = checkNotNull(promotion).let {
            assertEquals(NtkWifiWebtoonAdaptiveLaneState.SECOND_TARGET, it.newTarget)
            startedAt - 100_000_000L
        }

        repeat(9) { slot ->
            assertNull(
                state.recordSuccess(
                    pageIndex = 45 + slot,
                    attemptOrdinal = 1,
                    physicalStartedAtNanos = stageBoundary - 1L,
                    physicalCompletedAtNanos = stageBoundary + 500_000_000L,
                )
            )
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.SECOND_TARGET, state.target)

        startedAt = stageBoundary + 600_000_000L
        repeat(9) { slot ->
            state.recordSuccess(
                pageIndex = 54 + slot,
                attemptOrdinal = 1,
                physicalStartedAtNanos = startedAt,
                physicalCompletedAtNanos = startedAt + 500_000_000L,
            )
            startedAt += 600_000_000L
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.THIRD_TARGET, state.target)
    }

    @Test
    fun unhealthyEvidenceReducesOnlyFutureTargetToEighteen() {
        val slow = NtkWifiWebtoonAdaptiveLaneState()
        val slowTransition = checkNotNull(
            slow.recordSuccess(
                pageIndex = 2,
                attemptOrdinal = 1,
                physicalStartedAtNanos = 1_000_000_000L,
                physicalCompletedAtNanos =
                    1_000_000_000L +
                        NtkWifiWebtoonAdaptiveLaneState.FAST_COMPLETION_LIMIT_MS * 1_000_000L + 1L,
            )
        )
        assertEquals("slow_success", slowTransition.reason)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.INITIAL_TARGET, slowTransition.oldTarget)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, slowTransition.newTarget)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, slow.target)
        assertTrue(slow.frozen)

        val failed = promotedToThirtySix()
        val failureTransition = checkNotNull(failed.recordFailure(20, attemptOrdinal = 1))
        assertEquals("physical_failure", failureTransition.reason)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.SECOND_TARGET, failureTransition.oldTarget)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, failureTransition.newTarget)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, failed.target)
        assertTrue(failed.frozen)

        val retried = NtkWifiWebtoonAdaptiveLaneState()
        val retryTransition = checkNotNull(
            retried.recordSuccess(
                pageIndex = 11,
                attemptOrdinal = 2,
                physicalStartedAtNanos = 1_000_000_000L,
                physicalCompletedAtNanos = 1_500_000_000L,
            )
        )
        assertEquals("retry_success", retryTransition.reason)
        assertTrue(retried.frozen)
        assertNull(retried.recordFailure(29, attemptOrdinal = 2))
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, retried.target)
    }

    @Test
    fun frozenStateCannotPromoteAgainAfterFutureTargetReduction() {
        val state = promotedToThirtySix()
        checkNotNull(state.recordFailure(pageIndex = 20, attemptOrdinal = 1))

        var startedAt = 20_000_000_000L
        repeat(18) { ordinal ->
            assertNull(
                state.recordSuccess(
                    pageIndex = 27 + ordinal,
                    attemptOrdinal = 1,
                    physicalStartedAtNanos = startedAt,
                    physicalCompletedAtNanos = startedAt + 500_000_000L,
                )
            )
            startedAt += 600_000_000L
        }

        assertTrue(state.frozen)
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.UNHEALTHY_TARGET, state.target)
    }

    @Test
    fun eligibilityIsCurrentDirectWifiWebtoonOnly() {
        assertTrue(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/webtoon/work/episode",
                wifiQuicBulkTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/webtoon/work/episode",
                wifiQuicBulkTransport = true,
                cellularResilientTransport = true,
                adjacentPrefetch = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/webtoon/work/episode",
                wifiQuicBulkTransport = false,
                cellularResilientTransport = false,
                adjacentPrefetch = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/manhwa/work/episode",
                wifiQuicBulkTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/webtoon/work/adjacent",
                wifiQuicBulkTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = "/webtoon/work/previous",
                wifiQuicBulkTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = false,
                currentForegroundEpisode = false,
            )
        )
    }

    @Test
    fun pageOrdinalMapsToTheRealNineSessionRing() {
        assertEquals(2, NtkWifiWebtoonAdaptiveLaneState.sessionSlot(2))
        assertEquals(2, NtkWifiWebtoonAdaptiveLaneState.sessionSlot(11))
        assertEquals(2, NtkWifiWebtoonAdaptiveLaneState.sessionSlot(20))
        assertEquals(8, NtkWifiWebtoonAdaptiveLaneState.sessionSlot(17))
    }

    private fun promotedToThirtySix(): NtkWifiWebtoonAdaptiveLaneState {
        val state = NtkWifiWebtoonAdaptiveLaneState()
        var startedAt = 1_000_000_000L
        repeat(9) { slot ->
            state.recordSuccess(
                pageIndex = 9 + slot,
                attemptOrdinal = 1,
                physicalStartedAtNanos = startedAt,
                physicalCompletedAtNanos = startedAt + 500_000_000L,
            )
            startedAt += 600_000_000L
        }
        assertEquals(NtkWifiWebtoonAdaptiveLaneState.SECOND_TARGET, state.target)
        return state
    }
}
