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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class NtkRootTransportProbeInstrumentedTest {
    private static final String TAG = "ViewerPerf";
    private static final String DEFAULT_ROOTS =
            "https://sbxh6.com,https://toonflix.app,https://sbxh4.com";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void rootTransportVariantsReportStatusCodes() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        CustomHttpClient client = MainApplication.getHttpClient();
        client.resetCookie();
        client.clearLastCloudflareChallenge();

        String userAgent = InstrumentationRegistry.getArguments()
                .getString("ntkProbeUserAgent", client.agent);
        int timeoutMs = intArg("ntkRootProbeTimeoutMs", 3500);
        int maxRoots = Math.max(1, intArg("ntkRootProbeMaxRoots", 16));
        List<String> roots = rootsArg(client);
        LinkedHashSet<String> queue = new LinkedHashSet<>(roots);
        LinkedHashSet<String> probed = new LinkedHashSet<>();
        Log.d(TAG, "ntk_root_probe_start roots=" + roots
                + ",timeoutMs=" + timeoutMs
                + ",maxRoots=" + maxRoots);
        while(!queue.isEmpty() && probed.size() < maxRoots) {
            String root = normalizeRoot(queue.iterator().next());
            queue.remove(root);
            if(root.length() == 0 || probed.contains(root))
                continue;
            probed.add(root);
            for(String candidate : probeRoot(context, client, root, userAgent, timeoutMs)) {
                String normalized = normalizeRoot(candidate);
                if(normalized.length() > 0 && !probed.contains(normalized))
                    queue.add(normalized);
            }
        }
        Log.d(TAG, "ntk_root_probe_done count=" + probed.size()
                + ",roots=" + probed
                + ",remaining=" + queue);
        assertTrue("Expected root probes to run", !probed.isEmpty());
    }

    private static List<String> rootsArg(CustomHttpClient client) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        String arg = InstrumentationRegistry.getArguments()
                .getString("ntkRoots", DEFAULT_ROOTS);
        addRoots(roots, arg);
        if(boolArg("ntkIncludeResolvedRoots", false)) {
            try {
                roots.addAll(NtkDomainResolver.resolveCandidates(client.client,
                        defaultHeaders(client.agent, CustomHttpClient.NTK_WEBTOON_URL), null));
            } catch (Exception ignored) {
            }
        }
        if(roots.isEmpty())
            addRoots(roots, DEFAULT_ROOTS);
        return new ArrayList<>(roots);
    }

    private static void addRoots(LinkedHashSet<String> roots, String value) {
        if(value == null)
            return;
        for(String part : value.split(",")) {
            String root = normalizeRoot(part);
            if(root.length() > 0)
                roots.add(root);
        }
    }

    private static String normalizeRoot(String root) {
        if(root == null)
            return "";
        String trimmed = root.trim();
        if(trimmed.length() == 0)
            return "";
        if(!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))
            trimmed = "https://" + trimmed;
        while(trimmed.endsWith("/"))
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private static List<String> probeRoot(Context context, CustomHttpClient client, String root,
                                          String userAgent, int timeoutMs) {
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        if(root.length() == 0)
            return new ArrayList<>(discovered);
        MainApplication.p.setNtkSitePresetForDiagnostics(root);
        discovered.addAll(probeOkHttp(client, root, "okhttp_root", root + "/", "GET", null,
                htmlHeaders(userAgent, root, root, "")));
        discovered.addAll(probeOkHttp(client, root, "okhttp_api", root + "/api/manhwa-list?page=1&pageSize=1&withTotal=1",
                "GET", null, apiHeaders(userAgent, root, root + "/", "")));
        String challengeBody = "{\"path\":\"/\"}";
        discovered.addAll(probeOkHttp(client, root, "okhttp_challenge", root + "/api/ad/challenge", "POST",
                challengeBody.getBytes(StandardCharsets.UTF_8),
                challengeHeaders(userAgent, root, root + "/", "")));
        discovered.addAll(probeEngine(context, root, "quic_root", root + "/", userAgent, "GET", null,
                htmlHeaders(userAgent, root, root, ""), true, timeoutMs));
        discovered.addAll(probeEngine(context, root, "h2_root", root + "/", userAgent, "GET", null,
                htmlHeaders(userAgent, root, root, ""), false, timeoutMs));
        discovered.addAll(probeEngine(context, root, "quic_challenge", root + "/api/ad/challenge", userAgent,
                "POST", challengeBody.getBytes(StandardCharsets.UTF_8),
                challengeHeaders(userAgent, root, root + "/", ""), true, timeoutMs));
        discovered.addAll(probeEngine(context, root, "h2_challenge", root + "/api/ad/challenge", userAgent,
                "POST", challengeBody.getBytes(StandardCharsets.UTF_8),
                challengeHeaders(userAgent, root, root + "/", ""), false, timeoutMs));
        if(!discovered.isEmpty())
            Log.d(TAG, "ntk_root_probe_discovered root=" + root
                    + ",candidates=" + discovered);
        return new ArrayList<>(discovered);
    }

    private static List<String> probeOkHttp(CustomHttpClient client, String root, String name, String url,
                                            String method, byte[] body, Map<String, String> headers) {
        List<String> candidates = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            OkHttpClient probeClient = client.client.newBuilder()
                    .connectTimeout(2500, TimeUnit.MILLISECONDS)
                    .readTimeout(3500, TimeUnit.MILLISECONDS)
                    .callTimeout(4500, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            Request.Builder builder = new Request.Builder().url(url);
            for(String key : headers.keySet())
                builder.header(key, headers.get(key));
            if("POST".equals(method))
                builder.post(RequestBody.create(body == null ? new byte[0] : body));
            else
                builder.get();
            response = probeClient.newCall(builder.build()).execute();
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            candidates = discoveredRootList(bytes);
            log(root, name, response.code(), System.currentTimeMillis() - startedAt,
                    bytes.length, response.header("content-type", ""),
                    summarizeSetCookieHeaders(response.headers("set-cookie")),
                    "", sample(bytes), joinRoots(candidates));
        } catch (Exception e) {
            log(root, name, 0, System.currentTimeMillis() - startedAt, 0,
                    "", "-", e.getClass().getSimpleName() + ":" + safe(e.getMessage()), "", "-");
        } finally {
            if(response != null)
                response.close();
        }
        return candidates;
    }

    private static List<String> probeEngine(Context context, String root, String name, String url,
                                            String userAgent, String method, byte[] body,
                                            Map<String, String> headers, boolean quic, int timeoutMs) {
        List<String> candidates = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        try {
            NtkQuicFetcher.Result result = quic
                    ? NtkQuicFetcher.fetch(context, url, userAgent, "", headers,
                            method, body, timeoutMs)
                    : NtkQuicFetcher.fetchHttp2Only(context, url, userAgent, "", headers,
                            method, body, timeoutMs);
            log(root, name, result == null ? 0 : result.code,
                    System.currentTimeMillis() - startedAt,
                    result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length,
                    result == null ? "" : result.contentType(),
                    summarizeSetCookieHeaders(result == null ? null : result.setCookies()),
                    result == null || result.error == null ? "" :
                            result.error.getClass().getSimpleName() + ":" + safe(result.error.getMessage()),
                    result == null || result.bodyBytes == null ? "" : sample(result.bodyBytes),
                    result == null || result.bodyBytes == null ? "-" :
                            joinRoots(candidates = discoveredRootList(result.bodyBytes)));
        } catch (Exception e) {
            log(root, name, 0, System.currentTimeMillis() - startedAt, 0,
                    "", "-", e.getClass().getSimpleName() + ":" + safe(e.getMessage()), "", "-");
        }
        return candidates;
    }

    private static Map<String, String> htmlHeaders(String userAgent, String root, String referer,
                                                   String cookieHeader) {
        Map<String, String> headers = defaultHeaders(userAgent, root);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,text/plain,*/*;q=0.8");
        headers.put("Referer", referer);
        if(cookieHeader != null && cookieHeader.length() > 0)
            headers.put("Cookie", cookieHeader);
        return headers;
    }

    private static Map<String, String> apiHeaders(String userAgent, String root, String referer,
                                                  String cookieHeader) {
        Map<String, String> headers = defaultHeaders(userAgent, root);
        headers.put("Accept", "application/json,text/plain,*/*");
        headers.put("Referer", referer);
        if(cookieHeader != null && cookieHeader.length() > 0)
            headers.put("Cookie", cookieHeader);
        return headers;
    }

    private static Map<String, String> challengeHeaders(String userAgent, String root,
                                                        String referer, String cookieHeader) {
        Map<String, String> headers = defaultHeaders(userAgent, root);
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

    private static Map<String, String> defaultHeaders(String userAgent, String root) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept-Language", Locale.getDefault().toLanguageTag()
                + ",ko-KR;q=0.9,ko;q=0.8,en-US;q=0.7,en;q=0.6");
        try {
            String host = URI.create(root).getHost();
            if(host != null && host.length() > 0)
                headers.put("Host", host);
        } catch (Exception ignored) {
        }
        return headers;
    }

    private static void log(String root, String name, int code, long ms, int len, String type,
                            String setCookies, String error, String sample, String candidates) {
        Log.d(TAG, "ntk_root_probe root=" + root
                + ",name=" + name
                + ",code=" + code
                + ",ms=" + ms
                + ",len=" + len
                + ",type=" + safe(type)
                + ",setCookies=" + setCookies
                + ",candidates=" + safe(candidates)
                + ",block=" + looksBlocked(code, sample)
                + ",error=" + safe(error)
                + ",sample=" + safe(sample));
    }

    private static boolean looksBlocked(int code, String sample) {
        String lower = sample == null ? "" : sample.toLowerCase(Locale.ROOT);
        return code == 403
                || lower.contains("cloudflare")
                || lower.contains("attention required")
                || lower.contains("sorry, you have been blocked");
    }

    private static String summarizeSetCookieHeaders(List<String> cookies) {
        if(cookies == null || cookies.isEmpty())
            return "-";
        StringBuilder builder = new StringBuilder();
        for(String cookie : cookies) {
            if(builder.length() > 0)
                builder.append('|');
            int equals = cookie == null ? -1 : cookie.indexOf('=');
            builder.append(equals <= 0 ? "?" : cookie.substring(0, equals));
        }
        return builder.toString();
    }

    private static String sample(byte[] bytes) {
        if(bytes == null || bytes.length == 0)
            return "";
        int len = Math.min(bytes.length, 180);
        return new String(bytes, 0, len, StandardCharsets.UTF_8)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(',', ';');
    }

    private static String discoveredRoots(byte[] bytes) {
        return joinRoots(discoveredRootList(bytes));
    }

    private static List<String> discoveredRootList(byte[] bytes) {
        if(bytes == null || bytes.length == 0)
            return new ArrayList<>();
        try {
            String html = new String(bytes, StandardCharsets.UTF_8);
            LinkedHashSet<String> roots = new LinkedHashSet<>();
            roots.addAll(NtkDomainResolver.parseLatestRoots(html));
            roots.addAll(NtkDomainResolver.parseAddressGuideRoots(html));
            ArrayList<String> out = new ArrayList<>();
            for(String root : roots) {
                if(root == null || root.length() == 0)
                    continue;
                root = normalizeRoot(root);
                if(!isPlausibleNtkRoot(root))
                    continue;
                out.add(root);
                if(out.size() >= 8)
                    break;
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static String joinRoots(List<String> roots) {
        if(roots == null || roots.isEmpty())
            return "-";
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for(String root : roots) {
            if(root == null || root.length() == 0)
                continue;
            if(builder.length() > 0)
                builder.append('|');
            builder.append(root);
            count++;
            if(count >= 8)
                break;
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private static boolean isPlausibleNtkRoot(String root) {
        try {
            String host = URI.create(root).getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.matches("sbxh\\d+\\.com")
                    || host.endsWith(".newtoki1.org")
                    || "newtoki1.org".equals(host)
                    || "toonflix.app".equals(host);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int intArg(String name, int fallback) {
        try {
            return Integer.parseInt(InstrumentationRegistry.getArguments().getString(name,
                    Integer.toString(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean boolArg(String name, boolean fallback) {
        String value = InstrumentationRegistry.getArguments().getString(name,
                Boolean.toString(fallback));
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static String safe(String value) {
        if(value == null)
            return "";
        return value.replace('\n', ' ').replace('\r', ' ').replace(',', ';');
    }
}
