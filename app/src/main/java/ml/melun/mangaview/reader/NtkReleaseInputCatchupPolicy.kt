package ml.melun.mangaview.reader

/**
 * Closes the producer-vsync race where ACTION_UP starts a real fling just after the already-posted
 * direct callback observed the final drag target.
 *
 * This never invents an intermediate position. It can replace one pending callback only when the
 * release has already admitted a real pixel mutation (or an active OverScroller step) and the
 * prior callback observation is at most one physical refresh old.
 */
internal object NtkReleaseInputCatchupPolicy {
    fun shouldPost(
        renderRunning: Boolean,
        directSurfaceReady: Boolean,
        callbackPosted: Boolean,
        catchupPosted: Boolean,
        pointerDown: Boolean,
        hasAdmittedFrame: Boolean,
        releaseMoved: Boolean,
        scrollerFinished: Boolean,
        callbackObservedAtNanos: Long,
        nowNanos: Long,
        refreshPeriodNanos: Long,
    ): Boolean {
        if (!renderRunning || !directSurfaceReady || !callbackPosted || catchupPosted) return false
        if (pointerDown || !hasAdmittedFrame) return false
        if (!releaseMoved && scrollerFinished) return false
        if (callbackObservedAtNanos <= 0L || nowNanos < callbackObservedAtNanos) return false
        if (refreshPeriodNanos <= 0L) return false
        return nowNanos - callbackObservedAtNanos <= refreshPeriodNanos
    }
}
