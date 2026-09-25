package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import java.util.ArrayDeque

/** Queues animation-looper fling steps for ordered main-thread delivery and keeps the pump armed. */
internal class ViewerFlingStepPump(
    private val mainHandler: Handler,
    private val emitStep: (Double, Double, Long, Long, Long) -> Boolean,
    private val onFinished: (Long) -> Unit,
) {
    private val steps = ArrayDeque<FlingStep>()
    private val lock = Any()
    private var pumpArmed = false
    private var live = false
    private var lastStepNanos = 0L
    private val pump = object : Runnable {
        override fun run() {
            pumpSteps()
        }
    }

    /** Appends one step under the private lock; the animation looper must never post to main. */
    fun dispatch(
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ): Boolean {
        val step = FlingStep(deltaPixels, velocityPixelsPerSecond, frameTimeNanos,
            expectedPresentationTimeNanos, frameTimelineVsyncId)
        val arm = synchronized(lock) {
            live = true
            lastStepNanos = System.nanoTime()
            steps.addLast(step)
            if (pumpArmed) false else { pumpArmed = true; true }
        }
        if (arm) mainHandler.post(pump)
        return true
    }

    /** Ends the fling at its true instant, then drains the queued steps ahead of the boundary. */
    fun finish() {
        val finishedAtNanos = System.nanoTime()
        synchronized(lock) { live = false }
        mainHandler.post {
            drain()
            onFinished(finishedAtNanos)
        }
    }

    private fun pumpSteps() {
        drain()
        val rearm = synchronized(lock) {
            val current = live && System.nanoTime() - lastStepNanos < FLING_PUMP_TIMEOUT_NANOS
            if (current) true else { pumpArmed = false; false }
        }
        if (rearm) mainHandler.postDelayed(pump, FLING_PUMP_DELAY_MILLIS)
    }

    private fun drain() {
        while (true) {
            val step = synchronized(lock) { steps.pollFirst() } ?: break
            emitStep(step.deltaPixels, step.velocityPixelsPerSecond, step.frameTimeNanos,
                step.expectedPresentationTimeNanos, step.frameTimelineVsyncId)
        }
    }

    private data class FlingStep(
        val deltaPixels: Double,
        val velocityPixelsPerSecond: Double,
        val frameTimeNanos: Long,
        val expectedPresentationTimeNanos: Long,
        val frameTimelineVsyncId: Long,
    )

    private companion object {
        /** How often the armed pump re-checks while a fling is live; well inside one refresh period. */
        const val FLING_PUMP_DELAY_MILLIS = 4L

        /** A live fling that produced no motion step for this long has ended without its finish call. */
        const val FLING_PUMP_TIMEOUT_NANOS = 500_000_000L
    }
}
