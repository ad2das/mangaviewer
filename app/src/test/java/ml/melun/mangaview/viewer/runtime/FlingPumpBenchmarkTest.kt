package ml.melun.mangaview.viewer.runtime

import java.util.ArrayDeque
import java.util.PriorityQueue
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reported JVM benchmark (never asserted): fd wakes, handler posts and allocations for 10k fast
 * fling steps under the legacy 4 ms polling pump versus the demand-driven production pump. The
 * legacy policy is a faithful copy of the pre-change pump driven by a virtual clock; the virtual
 * queue allocates one record per post, modelling the Handler message the real queue would obtain.
 * The demand policy drives the production pump through an inline [ViewerFlingPumpWake]: the animation
 * side writes one fd wake per disarmed -> armed transition and the listener consumes it immediately,
 * modelling a main looper that drains each step inside the display period.
 *
 * Gated behind FLING_PUMP_BENCH=1 because a run costs a few seconds: set that variable and paste
 * the [fling-pump-bench] lines into review.
 */
class FlingPumpBenchmarkTest {
    @Test fun flingPumpBenchmark() {
        assumeTrue(
            "set FLING_PUMP_BENCH=1 to run the fling pump benchmark",
            System.getenv("FLING_PUMP_BENCH") == "1",
        )
        repeat(WARMUP_RUNS) {
            driveDemandDriven()
            driveLegacyPolling()
        }
        val demand = measure("demand-fd-wake", "wakes", ::driveDemandDriven)
        val legacy = measure("legacy-polling", "posts", ::driveLegacyPolling)
        println(
            ("[fling-pump-bench] steps=$STEPS legacyPosts=%d demandWakes=%d " +
                "ratio legacyPosts/demandWakes=%.2f").format(
                legacy.first, demand.first, legacy.first.toDouble() / demand.first.toDouble(),
            ),
        )
    }

    @Test fun ledgerDrainBenchmark() {
        assumeTrue(
            "set FLING_PUMP_BENCH=1 to run the ledger drain benchmark",
            System.getenv("FLING_PUMP_BENCH") == "1",
        )
        repeat(WARMUP_RUNS) {
            driveLegacyBoxedLedger()
            drivePrimitiveLedger()
        }
        reportLedger("legacy-boxed", ::driveLegacyBoxedLedger)
        reportLedger("primitive", ::drivePrimitiveLedger)
    }

    /** One begin, three appends and one drain per cycle; four segments per cycle. */
    private fun driveLegacyBoxedLedger(): Int {
        val ledger = LegacyBoxedLedger()
        var visited = 0
        repeat(LEDGER_CYCLES) {
            ledger.begin(1_000f)
            ledger.append(900f)
            ledger.append(700f)
            ledger.append(1_100f)
            visited += ledger.drain().size
        }
        return visited
    }

    private fun drivePrimitiveLedger(): Int {
        val ledger = PointerDeltaLedger()
        var visited = 0
        repeat(LEDGER_CYCLES) {
            ledger.begin(1_000f)
            ledger.append(900f)
            ledger.append(700f)
            ledger.append(1_100f)
            ledger.drainEach { visited++ }
        }
        return visited
    }

    private fun reportLedger(label: String, run: () -> Int) {
        val allocatedBefore = threadAllocatedBytes()
        val startedAt = System.nanoTime()
        val segments = run()
        val elapsedNanos = System.nanoTime() - startedAt
        val allocated = threadAllocatedBytes() - allocatedBefore
        println(
            "[fling-pump-bench] ledger=$label cycles=$LEDGER_CYCLES segments=$segments " +
                "elapsedMs=%.1f allocatedBytes=%d allocBytesPerSegment=%.2f".format(
                    elapsedNanos / 1_000_000.0, allocated, allocated.toDouble() / segments,
                ),
        )
        assertTrue("elapsed time must be observable", elapsedNanos > 0L)
    }

    /** Faithful copy of the pre-change boxed ledger: ArrayDeque<Double> plus a toList copy per drain. */
    private class LegacyBoxedLedger {
        private val segments = ArrayDeque<Double>()
        private var lastY = 0f

        fun begin(y: Float) {
            check(segments.isEmpty())
            lastY = y
        }

        fun append(y: Float): Double {
            val delta = (lastY - y).toDouble()
            if (delta != 0.0) {
                val last = segments.lastOrNull()
                if (last != null && (last > 0.0) == (delta > 0.0)) {
                    segments.removeLast()
                    segments.addLast(last + delta)
                } else segments.addLast(delta)
            }
            lastY = y
            return delta
        }

        fun drain(): List<Double> = segments.toList().also { segments.clear() }
    }

    private fun measure(label: String, unit: String, run: () -> Long): Pair<Long, Long> {
        val allocatedBefore = threadAllocatedBytes()
        val startedAt = System.nanoTime()
        val units = run()
        val elapsedNanos = System.nanoTime() - startedAt
        val allocated = threadAllocatedBytes() - allocatedBefore
        println(
            "[fling-pump-bench] policy=$label steps=$STEPS $unit=$units " +
                "elapsedMs=%.1f allocatedBytes=%d allocBytesPerStep=%.1f".format(
                    elapsedNanos / 1_000_000.0, allocated, allocated.toDouble() / STEPS,
                ),
        )
        assertTrue("elapsed time must be observable", elapsedNanos > 0L)
        return units to allocated
    }

    /**
     * Drives the production pump through [VirtualWake] for one step per display period. Each wake
     * runs the listener inline, so the step is delivered before the next one is dispatched and the
     * wake count is the exact number of fd writes the animation looper would issue.
     */
    private fun driveDemandDriven(): Long {
        val wake = VirtualWake()
        val pump = ViewerFlingStepPump(wake, { _, _, _, _, _ -> true }, {})
        pump.attach()
        var nowNanos = 0L
        repeat(STEPS) {
            nowNanos += STEP_PERIOD_NANOS
            pump.dispatch(1.0, 0.0, nowNanos, 0L, 0L)
        }
        pump.finish()
        pump.detach()
        return wake.wakes
    }

    private fun driveLegacyPolling(): Long {
        val queue = VirtualQueue()
        val pump = LegacyPollingPump(queue)
        driveFling(queue, { pump.dispatch(1.0) }, pump::finish)
        return queue.posts
    }

    /** One fast-fling step every display period, with the main queue drained before and after it. */
    private fun driveFling(queue: VirtualQueue, dispatch: () -> Unit, finish: () -> Unit) {
        var nowNanos = 0L
        repeat(STEPS) {
            nowNanos += STEP_PERIOD_NANOS
            queue.nowNanos = nowNanos
            queue.runDue()
            dispatch()
            queue.runDue()
        }
        finish()
        queue.nowNanos = nowNanos + TAIL_NANOS
        queue.runDue()
    }

    /**
     * Inline wake for the demand side: records the fd write and runs the main-side listener on the
     * caller, modelling a main looper that consumes the wake before the next step is dispatched.
     */
    private class VirtualWake : ViewerFlingPumpWake {
        private var listener: (() -> Unit)? = null
        var wakes = 0L
            private set

        override fun start(onWake: () -> Unit) {
            listener = onWake
        }

        override fun stop() {
            listener = null
        }

        override fun wake() {
            wakes++
            listener?.invoke()
        }
    }

    /** Faithful copy of the pre-change polling pump: 4 ms re-arm, 500 ms liveness timeout. */
    private class LegacyPollingPump(private val queue: VirtualQueue) {
        private val steps = ArrayDeque<LegacyStep>()
        private val lock = Any()
        private var pumpArmed = false
        private var live = false
        private var lastStepNanos = 0L
        private val pump = object : Runnable {
            override fun run() {
                pumpSteps()
            }
        }

        fun dispatch(deltaPixels: Double) {
            val step = LegacyStep(deltaPixels)
            val arm = synchronized(lock) {
                live = true
                lastStepNanos = queue.nowNanos
                steps.addLast(step)
                if (pumpArmed) false else { pumpArmed = true; true }
            }
            if (arm) queue.post(pump, 0L)
        }

        fun finish() {
            synchronized(lock) { live = false }
            queue.post({ drain() }, 0L)
        }

        private fun pumpSteps() {
            drain()
            val rearm = synchronized(lock) {
                val current = live && queue.nowNanos - lastStepNanos < FLING_PUMP_TIMEOUT_NANOS
                if (current) true else { pumpArmed = false; false }
            }
            if (rearm) queue.post(pump, FLING_PUMP_DELAY_MILLIS)
        }

        private fun drain() {
            while (true) {
                synchronized(lock) { steps.pollFirst() } ?: break
                // The step's payload is irrelevant to the post/alloc evidence.
            }
        }

        private class LegacyStep(val deltaPixels: Double)

        private companion object {
            const val FLING_PUMP_DELAY_MILLIS = 4L
            const val FLING_PUMP_TIMEOUT_NANOS = 500_000_000L
        }
    }

    /** Virtual-time main queue: one allocated record per post, delivered in due-time order. */
    private class VirtualQueue(var nowNanos: Long = 0L) {
        private class Scheduled(val dueNanos: Long, val ordinal: Long, val runnable: Runnable)
        private val pending = PriorityQueue<Scheduled>(compareBy({ it.dueNanos }, { it.ordinal }))
        private var ordinal = 0L
        var posts = 0L
            private set

        fun post(runnable: Runnable, delayMillis: Long): Boolean {
            posts++
            pending += Scheduled(nowNanos + delayMillis * 1_000_000L, ordinal++, runnable)
            return true
        }

        fun runDue() {
            // Each message runs at its own due time so a re-arm is scheduled relative to it, like a
            // real looper; the clock is restored to the window end afterwards.
            val end = nowNanos
            while (true) {
                val next = pending.poll() ?: break
                if (next.dueNanos > end) {
                    pending += next
                    break
                }
                nowNanos = next.dueNanos
                next.runnable.run()
            }
            nowNanos = end
        }
    }

    /**
     * Reflective because app unit tests compile against the Android boot classpath, which does not
     * expose java.lang.management; the test JVM itself is a full JDK and serves the real counter.
     */
    @Suppress("DEPRECATION")
    private fun threadAllocatedBytes(): Long = try {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = factory.getMethod("getThreadMXBean").invoke(null)
        val beanType = Class.forName("com.sun.management.ThreadMXBean")
        val supported = beanType.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
        if (supported) {
            beanType.getMethod("setThreadAllocatedMemoryEnabled", java.lang.Boolean.TYPE).invoke(bean, true)
            beanType.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
                .invoke(bean, Thread.currentThread().id) as Long
        } else {
            -1L
        }
    } catch (_: Throwable) {
        -1L
    }

    private companion object {
        const val STEPS = 10_000
        const val LEDGER_CYCLES = 10_000
        const val STEP_PERIOD_NANOS = 16_666_667L
        const val TAIL_NANOS = 100_000_000L
        const val WARMUP_RUNS = 2
    }
}
