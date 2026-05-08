package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.UpdatedList
import ml.melun.mangaview.mangaview.UpdatedManga
import ml.melun.mangaview.repository.MangaRepository

class UpdatesViewModel : BaseStateViewModel<List<UpdatedManga>>() {
    private var requestGroup: CustomHttpClient.RequestGroup? = null

    fun loadUpdates(baseMode: Int) {
        requestGroup?.cancel()
        requestGroup = CustomHttpClient.RequestGroup()
        load { MangaRepository.loadUpdates(UpdatedList(baseMode), requestGroup) }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
