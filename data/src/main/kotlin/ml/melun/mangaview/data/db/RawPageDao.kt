package ml.melun.mangaview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RawPageDao {
    @Query("SELECT * FROM raw_pages WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun find(cacheKey: String): RawPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RawPageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RawPageEntity>)

    @Query("UPDATE raw_pages SET lastAccessEpochMillis = :atMillis WHERE cacheKey = :cacheKey")
    suspend fun touch(cacheKey: String, atMillis: Long)

    @Query("DELETE FROM raw_pages WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)

    @Query("SELECT * FROM raw_pages ORDER BY lastAccessEpochMillis ASC")
    suspend fun oldestFirst(): List<RawPageEntity>

    @Query("SELECT COALESCE(SUM(byteCount), 0) FROM raw_pages")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM raw_pages")
    suspend fun deleteAll()
}
