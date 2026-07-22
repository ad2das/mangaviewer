package ml.melun.mangaview.reader

/**
 * Read-only terminal boundary for the callback-bound retire/prepare renderer.
 *
 * The submitted watermark advances at EGL_TRUE. The delivered watermark advances only after
 * the physical Choreographer callback has retired that frame's immutable target and its
 * compositor latch has joined, then the proof is dispatched through the monotonically ordered
 * Kotlin callback lane. Qualification observes both; it never requests a
 * render, drains EGL timestamp history, flushes feedback, or changes application timing.
 */
object NtkPhysicalCompletionContract {
    @JvmStatic
    fun violation(
        expectedTerminalInputEventNanos: Long,
        latestSubmittedInputEventNanos: Long,
        latestDeliveredLatchedInputEventNanos: Long
    ): String? = when {
        expectedTerminalInputEventNanos <= 0L -> "terminal-input-absent"
        latestSubmittedInputEventNanos < expectedTerminalInputEventNanos ->
            "terminal-input-not-submitted"
        latestDeliveredLatchedInputEventNanos < expectedTerminalInputEventNanos ->
            "terminal-retire-latch-callback-not-delivered"
        else -> null
    }
}
