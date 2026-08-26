package ml.melun.mangaview.reader

/**
 * Decides whether a physical MOVE arrived just after the producer observed the previous drag
 * target, while its only outstanding callback is already reserved for the next display vsync.
 *
 * This is deliberately independent from image readiness and test state. It closes both the narrow
 * same-vsync input race and an overdue producer reservation. In the latter case the pending
 * callback is replaced, not supplemented, so there is still at most one presentation owner.
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
        if (!pointerDown || !dragging) return false
        if (targetRevision <= 0L || targetRevision == callbackObservedTargetRevision) return false
        if (callbackObservedAtNanos <= 0L || nowNanos < callbackObservedAtNanos) return false
        if (refreshPeriodNanos <= 0L) return false

        val callbackAgeNanos = nowNanos - callbackObservedAtNanos
        // A successful callback normally owns its display period. Once its reserved successor is
        // already a full refresh old, however, waiting longer is no longer cadence: it is a lost
        // producer wakeup. A real MOVE can replace that exact reservation on any reader/runtime.
        // directLateInputCatchup cancels the delayed callback before consuming its token, while
        // catchupPosted coalesces further MOVE events until that replacement runs.
        if (callbackAgeNanos >= refreshPeriodNanos) return true

        // Within the normal display period, retain the narrow same-vsync race rule. An admitted
        // frame is replaced only for the first MOVE of a new physical gesture.
        if (callbackHadAdmission && !newPhysicalGesture) return false

        val catchupWindowNanos = if (newPhysicalGesture) {
            // UiAutomator and real repeated swipes can deliver the first changing MOVE almost one
            // period after the prior fling frame. This remains one-shot by gesture identity, so a
            // slightly wider window fixes the boundary cadence without admitting every MOVE.
            (refreshPeriodNanos + refreshPeriodNanos / 4L).coerceAtLeast(1L)
        } else {
            (refreshPeriodNanos / 2L).coerceAtLeast(1L)
        }
        return callbackAgeNanos <= catchupWindowNanos
    }
}
