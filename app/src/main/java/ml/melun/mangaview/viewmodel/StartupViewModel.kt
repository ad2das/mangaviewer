package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.model.UrlUpdateResult
import ml.melun.mangaview.repository.MangaRepository

class StartupViewModel : BaseStateViewModel<UrlUpdateResult>() {
    fun updateUrl(fetchUrl: String) {
        load { MangaRepository.updateUrl(fetchUrl) }
    }
}
