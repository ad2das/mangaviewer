package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentWebtoonSourceReleaseGateTest {

    @Test
    fun directWifiAdjacentWebtoonRequiresEverySignalAndReleasesOnlyOnce() {
        val gate = NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
            predecessorAlreadyComplete = false,
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
                requireDrawableRunwayCommit = false,
            )

            gate.markViewportActual()
            assertFalse(gate.tryClaimRelease(runwayBodiesComplete = false))
            assertTrue(gate.tryClaimRelease(runwayBodiesComplete = true))
            assertFalse(gate.tryClaimRelease(runwayBodiesComplete = true))
        }
    }
}
