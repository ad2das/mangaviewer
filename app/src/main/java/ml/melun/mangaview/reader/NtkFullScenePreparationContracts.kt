package ml.melun.mangaview.reader

import android.graphics.Bitmap

internal enum class NtkFullScenePreparationPhase {
    OPEN,
    GEOMETRY_READY,
    SURFACE_BIND_QUEUED,
    SURFACE_BOUND,
    QUIESCING,
    SEALED,
    FAILED,
    RETIRED
}

internal enum class NtkPreparationPageState {
    EMPTY,
    METADATA_READY,
    BODY_PUBLISHED,
    LEASE_OPENING,
    LEASED,
    COMPLETE,
    FAILED
}

internal enum class NtkPreparationTileState {
    PLANNED,
    ADMITTED,
    LEASED,
    DECODING,
    CPU_READY,
    UPLOADING,
    NATIVE_PREPARED,
    RESIDENT,
    FAILED
}

data class NtkCpuTransientPolicy(
    val policyBytes: Long,
    val usableBytes: Long,
    val hardCapBytes: Long
) {
    init {
        require(policyBytes > 0L)
        require(usableBytes == Math.multiplyExact(policyBytes, 9L) / 10L)
        require(hardCapBytes == minOf(32L * 1024L * 1024L, usableBytes / 4L))
        require(hardCapBytes > 0L)
    }

    companion object {
        fun create(policyBytes: Long): NtkCpuTransientPolicy {
            require(policyBytes > 0L)
            val usable = Math.multiplyExact(policyBytes, 9L) / 10L
            return NtkCpuTransientPolicy(
                policyBytes,
                usable,
                minOf(32L * 1024L * 1024L, usable / 4L)
            )
        }
    }
}

data class NtkPreparationAdmissionIdentity(
    val authority: Long,
    val key: NtkStripTileKey,
    val admissionId: Long,
    val pageArtifactDigest: String
) {
    init {
        require(authority > 0L && key.episode.value == authority)
        require(admissionId > 0L)
        require(NtkStripDigests.isSha256(pageArtifactDigest))
    }
}

data class NtkDetachedInstallIdentity(
    val admission: NtkPreparationAdmissionIdentity,
    val preparationGeneration: Long,
    val resourceRevision: Long,
    val installLease: Long
) {
    init {
        require(preparationGeneration > 0L)
        require(resourceRevision == 1L)
        require(installLease > 0L)
    }
}

data class NtkNativeDetachedPreparationToken(
    val engineGeneration: Long,
    val preparationGeneration: Long,
    val authority: Long,
    val manifestRevision: Long,
    val manifestDigest: String,
    val tokenNonce: Long,
    val openedAtNanos: Long
) {
    init {
        require(engineGeneration > 0L && preparationGeneration > 0L)
        require(authority > 0L)
        require(manifestRevision >= 0L && NtkStripDigests.isSha256(manifestDigest))
        require(tokenNonce > 0L && openedAtNanos > 0L)
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-native-detached-preparation-token-v1",
            engineGeneration.toString(),
            preparationGeneration.toString(),
            authority.toString(),
            manifestRevision.toString(),
            manifestDigest,
            tokenNonce.toString()
        )
}

internal typealias NtkNativePreparationToken = NtkNativeDetachedPreparationToken
internal typealias NtkNativeInstallIdentity = NtkDetachedInstallIdentity

data class NtkSurfacePreparationToken(
    val detached: NtkNativeDetachedPreparationToken,
    val demandGeneration: Long,
    val attachGeneration: Long,
    val surfaceEpoch: Long,
    val geometryRevision: Long,
    val width: Int,
    val height: Int,
    val adoptedAtNanos: Long
) {
    init {
        require(demandGeneration > 0L && attachGeneration > 0L && surfaceEpoch > 0L)
        require(geometryRevision > 0L)
        require(width > 0 && height > 0)
        require(adoptedAtNanos > 0L)
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-surface-preparation-token-v1",
            detached.identityDigest,
            demandGeneration.toString(),
            attachGeneration.toString(),
            surfaceEpoch.toString(),
            geometryRevision.toString(),
            width.toString(),
            height.toString()
        )
}

data class NtkPreparedOriginalTileProof(
    val pageArtifactDigest: String,
    val tilePlanDigest: String,
    val sourceKey: NtkStrictSourceKey,
    val responseIdentityDigest: String,
    val metadataBindingDigest: String,
    val encodedSha256: String,
    val encodedLength: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceTop: Int,
    val sourceBottom: Int,
    val rgbaBytes: Long,
    val bitmapConfig: Bitmap.Config,
    val sampleSize: Int,
    val tileProofDigest: String
) {
    init {
        require(NtkStripDigests.isSha256(pageArtifactDigest))
        require(NtkStripDigests.isSha256(tilePlanDigest))
        require(NtkStripDigests.isSha256(responseIdentityDigest))
        require(NtkStripDigests.isSha256(metadataBindingDigest))
        require(NtkStripDigests.isSha256(encodedSha256) && encodedLength > 0L)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(sourceTop >= 0 && sourceBottom in (sourceTop + 1)..sourceHeight)
        require(rgbaBytes == Math.multiplyExact(
            Math.multiplyExact(sourceWidth.toLong(), (sourceBottom - sourceTop).toLong()),
            4L
        ))
        require(bitmapConfig == Bitmap.Config.ARGB_8888 && sampleSize == 1)
        require(tileProofDigest == computedTileProofDigest)
    }

    val computedTileProofDigest: String
        get() = computeDigest(
            pageArtifactDigest,
            tilePlanDigest,
            sourceKey,
            responseIdentityDigest,
            metadataBindingDigest,
            encodedSha256,
            encodedLength,
            sourceWidth,
            sourceHeight,
            sourceTop,
            sourceBottom,
            rgbaBytes,
            bitmapConfig,
            sampleSize
        )

    companion object {
        fun create(
            artifact: NtkPreGeometryPageArtifact,
            tile: NtkPreGeometryTilePlan
        ): NtkPreparedOriginalTileProof {
            val plan = artifact.plan
            require(tile in plan.tiles)
            return NtkPreparedOriginalTileProof(
                pageArtifactDigest = artifact.artifactDigest,
                tilePlanDigest = tile.tilePlanDigest,
                sourceKey = plan.sourceKey,
                responseIdentityDigest = artifact.responseIdentityDigest,
                metadataBindingDigest = plan.metadataBindingDigest,
                encodedSha256 = artifact.encodedSha256,
                encodedLength = artifact.encodedLength,
                sourceWidth = plan.sourceWidth,
                sourceHeight = plan.sourceHeight,
                sourceTop = tile.sourceTop,
                sourceBottom = tile.sourceBottom,
                rgbaBytes = tile.rgbaBytes,
                bitmapConfig = Bitmap.Config.ARGB_8888,
                sampleSize = 1,
                tileProofDigest = computeDigest(
                    artifact.artifactDigest,
                    tile.tilePlanDigest,
                    plan.sourceKey,
                    artifact.responseIdentityDigest,
                    plan.metadataBindingDigest,
                    artifact.encodedSha256,
                    artifact.encodedLength,
                    plan.sourceWidth,
                    plan.sourceHeight,
                    tile.sourceTop,
                    tile.sourceBottom,
                    tile.rgbaBytes,
                    Bitmap.Config.ARGB_8888,
                    1
                )
            )
        }

        fun rootDigest(proofs: List<NtkPreparedOriginalTileProof>): String =
            NtkStripDigests.sha256Tokens(buildList {
                add("ntk-prepared-original-tile-root-v1")
                proofs.forEach { add(it.tileProofDigest) }
            })

        private fun computeDigest(
            pageArtifactDigest: String,
            tilePlanDigest: String,
            sourceKey: NtkStrictSourceKey,
            responseIdentityDigest: String,
            metadataBindingDigest: String,
            encodedSha256: String,
            encodedLength: Long,
            sourceWidth: Int,
            sourceHeight: Int,
            sourceTop: Int,
            sourceBottom: Int,
            rgbaBytes: Long,
            bitmapConfig: Bitmap.Config,
            sampleSize: Int
        ): String = NtkStripDigests.sha256Tokens(
            "ntk-prepared-original-tile-proof-v1",
            pageArtifactDigest,
            tilePlanDigest,
            sourceKey.manifestDigest,
            sourceKey.pageIndex.toString(),
            sourceKey.canonicalAssetDigest,
            responseIdentityDigest,
            metadataBindingDigest,
            encodedSha256,
            encodedLength.toString(),
            sourceWidth.toString(),
            sourceHeight.toString(),
            sourceTop.toString(),
            sourceBottom.toString(),
            rgbaBytes.toString(),
            bitmapConfig.toString(),
            sampleSize.toString()
        )
    }
}

data class NtkPreparedTileInstall(
    val token: NtkNativeDetachedPreparationToken,
    val identity: NtkDetachedInstallIdentity,
    val tileProof: NtkPreparedOriginalTileProof,
    val rgbaBytes: Long,
    val tile: ReaderTile
) {
    init {
        require(token.authority == identity.admission.authority)
        require(token.preparationGeneration == identity.preparationGeneration)
        require(tileProof.pageArtifactDigest == identity.admission.pageArtifactDigest)
        require(rgbaBytes == tileProof.rgbaBytes)
        require(tile.sourceTop == tileProof.sourceTop && tile.sourceBottom == tileProof.sourceBottom)
        require(tile.sourceWidth == tileProof.sourceWidth && tile.sourceHeight == tileProof.sourceHeight)
        require(tile.bitmap.config == Bitmap.Config.ARGB_8888)
        require(!tile.bitmap.isMutable && !tile.bitmap.isRecycled)
        require(tile.bitmap.width == tileProof.sourceWidth)
        require(tile.bitmap.height == tileProof.sourceBottom - tileProof.sourceTop)
    }
}

data class NtkDetachedPreparedTileAck(
    val identity: NtkDetachedInstallIdentity,
    val tileProofDigest: String,
    val residentInventoryDigest: String,
    val preGeometryPrepared: Boolean,
    val resourceCompletionNanos: Long
) {
    init {
        require(NtkStripDigests.isSha256(tileProofDigest))
        require(NtkStripDigests.isSha256(residentInventoryDigest))
        require(resourceCompletionNanos > 0L)
    }
}

internal typealias NtkPreparedTileResidentAck = NtkDetachedPreparedTileAck

data class NtkGeometryBindRequest(
    val token: NtkNativeDetachedPreparationToken,
    val requestId: Long,
    val geometryDigest: String,
    val preGeometryRootDigest: String,
    val preparedTileKeys: List<NtkStripTileKey>,
    val preparedInventoryDigest: String,
    val requestedAtNanos: Long
) {
    init {
        require(requestId > 0L)
        require(NtkStripDigests.isSha256(geometryDigest))
        require(NtkStripDigests.isSha256(preGeometryRootDigest))
        require(preparedTileKeys.distinct().size == preparedTileKeys.size)
        require(NtkStripDigests.isSha256(preparedInventoryDigest))
        require(requestedAtNanos > 0L)
    }
}

data class NtkGeometryBindProof(
    val token: NtkNativeDetachedPreparationToken,
    val surfaceToken: NtkSurfacePreparationToken,
    val requestId: Long,
    val geometryDigest: String,
    val preGeometryRootDigest: String,
    val adoptedPreparedTileCount: Int,
    val missingGeometrySlotCount: Int,
    val preparedInventoryDigest: String,
    val residentInventoryDigest: String,
    val geometryBindCompletionNanos: Long,
    val lastResourceCompletionNanos: Long
) {
    init {
        require(surfaceToken.detached == token)
        require(requestId > 0L)
        require(NtkStripDigests.isSha256(geometryDigest))
        require(NtkStripDigests.isSha256(preGeometryRootDigest))
        require(adoptedPreparedTileCount >= 0 && missingGeometrySlotCount >= 0)
        require(NtkStripDigests.isSha256(preparedInventoryDigest))
        require(NtkStripDigests.isSha256(residentInventoryDigest))
        require(geometryBindCompletionNanos > 0L)
        require(lastResourceCompletionNanos in 0L..geometryBindCompletionNanos)
    }
}

data class NtkPreparedResidentTileSeal(
    val identity: NtkDetachedInstallIdentity,
    val tileProofDigest: String,
    val rgbaBytes: Long,
    val residentInventoryDigestAtAck: String
) {
    init {
        require(NtkStripDigests.isSha256(tileProofDigest))
        require(rgbaBytes > 0L)
        require(NtkStripDigests.isSha256(residentInventoryDigestAtAck))
    }
}

data class NtkFullScenePreparationAccounting(
    val cpuReservedBytes: Long = 0L,
    val cpuDecodedBytes: Long = 0L,
    val gpuUploadReservedBytes: Long = 0L,
    val gpuPreparedResidentBytes: Long = 0L,
    val gpuSceneResidentBytes: Long = 0L
) {
    init {
        require(cpuReservedBytes >= 0L && cpuDecodedBytes >= 0L)
        require(gpuUploadReservedBytes >= 0L && gpuPreparedResidentBytes >= 0L)
        require(gpuSceneResidentBytes >= 0L)
    }
}

data class NtkFullScenePreparationCounters(
    val metadataCount: Long = 0L,
    val bodyPublishedCount: Long = 0L,
    val pageArtifactCount: Long = 0L,
    val plannedTileCount: Long = 0L,
    val admissionCount: Long = 0L,
    val decodeStartedCount: Long = 0L,
    val decodeCompletedCount: Long = 0L,
    val decodeFailureCount: Long = 0L,
    val duplicateAdmissionCount: Long = 0L,
    val duplicateDecodeCount: Long = 0L,
    val retryCount: Long = 0L,
    val leaseOpenCount: Long = 0L,
    val leaseReleaseCount: Long = 0L,
    val installAdmissionCount: Long = 0L,
    val installAckCount: Long = 0L,
    val duplicateInstallCount: Long = 0L,
    val geometryBindCount: Long = 0L,
    val actualDecodeActiveMax: Int = 0,
    val actualNormalPriorityTaskStarts: Long = 0L,
    val actualBackgroundPriorityTaskStarts: Long = 0L,
    val threeWideEntryCount: Long = 0L,
    val threeWideOverlapNanos: Long = 0L,
    val nativeUploadMax: Int = 0
) {
    init {
        val longs = listOf(
            metadataCount, bodyPublishedCount, pageArtifactCount, plannedTileCount,
            admissionCount, decodeStartedCount, decodeCompletedCount, decodeFailureCount,
            duplicateAdmissionCount, duplicateDecodeCount, retryCount, leaseOpenCount,
            leaseReleaseCount, installAdmissionCount, installAckCount, duplicateInstallCount,
            geometryBindCount, actualNormalPriorityTaskStarts,
            actualBackgroundPriorityTaskStarts, threeWideEntryCount, threeWideOverlapNanos
        )
        require(longs.all { it >= 0L })
        require(actualDecodeActiveMax in 0..3 && nativeUploadMax in 0..1)
    }
}

data class NtkSourceDrainProof(
    val manifestDigest: String,
    val pageCount: Int,
    val bodyPublishedCount: Int,
    val activePrimaryCalls: Int,
    val unleasedSourceCalls: Long,
    val partialBodyOperations: Long,
    val activeBodyLeases: Int,
    val completedAtNanos: Long
) {
    val isExact: Boolean
        get() = NtkStripDigests.isSha256(manifestDigest) && pageCount > 0 &&
            bodyPublishedCount == pageCount && activePrimaryCalls == 0 &&
            unleasedSourceCalls == 0L && partialBodyOperations == 0L &&
            activeBodyLeases == 0 && completedAtNanos > 0L
}

data class NtkPreparationDrainProof(
    val source: NtkSourceDrainProof,
    val decoderAccepting: Boolean,
    val decoderDrained: Boolean,
    val leaseDispatcherAccepting: Boolean,
    val leaseDispatcherDrained: Boolean,
    val nativeResourceAdmissionsClosed: Boolean,
    val nativeResourceQueueDrained: Boolean,
    val callbacksPending: Int,
    val actualDecodeActiveMax: Int,
    val actualNormalPriorityTaskStarts: Long,
    val actualBackgroundPriorityTaskStarts: Long,
    val threeWideEntryCount: Long,
    val threeWideOverlapNanos: Long,
    val completedAtNanos: Long
) {
    val isExact: Boolean
        get() = source.isExact && !decoderAccepting && decoderDrained &&
            !leaseDispatcherAccepting && leaseDispatcherDrained &&
            nativeResourceAdmissionsClosed && nativeResourceQueueDrained &&
            callbacksPending == 0 && actualDecodeActiveMax == 3 &&
            actualNormalPriorityTaskStarts > 0L && actualBackgroundPriorityTaskStarts == 0L &&
            threeWideEntryCount > 0L && threeWideOverlapNanos > 0L && completedAtNanos > 0L
}

data class NtkPreparedFullSceneSeal(
    val authority: Long,
    val surfaceEpoch: Long,
    val manifestRevision: Long,
    val manifestDigest: String,
    val geometryDigest: String,
    val preGeometryRootDigest: String,
    val pageArtifactRootDigest: String,
    val tileProofRootDigest: String,
    val nativeInventoryDigest: String,
    val pageCount: Int,
    val tileCount: Int,
    val totalRgbaBytes: Long,
    val residentTiles: List<NtkPreparedResidentTileSeal>,
    val resourceCycleLedger: NtkResourceCycleLedger,
    val counters: NtkFullScenePreparationCounters,
    val nativeProof: NtkGeometryBindProof,
    val sealedAtNanos: Long
) {
    init {
        require(authority > 0L && surfaceEpoch > 0L && manifestRevision >= 0L)
        require(NtkStripDigests.isSha256(manifestDigest))
        require(NtkStripDigests.isSha256(geometryDigest))
        require(NtkStripDigests.isSha256(preGeometryRootDigest))
        require(NtkStripDigests.isSha256(pageArtifactRootDigest))
        require(NtkStripDigests.isSha256(tileProofRootDigest))
        require(NtkStripDigests.isSha256(nativeInventoryDigest))
        require(pageCount > 0 && tileCount > 0 && residentTiles.size == tileCount)
        require(totalRgbaBytes > 0L && residentTiles.sumOf { it.rgbaBytes } == totalRgbaBytes)
        require(resourceCycleLedger.admissionCount == tileCount)
        require(resourceCycleLedger.releaseCount == 0)
        require(resourceCycleLedger.isValid)
        require(counters.admissionCount == tileCount.toLong())
        require(counters.decodeCompletedCount == tileCount.toLong())
        require(counters.installAckCount == tileCount.toLong())
        require(counters.actualDecodeActiveMax == 3)
        require(counters.actualBackgroundPriorityTaskStarts == 0L)
        require(nativeProof.geometryDigest == geometryDigest)
        require(nativeProof.preGeometryRootDigest == preGeometryRootDigest)
        require(sealedAtNanos > 0L)
    }

    val sealDigest: String
        get() = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-prepared-full-scene-seal-v1")
            add(authority.toString())
            add(surfaceEpoch.toString())
            add(manifestRevision.toString())
            add(manifestDigest)
            add(geometryDigest)
            add(preGeometryRootDigest)
            add(pageArtifactRootDigest)
            add(tileProofRootDigest)
            add(nativeInventoryDigest)
            add(pageCount.toString())
            add(tileCount.toString())
            add(totalRgbaBytes.toString())
            residentTiles.forEach { tile ->
                add(tile.identity.admission.key.pageIndex.toString())
                add(tile.identity.admission.key.slotIndex.toString())
                add(tile.identity.admission.admissionId.toString())
                add(tile.identity.installLease.toString())
                add(tile.tileProofDigest)
                add(tile.rgbaBytes.toString())
            }
            add(resourceCycleLedger.digest)
            add(counters.toString())
        })
}
