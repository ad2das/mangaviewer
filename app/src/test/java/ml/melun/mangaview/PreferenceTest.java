package ml.melun.mangaview;

import org.junit.Test;

import static ml.melun.mangaview.mangaview.CustomHttpClient.DEFAULT_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.WEBTOON_URL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreferenceTest {
    @Test
    public void siteUrlNormalizationPreservesNtkPreset() {
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://ntk01.com"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest("https://ntk01.com/cm"));
        assertEquals(NTK_COMIC_URL, Preference.normalizeComicUrlForTest(NTK_COMIC_URL));
        assertEquals(NTK_WEBTOON_URL, Preference.normalizeWebtoonUrlForTest(NTK_WEBTOON_URL));
    }

    @Test
    public void wfwfPresetForceIsExplicitlyDetectable() {
        assertFalse(Preference.needsWfwfSitePresetForTest(DEFAULT_COMIC_URL, DEFAULT_COMIC_URL, WEBTOON_URL));
        assertTrue(Preference.needsWfwfSitePresetForTest(NTK_COMIC_URL, NTK_COMIC_URL, NTK_WEBTOON_URL));
    }
}
