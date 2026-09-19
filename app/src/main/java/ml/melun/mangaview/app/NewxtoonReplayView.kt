package ml.melun.mangaview.app

import android.os.Handler
import android.util.Log
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ml.melun.mangaview.data.network.BrowserTlsRelay
import ml.melun.mangaview.source.ntk.AndroidBrowserViews

private const val TAG = "NewxtoonClearance"

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
            if (!awaitCommit(view, relay)) {
                close(view, replayWindow, relay)
                return null
            }
            return ReplayBrowser(view, replayWindow, relay)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "replay view failed", failure)
            withContext(Dispatchers.Main.immediate) {
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
        withContext(Dispatchers.Main.immediate) {
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
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = sourceUserAgent
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
