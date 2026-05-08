package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.MainPage
import ml.melun.mangaview.repository.MangaRepository

class HomeViewModel : BaseStateViewModel<MainPage>() {
    private var requestGroup: CustomHttpClient.RequestGroup? = null

    fun loadHome() {
        requestGroup = CustomHttpClient.RequestGroup()
        load { MangaRepository.loadComicHome(requestGroup) }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
