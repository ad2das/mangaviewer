package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.WorkPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reservation accounting for [UploadCapacityReservations]: ordering, promotion and settlement. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UploadCapacityReservationsTest {
    private val allocationLimit = 100L
    private val headroom = 30L

    @Test
    fun foregroundReservationIsServedBeforeAQueuedBackgroundWaiter() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(60) // 40 bytes free, but a background request is capped at 70 total.
        val foreground = async { ledger.reserve({ WorkPriority.FOCUS }, 50) }
        val background = async { ledger.reserve({ WorkPriority.NEXT_IMAGE }, 60) }
        runCurrent()
        assertEquals(2, ledger.waitingCount())
        ledger.synchronize(40) // Fits the foreground request (40 + 50 <= 100), not the background one.
        runCurrent()
        assertTrue(foreground.isCompleted)
        assertFalse(background.isCompleted)
        foreground.await().release()
        assertEquals(0, ledger.reservedBytes())
        ledger.synchronize(0)
        runCurrent()
        assertTrue(background.isCompleted)
        background.await().release()
        assertEquals(0, ledger.reservedBytes())
        assertEquals(0, ledger.waitingCount())
    }

    @Test
    fun promotedWaiterIsGrantedAheadOfAnOlderStillBackgroundWaiter() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(40) // Background cap 70: both 40-byte requests must queue.
        var promoted = WorkPriority.NEXT_IMAGE
        val older = async { ledger.reserve({ WorkPriority.NEXT_IMAGE }, 40) }
        val newer = async { ledger.reserve({ promoted }, 40) }
        runCurrent()
        assertEquals(2, ledger.waitingCount())
        promoted = WorkPriority.VISIBLE // The fling reached the newer tile.
        ledger.synchronize(40) // The next grant pass reads the live priority.
        runCurrent()
        assertTrue(newer.isCompleted)
        assertFalse(older.isCompleted)
        newer.await().release()
        ledger.synchronize(0)
        runCurrent()
        assertTrue(older.isCompleted)
        older.await().release()
        assertEquals(0, ledger.reservedBytes())
        assertEquals(0, ledger.waitingCount())
    }

    @Test
    fun backgroundWaiterStaysBlockedByHeadroomWhileForegroundFits() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(55)
        val background = async { ledger.reserve({ WorkPriority.NEXT_IMAGE }, 30) }
        runCurrent()
        assertEquals(1, ledger.waitingCount())
        val foreground = ledger.reserve({ WorkPriority.VISIBLE }, 40) // 55 + 40 <= 100: allowed past the headroom.
        assertEquals(40L, ledger.reservedBytes())
        assertFalse(background.isCompleted)
        foreground.release()
        ledger.synchronize(40) // 40 + 30 <= 70: the background request now fits inside its cap.
        runCurrent()
        assertTrue(background.isCompleted)
        background.await().release()
        assertEquals(0, ledger.reservedBytes())
        assertEquals(0, ledger.waitingCount())
    }

    @Test
    fun synchronizeWakesOnlyWaitersThatFit() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(95)
        val small = async { ledger.reserve({ WorkPriority.FOCUS }, 10) }
        val large = async { ledger.reserve({ WorkPriority.FOCUS }, 60) }
        runCurrent()
        assertEquals(2, ledger.waitingCount())
        ledger.synchronize(60) // 60 + 10 fits; 60 + 60 does not, so only the small waiter wakes.
        runCurrent()
        assertTrue(small.isCompleted)
        assertFalse(large.isCompleted)
        small.await().release()
        ledger.synchronize(0)
        runCurrent()
        assertTrue(large.isCompleted)
        large.await().release()
        assertEquals(0, ledger.reservedBytes())
        assertEquals(0, ledger.waitingCount())
    }

    @Test
    fun cancelledWaiterLeavesNoReservation() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(95)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { ledger.reserve({ WorkPriority.FOCUS }, 50) }
        assertEquals(1, ledger.waitingCount())
        waiter.cancelAndJoin()
        assertEquals(0, ledger.waitingCount())
        assertEquals(0, ledger.reservedBytes())
        ledger.synchronize(0)
        val reused = ledger.reserve({ WorkPriority.FOCUS }, 100)
        reused.release()
        assertEquals(0, ledger.reservedBytes())
    }

    @Test
    fun cancellationRacingTheGrantStillReturnsTheReservedBytes() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(90)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { ledger.reserve({ WorkPriority.FOCUS }, 50) }
        assertEquals(1, ledger.waitingCount())
        ledger.synchronize(50) // 50 + 50 <= 100: granted under the lock, the coroutine has not resumed yet.
        assertEquals(0, ledger.waitingCount())
        assertEquals(50L, ledger.reservedBytes())
        waiter.cancel() // The resume loses to cancellation; the grant must still be returned.
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertEquals(0, ledger.reservedBytes())
        assertEquals(0, ledger.waitingCount())
        ledger.synchronize(0)
        val reused = ledger.reserve({ WorkPriority.FOCUS }, 100)
        reused.release()
    }

    @Test
    fun commitAndReleaseSettleAReservationExactlyOnce() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        val committed = ledger.reserve({ WorkPriority.FOCUS }, 40)
        assertEquals(40L, ledger.reservedBytes())
        committed.commit()
        committed.commit() // A double settle must not return the bytes twice.
        assertEquals(0L, ledger.reservedBytes())
        val released = ledger.reserve({ WorkPriority.VISIBLE }, 60)
        assertEquals(60L, ledger.reservedBytes())
        released.release()
        released.commit()
        assertEquals(0L, ledger.reservedBytes())
        val reused = ledger.reserve({ WorkPriority.FOCUS }, 100)
        assertEquals(100L, ledger.reservedBytes())
        reused.release()
        assertEquals(0L, ledger.reservedBytes())
    }

    @Test
    fun closeFailsQueuedWaitersAndRejectsNewReservations() = runTest {
        val ledger = UploadCapacityReservations(allocationLimit, headroom)
        ledger.synchronize(95)
        // The ISE is captured inside the child: a teardown failure must fail the reservation, and
        // letting it escape the child would cancel the test scope before the assertion runs.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                ledger.reserve({ WorkPriority.FOCUS }, 50)
                null
            } catch (thrown: IllegalStateException) {
                thrown
            }
        }
        assertEquals(1, ledger.waitingCount())
        ledger.close()
        ledger.close() // Idempotent.
        runCurrent()
        assertTrue("teardown must surface as a failure, not cancellation", waiter.await() is IllegalStateException)
        assertEquals(0, ledger.waitingCount())
        assertEquals(0, ledger.reservedBytes())
        val rejected = runCatching { ledger.reserve({ WorkPriority.FOCUS }, 10) }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
    }
}
