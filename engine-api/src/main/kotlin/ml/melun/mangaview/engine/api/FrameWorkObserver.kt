package ml.melun.mangaview.engine.api

/**
 * Trace-only observer for app frame scheduling provenance. Implementations must not change
 * rendering, scheduling, or pacing; they only record which app event caused the next scene offer.
 */
fun interface FrameWorkObserver {
    fun workScheduled(kind: Int, atNanos: Long)

    companion object {
        /** Refresh entry caused by an asynchronous work result (tile completion, retry, clear). */
        const val WORK_RESULT = 2

        /** Refresh entry caused by a snapshot update or an enabled/disabled transition. */
        const val SNAPSHOT_UPDATE = 3
    }
}
