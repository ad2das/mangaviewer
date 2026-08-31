package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val preferQuic: Boolean = false,
) {
    private val attemptLanes = Semaphore(maxConcurrentAttempts)

    init {
        require(hedgeDelayMillis >= 0L)
        require(primaryRouteRetryMillis > 0L)
        require(attemptTimeoutMillis > 0L)
        require(maxConcurrentAttempts > 0)
    }

    suspend fun open(
        candidates: List<String>,
        headers: Map<String, String>,
        pageKey: String,
        validate: suspend (SourceResponse) -> OpenedPage,
    ): Winner = open(candidates, headers, pageKey, PageFetchPriority.NORMAL, validate)

    suspend fun open(
        candidates: List<String>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
    ): Winner {
        val ordered = replicas.orderedPrepared(replicas.prepare(candidates))
        require(ordered.isNotEmpty()) { "NTK page has no replica candidates" }
        val preferred = ordered.first()
        if (replicas.isVerified(preferred)) {
            try {
                return race(listOf(preferred), headers, pageKey, priority, validate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (preferredFailure: Throwable) {
                val fallbacks = ordered.drop(1)
                if (fallbacks.isEmpty()) throw preferredFailure
                return try {
                    race(fallbacks, headers, pageKey, priority, validate)
                } catch (fallbackFailure: Throwable) {
                    fallbackFailure.addSuppressed(preferredFailure)
                    throw fallbackFailure
                }
            }
        }
        return raceUnknown(ordered, headers, pageKey, priority, validate)
    }

    private suspend fun raceUnknown(
        ordered: List<NtkReplicaSelector.ReplicaCandidate>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
    ): Winner {
        var previousFailure: Throwable? = null
        for (window in ordered.chunked(candidateWindowSize(priority))) {
            try {
                return race(
                    window,
                    headers,
                    pageKey,
                    priority,
                    validate,
                    retryPrimary = priority == PageFetchPriority.VISIBLE || window.size == 1,
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

    private fun candidateWindowSize(priority: PageFetchPriority): Int = when (priority) {
        PageFetchPriority.VISIBLE -> Int.MAX_VALUE
        PageFetchPriority.NORMAL -> 3
        PageFetchPriority.FORWARD -> 2
        PageFetchPriority.BACKGROUND -> 1
    }

    private suspend fun race(
        ordered: List<NtkReplicaSelector.ReplicaCandidate>,
        headers: Map<String, String>,
        pageKey: String,
        priority: PageFetchPriority,
        validate: suspend (SourceResponse) -> OpenedPage,
        retryPrimary: Boolean = false,
    ): Winner = coroutineScope {
        val outcomes = Channel<Outcome>()
        val jobs = ordered.mapIndexed { index, candidate ->
            launch {
                if (index > 0) delay(hedgeDelayMillis * index)
                attempt(
                    candidate,
                    headers,
                    pageKey,
                    validate,
                    outcomes,
                    priority,
                )
            }
        }.toMutableList()
        if (retryPrimary) jobs += launch {
            delay(primaryRouteRetryMillis)
            attempt(
                ordered.first(),
                headers,
                pageKey,
                validate,
                outcomes,
                priority,
            )
        }
        var lastFailure: Throwable? = null
        repeat(ordered.size + if (retryPrimary) 1 else 0) {
            when (val outcome = outcomes.receive()) {
                is Outcome.Failed -> lastFailure = outcome.failure
                is Outcome.Succeeded -> {
                    jobs.forEach { job -> if (job != outcome.owner) job.cancel() }
                    jobs.joinAll()
                    outcomes.close()
                    return@coroutineScope outcome.winner
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
    ) {
        val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?: error("Replica attempt has no job")
        attemptLanes.withPermit {
            executeAttempt(
                candidate,
                headers,
                pageKey,
                validate,
                outcomes,
                owner,
                priority,
            )
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
    ) {
        val lease = replicas.acquireCandidate(candidate)
        val started = System.nanoTime()
        var response: SourceResponse? = null
        var opened: OpenedPage? = null
        try {
            response = transport.execute(SourceRequest(
                candidate.url,
                headers = headers,
                totalTimeoutMillis = attemptTimeoutMillis,
                preferQuic = preferQuic,
                priority = priority,
            ))
            if (response.statusCode !in 200..299) {
                throw IOException("HTTP ${response.statusCode}")
            }
            opened = validate(response)
            response = null
            outcomes.send(Outcome.Succeeded(
                Winner(opened, lease, started),
                owner,
            ))
            opened = null
        } catch (cancelled: CancellationException) {
            replicas.abandoned(lease)
            throw cancelled
        } catch (failure: Throwable) {
            replicas.failedAndReleased(lease)
            LOGGER.warning(
                "page candidate failed id=$pageKey candidate=${identity(candidate.url)} " +
                    "reason=${failure.message}",
            )
            outcomes.send(Outcome.Failed(failure))
        } finally {
            response?.close()
            opened?.close()
        }
    }

    data class Winner(
        val opened: OpenedPage,
        val lease: NtkReplicaSelector.ReplicaLease,
        val startedAtNanos: Long,
    )

    private sealed interface Outcome {
        data class Succeeded(val winner: Winner, val owner: kotlinx.coroutines.Job) : Outcome
        data class Failed(val failure: Throwable) : Outcome
    }

    private fun identity(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.host.orEmpty()}${uri.path.orEmpty()}"
    }.getOrDefault("invalid-url")

    private companion object {
        val LOGGER: Logger = Logger.getLogger(NtkReplicaRacer::class.java.simpleName)
    }
}
