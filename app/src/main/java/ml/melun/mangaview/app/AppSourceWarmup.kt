package ml.melun.mangaview.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport

/**
 * HttpEngine construction alone does not resolve DNS or establish TLS. Open one bodyless H2
 * exchange while the library UI is loading so the first catalog document does not pay that
 * cold connection cost. This is deliberately limited to the public document origin; signed
 * image URLs are never probed or consumed by connection warming.
 */
internal fun preconnectOrigin(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    transport: SourceTransport,
    url: String,
    timeoutMillis: Long,
) {
    scope.launch(dispatcher) {
        runCatching {
            transport.execute(
                SourceRequest(
                    url = url,
                    method = SourceHttpMethod.HEAD,
                    headers = mapOf("Accept" to "text/html,*/*;q=0.1"),
                    totalTimeoutMillis = timeoutMillis,
                    preferQuic = false,
                    priority = PageFetchPriority.BACKGROUND,
                ),
            ).close()
        }
    }
}
