package ml.melun.mangaview.activity

import android.view.Choreographer
import ml.melun.mangaview.data.settings.MAX_AUTO_SCROLL_SPEED

/**
 * Hands-free reading: each display frame advances the page by speed x frame time through the
 * same synthetic-scroll path the volume keys use. It stops when the reader touches the page,
 * when the reader leaves the foreground, or when the document stops moving (series end).
 */
internal class ViewerAutoScroller(
    private val density: Float,
    private val step: (Double) -> Boolean,
    private val onStateChanged: (Boolean) -> Unit,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var lastFrameNanos = 0L
    private var stalledFrames = 0
    private var carry = 0.0
    var speed = 2
        set(value) { field = value.coerceIn(1, MAX_AUTO_SCROLL_SPEED) }
    var running = false
        private set

    fun toggle() = if (running) stop() else start()

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        stalledFrames = 0
        carry = 0.0
        choreographer.postFrameCallback(this)
        onStateChanged(true)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
        onStateChanged(false)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) advance(frameTimeNanos - lastFrameNanos)
        lastFrameNanos = frameTimeNanos
        if (running) choreographer.postFrameCallback(this)
    }

    private fun advance(elapsedNanos: Long) {
        // Clamp a long frame gap (a dropped vsync, a GC pause) so the page never lurches.
        val seconds = elapsedNanos.coerceIn(0L, MAX_FRAME_NANOS) / 1_000_000_000.0
        carry += DP_PER_SECOND[speed - 1] * density * seconds
        if (carry < 1.0) return
        val whole = kotlin.math.floor(carry)
        carry -= whole
        stalledFrames = if (step(whole)) 0 else stalledFrames + 1
        if (stalledFrames >= STALL_FRAMES) stop()
    }

    private companion object {
        val DP_PER_SECOND = doubleArrayOf(36.0, 64.0, 100.0, 150.0, 220.0)
        const val MAX_FRAME_NANOS = 50_000_000L
        /** About two seconds at 60 Hz with no movement: the reader reached the end. */
        const val STALL_FRAMES = 120
    }
}
