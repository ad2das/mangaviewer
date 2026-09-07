package ml.melun.mangaview.source.ntk

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

internal class NtkDocumentClient(
    private val config: NtkConfig,
    private val transport: SourceTransport,
) {
    private val origin = NtkOriginSession(config.initialOrigin)

    suspend fun text(path: String, json: Boolean): String {
        return fetch(path, json, preferQuic = false, PageFetchPriority.NORMAL).html
    }

    suspend fun episodeDocument(
        path: String,
        priority: PageFetchPriority,
        traceContext: NtkTraceContext? = null,
    ): NtkEpisodeDocument {
        require(path.startsWith('/')) { "NTK path must be absolute" }
        val startedAt = System.nanoTime()
        val trace = traceContext ?: NtkTraceContext(
            sourceEpisodeId = path,
            episodePath = path,
        )
        NtkTrace.emit("native-document-start", trace, role = "main")
        val ticket = origin.begin()
        val opened = raceEpisodeResponse(path, priority, ticket.origin, trace)
        val fetched = readEpisodeResponse(opened, trace)
        logTiming("document-ready bytes=${fetched.html.toByteArray(Charsets.UTF_8).size}", startedAt)
        NtkTrace.emit("native-document-ready", trace, role = "main", outcome = "success")
        val document = NtkEpisodeDocument(
            origin = fetched.origin,
            path = path,
            html = fetched.html,
            responseHeaders = fetched.headers,
            contentType = fetched.contentType,
            finalUrl = fetched.finalUrl,
            originTicket = ticket,
        )
        validateNtkDocumentIdentity(document, path)
        return document
    }

    /** Publish only after the caller has accepted the document's parsed episode contract. */
    suspend fun acceptDocument(document: NtkEpisodeDocument) {
        document.originTicket?.let { origin.observe(document.finalUrl, it) }
    }

    private suspend fun raceEpisodeResponse(
        path: String,
        priority: PageFetchPriority,
        requestOrigin: String,
        trace: NtkTraceContext,
    ): OpenedText = coroutineScope {
        val outcomes = Channel<DocumentOutcome>(
            capacity = Channel.RENDEZVOUS,
            onUndeliveredElement = { outcome -> outcome.close() },
        )
        val protocols = if (transport.supportsProtocolSelection()) listOf(false, true) else listOf(false)
        val jobs = protocols.mapIndexed { index, preferQuic ->
            launch {
                var opened: OpenedText? = null
                if (index > 0) delay(DOCUMENT_PROTOCOL_HEDGE_MILLIS)
                NtkTrace.emit(
                    "native-document-http-start",
                    trace,
                    role = "main",
                    attempt = index.toString(),
                    protocol = if (preferQuic) "quic" else "h2",
                )
                try {
                    opened = openEpisodeResponse(requestOrigin + path, preferQuic, priority, trace, index)
                    val outcome = DocumentOutcome.Success(opened)
                    opened = null
                    outcomes.send(outcome)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    outcomes.send(DocumentOutcome.Failure(failure))
                } finally {
                    opened?.response?.close()
                }
            }
        }
        var selected: OpenedText? = null
        try {
            val failures = mutableListOf<Throwable>()
            repeat(jobs.size) {
                when (val outcome = outcomes.receive()) {
                    is DocumentOutcome.Failure -> failures += outcome.cause
                    is DocumentOutcome.Success -> {
                        selected = outcome.opened
                        jobs.forEach { job -> job.cancel() }
                        jobs.joinAll()
                        return@coroutineScope outcome.opened.also { selected = null }
                    }
                }
            }
            val summary = failures.joinToString { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }
            throw IOException("Every NTK document protocol failed ($summary)", failures.firstOrNull())
        } finally {
            selected?.response?.close()
            outcomes.cancel()
            jobs.forEach { it.cancel() }
        }
    }

    private suspend fun openEpisodeResponse(
        url: String,
        preferQuic: Boolean,
        priority: PageFetchPriority,
        trace: NtkTraceContext,
        attempt: Int,
    ): OpenedText {
        val response = transport.execute(SourceRequest(
            url = url,
            headers = requestHeaders(),
            preferQuic = preferQuic,
            priority = priority,
        ))
        NtkTrace.emit(
            "native-document-http-response",
            trace,
            role = "main",
            attempt = attempt.toString(),
            protocol = if (preferQuic) "quic" else "h2",
            status = response.statusCode,
        )
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("NTK document request failed with ${response.statusCode}")
        }
        return OpenedText(NtkOriginSession.originOf(response.finalUrl), response)
    }

    private suspend fun readEpisodeResponse(
        opened: OpenedText,
        trace: NtkTraceContext,
    ): FetchedText {
        val response = opened.response
        val capacity = response.contentLength
            ?.takeIf { it >= 0L }
            ?.coerceAtMost(MAX_DOCUMENT_BYTES.toLong())
            ?.toInt()
            ?: DOCUMENT_INITIAL_CAPACITY
        val output = ByteArrayOutputStream(capacity)
        val buffer = ByteArray(DOCUMENT_BUFFER_BYTES)
        try {
            while (true) {
                val count = response.body.readAtMost(buffer, 0, buffer.size)
                if (count < 0) break
                require(count > 0) { "NTK document stream returned zero bytes" }
                require(output.size() <= MAX_DOCUMENT_BYTES - count) {
                    "NTK document exceeds its byte limit"
                }
                output.write(buffer, 0, count)
            }
            require(response.contentLength == null || response.contentLength == output.size().toLong()) {
                "NTK document length does not match its declaration"
            }
            NtkTrace.emit("native-document-body-eof", trace, role = "main", outcome = "success")
            return FetchedText(
                opened.origin,
                response.finalUrl,
                output.toByteArray().toString(Charsets.UTF_8),
                response.headers,
                response.contentType,
            )
        } finally {
            response.close()
        }
    }

    private suspend fun fetch(
        path: String,
        json: Boolean,
        preferQuic: Boolean,
        priority: PageFetchPriority,
    ): FetchedText {
        require(path.startsWith('/')) { "NTK path must be absolute" }
        val ticket = origin.begin()
        val response = transport.execute(SourceRequest(
            url = ticket.origin + path,
            headers = requestHeaders().toMutableMap().apply {
                if (json) this["Accept"] = "application/json"
            },
            preferQuic = preferQuic,
            priority = priority,
        ))
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("NTK document request failed with ${response.statusCode}")
        }
        val headers = response.headers
        val contentType = response.contentType
        val html = response.readBytes(MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8)
        origin.observe(response.finalUrl, ticket)
        return FetchedText(NtkOriginSession.originOf(response.finalUrl), response.finalUrl, html, headers, contentType)
    }

    suspend fun currentOrigin(): String = origin.current()

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun logTiming(phase: String, startedAtNanos: Long) {
        runCatching { Log.d(NATIVE_TAG, "phase=$phase elapsedMs=${elapsedMillis(startedAtNanos)}") }
    }

    suspend fun url(path: String): String = origin.url(path)

    suspend fun openArtwork(value: String, refererPath: String): OpenedPage? {
        val base = "${origin.current()}/"
        val url = runCatching { URI(base).resolve(value.trim()).toString() }.getOrNull() ?: return null
        val response = transport.execute(SourceRequest(url, headers = requestHeaders(origin.url(refererPath))))
        if (response.statusCode !in 200..299) {
            response.close()
            return null
        }
        return OpenedPage(
            stream = response.body,
            contentLength = response.contentLength,
            contentType = response.contentType,
            entityTag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
        )
    }

    fun requestHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", config.userAgent)
        put("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
        referer?.let { put("Referer", it) }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 32 * 1_024 * 1_024
        const val DOCUMENT_PROTOCOL_HEDGE_MILLIS = 50L
        const val DOCUMENT_INITIAL_CAPACITY = 64 * 1_024
        const val DOCUMENT_BUFFER_BYTES = 32 * 1_024
        const val NATIVE_TAG = "NtkNative"
    }

    private sealed interface DocumentOutcome {
        fun close() = Unit
        data class Success(val opened: OpenedText) : DocumentOutcome {
            override fun close() = opened.response.close()
        }
        data class Failure(val cause: Throwable) : DocumentOutcome
    }

    private data class OpenedText(val origin: String, val response: ml.melun.mangaview.source.SourceResponse)

    private data class FetchedText(
        val origin: String,
        val finalUrl: String,
        val html: String,
        val headers: Map<String, List<String>>,
        val contentType: String?,
    )
}
