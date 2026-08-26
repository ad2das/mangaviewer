package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictRollingControlMailboxTest {
    @Test
    fun publishedWindowIngressSuppressesPixelOnlyRepeatsButNotStructureChanges() {
        val gate = PublishedWindowIngressGate<Any>()
        val firstStructure = Any()
        val replacementStructure = Any()

        assertTrue(gate.reserve(firstStructure, 3, 5, 4, 3, 4, true, 1))
        assertFalse(gate.reserve(firstStructure, 3, 5, 4, 3, 4, true, 1))
        assertTrue(gate.reserve(replacementStructure, 3, 5, 4, 3, 4, true, 1))
        assertTrue(gate.reserve(replacementStructure, 3, 5, 4, 3, 5, true, 1))
        assertTrue(gate.reserve(replacementStructure, 3, 5, 4, 3, 5, false, 1))

        gate.clear()
        assertTrue(gate.reserve(replacementStructure, 3, 5, 4, 3, 5, false, 1))
    }

    @Test
    fun physicalProofIsStickyWhileWindowTrafficRemainsLatestOnly() {
        val mailbox = StrictRollingControlMailbox()
        assertTrue(mailbox.offerWindow(0, 1, 0, true))
        assertFalse(mailbox.offerPhysicalDraw(0, 0, 1))
        assertFalse(mailbox.offerWindow(8, 10, 9, true, directionHint = -1))
        assertFalse(mailbox.offerPhysicalDraw(1, 1, 1))

        val events = mailbox.pollBatch()!!.events
        assertEquals(3, events.size)
        assertTrue(events[0] is StrictRollingControlMailbox.PhysicalDrawEvent)
        assertTrue(events[1] is StrictRollingControlMailbox.WindowEvent)
        assertTrue(events[2] is StrictRollingControlMailbox.PhysicalDrawEvent)
        val latestWindow = events[1] as StrictRollingControlMailbox.WindowEvent
        assertEquals(8, latestWindow.first)
        assertEquals(10, latestWindow.last)
        assertEquals(-1, latestWindow.directionHint)
        assertTrue(events.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
        assertTrue(mailbox.finishDrainIfEmpty())
    }

    @Test
    fun offerDuringDrainCannotLoseItsWakeup() {
        val mailbox = StrictRollingControlMailbox()
        assertTrue(mailbox.offerWindow(0, 1, 0, false))
        assertEquals(1, mailbox.pollBatch()!!.events.size)

        // A producer arriving after poll sees the existing drainer and must remain pending.
        assertFalse(mailbox.offerPhysicalDraw(0, 0, 1))
        assertFalse(mailbox.finishDrainIfEmpty())
        assertEquals(1, mailbox.pollBatch()!!.events.size)
        assertTrue(mailbox.finishDrainIfEmpty())
    }

    @Test
    fun sameEpochDifferentSourceDemandIsRejectedFailClosed() {
        val gate = NtkSourceDemandEpochGate()
        val episode = NtkEpisodeToken(7L)
        val first = demand(episode, 3L, intArrayOf(0), intArrayOf(1))
        val conflict = demand(episode, 3L, intArrayOf(1), intArrayOf(0))
        val newer = demand(episode, 4L, intArrayOf(1), intArrayOf(0))

        assertEquals(NtkSourceDemandOfferDecision.ACCEPT, gate.offer(episode, first))
        assertEquals(NtkSourceDemandOfferDecision.IDEMPOTENT, gate.offer(episode, first))
        assertEquals(NtkSourceDemandOfferDecision.CONFLICT, gate.offer(episode, conflict))
        assertEquals(NtkSourceDemandOfferDecision.ACCEPT, gate.offer(episode, newer))
        assertEquals(NtkSourceDemandOfferDecision.STALE, gate.offer(episode, first))
    }

    @Test
    fun physicalAdmissionLowersOnlyForForegroundReverseDemand() {
        val episode = NtkEpisodeToken(9L)
        val forward = NtkSourceDemandSnapshot(
            authority = episode.value,
            demandEpoch = 1L,
            hardPages = intArrayOf(25, 26),
            softPages = (27 until 77).toList().toIntArray(),
            backgroundPages = (0 until 25).toList().toIntArray(),
        )
        val reverse = NtkSourceDemandSnapshot(
            authority = episode.value,
            demandEpoch = 2L,
            hardPages = intArrayOf(3, 4),
            softPages = (0 until 3).toList().toIntArray() +
                (5 until 77).toList().toIntArray(),
            backgroundPages = IntArray(0),
        )

        assertEquals(
            25,
            NtkRollingPhysicalAdmissionPolicy.admittedForwardPages(25, 77, forward).minOrNull(),
        )
        assertEquals(
            0,
            NtkRollingPhysicalAdmissionPolicy.admittedForwardPages(25, 77, reverse).minOrNull(),
        )
    }

    @Test
    fun physicalAdmissionIgnoresBackgroundPagesOutsideTheViewportRunway() {
        val episode = NtkEpisodeToken(10L)
        val bounded = NtkSourceDemandSnapshot(
            authority = episode.value,
            demandEpoch = 3L,
            hardPages = intArrayOf(8, 9),
            softPages = intArrayOf(10, 11, 12, 7),
            backgroundPages = ((0 until 7) + (13 until 80)).toIntArray(),
        )

        assertEquals(
            setOf(7, 8, 9, 10, 11, 12),
            NtkRollingPhysicalAdmissionPolicy.admittedForwardPages(0, 80, bounded),
        )
    }

    @Test
    fun idleCompletionAdmissionCannotBeShrunkByLaterViewportDemand() {
        val completedAdmission = (0 until 12).toSet()
        val laterViewportDemand = (3 until 10).toSet()

        assertEquals(
            completedAdmission,
            NtkRollingPhysicalAdmissionPolicy.reconcileDemandedPages(
                previousAdmission = completedAdmission,
                demandedAdmission = laterViewportDemand,
                preserveExistingAdmission = true,
            ),
        )
        assertEquals(
            laterViewportDemand,
            NtkRollingPhysicalAdmissionPolicy.reconcileDemandedPages(
                previousAdmission = completedAdmission,
                demandedAdmission = laterViewportDemand,
                preserveExistingAdmission = false,
            ),
        )
    }

    private fun demand(
        episode: NtkEpisodeToken,
        epoch: Long,
        hard: IntArray,
        soft: IntArray
    ) = NtkSourceDemandSnapshot(
        authority = episode.value,
        demandEpoch = epoch,
        hardPages = hard,
        softPages = soft,
        backgroundPages = intArrayOf(2)
    )
}
