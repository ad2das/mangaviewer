package ml.melun.mangaview.reader

import android.app.Activity
import android.graphics.Bitmap
import android.view.Surface

internal const val NTK_RELEASE_REJECTED = 0
internal const val NTK_RELEASE_ACCEPTED_ASYNC = 1
internal const val NTK_RELEASE_ACKED_SYNCHRONOUSLY = 2

internal object NtkStripNativeBridge {
    init {
        System.loadLibrary("ntk_strip_renderer")
    }

    external fun nativeCreate(
        activity: Activity,
        callback: NtkStripRenderEngine,
        qualificationProfileId: String,
        engineGeneration: Long
    ): Long
    /**
     * Blocks only the dedicated lifecycle lane until the exact detached EGL/pbuffer/program
     * generation is ready or terminal. No Surface, ANativeWindow, frame id, or swap is created.
     */
    external fun nativeAwaitDetachedWarm(handle: Long): LongArray?
    external fun nativeAcquireSurfaceLease(
        surface: Surface,
        surfaceEpoch: Long
    ): Long
    external fun nativeReleaseSurfaceLease(leaseId: Long, surfaceEpoch: Long): Boolean
    external fun nativeQueueAttachLease(
        handle: Long,
        leaseId: Long,
        attachGeneration: Long,
        width: Int,
        height: Int,
        geometryRevision: Long,
        refreshPeriodNanos: Long,
        surfaceEpoch: Long
    ): Boolean
    external fun nativeAwaitAttach(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long
    ): LongArray?
    external fun nativeUpdateAttachGeometry(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long,
        width: Int,
        height: Int,
        geometryRevision: Long
    ): Boolean
    external fun nativeApplyResizeBeforePublish(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long,
        geometryRevision: Long
    ): LongArray?
    external fun nativePublishAttach(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long,
        geometryRevision: Long
    ): Boolean
    external fun nativeRequestSurfaceLoss(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long
    ): Int
    /** [viewRegistryLeaseCount, rendererOwnedLeaseCount]. */
    external fun nativeDebugSurfaceLeaseCounters(): LongArray
    /** Makes exactly the next native renderer construction fail before registration. */
    external fun nativeFailNextCreateForTesting()
    /** Fails the next callback-resolution transaction after local allocation, before registration. */
    external fun nativeFailNextCallbackResolutionForTesting()
    /** Native opaque-handle acquisition/destruction race self-test evidence. */
    external fun nativeRunHandleRegistrySelfTest(): LongArray
    /** Native engine-local release serial/terminalization self-test evidence. */
    external fun nativeRunReleaseProtocolSelfTest(): LongArray
    /** Native metadata-return -> terminal lifecycle -> dispatchable ordering evidence. */
    external fun nativeRunReleaseCallbackOrderingSelfTest(): LongArray
    /** Native detach selection excludes historical RELEASED trackers from the exact freeze. */
    external fun nativeRunRetiredAuthoritySelectionSelfTest(): LongArray
    /** Production asynchronous NTK11 SurfaceControl/AHB/Swappy exact-identity proof. */
    external fun nativeRunSurfaceControlSchema11SelfTest(): LongArray
    /** Native canonical retired-authority digest golden/mutation vectors. */
    external fun nativeRetiredAuthorityDigestVectorsForTesting(): String
    /** Native/Kotlin token-framed GPU scene digest parity vector. */
    external fun nativeGpuSceneDigestVectorForTesting(): String
    /** [registrySize, nextOpaqueId, createCount, destroyCount]. */
    external fun nativeDebugHandleRegistryCounters(): LongArray
    /** Simulates a lost shared EGL resource group at the next detach boundary. */
    external fun nativeSetContextLossForTesting(handle: Long)
    /** Simulates EGL_CONTEXT_LOST discovered by the detach operation itself. */
    external fun nativeSetContextLossDuringDetachForTesting(handle: Long)
    external fun nativeOpenDetachedPreparation(
        handle: Long,
        authority: Long,
        authorityGenerationCandidate: Long,
        preparationGeneration: Long,
        manifestRevision: Long,
        manifestDigest: String
    ): LongArray?
    external fun nativeInstallDetachedPrepared(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        preparationGeneration: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        rgbaBytes: Long,
        width: Int,
        height: Int,
        tileProofDigest: String,
        bitmap: Bitmap
    ): Boolean
    external fun nativeInstallSurfacePrepared(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        preparationGeneration: Long,
        surfaceEpoch: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        rgbaBytes: Long,
        width: Int,
        height: Int,
        tileProofDigest: String,
        bitmap: Bitmap
    ): Boolean
    external fun nativeAdoptDetachedPreparationToSurface(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        preparationGeneration: Long,
        demandGeneration: Long,
        attachGeneration: Long,
        surfaceEpoch: Long,
        geometryRevision: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String,
        preGeometryRootDigest: String,
        preparedInventoryDigest: String,
        gpuSceneFormat: Int,
        gpuSceneLogicalBytes: Long,
        gpuSceneDigest: String,
        contentHeight: Long,
        viewportWidth: Int,
        viewportHeight: Int,
        scrollTop: Long,
        pages: IntArray,
        slots: IntArray,
        widths: IntArray,
        heights: IntArray,
        contentTops: LongArray,
        contentBottoms: LongArray
    ): Array<String>?
    external fun nativeClosePreparationAdmissions(
        handle: Long,
        authorityGeneration: Long,
        authority: Long
    ): Boolean
    external fun nativeBind(
        handle: Long,
        authority: Long,
        authorityGenerationCandidate: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String,
        preGeometryRootDigest: String,
        gpuSceneFormat: Int,
        gpuSceneLogicalBytes: Long,
        gpuSceneDigest: String,
        contentHeight: Long,
        viewportWidth: Int,
        viewportHeight: Int,
        scrollTop: Long,
        pages: IntArray,
        slots: IntArray,
        widths: IntArray,
        heights: IntArray,
        contentTops: LongArray,
        contentBottoms: LongArray
    ): Long
    external fun nativeDisarm(
        handle: Long,
        authorityGeneration: Long,
        authority: Long
    ): Boolean
    external fun nativeUpload(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        rgbaBytes: Long,
        bitmap: Bitmap,
        contentTop: Long,
        contentBottom: Long
    ): Boolean
    external fun nativeCommitProtection(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        demandEpoch: Long,
        basisFrameSequence: Long,
        basisInputSequence: Long,
        direction: Int,
        protectedTileOrdinals: IntArray,
        protectedDigest: String
    ): Boolean
    external fun nativeRetire(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        policySurfaceEpoch: Long,
        demandEpoch: Long,
        basisFrameSequence: Long,
        basisInputSequence: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        retireLease: Long,
        rgbaBytes: Long,
        protectedDigest: String
    ): Boolean
    external fun nativeStage(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        corridorStart: Long,
        corridorEnd: Long,
        stageNonce: Long
    ): Boolean
    external fun nativeActivate(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        stageNonce: Long
    ): Boolean
    external fun nativeTouch(
        handle: Long,
        authorityGeneration: Long,
        authority: Long,
        action: Int,
        eventTimeNanos: Long,
        x: Float,
        y: Float,
        pointerId: Int
    ): Long
    external fun nativeReleaseAuthority(
        handle: Long,
        callback: NtkStripRenderEngine,
        engineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        reducerSurfaceEpoch: Long,
        releaseNonce: Long
    ): Int
    /** Read-only lifecycle evidence; never changes renderer state. */
    external fun nativeDebugLifecycleCounters(handle: Long): LongArray
    /** Startup-only lifecycle evidence kept separate from schema-11 frame qualification. */
    external fun nativeDebugStartupLifecycle(handle: Long): LongArray
    /** Read-only depth-one scheduler invariants; does not alter NTK11 wire evidence. */
    external fun nativeDebugSchedulerCounters(handle: Long): LongArray
    /** Read-only exact-token inventory evidence; never changes renderer state. */
    external fun nativeDebugAuthorityInventory(
        handle: Long,
        authorityGeneration: Long,
        authority: Long
    ): LongArray
    /** Read-only backend/tombstone evidence; array layout is fixed by RetiredBackendDebugSnapshot. */
    external fun nativeDebugRetiredBackend(handle: Long): LongArray
    external fun nativeResetInputTelemetry(handle: Long)
    external fun nativeFirstMainIngressNanos(handle: Long): Long
    external fun nativeLatestSuccessfulSwapInputEventNanos(handle: Long): Long
    /**
     * Read-only watermark published after the ordered Kotlin frame callback for a real
     * target-retired + compositor-latched frame has returned.
     */
    external fun nativeLatestDeliveredLatchedInputEventNanos(handle: Long): Long
    external fun nativePreSubmitViewportGap(handle: Long): Long
    external fun nativeResize(
        handle: Long,
        attachGeneration: Long,
        surfaceEpoch: Long,
        width: Int,
        height: Int
    )
    external fun nativeRequestRender(handle: Long)
    /**
     * A CONTEXT_LOST_RETIRED return is the complete old-generation lifetime barrier. Native may
     * retain immutable CPU release proof, but no thread/EGL/window/Swappy/global-ref ownership.
     */
    external fun nativeDetach(
        handle: Long,
        callback: NtkStripRenderEngine,
        surfaceEpoch: Long,
        frozenAuthorityKeyTriples: LongArray,
        frozenAuthorityDigest: String
    ): NtkNativeDetachResult
    /** Returns true only after the opaque native handle ID has been destroyed and unregistered. */
    external fun nativeDestroy(handle: Long): Boolean
}
