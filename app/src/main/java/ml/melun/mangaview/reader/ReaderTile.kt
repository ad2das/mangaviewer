package ml.melun.mangaview.reader

import android.graphics.Bitmap

data class ReaderTile(
    val sourceTop: Int,
    val sourceBottom: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bitmap: Bitmap
) {
    val sourceSpan: Int
        get() = sourceBottom - sourceTop

    /**
     * Exact original decodes may keep one immutable page Bitmap behind several 512-row draw
     * tiles. This avoids decoding the same JPEG once per tile while preserving the small GPU
     * upload units that keep host-GPU scrolling smooth.
     */
    fun hasExactSourcePixelStorage(): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceTop < 0 ||
            sourceBottom <= sourceTop || sourceBottom > sourceHeight || bitmap.isRecycled ||
            bitmap.width != sourceWidth
        ) return false
        return bitmap.height == sourceSpan || bitmap.height == sourceHeight
    }

    /** First bitmap row containing this tile's pixels. */
    fun bitmapSourceTop(): Int = if (bitmap.height == sourceHeight) sourceTop else 0
}

internal object ReaderExactDecodeStoragePolicy {
    private const val MAX_SHARED_FULL_PAGE_RGBA_BYTES = 24L * 1024L * 1024L

    fun useSharedFullPageBitmap(
        forceOriginal: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean = forceOriginal && sourceWidth > 0 && sourceHeight > 0 &&
        sourceWidth.toLong() * sourceHeight.toLong() * 4L <=
        MAX_SHARED_FULL_PAGE_RGBA_BYTES
}
