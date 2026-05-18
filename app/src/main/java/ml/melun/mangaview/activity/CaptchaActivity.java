package ml.melun.mangaview.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.runtime.AppDispatchers;
import okhttp3.Response;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.showErrorPopup;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_COMIC_URL;
import static ml.melun.mangaview.mangaview.CustomHttpClient.NTK_WEBTOON_URL;

public class CaptchaActivity extends AppCompatActivity {
    WebView webView;
    private TextView infoText;
    public static final int RESULT_CAPTCHA = 15;
    public static final int REQUEST_CAPTCHA = 32;
    String domain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long TURNSTILE_CHECK_DELAY_MS = 0;
    private static final long TURNSTILE_CHECK_INTERVAL_MS = 500;
    private static final long TURNSTILE_MAX_WAIT_MS = 30000;
    private static final long COOKIE_READ_THROTTLE_MS = 350;
    private static final String NTK_ACCESS_VERIFY_PATH = "/api/manhwa-list?page=1&pageSize=1&withTotal=1";
    public static final String SHADOW_HOOK_JS = "(function(){" +
            "if(window.__sh)return;" +
            "window.__sh=1;" +
            "var o=Element.prototype.attachShadow;" +
            "Element.prototype.attachShadow=function(i){" +
            "var s=o.call(this,i);" +
            "this.__sr=s;" +
            "try{" +
            "var mo=new MutationObserver(function(ms){" +
            "ms.forEach(function(m){" +
            "m.addedNodes.forEach(function(n){" +
            "if(n.tagName==='INPUT'&&n.type==='checkbox'){" +
            "try{n.focus();" +
            "var e1=new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0.5});" +
            "var e2=new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0});" +
            "n.dispatchEvent(e1);n.dispatchEvent(e2);" +
            "}catch(ex){}" +
            "console.log('__TURNSTILE_CB__');" +
            "}" +
            "});" +
            "});" +
            "});" +
            "mo.observe(s,{childList:true,subtree:true});" +
            "}catch(e){}" +
            "return s;" +
            "};" +
            "})();";
    public static final String TURNSTILE_AUTO_JS = "(function(){" +
            "function findCheckbox(){" +
            "var all=document.querySelectorAll('*');" +
            "for(var i=0;i<all.length;i++){" +
            "var el=all[i];" +
            "if(el.__sr){" +
            "var inp=el.__sr.querySelector('input[type=\"checkbox\"]');" +
            "if(inp)return inp;" +
            "}" +
            "}" +
            "return null;" +
            "}" +
            "var cb=findCheckbox();" +
            "if(cb){" +
            "try{cb.focus();" +
            "var e1=new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0.5});" +
            "var e2=new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0});" +
            "cb.dispatchEvent(e1);cb.dispatchEvent(e2);" +
            "}catch(ex){}" +
            "return JSON.stringify({type:'jsclick'});" +
            "}" +
            "var iframe=document.querySelector('iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]');" +
            "if(iframe){" +
            "var rect=iframe.getBoundingClientRect();" +
            "if(rect.width>10&&rect.height>10){" +
            "return JSON.stringify({type:'iframe',x:rect.left+rect.width/2,y:rect.top+rect.height/2,w:rect.width,h:rect.height});" +
            "}" +
            "}" +
            "var turnstileDiv=document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"]');" +
            "if(turnstileDiv){" +
            "var rect=turnstileDiv.getBoundingClientRect();" +
            "if(rect.width>10&&rect.height>10){" +
            "return JSON.stringify({type:'div',x:rect.left+rect.width/2,y:rect.top+rect.height/2,w:rect.width,h:rect.height});" +
            "}" +
            "}" +
            "var host=(location.hostname||'').toLowerCase();" +
            "var text=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ');" +
            "if((host.indexOf('ntk')>=0||host.indexOf('sbxh')>=0)&&text.length>200&&(text.indexOf('NEWTOKI')>=0||text.indexOf('실시간 웹툰 랭킹')>=0||text.indexOf('웹툰')>=0&&text.indexOf('만화')>=0))" +
            "return JSON.stringify({type:'normal'});" +
            "var mc=document.querySelector('.main-content');" +
            "if(mc){" +
            "for(var i=0;i<mc.children.length;i++){" +
            "var wrapper=mc.children[i];" +
            "var host=wrapper.querySelector('div > div');" +
            "if(host){" +
            "var rect=host.getBoundingClientRect();" +
            "if(rect.width>50&&rect.height>50){" +
            "var x=rect.left+rect.width*0.22;" +
            "var y=rect.top+rect.height/2;" +
            "return JSON.stringify({type:'iframe',x:x,y:y,w:rect.width*0.45,h:rect.height});" +
            "}" +
            "}" +
            "}" +
            "}" +
            "return JSON.stringify({type:'none'});" +
            "})();";
    private long pageFinishedTime = 0;
    private long lastAttemptTime = 0;
    private static final long FIRST_CLICK_DELAY_MS = 0;
    private static final long RETRY_MIN_MS = 1000;
    private static final long RETRY_MAX_MS = 3000;
    private boolean isFirstAttempt = true;
    private boolean isFinishing = false;
    private Set<String> initialClearanceValues = new HashSet<>();
    private int normalNtkPageCount = 0;
    private boolean turnstileAutoClickStarted = false;
    private boolean accessVerificationInFlight = false;
    private long lastTurnstileTouchAt = 0;
    private final Set<String> rejectedClearanceValues = new HashSet<>();
    private String lastVerificationClearanceValue = null;
    private long lastClearanceVerificationAt = 0;
    private long lastInvalidClearanceReloadAt = 0;
    private long lastCookieReadAt = 0;
    private String captchaLoadUrl;
    private boolean captchaLoadErrorVisible = false;
    private LocalWebViewProxy localWebViewProxy;
    private WebView releasedWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.onCreate(savedInstanceState);
        Context context = this;
        setContentView(R.layout.activity_captcha);
        getHttpClient().setCloudflareCaptchaActive(true);

        String purl = p.getUrl();

        Intent intent = getIntent();
        String url = resolveCaptchaUrl(intent, purl);

        infoText = this.findViewById(R.id.infoText);
        try {
            URL u = new URL(purl);
            domain = u.getHost();
        }catch (MalformedURLException e){
            showErrorPopup(context, "URL 형식이 올바르지 않습니다.", e, true);
        }

        if(purl.contains("http://")){
            showErrorPopup(context, "ip 주소 혹은 잘못된 주소를 사용중입니다. 자동 URL 설정을 사용하거나, 주소를 다시 입력해 주세요", null, false);
        }

        webView = this.findViewById(R.id.captchaWebView);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        captchaLoadUrl = url;
        configureActionButtons(purl);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        // Use real Chrome Mobile UA, remove "wv" indicator
        String realChromeUA = getHttpClient().agent;
        if(realChromeUA == null || realChromeUA.length() == 0) {
            realChromeUA = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
        }
        settings.setUserAgentString(realChromeUA);
        getHttpClient().setUserAgent(settings.getUserAgentString());

        CookieManager cookiem = CookieManager.getInstance();
        cookiem.setAcceptCookie(true);
        cookiem.setAcceptThirdPartyCookies(webView, true);
        initialClearanceValues = readClearanceValues(cookiem, purl, p.getWebtoonUrl(), p.getUrl(), NTK_WEBTOON_URL, NTK_COMIC_URL);
        // Do NOT remove all cookies — previous valid cf_clearance should be preserved
        if(!getHttpClient().hasCloudflareClearance()) {
            getHttpClient().clearCloudflareWebViewCookies(purl, p.getWebtoonUrl(), p.getUrl(), NTK_WEBTOON_URL, NTK_COMIC_URL);
        }

        // WebChromeClient for JS console and alerts
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                android.util.Log.d("CaptchaActivity", "JS Console [" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + "] " + msg);
                if(msg != null && msg.contains("__TURNSTILE_CB__")) {
                    android.util.Log.d("CaptchaActivity", "Turnstile checkbox detected via MutationObserver - triggering click");
                    handler.post(() -> attemptTurnstileClick());
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                android.util.Log.d("CaptchaActivity", "JS Alert: " + message);
                result.confirm();
                return true;
            }
        });

        WebViewClient client = new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                handler.removeCallbacksAndMessages(null);
                hideCaptchaLoadError();
                pageFinishedTime = 0;
                lastAttemptTime = 0;
                lastCookieReadAt = 0;
                normalNtkPageCount = 0;
                turnstileAutoClickStarted = false;
                view.evaluateJavascript(SHADOW_HOOK_JS, null);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                //super.onReceivedError(view, request, error);
                if(request != null && !request.isForMainFrame())
                    return;
                String failingUrl = request != null && request.getUrl() != null ? request.getUrl().toString() : (view == null ? null : view.getUrl());
                if(shouldSuppressNtkLoadErrorPopupForTest(p != null && p.isNtkSite(), failingUrl, purl)) {
                    android.util.Log.d("CaptchaActivity", "Suppressing NTK captcha WebView load error popup: " + failingUrl);
                    showCaptchaLoadError(failingUrl);
                    return;
                }
                showCaptchaLoadError(failingUrl);
                showPopup(context, "오류", "연결에 실패했습니다. URL을 확인해 주세요");
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Do NOT overwrite OkHttp UA with WebView UA here.
                // WebView default UA contains "; wv)" which flags it as WebView to Cloudflare,
                // causing Turnstile to trigger more aggressively.
                // UA is already synchronized via setUserAgentString() during setup.
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if(isFinishing) return;
                if(readCookiesAndFinish(cookiem, purl, url, true))
                    return;

                // Attempt click immediately when resources load (Turnstile iframe appears mid-load)
                // Do NOT wait for pageFinishedTime - iframe loads before onPageFinished
                long now = System.currentTimeMillis();
                long requiredInterval = isFirstAttempt ? FIRST_CLICK_DELAY_MS : (RETRY_MIN_MS + (long)(Math.random() * (RETRY_MAX_MS - RETRY_MIN_MS)));
                if(now - lastAttemptTime > requiredInterval) {
                    attemptTurnstileClick();
                }

                super.onLoadResource(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if(isFinishing) return;
                if(readCookiesAndFinish(cookiem, purl, url))
                    return;

                pageFinishedTime = System.currentTimeMillis();
                // Start Turnstile auto-click routine
                startTurnstileAutoClick();

                super.onPageFinished(view, url);
            }
        };

        webView.setWebViewClient(client);

//        webView.setOnTouchListener((view, motionEvent) -> true);

        android.util.Log.d("CaptchaActivity", "Loading captcha URL: " + url);
        loadCaptchaUrl(url);

        infoText.setVisibility(View.GONE);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String purl = p.getUrl();
        captchaLoadUrl = resolveCaptchaUrl(intent, purl);
        hideCaptchaLoadError();
        clearWebViewProxy();
        if(webView != null)
            loadCaptchaUrl(captchaLoadUrl);
    }

    private String resolveCaptchaUrl(Intent intent, String purl) {
        String path = intent == null ? null : intent.getStringExtra("url");
        if(path == null)
            return purl;
        if(path.startsWith("http://") || path.startsWith("https://"))
            return path;
        return purl + path;
    }

    private void configureActionButtons(String purl) {
        View reload = findViewById(R.id.captchaReload);
        View checkCookie = findViewById(R.id.captchaCheckCookie);
        View pasteCookie = findViewById(R.id.captchaPasteCookie);
        View close = findViewById(R.id.captchaClose);
        if(reload != null)
            reload.setOnClickListener(v -> {
                hideCaptchaLoadError();
                clearWebViewProxy();
                loadCaptchaUrl(captchaLoadUrl);
            });
        if(checkCookie != null)
            checkCookie.setOnClickListener(v -> {
                Toast.makeText(this, "쿠키를 확인합니다.", Toast.LENGTH_SHORT).show();
                readCookiesAndFinish(CookieManager.getInstance(), purl, webView == null ? null : webView.getUrl());
            });
        if(pasteCookie != null)
            pasteCookie.setOnClickListener(v -> pasteClearanceCookie(purl));
        if(close != null)
            close.setOnClickListener(v -> finish());
    }

    private void showCaptchaLoadError(String failingUrl) {
        captchaLoadErrorVisible = true;
        handler.removeCallbacksAndMessages(null);
        if(webView != null)
            webView.setVisibility(View.INVISIBLE);
        if(infoText == null)
            return;
        infoText.setText("사이트에 연결할 수 없습니다.\n네트워크 또는 사이트 차단 상태일 수 있습니다.\n새로고침하거나 닫은 뒤 다른 사이트로 전환하세요.");
        infoText.setVisibility(View.VISIBLE);
    }

    private void hideCaptchaLoadError() {
        captchaLoadErrorVisible = false;
        if(webView != null)
            webView.setVisibility(View.VISIBLE);
        if(infoText != null)
            infoText.setVisibility(View.GONE);
    }

    private void pasteClearanceCookie(String purl) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = clipboard != null
                && clipboard.hasPrimaryClip()
                && clipboard.getPrimaryClip() != null
                && clipboard.getPrimaryClip().getItemCount() > 0
                ? clipboard.getPrimaryClip().getItemAt(0).coerceToText(this)
                : null;
        String clearance = extractCookieValueForTest(text == null ? null : text.toString(), "cf_clearance");
        if(clearance == null || !isValidClearanceValue(clearance)) {
            Toast.makeText(this, "cf_clearance 쿠키를 찾지 못했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        CookieManager manager = CookieManager.getInstance();
        for(String url : cookieReadUrls(purl, webView == null ? null : webView.getUrl())) {
            if(url != null && url.length() > 0)
                manager.setCookie(url, "cf_clearance=" + clearance);
        }
        manager.flush();
        getHttpClient().setCookie("cf_clearance", clearance);
        Toast.makeText(this, "쿠키를 적용했습니다.", Toast.LENGTH_SHORT).show();
        verifyNtkAccessAndFinish(purl, webView == null ? null : webView.getUrl(), clearance);
    }

    private void loadCaptchaUrl(String url) {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) && p != null && p.isNtkSite()) {
            if(loadCaptchaUrlWithProxy(url))
                return;
        }
        webView.loadUrl(url);
        webView.evaluateJavascript(SHADOW_HOOK_JS, null);
    }

    @SuppressLint("RequiresFeature")
    private boolean loadCaptchaUrlWithProxy(String url) {
        try {
            LocalWebViewProxy proxy = LocalWebViewProxy.start();
            localWebViewProxy = proxy;
            int proxyPort = proxy.port();
            ProxyConfig proxyConfig = new ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + proxyPort)
                    .build();
            Executor direct = Runnable::run;
            ProxyController.getInstance().setProxyOverride(proxyConfig, direct, () -> {
                if(isFinishing || isDestroyed() || webView == null || localWebViewProxy != proxy) {
                    proxy.close();
                    return;
                }
                android.util.Log.d("CaptchaActivity", "NTK WebView proxy enabled on port " + proxyPort);
                try {
                    webView.loadUrl(url);
                    webView.evaluateJavascript(SHADOW_HOOK_JS, null);
                } catch (Exception e) {
                    android.util.Log.d("CaptchaActivity", "Failed to load NTK captcha through proxy", e);
                }
            });
            return true;
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "Failed to enable NTK WebView proxy", e);
            return false;
        }
    }

    private void startTurnstileAutoClick() {
        if(turnstileAutoClickStarted)
            return;
        turnstileAutoClickStarted = true;
        isFirstAttempt = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(isFinishing || isDestroyed() || captchaLoadErrorVisible) return;

                // Check if we've been waiting too long
                if(System.currentTimeMillis() - pageFinishedTime > TURNSTILE_MAX_WAIT_MS) {
                    android.util.Log.w("CaptchaActivity", "Turnstile max wait exceeded");
                    return;
                }

                // Check cookies first
                if(readCookiesAndFinish(CookieManager.getInstance(), p.getUrl(), webView == null ? null : webView.getUrl())) {
                    return;
                }

                attemptTurnstileClick();

                // Schedule next check with random jitter for retries
                long nextDelay = isFirstAttempt ? 0 : RETRY_MIN_MS + (long)(Math.random() * (RETRY_MAX_MS - RETRY_MIN_MS));
                handler.postDelayed(this, nextDelay);
            }
        }, TURNSTILE_CHECK_DELAY_MS);
    }

    private void attemptTurnstileClick() {
        if(isFinishing || isDestroyed() || webView == null || captchaLoadErrorVisible) return;

        long now = System.currentTimeMillis();
        long requiredInterval = isFirstAttempt ? FIRST_CLICK_DELAY_MS : (RETRY_MIN_MS + (long)(Math.random() * (RETRY_MAX_MS - RETRY_MIN_MS)));
        if(now - lastAttemptTime < requiredInterval) return;
        lastAttemptTime = now;

        webView.evaluateJavascript(TURNSTILE_AUTO_JS, result -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            android.util.Log.d("CaptchaActivity", "Turnstile check result: " + result);
            if(result == null || result.equals("null")) return;

            try {
                String clean = result.replaceAll("^\"|\"$", "").replace("\\\"", "\"");
                org.json.JSONObject obj = new org.json.JSONObject(clean);
                String type = obj.optString("type");

                if("jsclick".equals(type)) {
                    android.util.Log.d("CaptchaActivity", "Turnstile JS direct click executed via shadow hook");
                    isFirstAttempt = false;
                } else if("iframe".equals(type)) {
                    normalNtkPageCount = 0;
                    final float x = (float) obj.getDouble("x");
                    final float y = (float) obj.getDouble("y");
                    final float w = (float) obj.optDouble("w", 60);
                    final float h = (float) obj.optDouble("h", 60);
                    android.util.Log.d("CaptchaActivity", "Turnstile iframe found at: " + x + "," + y + " size:" + w + "x" + h);

                    webView.post(() -> {
                        if(!isFinishing && !isDestroyed() && webView != null)
                            simulateTouchBurst(webView, x, y, w, h);
                    });
                    isFirstAttempt = false;
                } else if("normal".equals(type)) {
                    isFirstAttempt = false;
                    normalNtkPageCount++;
                    android.util.Log.d("CaptchaActivity", "NTK normal page detected without Turnstile: " + normalNtkPageCount);
                    long elapsed = System.currentTimeMillis() - pageFinishedTime;
                    boolean finished = false;
                    if(normalNtkPageCount >= 1 && elapsed > 400L)
                        finished = readCookiesAndFinish(CookieManager.getInstance(), p.getUrl(), webView == null ? null : webView.getUrl());
                    if(!finished && shouldFinishNormalNtkPageForTest(normalNtkPageCount, elapsed)) {
                        android.util.Log.d("CaptchaActivity", "NTK normal page stable; verifying app access before finishing captcha");
                        verifyNtkAccessAndFinish(p.getUrl(), webView == null ? null : webView.getUrl(), null);
                    }
                } else {
                    isFirstAttempt = false;
                    normalNtkPageCount = 0;
                }
            } catch(Exception e) {
                android.util.Log.e("CaptchaActivity", "Failed to parse turnstile result", e);
            }
        });
    }

    private void simulateTouch(View view, float centerX, float centerY, float width, float height) {
        if(view == null) return;

        Random random = new Random();
        float density = getResources().getDisplayMetrics().density;

        // Convert CSS pixels to physical pixels and apply WebView offset
        int[] location = new int[2];
        view.getLocationOnScreen(location);

        float baseX = location[0] + (centerX * density);
        float baseY = location[1] + (centerY * density);
        float physW = width * density;
        float physH = height * density;

        // Random target within element (not exact center)
        float targetX = baseX + (random.nextFloat() - 0.5f) * physW * 0.5f;
        float targetY = baseY + (random.nextFloat() - 0.5f) * physH * 0.5f;

        // Clamp to view bounds
        int vw = view.getWidth();
        int vh = view.getHeight();
        targetX = Math.max(5, Math.min(vw - 5, targetX));
        targetY = Math.max(5, Math.min(vh - 5, targetY));

        long downTime = SystemClock.uptimeMillis();
        long eventTime = downTime;

        android.util.Log.d("CaptchaActivity", "Simulating touch at local: " + targetX + "," + targetY);

        MotionEvent downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, targetX, targetY, 0);
        downEvent.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(downEvent);
        downEvent.recycle();

        // Random hold time (80-200ms)
        long holdTime = 80 + random.nextInt(121);
        SystemClock.sleep(holdTime);
        eventTime += holdTime;

        MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, targetX, targetY, 0);
        upEvent.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(upEvent);
        upEvent.recycle();

        android.util.Log.d("CaptchaActivity", "Touch completed at: " + targetX + "," + targetY);
    }

    private void simulateTouchBurst(View view, float centerX, float centerY, float width, float height) {
        if(view == null) return;
        long now = System.currentTimeMillis();
        if(now - lastTurnstileTouchAt < 1000L)
            return;
        lastTurnstileTouchAt = now;
        simulateTouchWithMove(view, centerX, centerY, width, height);
        android.util.Log.d("CaptchaActivity", "Turnstile touch sequence completed");
    }

    private void simulateTouchWithMove(View view, float centerX, float centerY, float width, float height) {
        if(view == null) return;
        Random random = new Random();
        float density = getResources().getDisplayMetrics().density;

        // getBoundingClientRect returns CSS pixels relative to WebView viewport
        // dispatchTouchEvent expects view-local physical pixels (0,0 = top-left of view)
        float baseX = centerX * density;
        float baseY = centerY * density;
        float physW = width * density;
        float physH = height * density;

        float targetX = baseX + (random.nextFloat() - 0.5f) * physW * 0.5f;
        float targetY = baseY + (random.nextFloat() - 0.5f) * physH * 0.5f;

        int vw = view.getWidth();
        int vh = view.getHeight();
        targetX = Math.max(5, Math.min(vw - 5, targetX));
        targetY = Math.max(5, Math.min(vh - 5, targetY));

        android.util.Log.d("CaptchaActivity", "Dispatching touch in view-local coords: " + targetX + "," + targetY);

        long downTime = SystemClock.uptimeMillis();
        long eventTime = downTime;

        // DOWN
        MotionEvent downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, targetX, targetY, 0);
        downEvent.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(downEvent);
        downEvent.recycle();

        // Short MOVE (1-3 px drift)
        SystemClock.sleep(10 + random.nextInt(21));
        eventTime += 30;
        float moveX = targetX + (random.nextFloat() - 0.5f) * 3f;
        float moveY = targetY + (random.nextFloat() - 0.5f) * 3f;
        MotionEvent moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, moveX, moveY, 0);
        moveEvent.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(moveEvent);
        moveEvent.recycle();

        // UP
        SystemClock.sleep(20 + random.nextInt(41));
        eventTime += 60;
        MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, targetX, targetY, 0);
        upEvent.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(upEvent);
        upEvent.recycle();
    }

    private boolean readCookiesAndFinish(CookieManager cookiem, String purl, String currentUrl){
        return readCookiesAndFinish(cookiem, purl, currentUrl, false);
    }

    private boolean readCookiesAndFinish(CookieManager cookiem, String purl, String currentUrl, boolean throttle){
        if(isFinishing) return true;
        if(throttle) {
            long now = SystemClock.elapsedRealtime();
            if(now - lastCookieReadAt < COOKIE_READ_THROTTLE_MS)
                return false;
            lastCookieReadAt = now;
        }
        try {
            boolean hasClearance = false;
            final String[] clearanceValue = new String[1];
            for(String cookieUrl : cookieReadUrls(purl, currentUrl)) {
                if(cookieUrl == null || cookieUrl.length() == 0)
                    continue;
                String cookieStr = cookiem.getCookie(cookieUrl);
                if(cookieStr == null || cookieStr.length() == 0)
                    continue;
                final boolean[] foundClearance = { hasClearance };
                forEachCookiePair(cookieStr, (k, v) -> {
                    boolean clearance = "cf_clearance".equalsIgnoreCase(k);
                    if(clearance && !isValidClearanceValue(v))
                        return;
                    if(clearance && rejectedClearanceValues.contains(v))
                        return;
                    getHttpClient().setCookie(k, v);
                    if(clearance) {
                        foundClearance[0] = true;
                        clearanceValue[0] = v;
                    }
                });
                hasClearance = foundClearance[0];
            }
            // Cookie presence alone is not enough: stale WebView clearances can make this
            // activity close while OkHttp still receives the Cloudflare challenge.
            if(hasClearance) {
                long now = System.currentTimeMillis();
                if(accessVerificationInFlight)
                    return false;
                if(clearanceValue[0] != null
                        && clearanceValue[0].equals(lastVerificationClearanceValue)
                        && now - lastClearanceVerificationAt < 5000L)
                    return false;
                cookiem.flush();
                getHttpClient().syncCookiesFromWebView(p.getWebtoonUrl(), true);
                getHttpClient().syncCookiesFromWebView(p.getUrl(), true);
                getHttpClient().syncCookiesFromWebView(purl, true);
                if(currentUrl != null)
                    getHttpClient().syncCookiesFromWebView(currentUrl, true);
                verifyNtkAccessAndFinish(purl, currentUrl, clearanceValue[0]);
            }
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return false;
    }

    private void verifyNtkAccessAndFinish(String purl, String currentUrl, String clearanceValue) {
        if(accessVerificationInFlight || isFinishing)
            return;
        long now = System.currentTimeMillis();
        if(clearanceValue != null
                && clearanceValue.equals(lastVerificationClearanceValue)
                && now - lastClearanceVerificationAt < 5000L)
            return;
        lastVerificationClearanceValue = clearanceValue;
        lastClearanceVerificationAt = now;
        accessVerificationInFlight = true;
        AppDispatchers.runIo(() -> {
            boolean verified = verifyNtkAccess(purl, currentUrl);
            AppDispatchers.runOnMain(() -> {
                accessVerificationInFlight = false;
                if(isFinishing)
                    return;
                if(verified) {
                    android.util.Log.d("CaptchaActivity", "NTK clearance verified by app HTTP client");
                    finishWithVerifiedClearance();
                } else {
                    android.util.Log.d("CaptchaActivity", "NTK clearance failed app HTTP verification; keeping captcha open");
                    if(clearanceValue != null)
                        rejectedClearanceValues.add(clearanceValue);
                    resetInvalidNtkClearanceAndReload(purl, currentUrl);
                }
            });
        });
    }

    private boolean verifyNtkAccess(String purl, String currentUrl) {
        String pagePath = ntkVerificationUrl(purl, currentUrl);
        if(pagePath.length() > 0 && !verifyNtkPath(pagePath))
            return false;
        return verifyNtkPath(NTK_ACCESS_VERIFY_PATH);
    }

    private boolean verifyNtkPath(String path) {
        try {
            Response response = getHttpClient().mget(path, true);
            if(response == null)
                return false;
            int code = response.code();
            String body = getHttpClient().readBody(response);
            return code >= 200 && code < 400 && !getHttpClient().isCloudflareChallengeResponse(code, body);
        } catch(Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK clearance verification request failed", e);
            return false;
        }
    }

    private String ntkVerificationUrl(String purl, String currentUrl) {
        String candidate = ntkPagePath(currentUrl);
        if(candidate.length() == 0)
            candidate = ntkPagePath(purl);
        return candidate;
    }

    private String ntkPagePath(String url) {
        if(url == null || url.length() == 0)
            return "";
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String path = uri.getPath();
            if(path == null || path.length() == 0)
                return "";
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/"))
                return "";
            String query = uri.getEncodedQuery();
            return query == null || query.length() == 0 ? path : path + "?" + query;
        } catch (Exception e) {
            return "";
        }
    }

    private void finishWithVerifiedClearance() {
        String currentWebViewUrl = webView == null ? null : webView.getUrl();
        detachCaptchaWebView();
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        getHttpClient().syncCookiesFromWebView(p.getWebtoonUrl(), true);
        getHttpClient().syncCookiesFromWebView(p.getUrl(), true);
        if(currentWebViewUrl != null)
            getHttpClient().syncCookiesFromWebView(currentWebViewUrl, true);
        getHttpClient().saveClearanceToDisk();
        getHttpClient().markNtkAccessVerified();
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        Intent resultIntent = new Intent();
        setResult(RESULT_CAPTCHA, resultIntent);
        finish();
    }

    private void resetInvalidNtkClearanceAndReload(String purl, String currentUrl) {
        getHttpClient().clearCloudflareWebViewCookies(purl, p.getWebtoonUrl(), p.getUrl(), currentUrl, NTK_WEBTOON_URL, NTK_COMIC_URL);
        if(webView == null || isFinishing)
            return;
        long now = System.currentTimeMillis();
        if(now - lastInvalidClearanceReloadAt < 5000L)
            return;
        lastInvalidClearanceReloadAt = now;
        webView.loadUrl(ntkCaptchaLoadUrl(getHttpClient().getLastCloudflareChallengeUrl(), purl));
    }

    private String ntkCaptchaLoadUrl(String challengeUrl, String purl) {
        if(challengeUrl != null && challengeUrl.length() > 0
                && getHttpClient().isNtkUrl(challengeUrl)
                && !isNtkApiUrl(challengeUrl))
            return challengeUrl;
        if(purl != null && purl.length() > 0 && !isNtkApiUrl(purl))
            return purl;
        String webtoonUrl = p.getWebtoonUrl();
        if(webtoonUrl != null && webtoonUrl.length() > 0 && !isNtkApiUrl(webtoonUrl))
            return webtoonUrl;
        String root = getHttpClient().getUrl();
        if(root != null && root.endsWith("/manhwa"))
            root = root.substring(0, root.length() - 7);
        return root;
    }

    private boolean isNtkApiUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String path = uri.getPath();
            return path != null && path.toLowerCase(java.util.Locale.ROOT).startsWith("/api/");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean shouldSuppressNtkLoadErrorPopupForTest(boolean ntkSite, String requestUrl, String captchaUrl) {
        return ntkSite || isNtkLikeUrlForCaptcha(requestUrl) || isNtkLikeUrlForCaptcha(captchaUrl);
    }

    static boolean shouldFinishNormalNtkPageForTest(int normalPageCount, long elapsedMs) {
        return normalPageCount >= 2 && elapsedMs > 1200L;
    }

    static String extractCookieValueForTest(String text, String cookieName) {
        if(text == null || cookieName == null || cookieName.length() == 0)
            return null;
        final String[] value = new String[1];
        forEachCookiePair(text, (key, cookieValue) -> {
            if(value[0] == null && cookieName.equalsIgnoreCase(key))
                value[0] = cookieValue;
        });
        return value[0];
    }

    private static boolean isNtkLikeUrlForCaptcha(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("://ntk") || lower.contains("://sbxh") || lower.contains("://toonflix");
    }

    private Set<String> readClearanceValues(CookieManager cookiem, String... urls) {
        Set<String> values = new HashSet<>();
        if(cookiem == null || urls == null)
            return values;
        for(String url : urls) {
            if(url == null || url.length() == 0)
                continue;
            String cookieStr = cookiem.getCookie(url);
            if(cookieStr == null || cookieStr.length() == 0)
                continue;
            forEachCookiePair(cookieStr, (key, value) -> {
                if("cf_clearance".equalsIgnoreCase(key) && isValidClearanceValue(value))
                    values.add(value);
            });
        }
        return values;
    }

    private interface CookiePairConsumer {
        void accept(String key, String value);
    }

    private static void forEachCookiePair(String cookieStr, CookiePairConsumer consumer) {
        if(cookieStr == null || consumer == null)
            return;
        int start = 0;
        int length = cookieStr.length();
        while(start < length) {
            int end = nextCookieSeparator(cookieStr, start);
            int eq = cookieStr.indexOf('=', start);
            if(eq > start && eq < end) {
                String key = cookieStr.substring(start, eq).trim();
                String value = cookieStr.substring(eq + 1, end).trim();
                if(key.length() > 0)
                    consumer.accept(key, value);
            }
            start = end + 1;
        }
    }

    private static int nextCookieSeparator(String cookieStr, int start) {
        int end = cookieStr.length();
        int semicolon = cookieStr.indexOf(';', start);
        if(semicolon >= 0 && semicolon < end)
            end = semicolon;
        int newline = cookieStr.indexOf('\n', start);
        if(newline >= 0 && newline < end)
            end = newline;
        int carriageReturn = cookieStr.indexOf('\r', start);
        if(carriageReturn >= 0 && carriageReturn < end)
            end = carriageReturn;
        return end;
    }

    private boolean isValidClearanceValue(String value) {
        if(value == null)
            return false;
        String trimmed = value.trim();
        return trimmed.length() >= 20
                && !"deleted".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed);
    }

    private String[] cookieReadUrls(String purl, String currentUrl) {
        return new String[]{
                currentUrl,
                purl,
                p.getWebtoonUrl(),
                p.getUrl(),
                NTK_WEBTOON_URL,
                NTK_COMIC_URL
        };
    }

    @Override
    protected void onDestroy() {
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        getHttpClient().setCloudflareCaptchaActive(false);
        clearWebViewProxy();
        detachCaptchaWebView();
        super.onDestroy();
        destroyReleasedWebViewLater();
    }

    @Override
    public void finish() {
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        clearWebViewProxy();
        super.finish();
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void detachCaptchaWebView() {
        WebView target = webView;
        if(target == null)
            return;
        webView = null;
        releasedWebView = target;
        try {
            target.stopLoading();
        } catch (Exception ignored) {
        }
        try {
            target.onPause();
            target.pauseTimers();
        } catch (Exception ignored) {
        }
        try {
            target.setWebChromeClient(null);
            target.setWebViewClient(null);
        } catch (Exception ignored) {
        }
        try {
            target.setVisibility(View.GONE);
            ViewGroup parent = (target.getParent() instanceof ViewGroup) ? (ViewGroup) target.getParent() : null;
            if(parent != null)
                parent.removeView(target);
        } catch (Exception ignored) {
        }
        ConstraintLayout container = findViewById(R.id.captchaContainer);
        if(container != null)
            container.removeAllViews();
    }

    private void destroyReleasedWebViewLater() {
        WebView target = releasedWebView;
        releasedWebView = null;
        if(target == null)
            return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                target.clearHistory();
                target.removeAllViews();
                target.destroy();
            } catch (Exception ignored) {
            }
        }, 750L);
    }

    private void clearWebViewProxy() {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {});
            } catch (Exception ignored) {
            }
        }
        if(localWebViewProxy != null) {
            localWebViewProxy.close();
            localWebViewProxy = null;
        }
    }

    private static class LocalWebViewProxy {
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private volatile boolean closed = false;

        static LocalWebViewProxy start() throws Exception {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            LocalWebViewProxy proxy = new LocalWebViewProxy(socket);
            proxy.acceptLoop();
            return proxy;
        }

        private LocalWebViewProxy(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            executor.execute(() -> {
                while(!closed) {
                    try {
                        Socket client = serverSocket.accept();
                        if(!executeProxyTask(() -> handle(client)))
                            closeQuietly(client);
                    } catch (Exception e) {
                        if(!closed)
                            android.util.Log.d("CaptchaActivity", "NTK WebView proxy accept failed", e);
                    }
                }
            });
        }

        private void handle(Socket client) {
            try {
                client.setSoTimeout(15000);
                InputStream input = client.getInputStream();
                OutputStream output = client.getOutputStream();
                String requestLine = readLine(input);
                if(requestLine == null || !requestLine.startsWith("CONNECT ")) {
                    output.write("HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    closeQuietly(client);
                    return;
                }
                String target = requestLine.substring("CONNECT ".length()).split(" ", 2)[0];
                while(true) {
                    String header = readLine(input);
                    if(header == null || header.length() == 0)
                        break;
                }
                String host = target;
                int port = 443;
                int colon = target.lastIndexOf(':');
                if(colon > 0) {
                    host = target.substring(0, colon);
                    port = Integer.parseInt(target.substring(colon + 1));
                }
                Socket upstream = new Socket();
                String connectHost = CustomHttpClient.resolveDirectHostForNtkProxy(host);
                upstream.connect(new InetSocketAddress(InetAddress.getByName(connectHost), port), 10000);
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                pipeBoth(client, upstream);
            } catch (Exception e) {
                if(!closed)
                    android.util.Log.d("CaptchaActivity", "NTK WebView proxy connection failed", e);
                closeQuietly(client);
            }
        }

        private void pipeBoth(Socket a, Socket b) {
            boolean first = executeProxyTask(() -> pipe(a, b));
            boolean second = executeProxyTask(() -> pipe(b, a));
            if(!first || !second) {
                closeQuietly(a);
                closeQuietly(b);
            }
        }

        private boolean executeProxyTask(Runnable task) {
            if(closed || executor.isShutdown())
                return false;
            try {
                executor.execute(task);
                return true;
            } catch (RejectedExecutionException ignored) {
                return false;
            }
        }

        private void pipe(Socket from, Socket to) {
            byte[] buffer = new byte[8192];
            try {
                InputStream input = from.getInputStream();
                OutputStream output = to.getOutputStream();
                int read;
                while((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (Exception ignored) {
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private String readLine(InputStream input) throws Exception {
            StringBuilder builder = new StringBuilder();
            int previous = -1;
            int current;
            while((current = input.read()) != -1) {
                if(previous == '\r' && current == '\n') {
                    builder.setLength(Math.max(0, builder.length() - 1));
                    return builder.toString();
                }
                builder.append((char) current);
                previous = current;
                if(builder.length() > 8192)
                    throw new Exception("Proxy header too long");
            }
            return builder.length() == 0 ? null : builder.toString();
        }

        void close() {
            closed = true;
            closeQuietly(serverSocket);
            executor.shutdownNow();
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            try {
                if(closeable != null)
                    closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
