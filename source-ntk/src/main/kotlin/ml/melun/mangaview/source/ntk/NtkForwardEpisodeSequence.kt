package ml.melun.mangaview.source.ntk

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.source.SourceEpisode

/**
 * NTK catalogs are newest-first and their viewer's `next` direction moves toward index zero.
 * Returning only IDs keeps catalog knowledge out of the viewer and ACK scheduler.
 */
internal fun ntkForwardEpisodeIds(
    episodes: List<SourceEpisode>,
    current: EpisodeId,
    limit: Int,
): List<EpisodeId> {
    require(limit >= 0) { "Forward episode limit must not be negative" }
    val currentIndex = episodes.indexOfFirst { it.id == current }
    if (currentIndex <= 0 || limit == 0) return emptyList()
    val firstIndex = (currentIndex - limit).coerceAtLeast(0)
    return (currentIndex - 1 downTo firstIndex).map { episodes[it].id }
}
