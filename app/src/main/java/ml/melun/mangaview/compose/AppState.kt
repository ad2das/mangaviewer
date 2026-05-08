package ml.melun.mangaview.compose

import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Ranking
import ml.melun.mangaview.mangaview.Title

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Content<T>(val value: T) : LoadState<T>
    data class Empty(val message: String) : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
}

data class HomeContent(
    val sections: List<HomeSection> = emptyList(),
    val recent: List<MTitle> = emptyList(),
    val favorites: List<MTitle> = emptyList(),
)

data class HomeSection(
    val title: String,
    val items: List<MTitle>,
)

data class SearchContent(
    val query: String = "",
    val results: List<Title> = emptyList(),
    val searching: Boolean = false,
    val message: String = "",
)

fun rankingsToSections(rankings: List<Ranking<*>>?): List<HomeSection> {
    if (rankings.isNullOrEmpty()) return emptyList()
    return rankings.mapNotNull { ranking ->
        val items = ranking.filterIsInstance<MTitle>()
        if (items.isEmpty()) null else HomeSection(ranking.name ?: "목록", items)
    }
}
