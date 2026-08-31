package ml.melun.mangaview.viewer.runtime

import kotlin.math.exp
import kotlin.math.min

internal data class FlingStep(
    val displacementPixels: Double,
    val velocityPixelsPerSecond: Double,
)

/** Time-based decay without converting a dropped frame into one visible teleport. */
internal object ViewerFlingPhysics {
    fun advance(velocityPixelsPerSecond: Double, elapsedSeconds: Double): FlingStep {
        if (elapsedSeconds <= 0.0) return FlingStep(0.0, velocityPixelsPerSecond)
        val visualElapsed = min(elapsedSeconds, MAXIMUM_VISUAL_STEP_SECONDS)
        val visualDecay = exp(-DECAY_PER_SECOND * visualElapsed)
        val physicalDecay = exp(-DECAY_PER_SECOND * elapsedSeconds)
        return FlingStep(
            displacementPixels = velocityPixelsPerSecond *
                (1.0 - visualDecay) / DECAY_PER_SECOND,
            velocityPixelsPerSecond = velocityPixelsPerSecond * physicalDecay,
        )
    }

    private const val DECAY_PER_SECOND = 4.2
    private const val MAXIMUM_VISUAL_STEP_SECONDS = 1.0 / 30.0
}
