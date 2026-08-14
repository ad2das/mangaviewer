package ml.melun.mangaview.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.Keep
import ml.melun.mangaview.runtime.AppDispatchers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

private typealias ReleaseRegistration =
    NtkReleaseRegistration<NtkAuthorityReleaseRequest, NtkNativeAuthorityReleaseAck>

internal const val NTK_FIXED_RETIREMENT_RETIRED = 2

internal enum class NtkSurfaceAttachFailure {
    COMPOSITOR_ALPHA_FAILED,
    SURFACE_LEASE_ACQUIRE_FAILED,
    NATIVE_QUEUE_REJECTED,
    NATIVE_ATTACH_FAILED,
    RESIZE_BARRIER_FAILED,
    LIFECYCLE_EXECUTOR_REJECTED,
    LIFECYCLE_TASK_FAILED,
    PROTOCOL_REJECTED
}

internal enum class NtkSurfaceLossReason {
    HOLDER_DESTROYED,
    VIEW_CLOSED
}

internal data class NtkDetachedWarmProof(
    val engineGeneration: Long,
    val eglReadyNanos: Long,
    val renderPbufferReadyNanos: Long,
    val uploadPbufferReadyNanos: Long,
    val programReadyNanos: Long,
    val nativeWindowCount: Long,
    val surfaceControlAttachCount: Long,
    val windowFrameIdCount: Long,
    val windowSwapCount: Long
) {
    val isExactDetachedWarm: Boolean
        get() = engineGeneration > 0L &&
            eglReadyNanos > 0L &&
            renderPbufferReadyNanos > 0L &&
            uploadPbufferReadyNanos > 0L &&
            programReadyNanos > 0L &&
            nativeWindowCount == 0L &&
            surfaceControlAttachCount == 0L &&
            windowFrameIdCount == 0L &&
            windowSwapCount == 0L
}

data class NtkSchedulerDebugSnapshot(
    val maxLogicalProducerDepth: Long,
    val maxSuccessorDepth: Long,
    val maxSwappyReservationDepth: Long,
    val maxBackendPreparedDepth: Long,
    val spuriousCommitAttemptCount: Long,
    val terminalAcceptedCount: Long,
    val terminalSubmittedCount: Long,
    val terminalJoinedCount: Long,
    val terminalLostCount: Long
)

internal class NtkStripRenderEngine(
    activity: Activity,
    internal val engineGeneration: Long = allocateEngineGeneration(),
    private val protocol: NtkEngineProtocolCoordinator = NtkEngineProtocolCoordinator()
) : AutoCloseable {
    data class TileKey(val authority: Long, val page: Int, val slot: Int)
    data class InstallIdentity(
        val key: TileKey,
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val surfaceEpoch: Long,
        val admissionId: Long,
        val resourceRevision: Long,
        val installLease: Long,
        val rgbaBytes: Long
    )
    data class DetachedInstallCallbackIdentity(
        val key: TileKey,
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val preparationGeneration: Long,
        val admissionId: Long,
        val resourceRevision: Long,
        val installLease: Long,
        val rgbaBytes: Long
    )
    data class TileResidentResult(
        val identity: InstallIdentity,
        val sceneVersion: Long,
        val success: Boolean
    )

    internal data class LifecycleDebugSnapshot(
        val engineGeneration: Long,
        val globalCreateCount: Long,
        val globalDestroyCount: Long,
        val eglInitializeCount: Long,
        val eglContextCreateCount: Long,
        val bindApplyCount: Long,
        val releaseAckCount: Long,
        val nativeHandleIdentity: Long,
        val contextReusable: Boolean,
        val enginePoisoned: Boolean,
        val resourceWorkerGeneration: Long,
        val resourceWorkerCreateCount: Long,
        val resourceWorkerDestroyCount: Long,
        val activeResourceWorkerCount: Long,
        val resourceWorkerOwnerAuthorityGeneration: Long,
        val resourceWorkerOwnerAuthority: Long,
        val resourceWorkerContextCreatedNanos: Long,
        val resourceWorkerReadyNanos: Long,
        val resourceWorkerContextDestroyedNanos: Long,
        val resourceWorkerThreadJoinedNanos: Long,
        val bindCommittedNanos: Long
    )

    internal data class StartupLifecycleDebugSnapshot(
        val engineGeneration: Long,
        val nativeCreateBeginNanos: Long,
        val nativeCreateEndNanos: Long,
        val swappyInitBeginNanos: Long,
        val swappyInitEndNanos: Long,
        val eglInitBeginNanos: Long,
        val eglInitEndNanos: Long,
        val renderContextReadyNanos: Long,
        val uploadContextReadyNanos: Long,
        val renderPbufferReadyNanos: Long,
        val uploadPbufferReadyNanos: Long,
        val programReadyNanos: Long,
        val eglReadyNanos: Long,
        val detachedWarmReadyNanos: Long,
        val attachLeaseQueuedNanos: Long,
        val attachLeaseClaimedNanos: Long,
        val swappyWindowBeginNanos: Long,
        val swappyWindowEndNanos: Long,
        val surfaceControlAttachBeginNanos: Long,
        val surfaceControlAttachEndNanos: Long,
        val attachReadyNanos: Long,
        val attachPublishedNanos: Long,
        val firstBackendPrepareNanos: Long,
        val firstTransactionApplyNanos: Long,
        val firstLatchNanos: Long,
        val surfaceControlAttachCount: Long,
        val windowFrameIdCount: Long,
        val windowSwapCount: Long
    )

    internal data class RetiredBackendDebugSnapshot(
        val mode: Long,
        val backendRetirementSerial: Long,
        val backendRetiredNanos: Long,
        val remainingThreadCount: Long,
        val remainingEglHandleCount: Long,
        val remainingNativeWindowCount: Long,
        val remainingSwappyLeaseCount: Long,
        val remainingJniGlobalRefCount: Long,
        val remainingBitmapGlobalRefCount: Long,
        val remainingNativeCallbackCount: Long
    ) {
        val isRetiredProofOnly: Boolean
            get() = mode == NATIVE_HANDLE_MODE_RETIRED_PROOF_ONLY

        val hasNoBackendOwnership: Boolean
            get() = remainingThreadCount == 0L && remainingEglHandleCount == 0L &&
                remainingNativeWindowCount == 0L && remainingSwappyLeaseCount == 0L &&
                remainingJniGlobalRefCount == 0L && remainingBitmapGlobalRefCount == 0L &&
                remainingNativeCallbackCount == 0L
    }

    internal data class AuthorityInventoryDebugSnapshot(
        val lifecycle: Long,
        val capturedResourceCount: Long,
        val sceneCount: Long,
        val queuedUploadCount: Long,
        val readyCount: Long,
        val retireDeleteCount: Long,
        val preallocatedCount: Long,
        val inFlightCount: Long,
        val physicalComplete: Boolean,
        val ackEnqueued: Boolean
    )

    private data class ProtectionIdentity(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val authority: Long,
        val surfaceEpoch: Long,
        val demandEpoch: Long,
        val protectedDigest: String
    )

    private data class RetireIdentity(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val authority: Long,
        val surfaceEpoch: Long,
        val policySurfaceEpoch: Long,
        val demandEpoch: Long,
        val page: Int,
        val slot: Int,
        val resourceRevision: Long,
        val installLease: Long,
        val retireLease: Long,
        val protectedDigest: String
    )

    internal data class StageRevocation(
        val proof: NtkStageProof?,
        val requestWasPending: Boolean,
        val detachResult: NtkNativeDetachResult
    ) {
        val crossedStageBoundary: Boolean
            get() = proof != null || requestWasPending

        val resourcesPreserved: Boolean
            get() = detachResult.resourcesPreserved
    }

    internal data class SurfaceAttachReady(
        val key: NtkSurfaceAttachKey,
        val appliedGeometryRevision: Long,
        val width: Int,
        val height: Int,
        val completedNanos: Long
    )

    internal sealed interface SurfaceAttachCompletion {
        data class Ready(val value: SurfaceAttachReady) : SurfaceAttachCompletion
        data class CancelledBeforeClaim(
            val key: NtkSurfaceAttachKey
        ) : SurfaceAttachCompletion
        data class Failed(
            val key: NtkSurfaceAttachKey,
            val reason: NtkSurfaceAttachFailure
        ) : SurfaceAttachCompletion
    }

    internal data class SurfaceRevocation(
        val key: NtkSurfaceAttachKey,
        val proof: NtkStageProof?,
        val requestWasPending: Boolean,
        val reason: NtkSurfaceLossReason
    ) {
        val crossedStageBoundary: Boolean
            get() = proof != null || requestWasPending
    }
    /**
     * JNI reports an intentionally exhaustive proof record. Keeping that record as a gigantic
     * Kotlin primary constructor makes D8 synthesize constructor/copy invocations whose argument
     * register count cannot be verified on Android. The callback now fills this single-owner
     * object before publishing it through [frameSnapshot]; after publication it is immutable by
     * protocol. [withGpuScene] clones first so a later stage proof remains an atomic publication.
     */
    class FrameSnapshot internal constructor() : Cloneable {
        internal var schema11Values: LongArray = LongArray(0)
        // Frozen V10 prefix used only by the compatibility accumulator.
        internal var schema10Values: LongArray = LongArray(0)
        internal var postSubmitSuccessfulCount: Long = 0L
        internal var postSubmitLatchedProofCount: Long = 0L
        internal var postSubmitTerminalLostProofCount: Long = 0L
        internal var postSubmitLogicalUnlatchedNow: Long = 0L
        internal var postSubmitMaxLogicalUnlatched: Long = 0L
        internal var postSubmitConservationBranch: Int = 0
        internal var rendererPriorLatchInstalledBeforeSwappyPublish = false
        internal var latestAppliedBufferRefSerial: Long = 0L
        internal var latestConsumedLatchRefSerial: Long = 0L
        internal var surfaceLogicalUnlatchedNow: Int = 0
        internal var surfaceMaxLogicalUnlatched: Int = 0
        internal var submittedWaitLatchCount: Int = 0
        internal var applyBeforePriorCommitConsumedCount: Long = 0L
        internal var priorOnCompletePendingAtSuccessorApply: Int = 0
        internal var acquireFenceSignalNanos: Long = 0L
        internal var acquireFenceEventSequence: Long = 0L
        internal var acquireFenceSerial: Long = 0L
        internal var acquireFenceDupCount: Int = 0
        internal var frameworkTransferCount: Int = 0
        internal var proofFdCloseCount: Int = 0
        internal var rendererGpuClientWaitCount: Int = 0
        internal var applyBeforeAcquireSignalProven: Boolean = false
        internal var visualDemandEpoch: Long = 0L
        internal var visualMutationSerial: Long = 0L
        internal var visibleStateChanged: Boolean = false
        internal var engineGeneration: Long = 0L
        internal var authorityGeneration: Long = 0L
        internal var authority: Long = 0L
        internal var frameSequence: Long = 0L
        internal var sceneVersion: Long = 0L
        internal var scrollTopPx: Long = 0L
        internal var velocityPxPerSecond: Float = 0f
        internal var predictedStopPx: Long = 0L
        internal var residentContinuousStartPx: Long = 0L
        internal var residentContinuousEndPx: Long = 0L
        internal var visibleContentStartPx: Long = 0L
        internal var visibleContentEndPx: Long = 0L
        internal var firstVisiblePage: Int = 0
        internal var lastVisiblePage: Int = 0
        internal var firstVisibleGapPx: Long = 0L
        internal var viewportOriginalComplete: Boolean = false
        internal var runwayOriginalComplete: Boolean = false
        internal var presentedAtNanos: Long = 0L
        internal var compositionLatchNanos: Long = 0L
        internal var queueSubmitNanos: Long = 0L
        internal var gestureId: Long = 0L
        internal var appliedInputSequence: Long = 0L
        internal var inputOldestNanos: Long = 0L
        internal var inputNewestNanos: Long = 0L
        internal var mainIngressOldestNanos: Long = 0L
        internal var mainIngressNewestNanos: Long = 0L
        internal var receiptOldestNanos: Long = 0L
        internal var receiptNewestNanos: Long = 0L
        internal var mutationOldestNanos: Long = 0L
        internal var mutationNewestNanos: Long = 0L
        internal var drawBeginNanos: Long = 0L
        internal var targetReachedNanos: Long = 0L
        internal var fenceCompleteNanos: Long = 0L
        internal var preWaitNanos: Long = 0L
        internal var postWaitNanos: Long = 0L
        internal var preSwapNanos: Long = 0L
        internal var postSwapNanos: Long = 0L
        internal var swapIntervalNanos: Long = 0L
        internal var timestampQueryWorkNanos: Long = 0L
        internal var controlBacklogMax: Int = 0
        internal var moveMailboxWrites: Int = 0
        internal var integratedTiles: Int = 0
        internal var uploadCommandsSubmitting: Int = 0
        internal var uploadGpuFencesPending: Int = 0
        internal var gpuPhase: Int = 0
        internal var sealedScene: Boolean = false
        internal var resourceSubmitSerial: Long = 0L
        internal var sealedResourceSubmitSerial: Long = 0L
        internal var readyTileQueueDepth: Int = 0
        internal var nativePublicationsOutstanding: Int = 0
        internal var pendingPublishAcks: Int = 0
        internal var retireQueueDepth: Int = 0
        internal var retirementCount: Int = 0
        internal var uploadContextAlive: Boolean = false
        internal var lastGpuResourceCompletionNanos: Long = 0L
        internal var sealFenceCompletionNanos: Long = 0L
        internal var uploadContextDestroyedNanos: Long = 0L
        internal var stageLatchNanos: Long = 0L
        internal var firstDownIngressNanos: Long = 0L
        internal var sealedSceneVersion: Long = 0L
        internal var resourceWorkerState: Int = 0
        internal var resourceWorkerGeneration: Long = 0L
        internal var resourceWorkerCreateCount: Long = 0L
        internal var resourceWorkerDestroyCount: Long = 0L
        internal var activeResourceWorkerCount: Int = 0
        internal var activeUploadContextCount: Int = 0
        internal var sceneMutationCountSinceSeal: Long = 0L
        internal var offscreenWarmFenceCompletionNanos: Long = 0L
        internal var predecessorPhysicalCompleteNanos: Long = 0L
        internal var sealBarrierSerial: Long = 0L
        internal var stageBackbufferReadyNanos: Long = 0L
        internal var offscreenWarmDrawCount: Long = 0L
        internal var frameWorkKind: Int = 0
        internal var admissionSequence: Long = 0L
        internal var plannerInvocationCount: Long = 0L
        internal var backendPresentPrepareCount: Long = 0L
        internal var swapAttemptCount: Long = 0L
        internal var slotClosedNoAttemptCount: Long = 0L
        internal var terminalSwapCount: Long = 0L
        internal var windowSwapCountBeforeStage: Long = 0L
        internal var windowFrameIdCountBeforeStage: Long = 0L
        internal var preparedWorkGeneration: Long = 0L
        internal var swappyWorkGeneration: Long = 0L
        internal var swappyAdmissionSequence: Long = 0L
        internal var preparedDrawCount: Long = 0L
        internal var preparedFrameIdReservationCount: Long = 0L
        internal var admissionConsumed: Boolean = false
        internal var presentationSupported: Boolean = false
        internal var swappyMode: Int = 0
        internal var surfaceEpoch: Long = 0L
        internal var frameId: Long = 0L
        internal var latchProofState: Int = 0
        internal var logicalUnlatchedSubmissions: Int = 0
        internal var maxLogicalUnlatchedSubmissions: Int = 0
        internal var oldestUnlatchedAgeNanos: Long = 0L
        internal var latchQueryError: Int = 0
        internal var latchEvidenceDeadlineNanos: Long = 0L
        internal var cadenceQualificationFailed: Boolean = false
        internal var fixedPhaseTelemetryValid: Boolean = false
        internal var fixedPhaseSequence: Long = 0L
        internal var fixedPhaseStaleTargetObserved: Boolean = false
        internal var fixedPhaseMissProven: Boolean = false
        internal var fixedPhaseOutcome: Int = 0
        internal var fixedPhaseFatalReason: Int = 0
        internal var fixedPhasePlanValid: Boolean = false
        internal var fixedPhaseRefreshPeriodNanos: Long = 0L
        internal var fixedPhaseAppVsyncOffsetNanos: Long = 0L
        internal var fixedPhaseAcceptedFrameTimeNanos: Long = 0L
        internal var fixedPhaseAcceptedFrameIndex: Long = 0L
        internal var fixedPhaseDecisionNanos: Long = 0L
        internal var fixedPhaseMissedPresentationNanos: Long = 0L
        internal var fixedPhasePlannedPresentationNanos: Long = 0L
        internal var fixedPhasePresentationDeadlineNanos: Long = 0L
        internal var fixedPhaseOpenNanos: Long = 0L
        internal var fixedPhaseLatestSwapStartExclusiveNanos: Long = 0L
        internal var fixedPhaseWaitNanos: Long = 0L
        internal var fixedPhasePlannedTargetFrame: Long = 0L
        internal var fixedPhasePreSwapNanos: Long = 0L
        internal var fixedPhasePostSwapNanos: Long = 0L
        internal var fixedPhaseSwapDurationNanos: Long = 0L
        internal var fixedPhaseFenceWaitCount: Int = 0
        internal var fixedPhasePostSwapTargetRebaseCount: Int = 0
        internal var telemetrySchemaVersion: Int = 0
        internal var backendCompletionToken: Long = 0L
        internal var backendSurfaceSerial: Long = 0L
        internal var backendCompletionWorkGeneration: Long = 0L
        internal var backendCompletionFrameId: Long = 0L
        internal var backendCompletionGfxstreamFrameNumber: Long = 0L
        internal var backendCompletionClockDomain: Int = 0
        internal var backendPrepareBeginNanos: Long = 0L
        internal var backendCompletionSignalNanos: Long = 0L
        internal var backendWaitReturnNanos: Long = 0L
        internal var backendCompletionIssueCount: Int = 0
        internal var backendCompletionCommitCount: Int = 0
        internal var backendCompletionPublishCount: Int = 0
        internal var fixedPhaseReservationSequence: Long = 0L
        internal var fixedPhaseOpportunitySequence: Long = 0L
        internal var fixedPhaseOpportunityKind: Int = 0
        internal var fixedPhasePhysicalCallbackSequence: Long = 0L
        internal var fixedPhaseReservationNanos: Long = 0L
        internal var fixedPhaseOpportunityReceiptNanos: Long = 0L
        internal var fixedPhaseOpportunityPublishNanos: Long = 0L
        internal var fixedPhaseRendererWakeObservedNanos: Long = 0L
        internal var fixedCandidateSequence: Long = 0L
        internal var fixedCandidateRawSequence: Long = 0L
        internal var fixedCandidateCaptureNanos: Long = 0L
        internal var fixedCandidateClaimNanos: Long = 0L
        internal var fixedRefreshIssued: Int = 0
        internal var fixedRefreshDelivered: Int = 0
        internal var fixedRefreshPhysicalCallbackSequence: Long = 0L
        internal var fixedRefreshCapturedRawSequence: Long = 0L
        internal var fixedShadowRawSequence: Long = 0L
        internal var fixedShadowPromotionCount: Long = 0L
        internal var fixedWakeNoticeSequence: Long = 0L
        internal var fixedJoinNoticeSequence: Long = 0L
        internal var fixedJoinOpenNanos: Long = 0L
        internal var fixedJoinPriorRetirementSequence: Long = 0L
        internal var fixedLatchCreditWorkGeneration: Long = 0L
        internal var fixedLatchCreditAdmissionSequence: Long = 0L
        internal var fixedLatchCreditFrameId: Long = 0L
        internal var fixedLatchCreditQueueNanos: Long = 0L
        internal var fixedLatchCreditLatchNanos: Long = 0L
        internal var fixedLatchCreditQueryCount: Int = 0
        internal var fixedFinalCorridorBeginNanos: Long = 0L
        internal var fixedQueueMarkNanos: Long = 0L
        internal var fixedEglSwapEnterNanos: Long = 0L
        internal var fixedDecisionToEglEnterNanos: Long = 0L
        internal var fixedCommonCommitEntryNanos: Long = 0L
        internal var fixedOpportunityClaimNanos: Long = 0L
        internal var fixedRetirementDemandIssued: Long = 0L
        internal var fixedRetirementDemandSatisfied: Long = 0L
        internal var fixedRetirementDemandCancelled: Long = 0L
        internal var fixedOpportunityDemandIssued: Long = 0L
        internal var fixedOpportunityDemandSatisfied: Long = 0L
        internal var fixedOpportunityDemandCancelled: Long = 0L
        internal var fixedSupersededBeforeClaimCount: Long = 0L
        internal var fixedClosedOpportunityCount: Long = 0L
        internal var fixedTransportProfileDigest: Long = 0L
        internal var fixedTimingGeneration: Long = 0L
        internal var fixedTransportBoundNanos: Long = 0L
        internal var fixedInitialDecisionNanos: Long = 0L
        internal var fixedCase1CutoffNanos: Long = 0L
        internal var fixedCase2PhaseOpenNanos: Long = 0L
        internal var fixedCase2GateNanos: Long = 0L
        internal var fixedCase2CutoffNanos: Long = 0L
        internal var fixedCase2LatestStartExclusiveNanos: Long = 0L
        internal var fixedCase1LatestSafeDecisionNanos: Long = 0L
        internal var fixedInitialTransportAdmissionOutcome: Int = 0
        internal var fixedPhaseWaitCount: Int = 0
        internal var fixedCase2GateWaitTargetNanos: Long = 0L
        internal var fixedCase2GateWaitReturnNanos: Long = 0L
        internal var fixedFinalDecisionNanos: Long = 0L
        internal var fixedClaimIssuedCount: Int = 0
        internal var transactionPrepareBeginNanos: Long = 0L
        internal var transactionPrepareEndNanos: Long = 0L
        internal var fixedDecisionToClaimReturnNanos: Long = 0L
        internal var transactionApplyCallDurationNanos: Long = 0L
        internal var fixedDecisionToApplyEndNanos: Long = 0L
        internal var fixedTransportBoundSlackNanos: Long = 0L
        internal var fixedCutoffSlackNanos: Long = 0L
        internal var setFrameTimelineCount: Int = 0
        internal var fixedApplyDisposition: Int = 0
        internal var fixedPhaseRootFatalReason: Int = 0
        internal var fixedReceiptFatalReason: Int = 0
        internal var fixedRetirementRootFatalReason: Int = 0
        internal var fixedRetirementCallbackPublishCount: Int = 0
        internal var fixedOutstandingSubmissionCount: Int = 0
        internal var fixedMaxOutstandingSubmissionCount: Int = 0
        internal var fixedPreparedTransactionState: Int = 0
        internal var fixedExternalClaimPresent: Boolean = false
        internal var fixedPoolStates: IntArray = IntArray(8)
        internal var fixedPendingFenceWatchCount: Int = 0
        internal var fixedActiveFenceWatchCount: Int = 0
        internal var fixedTransactionCompleteEventSequence: Long = 0L
        internal var fixedPreviousBufferExpected: Boolean = false
        internal var fixedPreviousReleaseEventSequence: Long = 0L
        internal var fixedTeardownReleaseEventSequence: Long = 0L
        internal var fixedCallbackRecordDepth: Int = 0
        internal var fixedMaxCallbackRecordDepth: Int = 0
        internal var fixedPreviousReleaseRecordDepth: Int = 0
        internal var fixedAcquireFenceRecordDepth: Int = 0
        internal var fixedAppOwnedAcquireFdCount: Int = 0
        internal var fixedCommitProofPendingNow: Int = 0
        internal var fixedCompleteProofPendingNow: Int = 0
        internal var fixedMaxCommitProofPending: Int = 0
        internal var fixedMaxCompleteProofPending: Int = 0
        internal var fixedHeldFrameworkRefCount: Int = 0
        internal var fixedMaxHeldFrameworkRefCount: Int = 0
        internal var fixedFreeReusableCount: Int = 0
        internal var fixedMinFreeReusableCount: Int = 0
        internal var fixedAppOwnedBufferDomainNow: Int = 0
        internal var fixedMinAppOwnedBufferDomain: Int = 0
        internal var fixedBackpressureEnableCount: Long = 0L
        internal var fixedBackpressureDisableCount: Long = 0L
        internal var fixedCapacityExhaustedCount: Long = 0L
        internal var fixedCapacityWaitCount: Long = 0L
        internal var fixedBackendInvariantFatalCount: Long = 0L
        internal var fixedApplyBeforePriorCompleteCount: Long = 0L
        internal var fixedTargetUnretiredNow: Int = 0
        internal var fixedTargetUnretiredMax: Int = 0
        internal var fixedPreparedProducerNow: Int = 0
        internal var fixedPreparedProducerMax: Int = 0
        internal var fixedPriorLatchGateRequired: Int = 0
        internal var fixedPriorLatchGateUsed: Int = 0
        internal var fixedPriorLatchWaitCount: Int = 0
        internal var fixedPriorLatchObservationState: Int = 0
        internal var fixedPriorCommitProofPendingAtClaim: Int = 0
        internal var fixedPriorRetirementProofPresent: Boolean = false
        internal var fixedPriorAppliedBufferRefSerial: Long = 0L
        internal var fixedAppliedBufferRefSerial: Long = 0L
        internal var fixedTargetRetiredToSuccessorApplyNanos: Long = 0L
        internal var fixedSuccessorApplyToPriorCommitNanos: Long = 0L
        internal var fixedPriorLatchObservedAtClaim: Boolean = false
        internal var fixedReservationNanos: Long = 0L
        internal var fixedRawBaselineSequence: Long = 0L
        internal var fixedRawAuthoritySequence: Long = 0L
        internal var fixedTargetPhysicalCallbackSequence: Long = 0L
        internal var fixedTargetFrameTimeNanos: Long = 0L
        internal var fixedTargetFrameIndex: Long = 0L
        internal var fixedRetirementPublishNanos: Long = 0L
        internal var fixedRendererWakePublishNanos: Long = 0L
        internal var fixedRetirementRecordDemandIssued: Long = 0L
        internal var fixedRetirementRecordDemandSatisfied: Long = 0L
        internal var fixedRetirementRecordDemandCancelled: Long = 0L
        internal var fixedPriorRetirementWorkGeneration: Long = 0L
        internal var fixedPriorRetirementAdmissionSequence: Long = 0L
        internal var fixedPriorRetirementSequence: Long = 0L
        internal var fixedBackendReadyNanos: Long = 0L
        internal var fixedFirstCommitAttemptNanos: Long = 0L
        internal var fixedTimestampQueryBeforeFirstCommitCount: Int = 0
        internal var drawIssueEndNanos: Long = 0L
        internal var frameIdReservationBeginNanos: Long = 0L
        internal var frameIdReservedNanos: Long = 0L
        internal var postSwapCriticalNanos: Long = 0L
        internal var postSwapToNextReservationNanos: Long = 0L
        internal var commonCallbackTransactionNanos: Long = 0L
        internal var wakeDispatchToRendererCallbackNanos: Long = 0L
        internal var rendererCallbackToCommitEntryNanos: Long = 0L
        internal var commonCommitEntryToClaimNanos: Long = 0L
        internal var backendPhasePartitionValid: Boolean = false
        internal var readyCommitPriorityViolationFrames: Long = 0L
        internal var preCommitRetirementObservationFrames: Long = 0L
        internal var retainedQueryRequiredCount: Long = 0L
        internal var retainedQueryExecutedCount: Long = 0L
        internal var retainedQueryWrongSelectionCount: Long = 0L
        internal var commitBeforeRetainedQueryCount: Long = 0L
        internal var callbackArrivedDuringQueryCount: Long = 0L
        internal var evidenceCapsuleDepth: Int = 0
        internal var evidenceCapsuleMaxDepth: Int = 0
        internal var evidenceCapsuleInvalidFrames: Long = 0L
        internal var gpuSceneAdmissionState: Int = NtkGpuSceneAdmissionState.EMPTY.ordinal
        internal var gpuSceneFormat: Int = NtkGpuSceneFormat.RGBA8_UNORM.ordinal
        internal var gpuSceneExpectedTextureCount: Int = 0
        internal var gpuSceneResidentTextureCount: Int = 0
        internal var gpuSceneExpectedLogicalBytes: Long = 0L
        internal var gpuSceneResidentLogicalBytes: Long = 0L
        internal var gpuSceneDigest: String = ""
        internal var evidenceQualified: Boolean = false
        internal var workGeneration: Long = 0L
        internal var capsuleSequence: Long = 0L
        internal var latchEventSequence: Long = 0L
        internal var retirementSequence: Long = 0L
        internal var transactionSerial: Long = 0L
        internal var bufferSlot: Long = 0L
        internal var bufferGeneration: Long = 0L
        internal var frameTimelineVsyncId: Long = 0L
        internal var setBufferCount: Int = 0
        internal var transactionApplyCount: Int = 0
        internal var onCommitCallbackCount: Int = 0
        internal var onCompleteCallbackCount: Int = 0
        internal var latchSource: Int = 0
        internal var latchCallbackObservedNanos: Long = 0L
        internal var retirementCallbackPublishedNanos: Long = 0L
        internal var targetWaitCount: Int = 0
        internal var targetRebaseCount: Int = 0
        internal var stageCandidate: Boolean = false
        internal var stageNonce: Long = 0L
        internal var stageCorridorStartPx: Long = 0L
        internal var stageCorridorEndPx: Long = 0L
        internal var retirementState: Int = 0
        internal var retirementFatalReason: Int = 0
        internal var fixedLatchEventTransactionSerial: Long = 0L
        internal var fixedLatchEventSequence: Long = 0L
        internal var fixedExternalWorkGeneration: Long = 0L
        internal var fixedExternalFrameId: Long = 0L
        internal var functionalGpuInvariantValid: Boolean = false
        internal var gpuInvariantValid: Boolean = false

        internal fun withGpuScene(
            admissionState: Int,
            format: Int,
            expectedTextureCount: Int,
            residentTextureCount: Int,
            expectedLogicalBytes: Long,
            residentLogicalBytes: Long,
            digest: String
        ): FrameSnapshot {
            val copy = super.clone() as FrameSnapshot
            copy.gpuSceneAdmissionState = admissionState
            copy.gpuSceneFormat = format
            copy.gpuSceneExpectedTextureCount = expectedTextureCount
            copy.gpuSceneResidentTextureCount = residentTextureCount
            copy.gpuSceneExpectedLogicalBytes = expectedLogicalBytes
            copy.gpuSceneResidentLogicalBytes = residentLogicalBytes
            copy.gpuSceneDigest = digest
            return copy
        }

        internal fun withSurfaceQualification(
            functionalGpuInvariantValid: Boolean,
            gpuInvariantValid: Boolean
        ): FrameSnapshot {
            val copy = super.clone() as FrameSnapshot
            copy.functionalGpuInvariantValid = functionalGpuInvariantValid
            copy.gpuInvariantValid = gpuInvariantValid
            return copy
        }
    }

    private data class GpuInvariant(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val authority: Long,
        val frameSequence: Long,
        val gpuPhase: Int,
        val sealedScene: Boolean,
        val resourceSubmitSerial: Long,
        val sealedResourceSubmitSerial: Long,
        val readyTileQueueDepth: Int,
        val nativePublicationsOutstanding: Int,
        val pendingPublishAcks: Int,
        val retireQueueDepth: Int,
        val retirementCount: Int,
        val uploadContextAlive: Boolean,
        val lastGpuResourceCompletionNanos: Long,
        val sealFenceCompletionNanos: Long,
        val uploadContextDestroyedNanos: Long,
        val stageLatchNanos: Long,
        val firstDownIngressNanos: Long,
        val sealedSceneVersion: Long,
        val resourceWorkerState: Int,
        val resourceWorkerGeneration: Long,
        val resourceWorkerCreateCount: Long,
        val resourceWorkerDestroyCount: Long,
        val activeResourceWorkerCount: Int,
        val activeUploadContextCount: Int,
        val sceneMutationCountSinceSeal: Long,
        val offscreenWarmFenceCompletionNanos: Long,
        val predecessorPhysicalCompleteNanos: Long,
        val sealBarrierSerial: Long,
        val stageBackbufferReadyNanos: Long,
        val offscreenWarmDrawCount: Long,
        val frameWorkKind: Int,
        val admissionSequence: Long,
        val plannerInvocationCount: Long,
        val backendPresentPrepareCount: Long,
        val swapAttemptCount: Long,
        val slotClosedNoAttemptCount: Long,
        val terminalSwapCount: Long,
        val windowSwapCountBeforeStage: Long,
        val windowFrameIdCountBeforeStage: Long,
        val preparedWorkGeneration: Long,
        val swappyWorkGeneration: Long,
        val swappyAdmissionSequence: Long,
        val preparedDrawCount: Long,
        val preparedFrameIdReservationCount: Long,
        val admissionConsumed: Boolean,
        var gpuSceneAdmissionState: Int = NtkGpuSceneAdmissionState.EMPTY.ordinal,
        var gpuSceneFormat: Int = NtkGpuSceneFormat.RGBA8_UNORM.ordinal,
        var gpuSceneExpectedTextureCount: Int = 0,
        var gpuSceneResidentTextureCount: Int = 0,
        var gpuSceneExpectedLogicalBytes: Long = 0L,
        var gpuSceneResidentLogicalBytes: Long = 0L,
        var gpuSceneDigest: String = ""
    )

    private data class NativeFrameKey(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val surfaceEpoch: Long,
        val frameSequence: Long
    )

    private data class NativeStageEvidenceKey(
        val engineGeneration: Long,
        val surfaceEpoch: Long,
        val authorityGeneration: Long,
        val authority: Long,
        val workGeneration: Long,
        val frameId: Long,
        val frameSequence: Long,
        val admissionSequence: Long,
        val capsuleSequence: Long,
        val latchEventSequence: Long,
        val transactionSerial: Long
    )

    private class NativeStageLatchEvent {
        var engineGeneration: Long = 0L
        var surfaceEpoch: Long = 0L
        var authorityGeneration: Long = 0L
        var authority: Long = 0L
        var workGeneration: Long = 0L
        var frameId: Long = 0L
        var frameSequence: Long = 0L
        var admissionSequence: Long = 0L
        var capsuleSequence: Long = 0L
        var stageNonce: Long = 0L
        var sceneVersion: Long = 0L
        var corridorStartPx: Long = 0L
        var corridorEndPx: Long = 0L
        var latchEventSequence: Long = 0L
        var transactionSerial: Long = 0L
        var compositionLatchNanos: Long = 0L
        var gpuSceneFormatOrdinal: Int = 0
        var expectedTextureCount: Int = 0
        var residentTextureCount: Int = 0
        var expectedLogicalBytes: Long = 0L
        var residentLogicalBytes: Long = 0L
        var gpuSceneDigest: String = ""
        var lastResourceCompletionNanos: Long = 0L
        var sealFenceCompletionNanos: Long = 0L
    }

    private data class StageRequest(
        val token: NtkNativeAuthorityToken,
        val corridorStartPx: Long,
        val corridorEndPx: Long,
        val stageNonce: Long,
        val manifestRevision: Long,
        val manifestDigest: String,
        val geometryDigest: String,
        val gpuSceneFormat: NtkGpuSceneFormat,
        val gpuSceneTextureCount: Int,
        val gpuSceneLogicalBytes: Long,
        val gpuSceneDigest: String,
        val completion: (NtkStageProof?) -> Unit
    )

    private data class BindSeal(
        val token: NtkNativeAuthorityToken,
        val gpuSceneFormat: NtkGpuSceneFormat,
        val gpuSceneTextureCount: Int,
        val gpuSceneLogicalBytes: Long,
        val gpuSceneDigest: String
    )

    private data class PreparationBinding(
        val token: NtkNativeDetachedPreparationToken,
        val authorityGeneration: Long,
        val releaseToken: NtkNativeAuthorityToken
    )

    private var nextAuthorityGeneration = 0L
    private var currentBinding: NtkNativeAuthorityToken? = null
    private var currentPreparation: PreparationBinding? = null
    private val currentBindingMirror = AtomicReference<NtkNativeAuthorityToken?>()
    private val bindings = HashMap<AuthorityTokenKey, NtkNativeAuthorityToken>()
    private val releaseRegistrations = HashMap<AuthorityTokenKey, ReleaseRegistration>()
    private val closeOwnerStarted = AtomicBoolean(false)
    private val claimableRetiredProofKeys = HashSet<AuthorityTokenKey>()
    private var frozenRetiredTokens: Map<AuthorityTokenKey, NtkNativeAuthorityToken> = emptyMap()
    private val releasedDuringHandoffTokens = HashMap<AuthorityTokenKey, NtkNativeAuthorityToken>()
    private val releasedTokens = HashSet<AuthorityTokenKey>()
    private val failedTokens = HashSet<AuthorityTokenKey>()
    private val releasingTokens = HashSet<AuthorityTokenKey>()
    private val publishCallbacks = HashMap<InstallIdentity, (TileResidentResult) -> Unit>()
    private data class PreparedPublishCallback(
        val identity: NtkDetachedInstallIdentity,
        val completion: (NtkDetachedPreparedTileAck?) -> Unit
    )
    private val preparedPublishCallbacks =
        HashMap<DetachedInstallCallbackIdentity, PreparedPublishCallback>()
    private data class ProtectionCallback(
        val commit: NtkStripProtectionCommit,
        val completion: (NtkStripProtectionAck) -> Unit
    )
    private data class RetireCallbacks(
        val intent: NtkStripRetireIntent,
        val result: (NtkStripRetireResultAck) -> Unit,
        val freed: (NtkStripTileFreedAck) -> Unit,
        var detachedDelivered: Boolean = false
    )
    private val protectionCallbacks = HashMap<ProtectionIdentity, ProtectionCallback>()
    private val retireCallbacks = HashMap<RetireIdentity, RetireCallbacks>()
    private val frameSnapshot = AtomicReference<FrameSnapshot?>()
    private val gpuInvariants = HashMap<NativeFrameKey, GpuInvariant>()
    private var pendingStageEvidence: Pair<NativeStageEvidenceKey, FrameSnapshot>? = null
    // Reused handoff storage: native publishes schema-6 evidence immediately
    // before the matching frame callback on the single feedback thread.
    private val pendingFixedV4Evidence = LongArray(31)
    private val firstMainIngressNanos = AtomicLong(0L)
    private val preSubmitViewportGap = AtomicLong(0L)
    private var stageProof: NtkStageProof? = null
    private var stageRequest: StageRequest? = null
    private var bindSeal: BindSeal? = null
    private data class AttachRegistration(
        val operation: NtkAsyncSurfaceOperation,
        val completion: (SurfaceAttachCompletion) -> Unit,
        var width: Int,
        var height: Int,
        var geometryRevision: Long,
        var readyResizeTaskActive: Boolean = false
    )
    private data class SurfaceLossRegistration(
        val ticket: NtkDetachTicket,
        val revocation: SurfaceRevocation,
        val stageRequest: StageRequest?,
        val nativeDisposition: Int,
        val completion: (StageRevocation) -> Unit
    )
    private data class DetachPreparation(
        val proof: NtkStageProof?,
        val request: StageRequest?,
        val authoritySnapshot: List<NtkNativeAuthorityToken>
    )
    private val nextAttachGeneration = AtomicLong(0L)
    private var attachRegistration: AttachRegistration? = null
    private var surfaceLossRegistration: SurfaceLossRegistration? = null
    init {
        require(engineGeneration > 0L)
        reserveEngineGeneration(engineGeneration)
    }
    private val qualificationProfileId = Settings.Global.getString(
        activity.contentResolver,
        "ntk_qualification_profile_id"
    ).orEmpty()
    private val nativeHandle = NtkStripNativeBridge.nativeCreate(
        activity,
        this,
        qualificationProfileId,
        engineGeneration
    ).also { check(it > 0L) { "Unable to create NTK native renderer handle" } }
    @Volatile var frameListener: ((FrameSnapshot) -> Unit)? = null
    @Volatile var preSubmitViewportGapListener: ((Long, Long, Long, Long, Long) -> Unit)? = null

    private data class AuthorityTokenKey(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val authority: Long
    )

    private fun NtkNativeAuthorityToken.key() = AuthorityTokenKey(
        engineGeneration,
        authorityGeneration,
        authority
    )

    private fun tokenUsableLocked(token: NtkNativeAuthorityToken): Boolean =
        protocol.phaseLocked() == ProtocolPhase.LIVE_ATTACHED &&
            !failedTokens.contains(token.key()) &&
            !releasingTokens.contains(token.key())

    companion object {
        private const val FRAME_EVIDENCE_V11_MAGIC = 0x414b544e
        private const val FRAME_EVIDENCE_V11_SCHEMA = 11
        private const val FRAME_EVIDENCE_V11_FIELD_COUNT = 311
        private const val FRAME_EVIDENCE_V11_BYTES =
            16 + FRAME_EVIDENCE_V11_FIELD_COUNT * java.lang.Long.BYTES
        internal const val NATIVE_HANDLE_MODE_LIVE = 0L
        internal const val NATIVE_HANDLE_MODE_CONTEXT_LOSS_RETIRING = 1L
        internal const val NATIVE_HANDLE_MODE_RETIRED_PROOF_ONLY = 2L
        internal const val NATIVE_HANDLE_MODE_DESTROYED = 3L

        private val processEngineGeneration = AtomicLong(0L)
        internal fun allocateEngineGeneration(): Long = processEngineGeneration.incrementAndGet()
        private fun reserveEngineGeneration(value: Long) {
            var observed = processEngineGeneration.get()
            while (observed < value && !processEngineGeneration.compareAndSet(observed, value)) {
                observed = processEngineGeneration.get()
            }
        }
    }

    private fun <T> runSimpleOperation(
        operation: String,
        admission: NtkProtocolAdmission,
        rejected: T,
        nativeCall: () -> T
    ): T = protocol.runOperation(
        operation = operation,
        admission = admission,
        rejected = rejected,
        prepareLocked = { NtkPreparedOperation(Unit) },
        nativeCall = { nativeCall() },
        completeLocked = { _, result ->
            result.getOrElse {
                protocol.failLocked()
                rejected
            }
        }
    )

    private fun <T> runLiveOperation(
        operation: String,
        rejected: T,
        nativeCall: () -> T
    ): T = runSimpleOperation(operation, NtkProtocolAdmission.LIVE, rejected, nativeCall)

    private fun <T> runDebugOperation(
        operation: String,
        rejected: T,
        nativeCall: () -> T
    ): T = runSimpleOperation(operation, NtkProtocolAdmission.DEBUG, rejected, nativeCall)

    internal fun protocolPhaseSnapshot(): ProtocolPhase = protocol.phaseSnapshot()

    internal fun awaitDetachedWarmAsync(
        completion: (NtkDetachedWarmProof?) -> Unit
    ): AppDispatchers.TaskHandle? {
        if (protocol.phaseSnapshot() != ProtocolPhase.LIVE_DETACHED) {
            completion(null)
            return null
        }
        return try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                completion(awaitDetachedWarmBlocking())
            }
        } catch (_: RejectedExecutionException) {
            completion(null)
            null
        }
    }

    internal fun awaitDetachedWarmBlocking(): NtkDetachedWarmProof? {
        val values = runCatching {
            NtkStripNativeBridge.nativeAwaitDetachedWarm(nativeHandle)
        }.getOrNull()
        if (values == null || values.size != 9) return null
        return NtkDetachedWarmProof(
            engineGeneration = values[0],
            eglReadyNanos = values[1],
            renderPbufferReadyNanos = values[2],
            uploadPbufferReadyNanos = values[3],
            programReadyNanos = values[4],
            nativeWindowCount = values[5],
            surfaceControlAttachCount = values[6],
            windowFrameIdCount = values[7],
            windowSwapCount = values[8]
        ).takeIf {
            it.engineGeneration == engineGeneration &&
                it.isExactDetachedWarm &&
                protocol.phaseSnapshot() == ProtocolPhase.LIVE_DETACHED
        }
    }

    internal fun beginAttachAsync(
        lease: NtkNativeSurfaceLeaseTransfer,
        surfaceEpoch: Long,
        width: Int,
        height: Int,
        geometryRevision: Long,
        refreshPeriodNanos: Long,
        completion: (SurfaceAttachCompletion) -> Unit
    ): NtkSurfaceAttachKey? {
        if (surfaceEpoch <= 0L || width <= 0 || height <= 0 ||
            geometryRevision <= 0L || refreshPeriodNanos <= 0L
        ) {
            lease.close()
            return null
        }
        val attachGeneration = nextAttachGeneration.incrementAndGet()
        val key = NtkSurfaceAttachKey(
            engineGeneration,
            attachGeneration,
            surfaceEpoch
        )
        val operation = protocol.beginSurfaceAttach(key)
        if (operation == null) {
            lease.close()
            return null
        }
        protocol.withProtocolLock {
            check(attachRegistration == null)
            attachRegistration = AttachRegistration(
                operation,
                completion,
                width,
                height,
                geometryRevision
            )
        }
        val queued = runCatching {
            NtkStripNativeBridge.nativeQueueAttachLease(
                nativeHandle,
                lease.leaseId,
                attachGeneration,
                width,
                height,
                geometryRevision,
                refreshPeriodNanos,
                surfaceEpoch
            )
        }.getOrDefault(false)
        if (!queued) {
            lease.close()
            finishAttachFailure(
                key,
                operation,
                completion,
                NtkSurfaceAttachFailure.NATIVE_QUEUE_REJECTED
            )
            return null
        }
        lease.markConsumed()
        try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                finishAttachOnLifecycleLane(key, operation, completion)
            }
        } catch (_: RejectedExecutionException) {
            NtkStripNativeBridge.nativeRequestSurfaceLoss(
                nativeHandle,
                attachGeneration,
                surfaceEpoch
            )
            finishAttachFailure(
                key,
                operation,
                completion,
                NtkSurfaceAttachFailure.LIFECYCLE_EXECUTOR_REJECTED
            )
            return null
        }
        return key
    }

    private fun finishAttachOnLifecycleLane(
        key: NtkSurfaceAttachKey,
        operation: NtkAsyncSurfaceOperation,
        completion: (SurfaceAttachCompletion) -> Unit
    ) {
        try {
            val values = NtkStripNativeBridge.nativeAwaitAttach(
                nativeHandle,
                key.attachGeneration,
                key.surfaceEpoch
            )
            val code = values?.getOrNull(0)?.toInt() ?: 0
            if (values == null || values.size < 7 ||
                values[1] != key.attachGeneration ||
                values[2] != key.surfaceEpoch
            ) {
                finishAttachFailure(
                    key,
                    operation,
                    completion,
                    NtkSurfaceAttachFailure.NATIVE_ATTACH_FAILED
                )
                return
            }
            when (code) {
                1 -> {
                    var appliedRevision = values[3]
                    var appliedWidth = values[4].toInt()
                    var appliedHeight = values[5].toInt()
                    while (true) {
                        val requested = protocol.withProtocolLock {
                            attachRegistration?.takeIf {
                                it.operation === operation
                            }?.let { Triple(it.geometryRevision, it.width, it.height) }
                        } ?: run {
                            finishAttachFailure(
                                key,
                                operation,
                                completion,
                                NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                            )
                            return
                        }
                        if (requested.first == appliedRevision) break
                        val updated = NtkStripNativeBridge.nativeUpdateAttachGeometry(
                            nativeHandle,
                            key.attachGeneration,
                            key.surfaceEpoch,
                            requested.second,
                            requested.third,
                            requested.first
                        )
                        val ack = if (updated) {
                            NtkStripNativeBridge.nativeApplyResizeBeforePublish(
                                nativeHandle,
                                key.attachGeneration,
                                key.surfaceEpoch,
                                requested.first
                            )
                        } else null
                        if (ack == null || ack.size < 6 || ack[0] != 1L ||
                            ack[1] != key.attachGeneration ||
                            ack[2] != key.surfaceEpoch ||
                            ack[3] != requested.first
                        ) {
                            finishAttachFailure(
                                key,
                                operation,
                                completion,
                                NtkSurfaceAttachFailure.RESIZE_BARRIER_FAILED
                            )
                            return
                        }
                        appliedRevision = ack[3]
                        appliedWidth = ack[4].toInt()
                        appliedHeight = ack[5].toInt()
                    }
                    val publishable = protocol.completeSurfaceAttachReady(operation)
                    if (publishable) {
                        completion(
                            SurfaceAttachCompletion.Ready(
                                SurfaceAttachReady(
                                    key,
                                    appliedRevision,
                                    appliedWidth,
                                    appliedHeight,
                                    values[6]
                                )
                            )
                        )
                    }
                }
                2 -> {
                    protocol.withProtocolLock {
                        if (attachRegistration?.operation === operation) {
                            attachRegistration = null
                        }
                    }
                    protocol.completeSurfaceAttachCancelled(operation)
                    completion(SurfaceAttachCompletion.CancelledBeforeClaim(key))
                }
                3 -> {
                    // Holder loss won after the native render owner claimed the generation.
                    // Complete the async operation so the queued detach can drain, but never
                    // publish availability for this exact generation.
                    protocol.completeSurfaceAttachReady(operation)
                }
                else -> finishAttachFailure(
                    key,
                    operation,
                    completion,
                    NtkSurfaceAttachFailure.NATIVE_ATTACH_FAILED
                )
            }
        } catch (_: Throwable) {
            finishAttachFailure(
                key,
                operation,
                completion,
                NtkSurfaceAttachFailure.LIFECYCLE_TASK_FAILED
            )
        }
    }

    private fun finishAttachFailure(
        key: NtkSurfaceAttachKey,
        operation: NtkAsyncSurfaceOperation,
        completion: (SurfaceAttachCompletion) -> Unit,
        reason: NtkSurfaceAttachFailure
    ) {
        protocol.withProtocolLock {
            if (attachRegistration?.operation === operation) {
                attachRegistration = null
            }
        }
        protocol.completeSurfaceAttachFailed(operation)
        completion(SurfaceAttachCompletion.Failed(key, reason))
    }

    internal fun updateAttachGeometry(
        key: NtkSurfaceAttachKey,
        width: Int,
        height: Int,
        geometryRevision: Long
    ): Boolean {
        if (key.engineGeneration != engineGeneration ||
            width <= 0 || height <= 0 || geometryRevision <= 0L
        ) return false
        val accepted = protocol.withProtocolLock {
            val registration = attachRegistration
            if (registration == null || registration.operation.key != key ||
                geometryRevision < registration.geometryRevision
            ) false else {
                registration.width = width
                registration.height = height
                registration.geometryRevision = geometryRevision
                true
            }
        }
        val nativeAccepted = accepted && NtkStripNativeBridge.nativeUpdateAttachGeometry(
            nativeHandle,
            key.attachGeneration,
            key.surfaceEpoch,
            width,
            height,
            geometryRevision
        )
        if (nativeAccepted && protocol.phaseSnapshot() == ProtocolPhase.SURFACE_READY) {
            scheduleReadyResizeBarrier(key)
        }
        return nativeAccepted
    }

    private fun scheduleReadyResizeBarrier(key: NtkSurfaceAttachKey): Boolean {
        val disposition = protocol.withProtocolLock {
            val registration = attachRegistration
            if (protocol.phaseLocked() != ProtocolPhase.SURFACE_READY ||
                registration == null || registration.operation.key != key
            ) {
                0
            } else if (registration.readyResizeTaskActive) {
                1
            } else {
                registration.readyResizeTaskActive = true
                2
            }
        }
        if (disposition == 0) return false
        if (disposition == 1) return true
        return try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                finishReadyResizeBarrierOnLifecycleLane(key)
            }
            true
        } catch (_: RejectedExecutionException) {
            failReadySurface(
                key,
                NtkSurfaceAttachFailure.LIFECYCLE_EXECUTOR_REJECTED
            )
            false
        }
    }

    private fun finishReadyResizeBarrierOnLifecycleLane(key: NtkSurfaceAttachKey) {
        while (true) {
            val requested = protocol.withProtocolLock {
                attachRegistration?.takeIf {
                    it.operation.key == key &&
                        protocol.phaseLocked() == ProtocolPhase.SURFACE_READY
                }?.let { Triple(it.geometryRevision, it.width, it.height) }
            } ?: return
            val updated = NtkStripNativeBridge.nativeUpdateAttachGeometry(
                nativeHandle,
                key.attachGeneration,
                key.surfaceEpoch,
                requested.second,
                requested.third,
                requested.first
            )
            val ack = if (updated) {
                NtkStripNativeBridge.nativeApplyResizeBeforePublish(
                    nativeHandle,
                    key.attachGeneration,
                    key.surfaceEpoch,
                    requested.first
                )
            } else null
            if (ack == null || ack.size < 6 || ack[0] != 1L ||
                ack[1] != key.attachGeneration || ack[2] != key.surfaceEpoch ||
                ack[3] != requested.first
            ) {
                failReadySurface(key, NtkSurfaceAttachFailure.RESIZE_BARRIER_FAILED)
                return
            }
            val ready = protocol.withProtocolLock {
                val registration = attachRegistration
                if (registration == null || registration.operation.key != key ||
                    protocol.phaseLocked() != ProtocolPhase.SURFACE_READY
                ) {
                    null
                } else if (registration.geometryRevision != ack[3]) {
                    null
                } else {
                    registration.readyResizeTaskActive = false
                    SurfaceAttachReady(
                        key = key,
                        appliedGeometryRevision = ack[3],
                        width = ack[4].toInt(),
                        height = ack[5].toInt(),
                        completedNanos = SystemClock.elapsedRealtimeNanos()
                    )
                }
            }
            if (ready != null) {
                val completion = protocol.withProtocolLock {
                    attachRegistration?.takeIf {
                        it.operation.key == key
                    }?.completion
                } ?: return
                completion(SurfaceAttachCompletion.Ready(ready))
                return
            }
        }
    }

    private fun failReadySurface(
        key: NtkSurfaceAttachKey,
        reason: NtkSurfaceAttachFailure
    ) {
        val completion = protocol.withProtocolLock {
            val registration = attachRegistration
            if (registration == null || registration.operation.key != key ||
                protocol.phaseLocked() != ProtocolPhase.SURFACE_READY
            ) {
                null
            } else {
                attachRegistration = null
                protocol.failLocked()
                registration.completion
            }
        }
        completion?.invoke(SurfaceAttachCompletion.Failed(key, reason))
    }

    internal fun publishAttachedSurface(
        key: NtkSurfaceAttachKey,
        geometryRevision: Long
    ): Boolean {
        if (key.engineGeneration != engineGeneration || geometryRevision <= 0L) {
            return false
        }
        val registrationMatches = protocol.withProtocolLock {
            attachRegistration?.operation?.key == key &&
                attachRegistration?.geometryRevision == geometryRevision
        }
        if (!registrationMatches) return false
        val nativePublished = NtkStripNativeBridge.nativePublishAttach(
            nativeHandle,
            key.attachGeneration,
            key.surfaceEpoch,
            geometryRevision
        )
        if (!nativePublished || !protocol.publishSurface(key)) {
            protocol.withProtocolLock { protocol.failLocked() }
            return false
        }
        protocol.withProtocolLock { attachRegistration = null }
        return true
    }

    fun resize(
        key: NtkSurfaceAttachKey,
        width: Int,
        height: Int
    ) {
        runLiveOperation("resize", Unit) {
            NtkStripNativeBridge.nativeResize(
                nativeHandle,
                key.attachGeneration,
                key.surfaceEpoch,
                width,
                height
            )
        }
    }

    @Synchronized
    fun openDetachedPreparation(
        authority: Long,
        preparationGeneration: Long,
        manifestRevision: Long,
        manifestDigest: String
    ): NtkNativeDetachedPreparationToken? {
        if (authority <= 0L || preparationGeneration <= 0L ||
            manifestRevision < 0L ||
            !NtkStripDigests.isSha256(manifestDigest)
        ) return null
        return protocol.runOperation(
            operation = "openDetachedPreparation",
            admission = NtkProtocolAdmission.PREPARATION,
            rejected = null,
            prepareLocked = {
                val existing = currentPreparation?.takeIf {
                    it.token.authority == authority &&
                        it.token.engineGeneration == engineGeneration &&
                        it.token.preparationGeneration == preparationGeneration &&
                        it.token.manifestRevision == manifestRevision &&
                        it.token.manifestDigest == manifestDigest
                }
                if (currentPreparation != null && existing == null) null
                else NtkPreparedOperation(
                    existing?.authorityGeneration ?: ++nextAuthorityGeneration
                )
            },
            nativeCall = { candidate ->
                NtkStripNativeBridge.nativeOpenDetachedPreparation(
                    nativeHandle,
                    authority,
                    candidate,
                    preparationGeneration,
                    manifestRevision,
                    manifestDigest
                )
            },
            completeLocked = { candidate, result ->
                val values = result.getOrElse {
                    protocol.failLocked()
                    null
                }
                if (values == null || values.size != 3 || values[0] != candidate ||
                    values[1] <= 0L || values[2] <= 0L
                ) {
                    protocol.failLocked()
                    null
                } else {
                    val token = NtkNativeDetachedPreparationToken(
                        engineGeneration = engineGeneration,
                        preparationGeneration = preparationGeneration,
                        authority = authority,
                        manifestRevision = manifestRevision,
                        manifestDigest = manifestDigest,
                        tokenNonce = values[1],
                        openedAtNanos = values[2]
                    )
                    val releaseToken = NtkNativeAuthorityToken(
                        engineGeneration = engineGeneration,
                        authorityGeneration = candidate,
                        authority = authority,
                        manifestRevision = manifestRevision,
                        manifestDigest = manifestDigest,
                        geometryDigest = NtkStripDigests.sha256Tokens(
                            "ntk-native-preparation-pending-v1",
                            manifestDigest,
                            preparationGeneration.toString()
                        )
                    )
                    bindings[releaseToken.key()] = releaseToken
                    currentPreparation = PreparationBinding(token, candidate, releaseToken)
                    token
                }
            }
        )
    }

    fun installDetachedPrepared(
        install: NtkPreparedTileInstall,
        completion: (NtkDetachedPreparedTileAck?) -> Unit
    ): Boolean = installPreparedInternal(install, null, completion)

    fun installSurfacePrepared(
        install: NtkPreparedTileInstall,
        surfaceToken: NtkSurfacePreparationToken,
        completion: (NtkDetachedPreparedTileAck?) -> Unit
    ): Boolean = installPreparedInternal(install, surfaceToken, completion)

    private fun installPreparedInternal(
        install: NtkPreparedTileInstall,
        surfaceToken: NtkSurfacePreparationToken?,
        completion: (NtkDetachedPreparedTileAck?) -> Unit
    ): Boolean {
        val nativeIdentity = AtomicReference<DetachedInstallCallbackIdentity?>()
        var rejected: PreparedPublishCallback? = null
        val accepted = protocol.runOperation(
            operation = if (surfaceToken == null) {
                "installDetachedPrepared"
            } else {
                "installSurfacePrepared"
            },
            admission = NtkProtocolAdmission.PREPARATION,
            rejected = false,
            prepareLocked = {
                val preparation = currentPreparation
                if (preparation == null || preparation.token != install.token ||
                    install.identity.admission.authority != preparation.token.authority ||
                    install.identity.preparationGeneration !=
                        preparation.token.preparationGeneration ||
                    surfaceToken != null && (
                        surfaceToken.detached != preparation.token ||
                            protocol.currentSurfaceKeyLocked() != NtkSurfaceAttachKey(
                                engineGeneration,
                                surfaceToken.attachGeneration,
                                surfaceToken.surfaceEpoch
                            )
                        )
                ) null else {
                    val identity = DetachedInstallCallbackIdentity(
                        key = TileKey(
                            install.identity.admission.authority,
                            install.identity.admission.key.pageIndex,
                            install.identity.admission.key.slotIndex
                        ),
                        engineGeneration = engineGeneration,
                        authorityGeneration = preparation.authorityGeneration,
                        preparationGeneration =
                            install.identity.preparationGeneration,
                        admissionId = install.identity.admission.admissionId,
                        resourceRevision = install.identity.resourceRevision,
                        installLease = install.identity.installLease,
                        rgbaBytes = install.rgbaBytes
                    )
                    if (preparedPublishCallbacks.containsKey(identity)) null else {
                        preparedPublishCallbacks[identity] = PreparedPublishCallback(
                            install.identity,
                            completion
                        )
                        nativeIdentity.set(identity)
                        NtkPreparedOperation(identity)
                    }
                }
            },
            nativeCall = { identity ->
                if (surfaceToken == null) {
                    NtkStripNativeBridge.nativeInstallDetachedPrepared(
                        nativeHandle,
                        identity.authorityGeneration,
                        identity.key.authority,
                        identity.preparationGeneration,
                        identity.admissionId,
                        identity.key.page,
                        identity.key.slot,
                        identity.resourceRevision,
                        identity.installLease,
                        identity.rgbaBytes,
                        install.tileProof.sourceWidth,
                        install.tileProof.sourceBottom - install.tileProof.sourceTop,
                        install.tileProof.tileProofDigest,
                        install.tile.bitmap
                    )
                } else {
                    NtkStripNativeBridge.nativeInstallSurfacePrepared(
                        nativeHandle,
                        identity.authorityGeneration,
                        identity.key.authority,
                        identity.preparationGeneration,
                        surfaceToken.surfaceEpoch,
                        identity.admissionId,
                        identity.key.page,
                        identity.key.slot,
                        identity.resourceRevision,
                        identity.installLease,
                        identity.rgbaBytes,
                        install.tileProof.sourceWidth,
                        install.tileProof.sourceBottom - install.tileProof.sourceTop,
                        install.tileProof.tileProofDigest,
                        install.tile.bitmap
                    )
                }
            },
            completeLocked = { identity, result ->
                val nativeAccepted = result.getOrElse {
                    protocol.failLocked()
                    false
                }
                if (!nativeAccepted) {
                    rejected = preparedPublishCallbacks.remove(identity)
                }
                nativeAccepted
            }
        )
        if (!accepted && rejected == null) {
            nativeIdentity.get()?.let { identity ->
                protocol.withProtocolLock {
                    rejected = preparedPublishCallbacks.remove(identity)
                }
            }
        }
        rejected?.completion?.invoke(null)
        return accepted
    }

    @Synchronized
    fun adoptDetachedPreparationToSurface(
        request: NtkGeometryBindRequest,
        geometry: NtkStripGeometry,
        surface: NtkPublishedSurfaceIdentity,
        scrollTop: Long
    ): NtkGeometryBindProof? {
        if (request.geometryDigest != geometry.geometryDigest ||
            request.preGeometryRootDigest != geometry.preGeometryRootDigest ||
            surface.demandGeneration <= 0L ||
            surface.engineGeneration != engineGeneration ||
            surface.width != geometry.viewportWidthPx ||
            surface.height <= 0 ||
            request.token.authority != geometry.episode.value
        ) return null
        val tileCount = geometry.tileCount
        val pages = IntArray(tileCount)
        val slots = IntArray(tileCount)
        val widths = IntArray(tileCount)
        val heights = IntArray(tileCount)
        val tops = LongArray(tileCount)
        val bottoms = LongArray(tileCount)
        var index = 0
        geometry.pages.forEach { page ->
            page.tiles.forEach { tile ->
                pages[index] = tile.key.pageIndex
                slots[index] = tile.key.slotIndex
                widths[index] = page.asset.sourceWidth
                heights[index] = tile.sourceBottom - tile.sourceTop
                tops[index] = tile.contentTopPx
                bottoms[index] = tile.contentBottomPx
                index++
            }
        }
        return protocol.runOperation(
            operation = "adoptDetachedPreparationToSurface",
            admission = NtkProtocolAdmission.PREPARATION,
            rejected = null,
            prepareLocked = {
                currentPreparation?.takeIf { it.token == request.token }
                    ?.let(::NtkPreparedOperation)
            },
            nativeCall = { preparation ->
                NtkStripNativeBridge.nativeAdoptDetachedPreparationToSurface(
                    nativeHandle,
                    preparation.authorityGeneration,
                    request.token.authority,
                    request.token.preparationGeneration,
                    surface.demandGeneration,
                    surface.attachGeneration,
                    surface.surfaceEpoch,
                    surface.geometryRevision,
                    request.token.manifestRevision,
                    request.token.manifestDigest,
                    geometry.geometryDigest,
                    geometry.preGeometryRootDigest,
                    request.preparedInventoryDigest,
                    geometry.gpuSceneFormat.ordinal,
                    geometry.totalRgbaBytes,
                    geometry.gpuSceneDigest,
                    geometry.contentHeightPx,
                    geometry.viewportWidthPx,
                    surface.height,
                    scrollTop,
                    pages,
                    slots,
                    widths,
                    heights,
                    tops,
                    bottoms
                )
            },
            completeLocked = { preparation, result ->
                val values = result.getOrElse {
                    protocol.failLocked()
                    null
                }
                val generation = values?.getOrNull(0)?.toLongOrNull()
                val adopted = values?.getOrNull(1)?.toIntOrNull()
                val missing = values?.getOrNull(2)?.toIntOrNull()
                val preparedDigest = values?.getOrNull(3)
                val residentDigest = values?.getOrNull(4)
                val completionNanos = values?.getOrNull(5)?.toLongOrNull()
                val lastResourceNanos = values?.getOrNull(6)?.toLongOrNull()
                if (values == null || values.size != 7 ||
                    generation != preparation.authorityGeneration ||
                    adopted == null || adopted < 0 || missing == null || missing < 0 ||
                    preparedDigest != request.preparedInventoryDigest ||
                    residentDigest == null || !NtkStripDigests.isSha256(residentDigest) ||
                    completionNanos == null || completionNanos <= 0L ||
                    lastResourceNanos == null || lastResourceNanos !in 0L..completionNanos
                ) {
                    protocol.failLocked()
                    null
                } else {
                    val proof = NtkGeometryBindProof(
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
                        adoptedPreparedTileCount = adopted,
                        missingGeometrySlotCount = missing,
                        preparedInventoryDigest = preparedDigest,
                        residentInventoryDigest = residentDigest,
                        geometryBindCompletionNanos = completionNanos,
                        lastResourceCompletionNanos = lastResourceNanos
                    )
                    val token = NtkNativeAuthorityToken(
                        engineGeneration,
                        preparation.authorityGeneration,
                        request.token.authority,
                        request.token.manifestRevision,
                        request.token.manifestDigest,
                        request.geometryDigest
                    )
                    bindings[token.key()] = token
                    currentBinding = token
                    currentBindingMirror.set(token)
                    bindSeal = BindSeal(
                        token,
                        geometry.gpuSceneFormat,
                        geometry.tileCount,
                        geometry.totalRgbaBytes,
                        geometry.gpuSceneDigest
                    )
                    stageProof = null
                    proof
                }
            }
        )
    }

    fun closePreparationAdmissions(
        token: NtkNativeDetachedPreparationToken
    ): Boolean =
        protocol.runOperation(
            operation = "closePreparationAdmissions",
            admission = NtkProtocolAdmission.PREPARATION,
            rejected = false,
            prepareLocked = {
                val preparation = currentPreparation
                val binding = currentBinding
                if (preparation?.token != token || binding == null ||
                    binding.authorityGeneration != preparation.authorityGeneration ||
                    binding.authority != token.authority
                ) null else NtkPreparedOperation(preparation)
            },
            nativeCall = { preparation ->
                NtkStripNativeBridge.nativeClosePreparationAdmissions(
                    nativeHandle,
                    preparation.authorityGeneration,
                    preparation.token.authority
                )
            },
            completeLocked = { _, result ->
                result.getOrElse {
                    protocol.failLocked()
                    false
                }
            }
        )

    @Synchronized
    fun bind(
        authority: Long,
        geometry: NtkStripGeometry,
        viewportHeight: Int,
        scrollTop: Long
    ): NtkNativeAuthorityToken? {
        return bind(
            authority,
            geometry,
            viewportHeight,
            scrollTop,
            manifestRevision = geometry.manifestRevision,
            manifestDigest = geometry.manifestDigest,
            geometryDigest = geometry.geometryDigest
        )
    }

    internal fun setContextLossForTesting() {
        runLiveOperation("setContextLossForTesting", Unit) {
            NtkStripNativeBridge.nativeSetContextLossForTesting(nativeHandle)
        }
    }

    internal fun setContextLossDuringDetachForTesting() {
        runLiveOperation("setContextLossDuringDetachForTesting", Unit) {
            NtkStripNativeBridge.nativeSetContextLossDuringDetachForTesting(nativeHandle)
        }
    }

    @Synchronized
    fun bind(
        authority: Long,
        geometry: NtkStripGeometry,
        viewportHeight: Int,
        scrollTop: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String
    ): NtkNativeAuthorityToken? {
        if (authority <= 0L || manifestRevision < 0L ||
            !NtkStripDigests.isSha256(manifestDigest) ||
            !NtkStripDigests.isSha256(geometryDigest)
        ) return null
        val tileCount = geometry.pages.sumOf { it.tiles.size }
        val pages = IntArray(tileCount)
        val slots = IntArray(tileCount)
        val widths = IntArray(tileCount)
        val heights = IntArray(tileCount)
        val contentTops = LongArray(tileCount)
        val contentBottoms = LongArray(tileCount)
        var index = 0
        geometry.pages.forEach { page ->
            page.tiles.forEach { tile ->
                pages[index] = tile.key.pageIndex
                slots[index] = tile.key.slotIndex
                widths[index] = page.asset.sourceWidth
                heights[index] = tile.sourceBottom - tile.sourceTop
                contentTops[index] = tile.contentTopPx
                contentBottoms[index] = tile.contentBottomPx
                index++
            }
        }
        var cancelledStageCompletion: ((NtkStageProof?) -> Unit)? = null
        val token = protocol.runOperation(
            operation = "bind",
            admission = NtkProtocolAdmission.LIVE,
            rejected = null,
            prepareLocked = {
                val existing = currentBinding
                val exactExisting = existing?.takeIf {
                    it.authority == authority && it.manifestRevision == manifestRevision &&
                        it.manifestDigest == manifestDigest &&
                        it.geometryDigest == geometryDigest && tokenUsableLocked(it) &&
                        !releasedTokens.contains(it.key())
                }
                val candidate = exactExisting?.authorityGeneration ?: ++nextAuthorityGeneration
                NtkPreparedOperation(candidate)
            },
            nativeCall = { candidate ->
                NtkStripNativeBridge.nativeBind(
                    nativeHandle,
                    authority,
                    candidate,
                    manifestRevision,
                    manifestDigest,
                    geometryDigest,
                    geometry.preGeometryRootDigest,
                    geometry.gpuSceneFormat.ordinal,
                    geometry.totalRgbaBytes,
                    geometry.gpuSceneDigest,
                    geometry.contentHeightPx,
                    geometry.viewportWidthPx,
                    viewportHeight,
                    scrollTop,
                    pages,
                    slots,
                    widths,
                    heights,
                    contentTops,
                    contentBottoms
                )
            },
            completeLocked = { candidate, result ->
                val acceptedGeneration = result.getOrElse {
                    protocol.failLocked()
                    0L
                }
                if (acceptedGeneration <= 0L || acceptedGeneration != candidate) {
                    protocol.failLocked()
                    null
                } else {
                    val accepted = NtkNativeAuthorityToken(
                        engineGeneration,
                        acceptedGeneration,
                        authority,
                        manifestRevision,
                        manifestDigest,
                        geometryDigest
                    )
                    bindings[accepted.key()] = accepted
                    val previousBinding = currentBinding
                    currentBinding = accepted
                    currentBindingMirror.set(accepted)
                    if (previousBinding != null && previousBinding != accepted) {
                        cancelledStageCompletion = stageRequest?.completion
                        stageRequest = null
                        gpuInvariants.keys.removeAll {
                            it.engineGeneration == previousBinding.engineGeneration &&
                                it.authorityGeneration == previousBinding.authorityGeneration
                        }
                        frameSnapshot.set(null)
                        preSubmitViewportGap.set(0L)
                        firstMainIngressNanos.set(0L)
                    }
                    val nextSeal = BindSeal(
                        accepted,
                        geometry.gpuSceneFormat,
                        geometry.tileCount,
                        geometry.totalRgbaBytes,
                        geometry.gpuSceneDigest
                    )
                    if (bindSeal != nextSeal) stageProof = null
                    bindSeal = nextSeal
                    accepted
                }
            }
        )
        cancelledStageCompletion?.let { completion ->
            NtkReleaseCompletion.dispatch { completion(null) }
        }
        return token
    }

    fun disarm(authority: Long): Boolean = protocol.runOperation(
        operation = "disarm",
        admission = NtkProtocolAdmission.LIVE,
        rejected = false,
        prepareLocked = {
            currentBinding?.takeIf { tokenUsableLocked(it) && it.authority == authority }
                ?.let(::NtkPreparedOperation)
        },
        nativeCall = { token ->
            NtkStripNativeBridge.nativeDisarm(
                nativeHandle,
                token.authorityGeneration,
                authority
            )
        },
        completeLocked = { _, result -> result.getOrElse { protocol.failLocked(); false } }
    )

    fun upload(
        identity: InstallIdentity,
        bitmap: Bitmap,
        contentTop: Long,
        contentBottom: Long,
        completion: (TileResidentResult) -> Unit
    ): Boolean {
        if (identity.surfaceEpoch <= 0L || identity.admissionId <= 0L ||
            identity.resourceRevision <= 0L || identity.installLease <= 0L ||
            identity.rgbaBytes <= 0L || bitmap.allocationByteCount.toLong() < identity.rgbaBytes
        ) return false
        var rejectedCompletion: ((TileResidentResult) -> Unit)? = null
        val accepted = protocol.runOperation(
            operation = "upload",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = {
                val token = currentBinding
                if (token == null || !tokenUsableLocked(token) ||
                    token.engineGeneration != identity.engineGeneration ||
                    token.authority != identity.key.authority ||
                    token.authorityGeneration != identity.authorityGeneration ||
                    publishCallbacks.containsKey(identity)
                ) null else {
                    publishCallbacks[identity] = completion
                    NtkPreparedOperation(Unit)
                }
            },
            nativeCall = {
                NtkStripNativeBridge.nativeUpload(
                    nativeHandle,
                    identity.authorityGeneration,
                    identity.key.authority,
                    identity.surfaceEpoch,
                    identity.admissionId,
                    identity.key.page,
                    identity.key.slot,
                    identity.resourceRevision,
                    identity.installLease,
                    identity.rgbaBytes,
                    bitmap,
                    contentTop,
                    contentBottom
                )
            },
            completeLocked = { _, result ->
                val nativeAccepted = result.getOrElse {
                    protocol.failLocked()
                    false
                }
                if (!nativeAccepted) rejectedCompletion = publishCallbacks.remove(identity)
                nativeAccepted
            }
        )
        rejectedCompletion?.invoke(TileResidentResult(identity, 0L, false))
        return accepted
    }

    fun commitProtection(
        commit: NtkStripProtectionCommit,
        completion: (NtkStripProtectionAck) -> Unit
    ): Boolean {
        data class PreparedProtection(
            val token: NtkNativeAuthorityToken,
            val identity: ProtectionIdentity
        )
        var rejectedCallback: ProtectionCallback? = null
        val accepted = protocol.runOperation(
            operation = "commitProtection",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = {
                val token = currentBinding
                if (token == null || token.authority != commit.authority ||
                    !tokenUsableLocked(token)
                ) null else {
                    val identity = ProtectionIdentity(
                        token.engineGeneration,
                        token.authorityGeneration,
                        commit.authority,
                        commit.surfaceEpoch,
                        commit.demandEpoch,
                        commit.protectedDigest
                    )
                    if (protectionCallbacks.containsKey(identity)) null else {
                        protectionCallbacks[identity] = ProtectionCallback(commit, completion)
                        NtkPreparedOperation(PreparedProtection(token, identity))
                    }
                }
            },
            nativeCall = { prepared ->
                NtkStripNativeBridge.nativeCommitProtection(
                    nativeHandle,
                    prepared.token.authorityGeneration,
                    commit.authority,
                    commit.surfaceEpoch,
                    commit.demandEpoch,
                    commit.basisFrameSequence,
                    commit.basisInputSequence,
                    commit.direction.toNativeValue(),
                    commit.protectedTileOrdinals,
                    commit.protectedDigest
                )
            },
            completeLocked = { prepared, result ->
                val nativeAccepted = result.getOrElse {
                    protocol.failLocked()
                    false
                }
                if (!nativeAccepted) {
                    rejectedCallback = protectionCallbacks.remove(prepared.identity)
                }
                nativeAccepted
            }
        )
        if (!accepted) {
            val callback = rejectedCallback ?: ProtectionCallback(commit, completion)
            callback.completion(NtkStripProtectionAck(callback.commit, 0L, false))
        }
        return accepted
    }

    fun retire(
        intent: NtkStripRetireIntent,
        result: (NtkStripRetireResultAck) -> Unit,
        freed: (NtkStripTileFreedAck) -> Unit
    ): Boolean {
        data class PreparedRetire(
            val token: NtkNativeAuthorityToken,
            val identity: RetireIdentity
        )
        var rejectedCallbacks: RetireCallbacks? = null
        val accepted = protocol.runOperation(
            operation = "retire",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = {
                val token = currentBinding
                if (token == null || token.authority != intent.authority ||
                    !tokenUsableLocked(token)
                ) null else {
                    val identity = RetireIdentity(
                        token.engineGeneration,
                        token.authorityGeneration,
                        intent.authority,
                        intent.surfaceEpoch,
                        intent.policySurfaceEpoch,
                        intent.demandEpoch,
                        intent.key.pageIndex,
                        intent.key.slotIndex,
                        intent.resourceRevision,
                        intent.installLease,
                        intent.retireLease,
                        intent.protectedDigest
                    )
                    if (retireCallbacks.containsKey(identity)) null else {
                        retireCallbacks[identity] = RetireCallbacks(intent, result, freed)
                        NtkPreparedOperation(PreparedRetire(token, identity))
                    }
                }
            },
            nativeCall = { prepared ->
                NtkStripNativeBridge.nativeRetire(
                    nativeHandle,
                    prepared.token.authorityGeneration,
                    intent.authority,
                    intent.surfaceEpoch,
                    intent.policySurfaceEpoch,
                    intent.demandEpoch,
                    intent.basisFrameSequence,
                    intent.basisInputSequence,
                    intent.key.pageIndex,
                    intent.key.slotIndex,
                    intent.resourceRevision,
                    intent.installLease,
                    intent.retireLease,
                    intent.rgbaBytes,
                    intent.protectedDigest
                )
            },
            completeLocked = { prepared, nativeResult ->
                val nativeAccepted = nativeResult.getOrElse {
                    protocol.failLocked()
                    false
                }
                if (!nativeAccepted) rejectedCallbacks = retireCallbacks.remove(prepared.identity)
                nativeAccepted
            }
        )
        if (!accepted) {
            val callbacks = rejectedCallbacks ?: RetireCallbacks(intent, result, freed)
            callbacks.result(NtkStripRetireResult(
                callbacks.intent,
                NtkStripRetireResultCode.FAILED,
                latestFrameSnapshot()?.sceneVersion ?: 0L
            ))
        }
        return accepted
    }

    fun stage(
        authority: Long,
        corridorStartPx: Long,
        corridorEndPx: Long,
        stageNonce: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String,
        completion: (NtkStageProof?) -> Unit
    ): Boolean {
        fun reject(): Boolean {
            completion(null)
            return false
        }
        if (corridorStartPx < 0L || corridorEndPx <= corridorStartPx || stageNonce <= 0L) {
            return reject()
        }
        data class PreparedStage(
            val request: StageRequest,
            val cachedProof: NtkStageProof?
        )
        var deliveredProof: NtkStageProof? = null
        var deliverCompletion = false
        val accepted = protocol.runOperation(
            operation = "stage",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = {
                val seal = bindSeal
                if (seal == null || seal.token.authority != authority ||
                    seal.token.manifestRevision != manifestRevision ||
                    seal.token.manifestDigest != manifestDigest ||
                    seal.token.geometryDigest != geometryDigest ||
                    !tokenUsableLocked(seal.token) || stageRequest != null
                ) null else {
                    val request = StageRequest(
                        seal.token,
                        corridorStartPx,
                        corridorEndPx,
                        stageNonce,
                        manifestRevision,
                        manifestDigest,
                        geometryDigest,
                        seal.gpuSceneFormat,
                        seal.gpuSceneTextureCount,
                        seal.gpuSceneLogicalBytes,
                        seal.gpuSceneDigest,
                        completion
                    )
                    stageRequest = request
                    val cached = stageProof?.takeIf { current ->
                        current.authority == authority && current.stageNonce == stageNonce &&
                            current.corridorStartPx == corridorStartPx &&
                            current.corridorEndPx == corridorEndPx &&
                            current.compositionLatchNanos > 0L &&
                            current.gpuSceneCapacityProof.let { gpu ->
                                gpu.isExact && gpu.format == seal.gpuSceneFormat &&
                                    gpu.expectedTextureCount == seal.gpuSceneTextureCount &&
                                    gpu.residentTextureCount == seal.gpuSceneTextureCount &&
                                    gpu.expectedLogicalBytes == seal.gpuSceneLogicalBytes &&
                                    gpu.residentLogicalBytes == seal.gpuSceneLogicalBytes &&
                                    gpu.sceneDigest == seal.gpuSceneDigest
                            }
                    }
                    NtkPreparedOperation(PreparedStage(request, cached))
                }
            },
            nativeCall = { prepared ->
                prepared.cachedProof != null || NtkStripNativeBridge.nativeStage(
                    nativeHandle,
                    prepared.request.token.authorityGeneration,
                    authority,
                    corridorStartPx,
                    corridorEndPx,
                    stageNonce
                )
            },
            completeLocked = { prepared, result ->
                val nativeAccepted = result.getOrElse {
                    protocol.failLocked()
                    false
                }
                if (prepared.cachedProof != null && stageRequest === prepared.request) {
                    stageRequest = null
                    deliveredProof = prepared.cachedProof
                    deliverCompletion = true
                } else if (!nativeAccepted && stageRequest === prepared.request) {
                    stageRequest = null
                    deliverCompletion = true
                }
                nativeAccepted
            }
        )
        if (deliverCompletion) completion(deliveredProof)
        if (!accepted && !deliverCompletion) completion(null)
        return accepted
    }

    fun activate(authority: Long, stageNonce: Long): Boolean {
        var localRejection = ""
        var nativeInvoked = false
        val accepted = protocol.runOperation(
            operation = "activate",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = {
                val token = currentBinding
                val proof = stageProof
                val seal = bindSeal
                val tokenUsable = token != null && tokenUsableLocked(token)
                val gpuExact = proof?.gpuSceneCapacityProof?.let { gpu ->
                    seal != null && gpu.isExact && gpu.format == seal.gpuSceneFormat &&
                        gpu.expectedTextureCount == seal.gpuSceneTextureCount &&
                        gpu.residentTextureCount == seal.gpuSceneTextureCount &&
                        gpu.expectedLogicalBytes == seal.gpuSceneLogicalBytes &&
                        gpu.residentLogicalBytes == seal.gpuSceneLogicalBytes &&
                        gpu.sceneDigest == seal.gpuSceneDigest
                } == true
                if (token != null && tokenUsable && token.authority == authority &&
                    seal?.token == token && proof?.authority == authority &&
                    proof.stageNonce == stageNonce && gpuExact
                ) {
                    NtkPreparedOperation(token)
                } else {
                    localRejection = "phase=${protocol.phaseLocked()},token=${token != null}," +
                        "usable=$tokenUsable,authority=${token?.authority}/$authority," +
                        "seal=${seal != null},sealToken=${seal?.token == token}," +
                        "proof=${proof != null},proofAuthority=${proof?.authority}," +
                        "nonce=${proof?.stageNonce}/$stageNonce,gpuExact=$gpuExact"
                    null
                }
            },
            nativeCall = { token ->
                nativeInvoked = true
                NtkStripNativeBridge.nativeActivate(
                    nativeHandle,
                    token.authorityGeneration,
                    authority,
                    stageNonce
                )
            },
            completeLocked = { _, result -> result.getOrElse { protocol.failLocked(); false } }
        )
        if (!accepted && !nativeInvoked) {
            Log.e(
                "NtkStripRenderEngine",
                "activation rejected before native ${localRejection.ifEmpty { "protocol admission closed" }}"
            )
        }
        return accepted
    }

    fun touchReceipt(action: Int, eventTimeNanos: Long, x: Float, y: Float, pointerId: Int): Long {
        return protocol.runOperation(
            operation = "touch",
            admission = NtkProtocolAdmission.LIVE,
            rejected = 0L,
            prepareLocked = {
                currentBinding?.takeIf(::tokenUsableLocked)?.let { token ->
                    val ingressNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        SystemClock.uptimeNanos()
                    } else {
                        SystemClock.uptimeMillis() * 1_000_000L
                    }
                    firstMainIngressNanos.compareAndSet(0L, ingressNanos)
                    NtkPreparedOperation(token)
                }
            },
            nativeCall = { token ->
                NtkStripNativeBridge.nativeTouch(
                    nativeHandle,
                    token.authorityGeneration,
                    token.authority,
                    action,
                    eventTimeNanos,
                    x,
                    y,
                    pointerId
                )
            },
            completeLocked = { _, result -> result.getOrElse { protocol.failLocked(); 0L } }
        )
    }

    fun releaseAuthority(
        request: NtkAuthorityReleaseRequest,
        completion: (NtkNativeAuthorityReleaseAck) -> Unit
    ): Boolean {
        val key = request.token.key()
        if (request.token.engineGeneration != engineGeneration ||
            request.reducerSurfaceEpoch < 0L || request.releaseNonce <= 0L
        ) return false
        return protocol.runOperation(
            operation = "releaseAuthority",
            admission = NtkProtocolAdmission.RELEASE,
            rejected = false,
            prepareLocked = {
                val phase = protocol.phaseLocked()
                val exactToken = when (phase) {
                    ProtocolPhase.LIVE_DETACHED,
                    ProtocolPhase.SURFACE_ATTACHING,
                    ProtocolPhase.SURFACE_READY,
                    ProtocolPhase.LIVE_ATTACHED -> bindings[key]
                    ProtocolPhase.RETIRED_BLOCKED,
                    ProtocolPhase.RETIRED_DISPATCHABLE -> frozenRetiredTokens[key]
                    else -> null
                }
                val claimable = phase == ProtocolPhase.LIVE_DETACHED ||
                    phase == ProtocolPhase.SURFACE_ATTACHING ||
                    phase == ProtocolPhase.SURFACE_READY ||
                    phase == ProtocolPhase.LIVE_ATTACHED ||
                    claimableRetiredProofKeys.contains(key)
                if (exactToken != request.token || !claimable ||
                    releasedTokens.contains(key) || releasingTokens.contains(key) ||
                    releaseRegistrations.containsKey(key)
                ) null else {
                    val registration = ReleaseRegistration(request, completion)
                    registration.nativeCallActive = true
                    releaseRegistrations[key] = registration
                    releasingTokens += key
                    NtkPreparedOperation(registration)
                }
            },
            nativeCall = {
                NtkStripNativeBridge.nativeReleaseAuthority(
                    nativeHandle,
                    this,
                    request.token.engineGeneration,
                    request.token.authorityGeneration,
                    request.token.authority,
                    request.reducerSurfaceEpoch,
                    request.releaseNonce
                )
            },
            completeLocked = { registration, nativeResult ->
                registration.nativeCallActive = false
                val disposition = nativeResult.getOrElse {
                    protocol.failLocked()
                    NTK_RELEASE_REJECTED
                }
                when (disposition) {
                    NTK_RELEASE_REJECTED -> {
                        if (registration.stagedAck != null || registration.nativeDispatchable) {
                            failedTokens += key
                            protocol.failLocked()
                        }
                        releaseRegistrations.remove(key, registration)
                        releasingTokens.remove(key)
                        false
                    }
                    NTK_RELEASE_ACCEPTED_ASYNC -> {
                        scheduleIfDispatchableLocked(key, registration)
                        true
                    }
                    NTK_RELEASE_ACKED_SYNCHRONOUSLY -> {
                        if (registration.stagedAck == null || !registration.nativeDispatchable) {
                            failedTokens += key
                            releaseRegistrations.remove(key, registration)
                            releasingTokens.remove(key)
                            protocol.failLocked()
                            false
                        } else {
                            scheduleIfDispatchableLocked(key, registration)
                            true
                        }
                    }
                    else -> {
                        failedTokens += key
                        releaseRegistrations.remove(key, registration)
                        releasingTokens.remove(key)
                        protocol.failLocked()
                        false
                    }
                }
            }
        )
    }

    private fun scheduleIfDispatchableLocked(
        key: AuthorityTokenKey,
        registration: ReleaseRegistration
    ) {
        scheduleNtkReleaseCompletionLocked(
            protocol,
            releaseRegistrations,
            key,
            registration
        )
    }

    private fun rescheduleDispatchableCompletionsLocked() {
        check(protocol.externalCompletionDispatchAllowedLocked())
        releaseRegistrations.forEach { (key, registration) ->
            scheduleIfDispatchableLocked(key, registration)
        }
    }

    internal fun finishContextLossHandoff() {
        protocol.withProtocolLock {
            check(protocol.phaseLocked() == ProtocolPhase.RETIRED_BLOCKED) {
                "Only a blocked retired proof can finish a context-loss handoff"
            }
            protocol.setPhaseLocked(ProtocolPhase.RETIRED_DISPATCHABLE)
            rescheduleDispatchableCompletionsLocked()
        }
    }

    internal fun isRetiredProofOnly(): Boolean = when (protocol.phaseSnapshot()) {
        ProtocolPhase.RETIRED_BLOCKED,
        ProtocolPhase.RETIRED_DISPATCHABLE -> true
        else -> false
    }

    internal fun canCloseRetiredProof(): Boolean = protocol.withProtocolLock {
        protocol.phaseLocked() == ProtocolPhase.RETIRED_DISPATCHABLE &&
            claimableRetiredProofKeys.isEmpty() && releaseRegistrations.isEmpty() &&
            remainingOperationalKotlinCallbacksLocked() == 0
    }

    internal fun transferToProofRegistry() {
        protocol.withProtocolLock {
            check(protocol.phaseLocked() == ProtocolPhase.RETIRED_BLOCKED ||
                protocol.phaseLocked() == ProtocolPhase.RETIRED_DISPATCHABLE)
            check(remainingOperationalKotlinCallbacksLocked() == 0) {
                "A proof tombstone retained an operational Kotlin callback"
            }
            frameListener = null
            preSubmitViewportGapListener = null
        }
        NtkRetiredProofRegistry.adopt(this)
    }

    internal fun closeRetiredProofIfComplete(): Boolean {
        if (!canCloseRetiredProof()) return false
        close()
        return protocol.phaseSnapshot() == ProtocolPhase.CLOSED
    }

    internal fun isFailedNativeHandleOwner(): Boolean =
        protocol.phaseSnapshot() == ProtocolPhase.FAILED

    internal fun isClosingNativeHandleOwner(): Boolean =
        protocol.phaseSnapshot() == ProtocolPhase.CLOSING

    internal fun destroyIfLiveAtFinalViewTeardown() {
        when (protocol.phaseSnapshot()) {
            ProtocolPhase.LIVE_DETACHED -> close()
            ProtocolPhase.FAILED -> {
                close()
                if (protocol.phaseSnapshot() == ProtocolPhase.FAILED) {
                    NtkFailedNativeHandleRegistry.adopt(this)
                }
            }
            ProtocolPhase.RETIRED_BLOCKED,
            ProtocolPhase.RETIRED_DISPATCHABLE -> {
                if (!closeRetiredProofIfComplete()) transferToProofRegistry()
            }
            else -> Unit
        }
    }

    internal fun retiredBackendDebugSnapshot(): RetiredBackendDebugSnapshot? {
        val values = runDebugOperation("debugRetiredBackend", LongArray(0)) {
            NtkStripNativeBridge.nativeDebugRetiredBackend(nativeHandle)
        }
        if (values.size != 10) return null
        return RetiredBackendDebugSnapshot(
            values[0], values[1], values[2], values[3], values[4],
            values[5], values[6], values[7], values[8], values[9]
        )
    }

    internal fun currentToken(): NtkNativeAuthorityToken? = currentBindingMirror.get()

    internal fun currentReleaseToken(): NtkNativeAuthorityToken? = protocol.withProtocolLock {
        currentBinding ?: currentPreparation?.releaseToken
    }

    internal fun frozenRetiredTokensForTesting(): List<NtkNativeAuthorityToken> =
        protocol.withProtocolLock { frozenRetiredTokens.values.toList() }

    /**
     * Proof-only generations no longer own a live native renderer, so native inventory queries
     * are intentionally unavailable after context loss. Expose the exact Kotlin terminal ledger
     * instead of trying to reacquire a destroyed backend merely to verify callback ordering.
     */
    internal fun hasReleasedAuthorityProofForTesting(token: NtkNativeAuthorityToken): Boolean {
        if (token.engineGeneration != engineGeneration) return false
        val key = token.key()
        return protocol.withProtocolLock {
            releasedTokens.contains(key) && !failedTokens.contains(key) &&
                !releasingTokens.contains(key) && !bindings.containsKey(key) &&
                !claimableRetiredProofKeys.contains(key)
        }
    }

    internal fun nativeHandleIdentityForTesting(): Long = nativeHandle

    internal fun lifecycleDebugSnapshot(): LifecycleDebugSnapshot? {
        val values = runDebugOperation("debugLifecycle", LongArray(0)) {
            NtkStripNativeBridge.nativeDebugLifecycleCounters(nativeHandle)
        }
        if (values.size != 21) return null
        return LifecycleDebugSnapshot(
            values[0], values[1], values[2], values[3], values[4],
            values[5], values[6], values[7], values[8] == 1L, values[9] == 1L,
            values[10], values[11], values[12], values[13], values[14], values[15],
            values[16], values[17], values[18], values[19], values[20]
        )
    }

    internal fun startupLifecycleDebugSnapshot(): StartupLifecycleDebugSnapshot? {
        val values = runDebugOperation("debugStartupLifecycle", LongArray(0)) {
            NtkStripNativeBridge.nativeDebugStartupLifecycle(nativeHandle)
        }
        if (values.size != 28) return null
        return StartupLifecycleDebugSnapshot(
            values[0], values[1], values[2], values[3], values[4], values[5],
            values[6], values[7], values[8], values[9], values[10], values[11],
            values[12], values[13], values[14], values[15], values[16], values[17],
            values[18], values[19], values[20], values[21], values[22], values[23],
            values[24], values[25], values[26], values[27]
        )
    }

    internal fun schedulerDebugSnapshot(): NtkSchedulerDebugSnapshot? {
        val values = runDebugOperation("debugScheduler", LongArray(0)) {
            NtkStripNativeBridge.nativeDebugSchedulerCounters(nativeHandle)
        }
        if (values.size != 9) return null
        return NtkSchedulerDebugSnapshot(
            values[0], values[1], values[2], values[3], values[4],
            values[5], values[6], values[7], values[8]
        )
    }

    internal fun authorityInventoryDebugSnapshot(
        token: NtkNativeAuthorityToken
    ): AuthorityInventoryDebugSnapshot? {
        if (token.engineGeneration != engineGeneration) return null
        val values = runDebugOperation("debugAuthorityInventory", LongArray(0)) {
            NtkStripNativeBridge.nativeDebugAuthorityInventory(
                nativeHandle,
                token.authorityGeneration,
                token.authority
            )
        }
        if (values.size != 10) return null
        return AuthorityInventoryDebugSnapshot(
            values[0], values[1], values[2], values[3], values[4],
            values[5], values[6], values[7], values[8] == 1L, values[9] == 1L
        )
    }

    fun touch(action: Int, eventTimeNanos: Long, x: Float, y: Float, pointerId: Int): Boolean =
        touchReceipt(action, eventTimeNanos, x, y, pointerId) < 0L

    fun latestFrameSnapshot(): FrameSnapshot? = frameSnapshot.get()

    private fun publishLatestFrameSnapshot(candidate: FrameSnapshot): Boolean {
        val candidateKey = NtkFrameOrderKey(candidate.surfaceEpoch, candidate.frameSequence)
        while (true) {
            val current = frameSnapshot.get()
            if (current != null &&
                current.engineGeneration == candidate.engineGeneration &&
                current.authorityGeneration == candidate.authorityGeneration &&
                !isStrictlyNewerNtkFrame(
                    candidateKey,
                    NtkFrameOrderKey(current.surfaceEpoch, current.frameSequence)
                )
            ) return false
            if (frameSnapshot.compareAndSet(current, candidate)) return true
        }
    }

    fun resetInputTelemetry() {
        firstMainIngressNanos.set(0L)
        runLiveOperation("resetInputTelemetry", Unit) {
            NtkStripNativeBridge.nativeResetInputTelemetry(nativeHandle)
        }
    }

    fun firstMainIngressNanos(): Long {
        val direct = runLiveOperation("firstMainIngressNanos", 0L) {
            NtkStripNativeBridge.nativeFirstMainIngressNanos(nativeHandle)
        }
        return if (direct > 0L) direct else firstMainIngressNanos.get()
    }

    fun latestSuccessfulSwapInputEventNanos(): Long = runLiveOperation(
        "latestSuccessfulSwapInputEventNanos",
        0L
    ) {
        NtkStripNativeBridge.nativeLatestSuccessfulSwapInputEventNanos(nativeHandle)
    }

    fun latestDeliveredLatchedInputEventNanos(): Long = runLiveOperation(
        "latestDeliveredLatchedInputEventNanos",
        0L
    ) {
        NtkStripNativeBridge.nativeLatestDeliveredLatchedInputEventNanos(nativeHandle)
    }

    fun preSubmitViewportGap(): Long {
        val direct = runLiveOperation("preSubmitViewportGap", 0L) {
            NtkStripNativeBridge.nativePreSubmitViewportGap(nativeHandle)
        }
        return maxOf(direct, preSubmitViewportGap.get())
    }

    fun requestRender() {
        runLiveOperation("requestRender", Unit) {
            NtkStripNativeBridge.nativeRequestRender(nativeHandle)
        }
    }

    internal fun beginSurfaceLoss(
        key: NtkSurfaceAttachKey,
        reason: NtkSurfaceLossReason,
        completion: (StageRevocation) -> Unit
    ): SurfaceRevocation? {
        if (key.engineGeneration != engineGeneration) return null
        var revokedStageProof: NtkStageProof? = null
        var revokedStageRequest: StageRequest? = null
        val ticket = protocol.closeSurfaceAdmission(
            key = key,
            onAdmissionClosedLocked = {
                releasedDuringHandoffTokens.clear()
                revokedStageProof = stageProof
                revokedStageRequest = stageRequest
                stageProof = null
                stageRequest = null
            }
        ) ?: return null
        val nativeDisposition = runCatching {
            NtkStripNativeBridge.nativeRequestSurfaceLoss(
                nativeHandle,
                key.attachGeneration,
                key.surfaceEpoch
            )
        }.getOrDefault(4)
        if (nativeDisposition == 4) {
            protocol.withProtocolLock { protocol.failLocked() }
            return null
        }
        val revocation = SurfaceRevocation(
            key,
            revokedStageProof,
            revokedStageRequest != null,
            reason
        )
        protocol.withProtocolLock {
            surfaceLossRegistration = SurfaceLossRegistration(
                ticket,
                revocation,
                revokedStageRequest,
                nativeDisposition,
                completion
            )
        }
        try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                finishSurfaceLossOnLifecycleLane(ticket)
            }
        } catch (_: RejectedExecutionException) {
            protocol.withProtocolLock {
                protocol.failLocked()
                surfaceLossRegistration = null
            }
            val failed = failedDetachResult(key.surfaceEpoch)
            completion(
                StageRevocation(
                    revokedStageProof,
                    revokedStageRequest != null,
                    failed
                )
            )
        }
        return revocation
    }

    private fun finishSurfaceLossOnLifecycleLane(ticket: NtkDetachTicket) {
        val registration = protocol.withProtocolLock {
            surfaceLossRegistration?.takeIf { it.ticket == ticket }
        } ?: return
        val preparation = protocol.awaitDetachQuiescenceAndPrepare(
            ticket = ticket,
            prepareQuiescentLocked = {
                val exactAuthority = HashMap<AuthorityTokenKey, NtkNativeAuthorityToken>()
                fun mergeAuthority(token: NtkNativeAuthorityToken) {
                    val previous = exactAuthority.putIfAbsent(token.key(), token)
                    check(previous == null || previous == token) {
                        "Detach quiescent snapshot contains conflicting authority metadata"
                    }
                }
                bindings.values.forEach(::mergeAuthority)
                releasedDuringHandoffTokens.values.forEach(::mergeAuthority)
                val prepared = DetachPreparation(
                    registration.revocation.proof,
                    registration.stageRequest,
                    exactAuthority.values.sortedWith(
                        compareBy<NtkNativeAuthorityToken> { it.engineGeneration }
                            .thenBy { it.authorityGeneration }
                            .thenBy { it.authority }
                    )
                )
                prepared
            }
        ) ?: run {
            registration.completion(
                StageRevocation(
                    registration.revocation.proof,
                    registration.revocation.requestWasPending,
                    failedDetachResult(ticket.key.surfaceEpoch)
                )
            )
            return
        }
        val admissionSnapshot = preparation.value.authoritySnapshot
        val admissionSnapshotByKey = admissionSnapshot.associateBy { it.key() }
        check(admissionSnapshotByKey.size == admissionSnapshot.size) {
            "Detach admission snapshot contains duplicate authority keys"
        }
        val admissionKeyTriples = LongArray(admissionSnapshot.size * 3)
        admissionSnapshot.forEachIndexed { index, token ->
            val offset = index * 3
            admissionKeyTriples[offset] = token.engineGeneration
            admissionKeyTriples[offset + 1] = token.authorityGeneration
            admissionKeyTriples[offset + 2] = token.authority
        }
        val admissionDigest = NtkRetiredAuthorityDigest.compute(admissionSnapshot)
        val nativeResult = if (registration.nativeDisposition == 0) {
            preservedDetachResult(ticket.key.surfaceEpoch)
        } else runCatching {
            NtkStripNativeBridge.nativeDetach(
                nativeHandle,
                this,
                ticket.key.surfaceEpoch,
                admissionKeyTriples,
                admissionDigest
            )
        }.getOrElse { failedDetachResult(ticket.key.surfaceEpoch) }
        val result = protocol.withProtocolLock {
            val resolved = if (nativeResult.engineGeneration != engineGeneration ||
                nativeResult.surfaceEpoch != ticket.key.surfaceEpoch
            ) {
                protocol.failLocked()
                nativeResult.copy(disposition = NtkNativeDetachDisposition.FAILED)
            } else when (nativeResult.disposition) {
                NtkNativeDetachDisposition.SURFACE_PRESERVED -> {
                    if (!protocol.completeSurfaceDetach(
                            ticket,
                            ProtocolPhase.LIVE_DETACHED
                        )
                    ) {
                        protocol.failLocked()
                        nativeResult.copy(
                            disposition = NtkNativeDetachDisposition.FAILED
                        )
                    } else {
                        nativeResult
                    }
                }
                NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED -> {
                    val exactFrozen = HashMap<AuthorityTokenKey, NtkNativeAuthorityToken>()
                    var metadataConflict = false
                    fun mergeFrozen(token: NtkNativeAuthorityToken) {
                        val previous = exactFrozen.putIfAbsent(token.key(), token)
                        if (previous != null && previous != token) metadataConflict = true
                    }
                    bindings.values.forEach(::mergeFrozen)
                    releasedDuringHandoffTokens.values.forEach(::mergeFrozen)
                    val exactDigest = if (metadataConflict) "" else runCatching {
                        NtkRetiredAuthorityDigest.compute(exactFrozen.values)
                    }.getOrDefault("")
                    val valid = nativeResult.hasCompleteRetirementBarrier && !metadataConflict &&
                        exactFrozen == admissionSnapshotByKey &&
                        exactFrozen.size == nativeResult.retiredAuthorityCount &&
                        exactDigest == nativeResult.retiredAuthorityDigest &&
                        remainingOperationalKotlinCallbacksLocked() == 0
                    if (!valid) {
                        protocol.failLocked()
                        nativeResult.copy(disposition = NtkNativeDetachDisposition.FAILED)
                    } else {
                        frozenRetiredTokens = exactFrozen.toMap()
                        claimableRetiredProofKeys.clear()
                        claimableRetiredProofKeys.addAll(bindings.keys)
                        frameListener = null
                        preSubmitViewportGapListener = null
                        bindSeal = null
                        frameSnapshot.set(null)
                        if (!protocol.completeSurfaceDetach(
                                ticket,
                                ProtocolPhase.RETIRED_BLOCKED
                            )
                        ) {
                            protocol.failLocked()
                            nativeResult.copy(
                                disposition = NtkNativeDetachDisposition.FAILED
                            )
                        } else {
                            nativeResult
                        }
                    }
                }
                NtkNativeDetachDisposition.FAILED -> {
                    protocol.completeSurfaceDetach(ticket, ProtocolPhase.FAILED)
                    nativeResult
                }
            }
            if (protocol.externalCompletionDispatchAllowedLocked()) {
                rescheduleDispatchableCompletionsLocked()
            }
            resolved
        }
        try {
            registration.completion(StageRevocation(
                preparation.value.proof,
                preparation.value.request != null,
                result
            ))
        } finally {
            protocol.withProtocolLock {
                if (surfaceLossRegistration === registration) {
                    surfaceLossRegistration = null
                }
                attachRegistration = null
            }
            preparation.value.request?.completion?.let { completion ->
                NtkReleaseCompletion.dispatch { completion(null) }
            }
        }
    }

    private fun failedDetachResult(surfaceEpoch: Long) = NtkNativeDetachResult(
        NtkNativeDetachDisposition.FAILED,
        engineGeneration,
        surfaceEpoch,
        0L,
        0L,
        0,
        "",
        0,
        0,
        0,
        0,
        0,
        0,
        0
    )

    private fun preservedDetachResult(surfaceEpoch: Long) = NtkNativeDetachResult(
        NtkNativeDetachDisposition.SURFACE_PRESERVED,
        engineGeneration,
        surfaceEpoch,
        0L,
        0L,
        0,
        "",
        0,
        0,
        0,
        0,
        0,
        0,
        0
    )

    internal fun closeAfterSurfaceTerminal() {
        try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict { close() }
        } catch (_: RejectedExecutionException) {
            failCloseAndRetainNativeHandle()
        }
    }

    override fun close() {
        val initialPhase = protocol.phaseSnapshot()
        if (initialPhase == ProtocolPhase.CLOSED || initialPhase == ProtocolPhase.CLOSING) return
        if ((initialPhase == ProtocolPhase.RETIRED_BLOCKED ||
                initialPhase == ProtocolPhase.RETIRED_DISPATCHABLE) &&
            !canCloseRetiredProof()
        ) {
            transferToProofRegistry()
            return
        }
        if (!closeOwnerStarted.compareAndSet(false, true)) return
        val previous = protocol.beginCloseAndAwaitQuiescence(setOf(
            ProtocolPhase.LIVE_DETACHED,
            ProtocolPhase.RETIRED_DISPATCHABLE,
            ProtocolPhase.FAILED
        )) ?: run {
            closeOwnerStarted.set(false)
            return
        }
        var failedBeforeDrainOwnership = false
        val pendingReleaseDrain = try {
            protocol.withProtocolLock {
                if (protocol.phaseLocked() == ProtocolPhase.FAILED) {
                    failedBeforeDrainOwnership = true
                    false
                } else {
                    check(protocol.phaseLocked() == ProtocolPhase.CLOSING)
                    rescheduleDispatchableCompletionsLocked()
                    releaseRegistrations.isNotEmpty().also { pending ->
                        // Publish strong process ownership while the same protocol lock still
                        // proves CLOSING. A callback may flip the phase to FAILED immediately
                        // after unlock, but it can no longer make the handle unreachable.
                        if (pending) NtkClosingNativeHandleRegistry.adopt(this)
                    }
                }
            }
        } catch (_: Throwable) {
            failCloseAndRetainNativeHandle()
            return
        }
        if (failedBeforeDrainOwnership) {
            failCloseAndRetainNativeHandle()
            return
        }
        if (pendingReleaseDrain) {
            try {
                NtkClosingNativeHandleRegistry.dispatchTeardown {
                    try {
                        finishCloseAfterAdmission(previous)
                    } catch (_: Throwable) {
                        failCloseAndRetainNativeHandle()
                    }
                }
            } catch (_: Throwable) {
                failCloseAndRetainNativeHandle()
            }
            return
        }
        finishCloseAfterAdmission(previous)
    }

    private fun finishCloseAfterAdmission(previous: ProtocolPhase) {
        val failedDuringDrain = protocol.withProtocolLock {
            if (previous == ProtocolPhase.FAILED && releaseRegistrations.isNotEmpty()) {
                protocol.failLocked()
            } else {
                if (protocol.externalCompletionDispatchAllowedLocked()) {
                    rescheduleDispatchableCompletionsLocked()
                }
                // The condition releases the protocol lock. Native metadata/dispatch callbacks
                // and the external completion's finally-only removal remain free to progress.
                protocol.awaitChangedUninterruptiblyLocked {
                    releaseRegistrations.isEmpty() ||
                        protocol.phaseLocked() == ProtocolPhase.FAILED
                }
            }
            protocol.phaseLocked() == ProtocolPhase.FAILED
        }
        if (failedDuringDrain) {
            failCloseAndRetainNativeHandle()
            return
        }
        val destroyed = runCatching {
            NtkStripNativeBridge.nativeDestroy(nativeHandle)
        }.getOrDefault(false)
        if (!destroyed) {
            failCloseAndRetainNativeHandle()
            return
        }

        var cancelledStage: ((NtkStageProof?) -> Unit)? = null
        val rejectedPublishes = ArrayList<Pair<InstallIdentity, (TileResidentResult) -> Unit>>()
        val rejectedPreparedPublishes = ArrayList<PreparedPublishCallback>()
        val rejectedProtections = ArrayList<ProtectionCallback>()
        val rejectedRetires = ArrayList<RetireCallbacks>()
        var closeCommitRejected = false
        protocol.withProtocolLock {
            if (protocol.phaseLocked() != ProtocolPhase.CLOSING ||
                releaseRegistrations.isNotEmpty()
            ) {
                protocol.failLocked()
                closeCommitRejected = true
                return@withProtocolLock
            }
            cancelledStage = stageRequest?.completion
            stageRequest = null
            if (previous == ProtocolPhase.LIVE_DETACHED || previous == ProtocolPhase.FAILED) {
                rejectedPublishes += publishCallbacks.entries.map { it.key to it.value }
                rejectedPreparedPublishes += preparedPublishCallbacks.values
                rejectedProtections += protectionCallbacks.values
                rejectedRetires += retireCallbacks.values.filter { !it.detachedDelivered }
            }
            publishCallbacks.clear()
            preparedPublishCallbacks.clear()
            protectionCallbacks.clear()
            retireCallbacks.clear()
            gpuInvariants.clear()
            bindings.clear()
            claimableRetiredProofKeys.clear()
            frozenRetiredTokens = emptyMap()
            releasedDuringHandoffTokens.clear()
            currentBinding = null
            currentPreparation = null
            currentBindingMirror.set(null)
            bindSeal = null
            stageProof = null
            frameListener = null
            preSubmitViewportGapListener = null
            // CLOSED is the externally visible proof that no process registry owns this engine.
            // The teardown task's stack keeps a strong reference through the remainder of cleanup.
            NtkRetiredProofRegistry.remove(engineGeneration, this)
            NtkFailedNativeHandleRegistry.remove(engineGeneration, this)
            NtkClosingNativeHandleRegistry.remove(engineGeneration, this)
            protocol.setPhaseLocked(ProtocolPhase.CLOSED)
        }
        if (closeCommitRejected) {
            failCloseAndRetainNativeHandle()
            return
        }
        cancelledStage?.let { completion -> NtkReleaseCompletion.dispatch { completion(null) } }
        rejectedPublishes.forEach { (identity, completion) ->
            runCatching { completion(TileResidentResult(identity, 0L, false)) }
        }
        rejectedPreparedPublishes.forEach { callback ->
            runCatching { callback.completion(null) }
        }
        rejectedProtections.forEach {
            runCatching { it.completion(NtkStripProtectionAck(it.commit, 0L, false)) }
        }
        rejectedRetires.forEach {
            runCatching {
                it.result(NtkStripRetireResult(
                    it.intent,
                    NtkStripRetireResultCode.FAILED,
                    latestFrameSnapshot()?.sceneVersion ?: 0L
                ))
            }
        }
    }

    private fun failCloseAndRetainNativeHandle() {
        val retain = protocol.withProtocolLock {
            if (protocol.phaseLocked() == ProtocolPhase.CLOSED) {
                false
            } else {
                protocol.failLocked()
                true
            }
        }
        if (retain) NtkFailedNativeHandleRegistry.adopt(this)
        NtkClosingNativeHandleRegistry.remove(engineGeneration, this)
    }

    private fun callbackTokenLocked(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long
    ): NtkNativeAuthorityToken? {
        val key = AuthorityTokenKey(
            callbackEngineGeneration,
            authorityGeneration,
            authority
        )
        if (callbackEngineGeneration != engineGeneration) {
            failedTokens += key
            return null
        }
        if (releasedTokens.contains(key)) {
            protocol.failLocked()
            return null
        }
        if (protocol.phaseLocked() != ProtocolPhase.LIVE_ATTACHED &&
            protocol.phaseLocked() != ProtocolPhase.DETACH_CLOSING &&
            protocol.phaseLocked() != ProtocolPhase.CLOSING
        ) {
            failedTokens += key
            protocol.failLocked()
            return null
        }
        return bindings[key] ?: run {
            failedTokens += key
            null
        }
    }

    private fun remainingKotlinCallbacksLocked(token: NtkNativeAuthorityToken): Int {
        val key = token.key()
        var count = publishCallbacks.keys.count {
            it.engineGeneration == token.engineGeneration &&
                it.key.authority == token.authority &&
                it.authorityGeneration == token.authorityGeneration
        }
        count += preparedPublishCallbacks.keys.count {
            it.engineGeneration == token.engineGeneration &&
                it.key.authority == token.authority &&
                it.authorityGeneration == token.authorityGeneration
        }
        count += protectionCallbacks.keys.count {
            it.engineGeneration == key.engineGeneration &&
                it.authorityGeneration == key.authorityGeneration &&
                it.authority == key.authority
        }
        count += retireCallbacks.keys.count {
            it.engineGeneration == key.engineGeneration &&
                it.authorityGeneration == key.authorityGeneration &&
                it.authority == key.authority
        }
        if (stageRequest?.token == token) count++
        count += gpuInvariants.keys.count {
            it.engineGeneration == key.engineGeneration &&
                it.authorityGeneration == key.authorityGeneration
        }
        return count
    }

    private fun remainingOperationalKotlinCallbacksLocked(): Int =
        publishCallbacks.keys.count { it.engineGeneration == engineGeneration } +
            preparedPublishCallbacks.keys.count { it.engineGeneration == engineGeneration } +
            protectionCallbacks.keys.count { it.engineGeneration == engineGeneration } +
            retireCallbacks.keys.count { it.engineGeneration == engineGeneration } +
            (if (stageRequest?.token?.engineGeneration == engineGeneration) 1 else 0) +
            gpuInvariants.keys.count { it.engineGeneration == engineGeneration }

    @Keep
    @Suppress("unused")
    fun onNativeTileResident(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        rgbaBytes: Long,
        sceneVersion: Long,
        success: Boolean
    ) {
        val identity = InstallIdentity(
            TileKey(authority, page, slot),
            callbackEngineGeneration,
            authorityGeneration,
            surfaceEpoch,
            admissionId,
            resourceRevision,
            installLease,
            rgbaBytes
        )
        val completion = protocol.withProtocolLock {
            if (callbackTokenLocked(
                    callbackEngineGeneration,
                    authorityGeneration,
                    authority
                ) == null
            ) null else publishCallbacks.remove(identity)
        }
        completion?.invoke(TileResidentResult(identity, sceneVersion, success))
    }

    @Keep
    @Suppress("unused")
    fun onNativePreparedTileResident(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        preparationGeneration: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        rgbaBytes: Long,
        tileProofDigest: String,
        residentInventoryDigest: String,
        resourceCompletionNanos: Long,
        preGeometryPrepared: Boolean,
        success: Boolean
    ) {
        val identity = DetachedInstallCallbackIdentity(
            TileKey(authority, page, slot),
            callbackEngineGeneration,
            authorityGeneration,
            preparationGeneration,
            admissionId,
            resourceRevision,
            installLease,
            rgbaBytes
        )
        val completion = protocol.withProtocolLock {
            val preparation = currentPreparation
            if (callbackEngineGeneration != engineGeneration || preparation == null ||
                preparation.authorityGeneration != authorityGeneration ||
                preparation.token.authority != authority ||
                preparation.token.preparationGeneration != preparationGeneration
            ) null else preparedPublishCallbacks.remove(identity)
        }
        if (completion != null) {
            val ack = if (success && NtkStripDigests.isSha256(tileProofDigest) &&
                NtkStripDigests.isSha256(residentInventoryDigest) &&
                resourceCompletionNanos > 0L
            ) {
                NtkDetachedPreparedTileAck(
                    identity = completion.identity,
                    tileProofDigest = tileProofDigest,
                    residentInventoryDigest = residentInventoryDigest,
                    preGeometryPrepared = preGeometryPrepared,
                    resourceCompletionNanos = resourceCompletionNanos
                )
            } else null
            completion.completion.invoke(ack)
        }
    }

    @Keep
    @Suppress("unused")
    fun onNativePreSubmitViewportGap(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        count: Long
    ) {
        val listener = protocol.withProtocolLock {
            val token = callbackTokenLocked(
                callbackEngineGeneration,
                authorityGeneration,
                authority
            ) ?: return@withProtocolLock null
            if (currentBinding != token || authority <= 0L || surfaceEpoch <= 0L || count <= 0L) {
                return@withProtocolLock null
            }
            preSubmitViewportGap.updateAndGet { previous -> maxOf(previous, count) }
            preSubmitViewportGapListener
        }
        listener?.invoke(
            callbackEngineGeneration,
            authorityGeneration,
            authority,
            surfaceEpoch,
            count
        )
    }

    @Keep
    @Suppress("unused")
    fun onNativeProtectionCommitted(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        demandEpoch: Long,
        protectedDigest: String,
        sceneVersion: Long,
        success: Boolean
    ) {
        val identity = ProtectionIdentity(
            callbackEngineGeneration,
            authorityGeneration,
            authority,
            surfaceEpoch,
            demandEpoch,
            protectedDigest
        )
        val callback = protocol.withProtocolLock {
            if (callbackTokenLocked(
                    callbackEngineGeneration,
                    authorityGeneration,
                    authority
                ) == null
            ) null else protectionCallbacks.remove(identity)
        }
        callback?.completion?.invoke(NtkStripProtectionAck(callback.commit, sceneVersion, success))
    }

    @Keep
    @Suppress("unused")
    fun onNativeRetireResult(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        policySurfaceEpoch: Long,
        demandEpoch: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        retireLease: Long,
        protectedDigest: String,
        resultCode: Int,
        sceneVersion: Long,
        retireFenceSerial: Long
    ) {
        val identity = RetireIdentity(
            callbackEngineGeneration,
            authorityGeneration,
            authority,
            surfaceEpoch,
            policySurfaceEpoch,
            demandEpoch,
            page,
            slot,
            resourceRevision,
            installLease,
            retireLease,
            protectedDigest
        )
        val code = NtkStripRetireResultCode.entries.getOrNull(resultCode)
            ?: NtkStripRetireResultCode.FAILED
        val dispatch = protocol.withProtocolLock {
            if (callbackTokenLocked(
                    callbackEngineGeneration,
                    authorityGeneration,
                    authority
                ) == null
            ) return@withProtocolLock null
            val callbacks = retireCallbacks[identity] ?: return@withProtocolLock null
            val ack = NtkStripRetireResult(
                callbacks.intent,
                code,
                sceneVersion,
                if (code == NtkStripRetireResultCode.DETACHED) retireFenceSerial else 0L
            )
            if (code == NtkStripRetireResultCode.DETACHED) {
                callbacks.detachedDelivered = true
            } else {
                retireCallbacks.remove(identity, callbacks)
            }
            callbacks.result to ack
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused")
    fun onNativeTileFreed(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        policySurfaceEpoch: Long,
        demandEpoch: Long,
        admissionId: Long,
        page: Int,
        slot: Int,
        resourceRevision: Long,
        installLease: Long,
        retireLease: Long,
        rgbaBytes: Long,
        protectedDigest: String,
        freedNanos: Long,
        success: Boolean
    ) {
        val identity = RetireIdentity(
            callbackEngineGeneration,
            authorityGeneration,
            authority,
            surfaceEpoch,
            policySurfaceEpoch,
            demandEpoch,
            page,
            slot,
            resourceRevision,
            installLease,
            retireLease,
            protectedDigest
        )
        val dispatch = protocol.withProtocolLock {
            if (callbackTokenLocked(
                    callbackEngineGeneration,
                    authorityGeneration,
                    authority
                ) == null
            ) return@withProtocolLock null
            val callbacks = retireCallbacks.remove(identity) ?: return@withProtocolLock null
            callbacks.freed to NtkStripTileFreedAck(
                authority,
                surfaceEpoch,
                demandEpoch,
                admissionId,
                callbacks.intent.key,
                resourceRevision,
                installLease,
                retireLease,
                rgbaBytes,
                protectedDigest,
                freedNanos,
                success
            )
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused", "LongMethod", "ComplexMethod")
    fun onNativeFrameEvidenceV11(payload: ByteArray) {
        fun reject() {
            protocol.withProtocolLock { protocol.failLocked() }
        }
        val values = try {
            if (payload.size != FRAME_EVIDENCE_V11_BYTES) {
                reject()
                return
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            val schema = buffer.int
            val count = buffer.int
            val qualified = buffer.int
            if (magic != FRAME_EVIDENCE_V11_MAGIC ||
                schema != FRAME_EVIDENCE_V11_SCHEMA ||
                count != FRAME_EVIDENCE_V11_FIELD_COUNT || qualified != 1
            ) {
                reject()
                return
            }
            LongArray(FRAME_EVIDENCE_V11_FIELD_COUNT) { buffer.long }.also {
                if (buffer.hasRemaining()) {
                    reject()
                    return
                }
            }
        } catch (_: RuntimeException) {
            reject()
            return
        }

        val snapshot = FrameSnapshot()
        snapshot.schema11Values = values
        snapshot.schema10Values = values.copyOfRange(
            0, NtkSchema10FrameValidator.FIELD_COUNT
        ).also { it[83] = 10L }
        snapshot.evidenceQualified = true
        snapshot.engineGeneration = values[0]
        snapshot.authorityGeneration = values[1]
        snapshot.authority = values[2]
        snapshot.surfaceEpoch = values[3]
        snapshot.workGeneration = values[4]
        snapshot.frameId = values[5]
        snapshot.frameSequence = values[6]
        snapshot.admissionSequence = values[7]
        snapshot.capsuleSequence = values[8]
        snapshot.sceneVersion = values[9]
        snapshot.scrollTopPx = values[10]
        snapshot.velocityPxPerSecond = Float.fromBits(values[11].toInt())
        snapshot.predictedStopPx = values[12]
        snapshot.residentContinuousStartPx = values[13]
        snapshot.residentContinuousEndPx = values[14]
        snapshot.visibleContentStartPx = values[15]
        snapshot.visibleContentEndPx = values[16]
        snapshot.firstVisiblePage = values[17].toInt()
        snapshot.lastVisiblePage = values[18].toInt()
        snapshot.firstVisibleGapPx = values[19]
        snapshot.viewportOriginalComplete = values[20] == 1L
        snapshot.runwayOriginalComplete = values[21] == 1L
        snapshot.gestureId = values[22]
        snapshot.appliedInputSequence = values[23]
        snapshot.inputOldestNanos = values[24]
        snapshot.inputNewestNanos = values[25]
        snapshot.mainIngressOldestNanos = values[26]
        snapshot.mainIngressNewestNanos = values[27]
        snapshot.receiptOldestNanos = values[28]
        snapshot.receiptNewestNanos = values[29]
        snapshot.mutationOldestNanos = values[30]
        snapshot.mutationNewestNanos = values[31]
        snapshot.drawBeginNanos = values[32]
        snapshot.preWaitNanos = values[33]
        snapshot.preSwapNanos = values[33]
        snapshot.queueSubmitNanos = values[34]
        snapshot.postSwapCriticalNanos = values[35] - values[34]
        snapshot.postSwapNanos = values[36]
        snapshot.targetReachedNanos = values[37]
        snapshot.presentedAtNanos = values[38]
        snapshot.compositionLatchNanos = values[38]
        snapshot.latchEventSequence = values[39]
        snapshot.retirementSequence = values[40]
        snapshot.controlBacklogMax = values[41].toInt()
        snapshot.moveMailboxWrites = values[42].toInt()
        snapshot.integratedTiles = values[43].toInt()
        snapshot.uploadCommandsSubmitting = values[44].toInt()
        snapshot.uploadGpuFencesPending = values[45].toInt()
        snapshot.gpuPhase = values[46].toInt()
        snapshot.sealedScene = values[47] == 1L
        snapshot.resourceSubmitSerial = values[48]
        snapshot.sealedResourceSubmitSerial = values[49]
        snapshot.readyTileQueueDepth = values[50].toInt()
        snapshot.nativePublicationsOutstanding = values[51].toInt()
        snapshot.pendingPublishAcks = values[52].toInt()
        snapshot.retireQueueDepth = values[53].toInt()
        snapshot.retirementCount = values[54].toInt()
        snapshot.uploadContextAlive = values[55] == 1L
        snapshot.lastGpuResourceCompletionNanos = values[56]
        snapshot.sealFenceCompletionNanos = values[57]
        snapshot.uploadContextDestroyedNanos = values[58]
        snapshot.stageLatchNanos = values[59]
        snapshot.firstDownIngressNanos = values[60]
        snapshot.sealedSceneVersion = values[61]
        snapshot.resourceWorkerState = values[62].toInt()
        snapshot.resourceWorkerGeneration = values[63]
        snapshot.resourceWorkerCreateCount = values[64]
        snapshot.resourceWorkerDestroyCount = values[65]
        snapshot.activeResourceWorkerCount = values[66].toInt()
        snapshot.activeUploadContextCount = values[67].toInt()
        snapshot.sceneMutationCountSinceSeal = values[68]
        snapshot.offscreenWarmFenceCompletionNanos = values[69]
        snapshot.predecessorPhysicalCompleteNanos = values[70]
        snapshot.sealBarrierSerial = values[71]
        snapshot.stageBackbufferReadyNanos = values[72]
        snapshot.offscreenWarmDrawCount = values[73]
        snapshot.frameWorkKind = values[74].toInt()
        snapshot.plannerInvocationCount = values[75]
        snapshot.backendPresentPrepareCount = values[76]
        snapshot.swapAttemptCount = values[77]
        snapshot.slotClosedNoAttemptCount = values[78]
        snapshot.terminalSwapCount = values[79]
        snapshot.preparedDrawCount = values[80]
        snapshot.preparedFrameIdReservationCount = values[81]
        snapshot.admissionConsumed = values[82] == 1L
        snapshot.telemetrySchemaVersion = values[83].toInt()
        snapshot.fixedPhaseTelemetryValid = values[84] == 1L
        snapshot.fixedPhaseFatalReason = values[85].toInt()
        snapshot.fixedPhasePlanValid = values[86] == 1L
        snapshot.fixedPhaseRefreshPeriodNanos = values[87]
        snapshot.fixedPhaseDecisionNanos = values[88]
        snapshot.fixedPhasePlannedPresentationNanos = values[89]
        snapshot.fixedPhaseMissedPresentationNanos = values[90]
        snapshot.fixedPhaseLatestSwapStartExclusiveNanos = values[91]
        snapshot.fixedPhasePlannedTargetFrame = values[92]
        snapshot.backendPrepareBeginNanos = values[93]
        snapshot.drawIssueEndNanos = values[94]
        snapshot.backendCompletionSignalNanos = values[96]
        snapshot.backendWaitReturnNanos = values[96]
        snapshot.fenceCompleteNanos = values[96]
        snapshot.backendSurfaceSerial = values[97]
        snapshot.backendCompletionToken = values[98]
        snapshot.transactionSerial = values[98]
        snapshot.bufferSlot = values[99]
        snapshot.bufferGeneration = values[100]
        snapshot.frameTimelineVsyncId = values[101]
        snapshot.setBufferCount = values[102].toInt()
        snapshot.transactionApplyCount = values[103].toInt()
        snapshot.onCommitCallbackCount = values[104].toInt()
        snapshot.onCompleteCallbackCount = values[105].toInt()
        snapshot.latchSource = values[106].toInt()
        snapshot.latchCallbackObservedNanos = values[107]
        snapshot.retirementCallbackPublishedNanos = values[108]
        snapshot.postWaitNanos = values[108]
        snapshot.targetWaitCount = values[109].toInt()
        snapshot.targetRebaseCount = values[110].toInt()
        snapshot.stageCandidate = values[111] == 1L
        snapshot.stageNonce = values[112]
        snapshot.stageCorridorStartPx = values[113]
        snapshot.stageCorridorEndPx = values[114]
        snapshot.evidenceCapsuleDepth = values[115].toInt()
        snapshot.evidenceCapsuleMaxDepth = values[116].toInt()
        snapshot.evidenceCapsuleInvalidFrames = values[117]
        snapshot.cadenceQualificationFailed = values[118] != 0L
        snapshot.retirementState = values[119].toInt()
        snapshot.retirementFatalReason = values[120].toInt()
        snapshot.fixedPhasePhysicalCallbackSequence = values[121]
        snapshot.fixedCandidateSequence = values[122]
        snapshot.fixedCandidateRawSequence = values[123]
        snapshot.fixedCandidateCaptureNanos = values[124]
        snapshot.fixedCandidateClaimNanos = values[125]
        snapshot.fixedRefreshIssued = values[126].toInt()
        snapshot.fixedRefreshDelivered = values[127].toInt()
        snapshot.fixedRefreshPhysicalCallbackSequence = values[128]
        snapshot.fixedRefreshCapturedRawSequence = values[129]
        snapshot.fixedPriorRetirementSequence = values[130]
        snapshot.fixedExternalWorkGeneration = values[131]
        snapshot.fixedExternalFrameId = values[132]
        snapshot.fixedPhaseSequence = values[133]
        snapshot.fixedPhaseOpportunitySequence = values[134]
        snapshot.fixedPhaseReservationSequence = values[135]
        snapshot.fixedPhaseOpportunityReceiptNanos = values[136]
        snapshot.fixedPhaseOpportunityPublishNanos = values[137]
        snapshot.fixedPhaseRendererWakeObservedNanos = values[138]
        snapshot.fixedRetirementDemandIssued = values[139]
        snapshot.fixedRetirementDemandSatisfied = values[140]
        snapshot.fixedRetirementDemandCancelled = values[141]
        snapshot.fixedOpportunityDemandIssued = values[142]
        snapshot.fixedOpportunityDemandSatisfied = values[143]
        snapshot.fixedOpportunityDemandCancelled = values[144]
        snapshot.fixedSupersededBeforeClaimCount = values[145]
        snapshot.fixedClosedOpportunityCount = values[146]
        snapshot.fixedTransportProfileDigest = values[147]
        snapshot.fixedTimingGeneration = values[148]
        snapshot.fixedTransportBoundNanos = values[149]
        snapshot.fixedInitialDecisionNanos = values[150]
        snapshot.fixedCase1CutoffNanos = values[151]
        snapshot.fixedCase2PhaseOpenNanos = values[152]
        snapshot.fixedCase2GateNanos = values[153]
        snapshot.fixedCase2CutoffNanos = values[154]
        snapshot.fixedCase2LatestStartExclusiveNanos = values[155]
        snapshot.fixedCase1LatestSafeDecisionNanos = values[156]
        snapshot.fixedInitialTransportAdmissionOutcome = values[157].toInt()
        snapshot.fixedPhaseWaitCount = values[158].toInt()
        snapshot.fixedCase2GateWaitTargetNanos = values[159]
        snapshot.fixedCase2GateWaitReturnNanos = values[160]
        snapshot.fixedFinalDecisionNanos = values[161]
        snapshot.fixedClaimIssuedCount = values[162].toInt()
        snapshot.transactionPrepareBeginNanos = values[163]
        snapshot.transactionPrepareEndNanos = values[164]
        snapshot.fixedDecisionToClaimReturnNanos = values[165]
        snapshot.transactionApplyCallDurationNanos = values[166]
        snapshot.fixedDecisionToApplyEndNanos = values[167]
        snapshot.fixedTransportBoundSlackNanos = values[168]
        snapshot.fixedCutoffSlackNanos = values[169]
        snapshot.setFrameTimelineCount = values[170].toInt()
        snapshot.fixedApplyDisposition = values[171].toInt()
        snapshot.fixedPhaseRootFatalReason = values[172].toInt()
        snapshot.fixedReceiptFatalReason = values[173].toInt()
        snapshot.fixedRetirementRootFatalReason = values[174].toInt()
        snapshot.fixedRetirementCallbackPublishCount = values[175].toInt()
        snapshot.fixedOutstandingSubmissionCount = values[176].toInt()
        snapshot.fixedMaxOutstandingSubmissionCount = values[177].toInt()
        snapshot.fixedPreparedTransactionState = values[178].toInt()
        snapshot.fixedExternalClaimPresent = values[179] != 0L
        snapshot.fixedPoolStates = IntArray(8) { values[180 + it].toInt() }
        snapshot.fixedPendingFenceWatchCount = values[188].toInt()
        snapshot.fixedActiveFenceWatchCount = values[189].toInt()
        snapshot.fixedTransactionCompleteEventSequence = values[190]
        snapshot.fixedPreviousBufferExpected = values[191] != 0L
        snapshot.fixedPreviousReleaseEventSequence = values[192]
        snapshot.fixedTeardownReleaseEventSequence = values[193]
        snapshot.acquireFenceSignalNanos = values[194]
        snapshot.acquireFenceEventSequence = values[195]
        snapshot.acquireFenceSerial = values[196]
        snapshot.acquireFenceDupCount = values[197].toInt()
        snapshot.frameworkTransferCount = values[198].toInt()
        snapshot.proofFdCloseCount = values[199].toInt()
        snapshot.rendererGpuClientWaitCount = values[200].toInt()
        snapshot.applyBeforeAcquireSignalProven = values[201] == 1L
        snapshot.visualDemandEpoch = values[202]
        snapshot.visualMutationSerial = values[203]
        snapshot.visibleStateChanged = values[204] == 1L
        snapshot.fixedCallbackRecordDepth = values[205].toInt()
        snapshot.fixedMaxCallbackRecordDepth = values[206].toInt()
        snapshot.fixedPreviousReleaseRecordDepth = values[207].toInt()
        snapshot.fixedAcquireFenceRecordDepth = values[208].toInt()
        snapshot.fixedAppOwnedAcquireFdCount = values[209].toInt()
        snapshot.fixedCommitProofPendingNow = values[210].toInt()
        snapshot.fixedCompleteProofPendingNow = values[211].toInt()
        snapshot.fixedMaxCommitProofPending = values[212].toInt()
        snapshot.fixedMaxCompleteProofPending = values[213].toInt()
        snapshot.fixedHeldFrameworkRefCount = values[214].toInt()
        snapshot.fixedMaxHeldFrameworkRefCount = values[215].toInt()
        snapshot.fixedFreeReusableCount = values[216].toInt()
        snapshot.fixedMinFreeReusableCount = values[217].toInt()
        snapshot.fixedAppOwnedBufferDomainNow = values[218].toInt()
        snapshot.fixedMinAppOwnedBufferDomain = values[219].toInt()
        snapshot.fixedBackpressureEnableCount = values[220]
        snapshot.fixedBackpressureDisableCount = values[221]
        snapshot.fixedCapacityExhaustedCount = values[222]
        snapshot.fixedCapacityWaitCount = values[223]
        snapshot.fixedBackendInvariantFatalCount = values[224]
        snapshot.fixedApplyBeforePriorCompleteCount = values[225]
        snapshot.fixedTargetUnretiredNow = values[229].toInt()
        snapshot.fixedTargetUnretiredMax = values[230].toInt()
        snapshot.fixedPreparedProducerNow = values[231].toInt()
        snapshot.fixedPreparedProducerMax = values[232].toInt()
        snapshot.fixedPriorLatchGateRequired = values[233].toInt()
        snapshot.fixedPriorLatchGateUsed = values[234].toInt()
        snapshot.fixedPriorLatchWaitCount = values[235].toInt()
        snapshot.fixedPriorLatchObservationState = values[236].toInt()
        snapshot.fixedPriorCommitProofPendingAtClaim = values[237].toInt()
        snapshot.fixedPriorRetirementProofPresent = values[238] == 1L
        snapshot.fixedPriorAppliedBufferRefSerial = values[272]
        snapshot.fixedAppliedBufferRefSerial = values[273]
        snapshot.fixedTargetRetiredToSuccessorApplyNanos = values[274]
        snapshot.fixedSuccessorApplyToPriorCommitNanos = values[275]
        snapshot.fixedPriorLatchObservedAtClaim = values[276] == 1L
        snapshot.fixedReservationNanos = values[277]
        snapshot.fixedRawBaselineSequence = values[279]
        snapshot.fixedRawAuthoritySequence = values[280]
        snapshot.postSubmitSuccessfulCount = values[282]
        snapshot.postSubmitLatchedProofCount = values[283]
        snapshot.postSubmitTerminalLostProofCount = values[284]
        snapshot.postSubmitLogicalUnlatchedNow = values[285]
        snapshot.postSubmitMaxLogicalUnlatched = values[286]
        snapshot.postSubmitConservationBranch = values[287].toInt()
        snapshot.rendererPriorLatchInstalledBeforeSwappyPublish =
            values[288] == 1L
        snapshot.latestAppliedBufferRefSerial = values[299]
        snapshot.latestConsumedLatchRefSerial = values[300]
        snapshot.surfaceLogicalUnlatchedNow = values[301].toInt()
        snapshot.surfaceMaxLogicalUnlatched = values[302].toInt()
        snapshot.submittedWaitLatchCount = values[303].toInt()
        snapshot.applyBeforePriorCommitConsumedCount = values[307]
        snapshot.priorOnCompletePendingAtSuccessorApply =
            values[310].toInt()

        snapshot.presentationSupported = true
        snapshot.swappyMode = 2
        snapshot.latchProofState = 3
        snapshot.logicalUnlatchedSubmissions =
            snapshot.postSubmitLogicalUnlatchedNow.toInt()
        snapshot.maxLogicalUnlatchedSubmissions =
            snapshot.postSubmitMaxLogicalUnlatched.toInt()
        snapshot.swapIntervalNanos = snapshot.fixedPhaseRefreshPeriodNanos
        snapshot.fixedPhasePreSwapNanos = values[33]
        snapshot.fixedPhasePostSwapNanos = values[36]
        snapshot.fixedPhaseSwapDurationNanos = values[35] - values[34]
        snapshot.fixedPhaseFenceWaitCount = 0
        snapshot.fixedPhasePostSwapTargetRebaseCount = snapshot.targetRebaseCount
        snapshot.fixedTargetFrameTimeNanos = values[37]
        snapshot.fixedTargetFrameIndex = values[92]
        snapshot.fixedRetirementPublishNanos = values[108]
        snapshot.fixedRendererWakePublishNanos = values[108]
        snapshot.fixedBackendReadyNanos = values[96]
        snapshot.fixedFirstCommitAttemptNanos = values[34]
        snapshot.backendCompletionWorkGeneration = snapshot.workGeneration
        snapshot.backendCompletionFrameId = snapshot.frameId
        snapshot.backendCompletionClockDomain = 1
        snapshot.backendCompletionIssueCount = 1
        snapshot.backendCompletionCommitCount = 1
        snapshot.backendCompletionPublishCount = 1
        snapshot.backendPhasePartitionValid = values[93] > 0L &&
            values[94] >= values[93] && values[95] >= values[94] &&
            values[96] >= values[95] && values[34] >= values[96] &&
            values[35] >= values[34]
        snapshot.preparedWorkGeneration = snapshot.workGeneration
        snapshot.swappyWorkGeneration = snapshot.fixedExternalWorkGeneration
        snapshot.swappyAdmissionSequence = snapshot.admissionSequence

        val directSchema11Violation = NtkSchema11FrameValidator.violation(values)
        val priorLatchGateExact =
            if (snapshot.fixedPriorRetirementProofPresent) {
                snapshot.fixedPriorLatchGateRequired == 1 &&
                    snapshot.fixedPriorLatchGateUsed == 1 &&
                    snapshot.fixedPriorLatchWaitCount in 0..1 &&
                    snapshot.fixedPriorLatchObservationState == 2 &&
                    snapshot.fixedPriorCommitProofPendingAtClaim == 0 &&
                    snapshot.fixedPriorLatchObservedAtClaim
            } else {
                snapshot.fixedPriorLatchGateRequired == 0 &&
                    snapshot.fixedPriorLatchGateUsed == 0 &&
                    snapshot.fixedPriorLatchWaitCount == 0 &&
                    snapshot.fixedPriorLatchObservationState == 0 &&
                    snapshot.fixedPriorCommitProofPendingAtClaim == 0 &&
                    !snapshot.fixedPriorLatchObservedAtClaim
            }
        val exact = directSchema11Violation == null &&
            snapshot.engineGeneration > 0L && snapshot.surfaceEpoch > 0L &&
            snapshot.authorityGeneration > 0L && snapshot.authority > 0L &&
            snapshot.workGeneration > 0L && snapshot.frameId > 0L &&
            snapshot.frameSequence > 0L && snapshot.admissionSequence > 0L &&
            snapshot.capsuleSequence > 0L && snapshot.latchEventSequence > 0L &&
            snapshot.retirementSequence > 0L && snapshot.transactionSerial > 0L &&
            snapshot.bufferGeneration > 0L && snapshot.frameTimelineVsyncId > 0L &&
            snapshot.setBufferCount == 1 && snapshot.transactionApplyCount == 1 &&
            snapshot.onCommitCallbackCount == 1 && snapshot.latchSource == 1 &&
            snapshot.compositionLatchNanos > 0L && snapshot.targetWaitCount == 1 &&
            snapshot.targetRebaseCount == 0 &&
            snapshot.retirementState == NTK_FIXED_RETIREMENT_RETIRED &&
            snapshot.retirementFatalReason == 0 &&
            snapshot.telemetrySchemaVersion == FRAME_EVIDENCE_V11_SCHEMA &&
            snapshot.fixedPhaseTelemetryValid && snapshot.fixedPhaseFatalReason == 0 &&
            snapshot.fixedExternalWorkGeneration == snapshot.workGeneration &&
            snapshot.fixedExternalFrameId == snapshot.frameId &&
            snapshot.backendPhasePartitionValid &&
            snapshot.fixedTransportProfileDigest != 0L &&
            snapshot.fixedTimingGeneration > 0L &&
            snapshot.fixedTransportBoundNanos ==
                snapshot.fixedPhaseRefreshPeriodNanos / 2L &&
            snapshot.fixedInitialDecisionNanos >=
                snapshot.transactionPrepareEndNanos &&
            snapshot.fixedFinalDecisionNanos == snapshot.fixedPhaseDecisionNanos &&
            snapshot.fixedPhaseWaitCount in 0..1 &&
            snapshot.fixedClaimIssuedCount == 1 &&
            snapshot.transactionPrepareBeginNanos > 0L &&
            snapshot.transactionPrepareEndNanos >=
                snapshot.transactionPrepareBeginNanos &&
            snapshot.fixedDecisionToClaimReturnNanos >= 0L &&
            snapshot.fixedDecisionToClaimReturnNanos <=
                snapshot.fixedDecisionToApplyEndNanos &&
            snapshot.fixedDecisionToApplyEndNanos > 0L &&
            snapshot.fixedTransportBoundSlackNanos >= 0L &&
            snapshot.fixedCutoffSlackNanos >= 0L &&
            snapshot.setFrameTimelineCount == 1 &&
            snapshot.fixedApplyDisposition == 1 &&
            snapshot.fixedPhaseRootFatalReason == 0 &&
            snapshot.fixedReceiptFatalReason == 0 &&
            snapshot.fixedRetirementRootFatalReason == 0 &&
            snapshot.fixedRetirementCallbackPublishCount == 1 &&
            snapshot.onCompleteCallbackCount == 1 &&
            snapshot.fixedOutstandingSubmissionCount in 1..8 &&
            snapshot.fixedMaxOutstandingSubmissionCount in
                snapshot.fixedOutstandingSubmissionCount..8 &&
            snapshot.fixedPreparedTransactionState == 0 &&
            !snapshot.fixedExternalClaimPresent &&
            snapshot.fixedTargetUnretiredNow == 1 &&
            snapshot.fixedTargetUnretiredMax == 1 &&
            snapshot.fixedPreparedProducerNow == 0 &&
            snapshot.fixedPreparedProducerMax == 1 &&
            priorLatchGateExact &&
            snapshot.fixedBackpressureDisableCount == 0L &&
            snapshot.fixedCapacityExhaustedCount == 0L &&
            snapshot.fixedCapacityWaitCount == 0L &&
            snapshot.fixedBackendInvariantFatalCount == 0L &&
            snapshot.fixedReservationNanos in 1L..snapshot.drawBeginNanos &&
            snapshot.fixedRawAuthoritySequence >
                snapshot.fixedRawBaselineSequence &&
            NtkSchema11PostApplyConservation.isExact(snapshot) &&
            !snapshot.cadenceQualificationFailed
        if (!exact) {
            Log.e(
                "NtkStripRenderer",
                    "schema11 evidence rejected " +
                    "identity=${snapshot.engineGeneration}/${snapshot.surfaceEpoch}/" +
                    "${snapshot.authorityGeneration}/${snapshot.authority}/" +
                    "${snapshot.workGeneration}/${snapshot.frameId}/" +
                    "${snapshot.frameSequence}/${snapshot.admissionSequence}/" +
                    "${snapshot.capsuleSequence} tx=${snapshot.transactionSerial} " +
                    "buffer=${snapshot.bufferSlot}/${snapshot.bufferGeneration} " +
                    "vsync=${snapshot.frameTimelineVsyncId} " +
                    "counts=${snapshot.setBufferCount}/${snapshot.transactionApplyCount}/" +
                    "${snapshot.onCommitCallbackCount}/${snapshot.onCompleteCallbackCount} " +
                    "latch=${snapshot.latchSource}/${snapshot.latchEventSequence}/" +
                    "${snapshot.compositionLatchNanos}/${snapshot.latchCallbackObservedNanos} " +
                    "retire=${snapshot.retirementState}/${snapshot.retirementFatalReason}/" +
                    "${snapshot.retirementSequence}/${snapshot.targetWaitCount}/" +
                    "${snapshot.targetRebaseCount} external=${snapshot.fixedExternalWorkGeneration}/" +
                    "${snapshot.fixedExternalFrameId} schema=${snapshot.telemetrySchemaVersion} " +
                    "phase=${snapshot.fixedPhaseTelemetryValid}/${snapshot.fixedPhaseFatalReason} " +
                    "partition=${snapshot.backendPhasePartitionValid} " +
                    "cadence=${snapshot.cadenceQualificationFailed} " +
                    "direct=$directSchema11Violation"
            )
            Log.e(
                "NtkStripRenderer",
                    "schema11 evidence rejected detail " +
                    "profile=${snapshot.fixedTransportProfileDigest}/" +
                    "${snapshot.fixedTimingGeneration}/" +
                    "${snapshot.fixedTransportBoundNanos}/" +
                    "${snapshot.fixedPhaseRefreshPeriodNanos} " +
                    "decision=${snapshot.transactionPrepareBeginNanos}/" +
                    "${snapshot.transactionPrepareEndNanos}/" +
                    "${snapshot.fixedInitialDecisionNanos}/" +
                    "${snapshot.fixedFinalDecisionNanos}/" +
                    "${snapshot.fixedPhaseDecisionNanos} " +
                    "input=${snapshot.inputOldestNanos}/" +
                    "${snapshot.inputNewestNanos}/" +
                    "${snapshot.mainIngressOldestNanos}/" +
                    "${snapshot.mainIngressNewestNanos}/" +
                    "${snapshot.receiptOldestNanos}/" +
                    "${snapshot.receiptNewestNanos}/" +
                    "${snapshot.mutationOldestNanos}/" +
                    "${snapshot.mutationNewestNanos} " +
                    "transport=${snapshot.fixedPhaseWaitCount}/" +
                    "${snapshot.fixedClaimIssuedCount}/" +
                    "${snapshot.fixedDecisionToClaimReturnNanos}/" +
                    "${snapshot.fixedDecisionToApplyEndNanos}/" +
                    "${snapshot.fixedTransportBoundSlackNanos}/" +
                    "${snapshot.fixedCutoffSlackNanos}/" +
                    "${snapshot.setFrameTimelineCount}/" +
                    "${snapshot.fixedApplyDisposition} " +
                    "roots=${snapshot.fixedPhaseRootFatalReason}/" +
                    "${snapshot.fixedReceiptFatalReason}/" +
                    "${snapshot.fixedRetirementRootFatalReason}/" +
                    "${snapshot.fixedRetirementCallbackPublishCount} " +
                    "post=${snapshot.fixedOutstandingSubmissionCount}/" +
                    "${snapshot.fixedMaxOutstandingSubmissionCount}/" +
                    "${snapshot.fixedPreparedTransactionState}/" +
                    "${snapshot.fixedExternalClaimPresent} " +
                    "pool=${snapshot.fixedPoolStates.joinToString("/")} " +
                    "fence=${snapshot.fixedPendingFenceWatchCount}/" +
                    "${snapshot.fixedActiveFenceWatchCount} " +
                    "completion=${snapshot.fixedTransactionCompleteEventSequence}/" +
                    "${snapshot.fixedPreviousBufferExpected}/" +
                    "${snapshot.fixedPreviousReleaseEventSequence}/" +
                    "${snapshot.fixedTeardownReleaseEventSequence} " +
                    "ledger=${snapshot.fixedCallbackRecordDepth}/" +
                    "${snapshot.fixedMaxCallbackRecordDepth}/" +
                    "${snapshot.fixedPreviousReleaseRecordDepth}/" +
                    "${snapshot.fixedHeldFrameworkRefCount}/" +
                    "${snapshot.fixedFreeReusableCount}/" +
                    "${snapshot.fixedCommitProofPendingNow}/" +
                    "${snapshot.fixedCompleteProofPendingNow} " +
                    "policy=${snapshot.fixedBackpressureEnableCount}/" +
                    "${snapshot.fixedBackpressureDisableCount}/" +
                    "${snapshot.fixedCapacityExhaustedCount}/" +
                    "${snapshot.fixedCapacityWaitCount}/" +
                    "${snapshot.fixedBackendInvariantFatalCount}"
            )
            Log.e(
                "NtkStripRenderer",
                    "schema11 evidence rejected opportunity " +
                    "candidate=${snapshot.fixedPhasePhysicalCallbackSequence}/" +
                    "${snapshot.fixedCandidateSequence}/" +
                    "${snapshot.fixedCandidateRawSequence}/" +
                    "${snapshot.fixedCandidateCaptureNanos}/" +
                    "${snapshot.fixedCandidateClaimNanos} " +
                    "refresh=${snapshot.fixedRefreshIssued}/" +
                    "${snapshot.fixedRefreshDelivered}/" +
                    "${snapshot.fixedRefreshPhysicalCallbackSequence}/" +
                    "${snapshot.fixedRefreshCapturedRawSequence} " +
                    "phase=${snapshot.fixedPhaseSequence}/" +
                    "${snapshot.fixedPhaseOpportunitySequence}/" +
                    "${snapshot.fixedPhaseReservationSequence}/" +
                    "${snapshot.fixedPhaseOpportunityReceiptNanos}/" +
                    "${snapshot.fixedPhaseOpportunityPublishNanos}/" +
                    "${snapshot.fixedPhaseRendererWakeObservedNanos} " +
                    "retirementDemand=${snapshot.fixedRetirementDemandIssued}/" +
                    "${snapshot.fixedRetirementDemandSatisfied}/" +
                    "${snapshot.fixedRetirementDemandCancelled} " +
                    "opportunityDemand=${snapshot.fixedOpportunityDemandIssued}/" +
                    "${snapshot.fixedOpportunityDemandSatisfied}/" +
                    "${snapshot.fixedOpportunityDemandCancelled} " +
                    "closure=${snapshot.fixedSupersededBeforeClaimCount}/" +
                    "${snapshot.fixedClosedOpportunityCount} " +
                    "fixed=${snapshot.telemetrySchemaVersion}/" +
                    "${snapshot.fixedPhaseTelemetryValid}/" +
                    "${snapshot.fixedPhaseFatalReason}/" +
                    "${snapshot.fixedPhasePlanValid}/" +
                    "${snapshot.fixedPhaseRefreshPeriodNanos}/" +
                    "${snapshot.fixedPhaseDecisionNanos}/" +
                    "${snapshot.fixedPhasePlannedPresentationNanos}/" +
                    "${snapshot.fixedPhaseMissedPresentationNanos}/" +
                    "${snapshot.fixedPhaseLatestSwapStartExclusiveNanos}/" +
                    "${snapshot.fixedPhasePlannedTargetFrame} " +
                    "prior=${snapshot.fixedPriorRetirementProofPresent}/" +
                    "${snapshot.fixedPriorLatchObservationState}/" +
                    "${snapshot.fixedPriorCommitProofPendingAtClaim}/" +
                    "${snapshot.fixedPriorAppliedBufferRefSerial}/" +
                    "${snapshot.fixedAppliedBufferRefSerial}/" +
                    "${snapshot.fixedTargetRetiredToSuccessorApplyNanos}/" +
                    "${snapshot.fixedSuccessorApplyToPriorCommitNanos} " +
                    "reserve=${snapshot.fixedReservationNanos}/" +
                    "${snapshot.drawBeginNanos}/" +
                    "${snapshot.fixedRawBaselineSequence}/" +
                    "${snapshot.fixedRawAuthoritySequence}"
            )
            reject()
            return
        }

        val dispatch = protocol.withProtocolLock {
            val token = callbackTokenLocked(
                snapshot.engineGeneration,
                snapshot.authorityGeneration,
                snapshot.authority
            ) ?: return@withProtocolLock null
            if (currentBinding != token) return@withProtocolLock null
            if (snapshot.stageCandidate) {
                val key = NativeStageEvidenceKey(
                    snapshot.engineGeneration,
                    snapshot.surfaceEpoch,
                    snapshot.authorityGeneration,
                    snapshot.authority,
                    snapshot.workGeneration,
                    snapshot.frameId,
                    snapshot.frameSequence,
                    snapshot.admissionSequence,
                    snapshot.capsuleSequence,
                    snapshot.latchEventSequence,
                    snapshot.transactionSerial
                )
                if (pendingStageEvidence != null) {
                    protocol.failLocked()
                    return@withProtocolLock null
                }
                pendingStageEvidence = key to snapshot
            }
            if (!publishLatestFrameSnapshot(snapshot)) return@withProtocolLock null
            if (snapshot.mainIngressOldestNanos > 0L) {
                firstMainIngressNanos.compareAndSet(0L, snapshot.mainIngressOldestNanos)
            }
            frameListener?.let { it to snapshot }
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused")
    fun onNativeGpuInvariant(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        frameSequence: Long,
        gpuPhase: Int,
        sealedScene: Boolean,
        resourceSubmitSerial: Long,
        sealedResourceSubmitSerial: Long,
        readyTileQueueDepth: Int,
        nativePublicationsOutstanding: Int,
        pendingPublishAcks: Int,
        retireQueueDepth: Int,
        retirementCount: Int,
        uploadContextAlive: Boolean,
        lastGpuResourceCompletionNanos: Long,
        sealFenceCompletionNanos: Long,
        uploadContextDestroyedNanos: Long,
        stageLatchNanos: Long,
        firstDownIngressNanos: Long,
        sealedSceneVersion: Long,
        resourceWorkerState: Int,
        resourceWorkerGeneration: Long,
        resourceWorkerCreateCount: Long,
        resourceWorkerDestroyCount: Long,
        activeResourceWorkerCount: Int,
        activeUploadContextCount: Int,
        sceneMutationCountSinceSeal: Long,
        offscreenWarmFenceCompletionNanos: Long,
        predecessorPhysicalCompleteNanos: Long,
        sealBarrierSerial: Long,
        stageBackbufferReadyNanos: Long,
        offscreenWarmDrawCount: Long,
        frameWorkKind: Int,
        admissionSequence: Long,
        plannerInvocationCount: Long,
        backendPresentPrepareCount: Long,
        swapAttemptCount: Long,
        slotClosedNoAttemptCount: Long,
        terminalSwapCount: Long,
        windowSwapCountBeforeStage: Long,
        windowFrameIdCountBeforeStage: Long,
        preparedWorkGeneration: Long,
        swappyWorkGeneration: Long,
        swappyAdmissionSequence: Long,
        preparedDrawCount: Long,
        preparedFrameIdReservationCount: Long,
        admissionConsumed: Boolean
    ) {
        val invariant = GpuInvariant(
            callbackEngineGeneration,
            authorityGeneration,
            authority,
            frameSequence,
            gpuPhase,
            sealedScene,
            resourceSubmitSerial,
            sealedResourceSubmitSerial,
            readyTileQueueDepth,
            nativePublicationsOutstanding,
            pendingPublishAcks,
            retireQueueDepth,
            retirementCount,
            uploadContextAlive,
            lastGpuResourceCompletionNanos,
            sealFenceCompletionNanos,
            uploadContextDestroyedNanos,
            stageLatchNanos,
            firstDownIngressNanos,
            sealedSceneVersion,
            resourceWorkerState,
            resourceWorkerGeneration,
            resourceWorkerCreateCount,
            resourceWorkerDestroyCount,
            activeResourceWorkerCount,
            activeUploadContextCount,
            sceneMutationCountSinceSeal,
            offscreenWarmFenceCompletionNanos,
            predecessorPhysicalCompleteNanos,
            sealBarrierSerial,
            stageBackbufferReadyNanos,
            offscreenWarmDrawCount,
            frameWorkKind,
            admissionSequence,
            plannerInvocationCount,
            backendPresentPrepareCount,
            swapAttemptCount,
            slotClosedNoAttemptCount,
            terminalSwapCount,
            windowSwapCountBeforeStage,
            windowFrameIdCountBeforeStage,
            preparedWorkGeneration,
            swappyWorkGeneration,
            swappyAdmissionSequence,
            preparedDrawCount,
            preparedFrameIdReservationCount,
            admissionConsumed
        )
        protocol.withProtocolLock {
            val token = callbackTokenLocked(
                callbackEngineGeneration,
                authorityGeneration,
                authority
            ) ?: return@withProtocolLock
            if (currentBinding != token) return@withProtocolLock
            gpuInvariants[NativeFrameKey(
                callbackEngineGeneration,
                authorityGeneration,
                surfaceEpoch,
                frameSequence
            )] = invariant
        }
    }

    @Keep
    @Suppress("unused", "LongParameterList")
    fun onNativeFixedV4Evidence(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        frameSequence: Long,
        candidateSequence: Long,
        candidateRawSequence: Long,
        candidateCaptureNanos: Long,
        candidateClaimNanos: Long,
        refreshIssued: Long,
        refreshDelivered: Long,
        refreshPhysicalCallbackSequence: Long,
        refreshCapturedRawSequence: Long,
        shadowRawSequence: Long,
        shadowPromotionCount: Long,
        wakeNoticeSequence: Long,
        joinNoticeSequence: Long,
        joinOpenNanos: Long,
        joinPriorRetirementSequence: Long,
        latchCreditWorkGeneration: Long,
        latchCreditAdmissionSequence: Long,
        latchCreditFrameId: Long,
        latchCreditQueueNanos: Long,
        latchCreditLatchNanos: Long,
        latchCreditQueryCount: Long,
        finalCorridorBeginNanos: Long,
        queueMarkNanos: Long,
        eglSwapEnterNanos: Long,
        decisionToEglEnterNanos: Long,
        commonCommitEntryNanos: Long,
        opportunityClaimNanos: Long
    ) {
        val values = pendingFixedV4Evidence
        synchronized(values) {
            values[0] = callbackEngineGeneration
            values[1] = authorityGeneration
            values[2] = authority
            values[3] = surfaceEpoch
            values[4] = frameSequence
            values[5] = candidateSequence
            values[6] = candidateRawSequence
            values[7] = candidateCaptureNanos
            values[8] = candidateClaimNanos
            values[9] = refreshIssued
            values[10] = refreshDelivered
            values[11] = refreshPhysicalCallbackSequence
            values[12] = refreshCapturedRawSequence
            values[13] = shadowRawSequence
            values[14] = shadowPromotionCount
            values[15] = wakeNoticeSequence
            values[16] = joinNoticeSequence
            values[17] = joinOpenNanos
            values[18] = joinPriorRetirementSequence
            values[19] = latchCreditWorkGeneration
            values[20] = latchCreditAdmissionSequence
            values[21] = latchCreditFrameId
            values[22] = latchCreditQueueNanos
            values[23] = latchCreditLatchNanos
            values[24] = latchCreditQueryCount
            values[25] = finalCorridorBeginNanos
            values[26] = queueMarkNanos
            values[27] = eglSwapEnterNanos
            values[28] = decisionToEglEnterNanos
            values[29] = commonCommitEntryNanos
            values[30] = opportunityClaimNanos
        }
    }

    @Keep
    @Suppress("unused")
    fun onNativeFramePresented(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        frameSequence: Long,
        sceneVersion: Long,
        scrollTopPx: Long,
        velocityPxPerSecond: Float,
        predictedStopPx: Long,
        residentContinuousStartPx: Long,
        residentContinuousEndPx: Long,
        visibleContentStartPx: Long,
        visibleContentEndPx: Long,
        firstVisiblePage: Int,
        lastVisiblePage: Int,
        firstVisibleGapPx: Long,
        viewportOriginalComplete: Boolean,
        runwayOriginalComplete: Boolean,
        presentedAtNanos: Long,
        compositionLatchNanos: Long,
        queueSubmitNanos: Long,
        gestureId: Long,
        appliedInputSequence: Long,
        inputOldestNanos: Long,
        inputNewestNanos: Long,
        mainIngressOldestNanos: Long,
        mainIngressNewestNanos: Long,
        receiptOldestNanos: Long,
        receiptNewestNanos: Long,
        mutationOldestNanos: Long,
        mutationNewestNanos: Long,
        drawBeginNanos: Long,
        targetReachedNanos: Long,
        fenceCompleteNanos: Long,
        preWaitNanos: Long,
        postWaitNanos: Long,
        preSwapNanos: Long,
        postSwapNanos: Long,
        swapIntervalNanos: Long,
        timestampQueryWorkNanos: Long,
        controlBacklogMax: Int,
        moveMailboxWrites: Int,
        integratedTiles: Int,
        uploadCommandsSubmitting: Int,
        uploadGpuFencesPending: Int,
        presentationSupported: Boolean,
        swappyMode: Int,
        surfaceEpoch: Long,
        frameId: Long,
        latchProofState: Int,
        logicalUnlatchedSubmissions: Int,
        maxLogicalUnlatchedSubmissions: Int,
        oldestUnlatchedAgeNanos: Long,
        latchQueryError: Int,
        latchEvidenceDeadlineNanos: Long,
        cadenceQualificationFailed: Boolean,
        fixedPhaseTelemetryValid: Boolean,
        fixedPhaseSequence: Long,
        fixedPhaseStaleTargetObserved: Boolean,
        fixedPhaseMissProven: Boolean,
        fixedPhaseOutcome: Int,
        fixedPhaseFatalReason: Int,
        fixedPhasePlanValid: Boolean,
        fixedPhaseRefreshPeriodNanos: Long,
        fixedPhaseAppVsyncOffsetNanos: Long,
        fixedPhaseAcceptedFrameTimeNanos: Long,
        fixedPhaseAcceptedFrameIndex: Long,
        fixedPhaseDecisionNanos: Long,
        fixedPhaseMissedPresentationNanos: Long,
        fixedPhasePlannedPresentationNanos: Long,
        fixedPhasePresentationDeadlineNanos: Long,
        fixedPhaseOpenNanos: Long,
        fixedPhaseLatestSwapStartExclusiveNanos: Long,
        fixedPhaseWaitNanos: Long,
        fixedPhasePlannedTargetFrame: Long,
        fixedPhasePreSwapNanos: Long,
        fixedPhasePostSwapNanos: Long,
        fixedPhaseSwapDurationNanos: Long,
        fixedPhaseFenceWaitCount: Int,
        fixedPhasePostSwapTargetRebaseCount: Int,
        telemetrySchemaVersion: Int,
        backendCompletionToken: Long,
        backendSurfaceSerial: Long,
        backendCompletionWorkGeneration: Long,
        backendCompletionFrameId: Long,
        backendCompletionGfxstreamFrameNumber: Long,
        backendCompletionClockDomain: Int,
        backendPrepareBeginNanos: Long,
        backendCompletionSignalNanos: Long,
        backendWaitReturnNanos: Long,
        backendCompletionIssueCount: Int,
        backendCompletionCommitCount: Int,
        backendCompletionPublishCount: Int,
        fixedPhaseReservationSequence: Long,
        fixedPhaseOpportunitySequence: Long,
        fixedPhaseOpportunityKind: Int,
        fixedPhasePhysicalCallbackSequence: Long,
        fixedPhaseReservationNanos: Long,
        fixedPhaseOpportunityReceiptNanos: Long,
        fixedPhaseOpportunityPublishNanos: Long,
        fixedPhaseRendererWakeObservedNanos: Long,
        fixedRetirementDemandIssued: Long,
        fixedRetirementDemandSatisfied: Long,
        fixedRetirementDemandCancelled: Long,
        fixedOpportunityDemandIssued: Long,
        fixedOpportunityDemandSatisfied: Long,
        fixedOpportunityDemandCancelled: Long,
        fixedSupersededBeforeClaimCount: Long,
        fixedClosedOpportunityCount: Long,
        fixedTargetPhysicalCallbackSequence: Long,
        fixedTargetFrameTimeNanos: Long,
        fixedTargetFrameIndex: Long,
        fixedRetirementPublishNanos: Long,
        fixedRendererWakePublishNanos: Long,
        fixedRetirementRecordDemandIssued: Long,
        fixedRetirementRecordDemandSatisfied: Long,
        fixedRetirementRecordDemandCancelled: Long,
        fixedPriorRetirementWorkGeneration: Long,
        fixedPriorRetirementAdmissionSequence: Long,
        fixedPriorRetirementSequence: Long,
        fixedBackendReadyNanos: Long,
        fixedFirstCommitAttemptNanos: Long,
        fixedTimestampQueryBeforeFirstCommitCount: Int,
        drawIssueEndNanos: Long,
        frameIdReservationBeginNanos: Long,
        frameIdReservedNanos: Long,
        postSwapCriticalNanos: Long,
        postSwapToNextReservationNanos: Long,
        commonCallbackTransactionNanos: Long,
        wakeDispatchToRendererCallbackNanos: Long,
        rendererCallbackToCommitEntryNanos: Long,
        commonCommitEntryToClaimNanos: Long,
        backendPhasePartitionValid: Boolean,
        readyCommitPriorityViolationFrames: Long,
        preCommitRetirementObservationFrames: Long,
        retainedQueryRequiredCount: Long,
        retainedQueryExecutedCount: Long,
        retainedQueryWrongSelectionCount: Long,
        commitBeforeRetainedQueryCount: Long,
        callbackArrivedDuringQueryCount: Long,
        evidenceCapsuleDepth: Int,
        evidenceCapsuleMaxDepth: Int,
        evidenceCapsuleInvalidFrames: Long
    ) {
        val snapshot = FrameSnapshot()
        snapshot.engineGeneration = callbackEngineGeneration
        snapshot.authorityGeneration = authorityGeneration
        snapshot.authority = authority
        snapshot.frameSequence = frameSequence
        snapshot.sceneVersion = sceneVersion
        snapshot.scrollTopPx = scrollTopPx
        snapshot.velocityPxPerSecond = velocityPxPerSecond
        snapshot.predictedStopPx = predictedStopPx
        snapshot.residentContinuousStartPx = residentContinuousStartPx
        snapshot.residentContinuousEndPx = residentContinuousEndPx
        snapshot.visibleContentStartPx = visibleContentStartPx
        snapshot.visibleContentEndPx = visibleContentEndPx
        snapshot.firstVisiblePage = firstVisiblePage
        snapshot.lastVisiblePage = lastVisiblePage
        snapshot.firstVisibleGapPx = firstVisibleGapPx
        snapshot.viewportOriginalComplete = viewportOriginalComplete
        snapshot.runwayOriginalComplete = runwayOriginalComplete
        snapshot.presentedAtNanos = presentedAtNanos
        snapshot.compositionLatchNanos = compositionLatchNanos
        snapshot.queueSubmitNanos = queueSubmitNanos
        snapshot.gestureId = gestureId
        snapshot.appliedInputSequence = appliedInputSequence
        snapshot.inputOldestNanos = inputOldestNanos
        snapshot.inputNewestNanos = inputNewestNanos
        snapshot.mainIngressOldestNanos = mainIngressOldestNanos
        snapshot.mainIngressNewestNanos = mainIngressNewestNanos
        snapshot.receiptOldestNanos = receiptOldestNanos
        snapshot.receiptNewestNanos = receiptNewestNanos
        snapshot.mutationOldestNanos = mutationOldestNanos
        snapshot.mutationNewestNanos = mutationNewestNanos
        snapshot.drawBeginNanos = drawBeginNanos
        snapshot.targetReachedNanos = targetReachedNanos
        snapshot.fenceCompleteNanos = fenceCompleteNanos
        snapshot.preWaitNanos = preWaitNanos
        snapshot.postWaitNanos = postWaitNanos
        snapshot.preSwapNanos = preSwapNanos
        snapshot.postSwapNanos = postSwapNanos
        snapshot.swapIntervalNanos = swapIntervalNanos
        snapshot.timestampQueryWorkNanos = timestampQueryWorkNanos
        snapshot.controlBacklogMax = controlBacklogMax
        snapshot.moveMailboxWrites = moveMailboxWrites
        snapshot.integratedTiles = integratedTiles
        snapshot.uploadCommandsSubmitting = uploadCommandsSubmitting
        snapshot.uploadGpuFencesPending = uploadGpuFencesPending
        snapshot.presentationSupported = presentationSupported
        snapshot.swappyMode = swappyMode
        snapshot.surfaceEpoch = surfaceEpoch
        snapshot.frameId = frameId
        snapshot.latchProofState = latchProofState
        snapshot.logicalUnlatchedSubmissions = logicalUnlatchedSubmissions
        snapshot.maxLogicalUnlatchedSubmissions = maxLogicalUnlatchedSubmissions
        snapshot.oldestUnlatchedAgeNanos = oldestUnlatchedAgeNanos
        snapshot.latchQueryError = latchQueryError
        snapshot.latchEvidenceDeadlineNanos = latchEvidenceDeadlineNanos
        snapshot.cadenceQualificationFailed = cadenceQualificationFailed
        snapshot.fixedPhaseTelemetryValid = fixedPhaseTelemetryValid
        snapshot.fixedPhaseSequence = fixedPhaseSequence
        snapshot.fixedPhaseStaleTargetObserved = fixedPhaseStaleTargetObserved
        snapshot.fixedPhaseMissProven = fixedPhaseMissProven
        snapshot.fixedPhaseOutcome = fixedPhaseOutcome
        snapshot.fixedPhaseFatalReason = fixedPhaseFatalReason
        snapshot.fixedPhasePlanValid = fixedPhasePlanValid
        snapshot.fixedPhaseRefreshPeriodNanos = fixedPhaseRefreshPeriodNanos
        snapshot.fixedPhaseAppVsyncOffsetNanos = fixedPhaseAppVsyncOffsetNanos
        snapshot.fixedPhaseAcceptedFrameTimeNanos = fixedPhaseAcceptedFrameTimeNanos
        snapshot.fixedPhaseAcceptedFrameIndex = fixedPhaseAcceptedFrameIndex
        snapshot.fixedPhaseDecisionNanos = fixedPhaseDecisionNanos
        snapshot.fixedPhaseMissedPresentationNanos = fixedPhaseMissedPresentationNanos
        snapshot.fixedPhasePlannedPresentationNanos = fixedPhasePlannedPresentationNanos
        snapshot.fixedPhasePresentationDeadlineNanos = fixedPhasePresentationDeadlineNanos
        snapshot.fixedPhaseOpenNanos = fixedPhaseOpenNanos
        snapshot.fixedPhaseLatestSwapStartExclusiveNanos = fixedPhaseLatestSwapStartExclusiveNanos
        snapshot.fixedPhaseWaitNanos = fixedPhaseWaitNanos
        snapshot.fixedPhasePlannedTargetFrame = fixedPhasePlannedTargetFrame
        snapshot.fixedPhasePreSwapNanos = fixedPhasePreSwapNanos
        snapshot.fixedPhasePostSwapNanos = fixedPhasePostSwapNanos
        snapshot.fixedPhaseSwapDurationNanos = fixedPhaseSwapDurationNanos
        snapshot.fixedPhaseFenceWaitCount = fixedPhaseFenceWaitCount
        snapshot.fixedPhasePostSwapTargetRebaseCount = fixedPhasePostSwapTargetRebaseCount
        snapshot.telemetrySchemaVersion = telemetrySchemaVersion
        snapshot.backendCompletionToken = backendCompletionToken
        snapshot.backendSurfaceSerial = backendSurfaceSerial
        snapshot.backendCompletionWorkGeneration = backendCompletionWorkGeneration
        snapshot.backendCompletionFrameId = backendCompletionFrameId
        snapshot.backendCompletionGfxstreamFrameNumber = backendCompletionGfxstreamFrameNumber
        snapshot.backendCompletionClockDomain = backendCompletionClockDomain
        snapshot.backendPrepareBeginNanos = backendPrepareBeginNanos
        snapshot.backendCompletionSignalNanos = backendCompletionSignalNanos
        snapshot.backendWaitReturnNanos = backendWaitReturnNanos
        snapshot.backendCompletionIssueCount = backendCompletionIssueCount
        snapshot.backendCompletionCommitCount = backendCompletionCommitCount
        snapshot.backendCompletionPublishCount = backendCompletionPublishCount
        snapshot.fixedPhaseReservationSequence = fixedPhaseReservationSequence
        snapshot.fixedPhaseOpportunitySequence = fixedPhaseOpportunitySequence
        snapshot.fixedPhaseOpportunityKind = fixedPhaseOpportunityKind
        snapshot.fixedPhasePhysicalCallbackSequence = fixedPhasePhysicalCallbackSequence
        snapshot.fixedPhaseReservationNanos = fixedPhaseReservationNanos
        snapshot.fixedPhaseOpportunityReceiptNanos = fixedPhaseOpportunityReceiptNanos
        snapshot.fixedPhaseOpportunityPublishNanos = fixedPhaseOpportunityPublishNanos
        snapshot.fixedPhaseRendererWakeObservedNanos = fixedPhaseRendererWakeObservedNanos
        snapshot.fixedRetirementDemandIssued = fixedRetirementDemandIssued
        snapshot.fixedRetirementDemandSatisfied = fixedRetirementDemandSatisfied
        snapshot.fixedRetirementDemandCancelled = fixedRetirementDemandCancelled
        snapshot.fixedOpportunityDemandIssued = fixedOpportunityDemandIssued
        snapshot.fixedOpportunityDemandSatisfied = fixedOpportunityDemandSatisfied
        snapshot.fixedOpportunityDemandCancelled = fixedOpportunityDemandCancelled
        snapshot.fixedSupersededBeforeClaimCount = fixedSupersededBeforeClaimCount
        snapshot.fixedClosedOpportunityCount = fixedClosedOpportunityCount
        snapshot.fixedTargetPhysicalCallbackSequence = fixedTargetPhysicalCallbackSequence
        snapshot.fixedTargetFrameTimeNanos = fixedTargetFrameTimeNanos
        snapshot.fixedTargetFrameIndex = fixedTargetFrameIndex
        snapshot.fixedRetirementPublishNanos = fixedRetirementPublishNanos
        snapshot.fixedRendererWakePublishNanos = fixedRendererWakePublishNanos
        snapshot.fixedRetirementRecordDemandIssued = fixedRetirementRecordDemandIssued
        snapshot.fixedRetirementRecordDemandSatisfied = fixedRetirementRecordDemandSatisfied
        snapshot.fixedRetirementRecordDemandCancelled = fixedRetirementRecordDemandCancelled
        snapshot.fixedPriorRetirementWorkGeneration = fixedPriorRetirementWorkGeneration
        snapshot.fixedPriorRetirementAdmissionSequence = fixedPriorRetirementAdmissionSequence
        snapshot.fixedPriorRetirementSequence = fixedPriorRetirementSequence
        snapshot.fixedBackendReadyNanos = fixedBackendReadyNanos
        snapshot.fixedFirstCommitAttemptNanos = fixedFirstCommitAttemptNanos
        snapshot.fixedTimestampQueryBeforeFirstCommitCount =
            fixedTimestampQueryBeforeFirstCommitCount
        snapshot.drawIssueEndNanos = drawIssueEndNanos
        snapshot.frameIdReservationBeginNanos = frameIdReservationBeginNanos
        snapshot.frameIdReservedNanos = frameIdReservedNanos
        snapshot.postSwapCriticalNanos = postSwapCriticalNanos
        snapshot.postSwapToNextReservationNanos = postSwapToNextReservationNanos
        snapshot.commonCallbackTransactionNanos = commonCallbackTransactionNanos
        snapshot.wakeDispatchToRendererCallbackNanos = wakeDispatchToRendererCallbackNanos
        snapshot.rendererCallbackToCommitEntryNanos = rendererCallbackToCommitEntryNanos
        snapshot.commonCommitEntryToClaimNanos = commonCommitEntryToClaimNanos
        snapshot.backendPhasePartitionValid = backendPhasePartitionValid
        snapshot.readyCommitPriorityViolationFrames = readyCommitPriorityViolationFrames
        snapshot.preCommitRetirementObservationFrames = preCommitRetirementObservationFrames
        snapshot.retainedQueryRequiredCount = retainedQueryRequiredCount
        snapshot.retainedQueryExecutedCount = retainedQueryExecutedCount
        snapshot.retainedQueryWrongSelectionCount = retainedQueryWrongSelectionCount
        snapshot.commitBeforeRetainedQueryCount = commitBeforeRetainedQueryCount
        snapshot.callbackArrivedDuringQueryCount = callbackArrivedDuringQueryCount
        snapshot.evidenceCapsuleDepth = evidenceCapsuleDepth
        snapshot.evidenceCapsuleMaxDepth = evidenceCapsuleMaxDepth
        snapshot.evidenceCapsuleInvalidFrames = evidenceCapsuleInvalidFrames
        synchronized(pendingFixedV4Evidence) {
            val evidence = pendingFixedV4Evidence
            if (evidence[0] == callbackEngineGeneration &&
                evidence[1] == authorityGeneration &&
                evidence[2] == authority && evidence[3] == surfaceEpoch &&
                evidence[4] == frameSequence
            ) {
                snapshot.fixedCandidateSequence = evidence[5]
                snapshot.fixedCandidateRawSequence = evidence[6]
                snapshot.fixedCandidateCaptureNanos = evidence[7]
                snapshot.fixedCandidateClaimNanos = evidence[8]
                snapshot.fixedRefreshIssued = evidence[9].toInt()
                snapshot.fixedRefreshDelivered = evidence[10].toInt()
                snapshot.fixedRefreshPhysicalCallbackSequence = evidence[11]
                snapshot.fixedRefreshCapturedRawSequence = evidence[12]
                snapshot.fixedShadowRawSequence = evidence[13]
                snapshot.fixedShadowPromotionCount = evidence[14]
                snapshot.fixedWakeNoticeSequence = evidence[15]
                snapshot.fixedJoinNoticeSequence = evidence[16]
                snapshot.fixedJoinOpenNanos = evidence[17]
                snapshot.fixedJoinPriorRetirementSequence = evidence[18]
                snapshot.fixedLatchCreditWorkGeneration = evidence[19]
                snapshot.fixedLatchCreditAdmissionSequence = evidence[20]
                snapshot.fixedLatchCreditFrameId = evidence[21]
                snapshot.fixedLatchCreditQueueNanos = evidence[22]
                snapshot.fixedLatchCreditLatchNanos = evidence[23]
                snapshot.fixedLatchCreditQueryCount = evidence[24].toInt()
                snapshot.fixedFinalCorridorBeginNanos = evidence[25]
                snapshot.fixedQueueMarkNanos = evidence[26]
                snapshot.fixedEglSwapEnterNanos = evidence[27]
                snapshot.fixedDecisionToEglEnterNanos = evidence[28]
                snapshot.fixedCommonCommitEntryNanos = evidence[29]
                snapshot.fixedOpportunityClaimNanos = evidence[30]
                evidence[4] = 0L
            }
        }
        val dispatch = protocol.withProtocolLock {
            val token = callbackTokenLocked(
                snapshot.engineGeneration,
                snapshot.authorityGeneration,
                snapshot.authority
            ) ?: return@withProtocolLock null
            if (currentBinding != token) return@withProtocolLock null
            val gpu = gpuInvariants.remove(NativeFrameKey(
                snapshot.engineGeneration,
                snapshot.authorityGeneration,
                snapshot.surfaceEpoch,
                snapshot.frameSequence
            ))
            if (gpu == null || gpu.authority != snapshot.authority ||
                gpu.frameSequence != snapshot.frameSequence
            ) {
                protocol.failLocked()
                return@withProtocolLock null
            }
            snapshot.gpuPhase = gpu.gpuPhase
            snapshot.sealedScene = gpu.sealedScene
            snapshot.resourceSubmitSerial = gpu.resourceSubmitSerial
            snapshot.sealedResourceSubmitSerial = gpu.sealedResourceSubmitSerial
            snapshot.readyTileQueueDepth = gpu.readyTileQueueDepth
            snapshot.nativePublicationsOutstanding = gpu.nativePublicationsOutstanding
            snapshot.pendingPublishAcks = gpu.pendingPublishAcks
            snapshot.retireQueueDepth = gpu.retireQueueDepth
            snapshot.retirementCount = gpu.retirementCount
            snapshot.uploadContextAlive = gpu.uploadContextAlive
            snapshot.lastGpuResourceCompletionNanos = gpu.lastGpuResourceCompletionNanos
            snapshot.sealFenceCompletionNanos = gpu.sealFenceCompletionNanos
            snapshot.uploadContextDestroyedNanos = gpu.uploadContextDestroyedNanos
            snapshot.stageLatchNanos = gpu.stageLatchNanos
            snapshot.firstDownIngressNanos = gpu.firstDownIngressNanos
            snapshot.sealedSceneVersion = gpu.sealedSceneVersion
            snapshot.resourceWorkerState = gpu.resourceWorkerState
            snapshot.resourceWorkerGeneration = gpu.resourceWorkerGeneration
            snapshot.resourceWorkerCreateCount = gpu.resourceWorkerCreateCount
            snapshot.resourceWorkerDestroyCount = gpu.resourceWorkerDestroyCount
            snapshot.activeResourceWorkerCount = gpu.activeResourceWorkerCount
            snapshot.activeUploadContextCount = gpu.activeUploadContextCount
            snapshot.sceneMutationCountSinceSeal = gpu.sceneMutationCountSinceSeal
            snapshot.offscreenWarmFenceCompletionNanos = gpu.offscreenWarmFenceCompletionNanos
            snapshot.predecessorPhysicalCompleteNanos = gpu.predecessorPhysicalCompleteNanos
            snapshot.sealBarrierSerial = gpu.sealBarrierSerial
            snapshot.stageBackbufferReadyNanos = gpu.stageBackbufferReadyNanos
            snapshot.offscreenWarmDrawCount = gpu.offscreenWarmDrawCount
            snapshot.frameWorkKind = gpu.frameWorkKind
            snapshot.admissionSequence = gpu.admissionSequence
            snapshot.plannerInvocationCount = gpu.plannerInvocationCount
            snapshot.backendPresentPrepareCount = gpu.backendPresentPrepareCount
            snapshot.swapAttemptCount = gpu.swapAttemptCount
            snapshot.slotClosedNoAttemptCount = gpu.slotClosedNoAttemptCount
            snapshot.terminalSwapCount = gpu.terminalSwapCount
            snapshot.windowSwapCountBeforeStage = gpu.windowSwapCountBeforeStage
            snapshot.windowFrameIdCountBeforeStage = gpu.windowFrameIdCountBeforeStage
            snapshot.preparedWorkGeneration = gpu.preparedWorkGeneration
            snapshot.swappyWorkGeneration = gpu.swappyWorkGeneration
            snapshot.swappyAdmissionSequence = gpu.swappyAdmissionSequence
            snapshot.preparedDrawCount = gpu.preparedDrawCount
            snapshot.preparedFrameIdReservationCount = gpu.preparedFrameIdReservationCount
            snapshot.admissionConsumed = gpu.admissionConsumed
            snapshot.gpuSceneAdmissionState = gpu.gpuSceneAdmissionState
            snapshot.gpuSceneFormat = gpu.gpuSceneFormat
            snapshot.gpuSceneExpectedTextureCount = gpu.gpuSceneExpectedTextureCount
            snapshot.gpuSceneResidentTextureCount = gpu.gpuSceneResidentTextureCount
            snapshot.gpuSceneExpectedLogicalBytes = gpu.gpuSceneExpectedLogicalBytes
            snapshot.gpuSceneResidentLogicalBytes = gpu.gpuSceneResidentLogicalBytes
            snapshot.gpuSceneDigest = gpu.gpuSceneDigest
        // Latch evidence resolves round-robin, so callback delivery can legitimately be out of
        // submission order. Never let an older proof move progress, terminal-input telemetry, or
        // the public "latest" frame backwards.
            if (!publishLatestFrameSnapshot(snapshot)) return@withProtocolLock null
            if (snapshot.mainIngressOldestNanos > 0L) {
                firstMainIngressNanos.compareAndSet(0L, snapshot.mainIngressOldestNanos)
            }
            frameListener?.let { it to snapshot }
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused", "LongParameterList")
    fun onNativeStageLatchedV2(
        callbackEngineGeneration: Long,
        surfaceEpoch: Long,
        authorityGeneration: Long,
        authority: Long,
        workGeneration: Long,
        frameId: Long,
        frameSequence: Long,
        admissionSequence: Long,
        capsuleSequence: Long,
        stageNonce: Long,
        sceneVersion: Long,
        corridorStartPx: Long,
        corridorEndPx: Long,
        latchEventSequence: Long,
        transactionSerial: Long,
        compositionLatchNanos: Long,
        gpuSceneFormatOrdinal: Int,
        expectedTextureCount: Int,
        residentTextureCount: Int,
        expectedLogicalBytes: Long,
        residentLogicalBytes: Long,
        gpuSceneDigest: String,
        lastResourceCompletionNanos: Long,
        sealFenceCompletionNanos: Long
    ) {
        val key = NativeStageEvidenceKey(
            callbackEngineGeneration,
            surfaceEpoch,
            authorityGeneration,
            authority,
            workGeneration,
            frameId,
            frameSequence,
            admissionSequence,
            capsuleSequence,
            latchEventSequence,
            transactionSerial
        )
        val dispatch = protocol.withProtocolLock {
            val token = callbackTokenLocked(
                callbackEngineGeneration,
                authorityGeneration,
                authority
            ) ?: return@withProtocolLock null
            if (currentBinding != token) return@withProtocolLock null
            val request = stageRequest ?: return@withProtocolLock null
            val evidence = pendingStageEvidence
            if (evidence == null || evidence.first != key) {
                Log.e(
                    "NtkStripRenderer",
                    "schema11 stage evidence key rejected expected=$key observed=${evidence?.first}"
                )
                protocol.failLocked()
                return@withProtocolLock null
            }
            pendingStageEvidence = null
            val snapshot = evidence.second
            val identityExact = request.token == token &&
                request.stageNonce == stageNonce &&
                request.corridorStartPx == corridorStartPx &&
                request.corridorEndPx == corridorEndPx &&
                snapshot.stageCandidate && snapshot.stageNonce == stageNonce &&
                snapshot.sceneVersion == sceneVersion &&
                snapshot.stageCorridorStartPx == corridorStartPx &&
                snapshot.stageCorridorEndPx == corridorEndPx &&
                snapshot.compositionLatchNanos == compositionLatchNanos &&
                snapshot.lastGpuResourceCompletionNanos == lastResourceCompletionNanos &&
                snapshot.sealFenceCompletionNanos == sealFenceCompletionNanos
            val format = NtkGpuSceneFormat.values().getOrNull(gpuSceneFormatOrdinal)
            val gpuProof = if (identityExact && format != null) {
                runCatching {
                    NtkGpuSceneCapacityProof(
                        format,
                        expectedTextureCount,
                        residentTextureCount,
                        expectedLogicalBytes,
                        residentLogicalBytes,
                        gpuSceneDigest,
                        lastResourceCompletionNanos,
                        sealFenceCompletionNanos
                    )
                }.getOrNull()
            } else {
                null
            }
            val exactGpuProof = if (gpuProof != null && gpuProof.isExact &&
                gpuProof.format == request.gpuSceneFormat &&
                gpuProof.expectedTextureCount == request.gpuSceneTextureCount &&
                gpuProof.residentTextureCount == request.gpuSceneTextureCount &&
                gpuProof.expectedLogicalBytes == request.gpuSceneLogicalBytes &&
                gpuProof.residentLogicalBytes == request.gpuSceneLogicalBytes &&
                gpuProof.sceneDigest == request.gpuSceneDigest
            ) {
                gpuProof
            } else {
                null
            }
            if (!identityExact || exactGpuProof == null) {
                Log.e(
                    "NtkStripRenderer",
                    "schema11 stage proof rejected identityExact=$identityExact " +
                        "gpuProofExact=${gpuProof?.isExact} format=$gpuSceneFormatOrdinal " +
                        "textures=$expectedTextureCount/$residentTextureCount " +
                        "bytes=$expectedLogicalBytes/$residentLogicalBytes " +
                        "scene=$sceneVersion latch=$compositionLatchNanos"
                )
            }
            val proof = if (compositionLatchNanos > 0L && sceneVersion > 0L &&
                exactGpuProof != null
            ) {
                NtkStageProof(
                    authority,
                    stageNonce,
                    request.manifestRevision,
                    request.manifestDigest,
                    request.geometryDigest,
                    corridorStartPx,
                    corridorEndPx,
                    sceneVersion,
                    compositionLatchNanos,
                    exactGpuProof
                )
            } else {
                null
            }
            if (proof == null) {
                protocol.failLocked()
            } else {
                stageProof = proof
                val updated = snapshot.withGpuScene(
                    NtkGpuSceneAdmissionState.SEALED.ordinal,
                    gpuSceneFormatOrdinal,
                    expectedTextureCount,
                    residentTextureCount,
                    expectedLogicalBytes,
                    residentLogicalBytes,
                    gpuSceneDigest
                )
                frameSnapshot.compareAndSet(snapshot, updated)
            }
            if (stageRequest === request) {
                stageRequest = null
                request.completion to proof
            } else {
                null
            }
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused")
    fun onNativeStageLatched(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        stageNonce: Long,
        sceneVersion: Long,
        corridorStartPx: Long,
        corridorEndPx: Long,
        compositionLatchNanos: Long,
        gpuSceneFormatOrdinal: Int,
        expectedTextureCount: Int,
        residentTextureCount: Int,
        expectedLogicalBytes: Long,
        residentLogicalBytes: Long,
        gpuSceneDigest: String,
        lastResourceCompletionNanos: Long,
        sealFenceCompletionNanos: Long
    ) {
        val event = NativeStageLatchEvent()
        event.engineGeneration = callbackEngineGeneration
        event.authorityGeneration = authorityGeneration
        event.authority = authority
        event.stageNonce = stageNonce
        event.sceneVersion = sceneVersion
        event.corridorStartPx = corridorStartPx
        event.corridorEndPx = corridorEndPx
        event.compositionLatchNanos = compositionLatchNanos
        event.gpuSceneFormatOrdinal = gpuSceneFormatOrdinal
        event.expectedTextureCount = expectedTextureCount
        event.residentTextureCount = residentTextureCount
        event.expectedLogicalBytes = expectedLogicalBytes
        event.residentLogicalBytes = residentLogicalBytes
        event.gpuSceneDigest = gpuSceneDigest
        event.lastResourceCompletionNanos = lastResourceCompletionNanos
        event.sealFenceCompletionNanos = sealFenceCompletionNanos

        val dispatch = protocol.withProtocolLock {
            if (callbackTokenLocked(
                    event.engineGeneration,
                    event.authorityGeneration,
                    event.authority
                ) == null
            ) return@withProtocolLock null
            val request = stageRequest ?: return@withProtocolLock null
            if (request.token.engineGeneration != event.engineGeneration ||
                request.token.authorityGeneration != event.authorityGeneration ||
                request.token.authority != event.authority ||
                request.stageNonce != event.stageNonce ||
                request.corridorStartPx != event.corridorStartPx ||
                request.corridorEndPx != event.corridorEndPx
            ) return@withProtocolLock null
            val format = NtkGpuSceneFormat.values().getOrNull(event.gpuSceneFormatOrdinal)
            val gpuProof = if (format != null) {
                runCatching {
                    NtkGpuSceneCapacityProof(
                        format,
                        event.expectedTextureCount,
                        event.residentTextureCount,
                        event.expectedLogicalBytes,
                        event.residentLogicalBytes,
                        event.gpuSceneDigest,
                        event.lastResourceCompletionNanos,
                        event.sealFenceCompletionNanos
                    )
                }.getOrNull()
            } else {
                null
            }
            val exactGpuProof = if (gpuProof != null && gpuProof.isExact &&
                gpuProof.format == request.gpuSceneFormat &&
                gpuProof.expectedTextureCount == request.gpuSceneTextureCount &&
                gpuProof.residentTextureCount == request.gpuSceneTextureCount &&
                gpuProof.expectedLogicalBytes == request.gpuSceneLogicalBytes &&
                gpuProof.residentLogicalBytes == request.gpuSceneLogicalBytes &&
                gpuProof.sceneDigest == request.gpuSceneDigest
            ) {
                gpuProof
            } else {
                null
            }
            val proof = if (event.compositionLatchNanos > 0L &&
                event.sceneVersion > 0L && exactGpuProof != null
            ) {
                NtkStageProof(
                    event.authority,
                    event.stageNonce,
                    request.manifestRevision,
                    request.manifestDigest,
                    request.geometryDigest,
                    event.corridorStartPx,
                    event.corridorEndPx,
                    event.sceneVersion,
                    event.compositionLatchNanos,
                    exactGpuProof
                )
            } else {
                null
            }
            if (proof != null) {
                stageProof = proof
                val state = NtkGpuSceneAdmissionState.SEALED.ordinal
                for (entry in gpuInvariants.entries) {
                    val value = entry.value
                    if (value.engineGeneration == event.engineGeneration &&
                        value.authorityGeneration == event.authorityGeneration &&
                        value.authority == event.authority
                    ) {
                        value.gpuSceneAdmissionState = state
                        value.gpuSceneFormat = event.gpuSceneFormatOrdinal
                        value.gpuSceneExpectedTextureCount = event.expectedTextureCount
                        value.gpuSceneResidentTextureCount = event.residentTextureCount
                        value.gpuSceneExpectedLogicalBytes = event.expectedLogicalBytes
                        value.gpuSceneResidentLogicalBytes = event.residentLogicalBytes
                        value.gpuSceneDigest = event.gpuSceneDigest
                    }
                }
                val current = frameSnapshot.get()
                if (current != null &&
                    current.engineGeneration == event.engineGeneration &&
                    current.authorityGeneration == event.authorityGeneration &&
                    current.authority == event.authority
                ) {
                    val updated = current.withGpuScene(
                        state,
                        event.gpuSceneFormatOrdinal,
                        event.expectedTextureCount,
                        event.residentTextureCount,
                        event.expectedLogicalBytes,
                        event.residentLogicalBytes,
                        event.gpuSceneDigest
                    )
                    frameSnapshot.compareAndSet(current, updated)
                }
            } else {
                protocol.failLocked()
            }
            if (stageRequest === request) {
                stageRequest = null
                request.completion to proof
            } else {
                null
            }
        }
        dispatch?.first?.invoke(dispatch.second)
    }

    @Keep
    @Suppress("unused", "LongParameterList")
    fun onNativeAuthorityReleased(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        reducerSurfaceEpoch: Long,
        releaseNonce: Long,
        disposition: Int,
        admissionCloseSerial: Long,
        releaseClaimSerial: Long,
        resourceBarrierSerial: Long,
        resourceCompletionWatermark: Long,
        feedbackBarrierSerial: Long,
        capturedResourceCount: Int,
        capturedRgbaBytes: Long,
        capturedResourceDigest: String,
        releasedResourceCount: Int,
        releasedRgbaBytes: Long,
        releasedResourceDigest: String,
        deletedTextureCount: Int,
        deletedFenceCount: Int,
        releasedBitmapGlobalRefCount: Int,
        drainedUploadCount: Int,
        drainedRetireCount: Int,
        remainingCommandCount: Int,
        remainingResourceCount: Int,
        remainingRgbaBytes: Long,
        remainingFenceCount: Int,
        remainingBitmapGlobalRefCount: Int,
        remainingNativeCallbackCount: Int,
        backendRetirementSerial: Long,
        backendRetiredNanos: Long,
        retiredBackendRemainingThreadCount: Int,
        retiredBackendRemainingEglHandleCount: Int,
        retiredBackendRemainingNativeWindowCount: Int,
        retiredBackendRemainingSwappyLeaseCount: Int,
        retiredBackendRemainingJniGlobalRefCount: Int,
        completedNanos: Long,
        contextReusable: Boolean,
        success: Boolean
    ) {
        val key = AuthorityTokenKey(
            callbackEngineGeneration,
            authorityGeneration,
            authority
        )
        try {
            protocol.withProtocolLock {
                val registration = releaseRegistrations[key] ?: run {
                    failedTokens += key
                    protocol.failLocked()
                    return@withProtocolLock
                }
                val request = registration.request
                if (request.token.engineGeneration != callbackEngineGeneration ||
                    request.token.authorityGeneration != authorityGeneration ||
                    request.token.authority != authority ||
                    request.reducerSurfaceEpoch != reducerSurfaceEpoch ||
                    request.releaseNonce != releaseNonce || registration.stagedAck != null
                ) {
                    failedTokens += key
                    protocol.failLocked()
                    return@withProtocolLock
                }
                val physicalDisposition =
                    NtkPhysicalReleaseDisposition.entries.getOrNull(disposition) ?: run {
                        failedTokens += key
                        protocol.failLocked()
                        return@withProtocolLock
                    }
                val remainingKotlin = remainingKotlinCallbacksLocked(request.token)
                val nativeAck = runCatching {
                    NtkNativeAuthorityReleaseAck(
                        request = request,
                        disposition = physicalDisposition,
                        admissionCloseSerial = admissionCloseSerial,
                        releaseClaimSerial = releaseClaimSerial,
                        resourceBarrierSerial = resourceBarrierSerial,
                        resourceCompletionWatermark = resourceCompletionWatermark,
                        feedbackBarrierSerial = feedbackBarrierSerial,
                        capturedResourceCount = capturedResourceCount,
                        capturedRgbaBytes = capturedRgbaBytes,
                        capturedResourceDigest = capturedResourceDigest,
                        releasedResourceCount = releasedResourceCount,
                        releasedRgbaBytes = releasedRgbaBytes,
                        releasedResourceDigest = releasedResourceDigest,
                        deletedTextureCount = deletedTextureCount,
                        deletedFenceCount = deletedFenceCount,
                        releasedBitmapGlobalRefCount = releasedBitmapGlobalRefCount,
                        drainedUploadCount = drainedUploadCount,
                        drainedRetireCount = drainedRetireCount,
                        remainingCommandCount = remainingCommandCount,
                        remainingResourceCount = remainingResourceCount,
                        remainingRgbaBytes = remainingRgbaBytes,
                        remainingFenceCount = remainingFenceCount,
                        remainingBitmapGlobalRefCount = remainingBitmapGlobalRefCount,
                        remainingNativeCallbackCount = remainingNativeCallbackCount,
                        backendRetirementSerial = backendRetirementSerial,
                        backendRetiredNanos = backendRetiredNanos,
                        retiredBackendRemainingThreadCount = retiredBackendRemainingThreadCount,
                        retiredBackendRemainingEglHandleCount = retiredBackendRemainingEglHandleCount,
                        retiredBackendRemainingNativeWindowCount =
                            retiredBackendRemainingNativeWindowCount,
                        retiredBackendRemainingSwappyLeaseCount =
                            retiredBackendRemainingSwappyLeaseCount,
                        retiredBackendRemainingJniGlobalRefCount =
                            retiredBackendRemainingJniGlobalRefCount,
                        completedNanos = completedNanos,
                        contextReusable = contextReusable,
                        success = success && remainingKotlin == 0
                    )
                }.getOrElse {
                    failedTokens += key
                    protocol.failLocked()
                    return@withProtocolLock
                }
                val phase = protocol.phaseLocked()
                val stateMatchesDisposition = when (physicalDisposition) {
                    NtkPhysicalReleaseDisposition.EXPLICIT_DELETE ->
                        phase == ProtocolPhase.LIVE_DETACHED ||
                            phase == ProtocolPhase.SURFACE_ATTACHING ||
                            phase == ProtocolPhase.SURFACE_READY ||
                            phase == ProtocolPhase.LIVE_ATTACHED ||
                            phase == ProtocolPhase.DETACH_CLOSING ||
                            phase == ProtocolPhase.CLOSING
                    NtkPhysicalReleaseDisposition.CONTEXT_LOST ->
                        phase == ProtocolPhase.DETACH_CLOSING ||
                            phase == ProtocolPhase.RETIRED_BLOCKED ||
                            phase == ProtocolPhase.RETIRED_DISPATCHABLE
                }
                val validProof = stateMatchesDisposition &&
                    NtkTerminalPhysicalReleaseProofValidator.isValid(
                        NtkTerminalPhysicalReleaseProof(nativeAck, remainingKotlin),
                        request
                    )
                if (validProof) {
                    // The detach-admission snapshot is immutable. Any terminal metadata that
                    // removes a binding while that snapshot is being retired must retain the
                    // complete token, including an explicit-delete ACK that was already queued
                    // before context loss and is drained by nativeDetach's feedback barrier.
                    if (phase == ProtocolPhase.DETACH_CLOSING) {
                        val previous = releasedDuringHandoffTokens.putIfAbsent(key, request.token)
                        if (previous != null && previous != request.token) {
                            failedTokens += key
                            protocol.failLocked()
                            return@withProtocolLock
                        }
                    }
                    releasedTokens += key
                    releasingTokens.remove(key)
                    bindings.remove(key)
                    claimableRetiredProofKeys.remove(key)
                    if (currentBinding == request.token) {
                        currentBinding = null
                        currentBindingMirror.set(null)
                    }
                    currentPreparation?.takeIf {
                        it.authorityGeneration == authorityGeneration &&
                            it.token.authority == authority
                    }?.let { currentPreparation = null }
                } else {
                    failedTokens += key
                    protocol.failLocked()
                }
                registration.stagedAck = if (validProof) nativeAck else nativeAck.copy(success = false)
            }
        } catch (_: Throwable) {
            protocol.withProtocolLock {
                failedTokens += key
                protocol.failLocked()
            }
        }
    }

    @Keep
    @Suppress("unused")
    fun onNativeAuthorityReleaseDispatchable(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        releaseNonce: Long
    ) {
        val key = AuthorityTokenKey(
            callbackEngineGeneration,
            authorityGeneration,
            authority
        )
        try {
            protocol.withProtocolLock {
                val registration = releaseRegistrations[key] ?: run {
                    failedTokens += key
                    protocol.failLocked()
                    return@withProtocolLock
                }
                if (registration.request.releaseNonce != releaseNonce ||
                    registration.request.token.engineGeneration != callbackEngineGeneration ||
                    registration.request.token.authorityGeneration != authorityGeneration ||
                    registration.request.token.authority != authority ||
                    registration.stagedAck == null || registration.nativeDispatchable
                ) {
                    failedTokens += key
                    protocol.failLocked()
                    return@withProtocolLock
                }
                registration.nativeDispatchable = true
                scheduleIfDispatchableLocked(key, registration)
            }
        } catch (_: Throwable) {
            protocol.withProtocolLock {
                failedTokens += key
                protocol.failLocked()
            }
        }
    }
}
