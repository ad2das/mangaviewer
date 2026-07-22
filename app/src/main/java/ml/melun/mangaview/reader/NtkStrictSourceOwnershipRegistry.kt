package ml.melun.mangaview.reader

import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object NtkGlobalSourceAdmissionPolicy {
    fun canAdmit(
        activeSessionKeys: Set<Pair<Long, String>>,
        requestedSessionKey: Pair<Long, String>,
        activeTotal: Int
    ): Boolean {
        require(activeTotal >= 0)
        return activeTotal < NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS &&
            activeSessionKeys.all { it == requestedSessionKey }
    }
}

/**
 * Detailed per-page source records are diagnostic samples, not part of source authority.
 *
 * A long strip can contain well over one hundred images. Building and sending two multi-kilobyte
 * log records for every successful body competes with the cold JPEG decode wave on emulator and
 * real devices alike. The ownership ledger and aggregate counters remain exact; page zero gives
 * us a representative successful start/end record, while every non-success and partial response
 * is still recorded in full.
 */
internal object NtkStrictSourceOperationTelemetryPolicy {
    fun shouldLogStart(pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        return pageIndex == 0
    }

    fun shouldLogEnd(
        pageIndex: Int,
        succeeded: Boolean,
        partialBodyOperation: Boolean,
    ): Boolean {
        require(pageIndex >= 0)
        return pageIndex == 0 || !succeeded || partialBodyOperation
    }

    fun shouldLogSuccessfulAdoption(pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        return pageIndex == 0
    }
}

/**
 * Process-wide ownership and physical-call ledger for strict NTK source work.
 *
 * A discovered path is first [RESERVED], then a production seal atomically upgrades it to
 * [OWNED]. Every physical image
 * call for an owned path must then carry a live [NtkStrictSourceCallTag] admitted here.  Executor
 * counts are deliberately not used as the contract: this registry accounts the actual operation
 * which is allowed to create the OkHttp Call.
 */
object NtkStrictSourceOwnershipRegistry {
    private val operationTelemetry = NtkAsyncTelemetry(capacity = 256)

    enum class State { RESERVED, OWNED }

    data class Owner(
        val path: String,
        val state: State,
        val discoveryGeneration: Long,
        val manifestDigest: String,
        val exactProofDigest: String,
        val planBindingDigest: String,
        val promotionNonce: Long,
        val sessionId: Long,
        val geometrySealed: Boolean,
        val reservedAtMs: Long,
        val claimedAtMs: Long,
        val preGeometryStageRunwayClosed: Boolean,
        val preGeometryStageRunwayClosedAtMs: Long,
        val primaryAdmissionsSealed: Boolean,
        val primaryAdmissionsSealedAtMs: Long
    )

    internal data class ExactReservation(
        val token: NtkPromotionToken,
        val reservedAtMs: Long
    )

    data class Snapshot(
        val owner: Owner,
        val activeMetadata: Int,
        val activeStageBody: Int,
        val activeUrgentBody: Int,
        val activeBackgroundBody: Int,
        val activeLegacy: Int,
        val activeTotal: Int,
        val unleasedCallCount: Long,
        val peakActiveTotal: Int,
        val producerMax: Int,
        val newConnectionCount: Long = 0L,
        val reusedConnectionCount: Long = 0L,
        val distinctRouteCount: Int = 0,
        val globalActiveTotal: Int = 0,
        val globalPeakActiveTotal: Int = 0,
        val distinctConnectionCount: Int = 0,
        val cancelledLoserCount: Long = 0L,
        val metadataNewConnectionCount: Long = 0L,
        val metadataConnectionReuseCount: Long = 0L,
        val metadataDistinctConnectionCount: Int = 0,
        val distinctRouteKeyCount: Int = 0,
        val partialBodyOperationCount: Long = 0L,
        val preGeometryPeakActiveMetadata: Int = 0,
        val reclaimedMetadataOperationCount: Long = 0L,
        val activePrimaryFullBody: Int = 0,
        val primaryStartedCount: Int = 0,
        val primaryAdmissionsSealedAtMs: Long = 0L
    )

    private data class ActiveOperation(
        val tag: NtkStrictSourceCallTag,
        val startedAtMs: Long,
        val routeKeyHash: String,
        val callFactoryId: String,
        val method: String,
        val attempt: Int,
        val rangeStart: Long,
        val rangeEnd: Long,
        val manifestRevision: Long,
        val demandEpoch: Long,
        val launchedPreGeometry: Boolean,
        val metadataQueueDepth: Int,
        val bodyQueueDepth: Int,
        val preGeometryPhase: NtkPreGeometryPhase,
        val reclaimedMetadataLane: Boolean,
        val attemptOrdinal: Int,
        val workId: Long,
        val episodeAuthority: Long,
        val preclaim: Boolean,
        var callAdmitted: Boolean = false
    )

    private class RetiredSessionMetrics(
        val producerMax: Int,
        val unleasedCallCount: Long,
        var newConnectionCount: Long,
        var reusedConnectionCount: Long,
        var cancelledLoserCount: Long,
        routeKeyHashes: Set<String>,
        connectionIds: Set<String>,
        var metadataNewConnectionCount: Long,
        var metadataConnectionReuseCount: Long,
        metadataRouteKeyHashes: Set<String>,
        metadataConnectionIds: Set<String>,
        var partialBodyOperationCount: Long,
        val preGeometryStageRunwayClosed: Boolean,
        val preGeometryStageRunwayClosedAtMs: Long,
        val preGeometryPeakActiveMetadata: Int,
        val reclaimedMetadataOperationCount: Long
    ) {
        val routeKeyHashes = LinkedHashSet(routeKeyHashes)
        val connectionIds = LinkedHashSet(connectionIds)
        val metadataRouteKeyHashes = LinkedHashSet(metadataRouteKeyHashes)
        val metadataConnectionIds = LinkedHashSet(metadataConnectionIds)
    }

    private class Record(
        val path: String,
        val reservedAtMs: Long,
        val discoveryGeneration: Long,
        reservedManifestDigest: String,
        val exactProofDigest: String,
        val planBindingDigest: String,
        val promotionNonce: Long
    ) {
        var state = State.RESERVED
        var manifestDigest = reservedManifestDigest
        var sessionId = 0L
        var geometrySealed = false
        var claimedAtMs = 0L
        var preGeometryStageRunwayClosed = false
        var preGeometryStageRunwayClosedAtMs = 0L
        var preGeometryPeakActiveMetadata = 0
        var reclaimedMetadataOperationCount = 0L
        var primaryAdmissionsSealed = false
        var primaryAdmissionsSealedAtMs = 0L
        var peakActiveTotal = 0
        var producerMax = 0
        var unleasedCallCount = 0L
        var newConnectionCount = 0L
        var reusedConnectionCount = 0L
        var metadataNewConnectionCount = 0L
        var metadataConnectionReuseCount = 0L
        var partialBodyOperationCount = 0L
        var cancelledLoserCount = 0L
        val routeKeyHashes = LinkedHashSet<String>()
        val connectionIds = LinkedHashSet<String>()
        val metadataRouteKeyHashes = LinkedHashSet<String>()
        val metadataConnectionIds = LinkedHashSet<String>()
        val operations = LinkedHashMap<Long, ActiveOperation>()
        val primaryStartedPages = LinkedHashSet<Int>()
        // A page is admitted here only after its preceding owned Call has retired unsuccessfully.
        // This keeps retries bounded and failure-only while allowing them to outlive the ordinary
        // first-attempt admission seal established once every page has published metadata.
        val retryEligiblePages = LinkedHashSet<Int>()
        val successfulPrimaryPages = LinkedHashSet<Int>()
        val retiredSessionMetrics = LinkedHashMap<Pair<Long, String>, RetiredSessionMetrics>()

        fun owner(): Owner = Owner(
            path,
            state,
            discoveryGeneration,
            manifestDigest,
            exactProofDigest,
            planBindingDigest,
            promotionNonce,
            sessionId,
            geometrySealed,
            reservedAtMs,
            claimedAtMs,
            preGeometryStageRunwayClosed,
            preGeometryStageRunwayClosedAtMs,
            primaryAdmissionsSealed,
            primaryAdmissionsSealedAtMs
        )
    }

    private data class RecordKey(
        val path: String,
        val discoveryGeneration: Long,
    )

    class OperationLease internal constructor(
        private val path: String,
        val tag: NtkStrictSourceCallTag,
        private val closed: AtomicBoolean = AtomicBoolean(false)
    ) : Closeable {
        fun complete(
            httpCode: Int = 0,
            protocol: String = "",
            responseBytes: Long = 0L,
            metadataWitnessBytes: Long = 0L,
            metadataAcquisition: String = "",
            imageFormat: String = "",
            contentRangeTotal: Long = -1L,
            connectionId: String = "",
            connectionReused: Boolean = false,
            succeeded: Boolean = false,
            partialBodyOperation: Boolean = false,
            responseIdentityDigest: String = "",
            metadataBindingDigest: String = "",
            bodyDigest: String = ""
        ) {
            if (!closed.compareAndSet(false, true)) return
            finishOperation(
                path,
                tag,
                httpCode,
                protocol,
                responseBytes,
                metadataWitnessBytes,
                metadataAcquisition,
                imageFormat,
                contentRangeTotal,
                connectionId,
                connectionReused,
                succeeded,
                partialBodyOperation,
                responseIdentityDigest,
                metadataBindingDigest,
                bodyDigest
            )
        }

        override fun close() = complete()
    }

    /** Generation-keyed so a retired same-path session may drain beside its replacement. */
    private val records = LinkedHashMap<RecordKey, Record>()
    private val discoveryFences = LinkedHashMap<String, Long>()
    private val globalLock = java.lang.Object()
    private val operationSequence = AtomicLong(1L)
    private var globalPeakActiveTotal = 0

    @JvmStatic
    fun beginDiscoveryFence(path: String, discoveryGeneration: Long) {
        val normalized = normalize(path)
        require(normalized.isNotEmpty() && discoveryGeneration > 0L)
        synchronized(globalLock) {
            val current = discoveryFences[normalized]
            check(current == null || current == discoveryGeneration) {
                "Another manifest discovery generation already fences this path"
            }
            discoveryFences[normalized] = discoveryGeneration
        }
    }

    @JvmStatic
    fun endDiscoveryFence(path: String, discoveryGeneration: Long): Boolean {
        val normalized = normalize(path)
        return synchronized(globalLock) {
            if (discoveryFences[normalized] != discoveryGeneration) return@synchronized false
            discoveryFences.remove(normalized)
            globalLock.notifyAll()
            true
        }
    }

    @JvmStatic
    internal fun reserveExact(token: NtkPromotionToken): ExactReservation {
        val normalized = normalize(token.episodePath)
        require(normalized == token.episodePath)
        val now = monotonicMs()
        val reservation = synchronized(globalLock) {
            val key = RecordKey(normalized, token.discoveryGeneration)
            val record = records[key] ?: Record(
                    normalized,
                    now,
                    token.discoveryGeneration,
                    token.exactManifestDigest,
                    token.exactProofDigest,
                    token.planBindingDigest,
                    token.nonce
                ).also { records[key] = it }
            check(record.discoveryGeneration == token.discoveryGeneration &&
                record.manifestDigest == token.exactManifestDigest &&
                record.exactProofDigest == token.exactProofDigest &&
                record.planBindingDigest == token.planBindingDigest &&
                record.promotionNonce == token.nonce &&
                record.state == State.RESERVED
            ) { "Exact source reservation conflicts with an existing owner" }
            ExactReservation(token, record.reservedAtMs)
        }
        logDebug(
            "reader_strip_source_exact_reserved path=$normalized," +
                "discoveryGeneration=${token.discoveryGeneration}," +
                "manifestDigest=${token.exactManifestDigest}," +
                "promotionNonce=${token.nonce},reservedAt=${reservation.reservedAtMs}"
        )
        return reservation
    }

    internal fun claimExact(
        reservation: ExactReservation,
        sessionId: Long
    ): Owner {
        val token = reservation.token
        val normalized = normalize(token.episodePath)
        require(sessionId == token.sessionId)
        val now = monotonicMs()
        val owner = synchronized(globalLock) {
            val record = records[RecordKey(normalized, token.discoveryGeneration)]
                ?: error("Strict source claim preceded exact reservation")
            check(record.reservedAtMs == reservation.reservedAtMs &&
                record.discoveryGeneration == token.discoveryGeneration &&
                record.manifestDigest == token.exactManifestDigest &&
                record.exactProofDigest == token.exactProofDigest &&
                record.planBindingDigest == token.planBindingDigest &&
                record.promotionNonce == token.nonce
            ) { "Strict source claim changed exact discovery authority" }
            check(discoveryFences[normalized] == token.discoveryGeneration) {
                "Exact promotion lost its discovery fence"
            }
            if (record.state == State.OWNED) {
                check(record.manifestDigest == token.exactManifestDigest &&
                    record.sessionId == sessionId
                ) {
                    "Strict source path already belongs to another manifest/session"
                }
            } else {
                record.state = State.OWNED
                record.manifestDigest = token.exactManifestDigest
                record.sessionId = sessionId
                record.claimedAtMs = now
                record.preGeometryStageRunwayClosed = false
                record.preGeometryStageRunwayClosedAtMs = 0L
                record.preGeometryPeakActiveMetadata = 0
                record.reclaimedMetadataOperationCount = 0L
                record.primaryAdmissionsSealed = false
                record.primaryAdmissionsSealedAtMs = 0L
                record.primaryStartedPages.clear()
                record.retryEligiblePages.clear()
                record.successfulPrimaryPages.clear()
                record.peakActiveTotal = 0
                record.producerMax = 0
                record.unleasedCallCount = 0L
                record.newConnectionCount = 0L
                record.reusedConnectionCount = 0L
                record.metadataNewConnectionCount = 0L
                record.metadataConnectionReuseCount = 0L
                record.partialBodyOperationCount = 0L
                record.cancelledLoserCount = 0L
                record.routeKeyHashes.clear()
                record.connectionIds.clear()
                record.metadataRouteKeyHashes.clear()
                record.metadataConnectionIds.clear()
                discoveryFences.remove(normalized)
            }
            record.owner().also { globalLock.notifyAll() }
        }
        logDebug(
            "reader_strip_source_session_claimed path=$normalized,sessionId=$sessionId," +
                "discoveryGeneration=${token.discoveryGeneration}," +
                "manifestDigest=${token.exactManifestDigest}," +
                "promotionNonce=${token.nonce}," +
                "claimAt=${owner.claimedAtMs}"
        )
        return owner
    }

    @JvmStatic
    fun setGeometrySealed(path: String, manifestDigest: String, sessionId: Long): Boolean {
        return synchronized(globalLock) {
            val record = ownedRecordLocked(path, manifestDigest, sessionId)
                ?: return@synchronized false
            if (record.state != State.OWNED || record.manifestDigest != manifestDigest ||
                record.sessionId != sessionId
            ) {
                false
            } else {
                if (record.primaryStartedPages.isNotEmpty() && !record.primaryAdmissionsSealed) {
                    return@synchronized false
                }
                record.geometrySealed = true
                true
            }
        }
    }

    /** Monotonic boundary: every page is body-ready or owns its already-started primary flight. */
    @JvmStatic
    fun sealPrimaryAdmissions(path: String, manifestDigest: String, sessionId: Long): Boolean {
        val normalized = normalize(path)
        return synchronized(globalLock) {
            val record = ownedRecordLocked(normalized, manifestDigest, sessionId)
                ?: return@synchronized false
            if (record.state != State.OWNED || record.manifestDigest != manifestDigest ||
                record.sessionId != sessionId || record.geometrySealed
            ) return@synchronized false
            if (!record.primaryAdmissionsSealed) {
                record.primaryAdmissionsSealed = true
                record.primaryAdmissionsSealedAtMs = monotonicMs()
            }
            true
        }
    }

    @JvmStatic
    fun activeOperationCount(path: String, manifestDigest: String, sessionId: Long): Int =
        synchronized(globalLock) {
            ownedRecordLocked(path, manifestDigest, sessionId)?.operations?.size ?: 0
        }

    /**
     * Monotonically retires the two pre-geometry stage lanes for the exact current owner.
     * Repeating the transition for that owner is idempotent and never changes its first-close
     * timestamp. Geometry sealing and active STAGE_BODY work both fail closed.
     */
    @JvmStatic
    fun closePreGeometryStageRunway(
        path: String,
        manifestDigest: String,
        sessionId: Long
    ): Boolean {
        val normalized = normalize(path)
        return synchronized(globalLock) {
            val record = ownedRecordLocked(normalized, manifestDigest, sessionId)
                ?: return@synchronized false
            if (record.state != State.OWNED || record.manifestDigest != manifestDigest ||
                record.sessionId != sessionId
            ) {
                return@synchronized false
            }
            val counts = counts(record.operations.values.map(ActiveOperation::tag))
            if (!NtkPreGeometryAdmissionPolicy.canCloseStageRunway(
                    record.geometrySealed,
                    stageBodyQueueDepth = 0,
                    activeStageBodies = counts.stageBody,
                    unsettledStagePages = 0
                )
            ) {
                return@synchronized false
            }
            if (!record.preGeometryStageRunwayClosed) {
                record.preGeometryStageRunwayClosed = true
                record.preGeometryStageRunwayClosedAtMs = monotonicMs()
            }
            true
        }
    }

    @JvmStatic
    fun release(path: String, manifestDigest: String, sessionId: Long): Boolean {
        return release(path, manifestDigest, sessionId, 0L)
    }

    @JvmStatic
    fun release(
        path: String,
        manifestDigest: String,
        sessionId: Long,
        discoveryGeneration: Long,
    ): Boolean {
        val normalized = normalize(path)
        return synchronized(globalLock) {
            val record = ownedRecordLocked(
                normalized,
                manifestDigest,
                sessionId,
                discoveryGeneration.takeIf { it > 0L },
            ) ?: return@synchronized false
            if (record.state != State.OWNED || record.manifestDigest != manifestDigest ||
                record.sessionId != sessionId || record.operations.isNotEmpty()
            ) return@synchronized false
            records.remove(RecordKey(normalized, record.discoveryGeneration), record)
                .also { globalLock.notifyAll() }
        }
    }

    internal fun rollbackReservation(reservation: ExactReservation): Boolean {
        val token = reservation.token
        val normalized = normalize(token.episodePath)
        return synchronized(globalLock) {
            val key = RecordKey(normalized, token.discoveryGeneration)
            val record = records[key] ?: return@synchronized false
            if (record.state != State.RESERVED ||
                record.reservedAtMs != reservation.reservedAtMs ||
                record.discoveryGeneration != token.discoveryGeneration ||
                record.manifestDigest != token.exactManifestDigest ||
                record.exactProofDigest != token.exactProofDigest ||
                record.planBindingDigest != token.planBindingDigest ||
                record.promotionNonce != token.nonce ||
                record.operations.isNotEmpty()
            ) {
                return@synchronized false
            }
            records.remove(key, record).also { globalLock.notifyAll() }
        }
    }

    internal fun rollbackUninstalledOwner(owner: Owner): Boolean {
        val normalized = normalize(owner.path)
        return synchronized(globalLock) {
            val key = RecordKey(normalized, owner.discoveryGeneration)
            val record = records[key] ?: return@synchronized false
            if (record.state != State.OWNED ||
                record.discoveryGeneration != owner.discoveryGeneration ||
                record.manifestDigest != owner.manifestDigest ||
                record.exactProofDigest != owner.exactProofDigest ||
                record.planBindingDigest != owner.planBindingDigest ||
                record.promotionNonce != owner.promotionNonce ||
                record.sessionId != owner.sessionId ||
                record.operations.isNotEmpty()
            ) return@synchronized false
            records.remove(key, record).also { globalLock.notifyAll() }
        }
    }

    @JvmStatic
    fun owner(path: String?): Owner? {
        return synchronized(globalLock) { newestRecordLocked(path)?.owner() }
    }

    @JvmStatic
    fun nextOperationId(): Long = operationSequence.getAndIncrement()

    /** Actor-safe admission probe. Source actors must never enter [beginOperation]'s wait path. */
    fun canBeginOperationNow(path: String, manifestDigest: String, sessionId: Long): Boolean {
        val normalized = normalize(path)
        return synchronized(globalLock) {
            val record = ownedRecordLocked(normalized, manifestDigest, sessionId)
                ?: return@synchronized false
            if (record.state != State.OWNED ||
                (record.primaryAdmissionsSealed && record.retryEligiblePages.isEmpty())
            ) {
                return@synchronized false
            }
            val allOperations = records.values.flatMap { it.operations.values }
            val activeSessionKeys = allOperations.mapTo(LinkedHashSet()) { active ->
                active.tag.sessionId to active.tag.manifestDigest
            }
            NtkGlobalSourceAdmissionPolicy.canAdmit(
                activeSessionKeys,
                sessionId to manifestDigest,
                allOperations.size,
            )
        }
    }

    fun beginOperation(
        path: String,
        tag: NtkStrictSourceCallTag,
        routeKeyHash: String,
        callFactoryId: String,
        attempt: Int,
        rangeStart: Long = -1L,
        rangeEnd: Long = -1L,
        manifestRevision: Long = -1L,
        demandEpoch: Long = -1L,
        launchedPreGeometry: Boolean = false,
        metadataQueueDepth: Int = -1,
        bodyQueueDepth: Int = -1,
        method: String = "GET",
        workId: Long = 0L,
        episodeAuthority: Long = 0L,
        preclaim: Boolean = episodeAuthority == 0L
    ): OperationLease {
        require(tag.isProductionStrict)
        require(attempt >= 0)
        require(method == "GET") { "Strict image source operations must use GET" }
        require(tag.laneIndex in 0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS) {
            "Strict source operation has an invalid physical lane"
        }
        val normalized = normalize(path)
        val admissionRequestedAt = monotonicMs()
        synchronized(globalLock) {
            var record = ownedRecordLocked(normalized, tag.manifestDigest, tag.sessionId)
                ?: throw IllegalStateException("Strict source operation has no reserved owner")
            while (true) {
                check(record.state == State.OWNED && record.manifestDigest == tag.manifestDigest &&
                    record.sessionId == tag.sessionId
                ) { "Strict source operation owner mismatch" }
                check(!record.operations.containsKey(tag.operationId)) {
                    "Duplicate strict source operation id"
                }
                val allOperations = records.values.flatMap { it.operations.values }
                val activeSessionKeys = allOperations.mapTo(LinkedHashSet()) { active ->
                    active.tag.sessionId to active.tag.manifestDigest
                }
                if (NtkGlobalSourceAdmissionPolicy.canAdmit(
                        activeSessionKeys,
                        tag.sessionId to tag.manifestDigest,
                        allOperations.size
                    )
                ) {
                    break
                }
                globalLock.wait()
                record = ownedRecordLocked(normalized, tag.manifestDigest, tag.sessionId)
                    ?: throw IllegalStateException("Strict source owner disappeared while waiting")
            }
            val startedAt = monotonicMs()
            val gateWaitMs = startedAt - admissionRequestedAt
            check(record.state == State.OWNED && record.manifestDigest == tag.manifestDigest &&
                record.sessionId == tag.sessionId
            ) { "Strict source operation owner mismatch" }
            check(!record.operations.containsKey(tag.operationId)) {
                "Duplicate strict source operation id"
            }
            val activeForSourceKey = records.values.asSequence()
                .flatMap { it.operations.values.asSequence() }
                .count { active ->
                    active.tag.sessionId == tag.sessionId &&
                        active.tag.manifestDigest == tag.manifestDigest &&
                        active.tag.pageIndex == tag.pageIndex
                }
            check(activeForSourceKey == 0) {
                "A strict source key already has a physical producer"
            }
            val activeForPhysicalLane = records.values.asSequence()
                .flatMap { it.operations.values.asSequence() }
                .count { operation ->
                    operation.tag.sessionId == tag.sessionId &&
                        operation.tag.manifestDigest == tag.manifestDigest &&
                        operation.tag.laneIndex == tag.laneIndex
                }
            check(activeForPhysicalLane == 0) {
                "A strict source physical lane already has an active operation"
            }
            val active = globalCountsLocked()
            val preGeometryPhase = NtkPreGeometryAdmissionPolicy.phase(
                record.preGeometryStageRunwayClosed
            )
            val reclaimedMetadataLane = false
            check(tag.kind == NtkStrictSourceOperationKind.PRIMARY_FULL_BODY)
            check(attempt == tag.attemptOrdinal &&
                attempt in 1..NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS
            ) {
                "Strict primary full body attempt identity is invalid"
            }
            check(rangeStart == -1L && rangeEnd == -1L && method == "GET") {
                "Strict primary full body must be an un-ranged GET"
            }
            if (attempt == 1) {
                check(!record.geometrySealed && !record.primaryAdmissionsSealed) {
                    "Strict first-attempt primary admission started after its seal"
                }
                check(record.primaryStartedPages.add(tag.pageIndex)) {
                    "Strict source key attempted a second first-attempt producer"
                }
            } else {
                check(tag.pageIndex in record.primaryStartedPages &&
                    tag.pageIndex !in record.successfulPrimaryPages &&
                    record.retryEligiblePages.remove(tag.pageIndex)
                ) {
                    "Strict retry did not follow a retired failed producer"
                }
            }
            check(active.total < NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
            val attemptOrdinal = 1
            record.operations[tag.operationId] = ActiveOperation(
                tag,
                startedAt,
                routeKeyHash,
                callFactoryId,
                method,
                attempt,
                rangeStart,
                rangeEnd,
                manifestRevision,
                demandEpoch,
                launchedPreGeometry,
                metadataQueueDepth,
                bodyQueueDepth,
                preGeometryPhase,
                reclaimedMetadataLane,
                attemptOrdinal,
                workId,
                episodeAuthority,
                preclaim
            )
            val next = globalCountsLocked()
            record.peakActiveTotal = maxOf(record.peakActiveTotal, next.total)
            record.producerMax = maxOf(record.producerMax, activeForSourceKey + 1)
            globalPeakActiveTotal = maxOf(globalPeakActiveTotal, next.total)
            record.routeKeyHashes += routeKeyHash
            val detailedStart =
                NtkStrictSourceOperationTelemetryPolicy.shouldLogStart(tag.pageIndex)
            val snapshot = if (detailedStart) telemetry(record, next) else null
            // A scheduler-clock tick is expected while a 100+ call wave enters the global gate;
            // logging every 1 ms observation costs more than the event describes. Preserve only
            // waits large enough to diagnose actual admission contention.
            if (gateWaitMs >= MATERIAL_GATE_WAIT_MS) {
                operationTelemetry.offerRaw(
                    "reader_strip_source_global_wait_end",
                    tag.sessionId
                ) {
                    "reader_strip_source_global_wait_end sessionId=${tag.sessionId}," +
                        "manifestDigest=${tag.manifestDigest}," +
                        "operationId=${tag.operationId},kind=${tag.kind}," +
                        "lane=${tag.laneIndex},page=${tag.pageIndex},gateWaitMs=$gateWaitMs"
                }
            }
            if (snapshot != null) {
                operationTelemetry.offerRaw(
                    "reader_strip_source_operation_start",
                    tag.sessionId
                ) {
                    operationLog(
                    "reader_strip_source_operation_start",
                    tag,
                    routeKeyHash,
                    callFactoryId,
                    removedMethod = method,
                    attempt = attempt,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    manifestRevision = manifestRevision,
                    demandEpoch = demandEpoch,
                    launchedPreGeometry = launchedPreGeometry,
                    metadataQueueDepth = metadataQueueDepth,
                    bodyQueueDepth = bodyQueueDepth,
                    preGeometryPhase = preGeometryPhase,
                    reclaimedMetadataLane = reclaimedMetadataLane,
                    attemptOrdinal = attemptOrdinal,
                    workId = workId,
                    episodeAuthority = episodeAuthority,
                    preclaim = preclaim,
                    telemetry = snapshot,
                        tail = "gateWaitMs=$gateWaitMs,elapsedMs=0"
                    )
                }
            }
        }
        return OperationLease(normalized, tag)
    }

    /** Called at the actual Call creation point. */
    fun validateCall(path: String?, tag: NtkStrictSourceCallTag?): Boolean {
        val normalized = normalize(path)
        if (normalized.isEmpty()) {
            if (tag?.isProductionStrict != true) return true
            Log.e(
                "ViewerPerf",
                "reader_strip_source_terminal path=<empty>,reason=strict_tag_without_owned_path," +
                    "sessionId=${tag.sessionId},operationId=${tag.operationId}"
            )
            return false
        }
        var unleasedCallCount = 0L
        var globalActiveTotal = 0
        var globalPeak = 0
        var cancelledLoser = false
        var cancelledLoserCount = 0L
        var duplicateCallTag = false
        var strictTagWithoutOwner = false
        val accepted = synchronized(globalLock) {
            val strictTag = tag?.takeIf { it.isProductionStrict }
            val record = if (strictTag != null) {
                // Never attribute a late call from a drained generation to its replacement.
                ownedRecordLocked(normalized, strictTag.manifestDigest, strictTag.sessionId)
            } else {
                newestRecordLocked(normalized)
            }
            if (record == null) {
                strictTagWithoutOwner = strictTag != null
                globalActiveTotal = globalCountsLocked().total
                globalPeak = globalPeakActiveTotal
                return@synchronized !strictTagWithoutOwner
            }
            val activeOperation = tag?.takeIf { it.isProductionStrict }?.let {
                record.operations[it.operationId]
            }
            val leasedOperation = tag != null && activeOperation?.tag == tag
            val belongsToCurrentOwner = leasedOperation && record.state == State.OWNED &&
                tag!!.sessionId == record.sessionId && tag.manifestDigest == record.manifestDigest
            val result = when {
                belongsToCurrentOwner && activeOperation?.callAdmitted == false -> {
                    activeOperation.callAdmitted = true
                    true
                }
                belongsToCurrentOwner -> {
                    // One operation lease admits exactly one physical Call. Reusing its request
                    // tag would otherwise hide unbounded sockets behind a single 4-op ledger row.
                    duplicateCallTag = true
                    record.unleasedCallCount++
                    false
                }
                leasedOperation -> {
                    cancelledLoser = true
                    val retired = record.retiredSessionMetrics[
                        tag!!.sessionId to tag.manifestDigest
                    ]
                    if (retired != null) {
                        retired.cancelledLoserCount++
                        cancelledLoserCount = retired.cancelledLoserCount
                    } else {
                        record.cancelledLoserCount++
                        cancelledLoserCount = record.cancelledLoserCount
                    }
                    false
                }
                else -> {
                    record.unleasedCallCount++
                    false
                }
            }
            unleasedCallCount = record.unleasedCallCount
            globalActiveTotal = globalCountsLocked().total
            globalPeak = globalPeakActiveTotal
            result
        }
        if (!accepted) {
            if (cancelledLoser) {
                Log.d(
                    "ViewerPerf",
                    "reader_strip_source_cancelled_loser path=$normalized," +
                        "sessionId=${tag?.sessionId ?: 0L},operationId=${tag?.operationId ?: 0L}," +
                        "cancelledLoserCount=$cancelledLoserCount," +
                        "globalActiveTotal=$globalActiveTotal,globalPeakActiveTotal=$globalPeak"
                )
            } else {
                Log.e(
                    "ViewerPerf",
                    "reader_strip_source_terminal path=$normalized," +
                        "reason=${when {
                            strictTagWithoutOwner -> "strict_tag_without_owned_path"
                            duplicateCallTag -> "reused_operation_call_tag"
                            else -> "unleased_owned_path_call"
                        }}," +
                        "unleasedCallCount=$unleasedCallCount," +
                        "globalActiveTotal=$globalActiveTotal," +
                        "globalPeakActiveTotal=$globalPeak"
                )
            }
        }
        return accepted
    }

    fun snapshot(path: String): Snapshot? {
        return synchronized(globalLock) {
            val record = newestRecordLocked(path) ?: return@synchronized null
            val counts = counts(record.operations.values.map(ActiveOperation::tag))
            val globalCounts = globalCountsLocked()
            Snapshot(
                record.owner(),
                counts.metadata,
                counts.stageBody,
                counts.urgentBody,
                counts.backgroundBody,
                counts.legacy,
                counts.total,
                record.unleasedCallCount,
                record.peakActiveTotal,
                record.producerMax,
                record.newConnectionCount,
                record.reusedConnectionCount,
                record.routeKeyHashes.size,
                globalCounts.total,
                globalPeakActiveTotal,
                record.connectionIds.size,
                record.cancelledLoserCount,
                record.metadataNewConnectionCount,
                record.metadataConnectionReuseCount,
                record.metadataConnectionIds.size,
                record.metadataRouteKeyHashes.size,
                record.partialBodyOperationCount,
                record.preGeometryPeakActiveMetadata,
                record.reclaimedMetadataOperationCount,
                counts.primaryFullBody,
                record.primaryStartedPages.size,
                record.primaryAdmissionsSealedAtMs
            )
        }
    }

    internal fun clearForTest() {
        synchronized(globalLock) {
            records.clear()
            discoveryFences.clear()
            operationSequence.set(1L)
            globalPeakActiveTotal = 0
            globalLock.notifyAll()
        }
    }

    private fun finishOperation(
        path: String,
        tag: NtkStrictSourceCallTag,
        httpCode: Int,
        protocol: String,
        responseBytes: Long,
        metadataWitnessBytes: Long,
        metadataAcquisition: String,
        imageFormat: String,
        contentRangeTotal: Long,
        connectionId: String,
        connectionReused: Boolean,
        succeeded: Boolean,
        partialBodyOperation: Boolean,
        responseIdentityDigest: String,
        metadataBindingDigest: String,
        bodyDigest: String
    ) {
        synchronized(globalLock) {
            val record = records.values.firstOrNull { candidate ->
                candidate.path == normalize(path) &&
                    candidate.operations[tag.operationId]?.tag == tag
            } ?: return
            val removed = record.operations.remove(tag.operationId) ?: return
            val belongsToCurrentOwner = record.state == State.OWNED &&
                record.sessionId == removed.tag.sessionId &&
                record.manifestDigest == removed.tag.manifestDigest
            if (belongsToCurrentOwner &&
                removed.tag.kind == NtkStrictSourceOperationKind.PRIMARY_FULL_BODY
            ) {
                if (succeeded) {
                    record.retryEligiblePages.remove(removed.tag.pageIndex)
                    record.successfulPrimaryPages.add(removed.tag.pageIndex)
                } else if (removed.tag.attemptOrdinal <
                    NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS &&
                    removed.tag.pageIndex !in record.successfulPrimaryPages
                ) {
                    record.retryEligiblePages.add(removed.tag.pageIndex)
                }
            }
            val retiredKey = removed.tag.sessionId to removed.tag.manifestDigest
            val retiredMetrics = if (belongsToCurrentOwner) {
                null
            } else {
                record.retiredSessionMetrics[retiredKey]
            }
            if (partialBodyOperation) {
                if (belongsToCurrentOwner) {
                    record.partialBodyOperationCount++
                } else if (retiredMetrics != null) {
                    retiredMetrics.partialBodyOperationCount++
                }
            }
            if (connectionId.isNotBlank()) {
                if (belongsToCurrentOwner) {
                    record.connectionIds += connectionId
                    if (connectionReused) {
                        record.reusedConnectionCount++
                    } else {
                        record.newConnectionCount++
                    }
                } else if (retiredMetrics != null) {
                    retiredMetrics.connectionIds += connectionId
                    if (connectionReused) {
                        retiredMetrics.reusedConnectionCount++
                    } else {
                        retiredMetrics.newConnectionCount++
                    }
                }
            }
            if (NtkStrictSourceOperationTelemetryPolicy.shouldLogEnd(
                    tag.pageIndex,
                    succeeded,
                    partialBodyOperation,
                )
            ) {
                val counts = globalCountsLocked()
                val snapshot = if (retiredMetrics == null) {
                    telemetry(record, counts)
                } else {
                    telemetry(retiredMetrics, counts)
                }
                val elapsed = monotonicMs() - removed.startedAtMs
                operationTelemetry.offerRaw(
                    "reader_strip_source_operation_end",
                    tag.sessionId
                ) {
                    operationLog(
                    "reader_strip_source_operation_end",
                    tag,
                    removed.routeKeyHash,
                    removed.callFactoryId,
                    removed.method,
                    removed.attempt,
                    removed.rangeStart,
                    removed.rangeEnd,
                    removed.manifestRevision,
                    removed.demandEpoch,
                    removed.launchedPreGeometry,
                    removed.metadataQueueDepth,
                    removed.bodyQueueDepth,
                    removed.preGeometryPhase,
                    removed.reclaimedMetadataLane,
                    removed.attemptOrdinal,
                    removed.workId,
                    removed.episodeAuthority,
                    removed.preclaim,
                    snapshot,
                    "succeeded=$succeeded,partialBodyOperation=$partialBodyOperation," +
                        "protocol=$protocol,connectionId=$connectionId," +
                        "connectionReused=$connectionReused,reused=$connectionReused," +
                        "httpCode=$httpCode,contentRangeTotal=$contentRangeTotal," +
                        "total=$contentRangeTotal,responseBytes=$responseBytes," +
                        "metadataWitnessBytes=$metadataWitnessBytes,witnessBytes=$metadataWitnessBytes," +
                        "metadataAcquisition=$metadataAcquisition,acquisition=$metadataAcquisition," +
                        "imageFormat=$imageFormat,format=$imageFormat," +
                        "responseIdentityDigest=$responseIdentityDigest," +
                        "metadataBindingDigest=$metadataBindingDigest,bodyDigest=$bodyDigest," +
                        "elapsedMs=$elapsed"
                    )
                }
            }
            if (!belongsToCurrentOwner && record.operations.values.none {
                    it.tag.sessionId == removed.tag.sessionId &&
                        it.tag.manifestDigest == removed.tag.manifestDigest
                }
            ) {
                record.retiredSessionMetrics.remove(retiredKey)
            }
            globalLock.notifyAll()
        }
    }

    private data class Counts(
        val primaryFullBody: Int,
        val metadata: Int,
        val stageBody: Int,
        val urgentBody: Int,
        val backgroundBody: Int,
        val legacy: Int = 0
    ) {
        val total: Int
            get() = primaryFullBody + metadata + stageBody + urgentBody + backgroundBody
    }

    private data class OperationTelemetry(
        val counts: Counts,
        val unleasedCallCount: Long,
        val producerMax: Int,
        val globalPeakActiveTotal: Int,
        val cancelledLoserCount: Long,
        val newConnectionCount: Long,
        val reusedConnectionCount: Long,
        val distinctConnectionCount: Int,
        val distinctRouteCount: Int,
        val metadataNewConnectionCount: Long,
        val metadataConnectionReuseCount: Long,
        val metadataDistinctConnectionCount: Int,
        val distinctRouteKeyCount: Int,
        val partialBodyOperationCount: Long,
        val preGeometryPeakActiveMetadata: Int,
        val reclaimedMetadataOperationCount: Long,
        val preGeometryStageRunwayClosedAtMs: Long
    )

    private fun telemetry(record: Record, counts: Counts): OperationTelemetry =
        OperationTelemetry(
            counts = counts,
            unleasedCallCount = record.unleasedCallCount,
            producerMax = record.producerMax,
            globalPeakActiveTotal = globalPeakActiveTotal,
            cancelledLoserCount = record.cancelledLoserCount,
            newConnectionCount = record.newConnectionCount,
            reusedConnectionCount = record.reusedConnectionCount,
            distinctConnectionCount = record.connectionIds.size,
            distinctRouteCount = record.routeKeyHashes.size,
            metadataNewConnectionCount = record.metadataNewConnectionCount,
            metadataConnectionReuseCount = record.metadataConnectionReuseCount,
            metadataDistinctConnectionCount = record.metadataConnectionIds.size,
            distinctRouteKeyCount = record.metadataRouteKeyHashes.size,
            partialBodyOperationCount = record.partialBodyOperationCount,
            preGeometryPeakActiveMetadata = record.preGeometryPeakActiveMetadata,
            reclaimedMetadataOperationCount = record.reclaimedMetadataOperationCount,
            preGeometryStageRunwayClosedAtMs =
                record.preGeometryStageRunwayClosedAtMs
        )

    private fun telemetry(
        metrics: RetiredSessionMetrics,
        counts: Counts
    ): OperationTelemetry = OperationTelemetry(
        counts = counts,
        unleasedCallCount = metrics.unleasedCallCount,
        producerMax = metrics.producerMax,
        globalPeakActiveTotal = globalPeakActiveTotal,
        cancelledLoserCount = metrics.cancelledLoserCount,
        newConnectionCount = metrics.newConnectionCount,
        reusedConnectionCount = metrics.reusedConnectionCount,
        distinctConnectionCount = metrics.connectionIds.size,
        distinctRouteCount = metrics.routeKeyHashes.size,
        metadataNewConnectionCount = metrics.metadataNewConnectionCount,
        metadataConnectionReuseCount = metrics.metadataConnectionReuseCount,
        metadataDistinctConnectionCount = metrics.metadataConnectionIds.size,
        distinctRouteKeyCount = metrics.metadataRouteKeyHashes.size,
        partialBodyOperationCount = metrics.partialBodyOperationCount,
        preGeometryPeakActiveMetadata = metrics.preGeometryPeakActiveMetadata,
        reclaimedMetadataOperationCount = metrics.reclaimedMetadataOperationCount,
        preGeometryStageRunwayClosedAtMs = metrics.preGeometryStageRunwayClosedAtMs
    )

    private fun globalCountsLocked(): Counts {
        return counts(records.values.flatMap { it.operations.values }.map(ActiveOperation::tag))
    }

    /** Newest generation is the active path view; older generations are drain-only tombstones. */
    private fun newestRecordLocked(path: String?): Record? {
        val normalized = normalize(path)
        return records.values
            .asSequence()
            .filter { it.path == normalized }
            .maxByOrNull { it.discoveryGeneration }
    }

    private fun ownedRecordLocked(
        path: String?,
        manifestDigest: String,
        sessionId: Long,
        discoveryGeneration: Long? = null,
    ): Record? {
        val normalized = normalize(path)
        return records.values.firstOrNull { record ->
            record.path == normalized &&
                record.state == State.OWNED &&
                record.manifestDigest == manifestDigest &&
                record.sessionId == sessionId &&
                (discoveryGeneration == null ||
                    record.discoveryGeneration == discoveryGeneration)
        }
    }

    private fun counts(tags: Iterable<NtkStrictSourceCallTag>): Counts {
        var metadata = 0
        var primary = 0
        var stage = 0
        var urgent = 0
        var background = 0
        for (tag in tags) when (tag.kind) {
            NtkStrictSourceOperationKind.PRIMARY_FULL_BODY -> primary++
        }
        return Counts(primary, metadata, stage, urgent, background)
    }

    private fun operationLog(
        event: String,
        tag: NtkStrictSourceCallTag,
        routeKeyHash: String,
        callFactoryId: String,
        removedMethod: String,
        attempt: Int,
        rangeStart: Long,
        rangeEnd: Long,
        manifestRevision: Long,
        demandEpoch: Long,
        launchedPreGeometry: Boolean,
        metadataQueueDepth: Int,
        bodyQueueDepth: Int,
        preGeometryPhase: NtkPreGeometryPhase,
        reclaimedMetadataLane: Boolean,
        attemptOrdinal: Int,
        workId: Long,
        episodeAuthority: Long,
        preclaim: Boolean,
        telemetry: OperationTelemetry,
        tail: String
    ): String = "$event sessionId=${tag.sessionId},manifestDigest=${tag.manifestDigest}," +
        "manifestRevision=$manifestRevision,operationId=${tag.operationId}," +
        "operationKind=${tag.kind},kind=${tag.kind},method=$removedMethod," +
        "laneIndex=${tag.laneIndex},lane=${tag.laneIndex}," +
        "pageIndex=${tag.pageIndex},page=${tag.pageIndex}," +
        "routeKeyHash=$routeKeyHash,callFactoryId=$callFactoryId," +
        "demandEpoch=$demandEpoch,attemptOrdinal=$attemptOrdinal," +
        "workId=$workId,producerGeneration=1," +
        "episodeAuthority=$episodeAuthority,preclaim=$preclaim," +
        "launchedPreGeometry=$launchedPreGeometry," +
        "preGeometryPhase=$preGeometryPhase," +
        "reclaimedMetadataLane=$reclaimedMetadataLane," +
        "rangeStart=$rangeStart,rangeEnd=$rangeEnd," +
        "metadataQueueDepth=$metadataQueueDepth,bodyQueueDepth=$bodyQueueDepth," +
        "activePrimarySpools=${telemetry.counts.primaryFullBody}," +
        "activeMetadata=${telemetry.counts.metadata}," +
        "activePrimaryFullBody=${telemetry.counts.primaryFullBody}," +
        "activeStageBody=${telemetry.counts.stageBody}," +
        "activeUrgentBody=${telemetry.counts.urgentBody}," +
        "activeBackgroundBody=${telemetry.counts.backgroundBody}," +
        "activeLegacy=${telemetry.counts.legacy},activeTotal=${telemetry.counts.total}," +
        "globalActiveTotal=${telemetry.counts.total}," +
        "globalPeakActiveTotal=${telemetry.globalPeakActiveTotal}," +
        "producerMax=${telemetry.producerMax}," +
        "cancelledLoserCount=${telemetry.cancelledLoserCount}," +
        "unleasedCallCount=${telemetry.unleasedCallCount}," +
        "newConnectionCount=${telemetry.newConnectionCount}," +
        "reusedConnectionCount=${telemetry.reusedConnectionCount}," +
        "distinctConnectionCount=${telemetry.distinctConnectionCount}," +
        "distinctRouteCount=${telemetry.distinctRouteCount}," +
        "metadataNewConnectionCount=${telemetry.metadataNewConnectionCount}," +
        "metadataConnectionReuseCount=${telemetry.metadataConnectionReuseCount}," +
        "metadataDistinctConnectionCount=${telemetry.metadataDistinctConnectionCount}," +
        "distinctRouteKeyCount=${telemetry.distinctRouteKeyCount}," +
        "partialBodyOperationCount=${telemetry.partialBodyOperationCount}," +
        "preGeometryPeakActiveMetadata=${telemetry.preGeometryPeakActiveMetadata}," +
        "reclaimedMetadataOperationCount=${telemetry.reclaimedMetadataOperationCount}," +
        "preGeometryStageRunwayClosedAtMs=" +
        "${telemetry.preGeometryStageRunwayClosedAtMs}," +
        "physicalBodyProducerCountByKeyMax=${telemetry.producerMax},$tail"

    private fun normalize(path: String?): String = path?.let(NtkStripDigests::normalizeEpisodePath)
        ?.lowercase()
        .orEmpty()

    private const val MATERIAL_GATE_WAIT_MS = 10L

    /**
     * Keep ownership evidence in the same time domain as [NtkStrictSourceSession].
     *
     * `System.nanoTime()` and `SystemClock.elapsedRealtime()` do not advance identically while a
     * physical device is suspended. Comparing an ownership claim recorded with the former to an
     * exact seal recorded with the latter therefore made promotion fail on long-running devices,
     * cancelling every image request before any body could be published. The JVM fallback keeps
     * the registry's pure local tests usable where the Android clock is only a throwing stub.
     */
    private fun monotonicMs(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrNull()
        ?.takeIf { it > 0L }
        ?: (System.nanoTime() / 1_000_000L)

    private fun logDebug(message: String) {
        // android.util.Log is a no-op contractually; keeping it non-authoritative also permits the
        // generation ledger to be exercised by pure JVM concurrency tests.
        runCatching { Log.d("ViewerPerf", message) }
    }

}
