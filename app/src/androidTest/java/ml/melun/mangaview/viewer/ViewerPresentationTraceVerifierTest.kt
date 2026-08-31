package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPresentationTraceVerifierTest {
    @Test
    fun lateOlderSubmissionIsCheckedInActualCompletionOrder() {
        val samples = listOf(
            evidence(token = 12L, offset = 2_000L, presentedNanos = 110L),
            evidence(token = 11L, offset = 1_000L, presentedNanos = 120L),
        )

        val violations = ViewerPresentationTraceVerifier.verify(samples, listOf(100L..130L))

        assertTrue(violations.any { it.contains("moved backward") })
    }

    @Test
    fun loadingCoverageIsVisualButCannotMasqueradeAsActualContent() {
        val samples = listOf(evidence(1L, 0L, visual = true, actual = false))

        assertTrue(ViewerPresentationTraceVerifier.verify(samples, listOf(1L..10L)).isEmpty())
        assertEquals(false, samples.single().readableActualContent)
    }

    @Test
    fun detectsUncoveredBackwardAndImpossibleForwardFrames() {
        val samples = listOf(
            evidence(1L, 0L),
            evidence(2L, 4_000L, visual = false),
            evidence(3L, 3_000L),
        )

        val violations = ViewerPresentationTraceVerifier.verify(samples, listOf(1L..10L))

        assertEquals(3, violations.size)
    }

    @Test
    fun reverseWindowRejectsAForwardCompletionButAcceptsReverseMotion() {
        val samples = listOf(
            evidence(1L, 2_000L),
            evidence(2L, 1_000L),
            evidence(3L, 1_500L),
        )

        val violations = ViewerPresentationTraceVerifier.verifyDirected(
            samples,
            listOf(PresentationGestureWindow(1L..10L, TelemetryDirection.REVERSE)),
        )

        assertEquals(1, violations.count { it.contains("during a reverse gesture") })
    }

    @Test
    fun gestureWithoutACompletedSurfaceFrameCannotPass() {
        val violations = ViewerPresentationTraceVerifier.verify(emptyList(), listOf(1L..10L))

        assertEquals(1, violations.size)
        assertTrue(violations.single().startsWith("A real gesture window had no Surface presentation evidence"))
    }

    private fun evidence(
        token: Long,
        offset: Long,
        visual: Boolean = true,
        actual: Boolean = true,
        presentedNanos: Long = token,
    ) = NativePresentationEvidence(
        rendererIdentity = 1L,
        token = token,
        generation = 1L,
        presentedNanos = presentedNanos,
        renderLatencyNanos = 1L,
        scrollOffsetUnits = offset,
        viewportHeightUnits = 1_000L,
        anchorOrdinal = if (token == 3L) 0 else token.toInt(),
        anchorOffsetUnits = offset,
        readableActualContent = actual,
        fullVisualCoverage = visual,
        fullActualCoverage = actual,
    )
}
