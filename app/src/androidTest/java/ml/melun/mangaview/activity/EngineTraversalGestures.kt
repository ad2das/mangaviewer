package ml.melun.mangaview.activity

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONObject

/** Platform-dispatched touchscreen gestures, including ordinary fractional pointer coordinates. */
internal fun injectEngineTraversalGesture(
    instrumentation: Instrumentation,
    device: UiDevice,
    output: File,
    number: Int,
    forward: Boolean,
) {
    val x = device.displayWidth / 2f
    val phase = (number % 4) * 0.25f
    val start = (if (forward) device.displayHeight * 3 / 4f else device.displayHeight / 4f) + phase
    val end = (if (forward) device.displayHeight / 4f else device.displayHeight * 3 / 4f) + phase + (number % 3 + 1) * 0.125f
    val downTime = SystemClock.uptimeMillis()
    val records = StringBuilder()
    var primary: Throwable? = null
    var finished = false
    fun send(action: Int, y: Float) {
        val at = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val accepted = try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        records.append(JSONObject().apply {
            put("gestureOrdinal", number); put("action", action); put("downTimeMillis", downTime); put("eventTimeMillis", at)
            put("xBits", x.toRawBits()); put("yBits", y.toRawBits()); put("x", x); put("y", y)
            put("source", InputDevice.SOURCE_TOUCHSCREEN); put("dispatchAccepted", accepted)
            put("dispatchReturnedMonotonicNs", System.nanoTime()); put("receivedByViewerVerified", false)
        }).append('\n')
        check(accepted) { "Platform touchscreen injection was rejected" }
    }
    try {
        send(MotionEvent.ACTION_DOWN, start)
        for (step in 1..30) {
            SystemClock.sleep(5)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 30f)
        }
        send(MotionEvent.ACTION_UP, end)
        finished = true
    } catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        if (!finished) {
            try { send(MotionEvent.ACTION_CANCEL, end) } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
        try { File(output, "injected-motion.jsonl").appendText(records.toString()) } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }
}
