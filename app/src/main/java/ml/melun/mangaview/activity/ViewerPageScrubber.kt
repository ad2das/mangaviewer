package ml.melun.mangaview.activity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * The reader's page slider. Dragging previews a page (the chrome shows it in a bubble) and the
 * jump is committed once on release, so a long scrub never queues a decode for every page it
 * crosses. TalkBack sees a seek bar with page range and scroll actions.
 */
internal class ViewerPageScrubber(context: Context) : View(context) {
    var onPreview: (Int) -> Unit = {}
    var onCommit: (Int) -> Unit = {}
    var onDragChanged: (Boolean) -> Unit = {}

    private var palette = ViewerPalette.of(dark = true)
    private var pageCount = 0
    private var page = 1
    private var dragPage = 1
    private var dragging = false
    /** A committed jump the reading position has not reported yet; stale binds must not undo it. */
    private var pendingPage = 0
    private var pendingUntil = 0L
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackHeight = context.dpf(4f)
    private val thumbRadius = context.dpf(8f)
    private val thumbDragRadius = context.dpf(11f)
    private val inset = context.dpf(14f)

    init {
        isFocusable = true
        contentDescription = "페이지 이동"
        applyPalette(palette)
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        trackPaint.color = value.track
        fillPaint.color = value.accent
        thumbPaint.color = value.accent
        ringPaint.color = value.bar
        ringPaint.strokeWidth = context.dpf(2.5f)
        invalidate()
    }

    /** Reflects the reading position unless the reader is mid-drag. */
    fun bind(current: Int, count: Int) {
        val clamped = if (count > 0) current.coerceIn(1, count) else 1
        if (pendingPage != 0) {
            val waiting = clamped != pendingPage && android.os.SystemClock.uptimeMillis() < pendingUntil
            if (waiting && pageCount == count) return
            pendingPage = 0
        }
        if (pageCount == count && page == clamped) return
        pageCount = count
        page = clamped
        isEnabled = count > 1
        if (!dragging) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(context.dp(44), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val shown = if (dragging) dragPage else page
        val fraction = if (pageCount > 1) (shown - 1f) / (pageCount - 1f) else 0f
        val left = inset
        val right = width - inset
        val cy = height / 2f
        val x = left + (right - left) * fraction
        trackPaint.strokeWidth = trackHeight
        fillPaint.strokeWidth = trackHeight
        canvas.drawLine(left, cy, right, cy, trackPaint)
        if (pageCount > 0) canvas.drawLine(left, cy, x, cy, fillPaint)
        val radius = if (dragging) thumbDragRadius else thumbRadius
        canvas.drawCircle(x, cy, radius, thumbPaint)
        canvas.drawCircle(x, cy, radius, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragPage = page
                onDragChanged(true)
                track(event.x)
            }
            MotionEvent.ACTION_MOVE -> track(event.x)
            MotionEvent.ACTION_UP -> finish(commit = true)
            MotionEvent.ACTION_CANCEL -> finish(commit = false)
        }
        return true
    }

    private fun track(x: Float) {
        if (pageCount < 1) return
        val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val next = 1 + (fraction * (pageCount - 1)).roundToInt()
        if (next != dragPage) {
            dragPage = next
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onPreview(dragPage)
        invalidate()
    }

    private fun finish(commit: Boolean) {
        if (!dragging) return
        dragging = false
        onDragChanged(false)
        if (commit && dragPage != page) jump(dragPage)
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = SeekBar::class.java.name

    @Suppress("DEPRECATION") // RangeInfo's constructor is API 33; obtain() covers the API 30 floor.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (pageCount < 1) return
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, pageCount.toFloat(), page.toFloat(),
        )
        info.stateDescription = "$pageCount 페이지 중 $page 페이지"
        if (page > 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (page < pageCount) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> page + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> page - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (target !in 1..pageCount) return false
        jump(target)
        invalidate()
        return true
    }

    private fun jump(target: Int) {
        page = target
        pendingPage = target
        pendingUntil = android.os.SystemClock.uptimeMillis() + PENDING_HOLD_MILLIS
        onCommit(target)
    }

    private companion object {
        const val PENDING_HOLD_MILLIS = 1_500L
    }
}
