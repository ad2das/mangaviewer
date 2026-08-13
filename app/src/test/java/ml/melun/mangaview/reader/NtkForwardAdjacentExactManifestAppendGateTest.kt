package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkForwardAdjacentExactManifestAppendGateTest {
    private data class Pending(val revision: Long)

    @Test
    fun stalePendingCannotBlockOrOverwriteAReselectedPath() {
        val gate = NtkForwardAdjacentExactManifestAppendGate<Pending>(Pending::revision)
        val revision1 = Pending(1L)
        val revision3 = Pending(3L)

        assertSame(revision1, gate.installNewest("/webtoon/work/a", revision1))
        assertSame(revision3, gate.installNewest("/webtoon/work/a", revision3))
        assertSame(revision3, gate.installNewest("/webtoon/work/a", Pending(1L)))
        assertFalse(gate.removePending("/webtoon/work/a", revision1))
        assertSame(revision3, gate.current("/webtoon/work/a"))
    }

    @Test
    fun equalButDistinctStaleValueCannotRemoveTheInstalledOwner() {
        val gate = NtkForwardAdjacentExactManifestAppendGate<Pending>(Pending::revision)
        val installed = Pending(3L)
        val equalButDistinct = Pending(3L)
        gate.installNewest("/webtoon/work/a", installed)

        assertFalse(gate.removePending("/webtoon/work/a", equalButDistinct))
        assertSame(installed, gate.current("/webtoon/work/a"))
        assertTrue(gate.removePending("/webtoon/work/a", installed))
    }

    @Test
    fun inFlightReleaseRequiresTheExactClaimRevision() {
        val gate = NtkForwardAdjacentExactManifestAppendGate<Pending>(Pending::revision)
        val path = "/manhwa/work/a"

        assertTrue(gate.tryAcquire(path, 1L))
        assertFalse(gate.tryAcquire(path, 3L))
        assertFalse(gate.release(path, 3L))
        assertTrue(gate.release(path, 1L))
        assertTrue(gate.tryAcquire(path, 3L))
        assertFalse(gate.release(path, 1L))
        assertFalse(gate.tryAcquire(path, 4L))
        assertTrue(gate.release(path, 3L))
        assertTrue(gate.tryAcquire(path, 4L))
    }

    @Test
    fun aRetryCallbackBeforeReleaseCannotAcquireButReleaseThenRetryCan() {
        val gate = NtkForwardAdjacentExactManifestAppendGate<Pending>(Pending::revision)
        val path = "/webtoon/work/a"
        val pending = Pending(7L)
        gate.installNewest(path, pending)

        assertTrue(gate.tryAcquire(path, pending.revision))
        // Models a callback posted before finally released the exact revision token.
        assertFalse(gate.tryAcquire(path, pending.revision))
        assertTrue(gate.release(path, pending.revision))
        // The production helper schedules this retry only after the successful release above.
        assertTrue(gate.tryAcquire(path, pending.revision))
    }

    @Test
    fun concurrentPendingInstallationConvergesOnTheNewestRevision() {
        val gate = NtkForwardAdjacentExactManifestAppendGate<Pending>(Pending::revision)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val revisions = (1L..64L).toList()
        try {
            revisions.forEach { revision ->
                executor.execute {
                    start.await()
                    gate.installNewest("/webtoon/work/a", Pending(revision))
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS))
            assertEquals(64L, gate.current("/webtoon/work/a")?.revision)
        } finally {
            executor.shutdownNow()
        }
    }
}
