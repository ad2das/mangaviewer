package ml.melun.mangaview.core

@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

data class SeriesId(
    val sourceId: SourceId,
    val remoteKey: String,
) {
    init {
        require(remoteKey.isNotBlank()) { "Series remote key must not be blank" }
    }
}

data class EpisodeId(
    val seriesId: SeriesId,
    val remoteKey: String,
) {
    init {
        require(remoteKey.isNotBlank()) { "Episode remote key must not be blank" }
    }
}

data class PageId(
    val episodeId: EpisodeId,
    val remoteKey: String,
) {
    init {
        require(remoteKey.isNotBlank()) { "Page remote key must not be blank" }
    }

    companion object {
        fun at(episodeId: EpisodeId, ordinal: Int): PageId {
            require(ordinal >= 0) { "Page ordinal must not be negative" }
            return PageId(episodeId, "p${ordinal.toString().padStart(4, '0')}")
        }
    }
}
