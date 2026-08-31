package ml.melun.mangaview.activity

import kotlinx.coroutines.test.runTest
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
import org.junit.Test

class ViewerEpisodeListLoaderTest {
    private val sourceId = SourceId("test")
    private val seriesId = SeriesId(sourceId, "series")

    @Test
    fun loadsEveryPageAndKeepsTheFirstUniqueEpisode() = runTest {
        val first = episode("1", "one")
        val duplicate = episode("2", "first title")
        val secondCopy = episode("2", "replacement title")
        val last = episode("3", "three")
        val source = StubSource { cursor ->
            if (cursor == null) SourcePage(listOf(first, duplicate), "next")
            else SourcePage(listOf(secondCopy, last), "next")
        }

        val result = ViewerEpisodeListLoader(source).load(seriesId)

        assertEquals(listOf(first, duplicate, last), result)
        assertEquals(listOf<String?>(null, "next"), source.requestedCursors)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEpisodeFromAnotherSeries() = runTest {
        val wrong = SourceEpisode(EpisodeId(SeriesId(sourceId, "other"), "1"), "wrong")
        ViewerEpisodeListLoader(StubSource { SourcePage(listOf(wrong)) }).load(seriesId)
    }

    private fun episode(key: String, title: String) = SourceEpisode(EpisodeId(seriesId, key), title)

    private inner class StubSource(
        private val page: (String?) -> SourcePage<SourceEpisode>,
    ) : ContentSource {
        override val id = sourceId
        val requestedCursors = mutableListOf<String?>()

        override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
            requestedCursors += cursor
            return page(cursor)
        }

        override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
            error("unused")

        override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = error("unused")

        override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("unused")

        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit

        override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
            error("unused")
    }
}
