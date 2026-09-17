package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal class LibraryUiActions(
    private val scope: CoroutineScope,
    private val actions: LibraryActions,
    private val downloads: OfflineDownloadManager,
    private val current: () -> LibraryState,
    private val update: (((LibraryState) -> LibraryState) -> Unit),
    private val emit: (LibraryEffect) -> Unit,
) {
    fun persistSettings(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.StartTabChanged -> actions.updateSettings { it.copy(startTab = intent.value) }
            is LibraryIntent.DarkThemeChanged -> actions.updateSettings { it.copy(darkTheme = intent.enabled) }
            LibraryIntent.ClearSearchHistory -> actions.updateSettings { it.copy(recentQueries = emptyList()) }
            is LibraryIntent.RemoveSearchHistory -> actions.updateSettings { settings ->
                settings.copy(recentQueries = settings.recentQueries.filterNot { it == intent.value })
            }
            else -> error("Not a setting intent: $intent")
        }
    }

    fun selectSaved(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.SavedQueryChanged -> update { it.copy(savedQuery = intent.value) }
            is LibraryIntent.SavedTabSelected -> update { it.copy(libraryTab = intent.value, savedSelection = emptySet()) }
            is LibraryIntent.SavedSelectionToggled -> update {
                it.copy(savedSelection = if (intent.key in it.savedSelection) it.savedSelection - intent.key
                    else it.savedSelection + intent.key)
            }
            is LibraryIntent.SavedSelectionReplaced -> update { it.copy(savedSelection = intent.keys.toSet()) }
            LibraryIntent.SavedSelectionCleared -> update { it.copy(savedSelection = emptySet()) }
            else -> error("Not a saved selection intent: $intent")
        }
    }

    fun openSeriesLink(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.OpenSeriesInBrowser -> resolveSeriesUrl(intent.series, LibraryEffect::OpenUri)
            is LibraryIntent.ShareSeries -> resolveSeriesUrl(intent.series) { url ->
                LibraryEffect.ShareText(intent.series.title, "${intent.series.title}\n$url")
            }
            else -> error("Not a series link intent: $intent")
        }
    }

    fun toggleOverlay(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.ToggleSettings -> update { it.copy(settingsVisible = !it.settingsVisible) }
            LibraryIntent.TogglePreferences -> update {
                it.copy(preferencesVisible = !it.preferencesVisible, settingsVisible = false)
            }
            LibraryIntent.ToggleSourcePicker -> update {
                it.copy(
                    sourcePickerVisible = !it.sourcePickerVisible,
                    settingsVisible = false,
                    preferencesVisible = false,
                )
            }
            else -> error("Not an overlay intent: $intent")
        }
    }

    fun dismissOverlay(): Boolean {
        if (current().pendingOfflineRemoval != null) {
            update { it.copy(pendingOfflineRemoval = null) }
            return true
        }
        if (current().downloadSelectionVisible) {
            update { it.copy(downloadSelectionVisible = false) }
            return true
        }
        if (current().sourcePickerVisible) {
            update { it.copy(sourcePickerVisible = false) }
            return true
        }
        if (current().preferencesVisible) {
            update { it.copy(preferencesVisible = false, settingsVisible = true) }
            return true
        }
        if (current().settingsVisible) {
            update { it.copy(settingsVisible = false) }
            return true
        }
        if (current().seriesMenuVisible) {
            update { it.copy(seriesMenuVisible = false) }
            return true
        }
        return false
    }

    fun removeSaved(item: SavedItemRemoval) {
        scope.launch {
            try { actions.removeSaved(item); showMessage("${item.series.title} 삭제 완료") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage("삭제하지 못했습니다. 다시 시도해 주세요") }
        }
    }

    fun removeSelected(intent: LibraryIntent.RemoveSelected) {
        scope.launch {
            try {
                intent.items.forEach { actions.removeSaved(it) }
                intent.bookmarks.forEach { actions.removeBookmark(it) }
                update { it.copy(savedSelection = emptySet()) }
                showMessage("${intent.items.size + intent.bookmarks.size}개 삭제 완료")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage("삭제하지 못했습니다. 다시 시도해 주세요") }
        }
    }

    fun toggleDownloadSelection() {
        val snapshot = current()
        when {
            snapshot.downloadSelectionVisible -> update { it.copy(downloadSelectionVisible = false) }
            snapshot.content is LibraryContent.Episodes && snapshot.content.complete -> update {
                it.copy(downloadSelectionVisible = true, seriesMenuVisible = false)
            }
            else -> showMessage("회차 목록을 모두 불러온 뒤 다시 시도해 주세요")
        }
    }

    fun download(series: SourceSeries, episodes: List<SourceEpisode>) {
        val message = actions.download(series, episodes, current().offlineEpisodes)
        update { it.copy(downloadSelectionVisible = false, seriesMenuVisible = false) }
        showMessage(message)
    }

    fun removeOffline(episodeId: EpisodeId) {
        downloads.remove(episodeId)
        showMessage("오프라인 저장을 삭제했습니다")
    }

    fun resolveSeriesUrl(series: SourceSeries, effect: (String) -> LibraryEffect) {
        update { it.copy(seriesMenuVisible = false) }
        scope.launch {
            val url = runCatching { actions.seriesUrl(series) }.getOrNull()
            emit(url?.takeIf(String::isNotBlank)?.let(effect) ?: LibraryEffect.ShowMessage(
                "열 수 있는 작품 주소가 없습니다",
            ))
        }
    }

    fun openProjectPage(value: String) {
        update { it.copy(settingsVisible = false, preferencesVisible = false) }
        emit(LibraryEffect.OpenUri(value))
    }

    fun showMessage(value: String) {
        emit(LibraryEffect.ShowMessage(value))
    }
}
