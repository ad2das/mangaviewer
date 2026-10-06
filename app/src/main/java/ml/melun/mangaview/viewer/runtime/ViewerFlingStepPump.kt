package ml.melun.mangaview.viewer.runtime

import java.util.ArrayDeque

/**
 * Queues animation-looper fling steps for ordered main-thread delivery through [wake] only.
 *
 * Invariant: the animation looper never acquires the main MessageQueue monitor. Posting from that
 * thread would block on the monitor while main is busy and delay the vsync the step exists to
 * consume, so the production wake source is a non-blocking pipe observed by a main-looper file
 * descriptor listener ([ViewerFlingPumpPipeWake]). Delivery is demand-driven around that wake:
 * the pump writes one wake only on the disarmed -> armed transition, and the main-side listener
 * drains every queued step and then disarms under the same lock, so each step is delivered exactly
 * once, in FIFO order:
 *
 *  - a step queued while the listener drains is collected by the same listener pass;
 *  - a step queued after the drain's final poll but before the disarm is seen by the atomic
 *    empty-check, which drains again instead of disarming;
 *  - a step queued after the disarm finds the pump disarmed and arms a fresh wake itself.
 *
 * [finish] rides the same path: the true finish instant is parked under the lock, and the same
 * listener drains every queued step ahead of it before [onFinished] runs on the main thread.
 */
internal class ViewerFlingStepPump(
    private val wake: ViewerFlingPumpWake,
    private val emitStep: (Double, Double, Long, Long, Long) -> Boolean,
    private val onFinished: (Long) -> Unit,
) {
    private val steps = ArrayDeque<FlingStep>()
    private val lock = Any()
    private var armed = false
    private var closed = true
    private var finishPending = false
    private var finishAtNanos = 0L
    private var attached = false

    /**
     * Registers the main-side wake listener, then enables dispatch; must run on the main thread.
     * The wake source is live before any step can arm it, so no wake can be raised against a
     * missing listener; a dispatch during the attach instant itself is dropped by [closed].
     * Re-attachable.
     */
    fun attach() {
        if (attached) return
        wake.start(::onWoken)
        synchronized(lock) { closed = false }
        attached = true
    }

    /** Releases the wake source; dispatches after this are dropped until the next [attach]. */
    fun detach() {
        if (!attached) return
        attached = false
        synchronized(lock) {
            closed = true
            armed = false
            steps.clear()
            finishPending = false
        }
        wake.stop()
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
            if (closed) return false
            steps.addLast(step)
            if (armed) false else { armed = true; true }
        }
        if (arm) wake.wake()
        return true
    }

    /** Ends the fling at its true instant; queued steps drain ahead of the boundary on main. */
    fun finish() {
        val finishedAtNanos = System.nanoTime()
        val arm = synchronized(lock) {
            finishPending = true
            finishAtNanos = finishedAtNanos
            if (closed || armed) false else { armed = true; true }
        }
        if (arm) wake.wake()
    }

    private fun onWoken() {
        var finishedAt = NO_FINISH
        while (true) {
            drain()
            val decision = synchronized(lock) {
                when {
                    steps.isNotEmpty() -> Decision.DRAIN
                    finishPending -> {
                        finishPending = false
                        armed = false
                        finishedAt = finishAtNanos
                        Decision.FINISH
                    }
                    else -> {
                        armed = false
                        Decision.STOP
                    }
                }
            }
            if (decision != Decision.DRAIN) break
        }
        if (finishedAt != NO_FINISH) onFinished(finishedAt)
    }

    private fun drain() {
        while (true) {
            val step = synchronized(lock) { steps.pollFirst() } ?: break
            emitStep(step.deltaPixels, step.velocityPixelsPerSecond, step.frameTimeNanos,
                step.expectedPresentationTimeNanos, step.frameTimelineVsyncId)
        }
    }

    private enum class Decision { DRAIN, FINISH, STOP }

    private data class FlingStep(
        val deltaPixels: Double,
        val velocityPixelsPerSecond: Double,
        val frameTimeNanos: Long,
        val expectedPresentationTimeNanos: Long,
        val frameTimelineVsyncId: Long,
    )

    private companion object {
        const val NO_FINISH = -1L
    }
}
