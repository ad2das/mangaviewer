package ml.melun.mangaview.viewer

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.io.Closeable

/** Primitive, allocation-free capture of every HWUI frame reported by the viewer window. */
internal class ViewerWindowFrameRecorder(
    private val window: Window,
) : Closeable {
    private val lock = Any()
    private val thread = HandlerThread("viewer-frame-metrics").apply { start() }
    private val intendedVsync = LongArray(MAX_SAMPLES)
    private val totalDuration = LongArray(MAX_SAMPLES)
    private var writeIndex = 0
    private var count = 0
    private var dropped = 0
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedSinceLast ->
        val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        synchronized(lock) {
            dropped += droppedSinceLast.coerceAtLeast(0)
            if (intended <= 0L || duration < 0L) return@synchronized
            intendedVsync[writeIndex] = intended
            totalDuration[writeIndex] = duration
            writeIndex = (writeIndex + 1) % MAX_SAMPLES
            count = minOf(count + 1, MAX_SAMPLES)
        }
    }

    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    fun snapshot(): LongArray = synchronized(lock) {
        val start = if (count == MAX_SAMPLES) writeIndex else 0
        LongArray(count * 2).also { packed ->
            repeat(count) { offset ->
                val source = (start + offset) % MAX_SAMPLES
                packed[offset * 2] = intendedVsync[source]
                packed[offset * 2 + 1] = totalDuration[source]
            }
        }
    }

    fun droppedReportCount(): Int = synchronized(lock) { dropped }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    private companion object {
        const val MAX_SAMPLES = 32_768
    }
}
