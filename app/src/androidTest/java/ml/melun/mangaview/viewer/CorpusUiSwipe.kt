package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Preserve the real 55-step navigation path without a dispatch barrier for every MOVE. */
internal fun injectCorpusUiSwipe(instrumentation: Instrumentation, bounds: Rect): Boolean {
    val steps = 55
    val downTime = SystemClock.uptimeMillis()
    val x = bounds.centerX().toFloat()
    val startY = (bounds.bottom - bounds.height() / 5).toFloat()
    val endY = (bounds.top + bounds.height() / 5).toFloat()
    fun inject(action: Int, y: Float, sync: Boolean): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try { instrumentation.uiAutomation.injectInputEvent(event, sync) } finally { event.recycle() }
    }
    if (!inject(MotionEvent.ACTION_DOWN, startY, true)) return false
    var ended = false
    try {
        val moveStart = SystemClock.uptimeMillis()
        for (step in 1 until steps) {
            val remaining = moveStart + step * 5L - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            if (!inject(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / steps, false)) return false
        }
        val remaining = moveStart + steps * 5L - SystemClock.uptimeMillis()
        if (remaining > 0) SystemClock.sleep(remaining)
        ended = inject(MotionEvent.ACTION_UP, endY, true)
        return ended
    } finally {
        if (!ended) inject(MotionEvent.ACTION_CANCEL, endY, true)
    }
}
