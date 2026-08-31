package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineScope
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
    fun toggleDownloadSelection() {
        val snapshot = current()
        when {
            snapshot.downloadSelectionVisible -> update { it.copy(downloadSelectionVisible = false) }
            snapshot.content is LibraryContent.Episodes -> update {
                it.copy(downloadSelectionVisible = true, seriesMenuVisible = false)
            }
            else -> showMessage("회차 목록을 불러온 뒤 다시 시도해 주세요")
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
