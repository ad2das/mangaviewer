package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderSurfaceViewTest {
    @Test
    public void sourceNativeWebtoonTargetRemovesOnlyRedundantIntermediatePixels() {
        assertEquals(800, ReaderSurfaceView.sourceNativeRenderTargetSizeForTest(
                true, 1080, 2138, 740)[0]);
        assertEquals(1584, ReaderSurfaceView.sourceNativeRenderTargetSizeForTest(
                true, 1080, 2138, 740)[1]);
    }

    @Test
    public void sourceNativeWebtoonTargetNeverFallsBelowKnownSourceWidth() {
        int[] source1000 = ReaderSurfaceView.sourceNativeRenderTargetSizeForTest(
                true, 1080, 2138, 1000);
        assertEquals(1000, source1000[0]);
        assertEquals(1980, source1000[1]);
        assertEquals(1080, ReaderSurfaceView.sourceNativeRenderTargetSizeForTest(
                true, 1080, 2138, 1440)[0]);
    }

    @Test
    public void mangaTargetRemainsDisplaySized() {
        int[] target = ReaderSurfaceView.sourceNativeRenderTargetSizeForTest(
                false, 1080, 2138, 740);
        assertEquals(1080, target[0]);
        assertEquals(2138, target[1]);
    }

    @Test
    public void shortFastSwipeStartsFling() {
        assertTrue(ReaderSurfaceView.shouldStartFlingForTest(42f, 2400, 50, 16));
    }

    @Test
    public void tapSizedMovementDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(8f, 2400, 50, 16));
    }

    @Test
    public void subSlopFingerJitterStillCountsAsTap() {
        assertTrue(ReaderSurfaceView.isTapGestureForTest(true, 5f, 6f, 16));
        assertFalse(ReaderSurfaceView.isTapGestureForTest(true, 12f, 12f, 16));
        assertFalse(ReaderSurfaceView.isTapGestureForTest(false, 0f, 0f, 16));
    }

    @Test
    public void slowDragDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(80f, 30, 50, 16));
    }

    @Test
    public void realPixelsOnlyModeKeepsRequestedDrawablePrefixGuard() {
        assertTrue(ReaderSurfaceView.effectiveDrawablePrefixScrollLimitForTest(true, true));
        assertTrue(ReaderSurfaceView.effectiveDrawablePrefixScrollLimitForTest(true, false));
        assertFalse(ReaderSurfaceView.effectiveDrawablePrefixScrollLimitForTest(false, true));
    }

    @Test
    public void movedScrollerFrameAdvancesOnlyWhenNoNewerVersionIsPending() {
        assertTrue(ReaderSurfaceView.shouldAdvanceDesiredVersionForScrollerFrameForTest(
                true, 7L, 7L));
        assertTrue(ReaderSurfaceView.shouldAdvanceDesiredVersionForScrollerFrameForTest(
                true, 6L, 7L));
        assertFalse(ReaderSurfaceView.shouldAdvanceDesiredVersionForScrollerFrameForTest(
                true, 8L, 7L));
        assertFalse(ReaderSurfaceView.shouldAdvanceDesiredVersionForScrollerFrameForTest(
                false, 7L, 7L));
    }

    @Test
    public void idleBetweenGesturesIsNotChargedToTheNextMutation() {
        float causalAgeMs = ReaderSurfaceView.causalPixelMutationAgeMsForTest(
                100_000_000L, 116_000_000L);
        assertEquals(16f, causalAgeMs, 0.001f);
        assertFalse(ReaderSurfaceView.isCausalPixelMutationOverBudgetForTest(causalAgeMs));
    }

    @Test
    public void causalMutationAgeRetainsTheStrictFrameBudget() {
        assertTrue(ReaderSurfaceView.isCausalPixelMutationOverBudgetForTest(16.68f));
        assertFalse(ReaderSurfaceView.isCausalPixelMutationOverBudgetForTest(16.67f));
    }

    @Test
    public void downUpAndBusyWithoutPixelMutationHaveNoCausalAge() {
        assertEquals(0f, ReaderSurfaceView.causalPixelMutationAgeMsForTest(
                0L, 100_000_000L), 0f);
        assertFalse(ReaderSurfaceView.shouldConsumePixelMutationTimingForTest(true, 0L));
    }

    @Test
    public void awaitingCommitBacklogRemainsInMutationAge() {
        float causalAgeMs = ReaderSurfaceView.causalPixelMutationAgeMsForTest(
                100_000_000L, 130_000_000L);
        assertEquals(30f, causalAgeMs, 0.001f);
        assertTrue(ReaderSurfaceView.isCausalPixelMutationOverBudgetForTest(causalAgeMs));
    }

    @Test
    public void completedDrawProofCannotCrossSurfaceLifecycleEpochs() {
        assertTrue(ReaderSurfaceView.isCompletedDrawProofLifecycleCurrentForTest(7L, 7L));
        assertFalse(ReaderSurfaceView.isCompletedDrawProofLifecycleCurrentForTest(0L, 7L));
        assertFalse(ReaderSurfaceView.isCompletedDrawProofLifecycleCurrentForTest(6L, 7L));
    }

    @Test
    public void nativeLatchIsConvertedByDurationWithoutAssumingClockEpoch() {
        assertEquals(900L, ReaderSurfaceView.surfaceLatchPresentedUptimeNanosForTest(
                1_000L, 8_000L, 8_100L));
        assertEquals(0L, ReaderSurfaceView.surfaceLatchPresentedUptimeNanosForTest(
                1_000L, 0L, 8_100L));
        assertEquals(0L, ReaderSurfaceView.surfaceLatchPresentedUptimeNanosForTest(
                1_000L, 8_100L, 8_000L));
    }

    @Test
    public void gpuCommitBacklogUsesABoundedMultiFlightWindow() {
        int capacity = ReaderSurfaceView.maxPendingFrameCommitsForTest();
        assertTrue(capacity >= 3);
        assertTrue(ReaderSurfaceView.canAdmitPendingFrameCommitForTest(0));
        assertTrue(ReaderSurfaceView.canAdmitPendingFrameCommitForTest(capacity - 1));
        assertFalse(ReaderSurfaceView.canAdmitPendingFrameCommitForTest(capacity));
        assertFalse(ReaderSurfaceView.canAdmitPendingFrameCommitForTest(-1));
    }

    @Test
    public void physicalMotionIntervalClosesOnlyAfterRequestedPixelsAreCommitted() {
        assertTrue(ReaderSurfaceView.shouldClosePhysicalMotionIntervalForTest(
                true, true, 12L, 12L));
        assertTrue(ReaderSurfaceView.shouldClosePhysicalMotionIntervalForTest(
                true, true, 11L, 12L));

        assertFalse(ReaderSurfaceView.shouldClosePhysicalMotionIntervalForTest(
                false, true, 12L, 12L));
        assertFalse(ReaderSurfaceView.shouldClosePhysicalMotionIntervalForTest(
                true, false, 12L, 12L));
        assertFalse(ReaderSurfaceView.shouldClosePhysicalMotionIntervalForTest(
                true, true, 13L, 12L));
    }

    @Test
    public void coalescedMutationsKeepOldestAndNewestWatermarks() {
        assertEquals(100L, ReaderSurfaceView.mergeOldestPixelMutationNsForTest(0L, 100L));
        assertEquals(100L, ReaderSurfaceView.mergeOldestPixelMutationNsForTest(100L, 112L));
        assertEquals(112L, ReaderSurfaceView.mergeNewestPixelMutationNsForTest(100L, 112L));
    }

    @Test
    public void stateNullDoesNotConsumePendingMutationTiming() {
        assertFalse(ReaderSurfaceView.shouldConsumePixelMutationTimingForTest(false, 7L));
        assertTrue(ReaderSurfaceView.shouldConsumePixelMutationTimingForTest(true, 7L));
    }

    @Test
    public void contentMaxShrinkRepairsOnlyRealOutOfBoundsOffset() {
        assertTrue(ReaderSurfaceView.shouldApplyContentMaxShrinkCorrectionForTest(
                50_521f, 50_448f, 50_448f));
        assertFalse(ReaderSurfaceView.shouldApplyContentMaxShrinkCorrectionForTest(
                50_400f, 50_300f, 50_448f));
        assertFalse(ReaderSurfaceView.shouldApplyContentMaxShrinkCorrectionForTest(
                50_521f, 50_500f, 50_448f));
    }

    @Test
    public void initialPrepareCoversViewportPlusCeiledOneAndHalfAhead() {
        assertEquals(5345, ReaderSurfaceView.initialSoftwarePrepareBottomForTest(0, 2138, 10000));
        assertEquals(5448, ReaderSurfaceView.initialSoftwarePrepareBottomForTest(100, 2139, 10000));
        assertEquals(5000, ReaderSurfaceView.initialSoftwarePrepareBottomForTest(0, 2138, 5000));
    }

    @Test
    public void strictOriginalMetadataAcceptsNativeWidthButRejectsUnprovenQuality() {
        ReaderPreparedStore.PreparedOriginalProof valid = originalProof(
                ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                764,
                1200,
                1,
                false);
        assertTrue(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764, 1200, valid));
        assertFalse(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764, 1200, null));
        assertFalse(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764,
                1200,
                originalProof(ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                        764, 1200, 2, false)));
        assertFalse(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764,
                1200,
                originalProof(ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                        764, 1200, 1, true)));
        assertFalse(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764,
                1200,
                originalProof(ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                        382, 600, 1, false)));
        assertFalse(ReaderSurfaceView.authoritativeOriginalProofMetadataAcceptedForTest(
                764,
                1200,
                originalProof(ReaderPreparedStore.PreparedAssetVariant.PREVIEW,
                        764, 1200, 1, false)));
    }

    private ReaderPreparedStore.PreparedOriginalProof originalProof(
            ReaderPreparedStore.PreparedAssetVariant variant,
            int width,
            int height,
            int sample,
            boolean resized
    ) {
        return new ReaderPreparedStore.PreparedOriginalProof(
                "https://example.test/p001.jpeg",
                variant,
                width,
                height,
                sample,
                resized);
    }
}
