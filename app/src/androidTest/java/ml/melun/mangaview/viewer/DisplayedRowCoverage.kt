package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId

/** Only independently correlated displayed image rows may enter this accumulator. */
internal class DisplayedRowCoverage {
    private val heights = linkedMapOf<PageId, Int>()
    private val ranges = linkedMapOf<PageId, List<IntRange>>()
    private val errors = linkedSetOf<String>()

    fun record(page: PageId, top: Int, bottomExclusive: Int, height: Int, verified: Boolean) {
        if (!verified) return
        if (height <= 0 || top < 0 || bottomExclusive <= top || bottomExclusive > height) {
            errors += "Invalid displayed source rows for $page: $top..$bottomExclusive/$height"
            return
        }
        val prior = heights.putIfAbsent(page, height)
        if (prior != null && prior != height) {
            errors += "Displayed source height changed for $page: $prior -> $height"
            return
        }
        ranges[page] = merge(ranges[page].orEmpty() + listOf(top until bottomExclusive))
    }

    fun complete(page: PageId): Boolean = heights[page]?.let { height ->
        ranges[page] == listOf(0 until height)
    } == true

    fun firstMissing(pages: List<PageId>): PageId? = pages.firstOrNull { !complete(it) }

    fun firstMissingRow(page: PageId): Int {
        var cursor = 0
        ranges[page].orEmpty().forEach { range ->
            if (range.first > cursor) return cursor
            cursor = maxOf(cursor, range.last + 1)
        }
        return cursor
    }

    fun sourceHeight(page: PageId): Int? = heights[page]

    fun violations(pages: List<PageId>): List<String> = errors.toList() +
        pages.filterNot(::complete).map { "Incomplete actual displayed rows: $it firstMissing=${firstMissingRow(it)}" }

    fun report(): String = buildString {
        appendLine("page\tsourceHeight\tdisplayedHalfOpenRows\tcomplete")
        heights.forEach { (page, height) ->
            append(page).append('\t').append(height).append('\t')
                .append(ranges[page].orEmpty().joinToString { "${it.first}:${it.last + 1}" })
                .append('\t').appendLine(complete(page))
        }
    }

    private fun merge(input: List<IntRange>): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        input.sortedBy(IntRange::first).forEach { next ->
            val last = merged.lastOrNull()
            if (last == null || next.first.toLong() > last.last.toLong() + 1L) merged += next
            else merged[merged.lastIndex] = last.first..maxOf(last.last, next.last)
        }
        return merged
    }
}
