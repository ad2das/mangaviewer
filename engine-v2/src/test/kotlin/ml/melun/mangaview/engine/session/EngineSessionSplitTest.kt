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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionSplitTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
    private val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL

    @Test
    fun enablingSplitDoublesTheSpreadDocumentWithoutMovingTheAnchor() {
        val page = PageId.at(episode, 0)
        val session = readySession(
            viewport = EngineViewport(1080, 4000),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(1600, 1200))),
        )
        val before = session.snapshot
        assertEquals(1200L * q, before.visibleRegions.single().sourceBottomQ32)
        assertEquals(EngineSessionPhase.ACTIVE, before.phase)

        val enabled = session.dispatch(SessionEvent.SetSplitMode(true))

        assertTrue(enabled.snapshot.splitMode)
        assertEquals(before.anchor, enabled.snapshot.anchor)
        assertEquals(2400L * q, enabled.snapshot.visibleRegions.single().sourceBottomQ32)
        assertTrue(enabled.snapshot.geometryRevision > before.geometryRevision)
        assertEquals(enabled.snapshot, session.dispatch(SessionEvent.SetSplitMode(true)).snapshot)
        assertFalse(session.dispatch(SessionEvent.SetSplitMode(false)).snapshot.splitMode)
    }

    @Test
    fun disablingSplitFoldsARightHalfAnchorBackOntoThePage() {
        val page = PageId.at(episode, 0)
        val session = readySession(
            viewport = EngineViewport(1080, 1000),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(1600, 1200))),
        )
        session.dispatch(SessionEvent.SetSplitMode(true))

        val scrolled = session.dispatch(SessionEvent.Input(sample(1L, 2_000_000L)))

        assertEquals(InputOutcome.APPLIED, scrolled.receipts.single().outcome)
        val anchor = requireNotNull(scrolled.snapshot.anchor)
        assertTrue(anchor.sourceYQ32 >= 1200L * q)

        val disabled = session.dispatch(SessionEvent.SetSplitMode(false))

        assertFalse(disabled.snapshot.splitMode)
        assertEquals(anchor.sourceYQ32 - 1200L * q, disabled.snapshot.anchor?.sourceYQ32)
    }

    @Test
    fun splitIgnoresPortraitPagesAndClosedSessions() {
        val page = PageId.at(episode, 0)
        val portrait = readySession(
            viewport = EngineViewport(1080, 1000),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(1000, 1600))),
        )
        val before = portrait.snapshot.visibleRegions.single().sourceBottomQ32
        val toggled = portrait.dispatch(SessionEvent.SetSplitMode(true))
        assertEquals(before, toggled.snapshot.visibleRegions.single().sourceBottomQ32)

        val closed = portrait.dispatch(SessionEvent.Close).snapshot
        val late = portrait.dispatch(SessionEvent.SetSplitMode(false)).snapshot
        assertEquals(closed, late)
    }

    private fun sample(sequence: Long, delta: Long): InputSample = InputSample(
        sequence = sequence,
        gestureId = sequence,
        eventTimeNanos = 0L,
        deltaScreenUnits = delta,
    )

    private fun readySession(viewport: EngineViewport, pages: List<PageSpec>): EngineSession {
        val session = EngineSession(90L, episode, viewport, { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        session.dispatch(SessionEvent.ManifestResolved(1L, EpisodeManifest(episode, "Split", pages)))
        return session
    }
}
