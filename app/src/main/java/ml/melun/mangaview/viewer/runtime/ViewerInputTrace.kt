package ml.melun.mangaview.viewer.runtime

import android.os.Trace
import ml.melun.mangaview.engine.api.FrameIdentity

/**
 * Trace-only mirror of [PointerDeltaLedger] with raw-sample provenance.
 *
 * A gesture is mirrored only when tracing was already enabled at its ACTION_DOWN. The disabled
 * path stores no samples and allocates nothing. Tracing that starts mid-gesture stays unmapped for
 * that gesture: the mirror never backfills fabricated segments for samples it did not observe.
 * All methods are observation-only: they never touch the production ledger and never throw.
 */
internal class ViewerInputTraceLedger {
    internal data class Segment(
        val id: Long,
        val pointerId: Int,
        val firstSampleOrdinal: Long,
        val lastSampleOrdinal: Long,
        val mergedSampleCount: Int,
        val firstEventTimeNanos: Long,
        val lastEventTimeNanos: Long,
        val delta: Double,
        val synthetic: Boolean = false,
    )

    private val pending = ArrayDeque<Segment>()
    private var tracking = false
    private var lastY = 0f
    private var nextSegmentId = 1L
    private var samples = 0L

    val isTracking: Boolean get() = tracking
    val sampleCount: Long get() = samples

    fun begin(y: Float, enabled: Boolean) {
        tracking = enabled
        pending.clear()
        lastY = y
    }

    fun append(y: Float, eventTimeNanos: Long, pointerId: Int): Double {
        if (!tracking) return 0.0
        samples++
        val delta = (lastY - y).toDouble()
        if (delta != 0.0) {
            val last = pending.lastOrNull()
            if (last != null && (last.delta > 0.0) == (delta > 0.0)) {
                pending.removeLast()
                pending.addLast(last.copy(
                    lastSampleOrdinal = samples,
                    mergedSampleCount = last.mergedSampleCount + 1,
                    lastEventTimeNanos = eventTimeNanos,
                    delta = last.delta + delta,
                ))
            } else {
                pending.addLast(Segment(nextSegmentId++, pointerId, samples, samples, 1,
                    eventTimeNanos, eventTimeNanos, delta))
            }
        }
        lastY = y
        return delta
    }

    fun rebase(y: Float) {
        if (tracking) lastY = y
    }

    fun drain(): List<Segment> = if (!tracking) emptyList() else pending.toList().also { pending.clear() }

    /** Synthetic frame-driven fling step; carries no touch sample provenance. */
    fun synthetic(delta: Double): Segment =
        Segment(nextSegmentId++, 0, 0L, 0L, 1, 0L, 0L, delta, synthetic = true)
}

/** Trace-only gesture epoch bumped once at ACTION_DOWN, independent of the engine gesture id. */
internal class ViewerInputTraceGesture {
    var id: Long = 1L
        private set

    fun beginTouch() {
        id = if (id == Long.MAX_VALUE) 1L else id + 1L
    }
}

/** Disabled calls record nothing and never format a name. Enabled names stay at or below 127 chars. */
internal inline fun <T> viewerInputTrace(name: () -> String, block: () -> T): T {
    val enabled = Trace.isEnabled()
    if (enabled) Trace.beginSection(name())
    return try { block() } finally { if (enabled) Trace.endSection() }
}

/** viewer_sample:gestureEpoch:pointer:ordinal:eventTimeNanos:downTimeNanos:deltaBits (all hex). */
internal fun viewerSampleTraceName(gestureId: Long, pointerId: Int, sampleOrdinal: Long,
    eventTimeNanos: Long, downTimeNanos: Long, delta: Double): String =
    "viewer_sample:" + gestureId.hex() + ":" + pointerId.hex() + ":" + sampleOrdinal.hex() + ":" +
        eventTimeNanos.hex() + ":" + downTimeNanos.hex() + ":" + delta.bitsHex()

/**
 * viewer_segment:id:pointer:real:firstOrdinal-lastOrdinal:mergedCount:deltaBits (all hex).
 * A synthetic fling step is viewer_segment:id:0:fling:0-0:1:deltaBits and must never be joined to
 * a raw touch sample. An untracked gesture, including mid-gesture trace activation, has no mirror
 * segment and is emitted as viewer_segment:lost instead of a fabricated mapping.
 */
internal fun viewerSegmentTraceName(segment: ViewerInputTraceLedger.Segment?): String {
    if (segment == null) return "viewer_segment:lost"
    val tag = if (segment.synthetic) "fling" else "real"
    return "viewer_segment:" + segment.id.hex() + ":" + segment.pointerId.hex() + ":" + tag + ":" +
        segment.firstSampleOrdinal.hex() + "-" + segment.lastSampleOrdinal.hex() + ":" +
        segment.mergedSampleCount.hex() + ":" + segment.delta.bitsHex()
}

/**
 * engine_input:inputSequence:gesture:frameTimeNanos:inputRevision:movementRevision:sSession (hex).
 * Revisions are the snapshot after the input was dispatched.
 */
internal fun engineInputTraceName(inputSequence: Long, gestureId: Long, frameTimeNanos: Long,
    inputRevision: Long, movementRevision: Long, sessionId: Long): String =
    "engine_input:" + inputSequence.hex() + ":" + gestureId.hex() + ":" + frameTimeNanos.hex() + ":" +
        inputRevision.hex() + ":" + movementRevision.hex() + ":s" + sessionId.hex()

/** engine_present:sessionId:token:rendererEpoch:surfaceEpoch:rendererId (all hex). */
internal fun enginePresentTraceName(identity: FrameIdentity, rendererId: Long): String =
    "engine_present:" + identity.sessionId.hex() + ":" + identity.token.hex() + ":" +
        identity.rendererEpoch.hex() + ":" + identity.surfaceEpoch.hex() + ":" + rendererId.hex()

/** engine_present_fence:inputRevision:geometryRevision:timestampKindOrdinal:atNanos:frameId (hex). */
internal fun enginePresentFenceTraceName(identity: FrameIdentity, kindOrdinal: Int,
    timestampNanos: Long, eglFrameId: Long): String =
    "engine_present_fence:" + identity.inputRevision.hex() + ":" + identity.geometryRevision.hex() + ":" +
        kindOrdinal.hex() + ":" + timestampNanos.hex() + ":" + eglFrameId.hex()

private fun Long.hex(): String = toULong().toString(16)
private fun Int.hex(): String = toUInt().toString(16)
private fun Double.bitsHex(): String = toRawBits().toULong().toString(16)
