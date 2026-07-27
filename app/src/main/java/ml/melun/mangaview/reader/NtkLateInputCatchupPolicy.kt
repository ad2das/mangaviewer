package ml.melun.mangaview.reader

/**
 * Decides whether a physical MOVE arrived just after the producer observed the previous drag
 * target, while its only outstanding callback is already reserved for the next display vsync.
 *
 * This is deliberately independent from image readiness and test state. It only closes a narrow
 * same-vsync input race; ordinary Choreographer cadence remains authoritative.
 */
internal object NtkLateInputCatchupPolicy {
    fun shouldPost(
        renderRunning: Boolean,
        directSurfaceReady: Boolean,
        callbackPosted: Boolean,
        callbackHadAdmission: Boolean,
        catchupPosted: Boolean,
        newPhysicalGesture: Boolean,
        pointerDown: Boolean,
        dragging: Boolean,
        targetRevision: Long,
        callbackObservedTargetRevision: Long,
        callbackObservedAtNanos: Long,
        nowNanos: Long,
        refreshPeriodNanos: Long
    ): Boolean {
        if (!renderRunning || !directSurfaceReady || !callbackPosted || catchupPosted) return false
        // An admitted callback normally owns the current display period. The sole exception is a
        // MOVE from a newly started physical gesture that the reserved successor has not observed:
        // waiting for that successor made the first moving frame land 28-34 ms after the last
        // fling frame on host-GPU emulators. Gesture identity keeps this exception one-shot.
        if (callbackHadAdmission && !newPhysicalGesture) return false
        if (!pointerDown || !dragging) return false
        if (targetRevision <= 0L || targetRevision == callbackObservedTargetRevision) return false
        if (callbackObservedAtNanos <= 0L || nowNanos < callbackObservedAtNanos) return false
        if (refreshPeriodNanos <= 0L) return false

        val catchupWindowNanos = if (newPhysicalGesture) {
            // UiAutomator and real repeated swipes can deliver the first changing MOVE almost one
            // period after the prior fling frame. This remains one-shot by gesture identity, so a
            // slightly wider window fixes the boundary cadence without admitting every MOVE.
            (refreshPeriodNanos + refreshPeriodNanos / 4L).coerceAtLeast(1L)
        } else {
            (refreshPeriodNanos / 2L).coerceAtLeast(1L)
        }
        return nowNanos - callbackObservedAtNanos <= catchupWindowNanos
    }
}
