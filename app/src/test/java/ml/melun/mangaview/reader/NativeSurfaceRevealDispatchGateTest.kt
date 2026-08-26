package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSurfaceRevealDispatchGateTest {
    @Test
    fun tenThousandNativeFramesReserveOneMainCallbackAndKeepLatestIdentity() {
        val gate = NativeSurfaceRevealDispatchGate()
        var posts = 0
        repeat(10_000) { index ->
            if (gate.offer(request(index + 1L), alreadyRevealed = false).shouldPost) posts++
        }

        assertEquals(1, posts)
        assertEquals(request(10_000L), gate.take())
        assertNull(gate.take())
        val snapshot = gate.snapshot()
        assertEquals(10_000L, snapshot.offers)
        assertEquals(1L, snapshot.scheduleRequests)
        assertEquals(9_999L, snapshot.coalesced)
        assertFalse(snapshot.scheduled)
        assertFalse(snapshot.pending)
    }

    @Test
    fun alreadyRevealedFramesCreateNoMainThreadWork() {
        val gate = NativeSurfaceRevealDispatchGate()
        repeat(10_000) {
            assertFalse(gate.offer(request(7L), alreadyRevealed = true).shouldPost)
        }
        assertNull(gate.take())
        assertEquals(10_000L, gate.snapshot().alreadyRevealedSkipped)
        assertEquals(0L, gate.snapshot().scheduleRequests)
    }

    @Test
    fun offerRacingTakeOwnsExactlyOneSuccessorWakeup() {
        val gate = NativeSurfaceRevealDispatchGate()
        assertTrue(gate.offer(request(1L), false).shouldPost)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val take = executor.submit<NativeSurfaceRevealDispatchGate.Request?> {
                start.await()
                gate.take()
            }
            val offer = executor.submit<NativeSurfaceRevealDispatchGate.OfferResult> {
                start.await()
                while (gate.snapshot().scheduled) Thread.yield()
                gate.offer(request(2L), false)
            }
            start.countDown()
            assertEquals(request(1L), take.get(5, TimeUnit.SECONDS))
            assertTrue(offer.get(5, TimeUnit.SECONDS).shouldPost)
            assertEquals(request(2L), gate.take())
            assertEquals(2L, gate.snapshot().scheduleRequests)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun clearAndRejectedPostReleaseOnlyTheirReservation() {
        val gate = NativeSurfaceRevealDispatchGate()
        val first = gate.offer(request(1L), false)
        gate.onPostRejected(first.reservation + 1L)
        assertTrue(gate.snapshot().scheduled)
        gate.onPostRejected(first.reservation)
        assertFalse(gate.snapshot().scheduled)

        val second = gate.offer(request(2L), false)
        assertTrue(second.shouldPost)
        gate.clear()
        assertNull(gate.take())
        assertFalse(gate.snapshot().pending)
        assertFalse(gate.snapshot().scheduled)
    }

    private fun request(epoch: Long) = NativeSurfaceRevealDispatchGate.Request(
        attachEpoch = epoch,
        structureEpoch = epoch * 10L,
    )
}
