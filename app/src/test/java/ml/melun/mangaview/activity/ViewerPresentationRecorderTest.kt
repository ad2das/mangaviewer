package ml.melun.mangaview.activity

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPresentationRecorderTest {
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
        val recorder = ViewerPresentationRecorder()
        recorder.recordMotionFrame(1L, 100L)
        val first = recorder.motionFramesSince(0L)
        recorder.recordMotionFrame(2L, 200L)
        val second = recorder.motionFramesSince(first.nextSequence)

        assertEquals(listOf(1L, 100L), first.packed.toList())
        assertEquals(listOf(2L, 200L), second.packed.toList())
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
    )
}
