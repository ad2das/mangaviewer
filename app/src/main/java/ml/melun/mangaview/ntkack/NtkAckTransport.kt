package ml.melun.mangaview.ntkack

import android.content.Context
import android.util.Log
import ml.melun.mangaview.MainApplication
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** Proof-critical HTTPS transport with a closed endpoint allowlist and observable idleness. */
class NtkAckTransport(
    context: Context,
    private val origin: String,
    private val episodePath: String,
    private val userAgent: String,
    seedCookies: List<NtkAckCookie>,
    private val deadlineElapsedNanos: Long,
) {
    data class Result(
        val requestUrl: String,
        val finalUrl: String,
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, List<String>>,
        /** Parsed grants captured from this response only, never from a cumulative cookie jar. */
        val responseGrantCookies: List<NtkAckCookie>,
    ) {
        val bodyText: String get() = body.toString(Charsets.UTF_8)
    }

    private enum class Purpose {
        CHALLENGE, METRIC, CANARY, ACK, REGISTER, GUARD_JS, GUARD_WASM, EXACT_IMAGES
    }

    private val originUrl = origin.toHttpUrl()
    private val cookieJar = EvidenceCookieJar(originUrl, seedCookies)
    // The bound service is deliberately in the app process. Deriving from the production client
    // preserves the proof-specific cookie jar and no-retry policy while reusing only the normal
    // user-flow connection pool/DNS state already established by detail navigation. No request is
    // created until this click-owned transport executes an allowlisted operation.
    private val client = (MainApplication.getHttpClient().client ?: OkHttpClient()).newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    // Shares the ACK client's dispatcher/pool but preserves the exact Cookie header assembled
    // from this click's document response in the main process.
    private val exactClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()
    private val calls = NtkAckCancellationRegistry<Call>(Call::cancel)
    private val exactCalls = NtkAckCancellationRegistry<Call>(Call::cancel)
    private val idleCallbacks = ArrayList<() -> Unit>()
    @Volatile
    private var metricUrls: Set<String> = emptySet()

    val activeCallCount: Int get() = calls.activeCount + exactCalls.activeCount

    fun authorizeMetricUrls(urls: Collection<String>) {
        require(urls.isNotEmpty())
        val normalized = urls.map { normalizeMetricUrl(it) }.toSet()
        require(normalized.size == urls.size) { "Duplicate metric URL" }
        metricUrls = normalized
    }

    fun postChallenge(body: ByteArray): Result = execute(Purpose.CHALLENGE, "POST", "$origin/api/ad/challenge", body)
    fun postCanary(body: ByteArray): Result = execute(Purpose.CANARY, "POST", "$origin/api/ad/canary", body)
    fun postAck(body: ByteArray, headers: Map<String, String>): Result =
        execute(Purpose.ACK, "POST", "$origin/api/ad/ack", body, headers)
    fun registerKey(body: ByteArray): Result = execute(Purpose.REGISTER, "POST", "$origin/api/client-key/register", body)
    fun getMetric(url: String): Result = execute(Purpose.METRIC, "GET", normalizeMetricUrl(url), byteArrayOf())

    fun postExact(request: NtkAckSignRequest, signature: NtkAckSignature): Result {
        val body = request.bodyBytes
        require(signature.origin == origin && signature.episodePath == episodePath)
        val expectedEndpoint = if (episodePath.startsWith("/manhwa/")) {
            "/api/manhwa-images"
        } else {
            "/api/webtoon-images"
        }
        require(signature.method == "POST" && signature.endpoint == expectedEndpoint)
        require(signature.bodyDigestSha256 == NtkAckProofCodec.sha256Hex(body))
        val supplied = LinkedHashMap<String, String>()
        request.requestHeaders.forEach { header ->
            val name = header.name.trim().lowercase()
            require(name in EXACT_HEADER_ALLOWLIST && header.values.size == 1)
            require(supplied.put(name, header.values.single()) == null) {
                "Duplicate exact header: $name"
            }
        }
        require(supplied["user-agent"] == userAgent)
        require(supplied["origin"] == origin)
        require(supplied["referer"] == origin + episodePath)
        require(supplied["cookie"].orEmpty().isNotBlank())
        require(supplied.keys.none { it.startsWith("x-ntk-") })
        supplied["x-ntk-key-id"] = signature.requestKeyId
        supplied["x-ntk-ts"] = signature.timestamp
        supplied["x-ntk-nonce"] = signature.nonce
        supplied["x-ntk-sig"] = signature.signatureValue
        return execute(
            Purpose.EXACT_IMAGES,
            "POST",
            origin + expectedEndpoint,
            body,
            supplied,
        )
    }

    fun getGuardJavascript(version: String): Result =
        getGuard(Purpose.GUARD_JS, "/api/ad/guard-js", "/wasm/ad-guard/ad_guard.js", version)

    fun getGuardWasm(version: String): Result =
        getGuard(Purpose.GUARD_WASM, "/api/ad/guard-wasm", "/wasm/ad-guard/ad_guard_bg.wasm", version)

    fun cookieGrants(): List<NtkAckCookie> = cookieJar.grants()

    fun cancelAll() {
        calls.cancelAll()
        exactCalls.cancelAll()
        notifyIdleIfNeeded()
    }

    /** Stops proof-only work at the renderer quiescence boundary without closing exact H2 use. */
    fun quiesceProofCalls() {
        calls.cancelAll()
        notifyIdleIfNeeded()
    }

    fun whenIdle(callback: () -> Unit) {
        synchronized(idleCallbacks) {
            // Quiescence closes the call registry before it observes idleness, so no new call can
            // race this zero check after cancelAll().
            if (calls.activeCount == 0) callback() else idleCallbacks += callback
        }
    }

    fun clearStrictFreshCookies() = cookieJar.clearStrictFresh()

    private fun getGuard(purpose: Purpose, endpoint: String, redirectPath: String, version: String): Result {
        require(version.isNotBlank())
        val encodedVersion = java.net.URLEncoder.encode(version, "UTF-8")

        // The guard API is a permanent, same-origin redirect to an immutable versioned asset.
        // Starting with that allowlisted target removes one cold RTT for both JS and WASM. This
        // transport is created only by the click-owned isolated ACK flight; it is not a warm-up
        // and it cannot request viewer images (validateRequest keeps those paths forbidden).
        val direct = execute(
            purpose,
            "GET",
            "$origin$redirectPath?v=$encodedVersion",
            byteArrayOf(),
            allowGuardStatic = true,
        )
        if (direct.status == 200 && direct.body.isNotEmpty()) {
            Log.d(TAG, "ack_guard_direct_static path=$redirectPath,version=$version")
            return direct
        }

        // Keep the API endpoint as a fail-closed compatibility discovery path if a deployment
        // changes the static route. A successful redirect must still resolve to the exact closed
        // allowlist entry and preserve the requested version.
        Log.d(
            TAG,
            "ack_guard_direct_static_fallback path=$redirectPath,version=$version," +
                "status=${direct.status},bytes=${direct.body.size}",
        )
        val requested = execute(
            purpose,
            "GET",
            "$origin$endpoint?v=$encodedVersion",
            byteArrayOf(),
        )
        if (requested.status !in setOf(301, 302, 307, 308)) return requested
        val location = requested.headers.entries.firstOrNull { it.key.equals("location", true) }
            ?.value?.firstOrNull().orEmpty()
        val redirected = originUrl.resolve(location) ?: error("Invalid guard redirect")
        require(redirected.scheme == originUrl.scheme && redirected.host == originUrl.host)
        require(redirected.encodedPath == redirectPath) { "Guard redirect escaped allowlist" }
        require(redirected.queryParameter("v").orEmpty() == version) { "Guard redirect version mismatch" }
        return execute(purpose, "GET", redirected.toString(), byteArrayOf(), allowGuardStatic = true)
    }

    private fun execute(
        purpose: Purpose,
        method: String,
        url: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
        allowGuardStatic: Boolean = false,
    ): Result {
        val registry = if (purpose == Purpose.EXACT_IMAGES) exactCalls else calls
        check(!registry.isCancelled) { "ACK transport is cancelled" }
        val requestUrl = url.toHttpUrl()
        validateRequest(purpose, method, requestUrl, allowGuardStatic)
        val remaining = deadlineElapsedNanos - android.os.SystemClock.elapsedRealtimeNanos()
        check(remaining > 0L) { "ACK transport deadline expired" }
        val builder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", userAgent)
            .header("Origin", origin)
            .header("Referer", origin + episodePath)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        if (method == "POST") {
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
        } else {
            builder.get().header(
                "Accept",
                if (purpose == Purpose.METRIC) "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                else "application/javascript,application/wasm,*/*",
            )
        }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()

        val networkClient = if (purpose == Purpose.EXACT_IMAGES) exactClient else client
        val call = networkClient.newCall(request)
        call.timeout().timeout(remaining.coerceAtMost(TimeUnit.SECONDS.toNanos(8)), TimeUnit.NANOSECONDS)
        // Registration and cancellation share one atomic boundary. A call is either admitted and
        // included in cancelAll()'s snapshot, or rejected before call.execute() can begin.
        registry.register(call)
        try {
            call.execute().use { response ->
                val bytes = response.body?.bytes() ?: byteArrayOf()
                val responseGrants = cookieJar.captureSetCookieEvidence(response)
                return Result(
                    request.url.toString(),
                    response.request.url.toString(),
                    response.code,
                    bytes,
                    response.headers.toMultimap(),
                    responseGrants,
                )
            }
        } finally {
            registry.unregister(call)
            notifyIdleIfNeeded()
        }
    }

    private fun validateRequest(purpose: Purpose, method: String, url: HttpUrl, allowGuardStatic: Boolean) {
        require(url.scheme == "https" && url.host == originUrl.host && url.port == originUrl.port)
        val path = url.encodedPath
        val valid = when (purpose) {
            Purpose.CHALLENGE -> method == "POST" && path == "/api/ad/challenge"
            Purpose.CANARY -> method == "POST" && path == "/api/ad/canary"
            Purpose.ACK -> method == "POST" && path == "/api/ad/ack"
            Purpose.REGISTER -> method == "POST" && path == "/api/client-key/register"
            Purpose.METRIC -> method == "GET" && url.toString() in metricUrls && path == "/api/m/i"
            Purpose.GUARD_JS -> method == "GET" && (path == "/api/ad/guard-js" || allowGuardStatic && path == "/wasm/ad-guard/ad_guard.js")
            Purpose.GUARD_WASM -> method == "GET" && (path == "/api/ad/guard-wasm" || allowGuardStatic && path == "/wasm/ad-guard/ad_guard_bg.wasm")
            Purpose.EXACT_IMAGES -> method == "POST" && path == if (
                episodePath.startsWith("/manhwa/")
            ) "/api/manhwa-images" else "/api/webtoon-images"
        }
        require(valid) { "Forbidden ACK transport request: $method $url" }
        if (purpose != Purpose.EXACT_IMAGES) {
            require(path !in setOf("/api/manhwa-images", "/api/webtoon-images", "/api/manga-images"))
        }
    }

    private fun normalizeMetricUrl(raw: String): String {
        val resolved = originUrl.resolve(raw) ?: throw IllegalArgumentException("Invalid metric URL")
        require(resolved.scheme == "https" && resolved.host == originUrl.host && resolved.port == originUrl.port)
        require(resolved.encodedPath == "/api/m/i" && resolved.encodedQuery?.isNotBlank() == true)
        return resolved.toString()
    }

    private fun notifyIdleIfNeeded() {
        if (calls.activeCount != 0) return
        val callbacks = synchronized(idleCallbacks) {
            idleCallbacks.toList().also { idleCallbacks.clear() }
        }
        callbacks.forEach { it() }
    }

    private class EvidenceCookieJar(origin: HttpUrl, seeds: List<NtkAckCookie>) : CookieJar {
        private val cookies = LinkedHashMap<String, Cookie>()
        private val grants = LinkedHashMap<String, NtkAckCookie>()

        init {
            seeds.forEach { seed ->
                val builder = Cookie.Builder()
                    .name(seed.name)
                    .value(seed.value)
                    .path(seed.path.ifBlank { "/" })
                val domain = seed.domain.trim().removePrefix(".").ifBlank { origin.host }
                builder.domain(domain)
                if (seed.secure) builder.secure()
                if (seed.expiresAtEpochMs > 0L) builder.expiresAt(seed.expiresAtEpochMs)
                cookies[seed.name] = builder.build()
            }
        }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                if (cookie.expiresAt <= System.currentTimeMillis()) this.cookies.remove(cookie.name)
                else this.cookies[cookie.name] = cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }

        @Synchronized
        fun captureSetCookieEvidence(response: Response): List<NtkAckCookie> {
            val responseGrants = ArrayList<NtkAckCookie>()
            response.headers.values("Set-Cookie").forEach { raw ->
                val parsed = Cookie.parse(response.request.url, raw) ?: return@forEach
                if (parsed.name !in NtkAckCookieBoundary.grantNames) return@forEach
                val evidence = NtkAckCookie(
                    parsed.name,
                    parsed.value,
                    response.request.url.toString(),
                    parsed.domain,
                    parsed.path,
                    parsed.secure,
                    parsed.expiresAt,
                    NtkAckProofCodec.sha256Utf8(raw),
                )
                grants[parsed.name] = evidence
                responseGrants += evidence
            }
            return responseGrants.map(NtkAckCookie::copy)
        }

        @Synchronized
        fun grants(): List<NtkAckCookie> = grants.values.map(NtkAckCookie::copy)

        @Synchronized
        fun clearStrictFresh() {
            NtkAckCookieBoundary.strictFreshNames.forEach {
                cookies.remove(it)
                grants.remove(it)
            }
        }
    }

    private companion object {
        const val TAG = "NtkAckTransport"
        val EXACT_HEADER_ALLOWLIST = setOf(
            "user-agent", "content-type", "accept", "x-images-client", "origin", "referer",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "priority", "accept-language",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "cookie", "x-nv-session",
        )
    }
}
