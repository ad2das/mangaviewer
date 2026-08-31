package ml.melun.mangaview.activity

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

internal class ViewerTouchRoot(
    context: Context,
) : FrameLayout(context) {
    private val tapTracker = SurfaceTapTracker(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    var onSurfaceTap: () -> Unit = {}
    var excludesSurfaceTap: (Float, Float) -> Boolean = { _, _ -> false }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        observe(event)
        return handled
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapTracker.begin(
                event.x,
                event.y,
                eligible = !excludesSurfaceTap(event.x, event.y),
            )
            MotionEvent.ACTION_MOVE -> tapTracker.move(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN -> tapTracker.cancel()
            MotionEvent.ACTION_UP -> if (tapTracker.release(event.x, event.y)) onSurfaceTap()
            MotionEvent.ACTION_CANCEL -> tapTracker.cancel()
        }
    }
}

internal class SurfaceTapTracker(
    private val touchSlop: Float,
) {
    private var downX = 0f
    private var downY = 0f
    private var eligible = false

    fun begin(x: Float, y: Float, eligible: Boolean) {
        downX = x
        downY = y
        this.eligible = eligible
    }

    fun move(x: Float, y: Float) {
        if (eligible && movedBeyondTap(x, y)) eligible = false
    }

    fun release(x: Float, y: Float): Boolean {
        move(x, y)
        return eligible.also { eligible = false }
    }

    fun cancel() {
        eligible = false
    }

    private fun movedBeyondTap(x: Float, y: Float): Boolean =
        abs(x - downX) > touchSlop || abs(y - downY) > touchSlop
}
