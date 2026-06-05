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
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Search;
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
            "var e3=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});" +
            "n.dispatchEvent(e1);n.dispatchEvent(e2);n.dispatchEvent(e3);" +
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
            "var sr=el.__sr||el.shadowRoot;" +
            "if(sr){" +
            "var inp=sr.querySelector('input[type=\"checkbox\"]');" +
            "if(inp)return inp;" +
            "}" +
            "}" +
            "return null;" +
            "}" +
            "function usableTurnstileFrame(frame,rect){" +
            "if(!rect||rect.width<=10||rect.height<=10)return false;" +
            "var src=((frame.getAttribute('src')||frame.src||'')+'').toLowerCase();" +
            "if(src.indexOf('feedback-reports')>=0||src.indexOf('/feedback')>=0||src.indexOf('overrunning')>=0)return false;" +
            "if(src.indexOf('/pat/')>=0||src.indexOf('/cmg/')>=0||src.indexOf('/flow/')>=0||src.indexOf('/orchestrate/')>=0)return false;" +
            "if(src.length===0)return true;" +
            "if(src.indexOf('challenges.cloudflare')<0&&src.indexOf('cloudflare.com/turnstile')<0)return false;" +
            "return src.indexOf('turnstile')>=0||src.indexOf('/challenge-platform/')>=0;" +
            "}" +
            "function checkboxTarget(rect){" +
            "var w=Math.min(36,Math.max(24,rect.width*0.18));" +
            "var h=Math.min(44,Math.max(24,rect.height*0.62));" +
            "var x=rect.left+Math.min(Math.max(rect.width*0.14,36),rect.width*0.32);" +
            "var y=rect.top+rect.height/2;" +
            "return {x:x,y:y,w:w,h:h};" +
            "}" +
            "var cb=findCheckbox();" +
            "if(cb){" +
            "try{cb.focus();" +
            "var e1=new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0.5});" +
            "var e2=new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerType:'touch',isPrimary:true,width:20,height:20,pressure:0});" +
            "var e3=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});" +
            "cb.dispatchEvent(e1);cb.dispatchEvent(e2);cb.dispatchEvent(e3);" +
            "}catch(ex){}" +
            "return JSON.stringify({type:'jsclick'});" +
            "}" +
            "var frames=document.querySelectorAll('iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]');" +
            "for(var fi=0;fi<frames.length;fi++){" +
            "var iframe=frames[fi];" +
            "var rect=iframe.getBoundingClientRect();" +
            "if(usableTurnstileFrame(iframe,rect)){" +
            "var t=checkboxTarget(rect);" +
            "return JSON.stringify({type:'iframe',x:t.x,y:t.y,w:t.w,h:t.h,sig:iframe.getAttribute('src')||iframe.id||iframe.name||''});" +
            "}" +
            "}" +
            "var turnstileDiv=document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"]');" +
            "if(turnstileDiv){" +
            "var rect=turnstileDiv.getBoundingClientRect();" +
            "if(rect.width>10&&rect.height>10){" +
            "return JSON.stringify({type:'div',x:rect.left+rect.width/2,y:rect.top+rect.height/2,w:rect.width,h:rect.height,sig:turnstileDiv.id||turnstileDiv.className||turnstileDiv.getAttribute('data-sitekey')||''});" +
            "}" +
            "}" +
            "var host=(location.hostname||'').toLowerCase();" +
            "function normalNtkPage(){" +
            "if(host.indexOf('ntk')<0&&host.indexOf('sbxh')<0&&host.indexOf('toonflix')<0)return false;" +
            "var pageText=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ');" +
            "if(pageText.length>200&&(pageText.indexOf('NEWTOKI')>=0||pageText.indexOf('웹툰')>=0&&pageText.indexOf('만화')>=0))return true;" +
            "if(document.querySelector('a[href^=\"/manhwa\"],a[href^=\"/webtoon\"],a[href*=\"/manhwa/\"],a[href*=\"/webtoon/\"]'))return true;" +
            "if(document.querySelector('img[src*=\"/webtoon_uploads/\"],img[src*=\"/manhwa_uploads/\"],img[src*=\"/comic_uploads/\"],img[data-src*=\"/webtoon_uploads/\"],img[data-src*=\"/manhwa_uploads/\"],img[data-src*=\"/comic_uploads/\"]'))return true;" +
            "if(document.querySelector('main,#__next,.container,.content,.list,.post,.view,.toon')){" +
            "var links=document.querySelectorAll('a[href]').length;" +
            "var imgs=document.querySelectorAll('img[src],img[data-src]').length;" +
            "if(links>=8||imgs>=4)return true;" +
            "}" +
            "return false;" +
            "}" +
            "if(normalNtkPage())return JSON.stringify({type:'normal'});" +
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
            "if(rect.width>50&&rect.height>50&&rect.height<180){" +
            "var t=checkboxTarget(rect);" +
            "return JSON.stringify({type:'iframe',x:t.x,y:t.y,w:t.w,h:t.h});" +
            "}" +
            "}" +
            "}" +
            "}" +
            "return JSON.stringify({type:'none'});" +
            "})();";
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){" +
            "if(window.__ntkQuicBridgeInstalled||!window.NtkQuicBridge)return;" +
            "window.__ntkQuicBridgeInstalled=1;" +
            "function parseUrl(u){try{return new URL(u,location.href);}catch(e){return null;}}" +
            "function shouldBridge(u,m){" +
            "var x=parseUrl(u);if(!x||x.protocol!=='https:')return false;" +
            "var h=x.hostname.toLowerCase();" +
            "var r=(location.hostname||'').toLowerCase();if(r.indexOf('www.')===0)r=r.slice(4);if(h.indexOf('www.')===0)h=h.slice(4);" +
            "if(!r||!(h===r||h.slice(-(r.length+1))==='.'+r))return false;" +
            "if(x.pathname.indexOf('/cdn-cgi/challenge-platform/')!==0)return false;" +
            "return String(m||'GET').toUpperCase()!=='GET';" +
            "}" +
            "function textBase64(s){return btoa(unescape(encodeURIComponent(s||'')));}" +
            "function bodyBase64(b){" +
            "try{" +
            "if(b==null)return '';" +
            "if(typeof b==='string')return textBase64(b);" +
            "if(window.URLSearchParams&&b instanceof URLSearchParams)return textBase64(b.toString());" +
            "if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}" +
            "if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}" +
            "return textBase64(String(b));" +
            "}catch(e){return '';}" +
            "}" +
            "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}" +
            "function collectHeaders(input,init){" +
            "var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;" +
            "}" +
            "function chromeMajor(){var m=String(navigator.userAgent||'').match(/Chrome\\/(\\d+)/);return m?m[1]:'116';}" +
            "function isMobileUa(){return /Android|Mobile/i.test(String(navigator.userAgent||''));}" +
            "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';if(!h['Sec-Fetch-Dest']&&!h['sec-fetch-dest'])h['Sec-Fetch-Dest']='empty';if(!h['Sec-Fetch-Mode']&&!h['sec-fetch-mode'])h['Sec-Fetch-Mode']='cors';if(!h['Sec-Fetch-Site']&&!h['sec-fetch-site'])h['Sec-Fetch-Site']='same-origin';var v=chromeMajor(),mobile=isMobileUa();if(!h['sec-ch-ua'])h['sec-ch-ua']=mobile?'\\\"Chromium\\\";v=\\\"'+v+'\\\", \\\"Android WebView\\\";v=\\\"'+v+'\\\", \\\"Not A(Brand\\\";v=\\\"24\\\"':'\\\"Chromium\\\";v=\\\"'+v+'\\\", \\\"Google Chrome\\\";v=\\\"'+v+'\\\", \\\"Not_A Brand\\\";v=\\\"24\\\"';if(!h['sec-ch-ua-mobile'])h['sec-ch-ua-mobile']=mobile?'?1':'?0';if(!h['sec-ch-ua-platform'])h['sec-ch-ua-platform']=mobile?'\\\"Android\\\"':'\\\"Windows\\\"';}catch(e){}return h;}" +
            "var nativeFetch=window.fetch;" +
            "if(nativeFetch){window.fetch=function(input,init){" +
            "var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';" +
            "var method=(init&&init.method)||(input&&input.method)||'GET';" +
            "if(!shouldBridge(url,method))return nativeFetch.apply(this,arguments);" +
            "return new Promise(function(resolve,reject){try{" +
            "var absolute=new URL(url,location.href).href;" +
            "var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addDefaultHeaders(collectHeaders(input,init))),bodyBase64(init&&init.body));" +
            "var res=JSON.parse(raw||'{}');" +
            "if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');" +
            "resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));" +
            "}catch(e){reject(e);}});" +
            "};}" +
            "try{console.log('__NTK_QUIC_BRIDGE_INSTALLED__');}catch(e){}" +
            "var xhrOpen=window.XMLHttpRequest&&XMLHttpRequest.prototype.open;" +
            "var xhrSend=window.XMLHttpRequest&&XMLHttpRequest.prototype.send;" +
            "var xhrSetHeader=window.XMLHttpRequest&&XMLHttpRequest.prototype.setRequestHeader;" +
            "if(xhrOpen&&xhrSend){" +
            "XMLHttpRequest.prototype.open=function(m,u,a,user,pw){this.__ntkq={method:m||'GET',url:u||'',headers:{}};return xhrOpen.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);return xhrSetHeader?xhrSetHeader.apply(this,arguments):undefined;};" +
            "XMLHttpRequest.prototype.send=function(body){" +
            "var meta=this.__ntkq;if(!meta||!shouldBridge(meta.url,meta.method))return xhrSend.apply(this,arguments);" +
            "var xhr=this;setTimeout(function(){try{" +
            "var raw=window.NtkQuicBridge.request(new URL(meta.url,location.href).href,String(meta.method),JSON.stringify(addDefaultHeaders(meta.headers||{})),bodyBase64(body));" +
            "var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');" +
            "var headers=res.headers||{},headerText='';Object.keys(headers).forEach(function(k){headerText+=k+': '+headers[k]+'\\r\\n';});" +
            "var arr=bytesFromBase64(res.bodyBase64||''),response=arr;" +
            "if(!xhr.responseType||xhr.responseType==='text'){var bin='';for(var i=0;i<arr.length;i++)bin+=String.fromCharCode(arr[i]);response=decodeURIComponent(escape(bin));}" +
            "Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});" +
            "Object.defineProperty(xhr,'status',{configurable:true,get:function(){return res.status||200;}});" +
            "Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return res.statusText||'OK';}});" +
            "Object.defineProperty(xhr,'response',{configurable:true,get:function(){return response;}});" +
            "Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return typeof response==='string'?response:'';}});" +
            "xhr.getAllResponseHeaders=function(){return headerText;};" +
            "xhr.getResponseHeader=function(n){var l=String(n||'').toLowerCase();for(var k in headers){if(k.toLowerCase()===l)return headers[k];}return null;};" +
            "['readystatechange','load','loadend'].forEach(function(n){var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);});" +
            "}catch(e){try{console.log('__NTK_QUIC_XHR_ERROR__ '+e);}catch(x){}var ev=new Event('error');xhr.dispatchEvent(ev);if(typeof xhr.onerror==='function')xhr.onerror.call(xhr,ev);}}," +
            "0);" +
            "};" +
            "}" +
            "var nativeBeacon=navigator.sendBeacon;" +
            "if(nativeBeacon){navigator.sendBeacon=function(url,data){" +
            "if(!shouldBridge(url,'POST'))return nativeBeacon.apply(this,arguments);" +
            "try{window.NtkQuicBridge.request(new URL(url,location.href).href,'POST','{}',bodyBase64(data));return true;}catch(e){return false;}" +
            "};}" +
            "})();";
    private long pageFinishedTime = 0;
    private long lastAttemptTime = 0;
    private static final long FIRST_CLICK_DELAY_MS = 0;
    private static final long RETRY_MIN_MS = 100;
    private static final long RETRY_MAX_MS = 300;
    private static final long TURNSTILE_EVALUATION_MIN_INTERVAL_MS = 600L;
    private static final long TURNSTILE_IDLE_RECHECK_MS = 1_000L;
    private static final long TURNSTILE_REPEAT_TOUCH_INTERVAL_MS = 8_000L;
    private static final int TURNSTILE_MAX_TOUCHES_PER_WIDGET = 3;
    private boolean isFirstAttempt = true;
    private boolean isFinishing = false;
    private Set<String> initialClearanceValues = new HashSet<>();
    private int normalNtkPageCount = 0;
    private boolean turnstileAutoClickStarted = false;
    private boolean accessVerificationInFlight = false;
    private boolean turnstileEvaluationInFlight = false;
    private long lastTurnstileEvaluationAt = 0;
    private long lastTurnstileTouchAt = 0;
    private String lastTurnstileClickSignature = "";
    private long lastTurnstileRepeatTouchAt = 0;
    private int turnstileRepeatTouchCount = 0;
    private final Set<String> rejectedClearanceValues = new HashSet<>();
    private String lastVerificationClearanceValue = null;
    private long lastClearanceVerificationAt = 0;
    private long lastInvalidClearanceReloadAt = 0;
    private long lastCookieReadAt = 0;
    private String captchaLoadUrl;
    private boolean captchaLoadErrorVisible = false;
    private LocalWebViewProxy localWebViewProxy;
    private WebView releasedWebView;
    private boolean retriedCaptchaWithQuic = false;
    private boolean quicCaptchaLoadInFlight = false;
    private boolean quicCaptchaHtmlActive = false;
    private boolean retriedCaptchaWithProxy = false;
    private boolean retriedCaptchaWithoutProxy = false;

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
        if(shouldFinishRedundantNtkCaptcha(url, "onCreate"))
            return;

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
        boolean ntkSite = p != null && p.isNtkSite();
        WebView.setWebContentsDebuggingEnabled(shouldEnableWebContentsDebuggingForTest(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                ntkSite));
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
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        String userAgentOverride = ntkSite && intent.hasExtra("ntkCaptchaUserAgent")
                ? intent.getStringExtra("ntkCaptchaUserAgent") : null;
        String realChromeUA = userAgentOverride != null && userAgentOverride.trim().length() > 0
                ? userAgentOverride.trim()
                : captchaUserAgent(settings.getUserAgentString(), ntkSite);
        settings.setUserAgentString(realChromeUA);
        getHttpClient().setUserAgent(settings.getUserAgentString());
        if(ntkSite && NtkQuicFetcher.isAvailable())
            webView.addJavascriptInterface(new NtkQuicJavascriptBridge(), "NtkQuicBridge");

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
                    handler.post(() -> attemptTurnstileClickImmediate());
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if(request == null || !request.isForMainFrame())
                    return false;
                String targetUrl = request.getUrl() == null ? null : request.getUrl().toString();
                return shouldBlockCaptchaNavigation(targetUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldBlockCaptchaNavigation(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                handler.removeCallbacksAndMessages(null);
                hideCaptchaLoadError();
                pageFinishedTime = 0;
                lastAttemptTime = 0;
                lastCookieReadAt = 0;
                normalNtkPageCount = 0;
                turnstileAutoClickStarted = false;
                isFirstAttempt = true;
                lastAttemptTime = 0;
                turnstileEvaluationInFlight = false;
                lastTurnstileEvaluationAt = 0;
                lastTurnstileClickSignature = "";
                lastTurnstileRepeatTouchAt = 0;
                turnstileRepeatTouchCount = 0;
                view.evaluateJavascript(SHADOW_HOOK_JS, null);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                //super.onReceivedError(view, request, error);
                if(request != null && !request.isForMainFrame()) {
                    logNtkSubresourceError(request, error);
                    return;
                }
                String failingUrl = request != null && request.getUrl() != null ? request.getUrl().toString() : (view == null ? null : view.getUrl());
                if(retryCaptchaLoadWithProxyIfNeeded(failingUrl))
                    return;
                if(retryCaptchaLoadWithoutProxyIfNeeded(failingUrl))
                    return;
                if(retryCaptchaLoadWithQuicIfNeeded(failingUrl))
                    return;
                if(shouldSuppressNtkLoadErrorPopupForTest(p != null && p.isNtkSite(), failingUrl, purl)) {
                    android.util.Log.d("CaptchaActivity", "Suppressing NTK captcha WebView load error popup: " + failingUrl);
                    showCaptchaLoadError(failingUrl);
                    return;
                }
                showCaptchaLoadError(failingUrl);
                showPopup(context, "오류", "연결에 실패했습니다. URL을 확인해 주세요");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if(request != null && !request.isForMainFrame() && p != null && p.isNtkSite()) {
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
                    android.util.Log.d("CaptchaActivity", "NTK subresource HTTP error: method="
                            + request.getMethod() + ",code=" + code + ",url=" + url);
                }
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Do NOT overwrite OkHttp UA with WebView UA here.
                // WebView default UA contains "; wv)" which flags it as WebView to Cloudflare,
                // causing Turnstile to trigger more aggressively.
                // UA is already synchronized via setUserAgentString() during setup.
                WebResourceResponse quicResponse = interceptNtkQuicRequest(request);
                if(quicResponse != null)
                    return quicResponse;
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                android.util.Log.w("CaptchaActivity", "Captcha WebView renderer gone; recovering without crashing");
                handler.removeCallbacksAndMessages(null);
                clearWebViewProxy();
                if(view == webView) {
                    detachCaptchaWebView();
                    destroyReleasedWebViewLater();
                    if(!isFinishing && !isDestroyed()) {
                        Toast.makeText(CaptchaActivity.this, "Captcha page closed. Please try again.", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
                return true;
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
        if(shouldFinishRedundantNtkCaptcha(captchaLoadUrl, "onNewIntent"))
            return;
        retriedCaptchaWithQuic = false;
        quicCaptchaLoadInFlight = false;
        quicCaptchaHtmlActive = false;
        retriedCaptchaWithProxy = false;
        retriedCaptchaWithoutProxy = false;
        hideCaptchaLoadError();
        clearWebViewProxy();
        if(webView != null)
            loadCaptchaUrl(captchaLoadUrl);
    }

    private boolean shouldFinishRedundantNtkCaptcha(String url, String source) {
        if(p == null || !p.isNtkSite())
            return false;
        if(!getHttpClient().hasNtkAccessProof())
            return false;
        Log.d("CaptchaActivity", "finish redundant NTK captcha intent source=" + source
                + " proof=" + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " url=" + url);
        getHttpClient().markNtkAccessVerified();
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        Intent resultIntent = new Intent();
        setResult(RESULT_CAPTCHA, resultIntent);
        finish();
        return true;
    }

    private String resolveCaptchaUrl(Intent intent, String purl) {
        String path = intent == null ? null : intent.getStringExtra("url");
        if(p != null && p.isNtkSite()) {
            String resolved = ntkCaptchaLoadUrl(path, purl);
            Log.d("CaptchaActivity", "resolve NTK captcha path=" + path
                    + " purl=" + purl
                    + " resolved=" + resolved);
            return resolved;
        }
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
                retriedCaptchaWithQuic = false;
                quicCaptchaLoadInFlight = false;
                quicCaptchaHtmlActive = false;
                retriedCaptchaWithProxy = false;
                retriedCaptchaWithoutProxy = false;
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
            close.setOnClickListener(v -> {
                syncCaptchaCookiesToHttpClient(purl, webView == null ? null : webView.getUrl());
                finish();
            });
    }

    static String captchaUserAgentForTest(String defaultUserAgent) {
        return captchaUserAgent(defaultUserAgent, false);
    }

    static String ntkCaptchaUserAgentForTest(String defaultUserAgent) {
        return captchaUserAgent(defaultUserAgent, true);
    }

    static boolean shouldEnableWebContentsDebuggingForTest(boolean debuggable, boolean ntkSite) {
        return debuggable && !ntkSite;
    }

    private static String captchaUserAgent(String defaultUserAgent, boolean ntkSite) {
        if(defaultUserAgent == null || defaultUserAgent.trim().length() == 0)
            return "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
        String ua = defaultUserAgent.trim()
                .replace("; wv", "")
                .replace(" wv", "")
                .replace("Version/4.0 ", "");
        if(!ua.contains("Mobile Safari/"))
            ua = ua + " Mobile Safari/537.36";
        return ua;
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
        loadCaptchaUrlDirect(url);
    }

    private void loadCaptchaUrlDirect(String url) {
        quicCaptchaHtmlActive = false;
        webView.loadUrl(url);
        webView.evaluateJavascript(SHADOW_HOOK_JS, null);
    }

    private boolean retryCaptchaLoadWithQuicIfNeeded(String failingUrl) {
        if(!shouldRetryCaptchaLoadWithQuicForTest(p != null && p.isNtkSite(),
                retriedCaptchaWithQuic,
                quicCaptchaLoadInFlight,
                failingUrl,
                NtkQuicFetcher.isAvailable()))
            return false;
        retriedCaptchaWithQuic = true;
        quicCaptchaLoadInFlight = true;
        String retryUrl = failingUrl != null && failingUrl.length() > 0 ? failingUrl : captchaLoadUrl;
        android.util.Log.d("CaptchaActivity", "Retrying NTK captcha WebView with QUIC HTML fallback: " + retryUrl);
        quicCaptchaHtmlActive = true;
        quicCaptchaLoadInFlight = false;
        webView.loadUrl(retryUrl);
        handler.postDelayed(() -> {
            if(!isFinishing && !isDestroyed() && webView != null)
                webView.evaluateJavascript(SHADOW_HOOK_JS, null);
        }, 250L);
        return true;
    }

    static boolean shouldRetryCaptchaLoadWithQuicForTest(boolean ntkSite, boolean alreadyRetried,
                                                          boolean inFlight, String failingUrl,
                                                          boolean quicAvailable) {
        return ntkSite
                && quicAvailable
                && !alreadyRetried
                && !inFlight
                && failingUrl != null
                && failingUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
    }

    private String captchaCookieHeaderFor(String url) {
        String httpCookie = getHttpClient().getCookieHeader();
        String webViewCookie = null;
        try {
            webViewCookie = CookieManager.getInstance().getCookie(url);
        } catch (Exception ignored) {
        }
        if(webViewCookie == null || webViewCookie.length() == 0)
            return httpCookie;
        if(httpCookie == null || httpCookie.length() == 0 || httpCookie.equals(webViewCookie))
            return webViewCookie;
        return httpCookie + "; " + webViewCookie;
    }

    private String injectNtkQuicBridgeScript(String url, String html, Map<String, List<String>> headers) {
        if(html == null || html.length() == 0 || p == null || !p.isNtkSite()
                || !NtkQuicFetcher.isAvailable() || !isNtkProtectedHttpsUrl(url)
                || html.contains("__ntkQuicBridgeInstalled"))
            return html;
        String nonce = cspNonce(headers);
        if(nonce == null || nonce.length() == 0)
            nonce = htmlNonce(html);
        String script = "<script" + (nonce == null || nonce.length() == 0
                ? "" : " nonce=\"" + escapeHtmlAttribute(nonce) + "\"") + ">" + NTK_QUIC_BRIDGE_JS + "</script>";
        String lower = html.toLowerCase(java.util.Locale.ROOT);
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

    private String cspNonce(Map<String, List<String>> headers) {
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

    private String htmlNonce(String html) {
        String nonce = extractAttributeNonce(html, "nonce=\"", "\"");
        if(nonce != null && nonce.length() > 0)
            return nonce;
        return extractAttributeNonce(html, "nonce='", "'");
    }

    private String extractNonceToken(String value) {
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

    private String extractAttributeNonce(String value, String marker, String terminator) {
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

    private String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private final class NtkQuicJavascriptBridge {
        @JavascriptInterface
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(!NtkQuicFetcher.isAvailable() || !isNtkProtectedHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                byte[] body = bodyBase64 == null || bodyBase64.length() == 0
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                android.util.Log.d("CaptchaActivity", "NTK JS bridge QUIC start: method="
                        + method + ",bodyLen=" + body.length + ",headers=" + headers.keySet() + ",url=" + url);
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                        getApplicationContext(),
                        url,
                        getHttpClient().agent,
                        captchaCookieHeaderFor(url),
                        headers,
                        method,
                        body,
                        30000L);
                if(result == null)
                    return bridgeError("empty result");
                if(result.error != null)
                    return bridgeError(String.valueOf(result.error));
                applyQuicCaptchaCookiesOnMain(url, result);
                finishAfterQuicClearanceIfPresent(url, result);
                android.util.Log.d("CaptchaActivity", "NTK JS bridge QUIC request: method="
                        + method + ",code=" + result.code + ",len=" + result.bodyBytes.length + ",url=" + url);
                return bridgeResponse(result);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "NTK JS bridge QUIC request failed: " + url, e);
                return bridgeError(String.valueOf(e));
            }
        }
    }

    private Map<String, String> parseBridgeHeaders(String headersJson) {
        HashMap<String, String> result = new HashMap<>();
        if(headersJson == null || headersJson.length() == 0)
            return result;
        try {
            org.json.JSONObject object = new org.json.JSONObject(headersJson);
            Iterator<String> keys = object.keys();
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

    private String bridgeResponse(NtkQuicFetcher.Result result) {
        try {
            org.json.JSONObject object = new org.json.JSONObject();
            object.put("ok", true);
            object.put("status", result.code);
            object.put("statusText", result.code >= 400 ? "Cloudflare" : "OK");
            object.put("headers", bridgeResponseHeaders(result.headers));
            object.put("bodyBase64", Base64.encodeToString(result.bodyBytes, Base64.NO_WRAP));
            return object.toString();
        } catch (Exception e) {
            return bridgeError(String.valueOf(e));
        }
    }

    private org.json.JSONObject bridgeResponseHeaders(Map<String, List<String>> headers) throws org.json.JSONException {
        org.json.JSONObject object = new org.json.JSONObject();
        if(headers == null)
            return object;
        for(String key : headers.keySet()) {
            if(key == null || "set-cookie".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values != null && values.size() > 0 && values.get(0) != null)
                object.put(key, values.get(0));
        }
        return object;
    }

    private String bridgeError(String message) {
        try {
            org.json.JSONObject object = new org.json.JSONObject();
            object.put("ok", false);
            object.put("error", message == null ? "unknown" : message);
            return object.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    private void applyQuicCaptchaCookiesOnMain(String url, NtkQuicFetcher.Result result) {
        if(Looper.myLooper() == Looper.getMainLooper()) {
            applyQuicCaptchaCookies(url, result);
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AppDispatchers.runOnMain(() -> {
            try {
                applyQuicCaptchaCookies(url, result);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finishAfterQuicClearanceIfPresent(String url, NtkQuicFetcher.Result result) {
        String clearance = clearanceValueFromSetCookies(result);
        if(clearance == null)
            return;
        AppDispatchers.runOnMain(() -> {
            if(isFinishing || isDestroyed())
                return;
            String currentUrl = webView == null ? url : webView.getUrl();
            verifyNtkAccessAndFinish(p.getUrl(), currentUrl == null ? url : currentUrl, clearance);
        });
    }

    private String clearanceValueFromSetCookies(NtkQuicFetcher.Result result) {
        if(result == null)
            return null;
        for(String cookie : result.setCookies()) {
            String value = extractCookieValueForTest(cookie, "cf_clearance");
            if(isValidClearanceValue(value))
                return value;
        }
        return null;
    }

    private void applyQuicCaptchaCookies(String url, NtkQuicFetcher.Result result) {
        if(url == null || result == null)
            return;
        CookieManager manager = CookieManager.getInstance();
        List<String> setCookies = result.setCookies();
        if(setCookies.size() > 0)
            android.util.Log.d("CaptchaActivity", "NTK QUIC set-cookie names: "
                    + quicSetCookieNamesForTest(setCookies));
        for(String cookie : setCookies) {
            if(cookie == null || cookie.length() == 0)
                continue;
            manager.setCookie(url, cookie);
            int eq = cookie.indexOf('=');
            int semi = cookie.indexOf(';');
            if(eq > 0) {
                if(semi < 0)
                    semi = cookie.length();
                getHttpClient().setCookie(cookie.substring(0, eq), cookie.substring(eq + 1, semi));
            }
        }
        manager.flush();
    }

    static String quicSetCookieNamesForTest(List<String> cookies) {
        if(cookies == null || cookies.size() == 0)
            return "none";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        boolean hasClearance = false;
        for(String cookie : cookies) {
            if(cookie == null)
                continue;
            int eq = cookie.indexOf('=');
            if(eq <= 0)
                continue;
            String name = cookie.substring(0, eq).trim();
            if(name.length() == 0)
                continue;
            names.add(name);
            if("cf_clearance".equalsIgnoreCase(name))
                hasClearance = true;
        }
        return "count=" + names.size() + ",hasClearance=" + hasClearance + ",names=" + names;
    }

    private WebResourceResponse interceptNtkQuicRequest(WebResourceRequest request) {
        if(request == null || request.getUrl() == null)
            return null;
        String method = request.getMethod();
        String url = request.getUrl().toString();
        if(p != null && p.isNtkSite() && isNtkProtectedHttpsUrl(url) && method != null && !"GET".equalsIgnoreCase(method))
            android.util.Log.d("CaptchaActivity", "NTK WebView request needs direct WebView transport: method="
                    + method + ",url=" + url);
        if(!shouldInterceptNtkQuicRequestForTest(p != null && p.isNtkSite(), quicCaptchaHtmlActive,
                method, url, NtkQuicFetcher.isAvailable()))
            return null;
        try {
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                    getApplicationContext(),
                    url,
                    getHttpClient().agent,
                    captchaCookieHeaderFor(url),
                    request.getRequestHeaders(),
                    request.isForMainFrame() ? 15000L : 10000L);
            if(result == null || !result.isUsableHtml())
                return null;
            applyQuicCaptchaCookies(url, result);
            byte[] responseBytes = result.bodyBytes;
            if(request.isForMainFrame() && responseMimeType(result.contentType()).toLowerCase(java.util.Locale.ROOT).contains("html"))
                responseBytes = injectNtkQuicBridgeScript(url, result.body, result.headers).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            android.util.Log.d("CaptchaActivity", "Intercepted NTK WebView request through QUIC: "
                    + url + ",code=" + result.code + ",len=" + responseBytes.length);
            return new WebResourceResponse(
                    responseMimeType(result.contentType()),
                    responseEncoding(result.contentType()),
                    result.code,
                    result.code >= 400 ? "Cloudflare" : "OK",
                    responseHeaders(result.headers),
                    new ByteArrayInputStream(responseBytes));
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "Failed NTK QUIC WebView intercept: " + url, e);
            return null;
        }
    }

    static boolean shouldInterceptNtkQuicRequestForTest(boolean ntkSite, boolean quicHtmlActive,
                                                        String method, String url, boolean quicAvailable) {
        if(!ntkSite || !quicHtmlActive || !quicAvailable || method == null || !"GET".equalsIgnoreCase(method))
            return false;
        return isNtkProtectedHttpsUrl(url);
    }

    static String ntkQuicBridgeJavascriptForTest() {
        return NTK_QUIC_BRIDGE_JS;
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
            String root = p == null ? NTK_WEBTOON_URL : p.getNtkResolvedRoot();
            URI uri = URI.create(root);
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return normalizeHost(NTK_WEBTOON_URL);
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
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private void logNtkSubresourceError(WebResourceRequest request, WebResourceError error) {
        if(p == null || !p.isNtkSite() || request == null || request.getUrl() == null)
            return;
        String url = request.getUrl().toString();
        if(!isNtkProtectedHttpsUrl(url))
            return;
        int code = error == null ? 0 : error.getErrorCode();
        CharSequence description = error == null ? "" : error.getDescription();
        android.util.Log.d("CaptchaActivity", "NTK subresource load error: method="
                + request.getMethod() + ",code=" + code + ",desc=" + description + ",url=" + url);
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
                if(trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("charset="))
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

    private boolean retryCaptchaLoadWithProxyIfNeeded(String failingUrl) {
        if(!shouldRetryCaptchaLoadWithProxyForTest(p != null && p.isNtkSite(),
                localWebViewProxy != null,
                retriedCaptchaWithProxy,
                failingUrl))
            return false;
        retriedCaptchaWithProxy = true;
        String retryUrl = failingUrl != null && failingUrl.length() > 0 ? failingUrl : captchaLoadUrl;
        android.util.Log.d("CaptchaActivity", "Retrying NTK captcha WebView with local proxy: " + retryUrl);
        handler.postDelayed(() -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            hideCaptchaLoadError();
            if(!loadCaptchaUrlWithProxy(retryUrl))
                loadCaptchaUrlDirect(retryUrl);
        }, 250L);
        return true;
    }

    private boolean retryCaptchaLoadWithoutProxyIfNeeded(String failingUrl) {
        if(!shouldRetryCaptchaLoadWithoutProxyForTest(p != null && p.isNtkSite(),
                localWebViewProxy != null,
                retriedCaptchaWithoutProxy,
                failingUrl))
            return false;
        retriedCaptchaWithoutProxy = true;
        String retryUrl = failingUrl != null && failingUrl.length() > 0 ? failingUrl : captchaLoadUrl;
        android.util.Log.d("CaptchaActivity", "Retrying NTK captcha WebView without proxy: " + retryUrl);
        clearWebViewProxy();
        handler.postDelayed(() -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            hideCaptchaLoadError();
            loadCaptchaUrlDirect(retryUrl);
        }, 250L);
        return true;
    }

    static boolean shouldRetryCaptchaLoadWithProxyForTest(boolean ntkSite, boolean proxyActive,
                                                           boolean alreadyRetried, String failingUrl) {
        return ntkSite
                && !proxyActive
                && !alreadyRetried
                && failingUrl != null
                && failingUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
    }

    static boolean shouldRetryCaptchaLoadWithoutProxyForTest(boolean ntkSite, boolean proxyActive,
                                                              boolean alreadyRetried, String failingUrl) {
        return ntkSite
                && proxyActive
                && !alreadyRetried
                && failingUrl != null
                && failingUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
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
                    quicCaptchaHtmlActive = false;
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
                if(readCookiesAndFinish(CookieManager.getInstance(), p.getUrl(), webView == null ? null : webView.getUrl(), true)) {
                    return;
                }

                attemptTurnstileClick();

                handler.postDelayed(this, nextTurnstileCheckDelay());
            }
        }, TURNSTILE_CHECK_DELAY_MS);
    }

    private void attemptTurnstileClick() {
        if(isFinishing || isDestroyed() || webView == null || captchaLoadErrorVisible) return;

        long now = System.currentTimeMillis();
        long requiredInterval = isFirstAttempt ? FIRST_CLICK_DELAY_MS : TURNSTILE_EVALUATION_MIN_INTERVAL_MS;
        if(now - lastAttemptTime < requiredInterval) return;
        lastAttemptTime = now;

        performTurnstileClickEvaluation();
    }

    private void attemptTurnstileClickImmediate() {
        if(isFinishing || isDestroyed() || webView == null || captchaLoadErrorVisible) return;
        long now = System.currentTimeMillis();
        if(now - lastAttemptTime < TURNSTILE_EVALUATION_MIN_INTERVAL_MS)
            return;
        lastAttemptTime = now;
        performTurnstileClickEvaluation();
    }

    private long nextTurnstileCheckDelay() {
        return nextTurnstileCheckDelayForState(isFirstAttempt, lastTurnstileClickSignature,
                turnstileRepeatTouchCount, System.currentTimeMillis(), lastTurnstileRepeatTouchAt);
    }

    private static long nextTurnstileCheckDelayForState(boolean firstAttempt, String signature,
                                                        int touchCount, long now, long lastTouchAt) {
        if(signature != null && signature.length() > 0) {
            if(touchCount > 0 && touchCount < TURNSTILE_MAX_TOUCHES_PER_WIDGET) {
                long elapsed = now - lastTouchAt;
                long wait = TURNSTILE_REPEAT_TOUCH_INTERVAL_MS - elapsed;
                return Math.max(TURNSTILE_IDLE_RECHECK_MS, wait);
            }
            return TURNSTILE_IDLE_RECHECK_MS * 2;
        }
        if(firstAttempt)
            return TURNSTILE_EVALUATION_MIN_INTERVAL_MS;
        return TURNSTILE_IDLE_RECHECK_MS;
    }

    private void performTurnstileClickEvaluation() {
        long evaluationStartedAt = System.currentTimeMillis();
        if(turnstileEvaluationInFlight
                || evaluationStartedAt - lastTurnstileEvaluationAt < TURNSTILE_EVALUATION_MIN_INTERVAL_MS)
            return;
        turnstileEvaluationInFlight = true;
        lastTurnstileEvaluationAt = evaluationStartedAt;
        webView.evaluateJavascript(TURNSTILE_AUTO_JS, result -> {
            turnstileEvaluationInFlight = false;
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
                    String signature = turnstileSignature(obj, x, y, w, h);
                    android.util.Log.d("CaptchaActivity", "Turnstile iframe found at: " + x + "," + y + " size:" + w + "x" + h);

                    long now = System.currentTimeMillis();
                    if(!signature.equals(lastTurnstileClickSignature)) {
                        lastTurnstileClickSignature = signature;
                        lastTurnstileRepeatTouchAt = now;
                        turnstileRepeatTouchCount = 1;
                        postTurnstileTouch(x, y, w, h);
                    } else if(shouldRetryTurnstileTouchForTest(
                            now, lastTurnstileRepeatTouchAt, turnstileRepeatTouchCount)) {
                        lastTurnstileRepeatTouchAt = now;
                        turnstileRepeatTouchCount++;
                        postTurnstileTouch(x, y, w, h);
                    }
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
                        android.util.Log.d("CaptchaActivity", "NTK normal page stable; finishing captcha after cookie sync");
                        finishAfterNormalNtkPage(p.getUrl(), webView == null ? null : webView.getUrl());
                    }
                } else {
                    isFirstAttempt = false;
                    normalNtkPageCount = 0;
                    lastTurnstileClickSignature = "";
                    lastTurnstileRepeatTouchAt = 0;
                    turnstileRepeatTouchCount = 0;
                }
            } catch(Exception e) {
                android.util.Log.e("CaptchaActivity", "Failed to parse turnstile result", e);
            }
        });
    }

    private String turnstileSignature(org.json.JSONObject obj, float x, float y, float w, float h) {
        String signature = obj.optString("sig", "");
        if(signature != null && signature.length() > 0)
            return signature;
        int rx = Math.round(x);
        int ry = Math.round(y);
        int rw = Math.round(w);
        int rh = Math.round(h);
        return rx + ":" + ry + ":" + rw + ":" + rh;
    }

    private void postTurnstileTouch(float x, float y, float w, float h) {
        webView.post(() -> {
            if(!isFinishing && !isDestroyed() && webView != null)
                simulateTouchBurst(webView, x, y, w, h);
        });
    }

    static boolean shouldRetryTurnstileTouchForTest(long now, long lastTouchAt, int touchCount) {
        return touchCount > 0
                && touchCount < TURNSTILE_MAX_TOUCHES_PER_WIDGET
                && now - lastTouchAt >= TURNSTILE_REPEAT_TOUCH_INTERVAL_MS;
    }

    static long nextTurnstileCheckDelayForTest(boolean firstAttempt, String signature,
                                               int touchCount, long now, long lastTouchAt) {
        return nextTurnstileCheckDelayForState(firstAttempt, signature, touchCount, now, lastTouchAt);
    }

    static boolean shouldUseTurnstileIframeForTest(String src, double width, double height) {
        return shouldUseTurnstileIframe(src, width, height);
    }

    private static boolean shouldUseTurnstileIframe(String src, double width, double height) {
        if(width <= 10d || height <= 10d)
            return false;
        String lower = src == null ? "" : src.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("feedback-reports") || lower.contains("/feedback") || lower.contains("overrunning"))
            return false;
        if(lower.contains("/pat/") || lower.contains("/cmg/") || lower.contains("/flow/") || lower.contains("/orchestrate/"))
            return false;
        if(lower.length() == 0)
            return true;
        if(!lower.contains("challenges.cloudflare") && !lower.contains("cloudflare.com/turnstile"))
            return false;
        return lower.contains("turnstile") || lower.contains("/challenge-platform/");
    }

    static boolean shouldUseTurnstileFallbackContainerForTest(double width, double height) {
        return width > 50d && height > 50d && height < 180d;
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
            cookiem.flush();
            syncCaptchaCookiesToHttpClient(purl, currentUrl);
            boolean hasClearance = false;
            final String[] clearanceValue = new String[1];
            for(String cookieUrl : cookieReadUrls(purl, currentUrl)) {
                if(cookieUrl == null || cookieUrl.length() == 0)
                    continue;
                String cookieStr = cookiem.getCookie(cookieUrl);
                if(cookieStr == null || cookieStr.length() == 0)
                    continue;
                final boolean[] foundClearance = { hasClearance };
                CaptchaCookiePolicy.forEachCookiePair(cookieStr, (k, v) -> {
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
                syncCaptchaCookiesToHttpClient(purl, currentUrl);
                verifyNtkAccessAndFinish(purl, currentUrl, clearanceValue[0]);
            } else if(getHttpClient().hasCloudflareClearance()) {
                verifyNtkAccessAndFinish(purl, currentUrl, null);
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
        syncCaptchaCookiesToHttpClient(purl, currentUrl);
        AppDispatchers.runIo(() -> {
            boolean verified = verifyNtkAccess(purl, currentUrl);
            AppDispatchers.runOnMain(() -> {
                accessVerificationInFlight = false;
                if(isFinishing)
                    return;
                if(verified) {
                    android.util.Log.d("CaptchaActivity", "NTK clearance verified by app HTTP client");
                    finishWithVerifiedClearance();
                } else if(shouldFinishAfterVisibleNormalNtkPage()) {
                    android.util.Log.d("CaptchaActivity", "NTK app HTTP verification failed, but visible normal page is stable; finishing captcha");
                    finishAfterNormalNtkPage(purl, currentUrl);
                } else {
                    android.util.Log.d("CaptchaActivity", "NTK clearance failed app HTTP verification; keeping captcha open");
                    if(clearanceValue != null)
                        rejectedClearanceValues.add(clearanceValue);
                }
            });
        });
    }

    private boolean shouldFinishAfterVisibleNormalNtkPage() {
        long elapsed = System.currentTimeMillis() - pageFinishedTime;
        return shouldFinishNormalNtkPageForTest(normalNtkPageCount, elapsed);
    }

    private void finishAfterNormalNtkPage(String purl, String currentUrl) {
        if(isFinishing)
            return;
        syncCaptchaCookiesToHttpClient(purl, currentUrl);
        finishWithVerifiedClearance();
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
        syncCaptchaCookiesToHttpClient(captchaLoadUrl, currentWebViewUrl);
        getHttpClient().clearNtkTransientLoads();
        Search.clearNtkResultCaches();
        detachCaptchaWebView();
        getHttpClient().saveClearanceToDisk();
        getHttpClient().markNtkAccessVerified();
        Log.d("CaptchaActivity", "finished with verified NTK clearance proof="
                + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " loadUrl=" + captchaLoadUrl
                + " currentUrl=" + currentWebViewUrl);
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
                && !isNtkApiUrl(challengeUrl)) {
            if(challengeUrl.startsWith("/"))
                return getHttpClient().getUrl(challengeUrl) + challengeUrl;
            if(getHttpClient().isNtkUrl(challengeUrl))
                return challengeUrl;
        }
        if(purl != null && purl.length() > 0
                && getHttpClient().isNtkUrl(purl)
                && !isNtkApiUrl(purl))
            return purl;
        String webtoonUrl = p == null ? null : p.getWebtoonUrl();
        if(webtoonUrl != null && webtoonUrl.length() > 0
                && getHttpClient().isNtkUrl(webtoonUrl)
                && !isNtkApiUrl(webtoonUrl))
            return webtoonUrl;
        String root = getHttpClient().getUrl();
        if(root == null || root.length() == 0 || !getHttpClient().isNtkUrl(root))
            root = p == null ? NTK_WEBTOON_URL : p.getNtkResolvedRoot();
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

    private boolean shouldBlockCaptchaNavigation(String url) {
        return shouldBlockCaptchaNavigationForTest(url);
    }

    static boolean shouldBlockCaptchaNavigationForTest(String url) {
        if(url == null || url.length() == 0)
            return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if(lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:"))
            return false;
        if(isAllowedCaptchaNavigationUrl(lower))
            return false;
        logBlockedCaptchaNavigation(url);
        return true;
    }

    private static void logBlockedCaptchaNavigation(String url) {
        try {
            android.util.Log.d("CaptchaActivity", "Blocked external captcha navigation: " + url);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isAllowedCaptchaNavigationUrl(String lowerUrl) {
        try {
            java.net.URI uri = new java.net.URI(lowerUrl);
            String host = uri.getHost();
            if(host == null || host.length() == 0)
                return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if(host.startsWith("ntk") || host.startsWith("sbxh") || host.startsWith("toonflix")
                    || host.endsWith(".toonflix.app"))
                return true;
            if("challenges.cloudflare.com".equals(host) || host.endsWith(".challenges.cloudflare.com"))
                return true;
            String path = uri.getPath();
            return ("cloudflare.com".equals(host) || host.endsWith(".cloudflare.com"))
                    && path != null
                    && path.toLowerCase(java.util.Locale.ROOT).contains("turnstile");
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
        return CaptchaCookiePolicy.extractCookieValue(text, cookieName);
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
            CaptchaCookiePolicy.forEachCookiePair(cookieStr, (key, value) -> {
                if("cf_clearance".equalsIgnoreCase(key) && isValidClearanceValue(value))
                    values.add(value);
            });
        }
        return values;
    }

    private boolean isValidClearanceValue(String value) {
        return CaptchaCookiePolicy.isValidClearanceValue(value);
    }

    private String[] cookieReadUrls(String purl, String currentUrl) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addCookieReadUrl(urls, currentUrl);
        addCookieReadUrl(urls, purl);
        addCookieReadUrl(urls, captchaLoadUrl);
        addCookieReadUrl(urls, p.getWebtoonUrl());
        addCookieReadUrl(urls, p.getUrl());
        addCookieReadUrl(urls, getHttpClient().getUrl());
        addCookieReadUrl(urls, NTK_WEBTOON_URL);
        addCookieReadUrl(urls, NTK_COMIC_URL);
        return urls.toArray(new String[0]);
    }

    private void addCookieReadUrl(Set<String> urls, String url) {
        if(url == null || url.length() == 0)
            return;
        urls.add(url);
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if(scheme != null && host != null && host.length() > 0)
                urls.add(scheme + "://" + host);
        } catch (Exception ignored) {
        }
    }

    private void syncCaptchaCookiesToHttpClient(String purl, String currentUrl) {
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.flush();
            for(String cookieUrl : cookieReadUrls(purl, currentUrl))
                getHttpClient().syncCookiesFromWebView(cookieUrl, true);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    @Override
    protected void onDestroy() {
        syncCaptchaCookiesToHttpClient(captchaLoadUrl, webView == null ? null : webView.getUrl());
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
        syncCaptchaCookiesToHttpClient(captchaLoadUrl, webView == null ? null : webView.getUrl());
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
            target.loadUrl("about:blank");
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

}

