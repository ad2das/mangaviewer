package ml.melun.mangaview.viewer.runtime

/** Main-thread ledger that preserves every observed pointer delta exactly once. */
internal class PointerDeltaLedger {
    var pendingPixels: Double = 0.0
        private set
    private var lastY = 0f

    fun begin(y: Float) {
        check(pendingPixels == 0.0) { "A pointer sequence started before pending input was drained" }
        lastY = y
    }

    fun append(y: Float): Double {
        val delta = (lastY - y).toDouble()
        pendingPixels += delta
        lastY = y
        return delta
    }

    fun consume(delta: Double) {
        pendingPixels -= delta
    }

    fun drain(): Double = pendingPixels.also { pendingPixels = 0.0 }

    fun rebase(y: Float) {
        lastY = y
    }
}
