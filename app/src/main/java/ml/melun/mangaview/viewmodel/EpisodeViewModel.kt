package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.model.EpisodeLoadResult
import ml.melun.mangaview.repository.MangaRepository

class EpisodeViewModel : BaseStateViewModel<EpisodeLoadResult>() {
    private var cancellation: MangaRepository.Cancellation? = null

    fun loadEpisodes(title: Title) {
        loadEpisodes(title, true)
    }

    fun loadEpisodes(title: Title, allowSlowFallback: Boolean) {
        cancellation?.cancel()
        val activeCancellation = MangaRepository.cancellation()
        cancellation = activeCancellation
        load(onCancel = {
            activeCancellation.cancel()
            if(cancellation == activeCancellation)
                cancellation = null
        }) {
            val code = if (allowSlowFallback) {
                MangaRepository.fetchEpisodesForeground(title, activeCancellation)
            } else {
                MangaRepository.fetchEpisodes(title, activeCancellation)
            }
            EpisodeLoadResult(code, title.eps ?: emptyList<Manga>())
        }
    }

    override fun onCleared() {
        cancellation?.cancel()
        super.onCleared()
    }
}
