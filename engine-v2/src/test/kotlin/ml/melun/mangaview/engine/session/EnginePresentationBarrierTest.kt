package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class EnginePresentationBarrierTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
    private val page = PageId.at(episode, 0)

    @Test fun queuedReverseMovementsRequireDistinctPresentationsAndPreserveDistance() {
        val session = ready()
        val initial = session.snapshot
        val samples = listOf(50L, -50L, 20L).mapIndexed { index, delta ->
            InputSample(index + 1L, 1, 10, delta * 1024)
        }
        samples.forEach { assertEquals(InputOutcome.DEFERRED,
            session.dispatch(SessionEvent.Input(it)).receipts.single().outcome) }
        assertEquals(initial.anchor, session.snapshot.anchor)
        val first = session.dispatch(SessionEvent.ViewportReady(initial))
        assertEquals(samples[0], first.receipts.single().sample)
        assertEquals(50L * 1024, first.receipts.single().appliedScreenUnits)
        assertEquals(50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL, first.snapshot.anchor!!.sourceYQ32)
        assertTrue(session.dispatch(SessionEvent.ViewportReady(initial)).receipts.isEmpty())
        val second = session.dispatch(SessionEvent.ViewportReady(first.snapshot))
        assertEquals(samples[1], second.receipts.single().sample)
        assertEquals(-50L * 1024, second.receipts.single().appliedScreenUnits)
        assertEquals(initial.anchor, second.snapshot.anchor)
        assertTrue("Old acknowledgement at the same anchor must not release the third input",
            session.dispatch(SessionEvent.ViewportReady(initial)).receipts.isEmpty())
        val third = session.dispatch(SessionEvent.ViewportReady(second.snapshot))
        assertEquals(samples[2], third.receipts.single().sample)
        assertEquals(20L * 1024, third.receipts.single().appliedScreenUnits)
        assertEquals(0, third.snapshot.pendingInputCount)
    }

    @Test fun incompleteOrResizedPresentationCannotReleaseInputAndCloseCancelsOnce() {
        val session = ready()
        val old = session.snapshot
        val sample = InputSample(1, 1, 10, 20 * 1024L)
        session.dispatch(SessionEvent.Input(sample))
        assertTrue(session.dispatch(SessionEvent.ViewportReady(old.copy(completeViewport = false))).receipts.isEmpty())
        session.dispatch(SessionEvent.Resize(EngineViewport(200, 100)))
        assertTrue(session.dispatch(SessionEvent.ViewportReady(old)).receipts.isEmpty())
        val closed = session.dispatch(SessionEvent.Close)
        assertEquals(sample, closed.receipts.single().sample)
        assertEquals(InputOutcome.CANCELLED, closed.receipts.single().outcome)
        assertEquals(0L, closed.receipts.single().appliedScreenUnits)
        assertTrue(session.dispatch(SessionEvent.ViewportReady(session.snapshot)).receipts.isEmpty())
        assertTrue(session.dispatch(SessionEvent.Close).receipts.isEmpty())
    }

    @Test fun missingDimensionsAndPresentationWaitsKeepTheSameBoundaryAndReverseDistance() {
        val next = PageId.at(episode, 1)
        val dimensions = PageDimensions(100, 100)
        val session = EngineSession(1, episode, EngineViewport(100, 50)) { 100L }.apply {
            engageViewportReadinessBarrier()
            dispatch(SessionEvent.PositionResolved(1, null))
            dispatch(SessionEvent.ManifestResolved(1, EpisodeManifest(episode, "test",
                listOf(PageSpec(page, 0, dimensions), PageSpec(next, 1)))))
        }
        session.dispatch(SessionEvent.ViewportReady(session.snapshot))
        val forward = InputSample(1, 1, 10, 200 * 1024L)
        val reverse = InputSample(2, 1, 10, -50 * 1024L)
        val partial = session.dispatch(SessionEvent.Input(forward))
        assertEquals(50 * 1024L, partial.receipts.single().appliedScreenUnits)
        assertEquals(InputOutcome.DEFERRED, partial.receipts.single().outcome)
        assertTrue(next in partial.snapshot.requiredDimensions)
        session.dispatch(SessionEvent.Input(reverse))
        val geometry = session.dispatch(SessionEvent.DimensionsResolved(1, next, dimensions))
        assertTrue(geometry.receipts.isEmpty())
        assertEquals(partial.snapshot.anchor, geometry.snapshot.anchor)
        assertTrue(session.dispatch(SessionEvent.ViewportReady(partial.snapshot)).receipts.isEmpty())
        val clamped = session.dispatch(SessionEvent.ViewportReady(session.snapshot))
        assertEquals(forward, clamped.receipts.single().sample)
        assertEquals(InputOutcome.CLAMPED, clamped.receipts.single().outcome)
        assertEquals(150 * 1024L, clamped.receipts.single().appliedScreenUnits)
        val reversed = session.dispatch(SessionEvent.ViewportReady(clamped.snapshot))
        assertEquals(reverse, reversed.receipts.single().sample)
        assertEquals(-50 * 1024L, reversed.receipts.single().appliedScreenUnits)
        assertEquals(SourceAnchor(next, 0), reversed.snapshot.anchor)
        assertEquals(0, reversed.snapshot.pendingInputCount)
    }

    private fun ready(): EngineSession = EngineSession(1, episode, EngineViewport(100, 100)) { 100L }.apply {
        engageViewportReadinessBarrier()
        dispatch(SessionEvent.PositionResolved(1, null))
        dispatch(SessionEvent.ManifestResolved(1, EpisodeManifest(episode, "test",
            listOf(PageSpec(page, 0, PageDimensions(100, 1000))))))
    }
}
