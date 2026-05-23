package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReaderRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    interface WindowListener {
        fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean)
        fun onNearEnd(anchorPage: Int)
    }

    private data class Page(
        var bitmap: Bitmap? = null,
        var width: Int = 0,
        var height: Int = 0,
        var loading: Boolean = false
    )

    private val pages = ArrayList<Page>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 190, 190)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val src = Rect()
    private val dst = RectF()
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastY = 0f
    private var dragging = false
    private var scrollOffset = 0f
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastBusy = false
    private var lastRequestedBusy = false
    private val gapPx = 2

    fun setWindowListener(listener: WindowListener?) {
        this.listener = listener
    }

    fun setPageCount(count: Int) {
        pages.clear()
        repeat(max(0, count)) { pages.add(Page()) }
        scrollOffset = 0f
        lastAnchor = -1
        requestWindow(false)
        invalidate()
    }

    fun appendPageCount(count: Int) {
        if (count <= pages.size) return
        repeat(count - pages.size) { pages.add(Page()) }
        requestWindow(lastBusy)
        invalidate()
    }

    fun setPageLoading(index: Int) {
        pages.getOrNull(index)?.loading = true
        invalidate()
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap) {
        val page = pages.getOrNull(index) ?: return
        page.bitmap = bitmap
        page.width = max(1, bitmap.width)
        page.height = max(1, bitmap.height)
        page.loading = false
        clampScroll()
        requestWindow(lastBusy)
        invalidate()
    }

    fun clearPage(index: Int) {
        val page = pages.getOrNull(index) ?: return
        page.bitmap = null
        page.width = 0
        page.height = 0
        page.loading = false
    }

    fun clearOutside(first: Int, last: Int) {
        val safeFirst = max(0, first)
        val safeLast = min(pages.lastIndex, last)
        for (i in pages.indices) {
            if (i < safeFirst || i > safeLast) clearPage(i)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
        lastAnchor = -1
        requestWindow(lastBusy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (pages.isEmpty()) {
            canvas.drawText("로딩 중", width / 2f, height / 2f, textPaint)
            return
        }
        var top = -scrollOffset
        val viewportBottom = height.toFloat()
        for (i in pages.indices) {
            val pageHeight = pageDrawHeight(pages[i])
            val bottom = top + pageHeight
            if (bottom >= 0f && top <= viewportBottom) drawPage(canvas, i, top, pageHeight)
            top = bottom + gapPx
            if (top > viewportBottom) break
        }
    }

    private fun drawPage(canvas: Canvas, index: Int, top: Float, pageHeight: Float) {
        val page = pages[index]
        val bitmap = page.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            src.set(0, 0, bitmap.width, bitmap.height)
            dst.set(0f, top, width.toFloat(), top + pageHeight)
            paint.isFilterBitmap = !lastBusy
            canvas.drawBitmap(bitmap, src, dst, paint)
            return
        }
        paint.color = Color.rgb(18, 18, 18)
        dst.set(0f, top, width.toFloat(), top + pageHeight)
        canvas.drawRect(dst, paint)
        canvas.drawText(if (page.loading) "불러오는 중" else "대기 중", width / 2f, top + min(pageHeight / 2f, height / 2f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pages.isEmpty()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastY = event.y
                dragging = false
                setBusy(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = lastY - event.y
                if (!dragging && abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    scrollByInternal(dy)
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000, maxVelocity.toFloat())
                    val yVelocity = -it.yVelocity.toInt()
                    if (abs(yVelocity) > minVelocity) fling(yVelocity) else setBusy(false)
                    it.recycle()
                }
                velocityTracker = null
                dragging = false
                return true
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            requestWindow(true)
            invalidate()
            postInvalidateOnAnimation()
        } else if (lastBusy && !dragging) {
            setBusy(false)
        }
    }

    private fun fling(velocityY: Int) {
        scroller.fling(0, scrollOffset.toInt(), 0, velocityY, 0, 0, 0, max(0, (totalHeight() - height).toInt()))
        requestWindow(true)
        postInvalidateOnAnimation()
    }

    private fun scrollByInternal(dy: Float) {
        scrollOffset += dy
        clampScroll()
        requestWindow(true)
        invalidate()
    }

    private fun setBusy(busy: Boolean) {
        if (lastBusy == busy) return
        lastBusy = busy
        requestWindow(busy)
        invalidate()
    }

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0f, max(0f, totalHeight() - height))
    }

    private fun requestWindow(busy: Boolean) {
        if (pages.isEmpty() || width <= 0 || height <= 0) return
        val anchor = anchorPage()
        if (anchor == lastAnchor && busy == lastRequestedBusy) return
        lastAnchor = anchor
        lastRequestedBusy = busy
        val first = max(0, anchor - ReaderPipelinePolicy.windowBefore(busy))
        val last = min(pages.lastIndex, anchor + ReaderPipelinePolicy.windowAfter(busy))
        listener?.onWindowChanged(first, last, anchor, busy)
        if (anchor >= pages.size - 3) listener?.onNearEnd(anchor)
    }

    private fun anchorPage(): Int {
        var top = 0f
        val probe = scrollOffset + height * 0.35f
        for (i in pages.indices) {
            val bottom = top + pageDrawHeight(pages[i]) + gapPx
            if (probe <= bottom) return i
            top = bottom
        }
        return pages.lastIndex
    }

    private fun totalHeight(): Float {
        if (pages.isEmpty()) return 0f
        var total = 0f
        for (page in pages) total += pageDrawHeight(page) + gapPx
        return max(0f, total - gapPx)
    }

    private fun pageDrawHeight(page: Page): Float {
        if (width <= 0) return 1f
        if (page.width > 0 && page.height > 0) return max(1f, width * (page.height / page.width.toFloat()))
        return max(height * 1.4f, width * 1.35f)
    }
}
