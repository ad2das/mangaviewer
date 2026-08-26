package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
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

        val wakeup = mailbox.offer(10, 1, silentMissing = true)!!
        assertNotNull(wakeup)
        assertNull(mailbox.offer(11, 1, silentMissing = false))
        assertNull(mailbox.offer(12, 1, silentMissing = true))

        val request = mailbox.take(wakeup.token)
        assertNotNull(request)
        assertEquals(12, request?.anchor)
        assertEquals(1, request?.direction)
        assertFalse(request?.silentMissing ?: true)
        assertNull(mailbox.take(wakeup.token))
    }

    @Test
    fun requestPublishedAfterTakeOwnsANewWakeup() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val first = mailbox.offer(20, 1, silentMissing = false)
        assertNotNull(first)
        assertTrue(mailbox.hasPending())
        assertNotNull(mailbox.take(first!!.token))
        assertFalse(mailbox.hasPending())

        val second = mailbox.offer(21, 1, silentMissing = false)
        assertNotNull(second)
        assertEquals(21, mailbox.take(second!!.token)?.anchor)
    }

    @Test
    fun concurrentOffersAndTakesNeverLeaveAnUnownedRequest() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val pool = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(1)
        val producersDone = CountDownLatch(3)
        val wakeups = AtomicInteger()
        val tokens = ConcurrentLinkedQueue<NtkDeferredAdjacentPrepareMailbox.Wakeup>()

        repeat(3) { producer ->
            pool.execute {
                started.await()
                repeat(2_000) { ordinal ->
                    val wakeup = mailbox.offer(producer * 2_000 + ordinal, 1, ordinal % 2 == 0)
                    if (wakeup != null) {
                        wakeups.incrementAndGet()
                        tokens.add(wakeup)
                    }
                }
                producersDone.countDown()
            }
        }
        started.countDown()

        var consumed = 0
        while (!producersDone.await(1, TimeUnit.MILLISECONDS)) {
            tokens.poll()?.let { if (mailbox.take(it.token) != null) consumed++ }
        }
        while (true) {
            val wakeup = tokens.poll() ?: break
            if (mailbox.take(wakeup.token) != null) consumed++
        }

        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(wakeups.get(), consumed)
    }

    @Test
    fun clearRetiresThePendingLifecycleTurn() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val stale = mailbox.offer(30, 1, silentMissing = false)
        assertNotNull(stale)
        mailbox.clear()
        assertFalse(mailbox.hasPending())
        assertNull(mailbox.take(stale!!.token))
        assertNotNull(mailbox.offer(31, 1, silentMissing = true))
    }

    @Test
    fun accelerationInvalidatesTheOldDelayedCallback() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val delayed = mailbox.offer(40, 1, silentMissing = false)!!
        val immediate = mailbox.accelerate()!!

        assertNull(mailbox.take(delayed.token))
        assertEquals(40, mailbox.take(immediate.token)?.anchor)
    }

    @Test
    fun busyReofferCannotOverwriteANewerProducerRevision() {
        val mailbox = NtkDeferredAdjacentPrepareMailbox()
        val firstWake = mailbox.offer(50, 1, silentMissing = false)!!
        val running = mailbox.take(firstWake.token)!!
        val newerWake = mailbox.offer(51, 1, silentMissing = true)!!

        assertNull(mailbox.reoffer(running))
        val newer = mailbox.take(newerWake.token)!!
        assertEquals(51, newer.anchor)
        assertFalse(newer.silentMissing)
    }
}
