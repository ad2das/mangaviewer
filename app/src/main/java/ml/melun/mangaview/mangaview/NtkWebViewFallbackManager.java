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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Object INSTANCE_LOCK = new Object();
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){"
            + "if(window.__ntkViewerQuicBridgeInstalled||!window.NtkQuicBridge)return;"
            + "window.__ntkViewerQuicBridgeInstalled=1;"
            + "function parseUrl(u){try{return new URL(u,location.href);}catch(e){return null;}}"
            + "function shouldBridge(u,m){var x=parseUrl(u);if(!x||x.protocol!=='https:')return false;var h=x.hostname.toLowerCase();if(!(h==='sbxh3.com'||h.slice(-10)==='.sbxh3.com'))return false;return String(m||'GET').toUpperCase()!=='GET';}"
            + "function textBase64(s){return btoa(unescape(encodeURIComponent(s||'')));}"
            + "function bodyBase64(b){try{if(b==null)return '';if(typeof b==='string')return textBase64(b);if(window.URLSearchParams&&b instanceof URLSearchParams)return textBase64(b.toString());if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}return textBase64(String(b));}catch(e){return '';}}"
            + "function bodyBase64Async(b){try{if(b&&window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return bodyBase64(a);});if(b&&window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return bodyBase64(a);});}catch(e){}return Promise.resolve(bodyBase64(b));}"
            + "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}"
            + "function collectHeaders(input,init){var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;}"
            + "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';}catch(e){}return h;}"
            + "var nativeFetch=window.fetch;if(nativeFetch){window.fetch=function(input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';var method=(init&&init.method)||(input&&input.method)||'GET';if(!shouldBridge(url,method))return nativeFetch.apply(this,arguments);return new Promise(function(resolve,reject){try{var absolute=new URL(url,location.href).href;var hasInitBody=init&&Object.prototype.hasOwnProperty.call(init,'body');var bodyArg=hasInitBody?init.body:((input&&window.Request&&input instanceof Request)?input:null);bodyBase64Async(bodyArg).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addDefaultHeaders(collectHeaders(input,init))),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));}catch(e){reject(e);}},reject);}catch(e){reject(e);}});};}"
            + "var xhrOpen=window.XMLHttpRequest&&XMLHttpRequest.prototype.open;var xhrSend=window.XMLHttpRequest&&XMLHttpRequest.prototype.send;var xhrSetHeader=window.XMLHttpRequest&&XMLHttpRequest.prototype.setRequestHeader;if(xhrOpen&&xhrSend){XMLHttpRequest.prototype.open=function(m,u,a,user,pw){this.__ntkq={method:m||'GET',url:u||'',headers:{}};return xhrOpen.apply(this,arguments);};XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);return xhrSetHeader?xhrSetHeader.apply(this,arguments):undefined;};XMLHttpRequest.prototype.send=function(body){var meta=this.__ntkq;if(!meta||!shouldBridge(meta.url,meta.method))return xhrSend.apply(this,arguments);var xhr=this;setTimeout(function(){try{var raw=window.NtkQuicBridge.request(new URL(meta.url,location.href).href,String(meta.method),JSON.stringify(addDefaultHeaders(meta.headers||{})),bodyBase64(body));var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');var headers=res.headers||{},headerText='';Object.keys(headers).forEach(function(k){headerText+=k+': '+headers[k]+'\\r\\n';});var arr=bytesFromBase64(res.bodyBase64||''),response=arr;if(!xhr.responseType||xhr.responseType==='text'){var bin='';for(var i=0;i<arr.length;i++)bin+=String.fromCharCode(arr[i]);response=decodeURIComponent(escape(bin));}Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});Object.defineProperty(xhr,'status',{configurable:true,get:function(){return res.status||200;}});Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return res.statusText||'OK';}});Object.defineProperty(xhr,'response',{configurable:true,get:function(){return response;}});Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return typeof response==='string'?response:'';}});xhr.getAllResponseHeaders=function(){return headerText;};xhr.getResponseHeader=function(n){var l=String(n||'').toLowerCase();for(var k in headers){if(k.toLowerCase()===l)return headers[k];}return null;};['readystatechange','load','loadend'].forEach(function(n){var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);});}catch(e){var ev=new Event('error');xhr.dispatchEvent(ev);if(typeof xhr.onerror==='function')xhr.onerror.call(xhr,ev);}},0);};}"
            + "var nativeBeacon=navigator.sendBeacon;if(nativeBeacon){navigator.sendBeacon=function(url,data){if(!shouldBridge(url,'POST'))return nativeBeacon.apply(this,arguments);try{var absolute=new URL(url,location.href).href;bodyBase64Async(data).then(function(body64){try{window.NtkQuicBridge.request(absolute,'POST','{}',body64);}catch(e){}});return true;}catch(e){return false;}};}"
            + "function rearmAck(reason){try{var p=location.pathname||'';if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(p))window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:reason,scope:p}}));}catch(e){}}"
            + "var rearmCount=0,rearmTimer=setInterval(function(){rearmAck('native-bridge-ready');if(++rearmCount>=10)clearInterval(rearmTimer);},250);setTimeout(function(){rearmAck('native-bridge-ready');},0);"
            + "})();";
    private static WeakReference<NtkWebViewFallbackManager> instanceRef;

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
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null)
            return null;
        if(requestGroup != null && requestGroup.isCancelled())
            return null;
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
        try {
            if(!awaitTask(task, requestGroup)) {
                return null;
            }
            if(task.code <= 0 || task.body == null || task.body.length() == 0)
                return null;
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
                                           String workId, String episodeId, String imagesToken) {
        ArrayList<String> urls = new ArrayList<>();
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null
                || kind == null || workId == null || episodeId == null || imagesToken == null)
            return urls;
        CountDownLatch done = new CountDownLatch(1);
        ViewerImageResult result = new ViewerImageResult();
        mainHandler.post(() -> fetchViewerImageUrlsOnMain(userAgent, baseUrl, path, headers, kind,
                workId, episodeId, imagesToken, result, done));
        try {
            if(!done.await(65, TimeUnit.SECONDS))
                return urls;
            if(result.body == null || result.body.length() == 0)
                return urls;
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_webview_viewer_images body=" + result.body.substring(0, Math.min(400, result.body.length())));
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

    private void fetchViewerImageUrlsOnMain(String userAgent, String baseUrl, String path,
                                            Map<String, String> headers, String kind,
                                            String workId, String episodeId, String imagesToken,
                                            ViewerImageResult result, CountDownLatch done) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            done.countDown();
            return;
        }
        final WebView view = new WebView(context);
        final boolean[] finished = {false};
        final boolean[] requested = {false};
        Runnable finish = () -> {
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
        };
        try {
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setUserAgentString(userAgent);
            CookieManager.getInstance().setAcceptCookie(true);
            view.addJavascriptInterface(new ViewerImageBridge(result, finish), "NtkViewerBridge");
            if(NtkQuicFetcher.isAvailable())
                view.addJavascriptInterface(new NtkQuicBridge(userAgent), "NtkQuicBridge");
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if(requested[0] || finished[0] || !isFinishedDocumentUrl(url, baseUrl, path))
                        return;
                    requested[0] = true;
                    mainHandler.postDelayed(() -> {
                        if(!finished[0])
                            view.evaluateJavascript(buildViewerImageFetchScript(path, kind, workId,
                                    episodeId, imagesToken), null);
                    }, 150L);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if(request != null && request.isForMainFrame() && !finished[0])
                        finish.run();
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    WebResourceResponse response = interceptViewerQuicRequest(userAgent, request);
                    return response == null ? super.shouldInterceptRequest(view, request) : response;
                }
            });
            Activity activity = MainApplication.currentActivity;
            if(activity != null && !activity.isFinishing() && activity.getWindow() != null) {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                view.setAlpha(1.0f);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        Math.max(360, decor.getWidth()), Math.max(520, decor.getHeight() / 2),
                        Gravity.BOTTOM | Gravity.LEFT);
                decor.addView(view, 0, params);
            }
            view.loadUrl(baseUrl + path, webViewHeaders(headers));
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 1_200L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 2_500L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 5_000L);
            scheduleViewerImageFetch(view, finished, baseUrl, path, kind, workId, episodeId, imagesToken, 9_000L);
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
            view.evaluateJavascript(buildViewerImageFetchScript(path, kind, workId,
                    episodeId, imagesToken), null);
        }, delayMs);
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
            task.startedOnMainAt = SystemClock.elapsedRealtime();
            task.requested = false;
            if(shouldNavigateDocument(task.path)) {
                webView.loadUrl(task.baseUrl + task.path, webViewHeaders(task.headers));
                boolean episodeDocument = isNtkEpisodeDocumentPath(task.path);
                if(!episodeDocument)
                    mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, false), 1500L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, true), 4000L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, true), 8000L);
                if(episodeDocument)
                    mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task, true), 14000L);
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
            return;
        }
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(userAgent);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new Bridge(), "NtkBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if(Log.isLoggable(TAG, Log.DEBUG))
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
                    requestDocumentHtmlOnMain(task, false);
                else if(!shouldNavigateDocument(task.path))
                    view.evaluateJavascript(CustomHttpClient.buildNtkWebViewFetchScript(task.path, task.headers, task.token), null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                boolean mainFrame = request != null && request.isForMainFrame();
                if(Log.isLoggable(TAG, Log.DEBUG) && mainFrame) {
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
        if(task == null || task.completed || task.requested || webView == null)
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
        task.requested = true;
        task.loadStartedAt = SystemClock.elapsedRealtime();
        webView.evaluateJavascript(stopLoading && isNtkEpisodeDocumentPath(task.path)
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
            finishOnMain(task, code, body, code <= 0);
        });
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

    private static String buildViewerImageFetchScript(String path, String kind, String workId,
                                                      String episodeId, String imagesToken) {
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        return "(function(){var scope=" + jsonQuote(path) + ",kind=" + jsonQuote(kind)
                + ",workId=" + jsonQuote(workId) + ",episodeId=" + jsonQuote(episodeId)
                + ",token=" + jsonQuote(imagesToken) + ",endpoint=" + jsonQuote(endpoint) + ";"
                + "var sent=false;"
                + "if(window.__ntkViewerImageFetchLock===scope)return;window.__ntkViewerImageFetchLock=scope;"
                + "function send(o){if(sent)return;sent=true;try{if(window.__ntkViewerImageFetchLock===scope)delete window.__ntkViewerImageFetchLock;}catch(e){}try{window.NtkViewerBridge.onViewerImages(JSON.stringify(o));}catch(e){}}"
                + "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function b64(bytes){var s='';for(var i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}"
                + "function cookie(name){var m=document.cookie.match(new RegExp('(?:^|;\\\\s*)'+name+'=([^;]*)'));return m?decodeURIComponent(m[1]):'';}"
                + "async function hmac(key,msg){var enc=new TextEncoder();var k=await crypto.subtle.importKey('raw',enc.encode(key),{name:'HMAC',hash:'SHA-256'},false,['sign']);return b64(new Uint8Array(await crypto.subtle.sign('HMAC',k,enc.encode(msg))));}"
                + "async function nv(){var v=cookie('nv');if(!v||(v.split('.')[0]||'').length<40){await fetch('/api/nv-issue',{method:'POST',credentials:'same-origin',cache:'no-store'}).catch(function(){});v=cookie('nv');}return (!v||(v.split('.')[0]||'').length<40)?'':v;}"
                + "function acked(){try{if(window.__ntk_ad_ack_scope===scope)return true;var l=window.__ntk_ad_ack_last;if(l&&l.scope===scope)return true;}catch(e){}return false;}"
                + "function rearm(){try{window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:kind+'-native-403',scope:scope}}));}catch(e){}}"
                + "function waitAck(ms){return new Promise(function(resolve){if(acked())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.scope===scope)||acked())finish(true);}var to=setTimeout(function(){finish(acked());},ms);var iv=setInterval(function(){if(acked())finish(true);},120);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function api(){var v=await nv();if(!v)return{status:401,body:{error:'missing session'}};var n=new Uint8Array(24);crypto.getRandomValues(n);var nonce=b64(n);var proof=await hmac(v,token+'.'+nonce+'.'+navigator.userAgent);var r=await fetch(endpoint,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'content-type':'application/json','x-images-client':'viewer-v1'},body:JSON.stringify({workId:workId,episodeId:episodeId,token:token,nonce:nonce,proof:proof})});var b=await r.json().catch(function(){return {};});return{status:r.status,body:b};}"
                + "function decode64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
                + "function markAck(){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true}}));}catch(e){}}"
                + "async function bridgeReq(url,method,body){var abs=new URL(url,location.href).href,h={'content-type':'application/json','accept':'application/json','origin':location.origin,'referer':location.href};if(window.NtkQuicBridge){var raw=window.NtkQuicBridge.request(abs,method,JSON.stringify(h),body?b64(new TextEncoder().encode(JSON.stringify(body))):'');var o=JSON.parse(raw||'{}');if(!o.ok)throw new Error(o.error||'bridge failed');var text=decode64(o.bodyBase64||''),json={};try{json=JSON.parse(text||'{}');}catch(e){}return{status:o.status||0,body:json,text:text};}var opt={method:method,credentials:'same-origin',cache:'no-store',headers:h};if(body)opt.body=JSON.stringify(body);var r=await fetch(abs,opt);var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(e){}return{status:r.status,body:j,text:t};}"
                + "async function directAck(){try{var cRes=await bridgeReq('/api/ad/challenge','POST',{path:scope});var cBody=cRes.body||{};if(cRes.status!==200||!cBody.ok)return false;var ch=cBody.challenge||{},ct=ch.challengeToken||ch.token||'',imps=ch.impressionUrls||[],i,u;if(!ct)return false;for(i=0;i<imps.length;i++){u=imps[i];if(!u)continue;try{await bridgeReq(u,'GET',null);}catch(e){try{await fetch(new URL(u,location.href).href,{credentials:'include',cache:'no-store',mode:'no-cors'});}catch(_){}}}try{await bridgeReq('/api/ad/canary','POST',{adAckCanary:true,challengeToken:ct,token:ct,path:scope});}catch(e){}var aRes=await bridgeReq('/api/ad/ack','POST',{challengeToken:ct,token:ct,total:ch.slotCount||4,visible:ch.minSeen||2,path:scope,td:0,tp:''});var a=aRes.body||{},ok=aRes.status===200&&(a.ok||a.acked||a.status==='ok'||a.status==='acked');if(ok){markAck();return true;}return false;}catch(e){return false;}}"
                + "function extractAckState(){try{var ls={},ss={},i,k;for(i=0;i<localStorage.length;i++){k=localStorage.key(i);if(k)ls[k]=localStorage.getItem(k);}for(i=0;i<sessionStorage.length;i++){k=sessionStorage.key(i);if(k)ss[k]=sessionStorage.getItem(k);}return JSON.stringify({cookies:document.cookie,local:ls,session:ss,ackScope:(function(){try{return window.__ntk_ad_ack_scope;}catch(e){return null;}})(),ntkVars:(function(){try{var o={};for(var p in window)if(p.indexOf('__ntk')===0)try{o[p]=String(window[p]);}catch(e){}return o;}catch(e){return{};}})(),ua:navigator.userAgent,ts:Date.now()});}catch(e){return JSON.stringify({error:String(e)});}}"
                + "(async function(){try{for(var i=0;i<20;i++){if(document.body)break;await sleep(100);}var deadline=Date.now()+18000,r=await api(),armed=false,didDirect=false;while(Date.now()<deadline&&r&&r.status===403&&r.body&&r.body.error==='ad_ack_required'){try{window.NtkViewerBridge.onAckState(extractAckState());}catch(e){}if(!didDirect){didDirect=true;if(await directAck()){r=await api();continue;}}if(!acked()&&!armed){armed=true;rearm();}await waitAck(Math.min(1800,Math.max(0,deadline-Date.now())));await sleep(180);r=await api();}send({code:r?r.status:0,body:r?r.body:{error:'timeout'}});}catch(e){send({code:0,error:String(e)});}})();"
                + "})()";
    }

    private WebResourceResponse interceptViewerQuicRequest(String userAgent, WebResourceRequest request) {
        if(request == null || request.getUrl() == null || !NtkQuicFetcher.isAvailable())
            return null;
        String method = request.getMethod();
        String url = request.getUrl().toString();
        if(method == null || !"GET".equalsIgnoreCase(method) || !isSbxhHttpsUrl(url))
            return null;
        try {
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                    webViewCookieHeader(url), request.getRequestHeaders(),
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

    private static boolean isSbxhHttpsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if(!"https".equalsIgnoreCase(scheme) || host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("sbxh3.com") || host.endsWith(".sbxh3.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static String webViewCookieHeader(String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
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

        ViewerImageBridge(ViewerImageResult result, Runnable finish) {
            this.result = result;
            this.finish = finish;
        }

        @JavascriptInterface
        public void onViewerImages(String value) {
            result.body = value == null ? "" : value;
            finish.run();
        }

        @JavascriptInterface
        public void onAckState(String value) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_ack_state=" + (value == null ? "" : value));
        }
    }

    private static final class NtkQuicBridge {
        private final String userAgent;

        NtkQuicBridge(String userAgent) {
            this.userAgent = userAgent;
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(!NtkQuicFetcher.isAvailable() || !isSbxhHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                byte[] body = bodyBase64 == null || bodyBase64.length() == 0
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(MainApplication.appContext,
                        url, userAgent, webViewCookieHeader(url), headers, method, body, 15000L);
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
                if(url != null && url.contains("/api/ad/") && body != null && body.length > 0) {
                    try {
                        String bodyStr = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                        Log.w(TAG, "ntk_bridge_ad_body url=" + url + " body=" + bodyStr.substring(0, Math.min(800, bodyStr.length())));
                    } catch(Exception ignored) {}
                }
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
