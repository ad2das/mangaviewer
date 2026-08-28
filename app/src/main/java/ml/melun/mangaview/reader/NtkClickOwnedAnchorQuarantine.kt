package ml.melun.mangaview.reader

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.mangaview.Manga
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private fun ntkClickWorkerThread(
    runnable: Runnable,
    name: String,
    androidPriority: Int = Process.THREAD_PRIORITY_BACKGROUND,
): Thread = Thread({
    runCatching { Process.setThreadPriority(androidPriority) }
    runnable.run()
}, name).apply {
    isDaemon = true
    priority = if (androidPriority <= Process.THREAD_PRIORITY_DISPLAY) {
        Thread.NORM_PRIORITY + 1
    } else {
        Thread.NORM_PRIORITY - 1
    }
}

/**
 * Click-time pools retain their production concurrency while work exists, but not their complete
 * thread ring after a finite episode settles. The next episode recreates workers on demand. This
 * keeps hundreds of dead stacks and Thread roots out of every later ART GC without serializing a
 * request, changing an admission permit, or sharing an executor lane between logical roles.
 */
private fun <T : ExecutorService> T.retireIdleNtkClickWorkers(): T {
    (this as? ThreadPoolExecutor)?.apply {
        setKeepAliveTime(500L, TimeUnit.MILLISECONDS)
        allowCoreThreadTimeOut(true)
    }
    return this
}

/** Exact, manifest-bound completions for one finite post-click transfer wave. */
class NtkClickOwnedExactBodyStream(
    val bodyFutures: Map<Int, CompletableFuture<ReaderImageCache.NtkStrictPublishedBody?>>,
    private val owner: Closeable,
    private val initialViewportActivated: (Int) -> Unit = {},
    private val initialDrawableCommitted: () -> Unit = {},
    private val firstActualFramePresented: () -> Unit = {},
    private val adjacentViewportActivated: () -> Unit = {},
    private val adjacentRunwayReady: () -> Unit = {},
    val sourceRoutePreparationReady: CompletableFuture<Unit> =
        CompletableFuture.completedFuture(Unit),
    /**
     * The click-time metadata probe for p001 across every supported extension/replica. A completed
     * null proves that the document's generated pNNN names are virtual and the signed image table
     * is required; absence means this stream was created without the early probe.
     */
    val sampledAnchorCandidate: CompletableFuture<String?>? = null,
    /**
     * A completion-gated direct-Wi-Fi adjacent p0 GET has already been admitted from the
     * predecessor-proven physical route. Once that resident response is bound to the fresh
     * token document manifest, its header + EOF + digest proof is stronger than a still-pending
     * metadata-only HEAD. No current-episode, cellular, SNI, or non-inherited stream sets this.
     */
    val residentAnchorProofMayPrecedeSampledCandidate: Boolean = false,
    val bulkSourcePhysicalAdmissionReady: CompletableFuture<Unit> =
        sourceRoutePreparationReady,
    val manhwaWaveRecoveryState: NtkManhwaWaveRecoveryState? = null,
    /** The strict source actor, not this finite click wave, owns pN+ viewport admission. */
    private val viewportDemandOwnsSuffix: Boolean = false,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val initialViewportActivationSignaled = AtomicBoolean(false)
    private val initialDrawableCommitSignaled = AtomicBoolean(false)
    private val adjacentViewportActivationSignaled = AtomicBoolean(false)
    private val adjacentRunwayReadySignaled = AtomicBoolean(false)

    init {
        require(bodyFutures.isNotEmpty())
        require(bodyFutures.keys.all { it >= 0 })
    }

    /**
     * Futures in this immutable exact wave own real source work even before a worker has crossed
     * the final Call-admission seam. In particular, an adjacent suffix waits for its drawable
     * runway and can then queue behind the last runway body without appearing in either active
     * HTTP-operation registry. Keep that bounded ownership visible to the descriptor watchdog so
     * it cannot retire a healthy manifest while its own finite wave is still draining.
     */
    fun unresolvedBodyCount(): Int = bodyFutures.values.count { !it.isDone }

    fun onFirstActualFramePresented() {
        if (!closed.get()) firstActualFramePresented()
    }

    fun onInitialViewportActivated(pageIndex: Int) {
        require(pageIndex in bodyFutures.keys)
        if (!closed.get() && initialViewportActivationSignaled.compareAndSet(false, true)) {
            initialViewportActivated(pageIndex)
        }
    }

    fun onInitialDrawableCommitted() {
        if (!closed.get() && initialDrawableCommitSignaled.compareAndSet(false, true)) {
            initialDrawableCommitted()
        }
    }

    fun onAdjacentViewportActivated() {
        if (!closed.get() && adjacentViewportActivationSignaled.compareAndSet(false, true)) {
            adjacentViewportActivated()
        }
    }

    fun onAdjacentRunwayReady() {
        if (viewportDemandOwnsSuffix) return
        if (!closed.get() && adjacentRunwayReadySignaled.compareAndSet(false, true)) {
            adjacentRunwayReady()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) owner.close()
    }
}

/**
 * A completed adjacent runway body may beat its sampled per-page HEAD. Once the target document
 * has produced the exact manifest, that manifest is stronger identity evidence than the still
 * pending HEAD. Permit only the frozen direct-Wi-Fi adjacent p0-p3 cohort, and only when the
 * resident response names the exact canonical asset. Current episodes and every cellular/SNI
 * flight retain the normal candidate-reconciliation path.
 */
internal object NtkDirectWifiAdjacentResidentExactAdoptionPolicy {
    fun shouldAdopt(
        directWifiAdjacentOwned: Boolean,
        forwardFirstPage: Int,
        pageIndex: Int,
        runwayPageCount: Int,
        candidateReconciliationComplete: Boolean,
        expectedCanonicalAsset: String?,
        residentCanonicalAsset: String?,
    ): Boolean {
        if (!directWifiAdjacentOwned || candidateReconciliationComplete) return false
        if (runwayPageCount <= 0 || pageIndex - forwardFirstPage !in 0 until runwayPageCount) {
            return false
        }
        val expected = expectedCanonicalAsset.orEmpty()
        val resident = residentCanonicalAsset.orEmpty()
        return expected.isNotEmpty() && resident.isNotEmpty() &&
            ReaderImageCache.areEquivalentManhwaReplicaAssets(expected, resident)
    }
}

/** Breaks the adjacent anchor's release cycle after its first physical body fails. */
internal object NtkClickOwnedAnchorFallbackAdmissionPolicy {
    fun useDocumentValidatedGate(
        directWifiAdjacentOwned: Boolean,
        pageIndex: Int,
        forwardFirstPage: Int,
    ): Boolean = directWifiAdjacentOwned && pageIndex == forwardFirstPage
}

/** Authority-free request plan; publication still requires the fresh exact viewer manifest. */
private data class NtkClickOwnedQuarantinePlan(
    val normalizedEpisodePath: String,
    val discoveryGeneration: Long,
    val pageCount: Int,
    val documentDraft: NtkEpisodeDocumentPlanDraft? = null,
    val payloadHintDigest: String? = null,
    val maximumNumericBound: Boolean = false,
) {
    init {
        require(NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) == normalizedEpisodePath)
        require(discoveryGeneration > 0L)
        require(pageCount in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        require(listOf(documentDraft != null, payloadHintDigest != null).count { it } == 1)
        require(!maximumNumericBound || payloadHintDigest != null)
        payloadHintDigest?.let { require(NtkStripDigests.isSha256(it)) }
    }

    fun matches(draft: NtkEpisodeDocumentPlanDraft): Boolean =
        draft.normalizedEpisodePath == normalizedEpisodePath &&
            draft.discoveryGeneration == discoveryGeneration &&
            if (maximumNumericBound) draft.pageCount in 1..pageCount else draft.pageCount == pageCount

    fun bindCandidates(candidates: List<String>): NtkQuarantinePlanBinding {
        require(candidates.size == pageCount)
        return documentDraft?.let { draft ->
            NtkQuarantinePlanBinding.from(draft.bindSpeculativeCandidates(candidates))
        } ?: NtkQuarantinePlanBinding.fromClickPayloadHint(
            normalizedEpisodePath,
            discoveryGeneration,
            checkNotNull(payloadHintDigest),
            candidates,
        )
    }
}

/**
 * A completion-gated adjacent flight begins only after its predecessor is fully drawable. Its
 * first four bodies already start from the predecessor-proven physical suffix, so immediately
 * opening the other sixteen extension HEADs only divides the same cold CDN interval with the
 * boundary runway. Keep the established JPG-first hedge here too; a real uncommon target still
 * opens the unchanged bounded alternative race after 150 ms. Every non-adjacent/mobile path is
 * unchanged.
 */
internal object NtkDirectWifiAdjacentExtensionHedgePolicy {
    const val DEFAULT_DELAY_MS = 150L

    fun delayMs(
        directWifiCompletionGatedAdjacent: Boolean,
        pageIndex: Int,
        forwardFirstPage: Int,
    ): Long = if (
        directWifiCompletionGatedAdjacent &&
        pageIndex - forwardFirstPage in
            0 until NtkClickOwnedManhwaWavePolicy.DIRECT_EXTENSION_RACE_PAGES
    ) {
        DEFAULT_DELAY_MS
    } else {
        DEFAULT_DELAY_MS
    }
}

/**
 * Volatile, generation-scoped evidence for one ordinary physical suffix observed on every adopted
 * body of a completed predecessor. This is only a request hint: target HEAD reconciliation and the
 * normal exact-manifest quarantine adoption remain authoritative.
 */
internal object NtkDirectWifiPredecessorPhysicalExtensionRegistry {
    data class Evidence(
        val extension: String,
        val warmReplicaHosts: List<String>,
    )

    private data class Key(
        val predecessorEpisodePath: String,
        val viewerGeneration: Long,
        val networkHandle: Long,
    )

    private val entries = ConcurrentHashMap<Key, Evidence>()

    fun ownedForwardPages(forwardFirstPage: Int, exactPageCount: Int): IntRange {
        require(exactPageCount > 0)
        require(forwardFirstPage in 0 until exactPageCount)
        return forwardFirstPage until exactPageCount
    }

    fun record(
        predecessorEpisodePath: String,
        viewerGeneration: Long,
        capturedNetworkHandle: Long?,
        liveWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        liveNetworkHandle: Long?,
        observedCandidates: List<String>,
        ordinaryH1WarmCandidates: List<String> = emptyList(),
    ): Boolean {
        val path = NtkStripDigests.normalizeEpisodePath(predecessorEpisodePath)
        val handle = capturedNetworkHandle
        if (path.isBlank() || viewerGeneration <= 0L || handle == null ||
            !liveWifiTransport || cellularResilientTransport || liveNetworkHandle != handle
        ) return false
        val key = Key(path, viewerGeneration, handle)
        // Re-evaluating a completed predecessor replaces, rather than coexists with, old evidence.
        entries.remove(key)
        val extensions = observedCandidates.map { candidate ->
            candidate.substringBefore('?').substringBefore('#')
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
        }.toSet()
        val extension = extensions.singleOrNull()
            ?.takeIf { it == "jpg" || it == "jpeg" }
            ?: return false
        if (observedCandidates.isEmpty()) return false
        if (ordinaryH1WarmCandidates.any { it !in observedCandidates }) return false
        val warmReplicaHosts = ordinaryH1WarmCandidates.mapNotNull { candidate ->
            candidate.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase(Locale.ROOT)
                .takeIf(NtkClickOwnedManhwaWavePolicy::isReplicaHost)
        }.distinct()
        entries.keys
            .filter { existing -> existing.predecessorEpisodePath == path && existing != key }
            .forEach(entries::remove)
        entries[key] = Evidence(extension, warmReplicaHosts)
        if (entries.size > MAX_ENTRIES) {
            entries.keys.firstOrNull { it != key }?.let(entries::remove)
        }
        return true
    }

    fun consume(
        predecessorEpisodePath: String,
        viewerGeneration: Long,
        capturedNetworkHandle: Long?,
        directWifiCompletionGatedAdjacent: Boolean,
        predecessorComplete: Boolean,
        liveWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        liveNetworkHandle: Long?,
    ): Evidence? {
        val path = NtkStripDigests.normalizeEpisodePath(predecessorEpisodePath)
        val handle = capturedNetworkHandle
        if (!directWifiCompletionGatedAdjacent || !predecessorComplete || path.isBlank() ||
            viewerGeneration <= 0L || handle == null || !liveWifiTransport ||
            cellularResilientTransport || liveNetworkHandle != handle
        ) return null
        return entries.remove(Key(path, viewerGeneration, handle))
    }

    internal fun resetForTest() = entries.clear()

    private const val MAX_ENTRIES = 16
}

/**
 * Metadata-only numeric candidate frontier started by the committed viewer click.
 *
 * Numeric manhwa page names are finite but their exact count is carried by the episode document.
 * A small format sample uses HEAD while the independent document is in flight, then fills the
 * finite table locally with the preferred extension. It never stores image bytes, publishes a
 * plan, or decodes. Every body still validates HTTP image headers plus encoded magic and retains
 * the bounded GIF/WebP/PNG/JPEG resolver for a genuine candidate miss.
 */
internal class NtkClickOwnedManhwaProbeFrontier private constructor(
    private val episodePath: String,
    private val workId: String,
    private val episodeId: String,
    private val forwardFirstPage: Int,
    private val pageCancellations: Map<Int, ReaderImageCache.Cancellation>,
    private val jpgCandidates: Map<Int, CompletableFuture<String?>>,
    private val preferredExtension: CompletableFuture<String>,
    private val exactPageCountReady: CompletableFuture<Int>,
    private val sourceRoutePreparationReady: CompletableFuture<Unit>,
) : Closeable {
    data class Claim(
        val candidateFutures: Map<Int, CompletableFuture<String?>>,
        val sourceRoutePreparationReady: CompletableFuture<Unit>,
        val owner: NtkClickOwnedManhwaProbeFrontier,
    )

    private val claimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun trimToPageCount(pageCount: Int) {
        require(pageCount in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        jpgCandidates.entries
            .asSequence()
            .filter { it.key >= pageCount }
            .forEach { entry ->
                pageCancellations[entry.key]?.cancel()
                entry.value.cancel(false)
            }
        exactPageCountReady.complete(pageCount)
    }

    fun claim(draft: NtkEpisodeDocumentPlanDraft): Claim? = claimExactCount(
        draft.normalizedEpisodePath,
        draft.requestIdentity.normalizedSourceWorkId,
        draft.requestIdentity.normalizedEpisodeId,
        draft.pageCount,
    )

    fun claimTrustedPayloadCount(path: String, pageCount: Int): Claim? {
        val parts = path.trim('/').split('/')
        if (parts.size != 3) return null
        return claimExactCount(path, parts[1], parts[2], pageCount)
    }

    fun claimMaximumBound(path: String): Claim? {
        val parts = path.trim('/').split('/')
        if (closed.get() || parts.size != 3 || path != episodePath ||
            parts[1] != workId || parts[2] != episodeId ||
            jpgCandidates.size != minOf(
                NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES,
                NtkSourceLanePolicy.MAX_EPISODE_PAGES - forwardFirstPage,
            ) ||
            !claimed.compareAndSet(false, true)
        ) return null
        val maximumCandidates =
            (0 until NtkSourceLanePolicy.MAX_EPISODE_PAGES).associateWith { pageIndex ->
                jpgCandidates[pageIndex] ?: preferredExtension.thenApply { extension ->
                    candidateAsset(workId, episodeId, pageIndex, extension)
                }
            }
        Log.d(
            TAG,
            "click_manhwa_probe_frontier_claim_maximum path=$episodePath," +
                "pages=${maximumCandidates.size},probePages=${jpgCandidates.size}," +
                "ready=${maximumCandidates.values.count { it.isDone }}",
        )
        return Claim(
            maximumCandidates,
            sourceRoutePreparationReady,
            this,
        )
    }

    private fun claimExactCount(path: String, sourceWorkId: String, sourceEpisodeId: String, pageCount: Int): Claim? {
        if (closed.get() || path != episodePath ||
            sourceWorkId != workId || sourceEpisodeId != episodeId ||
            pageCount !in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES ||
            !claimed.compareAndSet(false, true)
        ) return null
        trimToPageCount(pageCount)
        val readyAtClaim = (0 until pageCount).count { pageIndex ->
            jpgCandidates[pageIndex]?.isDone == true
        }
        val claimedCandidates: Map<Int, CompletableFuture<String?>> =
            (0 until pageCount).associateWith { pageIndex ->
                // A pending parallel HEAD is already resolving every supported extension in one
                // RTT. Preserve it so a PNG/GIF/WebP page can never be downgraded to an unproven
                // JPG body merely because the independent document happened to finish first.
                jpgCandidates[pageIndex] ?: CompletableFuture.completedFuture(null)
        }
        Log.d(
            TAG,
            "click_manhwa_probe_frontier_claim path=$episodePath,pages=$pageCount," +
                "frontierPages=${jpgCandidates.size},ready=$readyAtClaim," +
                "cancelledOrDeferred=${pageCount - readyAtClaim}",
        )
        return Claim(
            claimedCandidates,
            sourceRoutePreparationReady,
            this,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pageCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        jpgCandidates.values.forEach { it.cancel(false) }
    }

    companion object {
        private const val TAG = "ViewerPerf"
        private const val FORMAT_SAMPLE_PAGES =
            NtkClickOwnedManhwaWavePolicy.DIRECT_EXTENSION_RACE_PAGES

        private val SETUP_EXECUTOR = Executors.newFixedThreadPool(8) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-probe-setup")
        }.retireIdleNtkClickWorkers()
        private const val PHYSICAL_PLAN_WAIT_MS = 1_200L
        private const val DIRECT_WIFI_LARGE_PNG_BODY_BYTES = 4L * 1024L * 1024L
        private val PHYSICAL_PLAN_DEADLINE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                ntkClickWorkerThread(runnable, "ntk-click-physical-plan")
            }

        fun start(
            manga: Manga,
            normalizedEpisodePath: String,
            initialPageIndexHint: Int = 0,
            directWifiCompletionGatedAdjacent: Boolean = false,
        ): NtkClickOwnedManhwaProbeFrontier? {
            val parts = normalizedEpisodePath.trim('/').split('/')
            if (parts.size != 3 || !parts[0].equals("manhwa", ignoreCase = true) ||
                !parts[1].matches(Regex("\\d{1,12}")) ||
                !parts[2].matches(Regex("\\d{1,12}"))
            ) return null
            val workId = parts[1]
            val episodeId = parts[2]
            val forwardFirstPage = initialPageIndexHint.coerceIn(
                0,
                NtkSourceLanePolicy.MAX_EPISODE_PAGES - 1,
            )
            val probeFrontierEnd = minOf(
                NtkSourceLanePolicy.MAX_EPISODE_PAGES,
                forwardFirstPage + NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES,
            )
            val sampleEnd = minOf(probeFrontierEnd, forwardFirstPage + FORMAT_SAMPLE_PAGES)
            val wifiTransportActive = runCatching {
                getHttpClient().isNtkWifiTransportActive
            }.getOrDefault(false)
            val directWifiNetwork = runCatching {
                getHttpClient().getNtkDirectWifiNetwork()
            }.getOrNull()
            val directWifiMixedResolutionActive = directWifiNetwork != null
            val preferredEvidence = if (wifiTransportActive) {
                NtkClickOwnedManhwaWavePolicy.WIFI_PREFERRED_EXTENSION_EVIDENCE
            } else {
                NtkClickOwnedManhwaWavePolicy.PREFERRED_EXTENSION_EVIDENCE
            }
            val pageCancellations = (forwardFirstPage until probeFrontierEnd)
                .associateWith { ReaderImageCache.Cancellation() }
            val probedBodySizes = ConcurrentHashMap<
                Int,
                NtkClickOwnedManhwaWavePolicy.SizedReplicaBody
                >()
            fun rememberProbeSize(pageIndex: Int, asset: String, byteCount: Long) {
                if (byteCount <= 0L ||
                    !asset.substringAfterLast('.', "").equals("png", ignoreCase = true)
                ) {
                    return
                }
                val host = runCatching { java.net.URI.create(asset).host.orEmpty() }
                    .getOrDefault("")
                if (!NtkClickOwnedManhwaWavePolicy.isReplicaHost(host)) return
                probedBodySizes[pageIndex] =
                    NtkClickOwnedManhwaWavePolicy.SizedReplicaBody(
                        pageIndex,
                        byteCount,
                        host,
                    )
                if (directWifiNetwork != null &&
                    byteCount >= DIRECT_WIFI_LARGE_PNG_BODY_BYTES
                ) {
                    // This exact HEAD already proved both suffix and outlier size. Publish its one
                    // current replica immediately so the whole-body GET does not wait for the
                    // episode-wide LPT snapshot. No extra body or Range request is created.
                    ReaderImageCache.rememberNtkDirectWifiMixedManhwaPhysicalHostPlan(
                        mapOf(asset to host),
                    )
                }
            }
            val sampleFutures = (forwardFirstPage until sampleEnd).associateWith { pageIndex ->
                CompletableFuture.supplyAsync(
                    {
                        ReaderImageCache.probeClickOwnedManhwaReplicaAssetParallel(
                            manga,
                            pageIndex,
                            NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.map { extension ->
                                candidateAsset(workId, episodeId, pageIndex, extension)
                            },
                            checkNotNull(pageCancellations[pageIndex]),
                            extensionHedgeDelayMs =
                                NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                                    directWifiCompletionGatedAdjacent,
                                    pageIndex,
                                    forwardFirstPage,
                                ),
                            // Metadata does not need one connection pool per eventual body
                            // shard. On direct Wi-Fi, multiplex all finite HEAD candidates on the
                            // existing Network-bound probe client. Creating the 24 carrier/H2
                            // body shards here made losing HEAD cancellation enqueue dozens of
                            // HTTP/2 reset tasks while the reader was physically scrolling.
                            isolatedMetadataTransport = directWifiMixedResolutionActive,
                            directWifiNetwork = directWifiNetwork,
                            onUsableResponse = { asset, byteCount ->
                                rememberProbeSize(pageIndex, asset, byteCount)
                            },
                        )
                    },
                    SETUP_EXECUTOR,
                ).thenCompose { it }
            }
            val preferredExtension = CompletableFuture<String>()
            val sampledExtensions = CompletableFuture<List<String>>()
            val firstUsableMixedExtensions = CompletableFuture<List<String>>()
            val remainingSamples = AtomicInteger(sampleFutures.size)
            fun sampleSnapshot(): List<String?> = sampleFutures.values.map { future ->
                runCatching { future.getNow(null) }.getOrNull()
            }
            sampleFutures.values.forEach { sample ->
                sample.whenComplete { _, _ ->
                    val snapshot = sampleSnapshot()
                    val observedExtensions =
                        NtkClickOwnedManhwaWavePolicy.observedSampleExtensions(snapshot)
                    // Never synthesize a JPG observation beside one real PNG. Mixed routing is
                    // valid only when two different suffixes actually answered.
                    val exactResolutionExtensions = observedExtensions
                    if (directWifiMixedResolutionActive &&
                        exactResolutionExtensions.size > 1
                    ) {
                        // The isolated H1 lane is intentionally PNG-only. A GIF observed before a
                        // later PNG must neither publish an unusable mixed marker nor close the
                        // extension barrier early.
                        val uncommonExtension =
                            "png".takeIf(exactResolutionExtensions::contains)
                        if (uncommonExtension != null) {
                            // Two independently observed suffixes are already sufficient to begin
                            // exact tail HEADs. Keep the four-sample barrier below for the final
                            // suffix set; a later third suffix gets a no-duplicate retry only on
                            // pages where this first jpg/png pass found no asset.
                            firstUsableMixedExtensions.complete(exactResolutionExtensions)
                            ReaderImageCache.rememberNtkDirectWifiMixedManhwaEpisode(
                                candidateAsset(
                                    workId,
                                    episodeId,
                                    0,
                                    uncommonExtension,
                                ),
                                uncommonExtension,
                            )
                        }
                        if (uncommonExtension != null) {
                            Log.d(
                                TAG,
                                "click_manhwa_probe_mixed_observed path=$normalizedEpisodePath," +
                                    "resolved=${snapshot.count { it != null }}," +
                                    "extensions=${exactResolutionExtensions.joinToString(separator = ";")}",
                            )
                        }
                    }
                    val consensus =
                        NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                            snapshot,
                            minimumEvidence = preferredEvidence,
                        )
                    if (consensus != null && preferredExtension.complete(consensus)) {
                        Log.d(
                            TAG,
                            "click_manhwa_probe_extension_ready path=$normalizedEpisodePath," +
                                "extension=$consensus,evidence=early_consensus," +
                                "resolved=${snapshot.count { it != null }}",
                        )
                    }
                    if (remainingSamples.decrementAndGet() == 0) {
                        if (directWifiNetwork != null &&
                            observedExtensions.isNotEmpty() &&
                            observedExtensions.all { extension ->
                                extension == "jpg" || extension == "jpeg"
                            }
                        ) {
                            // This proof is published only after every format sample has settled.
                            // A later mixed observation invalidates it monotonically at Call time.
                            ReaderImageCache.rememberNtkDirectWifiOrdinaryManhwaEpisode(
                                candidateAsset(workId, episodeId, forwardFirstPage, "jpg"),
                            )
                        }
                        sampledExtensions.complete(
                            if (directWifiMixedResolutionActive &&
                                observedExtensions.size > 1
                            ) {
                                observedExtensions
                            } else {
                                emptyList()
                            },
                        )
                        firstUsableMixedExtensions.complete(
                            if (directWifiMixedResolutionActive &&
                                observedExtensions.size > 1
                            ) {
                                observedExtensions
                            } else {
                                emptyList()
                            },
                        )
                        val fallback = NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                            snapshot,
                            minimumEvidence = 1,
                        ) ?: "jpg"
                        if (preferredExtension.complete(fallback)) {
                            Log.d(
                                TAG,
                                "click_manhwa_probe_extension_ready path=$normalizedEpisodePath," +
                                    "extension=$fallback,evidence=all_samples_complete," +
                                    "resolved=${snapshot.count { it != null }}," +
                                    "wifi=$wifiTransportActive," +
                                    "mixed=${observedExtensions.joinToString(separator = ";")}",
                            )
                        }
                    }
                }
            }
            val exactPageCountReady = CompletableFuture<Int>()
            val sourceRoutePreparationReady = preferredExtension.thenApply { extension ->
                if (extension != "jpg") {
                    // HEAD proves the immutable filename variant, not image bytes. Publish only
                    // that routing hint so the later exact owner can place its one authoritative
                    // tail GET on the sampled suffix instead of issuing JPG 404s plus five HEADs.
                    ReaderImageCache.rememberNtkGeneratedEpisodeExtensionHint(
                        candidateAsset(workId, episodeId, forwardFirstPage, extension)
                    )
                }
                Unit
            }
            fun probeDirectWifiTailPage(
                pageIndex: Int,
                firstExtensions: List<String>,
            ): CompletableFuture<String?> {
                fun probe(extensions: List<String>): CompletableFuture<String?> =
                    ReaderImageCache.probeClickOwnedManhwaReplicaAssetParallel(
                        manga,
                        pageIndex,
                        extensions.map { extension ->
                            candidateAsset(
                                workId,
                                episodeId,
                                pageIndex,
                                extension,
                            )
                        },
                        checkNotNull(pageCancellations[pageIndex]),
                        extensionHedgeDelayMs = 0L,
                        isolatedMetadataTransport = true,
                        directWifiNetwork = directWifiNetwork,
                        onUsableResponse = { asset, byteCount ->
                            rememberProbeSize(pageIndex, asset, byteCount)
                        },
                    )
                return probe(firstExtensions).thenCompose { candidate ->
                    if (candidate != null) {
                        CompletableFuture.completedFuture(candidate)
                    } else {
                        sampledExtensions.thenCompose { finalExtensions ->
                            val remaining = finalExtensions.filterNot(firstExtensions::contains)
                            if (remaining.isEmpty()) {
                                CompletableFuture.completedFuture(null)
                            } else {
                                probe(remaining)
                            }
                        }
                    }
                }
            }
            val futures = (forwardFirstPage until probeFrontierEnd)
                .associateWith { pageIndex ->
                    val sampled = sampleFutures[pageIndex]
                    if (sampled == null && directWifiMixedResolutionActive) {
                        firstUsableMixedExtensions.thenCompose { mixedExtensions ->
                            if (mixedExtensions.size < 2) {
                                preferredExtension.thenApply { extension ->
                                    candidateAsset(workId, episodeId, pageIndex, extension)
                                }
                            } else {
                                exactPageCountReady.thenCompose { exactPageCount ->
                                    if (pageIndex >= exactPageCount) {
                                        CompletableFuture.completedFuture(null)
                                    } else {
                                        probeDirectWifiTailPage(
                                            pageIndex,
                                            mixedExtensions,
                                        )
                                    }
                                }
                            }
                        }
                    } else if (sampled == null || pageIndex == forwardFirstPage ||
                        directWifiMixedResolutionActive
                    ) {
                        sampled ?: preferredExtension.thenApply { extension ->
                            candidateAsset(workId, episodeId, pageIndex, extension)
                        }
                    } else {
                        // Page zero alone retains a speculative JPG body. For the other entry
                        // pages, take their exact sample when it wins, otherwise route from the
                        // already-proven three-page consensus. A stalled p002 HEAD can therefore
                        // never leave a zero-byte JPG body alive for nine seconds before JPEG
                        // fallback, while a genuinely mixed page still uses its own faster proof.
                        val routed = CompletableFuture<String?>()
                        sampled.whenComplete { candidate, failure ->
                            if (failure == null && candidate != null) routed.complete(candidate)
                        }
                        preferredExtension.whenComplete { extension, failure ->
                            if (failure == null && extension != null) {
                                routed.complete(
                                    candidateAsset(workId, episodeId, pageIndex, extension)
                                )
                            } else if (!routed.isDone) {
                                routed.complete(null)
                            }
                        }
                        routed
                    }
                }
            val physicalPlanReady = if (!directWifiMixedResolutionActive) {
                CompletableFuture.completedFuture(Unit)
            } else {
                sampledExtensions.thenCompose { mixedExtensions ->
                    if (mixedExtensions.size < 2 || "png" !in mixedExtensions) {
                        CompletableFuture.completedFuture(Unit)
                    } else {
                        exactPageCountReady.thenCompose { exactPageCount ->
                            val exactCandidateFutures = (forwardFirstPage until exactPageCount)
                                .mapNotNull(futures::get)
                            val allExactHeadsSettled = CompletableFuture.allOf(
                                *exactCandidateFutures.toTypedArray()
                            ).handle { _, _ -> Unit }
                            val finiteWait = CompletableFuture<Unit>()
                            PHYSICAL_PLAN_DEADLINE_EXECUTOR.schedule(
                                { finiteWait.complete(Unit) },
                                PHYSICAL_PLAN_WAIT_MS,
                                TimeUnit.MILLISECONDS,
                            )
                            CompletableFuture.anyOf(
                                allExactHeadsSettled,
                                finiteWait,
                            ).thenApply {
                                val fixedBodies = probedBodySizes.values
                                    .filter {
                                        it.pageIndex < sampleEnd ||
                                            it.byteCount >= DIRECT_WIFI_LARGE_PNG_BODY_BYTES
                                    }
                                val movableBodies = probedBodySizes.values
                                    .filter {
                                        it.pageIndex in
                                            sampleEnd until exactPageCount &&
                                            it.byteCount < DIRECT_WIFI_LARGE_PNG_BODY_BYTES
                                    }
                                val hosts =
                                    NtkClickOwnedManhwaWavePolicy.sizeBalancedReplicaHosts(
                                        fixedBodies,
                                        movableBodies,
                                    )
                                val physicalAssignments = hosts.mapNotNull {
                                        (pageIndex, host) ->
                                    runCatching {
                                        futures[pageIndex]?.getNow(null)
                                    }.getOrNull()
                                        ?.takeIf { asset ->
                                            asset.substringAfterLast('.', "")
                                                .equals("png", ignoreCase = true)
                                        }
                                        ?.let { asset -> asset to host }
                                }.toMap()
                                ReaderImageCache
                                    .rememberNtkDirectWifiMixedManhwaPhysicalHostPlan(
                                        physicalAssignments
                                    )
                                Log.d(
                                    TAG,
                                    "click_manhwa_probe_physical_plan " +
                                        "path=$normalizedEpisodePath," +
                                        "known=${probedBodySizes.size}," +
                                        "planned=${physicalAssignments.size}," +
                                        "allHeads=${allExactHeadsSettled.isDone}",
                                )
                                Unit
                            }
                        }
                    }
                }
            }
            val routedFutures = futures.mapValues { (pageIndex, candidateFuture) ->
                if (!directWifiMixedResolutionActive || pageIndex < sampleEnd) {
                    candidateFuture
                } else {
                    candidateFuture.thenCompose { candidate ->
                        if (candidate == null ||
                            !candidate.substringAfterLast('.', "")
                                .equals("png", ignoreCase = true)
                        ) {
                            CompletableFuture.completedFuture(candidate)
                        } else if (
                            (probedBodySizes[pageIndex]?.byteCount ?: -1L) >=
                            DIRECT_WIFI_LARGE_PNG_BODY_BYTES
                        ) {
                            CompletableFuture.completedFuture(candidate)
                        } else {
                            physicalPlanReady.handle { _, _ -> candidate }
                        }
                    }
                }
            }
            Log.d(
                TAG,
                "click_manhwa_probe_frontier_start path=$normalizedEpisodePath," +
                    "pages=${routedFutures.size},method=SAMPLED_PARALLEL_HEAD," +
                    "samples=$FORMAT_SAMPLE_PAGES," +
                    "extensions=${NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.size}," +
                    "exactTailFromDocument=true",
            )
            return NtkClickOwnedManhwaProbeFrontier(
                normalizedEpisodePath,
                workId,
                episodeId,
                forwardFirstPage,
                pageCancellations,
                routedFutures,
                preferredExtension,
                exactPageCountReady,
                sourceRoutePreparationReady,
            )
        }

        private fun candidateAsset(
            workId: String,
            episodeId: String,
            pageIndex: Int,
            extension: String,
        ): String =
            "https://${NtkClickOwnedManhwaWavePolicy.replicaHost(pageIndex)}/" +
                "manhwa/$workId/$episodeId/" +
                "p%03d.%s".format(Locale.ROOT, pageIndex + 1, extension)
    }
}

/**
 * A bounded forward-readiness image wave owned by the committed viewer click.
 *
 * The document supplies only work/episode identity and page cardinality, so this flight has no
 * display or source authority. Its encoded bytes remain in a private quarantine file. They can be
 * adopted only after either the fresh signed viewer-image API table or the complete token-bound
 * numeric-manhwa document proves the byte-identical finite asset table. A private post-click decode
 * may overlap that proof, but it has no publication path until exact authority adopts the matching
 * body. A mismatch, cancellation, timeout, or lifecycle exit recycles its pixels and deletes the
 * file.
 */
internal class NtkClickOwnedAnchorQuarantine private constructor(
    private val appContext: Context,
    private val manga: Manga,
    private val plan: NtkClickOwnedQuarantinePlan,
    private val workId: String,
    private val episodeId: String,
    private val forwardFirstPage: Int,
    private val cancellation: ReaderImageCache.Cancellation,
    private val earlyJpgCandidates: Map<Int, CompletableFuture<String?>>,
    private val earlySourceRoutePreparationReady: CompletableFuture<Unit>?,
    private val earlyProbeOwner: NtkClickOwnedManhwaProbeFrontier?,
    private val directWifiAdjacentOwned: Boolean,
    private val adjacentPredecessorComplete: CompletableFuture<Unit>,
    private val viewerGeneration: Long,
    private val adjacentPredecessorEpisodePath: String,
) : Closeable {
    init {
        require(forwardFirstPage in 0 until plan.pageCount)
    }

    private data class HeldBody(
        val body: NtkQuarantinedBody,
        val fileLease: ReaderImageCache.NtkQuarantineFileLease,
        val binding: NtkQuarantinePlanBinding,
        val predecodedOriginal: NtkStrictPredecodedOriginal? = null,
    )

    private class CurrentRestoredBulkBodyLease(
        private val totalLease: Closeable,
        adaptiveOutcome: NtkAdaptiveManhwaBulkAdmission.Lease?,
    ) : Closeable {
        private val adaptive = java.util.concurrent.atomic.AtomicReference(adaptiveOutcome)
        val adaptiveOutcome: NtkAdaptiveManhwaBulkAdmission.Lease?
            get() = adaptive.get()

        fun abandonAdaptive() {
            adaptive.getAndSet(null)?.aborted()
        }

        override fun close() {
            // ReaderImageCache closes the physical-body lease at EOF before its caller can classify
            // the outcome. Keep the adaptive slot owned until succeeded/failed/aborted performs an
            // atomic outcome+release transition, but always return the outer C24 permit here.
            totalLease.close()
        }
    }

    /** Purely local request material that can be built while exact-count authority is in flight. */
    private data class PreparedCandidate(
        val binding: NtkQuarantinePlanBinding,
        val route: ReaderImageCache.NtkResolvedSourceRoute,
    )

    private data class SpeculativeBody(
        val candidate: String?,
        val future: CompletableFuture<HeldBody?>,
    )

    private data class Wave(
        val futures: Map<Int, CompletableFuture<HeldBody?>>,
    )

    private val closed = AtomicBoolean(false)
    private val effectivePageCount = AtomicInteger(plan.pageCount)
    private val manhwaWaveRecoveryState = NtkManhwaWaveRecoveryState(
        plan.pageCount,
        SystemClock.elapsedRealtimeNanos(),
    )
    private val completeDocumentPageCountHint = AtomicInteger(0)
    private val documentValidated = CompletableFuture<Unit>()
    private val wifiEntryPriorityMode = runCatching {
        getHttpClient().isNtkWifiTransportActive
    }.getOrDefault(false)
    private val capturedDirectWifiNetworkHandle = runCatching {
        getHttpClient().getNtkDirectWifiNetwork()?.networkHandle
    }.getOrNull()
    private val hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
        Build.FINGERPRINT,
        Build.MODEL,
        Build.HARDWARE,
        Build.PRODUCT,
    )
    private val directWifiAdjacentPhysicalRunwayPages = if (
        directWifiAdjacentOwned && hostGpuEmulatorRuntime
    ) {
        HOST_GPU_DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES
    } else {
        DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES
    }
    private val predecessorPhysicalEvidence:
        CompletableFuture<NtkDirectWifiPredecessorPhysicalExtensionRegistry.Evidence?> =
        if (directWifiAdjacentOwned) {
            adjacentPredecessorComplete.handle { _, predecessorFailure ->
                if (predecessorFailure != null || closed.get()) {
                    null
                } else {
                    val httpClient = getHttpClient()
                    NtkDirectWifiPredecessorPhysicalExtensionRegistry.consume(
                        predecessorEpisodePath = adjacentPredecessorEpisodePath,
                        viewerGeneration = viewerGeneration,
                        capturedNetworkHandle = capturedDirectWifiNetworkHandle,
                        directWifiCompletionGatedAdjacent = true,
                        predecessorComplete = adjacentPredecessorComplete.isDone,
                        liveWifiTransport = runCatching {
                            httpClient.isNtkWifiTransportActive
                        }.getOrDefault(false),
                        cellularResilientTransport = runCatching {
                            httpClient.isNtkCellularResilientTransportActive
                        }.getOrDefault(true),
                        liveNetworkHandle = runCatching {
                            httpClient.getNtkDirectWifiNetwork()?.networkHandle
                        }.getOrNull(),
                    )
                }
            }
        } else {
            CompletableFuture.completedFuture(null)
        }
    private val directWifiEarlyUncommonEnabled =
        capturedDirectWifiNetworkHandle != null && runCatching {
            !getHttpClient().isNtkCellularResilientTransportActive
        }.getOrDefault(false)
    private val hostGpuCurrentRestoredViewportPriority =
        NtkClickOwnedManhwaWavePolicy.shouldFenceHostGpuCurrentRestoredViewportBodies(
            hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
            directWifiAdjacentOwned = directWifiAdjacentOwned,
            wifiTransport = wifiEntryPriorityMode,
            cellularResilientTransport = !directWifiEarlyUncommonEnabled,
            capturedNetworkHandle = capturedDirectWifiNetworkHandle,
            forwardFirstPage = forwardFirstPage,
        )
    private val physicalExtensionRecordingEligible = viewerGeneration > 0L &&
        wifiEntryPriorityMode && directWifiEarlyUncommonEnabled
    private val initialSpeculationPages = minOf(
        if (directWifiAdjacentOwned) {
            directWifiAdjacentPhysicalRunwayPages
        } else {
            NtkClickOwnedManhwaWavePolicy.initialSpeculationPages(wifiEntryPriorityMode)
        },
        plan.pageCount,
    )
    private val pageCancellations = (0 until plan.pageCount)
        .associateWith { ReaderImageCache.Cancellation() }
    private val fallbackCancellations = (0 until plan.pageCount)
        .associateWith { ReaderImageCache.Cancellation() }
    private val speculativeUncommonCancellations = (0 until plan.pageCount)
        .associateWith { ReaderImageCache.Cancellation() }
    private val observedCandidates = (0 until plan.pageCount)
        .associateWith { CompletableFuture<String>() }
    private val adoptedPhysicalCandidates = if (physicalExtensionRecordingEligible) {
        (0 until plan.pageCount).associateWith { CompletableFuture<String>() }
    } else {
        emptyMap()
    }
    private val physicalExtensionEvidenceArmed = AtomicBoolean(false)
    private val dominantTailExtension: CompletableFuture<String?> =
        if (plan.maximumNumericBound && earlyJpgCandidates.isNotEmpty()) {
            val sample = earlyJpgCandidates.entries
                .sortedBy { it.key }
                .take(DOMINANT_EXTENSION_SAMPLE_PAGES)
                .map { it.value }
            CompletableFuture.allOf(*sample.toTypedArray()).handle { _, _ ->
                NtkClickOwnedManhwaWavePolicy.dominantTailExtension(
                    sample.map { future ->
                        runCatching { future.getNow(null) }.getOrNull()
                    },
                )
            }
        } else {
            CompletableFuture.completedFuture(null)
        }
    private val retained = ConcurrentHashMap<Int, HeldBody>()
    private val directWifiAdjacentInheritedResidentBodies =
        if (directWifiAdjacentOwned) {
            (forwardFirstPage until minOf(
                plan.pageCount,
                forwardFirstPage + directWifiAdjacentPhysicalRunwayPages,
            )).associateWith { CompletableFuture<HeldBody>() }
        } else {
            emptyMap()
        }
    // The target's exact manifest may bind an already-resident adjacent p0-p3 body before its
    // sampled per-page HEAD completes. Remember that stronger decision so the later HEAD cannot
    // launch a redundant mismatch-reconciliation request for the already-adopted exact asset.
    private val manifestBoundResidentRunwayPages = ConcurrentHashMap.newKeySet<Int>()
    // Completion-gated adjacent p0-p3 share the same captured H1 pool. Consecutive canonical
    // stripes place p0 and p3 on the same replica; opening both together forces a second cold TLS
    // connection. Chain only equal-host inherited bodies so p3 can reuse p0's just-idled socket.
    private val inheritedAdjacentPageReleases =
        ConcurrentHashMap<Int, CompletableFuture<Unit>>()
    // Every common JPG transfer binds the same immutable maximum numeric table. Rebuilding that
    // full table and its digest in every body worker was O(pageCount^2) allocation on the cold
    // path. One session-owned binding preserves the identical authority checks and lets only the
    // uncommon extension correction build a sparse one-off replacement table.
    private val defaultJpgBinding by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        plan.bindCandidates(
            List(plan.pageCount) { pageIndex -> candidateAsset(pageIndex, DEFAULT_EXTENSION) },
        )
    }
    // Admission is an ordered forward frontier, not a race for anonymous semaphore permits.
    // Otherwise an executor wake-up can spend the finite speculative budget on page 118 while
    // page 4 is still waiting, wasting transport on a candidate outside the eventual document.
    private val rollingSpeculationFrontier = AtomicInteger(
        minOf(plan.pageCount, forwardFirstPage + initialSpeculationPages),
    )
    private val speculationDebtHolders = ConcurrentHashMap.newKeySet<Int>()
    private val bodyTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
        true,
    )
    // The carrier/SNI and known-mixed paths retain the measured forty-body ring. Ordinary direct
    // Wi-Fi JPEG volumes alone use the Network-bound HTTP/1.1 body limit below.
    private val ordinaryDirectWifiBodyTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.DIRECT_WIFI_ORDINARY_BODY_TRANSFERS - 1,
        true,
    )
    // p0-p4 keep their existing independent runway admission. Once that runway is resident, roll
    // the suffix through one physical Call instead of letting a chapter enqueue a burst of local
    // setup, body completions and bitmap publications during the active boundary fling.
    private val hostGpuAdjacentTailBodyTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_TRANSFERS,
        true,
    )
    private val hostGpuCurrentRestoredBulkAdmission = NtkAdaptiveManhwaBulkAdmission(
        eligibleBodyCount = (
            plan.pageCount - minOf(
                plan.pageCount,
                forwardFirstPage +
                    NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_RESTORED_VIEWPORT_BODIES,
            )
        ).coerceAtLeast(0),
        // A probing stage used to stop refilling freed slots until every body in the stage had
        // drained. On a healthy Korean edge that made the throughput benchmark itself the long
        // pole: the same exact-original C6 rolling wave is faster than the stop-and-probe ladder.
        // Keep the measured C6 admission continuously work-conserving for this session. Socket,
        // DNS, cancellation, mixed-format, adjacent and non-emulator policies remain unchanged.
        probeWiderStages = false,
        healthGatedRollingRamp = true,
        // A maximum-bound numeric plan is provisionally 384 pages. Do not let those synthetic
        // suffix slots qualify a wider physical wave before the document publishes exact count.
        finiteBodyCountKnownAtConstruction = !plan.maximumNumericBound,
    )
    // This remains the common outer ceiling across ordinary, mixed and unresolved bodies.
    // Ordinary JPEGs add the adaptive inner gate; mixed PNGs still add their independent C8 gate.
    private val hostGpuCurrentRestoredTotalBulkBodyTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_RESTORED_BULK_BODY_TRANSFERS,
        true,
    )
    // One of the unchanged forty direct-Wi-Fi transfers belongs to the current viewport. Page zero
    // uses it on a normal launch; a cold near-tail continue uses it for that restored page. Bulk
    // bodies may borrow it only after the viewport is known to be inside the normal probe frontier,
    // so a late tail identity can never queue behind offscreen work.
    private val directWifiViewportBodyTransferPermit = Semaphore(1, true)
    // Large PNGs on mixed direct-Wi-Fi volumes are connection-bound on the replica CDN. Letting
    // all of them open HTTP/1.1 transfers together divides the same edge bandwidth into many
    // stalled sockets. Eight was the fastest measured finite wave; ordinary JPGs and every
    // cellular/SNI request stay on the existing forty-lane policy.
    private val mixedUncommonTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.MIXED_UNCOMMON_BODY_TRANSFERS,
        true,
    )
    // A proven uncommon page-zero extension must never queue behind the bulk transfer wave.
    // This is one additional bounded body slot, not a preload: it is reachable only after the
    // committed click and a successful image-only HEAD for that exact p001 asset.
    private val anchorBodyTransferPermit = Semaphore(1, true)
    private val waveReleased = AtomicBoolean(false)
    private val networkRelease = CompletableFuture<Unit>()
    private val initialViewportPage = CompletableFuture<Int>()
    private val restoredTailDrawableCommitted = CompletableFuture<Unit>()
    private val adjacentViewportRelease = CompletableFuture<Unit>().also { release ->
        if (!directWifiAdjacentOwned) release.complete(Unit)
    }
    private val adjacentRunwayRelease = CompletableFuture<Unit>().also { release ->
        if (!directWifiAdjacentOwned) release.complete(Unit)
    }
    private val firstActualFramePresented = CompletableFuture<Unit>()
    // Only a host-GPU direct-Wi-Fi resumed *current* manhwa waits here. The four viewport bodies
    // bypass this future; every other body waits before Call creation, owning neither a socket nor
    // transfer permit. Once the four logical body futures are terminal, the unchanged forty-wide
    // chapter wave opens. A live transport handoff also bypasses the frozen Wi-Fi-only fence.
    private val hostGpuCurrentRestoredViewportBodyRelease = CompletableFuture<Unit>().also { gate ->
        if (!hostGpuCurrentRestoredViewportPriority) gate.complete(Unit)
    }
    private val wifiEntryReleaseGate = CompletableFuture<Unit>().also { gate ->
        if (!wifiEntryPriorityMode) {
            gate.complete(Unit)
        } else {
            firstActualFramePresented.whenComplete { _, _ ->
                ENTRY_RELEASE_SCHEDULER.execute { gate.complete(Unit) }
            }
            ENTRY_RELEASE_SCHEDULER.schedule(
                { gate.complete(Unit) },
                NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_RELEASE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }
    // A body worker used to enter awaitRollingNumericAdmission() and poll every 25 ms. A
    // 112-page book therefore parked 72-88 Java threads until document authority completed, then
    // made Android schedule all of them at once; r56 spread valid GET starts out to click +1008 ms.
    // Each page now owns an event. The first bounded seed and later exact-header credits complete
    // individual events, while fresh document authority completes the retained remainder only
    // after the existing network-release gate. No waiting worker occupies BODY_EXECUTOR.
    private val numericAdmissionFutures = (0 until plan.pageCount)
        .associateWith { CompletableFuture<Unit>() }
    // A maximum-bound click plan contains 384 synthetic numeric slots until the document proves
    // the real page count. Build only the physical entry runway before that proof. Materializing a
    // candidate/body/predecode graph for every synthetic slot created hundreds of callbacks and
    // short-lived objects per chapter even though their network admission was closed. Once the
    // document is authoritative, append only its real finite suffix to the same click-owned wave.
    private val waveFuture: CompletableFuture<Wave?> =
        CompletableFuture.supplyAsync(::startForwardWave, COORDINATOR_EXECUTOR)
            .thenCompose { provisionalWave ->
                if (!plan.maximumNumericBound || provisionalWave == null) {
                    CompletableFuture.completedFuture(provisionalWave)
                } else {
                    documentValidated.thenApplyAsync(
                        { completeExactForwardWave(provisionalWave) },
                        COORDINATOR_EXECUTOR,
                    )
                }
            }

    init {
        documentValidated.whenComplete { _, documentFailure ->
            if (documentFailure == null && !closed.get()) {
                armPredecessorPhysicalExtensionEvidence()
            }
        }
        if (plan.maximumNumericBound) {
            val seedStart = forwardFirstPage
            val seedEnd = rollingSpeculationFrontier.get()
            (seedStart until seedEnd).forEach { pageIndex ->
                speculationDebtHolders.add(pageIndex)
                numericAdmissionFutures.getValue(pageIndex).complete(Unit)
            }
            documentValidated.whenComplete { _, documentFailure ->
                if (documentFailure != null || closed.get()) return@whenComplete
                val exactCount = effectivePageCount.get()
                val preFrameEnd = if (directWifiAdjacentOwned) {
                    minOf(forwardFirstPage + initialSpeculationPages, exactCount)
                } else {
                    minOf(
                        forwardFirstPage +
                            NtkClickOwnedManhwaWavePolicy.EXACT_PRE_FRAME_RUNWAY_PAGES,
                        exactCount,
                    )
                }
                if (NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                        wifiEntryPriorityMode,
                        exactCount,
                    )
                ) {
                    releaseWifiExactPreFrameRunwayAfterEntry(seedEnd, preFrameEnd, exactCount)
                } else {
                    releaseExactPreFrameRunway(
                        seedEnd,
                        preFrameEnd,
                        exactCount,
                        "document_validated",
                    )
                }
            }
            // Predecessor completion proves the current episode owns no remaining work, but opening
            // the whole adjacent wave there still divides bandwidth with the four boundary pages.
            // Finish that exact runway first, then admit the encoded suffix without viewport input.
            val completeWaveRelease = if (directWifiAdjacentOwned) {
                networkRelease.thenCombine(adjacentRunwayRelease) { _, _ -> Unit }
            } else {
                networkRelease
            }
            documentValidated.thenCombine(completeWaveRelease) { _, _ -> Unit }
                .whenComplete { _, admissionFailure ->
                    val exactCount = effectivePageCount.get()
                    if (admissionFailure == null && !closed.get()) {
                        val admissionOrder = if (directWifiAdjacentOwned) {
                            NtkClickOwnedManhwaWavePolicy.adjacentExactBodyAdmissionOrder(
                                exactCount,
                                initialSpeculationPages,
                            )
                        } else {
                            NtkClickOwnedManhwaWavePolicy.exactBodyAdmissionOrder(exactCount)
                        }
                        admissionOrder
                            .forEach { pageIndex ->
                                numericAdmissionFutures.getValue(pageIndex).complete(Unit)
                            }
                    }
                    numericAdmissionFutures.forEach { (pageIndex, admission) ->
                        if (!admission.isDone) {
                            admission.completeExceptionally(
                                admissionFailure ?: InterruptedException(
                                    if (closed.get()) {
                                        "Numeric page $pageIndex admission closed"
                                    } else {
                                        "Numeric page $pageIndex is outside the exact admitted table"
                                    }
                                )
                            )
                        }
                    }
                }
        }
        // An anchor constructed from the already-complete click-owned document has no later
        // validation callback. Mark its exact count immediately so uncommon-format fallback can
        // overlap the primary JPG body wave.
        if (plan.documentDraft != null) documentValidated.complete(Unit)
    }

    /**
     * Releases the post-click image wave after ACK-critical network traffic has completed. This
     * is an event gate, not a timer: document parsing continues immediately and no image request
     * occurs before the isolated owner reports its real prerequisite milestone.
     */
    fun releaseAfterAckNetworkPrerequisites() {
        releaseWave("ack_prerequisites")
    }

    fun releaseForTrustedClickPayloadCount() {
        check(plan.payloadHintDigest != null) {
            "Trusted click-payload release requires payload-count ownership"
        }
        releaseWave("trusted_click_payload_count")
    }

    fun releaseForBoundedNumericCandidates() {
        check(plan.maximumNumericBound) {
            "Bounded numeric-candidate release requires maximum-bound ownership"
        }
        releaseWave("bounded_numeric_candidates")
    }

    /**
     * Releases a cold maximum-bound flight only after the fresh document has trimmed it to the
     * exact page count. HEAD-only extension discovery may overlap the document, but image bodies
     * remain one-per-page and cannot be cancelled merely because the volume ended before the
     * speculative numeric frontier.
     */
    fun releaseAfterDocumentValidation() {
        check(documentValidated.isDone && !documentValidated.isCompletedExceptionally) {
            "Document-validated release requires an accepted fresh document"
        }
        releaseWave("document_validated")
    }

    /**
     * Releases only the finite request frontier from a complete 200 document's strictly parsed
     * numeric component. Full document parsing still owns authority/publication and must later
     * match this count exactly. This moves no request before the viewer click or document EOF; it
     * merely keeps HTML tree construction off the network release critical path.
     */
    fun releaseForCompleteDocumentPageCount(pageCount: Int) {
        check(plan.maximumNumericBound) {
            "Early complete-document count requires bounded numeric ownership"
        }
        require(pageCount in 1..plan.pageCount)
        val prior = completeDocumentPageCountHint.get()
        check(prior == 0 || prior == pageCount) { "Complete-document count changed" }
        completeDocumentPageCountHint.compareAndSet(0, pageCount)
        applyExactPageCount(pageCount)
        releaseAllSpeculationDebt()
        documentValidated.complete(Unit)
        releaseWave("complete_document_page_count")
    }

    fun validateDocumentDraft(draft: NtkEpisodeDocumentPlanDraft): Boolean {
        val hintedCount = completeDocumentPageCountHint.get()
        val matches = plan.matches(draft) && (hintedCount == 0 || hintedCount == draft.pageCount)
        if (matches && plan.maximumNumericBound) {
            applyExactPageCount(draft.pageCount)
        }
        Log.d(
            TAG,
            "click_payload_count_document_validation path=${plan.normalizedEpisodePath}," +
                "hintPages=${plan.pageCount},documentPages=${draft.pageCount}," +
                "numericBound=${plan.maximumNumericBound},matches=$matches",
        )
        if (matches) {
            releaseAllSpeculationDebt()
            documentValidated.complete(Unit)
        } else {
            documentValidated.completeExceptionally(
                IllegalStateException("Click numeric candidate bound did not match document"),
            )
            close()
        }
        return matches
    }

    private fun applyExactPageCount(pageCount: Int) {
        effectivePageCount.set(pageCount)
        val finiteBulkBodies = (
            pageCount - minOf(
                pageCount,
                forwardFirstPage +
                    NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_RESTORED_VIEWPORT_BODIES,
            )
        ).coerceAtLeast(0)
        if (hostGpuCurrentRestoredViewportPriority) {
            hostGpuCurrentRestoredBulkAdmission
                .settleForFiniteBodyCount(finiteBulkBodies)
                ?.let { admission ->
                    Log.d(
                        TAG,
                        "click_current_restored_bulk_admission " +
                            "path=${plan.normalizedEpisodePath},target=${admission.targetLimit}," +
                            "best=${admission.bestLimit},active=${admission.activeLeases}," +
                            "settled=${admission.settled},frozen=${admission.frozen}," +
                            "reason=${admission.transitionReason},bodies=$finiteBulkBodies",
                    )
                }
        }
        numericAdmissionFutures.entries
            .asSequence()
            .filter { it.key >= pageCount }
            .forEach { (pageIndex, admission) ->
                admission.completeExceptionally(
                    InterruptedException("Numeric page $pageIndex is outside exact count $pageCount")
                )
            }
        earlyProbeOwner?.trimToPageCount(pageCount)
        pageCancellations.entries
            .asSequence()
            .filter { it.key >= pageCount }
            .forEach { it.value.cancel() }
        fallbackCancellations.entries
            .asSequence()
            .filter { it.key >= pageCount }
            .forEach { it.value.cancel() }
        speculativeUncommonCancellations.entries
            .asSequence()
            .filter { it.key >= pageCount }
            .forEach { it.value.cancel() }
        retained.entries.toList().forEach { (pageIndex, held) ->
            if (pageIndex >= pageCount && retained.remove(pageIndex, held)) {
                held.predecodedOriginal?.close()
                held.fileLease.close()
            }
        }
    }

    /** Records only one suffix after every exact body has reached EOF and passed adoption. */
    private fun armPredecessorPhysicalExtensionEvidence() {
        if (!physicalExtensionRecordingEligible) return
        if (!physicalExtensionEvidenceArmed.compareAndSet(false, true)) return
        val exactCount = effectivePageCount.get()
        val ownedPages = NtkDirectWifiPredecessorPhysicalExtensionRegistry
            .ownedForwardPages(forwardFirstPage, exactCount)
        val exactCandidates = ownedPages.map { pageIndex ->
            checkNotNull(adoptedPhysicalCandidates[pageIndex])
        }
        CompletableFuture.allOf(*exactCandidates.toTypedArray()).whenComplete { _, failure ->
            if (failure != null || closed.get()) return@whenComplete
            val httpClient = getHttpClient()
            val recorded = NtkDirectWifiPredecessorPhysicalExtensionRegistry.record(
                predecessorEpisodePath = plan.normalizedEpisodePath,
                viewerGeneration = viewerGeneration,
                capturedNetworkHandle = capturedDirectWifiNetworkHandle,
                liveWifiTransport = runCatching {
                    httpClient.isNtkWifiTransportActive
                }.getOrDefault(false),
                cellularResilientTransport = runCatching {
                    httpClient.isNtkCellularResilientTransportActive
                }.getOrDefault(true),
                liveNetworkHandle = runCatching {
                    httpClient.getNtkDirectWifiNetwork()?.networkHandle
                }.getOrNull(),
                observedCandidates = exactCandidates.map(CompletableFuture<String>::join),
                ordinaryH1WarmCandidates = if (
                    forwardFirstPage >= NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_SPECULATION_PAGES
                ) {
                    exactCandidates
                        .take(NtkClickOwnedManhwaWavePolicy.DIRECT_EXTENSION_RACE_PAGES)
                        .map(CompletableFuture<String>::join)
                } else {
                    emptyList()
                },
            )
            Log.d(
                TAG,
                "click_predecessor_physical_extension path=${plan.normalizedEpisodePath}," +
                    "pages=${exactCandidates.size},forwardFirst=$forwardFirstPage," +
                    "exactPages=$exactCount,recorded=$recorded",
            )
        }
    }

    /**
     * Resolves the exact mixed-extension table when every page in the complete document has a
     * valid replica image response. Image EOF/digest publication remains per-page in the stream.
     */
    fun observedDocumentAuthorityFuture(
        lease: NtkDiscoveryLease,
        draft: NtkEpisodeDocumentPlanDraft,
    ): CompletableFuture<NtkManifestAuthorityFactory.TokenBoundDocumentAuthority>? {
        if (closed.get() || !plan.matches(draft) ||
            (!plan.maximumNumericBound && plan.documentDraft == null &&
                plan.payloadHintDigest == null) ||
            draft.normalizedEpisodePath != lease.episodePath ||
            draft.discoveryGeneration != lease.generation.value ||
            effectivePageCount.get() != draft.pageCount
        ) return null
        val exactCandidates = (0 until draft.pageCount).map { pageIndex ->
            checkNotNull(observedCandidates[pageIndex])
        }
        return CompletableFuture.allOf(*exactCandidates.toTypedArray()).thenApply {
            check(!closed.get()) { "Click-owned observed manifest closed before completion" }
            checkNotNull(
                NtkManifestAuthorityFactory.createObservedNumericReplicaDocumentAuthority(
                    lease,
                    draft,
                    exactCandidates.map(CompletableFuture<String>::join),
                )
            ) { "Observed numeric replica responses did not form exact authority" }
        }
    }

    /**
     * A complete token-bound document authority no longer needs ACK traffic to establish its
     * finite source table. Release immediately after that proof exists so the user's image bytes
     * overlap the independent browser audit instead of idling behind it.
     */
    fun releaseForTokenBoundDocumentAuthority(authority: NtkAuthoritativeManifest) {
        val proof = authority.proof as? NtkTokenBoundGeneratedManifestProof
            ?: throw IllegalArgumentException("Token-bound release requires token-bound authority")
        require(
            proof.documentPlanProof.normalizedEpisodePath == plan.normalizedEpisodePath &&
                proof.documentPlanProof.discoveryGeneration == plan.discoveryGeneration &&
                proof.documentPlanProof.pageCount == effectivePageCount.get() &&
                authority.seal.pageCount == effectivePageCount.get()
        ) { "Token-bound release authority does not match the click document" }
        releaseWave("token_bound_document_authority")
    }

    fun forwardFirstPage(): Int = forwardFirstPage

    private fun releaseWave(reason: String) {
        if (closed.get() || !waveReleased.compareAndSet(false, true)) return
        Log.d(
            TAG,
            "click_forward_quarantine_release_pending path=${plan.normalizedEpisodePath}," +
                "reason=$reason,gate=anchor_body_resident",
        )
        // The initial forward viewport is already a real post-click network request and owns a
        // dedicated transfer permit. Once that exact anchor body is resident, later click-owned
        // pages perform only
        // bounded network spooling. Their authoritative decode path remains independently gated by
        // firstActualFramePresented through bulkSourcePhysicalAdmissionReady below. Waiting for
        // HWUI after anchor EOF therefore left every 100+ page volume idle for another 400-500 ms
        // without protecting either the anchor transfer or its display-priority decode. Release
        // only the network wave at this exact body milestone; no timer, speculative decode, or
        // pre-entry request is introduced. A failed anchor still releases the tail so the strict
        // source fallback can recover that page.
        waveFuture.whenComplete waveComplete@ { wave, waveFailure ->
            val anchor = if (waveFailure == null) {
                wave?.futures?.get(forwardFirstPage)
            } else {
                null
            }
            if (anchor == null) {
                completeNetworkRelease(reason, "anchor_unavailable")
            } else {
                anchor.whenComplete { held, anchorFailure ->
                    if (anchorFailure != null || held == null) {
                        completeNetworkRelease(reason, "anchor_failed")
                    } else {
                        completeNetworkRelease(reason, "anchor_body_resident")
                    }
                }
            }
        }
    }

    private fun notifyFirstActualFramePresented() {
        if (closed.get() || !firstActualFramePresented.complete(Unit)) return
        Log.d(
            TAG,
            "click_forward_quarantine_first_actual path=${plan.normalizedEpisodePath}",
        )
    }

    private fun notifyInitialViewportActivated(pageIndex: Int) {
        if (closed.get() || pageIndex !in 0 until effectivePageCount.get() ||
            !initialViewportPage.complete(pageIndex)
        ) return
        Log.d(
            TAG,
            "click_forward_quarantine_initial_viewport " +
                "path=${plan.normalizedEpisodePath},page=$pageIndex",
        )
    }

    private fun notifyInitialDrawableCommitted() {
        val initialPageIndex = initialViewportPage.getNow(-1)
        if (closed.get() ||
            initialPageIndex - forwardFirstPage <
                NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES ||
            !restoredTailDrawableCommitted.complete(Unit)
        ) return
        Log.d(
            TAG,
            "click_forward_quarantine_restored_tail_drawable " +
                "path=${plan.normalizedEpisodePath},page=$initialPageIndex",
        )
    }

    private fun notifyAdjacentViewportActivated() {
        if (closed.get() || !directWifiAdjacentOwned ||
            !adjacentViewportRelease.complete(Unit)
        ) return
        Log.d(
            TAG,
            "click_forward_quarantine_adjacent_viewport_release " +
                "path=${plan.normalizedEpisodePath}," +
                "runwayPages=${minOf(directWifiAdjacentPhysicalRunwayPages, effectivePageCount.get())}",
        )
    }

    private fun notifyAdjacentRunwayReady() {
        if (closed.get() || !directWifiAdjacentOwned || !adjacentRunwayRelease.complete(Unit)) return
        Log.d(
            TAG,
            "click_forward_quarantine_adjacent_runway_release " +
                "path=${plan.normalizedEpisodePath}," +
                "runwayPages=${minOf(directWifiAdjacentPhysicalRunwayPages, effectivePageCount.get())}",
        )
    }

    /**
     * On direct Wi-Fi, keep p001-p012 on an otherwise quiet connection ring until their first
     * physical frame is visible. A finite timeout releases recovery work if that presentation
     * cannot be proved; cellular never enters this method.
     */
    private fun releaseWifiExactPreFrameRunwayAfterEntry(
        seed: Int,
        preFrameEnd: Int,
        exactCount: Int,
    ) {
        networkRelease.whenComplete { _, _ ->
            releaseExactPreFrameRunway(
                seed,
                preFrameEnd,
                exactCount,
                "wifi_anchor_body_resident",
            )
        }
    }

    private fun releaseExactPreFrameRunway(
        seed: Int,
        preFrameEnd: Int,
        exactCount: Int,
        reason: String,
    ) {
        if (closed.get()) return
        (seed until preFrameEnd).forEach { pageIndex ->
            numericAdmissionFutures.getValue(pageIndex).complete(Unit)
        }
        Log.d(
            TAG,
            "click_exact_pre_frame_runway path=${plan.normalizedEpisodePath}," +
                "pages=$seed-${preFrameEnd - 1},exactPages=$exactCount," +
                "wifiEntryPriority=$wifiEntryPriorityMode,reason=$reason",
        )
    }

    private fun completeNetworkRelease(reason: String, gateResult: String) {
        if (closed.get() || !networkRelease.complete(Unit)) return
        Log.d(
            TAG,
            "click_forward_quarantine_release path=${plan.normalizedEpisodePath}," +
                "reason=$reason,gate=$gateResult",
        )
    }

    /**
     * Binds the already-running, click-owned transfers to the fresh exact manifest without
     * waiting for the slowest body. Each future publishes only after its own immutable bytes have
     * passed the exact replica/manifest proof. The source session can therefore decode completed
     * pages while the tail of the same finite network wave is still arriving.
     */
    fun streamIfExact(
        exactManifest: NtkAuthoritativeManifest,
    ): NtkClickOwnedExactBodyStream? {
        if (exactManifest.seal.pageCount != effectivePageCount.get()) {
            Log.d(
                TAG,
                "click_anchor_quarantine_reject path=${plan.normalizedEpisodePath}," +
                    "reason=effective_page_count_mismatch,expected=${effectivePageCount.get()}," +
                    "actual=${exactManifest.seal.pageCount}",
            )
            close()
            return null
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ADOPTION_WAIT_MS)
        val wave = runCatching {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) null else waveFuture.get(remaining, TimeUnit.NANOSECONDS)
        }.getOrNull()
        if (wave == null) {
            Log.d(
                TAG,
                "click_anchor_quarantine_reject path=${plan.normalizedEpisodePath}," +
                    "reason=candidate_wave_not_ready",
            )
            close()
            return null
        }
        if (wave.futures.isEmpty()) {
            Log.d(
                TAG,
                "click_anchor_quarantine_reject path=${plan.normalizedEpisodePath},reason=no_body_futures",
            )
            close()
            return null
        }
        if (closed.get()) {
            return null
        }
        val viewportDemandOwnsSuffix = directWifiAdjacentOwned && hostGpuEmulatorRuntime
        val clickOwnedEndExclusive = minOf(
            effectivePageCount.get(),
            forwardFirstPage + directWifiAdjacentPhysicalRunwayPages,
        )
        val exactFutures = wave.futures
            .filterKeys { pageIndex ->
                pageIndex < effectivePageCount.get() &&
                    (!viewportDemandOwnsSuffix || pageIndex < clickOwnedEndExclusive)
            }
            .toSortedMap()
            .mapValues { (pageIndex, future) ->
            val residentAdoption = tryAdoptDirectWifiAdjacentResidentExactBody(
                pageIndex,
                future,
                exactManifest,
            )
            val reconciled = future.handle { held, transferFailure ->
                if (transferFailure != null || held == null || closed.get()) {
                    held?.let(::discardHeldBody)
                    null
                } else {
                    adoptHeldBody(pageIndex, held, exactManifest)
                }
            }
            residentAdoption?.let { adoption ->
                CompletableFuture.completedFuture(adoption.published)
            } ?: directWifiAdjacentResidentExactAdoptionFuture(
                pageIndex,
                exactManifest,
            )?.applyToEither(reconciled) { it } ?: reconciled
        }
        val extensionRouteReady =
            earlySourceRoutePreparationReady ?: CompletableFuture.completedFuture(Unit)
        val bulkRouteReady = extensionRouteReady.thenCombine(firstActualFramePresented) { _, _ ->
            Unit
        }
        val stream = NtkClickOwnedExactBodyStream(
            bodyFutures = exactFutures,
            owner = this,
            initialViewportActivated = ::notifyInitialViewportActivated,
            initialDrawableCommitted = ::notifyInitialDrawableCommitted,
            firstActualFramePresented = ::notifyFirstActualFramePresented,
            adjacentViewportActivated = ::notifyAdjacentViewportActivated,
            adjacentRunwayReady = ::notifyAdjacentRunwayReady,
            // URL derivation is CPU-only and must be ready before the source actor starts. Holding
            // it behind the first frame deadlocks exact publication because that publication is
            // what makes the streamed anchor renderable.
            sourceRoutePreparationReady = extensionRouteReady,
            sampledAnchorCandidate = earlyJpgCandidates[forwardFirstPage],
            residentAnchorProofMayPrecedeSampledCandidate =
                directWifiAdjacentOwned &&
                    directWifiAdjacentInheritedResidentBodies.containsKey(forwardFirstPage),
            // Only physical GET admission for pages outside the click-owned wave waits for the
            // real frame. This protects the forward anchor from bulk socket/decode contention without
            // delaying source-session promotion or route preparation.
            bulkSourcePhysicalAdmissionReady = bulkRouteReady,
            manhwaWaveRecoveryState = manhwaWaveRecoveryState,
            viewportDemandOwnsSuffix = viewportDemandOwnsSuffix,
        )
        CompletableFuture.allOf(*exactFutures.values.toTypedArray()).whenComplete { _, _ ->
            val published = exactFutures.values.mapNotNull { it.getNow(null) }
            val completeEpisodeStream =
                exactFutures.size == effectivePageCount.get() - forwardFirstPage &&
                    published.size == exactFutures.size
            Log.d(
                TAG,
                "click_anchor_quarantine_stream_complete path=${plan.normalizedEpisodePath}," +
                    "pages=${published.size},expected=${exactFutures.size}," +
                    "bytes=${published.sumOf { it.proof.encodedLength }}," +
                    "completeEpisodeStream=$completeEpisodeStream",
            )
            // A partial click stream and the remaining exact routes share one source authority.
            // Closing its owner merely because the first eight futures completed invalidates the
            // token-bound plan while the exact session is still downloading pages 8..N. The
            // source session closes the stream owner at its real terminal lifecycle boundary.
            if (completeEpisodeStream) close()
        }
        Log.d(
            TAG,
            "click_anchor_quarantine_stream_bound path=${plan.normalizedEpisodePath}," +
                "pages=${exactFutures.size},ready=${exactFutures.values.count { it.isDone }}",
        )
        return stream
    }

    private data class ResidentExactAdoption(
        val published: ReaderImageCache.NtkStrictPublishedBody?,
    )

    private fun tryAdoptDirectWifiAdjacentResidentExactBody(
        pageIndex: Int,
        candidateFuture: CompletableFuture<HeldBody?>,
        exactManifest: NtkAuthoritativeManifest,
    ): ResidentExactAdoption? {
        val held = retained[pageIndex] ?: return null
        return tryAdoptDirectWifiAdjacentResidentExactBody(
            pageIndex = pageIndex,
            candidateReconciliationComplete = candidateFuture.isDone,
            held = held,
            exactManifest = exactManifest,
        )
    }

    private fun directWifiAdjacentResidentExactAdoptionFuture(
        pageIndex: Int,
        exactManifest: NtkAuthoritativeManifest,
    ): CompletableFuture<ReaderImageCache.NtkStrictPublishedBody?>? {
        val residentReady = directWifiAdjacentInheritedResidentBodies[pageIndex] ?: return null
        val adoption = CompletableFuture<ReaderImageCache.NtkStrictPublishedBody?>()
        residentReady.whenComplete { held, failure ->
            if (failure != null || held == null || closed.get()) return@whenComplete
            val preparedHeld = attachDirectWifiAdjacentResidentPredecode(pageIndex, held)
            Log.d(
                TAG,
                "click_adjacent_resident_exact_signal path=${plan.normalizedEpisodePath}," +
                    "page=$pageIndex,asset=${preparedHeld.body.canonicalAsset.substringAfterLast('/')}",
            )
            val result = tryAdoptDirectWifiAdjacentResidentExactBody(
                pageIndex = pageIndex,
                candidateReconciliationComplete = false,
                held = preparedHeld,
                exactManifest = exactManifest,
            )
            result?.published?.let(adoption::complete)
        }
        return adoption
    }

    private fun attachDirectWifiAdjacentResidentPredecode(
        pageIndex: Int,
        held: HeldBody,
    ): HeldBody {
        if (held.predecodedOriginal != null || closed.get()) return held
        if (NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                encodedBytesAvailable = held.body.encodedBytes != null,
                sealedFileAvailable = held.body.sealedFile.isFile,
            )
        ) {
            // The exact EOF/SHA-owned file is the input to HostExactHardwareTilePool. A private
            // Bitmap here would be consumed once and immediately replaced by the same file's AHB
            // decode, adding a full-page NativeAllocationRegistry owner during live scrolling.
            return held
        }
        val adjacentRunwayOffset = pageIndex - forwardFirstPage
        val executor = if (
            directWifiAdjacentOwned && adjacentRunwayOffset == 0
        ) {
            // Only p0 is on the entry-critical path. Keep its decode on the single display lane;
            // letting p1..p4 share that priority caused five NativeAlloc-heavy decodes to contend
            // with the renderer during the current episode's physical scroll.
            ANCHOR_PREDECODE_EXECUTOR
        } else if (
            directWifiAdjacentOwned &&
            adjacentRunwayOffset in 1 until directWifiAdjacentPhysicalRunwayPages
        ) {
            DIRECT_WIFI_ADJACENT_RUNWAY_PREDECODE_EXECUTOR
        } else if (pageIndex == forwardFirstPage) {
            ANCHOR_PREDECODE_EXECUTOR
        } else {
            BULK_PREDECODE_EXECUTOR
        }
        val prepared = held.copy(
            predecodedOriginal = ReaderImageCache.predecodeQuarantinedOriginalAsync(
                held.body,
                executor,
            ),
        )
        return if (retained.replace(pageIndex, held, prepared)) {
            prepared
        } else {
            retained[pageIndex] ?: held
        }
    }

    private fun tryAdoptDirectWifiAdjacentResidentExactBody(
        pageIndex: Int,
        candidateReconciliationComplete: Boolean,
        held: HeldBody,
        exactManifest: NtkAuthoritativeManifest,
    ): ResidentExactAdoption? {
        val expectedCanonicalAsset = exactManifest.seal.normalizedCanonicalAssets
            .getOrNull(pageIndex)
        if (!NtkDirectWifiAdjacentResidentExactAdoptionPolicy.shouldAdopt(
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                forwardFirstPage = forwardFirstPage,
                pageIndex = pageIndex,
                runwayPageCount = minOf(
                    directWifiAdjacentPhysicalRunwayPages,
                    exactManifest.seal.pageCount - forwardFirstPage,
                ),
                candidateReconciliationComplete = candidateReconciliationComplete,
                expectedCanonicalAsset = expectedCanonicalAsset,
                residentCanonicalAsset = held.body.canonicalAsset,
            )
        ) {
            Log.d(
                TAG,
                "click_adjacent_resident_exact_skip path=${plan.normalizedEpisodePath}," +
                    "page=$pageIndex,candidateComplete=$candidateReconciliationComplete," +
                    "expected=${expectedCanonicalAsset.orEmpty().substringAfterLast('/')}," +
                    "resident=${held.body.canonicalAsset.substringAfterLast('/')}",
            )
            return null
        }
        if (!retained.remove(pageIndex, held)) return null
        val published = adoptClaimedHeldBody(pageIndex, held, exactManifest)
        if (published != null) {
            manifestBoundResidentRunwayPages.add(pageIndex)
            Log.d(
                TAG,
                "click_adjacent_resident_exact_adopt path=${plan.normalizedEpisodePath}," +
                    "page=$pageIndex,bytes=${published.proof.encodedLength}",
            )
        }
        return ResidentExactAdoption(published)
    }

    private fun adoptHeldBody(
        pageIndex: Int,
        held: HeldBody,
        exactManifest: NtkAuthoritativeManifest,
    ): ReaderImageCache.NtkStrictPublishedBody? {
        if (!retained.remove(pageIndex, held)) return null
        return adoptClaimedHeldBody(pageIndex, held, exactManifest)
    }

    private fun adoptClaimedHeldBody(
        pageIndex: Int,
        held: HeldBody,
        exactManifest: NtkAuthoritativeManifest,
    ): ReaderImageCache.NtkStrictPublishedBody? = runCatching {
        val proof = NtkQuarantineAdoptionProof.create(held.binding, held.body, exactManifest)
        ReaderImageCache.adoptQuarantinedEncodedOriginal(
            appContext,
            manga,
            exactManifest.seal,
            pageIndex,
            held.body,
            proof,
            held.predecodedOriginal,
        )
    }.onSuccess { exactBody ->
        adoptedPhysicalCandidates[pageIndex]?.complete(held.body.canonicalAsset)
        held.fileLease.consume()
    }.onFailure { failure ->
        held.predecodedOriginal?.close()
        held.fileLease.close()
        Log.d(
            TAG,
            "click_anchor_quarantine_page_reject path=${plan.normalizedEpisodePath}," +
                "page=$pageIndex,reason=adoption_${failure.javaClass.simpleName}",
        )
    }.getOrNull()

    private fun startForwardWave(): Wave? {
        val absolutePageLimit = minOf(MAX_CLICK_FORWARD_PAGES, plan.pageCount)
        val pageLimit = if (plan.maximumNumericBound) {
            minOf(
                absolutePageLimit,
                forwardFirstPage + initialSpeculationPages,
            )
        } else {
            absolutePageLimit
        }
        if (closed.get() || pageLimit == 0) return null
        // This runs only after the committed viewer click. It performs no network operation: the
        // exact-count document remains the authority gate for tail image GETs. A maximum-bound
        // plan materializes only its physical entry runway here; the document-completion stage
        // appends the real suffix and never constructs the synthetic 384-page tail.
        ReaderImageCache.prepareClickOwnedManhwaClientTopology()
        // Build the common binding once on the coordinator. Previously the first body worker
        // initialized this synchronized lazy value while every other worker contended on it during
        // the finite request fan-out.
        defaultJpgBinding
        val initialBodies = buildForwardBodyFutures(forwardFirstPage, pageLimit)
        Log.d(
            TAG,
            "click_forward_quarantine_wave path=${plan.normalizedEpisodePath}," +
                "pages=${initialBodies.size},initialScheduled=${initialBodies.size}," +
                "totalPages=${plan.pageCount},provisional=${plan.maximumNumericBound}," +
                "probeLanes=${NtkClickOwnedManhwaWavePolicy.PROBE_LANES}," +
                "earlyJpg=${earlyJpgCandidates.size}," +
                "immediateBodies=$initialSpeculationPages," +
                "formatVerifiedBodies=$FORMAT_VERIFIED_SPECULATIVE_PAGES," +
                "pipelined=true",
        )
        armHostGpuCurrentRestoredViewportBodyRelease(initialBodies)
        return Wave(initialBodies)
    }

    /**
     * Extends the click-owned entry runway only after the fresh document has replaced the numeric
     * maximum with its exact page count. The provisional futures are reused verbatim so p0-p4 can
     * overlap document I/O without creating duplicate producers.
     */
    private fun completeExactForwardWave(provisionalWave: Wave): Wave? {
        if (closed.get()) return null
        val exactPageLimit = minOf(MAX_CLICK_FORWARD_PAGES, effectivePageCount.get())
        val retainedProvisional = provisionalWave.futures
            .filterKeys { pageIndex -> pageIndex < exactPageLimit }
            .toSortedMap()
        val tailStart = maxOf(
            forwardFirstPage,
            (provisionalWave.futures.keys.maxOrNull() ?: (forwardFirstPage - 1)) + 1,
        )
        if (tailStart >= exactPageLimit) {
            return Wave(retainedProvisional)
        }
        val exactTail = buildForwardBodyFutures(tailStart, exactPageLimit)
        val exactBodies = java.util.TreeMap<Int, CompletableFuture<HeldBody?>>()
        exactBodies.putAll(retainedProvisional)
        exactBodies.putAll(exactTail)
        Log.d(
            TAG,
            "click_forward_quarantine_exact_wave path=${plan.normalizedEpisodePath}," +
                "entry=${retainedProvisional.size},tail=${exactTail.size}," +
                "exactPages=$exactPageLimit,scheduled=${exactBodies.size}",
        )
        return Wave(exactBodies)
    }

    private fun buildForwardBodyFutures(
        pageStart: Int,
        pageLimit: Int,
    ): Map<Int, CompletableFuture<HeldBody?>> {
        if (pageStart >= pageLimit || closed.get()) return emptyMap()
        if (earlyJpgCandidates.isEmpty()) {
            val directBodies = (pageStart until pageLimit).associateWith { pageIndex ->
                if (directWifiAdjacentOwned &&
                    pageIndex - forwardFirstPage in
                        0 until directWifiAdjacentPhysicalRunwayPages
                ) {
                    val targetCandidate = adjacentPredecessorComplete.thenCompose {
                        probeCandidates(
                            pageIndex,
                            NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS,
                        )
                    }
                    startDirectWifiAdjacentInheritedCandidate(
                        pageIndex,
                        targetCandidate,
                    )
                } else {
                    startBoundedNumericCandidate(pageIndex)
                }
            }
            return attachPrivatePredecodes(directBodies)
        }
        // Candidate and body stages are built only for this concrete range. Before document
        // authority that is the physical entry runway; afterwards it is the exact finite suffix.
        val candidateFutures = (pageStart until pageLimit).associateWith { pageIndex ->
            val earlyJpg = earlyJpgCandidates[pageIndex]
            if (earlyJpg == null) {
                if (plan.maximumNumericBound) {
                    // The click-time frontier intentionally probes only a bounded sample. Use its
                    // strongly dominant real extension as the preferred first body for the exact
                    // tail instead of making every page repeat JPG -> GIF -> full resolver. A
                    // mixed page still falls through the unchanged exhaustive resolver.
                    dominantTailExtension.thenApply { extension ->
                        extension?.let { candidateAsset(pageIndex, it) }
                    }
                } else {
                    probeCandidates(pageIndex, NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS)
                }
            } else {
                earlyJpg.handle { candidate, failure ->
                    if (failure == null) candidate else null
                }.thenCompose { jpgCandidate ->
                    if (pageIndex >= effectivePageCount.get()) {
                        CompletableFuture.completedFuture(null)
                    } else if (jpgCandidate != null || closed.get()) {
                        CompletableFuture.completedFuture(jpgCandidate)
                    } else {
                        // Keep a null hint distinct from a proven missing page. The body stage
                        // below races the canonical JPG with the bounded GIF fallback, covering
                        // both a transient HEAD transport failure and a genuine mixed-format page.
                        CompletableFuture.completedFuture(null)
                    }
                }
            }
        }
        val initialBodyFutures = candidateFutures.toSortedMap().mapValues { (pageIndex, candidateFuture) ->
            val forwardOffset = pageIndex - forwardFirstPage
            if (directWifiAdjacentOwned &&
                forwardOffset in 0 until directWifiAdjacentPhysicalRunwayPages
            ) {
                startDirectWifiAdjacentInheritedCandidate(pageIndex, candidateFuture)
            } else if (plan.maximumNumericBound) {
                // The head frontier starts at the committed click. Tail pages use this same race,
                // but startClickPrimaryCandidateRace keeps their body behind documentValidated.
                // Waiting for a slow metadata-only HEAD after the exact document was already
                // authoritative left the final finite pages idle for more than a second. Start the
                // common immutable JPG path immediately when that gate opens; a proven uncommon
                // extension can still win on the separate fallback executor and cancel it.
                if (forwardOffset >= NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES) {
                    startPreferredTailCandidate(pageIndex, candidateFuture)
                } else if (pageIndex == forwardFirstPage) {
                    startClickPrimaryCandidateRace(pageIndex, candidateFuture)
                } else if (
                    forwardOffset >= NtkClickOwnedManhwaWavePolicy.DIRECT_BODY_RACE_PAGES
                ) {
                    startVerifiedFrontierCandidate(pageIndex, candidateFuture)
                } else {
                    startClickPrimaryCandidateRace(pageIndex, candidateFuture)
                }
            } else {
                candidateFuture.thenCompose { candidate ->
                    if (closed.get()) {
                        CompletableFuture.completedFuture(null)
                    } else if (candidate == null) {
                        // Every parallel candidate missed or failed transiently. Re-run the bounded
                        // resolver before any body so an unproven filename cannot enter the manifest.
                        startCompletedHeadMissCandidate(pageIndex)
                    } else {
                        startResolvedCandidate(pageIndex, candidate)
                    }
                }
            }
        }
        return attachPrivatePredecodes(initialBodyFutures)
    }

    /**
     * Pages outside the metadata frontier are admitted only after exact document authority and
     * the first real frame. By then the bounded head sample can normally prove the volume's
     * dominant extension. Start that one body directly and retain the exhaustive resolver as the
     * failure path, avoiding dozens of known-wrong JPG/GIF attempts on a uniform JPEG volume.
     */
    private fun startPreferredTailCandidate(
        pageIndex: Int,
        candidateFuture: CompletableFuture<String?>,
    ): CompletableFuture<HeldBody?> {
        val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
        val preferredCandidate = candidateFuture.handle { candidate, failure ->
            if (failure == null && candidate != null) {
                candidate
            } else {
                candidateAsset(pageIndex, DEFAULT_EXTENSION)
            }
        }
        val preferred = preferredCandidate
            .thenCombine(tailAdmissionFuture(pageIndex, primaryCancellation)) {
                    candidate, admitted ->
                if (pageIndex == forwardFirstPage +
                    NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES ||
                    pageIndex == effectivePageCount.get() - 1
                ) {
                    Log.d(
                        TAG,
                        "click_preferred_tail_admission path=${plan.normalizedEpisodePath}," +
                            "page=$pageIndex,candidate=${candidate.substringAfterLast('/')}," +
                            "admitted=$admitted,effectivePages=${effectivePageCount.get()}," +
                            "closed=${closed.get()}",
                    )
                }
                if (admitted) candidate else null
            }
            .thenCompose { candidate ->
                if (candidate == null || closed.get()) {
                    CompletableFuture.completedFuture(null)
                } else {
                    CompletableFuture.supplyAsync(
                        {
                            fetchOwnedCandidate(
                                pageIndex,
                                candidate,
                                primaryCancellation,
                                telemetryAfterImageHeaders = true,
                            )
                        },
                        preferredTailBodyExecutor(pageIndex, candidate),
                    )
                }
            }
        return preferred.thenCompose { held ->
            if (held != null || closed.get() || pageIndex >= effectivePageCount.get()) {
                CompletableFuture.completedFuture(held)
            } else {
                startCompletedHeadMissCandidate(pageIndex)
            }
        }
    }

    /**
     * The sampled tail is never speculative: it can start only after the exact document and the
     * first real frame have both released the network gate. Bind it to those owner events directly.
     * Reusing the rolling-frontier future here left pages beyond that 120-page frontier completed
     * as null on a 168-page volume, handing the last 48 bodies back to the slower source actor.
     */
    private fun tailAdmissionFuture(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): CompletableFuture<Boolean> {
        val entryRelease = if (wifiEntryPriorityMode) {
            // A restored reader can start beyond the 120-page metadata frontier. Requiring its
            // initial page to wait for firstActualFramePresented creates a cycle: that frame needs
            // the same page's body. Let exactly the bound initial viewport page follow the already
            // protected anchor-resident network gate; every other tail page keeps the existing
            // first-frame/timeout gate. Cellular/SNI never enters this branch.
            val restoredViewportRelease = initialViewportPage.thenCompose { initialPageIndex ->
                if (pageIndex == initialPageIndex) networkRelease else wifiEntryReleaseGate
            }
            // Once the session identifies a restored tail viewport, its dedicated reserved body
            // lane protects that exact visible page. The remaining bodies are all from this same
            // current episode, so release them immediately instead of idling thirty-nine network
            // lanes until a UI commit. Adjacent work remains behind the separate full-completion
            // gate and cannot compete here. A normal page-zero launch keeps the physical-frame gate.
            val restoredTailRelease = initialViewportPage.thenCompose { initialPageIndex ->
                if (initialPageIndex - forwardFirstPage >=
                    NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES
                ) {
                    networkRelease
                } else {
                    restoredTailDrawableCommitted
                }
            }
            CompletableFuture.anyOf(
                wifiEntryReleaseGate,
                restoredViewportRelease,
                restoredTailRelease,
            )
                .thenApply { Unit }
        } else {
            networkRelease
        }
        val baseAdmission = documentValidated.thenCombine(entryRelease) { _, _ ->
            !closed.get() && pageIndex < effectivePageCount.get()
        }
        return baseAdmission.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, callCancellation)
        ) { baseAdmitted, adjacentAdmitted ->
            if (!baseAdmitted || !adjacentAdmitted) {
                false
            } else {
                runCatching {
                    callCancellation.throwIfCancelled()
                    true
                }.getOrDefault(false)
            }
        }.handle { admitted, failure ->
            failure == null && admitted == true
        }
    }

    private fun discardHeldBody(held: HeldBody) {
        if (!retained.remove(held.body.pageIndex, held)) return
        held.predecodedOriginal?.close()
        held.fileLease.close()
    }

    /**
     * Opens at most one predecessor-proven ordinary candidate for an adjacent runway page. The
     * target's own HEAD remains independent: a different physical suffix cancels and drains this
     * body before its exact candidate starts, while a miss uses the unchanged exhaustive fallback.
     */
    private fun startDirectWifiAdjacentInheritedCandidate(
        pageIndex: Int,
        candidateFuture: CompletableFuture<String?>,
    ): CompletableFuture<HeldBody?> = predecessorPhysicalEvidence.thenCompose { evidence ->
        val extension = evidence?.extension
        if (extension == null || closed.get()) {
            if (pageIndex == forwardFirstPage) {
                startClickPrimaryCandidateRace(pageIndex, candidateFuture)
            } else {
                startVerifiedFrontierCandidate(pageIndex, candidateFuture)
            }
        } else {
            val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
            val exactCancellation = checkNotNull(speculativeUncommonCancellations[pageIndex])
            val inheritedAdmissionHandoff = AtomicBoolean(false)
            val inheritedCandidate = candidateAsset(pageIndex, extension)
            val preferredWarmReplicaHost = NtkClickOwnedManhwaWavePolicy
                .preferredWarmAdjacentReplicaHost(
                    runwayPageIndex = pageIndex - forwardFirstPage,
                    predecessorWarmHosts = evidence.warmReplicaHosts,
                )
            val httpClient = getHttpClient()
            val predecessorProvenOrdinaryDirectWifi = NtkClickOwnedManhwaWavePolicy
                .shouldUseInheritedOrdinaryDirectWifiTransport(
                    directWifiAdjacentOwned = directWifiAdjacentOwned,
                    runwayPageIndex = pageIndex - forwardFirstPage,
                    inheritedExtension = extension,
                    liveWifiTransport = runCatching {
                        httpClient.isNtkWifiTransportActive
                    }.getOrDefault(false),
                    cellularResilientTransport = runCatching {
                        httpClient.isNtkCellularResilientTransportActive
                    }.getOrDefault(true),
                    capturedNetworkHandle = capturedDirectWifiNetworkHandle,
                    liveNetworkHandle = runCatching {
                        httpClient.getNtkDirectWifiNetwork()?.networkHandle
                    }.getOrNull(),
                )
            val inheritedHostHandoff = reserveInheritedAdjacentHostHandoff(
                inheritedCandidate,
                predecessorProvenOrdinaryDirectWifi,
                preferredWarmReplicaHost,
                pageIndex - forwardFirstPage,
                evidence.warmReplicaHosts,
            )
            val inheritedFollower = !inheritedHostHandoff.first.isDone
            if (!inheritedHostHandoff.first.isDone) {
                val inheritedHost = inheritedCandidate.substringAfter("://")
                    .substringBefore('/')
                Log.d(
                    TAG,
                    "click_adjacent_inherited_host_reuse_handoff " +
                        "path=${plan.normalizedEpisodePath},page=$pageIndex," +
                        "host=$inheritedHost",
                )
            }
            val inheritedExecutor = if (inheritedFollower) {
                // The predecessor page has already returned its H1 connection to the pool. Run the
                // bounded same-host follower in that completion turn instead of paying another
                // executor hop before reacquiring the just-idled connection.
                Executor { runnable -> runnable.run() }
            } else {
                primaryBodyExecutor(
                    pageIndex,
                    inheritedCandidate,
                    predecessorProvenOrdinaryDirectWifi,
                )
            }
            val inherited = primaryAdmissionFuture(pageIndex, primaryCancellation)
                .thenCombine(inheritedHostHandoff.first) { admitted, _ -> admitted }
                .thenApplyAsync({ admitted ->
                    if (!admitted || closed.get()) {
                        null
                    } else if (!isCapturedDirectWifiTransportLive()) {
                        inheritedAdmissionHandoff.set(true)
                        null
                    } else {
                        val held = fetchOwnedCandidate(
                            pageIndex,
                            inheritedCandidate,
                            primaryCancellation,
                            telemetryAfterImageHeaders = true,
                            requireCapturedDirectWifi = true,
                            predecessorProvenOrdinaryDirectWifi =
                                predecessorProvenOrdinaryDirectWifi,
                            preferredOrdinaryDirectWifiReplicaHost =
                                preferredWarmReplicaHost,
                        )
                        if (held == null && !isCapturedDirectWifiTransportLive()) {
                            inheritedAdmissionHandoff.set(true)
                        }
                        held
                    }
                }, inheritedExecutor)
                .handle { held, _ -> held }
            inherited.whenComplete { held, inheritedFailure ->
                if (inheritedFailure == null && held != null && !closed.get()) {
                    val signaled = directWifiAdjacentInheritedResidentBodies[pageIndex]
                        ?.complete(held) == true
                    Log.d(
                        TAG,
                        "click_adjacent_inherited_resident_ready path=${plan.normalizedEpisodePath}," +
                            "page=$pageIndex,signaled=$signaled",
                    )
                }
            }
            inheritedHostHandoff.second?.let { release ->
                inherited.whenComplete { _, _ -> release.complete(Unit) }
            }
            val started = SpeculativeBody(inheritedCandidate, inherited)

            fun startExactBody(candidate: String): CompletableFuture<HeldBody?> =
                primaryAdmissionFuture(pageIndex, exactCancellation)
                    .thenApplyAsync({ admitted ->
                        if (!admitted || closed.get()) {
                            null
                        } else {
                            fetchOwnedCandidate(
                                pageIndex,
                                candidate,
                                exactCancellation,
                                telemetryAfterImageHeaders = true,
                            )
                        }
                    }, verifiedExactBodyExecutor(pageIndex, candidate))
                    .handle { held, _ -> held }

            candidateFuture.handle { candidate, failure ->
                if (failure == null) candidate else null
            }.thenCompose { candidate ->
                if (manifestBoundResidentRunwayPages.contains(pageIndex)) {
                    // The fresh target manifest has already bound and adopted this exact body.
                    // A later sampled HEAD is weaker evidence and must not create a duplicate GET.
                    started.future
                } else if (inheritedAdmissionHandoff.get()) {
                    started.future.thenCompose { held ->
                        held?.let(::discardHeldBody)
                        when {
                            closed.get() -> CompletableFuture.completedFuture(null)
                            candidate != null -> startResolvedCandidate(pageIndex, candidate)
                            else -> CompletableFuture.completedFuture(null)
                        }
                    }
                } else if (candidate == null || started.candidate == candidate) {
                    started.future
                } else {
                    primaryCancellation.cancel()
                    // Never overlap two same-page ownership sessions. The predecessor candidate
                    // must leave quarantine before the target-HEAD candidate begins.
                    started.future.thenCompose { held ->
                        held?.let(::discardHeldBody)
                        if (closed.get()) {
                            CompletableFuture.completedFuture(null)
                        } else {
                            startExactBody(candidate)
                        }
                    }
                }
            }.thenCompose { held ->
                if (held != null || closed.get() || pageIndex >= effectivePageCount.get()) {
                    CompletableFuture.completedFuture(held)
                } else {
                    startCompletedHeadMissCandidate(pageIndex)
                }
            }
        }
    }

    private fun reserveInheritedAdjacentHostHandoff(
        candidate: String,
        enabled: Boolean,
        preferredWarmReplicaHost: String? = null,
        runwayPageIndex: Int = 0,
        predecessorWarmHosts: List<String> = emptyList(),
    ): Pair<CompletableFuture<Unit>, CompletableFuture<Unit>?> {
        if (!enabled) return CompletableFuture.completedFuture(Unit) to null
        val host = preferredWarmReplicaHost
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(NtkClickOwnedManhwaWavePolicy::isReplicaHost)
            ?: candidate.substringAfter("://", "").substringBefore('/').lowercase(Locale.ROOT)
        if (host.isBlank()) return CompletableFuture.completedFuture(Unit) to null
        val runwayPageCount = minOf(
            directWifiAdjacentPhysicalRunwayPages,
            effectivePageCount.get(),
        )
        val warmPreviousPage = NtkClickOwnedManhwaWavePolicy.previousWarmAdjacentReplicaPage(
            runwayPageIndex,
            runwayPageCount,
            predecessorWarmHosts,
        )
        val parallelHostGpuFollower = NtkClickOwnedManhwaWavePolicy
            .shouldParallelizeHostGpuAdjacentFollower(
                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                runwayPageIndex = runwayPageIndex,
                runwayPageCount = runwayPageCount,
                previousWarmPage = warmPreviousPage,
            )
        val previousPage = warmPreviousPage.takeUnless { parallelHostGpuFollower }
        if (parallelHostGpuFollower) {
            Log.d(
                TAG,
                "click_adjacent_host_gpu_follower_parallel " +
                    "path=${plan.normalizedEpisodePath},page=$runwayPageIndex," +
                    "previous=$warmPreviousPage,host=$host",
            )
        }
        synchronized(inheritedAdjacentPageReleases) {
            val release = inheritedAdjacentPageReleases.computeIfAbsent(runwayPageIndex) {
                CompletableFuture()
            }
            val admission = previousPage?.let { page ->
                inheritedAdjacentPageReleases.computeIfAbsent(page) {
                    CompletableFuture()
                }
            } ?: CompletableFuture.completedFuture(Unit)
            return admission to release
        }
    }

    /**
     * Opens one body URL after the click-time sample has proved the volume's preferred extension.
     *
     * The entry viewport still uses [startClickPrimaryCandidateRace], so a common JPG volume never
     * delays its first image behind HEAD. Pages outside that four-page viewport already wait for
     * their exact per-page admission; racing an unproven JPG there only created cancelled bodies,
     * reset H2 streams, and delayed the verified JPEG/PNG/GIF request on the same cold transport.
     */
    private fun startVerifiedFrontierCandidate(
        pageIndex: Int,
        candidateFuture: CompletableFuture<String?>,
    ): CompletableFuture<HeldBody?> {
        val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
        val earlyVerifiedCancellation =
            checkNotNull(speculativeUncommonCancellations[pageIndex])
        val admission = primaryAdmissionFuture(pageIndex, primaryCancellation)

        // Once both the exact-count document and this page's HEAD have proved the same PNG URL,
        // its private body no longer needs to sit idle behind the anchor-resident presentation gate.
        // This starts no guessed URL and changes no adoption order: the ordinary per-page admission
        // below must still open before the completed body can enter the exact stream.
        val earlyVerifiedCandidate = if (!directWifiEarlyUncommonEnabled) {
            CompletableFuture.completedFuture<String?>(null)
        } else {
            candidateFuture
                .handle { candidate, failure ->
                    if (failure == null) candidate else null
                }
                .thenCombine(documentValidated.handle { _, failure -> failure == null }) {
                        candidate, documentIsValid ->
                    val liveUncommonExtension =
                        if (documentIsValid && candidate != null && !closed.get() &&
                            pageIndex < effectivePageCount.get()
                        ) {
                            ReaderImageCache.directWifiMixedManhwaSpeculativeUncommonExtension(
                                candidate
                            )
                        } else {
                            null
                        }
                    if (candidate != null &&
                        candidate.substringAfterLast('.')
                            .equals(liveUncommonExtension, ignoreCase = true)
                    ) {
                        candidate
                    } else {
                        null
                    }
                }
        }
        val gatedEarlyVerifiedCandidate = earlyVerifiedCandidate.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, earlyVerifiedCancellation)
        ) { candidate, adjacentAdmitted ->
            candidate?.takeIf { adjacentAdmitted }
        }
        val earlyVerified = gatedEarlyVerifiedCandidate.thenCompose { candidate ->
                if (candidate == null) {
                    CompletableFuture.completedFuture(
                        SpeculativeBody(
                            null,
                            CompletableFuture.completedFuture(null),
                        )
                    )
                } else {
                    Log.d(
                        TAG,
                        "click_verified_frontier_early_uncommon " +
                            "path=${plan.normalizedEpisodePath},page=$pageIndex," +
                            "candidate=${candidate.substringAfterLast('/')}",
                    )
                    val body = CompletableFuture.supplyAsync(
                        {
                            fetchOwnedCandidate(
                                pageIndex,
                                candidate,
                                earlyVerifiedCancellation,
                                telemetryAfterImageHeaders = true,
                                restoredAnchorOrdinaryDirectWifi =
                                    isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                            )
                        },
                        fallbackBodyExecutor(pageIndex),
                    ).handle { held, _ -> held }
                    CompletableFuture.completedFuture(SpeculativeBody(candidate, body))
                }
            }

        fun startExactBody(candidate: String): CompletableFuture<HeldBody?> =
            CompletableFuture.supplyAsync(
                {
                    fetchOwnedCandidate(
                        pageIndex,
                        candidate,
                        primaryCancellation,
                        telemetryAfterImageHeaders = true,
                        restoredAnchorOrdinaryDirectWifi =
                            isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                    )
                },
                verifiedExactBodyExecutor(pageIndex, candidate),
            )

        val verified = candidateFuture
            .handle { candidate, failure ->
                if (failure == null) candidate else null
            }
            .thenCombine(admission) { candidate, admitted ->
                if (admitted) candidate else null
            }
            .thenCompose { candidate ->
                if (candidate == null || closed.get()) {
                    earlyVerifiedCancellation.cancel()
                    earlyVerified.thenCompose { started ->
                        started.future.thenApply { held ->
                            held?.let(::discardHeldBody)
                            null
                        }
                    }
                } else {
                    earlyVerified.thenCompose { started ->
                        if (started.candidate == candidate) {
                            started.future.thenCompose { held ->
                                if (held != null) {
                                    observedCandidates[pageIndex]?.complete(candidate)
                                    releaseSpeculationDebt(pageIndex)
                                    CompletableFuture.completedFuture(held)
                                } else if (closed.get()) {
                                    CompletableFuture.completedFuture(null)
                                } else {
                                    startExactBody(candidate)
                                }
                            }
                        } else {
                            earlyVerifiedCancellation.cancel()
                            // Wait for the discarded binding to leave the ownership registry before
                            // beginning the HEAD-proved candidate on its independent cancellation.
                            started.future.thenCompose { held ->
                                held?.let(::discardHeldBody)
                                if (closed.get()) {
                                    CompletableFuture.completedFuture(null)
                                } else {
                                    startExactBody(candidate)
                                }
                            }
                        }
                    }
                }
            }
        return verified.thenCompose { held ->
            if (held != null || closed.get() || pageIndex >= effectivePageCount.get()) {
                CompletableFuture.completedFuture(held)
            } else {
                startCompletedHeadMissCandidate(pageIndex)
            }
        }
    }

    /**
     * Starts final-pixel work as soon as each definitive post-click body reaches EOF. The bitmap is
     * a one-shot private handoff: it cannot reach ReaderSession until the exact manifest adopts this
     * same body identity. This overlaps JPEG CPU with the remaining network wave instead of leaving
     * a multi-second decode tail after the last response, while keeping the visible first page on a
     * dedicated lane and bounding the bulk work to the emulator's host CPU capacity.
     */
    private fun attachPrivatePredecodes(
        bodies: Map<Int, CompletableFuture<HeldBody?>>,
    ): Map<Int, CompletableFuture<HeldBody?>> = bodies.mapValues { (pageIndex, bodyFuture) ->
        bodyFuture.thenApply { held ->
            if (held == null || closed.get()) {
                held?.let(::discardHeldBody)
                null
            } else if (manifestBoundResidentRunwayPages.contains(pageIndex)) {
                // The raw inherited completion already won the exact-manifest race. Its lease was
                // consumed by that adoption; never retain or predecode the later HEAD wrapper.
                null
            } else if (NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                    hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                    directWifiAdjacentOwned = directWifiAdjacentOwned,
                    encodedBytesAvailable = held.body.encodedBytes != null,
                    sealedFileAvailable = held.body.sealedFile.isFile,
                )
            ) {
                // This is the click-race counterpart of the resident-adoption guard. Adjacent
                // p0-p4 also pass through this generic wave before the exact manifest adopts
                // them; starting their private Bitmap decodes here caused one NativeAlloc GC per
                // chapter even though the sealed file was immediately decoded into pooled AHBs.
                retained[pageIndex] = held
                held
            } else if (pageIndex - forwardFirstPage >= PRIVATE_PREDECODE_RUNWAY_PAGES) {
                // Exact-manifest promotion normally precedes the bulk body tail. Starting a
                // second speculative full-size Bitmap for every one of 100-200 pages therefore
                // races the authoritative twelve-lane decoder for the same immutable bytes. If
                // the speculative decode has not completed at lease-open time it is abandoned,
                // but an already-running BitmapFactory call cannot be interrupted and briefly
                // doubles native pixel allocation. The resulting NativeAlloc GC pause was
                // visible as a 303 ms presentation gap in the 200-page cold traversal.
                //
                // Keep the finite entry runway overlapped so the first physical flings cannot
                // outrun pixels. Every later exact body is still decoded immediately by the
                // authoritative source session; it simply has one pixel producer instead of two.
                retained[pageIndex] = held
                held
            } else {
                val executor = if (pageIndex == forwardFirstPage) {
                    ANCHOR_PREDECODE_EXECUTOR
                } else {
                    BULK_PREDECODE_EXECUTOR
                }
                val predecoded = ReaderImageCache.predecodeQuarantinedOriginalAsync(
                    held.body,
                    executor,
                )
                held.copy(predecodedOriginal = predecoded).also { prepared ->
                    retained[pageIndex] = prepared
                    retained.remove(pageIndex, held)
                }
            }
        }
    }

    /**
     * Starts the common JPG body at the click while the metadata-only race resolves uncommon
     * formats. Only one valid body can win: JPG volumes avoid a HEAD round trip, while a PNG/GIF
     * page cancels the failed JPG call and adopts its exact alternative. Pages beyond the useful
     * speculative frontier wait for the fresh document so a short volume does not emit body GETs.
     */
    private fun startClickPrimaryCandidateRace(
        pageIndex: Int,
        candidateFuture: CompletableFuture<String?>,
    ): CompletableFuture<HeldBody?> {
        val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
        val alternativeCancellation = checkNotNull(fallbackCancellations[pageIndex])

        val canonicalCandidate = candidateAsset(pageIndex, DEFAULT_EXTENSION)
        // Local request/binding preparation may overlap document I/O, but a BODY_EXECUTOR thread
        // is never parked waiting for admission. Once both local preparation and the page event
        // complete, the actual GET is submitted as a fresh runnable to the now-free executor.
        val preparedPrimary = CompletableFuture.supplyAsync(
            { prepareOwnedCandidate(pageIndex, canonicalCandidate) },
            BODY_EXECUTOR,
        )
        val primary = preparedPrimary
            .thenCombine(primaryAdmissionFuture(pageIndex, primaryCancellation)) {
                    prepared, admitted ->
                if (admitted) prepared else null
            }
            .thenApplyAsync({ prepared ->
                if (prepared == null || closed.get()) {
                    null
                } else {
                    fetchOwnedCandidate(
                        pageIndex,
                        canonicalCandidate,
                        primaryCancellation,
                        telemetryAfterImageHeaders = true,
                        preparedCandidate = prepared,
                        restoredAnchorOrdinaryDirectWifi =
                            isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                    )
                }
            }, primaryBodyExecutor(pageIndex, canonicalCandidate))
        val alternative = candidateFuture.handle { candidate, failure ->
            if (failure == null) candidate else null
        }.thenCompose { candidate ->
            if (candidate == null || closed.get() ||
                candidate.substringAfterLast('.').equals(DEFAULT_EXTENSION, ignoreCase = true)
            ) {
                CompletableFuture.completedFuture(null)
            } else {
                // Candidate completion runs on the metadata event itself. Cancel here, before
                // enqueueing on the bounded fallback executor, so one cancellation cannot sit
                // behind 112 alternative-body runnables while their speculative JPG calls fan
                // out. The proven body still waits for the normal per-page admission below.
                primaryCancellation.cancel()
                // Never park a fallback worker on numeric admission. The sampled extension for
                // pages 8..119 can resolve before the initial-viewport sample; if those tail
                // runnables occupy FALLBACK_BODY_EXECUTOR while waiting for first-actual release,
                // a valid p002.jpeg sits behind them and the renderer cannot cover the first
                // viewport needed to produce that very first actual frame. Attach to the page
                // event first and submit physical work only after admission, exactly like the
                // primary path above.
                primaryAdmissionFuture(pageIndex, alternativeCancellation)
                    .thenApplyAsync({ admitted ->
                        if (!admitted || closed.get()) {
                            null
                        } else {
                            fetchOwnedCandidate(
                                pageIndex,
                                candidate,
                                alternativeCancellation,
                                telemetryAfterImageHeaders = true,
                                restoredAnchorOrdinaryDirectWifi =
                                    isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                            )
                        }
                    }, fallbackBodyExecutor(pageIndex))
            }
        }
        return firstSuccessfulBody(
            primary,
            alternative,
            primaryCancellation,
            alternativeCancellation,
        ).thenCompose { held ->
            if (held != null || closed.get() || pageIndex >= effectivePageCount.get()) {
                CompletableFuture.completedFuture(held)
            } else {
                startCompletedHeadMissCandidate(pageIndex)
            }
        }
    }

    /** Downloads one HEAD-proven immutable asset through one logical replica-failover call. */
    private fun startResolvedCandidate(
        pageIndex: Int,
        candidate: String,
    ): CompletableFuture<HeldBody?> {
        val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
        val admission = networkRelease.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, primaryCancellation)
        ) { _, adjacentAdmitted -> adjacentAdmitted }
        return admission.thenApplyAsync(
            { admitted ->
                if (!admitted || closed.get() || pageIndex >= effectivePageCount.get()) null
                else fetchOwnedCandidate(
                        pageIndex,
                        candidate,
                        primaryCancellation,
                        telemetryAfterImageHeaders = true,
                        restoredAnchorOrdinaryDirectWifi =
                            isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                    )
            },
            primaryBodyExecutor(pageIndex, candidate),
        )
    }

    /** Re-resolves the finite extension set after the parallel HEAD race completed without a hit. */
    private fun startCompletedHeadMissCandidate(pageIndex: Int): CompletableFuture<HeldBody?> {
        val fallbackCancellation = checkNotNull(fallbackCancellations[pageIndex])
        // networkRelease is completed by this same anchor body's terminal result. If its inherited
        // H1 Call hits a transient reset and the fallback waits for networkRelease, p0 waits for
        // itself forever and exact discovery can never hand the page to the strict source. The
        // document proof is the preceding authority gate for only that direct-WiFi adjacent p0;
        // predecessor completion remains independently enforced below. Every suffix/current path
        // retains the ordinary network-release fence.
        val fallbackNetworkAdmission = if (
            NtkClickOwnedAnchorFallbackAdmissionPolicy.useDocumentValidatedGate(
                directWifiAdjacentOwned,
                pageIndex,
                forwardFirstPage,
            )
        ) {
            documentValidated
        } else {
            networkRelease
        }
        val admission = fallbackNetworkAdmission.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, fallbackCancellation)
        ) { _, adjacentAdmitted -> adjacentAdmitted }
        val result = admission.thenCompose { admitted ->
            if (!admitted || closed.get() || pageIndex >= effectivePageCount.get()) {
                CompletableFuture.completedFuture(null)
            } else {
                // Keep JPG in the list so a transient HEAD transport failure remains recoverable;
                // a real 404 advances immediately to GIF/WebP/PNG/JPEG without a failed body GET.
                probeCandidates(
                    pageIndex,
                    NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS,
                    fallbackCancellation,
                ).thenCompose { candidate ->
                    if (candidate == null || closed.get()) {
                        CompletableFuture.completedFuture(null)
                    } else {
                        CompletableFuture.supplyAsync(
                            {
                                fetchOwnedCandidate(
                                    pageIndex,
                                    candidate,
                                    fallbackCancellation,
                                    telemetryAfterImageHeaders = true,
                                    restoredAnchorOrdinaryDirectWifi =
                                        isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                                )
                            },
                            fallbackBodyExecutor(pageIndex),
                        )
                    }
                }
            }
        }
        result.whenComplete { held, failure ->
            if ((failure != null || held == null) && pageIndex < effectivePageCount.get()) {
                observedCandidates[pageIndex]?.completeExceptionally(
                    failure ?: IllegalStateException(
                        "No valid unproven JPG/fallback response for page $pageIndex"
                    )
                )
            }
        }
        return result
    }

    /**
     * Starts the common JPG body at click time instead of paying a separate HEAD round trip.
     * Only a 2xx image response is promoted to canonical image telemetry. A numeric miss waits
     * for the fresh document's exact page boundary before trying the uncommon extensions, while
     * lanes beyond that boundary are cancelled and never consume an image body.
     */
    private fun startBoundedNumericCandidate(pageIndex: Int): CompletableFuture<HeldBody?> {
        val primaryCancellation = checkNotNull(pageCancellations[pageIndex])
        val fallbackCancellation = checkNotNull(fallbackCancellations[pageIndex])
        val primaryAdmission = networkRelease.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, primaryCancellation)
        ) { _, adjacentAdmitted -> adjacentAdmitted }
        val primary = primaryAdmission.thenApplyAsync(
            { admitted ->
                if (!admitted || closed.get()) {
                    null
                } else {
                    fetchOwnedCandidate(
                        pageIndex,
                        candidateAsset(pageIndex, DEFAULT_EXTENSION),
                        primaryCancellation,
                        telemetryAfterImageHeaders = true,
                        restoredAnchorOrdinaryDirectWifi =
                            isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                    )
                }
            },
            primaryBodyExecutor(pageIndex, candidateAsset(pageIndex, DEFAULT_EXTENSION)),
        )
        // The fresh document arrives while the common JPG body is still transferring. Resolve
        // uncommon extensions with metadata-only HEADs at that point instead of waiting for a
        // slow three-replica JPG 404 to finish. Only the one successful extension performs GET.
        val fallback = documentValidated.handle { _, failure -> failure == null }
            .thenCombine(adjacentPhysicalAdmissionFuture(pageIndex, fallbackCancellation)) {
                    documentIsValid, adjacentAdmitted ->
                documentIsValid && adjacentAdmitted
            }
            .thenCompose { valid ->
                if (!valid || closed.get() || pageIndex >= effectivePageCount.get()) {
                    CompletableFuture.completedFuture(null)
                } else {
                    // GIF is the only production mixed-format page observed on the critical
                    // path and is cheap to test. Probing every uncommon extension for every JPG
                    // page created hundreds of needless HEAD streams and slowed the 29 MB body
                    // wave. Probe GIF alone in parallel; rarer formats run only after both common
                    // candidates have actually missed.
                    probeCandidates(
                        pageIndex,
                        listOf(NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS[1]),
                        fallbackCancellation,
                    ).thenCompose { candidate ->
                        if (candidate == null || closed.get()) {
                            CompletableFuture.completedFuture(null)
                        } else {
                            CompletableFuture.supplyAsync(
                                {
                                    fetchOwnedCandidate(
                                        pageIndex,
                                        candidate,
                                        fallbackCancellation,
                                        telemetryAfterImageHeaders = true,
                                        restoredAnchorOrdinaryDirectWifi =
                                            isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                                    )
                                },
                                fallbackBodyExecutor(pageIndex),
                            )
                        }
                    }
                }
            }
        val result = firstSuccessfulBody(
            primary,
            fallback,
            primaryCancellation,
            fallbackCancellation,
        ).thenCompose { commonBody ->
            if (commonBody != null || closed.get() || pageIndex >= effectivePageCount.get()) {
                CompletableFuture.completedFuture(commonBody)
            } else {
                probeCandidates(
                    pageIndex,
                    NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.drop(2),
                    fallbackCancellation,
                ).thenCompose { candidate ->
                    if (candidate == null || closed.get()) {
                        CompletableFuture.completedFuture(null)
                    } else {
                        CompletableFuture.supplyAsync(
                            {
                                fetchOwnedCandidate(
                                    pageIndex,
                                candidate,
                                fallbackCancellation,
                                telemetryAfterImageHeaders = true,
                                restoredAnchorOrdinaryDirectWifi =
                                    isRestoredOrdinaryDirectWifiRunwayPage(pageIndex),
                                )
                            },
                            fallbackBodyExecutor(pageIndex),
                        )
                    }
                }
            }
        }
        result.whenComplete { held, failure ->
            if ((failure != null || held == null) && pageIndex < effectivePageCount.get()) {
                observedCandidates[pageIndex]?.completeExceptionally(
                    failure ?: IllegalStateException(
                        "No valid numeric replica image response for page $pageIndex"
                    )
                )
            }
        }
        return result
    }

    private fun firstSuccessfulBody(
        primary: CompletableFuture<HeldBody?>,
        fallback: CompletableFuture<HeldBody?>,
        primaryCancellation: ReaderImageCache.Cancellation,
        fallbackCancellation: ReaderImageCache.Cancellation,
    ): CompletableFuture<HeldBody?> {
        val result = CompletableFuture<HeldBody?>()
        val remaining = AtomicInteger(2)
        fun observe(
            future: CompletableFuture<HeldBody?>,
            cancelOther: ReaderImageCache.Cancellation,
        ) {
            future.whenComplete { held, _ ->
                if (held != null && result.complete(held)) {
                    cancelOther.cancel()
                } else if (held != null) {
                    // A cancelled peer can still finish EOF concurrently with the winner. It has
                    // no exact consumer and must not retain either its private bitmap or file.
                    discardHeldBody(held)
                } else if (remaining.decrementAndGet() == 0) {
                    result.complete(null)
                }
            }
        }
        observe(primary, fallbackCancellation)
        observe(fallback, primaryCancellation)
        return result
    }

    private fun probeCandidates(
        pageIndex: Int,
        extensions: List<String>,
        probeCancellation: ReaderImageCache.Cancellation = cancellation,
    ): CompletableFuture<String?> = CompletableFuture.supplyAsync(
        {
            if (closed.get()) return@supplyAsync null
            ReaderImageCache.probeClickOwnedManhwaReplicaAsset(
                manga,
                pageIndex,
                extensions.map { extension -> candidateAsset(pageIndex, extension) },
                probeCancellation,
            )
        },
        PROBE_EXECUTOR,
    )

    private fun fetchOwnedCandidate(
        pageIndex: Int,
        candidate: String,
        callCancellation: ReaderImageCache.Cancellation = cancellation,
        telemetryAfterImageHeaders: Boolean = false,
        onValidImageHeaders: () -> Unit = {},
        preparedCandidate: PreparedCandidate? = null,
        requireCapturedDirectWifi: Boolean = false,
        predecessorProvenOrdinaryDirectWifi: Boolean = false,
        restoredAnchorOrdinaryDirectWifi: Boolean = false,
        preferredOrdinaryDirectWifiReplicaHost: String? = null,
    ): HeldBody? {
        val prepared = preparedCandidate ?: prepareOwnedCandidate(pageIndex, candidate)
        if (requireCapturedDirectWifi && !isCapturedDirectWifiTransportLive()) return null
        val binding = prepared.binding
        val sourceSessionId = SESSION_IDS.getAndIncrement()
        NtkQuarantineSourceOwnershipRegistry.beginSession(binding, sourceSessionId)
        return try {
            fetchIntoQuarantine(
                binding,
                sourceSessionId,
                pageIndex,
                candidate,
                callCancellation,
                telemetryAfterImageHeaders,
                onValidImageHeaders,
                prepared.route,
                requireCapturedDirectWifi,
                predecessorProvenOrdinaryDirectWifi,
                restoredAnchorOrdinaryDirectWifi,
                preferredOrdinaryDirectWifiReplicaHost,
            )
        } finally {
            closeOwnership(binding, sourceSessionId)
        }
    }

    private fun prepareOwnedCandidate(
        pageIndex: Int,
        candidate: String,
    ): PreparedCandidate {
        val binding = if (candidate == candidateAsset(pageIndex, DEFAULT_EXTENSION)) {
            defaultJpgBinding
        } else {
            pageBinding(pageIndex, candidate)
        }
        val directWifiAdjacentRunway = directWifiAdjacentOwned &&
            capturedDirectWifiNetworkHandle != null &&
            runCatching { getHttpClient().getNtkDirectWifiNetwork()?.networkHandle }
                .getOrNull() == capturedDirectWifiNetworkHandle &&
            pageIndex in 0 until directWifiAdjacentPhysicalRunwayPages
        val probeWarmAdjacentRunway = directWifiAdjacentRunway &&
            runCatching { earlyJpgCandidates[pageIndex]?.getNow(null) }
                .getOrNull() == candidate
        return PreparedCandidate(
            binding,
            ReaderImageCache.resolveClickOwnedAnchorQuarantineRoute(
                manga,
                binding,
                pageIndex,
                candidate,
                preferProbeWarmRoute = probeWarmAdjacentRunway,
                enableProofBackedExactReplicaRoute = directWifiAdjacentRunway,
            ),
        )
    }

    private fun acquireBodyTransferPermit(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): Boolean {
        val permits = if (pageIndex == forwardFirstPage) {
            anchorBodyTransferPermit
        } else {
            bodyTransferPermits
        }
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (permits.tryAcquire(BODY_TRANSFER_PERMIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                try {
                    callCancellation.throwIfCancelled()
                    if (!closed.get()) return true
                } catch (failure: Throwable) {
                    permits.release()
                    throw failure
                }
                permits.release()
                return false
            }
        }
        return false
    }

    private fun acquireBodyTransferLease(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): Closeable {
        if (!acquireBodyTransferPermit(pageIndex, callCancellation)) {
            throw InterruptedException("Click-owned body admission closed")
        }
        val permits = if (pageIndex == forwardFirstPage) {
            anchorBodyTransferPermit
        } else {
            bodyTransferPermits
        }
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true)) permits.release()
        }
    }

    private fun acquireMixedUncommonTransferLease(
        candidate: String,
        callCancellation: ReaderImageCache.Cancellation,
    ): Closeable? {
        val uncommonExtension =
            ReaderImageCache.directWifiMixedManhwaSpeculativeUncommonExtension(candidate)
                ?: return null
        if (!candidate.substringAfterLast('.').equals(uncommonExtension, ignoreCase = true)) {
            return null
        }
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (mixedUncommonTransferPermits.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                )
            ) {
                val released = AtomicBoolean(false)
                return Closeable {
                    if (released.compareAndSet(false, true)) {
                        mixedUncommonTransferPermits.release()
                    }
                }
            }
        }
        throw InterruptedException("Click-owned mixed uncommon admission closed")
    }

    private fun acquireHostGpuAdjacentTailBodyTransferLease(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): Closeable? {
        if (!NtkClickOwnedManhwaWavePolicy.shouldBoundHostGpuAdjacentTailTransfers(
                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                physicalRunwayPages = directWifiAdjacentPhysicalRunwayPages,
            )
        ) return null
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (hostGpuAdjacentTailBodyTransferPermits.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                )
            ) {
                val released = AtomicBoolean(false)
                return Closeable {
                    if (released.compareAndSet(false, true)) {
                        hostGpuAdjacentTailBodyTransferPermits.release()
                    }
                }
            }
        }
        throw InterruptedException("Click-owned adjacent tail admission closed")
    }

    private fun armHostGpuCurrentRestoredViewportBodyRelease(
        bodyFutures: Map<Int, CompletableFuture<HeldBody?>>,
    ) {
        if (!hostGpuCurrentRestoredViewportPriority ||
            hostGpuCurrentRestoredViewportBodyRelease.isDone
        ) return
        val viewportFutures = bodyFutures.entries
            .asSequence()
            .filter { (pageIndex, _) ->
                NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(
                    pageIndex = pageIndex,
                    forwardFirstPage = forwardFirstPage,
                    pageCount = plan.pageCount,
                )
            }
            .sortedBy { it.key }
            .map { it.value }
            .toList()
        check(viewportFutures.isNotEmpty()) {
            "Restored viewport body fence has no owned current bodies"
        }
        CompletableFuture.allOf(*viewportFutures.toTypedArray()).whenComplete { _, _ ->
            if (!closed.get() && hostGpuCurrentRestoredViewportBodyRelease.complete(Unit)) {
                Log.d(
                    TAG,
                    "click_current_restored_viewport_bodies_terminal " +
                        "path=${plan.normalizedEpisodePath},first=$forwardFirstPage," +
                        "count=${viewportFutures.size}",
                )
            }
        }
    }

    private fun awaitHostGpuCurrentRestoredViewportBodyAdmission(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ) {
        if (!hostGpuCurrentRestoredViewportPriority ||
            NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                pageCount = plan.pageCount,
            )
        ) return
        while (!closed.get() && !hostGpuCurrentRestoredViewportBodyRelease.isDone) {
            callCancellation.throwIfCancelled()
            // The fence is deliberately profile-local. A Wi-Fi -> cellular/SNI handoff restores
            // the existing admission topology instead of carrying an emulator Wi-Fi policy across.
            if (!isCapturedDirectWifiTransportLive()) return
            try {
                hostGpuCurrentRestoredViewportBodyRelease.get(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: java.util.concurrent.TimeoutException) {
                // Poll only for cancellation and a live network-profile transition.
            }
        }
        if (closed.get()) throw java.util.concurrent.CancellationException("quarantine closed")
        callCancellation.throwIfCancelled()
    }

    private fun acquireHostGpuCurrentRestoredBulkBodyTransferLease(
        pageIndex: Int,
        candidate: String,
        callCancellation: ReaderImageCache.Cancellation,
        predecessorProvenOrdinaryDirectWifi: Boolean,
        restoredAnchorOrdinaryDirectWifi: Boolean,
    ): CurrentRestoredBulkBodyLease? {
        if (!hostGpuCurrentRestoredViewportPriority ||
            NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                pageCount = plan.pageCount,
            )
        ) return null
        var totalLease: Closeable? = null
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (!isCapturedDirectWifiTransportLive()) {
                hostGpuCurrentRestoredBulkAdmission.freeze(
                    "profile_changed_before_outer_admission",
                )
                return null
            }
            if (hostGpuCurrentRestoredTotalBulkBodyTransferPermits.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                )
            ) {
                val released = AtomicBoolean(false)
                totalLease = Closeable {
                    if (released.compareAndSet(false, true)) {
                        hostGpuCurrentRestoredTotalBulkBodyTransferPermits.release()
                    }
                }
                break
            }
        }
        val ownedTotal = totalLease
            ?: throw InterruptedException("Click-owned restored current bulk admission closed")
        var pendingAdaptive: NtkAdaptiveManhwaBulkAdmission.Lease? = null
        try {
            while (!closed.get()) {
                callCancellation.throwIfCancelled()
                if (!isCapturedDirectWifiTransportLive()) {
                    hostGpuCurrentRestoredBulkAdmission.freeze(
                        "profile_changed_before_adaptive_admission",
                    )
                    ownedTotal.close()
                    return null
                }
                val adaptive = isLiveOrdinaryDirectWifiCandidate(
                    pageIndex,
                    candidate,
                    predecessorProvenOrdinaryDirectWifi,
                    restoredAnchorOrdinaryDirectWifi,
                )
                if (!adaptive) {
                    hostGpuCurrentRestoredBulkAdmission.freeze(
                        "ordinary_classification_changed_before_admission",
                    )
                    return CurrentRestoredBulkBodyLease(ownedTotal, null)
                }
                val lease = hostGpuCurrentRestoredBulkAdmission.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                ) ?: continue
                pendingAdaptive = lease
                if (!isCapturedDirectWifiTransportLive() ||
                    !isLiveOrdinaryDirectWifiCandidate(
                        pageIndex,
                        candidate,
                        predecessorProvenOrdinaryDirectWifi,
                        restoredAnchorOrdinaryDirectWifi,
                    )
                ) {
                    lease.disqualified(
                        if (!isCapturedDirectWifiTransportLive()) {
                            "profile_changed_after_adaptive_admission"
                        } else {
                            "ordinary_classification_changed_after_admission"
                        },
                    )
                    pendingAdaptive = null
                    if (!isCapturedDirectWifiTransportLive()) {
                        ownedTotal.close()
                        return null
                    }
                    return CurrentRestoredBulkBodyLease(ownedTotal, null)
                }
                val wrapper = CurrentRestoredBulkBodyLease(ownedTotal, lease)
                pendingAdaptive = null
                return wrapper
            }
        } catch (failure: Throwable) {
            try {
                pendingAdaptive?.aborted()
            } finally {
                ownedTotal.close()
            }
            throw failure
        }
        pendingAdaptive?.aborted()
        ownedTotal.close()
        throw InterruptedException("Click-owned restored current bulk admission closed")
    }

    private fun acquireOrdinaryDirectWifiTransferLease(
        pageIndex: Int,
        candidate: String,
        callCancellation: ReaderImageCache.Cancellation,
        route: ReaderImageCache.NtkResolvedSourceRoute,
        predecessorProvenOrdinaryDirectWifi: Boolean = false,
        restoredAnchorOrdinaryDirectWifi: Boolean = false,
        preferredOrdinaryDirectWifiReplicaHost: String? = null,
    ): Closeable? {
        if (!isLiveOrdinaryDirectWifiCandidate(
                pageIndex,
                candidate,
                predecessorProvenOrdinaryDirectWifi,
                restoredAnchorOrdinaryDirectWifi,
            )
        ) {
            ReaderImageCache.selectDirectWifiOrdinaryNetworkBoundH1(route, false)
            return null
        }
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (!isLiveOrdinaryDirectWifiCandidate(
                    pageIndex,
                    candidate,
                    predecessorProvenOrdinaryDirectWifi,
                    restoredAnchorOrdinaryDirectWifi,
                )
            ) {
                ReaderImageCache.selectDirectWifiOrdinaryNetworkBoundH1(route, false)
                return null
            }
            val initialPage = initialViewportPage.getNow(-1)
            val restoredViewportPriority = NtkClickOwnedManhwaWavePolicy
                .shouldUseDirectWifiRestoredViewportLane(
                    wifiTransport = wifiEntryPriorityMode,
                    pageIndex = pageIndex,
                    initialViewportPage = initialPage,
                )
            val visiblePriority = pageIndex == forwardFirstPage || restoredViewportPriority
            val mayBorrowViewportLane = !visiblePriority &&
                initialPage in forwardFirstPage until
                    (forwardFirstPage + NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES)
            val acquiredPermits = when {
                visiblePriority && directWifiViewportBodyTransferPermit.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                ) -> directWifiViewportBodyTransferPermit
                ordinaryDirectWifiBodyTransferPermits.tryAcquire(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                ) -> ordinaryDirectWifiBodyTransferPermits
                mayBorrowViewportLane && directWifiViewportBodyTransferPermit.tryAcquire() ->
                    directWifiViewportBodyTransferPermit
                else -> null
            }
            if (acquiredPermits != null) {
                if (!isLiveOrdinaryDirectWifiCandidate(
                        pageIndex,
                        candidate,
                        predecessorProvenOrdinaryDirectWifi,
                        restoredAnchorOrdinaryDirectWifi,
                    )
                ) {
                    acquiredPermits.release()
                    ReaderImageCache.selectDirectWifiOrdinaryNetworkBoundH1(route, false)
                    return null
                }
                ReaderImageCache.selectDirectWifiOrdinaryNetworkBoundH1(
                    route,
                    true,
                    preferredOrdinaryDirectWifiReplicaHost,
                )
                if (predecessorProvenOrdinaryDirectWifi) {
                    Log.d(
                        TAG,
                        "click_adjacent_inherited_ordinary_h1_admit " +
                            "path=${plan.normalizedEpisodePath},page=$pageIndex",
                    )
                }
                if (restoredAnchorOrdinaryDirectWifi) {
                    Log.d(
                        TAG,
                        "click_restored_anchor_ordinary_h1_admit " +
                            "path=${plan.normalizedEpisodePath},page=$pageIndex",
                    )
                }
                val released = AtomicBoolean(false)
                return Closeable {
                    if (released.compareAndSet(false, true)) {
                        acquiredPermits.release()
                    }
                }
            }
        }
        throw InterruptedException("Click-owned ordinary direct-WiFi admission closed")
    }

    private fun isLiveOrdinaryDirectWifiCandidate(
        pageIndex: Int,
        candidate: String,
        predecessorProvenOrdinaryDirectWifi: Boolean = false,
        restoredAnchorOrdinaryDirectWifi: Boolean = false,
    ): Boolean {
        val capturedHandle = capturedDirectWifiNetworkHandle ?: return false
        val inheritedRunway = predecessorProvenOrdinaryDirectWifi &&
            directWifiAdjacentOwned &&
            pageIndex - forwardFirstPage in
                0 until directWifiAdjacentPhysicalRunwayPages
        val extension = candidate.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val httpClient = getHttpClient()
        val liveHandle = runCatching {
            httpClient.getNtkDirectWifiNetwork()?.networkHandle
        }.getOrNull()
        val restoredAnchor = restoredAnchorOrdinaryDirectWifi &&
            NtkClickOwnedManhwaWavePolicy.shouldUseRestoredAnchorOrdinaryDirectWifiTransport(
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                extension = extension,
                liveWifiTransport = httpClient.isNtkWifiTransportActive,
                cellularResilientTransport =
                    httpClient.isNtkCellularResilientTransportActive,
                capturedNetworkHandle = capturedHandle,
                liveNetworkHandle = liveHandle,
            )
        if ((!ReaderImageCache.isKnownDirectWifiOrdinaryManhwaEpisode(candidate) &&
                !inheritedRunway && !restoredAnchor) ||
            ReaderImageCache.isKnownDirectWifiMixedManhwaEpisode(candidate)
        ) return false
        if (extension != "jpg" && extension != "jpeg") return false
        if (httpClient.isNtkCellularResilientTransportActive ||
            !httpClient.isNtkWifiTransportActive
        ) return false
        return liveHandle == capturedHandle
    }

    private fun isRestoredOrdinaryDirectWifiRunwayPage(pageIndex: Int): Boolean =
        !directWifiAdjacentOwned &&
            pageIndex - forwardFirstPage in
                0 until NtkClickOwnedManhwaWavePolicy.DIRECT_EXTENSION_RACE_PAGES

    private fun isLiveOrdinaryDirectWifiCandidate(candidate: String): Boolean =
        isLiveOrdinaryDirectWifiCandidate(
            pageIndex = -1,
            candidate = candidate,
            predecessorProvenOrdinaryDirectWifi = false,
            restoredAnchorOrdinaryDirectWifi = false,
        )

    /** Re-reads transport state immediately before an inherited guessed body may open. */
    private fun isCapturedDirectWifiTransportLive(): Boolean {
        val capturedHandle = capturedDirectWifiNetworkHandle ?: return false
        val httpClient = getHttpClient()
        if (!runCatching { httpClient.isNtkWifiTransportActive }.getOrDefault(false) ||
            runCatching { httpClient.isNtkCellularResilientTransportActive }.getOrDefault(true)
        ) return false
        return runCatching {
            httpClient.getNtkDirectWifiNetwork()?.networkHandle
        }.getOrNull() == capturedHandle
    }

    private fun primaryBodyExecutor(
        pageIndex: Int,
        candidate: String,
        predecessorProvenOrdinaryDirectWifi: Boolean = false,
    ): Executor = Executor { runnable ->
        if (NtkClickOwnedManhwaWavePolicy.shouldBoundHostGpuAdjacentTailTransfers(
                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                physicalRunwayPages = directWifiAdjacentPhysicalRunwayPages,
            )
        ) {
            HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_EXECUTOR.execute(runnable)
        } else if ((predecessorProvenOrdinaryDirectWifi && isCapturedDirectWifiTransportLive()) ||
            isLiveOrdinaryDirectWifiCandidate(candidate)
        ) {
            DIRECT_WIFI_ORDINARY_BODY_EXECUTOR.execute(runnable)
        } else {
            BODY_EXECUTOR.execute(runnable)
        }
    }

    private fun preferredTailBodyExecutor(pageIndex: Int, candidate: String): Executor {
        val initialPage = initialViewportPage.getNow(-1)
        return if (
            NtkClickOwnedManhwaWavePolicy.shouldUseDirectWifiRestoredViewportLane(
                wifiTransport = wifiEntryPriorityMode,
                pageIndex = pageIndex,
                initialViewportPage = initialPage,
            )
        ) {
            DIRECT_WIFI_RESTORED_VIEWPORT_BODY_EXECUTOR
        } else {
            primaryBodyExecutor(pageIndex, candidate)
        }
    }

    /**
     * Gives one already HEAD-proven uncommon p002-p012 body the next current-viewport worker.
     *
     * The request, transport, ownership, cancellation, and shared body permit remain exactly the
     * same. Only the executor used to submit that one canonical GET changes, and every live network
     * condition is re-read here so a handoff retains the original executor path.
     */
    private fun verifiedExactBodyExecutor(pageIndex: Int, candidate: String): Executor {
        val original = primaryBodyExecutor(pageIndex, candidate)
        val httpClient = getHttpClient()
        val liveWifiTransport = runCatching {
            httpClient.isNtkWifiTransportActive
        }.getOrDefault(false)
        val cellularResilientTransport = runCatching {
            httpClient.isNtkCellularResilientTransportActive
        }.getOrDefault(true)
        val liveNetworkHandle = runCatching {
            httpClient.getNtkDirectWifiNetwork()?.networkHandle
        }.getOrNull()
        val prioritizeRestoredViewport = NtkClickOwnedManhwaWavePolicy
            .shouldPrioritizeHostGpuCurrentRestoredViewportEntryBody(
                hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                directWifiAdjacentOwned = directWifiAdjacentOwned,
                wifiEntryPriorityMode = wifiEntryPriorityMode,
                liveWifiTransport = liveWifiTransport,
                cellularResilientTransport = cellularResilientTransport,
                capturedNetworkHandle = capturedDirectWifiNetworkHandle,
                liveNetworkHandle = liveNetworkHandle,
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                pageCount = effectivePageCount.get(),
                candidateExtension = candidate.substringAfterLast('.', ""),
            )
        val prioritize = prioritizeRestoredViewport || NtkClickOwnedManhwaWavePolicy
            .shouldPrioritizeVerifiedDirectWifiEntryBody(
                pageIndex = pageIndex - forwardFirstPage,
                candidateExtension = candidate.substringAfterLast('.', ""),
                currentEpisode = !directWifiAdjacentOwned,
                wifiEntryPriorityMode = wifiEntryPriorityMode,
                liveWifiTransport = liveWifiTransport,
                cellularResilientTransport = cellularResilientTransport,
                capturedNetworkHandle = capturedDirectWifiNetworkHandle,
                liveNetworkHandle = liveNetworkHandle,
            )
        if (!prioritize) return original
        Log.d(
            TAG,
            "click_verified_entry_exact_priority path=${plan.normalizedEpisodePath}," +
                "page=$pageIndex,candidate=${candidate.substringAfterLast('/')}," +
                "restoredViewport=$prioritizeRestoredViewport",
        )
        return WIFI_ENTRY_FALLBACK_BODY_EXECUTOR
    }

    private fun fallbackBodyExecutor(pageIndex: Int) = when {
        pageIndex == forwardFirstPage -> ANCHOR_FALLBACK_BODY_EXECUTOR
        NtkClickOwnedManhwaWavePolicy.shouldBoundHostGpuAdjacentTailTransfers(
            hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
            directWifiAdjacentOwned = directWifiAdjacentOwned,
            pageIndex = pageIndex,
            forwardFirstPage = forwardFirstPage,
            physicalRunwayPages = directWifiAdjacentPhysicalRunwayPages,
        ) -> HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_EXECUTOR
        NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
            wifiEntryPriorityMode,
            pageIndex - forwardFirstPage,
        ) -> WIFI_ENTRY_FALLBACK_BODY_EXECUTOR
        else -> FALLBACK_BODY_EXECUTOR
    }

    /**
     * Every direct-Wi-Fi adjacent body waits for predecessor completion. The first four are then
     * admitted immediately; the suffix waits only for those four bodies, never for viewport input.
     * Cellular/SNI retain their existing full-wave admission after the same coordinator gate.
     */
    private fun adjacentPhysicalAdmissionFuture(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): CompletableFuture<Boolean> {
        if (!directWifiAdjacentOwned) {
            return CompletableFuture.completedFuture(true)
        }
        val predecessorAdmission = adjacentPredecessorComplete.handle { _, releaseFailure ->
            releaseFailure == null && !closed.get() && pageIndex < effectivePageCount.get()
        }
        val runwayAdmission = if (pageIndex < directWifiAdjacentPhysicalRunwayPages) {
            CompletableFuture.completedFuture(Unit)
        } else {
            adjacentRunwayRelease
        }
        return predecessorAdmission.thenCombine(runwayAdmission) { predecessorAdmitted, _ ->
            predecessorAdmitted && runCatching {
                callCancellation.throwIfCancelled()
                true
            }.getOrDefault(false)
        }
    }

    /** Only physical transport pressure is evidence for lowering the ordinary-JPEG body limit. */
    private fun isAdaptiveBulkTransportFailure(
        failure: Throwable,
        stage: String,
        callCancellation: ReaderImageCache.Cancellation,
    ): Boolean {
        if (stage != "spool_body" || closed.get() || !isCapturedDirectWifiTransportLive()) {
            return false
        }
        if (runCatching { callCancellation.throwIfCancelled() }.isFailure) return false
        var current: Throwable? = failure
        repeat(8) {
            val observed = current ?: return false
            when (observed) {
                is NtkTerminalSourceException,
                is NtkQuarantineHttpStatusException,
                is java.util.concurrent.CancellationException,
                is InterruptedException -> return false
                is java.net.SocketTimeoutException,
                is java.net.SocketException,
                is java.io.EOFException -> return true
                // Explicit app cancellation was already excluded through the cancellation token.
                // OkHttp also represents its inherited 25 s full-call timeout as a plain
                // InterruptedIOException("timeout"); that is physical pressure and must downshift.
                is java.io.InterruptedIOException ->
                    return observed.message.orEmpty().contains("timeout", ignoreCase = true)
            }
            current = observed.cause
        }
        return NtkStrictSourceFailurePolicy.isRetryableTransportFailure(failure)
    }

    /** Final physical-call backstop for any newly added candidate branch. */
    private fun awaitAdjacentPhysicalAdmission(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ) {
        if (!directWifiAdjacentOwned) return
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            try {
                val release = if (pageIndex < directWifiAdjacentPhysicalRunwayPages) {
                    adjacentPredecessorComplete
                } else {
                    adjacentRunwayRelease
                }
                release.get(
                    BODY_TRANSFER_PERMIT_POLL_MS,
                    TimeUnit.MILLISECONDS,
                )
                break
            } catch (_: java.util.concurrent.TimeoutException) {
                // Poll only for cancellation. No adjacent image body is read before release.
            }
        }
        if (closed.get()) throw java.util.concurrent.CancellationException("quarantine closed")
        callCancellation.throwIfCancelled()
    }

    private fun primaryAdmissionFuture(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): CompletableFuture<Boolean> {
        val numericAdmission = if (!plan.maximumNumericBound) {
            CompletableFuture.completedFuture(
                !closed.get() && pageIndex < effectivePageCount.get()
            )
        } else {
            val admission = checkNotNull(numericAdmissionFutures[pageIndex])
            admission.handle { _, admissionFailure ->
                if (admissionFailure != null || closed.get() ||
                    pageIndex >= effectivePageCount.get()
                ) {
                    false
                } else {
                    runCatching {
                        callCancellation.throwIfCancelled()
                        true
                    }.getOrDefault(false)
                }
            }
        }
        return numericAdmission.thenCombine(
            adjacentPhysicalAdmissionFuture(pageIndex, callCancellation)
        ) { numericAdmitted, adjacentAdmitted ->
            numericAdmitted && adjacentAdmitted
        }
    }

    private fun releaseSpeculationDebt(pageIndex: Int) {
        if (!speculationDebtHolders.remove(pageIndex)) return
        // Image headers prove only that a replica accepted this one body; they do not prove the
        // episode table. Before the fresh numeric document completes, rolling this credit forward
        // can grow three entry probes into almost the entire book and starve the authority request.
        // documentValidated + networkRelease already completes every in-range admission future at
        // the real prerequisite event, so retain no polling or fixed delay here.
        if (plan.maximumNumericBound && !documentValidated.isDone) {
            Log.d(
                TAG,
                "click_anchor_rolling_credit_held path=${plan.normalizedEpisodePath}," +
                    "page=$pageIndex,debt=${speculationDebtHolders.size}," +
                    "frontier=${rollingSpeculationFrontier.get()}",
            )
            return
        }
        var admittedPage = -1
        var frontier: Int
        while (true) {
            val current = rollingSpeculationFrontier.get()
            frontier = minOf(plan.pageCount, current + 1)
            if (frontier == current || rollingSpeculationFrontier.compareAndSet(current, frontier)) {
                if (frontier > current) admittedPage = current
                break
            }
        }
        if (admittedPage >= 0) {
            speculationDebtHolders.add(admittedPage)
            numericAdmissionFutures[admittedPage]?.complete(Unit)
        }
        Log.d(
            TAG,
            "click_anchor_rolling_credit path=${plan.normalizedEpisodePath}," +
                "page=$pageIndex,debt=${speculationDebtHolders.size},frontier=$frontier",
        )
    }

    private fun releaseAllSpeculationDebt() {
        speculationDebtHolders.clear()
    }

    private fun pageBinding(pageIndex: Int, canonicalAsset: String): NtkQuarantinePlanBinding {
        val pageCandidates = List(plan.pageCount) { candidateIndex ->
            if (candidateIndex == pageIndex) canonicalAsset
            else candidateAsset(candidateIndex, DEFAULT_EXTENSION)
        }
        return plan.bindCandidates(pageCandidates)
    }

    private fun candidateAsset(pageIndex: Int, extension: String): String =
        "https://${NtkClickOwnedManhwaWavePolicy.replicaHost(pageIndex)}/" +
            "manhwa/$workId/$episodeId/" +
            "p%03d.%s".format(Locale.ROOT, pageIndex + 1, extension)

    private fun fetchIntoQuarantine(
        binding: NtkQuarantinePlanBinding,
        sourceSessionId: Long,
        pageIndex: Int,
        candidateAsset: String,
        callCancellation: ReaderImageCache.Cancellation,
        telemetryAfterImageHeaders: Boolean,
        onValidImageHeaders: () -> Unit,
        route: ReaderImageCache.NtkResolvedSourceRoute,
        requireCapturedDirectWifi: Boolean,
        predecessorProvenOrdinaryDirectWifi: Boolean,
        restoredAnchorOrdinaryDirectWifi: Boolean,
        preferredOrdinaryDirectWifiReplicaHost: String?,
    ): HeldBody? {
        var stage = "create_identity"
        var currentRestoredBulkOutcome: NtkAdaptiveManhwaBulkAdmission.Lease? = null
        var currentRestoredBulkOwner: CurrentRestoredBulkBodyLease? = null
        var currentRestoredPhysicalEvidence:
            ReaderImageCache.NtkStrictPhysicalBodyEvidence? = null
        var currentRestoredExactJpeg = false
        val expectedPlannedReplicaHost = (
            preferredOrdinaryDirectWifiReplicaHost?.trim()?.takeIf { it.isNotEmpty() }
                ?: runCatching { java.net.URI.create(candidateAsset).host.orEmpty() }
                    .getOrDefault("")
            ).lowercase(Locale.ROOT)
        val operationId = NtkStrictSourceOwnershipRegistry.nextOperationId()
        val identity = NtkQuarantineSourceCallIdentity.create(
            sourceSessionId,
            binding.discoveryGeneration,
            binding.bindingDigest,
            pageIndex,
            candidateAsset,
            NtkClickOwnedManhwaWavePolicy.ownershipLane(pageIndex),
            operationId,
            route.routeKeyHash,
            route.callFactoryId,
            ReaderImageCache.quarantineEffectiveRequestDigest(route, pageIndex, candidateAsset),
        )
        stage = "begin_operation"
        val operationLease = NtkQuarantineSourceOwnershipRegistry.beginOperation(
            binding.episodePath,
            identity,
        )
        var fileLease: ReaderImageCache.NtkQuarantineFileLease? = null
        return try {
            stage = "open_file_lease"
            val opened = ReaderImageCache.openQuarantineFileLease(
                appContext,
                binding,
                pageIndex,
                operationId,
            ) { }
            fileLease = opened
            stage = "spool_body"
            if (requireCapturedDirectWifi && !isCapturedDirectWifiTransportLive()) {
                throw InterruptedException("Inherited direct-Wi-Fi candidate transport changed")
            }
            val body = ReaderImageCache.spoolQuarantinedEncodedOriginal(
                appContext,
                manga,
                binding,
                pageIndex,
                candidateAsset,
                route,
                ReaderImageCache.NtkQuarantineCallContext(identity, operationLease),
                opened,
                callCancellation,
                waveRecoveryState = manhwaWaveRecoveryState,
                prioritizeManhwaHeaderRecovery = restoredAnchorOrdinaryDirectWifi ||
                    (directWifiAdjacentOwned &&
                        pageIndex - forwardFirstPage in
                            0 until directWifiAdjacentPhysicalRunwayPages),
                telemetryAfterImageHeaders = telemetryAfterImageHeaders,
                validImageHeadersSink = {
                    onValidImageHeaders()
                },
                exactImageHeaderSink = { exactHeader ->
                    currentRestoredExactJpeg = exactHeader.format.equals(
                        NtkExactImageHeaderParser.FORMAT_JPEG,
                        ignoreCase = true,
                    )
                    if (!currentRestoredExactJpeg && hostGpuCurrentRestoredViewportPriority) {
                        // Exact bytes, not the filename suffix, are the final format signal. This
                        // page continues through the mixed path, but it may never qualify a wider
                        // ordinary-JPEG wave for the rest of the session. A bulk Call keeps its
                        // slot until EOF so a downshift cannot temporarily exceed its cap; a
                        // viewport proof freezes the controller before the suffix fence opens.
                        hostGpuCurrentRestoredBulkAdmission.freeze("mixed_exact_format")
                    }
                    if (directWifiEarlyUncommonEnabled) {
                        val exactExtension = candidateAsset.substringAfterLast('.', "")
                            .lowercase(Locale.ROOT)
                        if (exactExtension != "jpg" && exactExtension != "jpeg") {
                            ReaderImageCache.invalidateNtkDirectWifiOrdinaryManhwaEpisode(
                                candidateAsset,
                            )
                        }
                        if (exactExtension == "png") {
                            ReaderImageCache.rememberNtkDirectWifiMixedManhwaEpisode(
                                candidateAsset,
                                "png",
                            )
                        }
                    }
                    observedCandidates[pageIndex]?.complete(candidateAsset)
                    releaseSpeculationDebt(pageIndex)
                },
                metadataSink = { },
                bodyReadAdmission = {
                    awaitHostGpuCurrentRestoredViewportBodyAdmission(
                        pageIndex,
                        callCancellation,
                    )
                    awaitAdjacentPhysicalAdmission(pageIndex, callCancellation)
                    var currentRestoredBulkLease: CurrentRestoredBulkBodyLease? = null
                    var adjacentTailLease: Closeable? = null
                    var mixedUncommonLease: Closeable? = null
                    var ordinaryWifiLease: Closeable? = null
                    try {
                        currentRestoredBulkLease =
                            acquireHostGpuCurrentRestoredBulkBodyTransferLease(
                                pageIndex,
                                candidateAsset,
                                callCancellation,
                                predecessorProvenOrdinaryDirectWifi,
                                restoredAnchorOrdinaryDirectWifi,
                            )
                        currentRestoredBulkOutcome = currentRestoredBulkLease?.adaptiveOutcome
                        currentRestoredBulkOwner = currentRestoredBulkLease
                        // This lease is acquired before the ordinary 40-call ring and before Call
                        // creation in spoolQuarantinedEncodedOriginal(). Waiting suffix pages own no
                        // socket, response body, or decode work while p0-p4 remain unaffected.
                        adjacentTailLease = acquireHostGpuAdjacentTailBodyTransferLease(
                            pageIndex,
                            callCancellation,
                        )
                        // Never own a scarce mixed-PNG permit while parked behind either
                        // viewport gate. Offscreen PNG workers could otherwise consume all eight
                        // permits, wait for the four-body restored viewport, and prevent its last
                        // body from acquiring the permit that completes that very gate.
                        mixedUncommonLease = acquireMixedUncommonTransferLease(
                            candidateAsset,
                            callCancellation,
                        )
                        ordinaryWifiLease = acquireOrdinaryDirectWifiTransferLease(
                            pageIndex,
                            candidateAsset,
                            callCancellation,
                            route,
                            predecessorProvenOrdinaryDirectWifi,
                            restoredAnchorOrdinaryDirectWifi,
                            preferredOrdinaryDirectWifiReplicaHost,
                        )
                        if (currentRestoredBulkOutcome != null && ordinaryWifiLease == null) {
                            // Classification/transport changed between the two gates. Freeze the
                            // session-local experiment before returning the common outer permit.
                            hostGpuCurrentRestoredBulkAdmission.freeze(
                                if (!isCapturedDirectWifiTransportLive()) {
                                    "profile_changed_between_body_gates"
                                } else {
                                    "ordinary_classification_changed_between_body_gates"
                                },
                            )
                            currentRestoredBulkOwner?.abandonAdaptive()
                            currentRestoredBulkOutcome = null
                        }
                        val baseLease =
                            if (ordinaryWifiLease != null && pageIndex != forwardFirstPage) {
                                null
                            } else {
                                acquireBodyTransferLease(pageIndex, callCancellation)
                            }
                        Closeable {
                            baseLease?.close()
                            ordinaryWifiLease?.close()
                            mixedUncommonLease?.close()
                            adjacentTailLease?.close()
                            currentRestoredBulkLease?.close()
                        }
                    } catch (failure: Throwable) {
                        ordinaryWifiLease?.close()
                        mixedUncommonLease?.close()
                        adjacentTailLease?.close()
                        currentRestoredBulkLease?.close()
                        throw failure
                    }
                },
                onPhysicalBodyProven = { evidence ->
                    // The spool emits this synchronously only after exact length + SHA at EOF.
                    // Keep it local to this one operation; it never enters body identity state.
                    currentRestoredPhysicalEvidence = evidence
                },
                preferFileBackedBody =
                    NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                        hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                        directWifiTransport = directWifiAdjacentOwned,
                        cellularResilientTransport = false,
                        adjacentPrefetch = directWifiAdjacentOwned,
                        episodePath = binding.episodePath,
                    ),
                // p0-p4 are the immutable boundary runway and retain their original prompt read.
                // Later adjacent pages are offscreen authority: pause only their byte/EOF work
                // during real foreground motion and resume the same response in idle gaps.
                deferBodyReadsWhilePhysicalMotion =
                    NtkClickOwnedManhwaWavePolicy.shouldBoundHostGpuAdjacentTailTransfers(
                        hostGpuEmulatorRuntime = hostGpuEmulatorRuntime,
                        directWifiAdjacentOwned = directWifiAdjacentOwned,
                        pageIndex = pageIndex,
                        forwardFirstPage = forwardFirstPage,
                        physicalRunwayPages = directWifiAdjacentPhysicalRunwayPages,
                    ),
            )
            val capturedProfileLive = isCapturedDirectWifiTransportLive()
            val ordinaryClassificationLive = isLiveOrdinaryDirectWifiCandidate(
                    pageIndex,
                    candidateAsset,
                    predecessorProvenOrdinaryDirectWifi,
                    restoredAnchorOrdinaryDirectWifi,
                )
            val stillComparableOrdinaryBody = currentRestoredBulkOutcome != null &&
                currentRestoredPhysicalEvidence != null &&
                expectedPlannedReplicaHost.isNotBlank()
            val completedAdmission = currentRestoredBulkOutcome?.let { outcome ->
                val evidence = currentRestoredPhysicalEvidence
                if (!stillComparableOrdinaryBody || evidence == null) {
                    outcome.disqualified("missing_physical_eof_proof")
                } else {
                    outcome.succeeded(
                        NtkAdaptiveManhwaBulkAdmission.PhysicalProof(
                            operationId = operationId,
                            pageIndex = pageIndex,
                            encodedBytes = body.encodedLength,
                            expectedResponseHost = expectedPlannedReplicaHost,
                            capturedProfileLive = capturedProfileLive,
                            ordinaryClassificationLive = ordinaryClassificationLive,
                            exactOrdinaryJpeg = currentRestoredExactJpeg,
                            evidence = evidence,
                        ),
                    )
                }
            }
            completedAdmission?.let { admission ->
                Log.d(
                    TAG,
                    "click_current_restored_bulk_admission " +
                        "path=${binding.episodePath},target=${admission.targetLimit}," +
                        "best=${admission.bestLimit},active=${admission.activeLeases}," +
                        "settled=${admission.settled},frozen=${admission.frozen}," +
                        "reason=${admission.transitionReason}," +
                        "proofs=${admission.stageSuccesses}," +
                        "hosts=${admission.healthyReplicaHostCount}",
                )
            }
            val held = HeldBody(body, opened, binding)
            retained[pageIndex] = held
            if (closed.get() && retained.remove(pageIndex, held)) {
                held.predecodedOriginal?.close()
                held.fileLease.close()
                null
            } else {
                held
            }
        } catch (failure: Throwable) {
            val congestionFailure = currentRestoredBulkOutcome != null &&
                isAdaptiveBulkTransportFailure(failure, stage, callCancellation)
            val admission = when {
                congestionFailure -> currentRestoredBulkOutcome?.failed()
                currentRestoredBulkOutcome != null &&
                    !isCapturedDirectWifiTransportLive() ->
                    currentRestoredBulkOutcome?.disqualified("profile_changed_after_body_failure")
                else -> currentRestoredBulkOutcome?.aborted()
            }
            if (admission != null) {
                Log.d(
                    TAG,
                    "click_current_restored_bulk_admission " +
                        "path=${binding.episodePath},target=${admission.targetLimit}," +
                        "best=${admission.bestLimit},active=${admission.activeLeases}," +
                        "settled=${admission.settled},frozen=${admission.frozen}," +
                        "reason=${admission.transitionReason}",
                )
            }
            operationLease.close()
            fileLease?.close()
            Log.d(
                TAG,
                "click_anchor_quarantine_miss path=${binding.episodePath}," +
                    "page=$pageIndex,stage=$stage,error=${failure.javaClass.simpleName}," +
                    "message=${failure.message.orEmpty().replace(',', ';').take(160)}",
            )
            null
        } finally {
            // Fail closed if a future branch exits without classifying the adaptive body. Normal
            // success/failure paths have already completed this lease, making close a no-op.
            currentRestoredBulkOutcome?.close()
            operationLease.close()
        }
    }

    private fun closeOwnership(binding: NtkQuarantinePlanBinding, sourceSessionId: Long) {
        NtkQuarantineSourceOwnershipRegistry.closeAdmissions(
            binding.episodePath,
            binding.discoveryGeneration,
            sourceSessionId,
        )
        NtkQuarantineSourceOwnershipRegistry.release(
            binding.episodePath,
            binding.discoveryGeneration,
            sourceSessionId,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hostGpuCurrentRestoredBulkAdmission.close()
        cancellation.cancel()
        pageCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        fallbackCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        speculativeUncommonCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        earlyProbeOwner?.close()
        networkRelease.complete(Unit)
        hostGpuCurrentRestoredViewportBodyRelease.completeExceptionally(
            InterruptedException("Click-owned restored viewport body admission closed"),
        )
        adjacentViewportRelease.completeExceptionally(
            InterruptedException("Click-owned adjacent viewport admission closed"),
        )
        adjacentRunwayRelease.completeExceptionally(
            InterruptedException("Click-owned adjacent runway admission closed"),
        )
        firstActualFramePresented.completeExceptionally(
            InterruptedException("Click-owned first actual frame gate closed"),
        )
        documentValidated.completeExceptionally(
            InterruptedException("Click-owned numeric candidate flight closed"),
        )
        numericAdmissionFutures.values.forEach { admission ->
            admission.completeExceptionally(
                InterruptedException("Click-owned numeric admission closed")
            )
        }
        observedCandidates.values.forEach { candidate ->
            candidate.completeExceptionally(
                InterruptedException("Click-owned observed candidate flight closed")
            )
        }
        adoptedPhysicalCandidates.values.forEach { candidate ->
            candidate.completeExceptionally(
                InterruptedException("Click-owned adopted physical candidate flight closed")
            )
        }
        directWifiAdjacentInheritedResidentBodies.values.forEach { resident ->
            resident.completeExceptionally(
                InterruptedException("Click-owned adjacent resident-body flight closed")
            )
        }
        retained.entries.toList().forEach { (pageIndex, held) ->
            if (retained.remove(pageIndex, held)) {
                held.predecodedOriginal?.close()
                held.fileLease.close()
            }
        }
    }

    companion object {
        private const val TAG = "ViewerPerf"
        // The exact API normally arrives while these bodies are already in flight. Keep their
        // one physical transfer alive long enough for exact adoption instead of cancelling near
        // completion and immediately downloading the same 15-20 MB a second time.
        private const val ADOPTION_WAIT_MS = 3_500L
        private const val DEFAULT_EXTENSION = "jpg"
        private const val BODY_TRANSFER_PERMIT_POLL_MS = 25L
        private const val DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES = 4
        private const val HOST_GPU_DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES = 5
        // This is a production post-click wave, not pre-entry warm-up. The signed image-list API
        // uses the separate control-plane client and consumes no source-operation lane, so every
        // page up to the ownership bound belongs in this wave. Leaving the final page out made a
        // ten-page cold book wait for a fresh 533 KiB transfer after ACK authority (r60: +627 ms),
        // even though pages 0..8 were already complete and privately quarantined.
        private const val MAX_CLICK_FORWARD_PAGES =
            NtkSourceLanePolicy.MAX_EPISODE_PAGES
        // Only the initial speculation debt starts at once. Each parsed image-container header
        // returns one credit, so successful post-click traffic rolls the frontier forward while a
        // run of numeric misses stops at this bound. The exact document reconciles the remaining
        // real pages; ACTIVE_BODY_TRANSFERS independently limits their physical concurrency.
        private const val SPECULATIVE_CLICK_PAGES =
            NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT
        private const val FORMAT_VERIFIED_SPECULATIVE_PAGES = SPECULATIVE_CLICK_PAGES
        private const val PRIVATE_PREDECODE_RUNWAY_PAGES = SPECULATIVE_CLICK_PAGES
        private const val DOMINANT_EXTENSION_SAMPLE_PAGES = 12
        private val SESSION_IDS = AtomicLong(Long.MAX_VALUE / 2L)
        // Only uncommon non-JPG misses use these lanes for bounded extension discovery.
        private val PROBE_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.PROBE_LANES,
        ) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-replica-probe")
        }.retireIdleNtkClickWorkers()
        // Connections stay bounded by the replica-local pool ring. The executor admits every
        // finite body to those pools without forcing a second client-side wave.
        private val BODY_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.BODY_LANES,
        ) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-anchor-quarantine")
        }.retireIdleNtkClickWorkers()
        private val DIRECT_WIFI_ORDINARY_BODY_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.DIRECT_WIFI_ORDINARY_BODY_TRANSFERS,
        ) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-direct-wifi-ordinary")
        }.retireIdleNtkClickWorkers()
        private val HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_EXECUTOR =
            Executors.newFixedThreadPool(
                NtkClickOwnedManhwaWavePolicy.HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_EXECUTOR_LANES,
            ) { runnable ->
                ntkClickWorkerThread(runnable, "ntk-click-adjacent-tail")
            }.retireIdleNtkClickWorkers()
        private val DIRECT_WIFI_RESTORED_VIEWPORT_BODY_EXECUTOR =
            Executors.newSingleThreadExecutor { runnable ->
                ntkClickWorkerThread(
                    runnable,
                    "ntk-click-direct-wifi-restored-viewport",
                    Process.THREAD_PRIORITY_DISPLAY,
                )
            }
        // A uniform JPEG/PNG/GIF book is not an exceptional retry: after the metadata-only probe,
        // every canonical page legitimately enters this executor. Keeping only eight workers made
        // the policy's 24 measured connection shards run in three serialized waves and delayed the
        // final GET itself beyond the cold deadline. bodyTransferPermits remains the single
        // physical admission bound, and page zero retains its dedicated executor below.
        private val FALLBACK_BODY_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
        ) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-anchor-fallback")
        }.retireIdleNtkClickWorkers()
        private val ANCHOR_FALLBACK_BODY_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            ntkClickWorkerThread(
                runnable,
                "ntk-click-page-zero-fallback",
                Process.THREAD_PRIORITY_DISPLAY,
            )
        }
        // p002-p012 can resolve to an uncommon extension after the exact-count bulk wave has
        // already filled the normal fallback executor. Keep those viewport peers ahead of the
        // offscreen queue; otherwise a 70 KiB p002 can wait behind ninety unrelated bodies and
        // hold the first complete landscape viewport for many seconds.
        private val WIFI_ENTRY_FALLBACK_BODY_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_SPECULATION_PAGES - 1,
        ) { runnable ->
            ntkClickWorkerThread(
                runnable,
                "ntk-click-wifi-entry-fallback",
                Process.THREAD_PRIORITY_DISPLAY,
            )
        }.retireIdleNtkClickWorkers()
        private val COORDINATOR_EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            ntkClickWorkerThread(runnable, "ntk-click-forward-coordinator")
        }.retireIdleNtkClickWorkers()
        private val ENTRY_RELEASE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                ntkClickWorkerThread(
                    runnable,
                    "ntk-click-wifi-entry-release",
                    Process.THREAD_PRIORITY_BACKGROUND,
                )
            }
        private val ANCHOR_PREDECODE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            ntkClickWorkerThread(
                runnable,
                "ntk-click-anchor-predecode",
                Process.THREAD_PRIORITY_DISPLAY,
            )
        }
        private val BULK_PREDECODE_EXECUTOR = Executors.newFixedThreadPool(6) { runnable ->
            ntkClickWorkerThread(
                runnable,
                "ntk-click-bulk-predecode",
                Process.THREAD_PRIORITY_BACKGROUND,
            )
        }.retireIdleNtkClickWorkers()
        private val DIRECT_WIFI_ADJACENT_RUNWAY_PREDECODE_EXECUTOR =
            Executors.newSingleThreadExecutor { runnable ->
                ntkClickWorkerThread(
                    runnable,
                    "ntk-click-adjacent-runway-predecode",
                    Process.THREAD_PRIORITY_BACKGROUND,
                )
            }

        fun start(
            context: Context,
            manga: Manga,
            draft: NtkEpisodeDocumentPlanDraft,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier? = null,
            directWifiAdjacentOwned: Boolean = false,
            adjacentPredecessorComplete: CompletableFuture<Unit> =
                CompletableFuture.completedFuture(Unit),
            initialPageIndexHint: Int = 0,
            viewerGeneration: Long = 0L,
            adjacentPredecessorEpisodePath: String = draft.normalizedEpisodePath,
        ): NtkClickOwnedAnchorQuarantine? {
            val parts = draft.normalizedEpisodePath.trim('/').split('/')
            if (parts.size != 3 || !parts[0].equals("manhwa", ignoreCase = true) ||
                !parts[1].matches(Regex("\\d{1,12}")) ||
                !parts[2].matches(Regex("\\d{1,12}")) || draft.pageCount < 2
            ) return null
            val earlyClaim = earlyProbeFrontier?.claim(draft)
            if (earlyProbeFrontier != null && earlyClaim == null) {
                earlyProbeFrontier.close()
            }
            val plan = NtkClickOwnedQuarantinePlan(
                normalizedEpisodePath = draft.normalizedEpisodePath,
                discoveryGeneration = draft.discoveryGeneration,
                pageCount = draft.pageCount,
                documentDraft = draft,
            )
            return create(
                context.applicationContext,
                manga,
                plan,
                parts[1],
                parts[2],
                earlyClaim,
                directWifiAdjacentOwned,
                adjacentPredecessorComplete,
                initialPageIndexHint,
                viewerGeneration,
                adjacentPredecessorEpisodePath,
            )
        }

        fun startFromTrustedPayloadHint(
            context: Context,
            manga: Manga,
            normalizedEpisodePath: String,
            discoveryGeneration: Long,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier?,
            directWifiAdjacentOwned: Boolean = false,
            adjacentPredecessorComplete: CompletableFuture<Unit> =
                CompletableFuture.completedFuture(Unit),
            initialPageIndexHint: Int = 0,
            viewerGeneration: Long = 0L,
            adjacentPredecessorEpisodePath: String = normalizedEpisodePath,
        ): NtkClickOwnedAnchorQuarantine? {
            val parts = normalizedEpisodePath.trim('/').split('/')
            if (parts.size != 3 || !parts[0].equals("manhwa", ignoreCase = true) ||
                !parts[1].matches(Regex("\\d{1,12}")) ||
                !parts[2].matches(Regex("\\d{1,12}"))
            ) return null
            val pageCount = manga.getExactNtkClickPayloadImageCount(normalizedEpisodePath)
            if (pageCount !in 2..NtkSourceLanePolicy.MAX_EPISODE_PAGES) return null
            val payloadHint = manga.ntkViewerPayloadHint.trim()
            if (payloadHint.isEmpty()) return null
            val earlyClaim = earlyProbeFrontier?.claimTrustedPayloadCount(
                normalizedEpisodePath,
                pageCount,
            )
            if (earlyProbeFrontier != null && earlyClaim == null) {
                earlyProbeFrontier.close()
                return null
            }
            val plan = NtkClickOwnedQuarantinePlan(
                normalizedEpisodePath = normalizedEpisodePath,
                discoveryGeneration = discoveryGeneration,
                pageCount = pageCount,
                payloadHintDigest = NtkStripDigests.sha256Bytes(
                    payloadHint.toByteArray(Charsets.UTF_8),
                ),
            )
            Log.d(
                TAG,
                "click_payload_count_quarantine_start path=$normalizedEpisodePath," +
                    "pages=$pageCount,generation=$discoveryGeneration",
            )
            return create(
                context.applicationContext,
                manga,
                plan,
                parts[1],
                parts[2],
                earlyClaim,
                directWifiAdjacentOwned,
                adjacentPredecessorComplete,
                initialPageIndexHint,
                viewerGeneration,
                adjacentPredecessorEpisodePath,
            )
        }

        fun startFromBoundedNumericCandidates(
            context: Context,
            manga: Manga,
            normalizedEpisodePath: String,
            discoveryGeneration: Long,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier? = null,
            directWifiAdjacentOwned: Boolean = false,
            adjacentPredecessorComplete: CompletableFuture<Unit> =
                CompletableFuture.completedFuture(Unit),
            initialPageIndexHint: Int = 0,
            viewerGeneration: Long = 0L,
            adjacentPredecessorEpisodePath: String = normalizedEpisodePath,
        ): NtkClickOwnedAnchorQuarantine? {
            val parts = normalizedEpisodePath.trim('/').split('/')
            if (parts.size != 3 || !parts[0].equals("manhwa", ignoreCase = true) ||
                !parts[1].matches(Regex("\\d{1,12}")) ||
                !parts[2].matches(Regex("\\d{1,12}"))
            ) return null
            val earlyClaim = earlyProbeFrontier?.claimMaximumBound(normalizedEpisodePath)
            if (earlyProbeFrontier != null && earlyClaim == null) {
                earlyProbeFrontier.close()
                return null
            }
            val maximum = NtkSourceLanePolicy.MAX_EPISODE_PAGES
            val plan = NtkClickOwnedQuarantinePlan(
                normalizedEpisodePath = normalizedEpisodePath,
                discoveryGeneration = discoveryGeneration,
                pageCount = maximum,
                payloadHintDigest = NtkStripDigests.sha256Tokens(
                    "ntk-click-bounded-numeric-candidates-v1",
                    normalizedEpisodePath,
                    discoveryGeneration.toString(),
                ),
                maximumNumericBound = true,
            )
            Log.d(
                TAG,
                "click_bounded_numeric_quarantine_start path=$normalizedEpisodePath," +
                    "maximumPages=$maximum,generation=$discoveryGeneration",
            )
            return create(
                context.applicationContext,
                manga,
                plan,
                parts[1],
                parts[2],
                earlyClaim,
                directWifiAdjacentOwned,
                adjacentPredecessorComplete,
                initialPageIndexHint,
                viewerGeneration,
                adjacentPredecessorEpisodePath,
            )
        }

        private fun create(
            context: Context,
            manga: Manga,
            plan: NtkClickOwnedQuarantinePlan,
            workId: String,
            episodeId: String,
            earlyClaim: NtkClickOwnedManhwaProbeFrontier.Claim?,
            directWifiAdjacentOwned: Boolean,
            adjacentPredecessorComplete: CompletableFuture<Unit>,
            initialPageIndexHint: Int,
            viewerGeneration: Long,
            adjacentPredecessorEpisodePath: String,
        ): NtkClickOwnedAnchorQuarantine = NtkClickOwnedAnchorQuarantine(
            context,
            manga,
            plan,
            workId,
            episodeId,
            initialPageIndexHint.coerceIn(0, plan.pageCount - 1),
            ReaderImageCache.Cancellation(),
            earlyClaim?.candidateFutures ?: emptyMap(),
            earlyClaim?.sourceRoutePreparationReady,
            earlyClaim?.owner,
            directWifiAdjacentOwned,
            adjacentPredecessorComplete,
            viewerGeneration,
            NtkStripDigests.normalizeEpisodePath(adjacentPredecessorEpisodePath),
        )
    }
}
