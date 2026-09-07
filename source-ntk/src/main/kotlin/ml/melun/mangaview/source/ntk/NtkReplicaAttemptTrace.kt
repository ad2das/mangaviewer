package ml.melun.mangaview.source.ntk

/** Immutable identity and event vocabulary for one replica attempt. */
internal class NtkReplicaAttemptTrace(
    traceContext: NtkTraceContext?,
    pageKey: String,
    routePreferQuic: Boolean,
    private val candidateHost: String,
) {
    private val context = (traceContext ?: NtkTraceContext()).copy(page = pageKey)
    private val attempt = NtkTrace.nextAttemptId()
    private val protocol = if (routePreferQuic) "quic" else "h2"

    fun start() = NtkTrace.emit(
        "protected-image-transport-start",
        context,
        role = "main",
        attempt = attempt,
        protocol = protocol,
        detail = candidateHost,
    )

    fun response(status: Int) = NtkTrace.emit(
        "protected-image-response",
        context,
        role = "main",
        attempt = attempt,
        protocol = protocol,
        status = status,
        outcome = "returned",
        detail = candidateHost,
    )

    fun prefixValidated(status: Int) = NtkTrace.emit(
        "protected-image-prefix-validated",
        context,
        role = "main",
        attempt = attempt,
        protocol = protocol,
        status = status,
        outcome = "success",
        detail = candidateHost,
    )

    fun cancelled() = NtkTrace.emit(
        "protected-image-attempt-cancelled",
        context,
        role = "main",
        attempt = attempt,
        protocol = protocol,
        outcome = "cancelled",
        detail = candidateHost,
    )

    fun failed(failure: Throwable) = NtkTrace.emit(
        "protected-image-attempt-failed",
        context,
        role = "main",
        attempt = attempt,
        protocol = protocol,
        outcome = failure.javaClass.simpleName,
        detail = candidateHost,
    )
}
