package ml.melun.mangaview.ntkack

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun ntkAckServerTimeOffsetAtRegistrationMidpoint(
    serverNowEpochMs: Long,
    registrationStartedAtEpochMs: Long,
    registrationReceivedAtEpochMs: Long,
): Long {
    val receivedAt = registrationReceivedAtEpochMs.coerceAtLeast(registrationStartedAtEpochMs)
    val localMidpoint = registrationStartedAtEpochMs +
        (receivedAt - registrationStartedAtEpochMs) / 2L
    return serverNowEpochMs - localMidpoint
}

/** Sole owner of the remote ACK WebView and its one proof-critical flight. */
class NtkAckBrowserEngine(
    private val context: Context,
    private val serviceInstanceId: String,
    private val proofKey: KeyPair,
    private val requestKeyStore: NtkAckRequestKeyStore,
) {
    private enum class State { EMPTY, WARMING, READY, RUNNING, PROVED, QUIESCING }

    private class Flight(
        val request: NtkAckRequest,
        val startedAtNanos: Long,
        val callbacks: CopyOnWriteArrayList<(Result<NtkAckProof>) -> Unit>,
        val prerequisitesCallbacks: CopyOnWriteArrayList<() -> Unit>,
    ) {
        val identity = NtkAckFlightIdentity(
            request.protocolVersion,
            request.flightId,
            request.generation,
            request.authEpoch,
            request.origin,
            request.episodePath,
        )
        val tasks = NtkAckFlightTasks()
        @Volatile var generationToken = UUID.randomUUID().toString()
        @Volatile var transport: NtkAckTransport? = null
        @Volatile var recorder: NtkAckEvidenceRecorder? = null
        @Volatile var proof: NtkAckProof? = null
        @Volatile var seal: NtkAckQuiescenceSeal? = null
        @Volatile var challengeJson = ""
        @Volatile var challengeToken = ""
        @Volatile var guardVersion = ""
        @Volatile var guardJavascript = byteArrayOf()
        @Volatile var guardWasm = byteArrayOf()
        @Volatile var guardJsDigest = ""
        @Volatile var guardWasmDigest = ""
        @Volatile var canaryResult: NtkAckTransport.Result? = null
        val metricResponses = ConcurrentHashMap<String, NtkAckTransport.Result>()
        val prerequisitesStarted = AtomicBoolean(false)
        val prerequisitesReady = AtomicBoolean(false)
        val guardProgramReady = AtomicBoolean(false)
        val shellReady = AtomicBoolean(false)
        val guardStarted = AtomicBoolean(false)
        @Volatile var guardState = "created"
        @Volatile var ackPostStarted = false
        @Volatile var terminalDelivered = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workers: ExecutorService = Executors.newFixedThreadPool(6) { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                runnable.run()
            },
            "ntk-ack-service-worker",
        ).apply { isDaemon = true }
    }
    private var state = State.EMPTY
    private var webView: WebView? = null
    private var bridge: FlightBridge? = null
    private var warmRequest: NtkAckWarmRequest? = null
    private val warmCallbacks = ArrayList<(Result<Unit>) -> Unit>()
    @Volatile private var flight: Flight? = null
    private var strictOrigin = ""
    private var shellFlightId = ""
    private var quiesceCallback: ((Result<NtkAckQuiescenceSeal>) -> Unit)? = null
    private var quiesceRendererGone = false
    private var credentialIdentity = ""
    private val deadlineRunnable = Runnable {
        flight?.let {
            failFlight(
                it,
                NtkAckProtocol.FAILURE_DEADLINE,
                "deadline",
                IllegalStateException("ACK hard deadline state=${it.guardState}"),
            )
        }
    }

    val webViewCreatedPid: Int get() = if (webView == null) 0 else Process.myPid()
    val hasLiveWebView: Boolean get() = webView != null

    fun warm(request: NtkAckWarmRequest, callback: (Result<Unit>) -> Unit) {
        assertMainLooper()
        validateWarmRequest(request)
        if (state == State.READY && warmRequest == request && webView != null) {
            callback(Result.success(Unit))
            return
        }
        warmCallbacks += callback
        if (state == State.WARMING) return
        if (state in setOf(State.RUNNING, State.PROVED, State.QUIESCING)) {
            completeWarm(Result.failure(IllegalStateException("ACK engine is not warmable: $state")))
            return
        }
        destroyWebViewOnly()
        state = State.WARMING
        warmRequest = request
        runCatching { createWebView(request) }
            .onFailure { completeWarm(Result.failure(it)) }
    }

    fun startAck(
        request: NtkAckRequest,
        onNetworkPrerequisitesReady: () -> Unit = {},
        callback: (Result<NtkAckProof>) -> Unit,
    ) {
        assertMainLooper()
        validateAckRequest(request)
        val current = flight
        if (current != null && current.request.singleFlightKey == request.singleFlightKey) {
            if (current.prerequisitesReady.get()) {
                onNetworkPrerequisitesReady()
            } else {
                current.prerequisitesCallbacks.add(onNetworkPrerequisitesReady)
            }
            current.proof?.let { callback(Result.success(it)) } ?: current.callbacks.add(callback)
            return
        }
        if (current != null) {
            require(request.generation > current.request.generation) { "Older ACK generation cannot supersede owner" }
            cancelFlight(current, NtkAckProtocol.FAILURE_SUPERSEDED, "superseded")
        }
        val created = Flight(
            request,
            SystemClock.elapsedRealtimeNanos(),
            CopyOnWriteArrayList(listOf(callback)),
            CopyOnWriteArrayList(listOf(onNetworkPrerequisitesReady)),
        )
        flight = created
        val identity = "${request.origin}|${NtkAckProofCodec.sha256Utf8(request.userAgent)}|${request.authEpoch}"
        if (credentialIdentity.isNotEmpty() && credentialIdentity != identity) requestKeyStore.clear()
        credentialIdentity = identity
        requestKeyStore.beginFlight(created.identity)
        // Challenge/key/guard work starts only after the committed click.
        startNetworkPrerequisites(created)
        // This route is selected only for the manhwa/full-challenge wire contract. Commit the real
        // flight shell as the WebView's first document so cold Chromium initialization is not
        // followed by a redundant inert-document commit. No target image/page request is made;
        // server authority is still withheld until the fresh challenge transcript is complete.
        ensureFullChallengeBrowser(created)
    }

    fun cancel(identity: NtkAckFlightIdentity, reasonCode: Int) {
        assertMainLooper()
        flight?.takeIf { it.identity.singleFlightKey == identity.singleFlightKey && it.identity.flightId == identity.flightId }
            ?.let { cancelFlight(it, reasonCode, "client_cancel") }
    }

    fun quiesce(identity: NtkAckFlightIdentity, callback: (Result<NtkAckQuiescenceSeal>) -> Unit) {
        assertMainLooper()
        val current = flight
        if (current == null || current.identity != identity) {
            callback(Result.failure(IllegalArgumentException("ACK quiescence identity mismatch")))
            return
        }
        current.seal?.let { callback(Result.success(it)); return }
        val proof = current.proof
        if (proof == null || state != State.PROVED) {
            callback(Result.failure(IllegalStateException("ACK proof is not ready")))
            return
        }
        check(quiesceCallback == null) { "ACK quiescence already running" }
        state = State.QUIESCING
        quiesceCallback = callback
        current.generationToken = UUID.randomUUID().toString()
        mainHandler.removeCallbacks(deadlineRunnable)
        current.transport?.quiesceProofCalls()
        current.transport?.whenIdle { mainHandler.post { terminateRendererAndDestroy(current, proof) } }
            ?: terminateRendererAndDestroy(current, proof)
    }

    fun signExact(request: NtkAckSignRequest): NtkAckSignature {
        assertMainLooper()
        val current = checkNotNull(flight) { "No ACK flight" }
        check(current.seal != null && state == State.EMPTY) { "ACK browser is not quiesced" }
        return requestKeyStore.signExact(request)
    }

    /**
     * Consumes the exact-sign capability and executes the one image-list request on the same
     * process-local OkHttp pool that just completed ACK. This avoids throwing away the fresh
     * DNS/TLS/H2 state only to recreate the control-plane request in the UI process.
     */
    fun executeExact(
        request: NtkAckSignRequest,
        callback: (Result<NtkAckExactExchange>) -> Unit,
    ) {
        assertMainLooper()
        val current = checkNotNull(flight) { "No ACK flight" }
        val signature = signExact(request)
        val transport = checkNotNull(current.transport) { "ACK transport is unavailable" }
        current.tasks.track(
            workers.submit {
                val result = runCatching {
                    val response = transport.postExact(request, signature)
                    NtkAckExactExchange(
                        protocolVersion = NtkAckProtocol.VERSION,
                        signature = signature,
                        requestUrl = response.requestUrl,
                        finalUrl = response.finalUrl,
                        status = response.status,
                        bodyBytes = response.body,
                        responseHeaders = response.headers.entries
                            .sortedBy { it.key.lowercase() }
                            .map { (name, values) -> NtkAckHeader(name, values) },
                        completedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    ).also {
                        Log.d(
                            TAG,
                            "ack_exact_image_api_done path=${current.request.episodePath}," +
                                "code=${it.status},bytes=${it.bodyBytes.size}," +
                                "error=${if (it.status >= 400) it.bodyBytes.toString(Charsets.UTF_8).take(160).replace(',', ';') else ""}," +
                                "elapsedMs=${(it.completedAtElapsedNanos - current.startedAtNanos) / 1_000_000L}",
                        )
                    }
                }
                callback(result)
            },
        )
    }

    fun clearStrictState(request: NtkAckClearRequest, callback: (Result<Unit>) -> Unit) {
        assertMainLooper()
        val originToClear = request.identity?.origin ?: flight?.request?.origin ?: strictOrigin
        flight?.let { cancelFlight(it, NtkAckProtocol.FAILURE_CANCELLED, "strict_clear") }
        if (request.clearCloudflareClearance) {
            requestKeyStore.clear()
            credentialIdentity = ""
        }
        clearWebViewCookies(originToClear, request.clearCloudflareClearance) {
            webView?.evaluateJavascript(
                "try{sessionStorage.clear();['ntk-ack-proof','ntk-ack-flight'].forEach(function(k){localStorage.removeItem(k)});true}catch(e){false}",
            ) { callback(Result.success(Unit)) } ?: callback(Result.success(Unit))
        }
    }

    fun destroyImmediately() {
        assertMainLooper()
        flight?.let { cancelFlight(it, NtkAckProtocol.FAILURE_CANCELLED, "service_destroy") }
        destroyWebViewOnly()
        workers.shutdownNow()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(request: NtkAckWarmRequest) {
        val created = WebView(context)
        check(created.parent == null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            created.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        configureSettings(created.settings, request.userAgent)
        installWebViewClients(created)
        val inertBridge = FlightBridge()
        bridge = inertBridge
        created.addJavascriptInterface(inertBridge, BRIDGE_NAME)
        measure(created, request.viewport)
        webView = created
        created.loadDataWithBaseURL(INERT_ORIGIN, INERT_HTML, "text/html", "UTF-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createFlightWebView(current: Flight) {
        val request = current.request.toWarmRequest()
        val created = WebView(context)
        check(created.parent == null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            created.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        configureSettings(created.settings, request.userAgent)
        installWebViewClients(created)
        measure(created, request.viewport)
        webView = created
        prepareFlightShell(current)
    }

    private fun installWebViewClients(created: WebView) {
        created.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) = pageReady(created)
            override fun onPageFinished(view: WebView?, url: String?) = pageReady(created)

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val expected = state == State.QUIESCING
                if (expected) {
                    quiesceRendererGone = true
                    flight?.let { current -> current.proof?.let { finishWebViewDestroyAndSeal(current, it, true, false) } }
                } else {
                    flight?.let {
                        failFlight(it, NtkAckProtocol.FAILURE_INTERNAL, "renderer_gone", IllegalStateException("Unexpected ACK renderer loss"))
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (url.isEmpty() || request?.method != "GET") return blockedWebResponse()
                flight?.metricResponses?.get(url)?.let { cached ->
                    if (cached.status == 200) return cachedMetricResponse(cached)
                }
                return if (url.startsWith("http://") || url.startsWith("https://")) blockedWebResponse() else null
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            created.webViewRenderProcessClient = object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
            }
        }
    }

    private fun pageReady(created: WebView) {
        if (webView !== created) return
        if (state == State.WARMING) {
            state = State.READY
            completeWarm(Result.success(Unit))
            return
        }
        val current = flight
        if (state == State.RUNNING && current != null) {
            markFlightShellReady(current.request.flightId, "page_callback")
        }
    }

    /**
     * The shell's final inline script is the earliest truthful readiness boundary: the document,
     * its DOM sentinels, and the per-flight JavaScript bridge all exist at that point. Waiting for
     * Chromium's compositor-facing page callbacks adds a cold renderer commit to a protocol that
     * does not display this private WebView. Those callbacks remain as a conservative fallback.
     */
    private fun markFlightShellReady(flightId: String, source: String) {
        assertMainLooper()
        val current = flight
        if (state != State.RUNNING || current == null ||
            current.request.flightId != flightId || shellFlightId != flightId
        ) return
        shellFlightId = ""
        if (current.shellReady.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "ack_shell_ready path=${current.request.episodePath},source=$source," +
                    "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
            )
        }
        maybeRunGuardInWebView(current)
    }

    private fun startNetworkPrerequisites(current: Flight) {
        if (!current.prerequisitesStarted.compareAndSet(false, true)) return
        Log.d(
            TAG,
            "ack_network_prerequisites_start path=${current.request.episodePath}," +
                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
        )
        current.tasks.track(workers.submit { runNetworkPrerequisites(current) })
    }

    private fun prepareFlightShell(current: Flight) {
        assertMainLooper()
        if (flight !== current || current.tasks.isCancelled()) return
        current.proof?.let { deliverProof(current, it); return }
        val view = webView ?: return failFlight(current, NtkAckProtocol.FAILURE_INTERNAL, "shell", IllegalStateException("No ACK WebView"))
        state = State.RUNNING
        strictOrigin = current.request.origin
        mainHandler.removeCallbacks(deadlineRunnable)
        val delayMs = ((current.request.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L)
        if (delayMs <= 0L) return failFlight(current, NtkAckProtocol.FAILURE_DEADLINE, "shell", IllegalStateException("Expired deadline"))
        mainHandler.postDelayed(deadlineRunnable, delayMs)
        configureSettings(view.settings, current.request.userAgent)
        measure(view, current.request.viewport)
        view.stopLoading()
        runCatching { view.removeJavascriptInterface(BRIDGE_NAME) }
        val flightBridge = FlightBridge()
        bridge = flightBridge
        view.addJavascriptInterface(flightBridge, BRIDGE_NAME)
        view.onResume()
        view.resumeTimers()
        // Keep the platform cookie jar synchronized, but do not serialize the first document on
        // its asynchronous callbacks. The same validated, non-HttpOnly seed set is installed by
        // the origin-bound shell before it signals readiness, which is the exact visibility point
        // the guard needs. This overlaps CookieManager I/O with the cold Chromium document commit.
        expireAndSeedWebViewCookies(current, 0) {}
        if (flight !== current || current.tasks.isCancelled()) return
        shellFlightId = current.request.flightId
        view.loadDataWithBaseURL(
            current.request.origin + current.request.episodePath,
            flightShellHtml(
                current.request.viewport,
                current.request.episodePath,
                current.request.flightId,
                current.request.seedCookies,
            ),
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun runNetworkPrerequisites(current: Flight) {
        try {
            checkActive(current)
            val request = current.request
            val seeds = NtkAckCookieBoundary.validateSeeds(request.origin, request.episodePath, request.seedCookies)
            val transport = NtkAckTransport(
                context,
                request.origin,
                request.episodePath,
                request.userAgent,
                seeds,
                request.deadlineElapsedRealtimeNanos,
            )
            current.transport = transport
            try {
                checkActive(current)
            } catch (error: Throwable) {
                transport.cancelAll()
                throw error
            }
            val keyFuture: Future<String> = current.tasks.track(
                workers.submit<String> {
                    checkActive(current)
                    ensureRequestKey(current, transport)
                },
            )
            val challengeBody = JSONObject().put("path", request.episodePath).toString().toByteArray()
            val challengeFuture: Future<NtkAckTransport.Result> =
                current.tasks.track(
                    workers.submit<NtkAckTransport.Result> {
                        checkActive(current)
                        transport.postChallenge(challengeBody)
                    },
                )

            // The bundled guard is executable application code. Read/decrypt it while request-key
            // registration and the fresh challenge are in flight, then let the click-owned shell
            // instantiate the WASM module. This grants no episode/image authority: execution waits
            // inside the shell for the fresh metric/canary transcript below.
            val executableGuard = loadBundledGuardPair()
            current.guardJavascript = executableGuard.javascript
            current.guardWasm = executableGuard.wasm
            current.guardJsDigest = NtkAckProofCodec.sha256Hex(executableGuard.javascript)
            current.guardWasmDigest = NtkAckProofCodec.sha256Hex(executableGuard.wasm)
            current.guardProgramReady.set(true)
            Log.d(
                TAG,
                "ack_guard_bundled_code_ready path=${current.request.episodePath}," +
                    "jsBytes=${executableGuard.javascript.size}," +
                    "wasmBytes=${executableGuard.wasm.size}," +
                    "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
            )
            mainHandler.post { maybeRunGuardInWebView(current) }

            val keyId = runCatching { keyFuture.get() }.getOrElse {
                throw IllegalStateException("request_key: ${rootCause(it).message}", rootCause(it))
            }
            val challenge = runCatching { challengeFuture.get() }.getOrElse {
                throw IllegalStateException("challenge_transport: ${rootCause(it).message}", rootCause(it))
            }
            require(challenge.status == 200) { "ACK challenge HTTP ${challenge.status}" }
            val challengeRoot = JSONObject(challenge.bodyText)
            require(challengeRoot.optBoolean("ok", false)) { "ACK challenge body rejected" }
            val challengeObject = challengeRoot.optJSONObject("challenge")
            if (challengeObject == null) {
                completeTrustedServerGrant(
                    current,
                    transport,
                    keyId,
                    challengeBody,
                    challenge,
                )
                return
            }
            val token = challengeObject.optString("token", "")
            require(token.isNotBlank())
            require(challengeObject.optString("scope", request.episodePath) == request.episodePath)
            mainHandler.post { ensureFullChallengeBrowser(current) }
            val urlsJson = challengeObject.optJSONArray("impressionUrls") ?: JSONArray()
            val urls = (0 until urlsJson.length()).map { urlsJson.getString(it) }
            require(urls.isNotEmpty())
            transport.authorizeMetricUrls(urls)
            val urlDigests = urls.map { NtkAckProofCodec.sha256Utf8(resolve(request.origin, it)) }.toSet()
            val version = readGuardVersion()
            val recorder = NtkAckEvidenceRecorder(request.episodePath, keyId, canaryRequired = true)
            current.recorder = recorder
            recorder.recordChallenge(
                request.episodePath,
                keyId,
                NtkAckProofCodec.sha256Hex(challengeBody),
                NtkAckProofCodec.sha256Hex(challenge.body),
                challenge.status,
                NtkAckProofCodec.sha256Utf8(token),
                version,
                urlDigests,
            )
            current.challengeJson = challengeObject.toString()
            current.challengeToken = token
            current.guardVersion = version

            // Canary validation depends only on the authenticated challenge token, not on the
            // local program read or metric observations. Keep the exact same single canary and
            // overlap it with the bounded impression wave.
            val canaryBody = JSONObject()
                .put("adGuardLoaded", true)
                .put("adAckCanary", true)
                .put("challengeToken", token)
                .put("token", token)
                .put("path", request.episodePath)
                .toString().toByteArray()
            val canaryFuture: Future<NtkAckTransport.Result> = current.tasks.track(
                workers.submit<NtkAckTransport.Result> {
                    checkActive(current)
                    transport.postCanary(canaryBody)
                },
            )
            val metricFutures: List<Future<Pair<String, NtkAckTransport.Result>>> = urls.map { url ->
                current.tasks.track(
                    workers.submit<Pair<String, NtkAckTransport.Result>> {
                        checkActive(current)
                        url to transport.getMetric(url)
                    },
                )
            }
            metricFutures.forEach { future ->
                val (url, result) = future.get()
                current.metricResponses[resolve(request.origin, url)] = result
                recorder.recordMetric(
                    NtkAckProofCodec.sha256Utf8(token),
                    NtkAckProofCodec.sha256Utf8(resolve(request.origin, url)),
                    NtkAckProofCodec.sha256Hex(result.body),
                    result.status,
                )
            }
            checkActive(current)
            val canary = runCatching { canaryFuture.get() }.getOrElse {
                throw IllegalStateException("canary_transport: ${rootCause(it).message}", rootCause(it))
            }
            require(canary.status == 200 && JSONObject(canary.bodyText).optBoolean("ok", false)) {
                "ACK canary rejected"
            }
            current.canaryResult = canary
            recorder.recordCanary(
                request.episodePath,
                NtkAckProofCodec.sha256Utf8(token),
                NtkAckProofCodec.sha256Hex(canaryBody),
                NtkAckProofCodec.sha256Hex(canary.body),
                canary.status,
            )
            markNetworkPrerequisitesReady(current)
        } catch (error: Throwable) {
            mainHandler.post { failFlight(current, failureCode(current, error), "prerequisites", error) }
        }
    }

    private fun loadBundledGuardPair(): NtkAckGuardCodec.ExecutablePair {
        val javascript = context.assets.open(BUNDLED_GUARD_JAVASCRIPT).use { it.readBytes() }
        val wasm = context.assets.open(BUNDLED_GUARD_WASM).use { it.readBytes() }
        return NtkAckGuardCodec.decode(javascript, wasm)
    }

    /**
     * The challenge endpoint has an explicit server-authoritative short-lived grant branch. It is
     * not a local cache hit: both the challenge response and a fresh exact ACK confirmation must
     * independently carry the strict nginx markers and a response-local signed ad_ack grant. The
     * challenge grant remains the viewer authority; the confirmation grant proves only the exact
     * confirmation transcript and must not replace the cookie used by the viewer API.
     */
    private fun completeTrustedServerGrant(
        current: Flight,
        transport: NtkAckTransport,
        keyId: String,
        challengeBody: ByteArray,
        challengeResponse: NtkAckTransport.Result,
    ) {
        checkActive(current)
        val challengeObservedAt = System.currentTimeMillis() + requestKeyStore.serverTimeOffsetMs
        val challengeEvidence = NtkAckTrustedGrantValidator.validateChallenge(
            current.request.origin,
            current.request.episodePath,
            challengeObservedAt,
            NtkAckTrustedGrantValidator.ResponseEvidence(
                challengeResponse.status,
                challengeResponse.body,
                challengeResponse.headers,
                challengeResponse.responseGrantCookies,
            ),
        )
        val authoritativeViewerGrant = challengeResponse.responseGrantCookies
            .single { it.name == "ad_ack" }
            .copy()
        Log.d(
            TAG,
            "ack_trusted_challenge_valid path=${current.request.episodePath}," +
                "headerDigest=${challengeEvidence.responseHeaderDigestSha256}," +
                "expiresInMs=${challengeEvidence.expiresAtEpochMs - challengeObservedAt}",
        )

        val confirmationBody = JSONObject()
            .put("path", current.request.episodePath)
            .put("requestKeyId", keyId)
            .toString()
            .toByteArray()
        synchronized(current) {
            check(!current.ackPostStarted) { "ACK POST duplicate" }
            current.ackPostStarted = true
        }
        checkActive(current)
        val signed = requestKeyStore.signAckRequest(current.identity, confirmationBody)
        val confirmationResponse = transport.postAck(confirmationBody, signed.headers())
        val confirmationObservedAt = System.currentTimeMillis() + requestKeyStore.serverTimeOffsetMs
        val confirmationEvidence = NtkAckTrustedGrantValidator.validateAckConfirmation(
            current.request.origin,
            current.request.episodePath,
            keyId,
            confirmationObservedAt,
            NtkAckTrustedGrantValidator.AckConfirmRequest(
                "POST",
                "/api/ad/ack",
                confirmationBody,
            ),
            NtkAckTrustedGrantValidator.ResponseEvidence(
                confirmationResponse.status,
                confirmationResponse.body,
                confirmationResponse.headers,
                confirmationResponse.responseGrantCookies,
            ),
        )
        checkActive(current)
        val cumulativeResponseGrants = transport.cookieGrants()
            .map { grant ->
                if (grant.name == "ad_ack") authoritativeViewerGrant.copy() else grant
            }
        check(cumulativeResponseGrants.count { it.name == "ad_ack" } == 1) {
            "Trusted challenge viewer grant was not retained"
        }
        val unsignedProof = NtkAckTrustedGrantProofFactory.createUnsigned(
            request = current.request,
            serviceInstanceId = serviceInstanceId,
            packageName = context.packageName,
            signingCertificateDigestSha256 = signingCertificateDigest(),
            requestKeyId = keyId,
            challengeRequestBody = challengeBody,
            challenge = challengeEvidence,
            confirmation = confirmationEvidence,
            // The transport keeps one latest response-backed grant per cookie name, so the
            // confirmation response has replaced ad_ack by this point. Restore the validated
            // challenge grant while retaining every cumulative response-backed control cookie.
            cumulativeResponseGrants = cumulativeResponseGrants,
            observedAtEpochMs = challengeObservedAt,
            startedAtElapsedNanos = current.startedAtNanos,
            completedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        )
        val proof = NtkAckProofCodec.signProof(unsignedProof, proofKey)
        current.proof = proof
        requestKeyStore.authorizeExactCapability(proof)
        current.guardJavascript = byteArrayOf()
        current.guardWasm = byteArrayOf()
        current.challengeJson = ""
        Log.d(
            TAG,
            "ack_trusted_server_grant_ready path=${current.request.episodePath}," +
                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
        )
        // This branch has completed both authenticated server exchanges and has no browser guard
        // phase. Downstream content traffic may leave the ACK-priority gate before proof delivery.
        markNetworkPrerequisitesReady(current)
        mainHandler.post { deliverProof(current, proof) }
    }

    private fun markNetworkPrerequisitesReady(current: Flight) {
        if (!current.prerequisitesReady.compareAndSet(false, true)) return
        Log.d(
            TAG,
            "ack_network_prerequisites_ready path=${current.request.episodePath}," +
                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
        )
        mainHandler.post {
            if (flight !== current || current.tasks.isCancelled()) return@post
            val callbacks = current.prerequisitesCallbacks.toList()
            current.prerequisitesCallbacks.clear()
            callbacks.forEach { ready ->
                runCatching(ready).onFailure {
                    Log.d(TAG, "ack_network_prerequisites_callback_failed", it)
                }
            }
            maybeRunGuardInWebView(current)
        }
    }

    private fun ensureRequestKey(current: Flight, transport: NtkAckTransport): String {
        checkActive(current)
        if (requestKeyStore.isRegistered()) return requestKeyStore.requestKeyId
        val seeds = current.request.seedCookies.associate { it.name to it.value }
        val body = JSONObject().put("publicKey", JSONObject(requestKeyStore.publicJwk()))
        seeds["ntk_fp"]?.let {
            body.put("fp", it).put("ntkFp", it).put("fingerprint", it)
        }
        seeds["ntk_pid"]?.let { body.put("pid", it).put("ntkPid", it) }
        seeds["__vsid"]?.let { body.put("vsid", it) }
        seeds["__ntk_ev_id"]?.let { body.put("eventId", it) }
        checkActive(current)
        val registrationStartedAt = System.currentTimeMillis()
        val response = transport.registerKey(body.toString().toByteArray())
        val registrationReceivedAt = System.currentTimeMillis()
        require(response.status == 200) {
            "Request-key registration HTTP ${response.status}: ${response.bodyText.take(160)}"
        }
        val json = JSONObject(response.bodyText)
        val keyId = json.optString("keyId", "")
        require(json.optBoolean("ok", false) && keyId.isNotBlank()) {
            "Request-key registration rejected: ${response.bodyText.take(160)}"
        }
        val registrationRoundTripMs =
            (registrationReceivedAt - registrationStartedAt).coerceAtLeast(0L)
        val localMidpoint = registrationStartedAt + registrationRoundTripMs / 2L
        val serverNow = json.optLong("serverNow", localMidpoint)
        val serverTimeOffsetMs = ntkAckServerTimeOffsetAtRegistrationMidpoint(
            serverNow,
            registrationStartedAt,
            registrationReceivedAt,
        )
        checkActive(current)
        requestKeyStore.bindRegisteredKey(
            current.identity,
            keyId,
            serverTimeOffsetMs,
            json.optLong("expiresAt", serverNow + 3_600_000L),
        )
        Log.d(
            TAG,
            "ack_request_key_clock_bound rttMs=$registrationRoundTripMs," +
                "serverTimeOffsetMs=$serverTimeOffsetMs",
        )
        return keyId
    }

    private fun maybeRunGuardInWebView(current: Flight) {
        assertMainLooper()
        if (flight !== current || current.tasks.isCancelled() || state != State.RUNNING ||
            !current.shellReady.get() || !current.guardProgramReady.get() ||
            !current.guardStarted.compareAndSet(false, true)
        ) return
        val view = webView ?: return failFlight(current, NtkAckProtocol.FAILURE_INTERNAL, "guard", IllegalStateException("No ACK WebView"))
        Log.d(
            TAG,
            "ack_guard_execute path=${current.request.episodePath}," +
                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - current.startedAtNanos) / 1_000_000L}",
        )
        view.evaluateJavascript(GUARD_EXECUTION_SCRIPT, null)
    }

    private fun ensureFullChallengeBrowser(current: Flight) {
        assertMainLooper()
        if (flight !== current || current.tasks.isCancelled() || current.proof != null) return
        when {
            state == State.READY && webView != null -> prepareFlightShell(current)
            state == State.EMPTY -> runCatching { createFlightWebView(current) }
                .onFailure {
                    failFlight(current, NtkAckProtocol.FAILURE_INTERNAL, "full_challenge_create", it)
                }
            state == State.WARMING -> Unit
            state == State.RUNNING -> maybeRunGuardInWebView(current)
            else -> failFlight(
                current,
                NtkAckProtocol.FAILURE_INTERNAL,
                "full_challenge_state",
                IllegalStateException("Full challenge browser state=$state"),
            )
        }
    }

    private inner class FlightBridge {
        @JavascriptInterface
        fun onShellReady(flightId: String) {
            // JavascriptInterface methods run on WebView's bridge thread. Preserve all flight
            // state transitions on the main looper and bind the callback to this random flight.
            mainHandler.post { markFlightShellReady(flightId, "dom_bridge") }
        }

        @JavascriptInterface
        fun generationToken(): String = flight?.generationToken.orEmpty()

        @JavascriptInterface
        fun challengeJson(token: String): String = withFlight(token) { it.challengeJson }.orEmpty()

        @JavascriptInterface
        fun guardJavascriptBase64(token: String): String = withFlight(token) {
            Base64.getEncoder().encodeToString(it.guardJavascript)
        }.orEmpty()

        @JavascriptInterface
        fun guardWasmBase64(token: String): String = withFlight(token) {
            Base64.getEncoder().encodeToString(it.guardWasm)
        }.orEmpty()

        @JavascriptInterface
        fun requestKeyId(token: String): String = withFlight(token) { requestKeyStore.requestKeyId }.orEmpty()

        @JavascriptInterface
        fun prerequisitesReady(token: String): Boolean = withFlight(token) {
            it.prerequisitesReady.get()
        } ?: false

        @JavascriptInterface
        fun onGuardState(token: String, value: String) {
            withFlight(token) {
                it.guardState = value.take(160)
                Log.d(
                    TAG,
                    "ack_guard_state path=${it.request.episodePath},state=${it.guardState}," +
                        "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - it.startedAtNanos) / 1_000_000L}",
                )
            }
        }

        @JavascriptInterface
        fun request(token: String, url: String, method: String, headersJson: String, bodyBase64: String): String {
            val current = withFlight(token) { it } ?: return bridgeError("stale generation")
            return try {
                val uri = URI(url)
                current.guardState = "request:${uri.path}"
                when {
                    method.equals("POST", true) && uri.path == "/api/ad/canary" ->
                        bridgeResult(checkNotNull(current.canaryResult))
                    method.equals("POST", true) && uri.path == "/api/ad/ack" -> {
                        val body = Base64.getDecoder().decode(bodyBase64)
                        if (body.isEmpty()) {
                            current.guardState = "ack-empty-ignored"
                            bridgeNoContent()
                        } else {
                            bridgeResult(performAckFromGuard(current, body))
                        }
                    }
                    else -> bridgeError("forbidden guard request")
                }
            } catch (error: Throwable) {
                mainHandler.post { failFlight(current, failureCode(current, error), "guard_request", error) }
                bridgeError(error.javaClass.simpleName + ":" + error.message.orEmpty())
            }
        }

        @JavascriptInterface
        fun onGuardFinished(token: String, success: Boolean, error: String) {
            val current = withFlight(token) { it } ?: return
            if (!success && current.proof == null) {
                mainHandler.post {
                    failFlight(current, NtkAckProtocol.FAILURE_PROOF_REJECTED, "guard_finished", IllegalStateException(error))
                }
            }
        }
    }

    private fun performAckFromGuard(current: Flight, rawBody: ByteArray): NtkAckTransport.Result {
        synchronized(current) {
            check(!current.ackPostStarted) { "ACK POST duplicate" }
            current.ackPostStarted = true
        }
        val recorder = checkNotNull(current.recorder)
        val transport = checkNotNull(current.transport)
        val request = JSONObject(rawBody.toString(Charsets.UTF_8))
        require(request.optString("challengeToken", current.challengeToken) == current.challengeToken)
        request.put("challengeToken", current.challengeToken)
        request.put("path", current.request.episodePath)
        request.put("requestKeyId", requestKeyStore.requestKeyId)
        val tp = request.optString("tp", "")
        require(tp.isNotBlank() && tp != "true" && tp != "false") { "Guard omitted tp" }
        val observationUrls = JSONArray()
        val challenge = JSONObject(current.challengeJson).optJSONArray("impressionUrls") ?: JSONArray()
        for (index in 0 until challenge.length()) observationUrls.put(challenge.getString(index))
        request.put("observationUrls", observationUrls)
        recorder.recordGuardProof(
            NtkAckProofCodec.sha256Utf8(current.challengeToken),
            current.guardVersion,
            current.guardJsDigest,
            current.guardWasmDigest,
            tp,
        )
        val body = request.toString().toByteArray()
        val signed = requestKeyStore.signAckRequest(current.identity, body)
        val response = transport.postAck(body, signed.headers())
        val json = JSONObject(response.bodyText)
        val outcome = when {
            json.optBoolean("ok", false) -> "ok"
            json.optBoolean("acked", false) -> "acked"
            json.optString("status") in setOf("ok", "acked") -> json.optString("status")
            else -> "rejected"
        }
        val grants = NtkAckCookieBoundary.validateGrants(
            current.request.origin,
            current.request.episodePath,
            transport.cookieGrants(),
        )
        val grantDigest = NtkAckProofCodec.cookieGrantDigest(grants)
        recorder.recordAck(
            current.request.episodePath,
            NtkAckProofCodec.sha256Utf8(current.challengeToken),
            requestKeyStore.requestKeyId,
            NtkAckProofCodec.sha256Hex(body),
            NtkAckProofCodec.sha256Hex(response.body),
            response.status,
            outcome,
            grantDigest,
        )
        val evidence = recorder.evidenceOrThrow()
        val proof = NtkAckProofCodec.signProof(
            NtkAckProof(
                protocolVersion = NtkAckProtocol.VERSION,
                proofId = "",
                serviceInstanceId = serviceInstanceId,
                flightId = current.request.flightId,
                generation = current.request.generation,
                authEpoch = current.request.authEpoch,
                requestNonce = current.request.requestNonce,
                packageName = context.packageName,
                appSigningCertificateDigestSha256 = signingCertificateDigest(),
                origin = current.request.origin,
                episodePath = current.request.episodePath,
                userAgentDigestSha256 = NtkAckProofCodec.sha256Utf8(current.request.userAgent),
                viewportDigestSha256 = NtkAckProofCodec.viewportDigest(current.request.viewport),
                proofMode = NtkAckProtocol.PROOF_MODE_FULL_CHALLENGE,
                challengeRequestDigestSha256 = evidence.challengeRequestDigestSha256,
                challengeResponseDigestSha256 = evidence.challengeResponseDigestSha256,
                challengeStatus = 200,
                trustedScopeDigestSha256 = "",
                trustedObservedAtEpochMs = 0L,
                trustedExpiresAtEpochMs = 0L,
                trustedIntervalMs = 0L,
                trustedSuccessCount = 0,
                trustedSubjectKind = "",
                trustedChallengeHeaderDigestSha256 = "",
                trustedChallengeCookiePayloadDigestSha256 = "",
                trustedAckHeaderDigestSha256 = "",
                trustedAckCookiePayloadDigestSha256 = "",
                challengeTokenDigestSha256 = evidence.challengeTokenDigestSha256,
                guardVersion = evidence.guardVersion,
                guardJsDigestSha256 = evidence.guardJsDigestSha256,
                guardWasmDigestSha256 = evidence.guardWasmDigestSha256,
                guardTpDigestSha256 = evidence.guardTpDigestSha256,
                observationSetDigestSha256 = evidence.observationSetDigestSha256,
                requiredObservationCount = evidence.requiredObservationCount,
                observed2xxCount = evidence.observed2xxCount,
                canaryRequestDigestSha256 = evidence.canaryRequestDigestSha256,
                canaryResponseDigestSha256 = evidence.canaryResponseDigestSha256,
                canaryStatus = evidence.canaryStatus,
                requestKeyId = requestKeyStore.requestKeyId,
                ackRequestBodyDigestSha256 = evidence.ackRequestBodyDigestSha256,
                ackResponseBodyDigestSha256 = evidence.ackResponseBodyDigestSha256,
                ackStatus = evidence.ackStatus,
                ackOutcome = evidence.ackOutcome,
                cookieGrantDigestSha256 = evidence.cookieGrantDigestSha256,
                cookieGrants = grants,
                startedAtElapsedNanos = current.startedAtNanos,
                completedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                canonicalEnvelope = byteArrayOf(),
                envelopeDigestSha256 = "",
                signature = byteArrayOf(),
            ),
            proofKey,
        )
        current.proof = proof
        requestKeyStore.authorizeExactCapability(proof)
        current.guardJavascript = byteArrayOf()
        current.guardWasm = byteArrayOf()
        current.challengeJson = ""
        mainHandler.post { deliverProof(current, proof) }
        return response
    }

    private fun deliverProof(current: Flight, proof: NtkAckProof) {
        if (flight !== current || current.tasks.isCancelled()) return
        val nativeTrustedGrant = proof.proofMode ==
            NtkAckProtocol.PROOF_MODE_TRUSTED_SERVER_GRANT
        if (state == State.WARMING && !nativeTrustedGrant) return
        check(state in setOf(State.EMPTY, State.READY, State.RUNNING, State.PROVED) ||
            (nativeTrustedGrant && state == State.WARMING)
        ) {
            "ACK proof delivered from invalid state $state"
        }
        if (nativeTrustedGrant && webView != null) destroyWebViewOnly()
        state = State.PROVED
        current.callbacks.toList().forEach { it(Result.success(proof)) }
        current.callbacks.clear()
    }

    private fun terminateRendererAndDestroy(current: Flight, proof: NtkAckProof) {
        assertMainLooper()
        if (flight !== current || state != State.QUIESCING) return
        val view = webView
        if (view == null) {
            finishWebViewDestroyAndSeal(current, proof, false, true)
            return
        }
        view.stopLoading()
        runCatching { view.removeJavascriptInterface(BRIDGE_NAME) }
        view.onPause()
        view.pauseTimers()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val renderer = view.webViewRenderProcess
            if (renderer != null && renderer.terminate()) {
                quiesceRendererGone = false
                val token = current.generationToken
                mainHandler.postDelayed(
                    {
                        if (flight === current && state == State.QUIESCING &&
                            current.seal == null && current.generationToken == token
                        ) {
                            failQuiescence(
                                current,
                                IllegalStateException("ACK renderer termination was not observed"),
                            )
                        }
                    },
                    QUIESCENCE_RENDERER_TIMEOUT_MS,
                )
                return
            }
        }
        finishWebViewDestroyAndSeal(current, proof, false, true)
    }

    private fun failQuiescence(current: Flight, error: Throwable) {
        assertMainLooper()
        if (flight !== current || state != State.QUIESCING || current.seal != null) return
        current.tasks.cancel()
        current.generationToken = UUID.randomUUID().toString()
        current.transport?.cancelAll()
        destroyWebViewOnly()
        requestKeyStore.clear()
        state = State.EMPTY
        flight = null
        val callback = quiesceCallback
        quiesceCallback = null
        callback?.invoke(
            Result.failure(
                NtkAckException(
                    NtkAckFailure(
                        NtkAckProtocol.VERSION,
                        current.request.flightId,
                        current.request.generation,
                        NtkAckProtocol.FAILURE_QUIESCENCE,
                        "quiescence_renderer_timeout",
                        true,
                        error.javaClass.simpleName + ":" + error.message.orEmpty(),
                        SystemClock.elapsedRealtimeNanos(),
                    ),
                ),
            ),
        )
    }

    private fun finishWebViewDestroyAndSeal(
        current: Flight,
        proof: NtkAckProof,
        rendererGone: Boolean,
        rendererAbsent: Boolean,
    ) {
        assertMainLooper()
        if (flight !== current || current.seal != null) return
        val view = webView
        if (view != null) {
            val parent = view.parent
            if (parent is ViewGroup) parent.removeView(view) else check(parent == null)
            view.removeAllViews()
            view.destroy()
        }
        webView = null
        bridge = null
        shellFlightId = ""
        val seal = NtkAckProofCodec.signQuiescence(
            NtkAckQuiescenceSeal(
                NtkAckProtocol.VERSION,
                serviceInstanceId,
                current.request.flightId,
                current.request.generation,
                current.request.authEpoch,
                current.request.origin,
                current.request.episodePath,
                proof.envelopeDigestSha256,
                Process.myPid(),
                0,
                true,
                rendererGone || quiesceRendererGone,
                rendererAbsent,
                current.transport?.activeCallCount ?: 0,
                SystemClock.elapsedRealtimeNanos(),
                byteArrayOf(),
                "",
                byteArrayOf(),
            ),
            proofKey,
        )
        requestKeyStore.markQuiesced(seal)
        current.seal = seal
        state = State.EMPTY
        val callback = quiesceCallback
        quiesceCallback = null
        callback?.invoke(Result.success(seal))
    }

    private fun cancelFlight(current: Flight, reasonCode: Int, stage: String) {
        if (!current.tasks.cancel()) return
        current.generationToken = UUID.randomUUID().toString()
        mainHandler.removeCallbacks(deadlineRunnable)
        current.transport?.cancelAll()
        val code = if (current.ackPostStarted && current.proof == null) {
            NtkAckProtocol.FAILURE_INDETERMINATE_ACK
        } else reasonCode
        val ownsCurrentFlight = flight === current
        val pendingQuiescence = if (ownsCurrentFlight) quiesceCallback else null
        if (ownsCurrentFlight) quiesceCallback = null
        requestKeyStore.invalidateFlight(current.identity)
        destroyWebViewOnly()
        if (ownsCurrentFlight && flight === current) {
            flight = null
            state = State.EMPTY
        }
        val failure = deliverFailure(current, code, stage, IllegalStateException(stage))
        pendingQuiescence?.invoke(Result.failure(failure))
    }

    private fun failFlight(current: Flight, code: Int, stage: String, error: Throwable) {
        if (flight !== current || current.proof != null || !current.tasks.cancel()) return
        current.generationToken = UUID.randomUUID().toString()
        mainHandler.removeCallbacks(deadlineRunnable)
        current.transport?.cancelAll()
        requestKeyStore.invalidateFlight(current.identity)
        deliverFailure(current, code, stage, error)
        destroyWebViewOnly()
        if (flight === current) {
            flight = null
            state = State.EMPTY
        }
    }

    private fun deliverFailure(
        current: Flight,
        code: Int,
        stage: String,
        error: Throwable,
    ): NtkAckException {
        val failure = NtkAckException(
            NtkAckFailure(
                NtkAckProtocol.VERSION,
                current.request.flightId,
                current.request.generation,
                code,
                stage,
                true,
                error.javaClass.simpleName + ":" + error.message.orEmpty(),
                SystemClock.elapsedRealtimeNanos(),
            ),
        )
        if (current.terminalDelivered) return failure
        current.terminalDelivered = true
        current.callbacks.toList().forEach { it(Result.failure(failure)) }
        current.callbacks.clear()
        return failure
    }

    private fun failureCode(current: Flight, error: Throwable): Int = when {
        current.ackPostStarted && current.proof == null -> NtkAckProtocol.FAILURE_INDETERMINATE_ACK
        SystemClock.elapsedRealtimeNanos() >= current.request.deadlineElapsedRealtimeNanos -> NtkAckProtocol.FAILURE_DEADLINE
        else -> NtkAckProtocol.FAILURE_PROOF_REJECTED
    }

    private fun destroyWebViewOnly() {
        val current = webView
        if (current != null) {
            runCatching { current.stopLoading() }
            runCatching { current.removeJavascriptInterface(BRIDGE_NAME) }
            runCatching { current.onPause() }
            runCatching { current.pauseTimers() }
            val parent = current.parent
            if (parent is ViewGroup) parent.removeView(current) else check(parent == null)
            runCatching { current.removeAllViews() }
            runCatching { current.destroy() }
        }
        webView = null
        bridge = null
        warmRequest = null
        shellFlightId = ""
        if (state != State.PROVED && state != State.QUIESCING) state = State.EMPTY
    }

    private fun expireAndSeedWebViewCookies(current: Flight, index: Int, done: () -> Unit) {
        check(index == 0)
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val operations = ArrayList<String>()
        NtkAckCookieBoundary.strictFreshNames.forEach {
            operations += "$it=; Path=/; Max-Age=0; SameSite=Lax; Secure"
        }
        current.request.seedCookies.forEach { cookie ->
            operations += "${cookie.name}=${cookie.value}; Path=${cookie.path.ifBlank { "/" }}; SameSite=Lax" +
                if (cookie.secure) "; Secure" else ""
        }
        if (operations.isEmpty()) {
            done()
            return
        }
        // Every strict-expiry name is disjoint from every permitted seed name. CookieManager
        // serializes its own store, so waiting for each callback before submitting the next adds
        // hundreds of milliseconds to a cold shell without adding ordering guarantees. Wait for
        // the whole independent set once; the callback contract makes the in-memory jar visible
        // to the flight document, and this ephemeral proof WebView does not need a disk flush.
        val remaining = AtomicInteger(operations.size)
        operations.forEach { operation ->
            manager.setCookie(current.request.origin, operation) {
                if (remaining.decrementAndGet() == 0) mainHandler.post(done)
            }
        }
    }

    private fun clearWebViewCookies(origin: String, clearCloudflare: Boolean, done: () -> Unit) {
        val names = NtkAckCookieBoundary.strictFreshNames.toMutableList()
        if (clearCloudflare) names += listOf("cf_clearance", "__cf_bm")
        if (origin.isBlank()) {
            done()
            return
        }
        val manager = CookieManager.getInstance()
        fun expire(index: Int) {
            if (index >= names.size) {
                manager.flush(); done(); return
            }
            manager.setCookie(origin, "${names[index]}=; Path=/; Max-Age=0; Secure") {
                mainHandler.post { expire(index + 1) }
            }
        }
        expire(0)
    }

    private fun configureSettings(settings: WebSettings, userAgent: String) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = userAgent
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // This private shell cannot navigate: file/content access is disabled and every HTTP
            // request is either served from the flight's immutable metric map or rejected by the
            // WebViewClient. Safe Browsing therefore adds only a cold provider/service startup to
            // an already closed URL set.
            settings.safeBrowsingEnabled = false
        }
    }

    private fun measure(view: WebView, viewport: NtkAckViewport) {
        val width = viewport.widthPx.coerceAtLeast(1)
        val height = viewport.heightPx.coerceAtLeast(1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun flightShellHtml(
        viewport: NtkAckViewport,
        episodePath: String,
        flightId: String,
        seedCookies: List<NtkAckCookie>,
    ): String =
        "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>NTK ACK Shell</title>" +
            "<style>html,body{margin:0;width:100%;height:100%}#__ntk_guard_rows{display:grid;grid-template-columns:repeat(4,78px);gap:8px;width:390px;min-height:76px;position:relative;visibility:visible;opacity:1}button,img{display:block;width:78px;height:48px;border:0}</style>" +
            "<script>${flightCookieBootstrapScript(seedCookies)}window.__ntk_fast_shell=0;window.__ntk_ack_only_shell=1;window.__ntk_ib_ok=1;window.__ntk_ib_loaded=1;window.__ntk_hs_ok=1;window.__ntk_ack_scope=${JSONObject.quote(episodePath)};</script>" +
            "</head><body><script id=init-html-sentinel>try{window.__ntk_ib_ok=1}catch(_){}</script><section id=__ntk_guard_rows data-br=1 data-brs=header data-br-n=0></section>" +
            "<script>window.__bSeen=0;document.documentElement.setAttribute('data-ab','__bSeen');document.documentElement.setAttribute('data-rb','__bSeen');" +
            "try{window.NtkAckBridge.onShellReady(${JSONObject.quote(flightId)})}catch(_){}</script></body></html>"

    private fun flightCookieBootstrapScript(seedCookies: List<NtkAckCookie>): String {
        val assignments = ArrayList<String>(NtkAckCookieBoundary.strictFreshNames.size + seedCookies.size)
        NtkAckCookieBoundary.strictFreshNames.forEach { name ->
            assignments += "$name=; Path=/; Max-Age=0; SameSite=Lax; Secure"
        }
        seedCookies.forEach { cookie ->
            assignments += "${cookie.name}=${cookie.value}; Path=${cookie.path.ifBlank { "/" }}; SameSite=Lax" +
                if (cookie.secure) "; Secure" else ""
        }
        return assignments.joinToString(separator = "", postfix = "") { value ->
            "try{document.cookie=${JSONObject.quote(value)}}catch(_){};"
        }
    }

    private fun readGuardVersion(): String {
        val value = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
            .getString("ntk_guard_version", "").orEmpty()
        return value.takeIf { it.matches(Regex("b\\d{13}-wasm-\\d{13}")) } ?: "latest"
    }

    private fun signingCertificateDigest(): String {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        } ?: error("App signing certificate unavailable")
        return NtkAckProofCodec.sha256Hex(signature.toByteArray())
    }

    private fun validateWarmRequest(request: NtkAckWarmRequest) {
        require(request.protocolVersion == NtkAckProtocol.VERSION && request.authEpoch > 0L)
        require(request.userAgent.isNotBlank())
        require(request.viewport.widthPx > 0 && request.viewport.heightPx > 0)
        require(request.clientPid > 0)
    }

    private fun validateAckRequest(request: NtkAckRequest) {
        require(request.protocolVersion == NtkAckProtocol.VERSION)
        UUID.fromString(request.flightId)
        require(request.generation > 0L && request.authEpoch > 0L)
        require(request.requestNonce.size == 32)
        require(request.origin == URI(request.origin).let { "${it.scheme}://${it.host}" } && request.origin.startsWith("https://"))
        require(request.episodePath.matches(Regex("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$")))
        require(request.userAgent.isNotBlank() && request.uaMetadata.isNotBlank())
        require(request.deadlineElapsedRealtimeNanos > SystemClock.elapsedRealtimeNanos())
        require(request.clientPid > 0)
        NtkAckCookieBoundary.validateSeeds(request.origin, request.episodePath, request.seedCookies)
    }

    private fun NtkAckRequest.toWarmRequest() = NtkAckWarmRequest(
        protocolVersion, authEpoch, userAgent, viewport, clientPid,
    )

    private fun checkActive(current: Flight) {
        check(flight === current && !current.tasks.isCancelled()) { "ACK flight is not active" }
        check(SystemClock.elapsedRealtimeNanos() < current.request.deadlineElapsedRealtimeNanos) { "ACK deadline" }
    }

    private fun <T> withFlight(token: String, block: (Flight) -> T): T? {
        val current = flight ?: return null
        if (current.tasks.isCancelled() || token != current.generationToken) return null
        return block(current)
    }

    private fun bridgeResult(result: NtkAckTransport.Result): String {
        val headers = JSONObject()
        result.headers.forEach { (name, values) -> headers.put(name, values.joinToString(", ")) }
        return JSONObject()
            .put("ok", true)
            .put("status", result.status)
            .put("bodyBase64", Base64.getEncoder().encodeToString(result.body))
            .put("headers", headers)
            .toString()
    }

    private fun bridgeError(message: String): String = JSONObject()
        .put("ok", false).put("error", message).toString()

    private fun bridgeNoContent(): String = JSONObject()
        .put("ok", true)
        .put("status", 204)
        .put("bodyBase64", "")
        .put("headers", JSONObject())
        .toString()

    private fun cachedMetricResponse(result: NtkAckTransport.Result): WebResourceResponse {
        val contentType = result.headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.firstOrNull().orEmpty().substringBefore(';').ifBlank { "image/gif" }
        val headers = result.headers.mapValues { (_, values) -> values.joinToString(", ") }.toMutableMap()
        headers["Cache-Control"] = "no-store"
        return WebResourceResponse(
            contentType,
            null,
            200,
            "OK",
            headers,
            ByteArrayInputStream(result.body),
        )
    }

    private fun blockedWebResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(byteArrayOf()),
    )

    private fun resolve(origin: String, raw: String): String = URI(origin + "/").resolve(raw).toString()

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun completeWarm(result: Result<Unit>) {
        val callbacks = warmCallbacks.toList()
        warmCallbacks.clear()
        callbacks.forEach { it(result) }
    }

    private fun assertMainLooper() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    companion object {
        private const val TAG = "NtkAckBrowserEngine"
        private const val BRIDGE_NAME = "NtkAckBridge"
        private const val QUIESCENCE_RENDERER_TIMEOUT_MS = 5_000L
        private const val INERT_ORIGIN = "https://ntk-ack.invalid/"
        private const val INERT_HTML = "<!doctype html><meta name=viewport content='width=device-width'><title>ntk-ack-inert</title>"
        private const val BUNDLED_GUARD_JAVASCRIPT = "ntk_guard/guard.js"
        private const val BUNDLED_GUARD_WASM = "ntk_guard/guard-wasm.bin"

        private val GUARD_EXECUTION_SCRIPT = """
            (async function(){
              const bridge=window.NtkAckBridge, token=String(bridge.generationToken()||'');
              function finish(ok,error){try{bridge.onGuardFinished(token,!!ok,String(error||''));}catch(_){}}
              function note(value){try{bridge.onGuardState(token,String(value||''));}catch(_){}}
              try{
                if(!token) throw new Error('missing generation token');
                note('script-start');
                const decode=function(value){const raw=atob(String(value||'')),out=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)out[i]=raw.charCodeAt(i)&255;return out;};
                const nativeToString=Function.prototype.toString;
                async function body64(value){let bytes;if(value instanceof Request)bytes=new Uint8Array(await value.clone().arrayBuffer());else if(value instanceof Blob)bytes=new Uint8Array(await value.arrayBuffer());else if(value instanceof ArrayBuffer)bytes=new Uint8Array(value);else if(ArrayBuffer.isView(value))bytes=new Uint8Array(value.buffer,value.byteOffset,value.byteLength);else{const text=typeof value==='string'?value:value instanceof URLSearchParams?value.toString():JSON.stringify(value||{});bytes=new TextEncoder().encode(text);}let raw='';for(let i=0;i<bytes.length;i+=0x8000)raw+=String.fromCharCode.apply(null,bytes.subarray(i,i+0x8000));return btoa(raw);}
                function response(raw){const value=JSON.parse(String(raw||'{}'));if(!value.ok)throw new Error(value.error||'native request failed');const bytes=decode(value.bodyBase64||'');return new Response(bytes,{status:value.status||200,headers:value.headers||{}});}
                const nativeFetch=window.fetch;
                const wrappedFetch=async function(input,init){const url=new URL(typeof input==='string'?input:input.url,location.href),method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();if(method==='POST'&&(url.pathname==='/api/ad/canary'||url.pathname==='/api/ad/ack')){note('fetch:'+url.pathname);const body=(init&&Object.prototype.hasOwnProperty.call(init,'body'))?init.body:(input instanceof Request?input:'');return response(bridge.request(token,url.href,method,JSON.stringify((init&&init.headers)||{}),await body64(body)));}throw new Error('forbidden guard fetch '+url.pathname);};
                wrappedFetch.__ntkNativeString='function fetch() { [native code] }';
                window.fetch=wrappedFetch;
                const beacon=function(url,data){try{const u=new URL(url,location.href);if(u.pathname==='/api/ad/canary'||u.pathname==='/api/ad/ack'){note('beacon:'+u.pathname);body64(data).then(function(encoded){bridge.request(token,u.href,'POST','{}',encoded);}).catch(function(error){finish(false,error);});return true;}}catch(_){}return false;};
                beacon.__ntkNativeString='function sendBeacon() { [native code] }';
                navigator.sendBeacon=beacon;
                const spoof=function(){try{if(this&&this.__ntkNativeString)return this.__ntkNativeString;}catch(_){}return nativeToString.apply(this,arguments);};
                spoof.__ntkNativeString='function toString() { [native code] }';
                Function.prototype.toString=spoof;
                window.__ntk_fast_shell=0;window.__ntk_ack_only_shell=1;window.__ntk_ib_ok=1;window.__ntk_ib_loaded=1;window.__ntk_hs_ok=1;window.__ntk_ack_scope=location.pathname;window.__ntkAckOnlyDirectAdApi=1;window.__ntkNaturalSamePageOwner=1;window.__ntk_request_key_id='';
                let source=new TextDecoder().decode(decode(bridge.guardJavascriptBase64(token)));
                const wasm=decode(bridge.guardWasmBase64(token));
                let exportSpec='',exports=['__i0','__i1','__i2','__i3','__i4','__i5','__i6','_hk','_vc','initSync'];
                source=source.replace(/import\.meta\.url/g,'location.href');
                source=source.replace(/export\s+function\s+([A-Za-z0-9_$]+)\s*\(/g,function(_,name){if(exports.indexOf(name)<0)exports.push(name);return 'function '+name+'(';});
                source=source.replace(/export\s*\{([^}]+)\}\s*;?/g,function(_,spec){exportSpec=spec;return '';});
                let attach=';window.__ntkGuardModule={};';
                exports.forEach(function(name){attach+='try{if(typeof '+name+'!=="undefined")window.__ntkGuardModule["'+name+'"]='+name+';}catch(_){}';});
                exportSpec.split(',').forEach(function(part){const match=String(part||'').trim().match(/^([A-Za-z0-9_$]+)\s+as\s+([A-Za-z0-9_$]+)$/);if(match)attach+='try{if(typeof '+match[1]+'!=="undefined")window.__ntkGuardModule["'+match[2]+'"]='+match[1]+';}catch(_){}';});
                (0,eval)(source+attach);
                const mod=window.__ntkGuardModule;
                if(!mod)throw new Error('guard module missing');
                if(mod.initSync)mod.initSync(wasm.buffer.slice(wasm.byteOffset,wasm.byteOffset+wasm.byteLength));
                else if(mod.default)await mod.default({module_or_path:URL.createObjectURL(new Blob([wasm],{type:'application/wasm'}))});
                if(!mod.__i4)throw new Error('guard __i4 missing');
                note('module-ready');
                const prerequisiteWaitStart=performance.now();
                while(!bridge.prerequisitesReady(token)){
                  if(performance.now()-prerequisiteWaitStart>2500)throw new Error('network prerequisites timeout');
                  await new Promise(function(resolve){setTimeout(resolve,4);});
                }
                note('prerequisites-ready');
                window.__ntk_request_key_id=String(bridge.requestKeyId(token)||'');
                if(!window.__ntk_request_key_id)throw new Error('request key missing');
                const challenge=JSON.parse(String(bridge.challengeJson(token)||'{}'));
                const rows=document.getElementById('__ntk_guard_rows');
                const slots=Math.max(4,Number(challenge.slotCount||4));
                const metricUrls=Array.isArray(challenge.impressionUrls)?challenge.impressionUrls:[];
                rows.textContent='';rows.style.cssText='display:grid;grid-template-columns:repeat(4,78px);gap:8px;width:390px;min-height:76px;padding:14px 0;box-sizing:border-box;margin:0;position:relative;z-index:1;opacity:1;visibility:visible;pointer-events:auto';rows.setAttribute('data-ntk-token',String(challenge.token||''));rows.setAttribute('data-br-n',String(slots));window.__ntkCompactLoadedToken=String(challenge.token||'');window.__ntkCompactLoadedImps=0;
                const requiredLoaded=Math.max(1,Number(challenge.minSeen||2));let readyImages;const imagesReady=new Promise(function(resolve){readyImages=resolve;});
                for(let i=0;i<slots;i++){const button=document.createElement('button');button.type='button';button.className='';button.setAttribute('aria-label','newtoki62');button.setAttribute(i===0?'data-bs':'data-bp','1');button.style.cssText='display:block;width:78px;height:48px;padding:0;margin:0;border:0;background:transparent;opacity:1;visibility:visible;pointer-events:auto';const image=document.createElement('img');image.width=78;image.height=48;image.alt='';image.loading='eager';image.decoding='async';image.style.cssText='display:block;width:78px;height:48px;object-fit:cover;opacity:1;visibility:visible';image.onload=function(){if(window.__ntkCompactLoadedToken===String(challenge.token||'')){window.__ntkCompactLoadedImps=Number(window.__ntkCompactLoadedImps||0)+1;if(window.__ntkCompactLoadedImps>=requiredLoaded)readyImages();}};image.src=metricUrls.length?new URL(metricUrls[i%metricUrls.length],location.href).href:'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="78" height="48"/>';button.appendChild(image);rows.appendChild(button);}
                window.__bSeen=slots;
                await Promise.race([imagesReady,new Promise(function(_,reject){setTimeout(function(){reject(new Error('metric DOM load timeout '+window.__ntkCompactLoadedImps));},1200);})]);note('rows-ready:'+window.__ntkCompactLoadedImps);
                const primeArgs=[String(challenge.token||''),JSON.stringify({token:String(challenge.token||''),path:location.pathname}),location.pathname];
                for(const arg of primeArgs){try{if(mod._vc)mod._vc(arg,location.pathname);}catch(_){}}
                for(const arg of primeArgs){try{if(mod._hk)mod._hk(arg,location.pathname);}catch(_){}}
                note('i4-start');
                let result=mod.__i4(JSON.stringify(challenge),location.pathname);
                if(result&&result.then)await result;
                note('i4-return:'+String(result));
                finish(true,'');
              }catch(error){finish(false,String(error&&error.stack||error));}
            })();
        """.trimIndent()
    }
}

class NtkAckException(val failure: NtkAckFailure) : IllegalStateException(failure.message)
