package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageSpec

enum class PageMilestone {
    ABSENT,
    METADATA,
    FETCHING,
    VERIFIED,
    DECODING,
    RESIDENT,
    PRESENTED,
}

enum class PageResidency {
    NONE,
    VERIFIED,
    RESIDENT,
}

data class VerifiedPageRef(
    val cacheKey: String,
    val byteCount: Long,
    val sha256: String,
    val dimensions: PageDimensions? = null,
) {
    init {
        require(cacheKey.isNotBlank()) { "Cache key must not be blank" }
        require(byteCount >= 0L) { "Byte count must not be negative" }
        require(sha256.isNotBlank()) { "Digest must not be blank" }
    }
}

data class PixelRef(
    val handle: Long,
    val dimensions: PageDimensions,
    val allocationBytes: Long,
    val tiles: List<PixelTileRef> = listOf(
        PixelTileRef(
            handle,
            0,
            dimensions.heightPx,
            dimensions.heightPx,
            1L,
            displayWidthPx = dimensions.widthPx,
        ),
    ),
) {
    init {
        require(handle != 0L) { "Pixel handle must not be zero" }
        require(allocationBytes > 0L) { "Pixel allocation must be positive" }
        require(tiles.isNotEmpty() && tiles.first().handle == handle) { "Pixel tiles are invalid" }
        require(tiles.zipWithNext().all { (left, right) ->
            left.sourceTopPx < right.sourceTopPx && left.sourceBottomPx <= right.sourceTopPx
        }) {
            "Pixel tiles must be ordered and non-overlapping"
        }
        require(tiles.all { it.sourceBottomPx <= dimensions.heightPx }) {
            "Pixel tiles must stay inside the source image"
        }
    }

    fun covers(band: PixelBand): Boolean = tiles.any { tile ->
        tile.sourceTopPx <= band.sourceTopPx && tile.sourceBottomPx >= band.sourceBottomPx &&
            tile.displayWidthPx >= band.displayWidthPx
    }

    fun subset(selected: List<PixelTileRef>): PixelRef {
        require(selected.isNotEmpty() && selected.all(tiles::contains))
        val knownBytes = selected.fold(0L) { total, tile ->
            saturatingAdd(total, tile.allocationBytes)
        }
        val bytes = if (knownBytes > 0L) {
            knownBytes
        } else {
            val selectedHeight = selected.sumOf {
                (it.sourceBottomPx - it.sourceTopPx).toLong()
            }
            val totalHeight = tiles.sumOf {
                (it.sourceBottomPx - it.sourceTopPx).toLong()
            }
            multiplyDivideFloorExact(allocationBytes, selectedHeight, totalHeight).coerceAtLeast(1L)
        }
        return PixelRef(selected.first().handle, dimensions, bytes, selected)
    }
}

data class PixelTileRef(
    val handle: Long,
    val sourceTopPx: Int,
    val sourceBottomPx: Int,
    val displayHeightPx: Int,
    val contentVersion: Long = 1L,
    val allocationBytes: Long = 0L,
    val displayWidthPx: Int = 0,
) {
    init {
        require(handle != 0L) { "Tile handle must not be zero" }
        require(sourceTopPx >= 0 && sourceBottomPx > sourceTopPx) { "Tile source range is invalid" }
        require(displayHeightPx > 0) { "Tile display height must be positive" }
        require(contentVersion > 0L) { "Tile content version must be positive" }
        require(allocationBytes >= 0L) { "Tile allocation must not be negative" }
        require(displayWidthPx >= 0) { "Tile display width must not be negative" }
    }
}

data class RetryState(
    val failures: Int,
    val eligibleAtNanos: Long,
    val reason: String,
) {
    init {
        require(failures > 0) { "Failure count must be positive" }
        require(eligibleAtNanos >= 0L) { "Retry time must not be negative" }
        require(reason.isNotBlank()) { "Retry reason must not be blank" }
    }
}

data class PageRuntime(
    val spec: PageSpec,
    val milestone: PageMilestone = PageMilestone.METADATA,
    val residency: PageResidency = PageResidency.NONE,
    val encoded: VerifiedPageRef? = null,
    val pixel: PixelRef? = null,
    val fetchRetry: RetryState? = null,
    val decodeRetry: RetryState? = null,
    val isPresented: Boolean = false,
) {
    fun advance(target: PageMilestone): PageRuntime {
        require(target.ordinal >= milestone.ordinal) { "Page milestone must not move backward" }
        return copy(milestone = target)
    }
}
