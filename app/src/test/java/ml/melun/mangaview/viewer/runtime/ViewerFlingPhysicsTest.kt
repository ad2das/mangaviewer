package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFlingPhysicsTest {
    @Test
    fun normalFrameUsesItsWholeElapsedTime() {
        val step = ViewerFlingPhysics.advance(6_000.0, 1.0 / 60.0)

        assertTrue(step.displacementPixels in 95.0..100.0)
        assertTrue(step.velocityPixelsPerSecond in 5_590.0..5_600.0)
    }

    @Test
    fun stalledFrameDecaysInRealTimeWithoutOneFrameTeleport() {
        val step = ViewerFlingPhysics.advance(24_000.0, 1.0)

        assertTrue(step.displacementPixels in 740.0..750.0)
        assertTrue(step.velocityPixelsPerSecond < 400.0)
    }

    @Test
    fun zeroElapsedTimeDoesNotMove() {
        val step = ViewerFlingPhysics.advance(1_234.0, 0.0)

        assertEquals(0.0, step.displacementPixels, 0.0)
        assertEquals(1_234.0, step.velocityPixelsPerSecond, 0.0)
    }
}
