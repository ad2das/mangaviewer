package ml.melun.mangaview.mangaview;


import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.CipherSuite;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.repository.CacheFileStore;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.CODE_SCOPED_STORAGE;

public class CustomHttpClient {
    public static final String DEFAULT_COMIC_URL = "https://wfwf450.com/cm";
    public static final String WEBTOON_URL = "https://wfwf450.com";
    public static final String NTK_COMIC_URL = "https://sbxh1.com/manhwa";
    public static final String NTK_WEBTOON_URL = "https://sbxh1.com";
    public static final String NTK_REACHABLE_FALLBACK_URL = "https://ntk01.com";
    private static final String NTK_HOST = "sbxh1.com";
    private static final String LEGACY_NTK_HOST = "ntk01.com";
    private static final long WFWF_DOMAIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS = 60 * 1000L;
    private static final long WFWF_DOMAIN_WAIT_TIMEOUT_MS = 6 * 1000L;
    private static final long NTK_DOMAIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long COOKIE_SYNC_INTERVAL_MS = 30 * 1000L;
    private static final long PAGE_CACHE_COLD_START_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int PAGE_CACHE_MAX_ENTRIES = 200;
    private static final int MAX_HTTP_REQUESTS = 8;
    private static final int MAX_HTTP_REQUESTS_PER_HOST = 4;
    private static final String PAGE_CACHE_PREFIX = "httpPageCacheV1_";
    private static final Gson GSON = new Gson();
    private static final Dns IPV4_FIRST_DNS = hostname -> {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
        ArrayList<InetAddress> sorted = new ArrayList<>(addresses.size() + 1);
        if("sbxh1.com".equalsIgnoreCase(hostname) || "www.sbxh1.com".equalsIgnoreCase(hostname))
            addAddressIfMissing(sorted, InetAddress.getByName("104.16.219.55"));
        for(InetAddress address : addresses)
            if(address instanceof Inet4Address)
                addAddressIfMissing(sorted, address);
        if(!sorted.isEmpty())
            return sorted;
        for(InetAddress address : addresses)
            if(!(address instanceof Inet4Address))
                addAddressIfMissing(sorted, address);
        if(sorted.isEmpty())
            throw new UnknownHostException(hostname);
        return sorted;
    };

    private static void addAddressIfMissing(List<InetAddress> addresses, InetAddress candidate) {
        if(candidate == null || addresses.contains(candidate))
            return;
        addresses.add(candidate);
    }

    public OkHttpClient client;
    private OkHttpClient unsafeFallbackClient;
    Map<String, String> cookies;
    Map<String, Long> cookieSyncAt;
    Map<String, CachedPage> pageCache;
    Map<String, PageLoadState> pageLoads;
    private volatile String lastCloudflareChallengeUrl = null;
    private volatile long lastCloudflareChallengeAt = 0L;
    private volatile boolean cloudflareCaptchaActive = false;
    private final ThreadLocal<RequestGroup> currentRequestGroup = requestGroupLocal();
    private final ThreadLocal<FetchMode> currentFetchMode = new ThreadLocal<>();

    private ThreadLocal<RequestGroup> requestGroupLocal() {
        return new
                ThreadLocal<>();
    }
    private final Object wfwfDomainLock = new Object();
    private DomainResolveState wfwfDomainResolveState;
    private DomainResolveState ntkDomainResolveState;
    private long wfwfDomainLastForcedRetry = 0;
    private long ntkDomainLastCheck = 0;
    private Context context;
    public String agent = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

    public enum FetchMode {
        CACHE_ONLY,
        DIRECT_ONLY,
        ALLOW_SHARED_WEBVIEW
    }

    public CustomHttpClient(Context context){
        this.context = context.getApplicationContext();
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        this.pageLoads = new HashMap<>();
        this.pageCache = new LinkedHashMap<String, CachedPage>(PAGE_CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                return size() > PAGE_CACHE_MAX_ENTRIES;
            }
        };
        loadDefaultUserAgent();
        loadSavedCookies();
        loadSavedUserAgent();
        this.client = baseClient(new OkHttpClient.Builder()).build();
        this.unsafeFallbackClient = baseClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();

        //this.cfc = new HashMap<>();
        //this.client = new OkHttpClient.Builder().build();
    }

    public synchronized void setCookie(String k, String v){
        if(k == null || k.length() == 0)
            return;
        if(v == null) {
            cookies.remove(k);
            persistCookies();
            return;
        }
        cookies.put(k, v);
        persistCookies();
        if("cf_clearance".equalsIgnoreCase(k) && v.length() > 0)
            saveClearanceToDisk();
    }

    public synchronized void setUserAgent(String userAgent) {
        if(userAgent == null || userAgent.trim().length() == 0)
            return;
        agent = userAgent.trim();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .putString("httpUserAgent", agent)
                .apply();
    }
    public synchronized void removeCookie(String k) {
        cookies.remove(k);
        persistCookies();
    }
    public synchronized boolean hasCloudflareClearance() {
        if(isClearanceExpired()) {
            clearCloudflareCookies();
            return false;
        }
        for(String key : cookies.keySet()) {
            if(!"cf_clearance".equalsIgnoreCase(key))
                continue;
            String value = cookies.get(key);
            if(value != null && value.length() > 0)
                return true;
        }
        return false;
    }

    public synchronized boolean hasNtkAccessProof() {
        return hasCloudflareClearance();
    }

    public String getLastCloudflareChallengeUrl() {
        return lastCloudflareChallengeUrl;
    }

    public boolean hasRecentCloudflareChallenge() {
        return lastCloudflareChallengeUrl != null
                && lastCloudflareChallengeUrl.length() > 0
                && System.currentTimeMillis() - lastCloudflareChallengeAt < 5 * 60 * 1000L;
    }

    public void clearLastCloudflareChallenge() {
        lastCloudflareChallengeUrl = null;
        lastCloudflareChallengeAt = 0L;
    }

    public void setCloudflareCaptchaActive(boolean active) {
        cloudflareCaptchaActive = active;
    }

    public void markNtkAccessVerified() {
        try {
            clearLastCloudflareChallenge();
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("ntkAccessVerifiedAt", System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public boolean hasRecentNtkAccessVerification() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long verifiedAt = pref.getLong("ntkAccessVerifiedAt", 0);
            return verifiedAt > 0 && System.currentTimeMillis() - verifiedAt < 10 * 60 * 1000L;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean hasFreshCloudflareClearance() {
        if(!hasCloudflareClearance())
            return false;
        long remaining = cloudflareClearanceRemainingMs();
        return remaining > 5 * 60 * 1000L;
    }

    private long cloudflareClearanceRemainingMs() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            return expireAt - System.currentTimeMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    public void saveClearanceToDisk() {
        try {
            String clearance = cookies.get("cf_clearance");
            if(clearance == null || clearance.length() == 0)
                return;
            long expireAt = System.currentTimeMillis() + 45 * 60 * 1000L; // 45 minutes
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cfClearanceValue", clearance)
                    .putLong("cfClearanceExpireAt", expireAt)
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public void restoreClearanceFromDisk() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String value = pref.getString("cfClearanceValue", null);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            if(value == null || value.length() == 0 || expireAt <= System.currentTimeMillis())
                return;
            cookies.put("cf_clearance", value);
            persistCookies();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public boolean isClearanceExpired() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            if(expireAt == 0)
                return true;
            return expireAt <= System.currentTimeMillis();
        } catch (Exception e) {
            return true;
        }
    }
    public synchronized void clearCloudflareCookies() {
        boolean changed = false;
        for(String key : new ArrayList<>(cookies.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if(lower.startsWith("cf_") || "__cf_bm".equals(lower)) {
                cookies.remove(key);
                changed = true;
            }
        }
        if(changed)
            persistCookies();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .remove("cfClearanceValue")
                .remove("cfClearanceExpireAt")
                .remove("ntkAccessVerifiedAt")
                .apply();
    }

    public void clearCloudflareWebViewCookies(String... urls) {
        try {
            CookieManager manager = CookieManager.getInstance();
            if(urls == null)
                return;
            for(String url : urls) {
                if(url == null || url.length() == 0)
                    continue;
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/");
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/");
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
            }
            manager.flush();
            clearCloudflareCookies();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }
    public synchronized void resetCookie(){
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        persistCookies();
    }

    public void syncCookiesFromWebView(String url){
        syncCookiesFromWebView(url, false);
    }

    public void syncCookiesFromWebView(String url, boolean force){
        long startedAt = System.currentTimeMillis();
        boolean ntkUrl = isNtkUrl(url);
        try {
            if(url == null || url.length() == 0)
                return;
            long now = System.currentTimeMillis();
            synchronized (this) {
                Long lastSync = cookieSyncAt.get(url);
                if(!force && lastSync != null && now - lastSync < COOKIE_SYNC_INTERVAL_MS)
                    return;
                cookieSyncAt.put(url, now);
            }

            String cookieStr = CookieManager.getInstance().getCookie(url);
            if(cookieStr == null || cookieStr.length() == 0)
                return;
            Map<String, String> webViewCookies = new HashMap<>();
            for(String raw : cookieStr.split(";")){
                String s = raw.trim();
                int eq = s.indexOf("=");
                if(eq <= 0)
                    continue;
                String key = s.substring(0, eq);
                String value = s.substring(eq + 1);
                webViewCookies.put(key, value);
            }
            if(webViewCookies.size() == 0)
                return;

            synchronized (this) {
                boolean changed = false;
                boolean clearanceChanged = false;
                for(String key : webViewCookies.keySet()) {
                    String value = webViewCookies.get(key);
                    if("cf_clearance".equalsIgnoreCase(key) && !canAcceptWebViewClearance(value))
                        continue;
                    if(!value.equals(cookies.get(key))) {
                        cookies.put(key, value);
                        changed = true;
                        if("cf_clearance".equalsIgnoreCase(key))
                            clearanceChanged = true;
                    }
                }
                if(changed)
                    persistCookies();
                if(clearanceChanged)
                    saveClearanceToDisk();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        } finally {
            if(ntkUrl)
                ViewerWarmupManager.logMetric("ntk_cookie_sync_ms", System.currentTimeMillis() - startedAt);
        }
    }

    private synchronized boolean canAcceptWebViewClearance(String value) {
        return value != null
                && value.trim().length() >= 20
                && !"deleted".equalsIgnoreCase(value.trim())
                && !"null".equalsIgnoreCase(value.trim());
    }

    private synchronized void loadSavedCookies(){
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String saved = pref.getString("httpCookies", "{}");
            JSONObject obj = new JSONObject(saved);
            for(java.util.Iterator<String> it = obj.keys(); it.hasNext();){
                String k = it.next();
                cookies.put(k, obj.getString(k));
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void loadSavedUserAgent(){
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String saved = pref.getString("httpUserAgent", null);
            if(saved != null && saved.trim().length() > 0)
                agent = saved.trim();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void loadDefaultUserAgent(){
        try {
            String defaultAgent = WebSettings.getDefaultUserAgent(context);
            if(defaultAgent != null && defaultAgent.trim().length() > 0)
                agent = defaultAgent.trim();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void persistCookies(){
        try {
            JSONObject obj = new JSONObject();
            for(String k : cookies.keySet())
                obj.put(k, cookies.get(k));
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("httpCookies", obj.toString())
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void storeResponseCookies(Response response){
        if(response == null)
            return;
        boolean changed = false;
        boolean clearanceChanged = false;
        for(String c : response.headers("Set-Cookie")){
            int eq = c.indexOf("=");
            int semi = c.indexOf(";");
            if(eq <= 0)
                continue;
            if(semi < 0)
                semi = c.length();
            String key = c.substring(0, eq);
            String value = c.substring(eq + 1, semi);
            if(!value.equals(cookies.get(key))) {
                cookies.put(key, value);
                changed = true;
                if("cf_clearance".equalsIgnoreCase(key))
                    clearanceChanged = true;
            }
        }
        if(changed)
            persistCookies();
        if(clearanceChanged)
            saveClearanceToDisk();
    }

    public synchronized String getCookie(String k){
        return cookies.get(k);
    }

    public synchronized String getCookieHeader() {
        StringBuilder builder = new StringBuilder();
        for(String key : cookies.keySet()) {
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(key).append('=').append(cookies.get(key));
        }
        return builder.toString();
    }

    public <T> T runWithRequestGroup(RequestGroup requestGroup, RequestWork<T> work) throws Exception {
        RequestGroup previous = currentRequestGroup.get();
        currentRequestGroup.set(requestGroup);
        try {
            return work.run();
        } finally {
            if(previous == null)
                currentRequestGroup.remove();
            else
                currentRequestGroup.set(previous);
        }
    }

    public <T> T runWithFetchMode(FetchMode fetchMode, RequestWork<T> work) throws Exception {
        FetchMode previous = currentFetchMode.get();
        currentFetchMode.set(fetchMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : fetchMode);
        try {
            return work.run();
        } finally {
            if(previous == null)
                currentFetchMode.remove();
            else
                currentFetchMode.set(previous);
        }
    }

    public boolean isDirectOnlyFetchMode() {
        FetchMode mode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        return mode == FetchMode.DIRECT_ONLY || mode == FetchMode.CACHE_ONLY;
    }

    private FetchMode effectiveFetchMode(FetchMode defaultMode) {
        FetchMode mode = currentFetchMode.get();
        if(mode != null)
            return mode;
        return defaultMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : defaultMode;
    }

    public RequestGroup currentRequestGroup() {
        return currentRequestGroup.get();
    }

    public Response get(String url, Map<String, String> headers){
        applyJitterIfNeeded(url);
        Response response;
        Call call = null;
        RequestGroup requestGroup = currentRequestGroup.get();
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .get();
            if(headers !=null)
                for(String k : headers.keySet()){
                    builder.addHeader(k, headers.get(k));
                }

            Request request = builder.build();
            call = this.client.newCall(request);
            if(requestGroup != null)
                requestGroup.add(call);
            try {
                response = call.execute();
            } catch (SSLException sslException) {
                if(!allowUnsafeFallback(url))
                    throw sslException;
                if(requestGroup != null)
                    requestGroup.remove(call);
                call = this.unsafeFallbackClient.newCall(request);
                if(requestGroup != null)
                    requestGroup.add(call);
                response = call.execute();
            }
            storeResponseCookies(response);
        } catch (Exception e){
            if(shouldRecordRequestFailure(url, e, requestGroup))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
        }
        return response;
    }

    private boolean shouldRecordRequestFailure(String url, Exception e, RequestGroup requestGroup) {
        if(isInterruptedRequest(e) || (requestGroup != null && requestGroup.isCancelled()))
            return false;
        return !(e instanceof SSLException && (isNtkUrl(url) || isNtk()));
    }

    private boolean allowUnsafeFallback(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("wfwf") || lower.contains("wolf") || lower.contains("ntk") || lower.contains("sbxh");
    }

    private void applyJitterIfNeeded(String url) {
        if(url == null)
            return;
        String lower = url.toLowerCase(Locale.ROOT);
        boolean isImage = lower.matches(".*\\.(jpg|jpeg|png|gif|webp|css|js|ico)(\\?.*)?$") || lower.contains("imgcloud") || lower.contains("stm.com") || lower.contains("cloud.com");
        if(isImage)
            return;
        boolean ntk = isNtkUrl(lower) || isNtk();
        if(!ntk)
            return;
        if(ntk && hasCloudflareClearance())
            return;
        try {
            Thread.sleep(30 + (int)(Math.random() * 60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String readBody(Response response) throws Exception {
        if(response == null)
            throw new Exception("Request failed");
        try {
            return response.body() == null ? "" : response.body().string();
        } finally {
            response.close();
        }
    }

    public static byte[] readBytes(Response response) throws Exception {
        if(response == null)
            throw new Exception("Request failed");
        try {
            return response.body() == null ? new byte[0] : response.body().bytes();
        } finally {
            response.close();
        }
    }

    public boolean resolveWfwfDomainNow() {
        if(isNtk())
            return false;
        return ensureNumberedDomain(true);
    }

    public boolean resolveNtkDomainNow() {
        if(!isNtk())
            return false;
        return ensureNumberedDomain(true);
    }

    private boolean ensureWfwfDomainForRetry() {
        if(isNtk())
            return ensureNumberedDomain(true);
        long now = System.currentTimeMillis();
        synchronized (wfwfDomainLock) {
            if(now - wfwfDomainLastForcedRetry < WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS)
                return false;
            wfwfDomainLastForcedRetry = now;
        }
        return ensureNumberedDomain(true);
    }

    private boolean ensureNumberedDomain(boolean force) {
        if(isNtk())
            return ensureNtkDomainIfNeeded(force);
        try {
            String webtoonUrl = getWebtoonUrl();
            String root = WfwfDomainResolver.toRoot(webtoonUrl);
            if(!WfwfDomainResolver.isSupportedNumberedUrl(root))
                return false;

            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            DomainResolveState resolveState;
            boolean shouldResolve = false;
            synchronized (wfwfDomainLock) {
                long lastCheck = pref.getLong("wfwfDomainLastCheck", 0);
                if(!force && now - lastCheck < WFWF_DOMAIN_CHECK_INTERVAL_MS)
                    return false;
                if(wfwfDomainResolveState == null) {
                    wfwfDomainResolveState = new DomainResolveState();
                    pref.edit().putLong("wfwfDomainLastCheck", now).apply();
                    shouldResolve = true;
                }
                resolveState = wfwfDomainResolveState;
            }

            if(!shouldResolve)
                return waitForWfwfDomainResolve(resolveState);

            boolean changed = false;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", agent);
                headers.put("Referer", root);
                String resolved = WfwfDomainResolver.resolve(client, root, headers, currentRequestGroup.get());
                if(resolved != null && !resolved.equals(root)) {
                    p.setWebtoonUrl(resolved);
                    String comicPath = isNtkUrl(resolved) ? "/manhwa" : "/cm";
                    p.setUrl(resolved + comicPath);
                    p.setDefUrl(resolved + comicPath);
                    resetCookie();
                    clearPageCache();
                    changed = true;
                }
                return changed;
            } finally {
                synchronized (wfwfDomainLock) {
                    resolveState.changed = changed;
                    if(wfwfDomainResolveState == resolveState)
                        wfwfDomainResolveState = null;
                }
                resolveState.done.countDown();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private boolean ensureNtkDomainIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        synchronized (wfwfDomainLock) {
            if(!force && ntkDomainLastCheck > 0 && now - ntkDomainLastCheck < NTK_DOMAIN_CHECK_INTERVAL_MS)
                return false;
            ntkDomainLastCheck = now;
        }
        return ensureNtkDomain();
    }

    private boolean ensureNtkDomain() {
        try {
            String currentRoot = WfwfDomainResolver.toRoot(getWebtoonUrl());
            if(!isNtkUrl(currentRoot))
                return false;

            DomainResolveState resolveState;
            boolean shouldResolve = false;
            synchronized (wfwfDomainLock) {
                if(ntkDomainResolveState == null) {
                    ntkDomainResolveState = new DomainResolveState();
                    shouldResolve = true;
                }
                resolveState = ntkDomainResolveState;
            }

            if(!shouldResolve)
                return waitForWfwfDomainResolve(resolveState);

            boolean changed = false;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", agent);
                headers.put("Referer", NtkDomainResolver.CHANNEL_URL);
                List<String> resolvedRoots = NtkDomainResolver.resolveCandidates(client, headers, currentRequestGroup.get());
                String reachable = reachableNtkRoot(currentRoot, resolvedRoots, headers);
                if(reachable != null && isNtkUrl(reachable) && !reachable.equals(currentRoot)) {
                    p.setNtkSitePreset(reachable);
                    resetCookie();
                    clearPageCache();
                    changed = true;
                }
                return changed;
            } finally {
                synchronized (wfwfDomainLock) {
                    resolveState.changed = changed;
                    if(ntkDomainResolveState == resolveState)
                        ntkDomainResolveState = null;
                }
                resolveState.done.countDown();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private String reachableNtkRoot(String currentRoot, List<String> resolvedRoots, Map<String, String> headers) {
        ArrayList<String> candidates = new ArrayList<>();
        if(resolvedRoots != null)
            for(String root : resolvedRoots)
                addNtkRootCandidate(candidates, root);
        addNtkRootCandidate(candidates, currentRoot);
        addNtkRootCandidate(candidates, "https://" + LEGACY_NTK_HOST);
        addNtkRootCandidate(candidates, NTK_WEBTOON_URL);
        for(String candidate : candidates)
            if(canReachNtkRoot(candidate, headers))
                return candidate;
        return resolvedRoots == null || resolvedRoots.isEmpty() ? null : NtkDomainResolver.normalizeRoot(resolvedRoots.get(0));
    }

    private void addNtkRootCandidate(List<String> candidates, String root) {
        root = NtkDomainResolver.normalizeRoot(root);
        if(root == null || root.length() == 0 || !isNtkUrl(root) || candidates.contains(root))
            return;
        candidates.add(root);
    }

    private boolean canReachNtkRoot(String root, Map<String, String> headers) {
        Response response = null;
        Call call = null;
        try {
            OkHttpClient probeClient = client.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build();
            Request.Builder builder = new Request.Builder().url(trimTrailingSlash(root) + "/").get();
            if(headers != null)
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            call = probeClient.newCall(builder.build());
            response = call.execute();
            if(response == null)
                return false;
            int code = response.code();
            String location = response.header("location", "");
            if(location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
                return false;
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if(call != null)
                call.cancel();
            if(response != null)
                response.close();
        }
    }

    private boolean waitForWfwfDomainResolve(DomainResolveState resolveState) {
        if(resolveState == null)
            return false;
        try {
            return resolveState.done.await(WFWF_DOMAIN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    && resolveState.changed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Response mget(String url, Boolean useDefaultCookies){
        return mget(url, useDefaultCookies, new HashMap<>());
    }
    public Response mget(String url){
        return mget(url,true);
    }

    public PageResponse mgetCachedPage(String url, long ttlMillis) throws Exception {
        ensureNumberedDomain(false);
        String normalized = normalizePath(url);
        String cacheKey = getBaseUrl(normalized) + normalized;
        long now = System.currentTimeMillis();
        FetchMode fetchMode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        if(isNtk())
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        PageLoadState activeLoad = null;
        CachedPage staleCached = null;
        synchronized (this) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null) {
                if(!isUsableCachedPage(cached)) {
                    pageCache.remove(cacheKey);
                } else {
                    boolean fresh = isPageCacheFresh(cached.time, now, ttlMillis);
                    if(fresh || shouldServeColdStartCachedPageImmediately(isNtk(), fetchMode, true, fresh))
                        return new PageResponse(cached.code, cached.body, true);
                    staleCached = cached;
                }
            }
        }
        CachedPage diskCached = readDiskCachedPage(cacheKey, now, ttlMillis, isNtk());
        if(diskCached != null) {
            synchronized (this) {
                pageCache.put(cacheKey, diskCached);
            }
            boolean fresh = isPageCacheFresh(diskCached.time, now, ttlMillis);
            if(fresh || shouldServeColdStartCachedPageImmediately(isNtk(), fetchMode, true, fresh))
                return new PageResponse(diskCached.code, diskCached.body, true);
            staleCached = diskCached;
        }
        if(fetchMode == FetchMode.CACHE_ONLY) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Cache miss: " + cacheKey);
        }
        synchronized (this) {
            activeLoad = pageLoads.get(cacheKey);
            if(activeLoad == null)
                pageLoads.put(cacheKey, new PageLoadState());
        }
        if(activeLoad != null)
            return waitForCachedPage(normalized, cacheKey, activeLoad, ttlMillis, staleCached);
        String loadKey = cacheKey;

        PageLoadState loadState;
        synchronized (this) {
            loadState = pageLoads.get(loadKey);
        }
        try {
            return loadPageFromNetworkWithDomainRetry(normalized, now, staleCached);
        } catch (Exception e) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw e;
        } finally {
            synchronized (this) {
                pageLoads.remove(loadKey);
            }
            if(loadState != null)
                loadState.done.countDown();
        }
    }

    public boolean warmupCachedPageDirect(String url, long ttlMillis) {
        try {
            ensureNumberedDomain(false);
            String normalized = normalizePath(url);
            String cacheKey = getBaseUrl(normalized) + normalized;
            long now = System.currentTimeMillis();
            if(isNtk())
                ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
            synchronized (this) {
                CachedPage cached = pageCache.get(cacheKey);
                if(cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                    return true;
                if(cached != null && !isUsableCachedPage(cached))
                    pageCache.remove(cacheKey);
            }
            Response response = mget(normalized, true, null, FetchMode.DIRECT_ONLY);
            if(response == null)
                return false;
            int code = response.code();
            String body = readBody(response);
            if(code >= 200 && code < 400 && body.length() > 0 && looksCacheable(body)) {
                synchronized (this) {
                    pageCache.put(cacheKey, new CachedPage(code, body, now));
                }
                writeDiskCachedPage(cacheKey, new CachedPage(code, body, now));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private PageResponse loadPageFromNetworkWithDomainRetry(String normalized, long now, CachedPage staleCached) throws Exception {
        Exception lastError = null;
        for(int attempt = 0; attempt < 3; attempt++) {
            try {
                return loadPageFromNetwork(normalized, now, staleCached);
            } catch (Exception error) {
                lastError = error;
                if(isNtk())
                    throw error;
                if(attempt == 0)
                    ensureWfwfDomainForRetry();
                client.connectionPool().evictAll();
                unsafeFallbackClient.connectionPool().evictAll();
                sleepBeforeWfwfRetry(attempt);
            }
        }
        throw lastError;
    }

    private void sleepBeforeWfwfRetry(int attempt) {
        try {
            Thread.sleep(180L + (attempt * 220L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PageResponse loadPageFromNetwork(String normalized, long now, CachedPage staleCached) throws Exception {
        Response response = mget(normalized, true, null);
        if(response == null)
            throw new Exception("Request failed: " + normalized);
        int code = response.code();
        String body = readBody(response);
        if(isCloudflareChallenge(code, body)) {
            lastCloudflareChallengeUrl = getBaseUrl(normalized) + normalized;
            lastCloudflareChallengeAt = System.currentTimeMillis();
            clearCloudflareCookies();
            throw new Exception("Cloudflare challenge");
        }
        if(isNtk())
            clearLastCloudflareChallenge();
        if(code >= 500 && staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        if(code >= 200 && code < 400 && body.length() > 0 && looksCacheable(body)) {
            String cacheKey = getBaseUrl(normalized) + normalized;
            CachedPage cachedPage = new CachedPage(code, body, now);
            synchronized (this) {
                pageCache.put(cacheKey, cachedPage);
            }
            writeDiskCachedPage(cacheKey, cachedPage);
        }
        return new PageResponse(code, body, false);
    }

    private PageResponse waitForCachedPage(String normalized, String cacheKey, PageLoadState loadState, long ttlMillis, CachedPage staleCached) throws Exception {
        if(shouldWaitForActivePageLoad(staleCached != null)) {
            try {
                loadState.done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                return new PageResponse(cached.code, cached.body, true);
            if(cached != null && !isUsableCachedPage(cached))
                pageCache.remove(cacheKey);
            String currentCacheKey = getBaseUrl(normalized) + normalized;
            if(!currentCacheKey.equals(cacheKey)) {
                cached = pageCache.get(currentCacheKey);
                if(cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                    return new PageResponse(cached.code, cached.body, true);
                if(cached != null && !isUsableCachedPage(cached))
                    pageCache.remove(currentCacheKey);
            }
        }
        if(staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        throw new Exception("Request failed: " + cacheKey);
    }

    static boolean shouldWaitForActivePageLoadForTest(boolean hasStaleCache) {
        return shouldWaitForActivePageLoad(hasStaleCache);
    }

    private static boolean shouldWaitForActivePageLoad(boolean hasStaleCache) {
        return !hasStaleCache;
    }

    static boolean isPageCacheFreshForTest(long cachedAt, long now, long ttlMillis) {
        return isPageCacheFresh(cachedAt, now, ttlMillis);
    }

    private static boolean isPageCacheFresh(long cachedAt, long now, long ttlMillis) {
        return cachedAt <= now && now - cachedAt < ttlMillis;
    }

    static boolean isPageCacheUsableForColdStartForTest(long cachedAt, long now) {
        return isPageCacheUsableForColdStart(cachedAt, now);
    }

    private static boolean isPageCacheUsableForColdStart(long cachedAt, long now) {
        return cachedAt <= now && now - cachedAt <= PAGE_CACHE_COLD_START_TTL_MS;
    }

    static boolean shouldServeColdStartCachedPageImmediatelyForTest(boolean ntk, FetchMode fetchMode, boolean hasCachedPage, boolean fresh) {
        return shouldServeColdStartCachedPageImmediately(ntk, fetchMode, hasCachedPage, fresh);
    }

    private static boolean shouldServeColdStartCachedPageImmediately(boolean ntk, FetchMode fetchMode, boolean hasCachedPage, boolean fresh) {
        return ntk && hasCachedPage && !fresh && fetchMode != FetchMode.CACHE_ONLY;
    }

    private CachedPage readDiskCachedPage(String cacheKey, long now, long ttlMillis, boolean allowColdStartStale) {
        if(cacheKey == null)
            return null;
        try {
            String json = CacheFileStore.read(context, PAGE_CACHE_PREFIX + cacheKey);
            if(json == null || json.length() == 0)
                return null;
            PersistedPage page = GSON.fromJson(json, PersistedPage.class);
            if(page == null || page.body == null || page.body.length() == 0)
                return null;
            if(!isCacheablePageBody(page.body)) {
                CacheFileStore.delete(context, PAGE_CACHE_PREFIX + cacheKey);
                return null;
            }
            boolean usable = isPageCacheFresh(page.time, now, ttlMillis)
                    || (allowColdStartStale && isPageCacheUsableForColdStart(page.time, now));
            if(!usable) {
                CacheFileStore.delete(context, PAGE_CACHE_PREFIX + cacheKey);
                return null;
            }
            return new CachedPage(page.code, page.body, page.time);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    private void writeDiskCachedPage(String cacheKey, CachedPage page) {
        if(!isNtk() || cacheKey == null || page == null || page.body == null || page.body.length() == 0
                || !isCacheablePageBody(page.body))
            return;
        try {
            CacheFileStore.write(context, PAGE_CACHE_PREFIX + cacheKey, GSON.toJson(new PersistedPage(page)));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public synchronized void clearPageCache() {
        pageCache.clear();
    }


    public String getUrl(){
        return getComicUrl();
    }

    public String getUrl(int baseMode){
        if(baseMode == MTitle.base_webtoon)
            return getWebtoonUrl();
        return getComicUrl();
    }

    public String getUrl(String path){
        return getBaseUrl(path);
    }

    private String getComicUrl(){
        String url = p.getUrl();
        if(url == null || url.length() == 0)
            url = DEFAULT_COMIC_URL;
        return trimTrailingSlash(url);
    }

    private String getWebtoonUrl(){
        String url = p.getWebtoonUrl();
        if(url == null || url.length() == 0)
            url = WEBTOON_URL;
        return trimTrailingSlash(url);
    }

    private String getBaseUrl(String path){
        if(isWebtoonPath(path))
            return getWebtoonUrl();
        return getRootUrl(getComicUrl());
    }

    public boolean isNtk() {
        return isNtkUrl(getWebtoonUrl()) || isNtkUrl(getComicUrl());
    }

    public boolean isNtkUrl(String url) {
        return isNtkUrlForTest(url);
    }

    static boolean isNtkUrlForTest(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("://ntk")
                || lower.contains("://newto")
                || lower.contains("://newtoki")
                || lower.contains("://" + NTK_HOST)
                || lower.contains("://www." + NTK_HOST)
                || lower.contains("://sbxh")
                || lower.contains("://www.sbxh")
                || lower.contains(".sbxh");
    }

    private boolean isWebtoonPath(String path){
        if(path == null)
            return false;
        return path.startsWith("/webtoon")
                || path.startsWith("webtoon")
                || path.startsWith("/ing")
                || path.startsWith("/end")
                || path.startsWith("/manhwa")
                || path.startsWith("/list?toon=")
                || path.startsWith("/view?toon=")
                || path.startsWith("/search.html")
                || path.contains("bo_table=webtoon");
    }

    private boolean isNtkPagePath(String path) {
        if(path == null)
            return false;
        return path.startsWith("/")
                && !path.matches(".*\\.(jpg|jpeg|png|gif|webp|css|js|ico)(\\?.*)?$");
    }

    private String getRootUrl(String url){
        String trimmed = trimTrailingSlash(url);
        if(trimmed.endsWith("/cm"))
            return trimmed.substring(0, trimmed.length() - 3);
        if(trimmed.endsWith("/manhwa"))
            return trimmed.substring(0, trimmed.length() - 7);
        return trimmed;
    }

    private String trimTrailingSlash(String url){
        while(url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        return url;
    }


    public Response mget(String url, Boolean useDefaultCookies, Map<String, String> customCookie){
        return mget(url, useDefaultCookies, customCookie, FetchMode.ALLOW_SHARED_WEBVIEW);
    }

    private Response mget(String url, Boolean useDefaultCookies, Map<String, String> customCookie, FetchMode requestedFetchMode){
        FetchMode requested = requestedFetchMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : requestedFetchMode;
        FetchMode fetchMode = requested == FetchMode.ALLOW_SHARED_WEBVIEW
                ? effectiveFetchMode(requested)
                : requested;
        if(fetchMode == FetchMode.CACHE_ONLY)
            return null;
        ensureNumberedDomain(false);
        if(customCookie==null)
            customCookie = new HashMap<>();
        url = normalizePath(url);
        String baseUrl = getBaseUrl(url);
        Map<String, String> headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
        applyNtkApiHeaders(headers, baseUrl, url);

        Response response = get(baseUrl + url, headers);
        if(isNtkUrl(baseUrl) && shouldRetryWithResolvedDomain(response)) {
            if(response != null)
                response.close();
            ensureWfwfDomainForRetry();
            baseUrl = getBaseUrl(url);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            applyNtkApiHeaders(headers, baseUrl, url);
            response = get(baseUrl + url, headers);
        }
        if(shouldUseNtkWebViewFallback(isNtkUrl(baseUrl),
                response == null || isNtkWebViewFallbackCandidate(response, url), url, fetchMode)) {
            if(response != null)
                response.close();
            response = getWithNtkWebViewFallback(baseUrl, url, headers);
        }
        if(shouldRetryWithResolvedDomain(response)) {
            if(response != null)
                response.close();
            ensureWfwfDomainForRetry();
            baseUrl = getBaseUrl(url);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            applyNtkApiHeaders(headers, baseUrl, url);
            response = get(baseUrl + url, headers);
            if(shouldUseNtkWebViewFallback(isNtkUrl(baseUrl),
                    response == null || isNtkWebViewFallbackCandidate(response, url), url, fetchMode)) {
                if(response != null)
                    response.close();
                response = getWithNtkWebViewFallback(baseUrl, url, headers);
            }
        }
        return response;
    }

    private boolean isNtkWebViewFallbackCandidate(Response response, String path) {
        if(response == null || path == null || !(path.startsWith("/webtoon/") || path.startsWith("/manhwa/")))
            return false;
        int code = response.code();
        String location = response.header("location", "");
        if((code == 301 || code == 302) && location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
            return false;
        return code == 301 || code == 302 || code == 403 || code == 404 || code >= 500;
    }

    private Response getWithNtkWebViewFallback(String baseUrl, String path, Map<String, String> headers) {
        return NtkWebViewFallbackManager.get(context).fetch(agent, baseUrl, path, headers);
    }

    static String buildNtkWebViewFetchScript(String path, Map<String, String> headers) {
        return buildNtkWebViewFetchScript(path, headers, null);
    }

    static String buildNtkWebViewFetchScript(String path, Map<String, String> headers, String token) {
        String accept = headers == null ? null : headers.get("Accept");
        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("try{");
        js.append("var done=false;");
        if(token == null)
            js.append("function finish(v){if(done)return;done=true;window.NtkBridge.onResult(JSON.stringify(v));}");
        else
            js.append("function finish(v){if(done)return;done=true;window.NtkBridge.onFetchResult(").append(jsQuote(token)).append(",JSON.stringify(v));}");
        js.append("var x=new XMLHttpRequest();");
        js.append("x.open('GET',").append(jsQuote(path)).append(",true);");
        js.append("x.withCredentials=true;");
        js.append("x.timeout=8000;");
        if(accept != null && accept.length() > 0)
            js.append("x.setRequestHeader('Accept'," + jsQuote(accept) + ");");
        js.append("x.onreadystatechange=function(){if(x.readyState===4)finish({code:x.status,body:x.responseText||''});};");
        js.append("x.onerror=function(){finish({code:0,error:'error'});};");
        js.append("x.ontimeout=function(){finish({code:0,error:'timeout'});};");
        js.append("x.send(null);");
        js.append("}catch(e){window.NtkBridge.onResult(JSON.stringify({code:0,error:String(e)}));}");
        js.append("})()");
        return js.toString();
    }

    static String buildNtkWebViewFetchScriptForTest(String path, String accept) {
        Map<String, String> headers = new HashMap<>();
        if(accept != null)
            headers.put("Accept", accept);
        return buildNtkWebViewFetchScript(path, headers);
    }

    private static String jsQuote(String value) {
        if(value == null)
            return "null";
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for(int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch(c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    static Response responseFromWebViewFetch(String baseUrl, String path, String value) throws Exception {
        if(value == null || value.length() == 0 || "null".equals(value))
            return null;
        String clean = value;
        if(clean.startsWith("\"") && clean.endsWith("\""))
            clean = new JSONArray("[" + value + "]").getString(0);
        JSONObject json = new JSONObject(clean);
        int code = json.optInt("code", 0);
        if(code <= 0)
            return null;
        String body = json.optString("body", "");
        Request request = new Request.Builder().url(baseUrl + path).build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("WebView")
                .body(ResponseBody.create(body, MediaType.parse("text/plain; charset=utf-8")))
                .build();
    }

    static boolean shouldUseNtkWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path) {
        return shouldUseNtkWebViewFallbackForTest(ntkUrl, missingResponse, path, FetchMode.ALLOW_SHARED_WEBVIEW);
    }

    static boolean shouldUseNtkWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        return shouldUseNtkWebViewFallback(ntkUrl, missingResponse, path, fetchMode);
    }

    private static boolean shouldUseNtkWebViewFallback(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        if(fetchMode != FetchMode.ALLOW_SHARED_WEBVIEW || !ntkUrl || !missingResponse || path == null)
            return false;
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/");
    }

    private Map<String, String> buildHeaders(String baseUrl, Boolean useDefaultCookies, Map<String, String> customCookie) {
        Map<String, String> cookie = new HashMap<>();
        if(Boolean.TRUE.equals(useDefaultCookies)) {
            if(!shouldSkipWebViewCookieSync(baseUrl))
                syncCookiesFromWebView(baseUrl);
            synchronized (this) {
                cookie.putAll(this.cookies);
            }
        }
        if(customCookie != null)
            cookie.putAll(customCookie);

        StringBuilder cbuilder = new StringBuilder();
        for(String key : cookie.keySet()){
            cbuilder.append(key);
            cbuilder.append('=');
            cbuilder.append(cookie.get(key));
            cbuilder.append("; ");
        }
        if(cbuilder.length()>2)
            cbuilder.delete(cbuilder.length()-2,cbuilder.length());

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cbuilder.toString());
        headers.put("User-Agent", agent);
        headers.put("Referer", baseUrl);
        if(isNtkUrl(baseUrl)) {
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Cache-Control", "max-age=0");
            headers.put("Upgrade-Insecure-Requests", "1");
            headers.put("Sec-Fetch-Dest", "document");
            headers.put("Sec-Fetch-Mode", "navigate");
            headers.put("Sec-Fetch-Site", "same-origin");
            headers.put("Sec-Fetch-User", "?1");
            putClientHintHeaders(headers);
        }
        return headers;
    }

    private boolean shouldSkipWebViewCookieSync(String baseUrl) {
        if(!isNtkUrl(baseUrl))
            return false;
        if(hasFreshCloudflareClearance())
            return true;
        FetchMode mode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        return mode == FetchMode.DIRECT_ONLY || mode == FetchMode.CACHE_ONLY;
    }

    private void applyNtkApiHeaders(Map<String, String> headers, String baseUrl, String path) {
        if(headers == null || !isNtkUrl(baseUrl) || path == null || !path.startsWith("/api/"))
            return;
        headers.put("Accept", "application/json,text/plain,*/*");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.remove("Upgrade-Insecure-Requests");
        headers.remove("Sec-Fetch-User");
    }

    private void putClientHintHeaders(Map<String, String> headers) {
        int chromeMajor = chromeMajorVersion(agent);
        String version = chromeMajor > 0 ? String.valueOf(chromeMajor) : "147";
        headers.put("sec-ch-ua", "\"Chromium\";v=\"" + version + "\", \"Android WebView\";v=\"" + version + "\", \"Not A(Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-ch-ua-platform", "\"Android\"");
    }

    private static int chromeMajorVersion(String userAgent) {
        try {
            if(userAgent == null)
                return -1;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(userAgent);
            if(!matcher.find())
                return -1;
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean shouldRetryWithResolvedDomain(Response response) {
        if(response == null)
            return true;
        int code = response.code();
        if(isNtk())
            return code == 301 || code == 302 || code == 404 || code >= 500;
        return code == 301 || code == 302 || code == 403 || code == 404 || code >= 500;
    }

    private String normalizePath(String url) {
        if(url == null || url.length() == 0)
            return "/";
        return url.startsWith("/") ? url : "/" + url;
    }

    private boolean looksCacheable(String body) {
        if(!isCacheablePageBody(body))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webtoon-list")
                || lower.contains("searchitem")
                || lower.contains("toon=")
                || lower.contains("/webtoon/")
                || lower.contains("/manhwa/")
                || lower.contains("image-view")
                || lower.contains("webtoon-body")
                || lower.contains("miso-post-gallery")
                || lower.contains("post-row");
    }

    static boolean isCacheablePageBodyForTest(String body) {
        return isCacheablePageBody(body);
    }

    private static boolean isUsableCachedPage(CachedPage page) {
        return page != null && page.body != null && page.body.length() > 0 && isCacheablePageBody(page.body);
    }

    private static boolean isCacheablePageBody(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return !lower.contains("<title>webpage not available</title>")
                && !lower.contains("webpage not available")
                && !lower.contains("net::err_")
                && !lower.contains("err_connection_reset")
                && !lower.contains("err_name_not_resolved")
                && !lower.contains("err_timed_out")
                && !lower.contains("error code 522")
                && !lower.contains("connection timed out")
                && !lower.contains("just a moment")
                && !lower.contains("challenges.cloudflare.com")
                && !lower.contains("cf-challenge")
                && !lower.contains("cf_chl")
                && !lower.contains("cf-mitigated")
                && !lower.contains("turnstile");
    }

    private boolean isCloudflareChallenge(int code, String body) {
        if(body == null)
            return false;
        if(code >= 500) {
            String lower = body.toLowerCase(Locale.ROOT);
            return lower.contains("cloudflare")
                    || lower.contains("error code 522")
                    || lower.contains("connection timed out");
        }
        if(code != 403)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(looksLikeNtkNormalPage(lower))
            return false;
        return lower.contains("cf-mitigated")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("cf-challenge")
                || lower.contains("cf-turnstile")
                || lower.contains("cf-please-wait")
                || lower.contains("turnstile")
                || lower.contains("__cf_bm")
                || lower.contains("checking your browser")
                || lower.contains("just a moment");
    }

    public boolean isCloudflareChallengeResponse(int code, String body) {
        return isCloudflareChallenge(code, body);
    }

    private boolean looksLikeNtkNormalPage(String lowerBody) {
        if(lowerBody == null || lowerBody.length() < 500)
            return false;
        return (lowerBody.contains("newtoki") || lowerBody.contains("뉴토끼"))
                && (lowerBody.contains("실시간 웹툰 랭킹")
                || lowerBody.contains("실시간 만화 랭킹")
                || lowerBody.contains("/webtoon/")
                || lowerBody.contains("/manhwa/")
                || lowerBody.contains("webtoon-list"));
    }

    public static class PageResponse {
        public final int code;
        public final String body;
        public final boolean fromCache;

        PageResponse(int code, String body, boolean fromCache) {
            this.code = code;
            this.body = body;
            this.fromCache = fromCache;
        }
    }

    private static class CachedPage {
        final int code;
        final String body;
        final long time;

        CachedPage(int code, String body, long time) {
            this.code = code;
            this.body = body;
            this.time = time;
        }
    }

    private static class PersistedPage {
        int code;
        String body;
        long time;

        PersistedPage() {
        }

        PersistedPage(CachedPage page) {
            this.code = page.code;
            this.body = page.body;
            this.time = page.time;
        }
    }

    private static class DomainResolveState {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean changed = false;
    }

    private static class PageLoadState {
        final CountDownLatch done = new CountDownLatch(1);
    }

    public Response post(String url, RequestBody body, Map<String,String> headers){
        return post(url,body,headers,false);
    }

    public Response post(String url, RequestBody body, Map<String,String> headers, boolean localCookies){

        if(localCookies)
            syncCookiesFromWebView(getBaseUrl(url));

        StringBuilder cs = new StringBuilder();
        //get cookies from headers
        if(headers.get("Cookie") != null)
            cs.append(headers.get("Cookie"));

        // add local cookies
        if(localCookies)
            synchronized (this) {
                for(String key : this.cookies.keySet()){
                    cs.append(key).append('=').append(this.cookies.get(key)).append("; ");
                }
            }

        headers.put("Cookie", cs.toString());

        Response response = null;
        Call call = null;
        RequestGroup requestGroup = currentRequestGroup.get();
        try {
            Request.Builder builder = new Request.Builder()
                    .addHeader("User-Agent", agent)
                    .url(url)
                    .post(body);

            for(String key: headers.keySet()){
                builder.addHeader(key, headers.get(key));
            }

            Request request = builder.build();
            call = this.client.newCall(request);
            if(requestGroup != null)
                requestGroup.add(call);
            response = call.execute();
            storeResponseCookies(response);
        }catch (Exception e){
            if(!isInterruptedRequest(e) && (requestGroup == null || !requestGroup.isCancelled()))
                ml.melun.mangaview.report.CrashReporter.record(e);
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
        }
        return response;

    }

    private static boolean isInterruptedRequest(Exception e) {
        return e instanceof InterruptedIOException
                || Thread.currentThread().isInterrupted()
                || "Canceled".equals(e.getMessage());
    }

    public interface RequestWork<T> {
        T run() throws Exception;
    }

    public static class RequestGroup {
        private final Set<Call> calls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private volatile boolean cancelled = false;

        void add(Call call) {
            synchronized (calls) {
                if(cancelled) {
                    call.cancel();
                    return;
                }
                calls.add(call);
            }
        }

        void remove(Call call) {
            calls.remove(call);
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
            synchronized (calls) {
                for(Call call : calls)
                    call.cancel();
                calls.clear();
            }
        }
    }


    public Response post(String url, RequestBody body){
//        if(!isloaded){
//            cloudflareDns.create();
//            isloaded = true;
//        }
        return post(url, body, new HashMap<>());
    }

    /*
    code source : https://gist.github.com/chalup/8706740
     */

    private static OkHttpClient.Builder getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType){
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType){
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private static OkHttpClient.Builder configureDispatcher(OkHttpClient.Builder builder) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(MAX_HTTP_REQUESTS);
        dispatcher.setMaxRequestsPerHost(MAX_HTTP_REQUESTS_PER_HOST);
        return builder.dispatcher(dispatcher);
    }

    private static OkHttpClient.Builder baseClient(OkHttpClient.Builder builder) {
        OkHttpClient.Builder configured = configureDispatcher(builder)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dns(IPV4_FIRST_DNS);
        if(android.os.Build.VERSION.SDK_INT < CODE_SCOPED_STORAGE) {
            List<CipherSuite> cipherSuites = new ArrayList<>(ConnectionSpec.MODERN_TLS.cipherSuites());
            cipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA);
            cipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA);

            ConnectionSpec legacyTls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .cipherSuites(cipherSuites.toArray(new CipherSuite[0]))
                    .build();
            configured.connectionSpecs(Arrays.asList(legacyTls, ConnectionSpec.CLEARTEXT));
        }
        return configured;
    }

    static List<Protocol> ntkTlsFallbackProtocolsForTest() {
        return java.util.Collections.singletonList(Protocol.HTTP_1_1);
    }

}
