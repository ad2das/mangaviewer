package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenshotSceneEvidenceTest {
    @Test fun reusedTokenCannotBindAnotherRendererGenerationBufferOrSubmission() {
        val matching = region()
        assertTrue(ViewerScreenshotSceneEvidence.sameSubmission(frame(), matching))
        listOf(matching.copy(rendererIdentity = 2), matching.copy(generation = 2),
            matching.copy(token = 8), matching.copy(bufferFrameId = 10),
            matching.copy(renderLatencyNanos = 21), matching.copy(presentedNanos = 131),
            matching.copy(timestampKind = PresentationTimestampKind.DISPLAY_PRESENT),
            matching.copy(submittedAtNanos = 101), matching.copy(geometryRevision = 4),
            matching.copy(userInputRevision = 5)).forEach {
            assertFalse(ViewerScreenshotSceneEvidence.sameSubmission(frame(), it))
        }
    }

    @Test fun candidatePreservesExactEpisodeRowsWithoutPromotingLatchEvidence() {
        val candidate = ViewerScreenshotSceneEvidence.candidates(listOf(frame()), listOf(region()))
            .getJSONObject(0)
        assertEquals("COMPOSITION_LATCH", candidate.getString("timestampKind"))
        val page = candidate.getJSONArray("regions").getJSONObject(0)
        assertEquals("fixture", page.getString("sourceId"))
        assertEquals("series", page.getString("seriesKey"))
        assertEquals("episode", page.getString("episodeKey"))
        assertEquals("p0000", page.getString("pageKey"))
        assertEquals(0, page.getInt("sourceTopRow"))
        assertEquals(120, page.getInt("sourceBottomRowExclusive"))
        assertFalse(candidate.has("passed"))
    }

    @Test fun terminalArrivalOrderDoesNotReplaceLatestSubmittedCandidateWithAnOlderFrame() {
        val frames = listOf(frame().copy(token = 8, submittedAtNanos = 200), frame())
        val candidates = ViewerScreenshotSceneEvidence.candidates(frames, listOf(region()))
        assertEquals(8L, candidates.getJSONObject(0).getLong("token"))
        assertEquals(0, candidates.getJSONObject(0).getJSONArray("regions").length())
        assertEquals(1, candidates.getJSONObject(1).getJSONArray("regions").length())
    }

    private fun frame() = NativePresentationEvidence(1, 7, 1, 130,
        submittedAtNanos = 100, renderLatencyNanos = 20, scrollOffsetUnits = 0,
        viewportHeightUnits = 240, anchorOrdinal = 0, anchorOffsetUnits = 0,
        readableActualContent = true, fullVisualCoverage = true, fullActualCoverage = true,
        timestampKind = PresentationTimestampKind.COMPOSITION_LATCH, bufferFrameId = 9,
        geometryRevision = 3, userInputRevision = 4)

    private fun region() = PresentedImageRegion(1, 7, 1,
        PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "series"), "episode"), 0),
        0, 120, 300, 130, PresentationTimestampKind.COMPOSITION_LATCH, true,
        bufferFrameId = 9, submittedAtNanos = 100, renderLatencyNanos = 20,
        geometryRevision = 3, userInputRevision = 4)
}
