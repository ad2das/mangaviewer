package ml.melun.mangaview.viewer

import kotlinx.collections.immutable.PersistentMap
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId

internal class EpisodeAppendReducer(
    private val scrollController: ScrollController,
) {
    fun succeeded(state: ViewerState, event: ViewerEvent.NextEpisodeSucceeded): ViewerState {
        val runtime = owned(state, event.token) ?: return state
        val boundary = runtime.boundaryPageId
        val resolved = markResolved(state, event, runtime)
        validate(event.manifest, event.token, state)
        val appended = appendPages(resolved, boundary, event.manifest)
        val mappedScroll = if (boundary != null && resolved.scroll.anchor.pageId == boundary) {
            resolved.scroll.copy(anchor = resolved.scroll.anchor.copy(pageId = event.manifest.pages.first().id))
        } else {
            resolved.scroll
        }
        return resolved.copy(
            manifests = resolved.manifests + event.manifest,
            pageOrder = appended.pageOrder,
            pages = appended.pages,
            layout = appended.ledger,
            scroll = scrollController.preserveAnchor(appended.ledger, resolved.viewport, mappedScroll),
            episodeProgress = resolved.episodeProgress + (
                event.manifest.id to EpisodeProgress(
                    event.manifest.pages.size,
                    0,
                    event.manifest.pages.last().id,
                )
            ),
            coldFetchSweep = resolved.coldFetchSweep.append(appended.addedPageCount),
        )
    }

    fun failed(state: ViewerState, event: ViewerEvent.NextEpisodeFailed): ViewerState {
        val runtime = owned(state, event.token) ?: return state
        require(event.reason.isNotBlank()) { "Failure reason must not be blank" }
        require(event.retryDelayNanos >= 0L) { "Retry delay must not be negative" }
        val retry = RetryState(
            failures = event.token.attempt,
            eligibleAtNanos = deadline(event.atNanos, event.retryDelayNanos),
            reason = event.reason,
        )
        return recordRetryDeadline(
            state.copy(
                episodeAppends = state.episodeAppends + (
                    event.token.fromEpisodeId to runtime.copy(owner = null, retry = retry)
                ),
            ),
            RetryWorkKey.Episode(event.token.fromEpisodeId),
            retry.eligibleAtNanos,
        )
    }

    private fun markResolved(
        state: ViewerState,
        event: ViewerEvent.NextEpisodeSucceeded,
        runtime: EpisodeAppendRuntime,
    ): ViewerState {
        val completed = runtime.copy(owner = null, retry = null, terminal = true, boundaryPageId = null)
        return state.copy(
            episodeAppends = state.episodeAppends + (event.token.fromEpisodeId to completed),
        )
    }

    private fun validate(
        manifest: EpisodeManifest,
        token: EpisodeOperationToken,
        state: ViewerState,
    ) {
        require(manifest.id == token.targetEpisodeId) {
            "Appended manifest does not match its operation target"
        }
        require(manifest.id.seriesId == state.manifests.first().id.seriesId) {
            "Appended episode belongs to another series"
        }
        require(state.manifests.none { it.id == manifest.id }) { "Episode is already appended" }
    }

    private fun appendPages(
        state: ViewerState,
        boundary: PageId?,
        manifest: EpisodeManifest,
    ): AppendedPages {
        val first = manifest.pages.first()
        val replacesBoundary = boundary != null && state.layout.contains(boundary)
        if (replacesBoundary) {
            require(first.id.episodeId == boundary.episodeId) {
                "Prepared episode does not match its boundary"
            }
        }
        val additions = if (replacesBoundary) manifest.pages.drop(1) else manifest.pages
        val firstRuntimeSpec = if (replacesBoundary && first.dimensions == null) {
            first.copy(dimensions = state.pages.getValue(requireNotNull(boundary)).spec.dimensions)
        } else {
            first
        }
        val pages = if (replacesBoundary) {
            state.pages.remove(requireNotNull(boundary)).put(first.id, PageRuntime(firstRuntimeSpec))
        } else {
            state.pages
        }
        return AppendedPages(
            ledger = if (replacesBoundary) {
                state.layout.replaceLast(requireNotNull(boundary), firstRuntimeSpec).append(additions)
            } else {
                state.layout.append(additions)
            },
            pageOrder = if (replacesBoundary) {
                state.pageOrder.dropLast(1) + manifest.pages.map { it.id }
            } else {
                state.pageOrder + manifest.pages.map { it.id }
            },
            pages = pages.putAll(additions.associate { it.id to PageRuntime(it) }),
            addedPageCount = additions.size,
        )
    }

    private fun owned(state: ViewerState, token: EpisodeOperationToken): EpisodeAppendRuntime? {
        if (token.generation != state.generation) return null
        return state.episodeAppends[token.fromEpisodeId]?.takeIf { it.owner == token }
    }

    private fun deadline(nowNanos: Long, delayNanos: Long): Long =
        if (Long.MAX_VALUE - nowNanos < delayNanos) Long.MAX_VALUE else nowNanos + delayNanos

    private data class AppendedPages(
        val ledger: LayoutLedger,
        val pageOrder: List<PageId>,
        val pages: PersistentMap<PageId, PageRuntime>,
        val addedPageCount: Int,
    )
}
