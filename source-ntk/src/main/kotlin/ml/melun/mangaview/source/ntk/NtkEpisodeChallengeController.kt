package ml.melun.mangaview.source.ntk

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView

internal class NtkEpisodeChallengeController(
    private val handler: Handler,
    private val profileName: () -> String,
    private val currentRequest: () -> RemoteRequest?,
    private val browser: () -> WebView?,
    private val originReady: (String) -> Boolean,
    private val warmRunning: (String) -> Boolean,
    private val navigateDocument: (WebView, RemoteRequest) -> Unit,
    private val adjacent: NtkAdjacentChallengeController,
) {
    fun beginEpisodeNavigation(view: WebView, request: RemoteRequest) {
        if (currentRequest() !== request || request.documentNavigationStarted ||
            !request.documentCookiesApplied || !request.identityCookiesApplied
        ) return
        val sameOriginContext = originReady(request.origin) || view.isOnOrigin(request.origin)
        when {
            request.challengePreflightResolved && !warmRunning(request.origin) ->
                navigateDocument(view, request)
            request.challengePreflightResolved -> Unit
            !sameOriginContext -> navigateDocument(view, request)
            else -> begin(view, request)
        }
    }

    fun beginWhileWarm(origin: String) {
        val request = currentRequest()?.takeIf { it.origin == origin } ?: return
        browser()?.let { begin(it, request) }
    }

    fun finishIfUnresolved(request: RemoteRequest) {
        if (!request.challengePreflightResolved) finish(request, payload = null)
    }

    fun accept(origin: String, path: String, requestId: Long, status: Int, payload: String) {
        val request = currentRequest()?.takeIf {
            it.contains(requestId) || requestId in it.inheritedChallengeRequestIds
        } ?: return
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return
        if (request.adjacentChallenges[path] != null && request.key != key) {
            adjacent.accept(request, key, path, status, payload)
            return
        }
        if (request.key != key || request.documentNavigationStarted) return
        val accepted = payload.takeIf { NtkBrowserChallengePreflight.accepts(path, status, it) }
        Log.d(
            ACK_TAG,
            "phase=challenge-overlap-end status=$status accepted=${accepted != null} " +
                "${NtkBrowserChallengePreflight.shape(path, payload)} ageMs=${request.ageMillis()}",
        )
        finish(request, accepted)
    }

    private fun begin(view: WebView, request: RemoteRequest) {
        if (currentRequest() !== request || request.documentNavigationStarted ||
            request.challengePreflightStarted || !request.documentCookiesApplied || !request.identityCookiesApplied
        ) return
        request.challengePreflightStarted = true
        Log.d(
            ACK_TAG,
            "profile=${profileName()} phase=challenge-overlap-start status=0 ageMs=${request.ageMillis()}",
        )
        view.evaluateJavascript(
            NtkBrowserChallengePreflight.start(request.path, request.requestId),
            null,
        )
        handler.postDelayed({ finish(request, payload = null) }, NTK_CHALLENGE_TIMEOUT_MILLIS)
    }

    private fun finish(request: RemoteRequest, payload: String?) {
        if (currentRequest() !== request || request.documentNavigationStarted ||
            request.challengePreflightResolved
        ) return
        request.challengePayload = payload
        request.challengeReceivedAtMillis = if (payload == null) 0L else SystemClock.elapsedRealtime()
        request.challengePreflightResolved = true
        if (!warmRunning(request.origin)) browser()?.let { navigateDocument(it, request) }
    }
}

private fun WebView.isOnOrigin(origin: String): Boolean = runCatching {
    val current = java.net.URI(requireNotNull(url))
    val expected = java.net.URI(origin)
    current.scheme == expected.scheme && current.authority == expected.authority
}.getOrDefault(false)

private const val ACK_TAG = "NtkAck"
internal const val NTK_CHALLENGE_TIMEOUT_MILLIS = 1_500L
