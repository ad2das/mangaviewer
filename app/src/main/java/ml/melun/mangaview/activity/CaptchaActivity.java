package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Random;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.showErrorPopup;
import static ml.melun.mangaview.Utils.showPopup;

public class CaptchaActivity extends AppCompatActivity {
    WebView webView;
    public static final int RESULT_CAPTCHA = 15;
    public static final int REQUEST_CAPTCHA = 32;
    String domain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long TURNSTILE_CHECK_DELAY_MS = 500;
    private static final long TURNSTILE_CHECK_INTERVAL_MS = 500;
    private static final long TURNSTILE_MAX_WAIT_MS = 30000;
    public static final String TURNSTILE_AUTO_JS = "(function() {" +
            "   var mc = document.querySelector('.main-content');" +
            "   if(!mc) return JSON.stringify({type:'none'});" +
            "   for(var i=0; i<mc.children.length; i++) {" +
            "       var wrapper = mc.children[i];" +
            "       var host = wrapper.querySelector('div > div');" +
            "       if(host) {" +
            "           var rect = host.getBoundingClientRect();" +
            "           if(rect.width > 50 && rect.height > 50) {" +
            "               var x = rect.left + rect.width * 0.22;" +
            "               var y = rect.top + rect.height/2;" +
            "               return JSON.stringify({type:'iframe', x:x, y:y, w:rect.width*0.45, h:rect.height});" +
            "           }" +
            "       }" +
            "   }" +
            "   return JSON.stringify({type:'none'});" +
            "})();";
    private long pageFinishedTime = 0;
    private boolean isFinishing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.onCreate(savedInstanceState);
        Context context = this;
        setContentView(R.layout.activity_captcha);

        String purl = p.getUrl();

        Intent intent = getIntent();
        String path = intent.getStringExtra("url");
        String url;
        if(path == null)
            url = purl;
        else if(path.startsWith("http://") || path.startsWith("https://"))
            url = path;
        else
            url = purl + path;

        TextView infoText = this.findViewById(R.id.infoText);
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
        webView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
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

        CookieManager cookiem = CookieManager.getInstance();
        cookiem.setAcceptCookie(true);
        cookiem.setAcceptThirdPartyCookies(webView, true);
        cookiem.removeAllCookies(null);

        // WebChromeClient for JS console and alerts
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                android.util.Log.d("CaptchaActivity", "JS Console [" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + "] " + consoleMessage.message());
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                //super.onReceivedError(view, request, error);
                if(request != null && !request.isForMainFrame())
                    return;
                showPopup(context, "오류", "연결에 실패했습니다. URL을 확인해 주세요");
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                getHttpClient().agent = request.getRequestHeaders().get("User-Agent");
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if(isFinishing) return;
                if(readCookiesAndFinish(cookiem, purl))
                    return;

                super.onLoadResource(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if(isFinishing) return;
                if(readCookiesAndFinish(cookiem, purl))
                    return;

                pageFinishedTime = System.currentTimeMillis();
                // Start Turnstile auto-click routine
                startTurnstileAutoClick();

                super.onPageFinished(view, url);
            }
        };

        webView.setWebViewClient(client);

//        webView.setOnTouchListener((view, motionEvent) -> true);

        webView.loadUrl(url);

        infoText.setVisibility(View.GONE);

    }

    private void startTurnstileAutoClick() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(isFinishing || isDestroyed()) return;

                // Check if we've been waiting too long
                if(System.currentTimeMillis() - pageFinishedTime > TURNSTILE_MAX_WAIT_MS) {
                    android.util.Log.w("CaptchaActivity", "Turnstile max wait exceeded");
                    return;
                }

                // Check cookies first
                if(readCookiesAndFinish(CookieManager.getInstance(), p.getUrl())) {
                    return;
                }

                attemptTurnstileClick();

                // Schedule next check
                handler.postDelayed(this, TURNSTILE_CHECK_INTERVAL_MS);
            }
        }, TURNSTILE_CHECK_DELAY_MS);
    }

    private void attemptTurnstileClick() {
        if(webView == null) return;

        webView.evaluateJavascript(TURNSTILE_AUTO_JS, result -> {
            android.util.Log.d("CaptchaActivity", "Turnstile check result: " + result);
            if(result == null || result.equals("null")) return;

            try {
                String clean = result.replaceAll("^\"|\"$", "").replace("\\\"", "\"");
                org.json.JSONObject obj = new org.json.JSONObject(clean);
                String type = obj.optString("type");

                if("iframe".equals(type)) {
                    final float x = (float) obj.getDouble("x");
                    final float y = (float) obj.getDouble("y");
                    final float w = (float) obj.optDouble("w", 60);
                    final float h = (float) obj.optDouble("h", 60);
                    android.util.Log.d("CaptchaActivity", "Turnstile iframe found at: " + x + "," + y + " size:" + w + "x" + h);

                    webView.post(() -> simulateTouch(webView, x, y, w, h));
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

    private boolean readCookiesAndFinish(CookieManager cookiem, String purl){
        if(isFinishing) return false;
        try {
            String cookieStr = cookiem.getCookie(purl);
            if(cookieStr == null || cookieStr.length() == 0)
                return false;

            boolean hasClearance = false;
            for (String s : cookieStr.split("; ")) {
                int eq = s.indexOf("=");
                if(eq <= 0)
                    continue;
                String k = s.substring(0, eq);
                String v = s.substring(eq + 1);
                getHttpClient().setCookie(k, v);
                if("cf_clearance".equals(k))
                    hasClearance = true;
            }

            if(hasClearance) {
                isFinishing = true;
                handler.removeCallbacksAndMessages(null);
                Intent resultIntent = new Intent();
                setResult(RESULT_CAPTCHA, resultIntent);
                finish();
                return true;
            }
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
        //destroy webview
        ((ConstraintLayout) findViewById(R.id.captchaContainer)).removeAllViews();
        if(webView != null) {
            webView.clearHistory();
            webView.clearCache(true);
            webView.destroy();
        }
    }

    @Override
    public void finish() {
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        super.finish();
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
