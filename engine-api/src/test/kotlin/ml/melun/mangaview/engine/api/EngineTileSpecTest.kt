package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineTileSpecTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)

    @Test fun rasterCropMatchesFullImageResizeBeforeCropping() {
        val tile = EngineTileSpec(id, "1", "0".repeat(64), PageDimensions(101, 1000), 100, 300, 150)
        assertEquals(1486, tile.rasterHeight)
        assertEquals(148, tile.rasterTop)
        assertEquals(446, tile.rasterBottom)
        assertEquals(178800L, tile.byteCount)
    }

    @Test fun allocationArithmeticDoesNotOverflowAnIntermediateInt() {
        val tile = EngineTileSpec(id, "1", "0".repeat(64), PageDimensions(2_000_000, 2_000_000),
            0, 2_000_000, 2_000_000)
        assertEquals(16_000_000_000_000L, tile.byteCount)
    }

    @Test(expected = ArithmeticException::class)
    fun impossibleRasterHeightFailsBeforeNativeAllocation() {
        EngineTileSpec(id, "1", "0".repeat(64), PageDimensions(1, 2), 0, 2, 1_500_000_000).byteCount
    }
}
