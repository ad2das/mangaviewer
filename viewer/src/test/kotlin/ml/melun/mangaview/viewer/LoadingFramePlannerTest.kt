package ml.melun.mangaview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingFramePlannerTest {
    @Test
    fun loadingGeometryFillsViewportAndCarriesExactInputCoordinate() {
        val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_000))
        val planner = LoadingFramePlanner(screenfulCount = 8)
        val geometry = planner.geometry(viewport)
        val offset = FixedPx.fromPixels(937.25)

        val frame = planner.plan(geometry, offset)

        assertEquals(offset, frame.scrollOffset)
        assertEquals(viewport, frame.viewport)
        val ordered = frame.loading.sortedBy { it.top }
        var coveredThrough = offset
        ordered.forEach { placement ->
            if (placement.top <= coveredThrough && placement.top + placement.height > coveredThrough) {
                coveredThrough = placement.top + placement.height
            }
        }
        assertTrue(coveredThrough >= offset + viewport.height)
        assertTrue(frame.pages.isEmpty())
    }

    @Test
    fun loadingCoordinateUsesRealBoundsWithoutOverscroll() {
        val viewport = Viewport(FixedPx.fromPixels(800), FixedPx.fromPixels(1_200))
        val planner = LoadingFramePlanner(screenfulCount = 4)
        val geometry = planner.geometry(viewport)

        assertEquals(FixedPx.ZERO, planner.plan(geometry, FixedPx.fromPixels(-300)).scrollOffset)
        assertEquals(
            geometry.maximumOffset,
            planner.plan(geometry, FixedPx(Long.MAX_VALUE)).scrollOffset,
        )
    }
}
