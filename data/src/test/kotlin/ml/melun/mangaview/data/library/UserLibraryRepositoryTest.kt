package ml.melun.mangaview.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ml.melun.mangaview.data.db.BookmarkEntity
import ml.melun.mangaview.data.db.LibraryEntryEntity
import ml.melun.mangaview.data.db.ReadingProgressEntity
import ml.melun.mangaview.data.settings.ViewerSettings

class UserLibraryRepositoryTest {
    @Test
    fun snapshotJoinsStableIdsWithoutProviderSpecificRules() {
        val library = LibraryEntryEntity("source", "series", "Title", null, true, 10L)
        val progress = ReadingProgressEntity("source", "series", "episode", "p0012", 45L, 20L)
        val bookmark = BookmarkEntity("source", "series", "episode", "p0007", 3L, 15L)

        val snapshot = assembleSnapshot(listOf(library), listOf(progress), listOf(bookmark), ViewerSettings())

        assertEquals("Title", snapshot.recent.single().series.title)
        assertEquals("episode", snapshot.recent.single().episodeId.remoteKey)
        assertEquals("p0012", snapshot.recent.single().pageId.remoteKey)
        assertEquals("Title", snapshot.bookmarks.single().seriesTitle)
        assertTrue(snapshot.favorites.single().favorite)
    }

    @Test
    fun orphanedProgressRemainsUsableAfterIndependentTableWrites() {
        val progress = ReadingProgressEntity("wfwf", "42", "9", "p0000", 0L, 20L)

        val recent = assembleSnapshot(emptyList(), listOf(progress), emptyList(), ViewerSettings()).recent.single()

        assertEquals("42", recent.series.title)
        assertEquals("wfwf", recent.episodeId.seriesId.sourceId.value)
    }
}
