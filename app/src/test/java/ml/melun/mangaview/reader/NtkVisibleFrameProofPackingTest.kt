package ml.melun.mangaview.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NtkVisibleFrameProofPackingTest {
    @Test
    fun onePassPackingPreservesSortedDistinctSubmissionIdentity() {
        val firstPageTwoIdentity = identity(displayIndex = 99, sourceIndex = 20)
        val alreadyCanonical = identity(displayIndex = 1, sourceIndex = 10)
        val packed = packNtkVisibleFrameProof(
            listOf(
                item(index = 2, identity = firstPageTwoIdentity),
                item(index = 1, identity = alreadyCanonical),
                item(index = 2, identity = identity(displayIndex = 2, sourceIndex = 999)),
            ),
            viewportHeight = 100,
        )

        assertArrayEquals(intArrayOf(1, 2), packed.pageIndexes)
        assertEquals(listOf(1, 2), packed.pageIdentities.map { it.displayPageIndex })
        assertSame(alreadyCanonical, packed.pageIdentities[0])
        assertEquals(20, packed.pageIdentities[1].sourcePageIndex)
        assertEquals(2, packed.pageIdentities[1].displayPageIndex)
    }

    @Test
    fun cardsAndOffscreenItemsKeepThePreviousProofSemantics() {
        val cardIdentity = identity(displayIndex = 3, sourceIndex = 30)
        val packed = packNtkVisibleFrameProof(
            listOf(
                item(index = 1, top = -20f, height = 10f),
                item(index = 2, top = 100f, height = 20f),
                item(index = 3, card = "episode", identity = cardIdentity),
                item(index = 4, top = 99f, height = 20f),
            ),
            viewportHeight = 100,
        )

        assertArrayEquals(intArrayOf(4), packed.pageIndexes)
        assertEquals(listOf(cardIdentity), packed.pageIdentities)
    }

    @Test
    fun exactVisibilityPredicateRejectsInvalidGeometryAndKeepsZeroHeightCrossing() {
        val packed = packNtkVisibleFrameProof(
            listOf(
                item(index = 1, top = Float.NaN, height = 20f),
                item(index = 2, top = -10f, height = 20f),
            ),
            viewportHeight = 0,
        )

        assertArrayEquals(intArrayOf(2), packed.pageIndexes)
    }

    @Test
    fun retainedBandOffsetPacksOnlyTheTranslatedPhysicalViewport() {
        val secondIdentity = identity(displayIndex = 2, sourceIndex = 20)
        val packed = packNtkVisibleFrameProof(
            listOf(
                item(index = 1, top = 0f, height = 100f),
                item(index = 2, top = 100f, height = 100f, identity = secondIdentity),
                item(index = 3, top = 200f, height = 100f),
            ),
            viewportHeight = 100,
            viewportTopOffset = 100f,
        )

        assertArrayEquals(intArrayOf(2), packed.pageIndexes)
        assertEquals(listOf(secondIdentity), packed.pageIdentities)
    }

    private fun item(
        index: Int,
        top: Float = 0f,
        height: Float = 20f,
        card: String? = null,
        identity: ReaderSurfaceView.CommittedPageIdentity? = null,
    ): NtkVisibleFrameProofItem = FakeItem(index, identity, card, top, height)

    private fun identity(
        displayIndex: Int,
        sourceIndex: Int,
    ) = ReaderSurfaceView.CommittedPageIdentity(
        displayPageIndex = displayIndex,
        normalizedEpisodePath = "/episode/a",
        sourcePageIndex = sourceIndex,
        canonicalAsset = "asset-$sourceIndex",
        manifestDigest = "digest",
        manifestPageCount = 100,
    )

    private data class FakeItem(
        override val index: Int,
        override val committedIdentity: ReaderSurfaceView.CommittedPageIdentity?,
        override val cardText: String?,
        override val top: Float,
        override val pageHeight: Float,
    ) : NtkVisibleFrameProofItem
}
