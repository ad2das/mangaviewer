package ml.melun.mangaview.activity

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import kotlin.math.abs
import ml.melun.mangaview.viewer.runtime.ViewerChromeState
import ml.melun.mangaview.viewer.runtime.ViewerCanvasView

internal class ViewerChromeController(
    private val activity: ViewerActivity,
    private val surface: ViewerCanvasView,
    private val snapshot: () -> ViewerChromeState?,
    private val actions: Actions,
) {
    data class Actions(
        val back: () -> Unit,
        val previous: () -> Unit,
        val episodes: () -> Unit,
        val next: () -> Unit,
        val bookmark: () -> Unit,
    )

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val top = LinearLayout(activity)
    private val bottom = LinearLayout(activity)
    private val title = label(18f, Typeface.BOLD)
    private val page = label(14f)
    private val previous = button("이전", actions.previous)
    private val episodes = button("회차", actions.episodes)
    private val next = button("다음", actions.next)
    private val gestureRelay = ChromeGestureRelay(
        surface = surface,
        touchSlop = touchSlop.toFloat(),
        hideWithoutDetachingTouchTarget = ::hideWithoutDetachingTouchTarget,
        finishHiddenGesture = ::finishHiddenGesture,
    )
    private var showing = false

    val visible: Boolean get() = showing

    fun install(root: FrameLayout) {
        configureBars()
        root.addView(top, barParams(Gravity.TOP))
        root.addView(bottom, barParams(Gravity.BOTTOM))
        setVisible(false)
    }

    fun toggle() {
        if (visible) {
            setVisible(false)
        } else {
            update(snapshot())
            setVisible(true)
        }
    }

    fun refresh() {
        if (visible) update(snapshot())
    }

    fun contains(x: Float, y: Float): Boolean = visible &&
        (top.containsPoint(x, y) || bottom.containsPoint(x, y))

    private fun configureBars() {
        listOf(top, bottom).forEach { bar ->
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setPadding(dp(8), dp(6), dp(8), dp(6))
            bar.setBackgroundColor(CHROME_BACKGROUND)
        }
        val back = button("‹", actions.back)
        top.addView(back, itemParams(48))
        top.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(page, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(button("책갈피", actions.bookmark), itemParams(72))
        bottom.addView(previous, itemParams(58))
        bottom.addView(episodes, itemParams(58))
        bottom.addView(next, itemParams(58))
        installDragForwarding(top, bottom, back, title, page, previous, episodes, next)
        installDragForwarding(bottom.getChildAt(1))
    }

    private fun update(state: ViewerChromeState?) {
        title.text = state?.title ?: "회차 불러오는 중"
        page.text = state?.let { "${it.pageNumber} / ${it.pageCount}" } ?: "– / –"
        previous.enable(state?.previousEpisodeId != null)
        next.enable(state?.nextEpisodeId != null)
        episodes.enable(state != null)
    }

    private fun setVisible(show: Boolean) {
        showing = show
        top.alpha = 1f
        bottom.alpha = 1f
        val value = if (show) View.VISIBLE else View.GONE
        top.visibility = value
        bottom.visibility = value
    }

    private fun label(size: Float, style: Int = Typeface.NORMAL) = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun button(text: String, click: () -> Unit) = label(14f, Typeface.BOLD).apply {
        this.text = text
        isClickable = true
        isFocusable = true
        setOnClickListener { if (tag != false) click() }
        setBackgroundColor(BUTTON_BACKGROUND)
    }

    private fun installDragForwarding(vararg views: View) {
        views.forEach { view -> view.setOnTouchListener(::forwardToolbarTouch) }
    }

    private fun forwardToolbarTouch(source: View, event: MotionEvent): Boolean {
        return gestureRelay.onTouch(source, event)
    }

    private fun hideWithoutDetachingTouchTarget() {
        showing = false
        top.alpha = 0f
        bottom.alpha = 0f
    }

    private fun finishHiddenGesture() {
        top.visibility = View.GONE
        bottom.visibility = View.GONE
        top.alpha = 1f
        bottom.alpha = 1f
    }

    private fun TextView.enable(enabled: Boolean) {
        tag = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun View.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    private fun barParams(gravity: Int) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(56),
        gravity,
    )

    private fun itemParams(widthDp: Int) = LinearLayout.LayoutParams(dp(widthDp), dp(44)).apply {
        marginStart = dp(4)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val CHROME_BACKGROUND = 0xEE111111.toInt()
        const val BUTTON_BACKGROUND = 0xFF252525.toInt()
    }
}

internal class ChromeGestureRelay(
    private val surface: View,
    touchSlop: Float,
    private val hideWithoutDetachingTouchTarget: () -> Unit,
    private val finishHiddenGesture: () -> Unit,
) {
    private val axisLock = VerticalGestureAxisLock(touchSlop)
    private val pendingEvents = ArrayDeque<MotionEvent>()
    private var forwarding = false

    fun onTouch(source: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(source, event)
            MotionEvent.ACTION_POINTER_DOWN -> beginMultiPointerForwarding(source, event)
            MotionEvent.ACTION_MOVE -> continueGesture(source, event)
            MotionEvent.ACTION_POINTER_UP -> relayOrBuffer(source, event)
            MotionEvent.ACTION_UP -> finish(source, event)
            MotionEvent.ACTION_CANCEL -> cancel(source, event)
        }
        return true
    }

    private fun begin(source: View, event: MotionEvent) {
        recyclePending()
        forwarding = false
        axisLock.begin(event.rawX, event.rawY)
        addPending(copyForSurface(source, event))
    }

    private fun beginMultiPointerForwarding(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            return
        }
        if (axisLock.currentRoute == VerticalGestureAxisLock.Route.REJECT) return
        addPending(copyForSurface(source, event))
        startForwarding()
    }

    private fun continueGesture(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            return
        }
        addPending(copyForSurface(source, event))
        when (axisLock.classify(event.rawX, event.rawY)) {
            VerticalGestureAxisLock.Route.FORWARD -> startForwarding()
            VerticalGestureAxisLock.Route.REJECT -> recyclePending()
            VerticalGestureAxisLock.Route.PENDING -> Unit
        }
    }

    private fun relayOrBuffer(source: View, event: MotionEvent) {
        if (forwarding) dispatch(copyForSurface(source, event))
        else if (axisLock.currentRoute != VerticalGestureAxisLock.Route.REJECT) {
            addPending(copyForSurface(source, event))
        }
    }

    private fun finish(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            endForwarding()
            return
        }
        addPending(copyForSurface(source, event))
        when (axisLock.classify(event.rawX, event.rawY)) {
            VerticalGestureAxisLock.Route.FORWARD -> {
                startForwarding()
                endForwarding()
            }
            VerticalGestureAxisLock.Route.PENDING -> {
                recyclePending()
                axisLock.reset()
                if (event.isInside(source)) source.performClick()
            }
            VerticalGestureAxisLock.Route.REJECT -> {
                recyclePending()
                axisLock.reset()
            }
        }
    }

    private fun cancel(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            endForwarding()
        } else {
            recyclePending()
            axisLock.reset()
        }
    }

    private fun startForwarding() {
        forwarding = true
        hideWithoutDetachingTouchTarget()
        while (pendingEvents.isNotEmpty()) dispatch(pendingEvents.removeFirst())
    }

    private fun endForwarding() {
        forwarding = false
        axisLock.reset()
        finishHiddenGesture()
    }

    private fun copyForSurface(source: View, event: MotionEvent): MotionEvent {
        val sourceLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        surface.getLocationOnScreen(surfaceLocation)
        return MotionEvent.obtain(event).apply {
            offsetLocation(
                (sourceLocation[0] - surfaceLocation[0]).toFloat(),
                (sourceLocation[1] - surfaceLocation[1]).toFloat(),
            )
        }
    }

    private fun dispatch(event: MotionEvent) {
        try {
            surface.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun addPending(event: MotionEvent) {
        if (pendingEvents.size >= MAX_PENDING_EVENTS) {
            val down = pendingEvents.removeFirst()
            pendingEvents.removeFirst().recycle()
            pendingEvents.addFirst(down)
        }
        pendingEvents.addLast(event)
    }

    private fun recyclePending() {
        while (pendingEvents.isNotEmpty()) pendingEvents.removeFirst().recycle()
    }

    private fun MotionEvent.isInside(view: View): Boolean =
        x >= 0f && y >= 0f && x < view.width && y < view.height

    private companion object {
        const val MAX_PENDING_EVENTS = 32
    }
}

internal class VerticalGestureAxisLock(
    private val touchSlop: Float,
) {
    enum class Route { PENDING, FORWARD, REJECT }

    private var downX = 0f
    private var downY = 0f
    private var route = Route.PENDING
    val currentRoute: Route get() = route

    fun begin(x: Float, y: Float) {
        downX = x
        downY = y
        route = Route.PENDING
    }

    fun classify(x: Float, y: Float): Route {
        if (route != Route.PENDING) return route
        val horizontal = abs(x - downX)
        val vertical = abs(y - downY)
        if (horizontal <= touchSlop && vertical <= touchSlop) return route
        route = if (vertical >= horizontal) Route.FORWARD else Route.REJECT
        return route
    }

    fun reset() {
        route = Route.PENDING
    }
}
