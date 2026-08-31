package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.INVALID_FRAME_TIMELINE_VSYNC_ID
import ml.melun.mangaview.viewer.ViewerEvent

internal class ViewerCanvasView(
    context: Context,
    private val renderPort: CanvasRenderPort,
    private val eventSink: (ViewerEvent) -> Unit,
    private val reportGestureBoundary: (Boolean, Long) -> Unit,
    private val reportMotionFrame: (sequence: Long, frameTimeNanos: Long) -> Unit,
) : View(context) {
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val choreographer = Choreographer.getInstance()
    private val fling = ViewerFlingDriver(
        choreographer,
        ::emitScroll,
        reportMotionFrame,
        ::finishGestureTrace,
    )
    private val dragFrame = ViewerVsyncScheduler(choreographer, ::dispatchDragFrame)
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val pointerDeltas = PointerDeltaLedger()
    private var previousDragFrameNanos = 0L
    private var lastMotionEventNanos = 0L
    private var latestDragVelocity = 0.0
    private var foreground = true
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var rendererAttached = false
    private var gestureMoveStarted = false
    private var dragFrameScheduled = false
    private var dragMotionSequence = 0L
    private var nextMotionSequence = 1L
    private var pendingGestureEnd = false
    private var pendingFlingVelocity: Double? = null
    private var pendingClick = false

    init {
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true
        contentDescription = "viewer-surface"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> moveGesture(event)
            MotionEvent.ACTION_POINTER_UP -> changePointer(event)
            MotionEvent.ACTION_UP -> endGesture(event, flingAfter = true)
            MotionEvent.ACTION_CANCEL -> endGesture(event, flingAfter = false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun cancelMotion() {
        flushPendingDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        previousDragFrameNanos = 0L
        lastMotionEventNanos = 0L
        latestDragVelocity = 0.0
        dragMotionSequence = 0L
        finishGestureTrace()
    }

    fun enterForeground() {
        foreground = true
        attachRendererIfReady()
    }

    fun enterBackground() {
        foreground = false
        cancelMotion()
        detachRenderer()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val configurationChanged = surfaceWidth != width || surfaceHeight != height
        if (configurationChanged) {
            cancelMotion()
            if (rendererAttached) detachRenderer()
        }
        surfaceWidth = width
        surfaceHeight = height
        eventSink(ViewerEvent.ViewportChanged(
            viewport = ml.melun.mangaview.viewer.Viewport(
                FixedPx.fromPixels(width),
                FixedPx.fromPixels(height),
            ),
            atNanos = System.nanoTime(),
        ))
        attachRendererIfReady()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachRendererIfReady()
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        detachRenderer()
        surfaceWidth = 0
        surfaceHeight = 0
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE || !foreground) return
        attachRendererIfReady()
        renderPort.redraw()
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !foreground) return
        attachRendererIfReady()
        renderPort.redraw()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val token = renderPort.draw(canvas, width, height) ?: return
        viewTreeObserver.registerFrameCommitCallback {
            renderPort.frameCommitted(token)
        }
    }

    private fun beginGesture(event: MotionEvent) {
        flushPendingDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        activePointerId = event.getPointerId(0)
        pointerDeltas.begin(event.y)
        val eventNanos = event.eventTime * NANOS_PER_MILLISECOND
        previousDragFrameNanos = eventNanos
        lastMotionEventNanos = eventNanos
        latestDragVelocity = 0.0
        gestureMoveStarted = false
    }

    private fun moveGesture(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val y = event.getY(index)
        val eventNanos = event.eventTime * NANOS_PER_MILLISECOND
        val delta = pointerDeltas.append(y)
        val eventElapsed = (eventNanos - lastMotionEventNanos).coerceAtLeast(1L)
        latestDragVelocity = delta * NANOS_PER_SECOND / eventElapsed
        lastMotionEventNanos = eventNanos
        beginGestureTrace()
        scheduleDragFrame()
    }

    private fun changePointer(event: MotionEvent) {
        val liftedIndex = event.actionIndex
        if (event.getPointerId(liftedIndex) != activePointerId) return
        val replacement = if (liftedIndex == 0) 1 else 0
        if (replacement >= event.pointerCount) return
        activePointerId = event.getPointerId(replacement)
        pointerDeltas.rebase(event.getY(replacement))
        lastMotionEventNanos = event.eventTime * NANOS_PER_MILLISECOND
    }

    private fun endGesture(event: MotionEvent, flingAfter: Boolean) {
        val tracker = velocityTracker
        tracker?.addMovement(event)
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex >= 0) appendFinalPointerDelta(event, pointerIndex)
        if (flingAfter && tracker != null) {
            tracker.computeCurrentVelocity(1_000, maximumFlingVelocity.toFloat())
            pendingFlingVelocity = (-tracker.getYVelocity(activePointerId)).toDouble()
        }
        pendingGestureEnd = true
        pendingClick = flingAfter
        scheduleDragFrame()
        tracker?.recycle()
        velocityTracker = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun appendFinalPointerDelta(event: MotionEvent, pointerIndex: Int) {
        val eventNanos = event.eventTime * NANOS_PER_MILLISECOND
        val delta = pointerDeltas.append(event.getY(pointerIndex))
        if (delta != 0.0) {
            val elapsed = (eventNanos - lastMotionEventNanos).coerceAtLeast(1L)
            latestDragVelocity = delta * NANOS_PER_SECOND / elapsed
            beginGestureTrace()
        }
        lastMotionEventNanos = eventNanos
    }

    private fun scheduleDragFrame() {
        if (dragFrameScheduled) return
        if (dragMotionSequence == 0L) dragMotionSequence = issueMotionSequence()
        dragFrameScheduled = true
        dragFrame.post()
    }

    private fun dispatchDragFrame(
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) {
        dragFrameScheduled = false
        val delta = dragStep(frameTimeNanos, pendingGestureEnd)
        pointerDeltas.consume(delta)
        if (delta != 0.0) {
            val elapsedNanos = (frameTimeNanos - previousDragFrameNanos).coerceAtLeast(1L)
            val measuredVelocity = delta * NANOS_PER_SECOND / elapsedNanos
            val velocity = latestDragVelocity.takeIf { it != 0.0 } ?: measuredVelocity
            if (emitScroll(
                    delta,
                    velocity,
                    frameTimeNanos,
                    expectedPresentationTimeNanos,
                    frameTimelineVsyncId,
                )) {
                reportMotionFrame(dragMotionSequence, frameTimeNanos)
            }
        }
        previousDragFrameNanos = frameTimeNanos
        if (pendingGestureEnd) {
            pendingGestureEnd = false
            val velocity = pendingFlingVelocity
            pendingFlingVelocity = null
            val click = pendingClick
            pendingClick = false
            val flingStarted = velocity != null &&
                fling.start(velocity, frameTimeNanos, issueMotionSequence())
            if (click) performClick()
            if (!flingStarted) finishGestureTrace()
        }
        if (pointerDeltas.pendingPixels != 0.0 || pendingGestureEnd) {
            scheduleDragFrame()
        } else {
            dragMotionSequence = 0L
        }
    }

    private fun dragStep(frameTimeNanos: Long, ending: Boolean): Double {
        val pending = pointerDeltas.pendingPixels
        if (pending == 0.0 || ending) return pending
        val elapsed = (frameTimeNanos - previousDragFrameNanos).coerceAtLeast(1L)
        val velocityBudget = abs(latestDragVelocity) * elapsed / NANOS_PER_SECOND
        val magnitude = min(abs(pending), velocityBudget.coerceAtLeast(MINIMUM_DRAG_STEP_PIXELS))
        return magnitude * pending.sign
    }

    private fun flushPendingDrag() {
        if (dragFrameScheduled) {
            dragFrame.cancel()
            dragFrameScheduled = false
        }
        dragMotionSequence = 0L
        val delta = pointerDeltas.drain()
        if (delta != 0.0) emitScroll(
            delta,
            0.0,
            System.nanoTime(),
            0L,
            INVALID_FRAME_TIMELINE_VSYNC_ID,
        )
        pendingFlingVelocity = null
        pendingClick = false
        val ending = pendingGestureEnd
        pendingGestureEnd = false
        if (ending) finishGestureTrace()
    }

    private fun finishGestureTrace() {
        if (gestureMoveStarted) {
            val atNanos = System.nanoTime()
            eventSink(ViewerEvent.InteractionChanged(false, atNanos))
            reportGestureBoundary(false, atNanos)
        }
        gestureMoveStarted = false
    }

    private fun beginGestureTrace() {
        if (gestureMoveStarted) return
        gestureMoveStarted = true
        val atNanos = System.nanoTime()
        eventSink(ViewerEvent.InteractionChanged(true, atNanos))
        reportGestureBoundary(true, atNanos)
    }

    private fun emitScroll(
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ): Boolean {
        if (deltaPixels == 0.0) return false
        val delta = FixedPx.fromPixels(deltaPixels)
        if (delta == FixedPx.ZERO) return false
        eventSink(ViewerEvent.UserScroll(
            delta = delta,
            velocityUnitsPerSecond = FixedPx.fromPixels(velocityPixelsPerSecond).units,
            atNanos = frameTimeNanos,
            frameTimelineVsyncId = frameTimelineVsyncId,
            expectedPresentationTimeNanos = expectedPresentationTimeNanos,
        ))
        return true
    }

    private fun issueMotionSequence(): Long {
        val issued = nextMotionSequence
        nextMotionSequence = if (issued == Long.MAX_VALUE) 1L else issued + 1L
        return issued
    }

    private fun attachRendererIfReady() {
        if (!foreground || rendererAttached || !isAttachedToWindow ||
            surfaceWidth <= 0 || surfaceHeight <= 0) return
        rendererAttached = true
        renderPort.attach()
        eventSink(ViewerEvent.SurfaceAttachmentChanged(true, System.nanoTime()))
    }

    private fun detachRenderer() {
        if (!rendererAttached) return
        rendererAttached = false
        eventSink(ViewerEvent.SurfaceAttachmentChanged(false, System.nanoTime()))
        renderPort.detach()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MINIMUM_DRAG_STEP_PIXELS = 0.25
    }
}
