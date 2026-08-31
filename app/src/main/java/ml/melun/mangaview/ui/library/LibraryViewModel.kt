package ml.melun.mangaview.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSearchQuery

internal class LibraryViewModel(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val offlineStore: OfflineEpisodeStore,
    private val offlineDownloads: OfflineDownloadManager,
    private val pageRepository: PageRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val actions = LibraryActions(viewModelScope, ioDispatcher, sourceRegistry, userLibrary, offlineDownloads)
    private val mutableState = MutableStateFlow(initialLibraryState(sourceRegistry))
    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)
    private val uiActions = LibraryUiActions(
        viewModelScope,
        actions,
        offlineDownloads,
        current = { mutableState.value },
        update = ::update,
        emit = { effect -> effectChannel.trySend(effect) },
    )
    private val episodeWarmer = LibraryEpisodeWarmer(
        viewModelScope,
        ioDispatcher,
        sourceRegistry,
        pageRepository,
        userLibrary,
    )
    private var contentJob: Job? = null
    private var homeJob: Job? = null
    private var genreJob: Job? = null
    private var contentVersion = 0L
    private var homeVersion = 0L
    private val observers = LibraryStateObservers(sourceRegistry, userLibrary, offlineStore, offlineDownloads)

    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()
    init {
        observers.start(viewModelScope, ::update, ::loadHome) {
            mostLikelyContinuation(state.value)?.let(episodeWarmer::warm)
        }
        loadHome()
    }
    fun accept(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged,
            is LibraryIntent.DestinationSelected,
            is LibraryIntent.SourceSelected,
            is LibraryIntent.HomeKindSelected,
            is LibraryIntent.HomeTabSelected,
            is LibraryIntent.SavedTabSelected,
            is LibraryIntent.GenreSelected,
            is LibraryIntent.DetailTabSelected,
            is LibraryIntent.SearchKindSelected,
            is LibraryIntent.SearchFieldSelected,
            -> acceptSelection(intent)
            else -> acceptAction(intent)
        }
    }

    private fun acceptSelection(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged -> update { it.copy(query = intent.value) }
            is LibraryIntent.DestinationSelected -> selectDestination(intent.value)
            is LibraryIntent.SourceSelected -> selectSource(intent.sourceId)
            is LibraryIntent.HomeKindSelected -> selectHomeKind(intent.value)
            is LibraryIntent.HomeTabSelected -> selectHomeTab(intent.value)
            is LibraryIntent.SavedTabSelected -> update { it.copy(libraryTab = intent.value) }
            is LibraryIntent.GenreSelected -> loadGenre(intent.value)
            is LibraryIntent.DetailTabSelected -> update { it.copy(detailTab = intent.value) }
            is LibraryIntent.SearchKindSelected -> update { it.copy(searchKind = intent.value) }
            is LibraryIntent.SearchFieldSelected -> update { it.copy(searchField = intent.value) }
            else -> error("Not a selection intent: $intent")
        }
    }

    private fun acceptAction(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.Search -> search()
            LibraryIntent.RetryHome -> loadHome()
            LibraryIntent.ToggleSettings -> update { it.copy(settingsVisible = !it.settingsVisible) }
            LibraryIntent.TogglePreferences -> update {
                it.copy(preferencesVisible = !it.preferencesVisible, settingsVisible = false)
            }
            LibraryIntent.AccountSignIn -> uiActions.showMessage("계정 동기화 설정이 이 빌드에 연결되어 있지 않습니다")
            LibraryIntent.CheckForUpdate -> uiActions.openProjectPage("https://github.com/ad2das/mangaviewer/releases")
            LibraryIntent.OpenLicenses -> uiActions.openProjectPage("https://github.com/ad2das/mangaviewer/blob/main/LICENSE")
            LibraryIntent.ToggleSeriesMenu -> update { it.copy(seriesMenuVisible = !it.seriesMenuVisible) }
            LibraryIntent.ToggleDownloadSelection -> uiActions.toggleDownloadSelection()
            is LibraryIntent.OpenSeriesInBrowser -> uiActions.resolveSeriesUrl(intent.series) { url ->
                LibraryEffect.OpenUri(url)
            }
            is LibraryIntent.ShareSeries -> uiActions.resolveSeriesUrl(intent.series) { url ->
                LibraryEffect.ShareText(intent.series.title, "${intent.series.title}\n$url")
            }
            LibraryIntent.Back -> back()
            else -> acceptContentAction(intent)
        }
    }

    private fun acceptContentAction(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.SeriesSelected -> episodes(intent.series)
            is LibraryIntent.EpisodeSelected -> openEpisode(intent.episodeId, currentSeries(state.value))
            is LibraryIntent.SavedSeriesSelected -> openSavedSeries(intent.series)
            is LibraryIntent.OfflineSeriesSelected -> episodes(intent.series, offlineOnly = true)
            is LibraryIntent.SavedEpisodeSelected -> openSavedPosition(intent.position)
            is LibraryIntent.FavoriteToggled -> actions.toggleFavorite(
                intent.series,
                state.value.saved.favorites.any { it.id == intent.series.id },
            )
            is LibraryIntent.DownloadEpisode -> uiActions.download(intent.series, listOf(intent.episode))
            is LibraryIntent.DownloadEpisodes -> uiActions.download(intent.series, intent.episodes)
            is LibraryIntent.RemoveOfflineEpisode -> update { it.copy(pendingOfflineRemoval = intent.episodeId) }
            LibraryIntent.CancelOfflineRemoval -> update { it.copy(pendingOfflineRemoval = null) }
            LibraryIntent.ConfirmOfflineRemoval -> confirmOfflineRemoval()
            is LibraryIntent.StartTabChanged -> actions.updateSettings { it.copy(startTab = intent.value) }
            is LibraryIntent.DarkThemeChanged -> actions.updateSettings { it.copy(darkTheme = intent.enabled) }
            else -> error("Not an action intent: $intent")
        }
    }

    private fun selectDestination(destination: MainDestination) {
        episodeWarmer.cancel()
        update { it.copy(
            destination = destination,
            content = LibraryContent.Empty,
            settingsVisible = false,
            preferencesVisible = false,
            seriesMenuVisible = false,
            downloadSelectionVisible = false,
        ) }
        actions.updateSettings { it.copy(startTab = destination.ordinal) }
        if (destination == MainDestination.HOME && mutableState.value.home is HomeContent.Failure) loadHome()
    }

    private fun selectSource(sourceId: ml.melun.mangaview.core.SourceId) {
        sourceRegistry.require(sourceId)
        cancelContent()
        cancelGenres()
        episodeWarmer.cancel()
        update { it.copy(
            selectedSourceId = sourceId,
            content = LibraryContent.Empty,
            homeTab = HomeTab.HOME,
            genres = GenreContent.Empty,
            selectedGenre = null,
            genreCatalog = LibraryContent.Empty,
        ) }
        actions.updateSettings { it.copy(sourceKey = sourceId.value) }
        loadHome()
    }

    private fun selectHomeKind(kind: ml.melun.mangaview.source.SeriesKind) {
        if (mutableState.value.homeKind == kind) return
        cancelGenres()
        update { it.copy(
            homeKind = kind,
            homeTab = HomeTab.HOME,
            genres = GenreContent.Empty,
            selectedGenre = null,
            genreCatalog = LibraryContent.Empty,
        ) }
        actions.updateSettings { it.copy(seriesKind = kind.ordinal) }
        loadHome()
    }

    private fun selectHomeTab(tab: HomeTab) {
        update { it.copy(homeTab = tab) }
        if (tab == HomeTab.GENRES) {
            cancelHome()
            if (mutableState.value.genres !is GenreContent.Ready) loadGenres()
        } else if (mutableState.value.home !is HomeContent.Ready) {
            loadHome()
        }
    }

    private fun loadHome() {
        homeJob?.cancel()
        val snapshot = mutableState.value
        val version = ++homeVersion
        update { it.copy(home = HomeContent.Loading) }
        homeJob = viewModelScope.launch {
            val source = sourceRegistry.require(snapshot.selectedSourceId)
            try {
                val result = withContext(ioDispatcher) { homeCatalogs(source, snapshot.homeKind) }
                if (version == homeVersion) {
                    update { it.copy(home = result) }
                    mostLikelyContinuation(state.value)?.let(episodeWarmer::warm)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == homeVersion) update {
                    it.copy(home = HomeContent.Failure(failure.message ?: "목록을 불러오지 못했습니다"))
                }
            }
        }
    }

    private fun loadGenres() {
        cancelGenres()
        val snapshot = mutableState.value
        update { it.copy(genres = GenreContent.Loading) }
        genreJob = viewModelScope.launch {
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
                update { it.copy(genres = GenreContent.Failure(failure.message ?: "장르 목록을 불러오지 못했습니다")) }
            }
        }
    }

    private fun loadGenre(genre: ml.melun.mangaview.source.SourceGenre) {
        cancelHome()
        cancelContent()
        val snapshot = mutableState.value
        val version = ++contentVersion
        update { it.copy(
            homeTab = HomeTab.GENRES,
            selectedGenre = genre,
            genreCatalog = LibraryContent.Loading,
        ) }
        contentJob = viewModelScope.launch {
            try {
                val items = withContext(ioDispatcher) {
                    sourceRegistry.require(snapshot.selectedSourceId).catalog(
                        CatalogQuery(snapshot.homeKind, CatalogOrder.LATEST, genre),
                    ).items
                }
                if (version == contentVersion) update {
                    it.copy(
                        genreCatalog = if (items.isEmpty()) {
                            LibraryContent.Failure("${genre.label} 장르에 등록된 작품이 없습니다")
                        } else {
                            LibraryContent.Series(items)
                        },
                        lastSeries = items,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == contentVersion) update {
                    it.copy(genreCatalog = LibraryContent.Failure(
                        failure.message ?: "장르 작품을 불러오지 못했습니다",
                    ))
                }
            }
        }
    }

    private fun search() {
        val snapshot = state.value
        val query = snapshot.query.trim()
        if (query.isEmpty()) return
        val source = sourceRegistry.require(snapshot.selectedSourceId)
        episodeWarmer.cancel()
        launchContent(
            load = {
                source.search(SourceSearchQuery(query, snapshot.searchKind, snapshot.searchField)).items
            },
            success = { result: List<SourceSeries> ->
                update { it.copy(content = LibraryContent.Series(result), lastSeries = result) }
            },
            failureMessage = "검색에 실패했습니다",
        )
    }

    private fun episodes(series: SourceSeries, offlineOnly: Boolean = false) {
        val source = sourceRegistry.require(series.id.sourceId)
        update { it.copy(activeSeries = series, detailTab = DetailTab.INTRO,
            selectedSourceId = if (offlineOnly) series.id.sourceId else it.selectedSourceId,
            lastSeries = if (offlineOnly) listOf(series) else it.lastSeries,
        ) }
        launchContent(
            load = {
                if (offlineOnly) offlineStore.episodes(series.id)
                else source.episodes(series.id).items
            },
            success = { result: List<SourceEpisode> ->
                update { it.copy(content = LibraryContent.Episodes(series, result)) }
                preferredEpisode(state.value, series, result)?.let(episodeWarmer::warm)
            },
            failureMessage = "회차를 불러오지 못했습니다",
        )
    }

    private fun <T> launchContent(
        load: suspend () -> T,
        success: (T) -> Unit,
        failureMessage: String,
    ) {
        cancelContent()
        val version = ++contentVersion
        update { it.copy(content = LibraryContent.Loading) }
        contentJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { load() }
                if (version == contentVersion) success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == contentVersion) update {
                    it.copy(content = LibraryContent.Failure(failure.message ?: failureMessage))
                }
            }
        }
    }

    private fun back() {
        if (state.value.pendingOfflineRemoval != null) {
            update { it.copy(pendingOfflineRemoval = null) }
            return
        }
        if (state.value.downloadSelectionVisible) {
            update { it.copy(downloadSelectionVisible = false) }
            return
        }
        if (state.value.preferencesVisible) {
            update { it.copy(preferencesVisible = false, settingsVisible = true) }
            return
        }
        if (state.value.settingsVisible) {
            update { it.copy(settingsVisible = false) }
            return
        }
        if (state.value.seriesMenuVisible) {
            update { it.copy(seriesMenuVisible = false) }
            return
        }
        if (state.value.activeSeries != null) {
            cancelContent()
            episodeWarmer.cancel()
            val series = state.value.lastSeries
            update { it.copy(
                activeSeries = null,
                seriesMenuVisible = false,
                downloadSelectionVisible = false,
                content = if (series.isEmpty()) LibraryContent.Empty else LibraryContent.Series(series),
            ) }
            return
        }
        if (state.value.selectedGenre != null) {
            cancelContent()
            update { it.copy(selectedGenre = null, genreCatalog = LibraryContent.Empty) }
        }
    }

    private fun confirmOfflineRemoval() {
        state.value.pendingOfflineRemoval?.let(uiActions::removeOffline)
        update { it.copy(pendingOfflineRemoval = null) }
    }

    private fun openSavedSeries(saved: SavedSeries) {
        val series = saved.asSourceSeries()
        update { it.copy(selectedSourceId = series.id.sourceId, lastSeries = listOf(series)) }
        episodes(series, offlineOnly = saved.updatedAtEpochMillis == 0L)
    }

    private fun openEpisode(episodeId: EpisodeId, series: SourceSeries) {
        episodeWarmer.warm(episodeId)
        val episode = SourceEpisode(episodeId, episodeId.remoteKey)
        actions.recordOpened(series, episode)
        effectChannel.trySend(LibraryEffect.OpenEpisode(episodeId))
    }

    private fun openSavedPosition(position: ml.melun.mangaview.core.ReadingPosition) {
        episodeWarmer.warm(position.pageId.episodeId)
        effectChannel.trySend(LibraryEffect.OpenEpisode(position.pageId.episodeId, position))
    }

    private fun cancelContent() {
        contentVersion += 1L
        contentJob?.cancel()
        contentJob = null
    }

    private fun cancelHome() {
        homeVersion += 1L
        homeJob?.cancel()
        homeJob = null
    }

    private fun cancelGenres() = genreJob?.cancel().also { genreJob = null }

    private fun update(transform: (LibraryState) -> LibraryState) {
        mutableState.value = transform(mutableState.value)
    }

}

private suspend fun homeCatalogs(source: ContentSource, kind: SeriesKind): HomeContent.Ready =
    coroutineScope {
        val popular = async { source.catalog(CatalogQuery(kind, CatalogOrder.POPULAR)).items }
        val latest = async { source.catalog(CatalogQuery(kind, CatalogOrder.LATEST)).items }
        val new = async { source.catalog(CatalogQuery(kind, CatalogOrder.NEW)).items }
        HomeContent.Ready(popular.await(), latest.await(), new.await())
    }

private fun currentSeries(state: LibraryState): SourceSeries =
    state.activeSeries ?: error("Episode selection requires an active series")

private fun preferredEpisode(
    state: LibraryState,
    series: SourceSeries,
    episodes: List<SourceEpisode>,
): EpisodeId? {
    val recent = state.saved.recent.firstOrNull { it.series.id == series.id }?.episodeId
    return episodes.firstOrNull { it.id == recent }?.id ?: firstEpisode(episodes)?.id
}

private fun mostLikelyContinuation(state: LibraryState): EpisodeId? {
    if (state.destination != MainDestination.HOME) return null
    return state.saved.recent.firstOrNull {
        it.series.id.sourceId == state.selectedSourceId
    }?.episodeId
}

private fun initialLibraryState(sourceRegistry: SourceRegistry): LibraryState {
    val options = sourceRegistry.options
    return LibraryState(query = "", sources = options, selectedSourceId = options.first().id)
}

private fun SavedSeries.asSourceSeries() = SourceSeries(id, title, thumbnailKey = thumbnailKey)

internal fun firstEpisode(episodes: List<SourceEpisode>): SourceEpisode? {
    episodes.filter { it.sequenceNumber != null }
        .minByOrNull { requireNotNull(it.sequenceNumber) }
        ?.let { return it }
    episodes.filter { it.publishedAtEpochMillis != null }
        .minByOrNull { requireNotNull(it.publishedAtEpochMillis) }
        ?.let { return it }
    // Provider contracts expose episode lists newest-first when no explicit ordering metadata
    // exists, so the final entry is the oldest safe fallback.
    return episodes.lastOrNull()
}

internal class LibraryViewModelFactory(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val offlineStore: OfflineEpisodeStore,
    private val offlineDownloads: OfflineDownloadManager,
    private val pageRepository: PageRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(
            sourceRegistry,
            userLibrary,
            offlineStore,
            offlineDownloads,
            pageRepository,
            ioDispatcher,
        ) as T
    }
}
