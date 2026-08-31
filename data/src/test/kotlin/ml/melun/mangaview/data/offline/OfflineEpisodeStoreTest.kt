package ml.melun.mangaview.data.offline

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineEpisodeStoreTest {
    @Test
    fun savedEpisodeSurvivesReloadAndProvidesEveryPage() = runTest {
        val root = createTempDir(prefix = "offline-store-")
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val fixture = fixture(root)
            val store = OfflineEpisodeStore(File(root, "saved"), dispatcher, clock = { 42L })
            store.save(fixture.series, fixture.episode, fixture.manifest, fixture.pages)

            val reloaded = OfflineEpisodeStore(File(root, "saved"), dispatcher)
            reloaded.load()

            assertEquals(listOf(fixture.episode), reloaded.episodes(fixture.series.id))
            assertEquals(fixture.manifest.copy(pages = fixture.manifest.pages.mapIndexed { index, spec ->
                spec.copy(dimensions = fixture.pages[index].dimensions, encodedLength = 4L, fingerprint = "sha$index")
            }), reloaded.manifest(fixture.episode.id))
            fixture.pages.forEach { assertNotNull(reloaded.find(it.pageId)) }

            reloaded.remove(fixture.episode.id)
            assertNull(reloaded.manifest(fixture.episode.id))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixture(root: File): Fixture {
        val series = SourceSeries(SeriesId(SourceId("test"), "series"), "작품", "작가", "cover")
        val episode = SourceEpisode(EpisodeId(series.id, "12"), "12화", 7L)
        val dimensions = PageDimensions(100, 200)
        val pages = List(3) { index ->
            val id = PageId.at(episode.id, index)
            val file = File(root, "source-$index.page").apply { writeBytes(byteArrayOf(1, 2, 3, index.toByte())) }
            CachedPage(id, file, 4L, "sha$index", "image/jpeg", dimensions)
        }
        val manifest = EpisodeManifest(
            episode.id,
            episode.title,
            pages.mapIndexed { index, page -> PageSpec(page.pageId, index) },
        )
        return Fixture(series, episode, manifest, pages)
    }

    private data class Fixture(
        val series: SourceSeries,
        val episode: SourceEpisode,
        val manifest: EpisodeManifest,
        val pages: List<CachedPage>,
    )
}
