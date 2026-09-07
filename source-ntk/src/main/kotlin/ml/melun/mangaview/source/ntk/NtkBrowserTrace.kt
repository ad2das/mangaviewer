package ml.melun.mangaview.source.ntk

internal fun RemoteRequest.browserTrace(
    event: String,
    role: String = "browser",
    status: Int = -1,
    source: String = "unknown",
    detail: String = "unknown",
) {
    NtkTrace.emit(
        event,
        NtkTraceContext(
            requestId = requestId,
            sourceEpisodeId = path,
            episodePath = path,
            providerEpisodeId = descriptor?.episodeId ?: "unknown",
            documentEpoch = documentEpoch,
        ),
        role = role,
        status = status,
        source = source,
        detail = detail,
    )
}

internal fun traceAckPhase(request: RemoteRequest, phase: String, status: Int) {
    val event = when (phase) {
        "ack-ready" -> "browser-ack-ready"
        "ack-start" -> "browser-ack-start"
        "ack-end" -> "browser-ack-end"
        "manifest-start" -> "browser-manifest-start"
        "manifest-end" -> "browser-manifest-end"
        "challenge-start" -> "browser-challenge-start"
        "challenge-end" -> "browser-challenge-end"
        else -> return
    }
    request.browserTrace(
        event,
        role = "bridge",
        status = status,
        source = if (phase.startsWith("manifest-")) "unobserved" else "unknown",
        detail = phase,
    )
}
