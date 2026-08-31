package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.SeriesKind

internal class LibraryStateObservers(
    private val sources: SourceRegistry,
    private val library: UserLibraryRepository,
    private val offline: OfflineEpisodeStore,
    private val downloads: OfflineDownloadManager,
) {
    private var restoredDestination = false

    fun start(
        scope: CoroutineScope,
        update: ((LibraryState) -> LibraryState) -> Unit,
        reloadHome: () -> Unit,
        libraryChanged: () -> Unit,
    ) {
        observeLibrary(scope, update, reloadHome, libraryChanged)
        scope.launch { offline.episodes.collectLatest { value -> update { it.copy(offlineEpisodes = value) } } }
        scope.launch { downloads.states.collectLatest { value -> update { it.copy(downloadStates = value) } } }
    }

    private fun observeLibrary(
        scope: CoroutineScope,
        update: ((LibraryState) -> LibraryState) -> Unit,
        reloadHome: () -> Unit,
        libraryChanged: () -> Unit,
    ) {
        scope.launch {
            library.snapshot.collectLatest { snapshot ->
                var reload = false
                update { state ->
                    if (restoredDestination) return@update state.copy(saved = snapshot)
                    restoredDestination = true
                    val sourceId = state.sources.firstOrNull {
                        it.id.value == snapshot.settings.sourceKey
                    }?.id ?: state.selectedSourceId
                    val kind = SeriesKind.entries.getOrElse(snapshot.settings.seriesKind) { SeriesKind.WEBTOON }
                    reload = sourceId != state.selectedSourceId || kind != state.homeKind
                    state.copy(
                        saved = snapshot,
                        destination = MainDestination.fromStored(snapshot.settings.startTab),
                        selectedSourceId = sourceId,
                        homeKind = kind,
                    )
                }
                libraryChanged()
                if (reload) reloadHome()
            }
        }
    }
}
