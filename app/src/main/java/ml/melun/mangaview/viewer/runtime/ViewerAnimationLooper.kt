package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import java.util.concurrent.CountDownLatch

/**
 * Dedicated motion looper for the app-driven fling tail.
 *
 * A gesture's motion step cannot share the main message queue with engine work: whenever the main
 * thread is still busy at a vsync, the platform does not hand that display slot to the app, and the
 * recorded cadence then jumps two periods. This looper runs nothing but the motion callback, and
 * paces it from its own deadline timer as well as from its own display-slot callback, because this
 * platform can withhold a whole vsync event from an idle client. The engine work the step reveals
 * is applied on the main thread, in order, by [ViewerSurfaceHost].
 *
 * The thread owns its [Choreographer] and prepares it on itself, so nothing here blocks while the
 * enclosing object is still being initialized.
 */
internal object ViewerAnimationLooper {
    private class AnimationThread : HandlerThread("viewer-animation", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
        private val ready = CountDownLatch(1)
        @Volatile private var value: Choreographer? = null

        override fun onLooperPrepared() {
            value = Choreographer.getInstance()
            ready.countDown()
        }

        fun choreographer(): Choreographer {
            ready.await()
            return checkNotNull(value)
        }
    }

    private val thread = AnimationThread()

    init {
        thread.start()
    }

    val looper: Looper get() = thread.looper
    val choreographer: Choreographer get() = thread.choreographer()

    /** Runs [block] on the animation looper, inline when the caller is already on it. */
    fun dispatch(block: Runnable) {
        if (Looper.myLooper() === looper) block.run() else Handler(looper).post(block)
    }

    fun isAnimationThread(): Boolean = Looper.myLooper() === looper
}

/**
 * [ViewerFrameScheduler] that paces the fling on the display's own time grid.
 *
 * The step carries the grid point it belongs to, never the instant the looper happened to run it.
 * That is the contract Choreographer already offers: `frameTimeNanos` is the vsync the frame is
 * *for*, not the wall-clock moment its callback was dispatched. [ViewerFlingDriver] re-bases every
 * next deadline on the frame time it was handed, so handing back the grid point makes the whole tail
 * advance by exactly one refresh period per step and lets the physics integrate a fixed step.
 *
 * Two independent triggers decide *when* that step is produced. A looper timer is always delivered by
 * the kernel, but this emulator withholds whole vsync events even from an idle secondary
 * Choreographer client (measured 3.24% of periods, including 50/66/100 ms holes), and a slot's
 * dispatch can itself be several milliseconds late when the host is busy. Whichever arrives first
 * produces the pending grid step and disarms the other. Because both paths hand back the same grid
 * time, a late wake-up or a withheld slot cannot leak into the recorded cadence; a step that is
 * produced late still shows up where it belongs, in the Surface presentation evidence.
 *
 * A slot at or before the last delivered grid point is the tail of that step's period rather than the
 * arrival of the next one, so it is ignored and the next slot (or the timer) is awaited.
 */
internal class ViewerAnimationScheduler(
    private val callback: (
        frameTimeNanos: Long,
        vsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) -> Unit,
) : ViewerFrameScheduler {
    private val handler = Handler.createAsync(ViewerAnimationLooper.looper)
    private val choreographer = ViewerAnimationLooper.choreographer
    private var scheduled = false
    private var slotChainRunning = false
    private var deadlineNanos = 0L
    private var lastDeliveredNanos = 0L
    private val tick = Runnable { onTrigger() }
    private val slot: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        // Re-arm before any delivery decision. The chain must never wait on work: one skipped
        // re-post costs a whole display period, and a chain broken by a cancelled callback is what
        // made this emulator look like it withheld a slot for an entire fling.
        if (slotChainRunning) choreographer.postFrameCallback(slot)
        if (!scheduled) return@FrameCallback
        if (frameTimeNanos <= lastDeliveredNanos) return@FrameCallback
        onTrigger()
    }

    override fun post(dueNanos: Long) = ViewerAnimationLooper.dispatch {
        if (scheduled) return@dispatch
        scheduled = true
        deadlineNanos = dueNanos
        if (!slotChainRunning) {
            slotChainRunning = true
            choreographer.postFrameCallback(slot)
        }
        // The slot chain is armed alongside the timer as the cover for a withheld vsync or a late
        // timer.
        armTimer()
    }

    /**
     * Arms the deadline timer, and is safe to call again while the deadline is pending. A wake-up
     * before the grid point must re-arm for the *remaining* time: arming with a fixed lead would
     * land in the past, and the looper would then re-run the timer immediately, spinning this
     * URGENT_DISPLAY thread and starving the render work the step exists to feed.
     */
    private fun armTimer() {
        handler.removeCallbacks(tick)
        val remaining = deadlineNanos - System.nanoTime()
        val delayMillis = if (remaining <= 0L) 0L else (remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
        handler.postDelayed(tick, delayMillis)
    }

    override fun cancel() = ViewerAnimationLooper.dispatch {
        scheduled = false
        handler.removeCallbacks(tick)
        if (slotChainRunning) {
            slotChainRunning = false
            choreographer.removeFrameCallback(slot)
        }
    }

    private fun onTrigger() {
        if (!scheduled) return
        val now = System.nanoTime()
        if (now < deadlineNanos) {
            // Woken ahead of the grid point (a slot can arrive early). The engine rejects a frame
            // time in the future, so hold the deadline armed and deliver it once the display period
            // has actually begun.
            armTimer()
            return
        }
        scheduled = false
        val frameTimeNanos = deadlineNanos
        lastDeliveredNanos = frameTimeNanos
        handler.removeCallbacks(tick)
        callback(frameTimeNanos, NO_VSYNC_ID, 0L)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
