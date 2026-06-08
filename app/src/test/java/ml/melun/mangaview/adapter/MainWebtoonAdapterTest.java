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
    public void sectionFailurePolicyDoesNotReportExpectedRequestFailures() {
        assertFalse(HomeSectionFetchFailurePolicy.shouldReportForTest(new Exception("Request failed: /cm")));
        assertFalse(HomeSectionFetchFailurePolicy.shouldReportForTest(new Exception("Cloudflare challenge")));
        assertTrue(HomeSectionFetchFailurePolicy.shouldReportForTest(new RuntimeException("unexpected")));
    }

    @Test
    public void emptyNtkFetchOpensCaptchaOnlyForObservedCloudflareChallenge() {
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(true, true, true));
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(true, false, true));
        assertFalse(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(true, false, false));
        assertTrue(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(false, false, false));
        assertFalse(HomeCaptchaPolicy.shouldOpenCaptchaOnEmptyFetch(false, true, false));
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
    public void ntkHomeEpisodePrefetchSkipsViewerImageDecode() {
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchViewerImagesFromHome("ntk", true));
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchViewerImagesFromHome("", true));
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchViewerImagesFromHome("wfwf", true));
        assertFalse(HomeEpisodePrefetchPolicy.shouldPrefetchViewerImagesFromHome("ntk", false));
    }

    @Test
    public void visibleContinueWarmupPrimesVisibleCards() {
        assertEquals(3, HomeContinueWarmupPolicy.visibleContinueWarmupLimitForTest(false));
        assertEquals(1, HomeContinueWarmupPolicy.visibleContinueWarmupLimitForTest(true));
        assertEquals(0L, HomeContinueWarmupPolicy.visibleHomeWarmupDelayMsForTest(false));
        assertEquals(0L, HomeContinueWarmupPolicy.visibleHomeWarmupDelayMsForTest(true));
    }

    @Test
    public void titleKeySeparatesSourcesWithSameIdAndMode() {
        Title ntk = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        ntk.setSourceSite("ntk");
        Title wfwf = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        wfwf.setSourceSite("wfwf");

        assertFalse(HomeTitleKeyPolicy.titleKey(ntk, null)
                .equals(HomeTitleKeyPolicy.titleKey(wfwf, null)));
    }

    @Test
    public void titleKeyNormalizesKnownSourceAliases() {
        Title ntk = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        ntk.setSourceSite("https://sbxh4.com");
        Title wfwf = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        wfwf.setSourceSite("https://wfwf455.com");

        assertTrue(HomeTitleKeyPolicy.titleKey(ntk, null).startsWith("ntk:"));
        assertTrue(HomeTitleKeyPolicy.titleKey(wfwf, null).startsWith("wfwf:"));
    }

    @Test
    public void containsTitleKeepsMixedSourceDuplicatesSeparate() {
        Title staleWfwf = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        staleWfwf.setSourceSite("wfwf");
        Title resolvedNtk = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        resolvedNtk.setSourceSite("ntk");

        assertFalse(HomeTitleKeyPolicy.containsTitle(
                Collections.singletonList(staleWfwf), resolvedNtk, null));
    }

    @Test
    public void containsTitleDedupesSameSourceSameName() {
        Title first = new Title("same", "", "", Collections.emptyList(), "", 12, base_webtoon);
        first.setSourceSite("ntk");
        Title second = new Title("same", "", "", Collections.emptyList(), "", 99, base_webtoon);
        second.setSourceSite("ntk");

        assertTrue(HomeTitleKeyPolicy.containsTitle(
                Collections.singletonList(first), second, null));
    }
}
