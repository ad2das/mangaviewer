package ml.melun.mangaview.account

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.db.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudLibraryRestoreDeviceTest {
    @Test fun recentMetadataAndFavoriteRestoreIndependentlyAndRemovalSurvivesRefresh() = runBlocking {
        val db = database()
        try {
            val local = LocalCloudLibrary { db }
            val recent = LibraryEntryEntity("wfwf", "comic:12", "Recent title", null, false, 100)
            db.viewer().saveLibraryEntry(recent)
            val cloudFavorite = CloudLibraryCodec.favorite(recent.copy(favorite = true, updatedAtEpochMillis = 10))
            val before = local.snapshot()
            local.restore(before + cloudFavorite, before, 101)
            assertEquals(recent.copy(favorite = true), db.viewer().libraryEntry("wfwf", "comic:12"))
            val touched = local.snapshot()
            val removed = cloudFavorite.copy(deleted = true, updatedAt = 20)
            local.restore(listOf(CloudLibraryCodec.series(recent), removed), touched, 102)
            assertEquals(recent, db.viewer().libraryEntry("wfwf", "comic:12"))
            val emptyDbRecords = listOf(CloudLibraryCodec.series(recent), cloudFavorite)
            db.cloudLibrary().deleteSeries("wfwf", "comic:12")
            local.restore(emptyDbRecords, emptyList(), 103)
            assertEquals(recent.copy(favorite = true), db.viewer().libraryEntry("wfwf", "comic:12"))
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, ViewerDatabase::class.java).build()

    @Test fun restoresNativeAndLegacyPositionAtomically() = runBlocking {
        val db = database()
        try {
            val local = LocalCloudLibrary { db }
            val reading = ReadingProgressEntity("ntk", "/webtoon/12", "/webtoon/12/nv-12-3", "p0002", 987654, 100)
            val native = EngineReadingAnchorEntity(reading.sourceKey, reading.seriesKey, reading.episodeKey,
                reading.pageKey, 9_876_543_210L, 12345, reading.offsetInPageUnits, reading.updatedAtEpochMillis)
            val bookmark = BookmarkEntity(reading.sourceKey, reading.seriesKey, reading.episodeKey,
                reading.pageKey, reading.offsetInPageUnits, 100)
            val bookmarkNative = EngineBookmarkAnchorEntity(reading.sourceKey, reading.seriesKey, reading.episodeKey,
                reading.pageKey, native.sourceYQ32, native.viewportOffsetUnits, reading.offsetInPageUnits, 100)
            val records = listOf(CloudLibraryCodec.progress(reading, native), CloudLibraryCodec.bookmark(bookmark, bookmarkNative))
            local.restore(CloudLibraryRecords.decode(CloudLibraryRecords.encode(records)), emptyList(), 200)
            val stored = db.cloudLibrary().snapshot()
            assertEquals(listOf(reading), stored.progress)
            assertEquals(listOf(native), stored.readingAnchors)
            assertEquals(listOf(bookmark), stored.bookmarks)
            assertEquals(listOf(bookmarkNative), stored.bookmarkAnchors)
            db.viewer().recordOpened(LibraryEntryEntity("ntk", "/webtoon/12", "Restored", null, false, 300),
                reading.copy(pageKey = "p0000", offsetInPageUnits = 0, updatedAtEpochMillis = 300))
            val reopened = db.cloudLibrary().snapshot()
            assertEquals(reading.copy(updatedAtEpochMillis = 300), reopened.progress.single())
            assertEquals(native.copy(updatedAtEpochMillis = 300), reopened.readingAnchors.single())
        } finally { db.close() }
    }

    @Test fun deletingBookmarkDuringDownloadDoesNotResurrectEitherAnchor() = runBlocking {
        val db = database()
        try {
            val local = LocalCloudLibrary { db }
            val bookmark = BookmarkEntity("wfwf", "comic:12", "3", "p0002", 987654, 100)
            val native = EngineBookmarkAnchorEntity("wfwf", "comic:12", "3", "p0002", 9_876_543_210L, 0, 987654, 100)
            db.viewer().saveBookmark(bookmark)
            db.engine().upsertBookmarkAnchor(native)
            val before = local.snapshot()
            db.viewer().deleteBookmark(bookmark)
            db.engine().deleteBookmarkAnchor("wfwf", "comic:12", "3", "p0002")
            val merged = local.restore(before.map { it.copy(updatedAt = 9000) }, before, 200)
            assertTrue(merged.single().deleted)
            assertTrue(db.cloudLibrary().bookmarks().isEmpty())
            assertTrue(db.cloudLibrary().bookmarkAnchors().isEmpty())
        } finally { db.close() }
    }

    @Test fun invalidRemotePayloadLeavesAllExistingRowsUntouched() = runBlocking {
        val db = database()
        try {
            val local = LocalCloudLibrary { db }
            val entry = LibraryEntryEntity("wfwf", "comic:12", "My favorite", null, true, 100)
            db.viewer().saveLibraryEntry(entry)
            val before = local.snapshot()
            val corrupt = before.first().copy(key = "invalid")
            try { local.restore(listOf(corrupt), before, 200); fail("Expected validation failure") }
            catch (_: IllegalArgumentException) { /* rejected before any write */ }
            assertEquals(listOf(entry), db.cloudLibrary().series())
        } finally { db.close() }
    }
}
