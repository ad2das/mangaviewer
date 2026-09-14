package ml.melun.mangaview.viewer.runtime

import android.os.Trace
import android.view.MotionEvent

/** Appends historical and current samples in order, mirroring each only after the real ledger. */
internal fun appendPointerSamples(
    event: MotionEvent,
    index: Int,
    pointerDeltas: PointerDeltaLedger,
    inputTrace: ViewerInputTraceLedger,
    gestureId: Long,
    pointerId: Int,
): Double {
    var delta = 0.0
    for (sample in 0 until event.historySize) {
        val y = event.getHistoricalY(index, sample)
        delta += pointerDeltas.append(y)
        recordPointerSample(inputTrace, gestureId, pointerId, y,
            event.getHistoricalEventTime(sample), event.downTime)
    }
    val y = event.getY(index)
    delta += pointerDeltas.append(y)
    recordPointerSample(inputTrace, gestureId, pointerId, y, event.eventTime, event.downTime)
    return delta
}

private fun recordPointerSample(inputTrace: ViewerInputTraceLedger, gestureId: Long, pointerId: Int,
    y: Float, eventTimeMillis: Long, downTimeMillis: Long) {
    if (!inputTrace.isTracking) return
    val eventTimeNanos = eventTimeMillis * NANOS_PER_MILLISECOND
    val delta = inputTrace.append(y, eventTimeNanos, pointerId)
    if (!Trace.isEnabled()) return
    Trace.beginSection(viewerSampleTraceName(gestureId, pointerId, inputTrace.sampleCount,
        eventTimeNanos, downTimeMillis * NANOS_PER_MILLISECOND, delta))
    Trace.endSection()
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
