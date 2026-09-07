package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeDiagnostic
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Negative controls for the exact endpoint and cache-branch gates used by the live probe. */
@RunWith(AndroidJUnit4::class)
class StartupActivationBenchmarkPolicyTest {
    @Test
    fun sourceFallbackCannotPassWhenResponseTimestampIsNull() {
        val initialResponseStartedAtNanos: Long? = null
        assertNull(initialResponseStartedAtNanos)
        val fallback = ViewerCachedResumeDiagnostic(
            ViewerCachedResumeRoute.SOURCE_FALLBACK,
            EPISODE,
            null,
            11L,
        )
        assertFalse(StartupActivationEvidencePolicy.completeLeaseMatches(fallback, EPISODE, 132))
        assertTrue(
            StartupActivationEvidencePolicy.completeLeaseMatches(
                ViewerCachedResumeDiagnostic(
                    ViewerCachedResumeRoute.COMPLETE_LEASE_OPENED,
                    EPISODE,
                    132,
                    12L,
                ),
                EPISODE,
                132,
            ),
        )
    }

    @Test
    fun cancelledDroppedContextLostAndZeroBufferSamplesCannotPass() {
        INVALID_TERMINALS.forEach { kind ->
            val sample = sample().copy(timestampKind = kind)
            assertFalse(
                "terminal $kind must not be a startup endpoint",
                StartupActivationEvidencePolicy.firstActualSubmissionMatches(
                    sample,
                    region(sample),
                    POSITION,
                    4L,
                ),
            )
        }
        val zeroBuffer = sample().copy(bufferFrameId = 0L)
        assertFalse(
            StartupActivationEvidencePolicy.firstActualSubmissionMatches(
                zeroBuffer,
                region(zeroBuffer),
                POSITION,
                4L,
            ),
        )
    }

    @Test
    fun wrongPageOrGenerationCannotPassThroughCurrentTelemetry() {
        val sample = sample()
        assertFalse(
            StartupActivationEvidencePolicy.firstActualSubmissionMatches(
                sample,
                region(sample).copy(pageId = PageId(EPISODE, "p0009")),
                POSITION,
                4L,
            ),
        )
        val wrongGeneration = sample.copy(generation = 9L)
        assertFalse(
            StartupActivationEvidencePolicy.firstActualSubmissionMatches(
                wrongGeneration,
                region(wrongGeneration),
                POSITION,
                4L,
            ),
        )
    }

    private fun sample() = NativePresentationEvidence(
        rendererIdentity = 3L,
        token = 7L,
        generation = 4L,
        presentedNanos = 120L,
        submittedAtNanos = 100L,
        renderLatencyNanos = 20L,
        scrollOffsetUnits = 0L,
        viewportHeightUnits = 2_000L,
        anchorOrdinal = 1,
        anchorOffsetUnits = POSITION.offsetInPageUnits,
        timestampKind = PresentationTimestampKind.COMPOSITION_LATCH,
        bufferFrameId = 12L,
        readableActualContent = true,
        fullVisualCoverage = true,
        fullActualCoverage = true,
        userInputRevision = 0L,
    )

    private fun region(sample: NativePresentationEvidence) = PresentedImageRegion(
        rendererIdentity = sample.rendererIdentity,
        token = sample.token,
        generation = sample.generation,
        pageId = POSITION.pageId,
        sourceTopRow = 0,
        sourceBottomRowExclusive = 100,
        sourceHeightRows = 100,
        presentedNanos = sample.presentedNanos,
        timestampKind = sample.timestampKind,
        imageIdentityVerified = true,
        bufferFrameId = sample.bufferFrameId,
        submittedAtNanos = sample.submittedAtNanos,
        userInputRevision = 0L,
    )

    private companion object {
        val EPISODE = EpisodeId(SeriesId(SourceId("ntk"), "/webtoon/57451201"), "/webtoon/57451201/jjaptoon-1341148")
        val POSITION = ReadingPosition(PageId(EPISODE, "p0007"), 321L)
        val INVALID_TERMINALS = setOf(
            PresentationTimestampKind.CANCELLED,
            PresentationTimestampKind.DROPPED,
            PresentationTimestampKind.CONTEXT_LOST,
        )
    }
}
