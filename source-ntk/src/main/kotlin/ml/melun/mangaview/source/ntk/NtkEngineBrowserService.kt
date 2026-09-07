package ml.melun.mangaview.source.ntk

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/** One admitted engine operation. No speculative requests, replay cache or private network queue. */
class NtkEngineBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active: RemoteRequest? = null
    private var pendingCallbacks = 0
    private var retiring: Message? = null
    private var retiredId = INVALID_REQUEST_ID
    private var destroyed = false
    private val profile by lazy(LazyThreadSafetyMode.NONE) {
        require(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        ProfileStore.getInstance().getOrCreateProfile(PROFILE)
    }
    private val cookies by lazy(LazyThreadSafetyMode.NONE) { NtkBrowserCookieApplier(handler, profile.cookieManager) }
    private val phases = NtkAckPhaseRelay({ PROFILE }, { active }, ::installManifest, { profile.cookieManager })
    private val documents by lazy(LazyThreadSafetyMode.NONE) {
        NtkBrowserDocumentController(
            { active }, { false },
            { origin, values, completed ->
                val request = active
                val applier = cookies
                pendingCallbacks++
                applier.cookies(origin, values, isCurrent = { active === request && !destroyed }) { accepted ->
                    try { completed(accepted) } finally { callbackFinished() }
                }
            },
            ::navigate, ::authorize, ::installManifest, ::fail,
        )
    }
    private val host: NtkBrowserHost by lazy(LazyThreadSafetyMode.NONE) {
        NtkBrowserHost(this, { PROFILE }, null, NtkBrowserHostCallbacks(
            currentRequest = { active },
            images = { origin, path, payload, id, epoch -> handler.post { accept(origin, path, payload, id, epoch) } },
            phase = { origin, path, phase, status, id, epoch ->
                handler.post { phases.accept(origin, path, phase, status, id, epoch) }
            },
            warmPhase = { _, _, _, _ -> },
            preflightChallenge = { _, _, _, _, _ -> },
            startAuthorization = ::authorize,
            deliverDescriptor = { documents.deliver(it) },
            episodeResponse = { documents.intercept(it) },
            runtimeWarmResponse = { null }, runtimeWarmFinished = {}, runtimeWarmFailed = {},
            fail = ::fail,
            rendererGone = { view, request ->
                host.rendererGone(view)
                request?.let { fail(it, "NTK engine browser renderer stopped") }
            },
        ))
    }
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            try {
                when (message.what) {
                    NtkBrowserProtocol.MSG_RESOLVE -> resolve(message)
                    NtkBrowserProtocol.MSG_DESCRIPTOR -> documents.descriptor(message)
                    NtkBrowserProtocol.MSG_RETIRE_DOCUMENT -> retire(message)
                    else -> reject(message, "Unsupported engine browser operation")
                }
            } catch (failure: Exception) {
                reject(message, failure.message ?: "Engine browser operation failed")
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        destroyed = true
        val request = active
        active = null
        request?.replyError("NTK engine browser service stopped")
        try { request?.document?.close() } finally {
            host.retire()
            retiring?.recycle()
            retiring = null
            super.onDestroy()
        }
    }

    private fun resolve(message: Message) {
        check(!destroyed && active == null && retiring == null && pendingCallbacks == 0) {
            "NTK engine browser already owns an operation"
        }
        val request = RemoteRequest.from(message)
        active = request
        pendingCallbacks++
        val ready: () -> Unit = {
            try {
                if (active === request && !destroyed) initializeIdentity(request)
            } catch (failure: Exception) {
                fail(request, failure.message ?: "NTK engine browser initialization failed")
            } finally { callbackFinished() }
        }
        val failed: (Throwable) -> Unit = { failure ->
            try { fail(request, failure.message ?: "NTK browser startup failed") }
            finally { callbackFinished() }
        }
        val owner = application as? NtkWebViewStartupOwner
        if (owner == null) ready() else owner.ntkWebViewStartup.whenReady(ready, failed)
    }

    private fun initializeIdentity(request: RemoteRequest) {
        val identity = request.fingerprint?.let { NtkBrowserIdentity(it, requireNotNull(request.persistentId)) }
        val applier = cookies
        pendingCallbacks++
        applier.identity(request.origin, identity, { active === request && !destroyed }) { accepted ->
            try {
                if (active === request && !destroyed) {
                    if (accepted) {
                        request.identityCookiesApplied = true
                        sendDocumentRequestReady(request.requestId, request.primaryRecipient)
                    } else fail(request, "NTK identity cookies rejected")
                }
            } finally { callbackFinished() }
        }
    }

    private fun navigate(request: RemoteRequest) {
        if (active !== request || !request.identityCookiesApplied || !request.documentCookiesApplied ||
            request.documentNavigationStarted
        ) return
        val view = host.acquire(request.userAgent)
        request.documentNavigationStarted = true
        request.documentEpoch++
        request.captureInstalledAtDocumentStart = host.installAuthorization(view, request)
        request.browserTrace("browser-document-replay-start")
        view.loadUrl(request.key)
        request.browserTrace("browser-document-replay-load-issued")
    }

    private fun authorize(request: RemoteRequest) {
        if (active !== request || !request.browserDocumentStarted || request.authorizationStarted) return
        request.authorizationStarted = true
        if (!request.captureInstalledAtDocumentStart) host.startAuthorization(request)
    }

    private fun installManifest(request: RemoteRequest) {
        if (active !== request || !request.browserDocumentStarted || !request.descriptorDelivered ||
            request.manifestDescriptorInstalled
        ) return
        val descriptor = request.descriptor ?: return
        val view = host.current ?: return
        request.manifestDescriptorInstalled = true
        view.evaluateJavascript(NtkBrowserManifestKick.source(descriptor), null)
    }

    private fun accept(origin: String, path: String, payload: String, id: Long, epoch: Long) {
        val request = active ?: return
        if (request.requestId != id || request.documentEpoch != epoch) return
        if (runCatching { validatedKey(origin, path) }.getOrNull() != request.key) return
        request.delivery.accept(payload)?.let { request.replyPayload(it) }
    }

    private fun fail(request: RemoteRequest, detail: String) {
        if (active === request) request.replyError(detail)
        // The client always issues retirement, including on error and cancellation.
    }

    private fun retire(message: Message) {
        val id = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        require(id > 0 && (active?.requestId == id ||
            (active == null && (retiringId() == id || retiredId == id)))) {
            "Retirement does not own this engine browser document"
        }
        if (retiredId == id && active == null && retiring == null) {
            message.replyTo?.let { sendDocumentRetired(id, it) }
            return
        }
        val request = active
        active = null
        request?.document?.close()
        request?.document = null
        host.retire()
        retiring?.recycle()
        retiring = Message.obtain(message)
        finishRetirement()
    }

    private fun retiringId(): Long? = retiring?.data?.getLong(NtkBrowserProtocol.KEY_REQUEST_ID)

    private fun callbackFinished() {
        check(pendingCallbacks > 0)
        pendingCallbacks--
        finishRetirement()
    }

    private fun finishRetirement() {
        if (pendingCallbacks != 0) return
        val message = retiring ?: return
        retiring = null
        retiredId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID)
        message.replyTo?.let { sendDocumentRetired(retiredId, it) }
        message.recycle()
    }

    private fun reject(message: Message, detail: String) {
        message.replyTo?.let { sendError(message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID), it, detail) }
    }

    private companion object { const val PROFILE = "ntk_engine" }
}
