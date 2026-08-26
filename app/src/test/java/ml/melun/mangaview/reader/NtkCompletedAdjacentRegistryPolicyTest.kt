package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkCompletedAdjacentRegistryPolicyTest {
    @Test
    fun liveMatchingRegistryRemainsAuthoritative() {
        assertFalse(
            NtkCompletedAdjacentRegistryPolicy.isUnusable(
                authorityReady = false,
                snapshotPresent = true,
                snapshotMatchesDiscovery = true,
                snapshotTerminalClosing = false,
            ),
        )
    }

    @Test
    fun absentMismatchedOrTerminalRegistryCanReleaseACompletedFlight() {
        assertTrue(
            NtkCompletedAdjacentRegistryPolicy.isUnusable(false, false, false, false),
        )
        assertTrue(
            NtkCompletedAdjacentRegistryPolicy.isUnusable(false, true, false, false),
        )
        assertTrue(
            NtkCompletedAdjacentRegistryPolicy.isUnusable(false, true, true, true),
        )
    }

    @Test
    fun currentAuthorityAlwaysPreventsDetach() {
        assertFalse(
            NtkCompletedAdjacentRegistryPolicy.isUnusable(true, false, false, true),
        )
    }
}
