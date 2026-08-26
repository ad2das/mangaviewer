package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPhysicalMoveMailboxTest {
    private fun sample(
        generation: Long,
        y: Float,
        eventTime: Long,
        received: Long,
        events: Int = 1,
        history: Int = 0,
    ) = NtkQueuedPhysicalMove(
        generation = generation,
        y = y,
        eventTimeMs = eventTime,
        oldestEventTimeMs = eventTime - history,
        newestEventTimeMs = eventTime,
        receivedOldestNanos = received,
        receivedNewestNanos = received,
        eventCount = events,
        historySampleCount = history,
    )

    @Test
    fun latestAbsolutePositionCoalescesWithoutLosingTimingEvidence() {
        val mailbox = NtkPhysicalMoveMailbox()
        val generation = mailbox.beginGesture()

        assertTrue(mailbox.offer(sample(generation, 600f, 100L, 1_000L, history = 2)))
        assertFalse(mailbox.offer(sample(generation, 420f, 116L, 2_000L)))

        val drained = checkNotNull(mailbox.take())
        assertEquals(420f, drained.y)
        assertEquals(116L, drained.eventTimeMs)
        assertEquals(98L, drained.oldestEventTimeMs)
        assertEquals(116L, drained.newestEventTimeMs)
        assertEquals(1_000L, drained.receivedOldestNanos)
        assertEquals(2_000L, drained.receivedNewestNanos)
        assertEquals(2, drained.eventCount)
        assertEquals(2, drained.historySampleCount)
        assertFalse(mailbox.finishDrainAndReserveNext())
    }

    @Test
    fun terminalEventInvalidatesMoveAlreadyWaitingForProducer() {
        val mailbox = NtkPhysicalMoveMailbox()
        val generation = mailbox.beginGesture()
        assertTrue(mailbox.offer(sample(generation, 500f, 100L, 1_000L)))

        mailbox.endGesture()

        assertNull(mailbox.take())
        assertFalse(mailbox.isCurrent(generation))
        assertFalse(mailbox.finishDrainAndReserveNext())
    }

    @Test
    fun offerDuringDrainReservesExactlyOneSuccessorPost() {
        val mailbox = NtkPhysicalMoveMailbox()
        val generation = mailbox.beginGesture()
        assertTrue(mailbox.offer(sample(generation, 600f, 100L, 1_000L)))
        assertEquals(600f, checkNotNull(mailbox.take()).y)

        assertFalse(mailbox.offer(sample(generation, 400f, 116L, 2_000L)))
        assertTrue(mailbox.finishDrainAndReserveNext())
        assertEquals(400f, checkNotNull(mailbox.take()).y)
        assertFalse(mailbox.finishDrainAndReserveNext())
    }
}
