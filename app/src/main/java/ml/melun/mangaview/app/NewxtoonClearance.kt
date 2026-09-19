package ml.melun.mangaview.app

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.data.network.BrowserTlsRelay
import ml.melun.mangaview.source.ntk.AndroidBrowserViews
import okhttp3.CookieJar

private const val TAG = "NewxtoonClearance"

/** The emulator's model and build id mark the session as non-phone; only there they are rewritten. */
private val spoofsDeviceIdentity: Boolean = run {
    val fingerprint = android.os.Build.FINGERPRINT
    val hardware = android.os.Build.HARDWARE
    fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
        hardware.contains("goldfish") || hardware.contains("ranchu") ||
        android.os.Build.MODEL.startsWith("sdk_")
}

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
 * Solves Cloudflare's managed challenge for the newxtoon origin in a real WebView and shares the
 * resulting cookies with every OkHttp route that talks to the origin.
 */
internal class NewxtoonClearance(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cookies = NewxtoonCookieStore(ORIGIN, context)
    val cookieJar: CookieJar get() = cookies.cookieJar
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    /** The WebView that solved the challenge; its browser identity owns the clearance cookie. */
    @Volatile
    private var solvedView: WebView? = null
    @Volatile
    private var solvedWindow: ChallengeWindow? = null
    @Volatile
    private var solvedRelay: BrowserTlsRelay? = null
    private val fetcher = NewxtoonFetchBridge(main)

    /**
     * The engine's own UA is kept byte for byte except for the emulator's model and build id, the
     * only parts that mark the session as non-phone; the client hints the engine sends stay
     * Android WebView, so the presented identity remains internally consistent.
     */
    val sourceUserAgent: String = runCatching { WebSettings.getDefaultUserAgent(appContext) }
        .getOrElse { fallbackUserAgent() }
        .let { engineUserAgent ->
            if (!spoofsDeviceIdentity) engineUserAgent
            else engineUserAgent.replace(DEVICE_MARKER, "Android 15; $DEVICE_MODEL Build/$DEVICE_BUILD")
        }

    /** The client hints the challenge WebView actually sends, replayed on the OkHttp route. */
    val clientHints: String = run {
        val version = Regex("Chrome/([0-9]+)").find(sourceUserAgent)?.groupValues?.get(1) ?: "124"
        "\"Chromium\";v=\"$version\", \"Not?A_Brand\";v=\"24\", \"Android WebView\";v=\"$version\""
    }

    private fun fallbackUserAgent(): String {
        val version = runCatching {
            WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName
        }.getOrNull() ?: "124.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "$DEVICE_MODEL Build/$DEVICE_BUILD; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/$version Mobile Safari/537.36"
    }

    /** A cached cookie that still draws a challenge is stale; run the challenge again. */
    suspend fun solveFresh(): Boolean = mutex.withLock {
        Log.i(TAG, "solveFresh: starting webview challenge despite cached clearance")
        runChallengeLocked()
    }

    /** Returns true only when a clearance cookie is present for the origin. */
    suspend fun solve(): Boolean {
        if (cookies.hasClearance()) {
            Log.i(TAG, "solve: clearance cookie already present")
            cookies.harvest()
            return true
        }
        return mutex.withLock {
            if (cookies.hasClearance()) {
                cookies.harvest()
                return@withLock true
            }
            runChallengeLocked()
        }
    }

    /**
     * True while a WebView that already cleared the challenge is alive, so transports can send
     * challenged routes to it directly instead of paying a refused HTTP request first.
     */
    val solvedViewReady: Boolean get() = solvedView != null

    /**
     * Runs a challenged route inside the WebView that solved the challenge, whose identity the
     * clearance cookie belongs to. Returns null when no solved browser can serve the route.
     */
    suspend fun fetchPage(url: String, headers: Map<String, String>): FetchedPage? {
        val view = ensureSolvedView() ?: return null
        return fetcher.fetch(view, url, headers, FETCH_TIMEOUT_MILLIS)?.also { page ->
            // A served document proves the clearance works from this browser; a 403 proves nothing.
            if (page.statusCode in 200..399) cookies.markVerified()
        }
    }

    private suspend fun ensureSolvedView(): WebView? {
        solvedView?.let { return it }
        return mutex.withLock {
            solvedView ?: run {
                if (runChallengeLocked()) solvedView else null
            }
        }
    }

    private suspend fun runChallengeLocked(): Boolean {
        for (attempt in 1..CHALLENGE_ATTEMPTS) {
            Log.i(TAG, "solve: starting webview challenge attempt=$attempt")
            val solved = try {
                releaseSolvedViewLocked()
                clearWebViewCookies()
                withTimeoutOrNull(SOLVE_TIMEOUT_MILLIS) { runChallenge() } != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "solve: challenge failed", failure)
                false
            }
            Log.i(TAG, "solve: completed=$solved clearancePresent=${cookies.hasClearance()} attempt=$attempt")
            if (solved) {
                cookies.markVerified()
                cookies.harvest()
                return true
            }
            // A single stalled verification does not mean the next one will stall too; the
            // managed challenge is re-rolled with a new Ray ID on every page load.
            delay(CHALLENGE_RETRY_DELAY_MILLIS)
        }
        return false
    }

    /** A stale cf_clearance poisons the challenge flow, so every attempt starts clean. */
    private suspend fun clearWebViewCookies() {
        withContext(Dispatchers.Main.immediate) {
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    CookieManager.getInstance().removeAllCookies { removed ->
                        Log.i(TAG, "webview cookies cleared removed=$removed")
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w(TAG, "cookie reset failed", failure) }
            CookieManager.getInstance().flush()
        }
    }

    /**
     * Chromium owns TLS, so the network path that resets plain ClientHellos must be relayed:
     * the same authenticated loopback CONNECT relay the NTK browser uses, with the first TLS
     * record fragmented.
     */
    private suspend fun runChallenge(): WebView? {
        val relay = BrowserTlsRelay()
        var window: ChallengeWindow? = null
        var solved: WebView? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                if (!installChallengeProxyOverride(relay, main)) {
                    Log.w(TAG, "proxy override unavailable; loading without relay")
                    return@withContext
                }
                val challengeWindow = ChallengeWindow(appContext)
                window = challengeWindow
                solved = awaitClearance(relay, challengeWindow)
            }
        } finally {
            if (solved != null) {
                // The clearance cookie belongs to this browser, so the solved view and its relay
                // stay alive and challenged routes are replayed from inside it.
                solvedView = solved
                solvedWindow = window
                solvedRelay = relay
            } else {
                withContext(Dispatchers.Main.immediate) { window?.close() }
                withContext(NonCancellable) {
                    clearChallengeProxyOverride(main)
                    relay.close()
                }
            }
        }
        return solved
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun awaitClearance(
        relay: BrowserTlsRelay,
        window: ChallengeWindow,
    ): WebView? = coroutineScope {
        val workScope = this
        suspendCancellableCoroutine { continuation: CancellableContinuation<WebView?> ->
            var settled = false
            var pollJob: Job? = null
            val captures = mutableListOf<Job>()
            var consoleLines = 0
            val webView = AndroidBrowserViews.create(window.context)
            val finish: (Boolean, String) -> Unit = { cleared, reason ->
                if (!settled) {
                    settled = true
                    pollJob?.cancel()
                    captures.forEach(Job::cancel)
                    Log.i(TAG, "challenge finished cleared=$cleared reason=$reason")
                    if (!cleared) {
                        // Chromium aborts the process when a WebView is stopped or destroyed from
                        // inside its own callback, so teardown always defers one main-loop turn.
                        main.post { teardownChallengeWebView(webView) }
                    }
                    if (continuation.isActive) continuation.resume(if (cleared) webView else null)
                }
            }
            var clearanceCheckAt = 0L
            fun checkClearance() {
                if (settled) return
                val now = System.currentTimeMillis()
                // A probe callback lost to a navigation must not wedge the guard forever, so the
                // guard is time-bound instead of sticky. No reload here: the cf_clearance that
                // appears mid-challenge is still pending, and navigating away aborts the challenge
                // script that would otherwise finish and promote it.
                if (now - clearanceCheckAt < CLEARANCE_CHECK_GUARD_MILLIS) return
                clearanceCheckAt = now
                webView.evaluateJavascript(CLEARANCE_PROBE_SCRIPT) { value ->
                    if (!settled && value?.trim('"') == "clear") {
                        runCatching { CookieManager.getInstance().flush() }
                        finish(true, "page-cleared")
                    }
                }
            }
            continuation.invokeOnCancellation { main.post { finish(false, "cancelled") } }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = sourceUserAgent
            webView.addJavascriptInterface(fetcher.Bridge(), BRIDGE_NAME)
            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                                                       host: String, realm: String) {
                    val credentials = relay.credentials(host, realm)
                    Log.i(TAG, "proxy auth host=$host realm=$realm matched=${credentials != null}")
                    if (credentials == null) handler.cancel()
                    else handler.proceed(credentials.first, credentials.second)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Log.i(TAG, "page started $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i(TAG, "page finished $url title=${view.title} clearancePresent=${cookies.hasClearance()}")
                    if (cookies.clearanceValue() != null) checkClearance()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    Log.w(TAG, "page error ${error.errorCode} ${error.description} ${request.url}")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Returning true is mandatory: an unhandled renderer death takes the app
                    // process with it, silently and with no Java stack.
                    Log.w(TAG, "webview renderer gone didCrash=${detail.didCrash()}")
                    finish(false, "renderer-gone")
                    return true
                }

                override fun shouldInterceptRequest(view: WebView,
                                                     request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    Log.i(TAG, "request ${request.method} ${request.url}")
                    return null
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    if (consoleLines < 80) {
                        consoleLines++
                        val source = message.sourceId()?.substringAfterLast('/')?.take(24) ?: ""
                        Log.i(TAG, "console[${message.messageLevel()}][$source] ${message.message()}")
                    }
                    return true
                }
            }
            val startedAt = System.currentTimeMillis()
            var lastProbe = 0L
            var clicksSent = 0
            fun startPolling() {
                pollJob = workScope.launch {
                    delay(POLL_INTERVAL_MILLIS)
                    while (isActive && !settled) {
                        // cf_clearance rotates while the challenge is still pending, so the cookie
                        // alone proves nothing; only the interstitial's markers disappearing does.
                        if (cookies.clearanceValue() != null) checkClearance()
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed - lastProbe > 4_000 && elapsed > 5_000) {
                            lastProbe = elapsed
                            webView.evaluateJavascript(PROBE_SCRIPT) { value ->
                                if (settled) return@evaluateJavascript
                                Log.i(TAG, "probe $value")
                                if (value != null && value != "null" && clicksSent < 3) {
                                    clicksSent++
                                    if (clicksSent == 1) {
                                        captureChallengeFrame(appContext, webView, "challenge-pre.png")
                                        webView.evaluateJavascript(TAP_PROBE_SCRIPT, null)
                                    }
                                    clickChallengeFrames(webView, value, clicksSent)
                                    val attempt = clicksSent
                                    captures += workScope.launch {
                                        delay(1_500L)
                                        if (!settled) {
                                            captureChallengeFrame(appContext, webView, "challenge-tap$attempt.png")
                                        }
                                    }
                                }
                            }
                        }
                        if (elapsed % 4_000 < POLL_INTERVAL_MILLIS) {
                            Log.i(TAG, "poll ${elapsed}ms url=${webView.url} clearancePresent=${cookies.hasClearance()}")
                        }
                        delay(POLL_INTERVAL_MILLIS)
                    }
                }
            }
            if (spoofsDeviceIdentity &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, FINGERPRINT_SCRIPT, setOf("*"))
            }
            webView.resumeTimers()
            window.attach(webView)
            // A persisted clearance that is still valid makes the interstitial resolve at once.
            cookies.seedWebView()
            Log.i(TAG, "webview created ua=${webView.settings.userAgentString} relay=${relay.proxyUrl} loading $ORIGIN")
            webView.loadUrl(ORIGIN)
            startPolling()
        }
    }

    /** Replaces the solved browser; its relay and proxy override are torn down with it. */
    private suspend fun releaseSolvedViewLocked() {
        val view = solvedView ?: return
        val window = solvedWindow
        val relay = solvedRelay
        solvedView = null
        solvedWindow = null
        solvedRelay = null
        withContext(Dispatchers.Main.immediate) {
            teardownChallengeWebView(view)
            window?.close()
        }
        withContext(NonCancellable) {
            clearChallengeProxyOverride(main)
            relay?.close()
        }
    }

    private companion object {
        const val ORIGIN = "https://newxtoon1.com"
        const val SOLVE_TIMEOUT_MILLIS = 25_000L
        const val FETCH_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 400L
        const val CLEARANCE_CHECK_GUARD_MILLIS = 1_000L
        const val CHALLENGE_ATTEMPTS = 3
        const val CHALLENGE_RETRY_DELAY_MILLIS = 1_000L
        // The emulator model and build id are the loudest "not a phone" markers left in the
        // user agent; a real device identity replaces them (no request header contradicts it).
        const val DEVICE_MODEL = "SM-S918N"
        const val DEVICE_BUILD = "UP1A.231005.007"
        val DEVICE_MARKER = Regex("Android [0-9]+; [^;)]+ Build/[^;)]+")
    }
}

/**
 * Chromium never delivers frames to a detached WebView, and Cloudflare's managed challenge
 * measures timing with requestAnimationFrame. A virtual display with a drained surface and a
 * presentation window keeps the renderer producing frames without any user-visible window.
 */
private class ChallengeWindow(context: Context) : Closeable {
    private val manager = requireNotNull(context.getSystemService(DisplayManager::class.java))
    private val frames = HandlerThread("newxtoon-clearance-frames").apply { start() }
    private val surface = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() },
            Handler(frames.looper))
    }
    private val display = manager.createVirtualDisplay("newxtoon-clearance", WIDTH, HEIGHT, DENSITY,
        surface.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
    private val presentation = Presentation(
        context.createDisplayContext(display.display), display.display)

    val context: Context = presentation.context

    fun attach(webView: WebView) {
        presentation.setContentView(webView)
        presentation.show()
    }

    override fun close() {
        runCatching { presentation.dismiss() }
        runCatching { display.release() }
        runCatching { surface.close() }
        runCatching { frames.quitSafely() }
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2340
        const val DENSITY = 420
    }
}

