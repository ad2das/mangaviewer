package ml.melun.mangaview.data.offline

import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.cache.*
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineDownloadRemovalTest {
    @Test fun deletionCancelsRunningAndQueuedEpisodesWithoutRemovingAnotherSeries() = runTest {
        val root = kotlin.io.path.createTempDirectory("offline-removal-").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = OfflineEpisodeStore(File(root, "saved"), dispatcher)
            val source = WaitingSource()
            val cache = object : RawPageCache {
                override suspend fun find(pageId: PageId): CachedPage? = null
                override suspend fun write(pageId: PageId, openedPage: OpenedPage, onPreview: ((PageTransferPreview) -> Unit)?): CachedPage = error("Unexpected image request")
                override suspend fun remove(pageId: PageId) = Unit
            }
            val repository = PageRepository(backgroundScope, { source }, cache)
            val manager = OfflineDownloadManager(backgroundScope, { source }, repository, store)
            val target = SourceSeries(SeriesId(source.id, "remove"), "Target")
            val other = SourceSeries(SeriesId(source.id, "keep"), "Keep")
            val storedTarget = save(store, root, target)
            val storedOther = save(store, root, other)
            val pending = (1..3).map { SourceEpisode(EpisodeId(target.id, "$it"), "$it") }
            pending.forEach { assertTrue(manager.download(target, it)) }
            val otherPending = SourceEpisode(EpisodeId(other.id, "1"), "1")
            assertTrue(manager.download(other, otherPending))
            runCurrent()
            assertEquals(2, source.started.size)
            manager.removeSeries(target.id)
            runCurrent()
            assertNull(store.manifest(storedTarget.id))
            assertNotNull(store.manifest(storedOther.id))
            assertTrue(manager.states.value.keys.none { it.seriesId == target.id })
            assertTrue(manager.states.value.containsKey(otherPending.id))
            assertTrue(source.started.contains(otherPending.id))
            assertFalse("Queued removed episode must never start", source.started.contains(pending.last().id))
            assertTrue("Explicit download after removal remains available", manager.download(target, pending.first()))
            manager.removeSeries(target.id)
        } finally { root.deleteRecursively() }
    }

    private suspend fun save(store: OfflineEpisodeStore, root: File, series: SourceSeries): SourceEpisode {
        val episode = SourceEpisode(EpisodeId(series.id, "saved"), "Saved")
        val id = PageId.at(episode.id, 0)
        val file = File(root, "${series.id.remoteKey}.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        store.save(series, episode, EpisodeManifest(episode.id, episode.title, listOf(PageSpec(id, 0))),
            listOf(CachedPage(id, file, 4, "fixture", "image/png", PageDimensions(1, 1))))
        return episode
    }
}

private class WaitingSource : ContentSource {
    override val id = SourceId("test")
    val started = mutableListOf<EpisodeId>()
    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest { started += episodeId; awaitCancellation() }
    override suspend fun search(query: String, cursor: String?) = SourcePage<SourceSeries>(emptyList())
    override suspend fun episodes(seriesId: SeriesId, cursor: String?) = SourcePage<SourceEpisode>(emptyList())
    override suspend fun adjacent(episodeId: EpisodeId) = AdjacentEpisodes(null, null)
    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit
    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("Unexpected image request")
}
