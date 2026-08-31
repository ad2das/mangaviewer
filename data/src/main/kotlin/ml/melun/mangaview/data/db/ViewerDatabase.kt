package ml.melun.mangaview.data.db

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawPageEntity::class,
        ReadingProgressEntity::class,
        LibraryEntryEntity::class,
        BookmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ViewerDatabase : RoomDatabase() {
    abstract fun rawPages(): RawPageDao

    abstract fun viewer(): ViewerDao
}

class ViewerDatabaseFactory(
    private val databaseName: String = DATABASE_NAME,
) {
    @WorkerThread
    fun open(context: Context): ViewerDatabase = Room.databaseBuilder(
        context.applicationContext,
        ViewerDatabase::class.java,
        databaseName,
    ).build()

    companion object {
        const val DATABASE_NAME = "mangaviewer_v2.db"
    }
}
