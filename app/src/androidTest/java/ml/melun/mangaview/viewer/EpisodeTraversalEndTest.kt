package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeTraversalEndTest {
    private val episode = EpisodeId(SeriesId(SourceId("fixture"), "series"), "last-episode")
    private val lastPage = PageId.at(episode, 9)
    private val bottom = VisiblePageTelemetry(lastPage, 99, 100L, 100L, 0L, 100L, 0L, true,
        pageHeightUnits = 1_000L, visibleOffsetInPageUnits = 900L)

    @Test
    fun enteringFinalEpisodeOrLastPageIsNotFinishingIt() {
        assertFalse(reached(bottom.copy(pageId = PageId.at(episode, 0))))
        assertFalse(reached(bottom.copy(visibleOffsetInPageUnits = 0L)))
        assertFalse(reached(bottom.copy(visibleOffsetInPageUnits = 899L)))
        assertTrue(reached(bottom))
    }

    @Test
    fun missingPixelsUnknownGeometryAndOverlapCannotFinish() {
        assertFalse(reached(bottom.copy(coveredUnits = 99L)))
        assertFalse(reached(bottom.copy(loadingUnits = 1L)))
        assertFalse(reached(bottom.copy(overlappingUnits = 1L)))
        assertFalse(reached(bottom.copy(pageHeightUnits = 0L)))
        assertFalse(reached(bottom.copy(presented = false)))
        assertFalse(reached(bottom.copy(visibleOffsetInPageUnits = 1_000L, visibleUnits = 0L)))
    }

    private fun reached(page: VisiblePageTelemetry) = EpisodeTraversalEnd.reached(lastPage, listOf(page))
}
