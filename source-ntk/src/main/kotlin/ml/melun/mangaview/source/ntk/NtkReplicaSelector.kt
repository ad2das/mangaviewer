package ml.melun.mangaview.source.ntk

import java.net.URI

internal class NtkReplicaSelector(
    private val resolveHost: (String) -> String = ::ntkReplicaHost,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val health = mutableMapOf<String, ReplicaHealth>()

    fun prepare(candidates: List<String>): List<ReplicaCandidate> = candidates.map { url ->
        ReplicaCandidate(url, resolveHost(url))
    }

    suspend fun order(candidates: List<String>): List<String> {
        val prepared = prepare(candidates)
        return synchronized(lock) { ordered(prepared).map(ReplicaCandidate::url) }
    }

    suspend fun acquire(candidates: List<String>): String =
        acquirePrepared(prepare(candidates)).candidate.url

    suspend fun acquirePrepared(candidates: List<ReplicaCandidate>): ReplicaLease = synchronized(lock) {
        require(candidates.isNotEmpty()) { "NTK page has no replica candidates" }
        val selected = ordered(candidates).first()
        val key = selected.host
        val previous = health[key] ?: ReplicaHealth()
        put(key, previous.copy(
            inFlight = previous.inFlight + 1,
            lastTouchedMillis = nowMillis(),
        ))
        ReplicaLease(selected)
    }

    private fun ordered(candidates: List<ReplicaCandidate>): List<ReplicaCandidate> {
        val now = nowMillis()
        return candidates.withIndex()
            .sortedWith(compareBy<IndexedValue<ReplicaCandidate>>(
                { health[it.value.host]?.blockedUntilMillis?.let { due -> due > now } ?: false },
                { predictedCompletion(health[it.value.host] ?: ReplicaHealth()) },
                { health[it.value.host]?.failures ?: 0 },
                { health[it.value.host]?.inFlight ?: 0 },
                IndexedValue<ReplicaCandidate>::index,
            ))
            .map(IndexedValue<ReplicaCandidate>::value)
    }

    suspend fun release(url: String) = release(ReplicaLease(ReplicaCandidate(url, resolveHost(url))))

    suspend fun release(lease: ReplicaLease) = releaseNow(lease)

    private fun releaseNow(lease: ReplicaLease) = synchronized(lock) {
        val key = lease.candidate.host
        val previous = health[key] ?: return@synchronized
        put(key, previous.copy(
            inFlight = (previous.inFlight - 1).coerceAtLeast(0),
            lastTouchedMillis = nowMillis(),
        ))
    }

    suspend fun succeeded(url: String, latencyMillis: Long) =
        succeeded(ReplicaLease(ReplicaCandidate(url, resolveHost(url))), latencyMillis)

    suspend fun succeeded(lease: ReplicaLease, latencyMillis: Long) = succeedNow(lease, latencyMillis)

    private fun succeedNow(lease: ReplicaLease, latencyMillis: Long) = synchronized(lock) {
        require(latencyMillis >= 0L) { "Replica latency must not be negative" }
        val key = lease.candidate.host
        val previous = health[key] ?: ReplicaHealth()
        val smoothed = previous.latencyMillis?.let { smooth(it, latencyMillis) } ?: latencyMillis
        put(key, previous.copy(
            failures = 0,
            latencyMillis = smoothed,
            successfulSamples = (previous.successfulSamples + 1).coerceAtMost(MIN_SUCCESS_SAMPLES),
            blockedUntilMillis = 0L,
            lastTouchedMillis = nowMillis(),
        ))
    }

    suspend fun failed(url: String) = failed(ReplicaLease(ReplicaCandidate(url, resolveHost(url))))

    suspend fun failed(lease: ReplicaLease) = failNow(lease, release = false)

    private fun failNow(lease: ReplicaLease, release: Boolean) = synchronized(lock) {
        val key = lease.candidate.host
        val previous = health[key] ?: ReplicaHealth()
        val failures = (previous.failures + 1).coerceAtMost(MAX_FAILURES)
        val cooldown = BASE_COOLDOWN_MILLIS shl (failures - 1).coerceAtMost(4)
        val now = nowMillis()
        put(key, previous.copy(
            failures = failures,
            blockedUntilMillis = saturatingAdd(now, cooldown),
            inFlight = if (release) (previous.inFlight - 1).coerceAtLeast(0) else previous.inFlight,
            lastTouchedMillis = now,
        ))
    }

    fun completed(lease: ReplicaLease, latencyMillis: Long) = synchronized(lock) {
        require(latencyMillis >= 0L) { "Replica latency must not be negative" }
        val key = lease.candidate.host
        val previous = health[key] ?: ReplicaHealth()
        val smoothed = previous.latencyMillis?.let { smooth(it, latencyMillis) } ?: latencyMillis
        put(key, previous.copy(
            failures = 0,
            latencyMillis = smoothed,
            successfulSamples = (previous.successfulSamples + 1).coerceAtMost(MIN_SUCCESS_SAMPLES),
            blockedUntilMillis = 0L,
            inFlight = (previous.inFlight - 1).coerceAtLeast(0),
            lastTouchedMillis = nowMillis(),
        ))
    }

    fun failedAndReleased(lease: ReplicaLease) = failNow(lease, release = true)

    fun abandoned(lease: ReplicaLease) = releaseNow(lease)

    internal suspend fun trackedHostCount(): Int = synchronized(lock) { health.size }

    private fun put(key: String, value: ReplicaHealth) {
        if (key !in health && health.size >= MAX_TRACKED_HOSTS) {
            removeOldestIdle()
        }
        health[key] = value
        while (health.size > MAX_TRACKED_HOSTS && removeOldestIdle()) Unit
    }

    private fun removeOldestIdle(): Boolean {
        val oldest = health.entries.asSequence()
            .filter { it.value.inFlight == 0 }
            .minByOrNull { it.value.lastTouchedMillis } ?: return false
        health.remove(oldest.key)
        return true
    }

    private fun smooth(previous: Long, sample: Long): Long =
        previous / 2L + sample / 2L + (previous % 2L + sample % 2L) / 2L

    private fun predictedCompletion(value: ReplicaHealth): Long {
        val measured = value.latencyMillis?.takeIf {
            value.successfulSamples >= MIN_SUCCESS_SAMPLES
        }
        val latency = (measured ?: UNMEASURED_LATENCY_MILLIS).coerceAtLeast(1L)
        return saturatingMultiply(latency, value.inFlight.toLong() + 1L)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long =
        if (left == 0L || right == 0L) 0L
        else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

    internal data class ReplicaCandidate(val url: String, val host: String)

    internal data class ReplicaLease(val candidate: ReplicaCandidate)

    private data class ReplicaHealth(
        val failures: Int = 0,
        val latencyMillis: Long? = null,
        val blockedUntilMillis: Long = 0L,
        val inFlight: Int = 0,
        val lastTouchedMillis: Long = 0L,
        val successfulSamples: Int = 0,
    )

    private companion object {
        const val BASE_COOLDOWN_MILLIS = 2_000L
        const val MAX_FAILURES = 8
        const val MAX_TRACKED_HOSTS = 64
        const val UNMEASURED_LATENCY_MILLIS = 300L
        const val MIN_SUCCESS_SAMPLES = 3
    }
}

private fun ntkReplicaHost(url: String): String = requireNotNull(URI(url).host) {
    "NTK page candidate has no host"
}.lowercase()
