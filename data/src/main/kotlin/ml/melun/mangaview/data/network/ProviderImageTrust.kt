package ml.melun.mangaview.data.network

import android.util.Log
import java.io.Closeable
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
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

    fun requiresRelaxedTls(url: String): Boolean =
        runCatching { URI(url).host }.getOrNull()?.let(RELAXED_HOSTS::matches) == true

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
) : SourceTransport by delegate, Closeable {
    override suspend fun execute(request: SourceRequest): SourceResponse =
        if (ProviderImageTrust.requiresRelaxedTls(request.url)) relaxed.execute(request) else delegate.execute(request)

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse =
        if (ProviderImageTrust.requiresRelaxedTls(request.url)) relaxed.executeOnFreshRoute(request)
        else delegate.executeOnFreshRoute(request)

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        if (ProviderImageTrust.requiresRelaxedTls(request.url)) relaxed.executeOnAlternateRoute(request)
        else delegate.executeOnAlternateRoute(request)

    /** The delegate is owned by its creator; this wrapper only owns the relaxed client. */
    override fun close() {
        (relaxed as? Closeable)?.close()
    }
}
