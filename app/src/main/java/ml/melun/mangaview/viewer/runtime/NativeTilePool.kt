package ml.melun.mangaview.viewer.runtime

import java.io.Closeable
import java.io.File
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.viewer.PixelBand
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.PixelTileRef

internal class NativeTilePool(
    private val bindings: NativePixelBindings,
    private val maximumBytes: Long = 128L * 1_024L * 1_024L,
    private val settledBytes: Long = 64L * 1_024L * 1_024L,
) : Closeable {
    init {
        require(maximumBytes > 0L)
        require(settledBytes >= 0L)
    }

    private data class Slot(
        val handle: Long,
        val width: Int,
        val height: Int,
        val bytes: Long,
        var contentVersion: Long = 0L,
        var inUse: Boolean = false,
    )

    private val lock = Any()
    private val slots = mutableListOf<Slot>()
    private var allocatedBytes = 0L
    private var nextContentVersion = 1L
    private var closed = false

    fun decodeBand(file: File, dimensions: PageDimensions, band: PixelBand): PixelRef {
        require(file.isFile && file.length() > 0L) { "Encoded page file is unavailable" }
        require(band.sourceBottomPx <= dimensions.heightPx) { "Band exceeds source image" }
        val displayWidth = band.displayWidthPx
        require(displayWidth <= dimensions.widthPx) { "Band display width exceeds source width" }
        val displayHeight = projectedBandHeight(dimensions, band)
        val slot = acquire(displayWidth, displayHeight)
        var success = false
        try {
            check(bindings.decodeBand(
                encodedPath = file.absolutePath,
                handle = slot.handle,
                contentVersion = slot.contentVersion,
                sourceWidth = dimensions.widthPx,
                sourceHeight = dimensions.heightPx,
                sourceTop = band.sourceTopPx,
                sourceBottom = band.sourceBottomPx,
                displayWidth = displayWidth,
            )) { "Native page band decode failed" }
            check(bindings.publish(slot.handle, slot.contentVersion)) {
                "Native page band publication failed"
            }
            val tile = PixelTileRef(
                handle = slot.handle,
                sourceTopPx = band.sourceTopPx,
                sourceBottomPx = band.sourceBottomPx,
                displayHeightPx = displayHeight,
                contentVersion = slot.contentVersion,
                allocationBytes = slot.bytes,
                displayWidthPx = displayWidth,
            )
            success = true
            return PixelRef(slot.handle, dimensions, slot.bytes, listOf(tile))
        } finally {
            if (!success) releaseReserved(slot)
        }
    }

    /** Moves gralloc allocation off the first decoded frame; no pixels are fabricated or shown. */
    fun preallocate(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val slot = acquire(width, height)
        releaseReserved(slot)
    }

    fun recycle(pixel: PixelRef) {
        synchronized(lock) {
            pixel.tiles.forEach { tile ->
                val slot = slots.firstOrNull { it.handle == tile.handle } ?: return@forEach
                if (slot.inUse && slot.contentVersion == tile.contentVersion) slot.inUse = false
            }
        }
    }

    fun compact() {
        val victims = synchronized(lock) {
            if (allocatedBytes <= settledBytes) return
            selectIdleVictims(allocatedBytes - settledBytes)
        }
        victims.forEach(bindings::release)
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            slots.map(Slot::handle).also {
                slots.clear()
                allocatedBytes = 0L
            }
        }
        owned.forEach(bindings::release)
    }

    private fun acquire(width: Int, height: Int): Slot = synchronized(lock) {
        check(!closed) { "Native tile pool is closed" }
        val reusable = slots.asSequence()
            .filter { !it.inUse && it.width >= width && it.height >= height }
            .minByOrNull(Slot::bytes)
        val slot = reusable ?: allocateSlot(width, height)
        slot.inUse = true
        check(nextContentVersion > 0L) { "Native content version space is exhausted" }
        slot.contentVersion = nextContentVersion++
        slot
    }

    private fun allocateSlot(width: Int, height: Int): Slot {
        val minimumBytes = minimumAllocationBytes(width, height)
        val required = bytesToFreeFor(minimumBytes)
        val victims = selectIdleVictims(required)
        victims.forEach(bindings::release)
        check(allocatedBytes + minimumBytes <= maximumBytes) { "Native pixel budget is exhausted" }
        val handle = bindings.allocate(width, height)
        check(handle != 0L) { "Native pixel allocation failed" }
        val bytes = bindings.allocationBytes(handle)
        if (bytes <= 0L) {
            bindings.release(handle)
            error("Native pixel allocation size is unavailable")
        }
        val strideVictims = selectIdleVictims(
            bytesToFreeFor(bytes),
        )
        strideVictims.forEach(bindings::release)
        if (bytes > maximumBytes - allocatedBytes) {
            bindings.release(handle)
            error("Native pixel budget is exhausted")
        }
        return Slot(handle, width, height, bytes).also {
            slots += it
            allocatedBytes += bytes
        }
    }

    private fun selectIdleVictims(bytesToFree: Long): List<Long> {
        if (bytesToFree <= 0L) return emptyList()
        var remaining = bytesToFree
        val selected = mutableListOf<Long>()
        slots.filterNot(Slot::inUse).sortedByDescending(Slot::bytes).forEach { slot ->
            if (remaining <= 0L) return@forEach
            slots.remove(slot)
            allocatedBytes -= slot.bytes
            remaining -= slot.bytes
            selected += slot.handle
        }
        return selected
    }

    private fun releaseReserved(slot: Slot) {
        synchronized(lock) { slot.inUse = false }
    }

    private fun projectedBandHeight(dimensions: PageDimensions, band: PixelBand): Int {
        val scaledPageHeight = (
            dimensions.heightPx.toLong() * band.displayWidthPx + dimensions.widthPx - 1L
            ) / dimensions.widthPx
        val projectedTop = band.sourceTopPx.toLong() * scaledPageHeight / dimensions.heightPx
        val projectedBottom = band.sourceBottomPx.toLong() * scaledPageHeight / dimensions.heightPx
        return Math.toIntExact(projectedBottom - projectedTop).also {
            require(it > 0) { "Pixel band disappears at the requested display width" }
        }
    }

    private fun minimumAllocationBytes(width: Int, height: Int): Long =
        Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), BYTES_PER_PIXEL)

    private fun bytesToFreeFor(bytes: Long): Long {
        val available = (maximumBytes - allocatedBytes).coerceAtLeast(0L)
        return (bytes - available).coerceAtLeast(0L)
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}
