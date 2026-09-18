package ml.melun.mangaview.data.network

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/**
 * The provider serves cover artwork from image CDNs whose certificate chain the platform trust store
 * cannot build, so the plain transport rejects it with "Trust anchor for certification path not
 * found". Only the hosts matched here are ever handed to a client that skips chain verification.
 */
object ProviderImageTrust {
    private val RELAXED_HOSTS = Regex("""^aws-cdn\d+\.site$""")
    private val NUMBERED_HOSTS = Regex("""^aws-cdn(\d+)\.site$""")
    private const val MIRROR_HOST_COUNT = 12

    fun requiresRelaxedTls(url: String): Boolean =
        runCatching { URI(url).host }.getOrNull()?.let(RELAXED_HOSTS::matches) == true

    /**
     * The numbered CDN domains front one artwork bucket. A court-ordered block can silence a
     * single number with a connection reset or a warning redirect while its siblings keep
     * serving the same object, so every other number is a fallback address for the same bytes.
     */
    fun mirrorCandidates(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val original = NUMBERED_HOSTS.matchEntire(uri.host?.lowercase() ?: return emptyList())
            ?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
        return (1..MIRROR_HOST_COUNT).filter { it != original }.mapNotNull { number ->
            runCatching {
                URI(uri.scheme, uri.userInfo, "aws-cdn$number.site", uri.port, uri.path, uri.query, uri.fragment)
                    .toString()
            }.getOrNull()
        }
    }

    fun trustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            Log.w("ProviderImageTrust", "Accepted a provider image certificate without chain verification")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun socketFactory(): SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager()), SecureRandom())
    }.socketFactory
}

/** Routes the provider's image CDNs through [relaxed] and every other host through [delegate]. */
class ProviderImageTransport(
    private val delegate: SourceTransport,
    private val relaxed: SourceTransport,
    private val nowNanos: () -> Long = System::nanoTime,
) : SourceTransport by delegate, Closeable {
    private val workingMirrors = ConcurrentHashMap<String, String>()

    override suspend fun execute(request: SourceRequest): SourceResponse =
        route(request, relaxed::execute, delegate::execute)

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse =
        route(request, relaxed::executeOnFreshRoute, delegate::executeOnFreshRoute)

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        route(request, relaxed::executeOnAlternateRoute, delegate::executeOnAlternateRoute)

    private suspend fun route(
        request: SourceRequest,
        relaxedRoute: suspend (SourceRequest) -> SourceResponse,
        delegateRoute: suspend (SourceRequest) -> SourceResponse,
    ): SourceResponse {
        if (!ProviderImageTrust.requiresRelaxedTls(request.url)) return delegateRoute(request)
        val mirrors = ProviderImageTrust.mirrorCandidates(request.url)
        if (mirrors.isEmpty()) return relaxedRoute(request)
        return fetchWithMirrors(request, mirrors, relaxedRoute)
    }

    /**
     * A blocked host answers with a reset or with the regulator's warning page, so a candidate
     * only counts when it returns an actual image. The first mirror that does is remembered and
     * tried before the blocked host on later requests.
     */
    private suspend fun fetchWithMirrors(
        request: SourceRequest,
        mirrors: List<String>,
        relaxedRoute: suspend (SourceRequest) -> SourceResponse,
    ): SourceResponse {
        val originalHost = host(request.url) ?: return relaxedRoute(request)
        val remembered = workingMirrors[originalHost]
            ?.let { working -> mirrors.firstOrNull { host(it) == working } }
        val candidates = buildList {
            remembered?.let(::add)
            add(request.url)
            mirrors.forEach { mirror -> if (mirror != remembered) add(mirror) }
        }
        val started = nowNanos()
        var originalResponse: SourceResponse? = null
        var firstFailure: IOException? = null
        try {
            for (candidate in candidates) {
                // One request budget covers the whole sweep: a network where every candidate
                // stalls must not multiply the caller's timeout by the number of mirrors.
                val remaining = request.totalTimeoutMillis - (nowNanos() - started) / 1_000_000
                if (remaining <= 0) break
                val response = try {
                    relaxedRoute(request.copy(url = candidate, totalTimeoutMillis = remaining))
                } catch (failure: IOException) {
                    if (firstFailure == null) firstFailure = failure
                    if (candidate == remembered) workingMirrors.remove(originalHost)
                    continue
                }
                if (response.isUsableArtwork()) {
                    originalResponse?.close()
                    if (candidate != request.url) workingMirrors[originalHost] = host(candidate) ?: originalHost
                    return response
                }
                if (candidate == request.url) originalResponse = response else response.close()
                if (candidate == remembered) workingMirrors.remove(originalHost)
            }
        } catch (failure: Throwable) {
            originalResponse?.close()
            throw failure
        }
        originalResponse?.let { return it }
        throw firstFailure ?: IOException("Provider artwork is unavailable")
    }

    private fun SourceResponse.isUsableArtwork(): Boolean {
        if (statusCode !in 200..299) return false
        val type = contentType ?: return true
        return type.startsWith("image/", ignoreCase = true)
    }

    private fun host(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** The delegate is owned by its creator; this wrapper only owns the relaxed client. */
    override fun close() {
        workingMirrors.clear()
        (relaxed as? Closeable)?.close()
    }
}
