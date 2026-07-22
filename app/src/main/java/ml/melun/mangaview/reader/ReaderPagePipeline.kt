package ml.melun.mangaview.reader

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Episode-scoped authority for page production. This first migration slice owns only state and
 * leases; existing producers remain active until they are moved behind this API in later slices.
 */
class ReaderPagePipeline(
    val episodeEpoch: Long,
    private val pageCount: Int
) {
    init {
        require(episodeEpoch > 0L) { "episodeEpoch must be positive" }
        require(pageCount >= 0) { "pageCount must not be negative" }
    }

    data class PageKey(val episodeEpoch: Long, val pageIndex: Int)

    data class ResolvedAsset(
        val canonicalAsset: String,
        val resolvedUrl: String
    )

    data class TileIdentity(
        val canonicalAsset: String,
        val pageWidth: Int,
        val pageHeight: Int,
        val tileSourceHeightPx: Int = ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
    )

    data class PhysicalWindow(val requiredPages: Set<Int>) {
        companion object {
            fun contiguous(first: Int, last: Int): PhysicalWindow =
                if (last < first) PhysicalWindow(emptySet())
                else PhysicalWindow((first..last).toSet())
        }
    }

    enum class Demand(val priority: Int) {
        FAR_TAIL(0),
        ROLLING_PROOF_METADATA(1),
        POST_ACTIVATION_BYTES(2),
        PHYSICAL_RUNWAY(3),
        VISIBLE(4),
        PRE_ACTIVATION_CRITICAL(5)
    }

    enum class Stage {
        EMPTY,
        BYTE_IN_FLIGHT,
        BYTES_READY,
        DECODE_IN_FLIGHT,
        TILES_READY,
        INSTALL_QUEUED,
        INSTALLED,
        FAILED_RETRYABLE,
        RETIRED
    }

    enum class LeaseStage { BYTES, DECODE, INSTALL }

    data class StageLease(
        val pageKey: PageKey,
        val stage: LeaseStage,
        val leaseId: Long,
        val demand: Demand
    )

    enum class RequestDisposition {
        STARTED_BYTES,
        STARTED_DECODE,
        JOINED_BYTES,
        JOINED_DECODE,
        ALREADY_TILES_READY,
        ALREADY_INSTALL_QUEUED,
        ALREADY_INSTALLED,
        RETIRED
    }

    data class RequestResult(
        val disposition: RequestDisposition,
        val lease: StageLease? = null
    )

    data class ByteCompletion(
        val episodeEpoch: Long,
        val pageIndex: Int,
        val canonicalAsset: String,
        val resolvedUrl: String,
        val stageLeaseId: Long
    )

    data class TileCompletion(
        val episodeEpoch: Long,
        val pageIndex: Int,
        val canonicalAsset: String,
        val stageLeaseId: Long,
        val identity: TileIdentity
    )

    data class PreparedPage(
        val pageIndex: Int,
        val identity: TileIdentity,
        val tilePage: ReaderPreparedStore.PreparedTilePage? = null,
        val installedSurfaceEpoch: Long? = null
    )

    data class PreparedHandoff(
        val episodeEpoch: Long,
        val pages: List<PreparedPage>
    )

    data class PageSnapshot(
        val key: PageKey,
        val stage: Stage,
        val demand: Demand,
        val asset: ResolvedAsset?,
        val tileIdentity: TileIdentity?,
        val hasTilePayload: Boolean,
        val surfaceEpoch: Long?,
        val activeLease: StageLease?
    )

    data class PipelineInvariantSnapshot(
        val retired: Boolean,
        val pageSlots: Int,
        val activeByteOwners: Int,
        val activeDecodeOwners: Int,
        val activeInstallOwners: Int,
        val rejectedStaleCompletions: Long,
        val rejectedDuplicateInstalls: Long,
        val acceptedStaleCompletions: Long = 0L
    )

    private data class PageSlot(
        val key: PageKey,
        var stage: Stage = Stage.EMPTY,
        var demand: Demand = Demand.FAR_TAIL,
        var asset: ResolvedAsset? = null,
        var identity: TileIdentity? = null,
        var tilePage: ReaderPreparedStore.PreparedTilePage? = null,
        var surfaceEpoch: Long? = null,
        var lease: StageLease? = null
    )

    private val lock = Any()
    private val leaseSequence = AtomicLong()
    private val slots = LinkedHashMap<Int, PageSlot>()
    private val tileSubscribers = LinkedHashMap<Int, LinkedHashMap<Long, (ReaderPreparedStore.PreparedTilePage) -> Unit>>()
    private var physicalWindow = PhysicalWindow(emptySet())
    private var retired = false
    private var rejectedStaleCompletions = 0L
    private var rejectedDuplicateInstalls = 0L

    fun requestBytes(index: Int, demand: Demand): RequestResult = synchronized(lock) {
        val slot = slot(index)
        promote(slot, demand)
        if (retired || slot.stage == Stage.RETIRED) return@synchronized retiredResult()
        when (slot.stage) {
            Stage.EMPTY, Stage.FAILED_RETRYABLE -> start(slot, LeaseStage.BYTES, Stage.BYTE_IN_FLIGHT)
            Stage.BYTE_IN_FLIGHT -> RequestResult(RequestDisposition.JOINED_BYTES)
            Stage.BYTES_READY, Stage.DECODE_IN_FLIGHT -> RequestResult(RequestDisposition.JOINED_DECODE)
            Stage.TILES_READY -> RequestResult(RequestDisposition.ALREADY_TILES_READY)
            Stage.INSTALL_QUEUED -> RequestResult(RequestDisposition.ALREADY_INSTALL_QUEUED)
            Stage.INSTALLED -> RequestResult(RequestDisposition.ALREADY_INSTALLED)
            Stage.RETIRED -> retiredResult()
        }
    }

    fun requestDrawable(index: Int, demand: Demand): RequestResult = synchronized(lock) {
        val slot = slot(index)
        promote(slot, demand)
        if (retired || slot.stage == Stage.RETIRED) return@synchronized retiredResult()
        when (slot.stage) {
            Stage.EMPTY, Stage.FAILED_RETRYABLE -> start(slot, LeaseStage.BYTES, Stage.BYTE_IN_FLIGHT)
            Stage.BYTE_IN_FLIGHT -> RequestResult(RequestDisposition.JOINED_BYTES)
            Stage.BYTES_READY -> start(slot, LeaseStage.DECODE, Stage.DECODE_IN_FLIGHT)
            Stage.DECODE_IN_FLIGHT -> RequestResult(RequestDisposition.JOINED_DECODE)
            Stage.TILES_READY -> RequestResult(RequestDisposition.ALREADY_TILES_READY)
            Stage.INSTALL_QUEUED -> RequestResult(RequestDisposition.ALREADY_INSTALL_QUEUED)
            Stage.INSTALLED -> RequestResult(RequestDisposition.ALREADY_INSTALLED)
            Stage.RETIRED -> retiredResult()
        }
    }

    fun acceptBytes(completion: ByteCompletion): Boolean = synchronized(lock) {
        val slot = slots[completion.pageIndex] ?: return@synchronized rejectStale()
        if (!matches(slot, completion.episodeEpoch, LeaseStage.BYTES, completion.stageLeaseId)) {
            return@synchronized rejectStale()
        }
        val canonical = completion.canonicalAsset.trim()
        if (canonical.isEmpty() || completion.resolvedUrl.trim().isEmpty()) return@synchronized rejectStale()
        slot.asset = ResolvedAsset(canonical, completion.resolvedUrl)
        slot.stage = Stage.BYTES_READY
        slot.lease = null
        true
    }

    @JvmOverloads
    fun acceptTiles(
        completion: TileCompletion,
        tilePage: ReaderPreparedStore.PreparedTilePage? = null
    ): Boolean {
        val callbacks: List<(ReaderPreparedStore.PreparedTilePage) -> Unit>
        synchronized(lock) {
        val slot = slots[completion.pageIndex] ?: return rejectStale()
        if (!matches(slot, completion.episodeEpoch, LeaseStage.DECODE, completion.stageLeaseId)) {
            return rejectStale()
        }
        val asset = slot.asset ?: return rejectStale()
        if (completion.canonicalAsset != asset.canonicalAsset ||
            completion.identity.canonicalAsset != asset.canonicalAsset ||
            !validIdentity(completion.identity)
        ) return rejectStale()
        if (tilePage != null && !ReaderPreparedStore.isCanonicalOriginalTilePage(
                tilePage,
                completion.identity.canonicalAsset
            )
        ) return rejectStale()
        slot.identity = completion.identity
        slot.tilePage = tilePage
        slot.stage = Stage.TILES_READY
        slot.lease = null
        callbacks = if (tilePage == null) emptyList()
        else tileSubscribers.remove(completion.pageIndex)?.values?.toList().orEmpty()
        }
        if (tilePage != null) callbacks.forEach { it(tilePage) }
        return true
    }

    fun acceptPreparedHandoff(handoff: PreparedHandoff): Int = synchronized(lock) {
        if (retired || handoff.episodeEpoch != episodeEpoch) return@synchronized 0
        var accepted = 0
        for (page in handoff.pages) {
            if (!validIndex(page.pageIndex) || !validIdentity(page.identity)) continue
            val slot = slot(page.pageIndex)
            if (slot.stage == Stage.INSTALLED || slot.stage == Stage.INSTALL_QUEUED) continue
            slot.asset = ResolvedAsset(page.identity.canonicalAsset, page.identity.canonicalAsset)
            slot.identity = page.identity
            slot.tilePage = page.tilePage
            slot.lease = null
            slot.surfaceEpoch = page.installedSurfaceEpoch
            slot.stage = if (page.installedSurfaceEpoch == null) Stage.TILES_READY else Stage.INSTALLED
            accepted++
        }
        accepted
    }

    fun updatePhysicalWindow(window: PhysicalWindow) = synchronized(lock) {
        physicalWindow = PhysicalWindow(window.requiredPages.filter(::validIndex).toSet())
    }

    fun preparedTilePage(index: Int): ReaderPreparedStore.PreparedTilePage? = synchronized(lock) {
        slots[index]?.tilePage
    }

    fun subscribeTilePage(
        index: Int,
        subscriberId: Long,
        callback: (ReaderPreparedStore.PreparedTilePage) -> Unit
    ): Boolean {
        require(subscriberId > 0L) { "subscriberId must be positive" }
        val ready = synchronized(lock) {
            if (retired || !validIndex(index)) return false
            slots[index]?.tilePage?.let { return@synchronized it }
            tileSubscribers.getOrPut(index) { LinkedHashMap() }[subscriberId] = callback
            slots[index]?.tilePage
        }
        if (ready != null) {
            synchronized(lock) { tileSubscribers[index]?.remove(subscriberId) }
            callback(ready)
        }
        return true
    }

    fun cancelTileSubscription(index: Int, subscriberId: Long) = synchronized(lock) {
        tileSubscribers[index]?.let { subscribers ->
            subscribers.remove(subscriberId)
            if (subscribers.isEmpty()) tileSubscribers.remove(index)
        }
    }

    fun queueInstall(index: Int, surfaceEpoch: Long): StageLease? = synchronized(lock) {
        if (retired || surfaceEpoch <= 0L || index !in physicalWindow.requiredPages) return@synchronized null
        val slot = slots[index] ?: return@synchronized null
        if (slot.stage != Stage.TILES_READY || slot.identity == null) return@synchronized null
        val lease = newLease(slot, LeaseStage.INSTALL)
        slot.stage = Stage.INSTALL_QUEUED
        slot.surfaceEpoch = surfaceEpoch
        slot.lease = lease
        lease
    }

    fun confirmInstalled(index: Int, identity: TileIdentity, surfaceEpoch: Long): Boolean = synchronized(lock) {
        val leaseId = slots[index]?.lease?.leaseId ?: return@synchronized rejectDuplicateInstall()
        confirmInstalledLocked(index, identity, surfaceEpoch, leaseId)
    }

    fun confirmInstalled(
        index: Int,
        identity: TileIdentity,
        surfaceEpoch: Long,
        installLeaseId: Long
    ): Boolean = synchronized(lock) {
        confirmInstalledLocked(index, identity, surfaceEpoch, installLeaseId)
    }

    fun onSurfaceEvicted(index: Int, surfaceEpoch: Long): Boolean = synchronized(lock) {
        val slot = slots[index] ?: return@synchronized false
        if (slot.stage != Stage.INSTALLED || slot.surfaceEpoch != surfaceEpoch || slot.identity == null) {
            return@synchronized false
        }
        slot.stage = Stage.TILES_READY
        slot.surfaceEpoch = null
        slot.lease = null
        true
    }

    fun failRetryable(index: Int, leaseId: Long): Boolean = synchronized(lock) {
        val slot = slots[index] ?: return@synchronized false
        if (slot.lease?.leaseId != leaseId) return@synchronized rejectStale()
        slot.stage = Stage.FAILED_RETRYABLE
        slot.lease = null
        true
    }

    fun retire(reason: String) = synchronized(lock) {
        if (retired) return@synchronized
        require(reason.isNotBlank()) { "retire reason must not be blank" }
        retired = true
        for (slot in slots.values) {
            slot.stage = Stage.RETIRED
            slot.lease = null
            slot.surfaceEpoch = null
        }
        tileSubscribers.clear()
    }

    fun pageSnapshot(index: Int): PageSnapshot? = synchronized(lock) {
        slots[index]?.let(::snapshotOf)
    }

    fun invariantSnapshot(): PipelineInvariantSnapshot = synchronized(lock) {
        PipelineInvariantSnapshot(
            retired = retired,
            pageSlots = slots.size,
            activeByteOwners = slots.values.count { it.stage == Stage.BYTE_IN_FLIGHT && it.lease?.stage == LeaseStage.BYTES },
            activeDecodeOwners = slots.values.count { it.stage == Stage.DECODE_IN_FLIGHT && it.lease?.stage == LeaseStage.DECODE },
            activeInstallOwners = slots.values.count { it.stage == Stage.INSTALL_QUEUED && it.lease?.stage == LeaseStage.INSTALL },
            rejectedStaleCompletions = rejectedStaleCompletions,
            rejectedDuplicateInstalls = rejectedDuplicateInstalls
        )
    }

    private fun confirmInstalledLocked(
        index: Int,
        identity: TileIdentity,
        surfaceEpoch: Long,
        installLeaseId: Long
    ): Boolean {
        val slot = slots[index] ?: return rejectDuplicateInstall()
        if (!matches(slot, episodeEpoch, LeaseStage.INSTALL, installLeaseId) ||
            slot.surfaceEpoch != surfaceEpoch || slot.identity != identity
        ) return rejectDuplicateInstall()
        slot.stage = Stage.INSTALLED
        slot.lease = null
        return true
    }

    private fun start(slot: PageSlot, leaseStage: LeaseStage, nextStage: Stage): RequestResult {
        val lease = newLease(slot, leaseStage)
        slot.stage = nextStage
        slot.lease = lease
        val disposition = if (leaseStage == LeaseStage.BYTES) {
            RequestDisposition.STARTED_BYTES
        } else {
            RequestDisposition.STARTED_DECODE
        }
        return RequestResult(disposition, lease)
    }

    private fun newLease(slot: PageSlot, stage: LeaseStage): StageLease =
        StageLease(slot.key, stage, leaseSequence.incrementAndGet(), slot.demand)

    private fun matches(slot: PageSlot, epoch: Long, stage: LeaseStage, leaseId: Long): Boolean =
        !retired && slot.key.episodeEpoch == epoch && slot.lease?.stage == stage &&
            slot.lease?.leaseId == leaseId

    private fun slot(index: Int): PageSlot {
        require(validIndex(index)) { "page index out of range: $index/$pageCount" }
        return slots.getOrPut(index) { PageSlot(PageKey(episodeEpoch, index)) }
    }

    private fun validIndex(index: Int): Boolean = index >= 0 && (pageCount == 0 || index < pageCount)

    private fun validIdentity(identity: TileIdentity): Boolean =
        identity.canonicalAsset.isNotBlank() && identity.pageWidth > 0 && identity.pageHeight > 0 &&
            identity.tileSourceHeightPx == ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX

    private fun promote(slot: PageSlot, demand: Demand) {
        if (demand.priority > slot.demand.priority) slot.demand = demand
    }

    private fun snapshotOf(slot: PageSlot): PageSnapshot = PageSnapshot(
        key = slot.key,
        stage = slot.stage,
        demand = slot.demand,
        asset = slot.asset,
        tileIdentity = slot.identity,
        hasTilePayload = slot.tilePage != null,
        surfaceEpoch = slot.surfaceEpoch,
        activeLease = slot.lease
    )

    private fun retiredResult() = RequestResult(RequestDisposition.RETIRED)

    private fun rejectStale(): Boolean {
        rejectedStaleCompletions++
        return false
    }

    private fun rejectDuplicateInstall(): Boolean {
        rejectedDuplicateInstalls++
        return false
    }
}
