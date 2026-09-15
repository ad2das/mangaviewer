package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileUploadPacerTest {
    private var now = 1_000L
    private val waits = mutableListOf<Long>()

    private fun pacer(bytesPerWindow: Long = 100, windowNanos: Long = 1_000) = TileUploadPacer(
        bytesPerWindow = bytesPerWindow,
        windowNanos = windowNanos,
        nanoTime = { now },
        sleep = { wait -> waits += wait; now += wait },
    )

    @Test fun firstSlotIsImmediateAndFollowingBandsSpreadAcrossWindows() = runTest {
        val pacer = pacer()
        pacer.acquire(100)
        assertTrue(waits.isEmpty())
        pacer.acquire(100)
        assertEquals(listOf(1_000L), waits)
        pacer.acquire(50)
        assertEquals(listOf(1_000L, 1_000L), waits)
        assertEquals(3_000L, now)
        pacer.acquire(100)
        assertEquals(listOf(1_000L, 1_000L, 500L), waits)
        assertEquals(3_500L, now)
    }

    @Test fun idleGapDoesNotBankBurstCredit() = runTest {
        val pacer = pacer()
        pacer.acquire(100)
        now += 10_000L
        pacer.acquire(100)
        assertTrue(waits.isEmpty())
        pacer.acquire(100)
        assertEquals(listOf(1_000L), waits)
    }

    @Test fun oversizedBandGetsItsOwnStretchedSlot() = runTest {
        val pacer = pacer()
        pacer.acquire(400)
        pacer.acquire(100)
        assertEquals(listOf(4_000L), waits)
    }
}
