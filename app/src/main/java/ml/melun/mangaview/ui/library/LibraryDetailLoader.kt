package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.data.cache.EpisodeCatalogSnapshot
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

/** Shows remembered lists immediately; only complete, current generations replace a snapshot. */
internal class LibraryDetailLoader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val source: (SeriesId) -> ContentSource,
    private val offlineEpisodes: suspend (SeriesId) -> List<SourceEpisode>,
    private val current: () -> LibraryState,
    private val update: ((LibraryState) -> LibraryState) -> Unit,
    private val ready: (SourceSeries, List<SourceEpisode>) -> Unit,
    private val readSnapshot: suspend (SeriesId) -> EpisodeCatalogSnapshot? = { null },
    private val saveSnapshot: suspend (EpisodeCatalogSnapshot) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val remembered = LinkedHashMap<SeriesId, EpisodeCatalogSnapshot>(16, 0.75f, true)
    private var job: Job? = null
    private var detailsJob: Job? = null
    private var version = 0L

    fun open(series: SourceSeries, offlineOnly: Boolean = false, refresh: Boolean = false) {
        cancel()
        val expected = version
        val cached = remembered[series.id].takeUnless { offlineOnly }
        val previous = (current().content as? LibraryContent.Episodes)?.takeIf { refresh && it.series.id == series.id }
        update { it.copy(activeSeries = series,
            detailTab = if (refresh && it.activeSeries?.id == series.id) it.detailTab else DetailTab.INTRO,
            activeSeriesDetails = cached?.details, detailOffline = offlineOnly,
            selectedSourceId = if (offlineOnly) series.id.sourceId else it.selectedSourceId,
            lastSeries = if (offlineOnly) listOf(series) else it.lastSeries,
            seriesMenuVisible = false,
            content = cached?.let { snapshot -> content(series, snapshot, refresh) }
                ?: previous?.copy(refreshing = true, refreshFailure = null) ?: LibraryContent.Loading,
        ) }
        job = scope.launch {
            val snapshot = cached ?: if (offlineOnly) null else safeRead(series.id)
            if (!active(expected, series.id)) return@launch
            if (snapshot != null) {
                remember(snapshot)
                update { it.copy(content = content(series, snapshot, refresh), activeSeriesDetails = snapshot.details) }
                ready(series, snapshot.episodes)
                if (!refresh && fresh(snapshot)) return@launch
            }
            load(series, offlineOnly, expected)
        }
    }

    private suspend fun load(series: SourceSeries, offlineOnly: Boolean, expected: Long) {
        try {
            val provider = if (offlineOnly) null else source(series.id)
            val episodes = if (provider == null) withContext(dispatcher) {
                withTimeout(120_000L) { offlineEpisodes(series.id) }
            } else receiveCatalog(provider, series, expected)
            if (!active(expected, series.id)) return
            validate(series.id, episodes)
            val snapshot = EpisodeCatalogSnapshot(series.id, episodes, current().activeSeriesDetails, clock())
            update { it.copy(content = LibraryContent.Episodes(series, episodes)) }
            if (!offlineOnly) remember(snapshot)
            ready(series, episodes)
            if (provider != null) {
                safeSave(snapshot)
                if (active(expected, series.id)) loadDetails(provider, snapshot, expected)
            }
        } catch (timeout: TimeoutCancellationException) {
            fail(expected, series.id, "회차 응답이 늦어지고 있습니다. 다시 시도해 주세요")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fail(expected, series.id, failureDisplayMessage(failure, "회차를 불러오지 못했습니다"))
        }
    }

    /** Large catalogs may take minutes when paced by the provider; time out only stalled progress. */
    private suspend fun receiveCatalog(provider: ContentSource, series: SourceSeries, expected: Long): List<SourceEpisode> =
        coroutineScope {
            val progress = Channel<Unit>(Channel.CONFLATED)
            val loading = async(dispatcher) {
                var count = 0
                provider.episodeCatalog(series.id) { partial ->
                    validate(series.id, partial)
                    if (partial.size > count) { count = partial.size; progress.trySend(Unit) }
                    withContext(scope.coroutineContext.minusKey(Job)) { publishPartial(series, partial, expected) }
                }
            }
            try {
                while (true) {
                    val result = withTimeout(120_000L) {
                        select<List<SourceEpisode>?> {
                            loading.onAwait { it }
                            progress.onReceive { null }
                        }
                    }
                    if (result != null) return@coroutineScope result
                }
                @Suppress("UNREACHABLE_CODE") error("Catalog did not settle")
            } finally { progress.cancel() }
        }

    private fun loadDetails(provider: ContentSource, snapshot: EpisodeCatalogSnapshot, expected: Long) {
        detailsJob = scope.launch {
            try {
                val details = withContext(dispatcher) { withTimeout(30_000L) { provider.seriesDetails(snapshot.seriesId) } }
                    ?: return@launch
                if (!active(expected, snapshot.seriesId)) return@launch
                val enriched = snapshot.copy(details = details)
                remember(enriched)
                update { it.copy(activeSeriesDetails = details) }
                safeSave(enriched)
            } catch (_: TimeoutCancellationException) {
                // Supplemental metadata never delays or clears an available episode list.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { }
        }
    }

    private fun content(series: SourceSeries, snapshot: EpisodeCatalogSnapshot, refresh: Boolean) =
        LibraryContent.Episodes(series, snapshot.episodes, refreshing = refresh || !fresh(snapshot))

    private fun fresh(snapshot: EpisodeCatalogSnapshot): Boolean = clock() - snapshot.savedAtEpochMillis in 0..120_000L

    private fun remember(snapshot: EpisodeCatalogSnapshot) {
        remembered[snapshot.seriesId] = snapshot
        while (remembered.size > 16 || remembered.values.sumOf { it.episodes.size } > 50_000) {
            remembered.remove(remembered.keys.first())
        }
    }

    private fun active(expected: Long, seriesId: SeriesId) =
        expected == version && current().activeSeries?.id == seriesId

    private suspend fun fail(expected: Long, seriesId: SeriesId, message: String) {
        if (!active(expected, seriesId)) return
        val local = if (current().content is LibraryContent.Episodes) emptyList() else try {
            withContext(dispatcher) { offlineEpisodes(seriesId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        if (!active(expected, seriesId)) return
        update { state ->
            val content = state.content
            state.copy(content = if (content is LibraryContent.Episodes && content.series.id == seriesId) {
                content.copy(refreshing = false, refreshFailure = message)
            } else if (local.isNotEmpty()) LibraryContent.Episodes(state.activeSeries!!, local,
                refreshFailure = "$message · 저장된 회차만 표시합니다", complete = false)
            else LibraryContent.Failure(message))
        }
    }

    private suspend fun safeRead(seriesId: SeriesId): EpisodeCatalogSnapshot? = try {
        readSnapshot(seriesId)?.takeIf { it.seriesId == seriesId }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun safeSave(snapshot: EpisodeCatalogSnapshot) {
        // This bounded atomic disk write must finish even if the user immediately goes back.
        try { withContext(NonCancellable) { saveSnapshot(snapshot) } }
        catch (_: Exception) { /* A full disk must not fail a successfully loaded list. */ }
    }

    private fun publishPartial(series: SourceSeries, episodes: List<SourceEpisode>, expected: Long) {
        if (!active(expected, series.id) || episodes.isEmpty()) return
        val previous = current().content as? LibraryContent.Episodes
        if (previous != null && (previous.complete || previous.items.size > episodes.size)) return
        update { it.copy(content = LibraryContent.Episodes(series, episodes, refreshing = true, complete = false)) }
    }

    private fun validate(seriesId: SeriesId, episodes: List<SourceEpisode>) {
        check(episodes.all { it.id.seriesId == seriesId } && episodes.map { it.id }.distinct().size == episodes.size) {
            "회차 목록이 올바르지 않습니다. 다시 시도해 주세요"
        }
    }

    fun cancel() {
        version++
        job?.cancel()
        detailsJob?.cancel()
        job = null
        detailsJob = null
    }
}
