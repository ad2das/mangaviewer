package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineTileSpecTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)

    @Test fun tileIdentityRequiresExactly64LowercaseAsciiHexCharacters() {
        fun tile(hash: String) = EngineTileSpec(id, "1", hash, PageDimensions(100, 100), 0, 100, 100)
        val valid = "0123456789abcdef".repeat(4)
        assertEquals(valid, tile(valid).sha256)
        for (invalid in listOf(valid.dropLast(1), valid + "0", valid.uppercase(),
            valid.dropLast(1) + "g", valid.dropLast(1) + "\u0660", valid.dropLast(1) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { tile(invalid) }
        }
    }

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
