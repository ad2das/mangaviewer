package ml.melun.mangaview.engine.session

/** Returns true only when ready FIFO work remains after yielding its processing budget. */
internal inline fun replayWithinBudget(
    clockNanos: () -> Long,
    hasPending: () -> Boolean,
    advance: () -> Boolean,
): Boolean {
    val startedAt = clockNanos()
    var processed = 0
    while (hasPending()) {
        if (advance()) return false
        processed++
        // A geometry step remains indivisible. Its elapsed time may exceed the budget,
        // but another step must not start after the budget has been observed exhausted.
        if (hasPending() && (processed >= 32 || clockNanos() - startedAt >= 1_000_000L)) return true
    }
    return false
}
