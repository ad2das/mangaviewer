package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId

/**
 * Forward reading velocity in pages per second, measured from the session anchor's movement over a
 * short trailing window. The position is the page ordinal plus the fraction of the page's own
 * height, so tall and short pages advance the estimate by the same amount for the same visual
 * distance. Only forward motion counts: a still or reversing reader reports 0 and the interaction
 * horizon stays at its minimum depth.
 *
 * The class owns no clock; callers pass the monotonic sample time, which keeps the window pure and
 * lets tests drive it with exact timestamps.
 */
internal class ReadingVelocity(private val windowNanos: Long = TRAILING_WINDOW_NANOS) {
    private class Sample(val episodeId: EpisodeId, val position: Double, val atNanos: Long)

    private val samples = ArrayDeque<Sample>()

    /** Latest window estimate; never negative. */
    var pagesPerSecond: Double = 0.0
        private set

    fun reset() {
        samples.clear()
        pagesPerSecond = 0.0
    }

    /**
     * Adds one observed anchor position: [ordinal] within the document, [fraction] of the page's
     * height already read. A sample from another document restarts the window — an ordinal jump
     * across a boundary is not motion.
     */
    fun onSample(episodeId: EpisodeId, ordinal: Int, fraction: Double, atNanos: Long) {
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        val position = ordinal.toDouble() + safeFraction
        if (samples.lastOrNull()?.episodeId?.let { it != episodeId } == true) samples.clear()
        samples.addLast(Sample(episodeId, position, atNanos))
        while (samples.size > 2 && samples.first().atNanos < atNanos - windowNanos) samples.removeFirst()
        val first = samples.first()
        val spanNanos = (samples.last().atNanos - first.atNanos).coerceAtLeast(0L)
        pagesPerSecond = if (spanNanos <= 0L) 0.0
        else ((position - first.position) / (spanNanos / NANOS_PER_SECOND)).coerceAtLeast(0.0)
    }

    /**
     * The velocity as of [nowNanos]. A newest sample older than the trailing window no longer
     * describes the reader — a held finger must not keep the last fling's estimate — so the read
     * goes to zero until the anchor moves again. Callers that want the last computed estimate
     * regardless of its age read [pagesPerSecond].
     */
    fun pagesPerSecondAt(nowNanos: Long): Double {
        val newest = samples.lastOrNull() ?: return 0.0
        return if (nowNanos - newest.atNanos > windowNanos) 0.0 else pagesPerSecond
    }

    private companion object {
        const val TRAILING_WINDOW_NANOS = 400_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
