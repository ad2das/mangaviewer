package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkCompositorRevealDispatchGateTest {
    @Test
    fun staleRunnableCannotConsumeReplacementReservation() {
        val gate = NtkCompositorRevealDispatchGate()
        val oldIdentity = identity(surfaceEpoch = 10L)
        val replacement = identity(surfaceEpoch = 11L)

        gate.activate(oldIdentity)
        val stale = gate.offer(oldIdentity)
        assertTrue(stale.shouldPost)

        gate.clear()
        gate.activate(replacement)
        val fresh = gate.offer(replacement)
        assertTrue(fresh.shouldPost)
        assertFalse(stale.reservation == fresh.reservation)

        assertNull(gate.take(stale.reservation))
        assertEquals(replacement, gate.take(fresh.reservation))
    }

    @Test
    fun retiringFrameCannotOverwriteActivatedReplacementIdentity() {
        val gate = NtkCompositorRevealDispatchGate()
        val oldIdentity = identity(surfaceEpoch = 20L)
        val replacement = identity(surfaceEpoch = 21L)

        gate.activate(replacement)
        val stale = gate.offer(oldIdentity)
        assertFalse(stale.shouldPost)

        val fresh = gate.offer(replacement)
        assertTrue(fresh.shouldPost)
        assertEquals(replacement, gate.take(fresh.reservation))
    }

    @Test
    fun repeatedFreshFramesKeepOneOwnerAndLatestExactIdentity() {
        val gate = NtkCompositorRevealDispatchGate()
        val replacement = identity(surfaceEpoch = 30L)
        gate.activate(replacement)

        val first = gate.offer(replacement)
        val second = gate.offer(replacement)

        assertTrue(first.shouldPost)
        assertFalse(second.shouldPost)
        assertEquals(first.reservation, second.reservation)
        assertEquals(replacement, gate.take(first.reservation))
        assertNull(gate.take(first.reservation))
    }

    private fun identity(surfaceEpoch: Long) = NtkCompositorRevealDispatchGate.Identity(
        engineGeneration = 7L,
        attachGeneration = surfaceEpoch + 100L,
        surfaceEpoch = surfaceEpoch,
    )
}
