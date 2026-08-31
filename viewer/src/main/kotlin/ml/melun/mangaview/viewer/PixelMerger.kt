package ml.melun.mangaview.viewer

internal data class PixelMerge(
    val pixel: PixelRef,
    val replaced: PixelRef?,
)

internal fun mergePixels(current: PixelRef?, incoming: PixelRef): PixelMerge {
    if (current == null) return PixelMerge(incoming, null)
    require(current.dimensions == incoming.dimensions) { "Pixel dimensions changed" }
    val replacedTiles = current.tiles.filter { existing ->
        incoming.tiles.any { added ->
            existing.sourceTopPx < added.sourceBottomPx &&
                added.sourceTopPx < existing.sourceBottomPx
        }
    }
    val retainedTiles = current.tiles - replacedTiles.toSet()
    val mergedTiles = (retainedTiles + incoming.tiles).sortedBy(PixelTileRef::sourceTopPx)
    val retained = retainedTiles.takeIf(List<PixelTileRef>::isNotEmpty)?.let(current::subset)
    val allocationBytes = (retained?.allocationBytes ?: 0L) + incoming.allocationBytes
    val pixel = PixelRef(mergedTiles.first().handle, incoming.dimensions, allocationBytes, mergedTiles)
    val replaced = replacedTiles.takeIf(List<PixelTileRef>::isNotEmpty)?.let(current::subset)
    return PixelMerge(pixel, replaced)
}
