package ml.melun.mangaview.data.offline

import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

data class DownloadedEpisode(
    val series: SourceSeries,
    val episode: SourceEpisode,
    val pageCount: Int,
    val byteCount: Long,
    val savedAtEpochMillis: Long,
)

internal data class StoredEpisode(
    val summary: DownloadedEpisode,
    val manifest: EpisodeManifest,
    val pages: Map<PageId, CachedPage>,
)
