package ml.melun.mangaview.source.ntk

import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebChromeClient
import java.io.File
import java.net.URI
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ProfileStore

/** Runs NTK's official browser acknowledgement outside the reader process. */
open class NtkBrowserService : Service() {
    protected open val browserProfileName: String = "ntk_primary"
    private val handler = Handler(Looper.getMainLooper())
    private val profile by lazy(LazyThreadSafetyMode.NONE) {
        require(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            "NTK browser isolation requires WebView multi-profile support"
        }
        ProfileStore.getInstance().getOrCreateProfile(browserProfileName)
    }
    private val cookieApplier by lazy(LazyThreadSafetyMode.NONE) {
        NtkBrowserCookieApplier(handler, profile.cookieManager)
    }
    private val staticResources = NtkBrowserStaticResourceCache(
        NtkStaticResourceDiskStore(root = { File(filesDir, "ntk_static_assets_v1") }),
    )
    private val incoming = Messenger(IncomingHandler())
    @Volatile private var active: RemoteRequest? = null
    private val ackPhases = NtkAckPhaseRelay(
        profileName = { browserProfileName },
        currentRequest = { active },
        authorizationReady = ::installManifestDescriptor,
        cookieManager = { profile.cookieManager },
    )
    private val warmRuntime = NtkBrowserWarmRuntime(
        handler = handler,
        cookieApplier = cookieApplier,
        staticResources = staticResources,
        awaitStartup = { ready, failed -> afterWebViewStartup(ready, failed) },
        acquireBrowser = { browserHost.acquire(it) },
        currentRequest = { active },
        navigate = ::navigate,
        park = { browserHost.park() },
        preparationFailed = ::fail,
        originReady = { challengeController.beginWhileWarm(it) },
    )
    private var completedDelivery: CompletedDelivery? = null
    private val cancellation = NtkBrowserRequestCancellation(
        { active },
        { request, park -> if (park) quiesce(request) else abort(request) },
        { requestId -> completedDelivery = completedDelivery?.takeUnless { it.requestId == requestId } },
    )
    private val adjacentChallenges = NtkAdjacentChallengeController(
        handler,
        { browserProfileName },
        { active },
        { browserHost.current },
        ::parkCompletedBrowser,
    )
    private val challengeController: NtkEpisodeChallengeController by lazy(LazyThreadSafetyMode.NONE) {
        NtkEpisodeChallengeController(
            handler,
            { browserProfileName },
            { active },
            { browserHost.current },
            { origin -> warmRuntime.isOriginReady(origin) },
            { origin -> warmRuntime.isRunning(origin) },
            ::navigateDocument,
            adjacentChallenges,
        )
    }
    private val documentController: NtkBrowserDocumentController by lazy(LazyThreadSafetyMode.NONE) {
        NtkBrowserDocumentController(
            { active },
            { origin -> warmRuntime.isRunning(origin) },
            { origin, cookies, ready ->
                val request = active
                cookieApplier.cookies(origin, cookies, isCurrent = { active === request }, completed = ready)
            },
            { request -> browserHost.current?.let { challengeController.beginEpisodeNavigation(it, request) } },
            ::startAuthorization,
            ::installManifestDescriptor,
            ::fail,
        )
    }
    private val browserHost: NtkBrowserHost by lazy(LazyThreadSafetyMode.NONE) {
        NtkBrowserHost(
            this,
            { browserProfileName },
            staticResources,
            NtkBrowserHostCallbacks(
                currentRequest = { active },
                images = { origin, path, payload, requestId, epoch ->
                    handler.post { accept(origin, path, payload, requestId, epoch) }
                },
                phase = { origin, path, phase, status, requestId, epoch ->
                    handler.post { ackPhases.accept(origin, path, phase, status, requestId, epoch) }
                },
                warmPhase = { origin, generation, phase, status ->
                    handler.post { warmRuntime.phase(origin, generation, phase, status) }
                },
                preflightChallenge = { origin, path, requestId, status, payload ->
                    handler.post { challengeController.accept(origin, path, requestId, status, payload) }
                },
                startAuthorization = ::startAuthorization,
                deliverDescriptor = documentController::deliver,
                episodeResponse = documentController::intercept,
                runtimeWarmResponse = warmRuntime::intercept,
                runtimeWarmFinished = warmRuntime::pageFinished,
                runtimeWarmFailed = warmRuntime::failed,
                fail = ::fail,
                rendererGone = ::rendererGone,
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder
    override fun onDestroy() {
        active?.let {
            it.replyError("NTK browser service stopped")
            it.document?.close()
        }
        active = null
        completedDelivery = null
        adjacentChallenges.clear()
        staticResources.close()
        retireBrowser()
        super.onDestroy()
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
                    current.ackState,
                    current.authorizationStarted || current.authorizationObserved,
                )) {
                redriveIncompleteDelivery(current)
            }
            return
        }
        val requestedChallenge = current?.adjacentChallenges?.get(request.path)
        if (current != null && current.origin == request.origin && NtkAdjacentChallengeHandoffPolicy.shouldInherit(
                completedDelivery = current.delivery.completedPayload() != null,
                challengeStarted = requestedChallenge?.started == true,
                challengeResolved = requestedChallenge?.resolved == true,
                challengePath = request.path.takeIf { requestedChallenge != null },
                requestedPath = request.path,
            )
        ) {
            inheritAdjacentChallenge(current, request)
            return
        }
        current?.takeIf { browserSupersession(it.delivery.completedPayload()) == NtkBrowserSupersession.RETIRE_UNFINISHED_BROWSER }?.let {
            it.replyError("NTK browser request was superseded")
            abort(it)
        } ?: current?.let(::quiesce)
        start(request)
    }

    private fun startWhenWebViewReady(request: RemoteRequest, identity: NtkBrowserIdentity?) {
        afterWebViewStartup(
            ready = {
                cookieApplier.identity(request.origin, identity, { active === request }) { accepted ->
                    if (active === request) {
                        if (!accepted) {
                            fail(request, "NTK identity cookies rejected")
                            return@identity
                        }
                        request.identityCookiesApplied = true
                        if (warmRuntime.isOriginReady(request.origin)) {
                            request.advanceAckState(NtkAckPreparationState.ORIGIN_READY)
                        }
                        navigate(request)
                    }
                }
            },
            failed = { failure ->
                fail(request, "NTK browser startup failed: ${failure.message}")
            }
        )
    }

    private fun start(request: RemoteRequest) {
        adjacentChallenges.consume(request)
        active = request
        staticResources.prepare(request.origin, request.userAgent)
        if (warmRuntime.isPending(request.origin)) {
            return
        }
        val identity = request.fingerprint?.let { fingerprint ->
            NtkBrowserIdentity(fingerprint, requireNotNull(request.persistentId))
        }
        startWhenWebViewReady(request, identity)
    }

    private fun inheritAdjacentChallenge(current: RemoteRequest, request: RemoteRequest) {
        current.document?.close()
        request.identityCookiesApplied = current.identityCookiesApplied
        request.inheritedChallengeRequestIds += current.requestId
        request.inheritedChallengeRequestIds += current.inheritedChallengeRequestIds
        request.adjacentChallenges.putAll(current.adjacentChallenges)
        request.challengePreflightStarted = true
        active = request
        staticResources.prepare(request.origin, request.userAgent)
        Log.d(ACK_TAG, "profile=$browserProfileName phase=adjacent-challenge-handoff status=0 ageMs=${request.ageMillis()}")
        handler.postDelayed(
            {
                if (active === request && !request.challengePreflightResolved) {
                    challengeController.finishIfUnresolved(request)
                }
            },
            NTK_CHALLENGE_TIMEOUT_MILLIS,
        )
    }

    private fun redriveIncompleteDelivery(request: RemoteRequest) {
        if (active !== request) return
        request.deliveryRedrives += 1
        request.documentNavigationStarted = false
        retireBrowser()
        navigate(request)
    }

    private fun navigate(request: RemoteRequest) {
        if (active !== request) return
        runCatching {
            val adjacent = request.intent != ml.melun.mangaview.source.PreparationIntent.INITIAL_VIEW
            browserHost.acquire(request.userAgent).apply {
                applyRenderPolicy(
                    this,
                    if (adjacent) NtkBrowserRenderPhase.ADJACENT_AUTHORIZATION
                    else NtkBrowserRenderPhase.INITIAL_AUTHORIZATION,
                )
                resumeTimers()
                onResume()
                settings.userAgentString = request.userAgent
                // ACK keeps scripts/XHR but never duplicates viewer-owned image requests.
                settings.blockNetworkImage = true
                settings.loadsImagesAutomatically = false
                request.authorizationStarted = false
                request.authorizationObserved = false
                request.browserDocumentStarted = false
                request.descriptorDelivered = false
                request.manifestDescriptorInstalled = false
                if (warmRuntime.isOriginReady(request.origin)) {
                    request.advanceAckState(NtkAckPreparationState.ORIGIN_READY)
                }
                challengeController.beginEpisodeNavigation(this, request)
            }
        }.onFailure { failure ->
            fail(request, "NTK browser startup failed: ${failure.message}")
        }
    }

    private fun navigateDocument(view: WebView, request: RemoteRequest) {
        if (active !== request || request.documentNavigationStarted) return
        if (request.document == null || !request.documentCookiesApplied) return
        request.documentNavigationStarted = true
        request.documentEpoch += 1L
        request.browserTrace("browser-document-replay-start")
        Log.d(ACK_TAG, "phase=document-replay-start status=0 ageMs=${request.ageMillis()}")
        request.captureInstalledAtDocumentStart = browserHost.installAuthorization(view, request)
        view.loadUrl(request.key)
        request.browserTrace("browser-document-replay-load-issued")
    }

    private fun accept(origin: String, path: String, payload: String, requestId: Long, epoch: Long) {
        val request = active ?: return
        if (request.requestId != requestId || request.documentEpoch != epoch) return
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return
        if (request.key != key) return
        val accepted = request.delivery.accept(payload) ?: return
        completedDelivery = CompletedDelivery(request.requestId, request.key, accepted)
        request.replyPayload(accepted)
        // Manifest capture is the last operation that needs a painted provider document. Keep the
        // browser request alive until the direct image route proves usable, but stop raster work
        // immediately so it cannot contend with the reader's first or adjacent frames.
        if (!adjacentChallenges.begin(request)) parkCompletedBrowser(request)
    }

    private fun parkCompletedBrowser(request: RemoteRequest) {
        if (active !== request) return
        browserHost.current?.let { applyRenderPolicy(it, NtkBrowserRenderPhase.PARKED) }
    }

    private fun startAuthorization(request: RemoteRequest) {
        if (active !== request || !request.browserDocumentStarted) return
        if (request.authorizationStarted) return
        val requiresFallback = shouldEvaluateAuthorizationFallback(
            browserDocumentStarted = request.browserDocumentStarted,
            authorizationStarted = request.authorizationStarted,
            captureInstalledAtDocumentStart = request.captureInstalledAtDocumentStart,
        )
        request.authorizationStarted = true
        if (requiresFallback) browserHost.startAuthorization(request)
    }

    private fun installManifestDescriptor(request: RemoteRequest) {
        if (active !== request || !request.browserDocumentStarted ||
            request.manifestDescriptorInstalled
        ) return
        if (!request.descriptorDelivered) return
        val descriptor = request.descriptor ?: return
        val view = browserHost.current ?: return
        request.manifestDescriptorInstalled = true
        Log.d(ACK_TAG, "profile=$browserProfileName phase=native-manifest-install status=0 ageMs=${request.ageMillis()}")
        view.evaluateJavascript(NtkBrowserManifestKick.source(descriptor)) { result ->
            if (active === request) {
                Log.d(
                    ACK_TAG,
                    "phase=native-manifest-installed status=200 ageMs=${request.ageMillis()} result=$result",
                )
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
        request.advanceAckState(NtkAckPreparationState.PARKED)
        request.document?.close()
        active = null
        browserHost.park()
    }

    /**
     * Cancellation can happen while the provider's one-use canary is on the wire. Destroy the
     * old document before another episode is admitted so a late response cannot mutate the shared
     * WebView cookie jar underneath the next ACK. Normal completed episodes use [quiesce] and keep
     * the single browser resident.
     */
    private fun abort(request: RemoteRequest) {
        if (active !== request) return
        request.advanceAckState(NtkAckPreparationState.PARKED)
        request.document?.close()
        active = null
        retireBrowser()
    }

    private fun retireBrowser() {
        browserHost.retire()
        warmRuntime.browserRetired()
    }

    private fun rendererGone(view: WebView, request: RemoteRequest?) {
        browserHost.rendererGone(view)
        warmRuntime.browserRetired()
        if (request != null && request.rendererRestarts < MAX_RENDERER_RESTARTS) {
            request.rendererRestarts += 1
            request.documentNavigationStarted = false
            handler.post { if (active === request) navigate(request) }
            Log.w(TAG, "browser renderer restarted attempt=${request.rendererRestarts}")
        } else {
            active = null
            request?.document?.close()
            request?.replyError("NTK browser renderer stopped")
            if (request == null) {
                handler.post {
                    warmRuntime.restart()
                }
            }
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                NtkBrowserProtocol.MSG_RESOLVE -> resolve(message)
                NtkBrowserProtocol.MSG_WARM -> warmRuntime.handle(message)
                NtkBrowserProtocol.MSG_DESCRIPTOR -> documentController.descriptor(message)
                NtkBrowserProtocol.MSG_CANCEL -> cancellation.cancel(message, quiesce = false)
                NtkBrowserProtocol.MSG_QUIESCE -> cancellation.cancel(message, quiesce = true)
                NtkBrowserProtocol.MSG_PREFLIGHT_ADJACENT -> adjacentChallenges.preflight(message)
                else -> super.handleMessage(message)
            }
        }
    }
}

private fun Service.afterWebViewStartup(ready: () -> Unit, failed: (Throwable) -> Unit) {
    val owner = application as? NtkWebViewStartupOwner
    if (owner == null) ready() else owner.ntkWebViewStartup.whenReady(ready, failed)
}

internal fun applyRenderPolicy(view: WebView, phase: NtkBrowserRenderPhase) {
    val policy = phase.renderPolicy()
    view.visibility = if (policy.visible) View.VISIBLE else View.INVISIBLE
    view.setLayerType(
        if (policy.hardwareRaster) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
        null,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        view.setRendererPriorityPolicy(
            if (policy.boundRenderer) WebView.RENDERER_PRIORITY_BOUND
            else WebView.RENDERER_PRIORITY_WAIVED,
            !policy.boundRenderer,
        )
    }
}

private fun replyError(message: Message, detail: String) {
    val recipient = message.replyTo ?: return
    val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
    sendError(requestId, recipient, detail)
}

internal class NtkAckPhaseRelay(
    private val profileName: () -> String,
    private val currentRequest: () -> RemoteRequest?,
    private val authorizationReady: (RemoteRequest) -> Unit,
    private val cookieManager: () -> CookieManager,
) {
    fun accept(origin: String, path: String, phase: String, status: Int, requestId: Long, epoch: Long) {
        val request = currentRequest() ?: return
        if (request.requestId != requestId || request.documentEpoch != epoch ||
            !request.documentCookiesApplied || !request.identityCookiesApplied
        ) return
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return
        if (request.key != key) return
        traceAckPhase(request, phase, status)
        val safePhase = phase.take(MAX_PHASE_LENGTH).replace('\n', '_').replace('\r', '_')
        Log.d(
            ACK_TAG,
            "profile=${profileName()} path=${request.path} phase=$safePhase " +
                "status=$status ageMs=${request.ageMillis()}",
        )
        request.authorizationObserved = true
        if (phase == "canary-start" || phase == "ack-start") {
            val cookieNames = cookieManager().getCookie(origin)
                .orEmpty()
                .split(';')
                .map { it.substringBefore('=').trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
                .joinToString("|")
            Log.d(
                ACK_TAG,
                "profile=${profileName()} path=${request.path} phase=$phase-cookie-names " +
                    "names=$cookieNames ageMs=${request.ageMillis()}",
            )
        }
        if (status in 200..299 && phase.startsWith("challenge-meta:ok=true")) {
            request.advanceAckState(NtkAckPreparationState.CHALLENGE_READY)
        }
        if (isNtkAuthorizationProof(phase, status)) {
            request.advanceAckState(NtkAckPreparationState.ACK_READY)
            request.replyAckReady()
            authorizationReady(request)
        }
    }
}

internal fun layoutForProviderObservation(view: WebView) {
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
