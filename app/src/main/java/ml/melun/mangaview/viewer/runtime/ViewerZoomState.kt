package ml.melun.mangaview.viewer.runtime

/**
 * View transform for magnified reading. The engine keeps rendering the fit-width document, so a
 * local pixel stays one document pixel; the surface view is scaled and shifted around the
 * document row and column the fingers hold. Vertical anchoring becomes engine scroll, horizontal
 * anchoring and magnification stay a compositor transform.
 */
internal class ViewerZoomState {
    var scale: Float = MIN_SCALE
        private set
    var translationX: Float = 0f
        private set
    private var viewportWidth = 0f

    val zoomed: Boolean get() = scale > MIN_SCALE + SCALE_EPSILON

    fun viewportChanged(width: Float) {
        viewportWidth = width.coerceAtLeast(0f)
        translationX = clampTranslation(translationX)
    }

    /**
     * Applies one incremental pinch step. Returns the engine scroll that keeps the document row
     * under [focusY] anchored while the scale changes; positive advances toward the next screen.
     */
    fun pinch(spanRatio: Float, focusX: Float, focusY: Float): Double {
        val previous = scale
        val next = (previous * spanRatio).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == previous) return 0.0
        scale = next
        translationX = clampTranslation(translationX + focusX * (previous - next))
        return anchorScroll(previous, next, focusY)
    }

    /** One-finger horizontal pan while magnified. [screenDeltaX] is a distance on the glass. */
    fun pan(screenDeltaX: Float): Boolean {
        if (!zoomed) return false
        val next = clampTranslation(translationX + screenDeltaX)
        if (next == translationX) return false
        translationX = next
        return true
    }

    /** Toggles between fit-width and [DOUBLE_TAP_SCALE] around the tapped point. */
    fun toggle(focusX: Float, focusY: Float): Double {
        val previous = scale
        val next = if (zoomed) MIN_SCALE else DOUBLE_TAP_SCALE
        scale = next
        translationX = if (next == MIN_SCALE) 0f
        else clampTranslation(translationX + focusX * (previous - next))
        return anchorScroll(previous, next, focusY)
    }

    private fun anchorScroll(previous: Float, next: Float, focusY: Float): Double =
        (focusY * (1f - previous / next)).toDouble()

    private fun clampTranslation(value: Float): Float {
        if (viewportWidth <= 0f) return 0f
        return value.coerceIn(viewportWidth * (1f - scale), 0f)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2f
        private const val SCALE_EPSILON = 0.001f
    }
}
