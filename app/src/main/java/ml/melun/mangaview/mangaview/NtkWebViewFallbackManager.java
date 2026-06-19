package ml.melun.mangaview.mangaview;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.reader.ReaderImageCache;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class NtkWebViewFallbackManager {
    private static final String TAG = "ViewerPerf";
    private static final java.math.BigInteger P256_ORDER = new java.math.BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
    private static final java.math.BigInteger P256_HALF_ORDER = P256_ORDER.shiftRight(1);
    private static final long WEBVIEW_LOAD_TIMEOUT_MS = 22_000L;
    private static final long CALLER_WAIT_TIMEOUT_MS = 38_000L;
    private static final long DOCUMENT_READY_WAIT_MS = 18_000L;
    private static final long HIDDEN_CHALLENGE_WAIT_MS = 2_500L;
    private static final long ACK_ONLY_CALLER_WAIT_TIMEOUT_MS = 58_000L;
    private static final long ACK_ONLY_WEBVIEW_FINISH_TIMEOUT_MS = 58_500L;
    private static final long PRIORITY_WOLF_DOCUMENT_READY_WAIT_MS = 2_500L;
    private static final long PRIORITY_WOLF_LOAD_TIMEOUT_MS = 6_000L;
    private static final long PRIORITY_NTK_VIEWER_IMAGE_GRACE_MS = 25_000L;
    private static final long VIEWER_IMAGE_CACHE_TTL_MS = 45_000L;
    private static final long SERVER_ACK_SUCCESS_TTL_MS = 5 * 60_000L;
    private static final ConcurrentHashMap<String, Long> SERVER_ACK_SUCCESS_BY_SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> STRICT_AD_ACK_SUCCESS_BY_SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SCOPED_AD_ACK_BY_SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SCOPED_AD_ACK_C_BY_SCOPE = new ConcurrentHashMap<>();
    private static final long NATIVE_ACK_CHALLENGE_TTL_MS = 45_000L;
    private static final ConcurrentHashMap<String, NativeAckChallenge> NATIVE_ACK_CHALLENGE_BY_SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> REQUEST_KEY_BY_SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RecentRequestKeyMaterial> REQUEST_KEY_MATERIAL_BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RecentRequestKeyMaterial> REQUEST_KEY_MATERIAL_BY_SCOPE = new ConcurrentHashMap<>();
    private static final boolean ACK_ONLY_CLOUDFLARE_NATIVE_FLOW_ONLY = true;
    private static final String ACK_ONLY_PLAIN_CF_TAG = "ntk_ack_plain_cf";
    private static final String ACK_ONLY_PLAIN_CF_PROMOTED_TAG = "ntk_ack_plain_cf_promoted";
    private static final byte[] TINY_GIF_BYTES = new byte[] {
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
            0x01, 0x00, (byte) 0xf0, 0x00, 0x00, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, 0x00, 0x00, 0x00, 0x21,
            (byte) 0xf9, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
    };
    private static final long METRIC_IMAGE_HIT_TTL_MS = 4_000L;
    private static final Object INSTANCE_LOCK = new Object();
    private static final Object VIEWER_IMAGE_FLIGHT_LOCK = new Object();
    private static final String NTK_QUIC_BRIDGE_JS = "(function(){"
            + "if(window.__ntkViewerQuicBridgeInstalled||!window.NtkQuicBridge)return;"
            + "window.__ntkViewerQuicBridgeInstalled=1;"
            + "function parseUrl(u){try{return new URL(u,location.href);}catch(e){return null;}}"
            + "function ntkRootHost(){var h=(location.hostname||'').toLowerCase();return h.indexOf('www.')===0?h.slice(4):h;}"
            + "function hostMatchesRoot(h){h=String(h||'').toLowerCase();if(h.indexOf('www.')===0)h=h.slice(4);var r=ntkRootHost();return !!r&&(h===r||h.slice(-(r.length+1))==='.'+r);}"
            + "function shouldBridge(u,m){var x=parseUrl(u);if(!x||x.protocol!=='https:')return false;if(!hostMatchesRoot(x.hostname))return false;if(x.pathname.indexOf('/cdn-cgi/challenge-platform/')===0)return false;if(x.pathname==='/api/manhwa-images'||x.pathname==='/api/webtoon-images'||x.pathname==='/api/manga-images'||x.pathname==='/api/nv-issue')return false;if(window.__ntkAckOnlyDirectAdApi&&(x.pathname.indexOf('/api/ad/')===0||x.pathname==='/api/client-key/register'))return false;if(x.pathname==='/api/ad/guard-js'||x.pathname==='/api/ad/guard-wasm')return true;return String(m||'GET').toUpperCase()!=='GET';}"
            + "function textBase64(s){return btoa(unescape(encodeURIComponent(s||'')));}"
            + "function bodyBase64(b){try{if(b==null)return '';if(typeof b==='string')return textBase64(b);if(window.URLSearchParams&&b instanceof URLSearchParams)return textBase64(b.toString());if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}return textBase64(String(b));}catch(e){return '';}}"
            + "function bodyBase64Async(b){try{if(b&&window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return bodyBase64(a);});if(b&&window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return bodyBase64(a);});}catch(e){}return Promise.resolve(bodyBase64(b));}"
            + "function bytesFromBase64(b){var bin=atob(b||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}"
            + "function textFromBase64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
            + "function noteAck(url,res,body64){try{if(String(url||'').indexOf('/api/ad/ack')<0)return;var req={},body={};try{req=JSON.parse(textFromBase64(body64)||'{}');}catch(e){}try{body=JSON.parse(textFromBase64(res&&res.bodyBase64)||'{}');}catch(e){}if((res.status||0)!==200||!(body.ok||body.acked||body.status==='ok'||body.status==='acked'))return;var p=req.path||location.pathname||'';window.__ntk_ad_ack_scope=p;window.__ntk_ad_ack_last={scope:p,ts:Date.now(),bridge:true,proof200:true};window.__ntk_ad_ack_tp=req.tp||'';window.__ntk_ad_ack_proof_200=p;try{window.NtkViewerBridge.onAckProof(JSON.stringify({scope:p,tp:req.tp||'',source:'native-fetch-ack-200'}));}catch(e){}window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:p,bridge:true,tp:req.tp||'',proof200:true}}));}catch(e){}}"
            + "function collectHeaders(input,init){var out={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers){new Headers(init.headers).forEach(function(v,k){h.set(k,v);});}h.forEach(function(v,k){out[k]=v;});}catch(e){}return out;}"
            + "function addDefaultHeaders(h){try{if(!h['Origin']&&!h['origin'])h['Origin']=location.origin;if(!h['Referer']&&!h['referer'])h['Referer']=location.href;if(!h['Accept']&&!h['accept'])h['Accept']='*/*';}catch(e){}return h;}"
            + "var nativeFetch=window.fetch;function ackBodyArg(input,init){try{return init&&Object.prototype.hasOwnProperty.call(init,'body')?init.body:((input&&window.Request&&input instanceof Request)?input:null);}catch(_){return null;}}"
            + "function cleanAckObs(v){try{v=String(v||'');if(v.indexOf('/api/m/i?')<0)return '';if(/[^\\x20-\\x7e]/.test(v))return '';return v;}catch(_){return '';}}"
            + "function augmentAckText(t){try{var req=JSON.parse(String(t||'{}')),changed=false;if(!req.tp)return String(t||'');if(!req.requestKeyId&&window.__ntk_request_key_id){req.requestKeyId=window.__ntk_request_key_id;changed=true;}var token=String(req.challengeToken||req.token||''),ch=token&&window.__ntkAckChallengeByToken?window.__ntkAckChallengeByToken[token]:null,chObs=[];if(ch&&ch.impressionUrls&&ch.impressionUrls.length){for(var oi=0;oi<ch.impressionUrls.length;oi++){var ov2=cleanAckObs(ch.impressionUrls[oi]);if(ov2)chObs.push(ov2);}}if(req.observationUrls&&req.observationUrls.length){var a=[];for(var i=0;i<req.observationUrls.length;i++){var ov=cleanAckObs(req.observationUrls[i]);if(ov)a.push(ov);}if(chObs.length>a.length)a=chObs;if(a.length!==req.observationUrls.length||chObs.length>req.observationUrls.length){if(a.length)req.observationUrls=a;else delete req.observationUrls;changed=true;}}if(!req.observationUrls&&chObs.length){req.observationUrls=chObs;changed=true;}if(!changed)return String(t||'');try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckBodyAugmented:true,requestKeyId:!!req.requestKeyId,observationUrls:req.observationUrls?req.observationUrls.length:0,tpLen:String(req.tp||'').length}));}catch(_){}return JSON.stringify(req);}catch(_){return String(t||'');}}"
            + "function augmentedAckCall(input,init){try{var b=ackBodyArg(input,init);if(typeof b!=='string')return null;var nt=augmentAckText(b);if(nt===b)return null;var ni={};if(init)for(var k in init)try{ni[k]=init[k];}catch(_){}ni.body=nt;return{input:input,init:ni,args:[input,ni]};}catch(_){return null;}}"
            + "function replayAckPrereqs(bodyText){try{if(!window.NtkQuicBridge)return;var req={};try{req=JSON.parse(bodyText||'{}');}catch(_){}var obs=req.observationUrls||[],seen=0;for(var oi=0;oi<obs.length;oi++){try{var ou=cleanAckObs(obs[oi]);if(!ou)continue;var oh=addDefaultHeaders({'accept':'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8','sec-fetch-dest':'image','sec-fetch-mode':'no-cors','sec-fetch-site':'same-origin'});var oraw=window.NtkQuicBridge.request(new URL(ou,location.href).href,'GET',JSON.stringify(oh),'');var oo=JSON.parse(oraw||'{}');if((oo.status||0)>=200&&(oo.status||0)<400)seen++;}catch(_){}}var token=String(req.challengeToken||req.token||''),canaryStatus=0;if(token){try{var cb=textBase64(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:req.path||location.pathname||''}));var ch=addDefaultHeaders({'content-type':'application/json','accept':'application/json'});var craw=window.NtkQuicBridge.request(new URL('/api/ad/canary',location.href).href,'POST',JSON.stringify(ch),cb),co=JSON.parse(craw||'{}');canaryStatus=co.status||0;}catch(_){}}try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckPrereqReplay:true,observations:obs.length,seen:seen,canaryStatus:canaryStatus,hasToken:!!token}));}catch(_){}return{seen:seen,canaryStatus:canaryStatus};}catch(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckPrereqReplayError:String(e).slice(0,160)}));}catch(_){}return null;}}"
            + "function bridgeAckFirst(au,callInput,callInit){return bodyBase64Async(ackBodyArg(callInput,callInit)).then(function(b){var hs2=addDefaultHeaders(collectHeaders(callInput,callInit)),bodyText=augmentAckText(textFromBase64(b)),kid='';b=textBase64(bodyText);try{var kr=null;if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(location.href,String(navigator.userAgent||''))||'{}'));else if(window.NtkQuicBridge.ensureViewerBrowserKey)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKey(location.href)||'{}'));if(kr&&kr.keyId){kid=String(kr.keyId);window.__ntk_request_key_id=kid;}if(!kid)kid=String(window.__ntk_request_key_id||'');if(kid){try{var bo=JSON.parse(bodyText||'{}');if(bo.requestKeyId!==kid){bo.requestKeyId=kid;bodyText=JSON.stringify(bo);b=textBase64(bodyText);}}catch(_){}}if(window.NtkQuicBridge.signViewerRequestFormat||window.NtkQuicBridge.signViewerRequest){var signRaw=window.NtkQuicBridge.signViewerRequestFormat?window.NtkQuicBridge.signViewerRequestFormat('POST',location.pathname||'',location.pathname||'',bodyText,'p1363'):window.NtkQuicBridge.signViewerRequest('POST',location.pathname||'',location.pathname||'',bodyText),sig=JSON.parse(String(signRaw||'{}'));if(sig&&sig.ok&&sig.headers)Object.keys(sig.headers).forEach(function(k){hs2[k]=sig.headers[k];});}try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckBridgeFirstSign:true,keyId:String(hs2['x-ntk-key-id']||kid||'').slice(0,12),keyHeader:!!hs2['x-ntk-key-id'],bodyRequestKey:bodyText.indexOf('requestKeyId')>=0}));}catch(_){}}catch(se){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckBridgeFirstSignError:String(se).slice(0,160)}));}catch(_){}}replayAckPrereqs(bodyText);var raw=window.NtkQuicBridge.request(au.href,'POST',JSON.stringify(hs2),b),res=JSON.parse(raw||'{}'),rt=textFromBase64(res.bodyBase64||'');try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckBridgeFirst:true,status:res.status||0,body:String(rt||'').slice(0,180),keyHeader:!!hs2['x-ntk-key-id']}));}catch(_){}noteAck(au.href,res,b);var body={};try{body=JSON.parse(rt||'{}');}catch(_){}if((res.status||0)===200&&(body.ok||body.acked||body.status==='ok'||body.status==='acked'))return new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}});throw new Error('NTK ACK bridge-first failed '+(res.status||0)+' '+String(rt||'').slice(0,120));});}"
            + "function logNativeAckFetch(fetchFn,ctx,args,input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',method=(init&&init.method)||(input&&input.method)||'GET';try{var au=new URL(url,location.href);if(au.pathname==='/api/ad/ack'&&String(method||'GET').toUpperCase()==='POST'){var callInput=input,callInit=init,callArgs=args,nativeAckBody64='';try{var aug=augmentedAckCall(input,init);if(aug){callInput=aug.input;callInit=aug.init;callArgs=aug.args;init=callInit;}}catch(_){}try{var hs=collectHeaders(callInput,callInit),meta={nativeAckFetchStart:true,url:au.href.slice(0,120),headers:Object.keys(hs).sort(),mode:String((callInit&&callInit.mode)||(callInput&&callInput.mode)||''),credentials:String((callInit&&callInit.credentials)||(callInput&&callInput.credentials)||'')};window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify(meta));bodyBase64Async(ackBodyArg(callInput,callInit)).then(function(b){nativeAckBody64=b||'';try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckFetchBody:true,body:decodeURIComponent(escape(atob(b||''))).slice(0,520)}));}catch(_){}try{var txt=textFromBase64(b),nt=augmentAckText(txt);if(false&&nt!==txt&&window.NtkQuicBridge){var rb=textBase64(nt),raw=window.NtkQuicBridge.request(au.href,'POST',JSON.stringify(addDefaultHeaders(hs)),rb),res=JSON.parse(raw||'{}'),rt=textFromBase64(res.bodyBase64||'');try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckAugmentedBridge:true,status:res.status||0,body:String(rt||'').slice(0,180)}));}catch(_){}noteAck(au.href,res,rb);}}catch(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckAugmentedBridgeError:String(e)}));}catch(_){}}});}catch(_){}if(window.NtkQuicBridge)return bridgeAckFirst(au,callInput,callInit).catch(function(be){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckBridgeFirstError:String(be).slice(0,220)}));}catch(_){}return fetchFn.apply(ctx,callArgs);});return fetchFn.apply(ctx,callArgs).then(function(r){try{r.clone().text().then(function(t){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckFetchResponse:true,status:r.status,body:String(t||'').slice(0,240)}));}catch(_){}try{bodyBase64Async(ackBodyArg(callInput,callInit)).then(function(b){if((!b||textFromBase64(b).indexOf('requestKeyId')<0)&&nativeAckBody64)b=nativeAckBody64;noteAck(au.href,{status:r.status,bodyBase64:textBase64(t||'')},b);try{var jj={};try{jj=JSON.parse(t||'{}');}catch(_){}if(r.status===400&&jj&&jj.error==='missing_canary'&&window.NtkQuicBridge){var hs2=addDefaultHeaders(collectHeaders(callInput,callInit)),bodyText=augmentAckText(textFromBase64(b)),kid='';b=textBase64(bodyText);try{var kr=null;if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(location.href,String(navigator.userAgent||''))||'{}'));else if(window.NtkQuicBridge.ensureViewerBrowserKey)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKey(location.href)||'{}'));if(kr&&kr.keyId){kid=String(kr.keyId);window.__ntk_request_key_id=kid;}if(!kid)kid=String(window.__ntk_request_key_id||'');if(kid){try{var bo=JSON.parse(bodyText||'{}');if(bo.requestKeyId!==kid){bo.requestKeyId=kid;bodyText=JSON.stringify(bo);b=textBase64(bodyText);}}catch(_){}}if(window.NtkQuicBridge.signViewerRequestFormat||window.NtkQuicBridge.signViewerRequest){var signRaw=window.NtkQuicBridge.signViewerRequestFormat?window.NtkQuicBridge.signViewerRequestFormat('POST',location.pathname||'',location.pathname||'',bodyText,'p1363'):window.NtkQuicBridge.signViewerRequest('POST',location.pathname||'',location.pathname||'',bodyText),sig=JSON.parse(String(signRaw||'{}'));if(sig&&sig.ok&&sig.headers)Object.keys(sig.headers).forEach(function(k){hs2[k]=sig.headers[k];});}try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckMissingCanaryBridgeRetrySign:true,keyId:String(hs2['x-ntk-key-id']||kid||'').slice(0,12),keyHeader:!!hs2['x-ntk-key-id'],bodyRequestKey:bodyText.indexOf('requestKeyId')>=0}));}catch(_){}}catch(se){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckMissingCanaryBridgeRetrySignError:String(se).slice(0,160)}));}catch(_){}}replayAckPrereqs(bodyText);var raw=window.NtkQuicBridge.request(au.href,'POST',JSON.stringify(hs2),b),res=JSON.parse(raw||'{}'),rt=textFromBase64(res.bodyBase64||'');try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckMissingCanaryBridgeRetry:true,status:res.status||0,body:String(rt||'').slice(0,180),keyHeader:!!hs2['x-ntk-key-id']}));}catch(_){}noteAck(au.href,res,b);}}catch(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckMissingCanaryBridgeRetryError:String(e)}));}catch(_){}}});}catch(_){}});}catch(_){}return r;}).catch(function(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({nativeAckFetchError:true,error:String(e)}));}catch(_){}throw e;});}}catch(_){}return fetchFn.apply(ctx,args);}try{if(nativeFetch&&!window.__ntkNativeFetch){var nf=function(input,init){return logNativeAckFetch(nativeFetch,this,arguments,input,init);};try{nf.__ntkNativeString='function fetch() { [native code] }';}catch(_){}window.__ntkNativeFetch=nf;}}catch(e){}if(nativeFetch){window.fetch=function(input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',method=(init&&init.method)||(input&&input.method)||'GET';if(!shouldBridge(url,method))return logNativeAckFetch(nativeFetch,this,arguments,input,init);return new Promise(function(resolve,reject){try{var absolute=new URL(url,location.href).href,hasInitBody=init&&Object.prototype.hasOwnProperty.call(init,'body'),bodyArg=hasInitBody?init.body:((input&&window.Request&&input instanceof Request)?input:null);bodyBase64Async(bodyArg).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(addDefaultHeaders(collectHeaders(input,init))),body64),res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);resolve(new Response(bytesFromBase64(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}}));}catch(e){reject(e);}},reject);}catch(e){reject(e);}});};}"
            + "try{(function(){if(window.__ntkAckNativeBridgeMirror)return;window.__ntkAckNativeBridgeMirror=1;try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({ackNativeBridgeMirrorDisabled:true,reason:'native-fetch-ack'}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({ackNativeBridgeMirrorInstallError:String(e)}));}catch(_){}}"
            + "var xhrOpen=window.XMLHttpRequest&&XMLHttpRequest.prototype.open;var xhrSend=window.XMLHttpRequest&&XMLHttpRequest.prototype.send;var xhrSetHeader=window.XMLHttpRequest&&XMLHttpRequest.prototype.setRequestHeader;if(xhrOpen&&xhrSend){XMLHttpRequest.prototype.open=function(m,u,a,user,pw){this.__ntkq={method:m||'GET',url:u||'',headers:{}};return xhrOpen.apply(this,arguments);};XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__ntkq&&k)this.__ntkq.headers[String(k)]=String(v);return xhrSetHeader?xhrSetHeader.apply(this,arguments):undefined;};XMLHttpRequest.prototype.send=function(body){var meta=this.__ntkq;if(!meta||!shouldBridge(meta.url,meta.method))return xhrSend.apply(this,arguments);var xhr=this;setTimeout(function(){try{var absolute=new URL(meta.url,location.href).href,body64=bodyBase64(body),path=(new URL(absolute)).pathname;if(path==='/api/ad/ack'&&!body64)return xhrSend.call(xhr,body);var raw=window.NtkQuicBridge.request(absolute,String(meta.method),JSON.stringify(addDefaultHeaders(meta.headers||{})),body64);var res=JSON.parse(raw||'{}');if(!res.ok)throw new Error(res.error||'NTK QUIC bridge failed');noteAck(absolute,res,body64);var headers=res.headers||{},headerText='';Object.keys(headers).forEach(function(k){headerText+=k+': '+headers[k]+'\\r\\n';});var arr=bytesFromBase64(res.bodyBase64||''),response=arr;if(!xhr.responseType||xhr.responseType==='text'){var bin='';for(var i=0;i<arr.length;i++)bin+=String.fromCharCode(arr[i]);response=decodeURIComponent(escape(bin));}Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});Object.defineProperty(xhr,'status',{configurable:true,get:function(){return res.status||200;}});Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return res.statusText||'OK';}});Object.defineProperty(xhr,'response',{configurable:true,get:function(){return response;}});Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return typeof response==='string'?response:'';}});xhr.getAllResponseHeaders=function(){return headerText;};xhr.getResponseHeader=function(n){var l=String(n||'').toLowerCase();for(var k in headers){if(k.toLowerCase()===l)return headers[k];}return null;};['readystatechange','load','loadend'].forEach(function(n){var e=new Event(n);xhr.dispatchEvent(e);var cb=xhr['on'+n];if(typeof cb==='function')cb.call(xhr,e);});}catch(e){var ev=new Event('error');xhr.dispatchEvent(ev);if(typeof xhr.onerror==='function')xhr.onerror.call(xhr,ev);}},0);};}"
            + "var nativeBeacon=navigator.sendBeacon;try{if(nativeBeacon&&!window.__ntkNativeBeacon)window.__ntkNativeBeacon=nativeBeacon;}catch(e){}if(nativeBeacon){navigator.sendBeacon=function(url,data){if(!shouldBridge(url,'POST'))return nativeBeacon.apply(this,arguments);try{var absolute=new URL(url,location.href).href;bodyBase64Async(data).then(function(body64){try{var raw=window.NtkQuicBridge.request(absolute,'POST','{}',body64);noteAck(absolute,JSON.parse(raw||'{}'),body64);}catch(e){}});return true;}catch(e){return false;}};}"
            + "function rearmAck(reason){try{var p=location.pathname||'';if(/^\\/(manhwa|webtoon)\\/[^\\/?#%]+\\/[^\\/?#%]+/.test(p))window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:reason,scope:p}}));}catch(e){}}"
            + "var rearmCount=0,rearmTimer=setInterval(function(){rearmAck('native-bridge-ready');if(++rearmCount>=10)clearInterval(rearmTimer);},250);setTimeout(function(){rearmAck('native-bridge-ready');},0);"
            + "})();";
    static WeakReference<NtkWebViewFallbackManager> instanceRef;
    private static final Map<String, CachedViewerImages> VIEWER_IMAGE_API_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> METRIC_IMAGE_HITS = new ConcurrentHashMap<>();
    private static final Map<String, ViewerImageFlight> VIEWER_IMAGE_FLIGHTS = new HashMap<>();

    private final Object lock = new Object();
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<FetchTask> queue = new ArrayDeque<>();
    private final Map<String, FetchTask> inFlight = new HashMap<>();
    private final Map<Long, ActiveViewerImageFetch> activeViewerImageFetches = new HashMap<>();
    private final Map<WebView, ViewerImageBridge> viewerImageBridgeRefs =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WebView webView;
    private FetchTask activeTask;
    private long nextToken = 1L;
    private long nextViewerImageFetchToken = 1L;
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
            ArrayList<ActiveViewerImageFetch> viewerFetches = new ArrayList<>(activeViewerImageFetches.values());
            activeViewerImageFetches.clear();
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
            for(ActiveViewerImageFetch active : viewerFetches) {
                if(active == null || active.cancel == null)
                    continue;
                try {
                    active.cancel.run();
                } catch (Exception ignored) {
                }
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
        return fetchViewerImageUrls(userAgent, baseUrl, path, ackScopePath, headers, kind,
                workId, episodeId, imagesToken, fallbackCookieHeader, shellHtml, null);
    }

    ArrayList<String> fetchViewerImageUrls(String userAgent, String baseUrl, String path,
                                           String ackScopePath, Map<String, String> headers, String kind,
                                           String workId, String episodeId, String imagesToken,
                                           String fallbackCookieHeader, String shellHtml,
                                           Runnable afterMainPost) {
        if(afterMainPost != null) {
            try {
                afterMainPost.run();
            } catch (Exception e) {
                Log.d(TAG, "ntk_webview_after_main_post_error path=" + path + "," + e);
            }
        }
        boolean ackOnly = "__ack_only__".equals(imagesToken);
        if(ackOnly) {
            Log.d(TAG, "ntk_webview_ack_only_flight path=" + path);
        } else {
            ArrayList<String> cached = new ArrayList<>();
            appendCachedViewerImageUrls(cached, kind, workId, episodeId, path);
            if(cached.size() > 0)
                return cached;
        }
        if(Looper.myLooper() == Looper.getMainLooper() || baseUrl == null || path == null
                || kind == null || workId == null || episodeId == null || imagesToken == null)
            return new ArrayList<>();
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
                    headers, kind, workId, episodeId, imagesToken, fallbackCookieHeader, shellHtml,
                    afterMainPost);
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
                                                           String shellHtml, Runnable afterMainPost) {
        ArrayList<String> urls = new ArrayList<>();
        boolean ackOnly = "__ack_only__".equals(imagesToken);
        if(!ackOnly) {
            appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
            if(urls.size() > 0)
                return urls;
        }
        CountDownLatch done = new CountDownLatch(1);
        ViewerImageResult result = new ViewerImageResult();
        AtomicReference<Runnable> cancelRef = new AtomicReference<>();
        long postedAt = SystemClock.elapsedRealtime();
        mainHandler.post(() -> fetchViewerImageUrlsOnMain(userAgent, baseUrl, path, headers, kind,
                workId, episodeId, imagesToken, ackScopePath, fallbackCookieHeader, shellHtml,
                result, done, cancelRef, postedAt));
        if(afterMainPost != null) {
            try {
                afterMainPost.run();
            } catch (Exception e) {
                Log.d(TAG, "ntk_webview_after_main_post_error path=" + path + "," + e);
            }
        }
        try {
            long deadline = SystemClock.elapsedRealtime()
                    + (ackOnly ? ACK_ONLY_CALLER_WAIT_TIMEOUT_MS : 18_000L);
            while(!done.await(120L, TimeUnit.MILLISECONDS)) {
                if(!ackOnly) {
                    appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
                    if(urls.size() > 0) {
                        cancelViewerImageFetch(cancelRef);
                        return urls;
                    }
                }
                if(SystemClock.elapsedRealtime() >= deadline) {
                    cancelViewerImageFetch(cancelRef);
                    if(ackOnly)
                        Log.d(TAG, "ntk_webview_ack_only_timeout_cancel path=" + path);
                    return urls;
                }
            }
            if(!ackOnly) {
                appendCachedViewerImageUrls(urls, kind, workId, episodeId, path);
                if(urls.size() > 0) {
                    cancelViewerImageFetch(cancelRef);
                    return urls;
                }
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
            if(urls.size() > 0)
                cacheViewerImageUrls(kind, workId, episodeId, path, urls, ackOnly
                        ? "ack-only-discover" : "bridge-result");
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
            long deadline = SystemClock.elapsedRealtime() + 18_000L;
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
            ArrayList<String> normalizedUrls = new ArrayList<>();
            JSONObject body = new JSONObject();
            JSONArray images = new JSONArray();
            for(String url : urls) {
                String normalized = normalizeViewerImageApiSrc(url, kind, workId, episodeId);
                if(normalized.length() == 0 || normalizedUrls.contains(normalized))
                    continue;
                normalizedUrls.add(normalized);
                JSONObject image = new JSONObject();
                image.put("src", normalized);
                images.put(image);
            }
            if(images.length() == 0)
                return;
            body.put("images", images);
            VIEWER_IMAGE_API_CACHE.put(key, new CachedViewerImages(body.toString(), System.currentTimeMillis()));
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, normalizedUrls);
            Log.d(TAG, "ntk_webview_viewer_images_cache_store key=" + key
                    + ",count=" + images.length()
                    + ",reason=" + reason);
        } catch (Exception e) {
            Log.d(TAG, "ntk_webview_viewer_images_cache_store_error key=" + key + "," + e);
        }
    }

    static void rememberViewerImageApiResponse(String endpoint, String path, JSONObject payload,
                                               NtkQuicFetcher.Result result, String reason) {
        if(result == null || result.error != null || result.code < 200 || result.code >= 300
                || result.body == null || result.body.length() == 0 || path == null
                || path.length() == 0)
            return;
        String kind = "";
        if(endpoint != null) {
            if(endpoint.endsWith("/webtoon-images"))
                kind = "webtoon";
            else if(endpoint.endsWith("/manhwa-images") || endpoint.endsWith("/manga-images"))
                kind = "manhwa";
        }
        String workId = "";
        String episodeId = "";
        if(payload != null) {
            workId = payload.optString("workId", "");
            episodeId = payload.optString("episodeId", "");
        }
        String key = viewerImageCacheKey(kind, workId, episodeId, path);
        ArrayList<String> urls = viewerImageUrlsFromApiBody(result.body, kind, workId, episodeId);
        if(key.length() > 0) {
            String cachedBody = urls.size() > 0 ? viewerImageApiBodyFromUrls(urls) : result.body;
            VIEWER_IMAGE_API_CACHE.put(key, new CachedViewerImages(cachedBody, System.currentTimeMillis()));
        }
        if(urls.size() > 0)
            ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(path, urls);
        if(key.length() > 0 || urls.size() > 0)
            Log.d(TAG, "ntk_webview_viewer_images_api_remember key=" + key
                    + ",path=" + path
                    + ",count=" + urls.size()
                    + ",reason=" + reason);
    }

    private static ArrayList<String> viewerImageUrlsFromApiBody(String body, String kind,
                                                                String workId, String episodeId) {
        ArrayList<String> urls = new ArrayList<>();
        if(body == null || body.length() == 0)
            return urls;
        try {
            JSONObject response = new JSONObject(body);
            JSONArray images = response.optJSONArray("images");
            if(images == null || images.length() == 0)
                return urls;
            for(int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String src = image == null ? "" : image.optString("src", "");
                String normalized = normalizeViewerImageApiSrc(src, kind, workId, episodeId);
                if(normalized.length() > 0 && !urls.contains(normalized))
                    urls.add(normalized);
            }
        } catch (Exception e) {
            Log.d(TAG, "ntk_webview_viewer_images_api_remember_error " + e);
        }
        return urls;
    }

    private static String normalizeViewerImageApiSrc(String src, String kind, String workId,
                                                     String episodeId) {
        if(src == null)
            return "";
        String value = src.trim();
        if(value.length() == 0)
            return "";
        String lower = value.toLowerCase(Locale.US);
        if(lower.contains("/board_uploads/") || lower.contains("/banner")
                || lower.contains("/advert") || lower.contains("/ads/")
                || lower.contains("/api/m/"))
            return "";
        boolean pageFile = lower.matches("p\\d{1,5}\\.(jpg|jpeg|png|webp)");
        String safeWorkId = workId == null ? "" : workId.trim();
        String safeEpisodeId = episodeId == null ? "" : episodeId.trim();
        String safeKind = kind == null ? "" : kind.trim().toLowerCase(Locale.US);
        if(pageFile && safeWorkId.length() > 0 && safeEpisodeId.length() > 0) {
            if("webtoon".equals(safeKind))
                return "https://moamoabon.com/blacktoon/episodes/" + safeWorkId + "/" + safeEpisodeId + "/" + value;
            return "https://moamoabon.com/manhwa/" + safeWorkId + "/" + safeEpisodeId + "/" + value;
        }
        if(value.startsWith("//"))
            value = "https:" + value;
        if(value.startsWith("/")) {
            if(lower.startsWith("/manhwa/") || lower.startsWith("/blacktoon/episodes/")
                    || lower.startsWith("/webtoon/"))
                value = "https://moamoabon.com" + value;
            else
                return "";
        }
        String normalizedLower = value.toLowerCase(Locale.US);
        if(!normalizedLower.startsWith("http://") && !normalizedLower.startsWith("https://"))
            return "";
        if(!(normalizedLower.endsWith(".jpg") || normalizedLower.endsWith(".jpeg")
                || normalizedLower.endsWith(".png") || normalizedLower.endsWith(".webp")))
            return "";
        if(normalizedLower.contains("/board_uploads/") || normalizedLower.contains("/banner")
                || normalizedLower.contains("/advert") || normalizedLower.contains("/ads/")
                || normalizedLower.contains("/api/m/"))
            return "";
        return value;
    }

    private static String viewerImageApiBodyFromUrls(List<String> urls) {
        try {
            JSONObject body = new JSONObject();
            JSONArray images = new JSONArray();
            if(urls != null) {
                for(String url : urls) {
                    if(url == null || url.length() == 0)
                        continue;
                    JSONObject image = new JSONObject();
                    image.put("src", url);
                    images.put(image);
                }
            }
            body.put("images", images);
            return body.toString();
        } catch (Exception e) {
            return "{\"images\":[]}";
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
                String normalized = normalizeViewerImageApiSrc(src, kind, workId, episodeId);
                if(normalized.length() > 0 && !urls.contains(normalized))
                    urls.add(normalized);
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
                                            CountDownLatch done, AtomicReference<Runnable> cancelRef,
                                            long postedAt) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            done.countDown();
            return;
        }
        final boolean ackOnlyFrame = "__ack_only__".equals(imagesToken);
        Log.d(TAG, "ntk_images_api_hidden_main_entry path=" + path
                + ",ackOnly=" + ackOnlyFrame
                + ",queueMs=" + (SystemClock.elapsedRealtime() - postedAt));
        final WebView view = new WebView(context);
        final long viewerFetchToken = nextViewerImageFetchToken++;
        final String fallbackCookies = fallbackCookieHeader == null ? "" : fallbackCookieHeader;
        final String webViewSeedCookies = ackOnlyFrame
                ? ackOnlySeedCookieHeader(fallbackCookies) : fallbackCookies;
        final String defaultWebViewUserAgent = WebSettings.getDefaultUserAgent(context);
        final String requestedUserAgent = userAgent;
        final String effectiveUserAgent =
                webViewUserAgentForTask(requestedUserAgent, baseUrl, path, defaultWebViewUserAgent);
        final NtkQuicBridge quicBridge = NtkQuicFetcher.isAvailable()
                ? new NtkQuicBridge(context, effectiveUserAgent, webViewSeedCookies) : null;
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
                activeViewerImageFetches.remove(viewerFetchToken);
                viewerImageBridgeRefs.remove(view);
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
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setLoadsImagesAutomatically(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                settings.setOffscreenPreRaster(false);
            settings.setUserAgentString(effectiveUserAgent);
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(view, true);
            String shellUrl = baseUrl + path;
            boolean modernGuardRoot = isModernNtkGuardRoot(baseUrl);
            boolean realImageFrame = modernGuardRoot && !ackOnlyFrame;
            boolean realMainFrame = modernGuardRoot && (ackOnlyFrame || realImageFrame);
            boolean visibleRealMainFrame = ackOnlyFrame && realMainFrame
                    && isAckOnlyVisibleWebViewProbeEnabled();
            final boolean ackOnlyPlainCloudflarePass =
                    ACK_ONLY_CLOUDFLARE_NATIVE_FLOW_ONLY && ackOnlyFrame && realMainFrame;
            if(realImageFrame || !ackOnlyFrame) {
                settings.setLoadsImagesAutomatically(false);
                settings.setBlockNetworkImage(true);
            }
            if(!visibleRealMainFrame) {
                try {
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                } catch (Exception ignored) {
                }
            }
            if(ackOnlyFrame)
                cancelActiveViewerImageFetchesForForegroundAck(path);
            else
                cancelActiveViewerImageFetchesForNewPath(path);
            activeViewerImageFetches.put(viewerFetchToken,
                    new ActiveViewerImageFetch(path, ackOnlyFrame, finish));
            if(ackOnlyFrame)
                Log.d(TAG, "ntk_webview_ack_only_storage_preserve origin=" + baseUrl);
            if(ackOnlyFrame) {
                expireAckOnlyBloatWebViewCookies(baseUrl, baseUrl + path);
            }
            applyWebViewCookieHeader(baseUrl, webViewSeedCookies);
            if(!ackOnlyFrame)
                applyWebViewCookieHeader(baseUrl + path, webViewSeedCookies);
            if(ackOnlyFrame) {
                String cookieUrl = baseUrl + path;
                String webViewCookies = webViewCookieHeader(cookieUrl);
                String mergedCookies = mergedCookieHeader(cookieUrl, webViewSeedCookies);
                if(hasCookieName(webViewCookies, "cf_clearance")
                        && !hasCookieName(webViewSeedCookies, "cf_clearance")) {
                    Log.d(TAG, "ntk_webview_ack_only_webview_cf_preserved origin=" + baseUrl
                            + ",path=" + path
                            + ",webView=" + summarizeNtkCookieHeaderForLog(webViewCookies)
                            + ",fallback=" + summarizeNtkCookieHeaderForLog(webViewSeedCookies)
                            + ",merged=" + summarizeNtkCookieHeaderForLog(mergedCookies));
                }
                Log.d(TAG, "ntk_webview_ack_only_cookie_summary origin=" + baseUrl
                        + ",path=" + path
                        + ",webView=" + summarizeNtkCookieHeaderForLog(webViewCookies)
                        + ",fallback=" + summarizeNtkCookieHeaderForLog(webViewSeedCookies)
                        + ",merged=" + summarizeNtkCookieHeaderForLog(mergedCookies));
            }
            installViewerImageBridges(view, result, finish, ackOnlyFrame, quicBridge,
                    ackScopePath);
            if(ackOnlyPlainCloudflarePass) {
                view.setTag(ACK_ONLY_PLAIN_CF_TAG);
                Log.d(TAG, "ntk_ack_only_plain_cf_stage path=" + path
                        + ",bridge=installed");
            }
            final int[] ackOnlyMainFrameRetries = new int[]{0};
            final boolean[] ackOnlyCloudflareReloaded = new boolean[]{false};
            final boolean[] ackOnlyPlainClearanceReloaded = new boolean[]{false};
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    String message = consoleMessage == null ? null : consoleMessage.message();
                    if(ackOnlyFrame && message != null && message.contains("__TURNSTILE_CB__")) {
                        Log.d(TAG, "ntk_ack_turnstile_shadow_detected path=" + path
                                + ",url=" + view.getUrl());
                        evaluateAckOnlyTurnstileProbe(view, finished, path, 0L);
                    }
                    if(shouldLogHiddenConsoleMessage(consoleMessage)) {
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
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if(ackOnlyFrame && isAckOnlyPlainCloudflarePending(view.getTag())
                            && !ackOnlyPlainCloudflarePass) {
                        installAckOnlyTurnstileShadowHook(view, finished, path, "page-start");
                        mainHandler.postDelayed(() -> installAckOnlyTurnstileShadowHook(view, finished,
                                path, "page-start-80"), 80L);
                        mainHandler.postDelayed(() -> installAckOnlyTurnstileShadowHook(view, finished,
                                path, "page-start-260"), 260L);
                    }
                    if(ackOnlyFrame && !isAckOnlyPlainCloudflareFlow(view.getTag())) {
                        installAckOnlyCloudflareBridge(view, finished, path, "page-start");
                        mainHandler.postDelayed(() -> installAckOnlyCloudflareBridge(view, finished, path,
                                "page-start-80"), 80L);
                        mainHandler.postDelayed(() -> installAckOnlyCloudflareBridge(view, finished, path,
                                "page-start-260"), 260L);
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "ntk_images_api_hidden_finished url=" + url + ",path=" + path
                            + ",requested=" + requested[0]
                            + ",finished=" + finished[0]
                            + ",match=" + isFinishedDocumentUrl(url, baseUrl, path));
                    if(ackOnlyFrame && !isAckOnlyPlainCloudflareFlow(view.getTag()))
                        installAckOnlyCloudflareBridge(view, finished, path, "page-finished");
                    if(requested[0] || finished[0] || !isFinishedDocumentUrl(url, baseUrl, path))
                        return;
                    if(ackOnlyPlainCloudflarePass && isAckOnlyPlainCloudflarePending(view.getTag())) {
                        if(fastPromoteAckOnlySyntheticShell(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                path, baseUrl, ackScopePath, kind, workId, episodeId,
                                imagesToken, "page-finished"))
                            return;
                        maybePromoteAckOnlyPlainCloudflarePass(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, "page-finished");
                        return;
                    }
                    if(ackOnlyFrame && "__ack_only__".equals(imagesToken)
                            && scriptRequests[0] >= viewerScriptRequestLimit(imagesToken)) {
                        scheduleAckOnlyPageFinishedRetry(view, finished, requested, scriptRequests,
                                baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken,
                                1_700L);
                    }
                    requested[0] = true;
                    mainHandler.post(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests, baseUrl, path,
                            ackScopePath, kind, workId, episodeId, imagesToken));
                    if(!ackOnlyFrame) {
                        mainHandler.postDelayed(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests,
                                baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken), 180L);
                        mainHandler.postDelayed(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests,
                                baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken), 520L);
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if(request != null && request.isForMainFrame() && !finished[0]) {
                        if(ackOnlyFrame && ackOnlyMainFrameRetries[0] < 2) {
                            ackOnlyMainFrameRetries[0]++;
                            requested[0] = false;
                            Log.d(TAG, "ntk_images_api_ack_only_mainframe_retry path=" + path
                                    + ",retry=" + ackOnlyMainFrameRetries[0]
                                    + ",code=" + (error == null ? 0 : error.getErrorCode())
                                    + ",description=" + (error == null ? "" : error.getDescription()));
                            mainHandler.postDelayed(() -> {
                                if(!finished[0] && view != null) {
                                    if(ackOnlyFrame) {
                                        Log.d(TAG, "ntk_ack_only_plain_cf_shell_reload path=" + path
                                                + ",retry=" + ackOnlyMainFrameRetries[0]);
                                        view.loadDataWithBaseURL(shellUrl, ackOnlySyntheticShellHtml(path),
                                                "text/html", "UTF-8", shellUrl);
                                    } else {
                                        view.loadUrl(shellUrl, webViewHeaders(headers));
                                    }
                                }
                            }, 900L * ackOnlyMainFrameRetries[0]);
                            return;
                        }
                        finish.run();
                    }
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if(!realMainFrame && request != null && request.isForMainFrame()
                            && isFinishedDocumentUrl(request.getUrl() == null ? "" : request.getUrl().toString(),
                            baseUrl, path)) {
                        return viewerShellResponse(shellHtml, shellGuardVersion);
                    }
                    if(realMainFrame && !ackOnlyFrame && !visibleRealMainFrame
                            && shouldBlockHiddenRealFrameDecorativeRequest(request))
                        return emptyWebViewResponse("text/plain");
                    if(ackOnlyPlainCloudflarePass && isAckOnlyPlainCloudflarePending(view.getTag())) {
                        if(realMainFrame)
                            logRealFrameRequest(request);
                        return super.shouldInterceptRequest(view, request);
                    }
                    if(realMainFrame)
                        logRealFrameRequest(request);
                    if(realMainFrame && ackOnlyFrame) {
                        WebResourceResponse guardResponse = siteGuardResourceResponse(request, quicBridge);
                        if(guardResponse != null)
                            return guardResponse;
                    }
                    if(realMainFrame && ackOnlyFrame && shouldBlockHiddenAckOnlyHeavyRequest(request))
                        return emptyWebViewResponse("text/plain");
                    if(realMainFrame && ackOnlyFrame && shouldUseDirectWebViewForAckOnlyApi(request))
                        return super.shouldInterceptRequest(view, request);
                    if(realMainFrame && request != null && request.isForMainFrame())
                        return super.shouldInterceptRequest(view, request);
                    WebResourceResponse response = interceptViewerQuicRequest(userAgent, webViewSeedCookies, request);
                    return response == null ? super.shouldInterceptRequest(view, request) : response;
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                WebResourceResponse errorResponse) {
                    super.onReceivedHttpError(view, request, errorResponse);
                    if(ackOnlyFrame && shouldLogAckOnlyResourceEvent(request)) {
                        Log.d(TAG, "ntk_ack_only_http_error main="
                                + (request != null && request.isForMainFrame())
                                + ",status=" + (errorResponse == null ? 0 : errorResponse.getStatusCode())
                                + ",url=" + (request == null || request.getUrl() == null ? "" : request.getUrl()));
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    Log.d(TAG, "ntk_ack_only_render_process_gone path=" + path
                            + ",didCrash=" + (detail != null && detail.didCrash())
                            + ",priority=" + (detail == null ? 0 : detail.rendererPriorityAtExit())
                            + ",url=" + (view == null ? "" : view.getUrl()));
                    finish.run();
                    return true;
                }
            });
            Activity activity = MainApplication.currentActivity;
            if(activity != null && !activity.isFinishing() && activity.getWindow() != null) {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                ViewGroup content = activity.findViewById(android.R.id.content);
                ViewGroup hiddenParent = content == null ? decor : content;
                boolean ackOnlyInteractiveCloudflareFrame = ackOnlyPlainCloudflarePass && realMainFrame
                        && !ACK_ONLY_CLOUDFLARE_NATIVE_FLOW_ONLY;
                boolean visibleAckFrame = visibleRealMainFrame
                        || (ackOnlyFrame && realMainFrame && !ackOnlyPlainCloudflarePass);
                view.setAlpha(visibleAckFrame ? 1f : 0.01f);
                view.setClickable(visibleRealMainFrame || ackOnlyInteractiveCloudflareFrame);
                view.setFocusable(visibleRealMainFrame || ackOnlyInteractiveCloudflareFrame);
                view.setFocusableInTouchMode(visibleRealMainFrame || ackOnlyInteractiveCloudflareFrame);
                FrameLayout.LayoutParams params = (realImageFrame || !ackOnlyFrame || !visibleRealMainFrame)
                        ? new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT)
                        : new FrameLayout.LayoutParams(
                        Math.max(390, decor.getWidth()), Math.max(720, decor.getHeight()),
                        Gravity.TOP | Gravity.LEFT);
                if(realMainFrame) {
                    if(visibleRealMainFrame) {
                        decor.addView(view, params);
                        view.setElevation(10000f);
                        view.setTranslationZ(10000f);
                        view.bringToFront();
                        view.requestFocus();
                        view.requestFocusFromTouch();
                    } else {
                        hiddenParent.addView(view, 0, params);
                        if(ackOnlyInteractiveCloudflareFrame) {
                            mainHandler.postDelayed(() -> {
                                if(!finished[0] && view.getParent() != null) {
                                    view.requestFocus();
                                    view.requestFocusFromTouch();
                                    Log.d(TAG, "ntk_ack_only_cf_focus_activate path=" + path
                                            + ",view=" + ackOnlyWebViewMetricSummary(view));
                                }
                            }, 120L);
                        }
                    }
                } else {
                    hiddenParent.addView(view, 0, params);
                }
            }
            Log.d(TAG, "ntk_images_api_hidden_document path=" + path
                    + ",modernGuard=" + modernGuardRoot
                    + ",realMainFrame=" + realMainFrame
                    + ",realImageFrame=" + realImageFrame
                    + ",visibleRealMainFrame=" + visibleRealMainFrame
                + ",ua=" + summarizeUserAgentForLog(effectiveUserAgent)
                + ",inputUa=" + summarizeUserAgentForLog(userAgent)
                + ",defaultUa=" + summarizeUserAgentForLog(defaultWebViewUserAgent)
                + ",shellGuardVersion=" + shellGuardVersion);
            if(realMainFrame) {
                if(ackOnlyFrame) {
                    if(!ackOnlyPlainCloudflarePass) {
                        installAckOnlyCloudflareBridge(view, finished, path, "before-load");
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 60L);
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 180L);
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 500L);
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 1_200L);
                        scheduleAckOnlyStaleCloudflareReload(view, finished, ackOnlyCloudflareReloaded,
                                quicBridge, baseUrl, path, shellUrl, headers, 3_400L);
                    } else {
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 1_200L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 2_500L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 3_400L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 4_800L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 8_500L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 13_500L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 20_000L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 28_000L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 36_000L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 45_000L);
                        scheduleAckOnlyPlainCloudflarePromotion(view, finished, requested, scriptRequests,
                                result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                                webViewSeedCookies, path,
                                ackOnlyPlainClearanceReloaded, baseUrl, ackScopePath, kind, workId,
                                episodeId, imagesToken, 52_000L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 360L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 1_400L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 3_600L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 7_200L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 11_500L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 18_000L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 24_000L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 32_000L);
                        scheduleAckOnlyPageActivationTouch(view, finished, path, 42_000L);
                    }
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 1_800L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 3_200L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 5_800L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 9_500L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 12_500L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 16_500L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 22_000L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 28_000L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 36_000L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 45_000L);
                    scheduleAckOnlyTurnstileProbe(view, finished, path, 52_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 120L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 300L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 450L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 900L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 1_500L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 2_400L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 3_800L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 7_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 12_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 20_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 32_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 42_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 52_000L);
                } else {
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 120L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 450L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 1_500L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 3_800L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 7_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 12_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 20_000L);
                    scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 30_000L);
                }
            } else {
                scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 120L);
                scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 700L);
                scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 1_800L);
                scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 3_800L);
                scheduleViewerImageFetch(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken, 7_000L);
            }
            if(ackOnlyFrame) {
                Log.d(TAG, "ntk_ack_only_synthetic_shell_load path=" + path
                        + ",url=" + shellUrl
                        + ",plainCf=" + ackOnlyPlainCloudflarePass);
                view.loadDataWithBaseURL(shellUrl, ackOnlySyntheticShellHtml(path),
                        "text/html", "UTF-8", shellUrl);
                if(ackOnlyPlainCloudflarePass) {
                    scheduleAckOnlySyntheticShellPromote(view, finished, requested, scriptRequests,
                            result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                            path, baseUrl, ackScopePath, kind, workId, episodeId, imagesToken,
                            160L, "shell-load-160");
                    scheduleAckOnlySyntheticShellPromote(view, finished, requested, scriptRequests,
                            result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                            path, baseUrl, ackScopePath, kind, workId, episodeId, imagesToken,
                            420L, "shell-load-420");
                    scheduleAckOnlySyntheticShellPromote(view, finished, requested, scriptRequests,
                            result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                            path, baseUrl, ackScopePath, kind, workId, episodeId, imagesToken,
                            900L, "shell-load-900");
                }
            } else {
                if(realMainFrame) {
                    view.loadUrl(shellUrl, webViewHeaders(headers));
                } else if(modernGuardRoot) {
                    Log.d(TAG, "ntk_images_api_shell_document_load path=" + path
                            + ",url=" + shellUrl
                            + ",guardVersion=" + shellGuardVersion
                            + ",htmlLen=" + (shellHtml == null ? 0 : shellHtml.length()));
                    view.loadDataWithBaseURL(shellUrl,
                            viewerShellHtml(shellHtml, shellGuardVersion),
                            "text/html", "UTF-8", shellUrl);
                } else {
                    view.loadUrl(shellUrl, webViewHeaders(headers));
                }
            }
            if(ackOnlyFrame && !ackOnlyPlainCloudflarePass) {
                scheduleAckOnlyCloudflareBridge(view, finished, path, 40L);
                scheduleAckOnlyCloudflareBridge(view, finished, path, 140L);
                scheduleAckOnlyCloudflareBridge(view, finished, path, 360L);
                scheduleAckOnlyCloudflareBridge(view, finished, path, 900L);
                scheduleAckOnlyCloudflareBridge(view, finished, path, 2_000L);
            }
            mainHandler.postDelayed(finish,
                    ackOnlyFrame ? ACK_ONLY_WEBVIEW_FINISH_TIMEOUT_MS : 84_000L);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            finish.run();
        }
    }

    private void cancelActiveViewerImageFetchesForForegroundAck(String path) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> cancelActiveViewerImageFetchesForForegroundAck(path));
            return;
        }
        ArrayList<ActiveViewerImageFetch> cancels = new ArrayList<>();
        for(ActiveViewerImageFetch active : activeViewerImageFetches.values()) {
            if(!active.ackOnly)
                cancels.add(active);
        }
        if(cancels.size() == 0)
            return;
        Log.d(TAG, "ntk_viewer_image_foreground_ack_preempt path=" + path
                + ",count=" + cancels.size());
        for(ActiveViewerImageFetch active : cancels) {
            try {
                active.cancel.run();
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelActiveViewerImageFetchesForNewPath(String path) {
        if(Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> cancelActiveViewerImageFetchesForNewPath(path));
            return;
        }
        ArrayList<ActiveViewerImageFetch> cancels = new ArrayList<>();
        for(ActiveViewerImageFetch active : activeViewerImageFetches.values()) {
            if(!active.ackOnly)
                cancels.add(active);
        }
        if(cancels.size() == 0)
            return;
        Log.d(TAG, "ntk_viewer_image_new_path_preempt path=" + path
                + ",count=" + cancels.size());
        for(ActiveViewerImageFetch active : cancels) {
            try {
                active.cancel.run();
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelViewerImageFetch(AtomicReference<Runnable> cancelRef) {
        Runnable cancel = cancelRef == null ? null : cancelRef.getAndSet(null);
        if(cancel == null)
            return;
        mainHandler.post(cancel);
    }

    private void installViewerImageBridges(WebView view, ViewerImageResult result, Runnable finish,
                                           boolean ackOnlyFrame, NtkQuicBridge quicBridge,
                                           String ackScopePath) {
        try {
            synchronized (viewerImageBridgeRefs) {
                if(viewerImageBridgeRefs.containsKey(view))
                    return;
            }
            ViewerImageBridge bridge = new ViewerImageBridge(result, finish, mainHandler,
                    ackOnlyFrame, ackScopePath);
            viewerImageBridgeRefs.put(view, bridge);
            view.addJavascriptInterface(bridge, "NtkViewerBridge");
            view.addJavascriptInterface(bridge, "NtkAckBridge");
            view.addJavascriptInterface(bridge, "__NtkViewerBridgeNative");
            view.addJavascriptInterface(bridge, "__NtkAckBridgeNative");
            view.addJavascriptInterface(bridge, "MangaViewerNativeViewerBridge");
            view.addJavascriptInterface(bridge, "MangaViewerNativeAckBridge");
            if(quicBridge != null)
                view.addJavascriptInterface(quicBridge, "NtkQuicBridge");
        } catch (Exception e) {
            Log.d(TAG, "ntk_viewer_image_bridge_install_error error=" + e);
        }
    }

    private boolean fastPromoteAckOnlySyntheticShell(WebView view, boolean[] finished,
                                                     boolean[] requested, int[] scriptRequests,
                                                     ViewerImageResult result, Runnable finish,
                                                     boolean ackOnlyFrame, NtkQuicBridge quicBridge,
                                                     String shellUrl, Map<String, String> headers,
                                                     String path, String baseUrl, String ackScopePath,
                                                     String kind, String workId, String episodeId,
                                                     String imagesToken,
                                                     String reason) {
        if(finished[0] || view == null || !ackOnlyFrame
                || !"__ack_only__".equals(imagesToken)
                || !isAckOnlyPlainCloudflarePending(view.getTag()))
            return false;
        String currentUrl = view.getUrl();
        if(!isFinishedDocumentUrl(currentUrl, baseUrl, path))
            return false;
        view.setTag(ACK_ONLY_PLAIN_CF_PROMOTED_TAG);
        requested[0] = false;
        scriptRequests[0] = 0;
        Log.d(TAG, "ntk_ack_only_synthetic_shell_fast_promote path=" + path
                + ",reason=" + reason
                + ",url=" + currentUrl
                + ",cookies=" + summarizeNtkCookieHeaderForLog(webViewCookieHeader(shellUrl)));
        installViewerImageBridges(view, result, finish, ackOnlyFrame, quicBridge, ackScopePath);
        evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken);
        return true;
    }

    private void scheduleAckOnlySyntheticShellPromote(WebView view, boolean[] finished,
                                                      boolean[] requested, int[] scriptRequests,
                                                      ViewerImageResult result, Runnable finish,
                                                      boolean ackOnlyFrame, NtkQuicBridge quicBridge,
                                                      String shellUrl, Map<String, String> headers,
                                                      String path, String baseUrl, String ackScopePath,
                                                      String kind, String workId, String episodeId,
                                                      String imagesToken, long delayMs,
                                                      String reason) {
        mainHandler.postDelayed(() -> fastPromoteAckOnlySyntheticShell(view, finished, requested,
                scriptRequests, result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                path, baseUrl, ackScopePath, kind, workId, episodeId, imagesToken, reason), delayMs);
    }

    private void scheduleAckOnlyPageFinishedRetry(WebView view, boolean[] finished,
                                                  boolean[] requested, int[] scriptRequests,
                                                  String baseUrl, String path,
                                                  String ackScopePath, String kind,
                                                  String workId, String episodeId,
                                                  String imagesToken, long delayMs) {
        mainHandler.postDelayed(() -> {
            String effectiveAckScope = ackScopePath == null || ackScopePath.length() == 0
                    ? path : ackScopePath;
            boolean ackDone = isModernNtkGuardRoot(baseUrl)
                    ? hasRecentStrictAdAckSuccess(effectiveAckScope)
                    : hasRecentServerAckSuccess(effectiveAckScope);
            if(finished[0] || view == null
                    || scriptRequests[0] < viewerScriptRequestLimit(imagesToken)
                    || ackDone) {
                return;
            }
            view.evaluateJavascript("(function(){try{return JSON.stringify({"
                            + "started:!!window.__ntkDirectAckStartedAt,"
                            + "proofed:typeof ackProofed==='function'&&ackProofed(),"
                            + "acked:typeof acked==='function'&&acked(),"
                            + "href:String(location.href||'').slice(0,160)});}"
                            + "catch(e){return 'ERR:'+String(e);}})()",
                    value -> {
                        if(finished[0] || view == null
                                || scriptRequests[0] < viewerScriptRequestLimit(imagesToken))
                            return;
                        String state = unquoteJavascriptString(value == null ? "" : value);
                        boolean started = state.contains("\"started\":true");
                        boolean proofed = state.contains("\"proofed\":true");
                        boolean acked = state.contains("\"acked\":true");
                        Log.d(TAG, "ntk_ack_only_page_finished_retry_check path=" + path
                                + ",url=" + view.getUrl()
                                + ",state=" + state
                                + ",attempts=" + scriptRequests[0]);
                        if(started || proofed || acked)
                            return;
                        scriptRequests[0] = 0;
                        requested[0] = true;
                        Log.d(TAG, "ntk_ack_only_page_finished_retry_start path=" + path
                                + ",url=" + view.getUrl()
                                + ",delayMs=" + delayMs);
                        evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                                baseUrl, path, ackScopePath, kind, workId, episodeId,
                                imagesToken);
                    });
        }, delayMs);
    }

    private void maybePromoteAckOnlyPlainCloudflarePass(WebView view, boolean[] finished,
                                                        boolean[] requested, int[] scriptRequests,
                                                        ViewerImageResult result, Runnable finish,
                                                        boolean ackOnlyFrame, NtkQuicBridge quicBridge,
                                                        String shellUrl, Map<String, String> headers,
                                                        String webViewSeedCookies, String path,
                                                        boolean[] clearanceReloaded,
                                                        String baseUrl, String ackScopePath,
                                                        String kind, String workId, String episodeId,
                                                        String imagesToken,
                                                        String reason) {
        if(finished[0] || view == null)
            return;
        String script = buildAckOnlyPlainCloudflareProbeScript();
        view.evaluateJavascript(script, value -> {
            if(finished[0] || view == null || !isAckOnlyPlainCloudflarePending(view.getTag()))
                return;
            String state = unquoteJavascriptString(value);
            Log.d(TAG, "ntk_ack_only_plain_cf_probe path=" + path
                    + ",reason=" + reason
                    + ",url=" + view.getUrl()
                    + ",view=" + ackOnlyWebViewMetricSummary(view)
                    + ",state=" + state);
            String bridgeCookieSummary = summarizeNtkCookieHeaderForLog(webViewCookieHeader(shellUrl));
            boolean bridgeHasClearance = bridgeCookieSummary.contains("cf=true");
            boolean bridgeHasGuardCookie = bridgeCookieSummary.contains("adGuardL=true")
                    || bridgeCookieSummary.contains("adAckC=true");
            boolean readyComplete = state.contains("\"ready\":\"complete\"");
            boolean hasClearance = state.contains("\"hasCf\":true") || bridgeHasClearance;
            boolean hasGuardCookie = state.contains("\"hasAdGuard\":true") || bridgeHasGuardCookie;
            boolean hasSentinel = state.contains("init-html-sentinel")
                    || state.contains("__ntk_ib_ok");
            boolean cloudflarePage = state.contains("\"cf\":true");
            String currentUrl = view.getUrl();
            boolean cloudflareUrl = (currentUrl != null && currentUrl.contains("__cf_chl"))
                    || state.contains("__cf_chl");
            boolean emptyShell = state.contains("\"title\":\"\"")
                    && state.contains("\"body\":\"\"");
            if(hasClearance && hasGuardCookie && !cloudflareUrl && !cloudflarePage
                    && !hasSentinel && emptyShell
                    && clearanceReloaded != null && !clearanceReloaded[0]) {
                Log.d(TAG, "ntk_ack_only_plain_cf_wait_empty_shell path=" + path
                        + ",reason=" + reason
                        + ",url=" + view.getUrl()
                        + ",readyComplete=" + readyComplete
                        + ",cookie=" + bridgeCookieSummary);
                return;
            }
            if(hasClearance && cloudflarePage) {
                if(cloudflareUrl) {
                    Log.d(TAG, "ntk_ack_only_plain_cf_wait_challenge path=" + path
                            + ",reason=" + reason
                            + ",url=" + view.getUrl()
                            + ",readyComplete=" + readyComplete
                            + ",cookie=" + bridgeCookieSummary);
                } else {
                    Log.d(TAG, "ntk_ack_only_plain_cf_wait_clearance_page path=" + path
                        + ",reason=" + reason
                        + ",url=" + view.getUrl()
                        + ",readyComplete=" + readyComplete
                        + ",hasGuard=" + hasGuardCookie
                        + ",cookie=" + bridgeCookieSummary);
                }
                return;
            }
            if(ackOnlyPlainCloudflareFailFast(reason, readyComplete, cloudflarePage, hasClearance)) {
                Log.d(TAG, "ntk_ack_only_plain_cf_fail_fast path=" + path
                        + ",reason=" + reason
                        + ",url=" + view.getUrl());
                finish.run();
                return;
            }
            if(hasClearance && hasGuardCookie && !cloudflareUrl
                    && (!state.contains("ERR:") || (bridgeHasClearance && bridgeHasGuardCookie))) {
                view.setTag(ACK_ONLY_PLAIN_CF_PROMOTED_TAG);
                requested[0] = false;
                scriptRequests[0] = 0;
                Log.d(TAG, "ntk_ack_only_plain_cf_cookie_bridge_promote path=" + path
                        + ",reason=" + reason
                        + ",url=" + view.getUrl()
                        + ",readyComplete=" + readyComplete
                        + ",cloudflarePage=" + cloudflarePage
                        + ",cookies=" + bridgeCookieSummary
                        + ",stateErr=" + state.contains("ERR:"));
                installViewerImageBridges(view, result, finish, ackOnlyFrame, quicBridge,
                        ackScopePath);
                mainHandler.post(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests,
                        baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken));
                return;
            }
            boolean canPromote = readyComplete
                    || (hasClearance && hasSentinel);
            if(cloudflareUrl || cloudflarePage || state.contains("ERR:") || !canPromote)
                return;
            view.setTag(ACK_ONLY_PLAIN_CF_PROMOTED_TAG);
            requested[0] = false;
            scriptRequests[0] = 0;
            Log.d(TAG, "ntk_ack_only_plain_cf_promote path=" + path
                    + ",reason=" + reason
                    + ",url=" + view.getUrl()
                    + ",readyComplete=" + readyComplete
                    + ",hasGuard=" + hasGuardCookie
                    + ",hasSentinel=" + hasSentinel
                    + ",cookies=" + summarizeNtkCookieHeaderForLog(webViewCookieHeader(shellUrl)));
            installViewerImageBridges(view, result, finish, ackOnlyFrame, quicBridge,
                    ackScopePath);
            mainHandler.post(() -> evaluateViewerImageFetchScript(view, finished, scriptRequests,
                    baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken));
        });
    }

    private static String buildAckOnlyPlainCloudflareProbeScript() {
        return "(function(){try{"
                + "function s(v,n){return String(v==null?'':v).replace(/\\s+/g,' ').slice(0,n||120);}"
                + "var h=String(location.href||''),t=String(document.title||''),"
                + "b=document.body?s(document.body.innerText||document.body.textContent||'',220):'',"
                + "ck=String(document.cookie||''),l=(h+' '+t+' '+b).toLowerCase(),"
                + "cf=l.indexOf('__cf_chl')>=0||l.indexOf('/cdn-cgi/challenge-platform')>=0||l.indexOf('just a moment')>=0||l.indexOf('performing security verification')>=0||l.indexOf('challenges.cloudflare.com')>=0;"
                + "var fs=[],ifs=document.querySelectorAll('iframe'),i,f,src,r,st;"
                + "for(i=0;i<ifs.length&&i<6;i++){f=ifs[i];src=f.getAttribute('src')||f.src||'';r=f.getBoundingClientRect?f.getBoundingClientRect():null;st=window.getComputedStyle?getComputedStyle(f):null;fs.push({src:s(src,150),w:r?Math.round(r.width):0,h:r?Math.round(r.height):0,x:r?Math.round(r.left):0,y:r?Math.round(r.top):0,display:st?s(st.display,24):'',visibility:st?s(st.visibility,24):''});}"
                + "var ce=document.querySelector('#challenge-error-text,.cf-error-details,[class*=\"challenge-error\"],[id*=\"challenge-error\"]');"
                + "var gl='';try{var c=document.createElement('canvas'),g=c.getContext('webgl')||c.getContext('experimental-webgl');if(g){var dbg=g.getExtension('WEBGL_debug_renderer_info');gl=dbg?(String(g.getParameter(dbg.UNMASKED_VENDOR_WEBGL))+'|'+String(g.getParameter(dbg.UNMASKED_RENDERER_WEBGL))):(String(g.getParameter(g.VENDOR))+'|'+String(g.getParameter(g.RENDERER)));}}catch(_){}"
                + "var ad=null;try{if(navigator.userAgentData){ad={mobile:!!navigator.userAgentData.mobile,brands:(navigator.userAgentData.brands||[]).map(function(x){return String(x.brand||'')+':'+String(x.version||'');}).slice(0,4)};}}catch(_){}"
                + "return JSON.stringify({href:h,ready:String(document.readyState||''),cf:cf,hasCf:/cf_clearance=/.test(ck),hasAdGuard:/ad_guard_l=/.test(ck),cookieLen:ck.length,title:s(t,80),body:b,visible:String(document.visibilityState||''),focus:document.hasFocus?document.hasFocus():false,webdriver:!!navigator.webdriver,cookieEnabled:!!navigator.cookieEnabled,touch:Number(navigator.maxTouchPoints||0),hw:Number(navigator.hardwareConcurrency||0),deviceMemory:Number(navigator.deviceMemory||0),secure:!!window.isSecureContext,activation:(navigator.userActivation?String(navigator.userActivation.isActive)+','+String(navigator.userActivation.hasBeenActive):''),viewport:String(window.innerWidth||0)+'x'+String(window.innerHeight||0),screen:String((window.screen&&screen.width)||0)+'x'+String((window.screen&&screen.height)||0),frames:fs,turnstile:!!document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"],iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]'),error:ce?s(ce.innerText||ce.textContent||'',160):'',gl:s(gl,160),ua:s(navigator.userAgent||'',160),uaData:ad});"
                + "}catch(e){return 'ERR:'+String(e);}})()";
    }

    private static boolean isAckOnlyPlainCloudflarePending(Object tag) {
        return ACK_ONLY_PLAIN_CF_TAG.equals(tag);
    }

    private static boolean isAckOnlyPlainCloudflareFlow(Object tag) {
        return ACK_ONLY_PLAIN_CF_TAG.equals(tag)
                || ACK_ONLY_PLAIN_CF_PROMOTED_TAG.equals(tag);
    }

    private static String ackOnlyWebViewMetricSummary(WebView view) {
        if(view == null)
            return "";
        try {
            return "attached=" + view.isAttachedToWindow()
                    + ";shown=" + view.isShown()
                    + ";hasFocus=" + view.hasFocus()
                    + ";windowFocus=" + view.hasWindowFocus()
                    + ";focusable=" + view.isFocusable()
                    + ";clickable=" + view.isClickable()
                    + ";alpha=" + view.getAlpha()
                    + ";size=" + view.getWidth() + "x" + view.getHeight()
                    + ";visibility=" + view.getVisibility()
                    + ";windowVisibility=" + view.getWindowVisibility();
        } catch (Exception e) {
            return "ERR:" + e.getClass().getSimpleName();
        }
    }

    private boolean isAckOnlyVisibleWebViewProbeEnabled() {
        if(debugBooleanSystemProperty("debug.ntk.ack_visible_webview"))
            return true;
        try {
            String value = android.provider.Settings.Global.getString(
                    context.getContentResolver(), "debug_ntk_ack_visible_webview");
            return parseDebugBoolean(value);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean debugBooleanSystemProperty(String name) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties.getMethod("get", String.class, String.class)
                    .invoke(null, name, "");
            return parseDebugBoolean(value == null ? "" : String.valueOf(value));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean parseDebugBoolean(String value) {
        if(value == null)
            return false;
        String normalized = value.trim().toLowerCase(Locale.US);
        return "1".equals(normalized)
                || "true".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    private static boolean ackOnlyPlainCloudflareFailFast(String reason, boolean readyComplete,
                                                          boolean cloudflarePage,
                                                          boolean hasClearance) {
        if(!readyComplete || !cloudflarePage)
            return false;
        if(reason == null)
            return false;
        if(reason.startsWith("delay-")) {
            try {
                long delayMs = Long.parseLong(reason.substring("delay-".length()));
                return delayMs >= 45_000L;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private void scheduleAckOnlyPlainCloudflarePromotion(WebView view, boolean[] finished,
                                                         boolean[] requested, int[] scriptRequests,
                                                         ViewerImageResult result, Runnable finish,
                                                         boolean ackOnlyFrame, NtkQuicBridge quicBridge,
                                                         String shellUrl, Map<String, String> headers,
                                                         String webViewSeedCookies, String path,
                                                         boolean[] clearanceReloaded,
                                                         String baseUrl, String ackScopePath,
                                                         String kind, String workId, String episodeId,
                                                         String imagesToken,
                                                         long delayMs) {
        mainHandler.postDelayed(() -> maybePromoteAckOnlyPlainCloudflarePass(view, finished, requested,
                scriptRequests, result, finish, ackOnlyFrame, quicBridge, shellUrl, headers,
                webViewSeedCookies, path, clearanceReloaded, baseUrl, ackScopePath, kind, workId, episodeId,
                imagesToken, "delay-" + delayMs), delayMs);
    }

    private void installAckOnlyTurnstileShadowHook(WebView view, boolean[] finished, String path,
                                                   String reason) {
        if(finished[0] || view == null)
            return;
        try {
            view.evaluateJavascript(ml.melun.mangaview.activity.CaptchaActivity.SHADOW_HOOK_JS,
                    value -> Log.d(TAG, "ntk_ack_turnstile_shadow_hook path=" + path
                            + ",reason=" + reason
                            + ",url=" + view.getUrl()));
        } catch (Exception e) {
            Log.d(TAG, "ntk_ack_turnstile_shadow_hook_error path=" + path
                    + ",reason=" + reason
                    + ",error=" + e);
        }
    }

    private void scheduleAckOnlyTurnstileShadowHook(WebView view, boolean[] finished,
                                                    String path, long delayMs) {
        mainHandler.postDelayed(() -> installAckOnlyTurnstileShadowHook(view, finished,
                path, "delay-" + delayMs), delayMs);
    }

    private void scheduleViewerImageFetch(WebView view, boolean[] finished, int[] scriptRequests,
                                          String baseUrl, String path,
                                          String ackScopePath,
                                          String kind, String workId, String episodeId,
                                          String imagesToken, long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || view == null)
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
            if(scriptRequests[0] >= viewerScriptRequestLimit(imagesToken)) {
                maybeRetryStaleAckOnlyFetch(view, finished, scriptRequests, baseUrl, path,
                        ackScopePath, kind, workId, episodeId, imagesToken, delayMs);
                return;
            }
            if("__ack_only__".equals(imagesToken)
                    && isAckOnlyPlainCloudflarePending(view.getTag())) {
                Log.d(TAG, "ntk_ack_only_plain_cf_eval_defer path=" + path
                        + ",delay=" + delayMs
                        + ",url=" + currentUrl);
                return;
            }
            evaluateViewerImageFetchScript(view, finished, scriptRequests, baseUrl, path, ackScopePath, kind, workId,
                    episodeId, imagesToken);
        }, delayMs);
    }

    private void maybeRetryStaleAckOnlyFetch(WebView view, boolean[] finished, int[] scriptRequests,
                                             String baseUrl, String path, String ackScopePath,
                                             String kind, String workId, String episodeId,
                                             String imagesToken, long delayMs) {
        if(!"__ack_only__".equals(imagesToken) || finished[0] || view == null)
            return;
        String script = "(function(){try{var scope=" + jsonQuote(path) + ";"
                + "var running=window.__ntkAckOnlyRunning===scope;"
                + "var age=Date.now()-Number(window.__ntkAckOnlyRunningAt||0);"
                + "var proof=!!window.__ntk_ad_ack_proof_200;"
                + "if(running&&age>=9000&&!proof){delete window.__ntkAckOnlyRunning;"
                + "return JSON.stringify({stale:true,age:age,proof:proof});}"
                + "return JSON.stringify({stale:false,running:running,age:age,proof:proof});"
                + "}catch(e){return JSON.stringify({stale:false,error:String(e)});}})()";
        view.evaluateJavascript(script, value -> {
            String state = unquoteJavascriptString(value == null ? "" : value);
            Log.d(TAG, "ntk_ack_only_limit_probe path=" + path
                    + ",delay=" + delayMs
                    + ",attempts=" + scriptRequests[0]
                    + ",state=" + state);
            if(finished[0] || view == null)
                return;
            if(!state.contains("\"stale\":true"))
                return;
            scriptRequests[0] = 0;
            evaluateViewerImageFetchScriptNow(view, finished, scriptRequests, baseUrl, path,
                    ackScopePath, kind, workId, episodeId, imagesToken);
        });
    }

    private static WebResourceResponse viewerShellResponse(String html, String guardVersion) {
        String bodyText = viewerShellHtml(html, guardVersion);
        byte[] body = bodyText
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/html; charset=utf-8");
        return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(body));
    }

    private static void logRealFrameRequest(WebResourceRequest request) {
        if(request == null || request.getUrl() == null)
            return;
        try {
            String url = request.getUrl().toString();
            String lower = url.toLowerCase(Locale.US);
            if(!(lower.contains("/api/ad/")
                    || lower.contains("/api/m/ev")
                    || lower.contains("/api/ev/")
                    || lower.contains("/api/manhwa-images")
                    || lower.contains("/api/webtoon-images")
                    || lower.contains("/_next/")
                    || lower.endsWith(".js")))
                return;
            Log.d(TAG, "ntk_realframe_request method=" + request.getMethod()
                    + ",main=" + request.isForMainFrame()
                    + ",url=" + url);
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldLogHiddenConsoleMessage(ConsoleMessage consoleMessage) {
        if(consoleMessage == null)
            return false;
        String message = consoleMessage.message() == null ? "" : consoleMessage.message();
        String source = consoleMessage.sourceId() == null ? "" : consoleMessage.sourceId();
        ConsoleMessage.MessageLevel level = consoleMessage.messageLevel();
        if(level == ConsoleMessage.MessageLevel.ERROR)
            return true;
        if(level == ConsoleMessage.MessageLevel.WARNING)
            return message.contains("guard") || source.contains("/api/ad/");
        return message.contains("ntk") || message.contains("/api/ad/");
    }

    static boolean hasRecentServerAckSuccess(String path) {
        String scope = normalizeAckSuccessScope(path);
        if(scope.length() == 0)
            return false;
        long now = SystemClock.uptimeMillis();
        Long stored = SERVER_ACK_SUCCESS_BY_SCOPE.get(scope);
        if(stored != null && now - stored <= SERVER_ACK_SUCCESS_TTL_MS)
            return true;
        if(stored != null)
            SERVER_ACK_SUCCESS_BY_SCOPE.remove(scope, stored);
        String decoded = decodeAckSuccessScope(scope);
        if(!decoded.equals(scope)) {
            stored = SERVER_ACK_SUCCESS_BY_SCOPE.get(decoded);
            if(stored != null && now - stored <= SERVER_ACK_SUCCESS_TTL_MS)
                return true;
            if(stored != null)
                SERVER_ACK_SUCCESS_BY_SCOPE.remove(decoded, stored);
        }
        return false;
    }

    static boolean hasRecentStrictAdAckSuccess(String path) {
        String scope = normalizeAckSuccessScope(path);
        if(scope.length() == 0)
            return false;
        long now = SystemClock.uptimeMillis();
        Long stored = STRICT_AD_ACK_SUCCESS_BY_SCOPE.get(scope);
        if(stored != null && now - stored <= SERVER_ACK_SUCCESS_TTL_MS)
            return true;
        if(stored != null)
            STRICT_AD_ACK_SUCCESS_BY_SCOPE.remove(scope, stored);
        String decoded = decodeAckSuccessScope(scope);
        if(!decoded.equals(scope)) {
            stored = STRICT_AD_ACK_SUCCESS_BY_SCOPE.get(decoded);
            if(stored != null && now - stored <= SERVER_ACK_SUCCESS_TTL_MS)
                return true;
            if(stored != null)
                STRICT_AD_ACK_SUCCESS_BY_SCOPE.remove(decoded, stored);
        }
        return false;
    }

    static boolean hasRecentStrictAdAckSuccessUnderTitlePath(String path) {
        String titleScope = normalizeAckSuccessScope(path);
        if(titleScope.length() == 0)
            return false;
        if(!titleScope.endsWith("/"))
            titleScope += "/";
        long now = SystemClock.uptimeMillis();
        for(Map.Entry<String, Long> entry : STRICT_AD_ACK_SUCCESS_BY_SCOPE.entrySet()) {
            String scope = entry.getKey();
            Long stored = entry.getValue();
            if(stored == null || now - stored > SERVER_ACK_SUCCESS_TTL_MS) {
                if(stored != null)
                    STRICT_AD_ACK_SUCCESS_BY_SCOPE.remove(scope, stored);
                continue;
            }
            if(scope != null && scope.startsWith(titleScope))
                return true;
            String decoded = decodeAckSuccessScope(scope);
            if(decoded.startsWith(titleScope))
                return true;
        }
        return false;
    }

    static String recentRequestKeyIdForScope(String path) {
        String scope = normalizeAckSuccessScope(path);
        if(scope.length() == 0)
            return "";
        String stored = REQUEST_KEY_BY_SCOPE.get(scope);
        if(stored != null && stored.length() > 0)
            return stored;
        String decoded = decodeAckSuccessScope(scope);
        if(!decoded.equals(scope)) {
            stored = REQUEST_KEY_BY_SCOPE.get(decoded);
            if(stored != null && stored.length() > 0)
                return stored;
        }
        return "";
    }

    static Map<String, String> signRecentRequestKeyForScope(String scope, String method,
                                                            String signedPath,
                                                            String signedScope,
                                                            String bodyText,
                                                            String signatureFormat) {
        String requestKeyId = recentRequestKeyIdForScope(scope);
        if(requestKeyId == null || requestKeyId.length() == 0)
            return Collections.emptyMap();
        RecentRequestKeyMaterial material = REQUEST_KEY_MATERIAL_BY_ID.get(requestKeyId);
        if(material == null)
            material = REQUEST_KEY_MATERIAL_BY_SCOPE.get(normalizeAckSuccessScope(scope));
        if(material == null || material.keyPair == null || material.keyPair.getPrivate() == null)
            return Collections.emptyMap();
        long now = System.currentTimeMillis();
        long signedNow = now + material.serverTimeOffsetMs;
        if(material.expiresAt > 0 && material.expiresAt - signedNow <= 30_000L)
            return Collections.emptyMap();
        try {
            String normalizedMethod = method == null || method.length() == 0
                    ? "POST" : method.toUpperCase(Locale.US);
            String path = signedPath == null ? "" : signedPath;
            String signScope = signedScope == null ? "" : signedScope;
            String body = bodyText == null ? "" : bodyText;
            byte[] nonceBytes = new byte[24];
            new java.security.SecureRandom().nextBytes(nonceBytes);
            String nonce = outerBase64Url(nonceBytes);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String bodyHash = outerBase64Url(digest.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String base = "ntk-brsig-v1\n" + normalizedMethod + "\n" + path + "\n"
                    + signScope + "\n" + requestKeyId + "\n" + signedNow + "\n"
                    + nonce + "\n" + bodyHash;
            boolean der = "der".equalsIgnoreCase(signatureFormat);
            byte[] signature = der
                    ? outerSignDer((java.security.interfaces.ECPrivateKey) material.keyPair.getPrivate(),
                    base.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : outerSignP1363((java.security.interfaces.ECPrivateKey) material.keyPair.getPrivate(),
                    base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-ntk-key-id", requestKeyId);
            headers.put("x-ntk-ts", String.valueOf(signedNow));
            headers.put("x-ntk-nonce", nonce);
            headers.put("x-ntk-sig", outerBase64Url(signature));
            Log.d(TAG, "ntk_request_key_recent_signed path=" + normalizeAckSuccessScope(scope)
                    + ",keyId=" + summarizeKeyId(requestKeyId)
                    + ",signedPath=" + path
                    + ",signedScope=" + signScope
                    + ",format=" + (der ? "der" : "p1363")
                    + ",sigLen=" + signature.length
                    + ",bodyLen=" + body.length());
            return headers;
        } catch(Exception e) {
            Log.d(TAG, "ntk_request_key_recent_sign_error path=" + normalizeAckSuccessScope(scope)
                    + ",keyId=" + summarizeKeyId(requestKeyId)
                    + ",error=" + e);
            return Collections.emptyMap();
        }
    }

    private static void rememberRecentRequestKeyMaterial(String pageUrl, String keyId,
                                                         java.security.KeyPair keyPair,
                                                         long serverTimeOffsetMs,
                                                         long expiresAt) {
        if(keyId == null || keyId.length() == 0 || keyPair == null)
            return;
        RecentRequestKeyMaterial material = new RecentRequestKeyMaterial(
                keyId, keyPair, serverTimeOffsetMs, expiresAt);
        REQUEST_KEY_MATERIAL_BY_ID.put(keyId, material);
        String scope = scopePathFromPageUrl(pageUrl);
        if(scope.length() > 0) {
            REQUEST_KEY_MATERIAL_BY_SCOPE.put(scope, material);
            REQUEST_KEY_BY_SCOPE.put(scope, keyId);
            String decoded = decodeAckSuccessScope(scope);
            if(decoded.length() > 0) {
                REQUEST_KEY_MATERIAL_BY_SCOPE.put(decoded, material);
                REQUEST_KEY_BY_SCOPE.put(decoded, keyId);
            }
        }
        Log.d(TAG, "ntk_request_key_material_recorded path=" + scope
                + ",keyId=" + summarizeKeyId(keyId)
                + ",expiresAt=" + expiresAt);
    }

    private static void rememberRecentRequestKeyMaterialFromJwk(String scope, String keyId,
                                                                String privateJwkText,
                                                                long expiresAt) {
        if(keyId == null || keyId.length() == 0
                || privateJwkText == null || privateJwkText.length() == 0)
            return;
        try {
            JSONObject jwk = new JSONObject(privateJwkText);
            String d = jwk.optString("d", "");
            if(d.length() == 0)
                return;
            byte[] privateBytes = Base64.decode(d, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            java.math.BigInteger s = new java.math.BigInteger(1, privateBytes);
            java.security.AlgorithmParameters parameters =
                    java.security.AlgorithmParameters.getInstance("EC");
            parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.spec.ECParameterSpec ecSpec =
                    parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
            java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("EC");
            java.security.PrivateKey privateKey =
                    keyFactory.generatePrivate(new java.security.spec.ECPrivateKeySpec(s, ecSpec));
            java.security.KeyPair keyPair = new java.security.KeyPair(null, privateKey);
            RecentRequestKeyMaterial material = new RecentRequestKeyMaterial(
                    keyId, keyPair, 0L, expiresAt);
            REQUEST_KEY_MATERIAL_BY_ID.put(keyId, material);
            String normalized = normalizeAckSuccessScope(scope);
            if(normalized.length() > 0) {
                REQUEST_KEY_MATERIAL_BY_SCOPE.put(normalized, material);
                REQUEST_KEY_BY_SCOPE.put(normalized, keyId);
                String decoded = decodeAckSuccessScope(normalized);
                if(decoded.length() > 0) {
                    REQUEST_KEY_MATERIAL_BY_SCOPE.put(decoded, material);
                    REQUEST_KEY_BY_SCOPE.put(decoded, keyId);
                }
            }
            Log.d(TAG, "ntk_request_key_material_jwk_recorded path=" + normalized
                    + ",keyId=" + summarizeKeyId(keyId)
                    + ",expiresAt=" + expiresAt);
        } catch(Exception e) {
            Log.d(TAG, "ntk_request_key_material_jwk_error path="
                    + normalizeAckSuccessScope(scope)
                    + ",keyId=" + summarizeKeyId(keyId)
                    + ",error=" + e);
        }
    }

    private static String scopePathFromPageUrl(String pageUrl) {
        if(pageUrl == null || pageUrl.length() == 0)
            return "";
        try {
            URI uri = URI.create(pageUrl);
            String rawPath = uri.getRawPath();
            return normalizeAckSuccessScope(rawPath == null || rawPath.length() == 0 ? "/" : rawPath);
        } catch(Exception ignored) {
            return "";
        }
    }

    private static String outerBase64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private static byte[] outerSignP1363(java.security.interfaces.ECPrivateKey privateKey,
                                         byte[] data) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] der = signer.sign();
        return outerDerToP1363(der);
    }

    private static byte[] outerSignDer(java.security.interfaces.ECPrivateKey privateKey,
                                       byte[] data) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }

    private static byte[] outerDerToP1363(byte[] der) throws Exception {
        int offset = 0;
        if(der[offset++] != 0x30)
            throw new IllegalArgumentException("not sequence");
        int seqLen = der[offset++] & 0xff;
        if((seqLen & 0x80) != 0)
            offset += seqLen & 0x7f;
        if(der[offset++] != 0x02)
            throw new IllegalArgumentException("missing r");
        int rLen = der[offset++] & 0xff;
        byte[] r = new byte[rLen];
        System.arraycopy(der, offset, r, 0, rLen);
        offset += rLen;
        if(der[offset++] != 0x02)
            throw new IllegalArgumentException("missing s");
        int sLen = der[offset++] & 0xff;
        byte[] s = new byte[sLen];
        System.arraycopy(der, offset, s, 0, sLen);
        java.math.BigInteger sValue = new java.math.BigInteger(1, s);
        if(sValue.compareTo(P256_HALF_ORDER) > 0)
            s = unsignedFixed32(P256_ORDER.subtract(sValue));
        byte[] out = new byte[64];
        copyUnsignedFixed(r, out, 0);
        copyUnsignedFixed(s, out, 32);
        return out;
    }

    private static byte[] unsignedFixed32(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        copyUnsignedFixed(raw, out, 0);
        return out;
    }

    private static void copyUnsignedFixed(byte[] raw, byte[] out, int offset) {
        int start = 0;
        while(start < raw.length - 1 && raw[start] == 0)
            start++;
        int len = raw.length - start;
        int copy = Math.min(32, len);
        System.arraycopy(raw, start + len - copy, out, offset + 32 - copy, copy);
    }

    private static final class RecentRequestKeyMaterial {
        final String keyId;
        final java.security.KeyPair keyPair;
        final long serverTimeOffsetMs;
        final long expiresAt;

        RecentRequestKeyMaterial(String keyId, java.security.KeyPair keyPair,
                                 long serverTimeOffsetMs, long expiresAt) {
            this.keyId = keyId;
            this.keyPair = keyPair;
            this.serverTimeOffsetMs = serverTimeOffsetMs;
            this.expiresAt = expiresAt;
        }
    }

    private static void rememberRecentRequestKeyId(String scope, String keyId, String source) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0 || keyId == null || keyId.length() == 0)
            return;
        REQUEST_KEY_BY_SCOPE.put(normalized, keyId);
        String decoded = decodeAckSuccessScope(normalized);
        if(decoded.length() > 0)
            REQUEST_KEY_BY_SCOPE.put(decoded, keyId);
        Log.d(TAG, "ntk_request_key_recorded path=" + normalized
                + ",source=" + source
                + ",keyId=" + summarizeKeyId(keyId));
    }

    private static void rememberServerAckSuccess(String scope, String source) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0)
            return;
        long now = SystemClock.uptimeMillis();
        SERVER_ACK_SUCCESS_BY_SCOPE.put(normalized, now);
        String decoded = decodeAckSuccessScope(normalized);
        if(decoded.length() > 0)
            SERVER_ACK_SUCCESS_BY_SCOPE.put(decoded, now);
        if(isStrictAdAckSuccessSource(source)) {
            STRICT_AD_ACK_SUCCESS_BY_SCOPE.put(normalized, now);
            if(decoded.length() > 0)
                STRICT_AD_ACK_SUCCESS_BY_SCOPE.put(decoded, now);
        }
        Log.d(TAG, "ntk_server_ack_success_recorded path=" + normalized
                + ",source=" + source
                + ",strictAdAck=" + isStrictAdAckSuccessSource(source));
    }

    private static boolean isStrictAdAckSuccessSource(String source) {
        if(source == null)
            return false;
        String normalized = source.toLowerCase(Locale.US);
        if(normalized.contains("challenge") || normalized.contains("cookie"))
            return false;
        return "native-ack-200".equals(normalized)
                || "native-fetch-ack-200".equals(normalized)
                || "captcha-webview-ack-200".equals(normalized)
                || "captcha-bridge-ack-200".equals(normalized)
                || "bridge-ack-200".equals(normalized)
                || "compact-ack-200".equals(normalized)
                || normalized.endsWith("-bridge-ack-200")
                || normalized.endsWith("-fetch-ack-200")
                || normalized.endsWith("-signed-fetch-ack-200")
                || normalized.endsWith("-state-ack-200");
    }

    static boolean isStrictAdAckSuccessSourceForTest(String source) {
        return isStrictAdAckSuccessSource(source);
    }

    static void rememberExternalServerAckSuccess(String scope, String source) {
        rememberServerAckSuccess(scope, source == null || source.length() == 0 ? "external" : source);
    }

    static void rememberScopedAdAck(String scope, String value, String source) {
        rememberScopedAckCookie(SCOPED_AD_ACK_BY_SCOPE, "ad_ack", scope, value, source);
    }

    static void rememberScopedAdAckC(String scope, String value, String source) {
        rememberScopedAckCookie(SCOPED_AD_ACK_C_BY_SCOPE, "ad_ack_c", scope, value, source);
    }

    private static void rememberScopedAckCookie(ConcurrentHashMap<String, String> store,
                                                String name, String scope, String value,
                                                String source) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0 || value == null || value.length() == 0)
            return;
        store.put(normalized, value);
        String decoded = decodeAckSuccessScope(normalized);
        if(decoded.length() > 0)
            store.put(decoded, value);
        Log.d(TAG, "ntk_scoped_" + name + "_recorded path=" + normalized
                + ",source=" + source);
    }

    static String scopedAdAckForPath(String scope) {
        return scopedAckCookieForPath(SCOPED_AD_ACK_BY_SCOPE, scope);
    }

    static String scopedAdAckCForPath(String scope) {
        return scopedAckCookieForPath(SCOPED_AD_ACK_C_BY_SCOPE, scope);
    }

    private static String scopedAckCookieForPath(ConcurrentHashMap<String, String> store,
                                                 String scope) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0)
            return "";
        String value = store.get(normalized);
        if(value != null && value.length() > 0)
            return value;
        String decoded = decodeAckSuccessScope(normalized);
        if(!decoded.equals(normalized)) {
            value = store.get(decoded);
            if(value != null && value.length() > 0)
                return value;
        }
        return "";
    }

    static void clearRecentServerAckSuccessForTest(String scope) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0)
            return;
        SERVER_ACK_SUCCESS_BY_SCOPE.remove(normalized);
        STRICT_AD_ACK_SUCCESS_BY_SCOPE.remove(normalized);
        String decoded = decodeAckSuccessScope(normalized);
        if(decoded.length() > 0)
            SERVER_ACK_SUCCESS_BY_SCOPE.remove(decoded);
        if(decoded.length() > 0)
            STRICT_AD_ACK_SUCCESS_BY_SCOPE.remove(decoded);
        NATIVE_ACK_CHALLENGE_BY_SCOPE.remove(normalized);
        if(decoded.length() > 0)
            NATIVE_ACK_CHALLENGE_BY_SCOPE.remove(decoded);
        REQUEST_KEY_BY_SCOPE.remove(normalized);
        if(decoded.length() > 0)
            REQUEST_KEY_BY_SCOPE.remove(decoded);
        SCOPED_AD_ACK_BY_SCOPE.remove(normalized);
        if(decoded.length() > 0)
            SCOPED_AD_ACK_BY_SCOPE.remove(decoded);
        SCOPED_AD_ACK_C_BY_SCOPE.remove(normalized);
        if(decoded.length() > 0)
            SCOPED_AD_ACK_C_BY_SCOPE.remove(decoded);
        Log.d(TAG, "ntk_server_ack_success_cleared_for_test path=" + normalized);
    }

    static void rememberNativeAckChallenge(String scope, String challengeBody) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0 || challengeBody == null || challengeBody.length() == 0)
            return;
        NativeAckChallenge challenge = new NativeAckChallenge(challengeBody, SystemClock.uptimeMillis());
        NATIVE_ACK_CHALLENGE_BY_SCOPE.put(normalized, challenge);
        String decoded = decodeAckSuccessScope(normalized);
        if(decoded.length() > 0)
            NATIVE_ACK_CHALLENGE_BY_SCOPE.put(decoded, challenge);
        Log.d(TAG, "ntk_native_ack_challenge_recorded path=" + normalized
                + ",bytes=" + challengeBody.length());
    }

    static String getRecentNativeAckChallenge(String scope) {
        String normalized = normalizeAckSuccessScope(scope);
        if(normalized.length() == 0)
            return "";
        long now = SystemClock.uptimeMillis();
        NativeAckChallenge challenge = NATIVE_ACK_CHALLENGE_BY_SCOPE.get(normalized);
        if(challenge != null && now - challenge.uptimeMs <= NATIVE_ACK_CHALLENGE_TTL_MS)
            return challenge.body;
        if(challenge != null)
            NATIVE_ACK_CHALLENGE_BY_SCOPE.remove(normalized, challenge);
        String decoded = decodeAckSuccessScope(normalized);
        if(!decoded.equals(normalized)) {
            challenge = NATIVE_ACK_CHALLENGE_BY_SCOPE.get(decoded);
            if(challenge != null && now - challenge.uptimeMs <= NATIVE_ACK_CHALLENGE_TTL_MS)
                return challenge.body;
            if(challenge != null)
                NATIVE_ACK_CHALLENGE_BY_SCOPE.remove(decoded, challenge);
        }
        return "";
    }

    private static String getRecentNativeAckChallengeWaiting(String scope, long timeoutMs) {
        String body = getRecentNativeAckChallenge(scope);
        if(body.length() > 0 || timeoutMs <= 0L)
            return body;
        long startedAt = SystemClock.uptimeMillis();
        long deadline = startedAt + timeoutMs;
        while(SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(12L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            body = getRecentNativeAckChallenge(scope);
            if(body.length() > 0) {
                Log.d(TAG, "ntk_native_ack_challenge_bridge_wait_hit path="
                        + (scope == null ? "" : scope)
                        + ",bytes=" + body.length()
                        + ",ms=" + (SystemClock.uptimeMillis() - startedAt));
                return body;
            }
        }
        return "";
    }

    private static String normalizeAckSuccessScope(String value) {
        if(value == null)
            return "";
        String scope = value.trim();
        if(scope.length() == 0)
            return "";
        try {
            if(scope.startsWith("http://") || scope.startsWith("https://")) {
                URI uri = URI.create(scope);
                scope = uri.getRawPath() == null ? "" : uri.getRawPath();
                if(uri.getRawQuery() != null && uri.getRawQuery().length() > 0)
                    scope += "?" + uri.getRawQuery();
            }
        } catch (Exception ignored) {
        }
        return scope.startsWith("/") ? scope : "";
    }

    private static String decodeAckSuccessScope(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static String summarizeKeyId(String keyId) {
        if(keyId == null || keyId.length() == 0)
            return "";
        return keyId.length() <= 12 ? keyId : keyId.substring(0, 12);
    }

    private static String viewerShellHtml(String html, String guardVersion) {
        String bodyText = html == null || html.length() == 0
                ? "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body></body></html>"
                : html;
        if(guardVersion != null && guardVersion.length() > 0 && !bodyText.contains(guardVersion)) {
            String marker = "<head>";
            String meta = "<meta name=\"ntk-guard-version\" content=\"" + guardVersion + "\">";
            int index = bodyText.toLowerCase(Locale.US).indexOf(marker);
            bodyText = index >= 0
                    ? bodyText.substring(0, index + marker.length()) + meta + bodyText.substring(index + marker.length())
                    : meta + bodyText;
        }
        if(!bodyText.toLowerCase(Locale.US).contains("<html")) {
            bodyText = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>"
                    + bodyText + "</body></html>";
        }
        if(!bodyText.contains("__ntk_fast_shell")) {
            int headEnd = bodyText.toLowerCase(Locale.US).indexOf("</head>");
            String marker = "<script>window.__ntk_fast_shell=1;</script>";
            bodyText = headEnd >= 0
                    ? bodyText.substring(0, headEnd) + marker + bodyText.substring(headEnd)
                    : marker + bodyText;
        }
        return bodyText;
    }

    private static String ackOnlySyntheticShellHtml(String path) {
        String safePath = path == null ? "" : path.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("<", "");
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>NTK ACK Shell</title>"
                + "<script>window.__ntk_fast_shell=0;window.__ntk_ack_only_shell=1;"
                + "window.__ntk_ib_ok=1;window.__ntk_ib_loaded=1;window.__ntk_hs_ok=1;"
                + "window.__ntk_ack_scope='" + safePath + "';</script>"
                + "</head><body><script id=\"init-html-sentinel\">try{window.__ntk_ib_ok=1}catch(_){}</script>"
                + "<div id=\"__ntk_guard_rows\" data-br=\"1\" data-br-n=\"0\" "
                + "style=\"position:relative;width:100%;min-height:1px;visibility:visible;opacity:1\"></div>"
                + "</body></html>";
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
        if(finished[0] || scriptRequests[0] >= viewerScriptRequestLimit(imagesToken) || view == null)
            return;
        if("__ack_only__".equals(imagesToken)) {
            String expected = baseUrl + path;
            view.evaluateJavascript("(function(){try{var h=String(location.href||''),t=String(document.title||''),hb=!!document.body,b=hb?String(document.body.innerText||document.body.textContent||'').replace(/\\s+/g,' ').slice(0,180):'',l=(h+' '+t+' '+b).toLowerCase(),cf=l.indexOf('__cf_chl')>=0||l.indexOf('/cdn-cgi/challenge-platform')>=0||l.indexOf('just a moment')>=0||l.indexOf('verify you are human')>=0||l.indexOf('verifying you are human')>=0||l.indexOf('challenges.cloudflare.com')>=0,ck=String(document.cookie||''),bc='',ls=[],ss=[],i,k;try{if(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)bc=String(window.NtkQuicBridge.cookie(h,'ad_ack_c')||'');}catch(_){}try{for(i=0;i<localStorage.length;i++){k=String(localStorage.key(i)||'');if(/ack|guard|browser|key|ntk|ad/i.test(k))ls.push(k.slice(0,48));}}catch(_){}try{for(i=0;i<sessionStorage.length;i++){k=String(sessionStorage.key(i)||'');if(/ack|guard|browser|key|ntk|ad/i.test(k))ss.push(k.slice(0,48));}}catch(_){}return JSON.stringify({href:h,ready:String(document.readyState||''),hasBody:hb,crypto:!!(window.crypto&&crypto.subtle),cf:cf,title:t.slice(0,80),body:b,visible:document.visibilityState,focus:document.hasFocus&&document.hasFocus(),cookieLen:ck.length,hasCf:/cf_clearance=/.test(ck),hasAdAck:/ad_ack=/.test(ck),hasAdAckC:/ad_ack_c=/.test(ck),hasBridgeAdAckC:bc.length>0,hasAdGuard:/ad_guard_l=/.test(ck),viewerBridge:!!window.NtkViewerBridge,ackBridge:!!window.NtkAckBridge,namedNativeAck:!!window.MangaViewerNativeAckBridge,namedNativeAckState:!!(window.MangaViewerNativeAckBridge&&window.MangaViewerNativeAckBridge.onAckState),namedNativeViewer:!!window.MangaViewerNativeViewerBridge,namedNativeViewerState:!!(window.MangaViewerNativeViewerBridge&&window.MangaViewerNativeViewerBridge.onAckState),viewerAckState:!!((window.NtkViewerBridge&&window.NtkViewerBridge.onAckState)||(window.NtkAckBridge&&window.NtkAckBridge.onAckState)),quicBridge:!!window.NtkQuicBridge,local:ls.slice(0,12),session:ss.slice(0,12)});}catch(e){return 'ERR:'+String(e);}})()",
                    value -> {
                        String rawState = value == null ? "" : value;
                        String state = unquoteJavascriptString(rawState);
                        Log.d(TAG, "ntk_images_api_eval_context path=" + path
                                + ",url=" + view.getUrl()
                                + ",state=" + state
                                + ",rawState=" + rawState
                                + ",expected=" + expected
                                + ",finished=" + finished[0]
                                + ",attempts=" + scriptRequests[0]);
            if(finished[0] || scriptRequests[0] >= viewerScriptRequestLimit(imagesToken))
                return;
                        final boolean matchedDocument = isFinishedDocumentUrl(view.getUrl(), baseUrl, path)
                                || (isAckOnlyPlainCloudflareFlow(view.getTag())
                                && "about:blank".equals(view.getUrl())
                                && state.contains("\"title\":\"NTK ACK Shell\"")
                                && state.contains("\"href\":\"" + expected + "\""));
                        if(!matchedDocument)
                            return;
                        if(state.contains("about:blank") || state.contains("chrome-error://") || state.contains("ERR:"))
                            return;
                        if(!state.contains("\"hasBody\":true")) {
                            Log.d(TAG, "ntk_ack_only_eval_wait_body path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",state=" + state);
                            return;
                        }
                        boolean readyComplete = state.contains("\"ready\":\"complete\"")
                                || state.contains("|complete|");
                        final boolean ackBridgeReady = state.contains("\"quicBridge\":true")
                                && (state.contains("\"viewerBridge\":true")
                                || state.contains("\"ackBridge\":true")
                                || state.contains("\"namedNativeAck\":true")
                                || state.contains("\"namedNativeViewer\":true"));
                        boolean guardReady = state.contains("\"hasAdGuard\":true")
                                || state.contains("\"hasAdAck\":true")
                                || state.contains("\"hasAdAckC\":true")
                                || state.contains("\"hasBridgeAdAckC\":true");
                        boolean hasClearance = state.contains("\"hasCf\":true");
                        boolean cryptoReady = state.contains("\"crypto\":true")
                                || state.contains("|true|cf=") || state.endsWith("|true\"");
                        if(!cryptoReady)
                            return;
                        boolean hasCloudflarePage = state.contains("__cf_chl")
                                || state.contains("/cdn-cgi/challenge-platform")
                                || state.contains("\"cf\":true") || state.contains("|cf=true");
                        if(hasCloudflarePage && !hasClearance) {
                            Log.d(TAG, "ntk_ack_only_eval_wait_cf_clear path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",hasClearance=" + hasClearance
                                    + ",guardReady=" + guardReady
                                    + ",state=" + state);
                            return;
                        }
                        if(hasCloudflarePage && hasClearance) {
                            Log.d(TAG, (state.contains("__cf_chl")
                                    ? "ntk_ack_only_eval_wait_cf_challenge path="
                                    : "ntk_ack_only_eval_wait_cf_verification path=") + path
                                    + ",url=" + view.getUrl()
                                    + ",guardReady=" + guardReady
                                    + ",state=" + state);
                            return;
                        }
                        final boolean bridgeGuardReadyEarly = ackBridgeReady && guardReady
                                && hasClearance && !hasCloudflarePage;
                        if(!ackBridgeReady) {
                            Log.d(TAG, "ntk_ack_only_eval_wait_bridge path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",state=" + state);
                            return;
                        }
                        final boolean syntheticSentinel = bridgeGuardReadyEarly && !readyComplete;
                        boolean sentinelState = state.contains("init-html-sentinel")
                                || state.contains("__ntk_ib_ok")
                                || syntheticSentinel;
                        final boolean startFromSentinel = sentinelState && !readyComplete
                                && ackBridgeReady && hasClearance && guardReady
                                && !hasCloudflarePage;
                        if(sentinelState) {
                            view.evaluateJavascript("(function(){try{"
                                            + "var first=!window.__ntk_ack_ib_sentinel_seen;"
                                            + "window.__ntk_ack_ib_sentinel_seen=1;"
                                            + "window.__ntk_ib_ok=1;window.__ntk_ib_loaded=1;"
                                            + "window.__ntk_hs_ok=1;"
                                            + "return JSON.stringify({injected:first,ibOk:String(window.__ntk_ib_ok),"
                                            + "ibLoaded:String(window.__ntk_ib_loaded),hsOk:!!window.__ntk_hs_ok});"
                                            + "}catch(e){return 'ERR:'+String(e);}})()",
                                    sentinelValue -> {
                                        Log.d(TAG, "ntk_ack_only_ib_sentinel_inject path="
                                                + path + ",url=" + view.getUrl()
                                                + ",value=" + (sentinelValue == null ? "" : sentinelValue)
                                                + ",start=" + startFromSentinel);
                                        if(startFromSentinel && !finished[0]
                                                && scriptRequests[0] < viewerScriptRequestLimit(imagesToken)
                                                && matchedDocument) {
                                            Log.d(TAG, "ntk_ack_only_ib_sentinel_start path=" + path
                                                    + ",url=" + view.getUrl());
                                            evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                                                    baseUrl, path, ackScopePath, kind, workId, episodeId,
                                                    imagesToken);
                                        }
                                    });
                        }
                        if(!readyComplete && !bridgeGuardReadyEarly) {
                            return;
                        }
                        if(!readyComplete) {
                            Log.d(TAG, "ntk_ack_only_eval_bridge_guard_early path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",state=" + state);
                        }
                        if(readyComplete && ackBridgeReady && guardReady && hasClearance
                                && !hasCloudflarePage) {
                            Log.d(TAG, "ntk_ack_only_eval_ready_fast_start path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",state=" + state);
                            evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                                    baseUrl, path, ackScopePath, kind, workId, episodeId,
                                    imagesToken);
                            return;
                        }
                        view.evaluateJavascript("(function(){try{var gl=window.__ntk_ad_guard_load_last;"
                                        + "return JSON.stringify({hsOk:!!window.__ntk_hs_ok,"
                                        + "storeKey:!!window.__ntkStoreReqKey,"
                                        + "ev:!!sessionStorage.getItem('__ntk_ev_id'),"
                                        + "me:!!sessionStorage.getItem('ntk:me:v1'),"
                                        + "loading:(document.body?String(document.body.innerText||document.body.textContent||'').indexOf('불러오는 중')>=0:false),"
                                        + "retry:(document.body?String(document.body.innerText||document.body.textContent||'').indexOf('일시적 오류')>=0:false),"
                                        + "guardLast:gl?String(JSON.stringify(gl)).slice(0,140):''});"
                                        + "}catch(e){return 'ERR:'+String(e);}})()",
                                readyValue -> {
                                    String readyRaw = readyValue == null ? "" : readyValue;
                                    String readyState = unquoteJavascriptString(readyRaw);
                                    Log.d(TAG, "ntk_ack_only_runtime_context path=" + path
                                            + ",url=" + view.getUrl()
                                            + ",state=" + readyState
                                            + ",bridgeReady=" + ackBridgeReady
                                            + ",attempts=" + scriptRequests[0]);
                                    if(finished[0] || scriptRequests[0] >= viewerScriptRequestLimit(imagesToken))
                                        return;
                                    if(!matchedDocument)
                                        return;
                                    boolean hsOk = readyState.contains("\"hsOk\":true");
                                    boolean storeKeyReady = readyState.contains("\"storeKey\":true");
                                    boolean evReady = readyState.contains("\"ev\":true");
                                    boolean meReady = readyState.contains("\"me\":true");
                                    boolean loading = readyState.contains("\"loading\":true");
                                    boolean retry = readyState.contains("\"retry\":true");
                                    boolean runtimeReady = hsOk || storeKeyReady || evReady || meReady;
                                    if(!hsOk && loading && !retry
                                            && !(ackBridgeReady && (runtimeReady || bridgeGuardReadyEarly))) {
                                        Log.d(TAG, "ntk_ack_only_runtime_wait path=" + path
                                                + ",url=" + view.getUrl()
                                                + ",state=" + readyState);
                                        return;
                                    }
                                    if(!hsOk && loading && !retry) {
                                        Log.d(TAG, (bridgeGuardReadyEarly
                                                ? "ntk_ack_only_runtime_bridge_guard_bypass path="
                                                : "ntk_ack_only_runtime_loading_bypass path=") + path
                                                + ",url=" + view.getUrl()
                                                + ",state=" + readyState
                                                + ",bridgeReady=" + ackBridgeReady);
                                    }
                                    evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                                            baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken);
                                });
                    });
            return;
        }
        view.evaluateJavascript("(function(){try{return JSON.stringify({href:String(location.href||''),"
                        + "ready:String(document.readyState||''),hasBody:!!document.body,"
                        + "title:String(document.title||'').slice(0,80)});}catch(e){return 'ERR:'+String(e);}})()",
                value -> {
                    String rawState = value == null ? "" : value;
                    String state = unquoteJavascriptString(rawState);
                    boolean contextReady = state.contains("\"href\":\"" + baseUrl + path)
                            && !state.contains("about:blank")
                            && !state.contains("chrome-error://")
                            && !state.contains("ERR:")
                            && state.contains("\"hasBody\":true");
                    Log.d(TAG, "ntk_images_api_eval_context path=" + path
                            + ",url=" + view.getUrl()
                            + ",state=" + state
                            + ",ready=" + contextReady
                            + ",attempts=" + scriptRequests[0]);
                    if(!contextReady) {
                        if(state.contains("about:blank")
                                && !"__ntk_image_context_reloaded__".equals(String.valueOf(view.getTag()))) {
                            view.setTag("__ntk_image_context_reloaded__");
                            Log.d(TAG, "ntk_images_api_context_reload path=" + path
                                    + ",url=" + view.getUrl()
                                    + ",target=" + baseUrl + path);
                            view.loadUrl(baseUrl + path);
                            mainHandler.postDelayed(() -> evaluateViewerImageFetchScript(view, finished,
                                    scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId,
                                    imagesToken), 260L);
                            mainHandler.postDelayed(() -> evaluateViewerImageFetchScript(view, finished,
                                    scriptRequests, baseUrl, path, ackScopePath, kind, workId, episodeId,
                                    imagesToken), 900L);
                        }
                        return;
                    }
                    evaluateViewerImageFetchScriptNow(view, finished, scriptRequests,
                            baseUrl, path, ackScopePath, kind, workId, episodeId, imagesToken);
                });
    }

    private void evaluateViewerImageFetchScriptNow(WebView view, boolean[] finished, int[] scriptRequests,
                                                   String baseUrl, String path,
                                                   String ackScopePath,
                                                   String kind, String workId, String episodeId,
                                                   String imagesToken) {
        if(finished[0] || scriptRequests[0] >= viewerScriptRequestLimit(imagesToken) || view == null)
            return;
        scriptRequests[0]++;
        String proofScope = ackScopePath == null || ackScopePath.length() == 0
                ? path : ackScopePath;
        boolean serverAckProof = !"__ack_only__".equals(imagesToken)
                && (hasRecentServerAckSuccess(proofScope)
                || hasRecentStrictAdAckSuccess(proofScope));
        boolean ackOnly = "__ack_only__".equals(imagesToken);
        String script = buildViewerImageFetchScript(baseUrl, path, ackScopePath, kind, workId,
                episodeId, imagesToken, serverAckProof);
        Log.d(TAG, "ntk_images_api_eval_script path=" + path
                + ",url=" + view.getUrl()
                + ",attempt=" + scriptRequests[0]
                + ",ackOnly=" + ackOnly
                + ",serverAckProof=" + serverAckProof
                + ",len=" + script.length()
                + ",finished=" + finished[0]);
        view.evaluateJavascript(script, value -> Log.d(TAG, "ntk_images_api_eval_result path=" + path
                + ",url=" + view.getUrl()
                + ",value=" + (value == null ? "" : value)));
    }

    private static int viewerScriptRequestLimit(String imagesToken) {
        return "__ack_only__".equals(imagesToken) ? 3 : 8;
    }

    private void scheduleAckOnlyCloudflareBridge(WebView view, boolean[] finished, String path,
                                                 long delayMs) {
        mainHandler.postDelayed(() -> installAckOnlyCloudflareBridge(view, finished, path,
                "delay-" + delayMs), delayMs);
    }

    private void installAckOnlyCloudflareBridge(WebView view, boolean[] finished, String path,
                                                String reason) {
        if(finished[0] || view == null)
            return;
        Log.d(TAG, "ntk_ack_cf_bridge_native_flow path=" + path
                + ",reason=" + reason
                + ",url=" + view.getUrl());
        if(ACK_ONLY_CLOUDFLARE_NATIVE_FLOW_ONLY)
            return;
        try {
            view.evaluateJavascript(buildAckOnlyCloudflareBridgeScript(reason), value ->
                    Log.d(TAG, "ntk_ack_cf_bridge_eval path=" + path
                            + ",reason=" + reason
                            + ",url=" + view.getUrl()
                            + ",value=" + (value == null ? "" : value)));
        } catch (Exception e) {
            Log.d(TAG, "ntk_ack_cf_bridge_eval_error path=" + path
                    + ",reason=" + reason
                    + ",error=" + e);
        }
    }

    private static String buildAckOnlyCloudflareBridgeScript(String reason) {
        return "(function(){try{"
                + "if(!window.NtkQuicBridge)return 'no-bridge';"
                + "var cur=window.fetch;"
                + "if(window.__ntkAckCfBridgeInstalled&&window.__ntkAckCfBridgeWrapped===cur)return 'already';"
                + "window.__ntkAckCfBridgeInstalled=1;"
                + "function log(o){try{o.reason=" + jsonQuote(reason) + ";window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(_){}}"
                + "function enc(s){try{return btoa(unescape(encodeURIComponent(String(s||''))));}catch(e){return '';}}"
                + "function body64(b){try{if(b==null)return '';if(typeof b==='string')return enc(b);"
                + "if(window.URLSearchParams&&b instanceof URLSearchParams)return enc(b.toString());"
                + "if(window.ArrayBuffer&&b instanceof ArrayBuffer){var a=new Uint8Array(b),r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return btoa(r);}"
                + "if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b)){var v=new Uint8Array(b.buffer,b.byteOffset,b.byteLength),o='';for(var j=0;j<v.length;j++)o+=String.fromCharCode(v[j]);return btoa(o);}"
                + "return enc(String(b));}catch(e){return '';}}"
                + "function body64Async(b){try{if(b&&window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return body64(a);});"
                + "if(b&&window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return body64(a);});}catch(e){}return Promise.resolve(body64(b));}"
                + "function bytes(b){try{var s=atob(b||''),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&255;return a;}catch(e){return new Uint8Array(0);}}"
                + "function headers(input,init){var h={};try{var hs=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers)new Headers(init.headers).forEach(function(v,k){hs.set(k,v);});hs.forEach(function(v,k){h[k]=v;});}catch(e){}"
                + "try{if(!h.origin&&!h.Origin)h.origin=location.origin;if(!h.referer&&!h.Referer)h.referer=location.href;if(!h.accept&&!h.Accept)h.accept='*/*';"
                + "if(!h['sec-fetch-dest'])h['sec-fetch-dest']='empty';if(!h['sec-fetch-mode'])h['sec-fetch-mode']='cors';if(!h['sec-fetch-site'])h['sec-fetch-site']='same-origin';}catch(e){}return h;}"
                + "function should(u,m){try{var a=new URL(u,location.href);return a.protocol==='https:'&&a.host===location.host&&a.pathname.indexOf('/cdn-cgi/challenge-platform/')===0&&String(m||'GET').toUpperCase()!=='GET';}catch(e){return false;}}"
                + "function bridge(input,init){var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',method=(init&&init.method)||(input&&input.method)||'POST';"
                + "var body=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);"
                + "var absolute=new URL(url,location.href).href;return body64Async(body).then(function(b){var h=headers(input,init);log({ackCfBridgeRequest:true,path:(new URL(absolute)).pathname,method:String(method),bodyLen:b.length,headers:Object.keys(h).slice(0,24)});"
                + "var raw=window.NtkQuicBridge.request(absolute,String(method),JSON.stringify(h),b),res=JSON.parse(raw||'{}');"
                + "if(!res.ok)throw new Error(res.error||'bridge failed');log({ackCfBridgeResponse:true,status:res.status||0,len:String(res.bodyBase64||'').length,path:(new URL(absolute)).pathname});"
                + "return new Response(bytes(res.bodyBase64||''),{status:res.status||200,statusText:res.statusText||'OK',headers:res.headers||{}});});}"
                + "var nf=cur;if(nf){window.fetch=function(input,init){try{var u=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',m=(init&&init.method)||(input&&input.method)||'GET';if(should(u,m))return bridge(input,init);}catch(e){log({ackCfBridgeFetchError:String(e)});}return nf.apply(this,arguments);};try{window.fetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}window.__ntkAckCfBridgeWrapped=window.fetch;}"
                + "var xp=window.XMLHttpRequest&&XMLHttpRequest.prototype;if(xp&&!window.__ntkAckCfBridgeXhr){var xo=xp.open,xs=xp.send,xh=xp.setRequestHeader;window.__ntkAckCfBridgeXhr=1;"
                + "function fire(x,n){try{var e=new Event(n);x.dispatchEvent(e);var cb=x['on'+n];if(typeof cb==='function')cb.call(x,e);}catch(_){}}"
                + "if(xo&&xs){xp.open=function(m,u,a,user,pw){try{this.__ntkAckCf={method:m||'GET',url:u||'',headers:{}};}catch(_){}return xo.apply(this,arguments);};"
                + "if(xh)xp.setRequestHeader=function(k,v){try{if(this.__ntkAckCf&&k)this.__ntkAckCf.headers[String(k)]=String(v);}catch(_){}return xh.apply(this,arguments);};"
                + "xp.send=function(body){var meta=this.__ntkAckCf||{};if(!should(meta.url,meta.method))return xs.apply(this,arguments);var xhr=this;bridge(meta.url,{method:meta.method||'POST',headers:meta.headers||{},body:body}).then(function(r){return r.text().then(function(t){return{r:r,t:t};});}).then(function(o){try{Object.defineProperty(xhr,'readyState',{configurable:true,get:function(){return 4;}});Object.defineProperty(xhr,'status',{configurable:true,get:function(){return o.r.status||200;}});Object.defineProperty(xhr,'statusText',{configurable:true,get:function(){return o.r.statusText||'OK';}});Object.defineProperty(xhr,'responseText',{configurable:true,get:function(){return o.t||'';}});Object.defineProperty(xhr,'response',{configurable:true,get:function(){return o.t||'';}});xhr.getAllResponseHeaders=function(){return '';};xhr.getResponseHeader=function(){return null;};}catch(_){}fire(xhr,'readystatechange');fire(xhr,'load');fire(xhr,'loadend');}).catch(function(e){log({ackCfBridgeXhrError:String(e)});fire(xhr,'error');fire(xhr,'loadend');});};}}"
                + "log({ackCfBridgeInstalled:true,href:String(location.href||'').slice(0,120)});try{console.log('__NTK_ACK_CF_BRIDGE_READY__');}catch(_){}return 'installed';"
                + "}catch(e){try{window.NtkViewerBridge&&window.NtkViewerBridge.onAckState(JSON.stringify({ackCfBridgeInstallError:String(e),reason:" + jsonQuote(reason) + "}));}catch(_){}return 'ERR:'+String(e);}})()";
    }

    private void scheduleAckOnlyStaleCloudflareReload(WebView view, boolean[] finished,
                                                      boolean[] reloaded,
                                                      NtkQuicBridge quicBridge,
                                                      String baseUrl, String path,
                                                      String shellUrl, Map<String, String> headers,
                                                      long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || reloaded[0] || view == null || quicBridge == null)
                return;
            String script = "(function(){try{var h=String(location.href||''),t=String(document.title||''),"
                    + "b=document.body?String(document.body.innerText||document.body.textContent||'').replace(/\\s+/g,' ').slice(0,220):'',"
                    + "ck=String(document.cookie||''),l=(h+' '+t+' '+b).toLowerCase(),"
                    + "cf=l.indexOf('/cdn-cgi/challenge-platform')>=0||l.indexOf('just a moment')>=0||l.indexOf('performing security verification')>=0||l.indexOf('challenges.cloudflare.com')>=0;"
                    + "return JSON.stringify({href:h,ready:String(document.readyState||''),cf:cf,hasCf:/cf_clearance=/.test(ck),title:t.slice(0,80),body:b});"
                    + "}catch(e){return 'ERR:'+String(e);}})()";
            view.evaluateJavascript(script, value -> {
                if(finished[0] || reloaded[0] || view == null)
                    return;
                String state = unquoteJavascriptString(value);
                Log.d(TAG, "ntk_ack_cf_stale_reload_probe path=" + path
                        + ",delay=" + delayMs
                        + ",url=" + view.getUrl()
                        + ",state=" + state);
                if(!state.contains("\"cf\":true"))
                    return;
                reloaded[0] = true;
                String beforeCookies = webViewCookieHeader(baseUrl + path);
                Log.d(TAG, "ntk_ack_cf_stale_reload path=" + path
                        + ",url=" + view.getUrl()
                        + ",cookie=" + summarizeNtkCookieHeaderForLog(beforeCookies));
                installAckOnlyCloudflareBridge(view, finished, path, "stale-cf-before-reload");
                mainHandler.postDelayed(() -> {
                    if(!finished[0] && view != null) {
                        view.loadUrl(shellUrl, webViewHeaders(headers));
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 80L);
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 300L);
                        scheduleAckOnlyCloudflareBridge(view, finished, path, 900L);
                    }
                }, 140L);
            });
        }, delayMs);
    }

    private void scheduleAckOnlyTurnstileProbe(WebView view, boolean[] finished, String path,
                                               long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || view == null)
                return;
            evaluateAckOnlyTurnstileProbe(view, finished, path, delayMs);
        }, delayMs);
    }

    private void scheduleAckOnlyPageActivationTouch(WebView view, boolean[] finished, String path,
                                                   long delayMs) {
        mainHandler.postDelayed(() -> {
            if(finished[0] || view == null)
                return;
            view.requestFocus();
            view.requestFocusFromTouch();
            float x = view.getWidth() > 0 ? view.getWidth() * 0.5f : 200f;
            float y = view.getHeight() > 0 ? view.getHeight() * 0.54f : 390f;
            dispatchAckPageActivationTouch(view, path, x, y, delayMs);
        }, delayMs);
    }

    private void evaluateAckOnlyTurnstileProbe(WebView view, boolean[] finished, String path,
                                               long delayMs) {
        String script = ml.melun.mangaview.activity.CaptchaActivity.TURNSTILE_AUTO_JS;
        view.evaluateJavascript(script, value -> {
            if(finished[0] || view == null)
                return;
            String clean = unquoteJavascriptString(value);
            Log.d(TAG, "ntk_ack_turnstile_probe path=" + path
                    + ",delay=" + delayMs
                    + ",url=" + view.getUrl()
                    + ",result=" + clean);
            try {
                JSONObject object = new JSONObject(clean);
                String type = object.optString("type", "");
                if("jsclick".equals(type)) {
                    Log.d(TAG, "ntk_ack_turnstile_jsclick path=" + path
                            + ",delay=" + delayMs
                            + ",url=" + view.getUrl());
                    return;
                }
                if(!"iframe".equals(type) && !"div".equals(type))
                    return;
                float x = (float) object.optDouble("x", -1d);
                float y = (float) object.optDouble("y", -1d);
                float w = (float) object.optDouble("w", 48d);
                float h = (float) object.optDouble("h", 48d);
                double viewportWidth = object.optDouble("vw", 0d);
                double viewportHeight = object.optDouble("vh", 0d);
                if(viewportWidth > 0d && view.getWidth() > 0)
                    x = (float) (x * view.getWidth() / viewportWidth);
                if(viewportHeight > 0d && view.getHeight() > 0)
                    y = (float) (y * view.getHeight() / viewportHeight);
                w = Math.max(24f, w);
                h = Math.max(24f, h);
                dispatchAckTurnstileTouch(view, path, x, y, w, h, delayMs);
            } catch (Exception e) {
                Log.d(TAG, "ntk_ack_turnstile_probe_parse_error path=" + path
                        + ",error=" + e);
            }
        });
    }

    private static String unquoteJavascriptString(String value) {
        if(value == null)
            return "";
        String text = value;
        if(text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
            text = text.substring(1, text.length() - 1);
        return text.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    private void dispatchAckTurnstileTouch(WebView view, String path, float x, float y, float w,
                                           float h, long delayMs) {
        view.post(() -> {
            if(view == null)
                return;
            float targetX = Math.max(5f, Math.min(view.getWidth() - 5f,
                    x + ((float) Math.random() - 0.5f) * w * 0.4f));
            float targetY = Math.max(5f, Math.min(view.getHeight() - 5f,
                    y + ((float) Math.random() - 0.5f) * h * 0.4f));
            long downTime = SystemClock.uptimeMillis();
            Log.d(TAG, "ntk_ack_turnstile_touch path=" + path
                    + ",delay=" + delayMs
                    + ",x=" + targetX
                    + ",y=" + targetY
                    + ",view=" + view.getWidth() + "x" + view.getHeight());
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                    targetX, targetY, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(down);
            down.recycle();
            long moveAt = downTime + 24L;
            MotionEvent move = MotionEvent.obtain(downTime, moveAt, MotionEvent.ACTION_MOVE,
                    targetX + ((float) Math.random() - 0.5f) * 3f,
                    targetY + ((float) Math.random() - 0.5f) * 3f, 0);
            move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(move);
            move.recycle();
            long upAt = downTime + 78L;
            MotionEvent up = MotionEvent.obtain(downTime, upAt, MotionEvent.ACTION_UP,
                    targetX, targetY, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(up);
            up.recycle();
        });
    }

    private void dispatchAckPageActivationTouch(WebView view, String path, float x, float y,
                                                long delayMs) {
        view.post(() -> {
            if(view == null)
                return;
            float targetX = Math.max(5f, Math.min(view.getWidth() - 5f,
                    x + ((float) Math.random() - 0.5f) * 18f));
            float targetY = Math.max(5f, Math.min(view.getHeight() - 5f,
                    y + ((float) Math.random() - 0.5f) * 24f));
            long downTime = SystemClock.uptimeMillis();
            Log.d(TAG, "ntk_ack_plain_cf_activation_touch path=" + path
                    + ",delay=" + delayMs
                    + ",x=" + targetX
                    + ",y=" + targetY
                    + ",view=" + view.getWidth() + "x" + view.getHeight());
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                    targetX, targetY, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(down);
            down.recycle();
            long upAt = downTime + 64L;
            MotionEvent up = MotionEvent.obtain(downTime, upAt, MotionEvent.ACTION_UP,
                    targetX, targetY, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(up);
            up.recycle();
        });
    }

    static long webViewLoadTimeoutMsForTest(boolean highPriority, boolean wolfDocument) {
        return webViewLoadTimeoutMs(highPriority, wolfDocument);
    }

    private static boolean shouldStopWaitingForCaller(boolean requestCancelled, long now, long deadline) {
        return requestCancelled || now >= deadline;
    }

    private static boolean isModernNtkGuardRoot(String root) {
        root = root == null ? "" : root.toLowerCase(Locale.ROOT);
        return root.contains("sbxh") || root.contains("toonflix") || root.contains("newtoki");
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
        try {
            URI parsed = new URI(url);
            URI base = new URI(baseUrl);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String baseScheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            String baseHost = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
            int port = parsed.getPort();
            int basePort = base.getPort();
            if(!scheme.equals(baseScheme) || !host.equals(baseHost) || port != basePort)
                return false;
            String decodedPath = parsed.getPath();
            String expectedPath = path.startsWith("/") ? path : "/" + path;
            return decodedPath != null && (decodedPath.equals(expectedPath)
                    || decodedPath.equals(expectedPath + "/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matchesFinishedDocumentUrl(String url, String expected) {
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
            return 45_000L;
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
        return webViewUserAgentForTask(userAgent, baseUrl, path, null);
    }

    private static String webViewUserAgentForTask(String userAgent, String baseUrl, String path,
                                                 String defaultUserAgent) {
        String candidate = userAgent == null || userAgent.trim().length() == 0
                ? defaultUserAgent : userAgent;
        if(candidate == null || candidate.trim().length() == 0)
            candidate = defaultUserAgent == null || defaultUserAgent.trim().length() == 0
                    ? "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    : defaultUserAgent;
        String ua = candidate.trim()
                .replace("; wv", "")
                .replace(" wv", "")
                .replace("Version/4.0 ", "");
        if(!ua.contains("Mobile Safari/"))
            ua = ua + " Mobile Safari/537.36";
        return ua;
    }

    private static String summarizeUserAgentForLog(String userAgent) {
        String value = userAgent == null ? "" : userAgent;
        value = value.replace(",", " ");
        return value.length() <= 120 ? value : value.substring(0, 120);
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

    private static String buildAckOnlyProofScript(String baseUrl, String path) {
        return NTK_QUIC_BRIDGE_JS + "(async function(){var base=" + jsonQuote(baseUrl)
                + ",scope=" + jsonQuote(path) + ",sent=false;"
                + buildViewerBridgeShimScript()
                + "function bcall(n,v){var bs=[window.NtkViewerBridge,window.NtkAckBridge,window.MangaViewerNativeViewerBridge,window.MangaViewerNativeAckBridge,window.__NtkViewerBridgeNative,window.__NtkAckBridgeNative];for(var i=0;i<bs.length;i++){try{var b=bs[i];if(b&&typeof b[n]==='function')return b[n](v);}catch(_){}}return null;}"
                + "function log(o){try{bcall('onAckState',JSON.stringify(o));}catch(_){}}"
                + "function send(o){if(sent)return;sent=true;try{if(window.__ntkAckOnlyRunning===scope)delete window.__ntkAckOnlyRunning;}catch(_){}try{bcall('onViewerImages',JSON.stringify(o));}catch(_){}}"
                + "function abs(u){return new URL(u,base).href;}function pageUrl(){return abs(scope);}function baseOrigin(){return new URL(base).origin;}function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function b64(bytes){var s='',c=0x8000;for(var i=0;i<bytes.length;i+=c){var sub=bytes.subarray(i,i+c);s+=String.fromCharCode.apply(null,sub);}return btoa(s);}function d64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}function bytes(b){try{var s=atob(b||''),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&255;return a;}catch(e){return new Uint8Array(0);}}"
                + "function sameScope(v){try{v=String(v||'');return v===scope||decodeURIComponent(v)===scope||(new URL(pageUrl())).pathname===v;}catch(_){return false;}}function proofed(){try{if(sameScope(window.__ntk_ad_ack_proof_200))return true;var l=window.__ntk_ad_ack_last;return !!(l&&l.proof200&&sameScope(l.scope));}catch(_){return false;}}"
                + "function markProof(src,tp){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_proof_200=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true,proof200:true,source:src||'compact-ack-200'};try{bcall('onAckProof',JSON.stringify({scope:scope,tp:tp||'',source:src||'compact-ack-200'}));}catch(_){}window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true,proof200:true}}));}catch(e){log({compactAckMarkError:String(e)});}}"
                + "function waitProof(ms){return new Promise(function(resolve){if(proofed())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.proof200&&sameScope(e.detail.scope))||proofed())finish(true);}var to=setTimeout(function(){finish(proofed());},Math.max(1,Number(ms||1)));var iv=setInterval(function(){if(proofed())finish(true);},80);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function bridgeReq(url,method,body,headers){var u=abs(url),h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()};if(headers)Object.keys(headers).forEach(function(k){h[k]=headers[k];});var bodyText=body?JSON.stringify(body):'',body64=body?b64(new TextEncoder().encode(bodyText)):'',raw=window.NtkQuicBridge.request(u,method||'POST',JSON.stringify(h),body64),o=JSON.parse(raw||'{}'),txt=d64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}return{status:o.status||0,body:j,text:txt,raw:o};}"
                + "function body64Any(x){try{if(x==null)return Promise.resolve('');if(typeof x==='string')return Promise.resolve(b64(new TextEncoder().encode(x)));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(window.ArrayBuffer&&x instanceof ArrayBuffer)return Promise.resolve(b64(new Uint8Array(x)));if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(x))return Promise.resolve(b64(new Uint8Array(x.buffer,x.byteOffset,x.byteLength)));return Promise.resolve(b64(new TextEncoder().encode(String(x))));}catch(e){return Promise.resolve('');}}"
                + "function installGuardAckBridge(){try{if(!window.NtkQuicBridge||window.__ntkCompactAckBridge)return;window.__ntkCompactAckBridge=1;var nf=window.__ntkNativeFetch||window.fetch,nb=window.__ntkNativeBeacon||navigator.sendBeacon,nativeToString=Function.prototype.toString;try{if(!Function.prototype.__ntkCompactNativeToString){Object.defineProperty(Function.prototype,'__ntkCompactNativeToString',{value:1,configurable:true});Function.prototype.toString=function(){try{if(this&&this.__ntkNativeString)return this.__ntkNativeString;}catch(_){}return nativeToString.apply(this,arguments);};}}catch(_){}function post(u,body64,tag){var p='';try{p=(new URL(u)).pathname;}catch(_){}try{if(p==='/api/ad/ack'&&!body64){log({compactAckGuardPostEmptySkip:tag,path:p});return{raw:{status:204,statusText:'No Content',headers:{}},text:'',json:{skippedEmptyAck:true}};}var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),body64||''),o=JSON.parse(raw||'{}'),txt=d64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}log({compactAckGuardPost:tag,path:p,status:o.status||0,body:j,bodyLen:String(body64||'').length});if(p==='/api/ad/ack'&&(o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){var rq={};try{rq=JSON.parse(d64(body64||'')||'{}');}catch(_){}markProof('compact-guard-i4-ack-200',rq.tp||'');}return{raw:o,text:txt,json:j};}catch(e){log({compactAckGuardPostError:tag,path:p,error:String(e)});return null;}}var beacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev'){body64Any(data).then(function(body){post(u,body,'beacon');});return true;}}catch(e){}return nb?nb.apply(this,arguments):false;};try{beacon.__ntkNativeString='function sendBeacon() { [native code] }';}catch(_){}navigator.sendBeacon=beacon;if(nf){var wrappedFetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if((p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev')&&m==='POST'){var body=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:null;return body64Any(body).then(function(bd){var r=post(u,bd,'fetch'),bytes=bytes(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}}catch(e){log({compactAckFetchBridgeError:String(e)});}return nf.apply(this,arguments);};try{wrappedFetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}window.fetch=wrappedFetch;}log({compactAckGuardBridgeInstalled:true,fetch:!!nf,beacon:!!nb,toStringSpoof:true});}catch(e){log({compactAckGuardBridgeInstallError:String(e)});}}"
                + "function guardVersion(){try{var gv=window.NtkQuicBridge&&window.NtkQuicBridge.guardVersionFor?String(window.NtkQuicBridge.guardVersionFor(pageUrl())||''):'';if(gv)return gv;var h=document.documentElement?document.documentElement.outerHTML:'';var m=h.match(/wv=([^\"'&<>]+)/);if(m&&m[1])return decodeURIComponent(m[1]);m=h.match(/b\\d{13}[^\"'<>]*wasm-\\d{13}/);if(m&&m[0])return m[0];}catch(_){}return '';}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wu='',st=performance.now();if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()}),jr=null,wr=null;if(window.NtkQuicBridge.requestGetBatch){var br=JSON.parse(window.NtkQuicBridge.requestGetBatch(JSON.stringify([abs(js),abs(wasm)]),h)||'{}'),rs=br.results||[];jr=rs[0]||{};wr=rs[1]||{};}else{jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');}if((jr.status||0)===200&&(wr.status||0)===200&&jr.bodyBase64&&wr.bodyBase64){var jb=bytes(jr.bodyBase64||''),wb=bytes(wr.bodyBase64||''),raw=wb&&wb.length>4&&wb[0]===0&&wb[1]===97&&wb[2]===115&&wb[3]===109,bu=URL.createObjectURL(new Blob([new TextDecoder().decode(jb)],{type:'application/javascript'}));wu=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}log({compactAckGuardBridgeModule:true,jsBytes:jb.length,wasmBytes:wb.length,rawWasm:raw,version:v,ms:Math.round(performance.now()-st),batch:!!window.NtkQuicBridge.requestGetBatch,exports:Object.keys(mod||{}).slice(0,12)});if(mod&&raw&&mod.initSync){var it=performance.now();mod.initSync(wb.buffer.slice(wb.byteOffset,wb.byteOffset+wb.byteLength));log({compactAckGuardInitSync:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)});wu='';}else if(mod&&mod.default){var dt=performance.now();await Promise.race([mod.default({module_or_path:wu}),sleep(2600).then(function(){log({compactAckGuardDefaultInitTimeout:true});})]);log({compactAckGuardDefaultInitDone:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-dt)});}}}catch(e){mod=null;log({compactAckGuardBridgeModuleError:String(e),version:v,ms:Math.round(performance.now()-st)});}}if(!mod){var ds=performance.now();mod=await import(abs(js));log({compactAckGuardDirectModule:true,version:v,ms:Math.round(performance.now()-ds),exports:Object.keys(mod||{}).slice(0,12)});if(mod&&mod.default)await Promise.race([mod.default({module_or_path:abs(wasm)}),sleep(2600).then(function(){log({compactAckGuardDirectInitTimeout:true});})]);}if(wu)try{URL.revokeObjectURL(wu);}catch(_){}window.__ntkGuardModule=mod;return mod;}catch(e){log({compactAckGuardLoadError:String(e),version:guardVersion()});return null;}}"
                + "function ensureHost(){try{if(document.body)return document.body;var b=document.createElement('body');if(document.documentElement)document.documentElement.appendChild(b);return document.body||b;}catch(e){try{log({compactAckHostError:String(e)});}catch(_){}return document.documentElement;}}"
                + "function ensureRows(ch){try{var urls=(ch&&ch.impressionUrls)||[],token=String(ch&&ch.token||''),slot=Math.max(Number(ch&&ch.slotCount||0),urls.length,4),root=document.getElementById('__ntk_guard_rows'),host=ensureHost();if(!host){log({compactAckRowsNoHost:true});return;}if(!root||root.getAttribute('data-ntk-token')!==token){if(root&&root.parentNode)root.parentNode.removeChild(root);root=document.createElement('section');root.id='__ntk_guard_rows';root.setAttribute('data-ntk-token',token);root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));root.style.cssText='display:grid;grid-template-columns:repeat(4,78px);gap:8px;position:relative;opacity:1;visibility:visible;width:390px;min-height:76px';for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.setAttribute(i===0?'data-bs':'data-bp','1');b.style.cssText='display:block;width:78px;height:48px;padding:0;margin:0;border:0;background:transparent';var img=document.createElement('img');img.width=78;img.height=48;img.loading='eager';img.decoding='sync';img.alt='';img.src=urls.length?abs(urls[i%urls.length]):'data:image/gif;base64,R0lGODlhTgAwAPAAAP///wAAACH5BAAAAAAALAAAAABOADAAAAIKjI+py+0Po5yUFQA7';b.appendChild(img);root.appendChild(b);}host.insertBefore(root,host.firstChild||null);}var de=document.documentElement||host,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;de.setAttribute('data-ab',ab);de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);log({compactAckRows:true,slot:slot,imps:urls.length,seen:Number(window.__bSeen||window[ab]||window[rb]||0),hasBody:!!document.body});}catch(e){log({compactAckRowsError:String(e)});}}"
                + "async function fireImps(ch){var seen=0,imps=(ch&&ch.impressionUrls)||[],target=Math.max(1,Math.min(imps.length,Number(ch&&ch.slotCount||imps.length||4)));await Promise.all(imps.slice(0,target).map(function(u,i){return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).then(function(r){if((r.status||0)>=200&&(r.status||0)<300)seen++;log({compactAckImp:i,status:r.status,seen:seen,target:target});}).catch(function(e){log({compactAckImpError:i,error:String(e)});});}));return seen;}"
                + "async function guardProof(token){try{var mod=await loadGuard();if(!mod||!mod._vc||!token)return '';var args=[token,JSON.stringify({token:token,path:scope}),scope];for(var i=0;i<args.length;i++){try{var v=mod._vc(args[i]);if(v&&v.then)v=await v;v=String(v||'');log({compactAckProofTry:i,len:v.length,value:v.slice(0,16)});if(v&&v!=='true'&&v!=='false')return v;}catch(e){log({compactAckProofTryError:i,error:String(e)});}}}catch(e){log({compactAckProofError:String(e)});}return '';}"
                + "async function runGuardI4(ch){try{var mod=await loadGuard();if(!mod||!mod.__i4||!ch)return false;installGuardAckBridge();var arg=JSON.stringify(ch),before=proofed();log({compactAckI4Start:true,argLen:arg.length,before:before});try{var r=mod.__i4(arg,scope);if(r&&r.then)await Promise.race([r,sleep(900)]);}catch(e){log({compactAckI4CallError:String(e)});}var ok=await waitProof(2200);log({compactAckI4Done:true,proofed:ok});return ok;}catch(e){log({compactAckI4Error:String(e)});return false;}}"
                + "async function run(){var started=Date.now(),attempt=0;try{window.__ntkAckOnlyRunning=scope;window.__ntkAckOnlyRunningAt=Date.now();delete window.__ntk_ad_ack_proof_200;delete window.__ntk_ad_ack_scope;delete window.__ntk_ad_ack_last;log({compactAckStart:true,scope:scope,hasViewer:!!window.NtkViewerBridge,hasNativeViewer:!!window.__NtkViewerBridgeNative,hasNamedViewer:!!window.MangaViewerNativeViewerBridge});for(attempt=1;attempt<=2&&!proofed();attempt++){var c=null;try{var nt=String(bcall('getNativeAckChallenge',scope)||'');if(nt){var nj=JSON.parse(nt||'{}');if(nj&&nj.ok)c={status:200,body:nj,text:nt,native:true};}}catch(e){log({compactAckNativeChallengeError:String(e)});}if(!c)c=await bridgeReq('/api/ad/challenge','POST',{path:scope});log({compactAckChallenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge),native:!!(c&&c.native)});if(!c||c.status!==200||!c.body||!c.body.ok)continue;if(c.body.trusted&&!c.body.challenge){send({code:0,body:{ok:false,ackOnly:true,softTrusted:true,attempts:attempt}});return;}var ch=c.body.challenge;if(!ch)continue;ensureRows(ch);var token=String(ch.token||''),seenTask=fireImps(ch),i4Task=runGuardI4(ch),seen=await Promise.race([seenTask,sleep(1600).then(function(){return 0;})]);if(await Promise.race([i4Task,waitProof(2600)]))break;var tp=await Promise.race([guardProof(token),sleep(2600).then(function(){return '';})]);log({compactAckProofDone:true,tpLen:String(tp||'').length,seen:seen});if(!tp||tp==='true'||tp==='false')continue;var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||0),(ch.impressionUrls||[]).length,4),visible=Math.max(minSeen,Math.min(total,seen||minSeen));await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};log({compactAckResponse:true,status:a&&a.status||0,body:b,tpLen:String(tp||'').length,total:total,visible:visible});if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markProof('compact-bridge-ack-200',tp);break;}await sleep(260);}var ok=proofed();send({code:ok?200:0,body:{ok:ok,ackOnly:true,compact:true,proofed:ok,attempts:attempt-1,ms:Date.now()-started}});}catch(e){log({compactAckError:String(e)});send({code:0,error:String(e),body:{ok:false,ackOnly:true,compact:true}});}}run();})()";
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
                                                      String episodeId, String imagesToken,
                                                      boolean serverAckProof) {
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        if(!"__ack_only__".equals(imagesToken))
            return buildViewerImageApiOnlyScript(baseUrl, path, endpoint, workId, episodeId, imagesToken,
                    serverAckProof);
        return NTK_QUIC_BRIDGE_JS + "(function(){var base=" + jsonQuote(baseUrl) + ",scope=" + jsonQuote(path) + ",kind=" + jsonQuote(kind)
                + ",workId=" + jsonQuote(workId) + ",episodeId=" + jsonQuote(episodeId)
                + ",token=" + jsonQuote(imagesToken) + ",endpoint=" + jsonQuote(endpoint) + ";"
                + "var sent=false,ackOnly=token==='__ack_only__';"
                + buildViewerBridgeShimScript()
                + "var ackRunAge=Date.now()-Number(window.__ntkAckOnlyRunningAt||0);"
                + "if(window.__ntkAckOnlyRunning===scope&&ackOnly&&ackRunAge<2200){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEvalSkipped:true,scope:scope,age:ackRunAge,ackOnly:ackOnly,shortGuard:true}));}catch(_){}return null;}if(window.__ntkAckOnlyRunning===scope&&ackOnly){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyRunningStaleCleared:true,scope:scope,age:ackRunAge,ackOnly:ackOnly}));delete window.__ntkAckOnlyRunning;}catch(_){}}else if(window.__ntkAckOnlyRunning===scope&&ackRunAge<4500){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEvalSkipped:true,scope:scope,age:ackRunAge,ackOnly:ackOnly,shortGuard:false}));}catch(_){}return null;}if(ackOnly){window.__ntkAckOnlyRunning=scope;window.__ntkAckOnlyRunningAt=Date.now();}"
                + "if(ackOnly){try{window.__ntkAckOnlyDirectAdApi=1;window.__ntk_fast_shell=0;if(document.documentElement)document.documentElement.removeAttribute('data-ntk-fast-shell');window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyRealMainFrame:true,fastShellCleared:true,directAdApi:true,href:String(location.href||'').slice(0,160)}));}catch(_){}}"
                + "if(ackOnly){try{var __prevTp=String(window.__ntk_ad_ack_tp||'');delete window.__ntk_ad_ack_tp;delete window.__ntk_ad_ack_tp_scope;delete window.__ntk_ad_ack_tp_token;delete window.__ntk_ad_ack_scope;delete window.__ntk_ad_ack_last;delete window.__ntk_ad_ack_proof_200;delete window.__ntk_ad_ack_inflight;delete window.__ntk_ad_ack_challenge_pending;delete window.__ntk_ad_ack_challenge_last;delete window.__ntkPreGuardCanaryToken;window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyProofStateReset:true,scope:scope,prevTpLen:__prevTp.length}));}catch(_){}}"
                + "function ackWebpackReq(){try{var req=null;(self.webpackChunk_N_E=self.webpackChunk_N_E||[]).push([[Math.floor(Math.random()*1e9)],{},function(r){req=r;}]);return req;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerReqError:String(e)}));}catch(_){}return null;}}"
                + "function ackSignerMod(){try{var req=ackWebpackReq(),m=null;try{m=req&&req(47760);}catch(_){}if(m&&(m.X||m.D))return m;var c=req&&req.c?req.c:{},ks=Object.keys(c);for(var i=0;i<ks.length;i++){var ex=c[ks[i]]&&c[ks[i]].exports;if(ex&&(ex.X||ex.D)){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerFound:true,id:String(ks[i]),hasX:!!ex.X,hasD:!!ex.D,source:'cache'}));}catch(_){}return ex;}}var defs=req&&req.m?req.m:{},ds=Object.keys(defs),cands=[];for(var j=0;j<ds.length;j++){try{var src=String(defs[ds[j]]||'');if(src.indexOf('x-ntk-key-id')>=0||src.indexOf('/api/client-key/register')>=0||src.indexOf('bodyText')>=0&&src.indexOf('crypto')>=0)cands.push(ds[j]);}catch(_){}}for(var k=0;k<cands.length;k++){try{var id=cands[k],ex2=req(id);if(ex2&&(ex2.X||ex2.D)){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerFound:true,id:String(id),hasX:!!ex2.X,hasD:!!ex2.D,source:'defs'}));}catch(_){}return ex2;}}catch(loadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerLoadError:true,id:String(cands[k]),error:String(loadErr).slice(0,120)}));}catch(_){}}}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerMissing:true,cacheModules:ks.length,defModules:ds.length,candidates:cands.slice(0,12),reqKeys:req?Object.keys(req).slice(0,16):[]}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackSignerFindError:String(e)}));}catch(_){}}return null;}"
                + "function ackIdentityMod(){try{var req=ackWebpackReq(),m=null;try{m=req&&req(68950);}catch(_){}if(m&&(m.qv||m.pI||m.qS))return m;var c=req&&req.c?req.c:{},ks=Object.keys(c);for(var i=0;i<ks.length;i++){var ex=c[ks[i]]&&c[ks[i]].exports;if(ex&&(ex.qv||ex.pI||ex.qS)){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackIdentityFound:true,id:String(ks[i]),hasQv:!!ex.qv,hasPI:!!ex.pI,source:'cache'}));}catch(_){}return ex;}}var defs=req&&req.m?req.m:{},ds=Object.keys(defs),cands=[];for(var j=0;j<ds.length;j++){try{var src=String(defs[j]||defs[ds[j]]||'');if(src.indexOf('__ntk_ev_id')>=0||src.indexOf('/api/ev/sync')>=0||src.indexOf('ntk_fp')>=0)cands.push(ds[j]);}catch(_){}}for(var k=0;k<cands.length;k++){try{var id=cands[k],ex2=req(id);if(ex2&&(ex2.qv||ex2.pI||ex2.qS)){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackIdentityFound:true,id:String(id),hasQv:!!ex2.qv,hasPI:!!ex2.pI,source:'defs'}));}catch(_){}return ex2;}}catch(loadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackIdentityLoadError:true,id:String(cands[k]),error:String(loadErr).slice(0,120)}));}catch(_){}}}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackIdentityMissing:true,cacheModules:ks.length,defModules:ds.length,candidates:cands.slice(0,12)}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackIdentityFindError:String(e)}));}catch(_){}}return null;}"
                + "async function ensureSiteIdentity(ms){try{var m=ackIdentityMod();if(!m){try{window.NtkViewerBridge.onAckState(JSON.stringify({siteIdentityEnsure:false,reason:'missing'}));}catch(_){}return false;}var wait=Math.max(400,Number(ms||1600));var before=document.cookie||'';if(m.qv)await Promise.race([m.qv(),sleep(wait).then(function(){return null;})]);if(m.pI)await Promise.race([m.pI(wait),sleep(wait).then(function(){return null;})]);var after=document.cookie||'',hasFp=/(?:^|;\\s*)ntk_fp=/.test(after),hasEv=/(?:^|;\\s*)__ntk_ev_id=/.test(after),hasEt=/(?:^|;\\s*)__ntk_et_id=/.test(after);try{window.NtkViewerBridge.onAckState(JSON.stringify({siteIdentityEnsure:true,hasFp:hasFp,hasEv:hasEv,hasEt:hasEt,cookieChanged:before!==after,hasQv:!!m.qv,hasPI:!!m.pI}));}catch(_){}return hasFp;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({siteIdentityEnsureError:String(e)}));}catch(_){}return false;}}"
                + "async function ensureBrowserKeyIdb(force,early){try{if(!force&&window.__ntk_request_key_id&&window.__ntk_image_private_key)return true;if(!force&&!early&&window.__ntkBrowserKeyTask)return await window.__ntkBrowserKeyTask;if(force){try{delete window.__ntk_request_key_id;delete window.__ntk_image_private_key;delete window.__ntkBrowserKeyTask;}catch(_){window.__ntk_request_key_id='';window.__ntk_image_private_key=null;window.__ntkBrowserKeyTask=null;}}try{await ensureSiteIdentity(ackOnly?2200:1400);}catch(siteIdentityErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({siteIdentityPreKeyError:String(siteIdentityErr)}));}catch(_){}}if(!force&&window.indexedDB){try{var stored=await new Promise(function(done){var rq=indexedDB.open('ntk-browser-request-key',1);rq.onerror=function(){done(null);};rq.onsuccess=function(){var db=rq.result;try{var tx=db.transaction('keys','readonly'),gr=tx.objectStore('keys').get('manhwa-v1');gr.onsuccess=function(){try{db.close();}catch(_){}done(gr.result||null);};gr.onerror=function(){try{db.close();}catch(_){}done(null);};}catch(e){try{db.close();}catch(_){}done(null);}};});if(stored&&stored.keyId&&(!stored.expiresAt||Number(stored.expiresAt)>Date.now()+60000)){var pk=stored.privateKey||null;if(!pk&&stored.privateJwk&&window.crypto&&crypto.subtle){try{pk=await crypto.subtle.importKey('jwk',stored.privateJwk,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);}catch(importErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbImportError:String(importErr),keyId:String(stored.keyId).slice(0,12)}));}catch(_){}}}if(pk){window.__ntk_request_key_id=String(stored.keyId);window.__ntk_image_private_key=pk;try{localStorage.setItem('ntk-browser-request-key-id',String(stored.keyId));if(stored.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(stored.expiresAt));}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbLoad:true,keyId:String(stored.keyId).slice(0,12),hasPrivate:!!stored.privateKey,hasPrivateJwk:!!stored.privateJwk,expiresIn:Number(stored.expiresAt||0)-Date.now()}));}catch(_){}return true;}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbNoPrivate:true,keyId:String(stored.keyId).slice(0,12),hasPrivateJwk:!!stored.privateJwk,early:!!early}));}catch(_){}}}catch(loadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbLoadError:String(loadErr)}));}catch(_){}}}if(!force&&!early){try{var lsKid=localStorage.getItem('ntk-browser-request-key-id')||'',lsExp=Number(localStorage.getItem('ntk-browser-request-key-exp')||0),lsJwk=localStorage.getItem('ntk-browser-request-private-jwk')||'';if(lsKid&&lsJwk&&(!lsExp||lsExp>Date.now()+60000)&&window.crypto&&crypto.subtle){var lsPk=await crypto.subtle.importKey('jwk',JSON.parse(lsJwk),{name:'ECDSA',namedCurve:'P-256'},false,['sign']);window.__ntk_request_key_id=lsKid;window.__ntk_image_private_key=lsPk;try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyLocalJwkLoad:true,keyId:String(lsKid).slice(0,12),expiresIn:lsExp-Date.now()}));}catch(_){}return true;}if(lsKid&&(!lsExp||lsExp>Date.now()+60000)){window.__ntk_request_key_id=lsKid;try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyLocalLoad:true,keyId:String(lsKid).slice(0,12),privateUnknown:true,expiresIn:lsExp-Date.now()}));}catch(_){}if(!ackOnly)return true;}}catch(_){}}try{if(ackOnly&&window.NtkQuicBridge&&(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent||window.NtkQuicBridge.ensureViewerBrowserKey)){var nrRaw=window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent?window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(pageUrl(),String(navigator.userAgent||'')):window.NtkQuicBridge.ensureViewerBrowserKey(pageUrl()),nr=JSON.parse(String(nrRaw||'{}'));try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyNativeAckEnsure:!!nr.ok,status:nr.status||0,keyId:nr.keyId?String(nr.keyId).slice(0,12):'',cached:!!nr.cached,ackOnly:true}));}catch(_){}if(nr&&nr.ok&&nr.keyId){window.__ntk_request_key_id=String(nr.keyId);return true;}}}catch(nativeKeyErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyNativeAckEnsureError:String(nativeKeyErr),ackOnly:true}));}catch(_){}}try{var mod=ackSignerMod(),siteId=null;if(!force&&mod&&mod.D){siteId=await Promise.race([mod.D(scope),sleep(ackOnly?2600:1600).then(function(){return null;})]);}if(!force&&!siteId&&mod&&mod.D&&!ackOnly&&window.NtkQuicBridge&&window.fetch){var nf=window.__ntkNativeFetch||window.fetch;try{window.fetch=function(input,init){try{var u=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(u))).pathname;if(p!=='/api/client-key/register')return nf.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){var h={'content-type':'application/json'};try{var ih=new Headers((init&&init.headers)||(input&&input.headers)||{});ih.forEach(function(v,k){h[k]=v;});}catch(_){}var raw=window.NtkQuicBridge.request(abs('/api/client-key/register'),'POST',JSON.stringify(h),body),o=JSON.parse(raw||'{}'),bytes=bytesFrom64(o.bodyBase64||'');try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteRegisterBridge:true,status:o.status||0,len:bytes.length,ackOnly:ackOnly}));}catch(_){}return new Response(bytes,{status:o.status||200,statusText:o.statusText||'OK',headers:o.headers||{}});});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteRegisterBridgeError:String(e),ackOnly:ackOnly}));}catch(_){}return nf.apply(this,arguments);}};try{window.fetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}siteId=await Promise.race([mod.D(scope),sleep(ackOnly?2600:1600).then(function(){return null;})]);}finally{window.fetch=nf;}}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteEnsure:!!siteId,keyId:String(siteId||'').slice(0,12),href:String(location.href||'').slice(0,120),ackOnly:ackOnly,hasModule:!!(mod&&mod.D),force:!!force,hasPrivate:!!window.__ntk_image_private_key}));}catch(_){}if(siteId&&window.__ntk_image_private_key){window.__ntk_request_key_id=siteId;return true;}if(siteId&&!window.__ntk_image_private_key){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteIdNoPrivate:true,keyId:String(siteId).slice(0,12),ackOnly:ackOnly,force:!!force}));}catch(_){}if(!ackOnly){window.__ntk_request_key_id=siteId;return true;}}}catch(siteErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteEnsureError:String(siteErr),href:String(location.href||'').slice(0,120),ackOnly:ackOnly,force:!!force}));}catch(_){}}if(!window.NtkQuicBridge)return false;if(!window.crypto||!crypto.subtle||!crypto.getRandomValues){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyUnavailable:true,hasCrypto:!!window.crypto,hasSubtle:!!(window.crypto&&crypto.subtle),hasRandom:!!window.crypto&&!!crypto.getRandomValues,href:String(location.href||'').slice(0,120)}));}catch(_){}return false;}function stdB64(a){var s='',chunk=0x8000;for(var i=0;i<a.length;i+=chunk){var sub=a.subarray(i,i+chunk);s+=String.fromCharCode.apply(null,sub);}return btoa(s);}function ensureNtkFp(){try{if(window.__ntk_fp_ready===1&&/(?:^|;\\s*)ntk_fp=[a-fA-F0-9]{16,}/.test(document.cookie||''))return true;function h(seed,text){var r=seed>>>0;for(var i=0;i<text.length;i++){r^=text.charCodeAt(i);r=Math.imul(r,0x1000193)>>>0;}return ('00000000'+(r>>>0).toString(16)).slice(-8);}function cv(){try{var c=document.createElement('canvas');c.width=200;c.height=50;var x=c.getContext('2d');if(!x)return '';x.textBaseline='top';x.font='14px Arial';x.fillStyle='#f60';x.fillRect(0,0,100,50);x.fillStyle='#069';x.fillText('ntk-fp-©',2,2);x.fillStyle='rgba(102,204,0,0.7)';x.fillText('ntk-fp-©',4,4);var d=c.toDataURL();return d.slice(Math.max(0,d.length-120));}catch(_){return '';}}function gl(){try{var g=document.createElement('canvas').getContext('webgl'),e=g&&g.getExtension('WEBGL_debug_renderer_info');if(!g||!e)return '';return [String(g.getParameter(e.UNMASKED_VENDOR_WEBGL)||''),String(g.getParameter(e.UNMASKED_RENDERER_WEBGL)||'')].join('|');}catch(_){return '';}}var n=navigator||{},seed=[n.userAgent||'',n.language||'',Array.isArray(n.languages)?n.languages.join(','):'',String(n.hardwareConcurrency||0),String(n.deviceMemory||0),n.platform||'',String(n.maxTouchPoints||0),window.screen?(screen.width||0)+'x'+(screen.height||0)+'x'+(screen.colorDepth||0):'',String(new Date().getTimezoneOffset()),(window.Intl&&Intl.DateTimeFormat)?Intl.DateTimeFormat().resolvedOptions().timeZone:'',cv(),gl()].join('|'),fp=h(0x811c9dc5,seed)+h(0xbb40e64d,seed)+h(0x9e3779b1,seed)+h(0x5f356495,seed);document.cookie='ntk_fp='+encodeURIComponent(fp)+'; Path=/; Max-Age=31536000; SameSite=Lax; Secure';try{window.__ntk_fp_ready=1;}catch(_){}return true;}catch(_){return false;}}ensureNtkFp();var kp=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']),jwk=await crypto.subtle.exportKey('jwk',kp.publicKey),privateJwk=await crypto.subtle.exportKey('jwk',kp.privateKey),keyPayload={publicKey:jwk};try{var fp=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ntk_fp')||''):'';var pid=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ntk_pid')||''):'';var vsid=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'__vsid')||''):'';var fpFromIdentity=false;if(!fp){try{var ac=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ad_ack_c')||window.NtkQuicBridge.cookie(pageUrl(),'ad_ack')||''):'';var p=ac.split('.')[0]||'',pad='===='.slice((p.length+3)%4),js=JSON.parse(atob((p+pad).replace(/-/g,'+').replace(/_/g,'/'))||'{}');if(js&&js.identity){fp=String(js.identity);fpFromIdentity=true;}}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRegisterPayload:true,fpPresent:!!fp,fpFromIdentity:!!fpFromIdentity,pidPresent:!!pid,vsidPresent:!!vsid,force:!!force,early:!!early}));}catch(_){}}catch(_){}var bodyText=JSON.stringify(keyPayload),body=stdB64(new TextEncoder().encode(bodyText)),directStatus=0,directJson=null;try{if(window.fetch){var dr=await Promise.race([fetch(abs('/api/client-key/register'),{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'content-type':'application/json'},body:bodyText}),sleep(2200).then(function(){return null;})]);if(dr){directStatus=dr.status||0;var dt=await dr.text().catch(function(){return '';});try{directJson=JSON.parse(dt||'{}');}catch(_){directJson=null;}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyDirectRegister:true,status:directStatus,ok:!!(directJson&&directJson.ok),keyId:directJson&&directJson.keyId?String(directJson.keyId).slice(0,12):'',href:String(location.href||'').slice(0,120),force:!!force,early:!!early}));}catch(_){}if(directStatus===200&&directJson&&directJson.ok&&directJson.keyId){window.__ntk_request_key_id=directJson.keyId;window.__ntk_image_private_key=kp.privateKey;try{localStorage.setItem('ntk-browser-request-key-id',String(directJson.keyId));if(directJson.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(directJson.expiresAt));localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(privateJwk));}catch(_){}if(window.__ntkStoreReqKey)await window.__ntkStoreReqKey({keyId:directJson.keyId,privateKey:kp.privateKey,privateJwk:privateJwk,expiresAt:directJson.expiresAt||Date.now()+3600000});return true;}}}}catch(directErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyDirectRegisterError:String(directErr).slice(0,140),href:String(location.href||'').slice(0,120),force:!!force,early:!!early}));}catch(_){}}var raw=window.NtkQuicBridge.request(abs('/api/client-key/register'),'POST',JSON.stringify({'content-type':'application/json'}),body),o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyStandaloneRegister:true,status:o.status||0,ok:!!(j&&j.ok),keyId:j&&j.keyId?String(j.keyId).slice(0,12):'',href:String(location.href||'').slice(0,120),force:!!force,early:!!early,body:String(txt||'').slice(0,80)}));}catch(_){}if((o.status||0)!==200||!j||!j.ok||!j.keyId)return false;window.__ntk_request_key_id=j.keyId;window.__ntk_image_private_key=kp.privateKey;try{localStorage.setItem('ntk-browser-request-key-id',String(j.keyId));if(j.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(j.expiresAt));localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(privateJwk));}catch(_){}if(window.__ntkStoreReqKey)await window.__ntkStoreReqKey({keyId:j.keyId,privateKey:kp.privateKey,privateJwk:privateJwk,expiresAt:j.expiresAt||Date.now()+3600000});return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyStandaloneError:String(e),href:String(location.href||'').slice(0,120),force:!!force}));}catch(_){}return false;}};try{window.__ntkEnsureBrowserKeyIdb=ensureBrowserKeyIdb;}catch(_){}"
                + "if(window.__ntkViewerImageFetchLock===scope){var __lockAge=Date.now()-Number(window.__ntkViewerImageFetchLockAt||0);if(!ackOnly&&__lockAge<=4200)return;}window.__ntkViewerImageFetchLock=scope;window.__ntkViewerImageFetchLockAt=Date.now();"
                + "function send(o){if(sent)return;sent=true;try{if(window.__ntkViewerImageFetchLock===scope)delete window.__ntkViewerImageFetchLock;if(window.__ntkAckOnlyRunning===scope)delete window.__ntkAckOnlyRunning;}catch(e){}try{window.NtkViewerBridge.onViewerImages(JSON.stringify(o));}catch(e){}}"
                + "function domImages(){try{var out=[],seen={},nodes=document.querySelectorAll('.vw-imgs img,.vw-spread img,.vw-spread-pair img,img');for(var i=0;i<nodes.length;i++){var img=nodes[i];if(!img)continue;if(img.closest('[data-br],[data-brs],[data-bs],[data-bp],button,a[href^=\"https://t.me\"]'))continue;var src=String(img.currentSrc||img.src||'');if(!src||seen[src])continue;if(/^data:/i.test(src)||/\\/api\\/banners\\//i.test(src)||/\\/banner/i.test(src)||/telegram/i.test(src)||/adservice|doubleclick|googlesyndication/i.test(src))continue;var w=img.naturalWidth||img.width||0,h=img.naturalHeight||img.height||0;if(w>0&&h>0&&(w<160||h<160))continue;seen[src]=1;out.push({page:out.length+1,src:src,width:w,height:h});}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomProbe:true,count:out.length,href:String(location.href||'').slice(0,160)}));}catch(_){}return out;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomProbeError:String(e)}));}catch(_){}return [];}}"
                + "function abs(url){return new URL(url,base).href;}"
                + "function pageUrl(){return abs(scope);}"
                + "function baseOrigin(){return new URL(base).origin;}"
                + "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function b64(bytes){var s='';for(var i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}"
                + "function docCookie(){try{return document.cookie||'';}catch(e){return '';}}"
                + "function bridgeCookie(name){try{return window.NtkQuicBridge?String(window.NtkQuicBridge.cookie(pageUrl(),name)||''):'';}catch(e){return '';}}"
                + "function cookie(name){var text=docCookie(),m=text.match(new RegExp('(?:^|;\\\\s*)'+name+'=([^;]*)'));if(m)return decodeURIComponent(m[1]);return bridgeCookie(name);}"
                + "function b64json(v){try{v=String(v||'').replace(/-/g,'+').replace(/_/g,'/');while(v.length%4)v+='=';return JSON.parse(atob(v));}catch(e){return null;}}"
                + "function ackCookieMatches(v){try{if(!v)return false;var p=String(v).split('.'),j=b64json(p.length>2?p[1]:p[0]);return !!j&&j.scope===scope&&(!j.exp||Number(j.exp)>Date.now());}catch(e){return false;}}"
                + "async function hmac(key,msg){var enc=new TextEncoder();if(window.crypto&&crypto.subtle&&crypto.subtle.importKey){try{var k=await crypto.subtle.importKey('raw',enc.encode(key),{name:'HMAC',hash:'SHA-256'},false,['sign']);return b64(new Uint8Array(await crypto.subtle.sign('HMAC',k,enc.encode(msg))));}catch(e){}}if(window.NtkQuicBridge){var v=String(window.NtkQuicBridge.hmacSha256(key,msg)||'');if(v)return v;}throw new Error('hmac unavailable');}"
                + "async function nv(){var v=cookie('nv');if(!v||(v.split('.')[0]||'').length<40){try{await bridgeReq('/api/nv-issue','POST',null);}catch(e){}v=cookie('nv');}if(!v||(v.split('.')[0]||'').length<40){await fetch(abs('/api/nv-issue'),{method:'POST',credentials:'same-origin',cache:'no-store'}).catch(function(){});v=cookie('nv');}return (!v||(v.split('.')[0]||'').length<40)?'':v;}"
                + "function sameScope(a){try{a=String(a||'');if(a===scope)return true;if(decodeURIComponent(a)===scope)return true;var p=(new URL(pageUrl())).pathname;return a===p||decodeURIComponent(a)===decodeURIComponent(p);}catch(e){return false;}}function ackProofed(){try{if(sameScope(window.__ntk_ad_ack_proof_200))return true;var l=window.__ntk_ad_ack_last;return !!(l&&l.proof200&&sameScope(l.scope));}catch(e){}return false;}function acked(){try{if(sameScope(window.__ntk_ad_ack_scope))return true;var l=window.__ntk_ad_ack_last;if(l&&sameScope(l.scope))return true;if(ackCookieMatches(cookie('ad_ack'))){markAck();return true;}}catch(e){}return false;}"
                + "function rearm(){try{window.dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{reason:kind+'-native-403',scope:scope}}));}catch(e){}}"
                + "function waitAck(ms){return new Promise(function(resolve){if(acked())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.scope===scope)||acked())finish(true);}var to=setTimeout(function(){finish(acked());},ms);var iv=setInterval(function(){if(acked())finish(true);},120);window.addEventListener('ntk-ad-ack-ready',ev);});}function waitProof(ms){return new Promise(function(resolve){if(ackProofed())return resolve(true);var done=false;function finish(v){if(done)return;done=true;clearTimeout(to);clearInterval(iv);window.removeEventListener('ntk-ad-ack-ready',ev);resolve(v);}function ev(e){if((e.detail&&e.detail.proof200&&e.detail.scope===scope)||ackProofed())finish(true);}var to=setTimeout(function(){finish(ackProofed());},ms);var iv=setInterval(function(){if(ackProofed())finish(true);},80);window.addEventListener('ntk-ad-ack-ready',ev);});}"
                + "async function api(){var v=await nv();if(!v)return{status:401,body:{error:'missing session'}};var n=new Uint8Array(24);crypto.getRandomValues(n);var nonce=b64(n);var proof=await hmac(v,token+'.'+nonce+'.'+navigator.userAgent),body={workId:workId,episodeId:episodeId,token:token,nonce:nonce,proof:proof},extra={'x-images-client':'viewer-v1'};try{var mod=ackSignerMod(),bodyText=JSON.stringify(body);if(mod&&mod.X){var signed=await mod.X({method:'POST',path:endpoint,scope:scope,bodyText:bodyText});if(signed&&signed.headers){Object.keys(signed.headers).forEach(function(k){extra[k]=signed.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesSiteSigned:true,keyId:String(signed.keyId||signed.headers['x-ntk-key-id']||'').slice(0,12),path:endpoint}));}catch(_){}}}}catch(se){try{window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesSiteSignError:String(se),path:endpoint}));}catch(_){}}return await bridgeReq(endpoint,'POST',body,extra);}"
                + "function decode64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}"
                + "function bytesFrom64(b){try{var s=atob(b||''),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&255;return a;}catch(e){return new Uint8Array(0);}}"
                + "function markAck(){try{window.__ntk_ad_ack_scope=scope;var prev=window.__ntk_ad_ack_last;if(!(prev&&prev.proof200&&sameScope(prev.scope)))window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true}}));}catch(e){}}"
                + "function markProofAck(source,tp){try{window.__ntk_ad_ack_scope=scope;window.__ntk_ad_ack_proof_200=scope;window.__ntk_ad_ack_last={scope:scope,ts:Date.now(),native:true,proof200:true,source:source||'bridge-ack-200'};try{window.NtkViewerBridge.onAckProof(JSON.stringify({scope:scope,tp:tp||'',source:source||'bridge-ack-200'}));}catch(_){}window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:scope,native:true,proof200:true}}));}catch(e){}}"
                + "async function bridgeReq(url,method,body,extra){var absolute=abs(url),h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()};if(extra){Object.keys(extra).forEach(function(k){h[k]=extra[k];});}var bodyText=body?JSON.stringify(body):'',body64=body?b64(new TextEncoder().encode(bodyText)):'',pathName='';try{pathName=(new URL(absolute)).pathname;}catch(_){}var signedImageApi=(pathName==='/api/manhwa-images'||pathName==='/api/webtoon-images')&&!!h['x-ntk-key-id'];function bridgeSigned(tag){try{var raw=window.NtkQuicBridge.request(absolute,method,JSON.stringify(h),body64);var o=JSON.parse(raw||'{}'),text=decode64(o.bodyBase64||''),json={};try{json=JSON.parse(text||'{}');}catch(e){}try{window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesSignedBridge:tag,path:pathName,status:o.status||0,body:json,keyId:String(h['x-ntk-key-id']||'').slice(0,12)}));}catch(_){}return{status:o.status||0,body:json,text:text,transport:'signed-bridge'};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesSignedBridgeError:tag,path:pathName,error:String(e)}));}catch(_){}throw e;}}if(window.NtkQuicBridge&&!signedImageApi){try{var raw=window.NtkQuicBridge.request(absolute,method,JSON.stringify(h),body64);var o=JSON.parse(raw||'{}');if(!o.ok)throw new Error(o.error||'bridge failed');var text=decode64(o.bodyBase64||''),json={};try{json=JSON.parse(text||'{}');}catch(e){}if(ackOnly&&pathName==='/api/ad/challenge'&&(o.status||0)===403){try{var low=String(text||'').toLowerCase();if(low.indexOf('just a moment')>=0||low.indexOf('cloudflare')>=0||low.indexOf('challenge-platform')>=0){window.__ntkAckOnlyHardBlock=1;window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyHardBlock:true,path:pathName,status:o.status||0,transport:'bridge'}));return{status:o.status||0,body:json,text:text,transport:'bridge'};}}catch(_){}}return{status:o.status||0,body:json,text:text,transport:'bridge'};}catch(be){try{window.NtkViewerBridge.onAckState(JSON.stringify({bridgeReqFallback:true,url:pathName,error:String(be)}));}catch(_){}}}try{if(signedImageApi)window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesNativeFetch:true,path:pathName,keyId:String(h['x-ntk-key-id']||'').slice(0,12)}));}catch(_){}var opt={method:method,credentials:'same-origin',cache:'no-store',headers:h};if(body)opt.body=bodyText;var fetchFn=window.__ntkNativeFetch||window.fetch;try{var r=await fetchFn.call(window,absolute,opt);var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(e){}try{if(ackOnly&&pathName.indexOf('/api/ad/')===0)window.NtkViewerBridge.onAckState(JSON.stringify({bridgeReqFetchResult:true,path:pathName,status:r.status,ok:!!(j&&j.ok),body:j,textHead:String(t||'').slice(0,100)}));}catch(_){}try{if(ackOnly&&pathName==='/api/ad/challenge'&&r.status===403){var low=String(t||'').toLowerCase();if(low.indexOf('just a moment')>=0||low.indexOf('cloudflare')>=0||low.indexOf('challenge-platform')>=0){window.__ntkAckOnlyHardBlock=1;window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyHardBlock:true,path:pathName,status:r.status,transport:'fetch'}));}}}catch(_){}return{status:r.status,body:j,text:t,transport:'fetch'};}catch(fe){try{if(signedImageApi)window.NtkViewerBridge.onAckState(JSON.stringify({viewerImagesNativeFetchError:true,path:pathName,error:String(fe)}));else if(ackOnly&&pathName.indexOf('/api/ad/')===0)window.NtkViewerBridge.onAckState(JSON.stringify({bridgeReqFetchError:true,path:pathName,error:String(fe)}));}catch(_){}if(signedImageApi&&window.NtkQuicBridge)return bridgeSigned('native-error');throw fe;}}"
                + "async function browserReq(url,method,body,extra){var absolute=abs(url),pathName='';try{pathName=(new URL(absolute)).pathname;}catch(_){}var h={'content-type':'application/json','accept':'application/json'};if(extra){Object.keys(extra).forEach(function(k){var lk=String(k||'').toLowerCase();if(lk!=='origin'&&lk!=='referer'&&lk!=='host'&&lk!=='cookie')h[k]=extra[k];});}var bodyText=body?JSON.stringify(body):'',fetchFn=window.__ntkNativeFetch||window.fetch;try{var opt={method:method,credentials:'same-origin',cache:'no-store',headers:h};if(body)opt.body=bodyText;var r=await fetchFn.call(window,absolute,opt);var t=await r.text().catch(function(){return '';});var j={};try{j=JSON.parse(t||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserReq:true,path:pathName,status:r.status,ok:!!(j&&j.ok),bodyKeys:j?Object.keys(j).slice(0,12):[],textHead:String(t||'').slice(0,80)}));}catch(_){}if((r.status>=400||(!j.ok&&String(t||'').indexOf('<html')>=0))&&window.NtkQuicBridge){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserReqBridgeFallback:true,path:pathName,status:r.status,html:String(t||'').indexOf('<html')>=0}));}catch(_){}return bridgeReq(url,method,body,extra);}return{status:r.status,body:j,text:t,transport:'browser-fetch'};}catch(fe){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserReqError:true,path:pathName,error:String(fe)}));}catch(_){}return bridgeReq(url,method,body,extra);}}"
                + "function fireImp(u){try{if(typeof bridgeReq==='function')return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).catch(function(){});return fetch(abs(u),{credentials:'same-origin',cache:'no-store',mode:'no-cors'}).catch(function(){});}catch(e){return Promise.resolve();}}"
                + "function body64Async(b){try{if(b==null)return Promise.resolve('');if(window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof b==='string')return Promise.resolve(b64(new TextEncoder().encode(b)));if(window.ArrayBuffer&&b instanceof ArrayBuffer)return Promise.resolve(b64(new Uint8Array(b)));if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b))return Promise.resolve(b64(new Uint8Array(b.buffer,b.byteOffset,b.byteLength)));if(window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(b64(new TextEncoder().encode(String(b))));}catch(e){return Promise.resolve('');}}"
                + "function installGuardBeaconBridge(){try{if(!window.NtkQuicBridge||navigator.__ntkGuardBeaconBridge)return;var nativeBeacon=window.__ntkNativeBeacon||navigator.sendBeacon,nativeFetch=window.__ntkNativeFetch||window.fetch;function shouldBridgePath(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function ackFrom(p,o,j,body){try{if(p==='/api/ad/ack'&&(o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){var rq={};try{rq=JSON.parse(decode64(body||'')||'{}');}catch(_){}if(typeof markProofAck==='function')markProofAck('guard-bridge-ack-200',rq.tp||'');else markAck();}}catch(_){}}function bridgePost(p,u,body,tag){try{var requestText='';try{requestText=decode64(body||'').slice(0,420);}catch(_){}var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),body);var o=JSON.parse(raw||'{}'),text=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(text||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,len:body.length,request:requestText,body:j}));}catch(_){}ackFrom(p,o,j,body);return{raw:o,text:text,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e)}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=1;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeBeacon?nativeBeacon.apply(this,arguments):false;body64Async(data).then(function(body){bridgePost(p,u,body,'beacon');});return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeOuterError:String(e)}));}catch(_){}return nativeBeacon?nativeBeacon.apply(this,arguments):false;}};if(nativeFetch){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeFetch.apply(this,arguments);var method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(method!=='POST')return nativeFetch.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){var r=bridgePost(p,u,body,'fetch');var bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardFetchBridgeOuterError:String(e)}));}catch(_){}return nativeFetch.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,mEv:true,fetch:true,ackNative:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e)}));}catch(_){}}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var urls=ch.impressionUrls||[],slot=Math.max(2,Number(ch.slotCount||urls.length||4)),root=document.getElementById('__ntk_guard_rows'),host=document.body||document.documentElement;if(!root){root=document.createElement('section');root.id='__ntk_guard_rows';root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));host.insertBefore(root,host.firstChild||null);}if(root.getAttribute('data-ntk-token')!==String(ch.token||'')){root.textContent='';root.setAttribute('data-ntk-token',String(ch.token||''));root.setAttribute('data-br-n',String(slot));for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.className='';b.setAttribute(i===0?'data-bs':'data-bp','1');var img=document.createElement('img');img.width=64;img.height=34;img.alt='';img.loading='eager';img.decoding='sync';img.src=urls[i]?abs(urls[i]):'data:image/gif;base64,R0lGODlhCgAGAPAAAP///wAAACH5BAAAAAAALAAAAAAKAAYAAAIIhI+py+0PYysAOw==';b.appendChild(img);root.appendChild(b);}}var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);var seen=Number(window.__bSeen||window[ab]||window[rb]||0);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,slot:slot,ab:ab,rb:rb,seen:seen,siteShape:true,unstyled:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e)}));}catch(_){}}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var host=document.body||document.documentElement,total=26,token=String(ch.token||''),urls=ch.impressionUrls||[],old=document.querySelectorAll('[data-ntk-synth-br=\"1\"]');for(var oi=0;oi<old.length;oi++){if(old[oi].getAttribute('data-ntk-token')!==token){try{old[oi].parentNode&&old[oi].parentNode.removeChild(old[oi]);}catch(_){}}}function ad(btn,i){btn.setAttribute('data-bs','1');var img=document.createElement('img');img.width=88;img.height=44;img.alt='';img.loading='eager';img.decoding='sync';img.style.cssText='display:block;width:88px;height:44px;object-fit:cover;opacity:1;visibility:visible';img.src=urls.length?abs(urls[i%urls.length]):'data:image/gif;base64,R0lGODlhWAA sAPAAAP///wAAACH5BAAAAAAALAAAAABYACwAAAIKjI+py+0Po5yUFQA7'.replace(' ','');btn.appendChild(img);}function make(slot,count,id,offset){var root=id?document.getElementById(id):null;if(!root||root.getAttribute('data-ntk-token')!==token){root=document.createElement('section');if(id)root.id=id;root.className='';root.style.cssText='display:grid;grid-template-columns:repeat(4,88px);gap:8px;position:relative;z-index:1;opacity:1;visibility:visible;pointer-events:auto';root.setAttribute('data-ntk-synth-br','1');root.setAttribute('data-ntk-token',token);root.setAttribute('data-br','1');root.setAttribute('data-brs',slot);root.setAttribute('data-br-n',String(count));for(var i=0;i<count;i++){var b=document.createElement('button');b.type='button';b.className='';b.style.cssText='display:block;width:88px;height:44px;padding:0;margin:0;border:0;background:transparent;opacity:1;visibility:visible;pointer-events:auto';b.setAttribute('aria-label','newtoki62');ad(b,offset+i);root.appendChild(b);}host.insertBefore(root,host.firstChild||null);}return root;}make('header',24,'__ntk_guard_rows',0);make('detail',2,'__ntk_guard_rows_detail',24);var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),total);window[ab]=Math.max(Number(window[ab]||0),total);window[rb]=Math.max(Number(window[rb]||0),total);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,slot:total,header:24,detail:2,ab:ab,rb:rb,seen:Number(window.__bSeen||0),siteShape:true,siteBannerRows:true,adImages:true,imageUrls:urls.length}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e)}));}catch(_){}}}"
                + "function siteRowsReady(){try{var rows=Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n]'));if(!rows.length)return false;for(var ri=0;ri<rows.length;ri++){var row=rows[ri],n=Number(row.getAttribute('data-br-n')||'0');if(!Number.isFinite(n)||n<=0)return false;if(n<2)continue;var bs=Array.from(row.children).filter(function(e){return e instanceof HTMLElement&&e.tagName==='BUTTON'&&(e.hasAttribute('data-bs')||e.hasAttribute('data-bp'));});if(bs.length<2)return false;var r0=bs[0].getBoundingClientRect();if(bs[0].getClientRects().length===0)return false;var sep=false;for(var i=1;i<bs.length;i++){var r=bs[i].getBoundingClientRect();if(Math.abs(r.left-r0.left)>=1||Math.abs(r.top-r0.top)>=1){sep=true;break;}}if(!sep)return false;}return true;}catch(e){return false;}}"
                + "function waitSiteRows(ms){return new Promise(function(resolve){try{if(siteRowsReady())return resolve(true);var done=false,started=Date.now();function finish(v){if(done)return;done=true;try{mo&&mo.disconnect();}catch(_){}clearTimeout(to);resolve(v);}function check(){if(siteRowsReady())finish(true);else if(Date.now()-started>=ms)finish(false);}var mo=new MutationObserver(check),to=setTimeout(function(){finish(siteRowsReady());},Math.max(300,Number(ms||3000)));try{mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['data-br-n','data-bs','data-bp','src','style','class']});}catch(_){}requestAnimationFrame(function(){requestAnimationFrame(check);});}catch(e){resolve(false);}});}"
                + "try{var __ntkWaitSiteRows=waitSiteRows;waitSiteRows=function(ms){try{if(ackOnly)return Promise.resolve(false);return __ntkWaitSiteRows(ms);}catch(e){return Promise.resolve(false);}};}catch(_){}"
                + "function guardImages(){try{return Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n] img'));}catch(e){return[];}}"
                + "function guardLoadedCount(){try{var imgs=guardImages(),n=0;for(var i=0;i<imgs.length;i++){if(imgs[i].complete&&imgs[i].naturalWidth>0&&imgs[i].naturalHeight>0)n++;}return n;}catch(e){return 0;}}"
                + "function waitGuardImages(need,ms){return new Promise(function(resolve){try{need=Math.max(1,Number(need||1));if(guardLoadedCount()>=need)return resolve(true);var imgs=guardImages(),done=false;function finish(v){if(done)return;done=true;clearTimeout(to);imgs.forEach(function(img){try{img.removeEventListener('load',check);img.removeEventListener('error',check);}catch(_){}});resolve(v);}function check(){if(guardLoadedCount()>=need)finish(true);}imgs.forEach(function(img){try{img.addEventListener('load',check);img.addEventListener('error',check);if(img.decode)img.decode().then(check,check);}catch(_){}});var to=setTimeout(function(){finish(guardLoadedCount()>=need);},Math.max(300,Number(ms||3600)));check();}catch(e){resolve(false);}});}"
                + "function guardDomState(label,mod){try{var rows=Array.from(document.querySelectorAll('[data-br=\"1\"][data-br-n]')),root=rows[0]||document.getElementById('__ntk_guard_rows'),imgs=guardImages(),buttons=root?root.querySelectorAll('button'):[],rr=root?root.getBoundingClientRect():null,first=imgs[0]||null,br=buttons[0]?buttons[0].getBoundingClientRect():null,ready=mod&&mod.__i5?!!mod.__i5():null;window.NtkViewerBridge.onAckState(JSON.stringify({guardDomState:label,ready:ready,rows:rows.length,imgs:imgs.length,loaded:guardLoadedCount(),buttons:buttons.length,root:rr?{w:rr.width,h:rr.height}:null,button:br?{w:br.width,h:br.height}:null,img:first?{complete:first.complete,nw:first.naturalWidth,nh:first.naturalHeight,w:first.width,h:first.height,src:String(first.getAttribute('src')||'').slice(0,160),currentSrc:String(first.currentSrc||'').slice(0,160)}:null,seen:Number(window.__bSeen||0)}));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDomStateError:label,error:String(e)}));}catch(_){}}}"
                + "function guardGlobalState(label,mod){try{var o={guardGlobalState:label,acked:acked(),ready:mod&&mod.__i5?!!mod.__i5():null,ibOk:window.__ntk_ib_ok===undefined?null:String(window.__ntk_ib_ok),ibLoaded:window.__ntk_ib_loaded===undefined?null:String(window.__ntk_ib_loaded),aiTampered:window.__ntk_ai_tampered===undefined?null:String(window.__ntk_ai_tampered),inflight:window.__ntk_ad_ack_inflight===undefined?null:String(window.__ntk_ad_ack_inflight).slice(0,96),ackScope:window.__ntk_ad_ack_scope===undefined?null:String(window.__ntk_ad_ack_scope),ackLast:window.__ntk_ad_ack_last?JSON.stringify(window.__ntk_ad_ack_last).slice(0,160):null,guardLoadLast:window.__ntk_ad_guard_load_last?JSON.stringify(window.__ntk_ad_guard_load_last).slice(0,160):null,seen:Number(window.__bSeen||0),rows:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length};window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardGlobalStateError:label,error:String(e)}));}catch(_){}}}"
                + "function guardVersion(){try{var h=document.documentElement?document.documentElement.outerHTML:'';var m=h.match(/wv=([^\"'&<>]+)/);if(m&&m[1])return decodeURIComponent(m[1]);m=h.match(/b\\d{13}[^\"'<>]*wasm-\\d{13}/);if(m&&m[0])return m[0];m=h.match(/\\/(b\\d{13})\\/_next\\/static\\//);if(m&&m[1]){var n=Number(m[1].slice(1));if(Number.isFinite(n))return m[1]+'-wasm-'+(n+4);}if(performance&&performance.getEntriesByType){var es=performance.getEntriesByType('resource')||[];for(var i=0;i<es.length;i++){var u=String(es[i].name||''),r=u.match(/wv=([^&]+)/);if(r&&r[1])return decodeURIComponent(r[1]);r=u.match(/\\/(b\\d{13})\\/_next\\/static\\//);if(r&&r[1]){var q=Number(r[1].slice(1));if(Number.isFinite(q))return r[1]+'-wasm-'+(q+4);}}}try{if(window.NtkQuicBridge&&window.NtkQuicBridge.guardVersionFor){var gv=String(window.NtkQuicBridge.guardVersionFor(pageUrl())||'');if(gv)return gv;}}catch(_){}}catch(e){}return '';}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch))}));}catch(_){ }var v=guardVersion();var q=v?'?v='+encodeURIComponent(v):'';var js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q;if(!v){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadFallback:'no version'}));}catch(_){}}var mod=null,wasmUrl='';if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),jt=new TextDecoder().decode(jb),blob=new Blob([jt],{type:'application/javascript'}),bu=URL.createObjectURL(blob),wb=bytesFrom64(wr.bodyBase64||'');wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModule:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,wasmBlob:true}));}catch(_){}}}catch(bridgeLoadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleError:String(bridgeLoadErr)}));}catch(_){}}}if(!mod)mod=await import(abs(js));if(mod&&mod.default){try{await mod.default({module_or_path:wasmUrl||abs(wasm)});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch)),fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80)}));}catch(_){ }var v=guardVersion();var q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='';try{var st=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModule:true,version:v,ms:Math.round(performance.now()-st),source:abs(js)}));}catch(_){}if(mod&&mod.default){var it=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}catch(directErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleError:String(directErr),version:v}));}catch(_){}if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),jt=new TextDecoder().decode(jb),blob=new Blob([jt],{type:'application/javascript'}),bu=URL.createObjectURL(blob),wb=bytesFrom64(wr.bodyBase64||'');wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModule:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,wasmBlob:true}));}catch(_){}if(mod&&mod.default){try{await mod.default({module_or_path:wasmUrl||abs(wasm)});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}}}}catch(bridgeLoadErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleError:String(bridgeLoadErr)}));}catch(_){}}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestored:true,fetchNative:/native code/.test(Function.prototype.toString.call(window.fetch)),bridgeFirst:true}));}catch(_){ }var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='',st=performance.now();if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),jt=new TextDecoder().decode(jb),bu=URL.createObjectURL(new Blob([jt],{type:'application/javascript'}));wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleFirst:true,jsBytes:jb.length,wasmBytes:wb.length,version:v,ms:Math.round(performance.now()-st)}));}catch(_){}if(mod&&mod.default){var it=performance.now();try{await mod.default({module_or_path:wasmUrl});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}}catch(bridgeErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleFirstError:String(bridgeErr),version:v}));}catch(_){}}}if(!mod){try{var dt=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleFallback:true,version:v,ms:Math.round(performance.now()-dt),source:abs(js)}));}catch(_){}if(mod&&mod.default){var di=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectInitFallback:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-di)}));}catch(_){}}}catch(directErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectModuleFallbackError:String(directErr),version:v}));}catch(_){}}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadError:String(e),version:guardVersion(),bridgeFirst:true}));}catch(_){}return null;}}"
                + "async function guardAck(ch){try{var mod=await loadGuard();if(!mod||!ch){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckMissing:'module'}));}catch(_){}return false;}try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisBeforeI4:true}));}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeBeforeI4Disabled:true}));}catch(_){}try{var fastShell=!ackOnly&&(window.__ntk_fast_shell===1||window.__ntk_fast_shell==='1');var realRows=false;if(!ackOnly&&!fastShell)realRows=await waitSiteRows(4500);if(realRows){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsNative:true,count:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length,ackOnly:ackOnly}));}catch(_){}}if(ackOnly||!realRows){ensureGuardRows(ch);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsChallenge:ackOnly,guardRowsSyntheticFast:fastShell,count:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length,ackOnly:ackOnly,skipSiteRows:ackOnly}));}catch(_){}}try{setTimeout(function(){fireGuardImpressions(ch);},0);}catch(_){}var imageNeed=ackOnly?Math.max(1,Number((ch&&ch.minSeen)||1)):Math.max(1,Math.min(8,guardImages().length||Number((ch&&ch.slotCount)||0)||(ch&&ch.impressionUrls&&ch.impressionUrls.length)||Number((ch&&ch.minSeen)||2)));var imageReady=await waitGuardImages(imageNeed,3200);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardImagesReady:imageReady,need:imageNeed,loaded:guardLoadedCount(),total:guardImages().length,fastShell:fastShell,ackOnly:ackOnly,waitAll:true}));}catch(_){}guardDomState('rows',mod);}catch(_){}var seenBefore=guardLoadedCount();try{if(!ackOnly&&(window.__ntk_fast_shell===1||window.__ntk_fast_shell==='1')&&guardLoadedCount()>=Math.max(1,Number((ch&&ch.minSeen)||1))){window.__ntk_ib_ok=1;window.__ntk_ib_loaded=1;window.NtkViewerBridge.onAckState(JSON.stringify({guardFastShellIbState:true,loaded:guardLoadedCount(),seen:seenBefore||0,asyncImps:true,deferred:true}));}}catch(_){}try{guardDomState('after-imps',mod);}catch(_){}try{if(ackOnly){try{var row=document.getElementById('__ntk_guard_rows')||document.querySelector('[data-br=\"1\"][data-br-n]');if(row){try{row.style.position='relative';row.style.zIndex='2147483647';row.style.opacity='1';row.style.visibility='visible';row.style.pointerEvents='auto';}catch(_){}if(row.scrollIntoView)row.scrollIntoView({block:'start',inline:'nearest'});}else window.scrollTo(0,0);window.dispatchEvent(new Event('scroll'));window.dispatchEvent(new Event('resize'));document.dispatchEvent(new Event('visibilitychange'));await Promise.race([new Promise(function(r){try{requestAnimationFrame(function(){requestAnimationFrame(r);});}catch(_){r();}}),sleep(120)]);}catch(_){}await sleep(60);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardVisibilitySettled:true,loaded:guardLoadedCount(),rows:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length,hasFocus:document.hasFocus?document.hasFocus():null,visibility:document.visibilityState,inner:{w:innerWidth,h:innerHeight},scrollY:scrollY,boundedRaf:true}));}catch(_){}}}catch(_){}try{delete window.__ntk_ad_ack_inflight;window.NtkViewerBridge.onAckState(JSON.stringify({guardInflightCleared:true,scope:scope}));}catch(_){}try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestoredAtI4:true,fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80),beaconText:String(Function.prototype.toString.call(navigator.sendBeacon)).slice(0,80)}));}catch(_){}try{guardGlobalState('before-i4',mod);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardExports:Object.keys(mod).slice(0,24),hasI4:!!mod.__i4,hasI5:!!mod.__i5,defaultType:typeof mod.default,prime:'none'}));}catch(_){}var fn=mod.__i4||mod.i4||mod._i4||mod.guardAck||mod.adAck;if(!fn)return false;try{if(!ackOnly){if(window.__ntkEnsureBrowserKeyIdb)await window.__ntkEnsureBrowserKeyIdb();else if(typeof ensureBrowserKeyIdb==='function')await ensureBrowserKeyIdb();}installGuardBeaconBridge();window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeI4:!!window.__ntk_request_key_id,keyId:String(window.__ntk_request_key_id||'').slice(0,12),ackOnly:ackOnly,bridgeAck:true,postBridgeAck:ackOnly,skipped:ackOnly}));}catch(preKeyErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeI4Error:String(preKeyErr)}));}catch(_){}}var arg=JSON.stringify(ch);for(var i=0;i<1;i++){try{var before=mod.__i5?!!mod.__i5():null;if(ackOnly&&window.NtkQuicBridge&&!(window.__ntkPreGuardCanaryOk&&window.__ntkPreGuardCanaryOkToken===String(ch.token||''))){try{var cyBody=b64(new TextEncoder().encode(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:ch.token||'',token:ch.token||'',path:scope})));var cyRaw=window.NtkQuicBridge.request(abs('/api/ad/canary'),'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),cyBody),cy=JSON.parse(cyRaw||'{}');window.NtkViewerBridge.onAckState(JSON.stringify({guardPreI4Canary:true,status:cy.status||0}));}catch(cyErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardPreI4CanaryError:String(cyErr)}));}catch(_){}}}else if(ackOnly&&window.__ntkPreGuardCanaryOk){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardPreI4CanarySkipped:true,reason:'pre_guard_ok'}));}catch(_){}}var r=fn(arg,scope);try{installGuardBeaconBridge();window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeAfterI4:true,bridgeAck:true,postInstall:ackOnly}));}catch(bridgeAfterErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeAfterI4Error:String(bridgeAfterErr)}));}catch(_){}}if(r&&r.then)r=await r;var after=mod.__i5?!!mod.__i5():null;var ackWait=ackOnly?1500:3000;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckTry:i,mode:'site-like-native-webview',argLen:String(arg||'').length,arg2Len:String(scope||'').length,arg:String(arg||'').slice(0,48),result:String(r||'').slice(0,80),before:before,after:after,acked:acked(),siteRetry:true,waitMs:ackWait,ackOnly:ackOnly}));}catch(_){}try{guardDomState('after-i4-'+i,mod);guardGlobalState('after-i4-'+i,mod);}catch(_){}if(await waitAck(ackWait))return true;if(ackOnly){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckAckOnlyFallback:true,tag:'guardAckFallback'}));}catch(_){}if(await signedDirectAck(ch,'guardAckFallbackAckOnly'))return true;return acked();}if(await signedDirectAck(ch,'guardAckFallback'))return true;}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckTryError:i,error:String(inner),siteRetry:true,ackOnly:ackOnly}));}catch(_){}}}try{guardGlobalState('after-i4-all',mod);}catch(_){}return acked();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckError:String(e),version:guardVersion()}));}catch(_){}return false;}}"
                + "async function guardProof(token){try{var mod=await loadGuard();if(!mod||!mod._vc||!token)return '';var args=[token,JSON.stringify({token:token,path:scope}),scope];for(var i=0;i<args.length;i++){try{var v=mod._vc(args[i],scope);if(v&&v.then)v=await v;v=String(v||'');try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTry:i,len:v.length,value:v.slice(0,16)}));}catch(_){}if(v&&v!=='true'&&v!=='false')return v;}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTryError:i,error:String(inner)}));}catch(_){}}}return '';}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofError:String(e)}));}catch(_){}return '';}}"
                + "async function fireGuardImpressions(ch){var seen=0,submitted=0;try{var imps=(ch&&ch.impressionUrls)||[],minSeen=Math.max(1,Number((ch&&ch.minSeen)||2)),slot=Math.max(minSeen,Number((ch&&ch.slotCount)||imps.length||minSeen)),target=Math.max(1,Math.min(slot,imps.length));var tasks=[];function record(ix,code,transport){if(code>=200&&code<300)seen++;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImp:ix,status:code,seen:seen,target:target,parallel:true,transport:transport||'bridge'}));}catch(_){}}function bridgeOne(ix,u,tag){return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).then(function(r){record(ix,r&&r.status||0,tag||'bridge');}).catch(function(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpError:ix,error:String(inner),parallel:true,transport:tag||'bridge'}));}catch(_){}});}function one(ix){var u=imps[ix];submitted++;if(ackOnly&&(window.__ntkNativeFetch||window.fetch)){try{var ff=window.__ntkNativeFetch||window.fetch;return ff.call(window,abs(u),{method:'GET',credentials:'include',cache:'no-store',headers:{'accept':'image/gif,image/*,*/*'}}).then(function(r){record(ix,r&&r.status||0,'fetch');}).catch(function(){return bridgeOne(ix,u,'bridge-fallback');});}catch(_){}}return bridgeOne(ix,u,'bridge');}for(var i=0;i<target;i++)tasks.push(one(i));await Promise.all(tasks);}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsError:String(e),parallel:true}));}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsComplete:true,seen:seen,submitted:submitted,parallel:true}));}catch(_){}return seen;}"
                + jsChunk("try{(function(){fireGuardImpressions=async function(ch){var seen=0,submitted=0;try{var imps=(ch&&ch.impressionUrls)||[],minSeen=Math.max(1,Number((ch&&ch.minSeen)||2)),slot=Math.max(minSeen,Number((ch&&ch.slotCount)||imps.length||minSeen)),target=Math.max(1,Math.min(slot,imps.length)),tasks=[];function record(ix,code,transport){if(code>=200&&code<300)seen++;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImp:ix,status:code,seen:seen,target:target,parallel:true,transport:transport||'bridge'}));}catch(_){}}function bridgeOne(ix,u,tag){return bridgeReq(u,'GET',null,{'accept':'image/gif,image/*,*/*'}).then(function(r){record(ix,r&&r.status||0,tag||'bridge');}).catch(function(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpError:ix,error:String(inner),parallel:true,transport:tag||'bridge'}));}catch(_){}});}function one(ix){var u=imps[ix];submitted++;if(ackOnly&&(window.__ntkNativeFetch||window.fetch)){try{var ff=window.__ntkNativeFetch||window.fetch;return ff.call(window,abs(u),{method:'GET',credentials:'include',cache:'no-store',headers:{'accept':'image/gif,image/*,*/*'}}).then(function(r){var st=r&&r.status||0;record(ix,st,'fetch');if(st>=200&&st<300)return;return bridgeOne(ix,u,'bridge-fallback-status-'+st);}).catch(function(){return bridgeOne(ix,u,'bridge-fallback-error');});}catch(_){}}return bridgeOne(ix,u,'bridge');}for(var i=0;i<target;i++)tasks.push(one(i));await Promise.all(tasks);}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsError:String(e),parallel:true,fallbackOnStatus:true}));}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsComplete:true,seen:seen,submitted:submitted,parallel:true,fallbackOnStatus:true}));}catch(_){}return seen;};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpBridgeFallbackOnStatusInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpBridgeFallbackOnStatusInstallError:String(e)}));}catch(_){}}")
                + "function randHex(n){try{var a=new Uint8Array(Math.ceil((n||16)/2));crypto.getRandomValues(a);var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s.slice(0,n||16);}catch(e){return String(Date.now().toString(16)+Math.random().toString(16).slice(2)).slice(0,n||16);}}"
                + "async function signedDirectAck(ch,tag){try{if(!window.NtkQuicBridge||!ch)return false;var token=ch.token||'',seen=await fireGuardImpressions(ch);if(await waitAck(180))return true;var tp=await guardProof(token);if(!tp||tp==='true'||tp==='false'){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckNoProof:true,seen:seen,tag:tag||''}));}catch(_){}return false;}var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen)),body={challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp},bodyText=JSON.stringify(body),headers={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()};try{var m=ackSignerMod();if(m&&m.X){var sig=await m.X({method:'POST',path:scope,scope:scope,bodyText:bodyText});if(sig&&sig.headers){Object.keys(sig.headers).forEach(function(k){headers[k]=sig.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckSigned:true,keyId:String(sig.keyId||sig.headers['x-ntk-key-id']||'').slice(0,12),signedPath:'scope',tag:tag||''}));}catch(_){}}}}catch(se){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckSignError:String(se),tag:tag||''}));}catch(_){}}try{var cyBody=b64(new TextEncoder().encode(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope}))),cyRaw=window.NtkQuicBridge.request(abs('/api/ad/canary'),'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),cyBody),cy=JSON.parse(cyRaw||'{}'),cyText=decode64(cy.bodyBase64||''),cyJson={};try{cyJson=JSON.parse(cyText||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckCanary:true,status:cy.status||0,body:cyJson,tag:tag||''}));}catch(_){}}catch(ce){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckCanaryError:String(ce),tag:tag||''}));}catch(_){}}if(!headers['x-ntk-key-id']){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckNoKey:true,tpLen:String(tp||'').length,seen:seen,tag:tag||''}));}catch(_){}return false;}try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckSubmit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen,keyId:String(headers['x-ntk-key-id']||'').slice(0,12),tag:tag||''}));}catch(_){}var raw=window.NtkQuicBridge.request(abs('/api/ad/ack'),'POST',JSON.stringify(headers),b64(new TextEncoder().encode(bodyText))),o=JSON.parse(raw||'{}'),text=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(text||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckBridge:true,status:o.status||0,body:j,keyHeader:!!headers['x-ntk-key-id'],tag:tag||''}));}catch(_){}if((o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){if(typeof markProofAck==='function')markProofAck('signed-direct-ack-200',tp||'');else markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({signedDirectAckError:String(e),tag:tag||''}));}catch(_){}}return false;}"
                + "async function directAck(){try{var serverScope=scope;try{serverScope=(new URL(pageUrl())).pathname||scope;}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckStart:true,scope:scope,serverScope:serverScope,href:String(location.href||'').slice(0,140)}));}catch(_){}var c=await browserReq('/api/ad/challenge','POST',{path:serverScope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckChallenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),trusted:!!(c&&c.body&&c.body.trusted),hasChallenge:!!(c&&c.body&&c.body.challenge),error:c&&c.body&&c.body.error||'',bodyKeys:c&&c.body?Object.keys(c.body).slice(0,12):[],transport:c&&c.transport||''}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok){try{var nt=window.NtkViewerBridge&&window.NtkViewerBridge.getNativeAckChallenge?String(window.NtkViewerBridge.getNativeAckChallenge(serverScope)||''):'';if(nt){var nj=JSON.parse(nt||'{}');if(nj&&nj.ok){c={status:200,body:nj,text:nt,transport:'native-challenge-cache'};window.NtkViewerBridge.onAckState(JSON.stringify({directAckNativeChallenge:true,ok:!!nj.ok,hasChallenge:!!nj.challenge,bytes:nt.length,serverScope:serverScope}));}}}catch(nce){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckNativeChallengeError:String(nce)}));}catch(_){}}}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}if(!c.body.challenge)return false;var ch=c.body.challenge,token=ch.token||'';try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckGuardStart:true,tokenLen:String(token||'').length,slotCount:ch.slotCount||0,minSeen:ch.minSeen||0,imps:(ch.impressionUrls||[]).length}));}catch(_){}var guardOk=await Promise.race([guardAck(ch),sleep(3200).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckTimeout:true}));}catch(_){}return false;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckGuardDone:true,guardOk:!!guardOk,acked:acked()}));}catch(_){}if(guardOk)return true;var seen=await fireGuardImpressions(ch);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckImpsSeen:true,seen:seen,acked:acked()}));}catch(_){}if(await waitAck(700))return true;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofStart:true,tokenLen:String(token||'').length}));}catch(_){}var tp=await Promise.race([guardProof(token),sleep(1800).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTimeout:true}));}catch(_){}return '';})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofDone:true,tpLen:String(tp||'').length,truthy:!!tp}));}catch(_){}if(!tp||tp==='true'||tp==='false'){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckNoProof:true,seen:seen,acked:acked()}));}catch(_){}return acked();}var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckSubmit:true,tp:String(tp||''),tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen,serverScope:serverScope}));}catch(_){}await sleep(120);var cy=await browserReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:serverScope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{},transport:cy&&cy.transport||''}));}catch(_){}var a=await browserReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:serverScope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckResponse:true,status:a&&a.status||0,body:b,transport:a&&a.transport||''}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckError:String(e)}));}catch(_){}}return false;}"
                + "async function directAckProofFirst(){try{var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}if(!c.body.challenge)return false;var ch=c.body.challenge;if(await guardAck(ch))return true;var token=ch.token||'',proofTask=guardProof(token),seenTask=fireGuardImpressions(ch),tp=await proofTask,seen=await seenTask;if(!tp||tp==='true'||tp==='false'){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstNoProof:true,seen:seen}));}catch(_){}return false;}var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstSubmit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen,afterGuard:true}));}catch(_){}var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstResponse:true,status:a&&a.status||0,body:b,afterGuard:true}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstError:String(e)}));}catch(_){}}return false;}"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstDisabled:true}));}catch(_){}"
                + "try{var __ntkRawWaitAck=waitAck;waitAck=function(ms){var n=Math.max(0,Number(ms)||0),cap=Math.min(n,ackOnly?1500:n);return Promise.race([__ntkRawWaitAck(cap),sleep(cap+30).then(function(){return acked();})]);};window.NtkViewerBridge.onAckState(JSON.stringify({waitAckBounded:true,ackOnly:ackOnly,cap:ackOnly?1500:null}));}catch(_){}"
                + "try{if(ackOnly&&typeof directAckProofFirst==='function'){directAck=directAckProofFirst;window.__ntkDirectAckStable=directAck;window.__ntkGuardAckStable=guardAck;window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstEnabled:true,ackOnly:true,stableStored:true}));}}catch(_){}"
                + "try{if(ackOnly){window.__ntkLooseAcked=acked;acked=function(){try{return ackCookieMatches(cookie('ad_ack'));}catch(e){return false;}};window.NtkViewerBridge.onAckState(JSON.stringify({strictServerAckCookieOnly:true}));}}catch(_){}"
                + "function installGuardBeaconBridge(){try{if(!window.NtkQuicBridge||navigator.__ntkGuardBeaconBridge)return;var nativeBeacon=window.__ntkNativeBeacon||navigator.sendBeacon,nativeFetch=window.__ntkNativeFetch||window.fetch,nativeToString=Function.prototype.toString;try{if(!Function.prototype.__ntkBridgeToString){Object.defineProperty(Function.prototype,'__ntkBridgeToString',{value:1,configurable:true});Function.prototype.toString=function(){try{if(this&&this.__ntkNativeString)return this.__ntkNativeString;}catch(_){}return nativeToString.apply(this,arguments);};}}catch(_){}function shouldBridgePath(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function ackFrom(p,o,j,body){try{if(p==='/api/ad/ack'&&(o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){var rq={};try{rq=JSON.parse(decode64(body||'')||'{}');}catch(_){}if(typeof markProofAck==='function')markProofAck('guard-bridge-ack-200',rq.tp||'');else markAck();}}catch(_){}}function bridgePost(p,u,body,tag){try{var requestText='';try{requestText=decode64(body||'').slice(0,420);}catch(_){}var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),body);var o=JSON.parse(raw||'{}'),text=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(text||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,len:body.length,request:requestText,body:j,toStringSpoof:true,ackNative:true}));}catch(_){}ackFrom(p,o,j,body);return{raw:o,text:text,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e),toStringSpoof:true,ackNative:true}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=1;var beacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeBeacon?nativeBeacon.apply(this,arguments):false;body64Async(data).then(function(body){bridgePost(p,u,body,'beacon');});return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeOuterError:String(e),ackNative:true}));}catch(_){}return nativeBeacon?nativeBeacon.apply(this,arguments):false;}};try{beacon.__ntkNativeString='function sendBeacon() { [native code] }';}catch(_){}navigator.sendBeacon=beacon;if(nativeFetch){var fetchBridge=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!shouldBridgePath(p))return nativeFetch.apply(this,arguments);var method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(method!=='POST')return nativeFetch.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){var r=bridgePost(p,u,body,'fetch');var bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardFetchBridgeOuterError:String(e),ackNative:true}));}catch(_){}return nativeFetch.apply(this,arguments);}};try{fetchBridge.__ntkNativeString='function fetch() { [native code] }';}catch(_){}window.fetch=fetchBridge;}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,mEv:true,fetch:!!nativeFetch,toStringSpoof:true,ackNative:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),toStringSpoof:true,ackNative:true}));}catch(_){}}}"
                + jsChunk("try{installGuardBeaconBridge=function(){try{if(!window.NtkQuicBridge)return;var nativeBeacon=window.__ntkNativeBeacon||navigator.sendBeacon,nativeFetch=window.__ntkNativeFetch||window.fetch,nativeToString=Function.prototype.toString;try{if(!Function.prototype.__ntkBridgeToString){Object.defineProperty(Function.prototype,'__ntkBridgeToString',{value:1,configurable:true});Function.prototype.toString=function(){try{if(this&&this.__ntkNativeString)return this.__ntkNativeString;}catch(_){}return nativeToString.apply(this,arguments);};}}catch(_){}function should(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function stdB64(a){var s='',c=0x8000;for(var i=0;i<a.length;i+=c)s+=String.fromCharCode.apply(null,a.subarray(i,i+c));return btoa(s);}function bodyStd(b){try{if(b==null)return Promise.resolve('');if(window.Request&&b instanceof Request&&b.clone)return b.clone().arrayBuffer().then(function(a){return stdB64(new Uint8Array(a));});if(typeof b==='string')return Promise.resolve(stdB64(new TextEncoder().encode(b)));if(window.URLSearchParams&&b instanceof URLSearchParams)return Promise.resolve(stdB64(new TextEncoder().encode(b.toString())));if(window.ArrayBuffer&&b instanceof ArrayBuffer)return Promise.resolve(stdB64(new Uint8Array(b)));if(window.ArrayBuffer&&ArrayBuffer.isView&&ArrayBuffer.isView(b))return Promise.resolve(stdB64(new Uint8Array(b.buffer,b.byteOffset,b.byteLength)));if(window.Blob&&b instanceof Blob&&b.arrayBuffer)return b.arrayBuffer().then(function(a){return stdB64(new Uint8Array(a));});return Promise.resolve(stdB64(new TextEncoder().encode(String(b))));}catch(e){return Promise.resolve('');}}function collect(input,init){var o={};try{var h=new Headers(input&&input.headers?input.headers:{});if(init&&init.headers)new Headers(init.headers).forEach(function(v,k){h.set(k,v);});h.forEach(function(v,k){o[k]=v;});}catch(_){}return o;}function defaults(h,b){function has(n){n=String(n).toLowerCase();for(var k in h)if(String(k).toLowerCase()===n)return true;return false;}try{if(!has('origin'))h.origin=baseOrigin();if(!has('referer'))h.referer=pageUrl();if(!has('accept'))h.accept='*/*';if(!has('content-type'))h['content-type']=typeof b==='string'?'text/plain;charset=UTF-8':'application/json';}catch(_){}return h;}function nativeHeaders(h){var o={};for(var k in h){var l=String(k).toLowerCase();if(l==='origin'||l==='referer'||l==='cookie'||l==='host'||l==='connection'||l==='content-length'||l==='accept-encoding')continue;o[k]=h[k];}return o;}async function sign(p,body,h){try{if(p!=='/api/ad/ack')return h;var mod=ackSignerMod(),text=decode64(body||'');if(mod&&mod.X){var s=await mod.X({method:'POST',path:p,scope:scope,bodyText:text});if(s&&s.headers){Object.keys(s.headers).forEach(function(k){h[k]=s.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSigned:true,keyId:String(s.keyId||s.headers['x-ntk-key-id']||'').slice(0,12),nativeHeaderClean:true}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSignError:String(e),nativeHeaderClean:true}));}catch(_){}}return h;}function ackFrom(p,st,j){try{if(p==='/api/ad/ack'&&st===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))markAck();}catch(_){}}function bridge(p,u,body,tag,h){try{var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h||{}),body),o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,body:j,keyHeader:!!(h&&h['x-ntk-key-id']),nativeHeaderClean:true}));}catch(_){}ackFrom(p,o.status||0,j);return{raw:o,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e),nativeHeaderClean:true}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=1;var beacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!should(p))return nativeBeacon?nativeBeacon.apply(this,arguments):false;bodyStd(data).then(function(body){var h=defaults({},data);sign(p,body,h).then(function(){bridge(p,u,body,'beacon',h);});});return true;}catch(e){return nativeBeacon?nativeBeacon.apply(this,arguments):false;}};try{beacon.__ntkNativeString='function sendBeacon() { [native code] }';}catch(_){}navigator.sendBeacon=beacon;if(nativeFetch){var fb=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!should(p))return nativeFetch.apply(this,arguments);var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(m!=='POST')return nativeFetch.apply(this,arguments);var ba=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null),h=defaults(collect(input,init),ba);return bodyStd(ba).then(function(body){return sign(p,body,h).then(function(){if(p==='/api/ad/ack'&&h['x-ntk-key-id']){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchStart:true,path:p,keyHeader:true,nativeHeaderClean:true}));}catch(_){}return nativeFetch(u,{method:'POST',credentials:'include',cache:'no-store',headers:nativeHeaders(h),body:decode64(body||'')}).then(function(resp){return resp.clone().text().then(function(txt){var j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetch:true,path:p,status:resp.status,body:j,keyHeader:true,nativeHeaderClean:true}));}catch(_){}ackFrom(p,resp.status,j);return new Response(txt,{status:resp.status,statusText:resp.statusText,headers:resp.headers});});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchError:true,path:p,error:String(err),keyHeader:true,nativeHeaderClean:true}));}catch(_){}var r=bridge(p,u,body,'fetch-fallback',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}var r=bridge(p,u,body,'fetch',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardFetchBridgeOuterError:String(e),nativeHeaderClean:true}));}catch(_){}return nativeFetch.apply(this,arguments);}};try{fb.__ntkNativeString='function fetch() { [native code] }';}catch(_){}window.fetch=fb;}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,fetch:!!nativeFetch,siteSigner:true,nativeHeaderClean:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),nativeHeaderClean:true}));}catch(_){}}};}catch(e){}")
                + jsChunk("try{installGuardBeaconBridge=function(){try{if(!window.NtkQuicBridge)return;var nf=window.__ntkNativeFetch||window.fetch,nb=window.__ntkNativeBeacon||navigator.sendBeacon;function ok(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function b64(a){var s='',c=32768;for(var i=0;i<a.length;i+=c)s+=String.fromCharCode.apply(null,a.subarray(i,i+c));return btoa(s);}function body64(x){try{if(x==null)return Promise.resolve('');if(window.Request&&x instanceof Request&&x.clone)return x.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof x==='string')return Promise.resolve(b64(new TextEncoder().encode(x)));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(b64(new TextEncoder().encode(String(x))));}catch(e){return Promise.resolve('');}}function addBase(h){h=h||{};h.accept=h.accept||'*/*';h['content-type']=h['content-type']||'application/json';h.origin=h.origin||baseOrigin();h.referer=h.referer||pageUrl();return h;}function clean(h){var o={};for(var k in h){var l=String(k).toLowerCase();if(l==='origin'||l==='referer'||l==='cookie'||l==='host'||l==='connection'||l==='content-length'||l==='accept-encoding')continue;o[k]=h[k];}return o;}async function sign(p,body,h){try{if(p!=='/api/ad/ack')return h;var m=ackSignerMod(),txt=decode64(body||'');if(m&&m.X){var s=await m.X({method:'POST',path:scope,scope:scope,bodyText:txt});if(s&&s.headers){Object.keys(s.headers).forEach(function(k){h[k]=s.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSigned:true,keyId:String(s.keyId||s.headers['x-ntk-key-id']||'').slice(0,12),signedPath:'scope'}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSignError:String(e),signedPath:'scope'}));}catch(_){}}return h;}function mark(p,st,j){try{if(p==='/api/ad/ack'&&st===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))markAck();}catch(_){}}function br(p,u,body,tag,h){var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h),body),o=JSON.parse(raw||'{}'),t=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(t||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,body:j,keyHeader:!!h['x-ntk-key-id'],signedPath:'scope'}));}catch(_){}mark(p,o.status||0,j);return{raw:o,json:j};}navigator.__ntkGuardBeaconBridge=1;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nb?nb.apply(this,arguments):false;body64(data).then(function(body){var h=addBase({});sign(p,body,h).then(function(){br(p,u,body,'beacon',h);});});return true;}catch(e){return nb?nb.apply(this,arguments):false;}};if(nf){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nf.apply(this,arguments);var method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(method!=='POST')return nf.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64(bodyArg).then(function(body){var h=addBase({});return sign(p,body,h).then(function(){if(p==='/api/ad/ack'&&h['x-ntk-key-id']){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchStart:true,path:p,signedPath:'scope'}));}catch(_){}return nf(u,{method:'POST',credentials:'include',cache:'no-store',headers:clean(h),body:decode64(body||'')}).then(function(resp){return resp.clone().text().then(function(txt){var j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetch:true,path:p,status:resp.status,body:j,signedPath:'scope'}));}catch(_){}mark(p,resp.status,j);return new Response(txt,{status:resp.status,statusText:resp.statusText,headers:resp.headers});});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchError:true,path:p,error:String(err),signedPath:'scope'}));}catch(_){}var r=br(p,u,body,'fetch-fallback',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}var r=br(p,u,body,'fetch',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});});}catch(e){return nf.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,signedPath:'scope'}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),signedPath:'scope'}));}catch(_){}}};}catch(e){}")
                + jsChunk("try{(function(){if(window.__ntkStoreReqKey)return;window.__ntkStoreReqKey=async function(k){try{if(!k||!k.keyId||!k.privateKey||!indexedDB)return;window.__ntk_request_key_id=k.keyId;try{localStorage.setItem('ntk-browser-request-key-id',String(k.keyId));if(k.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(k.expiresAt));if(k.privateJwk)localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(k.privateJwk));}catch(_){}await new Promise(function(done){var req=indexedDB.open('ntk-browser-request-key',1);req.onupgradeneeded=function(){try{var db=req.result;if(!db.objectStoreNames.contains('keys'))db.createObjectStore('keys');}catch(_){}};req.onerror=function(){done();};req.onsuccess=function(){var db=req.result;try{var tx=db.transaction('keys','readwrite');tx.objectStore('keys').put({keyId:k.keyId,privateKey:k.privateKey,privateJwk:k.privateJwk||null,expiresAt:k.expiresAt||Date.now()+3600000},'manhwa-v1');tx.oncomplete=function(){try{db.close();}catch(_){}done();};tx.onerror=function(){try{db.close();}catch(_){}done();};}catch(e){try{db.close();}catch(_){}done();}};});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbStore:true,keyId:String(k.keyId).slice(0,12),keyIdFull:String(k.keyId),privateJwk:k.privateJwk||null,expiresAt:k.expiresAt||Date.now()+3600000,scope:scope}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbStoreError:String(e)}));}catch(_){}}};})();}catch(e){}")
                + jsChunk("try{(function(){try{var old=window.__ntkRegisterReqKey;if(!old||old.__ntkIdbWrap)return;async function store(k){try{if(!k||!k.keyId||!k.privateKey||!indexedDB)return;window.__ntk_request_key_id=k.keyId;try{localStorage.setItem('ntk-browser-request-key-id',String(k.keyId));if(k.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(k.expiresAt));if(k.privateJwk)localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(k.privateJwk));}catch(_){}await new Promise(function(done){var req=indexedDB.open('ntk-browser-request-key',1);req.onupgradeneeded=function(){try{var db=req.result;if(!db.objectStoreNames.contains('keys'))db.createObjectStore('keys');}catch(_){}};req.onerror=function(){done();};req.onsuccess=function(){var db=req.result;try{var tx=db.transaction('keys','readwrite');tx.objectStore('keys').put({keyId:k.keyId,privateKey:k.privateKey,privateJwk:k.privateJwk||null,expiresAt:k.expiresAt||Date.now()+3600000},'manhwa-v1');tx.oncomplete=function(){try{db.close();}catch(_){}done();};tx.onerror=function(){try{db.close();}catch(_){}done();};}catch(e){try{db.close();}catch(_){}done();}};});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbStore:true,keyId:String(k.keyId).slice(0,12),keyIdFull:String(k.keyId),privateJwk:k.privateJwk||null,expiresAt:k.expiresAt||Date.now()+3600000,scope:scope}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbStoreError:String(e)}));}catch(_){}}}var wrapped=async function(){var k=await old();await store(k);return k;};wrapped.__ntkIdbWrap=1;window.__ntkRegisterReqKey=wrapped;old().then(store).catch(function(){});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyIdbWrapError:String(e)}));}catch(_){}}})();}catch(e){}")
                + jsChunk("try{installGuardBeaconBridge=function(){try{if(!window.NtkQuicBridge)return;var nf=window.__ntkNativeFetch||window.fetch,nb=window.__ntkNativeBeacon||navigator.sendBeacon;function ok(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function enc(s){return b64(new TextEncoder().encode(String(s||'')));}function body64(x){try{if(x==null)return Promise.resolve('');if(window.Request&&x instanceof Request&&x.clone)return x.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof x==='string')return Promise.resolve(enc(x));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(enc(String(x)));}catch(e){return Promise.resolve('');}}function base(h){h=h||{};h.accept=h.accept||'*/*';h['content-type']=h['content-type']||'application/json';h.origin=h.origin||baseOrigin();h.referer=h.referer||pageUrl();return h;}async function sign(p,body,h){try{if(p!=='/api/ad/ack')return h;var m=ackSignerMod(),txt=decode64(body||'');if(m&&m.X){var s=await m.X({method:'POST',path:scope,scope:scope,bodyText:txt});if(s&&s.headers){Object.keys(s.headers).forEach(function(k){h[k]=s.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSigned:true,keyId:String(s.keyId||s.headers['x-ntk-key-id']||'').slice(0,12),signedPath:'scope',bridgeFirst:true}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSignError:String(e),signedPath:'scope',bridgeFirst:true}));}catch(_){}}return h;}function mark(p,st,j){try{if(p==='/api/ad/ack'&&st===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))markAck();}catch(_){}}function br(p,u,body,tag,h){try{var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h||{}),body),o=JSON.parse(raw||'{}'),t=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(t||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,body:j,keyHeader:!!(h&&h['x-ntk-key-id']),signedPath:'scope',bridgeFirst:true}));}catch(_){}mark(p,o.status||0,j);return{raw:o,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e),bridgeFirst:true}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=1;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nb?nb.apply(this,arguments):false;body64(data).then(function(body){var h=base({});sign(p,body,h).then(function(){br(p,u,body,'beacon',h);});});return true;}catch(e){return nb?nb.apply(this,arguments):false;}};if(nf){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nf.apply(this,arguments);var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(m!=='POST')return nf.apply(this,arguments);var arg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64(arg).then(function(body){var h=base({});return sign(p,body,h).then(function(){var tag=(p==='/api/ad/ack'&&h['x-ntk-key-id'])?'fetch-signed':'fetch';if(tag==='fetch-signed'){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckBridgeFirst:true,path:p,keyId:String(h['x-ntk-key-id']||'').slice(0,12)}));}catch(_){}}var r=br(p,u,body,tag,h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});});}catch(e){return nf.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,signedPath:'scope',bridgeFirst:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),signedPath:'scope',bridgeFirst:true}));}catch(_){}}};}catch(e){}")
                + jsChunk("try{installGuardBeaconBridge=function(){try{if(!window.NtkQuicBridge)return;var nf=window.__ntkNativeFetch||window.fetch,nb=window.__ntkNativeBeacon||navigator.sendBeacon;function ok(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function enc(s){return b64(new TextEncoder().encode(String(s||'')));}function body64(x){try{if(x==null)return Promise.resolve('');if(window.Request&&x instanceof Request&&x.clone)return x.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof x==='string')return Promise.resolve(enc(x));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(enc(String(x)));}catch(e){return Promise.resolve('');}}function add(h){h=h||{};h.accept=h.accept||'*/*';h['content-type']=h['content-type']||'application/json';h.origin=h.origin||baseOrigin();h.referer=h.referer||pageUrl();return h;}function hdr(input,init){var h={};try{new Headers((init&&init.headers)||(input&&input.headers)||{}).forEach(function(v,k){h[k]=v;});}catch(_){}return add(h);}function mark(p,st,j){try{if(p==='/api/ad/ack'&&st===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))markAck();}catch(_){}}function br(p,u,body,tag,h){var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h||{}),body),o=JSON.parse(raw||'{}'),t=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(t||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,body:j,keyHeader:!!(h&&h['x-ntk-key-id']),nativeFirst:true}));}catch(_){}mark(p,o.status||0,j);return{raw:o,json:j};}navigator.__ntkGuardBeaconBridge=2;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nb?nb.apply(this,arguments):false;body64(data).then(function(body){var h=add({});br(p,u,body,'beacon',h);});return true;}catch(e){return nb?nb.apply(this,arguments):false;}};if(nf){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nf.apply(this,arguments);var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(m!=='POST')return nf.apply(this,arguments);var arg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64(arg).then(function(body){var h=hdr(input,init);if(p==='/api/ad/ack'&&h['x-ntk-key-id']){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchStart:true,path:p,nativeFirst:true,keyId:String(h['x-ntk-key-id']).slice(0,12)}));}catch(_){}return nf(u,{method:'POST',credentials:'include',cache:'no-store',headers:h,body:decode64(body||'')}).then(function(resp){return resp.clone().text().then(function(txt){var j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetch:true,path:p,status:resp.status,body:j,nativeFirst:true}));}catch(_){}mark(p,resp.status,j);return new Response(txt,{status:resp.status,statusText:resp.statusText,headers:resp.headers});});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchError:true,path:p,error:String(err),nativeFirst:true}));}catch(_){}var r=br(p,u,body,'fetch-fallback',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}var r=br(p,u,body,'fetch',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}catch(e){return nf.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,nativeFirst:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),nativeFirst:true}));}catch(_){}}};}catch(e){}")
                + jsChunk("try{(function(){if(!window.NtkQuicBridge||window.NtkQuicBridge.__ntkAckCanaryRetry)return;var rawReq=window.NtkQuicBridge.request.bind(window.NtkQuicBridge);window.NtkQuicBridge.__ntkAckCanaryRetry=1;window.NtkQuicBridge.request=function(u,m,h,b){var raw=rawReq(u,m,h,b);try{var p=(new URL(abs(u))).pathname;if(p==='/api/ad/ack'&&String(m||'GET').toUpperCase()==='POST'&&!window.__ntk_ack_canary_retry_busy){var o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}if((o.status||0)===400&&j&&j.error==='missing_canary'){window.__ntk_ack_canary_retry_busy=1;try{var req={};try{req=JSON.parse(decode64(b||'')||'{}');}catch(_){}var token=req.challengeToken||req.token||'',sp=req.path||scope||location.pathname,cyBody=b64(new TextEncoder().encode(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:sp}))),cyHeaders=JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),cy=rawReq(abs('/api/ad/canary'),'POST',cyHeaders,cyBody),cyObj=JSON.parse(cy||'{}'),cyTxt=decode64(cyObj.bodyBase64||''),cyJson={};try{cyJson=JSON.parse(cyTxt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryCanary:true,status:cyObj.status||0,body:cyJson,path:sp}));}catch(_){}raw=rawReq(u,m,h,b);try{var ro=JSON.parse(raw||'{}'),rt=decode64(ro.bodyBase64||''),rj={};try{rj=JSON.parse(rt||'{}');}catch(_){}window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryAfterCanary:true,status:ro.status||0,body:rj,path:sp}));}catch(_){}}finally{window.__ntk_ack_canary_retry_busy=0;}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryCanaryError:String(e)}));}catch(_){}}return raw;};try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckCanaryRetryInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckCanaryRetryInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!window.NtkQuicBridge||window.NtkQuicBridge.__ntkAckChallengeUsedRetry)return;var rawReq=window.NtkQuicBridge.request.bind(window.NtkQuicBridge);window.NtkQuicBridge.__ntkAckChallengeUsedRetry=1;window.NtkQuicBridge.request=function(u,m,h,b){var raw=rawReq(u,m,h,b);try{var p=(new URL(abs(u))).pathname;if(p==='/api/ad/ack'&&String(m||'GET').toUpperCase()==='POST'&&!window.__ntk_ack_challenge_used_retry_busy){var o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}var seen=j&&j.impression?Number(j.impression.seen||0):0;if((o.status||0)===409&&j&&j.error==='challenge_used'&&seen<=0&&typeof directAck==='function'){window.__ntk_ack_challenge_used_retry_busy=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryChallengeUsed:true,seen:seen}));}catch(_){}setTimeout(function(){directAck().then(function(ok){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryChallengeUsedDone:!!ok,acked:acked()}));}catch(_){}}).catch(function(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryChallengeUsedError:String(e)}));}catch(_){}}).then(function(){window.__ntk_ack_challenge_used_retry_busy=0;});},0);}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckRetryChallengeUsedOuterError:String(e)}));}catch(_){}window.__ntk_ack_challenge_used_retry_busy=0;}return raw;};try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckChallengeUsedRetryInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckChallengeUsedRetryInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!window.NtkQuicBridge||window.NtkQuicBridge.__ntkAckProofRequestWrap)return;var rawReq=window.NtkQuicBridge.request.bind(window.NtkQuicBridge);window.NtkQuicBridge.__ntkAckProofRequestWrap=1;window.NtkQuicBridge.request=function(u,m,h,b){var raw=rawReq(u,m,h,b);try{var p=(new URL(abs(u))).pathname;if(p==='/api/ad/ack'&&String(m||'GET').toUpperCase()==='POST'){var o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}if((o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){var rq={};try{rq=JSON.parse(decode64(b||'')||'{}');}catch(_){}if(typeof markProofAck==='function')markProofAck('native-bridge-ack-200',rq.tp||'');else markAck();try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofNativeBridge:true,status:o.status||0,body:j,tpLen:String(rq.tp||'').length}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofNativeBridgeError:String(e)}));}catch(_){}}return raw;};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofNativeBridgeInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofNativeBridgeInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{installGuardBeaconBridge=function(){try{if(!window.NtkQuicBridge)return;var nf=window.__ntkNativeFetch||window.fetch,nb=window.__ntkNativeBeacon||navigator.sendBeacon;function ok(p){return p==='/api/ad/canary'||p==='/api/ad/ack'||p==='/api/m/ev';}function enc(s){return b64(new TextEncoder().encode(String(s||'')));}function body64(x){try{if(x==null)return Promise.resolve('');if(window.Request&&x instanceof Request&&x.clone)return x.clone().arrayBuffer().then(function(a){return b64(new Uint8Array(a));});if(typeof x==='string')return Promise.resolve(enc(x));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return b64(new Uint8Array(a));});return Promise.resolve(enc(String(x)));}catch(e){return Promise.resolve('');}}function base(h){h=h||{};h.accept=h.accept||'*/*';h['content-type']=h['content-type']||'application/json';h.origin=h.origin||baseOrigin();h.referer=h.referer||pageUrl();return h;}function clean(h){var o={};for(var k in h){var l=String(k).toLowerCase();if(l==='origin'||l==='referer'||l==='cookie'||l==='host'||l==='connection'||l==='content-length'||l==='accept-encoding')continue;o[k]=h[k];}return o;}async function sign(p,body,h){try{if(p!=='/api/ad/ack')return h;var m=ackSignerMod(),txt=decode64(body||'');if(m&&m.X){var s=await m.X({method:'POST',path:scope,scope:scope,bodyText:txt});if(s&&s.headers){Object.keys(s.headers).forEach(function(k){h[k]=s.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSigned:true,keyId:String(s.keyId||s.headers['x-ntk-key-id']||'').slice(0,12),signedFinal:true,nativeFirst:true}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeySiteSignError:String(e),signedFinal:true,nativeFirst:true}));}catch(_){}}return h;}function mark(p,st,j,tp,source){try{if(p==='/api/ad/ack'&&st===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){if(typeof markProofAck==='function')markProofAck(source||'guard-fetch-ack-200',tp||'');else markAck();}}catch(_){}}function br(p,u,body,tag,h){try{var raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h||{}),body),o=JSON.parse(raw||'{}'),t=decode64(o.bodyBase64||''),j={};try{j=JSON.parse(t||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridge:tag,path:p,status:o.status||0,body:j,keyHeader:!!(h&&h['x-ntk-key-id']),signedFinal:true,nativeFirst:true}));}catch(_){}mark(p,o.status||0,j,'','guard-fetch-ack-200');return{raw:o,json:j};}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeError:tag,path:p,error:String(e),signedFinal:true,nativeFirst:true}));}catch(_){}return null;}}navigator.__ntkGuardBeaconBridge=4;navigator.sendBeacon=function(url,data){try{var u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nb?nb.apply(this,arguments):false;body64(data).then(function(body){var h=base({});sign(p,body,h).then(function(){br(p,u,body,'beacon',h);});});return true;}catch(e){return nb?nb.apply(this,arguments):false;}};if(nf){window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname;if(!ok(p))return nf.apply(this,arguments);var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(m!=='POST')return nf.apply(this,arguments);var arg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64(arg).then(function(body){var h=base({});return sign(p,body,h).then(function(){if(p==='/api/ad/ack'&&h['x-ntk-key-id']){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchStart:true,path:p,keyId:String(h['x-ntk-key-id']||'').slice(0,12),signedFinal:true,nativeFirst:true}));}catch(_){}return nf(u,{method:'POST',credentials:'include',cache:'no-store',headers:clean(h),body:decode64(body||'')}).then(function(resp){return resp.clone().text().then(function(txt){var j={},rq={};try{j=JSON.parse(txt||'{}');}catch(_){}try{rq=JSON.parse(decode64(body||'')||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetch:true,path:p,status:resp.status,body:j,keyHeader:true,signedFinal:true,nativeFirst:true}));}catch(_){}mark(p,resp.status,j,rq.tp||'','guard-native-signed-fetch-ack-200');return new Response(txt,{status:resp.status,statusText:resp.statusText,headers:resp.headers});});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeSignedFetchError:true,path:p,error:String(err),signedFinal:true,nativeFirst:true}));}catch(_){}var r=br(p,u,body,'fetch-fallback',h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});}var tag=(p==='/api/ad/ack'&&h['x-ntk-key-id'])?'fetch-signed':'fetch';var r=br(p,u,body,tag,h),bytes=bytesFrom64(r&&r.raw?r.raw.bodyBase64||'':'');return new Response(bytes,{status:r&&r.raw&&r.raw.status||200,statusText:r&&r.raw&&r.raw.statusText||'OK',headers:r&&r.raw&&r.raw.headers||{}});});});}catch(e){return nf.apply(this,arguments);}};}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstalled:true,signedFinal:true,nativeFirst:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBeaconBridgeInstallError:String(e),signedFinal:true,nativeFirst:true}));}catch(_){}}};}catch(e){}")
                + jsChunk("try{(function(){var oldInstall=installGuardBeaconBridge;if(!oldInstall||oldInstall.__ntkNativeCanaryFirst)return;installGuardBeaconBridge=function(){var out=oldInstall.apply(this,arguments);try{var bridgeFetch=window.fetch,nf=window.__ntkNativeFetch;if(nf&&bridgeFetch&&!bridgeFetch.__ntkNativeCanaryFirst){var wrapped=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(p==='/api/ad/canary'&&m==='POST'){var bodyText=(init&&typeof init.body==='string')?init.body:'';try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryFetchStart:true,path:p,bodyLen:bodyText.length}));}catch(_){}window.__ntkNativeCanaryPromise=nf(u,{method:'POST',credentials:'include',cache:'no-store',headers:{'content-type':'application/json','accept':'application/json'},body:bodyText}).then(function(resp){return resp.clone().text().then(function(txt){var j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryFetch:true,path:p,status:resp.status,body:j}));}catch(_){}return new Response(txt,{status:resp.status,statusText:resp.statusText,headers:resp.headers});});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryFetchError:true,path:p,error:String(err)}));}catch(_){}return bridgeFetch.apply(this,arguments);});return window.__ntkNativeCanaryPromise;}if(p==='/api/ad/ack'&&m==='POST'&&window.__ntkNativeCanaryPromise){var self=this,args=arguments;return Promise.resolve(window.__ntkNativeCanaryPromise).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryJoinBeforeAck:true,path:p}));}catch(_){}return bridgeFetch.apply(self,args);},function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryJoinBeforeAckError:String(err),path:p}));}catch(_){}return bridgeFetch.apply(self,args);});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryWrapError:String(e)}));}catch(_){}}return bridgeFetch.apply(this,arguments);};try{wrapped.__ntkNativeString='function fetch() { [native code] }';}catch(_){}wrapped.__ntkNativeCanaryFirst=1;window.fetch=wrapped;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryWrapInstalled:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryWrapInstallError:String(e)}));}catch(_){}}return out;};installGuardBeaconBridge.__ntkNativeCanaryFirst=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryInstallWrap:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryInstallWrapError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){var oldInstall=installGuardBeaconBridge;if(!oldInstall||oldInstall.__ntkAckNativeCanaryBefore)return;installGuardBeaconBridge=function(){var out=oldInstall.apply(this,arguments);try{var bridgeFetch=window.fetch,nf=window.__ntkNativeFetch;if(nf&&bridgeFetch&&!bridgeFetch.__ntkAckNativeCanaryBefore){var wrapped=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(url))).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(p==='/api/ad/ack'&&m==='POST'){var bodyText=(init&&typeof init.body==='string')?init.body:'',token='',sp=scope||location.pathname;try{var req=JSON.parse(bodyText||'{}');token=String(req.challengeToken||req.token||'');sp=req.path||sp;}catch(_){}if(token&&window.__ntkNativeCanaryToken!==token){window.__ntkNativeCanaryToken=token;var cyBody=JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:sp});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckStart:true,path:sp,bodyLen:cyBody.length}));}catch(_){}window.__ntkNativeCanaryPromise=nf(abs('/api/ad/canary'),{method:'POST',credentials:'include',cache:'no-store',headers:{'content-type':'application/json','accept':'application/json'},body:cyBody}).then(function(resp){return resp.clone().text().then(function(txt){var j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAck:true,status:resp.status,body:j,path:sp}));}catch(_){}return resp;});}).catch(function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckError:String(err),path:sp}));}catch(_){}return null;});}if(window.__ntkNativeCanaryPromise){var self=this,args=arguments;return Promise.resolve(window.__ntkNativeCanaryPromise).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckJoin:true,path:sp}));}catch(_){}return bridgeFetch.apply(self,args);},function(err){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckJoinError:String(err),path:sp}));}catch(_){}return bridgeFetch.apply(self,args);});}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckWrapError:String(e)}));}catch(_){}}return bridgeFetch.apply(this,arguments);};try{wrapped.__ntkNativeString='function fetch() { [native code] }';}catch(_){}wrapped.__ntkAckNativeCanaryBefore=1;window.fetch=wrapped;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckWrapInstalled:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckWrapInstallError:String(e)}));}catch(_){}}return out;};installGuardBeaconBridge.__ntkAckNativeCanaryBefore=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckInstallWrap:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeCanaryBeforeAckInstallWrapError:String(e)}));}catch(_){}}")
                + "function installGuardImportSpoof(){try{var nativeToString=Function.prototype.__ntkNativeToString||Function.prototype.toString;if(!Function.prototype.__ntkBridgeToString){Object.defineProperty(Function.prototype,'__ntkBridgeToString',{value:1,configurable:true});Object.defineProperty(Function.prototype,'__ntkNativeToString',{value:nativeToString,configurable:true});Function.prototype.toString=function(){try{if(this&&this.__ntkNativeString)return this.__ntkNativeString;}catch(_){}return nativeToString.apply(this,arguments);};}try{Function.prototype.toString.__ntkNativeString='function toString() { [native code] }';}catch(_){}try{if(window.fetch)window.fetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}try{if(navigator.sendBeacon)navigator.sendBeacon.__ntkNativeString='function sendBeacon() { [native code] }';}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardImportSpoof:true,fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80),toStringText:String(Function.prototype.toString.call(Function.prototype.toString)).slice(0,80)}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardImportSpoofError:String(e)}));}catch(_){}}}"
                + "async function loadGuard(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{installGuardImportSpoof();}catch(_){}var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,st=performance.now();var r=await fetch(abs(js),{credentials:'same-origin',cache:'no-store',headers:{'accept':'application/javascript,*/*'}});if(!r||!r.ok)throw new Error('guard-js '+(r?r.status:0));var jt=await r.text(),bu=URL.createObjectURL(new Blob([jt],{type:'application/javascript'})),mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardEvalText:true,version:q,exports:Object.keys(mod).slice(0,16),bytes:jt.length,ms:Math.round(performance.now()-st)}));}catch(_){}if(mod&&mod.default){var it=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardEvalInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardEvalLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}}"
                + "function ensureGuardRows(ch){try{ch=ch||{};var urls=ch.impressionUrls||[],slot=Math.max(4,Number(ch.slotCount||urls.length||4)),root=document.getElementById('__ntk_guard_rows'),host=document.body||document.documentElement,token=String(ch.token||'');if(!root||root.getAttribute('data-ntk-token')!==token){if(root&&root.parentNode)try{root.parentNode.removeChild(root);}catch(_){}root=document.createElement('section');root.id='__ntk_guard_rows';root.className='';root.style.cssText='display:grid;grid-template-columns:repeat(4,78px);gap:8px;position:relative;z-index:1;opacity:1;visibility:visible;pointer-events:auto';root.setAttribute('data-ntk-token',token);root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.className='';b.style.cssText='display:block;width:78px;height:48px;padding:0;margin:0;border:0;background:transparent;opacity:1;visibility:visible;pointer-events:auto';b.setAttribute('aria-label','newtoki62');b.setAttribute('data-bs','1');var img=document.createElement('img');img.width=78;img.height=48;img.alt='';img.loading='eager';img.decoding='sync';img.style.cssText='display:block;width:78px;height:48px;object-fit:cover;opacity:1;visibility:visible';img.src=urls[i]?abs(urls[i]):'data:image/gif;base64,R0lGODlhTgAwAPAAAP///wAAACH5BAAAAAAALAAAAABOADAAAAIshI+py+0Po5y02ouz3rz7D4biSJbmiabqyrbuC8fyrDkr7M13ad73jAUAOw==';b.appendChild(img);root.appendChild(b);}host.insertBefore(root,host.firstChild||null);}var detail=document.getElementById('__ntk_guard_rows_detail');if(detail&&detail.parentNode)try{detail.parentNode.removeChild(detail);}catch(_){}var de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,count:1,slot:slot,imps:urls.length,ab:ab,rb:rb,seen:Number(window.__bSeen||0),minimal:true}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e),minimal:true}));}catch(_){}}}"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckSimpleOverrideDisabled:true}));}catch(_){}"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckGuardOnlyOverrideDisabled:true}));}catch(_){}"
                + "ensureGuardRows=function(ch){try{ch=ch||{};var urls=ch.impressionUrls||[],slot=Math.max(4,Number(ch.slotCount||urls.length||4)),root=document.getElementById('__ntk_guard_rows'),host=document.body||document.documentElement,token=String(ch.token||''),de=document.documentElement,ab=de.getAttribute('data-ab')||'__bSeen',rb=de.getAttribute('data-rb')||ab;if(!root||root.getAttribute('data-ntk-token')!==token){if(root&&root.parentNode)try{root.parentNode.removeChild(root);}catch(_){}root=document.createElement('section');root.id='__ntk_guard_rows';root.className='';root.style.cssText='display:grid;grid-template-columns:repeat(4,78px);gap:8px;width:390px;min-height:76px;padding:14px 0;box-sizing:border-box;margin:0;position:relative;z-index:1;opacity:1;visibility:visible;pointer-events:auto';root.setAttribute('data-ntk-token',token);root.setAttribute('data-br','1');root.setAttribute('data-brs','header');root.setAttribute('data-br-n',String(slot));for(var i=0;i<slot;i++){var b=document.createElement('button');b.type='button';b.className='';b.style.cssText='display:block;width:78px;height:48px;padding:0;margin:0;border:0;background:transparent;opacity:1;visibility:visible;pointer-events:auto';b.setAttribute('aria-label','newtoki62');b.setAttribute('data-bs','1');var img=document.createElement('img');img.width=78;img.height=48;img.alt='';img.loading='eager';img.decoding='sync';img.style.cssText='display:block;width:78px;height:48px;object-fit:cover;opacity:1;visibility:visible';img.src=urls[i]?abs(urls[i]):'data:image/gif;base64,R0lGODlhTgAwAPAAAP///wAAACH5BAAAAAAALAAAAABOADAAAAIshI+py+0Po5y02ouz3rz7D4biSJbmiabqyrbuC8fyrDkr7M13ad73jAUAOw==';b.appendChild(img);root.appendChild(b);}host.insertBefore(root,host.firstChild||null);}var detail=document.getElementById('__ntk_guard_rows_detail');if(detail&&detail.parentNode)try{detail.parentNode.removeChild(detail);}catch(_){}if(!de.getAttribute('data-ab'))de.setAttribute('data-ab',ab);if(!de.getAttribute('data-rb'))de.setAttribute('data-rb',rb);window.__bSeen=Math.max(Number(window.__bSeen||0),slot);window[ab]=Math.max(Number(window[ab]||0),slot);window[rb]=Math.max(Number(window[rb]||0),slot);var rr=root.getBoundingClientRect(),bb=root.querySelector('button'),br=bb?bb.getBoundingClientRect():null;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsReady:true,count:1,slot:slot,imps:urls.length,deferred:false,seen:Number(window.__bSeen||0),ab:ab,rb:rb,rect:{rw:rr?rr.width:0,rh:rr?rr.height:0,bw:br?br.width:0,bh:br?br.height:0},rowShape:'success-log'}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardRowsError:String(e),rowShape:'success-log'}));}catch(_){}}};"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({fireImpAsyncOverrideDisabled:true}));}catch(_){}"
                + jsChunk("try{(function(){if(window.__ntkGuardAckCacheWrap)return;window.__ntkGuardAckCacheWrap=1;window.__ntkAckChallengeByToken=window.__ntkAckChallengeByToken||{};var old=guardAck;guardAck=async function(ch){try{if(ch&&ch.token)window.__ntkAckChallengeByToken[String(ch.token)]=ch;}catch(_){}return old.apply(this,arguments);};})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckCacheWrapError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!window.NtkQuicBridge||window.NtkQuicBridge.__ntkAckPayloadAugment)return;var rawReq=window.NtkQuicBridge.request.bind(window.NtkQuicBridge);window.NtkQuicBridge.__ntkAckPayloadAugment=1;function encText(s){return b64(new TextEncoder().encode(String(s||'')));}function cleanObs(v){try{v=String(v||'');if(v.indexOf('/api/m/i?')<0)return '';if(/[^\\x20-\\x7e]/.test(v))return '';return v;}catch(_){return '';}}function augment(u,m,h,b){try{var p=(new URL(abs(u))).pathname;if(p!=='/api/ad/ack'||String(m||'GET').toUpperCase()!=='POST')return b;var req={};try{req=JSON.parse(decode64(b||'')||'{}');}catch(_){return b;}var token=String(req.challengeToken||req.token||''),ch=token&&window.__ntkAckChallengeByToken?window.__ntkAckChallengeByToken[token]:null,changed=false,chObs=[];if(ch&&ch.impressionUrls&&ch.impressionUrls.length){for(var ci=0;ci<ch.impressionUrls.length;ci++){var co=cleanObs(ch.impressionUrls[ci]);if(co)chObs.push(co);}}if(req.tp&&window.__ntk_request_key_id&&!req.requestKeyId){req.requestKeyId=window.__ntk_request_key_id;changed=true;}if(req.observationUrls&&req.observationUrls.length){var filtered=[];for(var oi=0;oi<req.observationUrls.length;oi++){var ov=cleanObs(req.observationUrls[oi]);if(ov)filtered.push(ov);}if(chObs.length>filtered.length)filtered=chObs;if(filtered.length!==req.observationUrls.length||chObs.length>req.observationUrls.length){if(filtered.length)req.observationUrls=filtered;else delete req.observationUrls;changed=true;}}if(req.tp&&chObs.length&&!req.observationUrls){req.observationUrls=chObs;changed=true;}if(!changed)return b;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackPayloadAugmented:true,requestKeyId:!!req.requestKeyId,observationUrls:req.observationUrls?req.observationUrls.length:0,tpLen:String(req.tp||'').length}));}catch(_){}return encText(JSON.stringify(req));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackPayloadAugmentError:String(e)}));}catch(_){}return b;}}window.NtkQuicBridge.request=function(u,m,h,b){return rawReq(u,m,h,augment(u,m,h,b));};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackPayloadAugmentInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackPayloadAugmentInstallError:String(e)}));}catch(_){}}")
                + buildAckOnlyBrowserKeyRetryScript()
                + "loadGuard=async function(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeApisRestoredBeforeDirectUrl:true,fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80),toStringText:String(Function.prototype.toString.call(Function.prototype.toString)).slice(0,80)}));}catch(_){ }var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,st=performance.now(),mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectUrlImport:true,version:q,exports:Object.keys(mod).slice(0,16),ms:Math.round(performance.now()-st)}));}catch(_){}if(mod&&mod.default){var it=performance.now();await mod.default({module_or_path:abs(wasm)});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectUrlInit:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectUrlLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}};"
                + "loadGuard=async function(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;}catch(_){}var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='',st=performance.now();if(window.NtkQuicBridge){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()});var jr=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h,'')||'{}');var wr=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h,'')||'{}');if((jr.status||0)===200&&(wr.status||0)===200){var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),jt=new TextDecoder().decode(jb),bu=URL.createObjectURL(new Blob([jt],{type:'application/javascript'}));wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleShell:true,jsBytes:jb.length,wasmBytes:wb.length,version:q,ms:Math.round(performance.now()-st),exports:Object.keys(mod).slice(0,16)}));}catch(_){}if(mod&&mod.default){var it=performance.now();try{await mod.default({module_or_path:wasmUrl});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeInitShell:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}}catch(be){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleShellError:String(be),version:q}));}catch(_){}}}if(!mod){var ds=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectUrlFallbackShell:true,version:q,exports:Object.keys(mod).slice(0,16),ms:Math.round(performance.now()-ds)}));}catch(_){}if(mod&&mod.default)await mod.default({module_or_path:abs(wasm)});}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardShellLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}};"
                + "loadGuard=async function(){try{if(window.__ntkGuardModule)return window.__ntkGuardModule;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;}catch(_){}var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,mod=null,wasmUrl='',st=performance.now();if(window.NtkQuicBridge&&window.NtkQuicBridge.requestGetBatch){try{var h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()}),br=JSON.parse(window.NtkQuicBridge.requestGetBatch(JSON.stringify([abs(js),abs(wasm)]),h)||'{}'),rs=br.results||[],jr=rs[0]||{},wr=rs[1]||{};if((jr.status||0)===200&&(wr.status||0)===200&&jr.bodyBase64&&wr.bodyBase64){var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),jt=new TextDecoder().decode(jb),bu=URL.createObjectURL(new Blob([jt],{type:'application/javascript'}));wasmUrl=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleBatch:true,jsBytes:jb.length,wasmBytes:wb.length,version:q,ms:Math.round(performance.now()-st),exports:Object.keys(mod).slice(0,16)}));}catch(_){}if(mod&&mod.default){var it=performance.now();try{await mod.default({module_or_path:wasmUrl});}finally{if(wasmUrl)try{URL.revokeObjectURL(wasmUrl);}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeInitBatch:true,loaded:mod.__i5?!!mod.__i5():null,ms:Math.round(performance.now()-it)}));}catch(_){}}}}catch(batchErr){mod=null;wasmUrl='';try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleBatchError:String(batchErr),version:q,ms:Math.round(performance.now()-st),discarded:true}));}catch(_){}}}if(!mod&&window.NtkQuicBridge){try{var h2=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()}),jr2=JSON.parse(window.NtkQuicBridge.request(abs(js),'GET',h2,'')||'{}'),wr2=JSON.parse(window.NtkQuicBridge.request(abs(wasm),'GET',h2,'')||'{}');if((jr2.status||0)===200&&(wr2.status||0)===200){var jb2=bytesFrom64(jr2.bodyBase64||''),wb2=bytesFrom64(wr2.bodyBase64||''),jt2=new TextDecoder().decode(jb2),bu2=URL.createObjectURL(new Blob([jt2],{type:'application/javascript'}));wasmUrl=URL.createObjectURL(new Blob([wb2],{type:'application/wasm'}));mod=await import(bu2);try{URL.revokeObjectURL(bu2);}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleShell:true,jsBytes:jb2.length,wasmBytes:wb2.length,version:q,ms:Math.round(performance.now()-st),fallback:true,exports:Object.keys(mod).slice(0,16)}));}catch(_){}if(mod&&mod.default)await mod.default({module_or_path:wasmUrl});}}catch(be){mod=null;wasmUrl='';try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeModuleShellError:String(be),version:q,discarded:true}));}catch(_){}}}if(!mod){var ds=performance.now();mod=await import(abs(js));try{window.NtkViewerBridge.onAckState(JSON.stringify({guardDirectUrlFallbackShell:true,version:q,exports:Object.keys(mod).slice(0,16),ms:Math.round(performance.now()-ds)}));}catch(_){}if(mod&&mod.default)await mod.default({module_or_path:abs(wasm)});}window.__ntkGuardModule=mod;return mod;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardShellLoadError:String(e),version:guardVersion()}));}catch(_){}return null;}};"
                + jsChunk("try{(function(){try{if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}var oldLoad=loadGuard;if(oldLoad&&!oldLoad.__ntkSpoofWrap){loadGuard=async function(){try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}var r=await oldLoad.apply(this,arguments);try{if(window.__ntkNativeFetch){window.__ntkNativeFetch.__ntkNativeString='function fetch() { [native code] }';window.fetch=window.__ntkNativeFetch;}if(window.__ntkNativeBeacon){window.__ntkNativeBeacon.__ntkNativeString='function sendBeacon() { [native code] }';navigator.sendBeacon=window.__ntkNativeBeacon;}if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();window.NtkViewerBridge.onAckState(JSON.stringify({guardLoadSpoofWrap:true,fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80),beaconText:String(Function.prototype.toString.call(navigator.sendBeacon)).slice(0,80),toStringText:String(Function.prototype.toString.call(Function.prototype.toString)).slice(0,80)}));}catch(_){}return r;};loadGuard.__ntkSpoofWrap=1;}var oldBridge=installGuardBeaconBridge;if(oldBridge&&!oldBridge.__ntkSpoofWrap){installGuardBeaconBridge=function(){try{if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}var r=oldBridge.apply(this,arguments);try{if(window.fetch)window.fetch.__ntkNativeString='function fetch() { [native code] }';if(navigator.sendBeacon)navigator.sendBeacon.__ntkNativeString='function sendBeacon() { [native code] }';if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSpoofWrap:true,fetchText:String(Function.prototype.toString.call(window.fetch)).slice(0,80),beaconText:String(Function.prototype.toString.call(navigator.sendBeacon)).slice(0,80)}));}catch(_){}return r;};installGuardBeaconBridge.__ntkSpoofWrap=1;}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardSpoofWrapError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){var oldBridge=installGuardBeaconBridge;if(!oldBridge||oldBridge.__ntkProofFetchWrap)return;installGuardBeaconBridge=function(){var r=oldBridge.apply(this,arguments);try{var nativeFetch=window.fetch;if(nativeFetch&&!nativeFetch.__ntkAckProofFetchWrap){var proofFetch=function(input,init){var self=this,args=arguments,p='',method='GET',bodyText='';try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';p=(new URL(abs(url))).pathname;method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();var body=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:null;if(typeof body==='string')bodyText=body;}catch(_){}var pr=nativeFetch.apply(self,args);try{if(p==='/api/ad/ack'&&method==='POST'&&pr&&pr.then){return pr.then(function(resp){try{if(resp&&resp.status===200){resp.clone().text().then(function(txt){try{var j={};try{j=JSON.parse(txt||'{}');}catch(_){}if(j.ok||j.acked||j.status==='ok'||j.status==='acked'){var rq={};try{rq=JSON.parse(bodyText||'{}');}catch(_){}if(typeof markProofAck==='function')markProofAck('guard-fetch-ack-200',rq.tp||'');else markAck();try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardFetch:true,status:resp.status,body:j,tpLen:String(rq.tp||'').length}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardFetchParseError:String(e)}));}catch(_){}}});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardFetchError:String(e)}));}catch(_){}}return resp;});}}catch(_){}return pr;};try{proofFetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}proofFetch.__ntkAckProofFetchWrap=1;window.fetch=proofFetch;try{if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardFetchInstalled:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardFetchInstallError:String(e)}));}catch(_){}}return r;};installGuardBeaconBridge.__ntkProofFetchWrap=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardBridgeWrapInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofGuardBridgeWrapError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){guardProof=async function(token){try{var mod=await loadGuard();if(!mod||!token){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofMissing:true,hasModule:!!mod,tokenLen:String(token||'').length}));}catch(_){}return '';}var args=[token,JSON.stringify({token:token,path:scope}),scope],fns=[];if(mod._vc)fns.push(['_vc',mod._vc.bind(mod)]);if(mod._hk)fns.push(['_hk',mod._hk.bind(mod)]);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofStart:true,args:args.length,fns:fns.map(function(x){return x[0];}),tokenLen:String(token||'').length}));}catch(_){}for(var fi=0;fi<fns.length;fi++){for(var ai=0;ai<args.length;ai++){try{var name=fns[fi][0],fn=fns[fi][1],st=performance.now();try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofCall:name,arg:ai,argLen:String(args[ai]||'').length}));}catch(_){}var v=fn(args[ai],scope);if(v&&v.then)v=await Promise.race([v,sleep(650).then(function(){return {__ntkTimeout:true};})]);if(v&&v.__ntkTimeout){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTimeout:name,arg:ai,ms:Math.round(performance.now()-st)}));}catch(_){}continue;}v=String(v||'');try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTry:ai,fn:name,len:v.length,value:v.slice(0,32),ms:Math.round(performance.now()-st)}));}catch(_){}if(v&&v!=='true'&&v!=='false')return v;}catch(inner){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofTryError:ai,fn:fns[fi]&&fns[fi][0],error:String(inner)}));}catch(_){}}}}return '';}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofError:String(e)}));}catch(_){}return '';}};directAckProofFirst=async function(){try{try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstStart:true,scope:scope}));}catch(_){}var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstChallenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge)}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var guardOk=await Promise.race([guardAck(ch),sleep(3800).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstGuardTimeout:true}));}catch(_){}return false;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstGuardDone:true,guardOk:!!guardOk,acked:acked()}));}catch(_){}if(guardOk)return true;var token=ch.token||'',seen=await Promise.race([fireGuardImpressions(ch),sleep(1600).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstImpTimeout:true}));}catch(_){}return 0;})]),tp=await guardProof(token);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstProofDone:true,tpLen:String(tp||'').length,seen:seen,acked:acked()}));}catch(_){}if(!tp||tp==='true'||tp==='false')return false;var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstSubmit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen,bounded:true}));}catch(_){}var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{},bounded:true}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstResponse:true,status:a&&a.status||0,body:b,bounded:true}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstError:String(e),bounded:true}));}catch(_){}}return false;};})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardProofOverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(true)return;var slowGuardAck=guardAck;guardAck=async function(ch){if(!ackOnly&&slowGuardAck)return slowGuardAck.apply(this,arguments);try{var mod=await loadGuard();if(!mod||!ch)return false;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;}catch(_){}ensureGuardRows(ch);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastRows:true,count:document.querySelectorAll('[data-br=\"1\"][data-br-n]').length}));}catch(_){}var token=String(ch&&ch.token||''),canSkipImageWait=ackOnly&&window.__ntkPreGuardCanaryOk&&window.__ntkPreGuardCanaryOkToken===token;if(ackOnly&&window.NtkQuicBridge&&token&&window.__ntkPreGuardCanaryToken!==token){window.__ntkPreGuardCanaryToken=token;try{var cyBody=b64(new TextEncoder().encode(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope}))),cyRaw=window.NtkQuicBridge.request(abs('/api/ad/canary'),'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),cyBody),cy=JSON.parse(cyRaw||'{}'),cyTxt=decode64(cy.bodyBase64||''),cyJson={};try{cyJson=JSON.parse(cyTxt||'{}');}catch(_){}if((cy.status||0)===200&&cyJson&&cyJson.ok){window.__ntkPreGuardCanaryOk=1;window.__ntkPreGuardCanaryOkToken=token;canSkipImageWait=true;}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastPreImageCanary:true,status:cy.status||0,ok:!!(cyJson&&cyJson.ok)}));}catch(_){}}catch(cyErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastPreImageCanaryError:String(cyErr)}));}catch(_){}}}var imageNeed=Math.max(1,Number((ch&&ch.minSeen)||1)),loadedBefore=guardLoadedCount(),skipImageWait=canSkipImageWait&&loadedBefore>=imageNeed;var imageReady=skipImageWait?true:await waitGuardImages(imageNeed,1400);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastImages:true,ready:imageReady,loaded:guardLoadedCount(),total:guardImages().length,skipWait:skipImageWait,reason:skipImageWait?'pre_guard_ok':'wait',need:imageNeed,loadedBefore:loadedBefore}));}catch(_){}try{var row=document.getElementById('__ntk_guard_rows')||document.querySelector('[data-br=\"1\"][data-br-n]');if(row&&row.scrollIntoView)row.scrollIntoView({block:'start',inline:'nearest'});window.dispatchEvent(new Event('scroll'));window.dispatchEvent(new Event('resize'));await Promise.race([new Promise(function(r){try{requestAnimationFrame(function(){requestAnimationFrame(r);});}catch(_){r();}}),sleep(80)]);}catch(_){}try{guardGlobalState('fast-before-i4',mod);}catch(_){}var fn=mod.__i4||mod.i4||mod._i4||mod.guardAck||mod.adAck;if(!fn)return false;try{installGuardBeaconBridge();}catch(_){}if(window.NtkQuicBridge&&!(window.__ntkPreGuardCanaryOk&&window.__ntkPreGuardCanaryOkToken===String(ch.token||''))){try{var cyBody=b64(new TextEncoder().encode(JSON.stringify({adGuardLoaded:true,adAckCanary:true,challengeToken:ch.token||'',token:ch.token||'',path:scope})));window.NtkQuicBridge.request(abs('/api/ad/canary'),'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),cyBody);}catch(_){}}else if(window.NtkQuicBridge&&window.__ntkPreGuardCanaryOk){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastCanarySkipped:true,reason:'pre_guard_ok'}));}catch(_){}}var arg=JSON.stringify(ch),before=mod.__i5?!!mod.__i5():null,r=fn(arg,scope);if(r&&r.then)r=await r;try{installGuardBeaconBridge();}catch(_){}var after=mod.__i5?!!mod.__i5():null;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastTry:true,argLen:String(arg).length,before:before,after:after,result:String(r||'').slice(0,80),acked:acked(),waitMs:1500}));}catch(_){}if(await waitAck(1500))return true;return acked();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardAckFastError:String(e)}));}catch(_){}return false;}};directAckProofFirst=async function(){try{try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastStart:true,scope:scope}));}catch(_){}var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastChallenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge)}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var guardOk=await Promise.race([guardAck(ch),sleep(3600).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastGuardTimeout:true,ms:3600}));}catch(_){}return false;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastGuardDone:true,guardOk:!!guardOk,acked:acked()}));}catch(_){}if(guardOk)return true;var token=ch.token||'',tp=await guardProof(token);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastProofDone:true,tpLen:String(tp||'').length,acked:acked(),skipImpBeforeProof:true}));}catch(_){}if(!tp||tp==='true'||tp==='false')return false;var seen=await Promise.race([fireGuardImpressions(ch),sleep(1000).then(function(){return 0;})]),minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastSubmit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen}));}catch(_){}var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastResponse:true,status:a&&a.status||0,body:b}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFastError:String(e)}));}catch(_){}}return false;};})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardFastOverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(true)return;directAckProofFirst=async function(){try{try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Start:true,scope:scope}));}catch(_){}var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Challenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge)}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var guardOk=await Promise.race([guardAck(ch),sleep(2600).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2GuardTimeout:true}));}catch(_){}return false;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2GuardDone:true,guardOk:!!guardOk,acked:acked()}));}catch(_){}if(guardOk)return true;var token=ch.token||'',seen=await Promise.race([fireGuardImpressions(ch),sleep(1300).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2ImpTimeout:true}));}catch(_){}return 0;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2ImpDone:true,seen:seen,acked:acked()}));}catch(_){}var tp=await guardProof(token);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2ProofDone:true,tpLen:String(tp||'').length,seen:seen,acked:acked()}));}catch(_){}if(!tp||tp==='true'||tp==='false')return false;var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Submit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen}));}catch(_){}var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Canary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Response:true,status:a&&a.status||0,body:b}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2Error:String(e)}));}catch(_){}}return false;};})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast2OverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(false)return;directAckProofFirst=async function(){try{try{window.__ntkDirectAckStartedAt=Date.now();window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Start:true,scope:scope,guardFirst:true,proofParallel:true}));}catch(_){}var c=null;try{var skipNative=ackOnly||!!window.__ntkForceFreshAckChallenge;if(skipNative){var skipReason=window.__ntkForceFreshAckChallenge?'missing_canary':'fresh_first';window.__ntkForceFreshAckChallenge=0;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3NativeChallengeSkipped:true,reason:skipReason}));}catch(_){}}var nt=!skipNative&&window.NtkViewerBridge&&window.NtkViewerBridge.getNativeAckChallenge?String(window.NtkViewerBridge.getNativeAckChallenge(scope)||''):'';if(nt){var nj=JSON.parse(nt||'{}');if(nj&&nj.ok){c={status:200,body:nj,text:nt,transport:'native-challenge-cache'};window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3NativeChallenge:true,ok:!!nj.ok,hasChallenge:!!nj.challenge,bytes:nt.length,scope:scope}));}}}catch(nce){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3NativeChallengeError:String(nce)}));}catch(_){}}if(!c)c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Challenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge),transport:c&&c.transport||''}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var token=String(ch.token||'');try{ensureGuardRows(ch);}catch(_){}var proofTask=guardProof(token);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3GuardFirstStart:true,minSeen:ch&&ch.minSeen,slots:ch&&ch.slotCount,timeoutMs:2200,proofParallel:true}));}catch(_){}var seenTask=fireGuardImpressions(ch),guardOk=await Promise.race([guardAck(ch),sleep(2200).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3GuardFirstTimeout:true,timeoutMs:2200}));}catch(_){}return false;})]),seen=await Promise.race([seenTask,sleep(900).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3ImpTimeout:true}));}catch(_){}return 0;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3GuardFirstDone:true,guardOk:!!guardOk,seen:seen,acked:acked()}));}catch(_){}if(guardOk||acked())return true;var tp=await Promise.race([proofTask,sleep(1200).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3ProofTimeout:true,parallel:true}));}catch(_){}return '';})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3ProofDone:true,tpLen:String(tp||'').length,seen:seen,acked:acked(),parallel:true}));}catch(_){}if(!tp||tp==='true'||tp==='false'){tp=await Promise.race([guardProof(token),sleep(900).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3PostGuardProofTimeout:true}));}catch(_){}return '';})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3PostGuardProofDone:true,tpLen:String(tp||'').length,seen:seen,acked:acked()}));}catch(_){}if(!tp||tp==='true'||tp==='false')return acked();}var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Submit:true,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen,minSeen:minSeen}));}catch(_){}var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Canary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Response:true,status:a&&a.status||0,body:b}));}catch(_){}if(a&&a.status===400&&b&&b.error==='missing_canary'){window.__ntkForceFreshAckChallenge=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3MissingCanaryFresh:true}));}catch(_){}return false;}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Error:String(e)}));}catch(_){}}return false;};directAck=directAckProofFirst;window.__ntkDirectAckStable=directAck;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3Enabled:true,ackOnly:true,rebound:true,guardFirst:true,proofParallel:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast3OverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{if(ackOnly){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckStableRestoreSkipped:true,ackOnly:true,reason:'keep-fast-ack'}));}catch(_){}}else{if(window.__ntkGuardAckStable)guardAck=window.__ntkGuardAckStable;if(window.__ntkDirectAckStable){directAck=window.__ntkDirectAckStable;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckStableRestored:true,ackOnly:false}));}catch(_){}}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckStableRestoreError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){var oldGuardAck=guardAck;if(!ackOnly||!oldGuardAck||oldGuardAck.__ntkBrowserKeyBeforeGuard)return;guardAck=async function(ch){try{if(!window.__ntk_request_key_id){var ok=false,fn=window.__ntkEnsureBrowserKeyIdb||(typeof ensureBrowserKeyIdb==='function'?ensureBrowserKeyIdb:null);if(fn){var keyTask=Promise.resolve().then(function(){return fn();});if(ackOnly){ok=await Promise.race([keyTask,sleep(1700).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckEnsureTimeout:true,ackOnly:ackOnly,ms:1700}));}catch(_){}return false;})]);keyTask.then(function(r){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckLateEnsure:!!r,keyId:String(window.__ntk_request_key_id||'').slice(0,12),ackOnly:ackOnly}));}catch(_){}}).catch(function(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckLateEnsureError:String(e),ackOnly:ackOnly}));}catch(_){}});}else ok=await keyTask;}try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckEnsure:!!ok,keyId:String(window.__ntk_request_key_id||'').slice(0,12),ackOnly:ackOnly,bounded:ackOnly,ms:ackOnly?1700:null}));}catch(_){}}else{try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckPresent:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),ackOnly:ackOnly}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckEnsureError:String(e),ackOnly:ackOnly}));}catch(_){}}return oldGuardAck.apply(this,arguments);};guardAck.__ntkBrowserKeyBeforeGuard=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckInstalled:true,bounded:ackOnly,ms:1700}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyBeforeGuardAckInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(true)return;directAckProofFirst=async function(){try{try{window.__ntkDirectAckStartedAt=Date.now();window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Start:true,scope:scope,ordered:true}));}catch(_){}var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Challenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge),transport:c&&c.transport||''}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var token=String(ch.token||'');try{ensureGuardRows(ch);}catch(_){}var seen=await Promise.race([fireGuardImpressions(ch),sleep(1800).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4ImpTimeout:true}));}catch(_){}return 0;})]);var tp=await Promise.race([guardProof(token),sleep(2200).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4ProofTimeout:true}));}catch(_){}return '';})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Ready:true,tpLen:String(tp||'').length,seen:seen,acked:acked()}));}catch(_){}if(acked())return true;if(!tp||tp==='true'||tp==='false')return false;var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope,total:total,visible:visible});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Canary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(_){}await sleep(180);for(var attempt=0;attempt<2;attempt++){var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Response:true,status:a&&a.status||0,body:b,attempt:attempt,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markAck();return true;}if(a&&a.status===400&&b&&b.error==='missing_canary'){await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope,total:total,visible:visible});await sleep(220);continue;}if(!(a&&((a.status===0)||(a.status===400)||(a.status===428))))break;await sleep(260+attempt*260);}return acked();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Error:String(e)}));}catch(_){}}return false;};directAck=directAckProofFirst;window.__ntkDirectAckStable=directAck;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4Enabled:true,ackOnly:true,ordered:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast4OverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){var nf=window.__ntkNativeFetch;if(!ackOnly||!nf||nf.__ntkMissingCanaryFastRetry)return;var wrapped=function(input,init){var self=this,args=arguments,p='',m='GET';try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'';p=(new URL(abs(url))).pathname;m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();}catch(_){}var pr=nf.apply(self,args);try{if(p==='/api/ad/ack'&&m==='POST'&&pr&&pr.then){return pr.then(function(resp){try{if(resp&&resp.status===400){resp.clone().text().then(function(txt){try{var j={};try{j=JSON.parse(txt||'{}');}catch(_){}if(j&&j.error==='missing_canary'&&!window.__ntkMissingCanaryFastRetryBusy&&typeof directAck==='function'){window.__ntkMissingCanaryFastRetryBusy=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetry:true,path:p}));}catch(_){}setTimeout(function(){try{directAck().then(function(ok){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryDone:!!ok,acked:acked()}));}catch(_){}},function(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryError:String(e)}));}catch(_){}}).then(function(){window.__ntkMissingCanaryFastRetryBusy=0;});}catch(e){window.__ntkMissingCanaryFastRetryBusy=0;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryThrow:String(e)}));}catch(_){}}},0);}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryParseError:String(e)}));}catch(_){}}});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryOuterError:String(e)}));}catch(_){}}return resp;});}}catch(_){}return pr;};try{wrapped.__ntkNativeString='function fetch() { [native code] }';}catch(_){}wrapped.__ntkMissingCanaryFastRetry=1;window.__ntkNativeFetch=wrapped;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardNativeMissingCanaryFastRetryInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){var oldInstall=installGuardBeaconBridge;if(!ackOnly||!oldInstall||oldInstall.__ntkBridgeSignedAckPreferred)return;installGuardBeaconBridge=function(){var out=oldInstall.apply(this,arguments);try{var bridgeFetch=window.fetch;if(window.NtkQuicBridge&&bridgeFetch&&!bridgeFetch.__ntkBridgeSignedAckPreferred){var wrapped=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',u=abs(url),p=(new URL(u)).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(p==='/api/ad/ack'&&m==='POST'){var self=this,args=arguments,bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){return Promise.resolve().then(async function(){var h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl()},bodyText=decode64(body||''),bodyToken='',bodyProof='';try{var bodyObj=JSON.parse(bodyText||'{}');bodyToken=String(bodyObj.challengeToken||bodyObj.token||'');bodyProof=String(bodyObj.tp||'');if(!bodyToken||!bodyProof||bodyProof==='true'||bodyProof==='false'){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredBypassInvalid:true,hasChallenge:!!bodyToken,hasProof:!!bodyProof,bodyLen:String(bodyText||'').length}));}catch(_){}return bridgeFetch.apply(self,args);}if(bodyToken&&window.__ntkAckConsumedToken===bodyToken){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredSkipConsumed:true,token:bodyToken.slice(0,16)}));}catch(_){}return new Response(JSON.stringify({ok:false,error:'challenge_used_local'}),{status:409,statusText:'Conflict',headers:{'content-type':'application/json'}});}}catch(_){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredBypassParse:true,bodyLen:String(bodyText||'').length}));}catch(_){}return bridgeFetch.apply(self,args);}try{var ih=new Headers((init&&init.headers)||(input&&input.headers)||{});ih.forEach(function(v,k){var lk=String(k||'').toLowerCase();if(lk!=='cookie'&&lk!=='host'&&lk!=='content-length')h[k]=v;});}catch(_){}try{if(!window.__ntk_request_key_id){var keyFn=window.__ntkEnsureBrowserKeyIdb||(typeof ensureBrowserKeyIdb==='function'?ensureBrowserKeyIdb:null);if(keyFn){var keyOk=await Promise.race([Promise.resolve().then(function(){return keyFn(true,true);}),sleep(1600).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckKeyRetryTimeout:true,ms:1600}));}catch(_){}return false;})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckKeyRetry:!!keyOk,keyId:String(window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}}}}catch(ke){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckKeyRetryError:String(ke)}));}catch(_){}}try{if(window.NtkQuicBridge&&(window.NtkQuicBridge.signViewerRequestFormat||window.NtkQuicBridge.signViewerRequest)){var kid='',kr=null;try{if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(pageUrl(),String(navigator.userAgent||''))||'{}'));else if(window.NtkQuicBridge.ensureViewerBrowserKey)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKey(pageUrl())||'{}'));if(kr&&kr.keyId){kid=String(kr.keyId);window.__ntk_request_key_id=kid;}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeNativeKeyEnsure:!!(kr&&kr.ok),status:kr&&kr.status||0,keyId:String(kid||'').slice(0,12),cached:!!(kr&&kr.cached)}));}catch(_){}}catch(kerr){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeNativeKeyEnsureError:String(kerr).slice(0,160)}));}catch(_){}}if(!kid)kid=String(window.__ntk_request_key_id||'');if(kid){try{var bo=JSON.parse(bodyText||'{}');if(bo.requestKeyId!==kid){bo.requestKeyId=kid;bodyText=JSON.stringify(bo);body=b64(new TextEncoder().encode(bodyText));}}catch(_){}}var nsRaw=window.NtkQuicBridge.signViewerRequestFormat?window.NtkQuicBridge.signViewerRequestFormat('POST',scope,scope,bodyText,'p1363'):window.NtkQuicBridge.signViewerRequest('POST',scope,scope,bodyText),ns=JSON.parse(String(nsRaw||'{}'));if(ns&&ns.ok&&ns.headers){Object.keys(ns.headers).forEach(function(k){h[k]=ns.headers[k];});try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeNativeSignedAck:!!h['x-ntk-key-id'],keyId:String(ns.keyId||h['x-ntk-key-id']||kid||'').slice(0,12),bodyRequestKey:bodyText.indexOf('requestKeyId')>=0,signedPath:'scope'}));}catch(_){}}else{try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeNativeSignMiss:true,ok:!!(ns&&ns.ok),error:ns&&ns.error?String(ns.error).slice(0,160):''}));}catch(_){}}}}catch(nse){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeNativeSignError:String(nse).slice(0,160)}));}catch(_){}}try{var sm=ackSignerMod();if(sm&&sm.X&&!h['x-ntk-key-id']){var sig=await sm.X({method:'POST',path:scope,scope:scope,bodyText:bodyText});if(sig&&sig.headers)Object.keys(sig.headers).forEach(function(k){h[k]=sig.headers[k];});}}catch(se){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckSignError:String(se)}));}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredStart:true,keyId:String(h['x-ntk-key-id']||window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}var o={},txt='',j={},raw='',attempt=0;for(attempt=0;attempt<3;attempt++){if(attempt>0)await sleep(260+attempt*260);raw=window.NtkQuicBridge.request(u,'POST',JSON.stringify(h),body);o=JSON.parse(raw||'{}');txt=decode64(o.bodyBase64||'');j={};try{j=JSON.parse(txt||'{}');}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferred:true,status:o.status||0,error:o.error||'',body:j,keyHeader:!!h['x-ntk-key-id'],requestKeyId:!!window.__ntk_request_key_id,attempt:attempt}));}catch(_){}if((o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked'))break;if((o.status||0)===409&&j&&j.error==='challenge_used'){if(bodyToken)window.__ntkAckConsumedToken=bodyToken;break;}if((o.status||0)===400&&j&&j.error==='missing_canary'){window.__ntkForceFreshAckChallenge=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredFreshChallenge:true,attempt:attempt}));}catch(_){}break;}if(!((o.status||0)===0||(o.status||0)===400||(o.status||0)===428))break;}try{if((o.status||0)===200&&(j.ok||j.acked||j.status==='ok'||j.status==='acked')){var rq={};try{rq=JSON.parse(bodyText||'{}');}catch(_){}if(typeof markProofAck==='function')markProofAck('guard-fetch-ack-200',rq.tp||'');else markAck();}}catch(_){}return new Response(bytesFrom64(o.bodyBase64||''),{status:o.status||200,statusText:o.statusText||'OK',headers:o.headers||{}});});});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredError:String(e)}));}catch(_){}}return bridgeFetch.apply(this,arguments);};try{wrapped.__ntkNativeString='function fetch() { [native code] }';}catch(_){}wrapped.__ntkBridgeSignedAckPreferred=1;window.fetch=wrapped;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredInstalled:true,oneShot409Stop:true,invalidBodyBypass:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredInstallError:String(e)}));}catch(_){}}return out;};oldInstall.__ntkBridgeSignedAckPreferred=1;installGuardBeaconBridge.__ntkBridgeSignedAckPreferred=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredInstallWrap:true,oneShot409Stop:true,invalidBodyBypass:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBridgeSignedAckPreferredInstallWrapError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckInvalidBodyBypass)return;var baseFetch=window.fetch,nativeFetch=window.__ntkNativeFetch;window.__ntkAckInvalidBodyBypass=1;if(!baseFetch)return;window.fetch=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(url))).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(p==='/api/ad/ack'&&m==='POST'){var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){try{var txt=decode64(body||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}var hasChallenge=!!(j.challengeToken||j.token),hasProof=!!j.tp;if(!hasChallenge||!hasProof){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypass:true,hasChallenge:hasChallenge,hasProof:hasProof,bodyLen:String(txt||'').length,nativeFetch:!!nativeFetch}));}catch(_){}return (nativeFetch||baseFetch).apply(this,[input,init]);}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassParseError:String(e)}));}catch(_){}return (nativeFetch||baseFetch).apply(this,[input,init]);}return baseFetch.apply(this,[input,init]);}.bind(this));}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassError:String(e)}));}catch(_){}}return baseFetch.apply(this,arguments);};try{window.fetch.__ntkNativeString='function fetch() { [native code] }';window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckInvalidBodyBypassInstallWrap)return;window.__ntkAckInvalidBodyBypassInstallWrap=1;function install(tag){try{var baseFetch=window.fetch,nativeFetch=window.__ntkNativeFetch;if(!baseFetch||baseFetch.__ntkAckInvalidBodyBypassWrapped)return false;var wrapped=function(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(url))).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(p==='/api/ad/ack'&&m==='POST'){var self=this,args=arguments,bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);return body64Async(bodyArg).then(function(body){try{var txt=decode64(body||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}var hasChallenge=!!(j.challengeToken||j.token),hasProof=!!j.tp;if(!hasChallenge||!hasProof){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypass:true,tag:tag||'',hasChallenge:hasChallenge,hasProof:hasProof,bodyLen:String(txt||'').length,nativeFetch:!!nativeFetch}));}catch(_){}return (nativeFetch||baseFetch).apply(self,args);}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassParseError:String(e),tag:tag||''}));}catch(_){}return (nativeFetch||baseFetch).apply(self,args);}return baseFetch.apply(self,args);});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassError:String(e),tag:tag||''}));}catch(_){}}return baseFetch.apply(this,arguments);};try{wrapped.__ntkNativeString='function fetch() { [native code] }';wrapped.__ntkAckInvalidBodyBypassWrapped=1;}catch(_){}window.fetch=wrapped;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstalled:true,tag:tag||''}));}catch(_){}return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstallError:String(e),tag:tag||''}));}catch(_){}return false;}}install('initial-wrap');try{var oldInstall=installGuardBeaconBridge;if(oldInstall&&!oldInstall.__ntkAckInvalidBodyBypassInstallWrap){installGuardBeaconBridge=function(){var out=oldInstall.apply(this,arguments);try{install('after-guard-install');}catch(_){}return out;};installGuardBeaconBridge.__ntkAckInvalidBodyBypassInstallWrap=1;oldInstall.__ntkAckInvalidBodyBypassInstallWrap=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstallWrap:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstallWrapError:String(e)}));}catch(_){}}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackInvalidBodyBypassInstallWrapOuterError:String(e)}));}catch(_){}}")
                + buildAckOnlyBrowserKeyFetchRetryScript()
                + jsChunk("try{(function(){var prevLoad=loadGuard;if(!prevLoad||prevLoad.__ntkBoundedLoad)return;loadGuard=async function(){function note(o){try{window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(_){}}function loaded(m){try{return !m||!m.__i5?!!m:!!m.__i5();}catch(_){return false;}}try{if(window.__ntkGuardModule&&loaded(window.__ntkGuardModule))return window.__ntkGuardModule;if(window.__ntkGuardBoundedInitPromise){var pending=await Promise.race([window.__ntkGuardBoundedInitPromise,sleep(2800).then(function(){note({guardBoundedPendingTimeout:true});return null;})]);if(pending&&loaded(pending))return pending;return null;}if(!window.NtkQuicBridge||!window.NtkQuicBridge.requestGetBatch){var pm=await Promise.race([prevLoad.apply(this,arguments),sleep(2600).then(function(){note({guardBoundedPrevLoadTimeout:true});return null;})]);if(pm&&loaded(pm)){window.__ntkGuardModule=pm;return pm;}return null;}try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()}),st=performance.now(),br=JSON.parse(window.NtkQuicBridge.requestGetBatch(JSON.stringify([abs(js),abs(wasm)]),h)||'{}'),rs=br.results||[],jr=rs[0]||{},wr=rs[1]||{};if((jr.status||0)!==200||(wr.status||0)!==200||!jr.bodyBase64||!wr.bodyBase64){note({guardBoundedLoadBadStatus:true,jsStatus:jr.status||0,wasmStatus:wr.status||0,version:q});var fm=await Promise.race([prevLoad.apply(this,arguments),sleep(2600).then(function(){note({guardBoundedFallbackTimeout:true});return null;})]);if(fm&&loaded(fm)){window.__ntkGuardModule=fm;return fm;}return null;}var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),bu=URL.createObjectURL(new Blob([new TextDecoder().decode(jb)],{type:'application/javascript'})),wu=URL.createObjectURL(new Blob([wb],{type:'application/wasm'})),mod=await import(bu);try{URL.revokeObjectURL(bu);}catch(_){}note({guardBoundedModule:true,jsBytes:jb.length,wasmBytes:wb.length,version:q,ms:Math.round(performance.now()-st),exports:Object.keys(mod||{}).slice(0,16)});if(!mod)return null;if(!mod.default){window.__ntkGuardModule=mod;return mod;}window.__ntkGuardBoundedInitPromise=(async function(){var it=performance.now();try{await mod.default({module_or_path:wu});var ok=loaded(mod);note({guardBoundedInitDone:true,loaded:ok,ms:Math.round(performance.now()-it)});if(ok)window.__ntkGuardModule=mod;return ok?mod:null;}catch(e){note({guardBoundedInitError:String(e),ms:Math.round(performance.now()-it)});return null;}finally{try{URL.revokeObjectURL(wu);}catch(_){}window.__ntkGuardBoundedInitPromise=null;}})();var ready=await Promise.race([window.__ntkGuardBoundedInitPromise,sleep(2800).then(function(){note({guardBoundedInitTimeout:true,ms:2800});return null;})]);if(ready&&loaded(ready))return ready;return null;}catch(e){note({guardBoundedLoadError:String(e)});return null;}};loadGuard.__ntkBoundedLoad=1;})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({guardBoundedLoadInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckOnlyDirectGuardLoader)return;window.__ntkAckOnlyDirectGuardLoader=1;var oldLoad=loadGuard;loadGuard=async function(){function note(o){try{window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(_){}}function loaded(m){try{return !m||!m.__i5?!!m:!!m.__i5();}catch(_){return false;}}try{if(window.__ntkGuardModule&&loaded(window.__ntkGuardModule))return window.__ntkGuardModule;if(window.__ntkAckOnlyDirectGuardPromise){var pr=await Promise.race([window.__ntkAckOnlyDirectGuardPromise,sleep(3600).then(function(){note({ackOnlyDirectGuardPendingTimeout:true});return null;})]);if(pr&&loaded(pr))return pr;return null;}window.__ntkAckOnlyDirectGuardPromise=(async function(){var st=performance.now();try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}try{var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js=abs('/api/ad/guard-js'+q),wasm=abs('/api/ad/guard-wasm'+q),mod=await import(js);note({ackOnlyDirectGuardModule:true,version:q,ms:Math.round(performance.now()-st),exports:Object.keys(mod||{}).slice(0,16)});if(mod&&mod.default){var it=performance.now();await mod.default({module_or_path:wasm});note({ackOnlyDirectGuardInit:true,loaded:loaded(mod),ms:Math.round(performance.now()-it)});}if(mod&&loaded(mod)){window.__ntkGuardModule=mod;return mod;}return null;}catch(e){note({ackOnlyDirectGuardError:String(e),ms:Math.round(performance.now()-st)});try{var fb=await Promise.race([oldLoad.apply(this,arguments),sleep(2400).then(function(){note({ackOnlyDirectGuardFallbackTimeout:true});return null;})]);if(fb&&loaded(fb)){window.__ntkGuardModule=fb;return fb;}}catch(fe){note({ackOnlyDirectGuardFallbackError:String(fe)});}return null;}finally{window.__ntkAckOnlyDirectGuardPromise=null;}}).call(this);var ready=await Promise.race([window.__ntkAckOnlyDirectGuardPromise,sleep(3600).then(function(){note({ackOnlyDirectGuardTimeout:true,ms:3600});return null;})]);if(ready&&loaded(ready))return ready;return null;}catch(e){note({ackOnlyDirectGuardOuterError:String(e)});return null;}};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDirectGuardLoaderInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDirectGuardLoaderInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckOnlySyncGuardLoader)return;window.__ntkAckOnlySyncGuardLoader=1;var oldLoad=loadGuard;loadGuard=async function(){function note(o){try{window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(_){}}function loaded(m){try{return !m||!m.__i5?!!m:!!m.__i5();}catch(_){return false;}}try{if(window.__ntkGuardModule&&loaded(window.__ntkGuardModule))return window.__ntkGuardModule;if(window.__ntkAckOnlySyncGuardPromise){var pr=await Promise.race([window.__ntkAckOnlySyncGuardPromise,sleep(45000).then(function(){note({ackOnlySyncGuardPendingTimeout:true,ms:45000});return null;})]);if(pr&&loaded(pr))return pr;return null;}if(!window.NtkQuicBridge||!window.NtkQuicBridge.requestGetBatch)return oldLoad.apply(this,arguments);window.__ntkAckOnlySyncGuardPromise=(async function(){var st=performance.now(),bu=null,wu=null;try{if(window.__ntkNativeFetch)window.fetch=window.__ntkNativeFetch;if(window.__ntkNativeBeacon)navigator.sendBeacon=window.__ntkNativeBeacon;if(typeof installGuardImportSpoof==='function')installGuardImportSpoof();}catch(_){}try{var v=guardVersion(),q=v?'?v='+encodeURIComponent(v):'',js='/api/ad/guard-js'+q,wasm='/api/ad/guard-wasm'+q,h=JSON.stringify({'accept':'application/javascript,application/wasm,*/*','origin':baseOrigin(),'referer':pageUrl()}),br=JSON.parse(window.NtkQuicBridge.requestGetBatch(JSON.stringify([abs(js),abs(wasm)]),h)||'{}'),rs=br.results||[],jr=rs[0]||{},wr=rs[1]||{};if((jr.status||0)!==200||(wr.status||0)!==200||!jr.bodyBase64||!wr.bodyBase64){note({ackOnlySyncGuardBadStatus:true,jsStatus:jr.status||0,wasmStatus:wr.status||0,version:q});try{var sf=await Promise.race([oldLoad.apply(this,arguments),sleep(22000).then(function(){note({ackOnlySyncGuardBadStatusFallbackTimeout:true,ms:22000});return null;})]);if(sf&&loaded(sf)){window.__ntkGuardModule=sf;return sf;}}catch(sfe){note({ackOnlySyncGuardBadStatusFallbackError:String(sfe)});}return null;}var jb=bytesFrom64(jr.bodyBase64||''),wb=bytesFrom64(wr.bodyBase64||''),raw=wb&&wb.length>4&&wb[0]===0&&wb[1]===97&&wb[2]===115&&wb[3]===109;bu=URL.createObjectURL(new Blob([new TextDecoder().decode(jb)],{type:'application/javascript'}));var mod=await import(bu);try{URL.revokeObjectURL(bu);bu=null;}catch(_){}note({ackOnlySyncGuardModule:true,version:q,jsBytes:jb.length,wasmBytes:wb.length,rawWasm:raw,ms:Math.round(performance.now()-st),exports:Object.keys(mod||{}).slice(0,16)});if(mod&&raw&&mod.initSync){var it=performance.now();mod.initSync(wb.buffer.slice(wb.byteOffset,wb.byteOffset+wb.byteLength));note({ackOnlySyncGuardInitSync:true,loaded:loaded(mod),ms:Math.round(performance.now()-it)});}else if(mod&&mod.default){var dt=performance.now();wu=URL.createObjectURL(new Blob([wb],{type:'application/wasm'}));await mod.default({module_or_path:wu});note({ackOnlySyncGuardDefaultInit:true,loaded:loaded(mod),ms:Math.round(performance.now()-dt)});}if(mod&&loaded(mod)){window.__ntkGuardModule=mod;try{if(ackOnly&&!ackProofed()){note({ackOnlySyncGuardModuleReadyRetry:true});setTimeout(function(){try{var f=(typeof directAck==='function')?directAck:(window.__ntkDirectAckStable||null);if(window.__ntkAckOnlyRunning===scope&&!ackProofed()&&typeof f==='function')f();}catch(re){note({ackOnlySyncGuardModuleReadyRetryError:String(re)});}},0);}}catch(_){}return mod;}return null;}catch(e){note({ackOnlySyncGuardError:String(e),ms:Math.round(performance.now()-st)});try{var fb=await Promise.race([oldLoad.apply(this,arguments),sleep(22000).then(function(){note({ackOnlySyncGuardFallbackTimeout:true,ms:22000});return null;})]);if(fb&&loaded(fb)){window.__ntkGuardModule=fb;return fb;}}catch(fe){note({ackOnlySyncGuardFallbackError:String(fe)});}return null;}finally{try{if(bu)URL.revokeObjectURL(bu);}catch(_){}try{if(wu)URL.revokeObjectURL(wu);}catch(_){}window.__ntkAckOnlySyncGuardPromise=null;}}).call(this);var ready=await Promise.race([window.__ntkAckOnlySyncGuardPromise,sleep(45000).then(function(){note({ackOnlySyncGuardTimeout:true,ms:45000});return null;})]);if(ready&&loaded(ready))return ready;return null;}catch(e){note({ackOnlySyncGuardOuterError:String(e)});return null;}};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlySyncGuardLoaderInstalled:true,preloadDisabled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlySyncGuardLoaderInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckOnlyEarlyProofRunner)return;window.__ntkAckOnlyEarlyProofRunner=1;async function run(){var started=Date.now(),attempt=0;try{try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofRunnerStart:true,scope:scope,href:String(location.href||'').slice(0,160)}));}catch(_){}var deadline=Date.now()+65000;while(!ackProofed()&&!window.__ntkAckOnlyHardBlock&&Date.now()<deadline){attempt++;var fn=(typeof directAck==='function')?directAck:((window.__ntkDirectAckStable&&typeof window.__ntkDirectAckStable==='function')?window.__ntkDirectAckStable:((typeof directAckProofFirst==='function')?directAckProofFirst:null));try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofLoop:attempt,hasFn:!!fn,proofed:ackProofed(),hardBlock:!!window.__ntkAckOnlyHardBlock}));}catch(_){}if(!fn)break;try{await Promise.race([Promise.resolve().then(function(){return fn();}),sleep(Math.min(14000,Math.max(0,deadline-Date.now()))).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofDirectTimeout:true,attempt:attempt}));}catch(_){}return false;})]);}catch(callErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofCallError:String(callErr),attempt:attempt}));}catch(_){}}if(!ackProofed()&&!window.__ntkAckOnlyHardBlock)await waitProof(Math.min(1800,Math.max(0,deadline-Date.now())));if(!ackProofed()&&!window.__ntkAckOnlyHardBlock)await sleep(Math.min(360,Math.max(0,deadline-Date.now())));}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofRunnerExit:true,ok:ackProofed(),attempts:attempt,ms:Date.now()-started,hardBlock:!!window.__ntkAckOnlyHardBlock}));}catch(_){}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofRunnerError:String(e)}));}catch(_){}}}run();})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyEarlyProofRunnerInstallError:String(e)}));}catch(_){}}if(ackOnly)return null;")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckProofStrictInstalled)return;window.__ntkAckProofStrictInstalled=1;var softAcked=acked;acked=function(){return ackProofed();};var rawBridgeReq=bridgeReq;bridgeReq=async function(url,method,body,extra){var r=await rawBridgeReq.apply(this,arguments);try{var p=(new URL(abs(url))).pathname,b=r&&r.body?r.body:{},m=String(method||'GET').toUpperCase();if(p==='/api/ad/ack'&&m==='POST'&&(r.status||0)===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markProofAck('direct-bridge-ack-200',body&&body.tp||'');window.NtkViewerBridge.onAckState(JSON.stringify({ackProofStrictBridgeAck:true,status:r.status,body:b,softAcked:softAcked()}));}}catch(_){}return r;};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofStrictInstalled:true,softAcked:softAcked(),proofed:ackProofed()}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackProofStrictInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkStrictDirectAckReturnGate)return;window.__ntkStrictDirectAckReturnGate=1;var rawDirectAck=(typeof directAck==='function')?directAck:(window.__ntkDirectAckStable||null);if(typeof rawDirectAck!=='function')return;directAck=async function(){var result=false;try{result=await rawDirectAck.apply(this,arguments);}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({strictDirectAckReturnGateError:String(e)}));}catch(_){}return false;}var proofed=false;try{proofed=typeof ackProofed==='function'&&ackProofed();}catch(_){}try{window.NtkViewerBridge.onAckState(JSON.stringify({strictDirectAckReturnGate:true,result:!!result,proofed:!!proofed}));}catch(_){}return !!proofed;};window.__ntkDirectAckStable=directAck;try{window.NtkViewerBridge.onAckState(JSON.stringify({strictDirectAckReturnGateInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({strictDirectAckReturnGateInstallError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(true)return;directAckProofFirst=async function(){try{try{window.__ntkDirectAckStartedAt=Date.now();window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5Start:true,scope:scope,guardAckFirst:false,proofFirst:true}));}catch(_){}var c=await bridgeReq('/api/ad/challenge','POST',{path:scope});try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5Challenge:true,status:c&&c.status||0,ok:!!(c&&c.body&&c.body.ok),hasChallenge:!!(c&&c.body&&c.body.challenge),transport:c&&c.transport||''}));}catch(_){}if(!c||c.status!==200||!c.body||!c.body.ok)return false;if(c.body.trusted&&!c.body.challenge){markAck();return true;}var ch=c.body.challenge;if(!ch)return false;var token=String(ch.token||'');try{ensureGuardRows(ch);}catch(_){}try{var cy=await bridgeReq('/api/ad/canary','POST',{adGuardLoaded:true,adAckCanary:true,challengeToken:token,token:token,path:scope});window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5PreCanary:true,status:cy&&cy.status||0,body:cy&&cy.body||{}}));}catch(cyErr){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5PreCanaryError:String(cyErr)}));}catch(_){}}try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5GuardSkipped:true,reason:'proof-first'}));}catch(_){}var seen=await Promise.race([fireGuardImpressions(ch),sleep(1200).then(function(){return 0;})]);var tp=await Promise.race([guardProof(token),sleep(1800).then(function(){return '';})]);try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5FallbackProof:true,tpLen:String(tp||'').length,seen:seen,acked:acked(),proofed:ackProofed()}));}catch(_){}if(!tp||tp==='true'||tp==='false')return false;var minSeen=Math.max(1,Number(ch.minSeen||2)),total=Math.max(Number(ch.slotCount||4),28),visible=Math.max(minSeen,Math.min(total,seen||minSeen));var a=await bridgeReq('/api/ad/ack','POST',{challengeToken:token,total:total,visible:visible,path:scope,td:0,tp:tp});var b=a&&a.body?a.body:{};try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5Response:true,status:a&&a.status||0,body:b,tpLen:String(tp||'').length,total:total,visible:visible,seen:seen}));}catch(_){}if(a&&a.status===200&&(b.ok||b.acked||b.status==='ok'||b.status==='acked')){markProofAck('fast5-bridge-ack-200',tp);return true;}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5Error:String(e)}));}catch(_){}}return false;};directAck=directAckProofFirst;window.__ntkDirectAckStable=directAck;try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5Enabled:true,ackOnly:true,guardAckFirst:false,proofFirst:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckProofFirstFast5OverrideError:String(e)}));}catch(_){}}")
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckOnlyIbSentinelWrap)return;window.__ntkAckOnlyIbSentinelWrap=1;var old=guardAck;if(!old)return;guardAck=async function(ch){try{var txt=document.body?String(document.body.innerText||document.body.textContent||''):'',has=txt.indexOf('init-html-sentinel')>=0||txt.indexOf('__ntk_ib_ok')>=0||!!document.getElementById('init-html-sentinel');if(has){if(window.__ntk_ib_ok===undefined)window.__ntk_ib_ok=1;if(window.__ntk_ib_loaded===undefined)window.__ntk_ib_loaded=1;if(window.__ntk_hs_ok===undefined)window.__ntk_hs_ok=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyIbSentinelPromoted:true,ibOk:String(window.__ntk_ib_ok),ibLoaded:String(window.__ntk_ib_loaded),hsOk:!!window.__ntk_hs_ok,bodyHasSentinel:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyIbSentinelPromoteError:String(e)}));}catch(_){}}return old.apply(this,arguments);};})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyIbSentinelWrapError:String(e)}));}catch(_){}}")
                + "function extractAckState(){try{var ls={},ss={},i,k;for(i=0;i<localStorage.length;i++){k=localStorage.key(i);if(k)ls[k]=localStorage.getItem(k);}for(i=0;i<sessionStorage.length;i++){k=sessionStorage.key(i);if(k)ss[k]=sessionStorage.getItem(k);}return JSON.stringify({cookies:docCookie(),local:ls,session:ss,ackScope:(function(){try{return window.__ntk_ad_ack_scope;}catch(e){return null;}})(),ntkVars:(function(){try{var o={};for(var p in window)if(p.indexOf('__ntk')===0)try{o[p]=String(window[p]);}catch(e){}return o;}catch(e){return{};}})(),ua:navigator.userAgent,ts:Date.now()});}catch(e){return JSON.stringify({error:String(e)});}}"
                + "function extractKeyState(label){try{function names(s){var a=[],i,k,low;try{for(i=0;i<s.length;i++){k=s.key(i);low=String(k||'').toLowerCase();if(low.indexOf('browser')>=0||low.indexOf('key')>=0||low.indexOf('ack')>=0||low.indexOf('guard')>=0||low.indexOf('ntk')>=0||low.indexOf('ad')>=0)a.push(String(k).slice(0,64));}}catch(_){}return a.slice(0,24);}var ck=docCookie(),w=[],p;for(p in window){try{var lp=String(p||'').toLowerCase();if(lp.indexOf('browser')>=0||lp.indexOf('key')>=0||lp.indexOf('__ntk')===0||lp.indexOf('ack')>=0)w.push(String(p).slice(0,64));}catch(_){}}window.NtkViewerBridge.onAckState(JSON.stringify({keyState:label,href:String(location.href||'').slice(0,160),ready:document.readyState,cookieLen:ck.length,cookieHasAdAck:/ad_ack=/.test(ck),cookieHasAdAckC:/ad_ack_c=/.test(ck),local:names(localStorage),session:names(sessionStorage),win:w.slice(0,24),acked:acked(),proofed:ackProofed(),ts:Date.now()}));}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({keyStateError:label,error:String(e)}));}catch(_){}}}"
                + jsChunk("try{(function(){if(!ackOnly||window.__ntkAckOnlyDomImagesProofGate)return;window.__ntkAckOnlyDomImagesProofGate=1;var oldDomImages=domImages;domImages=function(){try{var imgs=oldDomImages?oldDomImages():[];if(!ackProofed()){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomPreAckIgnored:true,count:imgs&&imgs.length||0,proofed:false}));}catch(_){}return [];}return imgs;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomImagesProofGateError:String(e)}));}catch(_){}return [];}};try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomImagesProofGateInstalled:true}));}catch(_){}})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyDomImagesProofGateInstallError:String(e)}));}catch(_){}}")
                + ";(async function(){try{try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyMainEntry:true,ackOnly:ackOnly,href:String(location.href||'').slice(0,160),running:String(window.__ntkAckOnlyRunning||''),age:Date.now()-Number(window.__ntkAckOnlyRunningAt||0)}));}catch(_){}for(var i=0;i<4;i++){if(document.body)break;await sleep(50);}if(!document.body){try{document.documentElement.appendChild(document.createElement('body'));}catch(_){}}if(ackOnly){for(var di=0;di<3;di++){var early=domImages();if(early.length){send({code:200,body:{ok:true,ackOnly:true,images:early,source:'ack-only-dom-pre-ack'}});return;}await sleep(180+di*140);}}var deadline=Date.now()+(ackOnly?65000:72000),armed=false,attempt=0;if(!ackOnly){try{extractKeyState('start');}catch(_){}}else{try{window.NtkViewerBridge.onAckState(JSON.stringify({keyStateSkipped:'start',ackOnly:true,reason:'ack-loop-first'}));}catch(_){}}if(ackOnly){try{var __keyFn=window.__ntkEnsureBrowserKeyIdb||(typeof ensureBrowserKeyIdb==='function'?ensureBrowserKeyIdb:null);if(__keyFn&&!window.__ntk_request_key_id&&!window.__ntkBrowserKeyTask){window.__ntkBrowserKeyTask=Promise.resolve().then(function(){return __keyFn(false,true);});window.__ntkBrowserKeyTask.then(function(r){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyEarlyDone:!!r,keyId:String(window.__ntk_request_key_id||'').slice(0,12),nativeChallenge:true}));}catch(_){}}).catch(function(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyEarlyError:String(e),nativeChallenge:true}));}catch(_){}});window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyEarlyStarted:true,nativeChallenge:true}));}else{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyEarlySkipped:true,nativeChallenge:true,hasKey:!!window.__ntk_request_key_id,hasTask:!!window.__ntkBrowserKeyTask,hasFn:!!__keyFn}));}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyEarlyStartError:String(e),nativeChallenge:true}));}catch(_){}}while(!ackProofed()&&!acked()&&!window.__ntkAckOnlyHardBlock&&Date.now()<deadline){attempt++;try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyLoop:attempt,beforeDirect:true,fnFast4:!!(typeof directAckProofFirst==='function'),stable:!!window.__ntkDirectAckStable}));}catch(_){}if(ackProofed()||acked()||window.__ntkAckOnlyHardBlock)break;var __ackFn=(typeof directAck==='function')?directAck:((window.__ntkDirectAckStable&&typeof window.__ntkDirectAckStable==='function')?window.__ntkDirectAckStable:((typeof directAckProofFirst==='function')?directAckProofFirst:null));armed=await Promise.race([Promise.resolve().then(function(){return __ackFn();}),sleep(Math.min(14000,Math.max(0,deadline-Date.now()))).then(function(){try{window.NtkViewerBridge.onAckState(JSON.stringify({directAckTimeout:true,attempt:attempt}));}catch(_){}return false;})]);if(!ackOnly){try{extractKeyState('after-directAck-'+attempt);}catch(_){}}if(window.__ntkAckOnlyHardBlock)break;if(!ackProofed()&&!acked()&&!armed)rearm();if(!ackProofed()&&!acked())await waitProof(Math.min(2200,Math.max(0,deadline-Date.now())));if(!ackProofed()&&!acked())await sleep(Math.min(450,Math.max(0,deadline-Date.now())));}if(!ackOnly){try{extractKeyState('ack-only-final');}catch(_){}}var ok=ackProofed()||acked();if(ackOnly&&ok){for(var da=0;da<8;da++){var imgs=domImages();if(imgs.length){send({code:200,body:{ok:true,ackOnly:true,proofed:ackProofed(),acked:acked(),images:imgs,source:'ack-only-dom-after-ack',attempts:attempt}});return;}await sleep(260+da*180);}}try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyMainExit:true,ok:ok,proofed:ackProofed(),acked:acked(),attempts:attempt,hardBlock:!!window.__ntkAckOnlyHardBlock}));}catch(_){}send({code:ok?200:0,body:{ok:ok,ackOnly:true,proofed:ackProofed(),acked:acked(),hardBlock:!!window.__ntkAckOnlyHardBlock,attempts:attempt}});return;}if(!acked()){rearm();}var r=await api();while(Date.now()<deadline&&r&&(r.status===403||r.status===428)){try{window.NtkViewerBridge.onAckState(extractAckState());extractKeyState('api-'+r.status);}catch(e){}await waitAck(Math.min(1800,Math.max(0,deadline-Date.now())));await sleep(180);r=await api();}send({code:r?r.status:0,body:r?r.body:{error:'timeout'}});}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({ackOnlyMainError:String(e),ackOnly:ackOnly}));}catch(_){}send({code:0,error:String(e)});}})();"
                + "})()";
    }

    private static String buildViewerImageApiOnlyScript(String baseUrl, String path, String endpoint,
                                                        String workId, String episodeId,
                                                        String imagesToken, boolean serverAckProof) {
        return "(function(){var base=" + jsonQuote(baseUrl) + ",scope=" + jsonQuote(path)
                + ",workId=" + jsonQuote(workId) + ",episodeId=" + jsonQuote(episodeId)
                + ",token=" + jsonQuote(imagesToken) + ",endpoint=" + jsonQuote(endpoint)
                + ",serverAckProof=" + (serverAckProof ? "true" : "false") + ";"
                + "var apiScope=(endpoint.indexOf('/webtoon-')>=0?'/webtoon/':'/manhwa/')+workId+'/'+episodeId;"
                + "if(window.__ntkViewerImageFetchLock===scope){var a=Date.now()-Number(window.__ntkViewerImageFetchLockAt||0);if(a<=1200)return;}window.__ntkViewerImageFetchLock=scope;window.__ntkViewerImageFetchLockAt=Date.now();"
                + "var sent=false;function log(o){try{window.NtkViewerBridge.onAckState(JSON.stringify(o));}catch(_){}}function send(o){if(sent)return;sent=true;try{delete window.__ntkViewerImageFetchLock;}catch(_){}try{window.NtkViewerBridge.onViewerImages(JSON.stringify(o));}catch(e){}}"
                + "function abs(u){return new URL(u,base).href;}function pageUrl(){return abs(scope);}function baseOrigin(){return new URL(base).origin;}function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}"
                + "function seedAckProof(s){try{if(!serverAckProof||!s)return;window.__ntk_ad_ack_scope=s;window.__ntk_ad_ack_proof_200=s;window.__ntk_ad_ack_last={scope:s,ts:Date.now(),native:true,proof200:true,source:'native-server-proof'};window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready',{detail:{scope:s,native:true,proof200:true,source:'native-server-proof'}}));log({viewerImagesSeedAckProof:true,scope:s,apiScope:apiScope,ackScope:scope});}catch(e){log({viewerImagesSeedAckProofError:String(e),scope:s});}}seedAckProof(scope);if(apiScope!==scope)seedAckProof(apiScope);"
                + "function stdB64(a){var s='',c=0x8000;for(var i=0;i<a.length;i+=c){var sub=a.subarray(i,i+c);s+=String.fromCharCode.apply(null,sub);}return btoa(s);}function urlB64(a){return stdB64(a).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');}"
                + "function d64(b){try{return decodeURIComponent(escape(atob(b||'')));}catch(e){try{return atob(b||'');}catch(_){return '';}}}function bytes(b){try{var s=atob(b||''),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i)&255;return a;}catch(e){return new Uint8Array(0);}}"
                + "function webpackReq(){try{var req=null;(self.webpackChunk_N_E=self.webpackChunk_N_E||[]).push([[Math.floor(Math.random()*1e9)],{},function(r){req=r;}]);return req;}catch(e){log({viewerImagesWebpackReqError:String(e)});return null;}}"
                + "function safeMod(req,id){try{return req&&req(id);}catch(e){log({viewerImagesWebpackModError:String(e),id:id});return null;}}"
                + "function findSigner(req){try{var m=safeMod(req,47760);if(m&&(m.X||m.D))return m;var c=req&&req.c?req.c:{},ks=Object.keys(c);for(var i=0;i<ks.length;i++){var ex=c[ks[i]]&&c[ks[i]].exports;if(ex&&(ex.X||ex.D)){log({viewerImagesSignerFound:true,id:ks[i],hasX:!!ex.X,hasD:!!ex.D,source:'cache'});return ex;}}var defs=req&&req.m?req.m:{},ds=Object.keys(defs),cands=[];for(var j=0;j<ds.length;j++){try{var src=String(defs[ds[j]]||'');if(src.indexOf('x-ntk-key-id')>=0||src.indexOf('/api/client-key/register')>=0||(src.indexOf('bodyText')>=0&&src.indexOf('crypto')>=0))cands.push(ds[j]);}catch(_){}}for(var k=0;k<cands.length;k++){try{var id=cands[k],ex2=safeMod(req,id);if(ex2&&(ex2.X||ex2.D)){log({viewerImagesSignerFound:true,id:String(id),hasX:!!ex2.X,hasD:!!ex2.D,source:'defs'});return ex2;}}catch(loadErr){log({viewerImagesSignerLoadError:true,id:String(cands[k]),error:String(loadErr).slice(0,120)});}}log({viewerImagesSignerMissing:true,cacheModules:ks.length,defModules:ds.length,candidates:cands.slice(0,12),reqKeys:req?Object.keys(req).slice(0,16):[]});}catch(e){log({viewerImagesFindSignerError:String(e)});}return null;}"
                + "function ck(n){try{var m=String(document.cookie||'').match(new RegExp('(?:^|;\\\\s*)'+n+'=([^;]*)'));if(m)return decodeURIComponent(m[1]);}catch(_){}try{return window.NtkQuicBridge?String(window.NtkQuicBridge.cookie(pageUrl(),n)||''):'';}catch(_){return '';}}"
                + "function domImages(){try{var out=[],seen={},raw=0,rejected=0;function absUrl(v){try{v=String(v||'').trim();if(!v)return'';return new URL(v,location.href).href;}catch(_){return String(v||'');}}function addCandidate(el,v){raw++;var src=absUrl(v);if(!src||seen[src])return;var l=src.toLowerCase(),p='';try{p=(new URL(src)).pathname.toLowerCase();}catch(_){p=l;}if(/^data:|^blob:/i.test(src)||/\\/api\\/banners\\//i.test(p)||/\\/cdn-cgi\\//i.test(p)||/challenge|turnstile|captcha|verification|cloudflare-terms-of-service|telegram|doubleclick|googlesyndication|adservice/i.test(l)||/\\/banner|\\/advert|\\/popup|\\/sponsor|\\/ads?\\//i.test(p)||/(^|[-_/])(ad|ads|banner|advert)([-_/]|$)/i.test(p)){rejected++;return;}var ok=/\\/(?:manhwa|webtoon)\\/\\d+\\/[^/?#]+\\/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$/i.test(p)||/\\/black(?:toon)?\\/episodes\\/\\d+\\/[^/?#]+\\/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$/i.test(p)||/\\/wt\\/episodes\\/[^/?#]+\\/[^/?#]+\\/p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$/i.test(p);if(!ok){rejected++;return;}var r=null,w=0,h=0;try{r=el&&el.getBoundingClientRect?el.getBoundingClientRect():null;w=Math.round((el&&el.naturalWidth)||((r&&r.width)||el.width)||0);h=Math.round((el&&el.naturalHeight)||((r&&r.height)||el.height)||0);}catch(_){}if(w>0&&h>0&&(w<120||h<120)){rejected++;return;}seen[src]=1;var page=out.length+1;try{var hint=[el&&el.getAttribute&&el.getAttribute('data-page'),el&&el.getAttribute&&el.getAttribute('alt'),src].join(' '),m=hint.match(/(?:^|[^0-9])(?:p)?(\\d{1,4})(?:\\D|$)/i);if(m)page=parseInt(m[1],10)||page;}catch(_){}out.push({page:page,src:src,width:w,height:h});}var nodes=document.querySelectorAll('.vw-imgs img,.vw-spread img,.vw-spread-pair img,main img,article img,section img,img,source,[data-src],[data-original],[data-lazy-src],[data-url],[style*=\"background-image\"]');for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!el)continue;if(el.closest&&el.closest('[data-br],[data-brs],[data-bs],[data-bp],button,a[href^=\"https://t.me\"],[class*=\"banner\"],[id*=\"banner\"],[class*=\"advert\"],[id*=\"advert\"],[class*=\"popup\"],[id*=\"popup\"]'))continue;var vals=[];try{vals.push(el.currentSrc||'',el.src||'',el.getAttribute('src')||'',el.getAttribute('data-src')||'',el.getAttribute('data-original')||'',el.getAttribute('data-lazy-src')||'',el.getAttribute('data-url')||'');var ss=String(el.srcset||el.getAttribute('srcset')||'');if(ss)ss.split(',').forEach(function(x){vals.push(String(x).trim().split(/\\s+/)[0]||'');});var st=String((el.getAttribute&&el.getAttribute('style'))||'');var bm=st.match(/url\\((['\\\"]?)(.*?)\\1\\)/i);if(bm)vals.push(bm[2]||'');}catch(_){}for(var j=0;j<vals.length;j++)addCandidate(el,vals[j]);}out.sort(function(a,b){return(a.page||0)-(b.page||0);});log({viewerImagesDomProbe:true,count:out.length,raw:raw,rejected:rejected,url:String(location.href||'').slice(0,160),first:out[0]?String(out[0].src||'').slice(0,160):''});return out;}catch(e){log({viewerImagesDomProbeError:String(e)});return [];}}"
                + "async function hmac(k,m){var e=new TextEncoder();try{var key=await crypto.subtle.importKey('raw',e.encode(k),{name:'HMAC',hash:'SHA-256'},false,['sign']);return urlB64(new Uint8Array(await crypto.subtle.sign('HMAC',key,e.encode(m))));}catch(_){var v=window.NtkQuicBridge?String(window.NtkQuicBridge.hmacSha256(k,m)||''):'';if(v)return v;throw new Error('hmac');}}"
                + "async function nv(){var v=ck('nv');if(v&&(v.split('.')[0]||'').length>=40){log({viewerImagesNvReady:true,len:v.length,source:'cookie'});return v;}try{var p=window.__ntkNvIssuePromise;if(!p){p=fetch('/api/nv-issue',{method:'POST',credentials:'same-origin',cache:'no-store'}).catch(function(e){log({viewerImagesNvIssueFetchError:String(e).slice(0,120)});return null;}).then(function(){return null;});window.__ntkNvIssuePromise=p;p.finally(function(){if(window.__ntkNvIssuePromise===p)window.__ntkNvIssuePromise=null;});log({viewerImagesNvIssueFetchStart:true,hadCookie:!!v});}await p;}catch(fe){log({viewerImagesNvIssueError:String(fe).slice(0,120)});}v=ck('nv');if(v&&(v.split('.')[0]||'').length>=40){log({viewerImagesNvReady:true,len:v.length,source:'fetch'});return v;}if(window.NtkQuicBridge){try{window.NtkQuicBridge.request(abs('/api/nv-issue'),'POST',JSON.stringify({'accept':'application/json','origin':baseOrigin(),'referer':pageUrl()}),'');}catch(be){log({viewerImagesNvIssueBridgeError:String(be).slice(0,120)});}}await sleep(120);v=ck('nv');log({viewerImagesNvReady:!!(v&&(v.split('.')[0]||'').length>=40),len:String(v||'').length,source:'bridge-fallback'});return v;}"
                + "async function shaText(s){return urlB64(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(String(s||'')))));}async function ensureStandaloneKey(force,keyScope){try{if(window.__ntk_request_key_id&&window.__ntk_image_private_key){if(force)log({viewerImagesKeyForceReuse:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),keyScope:keyScope});return true;}if(!force&&window.indexedDB){try{var stored=await new Promise(function(done){var rq=indexedDB.open('ntk-browser-request-key',1);rq.onerror=function(){done(null);};rq.onsuccess=function(){var db=rq.result;try{var tx=db.transaction('keys','readonly'),gr=tx.objectStore('keys').get(kind==='webtoon'?'webtoon-v1':'manhwa-v1');gr.onsuccess=function(){try{db.close();}catch(_){}done(gr.result||null);};gr.onerror=function(){try{db.close();}catch(_){}done(null);};}catch(e){try{db.close();}catch(_){}done(null);}};});if(stored&&stored.keyId&&(!stored.expiresAt||Number(stored.expiresAt)>Date.now()+60000)){var pk=stored.privateKey||null;if(!pk&&stored.privateJwk&&window.crypto&&crypto.subtle){try{pk=await crypto.subtle.importKey('jwk',stored.privateJwk,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);}catch(importErr){log({viewerImagesIdbKeyImportError:String(importErr),keyId:String(stored.keyId).slice(0,12),keyScope:keyScope});}}if(pk){window.__ntk_request_key_id=String(stored.keyId);window.__ntk_image_private_key=pk;log({viewerImagesIdbKeyLoad:true,keyId:String(stored.keyId).slice(0,12),hasPrivate:!!stored.privateKey,hasPrivateJwk:!!stored.privateJwk,expiresIn:Number(stored.expiresAt||0)-Date.now(),keyScope:keyScope});return true;}log({viewerImagesIdbKeyNoPrivate:true,keyId:String(stored.keyId).slice(0,12),hasPrivateJwk:!!stored.privateJwk,keyScope:keyScope});}}catch(ie){log({viewerImagesIdbKeyLoadError:String(ie),keyScope:keyScope});}}if(!force){try{var lsKid=localStorage.getItem('ntk-browser-request-key-id')||'',lsExp=Number(localStorage.getItem('ntk-browser-request-key-exp')||0),lsJwk=localStorage.getItem('ntk-browser-request-private-jwk')||'';if(lsKid&&lsJwk&&(!lsExp||lsExp>Date.now()+60000)&&window.crypto&&crypto.subtle){var jwkObj=JSON.parse(lsJwk),pk=await crypto.subtle.importKey('jwk',jwkObj,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);window.__ntk_request_key_id=String(lsKid);window.__ntk_image_private_key=pk;log({viewerImagesLocalJwkLoad:true,keyId:String(lsKid).slice(0,12),expiresIn:lsExp-Date.now(),keyScope:keyScope});return true;}}catch(le){log({viewerImagesLocalJwkLoadError:String(le),keyScope:keyScope});}}if(window.NtkQuicBridge&&window.NtkQuicBridge.ensureViewerBrowserKey){try{var keyRaw=window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent?window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(pageUrl(),String(navigator.userAgent||'')):window.NtkQuicBridge.ensureViewerBrowserKey(pageUrl()),nr=JSON.parse(String(keyRaw||'{}'));log({viewerImagesNativeKeyRegisterIgnored:true,ok:!!nr.ok,status:nr.status||0,keyId:nr.keyId?String(nr.keyId).slice(0,12):'',cached:!!nr.cached,keyScope:keyScope,uaBridge:!!window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent,reason:'no_private_key'});}catch(ne){log({viewerImagesNativeKeyRegisterError:String(ne),keyScope:keyScope});}}if(!window.crypto||!crypto.subtle||!crypto.getRandomValues||!window.NtkQuicBridge){log({viewerImagesStandaloneKeyUnavailable:true,hasCrypto:!!window.crypto,hasSubtle:!!(window.crypto&&crypto.subtle),hasBridge:!!window.NtkQuicBridge,keyScope:keyScope});return false;}function ensureNtkFp(){try{if(/(?:^|;\\s*)ntk_fp=[a-fA-F0-9]{16,}/.test(document.cookie||''))return true;function h(seed,text){var r=seed>>>0;for(var i=0;i<text.length;i++){r^=text.charCodeAt(i);r=Math.imul(r,0x1000193)>>>0;}return ('00000000'+(r>>>0).toString(16)).slice(-8);}var n=navigator||{},seed=[n.userAgent||'',n.language||'',Array.isArray(n.languages)?n.languages.join(','):'',String(n.hardwareConcurrency||0),String(n.deviceMemory||0),n.platform||'',String(n.maxTouchPoints||0),window.screen?(screen.width||0)+'x'+(screen.height||0)+'x'+(screen.colorDepth||0):'',String(new Date().getTimezoneOffset()),(window.Intl&&Intl.DateTimeFormat)?Intl.DateTimeFormat().resolvedOptions().timeZone:'',String(location.href||'')].join('|'),fp=h(0x811c9dc5,seed)+h(0xbb40e64d,seed)+h(0x9e3779b1,seed)+h(0x5f356495,seed);document.cookie='ntk_fp='+encodeURIComponent(fp)+'; Path=/; Max-Age=31536000; SameSite=Lax; Secure';try{window.__ntk_fp_ready=1;}catch(_){}return true;}catch(_){return false;}}ensureNtkFp();var kp=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']),jwk=await crypto.subtle.exportKey('jwk',kp.publicKey),privateJwk=await crypto.subtle.exportKey('jwk',kp.privateKey),keyPayload={publicKey:jwk};try{var fp=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ntk_fp')||''):'';var pid=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ntk_pid')||''):'';var vsid=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'__vsid')||''):'';var fpFromIdentity=false;if(!fp){try{var ac=(window.NtkQuicBridge&&window.NtkQuicBridge.cookie)?String(window.NtkQuicBridge.cookie(pageUrl(),'ad_ack_c')||window.NtkQuicBridge.cookie(pageUrl(),'ad_ack')||''):'';var p=ac.split('.')[0]||'',pad='===='.slice((p.length+3)%4),js=JSON.parse(atob((p+pad).replace(/-/g,'+').replace(/_/g,'/'))||'{}');if(js&&js.identity){fp=String(js.identity);fpFromIdentity=true;}}catch(_){}}if(fp){keyPayload.fp=fp;keyPayload.ntkFp=fp;keyPayload.fingerprint=fp;}if(pid){keyPayload.pid=pid;keyPayload.ntkPid=pid;}if(vsid){keyPayload.vsid=vsid;}log({viewerImagesKeyRegisterPayload:true,fpPresent:!!fp,fpFromIdentity:!!fpFromIdentity,pidPresent:!!pid,vsidPresent:!!vsid,keyScope:keyScope});}catch(_){}var bodyText=JSON.stringify(keyPayload);try{window.__ntkAckOnlyDirectAdApi=1;if(window.fetch){var dr=await Promise.race([fetch('/api/client-key/register',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'content-type':'application/json'},body:bodyText}),sleep(2400).then(function(){return null;})]);if(dr){var dt=await dr.text().catch(function(){return '';});var dj={};try{dj=JSON.parse(dt||'{}');}catch(_){}log({viewerImagesDirectKeyRegister:true,status:dr.status||0,ok:!!(dj&&dj.ok),keyId:dj&&dj.keyId?String(dj.keyId).slice(0,12):'',keyScope:keyScope,textHead:String(dt||'').slice(0,120)});if((dr.status||0)===200&&dj&&dj.ok&&dj.keyId){window.__ntk_request_key_id=String(dj.keyId);window.__ntk_image_private_key=kp.privateKey;try{localStorage.setItem('ntk-browser-request-key-id',String(dj.keyId));if(dj.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(dj.expiresAt));localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(privateJwk));}catch(_){}return true;}}}}catch(de){log({viewerImagesDirectKeyRegisterError:String(de),keyScope:keyScope});}var body64=stdB64(new TextEncoder().encode(bodyText)),raw=window.NtkQuicBridge.request(abs('/api/client-key/register'),'POST',JSON.stringify({'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl(),'user-agent':String(navigator.userAgent||'')}),body64),o=JSON.parse(raw||'{}'),txt=d64(o.bodyBase64||''),j={};try{j=JSON.parse(txt||'{}');}catch(_){}log({viewerImagesStandaloneKeyRegister:true,status:o.status||0,ok:!!(j&&j.ok),keyId:j&&j.keyId?String(j.keyId).slice(0,12):'',keyScope:keyScope,uaBridge:true});if((o.status||0)!==200||!j||!j.ok||!j.keyId)return false;window.__ntk_request_key_id=String(j.keyId);window.__ntk_image_private_key=kp.privateKey;try{localStorage.setItem('ntk-browser-request-key-id',String(j.keyId));if(j.expiresAt)localStorage.setItem('ntk-browser-request-key-exp',String(j.expiresAt));localStorage.setItem('ntk-browser-request-private-jwk',JSON.stringify(privateJwk));}catch(_){}return true;}catch(e){log({viewerImagesStandaloneKeyError:String(e),keyScope:keyScope});return false;}}async function standaloneSignHeaders(h,bodyText,signedPath,signedScope,tryNo){try{if(!window.__ntk_request_key_id||!window.__ntk_image_private_key){var ok=await ensureStandaloneKey(tryNo>0,signedScope);if(!ok||!window.__ntk_image_private_key)return false;}var ts=String(Date.now()),nn=new Uint8Array(24);crypto.getRandomValues(nn);var nonce=urlB64(nn),bodyHash=await shaText(bodyText),base=['ntk-brsig-v1','POST',signedPath,signedScope,window.__ntk_request_key_id,ts,nonce,bodyHash].join('\\n'),sig=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},window.__ntk_image_private_key,new TextEncoder().encode(base)));h['x-ntk-key-id']=window.__ntk_request_key_id;h['x-ntk-ts']=ts;h['x-ntk-nonce']=nonce;h['x-ntk-sig']=urlB64(sig);h.__ntk_signed_body_text=bodyText;h.__ntk_sig_format='webcrypto-p1363';log({viewerImagesStandaloneSigned:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,bodyRequestKey:bodyText.indexOf('requestKeyId')>=0});return true;}catch(e){log({viewerImagesStandaloneSignError:String(e),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope});return false;}}async function ensureBrowserKey(force,keyScope){try{keyScope=keyScope||scope;if(window.__ntk_request_key_id&&window.__ntk_image_private_key){if(force)log({viewerImagesBrowserKeyForceReuse:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),keyScope:keyScope});return true;}var req=webpackReq(),m=findSigner(req),siteId=null;if(!m||!m.D){log({viewerImagesBrowserKeyNoModule:true,force:!!force,keyScope:keyScope});return await ensureStandaloneKey(force,keyScope);}var nf=window.__ntkNativeFetch||window.fetch;try{if(false&&window.NtkQuicBridge&&nf){window.fetch=function(input,init){try{var u=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(u))).pathname;if(p!=='/api/client-key/register')return nf.apply(this,arguments);var bodyArg=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:((input&&window.Request&&input instanceof Request)?input:null);function bodyP(x){try{if(x==null)return Promise.resolve('');if(window.Request&&x instanceof Request&&x.clone)return x.clone().arrayBuffer().then(function(a){return stdB64(new Uint8Array(a));});if(typeof x==='string')return Promise.resolve(stdB64(new TextEncoder().encode(x)));if(window.Blob&&x instanceof Blob&&x.arrayBuffer)return x.arrayBuffer().then(function(a){return stdB64(new Uint8Array(a));});return Promise.resolve(stdB64(new TextEncoder().encode(String(x))));}catch(e){return Promise.resolve('');}}return bodyP(bodyArg).then(function(b){var h={'content-type':'application/json','accept':'application/json','origin':baseOrigin(),'referer':pageUrl(),'user-agent':String(navigator.userAgent||'')};try{var ih=new Headers((init&&init.headers)||(input&&input.headers)||{});ih.forEach(function(v,k){h[k]=v;});}catch(_){}var raw=window.NtkQuicBridge.request(abs('/api/client-key/register'),'POST',JSON.stringify(h),b),o=JSON.parse(raw||'{}'),bs=bytes(o.bodyBase64||'');log({viewerImagesKeyRegisterBridge:true,status:o.status||0,len:bs.length,force:!!force,keyScope:keyScope,uaBridge:true});return new Response(bs,{status:o.status||200,statusText:o.statusText||'OK',headers:o.headers||{}});});}catch(e){log({viewerImagesKeyRegisterBridgeError:String(e),force:!!force,keyScope:keyScope});return nf.apply(this,arguments);}};}siteId=await Promise.race([m.D(keyScope),sleep(force?3200:2200).then(function(){return null;})]);}finally{if(nf)try{window.fetch=nf;}catch(_){}}if(siteId&&window.__ntk_image_private_key)window.__ntk_request_key_id=siteId;if(siteId&&!window.__ntk_image_private_key){log({viewerImagesBrowserKeySiteIdNoPrivate:true,keyId:String(siteId||'').slice(0,12),keptKeyId:String(window.__ntk_request_key_id||'').slice(0,12),force:!!force,keyScope:keyScope});if(await ensureStandaloneKey(force,keyScope))return true;}var usable=!!window.__ntk_image_private_key;if(window.__ntk_request_key_id&&!window.__ntk_image_private_key&&!siteId)log({viewerImagesBrowserKeyIdOnly:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),force:!!force,keyScope:keyScope});log({viewerImagesBrowserKeyEnsure:!!siteId,keyId:String(siteId||window.__ntk_request_key_id||'').slice(0,12),activeKeyId:String(window.__ntk_request_key_id||'').slice(0,12),force:!!force,keyScope:keyScope,hasPrivate:!!window.__ntk_image_private_key,usable:usable});return usable;}catch(e){log({viewerImagesBrowserKeyEnsureError:String(e),force:!!force,keyScope:keyScope});return false;}}"
                + "function bridgeReq(url,body,headers,tryNo){var txt=JSON.stringify(body),bh={};Object.keys(headers||{}).forEach(function(k){if(String(k).indexOf('__')!==0)bh[k]=headers[k];});try{if(!bh['user-agent']&&!bh['User-Agent'])bh['user-agent']=String(navigator.userAgent||'');}catch(_){}var raw=window.NtkQuicBridge.request(abs(url),'POST',JSON.stringify(bh),stdB64(new TextEncoder().encode(txt))),o=JSON.parse(raw||'{}'),t=d64(o.bodyBase64||''),j={};try{j=JSON.parse(t||'{}');}catch(_){}log({viewerImagesApiOnlyBridge:true,status:o.status||0,body:j,textHead:String(t||'').slice(0,180),keyHeader:!!bh['x-ntk-key-id'],tryNo:tryNo,sigFormat:headers&&headers.__ntk_sig_format?headers.__ntk_sig_format:'',siteSigned:!!(headers&&headers.__ntk_site_signed),uaBridge:!!(bh['user-agent']||bh['User-Agent'])});return{status:o.status||0,body:j,text:t};}"
                + "function retryable(r){try{return !r||r.status===0||r.status===403||(r.status===428&&r.body&&r.body.error==='browser_key_required');}catch(_){return true;}}"
                + "async function payload(v){var n=new Uint8Array(24);crypto.getRandomValues(n);var nonce=urlB64(n),proof=await hmac(v,token+'.'+nonce+'.'+navigator.userAgent),body={workId:workId,episodeId:episodeId,token:token,nonce:nonce,proof:proof};return{body:body,bodyText:JSON.stringify(body)};}"
                + "async function signHeaders(bodyText,signedPath,signedScope,tryNo){var h={'content-type':'application/json','x-images-client':'viewer-v1'},signed=false;try{try{window.__ntkAckOnlyDirectAdApi=1;}catch(_){}await ensureBrowserKey(tryNo>0,signedScope);try{if(window.__ntk_image_private_key&&window.__ntk_request_key_id&&await standaloneSignHeaders(h,bodyText,signedPath,signedScope,tryNo)){h.__ntk_direct_key_signed='1';log({viewerImagesDirectKeySignedFirst:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope});return h;}}catch(dse){log({viewerImagesDirectKeySignedFirstError:String(dse),tryNo:tryNo});}try{if(window.NtkQuicBridge&&(window.NtkQuicBridge.signViewerRequestFormat||window.NtkQuicBridge.signViewerRequest)){var kid='',kr=null;try{if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(pageUrl(),String(navigator.userAgent||''))||'{}'));else if(window.NtkQuicBridge.ensureViewerBrowserKey)kr=JSON.parse(String(window.NtkQuicBridge.ensureViewerBrowserKey(pageUrl())||'{}'));if(kr&&kr.keyId){kid=String(kr.keyId);log({viewerImagesNativeBridgeKeyEnsure:true,ok:!!kr.ok,status:kr.status||0,keyId:kid.slice(0,12),tryNo:tryNo,signedScope:signedScope});}}catch(kerr){log({viewerImagesNativeBridgeKeyEnsureError:String(kerr),tryNo:tryNo,signedScope:signedScope});}if(!kid)kid=String(window.__ntk_request_key_id||'');if(kid)window.__ntk_request_key_id=kid;var fmt=(tryNo%2===1?'der':'p1363');var raw=window.NtkQuicBridge.signViewerRequestFormat?window.NtkQuicBridge.signViewerRequestFormat('POST',signedPath,signedScope,bodyText,fmt):window.NtkQuicBridge.signViewerRequest('POST',signedPath,signedScope,bodyText),ns=JSON.parse(String(raw||'{}'));if(ns&&ns.ok&&ns.headers){Object.keys(ns.headers).forEach(function(k){h[k]=ns.headers[k];});h.__ntk_native_signed='1';h.__ntk_signed_body_text=bodyText;h.__ntk_sig_format=fmt;signed=!!h['x-ntk-key-id'];log({viewerImagesNativeBridgeSigned:signed,keyId:String(ns.keyId||h['x-ntk-key-id']||'').slice(0,12),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,apiScope:apiScope,ackScope:scope,bodyRequestKey:bodyText.indexOf('requestKeyId')>=0,format:fmt});if(signed)log({viewerImagesNativeBridgeDeferred:true,keyId:String(ns.keyId||h['x-ntk-key-id']||'').slice(0,12),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,format:fmt});}else log({viewerImagesNativeBridgeSignMiss:true,ok:!!(ns&&ns.ok),error:ns&&ns.error?String(ns.error).slice(0,120):'',tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,format:fmt});}}catch(nativeSignErr){log({viewerImagesNativeBridgeSignError:String(nativeSignErr),tryNo:tryNo,signedPath:signedPath,signedScope:signedScope});}for(var si=0;si<3;si++){var req2=webpackReq(),m=findSigner(req2);if(!m||!m.X){if(si===0)log({viewerImagesSignerNotReady:true,apiOnly:true,tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,apiScope:apiScope,ackScope:scope});if(await standaloneSignHeaders(h,bodyText,signedPath,signedScope,tryNo)){signed=true;break;}await sleep(120);continue;}if(si===0&&!window.__ntk_request_key_id)await ensureBrowserKey(true,signedScope);var s=await m.X({method:'POST',path:signedPath,scope:signedScope,bodyText:bodyText});if(s&&s.headers){Object.keys(s.headers).forEach(function(k){h[k]=s.headers[k];});try{delete h.__ntk_native_signed;delete h.__ntk_sig_format;}catch(_){}h.__ntk_site_signed='1';signed=!!h['x-ntk-key-id'];log({viewerImagesSiteSigned:true,keyId:String(s.keyId||s.headers['x-ntk-key-id']||'').slice(0,12),apiOnly:true,try:si,tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,apiScope:apiScope,ackScope:scope});break;}if(si===0)await ensureBrowserKey(true,signedScope);await sleep(120);}if(!signed&&await ensureStandaloneKey(false,signedScope))signed=await standaloneSignHeaders(h,bodyText,signedPath,signedScope,tryNo);if(!signed&&tryNo>0&&await ensureStandaloneKey(true,signedScope))signed=await standaloneSignHeaders(h,bodyText,signedPath,signedScope,tryNo);if(!signed)log({viewerImagesNoSiteSigner:true,apiOnly:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12),hasPrivate:!!window.__ntk_image_private_key,tryNo:tryNo,signedPath:signedPath,signedScope:signedScope,apiScope:apiScope,ackScope:scope});}catch(se){log({viewerImagesSiteSignError:String(se),apiOnly:true,tryNo:tryNo,signedPath:signedPath,signedScope:signedScope});}return h;}"
                + "(async function(){try{for(var di=0;di<3;di++){var early=domImages();if(early.length){send({code:200,body:{ok:true,images:early,source:'dom-pre-api'}});return;}await sleep(260+di*220);}var v=await nv();if(!v||(v.split('.')[0]||'').length<40){send({code:401,body:{ok:false,error:'missing_nv'}});return;}var last=null,variants=[{p:endpoint,s:scope},{p:endpoint,s:apiScope},{p:apiScope,s:apiScope}];for(var ai=0;ai<4;ai++){var variant=variants[Math.max(0,Math.min(variants.length-1,ai-1))];await ensureBrowserKey(ai>0,variant.s);if(ai>0)await sleep(360+ai*260);var p=await payload(v),signedPath=ai===0?endpoint:variant.p,signedScope=ai===0?scope:variant.s,h=await signHeaders(p.bodyText,signedPath,signedScope,ai);if(h.__ntk_signed_body_text){try{p.bodyText=String(h.__ntk_signed_body_text);p.body=JSON.parse(p.bodyText);}catch(_){}}try{var fh={};Object.keys(h).forEach(function(k){var lk=String(k||'').toLowerCase();if(lk.indexOf('__')!==0&&lk!=='origin'&&lk!=='referer'&&lk!=='host'&&lk!=='cookie'&&lk!=='content-length')fh[k]=h[k];});var opt={method:'POST',credentials:'same-origin',cache:'no-store',headers:fh,body:p.bodyText},ctrl=null,to=null;if(window.AbortController){ctrl=new AbortController();opt.signal=ctrl.signal;to=setTimeout(function(){try{ctrl.abort();}catch(_){}},1100+ai*700);}var fr=await fetch(endpoint,opt);if(to)clearTimeout(to);var ft=await fr.text().catch(function(){return '';});var fj={};try{fj=JSON.parse(ft||'{}');}catch(_){}last={status:fr.status,body:fj,text:ft};log({viewerImagesApiOnlyFetch:true,status:fr.status,body:fj,textHead:String(ft||'').slice(0,180),keyHeader:!!fh['x-ntk-key-id'],imagesClient:fh['x-images-client']||'',nativeSigned:!!h.__ntk_native_signed,siteSigned:!!h.__ntk_site_signed,directKeySigned:!!h.__ntk_direct_key_signed,tryNo:ai,signedPath:signedPath,signedScope:signedScope});if(!retryable(last)){send({code:fr.status,body:fj});return;}}catch(fe){log({viewerImagesApiOnlyFetchError:String(fe),keyHeader:!!h['x-ntk-key-id'],nativeSigned:!!h.__ntk_native_signed,siteSigned:!!h.__ntk_site_signed,directKeySigned:!!h.__ntk_direct_key_signed,tryNo:ai,signedPath:signedPath,signedScope:signedScope});}var mid=domImages();if(mid.length){send({code:200,body:{ok:true,images:mid,source:'dom-after-fetch',status:last&&last.status||0}});return;}if(window.NtkQuicBridge){try{last=bridgeReq(endpoint,p.body,h,ai);if(!retryable(last)){send({code:last.status,body:last.body});return;}}catch(be){last={status:0,body:{error:String(be)}};log({viewerImagesApiOnlyBridgeError:String(be),tryNo:ai,signedPath:signedPath,signedScope:signedScope});}}var late=domImages();if(late.length){send({code:200,body:{ok:true,images:late,source:'dom-after-bridge',status:last&&last.status||0}});return;}log({viewerImagesApiOnlyRetry:true,tryNo:ai,status:last&&last.status,body:last&&last.body,signedPath:signedPath,signedScope:signedScope});}send({code:last?last.status:0,body:last?last.body:{error:'timeout'}});}catch(e){send({code:0,error:String(e)});}})();})()";
    }

    private static String buildViewerImageFetchScript(String baseUrl, String path, String ackScopePath,
                                                      String kind, String workId,
                                                      String episodeId, String imagesToken,
                                                      boolean serverAckProof) {
        String scope = ackScopePath == null || ackScopePath.length() == 0 ? path : ackScopePath;
        return buildViewerImageFetchScript(baseUrl, scope, kind, workId, episodeId, imagesToken,
                serverAckProof);
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
        if(!request.isForMainFrame() && shouldUseDirectWebViewForActiveFetch(url))
            return null;
        try {
            Map<String, String> requestHeaders = request.getRequestHeaders() == null
                    ? new HashMap<>() : new HashMap<>(request.getRequestHeaders());
            String cookieHeader = NtkQuicBridge.bridgeCookieHeader(url, fallbackCookieHeader,
                    requestHeaders, new byte[0]);
            if(metricImage || cdnImage)
                Log.d(TAG, "ntk_viewer_quic_intercept_image_start url=" + url
                        + ",cdn=" + cdnImage
                        + ",metric=" + metricImage
                        + ",hasAdAckC=" + (NtkQuicBridge.cookieValue(cookieHeader, "ad_ack_c").length() > 0)
                        + ",adAckCMatches=" + NtkQuicBridge.ntkAckCookieMatchesScope(
                        NtkQuicBridge.cookieValue(cookieHeader, "ad_ack_c"),
                        NtkQuicBridge.ntkBridgeScopeForRequest(url, requestHeaders, new byte[0]))
                        + ",headers=" + requestHeaders);
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                    cookieHeader, requestHeaders,
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
            rememberStaticProbeResponse(url, result);
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
            if(metricImage && result.code >= 200 && result.code < 300) {
                rememberMetricImageHit(url);
                responseBytes = TINY_GIF_BYTES;
                mimeType = "image/gif";
            }
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

    private void rememberStaticProbeResponse(String url, NtkQuicFetcher.Result result) {
        if(url == null || result == null || result.code != 200 || result.bodyBytes == null
                || result.bodyBytes.length == 0)
            return;
        String lower = url.toLowerCase(Locale.US);
        if(!lower.contains("/_next/static/chunks/") || !lower.contains(".js"))
            return;
        try {
            java.io.File dir = new java.io.File(context.getCacheDir(), "ntk_static_probe");
            if(!dir.exists() && !dir.mkdirs())
                return;
            String name = Integer.toHexString(url.hashCode()) + ".js";
            java.io.File file = new java.io.File(dir, name);
            if(file.exists() && file.length() == result.bodyBytes.length)
                return;
            try(java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                output.write(result.bodyBytes);
            }
            Log.d(TAG, "ntk_static_probe_write name=" + name
                    + ",len=" + result.bodyBytes.length
                    + ",url=" + url);
        } catch (Exception e) {
            Log.d(TAG, "ntk_static_probe_write_failed " + e);
        }
    }

    private static boolean isNtkMetricImageUrl(String url) {
        try {
            return "/api/m/i".equals(URI.create(url).getPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static void rememberMetricImageHit(String url) {
        if(url == null || url.length() == 0)
            return;
        METRIC_IMAGE_HITS.put(url, SystemClock.uptimeMillis());
    }

    private static boolean hasRecentMetricImageHit(String url) {
        Long hitAt = url == null ? null : METRIC_IMAGE_HITS.get(url);
        if(hitAt == null)
            return false;
        if(SystemClock.uptimeMillis() - hitAt <= METRIC_IMAGE_HIT_TTL_MS)
            return true;
        METRIC_IMAGE_HITS.remove(url);
        return false;
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

    private static void expireCloudflareWebViewCookiesOnly(String... urls) {
        try {
            CookieManager manager = CookieManager.getInstance();
            if(urls == null)
                return;
            for(String url : urls) {
                if(url == null || url.length() == 0)
                    continue;
                String host = "";
                try {
                    host = Uri.parse(url).getHost();
                } catch (Exception ignored) {
                }
                expireWebViewCookieOnly(manager, url, "cf_clearance", host);
                expireWebViewCookieOnly(manager, url, "__cf_bm", host);
            }
            manager.flush();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static void expireAckOnlyBloatWebViewCookies(String... urls) {
        try {
            CookieManager manager = CookieManager.getInstance();
            if(urls == null)
                return;
            for(String url : urls) {
                if(url == null || url.length() == 0)
                    continue;
                String host = "";
                try {
                    host = Uri.parse(url).getHost();
                } catch (Exception ignored) {
                }
                expireWebViewCookieOnly(manager, url, "cf_chl_rc_ni", host);
                expireWebViewCookieOnly(manager, url, "cf_chl_rc_i", host);
                expireWebViewCookieOnly(manager, url, "newtoki_read", host);
                expireWebViewCookieOnly(manager, url, "newtoki_recent", host);
            }
            manager.flush();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static void clearWebViewCookiesForAckOnly(String baseUrl, String path,
                                                      String beforeCookieHeader) {
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.removeSessionCookies(null);
            manager.removeAllCookies(null);
            manager.flush();
            Log.d(TAG, "ntk_ack_only_cookie_store_clear_for_cf path=" + path
                    + ",origin=" + baseUrl
                    + ",before=" + summarizeNtkCookieHeaderForLog(beforeCookieHeader));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static void expireWebViewCookieOnly(CookieManager manager, String url, String name,
                                                String host) {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/");
        paths.add("/manhwa");
        paths.add("/webtoon");
        paths.add("/api");
        try {
            String urlPath = Uri.parse(url).getPath();
            if(urlPath != null && urlPath.length() > 0) {
                String normalized = urlPath.startsWith("/") ? urlPath : "/" + urlPath;
                while(normalized.length() > 1) {
                    if(!paths.contains(normalized))
                        paths.add(normalized);
                    int slash = normalized.lastIndexOf('/');
                    if(slash <= 0)
                        break;
                    normalized = normalized.substring(0, slash);
                }
            }
        } catch (Exception ignored) {
        }
        String[] expires = new String[]{
                name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=",
                name + "=deleted; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path="
        };
        for(String path : paths) {
            for(String expire : expires) {
                manager.setCookie(url, expire + path);
                if(host != null && host.length() > 0) {
                    manager.setCookie(url, expire + path + "; Domain=" + host);
                    manager.setCookie(url, expire + path + "; Domain=." + host);
                }
            }
        }
    }

    private static String summarizeNtkCookieHeaderForLog(String cookieHeader) {
        if(cookieHeader == null || cookieHeader.length() == 0)
            return "len=0";
        return "len=" + cookieHeader.length()
                + ",cf=" + hasCookieName(cookieHeader, "cf_clearance")
                + ",nv=" + hasCookieName(cookieHeader, "nv")
                + ",adGuardL=" + hasCookieName(cookieHeader, "ad_guard_l")
                + ",adAck=" + hasCookieName(cookieHeader, "ad_ack")
                + ",adAckC=" + hasCookieName(cookieHeader, "ad_ack_c");
    }

    private static boolean hasCookieName(String cookieHeader, String name) {
        if(cookieHeader == null || name == null || name.length() == 0)
            return false;
        String prefix = name + "=";
        for(String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            if(trimmed.equals(name) || trimmed.startsWith(prefix))
                return true;
        }
        return false;
    }

    private static String cookieHeaderWithoutCloudflare(String cookieHeader) {
        return cookieHeaderWithoutNames(cookieHeader, "cf_clearance", "__cf_bm");
    }

    private static String ackOnlySeedCookieHeader(String cookieHeader) {
        return cookieHeaderWithoutNames(cookieHeader,
                "cf_chl_rc_ni", "cf_chl_rc_i", "newtoki_read", "newtoki_recent");
    }

    private static String cookieHeaderWithoutNames(String cookieHeader, String... names) {
        if(cookieHeader == null || cookieHeader.length() == 0)
            return "";
        java.util.HashSet<String> blocked = new java.util.HashSet<>();
        if(names != null) {
            for(String name : names) {
                if(name != null && name.length() > 0)
                    blocked.add(name.toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder builder = new StringBuilder();
        for(String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            if(trimmed.length() == 0)
                continue;
            int equals = trimmed.indexOf('=');
            String name = equals <= 0 ? trimmed : trimmed.substring(0, equals).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if(blocked.contains(lower))
                continue;
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(trimmed);
        }
        return builder.toString();
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
        applyWebViewCookies(url, result, true);
    }

    private static void applyWebViewCookies(String url, NtkQuicFetcher.Result result, boolean flush) {
        if(url == null || result == null)
            return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for(String cookie : result.setCookies()) {
                if(cookie != null && cookie.length() > 0)
                    manager.setCookie(url, cookie);
            }
            if(flush)
                manager.flush();
        } catch (Exception ignored) {
        }
        applyBridgeCookiesToAppJar(result);
    }

    private static void applyBridgeCookiesToAppJar(NtkQuicFetcher.Result result) {
        if(result == null || result.setCookies() == null || result.setCookies().isEmpty())
            return;
        try {
            CustomHttpClient client = MainApplication.getHttpClient();
            if(client == null)
                return;
            for(String cookie : result.setCookies()) {
                if(cookie == null)
                    continue;
                String first = cookie.split(";", 2)[0].trim();
                int equals = first.indexOf('=');
                if(equals <= 0)
                    continue;
                String name = first.substring(0, equals).trim();
                String value = first.substring(equals + 1).trim();
                String lower = cookie.toLowerCase(java.util.Locale.ROOT);
                if(lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970"))
                    client.setCookie(name, null);
                else
                    client.setCookie(name, value);
            }
        } catch (Exception ignored) {
        }
    }

    private static void syncBridgeAckCookies(String url, byte[] requestBody,
                                             NtkQuicFetcher.Result result) {
        if(url == null || result == null || result.code != 200)
            return;
        try {
            URI uri = URI.create(url);
            if(!"/api/ad/ack".equals(uri.getPath()))
                return;
            JSONObject response = new JSONObject(result.body == null ? "{}" : result.body);
            if(!(response.optBoolean("ok", false)
                    || response.optBoolean("acked", false)
                    || "ok".equals(response.optString("status", ""))
                    || "acked".equals(response.optString("status", ""))))
                return;
            JSONObject request = new JSONObject(new String(requestBody == null
                    ? new byte[0] : requestBody, java.nio.charset.StandardCharsets.UTF_8));
            String scope = request.optString("path", "");
            if(!scope.startsWith("/manhwa/") && !scope.startsWith("/webtoon/"))
                return;
            String origin = uri.getScheme() + "://" + uri.getHost();
            if(uri.getPort() >= 0)
                origin += ":" + uri.getPort();
            CustomHttpClient client = MainApplication.getHttpClient();
            if(client != null) {
                client.syncCookiesFromWebView(origin, true);
                client.syncCookiesFromWebView(origin + scope, true);
                Log.d(TAG, "ntk_bridge_ack_cookie_synced path=" + scope);
            }
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

    private static boolean shouldBlockHiddenRealFrameDecorativeRequest(WebResourceRequest request) {
        if(request == null || request.isForMainFrame() || request.getUrl() == null)
            return false;
        String method = request.getMethod();
        if(method == null || !"GET".equalsIgnoreCase(method))
            return false;
        try {
            URI uri = URI.create(request.getUrl().toString());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if("whoas.xyz".equals(host))
                return true;
            if(host.contains("telegram") || path.contains("/api/banners/")
                    || path.contains("/banner") || path.contains("/ads/"))
                return true;
            if(isNtkImageCdnUrl(uri))
                return true;
            return path.endsWith(".css")
                    || path.endsWith(".woff")
                    || path.endsWith(".woff2")
                    || path.endsWith(".ttf")
                    || path.endsWith(".otf")
                    || path.contains("/_next/static/media/");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean shouldBlockHiddenAckOnlyHeavyRequest(WebResourceRequest request) {
        if(request == null || request.isForMainFrame() || request.getUrl() == null)
            return false;
        String method = request.getMethod();
        if(method == null || !"GET".equalsIgnoreCase(method))
            return false;
        try {
            URI uri = URI.create(request.getUrl().toString());
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if(path.startsWith("/api/ad/") || path.startsWith("/_next/"))
                return false;
            if("/api/m/i".equals(path) || path.startsWith("/api/ev/") || path.startsWith("/api/m/ev"))
                return false;
            if("/api/manhwa-images".equals(path) || "/api/webtoon-images".equals(path))
                return true;
            if(isNtkImageCdnUrl(uri))
                return true;
            if(path.endsWith(".css")
                    || path.endsWith(".woff")
                    || path.endsWith(".woff2")
                    || path.endsWith(".ttf")
                    || path.endsWith(".otf")
                    || path.endsWith(".png")
                    || path.endsWith(".jpg")
                    || path.endsWith(".jpeg")
                    || path.endsWith(".webp")
                    || path.endsWith(".gif")
                    || path.endsWith(".svg")
                    || path.startsWith("/_next/image")
                    || path.contains("/_next/static/media/"))
                return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private WebResourceResponse siteGuardResourceResponse(WebResourceRequest request,
                                                          NtkQuicBridge quicBridge) {
        if(request == null || request.isForMainFrame() || request.getUrl() == null)
            return null;
        String method = request.getMethod();
        if(method == null || !"GET".equalsIgnoreCase(method))
            return null;
        try {
            URI uri = URI.create(request.getUrl().toString());
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String assetPath;
            String mimeType;
            String encoding = null;
            if("/wasm/ad-guard/ad_guard.js".equals(path)) {
                assetPath = "ntk_guard/guard.js";
                mimeType = "application/javascript";
                encoding = "UTF-8";
            } else if("/wasm/ad-guard/ad_guard_bg.wasm".equals(path)) {
                assetPath = "ntk_guard/guard-wasm.bin";
                mimeType = "application/wasm";
            } else {
                return null;
            }
            String version = NtkQuicBridge.ntkGuardVersionFromUrl(request.getUrl().toString());
            if(version.length() > 0) {
                WebResourceResponse versioned = versionedSiteGuardResourceResponse(
                        uri, version, mimeType, encoding, quicBridge);
                if(versioned != null)
                    return versioned;
                Log.d(TAG, "ntk_ack_only_site_guard_resource_passthrough path=" + path
                        + ",version=" + version);
                return null;
            }
            byte[] bytes = NtkQuicBridge.readAssetBytes(context, assetPath);
            if(bytes == null || bytes.length == 0)
                return null;
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", mimeType + (encoding == null ? "" : "; charset=utf-8"));
            headers.put("cache-control", "no-store");
            Log.d(TAG, "ntk_ack_only_site_guard_resource path=" + path
                    + ",asset=" + assetPath
                    + ",len=" + bytes.length);
            return new WebResourceResponse(mimeType, encoding, 200, "OK", headers,
                    new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.d(TAG, "ntk_ack_only_site_guard_resource_error error=" + e);
            return null;
        }
    }

    private WebResourceResponse versionedSiteGuardResourceResponse(URI requestUri, String version,
                                                                   String mimeType, String encoding,
                                                                   NtkQuicBridge quicBridge) {
        if(requestUri == null || version == null || version.length() == 0 || quicBridge == null)
            return null;
        try {
            String scheme = requestUri.getScheme();
            String host = requestUri.getHost();
            if(scheme == null || host == null)
                return null;
            String path = requestUri.getPath() == null ? "" : requestUri.getPath().toLowerCase(Locale.ROOT);
            String apiPath;
            if("/wasm/ad-guard/ad_guard.js".equals(path))
                apiPath = "/api/ad/guard-js";
            else if("/wasm/ad-guard/ad_guard_bg.wasm".equals(path))
                apiPath = "/api/ad/guard-wasm";
            else
                return null;
            String origin = scheme + "://" + host;
            String apiUrl = origin + apiPath + "?v=" + version;
            JSONObject headersJson = new JSONObject();
            headersJson.put("accept", "application/javascript,application/wasm,*/*");
            headersJson.put("origin", origin);
            headersJson.put("referer", origin + "/");
            long started = SystemClock.uptimeMillis();
            JSONObject bridge = new JSONObject(quicBridge.request(apiUrl, "GET", headersJson.toString(), ""));
            int status = bridge.optInt("status", 0);
            String bodyBase64 = bridge.optString("bodyBase64", "");
            if(status != 200 || bodyBase64.length() == 0) {
                Log.d(TAG, "ntk_ack_only_site_guard_resource_bridge_miss path=" + path
                        + ",version=" + version
                        + ",status=" + status
                        + ",error=" + bridge.optString("error", "")
                        + ",ms=" + (SystemClock.uptimeMillis() - started));
                return null;
            }
            byte[] bytes = Base64.decode(bodyBase64, Base64.DEFAULT);
            if(bytes == null || bytes.length == 0)
                return null;
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", mimeType + (encoding == null ? "" : "; charset=utf-8"));
            headers.put("cache-control", "no-store");
            Log.d(TAG, "ntk_ack_only_site_guard_resource_bridge path=" + path
                    + ",apiPath=" + apiPath
                    + ",version=" + version
                    + ",len=" + bytes.length
                    + ",ms=" + (SystemClock.uptimeMillis() - started));
            return new WebResourceResponse(mimeType, encoding, 200, "OK", headers,
                    new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.d(TAG, "ntk_ack_only_site_guard_resource_bridge_error version=" + version
                    + ",error=" + e);
            return null;
        }
    }

    private static boolean shouldLogAckOnlyResourceEvent(WebResourceRequest request) {
        if(request == null || request.getUrl() == null)
            return false;
        String url = request.getUrl().toString().toLowerCase(Locale.ROOT);
        return request.isForMainFrame()
                || url.contains("cloudflare")
                || url.contains("turnstile")
                || url.contains("/cdn-cgi/")
                || url.contains("/api/ad/");
    }

    private static boolean shouldUseDirectWebViewForAckOnlyApi(WebResourceRequest request) {
        if(request == null || request.isForMainFrame() || request.getUrl() == null)
            return false;
        String method = request.getMethod();
        if(method == null
                || (!"POST".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method)))
            return false;
        try {
            URI uri = URI.create(request.getUrl().toString());
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return path.startsWith("/api/ad/")
                    || "/api/client-key/register".equals(path);
        } catch (Exception e) {
            return false;
        }
    }

    private static WebResourceResponse emptyWebViewResponse(String mimeType) {
        return new WebResourceResponse(mimeType, "UTF-8", new ByteArrayInputStream(new byte[0]));
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
        boolean trustedHost = lowerHost.equals("i.toonflix.app")
                || lowerHost.matches("flysky\\d*m\\.com");
        return trustedHost
                && (lowerPath.contains("/board_uploads/")
                || lowerPath.contains("/webtoon_uploads/")
                || lowerPath.contains("/manhwa_uploads/")
                || lowerPath.contains("/comic_uploads/")
                || lowerPath.contains("/manhwa/")
                || lowerPath.contains("/webtoon/")
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
                || "content-security-policy".equals(lower)
                || "content-security-policy-report-only".equals(lower)
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
                + viewerImageCacheKey(kind, workId, episodeId, path) + '|'
                + (imagesToken == null ? "" : imagesToken);
    }

    private static String jsChunk(String value) {
        return value;
    }

    private static String buildAckOnlyBrowserKeyRetryScript() {
        return jsChunk("try{(function(){"
                + "if(!ackOnly||!window.NtkQuicBridge||window.NtkQuicBridge.__ntkBrowserKeyRetry)return;"
                + "var rawReq=window.NtkQuicBridge.request.bind(window.NtkQuicBridge);"
                + "window.NtkQuicBridge.__ntkBrowserKeyRetry=1;"
                + "window.NtkQuicBridge.request=function(u,m,h,b){"
                + "var raw=rawReq(u,m,h,b);"
                + "try{"
                + "var p=(new URL(abs(u))).pathname,method=String(m||'GET').toUpperCase();"
                + "if(p==='/api/ad/ack'&&method==='POST'&&!window.__ntkBrowserKeyRetryBusy){"
                + "var o=JSON.parse(raw||'{}'),txt=decode64(o.bodyBase64||''),j={};"
                + "try{j=JSON.parse(txt||'{}');}catch(_){}"
                + "if((o.status||0)===428&&j&&j.error==='browser_key_required'){"
                + "window.__ntkBrowserKeyRetryBusy=1;"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryAfterRequired:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}"
                + "setTimeout(function(){"
                + "Promise.resolve().then(function(){"
                + "try{delete window.__ntk_request_key_id;}catch(_){window.__ntk_request_key_id='';}"
                + "if(window.__ntkEnsureBrowserKeyIdb)return window.__ntkEnsureBrowserKeyIdb(true);"
                + "if(typeof ensureBrowserKeyIdb==='function')return ensureBrowserKeyIdb(true);"
                + "return false;"
                + "}).then(function(ok){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryRegistered:!!ok,keyId:String(window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}"
                + "if(ok&&typeof directAck==='function')return directAck();"
                + "return false;"
                + "}).then(function(ok){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryDirectAckDone:!!ok,acked:acked(),proofed:ackProofed()}));}catch(_){}"
                + "window.__ntkBrowserKeyRetryBusy=0;"
                + "},function(e){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryError:String(e)}));}catch(_){}"
                + "window.__ntkBrowserKeyRetryBusy=0;"
                + "});"
                + "},0);"
                + "}"
                + "}"
                + "}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryOuterError:String(e)}));}catch(_){}window.__ntkBrowserKeyRetryBusy=0;}"
                + "return raw;"
                + "};"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryInstalled:true}));}catch(_){}})();}"
                + "catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyRetryInstallError:String(e)}));}catch(_){}}");
    }

    private static String buildAckOnlyBrowserKeyFetchRetryScript() {
        return jsChunk("try{(function(){"
                + "if(!ackOnly)return;"
                + "function isAckRequest(input,init){try{var url=(typeof input==='string'||input instanceof String)?String(input):(input&&input.url)||'',p=(new URL(abs(url))).pathname,m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();return p==='/api/ad/ack'&&m==='POST';}catch(_){return false;}}"
                + "function retryAck(){"
                + "if(window.__ntkBrowserKeyFetchRetryBusy)return;"
                + "window.__ntkBrowserKeyFetchRetryBusy=1;"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryAfterRequired:true,keyId:String(window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}"
                + "setTimeout(function(){"
                + "Promise.resolve().then(function(){"
                + "try{delete window.__ntk_request_key_id;}catch(_){window.__ntk_request_key_id='';}"
                + "if(window.__ntkEnsureBrowserKeyIdb)return window.__ntkEnsureBrowserKeyIdb(true);"
                + "if(typeof ensureBrowserKeyIdb==='function')return ensureBrowserKeyIdb(true);"
                + "return false;"
                + "}).then(function(ok){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryRegistered:!!ok,keyId:String(window.__ntk_request_key_id||'').slice(0,12)}));}catch(_){}"
                + "if(ok&&typeof directAck==='function')return directAck();"
                + "return false;"
                + "}).then(function(ok){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryDirectAckDone:!!ok,acked:acked(),proofed:ackProofed()}));}catch(_){}"
                + "window.__ntkBrowserKeyFetchRetryBusy=0;"
                + "},function(e){"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryError:String(e)}));}catch(_){}"
                + "window.__ntkBrowserKeyFetchRetryBusy=0;"
                + "});"
                + "},0);"
                + "}"
                + "function installFetchRetry(tag){"
                + "try{if(!window.fetch||window.fetch.__ntkBrowserKeyFetchRetry)return false;var baseFetch=window.fetch;"
                + "window.fetch=function(input,init){"
                + "var ack=isAckRequest(input,init),out=baseFetch.apply(this,arguments);"
                + "try{if(ack&&out&&out.then){return out.then(function(resp){try{if(resp&&resp.status===428){resp.clone().text().then(function(txt){try{var j={};try{j=JSON.parse(txt||'{}');}catch(_){}if(j&&j.error==='browser_key_required')retryAck();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInspectError:String(e)}));}catch(_){}}});}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryOuterError:String(e)}));}catch(_){}}return resp;});}}catch(_){}"
                + "return out;"
                + "};"
                + "try{window.fetch.__ntkNativeString='function fetch() { [native code] }';}catch(_){}"
                + "window.fetch.__ntkBrowserKeyFetchRetry=1;"
                + "try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInstalled:true,tag:tag||'direct'}));}catch(_){}"
                + "return true;}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInstallFetchError:String(e),tag:tag||'direct'}));}catch(_){}return false;}"
                + "}"
                + "installFetchRetry('initial');"
                + "try{var oldInstall=installGuardBeaconBridge;if(oldInstall&&!oldInstall.__ntkBrowserKeyFetchRetryInstallWrap){installGuardBeaconBridge=function(){var out=oldInstall.apply(this,arguments);try{installFetchRetry('after-guard-install');}catch(_){}return out;};installGuardBeaconBridge.__ntkBrowserKeyFetchRetryInstallWrap=1;oldInstall.__ntkBrowserKeyFetchRetryInstallWrap=1;try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInstallWrap:true}));}catch(_){}}}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInstallWrapError:String(e)}));}catch(_){}}"
                + "})();}catch(e){try{window.NtkViewerBridge.onAckState(JSON.stringify({browserKeyFetchRetryInstallError:String(e)}));}catch(_){}}");
    }

    private static String buildViewerBridgeShimScript() {
        return jsChunk("try{(function(){"
                + "var nativeBridge=window.MangaViewerNativeAckBridge||window.MangaViewerNativeViewerBridge||window.__NtkAckBridgeNative||window.__NtkViewerBridgeNative||window.NtkAckBridge||window.NtkViewerBridge;"
                + "function hasMethod(o,n){try{return !!(o&&typeof o[n]==='function');}catch(_){return false;}}"
                + "function call(n,v){try{var b=window.MangaViewerNativeAckBridge||window.MangaViewerNativeViewerBridge||window.__NtkAckBridgeNative||window.__NtkViewerBridgeNative||window.NtkAckBridge||nativeBridge;if(hasMethod(b,n))return b[n](v);}catch(_){}try{var vb=window.NtkViewerBridge;if(vb&&vb!==nativeBridge&&hasMethod(vb,n))return vb[n](v);}catch(_){}return null;}"
                + "if(!window.NtkViewerBridge||!hasMethod(window.NtkViewerBridge,'onAckState')){"
                + "try{if(!window.NtkViewerBridge||typeof window.NtkViewerBridge!=='object')window.NtkViewerBridge={};}catch(_){try{Object.defineProperty(window,'NtkViewerBridge',{value:{},configurable:true,writable:true});}catch(__){}}"
                + "try{window.NtkViewerBridge.onAckState=function(v){return call('onAckState',v);};}catch(_){}"
                + "try{window.NtkViewerBridge.onAckProof=function(v){return call('onAckProof',v);};}catch(_){}"
                + "try{window.NtkViewerBridge.onViewerImages=function(v){return call('onViewerImages',v);};}catch(_){}"
                + "try{window.NtkViewerBridge.getNativeAckChallenge=function(s){return call('getNativeAckChallenge',s);};}catch(_){}"
                + "}"
                + "if(!window.NtkAckBridge||!hasMethod(window.NtkAckBridge,'onAckState')){"
                + "try{if(!window.NtkAckBridge||typeof window.NtkAckBridge!=='object')window.NtkAckBridge={};}catch(_){try{Object.defineProperty(window,'NtkAckBridge',{value:{},configurable:true,writable:true});}catch(__){}}"
                + "try{window.NtkAckBridge.onAckState=function(v){return call('onAckState',v);};}catch(_){}"
                + "try{window.NtkAckBridge.onAckProof=function(v){return call('onAckProof',v);};}catch(_){}"
                + "try{window.NtkAckBridge.onViewerImages=function(v){return call('onViewerImages',v);};}catch(_){}"
                + "try{window.NtkAckBridge.getNativeAckChallenge=function(s){return call('getNativeAckChallenge',s);};}catch(_){}"
                + "}"
                + "try{call('onAckState',JSON.stringify({viewerBridgeShimReady:true,ackBridge:!!window.NtkAckBridge,nativeAckBridge:!!window.__NtkAckBridgeNative,nativeViewerBridge:!!window.__NtkViewerBridgeNative,namedNativeAck:!!window.MangaViewerNativeAckBridge,namedNativeViewer:!!window.MangaViewerNativeViewerBridge,viewerAckState:hasMethod(window.NtkViewerBridge,'onAckState'),ackState:hasMethod(window.NtkAckBridge,'onAckState'),namedAckState:hasMethod(window.MangaViewerNativeAckBridge,'onAckState')}));}catch(_){}"
                + "})();}catch(e){try{var b=window.MangaViewerNativeAckBridge||window.MangaViewerNativeViewerBridge||window.__NtkAckBridgeNative||window.__NtkViewerBridgeNative||window.NtkAckBridge;b&&b.onAckState&&b.onAckState(JSON.stringify({viewerBridgeShimError:String(e)}));}catch(_){}}");
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
        private final boolean finishOnAckProof;
        private final String ackScopePath;
        private volatile boolean ackStateProofRecorded;

        ViewerImageBridge(ViewerImageResult result, Runnable finish, Handler mainHandler,
                          boolean finishOnAckProof, String ackScopePath) {
            this.result = result;
            this.finish = finish;
            this.mainHandler = mainHandler;
            this.finishOnAckProof = finishOnAckProof;
            this.ackScopePath = ackScopePath == null ? "" : ackScopePath;
        }

        @JavascriptInterface
        public void onViewerImages(String value) {
            result.body = value == null ? "" : value;
            Log.d(TAG, "ntk_viewer_images_bridge_result len=" + result.body.length()
                    + ",body=" + (result.body.length() > 240
                    ? result.body.substring(0, 240) : result.body));
            mainHandler.post(finish);
        }

        @JavascriptInterface
        public void onAckState(String value) {
            String text = value == null ? "" : value;
            maybeRecordRequestKeyMaterial(text);
            Log.d(TAG, "ntk_ack_state=" + sanitizeAckStateForLog(text));
            maybeRecordAckStateProof(text);
        }

        @JavascriptInterface
        public String getNativeAckChallenge(String scope) {
            String body = getRecentNativeAckChallengeWaiting(scope, finishOnAckProof ? 450L : 0L);
            Log.d(TAG, "ntk_native_ack_challenge_bridge_get path=" + (scope == null ? "" : scope)
                    + ",bytes=" + body.length());
            return body;
        }

        @JavascriptInterface
        public void onAckProof(String value) {
            String text = value == null ? "" : value;
            Log.d(TAG, "ntk_ack_proof=" + text);
            try {
                if(text.startsWith("{")) {
                    JSONObject object = new JSONObject(text);
                    String scope = object.optString("scope", "");
                    if(scope.length() > 0) {
                        ackStateProofRecorded = true;
                        rememberServerAckSuccess(scope,
                                object.optString("source", "webview-ack-proof"));
                        if(finishOnAckProof)
                            mainHandler.post(finish);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void maybeRecordAckStateProof(String text) {
            if(!finishOnAckProof || ackStateProofRecorded || text == null
                    || !text.startsWith("{"))
                return;
            try {
                JSONObject state = new JSONObject(text);
                if(!state.has("guardBridge"))
                    return;
                if(!"/api/ad/ack".equals(state.optString("path", "")))
                    return;
                if(state.optInt("status", 0) != 200)
                    return;
                JSONObject body = state.optJSONObject("body");
                if(body == null)
                    return;
                if(!body.optBoolean("ok", false)
                        && !body.optBoolean("acked", false)
                        && !"ok".equals(body.optString("status", ""))
                        && !"acked".equals(body.optString("status", "")))
                    return;
                String scope = ackScopePath.length() > 0
                        ? ackScopePath : state.optString("scope", "");
                if(scope.length() == 0)
                    return;
                ackStateProofRecorded = true;
                JSONObject proof = new JSONObject();
                proof.put("scope", scope);
                proof.put("tp", "");
                proof.put("source", "guard-state-ack-200");
                proof.put("path", state.optString("path", ""));
                proof.put("status", state.optInt("status", 0));
                proof.put("tag", state.optString("guardBridge", ""));
                String proofText = proof.toString();
                Log.d(TAG, "ntk_ack_proof=" + proofText);
                rememberServerAckSuccess(scope, "guard-state-ack-200");
                mainHandler.post(finish);
            } catch (Exception ignored) {
            }
        }

        private void maybeRecordRequestKeyMaterial(String text) {
            if(text == null || !text.startsWith("{"))
                return;
            try {
                JSONObject state = new JSONObject(text);
                String keyId = state.optString("keyIdFull",
                        state.optString("keyId", state.optString("requestKeyId", "")));
                String privateJwk = "";
                Object jwkValue = state.opt("privateJwk");
                if(jwkValue instanceof JSONObject)
                    privateJwk = jwkValue.toString();
                else if(jwkValue != null)
                    privateJwk = String.valueOf(jwkValue);
                if(keyId.length() == 0 || privateJwk.length() == 0)
                    return;
                long expiresAt = state.optLong("expiresAt", 0L);
                String scope = state.optString("scope", ackScopePath);
                if(scope.length() == 0)
                    scope = ackScopePath;
                rememberRecentRequestKeyMaterialFromJwk(scope, keyId, privateJwk, expiresAt);
            } catch(Exception ignored) {
            }
        }

        private String sanitizeAckStateForLog(String text) {
            if(text == null || !text.startsWith("{"))
                return text == null ? "" : text;
            try {
                JSONObject state = new JSONObject(text);
                if(state.has("privateJwk"))
                    state.put("privateJwk", "[redacted]");
                if(state.has("keyIdFull"))
                    state.put("keyIdFull", summarizeKeyId(state.optString("keyIdFull", "")));
                return state.toString();
            } catch(Exception ignored) {
                return text.replaceAll("\\\"privateJwk\\\"\\s*:\\s*\\{[^}]*}", "\"privateJwk\":\"[redacted]\"");
            }
        }
    }

    private static final class NativeAckChallenge {
        final String body;
        final long uptimeMs;

        NativeAckChallenge(String body, long uptimeMs) {
            this.body = body;
            this.uptimeMs = uptimeMs;
        }
    }

    private static final class NtkQuicBridge {
        private final Context context;
        private final String userAgent;
        private final String fallbackCookieHeader;
        private final NtkQuicFetcher.Session http2Session;
        private final Map<String, byte[]> guardLoaderByVersion = new ConcurrentHashMap<>();
        private final Map<String, String> adAckCByChallenge = new ConcurrentHashMap<>();
        private final Map<String, String> adAckCByPath = new ConcurrentHashMap<>();
        private final Map<String, List<String>> adAckObservationsByChallenge = new ConcurrentHashMap<>();
        private final Map<String, Long> adCanaryProofByTokenPath = new ConcurrentHashMap<>();
        private NtkQuicFetcher.Session quicSession;
        private String quicSessionHost = "";
        private volatile String lastRequestKeyId = "";
        private java.security.KeyPair viewerBrowserKeyPair;
        private long viewerBrowserKeyExpiresAt;
        private long viewerBrowserKeyServerTimeOffsetMs;
        private String viewerBrowserKeyRegisterUrl = "";
        private String viewerBrowserKeyUserAgent = "";
        private volatile boolean suppressCloudflareFallbackCookies = false;

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

        void suppressCloudflareFallbackCookies() {
            suppressCloudflareFallbackCookies = true;
        }

        @JavascriptInterface
        public String cookie(String url, String name) {
            if(suppressCloudflareFallbackCookies
                    && ("cf_clearance".equalsIgnoreCase(name) || "__cf_bm".equalsIgnoreCase(name)))
                return "";
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
                return remembered;
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
                        + ",remembered=" + remembered
                        + ",ms=" + (SystemClock.uptimeMillis() - started));
                return version.length() > 0 ? version : remembered;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_guard_version_discovery_error " + e);
                return remembered;
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
        public synchronized String ensureViewerBrowserKey(String pageUrl) {
            return ensureViewerBrowserKeyInternal(pageUrl, userAgent);
        }

        @JavascriptInterface
        public synchronized String ensureViewerBrowserKeyForUserAgent(String pageUrl, String requestUserAgent) {
            return ensureViewerBrowserKeyInternal(pageUrl, normalizeBridgeUserAgent(requestUserAgent));
        }

        private String normalizeBridgeUserAgent(String requestUserAgent) {
            if(requestUserAgent != null && requestUserAgent.trim().length() > 0)
                return requestUserAgent.trim();
            return userAgent;
        }

        private synchronized String ensureViewerBrowserKeyInternal(String pageUrl, String requestUserAgent) {
            JSONObject out = new JSONObject();
            try {
                String effectiveUserAgent = normalizeBridgeUserAgent(requestUserAgent);
                long now = System.currentTimeMillis();
                long signedNow = now + viewerBrowserKeyServerTimeOffsetMs;
                if(viewerBrowserKeyPair != null
                        && lastRequestKeyId != null && lastRequestKeyId.length() > 0
                        && effectiveUserAgent.equals(viewerBrowserKeyUserAgent)
                        && (viewerBrowserKeyExpiresAt <= 0 || viewerBrowserKeyExpiresAt - signedNow > 30_000L)) {
                    out.put("ok", true);
                    out.put("keyId", lastRequestKeyId);
                    out.put("cached", true);
                    return out.toString();
                }
                String normalizedPageUrl = normalizeViewerBridgePageUrl(pageUrl);
                if(normalizedPageUrl.length() == 0)
                    throw new IllegalArgumentException("missing page url");
                java.security.KeyPairGenerator generator =
                        java.security.KeyPairGenerator.getInstance("EC");
                generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
                java.security.KeyPair keyPair = generator.generateKeyPair();
                java.security.interfaces.ECPublicKey publicKey =
                        (java.security.interfaces.ECPublicKey) keyPair.getPublic();
                JSONObject jwk = new JSONObject();
                jwk.put("kty", "EC");
                jwk.put("crv", "P-256");
                jwk.put("ext", true);
                JSONArray keyOps = new JSONArray();
                keyOps.put("verify");
                jwk.put("key_ops", keyOps);
                jwk.put("x", base64Url(fixedUnsigned32(publicKey.getW().getAffineX())));
                jwk.put("y", base64Url(fixedUnsigned32(publicKey.getW().getAffineY())));
                JSONObject registerBody = new JSONObject();
                registerBody.put("publicKey", jwk);
                String registerCookieHeader = bridgeCookieHeader(normalizedPageUrl, fallbackCookieHeader,
                        new HashMap<>(), new byte[0]);
                String fp = cookieValue(registerCookieHeader, "ntk_fp");
                if(fp.length() == 0) {
                    fp = generatedNtkFp(normalizedPageUrl, effectiveUserAgent);
                    if(fp.length() > 0) {
                        try {
                            CookieManager.getInstance().setCookie(originFor(normalizedPageUrl),
                                    "ntk_fp=" + fp + "; Path=/; Max-Age=31536000; SameSite=Lax; Secure");
                            CookieManager.getInstance().flush();
                        } catch (Exception ignored) {
                        }
                        registerCookieHeader = mergeCookieHeaders(registerCookieHeader, "ntk_fp=" + fp);
                    }
                }
                registerCookieHeader = ensureViewerBrowserIdentityState(normalizedPageUrl,
                        effectiveUserAgent, registerCookieHeader);
                fp = cookieValue(registerCookieHeader, "ntk_fp");
                String pid = cookieValue(registerCookieHeader, "ntk_pid");
                String vsid = cookieValue(registerCookieHeader, "__vsid");
                String eventId = cookieValue(registerCookieHeader, "__ntk_ev_id");
                String identity = ntkTokenIdentity(cookieValue(registerCookieHeader, "ad_ack_c"));
                if(identity.length() == 0)
                    identity = ntkTokenIdentity(cookieValue(registerCookieHeader, "ad_ack"));
                boolean fpFromIdentity = false;
                if(fp.length() == 0 && identity.length() > 0) {
                    fp = identity;
                    fpFromIdentity = true;
                }
                if(fp.length() > 0) {
                    registerBody.put("fp", fp);
                    registerBody.put("ntkFp", fp);
                    registerBody.put("fingerprint", fp);
                }
                if(pid.length() > 0) {
                    registerBody.put("pid", pid);
                    registerBody.put("ntkPid", pid);
                }
                if(vsid.length() > 0)
                    registerBody.put("vsid", vsid);
                if(eventId.length() > 0)
                    registerBody.put("eventId", eventId);
                byte[] body = registerBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                JSONObject headers = new JSONObject();
                headers.put("content-type", "application/json");
                headers.put("accept", "application/json");
                headers.put("origin", originFor(normalizedPageUrl));
                headers.put("referer", normalizedPageUrl);
                headers.put("user-agent", effectiveUserAgent);
                headers.put("sec-fetch-dest", "empty");
                headers.put("sec-fetch-mode", "cors");
                headers.put("sec-fetch-site", "same-origin");
                headers.put("priority", "u=1, i");
                String raw = request(originFor(normalizedPageUrl) + "/api/client-key/register",
                        "POST", headers.toString(), Base64.encodeToString(body, Base64.NO_WRAP));
                JSONObject response = new JSONObject(raw == null ? "{}" : raw);
                int status = response.optInt("status", 0);
                String text = "";
                if(response.has("bodyBase64")) {
                    byte[] responseBody = Base64.decode(response.optString("bodyBase64", ""), Base64.NO_WRAP);
                    text = new String(responseBody, java.nio.charset.StandardCharsets.UTF_8);
                }
                JSONObject server = new JSONObject(text.trim().startsWith("{") ? text : "{}");
                String keyId = server.optString("keyId", "");
                boolean ok = status == 200 && server.optBoolean("ok", false) && keyId.length() > 0;
                Log.d(TAG, "ntk_viewer_native_browser_key_register status=" + status
                        + ",ok=" + ok
                        + ",keyId=" + shortBridgeValue(keyId)
                        + ",serverNow=" + server.optLong("serverNow", 0L)
                        + ",body=" + shortBridgeValue(text)
                        + ",fpPresent=" + (fp.length() > 0)
                        + ",fpFromIdentity=" + fpFromIdentity
                        + ",pidPresent=" + (pid.length() > 0)
                        + ",vsidPresent=" + (vsid.length() > 0)
                        + ",uaLen=" + effectiveUserAgent.length()
                        + ",uaHash=" + Integer.toHexString(effectiveUserAgent.hashCode())
                        + ",page=" + normalizedPageUrl);
                if(!ok) {
                    out.put("ok", false);
                    out.put("status", status);
                    out.put("body", server);
                    return out.toString();
                }
                viewerBrowserKeyPair = keyPair;
                long localNow = System.currentTimeMillis();
                long serverNow = server.optLong("serverNow", localNow);
                viewerBrowserKeyServerTimeOffsetMs = serverNow - localNow;
                viewerBrowserKeyExpiresAt = server.optLong("expiresAt", serverNow + 3_600_000L);
                viewerBrowserKeyRegisterUrl = normalizedPageUrl;
                viewerBrowserKeyUserAgent = effectiveUserAgent;
                lastRequestKeyId = keyId;
                rememberRecentRequestKeyMaterial(normalizedPageUrl, keyId, keyPair,
                        viewerBrowserKeyServerTimeOffsetMs, viewerBrowserKeyExpiresAt);
                out.put("ok", true);
                out.put("keyId", keyId);
                out.put("expiresAt", viewerBrowserKeyExpiresAt);
                return out.toString();
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_native_browser_key_register_error=" + e);
                try {
                    out.put("ok", false);
                    out.put("error", String.valueOf(e));
                    return out.toString();
                } catch (Exception ignored) {
                    return "{\"ok\":false}";
                }
            }
        }

        private String ensureViewerBrowserIdentityState(String normalizedPageUrl,
                                                        String effectiveUserAgent,
                                                        String cookieHeader) {
            String origin = originFor(normalizedPageUrl);
            String fp = cookieValue(cookieHeader, "ntk_fp");
            String evId = cookieValue(cookieHeader, "__ntk_ev_id");
            boolean generatedEv = false;
            if(!isNtkHexIdentity(evId, 24)) {
                evId = generatedNtkEvId();
                generatedEv = evId.length() > 0;
                if(generatedEv) {
                    try {
                        CookieManager.getInstance().setCookie(origin,
                                "__ntk_ev_id=" + evId
                                        + "; Path=/; Max-Age=31536000; SameSite=Lax; Secure");
                        CookieManager.getInstance().flush();
                    } catch (Exception ignored) {
                    }
                    cookieHeader = mergeCookieHeaders(cookieHeader, "__ntk_ev_id=" + evId);
                }
            }
            int etagStatus = 0;
            String etId = cookieValue(cookieHeader, "__ntk_et_id");
            try {
                JSONObject etagHeaders = new JSONObject();
                etagHeaders.put("accept", "image/gif");
                etagHeaders.put("referer", normalizedPageUrl);
                etagHeaders.put("sec-fetch-dest", "image");
                etagHeaders.put("sec-fetch-mode", "no-cors");
                etagHeaders.put("sec-fetch-site", "same-origin");
                etagHeaders.put("priority", "i");
                String raw = request(origin + "/api/ev/etag", "GET",
                        etagHeaders.toString(), "");
                JSONObject response = new JSONObject(raw == null ? "{}" : raw);
                etagStatus = response.optInt("status", 0);
                String responseEtag = bridgeResponseHeader(response, "etag");
                String normalizedEt = normalizeQuotedNtkIdentity(responseEtag);
                if(isNtkHexIdentity(normalizedEt, 16)) {
                    etId = normalizedEt;
                    try {
                        CookieManager.getInstance().setCookie(origin,
                                "__ntk_et_id=" + etId
                                        + "; Path=/; Max-Age=31536000; SameSite=Lax; Secure");
                        CookieManager.getInstance().flush();
                    } catch (Exception ignored) {
                    }
                    cookieHeader = mergeCookieHeaders(cookieHeader, "__ntk_et_id=" + etId);
                }
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_identity_etag_error=" + e
                        + ",page=" + normalizedPageUrl);
            }
            int syncStatus = 0;
            String syncBody = "";
            if(isNtkHexIdentity(evId, 24)) {
                try {
                    JSONObject syncHeaders = new JSONObject();
                    syncHeaders.put("content-type", "application/json");
                    syncHeaders.put("accept", "application/json");
                    syncHeaders.put("origin", origin);
                    syncHeaders.put("referer", normalizedPageUrl);
                    syncHeaders.put("sec-fetch-dest", "empty");
                    syncHeaders.put("sec-fetch-mode", "cors");
                    syncHeaders.put("sec-fetch-site", "same-origin");
                    syncHeaders.put("priority", "u=1, i");
                    JSONObject syncPayload = new JSONObject();
                    syncPayload.put("evId", evId);
                    byte[] syncBytes = syncPayload.toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    String raw = request(origin + "/api/ev/sync", "POST",
                            syncHeaders.toString(),
                            Base64.encodeToString(syncBytes, Base64.NO_WRAP));
                    JSONObject response = new JSONObject(raw == null ? "{}" : raw);
                    syncStatus = response.optInt("status", 0);
                    if(response.has("bodyBase64")) {
                        syncBody = new String(Base64.decode(response.optString("bodyBase64", ""),
                                Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "ntk_viewer_identity_sync_error=" + e
                            + ",page=" + normalizedPageUrl);
                }
            }
            String refreshed = bridgeCookieHeader(normalizedPageUrl, fallbackCookieHeader,
                    new HashMap<>(), new byte[0]);
            if(refreshed.length() > 0)
                cookieHeader = mergeCookieHeaders(cookieHeader, refreshed);
            Log.d(TAG, "ntk_viewer_identity_sync"
                    + " etagStatus=" + etagStatus
                    + ",syncStatus=" + syncStatus
                    + ",fpPresent=" + (fp.length() > 0
                    || cookieValue(cookieHeader, "ntk_fp").length() > 0)
                    + ",evPresent=" + (cookieValue(cookieHeader, "__ntk_ev_id").length() > 0)
                    + ",etPresent=" + (cookieValue(cookieHeader, "__ntk_et_id").length() > 0)
                    + ",generatedEv=" + generatedEv
                    + ",body=" + shortBridgeValue(syncBody)
                    + ",uaLen=" + effectiveUserAgent.length()
                    + ",page=" + normalizedPageUrl);
            return cookieHeader;
        }

        @JavascriptInterface
        public synchronized String signViewerRequest(String method, String signedPath,
                                                     String signedScope, String bodyText) {
            return signViewerRequestInternal(method, signedPath, signedScope, bodyText, "p1363");
        }

        @JavascriptInterface
        public synchronized String signViewerRequestFormat(String method, String signedPath,
                                                           String signedScope, String bodyText,
                                                           String signatureFormat) {
            return signViewerRequestInternal(method, signedPath, signedScope, bodyText,
                    signatureFormat == null ? "" : signatureFormat);
        }

        private synchronized String signViewerRequestInternal(String method, String signedPath,
                                                              String signedScope, String bodyText,
                                                              String signatureFormat) {
            JSONObject out = new JSONObject();
            try {
                if(viewerBrowserKeyPair == null
                        || lastRequestKeyId == null || lastRequestKeyId.length() == 0) {
                    if(viewerBrowserKeyRegisterUrl != null && viewerBrowserKeyRegisterUrl.length() > 0)
                        ensureViewerBrowserKey(viewerBrowserKeyRegisterUrl);
                }
                if(viewerBrowserKeyPair == null
                        || lastRequestKeyId == null || lastRequestKeyId.length() == 0)
                    throw new IllegalStateException("missing browser key");
                String normalizedMethod = method == null || method.length() == 0
                        ? "POST" : method.toUpperCase(Locale.US);
                String path = signedPath == null ? "" : signedPath;
                String scope = signedScope == null ? "" : signedScope;
                String body = bodyText == null ? "" : bodyText;
                String timestamp = String.valueOf(System.currentTimeMillis()
                        + viewerBrowserKeyServerTimeOffsetMs);
                byte[] nonceBytes = new byte[24];
                new java.security.SecureRandom().nextBytes(nonceBytes);
                String nonce = base64Url(nonceBytes);
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                String bodyHash = base64Url(digest.digest(
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                String base = "ntk-brsig-v1\n" + normalizedMethod + "\n" + path + "\n"
                        + scope + "\n" + lastRequestKeyId + "\n" + timestamp + "\n"
                        + nonce + "\n" + bodyHash;
                boolean der = "der".equalsIgnoreCase(signatureFormat);
                byte[] signedBytes = der
                        ? signDer((java.security.interfaces.ECPrivateKey) viewerBrowserKeyPair.getPrivate(),
                                base.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : signP1363((java.security.interfaces.ECPrivateKey) viewerBrowserKeyPair.getPrivate(),
                                base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                boolean lowSAdjusted = !der && normalizeP1363LowS(signedBytes);
                JSONObject headers = new JSONObject();
                headers.put("x-ntk-key-id", lastRequestKeyId);
                headers.put("x-ntk-ts", timestamp);
                headers.put("x-ntk-nonce", nonce);
                String signatureText = base64Url(signedBytes);
                headers.put("x-ntk-sig", signatureText);
                out.put("ok", true);
                out.put("keyId", lastRequestKeyId);
                out.put("headers", headers);
                Log.d(TAG, "ntk_viewer_native_browser_key_signed keyId="
                        + shortBridgeValue(lastRequestKeyId)
                        + ",path=" + path
                        + ",scope=" + scope
                        + ",format=" + (der ? "der" : "p1363")
                        + ",sigLen=" + signedBytes.length
                        + ",sigTextLen=" + signatureText.length()
                        + ",bodyLen=" + body.length()
                        + ",serverOffsetMs=" + viewerBrowserKeyServerTimeOffsetMs
                        + ",lowSAdjusted=" + lowSAdjusted);
                return out.toString();
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_native_browser_key_sign_error=" + e
                        + ",path=" + signedPath
                        + ",scope=" + signedScope);
                try {
                    out.put("ok", false);
                    out.put("error", String.valueOf(e));
                    return out.toString();
                } catch (Exception ignored) {
                    return "{\"ok\":false}";
                }
            }
        }

        private static String normalizeViewerBridgePageUrl(String pageUrl) {
            if(pageUrl == null)
                return "";
            try {
                URI uri = URI.create(pageUrl);
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
                    return "";
                String rawPath = uri.getRawPath();
                String normalized = uri.getScheme() + "://" + uri.getHost()
                        + (rawPath == null || rawPath.length() == 0 ? "/" : rawPath);
                if(uri.getRawQuery() != null && uri.getRawQuery().length() > 0)
                    normalized += "?" + uri.getRawQuery();
                return normalized;
            } catch (Exception e) {
                return "";
            }
        }

        private static String originFor(String url) {
            try {
                URI uri = URI.create(url);
                if(uri.getScheme() == null || uri.getHost() == null)
                    return "";
                return uri.getScheme() + "://" + uri.getHost();
            } catch (Exception e) {
                return "";
            }
        }

        private static String base64Url(byte[] bytes) {
            return Base64.encodeToString(bytes,
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        }

        private static String shortBridgeValue(String value) {
            if(value == null)
                return "";
            return value.length() <= 12 ? value : value.substring(0, 12);
        }

        private static byte[] fixedUnsigned32(java.math.BigInteger value) {
            byte[] raw = value == null ? new byte[0] : value.toByteArray();
            byte[] out = new byte[32];
            int copy = Math.min(raw.length, 32);
            System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
            return out;
        }

        private static byte[] signP1363(java.security.interfaces.ECPrivateKey privateKey,
                                        byte[] message) throws Exception {
            try {
                java.security.Signature signature =
                        java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
                signature.initSign(privateKey);
                signature.update(message);
                return signature.sign();
            } catch (java.security.NoSuchAlgorithmException ignored) {
                java.security.Signature signature =
                        java.security.Signature.getInstance("SHA256withECDSA");
                signature.initSign(privateKey);
                signature.update(message);
                return derToP1363(signature.sign());
            }
        }

        private static byte[] signDer(java.security.interfaces.ECPrivateKey privateKey,
                                      byte[] message) throws Exception {
            java.security.Signature signature =
                    java.security.Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        }

        private static byte[] derToP1363(byte[] der) throws Exception {
            if(der == null || der.length < 8 || der[0] != 0x30)
                throw new IllegalArgumentException("bad der signature");
            int index = 2;
            if((der[1] & 0xff) > 0x80)
                index = 2 + (der[1] & 0x7f);
            if(index >= der.length || der[index++] != 0x02)
                throw new IllegalArgumentException("bad der r");
            int rLen = der[index++] & 0xff;
            byte[] r = new byte[rLen];
            System.arraycopy(der, index, r, 0, rLen);
            index += rLen;
            if(index >= der.length || der[index++] != 0x02)
                throw new IllegalArgumentException("bad der s");
            int sLen = der[index++] & 0xff;
            byte[] s = new byte[sLen];
            System.arraycopy(der, index, s, 0, sLen);
            byte[] out = new byte[64];
            byte[] rr = fixedUnsigned32(new java.math.BigInteger(1, r));
            byte[] ss = fixedUnsigned32(new java.math.BigInteger(1, s));
            System.arraycopy(rr, 0, out, 0, 32);
            System.arraycopy(ss, 0, out, 32, 32);
            return out;
        }

        private static boolean normalizeP1363LowS(byte[] signature) {
            if(signature == null || signature.length != 64)
                return false;
            byte[] sBytes = new byte[32];
            System.arraycopy(signature, 32, sBytes, 0, 32);
            java.math.BigInteger s = new java.math.BigInteger(1, sBytes);
            if(s.compareTo(P256_HALF_ORDER) <= 0)
                return false;
            byte[] normalized = fixedUnsigned32(P256_ORDER.subtract(s));
            System.arraycopy(normalized, 0, signature, 32, 32);
            return true;
        }

        private String bridgeRequestUserAgent(Map<String, String> headers) {
            if(headers != null) {
                for(Map.Entry<String, String> entry : headers.entrySet()) {
                    if(entry.getKey() != null
                            && "user-agent".equalsIgnoreCase(entry.getKey())
                            && entry.getValue() != null
                            && entry.getValue().trim().length() > 0)
                        return entry.getValue().trim();
                }
            }
            return userAgent;
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String bodyBase64) {
            if(!isNtkProtectedHttpsUrl(url))
                return bridgeError("unsupported url");
            try {
                String cachedGuard = isNtkAdGuardModuleUrl(url) ? cachedGuardModuleResponse(url) : null;
                if(cachedGuard != null)
                    return cachedGuard;
                if(!NtkQuicFetcher.isAvailable())
                    return bridgeError("quic unavailable");
                byte[] body = decodeBridgeBase64(bodyBase64);
                body = augmentAdAckBodyForBridge(url, method, body);
                Map<String, String> headers = parseBridgeHeaders(headersJson);
                if(isNtkAdAckPost(url, method)
                        && !adAckBodyHasRequestKey(body)
                        && !adAckBodyHasUsableProof(body)) {
                    Log.d(TAG, "ntk_viewer_ad_ack_blocked_missing_request_key url=" + url);
                    return bridgeJsonStatusResponse(428, "browser_key_required");
                }
                normalizeBridgeNavigationHeaders(url, headers, body);
                applyBridgeBrowserFetchHeaders(url, method, headers);
                String cookieHeader = bridgeCookieHeader(url, fallbackCookieHeader, headers, body);
                if(suppressCloudflareFallbackCookies && !shouldUseHttp2ForNtkAdControlPost(url, method))
                    cookieHeader = cookieHeaderWithoutCloudflare(cookieHeader);
                cookieHeader = cookieHeaderWithoutSatisfiedAdAckForChallenge(url, method,
                        headers, body, cookieHeader);
                cookieHeader = cookieHeaderWithScopedAdAckC(url, method, body, cookieHeader);
                if(isNtkAdCanaryPost(url, method)) {
                    String freshCanaryCookieHeader = cookieHeaderWithoutNames(cookieHeader, "ad_guard_l");
                    if(!freshCanaryCookieHeader.equals(cookieHeader))
                        Log.d(TAG, "ntk_viewer_ad_bridge_canary_stale_guard_cookie_stripped url="
                                + url);
                    cookieHeader = freshCanaryCookieHeader;
                }
                String requestUserAgent = bridgeRequestUserAgent(headers);
                removeHeaderIgnoreCase(headers, "cookie");
                removeHeaderIgnoreCase(headers, "user-agent");
                logBridgeRequest(url, method, headers, body, cookieHeader);
                if("GET".equalsIgnoreCase(method)
                        && isNtkMetricImageUrl(url)
                        && hasRecentMetricImageHit(url)) {
                    Log.d(TAG, "ntk_viewer_quic_bridge_metric_recent_hit_network_refresh url=" + url);
                }
                if(isNtkMetricsBeaconPost(url, method)) {
                    sendMetricsBeaconAsync(url, headers, cookieHeader, body);
                    return bridgeJsonOkResponse();
                }
                boolean adControlPost = shouldUseHttp2ForNtkAdControlPost(url, method);
                if(isNtkAdAckPost(url, method))
                    cookieHeader = submitCanaryBeforeAdAck(url, headers, cookieHeader, body);
                NtkQuicFetcher.Result result = null;
                if(isNtkAdAckPost(url, method)) {
                    Log.d(TAG, "ntk_viewer_ad_bridge_native_submit_skip_bridge_authoritative url="
                            + url);
                }
                if(result == null) {
                    if(adControlPost) {
                        long startMs = SystemClock.uptimeMillis();
                        NtkQuicFetcher.Session primarySession = quicControlSession(url);
                        result = primarySession == null
                                ? NtkQuicFetcher.fetch(MainApplication.appContext,
                                        url, requestUserAgent, cookieHeader, headers,
                                        method, body, 2500L)
                                : primarySession.fetch(url, requestUserAgent, cookieHeader, headers,
                                        method, body, 2500L);
                        Log.d(TAG, "ntk_viewer_ad_bridge_quic_first code="
                                + (result == null ? -1 : result.code)
                                + ",error=" + (result == null ? "null" : result.error)
                                + ",elapsedMs=" + (SystemClock.uptimeMillis() - startMs)
                                + ",session=" + (primarySession != null)
                                + ",url=" + url);
                        if(result == null || result.error != null || result.code >= 400
                                || isSignedAckBrowserKeyRequired(url, method, headers, result)) {
                            long retryStartMs = SystemClock.uptimeMillis();
                            NtkQuicFetcher.Result retry = http2Session == null
                                    ? NtkQuicFetcher.fetchHttp2Only(MainApplication.appContext,
                                            url, requestUserAgent, cookieHeader, headers, method, body, 1800L)
                                    : http2Session.fetch(url, requestUserAgent, cookieHeader, headers,
                                            method, body, 1800L);
                            Log.d(TAG, "ntk_viewer_ad_bridge_http2_retry code="
                                    + (retry == null ? -1 : retry.code)
                                    + ",error=" + (retry == null ? "null" : retry.error)
                                    + ",elapsedMs=" + (SystemClock.uptimeMillis() - retryStartMs)
                                    + ",reason=" + (isSignedAckBrowserKeyRequired(url, method, headers, result)
                                    ? "signed_ack_browser_key_required"
                                    : result != null && result.error == null && result.code >= 400
                                    ? "ad_control_http_" + result.code : "transport")
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
                        long timeoutMs = isNtkAdGuardModuleUrl(url) ? 2600L : 15000L;
                        result = NtkQuicFetcher.fetch(MainApplication.appContext,
                                url, requestUserAgent, cookieHeader, headers,
                                method, body, timeoutMs);
                        if(method != null && "POST".equalsIgnoreCase(method)
                                && URI.create(url) != null
                                && "/api/client-key/register".equals(URI.create(url).getPath())
                                && result != null && result.error == null && result.code == 403) {
                            long retryStartMs = SystemClock.uptimeMillis();
                            NtkQuicFetcher.Result retry = http2Session == null
                                    ? NtkQuicFetcher.fetchHttp2Only(MainApplication.appContext,
                                            url, requestUserAgent, cookieHeader, headers, method, body, 6000L)
                                    : http2Session.fetch(url, requestUserAgent, cookieHeader,
                                            headers, method, body, 6000L);
                            Log.d(TAG, "ntk_viewer_client_key_http2_retry code="
                                    + (retry == null ? -1 : retry.code)
                                    + ",error=" + (retry == null ? "null" : retry.error)
                                    + ",elapsedMs=" + (SystemClock.uptimeMillis() - retryStartMs)
                                    + ",url=" + url);
                            if(retry != null && retry.error == null
                                    && (result == null || retry.code < result.code))
                                result = retry;
                        }
                        if(isSignedViewerImagesPost(url, method, headers)
                                && result != null && result.error == null && result.code == 403) {
                            long retryStartMs = SystemClock.uptimeMillis();
                            NtkQuicFetcher.Result retry = http2Session == null
                                    ? NtkQuicFetcher.fetchHttp2Only(MainApplication.appContext,
                                            url, requestUserAgent, cookieHeader, headers, method, body, 6000L)
                                    : http2Session.fetch(url, requestUserAgent, cookieHeader,
                                            headers, method, body, 6000L);
                            Log.d(TAG, "ntk_viewer_image_bridge_http2_retry code="
                                    + (retry == null ? -1 : retry.code)
                                    + ",error=" + (retry == null ? "null" : retry.error)
                                    + ",elapsedMs=" + (SystemClock.uptimeMillis() - retryStartMs)
                                    + ",url=" + url);
                            if(retry != null && retry.error == null)
                                result = retry;
                        }
                    }
                }
                if(result == null)
                    return bridgeError("empty result");
                if(result.error != null) {
                    String localGuard = localGuardModuleResponse(url);
                    if(localGuard != null)
                        return localGuard;
                    return bridgeError(String.valueOf(result.error));
                }
                if(isNtkAdAckPost(url, method)
                        && result.code == 400
                        && result.bodyBytes != null
                        && new String(result.bodyBytes, java.nio.charset.StandardCharsets.UTF_8)
                        .contains("\"missing_canary\"")) {
                    cookieHeader = submitCanaryBeforeAdAck(url, headers, cookieHeader, body);
                    try {
                        Thread.sleep(220L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    long retryStartMs = SystemClock.uptimeMillis();
                    NtkQuicFetcher.Session retrySession = quicControlSession(url);
                    NtkQuicFetcher.Result retry = retrySession == null
                            ? NtkQuicFetcher.fetch(MainApplication.appContext,
                                    url, requestUserAgent, cookieHeader, headers, method, body, 2500L)
                            : retrySession.fetch(url, requestUserAgent, cookieHeader, headers,
                                    method, body, 2500L);
                    Log.d(TAG, "ntk_viewer_ad_bridge_missing_canary_retry code="
                            + (retry == null ? -1 : retry.code)
                            + ",error=" + (retry == null ? "null" : retry.error)
                            + ",elapsedMs=" + (SystemClock.uptimeMillis() - retryStartMs)
                            + ",session=" + (retrySession != null)
                            + ",url=" + url);
                    if(retry != null && retry.error == null)
                        result = retry;
                }
                String localGuard = result.code >= 400 ? localGuardModuleResponse(url) : null;
                if(localGuard != null)
                    return localGuard;
                rememberGuardModuleResponse(url, result.bodyBytes);
                if(isNtkAdGuardModuleUrl(url)) {
                    String strippedGuard = strippedVersionedGuardJsResponse(url, result);
                    if(strippedGuard != null)
                        return strippedGuard;
                    String decodedGuard = decodedVersionedGuardWasmResponse(url, result);
                    if(decodedGuard != null)
                        return decodedGuard;
                }
                applyWebViewCookies(url, result, !adControlPost);
                syncBridgeAckCookies(url, body, result);
                rememberSuccessfulAdCanary(url, method, body, result);
                rememberSuccessfulAdAck(url, method, body, result);
                rememberBridgeRequestKey(url, method, headers, body, result);
                rememberScopedAdAckC(url, method, body, result);
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_viewer_quic_bridge method=" + method
                            + ",code=" + result.code
                            + ",len=" + result.bodyBytes.length
                            + ",url=" + url);
                logAdControlBridgeResponse(url, method, result);
                rememberAdControlCloudflareChallenge(url, method, result);
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
                Log.d(TAG, "ntk_viewer_quic_bridge_error method=" + method
                        + ",url=" + url
                        + ",error=" + e);
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
                            String cachedGuard = isNtkAdGuardModuleUrl(url) ? cachedGuardModuleResponse(url) : null;
                            if(cachedGuard != null) {
                                JSONObject cached = new JSONObject(cachedGuard);
                                item.put("ok", cached.optBoolean("ok", false));
                                item.put("status", cached.optInt("status", 0));
                                item.put("error", JSONObject.NULL);
                                item.put("len", Base64.decode(cached.optString("bodyBase64", ""),
                                        Base64.DEFAULT).length);
                                item.put("headers", cached.optJSONObject("headers"));
                                item.put("bodyBase64", cached.optString("bodyBase64", ""));
                                item.put("source", "cached_guard");
                                return item;
                            }
                            Map<String, String> headers = new HashMap<>(baseHeaders);
                            String cookieHeader = bridgeCookieHeader(url, fallbackCookieHeader, headers, new byte[0]);
                            removeHeaderIgnoreCase(headers, "cookie");
                            long timeoutMs = isNtkAdGuardModuleUrl(url) ? 6500L : 2200L;
                            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, userAgent,
                                    cookieHeader, headers, "GET", null, timeoutMs);
                            if(result != null)
                                applyWebViewCookies(url, result);
                            item.put("ok", result != null && result.error == null
                                    && result.code >= 200 && result.code < 400);
                            item.put("status", result == null ? 0 : result.code);
                            item.put("error", result == null ? "null" : String.valueOf(result.error));
                            item.put("len", result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length);
                            if(result != null && result.bodyBytes != null) {
                                rememberGuardModuleResponse(url, result.bodyBytes);
                                item.put("headers", new JSONObject(responseHeaders(result.headers)));
                                item.put("bodyBase64", Base64.encodeToString(result.bodyBytes, Base64.NO_WRAP));
                            }
                            return item;
                        }));
                    }
                    for(java.util.concurrent.Future<JSONObject> future : futures) {
                        try {
                            results.put(future.get(7200L, TimeUnit.MILLISECONDS));
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

        private static boolean isNtkMetricsBeaconPost(String url, String method) {
            if(method == null || !"POST".equalsIgnoreCase(method))
                return false;
            try {
                return "/api/m/ev".equals(URI.create(url).getPath());
            } catch (Exception e) {
                return false;
            }
        }

        private void sendMetricsBeaconAsync(String url, Map<String, String> headers,
                                            String cookieHeader, byte[] body) {
            final Map<String, String> asyncHeaders = new HashMap<>(headers);
            final byte[] asyncBody = body == null ? new byte[0] : java.util.Arrays.copyOf(body, body.length);
            final String asyncCookieHeader = cookieHeader == null ? "" : cookieHeader;
            new Thread(() -> {
                long startMs = SystemClock.uptimeMillis();
                try {
                    NtkQuicFetcher.Session session = quicControlSession(url);
                    NtkQuicFetcher.Result result = session == null
                            ? NtkQuicFetcher.fetch(MainApplication.appContext, url, userAgent,
                                    asyncCookieHeader, asyncHeaders, "POST", asyncBody, 1800L)
                            : session.fetch(url, userAgent, asyncCookieHeader, asyncHeaders,
                                    "POST", asyncBody, 1800L);
                    if(result != null)
                        applyWebViewCookies(url, result);
                    Log.d(TAG, "ntk_viewer_metrics_bridge_async code="
                            + (result == null ? -1 : result.code)
                            + ",error=" + (result == null ? "null" : result.error)
                            + ",elapsedMs=" + (SystemClock.uptimeMillis() - startMs)
                            + ",url=" + url);
                } catch (Exception e) {
                    Log.d(TAG, "ntk_viewer_metrics_bridge_async_error " + e
                            + ",elapsedMs=" + (SystemClock.uptimeMillis() - startMs)
                            + ",url=" + url);
                }
            }, "ntk-metrics-bridge").start();
        }

        private static boolean isNtkAdAckPost(String url, String method) {
            if(method == null || !"POST".equalsIgnoreCase(method))
                return false;
            try {
                return "/api/ad/ack".equals(URI.create(url).getPath());
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isNtkAdChallengePost(String url, String method) {
            if(method == null || !"POST".equalsIgnoreCase(method))
                return false;
            try {
                return "/api/ad/challenge".equals(URI.create(url).getPath());
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isNtkAdCanaryPost(String url, String method) {
            if(method == null || !"POST".equalsIgnoreCase(method))
                return false;
            try {
                return "/api/ad/canary".equals(URI.create(url).getPath());
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isSignedViewerImagesPost(String url, String method,
                                                        Map<String, String> headers) {
            if(method == null || !"POST".equalsIgnoreCase(method) || headers == null
                    || !hasHeaderIgnoreCase(headers, "x-ntk-key-id"))
                return false;
            try {
                String path = URI.create(url).getPath();
                return "/api/manhwa-images".equals(path)
                        || "/api/webtoon-images".equals(path)
                        || "/api/manga-images".equals(path);
            } catch (Exception e) {
                return false;
            }
        }

        private String submitCanaryBeforeAdAck(String ackUrl, Map<String, String> ackHeaders,
                                               String cookieHeader, byte[] ackBody) {
            try {
                if(ackBody == null || ackBody.length == 0)
                    return cookieHeader;
                JSONObject ack = new JSONObject(new String(ackBody, java.nio.charset.StandardCharsets.UTF_8));
                String token = ack.optString("challengeToken", "");
                if(token.length() == 0)
                    token = ack.optString("token", "");
                String path = ack.optString("path", "");
                if(token.length() == 0 || path.length() == 0)
                    return cookieHeader;
                URI uri = URI.create(ackUrl);
                String origin = uri.getScheme() + "://" + uri.getHost();
                boolean recentProof = hasRecentAdCanaryProof(path, token);
                if(recentProof)
                    Log.d(TAG, "ntk_viewer_ad_bridge_canary_before_ack_reconfirm path=" + path
                            + ",reason=token_proof,tokenLen=" + token.length());
                String canaryUrl = origin + "/api/ad/canary";
                JSONObject canary = new JSONObject();
                canary.put("adGuardLoaded", true);
                canary.put("adAckCanary", true);
                canary.put("challengeToken", token);
                canary.put("token", token);
                canary.put("path", path);
                String requestKeyId = ack.optString("requestKeyId", "");
                if(requestKeyId.length() > 0)
                    canary.put("requestKeyId", requestKeyId);
                JSONArray observationUrls = ack.optJSONArray("observationUrls");
                if(observationUrls != null && observationUrls.length() > 0)
                    canary.put("observationUrls", observationUrls);
                if(ack.has("total"))
                    canary.put("total", ack.optInt("total"));
                if(ack.has("visible"))
                    canary.put("visible", ack.optInt("visible"));
                byte[] body = canary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Map<String, String> headers = new HashMap<>();
                headers.put("content-type", "application/json");
                headers.put("accept", "application/json");
                headers.put("origin", origin);
                headers.put("referer", origin + path);
                if(requestKeyId.length() > 0)
                    headers.put("x-ntk-key-id", requestKeyId);
                String canaryCookieHeader = cookieHeaderWithoutNames(cookieHeader, "ad_guard_l");
                if(!canaryCookieHeader.equals(cookieHeader))
                    Log.d(TAG, "ntk_viewer_ad_bridge_canary_before_ack_stale_guard_cookie_stripped path="
                            + path);
                long startMs = SystemClock.uptimeMillis();
                NtkQuicFetcher.Session session = quicControlSession(canaryUrl);
                NtkQuicFetcher.Result result = session == null
                        ? NtkQuicFetcher.fetch(context, canaryUrl, userAgent,
                                canaryCookieHeader, headers, "POST", body, 2600L)
                        : session.fetch(canaryUrl, userAgent, canaryCookieHeader,
                                headers, "POST", body, 2600L);
                if(result == null || result.error != null || result.code >= 400) {
                    long retryStartMs = SystemClock.uptimeMillis();
                    NtkQuicFetcher.Result retry = http2Session == null
                            ? NtkQuicFetcher.fetchHttp2Only(context, canaryUrl, userAgent,
                                    canaryCookieHeader, headers, "POST", body, 2600L)
                            : http2Session.fetch(canaryUrl, userAgent, canaryCookieHeader,
                                    headers, "POST", body, 2600L);
                    Log.d(TAG, "ntk_viewer_ad_bridge_canary_before_ack_http2_retry path="
                            + path
                            + ",code=" + (retry == null ? 0 : retry.code)
                            + ",error=" + (retry == null ? "null" : retry.error)
                            + ",ms=" + (SystemClock.uptimeMillis() - retryStartMs));
                    if(retry != null && retry.error == null
                            && (result == null || result.error != null || retry.code < result.code))
                        result = retry;
                }
                if(result != null)
                    applyWebViewCookies(canaryUrl, result, false);
                rememberSuccessfulAdCanary(canaryUrl, "POST", body, result);
                String responseBody = result == null || result.body == null ? "" : result.body;
                responseBody = responseBody.replace('\n', ' ').replace('\r', ' ');
                if(responseBody.length() > 180)
                    responseBody = responseBody.substring(0, 180);
                Log.d(TAG, "ntk_viewer_ad_bridge_canary_before_ack path=" + path
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? "null" : result.error)
                        + ",ms=" + (SystemClock.uptimeMillis() - startMs)
                        + ",setCookies=" + (result == null ? 0 : result.setCookies().size())
                        + ",cookieNames=" + (result == null ? "" : cookieNames(result.setCookies()))
                        + ",body=" + responseBody);
                String refreshedCookieHeader = bridgeCookieHeader(ackUrl, fallbackCookieHeader,
                        ackHeaders == null ? new HashMap<>() : new HashMap<>(ackHeaders), ackBody);
                String resultCookieHeader = setCookiesAsHeader(result);
                String mergedCookieHeader = mergeCookieHeaders(
                        mergeCookieHeaders(cookieHeader, resultCookieHeader),
                        refreshedCookieHeader);
                return cookieHeaderWithScopedAdAckC(ackUrl, "POST", ackBody, mergedCookieHeader);
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_ad_bridge_canary_before_ack_error " + e);
                return cookieHeader;
            }
        }

        private String adCanaryProofKey(String path, String token) {
            if(path == null || token == null || path.length() == 0 || token.length() == 0)
                return "";
            return path + "|" + token;
        }

        private boolean hasRecentAdCanaryProof(String path, String token) {
            String key = adCanaryProofKey(path, token);
            if(key.length() == 0)
                return false;
            Long seenAt = adCanaryProofByTokenPath.get(key);
            if(seenAt == null)
                return false;
            long age = SystemClock.uptimeMillis() - seenAt;
            if(age >= 0L && age <= 10_000L)
                return true;
            adCanaryProofByTokenPath.remove(key, seenAt);
            return false;
        }

        private void rememberSuccessfulAdCanary(String url, String method, byte[] requestBody,
                                                NtkQuicFetcher.Result result) {
            if(method == null || !"POST".equalsIgnoreCase(method) || requestBody == null
                    || result == null || result.error != null || result.code != 200)
                return;
            try {
                URI uri = URI.create(url);
                if(!"/api/ad/canary".equals(uri.getPath()))
                    return;
                JSONObject request = new JSONObject(new String(requestBody,
                        java.nio.charset.StandardCharsets.UTF_8));
                String token = request.optString("challengeToken", "");
                if(token.length() == 0)
                    token = request.optString("token", "");
                String path = request.optString("path", "");
                if(token.length() == 0 || path.length() == 0)
                    return;
                String bodyText = result.body != null ? result.body
                        : result.bodyBytes == null ? ""
                        : new String(result.bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                JSONObject response = new JSONObject(bodyText.length() == 0 ? "{}" : bodyText);
                if(!response.optBoolean("ok", false))
                    return;
                String key = adCanaryProofKey(path, token);
                if(key.length() == 0)
                    return;
                adCanaryProofByTokenPath.put(key, SystemClock.uptimeMillis());
                Log.d(TAG, "ntk_viewer_ad_bridge_canary_recorded path=" + path
                        + ",tokenLen=" + token.length()
                        + ",cookies=" + result.setCookies().size());
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_ad_bridge_canary_record_error " + e);
            }
        }

        private static String setCookiesAsHeader(NtkQuicFetcher.Result result) {
            if(result == null || result.setCookies() == null || result.setCookies().isEmpty())
                return "";
            StringBuilder builder = new StringBuilder();
            for(String cookie : result.setCookies()) {
                if(cookie == null)
                    continue;
                String first = cookie.split(";", 2)[0].trim();
                if(first.indexOf('=') <= 0)
                    continue;
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(first);
            }
            return builder.toString();
        }

        private void rememberScopedAdAckC(String url, String method, byte[] requestBody,
                                          NtkQuicFetcher.Result result) {
            if(result == null || result.error != null || result.code < 200 || result.code >= 300
                    || requestBody == null || requestBody.length == 0)
                return;
            try {
                if(method == null || !"POST".equalsIgnoreCase(method)
                        || !"/api/ad/challenge".equals(URI.create(url).getPath()))
                    return;
                JSONObject request = new JSONObject(new String(requestBody,
                        java.nio.charset.StandardCharsets.UTF_8));
                String path = request.optString("path", "");
                String adAckC = "";
                if(result.setCookies() != null) {
                    for(String cookie : result.setCookies()) {
                        String value = cookieValue(cookie, "ad_ack_c");
                        if(value.length() > 0) {
                            adAckC = value;
                            break;
                        }
                    }
                }
                if(path.length() == 0 || adAckC.length() == 0)
                    return;
                adAckCByPath.put(path, adAckC);
                NtkWebViewFallbackManager.rememberScopedAdAckC(path, adAckC, "bridge-challenge");
                rememberServerAckSuccess(path, "bridge-challenge-ad-ack-cookie-200");
                JSONObject response = new JSONObject(result.body == null ? "{}" : result.body);
                JSONObject challenge = response.optJSONObject("challenge");
                String token = challenge == null ? "" : challenge.optString("token", "");
                if(token.length() == 0) {
                    Log.d(TAG, "ntk_viewer_ad_ack_c_scoped_store path=" + path
                            + ",token=false");
                    return;
                }
                JSONArray impressions = challenge.optJSONArray("impressionUrls");
                JSONArray cleanImpressions = cleanObservationUrls(impressions);
                if(cleanImpressions.length() > 0)
                    adAckObservationsByChallenge.put(adAckChallengeKey(path, token),
                            jsonArrayToStringList(cleanImpressions));
                adAckCByChallenge.put(adAckChallengeKey(path, token), adAckC);
                Log.d(TAG, "ntk_viewer_ad_ack_c_scoped_store path=" + path
                        + ",serverProof=true"
                        + ",impressions=" + cleanImpressions.length());
            } catch (Exception ignored) {
            }
        }

        private void rememberSuccessfulAdAck(String url, String method, byte[] requestBody,
                                             NtkQuicFetcher.Result result) {
            if(!isNtkAdAckPost(url, method) || result == null || result.error != null
                    || result.code != 200 || result.bodyBytes == null || result.bodyBytes.length == 0)
                return;
            try {
                JSONObject response = new JSONObject(new String(result.bodyBytes,
                        java.nio.charset.StandardCharsets.UTF_8));
                if(!response.optBoolean("ok", false)
                        && !response.optBoolean("acked", false)
                        && !"ok".equals(response.optString("status", ""))
                        && !"acked".equals(response.optString("status", "")))
                    return;
                String scope = "";
                if(requestBody != null && requestBody.length > 0) {
                    try {
                        JSONObject request = new JSONObject(new String(requestBody,
                                java.nio.charset.StandardCharsets.UTF_8));
                        scope = request.optString("path", "");
                    } catch (Exception ignored) {
                    }
                }
                if(scope.length() == 0)
                    scope = ntkBridgeScopeForRequest(url, new HashMap<>(),
                            requestBody == null ? new byte[0] : requestBody);
                String adAck = "";
                String adAckC = "";
                if(result.setCookies() != null) {
                    for(String cookie : result.setCookies()) {
                        if(adAck.length() == 0)
                            adAck = cookieValue(cookie, "ad_ack");
                        if(adAckC.length() == 0)
                            adAckC = cookieValue(cookie, "ad_ack_c");
                    }
                }
                if(adAck.length() > 0)
                    NtkWebViewFallbackManager.rememberScopedAdAck(scope, adAck, "bridge-ack");
                if(adAckC.length() > 0)
                    NtkWebViewFallbackManager.rememberScopedAdAckC(scope, adAckC, "bridge-ack");
                rememberServerAckSuccess(scope, "bridge-ack-200");
            } catch (Exception e) {
                Log.d(TAG, "ntk_server_ack_success_record_error " + e);
            }
        }

        private void rememberBridgeRequestKey(String url, String method, Map<String, String> headers,
                                              byte[] requestBody, NtkQuicFetcher.Result result) {
            try {
                if(result != null && result.error == null && result.code >= 200 && result.code < 300
                        && result.bodyBytes != null && result.bodyBytes.length > 0
                        && method != null && "POST".equalsIgnoreCase(method)
                        && "/api/client-key/register".equals(URI.create(url).getPath())) {
                    JSONObject response = new JSONObject(new String(result.bodyBytes,
                            java.nio.charset.StandardCharsets.UTF_8));
                    String keyId = response.optString("keyId", "");
                    if(keyId.length() > 0) {
                        lastRequestKeyId = keyId;
                        String scope = ntkBridgeScopeForRequest(url, headers, requestBody);
                        rememberRecentRequestKeyId(scope, keyId, "client-key-register");
                        Log.d(TAG, "ntk_viewer_request_key_store keyId=" + summarizeKeyId(keyId));
                    }
                }
                if(requestBody != null && requestBody.length > 0 && isNtkAdAckPost(url, method)) {
                    JSONObject request = new JSONObject(new String(requestBody,
                            java.nio.charset.StandardCharsets.UTF_8));
                    String keyId = request.optString("requestKeyId", "");
                    if(keyId.length() > 0) {
                        lastRequestKeyId = keyId;
                        rememberRecentRequestKeyId(ntkBridgeScopeForRequest(url, headers, requestBody),
                                keyId, "ad-ack-request");
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private byte[] augmentAdAckBodyForBridge(String url, String method, byte[] body) {
            if(!isNtkAdAckPost(url, method) || body == null || body.length == 0)
                return body;
            try {
                JSONObject request;
                String bodyText = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                try {
                    request = new JSONObject(bodyText);
                } catch (Exception parseError) {
                    byte[] repaired = repairMalformedAdAckBody(url, method, bodyText, parseError);
                    if(repaired == body)
                        return body;
                    body = repaired;
                    request = new JSONObject(new String(body,
                            java.nio.charset.StandardCharsets.UTF_8));
                }
                if(request.optString("tp", "").length() == 0)
                    return body;
                boolean changed = false;
                if(request.optString("requestKeyId", "").length() == 0
                        && lastRequestKeyId != null && lastRequestKeyId.length() > 0) {
                    request.put("requestKeyId", lastRequestKeyId);
                    changed = true;
                }
                JSONArray existingUrls = request.optJSONArray("observationUrls");
                if(existingUrls != null) {
                    JSONArray cleanUrls = cleanObservationUrls(existingUrls);
                    JSONArray storedUrls = storedObservationsForAdAck(request);
                    if(storedUrls.length() > cleanUrls.length())
                        cleanUrls = storedUrls;
                    if(cleanUrls.length() != existingUrls.length()
                            || storedUrls.length() > existingUrls.length()) {
                        if(cleanUrls.length() > 0)
                            request.put("observationUrls", cleanUrls);
                        else
                            request.remove("observationUrls");
                        changed = true;
                    }
                }
                if(request.optJSONArray("observationUrls") == null) {
                    JSONArray urls = storedObservationsForAdAck(request);
                    if(urls.length() > 0) {
                        request.put("observationUrls", urls);
                        changed = true;
                    }
                }
                if(!changed)
                    return body;
                byte[] augmented = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "ntk_viewer_ad_ack_body_augmented_java keyId="
                        + summarizeKeyId(request.optString("requestKeyId", ""))
                        + ",observationUrls=" + (request.optJSONArray("observationUrls") == null
                        ? 0 : request.optJSONArray("observationUrls").length())
                        + ",bytes=" + body.length + "->" + augmented.length);
                return augmented;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_ad_ack_body_augment_java_error " + e);
                return body;
            }
        }

        private byte[] repairMalformedAdAckBody(String url, String method, String bodyText,
                                                Exception parseError) {
            if(!isNtkAdAckPost(url, method) || bodyText == null || bodyText.length() == 0)
                return new byte[0];
            int observationIndex = bodyText.indexOf("\"observationUrls\"");
            if(observationIndex < 0)
                return bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                String prefix = bodyText.substring(0, observationIndex).trim();
                if(prefix.endsWith(","))
                    prefix = prefix.substring(0, prefix.length() - 1).trim();
                if(prefix.endsWith("{"))
                    return bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String repairedText = prefix + "}";
                JSONObject request = new JSONObject(repairedText);
                JSONArray urls = storedObservationsForAdAck(request);
                if(urls.length() > 0) {
                    request.put("observationUrls", urls);
                }
                byte[] repaired = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "ntk_viewer_ad_ack_body_repaired_java reason="
                        + parseError.getClass().getSimpleName()
                        + ",observationUrls=" + (request.optJSONArray("observationUrls") == null
                        ? 0 : request.optJSONArray("observationUrls").length())
                        + ",bytes=" + bodyText.length() + "->" + repaired.length);
                return repaired;
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_ad_ack_body_repair_java_error " + e
                        + ",parseError=" + parseError);
                return bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        private JSONArray storedObservationsForAdAck(JSONObject request) {
            if(request == null)
                return new JSONArray();
            String path = request.optString("path", "");
            String token = request.optString("challengeToken", "");
            if(token.length() == 0)
                token = request.optString("token", "");
            List<String> observations = adAckObservationsByChallenge.get(adAckChallengeKey(path, token));
            JSONArray clean = cleanObservationList(observations);
            if(clean.length() > 0)
                return clean;
            return recentNativeChallengeObservations(path, token);
        }

        private static boolean adAckBodyHasRequestKey(byte[] body) {
            if(body == null || body.length == 0)
                return false;
            try {
                JSONObject request = new JSONObject(new String(body,
                        java.nio.charset.StandardCharsets.UTF_8));
                return request.optString("requestKeyId", "").length() > 0;
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean adAckBodyHasUsableProof(byte[] body) {
            if(body == null || body.length == 0)
                return false;
            try {
                JSONObject request = new JSONObject(new String(body,
                        java.nio.charset.StandardCharsets.UTF_8));
                String proof = request.optString("tp", "");
                return proof.length() > 0
                        && !"true".equalsIgnoreCase(proof)
                        && !"false".equalsIgnoreCase(proof);
            } catch (Exception e) {
                return false;
            }
        }

        private static JSONArray cleanObservationUrls(JSONArray urls) {
            JSONArray clean = new JSONArray();
            if(urls == null)
                return clean;
            for(int i = 0; i < urls.length(); i++) {
                String url = cleanObservationUrl(urls.optString(i, ""));
                if(url.length() > 0)
                    clean.put(url);
            }
            return clean;
        }

        private static JSONArray cleanObservationList(List<String> urls) {
            JSONArray clean = new JSONArray();
            if(urls == null)
                return clean;
            for(String candidate : urls) {
                String url = cleanObservationUrl(candidate);
                if(url.length() > 0)
                    clean.put(url);
            }
            return clean;
        }

        private static List<String> jsonArrayToStringList(JSONArray urls) {
            List<String> out = new ArrayList<>();
            if(urls == null)
                return out;
            for(int i = 0; i < urls.length(); i++) {
                String url = cleanObservationUrl(urls.optString(i, ""));
                if(url.length() > 0)
                    out.add(url);
            }
            return out;
        }

        private static JSONArray recentNativeChallengeObservations(String path, String token) {
            if(path == null || path.length() == 0 || token == null || token.length() == 0)
                return new JSONArray();
            try {
                String body = getRecentNativeAckChallenge(path);
                if(body.length() == 0)
                    return new JSONArray();
                JSONObject response = new JSONObject(body);
                JSONObject challenge = response.optJSONObject("challenge");
                if(challenge == null || !token.equals(challenge.optString("token", "")))
                    return new JSONArray();
                return cleanObservationUrls(challenge.optJSONArray("impressionUrls"));
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        private static String cleanObservationUrl(String url) {
            if(url == null)
                return "";
            String value = url.trim();
            if(value.length() == 0 || value.indexOf("/api/m/i?") < 0)
                return "";
            for(int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if(c < 0x20 || c > 0x7e)
                    return "";
            }
            return value;
        }

        private static String summarizeKeyId(String keyId) {
            if(keyId == null || keyId.length() == 0)
                return "";
            return keyId.length() <= 12 ? keyId : keyId.substring(0, 12);
        }

        private String cookieHeaderWithScopedAdAckC(String url, String method, byte[] body,
                                                    String cookieHeader) {
            if(!isNtkAdAckPost(url, method) || body == null || body.length == 0)
                return cookieHeader;
            try {
                JSONObject request = new JSONObject(new String(body,
                        java.nio.charset.StandardCharsets.UTF_8));
                String path = request.optString("path", "");
                String token = request.optString("challengeToken", "");
                if(token.length() == 0)
                    token = request.optString("token", "");
                if(path.length() == 0 || token.length() == 0)
                    return cookieHeader;
                String scoped = adAckCByChallenge.get(adAckChallengeKey(path, token));
                String source = "challenge";
                if(scoped == null || scoped.length() == 0) {
                    scoped = adAckCByPath.get(path);
                    source = "path";
                }
                if(scoped == null || scoped.length() == 0
                        || scoped.equals(cookieValue(cookieHeader, "ad_ack_c")))
                    return cookieHeader;
                String merged = mergeCookieHeaders(removeCookie(cookieHeader, "ad_ack_c"),
                        "ad_ack_c=" + scoped);
                Log.d(TAG, "ntk_viewer_ad_ack_c_scoped_restore path=" + path
                        + ",source=" + source);
                return merged;
            } catch (Exception e) {
                return cookieHeader;
            }
        }

        private static String cookieHeaderWithoutSatisfiedAdAckForChallenge(String url, String method,
                Map<String, String> headers, byte[] body, String cookieHeader) {
            if(!isNtkAdChallengePost(url, method))
                return cookieHeader;
            String scope = ntkBridgeScopeForRequest(url, headers, body);
            if(scope.length() == 0 || hasRecentServerAckSuccess(scope))
                return cookieHeader;
            String adAck = cookieValue(cookieHeader, "ad_ack");
            if(adAck.length() == 0 || !ntkAckCookieMatchesScope(adAck, scope))
                return cookieHeader;
            String stripped = removeCookie(cookieHeader, "ad_ack");
            Log.d(TAG, "ntk_viewer_ad_challenge_strict_cookie_strip path=" + scope
                    + ",hadAdAck=true,hadAdAckC="
                    + (cookieValue(cookieHeader, "ad_ack_c").length() > 0));
            return stripped;
        }

        private static String adAckChallengeKey(String path, String token) {
            return (path == null ? "" : path) + "|" + (token == null ? "" : token);
        }

        private static String removeCookie(String cookieHeader, String name) {
            if(cookieHeader == null || cookieHeader.length() == 0 || name == null || name.length() == 0)
                return cookieHeader == null ? "" : cookieHeader;
            StringBuilder builder = new StringBuilder();
            String prefix = name + "=";
            for(String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                if(trimmed.length() == 0 || trimmed.startsWith(prefix))
                    continue;
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(trimmed);
            }
            return builder.toString();
        }

        private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String name) {
            if(headers == null || name == null)
                return false;
            for(String key : headers.keySet()) {
                if(key != null && key.equalsIgnoreCase(name))
                    return true;
            }
            return false;
        }

        private static boolean isSignedAckBrowserKeyRequired(String url, String method,
                                                             Map<String, String> headers,
                                                             NtkQuicFetcher.Result result) {
            if(!isNtkAdAckPost(url, method) || !hasHeaderIgnoreCase(headers, "x-ntk-key-id")
                    || result == null || result.error != null || result.code != 428
                    || result.bodyBytes == null || result.bodyBytes.length == 0)
                return false;
            try {
                String body = new String(result.bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                return body.contains("\"browser_key_required\"");
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isOneShotAckSubmitIndeterminate(Throwable error) {
            if(error == null)
                return false;
            String lower = String.valueOf(error).toLowerCase(Locale.US);
            return lower.contains("timeout")
                    || lower.contains("timed out")
                    || lower.contains("canceled")
                    || lower.contains("cancelled");
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

        private void rememberAdControlCloudflareChallenge(String url, String method,
                                                          NtkQuicFetcher.Result result) {
            if(result == null || result.error != null || result.code != 403)
                return;
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                if(path == null || !path.startsWith("/api/ad/"))
                    return;
                CustomHttpClient client = MainApplication.getHttpClient();
                String body = result.body == null ? "" : result.body;
                if(client != null && client.isCloudflareChallengeResponse(result.code, body)) {
                    client.markCloudflareChallenge(url);
                    Log.d(TAG, "ntk_viewer_ad_bridge_cloudflare_challenge method="
                            + method + ",path=" + path + ",url=" + url);
                }
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
                if(ntkGuardVersionFromUrl(url).length() > 0)
                    return null;
                String path = URI.create(url).getPath();
                boolean wasm = "/api/ad/guard-wasm".equals(path);
                byte[] bytes = readAssetBytes(context, wasm
                        ? "ntk_guard/guard-wasm.bin"
                        : "ntk_guard/guard.js");
                if(bytes == null || bytes.length == 0)
                    return null;
                if(wasm) {
                    byte[] loader = readAssetBytes(context, "ntk_guard/guard.js");
                    if(hasEncryptedGuardLoader(loader)) {
                        byte[] raw = decryptLocalGuardWasm(bytes);
                        if(raw != null && raw.length > 4)
                            bytes = raw;
                        else
                            return null;
                    }
                } else {
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

        private String strippedVersionedGuardJsResponse(String url, NtkQuicFetcher.Result result) {
            if(result == null || result.code != 200 || result.bodyBytes == null)
                return null;
            if(!isNtkAdGuardModuleUrl(url))
                return null;
            try {
                URI uri = URI.create(url);
                if(!"/api/ad/guard-js".equals(uri.getPath()))
                    return null;
                byte[] stripped = stripEncryptedGuardLoader(result.bodyBytes);
                if(stripped != result.bodyBytes) {
                    Log.d(TAG, "ntk_viewer_quic_bridge_stripped_guard_js url=" + url
                            + ",rawBytes=" + result.bodyBytes.length
                            + ",plainBytes=" + stripped.length);
                }
                return guardModuleBridgeObject(stripped, false).toString();
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
                if(!guardLoaderNeedsDecryptedWasm(ntkGuardVersionFromUrl(url)))
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
                    if(guardLoaderNeedsDecryptedWasm(version)) {
                        byte[] raw = decryptVersionedGuardWasm(version, bytes);
                        if(raw != null && raw.length > 4)
                            bytes = raw;
                        else
                            return null;
                    }
                } else {
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

        private boolean guardLoaderNeedsDecryptedWasm(String version) {
            if(version == null || version.length() == 0)
                return false;
            byte[] bytes = guardLoaderByVersion.get(version);
            if(hasEncryptedGuardLoader(bytes))
                return true;
            java.io.File file = new java.io.File(new java.io.File(context.getCacheDir(), "ntk_guard_cache"),
                    "guard-js-" + version + ".js");
            return hasEncryptedGuardLoader(readFileBytes(file));
        }

        private static boolean hasEncryptedGuardLoader(byte[] bytes) {
            if(bytes == null || bytes.length < 16)
                return false;
            try {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return text.contains("/*!ENCRYPTED*/");
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
                if(marker < 0)
                    marker = text.indexOf("/*!ENCRYPTED*/");
                if(marker < 0)
                    return patchGuardLoaderForWebView(bytes);
                String stripped = text.substring(0, marker);
                String[] exportNames = encryptedGuardExportNames(text.substring(marker));
                if(exportNames != null && !hasGuardDefaultExport(stripped)) {
                    stripped = stripped + "\nexport{" + exportNames[0]
                            + " as initSync," + exportNames[1] + " as default};";
                }
                stripped = patchGuardLoaderForWebView(stripped);
                Log.d(TAG, "ntk_viewer_guard_js_stripped encryptedBytes=" + bytes.length
                        + ",plainBytes=" + stripped.length());
                return stripped.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return bytes;
            }
        }

        private static byte[] patchGuardLoaderForWebView(byte[] bytes) {
            if(bytes == null || bytes.length == 0)
                return bytes;
            try {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                String patched = patchGuardLoaderForWebView(text);
                if(patched == text)
                    return bytes;
                Log.d(TAG, "ntk_viewer_guard_js_webview_patch bytes=" + bytes.length
                        + ",patchedBytes=" + patched.length());
                return patched.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return bytes;
            }
        }

        private static String patchGuardLoaderForWebView(String text) {
            if(text == null || text.indexOf("WebAssembly.instantiateStreaming") < 0)
                return text;
            return text.replace("WebAssembly.instantiateStreaming", "__ntkNoInstantiateStreaming");
        }

        private static boolean hasGuardDefaultExport(String text) {
            if(text == null || text.length() == 0)
                return false;
            return java.util.regex.Pattern
                    .compile("export\\{[^}]*\\bas\\s+default\\b")
                    .matcher(text)
                    .find();
        }

        private static String[] encryptedGuardExportNames(String encryptedTail) {
            if(encryptedTail == null || encryptedTail.length() == 0)
                return null;
            try {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("export\\{\\s*([A-Za-z_$][\\w$]*)\\s+as\\s+initSync\\s*,\\s*([A-Za-z_$][\\w$]*)\\s+as\\s+default\\s*\\}")
                        .matcher(encryptedTail);
                if(matcher.find())
                    return new String[]{matcher.group(1), matcher.group(2)};
            } catch (Exception ignored) {
            }
            return null;
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
            syncBridgeCookiesFromWebView(url, headers);
            String cookieHeader = mergeCookieHeaders(
                    headerValueIgnoreCase(headers, "cookie"),
                    mergeCookieHeaders(
                            mergedCookieHeader(url, fallbackCookieHeader),
                            bridgeAppCookieHeader()));
            String scope = ntkBridgeScopeForRequest(url, headers, body);
            if(scope.length() == 0)
                return cookieHeader;
            return filterNtkAckCookiesForScope(cookieHeader, scope);
        }

        private static void syncBridgeCookiesFromWebView(String url, Map<String, String> headers) {
            try {
                CustomHttpClient client = MainApplication.getHttpClient();
                if(client == null)
                    return;
                client.syncCookiesFromWebView(url, true);
                String referer = headerValueIgnoreCase(headers, "referer");
                if(referer != null && referer.length() > 0)
                    client.syncCookiesFromWebView(referer, true);
                String origin = headerValueIgnoreCase(headers, "origin");
                if(origin != null && origin.startsWith("https://"))
                    client.syncCookiesFromWebView(origin, true);
            } catch (Exception e) {
                Log.d(TAG, "ntk_viewer_bridge_cookie_sync_error " + e);
            }
        }

        private static String bridgeAppCookieHeader() {
            try {
                CustomHttpClient client = MainApplication.getHttpClient();
                return client == null ? "" : client.getCookieHeader();
            } catch (Exception e) {
                return "";
            }
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
            rememberEarlyViewerImageApiUrls(scope, result.body);
            Log.d(TAG, "ntk_webview_viewer_images_cache_store key=" + key
                    + ",len=" + result.body.length());
        }

        private static void rememberEarlyViewerImageApiUrls(String scope, String body) {
            if(scope == null || scope.length() == 0 || body == null || body.length() == 0)
                return;
            try {
                JSONObject response = new JSONObject(body);
                if(!response.optBoolean("ok", false))
                    return;
                JSONArray images = response.optJSONArray("images");
                if(images == null || images.length() == 0)
                    return;
                ArrayList<String> urls = new ArrayList<>();
                for(int i = 0; i < images.length(); i++) {
                    JSONObject image = images.optJSONObject(i);
                    String src = image == null ? "" : image.optString("src", "");
                    if(src.length() > 0)
                        urls.add(src);
                }
                ReaderImageCache.INSTANCE.rememberEarlyNtkImageUrls(scope, urls);
            } catch (Exception e) {
                Log.d(TAG, "ntk_webview_viewer_images_early_urls_error path=" + scope + "," + e);
            }
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
            String cfClearance = cookieValue(cookieHeader, "cf_clearance");
            Log.d(TAG, "ntk_viewer_quic_bridge_request method=" + method
                    + ",url=" + url
                    + ",origin=" + origin
                    + ",referer=" + referer
                    + ",cookieLen=" + (cookieHeader == null ? 0 : cookieHeader.length())
                    + ",scope=" + scope
                    + ",hasAdAck=" + (adAck.length() > 0)
                    + ",hasAdAckC=" + (adAckC.length() > 0)
                    + ",hasAdGuardL=" + (adGuardL.length() > 0)
                    + ",hasCfClearance=" + (cfClearance.length() > 0)
                    + ",adAckMatches=" + ntkAckCookieMatchesScope(adAck, scope)
                    + ",adAckCMatches=" + ntkAckCookieMatchesScope(adAckC, scope)
                    + ",headerSummary=" + summarizeBridgeHeaders(headers)
                    + ",body=" + bodyText);
        }

        private static String summarizeBridgeHeaders(Map<String, String> headers) {
            if(headers == null || headers.isEmpty())
                return "";
            ArrayList<String> names = new ArrayList<>();
            ArrayList<String> keyValues = new ArrayList<>();
            for(Map.Entry<String, String> entry : headers.entrySet()) {
                if(entry == null || entry.getKey() == null)
                    continue;
                String name = entry.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                names.add(lower);
                if(lower.contains("browser") || lower.contains("key")
                        || lower.contains("bk") || lower.startsWith("x-")) {
                    String value = entry.getValue() == null ? "" : entry.getValue();
                    if(value.length() > 32)
                        value = value.substring(0, 32) + "...";
                    keyValues.add(lower + "=" + value);
                }
            }
            Collections.sort(names);
            Collections.sort(keyValues);
            return "names=" + names + ";keyLike=" + keyValues;
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

        private static String ntkTokenIdentity(String token) {
            if(token == null || token.length() == 0)
                return "";
            try {
                int dot = token.indexOf('.');
                String payload = dot > 0 ? token.substring(0, dot) : token;
                int padding = (4 - (payload.length() % 4)) % 4;
                StringBuilder padded = new StringBuilder(payload);
                for(int i = 0; i < padding; i++)
                    padded.append('=');
                byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
                JSONObject json = new JSONObject(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                return json.optString("identity", "");
            } catch (Exception e) {
                return "";
            }
        }

        private static String generatedNtkFp(String pageUrl, String requestUserAgent) {
            try {
                String seed = String.valueOf(requestUserAgent) + "|"
                        + Locale.getDefault().toLanguageTag() + "|"
                        + java.util.TimeZone.getDefault().getID() + "|"
                        + String.valueOf(pageUrl);
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder(64);
                for(byte value : digest)
                    builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                return builder.substring(0, 32);
            } catch (Exception e) {
                return "";
            }
        }

        private static String generatedNtkEvId() {
            try {
                byte[] bytes = new byte[32];
                new java.security.SecureRandom().nextBytes(bytes);
                StringBuilder builder = new StringBuilder(64);
                for(byte value : bytes)
                    builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                return builder.toString();
            } catch (Exception e) {
                return "";
            }
        }

        private static boolean isNtkHexIdentity(String value, int minLength) {
            if(value == null || value.length() < minLength)
                return false;
            for(int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if(!hex)
                    return false;
            }
            return true;
        }

        private static String normalizeQuotedNtkIdentity(String value) {
            if(value == null)
                return "";
            String normalized = value.trim();
            if(normalized.startsWith("W/"))
                normalized = normalized.substring(2).trim();
            while(normalized.length() >= 2
                    && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                    || (normalized.startsWith("'") && normalized.endsWith("'"))))
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            return normalized;
        }

        private static String bridgeResponseHeader(JSONObject response, String name) {
            if(response == null || name == null)
                return "";
            JSONObject headers = response.optJSONObject("headers");
            if(headers == null)
                return "";
            Iterator<String> keys = headers.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                if(key != null && key.equalsIgnoreCase(name))
                    return headers.optString(key, "");
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

        private static void normalizeBridgeNavigationHeaders(String url, Map<String, String> headers,
                                                             byte[] body) {
            if(headers == null || url == null)
                return;
            try {
                URI uri = URI.create(url);
                String origin = uri.getScheme() + "://" + uri.getHost();
                String referer = "";
                String scope = ntkBridgeScopeForRequest(url, headers, body);
                if(scope.length() > 0)
                    referer = origin + scope;
                if("/api/client-key/register".equals(uri.getPath())) {
                    putHeaderIfAbsentIgnoreCase(headers, "origin", origin);
                    putHeaderIfAbsentIgnoreCase(headers, "referer",
                            referer.length() > 0 ? referer : origin + "/");
                    return;
                }
                boolean ackPost = "/api/ad/ack".equals(uri.getPath());
                if(ackPost) {
                    removeHeaderIgnoreCase(headers, "origin");
                    removeHeaderIgnoreCase(headers, "referer");
                    headers.put("origin", origin);
                    if(referer.length() > 0)
                        headers.put("referer", referer);
                    return;
                }
                String currentOrigin = headerValueIgnoreCase(headers, "origin");
                String currentReferer = headerValueIgnoreCase(headers, "referer");
                if(isBlankBridgeHeader(currentOrigin)) {
                    removeHeaderIgnoreCase(headers, "origin");
                    headers.put("origin", origin);
                }
                if(isBlankBridgeHeader(currentReferer) && referer.length() > 0) {
                    removeHeaderIgnoreCase(headers, "referer");
                    headers.put("referer", referer);
                }
            } catch (Exception ignored) {
            }
        }

        private static void applyBridgeBrowserFetchHeaders(String url, String method,
                                                           Map<String, String> headers) {
            if(headers == null || url == null || method == null
                    || !"POST".equalsIgnoreCase(method))
                return;
            try {
                String path = URI.create(url).getPath();
                if("/api/client-key/register".equals(path)) {
                    putHeaderIfAbsentIgnoreCase(headers, "accept", "application/json");
                    putHeaderIfAbsentIgnoreCase(headers, "accept-language",
                            "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
                    putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-dest", "empty");
                    putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-mode", "cors");
                    putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-site", "same-origin");
                    putHeaderIfAbsentIgnoreCase(headers, "priority", "u=1, i");
                    return;
                }
                if(!"/api/manhwa-images".equals(path)
                        && !"/api/webtoon-images".equals(path)
                        && !"/api/manga-images".equals(path))
                    return;
                putHeaderIfAbsentIgnoreCase(headers, "accept-language",
                        "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
                putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-dest", "empty");
                putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-mode", "cors");
                putHeaderIfAbsentIgnoreCase(headers, "sec-fetch-site", "same-origin");
                putHeaderIfAbsentIgnoreCase(headers, "priority", "u=1, i");
            } catch (Exception ignored) {
            }
        }

        private static void putHeaderIfAbsentIgnoreCase(Map<String, String> headers,
                                                        String name, String value) {
            if(headers == null || name == null || value == null)
                return;
            if(hasHeaderIgnoreCase(headers, name))
                return;
            headers.put(name, value);
        }

        private static boolean isBlankBridgeHeader(String value) {
            if(value == null)
                return true;
            String trimmed = value.trim();
            return trimmed.length() == 0 || "null".equalsIgnoreCase(trimmed)
                    || "undefined".equalsIgnoreCase(trimmed);
        }

        private static byte[] decodeBridgeBase64(String value) {
            if(value == null || value.length() == 0)
                return new byte[0];
            try {
                return Base64.decode(value, Base64.DEFAULT);
            } catch (IllegalArgumentException ignored) {
            }
            String normalized = value.trim().replace('-', '+').replace('_', '/');
            int remainder = normalized.length() % 4;
            if(remainder != 0)
                normalized += "====".substring(remainder);
            return Base64.decode(normalized, Base64.DEFAULT);
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

        private static String bridgeJsonOkResponse() {
            try {
                JSONObject headers = new JSONObject();
                headers.put("content-type", "application/json");
                headers.put("cache-control", "no-store");
                JSONObject body = new JSONObject();
                body.put("ok", true);
                JSONObject object = new JSONObject();
                object.put("ok", true);
                object.put("status", 200);
                object.put("statusText", "OK");
                object.put("headers", headers);
                object.put("bodyBase64", Base64.encodeToString(body.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
                return object.toString();
            } catch (Exception e) {
                return bridgeError(String.valueOf(e));
            }
        }

        private static String bridgeJsonStatusResponse(int status, String error) {
            try {
                JSONObject headers = new JSONObject();
                headers.put("content-type", "application/json");
                headers.put("cache-control", "no-store");
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("error", error == null ? "unknown" : error);
                JSONObject object = new JSONObject();
                object.put("ok", true);
                object.put("status", status);
                object.put("statusText", status == 428 ? "Precondition Required" : "Error");
                object.put("headers", headers);
                object.put("bodyBase64", Base64.encodeToString(body.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
                return object.toString();
            } catch (Exception e) {
                return bridgeError(String.valueOf(e));
            }
        }

        private static String bridgeMetricImageHitResponse() {
            try {
                JSONObject headers = new JSONObject();
                headers.put("content-type", "image/gif");
                headers.put("cache-control", "no-store");
                JSONObject object = new JSONObject();
                object.put("ok", true);
                object.put("status", 200);
                object.put("statusText", "OK");
                object.put("headers", headers);
                object.put("bodyBase64", Base64.encodeToString(TINY_GIF_BYTES, Base64.NO_WRAP));
                return object.toString();
            } catch (Exception e) {
                return bridgeError(String.valueOf(e));
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

    private static final class ActiveViewerImageFetch {
        final String path;
        final boolean ackOnly;
        final Runnable cancel;

        ActiveViewerImageFetch(String path, boolean ackOnly, Runnable cancel) {
            this.path = path;
            this.ackOnly = ackOnly;
            this.cancel = cancel;
        }
    }
}
