package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class NtkAppHttpBypassInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void appHttpClientReachesNtkApiThroughBypassPath() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        String siteRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.clearNtkCellularResolvedRoot();
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        CustomHttpClient client = MainApplication.getHttpClient();

        String url = siteRoot + "/api/manhwa-list?page=1&pageSize=1&withTotal=1";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", client.agent);
        headers.put("Accept", "application/json,text/plain,*/*");
        headers.put("Referer", siteRoot + "/");

        long started = System.currentTimeMillis();
        Response response = client.mget("/api/manhwa-list?page=1&pageSize=1&withTotal=1", true);
        if(response == null) {
            Log.d(TAG, "ntk_app_http_bypass_mget_null root=" + siteRoot
                    + ",challenge=" + client.getLastCloudflareChallengeUrl());
            response = client.get(url, headers);
        }
        assertNotNull("Expected app HTTP bypass path to return an NTK HTTP response", response);
        int code = response.code();
        String body = response.body() == null ? "" : response.body().string();
        response.close();

        String lower = body.toLowerCase(Locale.ROOT);
        Log.d(TAG, "ntk_app_http_bypass code=" + code
                + ",bodyLen=" + body.length()
                + ",ms=" + (System.currentTimeMillis() - started)
                + ",sample=" + body.substring(0, Math.min(160, body.length())).replace('\n', ' '));

        assertTrue("Expected HTTP status from NTK, code=" + code, code > 0);
        assertTrue("Expected non-empty NTK response body, code=" + code, body.length() > 0);
        assertFalse("App must recover away from an HTTP legal-block route, code=" + code,
                code == 451
                        || lower.contains("법적 사유로 이용 불가")
                        || lower.contains("unavailable for legal reasons")
                        || lower.contains("\"status\":451")
                        || lower.contains("error 1026: cloudflare error"));
        assertFalse("App must not receive a WebView/network error document",
                lower.contains("webpage not available")
                        || lower.contains("net::err_")
                        || lower.contains("err_connection_closed")
                        || lower.contains("err_connection_reset")
                        || lower.contains("err_timed_out"));
        assertTrue("Expected NTK JSON, Cloudflare challenge, or normal NTK marker, code=" + code + ", body=" + body,
                lower.trim().startsWith("{")
                        || lower.trim().startsWith("[")
                        || lower.contains("cloudflare")
                        || lower.contains("turnstile")
                        || lower.contains("newtoki")
                        || lower.contains("/manhwa")
                        || lower.contains("/webtoon"));

        long steadyStarted = System.currentTimeMillis();
        Response steadyResponse =
                client.mget("/api/manhwa-list?page=2&pageSize=1&withTotal=1", true);
        assertNotNull("Expected the recovered cellular root to serve a repeat request",
                steadyResponse);
        int steadyCode = steadyResponse.code();
        String steadyBody =
                steadyResponse.body() == null ? "" : steadyResponse.body().string();
        steadyResponse.close();
        long steadyMs = System.currentTimeMillis() - steadyStarted;
        String steadyLower = steadyBody.toLowerCase(Locale.ROOT);
        Log.d(TAG, "ntk_app_http_bypass_steady code=" + steadyCode
                + ",bodyLen=" + steadyBody.length()
                + ",ms=" + steadyMs);
        assertTrue("Expected a non-empty repeat response, code=" + steadyCode,
                steadyCode > 0 && steadyBody.length() > 0);
        assertFalse("Repeat request must stay away from an HTTP legal-block route, code="
                        + steadyCode,
                steadyCode == 451
                        || steadyLower.contains("법적 사유로 이용 불가")
                        || steadyLower.contains("unavailable for legal reasons")
                        || steadyLower.contains("\"status\":451")
                        || steadyLower.contains("error 1026: cloudflare error"));
        assertTrue("Recovered cellular repeat request was too slow: " + steadyMs + "ms",
                steadyMs < 4_000L);
    }
}
