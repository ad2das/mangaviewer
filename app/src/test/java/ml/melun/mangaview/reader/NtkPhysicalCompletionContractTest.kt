package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkPhysicalCompletionContractTest {
    @Test
    fun rejectsMissingTerminalIdentity() {
        assertEquals(
            "terminal-input-absent",
            NtkPhysicalCompletionContract.violation(0L, 0L, 0L)
        )
    }

    @Test
    fun requiresQueueSuccessBeforePhysicalDelivery() {
        assertEquals(
            "terminal-input-not-submitted",
            NtkPhysicalCompletionContract.violation(300L, 299L, 300L)
        )
    }

    @Test
    fun requiresOrderedRetireLatchCallbackDelivery() {
        assertEquals(
            "terminal-retire-latch-callback-not-delivered",
            NtkPhysicalCompletionContract.violation(300L, 300L, 299L)
        )
    }

    @Test
    fun acceptsOnlyBothNaturalWatermarksAtTerminal() {
        assertNull(NtkPhysicalCompletionContract.violation(300L, 300L, 300L))
        assertNull(NtkPhysicalCompletionContract.violation(300L, 301L, 301L))
    }
}
