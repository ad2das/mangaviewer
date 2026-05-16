package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainWebtoonAdapterTest {
    @Test
    public void validPositionRejectsOutOfRangePositions() {
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), -1));
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void validPositionRejectsMissingRows() {
        assertFalse(AdapterPositionGuard.isValidPositionForTest(null, 0));
        assertFalse(AdapterPositionGuard.isValidPositionForTest(Collections.emptyList(), 0));
    }

    @Test
    public void validPositionAcceptsExistingRow() {
        assertTrue(AdapterPositionGuard.isValidPositionForTest(Arrays.asList("a", "b"), 1));
    }

    @Test
    public void firstValidTitleSkipsMissingAndInvalidTitles() {
        ArrayList<Title> titles = new ArrayList<>();
        titles.add(null);
        titles.add(new Title("invalid", "", "", Collections.emptyList(), "", 0, base_webtoon));
        titles.add(new Title("valid", "", "", Collections.emptyList(), "", 12, base_webtoon));

        assertEquals(12, HomeTitleSelector.firstValidTitleForTest(titles).getId());
    }

    @Test
    public void sectionFailurePolicyKeepsLoadingAfterSingleSectionFailure() {
        assertFalse(HomeSectionFetchFailurePolicy.shouldAbortForTest(new RuntimeException("section failed"), false));
        assertTrue(HomeSectionFetchFailurePolicy.shouldAbortForTest(new RuntimeException("cancelled"), true));
    }

    @Test
    public void emptyNtkFetchOpensCaptchaEvenWhenPlaceholderIsVisible() {
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(true, true));
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(true, false));
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(false, false));
        assertFalse(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(false, true));
    }

    @Test
    public void visibleEpisodeSnapshotPrefetchMatchesCurrentSite() {
        assertTrue(HomeEpisodePrefetchPolicy.shouldPrefetchVisibleEpisodeSnapshot("wfwf", false));
        assertTrue(HomeEpisodePrefetchPolicy.shouldPrefetchVisibleEpisodeSnapshot("ntk", true));
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchVisibleEpisodeSnapshot("wfwf", true));
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchVisibleEpisodeSnapshot("ntk", false));
        assertTrue(HomeEpisodePrefetchPolicy.shouldPrefetchVisibleEpisodeSnapshot("", false));
    }

    @Test
    public void visibleContinueWarmupKeepsHomeDecodePressureLow() {
        assertEquals(2, HomeContinueWarmupPolicy.visibleContinueWarmupLimitForTest(false));
        assertEquals(1, HomeContinueWarmupPolicy.visibleContinueWarmupLimitForTest(true));
        assertEquals(1100L, HomeContinueWarmupPolicy.visibleHomeWarmupDelayMsForTest(false));
        assertEquals(1500L, HomeContinueWarmupPolicy.visibleHomeWarmupDelayMsForTest(true));
    }
}
