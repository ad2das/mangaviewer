package ml.melun.mangaview.app

import java.io.Closeable
import java.net.URI
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/**
 * Splits newxtoon traffic by host: artwork lives on a Bunny pull zone that the direct edge route
 * serves without Cloudflare, while origin documents keep the clearance transport. The split
 * happens ahead of both, so the clearance machinery never sees an image request and the image
 * client never sees an origin document.
 */
internal class NewxtoonImageTransport(
    private val documents: SourceTransport,
    private val images: SourceTransport,
) : SourceTransport by documents, Closeable {
    override suspend fun execute(request: SourceRequest): SourceResponse =
        route(request, documents::execute, images::execute)

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse =
        route(request, documents::executeOnFreshRoute, images::executeOnFreshRoute)

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        route(request, documents::executeOnAlternateRoute, images::executeOnAlternateRoute)

    override fun warmConnections(urls: List<String>, preferQuic: Boolean) {
        val (artwork, pages) = urls.partition(::isArtwork)
        if (artwork.isNotEmpty()) images.warmConnections(artwork, false)
        if (pages.isNotEmpty()) documents.warmConnections(pages, preferQuic)
    }

    override fun retireIdleConnections() {
        documents.retireIdleConnections()
        images.retireIdleConnections()
    }

    override fun close() {
        (documents as? Closeable)?.close()
        (images as? Closeable)?.close()
    }

    private suspend fun route(
        request: SourceRequest,
        pageRoute: suspend (SourceRequest) -> SourceResponse,
        artworkRoute: suspend (SourceRequest) -> SourceResponse,
    ): SourceResponse = if (isArtwork(request.url)) artworkRoute(request) else pageRoute(request)

    private fun isArtwork(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.endsWith(ARTWORK_ZONE_SUFFIX) == true

    private companion object {
        const val ARTWORK_ZONE_SUFFIX = ".quicksharefiles.top"
    }
}
