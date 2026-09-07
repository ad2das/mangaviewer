package ml.melun.mangaview.data.db

import androidx.room.Entity

@Entity(
    tableName = "engine_reading_anchors",
    primaryKeys = ["sourceKey", "seriesKey"],
)
data class EngineReadingAnchorEntity(
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val sourceYQ32: Long,
    val viewportOffsetUnits: Long,
    val legacyScreenOffsetUnits: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "engine_bookmark_anchors",
    primaryKeys = ["sourceKey", "seriesKey", "episodeKey", "pageKey"],
)
data class EngineBookmarkAnchorEntity(
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val sourceYQ32: Long,
    val viewportOffsetUnits: Long,
    val legacyScreenOffsetUnits: Long,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "engine_pages",
    primaryKeys = ["cacheKey", "contentRevision"],
)
data class EnginePageEntity(
    val cacheKey: String,
    val contentRevision: String,
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val relativePath: String,
    val byteCount: Long,
    val sha256: String,
    val mediaType: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAtEpochMillis: Long,
    val lastAccessEpochMillis: Long,
)

@Entity(
    tableName = "engine_publications",
    primaryKeys = ["publicationId"],
)
data class EnginePublicationEntity(
    val publicationId: String,
    val cacheKey: String,
    val contentRevision: String,
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val stagingRelativePath: String,
    val destinationRelativePath: String,
    val byteCount: Long,
    val sha256: String,
    val mediaType: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAtEpochMillis: Long,
)
