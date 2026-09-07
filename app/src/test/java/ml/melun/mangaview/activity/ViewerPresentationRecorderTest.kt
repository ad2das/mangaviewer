package ml.melun.mangaview.activity

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPresentationRecorderTest {
    @Test
    fun terminalMissingTimestampIsPreservedAsFailureEvidence() {
        val recorder = ViewerPresentationRecorder()
        val sample = evidence(true).copy(presentedNanos = 0L,
            timestampKind = PresentationTimestampKind.UNAVAILABLE)
        assertEquals(false, recorder.recordPresentation(sample))
        assertEquals(sample, NativePresentationEvidencePacking.decode(
            recorder.presentationEvidenceSnapshot(),
        ).single())
    }

    @Test
    fun fallbackTimingIsRetainedButNeverReportsAFirstDisplayedImage() {
        PresentationTimestampKind.entries.filter { it != PresentationTimestampKind.DISPLAY_PRESENT }
            .forEach { kind ->
                val recorder = ViewerPresentationRecorder()
                recorder.beginUiEpoch()
                val sample = evidence(true).copy(timestampKind = kind)
                assertEquals(false, recorder.recordPresentation(sample))
                assertEquals(sample, NativePresentationEvidencePacking.decode(
                    recorder.presentationEvidenceSnapshot(),
                ).single())
            }
    }

    @Test
    fun boundedEvidenceSnapshotPreservesTokenSemanticsAndCoverage() {
        val recorder = ViewerPresentationRecorder()
        recorder.beginUiEpoch()
        val evidence = evidence(readable = true)

        assertTrue(recorder.recordPresentation(evidence))
        val decoded = NativePresentationEvidencePacking.decode(
            recorder.presentationEvidenceSnapshot(),
        ).single()

        assertEquals(evidence, decoded)
        assertEquals(false, recorder.recordPresentation(evidence.copy(token = 10L)))
    }

    @Test
    fun incrementalBatchesReturnOnlyEvidenceAfterTheCursor() {
        val recorder = ViewerPresentationRecorder()
        recorder.recordPresentation(evidence(readable = true))

        val first = recorder.presentationEvidenceSince(0L)
        recorder.recordPresentation(
            evidence(readable = true).copy(token = 10L, presentedNanos = 200L),
        )
        val second = recorder.presentationEvidenceSince(first.nextSequence)

        assertEquals(listOf(100L), decodeTimes(first.packed))
        assertEquals(listOf(200L), decodeTimes(second.packed))
        assertEquals(false, first.dropped)
        assertEquals(false, second.dropped)
    }

    @Test
    fun incrementalMotionBatchesUseAnIndependentCursor() {
        var callbackTime = 150L
        val recorder = ViewerPresentationRecorder { callbackTime }
        recorder.recordMotionFrame(1L, 100L)
        val first = recorder.motionFramesSince(0L)
        callbackTime = 450L
        recorder.recordMotionFrame(2L, 200L)
        val second = recorder.motionFramesSince(first.nextSequence)

        assertEquals(listOf(1L, 100L), first.packed.toList())
        assertEquals(listOf(2L, 200L), second.packed.toList())
        assertEquals(listOf(150L), first.applicationTimestamps.toList())
        assertEquals(listOf(450L), second.applicationTimestamps.toList())
    }

    @Test
    fun wrappedMotionEvidenceKeepsActualApplicationTimesAlignedWithVsyncSamples() {
        var callbackTime = 0L
        val recorder = ViewerPresentationRecorder { callbackTime }
        repeat(8_200) { index ->
            callbackTime = (index + 1L) * 100L + 37L
            recorder.recordMotionFrame(index + 1L, (index + 1L) * 100L)
        }

        val batch = recorder.motionFramesSince(0L)
        assertTrue(batch.dropped)
        assertEquals(8_192, batch.applicationTimestamps.size)
        batch.applicationTimestamps.forEachIndexed { index, applied ->
            assertEquals(batch.packed[index * 2 + 1] + 37L, applied)
        }
        val unread = recorder.motionFramesSince(batch.nextSequence)
        assertTrue(unread.packed.isEmpty())
        assertTrue(unread.applicationTimestamps.isEmpty())
    }

    private fun decodeTimes(packed: LongArray): List<Long> =
        NativePresentationEvidencePacking.decode(packed).map { it.presentedNanos }

    private fun evidence(readable: Boolean) = NativePresentationEvidence(
        rendererIdentity = 8L,
        token = 9L,
        generation = 3L,
        presentedNanos = 100L,
        renderLatencyNanos = 4L,
        scrollOffsetUnits = 500L,
        viewportHeightUnits = 2_000L,
        anchorOrdinal = 7,
        anchorOffsetUnits = 25L,
        readableActualContent = readable,
        fullVisualCoverage = true,
        fullActualCoverage = false,
        timestampKind = PresentationTimestampKind.DISPLAY_PRESENT,
    )
}
