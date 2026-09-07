package ml.melun.mangaview.source.ntk

import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority

internal data class NtkReplicaWinner(
    val opened: OpenedPage,
    val lease: NtkReplicaSelector.ReplicaLease,
    val startedAtNanos: Long,
    val usedQuic: Boolean,
)

/** Immutable scheduling policy for a logical PageId's bounded replica race. */
internal object NtkReplicaRacePolicy {
    const val IMMEDIATE_WINDOW = 3
    const val FORWARD_WINDOW = 3
    const val PROTOCOL_HEDGE_MILLIS = 125L
    const val PROVISIONAL_PROTOCOL_HEDGE_MILLIS = 350L
    const val PROVEN_PROTOCOL_HEDGE_MILLIS = 750L
    const val IMMEDIATE_BODY_DISTRIBUTION_GRACE_MILLIS = 250L
    const val PAGE_BODY_TIMEOUT_MILLIS = 120_000L
    const val LOG_TAG = "NtkPageRoute"

    fun isImmediate(priority: PageFetchPriority): Boolean =
        priority == PageFetchPriority.FOCUS || priority == PageFetchPriority.VISIBLE

    fun candidateWindow(priority: PageFetchPriority): Int = when (priority) {
        PageFetchPriority.FOCUS, PageFetchPriority.VISIBLE -> IMMEDIATE_WINDOW
        PageFetchPriority.NORMAL -> 3
        PageFetchPriority.IMMINENT_FORWARD,
        PageFetchPriority.FORWARD,
        PageFetchPriority.DISTANT_FORWARD,
        PageFetchPriority.ADJACENT_FORWARD,
        -> FORWARD_WINDOW
        PageFetchPriority.BACKGROUND -> 1
    }

    fun routeHedge(priority: PageFetchPriority, immediateMillis: Long): Long = when (priority) {
        // Only the single current focus opens every unknown route immediately. Losing candidates
        // are capped at the fixed validation prefix and cancelled, so this removes artificial
        // serial RTT without creating duplicate full-page downloads.
        PageFetchPriority.FOCUS -> 0L
        PageFetchPriority.FORWARD -> immediateMillis
        else -> immediateMillis
    }

    fun bodyDistributionGrace(priority: PageFetchPriority): Long = when (priority) {
        PageFetchPriority.FOCUS, PageFetchPriority.VISIBLE ->
            IMMEDIATE_BODY_DISTRIBUTION_GRACE_MILLIS
        // Forward pages already share a priority-aware three-body reader. Waiting after a valid
        // header merely postpones their first decodable bytes and did not improve distribution.
        PageFetchPriority.FORWARD -> 0L
        else -> 0L
    }
}
