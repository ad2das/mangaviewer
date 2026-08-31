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
        assertEquals(350.0, ledger.drain(), 0.0)
        assertEquals(0.0, ledger.pendingPixels, 0.0)
    }

    @Test
    fun consumingFramesAndPointerRebasePreserveOnlyRealInput() {
        val ledger = PointerDeltaLedger()
        ledger.begin(800f)
        ledger.append(700f)
        ledger.consume(60.0)
        ledger.rebase(400f)
        ledger.append(350f)

        assertEquals(90.0, ledger.drain(), 0.0)
    }
}
