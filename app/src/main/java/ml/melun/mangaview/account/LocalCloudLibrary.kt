package ml.melun.mangaview.account

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import ml.melun.mangaview.data.db.ViewerDatabase

internal interface CloudLocalPort {
    val changes: Flow<Unit>
    suspend fun snapshot(): List<CloudLibraryRecord>
    suspend fun restore(remote: List<CloudLibraryRecord>, beforeDownload: List<CloudLibraryRecord>, now: Long): List<CloudLibraryRecord>
}

internal class LocalCloudLibrary(private val database: suspend () -> ViewerDatabase) : CloudLocalPort {
    override val changes: Flow<Unit> = flow {
        val db = database()
        emitAll(combine(db.viewer().library(), db.viewer().progressHistory(), db.viewer().bookmarks(),
            db.cloudLibrary().readingAnchorChanges(), db.cloudLibrary().bookmarkAnchorChanges()) { _, _, _, _, _ -> Unit })
    }

    override suspend fun snapshot(): List<CloudLibraryRecord> = CloudLibraryCodec.snapshot(database().cloudLibrary().snapshot())

    /** Re-read within the write transaction so a download cannot overwrite a new local reading position. */
    override suspend fun restore(remote: List<CloudLibraryRecord>, beforeDownload: List<CloudLibraryRecord>, now: Long): List<CloudLibraryRecord> {
        remote.forEach(CloudLibraryCodec::validate)
        val db = database()
        return db.withTransaction {
            val local = snapshot()
            val current = local.associateBy { it.identity }
            val merged = CloudLibraryRecords.mergeAfterDownload(remote, beforeDownload, local, now)
            merged.filter { current[it.identity] != it }
                .sortedBy { if (it.kind == "favorite") 1 else 0 }
                .forEach { apply(db, it) }
            merged
        }
    }

    private suspend fun apply(db: ViewerDatabase, record: CloudLibraryRecord) {
        when (record.kind) {
            "series" -> {
                val value = CloudLibraryCodec.series(record)
                if (record.deleted) db.cloudLibrary().deleteSeries(value.sourceKey, value.seriesKey)
                else {
                    val existing = db.viewer().libraryEntry(value.sourceKey, value.seriesKey)
                    db.viewer().saveLibraryEntry(value.copy(favorite = existing?.favorite ?: false))
                }
            }
            "favorite" -> {
                val source = record.payload.get("source").asString
                val series = record.payload.get("series").asString
                db.viewer().libraryEntry(source, series)?.let {
                    db.viewer().saveLibraryEntry(it.copy(favorite = !record.deleted))
                }
            }
            "progress" -> {
                val value = CloudLibraryCodec.progress(record)
                db.engine().deleteReadingAnchor(value.sourceKey, value.seriesKey)
                if (record.deleted) db.cloudLibrary().deleteProgress(value.sourceKey, value.seriesKey)
                else {
                    db.viewer().saveProgress(value)
                    CloudLibraryCodec.readingAnchor(record)?.let { db.engine().upsertReadingAnchor(it) }
                }
            }
            "bookmark" -> {
                val value = CloudLibraryCodec.bookmark(record)
                db.engine().deleteBookmarkAnchor(value.sourceKey, value.seriesKey, value.episodeKey, value.pageKey)
                if (record.deleted) db.viewer().deleteBookmark(value)
                else {
                    db.viewer().saveBookmark(value)
                    CloudLibraryCodec.bookmarkAnchor(record)?.let { db.engine().upsertBookmarkAnchor(it) }
                }
            }
        }
    }
}
