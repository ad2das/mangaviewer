package ml.melun.mangaview.reader

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicyTest {
    private val healthy = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.State()

    @Test
    fun healthySessionKeepsItsOrdinaryTarget() {
        assertEquals(
            24,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                24,
                healthy,
                eligible = true,
            ),
        )
        assertEquals(
            24,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                24,
                NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.State(
                    NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING,
                    10L,
                ),
                eligible = false,
            ),
        )
    }

    @Test
    fun firstSocketFailureDrainsToOneThenThreeFreshSuccessesRestoreOrdinaryH2Target() {
        val probing = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordFailure(
            healthy,
            SocketException("reset"),
            nextWorkId = 40L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING,
            probing.mode,
        )
        assertEquals(
            1,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                24,
                probing,
                eligible = true,
            ),
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.MINIMUM_RETRY_DELAY_MS,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.retryDelayMs(
                0L,
                probing,
                eligible = true,
            ),
        )

        val preTripSuccess =
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordSuccess(
                probing,
                successfulWorkId = 39L,
                eligible = true,
            )
        assertEquals(probing, preTripSuccess)

        val degraded = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordSuccess(
            probing,
            successfulWorkId = 40L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.DEGRADED,
            degraded.mode,
        )
        assertEquals(1, degraded.recoverySuccessCount)
        assertEquals(
            4,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                24,
                degraded,
                eligible = true,
            ),
        )

        val second = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordSuccess(
            degraded,
            successfulWorkId = 41L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.DEGRADED,
            second.mode,
        )
        assertEquals(2, second.recoverySuccessCount)

        val recovered = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordSuccess(
            second,
            successfulWorkId = 42L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.RECOVERED,
            recovered.mode,
        )
        assertEquals(
            24,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                24,
                recovered,
                eligible = true,
            ),
        )
    }

    @Test
    fun repeatedPressureReturnsDegradedSessionToProbe() {
        val recovered = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.State(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.RECOVERED,
        )
        val probing = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordFailure(
            recovered,
            SocketTimeoutException("headers"),
            nextWorkId = 90L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING,
            probing.mode,
        )
        assertEquals(90L, probing.minimumRecoveryWorkId)
    }

    @Test
    fun lowLevelFenceTripPersistsOneIdempotentProbeEpoch() {
        val probing = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.observeFenceTrip(
            healthy,
            fenceTripped = true,
            nextWorkId = 70L,
            eligible = true,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING,
            probing.mode,
        )
        assertEquals(70L, probing.minimumRecoveryWorkId)
        assertEquals(
            probing,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.observeFenceTrip(
                probing,
                fenceTripped = true,
                nextWorkId = 99L,
                eligible = true,
            ),
        )
        assertEquals(
            healthy,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.observeFenceTrip(
                healthy,
                fenceTripped = true,
                nextWorkId = 70L,
                eligible = false,
            ),
        )
        assertEquals(
            healthy,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.observeFenceTrip(
                healthy,
                fenceTripped = false,
                nextWorkId = 70L,
                eligible = true,
            ),
        )
    }

    @Test
    fun onlySocketPressurePageCanOwnTheSingleDirectH1Proof() {
        val probing = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING
        assertTrue(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                pressureObserved = true,
                observationEligible = true,
                fenceRequiresDirectH1 = true,
                ownerExists = false,
                mode = probing,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                false, true, true, false, probing,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                true, false, true, false, probing,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                true, true, false, false, probing,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                true, true, true, true, probing,
            ),
        )
    }

    @Test
    fun pageLocalFenceRecoversPlainCanceledIOExceptionWithoutBroadeningGenericIo() {
        val canceled = IOException("Canceled")
        assertTrue(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.pressureObserved(
                failure = canceled,
                observationEligible = true,
                fenceRequiresDirectH1 = true,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.pressureObserved(
                failure = canceled,
                observationEligible = true,
                fenceRequiresDirectH1 = false,
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.pressureObserved(
                failure = canceled,
                observationEligible = false,
                fenceRequiresDirectH1 = true,
            ),
        )

        val probing = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordPressure(
            state = healthy,
            pressureObserved = true,
            nextWorkId = 44L,
        )
        assertEquals(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING,
            probing.mode,
        )
        assertEquals(
            healthy,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordPressure(
                state = healthy,
                pressureObserved = false,
                nextWorkId = 44L,
            ),
        )
    }

    @Test
    fun proofReusesFailedLaneFallsBackToAnotherFreeLaneAndNeverExceedsSix() {
        assertEquals(
            5,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.selectLane(
                preferredLaneIndex = 5,
                activeLanes = booleanArrayOf(true, true, true, true, true, false, false),
                adoptionLanes = BooleanArray(7),
                healthyActiveCeiling = 6,
            ),
        )
        assertEquals(
            6,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.selectLane(
                preferredLaneIndex = 5,
                activeLanes = booleanArrayOf(true, true, true, true, true, false, false),
                adoptionLanes = booleanArrayOf(false, false, false, false, false, true, false),
                healthyActiveCeiling = 6,
            ),
        )
        assertEquals(
            -1,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.selectLane(
                preferredLaneIndex = 5,
                activeLanes = booleanArrayOf(true, true, true, true, true, true, false),
                adoptionLanes = BooleanArray(7),
                healthyActiveCeiling = 6,
            ),
        )
    }

    @Test
    fun nestedSocketPressureIsClassifiedButOtherIoAndIneligibleWorkAreNot() {
        assertTrue(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.isSocketPressureFailure(
                IOException("wrapped", SocketException("reset")),
            ),
        )
        assertFalse(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.isSocketPressureFailure(
                IOException("ordinary io"),
            ),
        )
        assertTrue(
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.isSocketPressureFailure(
                StreamResetException("reset"),
            ),
        )
        assertEquals(
            healthy,
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordFailure(
                healthy,
                SocketException("reset"),
                nextWorkId = 2L,
                eligible = false,
            ),
        )
    }

    @Test
    fun sharedFenceTripsExactlyOnceAcrossConcurrentCalls() {
        val fence = NtkHostGpuEmulatorCurrentWebtoonRecoveryFence()
        assertFalse(fence.isTripped())
        assertTrue(fence.trip(7))
        assertTrue(fence.isTripped())
        assertTrue(fence.requiresDirectH1(7))
        assertFalse(fence.requiresDirectH1(8))
        assertFalse(fence.trip(8))
        assertTrue(fence.requiresDirectH1(8))
        assertTrue(fence.markDirectH1RecoveryLogged())
        assertFalse(fence.markDirectH1RecoveryLogged())
    }

    private class StreamResetException(message: String) : IOException(message)
}
