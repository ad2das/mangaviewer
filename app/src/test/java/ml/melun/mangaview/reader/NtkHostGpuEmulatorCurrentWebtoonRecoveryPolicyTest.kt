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
