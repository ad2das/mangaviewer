package ml.melun.mangaview.mangaview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import okhttp3.Response;

final class NtkWebViewFallbackManager {
    private static final String TAG = "ViewerPerf";
    private static final long WEBVIEW_LOAD_TIMEOUT_MS = 10_000L;
    private static final long CALLER_WAIT_TIMEOUT_MS = 15_000L;
    private static final Object INSTANCE_LOCK = new Object();
    private static NtkWebViewFallbackManager instance;

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
            if(instance == null)
                instance = new NtkWebViewFallbackManager(context);
            return instance;
        }
    }

    Response fetch(String userAgent, String baseUrl, String path, Map<String, String> headers) {
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null)
            return null;
        FetchTask task;
        boolean reused = false;
        String key = fetchKey(baseUrl, path);
        synchronized (lock) {
            task = inFlight.get(key);
            if(task == null) {
                task = new FetchTask(String.valueOf(nextToken++), key, userAgent, baseUrl, path, headers);
                inFlight.put(key, task);
                queue.add(task);
                startNextLocked();
            } else {
                task.waiters++;
                reused = true;
            }
        }
        try {
            if(!task.done.await(CALLER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancelIfStillActive(task);
                return null;
            }
            if(task.code <= 0 || task.body == null || task.body.length() == 0)
                return null;
            return CustomHttpClient.responseFromWebViewFetch(baseUrl, path,
                    "{\"code\":" + task.code + ",\"body\":" + jsonQuote(task.body) + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelIfStillActive(task);
            return null;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        } finally {
            if(reused)
                ViewerWarmupManager.logMetric("ntk_webview_reused", 1);
        }
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
        settings.setUserAgentString(userAgent);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new Bridge(), "NtkBridge");
        webView.setWebViewClient(new WebViewClient() {
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
                task.requested = true;
                task.loadStartedAt = SystemClock.elapsedRealtime();
                if(shouldNavigateDocument(task.path))
                    view.evaluateJavascript(buildDocumentHtmlScript(task.token), null);
                else
                    view.evaluateJavascript(CustomHttpClient.buildNtkWebViewFetchScript(task.path, task.headers, task.token), null);
            }
        });
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
        return path != null && (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"));
    }

    private static boolean isFinishedDocumentUrl(String url, String baseUrl, String path) {
        if(url == null || baseUrl == null || path == null)
            return false;
        String expected = baseUrl + path;
        return url.equals(expected) || url.startsWith(expected + "?") || url.startsWith(expected + "#");
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
        return "(function(){try{"
                + "var html=document.documentElement?document.documentElement.outerHTML:(document.body?document.body.innerHTML:'');"
                + "window.NtkBridge.onFetchResult(" + quotedToken + ",JSON.stringify({code:200,body:html||''}));"
                + "}catch(e){window.NtkBridge.onFetchResult(" + quotedToken + ",JSON.stringify({code:0,error:String(e)}));}})()";
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
        final long enqueuedAt = SystemClock.elapsedRealtime();
        volatile boolean requested = false;
        volatile boolean completed = false;
        volatile int code = 0;
        volatile String body = "";
        volatile long startedOnMainAt = 0L;
        volatile long loadStartedAt = 0L;
        volatile int waiters = 1;

        FetchTask(String token, String key, String userAgent, String baseUrl, String path, Map<String, String> headers) {
            this.token = token;
            this.key = key;
            this.userAgent = userAgent;
            this.baseUrl = baseUrl;
            this.path = path;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }
    }
}
