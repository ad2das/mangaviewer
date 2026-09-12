package ml.melun.mangaview.activity

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

internal class ViewerChromeController(
    private val activity: android.content.Context,
    private val surface: View,
    private val snapshot: () -> ViewerChromeState?,
    private val actions: Actions,
) {
    data class Actions(
        val back: () -> Unit,
        val previous: () -> Unit,
        val episodes: () -> Unit,
        val next: () -> Unit,
        val bookmark: () -> Unit,
        val split: () -> Unit,
    )

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val top = LinearLayout(activity)
    private val bottom = LinearLayout(activity)
    private val title = label(16f, Typeface.BOLD).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(12), 0, dp(8), 0)
    }
    private val page = label(13f, Typeface.BOLD).apply {
        background = roundedDrawable(0x28FFFFFF.toInt(), dp(12).toFloat())
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val previous = button("이전", actions.previous)
    private val episodes = button("회차", actions.episodes)
    private val next = button("다음", actions.next, isAccent = true)
    private val bookmark = button("책갈피", actions.bookmark)
    private val split = button("나눔", actions.split).apply { contentDescription = "양면 나눠보기" }
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
            bar.setPadding(dp(14), dp(8), dp(14), dp(8))
            bar.setBackgroundColor(CHROME_BACKGROUND)
        }
        val back = button("‹", actions.back, isCircular = true)
        top.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(split, LinearLayout.LayoutParams(dp(58), dp(44)).apply { marginStart = dp(6) })

        bottom.addView(page, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        bottom.addView(bookmark, itemParams(68))
        bottom.addView(previous, itemParams(58))
        bottom.addView(episodes, itemParams(58))
        bottom.addView(next, itemParams(58))

        installDragForwarding(top, bottom, back, title, page, bookmark, previous, episodes, next, split)
    }

    private fun update(state: ViewerChromeState?) {
        title.text = state?.title ?: "회차 불러오는 중"
        page.text = state?.let { "${it.pageNumber} / ${it.pageCount}" } ?: "– / –"
        previous.enable(state?.previousEpisodeId != null)
        next.enable(state?.nextEpisodeId != null)
        episodes.enable(state != null)
        split.enable(state != null)
        accent(split, state?.splitMode == true)
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

    private fun button(text: String, click: () -> Unit, isCircular: Boolean = false, isAccent: Boolean = false) = label(14f, Typeface.BOLD).apply {
        this.text = text
        isClickable = true
        isFocusable = true
        setOnClickListener { if (tag != false) click() }
        val radius = if (isCircular) dp(22).toFloat() else dp(12).toFloat()
        val bg = if (isAccent) ACCENT_BUTTON_BACKGROUND else BUTTON_BACKGROUND
        val border = if (isAccent) ACCENT_BORDER else BUTTON_BORDER
        background = roundedDrawable(bg, radius, dp(1), border)
    }

    private fun roundedDrawable(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun accent(view: TextView, enabled: Boolean) {
        view.background = roundedDrawable(
            if (enabled) ACCENT_BUTTON_BACKGROUND else BUTTON_BACKGROUND,
            dp(12).toFloat(), dp(1), if (enabled) ACCENT_BORDER else BUTTON_BORDER,
        )
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
        dp(64),
        gravity,
    )

    private fun itemParams(widthDp: Int) = LinearLayout.LayoutParams(dp(widthDp), dp(42)).apply {
        marginStart = dp(5)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val CHROME_BACKGROUND = 0xF5080A10.toInt()
        const val BUTTON_BACKGROUND = 0xFF181C26.toInt()
        const val BUTTON_BORDER = 0x33FFFFFF.toInt()
        const val ACCENT_BUTTON_BACKGROUND = 0xFF7C5CFF.toInt()
        const val ACCENT_BORDER = 0x669080FF.toInt()
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
        if (forwarding) {
            dispatch(copyForSurface(source, event))
        } else if (axisLock.currentRoute != VerticalGestureAxisLock.Route.REJECT) {
            addPending(copyForSurface(source, event))
        }
    }

    private fun finish(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            forwarding = false
            recyclePending()
            finishHiddenGesture()
            return
        }
        if (axisLock.currentRoute == VerticalGestureAxisLock.Route.PENDING) {
            // The relay consumes DOWN while watching for a drag, so a stationary tap
            // must be replayed as a click or the button listener would never fire.
            source.performClick()
        }
        recyclePending()
    }

    private fun cancel(source: View, event: MotionEvent) {
        if (forwarding) {
            dispatch(copyForSurface(source, event))
            forwarding = false
            recyclePending()
            finishHiddenGesture()
            return
        }
        recyclePending()
    }

    private fun startForwarding() {
        forwarding = true
        hideWithoutDetachingTouchTarget()
        while (pendingEvents.isNotEmpty()) {
            val event = pendingEvents.removeFirst()
            dispatch(event)
            event.recycle()
        }
    }

    private fun addPending(event: MotionEvent) {
        pendingEvents.addLast(event)
    }

    private fun dispatch(event: MotionEvent) {
        surface.dispatchTouchEvent(event)
    }

    private fun copyForSurface(source: View, event: MotionEvent): MotionEvent {
        val location = IntArray(2)
        source.getLocationOnScreen(location)
        val surfaceLocation = IntArray(2)
        surface.getLocationOnScreen(surfaceLocation)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(
            (location[0] - surfaceLocation[0]).toFloat(),
            (location[1] - surfaceLocation[1]).toFloat(),
        )
        return copy
    }

    private fun recyclePending() {
        while (pendingEvents.isNotEmpty()) {
            pendingEvents.removeFirst().recycle()
        }
    }
}

internal class VerticalGestureAxisLock(
    private val touchSlop: Float,
) {
    enum class Route { PENDING, FORWARD, REJECT }

    var currentRoute: Route = Route.PENDING
        private set

    private var downX = 0f
    private var downY = 0f

    fun begin(rawX: Float, rawY: Float) {
        downX = rawX
        downY = rawY
        currentRoute = Route.PENDING
    }

    fun classify(rawX: Float, rawY: Float): Route {
        if (currentRoute != Route.PENDING) return currentRoute
        val dx = abs(rawX - downX)
        val dy = abs(rawY - downY)
        if (dy > touchSlop && dy > dx * 1.25f) {
            currentRoute = Route.FORWARD
            return currentRoute
        }
        if (dx > touchSlop) {
            currentRoute = Route.REJECT
            return currentRoute
        }
        return Route.PENDING
    }
}