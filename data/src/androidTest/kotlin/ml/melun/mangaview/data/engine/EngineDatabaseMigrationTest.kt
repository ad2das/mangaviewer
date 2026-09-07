package ml.melun.mangaview.data.engine

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.db.ViewerDatabaseFactory
import ml.melun.mangaview.engine.api.SourceAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDatabaseMigrationTest {
    @Test
    fun schemaOneMigrationPreservesLegacyRowsAndPersistsEngineAnchors() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "engine-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createSchemaOne(context.getDatabasePath(databaseName))
            val database = ViewerDatabaseFactory(databaseName).open(context)
            try {
                assertLegacyRows(database)
                assertEngineTablesExist(database)
                assertNull(database.engine().readingAnchor("legacy-source", "legacy-series"))

                val episode = EpisodeId(SeriesId(SourceId("engine-source"), "engine-series"), "episode-2")
                val page = PageId(episode, "page-7")
                val anchor = SourceAnchor(page, sourceYQ32 = 7_000L, viewportOffsetUnits = 91L)
                val store = EnginePositionStore({ database }, Dispatchers.IO) { 12_345L }
                val legacyEpisode = EpisodeId(
                    SeriesId(SourceId("legacy-source"), "legacy-series"),
                    "legacy-episode",
                )
                assertNull(store.load(legacyEpisode))

                store.save(anchor, legacyScreenOffsetUnits = 777L)
                assertEquals(anchor, store.load(episode))
                val progress = checkNotNull(database.viewer().progress("engine-source", "engine-series"))
                assertEquals("episode-2", progress.episodeKey)
                assertEquals("page-7", progress.pageKey)
                assertEquals(777L, progress.offsetInPageUnits)
                assertEquals(12_345L, progress.updatedAtEpochMillis)
                database.viewer().saveProgress(progress.copy(updatedAtEpochMillis = 12_346L))
                assertNull(store.load(episode))
                database.viewer().saveProgress(progress)
                assertEquals(anchor, store.load(episode))
                database.viewer().saveProgress(progress.copy(offsetInPageUnits = 778L))
                assertNull(store.load(episode))
                database.viewer().saveProgress(progress)
                assertEquals(anchor, store.load(episode))

                store.saveBookmark(anchor, legacyScreenOffsetUnits = 888L)
                assertEquals(anchor, store.loadBookmark(page))
                val bookmark = checkNotNull(database.engine().legacyBookmark(
                    "engine-source",
                    "engine-series",
                    "episode-2",
                    "page-7",
                ))
                assertEquals(888L, bookmark.offsetInPageUnits)
                assertEquals(12_345L, bookmark.createdAtEpochMillis)
                database.viewer().saveBookmark(bookmark.copy(createdAtEpochMillis = 12_346L))
                assertNull(store.loadBookmark(page))
                database.viewer().saveBookmark(bookmark)
                assertEquals(anchor, store.loadBookmark(page))
                database.viewer().saveBookmark(bookmark.copy(offsetInPageUnits = 889L))
                assertNull(store.loadBookmark(page))
                database.viewer().saveBookmark(bookmark)
                assertEquals(anchor, store.loadBookmark(page))
            } finally {
                database.close()
            }

            val reopened = ViewerDatabaseFactory(databaseName).open(context)
            try {
                val store = EnginePositionStore({ reopened }, Dispatchers.IO) { 99L }
                val episode = EpisodeId(SeriesId(SourceId("engine-source"), "engine-series"), "episode-2")
                val page = PageId(episode, "page-7")
                assertEquals(
                    SourceAnchor(page, sourceYQ32 = 7_000L, viewportOffsetUnits = 91L),
                    store.load(episode),
                )
                assertEquals(
                    SourceAnchor(page, sourceYQ32 = 7_000L, viewportOffsetUnits = 91L),
                    store.loadBookmark(page),
                )
                assertLegacyRows(reopened, expectedProgressCount = 2L, expectedBookmarkCount = 2L)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun assertLegacyRows(
        database: ml.melun.mangaview.data.db.ViewerDatabase,
        expectedProgressCount: Long = 1L,
        expectedBookmarkCount: Long = 1L,
    ) {
        val raw = database.rawPages().find("legacy-cache-marker")
        assertNotNull(raw)
        assertEquals("legacy-source", raw?.sourceKey)
        assertEquals("legacy-path/marker.bin", raw?.relativePath)
        assertEquals(19L, raw?.byteCount)

        val progress = database.viewer().progress("legacy-source", "legacy-series")
        assertNotNull(progress)
        assertEquals("legacy-episode", progress?.episodeKey)
        assertEquals("legacy-page", progress?.pageKey)
        assertEquals(123L, progress?.offsetInPageUnits)

        val library = database.viewer().libraryEntry("legacy-source", "legacy-series")
        assertNotNull(library)
        assertTrue(library?.favorite == true)
        assertEquals("Legacy title", library?.title)

        val bookmark = database.engine().legacyBookmark(
            "legacy-source",
            "legacy-series",
            "legacy-episode",
            "legacy-page",
        )
        assertNotNull(bookmark)
        assertEquals(321L, bookmark?.offsetInPageUnits)

        val rawCount = database.openHelper.readableDatabase.scalarCount("raw_pages")
        val progressCount = database.openHelper.readableDatabase.scalarCount("reading_progress")
        val libraryCount = database.openHelper.readableDatabase.scalarCount("library_entries")
        val bookmarkCount = database.openHelper.readableDatabase.scalarCount("bookmarks")
        assertEquals(1L, rawCount)
        assertEquals(expectedProgressCount, progressCount)
        assertEquals(1L, libraryCount)
        assertEquals(expectedBookmarkCount, bookmarkCount)
    }

    private fun assertEngineTablesExist(database: ml.melun.mangaview.data.db.ViewerDatabase) {
        val names = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).use { cursor ->
            buildSet {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
        assertTrue("engine_reading_anchors" in names)
        assertTrue("engine_bookmark_anchors" in names)
        assertTrue("engine_pages" in names)
        assertTrue("engine_publications" in names)
    }

    private fun createSchemaOne(file: File) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `raw_pages` (" +
                    "`cacheKey` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, " +
                    "`seriesKey` TEXT NOT NULL, `episodeKey` TEXT NOT NULL, " +
                    "`pageKey` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                    "`byteCount` INTEGER NOT NULL, `sha256` TEXT NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, `widthPx` INTEGER NOT NULL, " +
                    "`heightPx` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, " +
                    "`lastAccessEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_progress` (" +
                    "`sourceKey` TEXT NOT NULL, `seriesKey` TEXT NOT NULL, " +
                    "`episodeKey` TEXT NOT NULL, `pageKey` TEXT NOT NULL, " +
                    "`offsetInPageUnits` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sourceKey`, `seriesKey`))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `library_entries` (" +
                    "`sourceKey` TEXT NOT NULL, `seriesKey` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `thumbnailKey` TEXT, `favorite` INTEGER NOT NULL, " +
                    "`updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`sourceKey`, `seriesKey`))",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_library_entries_updatedAtEpochMillis` " +
                    "ON `library_entries` (`updatedAtEpochMillis`)",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                    "`sourceKey` TEXT NOT NULL, `seriesKey` TEXT NOT NULL, " +
                    "`episodeKey` TEXT NOT NULL, `pageKey` TEXT NOT NULL, " +
                    "`offsetInPageUnits` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sourceKey`, `seriesKey`, `episodeKey`, `pageKey`))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, 'f5560161a74b913edfeede96df3433f0')",
            )
            database.version = 1

            database.insertOrThrow(
                "raw_pages",
                null,
                ContentValues().apply {
                    put("cacheKey", "legacy-cache-marker")
                    put("sourceKey", "legacy-source")
                    put("seriesKey", "legacy-series")
                    put("episodeKey", "legacy-episode")
                    put("pageKey", "legacy-page")
                    put("relativePath", "legacy-path/marker.bin")
                    put("byteCount", 19L)
                    put("sha256", "a".repeat(64))
                    put("mediaType", "image/png")
                    put("widthPx", 10)
                    put("heightPx", 20)
                    put("createdAtEpochMillis", 100L)
                    put("lastAccessEpochMillis", 200L)
                },
            )
            database.insertOrThrow(
                "reading_progress",
                null,
                ContentValues().apply {
                    put("sourceKey", "legacy-source")
                    put("seriesKey", "legacy-series")
                    put("episodeKey", "legacy-episode")
                    put("pageKey", "legacy-page")
                    put("offsetInPageUnits", 123L)
                    put("updatedAtEpochMillis", 456L)
                },
            )
            database.insertOrThrow(
                "library_entries",
                null,
                ContentValues().apply {
                    put("sourceKey", "legacy-source")
                    put("seriesKey", "legacy-series")
                    put("title", "Legacy title")
                    putNull("thumbnailKey")
                    put("favorite", 1)
                    put("updatedAtEpochMillis", 789L)
                },
            )
            database.insertOrThrow(
                "bookmarks",
                null,
                ContentValues().apply {
                    put("sourceKey", "legacy-source")
                    put("seriesKey", "legacy-series")
                    put("episodeKey", "legacy-episode")
                    put("pageKey", "legacy-page")
                    put("offsetInPageUnits", 321L)
                    put("createdAtEpochMillis", 654L)
                },
            )
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalarCount(table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
