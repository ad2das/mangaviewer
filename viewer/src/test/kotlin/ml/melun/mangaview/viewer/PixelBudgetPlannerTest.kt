package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PixelBudgetPlannerTest {
    @Test
    fun trimsDistantPixelsWithoutEvictingTheVisiblePage() {
        val opened = requireNotNull(
            ViewerFixtures.reducer().reduce(
                null,
                ViewerEvent.OpenEpisode(
                    generation = 1L,
                    manifest = ViewerFixtures.manifest(pageCount = 20),
                    viewport = ViewerFixtures.viewport,
                    atNanos = 1L,
                ),
            ),
        ).state
        val pixels = opened.pageOrder.mapIndexed { index, pageId ->
            pageId to PixelRef(
                handle = index + 1L,
                dimensions = PageDimensions(1_000, 1_500),
                allocationBytes = 8L * 1_024L * 1_024L,
            )
        }.toMap()
        val resident = opened.replacePages(
            opened.pages.mapValues { (pageId, runtime) ->
                runtime.copy(
                    residency = PageResidency.RESIDENT,
                    pixel = pixels.getValue(pageId),
                )
            },
        )

        val result = PixelBudgetPlanner(
            memoryPolicy = PixelMemoryPolicy(
                maximumResidentBytes = 24L * 1_024L * 1_024L,
                warmAdmissionBytes = 20L * 1_024L * 1_024L,
            ),
            retainedScreenfulsBehind = 0,
            retainedScreenfulsAhead = 1,
        ).trim(resident)

        val visible = resident.layout.indicesIntersecting(
            resident.scroll.contentOffset,
            FixedPx(resident.scroll.contentOffset.units + resident.viewport.height.units),
        )
        visible.forEach { assertNotNull(result.state.pages.getValue(resident.pageOrder[it]).pixel) }
        assertEquals(
            result.commands.size,
            result.state.pages.values.count { it.pixel == null },
        )
        assertNull(result.state.pages.getValue(resident.pageOrder.last()).pixel)
    }
}
