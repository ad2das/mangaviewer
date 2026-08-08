package ml.melun.mangaview.reader

import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean

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
    ) {
        val retirement = NtkStrictDiscoveryRetirementFence(
            episodePath,
            viewerGeneration,
            lease.generation.value,
        )
        val physicalCalls = CustomHttpClient.NtkStrictCallRegistry(
            episodePath,
            viewerGeneration,
        )
        /** Exact authority is retained here until its viewer explicitly retires ownership. */
        val completed = AtomicBoolean(false)
        val foregroundNetworkEntered = AtomicBoolean(false)
        /**
         * Linearizes the last possible adjacent foreground-network enter with viewer retirement.
         * Retirement claims this flag under the same Flight monitor used by body-gate release,
         * so a stale flights.values snapshot can never re-enter after leave has already run.
         */
        val networkOwnershipRetiring = AtomicBoolean(false)
        /**
         * The host-emulator direct-Wi-Fi resume path may overlap only the target document and its
         * trusted challenge after every required predecessor image body has reached verified EOF.
         * API, image bodies, source promotion and decode remain closed by
         * [adjacentPredecessorComplete]. Every other adjacent profile opens both events together.
         */
        val adjacentControlReady = CompletableFuture<Unit>().also { release ->
            if (!adjacentPredecessorGate) {
                release.complete(Unit)
            }
        }

        /** Every required predecessor drawable has been installed in the native runway. */
        val adjacentPredecessorComplete = CompletableFuture<Unit>().also { release ->
            if (!adjacentPredecessorGate) {
                release.complete(Unit)
            }
        }

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
    private data class AdjacentGateKey(
        val predecessorPath: String,
        val targetPath: String,
    )

    private val bodyResidentAdjacentTargets = ConcurrentHashMap<AdjacentGateKey, Long>()
    private val completedAdjacentTargets = ConcurrentHashMap<AdjacentGateKey, Long>()
    private val completedAdjacentPredecessors = ConcurrentHashMap<String, Long>()

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
    ): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = true,
            initialPageIndexHint = 0,
            completedRouteRecoveryAttempts = 0,
            viewerOwnerEpisodePath = viewerOwnerEpisodePath,
            adjacentPredecessorEpisodePath = adjacentPredecessorEpisodePath,
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
    ): Boolean {
        if (client == null || manga == null) return false
        val path = normalizedPath(manga.ntkEpisodePath) ?: return false
        val ownerPath = normalizedPath(viewerOwnerEpisodePath) ?: path
        val adjacentOwned = ownerPath != path &&
            ntkAdjacentOwnerAllowsTarget(ownerPath, path)
        val predecessorPath = normalizedPath(adjacentPredecessorEpisodePath) ?: ownerPath
        val transportState = runCatching {
            client.isNtkWifiTransportActive to
                client.isNtkCellularResilientTransportActive
        }.getOrDefault(false to true)
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
        if (!ViewerTelemetry.hasActiveSession() ||
            !ViewerTelemetry.isActiveEpisode(ownerPath) ||
            (ownerPath != path && !adjacentOwned)
        ) {
            Log.d("ViewerPerf", "ntk_strict_exact_discovery_preclick_suppressed path=$path")
            return false
        }
        val viewerGeneration = ViewerTelemetry.activeGeneration()
        if (viewerGeneration <= 0L) return false
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
                if (NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null ||
                    flights[path] != null
                ) return false
                val lease = if (rollingAdmission) {
                    NtkSourceSpoolRegistry.beginColdRollingDiscovery(
                        client.context,
                        manga,
                        effectiveInitialPageIndexHint,
                        viewerGeneration,
                    )
                } else {
                    NtkSourceSpoolRegistry.beginDiscovery(client.context, manga)
                } ?: return false
                Flight(
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
                ).also {
                    flights[path] = it
                }
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
            val route = AckRoute(client.prepareNtkStrictAckBootstrap(path))
            if (!flight.adjacentPredecessorGate) {
                startAckNetworkPrerequisites(client, flight, path, route)
            }
            route
        } catch (failure: Throwable) {
            synchronized(flightLifecycleLock(path)) {
                claimNetworkOwnershipRetirement(flight)
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_ack_start_${failure.javaClass.simpleName}"
                )
                flights.remove(path, flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_grant_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            client.leaveNtkStrictForegroundNetwork(path, viewerGeneration)
            return false
        }
        try {
            val worker = Thread(
                { runFlight(client, manga, path, flight, ackRoute) },
                "ntk-strict-exact-discovery"
            ).apply {
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
            if (!flight.retirement.attachWorker(worker)) {
                throw InterruptedIOException("Viewer ownership retired while worker was starting")
            }
            worker.start()
        } catch (failure: Throwable) {
            ackRoute.cancel()
            flight.physicalCalls.cancelAll()
            synchronized(flightLifecycleLock(path)) {
                claimNetworkOwnershipRetirement(flight)
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_worker_start_${failure.javaClass.simpleName}"
                )
                flights.remove(path, flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_worker_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            client.leaveNtkStrictForegroundNetwork(path, viewerGeneration)
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

    /**
     * Opens only the target document/challenge overlap for the one exact current-resume profile.
     * The caller has proved every required current image body reached EOF; it grants no target API,
     * body, source or decode admission. The generation marker closes release-before-flight races.
     */
    @JvmStatic
    fun releaseAdjacentControlAfterPredecessorBodiesResident(
        predecessorPath: String?,
        targetPath: String?,
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
        val generation = ViewerTelemetry.activeGeneration()
        if (generation <= 0L || !ViewerTelemetry.hasActiveSession()) return 0
        return synchronized(flightLifecycleLock("adjacent-gate:$key")) {
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
    @JvmOverloads
    fun releaseAdjacentBodiesAfterPredecessorComplete(
        path: String?,
        expectedTargetPath: String? = null,
    ): Int {
        val key = normalizedPath(path) ?: return 0
        val expectedTarget = expectedTargetPath?.let(::normalizedPath)
        if (expectedTargetPath != null && expectedTarget == null) return 0
        if (expectedTarget != null && !ntkAdjacentOwnerAllowsTarget(key, expectedTarget)) return 0
        val generation = ViewerTelemetry.activeGeneration()
        // A continuously appended reader retains the launch episode as its telemetry owner, so an
        // appended predecessor is intentionally not ViewerTelemetry's active episode. The exact
        // path match plus the active generation keeps this release scoped to the foreground
        // Session while allowing B-complete to unlock B->C.
        if (generation <= 0L || !ViewerTelemetry.hasActiveSession()) return 0
        return synchronized(flightLifecycleLock("adjacent-gate:$key")) {
            if (expectedTarget != null) {
            // Publish the authoritative pair before sweeping stale markers/flights. A late main
            // Handler callback for an older persisted target then observes this fence before it
            // can reserve a lease, even if it races the cleanup snapshot below.
            completedAdjacentTargets[adjacentGateKey(key, expectedTarget)] = generation
            // A persisted early-control identity is only a bounded transport hint. Reconcile it
            // with the provider/full-completion target before opening any API or image admission.
            // Cancelling a mismatched control-only flight also prevents later broad completion
            // maintenance from accidentally releasing its stale target.
            val staleMarkerTargets = bodyResidentAdjacentTargets.entries
                .filter { entry ->
                    entry.value == generation &&
                        entry.key.predecessorPath == key &&
                        entry.key.targetPath != expectedTarget
                }
                .map { it.key.targetPath }
                .toSet()
            bodyResidentAdjacentTargets.entries.forEach { entry ->
                if (entry.value == generation &&
                    entry.key.predecessorPath == key &&
                    entry.key.targetPath != expectedTarget
                ) {
                    bodyResidentAdjacentTargets.remove(entry.key, entry.value)
                }
            }
            val mismatches = flights.values.filter { flight ->
                flight.viewerGeneration == generation &&
                    flight.adjacentPredecessorGate &&
                    flight.adjacentPredecessorEpisodePath == key &&
                    flight.episodePath in staleMarkerTargets &&
                    !flight.adjacentPredecessorComplete.isDone &&
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

    private fun enterForegroundNetworkIfNeeded(flight: Flight) {
        synchronized(flight) {
            if (flight.foregroundNetworkEntered.get()) return
            if (flight.networkOwnershipRetiring.get() ||
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
                flight.client.cancelNtkWebViewFallbacks()
            } catch (failure: Throwable) {
                flight.foregroundNetworkEntered.set(false)
                throw failure
            }
        }
    }

    private fun releaseAdjacentBodyGate(flight: Flight): AdjacentBodyGateRelease {
        return synchronized(flight) {
            if (flight.adjacentPredecessorComplete.isDone) {
                return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
            }
            if (flight.networkOwnershipRetiring.get() ||
                flight.retirement.isRetired() ||
                flights[flight.episodePath] !== flight ||
                !isViewerOwnerActive(flight)
            ) {
                return@synchronized AdjacentBodyGateRelease.FAILED
            }
            try {
                flight.adjacentControlReady.complete(Unit)
                if (!flight.adjacentPredecessorComplete.complete(Unit)) {
                    return@synchronized AdjacentBodyGateRelease.ALREADY_OPEN
                }
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
                val retirementClaimed = claimNetworkOwnershipRetirement(owned)
                if (!retirementClaimed ||
                    !owned.retirement.retire(ownedPath, viewerGeneration)
                ) return@synchronized null
            // Detach the terminal lease before releasing this path's flight slot. Its asynchronous
            // close barrier is generation-routed through a tombstone and cannot mutate a replacement.
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    owned.lease,
                    "strict_exact_owner_retired_${safeReason(reason)}"
                )
                // Removing by identity lets a newer same-path viewer create its own generation now;
                // the retired worker's finally block cannot remove that newer entry.
                flights.remove(ownedPath, owned)
                owned
            } ?: continue
            Log.d(
                "ViewerPerf",
                "ntk_strict_exact_owner_retired path=$ownedPath," +
                    "viewerOwnerPath=$key,viewerGeneration=$viewerGeneration," +
                    "discoveryGeneration=${flight.lease.generation.value}," +
                    "reason=${safeReason(reason)}"
            )
            flight.client.leaveNtkStrictForegroundNetwork(ownedPath, flight.viewerGeneration)
            retiredAny = true
        }
        completedAdjacentPredecessors.entries.forEach { entry ->
            if (entry.value == viewerGeneration) {
                completedAdjacentPredecessors.remove(entry.key, entry.value)
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
            if (!claimNetworkOwnershipRetirement(owned) ||
                !owned.retirement.retire(key, viewerGeneration)
            ) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_adjacent_recovery_${safeReason(reason)}",
            )
            flights.remove(key, owned)
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
        flight.client.leaveNtkStrictForegroundNetwork(key, viewerGeneration)
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
            if (!claimNetworkOwnershipRetirement(owned) ||
                !owned.retirement.retire(key, viewerGeneration)
            ) {
                return@synchronized null
            }
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_consumed_${safeReason(reason)}",
            )
            flights.remove(key, owned)
            owned
        } ?: return false
        completedAdjacentPredecessors.remove(key, viewerGeneration)
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
        flight.client.leaveNtkStrictForegroundNetwork(key, viewerGeneration)
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

                                        override fun onBodyPrefix(bodyPrefix: ByteArray): Boolean {
                                            if (requestSeedFuture.isDone) return true
                                            val seed =
                                                NtkViewerImageRequestSeedParser.parseIfPresent(
                                                    flight.lease,
                                                    path,
                                                    bodyPrefix,
                                                ) ?: return false
                                            if (requestSeedFuture.complete(seed)) {
                                                Log.d(
                                                    "ViewerPerf",
                                                    "ntk_strict_document_request_seed_ready path=$path," +
                                                        "generation=${flight.lease.generation.value}," +
                                                        "bytes=${bodyPrefix.size}," +
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
            clickOwnedAnchor?.let { anchor ->
                NtkEpisodeDocumentPlanParser.completeNumericPageCountHint(
                    flight.lease,
                    path,
                    documentResponse,
                )?.let(anchor::releaseForCompleteDocumentPageCount)
            }
            val draft = traceStage("NtkDocumentPlanParse") {
                NtkEpisodeDocumentPlanParser.parse(
                    flight.lease,
                    path,
                    documentResponse
                )
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
                        client.buildUnsignedExactNtkViewerImageApiRequest(draft, "")
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
            routeRecoveryRequested = !exactInstalled &&
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) == null &&
                NtkStrictRouteRecoveryPolicy.shouldRecover(
                    failure,
                    flight.completedRouteRecoveryAttempts,
                ) &&
                isViewerOwnerActive(flight)
            restartSameOriginWithoutResolver = routeRecoveryRequested &&
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
                synchronized(flightLifecycleLock(path)) {
                    if (flights[path] === flight) {
                        claimNetworkOwnershipRetirement(flight)
                        flight.retirement.retire(path, flight.viewerGeneration)
                        NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                            flight.lease,
                            "strict_exact_post_install_${failure.javaClass.simpleName}"
                        )
                        flights.remove(path, flight)
                    }
                }
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
                client.leaveNtkStrictForegroundNetwork(path, flight.viewerGeneration)
            } else if (!flight.completed.get() || flight.retirement.isRetired()) {
                claimNetworkOwnershipRetirement(flight)
                flights.remove(path, flight)
                client.leaveNtkStrictForegroundNetwork(path, flight.viewerGeneration)
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
        var releasedForReplacement = false
        synchronized(flightLifecycleLock(path)) {
            if (flights[path] === failedFlight) {
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    failedFlight.lease,
                    if (skipDomainResolution) {
                        "strict_same_origin_transport_fallback"
                    } else {
                        "strict_route_recovery_$nextRouteRecoveryAttempts"
                    },
                )
                flights.remove(path, failedFlight)
                releasedForReplacement = true
            }
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
    ): T = flight.retirement.withActiveOwnership(
        flight.episodePath,
        flight.viewerGeneration,
        boundary
    ) {
        requireFlightIdentity(flight, boundary)
        action()
    }

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
        ViewerTelemetry.hasActiveSession() &&
            ViewerTelemetry.activeGeneration() == flight.viewerGeneration &&
            ViewerTelemetry.isActiveEpisode(flight.viewerOwnerEpisodePath)

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
        awaitFuture(flight.adjacentPredecessorComplete)
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
        awaitFuture(flight.adjacentControlReady)
        requireDiscoveryOwnership(flight, "adjacent_control_ready")
        Log.d(
            "ViewerPerf",
            "ntk_adjacent_control_ready " +
                "predecessor=${flight.adjacentPredecessorEpisodePath}," +
                "target=${flight.episodePath}," +
                "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
        )
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
