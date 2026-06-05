package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;

@RunWith(AndroidJUnit4.class)
public class NtkSearchGenreLiveInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private static final String NTK_ROOT = "https://sbxh4.com";

    @Before
    public void setUp() {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset(NTK_ROOT);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().clearPageCache();
        MainApplication.getHttpClient().clearLastCloudflareChallenge();
        Search.clearNtkResultCaches();
    }

    @Test
    public void ntkCombinedComicGenreLoadsTitlesThroughAppBypass() {
        String genrePath = MainPageWebtoon.resolveCurrentSiteFilterPath("액션", MTitle.base_comic, true);
        Search search = new Search(genrePath, 8, MTitle.base_comic);

        int result = search.fetch(MainApplication.getHttpClient());
        int count = search.getResult() == null ? 0 : search.getResult().size();
        Log.d(TAG, "ntk_live_genre result=" + result
                + ",count=" + count
                + ",path=" + genrePath
                + ",challenge=" + MainApplication.getHttpClient().getLastCloudflareChallengeUrl());

        assertTrue("NTK genre should load titles or surface the in-app captcha path",
                result == 0 && count > 0 || hasNtkChallengeFor("/api/manhwa-list"));
    }

    @Test
    public void ntkKeywordSearchLoadsTitlesThroughAppBypass() {
        Search search = new Search("원피스", 0, MTitle.base_comic);

        int result = search.fetch(MainApplication.getHttpClient());
        int count = search.getResult() == null ? 0 : search.getResult().size();
        Log.d(TAG, "ntk_live_keyword result=" + result
                + ",count=" + count
                + ",challenge=" + MainApplication.getHttpClient().getLastCloudflareChallengeUrl());

        assertTrue("NTK keyword search should load titles or surface the in-app captcha path",
                result == 0 && count > 0 || hasNtkChallengeFor("/search"));
    }

    private boolean hasNtkChallengeFor(String pathPrefix) {
        String challenge = MainApplication.getHttpClient().getLastCloudflareChallengeUrl();
        return MainApplication.getHttpClient().hasRecentCloudflareChallenge()
                && challenge != null
                && challenge.contains(NTK_ROOT)
                && challenge.contains(pathPrefix);
    }
}
