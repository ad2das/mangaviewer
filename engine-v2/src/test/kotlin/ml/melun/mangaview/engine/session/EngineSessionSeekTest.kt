package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SourceAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionSeekTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
    private val pages = (0 until 5).map { PageSpec(PageId.at(episode, it), it, dimensions = PageDimensions(1000, 2000)) }

    @Test
    fun seekMovesTheAnchorToThePageTopAndCountsAsAMovement() {
        val session = readySession()
        val before = session.snapshot

        val sought = session.dispatch(SessionEvent.SeekPage(PageId.at(episode, 3))).snapshot

        assertEquals(SourceAnchor(PageId.at(episode, 3), 0L, 0L), sought.anchor)
        assertEquals(PageId.at(episode, 3), sought.visibleRegions.first().pageId)
        assertTrue(sought.movementRevision > before.movementRevision)
    }

    @Test
    fun seekCancelsQueuedMovementSoItCannotUndoTheJump() {
        // The second page has no dimensions yet, so a long drag stays queued at its boundary.
        val gated = listOf(
            PageSpec(PageId.at(episode, 0), 0, dimensions = PageDimensions(1000, 2000)),
            PageSpec(PageId.at(episode, 1), 1),
        )
        val session = EngineSession(91L, episode, EngineViewport(1000, 1000), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        session.dispatch(SessionEvent.ManifestResolved(1L, EpisodeManifest(episode, "Seek", gated)))
        val drag = session.dispatch(SessionEvent.Input(InputSample(1L, 1L, 0L, 3_000L * 1024L)))
        assertEquals(1, drag.snapshot.pendingInputCount)

        val update = session.dispatch(SessionEvent.SeekPage(PageId.at(episode, 0)))

        assertEquals(0, update.snapshot.pendingInputCount)
        assertEquals(InputOutcome.CANCELLED, update.receipts.single().outcome)
        assertEquals(SourceAnchor(PageId.at(episode, 0), 0L, 0L), update.snapshot.anchor)
    }

    @Test
    fun seekIsIgnoredForUnknownPagesAndClosedSessions() {
        val session = readySession()
        val before = session.snapshot
        val other = EpisodeId(SeriesId(SourceId("test"), "series"), "other")

        val unknown = session.dispatch(SessionEvent.SeekPage(PageId.at(other, 0))).snapshot
        assertEquals(before.anchor, unknown.anchor)
        assertEquals(before.movementRevision, unknown.movementRevision)

        val closed = session.dispatch(SessionEvent.Close).snapshot
        val late = session.dispatch(SessionEvent.SeekPage(PageId.at(episode, 4))).snapshot
        assertEquals(closed, late)
    }

    private fun readySession(): EngineSession {
        val session = EngineSession(90L, episode, EngineViewport(1000, 1000), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        session.dispatch(SessionEvent.ManifestResolved(1L, EpisodeManifest(episode, "Seek", pages)))
        return session
    }
}
