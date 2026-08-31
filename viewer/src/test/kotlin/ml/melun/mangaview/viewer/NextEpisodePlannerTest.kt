package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePlannerTest {
    private val planner = NextEpisodePlanner()
    private val reducer = ViewerFixtures.reducer()

    @Test
    fun preparesWhenForwardVelocityReachesTheTwelveSecondRunway() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
        )).state
        val velocity = opened.layout.totalHeight.units / 10L
        val ready = opened.withFirstPixel()

        assertTrue(planner.shouldPrepare(ready.copy(velocityUnitsPerSecond = velocity)))
        assertFalse(planner.shouldPrepare(ready.copy(velocityUnitsPerSecond = -velocity)))
    }

    @Test
    fun adjacentRunwayStartsBeforePixelsSoAnImmediateFlingCannotReachAPlaceholder() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(3L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
        )).state

        assertTrue(planner.shouldPrepare(opened))
        assertTrue(planner.shouldPrepare(opened.copy(
            velocityUnitsPerSecond = opened.layout.totalHeight.units,
        )))
    }

    @Test
    fun shortEpisodePreparesAtRestAfterFirstPixel() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(4L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state

        assertTrue(planner.shouldPrepare(opened.withFirstPixel()))
    }

    @Test
    fun preparesAtRestAfterEveryOriginalIsVerified() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(2L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state
        val verified = opened.replacePages(
            opened.pages.mapValues { (_, page) ->
                page.copy(encoded = VerifiedPageRef("cache", 1L, "sha"))
            },
        )

        assertTrue(planner.shouldPrepare(verified.withFirstPixel())
        )
    }

    private fun ViewerState.withFirstPixel(): ViewerState {
        val pageId = pageOrder.first()
        val dimensions = PageDimensions(1_080, 1_920)
        return replacePage(
            pageId,
            pages.getValue(pageId).copy(
                pixel = PixelRef(1L, dimensions, 1_080L * 1_920L * 4L),
                isPresented = true,
            ),
        )
    }
}
