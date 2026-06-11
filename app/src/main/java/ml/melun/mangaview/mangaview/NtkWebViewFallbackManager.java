package ml.melun.mangaview.mangaview;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class NtkWebViewFallbackManager {
    private static final String TAG = "ViewerPerf";
    private static final long WEBVIEW_LOAD_TIMEOUT_MS = 22_000L;
    private static final long CALLER_WAIT_TIMEOUT_MS = 38_000L;
    private static final long DOCUMENT_READY_WAIT_MS = 18_000L;
    private static final long HIDDEN_CHALLENGE_WAIT_MS = 2_500L;
    private static final long PRIORITY_WOLF_DOCUMENT_READY_WAIT_MS = 2_500L;
    private static final long PRIORITY_WOLF_LOAD_TIMEOUT_MS = 6_000L;
    private static final long PRIORITY_NTK_VIEWER_IMAGE_GRACE_MS = 2_500L;
    private static final long VIEWER_IMAGE_CACHE_TTL_MS = 45_000L;
    private static final Object INSTANCE_LOCK = new Object();
    private static final Object VIEWER_IMAGE_FLIGHT_LOCK = new Object();
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){"
            + "if(window.__ntkViewerQuicBridgeInstalled||!window.NtkQuicBridge)return;"
            + "window.__ntkViewerQuicBridgeInstalled=1;"
            + "function parseUrl(u){try{return new URL(u,location.href);}catch(e){return null;}}"
            + "function ntkRootHost(){var h=(location.hostname||'').toLowerCase();return h.indexOf('www.')===0?h.slice(4):h;}"
            + "function hostMatchesRoot(h){h=String(h||'').toLowerCase();if(h.indexOf('www.')===0)h=h.slice(4);var r=ntkRootHost();return !!r&&(h===r||h.slice(-(r.length+1))==='.'+r);}"
            + "function shouldBridge(u,m){var x=parseUrl(u);if(!x||x.protocol!=='https:')return false;if(!hostMatchesRoot(x.hostname))return false;if(x.pathname.indexOf('/cdn-cgi/challenge-platform/')===0)return false;if(x.pathname==='/api/ad/guard-js'||x.pathname==='/api/ad/guard-wasm')return true;return String(m||'GET').toUpperCase()!=='GET';}"
            + "function textBase64(s){return btoa(unescape(encodeURIComponent(s||'')));}"
            + "function bodyBase64(b){try{if(b==null)return '';if(typeof b==='string')return textBase64(b);if(window.URLSearchParams&&b instanceof URLSearchParams)return textBase64(b.toString());if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}return textBase64(String(b));}catch(e){return '';}}"
            + "function bodyBase64Async(b){try{if(b&&window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return bodyBase64(a);});if(b&&window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return bodyBase64(a);});}catch(e){}return Promise.resolve(bodyBase64(b));}"
            + "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}"
            + "function textFromBase64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
            + "function noteAck(url,res,body64){try{if(String(url||'').indexOf('/api/ad/ack')<0)return;var req={},body={};try{req=JSON.parse(textFromBase64(body64)||'{}');}catch(e){}try{body=JSON.parse(textFromBase64(res&&res.bodyBase64)||'{}');}catch(e){}if((res.status||0)!==200||!(body.ok||body.acked||body.status==='ok'||body.status==='acked'))return;var p=req.path||location.pathname||'';window.__ntk_ad_ack_scope=p;window.__ntk_ad_ack_last={scope:p,ts:Date.now(),bridge:true};window.__ntk_ad_ack_tp=req.tp||'';try{window.NtkViewerBridge.onAckProof(req.tp||'');}catch(e){}window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:p,bridge:true,tp:req.tp||''}}));}catch(e){}}"
            + "function collectHeaders(input,init){var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;}"
            + "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';}catch(e){}return h;}"
            + "var nativeFetch=window.fetch;try{if(nativeFetch&&!window.__ntkNativeFetch)window.__ntkNativeFetch=nativeFetch;}catch(e){}if(nativeFetch){window.fetch=function(input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';var method=(init&&init.method)||(input&&input.method)||'GET';if(!shouldBridge(url,method))return nativeFetch.apply(this,arguments);return new Promise(function(resolve,reject){try{var absolute=new URL(url,location.href).href;var hasInitBody=init&&Object.prototype.hasOwnProperty.call(init,'body');var bodyArg=hasInitBody?init.body:((input&&window.Request&&input instanceof Request)?input:null);bodyBase64Async(bodyArg).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addDefaultHeaders(collectHeaders(input,init))),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));}catch(e){reject(e);}},reject);}catch(e){reject(e);}});};}"
            + "var xhrOpen=window.XMLHttpRequest&&XMLHttpRequest.prototype.open;var xhrSend=window.XMLHttpRequest&&XMLHttpRequest.prototype.send;var xhrSetHeader=window.XMLHttpRequest&&XMLHttpRequest.prototype.setRequestHeader;if(xhrOpen&&xhrSend){XMLHttpRequest.prototype.open=function(m,u,a,user,pw){this.__ntkq={method:m||'GET',url:u||'',headers:{}};return xhrOpen.apply(this,arguments);};XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);return xhrSetHeader?xhrSetHeader.apply(this,arguments):undefined;};XMLHttpRequest.prototype.send=function(body){var meta=this.__ntkq;if(!meta||!shouldBridge(meta.url,meta.method))return xhrSend.apply(this,arguments);var xhr=this;setTimeout(function(){try{var absolute=new URL(meta.url,location.href).href,body64=bodyBase64(body);var raw=window.NtkQuicBridge.request(absolute,String(meta.method),JSON.stringify(addDefaultHeaders(meta.headers||{})),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);var headers=res.headers||{},headerText='';Object.keys(headers).forEach(function(k){headerText+=k+': '+headers[k]+'\\r\\n';});var arr=bytesFromBase64(res.bodyBase64||''),response=arr;if(!xhr.responseType||xhr.responseType==='text'){var bin='';for(var i=0;i<arr.length;i++)bin+=String.fromCharCode(arr[i]);response=decodeURIComponent(escape(bin));}Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});Object.defineProperty(xhr,'status',{configurable:true,get:function(){return res.status||200;}});Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return res.statusText||'OK';}});Object.defineProperty(xhr,'response',{configurable:true,get:function(){return response;}});Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return typeof response==='string'?response:'';}});xhr.getAllResponseHeaders=function(){return headerText;};xhr.getResponseHeader=function(n){var l=String(n||'').toLowerCase();for(var k in headers){if(k.toLowerCase()===l)return headers[k];}return null;};['readystatechange','load','loadend'].forEach(function(n){var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);});}catch(e){var ev=new Event('error');xhr.dispatchEvent(ev);if(typeof xhr.onerror==='function')xhr.onerror.call(xhr,ev);}},0);};}"
            + "var nativeBeacon=navigator.sendBeacon;try{if(nativeBeacon&&!window.__ntkNativeBeacon)window.__ntkNativeBeacon=nativeBeacon;}catch(e){}if(nativeBeacon){navigator.sendBeacon=function(url,data){if(!shouldBridge(url,'POST'))return nativeBeacon.apply(this,arguments);try{var absolute=new URL(url,location.href).href;bodyBase64Async(data).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,'POST','{}',body64);noteAck(absolute,JSON.parse(raw||'{}'),body64);}catch(e){}});return true;}catch(e){return false;}};}"
            + "function rearmAck(reason){try{var p=location.pathname||'';if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(p))window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:reason,scope:p}}));}catch(e){}}"
            + "var rearmCount=0,rearmTimer=setInterval(function(){rearmAck('native-bridge-ready');if(++rearmCount>=10)clearInterval(rearmTimer);},250);setTimeout(function(){rearmAck('native-bridge-ready');},0);"
            + "})();";
    static WeakReference<NtkWebViewFallbackManager> instanceRef;
    private static final Map<String, CachedViewerImages> VIEWER_IMAGE_API_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ViewerImageFlight> VIEWER_IMAGE_FLIGHTS = new HashMap<>();

    private final Object lock = new Object();
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<FetchTask> queue = new ArrayDeque<>();
    private final Map<String, FetchTask> inFlight = new HashMap<>();

    private WebView webView;
    private FetchTask activeTask;
    private long nextToken = 1L;
    private String protectedViewerPath = "";
    private long protectedViewerUntil = 0L;
    private long protectedResumeAt = 0L;

    private NtkWebViewFallbackManager(Context context) {
        this.context = context.getApplicationContext();
    }

    static NtkWebViewFallbackManager get(Context context) {
        synchronized (INSTANCE_LOCK) {
            NtkWebViewFallbackManager instance = instanceRef == null ? null : instanceRef.get();
            if(instance == null)
                instance = new NtkWebViewFallbackManager(context);
            instanceRef = new WeakReference<>(instance);
            return instance;
        }
    }

    Response fetch(String userAgent, String baseUrl, String path, Map<String, String> headers) {
        return fetch(userAgent, baseUrl, path, headers, null);
    }

    Response fetch(String userAgent, String baseUrl, String path, Map<String, String> headers,
                   CustomHttpClient.RequestGroup requestGroup) {
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null) {
            Log.d(TAG, "ntk_webview_fetch_skip path=" + path
                    + ",main=" + (Looper.myLooper() == Looper.getMainLooper())
                    + ",baseNull=" + (baseUrl == null));
            return null;
        }
        if(requestGroup != null && requestGroup.isCancelled()) {
            Log.d(TAG, "ntk_webview_fetch_skip_cancelled path=" + path);
            return null;
        }
        FetchTask task;
        boolean reused = false;
        String key = fetchKey(baseUrl, path);
        synchronized (lock) {
            task = inFlight.get(key);
            if(task == null) {
                task = new FetchTask(String.valueOf(nextToken++), key,
                        webViewUserAgentForTask(userAgent, baseUrl, path), baseUrl, path, headers,
                        requestGroup, requestGroup != null && requestGroup.prioritizesWebViewFallback());
                inFlight.put(key, task);
                if(task.highPriority) {
                    queue.addFirst(task);
                    preemptActiveBackgroundTaskLocked();
                } else {
                    queue.add(task);
                }
                startNextLocked();
            } else {
                task.waiters++;
                reused = true;
            }
        }
        Log.d(TAG, "ntk_webview_fetch_wait path=" + path
                + ",reused=" + reused
                + ",priority=" + task.highPriority);
        try {
            if(!awaitTask(task, requestGroup)) {
                Log.d(TAG, "ntk_webview_fetch_wait_timeout path=" + path);
                return null;
            }
            if(task.code <= 0 || task.body == null || task.body.length() == 0) {
                Log.d(TAG, "ntk_webview_fetch_empty path=" + path
                        + ",code=" + task.code
                        + ",bodyLen=" + (task.body == null ? 0 : task.body.length()));
                return null;
            }
            return CustomHttpClient.responseFromWebViewFetch(baseUrl, path,
                    "{\"code\":" + task.code + ",\"body\":" + jsonQuote(task.body) + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseWaiterAndCancelIfUnused(task);
            return null;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        } finally {
            if(reused)
                ViewerWarmupManager.logMetric("ntk_webview_reused", 1);
        }
    }

    private boolean awaitTask(FetchTask task, CustomHttpClient.RequestGroup requestGroup) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + CALLER_WAIT_TIMEOUT_MS;
        while(true) {
            if(task.done.await(100L, TimeUnit.MILLISECONDS))
                return true;
            boolean cancelled = requestGroup != null && requestGroup.isCancelled();
            if(shouldStopWaitingForCaller(cancelled, SystemClock.elapsedRealtime(), deadline)) {
                releaseWaiterAndCancelIfUnused(task);
                return false;
            }
        }
    }

    static boolean shouldStopWaitingForCallerForTest(boolean requestCancelled, long now, long deadline) {
        return shouldStopWaitingForCaller(requestCancelled, now, deadline);
    }

    static long webViewLoadTimeoutMsForTest() {
        return WEBVIEW_LOAD_TIMEOUT_MS;
    }

    static long callerWaitTimeoutMsForTest() {
        return CALLER_WAIT_TIMEOUT_MS;
    }

    static long documentReadyWaitMsForTest() {
        return DOCUMENT_READY_WAIT_MS;
    }

    static long hiddenChallengeWaitMsForTest() {
        return HIDDEN_CHALLENGE_WAIT_MS;
    }

    static boolean isBlockedNtkDocumentBodyForTest(String body) {
        return isBlockedNtkDocumentBody(body);
    }

    static long documentReadyWaitMsForTest(boolean highPriority, boolean wolfDocument) {
        return documentReadyWaitMs(highPriority, wolfDocument);
    }

    void cancelAll() {
        Runnable cancel = () -> {
            ArrayList<FetchTask> tasks;
            synchronized (lock) {
                tasks = new ArrayList<>(inFlight.values());
                queue.clear();
                activeTask = null;
                clearProtectedViewerLocked();
                inFlight.clear();
            }
            for(FetchTask task : tasks) {
                if(task == null || task.completed)
                    continue;
                task.completed = true;
                task.code = 0;
                task.body = "";
                task.done.countDown();
            }
            destroyWebView();
        };
        if(Looper.myLooper() == Looper.getMainLooper()) {
            cancel.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                cancel.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    ArrayList<String> fetchViewerImageUrls(String userAgent, String baseUrl, String path,
                                           Map<String, String> headers, String kind,
                                           String workId, String episodeId, String imagesToken,
                                           String fallbackCookieHeader) {
        return fetchViewerImageUrls(userAgent, baseUrl, path, path, headers, kind,
                workId, episodeId, imagesToken, fallbackCookieHeader);
    }

    ArrayList<String> fetchViewerImageUrls(String userAgent, String baseUrl, String path,
                                           String ackScopePath, Map<String, String> headers, String kind,
                                           String workId, String episodeId, String imagesToken,
                                           String fallbackCookieHeader) {
        return fetchViewerImageUrls(userAgent, baseUrl, path, ackScopePath, headers, kind,
                workId, episodeId, imagesToken, fallbackCookieHeader, null);
    }

    ArrayList<String> fetchViewerImageUrls(String userAgent, String baseUrl, String path,
                                           String ackScopePath, Map<String, String> headers, String kind,
                                           String workId, String episodeId, String imagesToken,
                                           String fallbackCookieHeader, String shellHtml) {
        ArrayList<String> cached = new ArrayList<>();
        appendCachedViewerImageUrls(cached, kind, workId, episodeId, path);
        if(cached.size() > 0)
            return cached;
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null
                || kind == null || workId == null || episodeId == null || imagesToken == null)
            return cached;
        String flightKey = viewerImageFlightKey(baseUrl, path, kind, workId, episodeId, imagesToken);
        ViewerImageFlight flight;
        boolean owner = false;
        synchronized (VIEWER_IMAGE_FLIGHT_LOCK) {
            flight = VIEWER_IMAGE_FLIGHTS.get(flightKey);
            if(flight == null) {
                flight = new ViewerImageFlight(flightKey);
                VIEWER_IMAGE_FLIGHTS.put(flightKey, flight);
                owner = true;
            } else {
                flight.waiters++;
            }
        }
        if(!owner)
            return awaitViewerImageFlight(flight, kind, workId, episodeId, path);
        try {
            ArrayList<String> urls = fetchViewerImageUrlsUnshared(userAgent, baseUrl, path, ackScopePath,
                    headers, kind, workId, episodeId, imagesToken, fallbackCookieHeader, shellHtml);
            synchronized (flight) {
                flight.urls.clear();
                flight.urls.addAll(urls);
            }
            return urls;
        } finally {
            flight.done.countDown();
            synchronized (VIEWER_IMAGE_FLIGHT_LOCK) {
                if(VIEWER_IMAGE_FLIGHTS.get(flightKey) == flight)
                    VIEWER_IMAGE_FLIGHTS.remove(flightKey);
            }
        }
    }

    private ArrayList<String> fetchViewerImageUrlsUnshared(String userAgent, String baseUrl, String path,
                                                           String ackScopePath, Map<String, String> headers,
                                                           String kind, String workId, String episodeId,
                                                           String imagesToken, String fallbackCookieHeader,
                                                           String shellHtml) {
        ArrayList<String> urls = new ArrayList<>();
        appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
        if(urls.size() > 0)
            return urls;
        CountDownLatch done = new CountDownLatch(1);
        ViewerImageResult result = new ViewerImageResult();
        AtomicReference<Runnable> cancelRef = new AtomicReference<>();
        mainHandler.post(() -> fetchViewerImageUrlsOnMain(userAgent, baseUrl, path, headers, kind,
                workId, episodeId, imagesToken, ackScopePath, fallbackCookieHeader, shellHtml,
                result, done, cancelRef));
        try {
            long deadline = SystemClock.elapsedRealtime() + 65_000L;
            while(!done.await(120L, TimeUnit.MILLISECONDS)) {
                appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
                if(urls.size() > 0) {
                    cancelViewerImageFetch(cancelRef);
                    return urls;
                }
                if(SystemClock.elapsedRealtime() >= deadline) {
                    cancelViewerImageFetch(cancelRef);
                    return urls;
                }
            }
            appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
            if(urls.size() > 0) {
                cancelViewerImageFetch(cancelRef);
                return urls;
            }
            if(result.body == null || result.body.length() == 0)
                return urls;
            Log.d(TAG, "ntk_webview_viewer_images body="
                    + result.body.substring(0, Math.min(400, result.body.length())));
            JSONObject envelope = new JSONObject(result.body);
            JSONObject body = envelope.optJSONObject("body");
            if(body == null && envelope.optString("body", "").startsWith("{"))
                body = new JSONObject(envelope.optString("body"));
            if(body == null || !body.optBoolean("ok", false))
                return urls;
            JSONArray images = body.optJSONArray("images");
            if(images == null)
                return urls;
            for(int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String src = image == null ? "" : image.optString("src", "");
                if(src.length() > 0)
                    urls.add(src);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return urls;
    }

    private ArrayList<String> awaitViewerImageFlight(ViewerImageFlight flight, String kind, String workId,
                                                     String episodeId, String path) {
        ArrayList<String> urls = new ArrayList<>();
        Log.d(TAG, "ntk_webview_viewer_images_flight_join path=" + path
                + ",waiters=" + flight.waiters);
        try {
            long deadline = SystemClock.elapsedRealtime() + 65_000L;
            while(!flight.done.await(120L, TimeUnit.MILLISECONDS)) {
                appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
                if(urls.size() > 0)
                    return urls;
                if(SystemClock.elapsedRealtime() >= deadline)
                    return urls;
            }
            appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
            if(urls.size() > 0)
                return urls;
            synchronized (flight) {
                urls.addAll(flight.urls);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return urls;
    }

    ArrayList<String> cachedViewerImageUrls(String kind, String workId, String episodeId, String path) {
        ArrayList<String> urls = new ArrayList<>();
        appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
        return urls;
    }

    void dropCachedViewerImageUrls(String kind, String workId, String episodeId, String path) {
        String key = viewerImageCacheKey(kind, workId, episodeId, path);
        if(key.length() == 0)
            return;
        VIEWER_IMAGE_API_CACHE.remove(key);
        Log.d(TAG, "ntk_webview_viewer_images_cache_drop key=" + key);
    }

    void cacheViewerImageUrls(String kind, String workId, String episodeId, String path,
                              List<String> urls, String reason) {
        String key = viewerImageCacheKey(kind, workId, episodeId, path);
        if(key.length() == 0 || urls == null || urls.size() == 0)
            return;
        try {
            JSONObject body = new JSONObject();
            JSONArray images = new JSONArray();
            for(String url : urls) {
                if(url == null || url.length() == 0)
                    continue;
                JSONObject image = new JSONObject();
                image.put("src", url);
                images.put(image);
            }
            if(images.length() == 0)
                return;
            body.put("images", images);
            VIEWER_IMAGE_API_CACHE.put(key, new CachedViewerImages(body.toString(), System.currentTimeMillis()));
            Log.d(TAG, "ntk_webview_viewer_images_cache_store key=" + key
                    + ",count=" + images.length()
                    + ",reason=" + reason);
        } catch (Exception e) {
            Log.d(TAG, "ntk_webview_viewer_images_cache_store_error key=" + key + "," + e);
        }
    }

    void clearCachedViewerImageUrlsForTest() {
        VIEWER_IMAGE_API_CACHE.clear();
        synchronized (VIEWER_IMAGE_FLIGHTS) {
            VIEWER_IMAGE_FLIGHTS.clear();
        }
        Log.d(TAG, "ntk_webview_viewer_images_cache_clear_for_test");
    }

    private static void appendCachedViewerImageUrls(ArrayList<String> urls, String kind, String workId,
                                                    String episodeId, String path) {
        if(urls == null)
            return;
        String key = viewerImageCacheKey(kind, workId, episodeId, path);
        if(key.length() == 0)
            return;
        CachedViewerImages cached = VIEWER_IMAGE_API_CACHE.get(key);
        if(cached == null)
            return;
        if(System.currentTimeMillis() - cached.storedAtMs > VIEWER_IMAGE_CACHE_TTL_MS) {
            VIEWER_IMAGE_API_CACHE.remove(key);
            return;
        }
        try {
            JSONObject body = new JSONObject(cached.body);
            JSONArray images = body.optJSONArray("images");
            if(images == null)
                return;
            for(int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String src = image == null ? "" : image.optString("src", "");
                if(src.length() > 0)
                    urls.add(src);
            }
            if(urls.size() > 0)
                Log.d(TAG, "ntk_webview_viewer_images_cached key=" + key + ",count=" + urls.size());
        } catch (Exception ignored) {
        }
    }

    private void fetchViewerImageUrlsOnMain(String userAgent, String baseUrl, String path,
                                            Map<String, String> headers, String kind,
                                            String workId, String episodeId, String imagesToken,
                                            String ackScopePath, String fallbackCookieHeader, String shellHtml,
                                            ViewerImageResult result,
                                            CountDownLatch done, AtomicReference<Runnable> cancelRef) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            done.countDown();
            return;
        }
        final WebView view = new WebView(context);
        final String fallbackCookies = fallbackCookieHeader == null ? "" : fallbackCookieHeader;
        final NtkQuicBridge quicBridge = NtkQuicFetcher.isAvailable()
                ? new NtkQuicBridge(context, userAgent, fallbackCookies) : null;
        final boolean[] finished = {false};
        final boolean[] requested = {false};
        final int[] scriptRequests = {0};
        final String shellGuardVersion = ntkGuardVersionFromText(shellHtml);
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if(Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(this);
                    return;
                }
                if(finished[0])
                    return;
                finished[0] = true;
                try {
                    ViewGroup parent = (ViewGroup) view.getParent();
                    if(parent != null)
                        parent.removeView(view);
                } catch (Exception ignored) {
                }
                try {
                    view.destroy();
                } catch (Exception ignored) {
                }
                if(quicBridge != null)
                    quicBridge.close();
                done.countDown();
            }
        };
        cancelRef.set(finish);
        try {
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setUserAgentString(userAgent);
            CookieManager.getInstance().setAcceptCookie(true);
            applyWebViewCookieHeader(baseUrl, fallbackCookies);
            applyWebViewCookieHeader(baseUrl + path, fallbackCookies);
            view.addJavascriptInterface(new ViewerImageBridge(result, finish, mainHandler), "NtkViewerBridge");
            if(quicBridge != null)
                view.addJavascriptInterface(quicBridge, "NtkQuicBridge");
            String shellUrl = baseUrl + path;
            boolean modernGuardRoot = isModernNtkGuardRoot(baseUrl);
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    if(consoleMessage != null) {
                        Log.d(TAG, "ntk_images_api_hidden_console path=" + path
                                + ",level=" + consoleMessage.messageLevel()
                                + ",line=" + consoleMessage.lineNumber()
                                + ",source=" + consoleMessage.sourceId()
                                + ",message=" + consoleMessage.message());
                    }
                    return super.onConsoleMessage(consoleMessage);
                }
            });
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "ntk_images_api_hidden_finished url=" + url + ",path=" + path
                            + ",requested=" + requested[0]
                            + ",finished=" + finished[0]
                            + ",match=" + isFinishedDocumentUrl(url, baseUrl, path));
                    if(requested[0] || finished[0] || !isFinishedDocumentUrl(url, baseUrl, path))
                        return;
                    requested[0] = true;
                    mainHandler.post(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests, baseUrl, path,
                            ackScopePath, kind, workId, episodeId, imagesToken));
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if(request != null && request.isForMainFrame() && !finished[0])
                        finish.run();
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if(request != null && request.isForMainFrame()
                            && isFinishedDocumentUrl(request.getUrl() == null ? "" : request.getUrl().toString(),
                            baseUrl, path)) {
                        return viewerShellResponse(shellHtml, shellGuardVersion);
                    }
                    WebResourceResponse response = interceptViewerQuicRequest(userAgent, fallbackCookies, request);
                    return response == null ? super.shouldInterceptRequest(view, request) : response;
                }
            });
            Activity activity = MainApplication.currentActivity;
            if(activity != null && !activity.isFinishing() && activity.getWindow() != null) {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                view.setAlpha(0.01f);
                view.setClickable(false);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        Math.max(390, decor.getWidth()), Math.max(720, decor.getHeight()),
                        Gravity.TOP | Gravity.LEFT);
                decor.addView(view, 0, params);
            }
            Log.d(TAG, "ntk_images_api_hidden_document path=" + path
                    + ",modernGuard=" + modernGuardRoot
                    + ",realMainFrame=false"
                    + ",shellGuardVersion=" + shellGuardVersion);
            scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 120L);
            scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 700L);
            scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 1_800L);
            scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 3_800L);
            scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 7_000L);
            view.loadUrl(shellUrl, webViewHeaders(headers));
            mainHandler.postDelayed(finish, 64_000L);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            finish.run();
        }
    }

    private void cancelViewerImageFetch(AtomicReference<Runnable> cancelRef) {
        Runnable cancel = cancelRef == null ? null : cancelRef.getAndSet(null);
        if(cancel == null)
            return;
        mainHandler.post(cancel);
    }

    private void scheduleViewerImageFetch(WebView view, boolean[] finished, int[] scriptRequests,
                                          String baseUrl, String path,
                                          String ackScopePath,
                                          String kind, String workId, String episodeId,
                                          String imagesToken, long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || scriptRequests[0] >= 4 || view == null)
                return;
            String currentUrl = view.getUrl();
            boolean matched = isFinishedDocumentUrl(currentUrl, baseUrl, path);
            Log.d(TAG, "ntk_images_api_hidden_schedule delay=" + delayMs
                    + ",url=" + currentUrl
                    + ",path=" + path
                    + ",match=" + matched
                    + ",attempts=" + scriptRequests[0]);
            if(!matched)
                return;
            evaluateViewerImageFetchScript(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId,
                    episodeId, imagesToken);
        }, delayMs);
    }

    private static WebResourceResponse viewerShellResponse(String html, String guardVersion) {
        String bodyText = html == null || html.length() == 0
                ? "<!doctype html><html data-ntk-fast-shell=\"1\"><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body></body></html>"
                : html;
        if(guardVersion != null && guardVersion.length() > 0 && !bodyText.contains(guardVersion)) {
            String marker = "<head>";
            String meta = "<meta name=\"ntk-guard-version\" content=\"" + guardVersion + "\">";
            int index = bodyText.toLowerCase(Locale.US).indexOf(marker);
            bodyText = index >= 0
                    ? bodyText.substring(0, index + marker.length()) + meta + bodyText.substring(index + marker.length())
                    : meta + bodyText;
        }
        if(!bodyText.toLowerCase(Locale.US).contains("data-ntk-fast-shell")) {
            int htmlStart = bodyText.toLowerCase(Locale.US).indexOf("<html");
            if(htmlStart >= 0) {
                int htmlEnd = bodyText.indexOf('>', htmlStart);
                if(htmlEnd > htmlStart)
                    bodyText = bodyText.substring(0, htmlEnd) + " data-ntk-fast-shell=\"1\"" + bodyText.substring(htmlEnd);
            } else {
                bodyText = "<!doctype html><html data-ntk-fast-shell=\"1\"><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>"
                        + bodyText + "</body></html>";
            }
        }
        byte[] body = bodyText
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/html; charset=utf-8");
        return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(body));
    }

    private static WebResourceResponse viewerDocumentResponse(String html) {
        String bodyText = html == null || html.length() == 0
                ? "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body></body></html>"
                : html;
        byte[] body = bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/html; charset=utf-8");
        return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(body));
    }

    private static String ntkGuardVersionFromText(String text) {
        if(text == null || text.length() == 0)
            return "";
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("wv=([^\"'&<>\\\\\\s]+)")
                    .matcher(text);
            if(matcher.find()) {
                String value = URLDecoder.decode(matcher.group(1), "UTF-8");
                if(value.matches("b\\d{13}-wasm-\\d{13}"))
                    return value;
            }
            matcher = java.util.regex.Pattern
                    .compile("b\\d{13}[^\"'<>\\\\\\s]*wasm-\\d{13}")
                    .matcher(text);
            if(matcher.find()) {
                String value = matcher.group();
                return value.matches("b\\d{13}-wasm-\\d{13}") ? value : "";
            }
            matcher = java.util.regex.Pattern
                    .compile("/(b\\d{13})/_next/static/")
                    .matcher(text);
            if(matcher.find()) {
                long build = Long.parseLong(matcher.group(1).substring(1));
                return matcher.group(1) + "-wasm-" + (build + 4L);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void evaluateViewerImageFetchScript(WebView view, boolean[] finished, int[] scriptRequests,
                                                String baseUrl, String path,
                                                String ackScopePath,
                                                String kind, String workId, String episodeId,
                                                String imagesToken) {
        if(finished[0] || scriptRequests[0] >= 4 || view == null)
            return;
        scriptRequests[0]++;
        String script = buildViewerImageFetchScript(baseUrl, path, ackScopePath, kind, workId,
                episodeId, imagesToken);
        Log.d(TAG, "ntk_images_api_eval_script path=" + path
                + ",url=" + view.getUrl()
                + ",attempt=" + scriptRequests[0]
                + ",len=" + script.length()
                + ",finished=" + finished[0]);
        view.evaluateJavascript(script, value -> Log.d(TAG, "ntk_images_api_eval_result path=" + path
                + ",url=" + view.getUrl()
                + ",value=" + (value == null ? "" : value)));
    }

    static long webViewLoadTimeoutMsForTest(boolean highPriority, boolean wolfDocument) {
        return webViewLoadTimeoutMs(highPriority, wolfDocument);
    }

    private static boolean shouldStopWaitingForCaller(boolean requestCancelled, long now, long deadline) {
        return requestCancelled || now >= deadline;
    }

    private static boolean isModernNtkGuardRoot(String root) {
        root = root == null ? "" : root.toLowerCase(Locale.ROOT);
        return root.contains("sbxh") || root.contains("toonflix");
    }

    private void releaseWaiterAndCancelIfUnused(FetchTask task) {
        if(task == null || task.completed)
            return;
        boolean cancelActive = false;
        boolean completeQueued = false;
        synchronized (lock) {
            if(task.waiters > 0)
                task.waiters--;
            if(task.waiters > 0 || task.completed)
                return;
            if(activeTask == task) {
                cancelActive = true;
                inFlight.remove(task.key);
            } else {
                completeQueued = true;
                task.completed = true;
                inFlight.remove(task.key);
                queue.remove(task);
                startNextLocked();
            }
        }
        if(cancelActive)
            cancelIfStillActive(task);
        else if(completeQueued)
            task.done.countDown();
    }

    private void preemptActiveBackgroundTaskLocked() {
        FetchTask running = activeTask;
        if(running == null || running.completed || running.highPriority)
            return;
        mainHandler.post(() -> {
            if(running.completed)
                return;
            ViewerWarmupManager.logMetric("ntk_webview_preempted", 1);
            finishOnMain(running, 0, "", false);
        });
    }

    private void startNextLocked() {
        if(activeTask != null)
            return;
        FetchTask task;
        do {
            task = queue.poll();
            if(completeCancelledTaskLocked(task, "queue"))
                task = null;
        } while(task != null && task.completed);
        if(task == null)
            return;
        long now = SystemClock.elapsedRealtime();
        if(!task.highPriority && protectedViewerUntil > now) {
            queue.addFirst(task);
            scheduleProtectedViewerResumeLocked(protectedViewerUntil - now);
            return;
        }
        activeTask = task;
        final FetchTask nextTask = task;
        mainHandler.post(() -> beginOnMain(nextTask));
    }

    private void scheduleProtectedViewerResumeLocked(long delayMs) {
        long now = SystemClock.elapsedRealtime();
        long resumeAt = now + Math.max(1L, delayMs);
        if(protectedResumeAt > now && protectedResumeAt <= resumeAt)
            return;
        protectedResumeAt = resumeAt;
        mainHandler.postDelayed(() -> {
            synchronized (lock) {
                protectedResumeAt = 0L;
                if(activeTask == null)
                    startNextLocked();
            }
        }, Math.max(1L, delayMs));
    }

    private void beginOnMain(FetchTask task) {
        if(Looper.myLooper() != Looper.getMainLooper())
            return;
        if(completeCancelledTaskOnMain(task, "begin"))
            return;
        Log.d(TAG, "ntk_webview_begin path=" + (task == null ? "" : task.path));
        if(task.completed) {
            synchronized (lock) {
                if(activeTask == task)
                    activeTask = null;
                startNextLocked();
            }
            return;
        }
        try {
            ensureWebView(task.userAgent);
            attachSharedWebViewToActivity();
            task.startedOnMainAt = SystemClock.elapsedRealtime();
            task.requested = false;
            if(shouldNavigateDocument(task.path)) {
                boolean episodeDocument = isNtkEpisodeDocumentPath(task.path);
                if(episodeDocument && isModernNtkRoot(task.baseUrl)) {
                    webView.loadDataWithBaseURL(task.baseUrl,
                            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>",
                            "text/html", "UTF-8", null);
                    mainHandler.postDelayed(() -> runEpisodePreAckOnMain(task), 120L);
                    mainHandler.postDelayed(() -> startEpisodeNavigationOnMain(task, "preack-timeout"), 4200L);
                } else {
                    startEpisodeNavigationOnMain(task, "direct");
                }
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, !episodeDocument, false), 1500L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, !episodeDocument, episodeDocument), 4000L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, !episodeDocument, episodeDocument), 8000L);
                if(episodeDocument)
                    mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, false, true), 14000L);
            } else {
                webView.loadDataWithBaseURL(task.baseUrl, "<!doctype html><html><body></body></html>",
                        "text/html", "UTF-8", null);
            }
            mainHandler.postDelayed(() -> timeoutOnMain(task), webViewLoadTimeoutMs(task));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            finishOnMain(task, 0, "", true);
        }
    }

    private void runEpisodePreAckOnMain(FetchTask task) {
        if(completeCancelledTaskOnMain(task, "preack"))
            return;
        if(task == null || task.completed || webView == null || task.navigationStarted)
            return;
        if(Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "ntk_webview_preack_start path=" + task.path);
        webView.evaluateJavascript(buildEpisodePreAckScript(task.token, task.baseUrl, task.path), null);
    }

    private void startEpisodeNavigationOnMain(FetchTask task, String reason) {
        if(completeCancelledTaskOnMain(task, "navigate"))
            return;
        if(task == null || task.completed || webView == null || task.navigationStarted)
            return;
        task.navigationStarted = true;
        Log.d(TAG, "ntk_webview_navigate path=" + task.path + ",reason=" + reason);
        webView.loadUrl(task.baseUrl + task.path, webViewHeaders(task.headers));
    }

    private boolean retryEpisodeNavigationOnMain(FetchTask task, int errorCode, CharSequence description) {
        if(task == null || task.completed || webView == null || !isNtkEpisodeDocumentPath(task.path))
            return false;
        if(task.navigationErrorRetries >= 1)
            return false;
        task.navigationErrorRetries++;
        task.requested = false;
        Log.d(TAG, "ntk_webview_navigate_retry path=" + task.path
                + ",attempt=" + task.navigationErrorRetries
                + ",code=" + errorCode
                + ",description=" + description);
        mainHandler.postDelayed(() -> {
            if(task.completed || webView == null)
                return;
            webView.loadUrl(task.baseUrl + task.path, webViewHeaders(task.headers));
        }, 180L);
        return true;
    }

    private void ensureWebView(String userAgent) {
        if(webView != null) {
            webView.getSettings().setUserAgentString(userAgent);
            attachSharedWebViewToActivity();
            return;
        }
        Activity activity = MainApplication.currentActivity;
        webView = new WebView(activity == null ? context : activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(userAgent);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new Bridge(), "NtkBridge");
        if(NtkQuicFetcher.isAvailable())
            webView.addJavascriptInterface(new NtkQuicBridge(context, userAgent, ""), "NtkQuicBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d(TAG, "ntk_webview_page_started url=" + url);
                FetchTask task;
                synchronized (lock) {
                    task = activeTask;
                }
                if(task != null && shouldNavigateDocument(task.path) && isExternalDocumentRedirect(url, task.baseUrl, task.path)) {
                    if(Log.isLoggable(TAG, Log.DEBUG))
                        Log.d(TAG, "ntk_webview_external_redirect url=" + url);
                    finishOnMain(task, 403, "<html><body>cf-challenge external redirect " + url + "</body></html>", true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                FetchTask task;
                synchronized (lock) {
                    task = activeTask;
                }
                if(Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ntk_webview_page_finished url=" + url
                            + ",path=" + (task == null ? "" : task.path)
                            + ",requested=" + (task != null && task.requested));
                }
                if(task == null || task.completed || task.requested)
                    return;
                if(shouldNavigateDocument(task.path) && !isFinishedDocumentUrl(url, task.baseUrl, task.path))
                    return;
                if(shouldNavigateDocument(task.path) && !isNtkEpisodeDocumentPath(task.path))
                    requestDocumentHtmlOnMain(task, true, false);
                else if(shouldNavigateDocument(task.path) && isNtkEpisodeDocumentPath(task.path))
                    requestDocumentHtmlOnMain(task, false, true);
                else if(!shouldNavigateDocument(task.path))
                    view.evaluateJavascript(CustomHttpClient.buildNtkWebViewFetchScript(task.path, task.headers, task.token), null);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if(request != null && request.isForMainFrame()) {
                    Log.d(TAG, "ntk_webview_http_error url=" + request.getUrl()
                            + ",status=" + (errorResponse == null ? 0 : errorResponse.getStatusCode())
                            + ",reason=" + (errorResponse == null ? "" : errorResponse.getReasonPhrase()));
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = interceptViewerQuicRequest(userAgent, "", request);
                return response == null ? super.shouldInterceptRequest(view, request) : response;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                boolean mainFrame = request != null && request.isForMainFrame();
                if(mainFrame) {
                    CharSequence description = error == null ? "" : error.getDescription();
                    Log.d(TAG, "ntk_webview_error url=" + request.getUrl()
                            + ",code=" + (error == null ? 0 : error.getErrorCode())
                            + ",description=" + description);
                }
                if(!mainFrame)
                    return;
                FetchTask task;
                synchronized (lock) {
                    task = activeTask;
                }
                if(task != null && CustomHttpClient.isWolfEpisodeDocumentPath(task.path))
                    finishOnMain(task, 0, "", true);
                else if(task != null)
                    retryEpisodeNavigationOnMain(task, error == null ? 0 : error.getErrorCode(),
                            error == null ? "" : error.getDescription());
            }
        });
    }

    private void requestDocumentHtmlOnMain(FetchTask task, boolean stopLoading) {
        requestDocumentHtmlOnMain(task, stopLoading, false);
    }

    private void requestDocumentHtmlOnMain(FetchTask task, boolean stopLoading, boolean immediate) {
        if(completeCancelledTaskOnMain(task, "document"))
            return;
        if(task == null || task.completed || webView == null)
            return;
        boolean episodeDocument = isNtkEpisodeDocumentPath(task.path);
        if(task.requested && (!episodeDocument || !immediate))
            return;
        String currentUrl = webView.getUrl();
        if(!isFinishedDocumentUrl(currentUrl, task.baseUrl, task.path)) {
            if(Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "ntk_webview_document_skip_url path=" + task.path
                        + ",current=" + currentUrl
                        + ",immediate=" + immediate);
            }
            return;
        }
        if(stopLoading) {
            try {
                webView.stopLoading();
            } catch (Exception ignored) {
            }
        }
        if(!episodeDocument || !immediate)
            task.requested = true;
        if(task.loadStartedAt <= 0)
            task.loadStartedAt = SystemClock.elapsedRealtime();
        if(Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ntk_webview_document_request path=" + task.path
                    + ",immediate=" + immediate
                    + ",stop=" + stopLoading
                    + ",url=" + currentUrl);
        }
        webView.evaluateJavascript(immediate
                ? buildImmediateDocumentHtmlScript(task.token)
                : buildDocumentHtmlScript(task.token, documentReadyWaitMs(task)), null);
    }

    private void onBridgeResult(String token, String value) {
        mainHandler.post(() -> {
            FetchTask task;
            synchronized (lock) {
                task = activeTask;
            }
            if(completeCancelledTaskOnMain(task, "bridge"))
                return;
            if(task == null || task.completed || !task.token.equals(token))
                return;
            int code = 0;
            String body = "";
            try {
                Response response = CustomHttpClient.responseFromWebViewFetch(task.baseUrl, task.path, value);
                if(response != null) {
                    code = response.code();
                    body = CustomHttpClient.readBody(response);
                }
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            boolean blockedDocument = isBlockedNtkDocumentBody(body);
            boolean unusableEpisodeDocument = isUnusableNtkEpisodeDocumentResult(task.path, body);
            if(isNtkEpisodeDocumentPath(task.path) && isWebViewNetworkErrorDocumentBody(body)
                    && task.navigationErrorRetries > 0
                    && SystemClock.elapsedRealtime() - task.enqueuedAt
                    < Math.max(0L, webViewLoadTimeoutMs(task) - 800L)) {
                task.requested = false;
                if(Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ntk_webview_ignore_network_error_episode_probe path=" + task.path
                            + ",code=" + code
                            + ",retries=" + task.navigationErrorRetries
                            + ",htmlLen=" + (body == null ? 0 : body.length()));
                }
                return;
            }
            if(shouldNavigateDocument(task.path) && blockedDocument) {
                finishOnMain(task, code > 0 && code != 200 ? code : 403, body, true);
                return;
            }
            if(isNtkEpisodeDocumentPath(task.path) && (code <= 0 || unusableEpisodeDocument)
                    && SystemClock.elapsedRealtime() - task.enqueuedAt
                    < Math.max(0L, webViewLoadTimeoutMs(task) - 800L)) {
                task.requested = false;
                if(Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ntk_webview_ignore_empty_episode_probe path=" + task.path
                            + ",code=" + code
                            + ",htmlLen=" + (body == null ? 0 : body.length()));
                }
                return;
            }
            if(code > 0 && unusableEpisodeDocument) {
                if(Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ntk_webview_reject_empty_episode path=" + task.path
                            + ",code=" + code
                            + ",htmlLen=" + (body == null ? 0 : body.length()));
                }
                code = 0;
            }
            finishOnMain(task, code, body, shouldResetWebViewAfterFetch(task, code));
        });
    }

    private void attachSharedWebViewToActivity() {
        if(webView == null || webView.getParent() != null)
            return;
        Activity activity = MainApplication.currentActivity;
        if(activity == null || activity.isFinishing() || activity.getWindow() == null)
            return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            webView.setAlpha(0.01f);
            webView.setClickable(false);
            webView.setFocusable(false);
            webView.setFocusableInTouchMode(false);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.max(360, decor.getWidth()), Math.max(220, decor.getHeight() / 4),
                    Gravity.BOTTOM | Gravity.LEFT);
            decor.addView(webView, 0, params);
            Log.d(TAG, "ntk_webview_attached");
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private void timeoutOnMain(FetchTask task) {
        if(completeCancelledTaskOnMain(task, "timeout"))
            return;
        if(task == null || task.completed)
            return;
        finishOnMain(task, 0, "", true);
    }

    private void cancelIfStillActive(FetchTask task) {
        mainHandler.post(() -> {
            if(task != null && !task.completed)
                finishOnMain(task, 0, "", true);
        });
    }

    private void finishOnMain(FetchTask task, int code, String body, boolean resetWebView) {
        if(task == null || task.completed)
            return;
        if(isTaskCancelled(task)) {
            completeCancelledTaskOnMain(task, "finish");
            return;
        }
        boolean taskWasActive;
        synchronized (lock) {
            taskWasActive = activeTask == task;
        }
        task.completed = true;
        task.code = code;
        task.body = body == null ? "" : body;
        long finishedAt = SystemClock.elapsedRealtime();
        try {
            CustomHttpClient client = MainApplication.getHttpClient();
            if(client != null) {
                client.syncCookiesFromWebView(task.baseUrl, true);
                client.syncCookiesFromWebView(task.baseUrl + task.path, true);
                if(code > 0 && client.isCloudflareChallengeResponse(code, task.body))
                    client.markCloudflareChallenge(task.baseUrl + task.path);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        ViewerWarmupManager.logMetric("ntk_webview_queue_ms", Math.max(0L, task.startedOnMainAt - task.enqueuedAt));
        ViewerWarmupManager.logMetric("ntk_webview_fallback_ms", Math.max(0L, finishedAt - task.enqueuedAt));
        ViewerWarmupManager.logMetric("ntk_webview_load_ms", task.loadStartedAt <= 0 ? 0L : Math.max(0L, finishedAt - task.loadStartedAt));
        ViewerWarmupManager.logMetric("ntk_webview_html_len", task.body.length());
        ViewerWarmupManager.logMetric("ntk_webview_result_code", code);
        if(Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ntk_webview_fallback path=" + task.path
                    + ",reused=" + (task.waiters > 1)
                    + ",priority=" + task.highPriority
                    + ",result=" + code
                    + ",htmlLen=" + task.body.length()
                    + ",queueMs=" + Math.max(0L, task.startedOnMainAt - task.enqueuedAt)
                    + ",loadMs=" + (task.loadStartedAt <= 0 ? 0L : Math.max(0L, finishedAt - task.loadStartedAt)));
        }
        if(resetWebView && taskWasActive)
            destroyWebView();
        synchronized (lock) {
            inFlight.remove(task.key);
            queue.remove(task);
            if(taskWasActive && activeTask == task)
                activeTask = null;
            if(taskWasActive && shouldProtectPriorityNtkViewer(task, code, task.body))
                protectPriorityViewerLocked(task.path, finishedAt);
            startNextLocked();
        }
        task.done.countDown();
    }

    private boolean completeCancelledTaskOnMain(FetchTask task, String stage) {
        if(Looper.myLooper() != Looper.getMainLooper())
            return false;
        synchronized (lock) {
            return completeCancelledTaskLocked(task, stage);
        }
    }

    private boolean completeCancelledTaskLocked(FetchTask task, String stage) {
        if(task == null || task.completed || !isTaskCancelled(task))
            return false;
        boolean taskWasActive = activeTask == task;
        task.completed = true;
        task.code = 0;
        task.body = "";
        inFlight.remove(task.key);
        queue.remove(task);
        if(taskWasActive)
            activeTask = null;
        Log.d(TAG, "ntk_webview_skip_cancelled path=" + task.path + ",stage=" + stage);
        if(taskWasActive)
            destroyWebView();
        task.done.countDown();
        if(taskWasActive)
            startNextLocked();
        return true;
    }

    private static boolean isTaskCancelled(FetchTask task) {
        return task != null && task.requestGroup != null && task.requestGroup.isCancelled();
    }

    private void protectPriorityViewerLocked(String path, long now) {
        protectedViewerPath = path == null ? "" : path;
        protectedViewerUntil = now + PRIORITY_NTK_VIEWER_IMAGE_GRACE_MS;
        protectedResumeAt = 0L;
        Log.d(TAG, "ntk_webview_protect path=" + protectedViewerPath
                + ",ms=" + PRIORITY_NTK_VIEWER_IMAGE_GRACE_MS);
    }

    private void clearProtectedViewerLocked() {
        protectedViewerPath = "";
        protectedViewerUntil = 0L;
        protectedResumeAt = 0L;
    }

    private static boolean shouldProtectPriorityNtkViewer(FetchTask task, int code, String body) {
        return task != null
                && task.highPriority
                && code >= 200
                && code < 400
                && isNtkEpisodeDocumentPath(task.path)
                && looksLikeNtkViewerPayload(body);
    }

    static boolean shouldResetWebViewAfterFetchForTest(String path, int code) {
        return shouldResetWebViewAfterFetch(path, code);
    }

    private static boolean shouldResetWebViewAfterFetch(FetchTask task, int code) {
        return shouldResetWebViewAfterFetch(task == null ? null : task.path, code);
    }

    private static boolean shouldResetWebViewAfterFetch(String path, int code) {
        if(code <= 0)
            return true;
        return shouldNavigateDocument(path);
    }

    private void destroyWebView() {
        if(webView == null)
            return;
        try {
            if(webView.getParent() instanceof ViewGroup)
                ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
        }
        webView = null;
    }

    static String fetchKeyForTest(String baseUrl, String path) {
        return fetchKey(baseUrl, path);
    }

    static boolean shouldNavigateDocumentForTest(String path) {
        return shouldNavigateDocument(path);
    }

    static boolean isFinishedDocumentUrlForTest(String url, String baseUrl, String path) {
        return isFinishedDocumentUrl(url, baseUrl, path);
    }

    static String ntkQuicBridgeJavascriptForTest() {
        return NTK_QUIC_BRIDGE_JS;
    }

    private static String fetchKey(String baseUrl, String path) {
        return (baseUrl == null ? "" : baseUrl) + (path == null ? "" : path);
    }

    private static boolean shouldNavigateDocument(String path) {
        return path != null && (path.startsWith("/webtoon/")
                || path.startsWith("/manhwa/")
                || isNtkCategoryDocumentPath(path)
                || CustomHttpClient.isWolfEpisodeDocumentPath(path));
    }

    private static boolean isNtkCategoryDocumentPath(String path) {
        if(path == null)
            return false;
        String normalized = path.trim();
        return normalized.equals("/ing")
                || normalized.startsWith("/ing?")
                || normalized.equals("/end")
                || normalized.startsWith("/end?")
                || normalized.equals("/manhwa")
                || normalized.startsWith("/manhwa?")
                || normalized.equals("/manhwa-end")
                || normalized.startsWith("/manhwa-end?");
    }

    private static boolean isNtkEpisodeDocumentPath(String path) {
        return path != null && path.matches("^/(manhwa|webtoon)/[^/?#%]+/[^/?#%]+/?(?:[?#].*)?$");
    }

    private static boolean isFinishedDocumentUrl(String url, String baseUrl, String path) {
        if(url == null || baseUrl == null || path == null)
            return false;
        String expected = baseUrl + path;
        return url.equals(expected)
                || url.startsWith(expected + "?")
                || url.startsWith(expected + "#")
                || url.equals(expected + "/")
                || url.startsWith(expected + "/?")
                || url.startsWith(expected + "/#");
    }

    private static boolean isExternalDocumentRedirect(String url, String baseUrl, String path) {
        if(url == null || baseUrl == null || path == null)
            return false;
        if(isFinishedDocumentUrl(url, baseUrl, path))
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if(lower.startsWith("about:") || lower.startsWith("data:"))
            return false;
        return lower.contains("t.me/") || !lower.startsWith(baseUrl.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> webViewHeaders(Map<String, String> headers) {
        Map<String, String> result = new HashMap<>();
        if(headers == null)
            return result;
        putHeaderIfPresent(result, headers, "Accept-Language");
        putHeaderIfPresent(result, headers, "Referer");
        return result;
    }

    private static void putHeaderIfPresent(Map<String, String> target, Map<String, String> source, String key) {
        String value = source.get(key);
        if(value != null && value.length() > 0)
            target.put(key, value);
    }

    private static long documentReadyWaitMs(FetchTask task) {
        return task == null ? DOCUMENT_READY_WAIT_MS :
                documentReadyWaitMs(task.highPriority, CustomHttpClient.isWolfEpisodeDocumentPath(task.path));
    }

    private static long documentReadyWaitMs(boolean highPriority, boolean wolfDocument) {
        return highPriority && wolfDocument ? PRIORITY_WOLF_DOCUMENT_READY_WAIT_MS : DOCUMENT_READY_WAIT_MS;
    }

    private static long webViewLoadTimeoutMs(FetchTask task) {
        if(task == null)
            return WEBVIEW_LOAD_TIMEOUT_MS;
        if(isNtkTitleDocumentPath(task.path) || isNtkSearchDocumentPath(task.path))
            return 8_000L;
        if(task.highPriority && isNtkEpisodeDocumentPath(task.path))
            return 32_000L;
        return webViewLoadTimeoutMs(task.highPriority, CustomHttpClient.isWolfEpisodeDocumentPath(task.path));
    }

    private static long webViewLoadTimeoutMs(boolean highPriority, boolean wolfDocument) {
        return highPriority && wolfDocument ? PRIORITY_WOLF_LOAD_TIMEOUT_MS : WEBVIEW_LOAD_TIMEOUT_MS;
    }

    private static boolean isModernNtkRoot(String root) {
        root = root == null ? "" : root.toLowerCase(Locale.ROOT);
        return root.contains("sbxh") || root.contains("toonflix");
    }

    private static String webViewUserAgentForTask(String userAgent, String baseUrl, String path) {
        return userAgent;
    }

    private static boolean isNtkTitleDocumentPath(String path) {
        return path != null && path.matches("^/(manhwa|webtoon)/\\d+/?(?:[?#].*)?$");
    }

    private static boolean isNtkSearchDocumentPath(String path) {
        return path != null && path.startsWith("/search");
    }

    private static String buildDocumentHtmlScript(String token, long readyWaitMsValue) {
        String quotedToken = jsonQuote(token);
        String readyWaitMs = String.valueOf(Math.max(0L, readyWaitMsValue));
        String challengeWaitMs = String.valueOf(Math.max(0L,
                Math.min(readyWaitMsValue, HIDDEN_CHALLENGE_WAIT_MS)));
        return "(function(){var token=" + quotedToken + ";var started=Date.now();"
                + "var challengeWaitMs=" + challengeWaitMs + ";"
                + "function html(){return document.documentElement?document.documentElement.outerHTML:(document.body?document.body.innerHTML:'');}"
                + "function lower(v){return (v||'').toLowerCase();}"
                + "function emptyDoc(v){var b=document.body;return !v||v.length<160||(!document.querySelector('a[href],img,script[src],link[href]')&&(!b||!(b.innerText||'').trim()));}"
                + "function webviewError(v){v=lower(v);return v.indexOf('webpage not available')>=0||v.indexOf('net::err_')>=0||v.indexOf('err_connection_reset')>=0||v.indexOf('err_name_not_resolved')>=0||v.indexOf('err_timed_out')>=0||v.indexOf('error code 522')>=0||v.indexOf('connection timed out')>=0;}"
                + "function challenge(v){v=lower(v);return v.indexOf('just a moment')>=0||v.indexOf('challenges.cloudflare.com')>=0||v.indexOf('/cdn-cgi/challenge-platform')>=0||v.indexOf('cf-challenge')>=0||v.indexOf('cf_chl')>=0||v.indexOf('cf-chl')>=0||v.indexOf('_cf_chl')>=0||v.indexOf('cf-mitigated')>=0||v.indexOf('cf-turnstile')>=0||v.indexOf('cf_clearance')>=0||v.indexOf('cf-ray')>=0||v.indexOf('turnstile')>=0||v.indexOf('verifying you are human')>=0||v.indexOf('verify you are human')>=0||(v.indexOf('cloudflare')>=0&&v.indexOf('security service')>=0)||v.indexOf('developer tools blocked')>=0||v.indexOf('developer tool blocked')>=0||v.indexOf('devtools blocked')>=0||v.indexOf('devtool blocked')>=0;}"
                + "function ntkViewerProps(v){v=lower(v);return v.indexOf('\"imagestoken\"')>=0&&v.indexOf('\"imagemetas\"')>=0;}"
                + "function ntkRendered(){try{var episode=/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(location.pathname||'');var ns=document.querySelectorAll('img[src],img[data-src],img[data-original],link[rel=preload][as=image][href]');for(var i=0;i<ns.length;i++){var s=(ns[i].getAttribute('src')||ns[i].getAttribute('data-src')||ns[i].getAttribute('data-original')||ns[i].getAttribute('href')||'').toLowerCase();if(s.indexOf('/webtoon_uploads/')>=0||s.indexOf('/manhwa_uploads/')>=0||s.indexOf('/comic_uploads/')>=0||s.indexOf('/blacktoon/episodes/')>=0)return true;}if(episode)return false;if(document.querySelector('.vw-main,.vw-imgs,.viewer-content,.toon-view,div.image-view,section.webtoon-body'))return true;var as=document.querySelectorAll('a[href]'),episodeLinks=0;for(var j=0;j<as.length;j++){var h=(as[j].getAttribute('href')||'').toLowerCase();if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(h)&&h.indexOf('%5b')<0&&h.indexOf('page-')<0)episodeLinks++;}return episodeLinks>0&&Date.now()-started>1200;}catch(e){return false;}}"
                + "function ntkShell(v){v=lower(v);return (v.indexOf('/_next/static/')>=0||v.indexOf('self.__next_f')>=0||v.indexOf('id=\"__next\"')>=0||v.indexOf(\"id='__next'\")>=0)&&(v.indexOf('%5bsourceworkid%5d')>=0||v.indexOf('[sourceworkid]')>=0||v.indexOf('%5bviewid%5d')>=0||v.indexOf('[viewid]')>=0||v.indexOf('next-route-announcer')>=0||v.indexOf('app-router-announcer')>=0)&&!ntkRendered();}"
                + "function ntkErrorFallback(v){v=lower(v);return /^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(location.pathname||'')&&(v.indexOf('next_http_error_fallback')>=0||v.indexOf('id=\"__next_error__')>=0||v.indexOf(\"id='__next_error__\")>=0)&&(v.indexOf('/_next/static/')>=0||v.indexOf('self.__next_f')>=0||v.indexOf('id=\"__next\"')>=0||v.indexOf(\"id='__next'\")>=0);}"
                + "function send(code,body){window.NtkBridge.onFetchResult(token,JSON.stringify({code:code,body:body||''}));}"
                + "function check(){try{var v=html();"
                + "if((emptyDoc(v)||webviewError(v))&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,250);return;}"
                + "if(challenge(v)&&Date.now()-started<challengeWaitMs){setTimeout(check,350);return;}"
                + "if(ntkViewerProps(v)){send(200,v);return;}"
                + "if(ntkErrorFallback(v)&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,300);return;}"
                + "if(ntkShell(v)&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,300);return;}"
                + "if(emptyDoc(v)||webviewError(v)){send(0,v||'');return;}"
                + "if(challenge(v)){send(403,v);return;}"
                + "if(ntkShell(v)){send(0,v);return;}"
                + "send(200,v);"
                + "}catch(e){send(0,String(e));}}"
                + "check();})()";
    }

    private static String buildImmediateDocumentHtmlScript(String token) {
        return "(function(){var token=" + jsonQuote(token) + ";"
                + "function send(code,body){window.NtkBridge.onFetchResult(token,JSON.stringify({code:code,body:body||''}));}"
                + "try{var d=document.documentElement;var b=document.body;var v=d?d.outerHTML:(b?b.innerHTML:'');send(v&&v.length>0?200:0,v||'');}"
                + "catch(e){send(0,String(e));}})()";
    }

    private static String buildEpisodePreAckScript(String token, String baseUrl, String path) {
        return NTK_QUIC_BRIDGE_JS + "(async function(){var token=" + jsonQuote(token) + ",base=" + jsonQuote(baseUrl)
                + ",scope=" + jsonQuote(path) + ";"
                + "function done(ok,reason){try{window.NtkBridge.onPreAckDone(token,!!ok,String(reason||''));}catch(e){}}"
                + "function abs(u){return new URL(u,base).href;}"
                + "function pageUrl(){return abs(scope);}"
                + "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function acked(){try{if(window.__ntk_ad_ack_scope===scope)return true;var l=window.__ntk_ad_ack_last;if(l&&l.scope===scope)return true;}catch(e){}return false;}"
                + "function markAck(){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),preack:true};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,preack:true}}));}catch(e){}}"
                + "async function req(url,body){var r=await fetch(abs(url),{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'content-type':'application/json','accept':'application/json','origin':base,'referer':pageUrl()},body:JSON.stringify(body||{})});var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(e){}return{status:r.status,body:j,text:t};}"
                + "function fireImp(u){try{if(typeof bridgeReq==='function')return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).catch(function(){});return fetch(abs(u),{credentials:'same-origin',cache:'no-store',mode:'no-cors'}).catch(function(){});}catch(e){return Promise.resolve();}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var urls=ch.impressionUrls||[],slot=Math.max(2,Number(ch.slotCount||urls.length||4)),root=document.getElementById('__ntk_guard_rows'),host=document.body||document.documentElement;if(!root){root=document.createElement('section');root.id='__ntk_guard_rows';root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));host.insertBefore(root,host.firstChild||null);}if(root.getAttribute('data-ntk-token')!==String(ch.token||'')){root.textContent='';root.setAttribute('data-ntk-token',String(ch.token||''));root.setAttribute('data-br-n',String(slot));for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.className='';b.setAttribute(i===0?'data-bs':'data-bp','1');var img=document.createElement('img');img.width=64;img.height=34;img.alt='';img.loading='eager';img.decoding='sync';img.src=urls[i]?abs(urls[i]):'data:image/gif;base64,R0lGODlhCgAGAPAAAP///wAAACH5BAAAAAAALAAAAAAKAAYAAAIIhI+py+0PYysAOw==';b.appendChild(img);root.appendChild(b);}}var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);var seen=Number(window.__bSeen||window[ab]||window[rb]||0);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,slot:slot,ab:ab,rb:rb,seen:seen,siteShape:true,unstyled:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e)}));}catch(_){}}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;var mod=await import(abs('/api/ad/guard-js'));if(mod&&mod.default)await mod.default({module_or_path:abs('/api/ad/guard-wasm')});window.__ntkGuardModule=mod;return mod;}catch(e){return null;}}"
                + "function waitAck(ms){return new Promise(function(resolve){if(acked())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.scope===scope)||acked())finish(true);}var to=setTimeout(function(){finish(acked());},ms);var iv=setInterval(function(){if(acked())finish(true);},120);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function guardAck(ch){try{var mod=await loadGuard();if(!mod||!ch)return false;var fn=mod.__i4||mod.i4||mod._i4||mod.guardAck||mod.adAck;if(!fn)return false;var tries=[ch,JSON.stringify(ch),ch.token||''];for(var i=0;i<tries.length;i++){try{var r=fn(tries[i],scope);if(r&&r.then)await r;if(await waitAck(1400))return true;}catch(e){}}return acked();}catch(e){return false;}}"
                + "try{var c=await req('/api/ad/challenge',{path:scope});if(c&&c.status===200&&c.body&&c.body.ok){if(c.body.trusted&&!c.body.challenge){markAck();done(true,'trusted');return;}var ch=c.body.challenge;if(ch){(ch.impressionUrls||[]).forEach(fireImp);if(await guardAck(ch)){done(true,'guard');return;}}}}catch(e){done(false,String(e));return;}done(acked(),acked()?'event':'miss');})()";
    }

    private static boolean isUnusableNtkEpisodeDocumentResult(String path, String body) {
        if(!isNtkEpisodeDocumentPath(path))
            return false;
        if(body == null)
            return true;
        String trimmed = body.trim();
        if(trimmed.length() < 160)
            return true;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if(lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\""))
            return false;
        if(lower.contains("/webtoon_uploads/") || lower.contains("/manhwa_uploads/")
                || lower.contains("/comic_uploads/") || lower.contains("/blacktoon/episodes/"))
            return false;
        if(lower.contains("vw-main") || lower.contains("vw-imgs")
                || lower.contains("viewer-content") || lower.contains("toon-view")
                || lower.contains("image-view") || lower.contains("webtoon-body"))
            return false;
        return lower.contains("<html") && lower.contains("<body") && !lower.contains("<img");
    }

    private static boolean isBlockedNtkDocumentBody(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("just a moment")
                || lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_timed_out")
                || (lower.contains("403 forbidden") && lower.contains("nginx"))
                || lower.contains("<title>403 forbidden</title>")
                || lower.contains("<h1>403 forbidden</h1>")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("cf-challenge")
                || lower.contains("cf_chl")
                || lower.contains("cf-chl")
                || lower.contains("_cf_chl")
                || lower.contains("cf-mitigated")
                || lower.contains("cf-turnstile")
                || lower.contains("cf_clearance")
                || lower.contains("cf-ray")
                || lower.contains("turnstile")
                || lower.contains("verifying you are human")
                || lower.contains("verify you are human")
                || (lower.contains("cloudflare") && lower.contains("security service"))
                || lower.contains("개발자 도구 차단")
                || lower.contains("developer tools blocked")
                || lower.contains("developer tool blocked")
                || lower.contains("devtools blocked")
                || lower.contains("devtool blocked");
    }

    private static boolean isWebViewNetworkErrorDocumentBody(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_timed_out");
    }

    private static boolean looksLikeNtkViewerPayload(String body) {
        if(body == null)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("\"imagestoken\"") && lower.contains("\"imagemetas\"");
    }

    private static String buildViewerImageFetchScript(String baseUrl, String path, String kind, String workId,
                                                      String episodeId, String imagesToken) {
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        return NTK_QUIC_BRIDGE_JS + "(function(){var base=" + jsonQuote(baseUrl) + ",scope=" + jsonQuote(path) + ",kind=" + jsonQuote(kind)
                + ",workId=" + jsonQuote(workId) + ",episodeId=" + jsonQuote(episodeId)
                + ",token=" + jsonQuote(imagesToken) + ",endpoint=" + jsonQuote(endpoint) + ";"
                + "var sent=false;"
                + "if(window.__ntkViewerImageFetchLock===scope)return;window.__ntkViewerImageFetchLock=scope;"
                + "function send(o){if(sent)return;sent=true;try{if(window.__ntkViewerImageFetchLock===scope)delete window.__ntkViewerImageFetchLock;}catch(e){}try{window.NtkViewerBridge.onViewerImages(JSON.stringify(o));}catch(e){}}"
                + "function abs(url){return new URL(url,base).href;}"
                + "function pageUrl(){return abs(scope);}"
                + "function baseOrigin(){return new URL(base).origin;}"
                + "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function b64(bytes){var s='';for(var i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}"
                + "function docCookie(){try{return document.cookie||'';}catch(e){return '';}}"
                + "function bridgeCookie(name){try{return window.NtkQuicBridge?String(window.NtkQuicBridge.cookie(pageUrl(),name)||''):'';}catch(e){return '';}}"
                + "function cookie(name){var text=docCookie(),m=text.match(new RegExp('(?:^|;\\\\s*)'+name+'=([^;]*)'));if(m)return decodeURIComponent(m[1]);return bridgeCookie(name);}"
                + "function b64json(v){try{v=String(v||'').replace(/-/g,'+').replace(/_/g,'/');while(v.length%4)v+='=';return JSON.parse(atob(v));}catch(e){return null;}}"
                + "function ackCookieMatches(v){try{if(!v)return false;var p=String(v).split('.'),j=b64json(p.length>2?p[1]:p[0]);return !!j&&j.scope===scope;}catch(e){return false;}}"
                + "async function hmac(key,msg){var enc=new TextEncoder();if(window.crypto&&crypto.subtle&&crypto.subtle.importKey){try{var k=await crypto.subtle.importKey('raw',enc.encode(key),{name:'HMAC',hash:'SHA-256'},false,['sign']);return b64(new Uint8Array(await crypto.subtle.sign('HMAC',k,enc.encode(msg))));}catch(e){}}if(window.NtkQuicBridge){var v=String(window.NtkQuicBridge.hmacSha256(key,msg)||'');if(v)return v;}throw new Error('hmac unavailable');}"
                + "async function nv(){var v=cookie('nv');if(!v||(v.split('.')[0]||'').length<40){try{await bridgeReq('/api/nv-issue','POST',null);}catch(e){}v=cookie('nv');}if(!v||(v.split('.')[0]||'').length<40){await fetch(abs('/api/nv-issue'),{method:'POST',credentials:'same-origin',cache:'no-store'}).catch(function(){});v=cookie('nv');}return (!v||(v.split('.')[0]||'').length<40)?'':v;}"
                + "function acked(){try{if(window.__ntk_ad_ack_scope===scope)return true;var l=window.__ntk_ad_ack_last;if(l&&l.scope===scope)return true;if(ackCookieMatches(cookie('ad_ack'))){markAck();return true;}}catch(e){}return false;}"
                + "function rearm(){try{window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:kind+'-native-403',scope:scope}}));}catch(e){}}"
                + "function waitAck(ms){return new Promise(function(resolve){if(acked())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.scope===scope)||acked())finish(true);}var to=setTimeout(function(){finish(acked());},ms);var iv=setInterval(function(){if(acked())finish(true);},120);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function api(){var v=await nv();if(!v)return{status:401,body:{error:'missing session'}};var n=new Uint8Array(24);crypto.getRandomValues(n);var nonce=b64(n);var proof=await hmac(v,token+'.'+nonce+'.'+navigator.userAgent),body={workId:workId,episodeId:episodeId,token:token,nonce:nonce,proof:proof};return await bridgeReq(endpoint,'POST',body,{'x-images-client':'viewer-v1'});}"
                + "function decode64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
                + "function bytesFrom64(b){try{var s=atob(b||''),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&255;return a;}catch(e){return new Uint8Array(0);}}"
                + "function markAck(){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true}}));}catch(e){}}"
                + "async function bridgeReq(url,method,body,extra){var absolute=abs(url),h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()};if(extra){Object.keys(extra).forEach(function(k){h[k]=extra[k];});}var adPath=false,imageApiPath=false;try{var p=(new URL(absolute)).pathname;adPath=p.indexOf('/api/ad/')===0;imageApiPath=p==='/api/webtoon-images'||p==='/api/manhwa-images';}catch(_){}if(window.NtkQuicBridge){var raw=window.NtkQuicBridge.request(absolute,method,JSON.stringify(h),body?b64(new TextEncoder().encode(JSON.stringify(body))):'');var o=JSON.parse(raw||'{}');if(!o.ok)throw new Error(o.error||'bridge failed');var text=decode64(o.bodyBase64||''),json={};try{json=JSON.parse(text||'{}');}catch(e){}return{status:o.status||0,body:json,text:text};}var opt={method:method,credentials:'same-origin',cache:'no-store',headers:h};if(body)opt.body=JSON.stringify(body);var r=await fetch(absolute,opt);var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(e){}return{status:r.status,body:j,text:t};}"
                + "function fireImp(u){try{if(typeof bridgeReq==='function')return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).catch(function(){});return fetch(abs(u),{credentials:'same-origin',cache:'no-store',mode:'no-cors'}).catch(function(){});}catch(e){return Promise.resolve();}}"
                + "function body64Async(b){try{if(b==null)return Promise.resolve('');if(window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof b==='string')return Promise.resolve(b64(new TextEncoder().encode(b)));if(window.ArrayBuffer&&b instanceof ArrayBuffer)return Promise.resolve(b64(new Uint8Array(b)));if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b))return Promise.resolve(b64(new Uint8Array(b.buffer,b.byteOffset,b.byteLength)));if(window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(b64(new TextEncoder().encode(String(b))));}catch(e){return Promise.resolve('');}}"
                + "function installGuardBeaconBridge(){try{if(!window.NtkQuicBridge||navigator.__ntkGuardBeaconBridge)return;var nativeBeacon=window.__ntkNativeBeacon||navigator.sendBeacon,nativeFetch=window.__ntkNativeFetch||window.fetch;function shouldBridgePath(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function ackFrom(p,o,j){try{if(p==='/api/ad/ack'&&(o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))markAck();}catch(_){}}function bridgePost(p,u,body,tag){try{var requestText='';try{requestText=decode64(body||'').slice(0,420);}catch(_){}var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),body);var o=JSON.parse(raw||'{}'),text=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(text||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,len:body.length,request:requestText,body:j}));}catch(_){}ackFrom(p,o,j);return{raw:o,text:text,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e)}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=1;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeBeacon?nativeBeacon.apply(this,arguments):false;body64Async(data).then(function(body){bridgePost(p,u,body,'beacon');});return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeOuterError:String(e)}));}catch(_){}return nativeBeacon?nativeBeacon.apply(this,arguments):false;}};if(nativeFetch){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeFetch.apply(this,arguments);var method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(method!=='POST')return nativeFetch.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){var r=bridgePost(p,u,body,'fetch');var bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardFetchBridgeOuterError:String(e)}));}catch(_){}return nativeFetch.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,mEv:true,fetch:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e)}));}catch(_){}}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var urls=ch.impressionUrls||[],slot=Math.max(2,Number(ch.slotCount||urls.length||4)),root=document.getElementById('__ntk_guard_rows'),host=document.body||document.documentElement;if(!root){root=document.createElement('section');root.id='__ntk_guard_rows';root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));host.insertBefore(root,host.firstChild||null);}if(root.getAttribute('data-ntk-token')!==String(ch.token||'')){root.textContent='';root.setAttribute('data-ntk-token',String(ch.token||''));root.setAttribute('data-br-n',String(slot));for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.className='';b.setAttribute(i===0?'data-bs':'data-bp','1');var img=document.createElement('img');img.width=64;img.height=34;img.alt='';img.loading='eager';img.decoding='sync';img.src=urls[i]?abs(urls[i]):'data:image/gif;base64,R0lGODlhCgAGAPAAAP///wAAACH5BAAAAAAALAAAAAAKAAYAAAIIhI+py+0PYysAOw==';b.appendChild(img);root.appendChild(b);}}var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);var seen=Number(window.__bSeen||window[ab]||window[rb]||0);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,slot:slot,ab:ab,rb:rb,seen:seen,siteShape:true,unstyled:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e)}));}catch(_){}}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var host=document.body||document.documentElement,total=26,token=String(ch.token||''),urls=ch.impressionUrls||[],old=document.querySelectorAll('[data-ntk-synth-br=\"1\"]');for(var oi=0;oi<old.length;oi++){if(old[oi].getAttribute('data-ntk-token')!==token){try{old[oi].parentNode&&old[oi].parentNode.removeChild(old[oi]);}catch(_){}}}function ad(btn,i){btn.setAttribute('data-bs','1');var img=document.createElement('img');img.width=88;img.height=44;img.alt='';img.loading='eager';img.decoding='sync';img.style.cssText='display:block;width:88px;height:44px;object-fit:cover;opacity:1;visibility:visible';img.src=urls.length?abs(urls[i%urls.length]):'data:image/gif;base64,R0lGODlhWAA sAPAAAP///wAAACH5BAAAAAAALAAAAABYACwAAAIKjI+py+0Po5yUFQA7'.replace(' ','');btn.appendChild(img);}function make(slot,count,id,offset){var root=id?document.getElementById(id):null;if(!root||root.getAttribute('data-ntk-token')!==token){root=document.createElement('section');if(id)root.id=id;root.className='';root.style.cssText='display:grid;grid-template-columns:repeat(4,88px);gap:8px;position:relative;z-index:1;opacity:1;visibility:visible;pointer-events:auto';root.setAttribute('data-ntk-synth-br','1');root.setAttribute('data-ntk-token',token);root.setAttribute('data-br','1');root.setAttribute('data-brs',slot);root.setAttribute('data-br-n',String(count));for(var i=0;i<count;i++){var b=document.createElement('button');b.type='button';b.className='';b.style.cssText='display:block;width:88px;height:44px;padding:0;margin:0;border:0;background:transparent;opacity:1;visibility:visible;pointer-events:auto';b.setAttribute('aria-label','newtoki62');ad(b,offset+i);root.appendChild(b);}host.insertBefore(root,host.firstChild||null);}return root;}make('header',24,'__ntk_guard_rows',0);make('detail',2,'__ntk_guard_rows_detail',24);var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),total);window[ab]=Math.max(Number(window[ab]||0),total);window[rb]=Math.max(Number(window[rb]||0),total);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,slot:total,header:24,detail:2,ab:ab,rb:rb,seen:Number(window.__bSeen||0),siteShape:true,siteBannerRows:true,adImages:true,imageUrls:urls.length}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e)}));}catch(_){}}}"
                + "function siteRowsReady(){try{var rows=Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n]'));if(!rows.length)return false;for(var ri=0;ri<rows.length;ri++){var row=rows[ri],n=Number(row.getAttribute('data-br-n')||'0');if(!Number.isFinite(n)||n<=0)return false;if(n<2)continue;var bs=Array.from(row.children).filter(function(e){return e instanceof HTMLElement&&e.tagName==='BUTTON'&&(e.hasAttribute('data-bs')||e.hasAttribute('data-bp'));});if(bs.length<2)return false;var r0=bs[0].getBoundingClientRect();if(bs[0].getClientRects().length===0)return false;var sep=false;for(var i=1;i<bs.length;i++){var r=bs[i].getBoundingClientRect();if(Math.abs(r.left-r0.left)>=1||Math.abs(r.top-r0.top)>=1){sep=true;break;}}if(!sep)return false;}return true;}catch(e){return false;}}"
                + "function waitSiteRows(ms){return new Promise(function(resolve){try{if(siteRowsReady())return resolve(true);var done=false,started=Date.now();function finish(v){if(done)return;done=true;try{mo&&mo.disconnect();}catch(_){}clearTimeout(to);resolve(v);}function check(){if(siteRowsReady())finish(true);else if(Date.now()-started>=ms)finish(false);}var mo=new MutationObserver(check),to=setTimeout(function(){finish(siteRowsReady());},Math.max(300,Number(ms||3000)));try{mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['data-br-n','data-bs','data-bp','src','style','class']});}catch(_){}requestAnimationFrame(function(){requestAnimationFrame(check);});}catch(e){resolve(false);}});}"
                + "function guardImages(){try{return Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n] img'));}catch(e){return[];}}"
                + "function guardLoadedCount(){try{var imgs=guardImages(),n=0;for(var i=0;i<imgs.length;i++){if(imgs[i].complete&&imgs[i].naturalWidth>0&&imgs[i].naturalHeight>0)n++;}return n;}catch(e){return 0;}}"
                + "function waitGuardImages(need,ms){return new Promise(function(resolve){try{need=Math.max(1,Number(need||1));if(guardLoadedCount()>=need)return resolve(true);var imgs=guardImages(),done=false;function finish(v){if(done)return;done=true;clearTimeout(to);imgs.forEach(function(img){try{img.removeEventListener('load',check);img.removeEventListener('error',check);}catch(_){}});resolve(v);}function check(){if(guardLoadedCount()>=need)finish(true);}imgs.forEach(function(img){try{img.addEventListener('load',check);img.addEventListener('error',check);if(img.decode)img.decode().then(check,check);}catch(_){}});var to=setTimeout(function(){finish(guardLoadedCount()>=need);},Math.max(300,Number(ms||3600)));check();}catch(e){resolve(false);}});}"
                + "function guardDomState(label,mod){try{var rows=Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n]')),root=rows[0]||document.getElementById('__ntk_guard_rows'),imgs=guardImages(),buttons=root?root.querySelectorAll('button'):[],rr=root?root.getBoundingClientRect():null,first=imgs[0]||null,br=buttons[0]?buttons[0].getBoundingClientRect():null,ready=mod&&mod.__i5?!!mod.__i5():null;window.NtkViewerBridge.onAckState(JSON.stringify({guardDomState:label,ready:ready,rows:rows.length,imgs:imgs.length,loaded:guardLoadedCount(),buttons:buttons.length,root:rr?{w:rr.width,h:rr.height}:null,button:br?{w:br.width,h:br.height}:null,img:first?{complete:first.complete,nw:first.naturalWidth,nh:first.naturalHeight,w:first.width,h:first.height,src:String(first.getAttribute('src')||'').slice(0,160),currentSrc:String(first.currentSrc||'').slice(0,160)}:null,seen:Number(window.__bSeen||0)}));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDomStateError:label,error:String(e)}));}catch(_){}}}"
                + "function guardGlobalState(label,mod){try{var o={guardGlobalState:label,acked:acked(),ready:mod&&mod.__i5?!!mod.__i5():null,ibOk:window.__ntk_ib_ok===undefined?null:String(window.__ntk_ib_ok),ibLoaded:window.__ntk_ib_loaded===undefined?null:String(window.__ntk_ib_loaded),aiTampered:window.__ntk_ai_tampered===undefined?null:String(window.__ntk_ai_tampered),inflight:window.__ntk_ad_ack_inflight===undefined?null:String(window.__ntk_ad_ack_inflight).slice(0,96),ackScope:window.__ntk_ad_ack_scope===undefined?null:String(window.__ntk_ad_ack_scope),ackLast:window.__ntk_ad_ack_last?JSON.stringify(window.__ntk_ad_ack_last).slice(0,160):null,guardLoadLast:window.__ntk_ad_guard_load_last?JSON.stringify(window.__ntk_ad_guard_load_last).slice(0,160):null,seen:Number(window.__bSeen||0),rows:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length};window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardGlobalStateError:label,error:String(e)}));}catch(_){}}}"
                + "function guardVersion(){try{var h=document.documentElement?document.documentElement.outerHTML:'';var m=h.match(/wv=([^\"'&<>]+)/);if(m&&m[1])return decodeURIComponent(m[1]);m=h.match(/b\\d{13}[^\"'<>]*wasm-\\d{13}/);if(m&&m[0])return m[0];m=h.match(/\\/(b\\d{13})\\/_next\\/static\\//);if(m&&m[1]){var n=Number(m[1].slice(1));if(Number.isFinite(n))return m[1]+'-wasm-'+(n+4);}if(performance&&performance.getEntriesByType){var es=performance.getEntriesByType('resource')||[];for(var i=0;i<es.length;i++){var u=String(es[i].name||''),r=u.match(/wv=([^&]+)/);if(r&&r[1])return decodeURIComponent(r[1]);r=u.match(/\\/(b\\d{13})\\/_next\\/static\\//);if(r&&r[1]){var q=Number(r[1].slice(1));if(Number.isFinite(q))return r[1]+'-wasm-'+(q+4);}}}try{if(window.NtkQuicBridge&&window.NtkQuicBridge.guardVersionFor){var gv=String(window.NtkQuicBridge.guardVersionFor(pageUrl())||'');if(gv)return gv;}}catch(_){}}catch(e){}return '';}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch))}));}catch(_){ }var v=guardVersion();var q=v?'?v='+encodeURIComponent(v):'';var js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q;if(!v){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadFallback:'no version'}));}catch(_){}}var mod=null,wasmUrl='';if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),jt=new TextDecoder().decode(jb),blob=new Blob([jt],{type:'application/javascript'}),bu=URL.createObjectURL(blob),wb=bytesFrom64(wr.bodyBase64||'');wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModule:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,wasmBlob:true}));}catch(_){}}}catch(bridgeLoadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleError:String(bridgeLoadErr)}));}catch(_){}}}if(!mod)mod=await import(abs(js));if(mod&&mod.default){try{await mod.default({module_or_path:wasmUrl||abs(wasm)});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch)),fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80)}));}catch(_){ }var v=guardVersion();var q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='';try{var st=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModule:true,version:v,ms:Math.round(performance.now()-st),source:abs(js)}));}catch(_){}if(mod&&mod.default){var it=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}catch(directErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleError:String(directErr),version:v}));}catch(_){}if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),jt=new TextDecoder().decode(jb),blob=new Blob([jt],{type:'application/javascript'}),bu=URL.createObjectURL(blob),wb=bytesFrom64(wr.bodyBase64||'');wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModule:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,wasmBlob:true}));}catch(_){}if(mod&&mod.default){try{await mod.default({module_or_path:wasmUrl||abs(wasm)});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}}}}catch(bridgeLoadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleError:String(bridgeLoadErr)}));}catch(_){}}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch)),bridgeFirst:true}));}catch(_){ }var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='',st=performance.now();if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),jt=new TextDecoder().decode(jb),bu=URL.createObjectURL(new Blob([jt],{type:'application/javascript'}));wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleFirst:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,ms:Math.round(performance.now()-st)}));}catch(_){}if(mod&&mod.default){var it=performance.now();try{await mod.default({module_or_path:wasmUrl});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}}catch(bridgeErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleFirstError:String(bridgeErr),version:v}));}catch(_){}}}if(!mod){try{var dt=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleFallback:true,version:v,ms:Math.round(performance.now()-dt),source:abs(js)}));}catch(_){}if(mod&&mod.default){var di=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectInitFallback:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-di)}));}catch(_){}}}catch(directErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleFallbackError:String(directErr),version:v}));}catch(_){}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion(),bridgeFirst:true}));}catch(_){}return null;}}"
                + "async function guardAck(ch){try{var mod=await loadGuard();if(!mod||!ch){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckMissing:'module'}));}catch(_){}return false;}try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisBeforeI4:true}));}catch(_){}try{installGuardBeaconBridge();}catch(_){}try{if(mod.__i6&&!window.__ntk_guard_i6_once){window.__ntk_guard_i6_once=1;mod.__i6();window.NtkViewerBridge.onAckState(JSON.stringify({guardPrimeI6:true}));}}catch(primeErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardPrimeI6Error:String(primeErr)}));}catch(_){}}try{var fastShell=document.documentElement&&document.documentElement.getAttribute('data-ntk-fast-shell')==='1';var realRows=false;if(!fastShell)realRows=await waitSiteRows(4500);if(realRows){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsNative:true,count:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length}));}catch(_){}}else{ensureGuardRows(ch);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsSyntheticFast:fastShell,count:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length}));}catch(_){}}var imageNeed=Math.max(1,Number((ch&&ch.minSeen)||2));var imageReady=await waitGuardImages(imageNeed,1800);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardImagesReady:imageReady,need:imageNeed,loaded:guardLoadedCount(),total:guardImages().length,fastShell:fastShell}));}catch(_){}guardDomState('rows',mod);}catch(_){}try{var seenBefore=await fireGuardImpressions(ch);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardPreI4Imps:true,seen:seenBefore}));}catch(_){}}catch(_){}try{guardDomState('after-imps',mod);}catch(_){}try{var et=await bridgeReq('/api/ev/etag','GET',null,{'accept':'application/json,*/*'});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardEtagBeforeI4:true,status:et&&et.status||0}));}catch(_){}}catch(etagErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardEtagBeforeI4Error:String(etagErr)}));}catch(_){}}try{delete window.__ntk_ad_ack_inflight;window.NtkViewerBridge.onAckState(JSON.stringify({guardInflightCleared:true}));}catch(_){}try{guardGlobalState('before-i4',mod);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardExports:Object.keys(mod).slice(0,24),hasI4:!!mod.__i4,hasI5:!!mod.__i5,defaultType:typeof mod.default,prime:'i6'}));}catch(_){}var fn=mod.__i4||mod.i4||mod._i4||mod.guardAck||mod.adAck;if(!fn)return false;var arg=JSON.stringify(ch);for(var i=0;i<6;i++){try{var before=mod.__i5?!!mod.__i5():null;var r=fn(arg,scope);if(r&&r.then)r=await r;var after=mod.__i5?!!mod.__i5():null;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckTry:i,mode:'site-like-native-webview',argLen:String(arg||'').length,arg2Len:String(scope||'').length,arg:String(arg||'').slice(0,48),result:String(r||'').slice(0,80),before:before,after:after,acked:acked(),siteRetry:true}));}catch(_){}try{guardDomState('after-i4-'+i,mod);guardGlobalState('after-i4-'+i,mod);}catch(_){}if(await waitAck(550))return true;}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckTryError:i,error:String(inner),siteRetry:true}));}catch(_){}}}try{guardGlobalState('after-i4-all',mod);}catch(_){}return acked();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckError:String(e)}));}catch(_){}return false;}}"
                + "async function guardProof(token){try{var mod=await loadGuard();if(!mod||!mod._vc||!token)return '';var args=[token,JSON.stringify({token:token,path:scope}),scope];for(var i=0;i<args.length;i++){try{var v=mod._vc(args[i],scope);if(v&&v.then)v=await v;v=String(v||'');try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTry:i,len:v.length,value:v.slice(0,16)}));}catch(_){}if(v&&v!=='true'&&v!=='false')return v;}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTryError:i,error:String(inner)}));}catch(_){}}}return '';}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofError:String(e)}));}catch(_){}return '';}}"
                + "async function fireGuardImpressions(ch){var seen=0,submitted=0;try{var imps=(ch&&ch.impressionUrls)||[],minSeen=Math.max(1,Number((ch&&ch.minSeen)||2)),target=Math.max(1,Math.min(minSeen+1,imps.length));for(var i=0;i<target;i++){try{submitted++;var r=await bridgeReq(imps[i],'GET',null,{'accept':'image/gif,image/*,*/*'});var code=r&&r.status||0;if(code>=200&&code<300)seen++;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImp:i,status:code,seen:seen,target:target}));}catch(_){}}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpError:i,error:String(inner)}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsError:String(e)}));}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsComplete:true,seen:seen,submitted:submitted}));}catch(_){}return seen;}"
                + "function randHex(n){try{var a=new Uint8Array(Math.ceil((n||16)/2));crypto.getRandomValues(a);var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s.slice(0,n||16);}catch(e){return String(Date.now().toString(16)+Math.random().toString(16).slice(2)).slice(0,n||16);}}"
                + "async function directAck(){try{var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}if(!c.body.challenge)return false;var ch=c.body.challenge,token=ch.token||'';if(await guardAck(ch))return true;var seen=await fireGuardImpressions(ch);var tp=await guardProof(token);if(!tp||tp==='true'||tp==='false')tp='';var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=minSeen;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckSubmit:true,tp:String(tp||''),tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen}));}catch(_){}await sleep(120);var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckResponse:true,status:a&&a.status||0,body:b}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckError:String(e)}));}catch(_){}}return false;}"
                + "function extractAckState(){try{var ls={},ss={},i,k;for(i=0;i<localStorage.length;i++){k=localStorage.key(i);if(k)ls[k]=localStorage.getItem(k);}for(i=0;i<sessionStorage.length;i++){k=sessionStorage.key(i);if(k)ss[k]=sessionStorage.getItem(k);}return JSON.stringify({cookies:docCookie(),local:ls,session:ss,ackScope:(function(){try{return window.__ntk_ad_ack_scope;}catch(e){return null;}})(),ntkVars:(function(){try{var o={};for(var p in window)if(p.indexOf('__ntk')===0)try{o[p]=String(window[p]);}catch(e){}return o;}catch(e){return{};}})(),ua:navigator.userAgent,ts:Date.now()});}catch(e){return JSON.stringify({error:String(e)});}}"
                + "(async function(){try{for(var i=0;i<20;i++){if(document.body)break;await sleep(100);}var deadline=Date.now()+18000,armed=false;if(!acked()){armed=true;if(!(await directAck()))rearm();}var r=await api();while(Date.now()<deadline&&r&&r.status===403){try{window.NtkViewerBridge.onAckState(extractAckState());}catch(e){}if(!acked()&&!armed){armed=true;if(!(await directAck()))rearm();}await waitAck(Math.min(1800,Math.max(0,deadline-Date.now())));await sleep(180);r=await api();}send({code:r?r.status:0,body:r?r.body:{error:'timeout'}});}catch(e){send({code:0,error:String(e)});}})();"
                + "})()";
    }

    private static String buildViewerImageFetchScript(String baseUrl, String path, String ackScopePath,
                                                      String kind, String workId,
                                                      String episodeId, String imagesToken) {
        String scope = ackScopePath == null || ackScopePath.length() == 0 ? path : ackScopePath;
        return buildViewerImageFetchScript(baseUrl, scope, kind, workId, episodeId, imagesToken);
    }
    private WebResourceResponse interceptViewerQuicRequest(String userAgent, String fallbackCookieHeader,
                                                           WebResourceRequest request) {
        if(request == null || request.getUrl() == null || !NtkQuicFetcher.isAvailable())
            return null;
        String method = request.getMethod();
        String url = request.getUrl().toString();
        boolean metricImage = false;
        boolean cdnImage = false;
        try {
            URI requestUri = URI.create(url);
            String requestPath = requestUri.getPath();
            metricImage = "/api/m/i".equals(requestPath);
            cdnImage = isNtkImageCdnUrl(requestUri);
        } catch (Exception ignored) {
        }
        if(method == null || !"GET".equalsIgnoreCase(method)
                || (!isNtkProtectedHttpsUrl(url) && !cdnImage))
            return null;
        if(shouldUseDirectWebViewForActiveFetch(url))
            return null;
        try {
            if(metricImage || cdnImage)
                Log.d(TAG, "ntk_viewer_quic_intercept_image_start url=" + url
                        + ",cdn=" + cdnImage
                        + ",metric=" + metricImage
                        + ",headers=" + request.getRequestHeaders());
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                    mergedCookieHeader(url, fallbackCookieHeader), request.getRequestHeaders(),
                    request.isForMainFrame() ? 15000L : 10000L);
            if(result == null || result.error != null || result.code < 200 || result.code >= 500
                    || result.bodyBytes == null || result.bodyBytes.length == 0) {
                if(metricImage || cdnImage)
                    Log.d(TAG, "ntk_viewer_quic_intercept_image_miss code="
                            + (result == null ? 0 : result.code)
                            + ",error=" + (result == null ? "null" : result.error)
                            + ",len=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length));
                return null;
            }
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_viewer_quic_intercept url=" + url
                        + ",main=" + request.isForMainFrame()
                        + ",code=" + result.code
                        + ",type=" + result.contentType()
                        + ",len=" + result.bodyBytes.length);
            applyWebViewCookies(url, result);
            byte[] responseBytes = result.bodyBytes;
            if(request.isForMainFrame() && responseMimeType(result.contentType()).toLowerCase(Locale.ROOT).contains("html"))
                responseBytes = injectNtkQuicBridgeScript(result.body, result.headers).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String mimeType = metricImage ? "image/gif" : responseMimeType(result.contentType());
            if((metricImage || cdnImage) && !mimeType.toLowerCase(Locale.ROOT).startsWith("image/"))
                mimeType = imageMimeTypeFromUrl(url);
            if(metricImage || cdnImage)
                Log.d(TAG, "ntk_viewer_quic_intercept_image_hit code=" + result.code
                        + ",cdn=" + cdnImage
                        + ",metric=" + metricImage
                        + ",type=" + result.contentType()
                        + ",mime=" + mimeType
                        + ",len=" + responseBytes.length
                        + ",sig=" + responseSignature(responseBytes));
            return new WebResourceResponse(mimeType,
                    (metricImage || cdnImage) ? null : responseEncoding(result.contentType()), result.code,
                    result.code >= 400 ? "Cloudflare" : "OK",
                    responseHeaders(result.headers), new ByteArrayInputStream(responseBytes));
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_viewer_quic_intercept_failed url=" + url, e);
            return null;
        }
    }

    private boolean shouldUseDirectWebViewForActiveFetch(String url) {
        FetchTask task;
        synchronized (lock) {
            task = activeTask;
        }
        if(task == null || task.path == null || url == null)
            return false;
        String activePath = task.path;
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if(path == null)
                return false;
            String query = uri.getRawQuery();
            String requested = query == null || query.length() == 0 ? path : path + "?" + query;
            boolean direct = activePath.equals(requested);
            if(direct && Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_viewer_quic_intercept_skip_direct path=" + activePath);
            return direct;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNtkProtectedHttpsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if(!"https".equalsIgnoreCase(scheme) || host == null)
                return false;
            return hostMatchesRoot(host, currentNtkRootHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNtkAdGuardModuleUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null && (path.equals("/api/ad/guard-js") || path.equals("/api/ad/guard-wasm"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String currentNtkRootHost() {
        try {
            String root = MainApplication.p == null
                    ? CustomHttpClient.NTK_WEBTOON_URL
                    : MainApplication.p.getNtkResolvedRoot();
            URI uri = URI.create(root);
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return normalizeHost(CustomHttpClient.NTK_WEBTOON_URL);
        }
    }

    private static boolean hostMatchesRoot(String host, String rootHost) {
        host = normalizeHost(host);
        rootHost = normalizeHost(rootHost);
        return host.length() > 0 && rootHost.length() > 0
                && (host.equals(rootHost) || host.endsWith("." + rootHost));
    }

    private static String normalizeHost(String host) {
        if(host == null)
            return "";
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String webViewCookieHeader(String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String mergedCookieHeader(String url, String fallbackCookieHeader) {
        String webViewCookieHeader = webViewCookieHeader(url);
        if(webViewCookieHeader == null || webViewCookieHeader.length() == 0)
            return fallbackCookieHeader == null ? "" : fallbackCookieHeader;
        if(fallbackCookieHeader == null || fallbackCookieHeader.length() == 0)
            return webViewCookieHeader;
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        addCookieHeaderParts(cookies, fallbackCookieHeader);
        addCookieHeaderParts(cookies, webViewCookieHeader);
        StringBuilder builder = new StringBuilder();
        for(String value : cookies.values()) {
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(value);
        }
        return builder.toString();
    }

    private static void addCookieHeaderParts(LinkedHashMap<String, String> cookies, String cookieHeader) {
        if(cookies == null || cookieHeader == null || cookieHeader.length() == 0)
            return;
        for(String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            int equals = trimmed.indexOf('=');
            if(equals <= 0)
                continue;
            String name = trimmed.substring(0, equals).trim();
            if(name.length() > 0)
                cookies.put(name, trimmed);
        }
    }

    private static void applyWebViewCookieHeader(String url, String cookieHeader) {
        if(url == null || cookieHeader == null || cookieHeader.length() == 0)
            return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for(String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                if(trimmed.length() > 0 && trimmed.indexOf('=') > 0)
                    manager.setCookie(url, trimmed);
            }
            manager.flush();
        } catch (Exception ignored) {
        }
    }

    private static void applyWebViewCookies(String url, NtkQuicFetcher.Result result) {
        if(url == null || result == null)
            return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for(String cookie : result.setCookies()) {
                if(cookie != null && cookie.length() > 0)
                    manager.setCookie(url, cookie);
            }
            manager.flush();
        } catch (Exception ignored) {
        }
    }

    private static String injectNtkQuicBridgeScript(String html, Map<String, List<String>> headers) {
        if(html == null || html.length() == 0 || html.contains("__ntkViewerQuicBridgeInstalled"))
            return html;
        String nonce = cspNonce(headers);
        if(nonce == null || nonce.length() == 0)
            nonce = htmlNonce(html);
        String script = "<script" + (nonce == null || nonce.length() == 0
                ? "" : " nonce=\"" + escapeHtmlAttribute(nonce) + "\"") + ">" + NTK_QUIC_BRIDGE_JS + "</script>";
        String lower = html.toLowerCase(Locale.ROOT);
        int head = lower.indexOf("<head");
        if(head >= 0) {
            int headEnd = html.indexOf('>', head);
            if(headEnd >= 0)
                return html.substring(0, headEnd + 1) + script + html.substring(headEnd + 1);
        }
        int htmlTag = lower.indexOf("<html");
        if(htmlTag >= 0) {
            int htmlEnd = html.indexOf('>', htmlTag);
            if(htmlEnd >= 0)
                return html.substring(0, htmlEnd + 1) + "<head>" + script + "</head>" + html.substring(htmlEnd + 1);
        }
        return script + html;
    }

    private static String cspNonce(Map<String, List<String>> headers) {
        if(headers == null)
            return null;
        for(String key : headers.keySet()) {
            if(!"content-security-policy".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values == null)
                continue;
            for(String value : values) {
                String nonce = extractNonceToken(value);
                if(nonce != null && nonce.length() > 0)
                    return nonce;
            }
        }
        return null;
    }

    private static String htmlNonce(String html) {
        String nonce = extractAttributeNonce(html, "nonce=\"", "\"");
        if(nonce != null && nonce.length() > 0)
            return nonce;
        return extractAttributeNonce(html, "nonce='", "'");
    }

    private static String extractNonceToken(String value) {
        if(value == null)
            return null;
        int start = value.indexOf("'nonce-");
        if(start < 0)
            return null;
        start += "'nonce-".length();
        int end = value.indexOf('\'', start);
        if(end <= start)
            return null;
        return value.substring(start, end);
    }

    private static String extractAttributeNonce(String value, String marker, String terminator) {
        if(value == null)
            return null;
        int start = value.indexOf(marker);
        if(start < 0)
            return null;
        start += marker.length();
        int end = value.indexOf(terminator, start);
        if(end <= start)
            return null;
        return value.substring(start, end);
    }

    private static String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String responseMimeType(String contentType) {
        if(contentType == null || contentType.trim().length() == 0)
            return "text/html";
        String type = contentType.split(";", 2)[0].trim();
        return type.length() == 0 ? "text/html" : type;
    }

    private static String responseEncoding(String contentType) {
        if(contentType != null) {
            for(String part : contentType.split(";")) {
                String trimmed = part.trim();
                if(trimmed.toLowerCase(Locale.ROOT).startsWith("charset="))
                    return trimmed.substring("charset=".length()).trim();
            }
        }
        return "UTF-8";
    }

    private static Map<String, String> responseHeaders(Map<String, List<String>> source) {
        HashMap<String, String> result = new HashMap<>();
        if(source == null)
            return result;
        for(String key : source.keySet()) {
            if(key == null || shouldStripWebResourceResponseHeader(key))
                continue;
            List<String> values = source.get(key);
            if(values != null && values.size() > 0 && values.get(0) != null)
                result.put(key, values.get(0));
        }
        return result;
    }

    private static boolean isNtkImageCdnUrl(URI uri) {
        if(uri == null || !"https".equalsIgnoreCase(uri.getScheme()))
            return false;
        String host = uri.getHost();
        String path = uri.getPath();
        if(host == null || path == null)
            return false;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return lowerHost.equals("i.toonflix.app")
                && (lowerPath.contains("/board_uploads/")
                || lowerPath.contains("/webtoon_uploads/")
                || lowerPath.contains("/manhwa_uploads/")
                || lowerPath.contains("/comic_uploads/")
                || lowerPath.contains("/blacktoon/episodes/")
                || lowerPath.contains("/wt/episodes/"))
                && lowerPath.matches(".*\\.(?:jpg|jpeg|png|webp|gif)$");
    }

    private static String imageMimeTypeFromUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".png"))
            return "image/png";
        if(lower.endsWith(".webp"))
            return "image/webp";
        if(lower.endsWith(".gif"))
            return "image/gif";
        return "image/jpeg";
    }

    private static boolean shouldStripWebResourceResponseHeader(String key) {
        if(key == null)
            return true;
        String lower = key.toLowerCase(Locale.ROOT);
        return "set-cookie".equals(lower)
                || "content-encoding".equals(lower)
                || "content-length".equals(lower)
                || "transfer-encoding".equals(lower)
                || "connection".equals(lower);
    }

    private static String responseSignature(byte[] bytes) {
        if(bytes == null || bytes.length == 0)
            return "";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(12, bytes.length);
        for(int i = 0; i < count; i++) {
            if(i > 0)
                builder.append(' ');
            builder.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return builder.toString();
    }

    private static String viewerImageCacheKey(String kind, String workId, String episodeId, String path) {
        if(kind == null || workId == null || episodeId == null || path == null
                || kind.length() == 0 || workId.length() == 0 || episodeId.length() == 0
                || path.length() == 0)
            return "";
        return kind + ':' + workId + ':' + episodeId + ':' + path;
    }

    private static String viewerImageFlightKey(String baseUrl, String path, String kind, String workId,
                                               String episodeId, String imagesToken) {
        return (baseUrl == null ? "" : baseUrl) + '|'
                + viewerImageCacheKey(kind, workId, episodeId, path);
    }

    private static String jsonQuote(String value) {
        if(value == null)
            return "\"\"";
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
                    if(c < 0x20)
                        builder.append(String.format("\\u%04x", (int) c));
                    else
                        builder.append(c);
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private final class Bridge {
        @JavascriptInterface
        public void onResult(String value) {
            FetchTask task;
            synchronized (lock) {
                task = activeTask;
            }
            if(task != null)
                onBridgeResult(task.token, value);
        }

        @JavascriptInterface
        public void onFetchResult(String token, String value) {
            onBridgeResult(token, value);
        }

        @JavascriptInterface
        public void onPreAckDone(String token, boolean ok, String reason) {
            mainHandler.post(() -> {
                FetchTask task;
                synchronized (lock) {
                    task = activeTask;
                }
                if(task == null || task.completed || !task.token.equals(token))
                    return;
                Log.d(TAG, "ntk_webview_preack_done path=" + task.path
                        + ",ok=" + ok
                        + ",reason=" + reason);
                startEpisodeNavigationOnMain(task, ok ? "preack-ok" : "preack-miss");
            });
        }
    }

    private static final class ViewerImageBridge {
        private final ViewerImageResult result;
        private final Runnable finish;
        private final Handler mainHandler;

        ViewerImageBridge(ViewerImageResult result, Runnable finish, Handler mainHandler) {
            this.result = result;
            this.finish = finish;
            this.mainHandler = mainHandler;
        }

        @JavascriptInterface
        public void onViewerImages(String value) {
            result.body = value == null ? "" : value;
            mainHandler.post(finish);
        }

        @JavascriptInterface
        public void onAckState(String value) {
            Log.d(TAG, "ntk_ack_state=" + (value == null ? "" : value));
        }

        @JavascriptInterface
        public void onAckProof(String value) {
            Log.d(TAG, "ntk_ack_proof=" + (value == null ? "" : value));
        }
    }

    private static final class NtkQuicBridge {
        private final Context context;
        private final String userAgent;
        private final String fallbackCookieHeader;
        private final NtkQuicFetcher.Session http2Session;
        private final Map<String, byte[]> guardLoaderByVersion = new ConcurrentHashMap<>();
        private NtkQuicFetcher.Session quicSession;
        private String quicSessionHost = "";

        NtkQuicBridge(Context context, String userAgent, String fallbackCookieHeader) {
            this.context = context == null ? MainApplication.appContext : context.getApplicationContext();
            this.userAgent = userAgent;
            this.fallbackCookieHeader = fallbackCookieHeader == null ? "" : fallbackCookieHeader;
            this.http2Session = NtkQuicFetcher.newHttp2Session(this.context, userAgent);
        }

        void close() {
            if(http2Session != null)
                http2Session.close();
            if(quicSession != null)
                quicSession.close();
        }

        @JavascriptInterface
        public String cookie(String url, String name) {
            String value = cookieValue(webViewCookieHeader(url), name);
            return value.length() > 0 ? value : cookieValue(fallbackCookieHeader, name);
        }

        @JavascriptInterface
        public String guardVersion() {
            try {
                String value = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                        .getString("ntk_guard_version", "");
                return value != null && value.matches("b\\d{13}-wasm-\\d{13}") ? value : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String guardVersionFor(String url) {
            String remembered = guardVersion();
            if(remembered.length() > 0)
                return remembered;
            if(!isNtkProtectedHttpsUrl(url) || !NtkQuicFetcher.isAvailable())
                return "";
            try {
                URI uri = URI.create(url);
                String origin = uri.getScheme() + "://" + uri.getHost();
                String pageUrl = origin + (uri.getRawPath() == null || uri.getRawPath().length() == 0
                        ? "/" : uri.getRawPath());
                if(uri.getRawQuery() != null && uri.getRawQuery().length() > 0)
                    pageUrl += "?" + uri.getRawQuery();
                String cookieHeader = bridgeCookieHeader(pageUrl, fallbackCookieHeader, new HashMap<>(), new byte[0]);
                Map<String, String> headers = new HashMap<>();
                headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                headers.put("referer", pageUrl);
                headers.put("origin", origin);
                NtkQuicFetcher.Session session = quicControlSession(pageUrl);
                long started = SystemClock.uptimeMillis();
                NtkQuicFetcher.Result result = session == null
                        ? NtkQuicFetcher.fetch(context, pageUrl, userAgent, cookieHeader,
                                headers, "GET", null, 2200L)
                        : session.fetch(pageUrl, userAgent, cookieHeader,
                                headers, "GET", null, 2200L);
                String version = result == null || result.body == null ? "" : ntkGuardVersionFromText(result.body);
                if(version.length() > 0) {
                    context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                            .edit()
                            .putString("ntk_guard_version", version)
                            .apply();
                }
                Log.d(TAG, "ntk_viewer_guard_version_discovery url=" + pageUrl
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? "null" : result.error)
                        + ",len=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length)
                        + ",version=" + version
                        + ",ms=" + (SystemClock.uptimeMillis() - started));
                return version;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_guard_version_discovery_error " + e);
                return "";
            }
        }

        @JavascriptInterface
        public String hmacSha256(String key, String message) {
            if(key == null || key.length() == 0 || message == null)
                return "";
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] digest = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return Base64.encodeToString(digest,
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String hmacSha256Bytes(String keyBase64, String messageBase64) {
            try {
                byte[] key = Base64.decode(keyBase64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                byte[] message = Base64.decode(messageBase64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
                return Base64.encodeToString(mac.doFinal(message),
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String aesGcmDecrypt(String keyBase64, String ivBase64, String cipherBase64) {
            try {
                byte[] key = Base64.decode(keyBase64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                byte[] iv = Base64.decode(ivBase64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                byte[] cipherText = Base64.decode(cipherBase64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                return Base64.encodeToString(cipher.doFinal(cipherText),
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(!isNtkProtectedHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                String cachedGuard = cachedGuardModuleResponse(url);
                if(cachedGuard != null)
                    return cachedGuard;
                if(!NtkQuicFetcher.isAvailable())
                    return bridgeError("quic unavailable");
                byte[] body = bodyBase64 == null || bodyBase64.length() == 0
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                String cookieHeader = bridgeCookieHeader(url, fallbackCookieHeader, headers, body);
                removeHeaderIgnoreCase(headers, "cookie");
                logBridgeRequest(url, method, headers, body, cookieHeader);
                boolean adControlPost = shouldUseHttp2ForNtkAdControlPost(url, method);
                NtkQuicFetcher.Result result;
                if(adControlPost) {
                    long startMs = SystemClock.uptimeMillis();
                    NtkQuicFetcher.Session primarySession = quicControlSession(url);
                    result = primarySession == null
                            ? NtkQuicFetcher.fetch(MainApplication.appContext,
                                    url, userAgent, cookieHeader, headers,
                                    method, body, 2500L)
                            : primarySession.fetch(url, userAgent, cookieHeader, headers,
                                    method, body, 2500L);
                    Log.d(TAG, "ntk_viewer_ad_bridge_quic_first code="
                            + (result == null ? -1 : result.code)
                            + ",error=" + (result == null ? "null" : result.error)
                            + ",elapsedMs=" + (SystemClock.uptimeMillis() - startMs)
                            + ",session=" + (primarySession != null)
                            + ",url=" + url);
                    if(result == null || result.error != null || result.code >= 500) {
                        long retryStartMs = SystemClock.uptimeMillis();
                        NtkQuicFetcher.Result retry = http2Session == null
                                ? NtkQuicFetcher.fetchHttp2Only(MainApplication.appContext,
                                        url, userAgent, cookieHeader, headers, method, body, 1800L)
                                : http2Session.fetch(url, userAgent, cookieHeader, headers,
                                        method, body, 1800L);
                        Log.d(TAG, "ntk_viewer_ad_bridge_http2_retry code="
                                + (retry == null ? -1 : retry.code)
                                + ",error=" + (retry == null ? "null" : retry.error)
                                + ",elapsedMs=" + (SystemClock.uptimeMillis() - retryStartMs)
                                + ",session=" + (http2Session != null)
                                + ",url=" + url);
                        if(retry != null && retry.error == null
                                && (result == null || result.error != null || retry.code < result.code)) {
                            result = retry;
                            Log.d(TAG, "ntk_viewer_ad_bridge_http2_retry_selected code="
                                    + result.code + ",url=" + url);
                        }
                    }
                } else {
                    result = NtkQuicFetcher.fetch(MainApplication.appContext,
                            url, userAgent, cookieHeader, headers,
                            method, body, 15000L);
                }
                if(result == null)
                    return bridgeError("empty result");
                if(result.error != null) {
                    String localGuard = localGuardModuleResponse(url);
                    if(localGuard != null)
                        return localGuard;
                    return bridgeError(String.valueOf(result.error));
                }
                String localGuard = result.code >= 400 ? localGuardModuleResponse(url) : null;
                if(localGuard != null)
                    return localGuard;
                rememberGuardModuleResponse(url, result.bodyBytes);
                String decodedGuard = decodedVersionedGuardWasmResponse(url, result);
                if(decodedGuard != null)
                    return decodedGuard;
                applyWebViewCookies(url, result);
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_viewer_quic_bridge method=" + method
                            + ",code=" + result.code
                            + ",len=" + result.bodyBytes.length
                            + ",url=" + url);
                logAdControlBridgeResponse(url, method, result);
                logMetricsBridgeResponse(url, method, result);
                logViewerImageBridgeResponse(url, method, result);
                cacheViewerImageApiResponse(url, headers, body, result);
                JSONObject object = new JSONObject();
                object.put("ok", true);
                object.put("status", result.code);
                object.put("statusText", result.code >= 400 ? "Cloudflare" : "OK");
                object.put("headers", new JSONObject(responseHeaders(result.headers)));
                object.put("bodyBase64", Base64.encodeToString(result.bodyBytes, Base64.NO_WRAP));
                return object.toString();
            } catch (Exception e) {
                return bridgeError(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public String requestGetBatch(String urlsJson, String headersJson) {
            try {
                JSONArray urls = new JSONArray(urlsJson == null ? "[]" : urlsJson);
                int count = Math.min(urls.length(), 8);
                JSONArray results = new JSONArray();
                if(count <= 0) {
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("results", results);
                    return out.toString();
                }
                Map<String, String> baseHeaders = parseBridgeHeaders(headersJson);
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
                        .newFixedThreadPool(Math.min(4, count));
                java.util.List<java.util.concurrent.Future<JSONObject>> futures = new ArrayList<>();
                long started = SystemClock.uptimeMillis();
                try {
                    for(int i = 0; i < count; i++) {
                        final int index = i;
                        final String url = urls.optString(i, "");
                        futures.add(executor.submit(() -> {
                            JSONObject item = new JSONObject();
                            item.put("index", index);
                            item.put("url", url);
                            if(!isNtkProtectedHttpsUrl(url)) {
                                item.put("ok", false);
                                item.put("error", "unsupported url");
                                return item;
                            }
                            Map<String, String> headers = new HashMap<>(baseHeaders);
                            String cookieHeader = bridgeCookieHeader(url, fallbackCookieHeader, headers, new byte[0]);
                            removeHeaderIgnoreCase(headers, "cookie");
                            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                                    cookieHeader, headers, "GET", null, 2200L);
                            if(result != null)
                                applyWebViewCookies(url, result);
                            item.put("ok", result != null && result.error == null
                                    && result.code >= 200 && result.code < 400);
                            item.put("status", result == null ? 0 : result.code);
                            item.put("error", result == null ? "null" : String.valueOf(result.error));
                            item.put("len", result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length);
                            return item;
                        }));
                    }
                    for(java.util.concurrent.Future<JSONObject> future : futures) {
                        try {
                            results.put(future.get(2600L, TimeUnit.MILLISECONDS));
                        } catch (Exception e) {
                            JSONObject item = new JSONObject();
                            item.put("ok", false);
                            item.put("error", String.valueOf(e));
                            results.put(item);
                        }
                    }
                } finally {
                    executor.shutdownNow();
                }
                Log.d(TAG, "ntk_viewer_quic_bridge_batch_get count=" + count
                        + ",ms=" + (SystemClock.uptimeMillis() - started));
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("results", results);
                return out.toString();
            } catch (Exception e) {
                return bridgeError(String.valueOf(e));
            }
        }

        private static boolean shouldUseHttp2ForNtkAdControlPost(String url, String method) {
            if(method == null || !"POST".equalsIgnoreCase(method))
                return false;
            try {
                String path = URI.create(url).getPath();
                return "/api/ad/challenge".equals(path)
                        || "/api/ad/canary".equals(path)
                        || "/api/ad/ack".equals(path)
                        || "/api/ev/sync".equals(path)
                        || "/api/m/ev".equals(path);
            } catch (Exception e) {
                return false;
            }
        }

        private synchronized NtkQuicFetcher.Session quicControlSession(String url) {
            try {
                String host = URI.create(url).getHost();
                if(host == null || host.length() == 0)
                    return null;
                if(quicSession != null && host.equalsIgnoreCase(quicSessionHost))
                    return quicSession;
                if(quicSession != null)
                    quicSession.close();
                quicSession = NtkQuicFetcher.newQuicSession(context, userAgent, host);
                quicSessionHost = quicSession == null ? "" : host;
                return quicSession;
            } catch (Exception e) {
                return null;
            }
        }

        private void logAdControlBridgeResponse(String url, String method, NtkQuicFetcher.Result result) {
            if(result == null)
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(path == null || !path.startsWith("/api/ad/"))
                    return;
                String body = result.body == null ? "" : result.body;
                body = body.replace('\n', ' ').replace('\r', ' ');
                if(body.length() > 220)
                    body = body.substring(0, 220);
                List<String> setCookies = result.setCookies();
                Log.d(TAG, "ntk_viewer_ad_bridge_response method=" + method
                        + ",path=" + path
                        + ",code=" + result.code
                        + ",len=" + result.bodyBytes.length
                        + ",setCookies=" + setCookies.size()
                        + ",cookieNames=" + cookieNames(setCookies)
                        + ",body=" + body);
            } catch (Exception ignored) {
            }
        }

        private static String cookieNames(List<String> cookies) {
            if(cookies == null || cookies.size() == 0)
                return "";
            StringBuilder builder = new StringBuilder();
            for(String cookie : cookies) {
                if(cookie == null)
                    continue;
                String trimmed = cookie.trim();
                int equals = trimmed.indexOf('=');
                if(equals <= 0)
                    continue;
                if(builder.length() > 0)
                    builder.append('|');
                builder.append(trimmed.substring(0, equals));
            }
            return builder.toString();
        }

        private void logViewerImageBridgeResponse(String url, String method, NtkQuicFetcher.Result result) {
            if(result == null)
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(path == null || !(path.endsWith("/manhwa-images") || path.endsWith("/webtoon-images")))
                    return;
                String body = result.body == null ? "" : result.body;
                body = body.replace('\n', ' ').replace('\r', ' ');
                if(body.length() > 320)
                    body = body.substring(0, 320);
                Log.d(TAG, "ntk_viewer_image_bridge_response method=" + method
                        + ",path=" + path
                        + ",code=" + result.code
                        + ",len=" + result.bodyBytes.length
                        + ",body=" + body);
            } catch (Exception ignored) {
            }
        }

        private void logMetricsBridgeResponse(String url, String method, NtkQuicFetcher.Result result) {
            if(result == null)
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(path == null || !path.startsWith("/api/m/"))
                    return;
                String body = result.body == null ? "" : result.body;
                body = body.replace('\n', ' ').replace('\r', ' ');
                if(body.length() > 320)
                    body = body.substring(0, 320);
                Log.d(TAG, "ntk_viewer_metrics_bridge_response method=" + method
                        + ",path=" + path
                        + ",code=" + result.code
                        + ",len=" + result.bodyBytes.length
                        + ",body=" + body);
            } catch (Exception ignored) {
            }
        }

        private String localGuardModuleResponse(String url) {
            if(!isNtkAdGuardModuleUrl(url))
                return null;
            try {
                String path = URI.create(url).getPath();
                boolean wasm = "/api/ad/guard-wasm".equals(path);
                byte[] bytes = readAssetBytes(context, wasm
                        ? "ntk_guard/guard-wasm.bin"
                        : "ntk_guard/guard.js");
                if(bytes == null || bytes.length == 0)
                    return null;
                if(wasm) {
                    byte[] loader = readAssetBytes(context, "ntk_guard/guard.js");
                    if(!isHardenedGuardLoader(loader)) {
                        byte[] raw = decryptLocalGuardWasm(bytes);
                        if(raw != null && raw.length > 4)
                            bytes = raw;
                        else
                            return null;
                    }
                } else {
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if(!text.startsWith("/*!HARDENED*/"))
                        bytes = stripEncryptedGuardLoader(bytes);
                }
                JSONObject object = new JSONObject();
                object.put("ok", true);
                object.put("status", 200);
                object.put("statusText", "OK");
                JSONObject headers = new JSONObject();
                headers.put("content-type", wasm ? "application/wasm" : "application/javascript");
                object.put("headers", headers);
                object.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                Log.d(TAG, "ntk_viewer_quic_bridge_local_guard url=" + url
                        + ",wasm=" + wasm
                        + ",len=" + bytes.length);
                return object.toString();
            } catch (Exception e) {
                return null;
            }
        }

        private String decodedVersionedGuardWasmResponse(String url, NtkQuicFetcher.Result result) {
            if(result == null || result.code != 200 || result.bodyBytes == null)
                return null;
            if(!isNtkAdGuardModuleUrl(url))
                return null;
            try {
                URI uri = URI.create(url);
                if(!"/api/ad/guard-wasm".equals(uri.getPath()))
                    return null;
                if(ntkGuardVersionFromUrl(url).length() == 0)
                    return null;
                if(isHardenedGuardLoader(ntkGuardVersionFromUrl(url)))
                    return null;
                byte[] raw = decryptVersionedGuardWasm(ntkGuardVersionFromUrl(url), result.bodyBytes);
                if(raw == null || raw.length <= 4)
                    raw = decryptLocalGuardWasm(result.bodyBytes);
                if(raw == null || raw.length <= 4)
                    return null;
                Log.d(TAG, "ntk_viewer_quic_bridge_decoded_guard_wasm url=" + url
                        + ",encryptedBytes=" + result.bodyBytes.length
                        + ",plainBytes=" + raw.length);
                return guardModuleBridgeObject(raw, true).toString();
            } catch (Exception e) {
                return null;
            }
        }

        private String cachedGuardModuleResponse(String url) {
            if(!isNtkAdGuardModuleUrl(url))
                return null;
            try {
                String version = ntkGuardVersionFromUrl(url);
                if(version.length() == 0)
                    return localGuardModuleResponse(url);
                String path = URI.create(url).getPath();
                boolean wasm = "/api/ad/guard-wasm".equals(path);
                java.io.File file = new java.io.File(new java.io.File(context.getCacheDir(), "ntk_guard_cache"),
                        (wasm ? "guard-wasm-" : "guard-js-") + version + (wasm ? ".bin" : ".js"));
                if(!file.isFile() || file.length() <= 0L)
                    return null;
                byte[] bytes = readFileBytes(file);
                if(bytes == null || bytes.length == 0)
                    return null;
                if(wasm) {
                    if(!isHardenedGuardLoader(version)) {
                        byte[] raw = decryptVersionedGuardWasm(version, bytes);
                        if(raw != null && raw.length > 4)
                            bytes = raw;
                        else
                            return null;
                    }
                } else {
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if(!text.startsWith("/*!HARDENED*/"))
                        bytes = stripEncryptedGuardLoader(bytes);
                }
                JSONObject object = guardModuleBridgeObject(bytes, wasm);
                Log.d(TAG, "ntk_viewer_quic_bridge_cached_guard url=" + url
                        + ",wasm=" + wasm
                        + ",len=" + bytes.length);
                return object.toString();
            } catch (Exception e) {
                return null;
            }
        }

        private static String ntkGuardVersionFromUrl(String url) {
            try {
                String query = URI.create(url).getRawQuery();
                if(query == null || query.length() == 0)
                    return "";
                for(String part : query.split("&")) {
                    int equals = part.indexOf('=');
                    if(equals <= 0 || !"v".equals(part.substring(0, equals)))
                        continue;
                    String value = URLDecoder.decode(part.substring(equals + 1), "UTF-8");
                    return value.matches("b\\d{13}-wasm-\\d{13}") ? value : "";
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        private static JSONObject guardModuleBridgeObject(byte[] bytes, boolean wasm) throws Exception {
            JSONObject object = new JSONObject();
            object.put("ok", true);
            object.put("status", 200);
            object.put("statusText", "OK");
            JSONObject headers = new JSONObject();
            headers.put("content-type", wasm ? "application/wasm" : "application/javascript");
            object.put("headers", headers);
            object.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            return object;
        }

        private static byte[] readFileBytes(java.io.File file) {
            if(file == null || !file.isFile())
                return null;
            try(java.io.FileInputStream input = new java.io.FileInputStream(file);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while((read = input.read(buffer)) >= 0) {
                    if(read > 0)
                        output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }

        private static byte[] readAssetBytes(Context context, String assetPath) {
            if(context == null || assetPath == null || assetPath.length() == 0)
                return null;
            try(java.io.InputStream input = context.getAssets().open(assetPath);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while((read = input.read(buffer)) >= 0) {
                    if(read > 0)
                        output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }

        private boolean isHardenedGuardLoader(String version) {
            if(version == null || version.length() == 0)
                return false;
            byte[] bytes = guardLoaderByVersion.get(version);
            if(isHardenedGuardLoader(bytes))
                return true;
            java.io.File file = new java.io.File(new java.io.File(context.getCacheDir(), "ntk_guard_cache"),
                    "guard-js-" + version + ".js");
            return isHardenedGuardLoader(readFileBytes(file));
        }

        private static boolean isHardenedGuardLoader(byte[] bytes) {
            if(bytes == null || bytes.length < 12)
                return false;
            try {
                String prefix = new String(bytes, 0, Math.min(bytes.length, 64),
                        java.nio.charset.StandardCharsets.UTF_8);
                return prefix.startsWith("/*!HARDENED*/");
            } catch (Exception e) {
                return false;
            }
        }

        private static byte[] stripEncryptedGuardLoader(byte[] bytes) {
            if(bytes == null || bytes.length == 0)
                return bytes;
            try {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                int marker = text.indexOf("\n/*!ENCRYPTED*/");
                if(marker <= 0)
                    marker = text.indexOf("/*!ENCRYPTED*/");
                if(marker <= 0)
                    return bytes;
                String stripped = text.substring(0, marker);
                if(!stripped.contains("export{O as initSync,H as default}")
                        && stripped.contains("async function H("))
                    stripped = stripped + "\nexport{O as initSync,H as default};";
                Log.d(TAG, "ntk_viewer_guard_js_stripped encryptedBytes=" + bytes.length
                        + ",plainBytes=" + stripped.length());
                return stripped.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return bytes;
            }
        }

        private void rememberGuardModuleResponse(String url, byte[] bytes) {
            if(bytes == null || bytes.length == 0 || !isNtkAdGuardModuleUrl(url))
                return;
            try {
                String version = ntkGuardVersionFromUrl(url);
                if(version.length() == 0)
                    return;
                String path = URI.create(url).getPath();
                boolean wasm = "/api/ad/guard-wasm".equals(path);
                if(!wasm)
                    guardLoaderByVersion.put(version, bytes);
                java.io.File dir = new java.io.File(context.getCacheDir(), "ntk_guard_cache");
                if(!dir.isDirectory() && !dir.mkdirs())
                    return;
                java.io.File file = new java.io.File(dir,
                        (wasm ? "guard-wasm-" : "guard-js-") + version + (wasm ? ".bin" : ".js"));
                try(java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                    output.write(bytes);
                }
                Log.d(TAG, "ntk_viewer_quic_bridge_guard_cache_write url=" + url
                        + ",wasm=" + wasm
                        + ",len=" + bytes.length);
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_quic_bridge_guard_cache_write_failed " + e);
            }
        }

        private byte[] decryptVersionedGuardWasm(String version, byte[] encrypted) {
            if(version == null || version.length() == 0 || encrypted == null || encrypted.length <= 28)
                return null;
            try {
                byte[] memoryLoader = guardLoaderByVersion.get(version);
                byte[] raw = decryptGuardWasmWithLoader(encrypted, memoryLoader);
                if(raw != null && raw.length > 4) {
                    Log.d(TAG, "ntk_viewer_guard_wasm_decrypted_memory version=" + version
                            + ",encryptedBytes=" + encrypted.length
                            + ",plainBytes=" + raw.length);
                    return raw;
                }
                java.io.File file = new java.io.File(new java.io.File(context.getCacheDir(), "ntk_guard_cache"),
                        "guard-js-" + version + ".js");
                byte[] js = readFileBytes(file);
                if(js == null || js.length == 0)
                    return null;
                raw = decryptGuardWasmWithLoader(encrypted, js);
                if(raw != null && raw.length > 4) {
                    Log.d(TAG, "ntk_viewer_guard_wasm_decrypted_versioned version=" + version
                            + ",encryptedBytes=" + encrypted.length
                            + ",plainBytes=" + raw.length);
                }
                return raw;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_guard_wasm_decrypt_versioned_failed " + e);
                return null;
            }
        }

        private static byte[] decryptGuardWasmWithLoader(byte[] encrypted, byte[] loaderBytes) {
            try {
                if(loaderBytes == null || loaderBytes.length == 0)
                    return null;
                String text = new String(loaderBytes, java.nio.charset.StandardCharsets.UTF_8);
                int marker = text.indexOf("/*!ENCRYPTED*/");
                if(marker < 0)
                    return null;
                String array = "\\[\\[[0-9,\\]\\[\\s-]+\\]\\]";
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?:var|let|const)\\s+\\w+\\s*=\\s*(" + array + ")\\s*,\\s*\\w+\\s*=\\s*(" + array + ")\\s*,")
                        .matcher(text.substring(marker));
                if(!matcher.find())
                    return null;
                int[][] hmacSeed = parseGuardIntMatrix(matcher.group(1), 4, 4);
                int[][] aesSeed = parseGuardIntMatrix(matcher.group(2), 8, 4);
                if(hmacSeed == null || aesSeed == null)
                    return null;
                return decryptGuardWasmWithSeeds(encrypted, hmacSeed, aesSeed);
            } catch (Exception e) {
                return null;
            }
        }

        private static int[][] parseGuardIntMatrix(String text, int rows, int cols) {
            try {
                int[][] out = new int[rows][cols];
                java.util.regex.Matcher rowMatcher = java.util.regex.Pattern
                        .compile("\\[([^\\[\\]]+)\\]")
                        .matcher(text);
                int row = 0;
                while(rowMatcher.find() && row < rows) {
                    String[] parts = rowMatcher.group(1).split(",");
                    if(parts.length != cols)
                        return null;
                    for(int col = 0; col < cols; col++)
                        out[row][col] = Integer.parseInt(parts[col].trim());
                    row++;
                }
                return row == rows ? out : null;
            } catch (Exception e) {
                return null;
            }
        }

        private static byte[] decryptGuardWasmWithSeeds(byte[] encrypted, int[][] hmacSeed, int[][] aesSeed) {
            if(encrypted == null || encrypted.length <= 28)
                return null;
            try {
                byte[] hmacKey = new byte[16];
                for(int t = 0; t < 4; t++) {
                    int mask = (163 + 71 * t) & 255;
                    for(int r = 0; r < 4; r++)
                        hmacKey[4 * t + r] = (byte) (hmacSeed[t][r] ^ mask);
                }
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"));
                byte[] digest = mac.doFinal("ntk-ad-guard-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] aesMaterial = new byte[32];
                int[] order = {3, 0, 6, 1, 4, 7, 2, 5};
                for(int r = 0; r < 8; r++) {
                    int c = order[r];
                    int mask = (93 + 43 * c + 17 * r) & 255;
                    for(int u = 0; u < 4; u++)
                        aesMaterial[4 * c + u] = (byte) (aesSeed[r][u] ^ mask);
                }
                byte[] aesKey = new byte[32];
                for(int i = 0; i < 32; i++)
                    aesKey[i] = (byte) (aesMaterial[i] ^ digest[i]);
                byte[] iv = java.util.Arrays.copyOfRange(encrypted, 0, 12);
                byte[] cipherText = java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(cipherText);
                for(int i = 0; i < plain.length; i++)
                    plain[i] = (byte) (plain[i] ^ digest[i % 32]);
                if(plain.length < 4 || plain[0] != 0 || plain[1] != 97 || plain[2] != 115 || plain[3] != 109)
                    return null;
                return plain;
            } catch (Exception e) {
                return null;
            }
        }

        private static byte[] decryptLocalGuardWasm(byte[] encrypted) {
            if(encrypted == null || encrypted.length <= 28)
                return null;
            try {
                int[][] m = {
                        {67, 62, 10, 179},
                        {80, 118, 157, 198},
                        {25, 249, 170, 187},
                        {8, 198, 69, 199}
                };
                int[][] w = {
                        {88, 44, 175, 28},
                        {98, 138, 111, 85},
                        {56, 215, 132, 3},
                        {19, 180, 160, 203},
                        {26, 132, 95, 121},
                        {221, 5, 155, 93},
                        {162, 202, 184, 47},
                        {224, 107, 103, 135}
                };
                byte[] hmacKey = new byte[16];
                for(int t = 0; t < 4; t++) {
                    int mask = (163 + 71 * t) & 255;
                    for(int r = 0; r < 4; r++)
                        hmacKey[4 * t + r] = (byte) (m[t][r] ^ mask);
                }
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"));
                byte[] digest = mac.doFinal("ntk-ad-guard-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] aesMaterial = new byte[32];
                int[] order = {3, 0, 6, 1, 4, 7, 2, 5};
                for(int r = 0; r < 8; r++) {
                    int c = order[r];
                    int mask = (93 + 43 * c + 17 * r) & 255;
                    for(int u = 0; u < 4; u++)
                        aesMaterial[4 * c + u] = (byte) (w[r][u] ^ mask);
                }
                byte[] aesKey = new byte[32];
                for(int i = 0; i < 32; i++)
                    aesKey[i] = (byte) (aesMaterial[i] ^ digest[i]);
                byte[] iv = java.util.Arrays.copyOfRange(encrypted, 0, 12);
                byte[] cipherText = java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(cipherText);
                for(int i = 0; i < plain.length; i++)
                    plain[i] = (byte) (plain[i] ^ digest[i % 32]);
                if(plain.length < 4 || plain[0] != 0 || plain[1] != 97 || plain[2] != 115 || plain[3] != 109)
                    return null;
                Log.d(TAG, "ntk_viewer_guard_wasm_decrypted encryptedBytes=" + encrypted.length
                        + ",plainBytes=" + plain.length);
                return plain;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_guard_wasm_decrypt_failed " + e);
                return null;
            }
        }

        private static String bridgeCookieHeader(String url, String fallbackCookieHeader,
                                                 Map<String, String> headers, byte[] body) {
            String cookieHeader = mergeCookieHeaders(
                    headerValueIgnoreCase(headers, "cookie"),
                    mergedCookieHeader(url, fallbackCookieHeader));
            String scope = ntkBridgeScopeForRequest(url, headers, body);
            if(scope.length() == 0)
                return cookieHeader;
            return filterNtkAckCookiesForScope(cookieHeader, scope);
        }

        private static void cacheViewerImageApiResponse(String url, Map<String, String> headers,
                                                        byte[] requestBody, NtkQuicFetcher.Result result) {
            if(result == null || result.error != null || result.code < 200 || result.code >= 300
                    || result.body == null || result.body.length() == 0)
                return;
            String scope = ntkBridgeScopeForRequest(url, headers, requestBody);
            if(scope.length() == 0)
                return;
            String kind;
            try {
                String endpointPath = URI.create(url).getPath();
                if(endpointPath == null || !(endpointPath.endsWith("/manhwa-images")
                        || endpointPath.endsWith("/webtoon-images")))
                    return;
                kind = endpointPath.endsWith("/webtoon-images") ? "webtoon" : "manhwa";
            } catch (Exception e) {
                return;
            }
            String workId = "";
            String episodeId = "";
            try {
                JSONObject payload = new JSONObject(new String(requestBody, java.nio.charset.StandardCharsets.UTF_8));
                workId = payload.optString("workId", "");
                episodeId = payload.optString("episodeId", "");
            } catch (Exception ignored) {
            }
            String key = viewerImageCacheKey(kind, workId, episodeId, scope);
            if(key.length() == 0)
                return;
            VIEWER_IMAGE_API_CACHE.put(key, new CachedViewerImages(result.body, System.currentTimeMillis()));
            Log.d(TAG, "ntk_webview_viewer_images_cache_store key=" + key
                    + ",len=" + result.body.length());
        }

        private static String ntkBridgeScopeForRequest(String url, Map<String, String> headers, byte[] body) {
            if(url == null)
                return "";
            String kind = "";
            String apiPath = "";
            try {
                URI uri = URI.create(url);
                apiPath = uri.getPath();
                if(apiPath == null)
                    return "";
                if(apiPath.endsWith("/manhwa-images") || apiPath.endsWith("/webtoon-images"))
                    kind = apiPath.endsWith("/webtoon-images") ? "webtoon" : "manhwa";
            } catch (Exception e) {
                return "";
            }
            try {
                if(body != null && body.length > 0) {
                    JSONObject payload = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    String explicitPath = payload.optString("path", "");
                    if(explicitPath.matches("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$"))
                        return explicitPath;
                }
            } catch (Exception ignored) {
            }
            String referer = headers == null ? "" : headers.get("referer");
            if(referer == null || referer.length() == 0)
                referer = headers == null ? "" : headers.get("Referer");
            if(referer != null && referer.length() > 0) {
                try {
                    String path = URI.create(referer).getPath();
                    if(path != null && path.matches("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$"))
                        return path;
                } catch (Exception ignored) {
                }
            }
            try {
                if(body != null && body.length > 0) {
                    JSONObject payload = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    String workId = payload.optString("workId", "");
                    String episodeId = payload.optString("episodeId", "");
                    if(kind.length() > 0 && workId.length() > 0 && episodeId.length() > 0)
                        return "/" + kind + "/" + workId + "/" + episodeId;
                }
            } catch (Exception ignored) {
            }
            if(referer == null || referer.length() == 0)
                return "";
            try {
                String path = URI.create(referer).getPath();
                return path == null ? "" : path;
            } catch (Exception e) {
                return "";
            }
        }

        private static String filterNtkAckCookiesForScope(String cookieHeader, String scope) {
            if(cookieHeader == null || cookieHeader.length() == 0 || scope == null || scope.length() == 0)
                return cookieHeader == null ? "" : cookieHeader;
            StringBuilder builder = new StringBuilder();
            for(String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                int equals = trimmed.indexOf('=');
                if(equals <= 0)
                    continue;
                String name = trimmed.substring(0, equals).trim();
                String value = trimmed.substring(equals + 1).trim();
                if("ad_ack".equals(name) && !ntkAckCookieMatchesScope(value, scope))
                    continue;
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(trimmed);
            }
            return builder.toString();
        }

        private static void logBridgeRequest(String url, String method, Map<String, String> headers,
                                             byte[] body, String cookieHeader) {
            if(url == null)
                return;
            String lower = url.toLowerCase(Locale.ROOT);
            if(!(lower.contains("/api/webtoon-images") || lower.contains("/api/manhwa-images")
                    || lower.contains("/api/ad/ack") || lower.contains("/api/ad/challenge")
                    || lower.contains("/api/ad/canary") || lower.contains("/api/ad/guard-js")
                    || lower.contains("/api/ad/guard-wasm") || lower.contains("/api/m/i")
                    || lower.contains("/api/m/ev")))
                return;
            String bodyText = "";
            if(body != null && body.length > 0) {
                bodyText = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                if(bodyText.length() > 1400)
                    bodyText = bodyText.substring(0, 1400);
            }
            String origin = headers == null ? "" : String.valueOf(headers.get("origin"));
            String referer = headers == null ? "" : String.valueOf(headers.get("referer"));
            String scope = ntkBridgeScopeForRequest(url, headers, body);
            String adAck = cookieValue(cookieHeader, "ad_ack");
            String adAckC = cookieValue(cookieHeader, "ad_ack_c");
            String adGuardL = cookieValue(cookieHeader, "ad_guard_l");
            Log.d(TAG, "ntk_viewer_quic_bridge_request method=" + method
                    + ",url=" + url
                    + ",origin=" + origin
                    + ",referer=" + referer
                    + ",cookieLen=" + (cookieHeader == null ? 0 : cookieHeader.length())
                    + ",scope=" + scope
                    + ",hasAdAck=" + (adAck.length() > 0)
                    + ",hasAdAckC=" + (adAckC.length() > 0)
                    + ",hasAdGuardL=" + (adGuardL.length() > 0)
                    + ",adAckMatches=" + ntkAckCookieMatchesScope(adAck, scope)
                    + ",adAckCMatches=" + ntkAckCookieMatchesScope(adAckC, scope)
                    + ",body=" + bodyText);
        }

        private static boolean ntkAckCookieMatchesScope(String value, String scope) {
            if(value == null || value.length() == 0 || scope == null || scope.length() == 0)
                return false;
            try {
                String[] parts = value.split("\\.");
                if(parts.length < 1)
                    return false;
                String payload = parts[0];
                int padding = (4 - (payload.length() % 4)) % 4;
                StringBuilder padded = new StringBuilder(payload);
                for(int i = 0; i < padding; i++)
                    padded.append('=');
                byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
                JSONObject json = new JSONObject(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                long exp = json.optLong("exp", 0L);
                if(exp > 0L && exp < System.currentTimeMillis())
                    return false;
                return ntkScopesEqual(json.optString("scope", ""), scope);
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean ntkScopesEqual(String left, String right) {
            if(left == null || right == null || left.length() == 0 || right.length() == 0)
                return false;
            if(left.equals(right))
                return true;
            String encodedLeft = ntkEncodePath(left);
            String encodedRight = ntkEncodePath(right);
            if(encodedLeft.equals(right) || left.equals(encodedRight) || encodedLeft.equals(encodedRight))
                return true;
            return ntkDecodePath(left).equals(ntkDecodePath(right));
        }

        private static String ntkEncodePath(String value) {
            if(value == null || value.length() == 0)
                return "";
            int query = value.indexOf('?');
            String suffix = "";
            String path = value;
            if(query >= 0) {
                suffix = value.substring(query);
                path = value.substring(0, query);
            }
            String[] parts = path.split("/", -1);
            StringBuilder builder = new StringBuilder(path.length() + 16);
            for(int i = 0; i < parts.length; i++) {
                if(i > 0)
                    builder.append('/');
                if(parts[i].length() == 0)
                    continue;
                builder.append(ntkEncodePathSegment(parts[i]));
            }
            return builder.append(suffix).toString();
        }

        private static String ntkEncodePathSegment(String value) {
            try {
                String decoded = value.indexOf('%') >= 0 ? URLDecoder.decode(value, "UTF-8") : value;
                return URLEncoder.encode(decoded, "UTF-8").replace("+", "%20")
                        .replace("%21", "!")
                        .replace("%27", "'")
                        .replace("%28", "(")
                        .replace("%29", ")")
                        .replace("%7E", "~");
            } catch(Exception e) {
                return value;
            }
        }

        private static String ntkDecodePath(String value) {
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch(Exception e) {
                return value == null ? "" : value;
            }
        }

        private static String cookieValue(String cookieHeader, String name) {
            if(cookieHeader == null || name == null || name.length() == 0)
                return "";
            String prefix = name + "=";
            for(String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                if(trimmed.startsWith(prefix))
                    return trimmed.substring(prefix.length());
            }
            return "";
        }

        private static String mergeCookieHeaders(String first, String second) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            appendCookieHeader(values, first);
            appendCookieHeader(values, second);
            StringBuilder builder = new StringBuilder();
            for(Map.Entry<String, String> entry : values.entrySet()) {
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return builder.toString();
        }

        private static void appendCookieHeader(LinkedHashMap<String, String> values, String cookieHeader) {
            if(values == null || cookieHeader == null || cookieHeader.length() == 0)
                return;
            for(String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                int equals = trimmed.indexOf('=');
                if(equals <= 0)
                    continue;
                String name = trimmed.substring(0, equals).trim();
                String value = trimmed.substring(equals + 1).trim();
                if(name.length() > 0)
                    values.put(name, value);
            }
        }

        private static String headerValueIgnoreCase(Map<String, String> headers, String name) {
            if(headers == null || name == null)
                return "";
            for(Map.Entry<String, String> entry : headers.entrySet()) {
                if(entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                    return entry.getValue() == null ? "" : entry.getValue();
            }
            return "";
        }

        private static void removeHeaderIgnoreCase(Map<String, String> headers, String name) {
            if(headers == null || name == null)
                return;
            ArrayList<String> remove = new ArrayList<>();
            for(String key : headers.keySet()) {
                if(key != null && key.equalsIgnoreCase(name))
                    remove.add(key);
            }
            for(String key : remove)
                headers.remove(key);
        }

        private static Map<String, String> parseBridgeHeaders(String headersJson) {
            HashMap<String, String> result = new HashMap<>();
            if(headersJson == null || headersJson.length() == 0)
                return result;
            try {
                JSONObject object = new JSONObject(headersJson);
                java.util.Iterator<String> keys = object.keys();
                while(keys.hasNext()) {
                    String key = keys.next();
                    String value = object.optString(key, null);
                    if(key != null && value != null)
                        result.put(key, value);
                }
            } catch (Exception ignored) {
            }
            return result;
        }

        private static String bridgeError(String message) {
            try {
                JSONObject object = new JSONObject();
                object.put("ok", false);
                object.put("error", message == null ? "unknown" : message);
                return object.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"unknown\"}";
            }
        }
    }

    private static final class ViewerImageResult {
        volatile String body = "";
    }

    private static final class ViewerImageFlight {
        final CountDownLatch done = new CountDownLatch(1);
        final String key;
        final ArrayList<String> urls = new ArrayList<>();
        int waiters = 1;

        ViewerImageFlight(String key) {
            this.key = key == null ? "" : key;
        }
    }

    private static final class CachedViewerImages {
        final String body;
        final long storedAtMs;

        CachedViewerImages(String body, long storedAtMs) {
            this.body = body == null ? "" : body;
            this.storedAtMs = storedAtMs;
        }
    }

    private static final class FetchTask {
        final CountDownLatch done = new CountDownLatch(1);
        final String token;
        final String key;
        final String userAgent;
        final String baseUrl;
        final String path;
        final Map<String, String> headers;
        final CustomHttpClient.RequestGroup requestGroup;
        final boolean highPriority;
        final long enqueuedAt = SystemClock.elapsedRealtime();
        volatile boolean requested = false;
        volatile boolean completed = false;
        volatile boolean navigationStarted = false;
        volatile int navigationErrorRetries = 0;
        volatile int code = 0;
        volatile String body = "";
        volatile long startedOnMainAt = 0L;
        volatile long loadStartedAt = 0L;
        volatile int waiters = 1;

        FetchTask(String token, String key, String userAgent, String baseUrl, String path, Map<String, String> headers,
                  CustomHttpClient.RequestGroup requestGroup, boolean highPriority) {
            this.token = token;
            this.key = key;
            this.userAgent = userAgent;
            this.baseUrl = baseUrl;
            this.path = path;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
            this.requestGroup = requestGroup;
            this.highPriority = highPriority;
        }
    }
}
