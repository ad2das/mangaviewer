package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming

/** One first-content gate shared by single-episode and auto-append qualification. */
internal object ViewerFirstContentPolicy {
    fun violation(
        firstContentMillis: Long,
        limitMillis: Long,
        startup: ViewerStartupTiming?,
    ): String? {
        require(firstContentMillis >= 0L)
        require(limitMillis > 0L)
        if (firstContentMillis <= limitMillis) return null
        return "Cold first frame ${firstContentMillis}ms exceeded ${limitMillis}ms" +
            startupBreakdown(startup)
    }

    private fun startupBreakdown(timing: ViewerStartupTiming?): String {
        timing ?: return " without structured timing evidence"
        fun elapsed(end: Long?, start: Long?): String = if (end == null || start == null) {
            "unknown"
        } else {
            "${(end - start).coerceAtLeast(0L) / 1_000_000.0}ms"
        }
        return "; manifest=${elapsed(timing.manifestReadyAtNanos, timing.openStartedAtNanos)}, " +
            "response=${elapsed(timing.initialResponseStartedAtNanos, timing.manifestReadyAtNanos)}, " +
            "transfer=${elapsed(timing.initialVerifiedAtNanos, timing.initialResponseStartedAtNanos)}, " +
            "decode=${elapsed(timing.initialDecodedAtNanos, timing.initialVerifiedAtNanos)}, " +
            "present=${elapsed(timing.firstActualPresentedAtNanos, timing.firstActualSubmittedAtNanos)}"
    }
}
