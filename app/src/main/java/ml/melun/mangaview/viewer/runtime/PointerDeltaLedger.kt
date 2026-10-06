package ml.melun.mangaview.viewer.runtime

/**
 * Main-thread ledger that preserves every observed pointer delta exactly once.
 *
 * Segments live in a primitive ring so the hot drain path neither boxes each delta nor copies the
 * queue into a new list. Draining double-buffers the two arrays: the buffers are swapped and the
 * count is reset to zero before the drained buffer is visited, so the ledger is already clear when
 * a visitor runs. A visitor that throws cannot get its segments back on the next drain, and a
 * visitor that re-entrantly appends writes into the next active buffer, leaving the iframe under
 * visit untouched, so the append is delivered on the next drain exactly once.
 */
internal class PointerDeltaLedger {
    @PublishedApi internal var segments = DoubleArray(INITIAL_CAPACITY)
    @PublishedApi internal var size = 0
    @PublishedApi internal var drainedSegments = DoubleArray(INITIAL_CAPACITY)
    val pendingPixels: Double get() {
        var sum = 0.0
        for (index in 0 until size) sum += segments[index]
        return sum
    }
    val hasPending: Boolean get() = size > 0
    private var lastY = 0f

    fun begin(y: Float) {
        check(!hasPending) { "A pointer sequence started before pending input was drained" }
        lastY = y
    }

    fun append(y: Float): Double {
        val delta = (lastY - y).toDouble()
        if (delta != 0.0) {
            if (size > 0 && (segments[size - 1] > 0.0) == (delta > 0.0)) {
                segments[size - 1] += delta
            } else {
                if (size == segments.size) segments = segments.copyOf(size * 2)
                segments[size++] = delta
            }
        }
        lastY = y
        return delta
    }

    /** Allocation-free drain: swaps the buffers first, so the ledger is clear before visiting. */
    inline fun drainEach(action: (Double) -> Unit) {
        val visiting = segments
        val count = size
        segments = drainedSegments
        drainedSegments = visiting
        size = 0
        for (index in 0 until count) action(visiting[index])
    }

    /** Collecting form used by tests and diagnostics; the runtime hot path uses [drainEach]. */
    fun drain(): List<Double> {
        val visiting = segments
        val count = size
        segments = drainedSegments
        drainedSegments = visiting
        size = 0
        val drained = ArrayList<Double>(count)
        for (index in 0 until count) drained.add(visiting[index])
        return drained
    }

    fun rebase(y: Float) {
        lastY = y
    }

    private companion object {
        const val INITIAL_CAPACITY = 8
    }
}
