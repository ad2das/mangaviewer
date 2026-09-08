package ml.melun.mangaview.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineWholePreparationProtocolTest {
    @Test fun readinessUsesTheRecordedMilestoneEvenWhenPolledAfterTheDeadline() {
        var now = 1_000L
        val gate = WholePreparationGate(100L, { now }, timeoutNanos = 100L)

        assertEquals(WholePreparationState.READY, gate.observe(199L))
        assertEquals(WholePreparationState.READY, gate.observe(null))
    }

    @Test fun missingReadinessBecomesATerminalTimeoutAtTheExactDeadline() {
        var now = 199L
        val gate = WholePreparationGate(100L, { now }, timeoutNanos = 100L)
        assertEquals(WholePreparationState.WAITING, gate.observe(null))

        now = 200L
        assertEquals(WholePreparationState.TIMED_OUT, gate.observe(null))
        assertEquals(WholePreparationState.TIMED_OUT, gate.observe(150L))
    }

    @Test fun aMilestoneAfterTheDeadlineCannotTurnIntoReadiness() {
        val gate = WholePreparationGate(100L, { 201L }, timeoutNanos = 100L)
        assertEquals(WholePreparationState.TIMED_OUT, gate.observe(201L))
    }

    @Test fun preparationReadinessCannotDisableTheIndependentProtocolDeadline() {
        var now = 150L
        val readiness = WholePreparationGate(100L, { now }, timeoutNanos = 100L)
        val protocol = WholeProtocolDeadline(100L, { now }, boundNanos = 150L)
        assertEquals(WholePreparationState.READY, readiness.observe(150L))
        assertEquals(false, protocol.expired())

        now = 250L
        assertEquals(WholePreparationState.READY, readiness.observe(null))
        assertEquals(true, protocol.expired())
    }
}
