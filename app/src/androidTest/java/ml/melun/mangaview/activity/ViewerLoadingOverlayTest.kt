package ml.melun.mangaview.activity

import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ViewerLoadingOverlayTest {
    @Test fun loadingGesturesStayOnLoadingUiAndNewGesturesReachReadyReader() = onMain {
        val fixture = Fixture()
        fixture.gesture()
        assertTrue(fixture.actions.isEmpty())
        assertEquals(0, fixture.taps)
        fixture.loading.complete()
        fixture.gesture()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), fixture.actions)
    }

    @Test fun aGestureStartedDuringLoadingDoesNotJoinTheReadyReaderHalfwayThrough() = onMain {
        val fixture = Fixture()
        fixture.send(MotionEvent.ACTION_DOWN, 40f)
        fixture.loading.complete()
        fixture.send(MotionEvent.ACTION_MOVE, 100f)
        fixture.send(MotionEvent.ACTION_UP, 100f)
        assertTrue(fixture.actions.isEmpty())
        assertEquals(0, fixture.taps)
        fixture.gesture()
        assertEquals(MotionEvent.ACTION_DOWN, fixture.actions.first())
        assertEquals(3, fixture.actions.size)
    }

    @Test fun failedInitialLoadKeepsTheUnpreparedReaderDisabled() = onMain {
        val fixture = Fixture()
        fixture.loading.failed()
        fixture.gesture()
        assertTrue(fixture.loading.active)
        assertTrue(fixture.actions.isEmpty())
        assertEquals(0, fixture.taps)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Fixture {
        val actions = mutableListOf<Int>()
        var taps = 0
        private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(),
            android.R.style.Theme_Material)
        val loading = ViewerLoadingOverlay(context)
        private val root = ViewerTouchRoot(context).apply {
            onSurfaceTap = { taps++ }
            excludesSurfaceTap = { _, _ -> loading.active }
            addView(object : View(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    actions += event.actionMasked
                    return true
                }
            }, FrameLayout.LayoutParams(300, 600))
            addView(loading, FrameLayout.LayoutParams(300, 600))
            measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 300, 600)
        }
        private val downTime = SystemClock.uptimeMillis()
        private var step = 0L

        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, downTime + ++step * 16, action, 30f, y, 0)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }

        fun gesture() {
            send(MotionEvent.ACTION_DOWN, 40f)
            send(MotionEvent.ACTION_MOVE, 100f)
            send(MotionEvent.ACTION_UP, 100f)
        }
    }
}
