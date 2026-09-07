package ml.melun.mangaview.source

/** Observation only: implementations must be thread-safe, nonblocking, and perform no body/network work. */
fun interface SourceExchangeObserver {
    fun observed(evidence: SourceExchangeEvidence)
}

enum class SourceExchangePhase { STARTED, HEADERS, BODY_COMPLETE, BODY_FAILED, REQUEST_FAILED, CLOSED }

class SourceExchangeEvidence(
    val requestId: Long,
    val channel: String,
    val phase: SourceExchangePhase,
    val atNanos: Long,
    val requestUrl: String,
    val method: SourceHttpMethod,
    val priority: PageFetchPriority,
    val requestBodySha256: String?,
    val requestBodyBytes: Int,
    val statusCode: Int? = null,
    val finalUrl: String? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val bodyBytes: Long = 0,
    val bodySha256: String? = null,
    val documentBody: ByteArray? = null,
    val documentBodyLimitExceeded: Boolean = false,
    val errorType: String? = null,
    val preferQuic: Boolean = false,
) {
    override fun toString() = "SourceExchangeEvidence(request=$requestId, channel=$channel, phase=$phase, bytes=$bodyBytes)"
}
