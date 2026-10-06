package ml.melun.mangaview.viewer.runtime

/**
 * Main-thread ledger that preserves every observed pointer delta exactly once.
 *
 * Segments live in a primitive ring so the hot drain path neither boxes each delta nor copies the
 * queue into a new list. [drainEach] is inlined at the call site and visits the segments in order
 * before clearing the ledger.
 */
internal class PointerDeltaLedger {
    @PublishedApi internal var segments = DoubleArray(INITIAL_CAPACITY)
    @PublishedApi internal var size = 0
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

    /** Allocation-free drain: visits every pending segment in order, then clears the ledger. */
    inline fun drainEach(action: (Double) -> Unit) {
        for (index in 0 until size) action(segments[index])
        size = 0
    }

    /** Collecting form used by tests and diagnostics; the runtime hot path uses [drainEach]. */
    fun drain(): List<Double> {
        val drained = ArrayList<Double>(size)
        for (index in 0 until size) drained.add(segments[index])
        size = 0
        return drained
    }

    fun rebase(y: Float) {
        lastY = y
    }

    private companion object {
        const val INITIAL_CAPACITY = 8
    }
}
