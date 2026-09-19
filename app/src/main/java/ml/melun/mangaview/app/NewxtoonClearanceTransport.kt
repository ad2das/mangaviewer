package ml.melun.mangaview.app

import java.io.Closeable
import kotlinx.coroutines.delay
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import android.util.Log

private const val TAG = "NewxtoonClearance"

/**
 * Retries a challenged newxtoon request after the WebView clears the origin. Cloudflare binds the
 * clearance cookie to the solving browser, so when the HTTP route keeps drawing a challenge the
 * request is replayed inside that WebView, whose identity the cookie does belong to.
 */
internal class NewxtoonClearanceTransport(
    private val inner: SourceTransport,
    private val origin: String,
    private val solve: suspend () -> Boolean,
    private val solveFresh: suspend () -> Boolean = solve,
    private val fetchPage: suspend (String, Map<String, String>) -> FetchedPage? = { _, _ -> null },
) : SourceTransport by inner, Closeable {
    /** Set once the WebView route has proven it serves this origin; phones keep using HTTP. */
    @Volatile
    private var webViewRoute = false

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val sameOrigin = request.url.startsWith(origin)
        val replayable = sameOrigin && request.method == SourceHttpMethod.GET
        if (replayable && webViewRoute) {
            val replayed = webViewResponse(request)
            if (replayed != null && replayed.statusCode != 403) return replayed
            replayed?.close()
            webViewRoute = false
        }
        val response = inner.execute(request)
        if (response.statusCode != 403 || !sameOrigin) return response
        Log.i(TAG, "challenged ${response.statusCode} ${request.url} mitigated=${response.header("cf-mitigated")}")
        response.close()
        val cleared = solve()
        Log.i(TAG, "solve=$cleared retrying ${request.url}")
        var retried = retryAfterChallenge(request)
        if (retried.isChallenge() && replayable) {
            val replayed = webViewResponse(request)
            if (replayed != null && replayed.statusCode != 403) {
                Log.i(TAG, "served ${request.url} from the clearance webview")
                retried.close()
                webViewRoute = true
                retried = replayed
            } else {
                replayed?.close()
            }
        }
        Log.i(TAG, "retry status=${retried.statusCode} mitigated=${retried.header("cf-mitigated")} server=${retried.header("server")} ray=${retried.header("cf-ray")}")
        return retried
    }

    // The edge occasionally keeps challenging the first request after a fresh solve; give it a
    // moment, and if it still refuses, treat the cached clearance as stale and solve again.
    private suspend fun retryAfterChallenge(request: SourceRequest): SourceResponse {
        var retried = inner.execute(request)
        for (attempt in 1..2) {
            if (!retried.isChallenge()) break
            Log.i(TAG, "retry still challenged; attempt=$attempt")
            retried.close()
            delay(1_500L * attempt)
            if (attempt == 2) solveFresh()
            retried = inner.execute(request)
        }
        return retried
    }

    private fun SourceResponse.isChallenge(): Boolean =
        statusCode == 403 && header("cf-mitigated")?.contains("challenge") == true

    private suspend fun webViewResponse(request: SourceRequest): SourceResponse? {
        val page = fetchPage(request.url, request.headers) ?: return null
        return SourceResponse(
            statusCode = page.statusCode,
            finalUrl = page.finalUrl.ifBlank { request.url },
            headers = page.headers,
            body = ByteArrayPageStream(page.body),
            contentLength = page.body.size.toLong(),
            contentType = page.header("content-type"),
        )
    }

    private fun SourceResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")

    override fun close() {
        (inner as? AutoCloseable)?.close()
    }
}

/** A fully buffered response body; the WebView fetch already delivered every byte. */
private class ByteArrayPageStream(private val bytes: ByteArray) : PageByteStream {
    private var offset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (this.offset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - this.offset)
        bytes.copyInto(destination, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    override fun close() = Unit
}
