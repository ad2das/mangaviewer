package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NtkForegroundNetworkLeavePolicyTest {
    @Test
    fun enteredFlightOwnsTheOnePhysicalLeave() {
        assertEquals(
            NtkForegroundNetworkLeaveAction.LEAVE,
            NtkForegroundNetworkLeavePolicy.action(
                foregroundNetworkEntered = true,
                leaveStarted = false,
                leaveCompleted = false,
            ),
        )
    }

    @Test
    fun concurrentDetachWaitsForTheExistingPhysicalLeave() {
        assertEquals(
            NtkForegroundNetworkLeaveAction.AWAIT_EXISTING_LEAVE,
            NtkForegroundNetworkLeavePolicy.action(
                foregroundNetworkEntered = false,
                leaveStarted = true,
                leaveCompleted = false,
            ),
        )
    }

    @Test
    fun neverEnteredAndAlreadyBalancedFlightsNeedNoLeave() {
        assertEquals(
            NtkForegroundNetworkLeaveAction.NONE,
            NtkForegroundNetworkLeavePolicy.action(false, false, false),
        )
        assertEquals(
            NtkForegroundNetworkLeaveAction.NONE,
            NtkForegroundNetworkLeavePolicy.action(false, true, true),
        )
    }

    @Test
    fun enteredAndAlreadyClaimedIsAnInvalidState() {
        try {
            NtkForegroundNetworkLeavePolicy.action(true, true, false)
            fail("Expected inconsistent leave state to fail closed")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
