package ml.melun.mangaview.viewer.runtime

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp

internal class ViewerFlingDriver(
    choreographer: Choreographer,
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
    private val frameScheduler = ViewerVsyncScheduler(choreographer, ::doFrame)
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
        // The release is already being handled on a Choreographer frame. Use that timestamp as
        // the integration origin so the next callback advances immediately instead of consuming
        // an empty priming frame between drag and fling.
        previousFrameNanos = precedingFrameNanos
        motionSequence = sequence
        running = true
        frameScheduler.post()
        return true
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
        val previous = previousFrameNanos
        previousFrameNanos = frameTimeNanos
        if (previous > 0L) {
            val elapsedSeconds = (frameTimeNanos - previous).coerceAtLeast(0L) / NANOS_PER_SECOND
            if (elapsedSeconds > 0.0) {
                val decay = exp(-DECAY_PER_SECOND * elapsedSeconds)
                val displacement = velocity * (1.0 - decay) / DECAY_PER_SECOND
                if (!emit(
                        displacement,
                        velocity,
                        frameTimeNanos,
                        expectedPresentationTimeNanos,
                        frameTimelineVsyncId,
                    )) {
                    stop()
                    return
                }
                frameObserved(motionSequence, frameTimeNanos)
                velocity *= decay
            }
        }
        if (abs(velocity) < MINIMUM_VELOCITY) {
            stop()
        } else {
            frameScheduler.post()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MINIMUM_VELOCITY = 24.0
        const val MAXIMUM_VELOCITY = 24_000.0
        const val DECAY_PER_SECOND = 4.2
    }
}
