package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SeriesKind

import kotlinx.coroutines.CoroutineScope

/** Owns cancellable home and genre loads; stale home generations cannot publish. */
internal class LibraryCatalogLoader(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val sourceRegistry: SourceRegistry,
    private val current: () -> LibraryState,
    private val update: (((LibraryState) -> LibraryState) -> Unit),
    private val onHomeReady: () -> Unit,
) {
    private var homeJob: Job? = null
    private var genreJob: Job? = null
    private var homeVersion = 0L
    fun loadHome() {
        homeJob?.cancel()
        val snapshot = current()
        val version = ++homeVersion
        update { it.copy(home = HomeContent.Loading) }
        homeJob = scope.launch {
            val source = sourceRegistry.require(snapshot.selectedSourceId)
            try {
                val result = withContext(ioDispatcher) { homeCatalogs(source, snapshot.homeKind) }
                if (version == homeVersion) {
                    update { it.copy(home = result) }
                    onHomeReady()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == homeVersion) update {
                    it.copy(home = HomeContent.Failure(failureDisplayMessage(failure, "목록을 불러오지 못했습니다")))
                }
            }
        }
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
        val popular = async { source.catalog(CatalogQuery(kind, CatalogOrder.POPULAR)).items }
        val latest = async { source.catalog(CatalogQuery(kind, CatalogOrder.LATEST)).items }
        val new = async { source.catalog(CatalogQuery(kind, CatalogOrder.NEW)).items }
        HomeContent.Ready(popular.await(), latest.await(), new.await())
    }

