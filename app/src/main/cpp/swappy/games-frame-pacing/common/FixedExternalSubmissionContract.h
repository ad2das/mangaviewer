#pragma once

#include <swappy/swappyGL_extra.h>

#include <cstdint>

namespace swappy {

inline SwappyFixedFrameIdentityV1 emptyFixedFrameIdentity() noexcept {
    SwappyFixedFrameIdentityV1 identity{};
    identity.structSize = sizeof(identity);
    identity.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
    return identity;
}

inline SwappyFixedAppliedBufferRefV1 emptyFixedAppliedBufferRef() noexcept {
    SwappyFixedAppliedBufferRefV1 ref{};
    ref.structSize = sizeof(ref);
    ref.version = SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION;
    ref.identity = emptyFixedFrameIdentity();
    return ref;
}

inline SwappyFixedPriorRetirementProofV1
emptyFixedPriorRetirementProof() noexcept {
    SwappyFixedPriorRetirementProofV1 proof{};
    proof.structSize = sizeof(proof);
    proof.version = SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION;
    proof.predecessor = emptyFixedAppliedBufferRef();
    return proof;
}

inline bool fixedFrameIdentityEmpty(
        const SwappyFixedFrameIdentityV1& identity) noexcept {
    return identity.structSize == sizeof(identity) &&
        identity.version == SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION &&
        identity.engineGeneration == 0 && identity.surfaceEpoch == 0 &&
        identity.authorityGeneration == 0 && identity.authority == 0 &&
        identity.workGeneration == 0 && identity.ntkFrameId == 0 &&
        identity.frameSequence == 0 && identity.admissionSequence == 0 &&
        identity.capsuleSequence == 0 && identity.backendSurfaceSerial == 0 &&
        identity.transactionSerial == 0 && identity.bufferSlot == 0 &&
        identity.bufferGeneration == 0 && identity.frameTimelineVsyncId == 0;
}

inline bool fixedFrameIdentityValid(
        const SwappyFixedFrameIdentityV1& identity) noexcept {
    return identity.structSize == sizeof(identity) &&
        identity.version == SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION &&
        identity.engineGeneration != 0 && identity.surfaceEpoch != 0 &&
        identity.authorityGeneration > 0 && identity.authority > 0 &&
        identity.workGeneration != 0 && identity.ntkFrameId != 0 &&
        identity.frameSequence != 0 && identity.admissionSequence != 0 &&
        identity.capsuleSequence != 0 &&
        identity.backendSurfaceSerial != 0 &&
        identity.transactionSerial != 0 && identity.bufferGeneration != 0 &&
        identity.frameTimelineVsyncId != 0;
}

inline bool fixedFrameIdentityExact(
        const SwappyFixedFrameIdentityV1& observed,
        const SwappyFixedFrameIdentityV1& expected) noexcept {
    return fixedFrameIdentityValid(observed) &&
        observed.structSize == expected.structSize &&
        observed.version == expected.version &&
        observed.engineGeneration == expected.engineGeneration &&
        observed.surfaceEpoch == expected.surfaceEpoch &&
        observed.authorityGeneration == expected.authorityGeneration &&
        observed.authority == expected.authority &&
        observed.workGeneration == expected.workGeneration &&
        observed.ntkFrameId == expected.ntkFrameId &&
        observed.frameSequence == expected.frameSequence &&
        observed.admissionSequence == expected.admissionSequence &&
        observed.capsuleSequence == expected.capsuleSequence &&
        observed.backendSurfaceSerial == expected.backendSurfaceSerial &&
        observed.transactionSerial == expected.transactionSerial &&
        observed.bufferSlot == expected.bufferSlot &&
        observed.bufferGeneration == expected.bufferGeneration &&
        observed.frameTimelineVsyncId == expected.frameTimelineVsyncId;
}

inline bool fixedAppliedBufferRefEmpty(
        const SwappyFixedAppliedBufferRefV1& ref) noexcept {
    return ref.structSize == sizeof(ref) &&
        ref.version == SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION &&
        ref.appliedBufferRefSerial == 0 && fixedFrameIdentityEmpty(ref.identity);
}

inline bool fixedAppliedBufferRefValid(
        const SwappyFixedAppliedBufferRefV1& ref) noexcept {
    return ref.structSize == sizeof(ref) &&
        ref.version == SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION &&
        ref.appliedBufferRefSerial != 0 && fixedFrameIdentityValid(ref.identity);
}

inline bool fixedAppliedBufferRefExact(
        const SwappyFixedAppliedBufferRefV1& observed,
        const SwappyFixedAppliedBufferRefV1& expected) noexcept {
    if (fixedAppliedBufferRefEmpty(expected)) {
        return fixedAppliedBufferRefEmpty(observed);
    }
    return fixedAppliedBufferRefValid(observed) &&
        fixedAppliedBufferRefValid(expected) &&
        observed.appliedBufferRefSerial == expected.appliedBufferRefSerial &&
        fixedFrameIdentityExact(observed.identity, expected.identity);
}

inline bool fixedPriorRetirementProofEmpty(
        const SwappyFixedPriorRetirementProofV1& proof) noexcept {
    return proof.structSize == sizeof(proof) &&
        proof.version == SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION &&
        proof.hasPrior == 0 && proof.reserved == 0 &&
        fixedAppliedBufferRefEmpty(proof.predecessor) &&
        proof.retirementSequence == 0 &&
        proof.targetAuthorityRawSequence == 0 &&
        proof.targetPhysicalCallbackSequence == 0 &&
        proof.plannedTargetFrame == 0 && proof.originalTargetFrame == 0 &&
        proof.targetReachedNanos == 0 && proof.retirementCompleteNanos == 0 &&
        proof.proofCommittedNanos == 0 && proof.targetWaitCount == 0 &&
        proof.targetRebaseCount == 0 &&
        proof.retirementCallbackPublishCount == 0 && proof.state == 0 &&
        proof.fatalReason == 0;
}

inline bool fixedPriorRetirementProofValid(
        const SwappyFixedPriorRetirementProofV1& proof) noexcept {
    return proof.structSize == sizeof(proof) &&
        proof.version == SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION &&
        proof.hasPrior == 1 && proof.reserved == 0 &&
        fixedAppliedBufferRefValid(proof.predecessor) &&
        proof.retirementSequence != 0 &&
        proof.targetAuthorityRawSequence != 0 &&
        proof.targetPhysicalCallbackSequence != 0 &&
        proof.plannedTargetFrame > 0 && proof.originalTargetFrame > 0 &&
        proof.targetReachedNanos > 0 &&
        proof.retirementCompleteNanos >= proof.targetReachedNanos &&
        proof.proofCommittedNanos >= proof.retirementCompleteNanos &&
        proof.retirementCallbackPublishCount == 1 &&
        proof.state == SWAPPY_FIXED_RETIREMENT_RETIRED &&
        proof.fatalReason == 0;
}

inline bool fixedPriorRetirementProofExact(
        const SwappyFixedPriorRetirementProofV1& observed,
        const SwappyFixedPriorRetirementProofV1& expected) noexcept {
    if (fixedPriorRetirementProofEmpty(expected)) {
        return fixedPriorRetirementProofEmpty(observed);
    }
    return fixedPriorRetirementProofValid(observed) &&
        fixedPriorRetirementProofValid(expected) &&
        fixedAppliedBufferRefExact(observed.predecessor,
                                   expected.predecessor) &&
        observed.retirementSequence == expected.retirementSequence &&
        observed.targetAuthorityRawSequence ==
            expected.targetAuthorityRawSequence &&
        observed.targetPhysicalCallbackSequence ==
            expected.targetPhysicalCallbackSequence &&
        observed.plannedTargetFrame == expected.plannedTargetFrame &&
        observed.originalTargetFrame == expected.originalTargetFrame &&
        observed.targetReachedNanos == expected.targetReachedNanos &&
        observed.retirementCompleteNanos == expected.retirementCompleteNanos &&
        observed.proofCommittedNanos == expected.proofCommittedNanos &&
        observed.targetWaitCount == expected.targetWaitCount &&
        observed.targetRebaseCount == expected.targetRebaseCount &&
        observed.retirementCallbackPublishCount ==
            expected.retirementCallbackPublishCount &&
        observed.state == expected.state &&
        observed.fatalReason == expected.fatalReason;
}

inline bool fixedLatchObservationValid(
        const SwappyFixedLatchObservationV1& observation) noexcept {
    return observation.structSize == sizeof(observation) &&
        observation.version == SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION &&
        fixedFrameIdentityValid(observation.identity) &&
        observation.latchEventSequence != 0 &&
        observation.compositorLatchNanos > 0 &&
        observation.callbackObservedNanos >= observation.compositorLatchNanos &&
        observation.source == 1 && observation.onCommitCallbackCount == 1;
}

inline bool fixedLatchObservationEmpty(
        const SwappyFixedLatchObservationV1& observation) noexcept {
    return observation.structSize == 0 && observation.version == 0 &&
        observation.identity.structSize == 0 &&
        observation.identity.version == 0 &&
        observation.identity.engineGeneration == 0 &&
        observation.identity.surfaceEpoch == 0 &&
        observation.identity.authorityGeneration == 0 &&
        observation.identity.authority == 0 &&
        observation.identity.workGeneration == 0 &&
        observation.identity.ntkFrameId == 0 &&
        observation.identity.frameSequence == 0 &&
        observation.identity.admissionSequence == 0 &&
        observation.identity.capsuleSequence == 0 &&
        observation.identity.backendSurfaceSerial == 0 &&
        observation.identity.transactionSerial == 0 &&
        observation.identity.bufferSlot == 0 &&
        observation.identity.bufferGeneration == 0 &&
        observation.identity.frameTimelineVsyncId == 0 &&
        observation.latchEventSequence == 0 &&
        observation.compositorLatchNanos == 0 &&
        observation.callbackObservedNanos == 0 &&
        observation.source == 0 &&
        observation.onCommitCallbackCount == 0;
}

inline bool fixedLatchObservationExact(
        const SwappyFixedLatchObservationV1& observed,
        const SwappyFixedLatchObservationV1& expected) noexcept {
    return fixedLatchObservationValid(observed) &&
        fixedLatchObservationValid(expected) &&
        fixedFrameIdentityExact(observed.identity, expected.identity) &&
        observed.latchEventSequence == expected.latchEventSequence &&
        observed.compositorLatchNanos == expected.compositorLatchNanos &&
        observed.callbackObservedNanos == expected.callbackObservedNanos &&
        observed.source == expected.source &&
        observed.onCommitCallbackCount == expected.onCommitCallbackCount;
}

inline bool fixedExternalTransportProfileValid(
        const SwappyFixedExternalTransportProfile& profile) noexcept {
    return profile.structSize == sizeof(profile) &&
        profile.version == SWAPPY_FIXED_EXTERNAL_TRANSPORT_PROFILE_VERSION &&
        profile.profileDigest != 0 && profile.timingGeneration != 0 &&
        profile.refreshPeriodNanos > 0 &&
        profile.appVsyncOffsetNanos >= 0 &&
        profile.appVsyncOffsetNanos < profile.refreshPeriodNanos &&
        profile.presentationDeadlineNanos > 0 &&
        profile.presentationDeadlineNanos < profile.refreshPeriodNanos &&
        profile.transportBoundNanos == profile.refreshPeriodNanos / 2;
}

inline bool fixedExternalTransportReadyValid(
        const SwappyFixedExternalTransportReady& ready) noexcept {
    return ready.structSize == sizeof(ready) &&
        ready.version == SWAPPY_FIXED_EXTERNAL_TRANSPORT_READY_VERSION &&
        fixedExternalTransportProfileValid(ready.profile) &&
        ready.workGeneration != 0 && ready.ntkFrameId != 0 &&
        ready.engineGeneration != 0 && ready.surfaceEpoch != 0 &&
        ready.authorityGeneration > 0 && ready.authority > 0 &&
        ready.frameSequence != 0 && ready.capsuleSequence != 0 &&
        ready.backendSurfaceSerial != 0 && ready.transactionSerial != 0 &&
        ready.bufferGeneration != 0 && ready.acquireFenceSerial != 0 &&
        ready.prepareBeginNanos > 0 &&
        ready.prepareEndNanos >= ready.prepareBeginNanos &&
        ready.setBufferCount == 0 && ready.acquireFenceDupCount == 2 &&
        ready.setBufferPending == 1 && ready.firstStage <= 1 &&
        (fixedAppliedBufferRefEmpty(ready.previousAppliedBufferRef) ||
         fixedAppliedBufferRefValid(ready.previousAppliedBufferRef));
}

inline bool fixedExternalTransportReadyExact(
        const SwappyFixedExternalTransportReady& observed,
        const SwappyFixedExternalTransportReady& expected) noexcept {
    return fixedExternalTransportReadyValid(observed) &&
        observed.profile.structSize == expected.profile.structSize &&
        observed.profile.version == expected.profile.version &&
        observed.profile.profileDigest == expected.profile.profileDigest &&
        observed.profile.timingGeneration == expected.profile.timingGeneration &&
        observed.profile.refreshPeriodNanos ==
            expected.profile.refreshPeriodNanos &&
        observed.profile.appVsyncOffsetNanos ==
            expected.profile.appVsyncOffsetNanos &&
        observed.profile.presentationDeadlineNanos ==
            expected.profile.presentationDeadlineNanos &&
        observed.profile.transportBoundNanos ==
            expected.profile.transportBoundNanos &&
        observed.workGeneration == expected.workGeneration &&
        observed.ntkFrameId == expected.ntkFrameId &&
        observed.engineGeneration == expected.engineGeneration &&
        observed.surfaceEpoch == expected.surfaceEpoch &&
        observed.authorityGeneration == expected.authorityGeneration &&
        observed.authority == expected.authority &&
        observed.frameSequence == expected.frameSequence &&
        observed.capsuleSequence == expected.capsuleSequence &&
        observed.backendSurfaceSerial == expected.backendSurfaceSerial &&
        observed.transactionSerial == expected.transactionSerial &&
        observed.bufferSlot == expected.bufferSlot &&
        observed.bufferGeneration == expected.bufferGeneration &&
        observed.acquireFenceSerial == expected.acquireFenceSerial &&
        observed.prepareBeginNanos == expected.prepareBeginNanos &&
        observed.prepareEndNanos == expected.prepareEndNanos &&
        observed.setBufferCount == expected.setBufferCount &&
        observed.acquireFenceDupCount == expected.acquireFenceDupCount &&
        observed.setBufferPending == expected.setBufferPending &&
        observed.firstStage == expected.firstStage &&
        fixedAppliedBufferRefExact(observed.previousAppliedBufferRef,
                                   expected.previousAppliedBufferRef);
}

inline bool fixedExternalClaimExact(
        const SwappyFixedExternalClaim& observed,
        const SwappyFixedExternalClaim& expected) noexcept {
    return observed.structSize == sizeof(observed) &&
        observed.version == SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION &&
        observed.claimToken != 0 &&
        observed.claimToken == expected.claimToken &&
        observed.workGeneration == expected.workGeneration &&
        observed.admissionSequence == expected.admissionSequence &&
        observed.reservationSequence == expected.reservationSequence &&
        observed.opportunitySequence == expected.opportunitySequence &&
        observed.candidateSequence == expected.candidateSequence &&
        observed.noticeSequence == expected.noticeSequence &&
        observed.plannedTargetFrame == expected.plannedTargetFrame &&
        observed.frameTimelineVsyncId == expected.frameTimelineVsyncId &&
        observed.decisionNanos == expected.decisionNanos &&
        observed.ntkFrameId == expected.ntkFrameId &&
        observed.engineGeneration == expected.engineGeneration &&
        observed.surfaceEpoch == expected.surfaceEpoch &&
        observed.authorityGeneration == expected.authorityGeneration &&
        observed.authority == expected.authority &&
        observed.frameSequence == expected.frameSequence &&
        observed.capsuleSequence == expected.capsuleSequence &&
        observed.backendSurfaceSerial == expected.backendSurfaceSerial &&
        observed.transactionSerial == expected.transactionSerial &&
        observed.bufferSlot == expected.bufferSlot &&
        observed.bufferGeneration == expected.bufferGeneration &&
        observed.acquireFenceSerial == expected.acquireFenceSerial &&
        observed.transportProfileDigest == expected.transportProfileDigest &&
        observed.timingGeneration == expected.timingGeneration &&
        observed.transportBoundNanos == expected.transportBoundNanos &&
        observed.prepareBeginNanos == expected.prepareBeginNanos &&
        observed.prepareEndNanos == expected.prepareEndNanos &&
        observed.initialDecisionNanos == expected.initialDecisionNanos &&
        observed.claimReturnNanos == expected.claimReturnNanos &&
        observed.transportAdmissionOutcome ==
            expected.transportAdmissionOutcome &&
        observed.setBufferCount == expected.setBufferCount &&
        observed.acquireFenceDupCount == expected.acquireFenceDupCount &&
        observed.setBufferPending == expected.setBufferPending &&
        observed.firstStage == expected.firstStage &&
        observed.priorLatchGateRequired ==
            expected.priorLatchGateRequired &&
        observed.priorLatchGateUsed == expected.priorLatchGateUsed &&
        observed.priorCommitProofPendingAtClaim ==
            expected.priorCommitProofPendingAtClaim &&
        observed.priorLatchReserved == 0 &&
        expected.priorLatchReserved == 0 &&
        ((fixedLatchObservationEmpty(observed.priorLatchObservation) &&
          fixedLatchObservationEmpty(expected.priorLatchObservation)) ||
         fixedLatchObservationExact(observed.priorLatchObservation,
                                    expected.priorLatchObservation)) &&
        fixedPriorRetirementProofExact(observed.priorRetirementProof,
                                       expected.priorRetirementProof) &&
        fixedAppliedBufferRefExact(observed.previousAppliedBufferRef,
                                   expected.previousAppliedBufferRef) &&
        (observed.priorRetirementProof.hasPrior == 1
            ? observed.priorLatchGateRequired == 1 &&
              observed.priorLatchGateUsed == 1 &&
              observed.priorCommitProofPendingAtClaim == 0 &&
              fixedLatchObservationValid(
                  observed.priorLatchObservation) &&
              fixedFrameIdentityExact(
                  observed.priorLatchObservation.identity,
                  observed.previousAppliedBufferRef.identity) &&
              observed.priorLatchObservation.callbackObservedNanos <=
                  observed.initialDecisionNanos &&
              fixedAppliedBufferRefExact(
                  observed.priorRetirementProof.predecessor,
                  observed.previousAppliedBufferRef)
            : observed.priorLatchGateRequired == 0 &&
              observed.priorLatchGateUsed == 0 &&
              observed.priorCommitProofPendingAtClaim == 0 &&
              fixedLatchObservationEmpty(
                  observed.priorLatchObservation) &&
              fixedAppliedBufferRefEmpty(
                  observed.previousAppliedBufferRef));
}

inline bool fixedExternalSubmissionExact(
        const SwappyFixedExternalClaim& claim,
        const SwappyFixedExternalSubmission& submission) noexcept {
    return claim.claimToken != 0 && claim.workGeneration != 0 &&
        claim.admissionSequence != 0 && claim.frameTimelineVsyncId != 0 &&
        submission.structSize == sizeof(submission) &&
        submission.version == SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION &&
        submission.claimToken == claim.claimToken &&
        submission.workGeneration == claim.workGeneration &&
        submission.ntkFrameId == claim.ntkFrameId &&
        submission.engineGeneration == claim.engineGeneration &&
        submission.surfaceEpoch == claim.surfaceEpoch &&
        submission.authorityGeneration == claim.authorityGeneration &&
        submission.authority == claim.authority &&
        submission.frameSequence == claim.frameSequence &&
        submission.admissionSequence == claim.admissionSequence &&
        submission.capsuleSequence == claim.capsuleSequence &&
        submission.backendSurfaceSerial == claim.backendSurfaceSerial &&
        submission.transactionSerial == claim.transactionSerial &&
        submission.bufferSlot == claim.bufferSlot &&
        submission.bufferGeneration == claim.bufferGeneration &&
        submission.acquireFenceSerial == claim.acquireFenceSerial &&
        submission.frameTimelineVsyncId == claim.frameTimelineVsyncId &&
        submission.transportProfileDigest == claim.transportProfileDigest &&
        submission.timingGeneration == claim.timingGeneration &&
        submission.transportBoundNanos == claim.transportBoundNanos &&
        submission.transactionPrepareBeginNanos == claim.prepareBeginNanos &&
        submission.transactionPrepareEndNanos == claim.prepareEndNanos &&
        fixedAppliedBufferRefExact(submission.previousAppliedBufferRef,
                                   claim.previousAppliedBufferRef) &&
        fixedAppliedBufferRefValid(submission.appliedBufferRef) &&
        fixedFrameIdentityValid(submission.appliedBufferRef.identity) &&
        submission.appliedBufferRef.identity.engineGeneration ==
            submission.engineGeneration &&
        submission.appliedBufferRef.identity.surfaceEpoch ==
            submission.surfaceEpoch &&
        submission.appliedBufferRef.identity.authorityGeneration ==
            submission.authorityGeneration &&
        submission.appliedBufferRef.identity.authority == submission.authority &&
        submission.appliedBufferRef.identity.workGeneration ==
            submission.workGeneration &&
        submission.appliedBufferRef.identity.ntkFrameId ==
            submission.ntkFrameId &&
        submission.appliedBufferRef.identity.frameSequence ==
            submission.frameSequence &&
        submission.appliedBufferRef.identity.admissionSequence ==
            submission.admissionSequence &&
        submission.appliedBufferRef.identity.capsuleSequence ==
            submission.capsuleSequence &&
        submission.appliedBufferRef.identity.backendSurfaceSerial ==
            submission.backendSurfaceSerial &&
        submission.appliedBufferRef.identity.transactionSerial ==
            submission.transactionSerial &&
        submission.appliedBufferRef.identity.bufferSlot ==
            submission.bufferSlot &&
        submission.appliedBufferRef.identity.bufferGeneration ==
            submission.bufferGeneration &&
        submission.appliedBufferRef.identity.frameTimelineVsyncId ==
            submission.frameTimelineVsyncId &&
        submission.firstStage == claim.firstStage &&
        submission.gpuRenderBeginNanos > 0 &&
        submission.gpuRenderEndNanos >= submission.gpuRenderBeginNanos &&
        submission.gpuFenceIssuedNanos >= submission.gpuRenderEndNanos &&
        submission.gpuFenceWaitReturnNanos >=
            submission.gpuFenceIssuedNanos &&
        submission.transactionPrepareBeginNanos >=
            submission.gpuFenceWaitReturnNanos &&
        submission.transactionPrepareEndNanos >=
            submission.transactionPrepareBeginNanos &&
        submission.transactionPrepareEndNanos <= claim.initialDecisionNanos &&
        claim.initialDecisionNanos <= claim.decisionNanos &&
        (claim.priorRetirementProof.hasPrior == 0 ||
         claim.priorRetirementProof.retirementCompleteNanos <=
             claim.initialDecisionNanos) &&
        claim.claimReturnNanos >= claim.decisionNanos &&
        claim.claimReturnNanos <= submission.transactionApplyBeginNanos &&
        submission.transactionApplyBeginNanos >= claim.decisionNanos &&
        submission.transactionApplyEndNanos >=
            submission.transactionApplyBeginNanos &&
        submission.transactionApplyEndNanos - claim.decisionNanos <=
            claim.transportBoundNanos &&
        claim.setBufferCount == 0 && claim.acquireFenceDupCount == 2 &&
        claim.setBufferPending == 1 && submission.setBufferCount == 1 &&
        submission.acquireFenceDupCount == 2 &&
        submission.frameworkTransferCount == 1 &&
        submission.rendererGpuClientWaitCount == 0 &&
        submission.setFrameTimelineCount == 1 &&
        submission.transactionApplyCount == 1 &&
        submission.applyDisposition == SWAPPY_FIXED_EXTERNAL_APPLIED;
}

inline bool fixedExternalReceiptExact(
        const SwappyFixedExternalSubmissionReceipt& receipt) noexcept {
    if (receipt.structSize != sizeof(receipt) ||
        receipt.version != SWAPPY_FIXED_EXTERNAL_RECEIPT_VERSION ||
        receipt.applyDisposition != SWAPPY_FIXED_EXTERNAL_APPLIED ||
        receipt.retirementSequence == 0 ||
        !fixedExternalClaimExact(receipt.claim, receipt.claim) ||
        !fixedExternalSubmissionExact(receipt.claim, receipt.submission)) {
        return false;
    }
    const std::int32_t phaseReason = receipt.phase.phaseFatalReason;
    return receipt.fatalReason == phaseReason &&
        receipt.retirementFatalReason == phaseReason &&
        receipt.phase.receiptFatalReason == phaseReason &&
        receipt.phase.retirementFatalReason == phaseReason;
}

}  // namespace swappy
