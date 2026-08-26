package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkLateInputCatchupPolicyTest {
    private val refreshPeriodNanos = 16_666_667L
    private val observedAtNanos = 1_000_000_000L

    @Test
    fun lateMoveAfterObservedCallbackGetsOneSameVsyncCatchup() {
        assertTrue(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + 350_000L
            )
        )
    }

    @Test
    fun callbackThatAlreadyObservedMoveNeedsNoCatchup() {
        assertFalse(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 8L,
                nowNanos = observedAtNanos + 350_000L
            )
        )
    }

    @Test
    fun callbackThatSubmittedPixelsKeepsNormalVsyncCadence() {
        assertFalse(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + 350_000L,
                callbackHadAdmission = true
            )
        )
    }

    @Test
    fun overdueReservedSuccessorIsReplacedForSamePhysicalGesture() {
        assertTrue(
            shouldPost(
                targetRevision = 9L,
                callbackObservedTargetRevision = 8L,
                nowNanos = observedAtNanos + refreshPeriodNanos,
                callbackHadAdmission = true
            )
        )
    }

    @Test
    fun overdueUnadmittedReservationIsAlsoRecovered() {
        assertTrue(
            shouldPost(
                targetRevision = 9L,
                callbackObservedTargetRevision = 8L,
                nowNanos = observedAtNanos + refreshPeriodNanos * 3L
            )
        )
    }

    @Test
    fun firstMoveOfNewGestureCanReplaceAdmittedPriorFrameSuccessor() {
        assertTrue(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + refreshPeriodNanos * 3L / 4L,
                callbackHadAdmission = true,
                newPhysicalGesture = true
            )
        )
    }

    @Test
    fun oldInputDoesNotReplaceNormalNextVsyncCallback() {
        assertFalse(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + refreshPeriodNanos / 2L + 1L
            )
        )
    }

    @Test
    fun duplicateCatchupAndInactiveMotionAreRejected() {
        assertFalse(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + 350_000L,
                catchupPosted = true
            )
        )
        assertFalse(
            shouldPost(
                targetRevision = 8L,
                callbackObservedTargetRevision = 7L,
                nowNanos = observedAtNanos + 350_000L,
                dragging = false
            )
        )
    }

    private fun shouldPost(
        targetRevision: Long,
        callbackObservedTargetRevision: Long,
        nowNanos: Long,
        catchupPosted: Boolean = false,
        callbackHadAdmission: Boolean = false,
        newPhysicalGesture: Boolean = false,
        dragging: Boolean = true
    ): Boolean {
        return NtkLateInputCatchupPolicy.shouldPost(
            renderRunning = true,
            directSurfaceReady = true,
            callbackPosted = true,
            callbackHadAdmission = callbackHadAdmission,
            catchupPosted = catchupPosted,
            newPhysicalGesture = newPhysicalGesture,
            pointerDown = true,
            dragging = dragging,
            targetRevision = targetRevision,
            callbackObservedTargetRevision = callbackObservedTargetRevision,
            callbackObservedAtNanos = observedAtNanos,
            nowNanos = nowNanos,
            refreshPeriodNanos = refreshPeriodNanos
        )
    }
}
