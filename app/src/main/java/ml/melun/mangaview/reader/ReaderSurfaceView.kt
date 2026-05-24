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
import android.os.Looper
import android.util.AttributeSet
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
    }

    private data class Page(
        var bitmap: Bitmap? = null,
        var width: Int = 0,
        var height: Int = 0,
        var loading: Boolean = false
    )

    private data class DrawItem(
        val bitmap: Bitmap?,
        val loading: Boolean,
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

    private var velocityTracker: VelocityTracker? = null
    private var renderThread: Thread? = null
    private var renderHandler: Handler? = null
    private var renderChoreographer: Choreographer? = null
    private var renderRunning = false
    private var surfaceReady = false
    private var renderRequested = false
    private var frameScheduled = false
    private var lastY = 0f
    private var dragging = false
    private var scrollOffset = 0f
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastBusy = false
    private var lastRequestedBusy = false

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
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun setPageLoading(index: Int) {
        synchronized(stateLock) {
            pages.getOrNull(index)?.loading = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            page.bitmap = bitmap
            page.width = max(1, bitmap.width)
            page.height = max(1, bitmap.height)
            page.loading = false
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
            var top = 0f
            for (i in 0 until target) top += pageDrawHeightLocked(pages[i]) + gapPx
            scrollOffset = top
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
            if (renderThread?.isAlive != true) {
                renderThread = Thread(this::renderThreadLoop, "ReaderSurfaceRenderer").also { it.start() }
            }
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
            stopRenderLooperLocked()
            stateLock.notifyAll()
            renderThread
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onDetachedFromWindow() {
        synchronized(stateLock) {
            surfaceReady = false
            renderRunning = false
            stopRenderLooperLocked()
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
                        scrollOffset += dy
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
                    dragging = false
                    if (abs(velocityY) > minVelocity) {
                        scroller.fling(
                            0,
                            scrollOffset.toInt(),
                            0,
                            velocityY,
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
                requestRender()
                return true
            }
        }
        return true
    }

    private val frameCallback = Choreographer.FrameCallback {
        renderFrame()
    }

    private fun renderThreadLoop() {
        Looper.prepare()
        synchronized(stateLock) {
            renderHandler = Handler(Looper.myLooper()!!)
            renderChoreographer = Choreographer.getInstance()
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
        Looper.loop()
        synchronized(stateLock) {
            renderHandler = null
            renderChoreographer = null
            frameScheduled = false
            renderThread = null
        }
    }

    private fun renderFrame() {
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
        val items = ArrayList<DrawItem>()
        var top = -scrollOffset
        for (page in pages) {
            val pageHeight = pageDrawHeightLocked(page)
            val bottom = top + pageHeight
            if (bottom >= 0f && top <= viewHeight) {
                items.add(DrawItem(page.bitmap, page.loading, top, pageHeight))
            }
            top = bottom + gapPx
            if (top > viewHeight) break
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

    private fun stopRenderLooperLocked() {
        val handler = renderHandler ?: return
        handler.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Looper.myLooper()?.quitSafely()
        }
        renderHandler = null
        renderChoreographer = null
        frameScheduled = false
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
        val currentListener = listener ?: return
        currentListener.onWindowChanged(request.firstPage, request.lastPage, request.anchorPage, request.busy)
        if (request.nearEnd) currentListener.onNearEnd(request.anchorPage)
    }

    private fun anchorPageLocked(): Int {
        var top = 0f
        val probe = scrollOffset + height * 0.35f
        for (i in pages.indices) {
            val bottom = top + pageDrawHeightLocked(pages[i]) + gapPx
            if (probe <= bottom) return i
            top = bottom
        }
        return pages.lastIndex
    }

    private fun clampScrollLocked() {
        scrollOffset = scrollOffset.coerceIn(0f, max(0f, totalHeightLocked() - height))
    }

    private fun totalHeightLocked(): Float {
        if (pages.isEmpty()) return 0f
        var total = 0f
        for (page in pages) total += pageDrawHeightLocked(page) + gapPx
        return max(0f, total - gapPx)
    }

    private fun pageDrawHeightLocked(page: Page): Float {
        val viewWidth = width
        if (viewWidth <= 0) return 1f
        if (page.width > 0 && page.height > 0) return max(1f, viewWidth * (page.height / page.width.toFloat()))
        return max(height * 1.4f, viewWidth * 1.35f)
    }
}
