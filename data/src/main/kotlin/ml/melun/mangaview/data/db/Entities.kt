package ml.melun.mangaview.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "raw_pages")
data class RawPageEntity(
    @androidx.room.PrimaryKey val cacheKey: String,
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
    tableName = "reading_progress",
    primaryKeys = ["sourceKey", "seriesKey"],
)
data class ReadingProgressEntity(
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val offsetInPageUnits: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "library_entries",
    primaryKeys = ["sourceKey", "seriesKey"],
    indices = [Index(value = ["updatedAtEpochMillis"])],
)
data class LibraryEntryEntity(
    val sourceKey: String,
    val seriesKey: String,
    val title: String,
    val thumbnailKey: String?,
    val favorite: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["sourceKey", "seriesKey", "episodeKey", "pageKey"],
)
data class BookmarkEntity(
    val sourceKey: String,
    val seriesKey: String,
    val episodeKey: String,
    val pageKey: String,
    val offsetInPageUnits: Long,
    val createdAtEpochMillis: Long,
)
