package ml.melun.mangaview.state

sealed class UiState<out T> {
    data object Idle : UiState<Nothing>()
    data object Loading : UiState<Nothing>()
    data class Content<T>(val value: T) : UiState<T>()
    data object Empty : UiState<Nothing>()
    data class Error(val failure: MangaFailure) : UiState<Nothing>()
}
