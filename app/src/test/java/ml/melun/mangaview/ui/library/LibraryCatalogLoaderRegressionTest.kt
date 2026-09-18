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
import ml.melun.mangaview.data.cache.HomeCatalogSnapshotStore
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryCatalogLoaderRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
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

    @Test fun cancelledSourceSwitchLeavesNoStaleHomeFromThePreviousProvider() = runTest {
        val shownId = SourceId("shown")
        val nextId = SourceId("next")
        val shown = FakeCatalogSource(shownId, immediate = listOf(series(shownId, "shown-1")))
        val next = FakeCatalogSource(nextId)
        val fixture = LoaderFixture(testScheduler, registry(shown, next), stateFor(shown), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        assertEquals(fastHome(series(shownId, "shown-1")), fixture.state.home)

        fixture.state = fixture.state.copy(selectedSourceId = nextId)
        fixture.loader.loadHome()
        assertEquals(HomeContent.Loading, fixture.state.home)
        fixture.loader.cancelHome()
        runCurrent()

        assertEquals(HomeContent.Loading, fixture.state.home)
        assertEquals(1, fixture.readyCount)
    }

    @Test fun refreshingTheSameSourceKeepsTheLoadedHomeVisibleWhenCancelled() = runTest {
        val id = SourceId("same")
        val source = FakeCatalogSource(id, immediate = listOf(series(id, "same-1")))
        val fixture = LoaderFixture(testScheduler, registry(source), stateFor(source), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()
        val loaded = fastHome(series(id, "same-1"))
        assertEquals(loaded, fixture.state.home)

        source.immediate = null
        fixture.loader.loadHome()
        fixture.loader.cancelHome()
        runCurrent()

        assertEquals(loaded, fixture.state.home)
        assertEquals(1, fixture.readyCount)
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

    @Test fun rememberedHomePaintsBeforeTheNetworkAndSurvivesAFailedRefresh() = runTest {
        val slow = FakeCatalogSource(SourceId("slow"))
        val cache = HomeCatalogSnapshotStore(temporary.newFolder(), StandardTestDispatcher(testScheduler))
        val remembered = series(slow.id, "remembered-1")
        cache.save(slow.id, SeriesKind.WEBTOON, listOf(remembered), listOf(remembered), listOf(remembered))
        val fixture = LoaderFixture(testScheduler, registry(slow), stateFor(slow), backgroundScope, cache)

        fixture.loader.loadHome()
        runCurrent()
        assertEquals(fastHome(remembered), fixture.state.home)

        slow.fail(CatalogOrder.POPULAR, IllegalStateException("offline"))
        runCurrent()
        assertEquals(fastHome(remembered), fixture.state.home)
        assertEquals(0, fixture.readyCount)
    }

    @Test fun aFailedCatalogRowIsRetriedOnceBeforeTheHomeFails() = runTest {
        val flaky = FailingOnceCatalogSource(SourceId("flaky"), series(SourceId("flaky"), "recovered-1"))
        val fixture = LoaderFixture(testScheduler, registry(flaky), stateFor(flaky), backgroundScope)

        fixture.loader.loadHome()
        runCurrent()

        assertEquals(fastHome(flaky.item), fixture.state.home)
        assertEquals(CatalogOrder.entries.associateWith { 2 }, flaky.attempts)
        assertEquals(1, fixture.readyCount)
    }

    private class LoaderFixture(
        scheduler: TestCoroutineScheduler,
        registry: SourceRegistry,
        initial: LibraryState,
        scope: CoroutineScope,
        homeCache: HomeCatalogSnapshotStore? = null,
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
            homeCache = homeCache,
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
    var immediate: List<SourceSeries>? = null,
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

private class FailingOnceCatalogSource(
    override val id: SourceId,
    val item: SourceSeries,
) : ContentSource {
    val attempts = mutableMapOf<CatalogOrder, Int>()

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val attempt = (attempts[query.order] ?: 0) + 1
        attempts[query.order] = attempt
        if (attempt == 1) throw IllegalStateException("first ${query.order} attempt failed")
        return SourcePage(listOf(item))
    }

    override suspend fun search(query: String, cursor: String?) = error("Unexpected search")

    override suspend fun episodes(seriesId: SeriesId, cursor: String?) = error("Unexpected episodes")

    override suspend fun manifest(episodeId: EpisodeId) = error("Unexpected manifest")

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = error("Unexpected adjacent")

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = error("Unexpected prepare")

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("Unexpected page")
}
