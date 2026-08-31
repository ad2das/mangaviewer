package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.PagePlacement
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSubmissionLedgerTest {
    @Test
    fun olderGenerationOnCompleteRemainsObservableAfterNewGenerationSubmission() {
        val ledger = NativeSubmissionLedger()
        val loading = plan().copy(generation = 11L)
        val content = plan().copy(generation = 12L)
        ledger.record(1L, loading, false, true, false)
        ledger.finish(1L)
        ledger.record(2L, content, true, true, true)
        ledger.finish(2L)

        assertEquals(11L, ledger.complete(1L, 7L, 100L)?.generation)
        assertEquals(12L, ledger.complete(2L, 7L, 200L)?.generation)
    }

    @Test
    fun completionRetainsExactSemanticAndCoverageEvidence() {
        val ledger = NativeSubmissionLedger(capacity = 4)
        ledger.record(3L, plan(), true, true, false)
        ledger.finish(3L)

        val completed = requireNotNull(ledger.complete(3L, 5L, 99L))

        assertEquals(7L, completed.generation)
        assertEquals(FixedPx.fromPixels(1_200).units, completed.scrollOffsetUnits)
        assertEquals(11, completed.anchorOrdinal)
        assertEquals(FixedPx.fromPixels(200).units, completed.anchorOffsetUnits)
        assertTrue(completed.readableActualContent)
        assertTrue(completed.fullVisualCoverage)
        assertEquals(false, completed.fullActualCoverage)
        assertTrue(completed.renderLatencyNanos >= 0L)
        assertEquals(5L, completed.rendererIdentity)
        assertEquals(99L, completed.presentedNanos)
    }

    @Test
    fun staleOrOverwrittenTokenCannotBecomePresentationEvidence() {
        val ledger = NativeSubmissionLedger(capacity = 2)
        ledger.record(1L, plan(), true, true, true)
        ledger.record(3L, plan(), false, false, false)

        assertNull(ledger.complete(1L, 1L, 1L))
        assertEquals(false, requireNotNull(ledger.complete(3L, 1L, 2L)).readableActualContent)
        assertNull(ledger.complete(3L, 1L, 3L))
    }

    private fun plan(): FramePlan {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        return FramePlan(
            generation = 7L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(2_000)),
            scrollOffset = FixedPx.fromPixels(1_200),
            contentHeight = FixedPx.fromPixels(8_000),
            pages = listOf(PagePlacement(
                ordinal = 11,
                pageId = PageId.at(episode, 11),
                top = FixedPx.fromPixels(1_000),
                height = FixedPx.fromPixels(3_000),
                pixel = null,
            )),
        )
    }
}
