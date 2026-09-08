package ml.melun.mangaview.data.network

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.source.*

/** Ordinary successful requests keep their existing transport and pools. */
class SniRecoveryTransport(
    private val primary: SourceTransport,
    createRecovery: () -> SourceTransport,
    private val nowNanos: () -> Long = System::nanoTime,
) : SourceTransport, Closeable {
    private val recovery = lazy(createRecovery)
    private val recoveredHosts = ConcurrentHashMap<String, Long>()

    override suspend fun execute(request: SourceRequest) = execute(request, primary::execute)
    override suspend fun executeOnFreshRoute(request: SourceRequest) = execute(request, primary::executeOnFreshRoute)
    override suspend fun executeOnAlternateRoute(request: SourceRequest) = execute(request, primary::executeOnAlternateRoute)

    private suspend fun execute(request: SourceRequest, direct: suspend (SourceRequest) -> SourceResponse): SourceResponse {
        if (!request.url.startsWith("https://") || request.method == SourceHttpMethod.POST) return direct(request)
        val host = URI(request.url).host
        val started = nowNanos()
        if (recoveredHosts[host]?.let { started - it < RECOVERY_LIFETIME_NANOS } == true) {
            return remembered(request, host, started, direct)
        }
        return try {
            direct(request)
        } catch (failure: IOException) {
            if (failure.isCertificateFailure()) throw failure
            val remaining = request.totalTimeoutMillis - (nowNanos() - started) / 1_000_000
            if (remaining <= 0) throw failure
            try {
                recover(request.copy(totalTimeoutMillis = remaining)).also {
                    if (recoveredHosts.size >= 128) recoveredHosts.clear()
                    recoveredHosts[host] = nowNanos()
                }
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }

    private suspend fun remembered(request: SourceRequest, host: String, started: Long,
        direct: suspend (SourceRequest) -> SourceResponse): SourceResponse = try {
        recover(request)
    } catch (failure: IOException) {
        recoveredHosts.remove(host)
        if (failure.isCertificateFailure()) throw failure
        val remaining = request.totalTimeoutMillis - (nowNanos() - started) / 1_000_000
        if (remaining <= 0) throw failure
        direct(request.copy(totalTimeoutMillis = remaining))
    }

    private suspend fun recover(request: SourceRequest): SourceResponse {
        var acquired: SourceResponse? = null
        return try {
            withTimeout(request.totalTimeoutMillis) { recovery.value.execute(request).also { acquired = it } }
        } catch (failure: Throwable) { acquired?.close(); throw failure }
    }

    override fun routeParallelism() = primary.routeParallelism()
    override fun supportsProtocolSelection() = primary.supportsProtocolSelection()
    override fun warmConnections(urls: List<String>, preferQuic: Boolean) = primary.warmConnections(urls, preferQuic)
    override fun retireIdleConnections() {
        primary.retireIdleConnections()
        if (recovery.isInitialized()) recovery.value.retireIdleConnections()
    }
    override fun close() {
        try { (primary as? Closeable)?.close() }
        finally { if (recovery.isInitialized()) (recovery.value as? Closeable)?.close() }
        recoveredHosts.clear()
    }

    private fun Throwable.isCertificateFailure(): Boolean =
        generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(16)
            .any { it is SSLPeerUnverifiedException || it is CertificateException }

    private companion object { const val RECOVERY_LIFETIME_NANOS = 10L * 60 * 1_000_000_000 }
}
