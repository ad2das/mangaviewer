package ml.melun.mangaview.mangaview;


import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.InterruptedIOException;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.CODE_SCOPED_STORAGE;

public class CustomHttpClient {
    public static final String DEFAULT_COMIC_URL = "https://wfwf449.com/cm";
    public static final String WEBTOON_URL = "https://wfwf449.com";
    private static final long WFWF_DOMAIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS = 60 * 1000L;
    private static final long WFWF_DOMAIN_WAIT_TIMEOUT_MS = 6 * 1000L;
    private static final long COOKIE_SYNC_INTERVAL_MS = 30 * 1000L;
    private static final int PAGE_CACHE_MAX_ENTRIES = 80;
    private static final int MAX_HTTP_REQUESTS = 8;
    private static final int MAX_HTTP_REQUESTS_PER_HOST = 4;
    public OkHttpClient client;
    private OkHttpClient unsafeFallbackClient;
    Map<String, String> cookies;
    Map<String, Long> cookieSyncAt;
    Map<String, CachedPage> pageCache;
    Map<String, PageLoadState> pageLoads;
    private final ThreadLocal<RequestGroup> currentRequestGroup = requestGroupLocal();

    private ThreadLocal<RequestGroup> requestGroupLocal() {
        return new
                ThreadLocal<>();
    }
    private final Object wfwfDomainLock = new Object();
    private DomainResolveState wfwfDomainResolveState;
    private long wfwfDomainLastForcedRetry = 0;
    private Context context;
    public String agent = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

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
        loadSavedCookies();
        this.client = baseClient(new OkHttpClient.Builder()).build();
        this.unsafeFallbackClient = baseClient(getUnsafeOkHttpClient()).build();

        //this.cfc = new HashMap<>();
        //this.client = new OkHttpClient.Builder().build();
    }

    public synchronized void setCookie(String k, String v){
        cookies.put(k, v);
        persistCookies();
    }
    public synchronized void resetCookie(){
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        persistCookies();
    }

    public void syncCookiesFromWebView(String url){
        try {
            long now = System.currentTimeMillis();
            synchronized (this) {
                Long lastSync = cookieSyncAt.get(url);
                if(lastSync != null && now - lastSync < COOKIE_SYNC_INTERVAL_MS)
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
                for(String key : webViewCookies.keySet()) {
                    String value = webViewCookies.get(key);
                    if(!value.equals(cookies.get(key))) {
                        cookies.put(key, value);
                        changed = true;
                    }
                }
                if(changed)
                    persistCookies();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
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
            }
        }
        if(changed)
            persistCookies();
    }

    public synchronized String getCookie(String k){
        return cookies.get(k);
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

    public RequestGroup currentRequestGroup() {
        return currentRequestGroup.get();
    }

    public Response get(String url, Map<String, String> headers){
//        System.out.println(url);
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
            if(!isInterruptedRequest(e) && (requestGroup == null || !requestGroup.isCancelled()))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
        }
        return response;
    }

    private boolean allowUnsafeFallback(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("wfwf") || lower.contains("wolf");
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
        return ensureWfwfDomain(true);
    }

    private boolean ensureWfwfDomainForRetry() {
        long now = System.currentTimeMillis();
        synchronized (wfwfDomainLock) {
            if(now - wfwfDomainLastForcedRetry < WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS)
                return false;
            wfwfDomainLastForcedRetry = now;
        }
        return ensureWfwfDomain(true);
    }

    private boolean ensureWfwfDomain(boolean force) {
        try {
            String webtoonUrl = getWebtoonUrl();
            String root = WfwfDomainResolver.toRoot(webtoonUrl);
            if(!WfwfDomainResolver.isWfwfUrl(root))
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
                    p.setUrl(resolved + "/cm");
                    p.setDefUrl(resolved + "/cm");
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
        ensureWfwfDomain(false);
        String normalized = normalizePath(url);
        String cacheKey = getBaseUrl(normalized) + normalized;
        long now = System.currentTimeMillis();
        PageLoadState activeLoad = null;
        CachedPage staleCached = null;
        synchronized (this) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null && now - cached.time < ttlMillis)
                return new PageResponse(cached.code, cached.body, true);
            staleCached = cached;
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
            Response response = mget(normalized, true, null);
            if(response == null)
                throw new Exception("Request failed: " + normalized);
            int code = response.code();
            String body = readBody(response);
            if(code >= 500 && staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            if(code >= 200 && code < 400 && body.length() > 0 && looksCacheable(body)) {
                cacheKey = getBaseUrl(normalized) + normalized;
                synchronized (this) {
                    pageCache.put(cacheKey, new CachedPage(code, body, now));
                }
            }
            return new PageResponse(code, body, false);
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

    private PageResponse waitForCachedPage(String normalized, String cacheKey, PageLoadState loadState, long ttlMillis, CachedPage staleCached) throws Exception {
        try {
            loadState.done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null && now - cached.time < ttlMillis)
                return new PageResponse(cached.code, cached.body, true);
            String currentCacheKey = getBaseUrl(normalized) + normalized;
            if(!currentCacheKey.equals(cacheKey)) {
                cached = pageCache.get(currentCacheKey);
                if(cached != null && now - cached.time < ttlMillis)
                    return new PageResponse(cached.code, cached.body, true);
            }
        }
        if(staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        throw new Exception("Request failed: " + cacheKey);
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

    private boolean isWebtoonPath(String path){
        if(path == null)
            return false;
        return path.startsWith("/webtoon")
                || path.startsWith("webtoon")
                || path.startsWith("/ing")
                || path.startsWith("/end")
                || path.startsWith("/list?toon=")
                || path.startsWith("/view?toon=")
                || path.startsWith("/search.html")
                || path.contains("bo_table=webtoon");
    }

    private String getRootUrl(String url){
        String trimmed = trimTrailingSlash(url);
        if(trimmed.endsWith("/cm"))
            return trimmed.substring(0, trimmed.length() - 3);
        return trimmed;
    }

    private String trimTrailingSlash(String url){
        while(url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        return url;
    }


    public Response mget(String url, Boolean useDefaultCookies, Map<String, String> customCookie){
        ensureWfwfDomain(false);
        if(customCookie==null)
            customCookie = new HashMap<>();
        url = normalizePath(url);
        String baseUrl = getBaseUrl(url);
        Map<String, String> headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);

        Response response = get(baseUrl + url, headers);
        if(shouldRetryWithResolvedDomain(response)) {
            if(response != null)
                response.close();
            ensureWfwfDomainForRetry();
            baseUrl = getBaseUrl(url);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            response = get(baseUrl + url, headers);
        }
        return response;
    }

    private Map<String, String> buildHeaders(String baseUrl, Boolean useDefaultCookies, Map<String, String> customCookie) {
        Map<String, String> cookie = new HashMap<>();
        if(Boolean.TRUE.equals(useDefaultCookies)) {
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
        return headers;
    }

    private boolean shouldRetryWithResolvedDomain(Response response) {
        if(response == null)
            return true;
        int code = response.code();
        return code == 301 || code == 302 || code == 403 || code == 404 || code >= 500;
    }

    private String normalizePath(String url) {
        if(url == null || url.length() == 0)
            return "/";
        return url.startsWith("/") ? url : "/" + url;
    }

    private boolean looksCacheable(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webtoon-list")
                || lower.contains("searchitem")
                || lower.contains("toon=")
                || lower.contains("image-view")
                || lower.contains("webtoon-body")
                || lower.contains("miso-post-gallery")
                || lower.contains("post-row");
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
                .readTimeout(20, TimeUnit.SECONDS);
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

}
