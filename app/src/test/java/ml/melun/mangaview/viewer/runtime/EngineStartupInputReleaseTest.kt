package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.session.EngineSession
import org.junit.Assert.*
import org.junit.Test

class EngineStartupInputReleaseTest {
    private val series = SeriesId(SourceId("test"), "startup")
    private val current = EpisodeId(series, "current")
    private val next = EpisodeId(series, "next")
    private val currentPage = PageId.at(current, 14)
    private val nextPage = PageId.at(next, 0)
    private val dimensions = PageDimensions(100, 900)
    private val viewport = EngineViewport(100, 50)
    private val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL

    @Test fun exactPageEndReleasesFromSubmittedNextPageAndQueuedInputCompletesExactly() {
        var now = 1_000L
        val session = EngineSession(1, current, viewport) { now++ }.apply { engageStartupInputBarrier() }
        val generation = session.snapshot.generation
        session.dispatch(SessionEvent.PositionResolved(generation,
            SourceAnchor(currentPage, dimensions.heightPx.toLong() * q)))
        session.dispatch(SessionEvent.ManifestResolved(generation, manifest(current, currentPage, nextEpisode = next)))
        session.dispatch(SessionEvent.DimensionsResolved(generation, currentPage, dimensions))
        session.dispatch(SessionEvent.ManifestResolved(generation, manifest(next, nextPage, previousEpisode = current)))
        session.dispatch(SessionEvent.DimensionsResolved(generation, nextPage, dimensions))

        val sample = InputSample(1, 1, 0, 50L * 1_024L)
        val deferred = session.dispatch(SessionEvent.Input(sample)).receipts.single()
        assertEquals(InputOutcome.DEFERRED, deferred.outcome)
        assertEquals(listOf(nextPage), session.snapshot.visibleRegions.map { it.pageId })

        val presentation = presentation(session.snapshot, nextPage, 0, 900)
        assertTrue(releasesStartupInput(presentation, session.snapshot))
        val applied = session.dispatch(SessionEvent.ReleaseStartupInput).receipts.single()

        assertEquals(InputOutcome.APPLIED, applied.outcome)
        assertEquals(deferred.acceptedAtNanos, applied.acceptedAtNanos)
        assertEquals(sample.deltaScreenUnits, applied.appliedScreenUnits)
        assertEquals(SourceAnchor(nextPage, 50L * q), session.snapshot.anchor)
    }

    @Test fun releaseRejectsStaleNavigationFailedSwapAndSourceOutsideVisibleRegion() {
        val session = readySession()
        val state = session.snapshot
        val matching = presentation(state, nextPage, 0, 900)
        assertTrue(releasesStartupInput(matching, state))
        assertFalse(releasesStartupInput(matching.copy(swapSucceeded = false), state))
        assertFalse(releasesStartupInput(presentation(state, currentPage, 0, 900), state))
        assertFalse(releasesStartupInput(presentation(state, nextPage, 100, 900), state))
        assertFalse(releasesStartupInput(matching, state.copy(sessionId = state.sessionId + 1)))
        assertFalse(releasesStartupInput(matching, state.copy(anchor = SourceAnchor(currentPage, 0))))
        assertFalse(releasesStartupInput(matching, state.copy(viewport = EngineViewport(100, 51))))
        val afterNavigation = session.dispatch(SessionEvent.Navigate(next)).snapshot
        assertEquals(state.generation + 1, afterNavigation.generation)
        assertFalse(releasesStartupInput(matching, afterNavigation))
    }

    @Test fun zeroInputCompletesImmediatelyWhileNegativeInputWaitsAndReplaysExactly() {
        val session = readySession()
        val zero = InputSample(1, 1, 0, 0)
        assertEquals(InputOutcome.APPLIED, session.dispatch(SessionEvent.Input(zero)).receipts.single().outcome)
        val negative = InputSample(2, 1, 0, -25L * 1_024L)
        assertEquals(InputOutcome.DEFERRED, session.dispatch(SessionEvent.Input(negative)).receipts.single().outcome)
        val applied = session.dispatch(SessionEvent.ReleaseStartupInput).receipts.single()
        assertEquals(InputOutcome.APPLIED, applied.outcome)
        assertEquals(negative.deltaScreenUnits, applied.appliedScreenUnits)
        assertEquals(SourceAnchor(currentPage, 875L * q), session.snapshot.anchor)
    }

    @Test fun navigationBeforeFirstSubmissionCancelsOldInputAndNewGenerationCanRelease() {
        val session = readySession()
        val oldInput = InputSample(1, 1, 0, 10L * 1_024L)
        assertEquals(InputOutcome.DEFERRED, session.dispatch(SessionEvent.Input(oldInput)).receipts.single().outcome)

        val navigated = session.dispatch(SessionEvent.Navigate(next))
        assertEquals(InputOutcome.CANCELLED, navigated.receipts.single().outcome)
        val generation = navigated.snapshot.generation
        session.dispatch(SessionEvent.ManifestResolved(generation, manifest(next, nextPage)))
        session.dispatch(SessionEvent.DimensionsResolved(generation, nextPage, dimensions))
        val newInput = InputSample(2, 2, 0, 10L * 1_024L)
        assertEquals(InputOutcome.DEFERRED, session.dispatch(SessionEvent.Input(newInput)).receipts.single().outcome)

        val presentation = presentation(session.snapshot, nextPage, 0, 900)
        assertTrue(releasesStartupInput(presentation, session.snapshot))
        val applied = session.dispatch(SessionEvent.ReleaseStartupInput).receipts.single()
        assertEquals(InputOutcome.APPLIED, applied.outcome)
        assertEquals(newInput.deltaScreenUnits, applied.appliedScreenUnits)
    }

    @Test fun resumePositionUsesTheSubmittedScenesSourceGeometry() {
        val state = readySession().snapshot
        val anchor = SourceAnchor(currentPage, 25 * q, 7)
        val tile = EngineTileSpec(currentPage, "revision", "a".repeat(64), dimensions, 0, 900, 200)
        val scene = presentation(state, currentPage, 0, 900).scene.copy(completeCoverage = true,
            anchor = anchor, viewport = EngineViewport(200, 50), anchorDimensions = dimensions,
            placements = listOf(EngineTexturePlacement(EngineTexture(tile, 1, 1, 1, tile.byteCount), 0, 1800)))
        assertEquals(anchor to 50 * 1024L, submittedSourcePosition(scene))
        assertNull(submittedSourcePosition(scene.copy(completeCoverage = false)))
        assertNull(submittedSourcePosition(scene.copy(anchorDimensions = null)))
        assertNull(submittedSourcePosition(scene.copy(anchor = null)))
    }

    @Test fun splitResumeFoldsTheRightHalfRowsBackOntoTheOriginalPage() {
        val spread = PageDimensions(700, 500)
        val half = 500L * q
        assertEquals(300L * q, foldSplitSource(800L * q, spread))
        assertEquals(0L, foldSplitSource(half, spread))
        assertEquals(499L * q, foldSplitSource(499L * q, spread))
        assertEquals(900L * q, foldSplitSource(900L * q, dimensions))
        assertEquals(900L * q, foldSplitSource(900L * q, PageDimensions(500, 500)))
        val state = readySession().snapshot
        val tile = EngineTileSpec(currentPage, "revision", "a".repeat(64), spread, 0, 500, 700)
        val anchor = SourceAnchor(currentPage, 750L * q, 7)
        val scene = presentation(state, currentPage, 0, 500).scene.copy(completeCoverage = true,
            anchor = anchor, viewport = EngineViewport(700, 50), anchorDimensions = spread,
            placements = listOf(EngineTexturePlacement(EngineTexture(tile, 1, 1, 1, tile.byteCount), 0, 500 * 1024)))
        assertEquals(anchor to 750L * 1024L, submittedSourcePosition(scene))
        assertEquals(SourceAnchor(currentPage, 250L * q, 7) to 250L * 1024L,
            submittedSourcePosition(scene.copy(splitMode = true)))
    }

    private fun readySession(): EngineSession {
        val session = EngineSession(2, current, viewport) { 1_000L }.apply { engageStartupInputBarrier() }
        val generation = session.snapshot.generation
        session.dispatch(SessionEvent.PositionResolved(generation,
            SourceAnchor(currentPage, dimensions.heightPx.toLong() * q)))
        session.dispatch(SessionEvent.ManifestResolved(generation, manifest(current, currentPage, nextEpisode = next)))
        session.dispatch(SessionEvent.DimensionsResolved(generation, currentPage, dimensions))
        session.dispatch(SessionEvent.ManifestResolved(generation, manifest(next, nextPage, previousEpisode = current)))
        session.dispatch(SessionEvent.DimensionsResolved(generation, nextPage, dimensions))
        return session
    }

    private fun manifest(id: EpisodeId, pageId: PageId, previousEpisode: EpisodeId? = null,
        nextEpisode: EpisodeId? = null) = EpisodeManifest(id, id.remoteKey, listOf(PageSpec(pageId, 0)),
        previousEpisodeId = previousEpisode, nextEpisodeId = nextEpisode)

    private fun presentation(state: EngineSessionSnapshot, pageId: PageId, sourceTop: Int,
        sourceBottom: Int): EngineSurfacePresentation {
        val tile = EngineTileSpec(pageId, "revision", "a".repeat(64), dimensions, sourceTop, sourceBottom,
            viewport.widthPx)
        val scene = EngineSurfaceScene(state.sessionId, state.generation, state.inputRevision,
            state.geometryRevision, state.viewport, state.anchor,
            listOf(EngineTexturePlacement(EngineTexture(tile, 1, 1, 1, tile.byteCount), 0, 50)))
        return EngineSurfacePresentation(FrameIdentity(1, 1, 1, 1, 0, 0), scene, 1, 1, true,
            PresentationTimestampKind.SWAP_RETURN, 2, 1, 1)
    }
}
