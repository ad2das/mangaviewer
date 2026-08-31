package ml.melun.mangaview.data.network

internal fun httpEngineRemainingTimeoutNanos(
    totalTimeoutMillis: Long,
    startedAtNanos: Long,
    nowNanos: Long,
): Long {
    require(totalTimeoutMillis > 0L) { "HTTP engine timeout must be positive" }
    val maximumMillis = Long.MAX_VALUE / NANOS_PER_MILLISECOND
    val budget = totalTimeoutMillis.coerceAtMost(maximumMillis) * NANOS_PER_MILLISECOND
    val elapsed = (nowNanos - startedAtNanos).coerceAtLeast(0L)
    return budget - elapsed
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
