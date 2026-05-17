package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.MainPage
import ml.melun.mangaview.repository.MangaRepository

class HomeViewModel : BaseStateViewModel<MainPage>() {
    private var requestGroup: CustomHttpClient.RequestGroup? = null

    fun loadHome() {
        val group = CustomHttpClient.RequestGroup()
        requestGroup = group
        load(onCancel = {
            group.cancel()
            if(requestGroup == group)
                requestGroup = null
        }) {
            MangaRepository.loadComicHome(group)
        }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
