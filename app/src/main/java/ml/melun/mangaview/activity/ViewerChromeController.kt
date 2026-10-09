package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import kotlin.math.abs
import ml.melun.mangaview.R
import ml.melun.mangaview.ui.AppFonts
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
        val immersive: () -> Unit,
        val settings: () -> Unit,
        val seek: (Int) -> Unit = {},
        val autoScroll: () -> Unit = {},
    )

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var palette = ViewerPalette.of(dark = true)
    private var insets = ViewerSafeInsets(0, 0, 0, 0)
    private val top = LinearLayout(activity)
    private val bottom = LinearLayout(activity)
    private val back = ChromeIconButton(activity, R.drawable.ic_arrow_back_ios_new, "뒤로", actions.back)
    private val title = TextView(activity).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(activity.dp(4), 0, activity.dp(8), 0)
    }
    private val split = ChromeIconButton(activity, R.drawable.ic_menu_book, "양면 보기", actions.split)
    private val immersive = ChromeIconButton(activity, R.drawable.ic_fullscreen, "몰입 모드 (전체 화면)", actions.immersive)
    private val autoScroll = ChromeIconButton(activity, R.drawable.ic_swipe_down, "자동 스크롤 시작", actions.autoScroll)
    private val page = TextView(activity).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        isSingleLine = true
        fontFeatureSettings = "tnum"
    }
    private val scrubber = ViewerPageScrubber(activity)
    private val previous = ChromeActionItem(activity, R.drawable.ic_skip_previous, "이전", false, actions.previous)
    private val episodes = ChromeActionItem(activity, R.drawable.ic_format_list_bulleted, "회차", false, actions.episodes)
    private val bookmark = ChromeActionItem(activity, R.drawable.ic_bookmark, "책갈피", false, actions.bookmark)
    private val settings = ChromeActionItem(activity, R.drawable.ic_tune, "설정", false, actions.settings)
    private val next = ChromeActionItem(activity, R.drawable.ic_skip_next, "다음", true, actions.next)
    /** Large page readout in the middle of the screen while the scrubber is held. */
    private val bubble = TextView(activity).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        visibility = View.GONE
        setPadding(activity.dp(22), activity.dp(12), activity.dp(22), activity.dp(12))
    }
    private val gestureRelay = ChromeGestureRelay(
        surface = surface,
        touchSlop = touchSlop.toFloat(),
        hideWithoutDetachingTouchTarget = ::hideWithoutDetachingTouchTarget,
        finishHiddenGesture = ::finishHiddenGesture,
    )
    private var showing = false
    private var autoHidePaused = false
    private var scrubbing = false
    private var pageCount = 0
    private val autoHide = Runnable { if (showing && !autoHidePaused && !scrubbing) setVisible(false) }

    val visible: Boolean get() = showing

    fun install(root: FrameLayout) {
        configureBars()
        configureScrubber()
        root.addView(bubble, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        applyPalette(palette)
        val known = insets
        insets = ViewerSafeInsets(-1, -1, -1, -1)
        applyInsets(known)
        showing = false
        top.visibility = View.GONE
        bottom.visibility = View.GONE
    }

    fun toggle() {
        if (visible) {
            setVisible(false)
        } else {
            update(snapshot())
            setVisible(true)
        }
    }

    fun hide() {
        if (visible) setVisible(false)
    }

    /** Settings sheets keep the bars parked instead of letting the idle timer yank them away. */
    fun setAutoHidePaused(paused: Boolean) {
        autoHidePaused = paused
        if (paused) cancelAutoHide() else if (showing) scheduleAutoHide()
    }

    fun refresh() {
        if (visible) update(snapshot())
    }

    fun contains(x: Float, y: Float): Boolean = visible &&
        (top.containsPoint(x, y) || bottom.containsPoint(x, y))

    /** Follows the reader's light/dark choice; the bars and every control restyle in place. */
    fun applyPalette(value: ViewerPalette) {
        palette = value
        listOf(top, bottom).forEach { it.setBackgroundColor(value.bar) }
        listOf(back, autoScroll, split, immersive).forEach { it.applyPalette(value) }
        listOf(previous, episodes, bookmark, settings, next).forEach { it.applyPalette(value) }
        title.style(16f, AppFonts.SEMIBOLD, value.text)
        page.style(13f, AppFonts.SEMIBOLD, value.secondary)
        scrubber.applyPalette(value)
        bubble.style(22f, AppFonts.BOLD, Color.WHITE)
        bubble.background = roundedFill(0xE6151824.toInt(), activity.dpf(18f))
        if (showing) applySystemBarAppearance(true)
    }

    /** The bars reach under the system bars so their color, not a black strip, frames the screen. */
    fun applyInsets(value: ViewerSafeInsets) {
        if (insets == value) return
        insets = value
        (top.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = -value.top; top.layoutParams = it }
        (bottom.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = -value.bottom; bottom.layoutParams = it }
        top.setPadding(activity.dp(4), value.top + activity.dp(4), activity.dp(8), activity.dp(4))
        bottom.setPadding(activity.dp(8), activity.dp(2), activity.dp(8), value.bottom + activity.dp(6))
    }

    private fun update(state: ViewerChromeState?) {
        setText(title, state?.title ?: "회차 불러오는 중")
        pageCount = state?.pageCount ?: 0
        if (!scrubbing) setText(page, state?.let { pageLabel(it.pageNumber, it.pageCount) } ?: "– / –")
        scrubber.bind(state?.pageNumber ?: 1, pageCount)
        previous.enable(state?.previousEpisodeId != null)
        next.enable(state?.nextEpisodeId != null)
        episodes.enable(state != null)
        bookmark.enable(state != null)
        split.enable(state != null)
        val splitOn = state?.splitMode == true
        // Only a comic's spread splits into two pages; webtoon strips are often cut into slices
        // wider than tall, which would read as spreads. The control stays while split is on so it
        // can always be turned back off.
        val splittable = state != null && state.hasSpreads &&
            ml.melun.mangaview.ui.library.isComicSeries(state.episodeId.seriesId)
        split.visibility = if (splitOn || splittable) View.VISIBLE else View.GONE
        split.setActive(splitOn)
        split.contentDescription = if (splitOn) "단면 보기, 누르면 양면" else "양면 보기, 누르면 단면"
    }

    private fun setText(view: TextView, value: String) {
        if (view.text?.toString() != value) view.text = value
    }

    fun setAutoScrollActive(active: Boolean) {
        autoScroll.contentDescription = if (active) "자동 스크롤 멈춤" else "자동 스크롤 시작"
        autoScroll.setActive(active, if (active) R.drawable.ic_pause_fill1 else R.drawable.ic_swipe_down)
    }

    /** Mirrors the immersive setting on the quick toggle inside the chrome. */
    fun setImmersiveActive(active: Boolean) {
        immersive.contentDescription = if (active) "몰입 모드 켜짐" else "몰입 모드 (전체 화면)"
        immersive.setActive(active, if (active) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    private fun configureBars() {
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(back, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        top.addView(title, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        top.addView(autoScroll, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
            marginEnd = activity.dp(4)
        })
        top.addView(split, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        top.addView(immersive, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
            marginStart = activity.dp(4)
        })
        bottom.orientation = LinearLayout.VERTICAL
        val scrubRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44)).apply {
                marginStart = activity.dp(10)
            })
            addView(scrubber, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        }
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(previous, episodes, bookmark, settings, next).forEach { item ->
            // Height follows the label so a large system font grows the row instead of clipping it.
            item.minHeight = activity.dp(58)
            actionRow.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        bottom.addView(scrubRow)
        bottom.addView(actionRow)
        // The scrubber owns its horizontal drag; everything else relays vertical drags to the page.
        installDragForwarding(top, bottom, scrubRow, actionRow, back, title, page, autoScroll,
            previous, episodes, bookmark, settings, next, split, immersive)
    }

    private fun configureScrubber() {
        scrubber.onDragChanged = { dragging ->
            scrubbing = dragging
            if (dragging) cancelAutoHide() else scheduleAutoHide()
            fadeBubble(dragging)
        }
        scrubber.onPreview = { target ->
            val label = pageLabel(target, pageCount)
            setText(page, label)
            setText(bubble, label)
        }
        scrubber.onCommit = { target ->
            setText(page, pageLabel(target, pageCount))
            actions.seek(target)
        }
    }

    private fun fadeBubble(show: Boolean) {
        bubble.animate().cancel()
        if (show) {
            bubble.alpha = 0f
            bubble.scaleX = 0.92f
            bubble.scaleY = 0.92f
            bubble.visibility = View.VISIBLE
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FAST_MS).setInterpolator(EMPHASIZED).start()
        } else {
            bubble.animate().alpha(0f).setDuration(FAST_MS).withEndAction { bubble.visibility = View.GONE }.start()
        }
    }

    private fun pageLabel(number: Int, count: Int): String = if (count > 0) "$number / $count" else "– / –"

    private fun setVisible(show: Boolean) {
        if (showing == show) return
        showing = show
        cancelAutoHide()
        applySystemBarAppearance(show)
        if (show) {
            top.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showBars()
            scheduleAutoHide()
        } else {
            hideBarsAnimated()
        }
    }

    /**
     * Light bars need dark status/navigation icons while they are on screen. This uses the same
     * legacy visibility flags as the rest of the app: once a window opts into the controller's
     * appearance API, the platform stops honoring those flags for the library afterwards.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBarAppearance(chromeShowing: Boolean) {
        val decor = (activity as? Activity)?.window?.decorView ?: return
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val next = if (chromeShowing && !palette.dark) decor.systemUiVisibility or mask
        else decor.systemUiVisibility and mask.inv()
        if (next != decor.systemUiVisibility) decor.systemUiVisibility = next
    }

    private fun showBars() {
        listOf(top, bottom).forEachIndexed { index, bar ->
            bar.animate().cancel()
            bar.visibility = View.VISIBLE
            val travel = (bar.height.takeIf { it > 0 } ?: activity.dp(96)).toFloat() * 0.6f
            bar.alpha = 0f
            bar.translationY = if (index == 0) -travel else travel
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SHOW_DURATION_MS)
                .setInterpolator(EMPHASIZED)
                .start()
        }
    }

    private fun hideBarsAnimated() {
        listOf(top, bottom).forEachIndexed { index, bar ->
            val travel = bar.height.toFloat() * 0.6f
            bar.animate().cancel()
            bar.animate()
                .alpha(0f)
                .translationY(if (index == 0) -travel else travel)
                .setDuration(HIDE_DURATION_MS)
                .setInterpolator(ACCELERATE)
                .withEndAction {
                    if (!showing) {
                        bar.visibility = View.GONE
                        bar.alpha = 1f
                        bar.translationY = 0f
                    }
                }
                .start()
        }
    }

    private fun scheduleAutoHide() {
        top.removeCallbacks(autoHide)
        if (showing && !autoHidePaused && !scrubbing) {
            top.postDelayed(autoHide, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun cancelAutoHide() {
        top.removeCallbacks(autoHide)
    }

    private fun installDragForwarding(vararg views: View) {
        views.forEach { view -> view.setOnTouchListener(::forwardToolbarTouch) }
    }

    private fun forwardToolbarTouch(source: View, event: MotionEvent): Boolean {
        return gestureRelay.onTouch(source, event)
    }

    private fun hideWithoutDetachingTouchTarget() {
        showing = false
        cancelAutoHide()
        applySystemBarAppearance(false)
        top.animate().cancel()
        bottom.animate().cancel()
        top.alpha = 0f
        bottom.alpha = 0f
    }

    private fun finishHiddenGesture() {
        top.visibility = View.GONE
        bottom.visibility = View.GONE
        top.alpha = 1f
        bottom.alpha = 1f
        top.translationY = 0f
        bottom.translationY = 0f
    }

    private fun View.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top + translationY && y < bottom + translationY

    private companion object {
        const val SHOW_DURATION_MS = 240L
        const val HIDE_DURATION_MS = 160L
        const val FAST_MS = 140L
        const val AUTO_HIDE_DELAY_MS = 3_500L
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
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
    /** The relay consumes the touch stream, so it drives the control's pressed ripple itself. */
    private var pressed: View? = null

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
        if (source.isClickable && source.isEnabled) {
            source.drawableHotspotChanged(event.x, event.y)
            source.isPressed = true
            pressed = source
        }
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
            VerticalGestureAxisLock.Route.REJECT -> { release(); recyclePending() }
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
        release()
        recyclePending()
    }

    private fun release() {
        pressed?.isPressed = false
        pressed = null
    }

    private fun cancel(source: View, event: MotionEvent) {
        release()
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
        release()
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