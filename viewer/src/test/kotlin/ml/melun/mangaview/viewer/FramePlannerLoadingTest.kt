package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlannerLoadingTest {
    @Test
    fun unresolvedPagesKeepTheirExactGeometryAsScrollableLoadingPlacements() {
        val manifest = ViewerFixtures.manifest(4) { PageDimensions(1_000, 1_000) }
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        ))

        val frame = FramePlanner().plan(opened.state)

        assertEquals(frame.pages.map { it.ordinal }, frame.loading.map { it.ordinal })
        frame.pages.zip(frame.loading).forEach { (page, loading) ->
            assertEquals(page.top, loading.top)
            assertEquals(page.height, loading.height)
        }
    }

    @Test
    fun partialResidentPageLeavesOnlyItsMissingSourceIntervalAsLoadingGeometry() {
        val dimensions = PageDimensions(1_000, 4_000)
        val manifest = ViewerFixtures.manifest(1) { dimensions }
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        ))
        val pageId = manifest.pages.single().id
        val tile = PixelTileRef(9L, 0, 2_000, 2_000, allocationBytes = 8_000_000L)
        val runtime = opened.state.pages.getValue(pageId).copy(
            pixel = PixelRef(tile.handle, dimensions, tile.allocationBytes, listOf(tile)),
        )

        val frame = FramePlanner().plan(opened.state.replacePage(pageId, runtime))

        val loading = frame.loading.single()
        assertEquals(2_000, loading.sourceTopPx)
        assertEquals(4_000, loading.sourceBottomPx)
        assertEquals(4_000, loading.sourceHeightPx)
        assertTrue(loading.top > frame.pages.single().top)
    }
}
