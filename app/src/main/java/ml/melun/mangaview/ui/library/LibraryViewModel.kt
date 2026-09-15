package ml.melun.mangaview.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.app.EngineOpeningPreparations
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.library.UserLibrarySnapshot
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceThrottledException

internal class LibraryViewModel(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val offlineStore: OfflineEpisodeStore,
    private val offlineDownloads: OfflineDownloadManager,
    private val openings: () -> EngineOpeningPreparations,
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
    private val genrePager = GenreCatalogPager(viewModelScope, ioDispatcher) { catalog ->
        update { it.copy(genreCatalog = catalog,
            lastSeries = (catalog as? LibraryContent.Series)?.items ?: it.lastSeries) }
        if (catalog is LibraryContent.Series) statusEnrichment.enrich(catalog.items)
    }
    private val statusEnrichment = LibraryStatusEnrichment(viewModelScope, ioDispatcher, sourceRegistry, ::update)
    private val episodeWarmer = LibraryEpisodeWarmer(openings)
    private val catalogs = LibraryCatalogLoader(
        viewModelScope, ioDispatcher, sourceRegistry, { mutableState.value }, ::update,
        { episodeWarmer.continuation(state.value) },
    )
    private var contentJob: Job? = null
    private var detailsJob: Job? = null
    private var contentVersion = 0L
    private val observers = LibraryStateObservers(sourceRegistry, userLibrary, offlineStore, offlineDownloads)

    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()
    override fun onCleared() {
        episodeWarmer.cancel()
        super.onCleared()
    }
    init {
        observers.start(viewModelScope, ::update, catalogs::loadHome) {
            episodeWarmer.continuation(state.value)
        }
        catalogs.loadHome()
    }
    fun foreground(value: Boolean) = episodeWarmer.foreground(value, state.value)
    fun accept(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged,
            is LibraryIntent.DestinationSelected,
            is LibraryIntent.SourceSelected,
            is LibraryIntent.HomeKindSelected,
            is LibraryIntent.HomeTabSelected,
            is LibraryIntent.SavedTabSelected,
            is LibraryIntent.GenreSelected,
            is LibraryIntent.GenreFilterSelected,
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
            is LibraryIntent.GenreFilterSelected -> selectGenreFilter(intent.value)
            is LibraryIntent.DetailTabSelected -> update { it.copy(detailTab = intent.value) }
            is LibraryIntent.SearchKindSelected -> update { it.copy(searchKind = intent.value) }
            is LibraryIntent.SearchFieldSelected -> update { it.copy(searchField = intent.value) }
            else -> error("Not a selection intent: $intent")
        }
    }

    private fun acceptAction(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.LoadMoreGenre -> genrePager.next()
            LibraryIntent.Search -> search()
            LibraryIntent.RetryHome -> catalogs.loadHome()
            LibraryIntent.ToggleSettings, LibraryIntent.TogglePreferences, LibraryIntent.ToggleSourcePicker ->
                uiActions.toggleOverlay(intent)
            LibraryIntent.AccountSignIn, LibraryIntent.AccountSignOut, LibraryIntent.AccountRetry -> {
                effectChannel.trySend(intent.accountEffect())
            }
            LibraryIntent.CheckForUpdate -> effectChannel.trySend(LibraryEffect.CheckForUpdate)
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
            is LibraryIntent.OfflineSeriesSelected -> {
                switchSourceForSeries(intent.series)
                episodes(intent.series, offlineOnly = true)
            }
            is LibraryIntent.SavedEpisodeSelected -> openSavedPosition(intent.position)
            is LibraryIntent.RemoveBookmark -> actions.removeBookmark(intent.bookmark)
            is LibraryIntent.ResumeEpisode -> {
                episodeWarmer.warm(intent.episodeId)
                // Load the current exact source anchor, with legacy history as its existing fallback.
                effectChannel.trySend(LibraryEffect.OpenEpisode(intent.episodeId))
            }
            is LibraryIntent.FavoriteToggled -> actions.toggleFavorite(
                intent.series,
                state.value.saved.favorites.any { it.id == intent.series.id },
            )
            is LibraryIntent.DownloadEpisode -> uiActions.download(intent.series, listOf(intent.episode))
            is LibraryIntent.DownloadEpisodes -> uiActions.download(intent.series, intent.episodes)
            is LibraryIntent.RemoveOfflineEpisode -> update { it.copy(pendingOfflineRemoval = intent.episodeId) }
            LibraryIntent.CancelOfflineRemoval -> update { it.copy(pendingOfflineRemoval = null) }
            LibraryIntent.ConfirmOfflineRemoval -> confirmOfflineRemoval()
            else -> acceptPersistenceAction(intent)
        }
    }

    private fun acceptPersistenceAction(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.RemoveSavedItem -> { episodeWarmer.cancel(); uiActions.removeSaved(intent.item) }
            is LibraryIntent.StartTabChanged -> actions.updateSettings { it.copy(startTab = intent.value) }
            is LibraryIntent.DarkThemeChanged -> actions.updateSettings { it.copy(darkTheme = intent.enabled) }
            LibraryIntent.ClearSearchHistory -> actions.updateSettings { it.copy(recentQueries = emptyList()) }
            is LibraryIntent.RemoveSearchHistory -> actions.updateSettings { settings ->
                settings.copy(recentQueries = settings.recentQueries.filterNot { it == intent.value })
            }
            else -> error("Not an action intent: $intent")
        }
    }

    private fun selectDestination(destination: MainDestination) {
        observers.destinationSelected()
        update { it.copy(
            destination = destination,
            content = LibraryContent.Empty,
            settingsVisible = false,
            preferencesVisible = false,
            sourcePickerVisible = false,
            seriesMenuVisible = false,
            downloadSelectionVisible = false,
        ) }
        episodeWarmer.continuation(state.value)
        actions.updateSettings { it.copy(startTab = destination.ordinal) }
        if (destination == MainDestination.HOME && mutableState.value.home is HomeContent.Failure) catalogs.loadHome()
    }

    private fun selectSource(sourceId: ml.melun.mangaview.core.SourceId) {
        sourceRegistry.require(sourceId)
        cancelContent()
        catalogs.cancelGenres()
        statusEnrichment.cancel()
        genrePager.reset()
        update { it.copy(
            selectedSourceId = sourceId,
            content = LibraryContent.Empty,
            homeTab = HomeTab.HOME,
            genres = GenreContent.Empty,
            selectedGenre = null,
            genreStatusFilter = null,
            genreCatalog = LibraryContent.Empty,
            sourcePickerVisible = false,
        ) }
        actions.updateSettings { it.copy(sourceKey = sourceId.value) }
        catalogs.loadHome()
    }

    private fun selectHomeKind(kind: ml.melun.mangaview.source.SeriesKind) {
        if (mutableState.value.homeKind == kind) return
        catalogs.cancelGenres()
        statusEnrichment.cancel()
        genrePager.reset()
        update { it.copy(
            homeKind = kind,
            homeTab = HomeTab.HOME,
            genres = GenreContent.Empty,
            selectedGenre = null,
            genreStatusFilter = null,
            genreCatalog = LibraryContent.Empty,
        ) }
        actions.updateSettings { it.copy(seriesKind = kind.ordinal) }
        catalogs.loadHome()
    }

    private fun selectHomeTab(tab: HomeTab) {
        update { it.copy(homeTab = tab) }
        if (tab == HomeTab.GENRES) {
            catalogs.cancelHome()
            if (mutableState.value.genres !is GenreContent.Ready) catalogs.loadGenres()
        } else if (mutableState.value.home !is HomeContent.Ready) {
            catalogs.loadHome()
        }
    }

    private fun loadGenre(genre: ml.melun.mangaview.source.SourceGenre) {
        catalogs.cancelHome()
        cancelContent()
        val snapshot = state.value
        update { it.copy(homeTab = HomeTab.GENRES, selectedGenre = genre) }
        genrePager.start { cursor ->
            sourceRegistry.require(snapshot.selectedSourceId).catalog(
                CatalogQuery(
                    snapshot.homeKind,
                    CatalogOrder.LATEST,
                    genre,
                    cursor,
                    snapshot.genreStatusFilter,
                ),
            )
        }
    }

    private fun selectGenreFilter(status: SeriesStatus?) {
        if (state.value.genreStatusFilter == status) return
        val genre = state.value.selectedGenre ?: return
        update { it.copy(genreStatusFilter = status) }
        loadGenre(genre)
    }

    private fun search() {
        val snapshot = state.value
        val query = snapshot.query.trim()
        if (query.isEmpty()) return
        val source = sourceRegistry.require(snapshot.selectedSourceId)
        episodeWarmer.cancel()
        actions.updateSettings { settings ->
            settings.copy(recentQueries = (listOf(query) + settings.recentQueries.filterNot { it == query }).take(10))
        }
        launchContent(
            load = {
                source.search(SourceSearchQuery(query, snapshot.searchKind, snapshot.searchField)).items
            },
            success = { result: List<SourceSeries> ->
                update { it.copy(content = LibraryContent.Series(result), lastSeries = result) }
                statusEnrichment.enrich(result)
            },
            failureMessage = "검색에 실패했습니다",
        )
    }

    private fun episodes(series: SourceSeries, offlineOnly: Boolean = false) {
        val source = sourceRegistry.require(series.id.sourceId)
        update { it.copy(activeSeries = series, detailTab = DetailTab.INTRO,
            activeSeriesDetails = null,
            selectedSourceId = if (offlineOnly) series.id.sourceId else it.selectedSourceId,
            lastSeries = if (offlineOnly) listOf(series) else it.lastSeries,
        ) }
        // The reader's own series is almost always continued at the remembered episode, so start
        // that preparation before the list round-trip finishes; the list result reconciles it.
        if (!offlineOnly) {
            recentEpisodeFor(state.value.saved, series.id)?.let(episodeWarmer::warm)
        }
        launchContent(
            load = {
                if (offlineOnly) offlineStore.episodes(series.id)
                else source.episodes(series.id).items
            },
            success = { result: List<SourceEpisode> ->
                update { it.copy(content = LibraryContent.Episodes(series, result)) }
                preferredEpisode(state.value, series, result)?.let(episodeWarmer::warm)
                if (!offlineOnly) loadSeriesDetails(source, series)
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
                    it.copy(content = LibraryContent.Failure(failureDisplayMessage(failure, failureMessage)))
                }
            }
        }
    }

    private fun back() {
        if (uiActions.dismissOverlay()) return
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
            genrePager.reset()
            update { it.copy(selectedGenre = null, genreCatalog = LibraryContent.Empty) }
        }
    }

    private fun confirmOfflineRemoval() {
        state.value.pendingOfflineRemoval?.let(uiActions::removeOffline)
        update { it.copy(pendingOfflineRemoval = null) }
    }

    private fun openSavedSeries(saved: SavedSeries) {
        val series = saved.asSourceSeries()
        switchSourceForSeries(series)
        update { it.copy(lastSeries = listOf(series)) }
        episodes(series, offlineOnly = saved.updatedAtEpochMillis == 0L)
    }

    /**
     * Opening a series owned by another provider completes the provider switch (home, genres,
     * persisted settings) so the header chip can never disagree with the list below it.
     */
    private fun switchSourceForSeries(series: SourceSeries) {
        if (series.id.sourceId != mutableState.value.selectedSourceId) {
            selectSource(series.id.sourceId)
        }
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
        detailsJob?.cancel()
        detailsJob = null
    }

    private fun loadSeriesDetails(source: ContentSource, series: SourceSeries) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val details = runCatching {
                withContext(ioDispatcher) { source.seriesDetails(series.id) }
            }.getOrNull()
            if (state.value.activeSeries?.id == series.id) {
                update { it.copy(activeSeriesDetails = details) }
            }
        }
    }

    private fun update(transform: (LibraryState) -> LibraryState) {
        mutableState.value = transform(mutableState.value)
    }
}

/** Turns provider-specific failures into reader-friendly Korean copy. */
internal fun failureDisplayMessage(failure: Throwable, fallback: String): String =
    when (failure) {
        is SourceThrottledException -> "요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요"
        else -> failure.message ?: fallback
    }

private fun currentSeries(state: LibraryState): SourceSeries =
    state.activeSeries ?: error("Episode selection requires an active series")

private fun preferredEpisode(
    state: LibraryState,
    series: SourceSeries,
    episodes: List<SourceEpisode>,
): EpisodeId? {
    val recent = recentEpisodeFor(state.saved, series.id)
    return episodes.firstOrNull { it.id == recent }?.id ?: firstEpisode(episodes)?.id
}

/** The episode a returning reader is most likely to tap as soon as the series detail opens. */
internal fun recentEpisodeFor(saved: UserLibrarySnapshot, seriesId: SeriesId): EpisodeId? =
    saved.recent.firstOrNull { it.series.id == seriesId }?.episodeId

internal fun mostLikelyContinuation(state: LibraryState): EpisodeId? {
    // An explicitly opened series takes priority over a late home/library refresh.
    if (state.content is LibraryContent.Episodes) return null
    return state.saved.recent.firstOrNull()?.episodeId
}

private fun initialLibraryState(sourceRegistry: SourceRegistry): LibraryState {
    val options = sourceRegistry.options
    return LibraryState(query = "", sources = options, selectedSourceId = options.first().id)
}

private fun SavedSeries.asSourceSeries() = SourceSeries(id, title, thumbnailKey = thumbnailKey)

/**
 * The delivered list order is the display order: providers list episodes newest-first, so the
 * bottom-most entry is always the earliest chapter. Numbers parsed out of titles must never win
 * this choice (a special such as "외전-1화" parses to 1 but is not the first chapter).
 */
internal fun firstEpisode(episodes: List<SourceEpisode>): SourceEpisode? = episodes.lastOrNull()

internal class LibraryViewModelFactory(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val offlineStore: OfflineEpisodeStore,
    private val offlineDownloads: OfflineDownloadManager,
    private val openings: () -> EngineOpeningPreparations,
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
            openings,
            ioDispatcher,
        ) as T
    }
}
