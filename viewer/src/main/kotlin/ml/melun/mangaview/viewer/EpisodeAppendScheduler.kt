package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageSpec

class EpisodeAppendScheduler(
    private val planner: NextEpisodePlanner = NextEpisodePlanner(),
    private val scrollController: ScrollController = ScrollController(),
) {
    fun schedule(state: ViewerState): Reduction {
        val boundaryOwner = state.episodeAppends.entries.firstOrNull {
            it.value.boundaryPageId == state.scroll.anchor.pageId
        }?.key
        val episodeId = boundaryOwner ?: preparationCandidate(state)
            ?: return Reduction(state, emptyList())
        val runtime = state.episodeAppends[episodeId] ?: EpisodeAppendRuntime()
        if (!canStart(state, runtime)) return Reduction(state, emptyList())
        val targetEpisodeId = state.manifests.firstOrNull { it.id == episodeId }?.nextEpisodeId
            ?: return markTerminal(state, episodeId)
        val token = EpisodeOperationToken(
            generation = state.generation,
            fromEpisodeId = episodeId,
            targetEpisodeId = targetEpisodeId,
            attempt = (runtime.retry?.failures ?: 0) + 1,
        )
        val boundary = runtime.boundaryPageId ?: PageId(targetEpisodeId, BOUNDARY_PAGE_KEY)
        val withBoundary = if (!state.layout.contains(boundary)) {
            val spec = PageSpec(boundary, 0, estimateNextEpisodeRunway(state, episodeId))
            val ledger = state.layout.append(listOf(spec))
            state.copy(
                pageOrder = state.pageOrder + boundary,
                pages = state.pages.put(boundary, PageRuntime(spec)),
                layout = ledger,
                scroll = scrollController.preserveAnchor(ledger, state.viewport, state.scroll),
                coldFetchSweep = state.coldFetchSweep.append(1),
            )
        } else {
            state
        }
        val updated = runtime.copy(owner = token, retry = null, boundaryPageId = boundary)
        return Reduction(
            clearRetryDeadline(
                withBoundary.copy(
                    episodeAppends = withBoundary.episodeAppends + (episodeId to updated),
                ),
                RetryWorkKey.Episode(episodeId),
            ),
            listOf(ViewerCommand.LoadNextEpisode(token)),
        )
    }

    private fun canStart(state: ViewerState, runtime: EpisodeAppendRuntime): Boolean =
        !runtime.terminal && runtime.owner == null &&
            (runtime.retry?.eligibleAtNanos ?: 0L) <= state.lastEventNanos

    private fun preparationCandidate(state: ViewerState): ml.melun.mangaview.core.EpisodeId? {
        val currentIndex = state.manifests.indexOfFirst { it.id == state.currentEpisodeId }
        if (currentIndex < 0) return null
        return state.manifests.asSequence()
            .drop(currentIndex)
            .map { it.id }
            .firstOrNull { episodeId ->
                val runtime = state.episodeAppends[episodeId] ?: EpisodeAppendRuntime()
                canStart(state, runtime) && planner.shouldPrepare(state, episodeId)
            }
    }

    private fun estimateNextEpisodeRunway(
        state: ViewerState,
        episodeId: ml.melun.mangaview.core.EpisodeId,
    ): PageDimensions {
        val pages = state.manifests.first { it.id == episodeId }.pages
        val first = requireNotNull(state.layout.indexOf(pages.first().id))
        val last = requireNotNull(state.layout.indexOf(pages.last().id))
        val episodeTop = state.layout.topAt(first).units
        val episodeBottom = Math.addExact(
            state.layout.topAt(last).units,
            state.layout.entries[last].height.units,
        )
        val episodeHeight = episodeBottom - episodeTop
        val ratioHeight = multiplyDivideCeilExact(
            episodeHeight,
            RUNWAY_RATIO_WIDTH.toLong(),
            state.viewport.width.units,
        ).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return PageDimensions(RUNWAY_RATIO_WIDTH, ratioHeight)
    }

    private fun markTerminal(state: ViewerState, episodeId: ml.melun.mangaview.core.EpisodeId): Reduction =
        Reduction(
            state.copy(
                episodeAppends = state.episodeAppends + (episodeId to EpisodeAppendRuntime(terminal = true)),
            ),
            emptyList(),
        )

    private companion object {
        const val BOUNDARY_PAGE_KEY = "__viewer_pending_boundary__"
        const val RUNWAY_RATIO_WIDTH = 1_024
    }
}
