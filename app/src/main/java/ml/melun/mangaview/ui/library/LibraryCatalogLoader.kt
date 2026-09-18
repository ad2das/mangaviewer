package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.HomeCatalogSnapshotStore
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSeries

import kotlinx.coroutines.CoroutineScope

/** Owns cancellable home and genre loads; stale home generations cannot publish. */
internal class LibraryCatalogLoader(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val sourceRegistry: SourceRegistry,
    private val current: () -> LibraryState,
    private val update: (((LibraryState) -> LibraryState) -> Unit),
    private val onHomeReady: () -> Unit,
    private val homeCache: HomeCatalogSnapshotStore? = null,
) {
    private var homeJob: Job? = null
    private var genreJob: Job? = null
    private var homeVersion = 0L
    private var shownSource: SourceId? = null
    private var shownKind: SeriesKind? = null
    fun loadHome() {
        if (current().activeSeries != null) return
        homeJob?.cancel()
        val snapshot = current()
        val version = ++homeVersion
        // A cancelled reload must never leave another provider's cards under the current chip.
        if (!showsHomeFor(snapshot)) update { it.copy(home = HomeContent.Loading) }
        homeJob = scope.launch {
            // Paint the remembered home first; the refresh below replaces it when it arrives.
            val cached = homeCache?.load(snapshot.selectedSourceId, snapshot.homeKind)
            if (version != homeVersion) return@launch
            if (cached != null) {
                publishHome(snapshot, HomeContent.Ready(cached.popular, cached.latest, cached.new))
            } else {
                update { it.copy(home = HomeContent.Loading) }
                shownSource = null
                shownKind = null
            }
            try {
                val source = sourceRegistry.require(snapshot.selectedSourceId)
                val kind = snapshot.homeKind
                val result = withContext(ioDispatcher) { homeCatalogs(source, kind) }
                if (version == homeVersion) {
                    publishHome(snapshot, result)
                    onHomeReady()
                }
                runCatching {
                    homeCache?.save(
                        snapshot.selectedSourceId,
                        kind,
                        result.popular,
                        result.latest,
                        result.new,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == homeVersion) update { state ->
                    // A failed refresh must not replace a home the reader can still use.
                    if (state.home is HomeContent.Ready) state
                    else state.copy(
                        home = HomeContent.Failure(failureDisplayMessage(failure, "목록을 불러오지 못했습니다")),
                    )
                }
            }
        }
    }

    private fun showsHomeFor(snapshot: LibraryState): Boolean =
        snapshot.home is HomeContent.Ready &&
            shownSource == snapshot.selectedSourceId &&
            shownKind == snapshot.homeKind

    private fun publishHome(snapshot: LibraryState, content: HomeContent.Ready) {
        update { it.copy(home = content) }
        shownSource = snapshot.selectedSourceId
        shownKind = snapshot.homeKind
    }

    fun loadGenres() {
        cancelGenres()
        val snapshot = current()
        update { it.copy(genres = GenreContent.Loading) }
        genreJob = scope.launch {
            try {
                val items = withContext(ioDispatcher) {
                    sourceRegistry.require(snapshot.selectedSourceId).genres(snapshot.homeKind)
                }
                update {
                    it.copy(genres = if (items.isEmpty()) {
                        GenreContent.Failure("장르 목록을 불러오지 못했습니다")
                    } else {
                        GenreContent.Ready(items)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                update { it.copy(genres = GenreContent.Failure(failureDisplayMessage(failure, "장르 목록을 불러오지 못했습니다"))) }
            }
        }
    }

    fun cancelHome() {
        homeVersion += 1L
        homeJob?.cancel()
        homeJob = null
    }
    fun cancelGenres() = genreJob?.cancel().also { genreJob = null }
}

private suspend fun homeCatalogs(source: ContentSource, kind: SeriesKind): HomeContent.Ready =
    coroutineScope {
        val popular = async { catalogItems(source, CatalogQuery(kind, CatalogOrder.POPULAR)) }
        val latest = async { catalogItems(source, CatalogQuery(kind, CatalogOrder.LATEST)) }
        val new = async { catalogItems(source, CatalogQuery(kind, CatalogOrder.NEW)) }
        HomeContent.Ready(popular.await(), latest.await(), new.await())
    }

/**
 * Blocked provider routes routinely answer on the second attempt once the transport recovery is
 * warm, so one silent retry turns a home failure into a slower home.
 */
private suspend fun catalogItems(source: ContentSource, query: CatalogQuery): List<SourceSeries> =
    try {
        source.catalog(query).items
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (firstAttempt: Exception) {
        try {
            source.catalog(query).items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (secondAttempt: Exception) {
            firstAttempt.addSuppressed(secondAttempt)
            throw firstAttempt
        }
    }

