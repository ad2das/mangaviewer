package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId

class PixelBudgetPlanner(
    private val memoryPolicy: PixelMemoryPolicy = PixelMemoryPolicy(),
    retainedScreenfulsBehind: Int = 2,
    retainedScreenfulsAhead: Int = 6,
    private val windowPolicy: PixelWindowPolicy = PixelWindowPolicy(
        screenfulsAhead = retainedScreenfulsAhead,
        screenfulsBehind = retainedScreenfulsBehind,
    ),
) {
    private data class ResidentTile(
        val pageId: PageId,
        val pixel: PixelRef,
        val tile: PixelTileRef,
        val topUnits: Long,
        val bottomUnits: Long,
        val bytes: Long,
    )

    fun trim(state: ViewerState): Reduction {
        if (state.residentPageIds.isEmpty()) return Reduction(state, emptyList())
        val window = windowPolicy.window(state)
        val tiles = residentTiles(state)
        var bytes = state.residentBytes
        val removed = linkedSetOf<ResidentTile>()
        tiles.filter { outside(it, window.retainedStartUnits, window.retainedEndUnits) }
            .forEach { bytes = remove(it, removed, bytes) }
        if (bytes > memoryPolicy.maximumResidentBytes) {
            bytes = trimOffscreen(tiles, removed, window, bytes)
        }
        if (removed.isEmpty()) return Reduction(state, emptyList())
        return removeTiles(state, removed)
    }

    private fun trimOffscreen(
        tiles: List<ResidentTile>,
        removed: MutableSet<ResidentTile>,
        window: PixelWindow,
        initialBytes: Long,
    ): Long {
        val before = tiles.filterNot(removed::contains).filter { it.bottomUnits <= window.visibleStartUnits }
        val after = tiles.filterNot(removed::contains).filter { it.topUnits >= window.visibleEndUnits }
        val center = midpoint(window.visibleStartUnits, window.visibleEndUnits)
        var bytes = initialBytes
        var beforeIndex = 0
        var afterIndex = after.lastIndex
        while (bytes > memoryPolicy.maximumResidentBytes &&
            (beforeIndex <= before.lastIndex || afterIndex >= 0)
        ) {
            val beforeTile = before.getOrNull(beforeIndex)
            val afterTile = after.getOrNull(afterIndex)
            val selected = farther(beforeTile, afterTile, center) ?: break
            if (selected === beforeTile) beforeIndex += 1 else afterIndex -= 1
            bytes = remove(selected, removed, bytes)
        }
        return bytes
    }

    private fun residentTiles(state: ViewerState): List<ResidentTile> = buildList {
        state.residentPageIds.forEach { pageId ->
            val pixel = state.pages.getValue(pageId).pixel ?: return@forEach
            val index = requireNotNull(state.layout.indexOf(pageId))
            val pageTop = state.layout.topAt(index).units
            val pageHeight = state.layout.entries[index].height.units
            pixel.tiles.forEach { tile ->
                add(ResidentTile(
                    pageId,
                    pixel,
                    tile,
                    Math.addExact(pageTop, scale(tile.sourceTopPx, pageHeight, pixel.dimensions.heightPx)),
                    Math.addExact(pageTop, scale(tile.sourceBottomPx, pageHeight, pixel.dimensions.heightPx)),
                    tileBytes(pixel, tile),
                ))
            }
        }
    }

    private fun removeTiles(state: ViewerState, removed: Set<ResidentTile>): Reduction {
        val commands = mutableListOf<ViewerCommand>()
        val replacements = removed.groupBy(ResidentTile::pageId).mapValues { (pageId, locations) ->
            val runtime = state.pages.getValue(pageId)
            val pixel = requireNotNull(runtime.pixel)
            val removedTiles = locations.map(ResidentTile::tile)
            val remainingTiles = pixel.tiles - removedTiles.toSet()
            val remaining = remainingTiles.takeIf { it.isNotEmpty() }?.let(pixel::subset)
            commands += ViewerCommand.ReleasePixel(pixel.subset(removedTiles))
            runtime.copy(
                residency = if (remaining == null) PageResidency.VERIFIED else PageResidency.RESIDENT,
                pixel = remaining,
                isPresented = remaining != null && runtime.isPresented,
            )
        }
        return Reduction(state.replacePages(replacements), commands)
    }

    private fun farther(left: ResidentTile?, right: ResidentTile?, center: Long): ResidentTile? = when {
        left == null -> right
        right == null -> left
        distanceFrom(center, left) >= distanceFrom(center, right) -> left
        else -> right
    }

    private fun remove(tile: ResidentTile, removed: MutableSet<ResidentTile>, bytes: Long): Long {
        if (!removed.add(tile)) return bytes
        return (bytes - tile.bytes).coerceAtLeast(0L)
    }

    private fun tileBytes(pixel: PixelRef, tile: PixelTileRef): Long {
        if (tile.allocationBytes > 0L) return tile.allocationBytes
        val tileHeight = (tile.sourceBottomPx - tile.sourceTopPx).toLong()
        val totalHeight = pixel.tiles.sumOf {
            (it.sourceBottomPx - it.sourceTopPx).toLong()
        }
        return multiplyDivideFloorExact(pixel.allocationBytes, tileHeight, totalHeight).coerceAtLeast(1L)
    }

    private fun scale(sourceY: Int, pageHeight: Long, sourceHeight: Int): Long =
        multiplyDivideFloorExact(pageHeight, sourceY, sourceHeight)

    private fun outside(tile: ResidentTile, start: Long, end: Long): Boolean =
        tile.bottomUnits <= start || tile.topUnits >= end

    private fun distanceFrom(center: Long, tile: ResidentTile): Long =
        kotlin.math.abs(midpoint(tile.topUnits, tile.bottomUnits) - center)
}
