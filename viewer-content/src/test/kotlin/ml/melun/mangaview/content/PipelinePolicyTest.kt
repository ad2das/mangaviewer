package ml.melun.mangaview.content

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.session.SemanticViewportAnchor
import ml.melun.mangaview.viewer.session.SourceRangeFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PipelinePolicyTest {
    @Test
    fun partialDemandsCoverEveryRequestedRowWithoutBandOverlap() {
        val random = Random(601)
        val unit = SemanticViewportAnchor.Q32_ONE
        repeat(500) {
            val height = random.nextInt(1, 30_000)
            val dimensions = PageDimensions(random.nextInt(100, 2_000), height)
            val start = random.nextLong(0, unit)
            val end = random.nextLong(start + 1L, unit + 1L)
            val requested = SourceRangeFraction(start, end)
            val residents = mutableListOf<TextureRef>()
            while (true) {
                val rows = nextDecodeRange(requested, dimensions, 1_080, residents) ?: break
                check(residents.size < height)
                residents += texture(residents.size + 1L, rows, height)
            }
            assertTrue(residents.first().sourceTopPx <= start * height / unit)
            assertTrue(residents.last().sourceBottomPx >= (end * height + unit - 1L) / unit)
            residents.zipWithNext().forEach { (left, right) ->
                assertEquals(left.sourceBottomPx, right.sourceTopPx)
            }
        }
    }

    @Test
    fun sixThousandAndOneSourceRowsHaveNoOverlappingPixelOwnership() {
        val dimensions = PageDimensions(1_080, 6_001)
        val requested = SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE)
        val residents = mutableListOf<TextureRef>()
        repeat(3) { index ->
            val range = requireNotNull(nextDecodeRange(requested, dimensions, 1_080, residents))
            residents += texture(index + 1L, range, dimensions.heightPx)
        }
        assertEquals(0, residents.first().sourceTopPx)
        assertEquals(6_001, residents.last().sourceBottomPx)
        residents.zipWithNext().forEach { (left, right) ->
            assertEquals(left.sourceBottomPx, right.sourceTopPx)
        }
    }

    @Test
    fun longForwardRangeIsFilledByBoundedNonOverlappingBands() {
        val dimensions = PageDimensions(1_080, 10_000)
        val requested = SourceRangeFraction(0L, SemanticViewportAnchor.Q32_ONE)
        val residents = mutableListOf<TextureRef>()
        val ranges = mutableListOf<SourceRowRange>()

        repeat(5) { index ->
            val range = requireNotNull(nextDecodeRange(requested, dimensions, 1_080, residents))
            ranges += range
            residents += texture(index + 1L, range, dimensions.heightPx)
        }

        assertNull(nextDecodeRange(requested, dimensions, 1_080, residents))
        assertEquals(0, ranges.first().top)
        assertEquals(dimensions.heightPx, ranges.last().bottomExclusive)
        ranges.zipWithNext().forEach { (left, right) -> assertEquals(left.bottomExclusive, right.top) }
        residents.forEach { texture ->
            assertTrue(texture.sourceBottomPx - texture.sourceTopPx <= 2_001)
        }
    }

    private fun texture(key: Long, range: SourceRowRange, height: Int): TextureRef {
        val top = range.top
        val bottom = range.bottomExclusive
        return TextureRef(
            PageId.at(EpisodeId(SeriesId(SourceId("ntk"), "series"), "episode"), 0),
            1L,
            key,
            top,
            bottom,
            height,
            (bottom - top) * 1_080L * 4L,
        )
    }
}
