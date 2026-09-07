package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LayoutLedger
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

class PixelAnchorRegressionTest {
    @Test
    fun aWidthChangeScalesThePixelByWidthNotByTheNewPageHeight() {
        val viewport = Viewport(px(1000), px(1000))
        val original = ledger(viewport, PageDimensions(1000, 1500))
        val anchor = requireNotNull(ScrollModel.anchor(CanonicalScroll(px(250)), viewport, original))
        val resolved = original.resolve(pageId, PageDimensions(1000, 6000)).reflow(px(2000))
        val restored = ScrollModel.restore(anchor, resolved, px(20_000), 0F)
        assertEquals(px(1000), restored.contentOffset)
    }

    @Test
    fun aLargerResolvedPageDoesNotMultiplyTheVisiblePixelOffset() {
        val viewport = Viewport(px(1000), px(1000))
        val original = ledger(viewport, PageDimensions(1000, 1500))
        val scroll = CanonicalScroll(px(250), 0F)
        val anchor = requireNotNull(ScrollModel.anchor(scroll, viewport, original))
        val resolved = original.resolve(pageId, PageDimensions(1000, 6000))
        val restored = ScrollModel.restore(anchor, resolved, px(20_000), 0F)
        assertEquals(px(250), restored.contentOffset)
    }

    @Test
    fun anUnchangedLayoutRoundTripCannotLoseAFractionalPixel() {
        val viewport = Viewport(px(1080), px(1800))
        val original = ledger(viewport, PageDimensions(1080, 6001))
        val scroll = CanonicalScroll(FixedPx(1234567), 12F)
        val anchor = requireNotNull(ScrollModel.anchor(scroll, viewport, original))
        assertEquals(scroll, ScrollModel.restore(anchor, original, px(20_000), 12F))
    }

    private fun ledger(viewport: Viewport, dimensions: PageDimensions) =
        LayoutLedger.create(listOf(PageSpec(pageId, 0, dimensions)), viewport.width)

    private fun px(value: Int) = FixedPx.fromPixels(value)

    private val pageId = PageId.at(
        EpisodeId(SeriesId(SourceId("fixture"), "series"), "episode"), 0,
    )
}
