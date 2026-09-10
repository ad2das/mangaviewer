package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId

/** Immutable early facts. These never grant access to a body or mark a result complete. */
sealed interface WorkMetadata {
    data class PageGeometry(val pageId: PageId, val contentRevision: String, val dimensions: PageDimensions) : WorkMetadata {
        init { require(contentRevision.isNotBlank()) }
    }
}
