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

public class NtkSlugCanonicalRefreshInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Test
    public void webtoonSlugRefreshesThroughSearchResultPath() {
        String titleName = InstrumentationRegistry.getArguments().getString("ntkTitleName", "최강 매니저");
        String initialPath = InstrumentationRegistry.getArguments().getString("ntkInitialPath", "/webtoon/최강-매니저");
        String expectedPath = InstrumentationRegistry.getArguments().getString("ntkExpectedPath", "/webtoon/840894");
        String siteRoot = InstrumentationRegistry.getArguments().getString("ntkSiteRoot", "https://sbxh4.com");
        String customUserAgent = InstrumentationRegistry.getArguments().getString("ntkUserAgent", "");
        int titleId = Integer.parseInt(InstrumentationRegistry.getArguments().getString("ntkTitleId", "192568083"));

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
