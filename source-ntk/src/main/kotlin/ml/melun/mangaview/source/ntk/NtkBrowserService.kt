package ml.melun.mangaview.source.ntk

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebChromeClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.IOException

/** Runs NTK's official browser acknowledgement outside the reader process. */
open class NtkBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(IncomingHandler())
    private val startup = NtkBrowserStartup(this, ::startupReady, ::startupFailed)
    private var browser: WebView? = null
    private var active: RemoteRequest? = null
    private val ackPhases = NtkAckPhaseRelay { active }
    private var isStartupReady = false
    private var startupFailure: Throwable? = null
    private var pendingUserAgent: String? = null
    private var captureScript: ScriptHandler? = null
    private var completedDelivery: CompletedDelivery? = null

    override fun onCreate() = super.onCreate().also { startup.begin() }

    override fun onBind(intent: Intent?): IBinder = incoming.binder
    override fun onDestroy() {
        active?.replyError("NTK browser service stopped")
        active = null
        completedDelivery = null
        retireBrowser()
        startup.close()
        super.onDestroy()
    }

    private fun startupReady() {
        isStartupReady = true
        val userAgent = pendingUserAgent ?: active?.userAgent ?: return
        runCatching { browser(userAgent) }.onFailure(::startupFailed)
        active?.let(::navigate)
    }

    private fun startupFailed(failure: Throwable) {
        startupFailure = failure
        active?.let { fail(it, "NTK browser startup failed: ${failure.message}") }
        Log.e(TAG, "browser startup failed", failure)
    }

    private fun resolve(message: Message) {
        val request = runCatching { RemoteRequest.from(message) }.getOrElse { failure ->
            replyError(message, failure.message ?: "Invalid NTK browser request")
            return
        }
        completedDelivery?.takeIf { it.matches(request) }?.let { completed ->
            sendPayload(request.requestId, request.primaryRecipient, completed.payload)
            return
        }
        val current = active
        if (current != null && current.key == request.key) {
            val exactRedelivery = current.contains(request.requestId)
            current.add(request.requestId, request.primaryRecipient)
            if (current.ackReadyReported) {
                sendAckReady(request.requestId, request.primaryRecipient)
            }
            val completed = current.delivery.completedPayload()
            completed?.let {
                sendPayload(request.requestId, request.primaryRecipient, it)
            }
            if (NtkDeliveryRedrivePolicy.shouldRedrive(
                    exactRedelivery,
                    completed != null,
                    current.deliveryRedrives,
                )) {
                redriveIncompleteDelivery(current)
            }
            return
        }
        current?.replyError("NTK browser request was superseded")
        start(request)
    }

    private fun warm(message: Message) {
        val userAgent = runCatching {
            message.data.requiredString(NtkBrowserProtocol.KEY_USER_AGENT).also {
                require(it.length <= MAX_USER_AGENT_LENGTH) { "NTK user agent is too long" }
            }
        }.getOrElse { failure ->
            Log.w(TAG, "browser warmup rejected", failure)
            return
        }
        pendingUserAgent = userAgent
        if (!isStartupReady || startupFailure != null) return
        runCatching { browser(userAgent) }
            .onFailure { failure -> Log.w(TAG, "browser warmup failed", failure) }
    }

    private fun start(request: RemoteRequest) {
        active = request
        pendingUserAgent = request.userAgent
        startupFailure?.let { failure ->
            fail(request, "NTK browser startup failed: ${failure.message}")
            return
        }
        if (!isStartupReady) return
        navigate(request)
    }

    private fun redriveIncompleteDelivery(request: RemoteRequest) {
        if (active !== request) return
        request.deliveryRedrives += 1
        retireBrowser()
        navigate(request)
    }

    private fun navigate(request: RemoteRequest) {
        if (active !== request) return
        runCatching {
            val adjacent = request.intent != ml.melun.mangaview.source.PreparationIntent.INITIAL_VIEW
            Process.setThreadPriority(
                if (adjacent) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT,
            )
            browser(request.userAgent).apply {
                visibility = View.VISIBLE
                setLayerType(View.LAYER_TYPE_NONE, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRendererPriorityPolicy(
                        if (adjacent) {
                            WebView.RENDERER_PRIORITY_WAIVED
                        } else {
                            WebView.RENDERER_PRIORITY_BOUND
                        },
                        adjacent,
                    )
                }
                resumeTimers()
                onResume()
                settings.userAgentString = request.userAgent
                // This detached worker exists only to complete the provider's official
                // document/JavaScript acknowledgement. Letting it fetch <img> resources would
                // duplicate the viewer-owned page requests and steal bandwidth from HARD pages.
                // XHR/fetch/script traffic remains enabled, so manifest authorization is intact.
                settings.blockNetworkImage = true
                settings.loadsImagesAutomatically = false
                request.authorizationStarted = false
                request.descriptorDelivered = false
                request.descriptorApplying = false
                request.captureInstalledAtDocumentStart = installNaturalAuthorization(this)
                loadUrl(request.key)
            }
        }.onFailure { failure ->
            fail(request, "NTK browser startup failed: ${failure.message}")
        }
    }

    private fun cancel(message: Message, quiesce: Boolean) {
        val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        val request = active?.takeIf { it.contains(requestId) } ?: return
        request.remove(requestId)
        if (quiesce || request.isEmpty()) quiesce(request)
    }

    private fun accept(origin: String, path: String, payload: String) {
        val request = active ?: return
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return
        if (request.key != key) return
        val accepted = request.delivery.accept(payload) ?: return
        completedDelivery = CompletedDelivery(request.requestId, request.key, accepted)
        request.replyPayload(accepted)
    }

    private fun descriptor(message: Message) {
        val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        val request = active?.takeIf { it.contains(requestId) } ?: return
        val descriptor = runCatching { RemoteDescriptor.from(message.data) }.getOrElse { failure ->
            fail(request, "NTK descriptor rejected: ${failure.message}")
            return
        }
        request.descriptor = descriptor
        deliverDescriptor(request)
    }

    private fun deliverDescriptor(request: RemoteRequest) {
        if (active !== request) return
        val descriptor = request.descriptor ?: return
        if (request.descriptorDelivered || request.descriptorApplying) return
        request.descriptorApplying = true
        applyResponseCookies(request, descriptor, 0)
    }

    private fun startAuthorization(request: RemoteRequest) {
        if (active !== request) return
        if (request.authorizationStarted) return
        request.authorizationStarted = true
        val source = NtkBrowserCaptureScript.source + "\n" +
            NtkBrowserChallengeSingleFlight.source + "\n" +
            NtkBrowserEarlyAck.source
        browser?.evaluateJavascript(source, null)
    }

    private fun applyResponseCookies(
        request: RemoteRequest,
        descriptor: RemoteDescriptor,
        index: Int,
    ) {
        if (active !== request) return
        if (index >= descriptor.responseCookies.size) {
            request.descriptorApplying = false
            request.descriptorDelivered = true
            startAuthorization(request)
            return
        }
        CookieManager.getInstance().setCookie(request.origin, descriptor.responseCookies[index]) {
            handler.post {
                if (active === request) {
                    applyResponseCookies(request, descriptor, index + 1)
                }
            }
        }
    }

    private fun fail(request: RemoteRequest, detail: String) {
        if (active !== request) return
        request.replyError(detail)
        quiesce(request)
    }

    private fun quiesce(request: RemoteRequest) {
        if (active !== request) return
        active = null
        parkBrowser()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun browser(userAgent: String): WebView {
        browser?.let { return it }
        WebView.setWebContentsDebuggingEnabled(false)
        return WebView(this).apply {
            // This service observes network/JavaScript state only. It must never acquire a GPU
            // layer or raster tiles that can interfere with the visible reader process.
            // The browser is never attached to a Window, so keeping the normal visible/layer
            // lifecycle cannot expose pixels or allocate a presentation Surface. Marking this
            // detached provider worker INVISIBLE/SOFTWARE/no-draw makes Chromium throttle the
            // official acknowledgement JavaScript and delays the reader manifest.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            settings.loadsImagesAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            // Provider JavaScript needs a measured viewport, but browser pixels are never shown.
            // Rasterizing the hidden document competes with the reader's first decoded frame.
            settings.offscreenPreRaster = false
            settings.userAgentString = userAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            }
            addJavascriptInterface(
                BrowserBridge(
                    images = { origin, path, payload ->
                        this@NtkBrowserService.handler.post { accept(origin, path, payload) }
                    },
                    phase = { origin, path, phase, status ->
                        this@NtkBrowserService.handler.post {
                            ackPhases.accept(origin, path, phase, status)
                        }
                    },
                ),
                NtkBrowserCaptureScript.BRIDGE_NAME,
            )
            webChromeClient = QuietChromeClient()
            webViewClient = NtkBrowserGatewayClient(
                currentRequest = { active },
                startAuthorization = ::startAuthorization,
                deliverDescriptor = ::deliverDescriptor,
                fail = ::fail,
                rendererGone = ::rendererGone,
            )
            layoutForProviderObservation(this)
            resumeTimers()
            onResume()
        }.also {
            browser = it
        }
    }

    private fun installNaturalAuthorization(view: WebView): Boolean {
        val source = NtkBrowserCaptureScript.source + "\n" +
            NtkBrowserChallengeSingleFlight.source + "\n" +
            NtkBrowserEarlyAck.source
        return installDocumentStartScript(view, source)
    }

    private fun installDocumentStartScript(view: WebView, source: String): Boolean {
        runCatching { captureScript?.remove() }
        captureScript = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        captureScript = WebViewCompat.addDocumentStartJavaScript(
            view,
            source,
            setOf("*"),
        )
        return true
    }

    private fun retireBrowser() {
        runCatching { captureScript?.remove() }
        captureScript = null
        browser?.apply {
            stopLoading()
            removeJavascriptInterface(NtkBrowserCaptureScript.BRIDGE_NAME)
            destroy()
        }
        browser = null
    }

    private fun parkBrowser() {
        // Any document or visibility lifecycle transition here runs Chromium cleanup at the exact
        // instant the reader starts decoding its first hardware buffer. Both about:blank and an
        // INVISIBLE/onPause transition produced delayed cross-process Surface stalls. Keep the
        // detached document intact, freeze JavaScript timers, and only waive process priority. The
        // next ACK replaces it in place; service shutdown remains the sole destruction boundary.
        runCatching { captureScript?.remove() }
        captureScript = null
        browser?.apply {
            pauseTimers()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
            }
        }
    }
    private fun rendererGone(view: WebView, request: RemoteRequest?) {
        runCatching { captureScript?.remove() }
        captureScript = null
        browser = null
        view.destroy()
        if (request != null && request.rendererRestarts < MAX_RENDERER_RESTARTS) {
            request.rendererRestarts += 1
            handler.post { if (active === request) navigate(request) }
            Log.w(TAG, "browser renderer restarted attempt=${request.rendererRestarts}")
        } else {
            active = null
            request?.replyError("NTK browser renderer stopped")
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                NtkBrowserProtocol.MSG_RESOLVE -> resolve(message)
                NtkBrowserProtocol.MSG_WARM -> warm(message)
                NtkBrowserProtocol.MSG_DESCRIPTOR -> descriptor(message)
                NtkBrowserProtocol.MSG_CANCEL -> cancel(message, quiesce = false)
                NtkBrowserProtocol.MSG_QUIESCE -> cancel(message, quiesce = true)
                else -> super.handleMessage(message)
            }
        }
    }

}

private class QuietChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
}

private fun replyError(message: Message, detail: String) {
    val recipient = message.replyTo ?: return
    val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
    sendError(requestId, recipient, detail)
}

private class BrowserBridge(
    private val images: (String, String, String) -> Unit,
    private val phase: (String, String, String, Int) -> Unit,
) {
    @JavascriptInterface
    fun onImages(origin: String, path: String, payload: String) = images(origin, path, payload)

    @JavascriptInterface
    fun onPhase(origin: String, path: String, phase: String, status: Int) =
        this.phase(origin, path, phase, status)
}

private class NtkAckPhaseRelay(
    private val currentRequest: () -> RemoteRequest?,
) {
    fun accept(origin: String, path: String, phase: String, status: Int) {
        val request = currentRequest() ?: return
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return
        if (request.key != key) return
        val safePhase = phase.take(MAX_PHASE_LENGTH).replace('\n', '_').replace('\r', '_')
        Log.d(ACK_TAG, "phase=$safePhase status=$status ageMs=${request.ageMillis()}")
        if (isAuthorizationProof(phase, status)) request.replyAckReady()
    }

    private fun isAuthorizationProof(phase: String, status: Int): Boolean =
        status in 200..299 && (
            phase.startsWith("ack-meta:ok=true,acked=true") ||
                phase.startsWith("challenge-meta:ok=true,ackValid=true")
            )
}

private fun layoutForProviderObservation(view: WebView) {
    // Give the provider real DOM geometry without rasterizing an invisible device-size page.
    val width = MIN_BROWSER_WIDTH_PX
    val height = MIN_BROWSER_HEIGHT_PX
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, width, height)
    view.clipBounds = Rect(0, 0, 1, 1)
}

private const val ACK_TAG = "NtkAck"
private const val MAX_PHASE_LENGTH = 192
