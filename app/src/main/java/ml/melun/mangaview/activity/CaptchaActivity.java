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
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Arrays;
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
import ml.melun.mangaview.NtkDeviceIdentityManager;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.CustomHttpClient;
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
    private ProgressBar progressBar;
    public static final int RESULT_CAPTCHA = 15;
    public static final int REQUEST_CAPTCHA = 32;
    String domain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long TURNSTILE_CHECK_DELAY_MS = 0;
    private static final long TURNSTILE_CHECK_INTERVAL_MS = 500;
    private static final long TURNSTILE_MAX_WAIT_MS = 120000;
    private static final int TURNSTILE_MAX_STUCK_RELOADS = 2;
    private static final long COOKIE_READ_THROTTLE_MS = 350;
    private static final String NTK_ACCESS_VERIFY_PATH = "/api/manhwa-list?page=1&pageSize=1&withTotal=1";
    private static final String NTK_SEARCH_VERIFY_PATH = "/search?q=%EC%9B%90%ED%94%BC%EC%8A%A4&kind=manhwa";
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
            "return JSON.stringify({type:'iframe',x:t.x,y:t.y,w:t.w,h:t.h,vw:window.innerWidth||document.documentElement.clientWidth||0,vh:window.innerHeight||document.documentElement.clientHeight||0,sig:iframe.getAttribute('src')||iframe.id||iframe.name||''});" +
            "}" +
            "}" +
            "var turnstileDiv=document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"]');" +
            "if(turnstileDiv){" +
            "var rect=turnstileDiv.getBoundingClientRect();" +
            "if(rect.width>10&&rect.height>10){" +
            "return JSON.stringify({type:'div',x:rect.left+rect.width/2,y:rect.top+rect.height/2,w:rect.width,h:rect.height,vw:window.innerWidth||document.documentElement.clientWidth||0,vh:window.innerHeight||document.documentElement.clientHeight||0,sig:turnstileDiv.id||turnstileDiv.className||turnstileDiv.getAttribute('data-sitekey')||''});" +
            "}" +
            "}" +
            "var host=(location.hostname||'').toLowerCase();" +
            "var ntkText=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ');" +
            "if(/trash0607/i.test(ntkText+' '+(document.title||'')))return JSON.stringify({type:'trash0607'});" +
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
            "return JSON.stringify({type:'iframe',x:t.x,y:t.y,w:t.w,h:t.h,vw:window.innerWidth||document.documentElement.clientWidth||0,vh:window.innerHeight||document.documentElement.clientHeight||0});" +
            "}" +
            "}" +
            "}" +
            "}" +
            "return JSON.stringify({type:'none'});" +
            "})();";
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){" +
            "if(!window.NtkQuicBridge)return;" +
            "window.__ntkBridgeFetchInstalled=1;" +
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
            "function bodyBase64Async(b){return new Promise(function(resolve){try{if(b&&window.Blob&&b instanceof Blob){var fr=new FileReader();fr.onload=function(){resolve(bodyBase64(fr.result));};fr.onerror=function(){resolve('');};fr.readAsArrayBuffer(b);return;}resolve(bodyBase64(b));}catch(e){resolve('');}});}" +
            "function contentTypeFromBody(b){try{return b&&b.type?String(b.type):'';}catch(e){return '';}}" +
            "function addBodyContentType(h,b){try{var t=contentTypeFromBody(b);if(t&&!h['content-type']&&!h['Content-Type'])h['content-type']=t;}catch(e){}return h;}" +
            "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}" +
            "function collectHeaders(input,init){" +
            "var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;" +
            "}" +
            "function chromeMajor(){var m=String(navigator.userAgent||'').match(/Chrome\\/(\\d+)/);return m?m[1]:'116';}" +
            "function isMobileUa(){return /Android|Mobile/i.test(String(navigator.userAgent||''));}" +
            "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';if(!h['Sec-Fetch-Dest']&&!h['sec-fetch-dest'])h['Sec-Fetch-Dest']='empty';if(!h['Sec-Fetch-Mode']&&!h['sec-fetch-mode'])h['Sec-Fetch-Mode']='cors';if(!h['Sec-Fetch-Site']&&!h['sec-fetch-site'])h['Sec-Fetch-Site']='same-origin';var v=chromeMajor(),mobile=isMobileUa();if(!h['sec-ch-ua'])h['sec-ch-ua']=mobile?'\\\"Chromium\\\";v=\\\"'+v+'\\\", \\\"Android WebView\\\";v=\\\"'+v+'\\\", \\\"Not A(Brand\\\";v=\\\"24\\\"':'\\\"Chromium\\\";v=\\\"'+v+'\\\", \\\"Google Chrome\\\";v=\\\"'+v+'\\\", \\\"Not_A Brand\\\";v=\\\"24\\\"';if(!h['sec-ch-ua-mobile'])h['sec-ch-ua-mobile']=mobile?'?1':'?0';if(!h['sec-ch-ua-platform'])h['sec-ch-ua-platform']=mobile?'\\\"Android\\\"':'\\\"Windows\\\"';}catch(e){}return h;}" +
            "function textFromBase64(b){try{return new TextDecoder().decode(bytesFromBase64(b));}catch(e){try{return decodeURIComponent(escape(atob(b||'')));}catch(_){try{return atob(b||'');}catch(__){return '';}}}}" +
            "function sameRootChallengePost(a,m){try{return a&&a.protocol==='https:'&&a.host===location.host&&a.pathname.indexOf('/cdn-cgi/challenge-platform/')===0&&String(m||'GET').toUpperCase()==='POST';}catch(e){return false;}}" +
            "function sameRootAdApiPost(a,m){try{return a&&a.protocol==='https:'&&a.host===location.host&&(a.pathname==='/api/ad/challenge'||a.pathname==='/api/ad/canary'||a.pathname==='/api/ad/ack')&&String(m||'GET').toUpperCase()==='POST';}catch(e){return false;}}" +
            "window.__ntkBridgeFetch=function(input,init){" +
            "var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';" +
            "var method=(init&&init.method)||(input&&input.method)||'GET';" +
            "return new Promise(function(resolve,reject){try{" +
            "var absolute=new URL(url,location.href).href;" +
            "var body=init&&init.body;bodyBase64Async(body).then(function(encoded){try{var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addBodyContentType(addDefaultHeaders(collectHeaders(input,init)),body)),encoded);" +
            "var res=JSON.parse(raw||'{}');" +
            "if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');" +
            "resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));" +
            "}catch(e){reject(e);}});" +
            "}catch(e){reject(e);}});" +
            "};" +
            "if(window.fetch&&(!window.__ntkNativeFetch||window.fetch.__ntkBridgeWrapped!==1))window.__ntkNativeFetch=window.fetch;" +
            "var observeNativeAck=true;" +
            "var nativeFetch=window.__ntkNativeFetch;" +
            "if(observeNativeAck&&nativeFetch&&(!window.fetch||window.fetch.__ntkBridgeWrapped!==1)){var wrappedFetch=function(input,init){" +
            "try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';var absolute=new URL(url,location.href);var method=(init&&init.method)||(input&&input.method)||'GET';" +
            "if(sameRootChallengePost(absolute,method))return window.__ntkBridgeFetch(absolute.href,init||{});" +
            "if(sameRootAdApiPost(absolute,method))return window.__ntkBridgeFetch(absolute.href,init||{});" +
            "if(absolute.protocol==='https:'&&absolute.host===location.host&&(absolute.pathname==='/api/ad/ack'||absolute.pathname==='/api/ad/challenge')&&String(method).toUpperCase()==='POST'){var reqBody=init&&init.body,fetchArgs=arguments,self=this;return bodyBase64Async(reqBody).then(function(req64){return nativeFetch.apply(self,fetchArgs).then(function(r){try{window.NtkQuicBridge.recordAckStatus(absolute.href,'POST',r.status||0);}catch(e){}try{var rc=r.clone();rc.text().then(function(txt){try{window.NtkQuicBridge.recordAckExchange(absolute.href,'POST',r.status||0,req64||'',txt||'','fetch');}catch(e){}});}catch(e){}return r;});});}" +
            "}catch(e){}" +
            "return nativeFetch.apply(this,arguments);" +
            "};try{wrappedFetch.__ntkBridgeWrapped=1;}catch(e){}window.fetch=wrappedFetch;}" +
            "var xp=window.XMLHttpRequest&&XMLHttpRequest.prototype,xo=xp&&xp.open,xs=xp&&xp.send,xh=xp&&xp.setRequestHeader;" +
            "function fireXhr(xhr,n){try{var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);}catch(e){}}" +
            "if(observeNativeAck&&xp&&xo&&xs){if(!xp.__ntkOpenOriginal||xp.open.__ntkBridgeWrapped!==1)xp.__ntkOpenOriginal=xp.open;if(!xp.__ntkSendOriginal||xp.send.__ntkBridgeWrapped!==1)xp.__ntkSendOriginal=xp.send;if(xp.setRequestHeader&&(!xp.__ntkSetHeaderOriginal||xp.setRequestHeader.__ntkBridgeWrapped!==1))xp.__ntkSetHeaderOriginal=xp.setRequestHeader;" +
            "xo=xp.__ntkOpenOriginal;xs=xp.__ntkSendOriginal;xh=xp.__ntkSetHeaderOriginal;" +
            "if(xp.open.__ntkBridgeWrapped!==1){var wrappedOpen=function(m,u,a,user,pw){try{this.__ntkq={method:m||'GET',url:u||'',headers:{}};}catch(e){}return xo.apply(this,arguments);};try{wrappedOpen.__ntkBridgeWrapped=1;}catch(e){}xp.open=wrappedOpen;}" +
            "if(xh&&xp.setRequestHeader.__ntkBridgeWrapped!==1){var wrappedSetHeader=function(k,v){try{if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);}catch(e){}return xh.apply(this,arguments);};try{wrappedSetHeader.__ntkBridgeWrapped=1;}catch(e){}xp.setRequestHeader=wrappedSetHeader;}" +
            "if(xp.send.__ntkBridgeWrapped!==1){var wrappedSend=function(body){var meta=this.__ntkq||{},absolute;try{absolute=new URL(meta.url||'',location.href);}catch(e){}" +
            "if(sameRootChallengePost(absolute,meta.method)){try{var xhr=this,h=addDefaultHeaders(meta.headers||{}),raw=window.NtkQuicBridge.request(absolute.href,String(meta.method||'POST'),JSON.stringify(addBodyContentType(h,body)),bodyBase64(body));var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');var txt=textFromBase64(res.bodyBase64||'');try{Object.defineProperty(xhr,'readyState',{value:4,configurable:true});Object.defineProperty(xhr,'status',{value:res.status||0,configurable:true});Object.defineProperty(xhr,'statusText',{value:res.statusText||'',configurable:true});Object.defineProperty(xhr,'responseText',{value:txt,configurable:true});Object.defineProperty(xhr,'response',{value:txt,configurable:true});}catch(_){try{xhr.__ntkqResponseText=txt;}catch(__){}}fireXhr(xhr,'readystatechange');fireXhr(xhr,'load');fireXhr(xhr,'loadend');}catch(e){try{Object.defineProperty(this,'readyState',{value:4,configurable:true});Object.defineProperty(this,'status',{value:0,configurable:true});}catch(_){}fireXhr(this,'readystatechange');fireXhr(this,'error');fireXhr(this,'loadend');}return;}" +
            "if(sameRootAdApiPost(absolute,meta.method)){try{var xhr2=this,h2=addDefaultHeaders(meta.headers||{}),raw2=window.NtkQuicBridge.request(absolute.href,String(meta.method||'POST'),JSON.stringify(addBodyContentType(h2,body)),bodyBase64(body));var res2=JSON.parse(raw2||'{}');if(!res2.ok)throw new Error(res2.error||'NTK QUIC bridge failed');var txt2=textFromBase64(res2.bodyBase64||'');try{Object.defineProperty(xhr2,'readyState',{value:4,configurable:true});Object.defineProperty(xhr2,'status',{value:res2.status||0,configurable:true});Object.defineProperty(xhr2,'statusText',{value:res2.statusText||'',configurable:true});Object.defineProperty(xhr2,'responseText',{value:txt2,configurable:true});Object.defineProperty(xhr2,'response',{value:txt2,configurable:true});}catch(_){try{xhr2.__ntkqResponseText=txt2;}catch(__){}}fireXhr(xhr2,'readystatechange');fireXhr(xhr2,'load');fireXhr(xhr2,'loadend');}catch(e){try{Object.defineProperty(this,'readyState',{value:4,configurable:true});Object.defineProperty(this,'status',{value:0,configurable:true});}catch(_){}fireXhr(this,'readystatechange');fireXhr(this,'error');fireXhr(this,'loadend');}return;}" +
            "if(absolute&&absolute.protocol==='https:'&&absolute.host===location.host&&(absolute.pathname==='/api/ad/ack'||absolute.pathname==='/api/ad/challenge')&&String(meta.method||'GET').toUpperCase()==='POST'){" +
            "try{var xhr=this;var ackUrl=absolute.href;var ackMethod=String(meta.method||'POST'),reqBody=bodyBase64(body);xhr.addEventListener('loadend',function(){try{window.NtkQuicBridge.recordAckStatus(ackUrl,ackMethod,xhr.status||0);}catch(e){}try{window.NtkQuicBridge.recordAckExchange(ackUrl,ackMethod,xhr.status||0,reqBody||'',String(xhr.responseText||''),'xhr');}catch(e){}});}catch(e){}}" +
            "return xs.apply(this,arguments);};try{wrappedSend.__ntkBridgeWrapped=1;}catch(e){}xp.send=wrappedSend;}}" +
            "if(!window.__ntkBridgeFetchReadyLogged){window.__ntkBridgeFetchReadyLogged=1;try{console.log('__NTK_BRIDGE_FETCH_READY__');}catch(e){}}" +
            "})();";
    private long pageFinishedTime = 0;
    private long lastAttemptTime = 0;
    private static final long FIRST_CLICK_DELAY_MS = 0;
    private static final long RETRY_MIN_MS = 100;
    private static final long RETRY_MAX_MS = 300;
    private static final long TURNSTILE_EVALUATION_MIN_INTERVAL_MS = 600L;
    private static final long TURNSTILE_IDLE_RECHECK_MS = 1_000L;
    private static final long TURNSTILE_REPEAT_TOUCH_INTERVAL_MS = 2_500L;
    private static final int TURNSTILE_MAX_TOUCHES_PER_WIDGET = 18;
    private boolean isFirstAttempt = true;
    private volatile boolean isFinishing = false;
    private Set<String> initialClearanceValues = new HashSet<>();
    private int normalNtkPageCount = 0;
    private boolean turnstileAutoClickStarted = false;
    private boolean accessVerificationInFlight = false;
    private boolean waitingForNtkAdAckBeforeFinish = false;
    private boolean turnstileEvaluationInFlight = false;
    private boolean disableTurnstileAutomationForDiagnostics = false;
    private boolean disableNtkRootBootstrapForDiagnostics = false;
    private long lastTurnstileEvaluationAt = 0;
    private long lastNtkNormalProbeAt = 0;
    private long lastTurnstileTouchAt = 0;
    private long lastNtkCaptchaEnvironmentLogAt = 0;
    private String lastTurnstileClickSignature = "";
    private long lastTurnstileRepeatTouchAt = 0;
    private int turnstileRepeatTouchCount = 0;
    private int turnstileStuckReloadCount = 0;
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
    private boolean retriedCaptchaWithNtkRootBootstrap = false;
    private String ntkRootBootstrapReturnUrl = null;
    private long ntkRootBootstrapStartedAt = 0L;
    private volatile boolean ntkRootBootstrapMainFrameError = false;
    private boolean ntkReloadedAckTargetAfterStaleRootError = false;
    private static final long NTK_ROOT_BOOTSTRAP_STAGE_LOG_MS = 8_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.onCreate(savedInstanceState);
        Context context = this;
        Intent intent = getIntent();
        String purl = p.getUrl();
        boolean ntkSite = p != null && p.isNtkSite();
        boolean forceWebViewDebuggingForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkEnableWebViewDebuggingForDiagnostics", false);
        WebView.setWebContentsDebuggingEnabled(shouldEnableWebContentsDebuggingForTest(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                ntkSite,
                forceWebViewDebuggingForDiagnostics));

        setContentView(R.layout.activity_captcha);
        getHttpClient().setCloudflareCaptchaActive(true);

        String url = resolveCaptchaUrl(intent, purl);
        if(shouldFinishRedundantNtkCaptcha(url, "onCreate"))
            return;

        infoText = this.findViewById(R.id.infoText);
        progressBar = this.findViewById(R.id.progressBar);
        if(progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }
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
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        disableTurnstileAutomationForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkDisableTurnstileAutomationForDiagnostics", false);
        disableNtkRootBootstrapForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkDisableRootBootstrapForDiagnostics", false);
        boolean relaxWindowSettingsForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkRelaxWindowSettingsForDiagnostics", false);
        boolean useChromeUaMetadataForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkUseChromeUaMetadataForDiagnostics", false);
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
        settings.setJavaScriptCanOpenWindowsAutomatically(relaxWindowSettingsForDiagnostics);
        settings.setSupportMultipleWindows(relaxWindowSettingsForDiagnostics);
        if(relaxWindowSettingsForDiagnostics) {
            android.util.Log.d("CaptchaActivity", "Relaxing WebView window settings for NTK diagnostics");
        }

        boolean useRawWebViewUserAgentForDiagnostics = ntkSite
                && intent.getBooleanExtra("ntkUseRawWebViewUserAgentForDiagnostics", false);
        String userAgentOverride = ntkSite && intent.hasExtra("ntkCaptchaUserAgent")
                ? intent.getStringExtra("ntkCaptchaUserAgent") : null;
        String realChromeUA = useRawWebViewUserAgentForDiagnostics
                ? settings.getUserAgentString()
                : captchaUserAgent(
                        settings.getUserAgentString(),
                        ntkSite,
                        userAgentOverride,
                        ntkSite ? getHttpClient().agent : null);
        if(useRawWebViewUserAgentForDiagnostics) {
            android.util.Log.d("CaptchaActivity", "Using raw WebView UA for NTK diagnostics: " + realChromeUA);
        }
        settings.setUserAgentString(realChromeUA);
        if(useChromeUaMetadataForDiagnostics)
            applyChromeUaMetadataForDiagnostics(settings, realChromeUA);
        getHttpClient().setUserAgent(settings.getUserAgentString());
        if(ntkSite && NtkQuicFetcher.isAvailable())
            webView.addJavascriptInterface(new NtkQuicJavascriptBridge(), "NtkQuicBridge");

        CookieManager cookiem = CookieManager.getInstance();
        cookiem.setAcceptCookie(true);
        cookiem.setAcceptThirdPartyCookies(webView, true);
        initialClearanceValues = readClearanceValues(cookiem, purl, p.getWebtoonUrl(), p.getUrl(), NTK_WEBTOON_URL, NTK_COMIC_URL);
        // Do NOT remove all cookies — previous valid cf_clearance should be preserved
        if(!getHttpClient().hasCloudflareClearance()
                && initialClearanceValues != null
                && !initialClearanceValues.isEmpty()) {
            getHttpClient().clearCloudflareWebViewCookies(purl, p.getWebtoonUrl(), p.getUrl(), NTK_WEBTOON_URL, NTK_COMIC_URL);
        } else if(p != null && p.isNtkSite()) {
            android.util.Log.d("CaptchaActivity", "Skipping NTK WebView CF clear on create: appClearance="
                    + getHttpClient().hasCloudflareClearance()
                    + ",visibleClearance=" + (initialClearanceValues != null && !initialClearanceValues.isEmpty()));
        }

        // WebChromeClient for JS console and alerts
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                if(shouldSuppressCaptchaConsoleMessage(msg))
                    return true;
                android.util.Log.d("CaptchaActivity", "JS Console [" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + "] " + msg);
                if(!disableTurnstileAutomationForDiagnostics
                        && msg != null && msg.contains("__TURNSTILE_CB__")) {
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

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if(progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
                super.onProgressChanged(view, newProgress);
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
                if(view != null)
                    view.requestFocus();
                handler.removeCallbacksAndMessages(null);
                hideCaptchaLoadError();
                if(progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    progressBar.setVisibility(View.VISIBLE);
                }
                pageFinishedTime = 0;
                lastAttemptTime = 0;
                lastCookieReadAt = 0;
                normalNtkPageCount = 0;
                turnstileAutoClickStarted = false;
                isFirstAttempt = true;
                lastAttemptTime = 0;
                turnstileEvaluationInFlight = false;
                lastTurnstileEvaluationAt = 0;
                lastNtkNormalProbeAt = 0;
                lastTurnstileClickSignature = "";
                lastTurnstileRepeatTouchAt = 0;
                turnstileRepeatTouchCount = 0;
                lastNtkCaptchaEnvironmentLogAt = 0;
                installShadowHookIfAllowed();
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
                if(isCurrentNtkRootBootstrapUrl(failingUrl)) {
                    ntkRootBootstrapMainFrameError = true;
                    android.util.Log.d("CaptchaActivity", "NTK root bootstrap main-frame error: " + failingUrl);
                }
                if(shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                        p != null && p.isNtkSite(),
                        failingUrl,
                        captchaLoadUrl,
                        getHttpClient().hasCloudflareClearance(),
                        waitingForNtkAdAckBeforeFinish)) {
                    android.util.Log.d("CaptchaActivity",
                            "Ignoring stale NTK root bootstrap error after clearance: " + failingUrl);
                    ntkRootBootstrapReturnUrl = null;
                    ntkRootBootstrapStartedAt = 0L;
                    if(!ntkReloadedAckTargetAfterStaleRootError && webView != null
                            && captchaLoadUrl != null && captchaLoadUrl.length() > 0) {
                        ntkReloadedAckTargetAfterStaleRootError = true;
                        webView.loadUrl(captchaLoadUrl);
                        installShadowHookIfAllowed();
                    }
                    return;
                }
                if(retryNtkCaptchaWithRootBootstrapIfNeeded(failingUrl))
                    return;
                if(retryCaptchaLoadWithProxyIfNeeded(failingUrl))
                    return;
                if(retryCaptchaLoadWithQuicIfNeeded(failingUrl))
                    return;
                if(retryCaptchaLoadWithoutProxyIfNeeded(failingUrl))
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
                if(request != null && p != null && p.isNtkSite()) {
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
                    String reason = errorResponse == null ? "" : errorResponse.getReasonPhrase();
                    if(request.isForMainFrame() && isCurrentNtkRootBootstrapUrl(url) && code >= 400) {
                        ntkRootBootstrapMainFrameError = true;
                        android.util.Log.d("CaptchaActivity", "NTK root bootstrap main-frame HTTP error: code="
                                + code + ",url=" + url);
                    }
                    android.util.Log.d("CaptchaActivity", "NTK WebView HTTP error: mainFrame="
                            + request.isForMainFrame()
                            + ",method=" + request.getMethod()
                            + ",code=" + code
                            + ",reason=" + reason
                            + ",url=" + url);
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
                if(p != null && p.isNtkSite())
                    installShadowHookIfAllowed();
                if(readCookiesAndFinish(cookiem, purl, url, true))
                    return;
                scheduleNtkNormalPageFinishProbe(250L);

                // Attempt click immediately when resources load (Turnstile iframe appears mid-load)
                // Do NOT wait for pageFinishedTime - iframe loads before onPageFinished
                long now = System.currentTimeMillis();
                long requiredInterval = isFirstAttempt ? FIRST_CLICK_DELAY_MS : (RETRY_MIN_MS + (long)(Math.random() * (RETRY_MAX_MS - RETRY_MIN_MS)));
                if(!disableTurnstileAutomationForDiagnostics
                        && now - lastAttemptTime > requiredInterval) {
                    attemptTurnstileClick();
                }

                super.onLoadResource(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if(isFinishing) return;
                if(progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(100);
                    progressBar.setVisibility(View.GONE);
                }
                if(finishNtkRootBootstrapIfNeeded(url))
                    return;
                if(readCookiesAndFinish(cookiem, purl, url))
                    return;

                pageFinishedTime = System.currentTimeMillis();
                // Start Turnstile auto-click routine
                if(!disableTurnstileAutomationForDiagnostics)
                    startTurnstileAutoClick();
                scheduleNtkNormalPageFinishProbes();
                scheduleNtkCaptchaEnvironmentLogs();

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
        retriedCaptchaWithNtkRootBootstrap = false;
        ntkRootBootstrapReturnUrl = null;
        hideCaptchaLoadError();
        clearWebViewProxy();
        if(webView != null)
            loadCaptchaUrl(captchaLoadUrl);
    }

    private boolean shouldFinishRedundantNtkCaptcha(String url, String source) {
        if(p == null || !p.isNtkSite())
            return false;
        if(!getHttpClient().hasRecentNtkAccessVerification())
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
                turnstileStuckReloadCount = 0;
                retriedCaptchaWithQuic = false;
                quicCaptchaLoadInFlight = false;
                quicCaptchaHtmlActive = false;
                retriedCaptchaWithProxy = false;
                retriedCaptchaWithoutProxy = false;
                turnstileStuckReloadCount = 0;
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

    static String ntkCaptchaUserAgentForTest(String defaultUserAgent, String overrideUserAgent,
                                             String storedUserAgent) {
        return captchaUserAgent(defaultUserAgent, true, overrideUserAgent, storedUserAgent);
    }

    static boolean shouldEnableWebContentsDebuggingForTest(boolean debuggable, boolean ntkSite) {
        return shouldEnableWebContentsDebuggingForTest(debuggable, ntkSite, false);
    }

    static boolean shouldEnableWebContentsDebuggingForTest(boolean debuggable, boolean ntkSite,
                                                           boolean forceDiagnostics) {
        return debuggable && (!ntkSite || forceDiagnostics);
    }

    private static String captchaUserAgent(String defaultUserAgent, boolean ntkSite) {
        return captchaUserAgent(defaultUserAgent, ntkSite, null, null);
    }

    private static String captchaUserAgent(String defaultUserAgent, boolean ntkSite,
                                           String overrideUserAgent, String storedUserAgent) {
        if(overrideUserAgent != null && overrideUserAgent.trim().length() > 0)
            return overrideUserAgent.trim();
        String cleanedDefault = cleanedCaptchaUserAgent(defaultUserAgent);
        if(ntkSite && storedUserAgent != null && storedUserAgent.trim().length() > 0) {
            String stored = storedUserAgent.trim();
            if(!shouldReplaceStoredNtkCaptchaUserAgent(cleanedDefault, stored))
                return stored;
            android.util.Log.d("CaptchaActivity", "Replacing stale NTK captcha UA: stored="
                    + stored + ",default=" + cleanedDefault);
        }
        return cleanedDefault;
    }

    private static String cleanedCaptchaUserAgent(String defaultUserAgent) {
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

    private static boolean shouldReplaceStoredNtkCaptchaUserAgent(String defaultUserAgent, String storedUserAgent) {
        int defaultMajor = chromeMajor(defaultUserAgent);
        int storedMajor = chromeMajor(storedUserAgent);
        if(defaultMajor <= 0 || storedMajor <= 0)
            return false;
        return storedMajor + 4 < defaultMajor;
    }

    private static int chromeMajor(String userAgent) {
        if(userAgent == null)
            return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Chrome/(\\d+)")
                .matcher(userAgent);
        if(!matcher.find())
            return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeFullVersion(String userAgent) {
        if(userAgent == null)
            return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Chrome/([0-9.]+)")
                .matcher(userAgent);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void applyChromeUaMetadataForDiagnostics(WebSettings settings, String userAgent) {
        try {
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                android.util.Log.d("CaptchaActivity", "Chrome UA metadata diagnostics unsupported");
                return;
            }
            int major = chromeMajor(userAgent);
            String majorVersion = major > 0 ? String.valueOf(major) : "124";
            String fullVersion = chromeFullVersion(userAgent);
            if(fullVersion.length() == 0)
                fullVersion = majorVersion + ".0.0.0";
            UserAgentMetadata.BrandVersion chromium =
                    new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Chromium")
                            .setMajorVersion(majorVersion)
                            .setFullVersion(fullVersion)
                            .build();
            UserAgentMetadata.BrandVersion chrome =
                    new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Google Chrome")
                            .setMajorVersion(majorVersion)
                            .setFullVersion(fullVersion)
                            .build();
            UserAgentMetadata.BrandVersion notBrand =
                    new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Not A(Brand")
                            .setMajorVersion("24")
                            .setFullVersion("24.0.0.0")
                            .build();
            UserAgentMetadata metadata = new UserAgentMetadata.Builder()
                    .setBrandVersionList(Arrays.asList(chromium, chrome, notBrand))
                    .setFullVersion(fullVersion)
                    .setPlatform("Android")
                    .setPlatformVersion("")
                    .setArchitecture("")
                    .setModel("")
                    .setMobile(true)
                    .build();
            WebSettingsCompat.setUserAgentMetadata(settings, metadata);
            android.util.Log.d("CaptchaActivity", "Using Chrome UA metadata for NTK diagnostics: major="
                    + majorVersion + ",full=" + fullVersion);
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "Failed Chrome UA metadata diagnostics", e);
        }
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
        if(startNtkRootBootstrapBeforeDeepLinkIfNeeded(url))
            return;
        loadCaptchaUrlDirect(url);
    }

    private boolean startNtkRootBootstrapBeforeDeepLinkIfNeeded(String url) {
        if(p == null || !p.isNtkSite() || webView == null || retriedCaptchaWithNtkRootBootstrap)
            return false;
        if(disableNtkRootBootstrapForDiagnostics) {
            android.util.Log.d("CaptchaActivity", "Skipping NTK root bootstrap for diagnostics: " + url);
            return false;
        }
        if(getHttpClient().hasCloudflareClearance())
            return false;
        if(initialClearanceValues != null && !initialClearanceValues.isEmpty())
            return false;
        if(!isNtkEpisodeDeepLink(url))
            return false;
        String rootUrl = ntkRootBootstrapUrl(url);
        if(rootUrl == null || rootUrl.length() == 0 || rootUrl.equals(url))
            return false;
        retriedCaptchaWithNtkRootBootstrap = true;
        ntkRootBootstrapReturnUrl = url;
        ntkRootBootstrapStartedAt = SystemClock.elapsedRealtime();
        ntkRootBootstrapMainFrameError = false;
        ntkReloadedAckTargetAfterStaleRootError = false;
        hideCaptchaLoadError();
        quicCaptchaHtmlActive = false;
        android.util.Log.d("CaptchaActivity", "Starting NTK captcha via root bootstrap: root="
                + rootUrl + ",return=" + url);
        webView.loadUrl(rootUrl);
        installShadowHookIfAllowed();
        scheduleNtkRootBootstrapStageLog(rootUrl, url, ntkRootBootstrapStartedAt);
        return true;
    }

    private void loadCaptchaUrlDirect(String url) {
        quicCaptchaHtmlActive = false;
        webView.loadUrl(url);
        installShadowHookIfAllowed();
    }

    private void installShadowHookIfAllowed() {
        if(disableTurnstileAutomationForDiagnostics || webView == null)
            return;
        webView.evaluateJavascript(SHADOW_HOOK_JS + NTK_QUIC_BRIDGE_JS, null);
    }

    private boolean retryNtkCaptchaWithRootBootstrapIfNeeded(String failingUrl) {
        if(p == null || !p.isNtkSite() || webView == null || retriedCaptchaWithNtkRootBootstrap)
            return false;
        if(disableNtkRootBootstrapForDiagnostics)
            return false;
        String target = failingUrl != null && failingUrl.length() > 0 ? failingUrl : captchaLoadUrl;
        if(!isNtkEpisodeDeepLink(target))
            return false;
        String rootUrl = ntkRootBootstrapUrl(target);
        if(rootUrl == null || rootUrl.length() == 0 || rootUrl.equals(target))
            return false;
        retriedCaptchaWithNtkRootBootstrap = true;
        ntkRootBootstrapReturnUrl = target;
        ntkRootBootstrapStartedAt = SystemClock.elapsedRealtime();
        ntkRootBootstrapMainFrameError = false;
        ntkReloadedAckTargetAfterStaleRootError = false;
        hideCaptchaLoadError();
        quicCaptchaHtmlActive = false;
        android.util.Log.d("CaptchaActivity", "Retrying NTK captcha via root bootstrap: root="
                + rootUrl + ",return=" + target);
        webView.loadUrl(rootUrl);
        installShadowHookIfAllowed();
        scheduleNtkRootBootstrapStageLog(rootUrl, target, ntkRootBootstrapStartedAt);
        return true;
    }

    private boolean finishNtkRootBootstrapIfNeeded(String url) {
        if(ntkRootBootstrapReturnUrl == null || webView == null)
            return false;
        String rootUrl = ntkRootBootstrapUrl(ntkRootBootstrapReturnUrl);
        if(rootUrl == null || url == null || !sameUrlIgnoringTrailingSlash(rootUrl, url))
            return false;
        if(ntkRootBootstrapMainFrameError) {
            android.util.Log.d("CaptchaActivity", "NTK root bootstrap not finished after main-frame error: " + url);
            return false;
        }
        String returnUrl = ntkRootBootstrapReturnUrl;
        ntkRootBootstrapReturnUrl = null;
        ntkRootBootstrapStartedAt = 0L;
        syncCaptchaCookiesToHttpClient(rootUrl, returnUrl);
        android.util.Log.d("CaptchaActivity", "NTK root bootstrap finished; loading original captcha URL: " + returnUrl);
        webView.loadUrl(returnUrl);
        installShadowHookIfAllowed();
        return true;
    }

    private void scheduleNtkRootBootstrapStageLog(String rootUrl, String returnUrl, long startedAt) {
        handler.postDelayed(() -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            if(ntkRootBootstrapReturnUrl == null || ntkRootBootstrapStartedAt != startedAt)
                return;
            long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            String currentUrl = webView.getUrl();
            android.util.Log.d("CaptchaActivity", "NTK root bootstrap stage timeout: root="
                    + rootUrl
                    + ",return=" + returnUrl
                    + ",current=" + currentUrl
                    + ",mainFrameError=" + ntkRootBootstrapMainFrameError
                    + ",clearance=" + getHttpClient().hasCloudflareClearance()
                    + ",elapsedMs=" + elapsedMs);
            logNtkCaptchaEnvironment("root-bootstrap-stage-timeout");
        }, NTK_ROOT_BOOTSTRAP_STAGE_LOG_MS);
    }

    private static boolean isNtkEpisodeDeepLink(String url) {
        if(url == null || url.length() == 0)
            return false;
        try {
            String path = new URL(url).getPath();
            return path != null && (path.startsWith("/webtoon/") || path.startsWith("/manhwa/"));
        } catch(Exception e) {
            return false;
        }
    }

    private static String ntkRootBootstrapUrl(String url) {
        if(url == null || url.length() == 0)
            return null;
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getHost() + "/";
        } catch(Exception e) {
            return null;
        }
    }

    private static boolean sameUrlIgnoringTrailingSlash(String a, String b) {
        if(a == null || b == null)
            return false;
        while(a.endsWith("/") && a.length() > 1)
            a = a.substring(0, a.length() - 1);
        while(b.endsWith("/") && b.length() > 1)
            b = b.substring(0, b.length() - 1);
        return a.equals(b);
    }

    private boolean isCurrentNtkRootBootstrapUrl(String url) {
        if(ntkRootBootstrapReturnUrl == null || url == null)
            return false;
        String rootUrl = ntkRootBootstrapUrl(ntkRootBootstrapReturnUrl);
        return rootUrl != null && sameUrlIgnoringTrailingSlash(rootUrl, url);
    }

    static boolean shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
            boolean ntkSite,
            String failingUrl,
            String targetUrl,
            boolean hasClearance,
            boolean waitingForAck) {
        if(!ntkSite || failingUrl == null || targetUrl == null)
            return false;
        if(!hasClearance && !waitingForAck)
            return false;
        if(!isNtkEpisodeDeepLink(targetUrl))
            return false;
        String rootUrl = ntkRootBootstrapUrl(targetUrl);
        return rootUrl != null && sameUrlIgnoringTrailingSlash(rootUrl, failingUrl);
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
                installShadowHookIfAllowed();
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

    private static String summarizeCookieHeaderForLog(String cookieHeader) {
        if(cookieHeader == null || cookieHeader.length() == 0)
            return "none";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for(String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if(eq <= 0)
                continue;
            String name = part.substring(0, eq).trim();
            if(name.length() > 0)
                names.add(name);
        }
        return "count=" + names.size() + ",names=" + names;
    }

    private static String previewBridgeBodyForLog(byte[] body) {
        if(body == null || body.length == 0)
            return "";
        int length = Math.min(body.length, 120);
        String text = new String(body, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        return text.replace('\n', ' ').replace('\r', ' ');
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
        public void recordAckStatus(String url, String method, int status) {
            if(isFinishing || isDestroyed())
                return;
            if(method == null || !"POST".equalsIgnoreCase(method))
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(!"/api/ad/ack".equals(path) && !"/api/ad/challenge".equals(path))
                    return;
                if(status != 200) {
                    android.util.Log.d("CaptchaActivity", "NTK WebView ACK status observed status="
                            + status + ",url=" + url);
                    return;
                }
                String scope = ntkVerificationUrl(captchaLoadUrl, captchaLoadUrl);
                if(scope == null || scope.length() == 0)
                    return;
                if(!"/api/ad/ack".equals(path))
                    return;
                getHttpClient().rememberExternalNtkServerAckSuccess(scope, "captcha-webview-ack-200");
                android.util.Log.d("CaptchaActivity", "NTK WebView ACK proof recorded status="
                        + status + ",scope=" + scope + ",url=" + url);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "NTK WebView ACK proof record failed: " + url, e);
            }
        }

        @JavascriptInterface
        public void recordAckExchange(String url, String method, int status,
                                      String requestBodyBase64, String responseBody,
                                      String transport) {
            if(isFinishing || isDestroyed())
                return;
            if(method == null || !"POST".equalsIgnoreCase(method))
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(!"/api/ad/ack".equals(path) && !"/api/ad/challenge".equals(path))
                    return;
                String requestPreview = "";
                if(requestBodyBase64 != null && requestBodyBase64.length() > 0) {
                    try {
                        byte[] requestBody = Base64.decode(requestBodyBase64, Base64.DEFAULT);
                        requestPreview = previewBridgeBodyForLog(requestBody);
                    } catch (Exception ignored) {
                    }
                }
                String responsePreview = responseBody == null ? "" : responseBody;
                if(responsePreview.length() > 360)
                    responsePreview = responsePreview.substring(0, 360);
                android.util.Log.d("CaptchaActivity", "NTK WebView ACK exchange observed path="
                        + path + ",status=" + status + ",transport=" + transport
                        + ",request=" + requestPreview + ",response=" + responsePreview);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "NTK WebView ACK exchange record failed: "
                        + url, e);
            }
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(isFinishing || isDestroyed())
                return bridgeError("captcha activity finishing");
            if(!NtkQuicFetcher.isAvailable() || !isNtkProtectedHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                byte[] body = bodyBase64 == null || bodyBase64.length() == 0
                        ? new byte[0] : Base64.decode(bodyBase64, Base64.DEFAULT);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                String cookieHeader = captchaCookieHeaderFor(url);
                android.util.Log.d("CaptchaActivity", "NTK JS bridge QUIC start: method="
                        + method + ",bodyLen=" + body.length + ",headers=" + headers.keySet()
                        + ",cookies=" + summarizeCookieHeaderForLog(cookieHeader)
                        + ",bodyPreview=" + previewBridgeBodyForLog(body) + ",url=" + url);
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                        getApplicationContext(),
                        url,
                        getHttpClient().agent,
                        cookieHeader,
                        headers,
                        method,
                        body,
                        30000L);
                if(result == null)
                    return bridgeError("empty result");
                if(result.error != null)
                    return bridgeError(String.valueOf(result.error));
                if(isFinishing || isDestroyed())
                    return bridgeError("captcha activity finishing");
                if(shouldRetryNtkBridgePostWithHttp2(url, method, result)) {
                    android.util.Log.d("CaptchaActivity", "NTK JS bridge retrying with HTTP/2 transport: method="
                            + method + ",code=" + result.code + ",url=" + url);
                    NtkQuicFetcher.Result retry = NtkQuicFetcher.fetchHttp2Only(
                            getApplicationContext(),
                            url,
                            getHttpClient().agent,
                            cookieHeader,
                            headers,
                            method,
                            body,
                            12000L);
                    if(retry != null && retry.error == null && retry.code < result.code) {
                        result = retry;
                        android.util.Log.d("CaptchaActivity", "NTK JS bridge HTTP/2 retry selected: method="
                                + method + ",code=" + result.code + ",len=" + result.bodyBytes.length + ",url=" + url);
                    } else if(retry != null) {
                        android.util.Log.d("CaptchaActivity", "NTK JS bridge HTTP/2 retry ignored: method="
                                + method + ",code=" + retry.code + ",error=" + retry.error + ",url=" + url);
                    }
                }
                recordNtkBridgeAckProofIfNeeded(url, method, body, result);
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

    private static boolean shouldRetryNtkBridgePostWithHttp2(String url, String method, NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.code < 400 || result.code >= 600)
            return false;
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

    static boolean shouldRetryNtkBridgePostWithHttp2ForTest(String url, String method, NtkQuicFetcher.Result result) {
        return shouldRetryNtkBridgePostWithHttp2(url, method, result);
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
        if(isNtkGuardModulePath(url)) {
            rememberNtkGuardVersion(url);
            android.util.Log.d("CaptchaActivity", "NTK guard module QUIC request headers="
                    + (request.getRequestHeaders() == null ? "none" : request.getRequestHeaders().keySet())
                    + ",cookies=" + summarizeCookieHeaderForLog(captchaCookieHeaderFor(url))
                    + ",url=" + url);
        }
        if(!shouldInterceptNtkQuicRequestForTest(p != null && p.isNtkSite(), quicCaptchaHtmlActive,
                method, url, NtkQuicFetcher.isAvailable()))
            return null;
        try {
            Map<String, String> requestHeaders = ntkQuicRequestHeaders(url, request.getRequestHeaders());
            String cookieHeader = captchaCookieHeaderFor(url);
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                    getApplicationContext(),
                    url,
                    getHttpClient().agent,
                    cookieHeader,
                    requestHeaders,
                    request.isForMainFrame() ? 15000L : 10000L);
            if(isNtkGuardModulePath(url) && result != null && result.code >= 400) {
                WebResourceResponse immediateLocalGuard = localNtkGuardModuleResponse(url, result.code);
                if(immediateLocalGuard != null)
                    return immediateLocalGuard;
                String withoutGuardCookie = removeCookieFromHeader(cookieHeader, "ad_guard_l");
                if(withoutGuardCookie != null && !withoutGuardCookie.equals(cookieHeader)) {
                    android.util.Log.d("CaptchaActivity", "Retrying NTK guard module without ad_guard_l cookie: code="
                            + result.code + ",url=" + url);
                    NtkQuicFetcher.Result retry = NtkQuicFetcher.fetch(
                            getApplicationContext(),
                            url,
                            getHttpClient().agent,
                            withoutGuardCookie,
                            requestHeaders,
                            request.isForMainFrame() ? 15000L : 10000L);
                    if(retry != null && retry.code < result.code)
                        result = retry;
                }
                if(result != null && result.code >= 400) {
                    android.util.Log.d("CaptchaActivity", "Retrying NTK guard module without cookies: code="
                            + result.code + ",url=" + url);
                    NtkQuicFetcher.Result retry = NtkQuicFetcher.fetch(
                            getApplicationContext(),
                            url,
                            getHttpClient().agent,
                            "",
                            requestHeaders,
                            request.isForMainFrame() ? 15000L : 10000L);
                    if(retry != null && retry.code < result.code)
                        result = retry;
                }
                if(result != null && result.code >= 400 && hasHeader(requestHeaders, "Origin")) {
                    Map<String, String> withoutOrigin = removeHeader(requestHeaders, "Origin");
                    android.util.Log.d("CaptchaActivity", "Retrying NTK guard module without Origin header: code="
                            + result.code + ",url=" + url);
                    NtkQuicFetcher.Result retry = NtkQuicFetcher.fetch(
                            getApplicationContext(),
                            url,
                            getHttpClient().agent,
                            cookieHeader,
                            withoutOrigin,
                            request.isForMainFrame() ? 15000L : 10000L);
                    if(retry != null && retry.code < result.code)
                        result = retry;
                }
                if(result != null && result.code >= 400) {
                    WebResourceResponse localGuard = localNtkGuardModuleResponse(url, result.code);
                    if(localGuard != null)
                        return localGuard;
                }
            }
            if(result != null && isNtkGuardModulePath(url) && result.code >= 200 && result.code < 300
                    && result.bodyBytes != null && result.bodyBytes.length > 0) {
                applyQuicCaptchaCookies(url, result);
                cacheNtkGuardModule(url, result.bodyBytes);
                return new WebResourceResponse(
                        responseMimeType(result.contentType()),
                        responseEncoding(result.contentType()),
                        result.code,
                        "OK",
                        responseHeaders(result.headers),
                        new ByteArrayInputStream(result.bodyBytes));
            }
            if(result == null || !result.isUsableHtml()) {
                android.util.Log.d("CaptchaActivity", "NTK QUIC WebView intercept unusable: "
                        + url
                        + ",mainFrame=" + request.isForMainFrame()
                        + ",code=" + (result == null ? "null" : result.code)
                        + ",error=" + (result == null || result.error == null ? "null" : result.error.getClass().getSimpleName())
                        + ",bytes=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length));
                return null;
            }
            applyQuicCaptchaCookies(url, result);
            try {
                getHttpClient().rememberNtkViewerPageFromWebView(url, result.code, result.body);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "Failed to hand off NTK viewer page body: "
                        + url + "," + e);
            }
            byte[] responseBytes = result.bodyBytes;
            if(request.isForMainFrame() && responseMimeType(result.contentType()).toLowerCase(java.util.Locale.ROOT).contains("html")) {
                if(isCurrentNtkRootBootstrapUrl(url)) {
                    if(result.code >= 200 && result.code < 300) {
                        ntkRootBootstrapMainFrameError = false;
                        android.util.Log.d("CaptchaActivity", "NTK root bootstrap main-frame satisfied through QUIC: " + url);
                    } else {
                        ntkRootBootstrapMainFrameError = true;
                        android.util.Log.d("CaptchaActivity", "NTK root bootstrap QUIC main-frame blocked: "
                                + url + ",code=" + result.code + ",bytes=" + responseBytes.length);
                    }
                }
                responseBytes = injectNtkQuicBridgeScript(url, result.body, result.headers).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if(isNtkJavascriptResponse(url, result.contentType()))
                responseBytes = rewriteNtkJavascriptBridgeCalls(url, result.body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private String rewriteNtkJavascriptBridgeCalls(String url, String body) {
        if(body == null || body.length() == 0 || body.indexOf("/api/") < 0)
            return body;
        String rewritten = body
                .replace("fetch(\"/api/ev/sync\",", "window.__ntkBridgeFetch(\"/api/ev/sync\",")
                .replace("fetch(\"/api/m/ev\",", "window.__ntkBridgeFetch(\"/api/m/ev\",")
                .replace("navigator.sendBeacon?.(\"/api/m/ev\",e)", "false");
        if(!rewritten.equals(body)) {
            android.util.Log.d("CaptchaActivity", "Rewrote NTK JS API calls for QUIC bridge: " + url);
        }
        return rewritten;
    }

    private void rememberNtkGuardVersion(String url) {
        try {
            String query = URI.create(url).getRawQuery();
            if(query == null || query.length() == 0)
                return;
            for(String part : query.split("&")) {
                int equals = part.indexOf('=');
                if(equals <= 0)
                    continue;
                if(!"v".equals(part.substring(0, equals)))
                    continue;
                String value = java.net.URLDecoder.decode(part.substring(equals + 1),
                        java.nio.charset.StandardCharsets.UTF_8.name());
                if(value.matches("b\\d{13}-wasm-\\d{13}")) {
                    getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                            .edit()
                            .putString("ntk_guard_version", value)
                            .apply();
                    android.util.Log.d("CaptchaActivity", "Remembered NTK guard version: " + value);
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private void recordNtkBridgeAckProofIfNeeded(String url, String method, byte[] requestBody,
                                                 NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.code != 200)
            return;
        if(method == null || !"POST".equalsIgnoreCase(method))
            return;
        String path;
        try {
            URI uri = URI.create(url);
            path = uri.getPath();
            if(!"/api/ad/ack".equals(path) && !"/api/ad/challenge".equals(path))
                return;
        } catch (Exception e) {
            return;
        }
        try {
            String scope = bridgeAckRequestScope(requestBody);
            if(scope == null || scope.length() == 0)
                scope = ntkVerificationUrl(captchaLoadUrl, captchaLoadUrl);
            if(scope == null || scope.length() == 0)
                return;
            if("/api/ad/challenge".equals(path) && resultSetCookiesContainName(result, "ad_ack_c")) {
                getHttpClient().rememberExternalNtkServerAckSuccess(
                        scope, "captcha-bridge-challenge-ad-ack-cookie-200");
                android.util.Log.d("CaptchaActivity", "NTK JS bridge challenge ACK proof recorded status="
                        + result.code + ",scope=" + scope + ",url=" + url);
                return;
            }
            if(!"/api/ad/ack".equals(path))
                return;
            String responseText = result.body == null ? "" : result.body;
            org.json.JSONObject response = new org.json.JSONObject(responseText);
            boolean ok = response.optBoolean("ok", false)
                    || response.optBoolean("acked", false)
                    || "ok".equalsIgnoreCase(response.optString("status", ""))
                    || "acked".equalsIgnoreCase(response.optString("status", ""));
            if(!ok)
                return;
            getHttpClient().rememberExternalNtkServerAckSuccess(scope, "captcha-bridge-ack-200");
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK JS bridge ACK proof parse failed: " + url, e);
        }
    }

    private static String bridgeAckRequestScope(byte[] requestBody) {
        if(requestBody == null || requestBody.length == 0)
            return "";
        try {
            org.json.JSONObject request = new org.json.JSONObject(
                    new String(requestBody, java.nio.charset.StandardCharsets.UTF_8));
            return request.optString("path", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean resultSetCookiesContainName(NtkQuicFetcher.Result result, String name) {
        if(result == null || name == null)
            return false;
        for(String cookie : result.setCookies()) {
            String value = extractCookieValueForTest(cookie, name);
            if(value != null && value.length() > 0)
                return true;
        }
        return false;
    }

    private void cacheNtkGuardModule(String url, byte[] bytes) {
        if(bytes == null || bytes.length == 0)
            return;
        try {
            String version = ntkGuardVersionFromUrl(url);
            if(version.length() == 0)
                return;
            String path = URI.create(url).getPath();
            boolean wasm = path != null && path.equals("/api/ad/guard-wasm");
            java.io.File dir = new java.io.File(getCacheDir(), "ntk_guard_cache");
            if(!dir.exists() && !dir.mkdirs())
                return;
            java.io.File out = new java.io.File(dir, (wasm ? "guard-wasm-" : "guard-js-")
                    + version + (wasm ? ".bin" : ".js"));
            try(java.io.FileOutputStream stream = new java.io.FileOutputStream(out, false)) {
                stream.write(bytes);
            }
            android.util.Log.d("CaptchaActivity", "Cached NTK guard module: asset="
                    + out.getName() + ",len=" + bytes.length);
        } catch (Exception ignored) {
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
                String value = java.net.URLDecoder.decode(part.substring(equals + 1),
                        java.nio.charset.StandardCharsets.UTF_8.name());
                return value.matches("b\\d{13}-wasm-\\d{13}") ? value : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean isNtkJavascriptResponse(String url, String contentType) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if(lowerType.contains("javascript") || lowerType.contains("ecmascript"))
            return true;
        try {
            String path = URI.create(url).getPath();
            return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".js");
        } catch (Exception e) {
            return false;
        }
    }

    private WebResourceResponse localNtkGuardModuleResponse(String url, int serverCode) {
        if(!isNtkGuardModulePath(url))
            return null;
        try {
            String path = URI.create(url).getPath();
            boolean wasm = path != null && path.equals("/api/ad/guard-wasm");
            String assetPath = wasm ? "ntk_guard/guard-wasm.bin" : "ntk_guard/guard.js";
            byte[] bytes = readAssetBytes(assetPath);
            if(bytes == null || bytes.length == 0)
                return null;
            android.util.Log.d("CaptchaActivity", "Serving local NTK guard module fallback after server code="
                    + serverCode + ",asset=" + assetPath + ",url=" + url + ",len=" + bytes.length);
            scheduleNtkAdAckRearmAfterLocalGuard(wasm);
            return new WebResourceResponse(
                    wasm ? "application/wasm" : "text/javascript",
                    null,
                    200,
                    "OK",
                    Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                    new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "Failed local NTK guard module fallback: " + url, e);
            return null;
        }
    }

    private void scheduleNtkAdAckRearmAfterLocalGuard(boolean wasm) {
        if(webView == null || isFinishing || isDestroyed())
            return;
        long delayMs = wasm ? 900L : 1800L;
        handler.postDelayed(() -> {
            if(webView == null || isFinishing || isDestroyed())
                return;
            webView.evaluateJavascript("(function(){try{"
                    + "window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{scope:location.pathname||'/'}}));"
                    + "window.dispatchEvent(new CustomEvent('ntk-ad-allow-hidden',{detail:{sourceReason:'app_local_guard_fallback'}}));"
                    + "return 'rearmed';"
                    + "}catch(e){return String(e);}})()", value ->
                    android.util.Log.d("CaptchaActivity", "NTK local guard rearm result=" + value));
        }, delayMs);
    }

    private byte[] readAssetBytes(String assetPath) {
        try(java.io.InputStream input = getAssets().open(assetPath);
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

    private static String removeCookieFromHeader(String cookieHeader, String cookieName) {
        if(cookieHeader == null || cookieHeader.length() == 0 || cookieName == null)
            return cookieHeader;
        StringBuilder out = new StringBuilder();
        for(String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            if(trimmed.length() == 0)
                continue;
            int eq = trimmed.indexOf('=');
            String name = eq <= 0 ? trimmed : trimmed.substring(0, eq).trim();
            if(cookieName.equalsIgnoreCase(name))
                continue;
            if(out.length() > 0)
                out.append("; ");
            out.append(trimmed);
        }
        return out.toString();
    }

    private Map<String, String> ntkQuicRequestHeaders(String url, Map<String, String> rawHeaders) {
        if(!isNtkGuardModulePath(url))
            return rawHeaders;
        HashMap<String, String> headers = new HashMap<>();
        if(rawHeaders != null)
            headers.putAll(rawHeaders);
        putHeaderIfAbsent(headers, "Accept", "*/*");
        putHeaderIfAbsent(headers, "Sec-Fetch-Dest", "script");
        putHeaderIfAbsent(headers, "Sec-Fetch-Mode", "cors");
        putHeaderIfAbsent(headers, "Sec-Fetch-Site", "same-origin");
        return headers;
    }

    private static void putHeaderIfAbsent(Map<String, String> headers, String name, String value) {
        if(headers == null || name == null)
            return;
        for(String key : headers.keySet()) {
            if(name.equalsIgnoreCase(key))
                return;
        }
        headers.put(name, value);
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        if(headers == null || name == null)
            return false;
        for(String key : headers.keySet()) {
            if(name.equalsIgnoreCase(key))
                return true;
        }
        return false;
    }

    private static Map<String, String> removeHeader(Map<String, String> headers, String name) {
        HashMap<String, String> out = new HashMap<>();
        if(headers == null || name == null)
            return out;
        for(String key : headers.keySet()) {
            if(key == null || name.equalsIgnoreCase(key))
                continue;
            out.put(key, headers.get(key));
        }
        return out;
    }

    static boolean shouldInterceptNtkQuicRequestForTest(boolean ntkSite, boolean quicHtmlActive,
                                                        String method, String url, boolean quicAvailable) {
        if(!ntkSite || !quicAvailable || method == null || !"GET".equalsIgnoreCase(method))
            return false;
        if(isNtkGuardModulePath(url))
            return isNtkProtectedHttpsUrl(url);
        if(isNtkCloudflareChallengeResource(url))
            return quicHtmlActive && isNtkProtectedHttpsUrl(url);
        if(!quicHtmlActive)
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
                    installShadowHookIfAllowed();
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
        if(disableTurnstileAutomationForDiagnostics)
            return;
        if(turnstileAutoClickStarted)
            return;
        turnstileAutoClickStarted = true;
        isFirstAttempt = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(isFinishing || isDestroyed() || captchaLoadErrorVisible) return;

                // Check if we've been waiting too long
                long turnstileWaitMs = System.currentTimeMillis() - pageFinishedTime;
                if(turnstileWaitMs > TURNSTILE_MAX_WAIT_MS) {
                    if(reloadStuckNtkTurnstileChallenge(turnstileWaitMs))
                        return;
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

    private boolean reloadStuckNtkTurnstileChallenge(long waitMs) {
        if(p == null || !p.isNtkSite() || webView == null
                || captchaLoadUrl == null || captchaLoadUrl.length() == 0)
            return false;
        if(turnstileStuckReloadCount >= TURNSTILE_MAX_STUCK_RELOADS)
            return false;
        turnstileStuckReloadCount++;
        String reloadUrl = captchaLoadUrl;
        android.util.Log.w("CaptchaActivity", "Reloading stuck NTK Turnstile challenge waitMs="
                + waitMs
                + ",reload=" + turnstileStuckReloadCount
                + ",url=" + reloadUrl);
        retriedCaptchaWithNtkRootBootstrap = false;
        retriedCaptchaWithQuic = false;
        retriedCaptchaWithProxy = false;
        retriedCaptchaWithoutProxy = false;
        quicCaptchaLoadInFlight = false;
        quicCaptchaHtmlActive = false;
        ntkRootBootstrapReturnUrl = null;
        ntkRootBootstrapStartedAt = 0L;
        ntkRootBootstrapMainFrameError = false;
        ntkReloadedAckTargetAfterStaleRootError = false;
        hideCaptchaLoadError();
        clearWebViewProxy();
        handler.postDelayed(() -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            loadCaptchaUrl(reloadUrl);
        }, 150L);
        return true;
    }

    private void attemptTurnstileClick() {
        if(disableTurnstileAutomationForDiagnostics
                || isFinishing || isDestroyed() || webView == null || captchaLoadErrorVisible) return;

        long now = System.currentTimeMillis();
        long requiredInterval = isFirstAttempt ? FIRST_CLICK_DELAY_MS : TURNSTILE_EVALUATION_MIN_INTERVAL_MS;
        if(now - lastAttemptTime < requiredInterval) return;
        lastAttemptTime = now;

        performTurnstileClickEvaluation();
    }

    private void attemptTurnstileClickImmediate() {
        if(disableTurnstileAutomationForDiagnostics
                || isFinishing || isDestroyed() || webView == null || captchaLoadErrorVisible) return;
        long now = System.currentTimeMillis();
        if(now - lastAttemptTime < TURNSTILE_EVALUATION_MIN_INTERVAL_MS)
            return;
        lastAttemptTime = now;
        performTurnstileClickEvaluation();
    }

    private void scheduleNtkNormalPageFinishProbes() {
        scheduleNtkNormalPageFinishProbe(250L);
        scheduleNtkNormalPageFinishProbe(900L);
        scheduleNtkNormalPageFinishProbe(1800L);
        scheduleNtkNormalPageFinishProbe(3500L);
    }

    private void scheduleNtkCaptchaEnvironmentLogs() {
        if(p == null || !p.isNtkSite())
            return;
        handler.postDelayed(() -> logNtkCaptchaEnvironment("page-finished-1s"), 1_000L);
        handler.postDelayed(() -> logNtkCaptchaEnvironment("page-finished-5s"), 5_000L);
    }

    private void scheduleNtkNormalPageFinishProbe(long delayMs) {
        if(p == null || !p.isNtkSite() || webView == null || isFinishing || isDestroyed())
            return;
        handler.postDelayed(this::probeNtkNormalPageAndFinish, delayMs);
    }

    private void probeNtkNormalPageAndFinish() {
        if(p == null || !p.isNtkSite() || webView == null || isFinishing || isDestroyed()
                || captchaLoadErrorVisible)
            return;
        long now = System.currentTimeMillis();
        if(now - lastNtkNormalProbeAt < 500L)
            return;
        lastNtkNormalProbeAt = now;
        webView.evaluateJavascript("(function(){"
                + "try{"
                + "var host=(location.hostname||'').toLowerCase();"
                + "if(host.indexOf('ntk')<0&&host.indexOf('sbxh')<0&&host.indexOf('toonflix')<0)return 'none';"
                + "var text=(document.body?(document.body.innerText||''):'').replace(/\\s+/g,' ');"
                + "var title=document.title||'';"
                + "if(/trash0607/i.test(text+' '+title))return 'trash0607';"
                + "if(/웹페이지를 사용할 수 없음|webpage not available|403 forbidden|err_connection_reset|err_timed_out|err_name_not_resolved/i.test(text+' '+title))return 'none';"
                + "if(/verify you are human|checking your browser|performing security verification|just a moment/i.test(text))return 'challenge';"
                + "var links=document.querySelectorAll('a[href^=\"/manhwa\"],a[href^=\"/webtoon\"],a[href*=\"/manhwa/\"],a[href*=\"/webtoon/\"]').length;"
                + "var imgs=document.querySelectorAll('img[src],img[data-src],picture source[srcset]').length;"
                + "var totalLinks=document.querySelectorAll('a[href]').length;"
                + "var hasNtkBrand=text.indexOf('NEWTOKI')>=0||title.indexOf('NEWTOKI')>=0||text.indexOf('뉴토끼')>=0||title.indexOf('뉴토끼')>=0;"
                + "var normal=links>=1||links>=6||imgs>=4||totalLinks>=8||hasNtkBrand||!!document.querySelector('main,#__next,.container,.content,.list,.post,.view,.toon');"
                + "return normal?'normal':'none';"
                + "}catch(e){return 'error';}"
                + "})()", result -> {
            if(isFinishing || isDestroyed() || webView == null)
                return;
            String clean = result == null ? "" : result.replace("\"", "");
            if("trash0607".equals(clean)) {
                handleNtkTrash0607Block(webView.getUrl(), "normal-probe");
                return;
            }
            if(!"normal".equals(clean))
                return;
            normalNtkPageCount = Math.max(normalNtkPageCount, 1);
            android.util.Log.d("CaptchaActivity", "NTK normal page probe detected; verifying app HTTP access");
            if(!readCookiesAndFinish(CookieManager.getInstance(), p.getUrl(), webView.getUrl()))
                verifyNtkAccessAndFinish(p.getUrl(), webView.getUrl(), null);
        });
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
        if(disableTurnstileAutomationForDiagnostics)
            return;
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
                    float x = (float) obj.getDouble("x");
                    float y = (float) obj.getDouble("y");
                    float w = (float) obj.optDouble("w", 60);
                    float h = (float) obj.optDouble("h", 60);
                    float[] converted = cssViewportRectToViewPixels(obj, x, y, w, h);
                    final float touchX = converted[0];
                    final float touchY = converted[1];
                    final float touchW = converted[2];
                    final float touchH = converted[3];
                    String signature = turnstileSignature(obj, x, y, w, h);
                    android.util.Log.d("CaptchaActivity", "Turnstile iframe found css=" + x + "," + y
                            + " size=" + w + "x" + h
                            + " touch=" + touchX + "," + touchY
                            + " touchSize=" + touchW + "x" + touchH);

                    long now = System.currentTimeMillis();
                    if(!signature.equals(lastTurnstileClickSignature)) {
                        lastTurnstileClickSignature = signature;
                        lastTurnstileRepeatTouchAt = now;
                        turnstileRepeatTouchCount = 1;
                        postTurnstileTouch(touchX, touchY, touchW, touchH);
                    } else if(shouldRetryTurnstileTouchForTest(
                            now, lastTurnstileRepeatTouchAt, turnstileRepeatTouchCount)) {
                        lastTurnstileRepeatTouchAt = now;
                        turnstileRepeatTouchCount++;
                        postTurnstileTouch(touchX, touchY, touchW, touchH);
                    }
                    isFirstAttempt = false;
                } else if("normal".equals(type)) {
                    isFirstAttempt = false;
                    normalNtkPageCount++;
                    android.util.Log.d("CaptchaActivity", "NTK normal page detected without Turnstile: " + normalNtkPageCount);
                    long elapsed = System.currentTimeMillis() - pageFinishedTime;
                    if(shouldFinishNormalNtkPageForTest(normalNtkPageCount, elapsed)) {
                        android.util.Log.d("CaptchaActivity", "NTK normal page stable; verifying app HTTP access");
                        verifyNtkAccessAndFinish(p.getUrl(), webView == null ? null : webView.getUrl(), null);
                    } else if(normalNtkPageCount >= 1 && elapsed > 300L) {
                        readCookiesAndFinish(CookieManager.getInstance(), p.getUrl(), webView == null ? null : webView.getUrl());
                    }
                } else if("trash0607".equals(type)) {
                    handleNtkTrash0607Block(webView == null ? null : webView.getUrl(), "turnstile-probe");
                } else {
                    isFirstAttempt = false;
                    normalNtkPageCount = 0;
                    lastTurnstileClickSignature = "";
                    lastTurnstileRepeatTouchAt = 0;
                    turnstileRepeatTouchCount = 0;
                    logNtkCaptchaEnvironment("turnstile-" + type);
                }
            } catch(Exception e) {
                android.util.Log.e("CaptchaActivity", "Failed to parse turnstile result", e);
                logNtkCaptchaEnvironment("turnstile-parse-error");
            }
        });
    }

    private void logNtkCaptchaEnvironment(String reason) {
        if(p == null || !p.isNtkSite() || webView == null || isFinishing || isDestroyed())
            return;
        long now = System.currentTimeMillis();
        if(now - lastNtkCaptchaEnvironmentLogAt < 3_000L)
            return;
        lastNtkCaptchaEnvironmentLogAt = now;
        webView.evaluateJavascript("(function(){try{"
                + "function s(v,n){v=String(v||'').replace(/\\s+/g,' ');return v.slice(0,n||180);}"
                + "var ck=String(document.cookie||'');"
                + "var frames=[],ifs=document.querySelectorAll('iframe');"
                + "for(var i=0;i<ifs.length&&i<8;i++){frames.push(s(ifs[i].src||ifs[i].getAttribute('src')||ifs[i].id||ifs[i].name||'',120));}"
                + "var scripts=[],ss=document.querySelectorAll('script[src]');"
                + "for(var j=0;j<ss.length&&j<8;j++){scripts.push(s(ss[j].src,120));}"
                + "var ls=[],k;"
                + "try{for(var l=0;l<localStorage.length&&l<10;l++){k=String(localStorage.key(l)||'');ls.push(k.slice(0,60));}}catch(_){ls.push('ERR');}"
                + "var b=document.body?s(document.body.innerText||document.body.textContent||'',220):'';"
                + "var ad=navigator.userAgentData?JSON.stringify({brands:navigator.userAgentData.brands,mobile:navigator.userAgentData.mobile,platform:navigator.userAgentData.platform}):'';"
                + "return JSON.stringify({href:s(location.href,160),ready:String(document.readyState||''),title:s(document.title,100),body:b,ua:s(navigator.userAgent,180),uaData:s(ad,220),webdriver:!!navigator.webdriver,cookieEnabled:!!navigator.cookieEnabled,cookieLen:ck.length,hasCf:/cf_clearance=/.test(ck),hasBm:/__cf_bm=/.test(ck),hasAdGuard:/ad_guard_l=/.test(ck),touch:Number(navigator.maxTouchPoints||0),hw:Number(navigator.hardwareConcurrency||0),mem:Number(navigator.deviceMemory||0),secure:!!window.isSecureContext,visible:String(document.visibilityState||''),focus:document.hasFocus?document.hasFocus():false,viewport:String(window.innerWidth||0)+'x'+String(window.innerHeight||0),screen:String((screen&&screen.width)||0)+'x'+String((screen&&screen.height)||0),iframes:frames,scripts:scripts,local:ls,turnstile:!!document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"],iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]')});"
                + "}catch(e){return 'ERR:'+String(e);}})()", result ->
                android.util.Log.d("CaptchaActivity", "ntk_captcha_env reason=" + reason
                        + ",result=" + result));
    }

    private void handleNtkTrash0607Block(String currentUrl, String source) {
        if(isFinishing || isDestroyed())
            return;
        String url = currentUrl != null && currentUrl.length() > 0 ? currentUrl : captchaLoadUrl;
        Log.d("CaptchaActivity", "ntk_trash0607_device_change source=" + source + ",url=" + url);
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        NtkDeviceIdentityManager.changeDeviceInfo(this, true);
    }

    private boolean shouldSuppressCaptchaConsoleMessage(String msg) {
        if(msg == null)
            return false;
        if(msg.contains("__TURNSTILE_CB__") || msg.contains("__NTK_QUIC_"))
            return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("font-size:0;color:transparent")
                || lower.equals("nan")
                || lower.contains("permissions-policy header")
                || lower.contains("preloaded using link preload");
    }

    private float[] cssViewportRectToViewPixels(org.json.JSONObject obj, float x, float y, float w, float h) {
        float scaleX = 1f;
        float scaleY = 1f;
        try {
            double viewportWidth = obj.optDouble("vw", 0d);
            double viewportHeight = obj.optDouble("vh", 0d);
            if(webView != null && viewportWidth > 0d && webView.getWidth() > 0)
                scaleX = (float) (webView.getWidth() / viewportWidth);
            if(webView != null && viewportHeight > 0d && webView.getHeight() > 0)
                scaleY = (float) (webView.getHeight() / viewportHeight);
            if(scaleX <= 0f || scaleX > 8f)
                scaleX = 1f;
            if(scaleY <= 0f || scaleY > 8f)
                scaleY = scaleX;
        } catch (Exception ignored) {
        }
        return new float[]{x * scaleX, y * scaleY, Math.max(24f, w * scaleX), Math.max(24f, h * scaleY)};
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

        // Values passed here are already converted to WebView-local pixels.
        float baseX = centerX;
        float baseY = centerY;
        float physW = width;
        float physH = height;

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
                    if(shouldWaitForNtkAdAckBeforeFinish(purl, currentUrl)) {
                        if(!waitingForNtkAdAckBeforeFinish) {
                            waitingForNtkAdAckBeforeFinish = true;
                            android.util.Log.d("CaptchaActivity",
                                    "NTK document access verified; waiting for ad guard ack before finish");
                            startNtkNativeAdAckAfterClearance(ntkAckTargetUrlForFinish(purl, currentUrl));
                            waitForNtkAdAckAndFinish(purl, currentUrl, 0);
                        }
                    } else {
                        finishWithVerifiedClearance();
                    }
                } else {
                    android.util.Log.d("CaptchaActivity", "NTK clearance failed app HTTP verification; keeping captcha open");
                    if(clearanceValue != null) {
                        android.util.Log.d("CaptchaActivity",
                                "NTK clearance preserved after transient verification failure");
                    }
                }
            });
        });
    }

    private boolean shouldWaitForNtkAdAckBeforeFinish(String purl, String currentUrl) {
        String targetUrl = ntkAckTargetUrlForFinish(purl, currentUrl);
        String path = targetUrl.length() == 0 ? "" : ntkVerificationUrl(targetUrl, targetUrl);
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        boolean wait = lower.startsWith("/webtoon/") || lower.startsWith("/manhwa/");
        android.util.Log.d("CaptchaActivity", "NTK ad guard ack wait decision wait=" + wait
                + ",target=" + targetUrl
                + ",path=" + path
                + ",purl=" + purl
                + ",currentUrl=" + currentUrl
                + ",loadUrl=" + captchaLoadUrl);
        if(targetUrl.length() == 0)
            return false;
        return wait;
    }

    private void startNtkNativeAdAckAfterClearance(String targetUrl) {
        if(targetUrl == null || targetUrl.length() == 0 || !isNtkEpisodeUrl(targetUrl))
            return;
        final String baseUrl;
        final String path;
        try {
            URI uri = URI.create(targetUrl);
            baseUrl = uri.getScheme() + "://" + uri.getHost();
            path = uri.getRawPath();
        } catch (Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK native ad ack target parse failed: " + targetUrl, e);
            return;
        }
        if(baseUrl == null || path == null || path.length() == 0)
            return;
        AppDispatchers.runIo(() -> {
            boolean ok = false;
            try {
                ok = getHttpClient().performNtkNativeAckBypassIgnoringWebViewInFlight(baseUrl, path, path);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "NTK native ad ack after clearance failed: " + path, e);
            }
            boolean success = ok;
            android.util.Log.d("CaptchaActivity", "NTK native ad ack after clearance done path="
                    + path + ",success=" + success);
            if(!success)
                return;
            AppDispatchers.runOnMain(() -> {
                if(isFinishing || !waitingForNtkAdAckBeforeFinish)
                    return;
                if(hasNtkAdAckCookie(baseUrl + path, targetUrl)) {
                    android.util.Log.d("CaptchaActivity", "NTK native ad ack verified before finish: " + path);
                    waitingForNtkAdAckBeforeFinish = false;
                    finishWithVerifiedClearance();
                }
            });
        });
    }

    private boolean hasNtkAdAckCookie(String purl, String currentUrl) {
        syncCaptchaCookiesToHttpClient(purl, currentUrl);
        String targetUrl = ntkAckTargetUrlForFinish(purl, currentUrl);
        String path = targetUrl.length() == 0 ? "" : ntkVerificationUrl(targetUrl, targetUrl);
        return path.length() > 0 && getHttpClient().hasUsableNtkAdAckCookieForPath(path);
    }

    private void waitForNtkAdAckAndFinish(String purl, String currentUrl, int attempt) {
        if(isFinishing)
            return;
        String targetUrl = ntkAckTargetUrlForFinish(purl, currentUrl);
        if(attempt == 0 && startNtkNativeAdAckBeforeFinish(purl, currentUrl, targetUrl))
            return;
        if(attempt == 0 && shouldLoadNtkAckTargetBeforeFinish(targetUrl)) {
            android.util.Log.d("CaptchaActivity", "NTK ad guard ack loading target before finish: " + targetUrl);
            ntkRootBootstrapReturnUrl = null;
            ntkRootBootstrapStartedAt = 0L;
            ntkRootBootstrapMainFrameError = false;
            retriedCaptchaWithQuic = false;
            retriedCaptchaWithProxy = false;
            retriedCaptchaWithoutProxy = false;
            clearWebViewProxy();
            quicCaptchaHtmlActive = true;
            webView.loadUrl(targetUrl);
            handler.postDelayed(() -> {
                if(!isFinishing && !isDestroyed() && webView != null)
                    installShadowHookIfAllowed();
            }, 250L);
            handler.postDelayed(() -> waitForNtkAdAckAndFinish(purl, webView == null ? currentUrl : webView.getUrl(), attempt + 1), 500L);
            return;
        }
        if(hasNtkAdAckCookie(purl, currentUrl)) {
            android.util.Log.d("CaptchaActivity", "NTK ad guard ack verified before finish attempt=" + attempt);
            waitingForNtkAdAckBeforeFinish = false;
            finishWithVerifiedClearance();
            return;
        }
        if(attempt >= 14) {
            android.util.Log.d("CaptchaActivity", "NTK ad guard ack not observed before finish; continuing after wait");
            waitingForNtkAdAckBeforeFinish = false;
            finishWithVerifiedClearance();
            return;
        }
        handler.postDelayed(() -> waitForNtkAdAckAndFinish(purl, currentUrl, attempt + 1), 250L);
    }

    private boolean startNtkNativeAdAckBeforeFinish(String purl, String currentUrl, String targetUrl) {
        if(targetUrl == null || targetUrl.length() == 0)
            return false;
        String path = ntkVerificationUrl(targetUrl, targetUrl);
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/"))
            return false;
        String baseUrl = originForUrl(targetUrl);
        if(baseUrl.length() == 0)
            return false;
        android.util.Log.d("CaptchaActivity", "NTK ad guard native ack before finish start: base="
                + baseUrl + ",path=" + path + ",currentUrl=" + currentUrl);
        AppDispatchers.runIo(() -> {
            boolean ok = false;
            try {
                syncCaptchaCookiesToHttpClient(purl, currentUrl);
                ok = getHttpClient().performNtkNativeAckBypassIgnoringWebViewInFlight(baseUrl, path, path);
            } catch (Exception e) {
                android.util.Log.d("CaptchaActivity", "NTK ad guard native ack before finish failed", e);
            }
            boolean completed = ok || getHttpClient().hasUsableNtkAdAckCookieForPath(path);
            AppDispatchers.runOnMain(() -> {
                if(isFinishing)
                    return;
                if(completed) {
                    android.util.Log.d("CaptchaActivity",
                            "NTK ad guard native ack verified before finish path=" + path);
                    waitingForNtkAdAckBeforeFinish = false;
                    finishWithVerifiedClearance();
                } else {
                    android.util.Log.d("CaptchaActivity",
                            "NTK ad guard native ack before finish missed; falling back to WebView wait path="
                                    + path);
                    waitForNtkAdAckAndFinish(purl, currentUrl, 1);
                }
            });
        });
        return true;
    }

    private static String originForUrl(String url) {
        try {
            URI uri = URI.create(url);
            if(uri.getScheme() == null || uri.getHost() == null)
                return "";
            String origin = uri.getScheme() + "://" + uri.getHost();
            if(uri.getPort() > 0)
                origin += ":" + uri.getPort();
            return origin;
        } catch (Exception e) {
            return "";
        }
    }

    private String ntkAckTargetUrlForFinish(String purl, String currentUrl) {
        if(isNtkEpisodeUrl(captchaLoadUrl))
            return captchaLoadUrl;
        if(isNtkEpisodeUrl(purl))
            return purl;
        if(isNtkEpisodeUrl(currentUrl))
            return currentUrl;
        return "";
    }

    private boolean isNtkEpisodeUrl(String url) {
        if(url == null || url.length() == 0 || !getHttpClient().isNtkUrl(url))
            return false;
        String path = ntkVerificationUrl(url, url);
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("/webtoon/") || lower.startsWith("/manhwa/");
    }

    private boolean shouldLoadNtkAckTargetBeforeFinish(String purl) {
        if(webView == null || purl == null || purl.length() == 0)
            return false;
        if(!getHttpClient().isNtkUrl(purl))
            return false;
        String path = ntkVerificationUrl(purl, purl);
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/"))
            return false;
        String currentUrl = webView.getUrl();
        if(currentUrl == null || currentUrl.length() == 0)
            return true;
        String currentPath = ntkVerificationUrl(currentUrl, currentUrl);
        return !path.equals(currentPath);
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
        String pagePath = ntkVerificationPathForAccess(captchaLoadUrl, purl, currentUrl);
        if(pagePath.length() > 0) {
            if(verifyNtkDocumentPath(pagePath))
                return true;
            return false;
        }
        return verifyNtkPath(NTK_ACCESS_VERIFY_PATH) && verifyNtkPath(NTK_SEARCH_VERIFY_PATH);
    }

    static String ntkVerificationPathForAccessForTest(String captchaLoadUrl, String purl, String currentUrl) {
        return ntkVerificationPathForAccess(captchaLoadUrl, purl, currentUrl);
    }

    private static String ntkVerificationPathForAccess(String captchaLoadUrl, String purl, String currentUrl) {
        String loadPath = ntkPagePath(captchaLoadUrl);
        if(isNtkEpisodePath(loadPath))
            return loadPath;
        String purlPath = ntkPagePath(purl);
        if(isNtkEpisodePath(purlPath))
            return purlPath;
        String currentPath = ntkPagePath(currentUrl);
        if(isNtkEpisodePath(currentPath))
            return currentPath;
        if(currentPath.length() > 0)
            return currentPath;
        return purlPath;
    }

    private static boolean isNtkEpisodePath(String path) {
        if(path == null || path.length() == 0)
            return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("/webtoon/") || lower.startsWith("/manhwa/");
    }

    private boolean verifyNtkDocumentPath(String path) {
        try {
            NtkQuicFetcher.Result result = fetchNtkDocumentVerification(path);
            int code = result == null ? 0 : result.code;
            String body = result == null || result.body == null ? "" : result.body;
            boolean ok = code >= 200 && code < 400
                    && isUsableNtkDocumentVerificationBody(body)
                    && !getHttpClient().isCloudflareChallengeResponse(code, body);
            if(!ok)
                logNtkVerificationFailure("NTK document verification failed", path, code, body);
            if(!ok)
                return false;
            boolean rscOk = verifyNtkRscPath(path);
            if(!rscOk)
                android.util.Log.d("CaptchaActivity",
                        "NTK RSC verification advisory ignored after document access path=" + path);
            return true;
        } catch(Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK document verification request failed", e);
            return false;
        }
    }

    private NtkQuicFetcher.Result fetchNtkDocumentVerification(String path) {
        if(path == null || path.length() == 0)
            return null;
        try {
            String baseUrl = getHttpClient().getUrl(path);
            String targetUrl = baseUrl + path;
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("origin", baseUrl);
            headers.put("referer", targetUrl);
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                    getApplicationContext(),
                    targetUrl,
                    getHttpClient().agent,
                    getHttpClient().getCookieHeader(),
                    headers,
                    "GET",
                    null,
                    3500L);
            String body = result == null || result.body == null ? "" : result.body;
            if(result != null) {
                try {
                    getHttpClient().rememberNtkViewerPageFromWebView(targetUrl, result.code, body);
                } catch (Exception e) {
                    android.util.Log.d("CaptchaActivity", "Failed to hand off NTK verification body: "
                            + targetUrl + "," + e);
                }
            }
            android.util.Log.d("CaptchaActivity", "NTK document verification direct path=" + path
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + body.length()
                    + ",error=" + (result == null ? null : result.error));
            return result;
        } catch(Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK document verification direct failed path="
                    + path + "," + e);
            return null;
        }
    }

    private boolean verifyNtkRscPath(String path) {
        if(path == null || path.length() == 0)
            return false;
        String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
        if(!lowerPath.startsWith("/webtoon/") && !lowerPath.startsWith("/manhwa/"))
            return true;
        if(!NtkQuicFetcher.isAvailable())
            return true;
        try {
            String baseUrl = getHttpClient().getUrl(path);
            String targetUrl = baseUrl + path;
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "text/x-component");
            headers.put("rsc", "1");
            headers.put("next-url", path);
            headers.put("origin", baseUrl);
            headers.put("referer", targetUrl);
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(
                    getApplicationContext(),
                    targetUrl,
                    getHttpClient().agent,
                    getHttpClient().getCookieHeader(),
                    headers,
                    "GET",
                    null,
                    7000L);
            String body = result == null || result.body == null ? "" : result.body;
            int code = result == null ? 0 : result.code;
            boolean ok = result != null && result.error == null && code >= 200 && code < 400
                    && body.length() > 0
                    && !getHttpClient().isCloudflareChallengeResponse(code, body)
                    && !NtkDeviceIdentityManager.isTrash0607Block(body);
            if(!ok)
                logNtkVerificationFailure("NTK RSC verification failed", path, code, body);
            return ok;
        } catch(Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK RSC verification request failed", e);
            return false;
        }
    }

    private static boolean isUsableNtkDocumentVerificationBody(String body) {
        if(body == null)
            return false;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if(isBlockedOrErrorNtkDocumentBody(lower))
            return false;
        return (lower.contains("<html") || lower.contains("<!doctype html"))
                && (lower.contains("/manhwa") || lower.contains("/webtoon")
                || lower.contains("__next") || lower.contains("newtoki"));
    }

    private static boolean isBlockedOrErrorNtkDocumentBody(String lower) {
        if(lower == null || lower.length() == 0)
            return true;
        return lower.contains("webpage not available")
                || lower.contains("net::err_")
                || lower.contains("err_connection_reset")
                || lower.contains("err_timed_out")
                || (lower.contains("403 forbidden") && lower.contains("nginx"))
                || lower.contains("<title>403 forbidden</title>")
                || lower.contains("<h1>403 forbidden</h1>")
                || lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("cf-challenge")
                || lower.contains("cf-turnstile")
                || lower.contains("verifying you are human")
                || lower.contains("verify you are human")
                || lower.contains("developer tools blocked")
                || lower.contains("developer tool blocked")
                || lower.contains("devtools blocked")
                || lower.contains("devtool blocked")
                || lower.contains("媛쒕컻???꾧뎄 李⑤떒");
    }

    static boolean isUsableNtkDocumentVerificationBodyForTest(String body) {
        return isUsableNtkDocumentVerificationBody(body);
    }

    private boolean verifyNtkPath(String path) {
        try {
            Response response = getHttpClient().mget(path, true);
            if(response == null) {
                android.util.Log.d("CaptchaActivity", "NTK clearance verification empty response path=" + path);
                return false;
            }
            int code = response.code();
            String body = getHttpClient().readBody(response);
            boolean ok = code >= 200 && code < 400 && !getHttpClient().isCloudflareChallengeResponse(code, body);
            if(!ok)
                logNtkVerificationFailure("NTK clearance verification failed", path, code, body);
            return ok;
        } catch(Exception e) {
            android.util.Log.d("CaptchaActivity", "NTK clearance verification request failed", e);
            return false;
        }
    }

    private void logNtkVerificationFailure(String prefix, String path, int code, String body) {
        String sample = body == null ? "" : body.replace('\n', ' ').replace('\r', ' ');
        if(sample.length() > 180)
            sample = sample.substring(0, 180);
        android.util.Log.d("CaptchaActivity", prefix + " path=" + path
                + ",code=" + code + ",sample=" + sample);
    }

    private String ntkVerificationUrl(String purl, String currentUrl) {
        String candidate = ntkPagePath(currentUrl);
        if(candidate.length() == 0)
            candidate = ntkPagePath(purl);
        return candidate;
    }

    private static String ntkPagePath(String url) {
        if(url == null || url.length() == 0)
            return "";
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getRawPath();
            if(path == null || path.length() == 0)
                return "";
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if(!lower.startsWith("/webtoon/") && !lower.startsWith("/manhwa/")
                    && !lower.equals("/manhwa") && !lower.equals("/ing") && !lower.equals("/end")
                    && !lower.equals("/manhwa-end"))
                return "";
            String query = uri.getRawQuery();
            return query == null || query.length() == 0 ? path : path + "?" + query;
        } catch (Exception e) {
            return "";
        }
    }

    private void finishWithVerifiedClearance() {
        String currentWebViewUrl = webView == null ? null : webView.getUrl();
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        syncCaptchaCookiesToHttpClient(captchaLoadUrl, currentWebViewUrl);
        getHttpClient().clearNtkTransientLoads();
        Search.clearNtkResultCaches();
        detachCaptchaWebView();
        destroyReleasedWebViewLater();
        getHttpClient().saveClearanceToDisk();
        getHttpClient().markNtkAccessVerified();
        Log.d("CaptchaActivity", "finished with verified NTK clearance proof="
                + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " loadUrl=" + captchaLoadUrl
                + " currentUrl=" + currentWebViewUrl);
        Intent resultIntent = new Intent();
        setResult(RESULT_CAPTCHA, resultIntent);
        finish();
    }

    private void resetInvalidNtkClearanceAndReload(String purl, String currentUrl) {
        getHttpClient().clearCloudflareWebViewCookiesAggressively(purl, p.getWebtoonUrl(), p.getUrl(), currentUrl, NTK_WEBTOON_URL, NTK_COMIC_URL);
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
        return normalPageCount >= 1 && elapsedMs > 800L;
    }

    private static boolean isNtkGuardModulePath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null && (path.equals("/api/ad/guard-js") || path.equals("/api/ad/guard-wasm"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNtkCloudflareChallengeResource(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if(path == null)
                return false;
            String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
            return lowerPath.startsWith("/cdn-cgi/challenge-platform/")
                    || lowerPath.contains("/turnstile/")
                    || lowerPath.contains("/challenge-platform/");
        } catch (Exception e) {
            return false;
        }
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
            for(String cookieUrl : cookieReadUrls(purl, currentUrl)) {
                logCaptchaCookieSummary(manager, cookieUrl);
                getHttpClient().syncCookiesFromWebView(cookieUrl, true);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private void logCaptchaCookieSummary(CookieManager manager, String cookieUrl) {
        if(!isNtkLikeUrlForCaptcha(cookieUrl))
            return;
        try {
            String cookieStr = manager.getCookie(cookieUrl);
            if(cookieStr == null || cookieStr.length() == 0) {
                Log.d("CaptchaActivity", "ntk_captcha_cookie_summary url=" + cookieUrl
                        + ",empty=true");
                return;
            }
            final int[] count = new int[]{0};
            final boolean[] hasClearance = new boolean[]{false};
            final boolean[] hasAdGuard = new boolean[]{false};
            final boolean[] hasAdAck = new boolean[]{false};
            StringBuilder names = new StringBuilder();
            CaptchaCookiePolicy.forEachCookiePair(cookieStr, (key, value) -> {
                if(key == null || key.length() == 0)
                    return;
                if(count[0] > 0)
                    names.append('|');
                names.append(key);
                count[0]++;
                if("cf_clearance".equalsIgnoreCase(key))
                    hasClearance[0] = true;
                if(key.toLowerCase(java.util.Locale.ROOT).startsWith("ad_guard"))
                    hasAdGuard[0] = true;
                if(key.toLowerCase(java.util.Locale.ROOT).startsWith("ad_ack"))
                    hasAdAck[0] = true;
            });
            Log.d("CaptchaActivity", "ntk_captcha_cookie_summary url=" + cookieUrl
                    + ",count=" + count[0]
                    + ",cf=" + hasClearance[0]
                    + ",adGuard=" + hasAdGuard[0]
                    + ",adAck=" + hasAdAck[0]
                    + ",names=" + names);
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
        destroyReleasedWebViewNow();
        super.onDestroy();
    }

    @Override
    public void finish() {
        syncCaptchaCookiesToHttpClient(captchaLoadUrl, webView == null ? null : webView.getUrl());
        isFinishing = true;
        handler.removeCallbacksAndMessages(null);
        clearWebViewProxy();
        detachCaptchaWebView();
        destroyReleasedWebViewNow();
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
            target.removeJavascriptInterface("NtkQuicBridge");
        } catch (Exception ignored) {
        }
        try {
            target.stopLoading();
        } catch (Exception ignored) {
        }
        try {
            if(!isFinishing)
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

    private void destroyReleasedWebViewNow() {
        WebView target = releasedWebView;
        releasedWebView = null;
        if(target == null)
            return;
        try {
            target.clearHistory();
            target.removeAllViews();
            target.destroy();
        } catch (Exception ignored) {
        }
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
        }, 1500L);
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

