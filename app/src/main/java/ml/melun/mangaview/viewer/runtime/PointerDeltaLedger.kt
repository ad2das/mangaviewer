package ml.melun.mangaview.viewer.runtime

/** Main-thread ledger that preserves every observed pointer delta exactly once. */
internal class PointerDeltaLedger {
    private val segments = ArrayDeque<Double>()
    val pendingPixels: Double get() = segments.sum()
    val hasPending: Boolean get() = segments.isNotEmpty()
    private var lastY = 0f

    fun begin(y: Float) {
        check(!hasPending) { "A pointer sequence started before pending input was drained" }
        lastY = y
    }

    fun append(y: Float): Double {
        val delta = (lastY - y).toDouble()
        if (delta != 0.0) {
            val last = segments.lastOrNull()
            if (last != null && (last > 0.0) == (delta > 0.0)) {
                segments.removeLast()
                segments.addLast(last + delta)
            } else segments.addLast(delta)
        }
        lastY = y
        return delta
    }

    fun drain(): List<Double> = segments.toList().also { segments.clear() }

    fun rebase(y: Float) {
        lastY = y
    }
}
