package ml.melun.mangaview.viewer.runtime

import java.util.ArrayDeque

/**
 * Queues animation-looper fling steps for ordered main-thread delivery.
 *
 * Delivery is demand-driven: the animation looper appends under the private lock and posts the pump
 * only when it is not already armed, and the pump disarms itself as soon as a drain leaves the queue
 * empty. No periodic wake-up is scheduled, so an idle fling costs nothing: a step is delivered on
 * the post its own dispatch made instead of waiting for a poll period. A fling that ends without
 * [finish] needs no liveness timeout either; with no queued steps there is nothing left to deliver,
 * so the pump has already stopped by construction.
 */
internal class ViewerFlingStepPump(
    private val postToMain: (Runnable) -> Boolean,
    private val emitStep: (Double, Double, Long, Long, Long) -> Boolean,
    private val onFinished: (Long) -> Unit,
) {
    private val steps = ArrayDeque<FlingStep>()
    private val lock = Any()
    private var pumpArmed = false
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
            steps.addLast(step)
            if (pumpArmed) false else { pumpArmed = true; true }
        }
        if (arm) postToMain(pump)
        return true
    }

    /** Ends the fling at its true instant, then drains the queued steps ahead of the boundary. */
    fun finish() {
        val finishedAtNanos = System.nanoTime()
        postToMain {
            drain()
            onFinished(finishedAtNanos)
        }
    }

    private fun pumpSteps() {
        drain()
        // The re-arm decision is atomic with the queue check. A step added concurrently either
        // finds the pump still armed and is covered by this repost, or finds it disarmed and posts
        // the pump itself; no interleaving can strand a step with no armed pump.
        val repost = synchronized(lock) {
            if (steps.isNotEmpty()) true else { pumpArmed = false; false }
        }
        if (repost) postToMain(pump)
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
}
