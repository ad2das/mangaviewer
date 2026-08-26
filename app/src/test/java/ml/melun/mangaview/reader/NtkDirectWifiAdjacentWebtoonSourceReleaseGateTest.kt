package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentWebtoonSourceReleaseGateTest {

    @Test
    fun directWifiAdjacentWebtoonRequiresEverySignalAndReleasesOnlyOnce() {
        val gate = NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
            predecessorAlreadyComplete = false,
            requireViewportActual = true,
            requireDrawableRunwayCommit = true,
        )

        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
        gate.markPredecessorComplete()
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
        gate.markViewportActual()
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = false))
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
        gate.markDrawableRunwayCommitted()
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = false))
        assertTrue(gate.tryClaimRelease(runwayBodiesComplete = true))
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))

        gate.markPredecessorComplete()
        gate.markViewportActual()
        gate.markDrawableRunwayCommitted()
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
    }

    @Test
    fun nonQualifiedAdjacentProfilesRetainTheExistingReleaseContract() {
        repeat(3) {
            val gate = NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
                predecessorAlreadyComplete = true,
                requireViewportActual = false,
                requireDrawableRunwayCommit = false,
            )

            assertFalse(gate.tryClaimRelease(runwayBodiesComplete = false))
            assertTrue(gate.tryClaimRelease(runwayBodiesComplete = true))
            assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
        }
    }

    @Test
    fun exactExternalDrawableProofSurvivesConsumedBodyRetirement() {
        val gate = NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
            predecessorAlreadyComplete = true,
            requireViewportActual = true,
            requireDrawableRunwayCommit = true,
        )

        gate.markViewportActual()
        gate.markDrawableRunwayCommitted()
        gate.markExternallyProvenBodyCohortComplete()

        assertTrue(gate.tryClaimRelease(runwayBodiesComplete = false))
        assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
    }

    @Test
    fun hostDirectWifiManhwaRequiresViewportWhileWebtoonRetainsDrawableGate() {
        assertTrue(
            NtkAdjacentBulkReleasePolicy.requiresActualViewportAndDrawableRunway(
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                episodePath = "/manhwa/2/next",
            ),
        )
        assertFalse(
            NtkAdjacentBulkReleasePolicy.requiresActualViewportAndDrawableRunway(
                hostGpuEmulatorRuntime = false,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                episodePath = "/manhwa/2/next",
            ),
        )
        assertTrue(
            NtkAdjacentBulkReleasePolicy.requiresActualViewportAndDrawableRunway(
                hostGpuEmulatorRuntime = false,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                episodePath = "/webtoon/7/next",
            ),
        )
        val hostManhwaGate = NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
            predecessorAlreadyComplete = true,
            requireViewportActual = true,
            requireDrawableRunwayCommit = false,
        )
        assertFalse(hostManhwaGate.tryClaimRelease(runwayBodiesComplete = true))
        hostManhwaGate.markViewportActual()
        assertTrue(hostManhwaGate.tryClaimRelease(runwayBodiesComplete = true))
        assertTrue(
            NtkAdjacentBulkReleasePolicy.releasedPhysicalLaneCount(
                proposedLaneCount = 5,
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetchReleased = true,
                episodePath = "/manhwa/2/next",
            ) == 2,
        )
        assertTrue(
            NtkAdjacentBulkReleasePolicy.releasedPhysicalLaneCount(
                proposedLaneCount = 5,
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetchReleased = false,
                episodePath = "/manhwa/2/next",
            ) == 5,
        )
    }

}
