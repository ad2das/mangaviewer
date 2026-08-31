package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId

sealed interface RetryWorkKey {
    data class Page(val pageId: PageId, val kind: WorkKind) : RetryWorkKey
    data class Episode(val episodeId: EpisodeId) : RetryWorkKey
}

internal fun advanceRetryClock(state: ViewerState, eventTimeNanos: Long): ViewerState = state.copy(
    lastEventNanos = eventTimeNanos,
    nextRetryDeadlineNanos = state.retryDeadlines.values
        .asSequence()
        .filter { it > eventTimeNanos }
        .minOrNull(),
)

internal fun recordRetryDeadline(
    state: ViewerState,
    key: RetryWorkKey,
    deadlineNanos: Long,
): ViewerState {
    val deadlines = state.retryDeadlines.put(key, deadlineNanos)
    return state.copy(
        retryDeadlines = deadlines,
        nextRetryDeadlineNanos = deadlines.values.asSequence()
            .filter { it > state.lastEventNanos }
            .minOrNull(),
    )
}

internal fun clearRetryDeadline(state: ViewerState, key: RetryWorkKey): ViewerState {
    if (key !in state.retryDeadlines) return state
    val deadlines = state.retryDeadlines.remove(key)
    return state.copy(
        retryDeadlines = deadlines,
        nextRetryDeadlineNanos = deadlines.values.asSequence()
            .filter { it > state.lastEventNanos }
            .minOrNull(),
    )
}
