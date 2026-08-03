package ml.melun.mangaview.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun interface NtkStrictSourceRouteResolver {
    fun resolve(
        manga: Manga,
        manifestSeal: NtkEpisodeManifestSeal,
        pageIndex: Int,
        canonicalAsset: String
    ): ReaderImageCache.NtkResolvedSourceRoute
}

internal val PRODUCTION_NTK_STRICT_SOURCE_ROUTE_RESOLVER = NtkStrictSourceRouteResolver {
        manga, manifestSeal, pageIndex, canonicalAsset ->
    ReaderImageCache.resolveStrictSourceRoute(
        manga,
        manifestSeal,
        pageIndex,
        canonicalAsset
    )
}

internal object NtkStrictSourceActorCloseRearmPolicy {
    fun shouldRearm(
        closeRequested: Boolean,
        closeFinalized: Boolean,
        remainingCallbacks: Int
    ): Boolean {
        require(remainingCallbacks >= 0)
        return closeRequested && !closeFinalized && remainingCallbacks == 0
    }
}

/**
 * Serializes actor callback admission with the final close-barrier snapshot.
 *
 * The actor executor itself cannot provide this boundary: a producer can increment the pending
 * callback count after the actor observed zero but before it publishes the close proof. Closing
 * this gate only when the current callback is the sole remaining callback makes the zero in that
 * proof stable. Any later producer is rejected without mutating the pending count.
 */
internal class NtkStrictSourceActorCallbackGate {
    private val lock = Any()
    private var pendingCallbacks = 0
    private var admissionsClosed = false

    fun admit(submit: () -> Unit): Boolean = synchronized(lock) {
        if (admissionsClosed) return@synchronized false
        pendingCallbacks++
        try {
            submit()
            true
        } catch (failure: Throwable) {
            pendingCallbacks--
            check(pendingCallbacks >= 0)
            throw failure
        }
    }

    fun finish(): Int = synchronized(lock) {
        pendingCallbacks--
        check(pendingCallbacks >= 0)
        pendingCallbacks
    }

    fun remainingExcludingCurrent(currentCallbackDepth: Int): Int = synchronized(lock) {
        val currentAllowance = if (currentCallbackDepth > 0) 1 else 0
        (pendingCallbacks - currentAllowance).coerceAtLeast(0)
    }

    fun closeAdmissionsIfDrained(currentCallbackDepth: Int): Boolean = synchronized(lock) {
        if (admissionsClosed) return@synchronized true
        val currentAllowance = if (currentCallbackDepth > 0) 1 else 0
        if (pendingCallbacks - currentAllowance != 0) return@synchronized false
        admissionsClosed = true
        true
    }
}

private fun prestartedStrictLane(
    name: String,
    androidThreadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
    onThreadStarted: (() -> Unit)? = null,
    prestart: Boolean = true,
): ThreadPoolExecutor {
    val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable ->
            Thread({
                // android.jar throws in local JVM tests. Thread priority is a scheduling hint;
                // a platform/vendor rejection must not kill a prestarted production lane either.
                runCatching { Process.setThreadPriority(androidThreadPriority) }
                onThreadStarted?.invoke()
                runnable.run()
            }, name).apply { priority = Thread.NORM_PRIORITY }
        }
    )
    return try {
        if (prestart) executor.prestartAllCoreThreads()
        executor
    } catch (failure: Throwable) {
        executor.shutdownNow()
        throw failure
    }
}

private data class NtkStrictBootstrapResources(
    val actor: ExecutorService,
    val physicalLanes: Array<ExecutorService>,
    val routePreparationLanes: Array<ExecutorService>,
)

private const val NTK_STRICT_ROUTE_PREPARATION_LANES = 8
/**
 * Blocking image-body readers need enough streams to fill the production 120-operation identity
 * ceiling. The cold cohort leaders remain bounded to one real image body per origin/pool; only
 * after the entry body has crossed the actor boundary may the complete finite forward workset use
 * the whole ring. This keeps first-image priority while removing page-count tail waves.
 */
internal const val NTK_STRICT_PHYSICAL_WORKER_LANES = 120

/** Registers every started executor in a preallocated cleanup holder before starting the next. */
private fun buildStrictBootstrapResources(
    physicalLaneCount: Int,
    routePreparationLaneCount: Int,
    onThreadStarted: () -> Unit,
): NtkStrictBootstrapResources {
    require(physicalLaneCount in 0..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
    require(routePreparationLaneCount in 0..NTK_STRICT_ROUTE_PREPARATION_LANES)
    // If this allocation fails, no executor exists yet. Capacity covers actor + every source lane,
    // so registering a newly started resource cannot itself trigger a backing-array allocation.
    val created = ArrayList<ExecutorService>(
        physicalLaneCount + routePreparationLaneCount + 1
    )
    return try {
        val actor = prestartedStrictLane(
            "ntk-strict-source-actor",
            onThreadStarted = onThreadStarted
        )
        created += actor
        repeat(physicalLaneCount) { lane ->
            created += prestartedStrictLane(
                "ntk-strict-source-lane-$lane",
                androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                onThreadStarted = onThreadStarted,
                // Bootstrap overlaps the document/API request. Materialize the finite worker ring
                // there so the image wave does not pay dozens of JVM thread starts while its H2
                // bodies and JPEG decoders are already competing for the entry deadline.
                prestart = true,
            )
        }
        repeat(routePreparationLaneCount) { lane ->
            created += prestartedStrictLane(
                "ntk-strict-route-prepare-$lane",
                androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                onThreadStarted = onThreadStarted
            )
        }
        NtkStrictBootstrapResources(
            actor,
            Array(physicalLaneCount) { lane -> created[lane + 1] },
            Array(routePreparationLaneCount) { lane ->
                created[physicalLaneCount + lane + 1]
            },
        )
    } catch (failure: Throwable) {
        for (index in created.lastIndex downTo 0) {
            try {
                created[index].shutdownNow()
            } catch (_: Throwable) {
                // Preserve the construction failure after attempting every registered cleanup.
            }
        }
        throw failure
    }
}

internal class NtkStrictSourceExecutionBootstrap(
    /**
     * Numeric manhwa can deliver every click-owned response body through the isolated exact
     * exchange. Starting 128 source workers in parallel with that exchange only steals CPU from
     * WebView/ACK publication. Its worker lanes are therefore materialized at adoption from the
     * actual missing-body count; the actor itself is still click-owned and prestarted.
     */
    private val deferWorkerLanes: Boolean = false,
) : Closeable {
    internal data class Engines(
        val actor: ExecutorService,
        val physicalLanes: Array<ExecutorService>,
        val routePreparationLanes: Array<ExecutorService>,
    )

    private enum class State { READY, ADOPTED, CLOSED }

    private val lock = Any()
    private val startedThreads = AtomicInteger()
    private var state = State.READY
    private val resources = buildStrictBootstrapResources(
        physicalLaneCount = if (deferWorkerLanes) 0 else minOf(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NTK_STRICT_PHYSICAL_WORKER_LANES,
        ),
        routePreparationLaneCount = if (deferWorkerLanes) 0 else
            NTK_STRICT_ROUTE_PREPARATION_LANES,
        onThreadStarted = { startedThreads.incrementAndGet() },
    )
    private val actor = resources.actor
    private var physicalLanes = resources.physicalLanes
    private var routePreparationLanes = resources.routePreparationLanes

    fun adopt(
        requiredPhysicalLanes: Int = minOf(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NTK_STRICT_PHYSICAL_WORKER_LANES,
        ),
        requiredRoutePreparationLanes: Int = NTK_STRICT_ROUTE_PREPARATION_LANES,
    ): Engines =
        synchronized(lock) {
        check(state == State.READY) { "Strict source bootstrap is not single-use" }
        require(requiredPhysicalLanes in 0..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(requiredRoutePreparationLanes in 0..NTK_STRICT_ROUTE_PREPARATION_LANES)
        if (deferWorkerLanes) {
            check(physicalLanes.isEmpty() && routePreparationLanes.isEmpty())
            val createdPhysical = ArrayList<ExecutorService>(requiredPhysicalLanes)
            val createdRoute = ArrayList<ExecutorService>(requiredRoutePreparationLanes)
            try {
                repeat(requiredPhysicalLanes) { lane ->
                    createdPhysical += prestartedStrictLane(
                        "ntk-strict-source-lane-$lane",
                        androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                        onThreadStarted = { startedThreads.incrementAndGet() },
                        prestart = false,
                    )
                }
                repeat(requiredRoutePreparationLanes) { lane ->
                    createdRoute += prestartedStrictLane(
                        "ntk-strict-route-prepare-$lane",
                        androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                        onThreadStarted = { startedThreads.incrementAndGet() },
                    )
                }
                physicalLanes = createdPhysical.toTypedArray()
                routePreparationLanes = createdRoute.toTypedArray()
            } catch (failure: Throwable) {
                createdRoute.asReversed().forEach(ExecutorService::shutdownNow)
                createdPhysical.asReversed().forEach(ExecutorService::shutdownNow)
                state = State.CLOSED
                actor.shutdownNow()
                throw failure
            }
        }
        state = State.ADOPTED
        Engines(actor, physicalLanes, routePreparationLanes)
    }

    fun startedThreadCount(): Int = startedThreads.get()

    fun isAdopted(): Boolean = synchronized(lock) { state == State.ADOPTED }

    override fun close() {
        synchronized(lock) {
            if (state != State.READY) return
            state = State.CLOSED
            shutdownEnginesLocked()
        }
    }

    /** Constructor handoff failed after [adopt]; no session exists to own normal actor teardown. */
    fun abortConstructionFailure() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            shutdownEnginesLocked()
        }
    }

    private fun shutdownEnginesLocked() {
        routePreparationLanes.forEach(ExecutorService::shutdownNow)
        physicalLanes.forEach(ExecutorService::shutdownNow)
        actor.shutdownNow()
    }
}

/** Pure primary-flight ordering rules shared by the actor and focused host tests. */
internal object NtkStrictSourceSchedulerPolicy {
    enum class WorkKind { PRIMARY_FULL_BODY }

    data class Candidate(
        val pageIndex: Int,
        val routeBucket: String,
        val routeKeyHash: String,
        val priority: Int,
        val sourceLane: NtkSourceOperationLane
    ) {
        val urgencyBand: Int
            get() = when {
                sourceLane == NtkSourceOperationLane.STAGE -> 4
                sourceLane == NtkSourceOperationLane.URGENT -> 3
                sourceLane == NtkSourceOperationLane.TARGET -> 2
                else -> 1
            }
    }

    data class PrimarySelection(val candidate: Candidate, val routeBucket: String)

    fun hasRouteCapacity(
        activeRouteCount: Int,
        routeLimit: Int = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS_PER_ROUTE,
    ): Boolean {
        require(activeRouteCount >= 0)
        require(routeLimit in 1..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        return activeRouteCount < routeLimit
    }

    fun selectPrimary(
        candidates: List<Candidate>,
        currentRouteBucket: String?
    ): PrimarySelection? {
        if (candidates.isEmpty()) return null
        val highestBand = candidates.maxOf(Candidate::urgencyBand)
        val highest = candidates.filter { it.urgencyBand == highestBand }
        val affinity = currentRouteBucket?.let { route ->
            highest.filter { it.routeBucket == route }.takeIf(List<Candidate>::isNotEmpty)
        }
        val selected = (affinity ?: highest).minWithOrNull(
            compareByDescending<Candidate> { it.priority }
                .thenBy { it.pageIndex }
                .thenBy { it.routeKeyHash }
                .thenBy { it.routeBucket }
        ) ?: return null
        return PrimarySelection(selected, selected.routeBucket)
    }

    fun orderedBodyEvents(
        existingMetadata: NtkSourceMetadata?,
        descriptor: NtkStrictBodyDescriptor
    ): List<SourceEvent> = buildList {
        if (existingMetadata == null) add(SourceEvent.MetadataReady(descriptor.metadata))
        add(SourceEvent.BodyPublished(descriptor))
    }
}

/** Defines the immutable, forward-only first quarantine wave. */
internal object NtkStrictInitialWavePolicy {
    // Must match CustomHttpClient's established and direct-Wi-Fi webtoon pool counts. A stale
    // value leaves physical pools without cohort leaders and serializes their first real bodies.
    private const val WEBTOON_CONNECTION_SHARDS = 8
    private const val DIRECT_WIFI_WEBTOON_CONNECTION_SHARDS = 16
    private const val MANHWA_CONNECTION_SHARDS = 24
    // Three independent Wi-Fi leaders keep the entry route resilient to a slow/dead CDN pool.
    // A single leader was measured to stall for its full 10-second retry window.
    private const val WEBTOON_WIFI_ANCHOR_GATE_OPERATIONS = 3
    private const val WEBTOON_WIFI_LARGE_ANCHOR_GATE_OPERATIONS = 12
    private const val WEBTOON_WIFI_LARGE_ANCHOR_GATE_EPISODE_PAGES = 140
    private const val WEBTOON_CELLULAR_ANCHOR_GATE_OPERATIONS =
        WEBTOON_CONNECTION_SHARDS * 3
    /**
     * Every unusable manifest replica currently converges on the same healthy webtoon origin.
     * The old header-success ramp could grow to 64 bodies before any image reached EOF, starving
     * the entry stream. Six streams over eight host-local pools are merely the pre-anchor capacity;
     * the separate three-call Wi-Fi gate below protects the initial image. A wider ring is allowed
     * only after that image publishes. The fixed executor stays wide for numeric manhwa, whose
     * transport has different origins.
     */
    private const val WEBTOON_PRE_ANCHOR_STREAMS_PER_CONNECTION_SHARD = 6
    // A compatibility-origin Wi-Fi session can converge the manifest replicas onto a few real CDN
    // hosts. Opening all workers at anchor EOF exhausted server/HTTP2 flow control, produced
    // "closed" page failures, retained hundreds of MiB, and caused scroll jank. A fixed four-call
    // ring is stable, but makes a healthy 124-page/27 MiB episode take 17.6 seconds because its
    // twenty-four already-sharded pools are processed almost serially.
    //
    // Grow only after the entry body has crossed the actor boundary. The immutable initial plan
    // places the next twenty-four pages on distinct host-local pools, so opening one transfer per
    // pool does not add H2 contention. Session-local replica preference is separately gated on
    // explicit immutable misses; a merely fast host can no longer collapse this balanced topology.
    // A failed entry earns no growth, and the carrier transport policy remains untouched.
    private const val WEBTOON_WIFI_POST_ANCHOR_INITIAL_BODY_TRANSFERS = 4
    // Direct Wi-Fi webtoon bodies use host-local H2 pools proven by the cold suite.
    // Twenty-four transfers leave an 89-page episode in four 2-3 second waves even after every
    // call converges on the healthy compatibility origin. Six streams per isolated pool keep the
    // server's supported multiplexing bound while reducing that same workset to two waves.
    // Carrier/SNI and manhwa use their independent policies below.
    private const val WEBTOON_WIFI_POST_ANCHOR_TCP_BODY_TRANSFERS = 48
    private const val WEBTOON_DIRECT_WIFI_MEDIUM_TCP_BODY_TRANSFERS = 60
    private const val WEBTOON_DIRECT_WIFI_LARGE_TCP_BODY_TRANSFERS = 64
    // A 91-page, 27.9 MiB direct-Wi-Fi scene still needed a second 48-call transfer wave and
    // varied between 5.49 s and 6.09 s. Add only twelve followers over the already-isolated
    // 48-pool topology for medium-long scenes; the previously qualified 64-call ring remains
    // reserved for 180+ pages. Carrier/SNI has eight shards and cannot enter either branch.
    private const val WEBTOON_DIRECT_WIFI_MEDIUM_TCP_EPISODE_PAGES = 80
    private const val WEBTOON_DIRECT_WIFI_LARGE_TCP_EPISODE_PAGES = 180
    private const val WEBTOON_WIFI_POST_ANCHOR_QUIC_BODY_TRANSFERS = 24
    private const val WEBTOON_WIFI_LARGE_QUIC_BODY_TRANSFERS = 24
    private const val WEBTOON_WIFI_LARGE_EPISODE_PAGES = 140
    // Preserve the existing carrier transport wave: its independently sharded resilient route
    // needs the wider ring and is intentionally unaffected by this Wi-Fi stabilization.
    private const val WEBTOON_CELLULAR_POST_ANCHOR_BODY_TRANSFERS = 80
    private const val MANHWA_WIFI_QUIC_BODY_TRANSFERS = 60
    private const val MANHWA_WIFI_LARGE_QUIC_BODY_TRANSFERS = 80
    private const val MANHWA_WIFI_LARGE_EPISODE_PAGES = 120
    // The UI contract is an exact four-drawable runway. A fifth offscreen body used to enter the
    // same tiny adjacent wave and divide the just-released connection bandwidth with those four
    // user-visible pages. Keep discovery/authority complete, but admit and transfer exactly what
    // can be attached before the boundary. The full forward set opens only after that episode is
    // the real viewport; carrier/SNI never enters this adjacent Wi-Fi-only cap.
    private const val MANHWA_WIFI_ADJACENT_PREFETCH_BODY_TRANSFERS = 4
    internal const val WIFI_ADJACENT_INITIAL_RUNWAY_BODIES = 4

    /**
     * Carrier transport needs one actual demanded body to establish each finite host/pool cohort.
     * Opening only one body per origin left fifteen of eighteen pools idle until page zero EOF,
     * then made leaders and followers compete for brand-new connections. Wi-Fi retains its
     * measured three-origin first-frame gate.
     */
    fun webtoonPreAnchorGateOperations(
        cohortCount: Int,
        cellularResilientTransport: Boolean,
        episodePageCount: Int = 0,
        directWifiTransport: Boolean = false,
        adjacentPrefetch: Boolean = false,
    ): Int {
        require(cohortCount >= 0)
        require(episodePageCount >= 0)
        val limit = if (cellularResilientTransport) {
            WEBTOON_CELLULAR_ANCHOR_GATE_OPERATIONS
        } else if (directWifiTransport && adjacentPrefetch) {
            // Adjacent admission is already hard-capped to the four user-visible runway bodies.
            // Starting only three of them made page four wait for an earlier EOF, even though the
            // predecessor was fully drawable and the fourth isolated connection cohort was idle.
            // Match the physical opening wave to that existing four-body UI contract. Ordinary
            // Wi-Fi/current episodes, carrier/SNI, later pages, and previous episodes never enter
            // this branch.
            WIFI_ADJACENT_INITIAL_RUNWAY_BODIES
        } else if (
            directWifiTransport &&
            episodePageCount >= WEBTOON_WIFI_LARGE_ANCHOR_GATE_EPISODE_PAGES
        ) {
            // A large Wi-Fi scene cannot leave already-isolated connection cohorts idle
            // behind page zero: one rare three-second recovery then starts too late to satisfy
            // the complete-scene deadline. Open half of the finite 24-cohort ring; this gives the
            // terminal body a stable margin without exposing page zero to the full bulk wave.
            // Carrier keeps its existing wider resilient wave, and ordinary Wi-Fi episodes retain
            // the measured three-origin entry gate.
            WEBTOON_WIFI_LARGE_ANCHOR_GATE_OPERATIONS
        } else {
            WEBTOON_WIFI_ANCHOR_GATE_OPERATIONS
        }
        return minOf(cohortCount, limit)
    }

    fun usefulPhysicalLaneCount(
        episodePath: String,
        physicalLaneCount: Int,
        anchorBodyPublished: Boolean,
        manhwaTransferLimit: Int = NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
        cellularResilientTransport: Boolean = false,
        webtoonPublishedBodyCount: Int = 0,
        wifiQuicBulkTransport: Boolean = false,
        episodePageCount: Int = 0,
        adjacentPrefetch: Boolean = false,
        webtoonConnectionShardCount: Int = WEBTOON_CONNECTION_SHARDS,
    ): Int {
        require(episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
        require(physicalLaneCount >= 0)
        require(manhwaTransferLimit in 1..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(webtoonPublishedBodyCount >= 0)
        require(episodePageCount >= 0)
        require(webtoonConnectionShardCount > 0)
        return if (episodePath.startsWith("/webtoon/")) {
            // Until the entry body reaches EOF its pool is deliberately single-stream. Counting
            // it as a full six-stream pool pushes an avoidable extra body onto the other pools.
            // Once the entry body is safe, every finite pool can carry its measured useful share.
            val usefulWebtoonLanes = if (anchorBodyPublished) {
                if (cellularResilientTransport) {
                    WEBTOON_CELLULAR_POST_ANCHOR_BODY_TRANSFERS
                } else {
                    webtoonWifiPostAnchorBodyTransfers(
                        webtoonPublishedBodyCount,
                        wifiQuicBulkTransport,
                        episodePageCount,
                        webtoonConnectionShardCount,
                    )
                }
            } else {
                1 + (webtoonConnectionShardCount - 1) *
                    WEBTOON_PRE_ANCHOR_STREAMS_PER_CONNECTION_SHARD
            }
            minOf(
                physicalLaneCount,
                usefulWebtoonLanes,
            )
        } else {
            // Numeric manhwa already proved its cold throughput optimum at twenty-four isolated
            // H2 pools (NtkClickOwnedManhwaWavePolicy). The click-owned entry stream obeys that
            // bound, but the adopted exact-source continuation previously expanded to every one
            // of its 120 worker threads after the first frame. That made early forward pages share
            // flow control and emulator/network bandwidth with the entire volume: a fast fling
            // could reach pages 11-21 while their bodies were still stalled behind later pages.
            //
            // Keep the complete episode admitted and refill immediately at EOF, but cap physical
            // transfers to the finite production transfer limit over those same connection
            // pools. No page is dropped or delayed by a viewport event; this only turns the full
            // workset into a bounded forward rolling ring, preserving both all-image completion
            // and a usable downward runway.
            val bulkTransferLimit =
                if (wifiQuicBulkTransport && !cellularResilientTransport) {
                    maxOf(
                        manhwaTransferLimit,
                        if (episodePageCount >= MANHWA_WIFI_LARGE_EPISODE_PAGES) {
                            MANHWA_WIFI_LARGE_QUIC_BODY_TRANSFERS
                        } else {
                            MANHWA_WIFI_QUIC_BODY_TRANSFERS
                        },
                    )
                } else {
                    manhwaTransferLimit
                }
            minOf(
                physicalLaneCount,
                bulkTransferLimit,
                if (adjacentPrefetch && !cellularResilientTransport) {
                    MANHWA_WIFI_ADJACENT_PREFETCH_BODY_TRANSFERS
                } else {
                    Int.MAX_VALUE
                },
            )
        }
    }

    /**
     * Entry-completion-clocked Wi-Fi release. Before page zero EOF the stable four-call gate remains
     * in force. TCP first opens every immutable cold cohort. An 80+ page direct-Wi-Fi scene may
     * then use twelve bounded followers, while 180+ pages retain the qualified 64-call ring.
     * When the exact
     * HTTP/3 primary is actually available, keep the same twenty-four-transfer ceiling. A larger
     * cold wave can over-share each QUIC session and turn useful in-flight progress into a
     * synchronized timeout/fallback storm. An unavailable HTTP/3 engine keeps the TCP limit, and
     * the carrier ring remains independently fixed at eighty.
     */
    fun webtoonWifiPostAnchorBodyTransfers(
        publishedBodyCount: Int,
        wifiQuicBulkTransport: Boolean = false,
        episodePageCount: Int = 0,
        webtoonConnectionShardCount: Int = WEBTOON_CONNECTION_SHARDS,
    ): Int {
        require(publishedBodyCount >= 0)
        require(episodePageCount >= 0)
        require(webtoonConnectionShardCount > 0)
        return if (publishedBodyCount > 0) {
            if (wifiQuicBulkTransport) {
                if (episodePageCount >= WEBTOON_WIFI_LARGE_EPISODE_PAGES) {
                    WEBTOON_WIFI_LARGE_QUIC_BODY_TRANSFERS
                } else {
                    WEBTOON_WIFI_POST_ANCHOR_QUIC_BODY_TRANSFERS
                }
            } else {
                if (
                    webtoonConnectionShardCount >= DIRECT_WIFI_WEBTOON_CONNECTION_SHARDS &&
                    episodePageCount >= WEBTOON_DIRECT_WIFI_LARGE_TCP_EPISODE_PAGES
                ) {
                    WEBTOON_DIRECT_WIFI_LARGE_TCP_BODY_TRANSFERS
                } else if (
                    webtoonConnectionShardCount >= DIRECT_WIFI_WEBTOON_CONNECTION_SHARDS &&
                    episodePageCount >= WEBTOON_DIRECT_WIFI_MEDIUM_TCP_EPISODE_PAGES
                ) {
                    WEBTOON_DIRECT_WIFI_MEDIUM_TCP_BODY_TRANSFERS
                } else {
                    WEBTOON_WIFI_POST_ANCHOR_TCP_BODY_TRANSFERS
                }
            }
        } else {
            WEBTOON_WIFI_POST_ANCHOR_INITIAL_BODY_TRANSFERS
        }
    }

    fun admittedPageIndexes(
        pageCount: Int,
        initialPageIndex: Int,
        rollingAdmission: Boolean,
        alreadyPublishedPageIndexes: Set<Int> = emptySet(),
        adjacentPrefetch: Boolean = false,
    ): Set<Int> {
        require(pageCount > 0)
        require(initialPageIndex in 0 until pageCount)
        require(alreadyPublishedPageIndexes.all { it in 0 until pageCount })
        require(alreadyPublishedPageIndexes.size <= pageCount)
        if (!rollingAdmission) return (0 until pageCount).toSet() - alreadyPublishedPageIndexes
        if (adjacentPrefetch) {
            val endExclusive = minOf(
                pageCount,
                initialPageIndex + WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
            )
            return (initialPageIndex until endExclusive).asSequence()
                .filter { it !in alreadyPublishedPageIndexes }
                .toCollection(LinkedHashSet())
        }
        // Cold qualification requires every later image to be ready inside one entry deadline.
        // Admit the complete forward workset immediately; the physical executor still enforces
        // the production bounded-call limit and never downloads before the viewer is opened.
        return (initialPageIndex until pageCount).asSequence()
            .filter { it !in alreadyPublishedPageIndexes }
            .toCollection(LinkedHashSet())
    }

    /**
     * Route construction allocates request/header/digest state even before a body Call is admitted.
     * Keep that hidden work under the same direct-Wi-Fi adjacent runway gate as physical bodies:
     * before viewport release only the four attachable pages may prepare, and pages behind the
     * forward anchor are never prepared. Ordinary/current Wi-Fi and carrier/SNI sessions retain
     * their complete existing route-preparation policy.
     */
    fun isRoutePreparationAdmitted(
        pageIndex: Int,
        pageCount: Int,
        initialPageIndex: Int,
        adjacentPrefetch: Boolean,
        adjacentPrefetchReleased: Boolean,
        forwardResume: Boolean = false,
    ): Boolean {
        require(pageCount > 0)
        require(pageIndex in 0 until pageCount)
        require(initialPageIndex in 0 until pageCount)
        if (forwardResume && pageIndex < initialPageIndex) return false
        if (!adjacentPrefetch) return true
        if (pageIndex < initialPageIndex) return false
        return adjacentPrefetchReleased || pageIndex < minOf(
            pageCount,
            initialPageIndex + WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
        )
    }

    fun submissionTarget(admittedPageCount: Int): Int {
        require(admittedPageCount >= 0)
        return minOf(NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS, admittedPageCount)
    }

    fun routeBoundSubmissionTarget(
        admittedRouteBuckets: List<String>,
        routeLimit: Int,
    ): Int {
        if (admittedRouteBuckets.isEmpty()) return 0
        require(admittedRouteBuckets.none(String::isBlank))
        require(routeLimit in 1..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        val routeCapacity = admittedRouteBuckets.groupingBy { it }.eachCount().values
            .sumOf { routePageCount -> minOf(routePageCount, routeLimit) }
        return minOf(NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS, routeCapacity)
    }

    /**
     * Selects the first demanded image for every real OkHttp origin/pool pair. These are image
     * bodies, not probes: once their response headers establish the cold H2 pools, the remaining
     * demanded image bodies can be admitted without racing 100+ independent TLS handshakes.
     */
    fun coldConnectionCohortLeaders(
        episodePath: String,
        admittedPageIndexes: Set<Int>,
        maximumLeaders: Int = Int.MAX_VALUE,
        webtoonShardCount: Int = WEBTOON_CONNECTION_SHARDS,
        routeBucketForPage: (Int) -> String,
    ): List<Int> {
        require(episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
        require(admittedPageIndexes.all { it >= 0 })
        require(maximumLeaders >= 0)
        require(webtoonShardCount > 0)
        val seen = HashSet<String>()
        val leaders = ArrayList<Int>()
        for (pageIndex in admittedPageIndexes.sorted()) {
            if (leaders.size >= maximumLeaders) break
            val routeBucket = routeBucketForPage(pageIndex)
            require(routeBucket.isNotBlank())
            val cohort = coldConnectionCohortKey(
                episodePath,
                pageIndex,
                routeBucket,
                webtoonShardCount,
            )
            if (seen.add(cohort)) leaders += pageIndex
        }
        return leaders
    }

    fun coldConnectionCohortKey(
        episodePath: String,
        pageIndex: Int,
        routeBucket: String,
        webtoonShardCount: Int = WEBTOON_CONNECTION_SHARDS,
    ): String {
        require(episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
        require(routeBucket.isNotBlank())
        require(webtoonShardCount > 0)
        val shardCount = if (episodePath.startsWith("/manhwa/")) {
            MANHWA_CONNECTION_SHARDS
        } else {
            webtoonShardCount
        }
        val shard = if (episodePath.startsWith("/webtoon/")) {
            webtoonHostLocalShardIndex(pageIndex, shardCount)
        } else {
            exactImageShardIndex(pageIndex, shardCount)
        }
        return "$routeBucket#$shard"
    }

    fun webtoonConnectionShardCount(directWifiTransport: Boolean): Int =
        if (directWifiTransport) {
            DIRECT_WIFI_WEBTOON_CONNECTION_SHARDS
        } else {
            WEBTOON_CONNECTION_SHARDS
        }

    /**
     * Keep one real body per cold pool initially. As soon as a pool returns real response headers,
     * give that proven pool its fair share of the fixed physical ring while retaining one leader
     * for every pool still negotiating. The earlier `+2` ramp left 44 of 60 workers idle whenever
     * one unrelated TLS/H2 cohort was slow; production cold runs then varied from 3.4 to 5.1 s.
     * This policy opens no extra cold pool and never exceeds the same 60-operation ceiling.
     */
    fun progressiveLaneTarget(
        physicalLaneCount: Int,
        cohortCount: Int,
        settledCohortCount: Int,
    ): Int {
        require(physicalLaneCount >= 0)
        require(cohortCount in 0..physicalLaneCount)
        require(settledCohortCount in 0..cohortCount)
        if (cohortCount == 0) return physicalLaneCount
        if (settledCohortCount == cohortCount) return physicalLaneCount
        val fairSettledPoolShare = maxOf(1, physicalLaneCount / cohortCount)
        val unsettledLeaderCount = cohortCount - settledCohortCount
        return minOf(
            physicalLaneCount,
            unsettledLeaderCount + settledCohortCount * fairSettledPoolShare,
        )
    }

    /**
     * The entry body is the only publication with strict priority over the complete episode.
     * Before it arrives, retain the measured cold-pool ramp. Afterwards every connection already
     * has either an in-flight leader or a proven body, so all physical lanes can be filled without
     * delaying the first draw.
     */
    fun forwardLaneTarget(
        physicalLaneCount: Int,
        cohortCount: Int,
        settledCohortCount: Int,
        anchorBodyPublished: Boolean,
    ): Int = if (anchorBodyPublished) {
        require(physicalLaneCount >= 0)
        physicalLaneCount
    } else {
        progressiveLaneTarget(physicalLaneCount, cohortCount, settledCohortCount)
    }

    /**
     * Once the entry body has arrived, every cold cohort already owns its unique in-flight leader
     * from the sealed opening wave. Followers can therefore join those same finite pool identities
     * immediately. Keeping this second gate closed made the nominal full-lane release wait another
     * 350 ms (or the next completion callback) and dominated large, tiny-page episodes.
     */
    fun cohortFollowerEligible(
        cohortsOpen: Boolean,
        isLeader: Boolean,
        cohortSettled: Boolean,
        anchorBodyPublished: Boolean,
        firstSettledAtMs: Long,
        nowMs: Long,
        unsettledFollowerGraceMs: Long,
    ): Boolean {
        require(firstSettledAtMs >= 0L)
        require(nowMs >= 0L)
        require(unsettledFollowerGraceMs >= 0L)
        if (cohortsOpen || isLeader || cohortSettled || anchorBodyPublished) return true
        return firstSettledAtMs > 0L &&
            nowMs - firstSettledAtMs >= unsettledFollowerGraceMs
    }

    /** Must remain bit-identical to CustomHttpClient.ntkExactImageShardIndex. */
    internal fun exactImageShardIndex(pageIndex: Int, shardCount: Int): Int {
        require(pageIndex >= 0)
        require(shardCount > 0)
        var mixed = pageIndex
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7feb352d
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846ca68b.toInt()
        mixed = mixed xor (mixed ushr 16)
        return Math.floorMod(mixed, shardCount)
    }

    /**
     * Must remain bit-identical to CustomHttpClient.ntkWebtoonExactImageShardIndex.
     *
     * The manifest keeps pages zero and one on the same entry origin, then resumes the ordinary
     * three-origin stripe at page two. Give those two visible pages distinct host-local pools so
     * both are members of the three-call anchor wave. The remaining ordinals preserve a perfectly
     * balanced six-pool sequence for each actual origin.
     */
    internal fun webtoonHostLocalShardIndex(pageIndex: Int, shardCount: Int): Int {
        require(pageIndex >= 0)
        require(shardCount > 0)
        val hostLocalOrdinal = when {
            pageIndex <= 1 -> pageIndex
            pageIndex % 3 == 0 -> pageIndex / 3 + 1
            pageIndex % 3 == 1 -> pageIndex / 3 - 1
            else -> pageIndex / 3
        }
        return hostLocalOrdinal % shardCount
    }

}

/**
 * Actor-confined health gate for the current direct-Wi-Fi webtoon body wave.
 *
 * The exact HTTP/3 pool owns three hosts with three host-local session stripes. Pages two and
 * later therefore map to one real multiplexed session by `pageIndex % 9`. A global completion
 * count can be dominated by one healthy connection, so every promotion requires fresh, balanced
 * EOF evidence from all nine sessions. Any retry, physical failure, or
 * body that reaches the 3.0 second guard permanently reduces future admission to two streams per
 * real session (18 calls). The owner never cancels an active physical call, so the narrower target
 * takes effect only as completed calls naturally release lanes.
 */
internal class NtkWifiWebtoonAdaptiveLaneState(
    private val sessionSlotCount: Int = SESSION_SLOT_COUNT,
    private val fastCompletionLimitMs: Long = FAST_COMPLETION_LIMIT_MS,
) {
    data class Transition(
        val oldTarget: Int,
        val newTarget: Int,
        val frozen: Boolean,
        val reason: String,
        val pageIndex: Int,
        val sessionSlot: Int,
        val elapsedMs: Long,
        val fastSamples: List<Int>,
    )

    companion object {
        const val INITIAL_TARGET = 24
        const val UNHEALTHY_TARGET = 18
        const val SECOND_TARGET = 36
        const val THIRD_TARGET = 48
        const val SESSION_SLOT_COUNT = 9
        const val FAST_COMPLETION_LIMIT_MS = 3_000L

        fun isEligible(
            episodePath: String,
            wifiQuicBulkTransport: Boolean,
            cellularResilientTransport: Boolean,
            adjacentPrefetch: Boolean,
            currentForegroundEpisode: Boolean,
        ): Boolean = episodePath.startsWith("/webtoon/") &&
            wifiQuicBulkTransport &&
            !cellularResilientTransport &&
            !adjacentPrefetch &&
            currentForegroundEpisode

        fun sessionSlot(pageIndex: Int): Int {
            require(pageIndex >= 2)
            return Math.floorMod(pageIndex, SESSION_SLOT_COUNT)
        }
    }

    private val fastSamples = IntArray(sessionSlotCount)
    private var stageStartedAtNanos = 0L

    var target: Int = INITIAL_TARGET
        private set
    var frozen: Boolean = false
        private set

    init {
        require(sessionSlotCount > 0)
        require(fastCompletionLimitMs > 0L)
    }

    fun recordSuccess(
        pageIndex: Int,
        attemptOrdinal: Int,
        physicalStartedAtNanos: Long,
        physicalCompletedAtNanos: Long,
    ): Transition? {
        if (frozen || pageIndex < 2) return null
        if (attemptOrdinal != 1) {
            return freeze(
                reason = "retry_success",
                pageIndex = pageIndex,
                elapsedMs = elapsedMs(physicalStartedAtNanos, physicalCompletedAtNanos),
            )
        }
        if (physicalStartedAtNanos <= 0L ||
            physicalCompletedAtNanos < physicalStartedAtNanos
        ) {
            return freeze("invalid_timing", pageIndex, -1L)
        }
        val elapsedNanos = physicalCompletedAtNanos - physicalStartedAtNanos
        val elapsedMs = elapsedNanos / 1_000_000L
        if (elapsedNanos > fastCompletionLimitMs * 1_000_000L) {
            return freeze("slow_success", pageIndex, elapsedMs)
        }
        // A completion from the previous, narrower wave can still arrive after promotion. It is
        // useful for rendering but cannot prove that the newly widened wave is healthy.
        if (physicalStartedAtNanos < stageStartedAtNanos) return null

        val slot = Math.floorMod(pageIndex, sessionSlotCount)
        fastSamples[slot]++
        val requiredSamples = 1
        if (fastSamples.any { it < requiredSamples }) return null
        val nextTarget = when (target) {
            INITIAL_TARGET -> SECOND_TARGET
            SECOND_TARGET -> THIRD_TARGET
            else -> return null
        }
        val evidence = fastSamples.toList()
        val oldTarget = target
        target = nextTarget
        fastSamples.fill(0)
        stageStartedAtNanos = physicalCompletedAtNanos
        return Transition(
            oldTarget = oldTarget,
            newTarget = nextTarget,
            frozen = false,
            reason = "balanced_fast_eof",
            pageIndex = pageIndex,
            sessionSlot = slot,
            elapsedMs = elapsedMs,
            fastSamples = evidence,
        )
    }

    fun recordFailure(pageIndex: Int, attemptOrdinal: Int): Transition? {
        if (frozen || pageIndex < 2) return null
        return freeze(
            reason = if (attemptOrdinal > 1) "retry_failure" else "physical_failure",
            pageIndex = pageIndex,
            elapsedMs = -1L,
        )
    }

    private fun freeze(reason: String, pageIndex: Int, elapsedMs: Long): Transition {
        val oldTarget = target
        target = UNHEALTHY_TARGET
        frozen = true
        return Transition(
            oldTarget = oldTarget,
            newTarget = UNHEALTHY_TARGET,
            frozen = true,
            reason = reason,
            pageIndex = pageIndex,
            sessionSlot = Math.floorMod(pageIndex, sessionSlotCount),
            elapsedMs = elapsedMs,
            fastSamples = fastSamples.toList(),
        )
    }

    private fun elapsedMs(startedAtNanos: Long, completedAtNanos: Long): Long =
        if (startedAtNanos > 0L && completedAtNanos >= startedAtNanos) {
            (completedAtNanos - startedAtNanos) / 1_000_000L
        } else {
            -1L
        }
}

/** Actor-confined owner for cache pins retained by accepted exact body descriptors. */
internal class NtkStrictPublishedBodyPinLifecycle {
    private val pins = LinkedHashMap<Int, AutoCloseable>()

    fun retain(pageIndex: Int, pin: AutoCloseable) {
        require(pageIndex >= 0)
        check(pageIndex !in pins) { "Exact body page already has a cache pin" }
        pins[pageIndex] = pin
    }

    fun retainedCount(): Int = pins.size

    fun releaseAtFinalRetirement(activeBodyLeaseCount: Int): Int {
        check(activeBodyLeaseCount == 0) {
            "Accepted body cache pins cannot retire while body leases are active"
        }
        val retained = pins.values.toList()
        pins.clear()
        var firstFailure: Throwable? = null
        retained.forEach { pin ->
            try {
                pin.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
        return retained.size
    }
}

/**
 * A single two-phase source owner. The same actor, bounded physical lanes, page ledger, Call and
 * quarantine file survive PLAN_RESERVED -> exact ownership. Pre-exact work may only write
 * quarantined bytes and evidence; exact events and leases are gated until ownership is installed.
 */
internal class NtkStrictSourceSession(
    context: Context,
    private val manga: Manga,
    private val planBinding: NtkQuarantinePlanBinding,
    private val initialPageIndex: Int,
    executionBootstrap: NtkStrictSourceExecutionBootstrap,
    private val onQuarantineCloseBarrier: (NtkQuarantineCloseBarrierProof) -> Unit,
    private val onExactCloseBarrier: (NtkSourceCloseBarrierProof) -> Unit,
    private val onTerminalFailure: (Throwable) -> Unit,
    /**
     * Rolling-reader mode admits only HARD/SOFT pages. BACKGROUND remains ordered metadata in the
     * immutable manifest but cannot start a physical body Call until a later viewport epoch.
     */
    private val rollingAdmission: Boolean = false,
    private val initialExactBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody> = emptyMap(),
    private val streamedExactBodies: NtkClickOwnedExactBodyStream? = null,
    private val viewerImageApiBacked: Boolean = false,
    private val cellularResilientTransport: Boolean = false,
    private val directWifiTransport: Boolean = false,
    private val wifiQuicBulkTransport: Boolean = false,
    private val currentForegroundViewerGeneration: Long = 0L,
    private val adjacentPrefetch: Boolean = false,
) : Closeable {
    private enum class WorkMode { QUARANTINE, EXACT }

    private sealed interface SessionPhase {
        data object New : SessionPhase
        data class Quarantining(val startedAtMs: Long) : SessionPhase
        data class PromotionPreparing(val token: NtkPromotionToken) : SessionPhase
        data class PromotionPrepared(
            val token: NtkPromotionToken,
            val snapshot: NtkPromotionSnapshot
        ) : SessionPhase
        data class ExactInstalledGateClosed(
            val token: NtkPromotionToken,
            val snapshot: NtkPromotionSnapshot,
            val manifest: NtkAuthoritativeManifest,
            val owner: NtkStrictSourceOwnershipRegistry.Owner
        ) : SessionPhase
        data class ExactOpen(
            val manifest: NtkAuthoritativeManifest,
            val owner: NtkStrictSourceOwnershipRegistry.Owner
        ) : SessionPhase
        data class Closing(
            val exactIdentity: ExactIdentity?,
            val cause: Throwable?
        ) : SessionPhase
        data object Closed : SessionPhase
    }

    private data class ExactIdentity(
        val token: NtkPromotionToken,
        val manifest: NtkAuthoritativeManifest,
        val owner: NtkStrictSourceOwnershipRegistry.Owner
    )

    private sealed interface PhysicalResult {
        data class Quarantined(
            val body: NtkQuarantinedBody,
            val tempLease: ReaderImageCache.NtkQuarantineFileLease
        ) : PhysicalResult

        data class ResidentAdopted(
            val body: NtkQuarantinedBody,
            val tempLease: ReaderImageCache.NtkQuarantineFileLease,
            val published: ReaderImageCache.NtkStrictPublishedBody
        ) : PhysicalResult

        data class Exact(
            val body: ReaderImageCache.NtkStrictPublishedBody
        ) : PhysicalResult
    }

    private data class AdoptionResult(
        val published: ReaderImageCache.NtkStrictPublishedBody,
        val elapsedMs: Long,
    )

    private data class SessionPublishedView(
        val exactSealAtMs: Long,
        val exactOpen: Boolean,
        val exactManifestDigest: String,
        val debug: NtkQuarantineDebugSnapshot
    ) {
        companion object {
            fun initial() = SessionPublishedView(
                0L,
                false,
                "",
                NtkQuarantineDebugSnapshot(
                    NtkQuarantineState.NONE,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                )
            )
        }
    }

    private data class PageState(
        val pageIndex: Int,
        val canonicalAsset: String,
        val routeBucketHint: String,
        var quarantineState: NtkQuarantinePageState = NtkQuarantinePageState.QUEUED,
        var primaryStarted: Boolean = false,
        var activeWork: PrimaryWork? = null,
        var quarantineMetadata: NtkQuarantineMetadataEvidence? = null,
        var quarantinedBody: NtkQuarantinedBody? = null,
        var tempLease: ReaderImageCache.NtkQuarantineFileLease? = null,
        var adoptedExactContext: ReaderImageCache.NtkStrictCallContext? = null,
        var seededExactBody: ReaderImageCache.NtkStrictPublishedBody? = null,
        var streamedExactBodyPending: Boolean = false,
        var publishedBody: ReaderImageCache.NtkStrictPublishedBody? = null,
        var metadata: NtkSourceMetadata? = null,
        var metadataEvent: SourceEvent.MetadataReady? = null,
        var bodyDescriptor: NtkStrictBodyDescriptor? = null,
        var bodyEvent: SourceEvent.BodyPublished? = null,
        var terminalEvent: SourceEvent.TerminalFailure? = null,
        var physicalAttemptOrdinal: Int = 0,
        var physicalRecoveryCycle: Int = 0,
        var physicalRetryNotBeforeMs: Long = 0L,
        var physicalRetryScheduled: Boolean = false,
        var physicalStartedAtNanos: Long = 0L,
        var physicalCompletedAtNanos: Long = 0L,
        var physicalHost: String = "",
    )

    private data class PrimaryWork(
        val workId: Long,
        val operationId: Long,
        val laneIndex: Int,
        val pageIndex: Int,
        val demandEpoch: Long,
        val primaryQueueDepth: Int,
        val launchedPreGeometry: Boolean,
        val mode: WorkMode,
        val cancellation: ReaderImageCache.Cancellation,
        val attemptOrdinal: Int,
        val quarantineLease: NtkQuarantineSourceOwnershipRegistry.OperationLease? = null,
        var exactContext: ReaderImageCache.NtkStrictCallContext? = null,
        val resolvedRoute: ReaderImageCache.NtkResolvedSourceRoute? = null,
        @Volatile var physicalStartedAtNanos: Long = 0L,
        @Volatile var physicalCompletedAtNanos: Long = 0L,
        @Volatile var physicalHost: String = "",
    )

    private data class PhysicalCompletion(
        val work: PrimaryWork,
        val result: Result<PhysicalResult>,
    )

    /**
     * Immutable CPU-only request material. It is derived only after the viewer's post-click
     * document/API plan exists and cannot create a Call or touch the network.
     */
    private data class QuarantineRoutePreparation(
        val route: ReaderImageCache.NtkResolvedSourceRoute,
        val canonicalAssetDigest: String,
        val effectiveRequestDigest: String,
    )

    private data class SourceDemandDelivery(
        val episode: NtkEpisodeToken,
        val candidate: NtkSourceDemandSnapshot
    )

    private val appContext = context.applicationContext
    private val retryHandler = Handler(Looper.getMainLooper())
    private val sessionStartAtMs = SystemClock.elapsedRealtime()
    private val candidateSeal = NtkEpisodeManifestSeal.create(
        planBinding.episodePath,
        planBinding.discoveryGeneration,
        planBinding.normalizedOrderedCanonicalAssets
    )
    private val streamedExactBodyFutures = streamedExactBodies?.bodyFutures.orEmpty()
    private val externallyOwnedPageIndexes = streamedExactBodyFutures.keys
    private val bulkSourcePhysicalAdmissionReady =
        streamedExactBodies?.bulkSourcePhysicalAdmissionReady
            ?: CompletableFuture.completedFuture(Unit)
    private val missingInitialBodyCount =
        planBinding.normalizedOrderedCanonicalAssets.size - initialExactBodies.size -
            externallyOwnedPageIndexes.size
    private val requiredFallbackBodyLanes = if (streamedExactBodyFutures.isNotEmpty()) {
        minOf(
            planBinding.normalizedOrderedCanonicalAssets.size,
            maxOf(missingInitialBodyCount, STREAMED_EXACT_FALLBACK_LANES),
        )
    } else {
        missingInitialBodyCount
    }
    private val executionEngines = executionBootstrap.adopt(
        requiredPhysicalLanes = minOf(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NTK_STRICT_PHYSICAL_WORKER_LANES,
            requiredFallbackBodyLanes,
        ),
        requiredRoutePreparationLanes = if (requiredFallbackBodyLanes == 0) 0 else minOf(
            NTK_STRICT_ROUTE_PREPARATION_LANES,
            requiredFallbackBodyLanes,
        ),
    )
    private val actor = executionEngines.actor
    private val physicalLanes = executionEngines.physicalLanes
    private val routePreparationLanes = executionEngines.routePreparationLanes
    private val bodyLeaseAdmissionLock = Any()
    private val closeRequested = AtomicBoolean(false)
    private val closeFinalized = AtomicBoolean(false)
    private val physicalInFlightCount = AtomicInteger()
    private val activeBodyLeaseCount = AtomicInteger()
    private val temporaryFileLeaseCount = AtomicInteger()
    private val activeAdoptionTaskCount = AtomicInteger()
    private val actorCallbackGate = NtkStrictSourceActorCallbackGate()
    private val physicalCompletions = ConcurrentLinkedQueue<PhysicalCompletion>()
    private val physicalCompletionDrainScheduled = AtomicBoolean(false)
    private val listenerSequence = AtomicLong(1L)
    private val workSequence = AtomicLong(1L)
    private val bodyDescriptorSequence = AtomicLong(1L)
    private val bodyLeaseSequence = AtomicLong(1L)
    private val sourceLogSequence = AtomicLong(1L)
    private val sourceTelemetry = NtkAsyncTelemetry(capacity = 256)
    private val publishedBodyPins = NtkStrictPublishedBodyPinLifecycle()
    private val actorThread = AtomicReference<Thread>()
    private val publishedView = AtomicReference(SessionPublishedView.initial())
    private val residentAdoptionManifest = AtomicReference<NtkAuthoritativeManifest?>()
    private val residentBodyListeners = ConcurrentHashMap<Long, NtkStrictResidentBodyListener>()
    private val residentRenderDescriptors = ConcurrentHashMap<Int, NtkStrictBodyDescriptor>()
    private val pages = planBinding.normalizedOrderedCanonicalAssets
        .mapIndexed { index, asset ->
            PageState(
                index,
                asset,
                strictRouteBucketHint(asset),
                seededExactBody = initialExactBodies[index],
                streamedExactBodyPending = index in externallyOwnedPageIndexes,
            )
        }
        .toTypedArray()
    private val manhwaWaveRecoveryState =
        if (planBinding.episodePath.startsWith("/manhwa/")) {
            streamedExactBodies?.manhwaWaveRecoveryState
                ?: NtkManhwaWaveRecoveryState(
                    pages.size,
                    SystemClock.elapsedRealtimeNanos(),
                )
        } else {
            null
        }
    private val activeWorks = arrayOfNulls<PrimaryWork>(physicalLanes.size)
    private val manhwaPhysicalTransferLimit = if (viewerImageApiBacked) {
        VIEWER_IMAGE_API_MANHWA_BODY_TRANSFERS
    } else {
        NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS
    }
    private val webtoonConnectionShardCount =
        NtkStrictInitialWavePolicy.webtoonConnectionShardCount(directWifiTransport)
    /** Disk-backed adoption still owns its lane; resident bodies retire inline at actor commit. */
    private val adoptionInFlightByLane = BooleanArray(physicalLanes.size)
    private val laneRouteAffinity = arrayOfNulls<String>(physicalLanes.size)
    private val listeners = LinkedHashMap<Long, NtkSourceEventListener>()
    private val eventLedger = ArrayList<SourceEvent>()
    private val preparationDrainCompletions = ArrayList<(NtkSourceDrainProof) -> Unit>()
    private var boundEpisode: NtkEpisodeToken? = null
    private var preGeometryPlan = NtkPreGeometrySourcePlanner.create(initialPageIndex, pages.size)
    private var sourceDemand: NtkSourceDemandSnapshot? = null
    private var rollingAdmittedPages: Set<Int> =
        NtkStrictInitialWavePolicy.admittedPageIndexes(
            pages.size,
            initialPageIndex,
            rollingAdmission,
            initialExactBodies.keys + externallyOwnedPageIndexes,
            adjacentPrefetch = adjacentPrefetch,
        )
    private var adjacentPrefetchReleased = false
    private var adjacentPredecessorCompleted = false
    private var adjacentViewportActivated = false
    private val coldConnectionCohortLeaders =
        NtkStrictInitialWavePolicy.coldConnectionCohortLeaders(
            planBinding.episodePath,
            rollingAdmittedPages,
            maximumLeaders = if (
                directWifiTransport && planBinding.episodePath.startsWith("/webtoon/")
            ) {
                activeWorks.size
            } else {
                NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                    planBinding.episodePath,
                    activeWorks.size,
                    anchorBodyPublished = false,
                    manhwaTransferLimit = manhwaPhysicalTransferLimit,
                    cellularResilientTransport = cellularResilientTransport,
                    adjacentPrefetch = adjacentPrefetch,
                )
            },
            webtoonShardCount = webtoonConnectionShardCount,
        ) { pageIndex -> pages[pageIndex].routeBucketHint }
    private val coldConnectionCohortLeaderSet = coldConnectionCohortLeaders.toHashSet()
    private val webtoonPreAnchorGateOperations =
        NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
            cohortCount = coldConnectionCohortLeaders.size,
            cellularResilientTransport = cellularResilientTransport,
            episodePageCount = pages.size,
            // This new identity is consumed only by the adjacent four-body exception above. Keep
            // ordinary/current webtoon entry on its already-qualified three-body policy.
            directWifiTransport = directWifiTransport && adjacentPrefetch,
            adjacentPrefetch = adjacentPrefetch,
        )
    private val settledColdConnectionCohortLeaders = ConcurrentHashMap.newKeySet<Int>()
    private val coldConnectionCohortByPage = Array(pages.size) { pageIndex ->
        NtkStrictInitialWavePolicy.coldConnectionCohortKey(
            planBinding.episodePath,
            pageIndex,
            pages[pageIndex].routeBucketHint,
            webtoonConnectionShardCount,
        )
    }
    private val settledColdConnectionCohorts = ConcurrentHashMap.newKeySet<String>()
    /** Actor time of the first real response header from the finite cold cohort wave. */
    private var firstColdConnectionCohortSettledAtMs = 0L
    @Volatile
    private var coldConnectionCohortsOpen = coldConnectionCohortLeaders.isEmpty()
    /**
     * The first click-owned wave has a fixed forward order. The former selector rebuilt a
     * Candidate object/list and a grouping map for every admitted page, turning 114 admissions
     * into an allocation-heavy O(n²) actor loop before the final HTTP Call could even start.
     */
    private val preGeometryPendingPages = ArrayDeque<Int>(pages.size).apply {
        coldConnectionCohortLeaders.forEach(::addLast)
        for (pageIndex in initialPageIndex until pages.size) {
            if (pageIndex in rollingAdmittedPages &&
                pageIndex !in coldConnectionCohortLeaderSet
            ) addLast(pageIndex)
        }
        for (pageIndex in 0 until initialPageIndex) {
            if (pageIndex in rollingAdmittedPages &&
                pageIndex !in coldConnectionCohortLeaderSet
            ) addLast(pageIndex)
        }
    }
    private var pendingRollingAdmittedPages: Set<Int>? = null
    private val routeOperationLimit = if (planBinding.episodePath.startsWith("/manhwa/")) {
        NtkSourceLanePolicy.MAX_MANHWA_NETWORK_OPERATIONS_PER_ROUTE
    } else {
        NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS_PER_ROUTE
    }
    private val initialQuarantineWaveTargetCount = when {
        // The click-owned stream is already the finite cold physical wave. Requiring another
        // source GET before promotion both duplicates entry contention and creates a cycle:
        // promotion is needed to draw the stream, while bulk source GETs intentionally wait for
        // that draw. Start with a zero-call source proof and open exact publication immediately.
        streamedExactBodyFutures.isNotEmpty() -> 0
        planBinding.episodePath.startsWith("/webtoon/") ->
            webtoonPreAnchorGateOperations
        else -> coldConnectionCohortLeaders.size
    }
    private var geometryDigest = ""
    private var exactStagePageIndexes: Set<Int> = emptySet()
    private var primaryAdmissionsSealed = false
    private var actorClosed = false
    private var phase: SessionPhase = SessionPhase.New
    private var planReservedAtMs = 0L
    private var exactSealAtMsInternal = 0L
    private var claimAtMs = 0L
    private var firstQuarantineSubmittedAtMs = 0L
    private var initialQuarantineWaveSubmittedAtMs = 0L
    private var initialWaveCount = 0
    private var postPromotionStarted = 0
    private var overlapProof: NtkSourceOverlapProof? = null
    private var metadataFirstAtMs = 0L
    private var metadataAllAtMs = 0L
    private var metadataPublishedCount = 0
    private var bodyPublishedCount = 0
    private var forwardResumeReadyLogged = false
    private val wifiWebtoonAdaptiveLanes =
        if (NtkWifiWebtoonAdaptiveLaneState.isEligible(
                episodePath = planBinding.episodePath,
                wifiQuicBulkTransport = wifiQuicBulkTransport,
                cellularResilientTransport = cellularResilientTransport,
                adjacentPrefetch = adjacentPrefetch,
                currentForegroundEpisode = currentForegroundViewerGeneration > 0L,
            )
        ) {
            NtkWifiWebtoonAdaptiveLaneState()
        } else {
            null
        }
    private var geometryBoundAtMs = 0L
    private var sourceDemandDeliveryQueued = false
    private var pendingSourceDemand: SourceDemandDelivery? = null
    private val sourceDemandEpochGate = NtkSourceDemandEpochGate()
    private val sourceDemandMailboxLock = Any()
    private val sourceDemandOfferCount = AtomicLong()
    private val sourceDemandAppliedCount = AtomicLong()
    private val sourceDemandCoalescedCount = AtomicLong()
    private val actorCallbackDepth = ThreadLocal.withInitial { 0 }
    private val routePreparationStarted = AtomicBoolean(false)
    private val routePreparationRefillScheduled = AtomicBoolean(false)
    /**
     * A refill can materialize many lazy worker threads and build many HTTP calls. Keep that work
     * time-sliced on the source actor so response-header and, critically, completed-body callbacks
     * already queued by the physical lanes can publish without waiting behind an entire 48-call
     * expansion. This flag is actor-confined; it only coalesces the continuation runnable.
     */
    private var primaryRefillContinuationScheduled = false
    private lateinit var quarantineRoutePreparations:
        Array<CompletableFuture<QuarantineRoutePreparation>>

    val sessionId: Long = SESSION_SEQUENCE.getAndIncrement()
    val exactSealAtMs: Long
        get() = publishedView.get().exactSealAtMs

    init {
        require(candidateSeal.digestSha256 == planBinding.orderedAssetsDigest)
        require(initialPageIndex in pages.indices)
        require(initialExactBodies.size <= pages.size)
        require(externallyOwnedPageIndexes.all { it in pages.indices })
        require(initialExactBodies.keys.intersect(externallyOwnedPageIndexes).isEmpty())
        require(initialExactBodies.size + externallyOwnedPageIndexes.size <= pages.size)
        require(
            initialExactBodies.size <= minOf(
                pages.size,
                NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            )
        )
        initialExactBodies.forEach { (pageIndex, body) ->
            require(pageIndex in pages.indices)
            val metadata = body.metadata.requireProductionAuthority()
            body.proof.requireProductionAuthority(metadata)
            require(metadata.authority.acquisition ==
                NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY)
            require(metadata.manifestDigest == candidateSeal.digestSha256)
            require(metadata.pageIndex == pageIndex)
            require(metadata.canonicalAsset == pages[pageIndex].canonicalAsset)
        }
        bindStreamedExactBodyCompletions()
        bindBulkSourcePhysicalAdmission()
    }

    private fun bindStreamedExactBodyCompletions() {
        streamedExactBodyFutures.forEach { (pageIndex, future) ->
            future.whenComplete { body, failure ->
                executeActor {
                    acceptStreamedExactBodyCompletionActor(pageIndex, body, failure)
                }
            }
        }
    }

    private fun bindBulkSourcePhysicalAdmission() {
        if (streamedExactBodies == null || bulkSourcePhysicalAdmissionReady.isDone) return
        bulkSourcePhysicalAdmissionReady.whenComplete { _, _ ->
            executeActor {
                if (actorClosed || closeRequested.get()) return@executeActor
                logSourceEvent(
                    "reader_strip_source_bulk_physical_admission_open",
                    "streamedPages=${streamedExactBodyFutures.size}",
                )
                refillLanesActor()
            }
        }
    }

    private fun acceptStreamedExactBodyCompletionActor(
        pageIndex: Int,
        body: ReaderImageCache.NtkStrictPublishedBody?,
        failure: Throwable?,
    ) {
        assertActorThread()
        val page = pages[pageIndex]
        if (!page.streamedExactBodyPending) return
        page.streamedExactBodyPending = false
        if (failure != null || body == null) {
            logSourceEvent(
                "reader_strip_streamed_exact_body_fallback",
                "pageIndex=$pageIndex,error=${failure?.javaClass?.simpleName ?: "empty_body"}",
            )
            if (::quarantineRoutePreparations.isInitialized) {
                prepareFallbackRouteForStreamedPage(pageIndex)
            }
            if (rollingAdmission) rollingAdmittedPages = rollingAdmittedPages + pageIndex
            if (!isGeometrySealed() && pageIndex !in preGeometryPendingPages) {
                preGeometryPendingPages.addLast(pageIndex)
            }
            if (phase is SessionPhase.Quarantining || phase is SessionPhase.ExactOpen) {
                refillLanesActor()
            }
            return
        }
        validateStreamedExactBody(pageIndex, body)
        when (phase) {
            is SessionPhase.ExactOpen -> {
                // Streamed click-owned bodies bypass executePhysical(), so publish their immutable
                // resident descriptor here as well. Without this, an adjacent numeric volume could
                // complete all source accounting while its atomic runway saw only seeded page 0.
                publishResidentBodyForRender(body)
                acceptExactBody(page, body)
                page.primaryStarted = true
                page.quarantineState = NtkQuarantinePageState.EXACT_OWNED
                logSourceEvent(
                    "reader_strip_source_streamed_exact_body",
                    "pageIndex=$pageIndex,bytes=${body.proof.encodedLength}",
                )
                refillLanesActor()
                maybeCompletePreparationDrainActor()
            }
            is SessionPhase.Closing,
            SessionPhase.Closed -> Unit
            else -> page.seededExactBody = body
        }
    }

    private fun validateStreamedExactBody(
        pageIndex: Int,
        body: ReaderImageCache.NtkStrictPublishedBody,
    ) {
        val metadata = body.metadata.requireProductionAuthority()
        body.proof.requireProductionAuthority(metadata)
        require(metadata.authority.acquisition ==
            NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY)
        require(metadata.manifestDigest == candidateSeal.digestSha256)
        require(metadata.pageIndex == pageIndex)
        require(metadata.canonicalAsset == pages[pageIndex].canonicalAsset)
    }

    fun enqueueStartQuarantined(): CompletableFuture<NtkQuarantineStartProof> {
        startQuarantineRoutePreparation()
        return enqueueActorCommand {
            assertActorThread()
            check(phase == SessionPhase.New) {
                "Quarantine source session already started"
            }
            check(!closeRequested.get())
            NtkQuarantineSourceOwnershipRegistry.beginSession(planBinding, sessionId)
            planReservedAtMs = SystemClock.elapsedRealtime()
            phase = SessionPhase.Quarantining(planReservedAtMs)
            refillLanesActor()
            check(initialWaveCount == initialQuarantineWaveTargetCount)
            val ownership = checkNotNull(
                quarantineOwnershipSnapshot()
            ) { "Quarantine ownership disappeared during start" }
            checkOwnershipIdentity(ownership)
            val proofTimestamp = if (initialWaveCount == 0) {
                planReservedAtMs
            } else {
                firstQuarantineSubmittedAtMs
            }
            NtkQuarantineStartProof(
                planReservedAtMs,
                proofTimestamp,
                if (initialWaveCount == 0) proofTimestamp else initialQuarantineWaveSubmittedAtMs,
                initialWaveCount,
                initialWaveCount,
                ownership.physicalCallCount,
                ownership.duplicatePhysicalCallCount
            )
        }
    }

    private fun startQuarantineRoutePreparation() {
        check(routePreparationStarted.compareAndSet(false, true)) {
            "Quarantine route preparation already started"
        }
        // Previously the source actor resolved the Request route and calculated two SHA-256
        // identities serially for every page. On a 114-page webtoon that held back the last
        // physical GET by roughly 400 ms. Compute the immutable, CPU-only material on eight
        // bounded prestarted lanes; the actor still performs every ownership admission in order.
        // A click-owned exact wave can already contain every immutable body. In that case there
        // is no source route left to resolve, and scheduling page-count hash work only steals CPU
        // from exact publication and decode on the entry deadline.
        if (initialExactBodies.size == pages.size) {
            quarantineRoutePreparations = emptyArray()
            return
        }
        quarantineRoutePreparations = Array(pages.size) { pageIndex ->
            val page = pages[pageIndex]
            if (page.seededExactBody != null ||
                page.streamedExactBodyPending ||
                !isRoutePreparationAdmitted(pageIndex)
            ) {
                CompletableFuture()
            } else {
                createRoutePreparation(pageIndex)
            }
        }
        quarantineRoutePreparations.forEachIndexed { pageIndex, preparation ->
            val page = pages[pageIndex]
            if (page.seededExactBody == null && !page.streamedExactBodyPending) {
                preparation.whenComplete { _, _ -> scheduleRoutePreparationRefill() }
            }
        }
    }

    private fun createRoutePreparation(
        pageIndex: Int,
    ): CompletableFuture<QuarantineRoutePreparation> {
        val page = pages[pageIndex]
        // Route resolution is immutable CPU-only work. It may wait for the bounded extension
        // probe, but never for a frame; promotion must be able to publish the streamed anchor
        // before bulk physical admission opens.
        val sourceRouteReady = streamedExactBodies?.sourceRoutePreparationReady
            ?: CompletableFuture.completedFuture(Unit)
        return sourceRouteReady.thenApplyAsync({
            val route = ReaderImageCache.resolveStrictSourceRoute(
                manga,
                candidateSeal,
                page.pageIndex,
                page.canonicalAsset,
            )
            val canonicalAssetDigest = NtkStripDigests.canonicalAssetDigestSha256(
                page.canonicalAsset,
            )
            QuarantineRoutePreparation(
                route,
                canonicalAssetDigest,
                ReaderImageCache.quarantineEffectiveRequestDigest(
                    route,
                    page.pageIndex,
                    page.canonicalAsset,
                    canonicalAssetDigest,
                ),
            )
        }, routePreparationLanes[pageIndex % routePreparationLanes.size])
    }

    private fun prepareFallbackRouteForStreamedPage(pageIndex: Int) {
        assertActorThread()
        if (!::quarantineRoutePreparations.isInitialized ||
            quarantineRoutePreparations.isEmpty() ||
            quarantineRoutePreparations[pageIndex].isDone ||
            !isRoutePreparationAdmitted(pageIndex)
        ) return
        val preparation = createRoutePreparation(pageIndex)
        quarantineRoutePreparations[pageIndex] = preparation
        preparation.whenComplete { _, _ -> scheduleRoutePreparationRefill() }
    }

    private fun isRoutePreparationAdmitted(pageIndex: Int): Boolean =
        NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
            pageIndex = pageIndex,
            pageCount = pages.size,
            initialPageIndex = initialPageIndex,
            adjacentPrefetch = adjacentPrefetch,
            adjacentPrefetchReleased = adjacentPrefetchReleased,
            forwardResume = rollingAdmission && initialPageIndex > 0,
        )

    private fun startReleasedAdjacentRoutePreparationsActor() {
        assertActorThread()
        if (!adjacentPrefetch || !adjacentPrefetchReleased ||
            !::quarantineRoutePreparations.isInitialized ||
            quarantineRoutePreparations.isEmpty()
        ) return
        pages.forEachIndexed { pageIndex, page ->
            if (!isRoutePreparationAdmitted(pageIndex) ||
                page.seededExactBody != null ||
                page.streamedExactBodyPending ||
                quarantineRoutePreparations[pageIndex].isDone
            ) return@forEachIndexed
            val wasInitiallyAdmitted =
                NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                    pageIndex = pageIndex,
                    pageCount = pages.size,
                    initialPageIndex = initialPageIndex,
                    adjacentPrefetch = true,
                    adjacentPrefetchReleased = false,
                )
            if (wasInitiallyAdmitted) return@forEachIndexed
            val preparation = createRoutePreparation(pageIndex)
            quarantineRoutePreparations[pageIndex] = preparation
            preparation.whenComplete { _, _ -> scheduleRoutePreparationRefill() }
        }
    }

    private fun scheduleRoutePreparationRefill() {
        if (!routePreparationRefillScheduled.compareAndSet(false, true)) return
        executeActor(
            onRejected = { routePreparationRefillScheduled.set(false) },
        ) {
            routePreparationRefillScheduled.set(false)
            if (!actorClosed && !closeRequested.get()) refillLanesActor()
        }
    }

    fun enqueuePreparePromotion(
        token: NtkPromotionToken,
        validity: AtomicBoolean
    ): CompletableFuture<NtkPromotionSnapshot> = enqueueActorCommand {
        preparePromotionActor(token, validity)
    }

    fun enqueueInstallExactBinding(
        token: NtkPromotionToken,
        validity: AtomicBoolean,
        owner: NtkStrictSourceOwnershipRegistry.Owner,
        manifest: NtkAuthoritativeManifest,
        snapshot: NtkPromotionSnapshot
    ): CompletableFuture<Unit> = enqueueActorCommand {
        installExactBindingActor(token, validity, owner, manifest, snapshot)
    }

    fun enqueueActivateExactPublication(
        token: NtkPromotionToken,
        validity: AtomicBoolean
    ): CompletableFuture<Unit> = enqueueActorCommand {
        activateExactPublicationActor(token, validity)
    }

    private fun preparePromotionActor(
        token: NtkPromotionToken,
        validity: AtomicBoolean
    ): NtkPromotionSnapshot {
        assertActorThread()
        check(validity.get() && !closeRequested.get())
        check(phase is SessionPhase.Quarantining)
        checkTokenIdentity(token)
        phase = SessionPhase.PromotionPreparing(token)
        exactSealAtMsInternal = SystemClock.elapsedRealtime()
        val completed = pages.filter { it.quarantinedBody != null }
            .mapTo(LinkedHashSet(), PageState::pageIndex)
        val active = activeWorks.filterNotNull()
            .mapTo(LinkedHashSet(), PrimaryWork::pageIndex)
        val queued = pages.filter {
            !it.primaryStarted && it.quarantinedBody == null
        }.mapTo(LinkedHashSet(), PageState::pageIndex)
        val ownership = checkNotNull(
            quarantineOwnershipSnapshot()
        ) { "Quarantine ownership missing at promotion cut" }
        checkOwnershipIdentity(ownership)
        check(ownership.duplicatePhysicalCallCount == 0)
        val snapshot = NtkPromotionSnapshot(
            token,
            pages.size,
            completed,
            active,
            queued,
            ownership.physicalCallCount,
            ownership.duplicatePhysicalCallCount
        )
        check(validity.get() && !closeRequested.get())
        phase = SessionPhase.PromotionPrepared(token, snapshot)
        return snapshot
    }

    private fun installExactBindingActor(
        token: NtkPromotionToken,
        validity: AtomicBoolean,
        owner: NtkStrictSourceOwnershipRegistry.Owner,
        manifest: NtkAuthoritativeManifest,
        snapshot: NtkPromotionSnapshot
    ) {
        assertActorThread()
        check(validity.get() && !closeRequested.get())
        val prepared = phase as? SessionPhase.PromotionPrepared
            ?: error("Exact install preceded actor promotion prepare")
        check(prepared.token == token && prepared.snapshot == snapshot)
        check(snapshot.token == token)
        checkTokenIdentity(token)
        check(owner.state == NtkStrictSourceOwnershipRegistry.State.OWNED)
        check(owner.path == token.episodePath)
        check(owner.discoveryGeneration == token.discoveryGeneration)
        check(owner.sessionId == token.sessionId)
        check(owner.manifestDigest == token.exactManifestDigest)
        check(owner.exactProofDigest == token.exactProofDigest)
        check(owner.planBindingDigest == token.planBindingDigest)
        check(owner.promotionNonce == token.nonce)
        check(manifest.seal.digestSha256 == token.exactManifestDigest)
        check(manifest.proof.proofDigestSha256 == token.exactProofDigest)
        check(manifest.seal.normalizedCanonicalAssets ==
            planBinding.normalizedOrderedCanonicalAssets)
        validateMonotonicPromotionPartition(snapshot)

        val preparedContexts =
            ArrayList<Pair<PrimaryWork, ReaderImageCache.NtkStrictCallContext>>()
        try {
            for (work in activeWorks.filterNotNull()) {
                if (work.mode != WorkMode.QUARANTINE || work.exactContext != null) continue
                preparedContexts += work to beginExactOperationActor(work, manifest)
            }
            for ((work, _) in preparedContexts) {
                val mark = checkNotNull(work.quarantineLease).markAdopted(token)
                check(mark != NtkQuarantineSourceOwnershipRegistry.AdoptionMark.STALE) {
                    "Quarantine operation became stale during exact adoption"
                }
            }
            check(validity.get() && !closeRequested.get()) {
                "Promotion token invalidated during exact context preparation"
            }
        } catch (failure: Throwable) {
            preparedContexts.forEach { (_, context) -> context.operationLease.complete() }
            throw failure
        }
        for ((work, context) in preparedContexts) {
            work.exactContext = context
            pages[work.pageIndex].adoptedExactContext = context
        }
        claimAtMs = owner.claimedAtMs
        phase = SessionPhase.ExactInstalledGateClosed(token, snapshot, manifest, owner)
    }

    private fun activateExactPublicationActor(
        token: NtkPromotionToken,
        validity: AtomicBoolean
    ) {
        assertActorThread()
        val installed = phase as? SessionPhase.ExactInstalledGateClosed
            ?: error("Exact publication activation preceded install")
        check(installed.token == token)
        check(validity.get() && !closeRequested.get())
        phase = SessionPhase.ExactOpen(installed.manifest, installed.owner)
        residentAdoptionManifest.set(installed.manifest)
        manhwaWaveRecoveryState?.armExactAuthority(pages.size)
        acceptSeededExactBodiesActor()
        adoptAllSealedBodiesActor()
        refillLanesActor()
        val overlapFirstSubmissionAt = if (initialWaveCount == 0) {
            planReservedAtMs
        } else {
            firstQuarantineSubmittedAtMs
        }
        overlapProof = NtkSourceOverlapProof(
            planReservedAtMs,
            overlapFirstSubmissionAt,
            if (initialWaveCount == 0) {
                overlapFirstSubmissionAt
            } else {
                initialQuarantineWaveSubmittedAtMs
            },
            initialWaveCount,
            exactSealAtMsInternal,
            claimAtMs,
            installed.snapshot.completedPageIndexes.size,
            installed.snapshot.activePageIndexes.size,
            installed.snapshot.queuedPageIndexes.size,
            postPromotionStarted,
            installed.snapshot.physicalCallCount,
            installed.snapshot.duplicatePhysicalCallCount,
            // The only zero-wave same-millisecond promotion we admit is the bounded direct-Wi-Fi
            // adjacent runway. Cellular/SNI and the foreground episode retain strict positive
            // overlap proof even when their timing happens to fall in one clock millisecond.
            sameMillisecondSeededExactAllowed = adjacentPrefetch,
        )
        logOverlapProofActor()
    }

    fun bindEpisode(
        episode: NtkEpisodeToken,
        seal: NtkEpisodeManifestSeal,
        initialPageIndex: Int,
        listener: NtkSourceEventListener
    ): Closeable {
        check(!closeRequested.get())
        require(initialPageIndex in pages.indices)
        streamedExactBodies?.onInitialViewportActivated(initialPageIndex)
        val listenerId = listenerSequence.getAndIncrement()
        val detached = AtomicBoolean(false)
        executeActor {
            if (actorClosed || detached.get()) return@executeActor
            val open = phase as? SessionPhase.ExactOpen
                ?: error("Episode binding preceded exact publication activation")
            check(seal.hasSameAuthority(open.manifest.seal))
            val current = boundEpisode
            check(current == null || current == episode)
            boundEpisode = episode
            listeners[listenerId] = listener
            replayLedger(listener)
            refillLanesActor()
        }
        return Closeable {
            if (!detached.compareAndSet(false, true)) return@Closeable
            executeActor { listeners.remove(listenerId) }
        }
    }

    fun bindResidentBodies(
        episode: NtkEpisodeToken,
        seal: NtkEpisodeManifestSeal,
        listener: NtkStrictResidentBodyListener
    ): Closeable {
        check(!closeRequested.get())
        val manifest = checkNotNull(residentAdoptionManifest.get()) {
            "Resident render binding preceded exact publication activation"
        }
        check(seal.hasSameAuthority(manifest.seal))
        check(episode.value == seal.revision)
        val listenerId = listenerSequence.getAndIncrement()
        residentBodyListeners[listenerId] = listener
        // Registration and physical publication can race. Delivery is intentionally at-least-once;
        // descriptors carry an immutable source key and the render owner installs them idempotently.
        residentRenderDescriptors.entries
            .sortedBy { it.key }
            .forEach { (_, descriptor) ->
                runCatching { listener.onResidentBody(descriptor) }
            }
        return Closeable { residentBodyListeners.remove(listenerId) }
    }

    fun addSourceEventListener(listener: NtkSourceEventListener): Closeable {
        check(!closeRequested.get())
        val listenerId = listenerSequence.getAndIncrement()
        val detached = AtomicBoolean(false)
        executeActor {
            if (actorClosed || detached.get()) return@executeActor
            check(phase is SessionPhase.ExactOpen) {
                "Listener registration preceded exact publication activation"
            }
            listeners[listenerId] = listener
            replayLedger(listener)
        }
        return Closeable {
            if (!detached.compareAndSet(false, true)) return@Closeable
            executeActor { listeners.remove(listenerId) }
        }
    }

    fun requestPreparationDrain(
        episode: NtkEpisodeToken,
        completion: (NtkSourceDrainProof) -> Unit
    ) {
        check(!closeRequested.get())
        executeActor {
            check(acceptsEpisode(episode))
            preparationDrainCompletions += completion
            maybeCompletePreparationDrainActor()
        }
    }

    fun onFirstActualFramePresented(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (acceptsEpisode(episode)) {
                streamedExactBodies?.onFirstActualFramePresented()
            }
        }
    }

    fun onInitialDrawableCommitted(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (acceptsEpisode(episode)) {
                streamedExactBodies?.onInitialDrawableCommitted()
            }
        }
    }

    fun onAdjacentPredecessorComplete(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode)) return@executeActor
            adjacentPredecessorCompleted = true
            maybeReleaseAdjacentPrefetchAfterRunwayActor("predecessor_complete")
        }
    }

    fun onAdjacentViewportActivated(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode)) return@executeActor
            adjacentViewportActivated = true
            streamedExactBodies?.onAdjacentViewportActivated()
            maybeReleaseAdjacentPrefetchAfterRunwayActor("viewport_activated")
        }
    }

    private fun maybeReleaseAdjacentPrefetchAfterRunwayActor(reason: String) {
        assertActorThread()
        if (!adjacentPrefetch || adjacentPrefetchReleased ||
            !adjacentPredecessorCompleted || !adjacentViewportActivated
        ) return
        val runwayEndExclusive = minOf(
            pages.size,
            initialPageIndex + NtkStrictInitialWavePolicy.WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
        )
        if ((initialPageIndex until runwayEndExclusive).any { pages[it].publishedBody == null }) return
        streamedExactBodies?.onAdjacentRunwayReady()
        releaseAdjacentPrefetchActor("${reason}_runway_ready")
    }

    private fun releaseAdjacentPrefetchActor(reason: String) {
        assertActorThread()
        if (!adjacentPrefetch || adjacentPrefetchReleased) return
        adjacentPrefetchReleased = true
        startReleasedAdjacentRoutePreparationsActor()
        if (rollingAdmission) {
            val previousAdmission = rollingAdmittedPages
            val expandedAdmission = (initialPageIndex until pages.size).toSet()
            rollingAdmittedPages = expandedAdmission
            pendingRollingAdmittedPages = null
            // The pre-geometry deque is materialized from the initial four-page adjacent runway.
            // Expand the immutable resident-body workset after predecessor completion, but keep
            // list structure and decoded pixels under ReaderSession's viewport/idle gates.
            if (!isGeometrySealed() && sourceDemand == null) {
                for (pageIndex in initialPageIndex until pages.size) {
                    val page = pages[pageIndex]
                    if (pageIndex !in previousAdmission &&
                        !page.primaryStarted && page.terminalEvent == null &&
                        page.seededExactBody == null && !page.streamedExactBodyPending
                    ) {
                        preGeometryPendingPages.addLast(pageIndex)
                    }
                }
            }
        }
        logSourceEvent(
                "reader_strip_source_adjacent_prefetch_released",
                "reason=$reason,initialPage=$initialPageIndex,admitted=${rollingAdmittedPages.size}," +
                    "activeBefore=${activeWorks.count { it != null }}," +
                    "published=$bodyPublishedCount,metadata=$metadataPublishedCount," +
                    "unstarted=${pages.count { !it.primaryStarted && it.publishedBody == null && !it.streamedExactBodyPending }}," +
                    "pending=${preGeometryPendingPages.size}," +
                    "admissionsSealed=$primaryAdmissionsSealed",
        )
        refillLanesActor()
        val exactManifest = (phase as? SessionPhase.ExactOpen)?.manifest
        logSourceEvent(
                "reader_strip_source_adjacent_prefetch_refilled",
                "reason=$reason,activeAfter=${activeWorks.count { it != null }}," +
                    "published=$bodyPublishedCount," +
                    "unstarted=${pages.count { !it.primaryStarted && it.publishedBody == null && !it.streamedExactBodyPending }}," +
                    "bulkReady=${pages.count { !it.primaryStarted && isBulkSourcePhysicalAdmissionReady(it.pageIndex) }}," +
                    "cohortReady=${pages.count { !it.primaryStarted && isColdConnectionCohortEligible(it.pageIndex) }}," +
                    "routeReady=${pages.count { !it.primaryStarted && isRoutePreparationReadyWithoutActorWait(it.pageIndex) }}," +
                    "physicalLanes=${activeWorks.size},usefulLanes=${usefulPhysicalLaneCountActor(anchorBodyPublishedActor())}," +
                    "cohortsOpen=$coldConnectionCohortsOpen,phase=${phase.javaClass.simpleName}," +
                    "ownerReady=${exactManifest?.let { manifest ->
                        NtkStrictSourceOwnershipRegistry.canBeginOperationNow(
                            planBinding.episodePath,
                            manifest.seal.digestSha256,
                            sessionId,
                        )
                    } ?: false}",
        )
    }

    fun applyPreGeometryPlan(episode: NtkEpisodeToken, candidate: NtkPreGeometrySourcePlan) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode) || isGeometrySealed()) return@executeActor
            if (candidate.revision >= preGeometryPlan.revision) preGeometryPlan = candidate
            refillLanesActor()
        }
    }

    fun applySourceDemand(episode: NtkEpisodeToken, candidate: NtkSourceDemandSnapshot) {
        if (closeRequested.get()) return
        var conflict = false
        val delivery = synchronized(sourceDemandMailboxLock) {
            when (sourceDemandEpochGate.offer(episode, candidate)) {
                NtkSourceDemandOfferDecision.STALE,
                NtkSourceDemandOfferDecision.IDEMPOTENT -> null
                NtkSourceDemandOfferDecision.CONFLICT -> {
                    conflict = true
                    null
                }
                NtkSourceDemandOfferDecision.ACCEPT -> {
                    sourceDemandOfferCount.incrementAndGet()
                    val offered = SourceDemandDelivery(episode, candidate)
                    if (sourceDemandDeliveryQueued) {
                        if (pendingSourceDemand != null) sourceDemandCoalescedCount.incrementAndGet()
                        pendingSourceDemand = offered
                        null
                    } else {
                        sourceDemandDeliveryQueued = true
                        offered
                    }
                }
            }
        }
        if (conflict) {
            executeActor {
                if (acceptsEpisode(episode) && !closeRequested.get()) {
                    failSessionActor(
                        NtkSourceIdentityException(
                            "Conflicting strict source demand at epoch ${candidate.demandEpoch}"
                        )
                    )
                }
            }
            return
        }
        delivery?.let(::enqueueSourceDemandDelivery)
    }

    private fun enqueueSourceDemandDelivery(delivery: SourceDemandDelivery) {
        executeActor(onRejected = {
            synchronized(sourceDemandMailboxLock) {
                sourceDemandDeliveryQueued = false
                pendingSourceDemand = null
            }
        }) {
            if (acceptsEpisode(delivery.episode) &&
                delivery.candidate.demandEpoch >= currentDemandEpoch()
            ) {
                val all = delivery.candidate.orderedPages()
                check(all.size == pages.size && all.toSet() == pages.indices.toSet())
                sourceDemand = delivery.candidate
                if (rollingAdmission) {
                    // The exact manifest is already owned by the visible viewer. Keep physical
                    // calls bounded, but admit the whole remaining forward source path so a fast
                    // downward fling never waits for another viewport event before byte fetch.
                    if (!adjacentPrefetch || adjacentPrefetchReleased) {
                        rollingAdmittedPages = (initialPageIndex until pages.size).toSet()
                        pendingRollingAdmittedPages = null
                    }
                    logSourceEvent(
                        "reader_strip_source_forward_runway_admitted",
                        "initialPage=$initialPageIndex,admitted=${rollingAdmittedPages.size}"
                    )
                }
                sourceDemandAppliedCount.incrementAndGet()
                refillLanesActor()
            }
            val next = synchronized(sourceDemandMailboxLock) {
                val pending = pendingSourceDemand
                pendingSourceDemand = null
                if (pending == null || closeRequested.get()) {
                    sourceDemandDeliveryQueued = false
                    null
                } else pending
            }
            next?.let(::enqueueSourceDemandDelivery)
        }
    }

    fun onGeometrySealed(
        episode: NtkEpisodeToken,
        digest: String,
        exactStagePages: Set<Int>
    ) {
        require(NtkStripDigests.isSha256(digest))
        require(exactStagePages == pages.indices.toSet())
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode)) return@executeActor
            check(primaryAdmissionsSealed)
            check(pages.all { it.metadataEvent != null })
            geometryDigest = digest
            exactStagePageIndexes = exactStagePages.toSet()
            geometryBoundAtMs = SystemClock.elapsedRealtime()
            if (sourceDemand == null) {
                sourceDemand = NtkSourceDemandSnapshot(
                    authority = episode.value,
                    demandEpoch = 0L,
                    hardPages = exactStagePages.sorted().toIntArray(),
                    softPages = IntArray(0),
                    backgroundPages = IntArray(0)
                )
            }
            val seal = exactOpenManifestActor().seal
            check(NtkStrictSourceOwnershipRegistry.setGeometrySealed(
                planBinding.episodePath,
                seal.digestSha256,
                sessionId
            ))
            logSourceEvent(
                "reader_strip_source_geometry_bound",
                "manifestDigest=${seal.digestSha256},geometryDigest=$digest," +
                    "geometryBoundAt=$geometryBoundAtMs,pageCount=${pages.size}"
            )
        }
    }

    fun retire(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor { if (acceptsEpisode(episode)) requestClose(null) }
    }

    fun requestClose(cause: Throwable?) {
        if (!closeBodyLeaseAdmissions()) return
        executeActor {
            closeSessionActor(cause)
        }
    }

    private fun closeBodyLeaseAdmissions(): Boolean = synchronized(bodyLeaseAdmissionLock) {
        closeRequested.compareAndSet(false, true)
    }

    override fun close() = requestClose(null)

    private fun closeSessionActor(cause: Throwable?) {
        assertActorThread()
        if (phase is SessionPhase.Closed || phase is SessionPhase.Closing) {
            maybeFinishClosedActor()
            return
        }
        phase = SessionPhase.Closing(currentExactIdentityActor(), cause)
        manhwaWaveRecoveryState?.close()
        streamedExactBodies?.close()
        NtkQuarantineSourceOwnershipRegistry.closeAdmissions(
            planBinding.episodePath,
            planBinding.discoveryGeneration,
            sessionId
        )
        activeWorks.filterNotNull().forEach { it.cancellation.cancel() }
        routePreparationLanes.forEach(ExecutorService::shutdown)
        physicalLanes.forEach(ExecutorService::shutdown)
        maybeFinishClosedActor()
    }

    /**
     * Keeps the established transport-specific limits as the base policy. Only the current
     * direct-Wi-Fi webtoon may widen that base after balanced fast EOF evidence; a failed anchor,
     * carrier/SNI, manhwa, and adjacent work therefore never observe the adaptive target.
     */
    private fun usefulPhysicalLaneCountActor(anchorBodyPublished: Boolean): Int {
        assertActorThread()
        val base = NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
            planBinding.episodePath,
            activeWorks.size,
            anchorBodyPublished,
            manhwaTransferLimit = manhwaPhysicalTransferLimit,
            cellularResilientTransport = cellularResilientTransport,
            webtoonPublishedBodyCount = bodyPublishedCount,
            wifiQuicBulkTransport = wifiQuicBulkTransport,
            episodePageCount = pages.size,
            adjacentPrefetch = adjacentPrefetch && !adjacentPrefetchReleased,
            webtoonConnectionShardCount = webtoonConnectionShardCount,
        )
        val adaptive = wifiWebtoonAdaptiveLanes ?: return base
        if (!isCurrentForegroundViewerEpisode()) return base
        val anchor = pages[initialPageIndex]
        val anchorSucceeded = anchor.quarantinedBody != null || anchor.publishedBody != null
        if (!anchorBodyPublished || !anchorSucceeded || bodyPublishedCount <= 0) return base
        return minOf(activeWorks.size, adaptive.target)
    }

    private fun isCurrentForegroundViewerEpisode(): Boolean =
        currentForegroundViewerGeneration > 0L &&
            ViewerTelemetry.activeGeneration() == currentForegroundViewerGeneration &&
            ViewerTelemetry.isActiveEpisode(planBinding.episodePath)

    private fun refillLanesActor() {
        assertActorThread()
        val exactOpen = phase as? SessionPhase.ExactOpen
        val retryAfterAdmissionSeal = primaryAdmissionsSealed &&
            hasRecoverableRetryCandidateActor()
        if (actorClosed || closeRequested.get() ||
            (phase !is SessionPhase.Quarantining && exactOpen == null) ||
            (primaryAdmissionsSealed && !retryAfterAdmissionSeal)
        ) return
        // The quarantine start proof is defined by one simultaneously admitted leader for every
        // physical connection cohort. Preserve that finite first wave in its opening actor turn;
        // only time-slice the much wider post-proof expansion that formerly starved EOF events.
        val anchorBodyPublished = anchorBodyPublishedActor()
        // Establish one real image stream per origin/pool first. Once the entry body has crossed
        // the actor boundary it can no longer be delayed by later callbacks, so the bounded
        // 120-operation forward ring may be filled immediately instead of waiting for the slowest
        // unrelated cohort to settle.
        val usefulPhysicalLaneCount = usefulPhysicalLaneCountActor(anchorBodyPublished)
        val progressiveLaneCount = NtkStrictInitialWavePolicy.forwardLaneTarget(
            usefulPhysicalLaneCount,
            coldConnectionCohortLeaders.size,
            if (coldConnectionCohortsOpen) {
                coldConnectionCohortLeaders.size
            } else {
                settledColdConnectionCohorts.size
            },
            anchorBodyPublished,
        )
        // Header arrival is not body success. Wi-Fi keeps one body per replica origin before page
        // zero EOF. Carrier mode opens one demanded leader per finite host/pool cohort instead:
        // otherwise fifteen pools remain idle for about one second, then their leaders and
        // followers stampede those cold connections together.
        val usableLaneCount = if (
            planBinding.episodePath.startsWith("/webtoon/") && !anchorBodyPublished
        ) {
            minOf(progressiveLaneCount, webtoonPreAnchorGateOperations)
        } else {
            progressiveLaneCount
        }
        val launchLimitThisTurn = when {
            initialWaveCount < initialQuarantineWaveTargetCount ->
                initialQuarantineWaveTargetCount
            // Only the entry image needs actor latency protection. Once its complete body has
            // crossed the actor boundary, opening the remaining finite ring cannot delay that
            // publication and should happen at full speed for the all-images deadline.
            anchorBodyPublished -> usefulPhysicalLaneCount.coerceAtLeast(1)
            else -> MAX_PRIMARY_LAUNCHES_PER_ACTOR_TURN
        }
        var launchedThisTurn = 0
        laneLoop@ for (laneIndex in 0 until usableLaneCount) {
            while (activeWorks[laneIndex] == null && !adoptionInFlightByLane[laneIndex] &&
                !closeRequested.get() &&
                (phase is SessionPhase.Quarantining || phase is SessionPhase.ExactOpen) &&
                (!primaryAdmissionsSealed || retryAfterAdmissionSeal)
            ) {
                if (exactOpen != null && !NtkStrictSourceOwnershipRegistry.canBeginOperationNow(
                        planBinding.episodePath,
                        exactOpen.manifest.seal.digestSha256,
                        sessionId,
                    )
                ) {
                    // An adoption still owns a completed response's exact operation. Its worker
                    // will post completion and re-enter refill after releasing that slot. Never
                    // block the actor here: it must remain able to accept those completions.
                    return
                }
                val page = selectPrimaryPageActor(laneIndex) ?: break
                if (exactOpen != null) {
                    val seal = exactOpen.manifest.seal
                    val cached = ReaderImageCache.strictCachedPublishedBody(
                        appContext,
                        manga,
                        page.canonicalAsset,
                        seal,
                        page.pageIndex
                    )
                    if (cached != null) {
                        page.primaryStarted = true
                        acceptExactBody(page, cached)
                        continue
                    }
                }
                launchPrimaryFullBodyActor(laneIndex, page)
                launchedThisTurn++
                if (launchedThisTurn >= launchLimitThisTurn) {
                    break@laneLoop
                }
            }
        }
        if (launchedThisTurn >= launchLimitThisTurn &&
            initialWaveCount >= initialQuarantineWaveTargetCount
        ) {
            schedulePrimaryRefillContinuationActor()
        }
    }

    private fun hasRecoverableRetryCandidateActor(): Boolean {
        assertActorThread()
        val now = SystemClock.elapsedRealtime()
        return pages.any { page ->
            !page.primaryStarted && page.terminalEvent == null &&
                page.physicalRetryNotBeforeMs <= now
        }
    }

    /**
     * Re-enqueue rather than recurse. Executor FIFO ordering lets EOF/completion callbacks that
     * arrived during this refill run first, while repeated continuations still fill all eligible
     * physical lanes without waiting for another external event.
     */
    private fun schedulePrimaryRefillContinuationActor() {
        assertActorThread()
        if (primaryRefillContinuationScheduled || actorClosed || closeRequested.get()) return
        primaryRefillContinuationScheduled = true
        executeActor {
            primaryRefillContinuationScheduled = false
            if (!actorClosed && !closeRequested.get()) refillLanesActor()
        }
    }

    private fun selectPrimaryPageActor(laneIndex: Int): PageState? {
        assertActorThread()
        val now = SystemClock.elapsedRealtime()
        if (!isGeometrySealed() && sourceDemand == null) {
            val pendingCount = preGeometryPendingPages.size
            repeat(pendingCount) {
                val pageIndex = preGeometryPendingPages.removeFirst()
                val page = pages[pageIndex]
                if (page.primaryStarted || page.terminalEvent != null ||
                    page.seededExactBody != null ||
                    page.streamedExactBodyPending ||
                    page.physicalRetryNotBeforeMs > now ||
                    (rollingAdmission && pageIndex !in rollingAdmittedPages)
                ) return@repeat
                if (!isBulkSourcePhysicalAdmissionReady(pageIndex)) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
                if (!isColdConnectionCohortEligible(pageIndex)) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
                if (!isRoutePreparationReadyWithoutActorWait(pageIndex)) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
                if (!NtkStrictSourceSchedulerPolicy.hasRouteCapacity(
                        activeRouteCount(page.routeBucketHint),
                        routeOperationLimit,
                    ) || !hasConnectionPoolCapacity(pageIndex)
                ) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
                laneRouteAffinity[laneIndex] = coldConnectionCohortByPage[pageIndex]
                return page
            }
            return null
        }
        val candidates = pages.asSequence()
            .filter {
                !it.primaryStarted && it.terminalEvent == null &&
                    it.seededExactBody == null &&
                    !it.streamedExactBodyPending &&
                    it.physicalRetryNotBeforeMs <= now &&
                    (!rollingAdmission || it.pageIndex in rollingAdmittedPages) &&
                    isBulkSourcePhysicalAdmissionReady(it.pageIndex) &&
                    isColdConnectionCohortEligible(it.pageIndex) &&
                    isRoutePreparationReadyWithoutActorWait(it.pageIndex) &&
                    NtkStrictSourceSchedulerPolicy.hasRouteCapacity(
                        activeRouteCount(it.routeBucketHint),
                        routeOperationLimit,
                    ) && hasConnectionPoolCapacity(it.pageIndex)
            }
            .map { page ->
                NtkStrictSourceSchedulerPolicy.Candidate(
                    page.pageIndex,
                    coldConnectionCohortByPage[page.pageIndex],
                    page.routeBucketHint,
                    priorityFor(page.pageIndex),
                    laneFor(page.pageIndex)
                )
            }.toList()
        val selection = NtkStrictSourceSchedulerPolicy.selectPrimary(
            candidates,
            laneRouteAffinity[laneIndex]
        ) ?: return null
        laneRouteAffinity[laneIndex] = selection.routeBucket
        return pages[selection.candidate.pageIndex]
    }

    private fun isColdConnectionCohortEligible(pageIndex: Int): Boolean {
        // All cold pools already own exactly one real leader at this point. Do not let one long
        // TTFB strand every other page assigned to that pool indefinitely: after a bounded grace
        // period, admit its normal fair share on the same Call.Factory/pool. This creates no extra
        // URL, replica, or duplicate body request. The entry body is also a safe global release
        // boundary because the immutable opening proof already placed a leader in every cohort.
        return NtkStrictInitialWavePolicy.cohortFollowerEligible(
            cohortsOpen = coldConnectionCohortsOpen,
            isLeader = pageIndex in coldConnectionCohortLeaderSet,
            cohortSettled = coldConnectionCohortByPage[pageIndex] in settledColdConnectionCohorts,
            anchorBodyPublished = anchorBodyPublishedActor(),
            firstSettledAtMs = firstColdConnectionCohortSettledAtMs,
            nowMs = SystemClock.elapsedRealtime(),
            unsettledFollowerGraceMs = UNSETTLED_COHORT_FOLLOWER_GRACE_MS,
        )
    }

    private fun anchorBodyPublishedActor(): Boolean {
        assertActorThread()
        val anchor = pages[initialPageIndex]
        return anchor.quarantinedBody != null ||
            anchor.publishedBody != null || anchor.terminalEvent != null
    }

    private fun activeRouteCount(routeBucket: String): Int {
        var count = 0
        for (work in activeWorks) {
            if (work != null && pages[work.pageIndex].routeBucketHint == routeBucket) count++
        }
        return count
    }

    private fun hasConnectionPoolCapacity(pageIndex: Int): Boolean {
        val poolKey = coldConnectionCohortByPage[pageIndex]
        var count = 0
        for (work in activeWorks) {
            if (work != null && coldConnectionCohortByPage[work.pageIndex] == poolKey) count++
        }
        val anchorPool = coldConnectionCohortByPage[initialPageIndex]
        val anchor = pages[initialPageIndex]
        val anchorBodySealed = anchor.quarantinedBody != null || anchor.publishedBody != null ||
            anchor.terminalEvent != null
        val effectiveLimit = if (poolKey == anchorPool && !anchorBodySealed) {
            // Keep the entry image alone on its H2 connection until its complete body reaches EOF.
            // Other origins can fill normally. This prevents the 84 KiB anchor from sharing flow
            // control with twenty bulk pages while preserving full throughput immediately after it.
            1
        } else {
            // Cohort leaders and the per-origin route limit already prevent a single cold socket
            // from consuming the whole ring. A fixed equal share made faster proven CDN pools sit
            // idle while a slower pool owned the final pages; after the anchor EOF, let the fixed
            // 60-worker ring allocate itself by real completion throughput.
            activeWorks.size.coerceAtLeast(1)
        }
        return count < effectiveLimit
    }

    private fun isRoutePreparationReadyWithoutActorWait(pageIndex: Int): Boolean =
        // The start proof requires one real leader per physical pool, so those nine retain their
        // bounded initial wait. Every later page must be skipped until its preparation future is
        // complete: blocking the single source actor here delays response-header callbacks and
        // prevents already-proven pools from refilling for seconds.
        (streamedExactBodies == null && pageIndex in coldConnectionCohortLeaderSet) ||
            quarantineRoutePreparations[pageIndex].isDone

    private fun isBulkSourcePhysicalAdmissionReady(pageIndex: Int): Boolean {
        if (streamedExactBodies == null || bulkSourcePhysicalAdmissionReady.isDone) return true
        // If the click-owned anchor itself failed, it cannot produce the frame that opens the
        // gate. Admit only its exact fallback; every other source page remains held until that
        // fallback is actually presented.
        return pageIndex == initialPageIndex &&
            pageIndex in externallyOwnedPageIndexes &&
            !pages[pageIndex].streamedExactBodyPending
    }

    private fun launchPrimaryFullBodyActor(laneIndex: Int, page: PageState) {
        assertActorThread()
        check(!page.primaryStarted && page.activeWork == null)
        page.physicalAttemptOrdinal++
        check(page.physicalAttemptOrdinal > 0)
        page.physicalRetryScheduled = false
        page.physicalRetryNotBeforeMs = 0L
        val open = phase as? SessionPhase.ExactOpen
        val work = if (open != null) {
            createExactWorkActor(laneIndex, page, open.manifest)
        } else {
            check(phase is SessionPhase.Quarantining)
            createQuarantineWorkActor(laneIndex, page)
        }
        page.primaryStarted = true
        page.activeWork = work
        page.quarantineState = NtkQuarantinePageState.CALL_ACTIVE
        activeWorks[laneIndex] = work
        val pageIndex = page.pageIndex
        val canonicalAsset = page.canonicalAsset
        val operation: () -> PhysicalResult = if (open != null) {
            val manifest = open.manifest
            // Exact admission and the physical Call must share one immutable route. Resolving it
            // again here rebuilt the complete route after the actor had already admitted its
            // ownership lease, delaying lane submission and allowing mutable routing inputs to
            // describe a different route from the one actually used by the Call.
            val route = checkNotNull(work.resolvedRoute) {
                "Exact work lost its admitted immutable source route"
            }
            val context = checkNotNull(work.exactContext)
            ({
                PhysicalResult.Exact(
                    ReaderImageCache.spoolStrictPublishedBody(
                        appContext,
                        manga,
                        canonicalAsset,
                        manifest.seal,
                        pageIndex,
                        route,
                        context,
                        work.cancellation,
                        onMetadata = { },
                        waveRecoveryState = manhwaWaveRecoveryState,
                    )
                )
            })
        } else {
            // The actor already resolved and authenticated this immutable route when it created
            // the operation identity. Re-resolving it here rebuilt the complete episode seal for
            // every page a second time before the first physical wave could run.
            val route = checkNotNull(work.resolvedRoute) {
                "Quarantine work lost its authenticated source route"
            }
            ({
                var transferred = false
                var tempLease: ReaderImageCache.NtkQuarantineFileLease? = null
                try {
                    val openedLease = ReaderImageCache.openQuarantineFileLease(
                        appContext,
                        planBinding,
                        pageIndex,
                        work.operationId
                    ) {
                        val remaining = temporaryFileLeaseCount.decrementAndGet()
                        check(remaining >= 0)
                        executeActor {
                            if (closeRequested.get()) maybeFinishClosedActor()
                        }
                    }
                    tempLease = openedLease
                    temporaryFileLeaseCount.incrementAndGet()
                    val body = ReaderImageCache.spoolQuarantinedEncodedOriginal(
                        appContext,
                        manga,
                        planBinding,
                        pageIndex,
                        canonicalAsset,
                        route,
                        ReaderImageCache.NtkQuarantineCallContext(
                            checkNotNull(work.quarantineLease).identity,
                            work.quarantineLease
                        ),
                        openedLease,
                        work.cancellation,
                        waveRecoveryState = manhwaWaveRecoveryState,
                        responseHeadersSink = {
                            settleColdConnectionCohortLeader(pageIndex)
                        },
                        metadataSink = { },
                    )
                    // EOF/SHA validation is the terminal operation of the physical network lane.
                    // Resident adoption only constructs immutable authority objects; doing it on
                    // this worker made completed bodies wait behind adoption scheduling and kept
                    // the corresponding network lane unavailable for its next GET. The source
                    // actor already has an exact, synchronous resident-adoption path, so transfer
                    // the sealed body immediately and let that path publish it exactly once.
                    val physicalResult = PhysicalResult.Quarantined(body, openedLease)
                    transferred = true
                    physicalResult
                } finally {
                    if (!transferred) tempLease?.close()
                    // beginOperation happens before this physical lambda is submitted. Own its
                    // terminal release here as well as in the spool primitive so failures before
                    // Call creation (directory/I/O setup, cancellation, executor rejection) cannot
                    // strand quarantine ownership and permanently block the close barrier.
                    work.quarantineLease?.close()
                }
            })
        }
        val submitted = executePhysical(work, operation)
        check(submitted)
        recordSubmissionActor(work)
    }

    /** Called by physical lanes; it only posts the one transition back to the source actor. */
    private fun settleColdConnectionCohortLeader(pageIndex: Int) {
        if (pageIndex !in coldConnectionCohortLeaderSet ||
            !settledColdConnectionCohortLeaders.add(pageIndex)
        ) return
        val cohortKey = coldConnectionCohortByPage[pageIndex]
        check(settledColdConnectionCohorts.add(cohortKey)) {
            "Cold connection cohort has more than one leader"
        }
        val readyCount = settledColdConnectionCohortLeaders.size
        executeActor {
            if (actorClosed || closeRequested.get()) return@executeActor
            if (firstColdConnectionCohortSettledAtMs == 0L) {
                firstColdConnectionCohortSettledAtMs = SystemClock.elapsedRealtime()
            }
            // A late cold-cohort header can settle after the entry body has already published.
            // At that point the post-anchor transfer cap may be smaller than the original cohort
            // count (Wi-Fi: six transfers across twenty-four host/pool cohorts). Route through the
            // anchor-aware policy so that late settlement cannot feed that valid state into the
            // pre-anchor-only progressive ramp and terminate the complete source session.
            val anchorBodyPublished = anchorBodyPublishedActor()
            val laneTarget = NtkStrictInitialWavePolicy.forwardLaneTarget(
                usefulPhysicalLaneCountActor(anchorBodyPublished),
                coldConnectionCohortLeaders.size,
                readyCount,
                anchorBodyPublished,
            )
            logSourceEvent(
                "reader_strip_cold_cohort_settled",
                "pageIndex=$pageIndex,readyCount=$readyCount," +
                    "cohortCount=${coldConnectionCohortLeaders.size},laneTarget=$laneTarget," +
                    "cohortKey=$cohortKey",
            )
            if (!coldConnectionCohortsOpen &&
                readyCount >= coldConnectionCohortLeaders.size
            ) {
                coldConnectionCohortsOpen = true
                logSourceEvent(
                    "reader_strip_cold_cohorts_open",
                    "cohortCount=${coldConnectionCohortLeaders.size}," +
                        "pendingCount=${preGeometryPendingPages.size}",
                )
            }
            // Each response header proves one actual H2 pool is usable. Release only that pool's
            // bounded share immediately instead of waiting for the slowest unrelated leader.
            refillLanesActor()
        }
    }

    private fun createQuarantineWorkActor(laneIndex: Int, page: PageState): PrimaryWork {
        assertActorThread()
        check(isRoutePreparationReadyWithoutActorWait(page.pageIndex)) {
            "Source actor attempted to wait for a non-leader route preparation"
        }
        val workId = workSequence.getAndIncrement()
        val operationId = NtkStrictSourceOwnershipRegistry.nextOperationId()
        val preparation = try {
            quarantineRoutePreparations[page.pageIndex].get()
        } catch (wrapped: java.util.concurrent.ExecutionException) {
            throw wrapped.cause ?: wrapped
        }
        val route = preparation.route
        val identity = NtkQuarantineSourceCallIdentity.create(
            sessionId,
            planBinding.discoveryGeneration,
            planBinding.bindingDigest,
            page.pageIndex,
            page.canonicalAsset,
            laneIndex,
            operationId,
            route.routeKeyHash,
            route.callFactoryId,
            preparation.effectiveRequestDigest,
            preparation.canonicalAssetDigest,
        )
        val lease = NtkQuarantineSourceOwnershipRegistry.beginOperation(
            planBinding.episodePath,
            identity
        )
        val queueDepth = if (!isGeometrySealed() && sourceDemand == null) {
            preGeometryPendingPages.size
        } else {
            pages.count { !it.primaryStarted } - 1
        }
        return PrimaryWork(
            workId,
            operationId,
            laneIndex,
            page.pageIndex,
            currentDemandEpoch(),
            queueDepth,
            !isGeometrySealed(),
            WorkMode.QUARANTINE,
            ReaderImageCache.Cancellation(),
            page.physicalAttemptOrdinal,
            lease,
            resolvedRoute = route,
        )
    }

    private fun createExactWorkActor(
        laneIndex: Int,
        page: PageState,
        manifest: NtkAuthoritativeManifest
    ): PrimaryWork {
        assertActorThread()
        check(isRoutePreparationReadyWithoutActorWait(page.pageIndex)) {
            "Source actor attempted to wait for a non-leader exact route preparation"
        }
        val preparation = try {
            quarantineRoutePreparations[page.pageIndex].get()
        } catch (wrapped: java.util.concurrent.ExecutionException) {
            throw wrapped.cause ?: wrapped
        }
        val route = preparation.route
        val work = PrimaryWork(
            workSequence.getAndIncrement(),
            NtkStrictSourceOwnershipRegistry.nextOperationId(),
            laneIndex,
            page.pageIndex,
            currentDemandEpoch(),
            pages.count { !it.primaryStarted } - 1,
            !isGeometrySealed(),
            WorkMode.EXACT,
            ReaderImageCache.Cancellation(),
            page.physicalAttemptOrdinal,
            resolvedRoute = route,
        )
        work.exactContext = beginExactOperationActor(work, manifest, route)
        postPromotionStarted++
        return work
    }

    private fun beginExactOperationActor(
        work: PrimaryWork,
        manifest: NtkAuthoritativeManifest,
        admittedRoute: ReaderImageCache.NtkResolvedSourceRoute? = work.resolvedRoute,
    ): ReaderImageCache.NtkStrictCallContext {
        assertActorThread()
        val seal = manifest.seal
        // Synthetic adoption of an already-finished quarantine body has no prepared work object
        // and may still resolve once for bookkeeping. Every network-backed exact work supplies
        // the preparation route here and reuses it unchanged for its physical Call.
        val route = admittedRoute ?: PRODUCTION_NTK_STRICT_SOURCE_ROUTE_RESOLVER.resolve(
            manga,
            seal,
            work.pageIndex,
            pages[work.pageIndex].canonicalAsset,
        )
        work.quarantineLease?.identity?.let { quarantine ->
            check(route.routeKeyHash == quarantine.routeKeyHash)
            check(route.callFactoryId == quarantine.callFactoryId)
        }
        work.physicalHost = route.requestTemplate.url.host.lowercase()
        val tag = NtkStrictSourceCallTag.strict(
            sessionId,
            seal.digestSha256,
            work.operationId,
            work.laneIndex,
            work.pageIndex,
            work.attemptOrdinal,
        )
        val authority = boundEpisode?.value ?: 0L
        val lease = NtkStrictSourceOwnershipRegistry.beginOperation(
            planBinding.episodePath,
            tag,
            route.routeKeyHash,
            route.callFactoryId,
            attempt = work.attemptOrdinal,
            rangeStart = -1L,
            rangeEnd = -1L,
            manifestRevision = seal.revision,
            demandEpoch = work.demandEpoch,
            launchedPreGeometry = work.launchedPreGeometry,
            metadataQueueDepth = 0,
            bodyQueueDepth = work.primaryQueueDepth,
            workId = work.workId,
            episodeAuthority = authority,
            preclaim = authority == 0L
        )
        return ReaderImageCache.NtkStrictCallContext(tag, lease)
    }

    private fun executePhysical(
        work: PrimaryWork,
        operation: () -> PhysicalResult
    ): Boolean {
        assertActorThread()
        physicalInFlightCount.incrementAndGet()
        return try {
            physicalLanes[work.laneIndex].execute {
                work.physicalStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                val result = runCatching(operation)
                work.physicalCompletedAtNanos = SystemClock.elapsedRealtimeNanos()
                when (val value = result.getOrNull()) {
                    is PhysicalResult.ResidentAdopted ->
                        publishResidentBodyForRender(value.published)
                    is PhysicalResult.Exact -> if (value.body.encodedBytes != null) {
                        publishResidentBodyForRender(value.body)
                    }
                    else -> Unit
                }
                val remaining = physicalInFlightCount.decrementAndGet()
                check(remaining >= 0)
                enqueuePhysicalCompletion(work, result)
            }
            true
        } catch (failure: RejectedExecutionException) {
            physicalInFlightCount.decrementAndGet()
            completePhysicalActor(work, Result.failure(failure))
            false
        }
    }

    /**
     * Coalesces a burst of HTTP EOF callbacks into one actor turn. Apart from executor overhead,
     * every actor callback publishes an immutable debug snapshot; doing that 114 times while the
     * bulk decode wave starts is observable CPU contention with no corresponding UI state change.
     */
    private fun enqueuePhysicalCompletion(
        work: PrimaryWork,
        result: Result<PhysicalResult>,
    ) {
        physicalCompletions.add(PhysicalCompletion(work, result))
        schedulePhysicalCompletionDrain()
    }

    private fun schedulePhysicalCompletionDrain() {
        if (!physicalCompletionDrainScheduled.compareAndSet(false, true)) return
        executeActor(
            onRejected = {
                physicalCompletionDrainScheduled.set(false)
                discardRejectedPhysicalCompletions()
            }
        ) {
            while (true) {
                var completion = physicalCompletions.poll()
                while (completion != null) {
                    completePhysicalActor(
                        completion.work,
                        completion.result,
                        deferSuccessfulMaintenance = true,
                    )
                    completion = physicalCompletions.poll()
                }
                physicalCompletionDrainScheduled.set(false)
                if (physicalCompletions.isEmpty()) break
                if (!physicalCompletionDrainScheduled.compareAndSet(false, true)) break
            }
            refillLanesActor()
            maybeCompletePreparationDrainActor()
            if (closeRequested.get()) maybeFinishClosedActor()
        }
    }

    private fun discardRejectedPhysicalCompletions() {
        var completion = physicalCompletions.poll()
        while (completion != null) {
            completion.work.quarantineLease?.close()
            completion.work.exactContext?.operationLease?.complete()
            when (val value = completion.result.getOrNull()) {
                is PhysicalResult.Quarantined -> value.tempLease.close()
                is PhysicalResult.ResidentAdopted -> value.tempLease.close()
                else -> Unit
            }
            completion = physicalCompletions.poll()
        }
    }

    /**
     * Publishes a render descriptor from the validating physical lane. The descriptor owns the
     * exact resident bytes when available and otherwise opens the already-published immutable
     * file. Streamed click-owned bodies commonly use the latter form, so rejecting file-backed
     * publications would leave an adjacent runway stuck on its seeded first page.
     */
    private fun publishResidentBodyForRender(
        published: ReaderImageCache.NtkStrictPublishedBody
    ) {
        if (closeRequested.get()) return
        val bytes = published.encodedBytes
        val metadata = published.metadata
        val manifest = residentAdoptionManifest.get() ?: return
        if (metadata.manifestDigest != manifest.seal.digestSha256 ||
            metadata.pageIndex !in pages.indices ||
            metadata.canonicalAsset != pages[metadata.pageIndex].canonicalAsset ||
            (bytes != null && bytes.size.toLong() != published.proof.encodedLength)
        ) throw NtkSourceIdentityException("Resident render publication authority changed")
        val sourceKey = metadata.strictSourceKey
        val descriptor = NtkStrictBodyDescriptor(
            bodyDescriptorSequence.getAndIncrement(),
            sourceKey,
            metadata,
            published.proof,
        ) {
            NtkStrictBodyLease(
                sourceKey,
                published.file,
                metadata.sourceWidth,
                metadata.sourceHeight,
                metadata,
                published.proof,
                bytes,
                published.predecodedOriginal,
                release = {}
            )
        }
        if (residentRenderDescriptors.putIfAbsent(metadata.pageIndex, descriptor) != null) return
        residentBodyListeners.values.forEach { listener ->
            runCatching { listener.onResidentBody(descriptor) }
        }
    }

    private fun recordWifiWebtoonAdaptiveSuccessActor(work: PrimaryWork) {
        assertActorThread()
        val adaptive = wifiWebtoonAdaptiveLanes ?: return
        if (work.mode != WorkMode.EXACT || work.pageIndex < 2 || closeRequested.get()) return
        val anchor = pages[initialPageIndex]
        if (anchor.quarantinedBody == null && anchor.publishedBody == null) return
        adaptive.recordSuccess(
            pageIndex = work.pageIndex,
            attemptOrdinal = work.attemptOrdinal,
            physicalStartedAtNanos = work.physicalStartedAtNanos,
            physicalCompletedAtNanos = work.physicalCompletedAtNanos,
        )?.let(::logWifiWebtoonAdaptiveTransitionActor)
    }

    private fun recordWifiWebtoonAdaptiveFailureActor(work: PrimaryWork) {
        assertActorThread()
        val adaptive = wifiWebtoonAdaptiveLanes ?: return
        if (work.mode != WorkMode.EXACT || work.pageIndex < 2 || closeRequested.get()) return
        val anchor = pages[initialPageIndex]
        if (anchor.quarantinedBody == null && anchor.publishedBody == null) return
        adaptive.recordFailure(
            pageIndex = work.pageIndex,
            attemptOrdinal = work.attemptOrdinal,
        )?.let(::logWifiWebtoonAdaptiveTransitionActor)
    }

    private fun logWifiWebtoonAdaptiveTransitionActor(
        transition: NtkWifiWebtoonAdaptiveLaneState.Transition,
    ) {
        assertActorThread()
        logSourceEvent(
            "reader_strip_wifi_webtoon_adaptive_target",
            "oldTarget=${transition.oldTarget},newTarget=${transition.newTarget}," +
                "frozen=${transition.frozen},reason=${transition.reason}," +
                "pageIndex=${transition.pageIndex},sessionSlot=${transition.sessionSlot}," +
                "elapsedMs=${transition.elapsedMs}," +
                "fastSamples=${transition.fastSamples.joinToString("|")}",
        )
    }

    private fun completePhysicalActor(
        work: PrimaryWork,
        result: Result<PhysicalResult>,
        deferSuccessfulMaintenance: Boolean = false,
    ) {
        assertActorThread()
        val page = pages[work.pageIndex]
        if (activeWorks[work.laneIndex]?.workId != work.workId) {
            when (val stale = result.getOrNull()) {
                is PhysicalResult.Quarantined -> stale.tempLease.close()
                is PhysicalResult.ResidentAdopted -> stale.tempLease.close()
                else -> Unit
            }
            if (closeRequested.get()) maybeFinishClosedActor()
            return
        }
        activeWorks[work.laneIndex] = null
        page.activeWork = null
        page.physicalStartedAtNanos = work.physicalStartedAtNanos
        page.physicalCompletedAtNanos = work.physicalCompletedAtNanos
        page.physicalHost = work.physicalHost.ifBlank {
            work.resolvedRoute?.requestTemplate?.url?.host?.lowercase().orEmpty()
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            recordWifiWebtoonAdaptiveFailureActor(work)
            // executePhysical can reject before the operation lambda (and its finally block) runs.
            // Completing both possible ownership modes here closes that last pre-call gap.
            work.quarantineLease?.close()
            work.exactContext?.operationLease?.complete()
            if (closeRequested.get()) {
                page.quarantineState = NtkQuarantinePageState.FAILED
                maybeFinishClosedActor()
            } else {
                val recoverableWithinLedger =
                    NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                        failure,
                        work.attemptOrdinal
                    )
                val shouldRetry = recoverableWithinLedger ||
                    NtkStrictSourceFailurePolicy.shouldRetryPhysicalFailure(
                        failure,
                        work.attemptOrdinal,
                        page.physicalRecoveryCycle
                    )
                if (shouldRetry) {
                // Keep the episode owner and sealed manifest alive. The page becomes eligible for
                // the current viewport demand with a fresh Call/lease. A finite ledger may only
                // open the explicitly bounded number of delayed recovery cycles.
                if (work.attemptOrdinal >= NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS) {
                    page.physicalAttemptOrdinal = 0
                    page.physicalRecoveryCycle++
                }
                val delayMs = NtkStrictSourceFailurePolicy.retryDelayMs(
                    work.attemptOrdinal,
                    page.physicalRecoveryCycle,
                )
                page.primaryStarted = false
                page.quarantineState = NtkQuarantinePageState.QUEUED
                page.physicalRetryNotBeforeMs = SystemClock.elapsedRealtime() + delayMs
                if (!isGeometrySealed() && sourceDemand == null &&
                    !preGeometryPendingPages.contains(page.pageIndex)
                ) {
                    preGeometryPendingPages.addFirst(page.pageIndex)
                }
                logSourceEvent(
                    "reader_strip_source_operation_retry",
                    "pageIndex=${page.pageIndex},attempt=${work.attemptOrdinal}," +
                        "nextAttempt=${page.physicalAttemptOrdinal + 1}," +
                        "recoveryCycle=${page.physicalRecoveryCycle},delayMs=$delayMs," +
                        "admitted=${!rollingAdmission || page.pageIndex in rollingAdmittedPages}," +
                        "error=${failure.javaClass.simpleName}"
                )
                schedulePhysicalRetryActor(page, delayMs)
                if (delayMs == 0L) refillLanesActor()
                } else {
                    page.quarantineState = NtkQuarantinePageState.FAILED
                    failSessionActor(failure, page)
                }
            }
            return
        }
        recordWifiWebtoonAdaptiveSuccessActor(work)
        when (val value = result.getOrThrow()) {
            is PhysicalResult.Quarantined -> {
                page.tempLease = value.tempLease
                page.quarantinedBody = value.body
                page.quarantineMetadata = value.body.metadataEvidence
                page.quarantineState = NtkQuarantinePageState.BODY_SEALED
                if (phase is SessionPhase.ExactOpen) {
                    if (value.body.encodedBytes != null) {
                        adoptResidentBodyActor(page, value.body, value.tempLease, work.exactContext)
                    } else {
                        scheduleAdoptionActor(page, value.body, work.exactContext)
                    }
                }
            }
            is PhysicalResult.ResidentAdopted -> {
                page.tempLease = value.tempLease
                page.quarantinedBody = value.body
                page.quarantineMetadata = value.body.metadataEvidence
                page.quarantineState = NtkQuarantinePageState.BODY_SEALED
                completeResidentAdoptionActor(
                    page,
                    value.body,
                    value.tempLease,
                    value.published,
                    work.exactContext
                )
            }
            is PhysicalResult.Exact -> acceptExactBody(page, value.body)
            else -> error("Unexpected strict source result ${value.javaClass.name}")
        }
        if (!deferSuccessfulMaintenance) {
            refillLanesActor()
            maybeCompletePreparationDrainActor()
            if (closeRequested.get()) maybeFinishClosedActor()
        }
    }

    private fun schedulePhysicalRetryActor(page: PageState, delayMs: Long) {
        assertActorThread()
        if (delayMs <= 0L || page.physicalRetryScheduled ||
            actorClosed || closeRequested.get()
        ) return
        page.physicalRetryScheduled = true
        val pageIndex = page.pageIndex
        retryHandler.postDelayed({
            executeActor {
                val current = pages.getOrNull(pageIndex) ?: return@executeActor
                current.physicalRetryScheduled = false
                if (actorClosed || closeRequested.get() || current.terminalEvent != null ||
                    current.publishedBody != null || current.bodyEvent != null
                ) return@executeActor
                if (SystemClock.elapsedRealtime() < current.physicalRetryNotBeforeMs) {
                    schedulePhysicalRetryActor(
                        current,
                        current.physicalRetryNotBeforeMs - SystemClock.elapsedRealtime(),
                    )
                    return@executeActor
                }
                refillLanesActor()
            }
        }, delayMs)
    }

    private fun adoptAllSealedBodiesActor() {
        assertActorThread()
        for (page in pages) {
            val body = page.quarantinedBody ?: continue
            if (page.publishedBody != null) continue
            val context = page.activeWork?.exactContext ?: page.adoptedExactContext
            val tempLease = page.tempLease
            if (body.encodedBytes != null && tempLease != null) {
                adoptResidentBodyActor(page, body, tempLease, context)
            } else {
                scheduleAdoptionActor(page, body, context)
            }
        }
    }

    /**
     * Memory publication has no file rename or sidecar work. Keeping the old lane round-trip for
     * it doubled actor callbacks and delayed the last descriptor long after every HTTP body EOF.
     */
    private fun adoptResidentBodyActor(
        page: PageState,
        body: NtkQuarantinedBody,
        tempLease: ReaderImageCache.NtkQuarantineFileLease,
        activeContext: ReaderImageCache.NtkStrictCallContext?
    ) {
        assertActorThread()
        check(body.encodedBytes != null)
        val manifest = exactOpenManifestActor()
        var context = activeContext
        if (context == null) {
            val synthetic = PrimaryWork(
                workSequence.getAndIncrement(),
                body.callIdentity.operationId,
                body.callIdentity.laneIndex,
                page.pageIndex,
                0L,
                0,
                true,
                WorkMode.QUARANTINE,
                ReaderImageCache.Cancellation(),
                page.physicalAttemptOrdinal.coerceAtLeast(1)
            )
            context = beginExactOperationActor(synthetic, manifest)
        }
        val proof = NtkQuarantineAdoptionProof.create(planBinding, body, manifest)
        val published = ReaderImageCache.adoptQuarantinedEncodedOriginal(
            appContext,
            manga,
            manifest.seal,
            page.pageIndex,
            body,
            proof
        )
        publishResidentBodyForRender(published)
        completeResidentAdoptionActor(page, body, tempLease, published, context)
    }

    private fun completeResidentAdoptionActor(
        page: PageState,
        body: NtkQuarantinedBody,
        tempLease: ReaderImageCache.NtkQuarantineFileLease,
        published: ReaderImageCache.NtkStrictPublishedBody,
        activeContext: ReaderImageCache.NtkStrictCallContext?
    ) {
        assertActorThread()
        val startedAt = SystemClock.elapsedRealtime()
        val acceptedContext = checkNotNull(activeContext) {
            "Resident lane publication requires its promoted exact operation"
        }
        var retirementScheduled = false
        try {
            tempLease.consume()
            if (page.tempLease === tempLease) page.tempLease = null
            page.adoptedExactContext = null
            page.quarantinedBody = null
            page.quarantineState = NtkQuarantinePageState.EXACT_OWNED
            // The render owner needs only the already validated metadata/proof/body. Publish it
            // before producing the detailed accounting record for the completed source call.
            // The synthetic promoted operation still owns this physical lane until the detailed
            // success record below retires its lease. Publishing the fourth adjacent runway body
            // can open suffix admission, so suppress that refill until the lane ledger is closed.
            acceptExactBody(page, published, releaseAdjacentRunway = false)
            if (NtkStrictSourceOperationTelemetryPolicy.shouldLogSuccessfulAdoption(page.pageIndex)) {
                logSourceEvent(
                    "reader_strip_source_adoption",
                    "pageIndex=${page.pageIndex},elapsedMs=${SystemClock.elapsedRealtime() - startedAt}," +
                        "laneIndex=${body.callIdentity.laneIndex},storage=resident"
                )
            }
            completeResidentOperationActor(body, published, acceptedContext)
            retirementScheduled = true
            maybeReleaseAdjacentPrefetchAfterRunwayActor("resident_body_published")
        } catch (failure: Throwable) {
            if (!retirementScheduled) acceptedContext.operationLease.complete()
            page.adoptedExactContext = null
            tempLease.close()
            if (page.tempLease === tempLease) page.tempLease = null
            page.quarantineState = NtkQuarantinePageState.FAILED
            throw failure
        }
    }

    /**
     * Resident publication has no blocking file work. Retire its exact operation in the actor
     * transaction that accepted the immutable body, so an already-finished HTTP response does not
     * keep a physical lane idle for a second executor -> actor round trip. The operation ledger is
     * still closed before [refillLanesActor] can admit the next GET on this lane.
     */
    private fun completeResidentOperationActor(
        body: NtkQuarantinedBody,
        published: ReaderImageCache.NtkStrictPublishedBody,
        context: ReaderImageCache.NtkStrictCallContext
    ) {
        assertActorThread()
        context.operationLease.complete(
            httpCode = 200,
            protocol = "adopted-resident-quarantine",
            responseBytes = body.encodedLength,
            metadataWitnessBytes = body.metadataEvidence.byteWitnessLength,
            metadataAcquisition = NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY.name,
            imageFormat = body.metadataEvidence.imageFormat,
            contentRangeTotal = body.encodedLength,
            succeeded = true,
            partialBodyOperation = false,
            responseIdentityDigest = body.metadataEvidence.responseIdentityDigest,
            metadataBindingDigest = published.metadata.metadataBindingDigest,
            bodyDigest = body.encodedSha256
        )
    }

    private fun acceptSeededExactBodiesActor() {
        assertActorThread()
        for (page in pages) {
            val seeded = page.seededExactBody ?: continue
            if (adjacentPrefetch) publishResidentBodyForRender(seeded)
            acceptExactBody(page, seeded)
            page.seededExactBody = null
            page.primaryStarted = true
            page.quarantineState = NtkQuarantinePageState.EXACT_OWNED
            logSourceEvent(
                "reader_strip_source_seeded_exact_body",
                "pageIndex=${page.pageIndex},bytes=${seeded.proof.encodedLength}," +
                    "acquisition=${seeded.metadata.authority.acquisition.name}",
            )
        }
    }

    /**
     * A sealed response no longer occupies a socket, but its exact-cache rename, sidecar publish,
     * and proof checks used to run serially on the source actor. Run that independent per-page
     * publication on the response's prestarted lane. Exact ownership remains active until the
     * actor accepts the result, so this cannot increase the twelve-call physical network cap.
     */
    private fun scheduleAdoptionActor(
        page: PageState,
        body: NtkQuarantinedBody,
        activeContext: ReaderImageCache.NtkStrictCallContext?
    ) {
        assertActorThread()
        check(page.quarantineState != NtkQuarantinePageState.EXACT_ADOPTING)
        val adoptionLaneIndex = body.callIdentity.laneIndex
        check(!adoptionInFlightByLane[adoptionLaneIndex])
        adoptionInFlightByLane[adoptionLaneIndex] = true
        val manifest = exactOpenManifestActor()
        page.quarantineState = NtkQuarantinePageState.EXACT_ADOPTING
        var context = activeContext
        var adoptionCountOwnedBySchedule = false
        try {
            if (context == null) {
                val synthetic = PrimaryWork(
                    workSequence.getAndIncrement(),
                    body.callIdentity.operationId,
                    body.callIdentity.laneIndex,
                    page.pageIndex,
                    0L,
                    0,
                    true,
                    WorkMode.QUARANTINE,
                    ReaderImageCache.Cancellation(),
                    page.physicalAttemptOrdinal.coerceAtLeast(1)
                )
                context = beginExactOperationActor(synthetic, manifest)
            }
            page.adoptedExactContext = context
            val acceptedContext = checkNotNull(context)
            val tempLease = checkNotNull(page.tempLease) {
                "Quarantine adoption requires the sealed file lease"
            }
            activeAdoptionTaskCount.incrementAndGet()
            adoptionCountOwnedBySchedule = true
            physicalLanes[adoptionLaneIndex].execute {
                val startedAt = SystemClock.elapsedRealtime()
                val result = runCatching {
                    val proof = NtkQuarantineAdoptionProof.create(planBinding, body, manifest)
                    AdoptionResult(
                        ReaderImageCache.adoptQuarantinedEncodedOriginal(
                            appContext,
                            manga,
                            manifest.seal,
                            page.pageIndex,
                            body,
                            proof
                        ),
                        SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                result.getOrNull()?.let { adoption ->
                    publishResidentBodyForRender(adoption.published)
                }
                executeActor(
                    onRejected = {
                        result.getOrNull()?.let { tempLease.consume() } ?: tempLease.close()
                        acceptedContext.operationLease.complete()
                        val remaining = activeAdoptionTaskCount.decrementAndGet()
                        check(remaining >= 0)
                    }
                ) {
                    completeAdoptionActor(
                        page.pageIndex,
                        body,
                        tempLease,
                        acceptedContext,
                        result,
                    )
                }
            }
            adoptionCountOwnedBySchedule = false
        } catch (failure: Throwable) {
            if (adoptionCountOwnedBySchedule) {
                val remaining = activeAdoptionTaskCount.decrementAndGet()
                check(remaining >= 0)
            }
            adoptionInFlightByLane[adoptionLaneIndex] = false
            context?.operationLease?.complete()
            page.adoptedExactContext = null
            page.tempLease?.close()
            page.tempLease = null
            page.quarantineState = NtkQuarantinePageState.FAILED
            throw failure
        }
    }

    private fun completeAdoptionActor(
        pageIndex: Int,
        body: NtkQuarantinedBody,
        tempLease: ReaderImageCache.NtkQuarantineFileLease,
        context: ReaderImageCache.NtkStrictCallContext,
        result: Result<AdoptionResult>,
    ) {
        assertActorThread()
        val page = pages[pageIndex]
        var accepted = false
        try {
            val adoption = result.getOrThrow()
            tempLease.consume()
            if (page.tempLease === tempLease) page.tempLease = null
            context.operationLease.complete(
                httpCode = 200,
                protocol = "adopted-quarantine",
                responseBytes = body.encodedLength,
                metadataWitnessBytes = body.metadataEvidence.byteWitnessLength,
                metadataAcquisition =
                    NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY.name,
                imageFormat = body.metadataEvidence.imageFormat,
                contentRangeTotal = body.encodedLength,
                succeeded = true,
                partialBodyOperation = false,
                responseIdentityDigest = body.metadataEvidence.responseIdentityDigest,
                metadataBindingDigest = adoption.published.metadata.metadataBindingDigest,
                bodyDigest = body.encodedSha256
            )
            page.adoptedExactContext = null
            page.quarantinedBody = null
            page.quarantineState = NtkQuarantinePageState.EXACT_OWNED
            if (NtkStrictSourceOperationTelemetryPolicy.shouldLogSuccessfulAdoption(pageIndex)) {
                logSourceEvent(
                    "reader_strip_source_adoption",
                    "pageIndex=$pageIndex,elapsedMs=${adoption.elapsedMs}," +
                        "laneIndex=${body.callIdentity.laneIndex}"
                )
            }
            acceptExactBody(page, adoption.published)
            accepted = true
        } catch (failure: Throwable) {
            context.operationLease.complete()
            page.adoptedExactContext = null
            tempLease.close()
            if (page.tempLease === tempLease) page.tempLease = null
            page.quarantineState = NtkQuarantinePageState.FAILED
            throw failure
        } finally {
            val remaining = activeAdoptionTaskCount.decrementAndGet()
            check(remaining >= 0)
            adoptionInFlightByLane[body.callIdentity.laneIndex] = false
            if (accepted) {
                refillLanesActor()
                maybeCompletePreparationDrainActor()
                if (closeRequested.get()) maybeFinishClosedActor()
            }
        }
    }

    private fun acceptExactBody(
        page: PageState,
        published: ReaderImageCache.NtkStrictPublishedBody,
        releaseAdjacentRunway: Boolean = true,
    ) {
        assertActorThread()
        if (phase !is SessionPhase.ExactOpen) {
            throw NtkSourceIdentityException("Exact body publication gate is closed")
        }
        // NtkStrictPublishedBody validated this immutable authority at construction.
        val metadata = published.metadata
        val currentMetadata = page.metadata
        if (currentMetadata != null && !currentMetadata.hasSameAuthority(metadata)) {
            throw NtkSourceIdentityException("Strict metadata ledger mutation")
        }
        if (currentMetadata == null) {
            page.metadata = metadata
            metadataPublishedCount++
            check(metadataPublishedCount in 1..pages.size)
            val metadataEvent = SourceEvent.MetadataReady(metadata)
            page.metadataEvent = metadataEvent
            appendAndEmit(metadataEvent)
            val now = SystemClock.elapsedRealtime()
            if (metadataFirstAtMs == 0L) metadataFirstAtMs = now
        }
        val currentBody = page.publishedBody
        if (currentBody != null) {
            check(currentBody.proof == published.proof)
            check(publishedBodyPins.retainedCount() == bodyPublishedCount)
            return
        }
        val bodySeal = exactOpenManifestActor().seal
        val cachePin = ReaderImageCache.leaseAcceptedStrictPublishedBody(
            manga,
            page.canonicalAsset,
            published
        )
        var pinRetained = false
        try {
            publishedBodyPins.retain(page.pageIndex, cachePin)
            pinRetained = true
        } finally {
            if (!pinRetained) cachePin.close()
        }
        page.publishedBody = published
        manhwaWaveRecoveryState?.markValidatedBody(page.pageIndex)
        bodyPublishedCount++
        check(bodyPublishedCount in 1..pages.size)
        check(publishedBodyPins.retainedCount() == bodyPublishedCount)
        val descriptor = NtkStrictBodyDescriptor(
            bodyDescriptorSequence.getAndIncrement(),
            metadata.strictSourceKey,
            metadata,
            published.proof,
            {
                openBodyLease(
                    metadata.strictSourceKey,
                    page.pageIndex,
                    page.canonicalAsset,
                    bodySeal,
                    published
                )
            }
        )
        page.bodyDescriptor = descriptor
        val bodyEvent = SourceEvent.BodyPublished(descriptor)
        page.bodyEvent = bodyEvent
        appendAndEmit(bodyEvent)
        if (releaseAdjacentRunway) {
            maybeReleaseAdjacentPrefetchAfterRunwayActor("body_published")
        }
        if (rollingAdmission && page.pageIndex == initialPageIndex) {
            pendingRollingAdmittedPages?.let { demandedPages ->
                rollingAdmittedPages = demandedPages
                pendingRollingAdmittedPages = null
                logSourceEvent(
                    "reader_strip_source_anchor_released_runway",
                    "pageIndex=${page.pageIndex},admitted=${rollingAdmittedPages.size}"
                )
            }
        }
        sealPrimaryAdmissionsWhenMetadataComplete()
        if (rollingAdmission && initialPageIndex > 0 && !forwardResumeReadyLogged &&
            (initialPageIndex until pages.size).all { pages[it].bodyEvent != null }
        ) {
            forwardResumeReadyLogged = true
            logSourceEvent(
                "reader_strip_source_forward_ready",
                "initialPage=$initialPageIndex,forwardExpected=${pages.size - initialPageIndex}," +
                    "forwardSucceeded=${pages.count { it.pageIndex >= initialPageIndex && it.bodyEvent != null }}," +
                    "beforeAnchorBodies=${pages.count { it.pageIndex < initialPageIndex && it.bodyEvent != null }}," +
                    "pageCount=${pages.size}"
            )
        }
        if (bodyPublishedCount == pages.size) {
            LogReady.log(this, metadata.manifestDigest, pages.size)
        }
    }

    private object LogReady {
        fun log(session: NtkStrictSourceSession, manifestDigest: String, count: Int) {
            session.logPhysicalCohortSummary()
            session.logSourceEvent(
                "reader_strip_source_all_ready",
                "manifestDigest=$manifestDigest,pageCount=$count," +
                    "physicalCallCount=${NtkQuarantineSourceOwnershipRegistry.snapshot(
                        session.planBinding.episodePath,
                        session.planBinding.discoveryGeneration,
                        session.sessionId,
                    )?.physicalCallCount ?: count}," +
                    "duplicatePhysicalCallCount=${NtkQuarantineSourceOwnershipRegistry.snapshot(
                        session.planBinding.episodePath,
                        session.planBinding.discoveryGeneration,
                        session.sessionId,
                    )?.duplicatePhysicalCallCount ?: 0}"
            )
        }
    }

    /**
     * One post-EOF aggregate identifies the real CDN/pool tail without emitting one log line per
     * image (which itself perturbs a 120-call cold wave). Times are relative to the first physical
     * image GET and the immutable cohort key matches the host-local connection sharding policy.
     */
    private fun logPhysicalCohortSummary() {
        assertActorThread()
        val timedPages = pages.filter {
            it.physicalStartedAtNanos > 0L &&
                it.physicalCompletedAtNanos >= it.physicalStartedAtNanos
        }
        if (timedPages.isEmpty()) return
        val originNanos = timedPages.minOf(PageState::physicalStartedAtNanos)
        val summaries = timedPages
            .groupBy { page ->
                NtkStrictInitialWavePolicy.coldConnectionCohortKey(
                    planBinding.episodePath,
                    page.pageIndex,
                    page.routeBucketHint,
                    webtoonConnectionShardCount,
                )
            }
            .toSortedMap()
            .map { (cohort, cohortPages) ->
                val tail = cohortPages.maxBy(PageState::physicalCompletedAtNanos)
                val slowest = cohortPages.maxBy {
                    it.physicalCompletedAtNanos - it.physicalStartedAtNanos
                }
                val bytes = cohortPages.sumOf { it.publishedBody?.proof?.encodedLength ?: 0L }
                val host = cohortPages.firstNotNullOfOrNull {
                    it.physicalHost.takeIf(String::isNotBlank)
                }.orEmpty()
                val firstStartMs = (cohortPages.minOf(PageState::physicalStartedAtNanos) -
                    originNanos) / 1_000_000L
                val tailEndMs = (tail.physicalCompletedAtNanos - originNanos) / 1_000_000L
                val slowestMs = (slowest.physicalCompletedAtNanos -
                    slowest.physicalStartedAtNanos) / 1_000_000L
                "$cohort{$host,n=${cohortPages.size},b=$bytes,s=$firstStartMs," +
                    "e=$tailEndMs,max=$slowestMs,tail=${tail.pageIndex}}"
            }
            .joinToString(";")
        logSourceEvent(
            "reader_strip_source_physical_cohort_summary",
            "pageCount=${timedPages.size},cohorts=${summaries}",
        )
    }

    private fun sealPrimaryAdmissionsWhenMetadataComplete() {
        assertActorThread()
        if (primaryAdmissionsSealed || metadataPublishedCount != pages.size) return
        primaryAdmissionsSealed = true
        metadataAllAtMs = SystemClock.elapsedRealtime()
        val seal = exactOpenManifestActor().seal
        check(NtkStrictSourceOwnershipRegistry.sealPrimaryAdmissions(
            planBinding.episodePath,
            seal.digestSha256,
            sessionId
        ))
        logSourceEvent(
            "reader_strip_source_metadata_all_ready",
            "manifestDigest=${seal.digestSha256},pageCount=${pages.size}," +
                "metadataAllAt=$metadataAllAtMs"
        )
    }

    private fun openBodyLease(
        key: NtkStrictSourceKey,
        pageIndex: Int,
        canonicalAsset: String,
        seal: NtkEpisodeManifestSeal,
        published: ReaderImageCache.NtkStrictPublishedBody
    ): NtkStrictBodyLease {
        val leaseId = synchronized(bodyLeaseAdmissionLock) {
            val view = publishedView.get()
            check(view.exactOpen && view.exactManifestDigest == seal.digestSha256 &&
                !closeRequested.get()
            )
            activeBodyLeaseCount.incrementAndGet()
            bodyLeaseSequence.getAndIncrement()
        }
        val lease = try {
            ReaderImageCache.leaseAcceptedStrictPublishedBody(
                manga,
                canonicalAsset,
                published
            )
        } catch (failure: Throwable) {
            val remaining = activeBodyLeaseCount.decrementAndGet()
            check(remaining >= 0)
            executeActor {
                if (closeRequested.get()) maybeFinishClosedActor()
            }
            throw failure
        }
        val released = AtomicBoolean(false)
        return NtkStrictBodyLease(
            key,
            lease.file,
            published.metadata.sourceWidth,
            published.metadata.sourceHeight,
            published.metadata,
            published.proof,
            published.encodedBytes,
            published.predecodedOriginal,
        ) {
            if (released.compareAndSet(false, true)) {
                lease.close()
                val remaining = activeBodyLeaseCount.decrementAndGet()
                check(remaining >= 0)
                executeActor {
                    logSourceEvent(
                        "reader_strip_source_lease_released",
                        "leaseId=$leaseId,pageIndex=$pageIndex," +
                            "activeBodyLeaseCount=$remaining"
                    )
                    maybeCompletePreparationDrainActor()
                    if (closeRequested.get()) maybeFinishClosedActor()
                }
            }
        }
    }

    private fun maybeCompletePreparationDrainActor() {
        assertActorThread()
        if (preparationDrainCompletions.isEmpty() || closeRequested.get() ||
            boundEpisode == null || !primaryAdmissionsSealed ||
            pages.any { it.bodyEvent == null } || activeWorks.any { it != null } ||
            physicalInFlightCount.get() != 0 || activeBodyLeaseCount.get() != 0 ||
            activeAdoptionTaskCount.get() != 0
        ) return
        val seal = exactOpenManifestActor().seal
        val registry = NtkStrictSourceOwnershipRegistry.snapshot(planBinding.episodePath) ?: return
        if (registry.activeTotal != 0) return
        val proof = NtkSourceDrainProof(
            seal.digestSha256,
            pages.size,
            pages.count { it.bodyEvent != null },
            registry.activeTotal,
            registry.unleasedCallCount,
            registry.partialBodyOperationCount,
            activeBodyLeaseCount.get(),
            System.nanoTime().coerceAtLeast(1L)
        )
        check(proof.isExact)
        val callbacks = preparationDrainCompletions.toList()
        preparationDrainCompletions.clear()
        callbacks.forEach { it(proof) }
    }

    private fun failSessionActor(failure: Throwable, failedPage: PageState? = null) {
        assertActorThread()
        if (!closeBodyLeaseAdmissions()) return
        if (phase is SessionPhase.ExactOpen) {
            val page = failedPage ?: pages.firstOrNull { it.terminalEvent == null }
            if (page != null) {
                val event = SourceEvent.TerminalFailure(
                    page.pageIndex,
                    NtkSourcePhase.BODY,
                    failure
                )
                page.terminalEvent = event
                appendAndEmit(event)
            }
        }
        phase = SessionPhase.Closing(currentExactIdentityActor(), failure)
        manhwaWaveRecoveryState?.close()
        NtkQuarantineSourceOwnershipRegistry.closeAdmissions(
            planBinding.episodePath,
            planBinding.discoveryGeneration,
            sessionId
        )
        activeWorks.filterNotNull().forEach { it.cancellation.cancel() }
        routePreparationLanes.forEach(ExecutorService::shutdown)
        physicalLanes.forEach(ExecutorService::shutdown)
        runCatching { onTerminalFailure(failure) }
        maybeFinishClosedActor()
    }

    private fun maybeFinishClosedActor() {
        assertActorThread()
        if (!closeRequested.get() || activeWorks.any { it != null } ||
            physicalInFlightCount.get() != 0 || activeBodyLeaseCount.get() != 0 ||
            activeAdoptionTaskCount.get() != 0
        ) return
        pages.forEach { page ->
            page.adoptedExactContext?.operationLease?.complete()
            page.adoptedExactContext = null
            page.tempLease?.close()
            page.tempLease = null
        }
        if (temporaryFileLeaseCount.get() != 0 ||
            remainingActorCallbacksExcludingCurrent() != 0
        ) return
        val quarantineOwnership = quarantineOwnershipSnapshot()
        if (quarantineOwnership != null) {
            checkOwnershipIdentity(quarantineOwnership)
            if (quarantineOwnership.activeCalls != 0) return
        }
        val exactIdentity = currentExactIdentityActor()
        if (exactIdentity != null && NtkStrictSourceOwnershipRegistry.activeOperationCount(
                planBinding.episodePath,
                exactIdentity.manifest.seal.digestSha256,
                sessionId
            ) != 0
        ) return
        // Freeze callback admission in the same critical section that proves no queued callback
        // remains. Without this boundary, a lease/worker callback can arrive between the earlier
        // zero check and the close-proof construction below.
        if (!actorCallbackGate.closeAdmissionsIfDrained(actorCallbackDepthValue())) return
        if (closeFinalized.get()) return
        val terminalCause = (phase as? SessionPhase.Closing)?.cause
        // This is the final retirement transaction and every descriptor lease has drained.
        exactIdentity?.manifest?.seal?.let { retirementSeal ->
            pages.forEach { page ->
                page.publishedBody?.let { published ->
                    ReaderImageCache.persistAcceptedStrictPublishedBodyAsync(
                        appContext,
                        manga,
                        retirementSeal,
                        page.pageIndex,
                        page.canonicalAsset,
                        published
                    )
                }
            }
        }
        // Any private decode that was never transferred to ReaderSession belongs to this viewer
        // generation and must not survive exact-source retirement.
        pages.forEach { page -> page.publishedBody?.predecodedOriginal?.close() }
        // Release cache pins before irreversible registry/finalized state so an exceptional
        // close can rearm this actor instead of wedging a half-published close barrier.
        publishedBodyPins.releaseAtFinalRetirement(activeBodyLeaseCount.get())
        check(publishedBodyPins.retainedCount() == 0)
        // Ownership release is part of the close transaction. Never publish Closed/finalized
        // before the registry has proved that every real quarantine operation reached terminal.
        check(NtkQuarantineSourceOwnershipRegistry.release(
            planBinding.episodePath,
            planBinding.discoveryGeneration,
            sessionId
        ))
        check(closeFinalized.compareAndSet(false, true))
        actorClosed = true
        listeners.clear()
        residentBodyListeners.clear()
        residentRenderDescriptors.clear()
        pages.forEach { page ->
            page.quarantineState = NtkQuarantinePageState.CLOSED
        }
        phase = SessionPhase.Closed
        if (terminalCause != null) {
            ViewerTelemetry.terminalImagePipelineSummary(planBinding.episodePath)
        }
        if (exactIdentity == null) {
            val snapshot = NtkQuarantineSourceOwnershipRegistry.snapshot(
                planBinding.episodePath,
                planBinding.discoveryGeneration,
                sessionId,
            )
            val barrier = NtkQuarantineCloseBarrierProof(
                planBinding.episodePath,
                planBinding.discoveryGeneration,
                planBinding.bindingDigest,
                sessionId,
                snapshot?.activeCalls ?: 0,
                physicalInFlightCount.get(),
                0,
                0,
                remainingActorCallbacksExcludingCurrent(),
                temporaryFileLeaseCount.get(),
                activeAdoptionTaskCount.get(),
                admissionsClosed = true,
                completedAtMs = SystemClock.elapsedRealtime()
            )
            check(barrier.isComplete)
            onQuarantineCloseBarrier(barrier)
        } else {
            val barrier = NtkSourceCloseBarrierProof(
                planBinding.episodePath,
                planBinding.discoveryGeneration,
                exactIdentity.manifest.seal.digestSha256,
                sessionId,
                CLOSE_BARRIER_SEQUENCE.getAndIncrement(),
                remainingCalls = 0,
                remainingStreams = physicalInFlightCount.get(),
                remainingTeeWriters = 0,
                remainingMetadataParsers = 0,
                remainingCachePublishes = 0,
                remainingDecodes = activeBodyLeaseCount.get(),
                remainingCallbacks = remainingActorCallbacksExcludingCurrent(),
                remainingTemporaryFileLeases = temporaryFileLeaseCount.get(),
                remainingQuarantineCalls = 0,
                remainingQuarantineFiles = temporaryFileLeaseCount.get(),
                remainingAdoptionTasks = activeAdoptionTaskCount.get(),
                admissionsClosed = true,
                completedAtMs = SystemClock.elapsedRealtime()
            )
            check(barrier.isComplete) { "Incomplete exact source close barrier: $barrier" }
            onExactCloseBarrier(barrier)
        }
        routePreparationLanes.forEach(ExecutorService::shutdownNow)
        physicalLanes.forEach(ExecutorService::shutdownNow)
        actor.shutdown()
        sourceTelemetry.close()
    }

    private fun recordSubmissionActor(work: PrimaryWork) {
        assertActorThread()
        val now = SystemClock.elapsedRealtime()
        if (work.mode == WorkMode.QUARANTINE) {
            if (firstQuarantineSubmittedAtMs == 0L) firstQuarantineSubmittedAtMs = now
            if (initialWaveCount < initialQuarantineWaveTargetCount) {
                initialWaveCount++
                initialQuarantineWaveSubmittedAtMs = now
            }
        }
    }

    private fun logOverlapProofActor() {
        assertActorThread()
        val proof = overlapProof ?: return
        logSourceEvent(
            "reader_strip_source_overlap_proof",
            "planReservedAt=${proof.planReservedAtMs}," +
                "firstQuarantineSubmittedAt=${proof.firstQuarantineSubmittedAtMs}," +
                "initialQuarantineWaveSubmittedAt=${proof.initialQuarantineWaveSubmittedAtMs}," +
                "initialWaveCount=${proof.initialWaveCount}," +
                "exactSealAt=${proof.exactSealAtMs},ownerClaimedAt=${proof.ownerClaimedAtMs}," +
                "completedAtPromotion=${proof.completedAtPromotion}," +
                "activeAtPromotion=${proof.activeAtPromotion}," +
                "queuedAtPromotion=${proof.queuedAtPromotion}," +
                "postPromotionStarted=${proof.postPromotionStarted}," +
                "physicalCallCount=${proof.physicalCallCount}," +
                "duplicatePhysicalCallCount=${proof.duplicatePhysicalCallCount}"
        )
    }

    private fun priorityFor(pageIndex: Int): Int {
        if (!isGeometrySealed()) return preGeometryPlan.priorities[pageIndex] ?: 0
        val demand = sourceDemand
        val index = demand?.priorityIndex(pageIndex) ?: pageIndex
        return when (demand?.demandClass(pageIndex) ?: NtkSourceDemandClass.BACKGROUND) {
            NtkSourceDemandClass.HARD -> 4_000_000 - index
            NtkSourceDemandClass.SOFT -> 3_000_000 - index
            NtkSourceDemandClass.BACKGROUND -> 100_000 - index
        }
    }

    private fun laneFor(pageIndex: Int): NtkSourceOperationLane {
        if (!isGeometrySealed()) {
            return preGeometryPlan.lanes[pageIndex] ?: NtkSourceOperationLane.METADATA
        }
        return when (sourceDemand?.demandClass(pageIndex) ?: NtkSourceDemandClass.BACKGROUND) {
            NtkSourceDemandClass.HARD -> NtkSourceOperationLane.URGENT
            NtkSourceDemandClass.SOFT -> NtkSourceOperationLane.TARGET
            NtkSourceDemandClass.BACKGROUND -> NtkSourceOperationLane.BACKGROUND_PROOF
        }
    }

    private fun currentDemandEpoch(): Long = sourceDemand?.demandEpoch ?: preGeometryPlan.revision
    private fun isGeometrySealed(): Boolean = geometryDigest.isNotEmpty()
    private fun acceptsEpisode(episode: NtkEpisodeToken): Boolean =
        boundEpisode?.let { it == episode } ?: true

    private fun exactOpenManifestActor(): NtkAuthoritativeManifest {
        assertActorThread()
        return (phase as? SessionPhase.ExactOpen)?.manifest
            ?: error("Exact source publication is not open")
    }

    private fun currentExactIdentityActor(): ExactIdentity? {
        assertActorThread()
        return when (val current = phase) {
            is SessionPhase.ExactInstalledGateClosed ->
                ExactIdentity(current.token, current.manifest, current.owner)
            is SessionPhase.ExactOpen -> ExactIdentity(
                NtkPromotionToken(
                    planBinding.episodePath,
                    planBinding.discoveryGeneration,
                    sessionId,
                    planBinding.bindingDigest,
                    current.manifest.seal.digestSha256,
                    current.manifest.proof.proofDigestSha256,
                    current.owner.promotionNonce
                ),
                current.manifest,
                current.owner
            )
            is SessionPhase.Closing -> current.exactIdentity
            else -> null
        }
    }

    private fun checkTokenIdentity(token: NtkPromotionToken) {
        assertActorThread()
        check(token.episodePath == planBinding.episodePath)
        check(token.discoveryGeneration == planBinding.discoveryGeneration)
        check(token.sessionId == sessionId)
        check(token.planBindingDigest == planBinding.bindingDigest)
        check(token.exactManifestDigest == candidateSeal.digestSha256)
    }

    private fun quarantineOwnershipSnapshot(): NtkQuarantineSourceOwnershipRegistry.Snapshot? =
        NtkQuarantineSourceOwnershipRegistry.snapshot(
            planBinding.episodePath,
            planBinding.discoveryGeneration,
            sessionId,
        )

    private fun checkOwnershipIdentity(
        ownership: NtkQuarantineSourceOwnershipRegistry.Snapshot
    ) {
        assertActorThread()
        check(ownership.episodePath == planBinding.episodePath)
        check(ownership.discoveryGeneration == planBinding.discoveryGeneration)
        check(ownership.planBindingDigest == planBinding.bindingDigest)
        check(ownership.sessionId == sessionId)
    }

    private fun validateMonotonicPromotionPartition(snapshot: NtkPromotionSnapshot) {
        assertActorThread()
        val activeNow = activeWorks.filterNotNull().mapTo(HashSet(), PrimaryWork::pageIndex)
        for (pageIndex in snapshot.completedPageIndexes) {
            val page = pages[pageIndex]
            check(page.quarantinedBody != null || page.publishedBody != null) {
                "Completed promotion page regressed"
            }
        }
        for (pageIndex in snapshot.activePageIndexes) {
            val page = pages[pageIndex]
            check(pageIndex in activeNow ||
                page.quarantinedBody != null || page.publishedBody != null
            ) { "Active promotion page did not progress monotonically" }
        }
        for (pageIndex in snapshot.queuedPageIndexes) {
            val page = pages[pageIndex]
            check(!page.primaryStarted && page.quarantinedBody == null &&
                page.publishedBody == null
            ) { "Queued promotion page started while promotion was frozen" }
        }
    }

    private fun strictRouteBucketHint(canonicalAsset: String): String {
        val schemeEnd = canonicalAsset.indexOf("://")
        require(schemeEnd > 0)
        val authorityStart = schemeEnd + 3
        val pathStart = canonicalAsset.indexOf('/', authorityStart)
            .takeIf { it >= 0 } ?: canonicalAsset.length
        return canonicalAsset.substring(0, pathStart).lowercase()
    }

    private fun appendAndEmit(event: SourceEvent) {
        assertActorThread()
        check(phase is SessionPhase.ExactOpen)
        eventLedger += event
        listeners.values.toList().forEach { listener ->
            runCatching { listener.onSourceEvent(event) }
        }
    }

    private fun replayLedger(listener: NtkSourceEventListener) {
        assertActorThread()
        eventLedger.forEach { event -> runCatching { listener.onSourceEvent(event) } }
    }

    private fun <T> enqueueActorCommand(operation: () -> T): CompletableFuture<T> {
        val completion = CompletableFuture<T>()
        try {
            val admitted = actorCallbackGate.admit {
                actor.execute {
                    markAndAssertActorThread()
                    actorCallbackDepth.set(actorCallbackDepthValue() + 1)
                    try {
                        completion.complete(operation())
                    } catch (failure: Throwable) {
                        completion.completeExceptionally(failure)
                        if (!closeRequested.get()) failSessionActor(failure)
                    } finally {
                        finishActorCallbackActor()
                    }
                }
            }
            if (!admitted) {
                completion.completeExceptionally(
                    RejectedExecutionException("Strict source actor callback admissions closed")
                )
            }
        } catch (failure: RejectedExecutionException) {
            completion.completeExceptionally(failure)
        }
        return completion
    }

    private fun executeActor(
        onRejected: () -> Unit = {},
        operation: () -> Unit
    ) {
        try {
            val admitted = actorCallbackGate.admit {
                actor.execute {
                    markAndAssertActorThread()
                    actorCallbackDepth.set(actorCallbackDepthValue() + 1)
                    try {
                        operation()
                    } catch (failure: Throwable) {
                        if (!closeRequested.get()) failSessionActor(failure)
                    } finally {
                        finishActorCallbackActor()
                    }
                }
            }
            if (!admitted) onRejected()
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun finishActorCallbackActor() {
        assertActorThread()
        publishImmutableSessionViewActor()
        actorCallbackDepth.set((actorCallbackDepthValue() - 1).coerceAtLeast(0))
        val remaining = actorCallbackGate.finish()
        if (NtkStrictSourceActorCloseRearmPolicy.shouldRearm(
                closeRequested.get(),
                closeFinalized.get(),
                remaining
            )
        ) {
            // closeSessionActor can defer its exact barrier behind an already queued callback.
            // The final callback-count transition to zero is the matching wake-up.
            maybeFinishClosedActor()
            if (actorClosed) publishImmutableSessionViewActor()
        }
    }

    private fun markAndAssertActorThread() {
        actorThread.compareAndSet(null, Thread.currentThread())
        assertActorThread()
    }

    private fun assertActorThread() {
        check(Thread.currentThread() === actorThread.get()) {
            "Strict source mutable state escaped its actor"
        }
    }

    private fun publishImmutableSessionViewActor() {
        assertActorThread()
        val ownership = quarantineOwnershipSnapshot()
        val exact = currentExactIdentityActor()
        val quarantineState = when (phase) {
            SessionPhase.New -> NtkQuarantineState.NONE
            is SessionPhase.Quarantining -> NtkQuarantineState.SPOOLING
            is SessionPhase.PromotionPreparing,
            is SessionPhase.PromotionPrepared -> NtkQuarantineState.PROMOTION_FROZEN
            is SessionPhase.ExactInstalledGateClosed -> NtkQuarantineState.EXACT_ADOPTING
            is SessionPhase.ExactOpen -> if (pages.all { it.publishedBody != null }) {
                NtkQuarantineState.EXACT_ADOPTED
            } else NtkQuarantineState.EXACT_ADOPTING
            is SessionPhase.Closing -> NtkQuarantineState.ABORTING
            SessionPhase.Closed -> NtkQuarantineState.CLOSED
        }
        publishedView.set(
            SessionPublishedView(
                exactSealAtMsInternal,
                phase is SessionPhase.ExactOpen,
                exact?.manifest?.seal?.digestSha256.orEmpty(),
                NtkQuarantineDebugSnapshot(
                    quarantineState,
                    ownership?.physicalCallCount ?: 0,
                    ownership?.activeCalls ?: 0,
                    pages.count { it.quarantinedBody != null },
                    temporaryFileLeaseCount.get(),
                    pages.count { it.publishedBody != null },
                    ownership?.duplicatePhysicalCallCount ?: 0
                )
            )
        )
    }

    private fun remainingActorCallbacksExcludingCurrent(): Int {
        return actorCallbackGate.remainingExcludingCurrent(actorCallbackDepthValue())
    }

    private fun actorCallbackDepthValue(): Int = actorCallbackDepth.get() ?: 0

    private fun logSourceEvent(event: String, fields: String) {
        assertActorThread()
        val authority = boundEpisode?.value ?: 0L
        val sequence = sourceLogSequence.getAndIncrement()
        val state = publishedView.get().debug.quarantineState
        val payload = "$event sessionId=$sessionId,episodeAuthority=$authority," +
            "preclaim=${authority == 0L},eventSequence=$sequence," +
            "quarantineState=$state,$fields"
        sourceTelemetry.offerRaw(event, sessionId) { payload }
    }

    fun quarantineDebugSnapshot(): NtkQuarantineDebugSnapshot = publishedView.get().debug

    private companion object {
        /** A streamed transfer failure retains a bounded production fallback without 88 idle workers. */
        const val STREAMED_EXACT_FALLBACK_LANES = 12
        /**
         * A signed image table has already paid the ACK/API control-plane cost and contains exact
         * physical assets, so it has no speculative generated-body competition. Its finite tail
         * may use all prestarted body workers; normal generated manhwa keeps the measured
         * twenty-four-connection optimum.
         */
        const val VIEWER_IMAGE_API_MANHWA_BODY_TRANSFERS = 32
        /** Bound the idle time caused by one slow first-byte response without opening a new pool. */
        const val UNSETTLED_COHORT_FOLLOWER_GRACE_MS = 350L
        /**
         * A settled webtoon cohort exposes several follower lanes at once. Launching only two per
         * corresponding actor turn left measured capacity idle until the anchor body reached EOF;
         * a 209-page/15.2 MiB episode then missed the four-second whole-scene SLA. A four-work
         * slice catches up to the bounded target while still interleaving EOF/adoption callbacks.
         */
        const val MAX_PRIMARY_LAUNCHES_PER_ACTOR_TURN = 4
        val SESSION_SEQUENCE = AtomicLong(1L)
        val CLOSE_BARRIER_SEQUENCE = AtomicLong(1L)
    }
}

data class NtkQuarantineDebugSnapshot(
    val quarantineState: NtkQuarantineState,
    val quarantinePhysicalCallsStarted: Int,
    val quarantineActiveCalls: Int,
    val quarantineBodiesSealed: Int,
    val quarantineTempFiles: Int,
    val exactAdoptedBodies: Int,
    val duplicatePhysicalCalls: Int
)
