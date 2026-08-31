package ml.melun.mangaview.data.cache

import java.io.File
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.source.OpenedPage

data class CachedPage(
    val pageId: PageId,
    val file: File,
    val byteCount: Long,
    val sha256: String,
    val mediaType: String,
    val dimensions: PageDimensions,
)

interface RawPageCache {
    suspend fun find(pageId: PageId): CachedPage?

    /** Consumes bytes without taking ownership; the repository always closes [openedPage]. */
    suspend fun write(pageId: PageId, openedPage: OpenedPage): CachedPage

    suspend fun remove(pageId: PageId)
}
