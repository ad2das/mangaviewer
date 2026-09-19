package ml.melun.mangaview.app

import android.os.Handler
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Bytes a solved clearance WebView returned for a route of its own origin. */
internal data class FetchedPage(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.lastOrNull()
}

/**
 * Cloudflare binds the clearance cookie to the browser that solved the challenge, so a challenged
 * route is served from inside that same WebView: the page issues the fetch with its own identity
 * and posts the bytes back through the bridge.
 */
internal fun buildFetchScript(id: String, url: String, headers: Map<String, String>): String {
    val headerObject = JSONObject()
    headers.forEach { (name, value) -> headerObject.put(name, value) }
    return """
(function(){
  var requestId = ${JSONObject.quote(id)};
  var controller = new AbortController();
  var timer = setTimeout(function(){ controller.abort(); }, 20000);
  fetch(${JSONObject.quote(url)}, {
    credentials: "include",
    cache: "no-store",
    headers: $headerObject,
    signal: controller.signal
  }).then(function(response){
    return response.arrayBuffer().then(function(buffer){
      var bytes = new Uint8Array(buffer);
      var binary = "";
      var chunk = 0x8000;
      for (var index = 0; index < bytes.length; index += chunk) {
        binary += String.fromCharCode.apply(null, bytes.subarray(index, index + chunk));
      }
      var headers = [];
      response.headers.forEach(function(value, name){ headers.push([name, value]); });
      window.$BRIDGE_NAME.post(JSON.stringify({
        id: requestId,
        status: response.status,
        url: response.url,
        headers: headers,
        body: btoa(binary)
      }));
    });
  }).catch(function(error){
    window.$BRIDGE_NAME.post(JSON.stringify({ id: requestId, error: String(error) }));
  }).then(function(){ clearTimeout(timer); });
})()
""".trimIndent()
}

/** Decodes one bridge payload; null when the fetch failed or the message is not a response. */
internal fun parseFetchPayload(message: String): FetchedPage? = runCatching {
    val json = JSONObject(message)
    if (json.has("error")) return@runCatching null
    val status = json.getInt("status")
    val url = json.optString("url")
    val headers = linkedMapOf<String, MutableList<String>>()
    val rawHeaders = json.optJSONArray("headers") ?: JSONArray()
    for (index in 0 until rawHeaders.length()) {
        val pair = rawHeaders.optJSONArray(index) ?: continue
        val name = pair.optString(0)
        if (name.isEmpty()) continue
        headers.getOrPut(name) { mutableListOf() }.add(pair.optString(1))
    }
    val body = if (json.isNull("body")) ByteArray(0)
    else Base64.decode(json.optString("body"), Base64.DEFAULT)
    FetchedPage(status, url, headers, body)
}.getOrNull()

internal const val BRIDGE_NAME = "NxtFetch"

/**
 * Matches bridge callbacks to pending fetch continuations; the page posts each response from the
 * solved WebView, so the waiters live outside the clearance class.
 */
internal class NewxtoonFetchBridge(private val main: Handler) {
    private val waiters = ConcurrentHashMap<String, CancellableContinuation<FetchedPage?>>()

    suspend fun fetch(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): FetchedPage? = withTimeoutOrNull(timeoutMillis) {
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation: CancellableContinuation<FetchedPage?> ->
            waiters[id] = continuation
            continuation.invokeOnCancellation { waiters.remove(id) }
            main.post {
                if (!continuation.isActive) return@post
                runCatching { view.evaluateJavascript(buildFetchScript(id, url, headers), null) }
                    .onFailure {
                        waiters.remove(id)?.let { waiter -> if (waiter.isActive) waiter.resume(null) }
                    }
            }
        }
    }

    fun onMessage(message: String) {
        val id = runCatching { JSONObject(message).optString("id") }.getOrNull()
        if (id.isNullOrEmpty()) return
        val payload = parseFetchPayload(message)
        waiters.remove(id)?.let { waiter -> if (waiter.isActive) waiter.resume(payload) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun post(message: String) = onMessage(message)
    }
}
