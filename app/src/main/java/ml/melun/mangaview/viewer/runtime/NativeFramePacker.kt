package ml.melun.mangaview.viewer.runtime

import kotlin.math.ceil
import kotlin.math.floor
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.FixedPx

internal data class PackedNativeFrame(
    val count: Int,
    val width: Int,
    val height: Int,
    val bandHeight: Int,
    val viewportTop: Int,
    val coordinateOrigin: FixedPx,
    val tileData: IntArray,
    val geometryData: IntArray,
    val handles: LongArray,
    val localTileRanges: IntArray,
    val localVisualRanges: IntArray,
    val sceneSignature: Long,
) {
    fun hasVisibleContent(viewportTop: Int = this.viewportTop): Boolean =
        localTileRanges.hasIntersection(viewportTop, height)

    fun hasReadableVisibleContent(viewportTop: Int = this.viewportTop): Boolean =
        localTileRanges.containsPoint(viewportTop.toLong() + height.toLong() / 2L)

    fun hasCompleteVisibleContent(viewportTop: Int = this.viewportTop): Boolean =
        localTileRanges.covers(viewportTop, height)

    fun hasCompleteVisualCoverage(viewportTop: Int = this.viewportTop): Boolean =
        localVisualRanges.covers(viewportTop, height)

    /** Detaches a pending scene description from the packer's reusable primitive buffers. */
    fun frozenForRender(): PackedNativeFrame = copy(
        tileData = tileData.copyOf(count * TILE_INTEGER_STRIDE),
        geometryData = geometryData.copyOf(count * 2),
        handles = handles.copyOf(),
        localTileRanges = localTileRanges.copyOf(),
        localVisualRanges = localVisualRanges.copyOf(),
    )

    private companion object {
        const val TILE_INTEGER_STRIDE = 12
    }
}

internal fun IntArray.hasIntersection(viewportTop: Int, viewportHeight: Int): Boolean {
    require(size % 2 == 0) { "Tile ranges must contain top/bottom pairs" }
    require(viewportTop >= 0 && viewportHeight > 0) { "Viewport must be positive" }
    val viewportBottom = viewportTop.toLong() + viewportHeight.toLong()
    var index = 0
    while (index < size) {
        if (this[index].toLong() < viewportBottom &&
            this[index + 1].toLong() > viewportTop.toLong()
        ) return true
        index += 2
    }
    return false
}

internal fun IntArray.containsPoint(point: Long): Boolean {
    require(size % 2 == 0) { "Content ranges must contain start/end pairs" }
    require(point >= 0L) { "Content probe point must not be negative" }
    var index = 0
    while (index < size) {
        if (this[index].toLong() <= point && this[index + 1].toLong() > point) return true
        index += 2
    }
    return false
}

internal fun IntArray.covers(viewportTop: Int, viewportHeight: Int): Boolean {
    require(size % 2 == 0) { "Tile ranges must contain top/bottom pairs" }
    require(viewportTop >= 0 && viewportHeight > 0) { "Viewport must be positive" }
    val viewportBottom = viewportTop.toLong() + viewportHeight.toLong()
    var coveredThrough = viewportTop.toLong()
    var index = 0
    while (index < size) {
        val top = this[index].toLong()
        val bottom = this[index + 1].toLong()
        if (bottom > coveredThrough) {
            if (top > coveredThrough) return false
            coveredThrough = bottom
            if (coveredThrough >= viewportBottom) return true
        }
        index += 2
    }
    return false
}

internal class NativeFramePacker(
    private val maximumRenderWidth: Int = 1_440,
    private val overscanScreenfuls: Int = 1,
) {
    init {
        require(maximumRenderWidth > 0)
        require(overscanScreenfuls >= 0)
    }

    private var tileData = IntArray(INITIAL_TILE_CAPACITY * TILE_INTEGER_STRIDE)
    private var geometryData = IntArray(INITIAL_TILE_CAPACITY * 2)
    private var handles = LongArray(INITIAL_TILE_CAPACITY)
    private var contentTileRanges = IntArray(INITIAL_TILE_CAPACITY * 2)
    private var visualTileRanges = IntArray(INITIAL_TILE_CAPACITY * 2)

    fun pack(plan: FramePlan): PackedNativeFrame? {
        val geometry = geometry(plan) ?: return null
        return pack(plan, geometry)
    }

    fun packInstalled(plan: FramePlan, contract: NativeFullFrameContract): PackedNativeFrame? {
        if (plan.generation != contract.plan.generation || plan.viewport != contract.plan.viewport) return null
        val geometry = installedGeometry(plan, contract) ?: return null
        return pack(plan, geometry)
    }

    private fun pack(plan: FramePlan, geometry: NativeGeometry): PackedNativeFrame? {
        val maximumCount = plan.pages.sumOf { placement -> placement.pixel?.tiles?.size ?: 0 } +
            plan.loading.size
        ensureCapacity(maximumCount)
        val counts = packTiles(plan, geometry) ?: return null
        return PackedNativeFrame(
            count = counts.total,
            width = geometry.width,
            height = geometry.height,
            bandHeight = geometry.bandHeight,
            viewportTop = geometry.viewportTop,
            coordinateOrigin = geometry.coordinateOrigin,
            tileData = tileData,
            geometryData = geometryData,
            handles = handles.copyOf(counts.content),
            localTileRanges = contentTileRanges.copyOf(counts.content * 2),
            localVisualRanges = visualTileRanges.copyOf(counts.total * 2),
            sceneSignature = sceneSignature(counts.total, geometry),
        )
    }

    private fun installedGeometry(
        plan: FramePlan,
        contract: NativeFullFrameContract,
    ): NativeGeometry? {
        val viewportWidth = plan.viewport.width.toPixels()
        val viewportHeight = plan.viewport.height.toPixels()
        if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return null
        val scale = contract.renderWidth / viewportWidth
        val expectedHeight = ceil(viewportHeight * scale).toInt().coerceAtLeast(1)
        if (expectedHeight != contract.renderHeight) return null
        val maximumOffset = (plan.contentHeight.units - plan.viewport.height.units).coerceAtLeast(0L)
        if (plan.scrollOffset.units !in 0L..maximumOffset) return null
        val localUnits = plan.scrollOffset.units - contract.coordinateOrigin.units
        if (localUnits < 0L) return null
        val viewportTop = (localUnits.toDouble() / FixedPx.UNITS_PER_PIXEL * scale).toInt()
        if (viewportTop !in 0..(contract.bandHeight - contract.renderHeight)) return null
        return NativeGeometry(
            contract.renderWidth,
            contract.renderHeight,
            contract.bandHeight,
            viewportTop,
            scale,
            contract.coordinateOrigin,
        )
    }

    private fun geometry(plan: FramePlan): NativeGeometry? {
        val viewportWidth = plan.viewport.width.toPixels()
        val viewportHeight = plan.viewport.height.toPixels()
        if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return null
        val width = minOf(maximumRenderWidth, ceil(viewportWidth).toInt())
        val scale = width / viewportWidth
        val height = ceil(viewportHeight * scale).toInt().coerceAtLeast(1)
        val maximumOffset = FixedPx(
            (plan.contentHeight.units - plan.viewport.height.units).coerceAtLeast(0L),
        )
        if (plan.scrollOffset < FixedPx.ZERO || plan.scrollOffset > maximumOffset) return null
        val overscanUnits = multiplyBounded(plan.viewport.height.units, overscanScreenfuls)
        val originUnits = (plan.scrollOffset.units - overscanUnits).coerceAtLeast(0L)
        val viewportEndUnits = addBounded(plan.scrollOffset.units, plan.viewport.height.units)
        val desiredBandEndUnits = addBounded(viewportEndUnits, overscanUnits)
        val bandEndUnits = minOf(plan.contentHeight.units, desiredBandEndUnits)
            .coerceAtLeast(viewportEndUnits)
        val scaledBandHeight = ceil((bandEndUnits - originUnits).toDouble() / FixedPx.UNITS_PER_PIXEL * scale)
        if (!scaledBandHeight.isFinite() || scaledBandHeight > Int.MAX_VALUE) return null
        val bandHeight = scaledBandHeight.toInt().coerceAtLeast(height)
        val coordinateOrigin = FixedPx(originUnits)
        val scaledViewportTop = (plan.scrollOffset.units - originUnits).toDouble() /
            FixedPx.UNITS_PER_PIXEL * scale
        if (!scaledViewportTop.isFinite()) return null
        val viewportTop = scaledViewportTop.toInt()
        if (viewportTop !in 0..(bandHeight - height)) return null
        return NativeGeometry(width, height, bandHeight, viewportTop, scale, coordinateOrigin)
    }

    private fun packTiles(plan: FramePlan, geometry: NativeGeometry): PackedCounts? {
        // The installed native band is viewport-local and includes symmetric scroll headroom.
        // Keep every resident tile intersecting that band; scalar-only scroll is accepted only
        // while the viewport remains inside the same immutable band.
        val cursor = PackingCursor()
        for (placement in plan.pages) {
            if (!packPage(plan, placement, geometry, cursor)) return null
        }
        while (cursor.loadingIndex < plan.loading.size) {
            val loading = plan.loading[cursor.loadingIndex++]
            if (!packLoadingRange(loading, geometry, cursor)) return null
        }
        return PackedCounts(total = cursor.ordinal, content = cursor.contentOrdinal)
    }

    private fun packPage(
        plan: FramePlan,
        placement: ml.melun.mangaview.viewer.PagePlacement,
        geometry: NativeGeometry,
        cursor: PackingCursor,
    ): Boolean {
        val pixel = placement.pixel
        val tiles = pixel?.tiles.orEmpty()
        var tileIndex = 0
        while (tileIndex < tiles.size || nextLoading(plan, placement, cursor) != null) {
            val loading = nextLoading(plan, placement, cursor)
            val tile = tiles.getOrNull(tileIndex)
            if (loading != null && (tile == null || loading.sourceTopPx <= tile.sourceTopPx)) {
                cursor.loadingIndex += 1
                if (!packLoadingRange(loading, geometry, cursor)) return false
            } else {
                tileIndex += 1
                if (!packContent(placement, requireNotNull(pixel), requireNotNull(tile), geometry, cursor)) {
                    return false
                }
            }
        }
        return true
    }

    private fun nextLoading(
        plan: FramePlan,
        placement: ml.melun.mangaview.viewer.PagePlacement,
        cursor: PackingCursor,
    ) = plan.loading.getOrNull(cursor.loadingIndex)?.takeIf { it.ordinal == placement.ordinal }

    private fun packLoadingRange(
        loading: ml.melun.mangaview.viewer.LoadingPlacement,
        geometry: NativeGeometry,
        cursor: PackingCursor,
    ): Boolean {
        val range = loadingRange(loading, geometry) ?: return false
        if (range.bottom <= 0 || range.top >= geometry.bandHeight) return true
        if (range.bottom <= range.top) return false
        packLoading(cursor.ordinal, loading, range)
        recordVisualRange(cursor.ordinal, range)
        cursor.previous = PackedTileBoundary(
            loading.ordinal,
            loading.sourceBottomPx,
            loading.sourceHeightPx,
            range.bottom,
        )
        cursor.ordinal += 1
        return true
    }

    private fun packContent(
        placement: ml.melun.mangaview.viewer.PagePlacement,
        pixel: ml.melun.mangaview.viewer.PixelRef,
        tile: ml.melun.mangaview.viewer.PixelTileRef,
        geometry: NativeGeometry,
        cursor: PackingCursor,
    ): Boolean {
        val range = tileRange(placement, placement.ordinal, pixel, tile, geometry, cursor.previous)
            ?: return false
        if (range.bottom <= 0 || range.top >= geometry.bandHeight) return true
        if (range.bottom <= range.top) return false
        packTile(
            cursor.ordinal,
            placement.ordinal,
            tile.sourceTopPx,
            cursor.contentOrdinal,
            pixel,
            tile,
            range,
        )
        val rangeOffset = cursor.contentOrdinal * 2
        contentTileRanges[rangeOffset] = range.top
        contentTileRanges[rangeOffset + 1] = range.bottom
        recordVisualRange(cursor.ordinal, range)
        cursor.previous = PackedTileBoundary(
            placement.ordinal,
            tile.sourceBottomPx,
            pixel.dimensions.heightPx,
            range.bottom,
        )
        cursor.ordinal += 1
        cursor.contentOrdinal += 1
        return true
    }

    private fun loadingRange(
        placement: ml.melun.mangaview.viewer.LoadingPlacement,
        geometry: NativeGeometry,
    ): PackedTileRange? {
        val top = localLoadingBoundary(placement.top, geometry) ?: return null
        val bottom = localLoadingBoundary(placement.top + placement.height, geometry) ?: return null
        return PackedTileRange(top, bottom)
    }

    private fun localLoadingBoundary(value: FixedPx, geometry: NativeGeometry): Int? {
        val localUnits = value.units - geometry.coordinateOrigin.units
        val scaled = floor(localUnits.toDouble() / FixedPx.UNITS_PER_PIXEL * geometry.scale + 0.5)
        if (!scaled.isFinite()) return null
        return scaled.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun tileRange(
        placement: ml.melun.mangaview.viewer.PagePlacement,
        pageOrdinal: Int,
        pixel: ml.melun.mangaview.viewer.PixelRef,
        tile: ml.melun.mangaview.viewer.PixelTileRef,
        geometry: NativeGeometry,
        previous: PackedTileBoundary,
    ): PackedTileRange? {
        val sourceHeight = pixel.dimensions.heightPx
        var top = localBoundary(placement, tile.sourceTopPx, sourceHeight, geometry) ?: return null
        val bottom = localBoundary(placement, tile.sourceBottomPx, sourceHeight, geometry) ?: return null
        val continuesPage = previous.pageOrdinal == pageOrdinal &&
            previous.sourceBottom == tile.sourceTopPx
        val continuesNextPage = previous.pageOrdinal + 1 == pageOrdinal &&
            previous.sourceBottom == previous.sourceHeight && tile.sourceTopPx == 0 &&
            kotlin.math.abs(top.toLong() - previous.localBottom.toLong()) <= 1L
        if (continuesPage || continuesNextPage) top = previous.localBottom
        return PackedTileRange(top, bottom)
    }

    private fun localBoundary(
        placement: ml.melun.mangaview.viewer.PagePlacement,
        sourceY: Int,
        sourceHeight: Int,
        geometry: NativeGeometry,
    ): Int? {
        val pageOffsetUnits = scaleUnits(placement.height.units, sourceY, sourceHeight)
        val localUnits = placement.top.units - geometry.coordinateOrigin.units + pageOffsetUnits
        val value = floor(localUnits.toDouble() / FixedPx.UNITS_PER_PIXEL * geometry.scale + 0.5)
        if (!value.isFinite()) return null
        return value.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun packTile(
        ordinal: Int,
        pageOrdinal: Int,
        slotOrdinal: Int,
        contentOrdinal: Int,
        pixel: ml.melun.mangaview.viewer.PixelRef,
        tile: ml.melun.mangaview.viewer.PixelTileRef,
        range: PackedTileRange,
    ) {
        val offset = ordinal * TILE_INTEGER_STRIDE
        tileData[offset] = pageOrdinal
        tileData[offset + 1] = slotOrdinal
        tileData[offset + 2] = tile.sourceTopPx
        tileData[offset + 3] = tile.sourceBottomPx
        tileData[offset + 4] = pixel.dimensions.widthPx
        tileData[offset + 5] = pixel.dimensions.heightPx
        tileData[offset + 6] = tile.displayWidthPx
        tileData[offset + 7] = CPU_TILE_RESOURCE_KIND
        tileData[offset + 8] = tile.handle.toInt()
        tileData[offset + 9] = (tile.handle ushr 32).toInt()
        tileData[offset + 10] = tile.contentVersion.toInt()
        tileData[offset + 11] = (tile.contentVersion ushr 32).toInt()
        val geometryOffset = ordinal * 2
        geometryData[geometryOffset] = range.top
        geometryData[geometryOffset + 1] = range.bottom
        handles[contentOrdinal] = tile.handle
    }

    private fun packLoading(
        ordinal: Int,
        placement: ml.melun.mangaview.viewer.LoadingPlacement,
        range: PackedTileRange,
    ) {
        val offset = ordinal * TILE_INTEGER_STRIDE
        tileData[offset] = placement.ordinal
        tileData[offset + 1] = 0
        tileData[offset + 2] = placement.sourceTopPx
        tileData[offset + 3] = placement.sourceBottomPx
        tileData[offset + 4] = 1
        tileData[offset + 5] = placement.sourceHeightPx
        tileData[offset + 6] = 1
        tileData[offset + 7] = LOADING_TILE_RESOURCE_KIND
        tileData[offset + 8] = 0
        tileData[offset + 9] = 0
        tileData[offset + 10] = LOADING_CONTENT_VERSION
        tileData[offset + 11] = 0
        val geometryOffset = ordinal * 2
        geometryData[geometryOffset] = range.top
        geometryData[geometryOffset + 1] = range.bottom
    }

    private fun ensureCapacity(count: Int) {
        if (handles.size >= count) return
        val capacity = Integer.highestOneBit(count - 1).coerceAtLeast(1) shl 1
        tileData = IntArray(capacity * TILE_INTEGER_STRIDE)
        geometryData = IntArray(capacity * 2)
        handles = LongArray(capacity)
        contentTileRanges = IntArray(capacity * 2)
        visualTileRanges = IntArray(capacity * 2)
    }

    private fun recordVisualRange(ordinal: Int, range: PackedTileRange) {
        val offset = ordinal * 2
        visualTileRanges[offset] = range.top
        visualTileRanges[offset + 1] = range.bottom
    }

    private fun sceneSignature(count: Int, geometry: NativeGeometry): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, count)
        hash = mix(hash, geometry.width)
        hash = mix(hash, geometry.height)
        hash = mix(hash, geometry.bandHeight)
        hash = mix(hash, geometry.coordinateOrigin.units)
        repeat(count * TILE_INTEGER_STRIDE) { index -> hash = mix(hash, tileData[index]) }
        repeat(count * 2) { index -> hash = mix(hash, geometryData[index]) }
        return hash
    }

    private fun mix(hash: Long, value: Int): Long = mix(hash, value.toLong())

    private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

    private data class NativeGeometry(
        val width: Int,
        val height: Int,
        val bandHeight: Int,
        val viewportTop: Int,
        val scale: Double,
        val coordinateOrigin: FixedPx,
    )

    private data class PackedTileRange(val top: Int, val bottom: Int)

    private data class PackedCounts(val total: Int, val content: Int)

    private data class PackingCursor(
        var ordinal: Int = 0,
        var contentOrdinal: Int = 0,
        var loadingIndex: Int = 0,
        var previous: PackedTileBoundary = PackedTileBoundary.NONE,
    )

    private data class PackedTileBoundary(
        val pageOrdinal: Int,
        val sourceBottom: Int,
        val sourceHeight: Int,
        val localBottom: Int,
    ) {
        companion object {
            val NONE = PackedTileBoundary(-2, -1, -1, -1)
        }
    }

    private companion object {
        const val TILE_INTEGER_STRIDE = 12
        const val CPU_TILE_RESOURCE_KIND = 2
        const val LOADING_TILE_RESOURCE_KIND = 3
        const val LOADING_CONTENT_VERSION = 1
        const val INITIAL_TILE_CAPACITY = 16
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }

    private fun scaleUnits(value: Long, multiplier: Int, divisor: Int): Long {
        require(value >= 0L && divisor > 0 && multiplier >= 0 && multiplier <= divisor)
        val quotient = value / divisor
        val remainder = value % divisor
        return quotient * multiplier + remainder * multiplier.toLong() / divisor
    }

    private fun multiplyBounded(value: Long, multiplier: Int): Long {
        if (value == 0L || multiplier == 0) return 0L
        return if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier
    }

    private fun addBounded(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
