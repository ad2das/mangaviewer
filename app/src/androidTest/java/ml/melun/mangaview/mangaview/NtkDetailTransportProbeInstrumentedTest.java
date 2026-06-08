package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import okhttp3.Request;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class NtkDetailTransportProbeInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private String ntkRoot;

    @Before
    public void setUp() {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        ntkRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", "https://sbxh4.com");
        MainApplication.p.setNtkSitePreset(ntkRoot);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();
    }

    @Test
    public void detailTransportVariantsReportStatusCodes() throws Exception {
        CustomHttpClient client = MainApplication.getHttpClient();
        String requestedPath = InstrumentationRegistry.getArguments().getString("ntkDetailPath", "");
        String detailPath = requestedPath == null || requestedPath.trim().length() == 0
                ? firstCurrentDetailPath(client) : requestedPath.trim();
        String report = client.probeNtkDetailTransportVariantsForTest(detailPath);
        for(String line : report.split("\\n"))
            Log.d(TAG, "ntk_detail_probe " + line);
        assertTrue("Expected detail probe path", report.contains("path: /"));
    }

    @Test
    public void imageUrlProbeReportsStatusCode() throws Exception {
        CustomHttpClient client = MainApplication.getHttpClient();
        String imageUrl = InstrumentationRegistry.getArguments().getString("ntkImageUrl",
                "https://i.toonflix.app/manhwa/37043/1816201/p001.jpg");
        String referer = InstrumentationRegistry.getArguments().getString("ntkImageReferer",
                ntkRoot + "/manhwa/37043/1816201");
        String desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
        probeImageOkHttp(client, "okhttp_mobile_referer", imageUrl, referer, client.agent);
        probeImageOkHttp(client, "okhttp_desktop_referer", imageUrl, referer, desktopUa);
        probeImageOkHttp(client, "okhttp_desktop_no_referer", imageUrl, "", desktopUa);
        probeImageEngine("engine_quic_mobile_referer", imageUrl, referer, client.agent, true);
        probeImageEngine("engine_http2_mobile_referer", imageUrl, referer, client.agent, false);
        probeImageEngine("engine_quic_desktop_referer", imageUrl, referer, desktopUa, true);
        assertTrue("Image probe completed", true);
    }

    private static void probeImageOkHttp(CustomHttpClient client, String name, String imageUrl,
                                         String referer, String userAgent) {
        long startedAt = System.currentTimeMillis();
        try {
            Request.Builder builder = new Request.Builder()
                    .url(imageUrl)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", referer == null || referer.length() == 0 ? "none" : "cross-site");
            if(referer != null && referer.length() > 0)
                builder.header("Referer", referer);
            try(Response response = client.imageClient.newCall(builder.build()).execute()) {
                byte[] body = response.body() == null ? new byte[0] : response.body().bytes();
                Log.d(TAG, "ntk_image_probe name=" + name
                        + ",code=" + response.code()
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",len=" + body.length
                        + ",type=" + response.header("content-type", ""));
            }
        } catch (Exception e) {
            Log.d(TAG, "ntk_image_probe name=" + name
                    + ",fail=" + (System.currentTimeMillis() - startedAt) + "ms " + e);
        }
    }

    private static void probeImageEngine(String name, String imageUrl, String referer,
                                         String userAgent, boolean quic) {
        long startedAt = System.currentTimeMillis();
        try {
            Context context = ApplicationProvider.getApplicationContext();
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("Sec-Fetch-Dest", "image");
            headers.put("Sec-Fetch-Mode", "no-cors");
            headers.put("Sec-Fetch-Site", referer == null || referer.length() == 0 ? "none" : "cross-site");
            if(referer != null && referer.length() > 0)
                headers.put("Referer", referer);
            NtkQuicFetcher.Result result = quic
                    ? NtkQuicFetcher.fetch(context, imageUrl, userAgent, "", headers, 8_000L)
                    : NtkQuicFetcher.fetchHttp2Only(context, imageUrl, userAgent, "", headers,
                    "GET", null, 8_000L);
            Log.d(TAG, "ntk_image_probe name=" + name
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length)
                    + ",type=" + (result == null ? "" : result.contentType())
                    + ",error=" + (result == null || result.error == null ? "" : result.error));
        } catch (Exception e) {
            Log.d(TAG, "ntk_image_probe name=" + name
                    + ",fail=" + (System.currentTimeMillis() - startedAt) + "ms " + e);
        }
    }

    private static String firstCurrentDetailPath(CustomHttpClient client) throws Exception {
        CustomHttpClient.PageResponse page = client.mgetNtkDesktopDocumentPage("/manhwa", 0L);
        assertTrue("Expected /manhwa page, code=" + page.code,
                page.code >= 200 && page.code < 400 && page.body != null && page.body.length() > 0);
        List<Title> titles = MainPageWebtoon.parseNtkTitleListPayload(page.body, MTitle.base_comic, 4);
        assertTrue("Expected at least one NTK title", titles != null && !titles.isEmpty());
        Title title = titles.get(0);
        assertTrue("Expected current title path", title != null
                && title.getPath() != null && title.getPath().length() > 0);
        return title.getPath();
    }
}
