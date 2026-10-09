package ml.melun.mangaview.app

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineUserAgentSourceTest {
    /**
     * The deadlock shape from the ANR: a background caller resolves the engine UA and waits for
     * WebView startup, which must run on main; main then asks for the same value. A synchronized
     * lazy would make main wait for the background resolver's lock while the background resolver
     * waits for main — a cycle. The holder holds no lock across resolve(), so main runs the
     * resolver itself and the background caller observes the published value.
     */
    @Test
    fun aBackgroundResolverNeverBlocksAMainThreadCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val source = EngineUserAgentSource {
            if (calls.incrementAndGet() == 1) {
                // The background caller: waits until the main-thread caller also resolves.
                entered.countDown()
                assertTrue("background resolver was never released", release.await(2, TimeUnit.SECONDS))
            } else {
                // The main-thread caller: reaching resolve() at all is what unblocks the cycle.
                release.countDown()
            }
            TEST_USER_AGENT
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val background = pool.submit(Callable { source.get() })
            assertTrue("background resolver never entered", entered.await(2, TimeUnit.SECONDS))
            val mainThread = pool.submit(Callable { source.get() })
            val mainValue = try {
                mainThread.get(2, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                fail("a main-thread get() blocked behind the background resolver")
                return
            }
            val backgroundValue = background.get(2, TimeUnit.SECONDS)
            assertEquals(TEST_USER_AGENT, mainValue)
            assertEquals("both callers must observe the same published value", mainValue, backgroundValue)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * Control assertion for the test above: the same scenario on a SYNCHRONIZED lazy times out,
     * proving the deadlock is what this holder fixes. All waits are bounded and the latch is
     * released afterwards, so the control cannot hang the suite.
     */
    @Test
    fun theSynchronizedLazyControlTimesOutInTheSameScenario() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val control = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            } else {
                release.countDown()
            }
            TEST_USER_AGENT
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val background = pool.submit(Callable { control.value })
            assertTrue("background resolver never entered", entered.await(2, TimeUnit.SECONDS))
            val mainThread = pool.submit(Callable { control.value })
            try {
                mainThread.get(500, TimeUnit.MILLISECONDS)
                fail("a synchronized lazy must make the main-thread get() wait for the held lock")
            } catch (_: TimeoutException) {
                // Expected: the background resolver holds the initialization lock while waiting,
                // which is exactly the cycle the lock-free holder removes.
            }
            release.countDown()
            assertEquals(TEST_USER_AGENT, background.get(2, TimeUnit.SECONDS))
            assertEquals(TEST_USER_AGENT, mainThread.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun theValueIsResolvedOnceAndReused() {
        val calls = AtomicInteger()
        val source = EngineUserAgentSource {
            calls.incrementAndGet()
            TEST_USER_AGENT
        }
        assertEquals(TEST_USER_AGENT, source.get())
        assertEquals(TEST_USER_AGENT, source.get())
        assertEquals("the published value must be reused", 1, calls.get())
    }

    private companion object {
        const val TEST_USER_AGENT = "engine-test-user-agent"
    }
}
