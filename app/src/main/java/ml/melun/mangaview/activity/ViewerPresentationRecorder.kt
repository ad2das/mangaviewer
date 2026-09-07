package ml.melun.mangaview.activity

import java.util.ArrayDeque
import kotlin.math.max
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind

internal class ViewerPresentationRecorder(private val nanoTime: () -> Long = System::nanoTime) {
    private val presentationLock = Any()
    private val motionLock = Any()
    private val gestureLock = Any()
    private val presentations = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationRendererIds = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationTokens = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationGenerations = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationScrollOffsets = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationViewportHeights = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationAnchorOrdinals = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationAnchorOffsets = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationFlags = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationVsyncIds = LongArray(MAX_PRESENTATION_SAMPLES)
    private val expectedPresentationTimes = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationLatencies = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationSubmittedAt = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationBufferIds = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationGeometryRevisions = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationInputRevisions = LongArray(MAX_PRESENTATION_SAMPLES)
    private val presentationScrollCauses = LongArray(MAX_PRESENTATION_SAMPLES)
    private val renderAt = LongArray(MAX_PRESENTATION_SAMPLES)
    private val renderLatency = LongArray(MAX_PRESENTATION_SAMPLES)
    private val motionSequences = LongArray(MAX_PRESENTATION_SAMPLES)
    private val motionFrames = LongArray(MAX_PRESENTATION_SAMPLES)
    private val motionAppliedAt = LongArray(MAX_PRESENTATION_SAMPLES)
    private var presentationWriteIndex = 0
    private var presentationCount = 0
    private var presentationSequence = 0L
    private var renderWriteIndex = 0
    private var renderCount = 0
    private var motionWriteIndex = 0
    private var motionCount = 0
    private var motionSequence = 0L
    private val gestureWindows = ArrayDeque<LongRange>()
    private var activeGestureStart: Long? = null
    private var requestedUiEpoch = 0L
    private var reportedUiEpoch = -1L

    fun beginUiEpoch() = synchronized(presentationLock) {
        requestedUiEpoch += 1L
    }

    fun recordPresentation(evidence: NativePresentationEvidence): Boolean {
        return synchronized(presentationLock) {
            val index = presentationWriteIndex
            presentations[index] = evidence.presentedNanos
            presentationRendererIds[index] = evidence.rendererIdentity
            presentationTokens[index] = evidence.token
            presentationGenerations[index] = evidence.generation
            presentationScrollOffsets[index] = evidence.scrollOffsetUnits
            presentationViewportHeights[index] = evidence.viewportHeightUnits
            presentationAnchorOrdinals[index] = evidence.anchorOrdinal.toLong()
            presentationAnchorOffsets[index] = evidence.anchorOffsetUnits
            presentationFlags[index] = NativePresentationEvidencePacking.flags(evidence)
            presentationVsyncIds[index] = evidence.frameTimelineVsyncId
            expectedPresentationTimes[index] = evidence.expectedPresentationTimeNanos
            presentationLatencies[index] = evidence.renderLatencyNanos
            presentationSubmittedAt[index] = evidence.submittedAtNanos
            presentationBufferIds[index] = evidence.bufferFrameId
            presentationGeometryRevisions[index] = evidence.geometryRevision
            presentationInputRevisions[index] = evidence.userInputRevision
            presentationScrollCauses[index] = evidence.scrollCause?.ordinal?.toLong() ?: -1L
            presentationWriteIndex = (presentationWriteIndex + 1) % MAX_PRESENTATION_SAMPLES
            presentationCount = minOf(presentationCount + 1, MAX_PRESENTATION_SAMPLES)
            presentationSequence += 1L
            if (evidence.renderLatencyNanos >= 0L && evidence.presentedNanos > 0L) {
                recordRender(evidence.presentedNanos, evidence.renderLatencyNanos)
            }
            if (evidence.presentedNanos <= 0L || !evidence.readableActualContent ||
                evidence.timestampKind != PresentationTimestampKind.DISPLAY_PRESENT ||
                reportedUiEpoch == requestedUiEpoch) false else {
                reportedUiEpoch = requestedUiEpoch
                true
            }
        }
    }

    fun recordGestureBoundary(started: Boolean, atNanos: Long) {
        if (atNanos <= 0L) return
        synchronized(gestureLock) {
            if (started) {
                if (activeGestureStart == null) activeGestureStart = atNanos
            } else {
                val start = activeGestureStart ?: return
                activeGestureStart = null
                while (gestureWindows.size >= MAX_GESTURE_SAMPLES) gestureWindows.removeFirst()
                gestureWindows.addLast(start..max(start, atNanos))
            }
        }
    }

    fun recordMotionFrame(sequence: Long, frameTimeNanos: Long) {
        // The callback runs on main after applying the scroll. Choreographer's VSYNC can
        // precede the input event, so it cannot measure when the input actually took effect.
        val appliedAtNanos = nanoTime()
        if (sequence <= 0L || frameTimeNanos <= 0L) return
        synchronized(motionLock) {
            motionSequences[motionWriteIndex] = sequence
            motionFrames[motionWriteIndex] = frameTimeNanos
            motionAppliedAt[motionWriteIndex] = appliedAtNanos
            motionWriteIndex = (motionWriteIndex + 1) % MAX_PRESENTATION_SAMPLES
            motionCount = minOf(motionCount + 1, MAX_PRESENTATION_SAMPLES)
            motionSequence += 1L
        }
    }

    fun presentationSnapshot(): LongArray = synchronized(presentationLock) {
        snapshotRing(presentations, presentationCount, presentationWriteIndex)
    }

    fun presentationCadenceSnapshot(): LongArray = synchronized(presentationLock) {
        val start = if (presentationCount == MAX_PRESENTATION_SAMPLES) presentationWriteIndex else 0
        buildList {
            for (offset in 0 until presentationCount) {
                val source = (start + offset) % MAX_PRESENTATION_SAMPLES
                if (presentationVsyncIds[source] >= 0L && expectedPresentationTimes[source] > 0L) {
                    add(expectedPresentationTimes[source])
                }
            }
        }.toLongArray()
    }

    fun presentationEvidenceSnapshot(): LongArray = synchronized(presentationLock) {
        val output = LongArray(presentationCount * NativePresentationEvidencePacking.STRIDE)
        val start = if (presentationCount == MAX_PRESENTATION_SAMPLES) presentationWriteIndex else 0
        for (offset in 0 until presentationCount) {
            val source = (start + offset) % MAX_PRESENTATION_SAMPLES
            val target = offset * NativePresentationEvidencePacking.STRIDE
            output[target] = presentationRendererIds[source]
            output[target + 1] = presentationTokens[source]
            output[target + 2] = presentationGenerations[source]
            output[target + 3] = presentations[source]
            output[target + 4] = presentationLatencies[source]
            output[target + 5] = presentationScrollOffsets[source]
            output[target + 6] = presentationViewportHeights[source]
            output[target + 7] = presentationAnchorOrdinals[source]
            output[target + 8] = presentationAnchorOffsets[source]
            output[target + 9] = presentationFlags[source]
            output[target + 10] = presentationVsyncIds[source]
            output[target + 11] = expectedPresentationTimes[source]
            output[target + 12] = presentationSubmittedAt[source]
            output[target + 13] = presentationBufferIds[source]
            output[target + 14] = presentationGeometryRevisions[source]
            output[target + 15] = presentationInputRevisions[source]
            output[target + 16] = presentationScrollCauses[source]
        }
        output
    }

    fun presentationEvidenceSince(afterSequence: Long): ViewerPresentationBatch =
        synchronized(presentationLock) {
            val range = unreadRange(afterSequence, presentationSequence, presentationCount)
            val output = LongArray(range.count * NativePresentationEvidencePacking.STRIDE)
            repeat(range.count) { offset ->
                val source = ringIndex(range.firstSequence + offset)
                val target = offset * NativePresentationEvidencePacking.STRIDE
                output[target] = presentationRendererIds[source]
                output[target + 1] = presentationTokens[source]
                output[target + 2] = presentationGenerations[source]
                output[target + 3] = presentations[source]
                output[target + 4] = presentationLatencies[source]
                output[target + 5] = presentationScrollOffsets[source]
                output[target + 6] = presentationViewportHeights[source]
                output[target + 7] = presentationAnchorOrdinals[source]
                output[target + 8] = presentationAnchorOffsets[source]
                output[target + 9] = presentationFlags[source]
                output[target + 10] = presentationVsyncIds[source]
                output[target + 11] = expectedPresentationTimes[source]
                output[target + 12] = presentationSubmittedAt[source]
                output[target + 13] = presentationBufferIds[source]
                output[target + 14] = presentationGeometryRevisions[source]
                output[target + 15] = presentationInputRevisions[source]
                output[target + 16] = presentationScrollCauses[source]
            }
            ViewerPresentationBatch(presentationSequence, output, range.dropped)
        }

    fun renderSnapshot(): LongArray = synchronized(presentationLock) {
        val timestamps = snapshotRing(renderAt, renderCount, renderWriteIndex)
        val latencies = snapshotRing(renderLatency, renderCount, renderWriteIndex)
        LongArray(renderCount * 2).also { packed ->
            for (index in timestamps.indices) {
                packed[index * 2] = timestamps[index]
                packed[index * 2 + 1] = latencies[index]
            }
        }
    }

    fun motionFrameSnapshot(): LongArray = synchronized(motionLock) {
        val sequences = snapshotRing(motionSequences, motionCount, motionWriteIndex)
        val timestamps = snapshotRing(motionFrames, motionCount, motionWriteIndex)
        LongArray(motionCount * 2).also { packed ->
            for (index in timestamps.indices) {
                packed[index * 2] = sequences[index]
                packed[index * 2 + 1] = timestamps[index]
            }
        }
    }

    fun motionFramesSince(afterSequence: Long): ViewerMotionBatch = synchronized(motionLock) {
        val range = unreadRange(afterSequence, motionSequence, motionCount)
        val output = LongArray(range.count * 2)
        val appliedAt = LongArray(range.count)
        repeat(range.count) { offset ->
            val source = ringIndex(range.firstSequence + offset)
            output[offset * 2] = motionSequences[source]
            output[offset * 2 + 1] = motionFrames[source]
            appliedAt[offset] = motionAppliedAt[source]
        }
        ViewerMotionBatch(motionSequence, output, range.dropped, appliedAt)
    }

    fun gestureSnapshot(): List<LongRange> = synchronized(gestureLock) { gestureWindows.toList() }

    private fun recordRender(atNanos: Long, latencyNanos: Long) {
        renderAt[renderWriteIndex] = atNanos
        renderLatency[renderWriteIndex] = latencyNanos
        renderWriteIndex = (renderWriteIndex + 1) % MAX_PRESENTATION_SAMPLES
        renderCount = minOf(renderCount + 1, MAX_PRESENTATION_SAMPLES)
    }

    private fun snapshotRing(source: LongArray, count: Int, writeIndex: Int): LongArray {
        val start = if (count == source.size) writeIndex else 0
        return LongArray(count) { offset -> source[(start + offset) % source.size] }
    }

    private fun unreadRange(after: Long, newest: Long, residentCount: Int): UnreadRange {
        require(after >= 0L) { "Evidence cursor must not be negative" }
        val oldest = (newest - residentCount + 1L).coerceAtLeast(1L)
        val requested = (after + 1L).coerceAtMost(newest + 1L)
        val first = maxOf(requested, oldest)
        val count = (newest - first + 1L).coerceAtLeast(0L).toInt()
        return UnreadRange(first, count, requested < oldest)
    }

    private fun ringIndex(sequence: Long): Int = ((sequence - 1L) % MAX_PRESENTATION_SAMPLES).toInt()

    private data class UnreadRange(
        val firstSequence: Long,
        val count: Int,
        val dropped: Boolean,
    )

    private companion object {
        // A strict HOME + direction-change run lasts roughly forty seconds. Keep the complete
        // 60/90 Hz trace instead of silently overwriting its cold-entry gestures before export.
        const val MAX_PRESENTATION_SAMPLES = 8_192
        const val MAX_GESTURE_SAMPLES = 64
    }
}

internal data class ViewerPresentationBatch(
    val nextSequence: Long,
    val packed: LongArray,
    val dropped: Boolean,
)

internal data class ViewerMotionBatch(
    val nextSequence: Long,
    val packed: LongArray,
    val dropped: Boolean,
    val applicationTimestamps: LongArray,
)
