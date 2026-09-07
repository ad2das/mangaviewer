package ml.melun.mangaview.source.ntk

import android.util.Log
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/** Races replica headers under one logical PageId owner; only one verified body is retained. */
internal class NtkReplicaRacer(
    private val transport: SourceTransport,
    private val replicas: NtkReplicaSelector,
    private val hedgeDelayMillis: Long = 75L,
    private val primaryRouteRetryMillis: Long = 250L,
    private val attemptTimeoutMillis: Long = 8_000L,
    maxConcurrentAttempts: Int = 6,
    visibleReservedAttempts: Int = if (maxConcurrentAttempts > 1) {
        (maxConcurrentAttempts / 3).coerceAtLeast(1)
    } else {
        0
    },
    private val preferQuic: Boolean = false,
) {
    private val admission = NtkReplicaAttemptAdmission(maxConcurrentAttempts, visibleReservedAttempts)
    private val protocols = NtkReplicaProtocolRegistry(preferQuic)
    private val selectedBodies = NtkSelectedBodyRegistry()

    init {
        require(hedgeDelayMillis >= 0L)
        require(primaryRouteRetryMillis > 0L)
        require(attemptTimeoutMillis > 0L)
        require(maxConcurrentAttempts > 0)
        require(visibleReservedAttempts in 0 until maxConcurrentAttempts)
    }

    suspend fun open(
        candidates: List<String>,
        headers: Map<String, String>,
        pageKey: String,
        validate: suspend (SourceResponse) -> OpenedPage,
        traceContext: NtkTraceContext? = null,
    ): NtkReplicaWinner = open(candidates, headers, pageKey, PageFetchPriority.NORMAL, validate, traceContext)

    suspend fun open(
        candidates: List<String>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
        traceContext: NtkTraceContext? = null,
    ): NtkReplicaWinner {
        val healthOrdered = replicas.orderedPrepared(replicas.prepare(candidates))
        require(healthOrdered.isNotEmpty()) { "NTK page has no replica candidates" }
        val preferred = healthOrdered.first()
        if (replicas.isVerified(preferred)) {
            val primaryWindow = when (priority) {
                PageFetchPriority.FOCUS -> healthOrdered.take(NtkReplicaRacePolicy.IMMEDIATE_WINDOW)
                PageFetchPriority.VISIBLE -> healthOrdered.take(NtkReplicaRacePolicy.IMMEDIATE_WINDOW)
                PageFetchPriority.IMMINENT_FORWARD,
                PageFetchPriority.FORWARD,
                PageFetchPriority.DISTANT_FORWARD,
                PageFetchPriority.ADJACENT_FORWARD,
                -> healthOrdered.take(NtkReplicaRacePolicy.FORWARD_WINDOW)
                else -> listOf(preferred)
            }
            try {
                return race(
                    primaryWindow,
                    headers,
                    pageKey,
                    priority,
                    validate,
                    retryPrimary = NtkReplicaRacePolicy.isImmediate(priority) &&
                        primaryWindow.size == 1,
                    routeHedgeDelayMillis = NtkReplicaRacePolicy.routeHedge(priority, hedgeDelayMillis),
                    protocolHedgeDelayMillis = NtkReplicaRacePolicy.PROVEN_PROTOCOL_HEDGE_MILLIS,
                    traceContext = traceContext,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (preferredFailure: Throwable) {
                val fallbacks = healthOrdered.drop(primaryWindow.size)
                if (fallbacks.isEmpty()) throw preferredFailure
                return try {
                    race(fallbacks, headers, pageKey, priority, validate, traceContext = traceContext)
                } catch (fallbackFailure: Throwable) {
                    fallbackFailure.addSuppressed(preferredFailure)
                    throw fallbackFailure
                }
            }
        }
        if (replicas.hasAcceptedPrefix(preferred) && priority != PageFetchPriority.BACKGROUND) {
            return raceProvisional(healthOrdered, headers, pageKey, priority, validate, traceContext)
        }
        val reservation = replicas.reservePreferred(replicas.prepare(candidates))
        return raceUnknown(
            reservation.candidates,
            headers,
            pageKey,
            priority,
            validate,
            reservation.primary,
            traceContext = traceContext,
        )
    }

    private suspend fun raceProvisional(
        ordered: List<NtkReplicaSelector.ReplicaCandidate>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
        traceContext: NtkTraceContext?,
    ): NtkReplicaWinner = race(
        ordered.take(if (NtkReplicaRacePolicy.isImmediate(priority)) {
            NtkReplicaRacePolicy.IMMEDIATE_WINDOW
        } else {
            NtkReplicaRacePolicy.FORWARD_WINDOW
        }), headers, pageKey, priority, validate,
        routeHedgeDelayMillis = NtkReplicaRacePolicy.routeHedge(priority, hedgeDelayMillis),
        protocolHedgeDelayMillis = NtkReplicaRacePolicy.PROVISIONAL_PROTOCOL_HEDGE_MILLIS,
        traceContext = traceContext,
    )

    private suspend fun raceUnknown(
        ordered: List<NtkReplicaSelector.ReplicaCandidate>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
        reservedPrimary: NtkReplicaSelector.ReplicaLease,
        traceContext: NtkTraceContext?,
    ): NtkReplicaWinner {
        var previousFailure: Throwable? = null
        ordered.chunked(NtkReplicaRacePolicy.candidateWindow(priority)).forEachIndexed { windowIndex, window ->
            try {
                return race(
                    window,
                    headers,
                    pageKey,
                    priority,
                    validate,
                    // A multi-origin viewport window already has independent hedges. Only the
                    // exact FOCUS is allowed one additional protocol attempt; ordinary visible
                    // neighbors must not recreate the old duplicate-body storm.
                    retryPrimary = window.size == 1 &&
                        priority != PageFetchPriority.FORWARD &&
                        priority != PageFetchPriority.IMMINENT_FORWARD &&
                        priority != PageFetchPriority.DISTANT_FORWARD &&
                        priority != PageFetchPriority.ADJACENT_FORWARD,
                    routeHedgeDelayMillis = NtkReplicaRacePolicy.routeHedge(priority, hedgeDelayMillis),
                    preacquiredPrimary = reservedPrimary.takeIf { windowIndex == 0 },
                    traceContext = traceContext,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                previousFailure?.let(failure::addSuppressed)
                previousFailure = failure
            }
        }
        throw requireNotNull(previousFailure)
    }

    private suspend fun race(
        ordered: List<NtkReplicaSelector.ReplicaCandidate>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
        retryPrimary: Boolean = false,
        routeHedgeDelayMillis: Long = hedgeDelayMillis,
        protocolHedgeDelayMillis: Long = NtkReplicaRacePolicy.PROTOCOL_HEDGE_MILLIS,
        preacquiredPrimary: NtkReplicaSelector.ReplicaLease? = null,
        traceContext: NtkTraceContext? = null,
    ): NtkReplicaWinner = NtkReplicaRaceOwnership(replicas::abandoned).use { ownership ->
        val winner = coroutineScope {
            val outcomes = Channel<Outcome>()
            val jobs = ordered.mapIndexed { index, candidate ->
                launch(start = if (index == 0) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
                    if (index > 0) delay(routeHedgeDelayMillis * index)
                    attempt(
                        candidate, headers, pageKey, validate, outcomes, priority,
                        preferredProtocol(candidate), preacquiredPrimary.takeIf { index == 0 }, ownership, traceContext,
                    )
                }
            }.toMutableList()
            val alternateProtocolHedge = transport.supportsProtocolSelection() &&
                ordered.isNotEmpty() &&
                (priority == PageFetchPriority.FOCUS || ordered.size == 1) &&
                (NtkReplicaRacePolicy.isImmediate(priority) ||
                    priority == PageFetchPriority.FORWARD ||
                    priority == PageFetchPriority.IMMINENT_FORWARD ||
                    priority == PageFetchPriority.DISTANT_FORWARD ||
                    priority == PageFetchPriority.ADJACENT_FORWARD) &&
                        !protocols.hasProof(ordered.first().url)
            if (retryPrimary || alternateProtocolHedge) jobs += launch {
                delay(if (alternateProtocolHedge) protocolHedgeDelayMillis else primaryRouteRetryMillis)
                val preferredProtocol = preferredProtocol(ordered.first())
                attempt(
                    ordered.first(), headers, pageKey, validate, outcomes, priority,
                    if (alternateProtocolHedge) !preferredProtocol else preferredProtocol,
                    null, ownership, traceContext,
                )
            }
            try {
                selectReplicaWinner(outcomes, jobs, priority, pageKey)
            } finally {
                jobs.forEach { it.cancel() }
                outcomes.cancel()
            }
        }
        markSelected(ownership.take(winner))
    }

    private suspend fun selectReplicaWinner(
        outcomes: Channel<Outcome>, jobs: List<kotlinx.coroutines.Job>,
        priority: PageFetchPriority, pageKey: String,
    ): NtkReplicaWinner {
        var lastFailure: Throwable? = null
        var received = 0
        while (received < jobs.size) {
            when (val outcome = outcomes.receive()) {
                is Outcome.Failed -> {
                    received += 1
                    lastFailure = outcome.failure
                }
                is Outcome.Succeeded -> {
                    received += 1
                    var selected: Outcome.Succeeded = outcome
                    val distributionGrace = NtkReplicaRacePolicy.bodyDistributionGrace(priority)
                    if (distributionGrace > 0L && activeBodyCount(outcome.winner) > 0) {
                        val deadline = System.nanoTime() +
                            distributionGrace * 1_000_000L
                        while (received < jobs.size && activeBodyCount(selected.winner) > 0) {
                            val remainingNanos = deadline - System.nanoTime()
                            if (remainingNanos <= 0L) break
                            val next = withTimeoutOrNull(
                                (remainingNanos + 999_999L) / 1_000_000L,
                            ) { outcomes.receive() } ?: break
                            received += 1
                            when (next) {
                                is Outcome.Failed -> lastFailure = next.failure
                                is Outcome.Succeeded -> {
                                    if (activeBodyCount(next.winner) < activeBodyCount(selected.winner)) {
                                        selected = next
                                    }
                                }
                            }
                        }
                    }
                    jobs.forEach { job -> if (job != selected.owner) job.cancel() }
                    jobs.joinAll()
                    outcomes.close()
                    return selected.winner
                }
            }
        }
        jobs.joinAll()
        outcomes.close()
        throw IOException("Every NTK page replica failed for $pageKey", lastFailure)
    }

    private suspend fun attempt(
        candidate: NtkReplicaSelector.ReplicaCandidate,
        headers: Map<String, String>,
        pageKey: String,
        validate: suspend (SourceResponse) -> OpenedPage,
        outcomes: Channel<Outcome>,
        priority: PageFetchPriority,
        routePreferQuic: Boolean,
        preacquiredLease: NtkReplicaSelector.ReplicaLease?,
        ownership: NtkReplicaRaceOwnership,
        traceContext: NtkTraceContext?,
    ) {
        val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?: error("Replica attempt has no job")
        var entered = false
        try {
            admission.withPermit(priority) {
                entered = true
                executeAttempt(
                    candidate,
                    headers,
                    pageKey,
                    validate,
                    outcomes,
                    owner,
                    priority,
                    routePreferQuic,
                    preacquiredLease,
                    ownership,
                    traceContext,
                )
            }
        } finally {
            if (!entered && preacquiredLease != null) replicas.abandoned(preacquiredLease)
        }
    }

    private suspend fun executeAttempt(
        candidate: NtkReplicaSelector.ReplicaCandidate,
        headers: Map<String, String>,
        pageKey: String,
        validate: suspend (SourceResponse) -> OpenedPage,
        outcomes: Channel<Outcome>,
        owner: kotlinx.coroutines.Job,
        priority: PageFetchPriority,
        routePreferQuic: Boolean,
        preacquiredLease: NtkReplicaSelector.ReplicaLease?,
        ownership: NtkReplicaRaceOwnership,
        traceContext: NtkTraceContext?,
    ) {
        val lease = preacquiredLease ?: replicas.acquireCandidate(candidate)
        require(lease.candidate == candidate) { "Reserved NTK replica does not match its attempt" }
        val started = System.nanoTime()
        val trace = NtkReplicaAttemptTrace(traceContext, pageKey, routePreferQuic, candidate.host)
        trace.start()
        var response: SourceResponse? = null
        var opened: OpenedPage? = null
        var transferred = false
        try {
            val ready = withTimeoutOrNull(attemptTimeoutMillis) {
                val candidateResponse = transport.execute(replicaRequest(candidate.url, headers, routePreferQuic, priority))
                response = candidateResponse
                trace.response(candidateResponse.statusCode)
                if (candidateResponse.statusCode !in 200..299) {
                    throw IOException("HTTP ${candidateResponse.statusCode}")
                }
                opened = validate(candidateResponse)
                trace.prefixValidated(candidateResponse.statusCode)
                response = null
                true
            }
            if (ready != true) throw SocketTimeoutException("NTK route prefix timed out after ${attemptTimeoutMillis}ms")
            val accepted = requireNotNull(opened)
            logReadyCandidate(pageKey, priority, routePreferQuic, candidate.host, started)
            val winner = NtkReplicaWinner(accepted, lease, started, routePreferQuic)
            ownership.retain(winner)
            transferred = true
            opened = null
            outcomes.send(Outcome.Succeeded(winner, owner))
        } catch (cancelled: CancellationException) {
            if (!transferred) replicas.abandoned(lease)
            trace.cancelled()
            throw cancelled
        } catch (failure: Throwable) {
            if (!transferred) replicas.failedAndReleased(lease)
            trace.failed(failure)
            logFailedCandidate(pageKey, routePreferQuic, candidate.url, failure)
            outcomes.send(Outcome.Failed(failure))
        } finally {
            response?.close()
            opened?.close()
        }
    }

    /** A protocol is reusable only after its full image body reached the page cache. */
    fun completed(winner: NtkReplicaWinner) {
        releaseSelected(winner)
        protocols.completed(winner.lease.candidate.url, winner.usedQuic)
    }

    fun failed(winner: NtkReplicaWinner) {
        releaseSelected(winner)
        protocols.failed(winner.lease.candidate.url, winner.usedQuic)
    }

    fun abandoned(winner: NtkReplicaWinner) {
        releaseSelected(winner)
    }

    private fun markSelected(winner: NtkReplicaWinner): NtkReplicaWinner = winner.also {
        selectedBodies.acquire(it.lease.candidate.host)
    }

    private fun releaseSelected(winner: NtkReplicaWinner) {
        selectedBodies.release(winner.lease.candidate.host)
    }

    private fun activeBodyCount(winner: NtkReplicaWinner): Int =
        selectedBodies.count(winner.lease.candidate.host)

    private sealed interface Outcome {
        data class Succeeded(val winner: NtkReplicaWinner, val owner: kotlinx.coroutines.Job) : Outcome
        data class Failed(val failure: Throwable) : Outcome
    }

    private fun preferredProtocol(candidate: NtkReplicaSelector.ReplicaCandidate): Boolean =
        protocols.preferred(candidate.url)

}

/** Prefix selection has a separate timeout; a selected large body keeps its progress-aware budget. */
private fun replicaRequest(url: String, headers: Map<String, String>, preferQuic: Boolean,
    priority: PageFetchPriority) = SourceRequest(
    url, headers = headers, totalTimeoutMillis = NtkReplicaRacePolicy.PAGE_BODY_TIMEOUT_MILLIS,
    preferQuic = preferQuic, priority = priority,
)

private fun logReadyCandidate(
    pageKey: String,
    priority: PageFetchPriority,
    quic: Boolean,
    host: String,
    started: Long,
) {
    if (!NtkReplicaRacePolicy.isImmediate(priority)) return
    runCatching {
        Log.d(NtkReplicaRacePolicy.LOG_TAG,
            "candidate-ready id=$pageKey quic=$quic host=$host " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000L}",
        )
    }
}

private fun logFailedCandidate(
    pageKey: String,
    routePreferQuic: Boolean,
    url: String,
    failure: Throwable,
) {
    runCatching {
        Log.w(
            NtkReplicaRacePolicy.LOG_TAG,
            "page candidate failed id=$pageKey quic=$routePreferQuic candidate=${identity(url)} " +
                "reason=${failure.message}",
        )
    }
}

private fun identity(url: String): String = runCatching {
    val uri = URI(url)
    "${uri.host.orEmpty()}${uri.path.orEmpty()}"
}.getOrDefault("invalid-url")
