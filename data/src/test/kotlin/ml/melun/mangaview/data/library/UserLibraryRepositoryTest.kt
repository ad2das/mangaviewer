package ml.melun.mangaview.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.db.BookmarkEntity
import ml.melun.mangaview.data.db.LibraryEntryEntity
import ml.melun.mangaview.data.db.ReadEpisodeEntity
import ml.melun.mangaview.data.db.ReadingProgressEntity
import ml.melun.mangaview.data.db.ViewerDao
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.data.settings.ViewerSettingsStore

class UserLibraryRepositoryTest {
    @Test
    fun snapshotJoinsStableIdsWithoutProviderSpecificRules() {
        val library = LibraryEntryEntity("source", "series", "Title", null, true, 10L)
        val progress = ReadingProgressEntity("source", "series", "episode", "p0012", 45L, 20L)
        val bookmark = BookmarkEntity("source", "series", "episode", "p0007", 3L, 15L)
        val read = ReadEpisodeEntity("source", "series", "episode", 16L)

        val snapshot = assembleSnapshot(listOf(library), listOf(progress), listOf(bookmark), listOf(read), ViewerSettings())

        assertEquals("Title", snapshot.recent.single().series.title)
        assertEquals("episode", snapshot.recent.single().episodeId.remoteKey)
        assertEquals("p0012", snapshot.recent.single().pageId.remoteKey)
        assertEquals("Title", snapshot.bookmarks.single().seriesTitle)
        assertEquals("episode", snapshot.readEpisodes.single().episodeId.remoteKey)
        assertEquals(16L, snapshot.readEpisodes.single().readAtEpochMillis)
        assertTrue(snapshot.favorites.single().favorite)
    }

    @Test
    fun orphanedProgressRemainsUsableAfterIndependentTableWrites() {
        val progress = ReadingProgressEntity("wfwf", "42", "9", "p0000", 0L, 20L)

        val recent = assembleSnapshot(emptyList(), listOf(progress), emptyList(), emptyList(), ViewerSettings())
            .recent.single()

        assertEquals("42", recent.series.title)
        assertEquals("wfwf", recent.episodeId.seriesId.sourceId.value)
    }

    @Test
    fun progressTitleRoundTripsAndANullSaveKeepsTheStoredTitle() = runBlocking {
        val dao = FakeViewerDao()
        val repository = UserLibraryRepository(dao, settingsStore(), clock = { 100L })
        val episode = EpisodeId(SeriesId(SourceId("ntk"), "series"), "ep-1")
        val page = PageId(episode, "p0003")

        repository.saveProgress(page, 42L, "Episode 1")

        val stored = requireNotNull(dao.progress("ntk", "series"))
        assertEquals("Episode 1", stored.episodeTitle)
        assertEquals("Episode 1", assembleSnapshot(emptyList(), listOf(stored), emptyList(), emptyList(),
            ViewerSettings()).recent.single().episodeTitle)

        repository.saveProgress(page, 43L)

        val afterNullSave = requireNotNull(dao.progress("ntk", "series"))
        assertEquals(43L, afterNullSave.offsetInPageUnits)
        assertEquals("Episode 1", afterNullSave.episodeTitle)
    }

    @Test
    fun progressForADifferentEpisodeReplacesTheRowWithThePassedTitle() = runBlocking {
        val dao = FakeViewerDao()
        val repository = UserLibraryRepository(dao, settingsStore(), clock = { 100L })
        val episode = EpisodeId(SeriesId(SourceId("ntk"), "series"), "ep-1")

        repository.saveProgress(PageId(episode, "p0001"), 5L, "Episode 1")
        repository.saveProgress(PageId(EpisodeId(episode.seriesId, "ep-2"), "p0000"), 6L)

        val replaced = requireNotNull(dao.progress("ntk", "series"))
        assertEquals("ep-2", replaced.episodeKey)
        assertNull(replaced.episodeTitle)

        repository.saveProgress(PageId(EpisodeId(episode.seriesId, "ep-2"), "p0001"), 7L, "Episode 2")

        val titled = requireNotNull(dao.progress("ntk", "series"))
        assertEquals("ep-2", titled.episodeKey)
        assertEquals("Episode 2", titled.episodeTitle)
    }

    private fun settingsStore() = ViewerSettingsStore(UnusedDataStore())

    private class UnusedDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = emptyFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw UnsupportedOperationException("not used by this test")
    }

    private class FakeViewerDao : ViewerDao {
        val progressRows = linkedMapOf<Pair<String, String>, ReadingProgressEntity>()
        val readEpisodeRows = linkedMapOf<Triple<String, String, String>, ReadEpisodeEntity>()

        override suspend fun insertProgress(progress: ReadingProgressEntity) {
            progressRows[progress.sourceKey to progress.seriesKey] = progress
        }

        override suspend fun progress(sourceKey: String, seriesKey: String): ReadingProgressEntity? =
            progressRows[sourceKey to seriesKey]

        override suspend fun saveReadEpisode(episode: ReadEpisodeEntity) {
            readEpisodeRows[Triple(episode.sourceKey, episode.seriesKey, episode.episodeKey)] = episode
        }

        override fun progressHistory(): Flow<List<ReadingProgressEntity>> = emptyFlow()
        override suspend fun saveLibraryEntry(entry: LibraryEntryEntity) = unsupported()
        override fun library(): Flow<List<LibraryEntryEntity>> = emptyFlow()
        override suspend fun libraryEntry(sourceKey: String, seriesKey: String): LibraryEntryEntity? = unsupported()
        override suspend fun touchMatchingReadingAnchor(source: String, series: String, at: Long) = unsupported()
        override suspend fun deleteProgress(source: String, series: String) = unsupported()
        override suspend fun deleteReadingAnchor(source: String, series: String) = unsupported()
        override suspend fun clearFavorite(source: String, series: String, at: Long) = unsupported()
        override fun readEpisodes(): Flow<List<ReadEpisodeEntity>> = emptyFlow()
        override suspend fun deleteReadEpisodes(source: String, series: String) = unsupported()
        override suspend fun deleteReadEpisode(source: String, series: String, episode: String) = unsupported()
        override suspend fun saveBookmark(bookmark: BookmarkEntity) = unsupported()
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) = unsupported()
        override fun bookmarks(): Flow<List<BookmarkEntity>> = emptyFlow()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by this test")
    }
}
