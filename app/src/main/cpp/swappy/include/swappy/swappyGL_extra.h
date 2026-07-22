/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @defgroup swappyGL_extra Swappy for OpenGL extras
 * Extra utility functions to use Swappy with OpenGL.
 * @{
 */

#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <jni.h>
#include <stdint.h>

#include "swappy_common.h"
#define SWAPPY_FIXED_PHASE_TELEMETRY_VERSION 11
#define SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION 6
#define SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION 3
#define SWAPPY_FIXED_EXTERNAL_RECEIPT_VERSION 5
#define SWAPPY_FIXED_LATCH_EVENT_CREDIT_VERSION 1
#define SWAPPY_FIXED_EXTERNAL_TRANSPORT_PROFILE_VERSION 1
#define SWAPPY_FIXED_EXTERNAL_TRANSPORT_READY_VERSION 3
#define SWAPPY_FIXED_RESERVATION_RECEIPT_VERSION 1
#define SWAPPY_FIXED_OPPORTUNITY_IDENTITY_VERSION 1
#define SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION 1
#define SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION 1
#define SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION 1
#define SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION 1
#define SWAPPY_FIXED_PRIOR_LATCH_OBSERVATION_NONE 0
#define SWAPPY_FIXED_PRIOR_LATCH_ABSENT_AT_CLAIM 1
#define SWAPPY_FIXED_PRIOR_LATCH_OBSERVED_AT_CLAIM 2

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Exact raw Choreographer authority copied into a fixed admission. This is
 * evidence only; callers cannot manufacture or mutate it.
 */
typedef struct SwappyFixedRawAuthority {
    uint64_t sequence;
    uint64_t physicalCallbackSequence;
    int64_t frameTimeNanos;
    int64_t frameIndex;
    int64_t frameTimelineVsyncId;
    int64_t timelineExpectedPresentationNanos;
    int64_t timelinePresentationDeadlineNanos;
    int64_t callbackReceiptNanos;
    int32_t hasTimeline;
} SwappyFixedRawAuthority;

/** Full immutable producer identity shared by every fixed-path ledger. */
typedef struct SwappyFixedFrameIdentityV1 {
    uint32_t structSize;
    uint32_t version;
    uint64_t engineGeneration;
    uint64_t surfaceEpoch;
    int64_t authorityGeneration;
    int64_t authority;
    uint64_t workGeneration;
    uint64_t ntkFrameId;
    uint64_t frameSequence;
    uint64_t admissionSequence;
    uint64_t capsuleSequence;
    uint64_t backendSurfaceSerial;
    uint64_t transactionSerial;
    uint64_t bufferSlot;
    uint64_t bufferGeneration;
    int64_t frameTimelineVsyncId;
} SwappyFixedFrameIdentityV1;

/** Exact SurfaceControl setBuffer-chain node. */
typedef struct SwappyFixedAppliedBufferRefV1 {
    uint32_t structSize;
    uint32_t version;
    uint64_t appliedBufferRefSerial;
    SwappyFixedFrameIdentityV1 identity;
} SwappyFixedAppliedBufferRefV1;

/**
 * Immutable predecessor proof committed by SwappyCommon when target retirement
 * becomes terminal. A zero hasPrior value is legal only for the first frame.
 */
typedef struct SwappyFixedPriorRetirementProofV1 {
    uint32_t structSize;
    uint32_t version;
    uint32_t hasPrior;
    uint32_t reserved;
    SwappyFixedAppliedBufferRefV1 predecessor;
    uint64_t retirementSequence;
    uint64_t targetAuthorityRawSequence;
    uint64_t targetPhysicalCallbackSequence;
    int64_t plannedTargetFrame;
    int64_t originalTargetFrame;
    int64_t targetReachedNanos;
    int64_t retirementCompleteNanos;
    int64_t proofCommittedNanos;
    uint32_t targetWaitCount;
    uint32_t targetRebaseCount;
    uint32_t retirementCallbackPublishCount;
    int32_t state;
    int32_t fatalReason;
} SwappyFixedPriorRetirementProofV1;

/** Exact real SurfaceFlinger OnCommit observation carried as an optional successor sidecar. */
typedef struct SwappyFixedLatchObservationV1 {
    uint32_t structSize;
    uint32_t version;
    SwappyFixedFrameIdentityV1 identity;
    uint64_t latchEventSequence;
    int64_t compositorLatchNanos;
    int64_t callbackObservedNanos;
    uint32_t source;
    uint32_t onCommitCallbackCount;
} SwappyFixedLatchObservationV1;

/** Inputs to the deterministic fixed non-pipeline phase planner. */
typedef struct SwappyFixedPhasePlanInput {
    int64_t refreshPeriodNanos;
    int64_t appVsyncOffsetNanos;
    int64_t presentationDeadlineNanos;
    int64_t acceptedFrameTimeNanos;
    int64_t acceptedFrameIndex;
    int64_t decisionNanos;
} SwappyFixedPhasePlanInput;

/**
 * Immutable telemetry for one examined fixed opportunity. Integer booleans
 * keep the C ABI explicit.
 */
typedef struct SwappyFixedPhaseTelemetry {
    uint32_t schemaVersion;
    uint64_t sequence;
    uint64_t admissionSequence;
    uint64_t workGeneration;
    uint64_t rawAuthoritySequence;
    uint64_t reservationSequence;
    uint64_t opportunitySequence;
    int32_t opportunityKind;
    uint64_t priorRetirementWorkGeneration;
    uint64_t priorRetirementAdmissionSequence;
    uint64_t priorRetirementSequence;
    SwappyFixedPriorRetirementProofV1 priorRetirementProof;
    uint32_t priorRetirementProofPresent;
    uint32_t priorLatchGateRequired;
    uint32_t priorLatchGateUsed;
    uint32_t priorLatchWaitCount;
    uint32_t priorLatchObservationState;
    uint32_t priorCommitProofPendingAtClaim;
    SwappyFixedLatchObservationV1 priorLatchObservation;
    uint64_t physicalCallbackSequence;
    uint32_t plannerInvocationCount;
    uint32_t closedNoAttemptCount;
    int32_t admissionStatus;
    int32_t outcome;
    int32_t fatalReason;
    int32_t planValid;
    int32_t phaseMissProven;
    int32_t absoluteWaitRequired;
    int32_t absoluteWaitCompleted;
    int32_t admissionConsumed;
    int32_t staleTargetObserved;
    uint64_t candidateSequence;
    uint64_t candidateRawSequence;
    int64_t candidateCaptureNanos;
    int64_t candidateClaimNanos;
    int32_t candidateClaimReason;
    uint32_t refreshIssued;
    uint64_t shadowRawSequence;
    uint64_t shadowPromotionCount;
    uint32_t refreshDelivered;
    uint64_t refreshPhysicalCallbackSequence;
    uint64_t refreshCapturedRawSequence;
    uint64_t joinNoticeSequence;
    int64_t joinOpenNanos;
    uint64_t joinPriorRetirementSequence;
    uint64_t latchEventWorkGeneration;
    uint64_t latchEventNtkFrameId;
    uint64_t latchEventSurfaceEpoch;
    uint64_t latchEventAdmissionSequence;
    uint64_t latchEventSequence;
    uint64_t latchEventTransactionSerial;
    int64_t latchEventCompositorNanos;
    uint32_t latchEventSource;
    int64_t finalCorridorBeginNanos;
    int64_t transactionApplyBeginNanos;
    int64_t transactionApplyEndNanos;
    int64_t decisionToApplyBeginNanos;
    int64_t refreshPeriodNanos;
    int64_t appVsyncOffsetNanos;
    int64_t presentationDeadlineNanos;
    int64_t acceptedFrameTimeNanos;
    int64_t acceptedFrameIndex;
    int64_t decisionNanos;
    int64_t reservationNanos;
    int64_t opportunityReceiptNanos;
    int64_t opportunityPublishNanos;
    int64_t rendererWakeObservedNanos;
    uint64_t wakeNoticeSequence;
    int64_t retirementStageNanos;
    int64_t demandMutationCompleteNanos;
    int64_t terminalVisibleNanos;
    int64_t wakeDispatchNanos;
    int64_t rendererCallbackObservedNanos;
    int64_t commonCommitEntryNanos;
    int64_t opportunityClaimNanos;
    int64_t physicalRefreshNanos;
    int64_t earliestPresentationNanos;
    int64_t earliestCutoffNanos;
    int64_t missedPresentationNanos;
    int64_t plannedPresentationNanos;
    int64_t plannedCutoffNanos;
    int64_t phaseOpenNanos;
    /** Active exclusive pre-swap limit: C1 cutoff or C2 latest start. */
    int64_t latestSwapStartExclusiveNanos;
    int64_t phaseWaitNanos;
    int64_t plannedTargetFrame;
    int64_t frameTimelineVsyncId;
    int64_t timelinePresentationDeadlineNanos;
    int64_t wakeNanos;
    int64_t preSubmitNanos;
    int64_t postSubmitNanos;
    int64_t submitDurationNanos;
    uint32_t gpuFenceWaitCount;
    uint32_t targetRebaseCount;
    uint64_t externalBackendSurfaceSerial;
    uint64_t externalTransactionSerial;
    uint64_t externalWorkGeneration;
    uint64_t externalNtkFrameId;
    int64_t gpuRenderBeginNanos;
    int64_t gpuRenderEndNanos;
    int64_t gpuFenceIssuedNanos;
    int64_t gpuFenceWaitReturnNanos;
    uint64_t acquireFenceSerial;
    uint32_t acquireFenceDupCount;
    uint32_t frameworkTransferCount;
    uint32_t rendererGpuClientWaitCount;
    uint32_t setBufferPending;
    uint32_t setBufferCount;
    uint32_t transactionApplyCount;
    uint64_t retirementDemandIssued;
    uint64_t retirementDemandSatisfied;
    uint64_t retirementDemandCancelled;
    uint64_t opportunityDemandIssued;
    uint64_t opportunityDemandSatisfied;
    uint64_t opportunityDemandCancelled;
    uint64_t supersededBeforeClaimCount;
    uint64_t closedOpportunityCount;
    uint64_t transportProfileDigest;
    uint64_t timingGeneration;
    int64_t transportBoundNanos;
    int64_t initialDecisionNanos;
    int64_t case1CutoffNanos;
    int64_t case2PhaseOpenNanos;
    int64_t case2GateNanos;
    int64_t case2CutoffNanos;
    int64_t case2LatestStartExclusiveNanos;
    int64_t case1LatestSafeDecisionNanos;
    int32_t initialTransportAdmissionOutcome;
    uint32_t phaseWaitCount;
    int64_t case2GateWaitTargetNanos;
    int64_t case2GateWaitReturnNanos;
    int64_t finalDecisionNanos;
    uint32_t claimIssuedCount;
    int64_t transactionPrepareBeginNanos;
    int64_t transactionPrepareEndNanos;
    int64_t decisionToClaimReturnNanos;
    int64_t applyCallDurationNanos;
    int64_t decisionToApplyEndNanos;
    int64_t transportBoundSlackNanos;
    int64_t cutoffSlackNanos;
    uint32_t setFrameTimelineCount;
    int32_t applyDisposition;
    int32_t phaseFatalReason;
    int32_t receiptFatalReason;
    int32_t retirementFatalReason;
    uint32_t retirementCallbackPublishCount;
} SwappyFixedPhaseTelemetry;

/** Exact SurfaceFlinger OnCommit latch event for the submitted predecessor. */
typedef struct SwappyFixedLatchEventCredit {
    uint32_t structSize;
    uint32_t version;
    uint64_t predecessorWorkGeneration;
    uint64_t predecessorNtkFrameId;
    uint64_t predecessorSurfaceEpoch;
    uint64_t predecessorAdmissionSequence;
    uint64_t predecessorLatchEventSequence;
    uint64_t predecessorTransactionSerial;
    int64_t compositorLatchNanos;
    uint32_t source;
    uint32_t reserved;
} SwappyFixedLatchEventCredit;

typedef struct SwappyFixedExternalTransportProfile {
    uint32_t structSize;
    uint32_t version;
    uint64_t profileDigest;
    uint64_t timingGeneration;
    int64_t refreshPeriodNanos;
    int64_t appVsyncOffsetNanos;
    int64_t presentationDeadlineNanos;
    int64_t transportBoundNanos;
} SwappyFixedExternalTransportProfile;

typedef struct SwappyFixedExternalTransportReady {
    uint32_t structSize;
    uint32_t version;
    SwappyFixedExternalTransportProfile profile;
    uint64_t workGeneration;
    uint64_t ntkFrameId;
    uint64_t engineGeneration;
    uint64_t surfaceEpoch;
    int64_t authorityGeneration;
    int64_t authority;
    uint64_t frameSequence;
    uint64_t capsuleSequence;
    uint64_t backendSurfaceSerial;
    uint64_t transactionSerial;
    uint64_t bufferSlot;
    uint64_t bufferGeneration;
    uint64_t acquireFenceSerial;
    int64_t prepareBeginNanos;
    int64_t prepareEndNanos;
    uint32_t setBufferCount;
    uint32_t acquireFenceDupCount;
    uint32_t setBufferPending;
    uint32_t firstStage;
    SwappyFixedAppliedBufferRefV1 previousAppliedBufferRef;
} SwappyFixedExternalTransportReady;

typedef struct SwappyFixedReservationReceipt {
    uint32_t structSize;
    uint32_t version;
    uint64_t workGeneration;
    uint64_t reservationSequence;
    uint64_t rawBaselineSequence;
    int64_t reservationNanos;
} SwappyFixedReservationReceipt;

typedef struct SwappyFixedOpportunityIdentity {
    uint32_t structSize;
    uint32_t version;
    uint64_t workGeneration;
    uint64_t reservationSequence;
    uint64_t opportunitySequence;
    uint64_t candidateSequence;
    uint64_t noticeSequence;
} SwappyFixedOpportunityIdentity;

typedef struct SwappyFixedExternalClaim {
    uint32_t structSize;
    uint32_t version;
    uint64_t claimToken;
    uint64_t workGeneration;
    uint64_t admissionSequence;
    uint64_t reservationSequence;
    uint64_t opportunitySequence;
    uint64_t candidateSequence;
    uint64_t noticeSequence;
    int64_t plannedTargetFrame;
    int64_t frameTimelineVsyncId;
    int64_t decisionNanos;
    uint64_t ntkFrameId;
    uint64_t engineGeneration;
    uint64_t surfaceEpoch;
    int64_t authorityGeneration;
    int64_t authority;
    uint64_t frameSequence;
    uint64_t capsuleSequence;
    uint64_t backendSurfaceSerial;
    uint64_t transactionSerial;
    uint64_t bufferSlot;
    uint64_t bufferGeneration;
    uint64_t acquireFenceSerial;
    uint64_t transportProfileDigest;
    uint64_t timingGeneration;
    int64_t transportBoundNanos;
    int64_t prepareBeginNanos;
    int64_t prepareEndNanos;
    int64_t initialDecisionNanos;
    int64_t claimReturnNanos;
    int32_t transportAdmissionOutcome;
    uint32_t setBufferCount;
    uint32_t acquireFenceDupCount;
    uint32_t setBufferPending;
    uint32_t firstStage;
    uint32_t priorLatchGateRequired;
    uint32_t priorLatchGateUsed;
    uint32_t priorCommitProofPendingAtClaim;
    uint32_t priorLatchReserved;
    SwappyFixedLatchObservationV1 priorLatchObservation;
    SwappyFixedPriorRetirementProofV1 priorRetirementProof;
    SwappyFixedAppliedBufferRefV1 previousAppliedBufferRef;
} SwappyFixedExternalClaim;

typedef struct SwappyFixedExternalSubmission {
    uint32_t structSize;
    uint32_t version;
    uint64_t claimToken;
    uint64_t workGeneration;
    uint64_t ntkFrameId;
    uint64_t engineGeneration;
    uint64_t surfaceEpoch;
    int64_t authorityGeneration;
    int64_t authority;
    uint64_t frameSequence;
    uint64_t admissionSequence;
    uint64_t capsuleSequence;
    uint64_t backendSurfaceSerial;
    uint64_t transactionSerial;
    uint64_t bufferSlot;
    uint64_t bufferGeneration;
    uint64_t acquireFenceSerial;
    int64_t frameTimelineVsyncId;
    int64_t gpuRenderBeginNanos;
    int64_t gpuRenderEndNanos;
    int64_t gpuFenceIssuedNanos;
    int64_t gpuFenceWaitReturnNanos;
    int64_t transactionApplyBeginNanos;
    int64_t transactionApplyEndNanos;
    uint32_t setBufferCount;
    uint32_t acquireFenceDupCount;
    uint32_t frameworkTransferCount;
    uint32_t rendererGpuClientWaitCount;
    uint32_t setFrameTimelineCount;
    uint32_t transactionApplyCount;
    uint32_t firstStage;
    uint64_t transportProfileDigest;
    uint64_t timingGeneration;
    int64_t transportBoundNanos;
    int64_t transactionPrepareBeginNanos;
    int64_t transactionPrepareEndNanos;
    int32_t applyDisposition;
    int32_t reserved;
    SwappyFixedAppliedBufferRefV1 previousAppliedBufferRef;
    SwappyFixedAppliedBufferRefV1 appliedBufferRef;
} SwappyFixedExternalSubmission;

typedef struct SwappyFixedExternalSubmissionReceipt {
    uint32_t structSize;
    uint32_t version;
    SwappyFixedExternalClaim claim;
    SwappyFixedExternalSubmission submission;
    SwappyFixedPhaseTelemetry phase;
    uint64_t priorWorkGeneration;
    uint64_t priorAdmissionSequence;
    uint64_t priorRetirementSequence;
    uint64_t retirementSequence;
    int32_t applyDisposition;
    int32_t fatalReason;
    int32_t retirementFatalReason;
    int32_t reserved;
} SwappyFixedExternalSubmissionReceipt;

typedef struct SwappyFixedPhaseAdmission {
    uint64_t sequence;
    uint64_t workGeneration;
    uint64_t reservationSequence;
    uint64_t opportunitySequence;
    int32_t opportunityKind;
    uint64_t priorRetirementWorkGeneration;
    uint64_t priorRetirementAdmissionSequence;
    uint64_t priorRetirementSequence;
    SwappyFixedRawAuthority raw;
    SwappyFixedPhasePlanInput input;
    SwappyFixedPhaseTelemetry plan;
} SwappyFixedPhaseAdmission;

typedef enum SwappyFixedAdmissionStatus {
    SWAPPY_FIXED_ADMISSION_ADMITTED = 0,
    SWAPPY_FIXED_ADMISSION_WAITING_PRIOR_TARGET = 1,
    SWAPPY_FIXED_ADMISSION_WAITING_CANDIDATE = 2,
    SWAPPY_FIXED_ADMISSION_WAITING_PRIOR_LATCH = 3,
    SWAPPY_FIXED_ADMISSION_SLOT_CLOSED_WAITING_NEXT = 4,
    SWAPPY_FIXED_ADMISSION_FATAL = 6,
} SwappyFixedAdmissionStatus;

typedef enum SwappyFixedCommitStatus {
    SWAPPY_FIXED_COMMIT_SUBMITTED = 0,
    SWAPPY_FIXED_COMMIT_WAITING_PRIOR_TARGET = 1,
    SWAPPY_FIXED_COMMIT_WAITING_CANDIDATE = 2,
    SWAPPY_FIXED_COMMIT_WAITING_PRIOR_LATCH = 3,
    SWAPPY_FIXED_COMMIT_SLOT_CLOSED_WAITING_NEXT = 4,
    SWAPPY_FIXED_COMMIT_SUBMITTED_FATAL_AFTER_EGL_TRUE = 5,
    SWAPPY_FIXED_COMMIT_FATAL = 6,
} SwappyFixedCommitStatus;

typedef enum SwappyFixedExternalApplyDisposition {
    SWAPPY_FIXED_EXTERNAL_NOT_APPLIED = 0,
    SWAPPY_FIXED_EXTERNAL_APPLIED = 1,
} SwappyFixedExternalApplyDisposition;

/**
 * If an app supplies Android Choreographer ticks, this must be called before
 * the first swap and then once per tick.
 */
void SwappyGL_onChoreographer(int64_t frameTimeNanos);

/** Fixed non-pipeline mode used by the NTK renderer. */
void SwappyGL_setFixedNonPipelineModeNS(uint64_t swap_ns);
int32_t SwappyGL_getPipelineModeForNtk(void);
bool SwappyGL_isFixedNonPipelineModeForNtk(void);
bool SwappyGL_isBlockingWaitEnabledForNtk(void);
bool SwappyGL_hasFatalPacingErrorForNtk(void);
bool SwappyGL_isFixedPhaseConfigurationValidForNtk(void);

/** Freeze-time Common reservation precedes GPU rendering. */
bool SwappyGL_reserveFixedFrameForNtk(
    uint64_t work_generation, SwappyFixedReservationReceipt *out_receipt);
bool SwappyGL_abortFixedReservationForNtk(uint64_t work_generation);
bool SwappyGL_markReservedExternalGpuReadyForNtk(
    uint64_t work_generation,
    const SwappyFixedExternalTransportReady *transport_ready);

SwappyFixedCommitStatus SwappyGL_claimPreparedExternalFixedFrameForNtk(
    const SwappyFixedOpportunityIdentity *expected_opportunity,
    const SwappyFixedExternalTransportReady *transport_ready,
    SwappyFixedExternalClaim *out_claim);

bool SwappyGL_recordExternalLatchObservationForNtk(
    const SwappyFixedLatchObservationV1 *observation);

bool SwappyGL_commitExternalFixedSubmissionForNtk(
    const SwappyFixedExternalClaim *claim,
    const SwappyFixedExternalSubmission *submission,
    SwappyFixedExternalSubmissionReceipt *out_receipt);

bool SwappyGL_abortExternalFixedClaimForNtk(uint64_t claim_token);
bool SwappyGL_hasExternalFixedClaimForNtk(void);

bool SwappyGL_markFixedPhaseSubmissionFailureForNtk(void);

bool SwappyGL_getFixedPhaseTelemetryForNtk(
    uint64_t work_generation, SwappyFixedPhaseTelemetry *output);
bool SwappyGL_planFixedPhaseForTesting(
    const SwappyFixedPhasePlanInput *input,
    SwappyFixedPhaseTelemetry *output);

/** Pass callbacks to be called each frame to trace execution. */
void SwappyGL_injectTracer(const SwappyTracer *t);

/** Toggle auto-swap interval detection on/off. */
void SwappyGL_setAutoSwapInterval(bool enabled);

/** Sets the maximal duration for auto-swap interval in nanoseconds. */
void SwappyGL_setMaxAutoSwapIntervalNS(uint64_t max_swap_ns);

/** Toggle auto-pipeline mode on/off. */
void SwappyGL_setAutoPipelineMode(bool enabled);

/** Toggle statistics collection on/off. */
void SwappyGL_enableStats(bool enabled);

/** Record the start of a frame when statistics are enabled. */
void SwappyGL_recordFrameStart(EGLDisplay display, EGLSurface surface);

/** Return collected frame statistics. */
void SwappyGL_getStats(SwappyStats *swappyStats);

/** Clear collected frame statistics. */
void SwappyGL_clearStats(void);

/** Remove callbacks previously added using SwappyGL_injectTracer. */
void SwappyGL_uninjectTracer(const SwappyTracer *t);

/** Reset frame-pacing timing history. */
void SwappyGL_resetFramePacing(void);

/** Enable or disable frame pacing. */
void SwappyGL_enableFramePacing(bool enable);

/** Enable or disable blocking wait when frame pacing is disabled. */
void SwappyGL_enableBlockingWait(bool enable);

#ifdef __cplusplus
};
#endif

/** @} */
