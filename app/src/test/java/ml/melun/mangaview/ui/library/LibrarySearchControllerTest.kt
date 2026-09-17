package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import ml.melun.mangaview.app.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibrarySearchControllerTest {
    @Test fun cooldownTicksDoNotEnqueueTheSameSeriesMetadataAgain() = runTest {
        var attempts = 0
        val fixture = Fixture(this) { query ->
            if (query.cursor == null) SourcePage(listOf(series("first")), "2") else {
                if (attempts++ == 0) throw SourceThrottledException("429", 3_000L)
                SourcePage(listOf(series("second")))
            }
        }
        fixture.controller.submit(); runCurrent()
        assertEquals(1, fixture.enrichments)
        fixture.controller.next(); runCurrent()
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(1, fixture.enrichments)
        advanceUntilIdle()
        assertEquals(2, fixture.enrichments)
    }

    @Test fun lateSearchResultCannotOverwriteAnOpenEpisodeList() = runTest {
        val pending = CompletableDeferred<SourcePage<SourceSeries>>()
        val fixture = Fixture(this) { pending.await() }
        fixture.controller.submit(); runCurrent()
        val series = fixture.series
        val detail = LibraryContent.Episodes(series, listOf(SourceEpisode(EpisodeId(series.id, "1"), "1화")))
        fixture.state = fixture.state.copy(activeSeries = series, content = detail)
        pending.complete(SourcePage(listOf(series), "2")); runCurrent()
        assertEquals(detail, fixture.state.content)
        assertEquals("2", (fixture.state.searchContent as LibraryContent.Series).nextCursor)
        assertEquals("Hidden search results must not compete with the episode list for metadata", 0, fixture.enrichments)
    }

    @Test fun clearingQueryCancelsSearchAndRemovesTheOldResultSet() = runTest {
        val fixture = Fixture(this) { SourcePage(listOf(series("found")), "2") }
        fixture.controller.submit(); runCurrent()
        fixture.controller.queryChanged("")
        assertEquals(LibraryContent.Empty, fixture.state.searchContent)
        assertEquals("", fixture.state.submittedQuery)
        fixture.controller.next(); runCurrent()
        assertEquals(LibraryContent.Empty, fixture.state.searchContent)
    }

    @Test fun switchingFiltersSearchesAgainAndKeepsTheQueryThatProducedTheResults() = runTest {
        val queries = mutableListOf<SourceSearchQuery>()
        val fixture = Fixture(this) { queries += it; SourcePage(emptyList()) }
        fixture.controller.submit(); runCurrent()
        fixture.controller.selectKind(SeriesKind.COMIC); runCurrent()
        fixture.controller.selectField(SearchField.AUTHOR); runCurrent()
        assertEquals(3, queries.size)
        assertEquals(SeriesKind.COMIC, queries.last().kind)
        assertEquals(SearchField.AUTHOR, queries.last().field)
        fixture.controller.queryChanged("아직 제출하지 않은 검색어")
        assertEquals("생존", fixture.state.submittedQuery)
    }

    @Test fun newerQueryWinsEvenIfAnOlderRequestIsStillPending() = runTest {
        val old = CompletableDeferred<SourcePage<SourceSeries>>()
        val fixture = Fixture(this) { if (it.text == "생존") old.await() else SourcePage(listOf(series("new"))) }
        fixture.controller.submit(); runCurrent()
        fixture.controller.queryChanged("다음"); fixture.controller.submit(); runCurrent()
        old.complete(SourcePage(listOf(series("old")))); runCurrent()
        assertEquals(listOf("new"), (fixture.state.searchContent as LibraryContent.Series).items.map { it.title })
    }

    @Test fun hiddenFiltersFromAnotherProviderCannotChangeCombinedSearch() = runTest {
        val queries = mutableListOf<SourceSearchQuery>()
        val fixture = Fixture(this, SearchMode.COMBINED, false) { queries += it; SourcePage(emptyList()) }
        fixture.state = fixture.state.copy(searchField = SearchField.AUTHOR, searchKind = SeriesKind.COMIC)
        fixture.controller.submit(); runCurrent()
        assertEquals(SearchField.TITLE, queries.single().field)
        assertNull(queries.single().kind)
    }

    private class Fixture(scope: TestScope, mode: SearchMode = SearchMode.FIELDS, kinds: Boolean = true,
                          load: suspend (SourceSearchQuery) -> SourcePage<SourceSeries>) {
        val series = series("생존게임")
        private val source = SearchSource(load)
        private val registry = SourceRegistry(listOf(SourceRegistration(source.id, "test", kinds, mode) { source }))
        var state = LibraryState("생존", registry.options, source.id, destination = MainDestination.SEARCH)
        var enrichments = 0
        val controller = LibrarySearchController(scope, StandardTestDispatcher(scope.testScheduler), registry,
            { state }, { state = it(state) }, {}, {}, { enrichments++ })
    }

    private class SearchSource(val load: suspend (SourceSearchQuery) -> SourcePage<SourceSeries>) : ContentSource {
        override val id = SourceId("test")
        override suspend fun search(query: String, cursor: String?) = load(SourceSearchQuery(query, cursor = cursor))
        override suspend fun search(query: SourceSearchQuery) = load(query)
        override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> = error("unused")
        override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = error("unused")
        override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("unused")
        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit
        override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("unused")
    }

    companion object {
        private fun series(title: String) = SourceSeries(SeriesId(SourceId("test"), title), title)
    }
}
