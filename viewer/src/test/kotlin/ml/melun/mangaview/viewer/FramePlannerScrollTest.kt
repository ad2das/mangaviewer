package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FramePlannerScrollTest {
    @Test
    fun reusesImmutablePlacementsWhileNativeHeadroomStillCoversTheViewport() {
        val reducer = ViewerFixtures.reducer()
        val manifest = ViewerFixtures.manifest(30) { PageDimensions(1_080, 1_920) }
        var state = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        state = requireNotNull(reducer.reduce(
            state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(100), 1_000L, 2L),
        )).state
        val planner = FramePlanner(overscanScreenfuls = 2)
        val installed = planner.plan(state)

        state = requireNotNull(reducer.reduce(
            state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(100), 1_000L, 3L),
        )).state
        val reused = planner.planScroll(state, installed)

        assertSame(installed.pages, reused.pages)
        assertSame(installed.loading, reused.loading)
        assertEquals(state.scroll.contentOffset, reused.scrollOffset)
    }

    @Test
    fun rebuildsPlacementsBeforeTheViewportCanLeaveInstalledHeadroom() {
        val reducer = ViewerFixtures.reducer()
        val manifest = ViewerFixtures.manifest(30) { PageDimensions(1_080, 1_920) }
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val planner = FramePlanner(overscanScreenfuls = 2)
        val installed = planner.plan(opened)
        val moved = requireNotNull(reducer.reduce(
            opened,
            ViewerEvent.UserScroll(FixedPx.fromPixels(2_000), 10_000L, 2L),
        )).state

        val rebuilt = planner.planScroll(moved, installed)

        assertNotSame(installed.pages, rebuilt.pages)
        assertEquals(moved.scroll.contentOffset, rebuilt.scrollOffset)
    }
}
