package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.data.library.SavedBookmark
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

    fun removeBookmark(bookmark: SavedBookmark) = persist {
        library.removeBookmark(bookmark)
    }

    fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) = persist {
        library.updateSettings(transform)
    }

    suspend fun removeSaved(item: SavedItemRemoval) = withContext(ioDispatcher) {
        if (item.tab == SavedTab.ALL || item.tab == SavedTab.OFFLINE) downloads.removeSeries(item.series.id)
        when (item.tab) {
            SavedTab.ALL -> library.removeHistory(item.series.id, removeFavorite = true)
            SavedTab.RECENT -> library.removeHistory(item.series.id)
            SavedTab.FAVORITES -> library.setFavorite(item.series.id, item.series.title, item.series.thumbnailKey, false)
            // The row stands for every mark in the series, so one deletion removes them together.
            SavedTab.BOOKMARKS -> library.snapshot.first().bookmarks
                .filter { it.pageId.episodeId.seriesId == item.series.id }
                .forEach { library.removeBookmark(it) }
            SavedTab.OFFLINE -> Unit
        }
    }

    /**
     * What a removal is about to delete, captured so an undo can put it back: the favorite flag,
     * the resume position and the series' bookmarks. Downloads are not restorable and are left out.
     */
    suspend fun captureRestore(item: SavedItemRemoval): (suspend () -> Unit)? = withContext(ioDispatcher) {
        if (item.tab == SavedTab.OFFLINE) return@withContext null
        val snapshot = library.snapshot.first()
        val id = item.series.id
        val favorite = snapshot.favorites.firstOrNull { it.id == id }?.takeIf { it.favorite }
        val recent = snapshot.recent.firstOrNull { it.series.id == id }
        val marks = snapshot.bookmarks.filter { it.pageId.episodeId.seriesId == id }
        val restoresHistory = item.tab == SavedTab.ALL || item.tab == SavedTab.RECENT
        val restoresFavorite = item.tab == SavedTab.ALL || item.tab == SavedTab.FAVORITES
        val restorable = (restoresHistory && recent != null) || (restoresFavorite && favorite != null) ||
            (item.tab == SavedTab.BOOKMARKS && marks.isNotEmpty())
        if (!restorable) return@withContext null
        return@withContext {
            withContext(ioDispatcher) {
                if (restoresHistory && recent != null) {
                    library.recordOpened(id, recent.series.title, recent.series.thumbnailKey, recent.episodeId)
                    library.saveProgress(recent.pageId, recent.offsetInPageUnits)
                }
                if (restoresFavorite && favorite != null) library.setFavorite(id, favorite.title, favorite.thumbnailKey, true)
                if (item.tab == SavedTab.BOOKMARKS) marks.forEach { library.addBookmark(it.pageId, it.offsetInPageUnits) }
            }
        }
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
