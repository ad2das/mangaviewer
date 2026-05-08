package ml.melun.mangaview.repository.room;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                LibraryTitleEntity.class,
                BookmarkEntity.class,
                ViewerBookmarkEntity.class,
                OfflineIndexEntity.class,
                CacheEntryEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class MangaStoreDatabase extends RoomDatabase {
    private static volatile MangaStoreDatabase instance;

    public abstract MangaStoreDao dao();

    public static MangaStoreDatabase get(Context context) {
        if(instance == null) {
            synchronized (MangaStoreDatabase.class) {
                if(instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    MangaStoreDatabase.class,
                                    "mangaviewer_store.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
