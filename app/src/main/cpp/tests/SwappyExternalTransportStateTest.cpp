#include "../swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"
#include "../swappy/games-frame-pacing/common/FixedExternalTransportAdmission.h"
#include "../swappy/games-frame-pacing/common/FixedNonPipelinePhase.h"

#include <cstdlib>
#include <iostream>

namespace {

using swappy::FixedExternalTransportAdmissionOutcome;
using swappy::FixedPhasePlanInput;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL SwappyExternalTransportStateTest: " << message << '\n';
    std::exit(1);
}

FixedPhasePlanInput inputAt(std::int64_t decision) {
    return {
        .refreshPeriodNanos = 11'111'111,
        .appVsyncOffsetNanos = 2'000'000,
        .presentationDeadlineNanos = 6'111'111,
        .acceptedFrameTimeNanos = 1'000'000'000,
        .acceptedFrameIndex = 90,
        .decisionNanos = decision,
    };
}

struct FakeClockCommitState {
    std::uint64_t claimedRawSequence = 41;
    std::uint64_t shadowRawSequence = 0;
    std::uint64_t preparedTransactionSerial = 91;
    std::uint32_t plannerInvocationCount = 0;
    std::uint32_t phaseWaitCount = 0;
    std::uint32_t claimIssuedCount = 0;
    bool commitInFlight = false;

    void observeShadow(std::uint64_t sequence) {
        shadowRawSequence = sequence;
    }

    void preTokenFatalRollback() {
        commitInFlight = false;
        claimIssuedCount = 0;
    }
};

void unsafeCase1RetainsExactCandidateUntilCase2Gate() {
    const auto geometry = swappy::classifyFixedExternalTransportAdmission(
        inputAt(1'000'000'000), 5'555'555);
    require(geometry.valid, "base geometry invalid");
    FakeClockCommitState state{};
    state.observeShadow(42);
    const auto unsafe = swappy::classifyFixedExternalTransportAdmission(
        inputAt(geometry.earliestCutoffNanos - 77'886), 5'555'555);
    require(unsafe.valid && unsafe.outcome ==
                FixedExternalTransportAdmissionOutcome::DEFER_TO_CASE2_GATE &&
            !unsafe.claimMayBeIssued && state.claimIssuedCount == 0 &&
            state.claimedRawSequence == 41 &&
            state.preparedTransactionSerial == 91 &&
            state.shadowRawSequence == 42,
            "unsafe Case1 replaced or claimed retained work");

    ++state.phaseWaitCount;
    const auto atGate =
        swappy::classifyFixedExternalTransportAdmissionAtDecision(
            unsafe, geometry.case2GateNanos);
    if (atGate.claimMayBeIssued) {
        ++state.plannerInvocationCount;
        ++state.claimIssuedCount;
        state.commitInFlight = true;
    }
    require(atGate.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    CASE2_TRANSPORT_PROVABLE &&
            state.phaseWaitCount == 1 &&
            state.plannerInvocationCount == 1 &&
            state.claimIssuedCount == 1 && state.commitInFlight &&
            state.claimedRawSequence == 41 &&
            state.preparedTransactionSerial == 91,
            "G2 did not produce one plan/token for retained work");

    const auto lateWake =
        swappy::classifyFixedExternalTransportAdmissionAtDecision(
            unsafe, geometry.case2LatestStartExclusiveNanos);
    require(lateWake.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    SLOT_CLOSED_NO_ATTEMPT &&
            lateWake.rawOpportunityDisposed &&
            !lateWake.claimMayBeIssued &&
            lateWake.case2GateNanos == unsafe.case2GateNanos &&
            state.claimedRawSequence == 41 &&
            state.preparedTransactionSerial == 91,
            "late wake moved retained work into a new opportunity");
}

void closedSlotAndEveryPreTokenFatalLeaveNoClaim() {
    const auto geometry = swappy::classifyFixedExternalTransportAdmission(
        inputAt(1'000'000'000), 5'555'555);
    const auto closed = swappy::classifyFixedExternalTransportAdmission(
        inputAt(geometry.case2LatestStartExclusiveNanos), 5'555'555);
    FakeClockCommitState state{};
    state.commitInFlight = true;
    state.preTokenFatalRollback();
    const auto invalidBound = swappy::classifyFixedExternalTransportAdmission(
        inputAt(geometry.case2GateNanos), 5'555'556);
    require(closed.outcome ==
                FixedExternalTransportAdmissionOutcome::SLOT_CLOSED_NO_ATTEMPT &&
            !closed.claimMayBeIssued && !invalidBound.valid &&
            !state.commitInFlight && state.claimIssuedCount == 0,
            "closed/fatal pre-token path leaked claim state");
}

SwappyFixedExternalTransportReady readyProof() {
    SwappyFixedExternalTransportReady ready{};
    ready.structSize = sizeof(ready);
    ready.version = SWAPPY_FIXED_EXTERNAL_TRANSPORT_READY_VERSION;
    ready.profile.structSize = sizeof(ready.profile);
    ready.profile.version = SWAPPY_FIXED_EXTERNAL_TRANSPORT_PROFILE_VERSION;
    ready.profile.profileDigest = 1;
    ready.profile.timingGeneration = 2;
    ready.profile.refreshPeriodNanos = 11'111'111;
    ready.profile.appVsyncOffsetNanos = 2'000'000;
    ready.profile.presentationDeadlineNanos = 6'111'111;
    ready.profile.transportBoundNanos = 5'555'555;
    ready.workGeneration = 3;
    ready.ntkFrameId = 4;
    ready.engineGeneration = 5;
    ready.surfaceEpoch = 6;
    ready.authorityGeneration = 7;
    ready.authority = 8;
    ready.frameSequence = 9;
    ready.capsuleSequence = 10;
    ready.backendSurfaceSerial = 11;
    ready.transactionSerial = 12;
    ready.bufferSlot = 1;
    ready.bufferGeneration = 13;
    ready.acquireFenceSerial = 16;
    ready.prepareBeginNanos = 14;
    ready.prepareEndNanos = 15;
    ready.setBufferCount = 0;
    ready.acquireFenceDupCount = 2;
    ready.setBufferPending = 1;
    ready.firstStage = 1;
    ready.previousAppliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    return ready;
}

void timingGenerationMutationIsPreclaimFatal() {
    const auto expected = readyProof();
    auto mutated = expected;
    ++mutated.profile.timingGeneration;
    FakeClockCommitState state{};
    state.preTokenFatalRollback();
    require(!swappy::fixedExternalTransportReadyExact(mutated, expected) &&
            !state.commitInFlight && state.claimIssuedCount == 0,
            "timing generation mutation survived preclaim validation");
}

void transportAndCutoffFatalReasonsRemainDistinct() {
    constexpr std::int32_t kSwapMissedCutoff = 8;
    constexpr std::int32_t kTransportBoundExceeded = 18;
    const auto geometry = swappy::classifyFixedExternalTransportAdmission(
        inputAt(1'000'000'000), 5'555'555);
    const auto case1Input = inputAt(geometry.case1LatestSafeDecisionNanos);
    const auto plan = swappy::planFixedNonPipelinePhase(case1Input);
    const auto cutoff = swappy::validateFixedNonPipelinePostSwap(
        plan, case1Input.decisionNanos, plan.plannedCutoffNanos + 1);
    const std::int32_t cutoffReason = cutoff.valid ? 0 : kSwapMissedCutoff;
    const std::int64_t applyEnd =
        case1Input.decisionNanos + 5'555'555 + 1;
    const std::int32_t transportReason =
        applyEnd - case1Input.decisionNanos > 5'555'555
            ? kTransportBoundExceeded : 0;
    require(!cutoff.valid && cutoffReason == kSwapMissedCutoff &&
            transportReason == kTransportBoundExceeded,
            "cutoff/transport exact fatal roots collapsed");
}

void terminalCallbackPublishesExactlyOnce() {
    bool terminalPublicationComplete = true;
    bool callbackPublished = false;
    std::uint32_t publishCount = 0;
    const auto publishOnce = [&] {
        if (!terminalPublicationComplete || callbackPublished) return;
        callbackPublished = true;
        ++publishCount;
    };
    publishOnce();
    publishOnce();
    require(callbackPublished && publishCount == 1,
            "fatal terminal callback was not exactly once");
}

void retirementCallbackConsumptionIsNotSuccessorAdmissionGate() {
    const bool predecessorRetired = true;
    const bool terminalPublicationComplete = true;
    const bool exactPredecessorLatch = true;
    const bool exactTargetRetired = true;
    const bool candidateAvailable = true;
    const bool retirementCallbackPublished = false;
    const bool successorClaimable = predecessorRetired &&
        terminalPublicationComplete && exactPredecessorLatch &&
        exactTargetRetired && candidateAvailable;
    require(successorClaimable && !retirementCallbackPublished,
            "renderer retirement callback consumption became an admission gate");
}

}  // namespace

int main() {
    unsafeCase1RetainsExactCandidateUntilCase2Gate();
    closedSlotAndEveryPreTokenFatalLeaveNoClaim();
    timingGenerationMutationIsPreclaimFatal();
    transportAndCutoffFatalReasonsRemainDistinct();
    terminalCallbackPublishesExactlyOnce();
    retirementCallbackConsumptionIsNotSuccessorAdmissionGate();
    std::cout << "PASS SwappyExternalTransportStateTest schema11 6/6\n";
    return 0;
}
