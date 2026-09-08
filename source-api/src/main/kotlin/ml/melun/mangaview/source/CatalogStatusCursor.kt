package ml.melun.mangaview.source

/** Opaque continuation through ongoing pages followed by the provider's completed catalog. */
data class CatalogStatusCursor(val page: Int, val completed: Boolean = false) {
    init { require(page > 0) }

    fun next(nextPage: String?, includeCompleted: Boolean): String? = when {
        nextPage != null -> if (completed) "end:$nextPage" else nextPage
        includeCompleted && !completed -> "end:1"
        else -> null
    }

    companion object {
        fun parse(value: String?, includeCompleted: Boolean): CatalogStatusCursor {
            if (value == null) return CatalogStatusCursor(1)
            val completed = value.startsWith("end:")
            require(!completed || includeCompleted) { "Completed catalog is unavailable for this query" }
            val number = if (completed) value.removePrefix("end:") else value
            val page = number.toIntOrNull()
            require(page != null && page > 0 && page.toString() == number) { "Invalid catalog cursor" }
            return CatalogStatusCursor(page, completed)
        }
    }
}
