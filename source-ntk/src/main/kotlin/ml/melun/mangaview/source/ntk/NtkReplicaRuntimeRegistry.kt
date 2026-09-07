package ml.melun.mangaview.source.ntk

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.source.PageFetchPriority

/** Admission boundary that reserves transport attempts for newly visible pages. */
internal class NtkReplicaAttemptAdmission(maxAttempts: Int, visibleReserved: Int) {
    private enum class LeaseState { QUEUED, RUNNING, FINISHED }

    private inner class Lease(
        val priority: PageFetchPriority,
        val sequence: Long,
    ) {
        val ready = CompletableDeferred<Unit>()
        var state = LeaseState.QUEUED

        fun close() = release(this)
    }

    private val maximumAttempts = maxAttempts
    private val maximumSpeculativeAttempts = maxAttempts - visibleReserved
    private val lock = Any()
    private val queued = mutableListOf<Lease>()
    private var runningAttempts = 0
    private var runningSpeculativeAttempts = 0
    private var sequence = 0L

    init {
        require(maxAttempts > 0)
        require(visibleReserved in 0 until maxAttempts)
    }

    suspend fun <T> withPermit(priority: PageFetchPriority, block: suspend () -> T): T {
        val lease = acquire(priority)
        return try {
            lease.ready.await()
            block()
        } finally {
            lease.close()
        }
    }

    private fun acquire(priority: PageFetchPriority): Lease {
        val lease: Lease
        val ready = synchronized(lock) {
            sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
            lease = Lease(priority, sequence)
            queued += lease
            reserveReadyLocked()
        }
        ready.forEach { it.ready.complete(Unit) }
        return lease
    }

    private fun release(lease: Lease) {
        val ready = synchronized(lock) {
            when (lease.state) {
                LeaseState.QUEUED -> queued.remove(lease)
                LeaseState.RUNNING -> {
                    runningAttempts -= 1
                    if (!lease.priority.isVisibleAttempt()) runningSpeculativeAttempts -= 1
                }
                LeaseState.FINISHED -> return
            }
            lease.state = LeaseState.FINISHED
            reserveReadyLocked()
        }
        ready.forEach { it.ready.complete(Unit) }
    }

    private fun reserveReadyLocked(): List<Lease> = buildList {
        while (runningAttempts < maximumAttempts) {
            val next = queued.asSequence()
                .filter { lease ->
                    lease.priority.isVisibleAttempt() ||
                        runningSpeculativeAttempts < maximumSpeculativeAttempts
                }
                .minWithOrNull(compareBy<Lease>({ it.priority.ordinal }, { it.sequence }))
                ?: break
            queued.remove(next)
            next.state = LeaseState.RUNNING
            runningAttempts += 1
            if (!next.priority.isVisibleAttempt()) runningSpeculativeAttempts += 1
            add(next)
        }
    }

    private fun PageFetchPriority.isVisibleAttempt(): Boolean =
        this == PageFetchPriority.FOCUS || this == PageFetchPriority.VISIBLE
}

/** Remembers a protocol only after a complete validated image reached disk. */
internal class NtkReplicaProtocolRegistry(private val defaultQuic: Boolean) {
    private val protocols = ConcurrentHashMap<String, Boolean>()

    fun hasProof(url: String): Boolean = protocols.containsKey(key(url))
    fun preferred(url: String): Boolean = protocols[key(url)] ?: defaultQuic
    fun completed(url: String, usedQuic: Boolean) { protocols[key(url)] = usedQuic }
    fun failed(url: String, usedQuic: Boolean) { protocols.remove(key(url), usedQuic) }

    private fun key(url: String): String = runCatching {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        "${uri.scheme}://${uri.host.orEmpty().lowercase()}:$port"
    }.getOrDefault(url)
}

/** Counts selected bodies per replica so the opening viewport can spread across idle hosts. */
internal class NtkSelectedBodyRegistry {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun acquire(host: String) { counts.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet() }
    fun count(host: String): Int = counts[host]?.get() ?: 0
    fun release(host: String) {
        counts.computeIfPresent(host) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }
}
