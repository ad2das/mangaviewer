package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.delay

/** One initial attempt and a finite recovery window, cancelled with the owning surface. */
internal suspend fun retrySurfaceAttachment(
    maximumRetries: Int,
    retryDelayMillis: Long,
    canRetry: () -> Boolean,
    attach: suspend () -> Boolean,
    exhausted: () -> Unit,
) {
    require(maximumRetries >= 0 && retryDelayMillis > 0)
    repeat(maximumRetries + 1) { attempt ->
        if (!canRetry()) return
        if (attach()) return
        if (attempt == maximumRetries || !canRetry()) {
            exhausted()
            return
        }
        delay(retryDelayMillis)
    }
}
