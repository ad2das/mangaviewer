package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerDeltaLedgerTest {
    @Test
    fun moveAndFinalUpDeltasTelescopeWithoutLoss() {
        val ledger = PointerDeltaLedger()
        ledger.begin(1_000f)
        ledger.append(900f)
        ledger.append(725f)
        ledger.append(650f)

        assertEquals(350.0, ledger.pendingPixels, 0.0)
        assertEquals(listOf(350.0), ledger.drain())
        assertEquals(0.0, ledger.pendingPixels, 0.0)
    }

    @Test
    fun consumingFramesAndPointerRebasePreserveOnlyRealInput() {
        val ledger = PointerDeltaLedger()
        ledger.begin(800f)
        ledger.append(700f)
        assertEquals(listOf(100.0), ledger.drain())
        ledger.rebase(400f)
        ledger.append(350f)

        assertEquals(listOf(50.0), ledger.drain())
    }

    @Test
    fun reversalAtAClampedBoundaryRetainsTheFullOrderedInput() {
        val ledger = PointerDeltaLedger()
        ledger.begin(500f)
        ledger.append(600f)
        ledger.append(500f)
        var offset = 0.0
        ledger.drain().forEach { offset = (offset + it).coerceIn(0.0, 1000.0) }
        assertEquals(100.0, offset, 0.0)
    }

    @Test
    fun completeSampleIsAvailableOnTheFirstFrameWithoutPacing() {
        val ledger = PointerDeltaLedger()
        ledger.begin(500f)
        ledger.append(100f)
        assertEquals(listOf(400.0), ledger.drain())
        assertEquals(emptyList<Double>(), ledger.drain())
    }
}
