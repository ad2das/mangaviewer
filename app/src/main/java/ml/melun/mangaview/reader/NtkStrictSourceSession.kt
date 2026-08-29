package ml.melun.mangaview.reader

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
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
 * A delayed retry can be inspected by a refill before its deadline and removed from the
 * pre-geometry deque. Re-admit only the bounded direct-WiFi adjacent session here: its initial
 * runway is the exact next episode and the predecessor has already won foreground resources.
 * Cellular/SNI and the current episode keep their existing scheduling semantics.
 */
internal object NtkStrictAdjacentRetryReadmissionPolicy {
    fun remainingDelayMs(retryNotBeforeMs: Long, nowMs: Long): Long {
        require(retryNotBeforeMs >= 0L)
        require(nowMs >= 0L)
        return (retryNotBeforeMs - nowMs).coerceAtLeast(0L)
    }

    fun shouldReadmit(
        directWifiTransport: Boolean,
        adjacentPrefetch: Boolean,
        geometrySealed: Boolean,
        hasSourceDemand: Boolean,
        retryReady: Boolean,
        alreadyQueued: Boolean,
    ): Boolean = directWifiTransport && adjacentPrefetch &&
        !geometrySealed && !hasSourceDemand && retryReady && !alreadyQueued
}

/**
 * Freezes the telemetry boundary with the source session's transport role. Direct wired/Wi-Fi
 * strict retries are one logical page request, so an unsuccessful HTTP response is not published
 * as a user-visible image cancellation before the actor has had a chance to retry it. A successful
 * response still goes through the strict body/format proof and is reported as failed if that proof
 * fails. Carrier/SNI keeps
 * the established per-physical-attempt telemetry path unchanged.
 */
internal object NtkStrictLogicalImageTelemetryPolicy {
    fun afterSuccessfulHeaders(
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
    ): Boolean = directWifiTransport && !cellularResilientTransport
}

/**
 * Decides whether a route prepared after rolling admission must still wait for the click-time
 * filename probe. Once the exact manifest is owned, a page that has no click-owned body in flight
 * already has its complete canonical identity in that manifest. Waiting for the unrelated probe
 * can otherwise leave every newly admitted suffix route as an incomplete future forever when the
 * finite click wave has ceded that suffix to the strict source actor.
 */
internal object NtkStrictLateRoutePreparationPolicy {
    fun shouldWaitForStreamedSourceRoute(
        exactManifestOwned: Boolean,
        streamedExactBodyPending: Boolean,
    ): Boolean = !exactManifestOwned || streamedExactBodyPending
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

    /**
     * Reserves the close callback before publishing the external close flag. The submitted actor
     * task waits until that publication is complete, so neither a draining callback nor a very
     * fast actor can observe a close request without the matching close-control callback counted.
     */
    fun admitClose(
        publishCloseRequested: () -> Unit,
        submit: (awaitClosePublication: () -> Unit) -> Unit,
    ): Boolean = synchronized(lock) {
        if (admissionsClosed) return@synchronized false
        pendingCallbacks++
        val closePublished = CountDownLatch(1)
        var submitted = false
        try {
            submit { closePublished.await() }
            submitted = true
            publishCloseRequested()
            closePublished.countDown()
            true
        } catch (failure: Throwable) {
            closePublished.countDown()
            if (!submitted) {
                pendingCallbacks--
                check(pendingCallbacks >= 0)
            }
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

/**
 * One fair process-wide budget for optional disjoint suffix Calls. A batch lease is deliberately
 * separate from the source-session lifecycle: the response body that starts the physical Range
 * owns it and can release it from every EOF, failure, and cancellation path. Double-close is a
 * no-op, so racing EOF/cancellation/outer-response closes cannot inflate capacity.
 */
internal class NtkDirectWifiShortWebtoonTailPermitGate(
    val maximumPermits: Int,
) {
    init {
        require(maximumPermits > 0)
    }

    private val permits = Semaphore(maximumPermits, true)

    fun tryAcquire(): Lease? = tryAcquire(1)

    /** Acquires one logical body's complete suffix group atomically or not at all. */
    fun tryAcquire(permitCount: Int): Lease? {
        require(permitCount in 1..maximumPermits)
        if (!permits.tryAcquire(permitCount)) return null
        return Lease(permits, permitCount)
    }

    internal fun availablePermitsForTest(): Int = permits.availablePermits()

    internal class Lease(
        private val permits: Semaphore,
        private val permitCount: Int,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) permits.release(permitCount)
        }
    }
}

/**
 * Defers release of one frozen direct-Wi-Fi webtoon suffix lease until its owning response is done
 * with the group and every admitted physical network fetch has terminated. Cancellation may
 * request release before Call.execute() returns, so closing the lease from that request would let
 * a replacement suffix exceed the process-wide physical-Call bound. Current and adjacent roles
 * have separate gates, but share this physical lifecycle contract.
 *
 * A task reports its stable index. This makes a repeated terminal callback harmless without
 * allowing one task's duplicate callback to stand in for a different task which is still running.
 */
internal class NtkDirectWifiWebtoonTailLeaseOwner(
    private val lease: Closeable,
    val physicalTaskCount: Int,
) {
    init {
        require(physicalTaskCount > 0)
    }

    private val lock = Any()
    private val physicalTerminated = BooleanArray(physicalTaskCount)
    private var remainingPhysicalTasks = physicalTaskCount
    private var releaseRequested = false
    private var leaseCloseClaimed = false

    fun requestRelease() {
        val closeLease = synchronized(lock) {
            releaseRequested = true
            claimLeaseCloseLocked()
        }
        if (closeLease) lease.close()
    }

    fun physicalTerminated() {
        require(physicalTaskCount == 1)
        physicalTerminated(0)
    }

    fun physicalTerminated(physicalTaskIndex: Int) {
        val closeLease = synchronized(lock) {
            require(physicalTaskIndex in physicalTerminated.indices)
            if (!physicalTerminated[physicalTaskIndex]) {
                physicalTerminated[physicalTaskIndex] = true
                remainingPhysicalTasks--
                check(remainingPhysicalTasks >= 0)
            }
            claimLeaseCloseLocked()
        }
        if (closeLease) lease.close()
    }

    /** Retires a group which lost cancellation immediately after publication, before submission. */
    fun retireAllUnstartedPhysicalTasks() {
        repeat(physicalTaskCount, ::physicalTerminated)
    }

    private fun claimLeaseCloseLocked(): Boolean {
        if (!releaseRequested || remainingPhysicalTasks != 0 || leaseCloseClaimed) return false
        leaseCloseClaimed = true
        return true
    }
}

/**
 * Two-sided cancellation handshake for one frozen direct-Wi-Fi suffix segment.
 *
 * Cancellation can win immediately before or after the worker publishes its physical Call. The
 * cancelling thread records the request before looking for a registered Call, while the worker
 * publishes the Call before rechecking that request. One of those two sides therefore owns the
 * physical cancellation without making the healthy primary wait for the suffix worker to unwind.
 */
internal class NtkDirectWifiWebtoonTailSegmentCancellation<T : Any>(
    private val cancelPhysical: (T) -> Unit,
) {
    private val cancellationRequested = AtomicBoolean(false)
    private val registeredPhysical = AtomicReference<T?>(null)

    /** Returns false after cancelling [physical] when cancellation won before registration. */
    fun register(physical: T): Boolean {
        check(registeredPhysical.compareAndSet(null, physical)) {
            "Direct-Wi-Fi suffix registered overlapping physical work"
        }
        if (!cancellationRequested.get()) return true
        cancelRegisteredPhysical(physical)
        return false
    }

    fun clear(physical: T) {
        registeredPhysical.compareAndSet(physical, null)
    }

    fun requestCancellation() {
        cancellationRequested.set(true)
        registeredPhysical.getAndSet(null)?.let(cancelPhysical)
    }

    internal fun isCancellationRequestedForTest(): Boolean = cancellationRequested.get()

    private fun cancelRegisteredPhysical(physical: T) {
        if (registeredPhysical.compareAndSet(physical, null)) cancelPhysical(physical)
    }
}

/**
 * Immutable per-request authorization for the bounded direct-Wi-Fi suffix experiment.
 *
 * This is not a routing decision. The session freezes all eligibility inputs before any source
 * Call exists, then binds the tag to one exact manifest/page/asset tuple. ReaderImageCache may
 * consume the tag only for validator-checked, byte-disjoint Range suffixes. Page zero never gets
 * a tag, and one tag can claim at most one all-or-none physical Call group for its lifetime.
 */
internal class NtkDirectWifiShortWebtoonTailTag internal constructor(
    val normalizedEpisodePath: String,
    val manifestDigest: String,
    val viewerGeneration: Long,
    val pageIndex: Int,
    val pageCount: Int,
    val canonicalAssetDigest: String,
    private val permitGate: NtkDirectWifiShortWebtoonTailPermitGate,
) {
    init {
        require(normalizedEpisodePath.startsWith("/webtoon/"))
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) ==
            normalizedEpisodePath)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(viewerGeneration > 0L)
        require(pageIndex in 1 until pageCount)
        require(pageCount in 2..NtkDirectWifiShortWebtoonTailProfile.MAX_EPISODE_PAGES)
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
    }

    // Fixed cold A/B runs showed that a two-socket suffix group was gated by its slowest H1 socket
    // (3.51-3.72 s versus 2.94-3.05 s with one request). Preserve one exact, disjoint suffix for
    // this current direct-Wi-Fi role; adjacent/mobile/SNI keep their independent policies.
    val maximumExtraTailRequests: Int = 1
    private val extraTailClaimed = AtomicBoolean(false)

    /**
     * Returns an idempotent global-budget lease or null without blocking the primary body.
     * A cancellation observed on either side of permit acquisition cannot strand a permit.
     */
    fun tryAcquireExtraTail(cancelled: AtomicBoolean):
        NtkDirectWifiShortWebtoonTailPermitGate.Lease? =
        tryAcquireExtraTails(1, cancelled)

    fun tryAcquireExtraTails(
        requestCount: Int,
        cancelled: AtomicBoolean,
    ): NtkDirectWifiShortWebtoonTailPermitGate.Lease? {
        if (requestCount !in 1..maximumExtraTailRequests) return null
        if (cancelled.get() || !extraTailClaimed.compareAndSet(false, true)) return null
        val lease = permitGate.tryAcquire(requestCount)
        if (lease == null) {
            // Capacity can become available while this responsive primary continues. Permit a
            // later bounded checkpoint to retry, but never allow two concurrent claims.
            extraTailClaimed.set(false)
            return null
        }
        if (cancelled.get()) {
            lease.close()
            return null
        }
        return lease
    }
}

/** Freezes the current direct-Wi-Fi short-webtoon identity at source-session construction. */
internal class NtkDirectWifiShortWebtoonTailProfile private constructor(
    internal val normalizedEpisodePath: String,
    internal val manifestDigest: String,
    internal val viewerGeneration: Long,
    internal val pageCount: Int,
    private val permitGate: NtkDirectWifiShortWebtoonTailPermitGate,
) {
    companion object {
        const val MAX_EPISODE_PAGES = 8
        const val GLOBAL_MAX_CONCURRENT_EXTRA_TAILS = 4

        private val globalPermitGate = NtkDirectWifiShortWebtoonTailPermitGate(
            GLOBAL_MAX_CONCURRENT_EXTRA_TAILS,
        )

        fun freeze(
            episodePath: String,
            manifestDigest: String,
            pageCount: Int,
            rollingAdmission: Boolean,
            directWifiTransport: Boolean,
            cellularResilientTransport: Boolean,
            currentForegroundViewerGeneration: Long,
            adjacentPrefetch: Boolean,
            permitGate: NtkDirectWifiShortWebtoonTailPermitGate = globalPermitGate,
        ): NtkDirectWifiShortWebtoonTailProfile? {
            if (!rollingAdmission || !directWifiTransport || cellularResilientTransport ||
                adjacentPrefetch || currentForegroundViewerGeneration <= 0L ||
                !episodePath.startsWith("/webtoon/") || pageCount !in 2..MAX_EPISODE_PAGES ||
                NtkStripDigests.normalizeEpisodePath(episodePath) != episodePath ||
                !NtkStripDigests.isSha256(manifestDigest)
            ) return null
            return NtkDirectWifiShortWebtoonTailProfile(
                episodePath,
                manifestDigest,
                currentForegroundViewerGeneration,
                pageCount,
                permitGate,
            )
        }
    }

    fun tagForPage(
        pageIndex: Int,
        canonicalAssetDigest: String,
    ): NtkDirectWifiShortWebtoonTailTag? {
        if (pageIndex !in 1 until pageCount ||
            !NtkStripDigests.isSha256(canonicalAssetDigest)
        ) return null
        return NtkDirectWifiShortWebtoonTailTag(
            normalizedEpisodePath,
            manifestDigest,
            viewerGeneration,
            pageIndex,
            pageCount,
            canonicalAssetDigest,
            permitGate,
        )
    }
}

/**
 * Completion latch shared only by one frozen adjacent-session runway. The latch is monotonic:
 * route preparation may bind tags ahead of time, but no extra physical suffix can claim capacity
 * until the source actor has observed the predecessor's terminal completion event.
 */
internal class NtkDirectWifiAdjacentWebtoonPredecessorGate {
    private val complete = AtomicBoolean(false)

    fun markComplete() {
        complete.set(true)
    }

    fun isComplete(): Boolean = complete.get()
}

/**
 * Pure lane calculation for a direct-Wi-Fi adjacent webtoon.
 *
 * p0 is the only body admitted until OkHttp physically writes its exact request headers. At that
 * point p0 owns the first H2 stream and the remaining host p1-p4 runway may open on the established
 * direct-Wi-Fi pool. This preserves p0's DNS/TLS/wire-order head start while giving the current tail
 * enough time to prepare the complete next runway. Once p0 reaches EOF, admissions pause only
 * for its short decode/install ACK; afterwards the bounded p1-p4 lanes remain available. Rolling
 * admission still caps this phase at p1-p4 on the host and at p1-p3 on legacy profiles. The host
 * emulator's adjacent manhwa uses the stricter EOF form because concurrent multi-megabyte PNG
 * bodies were measured starving input before p0; carrier/SNI, generic and current-episode sessions
 * retain their established lane policy.
 */
internal object NtkDirectWifiAdjacentHeadInstallGatePolicy {
    fun usableLaneCount(
        progressiveLaneCount: Int,
        preAnchorGateOperations: Int,
        webtoon: Boolean,
        requiresHeadInstall: Boolean,
        anchorBodyPublished: Boolean,
        anchorRequestHeadersSent: Boolean,
        headPixelsInstalled: Boolean,
        prioritizeAnchorUntilEof: Boolean = false,
        initialRunwayBodyCount: Int =
            NtkStrictInitialWavePolicy.WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
    ): Int {
        require(progressiveLaneCount >= 0 && preAnchorGateOperations >= 0)
        require(initialRunwayBodyCount > 0)
        return when {
            requiresHeadInstall && prioritizeAnchorUntilEof && !anchorBodyPublished ->
                minOf(progressiveLaneCount, preAnchorGateOperations)
            requiresHeadInstall && anchorBodyPublished && !headPixelsInstalled -> 0
            requiresHeadInstall && headPixelsInstalled -> minOf(
                progressiveLaneCount,
                initialRunwayBodyCount - 1,
            )
            requiresHeadInstall && anchorRequestHeadersSent -> minOf(
                progressiveLaneCount,
                initialRunwayBodyCount,
            )
            webtoon && !anchorBodyPublished ->
                minOf(progressiveLaneCount, preAnchorGateOperations)
            else -> progressiveLaneCount
        }
    }
}

/**
 * Prevents a host-emulator resume from turning one healthy current-webtoon wave into a replica
 * failover storm. This is deliberately applied after the generic cohort/progressive calculation:
 * cold-pool discovery and every non-emulator transport retain their established topology, while
 * only newly admitted post-anchor bodies are bounded. The physical direct-Wi-Fi selector prefers
 * one compatibility origin and exposes multiple host-local H2 pools. A short nine-body opening
 * runway preserves first-visible readiness. Once six contiguous bodies prove that runway healthy,
 * an eight-body rolling wave keeps the link busy without reopening the reset storm measured when
 * the restored suffix filled the whole physical pool ring.
 */
internal object NtkHostGpuEmulatorCurrentWebtoonLanePolicy {
    // The restored-current compatibility route converges on one shared CDN path. Independent
    // 170-body cold runs at 22-24 active operations took 38-40 s and produced 6-8 reset/timeout
    // retries; later KR/ICN runs confirmed that over-admission can still reset healthy edge H2.
    // Keep the established nine-body first-visible runway, then one eight-body rolling wave. The
    // existing recovery governor narrows it to 1/4 after a socket failure and never cancels an
    // already-running exact body.
    private const val MAX_POST_ANCHOR_TRANSFERS = 8
    // Exact H3 removes the H2 reset constraint, but it does not remove the edge's bandwidth and
    // request-queue limit. Keep the proven transport work-conserving at the measured eight-body
    // optimum instead of mistaking protocol proof for unlimited useful concurrency.
    internal const val MAX_PROVEN_QUIC_TRANSFERS = 8
    internal const val MAX_WELL_PROVEN_QUIC_TRANSFERS = 8
    internal const val WELL_PROVEN_QUIC_FINAL_WAVE_MAX_TRANSFERS = 12
    // The exact recovery transport owns eight captured-network H2 pools, matching the reset-safe
    // current-episode wave. Refill one body per pool from the sealed manifest.
    internal const val MAX_PROVEN_FRAGMENTED_TLS_TRANSFERS = 8
    // p0 still owns the cold connection alone. Once it is exact-resident, admit four successors
    // at a time and refill only from the finite opening runway. This avoids concentrating every
    // decode/publication callback in one frame while keeping later suffix pages out of the wave.
    internal const val INITIAL_VISIBLE_RUNWAY_TRANSFERS = 4
    // The first nine bodies are the finite physical runway and the same transport proof. Six
    // bodies cover only about 1.3 seconds of a fast fling for ordinary webtoon slices; the next
    // body's request then starts with the bulk suffix and its first gfxstream upload lands exactly
    // at the drawable-prefix fence. Keeping p0..p8 in the opening wave starts those immutable
    // bodies before input without widening the steady-state ring or changing any retry authority.
    internal const val INITIAL_VISIBLE_RUNWAY_BODIES = 9
    // Keep all nine encoded bodies in the cold transport runway, but exempt only the established
    // p0..p5 physical opening runway from the decode fence. Decoding p6..p8 into large host
    // HardwareBuffers during the same gesture made input injection wait several seconds even
    // though those pixels were still offscreen. Their verified bodies remain resident and the
    // ordinary identity-bound blocker lane decodes them as the viewport actually approaches.
    internal const val INITIAL_SCROLL_RUNWAY_BODIES = 6
    // The settled 64 MiB pixel budget holds the physical webtoon viewport plus eight common
    // 800px compositor pages. Fill this window only while idle, one page at a time; the existing
    // 128 MiB rolling envelope remains the hard ceiling for unusually tall atomic pages.
    internal const val STEADY_FORWARD_RETAIN_AHEAD_PAGES =
        INITIAL_VISIBLE_RUNWAY_BODIES + 2
    internal const val SHORT_CURRENT_MAX_EPISODE_PAGES = 16

    fun openingProofIncomplete(contiguousForwardBodyCount: Int): Boolean {
        require(contiguousForwardBodyCount >= 0)
        return contiguousForwardBodyCount < INITIAL_VISIBLE_RUNWAY_BODIES
    }

    /**
     * Starts only the final two partial batches early. The ordinary wave stays at eight; once at
     * most sixteen bodies remain, admission may rise to twelve so the last four bodies begin as
     * soon as earlier slots finish. Sustained bulk transfer never observes this tail-only cap.
     */
    fun finalWaveCap(
        ordinaryCap: Int,
        remainingBodies: Int,
        wellProvenQuicRecoveryQualified: Boolean,
    ): Int {
        require(ordinaryCap >= 0)
        require(remainingBodies >= 0)
        return if (wellProvenQuicRecoveryQualified &&
            ordinaryCap == MAX_WELL_PROVEN_QUIC_TRANSFERS &&
            remainingBodies > ordinaryCap &&
            remainingBodies <= ordinaryCap * 2
        ) {
            minOf(WELL_PROVEN_QUIC_FINAL_WAVE_MAX_TRANSFERS, remainingBodies)
        } else {
            ordinaryCap
        }
    }

    fun cap(
        progressiveLaneCount: Int,
        emulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        webtoon: Boolean,
        rollingAdmission: Boolean,
        initialPageIndex: Int,
        currentForegroundEpisode: Boolean,
        adjacentPrefetch: Boolean,
        anchorBodyPublished: Boolean,
        contiguousForwardBodyCount: Int,
        healthyBulkExpansion: Boolean,
        fragmentedTlsRecoveryQualified: Boolean = false,
        quicRecoveryQualified: Boolean = false,
        wellProvenQuicRecoveryQualified: Boolean = false,
    ): Int {
        require(progressiveLaneCount >= 0)
        require(initialPageIndex >= 0)
        require(contiguousForwardBodyCount >= 0)
        val eligible = emulatorRuntime && directWifiTransport &&
            !cellularResilientTransport && webtoon && rollingAdmission &&
            currentForegroundEpisode && !adjacentPrefetch &&
            anchorBodyPublished
        return if (eligible) {
            // Reserve the established bounded opening wave for the pixels immediately in front of
            // the viewport. As soon as nine consecutive forward bodies are exact-resident, use a
            // bounded eight-body rolling wave. This keeps small-object downloads work-conserving
            // while the recovery governor still collapses any socket pressure to 1/4 immediately;
            // it never reopens the measured 22-24-body reset storm for the long suffix.
            val scopedLimit = if (openingProofIncomplete(contiguousForwardBodyCount)) {
                // A transport qualification proves that the completed bodies were exact; it does
                // not prove that pixels immediately in front of the viewport are resident. Keep
                // the finite opening wave focused on those contiguous pages before allowing the
                // wider recovered-transport ring to consume bandwidth or decode/disk callbacks.
                INITIAL_VISIBLE_RUNWAY_TRANSFERS
            } else if (wellProvenQuicRecoveryQualified) {
                // The complete opening runway and distinct exact H3 proofs have completed without
                // failover or range continuation. Fill the measured stable isolated-engine ring.
                MAX_WELL_PROVEN_QUIC_TRANSFERS
            } else if (quicRecoveryQualified) {
                // Three exact H3 EOF/SHA bodies prove the recovery transport before the bounded
                // isolated-engine ring receives eight streams. Opening proofs count, while the
                // independent contiguous p0..p8 fence still prevents premature bulk admission.
                MAX_PROVEN_QUIC_TRANSFERS
            } else if (fragmentedTlsRecoveryQualified) {
                // Ordinary H2 is capped because one translated socket previously reset at wide
                // admission. This recovered transport has eight independent connection shards
                // and reaches this branch only after exact EOF/SHA progress.
                MAX_PROVEN_FRAGMENTED_TLS_TRANSFERS
            } else if (healthyBulkExpansion) {
                MAX_POST_ANCHOR_TRANSFERS
            } else {
                INITIAL_VISIBLE_RUNWAY_BODIES
            }
            if (openingProofIncomplete(contiguousForwardBodyCount)) {
                // The generic cold-cohort gate describes H2 connection settlement and can expose
                // only three lanes here. This finite current-episode runway is already routed to
                // independent H3 engines, so after p0 publication it may keep four opening lanes
                // full until p8 without admitting the unrelated suffix.
                INITIAL_VISIBLE_RUNWAY_TRANSFERS
            } else {
                minOf(progressiveLaneCount, scopedLimit)
            }
        } else {
            progressiveLaneCount
        }
    }

    /**
     * Route preparation completes out of order. During the finite opening wave, do not let a
     * later ready cohort displace one of the contiguous pages that can fill the restored
     * viewport. This changes only admission order; every page retains its exact route and body.
     */
    fun allowsOpeningWavePage(
        pageIndex: Int,
        pageCount: Int,
        initialPageIndex: Int,
        initialWaveTarget: Int,
        openingWaveIncomplete: Boolean,
        eligible: Boolean,
    ): Boolean {
        require(pageCount > 0)
        require(pageIndex in 0 until pageCount)
        require(initialPageIndex in 0 until pageCount)
        require(initialWaveTarget >= 0)
        if (!eligible || !openingWaveIncomplete) return true
        return pageIndex in initialPageIndex until minOf(
            pageCount,
            initialPageIndex + initialWaveTarget,
        )
    }

    /**
     * The compatibility-host shard function maps consecutive opening pages onto only three H2
     * identities even though this current-episode runway uses independent H3 engines. Permit the
     * complete p1..p8 wave only inside that finite viewport; every later page retains the
     * one-operation-per-pool recovery topology.
     */
    fun openingWaveAnchorPoolOperationLimit(
        ordinaryLimit: Int,
        eligible: Boolean,
        openingWaveIncomplete: Boolean,
        pageInOpeningWave: Boolean,
    ): Int {
        require(ordinaryLimit > 0)
        return if (eligible && openingWaveIncomplete && pageInOpeningWave) {
            maxOf(ordinaryLimit, INITIAL_VISIBLE_RUNWAY_TRANSFERS)
        } else {
            ordinaryLimit
        }
    }

}

/**
 * Exact post-failure proof for widening only the fragmented-TLS recovery transport.
 * Cache hits and ordinary/H1/H2 responses cannot qualify.
 */
internal class NtkHostGpuEmulatorFragmentedTlsRecoveryHealthState(
    private val requiredSuccesses: Int = REQUIRED_SUCCESSES,
) {
    companion object {
        const val TRANSPORT = "exact-fragmented-tls-recovery"
        const val REQUIRED_SUCCESSES = 3
    }

    private val successfulWorkIds = HashSet<Long>()

    var qualified: Boolean = false
        private set

    init {
        require(requiredSuccesses > 0)
    }

    fun recordSuccess(
        workId: Long,
        evidence: ReaderImageCache.NtkStrictPhysicalBodyEvidence,
    ): Boolean {
        require(workId > 0L)
        if (qualified || !evidence.transport.equals(TRANSPORT, ignoreCase = true) ||
            !successfulWorkIds.add(workId)
        ) return false
        if (successfulWorkIds.size < requiredSuccesses) return false
        qualified = true
        return true
    }
}

/** Exact H3 EOF/SHA proof that alone may widen the balanced recovery ring. */
internal class NtkHostGpuEmulatorCurrentWebtoonQuicHealthState(
    private val requiredSuccesses: Int = REQUIRED_SUCCESSES,
    private val requiredWellProvenSuccesses: Int = REQUIRED_WELL_PROVEN_SUCCESSES,
) {
    companion object {
        const val TRANSPORT = "exact-quic-recovery"
        const val REQUIRED_SUCCESSES = 3
        const val REQUIRED_WELL_PROVEN_SUCCESSES = 6
    }

    private val observedWorkIds = HashSet<Long>()
    private var successfulProofs = 0

    var qualified: Boolean = false
        private set
    var wellProven: Boolean = false
        private set
    var frozen: Boolean = false
        private set

    init {
        require(requiredSuccesses > 0)
        require(requiredWellProvenSuccesses > requiredSuccesses)
    }

    fun recordSuccess(
        pageIndex: Int,
        workId: Long,
        evidence: ReaderImageCache.NtkStrictPhysicalBodyEvidence,
    ): Boolean {
        require(pageIndex >= 0)
        require(workId > 0L)
        if (!observedWorkIds.add(workId)) return false
        val exactH3 = evidence.transport.equals(TRANSPORT, ignoreCase = true) &&
            evidence.protocol.equals("h3", ignoreCase = true) &&
            !evidence.usedRangeContinuation
        if (!exactH3) {
            if (qualified) {
                // A Range-completed H3 body or one exact fallback is saturation evidence, not
                // proof that every already-qualified H3 authority is dead. Permanently
                // blacklisting the transport here pushed the full suffix onto known-slower H2.
                // Drop the 24-body tier now, retain the proven twelve-body tier, and require a
                // complete new exact-H3 proof window before widening again. A physical operation
                // that fails altogether first drops to the same conservative base and cannot
                // recover until that fresh window is complete.
                successfulProofs = 0
                wellProven = false
                return false
            }
            // A fallback proves only that this one body did not use the preferred H3 path. Keep
            // the conservative six-lane cap and reset the proof window, but do not permanently
            // blacklist a session in which later independent bodies may prove exact H3 again.
            // An outright physical failure enters the same base cap through recordFailure(); it
            // likewise requires a complete fresh window before it can recover.
            successfulProofs = 0
            qualified = false
            wellProven = false
            return false
        }
        successfulProofs++
        var changed = false
        if ((!qualified || frozen) && successfulProofs >= requiredSuccesses) {
            frozen = false
            qualified = true
            changed = true
        }
        if (!wellProven && successfulProofs >= requiredWellProvenSuccesses) {
            wellProven = true
            changed = true
        }
        return changed
    }

    fun recordFailure(pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        if (frozen) return false
        frozen = true
        qualified = false
        wellProven = false
        successfulProofs = 0
        return true
    }
}

/**
 * Actor-confined, fail-closed proof for widening a current-webtoon rolling wave from six to eight.
 * A cache hit is not evidence. Six fresh first-attempt preferred-host H2 bodies must complete
 * their strict EOF/SHA proof within three seconds on six distinct physical cohorts. Accepted
 * response provenance (including synthetic recovery responses) must still match the preferred
 * host and first physical request. Any physical
 * failure, internal failover, range continuation, retry, slow body, alternate host, or non-H2
 * response permanently freezes this session at six; active calls are never cancelled.
 */
internal class NtkHostGpuEmulatorCurrentWebtoonC8HealthState(
    private val requiredDistinctCohorts: Int = REQUIRED_DISTINCT_COHORTS,
    private val fastProofLimitMs: Long = FAST_PROOF_LIMIT_MS,
    private val preferredHost: String =
        NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_H2_PREFERRED_HOST,
) {
    data class Transition(
        val oldTarget: Int,
        val newTarget: Int,
        val qualified: Boolean,
        val frozen: Boolean,
        val reason: String,
        val operationId: Long,
        val pageIndex: Int,
        val evidenceCount: Int,
        val distinctCohortCount: Int,
        val elapsedMs: Long,
    )

    companion object {
        const val BASE_TARGET = 6
        const val EXPANDED_TARGET = 8
        const val REQUIRED_DISTINCT_COHORTS = 6
        const val FAST_PROOF_LIMIT_MS = 3_000L
    }

    private val seenOperationIds = HashSet<Long>()
    private val healthyCohorts = HashSet<String>()
    private var evidenceCount = 0

    var qualified: Boolean = false
        private set
    var frozen: Boolean = false
        private set

    init {
        require(requiredDistinctCohorts > 0)
        require(fastProofLimitMs > 0L)
        require(preferredHost.isNotBlank())
    }

    fun recordSuccess(
        operationId: Long,
        pageIndex: Int,
        attemptOrdinal: Int,
        cohortKey: String,
        evidence: ReaderImageCache.NtkStrictPhysicalBodyEvidence,
    ): Transition? {
        require(operationId > 0L)
        require(pageIndex >= 0)
        require(attemptOrdinal > 0)
        if (frozen || !seenOperationIds.add(operationId)) return null
        val elapsedMs =
            (evidence.proofReadyAtNanos - evidence.physicalStartedAtNanos) / 1_000_000L
        val unhealthyReason = when {
            attemptOrdinal != 1 -> "retry_success"
            evidence.physicalAttemptOrdinal != 0 -> "physical_failover"
            !evidence.protocol.equals("h2", ignoreCase = true) -> "non_h2_success"
            !evidence.responseHost.equals(preferredHost, ignoreCase = true) ->
                "alternate_host_success"
            evidence.usedRangeContinuation -> "range_continuation"
            cohortKey.isBlank() -> "missing_cohort"
            elapsedMs > fastProofLimitMs -> "slow_success"
            else -> null
        }
        if (unhealthyReason != null) {
            return freeze(unhealthyReason, operationId, pageIndex, elapsedMs)
        }
        evidenceCount++
        healthyCohorts += cohortKey
        if (qualified || healthyCohorts.size < requiredDistinctCohorts) return null
        qualified = true
        return transition(
            oldTarget = BASE_TARGET,
            newTarget = EXPANDED_TARGET,
            reason = "balanced_physical_eof",
            operationId = operationId,
            pageIndex = pageIndex,
            elapsedMs = elapsedMs,
        )
    }

    fun recordFailure(
        operationId: Long,
        pageIndex: Int,
        attemptOrdinal: Int,
    ): Transition? {
        require(operationId > 0L)
        require(pageIndex >= 0)
        require(attemptOrdinal > 0)
        if (frozen || !seenOperationIds.add(operationId)) return null
        return freeze(
            if (attemptOrdinal > 1) "retry_failure" else "physical_failure",
            operationId,
            pageIndex,
            -1L,
        )
    }

    private fun freeze(
        reason: String,
        operationId: Long,
        pageIndex: Int,
        elapsedMs: Long,
    ): Transition {
        val oldTarget = if (qualified) EXPANDED_TARGET else BASE_TARGET
        qualified = false
        frozen = true
        return transition(
            oldTarget,
            BASE_TARGET,
            reason,
            operationId,
            pageIndex,
            elapsedMs,
        )
    }

    private fun transition(
        oldTarget: Int,
        newTarget: Int,
        reason: String,
        operationId: Long,
        pageIndex: Int,
        elapsedMs: Long,
    ): Transition = Transition(
        oldTarget = oldTarget,
        newTarget = newTarget,
        qualified = qualified,
        frozen = frozen,
        reason = reason,
        operationId = operationId,
        pageIndex = pageIndex,
        evidenceCount = evidenceCount,
        distinctCohortCount = healthyCohorts.size,
        elapsedMs = elapsedMs,
    )
}

/**
 * Bounds a translated-socket reset storm without slowing a healthy source session. The first
 * outer socket failure lets already-running calls drain and admits one fresh proof request. Only
 * a body whose work was created after the latest trip may reopen the bounded recovery ring.
 * Three fresh, complete bodies then prove that the bounded recovery wave is moving again and
 * restore the ordinary H2 target. Failed pages remain page-local H1 work, whose response bodies
 * hold a separate four-permit physical gate through EOF. The low-level session fence remains
 * tripped, so any later socket failure returns the governor to the single proof lane immediately.
 */
internal object NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy {
    const val PROBING_LANE_TARGET = 1
    const val DEGRADED_LANE_TARGET =
        NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_ADJACENT_H1_RECOVERY_MAX_CONCURRENT
    const val RECOVERY_SUCCESS_TARGET = 3
    const val MINIMUM_RETRY_DELAY_MS = 125L
    const val BACKGROUND_OWNER_RECHECK_MS = 250L

    enum class Mode {
        HEALTHY,
        PROBING,
        DEGRADED,
        RECOVERED,
    }

    data class State(
        val mode: Mode = Mode.HEALTHY,
        val minimumRecoveryWorkId: Long = Long.MAX_VALUE,
        val recoverySuccessCount: Int = 0,
    ) {
        init {
            if (mode == Mode.PROBING) require(minimumRecoveryWorkId > 0L)
            require(recoverySuccessCount in 0..RECOVERY_SUCCESS_TARGET)
        }
    }

    fun laneTarget(
        ordinaryTarget: Int,
        state: State,
        eligible: Boolean,
    ): Int {
        require(ordinaryTarget >= 0)
        if (!eligible) return ordinaryTarget
        val limit = when (state.mode) {
            Mode.HEALTHY -> ordinaryTarget
            Mode.PROBING -> PROBING_LANE_TARGET
            Mode.DEGRADED -> DEGRADED_LANE_TARGET
            Mode.RECOVERED -> ordinaryTarget
        }
        return minOf(ordinaryTarget, limit)
    }

    /**
     * Persists a low-level trip that may race the actor completion callback. Returning a temporary
     * PROBING value only for lane arithmetic loses the recovery epoch when that callback completes
     * while the Activity is backgrounded; the session would then remain permanently pinned to a
     * one-lane calculation without accepting the proof successes that reopen it.
     */
    fun observeFenceTrip(
        state: State,
        fenceTripped: Boolean,
        nextWorkId: Long,
        eligible: Boolean,
    ): State {
        require(nextWorkId > 0L)
        return if (eligible && fenceTripped && state.mode == Mode.HEALTHY) {
            State(Mode.PROBING, nextWorkId, 0)
        } else {
            state
        }
    }

    fun recordFailure(
        state: State,
        failure: Throwable,
        nextWorkId: Long,
        eligible: Boolean,
    ): State {
        require(nextWorkId > 0L)
        return recordPressure(
            state = state,
            pressureObserved = eligible && isSocketPressureFailure(failure),
            nextWorkId = nextWorkId,
        )
    }

    /** Applies one already-scoped physical-pressure decision without reclassifying its cause. */
    fun recordPressure(
        state: State,
        pressureObserved: Boolean,
        nextWorkId: Long,
    ): State {
        require(nextWorkId > 0L)
        if (!pressureObserved) return state
        return State(Mode.PROBING, nextWorkId, 0)
    }

    fun recordSuccess(
        state: State,
        successfulWorkId: Long,
        eligible: Boolean,
    ): State {
        require(successfulWorkId > 0L)
        if (!eligible ||
            (state.mode != Mode.PROBING && state.mode != Mode.DEGRADED) ||
            successfulWorkId < state.minimumRecoveryWorkId
        ) return state
        val successCount = state.recoverySuccessCount + 1
        return if (successCount >= RECOVERY_SUCCESS_TARGET) {
            State(Mode.RECOVERED)
        } else {
            State(
                Mode.DEGRADED,
                state.minimumRecoveryWorkId,
                successCount,
            )
        }
    }

    fun retryDelayMs(
        ordinaryDelayMs: Long,
        state: State,
        eligible: Boolean,
    ): Long {
        require(ordinaryDelayMs >= 0L)
        return if (eligible &&
            (state.mode == Mode.PROBING || state.mode == Mode.DEGRADED)
        ) {
            maxOf(ordinaryDelayMs, MINIMUM_RETRY_DELAY_MS)
        } else {
            ordinaryDelayMs
        }
    }

    fun isSocketPressureFailure(failure: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var cursor: Throwable? = failure
        while (cursor != null && seen.add(cursor)) {
            if (cursor is java.net.SocketException ||
                cursor is java.net.SocketTimeoutException ||
                cursor.javaClass.simpleName == "StreamResetException"
            ) return true
            cursor = cursor.cause
        }
        return false
    }
}

/** Pure admission seam for the single page-owned recovery proof. */
internal object NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy {
    /**
     * A header-deadline cancellation can surface from OkHttp as plain `IOException("Canceled")`.
     * The shared fence is stronger evidence than that lossy exception text, but only for the exact
     * page recorded by the current host-GPU/direct-Wi-Fi recovery profile.
     */
    fun pressureObserved(
        failure: Throwable,
        observationEligible: Boolean,
        fenceRequiresDirectH1: Boolean,
    ): Boolean = observationEligible &&
        (fenceRequiresDirectH1 ||
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.isSocketPressureFailure(failure))

    fun shouldClaim(
        pressureObserved: Boolean,
        observationEligible: Boolean,
        fenceRequiresDirectH1: Boolean,
        ownerExists: Boolean,
        mode: NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode,
    ): Boolean = pressureObserved && observationEligible && fenceRequiresDirectH1 &&
        !ownerExists && mode == NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING

    fun selectLane(
        preferredLaneIndex: Int,
        activeLanes: BooleanArray,
        adoptionLanes: BooleanArray,
        healthyActiveCeiling: Int,
    ): Int {
        require(activeLanes.size == adoptionLanes.size)
        require(preferredLaneIndex in activeLanes.indices)
        require(healthyActiveCeiling > 0)
        if (activeLanes.count { it } >= healthyActiveCeiling) return -1
        fun free(index: Int) = !activeLanes[index] && !adoptionLanes[index]
        if (free(preferredLaneIndex)) return preferredLaneIndex
        return activeLanes.indices.firstOrNull(::free) ?: -1
    }
}

/**
 * Monotonic three-signal gate for expanding a direct-Wi-Fi adjacent webtoon past p0..p3.
 * Non-webtoon adjacent sessions opt out of the drawable signal and retain their old behavior.
 */
internal class NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
    predecessorAlreadyComplete: Boolean,
    requireViewportActual: Boolean,
    requireDrawableRunwayCommit: Boolean,
) {
    private var predecessorComplete = predecessorAlreadyComplete
    private var viewportActual = !requireViewportActual
    private var drawableRunwayCommitted = !requireDrawableRunwayCommit
    private var drawableProofIncludesCompleteBodyCohort = false
    private var releaseClaimed = false

    fun markPredecessorComplete() {
        predecessorComplete = true
    }

    fun markViewportActual() {
        viewportActual = true
    }

    fun markDrawableRunwayCommitted() {
        drawableRunwayCommitted = true
    }

    /**
     * ReaderSession emits this only after every manifest-bound runway source has produced a real
     * drawable at least once. That monotonic proof remains valid after the source actor releases an
     * already-consumed encoded body, so it also proves the bounded body cohort completed.
     */
    fun markExternallyProvenBodyCohortComplete() {
        drawableProofIncludesCompleteBodyCohort = true
    }

    fun tryClaimRelease(runwayBodiesComplete: Boolean): Boolean {
        if (releaseClaimed || !predecessorComplete || !viewportActual ||
            !drawableRunwayCommitted ||
            (!runwayBodiesComplete && !drawableProofIncludesCompleteBodyCohort)
        ) return false
        releaseClaimed = true
        return true
    }
}

/** Format-aware governor for an exact adjacent suffix. */
internal object NtkAdjacentBulkReleasePolicy {
    /**
     * Once the adjacent chapter owns the real viewport, its protected p0..p4 opening wave has
     * already crossed EOF and committed a drawable runway.  Keeping the suffix at the opening
     * two-transfer host-emulator limit made ordinary 12-16 page manhwa arrive in five-second
     * pairs while the user was already blocked on those exact pages.  Four background body reads
     * overlap the independent CDN pools without reopening the five-body pre-viewport wave; disk,
     * decode and native publication remain independently paced by their existing single-owner
     * gates.
     */
    internal const val HOST_GPU_MANHWA_RELEASED_BODY_TRANSFERS = 4

    fun requiresActualViewportAndDrawableRunway(
        hostGpuEmulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetch: Boolean,
        episodePath: String,
    ): Boolean {
        if (!adjacentPrefetch || !directWifiTransport || cellularResilientTransport) return false
        // A webtoon body can itself be a very tall surface, so every runtime retains its existing
        // viewport+drawable proof. On a host-GPU emulator, large PNG/JPEG pages under /manhwa/
        // have the same CPU, disk and NativeAlloc pressure; releasing their complete suffix from
        // the predecessor's p0-p4 completion alone reopens offscreen work during the fling.
        return episodePath.startsWith("/webtoon/") ||
            (hostGpuEmulatorRuntime && episodePath.startsWith("/manhwa/"))
    }

    fun releasedPhysicalLaneCount(
        proposedLaneCount: Int,
        hostGpuEmulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetchReleased: Boolean,
        episodePath: String,
    ): Int {
        if (proposedLaneCount <= 0) return proposedLaneCount
        val boundedHostManhwa = hostGpuEmulatorRuntime && directWifiTransport &&
            !cellularResilientTransport && adjacentPrefetchReleased &&
            episodePath.startsWith("/manhwa/")
        return if (boundedHostManhwa) {
            minOf(proposedLaneCount, HOST_GPU_MANHWA_RELEASED_BODY_TRANSFERS)
        } else {
            proposedLaneCount
        }
    }
}

/** Immutable p0..p3 authorization for one post-predecessor adjacent-webtoon Range group. */
internal class NtkDirectWifiAdjacentWebtoonRunwayTailTag internal constructor(
    val normalizedEpisodePath: String,
    val manifestDigest: String,
    val discoveryGeneration: Long,
    val pageIndex: Int,
    val pageCount: Int,
    val canonicalAssetDigest: String,
    private val predecessorGate: NtkDirectWifiAdjacentWebtoonPredecessorGate,
    private val permitGate: NtkDirectWifiShortWebtoonTailPermitGate,
) {
    init {
        require(normalizedEpisodePath.startsWith("/webtoon/"))
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) ==
            normalizedEpisodePath)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(discoveryGeneration > 0L)
        require(pageCount > 0)
        require(pageIndex in 0 until minOf(
            pageCount,
            NtkDirectWifiAdjacentWebtoonRunwayTailProfile.RUNWAY_PAGE_COUNT,
        ))
        require(NtkStripDigests.isSha256(canonicalAssetDigest))
    }

    // Fixed cold runs showed the four-way p0 suffix was gated by its slowest H1 socket and was
    // slower on average than one measured 65/35 suffix. Preserve one exact, disjoint hedge for
    // every runway body; the global gate still bounds different logical bodies process-wide.
    val maximumExtraTailRequests: Int = 1
    private val extraTailClaimed = AtomicBoolean(false)

    fun isPredecessorComplete(): Boolean = predecessorGate.isComplete()

    fun tryAcquireExtraTail(cancelled: AtomicBoolean):
        NtkDirectWifiShortWebtoonTailPermitGate.Lease? =
        tryAcquireExtraTails(1, cancelled)

    fun tryAcquireExtraTails(
        requestCount: Int,
        cancelled: AtomicBoolean,
    ): NtkDirectWifiShortWebtoonTailPermitGate.Lease? {
        // A pre-completion sample must remain retryable: do not burn this request's group claim.
        if (requestCount !in 1..maximumExtraTailRequests ||
            !predecessorGate.isComplete() || cancelled.get() ||
            !extraTailClaimed.compareAndSet(false, true)
        ) return null
        val lease = permitGate.tryAcquire(requestCount)
        if (lease == null) {
            extraTailClaimed.set(false)
            return null
        }
        if (cancelled.get()) {
            lease.close()
            return null
        }
        return lease
    }
}

/**
 * Frozen role for the next episode's initial runway. It is intentionally independent of the
 * current-short profile: adjacent p0 is eligible, long episodes are eligible, and p4+ never is.
 */
internal class NtkDirectWifiAdjacentWebtoonRunwayTailProfile private constructor(
    internal val normalizedEpisodePath: String,
    internal val manifestDigest: String,
    internal val discoveryGeneration: Long,
    internal val pageCount: Int,
    private val predecessorGate: NtkDirectWifiAdjacentWebtoonPredecessorGate,
    private val permitGate: NtkDirectWifiShortWebtoonTailPermitGate,
) {
    companion object {
        const val RUNWAY_PAGE_COUNT = 4
        const val GLOBAL_MAX_CONCURRENT_EXTRA_TAILS = 4

        private val globalPermitGate = NtkDirectWifiShortWebtoonTailPermitGate(
            GLOBAL_MAX_CONCURRENT_EXTRA_TAILS,
        )

        fun freeze(
            episodePath: String,
            manifestDigest: String,
            discoveryGeneration: Long,
            pageCount: Int,
            rollingAdmission: Boolean,
            directWifiTransport: Boolean,
            cellularResilientTransport: Boolean,
            adjacentPrefetch: Boolean,
            predecessorGate: NtkDirectWifiAdjacentWebtoonPredecessorGate =
                NtkDirectWifiAdjacentWebtoonPredecessorGate(),
            permitGate: NtkDirectWifiShortWebtoonTailPermitGate = globalPermitGate,
        ): NtkDirectWifiAdjacentWebtoonRunwayTailProfile? {
            if (!rollingAdmission || !directWifiTransport || cellularResilientTransport ||
                !adjacentPrefetch || !episodePath.startsWith("/webtoon/") || pageCount <= 0 ||
                discoveryGeneration <= 0L ||
                NtkStripDigests.normalizeEpisodePath(episodePath) != episodePath ||
                !NtkStripDigests.isSha256(manifestDigest)
            ) return null
            return NtkDirectWifiAdjacentWebtoonRunwayTailProfile(
                episodePath,
                manifestDigest,
                discoveryGeneration,
                pageCount,
                predecessorGate,
                permitGate,
            )
        }
    }

    fun markPredecessorComplete() {
        predecessorGate.markComplete()
    }

    fun tagForPage(
        pageIndex: Int,
        canonicalAssetDigest: String,
    ): NtkDirectWifiAdjacentWebtoonRunwayTailTag? {
        if (pageIndex !in 0 until minOf(pageCount, RUNWAY_PAGE_COUNT) ||
            !NtkStripDigests.isSha256(canonicalAssetDigest)
        ) return null
        return NtkDirectWifiAdjacentWebtoonRunwayTailTag(
            normalizedEpisodePath,
            manifestDigest,
            discoveryGeneration,
            pageIndex,
            pageCount,
            canonicalAssetDigest,
            predecessorGate,
            permitGate,
        )
    }

}

private fun prestartedStrictLane(
    name: String,
    androidThreadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
    onThreadStarted: (() -> Unit)? = null,
    prestart: Boolean = true,
    retireWhenIdle: Boolean = false,
): ThreadPoolExecutor {
    val executor = ThreadPoolExecutor(
        1,
        1,
        STRICT_LANE_IDLE_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
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
        // A source can have one independently ordered lane per canonical page. Prestarting keeps
        // cold first-pixel latency deterministic, but completed page/route lanes must not retain
        // 100+ thread stacks for the rest of a continuous-reader session. The actor is deliberately
        // excluded: its exact Thread identity is the mutable-state ownership invariant.
        if (retireWhenIdle) executor.allowCoreThreadTimeOut(true)
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
private const val STRICT_LANE_IDLE_KEEP_ALIVE_SECONDS = 5L
/**
 * Blocking image-body readers need enough streams to fill the production 120-operation identity
 * ceiling. The cold cohort leaders remain bounded to one real image body per origin/pool; only
 * after the entry body has crossed the actor boundary may the complete finite forward workset use
 * the whole ring. This keeps first-image priority while removing page-count tail waves.
 */
internal const val NTK_STRICT_PHYSICAL_WORKER_LANES = 120
internal const val NTK_DIRECT_WIFI_ADJACENT_PHYSICAL_WORKER_LANES = 5
internal const val NTK_DIRECT_WIFI_ADJACENT_ROUTE_PREPARATION_LANES = 2

/**
 * Process-wide bounded workers for consecutive direct-Wi-Fi adjacent episodes.
 *
 * Each episode still owns its actor, immutable manifest and per-lane active-work ledger. The
 * blocking physical/route workers are execution resources rather than episode state, however.
 * Giving every prefetched episode another 5+2 Java threads made a continuous reader accumulate
 * several complete rings while the preceding HTTP bodies drained. This shared ring preserves the
 * same global concurrency and lane-index admission but makes it independent of chapter count.
 */
private object NtkSharedDirectWifiAdjacentExecutors {
    private fun pool(name: String, parallelism: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            parallelism,
            parallelism,
            STRICT_LANE_IDLE_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { runnable ->
                Thread({
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    runnable.run()
                }, name).apply { priority = Thread.NORM_PRIORITY }
            },
        ).apply {
            // Threads are materialized only by real work and disappear after the same idle grace
            // as the former per-episode workers. The executor identity itself remains reusable.
            allowCoreThreadTimeOut(true)
        }

    private val physical = pool(
        "ntk-shared-adjacent-source",
        NTK_DIRECT_WIFI_ADJACENT_PHYSICAL_WORKER_LANES,
    )
    private val route = pool(
        "ntk-shared-adjacent-route",
        NTK_DIRECT_WIFI_ADJACENT_ROUTE_PREPARATION_LANES,
    )

    fun physicalViews(count: Int): Array<ExecutorService> =
        Array(count) { SharedExecutorView(physical) }

    fun routeViews(count: Int): Array<ExecutorService> =
        Array(count) { SharedExecutorView(route) }

    /** Session retirement cancels its tasks but must not close a process-shared worker ring. */
    private class SharedExecutorView(
        private val delegate: ExecutorService,
    ) : AbstractExecutorService() {
        override fun execute(command: Runnable) = delegate.execute(command)
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}

/** Frozen topology decision for a finite direct-Wi-Fi adjacent episode. */
internal object NtkDirectWifiAdjacentExecutionTopology {
    fun shouldDeferBootstrap(
        episodePath: String,
        rollingAdmission: Boolean,
        adjacentGrant: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
    ): Boolean = rollingAdmission && adjacentGrant && directWifiTransport &&
        !cellularResilientTransport &&
        (episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))

    fun physicalLaneCount(profileActive: Boolean, ordinaryCount: Int): Int {
        require(ordinaryCount >= 0)
        return if (profileActive) {
            minOf(ordinaryCount, NTK_DIRECT_WIFI_ADJACENT_PHYSICAL_WORKER_LANES)
        } else {
            ordinaryCount
        }
    }

    fun routeLaneCount(profileActive: Boolean, ordinaryCount: Int): Int {
        require(ordinaryCount >= 0)
        return if (profileActive) {
            minOf(ordinaryCount, NTK_DIRECT_WIFI_ADJACENT_ROUTE_PREPARATION_LANES)
        } else {
            ordinaryCount
        }
    }
}

/**
 * Defers construction of blocking worker lanes for every rolling direct-Wi-Fi episode.
 *
 * Discovery overlaps the document/API exchange and does not yet know the immutable body's
 * actual missing count. Eagerly prestarting the generic 120+8 ring here made a current webtoon
 * create 129 runnable Java threads while the main thread was still receiving touch input. The
 * session adoption point already has the sealed manifest and exact missing-body count, so it can
 * create the same maximum-capability lane identities lazily without sacrificing later expansion.
 * Adjacent episodes additionally share their smaller process-wide ring; current episodes retain
 * episode-owned workers and therefore preserve request cancellation and ownership semantics.
 */
internal object NtkDirectWifiRollingBootstrapTopology {
    private const val HOST_GPU_ROUTE_PREPARATION_LANES = 2

    fun shouldDeferBootstrap(
        episodePath: String,
        rollingAdmission: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
    ): Boolean = rollingAdmission && directWifiTransport && !cellularResilientTransport &&
        (episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))

    fun routeLaneCount(hostGpuProfileActive: Boolean, ordinaryCount: Int): Int {
        require(ordinaryCount >= 0)
        return if (hostGpuProfileActive) {
            minOf(ordinaryCount, HOST_GPU_ROUTE_PREPARATION_LANES)
        } else {
            ordinaryCount
        }
    }
}

/**
 * An adjacent direct-Wi-Fi webtoon can physically admit only p0..p3 before its drawable runway
 * commits. Building the generic 120-body/8-route executor ring for that four-body session leaves
 * more than a hundred idle Java threads alive during the physical fling and makes InputManager,
 * SurfaceFlinger and the renderer compete for the emulator's four CPUs. Size only this immutable
 * adjacent profile from its already-established admission bound. Current direct-Wi-Fi episodes
 * keep their full sealed-manifest topology but materialize its workers only when real work uses a
 * lane; carrier/SNI and ordinary non-rolling sources keep the eager topology.
 */
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
                retireWhenIdle = true,
            )
        }
        repeat(routePreparationLaneCount) { lane ->
            created += prestartedStrictLane(
                "ntk-strict-route-prepare-$lane",
                androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                onThreadStarted = onThreadStarted,
                retireWhenIdle = true,
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
    private val shareDeferredWorkerLanes: Boolean = false,
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

    init {
        require(!shareDeferredWorkerLanes || deferWorkerLanes) {
            "Shared strict workers require deferred lane construction"
        }
    }

    fun adopt(
        requiredPhysicalLanes: Int = minOf(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NTK_STRICT_PHYSICAL_WORKER_LANES,
        ),
        requiredRoutePreparationLanes: Int = NTK_STRICT_ROUTE_PREPARATION_LANES,
        // Adjacent ownership is finalized only when the immutable manifest is installed, which
        // can be later than bootstrap construction. Let that final role select shared workers.
        shareWorkerLanes: Boolean = shareDeferredWorkerLanes,
    ): Engines =
        synchronized(lock) {
        check(state == State.READY) { "Strict source bootstrap is not single-use" }
        require(requiredPhysicalLanes in 0..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(requiredRoutePreparationLanes in 0..NTK_STRICT_ROUTE_PREPARATION_LANES)
        require(!shareWorkerLanes || deferWorkerLanes)
        if (deferWorkerLanes) {
            check(physicalLanes.isEmpty() && routePreparationLanes.isEmpty())
            if (shareWorkerLanes) {
                physicalLanes =
                    NtkSharedDirectWifiAdjacentExecutors.physicalViews(requiredPhysicalLanes)
                routePreparationLanes =
                    NtkSharedDirectWifiAdjacentExecutors.routeViews(requiredRoutePreparationLanes)
            } else {
                val createdPhysical = ArrayList<ExecutorService>(requiredPhysicalLanes)
                val createdRoute = ArrayList<ExecutorService>(requiredRoutePreparationLanes)
                try {
                    repeat(requiredPhysicalLanes) { lane ->
                        createdPhysical += prestartedStrictLane(
                            "ntk-strict-source-lane-$lane",
                            androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                            onThreadStarted = { startedThreads.incrementAndGet() },
                            prestart = false,
                            retireWhenIdle = true,
                        )
                    }
                    repeat(requiredRoutePreparationLanes) { lane ->
                        createdRoute += prestartedStrictLane(
                            "ntk-strict-route-prepare-$lane",
                            androidThreadPriority = Process.THREAD_PRIORITY_BACKGROUND,
                            onThreadStarted = { startedThreads.incrementAndGet() },
                            retireWhenIdle = true,
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
        currentRouteBucket: String?,
        preferredPageIndexes: Set<Int> = emptySet(),
    ): PrimarySelection? {
        if (candidates.isEmpty()) return null
        val highestBand = candidates.maxOf(Candidate::urgencyBand)
        val highest = candidates.filter { it.urgencyBand == highestBand }
        // A healthy post-anchor webtoon wave should open every immutable cold pool before a
        // follower monopolizes an already-warm lane. Preserve STAGE/URGENT viewport ordering and
        // apply this only inside the otherwise-equal TARGET/BACKGROUND urgency band. Filtering
        // before affinity is essential: an affinity-first selector can keep choosing followers
        // from one warm pool while another pool's first real body remains at the tail.
        val preferred = if (highestBand <= 2 && preferredPageIndexes.isNotEmpty()) {
            highest.filter { it.pageIndex in preferredPageIndexes }
                .takeIf(List<Candidate>::isNotEmpty)
        } else {
            null
        }
        val preferredOrHighest = preferred ?: highest
        val affinity = currentRouteBucket?.let { route ->
            preferredOrHighest.filter { it.routeBucket == route }
                .takeIf(List<Candidate>::isNotEmpty)
        }
        val selected = (affinity ?: preferredOrHighest).minWithOrNull(
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

/** Keeps resident-render publication scoped to sources that explicitly expose that channel. */
internal object NtkStrictCachedBodyRenderPublicationPolicy {
    fun shouldPublishResidentDescriptor(
        adjacentPrefetch: Boolean,
        adjacentRenderPublication: Boolean,
    ): Boolean = adjacentPrefetch || adjacentRenderPublication
}

/** Defines the immutable, forward-only first quarantine wave. */
internal object NtkStrictInitialWavePolicy {
    // Must match CustomHttpClient's established and direct-Wi-Fi webtoon pool counts. A stale
    // value leaves physical pools without cohort leaders and serializes their first real bodies.
    private const val WEBTOON_CONNECTION_SHARDS = 8
    private const val DIRECT_WIFI_WEBTOON_CONNECTION_SHARDS = 24
    private const val MANHWA_CONNECTION_SHARDS = 24
    // Three independent Wi-Fi leaders keep the entry route resilient to a slow/dead CDN pool.
    // A single leader was measured to stall for its full 10-second retry window.
    private const val WEBTOON_WIFI_ANCHOR_GATE_OPERATIONS = 3
    // A short current direct-Wi-Fi chapter gives page zero one body-wide head start. The source
    // actor opens the complete current-body ring immediately after that exact body reaches EOF,
    // so this protects first-visible bandwidth without serializing the remaining chapter.
    // Adjacent work has its separate exact four-body contract below.
    private const val WEBTOON_DIRECT_WIFI_SHORT_CURRENT_ANCHOR_GATE_OPERATIONS = 1
    // The click-time H3 HEAD now warms one opening engine, and p0..p4 deliberately multiplex that
    // same connection. A one-body gate made the four successors repay another 1.0-1.5 second body
    // wave after p0 even though all five are required by the first physical gesture. Open exactly
    // that visible prefix together. This does not re-enable the failed five-connection H2 wave:
    // an unavailable H3 path still falls back sequentially through the bounded transport policy.
    private const val WEBTOON_HOST_GPU_CURRENT_ANCHOR_GATE_OPERATIONS = 5
    // A short current episode can contain a handful of unusually tall JPEGs. Letting three of
    // those cold bodies divide the opening window made the exact resume page miss the first-frame
    // SLA even though decode/presentation took only one frame. Give episodes that still fit in the
    // bounded post-anchor ring an anchor-only opening; the remaining bodies open immediately at
    // the anchor EOF. Longer scenes retain the measured three-body entry wave.
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
    internal const val HOST_GPU_ADJACENT_INITIAL_RUNWAY_BODIES = 5
    // Keep p0..p4 admitted, but do not make all five bodies compete with physical input on the
    // host-GPU emulator. Exact discovery now starts as soon as the predecessor is complete and p0
    // keeps exclusive wire ownership through EOF. Two rolling followers then finish p1..p4 in two
    // waves during the remaining chapter without the three-way TLS/hash completion bursts measured
    // at 166-285 ms. No page or byte is dropped, and physical devices keep the qualified four-body
    // production transfer ring.
    internal const val HOST_GPU_ADJACENT_ACTIVE_BODY_TRANSFERS = 2
    private const val WIFI_ADJACENT_ANCHOR_GATE_OPERATIONS = 1

    /**
     * A current manga page is the sole body that can remove the physical forward-scroll cap.
     * Opening one cold-cohort leader per remaining page before that body reaches EOF divides the
     * same bearer among an entire short chapter. Keep p0 exclusive only for the current viewport;
     * adjacent preparation and non-foreground work retain their finite cohort topology, and the
     * actor releases the complete post-anchor ring immediately after the body publication.
     */
    fun manhwaPreAnchorGateOperations(
        cohortCount: Int,
        currentForegroundEpisode: Boolean,
    ): Int {
        require(cohortCount >= 0)
        return if (currentForegroundEpisode) minOf(1, cohortCount) else cohortCount
    }

    fun allowsCurrentManhwaPreAnchorPage(
        pageIndex: Int,
        pageCount: Int,
        initialPageIndex: Int,
        currentForegroundEpisode: Boolean,
        anchorBodyPublished: Boolean,
    ): Boolean {
        require(pageIndex in 0 until pageCount)
        require(initialPageIndex in 0 until pageCount)
        return !currentForegroundEpisode || anchorBodyPublished || pageIndex == initialPageIndex
    }

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
        directWifiCurrentEpisode: Boolean = false,
        hostGpuEmulatorRuntime: Boolean = false,
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
            // Give the boundary-visible first page the complete post-predecessor link. The
            // remaining three admitted runway bodies open immediately after p0 reaches EOF.
            WIFI_ADJACENT_ANCHOR_GATE_OPERATIONS
        } else if (
            hostGpuEmulatorRuntime && directWifiTransport && directWifiCurrentEpisode
        ) {
            // Start precisely the H3-multiplexed p0..p4 visible prefix. The post-anchor governor
            // continues through the finite p0..p8 runway without admitting the unrelated suffix.
            WEBTOON_HOST_GPU_CURRENT_ANCHOR_GATE_OPERATIONS
        } else if (
            directWifiTransport &&
            directWifiCurrentEpisode &&
            episodePageCount in
                1..NtkHostGpuEmulatorCurrentWebtoonLanePolicy.SHORT_CURRENT_MAX_EPISODE_PAGES
        ) {
            WEBTOON_DIRECT_WIFI_SHORT_CURRENT_ANCHOR_GATE_OPERATIONS
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
        adjacentPrefetchBodyTransfers: Int = MANHWA_WIFI_ADJACENT_PREFETCH_BODY_TRANSFERS,
        webtoonConnectionShardCount: Int = WEBTOON_CONNECTION_SHARDS,
    ): Int {
        require(episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
        require(physicalLaneCount >= 0)
        require(manhwaTransferLimit in 1..NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        require(webtoonPublishedBodyCount >= 0)
        require(episodePageCount >= 0)
        require(webtoonConnectionShardCount > 0)
        require(adjacentPrefetchBodyTransfers > 0)
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
                    adjacentPrefetchBodyTransfers
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
        adjacentRunwayBodyCount: Int = WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
    ): Set<Int> {
        require(pageCount > 0)
        require(initialPageIndex in 0 until pageCount)
        require(alreadyPublishedPageIndexes.all { it in 0 until pageCount })
        require(alreadyPublishedPageIndexes.size <= pageCount)
        require(adjacentRunwayBodyCount > 0)
        if (!rollingAdmission) return (0 until pageCount).toSet() - alreadyPublishedPageIndexes
        if (adjacentPrefetch) {
            val endExclusive = minOf(
                pageCount,
                initialPageIndex + adjacentRunwayBodyCount,
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
     * A click-owned adjacent body can fail before the first adjacent frame is presented. The
     * fallback for that body's exact four-page runway must not wait for a frame that itself cannot
     * be attached until the runway is contiguous. `adjacentPrefetch` is issued only for a
     * direct-WiFi, foreground-authorized exact next episode; ordinary Wi-Fi and carrier/SNI retain
     * the single-anchor fallback gate.
     */
    fun isPreBulkFallbackBodyAdmitted(
        pageIndex: Int,
        pageCount: Int,
        initialPageIndex: Int,
        directWifiTransport: Boolean,
        adjacentPrefetch: Boolean,
        foregroundDemanded: Boolean = false,
        adjacentRunwayBodyCount: Int = WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
    ): Boolean {
        require(pageCount > 0)
        require(pageIndex in 0 until pageCount)
        require(initialPageIndex in 0 until pageCount)
        require(adjacentRunwayBodyCount > 0)
        if (pageIndex == initialPageIndex) return true
        // A restored viewport can be physically anchored away from the session's immutable launch
        // index. If that click-owned body failed before the first frame, waiting for first-present
        // to open bulk fallback creates a cycle: the demanded body gates the frame that gates its
        // own retry. HARD/SOFT demand is already generation- and manifest-bound, so it is the
        // authoritative pre-frame exception; BACKGROUND pages remain behind the bulk gate.
        if (foregroundDemanded) return true
        if (!directWifiTransport || !adjacentPrefetch || pageIndex < initialPageIndex) return false
        return pageIndex < minOf(
            pageCount,
            initialPageIndex + adjacentRunwayBodyCount,
        )
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
        adjacentRunwayBodyCount: Int = WIFI_ADJACENT_INITIAL_RUNWAY_BODIES,
    ): Boolean {
        require(pageCount > 0)
        require(pageIndex in 0 until pageCount)
        require(initialPageIndex in 0 until pageCount)
        require(adjacentRunwayBodyCount > 0)
        if (forwardResume && pageIndex < initialPageIndex) return false
        if (!adjacentPrefetch) return true
        if (pageIndex < initialPageIndex) return false
        return adjacentPrefetchReleased || pageIndex < minOf(
            pageCount,
            initialPageIndex + adjacentRunwayBodyCount,
        )
    }

    fun adjacentInitialRunwayBodyCount(
        emulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetch: Boolean,
        episodePath: String,
    ): Int = if (
        emulatorRuntime && directWifiTransport && !cellularResilientTransport &&
        adjacentPrefetch &&
        (episodePath.startsWith("/webtoon/") || episodePath.startsWith("/manhwa/"))
    ) {
        HOST_GPU_ADJACENT_INITIAL_RUNWAY_BODIES
    } else {
        WIFI_ADJACENT_INITIAL_RUNWAY_BODIES
    }

    fun adjacentActiveBodyTransferCount(
        emulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetch: Boolean,
        episodePath: String,
        admittedRunwayBodyCount: Int,
    ): Int {
        require(admittedRunwayBodyCount > 0)
        return if (
            emulatorRuntime && directWifiTransport && !cellularResilientTransport &&
            adjacentPrefetch && episodePath.startsWith("/manhwa/")
        ) {
            minOf(admittedRunwayBodyCount, HOST_GPU_ADJACENT_ACTIVE_BODY_TRANSFERS)
        } else {
            admittedRunwayBodyCount
        }
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

    fun connectionPoolOperationLimit(
        preferredPhysicalWebtoonCohort: Boolean,
        physicalLaneCount: Int,
    ): Int {
        require(physicalLaneCount >= 0)
        return if (preferredPhysicalWebtoonCohort) 1 else physicalLaneCount.coerceAtLeast(1)
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
 * Gives a generation-qualified non-zero resume source sole wire ownership until its body is safe.
 * This is deliberately narrower than an arbitrary requested page: the registry has already
 * proven current foreground ownership, direct Wi-Fi and non-cellular transport before constructing
 * this session.  Starting an entire short chapter beside that anchor divides bandwidth precisely
 * when no other page can produce the requested first frame.
 */
internal object NtkForwardResumeAnchorWirePolicy {
    fun isExclusive(
        rollingAdmission: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        currentForegroundViewerGeneration: Long,
        adjacentPrefetch: Boolean,
        initialPageIndex: Int,
    ): Boolean = rollingAdmission && directWifiTransport &&
        !cellularResilientTransport && currentForegroundViewerGeneration > 0L &&
        !adjacentPrefetch && initialPageIndex > 0

    fun usableLaneCount(
        proposedLaneCount: Int,
        exclusive: Boolean,
        anchorBodyPublished: Boolean,
    ): Int = if (exclusive && !anchorBodyPublished) {
        proposedLaneCount.coerceAtMost(1)
    } else {
        proposedLaneCount
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
    private val adjacentRenderPublication: Boolean = false,
    private val adjacentPredecessorAlreadyComplete: Boolean = false,
) : Closeable {
    internal val directWifiAdjacentRunwayProfile: Boolean
        get() = directWifiTransport && !cellularResilientTransport

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

    private data class PendingExactBindingInstall(
        val token: NtkPromotionToken,
        val validity: AtomicBoolean,
        val owner: NtkStrictSourceOwnershipRegistry.Owner,
        val manifest: NtkAuthoritativeManifest,
        val snapshot: NtkPromotionSnapshot,
        val completion: CompletableFuture<Unit>,
    )

    private sealed interface PhysicalResult {
        data class Quarantined(
            val body: NtkQuarantinedBody,
            val tempLease: ReaderImageCache.NtkQuarantineFileLease,
            val predecodedOriginal: NtkStrictPredecodedOriginal? = null,
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

    private data class PromotionDeferredFailureEvidence(
        val workId: Long,
        val operationId: Long,
        val laneIndex: Int,
        val demandEpoch: Long,
        val primaryQueueDepth: Int,
        val launchedPreGeometry: Boolean,
        val physicalAttemptOrdinal: Int,
        val resolvedRoute: ReaderImageCache.NtkResolvedSourceRoute,
    )

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
        var pendingPredecodedOriginal: NtkStrictPredecodedOriginal? = null,
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
        // Quarantine and exact ownership are distinct producer ledgers. A quarantine request may
        // already be on physical retry N when promotion installs a fresh exact owner; that live
        // request (or its sealed body) is still exact producer 1, not exact retry N.
        var exactLedgerAttemptOrdinal: Int = 0,
        var promotionDeferredFailure: PromotionDeferredFailureEvidence? = null,
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
        @Volatile var physicalBodyEvidence:
            ReaderImageCache.NtkStrictPhysicalBodyEvidence? = null,
    )

    /**
     * Actor-owned single proof after translated-socket pressure. The failed page keeps ownership
     * of the one PROBING admission instead of waiting for fixed lane zero to drain and allowing an
     * unrelated page to consume the proof. Existing calls still drain and no extra body is added
     * beyond the healthy six-body ceiling.
     */
    private data class CurrentWebtoonRecoveryProofOwner(
        val pageIndex: Int,
        val preferredLaneIndex: Int,
        val expectedAttemptOrdinal: Int,
        val readyAtMs: Long,
        var activeWorkId: Long = 0L,
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
    private val hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
        Build.FINGERPRINT,
        Build.MODEL,
        Build.HARDWARE,
        Build.PRODUCT,
    )
    private val candidateSeal = NtkEpisodeManifestSeal.create(
        planBinding.episodePath,
        planBinding.discoveryGeneration,
        planBinding.normalizedOrderedCanonicalAssets
    )
    // This profile is intentionally frozen once. A later network callback must not broaden a
    // carrier/SNI or adjacent session into the direct-Wi-Fi optimization, and a transient live
    // telemetry read must not silently retarget a request to another episode generation.
    private val directWifiShortWebtoonTailProfile =
        NtkDirectWifiShortWebtoonTailProfile.freeze(
            episodePath = candidateSeal.normalizedEpisodePath,
            manifestDigest = candidateSeal.digestSha256,
            pageCount = candidateSeal.pageCount,
            rollingAdmission = rollingAdmission,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            currentForegroundViewerGeneration = currentForegroundViewerGeneration,
            adjacentPrefetch = adjacentPrefetch,
        )
    private val directWifiAdjacentWebtoonRunwayTailProfile =
        NtkDirectWifiAdjacentWebtoonRunwayTailProfile.freeze(
            episodePath = candidateSeal.normalizedEpisodePath,
            manifestDigest = candidateSeal.digestSha256,
            discoveryGeneration = planBinding.discoveryGeneration,
            pageCount = candidateSeal.pageCount,
            rollingAdmission = rollingAdmission,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            adjacentPrefetch = adjacentPrefetch,
        ).also { profile ->
            if (adjacentPredecessorAlreadyComplete) profile?.markPredecessorComplete()
        }
    private val finiteDirectWifiAdjacentExecution =
        NtkDirectWifiAdjacentExecutionTopology.shouldDeferBootstrap(
            episodePath = candidateSeal.normalizedEpisodePath,
            rollingAdmission = rollingAdmission,
            adjacentGrant = adjacentPrefetch,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
        )
    private val hostGpuDirectWifiRollingExecution = hostGpuEmulatorRuntime &&
        NtkDirectWifiRollingBootstrapTopology.shouldDeferBootstrap(
            episodePath = candidateSeal.normalizedEpisodePath,
            rollingAdmission = rollingAdmission,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
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
    private val ordinaryRequiredPhysicalLanes = minOf(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NTK_STRICT_PHYSICAL_WORKER_LANES,
            requiredFallbackBodyLanes,
        )
    private val ordinaryRequiredRoutePreparationLanes =
        if (requiredFallbackBodyLanes == 0) 0 else minOf(
            NTK_STRICT_ROUTE_PREPARATION_LANES,
            requiredFallbackBodyLanes,
        )
    private val executionEngines = executionBootstrap.adopt(
        requiredPhysicalLanes = NtkDirectWifiAdjacentExecutionTopology.physicalLaneCount(
            finiteDirectWifiAdjacentExecution,
            ordinaryRequiredPhysicalLanes,
        ),
        requiredRoutePreparationLanes = NtkDirectWifiRollingBootstrapTopology.routeLaneCount(
            hostGpuDirectWifiRollingExecution,
            NtkDirectWifiAdjacentExecutionTopology.routeLaneCount(
                finiteDirectWifiAdjacentExecution,
                ordinaryRequiredRoutePreparationLanes,
            ),
        ),
        shareWorkerLanes = finiteDirectWifiAdjacentExecution,
    )
    private val actor = executionEngines.actor
    private val physicalLanes = executionEngines.physicalLanes
    private val routePreparationLanes = executionEngines.routePreparationLanes
    private val bodyLeaseAdmissionLock = Any()
    private val closeRequested = AtomicBoolean(false)
    private val closeFinalized = AtomicBoolean(false)
    private val activeStrictAdjacentPathRetained = AtomicBoolean(false)
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
    /** Actor-confined one-shot wake for a process-wide gate currently owned by another Session. */
    private var operationAdmissionWake:
        NtkStrictSourceOwnershipRegistry.OperationAdmissionWake? = null
    /**
     * A Blocked wake may become GRANTED on a physical completion thread before its actor callback
     * reaches the queue. Only the synchronous Ready caller, or that exact callback turn, may
     * consume it; unrelated actor maintenance must not steal the one-shot reservation.
     */
    private var operationAdmissionWakeConsumable = false
    /** Promotion itself is an actor command, but it may never block that actor on a foreign gate. */
    private var pendingExactBindingInstall: PendingExactBindingInstall? = null
    private var currentWebtoonRecoveryState =
        NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.State()
    private var currentWebtoonRecoveryProofOwner: CurrentWebtoonRecoveryProofOwner? = null
    private val currentWebtoonRecoveryFence =
        NtkHostGpuEmulatorCurrentWebtoonRecoveryFence()
    private val currentWebtoonC8HealthState =
        NtkHostGpuEmulatorCurrentWebtoonC8HealthState()
    private val currentWebtoonFragmentedTlsRecoveryHealthState =
        NtkHostGpuEmulatorFragmentedTlsRecoveryHealthState()
    private val currentWebtoonQuicHealthState =
        NtkHostGpuEmulatorCurrentWebtoonQuicHealthState()
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
    private val adjacentInitialRunwayBodyCount =
        NtkStrictInitialWavePolicy.adjacentInitialRunwayBodyCount(
            emulatorRuntime = hostGpuEmulatorRuntime,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            adjacentPrefetch = adjacentPrefetch,
            episodePath = planBinding.episodePath,
        )
    private val adjacentActiveBodyTransferCount =
        NtkStrictInitialWavePolicy.adjacentActiveBodyTransferCount(
            emulatorRuntime = hostGpuEmulatorRuntime,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            adjacentPrefetch = adjacentPrefetch,
            episodePath = planBinding.episodePath,
            admittedRunwayBodyCount = adjacentInitialRunwayBodyCount,
        )
    // The direct-Wi-Fi replica call always tries the compatibility origin first. On the host
    // emulator current-resume profile the scheduler must therefore model twenty-four physical pools,
    // not the canonical manifest's three origins multiplied by twenty-four. Keeping this immutable
    // also prevents a live transport transition from retargeting an already-admitted source wave.
    private val hostGpuCurrentWebtoonPreferredPhysicalCohorts =
        hostGpuEmulatorRuntime && directWifiTransport && !cellularResilientTransport &&
            planBinding.episodePath.startsWith("/webtoon/") && rollingAdmission &&
            currentForegroundViewerGeneration > 0L && !adjacentPrefetch
    private val forwardResumeAnchorWireExclusive =
        NtkForwardResumeAnchorWirePolicy.isExclusive(
            rollingAdmission = rollingAdmission,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            currentForegroundViewerGeneration = currentForegroundViewerGeneration,
            adjacentPrefetch = adjacentPrefetch,
            initialPageIndex = initialPageIndex,
        )
    private var rollingAdmittedPages: Set<Int> =
        NtkStrictInitialWavePolicy.admittedPageIndexes(
            pages.size,
            initialPageIndex,
            rollingAdmission,
            initialExactBodies.keys + externallyOwnedPageIndexes,
            adjacentPrefetch = adjacentPrefetch,
            adjacentRunwayBodyCount = adjacentInitialRunwayBodyCount,
        )
    private var adjacentPrefetchReleased = false
    private val hostGpuAdjacentManhwaHeadInstall = hostGpuEmulatorRuntime &&
        planBinding.episodePath.startsWith("/manhwa/")
    private val requiresAdjacentHeadPixelsInstall = adjacentPrefetch && directWifiTransport &&
        !cellularResilientTransport && (
            planBinding.episodePath.startsWith("/webtoon/") ||
                hostGpuAdjacentManhwaHeadInstall
            )
    private val hostGpuEmulatorAdjacentP0Predecode =
        requiresAdjacentHeadPixelsInstall && hostGpuEmulatorRuntime &&
            planBinding.episodePath.startsWith("/webtoon/")
    private var adjacentAnchorRequestHeadersSent =
        !requiresAdjacentHeadPixelsInstall
    private var adjacentHeadPixelsInstalled = !requiresAdjacentHeadPixelsInstall
    private val requiresAdjacentViewportAndDrawableBulkRelease =
        NtkAdjacentBulkReleasePolicy.requiresActualViewportAndDrawableRunway(
            hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
            directWifiTransport = directWifiTransport && adjacentPrefetch,
            cellularResilientTransport = cellularResilientTransport,
            adjacentPrefetch = adjacentPrefetch,
            episodePath = planBinding.episodePath,
        )
    private val adjacentPrefetchReleaseGate =
        NtkDirectWifiAdjacentWebtoonSourceReleaseGate(
            predecessorAlreadyComplete = adjacentPredecessorAlreadyComplete,
            // The host renderer already has the exact p0..p3 boundary runway. Starting every
            // remaining manhwa body while the predecessor is still physically scrolling caused
            // five simultaneous PNG/network/disk completions to starve input dispatch for up to
            // 303 ms. Expand only after the actual viewport enters, just like strict webtoon.
            requireViewportActual = requiresAdjacentViewportAndDrawableBulkRelease,
            // Host-emulator manhwa already owns the exact encoded p0-p4 body cohort. Requiring
            // every runway page to be decoded before admitting the first demanded suffix page
            // creates a visible loading edge. Webtoon retains the stronger pixel proof.
            requireDrawableRunwayCommit =
                requiresAdjacentViewportAndDrawableBulkRelease &&
                    planBinding.episodePath.startsWith("/webtoon/"),
        )
    private val demandBoundedAdjacentSuffix =
        hostGpuEmulatorRuntime && adjacentPrefetch &&
            requiresAdjacentViewportAndDrawableBulkRelease
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
                    adjacentPrefetchBodyTransfers = adjacentActiveBodyTransferCount,
                )
            },
            webtoonShardCount = webtoonConnectionShardCount,
        ) { pageIndex -> coldConnectionRouteBucketForPage(pageIndex) }
    private val coldConnectionCohortLeaderSet = coldConnectionCohortLeaders.toHashSet()
    private val webtoonPreAnchorGateOperations =
        NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
            cohortCount = coldConnectionCohortLeaders.size,
            cellularResilientTransport = cellularResilientTransport,
            episodePageCount = pages.size,
            // Keep the adjacent identity separate from the current short-scene exception below.
            directWifiTransport = directWifiTransport,
            adjacentPrefetch = adjacentPrefetch,
            directWifiCurrentEpisode = directWifiTransport && !adjacentPrefetch &&
                currentForegroundViewerGeneration > 0L,
            hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
        )
    private val settledColdConnectionCohortLeaders = ConcurrentHashMap.newKeySet<Int>()
    private val coldConnectionCohortByPage = Array(pages.size) { pageIndex ->
        NtkStrictInitialWavePolicy.coldConnectionCohortKey(
            planBinding.episodePath,
            pageIndex,
            coldConnectionRouteBucketForPage(pageIndex),
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
        // The first pages are the only bodies that can remove the physical forward-scroll cap.
        // Put the complete visible runway ahead of offscreen cold-pool leaders. The old leaders-
        // first order launched pages 9/24/33/48/66 immediately after the first three bodies and
        // starved pages 3-5 on a healthy but bandwidth-limited link.
        val openingRunwayEndExclusive = minOf(
            pages.size,
            initialPageIndex + maxOf(
                webtoonPreAnchorGateOperations,
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
            ),
        )
        for (pageIndex in initialPageIndex until openingRunwayEndExclusive) {
            if (pageIndex in rollingAdmittedPages) addLast(pageIndex)
        }
        coldConnectionCohortLeaders.forEach { pageIndex ->
            if (pageIndex !in initialPageIndex until openingRunwayEndExclusive) {
                addLast(pageIndex)
            }
        }
        for (pageIndex in initialPageIndex until pages.size) {
            if (pageIndex in rollingAdmittedPages &&
                pageIndex !in coldConnectionCohortLeaderSet &&
                pageIndex !in initialPageIndex until openingRunwayEndExclusive
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
        // A restored viewport has exactly one first-frame body.  Its source index is already
        // generation-qualified by NtkForwardResumeFloorPolicy, so do not dilute it across the
        // chapter's cold connection cohorts before its first successful EOF.
        forwardResumeAnchorWireExclusive -> 1
        // The host adjacent-manhwa contract deliberately gives p0 exclusive wire ownership until
        // EOF and its renderer ACK. Requiring the wider two-lane steady-state target in the
        // synchronous opening proof contradicts that gate and retires a healthy source before p0
        // can complete. The second lane is admitted by the same actor immediately after the ACK.
        hostGpuAdjacentManhwaHeadInstall -> 1
        planBinding.episodePath.startsWith("/webtoon/") ->
            webtoonPreAnchorGateOperations
        else -> NtkStrictInitialWavePolicy.manhwaPreAnchorGateOperations(
            cohortCount = coldConnectionCohortLeaders.size,
            currentForegroundEpisode = currentForegroundViewerGeneration > 0L &&
                !adjacentPrefetch,
        )
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
    private var finalWellProvenQuicWaveLogged = false
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
    /** Actor-confined deadline that prevents a released adjacent suffix from opening in a burst. */
    private var nextReleasedAdjacentPhysicalLaunchAtMs = 0L
    /**
     * A physically entered episode may become quiet before its viewport ever demands the suffix.
     * Once set, the exact remaining table is admitted through the existing paced adjacent lanes;
     * active-motion viewport demand remains the only path that can widen before this idle edge.
     */
    private var foregroundIdleCompletionRequested = false
    private var lastPhysicalBlockedRedrivePage = -1
    private var lastPhysicalBlockedRedriveAtMs = 0L
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
            // Admission is part of the failed click-owned body's fallback transition. Publish it
            // before asking the route-preparation gate to revalidate the page. The inverse order
            // made a suffix body that failed before adjacent release fail its own admission check;
            // release then saw the page in previousAdmission, omitted it from newlyAdmitted, and
            // left the placeholder route future incomplete forever.
            if (rollingAdmission) rollingAdmittedPages = rollingAdmittedPages + pageIndex
            if (::quarantineRoutePreparations.isInitialized) {
                prepareFallbackRouteForStreamedPage(pageIndex)
            }
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
            if (adjacentPrefetch &&
                activeStrictAdjacentPathRetained.compareAndSet(false, true) &&
                !ReaderImageCache.retainActiveStrictAdjacentNtkEpisodePath(
                    planBinding.episodePath,
                    "strict_source_session_$sessionId",
                )
            ) {
                activeStrictAdjacentPathRetained.set(false)
                error("Unable to retain active strict adjacent source path")
            }
            try {
                NtkQuarantineSourceOwnershipRegistry.beginSession(planBinding, sessionId)
                planReservedAtMs = SystemClock.elapsedRealtime()
                phase = SessionPhase.Quarantining(planReservedAtMs)
                logSourceEvent(
                    "reader_strip_source_runtime_profile",
                    "hostGpu=$hostGpuEmulatorRuntime,currentWebtoon=" +
                        "$hostGpuCurrentWebtoonPreferredPhysicalCohorts," +
                        "directWifi=$directWifiTransport,rolling=$rollingAdmission," +
                        "generation=$currentForegroundViewerGeneration," +
                        "resumeAnchor=$initialPageIndex," +
                        "resumeAnchorExclusive=$forwardResumeAnchorWireExclusive",
                )
                refillLanesActor()
                check(initialWaveCount == initialQuarantineWaveTargetCount) {
                    val openingEnd = minOf(
                        pages.size,
                        initialPageIndex + initialQuarantineWaveTargetCount,
                    )
                    val openingState = (initialPageIndex until openingEnd).joinToString(";") {
                            pageIndex ->
                        val page = pages[pageIndex]
                        val poolKey = coldConnectionCohortByPage[pageIndex]
                        val activeInPool = activeWorks.count { work ->
                            work != null && coldConnectionCohortByPage[work.pageIndex] == poolKey
                        }
                        "$pageIndex(started=${page.primaryStarted}," +
                            "admitted=${pageIndex in rollingAdmittedPages}," +
                            "leader=${pageIndex in coldConnectionCohortLeaderSet}," +
                            "cohort=${isColdConnectionCohortEligible(pageIndex)}," +
                            "route=${quarantineRoutePreparations[pageIndex].isDone}," +
                            "poolActive=$activeInPool)"
                    }
                    "Initial quarantine opening wave incomplete: " +
                        "count=$initialWaveCount,target=$initialQuarantineWaveTargetCount," +
                        "active=${activeWorks.filterNotNull().joinToString { it.pageIndex.toString() }}," +
                        "opening=$openingState"
                }
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
                    if (initialWaveCount == 0) {
                        proofTimestamp
                    } else {
                        initialQuarantineWaveSubmittedAtMs
                    },
                    initialWaveCount,
                    initialWaveCount,
                    ownership.physicalCallCount,
                    ownership.duplicatePhysicalCallCount
                )
            } catch (failure: Throwable) {
                releaseActiveStrictAdjacentPath("start_failure")
                throw failure
            }
        }
    }

    private fun releaseActiveStrictAdjacentPath(reason: String) {
        if (!activeStrictAdjacentPathRetained.compareAndSet(true, false)) return
        check(
            ReaderImageCache.releaseActiveStrictAdjacentNtkEpisodePath(
                planBinding.episodePath,
                "strict_source_session_${sessionId}_$reason",
            )
        ) { "Active strict adjacent source path lease disappeared" }
    }

    private fun startQuarantineRoutePreparation() {
        check(routePreparationStarted.compareAndSet(false, true)) {
            "Quarantine route preparation already started"
        }
        // Previously the source actor resolved the Request route and calculated two SHA-256
        // identities serially for every page. Compute immutable, CPU-only material on bounded
        // worker lanes; the actor still performs every ownership admission in order. The host
        // emulator direct-Wi-Fi profile uses two lanes because eight simultaneous hash/request
        // builders descheduled input for more than half a second; its opening routes are still
        // submitted first and the remaining work overlaps the body wave.
        // A click-owned exact wave can already contain every immutable body. In that case there
        // is no source route left to resolve, and scheduling page-count hash work only steals CPU
        // from exact publication and decode on the entry deadline.
        if (initialExactBodies.size == pages.size) {
            quarantineRoutePreparations = emptyArray()
            return
        }
        val pendingPreparations =
            arrayOfNulls<CompletableFuture<QuarantineRoutePreparation>>(pages.size)
        fun schedulePreparation(pageIndex: Int) {
            val page = pages[pageIndex]
            pendingPreparations[pageIndex] = if (page.seededExactBody != null ||
                page.streamedExactBodyPending ||
                !isRoutePreparationAdmitted(pageIndex)
            ) {
                CompletableFuture()
            } else {
                createRoutePreparation(pageIndex)
            }
        }
        // The actor start proof is synchronous. Submit the finite contiguous opening window
        // before bulk route hashing so its three exact routes cannot sit behind dozens of
        // offscreen pages on the same preparation lanes. No request is admitted here; this only
        // changes the order of immutable CPU-only route materialization.
        if (hostGpuCurrentWebtoonPreferredPhysicalCohorts) {
            val openingEnd = minOf(
                pages.size,
                initialPageIndex + maxOf(
                    initialQuarantineWaveTargetCount,
                    NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                        .INITIAL_VISIBLE_RUNWAY_BODIES,
                ),
            )
            for (pageIndex in initialPageIndex until openingEnd) {
                schedulePreparation(pageIndex)
            }
        }
        for (pageIndex in pages.indices) {
            if (pendingPreparations[pageIndex] == null) schedulePreparation(pageIndex)
        }
        quarantineRoutePreparations = Array(pages.size) { pageIndex ->
            checkNotNull(pendingPreparations[pageIndex])
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
        waitForStreamedSourceRoute: Boolean = true,
    ): CompletableFuture<QuarantineRoutePreparation> {
        val page = pages[pageIndex]
        // Route resolution is immutable CPU-only work. It may wait for the bounded extension
        // probe, but never for a frame; promotion must be able to publish the streamed anchor
        // before bulk physical admission opens.
        val sourceRouteReady = if (waitForStreamedSourceRoute) {
            streamedExactBodies?.sourceRoutePreparationReady
                ?: CompletableFuture.completedFuture(Unit)
        } else {
            // A completed click-owned future has already ceded this exact page to the strict
            // source generation. candidateSeal + planBinding now provide the complete immutable
            // route identity; waiting for the remaining click stream here makes a failed p1-p4
            // body wait for the clean adjacent frame that the same body is required to produce.
            CompletableFuture.completedFuture(Unit)
        }
        return sourceRouteReady.thenApplyAsync({
            val resolvedRoute = ReaderImageCache.resolveStrictSourceRoute(
                manga,
                candidateSeal,
                page.pageIndex,
                page.canonicalAsset,
            )
            val canonicalAssetDigest = NtkStripDigests.canonicalAssetDigestSha256(
                page.canonicalAsset,
            )
            val frozenProfile = directWifiShortWebtoonTailProfile
            val tailTag = frozenProfile?.tagForPage(
                page.pageIndex,
                canonicalAssetDigest,
            )
            val adjacentProfile = directWifiAdjacentWebtoonRunwayTailProfile
            val adjacentTailTag = adjacentProfile?.tagForPage(
                page.pageIndex,
                canonicalAssetDigest,
            )
            val route = if (frozenProfile == null && adjacentTailTag == null) {
                resolvedRoute
            } else {
                resolvedRoute.copy(
                    requestTemplate = resolvedRoute.requestTemplate.newBuilder()
                        .apply {
                            frozenProfile?.let {
                                tag(NtkDirectWifiShortWebtoonTailProfile::class.java, it)
                            }
                            tailTag?.let {
                                tag(NtkDirectWifiShortWebtoonTailTag::class.java, it)
                            }
                            if (adjacentProfile != null && adjacentTailTag != null) {
                                tag(
                                    NtkDirectWifiAdjacentWebtoonRunwayTailProfile::class.java,
                                    adjacentProfile,
                                )
                                tag(
                                    NtkDirectWifiAdjacentWebtoonRunwayTailTag::class.java,
                                    adjacentTailTag,
                                )
                            }
                        }
                        .build(),
                )
            }
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
        val preparation = createRoutePreparation(
            pageIndex,
            waitForStreamedSourceRoute = false,
        )
        quarantineRoutePreparations[pageIndex] = preparation
        preparation.whenComplete { _, _ -> scheduleRoutePreparationRefill() }
    }

    private fun isRoutePreparationAdmitted(pageIndex: Int): Boolean {
        if (demandBoundedAdjacentSuffix && adjacentPrefetchReleased) {
            return pageIndex in rollingAdmittedPages
        }
        return NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
            pageIndex = pageIndex,
            pageCount = pages.size,
            initialPageIndex = initialPageIndex,
            adjacentPrefetch = adjacentPrefetch,
            adjacentPrefetchReleased = adjacentPrefetchReleased,
            forwardResume = rollingAdmission && initialPageIndex > 0,
            adjacentRunwayBodyCount = adjacentInitialRunwayBodyCount,
        )
    }

    private fun startReleasedAdjacentRoutePreparationsActor(pageIndexes: Set<Int>) {
        assertActorThread()
        if (!adjacentPrefetch || !adjacentPrefetchReleased ||
            !::quarantineRoutePreparations.isInitialized ||
            quarantineRoutePreparations.isEmpty()
        ) return
        pageIndexes.sorted().forEach { pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@forEach
            if (!isRoutePreparationAdmitted(pageIndex) ||
                page.seededExactBody != null ||
                page.streamedExactBodyPending ||
                quarantineRoutePreparations[pageIndex].isDone
            ) return@forEach
            val wasInitiallyAdmitted =
                NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                    pageIndex = pageIndex,
                    pageCount = pages.size,
                    initialPageIndex = initialPageIndex,
                    adjacentPrefetch = true,
                    adjacentPrefetchReleased = false,
                    adjacentRunwayBodyCount = adjacentInitialRunwayBodyCount,
                )
            if (wasInitiallyAdmitted) return@forEach
            val preparation = createRoutePreparation(
                pageIndex,
                waitForStreamedSourceRoute =
                    NtkStrictLateRoutePreparationPolicy.shouldWaitForStreamedSourceRoute(
                        exactManifestOwned = phase is SessionPhase.ExactOpen,
                        streamedExactBodyPending = page.streamedExactBodyPending,
                    ),
            )
            quarantineRoutePreparations[pageIndex] = preparation
            preparation.whenComplete { _, _ -> scheduleRoutePreparationRefill() }
        }
    }

    /**
     * A forward-resume session deliberately leaves prefix route futures incomplete. Once a real
     * reverse gesture lowers the immutable source floor, replace only those never-started
     * placeholders with the same exact route preparation used by the initial suffix. No Call is
     * created here; the actor/ownership ledger still admits each body exactly once.
     */
    private fun startRollingLateRoutePreparationsActor(pageIndexes: Set<Int>) {
        assertActorThread()
        if (!rollingAdmission || adjacentPrefetch ||
            !::quarantineRoutePreparations.isInitialized ||
            quarantineRoutePreparations.isEmpty()
        ) return
        for (pageIndex in pageIndexes.sorted()) {
            val page = pages.getOrNull(pageIndex) ?: continue
            if (page.seededExactBody != null || page.streamedExactBodyPending ||
                page.primaryStarted || page.terminalEvent != null ||
                quarantineRoutePreparations[pageIndex].isDone
            ) continue
            val preparation = createRoutePreparation(
                pageIndex,
                waitForStreamedSourceRoute =
                    NtkStrictLateRoutePreparationPolicy.shouldWaitForStreamedSourceRoute(
                        exactManifestOwned = phase is SessionPhase.ExactOpen,
                        streamedExactBodyPending = page.streamedExactBodyPending,
                    ),
            )
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
    ): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        executeActor(
            onRejected = {
                completion.completeExceptionally(
                    RejectedExecutionException("Strict source actor callback admissions closed"),
                )
            },
        ) {
            if (pendingExactBindingInstall != null) {
                completion.completeExceptionally(
                    IllegalStateException("Exact binding install already pending"),
                )
                return@executeActor
            }
            pendingExactBindingInstall = PendingExactBindingInstall(
                token,
                validity,
                owner,
                manifest,
                snapshot,
                completion,
            )
            drivePendingExactBindingInstallActor()
        }
        return completion
    }

    private fun drivePendingExactBindingInstallActor() {
        assertActorThread()
        val pending = pendingExactBindingInstall ?: return
        try {
            check(!closeRequested.get()) { "Exact binding install closed while awaiting admission" }
            val needsOperationAdmission = activeWorks.any { work ->
                work != null && work.mode == WorkMode.QUARANTINE && work.exactContext == null
            } || pending.snapshot.activePageIndexes.any { pageIndex ->
                val page = pages[pageIndex]
                page.promotionDeferredFailure != null &&
                    isPromotionDeferredRetryPageActor(page)
            }
            if (needsOperationAdmission &&
                !canBeginOrAwaitOperationAdmissionActor(pending.owner)
            ) {
                return
            }
            installExactBindingActor(
                pending.token,
                pending.validity,
                pending.owner,
                pending.manifest,
                pending.snapshot,
            )
            if (pendingExactBindingInstall === pending) pendingExactBindingInstall = null
            pending.completion.complete(Unit)
        } catch (failure: Throwable) {
            if (pendingExactBindingInstall === pending) pendingExactBindingInstall = null
            pending.completion.completeExceptionally(failure)
            if (!closeRequested.get()) failSessionActor(failure)
        }
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
        val deferredFailureContexts =
            ArrayList<Pair<PageState, ReaderImageCache.NtkStrictCallContext>>()
        try {
            for (work in activeWorks.filterNotNull()) {
                if (work.mode != WorkMode.QUARANTINE || work.exactContext != null) continue
                preparedContexts += work to beginNextExactOperationActor(work, manifest)
            }
            // A quarantine Call may fail recoverably while exact install is parked behind a
            // foreign Session. Preserve that already-consumed physical attempt in the new exact
            // ledger: otherwise its next physical attempt has no exact failed predecessor and is
            // rejected. Admit every synthetic predecessor before completing any of them: the
            // last completion is allowed to hand the process-wide gate to another Session.
            val promotionLanesOwned = BooleanArray(activeWorks.size) { laneIndex ->
                activeWorks[laneIndex] != null
            }
            for (pageIndex in snapshot.activePageIndexes) {
                val page = pages[pageIndex]
                val deferred = page.promotionDeferredFailure
                    ?.takeIf { isPromotionDeferredRetryPageActor(page) }
                    ?: continue
                val failedPromotionLane = deferred.laneIndex
                check(failedPromotionLane in promotionLanesOwned.indices &&
                    !promotionLanesOwned[failedPromotionLane]
                ) { "Deferred promotion failure lost its retired physical lane" }
                promotionLanesOwned[failedPromotionLane] = true
                val failedPromotionWork = PrimaryWork(
                    deferred.workId,
                    deferred.operationId,
                    laneIndex = failedPromotionLane,
                    pageIndex = pageIndex,
                    demandEpoch = deferred.demandEpoch,
                    primaryQueueDepth = deferred.primaryQueueDepth,
                    launchedPreGeometry = deferred.launchedPreGeometry,
                    mode = WorkMode.QUARANTINE,
                    cancellation = ReaderImageCache.Cancellation(),
                    attemptOrdinal = deferred.physicalAttemptOrdinal,
                    resolvedRoute = deferred.resolvedRoute,
                )
                deferredFailureContexts += page to
                    beginNextExactOperationActor(failedPromotionWork, manifest)
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
            deferredFailureContexts.forEach { (page, context) ->
                context.operationLease.complete(
                    protocol = "promotion-quarantine-failure",
                    succeeded = false,
                )
                page.promotionDeferredFailure = null
            }
        } catch (failure: Throwable) {
            preparedContexts.forEach { (_, context) -> context.operationLease.complete() }
            deferredFailureContexts.forEach { (_, context) -> context.operationLease.complete() }
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
            // recordSubmissionActor(), preparePromotionActor(), and this activation all execute
            // on the same source actor. A fast foreground reopen can therefore submit its entire
            // real quarantine wave and seal it within one elapsedRealtime millisecond. The actor
            // order is the proof; wall-clock resolution must not turn that valid ordering into a
            // nondeterministic source-promotion failure.
            sameMillisecondActorOrderProven = true,
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
            adjacentPrefetchReleaseGate.markPredecessorComplete()
            directWifiAdjacentWebtoonRunwayTailProfile?.markPredecessorComplete()
            maybeReleaseAdjacentPrefetchAfterRunwayActor("predecessor_complete")
        }
    }

    fun onAdjacentViewportActivated(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode)) return@executeActor
            adjacentPrefetchReleaseGate.markViewportActual()
            streamedExactBodies?.onAdjacentViewportActivated()
            maybeReleaseAdjacentPrefetchAfterRunwayActor("viewport_activated")
        }
    }

    fun onAdjacentHeadPixelsInstalled(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            val acceptedEpisode = acceptsEpisode(episode)
            val alreadyInstalled = adjacentHeadPixelsInstalled
            logSourceEvent(
                "reader_strip_adjacent_head_pixels_event",
                "episode=${episode.value},bound=${boundEpisode?.value ?: 0L}," +
                    "accepted=$acceptedEpisode,already=$alreadyInstalled," +
                    "phase=${phase.javaClass.simpleName},active=" +
                    activeWorks.filterNotNull().joinToString("|") { it.pageIndex.toString() } +
                    ",pending=${preGeometryPendingPages.joinToString("|")}",
            )
            if (!acceptedEpisode || alreadyInstalled) return@executeActor
            adjacentHeadPixelsInstalled = true
            refillLanesActor()
            logSourceEvent(
                "reader_strip_adjacent_head_pixels_refilled",
                "active=${activeWorks.filterNotNull().joinToString("|") { it.pageIndex.toString() }}," +
                    "pending=${preGeometryPendingPages.joinToString("|")}," +
                    "published=$bodyPublishedCount,initialWave=$initialWaveCount/" +
                    initialQuarantineWaveTargetCount,
            )
        }
    }

    fun onAdjacentDrawableRunwayCommitted(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode)) return@executeActor
            // The caller's exact generation/manifest ledger has already observed every required
            // runway source as drawable. Keep that success monotonic instead of re-deriving it
            // from encoded bodies that may have been consumed and released before this actor turn.
            adjacentPrefetchReleaseGate.markDrawableRunwayCommitted()
            adjacentPrefetchReleaseGate.markExternallyProvenBodyCohortComplete()
            maybeReleaseAdjacentPrefetchAfterRunwayActor("drawable_runway_committed")
        }
    }

    fun onForegroundIdleCompletionRequested(episode: NtkEpisodeToken) {
        if (closeRequested.get()) return
        executeActor {
            if (!acceptsEpisode(episode) || !adjacentPrefetch ||
                !NtkReaderTransferPacer.isPhysicalForegroundEpisode(
                    candidateSeal.normalizedEpisodePath,
                ) || NtkReaderTransferPacer.isPhysicalMotionActive()
            ) return@executeActor
            val newlyAdmitted = pages.indices
                .filterTo(LinkedHashSet()) { pageIndex ->
                    pageIndex !in rollingAdmittedPages &&
                        !pages[pageIndex].primaryStarted &&
                        pages[pageIndex].terminalEvent == null &&
                        pages[pageIndex].seededExactBody == null &&
                        !pages[pageIndex].streamedExactBodyPending
                }
            foregroundIdleCompletionRequested = true
            if (newlyAdmitted.isNotEmpty()) {
                // Publish the immutable admission before route preparation. The route worker
                // revalidates this set and otherwise rejects the very page that created it.
                rollingAdmittedPages = rollingAdmittedPages + newlyAdmitted
                pendingRollingAdmittedPages = null
                startReleasedAdjacentRoutePreparationsActor(newlyAdmitted)
                logSourceEvent(
                    "reader_strip_source_foreground_idle_completion_admitted",
                    "new=${newlyAdmitted.size},admitted=${rollingAdmittedPages.size}," +
                        "remaining=${pages.count { !it.primaryStarted && it.publishedBody == null }}",
                )
            }
            refillLanesActor()
        }
    }

    /**
     * Repairs a lost or indefinitely pending route-preparation edge for the exact source which
     * already stopped a physical gesture. Source demand normally prepares newly-admitted pages,
     * but a page can already be present in [rollingAdmittedPages] while its original asynchronous
     * route future never completes. Repeating the same demand then has an empty admission delta
     * and cannot replace that future. Revalidate just this source on the actor, keep the immutable
     * manifest/route ownership, and let the ordinary bounded physical lanes perform the GET.
     */
    fun onPhysicalBlockedPageRequested(episode: NtkEpisodeToken, pageIndex: Int) {
        if (closeRequested.get() || pageIndex < 0) return
        executeActor {
            if (!acceptsEpisode(episode) || !adjacentPrefetch ||
                !NtkReaderTransferPacer.isPhysicalForegroundEpisode(
                    candidateSeal.normalizedEpisodePath,
                ) || pageIndex !in pages.indices
            ) return@executeActor
            val page = pages[pageIndex]
            if (page.publishedBody != null || page.seededExactBody != null ||
                page.terminalEvent != null
            ) return@executeActor
            val now = SystemClock.elapsedRealtime()
            if (lastPhysicalBlockedRedrivePage == pageIndex &&
                now - lastPhysicalBlockedRedriveAtMs <
                    PHYSICAL_BLOCKED_SOURCE_REDRIVE_MIN_INTERVAL_MS
            ) return@executeActor
            lastPhysicalBlockedRedrivePage = pageIndex
            lastPhysicalBlockedRedriveAtMs = now

            if (rollingAdmission && pageIndex !in rollingAdmittedPages) {
                rollingAdmittedPages = rollingAdmittedPages + pageIndex
                pendingRollingAdmittedPages = null
            }
            if (adjacentPrefetchReleased && !page.streamedExactBodyPending &&
                ::quarantineRoutePreparations.isInitialized &&
                quarantineRoutePreparations.isNotEmpty() &&
                !quarantineRoutePreparations[pageIndex].isDone &&
                !page.primaryStarted
            ) {
                // The exact manifest is already installed and this PageState is the compositor's
                // immutable blocker. Do not wait for a click-stream route edge which may never
                // repeat after its finite wave ceded the suffix.
                prepareFallbackRouteForStreamedPage(pageIndex)
            }
            logSourceEvent(
                "reader_strip_source_physical_blocker_redrive",
                "pageIndex=$pageIndex,admitted=${pageIndex in rollingAdmittedPages}," +
                    "started=${page.primaryStarted},route=" +
                    if (::quarantineRoutePreparations.isInitialized &&
                        quarantineRoutePreparations.isNotEmpty()
                    ) {
                        quarantineRoutePreparations[pageIndex].isDone
                    } else {
                        false
                    },
            )
            refillLanesActor()
        }
    }

    fun unresolvedStreamedExactBodyCount(): Int =
        if (closeRequested.get()) 0 else streamedExactBodies?.unresolvedBodyCount() ?: 0

    private fun maybeReleaseAdjacentPrefetchAfterRunwayActor(reason: String) {
        assertActorThread()
        if (!adjacentPrefetch || adjacentPrefetchReleased) return
        val runwayEndExclusive = minOf(
            pages.size,
            initialPageIndex + adjacentInitialRunwayBodyCount,
        )
        val runwayBodiesComplete = (initialPageIndex until runwayEndExclusive)
            .all { pages[it].publishedBody != null }
        if (!adjacentPrefetchReleaseGate.tryClaimRelease(runwayBodiesComplete)) return
        streamedExactBodies?.onAdjacentRunwayReady()
        releaseAdjacentPrefetchActor("${reason}_runway_ready")
    }

    private fun releaseAdjacentPrefetchActor(reason: String) {
        assertActorThread()
        if (!adjacentPrefetch || adjacentPrefetchReleased) return
        adjacentPrefetchReleased = true
        if (rollingAdmission) {
            val previousAdmission = rollingAdmittedPages
            val explicitViewportDemand = sourceDemand?.takeIf { it.demandEpoch > 0L }
            val expandedAdmission = if (demandBoundedAdjacentSuffix) {
                explicitViewportDemand?.let {
                    NtkRollingPhysicalAdmissionPolicy.admittedForwardPages(
                        initialPageIndex,
                        pages.size,
                        it,
                    )
                } ?: previousAdmission
            } else {
                (initialPageIndex until pages.size).toSet()
            }
            // Entering p0 consumes the final slot of the already-resident boundary runway. Refill
            // exactly one source beyond it immediately instead of waiting for the compositor's
            // first stable post-boundary window. On 2-4 second mobile/CDN bodies that later edge
            // arrives after the reader has already reached p5, forcing a visible JPEG decode and
            // forward cap. The paced adjacent executor and global motion read budget still bound
            // physical work; this only keeps the five-page runway full.
            val replenishmentPage = (initialPageIndex + adjacentInitialRunwayBodyCount)
                .takeIf {
                    demandBoundedAdjacentSuffix && finiteDirectWifiAdjacentExecution &&
                        it in pages.indices
                }
            val replenishedAdmission = if (replenishmentPage != null) {
                expandedAdmission + replenishmentPage
            } else {
                expandedAdmission
            }
            val effectiveAdmission = NtkRollingPhysicalAdmissionPolicy.reconcileDemandedPages(
                previousAdmission = previousAdmission,
                demandedAdmission = replenishedAdmission,
                preserveExistingAdmission = foregroundIdleCompletionRequested,
            )
            val newlyAdmitted = effectiveAdmission - previousAdmission
            rollingAdmittedPages = effectiveAdmission
            pendingRollingAdmittedPages = null
            // The pre-geometry deque is materialized from the initial four-page adjacent runway.
            // Expand the immutable resident-body workset after predecessor completion, but keep
            // list structure and decoded pixels under ReaderSession's viewport/idle gates.
            if (!isGeometrySealed() && sourceDemand == null) {
                for (pageIndex in newlyAdmitted.sorted()) {
                    val page = pages[pageIndex]
                    if (pageIndex !in previousAdmission &&
                        !page.primaryStarted && page.terminalEvent == null &&
                        page.seededExactBody == null && !page.streamedExactBodyPending
                    ) {
                        preGeometryPendingPages.addLast(pageIndex)
                    }
                }
            }
            // A click-owned suffix future may have failed before this release and already added
            // its page to previousAdmission. Its earlier fallback route was correctly gated while
            // adjacentPrefetchReleased was false, so newlyAdmitted alone is not a complete work
            // list. Reconcile every effective page with the placeholder futures; the helper is
            // idempotent for completed/initially prepared routes and starts no physical Call.
            startReleasedAdjacentRoutePreparationsActor(effectiveAdmission)
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
                    var admissionChanged = false
                    // The exact manifest is already owned by the visible viewer. Keep physical
                    // calls bounded to HARD/SOFT demand; BACKGROUND remains authority-only until
                    // a later compositor viewport moves the runway.
                    // A proven reverse gesture may move HARD/SOFT demand below the saved resume
                    // floor; keeping initialPageIndex here made that source permanently
                    // descriptor-less and pinned both scroll directions at the loading edge.
                    if (!adjacentPrefetch || adjacentPrefetchReleased) {
                        val previousAdmission = rollingAdmittedPages
                        val expandedAdmission =
                            NtkRollingPhysicalAdmissionPolicy.admittedForwardPages(
                                initialPageIndex,
                                pages.size,
                                delivery.candidate,
                            )
                        val effectiveAdmission =
                            NtkRollingPhysicalAdmissionPolicy.reconcileDemandedPages(
                                previousAdmission = previousAdmission,
                                demandedAdmission = expandedAdmission,
                                preserveExistingAdmission = foregroundIdleCompletionRequested,
                            )
                        val newlyAdmitted = effectiveAdmission - previousAdmission
                        admissionChanged = effectiveAdmission != previousAdmission
                        if (newlyAdmitted.isNotEmpty()) {
                            if (primaryAdmissionsSealed && isGeometrySealed()) {
                                val seal = exactOpenManifestActor().seal
                                if (!NtkStrictSourceOwnershipRegistry.authorizeRollingLateAdmissions(
                                        planBinding.episodePath,
                                        seal.digestSha256,
                                        sessionId,
                                        newlyAdmitted,
                                    )
                                ) {
                                    failSessionActor(
                                        NtkSourceIdentityException(
                                            "Rolling reverse admission lost its exact source owner"
                                        )
                                    )
                                    return@executeActor
                                }
                            }
                            // Route preparation revalidates every page against the actor's current
                            // rolling admission. Publish the new immutable set before starting it;
                            // the former inverse order made each newly demanded pN fail its own
                            // admission check and then lose the only edge that could retry it.
                            rollingAdmittedPages = effectiveAdmission
                            pendingRollingAdmittedPages = null
                            if (adjacentPrefetch) {
                                startReleasedAdjacentRoutePreparationsActor(newlyAdmitted)
                            } else {
                                startRollingLateRoutePreparationsActor(newlyAdmitted)
                            }
                        } else {
                            rollingAdmittedPages = effectiveAdmission
                            pendingRollingAdmittedPages = null
                        }
                    }
                    if (admissionChanged) {
                        val admittedFloor = rollingAdmittedPages.minOrNull() ?: initialPageIndex
                        logSourceEvent(
                            "reader_strip_source_forward_runway_admitted",
                            "initialPage=$initialPageIndex,admittedFloor=$admittedFloor," +
                                "admitted=${rollingAdmittedPages.size}"
                        )
                    }
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
        if (Thread.currentThread() === actorThread.get()) {
            closeBodyLeaseAdmissions()
            closeSessionActor(cause)
            return
        }
        try {
            val admitted = actorCallbackGate.admitClose(
                publishCloseRequested = {
                    synchronized(bodyLeaseAdmissionLock) {
                        closeRequested.set(true)
                    }
                },
                submit = { awaitClosePublication ->
                    actor.execute {
                        awaitClosePublication()
                        markAndAssertActorThread()
                        actorCallbackDepth.set(actorCallbackDepthValue() + 1)
                        try {
                            closeSessionActor(cause)
                        } finally {
                            finishActorCallbackActor()
                        }
                    }
                },
            )
            if (!admitted) {
                check(closeRequested.get() || closeFinalized.get()) {
                    "Strict source close callback admissions closed before finalization"
                }
            }
        } catch (failure: RejectedExecutionException) {
            check(closeRequested.get() || closeFinalized.get()) {
                "Strict source actor rejected its first close callback"
            }
        }
    }

    private fun closeBodyLeaseAdmissions(): Boolean = synchronized(bodyLeaseAdmissionLock) {
        closeRequested.compareAndSet(false, true)
    }

    override fun close() = requestClose(null)

    private fun closeSessionActor(cause: Throwable?) {
        assertActorThread()
        closeBodyLeaseAdmissions()
        failPendingExactBindingInstallActor(
            cause ?: IllegalStateException("Strict source session closed during exact install"),
        )
        clearOperationAdmissionWakeActor()
        if (phase is SessionPhase.Closed || phase is SessionPhase.Closing) {
            maybeFinishClosedActor()
            return
        }
        logCurrentWebtoonCloseDiagnosticActor()
        phase = SessionPhase.Closing(currentExactIdentityActor(), cause)
        currentWebtoonRecoveryProofOwner = null
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
     * One bounded line at retirement preserves the evidence that is otherwise lost when a strict
     * source is cancelled by Activity teardown. This is intentionally aggregate-only: per-body
     * logging measurably perturbs the emulator's four CPU cores and hides the network tail.
     */
    private fun logCurrentWebtoonCloseDiagnosticActor() {
        assertActorThread()
        if (!hostGpuCurrentWebtoonPreferredPhysicalCohorts || bodyPublishedCount <= 0) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val completed = pages.filter { page ->
            page.physicalStartedAtNanos > 0L &&
                page.physicalCompletedAtNanos >= page.physicalStartedAtNanos
        }
        val hostSummary = completed
            .groupBy { page -> page.physicalHost.ifBlank { "unknown" } }
            .toSortedMap()
            .entries
            .joinToString(";") { (host, hostPages) ->
                val durationsMs = hostPages.map { page ->
                    (page.physicalCompletedAtNanos - page.physicalStartedAtNanos) / 1_000_000L
                }
                "$host{n=${hostPages.size},avg=${durationsMs.average().toLong()}," +
                    "max=${durationsMs.maxOrNull() ?: 0L}}"
            }
        val activeSummary = activeWorks.filterNotNull()
            .sortedBy(PrimaryWork::pageIndex)
            .joinToString(";") { work ->
                val ageMs = if (work.physicalStartedAtNanos > 0L) {
                    (nowNanos - work.physicalStartedAtNanos) / 1_000_000L
                } else {
                    -1L
                }
                "${work.pageIndex}@${ageMs}ms"
            }
        Log.i(
            "ViewerPerf",
            "reader_strip_current_close_diagnostic published=$bodyPublishedCount/${pages.size}," +
                "active=${activeWorks.count { it != null }},remaining=" +
                "${pages.count { it.publishedBody == null && it.terminalEvent == null }}," +
                "quicQualified=${currentWebtoonQuicHealthState.qualified}," +
                "quicWellProven=${currentWebtoonQuicHealthState.wellProven}," +
                "quicFrozen=${currentWebtoonQuicHealthState.frozen}," +
                "hosts=$hostSummary,activePages=$activeSummary",
        )
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
            adjacentPrefetchBodyTransfers = adjacentActiveBodyTransferCount,
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

    private fun currentWebtoonRecoveryProfileEligibleActor(): Boolean {
        assertActorThread()
        return hostGpuCurrentWebtoonPreferredPhysicalCohorts && anchorBodyPublishedActor()
    }

    private fun currentWebtoonRecoveryLiveAdmissionEligibleActor(): Boolean {
        assertActorThread()
        return currentWebtoonRecoveryProfileEligibleActor() &&
            isCurrentForegroundViewerEpisode()
    }

    private fun currentWebtoonRecoveryObservationEligibleActor(work: PrimaryWork): Boolean {
        assertActorThread()
        // The exact context freezes the foreground/network/profile proof at request admission.
        // Observe that already-authorized request even if it reaches EOF or failure during Home;
        // this helper never launches new work.
        return currentWebtoonRecoveryProfileEligibleActor() &&
            work.exactContext?.hostGpuCurrentWebtoonResumeRecovery == true
    }

    private fun updateCurrentWebtoonRecoveryStateActor(
        next: NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.State,
        work: PrimaryWork,
        reason: String,
    ) {
        assertActorThread()
        val previous = currentWebtoonRecoveryState
        if (next == previous) return
        currentWebtoonRecoveryState = next
        logSourceEvent(
            "reader_strip_current_webtoon_recovery_governor",
            "oldMode=${previous.mode},newMode=${next.mode}," +
                "pageIndex=${work.pageIndex},workId=${work.workId}," +
                "minimumRecoveryWorkId=${next.minimumRecoveryWorkId}," +
                "recoverySuccessCount=${next.recoverySuccessCount},reason=$reason",
        )
    }

    private fun recordCurrentWebtoonRecoverySuccessActor(work: PrimaryWork) {
        assertActorThread()
        val next = NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordSuccess(
            currentWebtoonRecoveryState,
            work.workId,
            currentWebtoonRecoveryObservationEligibleActor(work),
        )
        updateCurrentWebtoonRecoveryStateActor(next, work, "post_trip_body_success")
    }

    private fun recordCurrentWebtoonC8HealthSuccessActor(work: PrimaryWork) {
        assertActorThread()
        if (!currentWebtoonC8HealthEligibleActor()) return
        val evidence = work.physicalBodyEvidence ?: return
        currentWebtoonC8HealthState.recordSuccess(
            operationId = work.operationId,
            pageIndex = work.pageIndex,
            attemptOrdinal = work.attemptOrdinal,
            cohortKey = coldConnectionCohortByPage[work.pageIndex],
            evidence = evidence,
        )?.let(::logCurrentWebtoonC8HealthTransitionActor)
    }

    private fun recordCurrentWebtoonFragmentedTlsRecoverySuccessActor(work: PrimaryWork) {
        assertActorThread()
        // Quarantine and exact work share this immutable source-session profile and the same
        // post-EOF/SHA evidence sink. Requiring an exact context here discarded every response
        // which started before promotion, even though those bodies are the opening proofs that
        // must qualify the rolling lane cap. Foreground state is still checked when the cap is
        // consumed, so observing a finishing background body cannot launch new work.
        if (!hostGpuCurrentWebtoonPreferredPhysicalCohorts) return
        val evidence = work.physicalBodyEvidence ?: return
        if (currentWebtoonFragmentedTlsRecoveryHealthState.recordSuccess(work.workId, evidence)) {
            logSourceEvent(
                "reader_strip_fragmented_tls_recovery_qualified",
                "pageIndex=${work.pageIndex},workId=${work.workId}," +
                    "laneTarget=${NtkHostGpuEmulatorCurrentWebtoonLanePolicy.MAX_PROVEN_FRAGMENTED_TLS_TRANSFERS}",
            )
        }
    }

    private fun recordCurrentWebtoonQuicHealthSuccessActor(work: PrimaryWork) {
        assertActorThread()
        if (!hostGpuCurrentWebtoonPreferredPhysicalCohorts) return
        val evidence = work.physicalBodyEvidence
        if (evidence == null) {
            if (work.pageIndex <= NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_CURRENT_QUIC_PROBE_PAGE) {
                logSourceEvent(
                    "reader_strip_current_quic_evidence_missing",
                    "pageIndex=${work.pageIndex},workId=${work.workId}",
                )
            }
            return
        }
        val wasQualified = currentWebtoonQuicHealthState.qualified
        val wasWellProven = currentWebtoonQuicHealthState.wellProven
        val wasFrozen = currentWebtoonQuicHealthState.frozen
        val newlyQualified = currentWebtoonQuicHealthState.recordSuccess(
            work.pageIndex,
            work.workId,
            evidence,
        )
        if (newlyQualified || wasQualified != currentWebtoonQuicHealthState.qualified ||
            wasWellProven != currentWebtoonQuicHealthState.wellProven ||
            wasFrozen != currentWebtoonQuicHealthState.frozen
        ) {
            logSourceEvent(
                "reader_strip_current_quic_health",
                "pageIndex=${work.pageIndex},workId=${work.workId}," +
                    "qualified=${currentWebtoonQuicHealthState.qualified}," +
                    "wellProven=${currentWebtoonQuicHealthState.wellProven}," +
                    "frozen=${currentWebtoonQuicHealthState.frozen}," +
                    "laneTarget=${if (currentWebtoonQuicHealthState.wellProven) {
                        NtkHostGpuEmulatorCurrentWebtoonLanePolicy.MAX_WELL_PROVEN_QUIC_TRANSFERS
                    } else if (currentWebtoonQuicHealthState.qualified) {
                        NtkHostGpuEmulatorCurrentWebtoonLanePolicy.MAX_PROVEN_QUIC_TRANSFERS
                    } else {
                        NtkHostGpuEmulatorCurrentWebtoonLanePolicy.MAX_PROVEN_FRAGMENTED_TLS_TRANSFERS
                    }}",
            )
        }
    }

    private fun recordCurrentWebtoonQuicHealthFailureActor(work: PrimaryWork) {
        assertActorThread()
        if (!hostGpuCurrentWebtoonPreferredPhysicalCohorts) return
        if (currentWebtoonQuicHealthState.recordFailure(work.pageIndex)) {
            logSourceEvent(
                "reader_strip_current_quic_health",
                "pageIndex=${work.pageIndex},workId=${work.workId},qualified=false," +
                    "frozen=true,laneTarget=" +
                    NtkHostGpuEmulatorCurrentWebtoonLanePolicy.MAX_PROVEN_FRAGMENTED_TLS_TRANSFERS,
            )
        }
    }

    private fun recordCurrentWebtoonC8HealthFailureActor(work: PrimaryWork) {
        assertActorThread()
        if (!currentWebtoonC8HealthEligibleActor()) return
        currentWebtoonC8HealthState.recordFailure(
            operationId = work.operationId,
            pageIndex = work.pageIndex,
            attemptOrdinal = work.attemptOrdinal,
        )?.let(::logCurrentWebtoonC8HealthTransitionActor)
    }

    private fun currentWebtoonC8HealthEligibleActor(): Boolean {
        assertActorThread()
        // Observe every terminal outcome for the frozen session profile, including while the
        // Activity is backgrounded. Only the cap's live foreground predicate may consume a
        // qualified state; hiding a background failure here could otherwise resurrect C8 later.
        return hostGpuCurrentWebtoonPreferredPhysicalCohorts
    }

    private fun logCurrentWebtoonC8HealthTransitionActor(
        transition: NtkHostGpuEmulatorCurrentWebtoonC8HealthState.Transition,
    ) {
        assertActorThread()
        logSourceEvent(
            "reader_strip_current_webtoon_bulk_target",
            "oldTarget=${transition.oldTarget},newTarget=${transition.newTarget}," +
                "qualified=${transition.qualified},frozen=${transition.frozen}," +
                "reason=${transition.reason},operationId=${transition.operationId}," +
                "pageIndex=${transition.pageIndex},evidence=${transition.evidenceCount}," +
                "distinctCohorts=${transition.distinctCohortCount}," +
                "elapsedMs=${transition.elapsedMs}",
        )
    }

    private fun contiguousForwardPublishedBodyCountActor(): Int {
        assertActorThread()
        val endExclusive = minOf(
            pages.size,
            initialPageIndex + NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                .INITIAL_VISIBLE_RUNWAY_BODIES,
        )
        var count = 0
        for (pageIndex in initialPageIndex until endExclusive) {
            if (pages[pageIndex].bodyEvent == null) break
            count++
        }
        return count
    }

    /**
     * @return true while the single PROBING admission is reserved by this owner. The caller must
     * stop ordinary refill in that case, including while waiting for the monotonic retry deadline.
     */
    private fun serviceCurrentWebtoonRecoveryProofOwnerActor(): Boolean {
        assertActorThread()
        val owner = currentWebtoonRecoveryProofOwner ?: return false
        if (currentWebtoonRecoveryState.mode !=
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING
        ) {
            currentWebtoonRecoveryProofOwner = null
            return false
        }
        if (owner.activeWorkId > 0L) return true
        if (!currentWebtoonRecoveryLiveAdmissionEligibleActor()) {
            // onHostResume does not currently wake this strict source directly. Re-arm only the
            // owner timer (no network Call) so a proof parked during Home resumes within one
            // bounded tick instead of consuming its sole timer and wedging the suffix forever.
            val page = pages.getOrNull(owner.pageIndex)
            if (page != null && !page.physicalRetryScheduled &&
                !actorClosed && !closeRequested.get()
            ) {
                schedulePhysicalRetryActor(
                    page,
                    NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy
                        .BACKGROUND_OWNER_RECHECK_MS,
                )
            }
            return true
        }
        if (SystemClock.elapsedRealtime() < owner.readyAtMs) return true
        val page = pages.getOrNull(owner.pageIndex)
        if (page == null || page.primaryStarted || page.activeWork != null ||
            page.terminalEvent != null || page.publishedBody != null || page.bodyEvent != null ||
            page.physicalAttemptOrdinal + 1 != owner.expectedAttemptOrdinal ||
            page.physicalRetryNotBeforeMs > SystemClock.elapsedRealtime() ||
            (rollingAdmission && page.pageIndex !in rollingAdmittedPages)
        ) {
            currentWebtoonRecoveryProofOwner = null
            return false
        }
        if (!currentWebtoonRecoveryFence.requiresDirectH1(page.pageIndex)) {
            currentWebtoonRecoveryProofOwner = null
            return false
        }
        // A C8 wave that tripped drains to the established healthy C6 ceiling before its proof.
        // The proof replaces the failed slot; it never creates C6+1 traffic.
        val laneIndex = NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.selectLane(
            preferredLaneIndex = owner.preferredLaneIndex,
            activeLanes = BooleanArray(activeWorks.size) { activeWorks[it] != null },
            adoptionLanes = BooleanArray(adoptionInFlightByLane.size) { lane ->
                adoptionInFlightByLane[lane] || hasPendingSealedAdoptionForLaneActor(lane)
            },
            healthyActiveCeiling =
                NtkHostGpuEmulatorCurrentWebtoonC8HealthState.BASE_TARGET,
        ).takeIf { it >= 0 } ?: return true
        val exactOpen = phase as? SessionPhase.ExactOpen ?: return true
        val cached = ReaderImageCache.strictCachedPublishedBody(
            appContext,
            manga,
            page.canonicalAsset,
            exactOpen.manifest.seal,
            page.pageIndex,
        )
        if (cached != null) {
            currentWebtoonRecoveryProofOwner = null
            acceptCachedExactBodyActor(page, cached)
            return false
        }
        if (!canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)) return true
        launchPrimaryFullBodyActor(laneIndex, page)
        owner.activeWorkId = checkNotNull(page.activeWork).workId
        logSourceEvent(
            "reader_strip_current_webtoon_recovery_proof_owned",
            "pageIndex=${page.pageIndex},laneIndex=$laneIndex," +
                "attempt=${page.physicalAttemptOrdinal},workId=${owner.activeWorkId}",
        )
        return true
    }

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
        val paceReleasedAdjacentTail = finiteDirectWifiAdjacentExecution &&
            adjacentPrefetchReleased && anchorBodyPublished
        if (paceReleasedAdjacentTail) {
            val remainingMs = nextReleasedAdjacentPhysicalLaunchAtMs -
                SystemClock.elapsedRealtime()
            if (remainingMs > 0L) {
                schedulePrimaryRefillContinuationActor(remainingMs)
                return
            }
        }
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
        val healthyCurrentWebtoonBulkExpansion =
            currentWebtoonC8HealthState.qualified &&
                !currentWebtoonC8HealthState.frozen &&
                !currentWebtoonRecoveryFence.isTripped()
        val currentWebtoonOrdinaryLaneCount = NtkHostGpuEmulatorCurrentWebtoonLanePolicy.cap(
            progressiveLaneCount = progressiveLaneCount,
            emulatorRuntime = hostGpuEmulatorRuntime,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            webtoon = planBinding.episodePath.startsWith("/webtoon/"),
            rollingAdmission = rollingAdmission,
            initialPageIndex = initialPageIndex,
            currentForegroundEpisode = isCurrentForegroundViewerEpisode(),
            adjacentPrefetch = adjacentPrefetch,
            anchorBodyPublished = anchorBodyPublished,
            contiguousForwardBodyCount = contiguousForwardPublishedBodyCountActor(),
            healthyBulkExpansion = healthyCurrentWebtoonBulkExpansion,
            fragmentedTlsRecoveryQualified =
                currentWebtoonFragmentedTlsRecoveryHealthState.qualified,
            quicRecoveryQualified = currentWebtoonQuicHealthState.qualified &&
                !currentWebtoonQuicHealthState.frozen,
            wellProvenQuicRecoveryQualified = currentWebtoonQuicHealthState.wellProven &&
                !currentWebtoonQuicHealthState.frozen,
        )
        val remainingCurrentBodies = pages.count { page ->
            page.publishedBody == null && page.terminalEvent == null
        }
        val currentWebtoonLaneCount = NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(
            ordinaryCap = currentWebtoonOrdinaryLaneCount,
            remainingBodies = remainingCurrentBodies,
            wellProvenQuicRecoveryQualified = currentWebtoonQuicHealthState.wellProven &&
                !currentWebtoonQuicHealthState.frozen,
        )
        if (currentWebtoonLaneCount > currentWebtoonOrdinaryLaneCount &&
            !finalWellProvenQuicWaveLogged
        ) {
            finalWellProvenQuicWaveLogged = true
            Log.i(
                "ViewerPerf",
                "reader_strip_final_quic_wave ordinary=$currentWebtoonOrdinaryLaneCount," +
                    "expanded=$currentWebtoonLaneCount,remaining=$remainingCurrentBodies," +
                    "active=${activeWorks.count { it != null }}",
            )
        }
        val fenceObservedState =
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.observeFenceTrip(
                currentWebtoonRecoveryState,
                currentWebtoonRecoveryFence.isTripped(),
                workSequence.get(),
                currentWebtoonRecoveryProfileEligibleActor(),
            )
        if (fenceObservedState != currentWebtoonRecoveryState) {
            val previous = currentWebtoonRecoveryState
            currentWebtoonRecoveryState = fenceObservedState
            logSourceEvent(
                "reader_strip_current_webtoon_recovery_governor",
                "oldMode=${previous.mode},newMode=${fenceObservedState.mode}," +
                    "pageIndex=-1,workId=0," +
                    "minimumRecoveryWorkId=${fenceObservedState.minimumRecoveryWorkId}," +
                    "recoverySuccessCount=${fenceObservedState.recoverySuccessCount}," +
                    "reason=physical_fence_observed",
            )
        }
        // The low-level header deadline can trip before its worker posts the failed page back to
        // the actor. Do not let fixed lane zero admit an unrelated proof during that short gap;
        // the completion callback below names the exact failed page and just-freed lane.
        if (currentWebtoonRecoveryFence.isTripped() &&
            currentWebtoonRecoveryState.mode ==
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING &&
            currentWebtoonRecoveryProofOwner == null
        ) return
        val recoveryGovernedLaneCount =
            NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.laneTarget(
                currentWebtoonLaneCount,
                currentWebtoonRecoveryState,
                currentWebtoonRecoveryLiveAdmissionEligibleActor(),
            )
        if (serviceCurrentWebtoonRecoveryProofOwnerActor()) return
        // Connection/header arrival is not body success. Wi-Fi keeps one body per replica origin
        // before page zero EOF. Carrier mode opens one demanded leader per finite host/pool cohort instead:
        // otherwise fifteen pools remain idle for about one second, then their leaders and
        // followers stampede those cold connections together.
        val baseHeadGateLaneCount = NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
            progressiveLaneCount = recoveryGovernedLaneCount,
            preAnchorGateOperations = webtoonPreAnchorGateOperations,
            webtoon = planBinding.episodePath.startsWith("/webtoon/"),
            requiresHeadInstall = requiresAdjacentHeadPixelsInstall,
            anchorBodyPublished = anchorBodyPublished,
            anchorRequestHeadersSent = adjacentAnchorRequestHeadersSent,
            headPixelsInstalled = adjacentHeadPixelsInstalled,
            prioritizeAnchorUntilEof = hostGpuAdjacentManhwaHeadInstall,
            initialRunwayBodyCount = adjacentInitialRunwayBodyCount,
        )
        // A response header proves only the route, not delivery of the anchor body. The pure gate
        // above already defines the bounded p0..p4 visible prefix; do not expand it again from a
        // header callback. The actor opens the remaining finite runway only after p0 publication.
        val headGateLaneCount = baseHeadGateLaneCount
        val releasedLaneCount = NtkAdjacentBulkReleasePolicy.releasedPhysicalLaneCount(
            proposedLaneCount = headGateLaneCount,
            hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
            directWifiTransport = directWifiTransport,
            cellularResilientTransport = cellularResilientTransport,
            adjacentPrefetchReleased = adjacentPrefetchReleased,
            episodePath = planBinding.episodePath,
        )
        val usableLaneCount = NtkForwardResumeAnchorWirePolicy.usableLaneCount(
            proposedLaneCount = releasedLaneCount,
            exclusive = forwardResumeAnchorWireExclusive,
            anchorBodyPublished = anchorBodyPublished,
        )
        val launchLimitThisTurn = when {
            initialWaveCount < initialQuarantineWaveTargetCount ->
                initialQuarantineWaveTargetCount
            paceReleasedAdjacentTail -> 1
            // Only the entry image needs actor latency protection. Once its complete body has
            // crossed the actor boundary, opening the remaining finite ring cannot delay that
            // publication and should happen at full speed for the all-images deadline.
            anchorBodyPublished -> recoveryGovernedLaneCount.coerceAtLeast(1)
            else -> MAX_PRIMARY_LAUNCHES_PER_ACTOR_TURN
        }
        var launchedThisTurn = 0
        laneLoop@ for (laneIndex in 0 until usableLaneCount) {
            while (activeWorks[laneIndex] == null && !adoptionInFlightByLane[laneIndex] &&
                !hasPendingSealedAdoptionForLaneActor(laneIndex) &&
                !closeRequested.get() &&
                (phase is SessionPhase.Quarantining || phase is SessionPhase.ExactOpen) &&
                (!primaryAdmissionsSealed || retryAfterAdmissionSeal)
            ) {
                val selectedFromPreGeometryDeque = !isGeometrySealed() && sourceDemand == null
                val previousLaneRouteAffinity = laneRouteAffinity[laneIndex]
                val page = selectPrimaryPageActor(
                    laneIndex,
                    preferHealthyColdCohortLeaders =
                        anchorBodyPublished && healthyCurrentWebtoonBulkExpansion &&
                            currentWebtoonRecoveryLiveAdmissionEligibleActor(),
                ) ?: break
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
                        acceptCachedExactBodyActor(page, cached)
                        continue
                    }
                    if (!canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)) {
                        // An adoption still owns a completed response's exact operation. Its
                        // worker re-enters refill directly when it belongs to this Session. A
                        // foreign Session instead owns the identity-bound registry grant armed
                        // above. Never block the actor here: it must accept either completion.
                        // selectPrimaryPageActor removes a pre-geometry candidate from its
                        // allocation-free deque. Admission is a later gate, so park the exact
                        // page back at the front before returning or the final foreign operation
                        // would wake an actor that no longer has any work to select.
                        laneRouteAffinity[laneIndex] = previousLaneRouteAffinity
                        if (selectedFromPreGeometryDeque && !page.primaryStarted &&
                            !preGeometryPendingPages.contains(page.pageIndex)
                        ) {
                            preGeometryPendingPages.addFirst(page.pageIndex)
                        }
                        return
                    }
                }
                launchPrimaryFullBodyActor(laneIndex, page)
                launchedThisTurn++
                if (paceReleasedAdjacentTail) {
                    nextReleasedAdjacentPhysicalLaunchAtMs = SystemClock.elapsedRealtime() +
                        DIRECT_WIFI_ADJACENT_RELEASE_LAUNCH_SPACING_MS
                }
                if (launchedThisTurn >= launchLimitThisTurn) {
                    break@laneLoop
                }
            }
        }
        if (launchedThisTurn >= launchLimitThisTurn &&
            initialWaveCount >= initialQuarantineWaveTargetCount
        ) {
            val continuationDelayMs = if (paceReleasedAdjacentTail) {
                (nextReleasedAdjacentPhysicalLaunchAtMs - SystemClock.elapsedRealtime())
                    .coerceAtLeast(1L)
            } else {
                0L
            }
            schedulePrimaryRefillContinuationActor(continuationDelayMs)
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
     * Observes and, if necessary, parks on the process-wide exact-source operation gate without
     * blocking this Session's actor. Registration and the readiness snapshot are one registry
     * transaction, so a foreign Session's final OperationLease cannot retire between a false
     * probe and listener installation.
     */
    private fun canBeginOrAwaitOperationAdmissionActor(
        owner: NtkStrictSourceOwnershipRegistry.Owner,
    ): Boolean {
        assertActorThread()
        operationAdmissionWake?.let { existing ->
            if (!operationAdmissionWakeMatchesOwner(existing, owner)) {
                operationAdmissionWake = null
                operationAdmissionWakeConsumable = false
                existing.close()
            } else {
            when {
                existing.isWaiting() -> return false
                existing.isGranted() -> return operationAdmissionWakeConsumable
                else -> {
                    operationAdmissionWake = null
                    operationAdmissionWakeConsumable = false
                }
            }
            }
        }
        return when (val observation =
            NtkStrictSourceOwnershipRegistry.observeOperationAdmission(owner) { wake ->
                executeActor(onRejected = { wake.close() }) {
                    if (operationAdmissionWake !== wake) {
                        wake.close()
                        return@executeActor
                    }
                    if (closeRequested.get() || actorClosed) {
                        operationAdmissionWake = null
                        operationAdmissionWakeConsumable = false
                        wake.close()
                        return@executeActor
                    }
                    operationAdmissionWakeConsumable = true
                    try {
                        resumeGrantedOperationAdmissionActor(wake)
                    } finally {
                        if (operationAdmissionWake === wake) {
                            operationAdmissionWakeConsumable = false
                            if (wake.isGranted()) {
                                operationAdmissionWake = null
                                wake.close()
                            }
                        }
                    }
                }
            }
        ) {
            is NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Ready -> {
                operationAdmissionWake = observation.wake
                operationAdmissionWakeConsumable = true
                true
            }
            NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.OwnerUnavailable ->
                throw NtkSourceIdentityException(
                    "Strict source operation admission owner is unavailable",
                )
            is NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked -> {
                operationAdmissionWake = observation.wake
                operationAdmissionWakeConsumable = false
                false
            }
        }
    }

    private fun resumeGrantedOperationAdmissionActor(
        wake: NtkStrictSourceOwnershipRegistry.OperationAdmissionWake,
    ) {
        assertActorThread()
        val pendingInstall = pendingExactBindingInstall
        if (pendingInstall != null) {
            if (!operationAdmissionWakeMatchesOwner(wake, pendingInstall.owner)) {
                operationAdmissionWake = null
                operationAdmissionWakeConsumable = false
                wake.close()
                return
            }
            drivePendingExactBindingInstallActor()
            return
        }
        val current = phase as? SessionPhase.ExactOpen
        if (current == null || !operationAdmissionWakeMatchesOwner(wake, current.owner)) {
            operationAdmissionWake = null
            operationAdmissionWakeConsumable = false
            wake.close()
            return
        }
        // A sealed quarantine body has already completed physical I/O and therefore owns the
        // first claim on this grant. Ordinary body refill follows only after every such pending
        // adoption has either consumed the grant or parked again.
        adoptAllSealedBodiesActor()
        refillLanesActor()
    }

    private fun operationAdmissionWakeMatchesOwner(
        wake: NtkStrictSourceOwnershipRegistry.OperationAdmissionWake,
        owner: NtkStrictSourceOwnershipRegistry.Owner,
    ): Boolean {
        return wake.path == planBinding.episodePath &&
            wake.discoveryGeneration == owner.discoveryGeneration &&
            wake.manifestDigest == owner.manifestDigest &&
            wake.exactProofDigest == owner.exactProofDigest &&
            wake.planBindingDigest == owner.planBindingDigest &&
            wake.promotionNonce == owner.promotionNonce &&
            wake.sessionId == sessionId
    }

    private fun clearOperationAdmissionWakeActor() {
        assertActorThread()
        operationAdmissionWake?.close()
        operationAdmissionWake = null
        operationAdmissionWakeConsumable = false
    }

    private fun failPendingExactBindingInstallActor(failure: Throwable) {
        assertActorThread()
        val pending = pendingExactBindingInstall ?: return
        pendingExactBindingInstall = null
        pending.completion.completeExceptionally(failure)
    }

    /**
     * Re-enqueue rather than recurse. Executor FIFO ordering lets EOF/completion callbacks that
     * arrived during this refill run first, while repeated continuations still fill all eligible
     * physical lanes without waiting for another external event.
     */
    private fun schedulePrimaryRefillContinuationActor(delayMs: Long = 0L) {
        assertActorThread()
        if (primaryRefillContinuationScheduled || actorClosed || closeRequested.get()) return
        primaryRefillContinuationScheduled = true
        val continuation = Runnable {
            executeActor {
                primaryRefillContinuationScheduled = false
                if (!actorClosed && !closeRequested.get()) refillLanesActor()
            }
        }
        if (delayMs > 0L) {
            if (!retryHandler.postDelayed(continuation, delayMs)) {
                primaryRefillContinuationScheduled = false
            }
        } else {
            continuation.run()
        }
    }

    private fun selectPrimaryPageActor(
        laneIndex: Int,
        preferHealthyColdCohortLeaders: Boolean,
    ): PageState? {
        assertActorThread()
        val now = SystemClock.elapsedRealtime()
        if (!isGeometrySealed() && sourceDemand == null) {
            val pendingCount = preGeometryPendingPages.size
            repeat(pendingCount) {
                val pageIndex = preGeometryPendingPages.removeFirst()
                val page = pages[pageIndex]
                val openingProofIncomplete =
                    hostGpuCurrentWebtoonPreferredPhysicalCohorts &&
                        NtkHostGpuEmulatorCurrentWebtoonLanePolicy.openingProofIncomplete(
                            contiguousForwardPublishedBodyCountActor(),
                        )
                if (!NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
                        pageIndex = pageIndex,
                        pageCount = pages.size,
                        initialPageIndex = initialPageIndex,
                        initialWaveTarget = if (openingProofIncomplete) {
                            // p0..p4 are the immediately scrollable runway. p5 is the exact
                            // contiguous-body proof that releases the stable eight-lane suffix;
                            // keep it ahead of unrelated cold-pool leaders as part of the same
                            // finite opening cohort.
                            NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                                .INITIAL_VISIBLE_RUNWAY_BODIES
                        } else {
                            initialQuarantineWaveTargetCount
                        },
                        openingWaveIncomplete =
                            openingProofIncomplete ||
                                initialWaveCount < initialQuarantineWaveTargetCount,
                        eligible = hostGpuCurrentWebtoonPreferredPhysicalCohorts,
                    )
                ) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
                if (page.primaryStarted || page.terminalEvent != null ||
                    page.seededExactBody != null ||
                    page.streamedExactBodyPending ||
                    page.physicalRetryNotBeforeMs > now ||
                    (rollingAdmission && pageIndex !in rollingAdmittedPages)
                ) return@repeat
                if (!allowsCurrentOpeningScrollRunwayActor(pageIndex)) {
                    preGeometryPendingPages.addLast(pageIndex)
                    return@repeat
                }
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
                    allowsCurrentOpeningScrollRunwayActor(it.pageIndex) &&
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
        val contiguousOpeningBodies = if (hostGpuCurrentWebtoonPreferredPhysicalCohorts) {
            contiguousForwardPublishedBodyCountActor()
        } else {
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES
        }
        val openingEndExclusive = minOf(
            pages.size,
            initialPageIndex +
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
        )
        val nextOpeningProofPage = if (
            contiguousOpeningBodies <
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES
        ) {
            // A lower page may already be in flight while a later page completes first. Using
            // only the contiguous published count then points at that active page and lets the
            // generic priority order select p4/p5 ahead of p3. Prefer the earliest selectable
            // opening candidate instead, preserving the exact physical scroll runway.
            candidates.asSequence()
                .map { it.pageIndex }
                .filter { it in initialPageIndex until openingEndExclusive }
                .minOrNull()
        } else {
            null
        }
        val selection = NtkStrictSourceSchedulerPolicy.selectPrimary(
            candidates,
            laneRouteAffinity[laneIndex],
            preferredPageIndexes = if (nextOpeningProofPage != null) {
                setOf(nextOpeningProofPage)
            } else if (preferHealthyColdCohortLeaders) {
                coldConnectionCohortLeaderSet
            } else {
                emptySet()
            },
        ) ?: return null
        laneRouteAffinity[laneIndex] = selection.routeBucket
        return pages[selection.candidate.pageIndex]
    }

    private fun isColdConnectionCohortEligible(pageIndex: Int): Boolean {
        val openingProofIncomplete =
            hostGpuCurrentWebtoonPreferredPhysicalCohorts &&
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.openingProofIncomplete(
                    contiguousForwardPublishedBodyCountActor(),
                )
        val openingViewportFollower =
            hostGpuCurrentWebtoonPreferredPhysicalCohorts &&
                (openingProofIncomplete ||
                    initialWaveCount < initialQuarantineWaveTargetCount) &&
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
                    pageIndex = pageIndex,
                    pageCount = pages.size,
                    initialPageIndex = initialPageIndex,
                    initialWaveTarget = if (openingProofIncomplete) {
                        NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES
                    } else {
                        initialQuarantineWaveTargetCount
                    },
                    openingWaveIncomplete = true,
                    eligible = true,
                )
        if (openingViewportFollower) {
            // Two consecutive canonical replica stripes can share one compatibility-host shard.
            // The finite opening-wave pool limit above already bounds that collision to two H2
            // streams and the global gate to three bodies, so do not substitute an offscreen cold
            // leader merely because this visible page is technically a cohort follower.
            return true
        }
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

    /**
     * Source demand can become non-null as soon as the user starts swiping, before geometry or the
     * first drawable is committed. Keep the same contiguous opening fence in both selectors so a
     * demand update cannot bypass it and substitute page 5/69 for the still-missing pages 3/4.
     */
    private fun allowsCurrentOpeningScrollRunwayActor(pageIndex: Int): Boolean {
        assertActorThread()
        val currentForegroundManhwa = planBinding.episodePath.startsWith("/manhwa/") &&
            currentForegroundViewerGeneration > 0L && !adjacentPrefetch
        if (!NtkStrictInitialWavePolicy.allowsCurrentManhwaPreAnchorPage(
                pageIndex = pageIndex,
                pageCount = pages.size,
                initialPageIndex = initialPageIndex,
                currentForegroundEpisode = currentForegroundManhwa,
                anchorBodyPublished = anchorBodyPublishedActor(),
            )
        ) return false
        if (!hostGpuCurrentWebtoonPreferredPhysicalCohorts) return true
        if (!NtkHostGpuEmulatorCurrentWebtoonLanePolicy.openingProofIncomplete(
                contiguousForwardPublishedBodyCountActor(),
            )
        ) return true
        return pageIndex in initialPageIndex until minOf(
            pages.size,
            initialPageIndex +
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
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
        val openingProofIncomplete =
            hostGpuCurrentWebtoonPreferredPhysicalCohorts &&
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.openingProofIncomplete(
                    contiguousForwardPublishedBodyCountActor(),
                )
        val pageInOpeningWave = pageIndex in initialPageIndex until minOf(
            pages.size,
            initialPageIndex +
                NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
        )
        val effectiveLimit = if (openingProofIncomplete && pageInOpeningWave) {
            // Scheduling still carries compatibility-H2 cohort identities, but these finite
            // current-episode bodies are physically isolated H3 requests. The global five-lane
            // opening cap is the real bound; do not serialize p4/p5 behind p1..p3.
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                .openingWaveAnchorPoolOperationLimit(
                    ordinaryLimit = 1,
                    eligible = true,
                    openingWaveIncomplete = true,
                    pageInOpeningWave = true,
                )
        } else if (poolKey == anchorPool && !anchorBodySealed) {
            // Keep the entry image alone on its H2 connection until EOF except for the finite
            // host-emulator opening viewport. Consecutive canonical stripes can map to this same
            // compatibility-host shard; one paired stream lets all three visible bodies start
            // without opening any offscreen work or weakening the post-opening one-per-pool cap.
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                .openingWaveAnchorPoolOperationLimit(
                    ordinaryLimit = 1,
                    eligible = hostGpuCurrentWebtoonPreferredPhysicalCohorts,
                    openingWaveIncomplete =
                        openingProofIncomplete ||
                            initialWaveCount < initialQuarantineWaveTargetCount,
                    pageInOpeningWave = pageIndex in initialPageIndex until minOf(
                        pages.size,
                        initialPageIndex + if (openingProofIncomplete) {
                            NtkHostGpuEmulatorCurrentWebtoonLanePolicy
                                .INITIAL_VISIBLE_RUNWAY_BODIES
                        } else {
                            initialQuarantineWaveTargetCount
                        },
                    ),
                )
        } else {
            // Cohort leaders and the per-origin route limit already prevent a single cold socket
            // from consuming the whole ring. A fixed equal share made faster proven CDN pools sit
            // idle while a slower pool owned the final pages; after the anchor EOF, let the fixed
            // 60-worker ring allocate itself by real completion throughput.
            NtkStrictInitialWavePolicy.connectionPoolOperationLimit(
                hostGpuCurrentWebtoonPreferredPhysicalCohorts,
                activeWorks.size,
            )
        }
        return count < effectiveLimit
    }

    private fun coldConnectionRouteBucketForPage(pageIndex: Int): String {
        require(pageIndex in pages.indices)
        return if (hostGpuCurrentWebtoonPreferredPhysicalCohorts) {
            "https://${NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_H2_PREFERRED_HOST}"
        } else {
            pages[pageIndex].routeBucketHint
        }
    }

    private fun isRoutePreparationReadyWithoutActorWait(pageIndex: Int): Boolean =
        // The start proof requires one real leader per physical pool, so those nine retain their
        // bounded initial wait. The host-emulator current-resume opening window gets the same
        // bounded treatment: its preparations were submitted first, and substituting a later
        // ready page would create a real viewport hole. Every later page is still skipped until
        // its future completes, because blocking the actor there delays response-header callbacks.
        (streamedExactBodies == null && pageIndex in coldConnectionCohortLeaderSet) ||
            (hostGpuCurrentWebtoonPreferredPhysicalCohorts &&
                initialWaveCount < initialQuarantineWaveTargetCount &&
                pageIndex in initialPageIndex until minOf(
                    pages.size,
                    initialPageIndex + initialQuarantineWaveTargetCount,
                )) ||
            quarantineRoutePreparations[pageIndex].isDone

    private fun isBulkSourcePhysicalAdmissionReady(pageIndex: Int): Boolean {
        if (streamedExactBodies == null || bulkSourcePhysicalAdmissionReady.isDone) return true
        if (pageIndex in externallyOwnedPageIndexes && pages[pageIndex].streamedExactBodyPending) {
            return false
        }
        val demandClass = sourceDemand?.demandClass(pageIndex)
        return NtkStrictInitialWavePolicy.isPreBulkFallbackBodyAdmitted(
            pageIndex = pageIndex,
            pageCount = pages.size,
            initialPageIndex = initialPageIndex,
            directWifiTransport = directWifiTransport,
            adjacentPrefetch = adjacentPrefetch,
            foregroundDemanded = demandClass == NtkSourceDemandClass.HARD ||
                demandClass == NtkSourceDemandClass.SOFT,
            adjacentRunwayBodyCount = adjacentInitialRunwayBodyCount,
        )
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
        if (requiresAdjacentHeadPixelsInstall && page.pageIndex == initialPageIndex) {
            check(
                NtkPhysicalConnectionObservationBridge.registerAdjacentRequestHeadersEnd(
                    work.operationId,
                ) {
                    executeActor {
                        if (actorClosed || closeRequested.get() ||
                            activeWorks.getOrNull(work.laneIndex)?.workId != work.workId
                        ) return@executeActor
                        adjacentAnchorRequestHeadersSent = true
                        logSourceEvent(
                            "reader_strip_adjacent_anchor_request_headers_sent",
                            "pageIndex=${page.pageIndex},operationId=${work.operationId}",
                        )
                        refillLanesActor()
                    }
                },
            ) { "Adjacent p0 registered more than one connection-ready callback" }
        }
        val pageIndex = page.pageIndex
        val canonicalAsset = page.canonicalAsset
        val physicalBodyEvidenceSink:
            ((ReaderImageCache.NtkStrictPhysicalBodyEvidence) -> Unit)? =
            if (hostGpuCurrentWebtoonPreferredPhysicalCohorts) {
                { evidence -> work.physicalBodyEvidence = evidence }
            } else {
                null
            }
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
                        onPhysicalBodyProven = physicalBodyEvidenceSink,
                        preferFileBackedBody =
                            NtkAdjacentBodyStoragePolicy.useFileBackedStrictSource(
                                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                                directWifiTransport = directWifiTransport,
                                cellularResilientTransport = cellularResilientTransport,
                                adjacentPrefetch = adjacentPrefetch,
                                episodePath = planBinding.episodePath,
                                pageIndex = pageIndex,
                                initialPageIndex = initialPageIndex,
                                adjacentInitialRunwayBodyCount = adjacentInitialRunwayBodyCount,
                            ),
                        deferBodyReadsWhilePhysicalMotion =
                            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                                adjacentPrefetch = adjacentPrefetch,
                                pageIndex = pageIndex,
                                initialPageIndex = initialPageIndex,
                                adjacentInitialRunwayBodyCount =
                                    adjacentInitialRunwayBodyCount,
                                // This profile starts pN+ only after an immutable HARD/SOFT
                                // viewport-demand admission. Pausing that already-bounded socket
                                // a second time leaves the upcoming page unread until the fling
                                // reaches it and can turn the intentional park into a Range
                                // timeout. Keep the existing process-wide two-lane/8 ms transfer
                                // pacer, but remove the redundant all-bytes stop.
                                viewportDemandBoundsSuffix = demandBoundedAdjacentSuffix,
                            ),
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
                            work.quarantineLease,
                        ),
                        openedLease,
                        work.cancellation,
                        waveRecoveryState = manhwaWaveRecoveryState,
                        responseHeadersSink = {
                            settleColdConnectionCohortLeader(pageIndex)
                        },
                        metadataSink = { },
                        onPhysicalBodyProven = physicalBodyEvidenceSink,
                        preferFileBackedBody =
                            NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                                directWifiTransport = directWifiTransport,
                                cellularResilientTransport = cellularResilientTransport,
                                adjacentPrefetch = adjacentPrefetch,
                                episodePath = planBinding.episodePath,
                            ),
                        deferBodyReadsWhilePhysicalMotion =
                            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                                adjacentPrefetch = adjacentPrefetch,
                                pageIndex = pageIndex,
                                initialPageIndex = initialPageIndex,
                                adjacentInitialRunwayBodyCount =
                                    adjacentInitialRunwayBodyCount,
                                viewportDemandBoundsSuffix = demandBoundedAdjacentSuffix,
                            ),
                    )
                    // EOF/SHA validation is the terminal operation of the physical network lane.
                    // Resident adoption only constructs immutable authority objects; doing it on
                    // this worker made completed bodies wait behind adoption scheduling and kept
                    // the corresponding network lane unavailable for its next GET. The source
                    // actor already has an exact, synchronous resident-adoption path, so transfer
                    // the sealed body immediately and let that path publish it exactly once.
                    val sourceWidth = body.metadataEvidence.sourceWidth
                    val sourceHeight = body.metadataEvidence.sourceHeight
                    val decodedRgbaBytes = if (sourceWidth > 0 && sourceHeight > 0) {
                        runCatching {
                            Math.multiplyExact(
                                Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong()),
                                4L,
                            )
                        }.getOrDefault(Long.MAX_VALUE)
                    } else {
                        Long.MAX_VALUE
                    }
                    val predecodedOriginal = if (
                        hostGpuEmulatorAdjacentP0Predecode &&
                        initialPageIndex == 0 && pageIndex == 0 &&
                        body.encodedBytes != null &&
                        sourceHeight <= HOST_GPU_ADJACENT_P0_PREDECODE_MAX_SOURCE_HEIGHT &&
                        decodedRgbaBytes in 1L..HOST_GPU_ADJACENT_P0_PREDECODE_MAX_RGBA_BYTES
                    ) {
                        ReaderImageCache.predecodeQuarantinedOriginalAsync(
                            body,
                            physicalLanes[work.laneIndex],
                        )
                    } else {
                        null
                    }
                    val physicalResult = PhysicalResult.Quarantined(
                        body,
                        openedLease,
                        predecodedOriginal,
                    )
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
            if (requiresAdjacentHeadPixelsInstall && pageIndex == initialPageIndex) {
                // Keep response headers as the conservative fallback when a transport cannot
                // expose requestHeadersEnd. The direct OkHttp path normally opens p1..p3 earlier
                // after p0 has won wire order, without moving any work before completion.
                adjacentAnchorRequestHeadersSent = true
            }
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
        work.exactContext = beginNextExactOperationActor(work, manifest, route)
        postPromotionStarted++
        return work
    }

    private fun beginNextExactOperationActor(
        work: PrimaryWork,
        manifest: NtkAuthoritativeManifest,
        admittedRoute: ReaderImageCache.NtkResolvedSourceRoute? = work.resolvedRoute,
    ): ReaderImageCache.NtkStrictCallContext {
        assertActorThread()
        check(NtkStrictSourceFailurePolicy.MAX_PHYSICAL_RECOVERY_CYCLES == 0) {
            "Strict exact producer ledger requires an explicit recovery-cycle identity"
        }
        val page = pages[work.pageIndex]
        val previous = page.exactLedgerAttemptOrdinal
        val next = previous + 1
        check(next in 1..NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS) {
            "Strict exact producer ledger exhausted"
        }
        val context = beginExactOperationActor(work, manifest, admittedRoute, next)
        check(page.exactLedgerAttemptOrdinal == previous) {
            "Strict exact producer ledger changed during actor admission"
        }
        page.exactLedgerAttemptOrdinal = next
        return context
    }

    private fun beginExactOperationActor(
        work: PrimaryWork,
        manifest: NtkAuthoritativeManifest,
        admittedRoute: ReaderImageCache.NtkResolvedSourceRoute? = work.resolvedRoute,
        exactAttemptOrdinal: Int,
    ): ReaderImageCache.NtkStrictCallContext {
        assertActorThread()
        require(exactAttemptOrdinal in
            1..NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS)
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
            exactAttemptOrdinal,
        )
        val authority = boundEpisode?.value ?: 0L
        val lease = NtkStrictSourceOwnershipRegistry.beginOperationWithAdmissionWake(
            planBinding.episodePath,
            tag,
            route.routeKeyHash,
            route.callFactoryId,
            attempt = exactAttemptOrdinal,
            rangeStart = -1L,
            rangeEnd = -1L,
            manifestRevision = seal.revision,
            demandEpoch = work.demandEpoch,
            launchedPreGeometry = work.launchedPreGeometry,
            metadataQueueDepth = 0,
            bodyQueueDepth = work.primaryQueueDepth,
            workId = work.workId,
            episodeAuthority = authority,
            preclaim = authority == 0L,
            admissionWake = operationAdmissionWake?.takeIf { it.isGranted() },
            allowBlocking = false,
        )
        operationAdmissionWake?.takeIf { it.isTerminal() }?.let {
            operationAdmissionWake = null
            operationAdmissionWakeConsumable = false
        }
        val hostGpuCurrentWebtoonResumeRecovery =
            hostGpuEmulatorRuntime &&
                directWifiTransport &&
                !cellularResilientTransport &&
                planBinding.episodePath.startsWith("/webtoon/") &&
                rollingAdmission &&
                isCurrentForegroundViewerEpisode() &&
                !adjacentPrefetch
        return ReaderImageCache.NtkStrictCallContext(
            tag,
            lease,
            telemetryAfterSuccessfulHeaders =
                NtkStrictLogicalImageTelemetryPolicy.afterSuccessfulHeaders(
                    directWifiTransport,
                    cellularResilientTransport,
            ),
            hostGpuCurrentWebtoonResumeRecovery = hostGpuCurrentWebtoonResumeRecovery,
            hostGpuCurrentWebtoonRecoveryFence = currentWebtoonRecoveryFence,
        )
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
                val result = runCatching {
                    val value = operation()
                    when (value) {
                        is PhysicalResult.ResidentAdopted ->
                            publishResidentBodyForRender(value.published)
                        is PhysicalResult.Exact -> if (value.body.encodedBytes != null) {
                            publishResidentBodyForRender(value.body)
                        }
                        else -> Unit
                    }
                    value
                }
                work.physicalCompletedAtNanos = SystemClock.elapsedRealtimeNanos()
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
                is PhysicalResult.Quarantined -> {
                    value.predecodedOriginal?.close()
                    value.tempLease.close()
                }
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

    /**
     * A strict-cache hit is already an accepted exact body, but an adjacent render owner also
     * needs the same resident descriptor event emitted by physical and seeded bodies. Keep that
     * event in the actor transaction before the accounting accept; [publishResidentBodyForRender]
     * deduplicates the immutable page capability when a physical publication raced the cache hit.
     */
    private fun acceptCachedExactBodyActor(
        page: PageState,
        cached: ReaderImageCache.NtkStrictPublishedBody,
    ) {
        assertActorThread()
        check(!page.primaryStarted && page.publishedBody == null)
        page.primaryStarted = true
        if (NtkStrictCachedBodyRenderPublicationPolicy.shouldPublishResidentDescriptor(
                adjacentPrefetch = adjacentPrefetch,
                adjacentRenderPublication = adjacentRenderPublication,
            )
        ) {
            publishResidentBodyForRender(cached)
        }
        acceptExactBody(page, cached)
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
        NtkPhysicalConnectionObservationBridge.cancelAdjacentRequestHeadersEnd(work.operationId)
        val page = pages[work.pageIndex]
        if (currentWebtoonRecoveryProofOwner?.activeWorkId == work.workId) {
            currentWebtoonRecoveryProofOwner = null
        }
        if (activeWorks[work.laneIndex]?.workId != work.workId) {
            when (val stale = result.getOrNull()) {
                is PhysicalResult.Quarantined -> {
                    stale.predecodedOriginal?.close()
                    stale.tempLease.close()
                }
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
            recordCurrentWebtoonQuicHealthFailureActor(work)
            recordCurrentWebtoonC8HealthFailureActor(work)
            recordWifiWebtoonAdaptiveFailureActor(work)
            // executePhysical can reject before the operation lambda (and its finally block) runs.
            // Completing both possible ownership modes here closes that last pre-call gap.
            work.quarantineLease?.close()
            work.exactContext?.let { failedContext ->
                failedContext.operationLease.complete()
                if (page.adoptedExactContext === failedContext) {
                    page.adoptedExactContext = null
                }
                work.exactContext = null
            }
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
                val preparedPromotion = phase as? SessionPhase.PromotionPrepared
                val pendingInstall = pendingExactBindingInstall
                if (work.mode == WorkMode.QUARANTINE && work.exactContext == null &&
                    preparedPromotion != null &&
                    work.pageIndex in preparedPromotion.snapshot.activePageIndexes
                ) {
                    if (pendingInstall != null) {
                        check(pendingInstall.token == preparedPromotion.token &&
                            pendingInstall.snapshot == preparedPromotion.snapshot
                        ) { "Pending exact install changed its prepared promotion" }
                    }
                    page.promotionDeferredFailure = PromotionDeferredFailureEvidence(
                        workId = work.workId,
                        operationId = work.operationId,
                        laneIndex = work.laneIndex,
                        demandEpoch = work.demandEpoch,
                        primaryQueueDepth = work.primaryQueueDepth,
                        launchedPreGeometry = work.launchedPreGeometry,
                        physicalAttemptOrdinal = work.attemptOrdinal,
                        resolvedRoute = checkNotNull(work.resolvedRoute) {
                            "Deferred quarantine failure lost its immutable route"
                        },
                    )
                }
                val recoveryEligible = currentWebtoonRecoveryObservationEligibleActor(work)
                val pageFenceRequiresDirectH1 =
                    currentWebtoonRecoveryFence.requiresDirectH1(page.pageIndex)
                val recoveryPressure =
                    NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.pressureObserved(
                        failure = failure,
                        observationEligible = recoveryEligible,
                        fenceRequiresDirectH1 = pageFenceRequiresDirectH1,
                    )
                // The first failed page owns the single proof. A second draining H2 failure must
                // not move minimumRecoveryWorkId past that already-reserved proof.
                val nextRecoveryState = if (recoveryPressure &&
                    currentWebtoonRecoveryProofOwner != null
                ) {
                    currentWebtoonRecoveryState
                } else {
                    NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.recordPressure(
                        state = currentWebtoonRecoveryState,
                        pressureObserved = recoveryPressure,
                        nextWorkId = workSequence.get(),
                    )
                }
                updateCurrentWebtoonRecoveryStateActor(
                    nextRecoveryState,
                    work,
                    failure.javaClass.simpleName,
                )
                // Keep the episode owner and sealed manifest alive. The page becomes eligible for
                // the current viewport demand with a fresh Call/lease. A finite ledger may only
                // open the explicitly bounded number of delayed recovery cycles.
                if (work.attemptOrdinal >= NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS) {
                    page.physicalAttemptOrdinal = 0
                    page.physicalRecoveryCycle++
                }
                val ordinaryDelayMs = NtkStrictSourceFailurePolicy.retryDelayMs(
                    work.attemptOrdinal,
                    page.physicalRecoveryCycle,
                )
                val delayMs =
                    NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.retryDelayMs(
                        ordinaryDelayMs,
                        currentWebtoonRecoveryState,
                        recoveryEligible && (recoveryPressure ||
                            currentWebtoonRecoveryState.mode ==
                                NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.PROBING ||
                            currentWebtoonRecoveryState.mode ==
                                NtkHostGpuEmulatorCurrentWebtoonRecoveryPolicy.Mode.DEGRADED),
                    )
                page.primaryStarted = false
                page.quarantineState = NtkQuarantinePageState.QUEUED
                page.physicalRetryNotBeforeMs = SystemClock.elapsedRealtime() + delayMs
                if (NtkHostGpuEmulatorCurrentWebtoonRecoveryProofPolicy.shouldClaim(
                        pressureObserved = recoveryPressure,
                        observationEligible = recoveryEligible,
                        fenceRequiresDirectH1 = pageFenceRequiresDirectH1,
                        ownerExists = currentWebtoonRecoveryProofOwner != null,
                        mode = currentWebtoonRecoveryState.mode,
                    )
                ) {
                    currentWebtoonRecoveryProofOwner = CurrentWebtoonRecoveryProofOwner(
                        pageIndex = page.pageIndex,
                        preferredLaneIndex = work.laneIndex,
                        expectedAttemptOrdinal = page.physicalAttemptOrdinal + 1,
                        readyAtMs = page.physicalRetryNotBeforeMs,
                    )
                    logSourceEvent(
                        "reader_strip_current_webtoon_recovery_proof_reserved",
                        "pageIndex=${page.pageIndex},laneIndex=${work.laneIndex}," +
                            "attempt=${page.physicalAttemptOrdinal + 1},delayMs=$delayMs",
                    )
                }
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
                if (phase is SessionPhase.ExactOpen) {
                    adoptAllSealedBodiesActor()
                }
                schedulePhysicalRetryActor(page, delayMs)
                if (delayMs == 0L) refillLanesActor()
                } else {
                    page.quarantineState = NtkQuarantinePageState.FAILED
                    failSessionActor(failure, page)
                }
            }
            return
        }
        recordCurrentWebtoonC8HealthSuccessActor(work)
        recordCurrentWebtoonFragmentedTlsRecoverySuccessActor(work)
        recordCurrentWebtoonQuicHealthSuccessActor(work)
        recordWifiWebtoonAdaptiveSuccessActor(work)
        recordCurrentWebtoonRecoverySuccessActor(work)
        when (val value = result.getOrThrow()) {
            is PhysicalResult.Quarantined -> {
                page.pendingPredecodedOriginal?.close()
                page.pendingPredecodedOriginal = value.predecodedOriginal
                page.tempLease = value.tempLease
                page.quarantinedBody = value.body
                page.quarantineMetadata = value.body.metadataEvidence
                page.quarantineState = NtkQuarantinePageState.BODY_SEALED
                if (phase is SessionPhase.ExactOpen) {
                    // One physical lane can finish a promotion-active response while an older
                    // sealed response from that lane is still publishing. Route every sealed
                    // body through the common lane-aware driver; its completion re-enters this
                    // driver before ordinary refill and serializes the next exact adoption.
                    adoptAllSealedBodiesActor()
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
            if (phase is SessionPhase.ExactOpen) {
                adoptAllSealedBodiesActor()
            }
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
                val directWifiAdjacentRetry = directWifiTransport && adjacentPrefetch
                val directWifiAdjacentRemainingMs =
                    if (directWifiAdjacentRetry) {
                        NtkStrictAdjacentRetryReadmissionPolicy.remainingDelayMs(
                            retryNotBeforeMs = current.physicalRetryNotBeforeMs,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                    } else {
                        0L
                    }
                if (directWifiAdjacentRetry && directWifiAdjacentRemainingMs > 0L) {
                    // Use one monotonic-clock sample. Reading it again while crossing the deadline
                    // could turn a positive delay into zero; schedulePhysicalRetryActor rejects a
                    // zero delay and the exact next-runway page would be lost permanently.
                    schedulePhysicalRetryActor(current, directWifiAdjacentRemainingMs)
                    return@executeActor
                } else if (!directWifiAdjacentRetry &&
                    SystemClock.elapsedRealtime() < current.physicalRetryNotBeforeMs
                ) {
                    schedulePhysicalRetryActor(
                        current,
                        current.physicalRetryNotBeforeMs - SystemClock.elapsedRealtime(),
                    )
                    return@executeActor
                }
                if (NtkStrictAdjacentRetryReadmissionPolicy.shouldReadmit(
                        directWifiTransport = directWifiTransport,
                        adjacentPrefetch = adjacentPrefetch,
                        geometrySealed = isGeometrySealed(),
                        hasSourceDemand = sourceDemand != null,
                        retryReady = current.physicalRetryNotBeforeMs <=
                            SystemClock.elapsedRealtime(),
                        alreadyQueued = preGeometryPendingPages.contains(pageIndex),
                    )
                ) {
                    // selectPrimaryPageActor may have observed this page before the retry deadline
                    // and consumed its deque entry. Restore exactly that page when the timer fires.
                    preGeometryPendingPages.addFirst(pageIndex)
                    logSourceEvent(
                        "reader_strip_source_adjacent_retry_readmitted",
                        "pageIndex=$pageIndex,attempt=${current.physicalAttemptOrdinal + 1}",
                    )
                }
                refillLanesActor()
            }
        }, delayMs)
    }

    private fun adoptAllSealedBodiesActor() {
        assertActorThread()
        // Promotion can leave an older contextless body and a still-active promoted producer on
        // the same physical lane. The context owner must retire that exact lane first; only then
        // may a synthetic adoption claim it. Two passes make this independent of page order.
        for (contextOwnedPass in listOf(true, false)) {
            for (page in pages) {
                val body = page.quarantinedBody ?: continue
                if (page.publishedBody != null) continue
                val laneIndex = body.callIdentity.laneIndex
                if (page.quarantineState == NtkQuarantinePageState.EXACT_ADOPTING ||
                    adoptionInFlightByLane[laneIndex] || activeWorks[laneIndex] != null
                ) continue
                val context = page.activeWork?.exactContext ?: page.adoptedExactContext
                if ((context != null) != contextOwnedPass) continue
                if (context == null && hasExactContextOwnerForLaneActor(laneIndex)) continue
                context?.let { owned ->
                    check(owned.tag.laneIndex == laneIndex) {
                        "Promoted exact context changed physical lane"
                    }
                }
                val tempLease = page.tempLease
                if (body.encodedBytes != null && tempLease != null) {
                    adoptResidentBodyActor(page, body, tempLease, context)
                } else {
                    scheduleAdoptionActor(page, body, context)
                }
            }
        }
    }

    private fun hasPendingSealedAdoptionForLaneActor(laneIndex: Int): Boolean {
        assertActorThread()
        if (phase !is SessionPhase.ExactOpen) return false
        return pages.any { page ->
            page.publishedBody == null &&
                page.quarantinedBody?.callIdentity?.laneIndex == laneIndex
        }
    }

    private fun hasExactContextOwnerForLaneActor(laneIndex: Int): Boolean {
        assertActorThread()
        return activeWorks.getOrNull(laneIndex)?.exactContext != null ||
            pages.any { page -> page.adoptedExactContext?.tag?.laneIndex == laneIndex }
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
        val exactOpen = phase as? SessionPhase.ExactOpen
            ?: error("Resident adoption requires exact publication authority")
        val manifest = exactOpen.manifest
        var context = activeContext
        var syntheticContext: ReaderImageCache.NtkStrictCallContext? = null
        if (context == null) {
            if (!canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)) return
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
            context = beginNextExactOperationActor(synthetic, manifest)
            syntheticContext = context
            // From this point the page is the actor-visible owner of the synthetic lease. Any
            // proof/cache/render exception before completeResidentAdoptionActor takes over must
            // still be able to find and retire it during this same actor transaction.
            page.adoptedExactContext = context
        }
        try {
            val proof = NtkQuarantineAdoptionProof.create(planBinding, body, manifest)
            val predecodedOriginal = page.pendingPredecodedOriginal
            val published = try {
                ReaderImageCache.adoptQuarantinedEncodedOriginal(
                    appContext,
                    manga,
                    manifest.seal,
                    page.pageIndex,
                    body,
                    proof,
                    predecodedOriginal,
                )
            } catch (failure: Throwable) {
                predecodedOriginal?.close()
                page.pendingPredecodedOriginal = null
                throw failure
            }
            page.pendingPredecodedOriginal = null
            publishResidentBodyForRender(published)
            completeResidentAdoptionActor(page, body, tempLease, published, context)
        } finally {
            syntheticContext?.takeIf { page.adoptedExactContext === it }?.let { owned ->
                page.adoptedExactContext = null
                owned.operationLease.complete()
            }
        }
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
            if (page.publishedBody !== published) {
                published.predecodedOriginal?.close()
            }
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
            if (adjacentPrefetch || adjacentRenderPublication) {
                publishResidentBodyForRender(seeded)
            }
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
        val exactOpen = phase as? SessionPhase.ExactOpen
            ?: error("File adoption requires exact publication authority")
        if (activeContext == null &&
            !canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)
        ) return
        val adoptionLaneIndex = body.callIdentity.laneIndex
        check(!adoptionInFlightByLane[adoptionLaneIndex])
        adoptionInFlightByLane[adoptionLaneIndex] = true
        val manifest = exactOpen.manifest
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
                context = beginNextExactOperationActor(synthetic, manifest)
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
                    val adoption = AdoptionResult(
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
                    publishResidentBodyForRender(adoption.published)
                    adoption
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
                adoptAllSealedBodiesActor()
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
        failPendingExactBindingInstallActor(failure)
        clearOperationAdmissionWakeActor()
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
        currentWebtoonRecoveryProofOwner = null
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
        if (phase !is SessionPhase.Closing || !closeRequested.get() ||
            activeWorks.any { it != null } ||
            physicalInFlightCount.get() != 0 || activeBodyLeaseCount.get() != 0 ||
            activeAdoptionTaskCount.get() != 0
        ) return
        pages.forEach { page ->
            page.adoptedExactContext?.operationLease?.complete()
            page.adoptedExactContext = null
            page.pendingPredecodedOriginal?.close()
            page.pendingPredecodedOriginal = null
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
        releaseActiveStrictAdjacentPath("close_barrier")
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
                page.quarantinedBody != null || page.publishedBody != null ||
                isPromotionDeferredRetryPageActor(page)
            ) { "Active promotion page did not progress monotonically" }
        }
        for (pageIndex in snapshot.queuedPageIndexes) {
            val page = pages[pageIndex]
            check(!page.primaryStarted && page.quarantinedBody == null &&
                page.publishedBody == null
            ) { "Queued promotion page started while promotion was frozen" }
        }
    }

    private fun isPromotionDeferredRetryPageActor(page: PageState): Boolean {
        assertActorThread()
        return page.promotionDeferredFailure != null &&
            !page.primaryStarted && page.activeWork == null &&
            page.quarantinedBody == null && page.publishedBody == null &&
            page.terminalEvent == null &&
            page.quarantineState == NtkQuarantinePageState.QUEUED
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
        if (!sourceTelemetry.isEnabled()) return
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
        const val DIRECT_WIFI_ADJACENT_RELEASE_LAUNCH_SPACING_MS = 64L
        const val HOST_GPU_ADJACENT_P0_PREDECODE_MAX_SOURCE_HEIGHT = 2_048
        const val HOST_GPU_ADJACENT_P0_PREDECODE_MAX_RGBA_BYTES = 16L * 1024L * 1024L
        const val PHYSICAL_BLOCKED_SOURCE_REDRIVE_MIN_INTERVAL_MS = 1_000L
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
