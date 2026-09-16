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
import ml.melun.mangaview.source.SourceThrottledException

internal class LibraryViewModel(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val offlineStore: OfflineEpisodeStore,
    private val offlineDownloads: OfflineDownloadManager,
    private val openings: () -> EngineOpeningPreparations,
    private val ioDispatcher: CoroutineDispatcher,
    homeCache: ml.melun.mangaview.data.cache.HomeCatalogSnapshotStore? = null,
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
    private val searches = LibrarySearchController(
        viewModelScope, ioDispatcher, sourceRegistry, { mutableState.value }, ::update,
        rememberQuery = { query -> actions.updateSettings { settings ->
            settings.copy(recentQueries = (listOf(query) + settings.recentQueries.filterNot { it == query }).take(10))
        } },
        started = { cancelContent(); episodeWarmer.cancel(); statusEnrichment.cancel() },
        loaded = { statusEnrichment.enrich(it) },
    )
    private val statusEnrichment = LibraryStatusEnrichment(viewModelScope, ioDispatcher, sourceRegistry, ::update)
    private val episodeWarmer = LibraryEpisodeWarmer(openings)
    private val catalogs = LibraryCatalogLoader(
        viewModelScope, ioDispatcher, sourceRegistry, { mutableState.value }, ::update,
        { episodeWarmer.continuation(state.value) }, homeCache,
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
            is LibraryIntent.SavedQueryChanged,
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
            is LibraryIntent.SavedSelectionToggled,
            is LibraryIntent.SavedSelectionReplaced,
            LibraryIntent.SavedSelectionCleared,
            -> acceptSelection(intent)
            else -> acceptAction(intent)
        }
    }

    private fun acceptSelection(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged -> searches.queryChanged(intent.value)
            is LibraryIntent.DestinationSelected -> selectDestination(intent.value)
            is LibraryIntent.SourceSelected -> selectSource(intent.sourceId)
            is LibraryIntent.HomeKindSelected -> selectHomeKind(intent.value)
            is LibraryIntent.HomeTabSelected -> selectHomeTab(intent.value)
            is LibraryIntent.GenreSelected -> loadGenre(intent.value)
            is LibraryIntent.GenreFilterSelected -> selectGenreFilter(intent.value)
            is LibraryIntent.DetailTabSelected -> update { it.copy(detailTab = intent.value) }
            is LibraryIntent.SearchKindSelected -> searches.selectKind(intent.value)
            is LibraryIntent.SearchFieldSelected -> searches.selectField(intent.value)
            else -> uiActions.selectSaved(intent)
        }
    }

    private fun acceptAction(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.LoadMoreGenre -> genrePager.next()
            LibraryIntent.LoadMoreSearch -> searches.next()
            LibraryIntent.Search -> searches.submit()
            LibraryIntent.RetryHome -> catalogs.loadHome()
            LibraryIntent.RetryDetail -> retryDetail()
            LibraryIntent.ToggleSettings, LibraryIntent.TogglePreferences, LibraryIntent.ToggleSourcePicker ->
                uiActions.toggleOverlay(intent)
            LibraryIntent.AccountSignIn, LibraryIntent.AccountSignOut, LibraryIntent.AccountRetry -> {
                effectChannel.trySend(intent.accountEffect())
            }
            LibraryIntent.CheckForUpdate -> effectChannel.trySend(LibraryEffect.CheckForUpdate)
            LibraryIntent.OpenLicenses -> uiActions.openProjectPage("https://github.com/ad2das/mangaviewer/blob/main/LICENSE")
            LibraryIntent.ToggleSeriesMenu -> update { it.copy(seriesMenuVisible = !it.seriesMenuVisible) }
            LibraryIntent.ToggleDownloadSelection -> uiActions.toggleDownloadSelection()
            is LibraryIntent.OpenSeriesInBrowser, is LibraryIntent.ShareSeries -> uiActions.openSeriesLink(intent)
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
            is LibraryIntent.RemoveSelected -> { episodeWarmer.cancel(); uiActions.removeSelected(intent) }
            else -> uiActions.persistSettings(intent)
        }
    }

    private fun selectDestination(destination: MainDestination) {
        observers.destinationSelected()
        if (state.value.destination == destination) return
        cancelContent()
        update { it.copy(
            destination = destination,
            content = if (destination == MainDestination.SEARCH) it.searchContent else LibraryContent.Empty,
            activeSeries = null,
            activeSeriesDetails = null,
            settingsVisible = false,
            preferencesVisible = false,
            sourcePickerVisible = false,
            seriesMenuVisible = false,
            downloadSelectionVisible = false,
            savedSelection = emptySet(),
        ) }
        episodeWarmer.continuation(state.value)
        actions.updateSettings { it.copy(startTab = destination.ordinal) }
        if (destination == MainDestination.HOME && mutableState.value.home is HomeContent.Failure) catalogs.loadHome()
    }

    private fun selectSource(sourceId: ml.melun.mangaview.core.SourceId) {
        if (state.value.selectedSourceId == sourceId) {
            update { it.copy(sourcePickerVisible = false) }
            return
        }
        sourceRegistry.require(sourceId)
        cancelContent()
        catalogs.cancelGenres()
        statusEnrichment.cancel()
        genrePager.reset()
        searches.reset()
        update { it.copy(
            selectedSourceId = sourceId,
            content = LibraryContent.Empty,
            activeSeries = null,
            activeSeriesDetails = null,
            lastSeries = emptyList(),
            homeTab = HomeTab.HOME,
            genres = GenreContent.Empty,
            selectedGenre = null,
            genreStatusFilter = null,
            genreCatalog = LibraryContent.Empty,
            sourcePickerVisible = false,
            savedSelection = emptySet(),
        ) }
        actions.updateSettings { it.copy(sourceKey = sourceId.value) }
        catalogs.loadHome()
        if (state.value.destination == MainDestination.SEARCH && state.value.query.isNotBlank()) searches.submit()
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

    private fun episodes(series: SourceSeries, offlineOnly: Boolean = false) {
        val source = sourceRegistry.require(series.id.sourceId)
        update { it.copy(activeSeries = series, detailTab = DetailTab.INTRO,
            activeSeriesDetails = null,
            detailOffline = offlineOnly,
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

    private fun retryDetail() {
        val snapshot = state.value
        val series = snapshot.activeSeries ?: return
        episodes(series, offlineOnly = snapshot.detailOffline)
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
        if (state.value.savedSelection.isNotEmpty()) {
            update { it.copy(savedSelection = emptySet()) }
            return
        }
        if (uiActions.dismissOverlay()) return
        if (state.value.activeSeries != null) {
            cancelContent()
            episodeWarmer.cancel()
            update { it.copy(
                activeSeries = null,
                activeSeriesDetails = null,
                seriesMenuVisible = false,
                downloadSelectionVisible = false,
                content = if (it.destination == MainDestination.SEARCH) it.searchContent else LibraryContent.Empty,
            ) }
            return
        }
        if (state.value.selectedGenre != null) {
            cancelContent()
            genrePager.reset()
            update { it.copy(selectedGenre = null, genreCatalog = LibraryContent.Empty) }
            return
        }
        if (state.value.destination != MainDestination.HOME) selectDestination(MainDestination.HOME)
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
        is SourceThrottledException -> if (failure.retryAfterMillis > 0L) {
            "사이트 요청이 제한되었습니다. ${(failure.retryAfterMillis / 1_000L) + 1L}초 후 다시 시도해 주세요"
        } else "요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요"
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "사이트에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요"
        is java.net.SocketTimeoutException -> "응답이 늦어지고 있습니다. 잠시 후 다시 시도해 주세요"
        is java.io.IOException -> failure.message?.takeIf { it.any { char -> char in '가'..'힣' } }
            ?: "사이트에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요"
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
    private val homeCache: ml.melun.mangaview.data.cache.HomeCatalogSnapshotStore? = null,
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
            homeCache,
        ) as T
    }
}
