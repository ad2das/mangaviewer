package ml.melun.mangaview.app

import android.os.Handler
import android.util.Log
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.data.network.BrowserTlsRelay
import ml.melun.mangaview.source.ntk.AndroidBrowserViews

private const val TAG = "NewxtoonClearance"
private const val REPLAY_COMMIT_TIMEOUT_MS = 20_000L

/** A committed replay browser: the view plus the resources keeping its identity alive. */
internal class ReplayBrowser(
    val view: WebView,
    val window: ChallengeWindow,
    val relay: BrowserTlsRelay,
)

/**
 * Stands up the inert replay browser: the clearance cookie is seeded into the WebView jar and an
 * inert same-origin document commits through loadDataWithBaseURL, so fetches issued from it carry
 * the proven browser identity immediately while no site content is ever loaded.
 */
internal class NewxtoonReplayView(
    private val appContext: android.content.Context,
    private val cookies: NewxtoonCookieStore,
    private val fetcher: NewxtoonFetchBridge,
    private val main: Handler,
    private val sourceUserAgent: String,
    private val spoofsDeviceIdentity: Boolean,
    private val engineChromeVersion: String,
) {
    /** Builds and commits the replay view; every failure path tears its browser down again. */
    suspend fun create(): ReplayBrowser? {
        val relay = BrowserTlsRelay()
        var window: ChallengeWindow? = null
        var view: WebView? = null
        try {
            // A Presentation needs a main-thread Looper even when the caller has none.
            val replayWindow = withContext(Dispatchers.Main.immediate) { ChallengeWindow(appContext) }
            window = replayWindow
            view = build(replayWindow, relay) ?: return null
            val committed = try {
                // No callback is guaranteed after loadDataWithBaseURL (OEM WebView quirks drop
                // onPageFinished/onReceivedError); create() runs under the clearance mutex, so an
                // unbounded suspend here wedges every later clearance solve.
                withTimeout(REPLAY_COMMIT_TIMEOUT_MS) { awaitCommit(view, relay) }
            } catch (timeout: TimeoutCancellationException) {
                Log.w(TAG, "replay view commit timed out")
                false
            }
            if (!committed) {
                close(view, replayWindow, relay)
                return null
            }
            return ReplayBrowser(view, replayWindow, relay)
        } catch (cancelled: CancellationException) {
            // The browser outlives this coroutine unless it is destroyed now; teardown cannot
            // wait on a dispatcher the cancelled context would refuse.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                view?.let { teardownChallengeWebView(it) }
                window?.close()
            }
            withContext(NonCancellable) {
                clearChallengeProxyOverride(main)
                relay.close()
            }
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "replay view failed", failure)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                view?.let { teardownChallengeWebView(it) }
                window?.close()
            }
            withContext(NonCancellable) {
                clearChallengeProxyOverride(main)
                relay.close()
            }
            return null
        }
    }

    suspend fun close(replay: ReplayBrowser) = close(replay.view, replay.window, replay.relay)

    private suspend fun close(view: WebView, window: ChallengeWindow, relay: BrowserTlsRelay) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            teardownChallengeWebView(view)
            window.close()
        }
        withContext(NonCancellable) {
            clearChallengeProxyOverride(main)
            relay.close()
        }
    }

    private suspend fun build(window: ChallengeWindow, relay: BrowserTlsRelay): WebView? {
        var view: WebView? = null
        withContext(Dispatchers.Main.immediate) {
            if (!installChallengeProxyOverride(relay, main)) {
                Log.w(TAG, "proxy override unavailable; replay view stays on the direct route")
            }
            val webView = AndroidBrowserViews.create(window.context)
            // The replay browser is offscreen and must never ride the app's GPU context: when
            // HWUI loses that context, Chromium aborts the process on the next functor draw
            // ("Non owned context lost!", SIGTRAP on RenderThread). A software layer keeps the
            // replay browser off the functor path entirely.
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = sourceUserAgent
            if (spoofsDeviceIdentity) applySpoofedUserAgentMetadata(webView, engineChromeVersion)
            webView.addJavascriptInterface(fetcher.Bridge(), BRIDGE_NAME)
            webView.webViewClient = client(relay)
            window.attach(webView)
            cookies.seedWebView()
            view = webView
        }
        return view
    }

    private fun client(relay: BrowserTlsRelay): WebViewClient = object : WebViewClient() {
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                                               host: String, realm: String) {
            relay.credentials(host, realm)?.let { handler.proceed(it.first, it.second) }
                ?: handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "replay view renderer gone didCrash=${detail.didCrash()}")
            return true
        }
    }

    private suspend fun awaitCommit(ready: WebView, relay: BrowserTlsRelay): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                ready.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                                                           host: String, realm: String) {
                        relay.credentials(host, realm)?.let { handler.proceed(it.first, it.second) }
                            ?: handler.cancel()
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.w(TAG, "replay view renderer gone didCrash=${detail.didCrash()}")
                        if (continuation.isActive) continuation.resume(false)
                        return true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                                 error: WebResourceError) {
                        if (request.isForMainFrame && continuation.isActive) continuation.resume(false)
                    }
                }
                // loadDataWithBaseURL commits a same-origin document without any network round trip;
                // fetches only need the committed origin, not a rendered site.
                ready.loadDataWithBaseURL(NEWXTOON_ORIGIN, REPLAY_DOCUMENT_HTML, "text/html",
                    Charsets.UTF_8.name(), null)
                continuation.invokeOnCancellation { ready.stopLoading() }
            }
        }
}
