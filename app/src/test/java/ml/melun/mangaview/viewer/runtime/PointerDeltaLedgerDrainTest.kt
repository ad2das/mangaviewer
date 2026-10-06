package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerDeltaLedgerDrainTest {
    @Test fun drainEachVisitsMergedSegmentsInOrderAndClears() {
        val ledger = PointerDeltaLedger()
        ledger.begin(1_000f)
        ledger.append(900f)
        ledger.append(1_050f)
        ledger.append(950f)
        val visited = mutableListOf<Double>()

        ledger.drainEach { visited += it }

        assertEquals(listOf(100.0, -150.0, 100.0), visited)
        assertFalse(ledger.hasPending)
        assertEquals(0.0, ledger.pendingPixels, 0.0)
    }

    @Test fun drainEachMatchesTheCollectingFormExactly() {
        val streaming = PointerDeltaLedger()
        val collecting = PointerDeltaLedger()
        streaming.begin(1_000f)
        collecting.begin(1_000f)
        listOf(980f, 940f, 930f, 1_050f, 1_200f, 1_150f, 1_100f).forEach { y ->
            assertEquals(collecting.append(y), streaming.append(y), 0.0)
        }
        val visited = mutableListOf<Double>()

        streaming.drainEach { visited += it }

        assertEquals(collecting.drain(), visited)
        assertEquals(0.0, collecting.pendingPixels, 0.0)
        assertEquals(0.0, streaming.pendingPixels, 0.0)
    }

    @Test fun growthBeyondTheInitialCapacityPreservesOrderAndPendingPixels() {
        val ledger = PointerDeltaLedger()
        ledger.begin(10f)
        val expected = mutableListOf<Double>()
        repeat(50) {
            ledger.append(0f)
            expected += 10.0
            ledger.append(10f)
            expected += -10.0
        }

        assertEquals(expected.sum(), ledger.pendingPixels, 0.0)
        val visited = mutableListOf<Double>()
        ledger.drainEach { visited += it }
        assertEquals(expected, visited)
    }

    @Test fun anEmptyDrainVisitsNothing() {
        val ledger = PointerDeltaLedger()
        var visits = 0

        ledger.drainEach { visits++ }

        assertEquals(0, visits)
        assertFalse(ledger.hasPending)
    }

    @Test fun reEntrantAppendDuringDrainIsDeliveredByTheNextDrainExactlyOnce() {
        val ledger = PointerDeltaLedger()
        ledger.begin(100f)
        ledger.append(90f)
        ledger.append(80f)

        val first = mutableListOf<Double>()
        ledger.drainEach { delta ->
            first += delta
            if (delta == 20.0) ledger.append(70f)
        }

        assertEquals("only the pre-drain segment is visited", listOf(20.0), first)
        assertEquals("the re-entrant append waits for the next drain", 10.0, ledger.pendingPixels, 0.0)
        val second = mutableListOf<Double>()
        ledger.drainEach { second += it }
        assertEquals(listOf(10.0), second)
        assertFalse(ledger.hasPending)
        var third = 0
        ledger.drainEach { third++ }
        assertEquals("no segment is ever delivered twice", 0, third)
    }

    @Test fun aThrowDuringTheDrainDropsTheVisitedSegmentsWithoutRedelivery() {
        val ledger = PointerDeltaLedger()
        ledger.begin(100f)
        ledger.append(90f)
        ledger.append(95f)

        val visited = mutableListOf<Double>()
        val failure = runCatching {
            ledger.drainEach { delta ->
                visited += delta
                if (delta == 10.0) throw IllegalStateException("visitor failed")
            }
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf(10.0), visited)
        assertFalse("the buffers swapped before visiting, so the ledger is already clear",
            ledger.hasPending)
        assertEquals(0.0, ledger.pendingPixels, 0.0)
        var redelivered = 0
        ledger.drainEach { redelivered++ }
        assertEquals("visited segments are never re-delivered", 0, redelivered)

        ledger.append(90f)
        val next = mutableListOf<Double>()
        ledger.drainEach { next += it }
        assertEquals(listOf(5.0), next)
    }
}
