package ml.melun.mangaview.source.ntk

import org.json.JSONObject

/** Moves the exact challenge RTT under the native episode-document request on the same origin. */
internal object NtkBrowserChallengePreflight {
    fun start(path: String, requestId: Long): String {
        val safePath = JSONObject.quote(path)
        return """
            (() => {
              const path = $safePath;
              const requestId = $requestId;
              try {
                history.replaceState(history.state, '', path);
              } catch (_) {
                window.NtkNativeManifest.onPreflightChallenge(
                  location.origin, path, requestId, 0, ''
                );
                return;
              }
              void fetch('/api/ad/challenge', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({path, force: false}),
                credentials: 'include',
                cache: 'no-store'
              }).then(async response => {
                const payload = await response.text();
                window.NtkNativeManifest.onPreflightChallenge(
                  location.origin, path, requestId, response.status, payload
                );
              }).catch(() => window.NtkNativeManifest.onPreflightChallenge(
                location.origin, path, requestId, 0, ''
              ));
            })();
        """.trimIndent()
    }

    /** Fetches a future scope without changing the current document or starting its ACK. */
    fun startAdjacent(path: String, requestId: Long): String {
        val safePath = JSONObject.quote(path)
        return """
            (() => {
              const path = $safePath;
              const requestId = $requestId;
              const previousPath = location.pathname + location.search + location.hash;
              try {
                history.replaceState(history.state, '', path);
              } catch (_) {
                window.NtkNativeManifest.onPreflightChallenge(
                  location.origin, path, requestId, 0, ''
                );
                return;
              }
              const restore = () => {
                try { history.replaceState(history.state, '', previousPath); } catch (_) {}
              };
              const flight = fetch('/api/ad/challenge', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({path, force: false}),
                credentials: 'include',
                cache: 'no-store'
              });
              restore();
              void flight.then(async response => {
                const payload = await response.text();
                window.NtkNativeManifest.onPreflightChallenge(
                  location.origin, path, requestId, response.status, payload
                );
              }).catch(() => {
                window.NtkNativeManifest.onPreflightChallenge(
                  location.origin, path, requestId, 0, ''
                );
              });
            })();
        """.trimIndent()
    }

    fun seed(payload: String?, elapsedSinceReceiptMillis: Long): String {
        if (payload == null) return ""
        return "window.__nativeChallengeSeed = {payload: ${JSONObject.quote(payload)}, " +
            "ageMillis: ${elapsedSinceReceiptMillis.coerceAtLeast(0L)}};"
    }

    fun accepts(path: String, status: Int, payload: String): Boolean = runCatching {
        if (status !in 200..299 || payload.length !in 2..MAX_PAYLOAD_CHARS) return false
        val root = JSONObject(payload)
        val challenge = root.optJSONObject("challenge") ?: return false
        root.optBoolean("ok") && challenge.optString("scope") == path &&
            challenge.optDouble("minSeen", -1.0).let { it.isFinite() && it >= 0.0 }
    }.getOrDefault(false)

    fun shape(path: String, payload: String): String = runCatching {
        val root = JSONObject(payload)
        val challenge = root.optJSONObject("challenge")
        val fields = challenge?.keys()?.asSequence()?.toList()?.sorted()?.joinToString("|")
            ?.take(160)
        "ok=${root.optBoolean("ok")},ackValid=${root.optBoolean("ackValid")}," +
            "trusted=${root.optBoolean("trusted")},challenge=${challenge != null}," +
            "scopeMatch=${challenge?.optString("scope") == path}," +
            "minSeen=${challenge?.optDouble("minSeen", -1.0)},fields=$fields"
    }.getOrDefault("invalid-json")

    private const val MAX_PAYLOAD_CHARS = 64 * 1_024
}
