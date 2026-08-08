package ml.melun.mangaview.reader

import okhttp3.Call
import okhttp3.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot physical OkHttp connection evidence shared by the strict reader wrapper and the
 * demand-bound transport's private fallback client. The fallback chooses its real OkHttp client
 * inside CustomHttpClient after the reader has already handed it a Call.Factory, so a wrapper
 * around that factory cannot observe connectionAcquired itself.
 *
 * It never selects a client or creates, delays, retries, or cancels a request. In addition to the
 * terminal telemetry observation, one explicitly registered direct-Wi-Fi adjacent p0 may use its
 * real requestHeadersEnd callback to release p1..p3 after p0 has won wire order.
 */
data class NtkPhysicalConnectionObservation(
    val connectionId: String,
    val reused: Boolean,
    val clientInstanceId: String,
) {
    companion object {
        @JvmField
        val NONE = NtkPhysicalConnectionObservation("", false, "")
    }
}

object NtkPhysicalConnectionObservationBridge {
    private val observations = ConcurrentHashMap<Long, NtkPhysicalConnectionObservation>()
    private val connectionUses = ConcurrentHashMap<Int, AtomicInteger>()
    private val adjacentRequestHeadersCallbacks = ConcurrentHashMap<Long, () -> Unit>()

    /**
     * Registers the one direct-Wi-Fi adjacent p0 callback that may open p1..p3 after OkHttp has
     * physically written p0's request headers. A reused H2 connection can be acquired before that
     * stream wins wire order, so connectionAcquired itself is intentionally insufficient.
     */
    fun registerAdjacentRequestHeadersEnd(operationId: Long, callback: () -> Unit): Boolean {
        if (operationId <= 0L) return false
        return adjacentRequestHeadersCallbacks.putIfAbsent(operationId, callback) == null
    }

    /** Called only from a real OkHttp EventListener.requestHeadersEnd event. */
    @JvmStatic
    fun signalAdjacentRequestHeadersEnd(operationId: Long) {
        if (operationId > 0L) adjacentRequestHeadersCallbacks.remove(operationId)?.invoke()
    }

    /** Retires a callback whose physical operation ended before writing request headers. */
    fun cancelAdjacentRequestHeadersEnd(operationId: Long) {
        if (operationId > 0L) adjacentRequestHeadersCallbacks.remove(operationId)
    }

    /** Records one real EventListener.connectionAcquired callback for a live strict operation. */
    @JvmStatic
    fun record(
        operationId: Long,
        connection: Connection,
        physicalClient: Call.Factory,
    ) {
        if (operationId <= 0L) return
        val connectionIdentity = System.identityHashCode(connection)
        val prior = connectionUses
            .computeIfAbsent(connectionIdentity) { AtomicInteger(0) }
            .getAndIncrement()
        observations[operationId] = NtkPhysicalConnectionObservation(
            connectionId = NtkStripDigests.sha256Tokens(
                "ntk-source-connection-v1",
                connectionIdentity.toString(),
            ).take(16),
            reused = prior > 0,
            clientInstanceId = NtkStripDigests.sha256Tokens(
                "viewer-http-client-instance-v2",
                System.identityHashCode(physicalClient).toString(),
            ),
        )
    }

    /** Terminal reporting owns removal, preventing duplicate observations for one logical call. */
    @JvmStatic
    fun take(operationId: Long): NtkPhysicalConnectionObservation? =
        if (operationId > 0L) observations.remove(operationId) else null

    /** Called when the reader's global strict transport state is retired. */
    @JvmStatic
    fun clear() {
        observations.clear()
        connectionUses.clear()
        adjacentRequestHeadersCallbacks.clear()
    }
}
