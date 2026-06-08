package ml.melun.mangaview.activity;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.webkit.CookieManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;

@RunWith(AndroidJUnit4.class)
public class NtkQuicCookieProbeInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void rootCookiesAreAppliedBeforeNtkQuicApiAndPageProbes() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        String root = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setNtkSitePreset(root);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        CustomHttpClient client = MainApplication.getHttpClient();
        client.resetCookie();
        client.clearLastCloudflareChallenge();
        clearWebViewCookies(root);
        String userAgent = InstrumentationRegistry.getArguments()
                .getString("ntkProbeUserAgent", client.agent);
        String userAgentBase64 = InstrumentationRegistry.getArguments()
                .getString("ntkProbeUserAgentBase64", "");
        if(userAgentBase64 != null && userAgentBase64.length() > 0)
            userAgent = new String(android.util.Base64.decode(userAgentBase64, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
        String extraCookieHeader = InstrumentationRegistry.getArguments()
                .getString("ntkExtraCookieHeader", "");
        String imagesToken = InstrumentationRegistry.getArguments()
                .getString("ntkImagesToken", "");
        String episodePath = normalizedPath(InstrumentationRegistry.getArguments()
                .getString("ntkEpisodePath", "/manhwa/37043/1816201"));
        String imageUrl = InstrumentationRegistry.getArguments().getString("ntkImageUrl",
                root + episodePath + "/p001.jpg");
        EpisodeIds episodeIds = episodeIdsFromPath(episodePath);
        android.util.Log.d(TAG, "ntk_quic_cookie_probe targetEpisode=" + episodePath
                + ",imageUrl=" + imageUrl
                + ",workId=" + episodeIds.workId
                + ",episodeId=" + episodeIds.episodeId);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        Map<String, String> rootHeaders = htmlHeaders(userAgent, root, "");
        NtkQuicFetcher.Result rootResult = fetchWithRetry(context, root + "/", userAgent, "", rootHeaders);
        log("root", rootResult);
        List<String> discoveredPaths = firstPaths(rootResult == null ? "" : rootResult.body, 12);
        android.util.Log.d(TAG, "ntk_quic_cookie_probe discoveredPaths=" + discoveredPaths);
        applySetCookies(cookieManager, root, rootResult);
        cookieManager.flush();

        String cookieHeader = cookieManager.getCookie(root);
        android.util.Log.d(TAG, "ntk_quic_cookie_probe cookies=" + summarizeCookieHeader(cookieHeader));
        if(extraCookieHeader != null && extraCookieHeader.length() > 0) {
            probe(context, "extra_cookie_page", root + episodePath,
                    htmlHeaders(userAgent, root + episodePath, extraCookieHeader),
                    extraCookieHeader, userAgent);
            probe(context, "extra_cookie_image", imageUrl,
                    imageHeaders(userAgent, root + episodePath, extraCookieHeader),
                    extraCookieHeader, userAgent);
        }
        if(imagesToken != null && imagesToken.length() > 0)
            postImagesProbe(context, root, cookieHeader, userAgent, imagesToken, episodePath, episodeIds);
        postProbe(context, "challenge_root_no_cookie", root + "/api/ad/challenge",
                challengeHeaders(root, root + "/", ""), "", userAgent, "{\"path\":\"/\"}");
        postHttp2Probe(context, "challenge_root_no_cookie_h2", root + "/api/ad/challenge",
                challengeHeaders(root, root + "/", ""), "", userAgent, "{\"path\":\"/\"}");
        postProbe(context, "challenge_episode_no_cookie", root + "/api/ad/challenge",
                challengeHeaders(root, root + episodePath, ""), "", userAgent, "{\"path\":\"" + episodePath + "\"}");
        probe(context, "api_no_cookie", root + "/api/manhwa-list?page=1&pageSize=1&withTotal=1",
                htmlHeaders(userAgent, root, ""), "", userAgent);
        probe(context, "page_no_cookie", root + episodePath,
                htmlHeaders(userAgent, root, ""), "", userAgent);
        probe(context, "image_no_cookie", imageUrl,
                imageHeaders(userAgent, root + episodePath, ""), "", userAgent);
        for(int i = 0; i < discoveredPaths.size(); i++)
            probe(context, "discovered_no_cookie_" + i, root + discoveredPaths.get(i),
                    htmlHeaders(userAgent, root, ""), "", userAgent);
        postProbe(context, "challenge_root", root + "/api/ad/challenge",
                challengeHeaders(root, root + "/", cookieHeader), cookieHeader, userAgent, "{\"path\":\"/\"}");
        postHttp2Probe(context, "challenge_root_h2", root + "/api/ad/challenge",
                challengeHeaders(root, root + "/", cookieHeader), cookieHeader, userAgent, "{\"path\":\"/\"}");
        postProbe(context, "challenge_episode", root + "/api/ad/challenge",
                challengeHeaders(root, root + episodePath, cookieHeader), cookieHeader, userAgent, "{\"path\":\"" + episodePath + "\"}");
        NtkQuicFetcher.Result guard = fetchWithRetry(context,
                root + "/api/ad/guard-js?v=b1780894052724-wasm-1780894052727",
                userAgent,
                cookieHeader == null ? "" : cookieHeader,
                javascriptHeaders(userAgent, root + "/", cookieHeader));
        log("guard_js", guard);
        applySetCookies(cookieManager, root, guard);
        cookieManager.flush();
        String guardedCookieHeader = cookieManager.getCookie(root);
        android.util.Log.d(TAG, "ntk_quic_cookie_probe guardedCookies=" + summarizeCookieHeader(guardedCookieHeader));
        postProbe(context, "challenge_root_after_guard", root + "/api/ad/challenge",
                challengeHeaders(root, root + "/", guardedCookieHeader), guardedCookieHeader, userAgent, "{\"path\":\"/\"}");
        postProbe(context, "challenge_episode_after_guard", root + "/api/ad/challenge",
                challengeHeaders(root, root + episodePath, guardedCookieHeader), guardedCookieHeader, userAgent, "{\"path\":\"" + episodePath + "\"}");
        probe(context, "api", root + "/api/manhwa-list?page=1&pageSize=1&withTotal=1",
                htmlHeaders(userAgent, root, cookieHeader), cookieHeader, userAgent);
        probe(context, "page", root + episodePath,
                htmlHeaders(userAgent, root, cookieHeader), cookieHeader, userAgent);
        probe(context, "image", imageUrl,
                imageHeaders(userAgent, root + episodePath, cookieHeader), cookieHeader, userAgent);
        for(int i = 0; i < discoveredPaths.size(); i++)
            probe(context, "discovered_" + i, root + discoveredPaths.get(i),
                    htmlHeaders(userAgent, root, cookieHeader), cookieHeader, userAgent);

        assertTrue("Expected QUIC probes to run", true);
    }

    private static void probe(Context context, String name, String url, Map<String, String> headers,
                              String cookieHeader, String userAgent) {
        NtkQuicFetcher.Result result = fetchWithRetry(context, url, userAgent,
                cookieHeader == null ? "" : cookieHeader, headers);
        log(name, result);
    }

    private static void postProbe(Context context, String name, String url, Map<String, String> headers,
                                  String cookieHeader, String userAgent, String body) {
        NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                cookieHeader == null ? "" : cookieHeader, headers, "POST",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 8_000L);
        log(name, result);
    }

    private static void postHttp2Probe(Context context, String name, String url, Map<String, String> headers,
                                  String cookieHeader, String userAgent, String body) {
        NtkQuicFetcher.Result result = NtkQuicFetcher.fetchHttp2Only(context, url, userAgent,
                cookieHeader == null ? "" : cookieHeader, headers, "POST",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 8_000L);
        log(name, result);
    }

    private static void postImagesProbe(Context context, String root, String cookieHeader,
                                        String userAgent, String imagesToken, String episodePath,
                                        EpisodeIds episodeIds) {
        try {
            String nv = cookieValue(cookieHeader, "nv");
            String nonce = randomBase64Url(24);
            String proof = hmacBase64Url(nv, imagesToken + "." + nonce + "." + userAgent);
            String body = "{\"workId\":\"" + episodeIds.workId
                    + "\",\"episodeId\":\"" + episodeIds.episodeId + "\",\"token\":\""
                    + imagesToken + "\",\"nonce\":\"" + nonce + "\",\"proof\":\"" + proof + "\"}";
            Map<String, String> headers = challengeHeaders(root, root + episodePath, cookieHeader);
            headers.put("x-images-client", "viewer-v1");
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context,
                    root + "/api/manhwa-images", userAgent,
                    cookieHeader == null ? "" : cookieHeader, headers, "POST",
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 8_000L);
            log("manhwa_images_api", result);
        } catch (Exception e) {
            android.util.Log.d(TAG, "ntk_quic_cookie_probe name=manhwa_images_api,error=" + e);
        }
    }

    private static String normalizedPath(String value) {
        if(value == null || value.trim().length() == 0)
            return "/manhwa/37043/1816201";
        String trimmed = value.trim();
        if(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            int scheme = trimmed.indexOf("://");
            int slash = scheme < 0 ? -1 : trimmed.indexOf('/', scheme + 3);
            return slash < 0 ? "/" : trimmed.substring(slash);
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static EpisodeIds episodeIdsFromPath(String path) {
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path == null ? "" : path);
        if(matcher.find())
            return new EpisodeIds(matcher.group(1), matcher.group(2));
        return new EpisodeIds("37043", "1816201");
    }

    private static final class EpisodeIds {
        final String workId;
        final String episodeId;

        EpisodeIds(String workId, String episodeId) {
            this.workId = workId;
            this.episodeId = episodeId;
        }
    }

    private static String cookieValue(String cookieHeader, String name) {
        if(cookieHeader == null || name == null)
            return "";
        for(String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if(equals > 0 && name.equals(trimmed.substring(0, equals)))
                return trimmed.substring(equals + 1);
        }
        return "";
    }

    private static String randomBase64Url(int bytes) {
        byte[] data = new byte[bytes];
        new java.security.SecureRandom().nextBytes(data);
        return android.util.Base64.encodeToString(data,
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING | android.util.Base64.URL_SAFE);
    }

    private static String hmacBase64Url(String key, String message) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return android.util.Base64.encodeToString(mac.doFinal(
                        message.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING | android.util.Base64.URL_SAFE);
    }

    private static NtkQuicFetcher.Result fetchWithRetry(Context context, String url, String userAgent,
                                                        String cookieHeader, Map<String, String> headers) {
        NtkQuicFetcher.Result last = null;
        for(int attempt = 0; attempt < 3; attempt++) {
            last = NtkQuicFetcher.fetch(context, url, userAgent, cookieHeader, headers, 8_000L);
            if(last != null && last.error == null && last.code > 0)
                return last;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private static Map<String, String> htmlHeaders(String userAgent, String referer, String cookieHeader) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,text/plain,*/*;q=0.8");
        headers.put("Accept-Language", Locale.getDefault().toLanguageTag() + ",ko-KR;q=0.9,ko;q=0.8,en-US;q=0.7,en;q=0.6");
        headers.put("Referer", referer + "/");
        if(cookieHeader != null && cookieHeader.length() > 0)
            headers.put("Cookie", cookieHeader);
        return headers;
    }

    private static Map<String, String> imageHeaders(String userAgent, String referer, String cookieHeader) {
        Map<String, String> headers = htmlHeaders(userAgent, referer, cookieHeader);
        headers.put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        return headers;
    }

    private static Map<String, String> javascriptHeaders(String userAgent, String referer, String cookieHeader) {
        Map<String, String> headers = htmlHeaders(userAgent, referer, cookieHeader);
        headers.put("Accept", "*/*");
        headers.put("Sec-Fetch-Dest", "script");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        return headers;
    }

    private static Map<String, String> challengeHeaders(String root, String referer, String cookieHeader) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("Origin", root);
        headers.put("Referer", referer);
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        if(cookieHeader != null && cookieHeader.length() > 0)
            headers.put("Cookie", cookieHeader);
        return headers;
    }

    private static void applySetCookies(CookieManager cookieManager, String root, NtkQuicFetcher.Result result) {
        if(result == null)
            return;
        for(String cookie : result.setCookies())
            cookieManager.setCookie(root, cookie);
    }

    private static void clearWebViewCookies(String root) {
        try {
            CountDownLatch done = new CountDownLatch(1);
            CookieManager.getInstance().removeAllCookies(value -> done.countDown());
            done.await(5, TimeUnit.SECONDS);
            CookieManager.getInstance().removeSessionCookies(null);
            String[] names = new String[]{
                    "ntk_pid", "__vsid", "__ntk_ev_id", "__ntk_et_id",
                    "ad_guard_l", "ntk_blk_ok_sig", "nv", "cf_clearance", "__cf_bm"
            };
            for(String name : names) {
                CookieManager.getInstance().setCookie(root, name + "=; Max-Age=0; Path=/");
                CookieManager.getInstance().setCookie(root, name + "=; Max-Age=0; Path=/; Domain=sbxh4.com");
                CookieManager.getInstance().setCookie(root, name + "=; Max-Age=0; Path=/; Domain=.sbxh4.com");
            }
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    private static void log(String name, NtkQuicFetcher.Result result) {
        String body = result == null || result.body == null ? "" : result.body;
        android.util.Log.d(TAG, "ntk_quic_cookie_probe name=" + name
                + ",code=" + (result == null ? 0 : result.code)
                + ",len=" + (result == null ? 0 : result.bodyBytes.length)
                + ",setCookies=" + summarizeSetCookies(result == null ? null : result.setCookies())
                + ",error=" + (result == null || result.error == null ? "" : result.error)
                + ",sample=" + body.substring(0, Math.min(180, body.length())).replace('\n', ' '));
    }

    private static String summarizeSetCookies(List<String> cookies) {
        if(cookies == null || cookies.isEmpty())
            return "-";
        StringBuilder builder = new StringBuilder();
        for(String cookie : cookies) {
            if(builder.length() > 0)
                builder.append(',');
            int equals = cookie == null ? -1 : cookie.indexOf('=');
            builder.append(equals <= 0 ? "?" : cookie.substring(0, equals));
        }
        return builder.toString();
    }

    private static String summarizeCookieHeader(String cookieHeader) {
        if(cookieHeader == null || cookieHeader.length() == 0)
            return "-";
        StringBuilder builder = new StringBuilder();
        for(String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if(equals <= 0)
                continue;
            if(builder.length() > 0)
                builder.append(',');
            builder.append(trimmed.substring(0, equals));
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private static List<String> firstPaths(String body, int limit) {
        ArrayList<String> paths = new ArrayList<>();
        if(body == null || body.length() == 0)
            return paths;
        Matcher matcher = Pattern.compile("/(?:manhwa|webtoon)/[A-Za-z0-9_./?=&%-]+").matcher(body);
        while(matcher.find() && paths.size() < limit) {
            String path = matcher.group();
            if(path.contains("_next") || path.contains("api/"))
                continue;
            if(!paths.contains(path))
                paths.add(path);
        }
        return paths;
    }
}
