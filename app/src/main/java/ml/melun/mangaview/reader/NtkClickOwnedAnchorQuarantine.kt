package ml.melun.mangaview.reader

import android.content.Context
import android.util.Log
import ml.melun.mangaview.mangaview.Manga
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exact, manifest-bound completions for one finite post-click transfer wave. */
class NtkClickOwnedExactBodyStream(
    val bodyFutures: Map<Int, CompletableFuture<ReaderImageCache.NtkStrictPublishedBody?>>,
    private val owner: Closeable,
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        require(bodyFutures.isNotEmpty())
        require(bodyFutures.keys.all { it >= 0 })
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) owner.close()
    }
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
    private val pageCancellations: Map<Int, ReaderImageCache.Cancellation>,
    private val jpgCandidates: Map<Int, CompletableFuture<String?>>,
) : Closeable {
    data class Claim(
        val candidateFutures: Map<Int, CompletableFuture<String?>>,
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
            jpgCandidates.size != NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS ||
            !claimed.compareAndSet(false, true)
        ) return null
        Log.d(
            TAG,
            "click_manhwa_probe_frontier_claim_maximum path=$episodePath," +
                "pages=${jpgCandidates.size},ready=${jpgCandidates.values.count { it.isDone }}",
        )
        return Claim(jpgCandidates, this)
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
        return Claim(claimedCandidates, this)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pageCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        jpgCandidates.values.forEach { it.cancel(false) }
    }

    companion object {
        private const val TAG = "ViewerPerf"
        private const val FORMAT_SAMPLE_PAGES = 4
        private val SETUP_EXECUTOR = Executors.newFixedThreadPool(8) { runnable ->
            Thread(runnable, "ntk-click-probe-setup").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }

        fun start(manga: Manga, normalizedEpisodePath: String): NtkClickOwnedManhwaProbeFrontier? {
            val parts = normalizedEpisodePath.trim('/').split('/')
            if (parts.size != 3 || !parts[0].equals("manhwa", ignoreCase = true) ||
                !parts[1].matches(Regex("\\d{1,12}")) ||
                !parts[2].matches(Regex("\\d{1,12}"))
            ) return null
            val workId = parts[1]
            val episodeId = parts[2]
            val pageCancellations = (0 until NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES)
                .associateWith { ReaderImageCache.Cancellation() }
            val sampleFutures = (0 until FORMAT_SAMPLE_PAGES).associateWith { pageIndex ->
                CompletableFuture.supplyAsync(
                    {
                        ReaderImageCache.probeClickOwnedManhwaReplicaAssetParallel(
                            manga,
                            pageIndex,
                            NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.map { extension ->
                                candidateAsset(workId, episodeId, pageIndex, extension)
                            },
                            checkNotNull(pageCancellations[pageIndex]),
                            extensionHedgeDelayMs = 150L,
                        )
                    },
                    SETUP_EXECUTOR,
                ).thenCompose { it }
            }
            val preferredExtension = CompletableFuture.allOf(
                *sampleFutures.values.toTypedArray()
            ).handle { _, _ ->
                val counts = sampleFutures.values
                    .mapNotNull { future -> runCatching { future.getNow(null) }.getOrNull() }
                    .map { candidate -> candidate.substringAfterLast('.').lowercase(Locale.ROOT) }
                    .groupingBy { it }
                    .eachCount()
                NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.maxByOrNull { extension ->
                    (counts[extension] ?: 0) * 100 -
                        NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.indexOf(extension)
                } ?: "jpg"
            }
            val futures = (0 until NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES)
                .associateWith { pageIndex ->
                    sampleFutures[pageIndex] ?: preferredExtension.thenApply { extension ->
                        candidateAsset(workId, episodeId, pageIndex, extension)
                    }
                }
            Log.d(
                TAG,
                "click_manhwa_probe_frontier_start path=$normalizedEpisodePath," +
                    "pages=${futures.size},method=SAMPLED_PARALLEL_HEAD," +
                    "samples=$FORMAT_SAMPLE_PAGES," +
                    "extensions=${NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS.size}," +
                    "exactTailFromDocument=true",
            )
            return NtkClickOwnedManhwaProbeFrontier(
                normalizedEpisodePath,
                workId,
                episodeId,
                pageCancellations,
                futures,
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
    private val cancellation: ReaderImageCache.Cancellation,
    private val earlyJpgCandidates: Map<Int, CompletableFuture<String?>>,
    private val earlyProbeOwner: NtkClickOwnedManhwaProbeFrontier?,
) : Closeable {
    private data class HeldBody(
        val body: NtkQuarantinedBody,
        val fileLease: ReaderImageCache.NtkQuarantineFileLease,
        val binding: NtkQuarantinePlanBinding,
        val predecodedOriginal: NtkStrictPredecodedOriginal? = null,
    )

    /** Purely local request material that can be built while exact-count authority is in flight. */
    private data class PreparedCandidate(
        val binding: NtkQuarantinePlanBinding,
        val route: ReaderImageCache.NtkResolvedSourceRoute,
    )

    private data class Wave(
        val futures: Map<Int, CompletableFuture<HeldBody?>>,
    )

    private val closed = AtomicBoolean(false)
    private val effectivePageCount = AtomicInteger(plan.pageCount)
    private val completeDocumentPageCountHint = AtomicInteger(0)
    private val documentValidated = CompletableFuture<Unit>()
    private val pageCancellations = (0 until plan.pageCount)
        .associateWith { ReaderImageCache.Cancellation() }
    private val fallbackCancellations = (0 until plan.pageCount)
        .associateWith { ReaderImageCache.Cancellation() }
    private val observedCandidates = (0 until plan.pageCount)
        .associateWith { CompletableFuture<String>() }
    private val retained = ConcurrentHashMap<Int, HeldBody>()
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
        minOf(NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT, plan.pageCount),
    )
    private val speculationDebtHolders = ConcurrentHashMap.newKeySet<Int>()
    private val bodyTransferPermits = Semaphore(
        NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
        true,
    )
    private val waveReleased = AtomicBoolean(false)
    private val networkRelease = CompletableFuture<Unit>()
    // A body worker used to enter awaitRollingNumericAdmission() and poll every 25 ms. A
    // 112-page book therefore parked 72-88 Java threads until document authority completed, then
    // made Android schedule all of them at once; r56 spread valid GET starts out to click +1008 ms.
    // Each page now owns an event. The first bounded seed and later exact-header credits complete
    // individual events, while fresh document authority completes the retained remainder only
    // after the existing network-release gate. No waiting worker occupies BODY_EXECUTOR.
    private val numericAdmissionFutures = (0 until plan.pageCount)
        .associateWith { CompletableFuture<Unit>() }
    // Wave construction starts when the click-owned document proves the immutable page count. The
    // actual GETs still wait on networkRelease, so they cannot crowd ACK-critical transport.
    private val waveFuture = CompletableFuture.supplyAsync(::startForwardWave, COORDINATOR_EXECUTOR)

    init {
        if (plan.maximumNumericBound) {
            val seed = rollingSpeculationFrontier.get()
            (0 until seed).forEach { pageIndex ->
                speculationDebtHolders.add(pageIndex)
                numericAdmissionFutures.getValue(pageIndex).complete(Unit)
            }
            documentValidated.thenCombine(networkRelease) { _, _ -> Unit }
                .whenComplete { _, admissionFailure ->
                    val exactCount = effectivePageCount.get()
                    numericAdmissionFutures.forEach { (pageIndex, admission) ->
                        if (admissionFailure == null && !closed.get() && pageIndex < exactCount) {
                            admission.complete(Unit)
                        } else if (!admission.isDone) {
                            admission.completeExceptionally(
                                admissionFailure ?: InterruptedException(
                                    "Numeric page $pageIndex is outside the exact admitted table"
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
        retained.entries.toList().forEach { (pageIndex, held) ->
            if (pageIndex >= pageCount && retained.remove(pageIndex, held)) {
                held.predecodedOriginal?.close()
                held.fileLease.close()
            }
        }
    }

    /**
     * Restore the measured r120 cold-start wave. The first 32 pages retain entry priority, then
     * the rest of the finite 120-page transport frontier is admitted tail-first in three short
     * batches. Tail-first avoids putting every late page behind the same already-busy H2 stream,
     * while the 150 ms spacing avoids the handshake saturation seen with one 120-request burst.
     * This is normal post-click production work; exact document authority still rejects pages
     * beyond the canonical count before publication.
     */
    private fun scheduleTailFirstNumericRamp(seed: Int) {
        val frontier = minOf(
            plan.pageCount,
            NtkClickOwnedManhwaWavePolicy.PROBE_FRONTIER_PAGES,
        )
        if (seed >= frontier) return
        val batches = ArrayList<IntRange>()
        var endExclusive = frontier
        while (endExclusive > seed) {
            val start = maxOf(seed, endExclusive - NUMERIC_RAMP_BATCH_PAGES)
            batches += start until endExclusive
            endExclusive = start
        }
        batches.forEachIndexed { batchIndex, range ->
            NUMERIC_RAMP_EXECUTOR.schedule({
                if (closed.get()) return@schedule
                var admitted = 0
                range.forEach { pageIndex ->
                    if (pageIndex < effectivePageCount.get() &&
                        speculationDebtHolders.add(pageIndex)
                    ) {
                        numericAdmissionFutures[pageIndex]?.complete(Unit)
                        admitted++
                    }
                }
                Log.d(
                    TAG,
                    "click_numeric_body_ramp path=${plan.normalizedEpisodePath}," +
                        "batch=${batchIndex + 1},pages=${range.first}-${range.last}," +
                        "admitted=$admitted,debt=${speculationDebtHolders.size}",
                )
            }, batchIndex * NUMERIC_RAMP_INTERVAL_MS, TimeUnit.MILLISECONDS)
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

    private fun releaseWave(reason: String) {
        if (closed.get() || !waveReleased.compareAndSet(false, true)) return
        Log.d(
            TAG,
            "click_forward_quarantine_release path=${plan.normalizedEpisodePath},reason=$reason",
        )
        networkRelease.complete(Unit)
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

        val exactFutures = wave.futures
            .filterKeys { it < effectivePageCount.get() }
            .toSortedMap()
            .mapValues { (pageIndex, future) ->
            future.handle { held, transferFailure ->
                if (transferFailure != null || held == null || closed.get()) {
                    held?.let {
                        retained.remove(pageIndex, it)
                        it.predecodedOriginal?.close()
                        it.fileLease.close()
                    }
                    null
                } else {
                    adoptHeldBody(pageIndex, held, exactManifest)
                }
            }
        }
        val stream = NtkClickOwnedExactBodyStream(exactFutures, this)
        CompletableFuture.allOf(*exactFutures.values.toTypedArray()).whenComplete { _, _ ->
            val published = exactFutures.values.mapNotNull { it.getNow(null) }
            Log.d(
                TAG,
                "click_anchor_quarantine_stream_complete path=${plan.normalizedEpisodePath}," +
                    "pages=${published.size},expected=${exactFutures.size}," +
                    "bytes=${published.sumOf { it.proof.encodedLength }}",
            )
            close()
        }
        Log.d(
            TAG,
            "click_anchor_quarantine_stream_bound path=${plan.normalizedEpisodePath}," +
                "pages=${exactFutures.size},ready=${exactFutures.values.count { it.isDone }}",
        )
        return stream
    }

    private fun adoptHeldBody(
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
        retained.remove(pageIndex, held)
        held.fileLease.consume()
        Log.d(
            TAG,
            "click_anchor_quarantine_page_adopted path=${plan.normalizedEpisodePath}," +
                "page=$pageIndex,bytes=${exactBody.proof.encodedLength}",
        )
    }.onFailure { failure ->
        retained.remove(pageIndex, held)
        held.predecodedOriginal?.close()
        held.fileLease.close()
        Log.d(
            TAG,
            "click_anchor_quarantine_page_reject path=${plan.normalizedEpisodePath}," +
                "page=$pageIndex,reason=adoption_${failure.javaClass.simpleName}",
        )
    }.getOrNull()

    private fun startForwardWave(): Wave? {
        val pageLimit = minOf(MAX_CLICK_FORWARD_PAGES, plan.pageCount)
        if (closed.get() || pageLimit == 0) return null
        // This runs only after the committed viewer click. It performs no network operation: the
        // exact-count document remains the authority gate for tail image GETs. Materializing the
        // local client map now keeps its object/lock cost out of the finite release fan-out.
        ReaderImageCache.prepareClickOwnedManhwaClientTopology()
        // Build the one common 384-entry binding once on the coordinator. Previously the first
        // body worker initialized this synchronized lazy value while every other worker contended
        // on it during the finite request fan-out.
        defaultJpgBinding
        if (earlyJpgCandidates.isEmpty()) {
            val directBodies = (0 until pageLimit).associateWith { pageIndex ->
                startBoundedNumericCandidate(pageIndex)
            }
            Log.d(
                TAG,
                "click_forward_quarantine_wave path=${plan.normalizedEpisodePath}," +
                    "pages=${directBodies.size},totalPages=${plan.pageCount}," +
                    "bodyLanes=${NtkClickOwnedManhwaWavePolicy.BODY_LANES}," +
                    "directNumericGet=true",
            )
            return Wave(attachPrivatePredecodes(directBodies))
        }
        // HEAD responses contain no image body. Give every finite page its own lane so extension
        // resolution has no second executor wave; each completed page immediately starts its one
        // body GET on the separately bounded 120-operation ring.
        val initialPageLimit = if (plan.maximumNumericBound) {
            minOf(pageLimit, NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        } else {
            pageLimit
        }
        val candidateFutures = (0 until initialPageLimit).associateWith { pageIndex ->
            val earlyJpg = earlyJpgCandidates[pageIndex]
            if (earlyJpg == null) {
                if (plan.maximumNumericBound) {
                    // The click-time frontier intentionally probes only its bounded connection-
                    // warming sample. Pages beyond it must not turn a 384-page numeric ownership
                    // bound into 264 x five speculative HEADs. Once the exact document retains
                    // such a page, race its common JPG directly; only a real body miss performs
                    // uncommon-extension discovery in startCompletedHeadMissCandidate().
                    CompletableFuture.completedFuture<String?>(null)
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
            if (plan.maximumNumericBound) {
                // The head frontier starts at the committed click. Tail pages use this same race,
                // but startClickPrimaryCandidateRace keeps their body behind documentValidated.
                // Waiting for a slow metadata-only HEAD after the exact document was already
                // authoritative left the final finite pages idle for more than a second. Start the
                // common immutable JPG path immediately when that gate opens; a proven uncommon
                // extension can still win on the separate fallback executor and cancel it.
                startClickPrimaryCandidateRace(pageIndex, candidateFuture)
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
        Log.d(
            TAG,
            "click_forward_quarantine_wave path=${plan.normalizedEpisodePath}," +
                "pages=$pageLimit,initialScheduled=${initialBodyFutures.size}," +
                "totalPages=${plan.pageCount}," +
                "probeLanes=${NtkClickOwnedManhwaWavePolicy.PROBE_LANES}," +
                "earlyJpg=${earlyJpgCandidates.size}," +
                "immediateBodies=$SPECULATIVE_CLICK_PAGES," +
                "formatVerifiedBodies=$FORMAT_VERIFIED_SPECULATIVE_PAGES," +
                "pipelined=true",
        )
        val preparedInitial = attachPrivatePredecodes(initialBodyFutures)
        if (!plan.maximumNumericBound || initialPageLimit == pageLimit) {
            return Wave(preparedInitial)
        }

        // Keep an exact future for every protocol page without submitting 384 body workers at
        // click time. The complete document releases only pages 120..<exactCount; pages beyond it
        // complete locally. A 112-page book therefore no longer drains 272 cancelled executor
        // tasks while valid bodies are competing for the same CPU, while a 270-page book still
        // receives one continuous rolling queue after its exact count is known.
        val combined = LinkedHashMap<Int, CompletableFuture<HeldBody?>>(pageLimit)
        combined.putAll(preparedInitial)
        val latePlaceholders = (initialPageLimit until pageLimit).associateWith {
            CompletableFuture<HeldBody?>()
        }
        combined.putAll(latePlaceholders)
        documentValidated.whenCompleteAsync({ _, validationFailure ->
            val exactCount = effectivePageCount.get()
            latePlaceholders.forEach { (pageIndex, placeholder) ->
                if (validationFailure != null || closed.get() || pageIndex >= exactCount) {
                    placeholder.complete(null)
                } else {
                    val lateBody = attachPrivatePredecodes(
                        mapOf(
                            pageIndex to startClickPrimaryCandidateRace(
                                pageIndex,
                                CompletableFuture.completedFuture<String?>(null),
                            ),
                        ),
                    ).getValue(pageIndex)
                    lateBody.whenComplete { held, failure ->
                        if (failure == null) placeholder.complete(held)
                        else placeholder.completeExceptionally(failure)
                    }
                }
            }
        }, COORDINATOR_EXECUTOR)
        return Wave(combined)
    }

    private fun discardHeldBody(held: HeldBody) {
        retained.remove(held.body.pageIndex, held)
        held.predecodedOriginal?.close()
        held.fileLease.close()
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
            } else {
                val executor = if (pageIndex == 0) {
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

        fun awaitAdmission(callCancellation: ReaderImageCache.Cancellation): Boolean {
            return if (plan.maximumNumericBound) {
                awaitRollingNumericAdmission(pageIndex, callCancellation)
            } else {
                !closed.get() && pageIndex < effectivePageCount.get()
            }
        }

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
                    )
                }
            }, BODY_EXECUTOR)
        val alternative = candidateFuture.handle { candidate, failure ->
            if (failure == null) candidate else null
        }.thenCompose { candidate ->
            if (candidate == null || closed.get() ||
                candidate.substringAfterLast('.').equals(DEFAULT_EXTENSION, ignoreCase = true)
            ) {
                CompletableFuture.completedFuture(null)
            } else {
                CompletableFuture.supplyAsync(
                    {
                        if (!awaitAdmission(alternativeCancellation)) null
                        else fetchOwnedCandidate(
                            pageIndex,
                            candidate,
                            alternativeCancellation,
                            telemetryAfterImageHeaders = true,
                            onValidImageHeaders = primaryCancellation::cancel,
                        )
                    },
                    FALLBACK_BODY_EXECUTOR,
                )
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
        return CompletableFuture.supplyAsync(
            {
                networkRelease.join()
                if (closed.get() || pageIndex >= effectivePageCount.get()) null
                else fetchOwnedCandidate(
                        pageIndex,
                        candidate,
                        primaryCancellation,
                        telemetryAfterImageHeaders = true,
                    )
            },
            BODY_EXECUTOR,
        )
    }

    /** Re-resolves the finite extension set after the parallel HEAD race completed without a hit. */
    private fun startCompletedHeadMissCandidate(pageIndex: Int): CompletableFuture<HeldBody?> {
        val fallbackCancellation = checkNotNull(fallbackCancellations[pageIndex])
        val result = networkRelease.thenCompose {
            if (closed.get() || pageIndex >= effectivePageCount.get()) {
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
                                )
                            },
                            FALLBACK_BODY_EXECUTOR,
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
        val primary = CompletableFuture.supplyAsync(
            {
                networkRelease.join()
                if (closed.get()) null else fetchOwnedCandidate(
                    pageIndex,
                    candidateAsset(pageIndex, DEFAULT_EXTENSION),
                    primaryCancellation,
                    telemetryAfterImageHeaders = true,
                )
            },
            BODY_EXECUTOR,
        )
        // The fresh document arrives while the common JPG body is still transferring. Resolve
        // uncommon extensions with metadata-only HEADs at that point instead of waiting for a
        // slow three-replica JPG 404 to finish. Only the one successful extension performs GET.
        val fallback = documentValidated.handle { _, failure -> failure == null }
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
                                    )
                                },
                                FALLBACK_BODY_EXECUTOR,
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
                                )
                            },
                            FALLBACK_BODY_EXECUTOR,
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
    ): HeldBody? {
        val prepared = preparedCandidate ?: prepareOwnedCandidate(pageIndex, candidate)
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
        return PreparedCandidate(
            binding,
            ReaderImageCache.resolveClickOwnedAnchorQuarantineRoute(
                manga,
                binding,
                pageIndex,
                candidate,
            ),
        )
    }

    private fun acquireBodyTransferPermit(
        callCancellation: ReaderImageCache.Cancellation,
    ): Boolean {
        while (!closed.get()) {
            callCancellation.throwIfCancelled()
            if (bodyTransferPermits.tryAcquire(BODY_TRANSFER_PERMIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                try {
                    callCancellation.throwIfCancelled()
                    if (!closed.get()) return true
                } catch (failure: Throwable) {
                    bodyTransferPermits.release()
                    throw failure
                }
                bodyTransferPermits.release()
                return false
            }
        }
        return false
    }

    private fun acquireBodyTransferLease(
        callCancellation: ReaderImageCache.Cancellation,
    ): Closeable {
        if (!acquireBodyTransferPermit(callCancellation)) {
            throw InterruptedException("Click-owned body admission closed")
        }
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true)) bodyTransferPermits.release()
        }
    }

    private fun awaitRollingNumericAdmission(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): Boolean {
        val admission = numericAdmissionFutures[pageIndex] ?: return false
        return runCatching {
            callCancellation.throwIfCancelled()
            admission.get()
            callCancellation.throwIfCancelled()
            !closed.get() && pageIndex < effectivePageCount.get()
        }.getOrDefault(false)
    }

    private fun primaryAdmissionFuture(
        pageIndex: Int,
        callCancellation: ReaderImageCache.Cancellation,
    ): CompletableFuture<Boolean> {
        if (!plan.maximumNumericBound) {
            return CompletableFuture.completedFuture(
                !closed.get() && pageIndex < effectivePageCount.get()
            )
        }
        val admission = checkNotNull(numericAdmissionFutures[pageIndex])
        return admission.handle { _, admissionFailure ->
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

    private fun releaseSpeculationDebt(pageIndex: Int) {
        if (!speculationDebtHolders.remove(pageIndex)) return
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
    ): HeldBody? {
        var stage = "create_identity"
        val operationId = NtkStrictSourceOwnershipRegistry.nextOperationId()
        val identity = NtkQuarantineSourceCallIdentity.create(
            sourceSessionId,
            binding.discoveryGeneration,
            binding.bindingDigest,
            pageIndex,
            candidateAsset,
            pageIndex,
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
            Log.d(
                TAG,
                "click_anchor_quarantine_start path=${binding.episodePath}," +
                    "page=$pageIndex,candidate=${candidateAsset.substringAfterLast('/')}",
            )
            stage = "open_file_lease"
            val opened = ReaderImageCache.openQuarantineFileLease(
                appContext,
                binding,
                pageIndex,
                operationId,
            ) { }
            fileLease = opened
            stage = "spool_body"
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
                telemetryAfterImageHeaders = telemetryAfterImageHeaders,
                validImageHeadersSink = {
                    onValidImageHeaders()
                },
                exactImageHeaderSink = {
                    observedCandidates[pageIndex]?.complete(candidateAsset)
                    releaseSpeculationDebt(pageIndex)
                },
                metadataSink = { },
                bodyReadAdmission = { acquireBodyTransferLease(callCancellation) },
            )
            val held = HeldBody(body, opened, binding)
            retained[pageIndex] = held
            if (closed.get() && retained.remove(pageIndex, held)) {
                discardHeldBody(held)
                null
            } else {
                Log.d(
                    TAG,
                    "click_anchor_quarantine_ready path=${binding.episodePath}," +
                        "page=$pageIndex,bytes=${body.encodedLength}",
                )
                held
            }
        } catch (failure: Throwable) {
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
        cancellation.cancel()
        pageCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        fallbackCancellations.values.forEach(ReaderImageCache.Cancellation::cancel)
        earlyProbeOwner?.close()
        networkRelease.complete(Unit)
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
        private const val NUMERIC_RAMP_BATCH_PAGES = 32
        private const val NUMERIC_RAMP_INTERVAL_MS = 150L
        private val SESSION_IDS = AtomicLong(Long.MAX_VALUE / 2L)
        // Only uncommon non-JPG misses use these lanes for bounded extension discovery.
        private val PROBE_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.PROBE_LANES,
        ) { runnable ->
            Thread(runnable, "ntk-click-replica-probe").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        // Connections stay bounded by the replica-local pool ring. The executor admits every
        // finite body to those pools without forcing a second client-side wave.
        private val BODY_EXECUTOR = Executors.newFixedThreadPool(
            NtkClickOwnedManhwaWavePolicy.BODY_LANES,
        ) { runnable ->
            Thread(runnable, "ntk-click-anchor-quarantine").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        // Format corrections and transport retries must not wait behind the primary rolling queue.
        private val FALLBACK_BODY_EXECUTOR = Executors.newFixedThreadPool(8) { runnable ->
            Thread(runnable, "ntk-click-anchor-fallback").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        private val COORDINATOR_EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "ntk-click-forward-coordinator").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        private val NUMERIC_RAMP_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ntk-click-numeric-ramp").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        private val ANCHOR_PREDECODE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ntk-click-anchor-predecode").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        private val BULK_PREDECODE_EXECUTOR = Executors.newFixedThreadPool(6) { runnable ->
            Thread(runnable, "ntk-click-bulk-predecode").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

        fun start(
            context: Context,
            manga: Manga,
            draft: NtkEpisodeDocumentPlanDraft,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier? = null,
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
            )
        }

        fun startFromTrustedPayloadHint(
            context: Context,
            manga: Manga,
            normalizedEpisodePath: String,
            discoveryGeneration: Long,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier?,
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
            )
        }

        fun startFromBoundedNumericCandidates(
            context: Context,
            manga: Manga,
            normalizedEpisodePath: String,
            discoveryGeneration: Long,
            earlyProbeFrontier: NtkClickOwnedManhwaProbeFrontier? = null,
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
            )
        }

        private fun create(
            context: Context,
            manga: Manga,
            plan: NtkClickOwnedQuarantinePlan,
            workId: String,
            episodeId: String,
            earlyClaim: NtkClickOwnedManhwaProbeFrontier.Claim?,
        ): NtkClickOwnedAnchorQuarantine = NtkClickOwnedAnchorQuarantine(
            context,
            manga,
            plan,
            workId,
            episodeId,
            ReaderImageCache.Cancellation(),
            earlyClaim?.candidateFutures ?: emptyMap(),
            earlyClaim?.owner,
        )
    }
}
