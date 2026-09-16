package ml.melun.mangaview.app

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.ntk.AndroidBrowserViews
import okhttp3.CookieJar
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "NewxtoonClearance"

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
 * Solves Cloudflare's managed challenge for the newxtoon origin in a real WebView and shares the
 * resulting cookies with every OkHttp route that talks to the origin.
 */
internal class NewxtoonClearance(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cookies = NewxtoonCookieStore(ORIGIN)
    val cookieJar: CookieJar get() = cookies.cookieJar
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Cloudflare scores the "wv" WebView marker in the user agent as a bot signal, so the string
     * is reduced to the plain Chromium identity of the same engine build. The clearance cookie is
     * bound to the user agent, so the catalog and engine transports must send exactly this string.
     */
    val sourceUserAgent: String = (runCatching { WebSettings.getDefaultUserAgent(appContext) }
        .getOrElse { fallbackUserAgent() })
        .replace("; wv", "")
        .replace(" Version/4.0", "")

    private fun fallbackUserAgent(): String {
        val version = runCatching {
            WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName
        }.getOrNull() ?: "124.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$version Mobile Safari/537.36"
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

    private suspend fun runChallengeLocked(): Boolean {
        Log.i(TAG, "solve: starting webview challenge")
        val solved = try {
            clearWebViewCookies()
            withTimeoutOrNull(SOLVE_TIMEOUT_MILLIS) { runChallenge() } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "solve: challenge failed", failure)
            false
        }
        Log.i(TAG, "solve: completed=$solved clearancePresent=${cookies.hasClearance()}")
        if (solved) cookies.harvest()
        return solved
    }

    /** Cloudflare's interstitial title; anything else means the real site has loaded. */
    private fun isChallengeTitle(title: String?): Boolean =
        title.isNullOrBlank() || title.contains("Just a moment", ignoreCase = true)

    /**
     * The WebView persists cookies across app runs, and a stale cf_clearance poisons the
     * challenge flow (the widget never produces an interactive checkbox). Start clean.
     * CookieManager callbacks are delivered through the calling thread's Looper, so this
     * must run on the main thread or the callback never fires.
     */
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

    private fun captureFrame(webView: WebView, name: String) {
        runCatching {
            webView.invalidate()
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            val dir = File(appContext.getExternalFilesDir(null), "newxtoon-search")
            dir.mkdirs()
            FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            Log.i(TAG, "captured $name")
        }.onFailure { Log.w(TAG, "capture failed", it) }
    }

    /**
     * Chromium owns TLS, so the network path that resets plain ClientHellos must be relayed:
     * the same authenticated loopback CONNECT relay the NTK browser uses, with the first TLS
     * record fragmented.
     */
    private suspend fun runChallenge(): Boolean {
        val relay = BrowserTlsRelay()
        return try {
            withContext(Dispatchers.Main.immediate) {
                if (!installProxyOverride(relay)) {
                    Log.w(TAG, "proxy override unavailable; loading without relay")
                    return@withContext false
                }
                val window = ChallengeWindow(appContext)
                try {
                    awaitClearance(relay, window)
                } finally {
                    window.close()
                }
            }
        } finally {
            withContext(NonCancellable) {
                awaitClearProxyOverride()
                relay.close()
            }
        }
    }

    private suspend fun installProxyOverride(relay: BrowserTlsRelay): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val config = ProxyConfig.Builder()
                .addProxyRule(relay.proxyUrl, ProxyConfig.MATCH_HTTPS)
                .build()
            try {
                ProxyController.getInstance().setProxyOverride(config, { main.post(it) }) {
                    if (continuation.isActive) continuation.resume(true)
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "proxy override failed", failure)
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private suspend fun awaitClearProxyOverride() {
        runCatching {
            suspendCancellableCoroutine<Unit> { continuation ->
                ProxyController.getInstance().clearProxyOverride({ main.post(it) }) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun awaitClearance(
        relay: BrowserTlsRelay,
        window: ChallengeWindow,
    ): Boolean = coroutineScope {
        val workScope = this
        suspendCancellableCoroutine { continuation ->
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
                    // Chromium aborts the process when a WebView is stopped or destroyed from
                    // inside its own callback, so teardown always defers one main-loop turn.
                    main.post { teardownWebView(webView) }
                    if (continuation.isActive) continuation.resume(cleared)
                }
            }
            continuation.invokeOnCancellation { main.post { finish(false, "cancelled") } }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = sourceUserAgent
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
                    if (cookies.clearanceValue() != null && !isChallengeTitle(view.title)) finish(true, "page-cleared")
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
                        // alone proves nothing; the real site loading is the only reliable signal.
                        if (cookies.clearanceValue() != null && !isChallengeTitle(webView.title)) {
                            runCatching { CookieManager.getInstance().flush() }
                            finish(true, "page-cleared")
                            return@launch
                        }
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed - lastProbe > 4_000 && elapsed > 5_000) {
                            lastProbe = elapsed
                            webView.evaluateJavascript(PROBE_SCRIPT) { value ->
                                if (settled) return@evaluateJavascript
                                Log.i(TAG, "probe $value")
                                if (value != null && value != "null" && clicksSent < 3) {
                                    clicksSent++
                                    if (clicksSent == 1) {
                                        captureFrame(webView, "challenge-pre.png")
                                        webView.evaluateJavascript(TAP_PROBE_SCRIPT, null)
                                    }
                                    clickChallengeFrames(webView, value, clicksSent)
                                    val attempt = clicksSent
                                    captures += workScope.launch {
                                        delay(1_500L)
                                        if (!settled) captureFrame(webView, "challenge-tap$attempt.png")
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
            webView.resumeTimers()
            window.attach(webView)
            Log.i(TAG, "webview created ua=${webView.settings.userAgentString} relay=${relay.proxyUrl} loading $ORIGIN")
            webView.loadUrl(ORIGIN)
            startPolling()
        }
    }

    /** Detach before destroy; a renderer gone view must never be used again. */
    private fun teardownWebView(webView: WebView) {
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }

    /** Managed challenges render a Turnstile checkbox; a trusted tap can clear it. */
    private fun clickChallengeFrames(webView: WebView, raw: String, attempt: Int) {
        val json = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
        val probe = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val dpr = (probe?.optDouble("dpr", 2.6) ?: 2.6).toFloat().coerceAtLeast(1f)
        val viewHeight = webView.height.toFloat().coerceAtLeast(1f)
        val frames = probe?.optJSONArray("frames")
        if (frames != null) {
            for (index in 0 until frames.length()) {
                val frame = frames.optJSONObject(index) ?: continue
                if (!frame.optString("src").contains("challenges.cloudflare.com")) continue
                val x = frame.optDouble("x", 0.0).toFloat() * dpr
                val y = frame.optDouble("y", 0.0).toFloat() * dpr
                val width = frame.optDouble("w", 0.0).toFloat() * dpr
                val height = frame.optDouble("h", 0.0).toFloat() * dpr
                if (width < 120f || height < 40f || height > viewHeight * 0.25f) continue
                Log.i(TAG, "tapping turnstile frame $x,$y ${width}x$height")
                tap(webView, x + minOf(20f * dpr, width / 4f), y + height / 2f)
                return
            }
        }
        val hosts = probe?.optJSONArray("hosts")
        if (hosts != null) {
            for (index in 0 until hosts.length()) {
                val host = hosts.optJSONObject(index) ?: continue
                val x = host.optDouble("x", 0.0).toFloat() * dpr
                val y = host.optDouble("y", 0.0).toFloat() * dpr
                val width = host.optDouble("w", 0.0).toFloat() * dpr
                val height = host.optDouble("h", 0.0).toFloat() * dpr
                if (width < 120f || height < 40f || height > viewHeight * 0.25f) continue
                Log.i(TAG, "tapping widget host ${host.optString("sel")} $x,$y ${width}x$height")
                tap(webView, x + minOf(20f * dpr, width / 4f), y + height / 2f)
                return
            }
        }
        // The widget can hide inside a closed shadow root; fall back to where it renders.
        val viewWidth = webView.width.toFloat().coerceAtLeast(1f)
        val fractions = when (attempt % 3) {
            1 -> 0.083f to 0.400f
            2 -> 0.083f to 0.406f
            else -> 0.076f to 0.400f
        }
        val x = viewWidth * fractions.first
        val y = viewHeight * fractions.second
        Log.i(TAG, "tapping fallback checkbox ${x.roundToInt()},${y.roundToInt()} attempt=$attempt view=${viewWidth.roundToInt()}x${viewHeight.roundToInt()}")
        tap(webView, x, y)
    }

    private fun tap(webView: WebView, x: Float, y: Float) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        fun event(action: Int, at: Long): MotionEvent {
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            })
            return MotionEvent.obtain(0L, at, action, 1, properties, coordinates, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val downTime = SystemClock.uptimeMillis()
        val down = event(MotionEvent.ACTION_DOWN, downTime).let {
            webView.dispatchTouchEvent(it).also { _ -> it.recycle() }
        }
        val up = event(MotionEvent.ACTION_UP, downTime + 80).let {
            webView.dispatchTouchEvent(it).also { _ -> it.recycle() }
        }
        Log.i(TAG, "tap ${x.roundToInt()},${y.roundToInt()} down=$down up=$up")
    }

    private companion object {
        const val ORIGIN = "https://newxtoon1.com"
        const val SOLVE_TIMEOUT_MILLIS = 25_000L
        const val POLL_INTERVAL_MILLIS = 400L
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

/** Retries a challenged newxtoon request after the WebView clears the origin. */
internal class NewxtoonClearanceTransport(
    private val inner: SourceTransport,
    private val origin: String,
    private val solve: suspend () -> Boolean,
    private val solveFresh: suspend () -> Boolean = solve,
) : SourceTransport by inner, Closeable {
    override suspend fun execute(request: SourceRequest): SourceResponse {
        val response = inner.execute(request)
        if (response.statusCode != 403 || !request.url.startsWith(origin)) return response
        Log.i(TAG, "challenged ${response.statusCode} ${request.url} mitigated=${response.header("cf-mitigated")}")
        response.close()
        val cleared = solve()
        Log.i(TAG, "solve=$cleared retrying ${request.url}")
        var retried = inner.execute(request)
        // The edge occasionally keeps challenging the first request after a fresh solve; give it a
        // moment, and if it still refuses, treat the cached clearance as stale and solve again.
        for (attempt in 1..2) {
            if (retried.statusCode != 403 || retried.header("cf-mitigated")?.contains("challenge") != true) break
            Log.i(TAG, "retry still challenged; attempt=$attempt")
            retried.close()
            delay(1_500L * attempt)
            if (attempt == 2) solveFresh()
            retried = inner.execute(request)
        }
        Log.i(TAG, "retry status=${retried.statusCode} mitigated=${retried.header("cf-mitigated")} server=${retried.header("server")} ray=${retried.header("cf-ray")}")
        return retried
    }

    private fun SourceResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")

    override fun close() {
        (inner as? AutoCloseable)?.close()
    }
}
