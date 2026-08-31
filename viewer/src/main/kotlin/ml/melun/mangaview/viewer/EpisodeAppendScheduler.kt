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
        if (boundaryOwner == null && !planner.shouldPrepare(state)) {
            return Reduction(state, emptyList())
        }
        val episodeId = boundaryOwner ?: state.currentEpisodeId
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
            val spec = PageSpec(boundary, 0, estimateNextPage(state, episodeId))
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

    private fun estimateNextPage(state: ViewerState, episodeId: ml.melun.mangaview.core.EpisodeId): PageDimensions {
        val candidates = state.manifests.first { it.id == episodeId }.pages.mapNotNull { page ->
            val index = state.layout.indexOf(page.id) ?: return@mapNotNull null
            state.layout.entries[index].resolvedDimensions ?: page.dimensions
        }.sortedBy { dimensions -> dimensions.heightPx.toDouble() / dimensions.widthPx }
        return candidates.getOrNull(candidates.size / 2) ?: PageDimensions(2, 3)
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
    }
}
