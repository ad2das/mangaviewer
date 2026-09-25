package ml.melun.mangaview.viewer.runtime

import android.os.Trace
import ml.melun.mangaview.viewer.FixedPx

/** Emits viewer scroll steps with the trace segment that belongs to each dispatch origin. */
internal class ViewerScrollEmitter(
    private val sink: ViewerSurfaceSink,
    private val inputTrace: ViewerInputTraceLedger,
) {
    fun emitTouch(
        deltaPixels: Double,
        fixedDelta: FixedPx,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
        segment: ViewerInputTraceLedger.Segment?,
    ): Boolean {
        if (deltaPixels == 0.0) return false
        return viewerInputTrace({ viewerSegmentTraceName(segment) }) {
            sink.userScroll(
                fixedDelta,
                velocityPixelsPerSecond.toFloat(),
                frameTimeNanos,
                frameTimelineVsyncId,
                expectedPresentationTimeNanos,
            )
        }
    }

    /** Fling steps are frame-synthetic and must never masquerade as original touch samples. */
    fun emitFling(
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ): Boolean = emitTouch(deltaPixels, FixedPx.fromPixels(deltaPixels), velocityPixelsPerSecond,
        frameTimeNanos, expectedPresentationTimeNanos, frameTimelineVsyncId,
        if (Trace.isEnabled()) inputTrace.synthetic(deltaPixels) else null)
}
