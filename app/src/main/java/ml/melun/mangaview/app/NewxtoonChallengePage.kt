package ml.melun.mangaview.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.util.Log
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.data.network.BrowserTlsRelay
import ml.melun.mangaview.source.ntk.AndroidBrowserViews
import ml.melun.mangaview.source.ntk.NtkWebViewStartupOwner

private const val TAG = "NewxtoonClearance"

internal const val NEWXTOON_ORIGIN = "https://newxtoon1.com"

/** The replay browser commits an inert same-origin document; fetches only need that origin. */
internal const val REPLAY_DOCUMENT_HTML = "<html><body></body></html>"

private const val POLL_INTERVAL_MILLIS = 400L
private const val CLEARANCE_CHECK_GUARD_MILLIS = 1_000L
private const val WEBVIEW_ENGINE_STARTUP_WAIT_MILLIS = 2_000L

internal val EMPTY_RESPONSE = android.webkit.WebResourceResponse(
    "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

internal val TRACKER_HOSTS = setOf(
    "tj.websitestatistics.top",
    "quicksharefiles.top",
    "googletagmanager.com",
    "google-analytics.com",
)

internal val COSMETIC_EXTENSIONS = listOf(
    ".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".svg", ".ico",
    ".mp4", ".webm", ".woff", ".woff2", ".ttf", ".otf",
)

private const val PROBE_SCRIPT = """
(function(){
  function deepFrames(){
    var out=[];
    function walk(root){
      var list=root.querySelectorAll("iframe");
      for(var i=0;i<list.length;i++){out.push(list[i]);}
      var all=root.querySelectorAll("*");
      for(var j=0;j<all.length;j++){var e=all[j];if(e.shadowRoot){walk(e.shadowRoot);}}
    }
    walk(document);
    return out;
  }
  var frames=[];
  var list=deepFrames();
  for(var i=0;i<list.length;i++){
    var b=list[i].getBoundingClientRect();
    frames.push({x:b.left,y:b.top,w:b.width,h:b.height,src:(list[i].src||"").slice(0,100)});
  }
  var hosts=[];
  var selectors=["#challenge-stage",".cf-turnstile","#turnstile-wrapper","[id*=turnstile]","[class*=turnstile]"];
  for(var s=0;s<selectors.length;s++){
    var e=document.querySelector(selectors[s]);
    if(!e){continue;}
    var r=e.getBoundingClientRect();
    if(r.width>=60&&r.height>=20){hosts.push({sel:selectors[s],x:r.left,y:r.top,w:r.width,h:r.height});}
  }
  return JSON.stringify({frames:frames,hosts:hosts,dpr:window.devicePixelRatio,title:document.title,taps:window.__taps|0});
})()
"""

private const val TAP_PROBE_SCRIPT = """
(function(){
  window.__taps=0;
  document.addEventListener("touchstart",function(){window.__taps+=1},true);
  document.addEventListener("pointerdown",function(){window.__taps+=100},true);
  document.addEventListener("mousedown",function(){window.__taps+=10000},true);
  return 1;
})()
"""

/**
 * The emulator renders through a translated software GL stack ("Android Emulator OpenGL ES
 * Translator") and reports an x86_64 platform, both of which mark the session as non-phone
 * before Cloudflare even looks at the interaction. These are JavaScript-visible only, so they
 * are patched at document start in every frame; nothing here contradicts a real request header.
 */
private const val FINGERPRINT_SCRIPT = """
(function(){
  try{
    var vendor="Qualcomm";
    var renderer="Adreno (TM) 740";
    var patch=function(proto){
      if(!proto||!proto.getParameter){return;}
      var original=proto.getParameter;
      var patched=function(parameter){
        if(parameter===37445){return vendor;}
        if(parameter===37446){return renderer;}
        return original.call(this,parameter);
      };
      patched.toString=function(){return "function getParameter() { [native code] }";};
      proto.getParameter=patched;
    };
    patch(window.WebGLRenderingContext&&window.WebGLRenderingContext.prototype);
    patch(window.WebGL2RenderingContext&&window.WebGL2RenderingContext.prototype);
  }catch(error){}
  try{
    Object.defineProperty(Navigator.prototype,"platform",{
      get:function(){return "Linux aarch64";},configurable:true
    });
  }catch(error){}
  try{
    Object.defineProperty(Navigator.prototype,"hardwareConcurrency",{
      get:function(){return 8;},configurable:true
    });
  }catch(error){}
  try{
    var chromeVersion=/Chrome\/([0-9]+)/.exec(navigator.userAgent);
    var major=chromeVersion?chromeVersion[1]:"152";
    var fullMatch=/Chrome\/([0-9.]+)/.exec(navigator.userAgent);
    var full=fullMatch?fullMatch[1]:major+".0.0.0";
    var brands=[
      {brand:"Chromium",version:major},
      {brand:"Not?A_Brand",version:"24"},
      {brand:"Android WebView",version:major}
    ];
    var entropy={
      architecture:"arm",bitness:"64",brands:brands,formFactors:["Mobile"],
      fullVersionList:[
        {brand:"Chromium",version:full},
        {brand:"Not?A_Brand",version:"24.0.0.0"},
        {brand:"Android WebView",version:full}
      ],
      mobile:true,model:"SM-S918N",platform:"Android",platformVersion:"15.0.0",
      uaFullVersion:full,wow64:false
    };
    var hints={
      brands:brands,mobile:true,platform:"Android",
      getHighEntropyValues:function(names){
        var out={};
        for(var index=0;index<names.length;index++){
          var name=names[index];
          if(entropy[name]!==undefined){out[name]=entropy[name];}
        }
        return Promise.resolve(out);
      },
      toJSON:function(){return {brands:brands,mobile:true,platform:"Android"};}
    };
    Object.defineProperty(Navigator.prototype,"userAgentData",{
      get:function(){return hints;},configurable:true
    });
  }catch(error){}
})();
"""

/**
 * Drives one Cloudflare challenge view: it owns the WebView until the interstitial clears and
 * hands the settled view back to the clearance, which promotes it to the replay browser.
 */
internal class NewxtoonChallengePage(
    private val appContext: Context,
    private val cookies: NewxtoonCookieStore,
    private val fetcher: NewxtoonFetchBridge,
    private val main: Handler,
    private val sourceUserAgent: String,
    private val spoofsDeviceIdentity: Boolean,
    private val onSettlingView: (WebView?) -> Unit,
    private val onSettlingUsable: (Boolean) -> Unit,
) {
    /** One attempt's mutable state; the page callbacks and the poll loop both settle it. */
    private class Attempt(
        val webView: WebView,
        val continuation: CancellableContinuation<WebView?>,
    ) {
        var settled = false
        var pollJob: Job? = null
        val captures = mutableListOf<Job>()
        var consoleLines = 0
        var clearanceCheckAt = 0L
        var startedAt = 0L
        var lastProbe = 0L
        var clicksSent = 0
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun awaitClearance(relay: BrowserTlsRelay, window: ChallengeWindow): WebView? =
        coroutineScope {
            val workScope = this
            awaitWebViewEngineStarted()
            suspendCancellableCoroutine { continuation ->
                val webView = AndroidBrowserViews.create(window.context)
                // This browser is offscreen and must never ride the app's GPU context: when HWUI
                // loses that context, Chromium aborts the process on the next functor draw
                // ("Non owned context lost!", SIGTRAP on RenderThread). A software layer keeps the
                // clearance browser off the functor path entirely.
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                val attempt = Attempt(webView, continuation)
                attempt.startedAt = System.currentTimeMillis()
                continuation.invokeOnCancellation { main.post { finish(attempt, false, "cancelled") } }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = sourceUserAgent
                webView.addJavascriptInterface(fetcher.Bridge(), BRIDGE_NAME)
                webView.webViewClient = challengeClient(attempt, relay)
                webView.webChromeClient = challengeConsole(attempt)
                webView.resumeTimers()
                // Attach before registering document-start scripts: addDocumentStartJavaScript on a
                // never-attached, cold-engine WebView races Chromium startup and can CHECK-abort
                // the whole process (WebView 150+ / canary tracks). Injection stays ahead of loadUrl.
                window.attach(webView)
                if (spoofsDeviceIdentity) {
                    runCatching {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(
                                webView, FINGERPRINT_SCRIPT, setOf("*"))
                        }
                    }.onFailure { Log.w(TAG, "fingerprint injection skipped", it) }
                }
                // A persisted clearance that is still valid makes the interstitial resolve at once.
                cookies.seedWebView()
                // While the challenge resolves, a verified-clearance fetch may use this view already.
                if (cookies.hasVerifiedClearance()) onSettlingView(webView)
                Log.i(TAG, "webview created ua=${webView.settings.userAgentString} " +
                    "relay=${relay.proxyUrl} loading $NEWXTOON_ORIGIN")
                webView.loadUrl(NEWXTOON_ORIGIN)
                startPolling(workScope, attempt)
            }
        }

    /**
     * The challenge owns the main process's first WebView, and WebView 150+ rejects
     * document-start script registration while the engine is still starting ("Must be started
     * before we block!"). Wait for the application-owned startup, bounded so a stalled or
     * unsupported startup degrades to the old behaviour instead of wedging the challenge.
     */
    private suspend fun awaitWebViewEngineStarted() {
        val owner = appContext.applicationContext as? NtkWebViewStartupOwner ?: return
        withTimeoutOrNull(WEBVIEW_ENGINE_STARTUP_WAIT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val settle: () -> Unit = {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                owner.ntkWebViewStartup.whenReady(settle) { settle() }
            }
        }
    }

    /** Settles the attempt exactly once; a cancelled continuation still tears the view down. */
    private fun finish(attempt: Attempt, cleared: Boolean, reason: String) {
        if (attempt.settled) return
        attempt.settled = true
        onSettlingView(null)
        onSettlingUsable(false)
        attempt.pollJob?.cancel()
        attempt.captures.forEach(Job::cancel)
        Log.i(TAG, "challenge finished cleared=$cleared reason=$reason")
        if (!cleared) {
            // Chromium aborts the process when a WebView is stopped or destroyed from inside its
            // own callback, so teardown always defers one main-loop turn.
            main.post { teardownChallengeWebView(attempt.webView) }
        }
        if (attempt.continuation.isActive) {
            attempt.continuation.resume(if (cleared) attempt.webView else null)
        }
    }

    /**
     * A probe callback lost to a navigation must not wedge the guard forever, so the guard is
     * time-bound instead of sticky. No reload here: the cf_clearance that appears mid-challenge
     * is still pending, and navigating away aborts the challenge script that would otherwise
     * finish and promote it.
     */
    private fun checkClearance(attempt: Attempt) {
        if (attempt.settled) return
        val now = System.currentTimeMillis()
        if (now - attempt.clearanceCheckAt < CLEARANCE_CHECK_GUARD_MILLIS) return
        attempt.clearanceCheckAt = now
        attempt.webView.evaluateJavascript(CLEARANCE_PROBE_SCRIPT) { value ->
            if (!attempt.settled && value?.trim('"') == "clear") {
                runCatching { CookieManager.getInstance().flush() }
                finish(attempt, true, "page-cleared")
            }
        }
    }

    private fun challengeClient(attempt: Attempt, relay: BrowserTlsRelay): WebViewClient =
        object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                                                   host: String, realm: String) {
                val credentials = relay.credentials(host, realm)
                Log.i(TAG, "proxy auth host=$host realm=$realm matched=${credentials != null}")
                if (credentials == null) handler.cancel()
                else handler.proceed(credentials.first, credentials.second)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.i(TAG, "page started $url")
                // Once the origin's document commits, evaluated fetch code runs inside it —
                // a verified-clearance replay no longer needs to wait for the full challenge.
                if (view === attempt.webView) onSettlingUsable(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page finished $url title=${view.title} " +
                    "clearancePresent=${cookies.hasClearance()}")
                if (cookies.clearanceValue() != null) checkClearance(attempt)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                Log.w(TAG, "page error ${error.errorCode} ${error.description} ${request.url}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning true is mandatory: an unhandled renderer death takes the app
                // process with it, silently and with no Java stack.
                Log.w(TAG, "webview renderer gone didCrash=${detail.didCrash()}")
                finish(attempt, false, "renderer-gone")
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                // Only cosmetic bytes are cut: third-party images/media/fonts and known
                // trackers cost seconds of page load, while every script and XHR stays
                // untouched so the challenge flow keeps full fidelity.
                if (request.isForMainFrame) return null
                val host = request.url.host?.lowercase() ?: return null
                val path = request.url.path?.lowercase() ?: ""
                val cosmetic = TRACKER_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    COSMETIC_EXTENSIONS.any { path.endsWith(it) }
                if (cosmetic) {
                    Log.i(TAG, "blocked ${request.method} ${request.url.host}")
                    return EMPTY_RESPONSE
                }
                return null
            }
        }

    private fun challengeConsole(attempt: Attempt): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (attempt.consoleLines < 80) {
                attempt.consoleLines++
                val source = message.sourceId()?.substringAfterLast('/')?.take(24) ?: ""
                Log.i(TAG, "console[${message.messageLevel()}][$source] ${message.message()}")
            }
            return true
        }
    }

    private fun startPolling(scope: CoroutineScope, attempt: Attempt) {
        attempt.pollJob = scope.launch {
            delay(POLL_INTERVAL_MILLIS)
            while (isActive && !attempt.settled) {
                // cf_clearance rotates while the challenge is still pending, so the cookie
                // alone proves nothing; only the interstitial's markers disappearing does.
                if (cookies.clearanceValue() != null) checkClearance(attempt)
                val elapsed = System.currentTimeMillis() - attempt.startedAt
                if (elapsed - attempt.lastProbe > 4_000 && elapsed > 5_000) {
                    attempt.lastProbe = elapsed
                    attempt.webView.evaluateJavascript(PROBE_SCRIPT) { value ->
                        probeChallenge(scope, attempt, value)
                    }
                }
                if (elapsed % 4_000 < POLL_INTERVAL_MILLIS) {
                    Log.i(TAG, "poll ${elapsed}ms url=${attempt.webView.url} " +
                        "clearancePresent=${cookies.hasClearance()}")
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun probeChallenge(scope: CoroutineScope, attempt: Attempt, value: String?) {
        if (attempt.settled) return
        Log.i(TAG, "probe $value")
        if (value == null || value == "null" || attempt.clicksSent >= 3) return
        attempt.clicksSent++
        if (attempt.clicksSent == 1) {
            captureChallengeFrame(appContext, attempt.webView, "challenge-pre.png")
            attempt.webView.evaluateJavascript(TAP_PROBE_SCRIPT, null)
        }
        clickChallengeFrames(attempt.webView, value, attempt.clicksSent)
        val tag = attempt.clicksSent
        attempt.captures += scope.launch {
            delay(1_500L)
            if (!attempt.settled) captureChallengeFrame(appContext, attempt.webView, "challenge-tap$tag.png")
        }
    }
}
