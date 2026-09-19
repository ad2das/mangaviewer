package ml.melun.mangaview.viewer.runtime

import android.view.MotionEvent
import kotlin.math.hypot

/** Pinch scale and anchor math, independent of drag/fling ownership. */
internal class ViewerPinchGesture(
    private val zoom: ViewerZoomState,
    private val applyZoom: () -> Unit,
    private val emitSyntheticScroll: (Double) -> Boolean,
) {
    var active = false
        private set
    private var baselineSpan = 0f

    fun begin(event: MotionEvent) {
        active = true
        baselineSpan = pinchSpan(event)
    }

    fun rebase(event: MotionEvent, excluded: Int) {
        baselineSpan = pinchSpanExcluding(event, excluded)
    }

    /** Two pointers own the gesture: their span changes the scale and their midpoint anchors it. */
    fun update(event: MotionEvent) {
        if (event.pointerCount < 2) {
            end()
            return
        }
        val span = pinchSpan(event)
        if (span <= 0f) return
        if (baselineSpan > 0f) {
            val focusX = (event.getX(0) + event.getX(1)) / 2f
            val focusY = (event.getY(0) + event.getY(1)) / 2f
            val scroll = zoom.pinch(span / baselineSpan, focusX, focusY)
            applyZoom()
            if (scroll != 0.0) emitSyntheticScroll(scroll)
        }
        baselineSpan = span
    }

    fun end() {
        if (!active) return
        active = false
        baselineSpan = 0f
    }

    private fun pinchSpan(event: MotionEvent): Float = pinchSpanExcluding(event, -1)

    private fun pinchSpanExcluding(event: MotionEvent, excluded: Int): Float {
        var first = -1
        var second = -1
        for (index in 0 until event.pointerCount) {
            if (index == excluded) continue
            if (first < 0) first = index else { second = index; break }
        }
        if (first < 0 || second < 0) return 0f
        // Local coordinates arrive pre-divided by the live view scale; multiplying back gives the
        // on-screen span the fingers actually describe. Without it the measured span shrinks as
        // magnification grows, so a steady pinch-in can never restore fit-width.
        return hypot(event.getX(second) - event.getX(first),
            event.getY(second) - event.getY(first)) * zoom.scale
    }

}
