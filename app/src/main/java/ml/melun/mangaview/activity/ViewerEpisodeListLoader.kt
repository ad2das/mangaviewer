package ml.melun.mangaview.activity

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode

internal class ViewerEpisodeListLoader(private val source: ContentSource) {
    suspend fun load(seriesId: SeriesId): List<SourceEpisode> {
        val episodes = LinkedHashMap<ml.melun.mangaview.core.EpisodeId, SourceEpisode>()
        val visitedCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = source.episodes(seriesId, cursor)
            page.items.forEach { episode ->
                require(episode.id.seriesId == seriesId) { "Episode list crossed into another series" }
                episodes.putIfAbsent(episode.id, episode)
            }
            cursor = page.nextCursor?.takeIf(visitedCursors::add)
        } while (cursor != null)
        return episodes.values.toList()
    }
}
