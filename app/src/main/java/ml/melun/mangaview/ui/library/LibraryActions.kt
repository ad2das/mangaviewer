package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.offline.DownloadedEpisode
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal class LibraryActions(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val sources: SourceRegistry,
    private val library: UserLibraryRepository,
    private val downloads: OfflineDownloadManager,
) {
    fun toggleFavorite(series: SourceSeries, favorite: Boolean) = persist {
        library.setFavorite(series.id, series.title, series.thumbnailKey, !favorite)
    }

    fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) = persist {
        library.updateSettings(transform)
    }

    fun recordOpened(series: SourceSeries, episode: SourceEpisode) = persist {
        library.recordOpened(series.id, series.title, series.thumbnailKey, episode.id)
    }

    fun download(
        series: SourceSeries,
        episodes: List<SourceEpisode>,
        saved: List<DownloadedEpisode>,
    ): String {
        val savedIds = saved.mapTo(hashSetOf()) { it.episode.id }
        val pending = episodes.distinctBy(SourceEpisode::id).filterNot { it.id in savedIds }
        pending.forEach { downloads.download(series, it) }
        return when {
            episodes.isEmpty() -> "다운로드할 회차를 선택해 주세요"
            pending.isEmpty() -> "이미 오프라인으로 저장된 회차입니다"
            else -> "${pending.size}개 회차 다운로드를 시작했습니다"
        }
    }

    suspend fun seriesUrl(series: SourceSeries): String? = withContext(ioDispatcher) {
        sources.require(series.id.sourceId).seriesUrl(series.id)
    }

    private fun persist(block: suspend () -> Unit) {
        scope.launch(ioDispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistent state must not interrupt navigation or touch handling.
            }
        }
    }
}
