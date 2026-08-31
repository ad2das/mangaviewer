package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelBandGridTest {
    @Test
    fun modestPageDecodesOnceWithoutChangingDisplayQuality() {
        val dimensions = PageDimensions(836, 1_200)
        val bands = PixelBandGrid().bandsIntersecting(dimensions, 0, dimensions.heightPx, 1_080)

        assertEquals(listOf(PixelBand(0, 1_200, 836)), bands)
    }

    @Test
    fun extremelyTallPageUsesStableBoundedBands() {
        val dimensions = PageDimensions(800, 500_000)
        val bands = PixelBandGrid().bandsIntersecting(dimensions, 0, dimensions.heightPx)

        assertEquals(PixelBand(0, 512, 800), bands.first())
        assertEquals(500_000, bands.last().sourceBottomPx)
        assertTrue(bands.zipWithNext().all { (left, right) ->
            left.sourceBottomPx == right.sourceTopPx
        })
        assertTrue(bands.all { it.sourceBottomPx - it.sourceTopPx <= 512 })
    }

    @Test
    fun defaultBandsNeverExceedFiveHundredTwelveDisplayRows() {
        val grid = PixelBandGrid()
        val dimensions = listOf(
            PageDimensions(320, 20_000),
            PageDimensions(800, 500_000),
            PageDimensions(1_440, 100_000),
            PageDimensions(2_000, 100_000),
            PageDimensions(8_000, 100_000),
        )

        dimensions.forEach { page ->
            val bands = grid.bandsIntersecting(page, 0, page.heightPx)
            assertEquals(0, bands.first().sourceTopPx)
            assertEquals(page.heightPx, bands.last().sourceBottomPx)
            assertTrue(bands.zipWithNext().all { (left, right) ->
                left.sourceBottomPx == right.sourceTopPx
            })
            assertTrue(bands.all { band ->
                val sourceRows = band.sourceBottomPx - band.sourceTopPx
                sourceRows.toLong() * band.displayWidthPx <=
                    512L * page.widthPx
            })
        }
    }

    @Test
    fun wideSourceAlsoHonorsTheScratchBudget() {
        val dimensions = PageDimensions(10_000, 500_000)
        val grid = PixelBandGrid()
        val rows = grid.sourceRowsPerBand(dimensions)

        assertTrue(rows.toLong() * dimensions.widthPx * 4L <= 32L * 1_024L * 1_024L)
        assertTrue(rows > 0)
    }

    @Test
    fun viewportWidthChangesQualityWithoutChangingSourceBoundaries() {
        val dimensions = PageDimensions(2_000, 20_000)
        val grid = PixelBandGrid()

        val narrow = grid.bandsIntersecting(dimensions, 0, dimensions.heightPx, 720)
        val wide = grid.bandsIntersecting(dimensions, 0, dimensions.heightPx, 1_440)

        assertEquals(narrow.map { it.sourceTopPx to it.sourceBottomPx }, wide.map { it.sourceTopPx to it.sourceBottomPx })
        assertTrue(narrow.all { it.displayWidthPx == 720 })
        assertTrue(wide.all { it.displayWidthPx == 1_440 })
    }
}
