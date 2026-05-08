package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository

class EpisodeViewModel : BaseStateViewModel<List<Manga>>() {
    fun loadEpisodes(title: Title) {
        load {
            MangaRepository.fetchEpisodes(title)
            title.eps
        }
    }
}
