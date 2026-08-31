package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTelemetryPlannerTest {
    private val planner = ViewerTelemetryPlanner()

    @Test
    fun reportsExactVisibleCoverageAndManifestIdentity() {
        val dimensions = PageDimensions(1_080, 1_920)
        val opened = openedState(dimensions)
        val pageId = opened.pageOrder.single()
        val pixel = PixelRef(7L, dimensions, 1_000L)
        val resident = opened.replacePage(
            pageId,
            opened.pages.getValue(pageId).copy(pixel = pixel, isPresented = true),
        )

        val evidence = planner.snapshot(resident, 99L)

        assertEquals(listOf(resident.manifests.single()), evidence.manifests)
        assertEquals(pageId, evidence.anchor.pageId)
        assertEquals(0L, evidence.uncoveredViewportUnits)
        assertEquals(0L, evidence.visuallyUncoveredViewportUnits)
        assertEquals(0L, evidence.overlappingViewportUnits)
        assertEquals(evidence.viewportHeightUnits, evidence.visiblePages.single().coveredUnits)
        assertEquals(0L, evidence.visiblePages.single().overlappingUnits)
        assertTrue(evidence.visiblePages.single().presented)
    }

    @Test
    fun sparsePixelsExposeUncoveredViewportWithoutChangingPageIdentity() {
        val dimensions = PageDimensions(1_080, 1_920)
        val opened = openedState(dimensions)
        val pageId = opened.pageOrder.single()
        val half = PixelTileRef(
            handle = 8L,
            sourceTopPx = 0,
            sourceBottomPx = 960,
            displayHeightPx = 960,
            allocationBytes = 1_000L,
            displayWidthPx = 1_080,
        )
        val resident = opened.replacePage(
            pageId,
            opened.pages.getValue(pageId).copy(
                pixel = PixelRef(half.handle, dimensions, half.allocationBytes, listOf(half)),
            ),
        )

        val evidence = planner.snapshot(resident, 100L)

        assertEquals(pageId, evidence.visiblePages.single().pageId)
        assertEquals(evidence.viewportHeightUnits / 2L, evidence.uncoveredViewportUnits)
        assertEquals(0L, evidence.visuallyUncoveredViewportUnits)
        assertEquals(
            evidence.viewportHeightUnits / 2L,
            evidence.visiblePages.single().loadingUnits,
        )
        assertEquals(
            evidence.viewportHeightUnits,
            evidence.visiblePages.single().visualCoveredUnits,
        )
        assertEquals(0L, evidence.overlappingViewportUnits)
    }

    @Test
    fun sourceScalingCannotOverflowCoverageArithmetic() {
        val dimensions = PageDimensions(1, Int.MAX_VALUE)
        val opened = openedState(dimensions)
        val pageId = opened.pageOrder.single()
        val pixel = PixelRef(9L, dimensions, 1_000L)
        val resident = opened.replacePage(
            pageId,
            opened.pages.getValue(pageId).copy(pixel = pixel, isPresented = true),
        )

        val evidence = planner.snapshot(resident, 101L)

        assertEquals(evidence.viewportHeightUnits, evidence.visiblePages.single().coveredUnits)
        assertEquals(0L, evidence.uncoveredViewportUnits)
        assertEquals(0L, evidence.overlappingViewportUnits)
    }

    private fun openedState(dimensions: PageDimensions): ViewerState = requireNotNull(
        ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = ViewerFixtures.manifest(1, dimensions = { dimensions }),
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
            ),
        ),
    ).state
}
