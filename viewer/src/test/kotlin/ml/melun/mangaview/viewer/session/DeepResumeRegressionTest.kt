package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResumeRegressionTest {
    private val id = EpisodeId(SeriesId(SourceId("ntk"), "series"), "episode")
    private val page = PageId.at(id, 0)
    private val viewport = Viewport(px(1000), px(1000))

    @Test
    fun unknownHeightCannotClampOrDecodeTheWrongPartOfASavedPage() {
        val session = ViewerSession(viewport)
        session.savedPositionResolved(ReadingPosition(page, px(8000).units))
        session.initialManifestResolved(manifest())
        assertEquals(px(8000), session.state.scroll.contentOffset)
        assertEquals(page, session.positionForPersistence()?.pageId)
        val before = DemandEngine().snapshot(session.state).demands.first()
        assertEquals(page, before.pageId)
        assertNull(before.sourceRange)
        session.applyUserInput(px(500), 1000F)
        session.applyUserInput(px(-200), -1000F)
        session.enterBackground()
        session.enterForeground()
        session.resolvePageDimensions(page, PageDimensions(1000, 20000))
        assertEquals(px(8300), session.state.scroll.contentOffset)
        assertEquals(ReadingPosition(page, px(8300).units), session.positionForPersistence())
        val range = requireNotNull(DemandEngine().snapshot(session.state).demands.first().sourceRange)
        val firstRow = range.startQ32 * 20000 / SemanticViewportAnchor.Q32_ONE
        assertTrue(firstRow in 8299..8300)
    }

    @Test
    fun reversingBeforeManifestRetainsTheBoundaryEffectInOrder() {
        val session = ViewerSession(viewport)
        session.applyUserInput(px(-100), -100F)
        session.applyUserInput(px(100), 100F)
        session.savedPositionResolved(null)
        session.initialManifestResolved(manifest(PageDimensions(1000, 20000)))
        assertEquals(px(100), session.state.scroll.contentOffset)
    }

    @Test
    fun missingSavedPageFallsBackWithoutDiscardingOrderedUserInput() {
        val session = ViewerSession(viewport)
        session.applyUserInput(px(-100), -100F)
        session.applyUserInput(px(100), 100F)
        session.savedPositionResolved(ReadingPosition(PageId.at(id, 999), px(8000).units))
        session.initialManifestResolved(manifest(PageDimensions(1000, 20000)))
        assertEquals(ReadingPosition(page, px(100).units), session.positionForPersistence())
    }

    @Test
    fun unresolvedNextEpisodeDoesNotClampARealGestureToTwoScreens() {
        val session = ViewerSession(viewport)
        session.savedPositionResolved(null)
        session.initialManifestResolved(manifest(PageDimensions(1000, 2000)).copy(
            nextEpisodeId = id.copy(remoteKey = "next"),
        ))
        session.applyUserInput(px(10000), 10000F)
        assertEquals(px(10000), session.state.scroll.contentOffset)
        assertTrue(session.state.maximumScroll >= px(10000))
    }

    private fun manifest(dimensions: PageDimensions? = null) = EpisodeManifest(
        id, "Episode", listOf(PageSpec(page, 0, dimensions)),
    )

    private fun px(value: Int) = FixedPx.fromPixels(value)
}
