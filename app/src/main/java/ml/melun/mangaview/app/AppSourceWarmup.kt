package ml.melun.mangaview.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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

/**
 * WFWF page bodies live on CDN hosts that are unknown until the episode document arrives, so the
 * remembered image hosts are the only pre-request knowledge available. A bodyless HEAD to the bare
 * host root opens DNS, TCP and TLS in parallel with the document fetch; it touches no path and no
 * signed URL, so a wrong or dead host is only an abandoned hint.
 */
internal fun preconnectImageHosts(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    transport: SourceTransport,
    hosts: () -> List<String>,
    timeoutMillis: Long,
) {
    scope.launch(dispatcher) {
        val remembered = hosts().take(IMAGE_HOST_PRECONNECT_LIMIT)
        coroutineScope {
            remembered.forEach { host ->
                launch {
                    runCatching {
                        transport.execute(
                            SourceRequest(
                                url = "https://$host/",
                                method = SourceHttpMethod.HEAD,
                                headers = mapOf("Accept" to "image/avif,image/webp,image/*,*/*;q=0.8"),
                                totalTimeoutMillis = timeoutMillis,
                                preferQuic = false,
                                priority = PageFetchPriority.BACKGROUND,
                            ),
                        ).close()
                    }
                }
            }
        }
    }
}

private const val IMAGE_HOST_PRECONNECT_LIMIT = 2
