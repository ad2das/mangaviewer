package ml.melun.mangaview.source.ntk

import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView

internal class NtkAdjacentChallengeController(
    private val handler: Handler,
    private val profileName: () -> String,
    private val currentRequest: () -> RemoteRequest?,
    private val browser: () -> WebView?,
    private val parkCompletedBrowser: (RemoteRequest) -> Unit,
) {
    private val seeds = linkedMapOf<String, AdjacentChallengeSeed>()

    fun clear() = seeds.clear()

    fun consume(request: RemoteRequest) {
        pruneSeeds()
        val seed = seeds.remove(request.key) ?: return
        request.challengePreflightStarted = true
        request.challengePreflightResolved = true
        request.challengePayload = seed.payload
        request.challengeReceivedAtMillis = seed.receivedAtMillis
        Log.d(ACK_TAG, "phase=adjacent-challenge-consumed status=200 ageMs=${seed.ageMillis()}")
    }

    fun preflight(message: Message) {
        val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        val request = currentRequest()?.takeIf { it.contains(requestId) } ?: return
        val path = runCatching {
            message.data.requiredString(NtkBrowserProtocol.KEY_ADJACENT_PATH)
        }.getOrNull() ?: return
        val key = runCatching { validatedKey(request.origin, path) }.getOrNull() ?: return
        if (key == request.key || !EPISODE_PATH.matches(path)) return
        request.adjacentChallenges.getOrPut(path) { NtkAdjacentChallengeFlight() }
        if (request.delivery.completedPayload() != null) begin(request)
    }

    fun begin(request: RemoteRequest): Boolean {
        if (currentRequest() !== request) return false
        val view = browser() ?: return false
        val pending = request.adjacentChallenges.filterValues { !it.started }
        if (pending.isEmpty()) return request.adjacentChallenges.values.any { !it.resolved }
        applyRenderPolicy(view, NtkBrowserRenderPhase.PARKED)
        pending.forEach { (path, flight) -> start(view, request, path, flight) }
        return true
    }

    fun accept(
        request: RemoteRequest,
        key: String,
        path: String,
        status: Int,
        payload: String,
    ) {
        if (currentRequest() !== request || request.adjacentChallenges[path] == null) return
        val accepted = payload.takeIf { NtkBrowserChallengePreflight.accepts(path, status, it) }
        if (accepted != null) {
            pruneSeeds()
            seeds[key] = AdjacentChallengeSeed(accepted, SystemClock.elapsedRealtime())
        }
        Log.d(ACK_TAG, "phase=adjacent-challenge-end status=$status accepted=${accepted != null} path=$path")
        finish(request, path)
    }

    private fun start(
        view: WebView,
        request: RemoteRequest,
        path: String,
        flight: NtkAdjacentChallengeFlight,
    ) {
        flight.started = true
        Log.d(ACK_TAG, "profile=${profileName()} phase=adjacent-challenge-start status=0 path=$path")
        view.evaluateJavascript(
            NtkBrowserChallengePreflight.startAdjacent(path, request.requestId),
            null,
        )
        handler.postDelayed({ finish(request, path) }, CHALLENGE_PREFLIGHT_TIMEOUT_MILLIS)
    }

    private fun finish(request: RemoteRequest, path: String) {
        if (currentRequest() !== request) return
        val flight = request.adjacentChallenges[path] ?: return
        if (flight.resolved) return
        flight.resolved = true
        if (request.delivery.completedPayload() != null &&
            request.adjacentChallenges.values.all { it.resolved }
        ) parkCompletedBrowser(request)
    }

    private fun pruneSeeds() {
        val iterator = seeds.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.ageMillis() > ADJACENT_CHALLENGE_TTL_MILLIS) iterator.remove()
        }
        while (seeds.size > MAX_ADJACENT_CHALLENGE_SEEDS) seeds.remove(seeds.keys.first())
    }
}

private data class AdjacentChallengeSeed(
    val payload: String,
    val receivedAtMillis: Long,
) {
    fun ageMillis(): Long =
        (SystemClock.elapsedRealtime() - receivedAtMillis).coerceAtLeast(0L)
}

private const val ACK_TAG = "NtkAck"
private const val CHALLENGE_PREFLIGHT_TIMEOUT_MILLIS = 1_500L
private const val ADJACENT_CHALLENGE_TTL_MILLIS = 15_000L
private const val MAX_ADJACENT_CHALLENGE_SEEDS = 1
private val EPISODE_PATH = Regex("^/(?:webtoon|manhwa)/[^/]+/[^/]+$")
