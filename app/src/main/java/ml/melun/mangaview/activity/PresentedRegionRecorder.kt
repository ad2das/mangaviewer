package ml.melun.mangaview.activity

import java.util.ArrayDeque
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import ml.melun.mangaview.viewer.runtime.PresentedImageRegionBatch

internal class PresentedRegionRecorder(private val capacity: Int = 8_192) {
    private val records = ArrayDeque<PresentedImageRegion>()
    private var sequence = 0L

    init { require(capacity > 0) }

    @Synchronized
    fun record(regions: List<PresentedImageRegion>) {
        regions.forEach { region ->
            if (records.size == capacity) records.removeFirst()
            records.addLast(region)
            sequence = Math.incrementExact(sequence)
        }
    }

    @Synchronized
    fun since(afterSequence: Long): PresentedImageRegionBatch {
        require(afterSequence in 0L..sequence)
        val oldest = sequence - records.size
        val skip = (afterSequence - oldest).coerceAtLeast(0L).toInt()
        return PresentedImageRegionBatch(sequence, records.drop(skip), afterSequence < oldest)
    }
}
