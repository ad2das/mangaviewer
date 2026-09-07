package ml.melun.mangaview.source.ntk

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal data class NtkBrowserHostCallbacks(
    val currentRequest: () -> RemoteRequest?,
    val images: (String, String, String, Long, Long) -> Unit,
    val phase: (String, String, String, Int, Long, Long) -> Unit,
    val warmPhase: (String, Long, String, Int) -> Unit,
    val preflightChallenge: (String, String, Long, Int, String) -> Unit,
    val startAuthorization: (RemoteRequest) -> Unit,
    val deliverDescriptor: (RemoteRequest) -> Unit,
    val episodeResponse: (WebResourceRequest) -> WebResourceResponse?,
    val runtimeWarmResponse: (WebResourceRequest) -> WebResourceResponse?,
    val runtimeWarmFinished: (String) -> Unit,
    val runtimeWarmFailed: () -> Unit,
    val fail: (RemoteRequest, String) -> Unit,
    val rendererGone: (WebView, RemoteRequest?) -> Unit,
)

/** Owns the sole WebView and document-start script; it owns no ACK request state. */
internal class NtkBrowserHost(
    private val context: Context,
    private val profileName: () -> String,
    private val staticResources: NtkBrowserStaticResourceCache?,
    private val callbacks: NtkBrowserHostCallbacks,
) {
    var current: WebView? = null
        private set
    private var captureScript: ScriptHandler? = null

    fun acquire(userAgent: String): WebView {
        current?.let { return it }
        val bridge = HostBridge(
            callbacks.images,
            callbacks.phase,
            callbacks.warmPhase,
            callbacks.preflightChallenge,
        )
        val client = NtkBrowserGatewayClient(
            callbacks.currentRequest,
            staticResources,
            callbacks.startAuthorization,
            callbacks.deliverDescriptor,
            callbacks.episodeResponse,
            callbacks.runtimeWarmResponse,
            callbacks.runtimeWarmFinished,
            callbacks.runtimeWarmFailed,
            callbacks.fail,
            callbacks.rendererGone,
        )
        return NtkBrowserViewFactory.create(
            context,
            userAgent,
            bridge,
            QuietChromeClient(),
            client,
            profileName(),
        ).apply(::layoutForProviderObservation).also { current = it }
    }

    fun installAuthorization(view: WebView, request: RemoteRequest): Boolean {
        val installed = installDocumentStartScript(view, authorizationSource(request))
        if (installed && request.descriptor != null) request.manifestDescriptorInstalled = true
        return installed
    }

    fun startAuthorization(request: RemoteRequest) {
        current?.evaluateJavascript(authorizationSource(request), null)
    }

    fun retire() {
        removeCaptureScript()
        current?.apply {
            stopLoading()
            removeJavascriptInterface(NtkBrowserCaptureScript.BRIDGE_NAME)
            destroy()
        }
        current = null
    }

    fun park() {
        removeCaptureScript()
        current?.let { applyRenderPolicy(it, NtkBrowserRenderPhase.PARKED) }
    }

    fun rendererGone(view: WebView) {
        removeCaptureScript()
        if (current === view) current = null
        view.destroy()
    }

    private fun authorizationSource(request: RemoteRequest): String {
        val challengeAge = request.challengeReceivedAtMillis.takeIf { it > 0L }?.let {
            (android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
        } ?: 0L
        val source = NtkBrowserCaptureScript.source + "\n" +
            NtkBrowserRequestKey.source + "\n" +
            (request.descriptor?.let(NtkBrowserManifestKick::source).orEmpty()) + "\n" +
            NtkBrowserManifestRequest.source + "\n" +
            NtkBrowserChallengePreflight.seed(request.challengePayload, challengeAge) + "\n" +
            NtkBrowserChallengeSingleFlight.source + "\n" +
            NtkBrowserEarliestAck.source
        return NtkBrowserCaptureScript.boundSource(request, source)
    }

    private fun installDocumentStartScript(view: WebView, source: String): Boolean {
        removeCaptureScript()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        captureScript = WebViewCompat.addDocumentStartJavaScript(view, source, setOf("*"))
        return true
    }

    private fun removeCaptureScript() {
        runCatching { captureScript?.remove() }
        captureScript = null
    }
}

private class QuietChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
}

internal class HostBridge(
    private val images: (String, String, String, Long, Long) -> Unit,
    private val phase: (String, String, String, Int, Long, Long) -> Unit,
    private val warmPhase: (String, Long, String, Int) -> Unit,
    private val preflightChallenge: (String, String, Long, Int, String) -> Unit,
) {
    @JavascriptInterface
    fun onImages(origin: String, path: String, payload: String, requestId: Long, epoch: Long) =
        images(origin, path, payload, requestId, epoch)

    @JavascriptInterface
    fun onPhase(origin: String, path: String, phase: String, status: Int, requestId: Long, epoch: Long) =
        this.phase(origin, path, phase, status, requestId, epoch)

    @JavascriptInterface
    fun onWarmPhase(origin: String, generation: Long, phase: String, status: Int) =
        warmPhase(origin, generation, phase, status)

    @JavascriptInterface
    fun onPreflightChallenge(
        origin: String,
        path: String,
        requestId: Long,
        status: Int,
        payload: String,
    ) = preflightChallenge(origin, path, requestId, status, payload)
}
