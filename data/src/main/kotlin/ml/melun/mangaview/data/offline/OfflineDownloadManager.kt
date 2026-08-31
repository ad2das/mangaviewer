package ml.melun.mangaview.data.offline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

sealed interface EpisodeDownloadState {
    data object Queued : EpisodeDownloadState
    data class Running(val completedPages: Int, val totalPages: Int) : EpisodeDownloadState
    data object Complete : EpisodeDownloadState
    data class Failed(val message: String) : EpisodeDownloadState
}

class OfflineDownloadManager(
    private val scope: CoroutineScope,
    private val sourceFor: (ml.melun.mangaview.core.SourceId) -> ContentSource,
    private val repository: PageRepository,
    private val store: OfflineEpisodeStore,
) {
    private val active = ConcurrentHashMap.newKeySet<EpisodeId>()
    private val episodeSemaphore = Semaphore(MAX_PARALLEL_EPISODES)
    private val stateLock = Any()
    private val mutableStates = MutableStateFlow<Map<EpisodeId, EpisodeDownloadState>>(emptyMap())
    val states: StateFlow<Map<EpisodeId, EpisodeDownloadState>> = mutableStates.asStateFlow()

    fun download(series: SourceSeries, episode: SourceEpisode): Boolean {
        if (!active.add(episode.id)) return false
        update(episode.id, EpisodeDownloadState.Queued)
        scope.launch {
            try {
                episodeSemaphore.withPermit { runDownload(series, episode) }
            } finally {
                active.remove(episode.id)
            }
        }
        return true
    }

    fun remove(episodeId: EpisodeId) {
        scope.launch {
            store.remove(episodeId)
            update(episodeId, null)
        }
    }

    private suspend fun runDownload(series: SourceSeries, episode: SourceEpisode) {
        try {
            val manifest = sourceFor(episode.id.seriesId.sourceId).manifest(episode.id)
            update(episode.id, EpisodeDownloadState.Running(0, manifest.pages.size))
            val semaphore = Semaphore(MAX_PARALLEL_PAGES)
            val completed = AtomicInteger(0)
            val pages = kotlinx.coroutines.coroutineScope {
                manifest.pages.map { spec ->
                    async {
                        semaphore.withPermit { repository.get(spec.id) }.also {
                            val count = completed.incrementAndGet()
                            update(episode.id, EpisodeDownloadState.Running(count, manifest.pages.size))
                        }
                    }
                }.awaitAll()
            }
            store.save(series, episode, manifest, pages)
            update(episode.id, EpisodeDownloadState.Complete)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            update(episode.id, EpisodeDownloadState.Failed(failure.message ?: "다운로드에 실패했습니다"))
        }
    }

    private fun update(episodeId: EpisodeId, state: EpisodeDownloadState?) {
        synchronized(stateLock) {
            mutableStates.value = mutableStates.value.toMutableMap().apply {
                if (state == null) remove(episodeId) else put(episodeId, state)
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL_PAGES = 4
        const val MAX_PARALLEL_EPISODES = 2
    }
}
