package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPresentationTraceVerifierTest {
    @Test
    fun crossPageMovementWithoutInputCannotHideBehindAnOrdinalChange() {
        val before = evidence(1L, 0L, anchorOrdinal = 1).copy(geometryRevision = 3L,
            userInputRevision = 9L, scrollCause = ScrollMutationCause.USER_INPUT)
        val after = evidence(2L, 50L, anchorOrdinal = 2).copy(geometryRevision = 3L,
            userInputRevision = 9L, scrollCause = ScrollMutationCause.USER_INPUT)
        assertTrue(ViewerPresentationTraceVerifier.verify(listOf(before, after), listOf(1L..10L))
            .any { it.contains("without input or geometry") })
    }

    @Test
    fun resolvingDimensionsMayShiftGlobalOffsetWhileKeepingTheSameSemanticPixel() {
        val before = evidence(1L, 20L, anchorOrdinal = 1).copy(geometryRevision = 3L,
            userInputRevision = 9L, scrollCause = ScrollMutationCause.USER_INPUT)
        val after = before.copy(token = 2L, presentedNanos = 2L, geometryRevision = 4L,
            scrollOffsetUnits = 1_000_000L, scrollCause = ScrollMutationCause.GEOMETRY_CORRECTION)
        assertTrue(ViewerPresentationTraceVerifier.verify(listOf(before, after), listOf(1L..10L)).isEmpty())
    }

    @Test
    fun compositorLatchAndSwapReturnCannotPassAsDisplayEvidence() {
        PresentationTimestampKind.entries.filter { it != PresentationTimestampKind.DISPLAY_PRESENT }
            .forEach { kind ->
                val sample = evidence(1L, 0L).copy(timestampKind = kind)
                assertTrue(ViewerPresentationTraceVerifier.verify(listOf(sample), listOf(1L..10L))
                    .any { it.startsWith("Actual display presentation is unverified") })
            }
    }

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
    fun loadingCoverageAloneCannotMasqueradeAsActualContent() {
        val samples = listOf(evidence(1L, 0L, visual = true, actual = false))

        assertTrue(ViewerPresentationTraceVerifier.verify(samples, listOf(1L..10L))
            .any { it.contains("never presented readable image pixels") })
        assertEquals(false, samples.single().readableActualContent)
    }

    @Test
    fun loadingFramesBeforeTheSeparatelyGatedFirstContentDeadlineAreAllowed() {
        val samples = listOf(
            evidence(1L, 0L, actual = false, presentedNanos = 5L),
            evidence(2L, 1_000L, actual = true, presentedNanos = 20L),
        )

        val violations = ViewerPresentationTraceVerifier.verify(
            samples,
            listOf(1L..10L, 15L..25L),
        )

        assertTrue(violations.isEmpty())
    }

    @Test
    fun detectsUncoveredBackwardAndImpossibleForwardFrames() {
        val samples = listOf(
            evidence(1L, 0L, anchorOrdinal = 0),
            evidence(2L, 4_000L, visual = false, anchorOrdinal = 0),
            evidence(3L, 3_000L, anchorOrdinal = 0),
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
        anchorOrdinal: Int = if (token == 3L) 0 else token.toInt(),
    ) = NativePresentationEvidence(
        rendererIdentity = 1L,
        token = token,
        generation = 1L,
        presentedNanos = presentedNanos,
        renderLatencyNanos = 1L,
        scrollOffsetUnits = offset,
        viewportHeightUnits = 1_000L,
        anchorOrdinal = anchorOrdinal,
        anchorOffsetUnits = offset,
        readableActualContent = actual,
        fullVisualCoverage = visual,
        fullActualCoverage = actual,
        timestampKind = PresentationTimestampKind.DISPLAY_PRESENT,
    )
}
