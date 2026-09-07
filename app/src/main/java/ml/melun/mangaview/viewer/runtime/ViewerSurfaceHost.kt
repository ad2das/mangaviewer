package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Trace
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

internal interface ViewerSurfaceSink {
    fun viewportChanged(viewport: Viewport)
    fun surfaceAvailable(surface: Surface, width: Int, height: Int, refreshRate: Float)
    fun surfaceUnavailable()
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
) : SurfaceView(context), SurfaceHolder.Callback {
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val pointerDeltas = PointerDeltaLedger()
    private val dragFrame = ViewerVsyncScheduler(android.view.Choreographer.getInstance(), ::drawDrag)
    private val fling = ViewerFlingDriver(
        android.view.Choreographer.getInstance(),
        ::emitScroll,
        sink::motionFrame,
        ::finishInteraction,
    )
    private var velocityTracker: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var previousFrameNanos = 0L
    private var lastMotionNanos = 0L
    private var latestVelocity = 0.0
    private var dragScheduled = false
    private var interaction = false
    private var gestureMoved = false
    private var ending = false
    private var flingVelocity: Double? = null
    private var motionSequence = 0L
    private var nextMotionSequence = 1L
    private var foreground = true
    private var surfaceReady = false
    private var rendererAttached = false
    private var attachedWidth = 0
    private var attachedHeight = 0

    init {
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
        setWillNotDraw(true)
        isFocusable = true
        isClickable = true
        contentDescription = "viewer-surface"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()
        if (tracing) {
            // Preserve the event's millisecond precision. The trace interval separately records
            // delivery/handling; injection-to-motion delay must not all be attributed to the app.
            Trace.beginSection("viewer_input:${event.actionMasked}:${event.eventTime}:${System.nanoTime()}")
        }
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> move(event)
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
        foreground = true
        requestHighFrameRate()
        attachIfReady()
    }

    fun enterBackground() {
        foreground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
        cancelMotion()
        detachRenderer()
    }

    fun cancelMotion() {
        flushDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        previousFrameNanos = 0L
        latestVelocity = 0.0
        finishInteraction()
    }

    /** EGL can observe window loss before SurfaceHolder delivers its lifecycle callback. */
    fun rendererUnavailable() {
        rendererAttached = false
        attachedWidth = 0
        attachedHeight = 0
        surfaceReady = surfaceReady && holder.surface.isValid
        attachIfReady()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        sink.viewportChanged(Viewport(FixedPx.fromPixels(width), FixedPx.fromPixels(height)))
        if (rendererAttached && (width != attachedWidth || height != attachedHeight)) {
            detachRenderer()
        }
        attachIfReady()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestHighFrameRate()
        attachIfReady()
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        detachRenderer()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = holder.surface.isValid
        attachIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = holder.surface.isValid && width > 0 && height > 0
        if (rendererAttached && (width != attachedWidth || height != attachedHeight)) {
            detachRenderer()
        }
        attachIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        detachRenderer()
    }

    private fun begin(event: MotionEvent) {
        flushDrag()
        fling.stop()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        pointerId = event.getPointerId(0)
        pointerDeltas.begin(event.y)
        val at = event.eventTime * NANOS_PER_MILLISECOND
        previousFrameNanos = at
        lastMotionNanos = at
        latestVelocity = 0.0
        gestureMoved = false
    }

    private fun move(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        val at = event.eventTime * NANOS_PER_MILLISECOND
        val delta = appendSamples(event, index)
        val elapsed = (at - lastMotionNanos).coerceAtLeast(1L)
        latestVelocity = delta * NANOS_PER_SECOND / elapsed
        lastMotionNanos = at
        beginInteraction()
        scheduleDrag()
    }

    private fun changePointer(event: MotionEvent) {
        val lifted = event.actionIndex
        if (event.getPointerId(lifted) != pointerId) return
        val replacement = if (lifted == 0) 1 else 0
        if (replacement >= event.pointerCount) return
        pointerId = event.getPointerId(replacement)
        pointerDeltas.rebase(event.getY(replacement))
    }

    private fun end(event: MotionEvent, flingAfter: Boolean) {
        val tracker = velocityTracker
        tracker?.addMovement(event)
        val index = event.findPointerIndex(pointerId)
        if (index >= 0) appendSamples(event, index)
        if (flingAfter && tracker != null) {
            tracker.computeCurrentVelocity(1_000, maximumFlingVelocity.toFloat())
            flingVelocity = (-tracker.getYVelocity(pointerId)).toDouble()
        }
        ending = true
        scheduleDrag()
        tracker?.recycle()
        velocityTracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (!gestureMoved && flingAfter) performClick()
    }

    private fun scheduleDrag() {
        if (dragScheduled) return
        if (motionSequence == 0L) motionSequence = issueMotionSequence()
        dragScheduled = true
        dragFrame.post()
    }

    private fun drawDrag(frameTime: Long, vsyncId: Long, expectedPresentation: Long) {
        dragScheduled = false
        var moved = false
        pointerDeltas.drain().forEach { delta ->
            val elapsed = (frameTime - previousFrameNanos).coerceAtLeast(1L)
            val velocity = latestVelocity.takeIf { it != 0.0 }
                ?: delta * NANOS_PER_SECOND / elapsed
            if (emitScroll(delta, velocity, frameTime, expectedPresentation, vsyncId)) {
                moved = true
            }
        }
        if (moved) sink.motionFrame(motionSequence, frameTime)
        previousFrameNanos = frameTime
        if (ending) finishDrag(frameTime)
        if (pointerDeltas.hasPending || ending) scheduleDrag() else motionSequence = 0L
    }

    private fun appendSamples(event: MotionEvent, index: Int): Double {
        var delta = 0.0
        for (sample in 0 until event.historySize) {
            delta += pointerDeltas.append(event.getHistoricalY(index, sample))
        }
        return delta + pointerDeltas.append(event.getY(index))
    }

    private fun finishDrag(frameTime: Long) {
        ending = false
        val velocity = flingVelocity
        flingVelocity = null
        val started = velocity != null && fling.start(velocity, frameTime, issueMotionSequence())
        if (!started) finishInteraction()
    }

    private fun emitScroll(
        deltaPixels: Double,
        velocityPixelsPerSecond: Double,
        frameTimeNanos: Long,
        expectedPresentationTimeNanos: Long,
        frameTimelineVsyncId: Long,
    ): Boolean {
        if (deltaPixels == 0.0) return false
        return sink.userScroll(
            FixedPx.fromPixels(deltaPixels),
            velocityPixelsPerSecond.toFloat(),
            frameTimeNanos,
            frameTimelineVsyncId,
            expectedPresentationTimeNanos,
        )
    }

    private fun flushDrag() {
        if (dragScheduled) dragFrame.cancel()
        dragScheduled = false
        pointerDeltas.drain().forEach { delta ->
            emitScroll(delta, 0.0, System.nanoTime(), 0L, -1L)
        }
        ending = false
        flingVelocity = null
        motionSequence = 0L
    }

    private fun beginInteraction() {
        gestureMoved = true
        if (interaction) return
        interaction = true
        sink.interactionChanged(true, System.nanoTime())
    }

    private fun finishInteraction() {
        if (!interaction) return
        interaction = false
        sink.interactionChanged(false, System.nanoTime())
    }

    private fun attachIfReady() {
        if (!foreground || rendererAttached || !surfaceReady || !isAttachedToWindow ||
            width <= 0 || height <= 0) return
        rendererAttached = true
        attachedWidth = width
        attachedHeight = height
        sink.surfaceAvailable(holder.surface, width, height, display?.refreshRate ?: 60.0F)
    }

    private fun detachRenderer() {
        if (!rendererAttached) return
        rendererAttached = false
        attachedWidth = 0
        attachedHeight = 0
        sink.surfaceUnavailable()
    }

    private fun requestHighFrameRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    private fun issueMotionSequence(): Long = nextMotionSequence.also {
        nextMotionSequence = if (it == Long.MAX_VALUE) 1L else it + 1L
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
