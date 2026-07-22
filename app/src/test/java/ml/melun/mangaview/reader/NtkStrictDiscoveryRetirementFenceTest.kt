package ml.melun.mangaview.reader

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NtkStrictDiscoveryRetirementFenceTest {
    @Test
    fun exactGenerationRetirementCancelsOnceAndRejectsLaterPublication() {
        val path = "/webtoon/11/22"
        val fence = NtkStrictDiscoveryRetirementFence(path, 7L, 9L)
        val cancels = AtomicInteger()
        val cancelled = CountDownLatch(1)
        assertTrue(fence.attachAckCancellation {
            cancels.incrementAndGet()
            cancelled.countDown()
        })
        assertEquals("before", fence.withActiveOwnership(path, 7L, "before") { "before" })

        assertTrue(fence.retire(path, 7L))
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, cancels.get())
        assertFalse(fence.retire(path, 7L))
        assertEquals(1, cancels.get())
        try {
            fence.withActiveOwnership(path, 7L, "after") { fail("retired action ran") }
            fail("retired ownership was accepted")
        } catch (_: InterruptedIOException) {
            // Expected: stale bytes cannot reach a publication boundary.
        }
    }

    @Test
    fun wrongPathOrViewerGenerationCannotRetireCurrentFlight() {
        val path = "/manhwa/33/44"
        val fence = NtkStrictDiscoveryRetirementFence(path, 13L, 17L)
        assertFalse(fence.retire(path, 12L))
        assertFalse(fence.retire("/manhwa/33/45", 13L))
        assertFalse(fence.isRetired())
        assertEquals(41, fence.withActiveOwnership(path, 13L, "current") { 41 })
    }

    @Test
    fun retirementDoesNotWaitForPublicationMonitorAndRejectsItsLateResult() {
        val path = "/webtoon/55/66"
        val fence = NtkStrictDiscoveryRetirementFence(path, 21L, 34L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                fence.withActiveOwnership(path, 21L, "blocked_actor_join") {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    "stale"
                }
                fail("Retired publication result escaped")
            } catch (expected: InterruptedIOException) {
                failure.set(expected)
            }
        }, "retirement-publication-test")
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertTrue(fence.retire(path, 21L))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("retirement waited ${elapsedMs}ms for publication", elapsedMs < 100L)

        release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(2))
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is InterruptedIOException)
    }

    @Test
    fun physicalCancellationIsSingleShotAndLateAttachmentCancelsImmediately() {
        val path = "/manhwa/77/88"
        val fence = NtkStrictDiscoveryRetirementFence(path, 31L, 47L)
        val physicalCancels = AtomicInteger()
        val firstCancelled = CountDownLatch(1)
        assertTrue(fence.attachPhysicalCancellation {
            Runnable {
                physicalCancels.incrementAndGet()
                firstCancelled.countDown()
            }
        })
        assertTrue(fence.retire(path, 31L))
        assertTrue(firstCancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, physicalCancels.get())
        assertFalse(fence.retire(path, 31L))
        assertEquals(1, physicalCancels.get())

        val alreadyRetired = NtkStrictDiscoveryRetirementFence(path, 32L, 48L)
        assertTrue(alreadyRetired.retire(path, 32L))
        val lateCancelled = CountDownLatch(1)
        assertFalse(
            alreadyRetired.attachPhysicalCancellation {
                Runnable {
                    physicalCancels.incrementAndGet()
                    lateCancelled.countDown()
                }
            }
        )
        assertTrue(lateCancelled.await(2, TimeUnit.SECONDS))
        assertEquals(2, physicalCancels.get())
    }
}
