package ml.melun.mangaview.reader

import java.io.Closeable
import java.io.File

/**
 * Episode-scoped production facade for the full-scene preparation protocol.
 *
 * All source, lease, decode, GPU preparation, geometry bind, drain, and seal decisions live in
 * [NtkFullScenePreparationRunner] and [NtkFullScenePreparationMachine]. This class deliberately
 * contains no compatibility residency reducer, batch dispatcher, retry policy, or second planner.
 */
internal class NtkEpisodeStripPipeline(
    val authority: Long,
    private val preparationGeneration: Long,
    canonicalAssets: List<String>,
    private val sourceTransport: SourceTransport,
    private val controllerPort: ControllerPort,
    private val listener: Listener,
    private val cpuTransientPolicyBytes: Long,
    private val manifestSeal: NtkEpisodeManifestSeal,
    private val initialPageIndex: Int = 0,
    private val initialPageOffsetPx: Int = 0,
    private val fullSceneExecutionBootstrap: NtkFullSceneExecutionBootstrap
) : Closeable {
    data class SourceHandle(
        val pageIndex: Int,
        val canonicalAsset: String,
        val file: File,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val metadata: NtkSourceMetadata? = null,
        val encodedOriginalProof: NtkEncodedOriginalProof? = null,
        val release: () -> Unit = {}
    )

    data class SourceRequest(
        val episode: NtkEpisodeToken,
        val pageIndex: Int,
        val canonicalAsset: String,
        val priority: Int,
        val manifestRevision: Long = 0L,
        val manifestDigest: String = "",
        val admissionId: Long = 0L
    ) {
        init {
            require(pageIndex >= 0)
            require(admissionId >= 0L)
        }
    }

    interface SourceTransport {
        fun register(request: SourceRequest, completion: (Result<SourceHandle>) -> Unit)

        fun registerWithEvents(
            request: SourceRequest,
            event: (SourceEvent) -> Unit,
            completion: (Result<SourceHandle>) -> Unit
        ) = register(request, completion)

        fun retire(episode: NtkEpisodeToken) {}
    }

    interface DetachedPreparationPort {
        val engineGeneration: Long

        fun openDetachedPreparation(
            authority: Long,
            preparationGeneration: Long,
            manifestSeal: NtkEpisodeManifestSeal,
            completion: (NtkNativePreparationToken?) -> Unit
        )

        fun installDetachedPrepared(
            install: NtkPreparedTileInstall,
            completion: (NtkPreparedTileResidentAck?) -> Unit
        ): Boolean

        fun closePreparationAdmissions(token: NtkNativePreparationToken): Boolean
    }

    interface ControllerPort {
        fun installSurfacePrepared(
            install: NtkPreparedTileInstall,
            surfaceToken: NtkSurfacePreparationToken,
            completion: (NtkPreparedTileResidentAck?) -> Unit
        ): Boolean

        fun adoptDetachedPreparation(
            request: NtkGeometryBindRequest,
            geometry: NtkStripGeometry,
            surface: NtkPublishedSurfaceIdentity,
            completion: (NtkGeometryBindProof?) -> Unit
        )

        fun currentToken(): NtkNativeAuthorityToken?

        fun stage(
            authority: Long,
            corridorStartPx: Long,
            corridorEndPx: Long,
            stageNonce: Long,
            manifestRevision: Long,
            manifestDigest: String,
            geometryDigest: String,
            completion: (NtkStageProof?) -> Unit
        )

        fun activate(authority: Long, stageNonce: Long): Boolean
        fun disarm(authority: Long): Boolean
        fun releaseAuthority(
            request: NtkAuthorityReleaseRequest,
            completion: (NtkNativeAuthorityReleaseAck) -> Unit
        ): Boolean
    }

    interface Listener {
        fun onStageReady(pipeline: NtkEpisodeStripPipeline, geometry: NtkStripGeometry)
        fun onStageReady(
            pipeline: NtkEpisodeStripPipeline,
            geometry: NtkStripGeometry,
            proof: NtkStageProof
        ) = onStageReady(pipeline, geometry)

        fun onProofSnapshot(pipeline: NtkEpisodeStripPipeline, proof: NtkEpisodeProofSnapshot) {}
        fun onContractState(pipeline: NtkEpisodeStripPipeline, state: NtkRunwayContractState) {}
        fun onFailed(pipeline: NtkEpisodeStripPipeline, error: Throwable)
        fun onTerminalCleanupComplete(pipeline: NtkEpisodeStripPipeline) {}
    }

    data class Snapshot(
        val sourceDemandRegistered: Int,
        val sourceReady: Int,
        val decodedTiles: Int,
        val residentTiles: Int,
        val residentBytes: Long,
        val residentContinuousEndPx: Long,
        val mandatoryEndPx: Long,
        val contractState: NtkRunwayContractState,
        val stageReady: Boolean,
        val retired: Boolean,
        val demandEpoch: Long = 0L,
        val backpressured: Boolean = false,
        val cpuChargedBytes: Long = 0L,
        val retirePendingBytes: Long = 0L,
        val measuredServiceP99Millis: Long = NtkRollingResidencyConstants.MIN_SERVICE_HORIZON_MS,
        val urgentServiceSamples: Int = 0
    )

    private val runner: NtkFullScenePreparationRunner by lazy {
        NtkFullScenePreparationRunner(
            owner = this,
            preparationGeneration = preparationGeneration,
            sourceTransport = sourceTransport,
            controllerPort = controllerPort,
            listener = listener,
            cpuTransientPolicyBytes = cpuTransientPolicyBytes,
            manifestSeal = manifestSeal,
            initialPageIndex = initialPageIndex,
            initialPageOffsetPx = initialPageOffsetPx,
            executionBootstrap = fullSceneExecutionBootstrap
        )
    }

    init {
        require(authority > 0L && preparationGeneration > 0L)
        require(cpuTransientPolicyBytes > 0L)
        require(initialPageIndex in canonicalAssets.indices)
        require(initialPageOffsetPx >= 0)
        require(manifestSeal.isStructurallyComplete) { "A complete exact manifest is required" }
        require(manifestSeal.pageCount == canonicalAssets.size)
        require(
            manifestSeal.normalizedCanonicalAssets ==
                canonicalAssets.map(NtkStripDigests::canonicalAsset)
        ) { "Constructor assets must exactly match the sealed manifest" }
        require(sourceTransport is NtkStrictSourceTransport) {
            "Exact manifest authority requires an episode-scoped strict source transport"
        }
    }

    fun start() = runner.start()
    fun onDetachedPreparationAvailable(port: DetachedPreparationPort) =
        runner.onDetachedPreparationAvailable(port)
    fun onGeometrySeed(width: Int, height: Int, revision: Long) =
        runner.onGeometrySeed(width, height, revision)
    fun onSurfaceAvailable(identity: NtkPublishedSurfaceIdentity) =
        runner.onSurfaceAvailable(identity)
    fun onSurfaceRevoked(
        identity: NtkPublishedSurfaceIdentity,
        crossedStageBoundary: Boolean
    ) = runner.onSurfaceRevoked(identity, crossedStageBoundary)
    fun onSurfaceLost(
        identity: NtkPublishedSurfaceIdentity,
        crossedStageBoundary: Boolean,
        resourcesPreserved: Boolean = true
    ) = runner.onSurfaceLost(identity, crossedStageBoundary, resourcesPreserved)

    fun onPreSubmitViewportGap(count: Long) = runner.onPreSubmitViewportGap(count)
    fun onViewportSample(sample: NtkViewportSample) = runner.onViewportSample(sample)
    fun activate() = activate {}
    fun activate(completion: (Boolean) -> Unit) = runner.activate(completion)
    fun stageProofSnapshot(completion: (NtkStageProof?) -> Unit) =
        runner.stageProofSnapshot(completion)
    fun terminalProofSnapshot(completion: (NtkEpisodeProofSnapshot?) -> Unit) =
        runner.terminalProofSnapshot(completion)
    fun terminalProofSnapshotBlocking(timeoutMillis: Long): NtkEpisodeProofSnapshot? =
        runner.terminalProofSnapshotBlocking(timeoutMillis)
    fun snapshot(completion: (Snapshot) -> Unit) = runner.snapshot(completion)
    override fun close() = retire("close")
    fun retire(reason: String) = runner.retire(reason)
}

class NtkTileContractViolationException(message: String) : IllegalStateException(message)
