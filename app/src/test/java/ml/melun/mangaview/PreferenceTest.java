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
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest(NTK_COMIC_URL));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://ntk01.com"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://ntk01.com/manhwa"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest("https://sbxh1.com/manhwa"));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest(NTK_WEBTOON_URL));
    }

    @Test
    public void siteUrlNormalizationKeepsDynamicNtkHost() {
        assertEquals("https://sbxh3.com/manhwa", Preference.normalizeComicUrlForTest("https://sbxh3.com"));
        assertEquals("https://sbxh3.com/manhwa", Preference.normalizeComicUrlForTest("https://sbxh3.com/cm"));
        assertEquals("https://sbxh3.com", Preference.normalizeWebtoonUrlForTest("https://sbxh3.com/manhwa"));
        assertEquals("https://newto03.com/manhwa", Preference.normalizeComicUrlForTest("https://newto03.com"));
        assertEquals("https://newto03.com", Preference.normalizeWebtoonUrlForTest("https://newto03.com/manhwa"));
        assertEquals("https://toonflix.app/manhwa", Preference.normalizeComicUrlForTest("https://toonflix.app"));
        assertEquals("https://toonflix.app", Preference.normalizeWebtoonUrlForTest("https://toonflix.app/manhwa"));
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
    public void ntkRecentProgressShrinksStaleEpisodeCountFromRelease() {
        MTitle title = new MTitle("성순 엑스터시", 36716, "", "", null, "13화", MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setReadingProgress(7, 43, 49);

        assertEquals(13, Preference.normalizedNtkEpisodeCountForTest(title));
        assertEquals(7, title.getBookmarkEpisodeIndex());
    }
}
