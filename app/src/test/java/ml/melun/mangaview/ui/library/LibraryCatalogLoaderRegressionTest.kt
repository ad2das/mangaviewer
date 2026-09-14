package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.app.SourceRegistration
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryCatalogLoaderRegressionTest {
    @Test fun staleGenerationCannotPublishAfterSourceAndKindSwitch() = runTest {
        val slowId = SourceId("slow")
        val fastId = SourceId("fast")
        val slow = FakeCatalogSource(slowId)
        val fast = FakeCatalogSource(fastId, immediate = listOf(series(fastId, "fast-1")))
        val fixture = LoaderFixture(testScheduler, registry(slow, fast), stateFor(slow), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        assertEquals(HomeContent.Loading, fixture.state.home)
        assertEquals(CatalogOrder.entries.toSet(), slow.started)

        fixture.state = fixture.state.copy(selectedSourceId = fastId, homeKind = SeriesKind.COMIC)
        fixture.loader.loadHome()
        runCurrent()
        val fastHome = fastHome(series(fastId, "fast-1"))
        assertEquals(fastHome, fixture.state.home)
        assertEquals(1, fixture.readyCount)

        slow.completeAll(listOf(series(slowId, "slow-1")))
        runCurrent()
        assertEquals(fastHome, fixture.state.home)
        assertEquals(1, fixture.readyCount)
    }

    @Test fun cancelledGenerationCannotPublishResultsOrSignalReady() = runTest {
        val slowId = SourceId("slow")
        val otherId = SourceId("other")
        val slow = FakeCatalogSource(slowId)
        val other = FakeCatalogSource(otherId, immediate = listOf(series(otherId, "other-1")))
        val fixture = LoaderFixture(testScheduler, registry(slow, other), stateFor(slow), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        assertEquals(CatalogOrder.entries.toSet(), slow.started)
        fixture.loader.cancelHome()
        slow.completeAll(listOf(series(slowId, "slow-1")))
        runCurrent()

        assertEquals(HomeContent.Loading, fixture.state.home)
        assertEquals(0, fixture.readyCount)
    }

    @Test fun staleFailureCannotOverwriteNewerGeneration() = runTest {
        val slowId = SourceId("slow")
        val fastId = SourceId("fast")
        val slow = FakeCatalogSource(slowId)
        val fast = FakeCatalogSource(fastId, immediate = listOf(series(fastId, "fast-1")))
        val fixture = LoaderFixture(testScheduler, registry(slow, fast), stateFor(slow), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        fixture.state = fixture.state.copy(selectedSourceId = fastId, homeKind = SeriesKind.COMIC)
        fixture.loader.loadHome()
        runCurrent()
        val fastHome = fastHome(series(fastId, "fast-1"))

        slow.fail(CatalogOrder.POPULAR, IllegalStateException("stale catalog failure"))
        runCurrent()

        assertEquals(fastHome, fixture.state.home)
        assertEquals(1, fixture.readyCount)
    }

    @Test fun failureOfCurrentGenerationPublishesItsMessage() = runTest {
        val broken = FakeCatalogSource(SourceId("broken"))
        val fixture = LoaderFixture(testScheduler, registry(broken), stateFor(broken), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        broken.fail(CatalogOrder.POPULAR, IllegalStateException("boom"))
        runCurrent()

        assertEquals(HomeContent.Failure("boom"), fixture.state.home)
        assertEquals(0, fixture.readyCount)
    }

    private class LoaderFixture(
        scheduler: TestCoroutineScheduler,
        registry: SourceRegistry,
        initial: LibraryState,
        scope: CoroutineScope,
    ) {
        var state: LibraryState = initial
        var readyCount: Int = 0
        val loader = LibraryCatalogLoader(
            scope = scope,
            ioDispatcher = StandardTestDispatcher(scheduler),
            sourceRegistry = registry,
            current = { state },
            update = { transform -> state = transform(state) },
            onHomeReady = { readyCount++ },
        )
    }

    private fun stateFor(source: ContentSource): LibraryState = LibraryState(
        query = "",
        sources = emptyList(),
        selectedSourceId = source.id,
        homeKind = SeriesKind.WEBTOON,
    )

    private fun registry(vararg sources: ContentSource): SourceRegistry = SourceRegistry(
        sources.map { SourceRegistration(id = it.id, label = it.id.value, create = { it }) },
    )

    private fun series(source: SourceId, key: String): SourceSeries = SourceSeries(SeriesId(source, key), key)

    private fun fastHome(series: SourceSeries): HomeContent.Ready = HomeContent.Ready(
        popular = listOf(series),
        latest = listOf(series),
        new = listOf(series),
    )
}

private class FakeCatalogSource(
    override val id: SourceId,
    private val immediate: List<SourceSeries>? = null,
) : ContentSource {
    private val pending = CatalogOrder.entries.associateWith { CompletableDeferred<List<SourceSeries>>() }
    val started = mutableSetOf<CatalogOrder>()

    fun completeAll(items: List<SourceSeries>) {
        CatalogOrder.entries.forEach { pending.getValue(it).complete(items) }
    }

    fun fail(order: CatalogOrder, failure: Throwable) {
        pending.getValue(order).completeExceptionally(failure)
    }

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        started += query.order
        return SourcePage(immediate ?: pending.getValue(query.order).await())
    }

    override suspend fun search(query: String, cursor: String?) = error("Unexpected search")

    override suspend fun episodes(seriesId: SeriesId, cursor: String?) = error("Unexpected episodes")

    override suspend fun manifest(episodeId: EpisodeId) = error("Unexpected manifest")

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("Unexpected adjacent")

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = error("Unexpected prepare")

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("Unexpected page")
}
