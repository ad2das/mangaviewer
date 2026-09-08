package ml.melun.mangaview.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Only user library metadata is backed up; image caches never leave the device. */
data class CloudLibrarySnapshot(
    val series: List<LibraryEntryEntity>,
    val progress: List<ReadingProgressEntity>,
    val bookmarks: List<BookmarkEntity>,
    val readingAnchors: List<EngineReadingAnchorEntity>,
    val bookmarkAnchors: List<EngineBookmarkAnchorEntity>,
)

@Dao
interface CloudLibraryDao {
    @Query("SELECT * FROM library_entries")
    suspend fun series(): List<LibraryEntryEntity>

    @Query("SELECT * FROM reading_progress")
    suspend fun progress(): List<ReadingProgressEntity>

    @Query("SELECT * FROM bookmarks")
    suspend fun bookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM engine_reading_anchors")
    suspend fun readingAnchors(): List<EngineReadingAnchorEntity>

    @Query("SELECT * FROM engine_bookmark_anchors")
    suspend fun bookmarkAnchors(): List<EngineBookmarkAnchorEntity>

    @Query("SELECT * FROM engine_reading_anchors")
    fun readingAnchorChanges(): Flow<List<EngineReadingAnchorEntity>>

    @Query("SELECT * FROM engine_bookmark_anchors")
    fun bookmarkAnchorChanges(): Flow<List<EngineBookmarkAnchorEntity>>

    @Query("DELETE FROM library_entries WHERE sourceKey = :source AND seriesKey = :series")
    suspend fun deleteSeries(source: String, series: String)

    @Query("DELETE FROM reading_progress WHERE sourceKey = :source AND seriesKey = :series")
    suspend fun deleteProgress(source: String, series: String)

    @Transaction
    suspend fun snapshot() = CloudLibrarySnapshot(series(), progress(), bookmarks(), readingAnchors(), bookmarkAnchors())
}
