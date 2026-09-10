package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Standard Android hardware Canvas control; frame metrics are not physical presentation timestamps. */
internal suspend fun measurePreparedCanvasControl(
    scenario: ActivityScenario<EngineBufferedProbeActivity>, bitmap: Bitmap, width: Int, height: Int,
    screenshotFile: File,
    record: (JSONObject) -> Unit,
): JSONObject {
    val finished = CompletableDeferred<Unit>()
    val offered = mutableListOf<Long>()
    val drawn = mutableListOf<Long>()
    val metrics = mutableListOf<JSONObject>()
    val observer = HandlerThread("canvas-control-metrics").apply { start() }
    val pageHeight = bitmap.height.toFloat() * width / bitmap.width
    val travel = minOf(pageHeight * 2 - height, 500F)
    require(travel > 0F)
    lateinit var window: Window
    lateinit var root: FrameLayout
    lateinit var view: View
    lateinit var choreographer: Choreographer
    lateinit var callback: Choreographer.FrameCallback
    var offset = 0F
    var failure: Throwable? = null
    var captureFailure: String? = null
    val captureBounds = IntArray(4)
    val listener = Window.OnFrameMetricsAvailableListener { _, frame, lost ->
        metrics += JSONObject().put("intendedVsyncNanos", frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
            .put("vsyncNanos", frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
            .put("totalDurationNanos", frame.getMetric(FrameMetrics.TOTAL_DURATION))
            .put("drawDurationNanos", frame.getMetric(FrameMetrics.DRAW_DURATION))
            .put("droppedMetricReports", lost)
    }
    try {
        scenario.onActivity { activity ->
            window = activity.window
            root = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            view = object : View(activity) {
                private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                private val bounds = RectF()
                override fun onDraw(canvas: Canvas) {
                    check(canvas.isHardwareAccelerated)
                    drawn += System.nanoTime()
                    for (page in 0..1) {
                        bounds.set(0F, page * pageHeight - offset, width.toFloat(), (page + 1) * pageHeight - offset)
                        canvas.drawBitmap(bitmap, null, bounds, paint)
                    }
                }
            }
            // The sibling SurfaceView has a transparent region; this fully covering control is opaque.
            view.setBackgroundColor(android.graphics.Color.WHITE)
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            window.addOnFrameMetricsAvailableListener(listener, Handler(observer.looper))
            choreographer = Choreographer.getInstance()
            callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val phase = offered.size % 120
                    offered += frameTimeNanos
                    offset = (if (phase < 60) phase else 120 - phase) * travel / 60
                    view.invalidate()
                    if (offered.size < 240) choreographer.postFrameCallback(this)
                    else choreographer.postFrameCallback {
                        choreographer.postFrameCallback { finished.complete(Unit) }
                    }
                }
            }
            choreographer.postFrameCallback(callback)
        }
        withTimeout(30000) { finished.await() }
    } catch (error: Throwable) { failure = error }
    finally {
        scenario.onActivity {
            choreographer.removeFrameCallback(callback)
            window.removeOnFrameMetricsAvailableListener(listener)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            captureBounds[0] = location[0]; captureBounds[1] = location[1]
            captureBounds[2] = view.width; captureBounds[3] = view.height
        }
        observer.quitSafely()
        observer.join()
        try {
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { screenshotFile.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { screenshot.recycle() }
        } catch (error: Throwable) { captureFailure = error.toString() }
        finally { scenario.onActivity { root.removeView(view) } }
    }
    val result = JSONObject().put("backend", "ANDROID_HARDWARE_CANVAS")
        .put("offeredFrameTimesNanos", JSONArray(offered)).put("drawTimesNanos", JSONArray(drawn))
        .put("frameMetrics", JSONArray(metrics)).put("physicalPresentationVerified", false)
        .put("completed", failure == null).put("failure", failure?.toString() ?: JSONObject.NULL)
        .put("screenshot", screenshotFile.name).put("captureFailure", captureFailure ?: JSONObject.NULL)
        .put("captureBounds", JSONArray(captureBounds.toList())).put("capturedOffsetPx", offset)
    record(result)
    failure?.let { throw it }
    return result
}
