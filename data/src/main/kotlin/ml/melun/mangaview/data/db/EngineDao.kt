package ml.melun.mangaview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EngineDao {
    @Query(
        "SELECT * FROM engine_reading_anchors " +
            "WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey LIMIT 1",
    )
    suspend fun readingAnchor(sourceKey: String, seriesKey: String): EngineReadingAnchorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingAnchor(anchor: EngineReadingAnchorEntity)

    @Query(
        "DELETE FROM engine_reading_anchors " +
            "WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey",
    )
    suspend fun deleteReadingAnchor(sourceKey: String, seriesKey: String)

    @Query(
        "SELECT * FROM engine_bookmark_anchors " +
            "WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey " +
            "AND episodeKey = :episodeKey AND pageKey = :pageKey LIMIT 1",
    )
    suspend fun bookmarkAnchor(
        sourceKey: String,
        seriesKey: String,
        episodeKey: String,
        pageKey: String,
    ): EngineBookmarkAnchorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmarkAnchor(anchor: EngineBookmarkAnchorEntity)

    @Query(
        "DELETE FROM engine_bookmark_anchors " +
            "WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey " +
            "AND episodeKey = :episodeKey AND pageKey = :pageKey",
    )
    suspend fun deleteBookmarkAnchor(
        sourceKey: String,
        seriesKey: String,
        episodeKey: String,
        pageKey: String,
    )

    @Query(
        "SELECT * FROM engine_pages " +
            "WHERE cacheKey = :cacheKey AND contentRevision = :contentRevision LIMIT 1",
    )
    suspend fun page(cacheKey: String, contentRevision: String): EnginePageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: EnginePageEntity)

    @Query("SELECT * FROM engine_pages ORDER BY lastAccessEpochMillis ASC")
    suspend fun oldestPages(): List<EnginePageEntity>

    @Query("UPDATE engine_pages SET lastAccessEpochMillis = :timeMillis " +
        "WHERE cacheKey = :cacheKey AND contentRevision = :revision")
    suspend fun touchPage(cacheKey: String, revision: String, timeMillis: Long)

    @Query(
        "DELETE FROM engine_pages " +
            "WHERE cacheKey = :cacheKey AND contentRevision = :contentRevision",
    )
    suspend fun deletePage(cacheKey: String, contentRevision: String)

    @Query("SELECT * FROM engine_publications WHERE publicationId = :publicationId LIMIT 1")
    suspend fun publication(publicationId: String): EnginePublicationEntity?

    @Query("SELECT * FROM engine_publications ORDER BY createdAtEpochMillis ASC")
    suspend fun publications(): List<EnginePublicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPublication(publication: EnginePublicationEntity)

    @Query("DELETE FROM engine_publications WHERE publicationId = :publicationId")
    suspend fun deletePublication(publicationId: String)

    /** Direct lookup for position transactions; the legacy DAO intentionally stays unchanged. */
    @Query(
        "SELECT * FROM bookmarks WHERE sourceKey = :sourceKey AND seriesKey = :seriesKey " +
            "AND episodeKey = :episodeKey AND pageKey = :pageKey LIMIT 1",
    )
    suspend fun legacyBookmark(
        sourceKey: String,
        seriesKey: String,
        episodeKey: String,
        pageKey: String,
    ): BookmarkEntity?
}
