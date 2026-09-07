package ml.melun.mangaview.viewer.runtime

import kotlin.random.Random
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.session.SceneQuad
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.viewer.session.ViewerSession
import ml.melun.mangaview.viewer.session.VisualKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewerPresentationMapperTest {
    @Test
    fun repeatedCoveredRegionCannotHideAnUncoveredBottom() {
        assertFalse(coverage(listOf(0 to 60, 0 to 60)))
    }

    @Test
    fun shuffledOverlappingTilesMustCoverEveryViewportUnit() {
        val random = Random(601)
        repeat(500) {
            val ranges = List(random.nextInt(1, 20)) {
                val top = random.nextInt(-50, 150)
                top to top + random.nextInt(1, 100)
            }
            val expected = (0 until 100).all { y -> ranges.any { y >= it.first && y < it.second } }
            assertEquals(ranges.toString(), expected, coverage(ranges.shuffled(random)))
        }
    }

    private fun coverage(ranges: List<Pair<Int, Int>>): Boolean {
        val episode = EpisodeId(SeriesId(SourceId("fixture"), "coverage"), "episode")
        val page = PageId.at(episode, 0)
        val session = ViewerSession(Viewport(FixedPx.fromPixels(100), FixedPx.fromPixels(100)))
        session.savedPositionResolved(null)
        session.initialManifestResolved(EpisodeManifest(episode, "fixture",
            listOf(PageSpec(page, 0, PageDimensions(100, 200)))))
        val scene = SceneSnapshot(1L, 1L, 1L, 1L, 1L, 0L, FixedPx.ZERO, FixedPx.ZERO,
            FixedPx.fromPixels(200), ranges.mapIndexed { index, (top, bottom) ->
                SceneQuad(page, FixedPx.fromPixels(top), FixedPx.fromPixels(bottom - top),
                    0, 100, 100, VisualKey(index + 1L))
            })
        return requireNotNull(ViewerPresentationMapper().frameMetadata(session.state, scene)).fullVisualCoverage
    }
}
