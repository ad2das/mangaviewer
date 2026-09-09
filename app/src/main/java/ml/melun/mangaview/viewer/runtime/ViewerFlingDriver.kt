package ml.melun.mangaview.viewer.runtime

import android.view.Choreographer
import kotlin.math.abs

internal fun interface ViewerFrameSchedulerFactory {
    fun create(callback: (Long, Long, Long) -> Unit): ViewerFrameScheduler
}

internal class ViewerFlingDriver(
    schedulerFactory: ViewerFrameSchedulerFactory,
    private val emit: (
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ) -> Boolean,
    private val frameObserved: (sequence: Long, frameTimeNanos: Long) -> Unit,
    private val finished: () -> Unit,
) {
    constructor(
        choreographer: Choreographer,
        emit: (Double, Double, Long, Long, Long) -> Boolean,
        frameObserved: (sequence: Long, frameTimeNanos: Long) -> Unit,
        finished: () -> Unit,
    ) : this(ViewerFrameSchedulerFactory { callback -> ViewerVsyncScheduler(choreographer, callback) },
        emit, frameObserved, finished)

    private val frameScheduler = schedulerFactory.create(::doFrame)
    private var velocity = 0.0
    private var previousFrameNanos = 0L
    private var motionSequence = 0L
    private var running = false

    fun start(
        initialVelocityPixelsPerSecond: Double,
        precedingFrameNanos: Long,
        sequence: Long,
    ): Boolean {
        stop()
        if (abs(initialVelocityPixelsPerSecond) < MINIMUM_VELOCITY) return false
        require(precedingFrameNanos > 0L) { "Fling must continue from a real frame" }
        require(sequence > 0L) { "Fling motion sequence must be positive" }
        velocity = initialVelocityPixelsPerSecond.coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
        // Integrate from the supplied monotonic origin; starting does not consume a frame.
        previousFrameNanos = precedingFrameNanos
        motionSequence = sequence
        running = true
        frameScheduler.post()
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
        if (!start(initialVelocityPixelsPerSecond, releasedAtNanos, sequence)) return false
        // An UP with unchanged pointer coordinates still has elapsed momentum by this
        // frame. Waiting for another callback would leave the release frame empty.
        // Input delivered after this VSYNC instead starts on the next available frame.
        if (frameTimeNanos > releasedAtNanos) {
            doFrame(frameTimeNanos, frameTimelineVsyncId, expectedPresentationTimeNanos)
        }
        return running
    }

    fun stop() {
        if (!running) return
        running = false
        previousFrameNanos = 0L
        motionSequence = 0L
        frameScheduler.cancel()
        finished()
    }

    private fun doFrame(
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) {
        if (!running) return
        // Re-arm before synchronous input/content/graphics work so that work cannot
        // delay requesting the next display slot. Every stop path cancels this arm.
        frameScheduler.post()
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
                    stop()
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
