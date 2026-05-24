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
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

class ReaderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    interface WindowListener {
        fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, busy: Boolean)
        fun onNearBoundary(direction: Int, anchorPage: Int)
        fun onBoundaryReached(direction: Int, anchorPage: Int)
        fun onTap()
    }

    private data class Page(
        var bitmap: Bitmap? = null,
        var tiles: List<ReaderTile> = emptyList(),
        var width: Int = 0,
        var height: Int = 0,
        var loading: Boolean = false,
        var cardText: String? = null
    )

    private data class DrawItem(
        val bitmap: Bitmap?,
        val tiles: List<ReaderTile>,
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
        val visibleLoading: Int,
        val items: List<DrawItem>
    )

    private data class WindowRequest(
        val firstPage: Int,
        val lastPage: Int,
        val anchorPage: Int,
        val busy: Boolean,
        val nearStart: Boolean,
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
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var pageGapPx = DEFAULT_PAGE_GAP_PX
    private val mainHandler = Handler(Looper.getMainLooper())

    private var velocityTracker: VelocityTracker? = null
    private var renderRunning = false
    private var renderRequested = false
    private var frameScheduled = false
    private var frameToken = 0
    private var lastY = 0f
    private var downY = 0f
    private var pendingDragY = Float.NaN
    private var lastVelocitySampleMs = 0L
    private var pointerDown = false
    private var dragging = false
    private var scrollOffset = 0f
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastBusy = false
    private var lastRequestedBusy = false
    private var lastBusyWindowDispatchMs = 0L
    private var layoutDirty = true
    private var pageTops = FloatArrayList(0)
    private var pageTopDeltas = RangeAddPointQuery(0)
    private var contentHeight = 0f
    private var statsActive = false
    private var statsLastCallbackStartNs = 0L
    private var statsLastPostEndNs = 0L
    private var lastPostedFrameEndNs = 0L
    private var statsCoalescedRequests = 0
    private var statsNoCanvasFrames = 0
    private var lastVisibleLoading = -1
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
        isFocusable = true
        isClickable = true
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
            rebuildLayoutLocked()
            val oldMaxScroll = max(0f, contentHeight - height).toInt()
            val shouldExtendActiveFling = !scroller.isFinished &&
                boundaryArmedDirection == DIRECTION_NEXT &&
                scroller.finalY >= oldMaxScroll - BOUNDARY_FLING_EXTEND_EPSILON_PX
            appendEmptyPagesLocked(count - pages.size)
            val newMaxScroll = max(0f, contentHeight - height).toInt()
            if (shouldExtendActiveFling && newMaxScroll > oldMaxScroll) {
                val velocity = scroller.currVelocity
                    .coerceAtLeast(minVelocity.toFloat() * BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER)
                    .coerceAtMost(maxVelocity.toFloat())
                    .toInt()
                scroller.fling(0, scrollOffset.toInt(), 0, velocity, 0, 0, 0, newMaxScroll)
            }
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
            materializeLayoutDeltasLocked()
            val oldFirstTop = pageTopOrElseLocked(0, 0f)
            repeat(insertedCount) { pages.add(0, Page()) }
            layoutDirty = true
            rebuildLayoutLocked()
            val shiftedFirstTop = pageTopOrElseLocked(insertedCount, 0f)
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
            val oldTop = pageTopOrElseLocked(index, 0f)
            page.bitmap = bitmap
            page.tiles = emptyList()
            page.width = max(1, bitmap.width)
            page.height = max(1, bitmap.height)
            page.loading = false
            page.cardText = null
            val newHeight = pageDrawHeightLocked(page)
            if (oldTop + oldHeight <= scrollOffset) scrollOffset += newHeight - oldHeight
            val belowVisible = oldTop > scrollOffset + height
            updatePageHeightDeltaLocked(index, newHeight - oldHeight)
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

    fun setPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            page.bitmap = null
            page.tiles = tiles
            page.width = max(1, pageWidth)
            page.height = max(1, pageHeight)
            page.loading = false
            page.cardText = null
            val newHeight = pageDrawHeightLocked(page)
            if (oldTop + oldHeight <= scrollOffset) scrollOffset += newHeight - oldHeight
            val belowVisible = oldTop > scrollOffset + height
            updatePageHeightDeltaLocked(index, newHeight - oldHeight)
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
            page.tiles = emptyList()
            page.loading = false
            page.cardText = null
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun clearAllPages() {
        synchronized(stateLock) {
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
            }
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun stopRenderingAndClearPages() {
        val thread = synchronized(stateLock) {
            renderRunning = false
            clearInputStateLocked()
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
            }
            layoutDirty = true
            val stopped = stopRenderThreadLocked()
            stateLock.notifyAll()
            stopped
        }
    }

    fun setPageBounds(index: Int, pageWidth: Int, pageHeight: Int) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (page.bitmap != null || page.tiles.isNotEmpty() || page.cardText != null || pageWidth <= 0 || pageHeight <= 0) return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            page.width = pageWidth
            page.height = pageHeight
            val newHeight = pageDrawHeightLocked(page)
            if (oldTop + oldHeight <= scrollOffset) scrollOffset += newHeight - oldHeight
            val belowVisible = oldTop > scrollOffset + height
            updatePageHeightDeltaLocked(index, newHeight - oldHeight)
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
            page.tiles = emptyList()
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
            scrollOffset = pageTopOrElseLocked(target, 0f)
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(stateLock) {
            renderRunning = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
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

    override fun onDetachedFromWindow() {
        synchronized(stateLock) {
            renderRunning = false
            clearInputStateLocked()
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
                synchronized(stateLock) {
                    noteInputLocked(event)
                    scroller.forceFinished(true)
                    lastY = event.y
                    downY = event.y
                    pendingDragY = Float.NaN
                    lastVelocitySampleMs = event.eventTime
                    pointerDown = true
                    dragging = false
                    boundaryArmedDirection = 0
                    if (!lastBusy) {
                        lastBusy = true
                        renderRequested = true
                    }
                }
                requestRender()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var sampleVelocity = false
                val request = synchronized(stateLock) {
                    val shouldSampleVelocity = event.eventTime - lastVelocitySampleMs >= MOVE_VELOCITY_SAMPLE_MS
                    if (shouldSampleVelocity) {
                        lastVelocitySampleMs = event.eventTime
                        sampleVelocity = true
                    }
                    if (frameScheduled) {
                        pendingDragY = event.y
                        null
                    } else {
                        noteInputLocked(event)
                        val request = if (applyDragMoveLocked(event.y)) {
                            renderRequested = true
                            scheduleFrameLocked()
                            stateLock.notifyAll()
                            windowRequestLocked(true)
                        } else {
                            null
                        }
                        sampleVelocity = true
                        request
                    }
                }
                if (sampleVelocity) velocityTracker?.addMovement(event)
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
                    val pendingMoved = applyPendingDragLocked()
                    val upMoved = applyDragMoveLocked(event.y)
                    val wasReleased = event.actionMasked == MotionEvent.ACTION_UP
                    val wasTap = wasReleased && !pendingMoved && !upMoved && !dragging && abs(event.y - downY) <= touchSlop
                    tap = wasTap
                    pointerDown = false
                    dragging = false
                    pendingDragY = Float.NaN
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderFrame(System.nanoTime(), canvas)
    }

    private fun renderFrame(frameTimeNanos: Long, canvas: Canvas) {
        val callbackStartNs = System.nanoTime()
        val work = synchronized(stateLock) {
            frameScheduled = false
            if (!renderRunning) return
            var request: WindowRequest? = null
            var boundary: BoundaryRequest? = null
            if (applyPendingDragLocked()) {
                renderRequested = true
                request = windowRequestLocked(true)
            }
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
        val timing = drawState(frameTimeNanos, callbackStartNs, state, canvas)
        if (lastVisibleLoading != state.visibleLoading) {
            lastVisibleLoading = state.visibleLoading
            Log.i(TAG, "reader_visible_loading=${state.visibleLoading} busy=${state.busy} items=${state.items.size}")
        }
        if (timing.posted) synchronized(stateLock) { lastPostedFrameEndNs = timing.postEndNs }
        recordFrameStats(timing, state.busy)
    }

    private fun drawState(frameTimeNs: Long, callbackStartNs: Long, state: DrawState, canvas: Canvas): DrawTiming {
        val drawStartNs = System.nanoTime()
        var drawEndNs = drawStartNs
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
        }
        val postEndNs = System.nanoTime()
        return DrawTiming(
            frameTimeNs = frameTimeNs,
            callbackStartNs = callbackStartNs,
            lockWaitMs = 0f,
            drawMs = nsToMs(drawEndNs - drawStartNs),
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
            val cardWidth = state.width * TRANSITION_CARD_WIDTH_RATIO
            val cardHeight = min(item.pageHeight * 0.72f, state.height * 0.11f)
            val centerY = item.top + item.pageHeight / 2f
            val top = max(0f, centerY - cardHeight / 2f)
            val bottom = min(state.height.toFloat(), top + cardHeight)
            dst.set(
                (state.width - cardWidth) / 2f,
                top,
                (state.width + cardWidth) / 2f,
                bottom
            )
            paint.color = Color.rgb(14, 14, 14)
            canvas.drawRoundRect(dst, 8f, 8f, paint)
            textPaint.textSize = 21f
            textPaint.color = Color.rgb(160, 160, 160)
            canvas.drawText("회차 전환", state.width / 2f, dst.centerY() - 16f, textPaint)
            textPaint.textSize = 30f
            textPaint.color = Color.WHITE
            canvas.drawText(cardText, state.width / 2f, dst.centerY() + 24f, textPaint)
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
        if (item.tiles.isNotEmpty()) {
            drawTiles(canvas, state, item)
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

    private fun drawTiles(canvas: Canvas, state: DrawState, item: DrawItem) {
        val visibleTop = max(0f, item.top)
        val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
        if (visibleBottom <= visibleTop) return
        paint.isFilterBitmap = !state.busy
        for (tile in item.tiles) {
            val bitmap = tile.bitmap
            if (bitmap.isRecycled || tile.sourceHeight <= 0) continue
            val tileTop = item.top + item.pageHeight * (tile.sourceTop / tile.sourceHeight.toFloat())
            val tileBottom = item.top + item.pageHeight * (tile.sourceBottom / tile.sourceHeight.toFloat())
            val drawTop = max(visibleTop, tileTop)
            val drawBottom = min(visibleBottom, tileBottom)
            if (drawBottom <= drawTop || tileBottom <= tileTop) continue
            val srcTop = ((drawTop - tileTop) / (tileBottom - tileTop) * bitmap.height)
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            val srcBottom = ((drawBottom - tileTop) / (tileBottom - tileTop) * bitmap.height)
                .toInt()
                .coerceIn(srcTop + 1, bitmap.height)
            src.set(0, srcTop, bitmap.width, srcBottom)
            dst.set(0f, drawTop, state.width.toFloat(), drawBottom)
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }

    private fun buildDrawStateLocked(busy: Boolean = lastBusy): DrawState {
        val viewWidth = max(1, width)
        val viewHeight = max(1, height)
        if (pages.isEmpty()) return DrawState(viewWidth, viewHeight, busy, true, 1, emptyList())
        rebuildLayoutLocked()
        val items = ArrayList<DrawItem>()
        var visibleLoading = 0
        var index = firstVisiblePageLocked(scrollOffset)
        while (index < pages.size) {
            val page = pages[index]
            val top = pageTopLocked(index) - scrollOffset
            val pageHeight = pageDrawHeightLocked(page)
            val bottom = top + pageHeight
            if (bottom >= 0f && top <= viewHeight) {
                if (page.loading || (page.bitmap == null && page.tiles.isEmpty() && page.cardText == null)) {
                    visibleLoading++
                }
                items.add(DrawItem(page.bitmap, page.tiles, page.loading, page.cardText, top, pageHeight))
            }
            if (top > viewHeight) break
            index++
        }
        return DrawState(viewWidth, viewHeight, busy, false, visibleLoading, items)
    }

    private fun requestRender() {
        synchronized(stateLock) {
            renderRequested = true
            scheduleFrameLocked(preferImmediate = pointerDown || dragging)
            stateLock.notifyAll()
        }
    }

    private fun isEmpty(): Boolean = synchronized(stateLock) { pages.isEmpty() }

    private fun clearInputStateLocked() {
        velocityTracker?.recycle()
        velocityTracker = null
        pointerDown = false
        dragging = false
        scroller.forceFinished(true)
    }

    private fun setBusyLocked(busy: Boolean): WindowRequest? {
        if (lastBusy == busy) return null
        lastBusy = busy
        renderRequested = true
        return windowRequestLocked(busy)
    }

    private fun scheduleFrameLocked(preferImmediate: Boolean = false) {
        if (!renderRunning) return
        if (frameScheduled) {
            statsCoalescedRequests++
            return
        }
        frameToken++
        frameScheduled = true
        postInvalidateOnAnimation()
    }

    private fun stopRenderThreadLocked(): Thread? {
        frameScheduled = false
        frameToken++
        return null
    }

    private fun windowRequestLocked(busy: Boolean): WindowRequest? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        val anchor = anchorPageLocked()
        if (busy && lastRequestedBusy) {
            val now = SystemClock.uptimeMillis()
            val anchorMoved = lastAnchor < 0 || abs(anchor - lastAnchor) >= BUSY_WINDOW_ANCHOR_STEP
            val intervalElapsed = now - lastBusyWindowDispatchMs >= BUSY_WINDOW_MIN_DISPATCH_MS
            if (!anchorMoved && !intervalElapsed) return null
        }
        if (anchor == lastAnchor && busy == lastRequestedBusy) return null
        lastAnchor = anchor
        lastRequestedBusy = busy
        if (busy) lastBusyWindowDispatchMs = SystemClock.uptimeMillis()
        val first = max(0, anchor - ReaderPipelinePolicy.windowBefore(busy))
        val last = min(pages.lastIndex, anchor + ReaderPipelinePolicy.windowAfter(busy))
        val boundaryPx = height * NEAR_BOUNDARY_SCREENFULS
        val nearStart = scrollOffset <= boundaryPx ||
            anchor <= NEAR_BOUNDARY_PAGE_THRESHOLD
        val remainingPx = contentHeight - (scrollOffset + height)
        val nearEnd = remainingPx <= boundaryPx ||
            anchor >= pages.size - NEAR_BOUNDARY_PAGE_THRESHOLD
        return WindowRequest(first, last, anchor, busy, nearStart, nearEnd)
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
            if (latest.nearStart) currentListener.onNearBoundary(DIRECTION_PREVIOUS, latest.anchorPage)
            if (latest.nearEnd) currentListener.onNearBoundary(DIRECTION_NEXT, latest.anchorPage)
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
        if (page.cardText != null) return max(1f, height * TRANSITION_CARD_PAGE_HEIGHT_RATIO)
        if (page.width > 0 && page.height > 0) return max(1f, viewWidth * (page.height / page.width.toFloat()))
        return max(height * 1.4f, viewWidth * 1.35f)
    }

    private fun rebuildLayoutLocked() {
        if (!layoutDirty && pageTops.size == pages.size && pageTopDeltas.size == pages.size) return
        if (pageTops.size != pages.size) pageTops = FloatArrayList(pages.size)
        if (pageTopDeltas.size != pages.size) pageTopDeltas = RangeAddPointQuery(pages.size)
        var top = 0f
        for (i in pages.indices) {
            pageTops[i] = top
            top += pageDrawHeightLocked(pages[i]) + pageGapPx
        }
        contentHeight = max(0f, top - pageGapPx)
        pageTopDeltas.clear()
        layoutDirty = false
    }

    private fun appendEmptyPagesLocked(additionalCount: Int) {
        if (additionalCount <= 0) return
        materializeLayoutDeltasLocked()
        var top = if (pages.isEmpty()) 0f else contentHeight + pageGapPx
        repeat(additionalCount) {
            val page = Page()
            pages.add(page)
            if (pageTops.size != pages.size) pageTops = pageTops.copyWithSize(pages.size)
            if (pageTopDeltas.size != pages.size) pageTopDeltas = pageTopDeltas.copyWithSize(pages.size)
            pageTops[pages.lastIndex] = top
            top += pageDrawHeightLocked(page) + pageGapPx
        }
        contentHeight = max(0f, top - pageGapPx)
        layoutDirty = false
    }

    private fun updatePageHeightDeltaLocked(index: Int, delta: Float) {
        if (abs(delta) <= 0.01f) return
        pageTopDeltas.add(index + 1, delta)
        contentHeight = max(0f, contentHeight + delta)
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
            val bottom = pageTopLocked(mid) + pageDrawHeightLocked(pages[mid]) + pageGapPx
            if (position <= bottom) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun pageTopLocked(index: Int): Float {
        return pageTops[index] + pageTopDeltas.get(index)
    }

    private fun pageTopOrElseLocked(index: Int, fallback: Float): Float {
        return if (index in 0 until pageTops.size) pageTopLocked(index) else fallback
    }

    private fun materializeLayoutDeltasLocked() {
        if (pageTopDeltas.isEmpty()) return
        for (i in 0 until pageTops.size) {
            pageTops[i] = pageTopLocked(i)
        }
        pageTopDeltas.clear()
    }

    private fun directionForDelta(delta: Float): Int {
        return when {
            delta > 0f -> DIRECTION_NEXT
            delta < 0f -> DIRECTION_PREVIOUS
            else -> 0
        }
    }

    private fun applyPendingDragLocked(): Boolean {
        val y = pendingDragY
        if (y.isNaN()) return false
        pendingDragY = Float.NaN
        return applyDragMoveLocked(y)
    }

    private fun applyDragMoveLocked(y: Float): Boolean {
        val dy = lastY - y
        if (dy == 0f) return false
        if (!dragging) dragging = true
        setBusyLocked(true)
        val direction = directionForDelta(dy)
        if (direction != 0) boundaryArmedDirection = direction
        scrollOffset += dy * DRAG_SCROLL_MULTIPLIER
        clampScrollLocked()
        lastY = y
        return true
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
                val callbackGapMs = nsToMs(timing.callbackStartNs - statsLastCallbackStartNs)
                statsCallbackSpacingMs.add(callbackGapMs)
                MainThreadStallMonitor.warn("reader_frame_callback_gap", callbackGapMs)
            }
            if (timing.posted && statsLastPostEndNs > 0L && timing.postEndNs > statsLastPostEndNs) {
                val postGapMs = nsToMs(timing.postEndNs - statsLastPostEndNs)
                statsPostSpacingMs.add(postGapMs)
                MainThreadStallMonitor.warn("reader_frame_post_gap", postGapMs)
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

        fun copyWithSize(nextSize: Int): FloatArrayList {
            val copy = FloatArrayList(0)
            copy.values = values.copyOf(max(1, nextSize))
            copy.size = nextSize
            return copy
        }

        fun getOrElse(index: Int, fallback: Float): Float {
            return if (index in 0 until size) values[index] else fallback
        }
    }

    private class RangeAddPointQuery(size: Int) {
        private var tree = FloatArray(max(2, size + 2))
        var size: Int = size
            private set
        private var dirty = false

        fun add(startIndex: Int, delta: Float) {
            if (startIndex >= size || abs(delta) <= 0.01f) return
            addInternal(startIndex + 1, delta)
            dirty = true
        }

        fun get(index: Int): Float {
            if (index !in 0 until size) return 0f
            var i = index + 1
            var sum = 0f
            while (i > 0) {
                sum += tree[i]
                i -= i and -i
            }
            return sum
        }

        fun clear() {
            if (!dirty) return
            java.util.Arrays.fill(tree, 0f)
            dirty = false
        }

        fun isEmpty(): Boolean = !dirty

        fun copyWithSize(nextSize: Int): RangeAddPointQuery {
            val copy = RangeAddPointQuery(nextSize)
            val limit = min(size, nextSize)
            for (i in 0 until limit) {
                val value = get(i)
                if (abs(value) > 0.01f) copy.add(i, value)
            }
            return copy
        }

        private fun addInternal(index: Int, delta: Float) {
            var i = index
            while (i < tree.size) {
                tree[i] += delta
                i += i and -i
            }
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
        private const val TRANSITION_CARD_WIDTH_RATIO = 0.58f
        private const val TRANSITION_CARD_PAGE_HEIGHT_RATIO = 0.14f
        private const val NEAR_BOUNDARY_SCREENFULS = 10
        private const val NEAR_BOUNDARY_PAGE_THRESHOLD = 16
        private const val BUSY_WINDOW_ANCHOR_STEP = 2
        private const val BUSY_WINDOW_MIN_DISPATCH_MS = 48L
        private const val BOUNDARY_EPSILON_PX = 2f
        private const val BOUNDARY_FLING_EXTEND_EPSILON_PX = 4
        private const val BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER = 2f
        private const val DRAG_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_SCROLL_MULTIPLIER = 1.0f
        private const val MOVE_VELOCITY_SAMPLE_MS = 16L
        private const val RENDER_THREAD_STOP_JOIN_MS = 500L
    }
}
