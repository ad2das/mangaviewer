package ml.melun.mangaview.source.ntk

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

internal data class NtkTraceContext(
    val requestId: Long = 0L,
    val sourceEpisodeId: String = "unknown",
    val episodePath: String = "unknown",
    val providerEpisodeId: String = "unknown",
    val page: String = "unknown",
    val documentEpoch: Long = 0L,
)

internal object NtkTrace {
    private const val TAG = "NtkTrace"
    private val attemptIds = AtomicLong()

    fun nextAttemptId(): String = "i${attemptIds.incrementAndGet()}"

    fun emit(
        event: String,
        context: NtkTraceContext = NtkTraceContext(),
        role: String,
        attempt: String = "unknown",
        protocol: String = "unknown",
        status: Int = -1,
        source: String = "unknown",
        outcome: String = "unknown",
        reject: String = "unknown",
        detail: String = "unknown",
    ) {
        fun field(value: String): String = value
            .substringBefore('?')
            .substringBefore('#')
            .replace(Regex("[^A-Za-z0-9_./:=+|_-]"), "_")
            .take(96)
            .ifBlank { "unknown" }
        runCatching {
            Log.d(
                TAG,
                "v=1 clock=elapsedRealtimeNanos monoNs=${SystemClock.elapsedRealtimeNanos()} " +
                    "role=${field(role)} event=${field(event)} requestId=${context.requestId} " +
                    "sourceEpisodeId=${field(context.sourceEpisodeId)} " +
                    "episodePath=${field(context.episodePath)} " +
                    "providerEpisodeId=${field(context.providerEpisodeId)} " +
                    "page=${field(context.page)} epoch=${context.documentEpoch} " +
                    "attempt=${field(attempt)} protocol=${field(protocol)} status=$status " +
                    "source=${field(source)} outcome=${field(outcome)} " +
                    "reject=${field(reject)} detail=${field(detail)}",
            )
        }
    }
}
