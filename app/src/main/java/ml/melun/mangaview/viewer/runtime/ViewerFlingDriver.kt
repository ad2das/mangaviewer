package ml.melun.mangaview.viewer.runtime

import android.view.Choreographer
import kotlin.math.abs

internal fun interface ViewerFrameSchedulerFactory {
    fun create(callback: (Long, Long, Long) -> Unit): ViewerFrameScheduler
}

internal class ViewerFlingDriver(
    schedulerFactory: ViewerFrameSchedulerFactory,
    private val periodNanos: Long,
    private val emit: (
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ) -> Boolean,
    private val frameObserved: (sequence: Long, frameTimeNanos: Long) -> Unit,
    private val finished: () -> Unit,
    private val dispatch: ((Runnable) -> Unit)? = null,
) {
    constructor(
        choreographer: Choreographer,
        periodNanos: Long,
        emit: (Double, Double, Long, Long, Long) -> Boolean,
        frameObserved: (sequence: Long, frameTimeNanos: Long) -> Unit,
        finished: () -> Unit,
    ) : this(ViewerFrameSchedulerFactory { callback -> ViewerVsyncScheduler(choreographer, callback) },
        periodNanos, emit, frameObserved, finished, null)

    init {
        require(periodNanos > 0L) { "Fling pacing period must be positive" }
    }

    private val frameScheduler = schedulerFactory.create(::doFrame)
    private var velocity = 0.0
    private var previousFrameNanos = 0L
    private var motionSequence = 0L
    private var running = false

    /** Whether this velocity can sustain a fling, decided on the caller's thread. */
    fun canFling(initialVelocityPixelsPerSecond: Double): Boolean =
        abs(initialVelocityPixelsPerSecond) >= MINIMUM_VELOCITY

    fun start(
        initialVelocityPixelsPerSecond: Double,
        precedingFrameNanos: Long,
        sequence: Long,
    ): Boolean {
        if (!canFling(initialVelocityPixelsPerSecond)) return false
        require(precedingFrameNanos > 0L) { "Fling must continue from a real frame" }
        require(sequence > 0L) { "Fling motion sequence must be positive" }
        onAnimationThread {
            stopInternal()
            velocity = initialVelocityPixelsPerSecond.coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
            // Integrate from the supplied monotonic origin; starting does not consume a frame.
            previousFrameNanos = precedingFrameNanos
            motionSequence = sequence
            running = true
            frameScheduler.post(precedingFrameNanos + periodNanos)
        }
        return true
    }

    fun startFromRelease(
        initialVelocityPixelsPerSecond: Double,
        releasedAtNanos: Long,
        frameTimeNanos: Long,
        sequence: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ): Boolean {
        if (!canFling(initialVelocityPixelsPerSecond)) return false
        require(releasedAtNanos > 0L) { "Fling must continue from a real frame" }
        require(sequence > 0L) { "Fling motion sequence must be positive" }
        onAnimationThread {
            stopInternal()
            velocity = initialVelocityPixelsPerSecond.coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
            previousFrameNanos = releasedAtNanos
            motionSequence = sequence
            running = true
            // An UP with unchanged pointer coordinates still has elapsed momentum by this
            // frame. Waiting for another deadline would leave the release frame empty.
            // Input delivered after this frame instead starts on the next one.
            if (frameTimeNanos > releasedAtNanos) {
                doFrame(frameTimeNanos, frameTimelineVsyncId, expectedPresentationTimeNanos)
            } else {
                frameScheduler.post(releasedAtNanos + periodNanos)
            }
        }
        return true
    }

    fun stop() = onAnimationThread { stopInternal() }

    private fun stopInternal() {
        if (!running) return
        running = false
        previousFrameNanos = 0L
        motionSequence = 0L
        frameScheduler.cancel()
        finished()
    }

    private fun onAnimationThread(block: () -> Unit) {
        val target = dispatch
        if (target == null) block() else target(Runnable { block() })
    }

    private fun doFrame(
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) {
        if (!running) return
        doFrameBody(frameTimeNanos, frameTimelineVsyncId, expectedPresentationTimeNanos)
    }

    private fun doFrameBody(
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) {
        // Re-arm before synchronous input/content/graphics work so that work cannot
        // delay the next deadline. Every stop path cancels this arm.
        frameScheduler.post(frameTimeNanos + periodNanos)
        val previous = previousFrameNanos
        if (frameTimeNanos <= previous) return
        previousFrameNanos = frameTimeNanos
        if (previous > 0L) {
            val elapsedSeconds = (frameTimeNanos - previous).coerceAtLeast(0L) / NANOS_PER_SECOND
            if (elapsedSeconds > 0.0) {
                val step = ViewerFlingPhysics.advance(velocity, elapsedSeconds)
                if (!emit(
                        step.displacementPixels,
                        velocity,
                        frameTimeNanos,
                        expectedPresentationTimeNanos,
                        frameTimelineVsyncId,
                    )) {
                    stopInternal()
                    return
                }
                frameObserved(motionSequence, frameTimeNanos)
                velocity = step.velocityPixelsPerSecond
            }
        }
        if (abs(velocity) < MINIMUM_VELOCITY) stop()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MINIMUM_VELOCITY = 24.0
        const val MAXIMUM_VELOCITY = 24_000.0
    }
}
