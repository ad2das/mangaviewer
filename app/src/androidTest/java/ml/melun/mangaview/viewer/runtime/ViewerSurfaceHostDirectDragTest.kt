package ml.melun.mangaview.viewer.runtime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the direct-drag repair on the real host: a real MotionEvent MOVE/UP is consumed during
 * dispatch (no Choreographer batching wait), its origin is the honest dispatch entry with
 * [NO_VSYNC_ID], historical samples and opposite reversals keep the exact observed total, the sink
 * receives the same nonzero unique motion-sequence contract as the scheduled path, CANCEL never
 * starts a fling, and lifecycle flush stays synchronous.
 *
 * Assertions run inside the same `runOnMainSync` block as the dispatch so the sink receipt is
 * observed before any frame callback can interleave. The host is main-thread affine, mirroring the
 * established fixture in [ViewerPendingInputLifecycleTest].
 */
@RunWith(AndroidJUnit4::class)
class ViewerSurfaceHostDirectDragTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class Scroll(
        val frameTimeNanos: Long,
        val vsyncId: Long,
        val expectedPresentationNanos: Long,
        val deltaUnits: Long,
    )

    private class Sink : ViewerSurfaceSink {
        val scrolls = mutableListOf<Scroll>()
        val motions = mutableListOf<LongArray>()
        var interactionChanges = 0

        override fun viewportChanged(viewport: Viewport) {}
        override fun surfaceAvailable(surface: Surface, width: Int, height: Int, refreshRate: Float,
            reportAttached: (Boolean) -> Unit) {}
        override fun surfaceUnavailable() {}
        override fun userScroll(delta: FixedPx, velocityPixelsPerSecond: Float, frameTimeNanos: Long,
            frameTimelineVsyncId: Long, expectedPresentationTimeNanos: Long): Boolean {
            scrolls += Scroll(frameTimeNanos, frameTimelineVsyncId, expectedPresentationTimeNanos,
                delta.units)
            return delta.units != 0L
        }
        override fun interactionChanged(active: Boolean, atNanos: Long) { interactionChanges++ }
        override fun motionFrame(sequence: Long, atNanos: Long) {
            motions += longArrayOf(sequence, atNanos)
        }
    }

    private fun create(sink: Sink): ViewerSurfaceHost {
        lateinit var host: ViewerSurfaceHost
        instrumentation.runOnMainSync {
            host = ViewerSurfaceHost(instrumentation.targetContext, sink)
        }
        return host
    }

    private fun motion(eventTime: Long, action: Int, y: Float) =
        MotionEvent.obtain(0L, eventTime, action, 100f, y, 0)

    private fun release(host: ViewerSurfaceHost) {
        instrumentation.runOnMainSync { host.cancelMotion() }
    }

    @Test fun moveIsAppliedOnTheDispatchPassBeforeAnyFrameCallback() {
        val sink = Sink()
        val host = create(sink)
        val down = motion(10L, MotionEvent.ACTION_DOWN, 200f)
        val move = motion(20L, MotionEvent.ACTION_MOVE, 260f)
        try {
            instrumentation.runOnMainSync {
                assertTrue(host.onTouchEvent(down))
                assertTrue(host.onTouchEvent(move))
                assertEquals(1, sink.scrolls.size)
                val scroll = sink.scrolls.single()
                assertEquals(NO_VSYNC_ID, scroll.vsyncId)
                assertEquals(0L, scroll.expectedPresentationNanos)
                assertTrue(scroll.frameTimeNanos > 0L)
                assertEquals(1, sink.motions.size)
                assertTrue(sink.motions.single()[0] > 0L)
                assertTrue(sink.motions.single()[1] > 0L)
            }
            val settledScrolls = sink.scrolls.size
            val settledMotions = sink.motions.size
            Thread.sleep(100L)
            assertEquals(settledScrolls, sink.scrolls.size)
            assertEquals(settledMotions, sink.motions.size)
        } finally {
            down.recycle()
            move.recycle()
            release(host)
        }
    }

    @Test fun historicalSamplesAndOppositeReversalKeepTheExactTotal() {
        val sink = Sink()
        val host = create(sink)
        val down = motion(10L, MotionEvent.ACTION_DOWN, 200f)
        val move = motion(20L, MotionEvent.ACTION_MOVE, 225f)
        move.addBatch(25L, 100f, 250f, 1f, 1f, 0)
        val reversal = motion(30L, MotionEvent.ACTION_MOVE, 245f)
        try {
            instrumentation.runOnMainSync {
                assertTrue(host.onTouchEvent(down))
                assertTrue(host.onTouchEvent(move))
                assertEquals(1, sink.scrolls.size)
                assertEquals(FixedPx.fromPixels(-50.0).units, sink.scrolls.single().deltaUnits)
                assertEquals(1, sink.motions.size)
                assertTrue(sink.motions.single()[0] > 0L)
                assertTrue(host.onTouchEvent(reversal))
                assertEquals(2, sink.scrolls.size)
                assertEquals(FixedPx.fromPixels(5.0).units, sink.scrolls.last().deltaUnits)
                assertEquals(2, sink.motions.size)
                assertTrue(sink.motions.last()[0] > 0L)
                assertNotEquals(sink.motions[0][0], sink.motions[1][0])
            }
        } finally {
            down.recycle()
            move.recycle()
            reversal.recycle()
            release(host)
        }
    }

    @Test fun terminalDispatchKeepsSequenceAndCancelNeverStartsAFling() {
        val sink = Sink()
        val host = create(sink)
        val down = motion(10L, MotionEvent.ACTION_DOWN, 200f)
        val move = motion(20L, MotionEvent.ACTION_MOVE, 260f)
        val cancel = motion(30L, MotionEvent.ACTION_CANCEL, 250f)
        try {
            instrumentation.runOnMainSync {
                assertTrue(host.onTouchEvent(down))
                assertTrue(host.onTouchEvent(move))
                assertTrue(host.onTouchEvent(cancel))
                assertEquals(2, sink.scrolls.size)
                assertEquals(FixedPx.fromPixels(10.0).units, sink.scrolls.last().deltaUnits)
                assertEquals(2, sink.motions.size)
                assertTrue(sink.motions.all { it[0] > 0L })
                assertNotEquals(sink.motions[0][0], sink.motions[1][0])
            }
            val settledScrolls = sink.scrolls.size
            val settledMotions = sink.motions.size
            Thread.sleep(100L)
            assertEquals(settledScrolls, sink.scrolls.size)
            assertEquals(settledMotions, sink.motions.size)
            assertTrue(sink.interactionChanges >= 2)
        } finally {
            down.recycle()
            move.recycle()
            cancel.recycle()
            release(host)
        }
    }

    @Test fun upAppliesTerminalDeltaAndCancelMotionStopsTheFling() {
        val sink = Sink()
        val host = create(sink)
        val start = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 100f, 200f, 0)
        val move = MotionEvent.obtain(start, start + 8L, MotionEvent.ACTION_MOVE, 100f, 260f, 0)
        val up = MotionEvent.obtain(start, start + 16L, MotionEvent.ACTION_UP, 100f, 230f, 0)
        try {
            instrumentation.runOnMainSync {
                assertTrue(host.onTouchEvent(down))
                assertTrue(host.onTouchEvent(move))
                val terminalIndex = sink.scrolls.size
                assertTrue(host.onTouchEvent(up))
                // The real terminal delta must arrive first even if a legitimate release catch-up
                // frame interleaves before this block yields.
                assertTrue(sink.scrolls.size > terminalIndex)
                val terminal = sink.scrolls[terminalIndex]
                assertEquals(FixedPx.fromPixels(30.0).units, terminal.deltaUnits)
                assertEquals(NO_VSYNC_ID, terminal.vsyncId)
                assertEquals(0L, terminal.expectedPresentationNanos)
                assertTrue(sink.motions.last()[0] > 0L)
            }
            Thread.sleep(100L)
            instrumentation.runOnMainSync { host.cancelMotion() }
            val settledScrolls = sink.scrolls.size
            val settledMotions = sink.motions.size
            Thread.sleep(50L)
            assertEquals(settledScrolls, sink.scrolls.size)
            assertEquals(settledMotions, sink.motions.size)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
            release(host)
        }
    }
}
