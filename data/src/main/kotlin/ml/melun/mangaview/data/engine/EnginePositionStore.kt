package ml.melun.mangaview.data.engine

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.db.BookmarkEntity
import ml.melun.mangaview.data.db.EngineBookmarkAnchorEntity
import ml.melun.mangaview.data.db.EngineReadingAnchorEntity
import ml.melun.mangaview.data.db.ReadingProgressEntity
import ml.melun.mangaview.data.db.ViewerDatabase
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.EnginePositionPort

class EnginePositionStore(
    private val database: suspend () -> ViewerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : EnginePositionPort {
    override suspend fun load(episodeId: EpisodeId): SourceAnchor? = withContext(ioDispatcher) {
        val db = database()
        val sourceKey = episodeId.seriesId.sourceId.value
        val seriesKey = episodeId.seriesId.remoteKey
        val stored = db.engine().readingAnchor(sourceKey, seriesKey) ?: return@withContext null
        if (stored.episodeKey != episodeId.remoteKey) return@withContext null

        val legacy = db.viewer().progress(sourceKey, seriesKey) ?: return@withContext null
        if (legacy.episodeKey != stored.episodeKey ||
            legacy.pageKey != stored.pageKey ||
            legacy.offsetInPageUnits != stored.legacyScreenOffsetUnits ||
            legacy.updatedAtEpochMillis != stored.updatedAtEpochMillis
        ) {
            return@withContext null
        }

        SourceAnchor(
            pageId = PageId(episodeId, stored.pageKey),
            sourceYQ32 = stored.sourceYQ32,
            viewportOffsetUnits = stored.viewportOffsetUnits,
        )
    }

    override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) {
        require(legacyScreenOffsetUnits >= 0L) {
            "Legacy screen offset must not be negative"
        }
        withContext(ioDispatcher) {
            val pageId = anchor.pageId
            val episodeId = pageId.episodeId
            val sourceKey = episodeId.seriesId.sourceId.value
            val seriesKey = episodeId.seriesId.remoteKey
            val timestamp = nowMillis()
            val db = database()
            db.withTransaction {
                db.viewer().saveProgress(
                    ReadingProgressEntity(
                        sourceKey = sourceKey,
                        seriesKey = seriesKey,
                        episodeKey = episodeId.remoteKey,
                        pageKey = pageId.remoteKey,
                        offsetInPageUnits = legacyScreenOffsetUnits,
                        updatedAtEpochMillis = timestamp,
                    ),
                )
                db.engine().upsertReadingAnchor(
                    EngineReadingAnchorEntity(
                        sourceKey = sourceKey,
                        seriesKey = seriesKey,
                        episodeKey = episodeId.remoteKey,
                        pageKey = pageId.remoteKey,
                        sourceYQ32 = anchor.sourceYQ32,
                        viewportOffsetUnits = anchor.viewportOffsetUnits,
                        legacyScreenOffsetUnits = legacyScreenOffsetUnits,
                        updatedAtEpochMillis = timestamp,
                    ),
                )
            }
        }
    }

    suspend fun loadBookmark(pageId: PageId): SourceAnchor? = withContext(ioDispatcher) {
        val db = database()
        val episodeId = pageId.episodeId
        val sourceKey = episodeId.seriesId.sourceId.value
        val seriesKey = episodeId.seriesId.remoteKey
        val episodeKey = episodeId.remoteKey
        val pageKey = pageId.remoteKey
        val legacy = db.engine().legacyBookmark(sourceKey, seriesKey, episodeKey, pageKey)
            ?: return@withContext null
        val stored = db.engine().bookmarkAnchor(sourceKey, seriesKey, episodeKey, pageKey)
            ?: return@withContext null
        if (legacy.offsetInPageUnits != stored.legacyScreenOffsetUnits ||
            legacy.createdAtEpochMillis != stored.createdAtEpochMillis
        ) {
            return@withContext null
        }
        SourceAnchor(
            pageId = pageId,
            sourceYQ32 = stored.sourceYQ32,
            viewportOffsetUnits = stored.viewportOffsetUnits,
        )
    }

    suspend fun saveBookmark(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) {
        require(legacyScreenOffsetUnits >= 0L) {
            "Legacy screen offset must not be negative"
        }
        withContext(ioDispatcher) {
            val pageId = anchor.pageId
            val episodeId = pageId.episodeId
            val sourceKey = episodeId.seriesId.sourceId.value
            val seriesKey = episodeId.seriesId.remoteKey
            val timestamp = nowMillis()
            val db = database()
            db.withTransaction {
                db.viewer().saveBookmark(
                    BookmarkEntity(
                        sourceKey = sourceKey,
                        seriesKey = seriesKey,
                        episodeKey = episodeId.remoteKey,
                        pageKey = pageId.remoteKey,
                        offsetInPageUnits = legacyScreenOffsetUnits,
                        createdAtEpochMillis = timestamp,
                    ),
                )
                db.engine().upsertBookmarkAnchor(
                    EngineBookmarkAnchorEntity(
                        sourceKey = sourceKey,
                        seriesKey = seriesKey,
                        episodeKey = episodeId.remoteKey,
                        pageKey = pageId.remoteKey,
                        sourceYQ32 = anchor.sourceYQ32,
                        viewportOffsetUnits = anchor.viewportOffsetUnits,
                        legacyScreenOffsetUnits = legacyScreenOffsetUnits,
                        createdAtEpochMillis = timestamp,
                    ),
                )
            }
        }
    }
}
