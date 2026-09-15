package ml.melun.mangaview.viewer.runtime

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

class ViewerSurfaceHostZoomDeviceTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class RecordingSink : ViewerSurfaceSink {
        val scrollUnits = mutableListOf<Long>()

        override fun viewportChanged(viewport: Viewport) {}
        override fun surfaceAvailable(surface: Surface, width: Int, height: Int, refreshRate: Float,
            reportAttached: (Boolean) -> Unit) { reportAttached(false) }
        override fun surfaceUnavailable() {}
        override fun userScroll(delta: FixedPx, velocityPixelsPerSecond: Float, frameTimeNanos: Long,
            frameTimelineVsyncId: Long, expectedPresentationTimeNanos: Long): Boolean {
            scrollUnits += delta.units
            return true
        }
        override fun interactionChanged(active: Boolean, atNanos: Long) {}
        override fun motionFrame(sequence: Long, atNanos: Long) {}
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, ids: IntArray, coords: FloatArray): MotionEvent {
        val properties = Array(ids.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val pointerCoords = Array(ids.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = coords[index * 2]
                y = coords[index * 2 + 1]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(downTime, eventTime, action, ids.size, properties, pointerCoords,
            0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun dispatch(host: ViewerSurfaceHost, event: MotionEvent) {
        try {
            host.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test fun pinchMagnifiesTheSurfaceAndAnchorsTheEngineScroll() = onMain {
        val sink = RecordingSink()
        val host = ViewerSurfaceHost(ApplicationProvider.getApplicationContext<Context>(), sink)
        host.layout(0, 0, 1080, 2000)
        val downTime = SystemClock.uptimeMillis()

        dispatch(host, event(downTime, downTime, MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(300f, 800f)))
        dispatch(host, event(downTime, downTime + 16,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(0, 1), floatArrayOf(300f, 800f, 700f, 1200f)))
        dispatch(host, event(downTime, downTime + 32, MotionEvent.ACTION_MOVE, intArrayOf(0, 1),
            floatArrayOf(200f, 700f, 900f, 1600f)))

        assertEquals(2.0f, host.scaleX, 0.1f)
        assertEquals(host.scaleX, host.scaleY, 0.001f)
        assertTrue("pinch must pan the magnified content horizontally: ${host.translationX}",
            host.translationX < 0f)
        val minimum = 1080f * (1f - host.scaleX)
        assertTrue(host.translationX >= minimum - 1f)
        assertTrue("pinch must anchor the document row through engine scroll",
            (sink.scrollUnits.lastOrNull() ?: 0L) > 500_000L)

        dispatch(host, event(downTime, downTime + 48,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(0, 1), floatArrayOf(200f, 700f, 900f, 1600f)))
        dispatch(host, event(downTime, downTime + 64, MotionEvent.ACTION_UP, intArrayOf(0), floatArrayOf(200f, 700f)))

        assertEquals(2.0f, host.scaleX, 0.1f)
        assertTrue((sink.scrollUnits.lastOrNull() ?: 0L) > 500_000L)
    }

    @Test fun doubleTapTogglesMagnificationAndAnchorsScroll() = onMain {
        val sink = RecordingSink()
        val host = ViewerSurfaceHost(ApplicationProvider.getApplicationContext<Context>(), sink)
        host.layout(0, 0, 1080, 2000)

        host.toggleZoom(540f, 1000f)
        assertEquals(ViewerZoomState.DOUBLE_TAP_SCALE, host.scaleX, 0.001f)
        assertEquals(-540f, host.translationX, 0.001f)
        assertEquals(500.0, sink.scrollUnits.last() / 1024.0, 60.0)

        host.toggleZoom(540f, 1000f)
        assertEquals(ViewerZoomState.MIN_SCALE, host.scaleX, 0.001f)
        assertEquals(0f, host.translationX, 0.001f)
        // Local row under the finger is 500 at 2x, so zooming out anchors back by 500 pixels.
        assertEquals(-500.0, sink.scrollUnits.last() / 1024.0, 60.0)
    }

    @Test fun doubleTapOnTheTouchRootZoomsInsteadOfTogglingChrome() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var taps = 0
        var doubleTaps = 0
        val root = ml.melun.mangaview.activity.ViewerTouchRoot(context)
        onMain {
            root.isClickable = true
            root.onSurfaceTap = { taps++ }
            root.onSurfaceDoubleTap = { _, _ -> doubleTaps++ }
            root.layout(0, 0, 1080, 2000)
            tap(root, 300f, 800f)
            tap(root, 304f, 803f)
        }

        assertEquals(1, doubleTaps)
        Thread.sleep(500)
        onMain { assertEquals(0, taps) }
    }

    @Test fun singleTapCommitsOnlyAfterTheDoubleTapWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var taps = 0
        val root = ml.melun.mangaview.activity.ViewerTouchRoot(context)
        onMain {
            root.isClickable = true
            root.onSurfaceTap = { taps++ }
            root.layout(0, 0, 1080, 2000)
            tap(root, 300f, 800f)
        }

        assertEquals(0, taps)
        Thread.sleep(500)
        onMain { assertEquals(1, taps) }
    }

    private var tapSequence = 0L
    private fun tap(root: android.view.View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis() + tapSequence++ * 10
        val down = event(downTime, downTime, MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(x, y))
        val up = event(downTime, downTime + 40, MotionEvent.ACTION_UP, intArrayOf(0), floatArrayOf(x, y))
        try {
            root.dispatchTouchEvent(down)
            root.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
