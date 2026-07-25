package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPageAtomicityRemovedTest {
    @Test
    fun first512SourceRowsContributeBeforeWholePageIsReady() {
        val episode = NtkEpisodeToken(9)
        val geometry = NtkStripGeometry.create(
            episode,
            viewportWidthPx = 1000,
            assets = listOf(NtkPageAsset(0, "https://cdn/p0.jpg", 1000, 5000))
        )
        assertEquals(3, geometry.pages.single().tiles.size)

        val firstTile = geometry.pages.single().tiles.first()
        val resident = NtkStripIntervalSet()
        resident.add(firstTile.contentTopPx, firstTile.contentBottomPx)

        assertEquals(firstTile.contentBottomPx, resident.continuousEndFrom(0))
        assertTrue(firstTile.contentBottomPx < geometry.contentHeightPx)
        assertEquals(0L, resident.coveredLength(firstTile.contentBottomPx, geometry.contentHeightPx))
    }

    @Test
    fun adjacentProjectedTileBoundariesHaveNoRoundingGap() {
        val geometry = NtkStripGeometry.create(
            NtkEpisodeToken(1),
            viewportWidthPx = 997,
            assets = listOf(NtkPageAsset(0, "a", 1080, 4097))
        )
        geometry.pages.single().tiles.zipWithNext().forEach { (left, right) ->
            assertEquals(left.contentBottomPx, right.contentTopPx)
        }
    }
}
