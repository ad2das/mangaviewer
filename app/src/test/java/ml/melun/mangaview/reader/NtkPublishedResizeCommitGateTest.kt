package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPublishedResizeCommitGateTest {
    @Test
    fun staleRunnableCannotClaimOrFinishReplacementOwner() {
        val gate = NtkPublishedResizeCommitGate<Any>()
        val retiredOwner = Any()
        val replacementOwner = Any()

        gate.activate(retiredOwner)
        val retiredReservation = gate.reserve(retiredOwner)!!
        gate.clear()
        gate.activate(replacementOwner)
        val replacementReservation = gate.reserve(replacementOwner)!!
        assertNotEquals(retiredReservation.token, replacementReservation.token)

        assertFalse(gate.begin(retiredOwner, retiredReservation))
        gate.finish(retiredOwner, retiredReservation)
        assertTrue(gate.begin(replacementOwner, replacementReservation))
        gate.finish(replacementOwner, replacementReservation)
    }

    @Test
    fun lateRetiredOwnerCannotReserveAfterReplacementActivation() {
        val gate = NtkPublishedResizeCommitGate<Any>()
        val retiredOwner = Any()
        val replacementOwner = Any()

        gate.activate(replacementOwner)
        assertNull(gate.reserve(retiredOwner))
        assertNotNull(gate.reserve(replacementOwner))
    }

    @Test
    fun scheduledAndRunningOwnerRemainSingleFlightUntilExactFinish() {
        val gate = NtkPublishedResizeCommitGate<Any>()
        val owner = Any()
        gate.activate(owner)
        val reservation = gate.reserve(owner)!!

        assertNull(gate.reserve(owner))
        assertTrue(gate.begin(owner, reservation))
        assertFalse(gate.begin(owner, reservation))
        assertNull(gate.reserve(owner))

        gate.finish(owner, reservation)
        assertNull(gate.reserve(owner))
    }
}
