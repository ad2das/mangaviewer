package ml.melun.mangaview.data.db

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opens Room only from the supplied IO dispatcher, never while AppGraph is built on main. */
class DeferredViewerDatabase(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val factory: ViewerDatabaseFactory = ViewerDatabaseFactory(),
) {
    private val appContext = context.applicationContext
    private val openMutex = Mutex()
    @Volatile
    private var opened: ViewerDatabase? = null

    val rawPages: RawPageDao = DeferredRawPageDao(::database)
    val viewer: ViewerDao = DeferredViewerDao(::database, ioDispatcher)

    private suspend fun database(): ViewerDatabase = opened ?: withContext(ioDispatcher) {
        openMutex.withLock {
            opened ?: factory.open(appContext).also { opened = it }
        }
    }
}

private class DeferredRawPageDao(
    private val database: suspend () -> ViewerDatabase,
) : RawPageDao {
    override suspend fun find(cacheKey: String): RawPageEntity? = database().rawPages().find(cacheKey)

    override suspend fun upsert(entity: RawPageEntity) = database().rawPages().upsert(entity)

    override suspend fun upsertAll(entities: List<RawPageEntity>) =
        database().rawPages().upsertAll(entities)

    override suspend fun touch(cacheKey: String, atMillis: Long) =
        database().rawPages().touch(cacheKey, atMillis)

    override suspend fun delete(cacheKey: String) = database().rawPages().delete(cacheKey)

    override suspend fun oldestFirst(): List<RawPageEntity> = database().rawPages().oldestFirst()

    override suspend fun totalBytes(): Long = database().rawPages().totalBytes()

    override suspend fun deleteAll() = database().rawPages().deleteAll()
}

private class DeferredViewerDao(
    private val database: suspend () -> ViewerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewerDao {
    override suspend fun saveProgress(progress: ReadingProgressEntity) =
        database().viewer().saveProgress(progress)

    override suspend fun progress(sourceKey: String, seriesKey: String): ReadingProgressEntity? =
        database().viewer().progress(sourceKey, seriesKey)

    override fun progressHistory(): Flow<List<ReadingProgressEntity>> =
        deferredFlow { dao -> dao.progressHistory() }

    override suspend fun saveLibraryEntry(entry: LibraryEntryEntity) =
        database().viewer().saveLibraryEntry(entry)

    override fun library(): Flow<List<LibraryEntryEntity>> = deferredFlow { dao -> dao.library() }

    override suspend fun libraryEntry(sourceKey: String, seriesKey: String): LibraryEntryEntity? =
        database().viewer().libraryEntry(sourceKey, seriesKey)

    override suspend fun recordOpened(
        entry: LibraryEntryEntity,
        initialProgress: ReadingProgressEntity,
    ) = database().viewer().recordOpened(entry, initialProgress)

    override suspend fun saveBookmark(bookmark: BookmarkEntity) =
        database().viewer().saveBookmark(bookmark)

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) =
        database().viewer().deleteBookmark(bookmark)

    override fun bookmarks(): Flow<List<BookmarkEntity>> = deferredFlow { dao -> dao.bookmarks() }

    private fun <T> deferredFlow(block: (ViewerDao) -> Flow<T>): Flow<T> = flow {
        emitAll(block(database().viewer()))
    }.flowOn(ioDispatcher)
}
