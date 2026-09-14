package ml.melun.mangaview.source.ntk

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import org.json.JSONObject

/**
 * Native provider flight for the protected image manifest, used when the isolated WebView cannot
 * prove authorization. It mirrors the page's own exchange: installation identity cookies, the ad
 * challenge, the per-session nv credential, then the HMAC proof over the signed viewer token. The
 * provider binds the image API to the token and nv session alone, so the manifest arrives without
 * any browser involvement; callers keep the WebView capture as their fallback.
 */
class NtkNativeManifestClient(
    private val transport: SourceTransport,
    private val userAgent: String,
) {
    suspend fun capture(
        origin: URI,
        document: NtkAccessDocument,
        identity: NtkBrowserIdentity,
    ): NtkEngineAuthorization {
        val descriptor = requireNotNull(document.descriptor) {
            "NTK native manifest requires a protected document"
        }
        val base = URI(origin.scheme, origin.authority, null, null, null).toString()
        val episodePath = document.episodeId.remoteKey
        val referer = base + episodePath
        val cookies = linkedMapOf(
            "ntk_fp" to identity.fingerprint,
            "ntk_pid" to identity.persistentId,
        )
        challenge(base, episodePath, referer, cookies)
        val session = session(base, episodePath, referer, cookies)
        val nonce = randomToken(NONCE_BYTES)
        val manifest = exchange(
            origin = base,
            url = base + descriptor.apiPath,
            json = JSONObject()
                .put("workId", descriptor.workId)
                .put("episodeId", descriptor.episodeId)
                .put("token", descriptor.token)
                .put("nonce", nonce)
                .put("proof", proof(session, descriptor.token, nonce))
                .toString(),
            referer = referer,
            cookies = cookies,
            extraHeaders = mapOf(
                "x-images-client" to "viewer-v1",
                "x-nv-session" to session,
            ),
            priority = PageFetchPriority.FOCUS,
        )
        require(JSONObject(manifest).optBoolean("ok", false)) {
            "NTK native image API rejected the identity proof"
        }
        val observedAt = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        runCatching {
            Log.i(TAG, "phase=native-manifest-ready episode=${document.episodeId} bytes=${manifest.length}")
        }
        return authorization(base, descriptor, document, manifest, observedAt)
    }

    private fun authorization(
        base: String,
        descriptor: NtkViewerDescriptor,
        document: NtkAccessDocument,
        manifest: String,
        observedAt: Long,
    ): NtkEngineAuthorization {
        val payload = JSONObject(manifest)
            .put("endpoint", descriptor.apiPath)
            .put("responseUrl", base + descriptor.apiPath)
            .put("responseContentType", "application/json")
            .put("requestMethod", "POST")
            .put("requestContentType", "application/json")
            .put("requestWorkId", descriptor.workId)
            .put("requestEpisodeId", descriptor.episodeId)
            .put("requestToken", descriptor.token)
        return NtkEngineAuthorization(
            payload = payload.toString(),
            episodeId = document.episodeId,
            documentSha256 = document.sourceDocument.sha256,
            documentReplaySha256 = document.sourceDocument.replaySha256,
            authEpoch = document.authEpoch,
            requestId = nextRequestId.incrementAndGet(),
            ackObservedNanos = observedAt,
            manifestObservedNanos = observedAt,
            documentRetiredNanos = observedAt,
        )
    }

    private suspend fun challenge(
        base: String,
        episodePath: String,
        referer: String,
        cookies: MutableMap<String, String>,
    ) {
        exchange(
            origin = base,
            url = "$base/api/ad/challenge",
            json = JSONObject().put("path", episodePath).put("force", false).toString(),
            referer = referer,
            cookies = cookies,
            extraHeaders = emptyMap(),
            priority = PageFetchPriority.NORMAL,
        )
    }

    private suspend fun session(
        base: String,
        episodePath: String,
        referer: String,
        cookies: MutableMap<String, String>,
    ): String {
        cookies["nv"]?.takeIf(::validSession)?.let { return it }
        val issued = exchange(
            origin = base,
            url = "$base/api/nv-issue",
            json = null,
            referer = referer,
            cookies = cookies,
            extraHeaders = mapOf("Content-Type" to "application/json"),
            priority = PageFetchPriority.NORMAL,
        )
        val granted = cookies["nv"]?.takeIf(::validSession)
            ?: runCatching { JSONObject(issued).optString("session") }.getOrNull()?.takeIf(::validSession)
        requireNotNull(granted) { "NTK native flight did not obtain an nv session" }
        cookies["nv"] = granted
        return granted
    }

    private suspend fun exchange(
        origin: String,
        url: String,
        json: String?,
        referer: String,
        cookies: MutableMap<String, String>,
        extraHeaders: Map<String, String>,
        priority: PageFetchPriority,
    ): String {
        val headers = buildMap {
            put("User-Agent", userAgent)
            put("Accept", "application/json, text/plain, */*")
            put("Origin", origin)
            put("Referer", referer)
            putAll(extraHeaders)
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        val response = transport.execute(SourceRequest(
            url = url,
            method = SourceHttpMethod.POST,
            headers = headers,
            body = json?.toByteArray(Charsets.UTF_8),
            bodyMediaType = if (json != null) "application/json" else null,
            totalTimeoutMillis = TIMEOUT_MILLIS,
            priority = priority,
        ))
        return try {
            absorbCookies(response, cookies)
            if (response.statusCode !in 200..299) {
                throw IOException("NTK native flight failed with ${response.statusCode}")
            }
            response.readBytes(RESPONSE_LIMIT).toString(Charsets.UTF_8)
        } finally {
            response.close()
        }
    }

    private fun absorbCookies(response: SourceResponse, cookies: MutableMap<String, String>) {
        response.headers.forEach { (name, values) ->
            if (!name.equals("Set-Cookie", ignoreCase = true)) return@forEach
            for (value in values) {
                val pair = value.substringBefore(';')
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                cookies[pair.substring(0, separator).trim()] = pair.substring(separator + 1).trim()
            }
        }
    }

    private fun validSession(value: String): Boolean = value.substringBefore('.').length >= MIN_SESSION_SEGMENT

    private fun randomToken(size: Int): String {
        val raw = ByteArray(size)
        random.nextBytes(raw)
        return base64Url(raw)
    }

    private fun proof(session: String, token: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(session.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal("$token.$nonce".toByteArray(Charsets.UTF_8)))
    }

    private fun base64Url(raw: ByteArray): String =
        Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private val random = SecureRandom()
    private val nextRequestId = AtomicLong(1L)

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val RESPONSE_LIMIT = 4 * 1_024 * 1_024
        const val NONCE_BYTES = 24
        const val MIN_SESSION_SEGMENT = 40
        const val TAG = "NtkNative"
    }
}
