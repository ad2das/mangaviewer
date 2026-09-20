package ml.melun.mangaview.app

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import android.util.Log

private const val TAG = "NewxtoonClearance"
private const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024

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
    private val solvedViewReady: () -> Boolean = { false },
    private val clearanceVerified: () -> Boolean = { false },
    private val onReplayRejected: () -> Unit = {},
    private val documents: NewxtoonDocumentCache? = null,
    private val refreshScope: CoroutineScope? = null,
) : SourceTransport by inner, Closeable {
    /** Set once the WebView route has proven it serves this origin; phones keep using HTTP. */
    @Volatile
    private var webViewRoute = false

    /** Set once plain HTTP serves a cleared request — one round trip, no bridge, forever after. */
    @Volatile
    private var directRoute = false

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val sameOrigin = request.url.startsWith(origin)
        val replayable = sameOrigin && request.method == SourceHttpMethod.GET
        val cache = documents
        // Documents answer from the disk cache first; a stale copy still answers instantly and
        // one background refresh keeps the next read current. Nothing else may pay the WebView
        // bridge twice for the same URL, so misses are single-flight per URL.
        if (cache != null && replayable) {
            cache.read(request.url)?.let { cached ->
                if (!cached.isFresh()) scheduleRefresh(request)
                return cached.response(request.url)
            }
            return cache.synchronizedOn(request.url) {
                cache.read(request.url)?.let { cached ->
                    if (!cached.isFresh()) scheduleRefresh(request)
                    cached.response(request.url)
                } ?: fetchAndCache(request)
            }
        }
        return fetchFresh(request)
    }

    /** Revalidates a stale entry without holding its reader; failures keep the old copy. */
    private fun scheduleRefresh(request: SourceRequest) {
        val cache = documents ?: return
        val scope = refreshScope ?: return
        scope.launch {
            runCatching {
                cache.synchronizedOn(request.url) {
                    if (cache.read(request.url)?.isFresh() == false) fetchAndCache(request)?.close()
                }
            }.onFailure { Log.d(TAG, "refresh failed for ${request.url}") }
        }
    }

    /** Fetches fresh bytes and stores a served document so the next read skips the bridge. */
    private suspend fun fetchAndCache(request: SourceRequest): SourceResponse {
        val response = fetchFresh(request)
        val cache = documents ?: return response
        if (response.statusCode != 200) return response
        val bytes = try {
            response.readBytes(MAX_DOCUMENT_BYTES)
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
        response.close()
        cache.write(request.url, response, bytes, newxtoonDocumentFreshFor(request.url))
        return SourceResponse(
            statusCode = response.statusCode,
            finalUrl = response.finalUrl.ifBlank { request.url },
            headers = response.headers,
            body = ByteArrayPageStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = response.contentType,
        )
    }

    private suspend fun fetchFresh(request: SourceRequest): SourceResponse {
        val sameOrigin = request.url.startsWith(origin)
        val replayable = sameOrigin && request.method == SourceHttpMethod.GET
        if (replayable) {
            val preferred = tryBrowserRoutes(request)
            if (preferred != null) return preferred
        }
        val response = inner.execute(request)
        if (response.statusCode != 403 || !sameOrigin) return response
        Log.i(TAG, "challenged ${response.statusCode} ${request.url} mitigated=${response.header("cf-mitigated")}")
        response.close()
        return solveThenRetry(request, replayable)
    }

    /**
     * Every established browser route, cheapest first: a proven direct route, one probe with a
     * verified clearance, then the solved WebView itself. Null when none of them can serve.
     */
    private suspend fun tryBrowserRoutes(request: SourceRequest): SourceResponse? {
        // Once plain HTTP serves a cleared request it stays the fastest route: a bare round trip
        // with no bridge, no view, no replay. A challenge answer demotes it back below replay.
        if (directRoute) {
            val direct = inner.execute(request)
            if (!direct.isChallenge()) {
                Log.i(TAG, "served ${request.url} natively status=${direct.statusCode} (direct route)")
                return direct
            }
            direct.close()
            directRoute = false
            replay(request)?.let { webViewRoute = true; return it }
        }
        // A verified clearance with no solved browser yet probes the direct route once — the
        // cookie alone may already serve it, which is cheaper than standing the view up.
        if (clearanceVerified() && !webViewRoute && !solvedViewReady()) {
            val probed = inner.execute(request)
            if (!probed.isChallenge()) {
                directRoute = true
                Log.i(TAG, "served ${request.url} natively status=${probed.statusCode} (direct probe)")
                return probed
            }
            probed.close()
        }
        // A live solved WebView — or a clearance already proven to serve — means replaying inside
        // the browser whose identity owns the cookie beats paying another refused request.
        if (webViewRoute || solvedViewReady() || clearanceVerified()) {
            val replayed = replay(request)
            if (replayed != null) return replayed
            webViewRoute = false
        }
        return null
    }

    private suspend fun solveThenRetry(request: SourceRequest, replayable: Boolean): SourceResponse {
        val cleared = solve()
        Log.i(TAG, "solve=$cleared retrying ${request.url}")
        // The solved browser is the one identity the clearance belongs to, so its replay comes
        // before any further plain round trip; plain stays as the fallback for loose bindings.
        if (replayable) {
            replay(request)?.let { webViewRoute = true; return it }
        }
        var retried = inner.execute(request)
        if (replayable && retried.statusCode != 403) directRoute = true
        retried = replayInstead(request, retried, replayable)
        if (retried.isChallenge()) {
            retried.close()
            retried = retryAfterChallenge(request)
            retried = replayInstead(request, retried, replayable)
        }
        Log.i(TAG, "retry status=${retried.statusCode} mitigated=${retried.header("cf-mitigated")} server=${retried.header("server")} ray=${retried.header("cf-ray")}")
        return retried
    }

    /** Swaps a challenged response for one served by the solved browser; unchanged when it cannot. */
    private suspend fun replayInstead(
        request: SourceRequest,
        response: SourceResponse,
        replayable: Boolean,
    ): SourceResponse {
        if (!replayable || !response.isChallenge()) return response
        val replayed = replay(request) ?: return response
        response.close()
        webViewRoute = true
        return replayed
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

    private suspend fun replay(request: SourceRequest): SourceResponse? {
        val page = fetchPage(request.url, request.headers) ?: return null
        if (page.statusCode == 403) {
            // The browser that owns the clearance was refused too: the clearance is dead and
            // every further replay from it would burn the same round trip for nothing.
            Log.i(TAG, "replay challenged ${request.url} mitigated=${page.header("cf-mitigated")}")
            onReplayRejected()
            return null
        }
        Log.i(TAG, "served ${request.url} from the clearance webview")
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
internal class ByteArrayPageStream(private val bytes: ByteArray) : PageByteStream {
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
