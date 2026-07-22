package ml.melun.mangaview.ntkack

import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NtkAckCancellationSafetyTest {
    @Test
    fun admittedCallIsCancelledAndRegistrationIsClosedAtomically() {
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        val registry = NtkAckCancellationRegistry<String>(cancelled::add)

        registry.register("admitted")
        registry.cancelAll()

        assertTrue("admitted" in cancelled)
        assertTrue(registry.isCancelled)
        assertTrue(registry.activeCount == 1)
        assertThrows(IllegalStateException::class.java) { registry.register("late") }
        registry.unregister("admitted")
        assertTrue(registry.activeCount == 0)
    }

    @Test
    fun concurrentRegisterEitherLosesBeforeAdmissionOrIsInCancellationSnapshot() {
        repeat(100) { iteration ->
            val cancelled = Collections.synchronizedList(mutableListOf<Int>())
            val registry = NtkAckCancellationRegistry<Int>(cancelled::add)
            val start = CountDownLatch(1)
            val registrationFailure = AtomicReference<Throwable?>()
            val registerThread = thread(start = true) {
                start.await()
                runCatching { registry.register(iteration) }
                    .onFailure(registrationFailure::set)
            }
            val cancelThread = thread(start = true) {
                start.await()
                registry.cancelAll()
            }

            start.countDown()
            registerThread.join()
            cancelThread.join()

            val rejected = registrationFailure.get() is IllegalStateException
            assertTrue(rejected || iteration in cancelled)
        }
    }

    @Test
    fun futurePublishedBeforeOrAfterCancellationIsAlwaysCancelled() {
        repeat(100) {
            val tasks = NtkAckFlightTasks()
            val future = FutureTask<Unit> { Unit }
            val start = CountDownLatch(1)
            val trackThread = thread(start = true) {
                start.await()
                tasks.track(future)
            }
            val cancelThread = thread(start = true) {
                start.await()
                tasks.cancel()
            }

            start.countDown()
            trackThread.join()
            cancelThread.join()

            assertTrue(future.isCancelled)
            assertTrue(tasks.isCancelled())
        }
    }
}
