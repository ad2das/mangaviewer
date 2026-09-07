package ml.melun.mangaview.app

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AndroidWorkDispatcherTest {
    @Test fun closureWaitsForWorkWithoutBlockingTheMainThread() = runBlocking {
        val worker = AndroidWorkDispatcher("close-regression", 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.coroutineDispatcher.dispatch(EmptyCoroutineContext, Runnable {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val closing = async(Dispatchers.Main) { worker.closeAndAwait() }
            var mainRan = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { mainRan = true }
            assertTrue(mainRan)
            assertFalse(closing.isCompleted)
            assertFalse(worker.isTerminated)
            release.countDown()
            withTimeout(3_000) { closing.await() }
            assertTrue(worker.isTerminated)
            worker.closeAndAwait() // Repeated close observes the same terminated executor.
        } finally {
            release.countDown()
            withContext(NonCancellable) { worker.closeAndAwait() }
        }
    }

    @Test fun timeoutCannotClaimWorkerTermination() = runBlocking {
        val worker = AndroidWorkDispatcher("close-timeout-regression", 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.coroutineDispatcher.dispatch(EmptyCoroutineContext, Runnable {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try {
                worker.closeAndAwait(10)
                fail("A blocked worker cannot be reported terminated")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("did not terminate"))
            }
            assertFalse(worker.isTerminated)
        } finally {
            release.countDown()
            withContext(NonCancellable) { worker.closeAndAwait() }
        }
        assertTrue(worker.isTerminated)
    }
}
