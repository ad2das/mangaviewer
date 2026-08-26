package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictSourceActorCallbackGateTest {
    @Test
    fun callbackQueuedBeforeFinalSnapshotDefersAdmissionClose() {
        val gate = NtkStrictSourceActorCallbackGate()

        assertTrue(gate.admit {})
        assertTrue(gate.admit {})
        assertEquals(1, gate.remainingExcludingCurrent(currentCallbackDepth = 1))
        assertFalse(gate.closeAdmissionsIfDrained(currentCallbackDepth = 1))

        assertEquals(1, gate.finish())
        assertTrue(gate.closeAdmissionsIfDrained(currentCallbackDepth = 1))
        assertEquals(0, gate.remainingExcludingCurrent(currentCallbackDepth = 1))
        assertEquals(0, gate.finish())
    }

    @Test
    fun callbackCannotEnterAfterCloseBarrierSnapshot() {
        val gate = NtkStrictSourceActorCallbackGate()

        assertTrue(gate.admit {})
        assertTrue(gate.closeAdmissionsIfDrained(currentCallbackDepth = 1))
        assertFalse(gate.admit {})
        assertEquals(0, gate.remainingExcludingCurrent(currentCallbackDepth = 1))
        assertEquals(0, gate.finish())
    }

    @Test
    fun closeFlagPublishesOnlyAfterItsActorCallbackIsReserved() {
        val gate = NtkStrictSourceActorCallbackGate()
        var closePublished = false
        var awaitPublication: (() -> Unit)? = null

        assertTrue(gate.admitClose(
            publishCloseRequested = { closePublished = true },
            submit = { await ->
                assertFalse(closePublished)
                awaitPublication = await
            },
        ))

        assertTrue(closePublished)
        checkNotNull(awaitPublication).invoke()
        assertEquals(1, gate.remainingExcludingCurrent(currentCallbackDepth = 0))
        assertEquals(0, gate.finish())
    }
}
