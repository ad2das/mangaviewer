package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDeferredAdjacentPrepareMailboxTest {
    @Test
    fun explicitBoundaryOwnershipSurvivesLaterSilentCoalescing() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()

        assertTrue(mailbox.offer(10, 1, silentMissing = true))
        assertFalse(mailbox.offer(11, 1, silentMissing = false))
        assertFalse(mailbox.offer(12, 1, silentMissing = true))

        assertEquals(
            NtkDeferredAdjacentPrepareMailbox.Request(12, 1, silentMissing = false),
            mailbox.take(),
        )
        assertNull(mailbox.take())
    }

    @Test
    fun requestPublishedAfterTakeOwnsANewWakeup() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        assertTrue(mailbox.offer(20, 1, silentMissing = false))
        assertTrue(mailbox.hasPending())
        assertNotNull(mailbox.take())
        assertFalse(mailbox.hasPending())

        assertTrue(mailbox.offer(21, 1, silentMissing = false))
        assertEquals(21, mailbox.take()?.anchor)
    }

    @Test
    fun concurrentOffersAndTakesNeverLeaveAnUnownedRequest() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val pool = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(1)
        val producersDone = CountDownLatch(3)
        val wakeups = AtomicInteger()

        repeat(3) { producer ->
            pool.execute {
                started.await()
                repeat(2_000) { ordinal ->
                    if (mailbox.offer(producer * 2_000 + ordinal, 1, ordinal % 2 == 0)) {
                        wakeups.incrementAndGet()
                    }
                }
                producersDone.countDown()
            }
        }
        started.countDown()

        var consumed = 0
        while (!producersDone.await(1, TimeUnit.MILLISECONDS)) {
            if (mailbox.take() != null) consumed++
        }
        while (mailbox.take() != null) consumed++

        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(wakeups.get(), consumed)
    }

    @Test
    fun clearRetiresThePendingLifecycleTurn() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        assertTrue(mailbox.offer(30, 1, silentMissing = false))
        mailbox.clear()
        assertFalse(mailbox.hasPending())
        assertNull(mailbox.take())
        assertTrue(mailbox.offer(31, 1, silentMissing = true))
    }
}
