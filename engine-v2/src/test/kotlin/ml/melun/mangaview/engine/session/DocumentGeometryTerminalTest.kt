package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.SourceAnchor
import org.junit.Assert.*
import org.junit.Test

class DocumentGeometryTerminalTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "terminal"), "1")
    private val pages = (0..2).map { PageId.at(episode, it) }
    private val dimensions = PageDimensions(100, 100)
    private val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL

    @Test fun lateLastPageDimensionsCannotLeaveTheViewportBeyondTheRealDocumentEnd() {
        val geometry = DocumentGeometry(episode, EngineViewport(100, 150))
        geometry.addManifest(EpisodeManifest(episode, "terminal", pages.mapIndexed { i, id -> PageSpec(id, i) }), true)
        geometry.anchor = AnchorState(pages[0], BigRational.ZERO, 0)
        geometry.setDimensions(pages[0], dimensions)
        geometry.setDimensions(pages[1], dimensions)

        val first = geometry.move(pixels(500))
        assertEquals(GeometryBlocker.Dimension(pages[2]), first.blocker)
        assertEquals(pixels(50), first.consumed)
        assertEquals(pixels(450), first.remaining)
        assertEquals(SourceAnchor(pages[0], 50L * q), geometry.publicAnchor())

        geometry.setDimensions(pages[2], dimensions)
        val remainder = geometry.move(first.remaining)
        assertEquals(DocumentBoundary.END, remainder.boundary)
        assertEquals(pixels(150), first.consumed + remainder.consumed)
        assertEquals(SourceAnchor(pages[1], 50L * q), geometry.publicAnchor())
        val visible = geometry.visible()
        assertTrue(visible.complete)
        assertEquals(pages[2], visible.regions.last().pageId)
        assertEquals(100L * q, visible.regions.last().sourceBottomQ32)
        assertEquals(150L * 1_024L, visible.regions.last().screenBottomUnits)

        val repeated = geometry.move(pixels(500))
        assertEquals(DocumentBoundary.END, repeated.boundary)
        assertEquals(BigRational.ZERO, repeated.consumed)
        assertEquals(SourceAnchor(pages[1], 50L * q), geometry.publicAnchor())
    }

    @Test fun mixedWidthLateGeometryPreservesTheSameOrderedInputResultsAsKnownGeometry() {
        val sizes = listOf(PageDimensions(137, 411), PageDimensions(251, 125), PageDimensions(83, 41))
        fun session() = EngineSession(1, episode, EngineViewport(100, 150)) { 1_000L }.also {
            it.dispatch(SessionEvent.PositionResolved(1, null))
            it.dispatch(SessionEvent.ManifestResolved(1,
                EpisodeManifest(episode, "terminal", pages.mapIndexed { i, id -> PageSpec(id, i) })))
        }
        val reference = session()
        val delayed = session()
        pages.forEachIndexed { i, id -> reference.dispatch(
            SessionEvent.DimensionsResolved(1, id, sizes[i])) }
        delayed.dispatch(SessionEvent.DimensionsResolved(1, pages[0], sizes[0]))
        val samples = listOf(1_000L, -37L, 11L).mapIndexed { i, delta ->
            InputSample(i + 1L, i + 1L, 0, delta * 1_024L)
        }
        val expected = samples.flatMap { reference.dispatch(SessionEvent.Input(it)).receipts }
        val observed = samples.flatMap { delayed.dispatch(SessionEvent.Input(it)).receipts }.toMutableList()
        pages.drop(1).forEachIndexed { i, id -> observed += delayed.dispatch(
            SessionEvent.DimensionsResolved(1, id, sizes[i + 1])).receipts }
        assertEquals(0, delayed.snapshot.pendingInputCount)
        assertEquals(reference.snapshot.anchor, delayed.snapshot.anchor)
        assertEquals(expected.map { Triple(it.sample.sequence, it.outcome, it.appliedScreenUnits) },
            observed.filter { it.outcome != InputOutcome.DEFERRED }
                .map { Triple(it.sample.sequence, it.outcome, it.appliedScreenUnits) })
    }

    @Test fun completeVisibleGeometryRetainsFutureInputRequirementsSeparately() {
        val session = EngineSession(1, episode, EngineViewport(100, 150)) { 1_000L }
        session.dispatch(SessionEvent.PositionResolved(1, null))
        session.dispatch(SessionEvent.ManifestResolved(1,
            EpisodeManifest(episode, "terminal", pages.mapIndexed { i, id -> PageSpec(id, i) })))
        pages.take(2).forEach { session.dispatch(SessionEvent.DimensionsResolved(1, it, dimensions)) }
        val update = session.dispatch(SessionEvent.Input(InputSample(1, 1, 0, 500L * 1_024L)))
        assertEquals(InputOutcome.DEFERRED, update.receipts.single().outcome)
        assertEquals(setOf(pages[2]), update.snapshot.requiredDimensions)
        assertEquals(1, update.snapshot.pendingInputCount)
        assertTrue(update.snapshot.completeViewport)
        assertEquals(pages.take(2), update.snapshot.visibleRegions.map { it.pageId })
        assertEquals(0L, update.snapshot.visibleRegions.first().screenTopUnits)
        assertEquals(150L * 1_024L, update.snapshot.visibleRegions.last().screenBottomUnits)
    }

    private fun pixels(value: Long) = BigRational.of(value * 1_024L)
}
