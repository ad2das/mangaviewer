package ml.melun.mangaview.source

import java.io.Closeable

enum class SourceHttpMethod {
    GET,
    POST,
    HEAD,
}

/** End-to-end page urgency. It is a scheduling hint only; bytes and validation stay identical. */
enum class PageFetchPriority {
    FOCUS,
    VISIBLE,
    IMMINENT_FORWARD,
    FORWARD,
    NORMAL,
    DISTANT_FORWARD,
    ADJACENT_FORWARD,
    BACKGROUND,
}

data class SourceRequest(
    val url: String,
    val method: SourceHttpMethod = SourceHttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyMediaType: String? = null,
    val totalTimeoutMillis: Long = 45_000L,
    val preferQuic: Boolean = false,
    val priority: PageFetchPriority = PageFetchPriority.NORMAL,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Source request URL must be HTTP or HTTPS"
        }
        require(totalTimeoutMillis > 0L) { "Request timeout must be positive" }
        require(method == SourceHttpMethod.POST || body == null) { "Only POST requests may have a body" }
    }
}

data class SourceResponse(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val body: PageByteStream,
    val contentLength: Long?,
    val contentType: String?,
) : Closeable {
    init {
        require(statusCode in 100..599) { "HTTP status code is invalid" }
        require(contentLength == null || contentLength >= 0L) { "Content length is invalid" }
    }

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.lastOrNull()

    override fun close() = body.close()
}

fun interface SourceTransport {
    suspend fun execute(request: SourceRequest): SourceResponse

    /** Executes after a route failure without reusing the failed connection pool. */
    suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse = execute(request)

    /** Last-resort route for multi-address origins after both primary paths stay silent. */
    suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        executeOnFreshRoute(request)

    /** Number of genuinely independent route pools available for a small header race. */
    fun routeParallelism(): Int = 1

    /** True only when preferQuic selects a genuinely independent protocol engine. */
    fun supportsProtocolSelection(): Boolean = false

    /** Optionally prepares transport state without issuing an HTTP request. */
    fun warmConnections(urls: List<String>, preferQuic: Boolean = false) = Unit

    /** Drops only reusable idle routes after a proven route failure. Active owners stay intact. */
    fun retireIdleConnections() = Unit

}
