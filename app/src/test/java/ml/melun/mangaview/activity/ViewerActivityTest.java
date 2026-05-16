package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.glide.ViewerWarmupManager;

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
    public void ntkBackgroundNextEpisodeFetchSkipsWhenImagesAreMissing() {
        assertTrue(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", true, true, false));
        assertTrue(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest(null, true, false, false));
        assertTrue(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", false, false, false));
    }

    @Test
    public void backgroundNextEpisodeFetchKeepsLoadedOrLegacyEpisodes() {
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("ntk", true, true, true));
        assertFalse(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("", false, false, false));
    }

    @Test
    public void wfwfBackgroundNextEpisodeFetchSkipsWhenImagesAreMissing() {
        assertTrue(ViewerActivity.shouldSkipBackgroundNextEpisodeFetchForTest("wfwf", false, false, false));
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
}
