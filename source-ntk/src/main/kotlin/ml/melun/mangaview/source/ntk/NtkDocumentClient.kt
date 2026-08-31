package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

internal class NtkDocumentClient(
    private val config: NtkConfig,
    private val transport: SourceTransport,
) {
    private val origin = NtkOriginSession(config.initialOrigin)

    suspend fun text(path: String, json: Boolean): String {
        return fetch(path, json).html
    }

    suspend fun episodeDocument(path: String): NtkEpisodeDocument {
        val fetched = fetch(path, json = false)
        return NtkEpisodeDocument(
            origin = fetched.origin,
            path = path,
            html = fetched.html,
            responseHeaders = fetched.headers,
            contentType = fetched.contentType,
            finalUrl = fetched.finalUrl,
        )
    }

    private suspend fun fetch(path: String, json: Boolean): FetchedText {
        val response = transport.execute(SourceRequest(
            url = origin.url(path),
            headers = requestHeaders().toMutableMap().apply {
                if (json) this["Accept"] = "application/json"
            },
        ))
        origin.observe(response.finalUrl)
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("NTK document request failed with ${response.statusCode}")
        }
        val headers = response.headers
        val contentType = response.contentType
        val html = response.readBytes(MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8)
        return FetchedText(origin.current(), response.finalUrl, html, headers, contentType)
    }

    suspend fun currentOrigin(): String = origin.current()

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
    }

    private data class FetchedText(
        val origin: String,
        val finalUrl: String,
        val html: String,
        val headers: Map<String, List<String>>,
        val contentType: String?,
    )
}
