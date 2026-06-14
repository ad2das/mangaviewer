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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

class ReaderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    data class ProgressPosition(
        val page: Int,
        val offset: Int
    )

    data class ScrollPositionSnapshot(
        val page: Int,
        val offset: Int,
        val scrollOffset: Int
    )

    data class VisibleCoverageSnapshot(
        val viewportPx: Int,
        val drawablePx: Int,
        val missingPx: Int,
        val placeholderPx: Int,
        val drawableItems: Int,
        val totalItems: Int,
        val visibleLoading: Int,
        val visibleErrors: Int,
        val visibleCards: Int,
        val busy: Boolean,
        val pageCount: Int
    )

    data class FrameStatsSnapshot(
        val samples: Int,
        val strictOverBudget: Int,
        val missedIntervals: Int,
        val missedFrames: Int,
        val droppedFrames: Int,
        val droppedFrameDebt: Int,
        val callbackP95: Float,
        val callbackMax: Float,
        val drawP95: Float,
        val totalP95: Float,
        val totalMax: Float,
        val noCanvas: Int,
        val coalesced: Int
    )

    interface WindowListener {
        fun onWindowChanged(firstPage: Int, lastPage: Int, anchorPage: Int, progressPage: Int, progressOffset: Int, busy: Boolean)
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
        var cardText: String? = null,
        var errorText: String? = null,
        var pendingResolveType: Int = PENDING_NONE,
        var pendingBitmap: Bitmap? = null,
        var pendingTiles: List<ReaderTile> = emptyList(),
        var pendingWidth: Int = 0,
        var pendingHeight: Int = 0,
        var placeholderRatio: Float = DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO
    )

    private data class DrawItem(
        val index: Int,
        val bitmap: Bitmap?,
        val tiles: List<ReaderTile>,
        val loading: Boolean,
        val cardText: String?,
        val errorText: String?,
        val top: Float,
        val pageHeight: Float
    )

    private data class DrawState(
        val width: Int,
        val height: Int,
        val busy: Boolean,
        val empty: Boolean,
        val visibleLoading: Int,
        val hasDrawableContent: Boolean,
        val scrollOffset: Float,
        val contentHeight: Float,
        val pageCount: Int,
        val items: List<DrawItem>
    )

    private data class CoverageStats(
        val drawablePx: Int,
        val missingPx: Int,
        val placeholderPx: Int,
        val drawableItems: Int,
        val totalItems: Int
    )

    private data class WindowRequest(
        val firstPage: Int,
        val lastPage: Int,
        val anchorPage: Int,
        val progressPage: Int,
        val progressOffset: Int,
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
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 190, 190)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val src = Rect()
    private val dstInt = Rect()
    private val dst = RectF()
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var pageGapPx = DEFAULT_PAGE_GAP_PX
    private var placeholderPageHeightRatio = DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO
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
    private var lastScrollInteractionMs = 0L
    private var pointerDown = false
    private var dragging = false
    private var scrollbarDragging = false
    private var scrollbarDragOffset = 0f
    private var scrollbarVisible = false
    private var scrollOffset = 0f
    private var activeScrollerOffsetShift = 0f
    private var lockedRestorePage = -1
    private var lockedRestoreOffset = 0
    private var lockedRestoreUntilMs = 0L
    private var structuralScrollAdjustUntilMs = 0L
    private var pendingResolveRetryPosted = false
    private var prependedRevealHoldPage = -1
    private var initialRenderHoldPage = -1
    private var initialRenderHoldUntilMs = 0L
    private var initialViewportHoldUntilMs = 0L
    private var deferInitialEmptyDraw = false
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastNearStart = false
    private var lastNearEnd = false
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
    private var lastSlowFrameLogMs = 0L
    private var statsCoalescedRequests = 0
    private var statsNoCanvasFrames = 0
    private var hasDrawnContentFrame = false
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
    private var boundaryDispatchInFlight = false
    private var lastCoverageLog: CoverageStats? = null
    private var lastCoverageLogMs = 0L
    private var lastVisibleCoverageSnapshot: VisibleCoverageSnapshot? = null
    private var lastFrameStatsSnapshot: FrameStatsSnapshot? = null

    init {
        isFocusable = true
        isClickable = true
    }

    fun setWindowListener(listener: WindowListener?) {
        this.listener = listener
    }

    fun setScrollbarVisible(visible: Boolean) {
        synchronized(stateLock) {
            if (scrollbarVisible == visible) return
            scrollbarVisible = visible
            if (!visible) {
                scrollbarDragging = false
                scrollbarDragOffset = 0f
            }
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
        requestRender()
    }

    fun setPageGapPx(gapPx: Int) {
        var request: WindowRequest? = null
        synchronized(stateLock) {
            val next = max(0, gapPx)
            if (pageGapPx == next) return
            pageGapPx = next
            layoutDirty = true
            clampScrollLocked()
            renderRequested = pages.isNotEmpty()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            request = if (renderRequested) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
    }

    @JvmOverloads
    fun setPageCount(count: Int, deferInitialEmptyDraw: Boolean = false) {
        val request = synchronized(stateLock) {
            scroller.forceFinished(true)
            activeScrollerOffsetShift = 0f
            clearInputStateLocked()
            lastBusy = false
            lastRequestedBusy = false
            pendingWindowRequest = null
            windowDispatchPosted = false
            pages.clear()
            prependedRevealHoldPage = -1
            repeat(max(0, count)) { pages.add(newPageLocked()) }
            setScrollOffsetLocked(0f)
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            lastAnchor = -1
            lastNearStart = false
            lastNearEnd = false
            hasDrawnContentFrame = false
            this.deferInitialEmptyDraw = deferInitialEmptyDraw
            initialViewportHoldUntilMs = 0L
            structuralScrollAdjustUntilMs = 0L
            lastVisibleCoverageSnapshot = null
            layoutDirty = true
            renderRequested = !this.deferInitialEmptyDraw
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    fun appendPageCount(count: Int, revealAppendedBoundary: Boolean = false) {
        val request = synchronized(stateLock) {
            if (count <= pages.size) return
            rebuildLayoutLocked()
            val oldMaxScroll = max(0f, contentHeight - height).toInt()
            val shouldExtendActiveFling = !scroller.isFinished &&
                boundaryArmedDirection == DIRECTION_NEXT &&
                scroller.finalY >= oldMaxScroll - BOUNDARY_FLING_EXTEND_EPSILON_PX
            val firstAppendedPage = pages.size
            appendEmptyPagesLocked(count - pages.size)
            val newMaxScroll = max(0f, contentHeight - height).toInt()
            if (revealAppendedBoundary && newMaxScroll > oldMaxScroll) {
                lockedRestorePage = firstAppendedPage
                lockedRestoreOffset = 0
                lockedRestoreUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                applyLockedRestorePositionLocked()
                if (!scroller.isFinished) scroller.forceFinished(true)
            } else if (shouldExtendActiveFling && newMaxScroll > oldMaxScroll) {
                val velocity = scroller.currVelocity
                    .coerceAtLeast(minVelocity.toFloat() * BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER)
                    .coerceAtMost(maxVelocity.toFloat())
                    .toInt()
                scroller.fling(0, scrollOffset.toInt(), 0, velocity, 0, 0, 0, newMaxScroll)
            }
            boundaryDispatchInFlight = false
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun prependPageCount(count: Int, insertedCount: Int, revealPrependedBoundary: Boolean = false) {
        val request = synchronized(stateLock) {
            if (insertedCount <= 0 || count <= pages.size) return
            rebuildLayoutLocked()
            materializeLayoutDeltasLocked()
            val oldFirstTop = pageTopOrElseLocked(0, 0f)
            val insertedPlaceholderRatio = representativeResolvedPageRatioLocked()
            repeat(insertedCount) { pages.add(0, newPageLocked(insertedPlaceholderRatio)) }
            if (revealPrependedBoundary) {
                pages.getOrNull(insertedCount - 1)?.let { page ->
                    page.width = width
                    page.height = max(1, (height * 0.38f).toInt())
                    page.loading = false
                    page.cardText = ""
                    page.errorText = null
                    clearPendingResolveLocked(page)
                }
            }
            if (lockedRestorePage >= 0) lockedRestorePage += insertedCount
            if (revealPrependedBoundary) prependedRevealHoldPage = -1
            layoutDirty = true
            rebuildLayoutLocked()
            val shiftedFirstTop = pageTopOrElseLocked(insertedCount, 0f)
            if (lockedRestorePage >= 0 && SystemClock.uptimeMillis() <= lockedRestoreUntilMs) {
                applyLockedRestorePositionLocked()
            } else if (revealPrependedBoundary) {
                val boundaryCardTop = pageTopOrElseLocked(insertedCount - 1, shiftedFirstTop)
                structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                setScrollOffsetLocked(max(0f, boundaryCardTop))
            } else {
                structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                setScrollOffsetLocked(scrollOffset + shiftedFirstTop - oldFirstTop)
            }
            if (!scroller.isFinished) scroller.forceFinished(true)
            activeScrollerOffsetShift = 0f
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun removePageRange(startIndex: Int, removedCount: Int) {
        if (removedCount <= 0) return
        val request = synchronized(stateLock) {
            if (startIndex !in pages.indices) return
            val endExclusive = min(pages.size, startIndex + removedCount)
            if (endExclusive <= startIndex) return
            rebuildLayoutLocked()
            repeat(endExclusive - startIndex) { pages.removeAt(startIndex) }
            pageTopDeltas.clear()
            layoutDirty = true
            if (pages.isEmpty()) {
                setScrollOffsetLocked(0f)
                lastAnchor = -1
                prependedRevealHoldPage = -1
            } else {
                if (prependedRevealHoldPage >= endExclusive) {
                    prependedRevealHoldPage -= endExclusive - startIndex
                } else if (prependedRevealHoldPage >= startIndex) {
                    prependedRevealHoldPage = -1
                }
                if (lockedRestorePage >= endExclusive) {
                    lockedRestorePage -= endExclusive - startIndex
                } else if (lockedRestorePage >= startIndex) {
                    lockedRestorePage = -1
                    lockedRestoreOffset = 0
                }
                lastAnchor = lastAnchor.coerceIn(0, pages.lastIndex)
                clampScrollLocked()
            }
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
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
                if (it.cardText == null && it.bitmap == null && it.tiles.isEmpty()) {
                    it.errorText = null
                    it.loading = true
                    clearPendingResolveLocked(it)
                }
            }
            if (shouldSuppressInitialEmptyRenderLocked() || shouldDeferInitialEmptyDrawLocked()) return
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
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(bitmap.width, bitmap.height)
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (shouldDeferHeightChangingResolveLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable)) {
                if (hasCurrentDrawable) {
                    page.bitmap = bitmap
                    page.tiles = emptyList()
                    page.loading = false
                    page.cardText = null
                    page.errorText = null
                    page.pendingResolveType = PENDING_SIZE
                    page.pendingBitmap = null
                } else {
                    page.pendingResolveType = PENDING_BITMAP
                    page.pendingBitmap = bitmap
                }
                page.pendingTiles = emptyList()
                page.pendingWidth = max(1, bitmap.width)
                page.pendingHeight = max(1, bitmap.height)
                deferInitialEmptyDraw = false
                schedulePendingResolveRetryLocked()
                return@synchronized null
            }
            page.bitmap = bitmap
            page.tiles = emptyList()
            page.width = max(1, bitmap.width)
            page.height = max(1, bitmap.height)
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_bitmap", index, oldHeight, newHeight)
            val nearVisible = isNearVisibleLocked(index, BUSY_RESOLVE_RENDER_EXTRA_PAGES)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            if (!lastBusy || nearVisible) {
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
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(pageWidth, pageHeight)
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (shouldDeferHeightChangingResolveLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable)) {
                if (hasCurrentDrawable) {
                    page.bitmap = null
                    page.tiles = tiles
                    page.loading = false
                    page.cardText = null
                    page.errorText = null
                    page.pendingResolveType = PENDING_SIZE
                    page.pendingTiles = emptyList()
                } else {
                    page.pendingResolveType = PENDING_TILES
                    page.pendingTiles = tiles
                }
                page.pendingBitmap = null
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                deferInitialEmptyDraw = false
                schedulePendingResolveRetryLocked()
                return@synchronized null
            }
            page.bitmap = null
            page.tiles = tiles
            page.width = max(1, pageWidth)
            page.height = max(1, pageHeight)
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_tiles", index, oldHeight, newHeight)
            val nearVisible = isNearVisibleLocked(index, BUSY_RESOLVE_RENDER_EXTRA_PAGES)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            if (!lastBusy || nearVisible) {
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
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            page.bitmap = null
            page.tiles = emptyList()
            page.loading = false
            page.cardText = null
            page.errorText = null
            clearPendingResolveLocked(page)
            val newHeight = pageDrawHeightLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_clear", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            if (!lastBusy || isNearVisibleLocked(index, BUSY_RESOLVE_RENDER_EXTRA_PAGES)) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                if (abs(newHeight - oldHeight) > HEIGHT_CHANGE_EPSILON_PX) {
                    Log.d(
                        TAG,
                        "reader_page_clear_height index=$index old=${oldHeight.toInt()} new=${newHeight.toInt()} " +
                            "offset=${scrollOffset.toInt()} lastBusy=$lastBusy"
                    )
                }
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        dispatchWindowRequest(request)
    }

    fun clearAllPages() {
        synchronized(stateLock) {
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
            }
            hasDrawnContentFrame = false
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun stopRenderingAndClearPages() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(stateLock) {
            renderRunning = false
            renderRequested = false
            frameScheduled = false
            clearInputStateLocked()
            resetActiveFrameStatsLocked()
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
            }
            hasDrawnContentFrame = false
            layoutDirty = true
            stopRenderThreadLocked()
            stateLock.notifyAll()
        }
    }

    fun setPageBounds(index: Int, pageWidth: Int, pageHeight: Int) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (page.bitmap != null || page.tiles.isNotEmpty() || page.cardText != null || page.errorText != null || pageWidth <= 0 || pageHeight <= 0) return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(pageWidth, pageHeight)
            if (shouldDeferHeightChangingResolveLocked(oldTop, oldHeight, newHeight, false)) {
                page.pendingResolveType = PENDING_BOUNDS
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                schedulePendingResolveRetryLocked()
                return@synchronized null
            }
            page.width = pageWidth
            page.height = pageHeight
            clearPendingResolveLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_bounds", index, oldHeight, newHeight)
            val nearVisible = isNearVisibleLocked(index, BUSY_RESOLVE_RENDER_EXTRA_PAGES)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            if (!shouldSuppressInitialEmptyRenderLocked()
                && !shouldDeferInitialEmptyDrawLocked()
                && (!lastBusy || nearVisible)
            ) {
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
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            page.bitmap = null
            page.tiles = emptyList()
            page.width = width
            page.height = max(1, (height * 0.38f).toInt())
            page.loading = false
            page.cardText = title
            page.errorText = null
            clearPendingResolveLocked(page)
            deferInitialEmptyDraw = false
            val newHeight = pageDrawHeightLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_card", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun setPageError(index: Int, message: String) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            page.bitmap = null
            page.tiles = emptyList()
            page.width = width
            page.height = max(1, (height * 0.38f).toInt())
            page.loading = false
            page.cardText = null
            page.errorText = message
            clearPendingResolveLocked(page)
            deferInitialEmptyDraw = false
            val newHeight = pageDrawHeightLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_error", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun scrollToPage(index: Int) {
        scrollToPage(index, 0)
    }

    fun scrollToPage(index: Int, offset: Int) {
        val request = synchronized(stateLock) {
            val target = index.coerceIn(0, pages.lastIndex)
            rebuildLayoutLocked()
            activeScrollerOffsetShift = 0f
            setScrollOffsetLocked(pageTopOrElseLocked(target, 0f) - offset)
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    fun lockRestoredPageOffset(index: Int, offset: Int) {
        val request = synchronized(stateLock) {
            if (index !in 0 until pages.size) return
            lockedRestorePage = index
            lockedRestoreOffset = offset
            lockedRestoreUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = !shouldSuppressInitialEmptyRenderLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            if (renderRequested) windowRequestLocked(false) else null
        }
        dispatchWindowRequest(request)
    }

    fun holdInitialRestoreRender(index: Int) {
        synchronized(stateLock) {
            if (index !in 0 until pages.size) return
            initialRenderHoldPage = index
            initialRenderHoldUntilMs = SystemClock.uptimeMillis() + INITIAL_RENDER_HOLD_MS
            initialViewportHoldUntilMs = max(initialViewportHoldUntilMs, initialRenderHoldUntilMs)
            if (pageHasDrawableContentLocked(index)) {
                renderRequested = true
                scheduleFrameLocked()
            }
            stateLock.notifyAll()
        }
        mainHandler.postDelayed({ requestRender() }, INITIAL_RENDER_HOLD_MS + 32L)
    }

    fun currentProgressPosition(): ProgressPosition? {
        return synchronized(stateLock) {
            progressPositionLocked()
        }
    }

    fun currentScrollPositionSnapshot(): ScrollPositionSnapshot? {
        return synchronized(stateLock) {
            val progress = progressPositionLocked() ?: return@synchronized null
            ScrollPositionSnapshot(progress.page, progress.offset, scrollOffset.toInt())
        }
    }

    fun testScrollByPixels(deltaPx: Float) {
        synchronized(stateLock) {
            rebuildLayoutLocked()
            val maxScroll = max(0f, totalHeightLocked() - height)
            setScrollOffsetLocked((scrollOffset + deltaPx).coerceIn(0f, maxScroll))
            scroller.forceFinished(true)
            lastScrollInteractionMs = SystemClock.uptimeMillis()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
        invalidate()
    }

    fun visibleCoverageSnapshot(): VisibleCoverageSnapshot? {
        return synchronized(stateLock) {
            lastVisibleCoverageSnapshot
        }
    }

    fun frameStatsSnapshot(): FrameStatsSnapshot? {
        return synchronized(stateLock) {
            lastFrameStatsSnapshot
        }
    }

    fun resetFrameStatsSnapshot() {
        synchronized(stateLock) {
            lastFrameStatsSnapshot = null
            resetActiveFrameStatsLocked()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(stateLock) {
            renderRunning = true
            renderRequested = pages.isNotEmpty() && !shouldBlockInitialEmptyFrameLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val request = synchronized(stateLock) {
            if (width != oldWidth || height != oldHeight) {
                materializeLayoutDeltasLocked()
                layoutDirty = true
            }
            clampScrollLocked()
            lastAnchor = -1
            val hasPages = pages.isNotEmpty()
            renderRequested = hasPages && !shouldBlockInitialEmptyFrameLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            if (hasPages) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(stateLock) {
            renderRunning = false
            renderRequested = false
            frameScheduled = false
            clearInputStateLocked()
            resetActiveFrameStatsLocked()
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
                val scrollbarRequest = synchronized(stateLock) {
                    if (startScrollbarDragLocked(event.x, event.y)) {
                        noteInputLocked(event)
                        lastScrollInteractionMs = event.eventTime
                        scroller.forceFinished(true)
                        activeScrollerOffsetShift = 0f
                        pointerDown = true
                        dragging = true
                        lastBusy = true
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                        windowRequestLocked(true)
                    } else {
                        null
                    }
                }
                if (scrollbarDragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    dispatchWindowRequest(scrollbarRequest)
                    requestRender()
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                synchronized(stateLock) {
                    noteInputLocked(event)
                    lastScrollInteractionMs = event.eventTime
                    scroller.forceFinished(true)
                    activeScrollerOffsetShift = 0f
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
                if (scrollbarDragging) {
                    val request = synchronized(stateLock) {
                        noteInputLocked(event)
                        moveScrollbarDragLocked(event.y)
                    }
                    dispatchWindowRequest(request)
                    requestRender()
                    return true
                }
                var sampleVelocity = false
                val request = synchronized(stateLock) {
                    val shouldSampleVelocity = event.eventTime - lastVelocitySampleMs >= MOVE_VELOCITY_SAMPLE_MS
                    if (shouldSampleVelocity) {
                        lastVelocitySampleMs = event.eventTime
                        sampleVelocity = true
                    }
                    if (frameScheduled) {
                        noteInputLocked(event)
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
                if (scrollbarDragging) {
                    val request = synchronized(stateLock) {
                        noteInputLocked(event)
                        moveScrollbarDragLocked(event.y)
                        scrollbarDragging = false
                        pointerDown = false
                        dragging = false
                        pendingDragY = Float.NaN
                        setBusyLocked(false)
                    }
                    dispatchWindowRequest(request)
                    requestRender()
                    return true
                }
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
                    val dragDistance = abs(event.y - downY)
                    val cancelledBoundaryDrag = event.actionMasked == MotionEvent.ACTION_CANCEL &&
                        shouldDispatchCancelledBoundaryLocked(dragDistance)
                    val canFling = shouldStartFling(dragDistance, velocityY, minVelocity, touchSlop)
                    if (wasTap) {
                        boundaryArmedDirection = 0
                        setBusyLocked(false) to null
                    } else if (wasReleased && canFling && abs(velocityY) > minVelocity) {
                        val flingVelocity = (velocityY * FLING_SCROLL_MULTIPLIER)
                            .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                            .toInt()
                        Log.d(
                            TAG,
                            "reader_fling_start drag=${dragDistance.toInt()} velocity=$velocityY " +
                                "scaled=$flingVelocity minVelocity=$minVelocity touchSlop=$touchSlop " +
                                "offset=${scrollOffset.toInt()}"
                        )
                        boundaryArmedDirection = directionForDelta(flingVelocity.toFloat())
                        if (boundaryArmedDirection != 0) lastScrollInteractionMs = event.eventTime
                        val busyRequest = setBusyLocked(true)
                        activeScrollerOffsetShift = 0f
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
                        (busyRequest ?: windowRequestLocked(true)) to boundaryRequestLocked()
                    } else {
                        if (wasReleased && dragDistance > touchSlop && abs(velocityY) > minVelocity) {
                            Log.d(
                                TAG,
                                "reader_fling_suppressed drag=${dragDistance.toInt()} velocity=$velocityY " +
                                    "minVelocity=$minVelocity touchSlop=$touchSlop offset=${scrollOffset.toInt()}"
                            )
                        }
                        val request = setBusyLocked(false)
                        val shouldDispatchBoundary = wasReleased || cancelledBoundaryDrag
                        val boundary = if (shouldDispatchBoundary) boundaryRequestLocked() else null
                        if (!shouldDispatchBoundary) boundaryArmedDirection = 0
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
                lastScrollInteractionMs = SystemClock.uptimeMillis()
                setScrollOffsetLocked(scroller.currY.toFloat() + activeScrollerOffsetShift)
                clampScrollLocked()
                renderRequested = true
                request = windowRequestLocked(true)
                boundary = boundaryRequestLocked(clearDirection = false)
            } else if (scroller.isFinished && activeScrollerOffsetShift != 0f) {
                activeScrollerOffsetShift = 0f
            }
            val wasBusy = lastBusy
            val busyNow = pointerDown || dragging || scrolling || !scroller.isFinished
            if (busyNow != lastBusy) {
                request = setBusyLocked(busyNow) ?: request
            } else if (busyNow) {
                request = windowRequestLocked(true) ?: request
            }
            if (wasBusy && !busyNow) boundary = boundaryRequestLocked() ?: boundary
            if (!busyNow) applyPendingPageResolvesLocked()
            val animateScroll = dragging || scrolling || !scroller.isFinished
            val shouldDraw = (renderRequested || animateScroll) && pages.isNotEmpty()
            val state = if (shouldDraw && !shouldDeferInitialEmptyDrawLocked()) buildDrawStateLocked(busyNow) else null
            if (renderRequested && pages.isEmpty()) renderRequested = false
            if (shouldDraw) renderRequested = false
            if (animateScroll) scheduleFrameLocked()
            RenderWork(request, boundary, state)
        }
        dispatchWindowRequest(work.request)
        dispatchBoundaryRequest(work.boundary)
        val state = work.state ?: return
        val timing = drawState(frameTimeNanos, callbackStartNs, state, canvas)
        val nowMs = SystemClock.uptimeMillis()
        if (timing.totalMs > frameBudgetMs() && nowMs - lastSlowFrameLogMs >= SLOW_FRAME_LOG_INTERVAL_MS) {
            lastSlowFrameLogMs = nowMs
            Log.d(
                TAG,
                "reader_slow_frame busy=${state.busy} items=${state.items.size} " +
                    "visibleLoading=${state.visibleLoading} drawMs=${fmt(timing.drawMs)} " +
                    "totalMs=${fmt(timing.totalMs)} visibleItems=${formatDrawItems(state.items)}"
            )
        }
        if (lastVisibleLoading != state.visibleLoading) {
            lastVisibleLoading = state.visibleLoading
            Log.i(
                TAG,
                "reader_visible_loading=${state.visibleLoading} busy=${state.busy} " +
                    "items=${state.items.size} visibleItems=${formatDrawItems(state.items)}"
            )
            logCoverageIfNeeded(state, force = true)
        }
        logCoverageIfNeeded(state, force = false)
        if (timing.posted) synchronized(stateLock) { lastPostedFrameEndNs = timing.postEndNs }
        updateVisibleCoverageSnapshot(state)
        recordFrameStats(timing, state.busy)
    }

    private fun drawState(frameTimeNs: Long, callbackStartNs: Long, state: DrawState, canvas: Canvas): DrawTiming {
        val drawStartNs = System.nanoTime()
        var drawEndNs = drawStartNs
        try {
            Trace.beginSection("RSV.draw")
            canvas.drawColor(PAGE_PLACEHOLDER_COLOR)
            if (!state.empty) {
                val fastInitialBitmapDraw = !hasDrawnContentFrame && state.hasDrawableContent
                for (item in state.items) drawItem(canvas, state, item, fastInitialBitmapDraw)
            }
            drawScrollbar(canvas, state)
            if (state.hasDrawableContent) hasDrawnContentFrame = true
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

    private fun drawItem(canvas: Canvas, state: DrawState, item: DrawItem, fastBitmapDraw: Boolean) {
        val bitmap = item.bitmap
        val cardText = item.cardText
        if (cardText != null) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) return
            val centerY = item.top + item.pageHeight / 2f
            val cardHeight = min(item.pageHeight, TRANSITION_CARD_BODY_HEIGHT_PX)
            val top = centerY - cardHeight / 2f
            val bottom = top + cardHeight
            val save = canvas.save()
            canvas.clipRect(0f, visibleTop, state.width.toFloat(), visibleBottom)
            dst.set(
                0f,
                top,
                state.width.toFloat(),
                bottom
            )
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawRect(dst, paint)
            textPaint.textSize = 38f
            textPaint.color = Color.rgb(190, 190, 190)
            canvas.drawText("회차 전환", state.width / 2f, dst.centerY() - 28f, textPaint)
            textPaint.textSize = 54f
            textPaint.color = Color.WHITE
            canvas.drawText(cardText, state.width / 2f, dst.centerY() + 40f, textPaint)
            textPaint.textSize = 34f
            textPaint.color = Color.rgb(190, 190, 190)
            canvas.restoreToCount(save)
            return
        }
        val errorText = item.errorText
        if (errorText != null) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) return
            val save = canvas.save()
            canvas.clipRect(0f, visibleTop, state.width.toFloat(), visibleBottom)
            paint.style = Paint.Style.FILL
            paint.color = PAGE_PLACEHOLDER_COLOR
            canvas.drawRect(0f, visibleTop, state.width.toFloat(), visibleBottom, paint)
            textPaint.textSize = 42f
            textPaint.color = Color.rgb(90, 90, 90)
            canvas.drawText(errorText, state.width / 2f, item.top + item.pageHeight / 2f + 15f, textPaint)
            canvas.restoreToCount(save)
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
            val dstTop = floor(visibleTop).toInt()
            val dstBottom = ceil(visibleBottom).toInt().coerceAtLeast(dstTop + 1)
            dstInt.set(0, dstTop, state.width, dstBottom)
            prepareBitmapPaint(fastBitmapDraw)
            canvas.drawBitmap(bitmap, src, dstInt, paint)
            return
        }
        if (item.tiles.isNotEmpty()) {
            drawTiles(canvas, state, item, fastBitmapDraw)
            return
        }
        paint.color = PAGE_PLACEHOLDER_COLOR
        dst.set(0f, max(0f, item.top), state.width.toFloat(), min(state.height.toFloat(), item.top + item.pageHeight))
        canvas.drawRect(dst, paint)
    }

    private fun drawTiles(canvas: Canvas, state: DrawState, item: DrawItem, fastBitmapDraw: Boolean) {
        val visibleTop = max(0f, item.top)
        val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
        if (visibleBottom <= visibleTop) return
        prepareBitmapPaint(fastBitmapDraw)
        val sourceHeight = item.tiles.firstOrNull()?.sourceHeight?.takeIf { it > 0 } ?: return
        val pageScale = item.pageHeight / sourceHeight.toFloat()
        for (tile in item.tiles) {
            val tileTop = item.top + tile.sourceTop * pageScale
            val tileBottom = item.top + tile.sourceBottom * pageScale
            if (tileBottom < visibleTop) continue
            if (tileTop > visibleBottom) break
            val bitmap = tile.bitmap
            if (bitmap.isRecycled || tile.sourceHeight <= 0) continue
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
            val dstTop = if (drawTop > visibleTop) drawTop - TILE_SEAM_OVERLAP_PX else drawTop
            val dstBottom = if (drawBottom < visibleBottom) drawBottom + TILE_SEAM_OVERLAP_PX else drawBottom
            val dstTopInt = floor(dstTop).toInt()
            val dstBottomInt = ceil(dstBottom).toInt().coerceAtLeast(dstTopInt + 1)
            dstInt.set(0, dstTopInt, state.width, dstBottomInt)
            canvas.drawBitmap(bitmap, src, dstInt, paint)
        }
    }

    private fun prepareBitmapPaint(fastBitmapDraw: Boolean) {
        paint.alpha = 255
        paint.colorFilter = null
        paint.isDither = true
        paint.isFilterBitmap = !fastBitmapDraw
    }

    private fun drawScrollbar(canvas: Canvas, state: DrawState) {
        if (!scrollbarVisible && !scrollbarDragging) return
        if (state.height <= 0 || state.contentHeight <= state.height) return
        val thumb = scrollbarThumbRectLocked(state.scrollOffset, state.contentHeight, state.height, state.width)
        paint.style = Paint.Style.FILL
        paint.color = SCROLLBAR_TRACK_COLOR
        dst.set(
            state.width - SCROLLBAR_RIGHT_MARGIN_PX - SCROLLBAR_TRACK_WIDTH_PX,
            0f,
            state.width - SCROLLBAR_RIGHT_MARGIN_PX,
            state.height.toFloat()
        )
        canvas.drawRoundRect(dst, SCROLLBAR_TRACK_RADIUS_PX, SCROLLBAR_TRACK_RADIUS_PX, paint)
        paint.color = if (scrollbarDragging) SCROLLBAR_THUMB_ACTIVE_COLOR else SCROLLBAR_THUMB_COLOR
        canvas.drawRoundRect(thumb, SCROLLBAR_THUMB_RADIUS_PX, SCROLLBAR_THUMB_RADIUS_PX, paint)
        paint.color = SCROLLBAR_GRIP_COLOR
        val gripLeft = thumb.left + SCROLLBAR_GRIP_INSET_PX
        val gripRight = thumb.right - SCROLLBAR_GRIP_INSET_PX
        val gripCenterY = thumb.centerY()
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY - SCROLLBAR_GRIP_GAP_PX,
            gripRight,
            gripCenterY - SCROLLBAR_GRIP_GAP_PX + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY,
            gripRight,
            gripCenterY + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY + SCROLLBAR_GRIP_GAP_PX,
            gripRight,
            gripCenterY + SCROLLBAR_GRIP_GAP_PX + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
    }

    private fun startScrollbarDragLocked(x: Float, y: Float): Boolean {
        rebuildLayoutLocked()
        val viewWidth = width
        val viewHeight = height
        val maxScroll = max(0f, contentHeight - viewHeight)
        if (!scrollbarVisible) return false
        if (viewWidth <= 0 || viewHeight <= 0 || maxScroll <= 0f) return false
        if (x < viewWidth - SCROLLBAR_TOUCH_WIDTH_PX) return false
        val thumb = scrollbarThumbRectLocked(scrollOffset, contentHeight, viewHeight, viewWidth)
        scrollbarDragging = true
        scrollbarDragOffset = if (y in thumb.top..thumb.bottom) {
            y - thumb.top
        } else {
            thumb.height() / 2f
        }
        moveScrollbarDragLocked(y)
        return true
    }

    private fun moveScrollbarDragLocked(y: Float): WindowRequest? {
        rebuildLayoutLocked()
        val viewHeight = height
        val viewWidth = width
        val maxScroll = max(0f, contentHeight - viewHeight)
        if (viewWidth <= 0 || viewHeight <= 0 || maxScroll <= 0f) return null
        val thumbHeight = scrollbarThumbHeightLocked(contentHeight, viewHeight)
        val trackRange = max(1f, viewHeight - thumbHeight)
        val targetTop = (y - scrollbarDragOffset).coerceIn(0f, trackRange)
        val nextScroll = (targetTop / trackRange) * maxScroll
        if (abs(nextScroll - scrollOffset) <= 0.5f) return null
        setScrollOffsetLocked(nextScroll)
        clampScrollLocked()
        lastScrollInteractionMs = SystemClock.uptimeMillis()
        boundaryArmedDirection = 0
        renderRequested = true
        scheduleFrameLocked()
        stateLock.notifyAll()
        return windowRequestLocked(true)
    }

    private fun scrollbarThumbRectLocked(scroll: Float, totalContentHeight: Float, viewHeight: Int, viewWidth: Int): RectF {
        val thumbHeight = scrollbarThumbHeightLocked(totalContentHeight, viewHeight)
        val maxScroll = max(1f, totalContentHeight - viewHeight)
        val trackRange = max(1f, viewHeight - thumbHeight)
        val top = (scroll.coerceIn(0f, maxScroll) / maxScroll) * trackRange
        val right = viewWidth - SCROLLBAR_RIGHT_MARGIN_PX - ((SCROLLBAR_TRACK_WIDTH_PX - SCROLLBAR_THUMB_WIDTH_PX) / 2f)
        return RectF(
            right - SCROLLBAR_THUMB_WIDTH_PX,
            top,
            right,
            top + thumbHeight
        )
    }

    private fun scrollbarThumbHeightLocked(totalContentHeight: Float, viewHeight: Int): Float {
        if (totalContentHeight <= 0f || viewHeight <= 0) return SCROLLBAR_MIN_THUMB_HEIGHT_PX
        val proportional = viewHeight * (viewHeight / totalContentHeight)
        return proportional.coerceIn(SCROLLBAR_MIN_THUMB_HEIGHT_PX, viewHeight.toFloat())
    }

    private fun buildDrawStateLocked(busy: Boolean = lastBusy): DrawState? {
        val viewWidth = max(1, width)
        val viewHeight = max(1, height)
        if (pages.isEmpty()) {
            return DrawState(viewWidth, viewHeight, busy, true, 1, false, scrollOffset, contentHeight, 0, emptyList())
        }
        applyLockedRestorePositionLocked()
        clampScrollLocked()
        rebuildLayoutLocked()
        if (shouldHoldInitialAnchorRenderLocked()) {
            renderRequested = true
            scheduleFrameLocked()
            return null
        }
        val items = ArrayList<DrawItem>()
        var visibleLoading = 0
        var hasDrawableContent = false
        var index = firstVisiblePageLocked(scrollOffset)
        while (index > 0 && pageTopLocked(index) - scrollOffset > 0f) {
            index--
        }
        while (index < pages.size) {
            val page = pages[index]
            val laidOutTop = pageTopLocked(index) - scrollOffset
            val pageHeight = pageDrawHeightLocked(page)
            var top = laidOutTop
            val previousBottom = items.lastOrNull()?.let { it.top + it.pageHeight }
            if (previousBottom == null) {
                if (top > COVERAGE_EDGE_FILL_PX && top < viewHeight) {
                    top = 0f
                }
            } else {
                if (previousBottom < viewHeight) {
                    if (laidOutTop > previousBottom + COVERAGE_EDGE_FILL_PX ||
                        laidOutTop < previousBottom - COVERAGE_EDGE_FILL_PX
                    ) {
                        top = previousBottom
                    }
                }
            }
            if (top > viewHeight) break
            val bottom = top + pageHeight
            if (bottom > 0f && top < viewHeight) {
                if (
                    page.bitmap == null &&
                    page.tiles.isEmpty() &&
                    page.cardText == null &&
                    page.errorText == null &&
                    top >= viewHeight - COVERAGE_EDGE_PLACEHOLDER_FILL_PX &&
                    ceil(min(viewHeight.toFloat(), bottom) - max(0f, top)).toInt() <= COVERAGE_EDGE_PLACEHOLDER_FILL_PX &&
                    items.lastOrNull()?.let { itemHasDrawable(it) } == true
                ) {
                    index++
                    continue
                }
                if (page.bitmap == null && page.tiles.isEmpty() && page.cardText == null && page.errorText == null) {
                    visibleLoading++
                } else {
                    hasDrawableContent = true
                }
                items.add(DrawItem(index, page.bitmap, page.tiles, page.loading, page.cardText, page.errorText, top, pageHeight))
            }
            index++
        }
        val last = items.lastOrNull()
        if (last != null && visibleLoading == 0 && itemHasDrawable(last)) {
            val bottomGap = viewHeight - ceil(last.top + last.pageHeight).toInt()
            if (bottomGap in 1..COVERAGE_EDGE_PLACEHOLDER_FILL_PX) {
                items[items.lastIndex] = last.copy(pageHeight = last.pageHeight + bottomGap)
            }
        }
        val state = DrawState(
            viewWidth,
            viewHeight,
            busy,
            false,
            visibleLoading,
            hasDrawableContent,
            scrollOffset,
            contentHeight,
            pages.size,
            items
        )
        if (!hasDrawnContentFrame && state.visibleLoading > 0 && shouldHoldInitialViewportRenderLocked()) {
            renderRequested = true
            scheduleFrameLocked()
            return null
        }
        if (!hasDrawnContentFrame && (state.visibleLoading == 0 || !shouldHoldInitialViewportRenderLocked())) {
            clearInitialRenderHoldLocked()
        }
        if (hasDrawnContentFrame && !state.hasDrawableContent) {
            renderRequested = true
            scheduleFrameLocked()
            return null
        }
        return state
    }

    private fun logCoverageIfNeeded(state: DrawState, force: Boolean) {
        if (!force && state.busy) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCoverageLogMs < BUSY_COVERAGE_LOG_INTERVAL_MS) return
        }
        val coverage = coverageStats(state)
        if (force || coverage != lastCoverageLog) {
            lastCoverageLog = coverage
            lastCoverageLogMs = SystemClock.uptimeMillis()
            Log.i(
                TAG,
                "reader_visible_coverage drawablePx=${coverage.drawablePx} " +
                    "missingPx=${coverage.missingPx} placeholderPx=${coverage.placeholderPx} " +
                    "drawableItems=${coverage.drawableItems} items=${coverage.totalItems}"
            )
            if (coverage.missingPx > COVERAGE_EDGE_FILL_PX) {
                Log.i(
                    TAG,
                    "reader_visible_gap scroll=${formatFloat(state.scrollOffset)} " +
                        "content=${formatFloat(state.contentHeight)} pages=${state.pageCount} " +
                        "busy=${state.busy} loading=${state.visibleLoading} " +
                        "items=${formatDrawItems(state.items)}"
                )
            }
        }
    }

    private fun updateVisibleCoverageSnapshot(state: DrawState) {
        val coverage = coverageStats(state)
        var visibleErrors = 0
        var visibleCards = 0
        for (item in state.items) {
            if (item.errorText != null) visibleErrors++
            if (item.cardText != null) visibleCards++
        }
        synchronized(stateLock) {
            lastVisibleCoverageSnapshot = VisibleCoverageSnapshot(
                viewportPx = state.height,
                drawablePx = coverage.drawablePx,
                missingPx = coverage.missingPx,
                placeholderPx = coverage.placeholderPx,
                drawableItems = coverage.drawableItems,
                totalItems = coverage.totalItems,
                visibleLoading = state.visibleLoading,
                visibleErrors = visibleErrors,
                visibleCards = visibleCards,
                busy = state.busy,
                pageCount = state.pageCount
            )
        }
    }

    private fun formatDrawItems(items: List<DrawItem>): String {
        if (items.isEmpty()) return "none"
        return items.joinToString(separator = "|") { item ->
            val bottom = item.top + item.pageHeight
            val state = when {
                itemHasDrawable(item) -> "draw"
                item.loading -> "load"
                else -> "empty"
            }
            "${item.index}:${formatFloat(item.top)}-${formatFloat(bottom)}:$state"
        }
    }

    private fun formatFloat(value: Float): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun coverageStats(state: DrawState): CoverageStats {
        if (state.empty) return CoverageStats(0, state.height, 0, 0, 0)
        var drawablePx = 0
        var placeholderPx = 0
        var drawableItems = 0
        var coveredPx = 0
        for (item in state.items) {
            val top = floor(max(0f, item.top)).toInt().coerceIn(0, state.height)
            val bottom = ceil(min(state.height.toFloat(), item.top + item.pageHeight)).toInt().coerceIn(top, state.height)
            if (bottom <= top) continue
            val px = bottom - top
            coveredPx += px
            if (itemHasDrawable(item)) {
                drawablePx += px
                drawableItems++
            } else if (item.loading) {
                placeholderPx += px
            }
        }
        val rawMissingPx = max(0, state.height - coveredPx)
        val missingPx = if (rawMissingPx <= COVERAGE_EDGE_FILL_PX && placeholderPx == 0 && drawablePx > 0) {
            0
        } else {
            rawMissingPx
        }
        return CoverageStats(
            drawablePx = if (missingPx == 0) max(drawablePx, state.height - placeholderPx) else drawablePx,
            missingPx = missingPx,
            placeholderPx = placeholderPx,
            drawableItems = drawableItems,
            totalItems = state.items.size
        )
    }

    private fun itemHasDrawable(item: DrawItem): Boolean {
        if (item.cardText != null) return true
        if (item.errorText != null) return true
        val bitmap = item.bitmap
        if (bitmap != null && !bitmap.isRecycled) return true
        return item.tiles.any { !it.bitmap.isRecycled }
    }

    private fun shouldHoldInitialAnchorRenderLocked(): Boolean {
        val page = initialRenderHoldPage
        if (page !in 0 until pages.size) return false
        val now = SystemClock.uptimeMillis()
        if (now > initialRenderHoldUntilMs) {
            clearInitialRenderHoldLocked()
            return false
        }
        if (!pageHasDrawableContentLocked(page)) return true
        return false
    }

    private fun shouldHoldInitialViewportRenderLocked(): Boolean {
        return SystemClock.uptimeMillis() <= max(initialRenderHoldUntilMs, initialViewportHoldUntilMs)
    }

    private fun clearInitialRenderHoldLocked() {
        initialRenderHoldPage = -1
        initialRenderHoldUntilMs = 0L
        initialViewportHoldUntilMs = 0L
    }

    private fun shouldSuppressInitialEmptyRenderLocked(): Boolean {
        val page = initialRenderHoldPage
        return page in 0 until pages.size &&
            SystemClock.uptimeMillis() <= initialRenderHoldUntilMs &&
            !pageHasDrawableContentLocked(page)
    }

    private fun shouldDeferInitialEmptyDrawLocked(): Boolean {
        if (!deferInitialEmptyDraw || pages.isEmpty()) return false
        if (hasAnyDrawableContentLocked()) {
            deferInitialEmptyDraw = false
            return false
        }
        return true
    }

    private fun shouldBlockInitialEmptyFrameLocked(): Boolean {
        return shouldSuppressInitialEmptyRenderLocked() || shouldDeferInitialEmptyDrawLocked()
    }

    private fun hasAnyDrawableContentLocked(): Boolean {
        for (index in pages.indices) {
            if (pageHasDrawableContentLocked(index)) return true
        }
        return false
    }

    private fun pageHasDrawableContentLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        return page.cardText != null || page.errorText != null || page.bitmap != null || page.tiles.isNotEmpty()
    }

    fun requestRender() {
        synchronized(stateLock) {
            if (pages.isEmpty()) return
            if (shouldBlockInitialEmptyFrameLocked()) {
                renderRequested = false
                stateLock.notifyAll()
                return
            }
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
        if (!busy) applyPendingPageResolvesLocked()
        renderRequested = true
        return windowRequestLocked(busy)
    }

    private fun scheduleFrameLocked(preferImmediate: Boolean = false) {
        if (!renderRunning) return
        if (shouldBlockInitialEmptyFrameLocked()) {
            renderRequested = false
            return
        }
        if (frameScheduled) {
            statsCoalescedRequests++
            return
        }
        frameToken++
        frameScheduled = true
        val firstDrawableFrame = !hasDrawnContentFrame && hasAnyDrawableContentLocked()
        if (preferImmediate || firstDrawableFrame) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                invalidate()
            } else {
                postInvalidate()
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun stopRenderThreadLocked(): Thread? {
        frameScheduled = false
        frameToken++
        return null
    }

    private fun windowRequestLocked(busy: Boolean): WindowRequest? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        val anchor = anchorPageLocked()
        val first = max(0, anchor - ReaderPipelinePolicy.windowBefore(busy))
        val last = min(pages.lastIndex, anchor + ReaderPipelinePolicy.windowAfter(busy))
        val boundaryPx = height * NEAR_BOUNDARY_SCREENFULS
        val nearStart = scrollOffset <= boundaryPx ||
            anchor <= NEAR_BOUNDARY_PAGE_THRESHOLD
        val remainingPx = contentHeight - (scrollOffset + height)
        val nearEnd = remainingPx <= boundaryPx ||
            anchor >= pages.size - NEAR_BOUNDARY_PAGE_THRESHOLD
        val progress = progressPositionLocked() ?: return null
        val nearChanged = nearStart != lastNearStart || nearEnd != lastNearEnd
        if (busy && lastRequestedBusy) {
            val now = SystemClock.uptimeMillis()
            val anchorMoved = lastAnchor < 0 || abs(anchor - lastAnchor) >= BUSY_WINDOW_ANCHOR_STEP
            val intervalElapsed = now - lastBusyWindowDispatchMs >= BUSY_WINDOW_MIN_DISPATCH_MS
            if (!anchorMoved && !intervalElapsed && !nearChanged) return null
        }
        if (anchor == lastAnchor && busy == lastRequestedBusy && !nearChanged) return null
        lastAnchor = anchor
        lastNearStart = nearStart
        lastNearEnd = nearEnd
        lastRequestedBusy = busy
        if (busy) lastBusyWindowDispatchMs = SystemClock.uptimeMillis()
        return WindowRequest(first, last, anchor, progress.page, progress.offset, busy, nearStart, nearEnd)
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
            currentListener.onWindowChanged(
                latest.firstPage,
                latest.lastPage,
                latest.anchorPage,
                latest.progressPage,
                latest.progressOffset,
                latest.busy
            )
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

    private fun pageOffsetLocked(index: Int): Int {
        if (index < 0 || index >= pages.size) return 0
        rebuildLayoutLocked()
        return (pageTopOrElseLocked(index, 0f) - scrollOffset).toInt()
    }

    private fun applyLockedRestorePositionLocked() {
        val target = lockedRestorePage
        if (target !in 0 until pages.size) return
        if (SystemClock.uptimeMillis() > lockedRestoreUntilMs) {
            clearLockedRestorePositionLocked()
            return
        }
        rebuildLayoutLocked()
        val desiredScroll = pageTopOrElseLocked(target, 0f) - lockedRestoreOffset
        val maxScroll = max(0f, contentHeight - height)
        val restoredScroll = desiredScroll.coerceIn(0f, maxScroll)
        setScrollOffsetLocked(restoredScroll)
        if (
            hasDrawnContentFrame &&
            pageHasDrawableContentLocked(target) &&
            abs(restoredScroll - desiredScroll) <= RESTORE_POSITION_EPSILON_PX
        ) {
            clearLockedRestorePositionLocked()
        }
    }

    private fun clearLockedRestorePositionLocked() {
        lockedRestorePage = -1
        lockedRestoreOffset = 0
        lockedRestoreUntilMs = 0L
    }

    private fun progressPositionLocked(): ProgressPosition? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        rebuildLayoutLocked()
        var page = firstVisiblePageLocked(scrollOffset + height * PROGRESS_PAGE_PROBE_SCREEN_RATIO)
            .coerceIn(0, pages.lastIndex)
        val contentBottom = pageTopLocked(page) + pageDrawHeightLocked(pages[page])
        if (scrollOffset >= contentBottom && page < pages.lastIndex) {
            page += 1
        }
        return ProgressPosition(page, pageOffsetLocked(page))
    }

    private fun restoreViewportAnchorLocked(
        anchor: ProgressPosition?,
        reason: String,
        index: Int = -1,
        oldHeight: Float = 0f,
        newHeight: Float = 0f
    ) {
        val target = anchor?.page ?: return
        if (target !in pages.indices) return
        val now = SystemClock.uptimeMillis()
        val recentScrollSettling = lastScrollInteractionMs > 0L &&
            now - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
        if (!shouldRestoreAnchorAfterPendingResolves(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling
            )
        ) {
            return
        }
        rebuildLayoutLocked()
        val before = scrollOffset
        setScrollOffsetLocked(pageTopOrElseLocked(target, 0f) - anchor.offset)
        clampScrollLocked()
        if (abs(scrollOffset - before) > HEIGHT_CHANGE_EPSILON_PX) {
            Log.d(
                TAG,
                "reader_viewport_anchor_restore reason=$reason index=$index anchor=$target " +
                    "anchorOffset=${anchor.offset} old=${oldHeight.toInt()} new=${newHeight.toInt()} " +
                    "from=${before.toInt()} to=${scrollOffset.toInt()} lastBusy=$lastBusy"
            )
        }
    }

    private fun clampScrollLocked() {
        val maxScroll = max(0f, totalHeightLocked() - height)
        val minScroll = if (prependedRevealHoldPage in pages.indices) {
            min(pageTopOrElseLocked(prependedRevealHoldPage, 0f), maxScroll)
        } else {
            0f
        }
        setScrollOffsetLocked(scrollOffset.coerceIn(minScroll, maxScroll))
    }

    private fun boundaryRequestLocked(clearDirection: Boolean = true): BoundaryRequest? {
        if (boundaryDispatchInFlight) return null
        val direction = boundaryArmedDirection
        if (clearDirection) boundaryArmedDirection = 0
        if (direction == 0 || pages.isEmpty() || width <= 0 || height <= 0) return null
        val maxScroll = max(0f, totalHeightLocked() - height)
        val atStart = scrollOffset <= BOUNDARY_EPSILON_PX
        val atEnd = scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX
        val request = when {
            direction == DIRECTION_PREVIOUS && atStart -> BoundaryRequest(direction, anchorPageLocked())
            direction == DIRECTION_NEXT && atEnd -> BoundaryRequest(direction, anchorPageLocked())
            else -> null
        }
        if (request != null) boundaryDispatchInFlight = true
        return request
    }

    private fun totalHeightLocked(): Float {
        rebuildLayoutLocked()
        return contentHeight
    }

    private fun pageDrawHeightLocked(page: Page): Float {
        val viewWidth = max(1, width)
        if (page.cardText != null) return TRANSITION_CARD_PAGE_HEIGHT_PX
        if (page.errorText != null) return TRANSITION_CARD_PAGE_HEIGHT_PX
        if (page.width > 0 && page.height > 0) return max(1f, viewWidth * (page.height / page.width.toFloat()))
        return max(1f, viewWidth * page.placeholderRatio)
    }

    private fun newPageLocked(placeholderRatio: Float = placeholderPageHeightRatio): Page {
        return Page(
            placeholderRatio = placeholderRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        )
    }

    private fun representativeResolvedPageRatioLocked(): Float {
        if (pages.isEmpty()) {
            return placeholderPageHeightRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        }
        val ratios = ArrayList<Float>(pages.size)
        for (page in pages) {
            if (page.cardText != null || page.errorText != null) continue
            if (page.width <= 0 || page.height <= 0) continue
            ratios.add(
                (page.height / page.width.toFloat()).coerceIn(
                    MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                    MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
                )
            )
        }
        if (ratios.isEmpty()) {
            return placeholderPageHeightRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        }
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    private fun resolvedPageDrawHeightLocked(pageWidth: Int, pageHeight: Int): Float {
        val viewWidth = max(1, width)
        if (pageWidth > 0 && pageHeight > 0) {
            return max(1f, viewWidth * (pageHeight / pageWidth.toFloat()))
        }
        return max(1f, viewWidth * placeholderPageHeightRatio)
    }

    private fun shouldDeferHeightChangingResolveLocked(
        oldTop: Float,
        oldHeight: Float,
        newHeight: Float,
        hasCurrentDrawable: Boolean
    ): Boolean {
        if (!hasDrawnContentFrame) return false
        if (!isScrollMovingLocked()) return false
        if (oldHeight <= 0f || abs(newHeight - oldHeight) <= HEIGHT_CHANGE_EPSILON_PX) return false
        if (!hasCurrentDrawable) return false
        val viewBottom = scrollOffset + max(1, height)
        if (oldTop >= viewBottom + COVERAGE_EDGE_FILL_PX) return false
        return true
    }

    private fun isScrollMovingLocked(): Boolean {
        return lastBusy || pointerDown || dragging || !scroller.isFinished || isRecentScrollSettlingLocked()
    }

    private fun clearPendingResolveLocked(page: Page) {
        page.pendingResolveType = PENDING_NONE
        page.pendingBitmap = null
        page.pendingTiles = emptyList()
        page.pendingWidth = 0
        page.pendingHeight = 0
    }

    private fun hasPendingPageResolvesLocked(): Boolean {
        for (page in pages) {
            if (page.pendingResolveType != PENDING_NONE) return true
        }
        return false
    }

    private fun isRecentScrollSettlingLocked(): Boolean {
        return lastScrollInteractionMs > 0L &&
            SystemClock.uptimeMillis() - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
    }

    private fun schedulePendingResolveRetryLocked() {
        if (pendingResolveRetryPosted) return
        pendingResolveRetryPosted = true
        mainHandler.postDelayed({
            synchronized(stateLock) {
                pendingResolveRetryPosted = false
                if (!hasPendingPageResolvesLocked()) return@synchronized
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }, HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS + 32L)
    }

    private fun applyPendingPageResolvesLocked() {
        if (pages.isEmpty()) return
        rebuildLayoutLocked()
        val viewportAnchor = progressPositionLocked() ?: return
        val recentScrollSettling = isRecentScrollSettlingLocked()
        if (!shouldRestoreAnchorAfterPendingResolves(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling
            )
        ) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked()
            return
        }
        var appliedCount = 0
        for (index in pages.indices) {
            if (!shouldApplyPendingPageResolveLocked(index)) continue
            if (applyPendingPageResolveLocked(index)) appliedCount++
        }
        if (appliedCount > 0) {
            structuralScrollAdjustUntilMs = max(
                structuralScrollAdjustUntilMs,
                SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
            )
            val beforeRestore = scrollOffset
            restoreViewportAnchorLocked(viewportAnchor, "pending_resolve")
            Log.d(
                TAG,
                "reader_pending_resolve_restore applied=$appliedCount anchor=${viewportAnchor.page} " +
                    "anchorOffset=${viewportAnchor.offset} from=${beforeRestore.toInt()} to=${scrollOffset.toInt()} " +
                    "lastBusy=$lastBusy pointerDown=$pointerDown dragging=$dragging " +
                    "scrollerFinished=${scroller.isFinished} recentSettling=$recentScrollSettling"
            )
        }
    }

    private fun shouldApplyPendingPageResolveLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        if (page.pendingResolveType == PENDING_NONE) return false
        val pageTop = pageTopOrElseLocked(index, 0f)
        val pageBottom = pageTop + pageDrawHeightLocked(page)
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + max(1, height)
        val intersectsViewport = pageBottom > viewportTop + COVERAGE_EDGE_FILL_PX &&
            pageTop < viewportBottom - COVERAGE_EDGE_FILL_PX
        if (intersectsViewport) {
            Log.d(
                TAG,
                "reader_pending_resolve_visible_deferred index=$index top=${pageTop.toInt()} " +
                    "bottom=${pageBottom.toInt()} scroll=${scrollOffset.toInt()} height=$height"
            )
            return false
        }
        return true
    }

    private fun applyPendingPageResolveLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        val type = page.pendingResolveType
        if (type == PENDING_NONE) return false
        val oldHeight = pageDrawHeightLocked(page)
        val pendingWidth = page.pendingWidth
        val pendingHeight = page.pendingHeight
        when (type) {
            PENDING_SIZE -> {
                if (page.bitmap == null && page.tiles.isEmpty()) {
                    clearPendingResolveLocked(page)
                    return false
                }
            }
            PENDING_BITMAP -> {
                val bitmap = page.pendingBitmap ?: return false
                page.bitmap = bitmap
                page.tiles = emptyList()
            }
            PENDING_TILES -> {
                page.bitmap = null
                page.tiles = page.pendingTiles
            }
            PENDING_BOUNDS -> {
                if (page.bitmap != null || page.tiles.isNotEmpty() || page.cardText != null || page.errorText != null) {
                    clearPendingResolveLocked(page)
                    return false
                }
            }
        }
        page.width = max(1, pendingWidth)
        page.height = max(1, pendingHeight)
        page.loading = false
        page.cardText = null
        page.errorText = null
        clearPendingResolveLocked(page)
        noteResolvedPageAspectLocked(page.width, page.height)
        val newHeight = pageDrawHeightLocked(page)
        updatePageHeightDeltaLocked(index, newHeight - oldHeight)
        return true
    }

    private fun noteResolvedPageAspectLocked(pageWidth: Int, pageHeight: Int) {
        if (pageWidth <= 0 || pageHeight <= 0) return
        val ratio = (pageHeight / pageWidth.toFloat()).coerceIn(
            MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
            MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
        )
        placeholderPageHeightRatio =
            placeholderPageHeightRatio * (1f - PLACEHOLDER_RATIO_LEARNING_RATE) +
                ratio * PLACEHOLDER_RATIO_LEARNING_RATE
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
        val appendedPlaceholderRatio = representativeResolvedPageRatioLocked()
        var top = if (pages.isEmpty()) 0f else contentHeight + pageGapPx
        repeat(additionalCount) {
            val page = newPageLocked(appendedPlaceholderRatio)
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
        /*
         * The old incremental range-delta path is cheap, but it is fragile when several
         * NTK pages resolve size while the placeholder aspect ratio is still learning.
         * A stale delta can make later pages overlap the current page, which looks like
         * the top of the image is pinned while only the lower part scrolls.
         */
        structuralScrollAdjustUntilMs = max(
            structuralScrollAdjustUntilMs,
            SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
        )
        layoutDirty = true
    }

    private fun applyPageHeightChangeLocked(index: Int, oldTop: Float, oldHeight: Float, delta: Float) {
        if (abs(delta) <= 0.01f) return
        val oldBottom = oldTop + oldHeight
        val recentScrollSettling = isRecentScrollSettlingLocked()
        if (shouldAdjustScrollForChangedPageHeight(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling,
                oldBottom = oldBottom,
                scrollOffset = scrollOffset
            )
        ) {
            setScrollOffsetLocked(scrollOffset + delta)
        }
        updatePageHeightDeltaLocked(index, delta)
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

    private fun setScrollOffsetLocked(next: Float) {
        if (height > 0) {
            val delta = next - scrollOffset
            val now = SystemClock.uptimeMillis()
            val lockedRestoreActive = lockedRestorePage >= 0 &&
                now <= lockedRestoreUntilMs
            val structuralAdjustActive = now <= structuralScrollAdjustUntilMs
            val recentUserScrollActive = lastScrollInteractionMs > 0L &&
                now - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
            if (
                abs(delta) >= height * SCROLL_JUMP_LOG_SCREEN_RATIO &&
                !pointerDown &&
                !dragging &&
                scroller.isFinished &&
                !lockedRestoreActive &&
                !structuralAdjustActive &&
                !recentUserScrollActive
            ) {
                Log.w(
                    TAG,
                    "reader_scroll_jump delta=${delta.toInt()} from=${scrollOffset.toInt()} to=${next.toInt()} " +
                        "anchor=${if (pages.isEmpty()) -1 else anchorPageLocked()} busy=$lastBusy lockedRestore=$lockedRestorePage"
                )
            }
        }
        scrollOffset = next
    }

    private fun directionForDelta(delta: Float): Int {
        return when {
            delta > 0f -> DIRECTION_NEXT
            delta < 0f -> DIRECTION_PREVIOUS
            else -> 0
        }
    }

    private fun shouldDispatchCancelledBoundaryLocked(dragDistance: Float): Boolean {
        val direction = boundaryArmedDirection
        if (direction == 0 || pages.isEmpty() || width <= 0 || height <= 0) return false
        val minDistance = max(
            touchSlop * BOUNDARY_CANCEL_MIN_DRAG_TOUCH_SLOP_MULTIPLIER,
            height * BOUNDARY_CANCEL_MIN_DRAG_SCREEN_RATIO
        )
        if (dragDistance < minDistance) return false
        val maxScroll = max(0f, totalHeightLocked() - height)
        return when (direction) {
            DIRECTION_PREVIOUS -> scrollOffset <= BOUNDARY_EPSILON_PX
            DIRECTION_NEXT -> scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX
            else -> false
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
        clearLockedRestorePositionLocked()
        if (!dragging) dragging = true
        setBusyLocked(true)
        val direction = directionForDelta(dy)
        if (direction != 0) {
            boundaryArmedDirection = direction
            lastScrollInteractionMs = SystemClock.uptimeMillis()
        }
        setScrollOffsetLocked(scrollOffset + dy * DRAG_SCROLL_MULTIPLIER)
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
        for (interval in frameSamples) {
            if (interval > missedThreshold) {
                missedFrames += max(1, kotlin.math.floor(interval / measuredBudget - 1f).toInt())
            }
        }
        val droppedFrames = total.count { it > nominalBudget }
        var droppedFrameDebt = 0
        for (duration in total) droppedFrameDebt += max(0, kotlin.math.floor(duration / nominalBudget).toInt())
        val strictPercent = if (frameSamples.isEmpty()) 0f else strictOverBudget * 100f / frameSamples.size
        val missedPercent = if (frameSamples.isEmpty()) 0f else missedIntervals * 100f / frameSamples.size
        val droppedPercent = if (total.isEmpty()) 0f else droppedFrames * 100f / total.size
        val callbackP95 = percentile(callbackIntervals, 0.95f)
        val callbackMax = maxOrZero(callbackIntervals)
        val drawP95 = percentile(draw, 0.95f)
        val totalP95 = percentile(total, 0.95f)
        val totalMax = maxOrZero(total)
        synchronized(stateLock) {
            lastFrameStatsSnapshot = FrameStatsSnapshot(
                samples = frameSamples.size,
                strictOverBudget = strictOverBudget,
                missedIntervals = missedIntervals,
                missedFrames = missedFrames,
                droppedFrames = droppedFrames,
                droppedFrameDebt = droppedFrameDebt,
                callbackP95 = callbackP95,
                callbackMax = callbackMax,
                drawP95 = drawP95,
                totalP95 = totalP95,
                totalMax = totalMax,
                noCanvas = noCanvas,
                coalesced = coalesced
            )
        }
        Log.i(
            TAG,
            "surface_jank_v3 samples=${frameSamples.size} nominalBudget=${fmt(nominalBudget)} measuredBudget=${fmt(measuredBudget)} " +
                "strictOverBudget=$strictOverBudget strictPct=${fmt(strictPercent)} " +
                "missedIntervals=$missedIntervals missedFrames=$missedFrames missedPct=${fmt(missedPercent)} " +
                "droppedFrames=$droppedFrames droppedFrameDebt=$droppedFrameDebt droppedPct=${fmt(droppedPercent)} " +
                "callbackP95=${fmt(callbackP95)} callbackMax=${fmt(callbackMax)} " +
                "postP95=${fmt(percentile(postIntervals, 0.95f))} postMax=${fmt(maxOrZero(postIntervals))} " +
                "lockP95=${fmt(percentile(lockWait, 0.95f))} drawP95=${fmt(drawP95)} " +
                "unlockP95=${fmt(percentile(post, 0.95f))} totalP95=${fmt(totalP95)} totalMax=${fmt(totalMax)} " +
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

    private fun resetActiveFrameStatsLocked() {
        statsActive = false
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        clearStatsSamples()
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

        @JvmStatic
        fun transitionCardPageHeightForTest(): Float = TRANSITION_CARD_PAGE_HEIGHT_PX

        @JvmStatic
        fun shouldStartFlingForTest(dragDistance: Float, velocityY: Int, minVelocity: Int, touchSlop: Int): Boolean {
            return shouldStartFling(dragDistance, velocityY, minVelocity, touchSlop)
        }

        private fun shouldStartFling(dragDistance: Float, velocityY: Int, minVelocity: Int, touchSlop: Int): Boolean {
            return dragDistance > touchSlop * FLING_MIN_DRAG_TOUCH_SLOP_MULTIPLIER &&
                abs(velocityY) > minVelocity * FLING_MIN_VELOCITY_MULTIPLIER
        }

        private const val TAG = "ReaderSurfaceStats"
        private const val DEFAULT_FRAME_BUDGET_MS = 16.67f
        private const val MISSED_VSYNC_FACTOR = 2.0f
        private const val MIN_FRAME_SAMPLES = 8
        private const val DEFAULT_PAGE_GAP_PX = 0
        private const val TILE_SEAM_OVERLAP_PX = 1f
        private const val TRANSITION_CARD_WIDTH_RATIO = 0.82f
        private const val TRANSITION_CARD_PAGE_HEIGHT_PX = 168f
        private const val TRANSITION_CARD_BODY_HEIGHT_PX = 144f
        private const val DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO = 1.45f
        private const val MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO = 0.85f
        private const val MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO = 3.8f
        private const val PLACEHOLDER_RATIO_LEARNING_RATE = 0.4f
        private const val NEAR_BOUNDARY_SCREENFULS = 10
        private const val NEAR_BOUNDARY_PAGE_THRESHOLD = 16
        private const val BUSY_WINDOW_ANCHOR_STEP = 2
        private const val BUSY_WINDOW_MIN_DISPATCH_MS = 48L
        private const val BUSY_COVERAGE_LOG_INTERVAL_MS = 250L
        private const val SLOW_FRAME_LOG_INTERVAL_MS = 500L
        private const val COVERAGE_EDGE_FILL_PX = 8
        private const val COVERAGE_EDGE_PLACEHOLDER_FILL_PX = 96
        private const val SCROLLBAR_TOUCH_WIDTH_PX = 96f
        private const val SCROLLBAR_RIGHT_MARGIN_PX = 10f
        private const val SCROLLBAR_TRACK_WIDTH_PX = 48f
        private const val SCROLLBAR_TRACK_RADIUS_PX = 24f
        private const val SCROLLBAR_THUMB_WIDTH_PX = 40f
        private const val SCROLLBAR_THUMB_RADIUS_PX = 20f
        private const val SCROLLBAR_MIN_THUMB_HEIGHT_PX = 188f
        private const val SCROLLBAR_GRIP_INSET_PX = 10f
        private const val SCROLLBAR_GRIP_HEIGHT_PX = 4f
        private const val SCROLLBAR_GRIP_GAP_PX = 13f
        private const val SCROLLBAR_GRIP_RADIUS_PX = 2f
        private const val BOUNDARY_EPSILON_PX = 2f
        private const val BOUNDARY_FLING_EXTEND_EPSILON_PX = 4
        private const val BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER = 2f
        private const val BOUNDARY_CANCEL_MIN_DRAG_SCREEN_RATIO = 0.08f
        private const val BOUNDARY_CANCEL_MIN_DRAG_TOUCH_SLOP_MULTIPLIER = 4f
        private const val PROGRESS_PAGE_PROBE_SCREEN_RATIO = 0.35f
        private const val DRAG_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_MIN_DRAG_TOUCH_SLOP_MULTIPLIER = 1.0f
        private const val FLING_MIN_VELOCITY_MULTIPLIER = 1
        private const val RESTORE_POSITION_LOCK_MS = 4000L
        private const val RESTORE_POSITION_EPSILON_PX = 2f
        private const val INITIAL_RENDER_HOLD_MS = 700L
        private const val SCROLL_JUMP_LOG_SCREEN_RATIO = 0.75f
        private const val HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS = 6500L
        private const val HEIGHT_CHANGE_EPSILON_PX = 0.01f
        private const val BUSY_RESOLVE_RENDER_EXTRA_PAGES = 2
        private const val MOVE_VELOCITY_SAMPLE_MS = 16L
        private const val RENDER_THREAD_STOP_JOIN_MS = 500L
        private const val PENDING_NONE = 0
        private const val PENDING_BITMAP = 1
        private const val PENDING_TILES = 2
        private const val PENDING_BOUNDS = 3
        private const val PENDING_SIZE = 4
        private const val PAGE_PLACEHOLDER_COLOR = -0x1
        private const val SCROLLBAR_TRACK_COLOR = 0x1A000000
        private const val SCROLLBAR_THUMB_COLOR = -0xf0f10
        private const val SCROLLBAR_THUMB_ACTIVE_COLOR = -0x1
        private const val SCROLLBAR_GRIP_COLOR = 0x4D000000

        private fun shouldAdjustScrollForChangedPageHeight(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean,
            oldBottom: Float,
            scrollOffset: Float
        ): Boolean {
            if (oldBottom > scrollOffset) return false
            if (lastBusy || recentScrollSettling) return false
            return !pointerDown && !dragging && scrollerFinished
        }

        private fun shouldRestoreAnchorAfterPendingResolves(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean
        ): Boolean {
            if (lastBusy || recentScrollSettling) return false
            return !pointerDown && !dragging && scrollerFinished
        }

        @JvmStatic
        fun shouldAdjustScrollForChangedPageHeightForTest(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean,
            oldBottom: Float,
            scrollOffset: Float
        ): Boolean {
            return shouldAdjustScrollForChangedPageHeight(
                lastBusy,
                pointerDown,
                dragging,
                scrollerFinished,
                recentScrollSettling,
                oldBottom,
                scrollOffset
            )
        }

        @JvmStatic
        fun shouldRestoreAnchorAfterPendingResolvesForTest(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean
        ): Boolean {
            return shouldRestoreAnchorAfterPendingResolves(
                lastBusy,
                pointerDown,
                dragging,
                scrollerFinished,
                recentScrollSettling
            )
        }
    }
}
