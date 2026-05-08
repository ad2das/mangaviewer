package ml.melun.mangaview.repository.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public abstract class MangaStoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertLibraryTitles(List<LibraryTitleEntity> titles);

    @Query("DELETE FROM library_titles WHERE scope = :scope")
    public abstract void clearLibraryScope(String scope);

    @Query("SELECT * FROM library_titles WHERE scope = :scope ORDER BY sortOrder ASC")
    public abstract List<LibraryTitleEntity> libraryTitles(String scope);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertBookmarks(List<BookmarkEntity> bookmarks);

    @Query("DELETE FROM bookmarks")
    public abstract void clearBookmarks();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertViewerBookmarks(List<ViewerBookmarkEntity> bookmarks);

    @Query("DELETE FROM viewer_bookmarks")
    public abstract void clearViewerBookmarks();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertOfflineIndex(List<OfflineIndexEntity> titles);

    @Query("DELETE FROM offline_index")
    public abstract void clearOfflineIndex();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertCache(CacheEntryEntity entry);

    @Query("SELECT * FROM cache_entries WHERE cacheKey = :key LIMIT 1")
    public abstract CacheEntryEntity cacheEntry(String key);

    @Transaction
    public void replaceLibraryScope(String scope, List<LibraryTitleEntity> titles) {
        clearLibraryScope(scope);
        if(titles != null && titles.size() > 0)
            upsertLibraryTitles(titles);
    }
}
