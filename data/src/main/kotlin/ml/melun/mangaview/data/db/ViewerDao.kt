package ml.melun.mangaview.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey")
    suspend fun progress(sourceKey: String, seriesKey: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updatedAtEpochMillis DESC")
    fun progressHistory(): Flow<List<ReadingProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLibraryEntry(entry: LibraryEntryEntity)

    @Query("SELECT * FROM library_entries ORDER BY updatedAtEpochMillis DESC")
    fun library(): Flow<List<LibraryEntryEntity>>

    @Query("SELECT * FROM library_entries WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey")
    suspend fun libraryEntry(sourceKey: String, seriesKey: String): LibraryEntryEntity?

    @Transaction
    suspend fun recordOpened(entry: LibraryEntryEntity, initialProgress: ReadingProgressEntity) {
        val existingEntry = libraryEntry(entry.sourceKey, entry.seriesKey)
        saveLibraryEntry(entry.copy(favorite = existingEntry?.favorite ?: entry.favorite))
        val existingProgress = progress(entry.sourceKey, entry.seriesKey)
        val nextProgress = if (existingProgress?.episodeKey == initialProgress.episodeKey) {
            touchMatchingReadingAnchor(entry.sourceKey, entry.seriesKey, initialProgress.updatedAtEpochMillis)
            existingProgress.copy(updatedAtEpochMillis = initialProgress.updatedAtEpochMillis)
        } else {
            initialProgress
        }
        saveProgress(nextProgress)
    }

    /** Refresh recency without invalidating a matching source-coordinate resume anchor. */
    @Query("UPDATE engine_reading_anchors SET updatedAtEpochMillis = :at WHERE sourceKey = :source AND seriesKey = :series " +
        "AND EXISTS (SELECT 1 FROM reading_progress p WHERE p.sourceKey = engine_reading_anchors.sourceKey " +
        "AND p.seriesKey = engine_reading_anchors.seriesKey AND p.episodeKey = engine_reading_anchors.episodeKey " +
        "AND p.pageKey = engine_reading_anchors.pageKey AND p.offsetInPageUnits = engine_reading_anchors.legacyScreenOffsetUnits " +
        "AND p.updatedAtEpochMillis = engine_reading_anchors.updatedAtEpochMillis)")
    suspend fun touchMatchingReadingAnchor(source: String, series: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks ORDER BY createdAtEpochMillis DESC")
    fun bookmarks(): Flow<List<BookmarkEntity>>
}
