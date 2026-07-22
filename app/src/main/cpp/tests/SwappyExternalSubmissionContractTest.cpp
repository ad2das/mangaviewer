#include "../swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"

#include <cstdlib>
#include <iostream>

namespace {

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL SwappyExternalSubmissionContractTest: " << message << '\n';
    std::exit(1);
}

SwappyFixedFrameIdentityV1 currentIdentity() {
    SwappyFixedFrameIdentityV1 identity{};
    identity.structSize = sizeof(identity);
    identity.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
    identity.engineGeneration = 8;
    identity.surfaceEpoch = 9;
    identity.authorityGeneration = 10;
    identity.authority = 11;
    identity.workGeneration = 2;
    identity.ntkFrameId = 7;
    identity.frameSequence = 12;
    identity.admissionSequence = 1;
    identity.capsuleSequence = 13;
    identity.backendSurfaceSerial = 14;
    identity.transactionSerial = 15;
    identity.bufferSlot = 1;
    identity.bufferGeneration = 16;
    identity.frameTimelineVsyncId = 6;
    return identity;
}

SwappyFixedAppliedBufferRefV1 currentAppliedBufferRef() {
    SwappyFixedAppliedBufferRefV1 ref{};
    ref.structSize = sizeof(ref);
    ref.version = SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION;
    ref.appliedBufferRefSerial = 20;
    ref.identity = currentIdentity();
    return ref;
}

SwappyFixedExternalClaim claim() {
    SwappyFixedExternalClaim value{};
    value.structSize = sizeof(value);
    value.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
    value.claimToken = 1;
    value.workGeneration = 2;
    value.admissionSequence = 1;
    value.reservationSequence = 3;
    value.opportunitySequence = 4;
    value.candidateSequence = 5;
    value.noticeSequence = 6;
    value.plannedTargetFrame = 5;
    value.frameTimelineVsyncId = 6;
    value.decisionNanos = 100;
    value.ntkFrameId = 7;
    value.engineGeneration = 8;
    value.surfaceEpoch = 9;
    value.authorityGeneration = 10;
    value.authority = 11;
    value.frameSequence = 12;
    value.capsuleSequence = 13;
    value.backendSurfaceSerial = 14;
    value.transactionSerial = 15;
    value.bufferSlot = 1;
    value.bufferGeneration = 16;
    value.acquireFenceSerial = 19;
    value.transportProfileDigest = 17;
    value.timingGeneration = 18;
    value.transportBoundNanos = 50;
    value.prepareBeginNanos = 90;
    value.prepareEndNanos = 95;
    value.initialDecisionNanos = 100;
    value.claimReturnNanos = 101;
    value.transportAdmissionOutcome = 2;
    value.setBufferCount = 0;
    value.acquireFenceDupCount = 2;
    value.setBufferPending = 1;
    value.firstStage = 1;
    value.priorRetirementProof =
        swappy::emptyFixedPriorRetirementProof();
    value.previousAppliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    return value;
}

SwappyFixedExternalSubmission submission() {
    SwappyFixedExternalSubmission value{};
    value.structSize = sizeof(value);
    value.version = SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION;
    value.claimToken = 1;
    value.workGeneration = 2;
    value.ntkFrameId = 7;
    value.engineGeneration = 8;
    value.surfaceEpoch = 9;
    value.authorityGeneration = 10;
    value.authority = 11;
    value.frameSequence = 12;
    value.admissionSequence = 1;
    value.capsuleSequence = 13;
    value.backendSurfaceSerial = 14;
    value.transactionSerial = 15;
    value.bufferSlot = 1;
    value.bufferGeneration = 16;
    value.acquireFenceSerial = 19;
    value.frameTimelineVsyncId = 6;
    value.gpuRenderBeginNanos = 80;
    value.gpuRenderEndNanos = 81;
    value.gpuFenceIssuedNanos = 82;
    value.gpuFenceWaitReturnNanos = 83;
    value.transactionApplyBeginNanos = 102;
    value.transactionApplyEndNanos = 120;
    value.setBufferCount = 1;
    value.acquireFenceDupCount = 2;
    value.frameworkTransferCount = 1;
    value.rendererGpuClientWaitCount = 0;
    value.setFrameTimelineCount = 1;
    value.transactionApplyCount = 1;
    value.firstStage = 1;
    value.transportProfileDigest = 17;
    value.timingGeneration = 18;
    value.transportBoundNanos = 50;
    value.transactionPrepareBeginNanos = 90;
    value.transactionPrepareEndNanos = 95;
    value.applyDisposition = SWAPPY_FIXED_EXTERNAL_APPLIED;
    value.previousAppliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    value.appliedBufferRef = currentAppliedBufferRef();
    return value;
}

SwappyFixedAppliedBufferRefV1 predecessorRef() {
    auto ref = currentAppliedBufferRef();
    ref.appliedBufferRefSerial = 19;
    ref.identity.workGeneration = 1;
    ref.identity.ntkFrameId = 6;
    ref.identity.frameSequence = 11;
    ref.identity.transactionSerial = 14;
    ref.identity.bufferSlot = 0;
    ref.identity.bufferGeneration = 15;
    ref.identity.frameTimelineVsyncId = 5;
    return ref;
}

SwappyFixedPriorRetirementProofV1 priorRetirementProof() {
    SwappyFixedPriorRetirementProofV1 proof{};
    proof.structSize = sizeof(proof);
    proof.version = SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION;
    proof.hasPrior = 1;
    proof.predecessor = predecessorRef();
    proof.retirementSequence = 3;
    proof.targetAuthorityRawSequence = 4;
    proof.targetPhysicalCallbackSequence = 5;
    proof.plannedTargetFrame = 70;
    proof.originalTargetFrame = 70;
    proof.targetReachedNanos = 80;
    proof.retirementCompleteNanos = 90;
    proof.proofCommittedNanos = 91;
    proof.targetWaitCount = 1;
    proof.retirementCallbackPublishCount = 1;
    proof.state = SWAPPY_FIXED_RETIREMENT_RETIRED;
    return proof;
}

SwappyFixedExternalClaim successorClaim(bool latchObserved) {
    auto value = claim();
    value.firstStage = 0;
    value.priorRetirementProof = priorRetirementProof();
    value.previousAppliedBufferRef = predecessorRef();
    value.priorCommitProofPendingAtClaim = latchObserved ? 0U : 1U;
    if (latchObserved) {
        value.priorLatchGateRequired = 1;
        value.priorLatchGateUsed = 1;
        value.priorLatchObservation.structSize =
            sizeof(value.priorLatchObservation);
        value.priorLatchObservation.version =
            SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION;
        value.priorLatchObservation.identity = predecessorRef().identity;
        value.priorLatchObservation.latchEventSequence = 7;
        value.priorLatchObservation.compositorLatchNanos = 92;
        value.priorLatchObservation.callbackObservedNanos = 93;
        value.priorLatchObservation.source = 1;
        value.priorLatchObservation.onCommitCallbackCount = 1;
    }
    return value;
}

void exactClaimAndSubmissionPass() {
    const auto expected = claim();
    require(swappy::fixedExternalClaimExact(expected, expected),
            "exact claim rejected");
    require(swappy::fixedExternalSubmissionExact(expected, submission()),
            "exact external submission rejected");
}

void claimMutationFails() {
    const auto expected = claim();
    auto observed = expected;
    ++observed.opportunitySequence;
    require(!swappy::fixedExternalClaimExact(observed, expected),
            "mutated claim accepted");
}

void successorRequiresExactPriorLatchGate() {
    const auto missing = successorClaim(false);
    const auto observed = successorClaim(true);
    require(observed.priorLatchGateRequired == 1 &&
            observed.priorLatchGateUsed == 1 &&
            observed.priorCommitProofPendingAtClaim == 0 &&
            swappy::fixedLatchObservationValid(
                observed.priorLatchObservation) &&
            swappy::fixedExternalClaimExact(observed, observed) &&
            !swappy::fixedExternalClaimExact(missing, missing),
            "successor claim did not require exact predecessor latch proof");
}

void requiredLatchStateMustBeExact() {
    auto unused = successorClaim(true);
    unused.priorLatchGateUsed = 0;
    auto pending = successorClaim(true);
    pending.priorCommitProofPendingAtClaim = 1;
    auto wrongIdentity = successorClaim(true);
    ++wrongIdentity.priorLatchObservation.identity.transactionSerial;
    require(!swappy::fixedExternalClaimExact(unused, unused) &&
            !swappy::fixedExternalClaimExact(pending, pending) &&
            !swappy::fixedExternalClaimExact(wrongIdentity, wrongIdentity),
            "inexact required predecessor latch state accepted");
}

void claimReturnBoundaryIsExact() {
    const auto expected = claim();
    auto changed = expected;
    ++changed.claimReturnNanos;
    auto beforeDecision = submission();
    auto lateClaim = expected;
    lateClaim.claimReturnNanos = lateClaim.decisionNanos - 1;
    require(!swappy::fixedExternalClaimExact(changed, expected) &&
            !swappy::fixedExternalSubmissionExact(lateClaim, beforeDecision),
            "claim return boundary was not exact");
}

void crossClaimSubmissionFails() {
    auto value = submission();
    ++value.claimToken;
    require(!swappy::fixedExternalSubmissionExact(claim(), value),
            "cross-claim submission accepted");
}

void crossTimelineSubmissionFails() {
    auto value = submission();
    ++value.frameTimelineVsyncId;
    require(!swappy::fixedExternalSubmissionExact(claim(), value),
            "cross-timeline submission accepted");
}

void gpuProofOrderingIsExact() {
    auto value = submission();
    value.gpuFenceWaitReturnNanos = value.gpuFenceIssuedNanos - 1;
    require(!swappy::fixedExternalSubmissionExact(claim(), value),
            "invalid GPU proof order accepted");
}

void applyCannotPrecedeAcquireFenceExport() {
    auto value = submission();
    value.transactionApplyBeginNanos = value.gpuFenceWaitReturnNanos - 1;
    require(!swappy::fixedExternalSubmissionExact(claim(), value),
            "transaction preceding acquire-fence export accepted");
}

void exactlyOneSetAndApplyAreRequired() {
    auto setTwice = submission();
    setTwice.setBufferCount = 2;
    auto applyTwice = submission();
    applyTwice.transactionApplyCount = 2;
    require(!swappy::fixedExternalSubmissionExact(claim(), setTwice) &&
            !swappy::fixedExternalSubmissionExact(claim(), applyTwice),
            "duplicate transaction mutation accepted");
}

void zeroIdentityFails() {
    auto value = submission();
    value.transactionSerial = 0;
    require(!swappy::fixedExternalSubmissionExact(claim(), value),
            "zero transaction identity accepted");
}

void fullPreparedIdentityMutationsFail() {
    auto transaction = submission();
    ++transaction.transactionSerial;
    auto slot = submission();
    ++slot.bufferSlot;
    auto generation = submission();
    ++generation.bufferGeneration;
    auto frame = submission();
    ++frame.ntkFrameId;
    auto capsule = submission();
    ++capsule.capsuleSequence;
    auto epoch = submission();
    ++epoch.surfaceEpoch;
    auto profile = submission();
    ++profile.transportProfileDigest;
    require(!swappy::fixedExternalSubmissionExact(claim(), transaction) &&
            !swappy::fixedExternalSubmissionExact(claim(), slot) &&
            !swappy::fixedExternalSubmissionExact(claim(), generation) &&
            !swappy::fixedExternalSubmissionExact(claim(), frame) &&
            !swappy::fixedExternalSubmissionExact(claim(), capsule) &&
            !swappy::fixedExternalSubmissionExact(claim(), epoch) &&
            !swappy::fixedExternalSubmissionExact(claim(), profile),
            "prepared base identity mutation accepted");
}

void preparationAndMutationCountsAreExact() {
    auto latePrepare = submission();
    latePrepare.transactionPrepareEndNanos = claim().decisionNanos + 1;
    auto timelineTwice = submission();
    timelineTwice.setFrameTimelineCount = 2;
    auto notApplied = submission();
    notApplied.applyDisposition = SWAPPY_FIXED_EXTERNAL_NOT_APPLIED;
    require(!swappy::fixedExternalSubmissionExact(claim(), latePrepare) &&
            !swappy::fixedExternalSubmissionExact(claim(), timelineTwice) &&
            !swappy::fixedExternalSubmissionExact(claim(), notApplied),
            "preparation/apply mutation contract accepted invalid proof");
}

void transportBoundIsStrict() {
    auto tooSlow = submission();
    tooSlow.transactionApplyEndNanos =
        claim().decisionNanos + claim().transportBoundNanos + 1;
    require(!swappy::fixedExternalSubmissionExact(claim(), tooSlow),
            "transport bound violation accepted");
}

void appliedFatalReceiptPreservesExactRoot() {
    SwappyFixedExternalSubmissionReceipt receipt{};
    receipt.structSize = sizeof(receipt);
    receipt.version = SWAPPY_FIXED_EXTERNAL_RECEIPT_VERSION;
    receipt.claim = claim();
    receipt.submission = submission();
    receipt.retirementSequence = 99;
    receipt.applyDisposition = SWAPPY_FIXED_EXTERNAL_APPLIED;
    receipt.fatalReason = 8;
    receipt.retirementFatalReason = 8;
    receipt.phase.phaseFatalReason = 8;
    receipt.phase.receiptFatalReason = 8;
    receipt.phase.retirementFatalReason = 8;
    require(swappy::fixedExternalReceiptExact(receipt),
            "APPLIED fatal receipt rejected exact root");
    receipt.phase.receiptFatalReason = 14;
    require(!swappy::fixedExternalReceiptExact(receipt),
            "fatal root overwrite was accepted");
}

}  // namespace

int main() {
    exactClaimAndSubmissionPass();
    claimMutationFails();
    successorRequiresExactPriorLatchGate();
    requiredLatchStateMustBeExact();
    claimReturnBoundaryIsExact();
    crossClaimSubmissionFails();
    crossTimelineSubmissionFails();
    gpuProofOrderingIsExact();
    applyCannotPrecedeAcquireFenceExport();
    exactlyOneSetAndApplyAreRequired();
    zeroIdentityFails();
    fullPreparedIdentityMutationsFail();
    preparationAndMutationCountsAreExact();
    transportBoundIsStrict();
    appliedFatalReceiptPreservesExactRoot();
    std::cout << "PASS SwappyExternalSubmissionContractTest schema11 15/15\n";
    return 0;
}
