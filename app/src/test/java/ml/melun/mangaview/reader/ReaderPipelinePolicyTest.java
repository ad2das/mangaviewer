package ml.melun.mangaview.reader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ReaderPipelinePolicyTest {
    @Test
    public void busyScrollUsesBoundedFullWidthDecodeRunway() {
        assertEquals(1, ReaderPipelinePolicy.windowBefore(true));
        assertEquals(3, ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(true));
        assertEquals(1080, ReaderPipelinePolicy.BUSY_DECODE_WIDTH);
    }

    @Test
    public void hostGpuProtectedNumericDecodeIsSerializedWithoutChangingPhysicalDevices() {
        assertEquals(1, ReaderPipelinePolicy.protectedNumericDecodeParallelism(true));
        assertEquals(12, ReaderPipelinePolicy.protectedNumericDecodeParallelism(false));
    }

    @Test
    public void idleWindowStaysBoundedAroundAnchor() {
        assertEquals(1, ReaderPipelinePolicy.windowBefore(false));
        assertTrue(ReaderPipelinePolicy.windowAfter(false) <= ReaderPipelinePolicy.windowAfter(true));
        assertEquals(4, ReaderPipelinePolicy.decodeParallelism(false));
        assertEquals(3, ReaderPipelinePolicy.IDLE_WINDOW_AFTER);
        assertEquals(1, ReaderPipelinePolicy.INITIAL_WINDOW_AFTER);
    }

    @Test
    public void ntkFarTailCannotShareTheLaunchCriticalFanoutBurst() {
        assertEquals(3, ReaderPipelinePolicy.NTK_VERIFIED_SOURCE_FANOUT_PARALLELISM);
        assertEquals(1, ReaderPipelinePolicy.NTK_PROGRESSIVE_FAR_TAIL_PARALLELISM);
        assertTrue(ReaderPipelinePolicy.isNtkLaunchCriticalSource(0, 3));
        assertTrue(ReaderPipelinePolicy.isNtkLaunchCriticalSource(3, 3));
        assertFalse(ReaderPipelinePolicy.isNtkLaunchCriticalSource(4, 3));
        assertFalse(ReaderPipelinePolicy.isNtkLaunchCriticalSource(-1, 3));
    }

    @Test
    public void strictNtkBitmapWindowTracksDirectionWithoutManifestFanout() {
        assertArrayEquals(new int[]{5, 14},
                ReaderSession.protectedNumericBitmapWindowForTest(20, 5, 1));
        assertArrayEquals(new int[]{3, 12},
                ReaderSession.protectedNumericBitmapWindowForTest(20, 12, -1));
    }

    @Test
    public void strictExactManhwaWindowNeverDropsCanonicalPageZeroForTwoPageStart() {
        assertEquals(new kotlin.ranges.IntRange(0, 21),
                ReaderSession.strictExactProtectedNumericBitmapWindowForTest(22));
        assertTrue(ReaderSession.strictExactProtectedNumericBitmapWindowForTest(0).isEmpty());
    }

    @Test
    public void strictExactAppendedEpisodeUsesDirectionalPixelsWithoutWeakeningLaunchResidency() {
        assertArrayEquals(new int[]{0, 14},
                ReaderSession.strictExactScopedBitmapWindowBoundsForTest(
                        40, 5, 1, 0, 14));
        assertArrayEquals(new int[]{0, 23},
                ReaderSession.strictExactScopedBitmapWindowBoundsForTest(
                        40, 14, 1, 0, 14));
        assertArrayEquals(new int[]{25, 34},
                ReaderSession.strictExactScopedBitmapWindowBoundsForTest(
                        40, 25, 1, -1, -1));
        assertArrayEquals(new int[]{16, 25},
                ReaderSession.strictExactScopedBitmapWindowBoundsForTest(
                        40, 25, -1, -1, -1));
        assertArrayEquals(new int[]{24, 34},
                ReaderSession.strictExactScopedBitmapWindowWithPhysicalSpanForTest(
                        40, 25, 1, -1, -1, 24, 27));
        assertArrayEquals(new int[]{16, 27},
                ReaderSession.strictExactScopedBitmapWindowWithPhysicalSpanForTest(
                        40, 25, -1, -1, -1, 24, 27));
        assertArrayEquals(new int[]{25, 34},
                ReaderSession.strictExactScopedBitmapWindowWithPhysicalSpanForTest(
                        40, 25, 1, -1, -1, 10, 9));
    }

    @Test
    public void physicalDirectionHintOpensReversePixelsBeforeTheAnchorMoves() {
        assertEquals(-1, ReaderSession.resolveWindowDirectionForTest(
                25, 25, 1, -1));
        assertEquals(1, ReaderSession.resolveWindowDirectionForTest(
                25, 25, -1, 1));
        assertEquals(-1, ReaderSession.resolveWindowDirectionForTest(
                25, 24, 1, 0));
    }

    @Test
    public void idleAnchorFallbackDoesNotInventAReverseGesture() {
        assertEquals(1, ReaderSession.resolveWindowDirectionForTest(
                0, 1, 1, 0, true));
        assertEquals(1, ReaderSession.resolveWindowDirectionForTest(
                1, 0, 1, 0, false));
        assertEquals(1, ReaderSession.resolveWindowDirectionForTest(
                0, 1, -1, 0, false));
        assertEquals(-1, ReaderSession.resolveWindowDirectionForTest(
                1, 0, 1, 0, true));
        assertEquals(-1, ReaderSession.resolveWindowDirectionForTest(
                1, 0, 1, -1, false));
    }

    @Test
    public void exactColdWindowAdmitsTheForwardRunwayBeforePhysicalDraw() {
        assertArrayEquals(new int[]{0, 19},
                ReaderPipelinePolicy.strictExactColdVisibleDemandBounds(
                        20, 5, 7, 1, false));
        assertArrayEquals(new int[]{0, 0},
                ReaderPipelinePolicy.strictExactColdVisibleDemandBounds(
                        1, 0, 0, -1, false));
    }

    @Test
    public void exactColdWindowKeepsTheWholeForwardEpisodeAfterCommit() {
        assertArrayEquals(new int[]{0, 19},
                ReaderPipelinePolicy.strictExactColdVisibleDemandBounds(
                        20, 5, 7, 1, true));
        assertArrayEquals(new int[]{0, 19},
                ReaderPipelinePolicy.strictExactColdVisibleDemandBounds(
                        20, 5, 7, -1, true));
    }

    @Test
    public void rollingAdmissionKeepsAForwardRunwayBeforePhysicalPixelsAreCommitted() {
        StrictRollingAdmission initial = StrictRollingAdmission.initial(20);
        assertTrue(initial.admitsSource(0));
        assertTrue(initial.admitsSource(1));
        assertTrue(initial.admitsSource(9));
        assertTrue(initial.admitsSource(19));

        StrictRollingAdmission stillCold = StrictRollingAdmission.update(
                initial, 20, 8, 9, 8, 9, 1, false);
        assertEquals(0, stillCold.getAllowedFirstSource());
        assertEquals(19, stillCold.getAllowedLastSource());
        assertTrue(stillCold.admitsSource(8));

        StrictRollingAdmission committed = StrictRollingAdmission.update(
                stillCold, 20, 5, 7, 5, 7, 1, true);
        assertEquals(0, committed.getAllowedFirstSource());
        assertEquals(19, committed.getAllowedLastSource());
        assertTrue(committed.admitsSource(5));
        assertTrue(committed.admitsSource(9));
        assertTrue(committed.admitsSource(17));
    }

    @Test
    public void rollingAdmissionUsesRestoredPageAsInitialVisibleAnchor() {
        StrictRollingAdmission restored = StrictRollingAdmission.initial(112, 67, 67);

        assertEquals(67, restored.getVisibleFirstDisplay());
        assertEquals(67, restored.getVisibleLastDisplay());
        assertEquals(67, restored.getAllowedFirstSource());
        assertFalse(restored.admitsSource(0));
        assertTrue(restored.admitsSource(67));
        assertTrue(restored.admitsSource(111));

        StrictRollingAdmission committed = StrictRollingAdmission.update(
                restored, 112, 67, 69, 67, 69, 1, true);
        assertEquals(67, committed.getAllowedFirstSource());
        assertFalse(committed.admitsSource(66));
        assertTrue(committed.admitsSource(111));
    }

    @Test
    public void provenReverseGestureMonotonicallyWidensOnlyThreePredecessorSources() {
        StrictRollingAdmission restored = StrictRollingAdmission.initial(112, 67, 67);
        StrictRollingAdmission committed = StrictRollingAdmission.update(
                restored, 112, 67, 69, 67, 69, 1, true);

        StrictRollingAdmission reverse = StrictRollingAdmission.update(
                committed, 112, 66, 68, 66, 68, -1, true, true, 63);
        assertEquals(-1, reverse.getDirection());
        assertEquals(63, reverse.getAllowedFirstSource());
        assertTrue(reverse.admitsSource(63));
        assertFalse(reverse.admitsSource(62));

        StrictRollingAdmission deeperReverse = StrictRollingAdmission.update(
                reverse, 112, 60, 62, 60, 62, -1, true, true, 57);
        assertEquals(57, deeperReverse.getAllowedFirstSource());

        StrictRollingAdmission forward = StrictRollingAdmission.update(
                deeperReverse, 112, 70, 72, 70, 72, 1, true, false);
        assertEquals(57, forward.getAllowedFirstSource());
        assertTrue(forward.admitsSource(57));
    }

    @Test
    public void busyPhysicalReverseStillWidensWhenTheExplicitDirectionHintWasCoalesced() {
        assertEquals(17, StrictRollingAdmission.observedPhysicalReverseFloor(
                27, 20, -1, true));
        assertEquals(27, StrictRollingAdmission.observedPhysicalReverseFloor(
                27, 20, -1, false));
        assertEquals(27, StrictRollingAdmission.observedPhysicalReverseFloor(
                27, 20, 1, true));
        assertEquals(0, StrictRollingAdmission.observedPhysicalReverseFloor(
                2, 1, -1, true));
    }

    @Test
    public void reverseSoftDemandPrefersNearestPredecessorButForwardPrefersRunway() {
        StrictRollingAdmission restored = StrictRollingAdmission.initial(112, 67, 67);
        StrictRollingAdmission committed = StrictRollingAdmission.update(
                restored, 112, 67, 69, 67, 69, 1, true);
        StrictRollingAdmission reverse = StrictRollingAdmission.update(
                committed, 112, 66, 67, 66, 67, -1, true, true, 63);

        assertEquals(Arrays.asList(65, 64, 63),
                reverse.orderedSoftSources(Arrays.asList(66, 67)).subList(0, 3));

        StrictRollingAdmission forward = StrictRollingAdmission.update(
                reverse, 112, 70, 71, 70, 71, 1, true, false);
        assertEquals(Arrays.asList(72, 73, 74),
                forward.orderedSoftSources(Arrays.asList(70, 71)).subList(0, 3));
    }

    @Test
    public void repeatedCommittedViewportDoesNotAdvanceDemandEpoch() {
        StrictRollingAdmission initial = StrictRollingAdmission.initial(20);
        assertTrue(initial.shouldOpenPhysicalDrawGate());
        StrictRollingAdmission firstColdDemand = StrictRollingAdmission.update(
                initial, 20, 0, 0, 0, 0, 1, false);
        assertSame(initial, firstColdDemand);
        assertEquals(0L, firstColdDemand.getEpoch());
        StrictRollingAdmission repeatedColdDemand = StrictRollingAdmission.update(
                firstColdDemand, 20, 9, 10, 9, 10, -1, false);
        assertSame(firstColdDemand, repeatedColdDemand);

        StrictRollingAdmission committed = StrictRollingAdmission.update(
                firstColdDemand, 20, 5, 7, 5, 7, 1, true);
        assertFalse(committed.shouldOpenPhysicalDrawGate());
        StrictRollingAdmission repeatedCommit = StrictRollingAdmission.update(
                committed, 20, 5, 7, 5, 7, 1, true);
        assertSame(committed, repeatedCommit);
        assertEquals(committed.getEpoch(), repeatedCommit.getEpoch());

    }

    @Test
    public void visibleBoundaryJitterUpdatesPixelWindowWithoutRestartingSourceDemand() {
        StrictRollingAdmission initial = StrictRollingAdmission.initial(77, 25, 25);
        StrictRollingAdmission committed = StrictRollingAdmission.update(
                initial, 77, 25, 28, 25, 28, 1, true);
        StrictRollingAdmission widerVisible = StrictRollingAdmission.update(
                committed, 77, 25, 30, 25, 30, 1, true);

        assertFalse(committed == widerVisible);
        assertEquals(30, widerVisible.getVisibleLastDisplay());
        assertEquals(committed.getEpoch(), widerVisible.getEpoch());
        assertTrue(committed.hasSameSourceDemand(widerVisible));

        StrictRollingAdmission reverse = StrictRollingAdmission.update(
                widerVisible, 77, 24, 27, 24, 27, -1, true, true, 21);
        assertEquals(committed.getEpoch() + 1L, reverse.getEpoch());
        assertFalse(widerVisible.hasSameSourceDemand(reverse));
    }

    @Test
    public void terminalEdgeRequiresFullRealPixelCoverageAndNoPermanentError() {
        String clean = ReaderPipelinePolicy.strictViewportDefectReasons(
                2139, 2139, 2139, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(clean.isEmpty());
        assertTrue(ReaderPipelinePolicy.isStrictBottomEdgeEligible(5, 4, clean));

        String unresolved = ReaderPipelinePolicy.strictViewportDefectReasons(
                2139, 2139, 1500, 639, 0, 0, 0, 0, 0, 0);
        assertTrue(unresolved.contains("drawableShort"));
        assertTrue(unresolved.contains("missing"));
        assertFalse(ReaderPipelinePolicy.isStrictBottomEdgeEligible(5, 4, unresolved));

        String permanentError = ReaderPipelinePolicy.strictViewportDefectReasons(
                2139, 2139, 2139, 0, 0, 0, 1, 0, 0, 0);
        assertTrue(permanentError.contains("error"));
        assertFalse(ReaderPipelinePolicy.isStrictBottomEdgeEligible(5, 4, permanentError));
    }

    @Test
    public void transitionCardIsAColdDefectButValidAfterActualImageCommit() {
        assertEquals(1, ReaderPipelinePolicy.strictTransitionCardDefectCount(1, false));
        assertEquals(0, ReaderPipelinePolicy.strictTransitionCardDefectCount(1, true));
        assertEquals(0, ReaderPipelinePolicy.strictTransitionCardDefectCount(0, false));
        assertEquals(0, ReaderPipelinePolicy.strictTransitionCardDefectCount(1, false, true));
        assertEquals(1, ReaderPipelinePolicy.strictTransitionCardDefectCount(1, false, false));
    }

    @Test
    public void shortExactTerminalTailIsActualOnlyForTheNaturalHeightShortfall() {
        assertTrue(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 820, 820, "viewportShort|drawableShort"));
        assertTrue(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 1398, 1399, "viewportShort|drawableShort"));

        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                false, 2340, 820, 820, "viewportShort|drawableShort"));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 820, 818, "viewportShort|drawableShort"));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 820, 820, "viewportShort|drawableShort|missing"));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 820, 820, "viewportShort|drawableShort|card"));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailActualFrame(
                true, 2340, 2340, 2340, ""));
    }

    @Test
    public void exactTerminalTailSourceSequenceAllowsUnorderedFetchButNotVisibleSourceGaps() {
        assertTrue(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 18, 20, new int[]{18, 19}));
        assertTrue(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 18, 20, new int[]{18, 18, 19}));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 18, 20, new int[]{18}));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 18, 20, new int[]{18, 20}));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 18, 20, new int[]{19, 18, 19}));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                false, 18, 20, new int[]{18, 19}));
        assertFalse(ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence(
                true, 0, 20, new int[]{18, 19}));
    }

    @Test
    public void autoCutDisplayIndexesNeverReplaceCanonicalSourceIndexes() {
        StrictRollingAdmission initial = StrictRollingAdmission.initial(20);
        StrictRollingAdmission splitDisplay = StrictRollingAdmission.update(
                initial,
                20,
                10,
                12,
                5,
                6,
                1,
                true);

        assertEquals(10, splitDisplay.getVisibleFirstDisplay());
        assertEquals(12, splitDisplay.getVisibleLastDisplay());
        assertEquals(0, splitDisplay.getAllowedFirstSource());
        assertEquals(19, splitDisplay.getAllowedLastSource());
        assertTrue(splitDisplay.admitsSource(10));
        assertTrue(splitDisplay.admitsSource(16));
    }

    @Test
    public void strictRunwayKeepsEveryForwardSourceForLongEpisodes() {
        int[] forward = ReaderPipelinePolicy.strictExactColdVisibleDemandBounds(
                100, 40, 42, 1, true);

        assertArrayEquals(new int[]{0, 99}, forward);
    }

    @Test
    public void initialBlankMetricStopsAtTheFirstValidPhysicalFrame() {
        assertTrue(ReaderPipelinePolicy.shouldCountStrictInitialBlankFrame(false, true));
        assertFalse(ReaderPipelinePolicy.shouldCountStrictInitialBlankFrame(false, false));
        assertFalse(ReaderPipelinePolicy.shouldCountStrictInitialBlankFrame(true, true));
    }

    @Test
    public void actualImageRequiresACommittedFrameWithNonZeroMatchingPageTableIdentity() {
        assertFalse(strictCommittedFrameValid(0L, 0L));
        assertFalse(strictCommittedFrameValid(2L, 3L));
        assertTrue(strictCommittedFrameValid(2L, 2L));
    }

    @Test
    public void drawableCoverageCannotReplaceHardwareCommitProof() {
        assertFalse(ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, false, true, false,
                7L, 3L, 3L, 2L, 2L,
                true, false));
        assertFalse(ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, true, true, false,
                0L, 3L, 3L, 2L, 2L,
                true, false));
    }

    @Test
    public void postedFallbackCannotReplaceRegisteredHwuiFrameCommitCallbackProof() {
        assertFalse(ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, true, false, false,
                7L, 3L, 3L, 2L, 2L,
                true, false));
    }

    @Test
    public void dedicatedSurfaceRequiresOneSuccessfulQueueSubmissionProof() {
        assertTrue(ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, false, false, true,
                7L, 3L, 3L, 2L, 2L,
                true, false));
        assertFalse(ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, true, true, true,
                7L, 3L, 3L, 2L, 2L,
                true, false));
    }

    @Test
    public void requestTelemetrySeparatesRangeProbeFromFullAssetBody() {
        String image = "https://cdn.example.test/webtoon/1/2/p001.webp";
        String full = ReaderImageCache.imageTelemetrySourceKeyForTest(1, image, null);
        String fullAgain = ReaderImageCache.imageTelemetrySourceKeyForTest(1, image, "");
        String range = ReaderImageCache.imageTelemetrySourceKeyForTest(
                1, image, "bytes=0-8191");
        String normalizedRange = ReaderImageCache.imageTelemetrySourceKeyForTest(
                1, image, " BYTES = 0 - 8191 ");
        String otherRange = ReaderImageCache.imageTelemetrySourceKeyForTest(
                1, image, "bytes=8192-16383");

        assertEquals(full, fullAgain);
        assertEquals(range, normalizedRange);
        assertFalse(full.equals(range));
        assertFalse(range.equals(otherRange));
    }

    @Test
    public void heightResolveAdjustsOnlyPagesFullyAboveViewport() {
        assertTrue(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, false, 1200f, 1000f));
    }

    @Test
    public void heightResolveWaitsUntilRecentScrollSettles() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, true, 900f, 1000f));
    }

    @Test
    public void heightResolveDoesNotCorrectScrollDuringActiveInputOrFling() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, true, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, true, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, false, false, 900f, 1000f));
    }

    @Test
    public void pendingResolveAnchorRestoreWaitsForStoppedInput() {
        assertTrue(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, true));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                true, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, true, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, true, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, false, false));
    }

    @Test
    public void preparedAutoCutOnlySplitsWideSpreadPages() {
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 720, 1600));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, false, 1600, 1200));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(false, true, 1600, 1200));
        assertTrue(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 1600, 1200));
    }

    @Test
    public void earlyNtkPartialSubsetIsEligibleForNonShrinkingMerge() {
        List<String> full = generatedNtkWebtoonImages(57);
        List<String> partial = full.subList(0, 3);

        assertTrue(ReaderImageCache.shouldReplaceWithVerifiedGeneratedSubsetForTest(full, partial));
    }

    @Test
    public void strictExactOriginalTilesCannotBeSuppressedBeforeSurfaceInstall() {
        assertTrue(ReaderSession.mustInstallStrictAuthoritativeTilesForTest(
                true, true, 21, 0, 21));
        assertFalse(ReaderSession.mustInstallStrictAuthoritativeTilesForTest(
                false, true, 21, 0, 21));
        assertFalse(ReaderSession.mustInstallStrictAuthoritativeTilesForTest(
                true, false, 21, 0, 21));
        assertFalse(ReaderSession.mustInstallStrictAuthoritativeTilesForTest(
                true, true, 22, 0, 21));
    }

    @Test
    public void signedNtkDescriptorFontsRemainNativeImageSlots() {
        assertTrue(ReaderImageCache.isNtkSignedDescriptorImageUrlForTest(
                "https://f1spard.site/token/qc/page-8.woff#mvpage=8"));
        assertTrue(ReaderImageCache.isNtkSignedDescriptorImageUrlForTest(
                "https://shaomoi.org/token/rs/page-9.woff2#mvpage=9"));
        assertFalse(ReaderImageCache.isNtkSignedDescriptorImageUrlForTest(
                "https://f1spard.site/assets/font.woff2"));
    }

    private static List<String> generatedNtkWebtoonImages(int count) {
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= count; page++) {
            images.add(String.format(Locale.ROOT,
                    "https://fifa.worldcup73.xyz/black/episodes/12046/1186913/p%03d.jpg",
                    page));
        }
        return images;
    }

    private static boolean strictCommittedFrameValid(long proofEpoch, long currentEpoch) {
        return ReaderPipelinePolicy.isStrictCommittedFrameValid(
                true, true, true, true, true, false,
                7L, 3L, 3L, proofEpoch, currentEpoch,
                true, false);
    }
}
