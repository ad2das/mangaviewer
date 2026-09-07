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
        EngineReadingAnchorEntity::class,
        EngineBookmarkAnchorEntity::class,
        EnginePageEntity::class,
        EnginePublicationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ViewerDatabase : RoomDatabase() {
    abstract fun rawPages(): RawPageDao

    abstract fun viewer(): ViewerDao

    abstract fun engine(): EngineDao
}

class ViewerDatabaseFactory(
    private val databaseName: String = DATABASE_NAME,
) {
    @WorkerThread
    fun open(context: Context): ViewerDatabase = Room.databaseBuilder(
        context.applicationContext,
        ViewerDatabase::class.java,
        databaseName,
    ).addMigrations(EngineDatabaseMigration.FROM_1_TO_2).build()

    companion object {
        const val DATABASE_NAME = "mangaviewer_v2.db"
    }
}
