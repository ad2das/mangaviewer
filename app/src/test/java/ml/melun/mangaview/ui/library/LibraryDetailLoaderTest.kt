package ml.melun.mangaview.ui.library

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.data.cache.EpisodeCatalogSnapshot
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryDetailLoaderTest {
    @Test fun longCatalogKeepsLoadingAsLongAsNewEpisodesContinueToArrive() = runTest {
        val f = Fixture(this)
        f.fetch = { id, partial ->
            repeat(3) { page ->
                kotlinx.coroutines.delay(100_000L)
                partial((0..page).map { episode(id, it.toString()) })
            }
            listOf("0", "1", "2").map { episode(id, it) }
        }
        f.loader.open(series); advanceUntilIdle()
        assertTrue(f.content.complete)
        assertEquals(3, f.content.items.size)
        assertEquals(3, f.saved[series.id]?.episodes?.size)
        assertTrue(testScheduler.currentTime >= 300_000L)
    }

    @Test fun stalledCatalogCancelsTheRequestButKeepsAlreadyVisibleEpisodes() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.fetch = { id, partial ->
            partial(listOf(episode(id, "1")))
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        f.loader.open(series); advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(f.content.complete)
        assertNotNull(f.content.refreshFailure)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun partialListIsUsableBeforeTheTailButOnlyTheCompleteListIsSaved() = runTest {
        val tail = CompletableDeferred<Unit>()
        val f = Fixture(this)
        f.fetch = { id, partial -> partial(listOf(episode(id, "3"))); tail.await(); listOf("3", "2", "1").map { episode(id, it) } }
        f.loader.open(series); runCurrent()
        assertEquals(listOf("3"), f.content.items.map { it.id.remoteKey })
        assertFalse(f.content.complete)
        assertTrue(f.content.refreshing)
        assertTrue(f.saved.isEmpty())
        assertEquals(0, f.readyCount)
        tail.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("3", "2", "1"), f.content.items.map { it.id.remoteKey })
        assertTrue(f.content.complete)
        assertEquals(f.content.items, f.saved[series.id]?.episodes)
        assertEquals(1, f.readyCount)
    }

    @Test fun reopeningAFreshListPublishesSynchronouslyWithoutAnotherRequest() = runTest {
        val f = Fixture(this)
        f.loader.open(series); advanceUntilIdle()
        val first = f.content.items
        f.loader.cancel()
        f.state = f.state.copy(activeSeries = null, content = LibraryContent.Empty)
        f.loader.open(series)
        assertEquals(first, f.content.items)
        assertFalse(f.content.refreshing)
        advanceUntilIdle()
        assertEquals(1, f.requests)
    }

    @Test fun staleSnapshotStaysVisibleWhileRefreshingAndAfterFailure() = runTest {
        val tail = CompletableDeferred<Unit>()
        val f = Fixture(this)
        f.saved[series.id] = EpisodeCatalogSnapshot(series.id, listOf(episode(series.id, "old")), null, 0L)
        f.now = 200_000L
        f.fetch = { _, _ -> tail.await(); throw IOException("offline") }
        f.loader.open(series); runCurrent()
        assertEquals("old", f.content.items.single().id.remoteKey)
        assertTrue(f.content.refreshing)
        tail.complete(Unit); advanceUntilIdle()
        assertEquals("old", f.content.items.single().id.remoteKey)
        assertFalse(f.content.refreshing)
        assertNotNull(f.content.refreshFailure)
        assertEquals(0L, f.saved[series.id]?.savedAtEpochMillis)
    }

    @Test fun manualRefreshFetchesNewEpisodesAndKeepsTheSelectedTabAndOldList() = runTest {
        val f = Fixture(this)
        f.loader.open(series); advanceUntilIdle()
        f.state = f.state.copy(detailTab = DetailTab.EPISODES)
        val gate = CompletableDeferred<Unit>()
        f.fetch = { id, partial -> partial(listOf(episode(id, "new"))); gate.await(); listOf("new", "1").map { episode(id, it) } }
        f.loader.open(series, refresh = true); runCurrent()
        assertEquals(DetailTab.EPISODES, f.state.detailTab)
        assertEquals("1", f.content.items.single().id.remoteKey)
        assertTrue(f.content.refreshing)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("new", "1"), f.content.items.map { it.id.remoteKey })
        assertEquals(2, f.requests)
    }

    @Test fun cancelledOldWorkCannotReplaceAnotherSeriesOrPersistItsPartialList() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this)
        val other = SourceSeries(SeriesId(SourceId("test"), "other"), "other")
        f.fetch = { id, partial ->
            if (id == series.id) withContext(NonCancellable) {
                gate.await(); partial(listOf(episode(id, "late")))
            }
            listOf(episode(id, "1"))
        }
        f.loader.open(series); runCurrent()
        f.loader.open(other); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(other.id, f.content.series.id)
        assertEquals(other.id, f.content.items.single().id.seriesId)
        assertFalse(f.saved.containsKey(series.id))
    }

    @Test fun partialFailureIsNotRememberedAsACompleteCatalog() = runTest {
        val f = Fixture(this)
        f.fetch = { id, partial -> partial(listOf(episode(id, "3"))); throw IOException("tail failed") }
        f.loader.open(series); advanceUntilIdle()
        assertFalse(f.content.complete)
        assertFalse(f.content.refreshing)
        assertNotNull(f.content.refreshFailure)
        assertTrue(f.saved.isEmpty())
        f.loader.open(series); advanceUntilIdle()
        assertEquals(2, f.requests)
    }

    @Test fun offlineSelectionNeverUsesTheOnlineSnapshotOrCallsTheProvider() = runTest {
        val f = Fixture(this)
        f.saved[series.id] = EpisodeCatalogSnapshot(series.id, listOf(episode(series.id, "online")), null, f.now)
        f.local = listOf(episode(series.id, "downloaded"))
        f.loader.open(series, offlineOnly = true); advanceUntilIdle()
        assertEquals("downloaded", f.content.items.single().id.remoteKey)
        assertEquals(0, f.requests)
        assertEquals("online", f.saved[series.id]?.episodes?.single()?.id?.remoteKey)
    }

    @Test fun networkFailureCanShowDownloadedEpisodesWithoutCachingAnIncompleteList() = runTest {
        val f = Fixture(this)
        f.local = listOf(episode(series.id, "downloaded"))
        f.fetch = { _, _ -> throw IOException("offline") }
        f.loader.open(series); advanceUntilIdle()
        assertFalse(f.content.complete)
        assertTrue(f.content.refreshFailure!!.contains("저장된 회차"))
        assertTrue(f.saved.isEmpty())
    }

    @Test fun newLoaderCanUseAPersistedFreshSnapshotWithoutNetwork() = runTest {
        val f = Fixture(this)
        f.saved[series.id] = EpisodeCatalogSnapshot(series.id, listOf(episode(series.id, "stored")),
            SourceSeriesDetails(description = "소개"), f.now)
        f.loader.open(series); advanceUntilIdle()
        assertEquals("stored", f.content.items.single().id.remoteKey)
        assertEquals("소개", f.state.activeSeriesDetails?.description)
        assertEquals(0, f.requests)
    }

    private class Fixture(scope: TestScope) {
        var state = LibraryState("", emptyList(), SourceId("test"))
        var now = 1_000L
        var requests = 0
        var readyCount = 0
        var local = emptyList<SourceEpisode>()
        val saved = mutableMapOf<SeriesId, EpisodeCatalogSnapshot>()
        var fetch: suspend (SeriesId, suspend (List<SourceEpisode>) -> Unit) -> List<SourceEpisode> =
            { id, _ -> listOf(episode(id, "1")) }
        private val provider = object : ContentSource {
            override val id = SourceId("test")
            override suspend fun episodeCatalog(seriesId: SeriesId, onPartial: suspend (List<SourceEpisode>) -> Unit): List<SourceEpisode> {
                requests++; return fetch(seriesId, onPartial)
            }
            override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> = error("unused")
            override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> = error("unused")
            override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = error("unused")
            override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("unused")
            override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit
            override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("unused")
        }
        val loader = LibraryDetailLoader(scope, StandardTestDispatcher(scope.testScheduler), { provider }, { local },
            { state }, { state = it(state) }, { _, _ -> readyCount++ },
            readSnapshot = { saved[it] }, saveSnapshot = { saved[it.seriesId] = it }, clock = { now })
        val content get() = state.content as LibraryContent.Episodes
    }

    private companion object {
        val series = SourceSeries(SeriesId(SourceId("test"), "series"), "title")
        fun episode(id: SeriesId, key: String) = SourceEpisode(EpisodeId(id, key), "${key}화")
    }
}
