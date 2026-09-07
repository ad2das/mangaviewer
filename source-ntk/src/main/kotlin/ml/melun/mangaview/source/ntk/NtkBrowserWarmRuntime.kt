package ml.melun.mangaview.source.ntk

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.net.URI

/** Sole owner of origin-only WebView warmup state. */
internal class NtkBrowserWarmRuntime(
    private val handler: Handler,
    private val cookieApplier: NtkBrowserCookieApplier,
    private val staticResources: NtkBrowserStaticResourceCache,
    private val awaitStartup: (ready: () -> Unit, failed: (Throwable) -> Unit) -> Unit,
    private val acquireBrowser: (String) -> WebView,
    private val currentRequest: () -> RemoteRequest?,
    private val navigate: (RemoteRequest) -> Unit,
    private val park: () -> Unit,
    private val preparationFailed: (RemoteRequest, String) -> Unit,
    private val originReady: (String) -> Unit = {},
) {
    private var pendingUserAgent: String? = null
    private var pendingOrigin: String? = null
    private var recipient: Messenger? = null
    private var generation = 0L
    private var preparationGeneration = 0L
    private var startedAtMillis = 0L
    private var navigationOrigin: String? = null
    private var runtimeOrigin: String? = null
    private var runtimeMarker: String? = null
    private var runtimeGeneration = 0L
    private var runtimePageReady = false
    private var completedOrigin: String? = null
    private var documentReadyOrigin: String? = null
    private var credentialsReadyOrigin: String? = null
    private var readyOrigin: String? = null

    fun handle(message: Message) {
        val warmup = runCatching {
            val userAgent = message.data.requiredString(NtkBrowserProtocol.KEY_USER_AGENT).also {
                require(it.length <= MAX_USER_AGENT_LENGTH) { "NTK user agent is too long" }
            }
            val origin = validatedKey(
                message.data.requiredString(NtkBrowserProtocol.KEY_ORIGIN),
                "/",
            ).removeSuffix("/")
            Triple(userAgent, origin, browserIdentity(message.data))
        }.getOrElse { failure ->
            Log.w(TAG, "browser warmup rejected", failure)
            return
        }
        val (userAgent, origin, identity) = warmup
        pendingUserAgent = userAgent
        pendingOrigin = origin
        recipient = message.replyTo
        staticResources.prepare(origin, userAgent)
        if (completedOrigin == origin || navigationOrigin == origin || runtimeOrigin == origin) return
        val cookieGeneration = ++preparationGeneration
        awaitStartup(
            {
                cookieApplier.identity(origin, identity, {
                    cookieGeneration == preparationGeneration && pendingOrigin == origin
                }) { accepted ->
                    if (cookieGeneration != preparationGeneration) return@identity
                    if (!accepted) {
                        preparationFailed(origin, "NTK warm identity cookies rejected")
                        return@identity
                    }
                    if (pendingOrigin == origin && completedOrigin != origin &&
                        navigationOrigin != origin
                    ) begin(userAgent, origin)
                }
            },
            { failure -> preparationFailed(origin, "NTK browser startup failed: ${failure.message}") },
        )
    }

    private fun preparationFailed(origin: String, detail: String) {
        if (pendingOrigin != origin) return
        pendingOrigin = null
        pendingUserAgent = null
        recipient?.let { sendWarmPhase(it, "preparation-failed", 0, 0L) }
        Log.w(TAG, detail)
        currentRequest()?.takeIf { it.origin == origin }?.let { preparationFailed(it, detail) }
    }

    fun phase(origin: String, reportedGeneration: Long, phase: String, status: Int) {
        val normalized = runCatching { validatedKey(origin, "/").removeSuffix("/") }.getOrNull()
            ?: return
        if (reportedGeneration != generation || normalized != pendingOrigin) return
        if (runtimeOrigin != normalized) return
        val age = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
        val safePhase = phase.take(WARM_PHASE_LENGTH).replace('\n', '_').replace('\r', '_')
        Log.d(ACK_TAG, "phase=warm-$safePhase status=$status ageMs=$age")
        recipient?.let { sendWarmPhase(it, safePhase, status, age) }
        when (phase) {
            "origin-ready" -> if (status in 200..299) markOriginReady(normalized)
            "document-ready" -> if (status in 200..299) {
                documentReadyOrigin = normalized
                finishIfReady(normalized)
            }
            "complete" -> {
                navigationOrigin = null
                if (status in 200..299) credentialsReadyOrigin = normalized
                finishIfReady(normalized)
                if (runtimePageReady) finish(normalized)
            }
        }
    }

    fun isPending(origin: String): Boolean =
        pendingOrigin == origin && completedOrigin != origin

    fun isRunning(origin: String): Boolean = runtimeOrigin == origin

    fun isOriginReady(origin: String): Boolean = readyOrigin == origin

    fun pageFinished(url: String) {
        val origin = runtimeOrigin ?: return
        val marker = runtimeMarker ?: return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.path != "/" || uri.fragment != marker) return
        runtimePageReady = true
        if (navigationOrigin == null) finish(origin)
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val origin = runtimeOrigin ?: return null
        if (!request.isForMainFrame) return null
        val requested = runCatching { URI(request.url.toString()) }.getOrNull() ?: return null
        val expected = runCatching { URI(origin) }.getOrNull() ?: return null
        if (requested.scheme != expected.scheme || requested.authority != expected.authority ||
            requested.path != "/"
        ) return null
        val bytes = NtkBrowserWarmPage.html(origin, generation).toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "text/html",
            Charsets.UTF_8.name(),
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "Content-Length" to bytes.size.toString()),
            ByteArrayInputStream(bytes),
        )
    }

    fun failed() { runtimeOrigin?.let { finish(it, forced = true) } }

    fun browserRetired() {
        preparationGeneration += 1L
        readyOrigin = null
        navigationOrigin = null
        runtimeOrigin = null
        runtimeMarker = null
        runtimePageReady = false
        documentReadyOrigin = null
        credentialsReadyOrigin = null
        runtimeGeneration += 1L
    }

    fun restart() {
        val userAgent = pendingUserAgent ?: return
        val origin = pendingOrigin ?: return
        runCatching { begin(userAgent, origin) }
            .onFailure { Log.w(TAG, "browser warmup restart failed", it) }
    }

    private fun begin(userAgent: String, origin: String) {
        val view = acquireBrowser(userAgent)
        navigationOrigin = origin
        generation += 1L
        runtimeGeneration += 1L
        val currentGeneration = runtimeGeneration
        val marker = "native-runtime-warm-$currentGeneration"
        runtimeOrigin = origin
        runtimeMarker = marker
        runtimePageReady = false
        documentReadyOrigin = null
        credentialsReadyOrigin = null
        startedAtMillis = SystemClock.elapsedRealtime()
        readyOrigin = null
        view.settings.userAgentString = userAgent
        applyRenderPolicy(view, NtkBrowserRenderPhase.ORIGIN_WARMUP)
        view.resumeTimers()
        view.onResume()
        Log.d(ACK_TAG, "phase=warm-runtime-start status=0 generation=$currentGeneration")
        view.loadUrl("$origin/#$marker")
        handler.postDelayed({
            if (currentGeneration == runtimeGeneration && runtimeOrigin == origin) {
                finish(origin, forced = true)
            }
        }, MAX_RUNTIME_WARM_MILLIS)
    }

    private fun markOriginReady(origin: String) {
        readyOrigin = origin
        currentRequest()?.takeIf { it.origin == origin }
            ?.advanceAckState(NtkAckPreparationState.ORIGIN_READY)
        originReady(origin)
    }

    private fun finishIfReady(origin: String) {
        if (documentReadyOrigin == origin && credentialsReadyOrigin == origin) finish(origin)
    }

    private fun finish(origin: String, forced: Boolean = false) {
        if (runtimeOrigin != origin) return
        navigationOrigin = null
        runtimeOrigin = null
        runtimeMarker = null
        completedOrigin = origin
        Log.d(
            ACK_TAG,
            "phase=warm-runtime-end status=${if (forced) 0 else 200} generation=$runtimeGeneration",
        )
        val request = currentRequest()?.takeIf { it.origin == origin }
        request?.identityCookiesApplied = true
        if (request == null) park() else navigate(request)
    }

    private fun browserIdentity(data: Bundle): NtkBrowserIdentity? {
        val fingerprint = data.getString(NtkBrowserProtocol.KEY_FINGERPRINT)
        val persistentId = data.getString(NtkBrowserProtocol.KEY_PERSISTENT_ID)
        require((fingerprint == null) == (persistentId == null)) {
            "NTK browser identity is incomplete"
        }
        return fingerprint?.let { NtkBrowserIdentity(it, requireNotNull(persistentId)) }
    }

    private companion object {
        const val ACK_TAG = "NtkAck"
        const val WARM_PHASE_LENGTH = 96
        const val MAX_RUNTIME_WARM_MILLIS = 3_500L
    }
}
