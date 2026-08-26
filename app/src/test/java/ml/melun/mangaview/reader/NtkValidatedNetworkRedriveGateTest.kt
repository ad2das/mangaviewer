package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkValidatedNetworkRedriveGateTest {
    @Test
    fun initiallyValidatedNetworkDoesNotManufactureARecovery() {
        val gate = NtkValidatedNetworkRedriveGate(1_000L)
        gate.initialize(validated = true)

        assertNull(gate.observe(validated = true, nowMs = 10L))
        assertNull(gate.pendingTicket())
    }

    @Test
    fun oneUnvalidatedToValidatedEdgeOwnsOneTicket() {
        val gate = NtkValidatedNetworkRedriveGate(1_000L)
        gate.initialize(validated = false)

        val ticket = gate.observe(validated = true, nowMs = 50L)!!
        assertEquals(1L, ticket.epoch)
        assertEquals(1_050L, ticket.deadlineAtMs)
        assertEquals(ticket, gate.pendingTicket())
        assertNull(gate.observe(validated = true, nowMs = 60L))
        assertTrue(gate.complete(ticket))
        assertNull(gate.pendingTicket())
        assertFalse(gate.complete(ticket))

        gate.observe(validated = false, nowMs = 70L)
        assertEquals(2L, gate.observe(validated = true, nowMs = 80L)!!.epoch)
    }

    @Test
    fun aNewLossInvalidatesTheOldTicketAndCreatesANewerEpoch() {
        val gate = NtkValidatedNetworkRedriveGate(1_000L)
        gate.initialize(validated = false)
        val first = gate.observe(validated = true, nowMs = 10L)!!

        assertNull(gate.observe(validated = false, nowMs = 20L))
        assertNull(gate.pendingTicket())
        val second = gate.observe(validated = true, nowMs = 30L)!!

        assertEquals(first.epoch + 1L, second.epoch)
        assertFalse(gate.complete(first))
        assertTrue(gate.complete(second))
    }

    @Test
    fun cancelMakesAQueuedCallbackStale() {
        val gate = NtkValidatedNetworkRedriveGate(1_000L)
        gate.initialize(validated = false)
        val ticket = gate.observe(validated = true, nowMs = 10L)!!

        gate.cancel()

        assertNull(gate.pendingTicket())
        assertFalse(gate.complete(ticket))

        gate.initialize(validated = false)
        assertEquals(ticket.epoch + 1L, gate.observe(validated = true, nowMs = 20L)!!.epoch)
    }

    @Test
    fun objectiveOwnerProgressCanRenewOnlyTheCurrentEpoch() {
        val gate = NtkValidatedNetworkRedriveGate(1_000L)
        gate.initialize(validated = false)
        val ticket = gate.observe(validated = true, nowMs = 10L)!!

        val renewed = gate.renew(ticket, nowMs = 900L)!!
        assertEquals(ticket.epoch, renewed.epoch)
        assertEquals(1_900L, renewed.deadlineAtMs)
        gate.observe(validated = false, nowMs = 950L)
        assertNull(gate.renew(renewed, nowMs = 1_000L))
    }

    @Test
    fun renewalNeverExtendsPastTheImmutableHardDeadline() {
        val gate = NtkValidatedNetworkRedriveGate(
            budgetMs = 1_000L,
            hardBudgetMs = 3_000L,
        )
        gate.initialize(validated = false)
        val ticket = gate.observe(validated = true, nowMs = 10L)!!

        assertEquals(3_010L, ticket.hardDeadlineAtMs)
        val renewed = gate.renew(ticket, nowMs = 2_900L)!!
        assertEquals(3_010L, renewed.deadlineAtMs)
        assertEquals(ticket.hardDeadlineAtMs, renewed.hardDeadlineAtMs)
    }

    @Test
    fun homeTimeShiftsBothDeadlinesWithoutManufacturingANewEpoch() {
        val gate = NtkValidatedNetworkRedriveGate(
            budgetMs = 1_000L,
            hardBudgetMs = 3_000L,
        )
        gate.initialize(validated = false)
        val ticket = gate.observe(validated = true, nowMs = 100L)!!

        val resumed = gate.resumeAfterPause(
            ticket,
            pausedAtMs = 400L,
            resumedAtMs = 10_400L,
        )!!

        assertEquals(ticket.epoch, resumed.epoch)
        assertEquals(11_100L, resumed.deadlineAtMs)
        assertEquals(13_100L, resumed.hardDeadlineAtMs)
        assertEquals(resumed, gate.pendingTicket())
    }
}
