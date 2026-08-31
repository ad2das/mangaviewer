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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal class LibraryViewModel(
    private val sourceRegistry: SourceRegistry,
    private val userLibrary: UserLibraryRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState())
    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)
    private var requestJob: Job? = null
    private var warmupJob: Job? = null
    private var warmingEpisodeId: EpisodeId? = null
    private var requestVersion = 0L
    private var restoredStartTab = false

    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            userLibrary.snapshot.collectLatest { snapshot ->
                update { state ->
                    val tab = if (restoredStartTab) state.selectedTab else {
                        restoredStartTab = true
                        LibraryTab.fromStored(snapshot.settings.startTab)
                    }
                    state.copy(saved = snapshot, selectedTab = tab)
                }
            }
        }
    }

    fun accept(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged -> update { it.copy(query = intent.value) }
            is LibraryIntent.TabSelected -> selectTab(intent.tab)
            is LibraryIntent.SourceSelected -> selectSource(intent)
            LibraryIntent.Search -> search()
            is LibraryIntent.SeriesSelected -> episodes(intent.series)
            is LibraryIntent.EpisodeSelected -> openEpisode(intent.episodeId, currentSeries())
            is LibraryIntent.SavedSeriesSelected -> openSavedSeries(intent.series)
            is LibraryIntent.SavedEpisodeSelected -> effectChannel.trySend(
                LibraryEffect.OpenEpisode(intent.position.pageId.episodeId, intent.position),
            )
            is LibraryIntent.FavoriteToggled -> toggleFavorite(intent.series)
            is LibraryIntent.DarkThemeChanged -> updateSettings { it.copy(darkTheme = intent.enabled) }
            LibraryIntent.Back -> back()
        }
    }

    private fun selectTab(tab: LibraryTab) {
        update { it.copy(selectedTab = tab) }
        updateSettings { it.copy(startTab = tab.ordinal) }
    }

    private fun openSavedSeries(saved: SavedSeries) {
        val series = saved.asSourceSeries()
        update {
            it.copy(
                selectedTab = LibraryTab.SEARCH,
                selectedSourceId = series.id.sourceId,
                lastSeries = listOf(series),
            )
        }
        updateSettings { it.copy(startTab = LibraryTab.SEARCH.ordinal) }
        episodes(series)
    }

    private fun selectSource(intent: LibraryIntent.SourceSelected) {
        sourceRegistry.require(intent.sourceId)
        cancelRequest()
        cancelWarmup()
        update {
            it.copy(
                selectedSourceId = intent.sourceId,
                content = LibraryContent.Empty,
                lastSeries = emptyList(),
            )
        }
    }

    private fun search() {
        val snapshot = state.value
        val query = snapshot.query.trim()
        if (query.isEmpty()) return
        val source = sourceRegistry.require(snapshot.selectedSourceId)
        cancelWarmup()
        launchRequest(
            load = { source.search(query).items },
            success = { result: List<SourceSeries> ->
                update { it.copy(content = LibraryContent.Series(result), lastSeries = result) }
            },
            failureMessage = "검색에 실패했습니다",
        )
    }

    private fun episodes(series: SourceSeries) {
        val source = sourceRegistry.require(series.id.sourceId)
        launchRequest(
            load = { source.episodes(series.id).items },
            success = { result: List<SourceEpisode> ->
                update { it.copy(content = LibraryContent.Episodes(series, result)) }
                result.firstOrNull()?.id?.let(::warm)
            },
            failureMessage = "회차를 불러오지 못했습니다",
        )
    }

    private fun <T> launchRequest(
        load: suspend () -> T,
        success: (T) -> Unit,
        failureMessage: String,
    ) {
        cancelRequest()
        val version = ++requestVersion
        update { it.copy(content = LibraryContent.Loading) }
        requestJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { load() }
                if (version == requestVersion) success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (version == requestVersion) {
                    val message = failure.message?.takeIf(String::isNotBlank) ?: failureMessage
                    update { it.copy(content = LibraryContent.Failure(message)) }
                }
            }
        }
    }

    private fun back() {
        cancelWarmup()
        val series = state.value.lastSeries
        update {
            it.copy(content = if (series.isEmpty()) LibraryContent.Empty else LibraryContent.Series(series))
        }
    }

    private fun cancelRequest() {
        requestVersion += 1L
        requestJob?.cancel()
        requestJob = null
    }

    private fun openEpisode(episodeId: EpisodeId, series: SourceSeries) {
        if (warmingEpisodeId != episodeId) warm(episodeId)
        persist {
            userLibrary.recordOpened(series.id, series.title, series.thumbnailKey, episodeId)
        }
        effectChannel.trySend(LibraryEffect.OpenEpisode(episodeId))
    }

    private fun toggleFavorite(series: SourceSeries) {
        val favorite = state.value.saved.favorites.any { it.id == series.id }
        persist {
            userLibrary.setFavorite(series.id, series.title, series.thumbnailKey, !favorite)
        }
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
                // A failed preference/history write must not terminate navigation or input.
            }
        }
    }

    private fun currentSeries(): SourceSeries =
        (state.value.content as? LibraryContent.Episodes)?.series
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
                // Viewer entry owns user-visible error reporting and retries.
            }
        }
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
        return LibraryState(
            query = "",
            sources = options,
            selectedSourceId = options.first().id,
            content = LibraryContent.Empty,
        )
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
