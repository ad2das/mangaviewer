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
        val timing = startup ?: return (
            "Cold first frame ${firstContentMillis}ms exceeded ${limitMillis}ms " +
                "without structured timing evidence"
            )
        val verified = timing.initialVerifiedAtNanos
        val decoded = timing.initialDecodedAtNanos
        val submitted = timing.firstActualSubmittedAtNanos
        val presented = timing.firstActualPresentedAtNanos
        if (verified == null || decoded == null || submitted == null || presented == null) {
            return "Cold first frame ${firstContentMillis}ms exceeded ${limitMillis}ms " +
                "with incomplete startup timing: $timing"
        }
        val decodeNanos = decoded - verified
        val presentationNanos = presented - submitted
        val internalTailNanos = decodeNanos + presentationNanos
        return if (decodeNanos >= MAXIMUM_INITIAL_DECODE_NANOS ||
            presentationNanos >= MAXIMUM_INITIAL_PRESENT_NANOS ||
            internalTailNanos >= MAXIMUM_INITIAL_INTERNAL_TAIL_NANOS
        ) {
            "Cold first frame ${firstContentMillis}ms exceeded ${limitMillis}ms with app tail " +
                "decode=${decodeNanos / 1_000_000.0}ms, " +
                "present=${presentationNanos / 1_000_000.0}ms"
        } else {
            null
        }
    }

    const val MAXIMUM_INITIAL_DECODE_NANOS = 350_000_000L
    const val MAXIMUM_INITIAL_PRESENT_NANOS = 150_000_000L
    const val MAXIMUM_INITIAL_INTERNAL_TAIL_NANOS = 500_000_000L
}
