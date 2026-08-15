package ml.melun.mangaview.ntkack

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkAckBrowserEngineClockTest {
    @Test
    fun registrationOffsetUsesRequestResponseMidpoint() {
        assertEquals(
            0L,
            ntkAckServerTimeOffsetAtRegistrationMidpoint(
                serverNowEpochMs = 10_100L,
                registrationStartedAtEpochMs = 10_000L,
                registrationReceivedAtEpochMs = 10_200L,
            ),
        )
        assertEquals(
            500L,
            ntkAckServerTimeOffsetAtRegistrationMidpoint(
                serverNowEpochMs = 10_600L,
                registrationStartedAtEpochMs = 10_000L,
                registrationReceivedAtEpochMs = 10_200L,
            ),
        )
    }

    @Test
    fun registrationOffsetHandlesZeroOrRegressedWallClockConservatively() {
        assertEquals(
            250L,
            ntkAckServerTimeOffsetAtRegistrationMidpoint(
                serverNowEpochMs = 20_250L,
                registrationStartedAtEpochMs = 20_000L,
                registrationReceivedAtEpochMs = 20_000L,
            ),
        )
        assertEquals(
            250L,
            ntkAckServerTimeOffsetAtRegistrationMidpoint(
                serverNowEpochMs = 20_250L,
                registrationStartedAtEpochMs = 20_000L,
                registrationReceivedAtEpochMs = 19_900L,
            ),
        )
    }

    @Test
    fun networkPrerequisiteWaitUsesTheOwningFlightDeadlineInsteadOfAFixedTimeout() {
        assertEquals(
            19_250L,
            ntkAckNetworkPrerequisiteWaitBudgetMs(
                deadlineElapsedRealtimeNanos = 30_000_000_000L,
                nowElapsedRealtimeNanos = 10_000_000_000L,
            ),
        )
        assertEquals(
            4_250L,
            ntkAckNetworkPrerequisiteWaitBudgetMs(
                deadlineElapsedRealtimeNanos = 15_000_000_000L,
                nowElapsedRealtimeNanos = 10_000_000_000L,
            ),
        )
        assertEquals(
            250L,
            ntkAckNetworkPrerequisiteWaitBudgetMs(
                deadlineElapsedRealtimeNanos = 10_500_000_000L,
                nowElapsedRealtimeNanos = 10_000_000_000L,
            ),
        )
    }
}
