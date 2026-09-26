package ml.melun.mangaview.source.ntk

import java.net.SocketTimeoutException
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/** Bounds a silent image candidate before EnginePageWork tries the next authorized candidate. */
class NtkPageHeaderTransport(
    private val transport: SourceTransport,
    private val headerTimeoutMillis: Long = 8_000L,
) : SourceTransport by transport {
    init { require(headerTimeoutMillis > 0L) }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        var opened: SourceResponse? = null
        var handedOff = false
        try {
            val received = withTimeoutOrNull(headerTimeoutMillis) {
                opened = transport.execute(request)
                true
            } == true
            if (!received) {
                // A silent candidate is expected under provider throttling; logging it makes the
                // stall visible instead of only surfacing as a failed page request downstream.
                // The log is best-effort: JVM unit tests have no android.util.Log implementation,
                // and the timeout itself must not depend on the logger.
                runCatching {
                    android.util.Log.w(
                        "NtkPage",
                        "image response headers silent for ${headerTimeoutMillis}ms; trying the next candidate",
                    )
                }
                throw SocketTimeoutException("NTK image response headers timed out after ${headerTimeoutMillis}ms")
            }
            return checkNotNull(opened).also { handedOff = true }
        } finally {
            // A response delivered at the cancellation boundary still has one owner.
            if (!handedOff) opened?.close()
        }
    }
}
