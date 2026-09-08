package ml.melun.mangaview.app

import java.io.Closeable
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.source.*

internal class ProviderOriginTransport(
    private val delegate: SourceTransport,
    private val directory: ProviderOriginDirectory,
) : SourceTransport by delegate, Closeable {
    override suspend fun execute(request: SourceRequest) = execute(request, delegate::execute)
    override suspend fun executeOnFreshRoute(request: SourceRequest) = execute(request, delegate::executeOnFreshRoute)
    override suspend fun executeOnAlternateRoute(request: SourceRequest) = execute(request, delegate::executeOnAlternateRoute)

    private suspend fun execute(request: SourceRequest, send: suspend (SourceRequest) -> SourceResponse): SourceResponse {
        val provider = directory.provider(request.url) ?: return send(request)
        if (request.method == SourceHttpMethod.POST) return send(request)
        val origin = directory.current(provider, origin(request.url))
        var acquired: SourceResponse? = null
        return try { withTimeout(request.totalTimeoutMillis) {
            val started = System.nanoTime()
            try {
                checked(send(atOrigin(request, origin))).also { acquired = it }.also { response ->
                    val finalOrigin = origin(response.finalUrl)
                    // Image redirects to a CDN do not indicate that the catalog moved.
                    val type = response.contentType.orEmpty().substringBefore(';').trim()
                    if (finalOrigin != origin && (type == "text/html" || type == "application/json")) {
                        directory.observeRedirect(provider, finalOrigin, delegate)
                    }
                }
            } catch (failure: IOException) {
                acquired?.close(); acquired = null
                val replacement = directory.recover(provider, origin, delegate)
                if (replacement == null || replacement == origin) throw failure
                val remaining = request.totalTimeoutMillis - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) throw failure
                checked(send(atOrigin(request.copy(totalTimeoutMillis = remaining), replacement))).also { acquired = it }
            }
        } } catch (failure: Throwable) { acquired?.close(); throw failure }
    }

    private fun checked(response: SourceResponse): SourceResponse {
        if (response.statusCode in RECOVERABLE_STATUS) {
            response.close()
            throw IOException("Provider origin returned ${response.statusCode}")
        }
        return response
    }

    private fun atOrigin(request: SourceRequest, origin: String): SourceRequest {
        val uri = URI(request.url)
        val target = origin + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val headers = request.headers.mapValues { (key, value) ->
            if ((key.equals("Referer", true) || key.equals("Origin", true)) &&
                runCatching { URI(value).host == uri.host }.getOrDefault(false)) {
                val referer = URI(value)
                origin + referer.rawPath + (referer.rawQuery?.let { "?$it" } ?: "")
            } else value
        }
        return request.copy(url = target, headers = headers)
    }

    private fun origin(value: String): String {
        val uri = URI(value)
        return URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
    }

    override fun close() { (delegate as? Closeable)?.close() }

    private companion object { val RECOVERABLE_STATUS = setOf(403, 404, 410, 421, 451, 502, 503) }
}
