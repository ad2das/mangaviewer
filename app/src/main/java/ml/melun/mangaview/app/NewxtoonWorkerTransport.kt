package ml.melun.mangaview.app

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/**
 * Serves newxtoon documents through a Cloudflare Worker that subrequests the origin: a worker
 * subrequest is not handed the zone challenge, so catalog and chapter HTML arrives without any
 * clearance cookie or WebView. The clearance route stays behind it as the fallback for the moment
 * the worker is unreachable or refuses a document.
 */
internal class NewxtoonWorkerTransport(
    private val worker: SourceTransport,
    private val fallback: SourceTransport,
    private val origin: String = ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN,
    private val workerOrigin: String = DEFAULT_WORKER_ORIGIN,
) : SourceTransport, Closeable {
    private val originHost = URI(origin).host.lowercase()

    override suspend fun execute(request: SourceRequest): SourceResponse =
        route(request, worker::execute, fallback::execute)

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse =
        route(request, worker::executeOnFreshRoute, fallback::executeOnFreshRoute)

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        route(request, worker::executeOnAlternateRoute, fallback::executeOnAlternateRoute)

    override fun warmConnections(urls: List<String>, preferQuic: Boolean) {
        val (documents, rest) = urls.partition(::isOriginDocument)
        if (documents.isNotEmpty()) worker.warmConnections(listOf(workerOrigin), false)
        if (rest.isNotEmpty()) fallback.warmConnections(rest, preferQuic)
    }

    override fun retireIdleConnections() {
        worker.retireIdleConnections()
        fallback.retireIdleConnections()
    }

    override fun close() {
        (worker as? Closeable)?.close()
        (fallback as? Closeable)?.close()
    }

    private suspend fun route(
        request: SourceRequest,
        workerRoute: suspend (SourceRequest) -> SourceResponse,
        fallbackRoute: suspend (SourceRequest) -> SourceResponse,
    ): SourceResponse {
        if (request.method != SourceHttpMethod.GET || !isOriginDocument(request.url)) {
            return fallbackRoute(request)
        }
        val relayed = request.copy(url = workerUrl(request.url))
        val response = try {
            workerRoute(relayed)
        } catch (failure: IOException) {
            return fallbackRoute(request)
        }
        if (response.statusCode in 200..299) {
            // The worker answers under its own URL with a plain-text type; the payload is still the
            // origin's HTML, so downstream sees the requested URL and an HTML content type again.
            return response.copy(
                finalUrl = request.url,
                contentType = response.contentType?.takeUnless { it.startsWith("text/plain") }
                    ?: "text/html; charset=utf-8",
            )
        }
        response.close()
        return fallbackRoute(request)
    }

    private fun workerUrl(target: String): String =
        workerOrigin + "/?url=" + URLEncoder.encode(target, "UTF-8")

    private fun isOriginDocument(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull() == originHost

    internal companion object {
        // A public CORS relay that subrequests the origin from Cloudflare's network, where the zone
        // challenge is not applied. Swap for a dedicated deployment when one exists.
        const val DEFAULT_WORKER_ORIGIN = "https://cors-get-proxy.sirjosh.workers.dev"
    }
}
