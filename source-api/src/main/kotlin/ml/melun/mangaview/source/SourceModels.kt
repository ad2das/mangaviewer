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

enum class SearchField {
    TITLE,
    AUTHOR,
}

data class SourceSearchQuery(
    val text: String,
    val kind: SeriesKind? = null,
    val field: SearchField = SearchField.TITLE,
    val cursor: String? = null,
) {
    init {
        require(text.isNotBlank()) { "Search text must not be blank" }
    }
}

enum class CatalogOrder {
    LATEST,
    POPULAR,
    NEW,
}

data class SourceGenre(
    val key: String,
    val label: String,
) {
    init {
        require(key.isNotBlank()) { "Genre key must not be blank" }
        require(label.isNotBlank()) { "Genre label must not be blank" }
    }
}

data class CatalogQuery(
    val kind: SeriesKind,
    val order: CatalogOrder,
    val genre: SourceGenre? = null,
    val cursor: String? = null,
)

data class SourceEpisode(
    val id: EpisodeId,
    val title: String,
    val publishedAtEpochMillis: Long? = null,
    val pageCountHint: Int? = null,
    val sequenceNumber: Double? = null,
) {
    init {
        require(pageCountHint == null || pageCountHint > 0) { "Page count hint must be positive" }
        require(sequenceNumber == null || sequenceNumber.isFinite()) {
            "Episode sequence number must be finite"
        }
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
