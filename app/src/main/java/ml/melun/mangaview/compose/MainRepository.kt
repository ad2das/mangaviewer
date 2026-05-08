package ml.melun.mangaview.compose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.MainPageWebtoon
import ml.melun.mangaview.mangaview.Search
import ml.melun.mangaview.mangaview.Title

class MainRepository {
    suspend fun loadHome(baseMode: Int): Result<HomeContent> = withContext(Dispatchers.IO) {
        runCatching {
            val page = MainPageWebtoon(MainApplication.getHttpClient(), baseMode)
            HomeContent(
                sections = rankingsToSections(page.dataSet),
                recent = MainApplication.p.recent.orEmpty().filterValidTitles(),
                favorites = MainApplication.p.favorite.orEmpty().filterValidTitles(),
            )
        }
    }

    suspend fun search(query: String, baseMode: Int): Result<List<Title>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@runCatching emptyList()
            val search = Search(trimmed, 0, baseMode)
            val status = search.fetch(MainApplication.getHttpClient())
            if (status != 0 && search.result.isNullOrEmpty()) {
                throw IllegalStateException("검색 결과를 불러오지 못했습니다")
            }
            search.result.orEmpty()
        }
    }

    suspend fun loadLibrary(): Result<Pair<List<MTitle>, List<MTitle>>> = withContext(Dispatchers.IO) {
        runCatching {
            MainApplication.p.recent.orEmpty().filterValidTitles() to
                    MainApplication.p.favorite.orEmpty().filterValidTitles()
        }
    }
}

private fun List<MTitle>.filterValidTitles(): List<MTitle> =
    filter { it.id > 0 && !it.name.isNullOrBlank() }
