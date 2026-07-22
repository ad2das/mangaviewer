package ml.melun.mangaview.reader

import kotlin.math.max
import kotlin.math.min

/**
 * Canonical half-open content-pixel intervals used by the NTK strip pipeline.
 *
 * Page readiness is deliberately absent: a decoded original-source tile contributes coverage as
 * soon as it is resident, even when the rest of its page has not finished decoding.
 */
class NtkStripIntervalSet {
    data class Interval(val startPx: Long, val endPx: Long) {
        init { require(startPx >= 0L && endPx > startPx) }
        val lengthPx: Long get() = endPx - startPx
    }

    private val intervals = ArrayList<Interval>()

    fun snapshot(): List<Interval> = intervals.toList()

    fun isEmpty(): Boolean = intervals.isEmpty()

    fun clear() = intervals.clear()

    fun add(startPx: Long, endPx: Long) {
        if (startPx < 0L || endPx <= startPx) return
        var mergedStart = startPx
        var mergedEnd = endPx
        var index = 0
        while (index < intervals.size && intervals[index].endPx < mergedStart) index++
        val insertAt = index
        while (index < intervals.size && intervals[index].startPx <= mergedEnd) {
            val current = intervals[index]
            mergedStart = min(mergedStart, current.startPx)
            mergedEnd = max(mergedEnd, current.endPx)
            intervals.removeAt(index)
        }
        intervals.add(insertAt, Interval(mergedStart, mergedEnd))
    }

    fun remove(startPx: Long, endPx: Long) {
        if (startPx < 0L || endPx <= startPx) return
        var index = 0
        while (index < intervals.size) {
            val current = intervals[index]
            if (current.endPx <= startPx) {
                index++
                continue
            }
            if (current.startPx >= endPx) break
            intervals.removeAt(index)
            if (current.startPx < startPx) {
                intervals.add(index++, Interval(current.startPx, startPx))
            }
            if (current.endPx > endPx) {
                intervals.add(index, Interval(endPx, current.endPx))
                break
            }
        }
    }

    fun contains(startPx: Long, endPx: Long): Boolean {
        if (startPx < 0L || endPx <= startPx) return false
        for (interval in intervals) {
            if (interval.startPx > startPx) return false
            if (interval.startPx <= startPx && interval.endPx >= endPx) return true
        }
        return false
    }

    /** End of the continuous resident interval containing [fromPx], or [fromPx] at a gap. */
    fun continuousEndFrom(fromPx: Long): Long {
        if (fromPx < 0L) return fromPx
        for (interval in intervals) {
            if (interval.startPx > fromPx) return fromPx
            if (interval.endPx > fromPx) return interval.endPx
        }
        return fromPx
    }

    /** Start of the continuous resident interval reaching [fromPx], or [fromPx] at a gap. */
    fun continuousStartFrom(fromPx: Long): Long {
        if (fromPx < 0L) return fromPx
        for (interval in intervals.asReversed()) {
            if (interval.endPx < fromPx) return fromPx
            if (interval.startPx < fromPx && interval.endPx >= fromPx) return interval.startPx
        }
        return fromPx
    }

    fun firstGap(startPx: Long, endPx: Long): Long? {
        if (startPx < 0L || endPx <= startPx) return startPx
        var cursor = startPx
        for (interval in intervals) {
            if (interval.endPx <= cursor) continue
            if (interval.startPx > cursor) return cursor
            cursor = max(cursor, interval.endPx)
            if (cursor >= endPx) return null
        }
        return cursor
    }

    fun coveredLength(startPx: Long, endPx: Long): Long {
        if (startPx < 0L || endPx <= startPx) return 0L
        var result = 0L
        for (interval in intervals) {
            if (interval.endPx <= startPx) continue
            if (interval.startPx >= endPx) break
            result += max(0L, min(endPx, interval.endPx) - max(startPx, interval.startPx))
        }
        return result
    }
}
