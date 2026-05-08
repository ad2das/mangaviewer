package ml.melun.mangaview.state

sealed class MangaFailure(open val cause: Throwable? = null) {
    data class NetworkError(override val cause: Throwable? = null) : MangaFailure(cause)
    data class CaptchaRequired(override val cause: Throwable? = null) : MangaFailure(cause)
    data class ParseError(override val cause: Throwable? = null) : MangaFailure(cause)
    data class StorageError(override val cause: Throwable? = null) : MangaFailure(cause)
    data class Cancelled(override val cause: Throwable? = null) : MangaFailure(cause)
    data class Unknown(override val cause: Throwable? = null) : MangaFailure(cause)
}
