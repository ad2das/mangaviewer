package ml.melun.mangaview.activity;

import org.junit.Test;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.glide.ViewerWarmupManager;
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
        assertEquals(18, ViewerActivity.viewerItemViewCacheSizeForTest("wfwf", false));
        assertEquals(22, ViewerActivity.viewerItemViewCacheSizeForTest("ntk", false));
        assertEquals(8, ViewerActivity.viewerItemViewCacheSizeForTest("wfwf", true));
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
        assertTrue(ViewerActivity.initialPreloadAheadCountForTest() <= 12);
    }

    @Test
    public void nextEpisodePrefetchStartsSoonAfterInitialFrameSettles() {
        assertTrue(ViewerActivity.initialNextEpisodePrefetchDelayMsForTest() >= 250L);
        assertTrue(ViewerActivity.initialNextEpisodePrefetchDelayMsForTest() <= 500L);
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
    public void boundaryEpisodeLoadsWaitForIdleScroll() {
        assertTrue(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_IDLE));
        assertFalse(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_DRAGGING));
        assertFalse(ViewerActivity.shouldCheckBoundaryDuringScrollStateForTest(RecyclerView.SCROLL_STATE_SETTLING));
        assertTrue(ViewerActivity.boundaryLoadIdleDelayMsForTest() >= ViewerActivity.initialViewerPreloadDelayMsForTest());
    }

    @Test
    public void failedBoundaryEpisodeLoadsBackOffBeforeRetry() {
        assertTrue(ViewerActivity.boundaryLoadFailureCooldownMsForTest() >= 1000L);
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
