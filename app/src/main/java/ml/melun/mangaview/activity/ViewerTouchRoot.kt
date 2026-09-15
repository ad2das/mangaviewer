package ml.melun.mangaview.activity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

internal class ViewerTouchRoot(
    context: Context,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTracker = SurfaceTapTracker(touchSlop)
    private val doubleTapTimeoutMillis = ViewConfiguration.getDoubleTapTimeout().toLong()
    // A plain main handler commits the tap even before this view is attached to a window.
    private val tapHandler = Handler(Looper.getMainLooper())
    private var lastTapMillis = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingTap: Runnable? = null
    var onSurfaceTap: () -> Unit = {}
    var onSurfaceDoubleTap: (Float, Float) -> Unit = { _, _ -> }
    var excludesSurfaceTap: (Float, Float) -> Boolean = { _, _ -> false }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        observe(event)
        return handled
    }

    override fun onDetachedFromWindow() {
        cancelPendingTap()
        super.onDetachedFromWindow()
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A new gesture retracts an uncommitted single tap so a following drag cannot
                // reveal the chrome mid-scroll.
                cancelPendingTap()
                tapTracker.begin(
                    event.x,
                    event.y,
                    eligible = !excludesSurfaceTap(event.x, event.y),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                tapTracker.move(event.x, event.y)
                if (!tapTracker.tapEligible) cancelPendingTap()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapTracker.cancel()
                cancelPendingTap()
            }
            MotionEvent.ACTION_UP -> if (tapTracker.release(event.x, event.y)) tapped(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> {
                tapTracker.cancel()
                cancelPendingTap()
            }
        }
    }

    /**
     * A single tap is committed only after the double-tap window closes, because the same gesture
     * is also the magnification toggle. Two taps inside the window and the slop zoom instead.
     */
    private fun tapped(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMillis <= doubleTapTimeoutMillis &&
            abs(x - lastTapX) <= touchSlop && abs(y - lastTapY) <= touchSlop) {
            cancelPendingTap()
            lastTapMillis = 0L
            onSurfaceDoubleTap(x, y)
            return
        }
        lastTapMillis = now
        lastTapX = x
        lastTapY = y
        cancelPendingTap()
        val commit = Runnable {
            pendingTap = null
            onSurfaceTap()
        }
        pendingTap = commit
        tapHandler.postDelayed(commit, doubleTapTimeoutMillis)
    }

    private fun cancelPendingTap() {
        pendingTap?.let(tapHandler::removeCallbacks)
        pendingTap = null
    }
}

internal class SurfaceTapTracker(
    private val touchSlop: Float,
) {
    private var downX = 0f
    private var downY = 0f
    private var eligible = false

    val tapEligible: Boolean get() = eligible

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
