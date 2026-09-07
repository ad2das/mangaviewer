package ml.melun.mangaview.data.engine

import androidx.room.withTransaction
import ml.melun.mangaview.data.db.EnginePageEntity
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.data.db.ViewerDatabase

/** Database operations needed by the publication protocol; commit must be atomic. */
interface EnginePublicationIndex {
    suspend fun page(cacheKey: String, revision: String): EnginePageEntity?
    suspend fun pages(): List<EnginePageEntity>
    suspend fun journals(): List<EnginePublicationEntity>
    suspend fun stage(journal: EnginePublicationEntity)
    suspend fun commit(journalId: String, page: EnginePageEntity)
    suspend fun forgetJournal(journalId: String)
    suspend fun remove(page: EnginePageEntity)
    suspend fun touch(page: EnginePageEntity, timeMillis: Long)
}

class RoomEnginePublicationIndex(
    private val database: suspend () -> ViewerDatabase,
) : EnginePublicationIndex {
    override suspend fun page(cacheKey: String, revision: String) = database().engine().page(cacheKey, revision)
    override suspend fun pages() = database().engine().oldestPages()
    override suspend fun journals() = database().engine().publications()
    override suspend fun stage(journal: EnginePublicationEntity) = database().engine().upsertPublication(journal)

    override suspend fun commit(journalId: String, page: EnginePageEntity) {
        val db = database()
        db.withTransaction {
            val previous = db.engine().page(page.cacheKey, page.contentRevision)
            check(previous == null || previous.copy(lastAccessEpochMillis = page.lastAccessEpochMillis,
                createdAtEpochMillis = page.createdAtEpochMillis) == page) { "Immutable publication changed" }
            db.engine().upsertPage(page)
            db.engine().deletePublication(journalId)
        }
    }

    override suspend fun forgetJournal(journalId: String) = database().engine().deletePublication(journalId)
    override suspend fun remove(page: EnginePageEntity) = database().engine().deletePage(page.cacheKey, page.contentRevision)
    override suspend fun touch(page: EnginePageEntity, timeMillis: Long) =
        database().engine().touchPage(page.cacheKey, page.contentRevision, timeMillis)
}
