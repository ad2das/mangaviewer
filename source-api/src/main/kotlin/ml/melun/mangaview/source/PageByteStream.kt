package ml.melun.mangaview.source

import java.io.Closeable

interface PageByteStream : Closeable {
    /** Suspends before borrowing a transfer buffer when this stream is deliberately parked. */
    suspend fun awaitReadable() = Unit

    suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int

    /** Raises an already-open body's urgency without changing its bytes or ownership. */
    fun promote(priority: PageFetchPriority) = Unit
}

data class OpenedPage(
    val stream: PageByteStream,
    val contentLength: Long?,
    val contentType: String?,
    val entityTag: String?,
    val lastModified: String?,
) : Closeable {
    init {
        require(contentLength == null || contentLength >= 0L) {
            "Content length must not be negative"
        }
    }

    override fun close() = stream.close()
}

data class PageValidation(
    val entityTag: String? = null,
    val lastModified: String? = null,
)
