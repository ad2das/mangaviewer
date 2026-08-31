package ml.melun.mangaview.app

import java.util.concurrent.atomic.AtomicInteger
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun optionsDoNotInitializeUnselectedSourcesAndSelectedSourceIsCreatedOnce() {
        val ntkCreates = AtomicInteger()
        val wfwfCreates = AtomicInteger()
        val ntkId = SourceId("ntk")
        val wfwfId = SourceId("wfwf")
        val registry = SourceRegistry(listOf(
            SourceRegistration(ntkId, "NTK") { ntkCreates.incrementAndGet(); fakeSource(ntkId) },
            SourceRegistration(wfwfId, "WFWF") { wfwfCreates.incrementAndGet(); fakeSource(wfwfId) },
        ))

        assertEquals(listOf(ntkId, wfwfId), registry.options.map(SourceOption::id))
        assertEquals(0, ntkCreates.get())
        assertEquals(0, wfwfCreates.get())

        val first = registry.require(wfwfId)
        val second = registry.require(wfwfId)
        assertSame(first, second)
        assertEquals(0, ntkCreates.get())
        assertEquals(1, wfwfCreates.get())
    }

    private fun fakeSource(sourceId: SourceId): ContentSource = object : ContentSource {
        override val id = sourceId
        override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
            SourcePage(emptyList())
        override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> =
            SourcePage(emptyList())
        override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = error("unused")
        override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("unused")
        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit
        override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
            error("unused")
    }
}
