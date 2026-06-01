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
import android.webkit.JavascriptInterface;
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

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class NtkWebViewFallbackManager {
    private static final String TAG = "ViewerPerf";
    private static final long WEBVIEW_LOAD_TIMEOUT_MS = 22_000L;
    private static final long CALLER_WAIT_TIMEOUT_MS = 30_000L;
    private static final long DOCUMENT_READY_WAIT_MS = 18_000L;
    private static final long PRIORITY_WOLF_DOCUMENT_READY_WAIT_MS = 2_500L;
    private static final long PRIORITY_WOLF_LOAD_TIMEOUT_MS = 6_000L;
    private static final long VIEWER_IMAGE_CACHE_TTL_MS = 45_000L;
    private static final Object INSTANCE_LOCK = new Object();
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){"
            + "if(window.__ntkViewerQuicBridgeInstalled||!window.NtkQuicBridge)return;"
            + "window.__ntkViewerQuicBridgeInstalled=1;"
            + "function parseUrl(u){try{return new URL(u,location.href);}catch(e){return null;}}"
            + "function ntkRootHost(){var h=(location.hostname||'').toLowerCase();return h.indexOf('www.')===0?h.slice(4):h;}"
            + "function hostMatchesRoot(h){h=String(h||'').toLowerCase();if(h.indexOf('www.')===0)h=h.slice(4);var r=ntkRootHost();return !!r&&(h===r||h.slice(-(r.length+1))==='.'+r);}"
            + "function shouldBridge(u,m){var x=parseUrl(u);if(!x||x.protocol!=='https:')return false;if(!hostMatchesRoot(x.hostname))return false;return String(m||'GET').toUpperCase()!=='GET';}"
            + "function textBase64(s){return btoa(unescape(encodeURIComponent(s||'')));}"
            + "function bodyBase64(b){try{if(b==null)return '';if(typeof b==='string')return textBase64(b);if(window.URLSearchParams&&b instanceof URLSearchParams)return textBase64(b.toString());if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}return textBase64(String(b));}catch(e){return '';}}"
            + "function bodyBase64Async(b){try{if(b&&window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return bodyBase64(a);});if(b&&window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return bodyBase64(a);});}catch(e){}return Promise.resolve(bodyBase64(b));}"
            + "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}"
            + "function textFromBase64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
            + "function noteAck(url,res,body64){try{if(String(url||'').indexOf('/api/ad/ack')<0)return;var req={},body={};try{req=JSON.parse(textFromBase64(body64)||'{}');}catch(e){}try{body=JSON.parse(textFromBase64(res&&res.bodyBase64)||'{}');}catch(e){}if((res.status||0)!==200||!(body.ok||body.acked||body.status==='ok'||body.status==='acked'))return;var p=req.path||location.pathname||'';window.__ntk_ad_ack_scope=p;window.__ntk_ad_ack_last={scope:p,ts:Date.now(),bridge:true};window.__ntk_ad_ack_tp=req.tp||'';try{window.NtkViewerBridge.onAckProof(req.tp||'');}catch(e){}window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:p,bridge:true,tp:req.tp||''}}));}catch(e){}}"
            + "function collectHeaders(input,init){var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;}"
            + "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';}catch(e){}return h;}"
            + "var nativeFetch=window.fetch;if(nativeFetch){window.fetch=function(input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';var method=(init&&init.method)||(input&&input.method)||'GET';if(!shouldBridge(url,method))return nativeFetch.apply(this,arguments);return new Promise(function(resolve,reject){try{var absolute=new URL(url,location.href).href;var hasInitBody=init&&Object.prototype.hasOwnProperty.call(init,'body');var bodyArg=hasInitBody?init.body:((input&&window.Request&&input instanceof Request)?input:null);bodyBase64Async(bodyArg).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addDefaultHeaders(collectHeaders(input,init))),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));}catch(e){reject(e);}},reject);}catch(e){reject(e);}});};}"
            + "var xhrOpen=window.XMLHttpRequest&&XMLHttpRequest.prototype.open;var xhrSend=window.XMLHttpRequest&&XMLHttpRequest.prototype.send;var xhrSetHeader=window.XMLHttpRequest&&XMLHttpRequest.prototype.setRequestHeader;if(xhrOpen&&xhrSend){XMLHttpRequest.prototype.open=function(m,u,a,user,pw){this.__ntkq={method:m||'GET',url:u||'',headers:{}};return xhrOpen.apply(this,arguments);};XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);return xhrSetHeader?xhrSetHeader.apply(this,arguments):undefined;};XMLHttpRequest.prototype.send=function(body){var meta=this.__ntkq;if(!meta||!shouldBridge(meta.url,meta.method))return xhrSend.apply(this,arguments);var xhr=this;setTimeout(function(){try{var absolute=new URL(meta.url,location.href).href,body64=bodyBase64(body);var raw=window.NtkQuicBridge.request(absolute,String(meta.method),JSON.stringify(addDefaultHeaders(meta.headers||{})),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);var headers=res.headers||{},headerText='';Object.keys(headers).forEach(function(k){headerText+=k+': '+headers[k]+'\\r\\n';});var arr=bytesFromBase64(res.bodyBase64||''),response=arr;if(!xhr.responseType||xhr.responseType==='text'){var bin='';for(var i=0;i<arr.length;i++)bin+=String.fromCharCode(arr[i]);response=decodeURIComponent(escape(bin));}Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});Object.defineProperty(xhr,'status',{configurable:true,get:function(){return res.status||200;}});Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return res.statusText||'OK';}});Object.defineProperty(xhr,'response',{configurable:true,get:function(){return response;}});Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return typeof response==='string'?response:'';}});xhr.getAllResponseHeaders=function(){return headerText;};xhr.getResponseHeader=function(n){var l=String(n||'').toLowerCase();for(var k in headers){if(k.toLowerCase()===l)return headers[k];}return null;};['readystatechange','load','loadend'].forEach(function(n){var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);});}catch(e){var ev=new Event('error');xhr.dispatchEvent(ev);if(typeof xhr.onerror==='function')xhr.onerror.call(xhr,ev);}},0);};}"
            + "var nativeBeacon=navigator.sendBeacon;if(nativeBeacon){navigator.sendBeacon=function(url,data){if(!shouldBridge(url,'POST'))return nativeBeacon.apply(this,arguments);try{var absolute=new URL(url,location.href).href;bodyBase64Async(data).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,'POST','{}',body64);noteAck(absolute,JSON.parse(raw||'{}'),body64);}catch(e){}});return true;}catch(e){return false;}};}"
            + "function rearmAck(reason){try{var p=location.pathname||'';if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(p))window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:reason,scope:p}}));}catch(e){}}"
            + "var rearmCount=0,rearmTimer=setInterval(function(){rearmAck('native-bridge-ready');if(++rearmCount>=10)clearInterval(rearmTimer);},250);setTimeout(function(){rearmAck('native-bridge-ready');},0);"
            + "})();";
    private static WeakReference<NtkWebViewFallbackManager> instanceRef;
    private static final Map<String, CachedViewerImages> VIEWER_IMAGE_API_CACHE = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<FetchTask> queue = new ArrayDeque<>();
    private final Map<String, FetchTask> inFlight = new HashMap<>();

    private WebView webView;
    private FetchTask activeTask;
    private long nextToken = 1L;

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
                task = new FetchTask(String.valueOf(nextToken++), key, userAgent, baseUrl, path, headers,
                        requestGroup != null && requestGroup.prioritizesWebViewFallback());
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
        ArrayList<String> urls = new ArrayList<>();
        appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
        if(urls.size() > 0)
            return urls;
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null
                || kind == null || workId == null || episodeId == null || imagesToken == null)
            return urls;
        CountDownLatch done = new CountDownLatch(1);
        ViewerImageResult result = new ViewerImageResult();
        mainHandler.post(() -> fetchViewerImageUrlsOnMain(userAgent, baseUrl, path, headers, kind,
                workId, episodeId, imagesToken, fallbackCookieHeader, result, done));
        try {
            long deadline = SystemClock.elapsedRealtime() + 65_000L;
            while(!done.await(120L, TimeUnit.MILLISECONDS)) {
                appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
                if(urls.size() > 0)
                    return urls;
                if(SystemClock.elapsedRealtime() >= deadline)
                    return urls;
            }
            appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
            if(urls.size() > 0)
                return urls;
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

    ArrayList<String> cachedViewerImageUrls(String kind, String workId, String episodeId, String path) {
        ArrayList<String> urls = new ArrayList<>();
        appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
        return urls;
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
                                            String fallbackCookieHeader, ViewerImageResult result,
                                            CountDownLatch done) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            done.countDown();
            return;
        }
        final WebView view = new WebView(context);
        final String fallbackCookies = fallbackCookieHeader == null ? "" : fallbackCookieHeader;
        final boolean[] finished = {false};
        final boolean[] requested = {false};
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
                done.countDown();
            }
        };
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
            if(NtkQuicFetcher.isAvailable())
                view.addJavascriptInterface(new NtkQuicBridge(userAgent, fallbackCookies), "NtkQuicBridge");
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if(requested[0] || finished[0] || !isFinishedDocumentUrl(url, baseUrl, path))
                        return;
                    requested[0] = true;
                    mainHandler.post(() -> evaluateViewerImageFetchScript(view, finished, baseUrl, path,
                            kind, workId, episodeId, imagesToken));
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if(request != null && request.isForMainFrame() && !finished[0])
                        finish.run();
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
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
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1,
                        Gravity.BOTTOM | Gravity.LEFT);
                decor.addView(view, 0, params);
            }
            view.loadDataWithBaseURL(baseUrl + path,
                    "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                    "text/html", "UTF-8", null);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 120L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 700L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 1_800L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 3_800L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 7_000L);
            mainHandler.postDelayed(finish, 64_000L);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            finish.run();
        }
    }

    private void scheduleViewerImageFetch(WebView view, boolean[] finished, String baseUrl, String path,
                                          String kind, String workId, String episodeId,
                                          String imagesToken, long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || view == null)
                return;
            String currentUrl = view.getUrl();
            if(!isFinishedDocumentUrl(currentUrl, baseUrl, path))
                return;
            evaluateViewerImageFetchScript(view, finished, baseUrl, path, kind, workId,
                    episodeId, imagesToken);
        }, delayMs);
    }

    private void evaluateViewerImageFetchScript(WebView view, boolean[] finished, String baseUrl, String path,
                                                String kind, String workId, String episodeId,
                                                String imagesToken) {
        if(finished[0] || view == null)
            return;
        try {
            view.stopLoading();
        } catch (Exception ignored) {
        }
        view.evaluateJavascript(buildViewerImageFetchScript(baseUrl, path, kind, workId,
                episodeId, imagesToken), null);
    }

    static long webViewLoadTimeoutMsForTest(boolean highPriority, boolean wolfDocument) {
        return webViewLoadTimeoutMs(highPriority, wolfDocument);
    }

    private static boolean shouldStopWaitingForCaller(boolean requestCancelled, long now, long deadline) {
        return requestCancelled || now >= deadline;
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
        } while(task != null && task.completed);
        if(task == null)
            return;
        activeTask = task;
        final FetchTask nextTask = task;
        mainHandler.post(() -> beginOnMain(nextTask));
    }

    private void beginOnMain(FetchTask task) {
        if(Looper.myLooper() != Looper.getMainLooper())
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
                webView.loadUrl(task.baseUrl + task.path, webViewHeaders(task.headers));
                boolean episodeDocument = isNtkEpisodeDocumentPath(task.path);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, false, false), 1500L);
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
            webView.addJavascriptInterface(new NtkQuicBridge(userAgent, ""), "NtkQuicBridge");
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
                if(task == null || task.completed || task.requested)
                    return;
                if(shouldNavigateDocument(task.path) && !isFinishedDocumentUrl(url, task.baseUrl, task.path))
                    return;
                if(shouldNavigateDocument(task.path) && !isNtkEpisodeDocumentPath(task.path))
                    requestDocumentHtmlOnMain(task, false, false);
                else if(!shouldNavigateDocument(task.path))
                    view.evaluateJavascript(CustomHttpClient.buildNtkWebViewFetchScript(task.path, task.headers, task.token), null);
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
            }
        });
    }

    private void requestDocumentHtmlOnMain(FetchTask task, boolean stopLoading) {
        requestDocumentHtmlOnMain(task, stopLoading, false);
    }

    private void requestDocumentHtmlOnMain(FetchTask task, boolean stopLoading, boolean immediate) {
        if(task == null || task.completed || webView == null)
            return;
        boolean episodeDocument = isNtkEpisodeDocumentPath(task.path);
        if(task.requested && (!episodeDocument || !immediate))
            return;
        String currentUrl = webView.getUrl();
        if(!isFinishedDocumentUrl(currentUrl, task.baseUrl, task.path))
            return;
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
            boolean unusableEpisodeDocument = isUnusableNtkEpisodeDocumentResult(task.path, body);
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
            finishOnMain(task, code, body, code <= 0);
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
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1,
                    Gravity.BOTTOM | Gravity.LEFT);
            decor.addView(webView, 0, params);
            Log.d(TAG, "ntk_webview_attached");
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private void timeoutOnMain(FetchTask task) {
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
            startNextLocked();
        }
        task.done.countDown();
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

    private static String fetchKey(String baseUrl, String path) {
        return (baseUrl == null ? "" : baseUrl) + (path == null ? "" : path);
    }

    private static boolean shouldNavigateDocument(String path) {
        return path != null && (path.startsWith("/webtoon/")
                || path.startsWith("/manhwa/")
                || CustomHttpClient.isWolfEpisodeDocumentPath(path));
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
        return webViewLoadTimeoutMs(task.highPriority, CustomHttpClient.isWolfEpisodeDocumentPath(task.path));
    }

    private static long webViewLoadTimeoutMs(boolean highPriority, boolean wolfDocument) {
        return highPriority && wolfDocument ? PRIORITY_WOLF_LOAD_TIMEOUT_MS : WEBVIEW_LOAD_TIMEOUT_MS;
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
        return "(function(){var token=" + quotedToken + ";var started=Date.now();"
                + "function html(){return document.documentElement?document.documentElement.outerHTML:(document.body?document.body.innerHTML:'');}"
                + "function lower(v){return (v||'').toLowerCase();}"
                + "function emptyDoc(v){var b=document.body;return !v||v.length<160||(!document.querySelector('a[href],img,script[src],link[href]')&&(!b||!(b.innerText||'').trim()));}"
                + "function webviewError(v){v=lower(v);return v.indexOf('webpage not available')>=0||v.indexOf('net::err_')>=0||v.indexOf('err_connection_reset')>=0||v.indexOf('err_name_not_resolved')>=0||v.indexOf('err_timed_out')>=0||v.indexOf('error code 522')>=0||v.indexOf('connection timed out')>=0;}"
                + "function challenge(v){v=lower(v);return v.indexOf('just a moment')>=0||v.indexOf('challenges.cloudflare.com')>=0||v.indexOf('cf-challenge')>=0||v.indexOf('cf_chl')>=0||v.indexOf('cf-mitigated')>=0||v.indexOf('turnstile')>=0;}"
                + "function ntkViewerProps(v){v=lower(v);return v.indexOf('\"imagestoken\"')>=0&&v.indexOf('\"imagemetas\"')>=0;}"
                + "function ntkRendered(){try{var episode=/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(location.pathname||'');var ns=document.querySelectorAll('img[src],img[data-src],img[data-original],link[rel=preload][as=image][href]');for(var i=0;i<ns.length;i++){var s=(ns[i].getAttribute('src')||ns[i].getAttribute('data-src')||ns[i].getAttribute('data-original')||ns[i].getAttribute('href')||'').toLowerCase();if(s.indexOf('/webtoon_uploads/')>=0||s.indexOf('/manhwa_uploads/')>=0||s.indexOf('/comic_uploads/')>=0||s.indexOf('/blacktoon/episodes/')>=0)return true;}if(episode)return false;if(document.querySelector('.vw-main,.vw-imgs,.viewer-content,.toon-view,div.image-view,section.webtoon-body'))return true;var as=document.querySelectorAll('a[href]'),episodeLinks=0;for(var j=0;j<as.length;j++){var h=(as[j].getAttribute('href')||'').toLowerCase();if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(h)&&h.indexOf('%5b')<0&&h.indexOf('page-')<0)episodeLinks++;}return episodeLinks>0&&Date.now()-started>1200;}catch(e){return false;}}"
                + "function ntkShell(v){v=lower(v);return (v.indexOf('/_next/static/')>=0||v.indexOf('self.__next_f')>=0||v.indexOf('id=\"__next\"')>=0||v.indexOf(\"id='__next'\")>=0)&&(v.indexOf('%5bsourceworkid%5d')>=0||v.indexOf('[sourceworkid]')>=0||v.indexOf('%5bviewid%5d')>=0||v.indexOf('[viewid]')>=0||v.indexOf('next-route-announcer')>=0||v.indexOf('app-router-announcer')>=0)&&!ntkRendered();}"
                + "function ntkErrorFallback(v){v=lower(v);return /^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(location.pathname||'')&&(v.indexOf('next_http_error_fallback')>=0||v.indexOf('id=\"__next_error__')>=0||v.indexOf(\"id='__next_error__\")>=0)&&(v.indexOf('/_next/static/')>=0||v.indexOf('self.__next_f')>=0||v.indexOf('id=\"__next\"')>=0||v.indexOf(\"id='__next'\")>=0);}"
                + "function send(code,body){window.NtkBridge.onFetchResult(token,JSON.stringify({code:code,body:body||''}));}"
                + "function check(){try{var v=html();"
                + "if((emptyDoc(v)||webviewError(v))&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,250);return;}"
                + "if(challenge(v)&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,350);return;}"
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

    private static String buildViewerImageFetchScript(String baseUrl, String path, String kind, String workId,
                                                      String episodeId, String imagesToken) {
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        return "(function(){var base=" + jsonQuote(baseUrl) + ",scope=" + jsonQuote(path) + ",kind=" + jsonQuote(kind)
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
                + "async function hmac(key,msg){var enc=new TextEncoder();if(window.crypto&&crypto.subtle&&crypto.subtle.importKey){try{var k=await crypto.subtle.importKey('raw',enc.encode(key),{name:'HMAC',hash:'SHA-256'},false,['sign']);return b64(new Uint8Array(await crypto.subtle.sign('HMAC',k,enc.encode(msg))));}catch(e){}}if(window.NtkQuicBridge){var v=String(window.NtkQuicBridge.hmacSha256(key,msg)||'');if(v)return v;}throw new Error('hmac unavailable');}"
                + "async function nv(){var v=cookie('nv');if(!v||(v.split('.')[0]||'').length<40){try{await bridgeReq('/api/nv-issue','POST',null);}catch(e){}v=cookie('nv');}if(!v||(v.split('.')[0]||'').length<40){await fetch(abs('/api/nv-issue'),{method:'POST',credentials:'same-origin',cache:'no-store'}).catch(function(){});v=cookie('nv');}return (!v||(v.split('.')[0]||'').length<40)?'':v;}"
                + "function acked(){try{if(window.__ntk_ad_ack_scope===scope)return true;var l=window.__ntk_ad_ack_last;if(l&&l.scope===scope)return true;}catch(e){}return false;}"
                + "function rearm(){try{window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:kind+'-native-403',scope:scope}}));}catch(e){}}"
                + "function waitAck(ms){return new Promise(function(resolve){if(acked())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.scope===scope)||acked())finish(true);}var to=setTimeout(function(){finish(acked());},ms);var iv=setInterval(function(){if(acked())finish(true);},120);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function api(){var v=await nv();if(!v)return{status:401,body:{error:'missing session'}};var n=new Uint8Array(24);crypto.getRandomValues(n);var nonce=b64(n);var proof=await hmac(v,token+'.'+nonce+'.'+navigator.userAgent),body={workId:workId,episodeId:episodeId,token:token,nonce:nonce,proof:proof};return await bridgeReq(endpoint,'POST',body,{'x-images-client':'viewer-v1'});}"
                + "function decode64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
                + "function markAck(){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true}}));}catch(e){}}"
                + "async function bridgeReq(url,method,body,extra){var absolute=abs(url),h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()};if(extra){Object.keys(extra).forEach(function(k){h[k]=extra[k];});}if(window.NtkQuicBridge){var raw=window.NtkQuicBridge.request(absolute,method,JSON.stringify(h),body?b64(new TextEncoder().encode(JSON.stringify(body))):'');var o=JSON.parse(raw||'{}');if(!o.ok)throw new Error(o.error||'bridge failed');var text=decode64(o.bodyBase64||''),json={};try{json=JSON.parse(text||'{}');}catch(e){}return{status:o.status||0,body:json,text:text};}var opt={method:method,credentials:'same-origin',cache:'no-store',headers:h};if(body)opt.body=JSON.stringify(body);var r=await fetch(absolute,opt);var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(e){}return{status:r.status,body:j,text:t};}"
                + "function fireImp(u){try{fetch(abs(u),{credentials:'same-origin',cache:'no-store',mode:'no-cors'}).catch(function(){});}catch(e){}}"
                + "async function directAck(){try{var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}if(!c.body.challenge)return false;var ch=c.body.challenge,token=ch.token||'',imps=ch.impressionUrls||[];imps.forEach(fireImp);await sleep(180);await bridgeReq('/api/ad/canary','POST',{adAckCanary:true,challengeToken:token,token:token,path:scope});var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:ch.slotCount||4,visible:ch.minSeen||2,path:scope,td:0,tp:''});var b=a&&a.body?a.body:{};if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckError:String(e)}));}catch(_){}}return false;}"
                + "function extractAckState(){try{var ls={},ss={},i,k;for(i=0;i<localStorage.length;i++){k=localStorage.key(i);if(k)ls[k]=localStorage.getItem(k);}for(i=0;i<sessionStorage.length;i++){k=sessionStorage.key(i);if(k)ss[k]=sessionStorage.getItem(k);}return JSON.stringify({cookies:docCookie(),local:ls,session:ss,ackScope:(function(){try{return window.__ntk_ad_ack_scope;}catch(e){return null;}})(),ntkVars:(function(){try{var o={};for(var p in window)if(p.indexOf('__ntk')===0)try{o[p]=String(window[p]);}catch(e){}return o;}catch(e){return{};}})(),ua:navigator.userAgent,ts:Date.now()});}catch(e){return JSON.stringify({error:String(e)});}}"
                + "(async function(){try{for(var i=0;i<20;i++){if(document.body)break;await sleep(100);}var deadline=Date.now()+18000,armed=false;if(!acked()){armed=true;if(!(await directAck()))rearm();}var r=await api();while(Date.now()<deadline&&r&&r.status===403){try{window.NtkViewerBridge.onAckState(extractAckState());}catch(e){}if(!acked()&&!armed){armed=true;if(!(await directAck()))rearm();}await waitAck(Math.min(1800,Math.max(0,deadline-Date.now())));await sleep(180);r=await api();}send({code:r?r.status:0,body:r?r.body:{error:'timeout'}});}catch(e){send({code:0,error:String(e)});}})();"
                + "})()";
    }

    private WebResourceResponse interceptViewerQuicRequest(String userAgent, String fallbackCookieHeader,
                                                           WebResourceRequest request) {
        if(request == null || request.getUrl() == null || !NtkQuicFetcher.isAvailable())
            return null;
        String method = request.getMethod();
        String url = request.getUrl().toString();
        if(method == null || !"GET".equalsIgnoreCase(method) || !isNtkProtectedHttpsUrl(url))
            return null;
        try {
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                    mergedCookieHeader(url, fallbackCookieHeader), request.getRequestHeaders(),
                    request.isForMainFrame() ? 15000L : 10000L);
            if(result == null || result.error != null || result.code < 200 || result.code >= 500
                    || result.bodyBytes == null || result.bodyBytes.length == 0)
                return null;
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
            return new WebResourceResponse(responseMimeType(result.contentType()),
                    responseEncoding(result.contentType()), result.code,
                    result.code >= 400 ? "Cloudflare" : "OK",
                    responseHeaders(result.headers), new ByteArrayInputStream(responseBytes));
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_viewer_quic_intercept_failed url=" + url, e);
            return null;
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

    private static String currentNtkRootHost() {
        try {
            String root = MainApplication.p == null
                    ? CustomHttpClient.NTK_WEBTOON_URL
                    : MainApplication.p.getNtkResolvedRoot();
            URI uri = URI.create(root);
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return normalizeHost("sbxh3.com");
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
            if(key == null || "set-cookie".equalsIgnoreCase(key))
                continue;
            List<String> values = source.get(key);
            if(values != null && values.size() > 0 && values.get(0) != null)
                result.put(key, values.get(0));
        }
        return result;
    }

    private static String viewerImageCacheKey(String kind, String workId, String episodeId, String path) {
        if(kind == null || workId == null || episodeId == null || path == null
                || kind.length() == 0 || workId.length() == 0 || episodeId.length() == 0
                || path.length() == 0)
            return "";
        return kind + ':' + workId + ':' + episodeId + ':' + path;
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
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_ack_state=" + (value == null ? "" : value));
        }

        @JavascriptInterface
        public void onAckProof(String value) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_ack_proof=" + (value == null ? "" : value));
        }
    }

    private static final class NtkQuicBridge {
        private final String userAgent;
        private final String fallbackCookieHeader;

        NtkQuicBridge(String userAgent, String fallbackCookieHeader) {
            this.userAgent = userAgent;
            this.fallbackCookieHeader = fallbackCookieHeader == null ? "" : fallbackCookieHeader;
        }

        @JavascriptInterface
        public String cookie(String url, String name) {
            String value = cookieValue(webViewCookieHeader(url), name);
            return value.length() > 0 ? value : cookieValue(fallbackCookieHeader, name);
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
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(!NtkQuicFetcher.isAvailable() || !isNtkProtectedHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                byte[] body = bodyBase64 == null || bodyBase64.length() == 0
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(MainApplication.appContext,
                        url, userAgent, bridgeCookieHeader(url, fallbackCookieHeader, headers, body), headers,
                        method, body, 15000L);
                if(result == null)
                    return bridgeError("empty result");
                if(result.error != null)
                    return bridgeError(String.valueOf(result.error));
                applyWebViewCookies(url, result);
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_viewer_quic_bridge method=" + method
                            + ",code=" + result.code
                            + ",len=" + result.bodyBytes.length
                            + ",url=" + url);
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

        private static String bridgeCookieHeader(String url, String fallbackCookieHeader,
                                                 Map<String, String> headers, byte[] body) {
            String cookieHeader = mergedCookieHeader(url, fallbackCookieHeader);
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
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(path == null || !(path.endsWith("/manhwa-images") || path.endsWith("/webtoon-images")))
                    return "";
                kind = path.endsWith("/webtoon-images") ? "webtoon" : "manhwa";
            } catch (Exception e) {
                return "";
            }
            try {
                if(body != null && body.length > 0) {
                    JSONObject payload = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    String workId = payload.optString("workId", "");
                    String episodeId = payload.optString("episodeId", "");
                    if(workId.length() > 0 && episodeId.length() > 0)
                        return "/" + kind + "/" + workId + "/" + episodeId;
                }
            } catch (Exception ignored) {
            }
            String referer = headers == null ? "" : headers.get("referer");
            if(referer == null || referer.length() == 0)
                referer = headers == null ? "" : headers.get("Referer");
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
                if(("ad_ack".equals(name) || "ad_ack_c".equals(name))
                        && !ntkAckCookieMatchesScope(value, scope))
                    continue;
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(trimmed);
            }
            return builder.toString();
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
                return scope.equals(json.optString("scope", ""));
            } catch (Exception e) {
                return false;
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
        final boolean highPriority;
        final long enqueuedAt = SystemClock.elapsedRealtime();
        volatile boolean requested = false;
        volatile boolean completed = false;
        volatile int code = 0;
        volatile String body = "";
        volatile long startedOnMainAt = 0L;
        volatile long loadStartedAt = 0L;
        volatile int waiters = 1;

        FetchTask(String token, String key, String userAgent, String baseUrl, String path, Map<String, String> headers,
                  boolean highPriority) {
            this.token = token;
            this.key = key;
            this.userAgent = userAgent;
            this.baseUrl = baseUrl;
            this.path = path;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
            this.highPriority = highPriority;
        }
    }
}
