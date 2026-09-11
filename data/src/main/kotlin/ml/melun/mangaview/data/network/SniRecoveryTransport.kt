package ml.melun.mangaview.data.network

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.*

/** Ordinary successful requests keep their existing transport and pools. */
class SniRecoveryTransport(
    private val primary: SourceTransport,
    createRecovery: () -> SourceTransport,
    private val nowNanos: () -> Long = System::nanoTime,
    sharedRecovery: Boolean = false,
) : SourceTransport, Closeable {
    private val recovery = if (sharedRecovery) lazy { sharedRecoveryTransport(createRecovery) } else lazy(createRecovery)
    private val ownsRecovery = !sharedRecovery
    private val recoveredHosts = if (sharedRecovery) SHARED_RECOVERED_HOSTS else ConcurrentHashMap()

    override suspend fun execute(request: SourceRequest) = execute(request, primary::execute)
    override suspend fun executeOnFreshRoute(request: SourceRequest) = execute(request, primary::executeOnFreshRoute)
    override suspend fun executeOnAlternateRoute(request: SourceRequest) = execute(request, primary::executeOnAlternateRoute)

    private suspend fun execute(request: SourceRequest, direct: suspend (SourceRequest) -> SourceResponse): SourceResponse {
        if (!request.url.startsWith("https://") || request.method == SourceHttpMethod.POST) return direct(request)
        val host = URI(request.url).host
        val started = nowNanos()
        if (recoveredHosts[host]?.let { started - it < RECOVERY_LIFETIME_NANOS } == true) {
            return remembered(request, host, started) { attempt -> boundedDirect(attempt, direct) }
        }
        return try {
            boundedDirect(request, direct)
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

    private suspend fun boundedDirect(request: SourceRequest,
        direct: suspend (SourceRequest) -> SourceResponse,
    ): SourceResponse {
        var acquired: SourceResponse? = null
        return try {
            withTimeoutOrNull(minOf(DIRECT_HEADER_TIMEOUT_MILLIS, request.totalTimeoutMillis)) {
                direct(request).also { acquired = it }
            } ?: throw SocketTimeoutException("Direct HTTPS response headers remained unavailable")
        } catch (failure: Throwable) {
            try { acquired?.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
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
    override fun warmConnections(urls: List<String>, preferQuic: Boolean) {
        val (recovered, direct) = urls.partition { isRecovered(URI(it).host) }
        if (direct.isNotEmpty()) primary.warmConnections(direct, preferQuic)
        if (recovered.isNotEmpty()) recovery.value.warmConnections(recovered, preferQuic)
    }
    override fun retireIdleConnections() {
        primary.retireIdleConnections()
        if (recovery.isInitialized()) recovery.value.retireIdleConnections()
    }
    override fun close() {
        try { (primary as? Closeable)?.close() }
        finally { if (ownsRecovery && recovery.isInitialized()) (recovery.value as? Closeable)?.close() }
        if (ownsRecovery) recoveredHosts.clear()
    }

    private fun isRecovered(host: String): Boolean =
        recoveredHosts[host]?.let { nowNanos() - it < RECOVERY_LIFETIME_NANOS } == true

    private fun Throwable.isCertificateFailure(): Boolean =
        generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(16)
            .any { it is SSLPeerUnverifiedException || it is CertificateException }

    companion object {
        const val DIRECT_HEADER_TIMEOUT_MILLIS = 1_000L
        const val RECOVERY_LIFETIME_NANOS = 10L * 60 * 1_000_000_000
        // The library and the viewer engine run in one process; a host proven blocked once
        // must not pay the direct-route timeout again in the other transport.
        val SHARED_RECOVERED_HOSTS = ConcurrentHashMap<String, Long>()
        @Volatile private var sharedInstance: SourceTransport? = null
        private val sharedLock = Any()

        fun sharedRecoveryTransport(create: () -> SourceTransport): SourceTransport {
            sharedInstance?.let { return it }
            return synchronized(sharedLock) {
                sharedInstance ?: create().also { sharedInstance = it }
            }
        }

        internal fun resetSharedForTest() {
            (sharedInstance as? Closeable)?.close()
            sharedInstance = null
            SHARED_RECOVERED_HOSTS.clear()
        }
    }
}
