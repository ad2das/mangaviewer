package ml.melun.mangaview.reader

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.ntkack.NtkAckBrowserClient
import ml.melun.mangaview.runtime.PerfTrace
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.io.InterruptedIOException
import java.util.ArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object NtkStrictDiscoveryThreadPolicy {
    const val ANDROID_PRIORITY: Int = Process.THREAD_PRIORITY_BACKGROUND
    const val JAVA_PRIORITY: Int = Thread.NORM_PRIORITY - 1

    fun enterWorker() {
        runCatching { Process.setThreadPriority(ANDROID_PRIORITY) }
    }
}

internal object NtkHostGpuEmulatorWebtoonControlPlanePolicy {
    fun isEligible(
        directWebtoon: Boolean,
        emulatorRuntime: Boolean,
        directWifiAdjacent: Boolean,
        directWifiCurrent: Boolean,
        rollingAdmission: Boolean,
        initialPageIndex: Int,
    ): Boolean {
        require(initialPageIndex >= 0)
        return directWebtoon && emulatorRuntime && (
            directWifiAdjacent ||
                (directWifiCurrent && rollingAdmission && initialPageIndex > 0)
            )
    }
}

enum class NtkValidatedAdjacentFlightPhase {
    GATE_WAIT,
    GATE_RELEASED,
    NETWORK_ENTERED,
    ROUTE_RECOVERY_SLOT_HELD,
}

internal data class NtkValidatedAdjacentFlightRetirementCandidate(
    val exactAdjacentIdentity: Boolean,
    val discoveryGeneration: Long,
    val startedAtMs: Long,
    val foregroundNetworkEntered: Boolean,
    val foregroundNetworkEnteredAtMs: Long,
    val phase: NtkValidatedAdjacentFlightPhase,
    val routeRecoverySlotHeldAtMs: Long,
    val controlReady: Boolean,
    val predecessorReady: Boolean,
    val predecessorReadyAtMs: Long,
    val completed: Boolean,
    val retired: Boolean,
    val networkOwnershipRetiring: Boolean,
    val authorityReady: Boolean,
    val activeViewer: Boolean,
    val validatedEpoch: Long,
    val lastRetiredValidatedEpoch: Long,
)

/** Pure eligibility shared by observation and the exact validated-epoch retirement commit. */
internal object NtkValidatedAdjacentFlightRetirementPolicy {
    fun eligibleSinceMs(
        candidate: NtkValidatedAdjacentFlightRetirementCandidate,
    ): Long? {
        val physicalPhaseEligible = when (candidate.phase) {
            NtkValidatedAdjacentFlightPhase.GATE_WAIT -> false
            NtkValidatedAdjacentFlightPhase.GATE_RELEASED ->
                !candidate.foregroundNetworkEntered &&
                    !candidate.networkOwnershipRetiring &&
                    candidate.predecessorReadyAtMs > 0L
            NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED ->
                candidate.foregroundNetworkEntered &&
                    !candidate.networkOwnershipRetiring &&
                    candidate.foregroundNetworkEnteredAtMs > 0L
            NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD ->
                !candidate.foregroundNetworkEntered &&
                    candidate.networkOwnershipRetiring &&
                    candidate.routeRecoverySlotHeldAtMs > 0L
        }
        if (!candidate.exactAdjacentIdentity ||
            candidate.discoveryGeneration <= 0L ||
            candidate.startedAtMs < 0L ||
            !physicalPhaseEligible ||
            !candidate.controlReady ||
            !candidate.predecessorReady ||
            candidate.predecessorReadyAtMs <= 0L ||
            candidate.completed || candidate.retired ||
            candidate.authorityReady ||
            !candidate.activeViewer || candidate.validatedEpoch <= 0L ||
            candidate.validatedEpoch <= candidate.lastRetiredValidatedEpoch
        ) return null
        val physicalEligibleAtMs = when (candidate.phase) {
            NtkValidatedAdjacentFlightPhase.GATE_RELEASED ->
                candidate.predecessorReadyAtMs
            NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED ->
                candidate.foregroundNetworkEnteredAtMs
            NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD ->
                candidate.routeRecoverySlotHeldAtMs
            NtkValidatedAdjacentFlightPhase.GATE_WAIT -> return null
        }
        return maxOf(
            candidate.startedAtMs,
            physicalEligibleAtMs,
            candidate.predecessorReadyAtMs,
        )
    }
}

internal enum class NtkForegroundNetworkLeaveAction {
    LEAVE,
    AWAIT_EXISTING_LEAVE,
    NONE,
}

/** Pure state decision for one Flight's exactly-once client foreground leave. */
internal object NtkForegroundNetworkLeavePolicy {
    fun action(
        foregroundNetworkEntered: Boolean,
        leaveStarted: Boolean,
        leaveCompleted: Boolean,
    ): NtkForegroundNetworkLeaveAction {
        require(!foregroundNetworkEntered || !leaveStarted) {
            "Foreground network cannot remain entered after leave ownership was claimed"
        }
        return when {
            foregroundNetworkEntered -> NtkForegroundNetworkLeaveAction.LEAVE
            leaveStarted && !leaveCompleted ->
                NtkForegroundNetworkLeaveAction.AWAIT_EXISTING_LEAVE
            else -> NtkForegroundNetworkLeaveAction.NONE
        }
    }
}

internal object NtkCompletedAdjacentRegistryPolicy {
    fun isUnusable(
        authorityReady: Boolean,
        snapshotPresent: Boolean,
        snapshotMatchesDiscovery: Boolean,
        snapshotTerminalClosing: Boolean,
    ): Boolean = !authorityReady && (
        !snapshotPresent || !snapshotMatchesDiscovery || snapshotTerminalClosing
    )
}

internal object NtkPauseAdjustedTimestampPolicy {
    fun shift(timestampMs: Long, pausedAtMs: Long, resumedAtMs: Long): Long {
        require(pausedAtMs >= 0L && resumedAtMs >= pausedAtMs)
        if (timestampMs <= 0L || timestampMs >= resumedAtMs) return timestampMs
        val pausedDurationMs = resumedAtMs - pausedAtMs
        return if (timestampMs <= pausedAtMs) {
            timestampMs + pausedDurationMs
        } else {
            resumedAtMs
        }
    }
}

/**
 * The only StrictFresh document + server grant -> exact image manifest coordinator.
 *
 * Calls from activities, warmup and the reader all join this path-level flight. The document GET
 * and grant prerequisite overlap, but there is exactly one logical document request, one challenge
 * request, one image-API request, one document parse and one API parse for a discovery generation.
 * Webtoon consumes the server's response-local trusted grant directly; manhwa and a real full
 * challenge retain the isolated browser/key-signing authority.
 */
object NtkStrictEpisodeDiscoveryCoordinator {
    private const val ADJACENT_GATE_OWNERSHIP_SLICE_MS = 1_000L

    private enum class AdjacentBodyGateRelease {
        OPENED,
        ALREADY_OPEN,
        FAILED,
    }

    private class AckRoute(
        val bootstrap: CustomHttpClient.NtkStrictAckBootstrap,
    ) {
        @Volatile
        var directTrustedTask: FutureTask<CustomHttpClient.NtkDirectTrustedGrant>? = null
            private set

        @Volatile
        private var directTrustedThread: Thread? = null

        @Volatile
        var nvSeedTask: FutureTask<Boolean>? = null
            private set

        @Volatile
        private var nvSeedThread: Thread? = null

        @Volatile
        var isolatedHandle: NtkAckBrowserClient.FlightHandle? = null
            private set

        @Synchronized
        fun attachIsolatedHandle(
            flight: Flight,
            handle: NtkAckBrowserClient.FlightHandle,
        ) {
            check(isolatedHandle == null) { "Strict isolated ACK owner already attached" }
            if (!flight.retirement.attachAckCancellation { handle.cancel() }) {
                handle.cancel()
                throw InterruptedIOException("Viewer ownership retired while ACK was starting")
            }
            isolatedHandle = handle
        }

        @Synchronized
        fun attachDirectTrustedTask(
            task: FutureTask<CustomHttpClient.NtkDirectTrustedGrant>,
            thread: Thread,
        ) {
            check(directTrustedTask == null) { "Strict direct trusted owner already attached" }
            directTrustedTask = task
            directTrustedThread = thread
        }

        @Synchronized
        fun attachNvSeedTask(task: FutureTask<Boolean>, thread: Thread) {
            check(nvSeedTask == null) { "Strict nv seed owner already attached" }
            nvSeedTask = task
            nvSeedThread = thread
        }

        fun cancel() {
            directTrustedTask?.takeUnless { it.isDone }?.cancel(true)
            directTrustedThread?.takeIf { it.isAlive }?.interrupt()
            nvSeedTask?.takeUnless { it.isDone }?.cancel(true)
            nvSeedThread?.takeIf { it.isAlive }?.interrupt()
            isolatedHandle?.takeUnless { it.isDone }?.cancel()
        }
    }

    private class Flight(
        val client: CustomHttpClient,
        val lease: NtkDiscoveryLease,
        val startedAtMs: Long,
        val viewerGeneration: Long,
        val episodePath: String,
        val viewerOwnerEpisodePath: String,
        val adjacentPredecessorEpisodePath: String,
        val adjacentPredecessorGate: Boolean,
        val directWifiAdjacentBodyGate: Boolean,
        val directWifiCurrentViewer: Boolean,
        val rollingAdmission: Boolean,
        val initialPageIndexHint: Int,
        val completedRouteRecoveryAttempts: Int,
        val sameOriginFallbackConsumed: Boolean,
        val routeSnapshot: CustomHttpClient.NtkStrictRouteSnapshot,
    ) {
        val retirement = NtkStrictDiscoveryRetirementFence(
            episodePath,
            viewerGeneration,
            lease.generation.value,
        )
        val physicalCalls = CustomHttpClient.NtkStrictCallRegistry(
            episodePath,
            viewerGeneration,
            routeSnapshot,
        )
        /** Exact authority is retained here until its viewer explicitly retires ownership. */
        val completed = AtomicBoolean(false)
        val foregroundNetworkEntered = AtomicBoolean(false)
        /** Monotonic admission time; zero means this Flight has never owned foreground network. */
        val foregroundNetworkEnteredAtMs = AtomicLong(0L)
        val foregroundNetworkLeaveStarted = AtomicBoolean(false)
        val foregroundNetworkLeaveCompleted = CompletableFuture<Unit>()
        val validatedAdjacentPhase = AtomicReference(
            NtkValidatedAdjacentFlightPhase.GATE_WAIT,
        )
        /** Set only after a route-failed worker has left network but retained the path slot. */
        val routeRecoverySlotHeldAtMs = AtomicLong(0L)
        /**
         * Linearizes the last possible adjacent foreground-network enter with viewer retirement.
         * Retirement claims this flag under the same Flight monitor used by body-gate release,
         * so a stale flights.values snapshot can never re-enter after leave has already run.
         */
        val networkOwnershipRetiring = AtomicBoolean(false)
        /**
         * Control admission may overlap only the target document and its trusted challenge. The
         * scoped direct-Wi-Fi manhwa path can open at flight admission for its document and bounded
         * format HEAD probes; API, image bodies, source promotion and decode remain closed by
         * [adjacentPredecessorComplete]. The host-emulator webtoon path opens this event only after
         * every predecessor body is resident. Every other adjacent profile opens both events
         * together at full predecessor completion.
         */
        val adjacentControlReady = CompletableFuture<Unit>().also { release ->
            if (!adjacentPredecessorGate ||
                NtkAdjacentMetadataControlPolicy.mayOpenAtFlightAdmission(
                    directWifiAdjacentBodyGate,
                    episodePath,
                )
            ) {
                release.complete(Unit)
            }
        }

        /** Every required predecessor drawable has been installed in the native runway. */
        val adjacentPredecessorComplete = CompletableFuture<Unit>().also { release ->
            if (!adjacentPredecessorGate) {
                release.complete(Unit)
            }
        }
        /**
         * Surface-proven physical demand for this exact predecessor/target pair. This does not
         * open network/body admission; it only stops optional control allocation from waiting for
         * motion-idle after the user is already clamped at the completed predecessor's end.
         */
        val adjacentPhysicalBoundaryDemand = CompletableFuture<Unit>()
        /** Monotonic full body-gate release time; control-only release deliberately leaves zero. */
        val adjacentPredecessorReadyAtMs = AtomicLong(
            if (adjacentPredecessorGate) 0L else startedAtMs,
        )

        init {
            check(
                retirement.attachPhysicalCancellation {
                    physicalCalls.markCancelledAndDetachCalls()
                }
            )
        }
    }

    private val flights = ConcurrentHashMap<String, Flight>()
    private val flightLifecycleLocks = ConcurrentHashMap<String, Any>()
    /** Keeps same-path admission closed while a detached Flight balances its client gate. */
    private val foregroundNetworkLeaveBarriers = ConcurrentHashMap<String, Flight>()
    private data class AdjacentGateKey(
        val predecessorPath: String,
        val targetPath: String,
    )

    private data class ValidatedAdjacentRetirementKey(
        val viewerGeneration: Long,
        val viewerOwnerPath: String,
        val predecessorPath: String,
        val targetPath: String,
    )

    private val bodyResidentAdjacentTargets = ConcurrentHashMap<AdjacentGateKey, Long>()
    private val completedAdjacentTargets = ConcurrentHashMap<AdjacentGateKey, Long>()
    private val completedAdjacentPredecessors = ConcurrentHashMap<String, Long>()
    private val physicalBoundaryAdjacentTargets = ConcurrentHashMap<AdjacentGateKey, Long>()
    private val physicalBoundaryAdjacentPredecessors = ConcurrentHashMap<String, Long>()
    /** Last successful exact retirement epoch; discovery generation is intentionally not a key. */
    private val validatedAdjacentRetirementEpochs =
        ConcurrentHashMap<ValidatedAdjacentRetirementKey, Long>()

    private fun adjacentGateKey(predecessorPath: String, targetPath: String): AdjacentGateKey =
        AdjacentGateKey(predecessorPath, targetPath)

    @JvmStatic
    fun start(client: CustomHttpClient?, manga: Manga?): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = false,
            initialPageIndexHint = 0,
            completedRouteRecoveryAttempts = 0,
            viewerOwnerEpisodePath = null,
            adjacentPredecessorEpisodePath = null,
        )
    }

    /** Exact cold-reader discovery whose physical image body admission starts at source 0/1. */
    @JvmStatic
    @JvmOverloads
    fun startColdRolling(
        client: CustomHttpClient?,
        manga: Manga?,
        initialPageIndexHint: Int = 0,
    ): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = true,
            initialPageIndexHint = initialPageIndexHint,
            completedRouteRecoveryAttempts = 0,
            viewerOwnerEpisodePath = null,
            adjacentPredecessorEpisodePath = null,
        )
    }

    /**
     * Activity-owned current discovery. Unlike the compatibility/pre-click entry point, this
     * carries the already-claimed viewer identity into the path-lock admission boundary.
     */
    @JvmStatic
    fun startOwnedColdRolling(
        client: CustomHttpClient?,
        manga: Manga?,
        initialPageIndexHint: Int,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
    ): Boolean = startInternal(
        client,
        manga,
        rollingAdmission = true,
        initialPageIndexHint = initialPageIndexHint,
        completedRouteRecoveryAttempts = 0,
        viewerOwnerEpisodePath = expectedOwnerEpisodePath,
        adjacentPredecessorEpisodePath = null,
        expectedCurrentViewerGeneration = expectedViewerGeneration,
        expectedCurrentOwnerEpisodePath = expectedOwnerEpisodePath,
    )

    /**
     * Restarts only the exact current viewer after a validated-network edge. Unlike the ordinary
     * entry point, this carries the Activity's immutable viewer generation into the path lock so
     * a stale same-path Activity cannot start work for a replacement reader.
     */
    enum class CurrentValidatedRedriveResult {
        STARTED,
        ACTIVE,
        AUTHORITY_READY,
        SOURCE_SETTLING,
        STALE_OWNER,
        ATTEMPT_FAILED,
    }

    @JvmStatic
    fun startCurrentColdRollingAfterValidated(
        client: CustomHttpClient?,
        manga: Manga?,
        initialPageIndexHint: Int,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
    ): CurrentValidatedRedriveResult {
        val path = normalizedPath(manga?.ntkEpisodePath)
            ?: return CurrentValidatedRedriveResult.STALE_OWNER
        val ownerPath = normalizedPath(expectedOwnerEpisodePath)
        if (client == null || manga == null || expectedViewerGeneration <= 0L ||
            ownerPath != path ||
            !ViewerTelemetry.isActiveViewer(expectedViewerGeneration, ownerPath)
        ) return CurrentValidatedRedriveResult.STALE_OWNER
        if (NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null) {
            return CurrentValidatedRedriveResult.AUTHORITY_READY
        }
        if (currentValidatedFlightObservation(
                path,
                expectedViewerGeneration,
                ownerPath,
            ) != null
        ) return CurrentValidatedRedriveResult.ACTIVE
        retireCompletedTerminalCurrentFlightForValidatedReplacement(
            path,
            expectedViewerGeneration,
            ownerPath,
        )
        val freshAdmissionAttempted = AtomicBoolean(false)
        val existingProgressObserved = AtomicBoolean(false)
        val started = startInternal(
            client,
            manga,
            rollingAdmission = true,
            initialPageIndexHint = initialPageIndexHint,
            completedRouteRecoveryAttempts = 0,
            viewerOwnerEpisodePath = expectedOwnerEpisodePath,
            adjacentPredecessorEpisodePath = null,
            expectedCurrentViewerGeneration = expectedViewerGeneration,
            expectedCurrentOwnerEpisodePath = expectedOwnerEpisodePath,
            requireFreshValidatedGeneration = true,
            validatedRedriveAdmissionAttempted = freshAdmissionAttempted,
            validatedRedriveProgressObserved = existingProgressObserved,
        )
        if (started) return CurrentValidatedRedriveResult.STARTED
        if (!ViewerTelemetry.isActiveViewer(expectedViewerGeneration, ownerPath)
        ) return CurrentValidatedRedriveResult.STALE_OWNER
        if (NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null) {
            return CurrentValidatedRedriveResult.AUTHORITY_READY
        }
        if (currentValidatedFlightObservation(
                path,
                expectedViewerGeneration,
                ownerPath,
            ) != null
        ) return CurrentValidatedRedriveResult.ACTIVE
        if (freshAdmissionAttempted.get()) {
            return CurrentValidatedRedriveResult.ATTEMPT_FAILED
        }
        if (existingProgressObserved.get()) {
            return CurrentValidatedRedriveResult.SOURCE_SETTLING
        }
        return if (NtkSourceSpoolRegistry.hasCurrentDiscoveryEntry(path)) {
            CurrentValidatedRedriveResult.SOURCE_SETTLING
        } else {
            CurrentValidatedRedriveResult.ATTEMPT_FAILED
        }
    }

    /**
     * Starts the one exact manifest flight for the immediate continuous-reader neighbor while the
     * original click-owned viewer remains active. The target must stay inside the same work path;
     * this does not authorize arbitrary background/pre-click discovery.
     */
    @JvmStatic
    fun startAdjacentColdRolling(
        client: CustomHttpClient?,
        manga: Manga?,
        viewerOwnerEpisodePath: String?,
        adjacentPredecessorEpisodePath: String? = viewerOwnerEpisodePath,
        expectedViewerGeneration: Long,
    ): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = true,
            initialPageIndexHint = 0,
            completedRouteRecoveryAttempts = 0,
            viewerOwnerEpisodePath = viewerOwnerEpisodePath,
            adjacentPredecessorEpisodePath = adjacentPredecessorEpisodePath,
            expectedCurrentViewerGeneration = expectedViewerGeneration,
            expectedCurrentOwnerEpisodePath = viewerOwnerEpisodePath,
        )
    }

    private fun startInternal(
        client: CustomHttpClient?,
        manga: Manga?,
        rollingAdmission: Boolean,
        initialPageIndexHint: Int,
        completedRouteRecoveryAttempts: Int,
        viewerOwnerEpisodePath: String?,
        adjacentPredecessorEpisodePath: String?,
        sameOriginFallbackConsumed: Boolean = false,
        expectedCurrentViewerGeneration: Long? = null,
        expectedCurrentOwnerEpisodePath: String? = null,
        requireFreshValidatedGeneration: Boolean = false,
        validatedRedriveAdmissionAttempted: AtomicBoolean? = null,
        validatedRedriveProgressObserved: AtomicBoolean? = null,
    ): Boolean {
        if (client == null || manga == null) return false
        val path = normalizedPath(manga.ntkEpisodePath) ?: return false
        val ownerPath = normalizedPath(viewerOwnerEpisodePath) ?: path
        val expectedOwnerPath = normalizedPath(expectedCurrentOwnerEpisodePath)
        if (expectedCurrentViewerGeneration != null &&
            (expectedCurrentViewerGeneration <= 0L || expectedOwnerPath == null ||
                ownerPath != expectedOwnerPath)
        ) return false
        if (requireFreshValidatedGeneration &&
            (expectedCurrentViewerGeneration == null || ownerPath != path)
        ) return false
        val adjacentOwned = ownerPath != path &&
            ntkAdjacentOwnerAllowsTarget(ownerPath, path)
        val predecessorPath = normalizedPath(adjacentPredecessorEpisodePath) ?: ownerPath
        val routeSnapshot = runCatching {
            client.captureNtkStrictRouteSnapshot(path)
        }.getOrElse { failure ->
            Log.e("ViewerPerf", "ntk_strict_route_snapshot_failed path=$path", failure)
            return false
        }
        val transportState = routeSnapshot.directWifiTransport to
            routeSnapshot.cellularResilientTransport
        // Freeze the forward-resume decision at discovery ownership. Every later source,
        // renderer and click-owned manhwa consumer reads this same generation-owned value; a
        // network handoff after the click can therefore neither enable the Wi-Fi optimization on
        // cellular/SNI nor make the source and renderer wait for different ranges.
        val effectiveInitialPageIndexHint = if (
            rollingAdmission && !adjacentOwned && transportState.first && !transportState.second
        ) {
            initialPageIndexHint.coerceAtLeast(0)
        } else {
            0
        }
        val adjacentAdmission = NtkAdjacentAdmissionPolicy.decide(
            adjacentOwned = adjacentOwned,
            wifiTransportActive = transportState.first,
            cellularResilientTransportActive = transportState.second,
        )
        val adjacentPredecessorGate = adjacentAdmission.predecessorCompletionRequired
        val directWifiAdjacentBodyGate = adjacentAdmission.directWifiPhysicalRunway
        val directWifiCurrentViewer = !adjacentOwned &&
            transportState.first && !transportState.second
        val viewerGeneration = expectedCurrentViewerGeneration
            ?: ViewerTelemetry.activeGeneration()
        if (!ViewerTelemetry.isActiveViewer(viewerGeneration, ownerPath) ||
            (ownerPath != path && !adjacentOwned)
        ) {
            Log.d("ViewerPerf", "ntk_strict_exact_discovery_preclick_suppressed path=$path")
            return false
        }
        if (expectedCurrentViewerGeneration != null &&
            (viewerGeneration != expectedCurrentViewerGeneration ||
                !ViewerTelemetry.isActiveViewer(
                    expectedCurrentViewerGeneration,
                    expectedOwnerPath,
                ))
        ) return false
        if (adjacentOwned) {
            retireCompletedUnusableAdjacentFlightForReplacement(
                path,
                predecessorPath,
                viewerGeneration,
                ownerPath,
            )
        }
        val adjacentAdmissionLock = flightLifecycleLock("adjacent-gate:$predecessorPath")
        val flight = synchronized(adjacentAdmissionLock) {
            if (adjacentOwned) {
                val exactGateKey = adjacentGateKey(predecessorPath, path)
                val predecessorReconciled = completedAdjacentTargets.entries.any { entry ->
                    entry.value == viewerGeneration &&
                        entry.key.predecessorPath == predecessorPath
                }
                if (predecessorReconciled &&
                    completedAdjacentTargets[exactGateKey] != viewerGeneration
                ) {
                    Log.d(
                        "ViewerPerf",
                        "ntk_strict_adjacent_stale_target_suppressed " +
                            "predecessor=$predecessorPath,target=$path," +
                            "generation=$viewerGeneration",
                    )
                    return false
                }
            }
            synchronized(flightLifecycleLock(path)) {
                // Every admission, including the first adjacent target, is fenced again at the
                // exact point where its lease/Flight becomes visible. A viewer replacement can
                // occur after the earlier transport snapshot and before this path lock.
                if (!ViewerTelemetry.isActiveViewer(viewerGeneration, ownerPath)) return false
                if (expectedCurrentViewerGeneration != null &&
                    (!ViewerTelemetry.isActiveViewer(
                        expectedCurrentViewerGeneration,
                        expectedOwnerPath,
                    ) ||
                        ownerPath != expectedOwnerPath)
                ) return false
                if (NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null ||
                    flights[path] != null || foregroundNetworkLeaveBarriers[path] != null
                ) {
                    validatedRedriveProgressObserved?.set(true)
                    return false
                }
                if (requireFreshValidatedGeneration &&
                    NtkSourceSpoolRegistry.prepareCurrentSlotForValidatedReplacement(path) ==
                    NtkSourceSpoolRegistry.ValidatedReplacementSlot.LIVE_PROGRESS
                ) {
                    validatedRedriveProgressObserved?.set(true)
                    return false
                }
                val lease = if (rollingAdmission) {
                    if (requireFreshValidatedGeneration) {
                        NtkSourceSpoolRegistry.beginFreshColdRollingDiscoveryAfterValidated(
                            client.context,
                            manga,
                            effectiveInitialPageIndexHint,
                            checkNotNull(expectedCurrentViewerGeneration),
                        )
                    } else {
                        NtkSourceSpoolRegistry.beginColdRollingDiscovery(
                            client.context,
                            manga,
                            effectiveInitialPageIndexHint,
                            viewerGeneration,
                        )
                    }
                } else {
                    NtkSourceSpoolRegistry.beginDiscovery(client.context, manga)
                } ?: run {
                    validatedRedriveProgressObserved?.set(true)
                    return false
                }
                val admitted = Flight(
                    client,
                    lease,
                    SystemClock.elapsedRealtime(),
                    viewerGeneration,
                    path,
                    ownerPath,
                    predecessorPath,
                    adjacentPredecessorGate,
                    directWifiAdjacentBodyGate,
                    directWifiCurrentViewer,
                    rollingAdmission,
                    effectiveInitialPageIndexHint,
                    completedRouteRecoveryAttempts,
                    sameOriginFallbackConsumed,
                    routeSnapshot,
                )
                flights[path] = admitted
                if (adjacentOwned && (
                        physicalBoundaryAdjacentTargets[
                            adjacentGateKey(predecessorPath, path)
                        ] == viewerGeneration ||
                            physicalBoundaryAdjacentPredecessors[predecessorPath] ==
                            viewerGeneration
                    )
                ) {
                    admitted.adjacentPhysicalBoundaryDemand.complete(Unit)
                }
                if (!ViewerTelemetry.isActiveViewer(viewerGeneration, ownerPath)) {
                    // Linearize against viewerOpen(): if replacement began before insertion this
                    // post-publish check retires us; if it begins afterwards its ownership scan
                    // observes this visible Flight and retires the same identity.
                    claimNetworkOwnershipRetirement(admitted)
                    admitted.retirement.retire(path, viewerGeneration)
                    NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                        admitted.lease,
                        "strict_exact_stale_post_admission",
                    )
                    check(!admitted.foregroundNetworkEntered.get())
                    check(detachFlightForForegroundLeaveLocked(admitted))
                    // No foreground enter is possible before worker/bootstrap admission, so this
                    // only compare-removes the short common barrier under the reentrant path lock.
                    completeDetachedFlightForegroundLeave(admitted)
                    return false
                }
                validatedRedriveAdmissionAttempted?.set(true)
                admitted
            }
        }

        val ackRoute = try {
            // The network-priority gate belongs to the exact discovery generation, not to the
            // Activity. Opening it only after the flight/lease is installed means every successful
            // enter has one deterministic retirement owner and an early start rejection cannot
            // strand a process-wide gate. This retires already-running compatibility calls but
            // starts no viewer request by itself.
            val exactAdjacentGateKey = adjacentGateKey(
                flight.adjacentPredecessorEpisodePath,
                flight.episodePath,
            )
            val predecessorHasReconciledTarget = completedAdjacentTargets.entries.any { entry ->
                entry.value == viewerGeneration &&
                    entry.key.predecessorPath == flight.adjacentPredecessorEpisodePath
            }
            if (flight.adjacentPredecessorGate &&
                predecessorHasReconciledTarget &&
                completedAdjacentTargets[exactAdjacentGateKey] != viewerGeneration
            ) {
                throw InterruptedIOException(
                    "Adjacent target was replaced before discovery worker admission",
                )
            }
            if (!flight.adjacentPredecessorGate) {
                enterForegroundNetworkIfNeeded(flight)
            } else if (
                completedAdjacentTargets[exactAdjacentGateKey] == viewerGeneration
            ) {
                // Completion can win just before the resolved neighbor creates this flight.
                // Publish admission now; the worker still performs the foreground enter before
                // starting any target-network prerequisite.
                check(releaseAdjacentBodyGate(flight) != AdjacentBodyGateRelease.FAILED) {
                    "Completed adjacent target could not release work admission"
                }
            } else if (
                !predecessorHasReconciledTarget &&
                completedAdjacentPredecessors[flight.adjacentPredecessorEpisodePath] ==
                    viewerGeneration
            ) {
                check(releaseAdjacentBodyGate(flight) != AdjacentBodyGateRelease.FAILED) {
                    "Completed adjacent predecessor could not release work admission"
                }
            } else if (
                bodyResidentAdjacentTargets[exactAdjacentGateKey] ==
                    viewerGeneration
            ) {
                check(flight.directWifiAdjacentBodyGate && path.startsWith("/webtoon/"))
                check(releaseAdjacentControlGate(flight) != AdjacentBodyGateRelease.FAILED) {
                    "Body-resident adjacent predecessor could not release control admission"
                }
            }
            // Bootstrap is local-only: it creates identity seeds but performs no network request.
            // Every adjacent flight retains this empty route until its worker observes the
            // predecessor-complete event. Its network-specific transport is selected only after
            // that release; current flights preserve eager overlap.
            val route = AckRoute(
                client.prepareNtkStrictAckBootstrap(path, flight.physicalCalls),
            )
            if (!flight.adjacentPredecessorGate) {
                startAckNetworkPrerequisites(client, flight, path, route)
            }
            route
        } catch (failure: Throwable) {
            val detached = synchronized(flightLifecycleLock(path)) {
                claimNetworkOwnershipRetirement(flight)
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_ack_start_${failure.javaClass.simpleName}"
                )
                detachFlightForForegroundLeaveLocked(flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_grant_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            if (detached) {
                completeDetachedFlightForegroundLeave(flight)
            } else {
                leaveForegroundNetworkIfEntered(flight)
            }
            return false
        }
        try {
            val worker = Thread(
                {
                    // Exact discovery owns network/control progress, not a display deadline.
                    // Running this CPU-heavy coordinator at Java priority NORM+1 translated to
                    // nice -2 on Android and let one discovery consume a complete guest core
                    // while the Surface producer was servicing physical scroll. Keep every
                    // request, permit and completion unchanged, but make the coordinator yield
                    // to input/render owners just like its body-transfer workers.
                    NtkStrictDiscoveryThreadPolicy.enterWorker()
                    runFlight(client, manga, path, flight, ackRoute)
                },
                "ntk-strict-exact-discovery"
            ).apply {
                isDaemon = true
                priority = NtkStrictDiscoveryThreadPolicy.JAVA_PRIORITY
            }
            if (!flight.retirement.attachWorker(worker)) {
                throw InterruptedIOException("Viewer ownership retired while worker was starting")
            }
            worker.start()
        } catch (failure: Throwable) {
            ackRoute.cancel()
            flight.physicalCalls.cancelAll()
            val detached = synchronized(flightLifecycleLock(path)) {
                claimNetworkOwnershipRetirement(flight)
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_worker_start_${failure.javaClass.simpleName}"
                )
                detachFlightForForegroundLeaveLocked(flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_worker_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            if (detached) {
                completeDetachedFlightForegroundLeave(flight)
            } else {
                leaveForegroundNetworkIfEntered(flight)
            }
            return false
        }
        return true
    }

    @JvmStatic
    fun isInFlight(path: String?): Boolean =
        normalizedPath(path)?.let { key ->
            flights[key]?.let { !it.completed.get() && !it.retirement.isRetired() } == true
        } == true

    /**
     * Returns the generation-owned route decision captured before discovery admission. The shared
     * HTTP client may change modes while the exact adjacent body is decoding, so UI publication
     * must never reconstruct this decision from that later mutable state.
     */
    @JvmStatic
    fun isDirectWifiAdjacentRouteProfile(
        path: String?,
        discoveryGeneration: Long = 0L,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        val flight = flights[key] ?: return false
        return !flight.retirement.isRetired() &&
            (discoveryGeneration <= 0L || flight.lease.generation.value == discoveryGeneration) &&
            flight.viewerOwnerEpisodePath != key &&
            ntkAdjacentOwnerAllowsTarget(flight.viewerOwnerEpisodePath, key) &&
            flight.routeSnapshot.directWifiTransport &&
            !flight.routeSnapshot.cellularResilientTransport
    }

    /** Excludes HOME/background duration from every active adjacent physical eligibility clock. */
    @JvmStatic
    fun shiftActiveAdjacentPhysicalEligibilityAfterPause(
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
        pausedAtMs: Long,
        resumedAtMs: Long,
    ): Int {
        val owner = normalizedPath(expectedOwnerEpisodePath) ?: return 0
        if (expectedViewerGeneration <= 0L || pausedAtMs <= 0L ||
            resumedAtMs < pausedAtMs ||
            !ViewerTelemetry.isActiveViewer(expectedViewerGeneration, owner)
        ) return 0
        val ownedAdjacentPaths = flights.values
            .filter { flight ->
                flight.viewerGeneration == expectedViewerGeneration &&
                    flight.viewerOwnerEpisodePath == owner &&
                    flight.episodePath != owner && flight.adjacentPredecessorGate
            }
            .map(Flight::episodePath)
            .distinct()
        var shiftedFlights = 0
        ownedAdjacentPaths.forEach { path ->
            val shifted = synchronized(flightLifecycleLock(path)) {
                val flight = flights[path] ?: return@synchronized false
                if (flight.viewerGeneration != expectedViewerGeneration ||
                    flight.viewerOwnerEpisodePath != owner ||
                    flight.episodePath == owner || !flight.adjacentPredecessorGate ||
                    flight.completed.get() || flight.retirement.isRetired() ||
                    !ViewerTelemetry.isActiveViewer(expectedViewerGeneration, owner)
                ) return@synchronized false
                synchronized(flight) {
                    var changed = false
                    changed = shiftAdjacentPhysicalTimestampAfterPause(
                        flight.foregroundNetworkEnteredAtMs,
                        pausedAtMs,
                        resumedAtMs,
                    ) || changed
                    changed = shiftAdjacentPhysicalTimestampAfterPause(
                        flight.adjacentPredecessorReadyAtMs,
                        pausedAtMs,
                        resumedAtMs,
                    ) || changed
                    changed = shiftAdjacentPhysicalTimestampAfterPause(
                        flight.routeRecoverySlotHeldAtMs,
                        pausedAtMs,
                        resumedAtMs,
                    ) || changed
                    changed
                }
            }
            if (shifted) shiftedFlights++
        }
        return shiftedFlights
    }

    private fun shiftAdjacentPhysicalTimestampAfterPause(
        timestamp: AtomicLong,
        pausedAtMs: Long,
        resumedAtMs: Long,
    ): Boolean {
        while (true) {
            val current = timestamp.get()
            val shifted = NtkPauseAdjustedTimestampPolicy.shift(
                current,
                pausedAtMs,
                resumedAtMs,
            )
            if (shifted == current) return false
            if (timestamp.compareAndSet(current, shifted)) return true
        }
    }

    data class CurrentValidatedFlightObservation(
        val discoveryGeneration: Long,
        val startedAtMs: Long,
    )

    @JvmStatic
    fun currentValidatedFlightObservation(
        path: String?,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
    ): CurrentValidatedFlightObservation? {
        val key = normalizedPath(path) ?: return null
        val ownerPath = normalizedPath(expectedOwnerEpisodePath) ?: return null
        if (key != ownerPath || expectedViewerGeneration <= 0L) return null
        val flight = flights[key] ?: return null
        if (flight.viewerGeneration != expectedViewerGeneration ||
            flight.viewerOwnerEpisodePath != ownerPath ||
            flight.completed.get() || flight.retirement.isRetired() ||
            !ViewerTelemetry.isActiveViewer(expectedViewerGeneration, ownerPath)
        ) return null
        return CurrentValidatedFlightObservation(
            flight.lease.generation.value,
            flight.startedAtMs,
        )
    }

    /** Replaces only the exact current flight which outlived one validated recovery deadline. */
    @JvmStatic
    fun retireCurrentFlightForValidatedReplacement(
        path: String?,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
        expectedDiscoveryGeneration: Long,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        val ownerPath = normalizedPath(expectedOwnerEpisodePath) ?: return false
        if (key != ownerPath || expectedViewerGeneration <= 0L ||
            expectedDiscoveryGeneration <= 0L
        ) return false
        val retired = synchronized(flightLifecycleLock(key)) {
            val flight = flights[key] ?: return@synchronized null
            if (flight.viewerGeneration != expectedViewerGeneration ||
                flight.viewerOwnerEpisodePath != ownerPath ||
                flight.lease.generation.value != expectedDiscoveryGeneration ||
                flight.completed.get() || flight.retirement.isRetired() ||
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(key) != null ||
                !ViewerTelemetry.isActiveViewer(expectedViewerGeneration, ownerPath)
            ) return@synchronized null
            claimNetworkOwnershipRetirement(flight)
            if (!flight.retirement.retire(key, expectedViewerGeneration)) return@synchronized null
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                flight.lease,
                "validated_network_flight_deadline",
            )
            if (!detachFlightForForegroundLeaveLocked(flight)) return@synchronized null
            flight
        } ?: return false
        completeDetachedFlightForegroundLeave(retired)
        Log.w(
            "ViewerPerf",
            "ntk_strict_validated_flight_replaced path=$key," +
                "viewerGeneration=$expectedViewerGeneration," +
                "discoveryGeneration=$expectedDiscoveryGeneration",
        )
        return true
    }

    data class AdjacentValidatedFlightObservation(
        val targetEpisodePath: String,
        val predecessorEpisodePath: String,
        val viewerGeneration: Long,
        val viewerOwnerEpisodePath: String,
        val validatedEpoch: Long,
        val discoveryGeneration: Long,
        val startedAtMs: Long,
        val phase: NtkValidatedAdjacentFlightPhase,
        val physicalPhaseEnteredAtMs: Long,
        val predecessorReadyAtMs: Long,
        val eligibleSinceMs: Long,
        val observedAtMs: Long,
    )

    /**
     * Observes only an exact adjacent Flight which has crossed every normal reading-order gate.
     * Control/predecessor waiters are intentionally invisible even if a control-only profile has
     * entered foreground network. A route-recovery reservation is visible only in its explicit
     * post-network slot-held phase.
     */
    @JvmStatic
    fun adjacentValidatedFlightObservation(
        targetPath: String?,
        predecessorPath: String?,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String?,
        validatedEpoch: Long,
    ): AdjacentValidatedFlightObservation? {
        val target = normalizedPath(targetPath) ?: return null
        val predecessor = normalizedPath(predecessorPath) ?: return null
        val owner = normalizedPath(expectedOwnerEpisodePath) ?: return null
        if (expectedViewerGeneration <= 0L || validatedEpoch <= 0L ||
            !ntkAdjacentOwnerAllowsTarget(owner, target) ||
            !ntkAdjacentOwnerAllowsTarget(predecessor, target)
        ) return null
        val retirementKey = ValidatedAdjacentRetirementKey(
            expectedViewerGeneration,
            owner,
            predecessor,
            target,
        )
        return synchronized(flightLifecycleLock(target)) {
            val flight = flights[target] ?: return@synchronized null
            val lastRetiredEpoch = validatedAdjacentRetirementEpochs[retirementKey] ?: 0L
            val candidate = synchronized(flight) {
                validatedAdjacentRetirementCandidate(
                    flight,
                    target,
                    predecessor,
                    expectedViewerGeneration,
                    owner,
                    validatedEpoch,
                    lastRetiredEpoch,
                )
            }
            val eligibleSinceMs =
                NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(candidate)
                    ?: return@synchronized null
            val phaseEnteredAtMs = when (candidate.phase) {
                NtkValidatedAdjacentFlightPhase.GATE_RELEASED ->
                    candidate.predecessorReadyAtMs
                NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED ->
                    candidate.foregroundNetworkEnteredAtMs
                NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD ->
                    candidate.routeRecoverySlotHeldAtMs
                NtkValidatedAdjacentFlightPhase.GATE_WAIT -> return@synchronized null
            }
            AdjacentValidatedFlightObservation(
                target,
                predecessor,
                expectedViewerGeneration,
                owner,
                validatedEpoch,
                candidate.discoveryGeneration,
                candidate.startedAtMs,
                candidate.phase,
                phaseEnteredAtMs,
                candidate.predecessorReadyAtMs,
                eligibleSinceMs,
                SystemClock.elapsedRealtime(),
            )
        }
    }

    /**
     * Retires the still-current exact observation once for its validated epoch. The epoch ledger
     * deliberately omits discovery generation, so a replacement cannot also be retired by a late
     * callback from the same network edge.
     */
    @JvmStatic
    fun retireObservedAdjacentFlightForValidatedReplacement(
        observation: AdjacentValidatedFlightObservation,
    ): Boolean {
        val target = normalizedPath(observation.targetEpisodePath) ?: return false
        val predecessor = normalizedPath(observation.predecessorEpisodePath) ?: return false
        val owner = normalizedPath(observation.viewerOwnerEpisodePath) ?: return false
        if (target != observation.targetEpisodePath ||
            predecessor != observation.predecessorEpisodePath ||
            owner != observation.viewerOwnerEpisodePath ||
            observation.viewerGeneration <= 0L || observation.validatedEpoch <= 0L ||
            observation.discoveryGeneration <= 0L || observation.startedAtMs < 0L ||
            !ntkAdjacentOwnerAllowsTarget(owner, target) ||
            !ntkAdjacentOwnerAllowsTarget(predecessor, target)
        ) return false
        val retirementKey = ValidatedAdjacentRetirementKey(
            observation.viewerGeneration,
            owner,
            predecessor,
            target,
        )
        val retired = synchronized(flightLifecycleLock(target)) {
            val flight = flights[target] ?: return@synchronized null
            if (flight.lease.generation.value != observation.discoveryGeneration ||
                flight.startedAtMs != observation.startedAtMs
            ) return@synchronized null
            val lastRetiredEpoch = validatedAdjacentRetirementEpochs[retirementKey] ?: 0L
            val claimed = synchronized(flight) {
                val candidate = validatedAdjacentRetirementCandidate(
                    flight,
                    target,
                    predecessor,
                    observation.viewerGeneration,
                    owner,
                    observation.validatedEpoch,
                    lastRetiredEpoch,
                )
                val currentEligibleSinceMs =
                    NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(candidate)
                val currentPhysicalPhaseEnteredAtMs = when (candidate.phase) {
                    NtkValidatedAdjacentFlightPhase.GATE_RELEASED ->
                        candidate.predecessorReadyAtMs
                    NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED ->
                        candidate.foregroundNetworkEnteredAtMs
                    NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD ->
                        candidate.routeRecoverySlotHeldAtMs
                    NtkValidatedAdjacentFlightPhase.GATE_WAIT -> 0L
                }
                if (currentEligibleSinceMs == null ||
                    candidate.phase != observation.phase ||
                    currentPhysicalPhaseEnteredAtMs != observation.physicalPhaseEnteredAtMs ||
                    candidate.predecessorReadyAtMs != observation.predecessorReadyAtMs ||
                    currentEligibleSinceMs != observation.eligibleSinceMs
                ) {
                    false
                } else {
                    when (candidate.phase) {
                        NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED ->
                            claimNetworkOwnershipRetirement(flight)
                        NtkValidatedAdjacentFlightPhase.GATE_RELEASED ->
                            claimNetworkOwnershipRetirement(flight)
                        NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD ->
                            flight.networkOwnershipRetiring.get()
                        NtkValidatedAdjacentFlightPhase.GATE_WAIT -> false
                    }
                }
            }
            if (!claimed ||
                !flight.retirement.retire(target, observation.viewerGeneration)
            ) return@synchronized null
            validatedAdjacentRetirementEpochs[retirementKey] = observation.validatedEpoch
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                flight.lease,
                "validated_network_adjacent_flight_deadline",
            )
            if (!detachFlightForForegroundLeaveLocked(flight)) return@synchronized null
            flight
        } ?: return false
        completeDetachedFlightForegroundLeave(retired)
        Log.w(
            "ViewerPerf",
            "ntk_strict_validated_adjacent_flight_replaced target=$target," +
                "predecessor=$predecessor,viewerOwnerPath=$owner," +
                "viewerGeneration=${observation.viewerGeneration}," +
                "discoveryGeneration=${observation.discoveryGeneration}," +
                "validatedEpoch=${observation.validatedEpoch},phase=${observation.phase}",
        )
        return true
    }

    private fun validatedAdjacentRetirementCandidate(
        flight: Flight,
        target: String,
        predecessor: String,
        expectedViewerGeneration: Long,
        expectedOwner: String,
        validatedEpoch: Long,
        lastRetiredEpoch: Long,
    ): NtkValidatedAdjacentFlightRetirementCandidate =
        NtkValidatedAdjacentFlightRetirementCandidate(
            exactAdjacentIdentity = flight.episodePath == target &&
                flight.adjacentPredecessorEpisodePath == predecessor &&
                flight.viewerGeneration == expectedViewerGeneration &&
                flight.viewerOwnerEpisodePath == expectedOwner &&
                flight.adjacentPredecessorGate,
            discoveryGeneration = flight.lease.generation.value,
            startedAtMs = flight.startedAtMs,
            foregroundNetworkEntered = flight.foregroundNetworkEntered.get(),
            foregroundNetworkEnteredAtMs = flight.foregroundNetworkEnteredAtMs.get(),
            phase = flight.validatedAdjacentPhase.get(),
            routeRecoverySlotHeldAtMs = flight.routeRecoverySlotHeldAtMs.get(),
            controlReady = flight.adjacentControlReady.isDone,
            predecessorReady = flight.adjacentPredecessorComplete.isDone,
            predecessorReadyAtMs = flight.adjacentPredecessorReadyAtMs.get(),
            completed = flight.completed.get(),
            retired = flight.retirement.isRetired(),
            networkOwnershipRetiring = flight.networkOwnershipRetiring.get(),
            authorityReady =
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(target) != null,
            activeViewer = ViewerTelemetry.isActiveViewer(
                expectedViewerGeneration,
                expectedOwner,
            ),
            validatedEpoch = validatedEpoch,
            lastRetiredValidatedEpoch = lastRetiredEpoch,
        )

    /**
     * A successful Flight remains the source-lifetime owner after its worker exits. If that exact
     * source later becomes terminal-closing, the completed Flight must be detached before a
     * validated-network admission can publish a replacement generation.
     */
    private fun retireCompletedTerminalCurrentFlightForValidatedReplacement(
        path: String,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String,
    ): Boolean {
        val retired = synchronized(flightLifecycleLock(path)) {
            val flight = flights[path] ?: return@synchronized null
            if (flight.viewerGeneration != expectedViewerGeneration ||
                flight.viewerOwnerEpisodePath != expectedOwnerEpisodePath ||
                !flight.completed.get() || flight.retirement.isRetired() ||
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null ||
                !ViewerTelemetry.isActiveViewer(
                    expectedViewerGeneration,
                    expectedOwnerEpisodePath,
                )
            ) return@synchronized null
            val snapshot = NtkSourceSpoolRegistry.currentSnapshot(path)
            if (snapshot != null &&
                (snapshot.generation != flight.lease.generation.value ||
                    snapshot.state.ordinal < NtkSourceState.TERMINAL_CLOSING.ordinal)
            ) return@synchronized null
            claimNetworkOwnershipRetirement(flight)
            if (!flight.retirement.retire(path, expectedViewerGeneration)) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                flight.lease,
                "validated_network_completed_terminal",
            )
            if (!detachFlightForForegroundLeaveLocked(flight)) return@synchronized null
            flight
        } ?: return false
        completeDetachedFlightForegroundLeave(retired)
        Log.w(
            "ViewerPerf",
            "ntk_strict_validated_completed_terminal_replaced path=$path," +
                "viewerGeneration=$expectedViewerGeneration," +
                "discoveryGeneration=${retired.lease.generation.value}",
        )
        return true
    }

    /**
     * Detaches a completed adjacent source owner only after its published registry generation has
     * become unusable. A live completed manifest remains authoritative and ordinary gate waiters
     * cannot satisfy the completed predicate.
     */
    private fun retireCompletedUnusableAdjacentFlightForReplacement(
        targetPath: String,
        predecessorPath: String,
        expectedViewerGeneration: Long,
        expectedOwnerEpisodePath: String,
    ): Boolean {
        if (expectedViewerGeneration <= 0L ||
            !ntkAdjacentOwnerAllowsTarget(expectedOwnerEpisodePath, targetPath) ||
            !ntkAdjacentOwnerAllowsTarget(predecessorPath, targetPath)
        ) return false
        val retired = synchronized(flightLifecycleLock(targetPath)) {
            val flight = flights[targetPath] ?: return@synchronized null
            if (flight.episodePath != targetPath ||
                flight.adjacentPredecessorEpisodePath != predecessorPath ||
                flight.viewerGeneration != expectedViewerGeneration ||
                flight.viewerOwnerEpisodePath != expectedOwnerEpisodePath ||
                !flight.adjacentPredecessorGate || !flight.completed.get() ||
                flight.retirement.isRetired() ||
                !ViewerTelemetry.isActiveViewer(
                    expectedViewerGeneration,
                    expectedOwnerEpisodePath,
                )
            ) return@synchronized null
            val snapshot = NtkSourceSpoolRegistry.currentSnapshot(targetPath)
            val registryUnusable = NtkCompletedAdjacentRegistryPolicy.isUnusable(
                authorityReady =
                    NtkSourceSpoolRegistry.currentAuthoritativeManifest(targetPath) != null,
                snapshotPresent = snapshot != null,
                snapshotMatchesDiscovery =
                    snapshot?.generation == flight.lease.generation.value,
                snapshotTerminalClosing = snapshot != null &&
                    snapshot.state.ordinal >= NtkSourceState.TERMINAL_CLOSING.ordinal,
            )
            if (!registryUnusable || !claimNetworkOwnershipRetirement(flight) ||
                !flight.retirement.retire(targetPath, expectedViewerGeneration)
            ) return@synchronized null
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                flight.lease,
                "strict_completed_adjacent_registry_unusable",
            )
            if (!detachFlightForForegroundLeaveLocked(flight)) return@synchronized null
            flight
        } ?: return false
        completeDetachedFlightForegroundLeave(retired)
        Log.w(
            "ViewerPerf",
            "ntk_strict_completed_adjacent_registry_unusable_retired " +
                "target=$targetPath,predecessor=$predecessorPath," +
                "viewerOwnerPath=$expectedOwnerEpisodePath," +
                "viewerGeneration=$expectedViewerGeneration," +
                "discoveryGeneration=${retired.lease.generation.value}",
        )
        return true
    }

    /**
     * Source-session construction can race ahead of the later resident-body claim callback. Carry
     * the already-open discovery gate into that new session so its first 600 ms sample does not
     * wait for a redundant actor round trip. This is observation only and cannot open a gate.
     */
    internal fun isAdjacentBodyGateOpen(
        path: String?,
        viewerGeneration: Long,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (viewerGeneration <= 0L) return false
        val flight = flights[key] ?: return false
        return flight.viewerGeneration == viewerGeneration &&
            flight.adjacentPredecessorGate &&
            flight.adjacentPredecessorComplete.isDone &&
            !flight.retirement.isRetired()
    }

    /** Observation only: callers use this to park UI retry polling while the document-only owner
     * waits on the independent predecessor-complete gate. */
    internal fun isAdjacentControlGateOpen(
        path: String?,
        viewerGeneration: Long,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (viewerGeneration <= 0L) return false
        val flight = flights[key] ?: return false
        return flight.viewerGeneration == viewerGeneration &&
            flight.adjacentPredecessorGate &&
            flight.adjacentControlReady.isDone &&
            !flight.adjacentPredecessorComplete.isDone &&
            !flight.retirement.isRetired()
    }

    /**
     * Opens only the target document/challenge overlap for the one exact current-resume profile.
     * The caller has proved every required current image body reached EOF; it grants no target API,
     * body, source or decode admission. The generation marker closes release-before-flight races.
     */
    @JvmStatic
    fun releaseAdjacentControlAfterPredecessorBodiesResident(
        predecessorPath: String?,
        targetPath: String?,
        expectedViewerGeneration: Long,
        expectedViewerOwnerPath: String?,
    ): Int {
        val key = normalizedPath(predecessorPath) ?: return 0
        val targetKey = normalizedPath(targetPath) ?: return 0
        if (!targetKey.startsWith("/webtoon/") ||
            !ntkAdjacentOwnerAllowsTarget(key, targetKey)
        ) return 0
        if (!NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
                Build.FINGERPRINT,
                Build.MODEL,
                Build.HARDWARE,
                Build.PRODUCT,
            )
        ) return 0
        val ownerPath = normalizedPath(expectedViewerOwnerPath) ?: return 0
        val generation = expectedViewerGeneration
        if (generation <= 0L || !ViewerTelemetry.isActiveViewer(generation, ownerPath)) return 0
        return synchronized(flightLifecycleLock("adjacent-gate:$key")) {
            if (!ViewerTelemetry.isActiveViewer(generation, ownerPath)) return@synchronized 0
            val predecessorReconciled = completedAdjacentTargets.entries.any { entry ->
                entry.value == generation && entry.key.predecessorPath == key
            }
            if (predecessorReconciled &&
                completedAdjacentTargets[adjacentGateKey(key, targetKey)] != generation
            ) return@synchronized 0
            bodyResidentAdjacentTargets[adjacentGateKey(key, targetKey)] = generation
            var released = 0
            flights.values.forEach { flight ->
                if (flight.viewerGeneration == generation &&
                    flight.adjacentPredecessorGate &&
                    flight.directWifiAdjacentBodyGate &&
                    flight.episodePath == targetKey &&
                    flight.adjacentPredecessorEpisodePath == key
                ) {
                    if (releaseAdjacentControlGate(flight) == AdjacentBodyGateRelease.OPENED) {
                        released++
                    }
                }
            }
            released
        }
    }

    /**
     * Opens all target-work admission for adjacent flights whose immediate
     * predecessor is now completely drawable. The generation-scoped marker also covers the
     * normal ordering where completion wins just before the adjacent flight is created.
     */
    @JvmStatic
    fun releaseAdjacentBodiesAfterPredecessorComplete(
        path: String?,
        expectedTargetPath: String?,
        expectedViewerGeneration: Long,
        expectedViewerOwnerPath: String?,
    ): Int {
        val key = normalizedPath(path) ?: return 0
        val expectedTarget = expectedTargetPath?.let(::normalizedPath)
        if (expectedTargetPath != null && expectedTarget == null) return 0
        if (expectedTarget != null && !ntkAdjacentOwnerAllowsTarget(key, expectedTarget)) return 0
        val ownerPath = normalizedPath(expectedViewerOwnerPath) ?: return 0
        val generation = expectedViewerGeneration
        // A continuously appended reader retains the launch episode as its telemetry owner, so an
        // appended predecessor is intentionally not ViewerTelemetry's active episode. The exact
        // path match plus the active generation keeps this release scoped to the foreground
        // Session while allowing B-complete to unlock B->C.
        if (generation <= 0L || !ViewerTelemetry.isActiveViewer(generation, ownerPath)) return 0
        return synchronized(flightLifecycleLock("adjacent-gate:$key")) {
            if (!ViewerTelemetry.isActiveViewer(generation, ownerPath)) return@synchronized 0
            if (expectedTarget != null) {
                // Publish one authoritative pair, then remove every older pair for the same
                // predecessor before any flight admission can observe the ledger. Exact image
                // authority for another path is not evidence that it is the next episode.
                completedAdjacentTargets[adjacentGateKey(key, expectedTarget)] = generation
                completedAdjacentTargets.entries.forEach { entry ->
                    if (entry.value == generation &&
                        entry.key.predecessorPath == key &&
                        entry.key.targetPath != expectedTarget
                    ) {
                        completedAdjacentTargets.remove(entry.key, entry.value)
                    }
                }
                bodyResidentAdjacentTargets.entries.forEach { entry ->
                    if (entry.value == generation &&
                        entry.key.predecessorPath == key &&
                        entry.key.targetPath != expectedTarget
                    ) {
                        bodyResidentAdjacentTargets.remove(entry.key, entry.value)
                    }
                }
                // A mismatched flight may already have crossed its control/body gate. Retire it
                // regardless of marker membership or gate state so it cannot publish later.
                val mismatches = flights.values.filter { flight ->
                    flight.viewerGeneration == generation &&
                        flight.adjacentPredecessorGate &&
                        flight.adjacentPredecessorEpisodePath == key &&
                        flight.episodePath != expectedTarget
                }
                mismatches.forEach { stale ->
                    retireAdjacentTargetForReplacement(
                        stale.episodePath,
                        stale.lease.generation.value,
                        generation,
                        "adjacent_target_reconciled",
                    )
                }
            }
            val reconciledTargets = completedAdjacentTargets.entries
                .filter { entry ->
                    entry.value == generation && entry.key.predecessorPath == key
                }
                .map { it.key.targetPath }
                .toSet()
            if (expectedTarget == null && reconciledTargets.isEmpty()) {
                completedAdjacentPredecessors[key] = generation
            }
            var released = 0
            flights.values.forEach { flight ->
                if (flight.viewerGeneration == generation &&
                    flight.adjacentPredecessorGate &&
                    (if (reconciledTargets.isEmpty()) {
                        expectedTarget == null || flight.episodePath == expectedTarget
                    } else {
                        flight.episodePath in reconciledTargets
                    }) &&
                    flight.adjacentPredecessorEpisodePath == key
                ) {
                    if (releaseAdjacentBodyGate(flight) == AdjacentBodyGateRelease.OPENED) {
                        released++
                    }
                }
            }
            released
        }
    }

    /**
     * Publishes only real, clamped Surface demand. It cannot select a structural target, release a
     * body gate, or create a discovery flight. The generation marker closes the ordering where the
     * boundary arrives immediately before the exact target flight is admitted.
     */
    @JvmStatic
    fun releaseAdjacentPhysicalBoundaryDemand(
        path: String?,
        expectedTargetPath: String?,
        expectedViewerGeneration: Long,
        expectedViewerOwnerPath: String?,
    ): Int {
        val key = normalizedPath(path) ?: return 0
        val expectedTarget = expectedTargetPath?.let(::normalizedPath)
        if (expectedTargetPath != null && expectedTarget == null) return 0
        if (expectedTarget != null && !ntkAdjacentOwnerAllowsTarget(key, expectedTarget)) return 0
        val ownerPath = normalizedPath(expectedViewerOwnerPath) ?: return 0
        val generation = expectedViewerGeneration
        if (generation <= 0L || !ViewerTelemetry.isActiveViewer(generation, ownerPath)) return 0
        return synchronized(flightLifecycleLock("adjacent-gate:$key")) {
            if (!ViewerTelemetry.isActiveViewer(generation, ownerPath)) return@synchronized 0
            if (expectedTarget == null) {
                physicalBoundaryAdjacentPredecessors[key] = generation
            } else {
                physicalBoundaryAdjacentTargets[adjacentGateKey(key, expectedTarget)] = generation
                physicalBoundaryAdjacentTargets.entries.forEach { entry ->
                    if (entry.value == generation &&
                        entry.key.predecessorPath == key &&
                        entry.key.targetPath != expectedTarget
                    ) {
                        physicalBoundaryAdjacentTargets.remove(entry.key, entry.value)
                    }
                }
            }
            var released = 0
            flights.values.forEach { flight ->
                if (flight.viewerGeneration == generation &&
                    flight.adjacentPredecessorGate &&
                    flight.adjacentPredecessorEpisodePath == key &&
                    (expectedTarget == null || flight.episodePath == expectedTarget) &&
                    flight.adjacentPhysicalBoundaryDemand.complete(Unit)
                ) {
                    released++
                }
            }
            Log.d(
                "ViewerPerf",
                "ntk_adjacent_physical_boundary_demand predecessor=$key," +
                    "target=${expectedTarget.orEmpty()},generation=$generation,released=$released",
            )
            released
        }
    }

    private fun enterForegroundNetworkIfNeeded(flight: Flight) {
        val entered = synchronized(flight) {
            if (flight.foregroundNetworkEntered.get()) return
            if (flight.networkOwnershipRetiring.get() ||
                flight.foregroundNetworkLeaveStarted.get() ||
                flight.retirement.isRetired() ||
                flights[flight.episodePath] !== flight ||
                !isViewerOwnerActive(flight)
            ) {
                throw InterruptedIOException("Viewer ownership retired before foreground enter")
            }
            if (!flight.foregroundNetworkEntered.compareAndSet(false, true)) return
            try {
                flight.client.enterNtkStrictForegroundNetwork(
                    flight.episodePath,
                    flight.viewerGeneration,
                )
                flight.foregroundNetworkEnteredAtMs.set(SystemClock.elapsedRealtime())
                flight.validatedAdjacentPhase.set(
                    NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED,
                )
                true
            } catch (failure: Throwable) {
                // The client may have installed its path+generation owner before a later cutover
                // callback failed. Preserve our logical enter claim so the common detach path
                // performs the one balancing client leave instead of leaking that partial owner.
                throw failure
            }
        }
        if (!entered) return
        try {
            // WebView teardown is marshalled to main and may wait for that callback. Never retain
            // the flight monitor across this compatibility barrier: the main thread can be behind
            // the ReaderControl body-gate release, which owns the same monitor briefly. Holding it
            // here creates a deterministic two-second lock inversion on short resume tails.
            flight.client.cancelNtkWebViewFallbacks()
        } catch (failure: Throwable) {
            leaveForegroundNetworkIfEntered(flight)
            throw failure
        }
        synchronized(flight) {
            if (flight.networkOwnershipRetiring.get() ||
                flight.retirement.isRetired() ||
                flights[flight.episodePath] !== flight ||
                !isViewerOwnerActive(flight)
            ) {
                throw InterruptedIOException("Viewer ownership retired during foreground enter")
            }
        }
    }

    /** Exactly-once balance for a Flight's path+viewer foreground admission. */
    private fun leaveForegroundNetworkIfEntered(flight: Flight) {
        val action = synchronized(flight) {
            NtkForegroundNetworkLeavePolicy.action(
                flight.foregroundNetworkEntered.get(),
                flight.foregroundNetworkLeaveStarted.get(),
                flight.foregroundNetworkLeaveCompleted.isDone,
            ).also { selected ->
                if (selected == NtkForegroundNetworkLeaveAction.LEAVE) {
                    check(flight.foregroundNetworkEntered.compareAndSet(true, false))
                    check(flight.foregroundNetworkLeaveStarted.compareAndSet(false, true))
                    flight.validatedAdjacentPhase.compareAndSet(
                        NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED,
                        NtkValidatedAdjacentFlightPhase.GATE_WAIT,
                    )
                }
            }
        }
        when (action) {
            NtkForegroundNetworkLeaveAction.LEAVE -> {
                try {
                    flight.client.leaveNtkStrictForegroundNetwork(
                        flight.episodePath,
                        flight.viewerGeneration,
                    )
                } finally {
                    flight.foregroundNetworkLeaveCompleted.complete(Unit)
                }
            }
            NtkForegroundNetworkLeaveAction.AWAIT_EXISTING_LEAVE ->
                awaitForegroundNetworkLeaveUninterruptibly(flight)
            NtkForegroundNetworkLeaveAction.NONE -> Unit
        }
    }

    private fun awaitForegroundNetworkLeaveUninterruptibly(flight: Flight) {
        var interrupted = false
        while (true) {
            try {
                flight.foregroundNetworkLeaveCompleted.get()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (wrapped: ExecutionException) {
                throw (wrapped.cause ?: wrapped)
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** Must run under this Flight's path lock before its map identity becomes replaceable. */
    private fun detachFlightForForegroundLeaveLocked(flight: Flight): Boolean {
        val path = flight.episodePath
        check(Thread.holdsLock(flightLifecycleLock(path))) {
            "Strict Flight detach requires its path lifecycle lock"
        }
        if (flights[path] !== flight) return false
        val existingBarrier = foregroundNetworkLeaveBarriers.putIfAbsent(path, flight)
        check(existingBarrier == null || existingBarrier === flight) {
            "Another strict Flight owns the foreground leave barrier"
        }
        if (flights.remove(path, flight)) return true
        foregroundNetworkLeaveBarriers.remove(path, flight)
        return false
    }

    /** Balances or joins the one client leave, then reopens same-path Flight admission. */
    private fun completeDetachedFlightForegroundLeave(flight: Flight) {
        try {
            leaveForegroundNetworkIfEntered(flight)
        } finally {
            synchronized(flightLifecycleLock(flight.episodePath)) {
                foregroundNetworkLeaveBarriers.remove(flight.episodePath, flight)
            }
        }
    }

    private fun releaseAdjacentBodyGate(flight: Flight): AdjacentBodyGateRelease {
        return synchronized(flight) {
            if (flight.adjacentPredecessorComplete.isDone) {
                publishAdjacentPredecessorReady(flight)
                return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
            }
            val routeRecoverySlotHeld =
                flight.validatedAdjacentPhase.get() ==
                    NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD &&
                    flight.networkOwnershipRetiring.get() &&
                    !flight.foregroundNetworkEntered.get()
            if ((flight.networkOwnershipRetiring.get() && !routeRecoverySlotHeld) ||
                flight.retirement.isRetired() ||
                flights[flight.episodePath] !== flight ||
                !isViewerOwnerActive(flight)
            ) {
                return@synchronized AdjacentBodyGateRelease.FAILED
            }
            try {
                flight.adjacentControlReady.complete(Unit)
                if (!flight.adjacentPredecessorComplete.complete(Unit)) {
                    publishAdjacentPredecessorReady(flight)
                    return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
                }
                publishAdjacentPredecessorReady(flight)
                ViewerTelemetry.adjacentWorkStarted(flight.adjacentPredecessorEpisodePath)
                Log.d(
                    "ViewerPerf",
                    "ntk_adjacent_body_gate_release " +
                        "predecessor=${flight.adjacentPredecessorEpisodePath}," +
                        "target=${flight.episodePath},generation=${flight.viewerGeneration}",
                )
                AdjacentBodyGateRelease.OPENED
            } catch (failure: Throwable) {
                Log.e(
                    "ViewerPerf",
                    "ntk_adjacent_body_gate_release_failed target=${flight.episodePath}",
                    failure,
                )
                AdjacentBodyGateRelease.FAILED
            }
        }
    }

    /** Full reading-order release; a control-only event can never publish this phase. */
    private fun publishAdjacentPredecessorReady(flight: Flight) {
        flight.adjacentPredecessorReadyAtMs.compareAndSet(
            0L,
            SystemClock.elapsedRealtime(),
        )
        flight.validatedAdjacentPhase.compareAndSet(
            NtkValidatedAdjacentFlightPhase.GATE_WAIT,
            NtkValidatedAdjacentFlightPhase.GATE_RELEASED,
        )
    }

    private fun releaseAdjacentControlGate(flight: Flight): AdjacentBodyGateRelease {
        return synchronized(flight) {
            if (flight.adjacentControlReady.isDone) {
                return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
            }
            if (flight.networkOwnershipRetiring.get() ||
                flight.retirement.isRetired() ||
                flights[flight.episodePath] !== flight ||
                !isViewerOwnerActive(flight)
            ) {
                return@synchronized AdjacentBodyGateRelease.FAILED
            }
            if (!flight.adjacentControlReady.complete(Unit)) {
                return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
            }
            Log.d(
                "ViewerPerf",
                "ntk_adjacent_control_gate_release " +
                    "predecessor=${flight.adjacentPredecessorEpisodePath}," +
                    "target=${flight.episodePath},generation=${flight.viewerGeneration}",
            )
            AdjacentBodyGateRelease.OPENED
        }
    }

    /** Orders every terminal leave against a possibly concurrent adjacent gate release. */
    private fun claimNetworkOwnershipRetirement(flight: Flight): Boolean = synchronized(flight) {
        if (flight.networkOwnershipRetiring.get()) {
            false
        } else {
            flight.networkOwnershipRetiring.set(true)
            true
        }
    }

    /**
     * Cancels only the coordinator flight owned by this exact viewer generation. An old Activity
     * can therefore never cancel a newer same-path viewer generation.
     */
    @JvmStatic
    fun retireViewerOwnership(
        path: String?,
        viewerGeneration: Long,
        reason: String?
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (viewerGeneration <= 0L) return false
        val ownedPaths = flights.values
            .filter {
                it.viewerGeneration == viewerGeneration &&
                    it.viewerOwnerEpisodePath.equals(key, ignoreCase = true)
            }
            .map(Flight::episodePath)
            .distinct()
            .sortedBy { if (it.equals(key, ignoreCase = true)) 0 else 1 }
        var retiredAny = false
        for (ownedPath in ownedPaths) {
            val flight = synchronized(flightLifecycleLock(ownedPath)) {
                val owned = flights[ownedPath] ?: return@synchronized null
                if (owned.viewerGeneration != viewerGeneration ||
                    !owned.viewerOwnerEpisodePath.equals(key, ignoreCase = true)
                ) return@synchronized null
                // Body-gate release owns the same monitor while entering the target foreground
                // network. Claim retirement here first: either release finishes before this claim
                // and leave below balances its enter, or every stale release observes the claim and
                // exits without entering after leave.
                claimNetworkOwnershipRetirement(owned)
                if (!owned.retirement.retire(ownedPath, viewerGeneration)) {
                    return@synchronized null
                }
            // Detach the terminal lease before releasing this path's flight slot. Its asynchronous
            // close barrier is generation-routed through a tombstone and cannot mutate a replacement.
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    owned.lease,
                    "strict_exact_owner_retired_${safeReason(reason)}"
                )
                if (!detachFlightForForegroundLeaveLocked(owned)) {
                    return@synchronized null
                }
                owned
            } ?: continue
            Log.d(
                "ViewerPerf",
                "ntk_strict_exact_owner_retired path=$ownedPath," +
                    "viewerOwnerPath=$key,viewerGeneration=$viewerGeneration," +
                    "discoveryGeneration=${flight.lease.generation.value}," +
                    "reason=${safeReason(reason)}"
            )
            completeDetachedFlightForegroundLeave(flight)
            retiredAny = true
        }
        completedAdjacentPredecessors.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                completedAdjacentPredecessors.remove(entry.key, entry.value)
            }
        }
        physicalBoundaryAdjacentPredecessors.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                physicalBoundaryAdjacentPredecessors.remove(entry.key, entry.value)
            }
        }
        physicalBoundaryAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                physicalBoundaryAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        bodyResidentAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                bodyResidentAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        completedAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                completedAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        validatedAdjacentRetirementEpochs.entries.forEach { entry ->
            if (entry.key.viewerGeneration == viewerGeneration &&
                entry.key.viewerOwnerPath == key
            ) {
                validatedAdjacentRetirementEpochs.remove(entry.key, entry.value)
            }
        }
        return retiredAny
    }

    /**
     * Detaches one failed adjacent exact generation so the same active viewer can replace it.
     *
     * This is intentionally narrower than [retireViewerOwnership]: the launch episode and every
     * other healthy adjacent flight remain owned. Both viewer and discovery generations must match,
     * and a launch/current flight can never enter this replacement path.
     */
    @JvmStatic
    fun retireAdjacentTargetForReplacement(
        path: String?,
        discoveryGeneration: Long,
        viewerGeneration: Long,
        reason: String?,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (discoveryGeneration <= 0L || viewerGeneration <= 0L) return false
        val flight = synchronized(flightLifecycleLock(key)) {
            val owned = flights[key] ?: return@synchronized null
            if (owned.viewerGeneration != viewerGeneration ||
                owned.lease.generation.value != discoveryGeneration ||
                owned.viewerOwnerEpisodePath.equals(key, ignoreCase = true)
            ) {
                return@synchronized null
            }
            claimNetworkOwnershipRetirement(owned)
            if (!owned.retirement.retire(key, viewerGeneration)) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_adjacent_recovery_${safeReason(reason)}",
            )
            if (!detachFlightForForegroundLeaveLocked(owned)) return@synchronized null
            owned
        } ?: return false
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_adjacent_recovery_retired path=$key," +
                "viewerOwnerPath=${flight.viewerOwnerEpisodePath}," +
                "viewerGeneration=$viewerGeneration," +
                "discoveryGeneration=$discoveryGeneration," +
                "reason=${safeReason(reason)}",
        )
        completeDetachedFlightForegroundLeave(flight)
        return true
    }

    /**
     * Replaces only an unfinished adjacent flight whose image work was cancelled after launch.
     *
     * Active-scroll cleanup records the exact path and cancellation time in [ReaderImageCache].
     * Checking that evidence under the flight lifecycle lock prevents a late timeout from retiring
     * a flight which has already published authority, while still allowing the same active reader
     * generation to recover immediately instead of joining a permanently empty discovery.
     */
    @JvmStatic
    fun retireCancelledAdjacentTargetForReplacement(
        path: String?,
        viewerGeneration: Long,
        reason: String?,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (viewerGeneration <= 0L) return false
        val flight = synchronized(flightLifecycleLock(key)) {
            val owned = flights[key] ?: return@synchronized null
            if (owned.viewerGeneration != viewerGeneration ||
                owned.viewerOwnerEpisodePath.equals(key, ignoreCase = true) ||
                owned.completed.get() ||
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(key) != null ||
                !ReaderImageCache.wasNtkEpisodeWorkCancelledSince(key, owned.startedAtMs)
            ) {
                return@synchronized null
            }
            claimNetworkOwnershipRetirement(owned)
            if (!owned.retirement.retire(key, viewerGeneration)) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_adjacent_cancelled_${safeReason(reason)}",
            )
            if (!detachFlightForForegroundLeaveLocked(owned)) return@synchronized null
            owned
        } ?: return false
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_adjacent_cancelled_retired path=$key," +
                "viewerOwnerPath=${flight.viewerOwnerEpisodePath}," +
                "viewerGeneration=$viewerGeneration," +
                "discoveryGeneration=${flight.lease.generation.value}," +
                "reason=${safeReason(reason)}",
        )
        completeDetachedFlightForegroundLeave(flight)
        return true
    }

    /** Retires one completed episode after continuous forward reading has removed its page table. */
    @JvmStatic
    fun retireConsumedTargetOwnership(
        path: String?,
        discoveryGeneration: Long,
        viewerGeneration: Long,
        viewerOwnerPath: String?,
        reason: String?,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        val ownerKey = normalizedPath(viewerOwnerPath) ?: return false
        if (discoveryGeneration <= 0L || viewerGeneration <= 0L) return false
        val flight = synchronized(flightLifecycleLock(key)) {
            val owned = flights[key] ?: return@synchronized null
            if (owned.viewerGeneration != viewerGeneration ||
                owned.lease.generation.value != discoveryGeneration ||
                !owned.viewerOwnerEpisodePath.equals(ownerKey, ignoreCase = true) ||
                !owned.completed.get()
            ) {
                return@synchronized null
            }
            claimNetworkOwnershipRetirement(owned)
            if (!owned.retirement.retire(key, viewerGeneration)) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_consumed_${safeReason(reason)}",
            )
            if (!detachFlightForForegroundLeaveLocked(owned)) return@synchronized null
            owned
        } ?: return false
        completedAdjacentPredecessors.remove(key, viewerGeneration)
        physicalBoundaryAdjacentPredecessors.remove(key, viewerGeneration)
        physicalBoundaryAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration &&
                (entry.key.predecessorPath == key || entry.key.targetPath == key)
            ) {
                physicalBoundaryAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        bodyResidentAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration &&
                (entry.key.predecessorPath == key || entry.key.targetPath == key)
            ) {
                bodyResidentAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        completedAdjacentTargets.entries.forEach { entry ->
            if (entry.value == viewerGeneration &&
                (entry.key.predecessorPath == key || entry.key.targetPath == key)
            ) {
                completedAdjacentTargets.remove(entry.key, entry.value)
            }
        }
        validatedAdjacentRetirementEpochs.entries.forEach { entry ->
            if (entry.key.viewerGeneration == viewerGeneration &&
                entry.key.viewerOwnerPath == ownerKey &&
                entry.key.targetPath == key
            ) {
                validatedAdjacentRetirementEpochs.remove(entry.key, entry.value)
            }
        }
        completeDetachedFlightForegroundLeave(flight)
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_consumed_retired path=$key," +
                "viewerOwnerPath=$ownerKey,viewerGeneration=$viewerGeneration," +
                "discoveryGeneration=$discoveryGeneration,reason=${safeReason(reason)}",
        )
        return true
    }

    private fun startIsolatedAck(
        client: CustomHttpClient,
        flight: Flight,
        bootstrap: CustomHttpClient.NtkStrictAckBootstrap,
    ): NtkAckBrowserClient.FlightHandle =
        NtkAckBrowserClient.get(client.context).startAck(
            bootstrap.origin,
            bootstrap.episodePath,
            flight.lease.generation.value,
            bootstrap.userAgent,
            bootstrap.seedCookies,
        )

    /** Starts every target-network prerequisite only after adjacent admission is open. */
    private fun startAckNetworkPrerequisites(
        client: CustomHttpClient,
        flight: Flight,
        path: String,
        ackRoute: AckRoute,
    ) {
        if (isDirectTrustedWebtoon(path) && ackRoute.directTrustedTask == null) {
            val task = FutureTask {
                traceStage("NtkTrustedChallenge") {
                    client.fetchExactNtkTrustedChallengeGrant(
                        ackRoute.bootstrap,
                        flight.physicalCalls,
                    )
                }
            }
            val thread = Thread(task, "ntk-strict-trusted-challenge").apply {
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
            ackRoute.attachDirectTrustedTask(task, thread)
            thread.start()
        }
        if (requiresClickOwnedIsolatedAck(path)) {
            // Slug manhwa cannot bind the document's virtual numeric replica names directly;
            // its exact source table is issued by the signed image API. Numeric manhwa retains
            // the demand-driven observation path.
            ensureIsolatedAck(client, flight, ackRoute)
            Log.d(
                "ViewerPerf",
                "ntk_strict_click_owned_isolated_ack_start path=$path," +
                    "generation=${flight.lease.generation.value}",
            )
        }
    }

    private fun ensureIsolatedAck(
        client: CustomHttpClient,
        flight: Flight,
        ackRoute: AckRoute,
    ): NtkAckBrowserClient.FlightHandle = synchronized(ackRoute) {
        ackRoute.isolatedHandle ?: startIsolatedAck(
            client,
            flight,
            ackRoute.bootstrap,
        ).also { handle -> ackRoute.attachIsolatedHandle(flight, handle) }
    }

    private fun ensureExactNvSeed(
        client: CustomHttpClient,
        flight: Flight,
        ackRoute: AckRoute,
    ): FutureTask<Boolean> = synchronized(ackRoute) {
        ackRoute.nvSeedTask ?: FutureTask {
            traceStage("NtkExactNvSeed") {
                client.ensureExactNtkNvSeed(
                    ackRoute.bootstrap,
                    flight.physicalCalls,
                )
            }
            true
        }.also { task ->
            val thread = Thread(task, "ntk-strict-nv-seed").apply {
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
            ackRoute.attachNvSeedTask(task, thread)
            thread.start()
        }
    }

    /**
     * Returns a validated direct grant, or installs the isolated owner only after the server has
     * explicitly selected its full-challenge branch. No image request is started by this join.
     */
    private fun awaitDirectTrustedGrantOrStartIsolated(
        client: CustomHttpClient,
        flight: Flight,
        ackRoute: AckRoute,
    ): CustomHttpClient.NtkDirectTrustedGrant? {
        val task = ackRoute.directTrustedTask ?: return null
        return try {
            traceStage("NtkTrustedChallengeWait") { task.get() }
        } catch (wrapped: ExecutionException) {
            val cause = wrapped.cause ?: wrapped
            if (cause is CustomHttpClient.NtkStrictFullChallengeRequiredException) {
                requireDiscoveryOwnership(flight, "full_challenge_fallback_start")
                val handle = ensureIsolatedAck(client, flight, ackRoute)
                Log.d(
                    "ViewerPerf",
                    "ntk_strict_full_challenge_isolated_start path=${flight.episodePath}," +
                        "generation=${flight.lease.generation.value}," +
                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}"
                )
                null
            } else {
                throw cause
            }
        }
    }

    private fun runFlight(
        client: CustomHttpClient,
        manga: Manga,
        path: String,
        flight: Flight,
        ackRoute: AckRoute,
    ) {
        var exactInstalled = false
        var clickOwnedAnchor: NtkClickOwnedAnchorQuarantine? = null
        var clickOwnedManhwaProbe: NtkClickOwnedManhwaProbeFrontier? = null
        var streamingDocumentThread: Thread? = null
        var routeRecoveryRequested = false
        var restartSameOriginWithoutResolver = false
        try {
            requireDiscoveryOwnership(flight, "worker_start")
            val directWebtoon = isDirectTrustedWebtoon(path)
            val hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
                Build.FINGERPRINT,
                Build.MODEL,
                Build.HARDWARE,
                Build.PRODUCT,
            )
            val hostGpuEmulatorDirectWifiAdjacentWebtoon =
                directWebtoon && flight.directWifiAdjacentBodyGate && hostGpuEmulatorRuntime
            if (flight.adjacentPredecessorGate) {
                // Every profile uses this control gate. Only the scoped host-emulator webtoon can
                // receive it before the full predecessor-drawable event.
                awaitAdjacentControlReady(flight)
                enterForegroundNetworkIfNeeded(flight)
                startAckNetworkPrerequisites(client, flight, path, ackRoute)
            }
            // Keep the current resume and its exact forward neighbor on one strict OkHttp H2
            // pool. The current episode still owns every resource until all of its required
            // images are ready; only the already-authorized control transport is shared so the
            // neighbor does not pay a second DNS/TLS connection after completion.
            val hostGpuEmulatorDirectWifiWebtoonControlPlane =
                NtkHostGpuEmulatorWebtoonControlPlanePolicy.isEligible(
                    directWebtoon = directWebtoon,
                    emulatorRuntime = hostGpuEmulatorRuntime,
                    directWifiAdjacent = flight.directWifiAdjacentBodyGate,
                    directWifiCurrent = flight.directWifiCurrentViewer,
                    rollingAdmission = flight.rollingAdmission,
                    initialPageIndex = flight.initialPageIndexHint,
                )
            if (!directWebtoon) {
                // Resolve a four-page format sample at the committed click. It downloads no image
                // body and lets uncommon-format pages join the same bounded body race. Every body
                // still validates response headers and encoded magic before private quarantine.
                clickOwnedManhwaProbe = NtkClickOwnedManhwaProbeFrontier.start(
                    manga,
                    path,
                    flight.initialPageIndexHint,
                    flight.directWifiAdjacentBodyGate,
                )
                clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.startFromTrustedPayloadHint(
                    client.context,
                    manga,
                    path,
                    flight.lease.generation.value,
                    clickOwnedManhwaProbe,
                    flight.directWifiAdjacentBodyGate,
                    flight.adjacentPredecessorComplete,
                    initialPageIndexHint = flight.initialPageIndexHint,
                    viewerGeneration = flight.viewerGeneration,
                    adjacentPredecessorEpisodePath = flight.adjacentPredecessorEpisodePath,
                )
                if (clickOwnedAnchor != null) {
                    clickOwnedManhwaProbe = null
                    if (manga.getExactNtkClickPayloadImageCount(path) > 0) {
                        clickOwnedAnchor.releaseForTrustedClickPayloadCount()
                    }
                } else {
                    // No exact click payload count is available on a true cold launch. Bind the
                    // metadata-only 120-page candidate frontier to a private maximum-bound owner
                    // now, after the committed click. The independent fresh document trims
                    // non-existent tail pages before source authority or decode can be published.
                    clickOwnedAnchor =
                        NtkClickOwnedAnchorQuarantine.startFromBoundedNumericCandidates(
                            client.context,
                            manga,
                            path,
                            flight.lease.generation.value,
                            clickOwnedManhwaProbe,
                            flight.directWifiAdjacentBodyGate,
                            flight.adjacentPredecessorComplete,
                            initialPageIndexHint = flight.initialPageIndexHint,
                            viewerGeneration = flight.viewerGeneration,
                            adjacentPredecessorEpisodePath =
                                flight.adjacentPredecessorEpisodePath,
                        )
                    if (clickOwnedAnchor != null) {
                        clickOwnedManhwaProbe = null
                    }
                }
            }
            // Some current episode documents intentionally omit the optional streaming seed.
            // A missing seed only removes the document/API overlap optimization; the complete
            // document still carries the request identity needed to build the exact API request.
            // Model that as a normal nullable result so those episodes take the authoritative
            // post-document path instead of being rejected as malformed.
            val requestSeedFuture = CompletableFuture<NtkViewerImageRequestSeed?>()
            if (directWebtoon) {
                // The episode list was fetched to render the user-visible detail screen in this
                // same cold navigation. If that click payload carries the exact viewer token,
                // use it only after the committed click to overlap the image-list POST with the
                // fresh RSC document. The complete document below remains mandatory and must
                // match this request-only identity before any manifest or pixel is published.
                val clickPayloadSeed = NtkViewerImageRequestSeedParser.parseIfPresent(
                    flight.lease,
                    path,
                    manga.ntkViewerPayloadHint.toByteArray(Charsets.UTF_8),
                )
                if (clickPayloadSeed != null) {
                    requestSeedFuture.complete(clickPayloadSeed)
                    Log.d(
                        "ViewerPerf",
                        "ntk_strict_click_payload_request_seed_ready path=$path," +
                            "generation=${flight.lease.generation.value}," +
                            "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                    )
                }
            }
            val documentCookiesPublished = AtomicBoolean(false)
            var streamedRequestSeed: NtkViewerImageRequestSeed? = null
            var overlappedApiResponse: CustomHttpClient.NtkBoundHttpResponse? = null
            var directGrantResolvedByOverlap = false
            val documentResponse = if (directWebtoon) {
                val documentTask = FutureTask {
                    try {
                        val response = traceStage("NtkExactDocument") {
                            tracePageListPhysicalRequest(flight) {
                                client.fetchExactNtkEpisodeDocument(
                                    path,
                                    flight.physicalCalls,
                                    object : CustomHttpClient.NtkStrictDocumentStreamObserver {
                                        override fun initialBodyPrefixBytes(): Int =
                                            if (hostGpuEmulatorDirectWifiWebtoonControlPlane) {
                                                // The compact response is at most a few dozen KiB.
                                                // Observe each small read boundary so the exact
                                                // fresh token can start the already-authorized API
                                                // without waiting for a later cadence threshold.
                                                4 * 1024
                                            } else {
                                                112 * 1024
                                            }

                                        override fun onResponseHeaders(
                                            responseHead: CustomHttpClient.NtkBoundHttpResponse,
                                        ) {
                                            requireDiscoveryOwnership(
                                                flight,
                                                "document_response_headers",
                                            )
                                            if (documentCookiesPublished.compareAndSet(false, true)) {
                                                withBoundedDiscoveryOwnership(
                                                    flight,
                                                    "streaming_document_cookie_publication",
                                                ) {
                                                    check(
                                                        client.publishExactNtkEpisodeResponseCookies(
                                                            responseHead,
                                                            flight.physicalCalls,
                                                        )
                                                    ) {
                                                        "Streaming document cookie publication lost ownership"
                                                    }
                                                }
                                                logStage(flight, "document_headers_ready")
                                            }
                                        }

                                        override fun onBodyPrefix(
                                            bodyPrefix: ByteArray,
                                            bodyPrefixLength: Int,
                                        ): Boolean {
                                            if (requestSeedFuture.isDone) return true
                                            val seed =
                                                NtkViewerImageRequestSeedParser.parseIfPresent(
                                                    flight.lease,
                                                    path,
                                                    bodyPrefix,
                                                    bodyPrefixLength,
                                                ) ?: return false
                                            if (requestSeedFuture.complete(seed)) {
                                                Log.d(
                                                    "ViewerPerf",
                                                    "ntk_strict_document_request_seed_ready path=$path," +
                                                        "generation=${flight.lease.generation.value}," +
                                                    "bytes=$bodyPrefixLength," +
                                                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                                                )
                                            }
                                            return true
                                        }
                                    },
                                    flight.sameOriginFallbackConsumed ||
                                        hostGpuEmulatorDirectWifiWebtoonControlPlane,
                                    hostGpuEmulatorDirectWifiWebtoonControlPlane,
                                )
                            }
                        }
                        if (!requestSeedFuture.isDone) {
                            val seed = NtkViewerImageRequestSeedParser.parseIfPresent(
                                flight.lease,
                                path,
                                response.bodyBytes,
                            )
                            if (seed != null) {
                                requestSeedFuture.complete(seed)
                            } else {
                                requestSeedFuture.complete(null)
                                Log.d(
                                    "ViewerPerf",
                                    "ntk_strict_document_request_seed_absent path=$path," +
                                        "generation=${flight.lease.generation.value}," +
                                        "fallback=complete_document_request_identity," +
                                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                                )
                            }
                        }
                        response
                    } catch (failure: Throwable) {
                        // Without this hand-off a failed streaming document can leave the worker
                        // waiting forever for a request seed that can no longer be produced.
                        requestSeedFuture.completeExceptionally(failure)
                        throw failure
                    }
                }
                streamingDocumentThread = Thread(
                    documentTask,
                    "ntk-strict-streaming-document",
                ).apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
                    start()
                }

                // The exact document and trusted challenge may overlap only the verified-body
                // render tail. Do not consume their seed into an API request until the native
                // current runway has reached its existing complete-drawable gate.
                if (flight.adjacentPredecessorGate) {
                    awaitAdjacentPredecessorComplete(flight)
                }
                streamedRequestSeed = awaitFuture(requestSeedFuture)
                val directGrant = streamedRequestSeed?.let {
                    awaitDirectTrustedGrantOrStartIsolated(
                        client,
                        flight,
                        ackRoute,
                    )
                }
                directGrantResolvedByOverlap = streamedRequestSeed != null
                if (directGrant != null) {
                    requireDiscoveryOwnership(flight, "streaming_trusted_challenge_response")
                    logStage(flight, "trusted_challenge_ready")
                    withBoundedDiscoveryOwnership(flight, "streaming_trusted_grant_import") {
                        client.importVerifiedNtkAckCookieGrants(
                            directGrant.origin,
                            directGrant.episodePath,
                            directGrant.cookieGrants,
                        )
                    }
                    val unsignedRequest = traceStage("NtkStreamingExactRequestBuild") {
                        withDiscoveryOwnership(flight, "streaming_exact_webtoon_request_build") {
                            client.buildUnsignedExactNtkViewerImageApiRequest(
                                checkNotNull(streamedRequestSeed),
                                "",
                                flight.physicalCalls,
                            )
                        }
                    }
                    logStage(flight, "streaming_trusted_request_ready")
                    overlappedApiResponse = traceStage("NtkStreamingTrustedImageApi") {
                        tracePageListPhysicalRequest(flight) {
                            client.executeUnsignedExactNtkWebtoonImageApi(
                                unsignedRequest,
                                flight.physicalCalls,
                                hostGpuEmulatorDirectWifiWebtoonControlPlane,
                            )
                        }
                    }
                }
                awaitFuture(documentTask)
            } else {
                traceStage("NtkExactDocument") {
                    tracePageListPhysicalRequest(flight) {
                        client.fetchExactNtkEpisodeDocument(
                            path,
                            flight.physicalCalls,
                        )
                    }
                }
            }
            // A physical response carries no authority after its viewer generation retires.
            requireDiscoveryOwnership(flight, "document_response")
            if (documentCookiesPublished.compareAndSet(false, true)) {
                withBoundedDiscoveryOwnership(flight, "document_cookie_publication") {
                    check(
                        client.publishExactNtkEpisodeResponseCookies(
                            documentResponse,
                            flight.physicalCalls,
                        )
                    ) { "Strict document cookie publication lost flight ownership" }
                }
            }
            if (flight.adjacentPredecessorGate && !directWebtoon) {
                // The exact document GET and bounded format probes may overlap the predecessor,
                // but parsing their complete RSC/JSON response creates the discovery flight's
                // largest short-lived Java object graph. No adjacent image body may cross the
                // predecessor gate anyway, so parsing earlier has no publication benefit. Wait
                // until predecessor bodies are resident, then keep this allocation burst out of
                // an actual drag/fling. Network ownership and the already-consumed response stay
                // intact; lifecycle ownership is checked during every short motion wait.
                awaitAdjacentPredecessorComplete(flight)
                if (hostGpuEmulatorRuntime) {
                    NtkReaderTransferPacer.awaitMotionIdleUntilRequired(
                        requiredNow = flight.adjacentPhysicalBoundaryDemand::isDone,
                        stillOwned = {
                            requireDiscoveryOwnership(flight, "document_parse_motion_wait")
                        },
                    )
                }
            }
            val draft = traceStage("NtkDocumentPlanParse") {
                NtkEpisodeDocumentPlanParser.parse(
                    flight.lease,
                    path,
                    documentResponse
                )
            }
            clickOwnedAnchor?.let { anchor ->
                NtkEpisodeDocumentPlanParser.completeNumericPageCountHint(
                    flight.lease,
                    path,
                    draft,
                )?.let(anchor::releaseForCompleteDocumentPageCount)
            }
            requireDiscoveryOwnership(flight, "document_parse_complete")
            streamedRequestSeed?.let { seed ->
                check(seed.matches(draft)) {
                    "Streaming request seed does not match the complete strict document"
                }
            }
            logStage(flight, "document_plan_ready")
            clickOwnedAnchor?.let { anchor ->
                check(anchor.validateDocumentDraft(draft)) {
                    "Click payload image count differs from the fresh episode document"
                }
                anchor.releaseAfterDocumentValidation()
            }
            var plan = NtkSourceSpoolRegistry.currentQuarantineAssetEvidence(flight.lease)
                ?.takeIf { evidence ->
                    evidence.viewerRequestIdentityDigest ==
                        draft.requestIdentity.identityDigestSha256 &&
                        evidence.orderedSourcePages.isNotEmpty() &&
                        evidence.orderedSourcePages.all { it in draft.orderedPages }
                }
                ?.let(draft::bind)
            var tokenBoundBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody> = emptyMap()
            var tokenBoundManifestDigest = ""
            val tokenBoundAuthority = if (plan == null) {
                NtkManifestAuthorityFactory
                    .createTokenBoundGeneratedManhwaDocumentAuthority(flight.lease, draft)
            } else {
                null
            }
            if (tokenBoundAuthority != null) {
                // The fresh document proves the finite work/episode/page table. Replica suffixes
                // are aliases of that logical pNNN page, while every selected body still needs its
                // own image header, EOF, digest and exact adoption proof. Promote this production
                // route immediately so completed pages decode/install progressively instead of
                // holding the entire scene behind the slowest response header.
                if (clickOwnedAnchor == null) {
                    clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.start(
                        client.context,
                        manga,
                        draft,
                        clickOwnedManhwaProbe,
                        flight.directWifiAdjacentBodyGate,
                        flight.adjacentPredecessorComplete,
                        initialPageIndexHint = flight.initialPageIndexHint,
                        viewerGeneration = flight.viewerGeneration,
                        adjacentPredecessorEpisodePath = flight.adjacentPredecessorEpisodePath,
                    )
                    if (clickOwnedAnchor != null) clickOwnedManhwaProbe = null
                }
                clickOwnedAnchor?.let { anchor ->
                    anchor.releaseForTokenBoundDocumentAuthority(tokenBoundAuthority.manifest)
                    val tokenBoundStream = anchor.streamIfExact(tokenBoundAuthority.manifest)
                    if (tokenBoundStream != null) {
                        // Some valid numeric documents expose only a finite page count. Their
                        // generated pNNN names are virtual placeholders and every extension/replica
                        // returns 404; the signed image API below is the only physical authority.
                        // Do not publish generated authority until the already-running click probe
                        // and its exact page-zero body have proved real bytes. This waits for no
                        // extra request and does not delay a drawable: page zero could not render
                        // before the same body completion.
                        val forwardFirstPage = anchor.forwardFirstPage()
                        val residentExactAnchorBody = if (
                            tokenBoundStream.residentAnchorProofMayPrecedeSampledCandidate
                        ) {
                            runCatching {
                                awaitFuture(checkNotNull(
                                    tokenBoundStream.bodyFutures[forwardFirstPage]
                                ) {
                                    "Token-bound numeric stream omitted forward anchor"
                                })
                            }.getOrNull()
                        } else {
                            null
                        }
                        // A successful inherited resident GET has response identity, image
                        // metadata, EOF and digest bound to this fresh token-document manifest.
                        // Waiting for its older metadata-only HEAD after that stronger proof adds
                        // no identity evidence and can consume the entire short-tail runway.
                        // Every other route retains the established HEAD-first fallback check.
                        val sampledCandidate = if (residentExactAnchorBody == null) {
                            tokenBoundStream.sampledAnchorCandidate?.let {
                                runCatching { awaitFuture(it) }
                            }
                        } else {
                            null
                        }
                        val sampledRouteMissing =
                            sampledCandidate?.isSuccess == true &&
                                sampledCandidate.getOrNull() == null
                        val exactAnchorBody = if (residentExactAnchorBody != null) {
                            residentExactAnchorBody
                        } else if (sampledRouteMissing) {
                            null
                        } else {
                            runCatching {
                                awaitFuture(checkNotNull(
                                    tokenBoundStream.bodyFutures[forwardFirstPage]
                                ) {
                                    "Token-bound numeric stream omitted forward anchor"
                                })
                            }.getOrNull()
                        }
                        requireDiscoveryOwnership(
                            flight,
                            "token_bound_numeric_anchor_validation",
                        )
                        if (exactAnchorBody == null) {
                            tokenBoundStream.close()
                            clickOwnedAnchor = null
                            Log.d(
                                "ViewerPerf",
                                "ntk_token_bound_generated_route_rejected path=$path," +
                                    "sampledRouteMissing=$sampledRouteMissing," +
                                    "fallback=signed_image_api",
                            )
                        } else {
                            val planResult = withDiscoveryOwnership(
                                flight,
                                "token_bound_numeric_plan_reserve",
                            ) {
                                NtkSourceSpoolRegistry.reserveTokenBoundGeneratedDocumentPlan(
                                    client.context,
                                    manga,
                                    flight.lease,
                                    tokenBoundAuthority.plan,
                                    tokenBoundAuthority.manifest,
                                    streamedExactBodies = tokenBoundStream,
                                )
                            }
                            if (!planResult.accepted) tokenBoundStream.close()
                            check(planResult.accepted) {
                                "Token-bound numeric plan rejected: ${planResult.status}"
                            }
                            val authority = withDiscoveryOwnership(
                                flight,
                                "token_bound_numeric_manifest_install",
                            ) {
                                val install = NtkManifestAuthorityFactory
                                    .installTokenBoundGeneratedManhwaDocumentAuthority(
                                        client.context,
                                        manga,
                                        flight.lease,
                                        tokenBoundAuthority,
                                    )
                                check(install.accepted) {
                                    "Token-bound numeric manifest rejected: ${install.status}"
                                }
                                checkNotNull(install.authoritativeManifest) {
                                    "Token-bound numeric install omitted exact authority"
                                }.also { exactInstalled = true }
                            }
                            clickOwnedAnchor = null
                            ackRoute.cancel()
                            completeOwnedFlight(
                                manga,
                                path,
                                flight,
                                authority,
                                tokenBoundAuthority.plan,
                                "token_bound_numeric_document",
                            )
                            return
                        }
                    }
                }
            }
            if (plan == null && tokenBoundAuthority == null) {
                // Numeric manhwa paths deterministically name immutable replica assets. Start the
                // complete bounded forward wave only after this viewer click's document has proved
                // the episode and page count. Bodies stay private and undecoded until the fresh
                // signed API table validates every page identity below.
                if (clickOwnedAnchor == null) {
                    clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.start(
                        client.context,
                        manga,
                        draft,
                        clickOwnedManhwaProbe,
                        flight.directWifiAdjacentBodyGate,
                        flight.adjacentPredecessorComplete,
                        initialPageIndexHint = flight.initialPageIndexHint,
                        viewerGeneration = flight.viewerGeneration,
                        adjacentPredecessorEpisodePath = flight.adjacentPredecessorEpisodePath,
                    )
                    if (clickOwnedAnchor != null) clickOwnedManhwaProbe = null
                    clickOwnedAnchor?.let { anchor ->
                        ensureIsolatedAck(client, flight, ackRoute).whenNetworkPrerequisitesReady {
                            anchor.releaseAfterAckNetworkPrerequisites()
                        }
                    }
                }
            }

            // Numeric manhwa does not need ACK when the complete click-owned document and every
            // physical replica response prove the finite mixed-extension table. Join only that
            // observed authority on the normal path; an isolated browser is created below solely
            // if observation genuinely fails and the signed-API compatibility path is required.
            // Observed numeric authority requires every physical body. An offscreen adjacent owns
            // only four runway bodies until boundary activation, so joining that proof here would
            // deadlock before a strict stream exists to receive the activation signal. Use the
            // normal signed-image authority for that bounded adjacent flight instead.
            val observedAuthorityFuture = if (
                plan == null && !flight.directWifiAdjacentBodyGate &&
                (clickOwnedAnchor?.forwardFirstPage() ?: 0) == 0
            ) {
                clickOwnedAnchor?.observedDocumentAuthorityFuture(flight.lease, draft)
            } else {
                null
            }
            if (observedAuthorityFuture != null) {
                val value = runCatching { observedAuthorityFuture.join() }
                    .onFailure { failure ->
                        Log.d(
                            "ViewerPerf",
                            "ntk_observed_numeric_authority_failed path=$path," +
                                "error=${failure.javaClass.simpleName}",
                        )
                    }
                    .getOrNull()
                if (value != null) {
                    requireDiscoveryOwnership(flight, "observed_numeric_authority")
                    val observedStream = checkNotNull(
                        clickOwnedAnchor?.streamIfExact(value.manifest)
                    ) { "Observed numeric authority could not bind click-owned body stream" }
                    val planResult = withDiscoveryOwnership(
                        flight,
                        "observed_numeric_plan_reserve",
                    ) {
                        NtkSourceSpoolRegistry.reserveObservedNumericReplicaDocumentPlan(
                            client.context,
                            manga,
                            flight.lease,
                            value.plan,
                            value.manifest,
                            observedStream,
                        )
                    }
                    if (!planResult.accepted) observedStream.close()
                    check(planResult.accepted) {
                        "Observed numeric plan rejected: ${planResult.status}"
                    }
                    val authority = withDiscoveryOwnership(
                        flight,
                        "observed_numeric_manifest_install",
                    ) {
                        val install =
                            NtkManifestAuthorityFactory
                                .installObservedNumericReplicaDocumentAuthority(
                                    client.context,
                                    manga,
                                    flight.lease,
                                    value,
                                )
                        check(install.accepted) {
                            "Observed numeric manifest rejected: ${install.status}"
                        }
                        checkNotNull(install.authoritativeManifest) {
                            "Observed numeric install omitted exact authority"
                        }.also { exactInstalled = true }
                    }
                    clickOwnedAnchor = null
                    ackRoute.cancel()
                    completeOwnedFlight(
                        manga,
                        path,
                        flight,
                        authority,
                        value.plan,
                        "observed_numeric_replica",
                    )
                    return
                }
            }
            var planReserved = false
            if (plan != null) {
                val planResult = withDiscoveryOwnership(flight, "document_plan_reserve") {
                    NtkSourceSpoolRegistry.reserveDocumentPlan(
                        client.context,
                        manga,
                        flight.lease,
                        plan,
                    )
                }
                check(planResult.accepted) {
                    "Document plan rejected: ${planResult.status}"
                }
                planReserved = true
            }

            val directGrant = if (directGrantResolvedByOverlap) {
                null
            } else {
                awaitDirectTrustedGrantOrStartIsolated(client, flight, ackRoute)
            }
            val apiResponse = overlappedApiResponse ?: if (directGrant != null) {
                requireDiscoveryOwnership(flight, "trusted_challenge_response")
                logStage(flight, "trusted_challenge_ready")
                withBoundedDiscoveryOwnership(flight, "trusted_grant_import") {
                    client.importVerifiedNtkAckCookieGrants(
                        directGrant.origin,
                        directGrant.episodePath,
                        directGrant.cookieGrants,
                    )
                }
                val unsignedRequest = traceStage("NtkExactRequestBuild") {
                    withDiscoveryOwnership(flight, "exact_webtoon_request_build") {
                        client.buildUnsignedExactNtkViewerImageApiRequest(
                            draft,
                            "",
                            flight.physicalCalls,
                        )
                    }
                }
                logStage(flight, "trusted_request_ready")
                traceStage("NtkTrustedImageApi") {
                    tracePageListPhysicalRequest(flight) {
                        client.executeUnsignedExactNtkWebtoonImageApi(
                            unsignedRequest,
                            flight.physicalCalls,
                            hostGpuEmulatorDirectWifiWebtoonControlPlane,
                        )
                    }
                }
            } else {
                val ackHandle = ensureIsolatedAck(client, flight, ackRoute)
                // nv and browser ACK are independent post-click prerequisites. Join both only
                // immediately before serializing the exact image request so neither adds a
                // separate cold round trip to the critical path.
                val nvSeedTask = ensureExactNvSeed(client, flight, ackRoute)
                val ackProof = traceStage("NtkAckProofWait") { ackHandle.joinProof() }
                requireDiscoveryOwnership(flight, "ack_proof_response")
                logStage(flight, "ack_proof_ready")
                withBoundedDiscoveryOwnership(flight, "ack_grant_import") {
                    client.importVerifiedNtkAckCookieGrants(
                        ackProof.origin,
                        ackProof.episodePath,
                        ackProof.cookieGrants,
                    )
                }
                traceStage("NtkExactNvSeedWait") { awaitFuture(nvSeedTask) }
                requireDiscoveryOwnership(flight, "nv_seed_response")
                logStage(flight, "nv_seed_ready")
                val unsignedRequest = traceStage("NtkExactRequestBuild") {
                    withDiscoveryOwnership(flight, "exact_request_build") {
                        client.buildUnsignedExactNtkViewerImageApiRequest(
                            draft,
                            ackProof.requestKeyId,
                            flight.physicalCalls,
                        )
                    }
                }
                requireDiscoveryOwnership(flight, "ack_quiesce")
                traceStage("NtkAckQuiesce") { ackHandle.quiesce() }
                requireDiscoveryOwnership(flight, "ack_quiesce_response")
                traceStage("NtkSignedImageApi") {
                    tracePageListPhysicalRequest(flight) {
                        requireDiscoveryOwnership(flight, "exact_request_execute")
                        val exchange = ackHandle.executeExact(
                            unsignedRequest.endpoint,
                            unsignedRequest.requestIdentityDigestSha256,
                            unsignedRequest.imagesTokenDigestSha256,
                            unsignedRequest.request.bodyBytes,
                            unsignedRequest.request.headers,
                        )
                        requireDiscoveryOwnership(flight, "isolated_exact_response")
                        client.bindIsolatedExactNtkViewerImageApiResponse(
                            unsignedRequest,
                            exchange,
                            flight.physicalCalls,
                        )
                    }
                }
            }
            // This check is intentionally the first operation after the physical API response.
            requireDiscoveryOwnership(flight, "signed_api_response")
            val envelope = NtkViewerImageApiAuthorityParser.parse(
                draft,
                apiResponse.request,
                apiResponse
            )
            requireDiscoveryOwnership(flight, "signed_api_parse_complete")
            // Preserve the exact page-slot replicas before route preparation begins. This is a
            // transport-only proof cache: the canonical manifest/digest is unchanged, and only a
            // direct-Wi-Fi adjacent runway may consume the alternatives after its predecessor
            // completion gate has released.
            withDiscoveryOwnership(flight, "signed_api_replica_transport_proof") {
                ReaderImageCache.rememberExactNtkApiReplicaCandidates(
                    path,
                    envelope.orderedAssetsDigestSha256,
                    envelope.orderedAssets,
                    envelope.orderedReplicaCandidates,
                )
            }
            val boundPlan: NtkProvisionalEpisodePlan
            val authority: NtkAuthoritativeManifest
            if (plan == null) {
                // A numeric manhwa document proves the page count and signing identity, not the
                // physical image URLs. Its generated /manhwa/.../pNNN.jpg names can be virtual
                // placeholders (and return 404) while the signed API supplies the actual CDN
                // assets. Never start or promote body work until that authoritative table exists.
                val exactEvidence = NtkQuarantineAssetEvidence.createWithSourcePages(
                    path,
                    flight.lease.generation.value,
                    draft.requestIdentity.identityDigestSha256,
                    envelope.orderedAssets,
                    envelope.orderedSourcePages,
                    apiResponse.bodyBytes
                )
                withDiscoveryOwnership(flight, "quarantine_evidence_observe") {
                    check(
                        NtkSourceSpoolRegistry.observeQuarantineAssetEvidence(
                            flight.lease,
                            exactEvidence
                        )
                    ) { "Exact API evidence could not bind quarantine plan" }
                }
                plan = draft.bind(exactEvidence)
            }
            boundPlan = checkNotNull(plan)
            val exactManifestPreview = checkNotNull(
                NtkManifestAuthorityFactory.createViewerImageApiManifest(
                    flight.lease,
                    boundPlan,
                    envelope,
                )
            ) { "Exact API envelope could not build immutable manifest" }
            val clickOwnedExactStream = clickOwnedAnchor?.streamIfExact(exactManifestPreview)
            val clickOwnedExactBodies = when {
                clickOwnedExactStream != null -> emptyMap()
                tokenBoundBodies.isNotEmpty() &&
                    tokenBoundManifestDigest == exactManifestPreview.seal.digestSha256 ->
                    tokenBoundBodies
                else -> emptyMap()
            }
            if (!planReserved) {
                val planResult = withDiscoveryOwnership(flight, "exact_plan_reserve") {
                    NtkSourceSpoolRegistry.reserveDocumentPlan(
                        client.context,
                        manga,
                        flight.lease,
                        boundPlan,
                        clickOwnedExactBodies,
                        clickOwnedExactStream,
                    )
                }
                if (!planResult.accepted) clickOwnedExactStream?.close()
                check(planResult.accepted) {
                    "Exact-backed document plan rejected: ${planResult.status}"
                }
                planReserved = true
                // The source session accepted the stream and now owns its Closeable lifecycle.
                // Until this exact handoff succeeds, keep the quarantine owner reachable from the
                // flight finally block so retirement while waiting on the predecessor cannot leak
                // its pending futures, files or physical workers.
                clickOwnedAnchor = null
            }
            authority = withDiscoveryOwnership(flight, "exact_manifest_install") {
                val exactResult = NtkManifestAuthorityFactory.installViewerImageApiEnvelope(
                    client.context,
                    manga,
                    flight.lease,
                    boundPlan,
                    envelope,
                    exactManifestPreview,
                )
                check(exactResult.accepted) {
                    "Exact manifest promotion rejected: ${exactResult.status}"
                }
                checkNotNull(exactResult.authoritativeManifest) {
                    "Accepted exact manifest omitted authority"
                }.also {
                    exactInstalled = true
                }
            }
            completeOwnedFlight(
                manga,
                path,
                flight,
                authority,
                boundPlan,
                "viewer_image_api",
            )
        } catch (failure: Throwable) {
            val replacementEligible = !exactInstalled &&
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) == null &&
                isViewerOwnerActive(flight)
            restartSameOriginWithoutResolver = replacementEligible &&
                NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                    failure,
                    flight.completedRouteRecoveryAttempts,
                    flight.directWifiCurrentViewer,
                    sameOriginFallbackConsumed = flight.sameOriginFallbackConsumed,
                    directWifiAdjacentViewer = flight.directWifiAdjacentBodyGate,
                    hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
                        Build.FINGERPRINT,
                        Build.MODEL,
                        Build.HARDWARE,
                        Build.PRODUCT,
                    ),
                )
            val resolveAfterSameOriginFallback = replacementEligible &&
                NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                    failure,
                    flight.completedRouteRecoveryAttempts,
                    flight.directWifiCurrentViewer,
                    sameOriginFallbackConsumed = flight.sameOriginFallbackConsumed,
                    directWifiAdjacentViewer = flight.directWifiAdjacentBodyGate,
                    hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
                        Build.FINGERPRINT,
                        Build.MODEL,
                        Build.HARDWARE,
                        Build.PRODUCT,
                    ),
                )
            routeRecoveryRequested = replacementEligible &&
                (restartSameOriginWithoutResolver ||
                    resolveAfterSameOriginFallback ||
                    NtkStrictRouteRecoveryPolicy.shouldRecover(
                        failure,
                        flight.completedRouteRecoveryAttempts,
                    ))
            if (routeRecoveryRequested) {
                // Keep the old lease/flight as a path reservation until domain recovery finishes.
                // This prevents UI watchdogs from starting a competing flight in the gap.
                ackRoute.cancel()
                flight.physicalCalls.cancelAll()
                Log.w(
                    "ViewerPerf",
                    "ntk_strict_route_recovery_scheduled path=$path," +
                        "generation=${flight.lease.generation.value}," +
                        "attempt=${flight.completedRouteRecoveryAttempts + 1}," +
                        "failure=${failure.javaClass.simpleName}," +
                        "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                )
            } else if (!exactInstalled &&
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) == null
            ) {
                ackRoute.cancel()
                NtkSourceSpoolRegistry.failDiscovery(
                    flight.lease,
                    "strict_exact_discovery_${failure.javaClass.simpleName}"
                )
            } else if (exactInstalled && !flight.completed.get()) {
                // Promotion succeeded but final viewer ownership did not. Never leave the claimed
                // source reachable without a lifecycle retirement handle.
                val detached = synchronized(flightLifecycleLock(path)) {
                    if (flights[path] === flight) {
                        claimNetworkOwnershipRetirement(flight)
                        flight.retirement.retire(path, flight.viewerGeneration)
                        NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                            flight.lease,
                            "strict_exact_post_install_${failure.javaClass.simpleName}"
                        )
                        detachFlightForForegroundLeaveLocked(flight)
                    } else {
                        false
                    }
                }
                if (detached) completeDetachedFlightForegroundLeave(flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_discovery_failed path=$path," +
                    "generation=${flight.lease.generation.value}," +
                    "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                failure
            )
        } finally {
            streamingDocumentThread?.takeIf(Thread::isAlive)?.interrupt()
            clickOwnedManhwaProbe?.close()
            clickOwnedAnchor?.close()
            flight.retirement.detachWorker(Thread.currentThread())
            if (routeRecoveryRequested) {
                // The resolver is intentionally demand-driven and may run only after the failed
                // strict owner releases its network gate. The path slot stays reserved above.
                claimNetworkOwnershipRetirement(flight)
                leaveForegroundNetworkIfEntered(flight)
                synchronized(flight) {
                    flight.routeRecoverySlotHeldAtMs.set(SystemClock.elapsedRealtime())
                    flight.validatedAdjacentPhase.set(
                        NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD,
                    )
                }
            } else if (!flight.completed.get() || flight.retirement.isRetired()) {
                val detached = synchronized(flightLifecycleLock(path)) {
                    claimNetworkOwnershipRetirement(flight)
                    detachFlightForForegroundLeaveLocked(flight)
                }
                if (detached) {
                    completeDetachedFlightForegroundLeave(flight)
                } else {
                    leaveForegroundNetworkIfEntered(flight)
                }
            }
        }
        if (routeRecoveryRequested) {
            recoverStrictRouteAndRestart(
                client,
                manga,
                path,
                flight,
                skipDomainResolution = restartSameOriginWithoutResolver,
            )
        }
    }

    private fun recoverStrictRouteAndRestart(
        client: CustomHttpClient,
        manga: Manga,
        path: String,
        failedFlight: Flight,
        skipDomainResolution: Boolean,
    ) {
        val originBefore = client.getUrl(path)
        val changed = if (skipDomainResolution) {
            Log.d(
                "ViewerPerf",
                "ntk_strict_same_origin_h2_failover path=$path," +
                    "viewerGeneration=${failedFlight.viewerGeneration}," +
                    "attempt=${failedFlight.completedRouteRecoveryAttempts + 1}," +
                    "origin=$originBefore",
            )
            false
        } else {
            client.resolveNtkDomainAfterRouteFailure()
        }
        val originAfter = client.getUrl(path)
        val stillOwned = isViewerOwnerActive(failedFlight)
        val nextRouteRecoveryAttempts = failedFlight.completedRouteRecoveryAttempts +
            if (skipDomainResolution) 0 else 1
        val releasedForReplacement = synchronized(flightLifecycleLock(path)) {
            if (flights[path] === failedFlight) {
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    failedFlight.lease,
                    if (skipDomainResolution) {
                        "strict_same_origin_transport_fallback"
                    } else {
                        "strict_route_recovery_$nextRouteRecoveryAttempts"
                    },
                )
                detachFlightForForegroundLeaveLocked(failedFlight)
            } else {
                false
            }
        }
        if (releasedForReplacement) {
            completeDetachedFlightForegroundLeave(failedFlight)
        }
        if (!stillOwned || !releasedForReplacement) {
            Log.d(
                "ViewerPerf",
                "ntk_strict_route_recovery_abandoned path=$path," +
                    "viewerGeneration=${failedFlight.viewerGeneration}," +
                    "stillOwned=$stillOwned,released=$releasedForReplacement",
            )
            return
        }
        val restarted = startInternal(
            client,
            manga,
            failedFlight.rollingAdmission,
            failedFlight.initialPageIndexHint,
            nextRouteRecoveryAttempts,
            failedFlight.viewerOwnerEpisodePath,
            failedFlight.adjacentPredecessorEpisodePath,
            sameOriginFallbackConsumed =
                failedFlight.sameOriginFallbackConsumed || skipDomainResolution,
            // Every recovery remains fenced to the viewer which owned the failed flight. This is
            // also required for an adjacent target: between retiring the old target flight and
            // admitting its replacement, a new viewer can open the same launch path.
            expectedCurrentViewerGeneration = failedFlight.viewerGeneration,
            expectedCurrentOwnerEpisodePath = failedFlight.viewerOwnerEpisodePath,
        )
        val joined = restarted ||
            isInFlight(path) ||
            NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null
        Log.d(
            "ViewerPerf",
            "ntk_strict_route_recovery_result path=$path," +
                "viewerGeneration=${failedFlight.viewerGeneration}," +
                "routeAttempt=$nextRouteRecoveryAttempts," +
                "sameOriginFallback=$skipDomainResolution," +
                "changed=$changed,restarted=$restarted,joined=$joined," +
                "originBefore=$originBefore,originAfter=$originAfter",
        )
    }

    private fun requireDiscoveryOwnership(flight: Flight, boundary: String) {
        withDiscoveryOwnership(flight, boundary) { Unit }
    }

    /**
     * Serializes every parse/publication boundary with explicit retirement. ViewerTelemetry is
     * checked inside the fence so a generation switch cannot race an authority installation.
     */
    private fun <T> withDiscoveryOwnership(
        flight: Flight,
        boundary: String,
        action: () -> T
    ): T {
        // Adjacent control work has no visible result until its predecessor is crossed. Registry
        // reservation and authority installation create the stage's largest object graphs and
        // hold process-wide source locks; entering one during a physical gesture lets a
        // background worker be descheduled while owning those locks and stalls the display/JIT
        // producer for multiple frames. Wait before taking retirement/source ownership, then run
        // the unchanged atomic boundary in the next real input-idle gap. Current-episode work and
        // all network I/O retain their original admission and concurrency.
        if (flight.adjacentPredecessorGate &&
            isAdjacentDisplayDeferredOwnershipBoundary(boundary)
        ) {
            NtkReaderTransferPacer.awaitMotionIdleUntilRequired(
                requiredNow = flight.adjacentPhysicalBoundaryDemand::isDone,
                stillOwned = {
                    requireFlightIdentity(flight, "${boundary}_motion_wait")
                },
            )
        }
        return flight.retirement.withActiveOwnership(
            flight.episodePath,
            flight.viewerGeneration,
            boundary
        ) {
            requireFlightIdentity(flight, boundary)
            action()
        }
    }

    private fun isAdjacentDisplayDeferredOwnershipBoundary(boundary: String): Boolean =
        boundary.contains("plan_reserve") ||
            boundary.contains("manifest_install") ||
            boundary == "quarantine_evidence_observe" ||
            boundary == "signed_api_replica_transport_proof"

    /** Only local cookie-map commits may use this state-lock-linearized boundary. */
    private fun <T> withBoundedDiscoveryOwnership(
        flight: Flight,
        boundary: String,
        action: () -> T,
    ): T = flight.retirement.withBoundedActiveOwnership(
        flight.episodePath,
        flight.viewerGeneration,
        boundary,
    ) {
        requireFlightIdentity(flight, boundary)
        action()
    }

    private fun requireFlightIdentity(flight: Flight, boundary: String) {
        if (flights[flight.episodePath] !== flight ||
            !isViewerOwnerActive(flight)
        ) {
            throw InterruptedIOException(
                "Viewer ownership retired before $boundary path=${flight.episodePath}," +
                    "viewerGeneration=${flight.viewerGeneration}"
            )
        }
    }

    private fun isViewerOwnerActive(flight: Flight): Boolean =
        ViewerTelemetry.isActiveViewer(
            flight.viewerGeneration,
            flight.viewerOwnerEpisodePath,
        )

    private fun <T> withOwnedAuthority(
        flight: Flight,
        authority: NtkAuthoritativeManifest,
        boundary: String,
        action: () -> T
    ): T = withDiscoveryOwnership(flight, boundary) {
        check(authority.seal.normalizedEpisodePath.equals(
            flight.episodePath,
            ignoreCase = true
        )) { "Owned authority episode mismatch" }
        action()
    }

    private fun completeOwnedFlight(
        manga: Manga,
        path: String,
        flight: Flight,
        authority: NtkAuthoritativeManifest,
        plan: NtkProvisionalEpisodePlan,
        source: String,
    ) {
        runCatching {
            withOwnedAuthority(flight, authority, "compatibility_mirror") {
                mirrorOwnedAuthority(manga, path, authority)
            }
        }.onFailure {
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_compatibility_mirror_failed path=$path",
                it,
            )
        }
        requireDiscoveryOwnership(flight, "discovery_complete")
        // Keep the flight as the completed source-lifetime owner only after the final viewer
        // identity check. Activity teardown or adjacent navigation then detaches its generation.
        flight.completed.set(true)
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_discovery_owned path=$path," +
                "generation=${flight.lease.generation.value}," +
                "source=$source," +
                "pages=${authority.seal.pageCount}," +
                "planProof=${plan.proof.proofDigestSha256}," +
                "manifestDigest=${authority.seal.digestSha256}," +
                "proofDigest=${authority.proof.proofDigestSha256}," +
                "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
        )
    }

    private inline fun <T> tracePageListPhysicalRequest(flight: Flight, request: () -> T): T {
        requireDiscoveryOwnership(flight, "page_list_request")
        val operationId = ViewerTelemetry.pageListRequestStarted()
        return try {
            val result = request()
            // A request admitted by the old generation may finish after Activity teardown. Its
            // bytes must be discarded before parsing, reservation or manifest publication.
            requireDiscoveryOwnership(flight, "page_list_response")
            ViewerTelemetry.pageListRequestFinished(operationId, "success")
            result
        } catch (failure: Throwable) {
            ViewerTelemetry.pageListRequestFinished(operationId, pageListFailureOutcome(failure))
            throw failure
        }
    }

    private inline fun <T> traceStage(name: String, action: () -> T): T {
        PerfTrace.begin(name)
        return try {
            action()
        } finally {
            PerfTrace.end()
        }
    }

    private fun <T> awaitFuture(future: Future<T>): T = try {
        future.get()
    } catch (wrapped: ExecutionException) {
        throw (wrapped.cause ?: wrapped)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Strict discovery future was interrupted").also {
            it.initCause(interrupted)
        }
    }

    private fun awaitAdjacentPredecessorComplete(flight: Flight) {
        if (!flight.adjacentPredecessorGate) return
        awaitAdjacentGateWithOwnership(
            flight,
            flight.adjacentPredecessorComplete,
            "adjacent_predecessor_wait",
        )
        requireDiscoveryOwnership(flight, "adjacent_predecessor_complete")
        Log.d(
            "ViewerPerf",
            "ntk_adjacent_metadata_ready_wait_body_released " +
                "predecessor=${flight.adjacentPredecessorEpisodePath}," +
                "target=${flight.episodePath}," +
                "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
        )
    }

    private fun awaitAdjacentControlReady(flight: Flight) {
        if (!flight.adjacentPredecessorGate) return
        awaitAdjacentGateWithOwnership(
            flight,
            flight.adjacentControlReady,
            "adjacent_control_wait",
        )
        requireDiscoveryOwnership(flight, "adjacent_control_ready")
        Log.d(
            "ViewerPerf",
            "ntk_adjacent_control_ready " +
                "predecessor=${flight.adjacentPredecessorEpisodePath}," +
                "target=${flight.episodePath}," +
            "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
        )
    }

    private fun <T> awaitAdjacentGateWithOwnership(
        flight: Flight,
        future: Future<T>,
        boundary: String,
    ): T {
        while (true) {
            try {
                return future.get(ADJACENT_GATE_OWNERSHIP_SLICE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A reader can legitimately spend minutes before reaching its prepared neighbor.
                // Keep the gate open-ended, but never let a retired/stale worker remain parked.
                requireDiscoveryOwnership(flight, boundary)
            } catch (wrapped: ExecutionException) {
                throw (wrapped.cause ?: wrapped)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Strict adjacent gate was interrupted").also {
                    it.initCause(interrupted)
                }
            }
        }
    }

    private fun logStage(flight: Flight, stage: String) {
        Log.d(
            "ViewerPerf",
            "ntk_strict_cold_stage path=${flight.episodePath}," +
                "generation=${flight.lease.generation.value},stage=$stage," +
                "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}"
        )
    }

    private fun pageListFailureOutcome(failure: Throwable): String {
        var current: Throwable? = failure
        repeat(6) {
            when (current) {
                is InterruptedException,
                is InterruptedIOException,
                is CancellationException -> return "cancelled_${failure.javaClass.simpleName}"
            }
            if (current?.message?.equals("canceled", ignoreCase = true) == true) {
                return "cancelled_${failure.javaClass.simpleName}"
            }
            current = current?.cause
        }
        return "failed_${failure.javaClass.simpleName.ifBlank { "unknown" }}"
    }

    private fun safeReason(reason: String?): String = reason.orEmpty()
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "unspecified" }

    /**
     * Compatibility consumers receive exact URLs only after the registry already owns the source.
     * They are mirrors, never inputs to the strict source or controller authority.
     */
    private fun mirrorOwnedAuthority(
        manga: Manga,
        path: String,
        authority: NtkAuthoritativeManifest
    ) {
        val current = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)
        val snapshot = NtkSourceSpoolRegistry.currentSnapshot(path)
        val sourceOwner = NtkStrictSourceOwnershipRegistry.owner(path)
        val registryOwnsAuthority = current?.seal?.hasSameAuthority(authority.seal) == true &&
            snapshot != null &&
            snapshot.manifestDigest == authority.seal.digestSha256 &&
            snapshot.state.ordinal >= NtkSourceState.OWNED_PRECLAIM.ordinal &&
            snapshot.state.ordinal < NtkSourceState.TERMINAL_CLOSING.ordinal
        val uiOwnsAuthority = sourceOwner?.state == NtkStrictSourceOwnershipRegistry.State.OWNED &&
            sourceOwner.manifestDigest == authority.seal.digestSha256 &&
            sourceOwner.discoveryGeneration == authority.proof.discoveryGeneration
        check(registryOwnsAuthority || uiOwnsAuthority) {
            "Compatibility publication preceded exact source ownership"
        }

        val exactUrls = ArrayList(authority.seal.normalizedCanonicalAssets)
        manga.setNtkImageCount(exactUrls.size)
        manga.setImgs(exactUrls)
        ReaderImageCache.rememberEarlyNtkImageUrls(
            path,
            exactUrls,
            strictExactOwner = true
        )
    }

    /**
     * HTTP path segments are case-sensitive. Keep the exact catalog/click spelling for slug
     * works while matching only the fixed route prefix without case sensitivity.
     *
     * Lowercasing the whole value made a real click such as
     * `/webtoon/u-bt-I_killed-863ce912/...` differ from ViewerTelemetry's click authority. The
     * coordinator then treated the already-committed click as a pre-click call and suppressed the
     * only discovery flight, leaving the reader permanently empty.
     */
    internal fun normalizedPath(path: String?): String? {
        val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty())
        return normalized.takeIf {
            it.matches(
                Regex(
                    """^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$""",
                    RegexOption.IGNORE_CASE,
                ),
            )
        }
    }

    internal fun ntkAdjacentOwnerAllowsTarget(ownerPath: String?, targetPath: String?): Boolean {
        val owner = normalizedPath(ownerPath) ?: return false
        val target = normalizedPath(targetPath) ?: return false
        if (owner.equals(target, ignoreCase = true)) return false
        val ownerSegments = owner.trim('/').split('/')
        val targetSegments = target.trim('/').split('/')
        return ownerSegments.size == 3 &&
            targetSegments.size == 3 &&
            ownerSegments[0].equals(targetSegments[0], ignoreCase = true) &&
            ownerSegments[1].equals(targetSegments[1], ignoreCase = true)
    }

    private fun isDirectTrustedWebtoon(path: String): Boolean =
        path.startsWith("/webtoon/", ignoreCase = true)

    internal fun requiresClickOwnedIsolatedAck(path: String?): Boolean {
        val normalized = normalizedPath(path) ?: return false
        val segments = normalized.trim('/').split('/')
        return segments.size == 3 &&
            segments[0].equals("manhwa", ignoreCase = true) &&
            (segments[1].any { !it.isDigit() } || segments[2].any { !it.isDigit() })
    }

    private fun flightLifecycleLock(path: String): Any =
        flightLifecycleLocks.computeIfAbsent(path) { Any() }
}
