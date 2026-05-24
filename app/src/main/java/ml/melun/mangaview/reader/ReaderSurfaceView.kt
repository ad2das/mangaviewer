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
import android.os.Process
import android.os.SystemClock
import android.os.Trace
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
import java.util.Locale

class ReaderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    interface WindowListener {
        fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean)
        fun onNearEnd(anchorPage: Int)
        fun onBoundaryReached(direction: Int, anchorPage: Int)
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

    private data class BoundaryRequest(
        val direction: Int,
        val anchorPage: Int
    )

    private data class DrawTiming(
        val frameTimeNs: Long,
        val callbackStartNs: Long,
        val lockWaitMs: Float,
        val drawMs: Float,
        val postMs: Float,
        val totalMs: Float,
        val postEndNs: Long,
        val posted: Boolean
    )

    private data class PendingInput(
        val oldestNs: Long,
        val newestNs: Long,
        val events: Int,
        val history: Int
    )

    private data class RenderWork(
        val request: WindowRequest?,
        val boundary: BoundaryRequest?,
        val state: DrawState?
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
    private val dragStartSlop = max(1f, touchSlop * 0.5f)
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var pageGapPx = DEFAULT_PAGE_GAP_PX
    private val mainHandler = Handler(Looper.getMainLooper())

    private var velocityTracker: VelocityTracker? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var renderChoreographer: Choreographer? = null
    private var renderRunning = false
    private var surfaceReady = false
    private var renderRequested = false
    private var frameScheduled = false
    private var immediateFrameScheduled = false
    private var pendingFrameCallback: Choreographer.FrameCallback? = null
    private var frameToken = 0
    private var lastY = 0f
    private var downY = 0f
    private var pointerDown = false
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
    private var statsLastCallbackStartNs = 0L
    private var statsLastPostEndNs = 0L
    private var lastPostedFrameEndNs = 0L
    private var statsCoalescedRequests = 0
    private var statsNoCanvasFrames = 0
    private val statsCallbackSpacingMs = ArrayList<Float>(240)
    private val statsPostSpacingMs = ArrayList<Float>(240)
    private val statsLockWaitMs = ArrayList<Float>(240)
    private val statsDrawMs = ArrayList<Float>(240)
    private val statsPostMs = ArrayList<Float>(240)
    private val statsTotalMs = ArrayList<Float>(240)
    private val statsInputOldestMs = ArrayList<Float>(240)
    private val statsInputNewestMs = ArrayList<Float>(240)
    private var pendingOldestInputNs = 0L
    private var pendingNewestInputNs = 0L
    private var pendingInputEvents = 0
    private var pendingHistorySamples = 0
    private var pendingWindowRequest: WindowRequest? = null
    private var windowDispatchPosted = false
    private var boundaryArmedDirection = 0

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun setWindowListener(listener: WindowListener?) {
        this.listener = listener
    }

    fun setPageGapPx(gapPx: Int) {
        var request: WindowRequest? = null
        synchronized(stateLock) {
            val next = max(0, gapPx)
            if (pageGapPx == next) return
            pageGapPx = next
            layoutDirty = true
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            request = windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun setPageCount(count: Int) {
        val request = synchronized(stateLock) {
            pages.clear()
            repeat(max(0, count)) { pages.add(Page()) }
            scrollOffset = 0f
            boundaryArmedDirection = 0
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

    fun prependPageCount(count: Int, insertedCount: Int) {
        val request = synchronized(stateLock) {
            if (insertedCount <= 0 || count <= pages.size) return
            rebuildLayoutLocked()
            val oldFirstTop = pageTops.getOrElse(0, 0f)
            repeat(insertedCount) { pages.add(0, Page()) }
            layoutDirty = true
            rebuildLayoutLocked()
            val shiftedFirstTop = pageTops.getOrElse(insertedCount, 0f)
            scrollOffset += shiftedFirstTop - oldFirstTop
            boundaryArmedDirection = 0
            clampScrollLocked()
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
            if (!lastBusy || isNearVisibleLocked(index, 1)) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
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
            val belowVisible = oldTop > scrollOffset + height
            layoutDirty = true
            clampScrollLocked()
            if (!lastBusy || !belowVisible) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
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

    fun setPageBounds(index: Int, pageWidth: Int, pageHeight: Int) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (page.bitmap != null || page.cardText != null || pageWidth <= 0 || pageHeight <= 0) return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTops.getOrElse(index, 0f)
            page.width = pageWidth
            page.height = pageHeight
            val newHeight = pageDrawHeightLocked(page)
            if (oldTop + oldHeight <= scrollOffset) scrollOffset += newHeight - oldHeight
            val belowVisible = oldTop > scrollOffset + height
            layoutDirty = true
            clampScrollLocked()
            if (!lastBusy || !belowVisible) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        dispatchWindowRequest(request)
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
                    noteInputLocked(event)
                    scroller.forceFinished(true)
                    lastY = event.y
                    downY = event.y
                    pointerDown = true
                    dragging = false
                    boundaryArmedDirection = 0
                    setBusyLocked(true)
                }
                dispatchWindowRequest(request)
                requestRender()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val request = synchronized(stateLock) {
                    noteInputLocked(event)
                    var dy = lastY - event.y
                    if (!dragging) {
                        val totalDy = downY - event.y
                        if (abs(totalDy) > dragStartSlop) {
                            dragging = true
                            lastY = downY - if (totalDy > 0f) dragStartSlop else -dragStartSlop
                            dy = lastY - event.y
                        }
                    }
                    if (dragging) {
                        val busyRequest = setBusyLocked(true)
                        val direction = directionForDelta(dy)
                        if (direction != 0) boundaryArmedDirection = direction
                        scrollOffset += dy * DRAG_SCROLL_MULTIPLIER
                        clampScrollLocked()
                        lastY = event.y
                        renderRequested = true
                        scheduleFrameLocked(preferImmediate = true)
                        stateLock.notifyAll()
                        busyRequest
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
                val result = synchronized(stateLock) {
                    noteInputLocked(event)
                    val wasReleased = event.actionMasked == MotionEvent.ACTION_UP
                    val wasTap = wasReleased && !dragging && abs(event.y - downY) <= touchSlop
                    tap = wasTap
                    pointerDown = false
                    dragging = false
                    if (wasTap) {
                        boundaryArmedDirection = 0
                        setBusyLocked(false) to null
                    } else if (wasReleased && abs(velocityY) > minVelocity) {
                        val flingVelocity = (velocityY * FLING_SCROLL_MULTIPLIER)
                            .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                            .toInt()
                        boundaryArmedDirection = directionForDelta(flingVelocity.toFloat())
                        val busyRequest = setBusyLocked(true)
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
                        (busyRequest ?: windowRequestLocked(true)) to null
                    } else {
                        val request = setBusyLocked(false)
                        val boundary = if (wasReleased) boundaryRequestLocked() else null
                        if (!wasReleased) boundaryArmedDirection = 0
                        request to boundary
                    }
                }
                dispatchWindowRequest(result.first)
                dispatchBoundaryRequest(result.second)
                if (tap) mainHandler.post { listener?.onTap() }
                requestRender()
                return true
            }
        }
        return true
    }

    private fun renderFrame(frameTimeNanos: Long, token: Int) {
        val callbackStartNs = System.nanoTime()
        val work = synchronized(stateLock) {
            if (token != frameToken) return
            frameScheduled = false
            immediateFrameScheduled = false
            pendingFrameCallback = null
            if (!renderRunning || !surfaceReady) return
            var request: WindowRequest? = null
            var boundary: BoundaryRequest? = null
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
            }
            val wasBusy = lastBusy
            val busyNow = pointerDown || dragging || scrolling || !scroller.isFinished
            if (busyNow != lastBusy) {
                request = setBusyLocked(busyNow) ?: request
            } else if (busyNow) {
                request = windowRequestLocked(true) ?: request
            }
            if (wasBusy && !busyNow) boundary = boundaryRequestLocked()
            val animateScroll = dragging || scrolling || !scroller.isFinished
            val shouldDraw = renderRequested || animateScroll
            val state = if (shouldDraw) buildDrawStateLocked(busyNow) else null
            if (shouldDraw) renderRequested = false
            if (animateScroll) scheduleFrameLocked()
            RenderWork(request, boundary, state)
        }
        dispatchWindowRequest(work.request)
        dispatchBoundaryRequest(work.boundary)
        val state = work.state ?: return
        val timing = drawState(frameTimeNanos, callbackStartNs, state)
        if (timing.posted) synchronized(stateLock) { lastPostedFrameEndNs = timing.postEndNs }
        recordFrameStats(timing, state.busy)
    }

    private fun drawState(frameTimeNs: Long, callbackStartNs: Long, state: DrawState): DrawTiming {
        val lockStartNs = System.nanoTime()
        val canvas = try {
            Trace.beginSection("RSV.lockCanvas")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
        } catch (_: RuntimeException) {
            null
        } finally {
            Trace.endSection()
        } ?: return DrawTiming(
            frameTimeNs,
            callbackStartNs,
            nsToMs(System.nanoTime() - lockStartNs),
            0f,
            0f,
            nsToMs(System.nanoTime() - callbackStartNs),
            System.nanoTime(),
            false
        )
        val lockEndNs = System.nanoTime()
        var drawEndNs = lockEndNs
        var postEndNs = lockEndNs
        try {
            Trace.beginSection("RSV.draw")
            canvas.drawColor(Color.BLACK)
            if (state.empty) {
                canvas.drawText("로딩 중", state.width / 2f, state.height / 2f, textPaint)
            } else {
                for (item in state.items) drawItem(canvas, state, item)
            }
        } finally {
            Trace.endSection()
            drawEndNs = System.nanoTime()
            try {
                Trace.beginSection("RSV.unlockPost")
                holder.unlockCanvasAndPost(canvas)
            } catch (_: RuntimeException) {
            } finally {
                Trace.endSection()
                postEndNs = System.nanoTime()
            }
        }
        return DrawTiming(
            frameTimeNs = frameTimeNs,
            callbackStartNs = callbackStartNs,
            lockWaitMs = nsToMs(lockEndNs - lockStartNs),
            drawMs = nsToMs(drawEndNs - lockEndNs),
            postMs = nsToMs(postEndNs - drawEndNs),
            totalMs = nsToMs(postEndNs - callbackStartNs),
            postEndNs = postEndNs,
            posted = true
        )
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
            canvas.drawText("회차 전환", state.width / 2f, item.top + item.pageHeight / 2f - 34f, textPaint)
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

    private fun buildDrawStateLocked(busy: Boolean = lastBusy): DrawState {
        val viewWidth = max(1, width)
        val viewHeight = max(1, height)
        if (pages.isEmpty()) return DrawState(viewWidth, viewHeight, busy, true, emptyList())
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
        return DrawState(viewWidth, viewHeight, busy, false, items)
    }

    private fun requestRender() {
        synchronized(stateLock) {
            renderRequested = true
            scheduleFrameLocked(preferImmediate = pointerDown || dragging)
            stateLock.notifyAll()
        }
    }

    private fun isEmpty(): Boolean = synchronized(stateLock) { pages.isEmpty() }

    private fun setBusyLocked(busy: Boolean): WindowRequest? {
        if (lastBusy == busy) return null
        lastBusy = busy
        renderRequested = true
        return windowRequestLocked(busy)
    }

    private fun scheduleFrameLocked(preferImmediate: Boolean = false) {
        if (!renderRunning || !surfaceReady) return
        val handler = renderHandler
        val choreographer = renderChoreographer
        if (handler == null && choreographer == null) return
        val nowNs = System.nanoTime()
        val canRenderImmediate = preferImmediate &&
            handler != null &&
            !immediateFrameScheduled &&
            (lastPostedFrameEndNs == 0L || nowNs - lastPostedFrameEndNs >= IMMEDIATE_FRAME_MIN_INTERVAL_NS)
        if (frameScheduled) {
            statsCoalescedRequests++
            return
        }
        frameToken++
        val token = frameToken
        if (canRenderImmediate) {
            frameScheduled = true
            immediateFrameScheduled = true
            handler.post { renderFrame(System.nanoTime(), token) }
            return
        }
        val callback = Choreographer.FrameCallback { frameTimeNanos ->
            renderFrame(frameTimeNanos, token)
        }
        pendingFrameCallback = callback
        frameScheduled = true
        if (choreographer != null) {
            choreographer.postFrameCallback(callback)
        } else if (handler != null) {
            handler.post {
                val shouldPost = synchronized(stateLock) {
                    pendingFrameCallback === callback &&
                        frameToken == token &&
                        renderRunning &&
                        surfaceReady
                }
                if (shouldPost) Choreographer.getInstance().postFrameCallback(callback)
            }
        }
    }

    private fun ensureRenderThreadLocked() {
        if (renderThread?.isAlive == true && renderHandler != null) return
        val thread = HandlerThread("ReaderSurfaceRenderer", Process.THREAD_PRIORITY_DISPLAY)
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
        immediateFrameScheduled = false
        frameToken++
        val callback = pendingFrameCallback
        pendingFrameCallback = null
        if (handler == null || thread == null) return
        handler.post {
            callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
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
        synchronized(stateLock) {
            pendingWindowRequest = request
            if (windowDispatchPosted) return
            windowDispatchPosted = true
        }
        mainHandler.post {
            val latest = synchronized(stateLock) {
                windowDispatchPosted = false
                val next = pendingWindowRequest
                pendingWindowRequest = null
                next
            } ?: return@post
            val currentListener = listener ?: return@post
            currentListener.onWindowChanged(latest.firstPage, latest.lastPage, latest.anchorPage, latest.busy)
            if (latest.nearEnd) currentListener.onNearEnd(latest.anchorPage)
        }
    }

    private fun dispatchBoundaryRequest(request: BoundaryRequest?) {
        if (request == null) return
        mainHandler.post {
            listener?.onBoundaryReached(request.direction, request.anchorPage)
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

    private fun boundaryRequestLocked(): BoundaryRequest? {
        val direction = boundaryArmedDirection
        boundaryArmedDirection = 0
        if (direction == 0 || pages.isEmpty() || width <= 0 || height <= 0) return null
        val maxScroll = max(0f, totalHeightLocked() - height)
        val atStart = scrollOffset <= BOUNDARY_EPSILON_PX
        val atEnd = scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX
        return when {
            direction == DIRECTION_PREVIOUS && atStart -> BoundaryRequest(direction, anchorPageLocked())
            direction == DIRECTION_NEXT && atEnd -> BoundaryRequest(direction, anchorPageLocked())
            else -> null
        }
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
            top += pageDrawHeightLocked(pages[i]) + pageGapPx
        }
        contentHeight = max(0f, top - pageGapPx)
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
            val bottom = pageTops[mid] + pageDrawHeightLocked(pages[mid]) + pageGapPx
            if (position <= bottom) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun directionForDelta(delta: Float): Int {
        return when {
            delta > 0f -> DIRECTION_NEXT
            delta < 0f -> DIRECTION_PREVIOUS
            else -> 0
        }
    }

    private fun isNearVisibleLocked(index: Int, extraPages: Int): Boolean {
        if (pages.isEmpty()) return false
        val anchor = anchorPageLocked()
        return index in (anchor - extraPages)..(anchor + extraPages)
    }

    private fun noteInputLocked(event: MotionEvent) {
        fun addInputTime(uptimeMs: Long) {
            val eventNs = uptimeMsToNanoTime(uptimeMs)
            if (pendingOldestInputNs == 0L || eventNs < pendingOldestInputNs) pendingOldestInputNs = eventNs
            if (eventNs > pendingNewestInputNs) pendingNewestInputNs = eventNs
        }
        for (i in 0 until event.historySize) addInputTime(event.getHistoricalEventTime(i))
        addInputTime(event.eventTime)
        pendingInputEvents++
        pendingHistorySamples += event.historySize
    }

    private fun uptimeMsToNanoTime(uptimeMs: Long): Long {
        val nowNs = System.nanoTime()
        val nowUptimeNs = SystemClock.uptimeMillis() * 1_000_000L
        return nowNs - (nowUptimeNs - uptimeMs * 1_000_000L)
    }

    private fun consumePendingInputLocked(): PendingInput? {
        if (pendingInputEvents <= 0 || pendingOldestInputNs <= 0L) return null
        val input = PendingInput(
            oldestNs = pendingOldestInputNs,
            newestNs = pendingNewestInputNs,
            events = pendingInputEvents,
            history = pendingHistorySamples
        )
        pendingOldestInputNs = 0L
        pendingNewestInputNs = 0L
        pendingInputEvents = 0
        pendingHistorySamples = 0
        return input
    }

    private fun recordFrameStats(timing: DrawTiming, active: Boolean) {
        val pendingInput = synchronized(stateLock) { consumePendingInputLocked() }
        if (active) {
            if (!statsActive) {
                statsActive = true
                statsLastCallbackStartNs = timing.callbackStartNs
                statsLastPostEndNs = if (timing.posted) timing.postEndNs else 0L
                clearStatsSamples()
                if (!timing.posted) statsNoCanvasFrames++
                return
            }
            if (statsLastCallbackStartNs > 0L && timing.callbackStartNs > statsLastCallbackStartNs) {
                statsCallbackSpacingMs.add(nsToMs(timing.callbackStartNs - statsLastCallbackStartNs))
            }
            if (timing.posted && statsLastPostEndNs > 0L && timing.postEndNs > statsLastPostEndNs) {
                statsPostSpacingMs.add(nsToMs(timing.postEndNs - statsLastPostEndNs))
            }
            if (timing.posted) {
                statsLockWaitMs.add(timing.lockWaitMs)
                statsDrawMs.add(timing.drawMs)
                statsPostMs.add(timing.postMs)
                statsTotalMs.add(timing.totalMs)
                pendingInput?.let {
                    statsInputOldestMs.add(nsToMs(timing.postEndNs - it.oldestNs))
                    statsInputNewestMs.add(nsToMs(timing.postEndNs - it.newestNs))
                }
            } else {
                statsNoCanvasFrames++
            }
            statsLastCallbackStartNs = timing.callbackStartNs
            if (timing.posted) statsLastPostEndNs = timing.postEndNs
            return
        }
        if (!statsActive) return
        val callbackIntervals = ArrayList(statsCallbackSpacingMs)
        val postIntervals = ArrayList(statsPostSpacingMs)
        val lockWait = ArrayList(statsLockWaitMs)
        val draw = ArrayList(statsDrawMs)
        val post = ArrayList(statsPostMs)
        val total = ArrayList(statsTotalMs)
        val inputOldest = ArrayList(statsInputOldestMs)
        val inputNewest = ArrayList(statsInputNewestMs)
        val noCanvas = statsNoCanvasFrames
        val coalesced = statsCoalescedRequests
        statsActive = false
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        clearStatsSamples()
        if (callbackIntervals.isEmpty() && postIntervals.isEmpty() && total.isEmpty()) return
        callbackIntervals.sort()
        postIntervals.sort()
        lockWait.sort()
        draw.sort()
        post.sort()
        total.sort()
        inputOldest.sort()
        inputNewest.sort()
        val nominalBudget = frameBudgetMs()
        val measuredBudget = if (callbackIntervals.size >= MIN_FRAME_SAMPLES) {
            percentile(callbackIntervals, 0.50f).coerceIn(nominalBudget * 0.90f, nominalBudget * 1.25f)
        } else {
            nominalBudget
        }
        val frameSamples = if (postIntervals.isNotEmpty()) postIntervals else callbackIntervals
        val strictOverBudget = frameSamples.count { it > nominalBudget }
        val missedThreshold = measuredBudget * MISSED_VSYNC_FACTOR
        val missedIntervals = frameSamples.count { it > missedThreshold }
        var missedFrames = 0
        for (interval in frameSamples) missedFrames += max(0, kotlin.math.floor(interval / measuredBudget - 0.5f).toInt())
        val strictPercent = if (frameSamples.isEmpty()) 0f else strictOverBudget * 100f / frameSamples.size
        val missedPercent = if (frameSamples.isEmpty()) 0f else missedIntervals * 100f / frameSamples.size
        Log.i(
            TAG,
            "surface_jank_v3 samples=${frameSamples.size} nominalBudget=${fmt(nominalBudget)} measuredBudget=${fmt(measuredBudget)} " +
                "strictOverBudget=$strictOverBudget strictPct=${fmt(strictPercent)} " +
                "missedIntervals=$missedIntervals missedFrames=$missedFrames missedPct=${fmt(missedPercent)} " +
                "callbackP95=${fmt(percentile(callbackIntervals, 0.95f))} callbackMax=${fmt(maxOrZero(callbackIntervals))} " +
                "postP95=${fmt(percentile(postIntervals, 0.95f))} postMax=${fmt(maxOrZero(postIntervals))} " +
                "lockP95=${fmt(percentile(lockWait, 0.95f))} drawP95=${fmt(percentile(draw, 0.95f))} " +
                "unlockP95=${fmt(percentile(post, 0.95f))} totalP95=${fmt(percentile(total, 0.95f))} totalMax=${fmt(maxOrZero(total))} " +
                "inputOldestP95=${fmt(percentile(inputOldest, 0.95f))} inputNewestP95=${fmt(percentile(inputNewest, 0.95f))} " +
                "noCanvas=$noCanvas coalesced=$coalesced"
        )
    }

    private fun clearStatsSamples() {
        statsCallbackSpacingMs.clear()
        statsPostSpacingMs.clear()
        statsLockWaitMs.clear()
        statsDrawMs.clear()
        statsPostMs.clear()
        statsTotalMs.clear()
        statsInputOldestMs.clear()
        statsInputNewestMs.clear()
        statsNoCanvasFrames = 0
        statsCoalescedRequests = 0
    }

    private fun percentile(sorted: List<Float>, percentile: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun maxOrZero(sorted: List<Float>): Float = if (sorted.isEmpty()) 0f else sorted.last()

    private fun nsToMs(ns: Long): Float = ns / 1_000_000f

    private fun frameBudgetMs(): Float {
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.refreshRate else display?.refreshRate
        return if (rate != null && rate > 1f) 1000f / rate else DEFAULT_FRAME_BUDGET_MS
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

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

    companion object {
        const val DIRECTION_PREVIOUS = -1
        const val DIRECTION_NEXT = 1

        private const val TAG = "ReaderSurfaceStats"
        private const val DEFAULT_FRAME_BUDGET_MS = 16.67f
        private const val MISSED_VSYNC_FACTOR = 1.5f
        private const val MIN_FRAME_SAMPLES = 8
        private const val DEFAULT_PAGE_GAP_PX = 0
        private const val BOUNDARY_EPSILON_PX = 2f
        private const val DRAG_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_SCROLL_MULTIPLIER = 1.0f
        private const val IMMEDIATE_FRAME_MIN_INTERVAL_NS = 8_000_000L
    }
}
