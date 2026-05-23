package ml.melun.mangaview.activity;

import org.junit.Test;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerActivityTest {
    @Test
    public void validEpisodePickerPositionRejectsOutOfRangeRows() {
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validEpisodePickerPositionRejectsMissingData() {
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(null, 0));
        assertFalse(ViewerActivity.isValidEpisodePickerPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validEpisodePickerPositionAcceptsExistingRow() {
        assertTrue(ViewerActivity.isValidEpisodePickerPositionForTest(Arrays.asList("a", "b"), 1));
    }

    @Test
    public void episodePickerStableIdAvoidsNameParsingForKnownIds() {
        Manga first = new Manga(91, "서머타임 렌더링 91화", "", 0);
        first.setTitleId(7843);
        Manga second = new Manga(91, "서머타임 렌더링 87화", "", 0);
        second.setTitleId(7843);

        assertEquals(ViewerActivity.fastEpisodeStableIdForTest(first, 0),
                ViewerActivity.fastEpisodeStableIdForTest(second, 1));
    }

    @Test
    public void ntkBackgroundNextEpisodeFetchSkipsOnlyWhenSourceIsUnknown() {
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", true, true, false));
        assertTrue(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest(null, true, false, false));
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", false, false, false));
    }

    @Test
    public void backgroundNextEpisodeFetchKeepsLoadedOrLegacyEpisodes() {
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", true, true, true));
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("", false, false, false));
    }

    @Test
    public void wfwfBackgroundNextEpisodeFetchWarmsMissingImages() {
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("wfwf", false, false, false));
    }

    @Test
    public void wfwfViewerKeepsExtraRowsReadyWithoutDataSaver() {
        assertEquals(12, ViewerActivity.viewerItemViewCacheSizeForTest("wfwf", false));
        assertEquals(12, ViewerActivity.viewerItemViewCacheSizeForTest("ntk", false));
        assertEquals(6, ViewerActivity.viewerItemViewCacheSizeForTest("wfwf", true));
        assertEquals(4, ViewerActivity.viewerInitialPrefetchItemCountForTest(false));
    }

    @Test
    public void boundaryLoadErrorsDoNotCloseExistingViewerContent() {
        assertTrue(ViewerActivity.shouldSuppressBoundaryLoadErrorForTest(false, true));
        assertFalse(ViewerActivity.shouldSuppressBoundaryLoadErrorForTest(true, true));
        assertFalse(ViewerActivity.shouldSuppressBoundaryLoadErrorForTest(false, false));
    }

    @Test
    public void emptyLoadResultIsRecoveredWhenImagesArrivedBeforeFinish() {
        assertTrue(ViewerActivity.shouldRecoverEmptyLoadResultForTest(ViewerWarmupManager.LOAD_EMPTY_IMAGES, true));
        assertTrue(ViewerActivity.shouldRecoverEmptyLoadResultForTest(ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING, true));
        assertFalse(ViewerActivity.shouldRecoverEmptyLoadResultForTest(ViewerWarmupManager.LOAD_EMPTY_IMAGES, false));
        assertFalse(ViewerActivity.shouldRecoverEmptyLoadResultForTest(0, true));
    }

    @Test
    public void displayedPageIndexParsesToolbarPageText() {
        assertEquals(0, ViewerActivity.displayedPageIndexForTest("1/22"));
        assertEquals(21, ViewerActivity.displayedPageIndexForTest(" 22 / 22 "));
        assertEquals(-1, ViewerActivity.displayedPageIndexForTest("-/-"));
        assertEquals(-1, ViewerActivity.displayedPageIndexForTest("0/22"));
        assertEquals(-1, ViewerActivity.displayedPageIndexForTest(null));
    }

    @Test
    public void initialViewerPreloadStartsOnNextFrame() {
        assertTrue(ViewerActivity.initialViewerPreloadDelayMsForTest() <= 40L);
    }

    @Test
    public void initialViewerPreloadBudgetStaysConservative() {
        assertTrue(ViewerActivity.initialPreloadAheadCountForTest() >= 24);
    }

    @Test
    public void nextEpisodePrefetchWaitsForViewerPipeline() {
        assertTrue(ViewerActivity.initialNextEpisodePrefetchDelayMsForTest() >=
                ViewerActivity.viewerPipelineStartDelayMsForTest());
        assertTrue(ViewerActivity.initialNextEpisodePrefetchDelayMsForTest() <= 900L);
    }

    @Test
    public void nextEpisodePrefetchChainsAcrossShortChapters() {
        assertTrue(ViewerActivity.nextEpisodePrefetchChainDepthForTest(false) >= 5);
        assertTrue(ViewerActivity.nextEpisodePrefetchChainDepthForTest(true) >= 1);
    }

    @Test
    public void transientEmptyViewerRetryStaysShort() {
        assertTrue(ViewerActivity.transientEmptyFirstFrameRetryDelayMsForTest() >= 400L);
        assertTrue(ViewerActivity.transientEmptyFirstFrameRetryDelayMsForTest() <= 800L);
    }

    @Test
    public void missingEpisodePromptDetectsSkippedNextNumbers() {
        assertTrue(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "서머타임 렌더링 73화", "서머타임 렌더링 76화"));
    }

    @Test
    public void missingEpisodePromptTreatsHyphenPartAsSameChapterNotRange() {
        assertTrue(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "마왕의 딸은 너무 착해!! 1화", "마왕의 딸은 너무 착해!! 11-2화"));
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "마왕의 딸은 너무 착해!! 11-1화", "마왕의 딸은 너무 착해!! 11-2화"));
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "마왕의 딸은 너무 착해!! 11-2화", "마왕의 딸은 너무 착해!! 12화"));
    }

    @Test
    public void missingEpisodePromptAllowsPackedEpisodeRanges() {
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "서머타임 렌더링 03, 04화", "서머타임 렌더링 05, 06화"));
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "서머타임 렌더링 05, 06화", "서머타임 렌더링 07화"));
    }

    @Test
    public void missingEpisodePromptIgnoresSpecialEpisodes() {
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "서머타임 렌더링 번외편 3~4화 + 2권 부록", "서머타임 렌더링 05화"));
        assertFalse(MissingEpisodeNavigator.shouldPromptMissingNextEpisodeForTest(
                "서머타임 렌더링 36화", "서머타임 렌더링 번외편 5~7화"));
    }

    @Test
    public void missingEpisodeBoundaryPromptWaitsForExplicitBottomJump() {
        assertFalse(ViewerActivity.shouldPromptMissingEpisodeAtBoundaryForTest(false, true));
        assertTrue(ViewerActivity.shouldPromptMissingEpisodeAtBoundaryForTest(true, true));
        assertFalse(ViewerActivity.shouldPromptMissingEpisodeAtBoundaryForTest(true, false));
    }

    @Test
    public void missingEpisodePromptDoesNotUseNormalNextAttachThreshold() {
        assertTrue(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(70, 98, 120, 22));
        assertFalse(ViewerActivity.shouldPromptMissingEpisodeAtBoundaryForTest(false, true));
    }

    @Test
    public void normalNextEpisodeAttachDoesNotTriggerAtFirstPageOfShortEpisode() {
        assertFalse(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(0, 19, 22));
        assertFalse(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(0, 18, 19, 22));
        assertTrue(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(15, 19, 22));
        assertTrue(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(14, 18, 19, 22));
        assertFalse(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(96, 120, 22));
        assertTrue(ViewerActivity.shouldAttachNextEpisodeAtPositionForTest(98, 120, 22));
    }

    @Test
    public void bottomBoundaryIgnoresInitialFullSpanLayout() {
        assertFalse(ViewerActivity.shouldTreatAsViewerBottomForTest(0, 18, 19));
        assertTrue(ViewerActivity.shouldTreatAsViewerBottomForTest(14, 18, 19));
    }

    @Test
    public void boundaryEpisodeLoadsWaitForIdleScroll() {
        assertTrue(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_IDLE));
        assertFalse(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_DRAGGING));
        assertFalse(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_SETTLING));
        assertTrue(ViewerActivity.boundaryLoadIdleDelayMsForTest() >= ViewerActivity.initialViewerPreloadDelayMsForTest());
    }

    @Test
    public void busyScrollAnchorDispatchIsThrottledWithoutCappingFling() {
        assertTrue(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(RecyclerView.NO_POSITION, 10, 0L));
        assertFalse(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(10, 11, 20L));
        assertFalse(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(10, 12, 20L));
        assertTrue(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(10, 14, 20L));
        assertFalse(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(10, 11, 120L));
        assertTrue(ViewerActivity.shouldDispatchBusyScrollAnchorForTest(10, 11, 180L));
    }

    @Test
    public void bookmarkSaveWaitsForIdleScroll() {
        assertTrue(ViewerActivity.shouldScheduleScrollBookmarkSaveForTest(RecyclerView.SCROLL_STATE_IDLE));
        assertFalse(ViewerActivity.shouldScheduleScrollBookmarkSaveForTest(RecyclerView.SCROLL_STATE_DRAGGING));
        assertFalse(ViewerActivity.shouldScheduleScrollBookmarkSaveForTest(RecyclerView.SCROLL_STATE_SETTLING));
    }

    @Test
    public void failedBoundaryEpisodeLoadsBackOffBeforeRetry() {
        assertTrue(ViewerActivity.boundaryLoadFailureCooldownMsForTest() >= 1000L);
    }

    @Test
    public void boundaryPipelineFallsBackQuicklyIfEpisodeWasNotReady() {
        assertTrue(ViewerActivity.pipelineBoundaryFallbackDelayMsForTest() <= 160L);
    }

    @Test
    public void viewerPipelineStartsAfterInitialLayoutCanDraw() {
        assertTrue(ViewerActivity.viewerPipelineStartDelayMsForTest() >= 250L);
        assertTrue(ViewerActivity.viewerPipelineStartDelayMsForTest() <= 400L);
    }

    @Test
    public void automaticNextAppendShowsPreviewBeforeFullQualityPromotion() {
        assertTrue(ViewerActivity.autoAppendPreviewOnlyMsForTest() >= 1500L);
        assertTrue(ViewerActivity.autoAppendPreviewOnlyMsForTest() <= 4000L);
    }

    @Test
    public void initialViewerGuardDefersBackgroundWorkUntilFirstDrawSettles() {
        assertTrue(ViewerActivity.initialBackgroundWorkGuardMsForTest() >= 1000L);
        assertTrue(ViewerActivity.initialBackgroundWorkGuardMsForTest() <= 1800L);
        assertTrue(ViewerActivity.shouldHoldInitialBackgroundWorkForTest(false, 100L, 200L));
        assertFalse(ViewerActivity.shouldHoldInitialBackgroundWorkForTest(true, 100L, 200L));
        assertFalse(ViewerActivity.shouldHoldInitialBackgroundWorkForTest(false, 200L, 200L));
    }

    @Test
    public void initialResumeTopOverwriteKeepsExistingBookmarkUntilUserDrags() {
        assertTrue(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                true, false, 0, PageItem.FIRST, 7, 0, PageItem.FIRST));
        assertTrue(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                true, false, 0, PageItem.FIRST, 0, -240, PageItem.FIRST));
        assertTrue(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                true, false, 0, PageItem.FIRST, 0, 0, PageItem.SECOND));
    }

    @Test
    public void initialResumeTopOverwriteAllowsUserOrNonResumeSaves() {
        assertFalse(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                true, true, 0, PageItem.FIRST, 7, 0, PageItem.FIRST));
        assertFalse(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                false, false, 0, PageItem.FIRST, 7, 0, PageItem.FIRST));
        assertFalse(ViewerActivity.shouldSkipInitialTopBookmarkOverwriteForTest(
                true, false, 1, PageItem.FIRST, 7, 0, PageItem.FIRST));
    }
}
