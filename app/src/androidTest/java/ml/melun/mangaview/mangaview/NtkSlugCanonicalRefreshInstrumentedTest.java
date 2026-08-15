package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.Collections;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.LiveNetworkAssume;

public class NtkSlugCanonicalRefreshInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Test
    public void webtoonSlugRefreshesThroughSearchResultPath() {
        LiveNetworkAssume.assumeEnabled();
        // Keep the default fixture tied to a currently published work from the qualification
        // corpus.  The former title was removed from the live catalog and sbxh4 is a retired
        // origin, so that combination exercised only a permanent 404/search miss instead of the
        // slug-to-canonical recovery path this test owns.
        String titleName = InstrumentationRegistry.getArguments().getString(
                "ntkTitleName", "나만 볼 수 있는 아카식 레코드");
        String initialPath = InstrumentationRegistry.getArguments().getString(
                "ntkInitialPath", "/webtoon/나만-볼-수-있는-아카식-레코드");
        String expectedPath = InstrumentationRegistry.getArguments().getString(
                "ntkExpectedPath", "/webtoon/844541");
        String siteRoot = InstrumentationRegistry.getArguments().getString(
                "ntkSiteRoot", "https://sbxh9.com");
        String customUserAgent = InstrumentationRegistry.getArguments().getString("ntkUserAgent", "");
        int titleId = Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("ntkTitleId", "844541"));

        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();
        if(customUserAgent.trim().length() > 0) {
            MainApplication.getHttpClient().agent = customUserAgent.trim();
            Log.d(TAG, "ntk_slug_refresh_user_agent=" + customUserAgent.trim());
        }
        MainApplication.p.setBaseMode(MTitle.base_webtoon);
        Title title = new Title(titleName, "", "", Collections.emptyList(), "", titleId, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setPath(initialPath);

        long startedAt = SystemClock.elapsedRealtime();
        int result = title.fetchEps(MainApplication.getHttpClient());
        long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        Log.d(TAG, "ntk_slug_refresh_result result=" + result
                + ",elapsedMs=" + elapsedMs
                + ",titleId=" + titleId
                + ",initialPath=" + initialPath
                + ",expectedPath=" + expectedPath
                + ",actualPath=" + title.getPath()
                + ",eps=" + (title.getEps() == null ? 0 : title.getEps().size()));

        assertEquals(Title.LOAD_OK, result);
        assertEquals(expectedPath, title.getPath());
        assertNotNull(title.getEps());
        assertTrue("expected parsed episodes", title.getEps().size() > 0);
    }
}
