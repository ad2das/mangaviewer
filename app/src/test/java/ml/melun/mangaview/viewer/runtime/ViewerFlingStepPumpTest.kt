package ml.melun.mangaview.viewer.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract tests for fd-woken fling step delivery: FIFO order under one wake per disarmed ->
 * armed transition, no lost wakeup for steps that arrive during or just after a drain, finish
 * ordering behind queued steps, teardown drop, and the invariant that the animation-looper side
 * only ever calls the injected [ViewerFlingPumpWake.wake], never a main delivery path.
 */
class ViewerFlingStepPumpTest {
    @Test fun stepsDrainInDispatchOrderBehindOneWake() {
        val wake = FakeWake()
        val emitted = mutableListOf<Long>()
        val pump = pumpOf(wake) { emitted += it }
        pump.attach()

        repeat(4) { pump.dispatch(it.toDouble(), 0.0, it.toLong(), 0L, 0L) }
        assertEquals("a burst before the listener runs shares one wake", 1, wake.wakes)

        wake.deliver()
        assertEquals(listOf(0L, 1L, 2L, 3L), emitted)
        assertEquals("an empty drain disarms, so nothing wakes again by itself", 1, wake.wakes)
    }

    @Test fun aStepAfterTheDrainWakesThePumpAgain() {
        val wake = FakeWake()
        val emitted = mutableListOf<Long>()
        val pump = pumpOf(wake) { emitted += it }
        pump.attach()

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        wake.deliver()
        assertEquals(1, wake.wakes)

        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        assertEquals("a disarmed pump arms and wakes for the next step", 2, wake.wakes)
        wake.deliver()
        assertEquals(listOf(1L, 2L), emitted)
    }

    @Test fun stepDispatchedWhileADrainIsInFlightIsStillDelivered() {
        val wake = FakeWake()
        val emitted = mutableListOf<Long>()
        val firstEmissionEntered = CountDownLatch(1)
        val releaseFirstEmission = CountDownLatch(1)
        val pump = ViewerFlingStepPump(wake, { _, _, frameTime, _, _ ->
            emitted += frameTime
            if (frameTime == 1L) {
                firstEmissionEntered.countDown()
                check(releaseFirstEmission.await(5, TimeUnit.SECONDS))
            }
            true
        }, {})
        pump.attach()
        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        val listener = thread(name = "main-listener") { wake.deliver() }
        assertTrue(firstEmissionEntered.await(5, TimeUnit.SECONDS))

        // The listener is inside its drain, so the pump is still armed: these arrive without a wake.
        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        pump.dispatch(3.0, 0.0, 3L, 0L, 0L)
        assertEquals("a dispatch during the drain must not add a wake", 1, wake.wakes)
        releaseFirstEmission.countDown()
        listener.join(5_000)
        assertFalse("the listener must finish once the drain unblocks", listener.isAlive)
        assertEquals("the same listener pass collects them in order", listOf(1L, 2L, 3L), emitted)
        assertEquals(1, wake.wakes)
    }

    @Test fun eachDisarmedToArmedTransitionRaisesExactlyOneWake() {
        val wake = FakeWake()
        var emitted = 0
        val pump = ViewerFlingStepPump(wake, { _, _, _, _, _ -> emitted++; true }, {})
        pump.attach()

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        pump.dispatch(3.0, 0.0, 3L, 0L, 0L)
        wake.deliver()
        pump.dispatch(4.0, 0.0, 4L, 0L, 0L)
        wake.deliver()
        pump.dispatch(5.0, 0.0, 5L, 0L, 0L)
        pump.dispatch(6.0, 0.0, 6L, 0L, 0L)
        wake.deliver()

        assertEquals("one wake per disarmed -> armed transition, three here", 3, wake.wakes)
        assertEquals(6, emitted)
    }

    @Test fun finishWhileArmedRidesTheWakeAlreadyInFlight() {
        val wake = FakeWake()
        val events = mutableListOf<String>()
        var finishedAtNanos = 0L
        val pump = ViewerFlingStepPump(wake, { _, _, frameTime, _, _ ->
            events += "step$frameTime"
            true
        }, { atNanos -> finishedAtNanos = atNanos; events += "finished" })
        pump.attach()

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        pump.dispatch(2.0, 0.0, 2L, 0L, 0L)
        pump.finish()
        assertEquals("finish rides the wake already in flight", 1, wake.wakes)

        wake.deliver()
        assertEquals(listOf("step1", "step2", "finished"), events)
        assertTrue("the boundary carries the true finish instant", finishedAtNanos > 0L)
    }

    @Test fun finishAfterDisarmRaisesItsOwnWake() {
        val wake = FakeWake()
        val events = mutableListOf<String>()
        var finishedAtNanos = 0L
        val pump = ViewerFlingStepPump(wake, { _, _, frameTime, _, _ ->
            events += "step$frameTime"
            true
        }, { atNanos -> finishedAtNanos = atNanos; events += "finished" })
        pump.attach()

        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        wake.deliver()
        assertEquals(listOf("step1"), events)

        pump.finish()
        assertEquals("a disarmed pump owes the finish its own wake", 2, wake.wakes)
        wake.deliver()
        assertEquals(listOf("step1", "finished"), events)
        assertTrue(finishedAtNanos > 0L)
    }

    @Test fun detachDropsQueuedStepsAndSilencesDelivery() {
        val wake = FakeWake()
        val emitted = mutableListOf<Long>()
        val pump = pumpOf(wake) { emitted += it }
        pump.attach()
        pump.dispatch(1.0, 0.0, 1L, 0L, 0L)
        assertEquals(1, wake.wakes)

        pump.detach()
        assertEquals(1, wake.stops)
        assertFalse("dispatches after detach are dropped", pump.dispatch(2.0, 0.0, 2L, 0L, 0L))
        assertEquals("teardown must not wake", 1, wake.wakes)
        wake.deliver()
        assertEquals("the queued step is dropped with the wake source", emptyList<Long>(), emitted)

        pump.attach()
        pump.dispatch(3.0, 0.0, 3L, 0L, 0L)
        wake.deliver()
        assertEquals(listOf(3L), emitted)
    }

    @Test fun theAnimationLooperOnlyEverCallsWake() {
        val wake = FakeWake()
        val deliveryThreads = mutableListOf<String>()
        val pump = ViewerFlingStepPump(wake, { _, _, _, _, _ ->
            deliveryThreads += Thread.currentThread().name
            true
        }, { deliveryThreads += "finished" })
        pump.attach()

        val looper = thread(name = "animation-looper") {
            repeat(DISPATCHES) { pump.dispatch(it.toDouble(), 0.0, it.toLong(), 0L, 0L) }
        }
        looper.join(5_000)
        assertFalse("the animation looper must not wait for delivery", looper.isAlive)
        assertTrue("the animation looper must not deliver its own steps", deliveryThreads.isEmpty())
        assertEquals("the whole burst coalesces behind one wake", 1, wake.wakes)
        assertTrue("wakes come only from the animation thread",
            wake.wakingThreads.all { it == "animation-looper" })

        wake.deliver()
        assertEquals(DISPATCHES, deliveryThreads.size)
        assertTrue("delivery happens on the listener thread",
            deliveryThreads.all { it == Thread.currentThread().name })
    }

    @Test fun concurrentDispatchAndDeliveryDeliverEveryStepExactlyOnce() {
        val wake = FakeWake()
        val ids = ConcurrentHashMap.newKeySet<Long>()
        val duplicates = AtomicInteger()
        val delivered = AtomicInteger()
        val pump = ViewerFlingStepPump(wake, { _, _, frameTime, _, _ ->
            if (!ids.add(frameTime)) duplicates.incrementAndGet()
            delivered.incrementAndGet()
            true
        }, {})
        pump.attach()

        val producers = (0 until PRODUCERS).map { producer ->
            thread(name = "fling-producer-$producer") {
                repeat(STEPS_PER_PRODUCER) { sequence ->
                    pump.dispatch(1.0, 0.0, producer * PRODUCER_STRIDE + sequence, 0L, 0L)
                }
            }
        }
        producers.forEach { it.join(PRODUCER_TIMEOUT_MILLIS) }
        producers.forEach { assertFalse("producer ${it.name} must finish", it.isAlive) }

        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        while (delivered.get() < TOTAL_STEPS && System.nanoTime() < deadline) {
            wake.deliver()
        }

        assertEquals("every dispatched step must be delivered", TOTAL_STEPS, delivered.get())
        assertEquals("no step may be delivered twice", 0, duplicates.get())
        assertEquals(TOTAL_STEPS, ids.size)
        assertTrue("wakes coalesce instead of one per step", wake.wakes <= TOTAL_STEPS)
    }

    private fun pumpOf(
        wake: ViewerFlingPumpWake,
        onEmit: (Long) -> Unit,
    ): ViewerFlingStepPump = ViewerFlingStepPump(wake, { _, _, frameTime, _, _ ->
        onEmit(frameTime)
        true
    }, {})

    /** Records wake calls and runs the registered listener only when [deliver] is invoked. */
    private class FakeWake : ViewerFlingPumpWake {
        private val lock = Any()
        private var listener: (() -> Unit)? = null
        private var startCount = 0
        private var stopCount = 0
        private var wakeCount = 0
        private val wakingThreadNames = mutableListOf<String>()

        val starts: Int get() = synchronized(lock) { startCount }
        val stops: Int get() = synchronized(lock) { stopCount }
        val wakes: Int get() = synchronized(lock) { wakeCount }
        val wakingThreads: List<String> get() = synchronized(lock) { wakingThreadNames.toList() }

        override fun start(onWake: () -> Unit) {
            synchronized(lock) { startCount++; listener = onWake }
        }

        override fun stop() {
            synchronized(lock) { stopCount++; listener = null }
        }

        override fun wake() {
            synchronized(lock) {
                wakeCount++
                wakingThreadNames += Thread.currentThread().name
            }
        }

        fun deliver() {
            val current = synchronized(lock) { listener } ?: return
            current()
        }
    }

    private companion object {
        const val DISPATCHES = 64
        const val PRODUCERS = 4
        const val STEPS_PER_PRODUCER = 2_500
        const val TOTAL_STEPS = PRODUCERS * STEPS_PER_PRODUCER
        const val PRODUCER_STRIDE = 10_000L
        const val PRODUCER_TIMEOUT_MILLIS = 5_000L
        const val DRAIN_TIMEOUT_NANOS = 30_000_000_000L
    }
}
