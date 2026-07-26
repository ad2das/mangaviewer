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
        pointerDown: Boolean,
        dragging: Boolean,
        targetRevision: Long,
        callbackObservedTargetRevision: Long,
        callbackObservedAtNanos: Long,
        nowNanos: Long,
        refreshPeriodNanos: Long
    ): Boolean {
        if (!renderRunning || !directSurfaceReady || !callbackPosted || catchupPosted) return false
        if (callbackHadAdmission) return false
        if (!pointerDown || !dragging) return false
        if (targetRevision <= 0L || targetRevision == callbackObservedTargetRevision) return false
        if (callbackObservedAtNanos <= 0L || nowNanos < callbackObservedAtNanos) return false
        if (refreshPeriodNanos <= 0L) return false

        val catchupWindowNanos = (refreshPeriodNanos / 2L).coerceAtLeast(1L)
        return nowNanos - callbackObservedAtNanos <= catchupWindowNanos
    }
}
