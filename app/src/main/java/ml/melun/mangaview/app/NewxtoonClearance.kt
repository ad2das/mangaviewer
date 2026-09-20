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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/** Reader-session key for this source; the clearance gate is keyed by source id. */
private const val NEWXTOON_SOURCE_KEY = "newxtoon"

/** The emulator's model and build id mark the session as non-phone; only there they are rewritten. */
private val spoofsDeviceIdentity: Boolean = run {
    val fingerprint = android.os.Build.FINGERPRINT
    val hardware = android.os.Build.HARDWARE
    fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
        hardware.contains("goldfish") || hardware.contains("ranchu") ||
        android.os.Build.MODEL.startsWith("sdk_")
}

/**
 * Solves Cloudflare's managed challenge for the newxtoon origin in a real WebView and shares the
 * resulting cookies with every OkHttp route that talks to the origin.
 */
internal class NewxtoonClearance(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cookies = NewxtoonCookieStore(NEWXTOON_ORIGIN, context)
    val cookieJar: CookieJar get() = cookies.cookieJar
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    /** Durable document store shared by every transport that replays through the solved view. */
    val documents = NewxtoonDocumentCache(context)
    /** Background scope for stale-while-revalidate refreshes launched by the transports. */
    val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The WebView that solved the challenge; its browser identity owns the clearance cookie. */
    @Volatile
    private var solvedView: WebView? = null
    /** Wall time the current solved view became usable; gates re-solves against a fresh view. */
    @Volatile
    private var solvedAtMillis = 0L
    /** Set when the edge refused a replay served by the current view, so it must be replaced. */
    @Volatile
    private var replayRefused = false
    /** Wall time the challenge ladder last ended without a clearance; holds off the next storm. */
    @Volatile
    private var solveFailedAtMillis = 0L
    /**
     * The challenge view while it is still resolving. A verified clearance is seeded before the
     * page loads, so this view can serve document fetches immediately instead of waiting for the
     * interstitial to fully resolve.
     */
    @Volatile
    private var settlingView: WebView? = null
    /** True once the settling view's document committed, so evaluated fetch code has an origin. */
    @Volatile
    private var settlingViewUsable = false
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

    /** Builds the inert replay browser that hosts fetches under the proven browser identity. */
    private val replayViews = NewxtoonReplayView(appContext, cookies, fetcher, main, sourceUserAgent)

    /** Challenge/replay browser machinery; only this class promotes a settled view. */
    private val challengePage = NewxtoonChallengePage(
        appContext, cookies, fetcher, main, sourceUserAgent, spoofsDeviceIdentity,
        onSettlingView = { settlingView = it },
        onSettlingUsable = { settlingViewUsable = it },
    )

    private fun fallbackUserAgent(): String {
        val version = runCatching {
            WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName
        }.getOrNull() ?: "124.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "$DEVICE_MODEL Build/$DEVICE_BUILD; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/$version Mobile Safari/537.36"
    }

    /**
     * Replaces a clearance the edge already refused. Queued requests reach this point together,
     * so a view that solved moments ago is reused: a second challenge would tear down the browser
     * its siblings are about to replay through and restart the whole storm.
     */
    suspend fun solveFresh(): Boolean {
        awaitForeignReaderIdle()
        return mutex.withLock {
            val age = System.currentTimeMillis() - solvedAtMillis
            if (solvedView != null && !replayRefused && age in 0..RE_SOLVE_GRACE_MILLIS) {
                Log.i(TAG, "solveFresh: reusing view solved ${age}ms ago")
                return@withLock true
            }
            Log.i(TAG, "solveFresh: starting webview challenge despite cached clearance")
            runChallengeLocked()
        }
    }

    /**
     * A challenge or replay browser may only be stood up while no reader session for another
     * source is scrolling: its software WebView draw otherwise stalls that reader's frames.
     */
    private suspend fun awaitForeignReaderIdle() =
        ViewerSessionActivity.awaitForeignIdle(NEWXTOON_SOURCE_KEY)

    /**
     * The edge challenged a request replayed by the solved browser, so the clearance that browser
     * holds is dead: trust is withdrawn and the next fetch stands up a fresh challenge instead of
     * replaying through the refused view again.
     */
    fun markReplayRefused() {
        Log.i(TAG, "replay refused by the edge; clearance marked stale")
        replayRefused = true
        cookies.markStale()
    }

    /**
     * Returns true only when a clearance cookie is present for the origin. A present cookie is
     * not enough to serve: the replay browser must exist too, so the view is stood up alongside
     * the cookie — instantly for a verified clearance, through the challenge otherwise.
     */
    suspend fun solve(): Boolean {
        awaitForeignReaderIdle()
        if (!cookies.hasClearance()) {
            return mutex.withLock {
                if (cookies.hasClearance()) {
                    cookies.harvest()
                    true
                } else {
                    runChallengeLocked()
                }
            }
        }
        Log.i(TAG, "solve: clearance cookie already present")
        cookies.harvest()
        // A cookie without a replay browser still pays the refused-request chain per document.
        return ensureSolvedView() != null
    }

    /**
     * True while a WebView that already cleared the challenge is alive, so transports can send
     * challenged routes to it directly instead of paying a refused HTTP request first.
     */
    val solvedViewReady: Boolean get() = solvedView != null

    /**
     * True once a clearance was proven to serve documents. The cookie is fingerprint-bound to the
     * solving browser, so the plain HTTP route is already known dead and requests may skip it.
     */
    val clearanceVerified: Boolean get() = cookies.hasVerifiedClearance()

    /** True while a proven clearance survives process death; safe to pre-warm on app start. */
    val persistedClearancePresent: Boolean get() = cookies.hasPersistedClearance()

    /**
     * Pre-creates the replay browser while the catalog opens so the first uncached document does
     * not pay a refused HTTP round trip plus a view spin-up. A verified clearance takes the
     * instant replay-view path; anything else resolves through the regular challenge.
     */
    suspend fun warmSolvedView() {
        awaitForeignReaderIdle()
        ensureSolvedView()
    }

    /**
     * Runs a challenged route inside the WebView that solved the challenge, whose identity the
     * clearance cookie belongs to. Returns null when no solved browser can serve the route.
     */
    suspend fun fetchPage(url: String, headers: Map<String, String>): FetchedPage? {
        // A verified clearance already rides with the page, so a still-settling challenge view
        // can serve the document while the interstitial finishes in the background.
        var view: WebView? = solvedView
            ?: if (cookies.hasVerifiedClearance()) {
                settlingView?.takeIf { settlingViewUsable }
            } else null
            ?: ensureSolvedView()
        var page = view?.let { fetcher.fetch(it, url, headers, FETCH_TIMEOUT_MILLIS) }
        if (page == null && view != null && view !== solvedView) {
            // The view died mid-fetch (refused replay tore it down); a fresh browser may already
            // be up, so the request retries once against it instead of paying the full timeout.
            view = solvedView?.takeIf { it !== view } ?: ensureSolvedView()
            page = view?.let { fetcher.fetch(it, url, headers, FETCH_TIMEOUT_MILLIS) }
        }
        if (view == null) {
            Log.i(TAG, "replay unavailable (no browser) for $url")
            return null
        }
        if (page == null) {
            Log.i(TAG, "replay bridge empty for $url")
            return null
        }
        // A served document proves the clearance works from this browser; a 403 proves nothing.
        if (page.statusCode in 200..399) cookies.markVerified()
        else if (page.statusCode == 403) {
            Log.i(TAG, "replay answered 403 for $url mitigated=${page.header("cf-mitigated")}")
        }
        return page
    }

    private suspend fun ensureSolvedView(): WebView? {
        solvedView?.let { if (!replayRefused) return it }
        return mutex.withLock {
            solvedView?.let { if (!replayRefused) return@withLock it }
            // The edge refused the old browser, so it is torn down and replaced; its withdrawn
            // trust is exactly why the replay-view shortcut below is now skipped.
            replayRefused = false
            releaseSolvedViewLocked()
            // Any clearance cookie stands the replay browser up without another interstitial: a
            // proven one serves immediately, and one Cloudflare planted while a challenge was
            // still spinning is proven by the first fetch through it. A refused replay marks the
            // clearance stale, so a dead cookie costs one round trip instead of a full challenge.
            if (cookies.hasClearance() && runReplayViewLocked()) solvedView
            else if (runChallengeLocked()) solvedView
            else null
        }
    }

    /**
     * Stands up a replay browser without loading the site at all: the clearance cookie is seeded
     * into the WebView jar and an inert document commits under the origin, so fetches issued from
     * it carry the proven browser identity immediately. Used only once a clearance is verified.
     */
    private suspend fun runReplayViewLocked(): Boolean {
        val replay = replayViews.create() ?: return false
        solvedView = replay.view
        solvedWindow = replay.window
        solvedRelay = replay.relay
        solvedAtMillis = System.currentTimeMillis()
        replayRefused = false
        Log.i(TAG, "replay view ready (no challenge page)")
        return true
    }

    private suspend fun runChallengeLocked(): Boolean {
        // A failed ladder is retried on a cool-down: queued callers would otherwise restart a
        // 75-second challenge each, and the edge answers a hammered origin with more challenges.
        val sinceFailure = System.currentTimeMillis() - solveFailedAtMillis
        if (sinceFailure in 0..SOLVE_FAILURE_COOLDOWN_MILLIS) {
            Log.i(TAG, "solve: cooling down after a failed challenge (${sinceFailure}ms ago)")
            return false
        }
        for (attempt in 1..CHALLENGE_ATTEMPTS) {
            Log.i(TAG, "solve: starting webview challenge attempt=$attempt")
            val solved = try {
                releaseSolvedViewLocked()
                // A clearance Cloudflare planted while the previous interstitial was still
                // spinning must survive the next attempt: wiping it is exactly what turns one
                // stalled verification into a failed ladder. Only a cookie-less start is clean.
                if (!cookies.hasClearance()) clearWebViewCookies()
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
                solvedAtMillis = System.currentTimeMillis()
                replayRefused = false
                return true
            }
            // Cloudflare plants a usable cf_clearance while the interstitial is still spinning,
            // so an attempt that timed out may already have earned one. Adopt it through the
            // replay browser — the first fetch proves it — instead of wiping it and re-rolling.
            if (cookies.hasClearance() && runReplayViewLocked()) {
                Log.i(TAG, "solve: adopted clearance planted during attempt=$attempt")
                cookies.markVerified()
                cookies.harvest()
                return true
            }
            // A single stalled verification does not mean the next one will stall too; the
            // managed challenge is re-rolled with a new Ray ID on every page load.
            delay(CHALLENGE_RETRY_DELAY_MILLIS)
        }
        solveFailedAtMillis = System.currentTimeMillis()
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
                solved = challengePage.awaitClearance(relay, challengeWindow)
            }
        } finally {
            if (solved != null) {
                // The clearance cookie belongs to this browser, so the solved view and its relay
                // stay alive and challenged routes are replayed from inside it.
                solvedView = solved
                solvedWindow = window
                solvedRelay = relay
            } else {
                // A cancelled ladder still owes the window its teardown; NonCancellable keeps the
                // main-thread detach reachable while the coroutine is already unwinding.
                withContext(NonCancellable + Dispatchers.Main.immediate) { window?.close() }
                withContext(NonCancellable) {
                    clearChallengeProxyOverride(main)
                    relay.close()
                }
            }
        }
        return solved
    }

    /** Replaces the solved browser; its relay and proxy override are torn down with it. */
    private suspend fun releaseSolvedViewLocked() {
        settlingView = null
        settlingViewUsable = false
        val view = solvedView ?: return
        val window = solvedWindow
        val relay = solvedRelay
        solvedView = null
        solvedWindow = null
        solvedRelay = null
        // Teardown must survive a cancelled caller: the solved browser is a heavy resource and a
        // plain withContext(Main) would skip the detach while the coroutine unwinds.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            teardownChallengeWebView(view)
            window?.close()
        }
        withContext(NonCancellable) {
            clearChallengeProxyOverride(main)
            relay?.close()
        }
    }

    private companion object {
        const val SOLVE_TIMEOUT_MILLIS = 75_000L
        const val FETCH_TIMEOUT_MILLIS = 15_000L
        const val RE_SOLVE_GRACE_MILLIS = 20_000L
        const val SOLVE_FAILURE_COOLDOWN_MILLIS = 60_000L
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
internal class ChallengeWindow(context: Context) : Closeable {
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

