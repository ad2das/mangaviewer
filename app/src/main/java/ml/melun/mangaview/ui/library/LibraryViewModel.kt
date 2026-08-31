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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SeriesKind

internal class LibraryViewModel(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState())
    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)
    private var contentJob: Job? = null
    private var homeJob: Job? = null
    private var warmupJob: Job? = null
    private var warmingEpisodeId: EpisodeId? = null
    private var contentVersion = 0L
    private var homeVersion = 0L
    private var restoredDestination = false

    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        observeSavedState()
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
            is LibraryIntent.HomeTabSelected -> update { it.copy(homeTab = intent.value) }
            is LibraryIntent.SavedTabSelected -> update { it.copy(libraryTab = intent.value) }
            is LibraryIntent.GenreSelected -> loadGenre(intent.value)
            else -> error("Not a selection intent: $intent")
        }
    }

    private fun acceptAction(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.Search -> search()
            LibraryIntent.RetryHome -> loadHome()
            LibraryIntent.ToggleSettings -> update { it.copy(settingsVisible = !it.settingsVisible) }
            is LibraryIntent.SeriesSelected -> episodes(intent.series)
            is LibraryIntent.EpisodeSelected -> openEpisode(intent.episodeId, currentSeries())
            is LibraryIntent.SavedSeriesSelected -> openSavedSeries(intent.series)
            is LibraryIntent.SavedEpisodeSelected -> effectChannel.trySend(
                LibraryEffect.OpenEpisode(intent.position.pageId.episodeId, intent.position),
            )
            is LibraryIntent.FavoriteToggled -> toggleFavorite(intent.series)
            is LibraryIntent.DarkThemeChanged -> updateSettings { it.copy(darkTheme = intent.enabled) }
            LibraryIntent.Back -> back()
            else -> error("Not an action intent: $intent")
        }
    }

    private fun observeSavedState() {
        viewModelScope.launch {
            userLibrary.snapshot.collectLatest { snapshot ->
                var reloadHome = false
                update { state ->
                    if (restoredDestination) return@update state.copy(saved = snapshot)
                    val destination = run {
                        restoredDestination = true
                        MainDestination.fromStored(snapshot.settings.startTab)
                    }
                    val sourceId = state.sources.firstOrNull {
                        it.id.value == snapshot.settings.sourceKey
                    }?.id ?: state.selectedSourceId
                    val kind = SeriesKind.entries.getOrElse(snapshot.settings.seriesKind) { SeriesKind.WEBTOON }
                    reloadHome = sourceId != state.selectedSourceId || kind != state.homeKind
                    state.copy(
                        saved = snapshot,
                        destination = destination,
                        selectedSourceId = sourceId,
                        homeKind = kind,
                    )
                }
                if (reloadHome) loadHome()
            }
        }
    }

    private fun selectDestination(destination: MainDestination) {
        cancelWarmup()
        update { it.copy(destination = destination, content = LibraryContent.Empty, settingsVisible = false) }
        updateSettings { it.copy(startTab = destination.ordinal) }
        if (destination == MainDestination.HOME && mutableState.value.home is HomeContent.Failure) loadHome()
    }

    private fun selectSource(sourceId: ml.melun.mangaview.core.SourceId) {
        sourceRegistry.require(sourceId)
        cancelContent()
        cancelWarmup()
        update { it.copy(selectedSourceId = sourceId, content = LibraryContent.Empty) }
        updateSettings { it.copy(sourceKey = sourceId.value) }
        loadHome()
    }

    private fun selectHomeKind(kind: ml.melun.mangaview.source.SeriesKind) {
        if (mutableState.value.homeKind == kind) return
        update { it.copy(homeKind = kind, homeTab = HomeTab.HOME) }
        updateSettings { it.copy(seriesKind = kind.ordinal) }
        loadHome()
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
                if (version == homeVersion) update { it.copy(home = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == homeVersion) update {
                    it.copy(home = HomeContent.Failure(failure.message ?: "목록을 불러오지 못했습니다"))
                }
            }
        }
    }

    private suspend fun homeCatalogs(
        source: ContentSource,
        kind: ml.melun.mangaview.source.SeriesKind,
    ): HomeContent.Ready = coroutineScope {
        val popular = async { source.catalog(CatalogQuery(kind, CatalogOrder.POPULAR)).items }
        val latest = async { source.catalog(CatalogQuery(kind, CatalogOrder.LATEST)).items }
        val new = async { source.catalog(CatalogQuery(kind, CatalogOrder.NEW)).items }
        HomeContent.Ready(popular.await(), latest.await(), new.await())
    }

    private fun loadGenre(genre: String) {
        homeJob?.cancel()
        val snapshot = mutableState.value
        val version = ++homeVersion
        update { it.copy(homeTab = HomeTab.GENRES, home = HomeContent.Loading) }
        homeJob = viewModelScope.launch {
            try {
                val items = withContext(ioDispatcher) {
                    sourceRegistry.require(snapshot.selectedSourceId).catalog(
                        CatalogQuery(snapshot.homeKind, CatalogOrder.LATEST, genre),
                    ).items
                }
                if (version == homeVersion) update {
                    it.copy(home = HomeContent.Ready(items, items, items, genre))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == homeVersion) update {
                    it.copy(home = HomeContent.Failure(failure.message ?: "장르 목록을 불러오지 못했습니다"))
                }
            }
        }
    }

    private fun search() {
        val snapshot = state.value
        val query = snapshot.query.trim()
        if (query.isEmpty()) return
        val source = sourceRegistry.require(snapshot.selectedSourceId)
        cancelWarmup()
        launchContent(
            load = { source.search(query).items },
            success = { result: List<SourceSeries> ->
                update { it.copy(content = LibraryContent.Series(result), lastSeries = result) }
            },
            failureMessage = "검색에 실패했습니다",
        )
    }

    private fun episodes(series: SourceSeries) {
        val source = sourceRegistry.require(series.id.sourceId)
        update { it.copy(activeSeries = series) }
        launchContent(
            load = { source.episodes(series.id).items },
            success = { result: List<SourceEpisode> ->
                update { it.copy(content = LibraryContent.Episodes(series, result)) }
                result.firstOrNull()?.id?.let(::warm)
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
        if (state.value.settingsVisible) {
            update { it.copy(settingsVisible = false) }
            return
        }
        if (state.value.activeSeries != null) {
            cancelContent()
            cancelWarmup()
            val series = state.value.lastSeries
            update { it.copy(
                activeSeries = null,
                content = if (series.isEmpty()) LibraryContent.Empty else LibraryContent.Series(series),
            ) }
        }
    }

    private fun openSavedSeries(saved: SavedSeries) {
        val series = saved.asSourceSeries()
        update { it.copy(selectedSourceId = series.id.sourceId, lastSeries = listOf(series)) }
        episodes(series)
    }

    private fun openEpisode(episodeId: EpisodeId, series: SourceSeries) {
        if (warmingEpisodeId != episodeId) warm(episodeId)
        persist { userLibrary.recordOpened(series.id, series.title, series.thumbnailKey, episodeId) }
        effectChannel.trySend(LibraryEffect.OpenEpisode(episodeId))
    }

    private fun toggleFavorite(series: SourceSeries) {
        val favorite = state.value.saved.favorites.any { it.id == series.id }
        persist { userLibrary.setFavorite(series.id, series.title, series.thumbnailKey, !favorite) }
    }

    private fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) {
        persist { userLibrary.updateSettings(transform) }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence failure must not break navigation or touch handling.
            }
        }
    }

    private fun currentSeries(): SourceSeries =
        state.value.activeSeries
            ?: error("Episode selection requires an active series")

    private fun warm(episodeId: EpisodeId) {
        cancelWarmup()
        warmingEpisodeId = episodeId
        val source = sourceRegistry.require(episodeId.seriesId.sourceId)
        warmupJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    source.prepare(episodeId, PreparationIntent.INITIAL_VIEW)
                    source.manifest(episodeId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Viewer entry owns visible retry/error handling.
            }
        }
    }

    private fun cancelContent() {
        contentVersion += 1L
        contentJob?.cancel()
        contentJob = null
    }

    private fun cancelWarmup() {
        warmupJob?.cancel()
        warmupJob = null
        warmingEpisodeId = null
    }

    private fun update(transform: (LibraryState) -> LibraryState) {
        mutableState.value = transform(mutableState.value)
    }

    private fun initialState(): LibraryState {
        val options = sourceRegistry.options
        return LibraryState(query = "", sources = options, selectedSourceId = options.first().id)
    }
}

private fun SavedSeries.asSourceSeries() = SourceSeries(id, title, thumbnailKey = thumbnailKey)

internal class LibraryViewModelFactory(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(sourceRegistry, userLibrary, ioDispatcher) as T
    }
}
