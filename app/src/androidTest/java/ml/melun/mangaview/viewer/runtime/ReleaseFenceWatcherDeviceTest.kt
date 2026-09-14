package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Drives the production ReleaseFenceWatcher on a HandlerThread so registration and one-shot wake
 * ride a real Java Looper - the same owner-thread linkage the compositor uses. Pipe readiness and
 * HUP are generic fd events, not sync-fence products: the test proves wake/deregistration
 * behavior, not fence validation or buffer release.
 */
class ReleaseFenceWatcherDeviceTest {
    @Test fun readinessWakeRegistersAndFiresExactlyOnceOnHandlerThreadLooper() {
        val thread = HandlerThread("release-fence-readiness").apply { start() }
        val preSignaled = ParcelFileDescriptor.createPipe()
        val laterReadiness = ParcelFileDescriptor.createPipe()
        try {
            val ownerTid = onOwner(thread) { ReleaseFenceWatcherProbe.currentTid() }
            assertNotEquals(Process.myTid(), ownerTid)
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.hasLooper() })

            // Readiness already present at arm time: level-triggered, one notification.
            Os.write(preSignaled[1].fileDescriptor, byteArrayOf(1), 0, 1)
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.arm(preSignaled[0].fd) })
            awaitWakeCount(1)
            assertEquals(ownerTid, ReleaseFenceWatcherProbe.wakeTid())
            assertEquals(1, ReleaseFenceWatcherProbe.wakeInputCount())
            assertEquals(0, ReleaseFenceWatcherProbe.wakeFaultCount())
            assertFalse(onOwner(thread) { ReleaseFenceWatcherProbe.armed() })
            settle()
            assertEquals(1, ReleaseFenceWatcherProbe.wakeCount())

            // Readiness arriving after registration.
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.arm(laterReadiness[0].fd) })
            settle()
            assertEquals(0, ReleaseFenceWatcherProbe.wakeCount())
            Os.write(laterReadiness[1].fileDescriptor, byteArrayOf(2), 0, 1)
            awaitWakeCount(1)
            assertEquals(ownerTid, ReleaseFenceWatcherProbe.wakeTid())
            assertEquals(1, ReleaseFenceWatcherProbe.wakeInputCount())
            assertEquals(0, ReleaseFenceWatcherProbe.wakeFaultCount())
            assertFalse(onOwner(thread) { ReleaseFenceWatcherProbe.armed() })
        } finally {
            shutdown(thread, preSignaled[0], preSignaled[1], laterReadiness[0], laterReadiness[1])
        }
    }

    @Test fun cancellationAndHangupDeregisterOnceWithoutFabricatingReadiness() {
        val thread = HandlerThread("release-fence-cancel").apply { start() }
        val cancelled = ParcelFileDescriptor.createPipe()
        val hungUp = ParcelFileDescriptor.createPipe()
        try {
            val ownerTid = onOwner(thread) { ReleaseFenceWatcherProbe.currentTid() }
            assertNotEquals(Process.myTid(), ownerTid)
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.hasLooper() })

            // Explicit removal before any signal: even later readiness produces no notification.
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.arm(cancelled[0].fd) })
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.disarm() })
            assertFalse(onOwner(thread) { ReleaseFenceWatcherProbe.armed() })
            Os.write(cancelled[1].fileDescriptor, byteArrayOf(1), 0, 1)
            settle()
            assertEquals(0, ReleaseFenceWatcherProbe.wakeCount())

            // HUP: exactly one fault notification, deregistered, and the read fd stays test-owned.
            assertTrue(onOwner(thread) { ReleaseFenceWatcherProbe.arm(hungUp[0].fd) })
            hungUp[1].close()
            awaitWakeCount(1)
            assertEquals(ownerTid, ReleaseFenceWatcherProbe.wakeTid())
            assertEquals(0, ReleaseFenceWatcherProbe.wakeInputCount())
            assertEquals(1, ReleaseFenceWatcherProbe.wakeFaultCount())
            assertFalse(onOwner(thread) { ReleaseFenceWatcherProbe.armed() })
            settle()
            assertEquals(1, ReleaseFenceWatcherProbe.wakeCount())
            assertTrue(Os.fcntlInt(hungUp[0].fileDescriptor, OsConstants.F_GETFD, 0) >= 0)
            assertFalse(onOwner(thread) { ReleaseFenceWatcherProbe.disarm() })
        } finally {
            shutdown(thread, cancelled[0], cancelled[1], hungUp[0], hungUp[1])
        }
    }

    private fun <T : Any> onOwner(thread: HandlerThread, block: () -> T): T {
        val result = arrayOfNulls<Any>(1)
        postOnOwner(thread) { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    private fun postOnOwner(thread: HandlerThread, block: () -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        val latch = CountDownLatch(1)
        check(Handler(thread.looper).post {
            try {
                block()
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                latch.countDown()
            }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) throw AssertionError("owner task timed out")
        failure[0]?.let { throw it }
    }

    private fun awaitWakeCount(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (ReleaseFenceWatcherProbe.wakeCount() >= expected) return
            Thread.sleep(10)
        }
        throw AssertionError("expected $expected wake(s), got ${ReleaseFenceWatcherProbe.wakeCount()}")
    }

    private fun settle() {
        Thread.sleep(200)
    }

    private fun shutdown(thread: HandlerThread, vararg descriptors: ParcelFileDescriptor) {
        // Remove and close on the owner looper first; a watched fd is never closed while it runs.
        runCatching {
            postOnOwner(thread) {
                ReleaseFenceWatcherProbe.disarm()
                descriptors.forEach { runCatching { it.close() } }
            }
        }
        thread.quitSafely()
        thread.join(5_000)
        if (thread.isAlive) {
            thread.quit()
            thread.join(5_000)
        }
        if (thread.isAlive) throw AssertionError("release-fence owner thread still alive; descriptors left open")
        // The looper is gone, so no registration can dispatch: idempotent close from here is safe.
        descriptors.forEach { runCatching { it.close() } }
    }
}
