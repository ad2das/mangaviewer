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

/** A stable-on-callback prefix of a page that is still being received and is not cache-valid. */
data class PageTransferPreview(
    val pageId: PageId,
    val file: File,
    val byteCount: Long,
    val header: ImageHeader,
)

interface RawPageCache {
    suspend fun find(pageId: PageId): CachedPage?

    /** Consumes bytes without taking ownership; the repository always closes [openedPage]. */
    suspend fun write(
        pageId: PageId,
        openedPage: OpenedPage,
        onPreview: ((PageTransferPreview) -> Unit)? = null,
    ): CachedPage

    suspend fun remove(pageId: PageId)
}
