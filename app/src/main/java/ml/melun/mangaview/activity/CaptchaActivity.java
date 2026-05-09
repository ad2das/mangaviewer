package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.ConsoleMessage;
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
import java.util.Locale;

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
    String purl;
    String rootUrl;
    String currentUrl;
    CookieManager cookiem;
    boolean requireCloudflareClearance;

    private static final String TURNSTILE_AUTO_JS = "javascript:(function autoSolve(){" +
            "function clickTurnstile(){" +
            "var iframes=document.querySelectorAll('iframe[src*=\"challenges.cloudflare.com\"]');" +
            "for(var i=0;i<iframes.length;i++){" +
            "var iframe=iframes[i];try{" +
            "var rect=iframe.getBoundingClientRect();" +
            "if(rect.width>0&&rect.height>0){" +
            "var evt=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});" +
            "iframe.dispatchEvent(evt);" +
            "}" +
            "}catch(e){}" +
            "}" +
            "var widgets=document.querySelectorAll('.cf-turnstile,.turnstile-widget,[data-cf-turnstile]');" +
            "for(var j=0;j<widgets.length;j++){try{widgets[j].click();}catch(e){}}" +
            "}" +
            "clickTurnstile();" +
            "setTimeout(clickTurnstile,500);" +
            "setTimeout(clickTurnstile,1200);" +
            "setTimeout(clickTurnstile,2500);" +
            "})()";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.onCreate(savedInstanceState);
        Context context = this;
        setContentView(R.layout.activity_captcha);

        purl = p.getUrl();
        rootUrl = p.getWebtoonUrl();
        requireCloudflareClearance = (rootUrl != null && rootUrl.toLowerCase(java.util.Locale.ROOT).contains("://ntk"))
                || (purl != null && purl.toLowerCase(java.util.Locale.ROOT).contains("://ntk"));

        Intent intent = getIntent();
        String path = intent.getStringExtra("url");
        String url;
        if(path == null)
            url = getCaptchaStartUrl(purl, rootUrl);
        else if(path.startsWith("http://") || path.startsWith("https://"))
            url = path;
        else
            url = getCaptchaStartUrl(purl, rootUrl) + path;
        currentUrl = url;

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

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String webViewUserAgent = settings.getUserAgentString();
        settings.setUserAgentString(webViewUserAgent);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        getHttpClient().setUserAgent(webViewUserAgent);
        CookieSyncManager.createInstance(this);
        cookiem = CookieManager.getInstance();
        cookiem.setAcceptCookie(true);
        cookiem.setAcceptThirdPartyCookies(webView, true);
        getHttpClient().syncCookiesFromWebView(purl, true);
        getHttpClient().syncCookiesFromWebView(rootUrl, true);

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
                String userAgent = request.getRequestHeaders().get("User-Agent");
                if(userAgent != null && userAgent.length() > 0)
                    getHttpClient().setUserAgent(userAgent);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if(readCookiesAndFinish(true))
                    return;

                if(url.contains("bootstrap") || url.contains("jquery") || url.contains("cloudflare") || url.contains("turnstile")){
                    try {
                        if(syncCookies(cookiem, requireCloudflareClearance, purl, rootUrl, currentUrl)) {
                            Intent resultIntent = new Intent();
                            setResult(RESULT_CAPTCHA, resultIntent);
                            finish();
                            return;
                        }
                    }catch (Exception e){
                        Utils.showErrorPopup(context, "인증 도중 오류가 발생했습니다. 네트워크 연결 상태를 확인해주세요.", e, true);
                    }
                }
                view.loadUrl(TURNSTILE_AUTO_JS);
                super.onLoadResource(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                view.loadUrl(TURNSTILE_AUTO_JS);
                if(readCookiesAndFinish(true))
                    return;
                super.onPageFinished(view, url);
            }
        };

        webView.setWebViewClient(client);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if(consoleMessage != null)
                    android.util.Log.d("MangaViewCaptcha", consoleMessage.message());
                return super.onConsoleMessage(consoleMessage);
            }
        });
        findViewById(R.id.captchaReload).setOnClickListener(v -> webView.reload());
        findViewById(R.id.captchaCheckCookie).setOnClickListener(v -> {
            readCookiesAndFinish(false);
        });
        findViewById(R.id.captchaPasteCookie).setOnClickListener(v -> importCookieFromClipboard(context));
        findViewById(R.id.captchaClose).setOnClickListener(v -> finish());

//        webView.setOnTouchListener((view, motionEvent) -> true);

        webView.loadUrl(url);

        infoText.setVisibility(View.GONE);

    }

    private String getCaptchaStartUrl(String purl, String rootUrl) {
        if(rootUrl != null && rootUrl.toLowerCase(java.util.Locale.ROOT).contains("://ntk"))
            return rootUrl;
        return purl;
    }

    private boolean readCookiesAndFinish(boolean silent){
        try {
            boolean ready = syncCookies(cookiem, requireCloudflareClearance, purl, rootUrl, currentUrl);
            if(!ready) {
                if(!silent)
                    showPopup(this, "인증 대기", requireCloudflareClearance
                            ? "아직 Cloudflare 인증 쿠키가 없습니다. 인증 화면이 완료된 뒤 다시 확인해 주세요."
                            : "아직 저장된 인증 쿠키가 없습니다. 페이지 로드가 끝난 뒤 다시 확인해 주세요.");
                return false;
            }

            Intent resultIntent = new Intent();
            setResult(RESULT_CAPTCHA, resultIntent);
            finish();
            return true;
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return false;
    }

    private boolean syncCookies(CookieManager cookiem, boolean requireClearance, String... urls) {
        boolean hasClearance = false;
        boolean hasAnyCookie = false;
        for(String url : urls) {
            if(url == null || url.length() == 0)
                continue;
            String cookieStr = cookiem.getCookie(url);
            if(cookieStr == null || cookieStr.length() == 0)
                continue;
            hasAnyCookie = true;
            for(String s : cookieStr.split("; ")) {
                int eq = s.indexOf("=");
                if(eq <= 0)
                    continue;
                String k = s.substring(0, eq);
                String v = s.substring(eq + 1);
                getHttpClient().setCookie(k, v);
                if("cf_clearance".equals(k))
                    hasClearance = true;
            }
        }
        CookieManager.getInstance().flush();
        if(hasClearance) {
            getHttpClient().syncCookiesFromWebView(purl, true);
            getHttpClient().syncCookiesFromWebView(rootUrl, true);
            if(currentUrl != null)
                getHttpClient().syncCookiesFromWebView(currentUrl, true);
        }
        return requireClearance ? hasClearance : hasAnyCookie;
    }

    private void importCookieFromClipboard(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard == null || !clipboard.hasPrimaryClip()) {
                showPopup(context, "쿠키 없음", "클립보드에 cf_clearance 쿠키를 복사한 뒤 다시 눌러 주세요.");
                return;
            }
            ClipData data = clipboard.getPrimaryClip();
            if(data == null || data.getItemCount() == 0) {
                showPopup(context, "쿠키 없음", "클립보드에 cf_clearance 쿠키를 복사한 뒤 다시 눌러 주세요.");
                return;
            }
            CharSequence text = data.getItemAt(0).coerceToText(context);
            if(text == null || text.toString().trim().length() == 0) {
                showPopup(context, "쿠키 없음", "클립보드에 cf_clearance 쿠키를 복사한 뒤 다시 눌러 주세요.");
                return;
            }
            if(!saveCookieString(text.toString())) {
                showPopup(context, "쿠키 인식 실패", "cf_clearance=... 형식의 쿠키를 복사한 뒤 다시 시도해 주세요.");
                return;
            }
            Intent resultIntent = new Intent();
            setResult(RESULT_CAPTCHA, resultIntent);
            finish();
        } catch (Exception e) {
            Utils.showErrorPopup(context, "쿠키를 가져오지 못했습니다.", e, true);
        }
    }

    private boolean saveCookieString(String raw) {
        boolean hasClearance = false;
        String normalized = raw.replace("\n", ";").replace("\r", ";");
        if(normalized.trim().startsWith("cf_clearance="))
            normalized = normalized.trim();
        for(String part : normalized.split(";")) {
            String cookie = part.trim();
            int eq = cookie.indexOf("=");
            if(eq <= 0)
                continue;
            String key = cookie.substring(0, eq).trim();
            String value = cookie.substring(eq + 1).trim();
            String lower = key.toLowerCase(Locale.ROOT);
            if(value.length() == 0)
                continue;
            if("cf_clearance".equals(lower))
                hasClearance = true;
            if("cf_clearance".equals(lower) || lower.startsWith("cf_") || "__cf_bm".equals(lower) || lower.contains("session"))
                getHttpClient().setCookie(key, value);
        }
        return hasClearance;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //destroy webview
        ((ConstraintLayout) findViewById(R.id.captchaContainer)).removeAllViews();
        if(webView == null)
            return;
        webView.clearHistory();
        webView.clearCache(true);
        webView.destroy();
    }

    @Override
    public void finish() {
        super.finish();
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
