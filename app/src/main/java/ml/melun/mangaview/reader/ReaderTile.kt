package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.os.Build

internal fun Bitmap.hasImmutableExactPixelConfig(): Boolean =
    HostExactHardwareTilePool.isActiveToken(this) ||
        (!isRecycled && !isMutable &&
            (config == Bitmap.Config.ARGB_8888 ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    config == Bitmap.Config.HARDWARE)))

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
            sourceBottom <= sourceTop || sourceBottom > sourceHeight ||
            !bitmap.hasImmutableExactPixelConfig()
        ) return false
        if (HostExactHardwareTilePool.hasExactStorage(bitmap, sourceWidth, sourceSpan)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            // The host-emulator pool rounds width and keeps one fixed-height reusable allocation.
            // Only [sourceWidth] x [sourceSpan] contains this immutable decode; padding is never
            // part of the logical source rectangle.
            return bitmap.width >= sourceWidth && bitmap.height >= sourceSpan
        }
        if (bitmap.width != sourceWidth) return false
        return bitmap.height == sourceSpan || bitmap.height == sourceHeight
    }

    /** First bitmap row containing this tile's pixels. */
    fun bitmapSourceTop(): Int =
        if (!HostExactHardwareTilePool.isActiveToken(bitmap) &&
            bitmap.width == sourceWidth && bitmap.height == sourceHeight
        ) sourceTop else 0
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

/**
 * Keeps the immutable launch episode resident until an exact successor is physically presented,
 * while bounding decoded pixels from continuously appended episodes. Exact encoded bodies and
 * page structure are retained separately, so this budget controls only replaceable
 * Bitmap/ReaderTile storage.
 */
internal object ReaderStrictBitmapResidencyPolicy {
    private const val MIN_ADJACENT_CACHE_BYTES = 32L * 1024L * 1024L
    private const val MIN_TOTAL_BITMAP_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BITMAP_BYTES = 64L * 1024L * 1024L
    private const val MIN_NON_BITMAP_HEAP_RESERVE_BYTES = 160L * 1024L * 1024L

    fun totalBitmapBudgetBytes(requiredLaunchBytes: Long, maxHeapBytes: Long): Long {
        val heap = maxHeapBytes.coerceAtLeast(0L)
        val adjacentCache = maxOf(MIN_ADJACENT_CACHE_BYTES, heap / 16L)
            .coerceAtMost(MIN_TOTAL_BITMAP_BYTES)
        // Exact bodies are disk-backed and decoded pixels are native allocations accounted to
        // the runtime heap. Keep only the visible/near-forward working set. A 96 MiB pixel target
        // plus the decoder, renderer and 48 MiB launch scene still crossed ART's NativeAlloc line
        // while each successor page arrived, so collection overlapped physical input. Sixty-four
        // MiB holds sixteen common 800x~1200 pages (well beyond the physical viewport plus four-
        // page runway) and retires the now-rehydratable launch scene before that feedback loop.
        // Completion is monotonic and exact bodies remain resident, so pixels behind the protected
        // viewport/runway can be rehydrated losslessly.
        // Historical pixels rehydrate losslessly from the exact resident body.
        val heapAfterReserve = (heap - MIN_NON_BITMAP_HEAP_RESERVE_BYTES).coerceAtLeast(0L)
        val totalCeiling = minOf(
            MAX_TOTAL_BITMAP_BYTES,
            maxOf(MIN_TOTAL_BITMAP_BYTES, heapAfterReserve),
        )
        val launch = requiredLaunchBytes.coerceAtLeast(0L)
        val twoLaunches = if (launch > Long.MAX_VALUE / 2L) Long.MAX_VALUE else launch * 2L
        val requested = if (twoLaunches > Long.MAX_VALUE - adjacentCache) {
            Long.MAX_VALUE
        } else {
            twoLaunches + adjacentCache
        }
        return minOf(totalCeiling, requested.coerceAtLeast(MIN_TOTAL_BITMAP_BYTES))
    }

    fun protectsLaunchPixel(
        strictColdSession: Boolean,
        rollingPixelResidency: Boolean,
        belongsToLaunchEpisode: Boolean,
        successorPhysicallyPresented: Boolean,
    ): Boolean =
        strictColdSession &&
            !rollingPixelResidency &&
            belongsToLaunchEpisode &&
            !successorPhysicallyPresented

    /**
     * Normal strict-cold chapters keep traversed adjacent pixels as a budgeted LRU. Their exact
     * encoded bodies remain independently recoverable, but deleting every drawable as soon as it
     * leaves the directional window makes a forward/reverse fling decode the same chapter forever.
     * Explicit oversized/short rolling profiles still require the hard physical-window bound.
     */
    fun shouldHardEvictOutsideRetainedWindow(
        strictColdSession: Boolean,
        rollingPixelResidency: Boolean,
        shortWebtoonRolling: Boolean,
    ): Boolean =
        !strictColdSession || rollingPixelResidency || shortWebtoonRolling

    fun shouldTrimRetainedUnderBudgetPressure(
        shortWebtoonRolling: Boolean,
        immediateGeneratedUx: Boolean,
        strictColdSession: Boolean,
        rollingPixelResidency: Boolean,
        protectsImmediateSurface: Boolean,
        viewportBusy: Boolean,
        initialSettleActive: Boolean,
    ): Boolean {
        if (shortWebtoonRolling) return true
        if (!immediateGeneratedUx) return false
        // A normal strict-cold session can keep a broad numerical retained range while traversing
        // multiple chapters. That range is an admission/reverse-recovery contract, not decoded
        // pixel ownership: exact bodies remain resident. The caller independently excludes the
        // current protected pixel window and a larger moving runway, so it is both safe and
        // necessary to retire far-behind LRU pixels during input. Waiting for a global idle point
        // lets a complete chapter accumulate above the byte budget and makes NativeAlloc GC stop
        // the very gesture that would eventually reach that idle point.
        if (strictColdSession && !rollingPixelResidency) {
            // Strict launch protection and the active pixel window are evaluated per index by the
            // caller. The broad immediate-surface flag predates that exact identity protection;
            // applying it here pins every traversed chapter and defeats the byte budget.
            return !initialSettleActive
        }
        if (protectsImmediateSurface) return false
        return viewportBusy || initialSettleActive
    }
}
