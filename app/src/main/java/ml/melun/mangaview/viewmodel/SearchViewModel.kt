package ml.melun.mangaview.viewmodel

import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Search
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository

class SearchViewModel : BaseStateViewModel<List<Title>>() {
    private var requestGroup: CustomHttpClient.RequestGroup? = null

    fun search(query: String, page: Int, baseMode: Int) {
        requestGroup?.cancel()
        requestGroup = CustomHttpClient.RequestGroup()
        load {
            val search = Search(query, page, baseMode)
            MangaRepository.search(search, requestGroup)
            search.result
        }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
