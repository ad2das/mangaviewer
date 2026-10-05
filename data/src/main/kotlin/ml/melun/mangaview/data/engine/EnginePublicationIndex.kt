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

    /** Applies every pending last-access hint; implementations that can must batch it. */
    suspend fun touchAll(pages: List<EnginePageEntity>) {
        for (page in pages) touch(page, page.lastAccessEpochMillis)
    }

    /** Stages every journal of a batch; implementations that can must do it in one transaction. */
    suspend fun stageAll(journals: List<EnginePublicationEntity>) {
        journals.forEach { stage(it) }
    }

    /** Commits every entry of a batch at once; the in-transaction immutability check still applies. */
    suspend fun commitAll(entries: List<Pair<String, EnginePageEntity>>) {
        entries.forEach { (journalId, page) -> commit(journalId, page) }
    }

    /**
     * Commits a batch and applies its pending last-access hints in the same store transaction, so
     * flushing touches never opens a second writer transaction beside the commit.
     */
    suspend fun commitAll(entries: List<Pair<String, EnginePageEntity>>, touches: List<EnginePageEntity>) {
        commitAll(entries)
        touchAll(touches)
    }
}

class RoomEnginePublicationIndex(
    private val database: suspend () -> ViewerDatabase,
) : EnginePublicationIndex {
    override suspend fun page(cacheKey: String, revision: String) = database().engine().page(cacheKey, revision)
    override suspend fun pages() = database().engine().oldestPages()
    override suspend fun journals() = database().engine().publications()
    override suspend fun stage(journal: EnginePublicationEntity) = database().engine().upsertPublication(journal)

    override suspend fun stageAll(journals: List<EnginePublicationEntity>) {
        val db = database()
        db.withTransaction { journals.forEach { db.engine().upsertPublication(it) } }
    }

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

    override suspend fun commitAll(entries: List<Pair<String, EnginePageEntity>>) = commitAll(entries, emptyList())

    override suspend fun commitAll(entries: List<Pair<String, EnginePageEntity>>, touches: List<EnginePageEntity>) {
        val db = database()
        db.withTransaction {
            // Flush the pending last-access hints first: a commit below may re-publish one of the
            // same rows with a newer timestamp, and that newer value must win over the older hint.
            if (touches.isNotEmpty()) db.engine().updatePages(touches)
            for ((journalId, page) in entries) {
                val previous = db.engine().page(page.cacheKey, page.contentRevision)
                check(previous == null || previous.copy(lastAccessEpochMillis = page.lastAccessEpochMillis,
                    createdAtEpochMillis = page.createdAtEpochMillis) == page) { "Immutable publication changed" }
                db.engine().upsertPage(page)
                db.engine().deletePublication(journalId)
            }
        }
    }

    override suspend fun forgetJournal(journalId: String) = database().engine().deletePublication(journalId)
    override suspend fun remove(page: EnginePageEntity) = database().engine().deletePage(page.cacheKey, page.contentRevision)
    override suspend fun touch(page: EnginePageEntity, timeMillis: Long) =
        database().engine().touchPage(page.cacheKey, page.contentRevision, timeMillis)
    override suspend fun touchAll(pages: List<EnginePageEntity>) {
        if (pages.isNotEmpty()) database().engine().updatePages(pages)
    }
}
