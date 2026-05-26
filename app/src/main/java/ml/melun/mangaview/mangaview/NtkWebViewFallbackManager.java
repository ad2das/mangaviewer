package ml.melun.mangaview.mangaview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import okhttp3.Response;

final class NtkWebViewFallbackManager {
    private static final String TAG = "ViewerPerf";
    private static final long WEBVIEW_LOAD_TIMEOUT_MS = 22_000L;
    private static final long CALLER_WAIT_TIMEOUT_MS = 30_000L;
    private static final long DOCUMENT_READY_WAIT_MS = 18_000L;
    private static final Object INSTANCE_LOCK = new Object();
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
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task), 250L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task), 700L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task), 1500L);
                mainHandler.postDelayed(() -> requestDocumentHtmlOnMain(task), 4000L);
            } else {
                webView.loadDataWithBaseURL(task.baseUrl, "<!doctype html><html><body></body></html>",
                        "text/html", "UTF-8", null);
            }
            mainHandler.postDelayed(() -> timeoutOnMain(task), WEBVIEW_LOAD_TIMEOUT_MS);
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
                if(shouldNavigateDocument(task.path))
                    requestDocumentHtmlOnMain(task);
                else
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

    private void requestDocumentHtmlOnMain(FetchTask task) {
        if(task == null || task.completed || task.requested || webView == null)
            return;
        String currentUrl = webView.getUrl();
        if(!isFinishedDocumentUrl(currentUrl, task.baseUrl, task.path))
            return;
        task.requested = true;
        task.loadStartedAt = SystemClock.elapsedRealtime();
        webView.evaluateJavascript(buildDocumentHtmlScript(task.token), null);
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

    private static boolean isFinishedDocumentUrl(String url, String baseUrl, String path) {
        if(url == null || baseUrl == null || path == null)
            return false;
        String expected = baseUrl + path;
        return url.equals(expected) || url.startsWith(expected + "?") || url.startsWith(expected + "#");
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

    private static String buildDocumentHtmlScript(String token) {
        String quotedToken = jsonQuote(token);
        String readyWaitMs = String.valueOf(DOCUMENT_READY_WAIT_MS);
        return "(function(){var token=" + quotedToken + ";var started=Date.now();"
                + "function html(){return document.documentElement?document.documentElement.outerHTML:(document.body?document.body.innerHTML:'');}"
                + "function lower(v){return (v||'').toLowerCase();}"
                + "function emptyDoc(v){var b=document.body;return !v||v.length<160||(!document.querySelector('a[href],img,script[src],link[href]')&&(!b||!(b.innerText||'').trim()));}"
                + "function webviewError(v){v=lower(v);return v.indexOf('webpage not available')>=0||v.indexOf('net::err_')>=0||v.indexOf('err_connection_reset')>=0||v.indexOf('err_name_not_resolved')>=0||v.indexOf('err_timed_out')>=0||v.indexOf('error code 522')>=0||v.indexOf('connection timed out')>=0;}"
                + "function challenge(v){v=lower(v);return v.indexOf('just a moment')>=0||v.indexOf('challenges.cloudflare.com')>=0||v.indexOf('cf-challenge')>=0||v.indexOf('cf_chl')>=0||v.indexOf('cf-mitigated')>=0||v.indexOf('turnstile')>=0;}"
                + "function ntkRendered(){try{if(document.querySelector('.vw-main,.vw-imgs,.viewer-content,.toon-view,div.image-view,section.webtoon-body,main[class*=viewer]'))return true;var ns=document.querySelectorAll('img[src],img[data-src],img[data-original],link[rel=preload][as=image][href]');for(var i=0;i<ns.length;i++){var s=(ns[i].getAttribute('src')||ns[i].getAttribute('data-src')||ns[i].getAttribute('data-original')||ns[i].getAttribute('href')||'').toLowerCase();if(s.indexOf('/webtoon_uploads/')>=0||s.indexOf('/manhwa_uploads/')>=0||s.indexOf('/comic_uploads/')>=0||s.indexOf('/blacktoon/episodes/')>=0)return true;}var as=document.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var h=(as[j].getAttribute('href')||'').toLowerCase();if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(h)&&h.indexOf('%5b')<0&&h.indexOf('page-')<0)return true;}return false;}catch(e){return false;}}"
                + "function ntkShell(v){v=lower(v);return (v.indexOf('/_next/static/')>=0||v.indexOf('self.__next_f')>=0||v.indexOf('id=\"__next\"')>=0||v.indexOf(\"id='__next'\")>=0)&&(v.indexOf('%5bsourceworkid%5d')>=0||v.indexOf('[sourceworkid]')>=0||v.indexOf('%5bviewid%5d')>=0||v.indexOf('[viewid]')>=0||v.indexOf('next-route-announcer')>=0||v.indexOf('app-router-announcer')>=0)&&!ntkRendered();}"
                + "function send(code,body){window.NtkBridge.onFetchResult(token,JSON.stringify({code:code,body:body||''}));}"
                + "function check(){try{var v=html();"
                + "if((emptyDoc(v)||webviewError(v))&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,250);return;}"
                + "if(challenge(v)&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,350);return;}"
                + "if(ntkShell(v)&&Date.now()-started<" + readyWaitMs + "){setTimeout(check,300);return;}"
                + "if(emptyDoc(v)||webviewError(v)){send(0,v||'');return;}"
                + "if(challenge(v)){send(403,v);return;}"
                + "if(ntkShell(v)){send(0,v);return;}"
                + "send(200,v);"
                + "}catch(e){send(0,String(e));}}"
                + "check();})()";
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
