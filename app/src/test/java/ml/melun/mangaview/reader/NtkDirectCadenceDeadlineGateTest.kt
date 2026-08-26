package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectCadenceDeadlineGateTest {
    @Test
    fun tenThousandFramesKeepOneScheduledOwnerAndLatestDeadline() {
        val gate = NtkDirectCadenceDeadlineGate()
        var scheduleRequests = 0
        repeat(10_000) { frame ->
            if (gate.arm(frame.toLong(), 18L) != 0L) scheduleRequests++
        }

        val snapshot = gate.snapshot()
        assertEquals(1, scheduleRequests)
        assertTrue(snapshot.scheduled)
        assertEquals(10_000L, snapshot.arms)
        assertEquals(1L, snapshot.scheduleRequests)
        assertEquals(96L, snapshot.deadlineMs)
    }

    @Test
    fun earlyWakeupRepostsUntilTheLatestFrameDeadline() {
        val gate = NtkDirectCadenceDeadlineGate()
        val reservation = gate.arm(nowMs = 100L, delayMs = 18L)
        assertEquals(0L, gate.arm(nowMs = 110L, delayMs = 18L))

        assertEquals(
            NtkDirectCadenceDeadlineGate.DrainDecision.Repost(8L),
            gate.drain(reservation, nowMs = 120L),
        )
        val due = gate.drain(reservation, nowMs = 128L)
        assertTrue(due is NtkDirectCadenceDeadlineGate.DrainDecision.Due)
        assertFalse(gate.snapshot().scheduled)
    }

    @Test
    fun clearAndStalePostFailureCannotCancelSuccessor() {
        val gate = NtkDirectCadenceDeadlineGate()
        val first = gate.arm(nowMs = 0L, delayMs = 18L)
        gate.clear()
        val second = gate.arm(nowMs = 20L, delayMs = 18L)
        assertNotEquals(first, second)

        gate.onPostRejected(first)
        assertTrue(gate.snapshot().scheduled)
        assertEquals(NtkDirectCadenceDeadlineGate.DrainDecision.Stale, gate.drain(first, 40L))
        assertTrue(gate.drain(second, 40L) is NtkDirectCadenceDeadlineGate.DrainDecision.Due)
    }

    @Test
    fun repeatedRearmCannotStarveTheExistingRecoveryOwner() {
        val gate = NtkDirectCadenceDeadlineGate()
        val reservation = gate.arm(nowMs = 0L, delayMs = 35L)
        repeat(1_000) { gate.arm(nowMs = it.toLong(), delayMs = 35L) }

        assertTrue(
            gate.drain(reservation, nowMs = 96L) is
                NtkDirectCadenceDeadlineGate.DrainDecision.Due,
        )
    }

    @Test
    fun readerFramePathDoesNotRemoveTheCadenceWatchdogFromMessageQueue() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
        ).readText()
        assertFalse(source.contains("removeCallbacks(directCadenceWatchdog"))
        assertTrue(source.contains("directCadenceDeadlineGate.arm("))
        assertTrue(source.contains("DrainDecision.Repost"))
    }
}
