package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.model.EpisodeLoadResult
import ml.melun.mangaview.repository.MangaRepository

class EpisodeViewModel : BaseStateViewModel<EpisodeLoadResult>() {
    fun loadEpisodes(title: Title) {
        loadEpisodes(title, true)
    }

    fun loadEpisodes(title: Title, allowSlowFallback: Boolean) {
        load {
            val code = if (allowSlowFallback) {
                MangaRepository.fetchEpisodesForeground(title)
            } else {
                MangaRepository.fetchEpisodes(title)
            }
            EpisodeLoadResult(code, title.eps ?: emptyList<Manga>())
        }
    }
}
