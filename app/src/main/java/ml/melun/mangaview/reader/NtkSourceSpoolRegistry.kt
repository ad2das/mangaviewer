package ml.melun.mangaview.reader

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.Preference
import ml.melun.mangaview.activity.NtkQuicFetcher
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun interface NtkAuthoritativeManifestListener {
    fun onInstalled(episodePath: String, manifest: NtkAuthoritativeManifest)
}

fun interface NtkProvisionalEpisodePlanListener {
    fun onInstalled(episodePath: String, plan: NtkProvisionalEpisodePlan)
}

internal object NtkForwardResumeFloorPolicy {
    fun decide(
        rollingAdmission: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        requestedViewerGeneration: Long,
        currentForegroundViewerGeneration: Long,
        requestedPageIndex: Int,
        pageCount: Int,
    ): Int {
        if (!rollingAdmission || !directWifiTransport || cellularResilientTransport ||
            requestedViewerGeneration <= 0L ||
            requestedViewerGeneration != currentForegroundViewerGeneration ||
            requestedPageIndex !in 0 until pageCount
        ) return 0
        return requestedPageIndex
    }
}

internal class NtkAuthoritativeManifestChannel {
    private val listeners = CopyOnWriteArraySet<NtkAuthoritativeManifestListener>()

    fun subscribe(listener: NtkAuthoritativeManifestListener): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    fun publish(
        episodePath: String,
        manifest: NtkAuthoritativeManifest,
        isCurrent: () -> Boolean = { true },
    ) {
        for (listener in listeners) {
            if (!isCurrent()) return
            runCatching { listener.onInstalled(episodePath, manifest) }
                .onFailure {
                    Log.e(
                        "ViewerPerf",
                        "reader_source_authority_listener_failed path=$episodePath",
                        it
                    )
                }
        }
    }
}

internal class NtkProvisionalEpisodePlanChannel {
    private val listeners = CopyOnWriteArraySet<NtkProvisionalEpisodePlanListener>()

    fun subscribe(listener: NtkProvisionalEpisodePlanListener): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    fun publish(
        episodePath: String,
        plan: NtkProvisionalEpisodePlan,
        isCurrent: () -> Boolean = { true },
    ) {
        for (listener in listeners) {
            if (!isCurrent()) return
            runCatching { listener.onInstalled(episodePath, plan) }
                .onFailure {
                    Log.e(
                        "ViewerPerf",
                        "reader_source_plan_listener_failed path=$episodePath",
                        it
                    )
                }
        }
    }
}

data class NtkDiscoveryLease(
    val episodePath: String,
    val generation: NtkDiscoveryGeneration
) {
    fun generationValue(): Long = generation.value
}

enum class NtkManifestInstallStatus {
    INSTALLED_EXACT,
    SAME_EXACT_NO_OP,
    STALE_DISCOVERY_REJECTED,
    INVALID_EXACT_PROOF,
    CONFLICTING_EXACT_AUTHORITY,
    TERMINAL_REJECTED,
    FAILED
}

data class NtkManifestInstallResult(
    val status: NtkManifestInstallStatus,
    val authoritativeManifest: NtkAuthoritativeManifest?,
    val state: NtkSourceState
) {
    val accepted: Boolean
        get() = status == NtkManifestInstallStatus.INSTALLED_EXACT ||
            status == NtkManifestInstallStatus.SAME_EXACT_NO_OP

    val activeSeal: NtkEpisodeManifestSeal?
        get() = authoritativeManifest?.seal
}

enum class NtkPlanInstallStatus {
    INSTALLED_PLAN,
    SAME_PLAN_NO_OP,
    STALE_DISCOVERY_REJECTED,
    INVALID_PLAN_PROOF,
    CONFLICTING_PLAN,
    TERMINAL_REJECTED
}

data class NtkPlanInstallResult(
    val status: NtkPlanInstallStatus,
    val provisionalPlan: NtkProvisionalEpisodePlan?,
    val state: NtkSourceState,
    val planState: NtkPlanState
) {
    val accepted: Boolean
        get() = status == NtkPlanInstallStatus.INSTALLED_PLAN ||
            status == NtkPlanInstallStatus.SAME_PLAN_NO_OP
}

data class NtkSourceRegistrySnapshot(
    val episodePath: String,
    val generation: Long,
    val state: NtkSourceState,
    val planState: NtkPlanState,
    val planProofDigest: String,
    val plannedPageCount: Int,
    val requestIdentityDigest: String,
    val manifestDigest: String,
    val proofDigest: String,
    val sessionId: Long,
    val closeBarrierSerial: Long,
    val quarantineState: NtkQuarantineState,
    val quarantinePhysicalCallsStarted: Int,
    val quarantineActiveCalls: Int,
    val quarantineBodiesSealed: Int,
    val quarantineTempFiles: Int,
    val exactAdoptedBodies: Int,
    val duplicatePhysicalCalls: Int,
    val planReservationBoundary: NtkPlanReservationBoundarySnapshot?
) {
    /** Compatibility-only diagnostic aliases; neither value grants source authority. */
    val planDigest: String
        get() = planProofDigest

    val planPageCount: Int
        get() = plannedPageCount
}

/**
 * Immutable proof of the last authority-free PLAN_RESERVED boundary immediately before exact
 * promotion. A fast exact API may make PLAN_RESERVED too brief for external polling, so the
 * transition itself retains the same evidence without extending or delaying production work.
 */
data class NtkPlanReservationBoundarySnapshot(
    val episodePath: String,
    val generation: Long,
    val sourceState: NtkSourceState,
    val planState: NtkPlanState,
    val planProofDigest: String,
    val requestIdentityDigest: String,
    val plannedPageCount: Int,
    val manifestDigest: String,
    val proofDigest: String,
    val sessionId: Long,
    val quarantineState: NtkQuarantineState,
    val quarantinePhysicalCallsStarted: Int,
    val quarantineActiveCalls: Int,
    val quarantineBodiesSealed: Int,
    val quarantineTempFiles: Int,
    val exactAdoptedBodies: Int,
    val duplicatePhysicalCalls: Int,
    val strictOwnershipPresent: Boolean,
    val capturedAtElapsedNanos: Long
)

internal enum class NtkPlanPromotionValidation {
    ACCEPT,
    WRONG_SOURCE_STATE,
    WRONG_PLAN_STATE,
    STALE_LEASE,
    PLAN_DIGEST_MISMATCH,
    REQUEST_IDENTITY_MISMATCH,
    PAGE_COUNT_MISMATCH,
    ORDERED_ASSETS_MISMATCH,
    INVALID_EXACT_PROOF
}

internal object NtkPlanPromotionPolicy {
    fun samePlan(
        current: NtkProvisionalEpisodePlan,
        incoming: NtkProvisionalEpisodePlan
    ): Boolean =
        current.proof.proofDigestSha256 == incoming.proof.proofDigestSha256 &&
            current.proof.requestIdentity.identityDigestSha256 ==
            incoming.proof.requestIdentity.identityDigestSha256

    fun validate(
        sourceState: NtkSourceState,
        planState: NtkPlanState,
        lease: NtkDiscoveryLease,
        plan: NtkProvisionalEpisodePlan?,
        expectedPlanProofDigest: String,
        incoming: NtkAuthoritativeManifest
    ): NtkPlanPromotionValidation {
        val proof = incoming.proof
        if (sourceState != NtkSourceState.DISCOVERING) {
            return NtkPlanPromotionValidation.WRONG_SOURCE_STATE
        }
        if (planState != NtkPlanState.PLAN_RESERVED || plan == null) {
            return NtkPlanPromotionValidation.WRONG_PLAN_STATE
        }
        if (plan.proof.normalizedEpisodePath != lease.episodePath ||
            plan.proof.discoveryGeneration != lease.generation.value ||
            incoming.seal.normalizedEpisodePath != lease.episodePath ||
            proof.discoveryGeneration != lease.generation.value
        ) return NtkPlanPromotionValidation.STALE_LEASE
        if (expectedPlanProofDigest.isBlank() ||
            plan.proof.proofDigestSha256 != expectedPlanProofDigest
        ) return NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH
        when (proof) {
            is NtkViewerImageApiManifestProof -> {
                if (proof.documentPlanProofDigestSha256 !=
                    plan.proof.proofDigestSha256
                ) return NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH
                if (proof.viewerImageRequestIdentityDigestSha256 !=
                    plan.proof.requestIdentity.identityDigestSha256
                ) return NtkPlanPromotionValidation.REQUEST_IDENTITY_MISMATCH
            }
            is NtkTokenBoundGeneratedManifestProof -> {
                if (proof.documentPlanProof.proofDigestSha256 !=
                    plan.proof.proofDigestSha256 ||
                    proof.documentPlanProof != plan.proof
                ) return NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH
                if (proof.documentPlanProof.requestIdentity.identityDigestSha256 !=
                    plan.proof.requestIdentity.identityDigestSha256
                ) return NtkPlanPromotionValidation.REQUEST_IDENTITY_MISMATCH
            }
            is NtkObservedNumericReplicaManifestProof -> {
                if (proof.documentPlanProof.proofDigestSha256 !=
                    plan.proof.proofDigestSha256 ||
                    proof.documentPlanProof != plan.proof
                ) return NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH
                if (proof.documentPlanProof.requestIdentity.identityDigestSha256 !=
                    plan.proof.requestIdentity.identityDigestSha256
                ) return NtkPlanPromotionValidation.REQUEST_IDENTITY_MISMATCH
            }
            else -> return NtkPlanPromotionValidation.INVALID_EXACT_PROOF
        }
        if (proof.pageCount != plan.pageCount ||
            incoming.seal.pageCount != plan.pageCount
        ) return NtkPlanPromotionValidation.PAGE_COUNT_MISMATCH
        if (incoming.seal.normalizedCanonicalAssets !=
            plan.normalizedOrderedCanonicalAssets ||
            incoming.seal.digestSha256 != plan.orderedAssetsDigestSha256 ||
            proof.orderedAssetsDigestSha256 != plan.orderedAssetsDigestSha256
        ) return NtkPlanPromotionValidation.ORDERED_ASSETS_MISMATCH
        if (!incoming.isProductionClaimable) {
            return NtkPlanPromotionValidation.INVALID_EXACT_PROOF
        }
        return NtkPlanPromotionValidation.ACCEPT
    }
}

/**
 * The single authority boundary for native NTK sources.
 *
 * A document plan is deliberately orthogonal to [NtkSourceState]. Reserving a plan leaves the
 * source DISCOVERING and may only prestart inert source executors. The exact viewer-image API EOF
 * proof is checked against that plan and promoted atomically through RESERVED -> OWNED_PRECLAIM;
 * only [NtkCacheSourceTransport.startOwned] may then create image operations.
 */
object NtkSourceSpoolRegistry {
    private enum class PromotionStage {
        PREPARE_QUEUED,
        PREPARED,
        OWNERSHIP_RESERVING,
        OWNER_CLAIMED,
        SESSION_INSTALL_QUEUED,
        SESSION_INSTALLED,
        COMMIT_QUEUED
    }

    private data class PendingPromotion(
        val token: NtkPromotionToken,
        val manifest: NtkAuthoritativeManifest,
        var stage: PromotionStage,
        val result: CompletableFuture<NtkManifestInstallResult>,
        val validity: AtomicBoolean = AtomicBoolean(true),
        var snapshot: NtkPromotionSnapshot? = null,
        var reservation: NtkStrictSourceOwnershipRegistry.ExactReservation? = null,
        var owner: NtkStrictSourceOwnershipRegistry.Owner? = null,
        var installSucceeded: Boolean = false,
        var installFuture: CompletableFuture<Unit>? = null
    )

    private data class ExactClosingIdentity(
        val token: NtkPromotionToken,
        val manifestDigest: String,
        val sessionId: Long,
        val owner: NtkStrictSourceOwnershipRegistry.Owner
    )

    private data class CloseAction(
        val path: String,
        val discoveryGeneration: Long,
        val session: NtkStrictSourceSession?,
        val token: NtkPromotionToken?,
        val reservation: NtkStrictSourceOwnershipRegistry.ExactReservation?,
        val owner: NtkStrictSourceOwnershipRegistry.Owner?,
        val installSucceeded: Boolean,
        val installFuture: CompletableFuture<Unit>?,
        val promotionResult: CompletableFuture<NtkManifestInstallResult>?,
        val cause: Throwable,
        val endDiscoveryFence: Boolean,
        val executionBootstrapFuture: CompletableFuture<NtkStrictSourceExecutionBootstrap>? = null,
    )

    /** Immutable inputs captured under the path lock and materialized without holding it. */
    private data class PlanSessionConstructionSpec(
        val context: Context,
        val manga: Manga,
        val requestedInitialPageIndexHint: Int,
        val forwardResumeViewerGeneration: Long,
        val rollingAdmission: Boolean,
        val viewerImageApiBacked: Boolean,
        val executionBootstrapFuture: CompletableFuture<NtkStrictSourceExecutionBootstrap>,
    )

    private class Entry(
        val context: Context,
        val manga: Manga,
        val lease: NtkDiscoveryLease,
        val requestedInitialPageIndexHint: Int,
        val forwardResumeViewerGeneration: Long,
        val rollingAdmission: Boolean,
    ) {
        var effectiveInitialPageIndex: Int =
            if (rollingAdmission) 0 else requestedInitialPageIndexHint
        var forwardResumeFinalized: Boolean = !rollingAdmission
        var state: NtkSourceState = NtkSourceState.DISCOVERING
        var planState: NtkPlanState = NtkPlanState.NONE
        var quarantineState: NtkQuarantineState = NtkQuarantineState.NONE
        var quarantineAssetEvidence: NtkQuarantineAssetEvidence? = null
        var provisionalPlan: NtkProvisionalEpisodePlan? = null
        var planBinding: NtkQuarantinePlanBinding? = null
        var sourceSession: NtkStrictSourceSession? = null
        var executionBootstrapFuture: CompletableFuture<NtkStrictSourceExecutionBootstrap>? = null
        var quarantineStartProof: NtkQuarantineStartProof? = null
        var promotionNonce: Long = 0L
        var pendingPromotion: PendingPromotion? = null
        var exactClosingIdentity: ExactClosingIdentity? = null
        var authoritative: NtkAuthoritativeManifest? = null
        var planReservationBoundary: NtkPlanReservationBoundarySnapshot? = null
        var transport: NtkCacheSourceTransport? = null
        var claimed = false
        var closeBarrier: NtkSourceCloseBarrierProof? = null
        var quarantineCloseBarrier: NtkQuarantineCloseBarrierProof? = null
        var terminalCause: String = ""
    }

    private val generationSequence = AtomicLong(1L)
    private val PROMOTION_NONCE_SEQUENCE = AtomicLong(1L)
    private val entries = ConcurrentHashMap<String, Entry>()
    /** Closing generations detached from the active path slot so the same path can reopen now. */
    private val retiredEntries = ConcurrentHashMap<NtkDiscoveryLease, Entry>()
    private val mutationLocks = ConcurrentHashMap<String, Any>()
    private val provisionalEpisodePlanChannel = NtkProvisionalEpisodePlanChannel()
    private val authoritativeManifestChannel = NtkAuthoritativeManifestChannel()
    // Created without a worker thread. The first task is submitted only by beginDiscovery after
    // the committed episode click, so this overlaps inert executor construction with the strict
    // document request without becoming a pre-click warm-up or starting any image operation.
    private val executionBootstrapConstructor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ntk-strict-bootstrap-constructor").apply { isDaemon = true }
    }

    @JvmStatic
    fun addProvisionalEpisodePlanListener(
        listener: NtkProvisionalEpisodePlanListener
    ): Closeable = provisionalEpisodePlanChannel.subscribe(listener)

    @JvmStatic
    fun addAuthoritativeManifestListener(listener: NtkAuthoritativeManifestListener): Closeable =
        authoritativeManifestChannel.subscribe(listener)

    @JvmStatic
    fun beginDiscovery(context: Context?, manga: Manga?): NtkDiscoveryLease? {
        return beginDiscoveryInternal(context, manga, rollingAdmission = false)
    }

    @JvmStatic
    @JvmOverloads
    fun beginColdRollingDiscovery(
        context: Context?,
        manga: Manga?,
        initialPageIndexHint: Int = 0,
        forwardResumeViewerGeneration: Long = 0L,
    ): NtkDiscoveryLease? {
        return beginDiscoveryInternal(
            context,
            manga,
            rollingAdmission = true,
            rollingInitialPageIndexHint = initialPageIndexHint,
            forwardResumeViewerGeneration = forwardResumeViewerGeneration,
        )
    }

    private fun beginDiscoveryInternal(
        context: Context?,
        manga: Manga?,
        rollingAdmission: Boolean,
        rollingInitialPageIndexHint: Int = 0,
        forwardResumeViewerGeneration: Long = 0L,
    ): NtkDiscoveryLease? {
        if (context == null || manga == null) return null
        val path = normalizedPath(manga.ntkEpisodePath) ?: return null
        val appContext = context.applicationContext
        return synchronized(mutationLock(path)) {
            val current = entries[path]
            if (current != null) {
                if (current.state == NtkSourceState.TERMINAL_CLOSING) {
                    // A closing entry must never be recycled as a new discovery generation.
                    return@synchronized null
                }
                if (current.state != NtkSourceState.TERMINAL_CLOSED) {
                    return@synchronized current.lease
                }
            }
            val lease = NtkDiscoveryLease(
                path,
                NtkDiscoveryGeneration(generationSequence.getAndIncrement())
            )
            val initialPage = if (rollingAdmission) {
                rollingInitialPageIndexHint.coerceAtLeast(0)
            } else {
                deriveInitialPageIndex(appContext, manga)
            }
            val entry = Entry(
                appContext,
                manga,
                lease,
                initialPage,
                forwardResumeViewerGeneration,
                rollingAdmission,
            )
            val bootstrapStartedAt = SystemClock.elapsedRealtime()
            entry.executionBootstrapFuture = CompletableFuture.supplyAsync({
                NtkStrictSourceExecutionBootstrap(
                    deferWorkerLanes = path.startsWith("/manhwa/", ignoreCase = true),
                ).also { bootstrap ->
                    Log.d(
                        "ViewerPerf",
                        "reader_source_execution_bootstrap_ready path=$path," +
                            "generation=${lease.generation.value}," +
                            "threads=${bootstrap.startedThreadCount()}," +
                            "elapsedMs=${SystemClock.elapsedRealtime() - bootstrapStartedAt}"
                    )
                }
            }, executionBootstrapConstructor)
            entries[path] = entry
            NtkStrictSourceOwnershipRegistry.beginDiscoveryFence(path, lease.generation.value)
            ReaderImageCache.cancelNtkGeneratedForegroundWorkForEpisode(
                path,
                manga.baseMode,
                "strict_manifest_discovery"
            )
            ReaderImageCache.suppressPermitlessInitialGeneratedForeground(
                path,
                "strict_manifest_discovery"
            )
            logState(entry, NtkSourceState.ABSENT, NtkSourceState.DISCOVERING, "begin")
            lease
        }
    }

    @JvmStatic
    fun observeQuarantineAssetEvidence(
        lease: NtkDiscoveryLease?,
        evidence: NtkQuarantineAssetEvidence?
    ): Boolean {
        if (lease == null || evidence == null ||
            evidence.normalizedEpisodePath != lease.episodePath ||
            evidence.discoveryGeneration != lease.generation.value
        ) return false
        var action: CloseAction? = null
        val observed = synchronized(mutationLock(lease.episodePath)) {
            val entry = entries[lease.episodePath] ?: return@synchronized false
            if (entry.lease != lease || entry.state != NtkSourceState.DISCOVERING ||
                entry.planState == NtkPlanState.TERMINAL
            ) return@synchronized false
            val current = entry.quarantineAssetEvidence
            if (current != null && current.evidenceDigest != evidence.evidenceDigest) {
                action = failClosedLocked(entry, "conflicting_quarantine_asset_evidence")
                return@synchronized false
            }
            entry.quarantineAssetEvidence = evidence
            Log.d(
                "ViewerPerf",
                "reader_source_quarantine_evidence path=${lease.episodePath}," +
                    "generation=${lease.generation.value}," +
                    "sources=${evidence.normalizedOrderedCanonicalAssets.size}," +
                    "orderedAssetsDigest=${evidence.orderedAssetsDigest}," +
                    "evidenceDigest=${evidence.evidenceDigest}"
            )
            true
        }
        performCloseAction(action)
        return observed
    }

    @JvmStatic
    fun currentQuarantineAssetEvidence(
        lease: NtkDiscoveryLease?
    ): NtkQuarantineAssetEvidence? {
        if (lease == null) return null
        return synchronized(mutationLock(lease.episodePath)) {
            entries[lease.episodePath]
                ?.takeIf { it.lease == lease && it.state == NtkSourceState.DISCOVERING }
                ?.quarantineAssetEvidence
        }
    }

    @JvmStatic
    fun reserveDocumentPlan(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        incoming: NtkProvisionalEpisodePlan?,
        initialExactBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody> = emptyMap(),
        streamedExactBodies: NtkClickOwnedExactBodyStream? = null,
    ): NtkPlanInstallResult = reserveDocumentPlanInternal(
        context,
        manga,
        lease,
        incoming,
        initialExactBodies,
        streamedExactBodies,
        null,
    )

    /**
     * Reserves a plan whose ordered source table is proven directly by the same token-bound
     * document authority that will immediately promote it. This is intentionally separate from
     * [reserveDocumentPlan]: callers cannot turn an arbitrary speculative table into source
     * evidence by setting a flag.
     */
    @JvmStatic
    fun reserveTokenBoundGeneratedDocumentPlan(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        incoming: NtkProvisionalEpisodePlan?,
        authorityEvidence: NtkAuthoritativeManifest?,
        initialExactBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody> = emptyMap(),
        streamedExactBodies: NtkClickOwnedExactBodyStream? = null,
    ): NtkPlanInstallResult = reserveDocumentPlanInternal(
        context,
        manga,
        lease,
        incoming,
        initialExactBodies,
        streamedExactBodies,
        authorityEvidence,
    )

    @JvmStatic
    fun reserveObservedNumericReplicaDocumentPlan(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        incoming: NtkProvisionalEpisodePlan?,
        authorityEvidence: NtkAuthoritativeManifest?,
        streamedExactBodies: NtkClickOwnedExactBodyStream?,
    ): NtkPlanInstallResult = reserveDocumentPlanInternal(
        context,
        manga,
        lease,
        incoming,
        emptyMap(),
        streamedExactBodies,
        authorityEvidence,
    )

    private fun reserveDocumentPlanInternal(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        incoming: NtkProvisionalEpisodePlan?,
        initialExactBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody>,
        streamedExactBodies: NtkClickOwnedExactBodyStream?,
        tokenBoundAuthorityEvidence: NtkAuthoritativeManifest?,
    ): NtkPlanInstallResult {
        if (context == null || manga == null || lease == null || incoming == null ||
            incoming.proof.normalizedEpisodePath != lease.episodePath ||
            incoming.proof.discoveryGeneration != lease.generation.value ||
            (tokenBoundAuthorityEvidence != null && !tokenBoundPlanEvidenceMatches(
                lease,
                incoming,
                tokenBoundAuthorityEvidence,
            ))
        ) return invalidPlanResult()

        val path = lease.episodePath
        var constructionSpec: PlanSessionConstructionSpec? = null
        var sessionToStart: NtkStrictSourceSession? = null
        var startFuture: CompletableFuture<NtkQuarantineStartProof>? = null
        var closeAction: CloseAction? = null
        val admission = synchronized(mutationLock(path)) {
            val entry = entries[path]
                ?: return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.STALE_DISCOVERY_REJECTED,
                    null,
                    NtkSourceState.ABSENT,
                    NtkPlanState.NONE
                )
            if (entry.lease != lease) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.STALE_DISCOVERY_REJECTED,
                    entry.provisionalPlan,
                    entry.state,
                    entry.planState
                )
            }
            val current = entry.provisionalPlan
            if (current != null && NtkPlanPromotionPolicy.samePlan(current, incoming)) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.SAME_PLAN_NO_OP,
                    current,
                    entry.state,
                    entry.planState
                )
            }
            if (current != null || entry.planState != NtkPlanState.NONE) {
                closeAction = failClosedLocked(entry, "conflicting_document_plan")
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.CONFLICTING_PLAN,
                    current,
                    entry.state,
                    entry.planState
                )
            }
            if (entry.state != NtkSourceState.DISCOVERING) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.TERMINAL_REJECTED,
                    current,
                    entry.state,
                    entry.planState
                )
            }
            if (!planEvidenceMatches(entry, incoming, tokenBoundAuthorityEvidence)) {
                closeAction = failClosedLocked(
                    entry,
                    "document_plan_quarantine_evidence_mismatch"
                )
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.INVALID_PLAN_PROOF,
                    null,
                    entry.state,
                    entry.planState
                )
            }
            constructionSpec = PlanSessionConstructionSpec(
                entry.context,
                entry.manga,
                entry.requestedInitialPageIndexHint,
                entry.forwardResumeViewerGeneration,
                entry.rollingAdmission,
                entry.quarantineAssetEvidence != null,
                checkNotNull(entry.executionBootstrapFuture) {
                    "Strict execution bootstrap was not click-owned"
                },
            )
            // This is an admission result only. Thread prestart, page-table construction and
            // session initialization happen below without the lifecycle-visible path lock.
            NtkPlanInstallResult(
                NtkPlanInstallStatus.INSTALLED_PLAN,
                incoming,
                entry.state,
                NtkPlanState.PLAN_RESERVED,
            )
        }
        performCloseAction(closeAction)
        if (admission.status != NtkPlanInstallStatus.INSTALLED_PLAN) return admission

        val spec = checkNotNull(constructionSpec)
        // Canonical-list normalization/digest checks can scale with the whole episode and must not
        // extend the path lock observed by Activity teardown.
        val binding = NtkQuarantinePlanBinding.from(incoming)
        val callbacksEnabled = AtomicBoolean(false)
        val bootstrap = awaitActorFuture(spec.executionBootstrapFuture)
        val cellularResilientTransport = runCatching {
            MainApplication.getHttpClient().isNtkCellularResilientTransportActive()
        }.getOrDefault(false)
        val directWifiTransport = !cellularResilientTransport && runCatching {
            MainApplication.getHttpClient().isNtkWifiTransportActive()
        }.getOrDefault(false)
        // Capture a positive current-viewer identity at construction. `adjacentPrefetch == false`
        // alone is not current authority: a retiring/previous session can also lack that grant.
        val observedViewerGeneration = ViewerTelemetry.activeGeneration()
        val currentForegroundViewerGeneration = observedViewerGeneration.takeIf {
            it > 0L &&
                ViewerTelemetry.isActiveEpisode(binding.episodePath) &&
                ViewerTelemetry.activeGeneration() == it
        } ?: 0L
        // Resolve the optimization once, at the exact source-session construction edge. A raw
        // bookmark is not authority: direct Wi-Fi must still be live, cellular/SNI must be off,
        // and the requesting viewer generation/path must still own the foreground. Invalid or
        // stale bookmarks fail to source zero instead of being coerced to a different page.
        val initialPageIndex = if (spec.rollingAdmission) {
            NtkForwardResumeFloorPolicy.decide(
                rollingAdmission = true,
                directWifiTransport = directWifiTransport,
                cellularResilientTransport = cellularResilientTransport,
                requestedViewerGeneration = spec.forwardResumeViewerGeneration,
                currentForegroundViewerGeneration = currentForegroundViewerGeneration,
                requestedPageIndex = spec.requestedInitialPageIndexHint,
                pageCount = binding.normalizedOrderedCanonicalAssets.size,
            )
        } else {
            spec.requestedInitialPageIndexHint
                .takeIf { it in binding.normalizedOrderedCanonicalAssets.indices } ?: 0
        }
        val effectiveStreamedExactBodies = streamedExactBodies?.let { stream ->
            if (initialPageIndex == 0 && 0 !in stream.bodyFutures) {
                // The request began as Wi-Fi resume but the source-construction transport or
                // viewer owner no longer qualifies. Cancel its suffix-only overlap and restore
                // the ordinary source-zero path; otherwise the stream's first-frame gate could
                // retain mobile/SNI work behind a page it does not own.
                stream.close()
                null
            } else {
                stream
            }
        }
        val session = try {
            NtkStrictSourceSession(
                context = spec.context,
                manga = spec.manga,
                planBinding = binding,
                initialPageIndex = initialPageIndex,
                executionBootstrap = bootstrap,
                onQuarantineCloseBarrier = { barrier ->
                    if (callbacksEnabled.get()) {
                        completeQuarantineCloseBarrier(path, lease, barrier)
                    }
                },
                onExactCloseBarrier = { barrier ->
                    if (callbacksEnabled.get()) completeCloseBarrier(path, lease, barrier)
                },
                onTerminalFailure = { failure ->
                    if (callbacksEnabled.get()) failQuarantineSession(path, lease, failure)
                },
                rollingAdmission = spec.rollingAdmission,
                initialExactBodies = initialExactBodies,
                streamedExactBodies = effectiveStreamedExactBodies,
                viewerImageApiBacked = spec.viewerImageApiBacked,
                cellularResilientTransport = cellularResilientTransport,
                directWifiTransport = directWifiTransport,
                wifiQuicBulkTransport =
                    spec.viewerImageApiBacked &&
                        !binding.episodePath.startsWith("/webtoon/") &&
                        directWifiTransport &&
                        NtkQuicFetcher.isAvailable(),
                currentForegroundViewerGeneration = currentForegroundViewerGeneration,
                adjacentPrefetch = directWifiTransport &&
                    currentForegroundViewerGeneration == 0L &&
                    ReaderImageCache.hasActiveAdjacentNtkForegroundViewerGrant(
                        binding.episodePath,
                    ),
            )
        } catch (failure: Throwable) {
            bootstrap.abortConstructionFailure()
            failQuarantineSession(path, lease, failure)
            return NtkPlanInstallResult(
                NtkPlanInstallStatus.INVALID_PLAN_PROOF,
                null,
                NtkSourceState.TERMINAL_CLOSING,
                NtkPlanState.TERMINAL,
            )
        }

        closeAction = null
        val result = synchronized(mutationLock(path)) {
            val entry = entries[path]
                ?: return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.STALE_DISCOVERY_REJECTED,
                    null,
                    NtkSourceState.ABSENT,
                    NtkPlanState.NONE,
                )
            if (entry.lease != lease) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.STALE_DISCOVERY_REJECTED,
                    entry.provisionalPlan,
                    entry.state,
                    entry.planState,
                )
            }
            val current = entry.provisionalPlan
            if (current != null && NtkPlanPromotionPolicy.samePlan(current, incoming)) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.SAME_PLAN_NO_OP,
                    current,
                    entry.state,
                    entry.planState,
                )
            }
            if (current != null || entry.planState != NtkPlanState.NONE) {
                closeAction = failClosedLocked(entry, "conflicting_document_plan")
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.CONFLICTING_PLAN,
                    current,
                    entry.state,
                    entry.planState,
                )
            }
            if (entry.state != NtkSourceState.DISCOVERING) {
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.TERMINAL_REJECTED,
                    current,
                    entry.state,
                    entry.planState,
                )
            }
            if (!planEvidenceMatches(entry, incoming, tokenBoundAuthorityEvidence)) {
                closeAction = failClosedLocked(
                    entry,
                    "document_plan_quarantine_evidence_mismatch",
                )
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.INVALID_PLAN_PROOF,
                    null,
                    entry.state,
                    entry.planState,
                )
            }
            entry.provisionalPlan = incoming
            entry.planBinding = binding
            entry.effectiveInitialPageIndex = initialPageIndex
            entry.forwardResumeFinalized = true
            entry.sourceSession = session
            entry.executionBootstrapFuture = null
            entry.planState = NtkPlanState.PLAN_RESERVED
            entry.quarantineState = NtkQuarantineState.SPOOLING
            sessionToStart = session
            callbacksEnabled.set(true)
            startFuture = try {
                session.enqueueStartQuarantined()
            } catch (failure: Throwable) {
                closeAction = failClosedLocked(
                    entry,
                    "quarantine_start_enqueue_${failure.javaClass.simpleName}",
                )
                return@synchronized NtkPlanInstallResult(
                    NtkPlanInstallStatus.INVALID_PLAN_PROOF,
                    null,
                    entry.state,
                    entry.planState,
                )
            }
            logPlan(entry, NtkPlanState.NONE, entry.planState, "document_plan_valid")
            NtkPlanInstallResult(
                NtkPlanInstallStatus.INSTALLED_PLAN,
                incoming,
                entry.state,
                entry.planState,
            )
        }
        performCloseAction(closeAction)
        if (result.status != NtkPlanInstallStatus.INSTALLED_PLAN) {
            session.requestClose(IllegalStateException("Document plan session lost admission"))
            return result
        }
        if (result.status == NtkPlanInstallStatus.INSTALLED_PLAN) {
            try {
                val proof = awaitActorFuture(checkNotNull(startFuture))
                val stored = synchronized(mutationLock(path)) {
                    val entry = entries[path] ?: return@synchronized false
                    if (entry.lease != lease || entry.sourceSession !== sessionToStart ||
                        entry.state.ordinal >= NtkSourceState.TERMINAL_CLOSING.ordinal
                    ) return@synchronized false
                    entry.quarantineStartProof = proof
                    entry.quarantineState = NtkQuarantineState.SPOOLING
                    true
                }
                if (!stored) {
                    sessionToStart?.requestClose(
                        IllegalStateException("Quarantine start completed for a stale entry")
                    )
                    return NtkPlanInstallResult(
                        NtkPlanInstallStatus.TERMINAL_REJECTED,
                        null,
                        NtkSourceState.TERMINAL_CLOSING,
                        NtkPlanState.TERMINAL
                    )
                }
                result.provisionalPlan?.let { installed ->
                    if (isCurrentPlanPublication(lease, installed)) {
                        provisionalEpisodePlanChannel.publish(path, installed) {
                            isCurrentPlanPublication(lease, installed)
                        }
                    }
                }
            } catch (failure: Throwable) {
                Log.e(
                    "ViewerPerf",
                    "reader_quarantine_start_failed path=$path," +
                        "generation=${lease.generation.value}," +
                        "error=${failure.javaClass.simpleName}",
                    failure,
                )
                failQuarantineSession(path, lease, failure)
                return NtkPlanInstallResult(
                    NtkPlanInstallStatus.INVALID_PLAN_PROOF,
                    null,
                    NtkSourceState.TERMINAL_CLOSING,
                    NtkPlanState.TERMINAL
                )
            }
        }
        return result
    }

    private fun planEvidenceMatches(
        entry: Entry,
        incoming: NtkProvisionalEpisodePlan,
        tokenBoundAuthorityEvidence: NtkAuthoritativeManifest?,
    ): Boolean {
        val evidence = entry.quarantineAssetEvidence
        if (evidence != null) {
            return evidence.viewerRequestIdentityDigest ==
                incoming.proof.requestIdentity.identityDigestSha256 &&
                evidence.normalizedOrderedCanonicalAssets ==
                incoming.normalizedOrderedCanonicalAssets &&
                evidence.orderedAssetsDigest == incoming.orderedAssetsDigestSha256 &&
                evidence.sourceRequestPolicyVersion == incoming.proof.sourceRequestPolicyVersion
        }
        return tokenBoundPlanEvidenceMatches(
            entry.lease,
            incoming,
            tokenBoundAuthorityEvidence,
        )
    }

    private fun tokenBoundPlanEvidenceMatches(
        lease: NtkDiscoveryLease,
        plan: NtkProvisionalEpisodePlan,
        authority: NtkAuthoritativeManifest?,
    ): Boolean {
        val exact = authority ?: return false
        val documentProof = when (val proof = exact.proof) {
            is NtkTokenBoundGeneratedManifestProof -> proof.documentPlanProof
            is NtkObservedNumericReplicaManifestProof -> proof.documentPlanProof
            else -> return false
        }
        return exact.isProductionClaimable &&
            exact.seal.normalizedEpisodePath == lease.episodePath &&
            exact.seal.revision == lease.generation.value &&
            exact.proof.discoveryGeneration == lease.generation.value &&
            documentProof == plan.proof &&
            documentProof.proofDigestSha256 == plan.proof.proofDigestSha256 &&
            documentProof.requestIdentity.identityDigestSha256 ==
                plan.proof.requestIdentity.identityDigestSha256 &&
            exact.seal.pageCount == plan.pageCount &&
            exact.seal.normalizedCanonicalAssets == plan.normalizedOrderedCanonicalAssets &&
            exact.seal.digestSha256 == plan.orderedAssetsDigestSha256
    }

    @JvmStatic
    fun promoteDocumentPlanToExact(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        documentPlanProofDigestSha256: String?,
        incoming: NtkAuthoritativeManifest?
    ): NtkManifestInstallResult {
        val proof = incoming?.proof
        val boundPlanProofDigest = when (proof) {
            is NtkViewerImageApiManifestProof -> proof.documentPlanProofDigestSha256
            is NtkTokenBoundGeneratedManifestProof ->
                proof.documentPlanProof.proofDigestSha256
            is NtkObservedNumericReplicaManifestProof ->
                proof.documentPlanProof.proofDigestSha256
            else -> ""
        }
        if (context == null || manga == null || lease == null || incoming == null ||
            proof == null || boundPlanProofDigest.isBlank() ||
            !incoming.isProductionClaimable ||
            incoming.seal.normalizedEpisodePath != lease.episodePath ||
            proof.discoveryGeneration != lease.generation.value ||
            documentPlanProofDigestSha256.isNullOrBlank() ||
            boundPlanProofDigest != documentPlanProofDigestSha256
        ) return invalidExactResult()

        return installExactLocked(
            path = lease.episodePath,
            lease = lease,
            incoming = incoming,
            requirePlan = true,
            expectedPlanProofDigest = documentPlanProofDigestSha256
        )
    }

    @JvmStatic
    fun installExplicitExact(
        context: Context?,
        manga: Manga?,
        lease: NtkDiscoveryLease?,
        incoming: NtkAuthoritativeManifest?
    ): NtkManifestInstallResult {
        if (context == null || manga == null || lease == null || incoming == null ||
            !incoming.isProductionClaimable ||
            incoming.proof.kind != NtkExactManifestProofKind.EPISODE_DOCUMENT_GENERATED ||
            incoming.seal.normalizedEpisodePath != lease.episodePath ||
            incoming.proof.discoveryGeneration != lease.generation.value
        ) return invalidExactResult()
        return installExactLocked(
            path = lease.episodePath,
            lease = lease,
            incoming = incoming,
            requirePlan = false,
            expectedPlanProofDigest = ""
        )
    }

    private fun installExactLocked(
        path: String,
        lease: NtkDiscoveryLease,
        incoming: NtkAuthoritativeManifest,
        requirePlan: Boolean,
        expectedPlanProofDigest: String
    ): NtkManifestInstallResult {
        if (!requirePlan) {
            return NtkManifestInstallResult(
                NtkManifestInstallStatus.INVALID_EXACT_PROOF,
                null,
                NtkSourceState.ABSENT
            )
        }
        var session: NtkStrictSourceSession? = null
        var token: NtkPromotionToken? = null
        var pendingResult: CompletableFuture<NtkManifestInstallResult>? = null
        var prepareFuture: CompletableFuture<NtkPromotionSnapshot>? = null
        var immediateResult: NtkManifestInstallResult? = null
        var closeAction: CloseAction? = null
        synchronized(mutationLock(path)) {
            val entry = entries[path]
                ?: run {
                    immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.STALE_DISCOVERY_REJECTED,
                    null,
                    NtkSourceState.ABSENT
                    )
                    return@synchronized
                }
            if (entry.lease != lease) {
                immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.STALE_DISCOVERY_REJECTED,
                    entry.authoritative,
                    entry.state
                )
                return@synchronized
            }

            val current = entry.authoritative
            if (current != null &&
                current.seal.hasSameAuthority(incoming.seal) &&
                current.proof.proofDigestSha256 == incoming.proof.proofDigestSha256
            ) {
                immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.SAME_EXACT_NO_OP,
                    current,
                    entry.state
                )
                return@synchronized
            }
            val pending = entry.pendingPromotion
            if (pending != null) {
                if (pending.manifest.seal.hasSameAuthority(incoming.seal) &&
                    pending.manifest.proof.proofDigestSha256 ==
                    incoming.proof.proofDigestSha256
                ) {
                    pendingResult = pending.result
                } else {
                    closeAction = failClosedLocked(
                        entry,
                        "conflicting_exact_during_promotion"
                    )
                    immediateResult = NtkManifestInstallResult(
                        NtkManifestInstallStatus.CONFLICTING_EXACT_AUTHORITY,
                        null,
                        entry.state
                    )
                }
                return@synchronized
            }
            if (current != null || entry.state.ordinal >= NtkSourceState.OWNED_PRECLAIM.ordinal) {
                closeAction = failClosedLocked(entry, "different_exact_digest_after_owned")
                immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.CONFLICTING_EXACT_AUTHORITY,
                    current,
                    entry.state
                )
                return@synchronized
            }
            if (entry.state != NtkSourceState.DISCOVERING) {
                immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.TERMINAL_REJECTED,
                    current,
                    entry.state
                )
                return@synchronized
            }

            val plan = entry.provisionalPlan
            val validation = NtkPlanPromotionPolicy.validate(
                entry.state,
                entry.planState,
                lease,
                plan,
                expectedPlanProofDigest,
                incoming
            )
            if (validation != NtkPlanPromotionValidation.ACCEPT) {
                closeAction = failClosedLocked(entry, "exact_manifest_plan_mismatch")
                Log.e(
                    "ViewerPerf",
                    "reader_source_exact_plan_validation_failed path=$path," +
                        "validation=$validation"
                )
                immediateResult = NtkManifestInstallResult(
                    NtkManifestInstallStatus.INVALID_EXACT_PROOF,
                    entry.authoritative,
                    entry.state
                )
                return@synchronized
            }
            val activeSession = checkNotNull(entry.sourceSession) {
                "Reserved document plan omitted its source session"
            }
            val binding = checkNotNull(entry.planBinding)
            check(binding.bindingDigest == plan?.bindingDigestSha256)
            val quarantine = activeSession.quarantineDebugSnapshot()
            entry.planReservationBoundary = NtkPlanReservationBoundarySnapshot(
                episodePath = path,
                generation = lease.generation.value,
                sourceState = entry.state,
                planState = entry.planState,
                planProofDigest = plan?.proof?.proofDigestSha256.orEmpty(),
                requestIdentityDigest =
                    plan?.proof?.requestIdentity?.identityDigestSha256.orEmpty(),
                plannedPageCount = plan?.pageCount ?: 0,
                manifestDigest = entry.authoritative?.seal?.digestSha256.orEmpty(),
                proofDigest = entry.authoritative?.proof?.proofDigestSha256.orEmpty(),
                sessionId = activeSession.sessionId,
                quarantineState = quarantine.quarantineState,
                quarantinePhysicalCallsStarted =
                    quarantine.quarantinePhysicalCallsStarted,
                quarantineActiveCalls = quarantine.quarantineActiveCalls,
                quarantineBodiesSealed = quarantine.quarantineBodiesSealed,
                quarantineTempFiles = quarantine.quarantineTempFiles,
                exactAdoptedBodies = quarantine.exactAdoptedBodies,
                duplicatePhysicalCalls = quarantine.duplicatePhysicalCalls,
                strictOwnershipPresent =
                    NtkStrictSourceOwnershipRegistry.snapshot(path) != null,
                capturedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
            )

            val previousState = entry.state
            val previousPlanState = entry.planState
            val promotionNonce = PROMOTION_NONCE_SEQUENCE.getAndIncrement()
            val issuedToken = NtkPromotionToken(
                path,
                lease.generation.value,
                activeSession.sessionId,
                binding.bindingDigest,
                incoming.seal.digestSha256,
                incoming.proof.proofDigestSha256,
                promotionNonce
            )
            val resultFuture = CompletableFuture<NtkManifestInstallResult>()
            entry.promotionNonce = promotionNonce
            entry.quarantineState = NtkQuarantineState.PROMOTION_FROZEN
            entry.state = NtkSourceState.RESERVED
            entry.planState = NtkPlanState.PROMOTED
            entry.pendingPromotion = PendingPromotion(
                issuedToken,
                incoming,
                PromotionStage.PREPARE_QUEUED,
                resultFuture
            )
            val issuedPending = checkNotNull(entry.pendingPromotion)
            val queuedPrepare = activeSession.enqueuePreparePromotion(
                issuedToken,
                issuedPending.validity
            )
            logState(entry, previousState, entry.state, "exact_proof_valid")
            if (previousPlanState != entry.planState) {
                logPlan(entry, previousPlanState, entry.planState, "exact_proof_promoted")
            }
            session = activeSession
            token = issuedToken
            pendingResult = resultFuture
            prepareFuture = queuedPrepare
        }
        performCloseAction(closeAction)
        immediateResult?.let { return it }
        val joinOnly = prepareFuture == null
        if (joinOnly) return awaitActorFuture(checkNotNull(pendingResult))

        val activeSession = checkNotNull(session)
        val activeToken = checkNotNull(token)
        val resultFuture = checkNotNull(pendingResult)
        val snapshot = try {
            awaitActorFuture(checkNotNull(prepareFuture))
        } catch (failure: Throwable) {
            return failPendingPromotion(
                path, lease, activeSession, activeToken, resultFuture, failure
            )
        }

        val prepareCurrent = synchronized(mutationLock(path)) {
            val entry = entries[path]
            val pending = entry?.pendingPromotion
            if (entry?.lease != lease || pending?.token != activeToken) {
                false
            } else {
                pending.snapshot = snapshot
                pending.stage = PromotionStage.PREPARED
                pending.stage = PromotionStage.OWNERSHIP_RESERVING
                true
            }
        }
        if (!prepareCurrent) {
            return failPendingPromotion(
                path,
                lease,
                activeSession,
                activeToken,
                resultFuture,
                IllegalStateException("Promotion token invalidated after prepare")
            )
        }

        val reservation = try {
            NtkStrictSourceOwnershipRegistry.reserveExact(activeToken)
        } catch (failure: Throwable) {
            return failPendingPromotion(
                path, lease, activeSession, activeToken, resultFuture, failure
            )
        }
        val reservationCurrent = synchronized(mutationLock(path)) {
            val entry = entries[path]
            val pending = entry?.pendingPromotion
            if (entry?.lease != lease || pending?.token != activeToken) {
                false
            } else {
                pending.reservation = reservation
                true
            }
        }
        if (!reservationCurrent) {
            NtkStrictSourceOwnershipRegistry.rollbackReservation(reservation)
            return failPendingPromotion(
                path,
                lease,
                activeSession,
                activeToken,
                resultFuture,
                IllegalStateException("Promotion token invalidated after exact reservation")
            )
        }

        val owner = try {
            NtkStrictSourceOwnershipRegistry.claimExact(
                reservation,
                activeToken.sessionId
            )
        } catch (failure: Throwable) {
            NtkStrictSourceOwnershipRegistry.rollbackReservation(reservation)
            return failPendingPromotion(
                path, lease, activeSession, activeToken, resultFuture, failure
            )
        }

        var installFuture: CompletableFuture<Unit>? = null
        val ownerCurrent = synchronized(mutationLock(path)) {
            val entry = entries[path]
            val pending = entry?.pendingPromotion
            if (entry?.lease != lease || pending?.token != activeToken) {
                false
            } else {
                pending.owner = owner
                pending.stage = PromotionStage.OWNER_CLAIMED
                entry.exactClosingIdentity = ExactClosingIdentity(
                    activeToken,
                    incoming.seal.digestSha256,
                    activeToken.sessionId,
                    owner
                )
                pending.stage = PromotionStage.SESSION_INSTALL_QUEUED
                installFuture = activeSession.enqueueInstallExactBinding(
                    activeToken,
                    pending.validity,
                    owner,
                    incoming,
                    snapshot
                )
                pending.installFuture = installFuture
                true
            }
        }
        if (!ownerCurrent) {
            NtkStrictSourceOwnershipRegistry.rollbackUninstalledOwner(owner)
            return failPendingPromotion(
                path,
                lease,
                activeSession,
                activeToken,
                resultFuture,
                IllegalStateException("Promotion token invalidated after exact claim")
            )
        }

        try {
            awaitActorFuture(checkNotNull(installFuture))
        } catch (failure: Throwable) {
            return failPendingPromotion(
                path, lease, activeSession, activeToken, resultFuture, failure
            )
        }

        var activationFuture: CompletableFuture<Unit>? = null
        val activationQueued = synchronized(mutationLock(path)) {
            val entry = entries[path]
            val pending = entry?.pendingPromotion
            if (entry?.lease != lease || pending?.token != activeToken) {
                false
            } else {
                pending.installSucceeded = true
                pending.stage = PromotionStage.SESSION_INSTALLED
                val transport = NtkCacheSourceTransport(activeSession)
                entry.authoritative = incoming
                entry.transport = transport
                entry.quarantineState = NtkQuarantineState.EXACT_ADOPTING
                pending.stage = PromotionStage.COMMIT_QUEUED
                activationFuture = activeSession.enqueueActivateExactPublication(
                    activeToken,
                    pending.validity
                )
                check(activationFuture?.isCompletedExceptionally == false) {
                    "Exact publication activation was rejected"
                }
                true
            }
        }
        if (!activationQueued) {
            val failure = IllegalStateException(
                "Promotion token invalidated after actor exact install"
            )
            val commitCloseAction = closePromotionWithoutEntry(
                path = path,
                session = activeSession,
                token = activeToken,
                reservation = reservation,
                owner = owner,
                installSucceeded = true,
                resultFuture = resultFuture,
                failure = failure
            )
            performCloseAction(commitCloseAction)
            return failedExactResult()
        }

        // The UI may synchronously claim the transport from either the publication callback or
        // its close-the-subscription-race current-authority read. Do not expose OWNED_PRECLAIM
        // until the source actor has opened the exact session and installed the resident-body
        // manifest. Previously the actor activation was merely queued here; on a fast document
        // response the UI won that race and bindResidentBodies failed closed.
        try {
            awaitActorFuture(checkNotNull(activationFuture))
        } catch (failure: Throwable) {
            return failPendingPromotion(
                path, lease, activeSession, activeToken, resultFuture, failure
            )
        }

        val committed = synchronized(mutationLock(path)) {
            val entry = entries[path]
            val pending = entry?.pendingPromotion
            if (entry?.lease != lease || pending?.token != activeToken ||
                !pending.installSucceeded || entry.authoritative !== incoming ||
                entry.transport?.strictSessionId != activeToken.sessionId
            ) {
                false
            } else {
                val previous = entry.state
                entry.state = NtkSourceState.OWNED_PRECLAIM
                entry.pendingPromotion = null
                logState(entry, previous, entry.state, "exact_owner_activated")
                Log.d(
                    "ViewerPerf",
                    "reader_source_exact_installed path=$path," +
                        "generation=${lease.generation.value},proofKind=${incoming.proof.kind}," +
                        "proofDigest=${incoming.proof.proofDigestSha256}," +
                        "manifestDigest=${incoming.seal.digestSha256}," +
                        "sources=${incoming.seal.pageCount}," +
                        "sessionId=${entry.transport?.strictSessionId}"
                )
                true
            }
        }
        if (!committed) {
            return failPendingPromotion(
                path,
                lease,
                activeSession,
                activeToken,
                resultFuture,
                IllegalStateException("Promotion token invalidated after exact activation")
            )
        }

        val result = NtkManifestInstallResult(
            NtkManifestInstallStatus.INSTALLED_EXACT,
            incoming,
            NtkSourceState.OWNED_PRECLAIM
        )
        if (!isCurrentAuthorityPublication(lease, incoming)) {
            val failed = failedExactResult()
            resultFuture.complete(failed)
            return failed
        }
        // Publication transfers the exact transport to the UI. A listener is therefore allowed
        // to claim it and retire this registry entry synchronously. Settle the install before the
        // callback: re-checking registry liveness after a successful ownership transfer used to
        // misclassify that expected retirement as FAILED and cancel every still-streaming body.
        resultFuture.complete(result)
        authoritativeManifestChannel.publish(path, incoming) {
            isCurrentAuthorityPublication(lease, incoming)
        }
        return result
    }

    @JvmStatic
    fun failDiscovery(lease: NtkDiscoveryLease?, cause: String?): Boolean {
        if (lease == null) return false
        var action: CloseAction? = null
        val failed = synchronized(mutationLock(lease.episodePath)) {
            val entry = entries[lease.episodePath] ?: return@synchronized false
            if (entry.lease != lease) return@synchronized false
            action = failClosedLocked(
                entry,
                cause?.takeIf(String::isNotBlank) ?: "discovery_failed"
            )
            true
        }
        performCloseAction(action)
        return failed
    }

    /**
     * Retires an exact discovery generation without keeping its path slot occupied.
     *
     * Only the short state transition runs on the caller (including Activity.onDestroy). Actor
     * shutdown remains asynchronous. A session-backed entry is moved to [retiredEntries] before
     * its close request is dispatched, so its generation-qualified close barrier cannot touch a
     * newer same-path entry and a new discovery never receives the terminal old lease.
     */
    @JvmStatic
    fun retireDiscoveryForReplacement(
        lease: NtkDiscoveryLease?,
        cause: String?,
    ): Boolean {
        if (lease == null) return false
        var action: CloseAction? = null
        val retired = synchronized(mutationLock(lease.episodePath)) {
            val entry = entries[lease.episodePath] ?: return@synchronized false
            if (entry.lease != lease) return@synchronized false
            action = retireEntryForReplacementLocked(entry, cause)
            true
        }
        performCloseAction(action)
        return retired
    }

    /**
     * Lifecycle fallback for a completed coordinator flight. Only the exact generation carried by
     * the immutable launch seal may be detached; a newer same-path generation is never selected.
     */
    @JvmStatic
    fun retireDiscoveryGenerationForReplacement(
        path: String?,
        discoveryGeneration: Long,
        cause: String?,
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (discoveryGeneration <= 0L) return false
        var action: CloseAction? = null
        val retired = synchronized(mutationLock(key)) {
            val entry = entries[key] ?: return@synchronized false
            if (entry.lease.generation.value != discoveryGeneration) return@synchronized false
            action = retireEntryForReplacementLocked(entry, cause)
            true
        }
        performCloseAction(action)
        return retired
    }

    private fun retireEntryForReplacementLocked(entry: Entry, cause: String?): CloseAction? {
        val lease = entry.lease
        val action = failClosedLocked(
            entry,
            cause?.takeIf(String::isNotBlank) ?: "discovery_owner_retired"
        )
        if (entry.sourceSession != null) {
            // Publish the tombstone before releasing the active slot. The close callback uses the
            // same path lock, so it can observe either the active identity or this one.
            retiredEntries[lease] = entry
            entries.remove(lease.episodePath, entry)
        }
        return action
    }

    @JvmStatic
    fun isDiscoveryActive(path: String?): Boolean {
        val key = normalizedPath(path) ?: return false
        return synchronized(mutationLock(key)) {
            val entry = entries[key] ?: return@synchronized false
            entry.state == NtkSourceState.DISCOVERING &&
                entry.planState != NtkPlanState.TERMINAL
        }
    }

    @JvmStatic
    fun currentAuthoritativeManifest(path: String?): NtkAuthoritativeManifest? =
        normalizedPath(path)?.let { key ->
            synchronized(mutationLock(key)) { entries[key]?.authoritative }
        }

    /** One-shot effective floor for the exact foreground viewer generation that requested it. */
    @JvmStatic
    fun currentInitialPageIndex(path: String?, viewerGeneration: Long): Int =
        normalizedPath(path)?.let { key ->
            synchronized(mutationLock(key)) {
                entries[key]?.takeIf { entry ->
                    entry.forwardResumeFinalized &&
                        (!entry.rollingAdmission ||
                            entry.forwardResumeViewerGeneration == viewerGeneration)
                }?.effectiveInitialPageIndex ?: 0
            }
        } ?: 0

    private fun isCurrentPlanPublication(
        lease: NtkDiscoveryLease,
        plan: NtkProvisionalEpisodePlan,
    ): Boolean = synchronized(mutationLock(lease.episodePath)) {
        val entry = entries[lease.episodePath]
        entry?.lease == lease &&
            entry.state.ordinal < NtkSourceState.TERMINAL_CLOSING.ordinal &&
            entry.provisionalPlan?.proof?.proofDigestSha256 == plan.proof.proofDigestSha256 &&
            plan.proof.discoveryGeneration == lease.generation.value
    }

    private fun isCurrentAuthorityPublication(
        lease: NtkDiscoveryLease,
        manifest: NtkAuthoritativeManifest,
    ): Boolean = synchronized(mutationLock(lease.episodePath)) {
        val entry = entries[lease.episodePath]
        entry?.lease == lease &&
            entry.state.ordinal >= NtkSourceState.OWNED_PRECLAIM.ordinal &&
            entry.state.ordinal < NtkSourceState.TERMINAL_CLOSING.ordinal &&
            entry.authoritative?.proof?.proofDigestSha256 == manifest.proof.proofDigestSha256 &&
            manifest.proof.discoveryGeneration == lease.generation.value
    }

    @JvmStatic
    fun currentProvisionalEpisodePlan(path: String?): NtkProvisionalEpisodePlan? =
        normalizedPath(path)?.let { key ->
            synchronized(mutationLock(key)) { entries[key]?.provisionalPlan }
        }

    @JvmStatic
    fun currentDiscoveryLease(path: String?): NtkDiscoveryLease? =
        normalizedPath(path)?.let { key ->
            synchronized(mutationLock(key)) { entries[key]?.lease }
        }

    @JvmStatic
    fun currentManifestSeal(path: String?): NtkEpisodeManifestSeal? =
        currentAuthoritativeManifest(path)?.seal

    @JvmStatic
    fun currentSnapshot(path: String?): NtkSourceRegistrySnapshot? {
        val key = normalizedPath(path) ?: return null
        return synchronized(mutationLock(key)) {
            val entry = entries[key] ?: return@synchronized null
            val quarantine = entry.sourceSession?.quarantineDebugSnapshot()
            NtkSourceRegistrySnapshot(
                episodePath = key,
                generation = entry.lease.generation.value,
                state = entry.state,
                planState = entry.planState,
                planProofDigest = entry.provisionalPlan?.proof?.proofDigestSha256.orEmpty(),
                plannedPageCount = entry.provisionalPlan?.pageCount ?: 0,
                requestIdentityDigest = entry.provisionalPlan?.proof?.requestIdentity
                    ?.identityDigestSha256.orEmpty(),
                manifestDigest = entry.authoritative?.seal?.digestSha256.orEmpty(),
                proofDigest = entry.authoritative?.proof?.proofDigestSha256.orEmpty(),
                sessionId = entry.sourceSession?.sessionId ?: 0L,
                closeBarrierSerial = entry.closeBarrier?.barrierSerial ?: 0L,
                quarantineState = quarantine?.quarantineState ?: entry.quarantineState,
                quarantinePhysicalCallsStarted =
                    quarantine?.quarantinePhysicalCallsStarted ?: 0,
                quarantineActiveCalls = quarantine?.quarantineActiveCalls ?: 0,
                quarantineBodiesSealed = quarantine?.quarantineBodiesSealed ?: 0,
                quarantineTempFiles = quarantine?.quarantineTempFiles ?: 0,
                exactAdoptedBodies = quarantine?.exactAdoptedBodies ?: 0,
                duplicatePhysicalCalls = quarantine?.duplicatePhysicalCalls ?: 0,
                planReservationBoundary = entry.planReservationBoundary
            )
        }
    }

    @JvmStatic
    internal fun claim(
        path: String?,
        manifestDigest: String?
    ): NtkEpisodeStripPipeline.SourceTransport? {
        val key = normalizedPath(path) ?: return null
        val digest = manifestDigest?.trim()?.lowercase().orEmpty()
        return synchronized(mutationLock(key)) {
            val entry = entries[key] ?: return@synchronized null
            val authority = entry.authoritative ?: return@synchronized null
            val transport = entry.transport ?: return@synchronized null
            if (entry.state != NtkSourceState.OWNED_PRECLAIM || entry.claimed ||
                authority.seal.digestSha256 != digest
            ) return@synchronized null
            entry.claimed = true
            ClaimedPort(key, entry.lease, authority.seal, transport)
        }
    }

    @JvmStatic
    fun markClaimPhase(
        path: String?,
        manifestDigest: String?,
        phase: NtkManifestClaimPhase
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        val digest = manifestDigest?.trim()?.lowercase().orEmpty()
        return synchronized(mutationLock(key)) {
            val entry = entries[key] ?: return@synchronized false
            if (!entry.claimed || entry.authoritative?.seal?.digestSha256 != digest) {
                return@synchronized false
            }
            val next = when (phase) {
                NtkManifestClaimPhase.BEFORE_CLAIM -> NtkSourceState.OWNED_PRECLAIM
                NtkManifestClaimPhase.BINDING -> NtkSourceState.OWNED_BINDING
                NtkManifestClaimPhase.STAGED -> NtkSourceState.OWNED_STAGED
                NtkManifestClaimPhase.ACTIVE -> NtkSourceState.OWNED_ACTIVE
            }
            if (entry.state.ordinal > next.ordinal ||
                entry.state.ordinal < NtkSourceState.OWNED_PRECLAIM.ordinal ||
                entry.state.ordinal >= NtkSourceState.TERMINAL_CLOSING.ordinal
            ) return@synchronized false
            val previous = entry.state
            entry.state = next
            if (previous != next) {
                logState(entry, previous, next, "ui_phase_${phase.name.lowercase()}")
            }
            true
        }
    }

    @JvmStatic
    fun retire(path: String?) {
        val key = normalizedPath(path) ?: return
        val action = synchronized(mutationLock(key)) {
            entries[key]?.let { closeEntryLocked(it, "retire") }
        }
        performCloseAction(action)
    }

    private fun invalidPlanResult() = NtkPlanInstallResult(
        NtkPlanInstallStatus.INVALID_PLAN_PROOF,
        null,
        NtkSourceState.ABSENT,
        NtkPlanState.NONE
    )

    private fun invalidExactResult() = NtkManifestInstallResult(
        NtkManifestInstallStatus.INVALID_EXACT_PROOF,
        null,
        NtkSourceState.ABSENT
    )

    private fun failedExactResult() = NtkManifestInstallResult(
        NtkManifestInstallStatus.FAILED,
        null,
        NtkSourceState.TERMINAL_CLOSING
    )

    private fun failClosedLocked(entry: Entry, cause: String): CloseAction? {
        entry.terminalCause = cause
        return closeEntryLocked(entry, cause)
    }

    private fun closeEntryLocked(entry: Entry, cause: String): CloseAction? {
        if (entry.state == NtkSourceState.TERMINAL_CLOSED ||
            entry.state == NtkSourceState.TERMINAL_CLOSING
        ) return null
        val previous = entry.state
        val previousPlan = entry.planState
        val pending = entry.pendingPromotion
        val closingIdentity = entry.exactClosingIdentity
        entry.state = NtkSourceState.TERMINAL_CLOSING
        entry.planState = NtkPlanState.TERMINAL
        entry.terminalCause = cause
        pending?.validity?.set(false)
        entry.pendingPromotion = null
        logState(entry, previous, entry.state, cause)
        if (previousPlan != entry.planState) {
            logPlan(entry, previousPlan, entry.planState, cause)
        }
        entry.quarantineState = NtkQuarantineState.ABORTING
        val session = entry.sourceSession
        val executionBootstrapFuture = if (session == null) {
            entry.executionBootstrapFuture.also { entry.executionBootstrapFuture = null }
        } else {
            null
        }
        if (session == null) {
            entry.state = NtkSourceState.TERMINAL_CLOSED
            entry.quarantineState = NtkQuarantineState.CLOSED
            entries.remove(entry.lease.episodePath, entry)
            logState(entry, NtkSourceState.TERMINAL_CLOSING, entry.state, "no_owned_resources")
        }
        return CloseAction(
            path = entry.lease.episodePath,
            discoveryGeneration = entry.lease.generation.value,
            session = session,
            token = pending?.token ?: closingIdentity?.token,
            reservation = pending?.reservation,
            owner = pending?.owner ?: closingIdentity?.owner,
            installSucceeded = pending?.installSucceeded == true ||
                entry.authoritative != null,
            installFuture = pending?.installFuture,
            promotionResult = pending?.result,
            cause = IllegalStateException(cause),
            endDiscoveryFence = true,
            executionBootstrapFuture = executionBootstrapFuture,
        )
    }

    private fun failQuarantineSession(
        path: String,
        lease: NtkDiscoveryLease,
        failure: Throwable
    ) {
        Log.e(
            "ViewerPerf",
            "reader_quarantine_source_failed path=$path," +
                "generation=${lease.generation.value}," +
                "error=${failure.javaClass.simpleName}",
            failure,
        )
        val action: CloseAction? = synchronized(mutationLock(path)) {
            val entry = entries[path] ?: return@synchronized null
            if (entry.lease != lease ||
                entry.state.ordinal >= NtkSourceState.TERMINAL_CLOSING.ordinal
            ) return@synchronized null
            failClosedLocked(
                entry,
                "quarantine_source_${failure.javaClass.simpleName}"
            )
        }
        performCloseAction(action)
    }

    private fun failPendingPromotion(
        path: String,
        lease: NtkDiscoveryLease,
        session: NtkStrictSourceSession,
        token: NtkPromotionToken,
        resultFuture: CompletableFuture<NtkManifestInstallResult>,
        failure: Throwable
    ): NtkManifestInstallResult {
        val action = synchronized(mutationLock(path)) {
            val entry = entries[path]
            if (entry?.lease == lease && entry.pendingPromotion?.token == token) {
                failClosedLocked(
                    entry,
                    "exact_promotion_${failure.javaClass.simpleName}"
                )
            } else null
        } ?: closePromotionWithoutEntry(
            path = path,
            session = session,
            token = token,
            reservation = null,
            owner = null,
            installSucceeded = false,
            resultFuture = resultFuture,
            failure = failure
        )
        Log.e("ViewerPerf", "reader_source_exact_install_failed path=$path", failure)
        performCloseAction(action)
        return failedExactResult()
    }

    private fun closePromotionWithoutEntry(
        path: String,
        session: NtkStrictSourceSession,
        token: NtkPromotionToken,
        reservation: NtkStrictSourceOwnershipRegistry.ExactReservation?,
        owner: NtkStrictSourceOwnershipRegistry.Owner?,
        installSucceeded: Boolean,
        resultFuture: CompletableFuture<NtkManifestInstallResult>,
        failure: Throwable
    ) = CloseAction(
        path,
        token.discoveryGeneration,
        session,
        token,
        reservation,
        owner,
        installSucceeded,
        null,
        resultFuture,
        failure,
        endDiscoveryFence = true
    )

    private fun performCloseAction(action: CloseAction?) {
        if (action == null) return
        if (action.endDiscoveryFence) {
            NtkStrictSourceOwnershipRegistry.endDiscoveryFence(
                action.path,
                action.discoveryGeneration
            )
        }
        action.executionBootstrapFuture?.whenComplete { bootstrap, _ ->
            bootstrap?.close()
        }
        action.session?.requestClose(action.cause)
        if (!action.installSucceeded) {
            val owner = action.owner
            if (owner != null) {
                val released =
                    NtkStrictSourceOwnershipRegistry.rollbackUninstalledOwner(owner)
                if (!released) {
                    val installFuture = action.installFuture
                    check(installFuture != null) {
                        "Uninstalled exact owner could not be rolled back"
                    }
                    installFuture.whenComplete { _, _ ->
                        check(
                            NtkStrictSourceOwnershipRegistry.rollbackUninstalledOwner(owner)
                        ) { "Settled uninstalled exact owner could not be rolled back" }
                    }
                }
            } else {
                action.reservation?.let {
                    NtkStrictSourceOwnershipRegistry.rollbackReservation(it)
                }
            }
        }
        action.promotionResult?.complete(failedExactResult())
    }

    private fun <T> awaitActorFuture(future: CompletableFuture<T>): T {
        return try {
            future.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while awaiting strict source actor", failure)
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    private fun completeQuarantineCloseBarrier(
        path: String,
        lease: NtkDiscoveryLease,
        barrier: NtkQuarantineCloseBarrierProof
    ) {
        synchronized(mutationLock(path)) {
            val active = entries[path]
            val entry = active?.takeIf { it.lease == lease } ?: retiredEntries[lease]
                ?: return@synchronized
            check(
                barrier.isComplete &&
                    barrier.episodePath == path &&
                    barrier.discoveryGeneration == lease.generation.value &&
                    barrier.planBindingDigest == entry.planBinding?.bindingDigest &&
                    barrier.sessionId == entry.sourceSession?.sessionId
            ) { "Quarantine source close barrier identity mismatch" }
            entry.quarantineCloseBarrier = barrier
            val previous = entry.state
            entry.state = NtkSourceState.TERMINAL_CLOSED
            entry.planState = NtkPlanState.TERMINAL
            entry.quarantineState = NtkQuarantineState.CLOSED
            if (active === entry) entries.remove(path, entry) else retiredEntries.remove(lease, entry)
            logState(entry, previous, entry.state, "quarantine_close_barrier")
        }
    }

    private fun completeCloseBarrier(
        path: String,
        lease: NtkDiscoveryLease,
        barrier: NtkSourceCloseBarrierProof
    ) {
        synchronized(mutationLock(path)) {
            val active = entries[path]
            val entry = active?.takeIf { it.lease == lease } ?: retiredEntries[lease]
                ?: return@synchronized
            val manifestDigest = entry.authoritative?.seal?.digestSha256
                ?: entry.exactClosingIdentity?.manifestDigest
                ?: error("Exact close barrier omitted its closing identity")
            check(
                barrier.isComplete && barrier.episodePath == path &&
                    barrier.discoveryGeneration == lease.generation.value &&
                    barrier.manifestDigest == manifestDigest &&
                    barrier.sessionId == entry.sourceSession?.sessionId
            ) { "Strict source close barrier identity mismatch" }
            entry.closeBarrier = barrier
            val previous = entry.state
            entry.state = NtkSourceState.TERMINAL_CLOSED
            entry.planState = NtkPlanState.TERMINAL
            entry.quarantineState = NtkQuarantineState.CLOSED
            logState(entry, previous, entry.state, "close_barrier_${barrier.barrierSerial}")
            check(
                NtkStrictSourceOwnershipRegistry.release(
                    path,
                    manifestDigest,
                    barrier.sessionId,
                    lease.generation.value,
                )
            ) { "Strict source ownership release preceded common close barrier" }
            if (active === entry) entries.remove(path, entry) else retiredEntries.remove(lease, entry)
        }
    }

    private class ClaimedPort(
        private val path: String,
        private val lease: NtkDiscoveryLease,
        private val seal: NtkEpisodeManifestSeal,
        private val transport: NtkCacheSourceTransport
    ) : NtkEpisodeStripPipeline.SourceTransport, NtkStrictSourceTransport, Closeable {
        private val retired = AtomicBoolean(false)

        override val exactSealAtMs: Long
            get() = transport.exactSealAtMs

        override fun register(
            request: NtkEpisodeStripPipeline.SourceRequest,
            completion: (Result<NtkEpisodeStripPipeline.SourceHandle>) -> Unit
        ) = transport.register(request, completion)

        override fun registerWithEvents(
            request: NtkEpisodeStripPipeline.SourceRequest,
            event: (SourceEvent) -> Unit,
            completion: (Result<NtkEpisodeStripPipeline.SourceHandle>) -> Unit
        ) = transport.registerWithEvents(request, event, completion)

        override fun addSourceEventListener(listener: NtkSourceEventListener): Closeable =
            transport.addSourceEventListener(listener)

        override fun bindEpisode(
            episode: NtkEpisodeToken,
            manifestSeal: NtkEpisodeManifestSeal,
            initialPageIndex: Int,
            listener: NtkSourceEventListener
        ): Closeable {
            check(!retired.get() && seal.hasSameAuthority(manifestSeal))
            return transport.bindEpisode(episode, manifestSeal, initialPageIndex, listener)
        }

        override fun bindResidentBodies(
            episode: NtkEpisodeToken,
            manifestSeal: NtkEpisodeManifestSeal,
            listener: NtkStrictResidentBodyListener
        ): Closeable {
            check(!retired.get() && seal.hasSameAuthority(manifestSeal))
            return transport.bindResidentBodies(episode, manifestSeal, listener)
        }

        override fun onGeometrySealed(
            episode: NtkEpisodeToken,
            geometryDigest: String,
            exactStagePageIndexes: Set<Int>
        ) = transport.onGeometrySealed(episode, geometryDigest, exactStagePageIndexes)

        override fun onFirstActualFramePresented(episode: NtkEpisodeToken) =
            transport.onFirstActualFramePresented(episode)

        override fun onInitialDrawableCommitted(episode: NtkEpisodeToken) =
            transport.onInitialDrawableCommitted(episode)

        override fun onAdjacentPredecessorComplete(episode: NtkEpisodeToken) =
            transport.onAdjacentPredecessorComplete(episode)

        override fun onAdjacentViewportActivated(episode: NtkEpisodeToken) =
            transport.onAdjacentViewportActivated(episode)

        override fun requestPreparationDrain(
            episode: NtkEpisodeToken,
            completion: (NtkSourceDrainProof) -> Unit
        ) = transport.requestPreparationDrain(episode, completion)

        override fun applyPreGeometryPlan(
            episode: NtkEpisodeToken,
            plan: NtkPreGeometrySourcePlan
        ) = transport.applyPreGeometryPlan(episode, plan)

        override fun applySourceDemand(
            episode: NtkEpisodeToken,
            demand: NtkSourceDemandSnapshot
        ) = transport.applySourceDemand(episode, demand)

        override fun retire(episode: NtkEpisodeToken) = close()

        override fun close() {
            if (!retired.compareAndSet(false, true)) return
            val action = synchronized(mutationLock(path)) {
                val entry = entries[path]
                if (entry?.lease == lease) {
                    closeEntryLocked(entry, "claimed_port_retire")
                } else null
            }
            performCloseAction(action)
        }
    }

    private fun deriveInitialPageIndex(context: Context, manga: Manga): Int {
        if (!manga.useBookmark()) return 0
        return runCatching { Preference(context).getViewerBookmark(manga) }
            .getOrDefault(0)
            .coerceAtLeast(0)
    }

    private fun mutationLock(path: String): Any = mutationLocks.computeIfAbsent(path) { Any() }

    internal fun normalizedPath(path: String?): String? {
        // Work and episode slugs are server-owned URL path segments and therefore
        // case-sensitive. Lowercasing this key split the coordinator's exact path from its
        // discovery lease for real slugs such as u-bt-I_killed-*, making the complete document
        // fail ownership validation after it had already downloaded successfully.
        val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty())
        return normalized.takeIf {
            it.startsWith("/manhwa/", ignoreCase = true) ||
                it.startsWith("/webtoon/", ignoreCase = true)
        }
    }

    private fun logPlan(
        entry: Entry,
        previous: NtkPlanState,
        next: NtkPlanState,
        reason: String
    ) {
        Log.d(
            "ViewerPerf",
            "reader_source_plan_state path=${entry.lease.episodePath}," +
                "generation=${entry.lease.generation.value},from=$previous,to=$next," +
                "reason=$reason,planProof=${entry.provisionalPlan?.proof?.proofDigestSha256.orEmpty()}," +
                "requestIdentity=${entry.provisionalPlan?.proof?.requestIdentity
                    ?.identityDigestSha256.orEmpty()}," +
                "pages=${entry.provisionalPlan?.pageCount ?: 0}"
        )
    }

    private fun logState(
        entry: Entry,
        previous: NtkSourceState,
        next: NtkSourceState,
        reason: String
    ) {
        Log.d(
            "ViewerPerf",
            "reader_source_state path=${entry.lease.episodePath}," +
                "generation=${entry.lease.generation.value},from=$previous,to=$next," +
                "reason=$reason,planState=${entry.planState}," +
                "planProof=${entry.provisionalPlan?.proof?.proofDigestSha256.orEmpty()}," +
                "manifestDigest=${entry.authoritative?.seal?.digestSha256.orEmpty()}," +
                "sessionId=${entry.transport?.strictSessionId ?: 0L}"
        )
    }
}
