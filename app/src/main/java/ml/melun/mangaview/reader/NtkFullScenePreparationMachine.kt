package ml.melun.mangaview.reader

import java.util.Locale

internal enum class NtkBodyLeaseState { OPENING, OPEN, RELEASING }

internal data class NtkPreparationPageRecord(
    val pageIndex: Int,
    val state: NtkPreparationPageState = NtkPreparationPageState.EMPTY,
    val metadata: NtkSourceMetadata? = null,
    val plan: NtkPreGeometryPagePlan? = null,
    val artifact: NtkPreGeometryPageArtifact? = null,
    val descriptor: NtkStrictBodyDescriptor? = null
)

internal data class NtkPreparationTileRecord(
    val plan: NtkPreGeometryTilePlan,
    val state: NtkPreparationTileState = NtkPreparationTileState.PLANNED,
    val admission: NtkPreparationAdmissionIdentity? = null,
    val tileProof: NtkPreparedOriginalTileProof? = null,
    val payloadToken: Long = 0L,
    val install: NtkNativeInstallIdentity? = null,
    val nativeAck: NtkPreparedTileResidentAck? = null
) {
    init {
        require(payloadToken >= 0L)
    }
}

internal data class NtkBodyLeaseRecord(
    val pageIndex: Int,
    val requestId: Long,
    val descriptorId: Long,
    val state: NtkBodyLeaseState,
    val leaseId: Long = 0L,
    val taskReferences: Int = 0
) {
    init {
        require(pageIndex >= 0 && requestId > 0L && descriptorId > 0L)
        require(leaseId >= 0L && taskReferences >= 0)
        require((state == NtkBodyLeaseState.OPENING) == (leaseId == 0L))
    }
}

internal data class NtkBodyLeaseLedger(
    val records: Map<Int, NtkBodyLeaseRecord> = emptyMap()
) {
    val openingCount: Int get() = records.values.count { it.state == NtkBodyLeaseState.OPENING }
    val openCount: Int get() = records.values.count { it.state != NtkBodyLeaseState.OPENING }
    val activeCount: Int get() = records.size
    val totalTaskReferences: Int get() = records.values.sumOf { it.taskReferences }
}

internal data class NtkPreparationAdmissionLedger(
    val admissions: Map<NtkStripTileKey, NtkPreparationAdmissionIdentity> = emptyMap()
)

internal data class NtkFullScenePreparationConfig(
    val episode: NtkEpisodeToken,
    val preparationGeneration: Long,
    val manifestSeal: NtkEpisodeManifestSeal,
    val initialPageIndex: Int,
    val cpuPolicy: NtkCpuTransientPolicy
) {
    init {
        require(episode.value > 0L && preparationGeneration > 0L)
        require(manifestSeal.isStructurallyComplete)
        require(initialPageIndex in 0 until manifestSeal.pageCount)
    }
}

internal data class NtkPreparationGeometrySeed(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val geometryRevision: Long
) {
    init {
        require(viewportWidthPx > 0 && viewportHeightPx > 0)
        require(geometryRevision > 0L)
    }
}

internal data class NtkFullScenePreparationSnapshot(
    val config: NtkFullScenePreparationConfig,
    val phase: NtkFullScenePreparationPhase,
    val pages: List<NtkPreparationPageRecord>,
    val tileRecords: Map<NtkStripTileKey, NtkPreparationTileRecord>,
    val admissionLedger: NtkPreparationAdmissionLedger,
    val bodyLeaseLedger: NtkBodyLeaseLedger,
    val accounting: NtkFullScenePreparationAccounting,
    val nativePreparationToken: NtkNativePreparationToken?,
    val geometrySeed: NtkPreparationGeometrySeed?,
    val publishedSurface: NtkPublishedSurfaceIdentity?,
    val geometry: NtkStripGeometry?,
    val geometryBindRequest: NtkGeometryBindRequest?,
    val geometryBindProof: NtkGeometryBindProof?,
    val uploadInFlight: NtkNativeInstallIdentity?,
    val activeDecoderTasks: Set<NtkPreparationAdmissionIdentity>,
    val counters: NtkFullScenePreparationCounters,
    val decoderThreadIds: Set<Long>,
    val resourceCycleLedger: NtkResourceCycleLedger,
    val outputCreditsUsed: Int,
    val nextAdmissionId: Long,
    val nextInstallLease: Long,
    val lastNativeInventoryDigest: String,
    val lastResourceCompletionNanos: Long,
    val sourceDrainProof: NtkSourceDrainProof?,
    val drainProof: NtkPreparationDrainProof?,
    val preparedSeal: NtkPreparedFullSceneSeal?,
    val failureReason: String?
) {
    val metadataByPage: List<NtkSourceMetadata?> get() = pages.map { it.metadata }
    val plansByPage: List<NtkPreGeometryPagePlan?> get() = pages.map { it.plan }
    val artifactsByPage: List<NtkPreGeometryPageArtifact?> get() = pages.map { it.artifact }
}

internal sealed interface NtkFullScenePreparationEvent {
    data class DetachedPreparationOpened(val token: NtkNativePreparationToken) :
        NtkFullScenePreparationEvent
    data class GeometrySeedAvailable(val seed: NtkPreparationGeometrySeed) :
        NtkFullScenePreparationEvent
    data class SurfacePublished(val identity: NtkPublishedSurfaceIdentity) :
        NtkFullScenePreparationEvent
    data class MetadataReady(val metadata: NtkSourceMetadata) : NtkFullScenePreparationEvent
    data class BodyPublished(val descriptor: NtkStrictBodyDescriptor) :
        NtkFullScenePreparationEvent
    data class BodyLeaseOpened(val pageIndex: Int, val requestId: Long, val leaseId: Long) :
        NtkFullScenePreparationEvent
    data class BodyLeaseOpenFailed(val pageIndex: Int, val requestId: Long, val reason: String) :
        NtkFullScenePreparationEvent
    data class BodyLeaseReleased(val pageIndex: Int, val leaseId: Long) :
        NtkFullScenePreparationEvent
    data class DecodeStarted(
        val admission: NtkPreparationAdmissionIdentity,
        val priority: NtkDecodePriority,
        val workerThreadId: Long,
        val actualActiveTasks: Int
    ) : NtkFullScenePreparationEvent
    data class DecodeCompleted(
        val admission: NtkPreparationAdmissionIdentity,
        val tileProof: NtkPreparedOriginalTileProof,
        val payloadToken: Long
    ) : NtkFullScenePreparationEvent
    data class DecodeFailed(
        val admission: NtkPreparationAdmissionIdentity,
        val reason: String
    ) : NtkFullScenePreparationEvent
    data class NativeInstallAck(val ack: NtkPreparedTileResidentAck) :
        NtkFullScenePreparationEvent
    data class SurfacePreparationBound(val proof: NtkGeometryBindProof) :
        NtkFullScenePreparationEvent
    data class SourceDrained(val proof: NtkSourceDrainProof) : NtkFullScenePreparationEvent
    data class DrainCompleted(val proof: NtkPreparationDrainProof) :
        NtkFullScenePreparationEvent
    data class SurfaceLost(val identity: NtkPublishedSurfaceIdentity) :
        NtkFullScenePreparationEvent
    data object Retire : NtkFullScenePreparationEvent
}

internal sealed interface NtkFullScenePreparationCommand {
    data class OpenBodyLease(
        val pageIndex: Int,
        val requestId: Long,
        val descriptor: NtkStrictBodyDescriptor
    ) : NtkFullScenePreparationCommand
    data class StartDecode(
        val admission: NtkPreparationAdmissionIdentity,
        val leaseId: Long,
        val tilePlan: NtkPreGeometryTilePlan,
        val expectedProof: NtkPreparedOriginalTileProof
    ) : NtkFullScenePreparationCommand
    data class StartDecodeCohort(
        val decodes: List<StartDecode>
    ) : NtkFullScenePreparationCommand {
        init {
            require(decodes.size == 3)
            require(decodes.map { it.admission }.toSet().size == decodes.size)
            require(decodes.map { it.leaseId }.all { it > 0L })
        }
    }
    data class ReleaseBodyLease(val pageIndex: Int, val leaseId: Long) :
        NtkFullScenePreparationCommand
    data class InstallPrepared(
        val identity: NtkNativeInstallIdentity,
        val payloadToken: Long,
        val tileProof: NtkPreparedOriginalTileProof,
        val surfaceToken: NtkSurfacePreparationToken?
    ) : NtkFullScenePreparationCommand
    data class AdoptDetachedPreparation(val request: NtkGeometryBindRequest) :
        NtkFullScenePreparationCommand
    data class NotifyGeometrySealed(val geometry: NtkStripGeometry) :
        NtkFullScenePreparationCommand
    data object ClosePreparationAdmissions : NtkFullScenePreparationCommand
    data class PublishSeal(val seal: NtkPreparedFullSceneSeal) :
        NtkFullScenePreparationCommand
    data class ReleasePreparationAuthority(val reason: String) :
        NtkFullScenePreparationCommand
}

internal data class NtkFullScenePreparationTransition(
    val snapshot: NtkFullScenePreparationSnapshot,
    val commands: List<NtkFullScenePreparationCommand>
)

/** Pure single-owner reducer for source-to-native full-scene preparation. */
internal class NtkFullScenePreparationMachine(
    private val nowNanos: () -> Long = System::nanoTime
) {
    fun initial(config: NtkFullScenePreparationConfig): NtkFullScenePreparationSnapshot =
        NtkFullScenePreparationSnapshot(
            config = config,
            phase = NtkFullScenePreparationPhase.OPEN,
            pages = List(config.manifestSeal.pageCount) { NtkPreparationPageRecord(it) },
            tileRecords = emptyMap(),
            admissionLedger = NtkPreparationAdmissionLedger(),
            bodyLeaseLedger = NtkBodyLeaseLedger(),
            accounting = NtkFullScenePreparationAccounting(),
            nativePreparationToken = null,
            geometrySeed = null,
            publishedSurface = null,
            geometry = null,
            geometryBindRequest = null,
            geometryBindProof = null,
            uploadInFlight = null,
            activeDecoderTasks = emptySet(),
            counters = NtkFullScenePreparationCounters(),
            decoderThreadIds = emptySet(),
            resourceCycleLedger = NtkResourceCycleLedger.empty(),
            outputCreditsUsed = 0,
            nextAdmissionId = 1L,
            nextInstallLease = 1L,
            lastNativeInventoryDigest = "",
            lastResourceCompletionNanos = 0L,
            sourceDrainProof = null,
            drainProof = null,
            preparedSeal = null,
            failureReason = null
        )

    fun reduce(
        current: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent
    ): NtkFullScenePreparationTransition {
        if (current.phase == NtkFullScenePreparationPhase.SEALED) {
            return if (event == NtkFullScenePreparationEvent.Retire) {
                NtkFullScenePreparationTransition(
                    current.copy(phase = NtkFullScenePreparationPhase.RETIRED),
                    listOf(NtkFullScenePreparationCommand.ReleasePreparationAuthority("retire"))
                )
            } else NtkFullScenePreparationTransition(current, emptyList())
        }
        if (current.phase == NtkFullScenePreparationPhase.FAILED ||
            current.phase == NtkFullScenePreparationPhase.RETIRED
        ) return NtkFullScenePreparationTransition(current, emptyList())
        if (event == NtkFullScenePreparationEvent.Retire) {
            return NtkFullScenePreparationTransition(
                current.copy(phase = NtkFullScenePreparationPhase.RETIRED),
                listOf(NtkFullScenePreparationCommand.ReleasePreparationAuthority("retire"))
            )
        }
        if (event is NtkFullScenePreparationEvent.SurfaceLost) {
            return if (event.identity == current.publishedSurface) {
                fail(current, "surface lost before prepared-scene seal")
            } else fail(current, "surface loss identity mismatch")
        }
        if (current.phase == NtkFullScenePreparationPhase.QUIESCING &&
            event !is NtkFullScenePreparationEvent.DrainCompleted
        ) return fail(current, "work event ${event.javaClass.simpleName} after quiescence")

        val transition = when (event) {
            is NtkFullScenePreparationEvent.DetachedPreparationOpened ->
                acceptNativeToken(current, event.token)
            is NtkFullScenePreparationEvent.GeometrySeedAvailable ->
                acceptGeometrySeed(current, event.seed)
            is NtkFullScenePreparationEvent.SurfacePublished ->
                acceptSurface(current, event.identity)
            is NtkFullScenePreparationEvent.MetadataReady ->
                acceptMetadata(current, event.metadata)
            is NtkFullScenePreparationEvent.BodyPublished ->
                acceptBodyPublished(current, event.descriptor)
            is NtkFullScenePreparationEvent.BodyLeaseOpened ->
                acceptLeaseOpened(current, event)
            is NtkFullScenePreparationEvent.BodyLeaseOpenFailed ->
                fail(current, "body lease open failed page=${event.pageIndex}: ${event.reason}")
            is NtkFullScenePreparationEvent.BodyLeaseReleased ->
                acceptLeaseReleased(current, event)
            is NtkFullScenePreparationEvent.DecodeStarted ->
                acceptDecodeStarted(current, event)
            is NtkFullScenePreparationEvent.DecodeCompleted ->
                acceptDecodeCompleted(current, event)
            is NtkFullScenePreparationEvent.DecodeFailed ->
                failWithDecodeFailure(current, event)
            is NtkFullScenePreparationEvent.NativeInstallAck ->
                acceptNativeAck(current, event.ack)
            is NtkFullScenePreparationEvent.SurfacePreparationBound ->
                acceptGeometryBound(current, event.proof)
            is NtkFullScenePreparationEvent.SourceDrained ->
                acceptSourceDrain(current, event.proof)
            is NtkFullScenePreparationEvent.DrainCompleted ->
                acceptDrain(current, event.proof)
            is NtkFullScenePreparationEvent.SurfaceLost,
            NtkFullScenePreparationEvent.Retire -> error("handled above")
        }
        return if (transition.snapshot.phase in setOf(
                NtkFullScenePreparationPhase.FAILED,
                NtkFullScenePreparationPhase.RETIRED,
                NtkFullScenePreparationPhase.SEALED,
                NtkFullScenePreparationPhase.QUIESCING
            )
        ) transition else pump(transition.snapshot, transition.commands)
    }

    private fun acceptNativeToken(
        s: NtkFullScenePreparationSnapshot,
        token: NtkNativePreparationToken
    ): NtkFullScenePreparationTransition {
        val c = s.config
        if (token.authority != c.episode.value ||
            token.preparationGeneration != c.preparationGeneration ||
            token.manifestRevision != c.manifestSeal.revision ||
            token.manifestDigest != c.manifestSeal.digestSha256 ||
            s.publishedSurface?.engineGeneration?.let { it != token.engineGeneration } == true
        ) return fail(s, "native preparation token identity mismatch")
        val existing = s.nativePreparationToken
        if (existing != null) {
            return if (existing == token) NtkFullScenePreparationTransition(s, emptyList())
            else fail(s, "native preparation token mutated")
        }
        return NtkFullScenePreparationTransition(s.copy(nativePreparationToken = token), emptyList())
    }

    private fun acceptGeometrySeed(
        s: NtkFullScenePreparationSnapshot,
        seed: NtkPreparationGeometrySeed
    ): NtkFullScenePreparationTransition {
        val existing = s.geometrySeed
        if (existing != null) {
            return if (existing == seed) NtkFullScenePreparationTransition(s, emptyList())
            else fail(s, "geometry seed mutated")
        }
        val surface = s.publishedSurface
        if (surface != null && (surface.width != seed.viewportWidthPx ||
                surface.height != seed.viewportHeightPx ||
                surface.geometryRevision != seed.geometryRevision)
        ) return fail(s, "geometry seed conflicts with published surface")
        return createGeometryIfReady(s.copy(geometrySeed = seed))
    }

    private fun acceptSurface(
        s: NtkFullScenePreparationSnapshot,
        identity: NtkPublishedSurfaceIdentity
    ): NtkFullScenePreparationTransition {
        val token = s.nativePreparationToken
        if (identity.demandGeneration <= 0L ||
            token != null && identity.engineGeneration != token.engineGeneration
        ) return fail(s, "published surface identity mismatch")
        val seed = s.geometrySeed
        if (seed != null && (identity.width != seed.viewportWidthPx ||
                identity.height != seed.viewportHeightPx ||
                identity.geometryRevision != seed.geometryRevision)
        ) return fail(s, "published surface geometry mismatch")
        val existing = s.publishedSurface
        if (existing != null) {
            return if (existing == identity) NtkFullScenePreparationTransition(s, emptyList())
            else fail(s, "published surface mutated")
        }
        return NtkFullScenePreparationTransition(
            s.copy(publishedSurface = identity),
            emptyList()
        )
    }

    private fun acceptMetadata(
        s: NtkFullScenePreparationSnapshot,
        metadata: NtkSourceMetadata
    ): NtkFullScenePreparationTransition {
        val pageIndex = metadata.pageIndex
        if (!validMetadata(s.config, metadata)) return fail(s, "metadata identity mismatch page=$pageIndex")
        if (s.pages[pageIndex].metadata != null) return fail(s, "duplicate metadata page=$pageIndex")
        val plan = runCatching { NtkSourceTileLayout.create(s.config.episode, metadata) }
            .getOrElse { return fail(s, "pregeometry plan failed page=$pageIndex: ${it.message}") }
        if (Math.multiplyExact(plan.largestTileRgbaBytes, 3L) > s.config.cpuPolicy.hardCapBytes) {
            return fail(s, "UNSATISFIABLE_CPU_TRANSIENT page=$pageIndex")
        }
        val pages = s.pages.toMutableList()
        pages[pageIndex] = pages[pageIndex].copy(
            state = NtkPreparationPageState.METADATA_READY,
            metadata = metadata,
            plan = plan
        )
        val tiles = LinkedHashMap(s.tileRecords)
        plan.tiles.forEach { tile ->
            if (tiles.put(tile.key, NtkPreparationTileRecord(tile)) != null) {
                return fail(s, "duplicate planned tile ${tile.key}")
            }
        }
        val next = s.copy(
            pages = pages,
            tileRecords = tiles,
            counters = s.counters.copy(
                metadataCount = s.counters.metadataCount + 1L,
                plannedTileCount = s.counters.plannedTileCount + plan.tiles.size
            )
        )
        return createGeometryIfReady(next)
    }

    private fun createGeometryIfReady(
        s: NtkFullScenePreparationSnapshot
    ): NtkFullScenePreparationTransition {
        if (s.geometry != null || s.pages.any { it.plan == null }) {
            return NtkFullScenePreparationTransition(s, emptyList())
        }
        val seed = s.geometrySeed
            ?: return NtkFullScenePreparationTransition(s, emptyList())
        val plans = s.pages.map { checkNotNull(it.plan) }
        val geometry = runCatching {
            NtkStripGeometry.createFromPreGeometryPlans(
                s.config.episode,
                seed.viewportWidthPx,
                s.config.manifestSeal,
                plans
            )
        }.getOrElse { return fail(s, "geometry reconciliation failed: ${it.message}") }
        if (geometry.largestTileRgbaBytes != plans.maxOf { it.largestTileRgbaBytes }) {
            return fail(s, "geometry largest tile diverged from pregeometry plans")
        }
        return NtkFullScenePreparationTransition(
            s.copy(
                phase = NtkFullScenePreparationPhase.GEOMETRY_READY,
                geometry = geometry
            ),
            listOf(NtkFullScenePreparationCommand.NotifyGeometrySealed(geometry))
        )
    }

    private fun acceptBodyPublished(
        s: NtkFullScenePreparationSnapshot,
        descriptor: NtkStrictBodyDescriptor
    ): NtkFullScenePreparationTransition {
        val pageIndex = descriptor.sourceKey.pageIndex
        if (pageIndex !in s.pages.indices) return fail(s, "body page out of bounds $pageIndex")
        val page = s.pages[pageIndex]
        val previous = page.descriptor
        if (previous != null) {
            return if (previous === descriptor) NtkFullScenePreparationTransition(s, emptyList())
            else fail(s, "body descriptor capability mutated page=$pageIndex")
        }
        val metadata = page.metadata ?: return fail(s, "body publication preceded metadata page=$pageIndex")
        val plan = page.plan ?: return fail(s, "body publication lacks plan page=$pageIndex")
        if (descriptor.sourceKey != metadata.strictSourceKey || descriptor.metadata != metadata ||
            descriptor.proof.strictSourceKey != descriptor.sourceKey
        ) return fail(s, "body descriptor authority mismatch page=$pageIndex")
        val artifact = runCatching {
            NtkPreGeometryPageArtifact.create(plan, metadata, descriptor.proof)
        }.getOrElse { return fail(s, "body artifact failed page=$pageIndex: ${it.message}") }
        val pages = s.pages.toMutableList()
        pages[pageIndex] = page.copy(
            state = NtkPreparationPageState.BODY_PUBLISHED,
            artifact = artifact,
            descriptor = descriptor
        )
        return NtkFullScenePreparationTransition(
            s.copy(
                pages = pages,
                counters = s.counters.copy(
                    bodyPublishedCount = s.counters.bodyPublishedCount + 1L,
                    pageArtifactCount = s.counters.pageArtifactCount + 1L
                )
            ),
            emptyList()
        )
    }

    private fun acceptLeaseOpened(
        s: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent.BodyLeaseOpened
    ): NtkFullScenePreparationTransition {
        val record = s.bodyLeaseLedger.records[event.pageIndex]
            ?: return fail(s, "unknown body lease open page=${event.pageIndex}")
        if (record.state != NtkBodyLeaseState.OPENING || record.requestId != event.requestId ||
            event.leaseId <= 0L
        ) return fail(s, "body lease open identity mismatch page=${event.pageIndex}")
        val records = LinkedHashMap(s.bodyLeaseLedger.records)
        records[event.pageIndex] = record.copy(
            state = NtkBodyLeaseState.OPEN,
            leaseId = event.leaseId
        )
        val pages = s.pages.toMutableList()
        pages[event.pageIndex] = pages[event.pageIndex].copy(
            state = NtkPreparationPageState.LEASED
        )
        return NtkFullScenePreparationTransition(
            s.copy(
                pages = pages,
                bodyLeaseLedger = NtkBodyLeaseLedger(records),
                counters = s.counters.copy(leaseOpenCount = s.counters.leaseOpenCount + 1L)
            ),
            emptyList()
        )
    }

    private fun acceptLeaseReleased(
        s: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent.BodyLeaseReleased
    ): NtkFullScenePreparationTransition {
        val record = s.bodyLeaseLedger.records[event.pageIndex]
            ?: return fail(s, "unknown body lease release page=${event.pageIndex}")
        if (record.state != NtkBodyLeaseState.RELEASING || record.leaseId != event.leaseId ||
            record.taskReferences != 0
        ) return fail(s, "body lease release identity mismatch page=${event.pageIndex}")
        val records = LinkedHashMap(s.bodyLeaseLedger.records)
        records.remove(event.pageIndex)
        val pages = s.pages.toMutableList()
        pages[event.pageIndex] = pages[event.pageIndex].copy(
            state = NtkPreparationPageState.COMPLETE
        )
        return NtkFullScenePreparationTransition(
            s.copy(
                pages = pages,
                bodyLeaseLedger = NtkBodyLeaseLedger(records),
                counters = s.counters.copy(
                    leaseReleaseCount = s.counters.leaseReleaseCount + 1L
                )
            ),
            emptyList()
        )
    }

    private fun acceptDecodeStarted(
        s: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent.DecodeStarted
    ): NtkFullScenePreparationTransition {
        val record = s.tileRecords[event.admission.key]
            ?: return fail(s, "decode start unknown tile ${event.admission.key}")
        if (record.state != NtkPreparationTileState.ADMITTED ||
            record.admission != event.admission || event.admission !in s.activeDecoderTasks
        ) return fail(s, "duplicate or mismatched decode start ${event.admission.key}")
        if (event.priority != NtkDecodePriority.NORMAL || event.workerThreadId <= 0L ||
            event.actualActiveTasks !in 1..3
        ) return fail(s, "decode worker entry contract mismatch ${event.admission.key}")
        val tiles = LinkedHashMap(s.tileRecords)
        tiles[event.admission.key] = record.copy(state = NtkPreparationTileState.DECODING)
        val threads = s.decoderThreadIds + event.workerThreadId
        val reachedThree = event.actualActiveTasks == 3
        return NtkFullScenePreparationTransition(
            s.copy(
                tileRecords = tiles,
                decoderThreadIds = threads,
                counters = s.counters.copy(
                    decodeStartedCount = s.counters.decodeStartedCount + 1L,
                    actualDecodeActiveMax = maxOf(
                        s.counters.actualDecodeActiveMax,
                        event.actualActiveTasks
                    ),
                    actualNormalPriorityTaskStarts =
                        s.counters.actualNormalPriorityTaskStarts + 1L,
                    threeWideEntryCount = s.counters.threeWideEntryCount +
                        if (reachedThree) 1L else 0L
                )
            ),
            emptyList()
        )
    }

    private fun acceptDecodeCompleted(
        s: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent.DecodeCompleted
    ): NtkFullScenePreparationTransition {
        val key = event.admission.key
        val record = s.tileRecords[key] ?: return fail(s, "decode completion unknown tile $key")
        if (record.state != NtkPreparationTileState.DECODING ||
            record.admission != event.admission || event.admission !in s.activeDecoderTasks ||
            event.payloadToken <= 0L
        ) return fail(s, "duplicate or mismatched decode completion $key")
        val expected = record.tileProof ?: return fail(s, "decode completion lacks expected proof $key")
        if (event.tileProof != expected) return fail(s, "decoded tile proof mismatch $key")
        val lease = s.bodyLeaseLedger.records[key.pageIndex]
            ?: return fail(s, "decode completion lost body lease $key")
        if (lease.state != NtkBodyLeaseState.OPEN || lease.taskReferences <= 0) {
            return fail(s, "decode completion lease reference mismatch $key")
        }
        val tiles = LinkedHashMap(s.tileRecords)
        tiles[key] = record.copy(
            state = NtkPreparationTileState.CPU_READY,
            payloadToken = event.payloadToken
        )
        val leases = LinkedHashMap(s.bodyLeaseLedger.records)
        leases[key.pageIndex] = lease.copy(taskReferences = lease.taskReferences - 1)
        val bytes = record.plan.rgbaBytes
        return NtkFullScenePreparationTransition(
            s.copy(
                tileRecords = tiles,
                activeDecoderTasks = s.activeDecoderTasks - event.admission,
                bodyLeaseLedger = NtkBodyLeaseLedger(leases),
                accounting = s.accounting.copy(
                    cpuReservedBytes = Math.subtractExact(s.accounting.cpuReservedBytes, bytes),
                    cpuDecodedBytes = Math.addExact(s.accounting.cpuDecodedBytes, bytes)
                ),
                counters = s.counters.copy(
                    decodeCompletedCount = s.counters.decodeCompletedCount + 1L
                )
            ),
            emptyList()
        )
    }

    private fun failWithDecodeFailure(
        s: NtkFullScenePreparationSnapshot,
        event: NtkFullScenePreparationEvent.DecodeFailed
    ): NtkFullScenePreparationTransition = fail(
        s.copy(counters = s.counters.copy(
            decodeFailureCount = s.counters.decodeFailureCount + 1L
        )),
        "decode failed ${event.admission.key}: ${event.reason}"
    )

    private fun acceptNativeAck(
        s: NtkFullScenePreparationSnapshot,
        ack: NtkPreparedTileResidentAck
    ): NtkFullScenePreparationTransition {
        val expectedInstall = s.uploadInFlight
            ?: return fail(s, "native ACK without upload")
        val key = expectedInstall.admission.key
        val record = s.tileRecords[key] ?: return fail(s, "native ACK unknown tile $key")
        if (ack.identity != expectedInstall || record.install != expectedInstall ||
            record.state != NtkPreparationTileState.UPLOADING ||
            ack.tileProofDigest != record.tileProof?.tileProofDigest
        ) return fail(s, "duplicate or mismatched native ACK $key")
        val preGeometry = s.phase != NtkFullScenePreparationPhase.SURFACE_BOUND
        if (ack.preGeometryPrepared != preGeometry ||
            ack.resourceCompletionNanos <= s.lastResourceCompletionNanos
        ) return fail(s, "native ACK phase/order mismatch $key")
        val tiles = LinkedHashMap(s.tileRecords)
        tiles[key] = record.copy(
            state = if (preGeometry) NtkPreparationTileState.NATIVE_PREPARED
            else NtkPreparationTileState.RESIDENT,
            payloadToken = 0L,
            nativeAck = ack
        )
        val bytes = record.plan.rgbaBytes
        val accounting = if (preGeometry) s.accounting.copy(
            cpuDecodedBytes = Math.subtractExact(s.accounting.cpuDecodedBytes, bytes),
            gpuUploadReservedBytes = Math.subtractExact(
                s.accounting.gpuUploadReservedBytes,
                bytes
            ),
            gpuPreparedResidentBytes = Math.addExact(
                s.accounting.gpuPreparedResidentBytes,
                bytes
            )
        ) else s.accounting.copy(
            cpuDecodedBytes = Math.subtractExact(s.accounting.cpuDecodedBytes, bytes),
            gpuUploadReservedBytes = Math.subtractExact(
                s.accounting.gpuUploadReservedBytes,
                bytes
            ),
            gpuSceneResidentBytes = Math.addExact(s.accounting.gpuSceneResidentBytes, bytes)
        )
        return NtkFullScenePreparationTransition(
            s.copy(
                tileRecords = tiles,
                uploadInFlight = null,
                outputCreditsUsed = s.outputCreditsUsed - 1,
                accounting = accounting,
                lastNativeInventoryDigest = ack.residentInventoryDigest,
                lastResourceCompletionNanos = ack.resourceCompletionNanos,
                counters = s.counters.copy(
                    installAckCount = s.counters.installAckCount + 1L
                )
            ),
            emptyList()
        )
    }

    private fun acceptGeometryBound(
        s: NtkFullScenePreparationSnapshot,
        proof: NtkGeometryBindProof
    ): NtkFullScenePreparationTransition {
        val request = s.geometryBindRequest ?: return fail(s, "geometry bind ACK without request")
        val geometry = s.geometry ?: return fail(s, "geometry bind ACK without geometry")
        val surface = s.publishedSurface ?: return fail(s, "geometry bind ACK without surface")
        val prepared = s.tileRecords.values.count {
            it.state == NtkPreparationTileState.NATIVE_PREPARED
        }
        if (proof.token != request.token ||
            proof.surfaceToken.surfaceEpoch != surface.surfaceEpoch ||
            proof.surfaceToken.demandGeneration != surface.demandGeneration ||
            proof.surfaceToken.attachGeneration != surface.attachGeneration ||
            proof.surfaceToken.geometryRevision != surface.geometryRevision ||
            proof.surfaceToken.width != surface.width ||
            proof.surfaceToken.height != surface.height ||
            proof.requestId != request.requestId ||
            proof.geometryDigest != request.geometryDigest ||
            proof.preGeometryRootDigest != request.preGeometryRootDigest ||
            proof.preparedInventoryDigest != request.preparedInventoryDigest ||
            proof.adoptedPreparedTileCount != prepared ||
            proof.missingGeometrySlotCount != geometry.tileCount - prepared ||
            proof.lastResourceCompletionNanos != s.lastResourceCompletionNanos
        ) return fail(s, "geometry bind proof mismatch")
        val tiles = LinkedHashMap(s.tileRecords)
        tiles.forEach { (key, value) ->
            if (value.state == NtkPreparationTileState.NATIVE_PREPARED) {
                tiles[key] = value.copy(state = NtkPreparationTileState.RESIDENT)
            }
        }
        return NtkFullScenePreparationTransition(
            s.copy(
                phase = NtkFullScenePreparationPhase.SURFACE_BOUND,
                tileRecords = tiles,
                geometryBindProof = proof,
                accounting = s.accounting.copy(
                    gpuPreparedResidentBytes = 0L,
                    gpuSceneResidentBytes = Math.addExact(
                        s.accounting.gpuSceneResidentBytes,
                        s.accounting.gpuPreparedResidentBytes
                    )
                ),
                lastNativeInventoryDigest = proof.residentInventoryDigest,
                counters = s.counters.copy(
                    geometryBindCount = s.counters.geometryBindCount + 1L
                )
            ),
            emptyList()
        )
    }

    private fun acceptSourceDrain(
        s: NtkFullScenePreparationSnapshot,
        proof: NtkSourceDrainProof
    ): NtkFullScenePreparationTransition {
        if (!proof.isExact || proof.manifestDigest != s.config.manifestSeal.digestSha256 ||
            proof.pageCount != s.pages.size ||
            s.pages.any { it.artifact == null } || s.bodyLeaseLedger.activeCount != 0
        ) return fail(s, "source drain proof mismatch")
        if (s.sourceDrainProof != null) return fail(s, "duplicate source drain proof")
        return NtkFullScenePreparationTransition(s.copy(sourceDrainProof = proof), emptyList())
    }

    private fun acceptDrain(
        s: NtkFullScenePreparationSnapshot,
        proof: NtkPreparationDrainProof
    ): NtkFullScenePreparationTransition {
        if (s.phase != NtkFullScenePreparationPhase.QUIESCING || !proof.isExact ||
            proof.source != s.sourceDrainProof
        ) return fail(s, "preparation drain proof mismatch")
        val geometry = s.geometry ?: return fail(s, "drain lacks geometry")
        val bind = s.geometryBindProof ?: return fail(s, "drain lacks geometry bind")
        val counters = s.counters.copy(
            actualDecodeActiveMax = proof.actualDecodeActiveMax,
            actualNormalPriorityTaskStarts = proof.actualNormalPriorityTaskStarts,
            actualBackgroundPriorityTaskStarts = proof.actualBackgroundPriorityTaskStarts,
            threeWideEntryCount = proof.threeWideEntryCount,
            threeWideOverlapNanos = proof.threeWideOverlapNanos
        )
        val orderedRecords = (0 until geometry.tileCount).map { ordinal ->
            s.tileRecords[geometry.keyAtOrdinal(ordinal)]
                ?: return fail(s, "seal lacks tile ordinal=$ordinal")
        }
        if (orderedRecords.any { it.state != NtkPreparationTileState.RESIDENT }) {
            return fail(s, "seal found nonresident tile")
        }
        if (s.accounting != NtkFullScenePreparationAccounting(
                gpuSceneResidentBytes = geometry.totalRgbaBytes
            ) || s.outputCreditsUsed != 0 || s.activeDecoderTasks.isNotEmpty() ||
            s.bodyLeaseLedger.activeCount != 0 || s.uploadInFlight != null
        ) return fail(s, "seal accounting not drained")
        val artifacts = s.pages.map { it.artifact ?: return fail(s, "seal lacks page artifact") }
        val tileProofs = orderedRecords.map { it.tileProof ?: return fail(s, "seal lacks tile proof") }
        val resident = orderedRecords.map { record ->
            val install = record.install ?: return fail(s, "seal lacks install identity")
            val ack = record.nativeAck ?: return fail(s, "seal lacks native ACK")
            NtkPreparedResidentTileSeal(
                identity = install,
                tileProofDigest = checkNotNull(record.tileProof).tileProofDigest,
                rgbaBytes = record.plan.rgbaBytes,
                residentInventoryDigestAtAck = ack.residentInventoryDigest
            )
        }
        val seal = runCatching {
            NtkPreparedFullSceneSeal(
                authority = s.config.episode.value,
                surfaceEpoch = bind.surfaceToken.surfaceEpoch,
                manifestRevision = s.config.manifestSeal.revision,
                manifestDigest = s.config.manifestSeal.digestSha256,
                geometryDigest = geometry.geometryDigest,
                preGeometryRootDigest = geometry.preGeometryRootDigest,
                pageArtifactRootDigest = NtkPreGeometryPageArtifact.rootDigest(artifacts),
                tileProofRootDigest = NtkPreparedOriginalTileProof.rootDigest(tileProofs),
                nativeInventoryDigest = s.lastNativeInventoryDigest,
                pageCount = s.pages.size,
                tileCount = geometry.tileCount,
                totalRgbaBytes = geometry.totalRgbaBytes,
                residentTiles = resident,
                resourceCycleLedger = s.resourceCycleLedger,
                counters = counters,
                nativeProof = bind,
                sealedAtNanos = proof.completedAtNanos
            )
        }.getOrElse { return fail(s, "full-scene seal validation failed: ${it.message}") }
        return NtkFullScenePreparationTransition(
            s.copy(
                phase = NtkFullScenePreparationPhase.SEALED,
                counters = counters,
                drainProof = proof,
                preparedSeal = seal
            ),
            listOf(NtkFullScenePreparationCommand.PublishSeal(seal))
        )
    }

    private fun pump(
        initial: NtkFullScenePreparationSnapshot,
        existingCommands: List<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationTransition {
        var s = initial
        val commands = ArrayList(existingCommands)
        s = openEligibleLeases(s, commands)
        terminalFromPump(s, commands)?.let { return it }
        s = admitEligibleTiles(s, commands)
        terminalFromPump(s, commands)?.let { return it }
        s = releaseCompletedLeases(s, commands)
        terminalFromPump(s, commands)?.let { return it }
        s = submitNativeWork(s, commands)
        terminalFromPump(s, commands)?.let { return it }
        if (canQuiesce(s)) {
            s = s.copy(phase = NtkFullScenePreparationPhase.QUIESCING)
            commands += NtkFullScenePreparationCommand.ClosePreparationAdmissions
        }
        assertInvariants(s)
        return NtkFullScenePreparationTransition(s, commands)
    }

    private fun terminalFromPump(
        snapshot: NtkFullScenePreparationSnapshot,
        commands: MutableList<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationTransition? {
        if (snapshot.phase != NtkFullScenePreparationPhase.FAILED) return null
        commands += NtkFullScenePreparationCommand.ReleasePreparationAuthority(
            snapshot.failureReason ?: "preparation pump failed"
        )
        return NtkFullScenePreparationTransition(snapshot, commands)
    }

    private fun openEligibleLeases(
        initial: NtkFullScenePreparationSnapshot,
        commands: MutableList<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationSnapshot {
        var s = initial
        val priority = NtkPreGeometrySourcePlanner.create(
            s.config.initialPageIndex,
            s.pages.size
        ).priorities
        val candidates = s.pages.filter {
            it.state == NtkPreparationPageState.BODY_PUBLISHED &&
                it.pageIndex !in s.bodyLeaseLedger.records
        }.sortedWith(compareByDescending<NtkPreparationPageRecord> {
            priority[it.pageIndex] ?: 0
        }.thenBy { it.pageIndex })
        for (page in candidates) {
            if (s.bodyLeaseLedger.activeCount >= 3) break
            val descriptor = checkNotNull(page.descriptor)
            val requestId = page.pageIndex + 1L
            val records = LinkedHashMap(s.bodyLeaseLedger.records)
            records[page.pageIndex] = NtkBodyLeaseRecord(
                pageIndex = page.pageIndex,
                requestId = requestId,
                descriptorId = descriptor.descriptorId,
                state = NtkBodyLeaseState.OPENING
            )
            val pages = s.pages.toMutableList()
            pages[page.pageIndex] = page.copy(state = NtkPreparationPageState.LEASE_OPENING)
            s = s.copy(pages = pages, bodyLeaseLedger = NtkBodyLeaseLedger(records))
            commands += NtkFullScenePreparationCommand.OpenBodyLease(
                page.pageIndex,
                requestId,
                descriptor
            )
        }
        return s
    }

    private fun admitEligibleTiles(
        initial: NtkFullScenePreparationSnapshot,
        commands: MutableList<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationSnapshot {
        var s = initial
        val priority = NtkPreGeometrySourcePlanner.create(
            s.config.initialPageIndex,
            s.pages.size
        ).priorities
        val initialThreeWideCohort =
            s.config.manifestSeal.pageCount >= 3 &&
                s.counters.decodeStartedCount == 0L &&
                s.activeDecoderTasks.isEmpty() &&
                s.outputCreditsUsed == 0
        if (initialThreeWideCohort) {
            val eligibleCount = s.tileRecords.values.count { record ->
                record.state == NtkPreparationTileState.PLANNED &&
                    s.pages[record.plan.key.pageIndex].artifact != null &&
                    s.bodyLeaseLedger.records[record.plan.key.pageIndex]?.state ==
                        NtkBodyLeaseState.OPEN
            }
            if (eligibleCount < 3) return s
        }
        val cohortStarts = if (initialThreeWideCohort) {
            ArrayList<NtkFullScenePreparationCommand.StartDecode>(3)
        } else {
            null
        }
        while (s.outputCreditsUsed < 3 && s.activeDecoderTasks.size < 3) {
            val candidate = s.tileRecords.values.filter { record ->
                record.state == NtkPreparationTileState.PLANNED &&
                    s.pages[record.plan.key.pageIndex].artifact != null &&
                    s.bodyLeaseLedger.records[record.plan.key.pageIndex]?.state ==
                        NtkBodyLeaseState.OPEN
            }.sortedWith(compareByDescending<NtkPreparationTileRecord> {
                priority[it.plan.key.pageIndex] ?: 0
            }.thenBy { it.plan.key.pageIndex }.thenBy { it.plan.key.slotIndex }).firstOrNull()
                ?: break
            val bytes = candidate.plan.rgbaBytes
            if (Math.addExact(s.accounting.cpuReservedBytes, s.accounting.cpuDecodedBytes) +
                bytes > s.config.cpuPolicy.hardCapBytes
            ) break
            val page = s.pages[candidate.plan.key.pageIndex]
            val artifact = checkNotNull(page.artifact)
            val admission = NtkPreparationAdmissionIdentity(
                authority = s.config.episode.value,
                key = candidate.plan.key,
                admissionId = stableIdentity(candidate.plan.key),
                pageArtifactDigest = artifact.artifactDigest
            )
            if (candidate.plan.key in s.admissionLedger.admissions) {
                return failSnapshot(s, "duplicate admission ${candidate.plan.key}")
            }
            val proof = NtkPreparedOriginalTileProof.create(artifact, candidate.plan)
            val cycle = NtkTileCycleIdentity(
                key = candidate.plan.key,
                admissionId = admission.admissionId,
                resourceRevision = 1L,
                admissionSurfaceEpoch = s.config.preparationGeneration
            )
            val ledgerUpdate = s.resourceCycleLedger.admit(
                s.config.preparationGeneration,
                cycle
            )
            if (!ledgerUpdate.applied) return failSnapshot(s, checkNotNull(ledgerUpdate.violation))
            val tiles = LinkedHashMap(s.tileRecords)
            tiles[candidate.plan.key] = candidate.copy(
                state = NtkPreparationTileState.ADMITTED,
                admission = admission,
                tileProof = proof
            )
            val admissions = LinkedHashMap(s.admissionLedger.admissions)
            admissions[candidate.plan.key] = admission
            val leases = LinkedHashMap(s.bodyLeaseLedger.records)
            val lease = checkNotNull(leases[candidate.plan.key.pageIndex])
            leases[candidate.plan.key.pageIndex] = lease.copy(
                taskReferences = lease.taskReferences + 1
            )
            s = s.copy(
                tileRecords = tiles,
                admissionLedger = NtkPreparationAdmissionLedger(admissions),
                bodyLeaseLedger = NtkBodyLeaseLedger(leases),
                activeDecoderTasks = s.activeDecoderTasks + admission,
                outputCreditsUsed = s.outputCreditsUsed + 1,
                nextAdmissionId = s.nextAdmissionId + 1L,
                accounting = s.accounting.copy(
                    cpuReservedBytes = Math.addExact(s.accounting.cpuReservedBytes, bytes),
                    gpuUploadReservedBytes = Math.addExact(
                        s.accounting.gpuUploadReservedBytes,
                        bytes
                    )
                ),
                resourceCycleLedger = ledgerUpdate.ledger,
                counters = s.counters.copy(admissionCount = s.counters.admissionCount + 1L)
            )
            val start = NtkFullScenePreparationCommand.StartDecode(
                admission,
                lease.leaseId,
                candidate.plan,
                proof
            )
            if (cohortStarts != null) cohortStarts += start else commands += start
        }
        if (cohortStarts != null) {
            check(cohortStarts.size == 3) {
                "Initial NORMAL decode cohort was not exactly three-wide"
            }
            commands += NtkFullScenePreparationCommand.StartDecodeCohort(cohortStarts)
        }
        return s
    }

    private fun releaseCompletedLeases(
        initial: NtkFullScenePreparationSnapshot,
        commands: MutableList<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationSnapshot {
        var s = initial
        s.bodyLeaseLedger.records.values.sortedBy { it.pageIndex }.forEach { record ->
            if (record.state != NtkBodyLeaseState.OPEN || record.taskReferences != 0) return@forEach
            val pageTiles = s.tileRecords.values.filter { it.plan.key.pageIndex == record.pageIndex }
            if (pageTiles.any { it.state == NtkPreparationTileState.PLANNED }) return@forEach
            val records = LinkedHashMap(s.bodyLeaseLedger.records)
            records[record.pageIndex] = record.copy(state = NtkBodyLeaseState.RELEASING)
            s = s.copy(bodyLeaseLedger = NtkBodyLeaseLedger(records))
            commands += NtkFullScenePreparationCommand.ReleaseBodyLease(
                record.pageIndex,
                record.leaseId
            )
        }
        return s
    }

    private fun submitNativeWork(
        initial: NtkFullScenePreparationSnapshot,
        commands: MutableList<NtkFullScenePreparationCommand>
    ): NtkFullScenePreparationSnapshot {
        var s = initial
        val token = s.nativePreparationToken ?: return s
        if (s.phase == NtkFullScenePreparationPhase.GEOMETRY_READY) {
            if (s.uploadInFlight != null || s.geometryBindRequest != null) return s
            val geometry = s.geometry ?: return s
            val surface = s.publishedSurface
            val prepared = s.tileRecords.values.filter {
                it.state == NtkPreparationTileState.NATIVE_PREPARED
            }.sortedWith(tileRecordComparator())
            if (surface != null && prepared.isNotEmpty()) {
                val seed = s.geometrySeed ?: return s
                if (surface.width != seed.viewportWidthPx ||
                    surface.height != seed.viewportHeightPx ||
                    surface.geometryRevision != seed.geometryRevision
                ) return failSnapshot(s, "surface/geometry seed mismatch")
                val preparedDigest = preparedInventoryDigest(prepared)
                val request = NtkGeometryBindRequest(
                    token = token,
                    requestId = 1L,
                    geometryDigest = geometry.geometryDigest,
                    preGeometryRootDigest = geometry.preGeometryRootDigest,
                    preparedTileKeys = prepared.map { it.plan.key },
                    preparedInventoryDigest = preparedDigest,
                    requestedAtNanos = nowNanos().coerceAtLeast(1L)
                )
                s = s.copy(
                    phase = NtkFullScenePreparationPhase.SURFACE_BIND_QUEUED,
                    geometryBindRequest = request
                )
                commands += NtkFullScenePreparationCommand.AdoptDetachedPreparation(request)
                return s
            }
        }
        if (s.phase != NtkFullScenePreparationPhase.OPEN &&
            s.phase != NtkFullScenePreparationPhase.GEOMETRY_READY &&
            s.phase != NtkFullScenePreparationPhase.SURFACE_BOUND ||
            s.uploadInFlight != null
        ) return s
        val ready = s.tileRecords.values.filter {
            it.state == NtkPreparationTileState.CPU_READY
        }.minWithOrNull(compareBy<NtkPreparationTileRecord> {
            checkNotNull(it.admission).admissionId
        }) ?: return s
        val admission = checkNotNull(ready.admission)
        val identity = NtkNativeInstallIdentity(
            admission = admission,
            preparationGeneration = s.config.preparationGeneration,
            resourceRevision = 1L,
            installLease = stableIdentity(ready.plan.key)
        )
        val cycle = NtkTileCycleIdentity(
            key = ready.plan.key,
            admissionId = admission.admissionId,
            resourceRevision = 1L,
            installLease = identity.installLease,
            admissionSurfaceEpoch = s.config.preparationGeneration
        )
        val ledgerUpdate = s.resourceCycleLedger.bindInstall(
            s.config.preparationGeneration,
            cycle
        )
        if (!ledgerUpdate.applied) return failSnapshot(s, checkNotNull(ledgerUpdate.violation))
        val tiles = LinkedHashMap(s.tileRecords)
        tiles[ready.plan.key] = ready.copy(
            state = NtkPreparationTileState.UPLOADING,
            install = identity
        )
        s = s.copy(
            tileRecords = tiles,
            uploadInFlight = identity,
            nextInstallLease = s.nextInstallLease + 1L,
            resourceCycleLedger = ledgerUpdate.ledger,
            counters = s.counters.copy(
                installAdmissionCount = s.counters.installAdmissionCount + 1L,
                nativeUploadMax = 1
            )
        )
        commands += NtkFullScenePreparationCommand.InstallPrepared(
            identity,
            ready.payloadToken,
            checkNotNull(ready.tileProof),
            s.geometryBindProof?.surfaceToken
        )
        return s
    }

    private fun canQuiesce(s: NtkFullScenePreparationSnapshot): Boolean {
        val geometry = s.geometry ?: return false
        return s.phase == NtkFullScenePreparationPhase.SURFACE_BOUND &&
            s.pages.all { it.state == NtkPreparationPageState.COMPLETE && it.artifact != null } &&
            s.tileRecords.size == geometry.tileCount &&
            s.tileRecords.values.all { it.state == NtkPreparationTileState.RESIDENT } &&
            s.activeDecoderTasks.isEmpty() && s.outputCreditsUsed == 0 &&
            s.bodyLeaseLedger.activeCount == 0 && s.uploadInFlight == null &&
            s.sourceDrainProof?.isExact == true &&
            s.accounting == NtkFullScenePreparationAccounting(
                gpuSceneResidentBytes = geometry.totalRgbaBytes
            )
    }

    private fun validMetadata(
        config: NtkFullScenePreparationConfig,
        metadata: NtkSourceMetadata
    ): Boolean = metadata.pageIndex in 0 until config.manifestSeal.pageCount &&
        metadata.manifestRevision == config.manifestSeal.revision &&
        metadata.manifestDigest == config.manifestSeal.digestSha256 &&
        metadata.canonicalAsset ==
            config.manifestSeal.normalizedCanonicalAssets[metadata.pageIndex] &&
        metadata.isProductionAuthoritative

    private fun stableIdentity(key: NtkStripTileKey): Long =
        ((key.pageIndex.toLong() + 1L) shl 32) or (key.slotIndex.toLong() + 1L)

    private fun tileRecordComparator(): Comparator<NtkPreparationTileRecord> =
        compareBy<NtkPreparationTileRecord> { it.plan.key.pageIndex }
            .thenBy { it.plan.key.slotIndex }

    private fun preparedInventoryDigest(records: List<NtkPreparationTileRecord>): String =
        NtkStripDigests.sha256Tokens(buildList {
            add("ntk-native-prepared-inventory-v1")
            records.forEach { record ->
                add(record.plan.key.pageIndex.toString())
                add(record.plan.key.slotIndex.toString())
                add(checkNotNull(record.tileProof).tileProofDigest)
                add(record.plan.rgbaBytes.toString())
            }
        })

    private fun assertInvariants(s: NtkFullScenePreparationSnapshot) {
        check(s.outputCreditsUsed in 0..3)
        check(s.activeDecoderTasks.size <= 3)
        check(s.bodyLeaseLedger.activeCount <= 3)
        check(s.uploadInFlight == null || s.tileRecords.values.count {
            it.state == NtkPreparationTileState.UPLOADING
        } == 1)
        check(s.accounting.cpuReservedBytes + s.accounting.cpuDecodedBytes <=
            s.config.cpuPolicy.hardCapBytes)
        check(s.bodyLeaseLedger.totalTaskReferences == s.activeDecoderTasks.size)
        check(s.admissionLedger.admissions.size == s.counters.admissionCount.toInt())
        check(s.resourceCycleLedger.admissionCount == s.admissionLedger.admissions.size)
        check(s.outputCreditsUsed == s.tileRecords.values.count {
            it.state in setOf(
                NtkPreparationTileState.ADMITTED,
                NtkPreparationTileState.LEASED,
                NtkPreparationTileState.DECODING,
                NtkPreparationTileState.CPU_READY,
                NtkPreparationTileState.UPLOADING
            )
        })
    }

    private fun fail(
        s: NtkFullScenePreparationSnapshot,
        reason: String
    ): NtkFullScenePreparationTransition = NtkFullScenePreparationTransition(
        failSnapshot(s, reason),
        listOf(NtkFullScenePreparationCommand.ReleasePreparationAuthority(reason))
    )

    private fun failSnapshot(
        s: NtkFullScenePreparationSnapshot,
        reason: String
    ): NtkFullScenePreparationSnapshot = s.copy(
        phase = NtkFullScenePreparationPhase.FAILED,
        failureReason = reason.lowercase(Locale.ROOT)
    )
}
