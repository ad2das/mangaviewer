package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.UpdatedList
import ml.melun.mangaview.mangaview.UpdatedManga
import ml.melun.mangaview.repository.MangaRepository

class UpdatesViewModel : BaseStateViewModel<List<UpdatedManga>>() {
    private var requestGroup: CustomHttpClient.RequestGroup? = null
    private var updated: UpdatedList? = null
    private var activeBaseMode: Int? = null

    fun reset(baseMode: Int) {
        requestGroup?.cancel()
        activeBaseMode = baseMode
        updated = UpdatedList(baseMode)
    }

    fun loadMore(baseMode: Int) {
        if (updated == null || activeBaseMode != baseMode) {
            reset(baseMode)
        }
        val target = updated ?: return
        if (target.isLast) {
            return
        }
        requestGroup?.cancel()
        requestGroup = CustomHttpClient.RequestGroup()
        load { MangaRepository.loadUpdates(target, requestGroup) }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
