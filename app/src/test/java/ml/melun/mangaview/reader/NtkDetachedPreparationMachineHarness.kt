package ml.melun.mangaview.reader

import java.util.concurrent.atomic.AtomicLong

internal class NtkDetachedPreparationMachineHarness(
    val pageCount: Int = 1,
    sourceWidth: Int = 100,
    sourceHeight: Int = 100
) {
    val episode = NtkEpisodeToken(9_001L)
    val preparationGeneration = 17L
    val engineGeneration = 23L
    val assets = (0 until pageCount).map {
        "https://images.example/detached-preparation-${it + 1}.jpg"
    }
    val manifest = NtkEpisodeManifestSeal.create(
        "/webtoon/detached-preparation/fixture",
        71L,
        assets
    )
    val token = NtkNativePreparationToken(
        engineGeneration = engineGeneration,
        preparationGeneration = preparationGeneration,
        authority = episode.value,
        manifestRevision = manifest.revision,
        manifestDigest = manifest.digestSha256,
        tokenNonce = 29L,
        openedAtNanos = 31L
    )
    val geometrySeed = NtkPreparationGeometrySeed(
        viewportWidthPx = sourceWidth,
        viewportHeightPx = 2_340,
        geometryRevision = 37L
    )
    val surface = NtkPublishedSurfaceIdentity(
        engineGeneration = engineGeneration,
        attachGeneration = 41L,
        surfaceEpoch = 43L,
        geometryRevision = geometrySeed.geometryRevision,
        width = geometrySeed.viewportWidthPx,
        height = geometrySeed.viewportHeightPx,
        demandGeneration = 47L
    )
    val metadata: List<NtkSourceMetadata>
    val descriptors: List<NtkStrictBodyDescriptor>

    private val clock = AtomicLong(1_000L)
    private val machine = NtkFullScenePreparationMachine { clock.incrementAndGet() }
    var snapshot = machine.initial(
        NtkFullScenePreparationConfig(
            episode = episode,
            preparationGeneration = preparationGeneration,
            manifestSeal = manifest,
            initialPageIndex = 0,
            cpuPolicy = NtkCpuTransientPolicy.create(128L * 1024L * 1024L)
        )
    )
        private set

    init {
        metadata = assets.mapIndexed { pageIndex, asset ->
            val encodedLength = 10_000L + pageIndex
            NtkSourceMetadata.createStrict(
                manifestRevision = manifest.revision,
                manifestDigest = manifest.digestSha256,
                pageIndex = pageIndex,
                canonicalAsset = asset,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                authority = NtkSourceMetadataAuthority.createStrict(
                    acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
                    responseIdentityDigest = digest("response-$pageIndex"),
                    byteWitnessSha256 = digest("witness-$pageIndex"),
                    byteWitnessLength = 128L,
                    encodedLength = encodedLength,
                    strongValidatorDigest = digest("validator-$pageIndex"),
                    imageFormat = "jpeg"
                )
            )
        }
        descriptors = metadata.mapIndexed { pageIndex, value ->
            NtkStrictBodyDescriptor(
                descriptorId = pageIndex + 1L,
                sourceKey = value.strictSourceKey,
                metadata = value,
                proof = NtkEncodedOriginalProof.createStrict(
                    value,
                    digest("encoded-$pageIndex"),
                    value.authority.encodedLength
                ),
                openLease = { error("Pure reducer test does not open a physical lease") }
            )
        }
    }

    fun send(event: NtkFullScenePreparationEvent): List<NtkFullScenePreparationCommand> {
        val transition = machine.reduce(snapshot, event)
        snapshot = transition.snapshot
        return transition.commands
    }

    fun openDetached(): List<NtkFullScenePreparationCommand> =
        send(NtkFullScenePreparationEvent.DetachedPreparationOpened(token))

    fun seedGeometry(): List<NtkFullScenePreparationCommand> =
        send(NtkFullScenePreparationEvent.GeometrySeedAvailable(geometrySeed))

    fun publishSurface(): List<NtkFullScenePreparationCommand> =
        send(NtkFullScenePreparationEvent.SurfacePublished(surface))

    fun publishMetadata(pageIndex: Int): List<NtkFullScenePreparationCommand> =
        send(NtkFullScenePreparationEvent.MetadataReady(metadata[pageIndex]))

    fun publishBody(pageIndex: Int): List<NtkFullScenePreparationCommand> =
        send(NtkFullScenePreparationEvent.BodyPublished(descriptors[pageIndex]))

    fun openLease(
        command: NtkFullScenePreparationCommand.OpenBodyLease,
        leaseId: Long = 101L + command.pageIndex
    ): List<NtkFullScenePreparationCommand> = send(
        NtkFullScenePreparationEvent.BodyLeaseOpened(
            command.pageIndex,
            command.requestId,
            leaseId
        )
    )

    fun startDecode(
        command: NtkFullScenePreparationCommand.StartDecode,
        actualActiveTasks: Int = 1
    ): List<NtkFullScenePreparationCommand> = send(
        NtkFullScenePreparationEvent.DecodeStarted(
            command.admission,
            NtkDecodePriority.NORMAL,
            workerThreadId = 201L + command.admission.key.pageIndex,
            actualActiveTasks = actualActiveTasks
        )
    )

    fun completeDecode(
        command: NtkFullScenePreparationCommand.StartDecode
    ): List<NtkFullScenePreparationCommand> = send(
        NtkFullScenePreparationEvent.DecodeCompleted(
            command.admission,
            command.expectedProof,
            payloadToken = command.admission.admissionId
        )
    )

    fun acknowledgeInstall(
        command: NtkFullScenePreparationCommand.InstallPrepared
    ): List<NtkFullScenePreparationCommand> = send(
        NtkFullScenePreparationEvent.NativeInstallAck(
            NtkPreparedTileResidentAck(
                identity = command.identity,
                tileProofDigest = command.tileProof.tileProofDigest,
                residentInventoryDigest = digest(
                    "resident-${command.identity.admission.key.pageIndex}-" +
                        command.identity.admission.key.slotIndex
                ),
                preGeometryPrepared = command.surfaceToken == null,
                resourceCompletionNanos = clock.addAndGet(10L)
            )
        )
    )

    fun acknowledgeAdoption(
        command: NtkFullScenePreparationCommand.AdoptDetachedPreparation
    ): List<NtkFullScenePreparationCommand> {
        val request = command.request
        val geometry = checkNotNull(snapshot.geometry)
        val completionNanos = clock.addAndGet(10L)
        return send(
            NtkFullScenePreparationEvent.SurfacePreparationBound(
                NtkGeometryBindProof(
                    token = request.token,
                    surfaceToken = NtkSurfacePreparationToken(
                        detached = request.token,
                        demandGeneration = surface.demandGeneration,
                        attachGeneration = surface.attachGeneration,
                        surfaceEpoch = surface.surfaceEpoch,
                        geometryRevision = surface.geometryRevision,
                        width = surface.width,
                        height = surface.height,
                        adoptedAtNanos = completionNanos
                    ),
                    requestId = request.requestId,
                    geometryDigest = request.geometryDigest,
                    preGeometryRootDigest = request.preGeometryRootDigest,
                    adoptedPreparedTileCount = request.preparedTileKeys.size,
                    missingGeometrySlotCount = geometry.tileCount -
                        request.preparedTileKeys.size,
                    preparedInventoryDigest = request.preparedInventoryDigest,
                    residentInventoryDigest = digest(
                        "surface-resident-${request.preparedTileKeys.size}"
                    ),
                    geometryBindCompletionNanos = completionNanos,
                    lastResourceCompletionNanos = snapshot.lastResourceCompletionNanos
                )
            )
        )
    }

    fun releaseLease(
        command: NtkFullScenePreparationCommand.ReleaseBodyLease
    ): List<NtkFullScenePreparationCommand> = send(
        NtkFullScenePreparationEvent.BodyLeaseReleased(
            command.pageIndex,
            command.leaseId
        )
    )

    fun sourceProof(): NtkSourceDrainProof = NtkSourceDrainProof(
        manifestDigest = manifest.digestSha256,
        pageCount = pageCount,
        bodyPublishedCount = pageCount,
        activePrimaryCalls = 0,
        unleasedSourceCalls = 0L,
        partialBodyOperations = 0L,
        activeBodyLeases = 0,
        completedAtNanos = clock.addAndGet(10L)
    )

    fun finishDrain(): List<NtkFullScenePreparationCommand> {
        val source = checkNotNull(snapshot.sourceDrainProof)
        return send(
            NtkFullScenePreparationEvent.DrainCompleted(
                NtkPreparationDrainProof(
                    source = source,
                    decoderAccepting = false,
                    decoderDrained = true,
                    leaseDispatcherAccepting = false,
                    leaseDispatcherDrained = true,
                    nativeResourceAdmissionsClosed = true,
                    nativeResourceQueueDrained = true,
                    callbacksPending = 0,
                    actualDecodeActiveMax = 3,
                    actualNormalPriorityTaskStarts = snapshot.counters.decodeStartedCount,
                    actualBackgroundPriorityTaskStarts = 0L,
                    threeWideEntryCount = 1L,
                    threeWideOverlapNanos = 1L,
                    completedAtNanos = clock.addAndGet(10L)
                )
            )
        )
    }

    companion object {
        fun digest(value: String): String = NtkStripDigests.sha256Tokens(
            "ntk-detached-preparation-machine-test-v1",
            value
        )
    }
}
