package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentAdmissionPolicyTest {
    @Test
    fun staleForwardTailCannotSkipNewlyPublishedPages() {
        assertTrue(
            NtkAdjacentAdmissionPolicy.shouldRejectStaleForwardTail(
                direction = 1,
                requestedAnchor = 13,
                normalizedAnchor = 15,
                viewportAnchor = 13,
            )
        )
        assertFalse(
            NtkAdjacentAdmissionPolicy.shouldRejectStaleForwardTail(
                direction = 1,
                requestedAnchor = 13,
                normalizedAnchor = 15,
                viewportAnchor = 15,
            )
        )
        assertFalse(
            NtkAdjacentAdmissionPolicy.shouldRejectStaleForwardTail(
                direction = -1,
                requestedAnchor = 13,
                normalizedAnchor = 15,
                viewportAnchor = 13,
            )
        )
        assertFalse(
            NtkAdjacentAdmissionPolicy.shouldRejectStaleForwardTail(
                direction = 1,
                requestedAnchor = 15,
                normalizedAnchor = 15,
                viewportAnchor = 13,
            )
        )
    }

    @Test
    fun currentEpisodeHasNoPredecessorGateOrAdjacentRunway() {
        assertAdmission(
            adjacentOwned = false,
            wifi = true,
            cellularResilient = false,
            predecessorGate = false,
            directWifiRunway = false,
        )
    }

    @Test
    fun directWifiAdjacentGetsUniversalGateAndFourPageTransportPolicy() {
        assertAdmission(true, true, false, true, true)
    }

    @Test
    fun cellularAdjacentKeepsUniversalGateWithoutWifiTransportPolicy() {
        assertAdmission(true, false, true, true, false)
    }

    @Test
    fun sniNonWifiAdjacentKeepsUniversalGateWithoutWifiTransportPolicy() {
        assertAdmission(true, false, false, true, false)
    }

    @Test
    fun wifiCellularResilientTransitionKeepsUniversalGateWithoutWifiTransportPolicy() {
        assertAdmission(true, true, true, true, false)
    }

    private fun assertAdmission(
        adjacentOwned: Boolean,
        wifi: Boolean,
        cellularResilient: Boolean,
        predecessorGate: Boolean,
        directWifiRunway: Boolean,
    ) {
        assertEquals(
            NtkAdjacentAdmission(predecessorGate, directWifiRunway),
            NtkAdjacentAdmissionPolicy.decide(adjacentOwned, wifi, cellularResilient),
        )
    }
}
