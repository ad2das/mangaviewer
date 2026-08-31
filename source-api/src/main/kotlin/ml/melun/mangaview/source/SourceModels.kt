package ml.melun.mangaview.source

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId

data class SourceSeries(
    val id: SeriesId,
    val title: String,
    val subtitle: String? = null,
    val thumbnailKey: String? = null,
)

enum class SeriesKind {
    WEBTOON,
    COMIC,
}

enum class CatalogOrder {
    LATEST,
    POPULAR,
    NEW,
}

data class CatalogQuery(
    val kind: SeriesKind,
    val order: CatalogOrder,
    val genre: String? = null,
    val cursor: String? = null,
)

data class SourceEpisode(
    val id: EpisodeId,
    val title: String,
    val publishedAtEpochMillis: Long? = null,
    val pageCountHint: Int? = null,
) {
    init {
        require(pageCountHint == null || pageCountHint > 0) { "Page count hint must be positive" }
    }
}

data class SourcePage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
) {
    init {
        require(nextCursor == null || nextCursor.isNotBlank()) { "Cursor must not be blank" }
    }
}

data class AdjacentEpisodes(
    val previous: EpisodeId?,
    val next: EpisodeId?,
)

enum class PreparationIntent {
    INITIAL_VIEW,
    ADJACENT_FORWARD,
    ADJACENT_REVERSE,
}
