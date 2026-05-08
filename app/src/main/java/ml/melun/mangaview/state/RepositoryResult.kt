package ml.melun.mangaview.state

sealed class RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>()
    data object Empty : RepositoryResult<Nothing>()
    data class Failure(val failure: MangaFailure) : RepositoryResult<Nothing>()
}
