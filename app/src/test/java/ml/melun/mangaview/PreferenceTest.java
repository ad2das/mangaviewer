package ml.melun.mangaview;

import org.junit.Test;

import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ml.melun.mangaview.mangaview.MTitle;

public class PreferenceTest {
    @Test
    public void siteUrlNormalizationPreservesNtkPreset() {
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://ntk01.com"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://ntk01.com/cm"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://ntk01.com/manhwa"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://sbxh1.com"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://sbxh1.com/cm"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://sbxh4.com/manhwa"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest(NTK_COMIC_URL));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://ntk01.com"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://ntk01.com/manhwa"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://sbxh1.com/manhwa"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://sbxh4.com/manhwa"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest(NTK_WEBTOON_URL));
    }

    @Test
    public void siteUrlNormalizationKeepsDynamicNtkHost() {
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest(NTK_WEBTOON_URL));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest(NTK_COMIC_URL));
        assertEquals("https://newto03.com/manhwa", Preference.normalizeComicUrlForTest("https://newto03.com"));
        assertEquals("https://newto03.com", Preference.normalizeWebtoonUrlForTest("https://newto03.com/manhwa"));
        assertEquals("https://toonflix.app/manhwa", Preference.normalizeComicUrlForTest("https://toonflix.app"));
        assertEquals("https://toonflix.app", Preference.normalizeWebtoonUrlForTest("https://toonflix.app/manhwa"));
    }

    @Test
    public void siteUrlNormalizationRepairsWolfManhwaPath() {
        assertEquals("https://wfwf455.com/cm", Preference.normalizeComicUrlForTest("https://wfwf455.com/manhwa"));
        assertEquals("https://wfwf455.com", Preference.normalizeWebtoonUrlForTest("https://wfwf455.com/manhwa"));
    }

    @Test
    public void siteUrlNormalizationKeepsStoredDynamicNtkRoot() {
        assertEquals("https://odd-address.example/manhwa",
                Preference.normalizeComicUrlForTest("https://odd-address.example", "https://odd-address.example"));
        assertEquals("https://odd-address.example",
                Preference.normalizeWebtoonUrlForTest("https://odd-address.example/manhwa", "https://odd-address.example"));
    }

    @Test
    public void wfwfPresetForceIsExplicitlyDetectable() {
        assertFalse(Preference.needsWfwfSitePresetForTest(DEFAULT_COMIC_URL, DEFAULT_COMIC_URL, WEBTOON_URL));
        assertFalse(Preference.needsWfwfSitePresetForTest(NTK_COMIC_URL, NTK_COMIC_URL, NTK_WEBTOON_URL));
        assertTrue(Preference.needsWfwfSitePresetForTest(DEFAULT_COMIC_URL, NTK_COMIC_URL, WEBTOON_URL));
    }

    @Test
    public void forcedWfwfPresetKeepsRememberedResolvedRoot() {
        assertEquals("https://wfwf455.com",
                Preference.resolvedWfwfRootForTest("https://wfwf455.com", NTK_WEBTOON_URL, NTK_COMIC_URL, NTK_COMIC_URL));
    }

    @Test
    public void ntkRecentProgressShrinksStaleEpisodeCountFromRelease() {
        MTitle title = new MTitle("성순 엑스터시", 36716, "", "", null, "13화", MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(7, 43, 49);

        assertEquals(13, Preference.normalizedNtkEpisodeCountForTest(title));
        assertEquals(7, title.getBookmarkEpisodeIndex());
    }

    @Test
    public void ntkWebtoonProgressKeepsLoadedEpisodeCount() {
        MTitle title = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setReadingProgress(210, 18, 120);

        assertEquals(120, Preference.normalizedNtkEpisodeCountForTest(title));
    }

    @Test
    public void progressMergeKeepsExistingIndexWhenIncomingOnlyUpdatesCount() {
        MTitle incoming = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        incoming.setSourceSite("ntk");
        incoming.setReadingProgress(210, -1, 120);

        MTitle existing = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        existing.setSourceSite("ntk");
        existing.setReadingProgress(210, 18, 100);

        MTitle merged = Preference.preserveMoreCompleteProgressForTest(incoming, existing);

        assertEquals(210, merged.getBookmarkEpisodeId());
        assertEquals(18, merged.getBookmarkEpisodeIndex());
        assertEquals(120, merged.getEpisodeCount());
    }

    @Test
    public void progressMergeKeepsExistingIndexWhenIncomingBookmarkIdCannotResolve() {
        MTitle incoming = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        incoming.setSourceSite("ntk");
        incoming.setReadingProgress(999, -1, 120);

        MTitle existing = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        existing.setSourceSite("ntk");
        existing.setReadingProgress(210, 18, 100);

        MTitle merged = Preference.preserveMoreCompleteProgressForTest(incoming, existing);

        assertEquals(210, merged.getBookmarkEpisodeId());
        assertEquals(18, merged.getBookmarkEpisodeIndex());
        assertEquals(120, merged.getEpisodeCount());
    }

    @Test
    public void progressMergeKeepsIncomingSelectedIndexWhenExistingCountIsLarger() {
        MTitle incoming = new MTitle("화산귀환", 769209, "", "", null, "165화", MTitle.base_webtoon);
        incoming.setSourceSite("ntk");
        incoming.setReadingProgress(7, 165, 165);

        MTitle existing = new MTitle("화산귀환", 769209, "", "", null, "171화", MTitle.base_webtoon);
        existing.setSourceSite("ntk");
        existing.setReadingProgress(1, 171, 171);

        MTitle merged = Preference.preserveMoreCompleteProgressForTest(incoming, existing);

        assertEquals(7, merged.getBookmarkEpisodeId());
        assertEquals(165, merged.getBookmarkEpisodeIndex());
        assertEquals(171, merged.getEpisodeCount());
    }

    @Test
    public void progressMergeKeepsExistingIdWhenIncomingHasSameIndexWithDifferentId() {
        MTitle incoming = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        incoming.setSourceSite("ntk");
        incoming.setReadingProgress(999, 18, 120);

        MTitle existing = new MTitle("Long Webtoon", 36716, "", "", null, "5", MTitle.base_webtoon);
        existing.setSourceSite("ntk");
        existing.setReadingProgress(210, 18, 100);

        MTitle merged = Preference.preserveMoreCompleteProgressForTest(incoming, existing);

        assertEquals(210, merged.getBookmarkEpisodeId());
        assertEquals(18, merged.getBookmarkEpisodeIndex());
        assertEquals(120, merged.getEpisodeCount());
    }

    @Test
    public void wfwfProgressInfersListIndexFromEpisodeNumber() {
        MTitle title = new MTitle("서머타임 렌더링", 10017, "", "", null, "", MTitle.base_comic);
        title.setSourceSite("wfwf");

        assertEquals(132, Preference.inferEpisodeIndexFromEpisodeIdForTest(title, 74, 205));
    }

    @Test
    public void ntkProgressDoesNotInferIndexFromSourceId() {
        MTitle title = new MTitle("서머타임 렌더링", 7843, "", "", null, "", MTitle.base_comic);
        title.setSourceSite("ntk");

        assertEquals(-1, Preference.inferEpisodeIndexFromEpisodeIdForTest(title, 74, 205));
    }
}
