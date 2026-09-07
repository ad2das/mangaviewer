package ml.melun.mangaview.viewer

import java.io.File
import ml.melun.mangaview.app.SourceRegistration
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleEpisodeRegressionTest {
    @Test
    fun exactLiveIdentityDoesNotRequireDownloadingUnrelatedLaterCatalogPages() {
        val source = PagedSource(
            catalogPage = { cursor ->
                check(cursor == null) { "Unrelated catalog page requested" }
                SourcePage(listOf(series("series", "live work")), "catalog-2")
            },
            episodePage = { SourcePage(listOf(episode("episode-2", "live episode"))) },
        )
        withCorpus(source) { corpus ->
            assertEquals("live work", corpus.resolveSingleEpisode(regression()).series.title)
            assertEquals("live work", corpus.resolveSingleEpisode(regression()).series.title)
            assertEquals(listOf<String?>(null), source.catalogCursors)
        }
    }

    @Test
    fun recoveredChainRefreshesDamagedWorkTitleAndSpansEpisodePages() {
        val source = PagedSource(
            catalogPage = { SourcePage(listOf(series("series", "live work title"))) },
            episodePage = { cursor ->
                if (cursor == null) SourcePage(listOf(episode("119", "live 119"), episode("118", "live 118")), "older")
                else SourcePage((117 downTo 115).map { episode("$it", "live $it") })
            },
        )
        val historical = CorpusSeriesSample(SeriesKind.COMIC, series("series", "damaged historical title"),
            (115..119).map { episode("$it", "damaged $it") })
        withCorpus(source) { corpus ->
            val resolved = corpus.refreshRegression(historical)
            assertEquals("live work title", resolved.series.title)
            assertEquals((115..119).map { "live $it" }, resolved.chain.map { it.title })
            assertEquals(historical.chain.map { it.id }, resolved.chain.map { it.id })
            assertEquals(listOf<String?>(null, "older"), source.episodeCursors)
            corpus.refreshRegression(historical)
            assertEquals(listOf<String?>(null), source.catalogCursors)
        }
    }

    @Test
    fun resolvesSeriesAndEpisodeTitlesAcrossAllLivePages() {
        val source = PagedSource(
            catalogPage = { cursor ->
                if (cursor == null) SourcePage(listOf(series("other", "other work")), "catalog-2")
                else SourcePage(listOf(series("series", "live work")))
            },
            episodePage = { cursor ->
                if (cursor == null) SourcePage(listOf(episode("episode-1", "old episode")), "episodes-2")
                else SourcePage(listOf(episode("episode-2", "live episode")))
            },
        )

        withCorpus(source) { corpus ->
            val resolved = corpus.resolveSingleEpisode(regression())

            assertEquals("live work", resolved.series.title)
            assertEquals("live episode", resolved.episode.title)
            assertEquals(listOf<String?>(null, "catalog-2"), source.catalogCursors)
            assertEquals(listOf<String?>(null, "episodes-2"), source.episodeCursors)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun catalogCursorCycleCannotBeUsedToSubstituteARecord() {
        val source = PagedSource(
            catalogPage = { cursor -> SourcePage(emptyList(), if (cursor == null) "cycle" else "cycle") },
            episodePage = { SourcePage(emptyList()) },
        )

        withCorpus(source) { corpus -> corpus.resolveSingleEpisode(regression()) }
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateLiveEpisodeIdentityFailsInsteadOfChoosingOneTitle() {
        val source = PagedSource(
            catalogPage = { SourcePage(listOf(series("series", "live work"))) },
            episodePage = { cursor ->
                if (cursor == null) SourcePage(listOf(episode("episode-2", "first title")), "episode-2")
                else SourcePage(listOf(episode("episode-2", "replacement title")))
            },
        )

        withCorpus(source) { corpus -> corpus.resolveSingleEpisode(regression()) }
    }

    @Test(expected = IllegalStateException::class)
    fun missingExactEpisodeFailsInsteadOfUsingAnotherEpisode() {
        val source = PagedSource(
            catalogPage = { SourcePage(listOf(series("series", "live work"))) },
            episodePage = { SourcePage(listOf(episode("different-episode", "not the regression"))) },
        )

        withCorpus(source) { corpus -> corpus.resolveSingleEpisode(regression()) }
    }

    private fun regression() = SingleEpisodeRegression(
        source = SOURCE,
        kind = SeriesKind.COMIC,
        seriesKey = "series",
        episodeKey = "episode-2",
        provenance = listOf(JSONObject()
            .put("artifact", "fixture")
            .put("classification", "SINGLE_EPISODE_DEVICE_FAILURE")
            .put("reason", "fixture failure")),
    )

    private fun series(key: String, title: String) = SourceSeries(SeriesId(SOURCE, key), title)

    private fun episode(key: String, title: String) = SourceEpisode(
        EpisodeId(SeriesId(SOURCE, "series"), key), title,
    )

    private fun withCorpus(source: PagedSource, block: (QualificationCorpus) -> Unit) {
        val marker = File.createTempFile("single-regression", ".dir")
        assertTrue(marker.delete())
        assertTrue(marker.mkdirs())
        try {
            val registry = SourceRegistry(listOf(SourceRegistration(SOURCE, "fixture") { source }))
            block(QualificationCorpus(registry, marker))
        } finally {
            marker.deleteRecursively()
        }
    }

    private class PagedSource(
        private val catalogPage: (String?) -> SourcePage<SourceSeries>,
        private val episodePage: (String?) -> SourcePage<SourceEpisode>,
    ) : ContentSource {
        override val id = SOURCE
        val catalogCursors = mutableListOf<String?>()
        val episodeCursors = mutableListOf<String?>()

        override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
            SourcePage(emptyList())

        override suspend fun catalog(query: ml.melun.mangaview.source.CatalogQuery): SourcePage<SourceSeries> {
            catalogCursors += query.cursor
            return catalogPage(query.cursor)
        }

        override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
            episodeCursors += cursor
            return episodePage(cursor)
        }

        override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = error("unused")

        override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("unused")

        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit

        override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
            error("unused")
    }

    private companion object {
        val SOURCE = SourceId("wfwf")
    }
}
