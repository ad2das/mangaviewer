package ml.melun.mangaview.source

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Transparent transport observation. With no observer the original response/stream is returned unchanged. */
class ObservedSourceTransport(
    private val delegate: SourceTransport,
    private val channel: String,
    private val observer: () -> SourceExchangeObserver?,
) : SourceTransport, Closeable {
    init { require(channel.isNotBlank()) }

    override suspend fun execute(request: SourceRequest) = observe(request, delegate::execute)
    override suspend fun executeOnFreshRoute(request: SourceRequest) = observe(request, delegate::executeOnFreshRoute)
    override suspend fun executeOnAlternateRoute(request: SourceRequest) = observe(request, delegate::executeOnAlternateRoute)
    override fun routeParallelism() = delegate.routeParallelism()
    override fun supportsProtocolSelection() = delegate.supportsProtocolSelection()
    override fun warmConnections(urls: List<String>, preferQuic: Boolean) = delegate.warmConnections(urls, preferQuic)
    override fun retireIdleConnections() = delegate.retireIdleConnections()
    override fun close() { if (delegate is AutoCloseable) delegate.close() }

    private suspend fun observe(request: SourceRequest, execute: suspend (SourceRequest) -> SourceResponse): SourceResponse {
        val sink = observer() ?: return execute(request)
        val exchange = Exchange(nextRequest.incrementAndGet(), channel, request, sink)
        exchange.record(SourceExchangePhase.STARTED)
        val response = try { execute(request) } catch (failure: Throwable) {
            exchange.recordFailure(SourceExchangePhase.REQUEST_FAILED, failure)
            throw failure
        }
        exchange.response = response
        try { exchange.record(SourceExchangePhase.HEADERS) } catch (failure: Throwable) {
            try { response.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        return response.copy(body = ObservedBody(response.body, exchange))
    }

    private class Exchange(val id: Long, val channel: String, request: SourceRequest, val sink: SourceExchangeObserver) {
        private val url = request.url
        private val method = request.method
        private val priority = request.priority
        private val preferQuic = request.preferQuic
        private val requestBytes = request.body?.size ?: 0
        private val requestHash = request.body?.let { sha256(it) }
        var response: SourceResponse? = null
        fun recordFailure(phase: SourceExchangePhase, failure: Throwable, bytes: Long = 0) {
            try { record(phase, bytes, error = failure) } catch (observation: Throwable) {
                if (observation !== failure) failure.addSuppressed(observation)
            }
        }
        fun record(phase: SourceExchangePhase, bytes: Long = 0, digest: String? = null,
                   document: ByteArray? = null, documentLimitExceeded: Boolean = false, error: Throwable? = null) {
            val value = response
            sink.observed(SourceExchangeEvidence(id, channel, phase, System.nanoTime(), url, method, priority,
                requestHash, requestBytes, value?.statusCode, value?.finalUrl, value?.contentType, value?.contentLength,
                bytes, digest, document, documentLimitExceeded, error?.javaClass?.name, preferQuic))
        }
    }

    private class ObservedBody(private val delegate: PageByteStream, private val exchange: Exchange) : PageByteStream {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bytes = 0L
        private var completed = false
        private var closedReported = false
        private var documentLimitExceeded = false
        private var document = exchange.response?.contentType?.substringBefore(';')?.trim()?.lowercase()?.let { type ->
            if (type in setOf("text/html", "application/xhtml+xml", "application/json")) ByteArrayOutputStream() else null
        }

        override suspend fun awaitReadable() = delegate.awaitReadable()
        override fun promote(priority: PageFetchPriority) = delegate.promote(priority)

        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            val count = try { delegate.readAtMost(destination, offset, byteCount) } catch (failure: Throwable) {
                exchange.recordFailure(SourceExchangePhase.BODY_FAILED, failure, bytes)
                throw failure
            }
            if (count > 0) {
                check(!completed)
                digest.update(destination, offset, count)
                bytes = Math.addExact(bytes, count.toLong())
                document?.let { buffer ->
                    if (bytes > MAX_DOCUMENT_BYTES) {
                        document = null
                        documentLimitExceeded = true
                    } else buffer.write(destination, offset, count)
                }
            } else if (count < 0 && !completed) {
                completed = true
                exchange.record(SourceExchangePhase.BODY_COMPLETE, bytes, digest.digest().hex(),
                    document?.toByteArray(), documentLimitExceeded)
                document = null
            }
            return count
        }

        override fun close() {
            delegate.close()
            document = null
            if (!closedReported) {
                closedReported = true
                exchange.record(SourceExchangePhase.CLOSED, bytes)
            }
        }
    }

    companion object {
        private val nextRequest = AtomicLong()
        private const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).hex()
    }
}
