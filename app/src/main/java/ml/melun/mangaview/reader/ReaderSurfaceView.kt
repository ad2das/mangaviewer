package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReaderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    interface WindowListener {
        fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean)
        fun onNearEnd(anchorPage: Int)
        fun onTap()
    }

    private data class Page(
        var bitmap: Bitmap? = null,
        var width: Int = 0,
        var height: Int = 0,
        var loading: Boolean = false,
        var cardText: String? = null
    )

    private data class DrawItem(
        val bitmap: Bitmap?,
        val loading: Boolean,
        val cardText: String?,
        val top: Float,
        val pageHeight: Float
    )

    private data class DrawState(
        val width: Int,
        val height: Int,
        val busy: Boolean,
        val empty: Boolean,
        val items: List<DrawItem>
    )

    private data class WindowRequest(
        val firstPage: Int,
        val lastPage: Int,
        val anchorPage: Int,
        val busy: Boolean,
        val nearEnd: Boolean
    )

    private val stateLock = Object()
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val gapPx = 2
    private val mainHandler = Handler(Looper.getMainLooper())

    private var velocityTracker: VelocityTracker? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var renderChoreographer: Choreographer? = null
    private var renderRunning = false
    private var surfaceReady = false
    private var renderRequested = false
    private var frameScheduled = false
    private var lastY = 0f
    private var downY = 0f
    private var dragging = false
    private var scrollOffset = 0f
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastBusy = false
    private var lastRequestedBusy = false
    private var layoutDirty = true
    private var pageTops = FloatArrayList(0)
    private var contentHeight = 0f
    private var statsActive = false
    private var statsLastFrameNs = 0L
    private val statsIntervalsMs = ArrayList<Float>(240)

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun setWindowListener(listener: WindowListener?) {
        this.listener = listener
    }

    fun setPageCount(count: Int) {
        val request = synchronized(stateLock) {
            pages.clear()
            repeat(max(0, count)) { pages.add(Page()) }
            scrollOffset = 0f
            lastAnchor = -1
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    fun appendPageCount(count: Int) {
        val request = synchronized(stateLock) {
            if (count <= pages.size) return
            repeat(count - pages.size) { pages.add(Page()) }
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun setPageLoading(index: Int) {
        synchronized(stateLock) {
            pages.getOrNull(index)?.let {
                if (it.cardText == null) it.loading = true
            }
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTops.getOrElse(index, 0f)
            page.bitmap = bitmap
            page.width = max(1, bitmap.width)
            page.height = max(1, bitmap.height)
            page.loading = false
            page.cardText = null
            val newHeight = pageDrawHeightLocked(page)
            if (oldTop + oldHeight <= scrollOffset) scrollOffset += newHeight - oldHeight
            layoutDirty = true
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun clearPageBitmap(index: Int) {
        synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            page.bitmap = null
            page.loading = false
            page.cardText = null
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun setPageCard(index: Int, title: String) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            page.bitmap = null
            page.width = width
            page.height = max(1, (height * 0.38f).toInt())
            page.loading = false
            page.cardText = title
            layoutDirty = true
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun scrollToPage(index: Int) {
        val request = synchronized(stateLock) {
            val target = index.coerceIn(0, pages.lastIndex)
            rebuildLayoutLocked()
            scrollOffset = pageTops.getOrElse(target, 0f)
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(stateLock) {
            surfaceReady = true
            renderRunning = true
            renderRequested = true
            ensureRenderThreadLocked()
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val request = synchronized(stateLock) {
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val thread = synchronized(stateLock) {
            surfaceReady = false
            renderRunning = false
            stopRenderThreadLocked()
            stateLock.notifyAll()
            renderThread
        }
    }

    override fun onDetachedFromWindow() {
        synchronized(stateLock) {
            surfaceReady = false
            renderRunning = false
            stopRenderThreadLocked()
            stateLock.notifyAll()
        }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEmpty()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                val request = synchronized(stateLock) {
                    scroller.forceFinished(true)
                    lastY = event.y
                    downY = event.y
                    dragging = false
                    setBusyLocked(true)
                }
                dispatchWindowRequest(request)
                requestRender()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val request = synchronized(stateLock) {
                    val dy = lastY - event.y
                    if (!dragging && abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        scrollOffset += dy * DRAG_SCROLL_MULTIPLIER
                        clampScrollLocked()
                        lastY = event.y
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                        windowRequestLocked(true)
                    } else {
                        null
                    }
                }
                dispatchWindowRequest(request)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocityTracker
                var velocityY = 0
                var tap = false
                if (tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, maxVelocity.toFloat())
                    velocityY = (-tracker.yVelocity)
                        .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                        .toInt()
                    tracker.recycle()
                }
                velocityTracker = null
                val request = synchronized(stateLock) {
                    val wasTap = !dragging && abs(event.y - downY) <= touchSlop
                    tap = wasTap
                    dragging = false
                    if (wasTap) {
                        null
                    } else if (abs(velocityY) > minVelocity) {
                        val flingVelocity = (velocityY * FLING_SCROLL_MULTIPLIER)
                            .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                            .toInt()
                        scroller.fling(
                            0,
                            scrollOffset.toInt(),
                            0,
                            flingVelocity,
                            0,
                            0,
                            0,
                            max(0, (totalHeightLocked() - height).toInt())
                        )
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                        windowRequestLocked(true)
                    } else {
                        setBusyLocked(false)
                    }
                }
                dispatchWindowRequest(request)
                if (tap) mainHandler.post { listener?.onTap() }
                requestRender()
                return true
            }
        }
        return true
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        renderFrame(frameTimeNanos)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val requestAndState = synchronized(stateLock) {
            frameScheduled = false
            if (!renderRunning || !surfaceReady) return
            var request: WindowRequest? = null
            val scrolling = try {
                scroller.computeScrollOffset()
            } catch (_: ArrayIndexOutOfBoundsException) {
                scroller.forceFinished(true)
                false
            }
            if (scrolling) {
                scrollOffset = scroller.currY.toFloat()
                clampScrollLocked()
                renderRequested = true
                request = windowRequestLocked(true)
            } else if (lastBusy && !dragging) {
                request = setBusyLocked(false)
            }
            val state = buildDrawStateLocked()
            recordFrameStatsLocked(frameTimeNanos, scrolling || lastBusy || dragging)
            renderRequested = false
            if (!scroller.isFinished || lastBusy || dragging) scheduleFrameLocked()
            request to state
        }
        dispatchWindowRequest(requestAndState.first)
        drawState(requestAndState.second)
    }

    private fun drawState(state: DrawState) {
        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
        } catch (_: RuntimeException) {
            null
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            if (state.empty) {
                canvas.drawText("로딩 중", state.width / 2f, state.height / 2f, textPaint)
                return
            }
            for (item in state.items) drawItem(canvas, state, item)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun drawItem(canvas: Canvas, state: DrawState, item: DrawItem) {
        val bitmap = item.bitmap
        val cardText = item.cardText
        if (cardText != null) {
            paint.color = Color.rgb(12, 12, 12)
            dst.set(0f, max(0f, item.top), state.width.toFloat(), min(state.height.toFloat(), item.top + item.pageHeight))
            canvas.drawRect(dst, paint)
            textPaint.textSize = 30f
            textPaint.color = Color.rgb(170, 170, 170)
            canvas.drawText("다음 회차", state.width / 2f, item.top + item.pageHeight / 2f - 34f, textPaint)
            textPaint.textSize = 42f
            textPaint.color = Color.WHITE
            canvas.drawText(cardText, state.width / 2f, item.top + item.pageHeight / 2f + 30f, textPaint)
            textPaint.textSize = 34f
            textPaint.color = Color.rgb(190, 190, 190)
            return
        }
        if (bitmap != null && !bitmap.isRecycled) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) return
            val sourceTop = ((visibleTop - item.top) / item.pageHeight * bitmap.height)
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            val sourceBottom = ((visibleBottom - item.top) / item.pageHeight * bitmap.height)
                .toInt()
                .coerceIn(sourceTop + 1, bitmap.height)
            src.set(0, sourceTop, bitmap.width, sourceBottom)
            dst.set(0f, visibleTop, state.width.toFloat(), visibleBottom)
            paint.isFilterBitmap = !state.busy
            canvas.drawBitmap(bitmap, src, dst, paint)
            return
        }
        paint.color = Color.rgb(18, 18, 18)
        dst.set(0f, max(0f, item.top), state.width.toFloat(), min(state.height.toFloat(), item.top + item.pageHeight))
        canvas.drawRect(dst, paint)
        canvas.drawText(
            if (item.loading) "불러오는 중" else "대기 중",
            state.width / 2f,
            item.top + min(item.pageHeight / 2f, state.height / 2f),
            textPaint
        )
    }

    private fun buildDrawStateLocked(): DrawState {
        val viewWidth = max(1, width)
        val viewHeight = max(1, height)
        if (pages.isEmpty()) return DrawState(viewWidth, viewHeight, lastBusy, true, emptyList())
        rebuildLayoutLocked()
        val items = ArrayList<DrawItem>()
        var index = firstVisiblePageLocked(scrollOffset)
        while (index < pages.size) {
            val page = pages[index]
            val top = pageTops[index] - scrollOffset
            val pageHeight = pageDrawHeightLocked(page)
            val bottom = top + pageHeight
            if (bottom >= 0f && top <= viewHeight) {
                items.add(DrawItem(page.bitmap, page.loading, page.cardText, top, pageHeight))
            }
            if (top > viewHeight) break
            index++
        }
        return DrawState(viewWidth, viewHeight, lastBusy, false, items)
    }

    private fun requestRender() {
        synchronized(stateLock) {
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    private fun isEmpty(): Boolean = synchronized(stateLock) { pages.isEmpty() }

    private fun setBusyLocked(busy: Boolean): WindowRequest? {
        if (lastBusy == busy) return null
        lastBusy = busy
        renderRequested = true
        scheduleFrameLocked()
        stateLock.notifyAll()
        return windowRequestLocked(busy)
    }

    private fun scheduleFrameLocked() {
        if (!renderRunning || !surfaceReady || frameScheduled) return
        val choreographer = renderChoreographer
        val handler = renderHandler
        if (choreographer != null) {
            frameScheduled = true
            choreographer.postFrameCallback(frameCallback)
        } else if (handler != null) {
            frameScheduled = true
            handler.post {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    private fun ensureRenderThreadLocked() {
        if (renderThread?.isAlive == true && renderHandler != null) return
        val thread = HandlerThread("ReaderSurfaceRenderer")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
        renderHandler?.post {
            synchronized(stateLock) {
                if (!renderRunning || !surfaceReady || renderThread !== thread) {
                    thread.quitSafely()
                    return@post
                }
                renderChoreographer = Choreographer.getInstance()
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }
    }

    private fun stopRenderThreadLocked() {
        val handler = renderHandler
        val thread = renderThread
        renderHandler = null
        renderChoreographer = null
        renderThread = null
        frameScheduled = false
        if (handler == null || thread == null) return
        handler.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            thread.quitSafely()
        }
    }

    private fun windowRequestLocked(busy: Boolean): WindowRequest? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        val anchor = anchorPageLocked()
        if (anchor == lastAnchor && busy == lastRequestedBusy) return null
        lastAnchor = anchor
        lastRequestedBusy = busy
        val first = max(0, anchor - ReaderPipelinePolicy.windowBefore(busy))
        val last = min(pages.lastIndex, anchor + ReaderPipelinePolicy.windowAfter(busy))
        return WindowRequest(first, last, anchor, busy, anchor >= pages.size - 3)
    }

    private fun dispatchWindowRequest(request: WindowRequest?) {
        if (request == null) return
        mainHandler.post {
            val currentListener = listener ?: return@post
            currentListener.onWindowChanged(request.firstPage, request.lastPage, request.anchorPage, request.busy)
            if (request.nearEnd) currentListener.onNearEnd(request.anchorPage)
        }
    }

    private fun anchorPageLocked(): Int {
        rebuildLayoutLocked()
        val probe = scrollOffset + height * 0.35f
        return firstVisiblePageLocked(probe).coerceIn(0, pages.lastIndex)
    }

    private fun clampScrollLocked() {
        scrollOffset = scrollOffset.coerceIn(0f, max(0f, totalHeightLocked() - height))
    }

    private fun totalHeightLocked(): Float {
        rebuildLayoutLocked()
        return contentHeight
    }

    private fun pageDrawHeightLocked(page: Page): Float {
        val viewWidth = width
        if (viewWidth <= 0) return 1f
        if (page.width > 0 && page.height > 0) return max(1f, viewWidth * (page.height / page.width.toFloat()))
        return max(height * 1.4f, viewWidth * 1.35f)
    }

    private fun rebuildLayoutLocked() {
        if (!layoutDirty && pageTops.size == pages.size) return
        if (pageTops.size != pages.size) pageTops = FloatArrayList(pages.size)
        var top = 0f
        for (i in pages.indices) {
            pageTops[i] = top
            top += pageDrawHeightLocked(pages[i]) + gapPx
        }
        contentHeight = max(0f, top - gapPx)
        layoutDirty = false
    }

    private fun firstVisiblePageLocked(position: Float): Int {
        if (pages.isEmpty()) return 0
        rebuildLayoutLocked()
        var low = 0
        var high = pages.lastIndex
        var result = pages.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val bottom = pageTops[mid] + pageDrawHeightLocked(pages[mid]) + gapPx
            if (position <= bottom) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun recordFrameStatsLocked(frameTimeNanos: Long, active: Boolean) {
        if (active) {
            if (!statsActive) {
                statsActive = true
                statsLastFrameNs = frameTimeNanos
                statsIntervalsMs.clear()
                return
            }
            if (statsLastFrameNs > 0L && frameTimeNanos > statsLastFrameNs) {
                statsIntervalsMs.add((frameTimeNanos - statsLastFrameNs) / 1_000_000f)
            }
            statsLastFrameNs = frameTimeNanos
            return
        }
        if (!statsActive) return
        val intervals = ArrayList(statsIntervalsMs)
        statsActive = false
        statsLastFrameNs = 0L
        statsIntervalsMs.clear()
        if (intervals.isEmpty()) return
        intervals.sort()
        val frames = intervals.size + 1
        val jank = intervals.count { it > FRAME_BUDGET_MS }
        val p90 = percentile(intervals, 0.90f)
        val p95 = percentile(intervals, 0.95f)
        val p99 = percentile(intervals, 0.99f)
        val max = intervals.last()
        val jankPercent = jank * 100f / intervals.size
        Log.i(
            TAG,
            "surface_jank frames=$frames intervals=${intervals.size} jank=$jank " +
                "jankPct=${"%.2f".format(java.util.Locale.US, jankPercent)} " +
                "p90=${"%.2f".format(java.util.Locale.US, p90)} " +
                "p95=${"%.2f".format(java.util.Locale.US, p95)} " +
                "p99=${"%.2f".format(java.util.Locale.US, p99)} " +
                "max=${"%.2f".format(java.util.Locale.US, max)}"
        )
    }

    private fun percentile(sorted: List<Float>, percentile: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private class FloatArrayList(size: Int) {
        private var values = FloatArray(max(1, size))
        var size: Int = size
            private set

        operator fun get(index: Int): Float = values[index]

        operator fun set(index: Int, value: Float) {
            if (index >= values.size) values = values.copyOf(max(index + 1, values.size * 2))
            values[index] = value
            if (index >= size) size = index + 1
        }

        fun getOrElse(index: Int, fallback: Float): Float {
            return if (index in 0 until size) values[index] else fallback
        }
    }

    private companion object {
        private const val TAG = "ReaderSurfaceStats"
        private const val FRAME_BUDGET_MS = 16.67f
        private const val DRAG_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_SCROLL_MULTIPLIER = 1.0f
    }
}
