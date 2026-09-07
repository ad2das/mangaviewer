package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class EngineViewerDiagnosticsTest {
    private fun observation(token: Long) = EngineSurfacePresentation(
        FrameIdentity(1, 1, 1, token, token, 1),
        EngineSurfaceScene(1, 1, token, 1, EngineViewport(1, 1), null, emptyList()),
        100 + token, 2, true, PresentationTimestampKind.SWAP_RETURN, 110 + token, token, 9)

    @Test fun frameHistoryIncludesEmptyAndFailedFramesAndReportsLostEvidence() {
        val diagnostics = EngineViewerDiagnostics(2)
        diagnostics.presented(observation(1))
        diagnostics.presented(observation(2).copy(swapSucceeded = false,
            timestampKind = PresentationTimestampKind.UNAVAILABLE, timestampNanos = 0, eglFrameId = 0))
        diagnostics.presented(observation(3))
        val batch = diagnostics.framesSince(0)
        assertEquals(1L, batch.lostCount)
        assertEquals(listOf(2L, 3L), batch.observations.map { it.presentation.identity.token })
        assertFalse(batch.observations.first().presentation.swapSucceeded)
        (batch.observations as MutableList).clear()
        assertEquals(2, diagnostics.framesSince(1).observations.size)
        assertTrue(diagnostics.framesSince(3).observations.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { diagnostics.framesSince(4) }
    }

    @Test fun closeProofRetainsIndependentNativeCountAndSealsFurtherDelivery() {
        val diagnostics = EngineViewerDiagnostics()
        diagnostics.presented(observation(1))
        diagnostics.rendererClosed(9, 3, 150)
        assertEquals(3L, diagnostics.frameCloseProof()?.submittedFrameCount)
        assertEquals(1L, diagnostics.frameCloseProof()?.deliveredObservationCount)
        assertThrows(IllegalStateException::class.java) { diagnostics.presented(observation(2)) }
    }

    @Test fun eglLatchAndCancelledCloseNeverBecomePhysicalPresentationEvidence() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val page = PageId.at(episode, 0)
        val tile = EngineTileSpec(page, "revision", "a".repeat(64), PageDimensions(10, 10), 0, 10, 10)
        val scene = EngineSurfaceScene(1, 1, 0, 0, EngineViewport(10, 10), SourceAnchor(page, 0),
            listOf(EngineTexturePlacement(EngineTexture(tile, 1, 1, 1, tile.byteCount), 0, 10)), completeCoverage = true)
        val latch = EngineSurfacePresentation(FrameIdentity(1, 1, 1, 1, 0, 0), scene,
            110, 2, true, PresentationTimestampKind.COMPOSITION_LATCH, 120, 1, 1)
        val diagnostics = EngineViewerDiagnostics()
        diagnostics.opened(100)
        diagnostics.presented(latch)
        assertEquals(110L, diagnostics.startup()?.firstActualSubmittedAtNanos)
        assertNull(diagnostics.startup()?.firstActualPresentedAtNanos)
        assertNull(diagnostics.startup()?.presentedPageKey)
        diagnostics.presented(latch.copy(timestampKind = PresentationTimestampKind.CANCELLED,
            timestampNanos = 0, swapSucceeded = false))
        assertEquals(110L, diagnostics.startup()?.firstActualSubmittedAtNanos)
        assertNull(diagnostics.startup()?.firstActualPresentedAtNanos)
    }
}
