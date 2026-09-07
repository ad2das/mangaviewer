package ml.melun.mangaview.viewer

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec

data class PageLayout(
    val spec: PageSpec,
    val height: FixedPx,
    val resolvedDimensions: PageDimensions?,
)

class LayoutLedger private constructor(
    val viewportWidth: FixedPx,
    val entries: PersistentList<PageLayout>,
    private val indexById: Map<PageId, Int>,
    private val heights: ImmutableLongSumTree,
) {
    val totalHeight: FixedPx = FixedPx(heights.sum)

    fun contains(pageId: PageId): Boolean = indexById.containsKey(pageId)

    fun indexOf(pageId: PageId): Int? = indexById[pageId]

    fun topOf(pageId: PageId): FixedPx? = indexById[pageId]?.let(::topAt)

    fun topAt(index: Int): FixedPx {
        require(index in entries.indices) { "Page index is outside the ledger" }
        return FixedPx(heights.prefixSum(index))
    }

    fun heightOf(pageId: PageId): FixedPx? = indexById[pageId]?.let { entries[it].height }

    /** A saved pixel can be deeper than an unknown page's estimate; metadata alone resolves it. */
    fun reserveUnknownHeight(pageId: PageId, minimum: FixedPx): LayoutLedger {
        val index = indexById[pageId] ?: return this
        val current = entries[index]
        if (current.resolvedDimensions != null || current.height >= minimum) return this
        val replacement = current.copy(height = minimum)
        return LayoutLedger(viewportWidth, entries.set(index, replacement), indexById,
            heights.update(index, minimum.units))
    }

    fun pageAt(contentOffset: FixedPx): PageId? {
        if (entries.isEmpty()) return null
        val target = contentOffset.units.coerceIn(0L, (totalHeight.units - 1L).coerceAtLeast(0L))
        return entries[heights.indexAtOffset(target)].spec.id
    }

    fun indicesIntersecting(start: FixedPx, endExclusive: FixedPx): IntRange {
        if (entries.isEmpty() || endExclusive <= start || endExclusive <= FixedPx.ZERO || start >= totalHeight) {
            return IntRange.EMPTY
        }
        val firstId = pageAt(FixedPx(start.units.coerceAtLeast(0L))) ?: return IntRange.EMPTY
        val lastPoint = (endExclusive.units - 1L).coerceAtLeast(start.units).coerceAtMost(totalHeight.units - 1L)
        val lastId = pageAt(FixedPx(lastPoint)) ?: return IntRange.EMPTY
        return indexById.getValue(firstId)..indexById.getValue(lastId)
    }

    fun resolve(pageId: PageId, dimensions: PageDimensions): LayoutLedger {
        val index = indexById[pageId] ?: return this
        val current = entries[index]
        if (current.resolvedDimensions == dimensions) return this
        val replacement = current.copy(
            height = scaledHeight(viewportWidth, dimensions),
            resolvedDimensions = dimensions,
        )
        return LayoutLedger(
            viewportWidth,
            entries.set(index, replacement),
            indexById,
            heights.update(index, replacement.height.units),
        )
    }

    fun append(pages: List<PageSpec>): LayoutLedger {
        require(pages.none { contains(it.id) }) { "Appended pages must be new" }
        if (pages.isEmpty()) return this
        val additions = pages.map { page -> page.toLayout(viewportWidth) }
        return rebuild(entries.addAll(additions), viewportWidth)
    }

    fun replaceLast(expectedId: PageId, page: PageSpec): LayoutLedger {
        require(entries.lastOrNull()?.spec?.id == expectedId) { "Boundary page is not last" }
        require(!contains(page.id) || page.id == expectedId) { "Replacement page id already exists" }
        return rebuild(entries.set(entries.lastIndex, page.toLayout(viewportWidth)), viewportWidth)
    }

    fun reflow(width: FixedPx): LayoutLedger {
        if (width == viewportWidth) return this
        val resized = entries.map { entry ->
            val dimensions = entry.resolvedDimensions ?: entry.spec.dimensions
            entry.copy(height = dimensions?.let { scaledHeight(width, it) } ?: FixedPx(
                multiplyDivideFloorExact(entry.height.units, width.units, viewportWidth.units),
            ))
        }
        return rebuild(resized, width)
    }

    companion object {
        fun create(pages: List<PageSpec>, viewportWidth: FixedPx): LayoutLedger {
            require(pages.isNotEmpty()) { "A ledger needs at least one page" }
            require(pages.map(PageSpec::id).toSet().size == pages.size) { "Page ids must be unique" }
            return rebuild(pages.map { it.toLayout(viewportWidth) }, viewportWidth)
        }

        private fun PageSpec.toLayout(width: FixedPx): PageLayout {
            val knownDimensions = dimensions
            val height = knownDimensions?.let { scaledHeight(width, it) } ?: fallbackHeight(width)
            return PageLayout(this, height, knownDimensions)
        }

        private fun scaledHeight(width: FixedPx, dimensions: PageDimensions): FixedPx {
            val height = multiplyDivideFloorExact(
                width.units,
                dimensions.heightPx,
                dimensions.widthPx,
            )
            return FixedPx(height.coerceAtLeast(1L))
        }

        private fun fallbackHeight(width: FixedPx): FixedPx =
            FixedPx(multiplyDivideFloorExact(width.units, 3, 2).coerceAtLeast(1L))

        private fun rebuild(entries: List<PageLayout>, width: FixedPx): LayoutLedger {
            val persistentEntries = entries.toPersistentList()
            val indexById = entries.mapIndexed { index, entry -> entry.spec.id to index }.toMap()
            val heights = ImmutableLongSumTree.create(entries.map { it.height.units })
            return LayoutLedger(width, persistentEntries, indexById, heights)
        }
    }
}
