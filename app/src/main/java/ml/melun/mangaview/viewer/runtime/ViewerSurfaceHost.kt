package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Trace
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

internal interface ViewerSurfaceSink {
    fun viewportChanged(viewport: Viewport)
    fun surfaceAvailable(
        surface: Surface,
        width: Int,
        height: Int,
        refreshRate: Float,
        reportAttached: (Boolean) -> Unit,
    )
    fun surfaceUnavailable()
    fun surfaceAttachExhausted() {}
    fun userScroll(
        delta: FixedPx,
        velocityPixelsPerSecond: Float,
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ): Boolean
    fun interactionChanged(active: Boolean, atNanos: Long)
    fun motionFrame(sequence: Long, atNanos: Long)
}

internal class ViewerSurfaceHost(
    context: Context,
    private val sink: ViewerSurfaceSink,
) : SurfaceView(context) {
    private val attachment = ViewerSurfaceAttachment(this, sink)
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val pointerDeltas = PointerDeltaLedger()
    private val dragQuantizer = PointerDeltaQuantizer()
    private val inputTrace = ViewerInputTraceLedger()
    private val dragFrame = ViewerVsyncScheduler(android.view.Choreographer.getInstance(), ::drawDrag)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scrollEmitter = ViewerScrollEmitter(sink, inputTrace)
    // The fling's motion steps are paced by a deadline on ViewerAnimationLooper, not by a display
    // slot callback: the platform can withhold a whole vsync event from an idle client, and a fling
    // that waits for that event never produces the step for that display period. Only the engine
    // step the deadline reveals is handed back to the main thread, in order.
    private val refreshPeriodNanos = (1_000_000_000.0 / (context.display?.refreshRate ?: 60f)).toLong()
    private val flingPump = ViewerFlingStepPump(mainHandler, scrollEmitter::emitFling, ::finishInteraction)
    private val fling = ViewerFlingDriver(
        ViewerFrameSchedulerFactory { callback -> ViewerAnimationScheduler(callback) },
        refreshPeriodNanos,
        flingPump::dispatch,
        sink::motionFrame,
        flingPump::finish,
        ViewerAnimationLooper::dispatch,
    )
    private val zoom = ViewerZoomState()
    private val pinch = ViewerPinchGesture(zoom, ::applyZoom, ::emitSyntheticScroll)
    private var velocityTracker: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var previousFrameNanos = 0L
    private var lastMotionNanos = 0L
    private var lastPointerX = 0f
    private var dispatchEntryNanos = 0L
    private var latestVelocity = 0.0
    private var dragScheduled = false
    private var interaction = false
    private var gestureMoved = false
    private var ending = false
    private var flingVelocity: Double? = null
    private var flingReleaseNanos = 0L
    private var motionSequence = 0L
    private var nextMotionSequence = 1L
    private val traceGesture = ViewerInputTraceGesture()
    init {
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(attachment)
        setWillNotDraw(true)
        isFocusable = true
        isClickable = true
        contentDescription = "viewer-surface"
        // Scale around the top-left corner so a local pixel is a document pixel plus translation.
        pivotX = 0f
        pivotY = 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        dispatchEntryNanos = System.nanoTime()
        val tracing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()
        if (tracing) {
            // Preserve the event's millisecond precision. The trace interval separately records
            // delivery/handling; injection-to-motion delay must not all be attributed to the app.
            Trace.beginSection("viewer_input:${event.actionMasked}:${event.eventTime}:${System.nanoTime()}")
        }
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event, tracing)
                MotionEvent.ACTION_MOVE -> move(event)
                MotionEvent.ACTION_POINTER_DOWN -> beginPinch(event)
                MotionEvent.ACTION_POINTER_UP -> changePointer(event)
                MotionEvent.ACTION_UP -> end(event, flingAfter = true)
                MotionEvent.ACTION_CANCEL -> end(event, flingAfter = false)
            }
            return true
        } finally {
            if (tracing) Trace.endSection()
        }
    }

    override fun performClick(): Boolean = super.performClick()

    fun enterForeground() {
        setReaderFrameRate(true)
        attachment.enterForeground()
    }

    fun enterBackground() {
        setReaderFrameRate(false)
        cancelMotion()
        attachment.enterBackground()
    }

    fun rendererUnavailable() = attachment.rendererUnavailable()

    fun cancelMotion() {
        flushDrag()
        fling.stop()
        pinch.end()
        velocityTracker?.recycle()
        velocityTracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        previousFrameNanos = 0L
        latestVelocity = 0.0
        finishInteraction()
    }

    /**
     * One synthetic viewport step from a hardware key. Positive pixels advance toward the next
     * screen, matching the finger-up direction a real drag reports. Not an original touch sample.
     */
    fun stepViewport(pixels: Double): Boolean {
        if (pixels == 0.0) return false
        return emitSyntheticScroll(pixels)
    }

    /**
     * Double-tap magnification around a tap delivered in the parent's coordinates. The engine
     * receives one synthetic scroll that keeps the tapped document row on screen; the rest is a
     * compositor transform.
     */
    fun toggleZoom(parentX: Float, parentY: Float): Boolean {
        flushDrag()
        fling.stop()
        val focusX = (parentX - zoom.translationX) / zoom.scale
        val focusY = parentY / zoom.scale
        val scroll = zoom.toggle(focusX, focusY)
        applyZoom()
        if (scroll != 0.0) emitSyntheticScroll(scroll)
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        zoom.viewportChanged(width.toFloat())
        applyZoom()
        sink.viewportChanged(Viewport(FixedPx.fromPixels(width), FixedPx.fromPixels(height)))
        attachment.resized(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setReaderFrameRate(true)
        attachment.attachIfReady()
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        attachment.detachRenderer()
        super.onDetachedFromWindow()
    }

    private fun begin(event: MotionEvent, tracing: Boolean) {
        // Real drag deltas are drained on the dispatch pass itself (drainDragImmediately), so keep
        // touchscreen samples unbuffered instead of letting a second frame-batching wait delay them.
        if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) requestUnbufferedDispatch(event)
        flushDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        pointerId = event.getPointerId(0)
        lastPointerX = event.x
        pointerDeltas.begin(event.y)
        inputTrace.begin(event.y, tracing)
        traceGesture.beginTouch()
        val at = event.eventTime * NANOS_PER_MILLISECOND
        previousFrameNanos = at
        lastMotionNanos = at
        latestVelocity = 0.0
        gestureMoved = false
        pinch.end()
        if (event.pointerCount >= 2) beginPinch(event)
    }

    private fun beginPinch(event: MotionEvent) {
        flushDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        pinch.begin(event)
        beginInteraction()
    }

    private fun applyZoom() {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.translationX
    }

    private fun move(event: MotionEvent) {
        if (pinch.active) {
            pinch.update(event)
            return
        }
        if (event.pointerCount >= 2) {
            beginPinch(event)
            return
        }
        velocityTracker?.addMovement(event)
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        val pointerX = event.getX(index)
        if (zoom.zoomed && zoom.pan((pointerX - lastPointerX) * zoom.scale)) applyZoom()
        lastPointerX = pointerX
        val at = event.eventTime * NANOS_PER_MILLISECOND
        val delta = appendPointerSamples(event, index, pointerDeltas, inputTrace, traceGesture.id, pointerId)
        val elapsed = (at - lastMotionNanos).coerceAtLeast(1L)
        latestVelocity = delta * NANOS_PER_SECOND / elapsed
        lastMotionNanos = at
        beginInteraction()
        drainDragImmediately()
    }

    private fun changePointer(event: MotionEvent) {
        if (pinch.active) {
            if (event.pointerCount - 1 >= 2) {
                pinch.rebase(event, event.actionIndex)
                return
            }
            pinch.end()
            val remaining = (0 until event.pointerCount).first { it != event.actionIndex }
            pointerId = event.getPointerId(remaining)
            velocityTracker?.recycle()
            velocityTracker = null
            lastPointerX = event.getX(remaining)
            pointerDeltas.rebase(event.getY(remaining))
            dragQuantizer.rebase()
            inputTrace.rebase(event.getY(remaining))
            val at = event.eventTime * NANOS_PER_MILLISECOND
            previousFrameNanos = at
            lastMotionNanos = at
            latestVelocity = 0.0
            return
        }
        val lifted = event.actionIndex
        if (event.getPointerId(lifted) != pointerId) return
        val replacement = if (lifted == 0) 1 else 0
        if (replacement >= event.pointerCount) return
        pointerId = event.getPointerId(replacement)
        lastPointerX = event.getX(replacement)
        pointerDeltas.rebase(event.getY(replacement))
        dragQuantizer.rebase()
        inputTrace.rebase(event.getY(replacement))
    }

    private fun end(event: MotionEvent, flingAfter: Boolean) {
        if (pinch.active) {
            // A pinch never launches a fling and never counts as a tap.
            pinch.end()
            velocityTracker?.recycle()
            velocityTracker = null
            pointerId = MotionEvent.INVALID_POINTER_ID
            // beginPinch opened an interaction; without this close the sink keeps reporting
            // gesture-in-progress until the next gesture or a background transition.
            finishInteraction()
            return
        }
        val tracker = velocityTracker
        tracker?.addMovement(event)
        val index = event.findPointerIndex(pointerId)
        if (index >= 0) appendPointerSamples(event, index, pointerDeltas, inputTrace, traceGesture.id, pointerId)
        if (flingAfter && tracker != null) {
            tracker.computeCurrentVelocity(1_000, maximumFlingVelocity.toFloat())
            flingVelocity = (-tracker.getYVelocity(pointerId)).toDouble()
            flingReleaseNanos = event.eventTime * NANOS_PER_MILLISECOND
        }
        ending = true
        drainDragImmediately()
        tracker?.recycle()
        velocityTracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (!gestureMoved && flingAfter) performClick()
    }

    private fun scheduleDrag() {
        if (dragScheduled) return
        if (motionSequence == 0L) motionSequence = issueMotionSequence()
        dragScheduled = true
        dragFrame.post(0L)
    }

    private fun drawDrag(frameTime: Long, vsyncId: Long, expectedPresentation: Long) {
        dragScheduled = false
        drainPending(frameTime, vsyncId, expectedPresentation)
        if (ending) finishDrag(frameTime, vsyncId, expectedPresentation)
        if (pointerDeltas.hasPending || ending) scheduleDrag() else motionSequence = 0L
    }

    /**
     * Applies every queued real pointer delta on the dispatch pass itself instead of waiting for a
     * Choreographer drag frame. The trace origin is the actual MotionEvent dispatch entry time with
     * no frame-timeline vsync id ([NO_VSYNC_ID]); the legacy frame-callback drain keeps its own
     * Choreographer origin. Fling stays frame-driven: [finishDrag] still starts it from the actual
     * release time.
     */
    private fun drainDragImmediately() {
        if (dragScheduled) dragFrame.cancel()
        dragScheduled = false
        // Mirror the scheduled path: each drain carries a nonzero unique sequence, because the
        // presentation recorder drops every motion frame whose sequence is <= 0.
        if (motionSequence == 0L) motionSequence = issueMotionSequence()
        drainPending(dispatchEntryNanos, NO_VSYNC_ID, 0L)
        if (ending) finishDrag(dispatchEntryNanos, NO_VSYNC_ID, 0L)
        if (pointerDeltas.hasPending || ending) scheduleDrag() else motionSequence = 0L
    }

    private fun drainPending(frameTime: Long, vsyncId: Long, expectedPresentation: Long) {
        var moved = false
        val traceSegments = inputTrace.drain()
        var traceIndex = 0
        pointerDeltas.drain().forEach { delta ->
            val elapsed = (frameTime - previousFrameNanos).coerceAtLeast(1L)
            val velocity = latestVelocity.takeIf { it != 0.0 }
                ?: delta * NANOS_PER_SECOND / elapsed
            if (scrollEmitter.emitTouch(delta, dragQuantizer.apply(delta), velocity, frameTime, expectedPresentation,
                    vsyncId, traceSegments.getOrNull(traceIndex++))) {
                moved = true
            }
        }
        if (moved) sink.motionFrame(motionSequence, frameTime)
        previousFrameNanos = frameTime
    }

    private fun finishDrag(frameTime: Long, vsyncId: Long, expectedPresentation: Long) {
        ending = false
        val velocity = flingVelocity
        val releasedAt = flingReleaseNanos
        flingVelocity = null
        flingReleaseNanos = 0L
        val started = velocity != null && fling.startFromRelease(velocity, releasedAt, frameTime,
            issueMotionSequence(), vsyncId, expectedPresentation)
        if (!started) finishInteraction()
    }

    /** Input the app derives itself, such as a key step or zoom anchoring, is never a touch sample. */
    private fun emitSyntheticScroll(pixels: Double): Boolean {
        val moved = scrollEmitter.emitTouch(pixels, FixedPx.fromPixels(pixels), 0.0, System.nanoTime(), 0L, NO_VSYNC_ID,
            if (Trace.isEnabled()) inputTrace.synthetic(pixels) else null)
        if (moved) sink.motionFrame(issueMotionSequence(), System.nanoTime())
        return moved
    }

    private fun flushDrag() {
        if (dragScheduled) dragFrame.cancel()
        dragScheduled = false
        val traceSegments = inputTrace.drain()
        var traceIndex = 0
        pointerDeltas.drain().forEach { delta ->
            scrollEmitter.emitTouch(delta, dragQuantizer.apply(delta), 0.0, System.nanoTime(), 0L, -1L,
                traceSegments.getOrNull(traceIndex++))
        }
        ending = false
        flingVelocity = null
        flingReleaseNanos = 0L
        motionSequence = 0L
        dragQuantizer.begin()
    }

    private fun beginInteraction() {
        gestureMoved = true
        if (interaction) return
        interaction = true
        sink.interactionChanged(true, System.nanoTime())
    }

    /**
     * [atNanos] is when the interaction truly ended, which is not always when this runs: the fling
     * finish is handed to the main thread, and stamping the boundary there would charge the main
     * loop's dispatch latency to the gesture tail. Callers that know the real end pass it.
     */
    private fun finishInteraction(atNanos: Long = System.nanoTime()) {
        if (!interaction) return
        interaction = false
        sink.interactionChanged(false, atNanos)
    }

    private fun issueMotionSequence(): Long = nextMotionSequence.also {
        nextMotionSequence = if (it == Long.MAX_VALUE) 1L else it + 1L
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/** Frame-rate requests belong to the Android view, independent of renderer attachment. */
private fun SurfaceView.setReaderFrameRate(active: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        requestedFrameRate = if (active) android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            else android.view.View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
    }
}
