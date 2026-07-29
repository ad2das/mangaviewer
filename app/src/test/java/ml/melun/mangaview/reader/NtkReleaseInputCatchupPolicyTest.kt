package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkReleaseInputCatchupPolicyTest {
    private val refreshPeriodNanos = 16_666_667L
    private val observedAtNanos = 1_000_000_000L

    @Test
    fun releaseMotionAfterObservedCallbackReplacesItsStaleSuccessor() {
        assertTrue(shouldPost(releaseMoved = true, scrollerFinished = false))
    }

    @Test
    fun newlyStartedFlingIsDemandEvenWhenReleaseCoordinateDidNotMove() {
        assertTrue(shouldPost(releaseMoved = false, scrollerFinished = false))
    }

    @Test
    fun stationaryReleaseAndUnadmittedMutationDoNotCreateFrames() {
        assertFalse(shouldPost(releaseMoved = false, scrollerFinished = true))
        assertFalse(
            shouldPost(
                releaseMoved = true,
                scrollerFinished = false,
                hasAdmittedFrame = false,
            )
        )
    }

    @Test
    fun oldOrStillPointerOwnedCallbacksKeepNormalCadence() {
        assertFalse(
            shouldPost(
                releaseMoved = true,
                scrollerFinished = false,
                nowNanos = observedAtNanos + refreshPeriodNanos + 1L,
            )
        )
        assertFalse(
            shouldPost(
                releaseMoved = true,
                scrollerFinished = false,
                pointerDown = true,
            )
        )
    }

    private fun shouldPost(
        releaseMoved: Boolean,
        scrollerFinished: Boolean,
        hasAdmittedFrame: Boolean = true,
        pointerDown: Boolean = false,
        nowNanos: Long = observedAtNanos + 400_000L,
    ): Boolean = NtkReleaseInputCatchupPolicy.shouldPost(
        renderRunning = true,
        directSurfaceReady = true,
        callbackPosted = true,
        catchupPosted = false,
        pointerDown = pointerDown,
        hasAdmittedFrame = hasAdmittedFrame,
        releaseMoved = releaseMoved,
        scrollerFinished = scrollerFinished,
        callbackObservedAtNanos = observedAtNanos,
        nowNanos = nowNanos,
        refreshPeriodNanos = refreshPeriodNanos,
    )
}
