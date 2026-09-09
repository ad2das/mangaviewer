package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class EngineInputReplayBudgetTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "replay"), "1")
    private val pages = (0..2).map { PageId.at(episode, it) }

    private fun ready(held: Boolean, clock: () -> Long = { 1_000L }): EngineSession =
        EngineSession(1, episode, EngineViewport(100, 150), clock).also { session ->
            if (held) session.engageStartupInputBarrier()
            session.dispatch(SessionEvent.PositionResolved(1, null))
            session.dispatch(SessionEvent.ManifestResolved(1, EpisodeManifest(episode, "replay",
                pages.mapIndexed { i, id -> PageSpec(id, i) })))
            listOf(PageDimensions(137, 411), PageDimensions(251, 125), PageDimensions(83, 41))
                .forEachIndexed { i, size -> session.dispatch(SessionEvent.DimensionsResolved(1, pages[i], size)) }
        }

    @Test fun hundredsOfQueuedReversalsKeepTheSameFifoResultsAndExactFinalPosition() {
        val reference = ready(false)
        val queued = ready(true)
        val samples = (1..488).map { index ->
            InputSample(index.toLong(), 1, 0, (when (index % 5) {
                0 -> -1000L; 1 -> 500L; 2 -> -37L; 3 -> 11L; else -> 200L
            }) * 1024L)
        }
        val expected = samples.flatMap { reference.dispatch(SessionEvent.Input(it)).receipts }
        samples.forEach { queued.dispatch(SessionEvent.Input(it)) }
        var update = queued.dispatch(SessionEvent.ReleaseStartupInput)
        val observed = update.receipts.toMutableList()
        assertTrue(queued.inputReplayPending)
        assertTrue(update.receipts.size in 1..32)
        while (queued.inputReplayPending) {
            update = queued.dispatch(SessionEvent.ContinueInput(1))
            assertTrue(update.receipts.size in 1..32)
            observed += update.receipts
        }
        assertEquals(488, observed.size)
        assertEquals(expected.map { it.copy(acceptedAtNanos = 0, resolvedAtNanos = 0) },
            observed.map { it.copy(acceptedAtNanos = 0, resolvedAtNanos = 0) })
        assertEquals(reference.snapshot.anchor, queued.snapshot.anchor)
        assertEquals(reference.snapshot.movementRevision, queued.snapshot.movementRevision)
        assertEquals(0, queued.snapshot.pendingInputCount)
    }

    @Test fun elapsedBudgetYieldsBeforeTheCountLimitAndOldContinuationCannotNavigate() {
        var now = 1_000L
        val queued = ready(true) { now += 600_000; now }
        (1..100).forEach { queued.dispatch(SessionEvent.Input(InputSample(it.toLong(), 1, 0, 1024))) }
        val first = queued.dispatch(SessionEvent.ReleaseStartupInput)
        assertTrue(first.receipts.size in 1..2)
        assertTrue(queued.inputReplayPending)
        val oldGeneration = queued.snapshot.generation
        val canceled = queued.dispatch(SessionEvent.Navigate(episode.copy(remoteKey = "2")))
        assertEquals(100 - first.receipts.size, canceled.receipts.size)
        assertFalse(queued.inputReplayPending)
        val after = queued.snapshot
        assertTrue(queued.dispatch(SessionEvent.ContinueInput(oldGeneration)).receipts.isEmpty())
        assertEquals(after, queued.snapshot)
    }

    @Test fun newReverseInputStaysBehindTheYieldedQueueAndCloseCancelsEveryRemainder() {
        val queued = ready(true)
        (1..100).forEach { queued.dispatch(SessionEvent.Input(InputSample(it.toLong(), 1, 0, 1024))) }
        val completed = queued.dispatch(SessionEvent.ReleaseStartupInput).receipts.toMutableList()
        val arrival = queued.dispatch(SessionEvent.Input(InputSample(101, 2, 0, -1024)))
        assertEquals(InputOutcome.DEFERRED, arrival.receipts.last().outcome)
        assertEquals(101L, arrival.receipts.last().sample.sequence)
        completed += arrival.receipts.filter { it.outcome != InputOutcome.DEFERRED }
        val canceled = queued.dispatch(SessionEvent.Close).receipts
        assertEquals((1L..101L).toList(), (completed + canceled).map { it.sample.sequence })
        assertTrue(canceled.all { it.outcome == InputOutcome.CANCELLED })
        assertFalse(queued.inputReplayPending)
        assertEquals(0, queued.snapshot.pendingInputCount)
        assertTrue(queued.dispatch(SessionEvent.ContinueInput(1)).receipts.isEmpty())
    }
}
