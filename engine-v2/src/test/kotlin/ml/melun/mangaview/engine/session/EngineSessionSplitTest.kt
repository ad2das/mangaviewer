package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.VisiblePageRegion
import ml.melun.mangaview.engine.runtime.EngineTilePlanner
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
    fun disablingSplitFoldsASecondHalfAnchorBackOntoThePage() {
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

    @Test
    fun directFullSpreadPlanKeepsRightHalfBeforeLeftHalf() {
        val pageId = PageId.at(episode, 0)
        val dimensions = PageDimensions(1600, 1200)
        val page = PageContentIdentity(pageId, "1", "1".repeat(64), dimensions, 1)
        val session = EngineSessionSnapshot(1, 1, EngineSessionPhase.ACTIVE, EngineViewport(1080, 4000),
            SourceAnchor(pageId, 0), 1, 1, 0,
            listOf(VisiblePageRegion(pageId, dimensions, 0, 2400L * q, 0, 4000L * 1024L)),
            emptySet(), emptySet(), true, splitMode = true)
        val plan = EngineTilePlanner(20_000_000, 302)
            .plan(EngineRuntimeSnapshot(session, emptyMap(), mapOf(pageId to page)))
        val halves = plan.placements.map { if (it.tile.cropLeftPx > 0) "R" else "L" }
        assertEquals(halves.sortedDescending(), halves)
        assertTrue(halves.contains("L") && halves.contains("R"))
        assertEquals(800, plan.placements.first().tile.cropLeftPx)
        assertEquals(0, plan.placements.last().tile.cropLeftPx)
        assertTrue(plan.placements.zipWithNext().all { (a, b) -> a.topScreenUnits <= b.topScreenUnits })
    }

    @Test
    fun walkingTheWholeDocumentVisitsEveryHalfInSourceOrder() {
        val specs = listOf(
            PageSpec(PageId.at(episode, 0), 0, PageDimensions(1000, 1600)),
            PageSpec(PageId.at(episode, 1), 1, PageDimensions(1600, 1200)),
            PageSpec(PageId.at(episode, 2), 2, PageDimensions(1600, 1200)),
            PageSpec(PageId.at(episode, 3), 3, PageDimensions(1000, 1600)),
            PageSpec(PageId.at(episode, 4), 4, PageDimensions(1600, 1200)),
        )
        val expected = listOf(0 to "F", 1 to "R", 1 to "L", 2 to "R", 2 to "L",
            3 to "F", 4 to "R", 4 to "L")
        val session = readySession(EngineViewport(1080, 400), specs)
        session.dispatch(SessionEvent.SetSplitMode(true))
        val identities = specs.associate { it.id to
            PageContentIdentity(it.id, "1", "0".repeat(64), it.dimensions!!, 1)
        }
        val planner = EngineTilePlanner(80_000_000, 512)
        fun tag(tile: EngineTileSpec): String = when {
            tile.cropLeftPx == 0 && tile.cropRightPx == tile.dimensions.widthPx -> "F"
            tile.cropLeftPx > 0 -> "R"
            else -> "L"
        }
        fun topTag(): Pair<Int, String>? {
            val tile = planner.plan(EngineRuntimeSnapshot(session.snapshot, emptyMap(), identities))
                .placements.minByOrNull { it.topScreenUnits }?.tile ?: return null
            return specs.indexOfFirst { it.id == tile.pageId } to tag(tile)
        }
        val visited = mutableListOf<Pair<Int, String>>()
        fun record() {
            topTag()?.let { if (visited.lastOrNull() != it) visited += it }
        }
        var sequence = 1L
        record()
        repeat(4000) { session.dispatch(SessionEvent.Input(sample(sequence++, 120_000L))); record() }
        assertEquals("top of viewport, forward", expected, visited)
        visited.clear()
        record()
        repeat(4000) { session.dispatch(SessionEvent.Input(sample(sequence++, -120_000L))); record() }
        assertEquals("top of viewport, backward", expected.reversed(), visited)
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
