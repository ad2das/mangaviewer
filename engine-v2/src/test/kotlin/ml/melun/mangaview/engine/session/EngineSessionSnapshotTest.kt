package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SourceAnchor
import org.junit.Assert.*
import org.junit.Test

class EngineSessionSnapshotTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")

    @Test fun unknownDimensionsBelongOnlyToAcceptedManifestPagesAndDoNotSurviveNavigation() {
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 100L }
        val page = PageId.at(episode, 0)
        session.dispatch(SessionEvent.PositionResolved(1, null))
        session.dispatch(SessionEvent.ManifestResolved(1,
            EpisodeManifest(episode, "episode", listOf(PageSpec(page, 0)))))
        val size = PageDimensions(100, 200)
        assertThrows(IllegalArgumentException::class.java) {
            session.dispatch(SessionEvent.DimensionsResolved(1, PageId.at(episode, 1), size))
        }
        assertEquals(setOf(page), session.snapshot.requiredDimensions)
        session.dispatch(SessionEvent.DimensionsResolved(1, page, size))
        assertEquals(size, session.snapshot.anchorDimensions)
        val next = EpisodeId(episode.seriesId, "next")
        session.dispatch(SessionEvent.Navigate(next))
        assertThrows(IllegalArgumentException::class.java) {
            session.dispatch(SessionEvent.DimensionsResolved(2, page, size))
        }
        val nextPage = PageId.at(next, 0)
        session.dispatch(SessionEvent.ManifestResolved(2,
            EpisodeManifest(next, "next", listOf(PageSpec(nextPage, 0)))))
        assertNull(session.snapshot.anchorDimensions)
        assertEquals(setOf(nextPage), session.snapshot.requiredDimensions)
        session.dispatch(SessionEvent.DimensionsResolved(2, nextPage, size))
        assertEquals(nextPage, session.snapshot.anchor!!.pageId)
        assertEquals(size, session.snapshot.anchorDimensions)
    }

    @Test fun readersShareCompletedStateWhileGeometryInputAndLifecyclePublishFreshImmutableStates() {
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 100L }
        val waiting = session.dispatch(SessionEvent.Input(InputSample(1, 1, 0, 25 * 1024L))).snapshot
        repeat(100) { assertSame(waiting, session.snapshot) }
        assertEquals(1, waiting.pendingInputCount)
        session.dispatch(SessionEvent.PositionResolved(1, null))
        val page = PageId.at(episode, 0)
        val unknown = session.dispatch(SessionEvent.ManifestResolved(1,
            EpisodeManifest(episode, "episode", listOf(PageSpec(page, 0))))).snapshot
        assertEquals(setOf(page), unknown.requiredDimensions)
        assertSame(unknown, session.snapshot)
        val ready = session.dispatch(SessionEvent.DimensionsResolved(1, page, PageDimensions(100, 1000)))
        assertSame(ready.snapshot, session.snapshot)
        assertEquals(InputOutcome.APPLIED, ready.receipts.single().outcome)
        assertEquals(25 * SourceAnchor.SOURCE_UNITS_PER_PIXEL, ready.snapshot.anchor!!.sourceYQ32)
        assertEquals(0, ready.snapshot.pendingInputCount)
        assertTrue(ready.snapshot.completeViewport)
        assertEquals(setOf(page), unknown.requiredDimensions)
        assertEquals(1, waiting.pendingInputCount)
        repeat(100) { index ->
            val update = session.dispatch(SessionEvent.Input(InputSample(index + 2L, 1, 0, 1L)))
            repeat(5) { assertSame(update.snapshot, session.snapshot) }
            assertEquals(InputOutcome.APPLIED, update.receipts.single().outcome)
        }
        val moved = session.snapshot
        assertEquals((25 * 1024L + 100) * SourceAnchor.SOURCE_UNITS_PER_PIXEL / 1024,
            moved.anchor!!.sourceYQ32)
        val resized = session.dispatch(SessionEvent.Resize(EngineViewport(200, 100))).snapshot
        assertSame(resized, session.snapshot)
        assertEquals(moved.anchor, resized.anchor)
        assertEquals(EngineViewport(100, 100), moved.viewport)
        val next = EpisodeId(episode.seriesId, "next")
        val navigated = session.dispatch(SessionEvent.Navigate(next)).snapshot
        assertSame(navigated, session.snapshot)
        assertEquals(2L, navigated.generation)
        assertNull(navigated.anchor)
        assertEquals(setOf(next), navigated.requiredEpisodes)
        val closed = session.dispatch(SessionEvent.Close).snapshot
        assertSame(closed, session.snapshot)
        assertEquals(EngineSessionPhase.CLOSED, closed.phase)
        assertTrue(closed.visibleRegions.isEmpty())
        assertTrue(closed.requiredEpisodes.isEmpty())
        assertEquals(setOf(next), navigated.requiredEpisodes)
    }
}
