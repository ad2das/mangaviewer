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
    private const val MIN_ROLLING_RESIDENCY_PAGE_COUNT = 160
    private const val MIN_ROLLING_RESIDENCY_RGBA_BYTES = 1_536L * 1024L * 1024L
    private const val MAX_SHORT_WEBTOON_ROLLING_PAGE_COUNT = 8
    private const val MIN_SHORT_WEBTOON_ROLLING_RGBA_BYTES = 1_024L * 1024L * 1024L

    fun useSharedFullPageBitmap(
        forceOriginal: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean = forceOriginal && sourceWidth > 0 && sourceHeight > 0 &&
        sourceWidth.toLong() * sourceHeight.toLong() * 4L <=
        MAX_SHARED_FULL_PAGE_RGBA_BYTES


    /**
     * Multi-GiB numeric volumes and a direct-Wi-Fi current short webtoon whose exact originals
     * exceed one GiB use bounded decoded-pixel residency. Every exact encoded body remains owned
     * by the source session and every page is still decoded/identity-checked, but an offscreen
     * winner may be recycled and decoded again from that resident body when it enters the forward
     * runway. Carrier/SNI and ordinary-size webtoons remain outside this policy.
     */
    fun useBoundedRollingResidency(
        episodePath: String,
        pageCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        directWifiCurrentWebtoon: Boolean = false,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || pageCount <= 0) return false
        val pageBytes = sourceWidth.toLong() * sourceHeight.toLong() * 4L
        val totalBytes = if (pageBytes <= 0L ||
            pageBytes > Long.MAX_VALUE / pageCount.toLong()
        ) {
            Long.MAX_VALUE
        } else {
            pageBytes * pageCount.toLong()
        }
        if (episodePath.startsWith("/webtoon/")) {
            return directWifiCurrentWebtoon &&
                pageCount <= MAX_SHORT_WEBTOON_ROLLING_PAGE_COUNT &&
                totalBytes >= MIN_SHORT_WEBTOON_ROLLING_RGBA_BYTES
        }
        return episodePath.startsWith("/manhwa/") &&
            pageCount >= MIN_ROLLING_RESIDENCY_PAGE_COUNT &&
            totalBytes >= MIN_ROLLING_RESIDENCY_RGBA_BYTES
    }
}
