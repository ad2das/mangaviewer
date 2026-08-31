package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.DemandPlanner
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlanner
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.ScrollController
import ml.melun.mangaview.viewer.ViewerEvent
import ml.melun.mangaview.viewer.ViewerReducer
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.WorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreManifestLoadingControllerTest {
    private val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_000))

    @Test
    fun realInputChangesTheNextSurfacePlanSynchronously() {
        val controller = PreManifestLoadingController(viewport, LoadingFramePlanner(8))
        val before = controller.frame()

        assertTrue(controller.scrollBy(FixedPx.fromPixels(411.5)))
        val after = controller.frame()

        assertEquals(FixedPx.fromPixels(411.5), after.scrollOffset)
        assertTrue(after.scrollOffset > before.scrollOffset)
        assertTrue(controller.hasDisplacedInput)
    }

    @Test
    fun reverseInputAtTheTopDoesNotCreateLatentDrift() {
        val controller = PreManifestLoadingController(viewport, LoadingFramePlanner(8))

        assertFalse(controller.scrollBy(FixedPx.fromPixels(-600)))
        assertTrue(controller.scrollBy(FixedPx.fromPixels(100)))

        assertEquals(FixedPx.fromPixels(100), controller.offset)
    }

    @Test
    fun manifestOpenStartsAtTheExactVisibleLoadingCoordinate() {
        val controller = PreManifestLoadingController(viewport, LoadingFramePlanner(8))
        controller.scrollBy(FixedPx.fromPixels(733.25))
        val lastLoadingFrame = controller.frame()
        val reducer = ViewerReducer(ScrollController(), WorkScheduler(DemandPlanner()))

        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = manifest(),
                viewport = viewport,
                atNanos = 1L,
                initialScroll = controller.offset,
            ),
        ))
        val firstContentFrame = FramePlanner().plan(opened.state)

        assertEquals(lastLoadingFrame.scrollOffset, firstContentFrame.scrollOffset)
        assertEquals(1L, opened.state.userInputRevision)
    }

    private fun manifest(): EpisodeManifest {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_080, 2_000)
        val pages = List(4) { index ->
            PageSpec(PageId.at(episode, index), index, dimensions)
        }
        return EpisodeManifest(episode, "Episode", pages)
    }
}
