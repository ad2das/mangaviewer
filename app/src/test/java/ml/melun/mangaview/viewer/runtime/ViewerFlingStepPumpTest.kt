package ml.melun.mangaview.viewer.runtime

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract tests for demand-driven fling step delivery: FIFO order, the drain/disarm handshake
 * with a concurrent dispatch, finish ordering, and the post bound per dispatched step.
 */
class ViewerFlingStepPumpTest {
    @Test fun stepsDrainInDispatchOrderBehindOneArmedPump() {
        val queue = FakeMainQueue()
        val emitted = mutableListOf<Long>()
        val pump = pumpOf(queue) { frameTime -> emitted += frameTime }

        repeat(4) { pump.dispatch(it.toDouble(), 0.0, it.toLong(), 0L, 0L) }
        assertEquals("a burst before the drain shares one armed pump", 1, queue.posts)

        queue.runAll()
        assertEquals(listOf(0L, 1L, 2L, 3L), emitted)
        assertEquals(0, queue.size)
    }

    @Test fun aStepAfterDisarmWakesThePumpAgainWithoutPolling() {
        val queue = FakeMainQueue()
        val emitted = mutableListOf<Long>()
        val pump = pumpOf(queue) { frameTime -> emitted += frameTime }

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        queue.runAll()
        assertEquals(1, queue.posts)
        assertEquals("an empty drain disarms: no periodic wake-up remains", 0, queue.size)

        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        assertEquals(2, queue.posts)
        queue.runAll()
        assertEquals(listOf(1L, 2L), emitted)
        assertEquals(0, queue.size)
    }

    @Test fun stepDispatchedWhileADrainIsInFlightIsStillDelivered() {
        val queue = FakeMainQueue()
        val emitted = mutableListOf<Long>()
        val firstEmissionEntered = CountDownLatch(1)
        val releaseFirstEmission = CountDownLatch(1)
        val pump = ViewerFlingStepPump({ queue.post(it) }, { _, _, frameTime, _, _ ->
            emitted += frameTime
            if (frameTime == 1L) {
                firstEmissionEntered.countDown()
                check(releaseFirstEmission.await(5, TimeUnit.SECONDS))
            }
            true
        }, {})

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        val drainer = thread(name = "fling-pump-drainer") { queue.runNext() }
        assertTrue(firstEmissionEntered.await(5, TimeUnit.SECONDS))

        // The pump is inside its drain: these steps must be collected without a second post.
        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        pump.dispatch(3.0, 0.0, 3L, 0L, 0L)
        releaseFirstEmission.countDown()
        drainer.join(5_000)
        assertFalse("the drain must complete", drainer.isAlive)
        queue.runAll()

        assertEquals(1, queue.posts)
        assertEquals(listOf(1L, 2L, 3L), emitted)
    }

    @Test fun finishDrainsQueuedStepsBeforeTheBoundary() {
        val queue = FakeMainQueue()
        val events = mutableListOf<String>()
        var finishedAtNanos = 0L
        val pump = ViewerFlingStepPump({ queue.post(it) }, { _, _, frameTime, _, _ ->
            events += "step$frameTime"
            true
        }, { atNanos -> finishedAtNanos = atNanos; events += "finished" })

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        pump.finish()
        queue.runAll()

        assertEquals(listOf("step1", "step2", "finished"), events)
        assertTrue("the boundary carries the true finish instant", finishedAtNanos > 0L)
        assertEquals(0, queue.size)
    }

    @Test fun immediateDrainsPostAtMostOncePerDispatchedStep() {
        val queue = FakeMainQueue()
        var emitted = 0
        val pump = ViewerFlingStepPump({ queue.post(it) }, { _, _, _, _, _ -> emitted++; true }, {})

        repeat(STEPS) { step ->
            pump.dispatch(step.toDouble(), 0.0, step.toLong(), 0L, 0L)
            queue.runAll()
        }

        assertEquals(STEPS, emitted)
        assertTrue("immediate drains must not exceed one post per step", queue.posts <= STEPS)
    }

    @Test fun stepsBatchedFasterThanTheDrainSharePosts() {
        val queue = FakeMainQueue()
        var emitted = 0
        val pump = ViewerFlingStepPump({ queue.post(it) }, { _, _, _, _, _ -> emitted++; true }, {})

        repeat(BURSTS) {
            repeat(STEPS / BURSTS) { step ->
                pump.dispatch(step.toDouble(), 0.0, step.toLong(), 0L, 0L)
            }
            queue.runAll()
        }

        assertEquals(STEPS, emitted)
        assertTrue("batched arrival must coalesce, not poll per step", queue.posts < STEPS)
    }

    private fun pumpOf(
        queue: FakeMainQueue,
        onEmit: (Long) -> Unit,
    ): ViewerFlingStepPump = ViewerFlingStepPump({ queue.post(it) }, { _, _, frameTime, _, _ ->
        onEmit(frameTime)
        true
    }, {})

    /** Records ordered posts and runs them on demand, mirroring a main-looper queue. */
    private class FakeMainQueue {
        private val messages = ArrayDeque<Runnable>()
        @Volatile var posts = 0
            private set
        val size: Int get() = synchronized(this) { messages.size }

        @Synchronized fun post(message: Runnable): Boolean {
            posts++
            messages.addLast(message)
            return true
        }

        @Synchronized fun runNext() {
            messages.pollFirst()?.run()
        }

        fun runAll() {
            while (true) {
                val message = synchronized(this) { messages.pollFirst() } ?: return
                message.run()
            }
        }
    }

    private companion object {
        const val STEPS = 10_000
        const val BURSTS = 100
    }
}
