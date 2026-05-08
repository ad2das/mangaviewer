package ml.melun.mangaview.compose

import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.model.PageItem

data class ViewerUiState(
    val title: String = "",
    val pages: List<PageItem> = emptyList(),
    val currentManga: Manga? = null,
    val loading: Boolean = false,
    val error: ViewerError? = null,
)

sealed interface ViewerError {
    data object Cancelled : ViewerError
    data object EmptyEpisode : ViewerError
    data class Network(val message: String?) : ViewerError
    data class Parse(val message: String?) : ViewerError
    data class Unknown(val message: String?) : ViewerError
}
