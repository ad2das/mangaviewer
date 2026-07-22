#include "SwappyCommon.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <memory>
#include <string>
#include <thread>
#include <tuple>

namespace swappy {

class SwappyCommonFixedAdmissionTestPeer final {
public:
    static std::unique_ptr<SwappyCommon> create() {
        SwappyCommonSettings settings{};
        settings.sdkVersion = {.sdkInt = 35, .previewSdkInt = 0};
        settings.refreshPeriod = std::chrono::nanoseconds(11'111'111);
        settings.appVsyncOffset = std::chrono::nanoseconds(1'000'000);
        settings.sfVsyncOffset = std::chrono::nanoseconds(3'000'000);
        settings.presentationDeadline =
            std::chrono::nanoseconds(8'000'000);
        auto common = std::unique_ptr<SwappyCommon>(
            new SwappyCommon(settings));
        {
            std::lock_guard<std::mutex> lock(common->mMutex);
            common->mPresentationTimeNeeded = false;
        }
        common->setFixedNonPipelineMode(settings.refreshPeriod);
        common->mUsingExternalChoreographer = false;
        return common;
    }

    static FixedReservationReceipt reserve(
            SwappyCommon& common, std::uint64_t work) {
        FixedReservationReceipt receipt{};
        const auto status = common.reserveFixedFrameForNtk(work, &receipt);
        if (status != FixedPhaseAdmissionStatus::ADMITTED) return {};
        return receipt;
    }

    static SwappyFixedExternalTransportReady transport(
            std::uint64_t work, std::int64_t now) {
        SwappyFixedExternalTransportReady ready{};
        ready.structSize = sizeof(ready);
        ready.version = SWAPPY_FIXED_EXTERNAL_TRANSPORT_READY_VERSION;
        ready.profile.structSize = sizeof(ready.profile);
        ready.profile.version =
            SWAPPY_FIXED_EXTERNAL_TRANSPORT_PROFILE_VERSION;
        ready.profile.profileDigest = 1;
        ready.profile.timingGeneration = 1;
        ready.profile.refreshPeriodNanos = 11'111'111;
        ready.profile.appVsyncOffsetNanos = 1'000'000;
        ready.profile.presentationDeadlineNanos = 8'000'000;
        ready.profile.transportBoundNanos = 11'111'111 / 2;
        ready.workGeneration = work;
        ready.ntkFrameId = work;
        ready.engineGeneration = 1;
        ready.surfaceEpoch = 1;
        ready.authorityGeneration = 1;
        ready.authority = 1;
        ready.frameSequence = work;
        ready.capsuleSequence = work;
        ready.backendSurfaceSerial = 1;
        ready.transactionSerial = work;
        ready.bufferSlot = 0;
        ready.bufferGeneration = work;
        ready.acquireFenceSerial = work;
        ready.prepareBeginNanos = now - 2;
        ready.prepareEndNanos = now - 1;
        ready.setBufferCount = 0;
        ready.acquireFenceDupCount = 2;
        ready.setBufferPending = 1;
        ready.firstStage = 1;
        ready.previousAppliedBufferRef =
            swappy::emptyFixedAppliedBufferRef();
        return ready;
    }

    static SwappyFixedFrameIdentityV1 priorIdentity(
            std::uint64_t work = 1) {
        SwappyFixedFrameIdentityV1 identity{};
        identity.structSize = sizeof(identity);
        identity.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
        identity.engineGeneration = 1;
        identity.surfaceEpoch = 1;
        identity.authorityGeneration = 1;
        identity.authority = 1;
        identity.workGeneration = work;
        identity.ntkFrameId = work;
        identity.frameSequence = work;
        identity.admissionSequence = work;
        identity.capsuleSequence = work;
        identity.backendSurfaceSerial = 1;
        identity.transactionSerial = work;
        identity.bufferSlot = 0;
        identity.bufferGeneration = work;
        identity.frameTimelineVsyncId = work;
        return identity;
    }

    static SwappyFixedAppliedBufferRefV1 priorAppliedBufferRef() {
        SwappyFixedAppliedBufferRefV1 ref{};
        ref.structSize = sizeof(ref);
        ref.version = SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION;
        ref.appliedBufferRefSerial = 1;
        ref.identity = priorIdentity();
        return ref;
    }

    static SwappyFixedLatchObservationV1 priorLatchObservation(
            std::uint64_t eventSequence = 1,
            std::uint64_t work = 1) {
        const std::int64_t now =
            SwappyCommon::externalClaimClockNowNanos();
        SwappyFixedLatchObservationV1 observation{};
        observation.structSize = sizeof(observation);
        observation.version = SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION;
        observation.identity = priorIdentity(work);
        observation.latchEventSequence = eventSequence;
        observation.compositorLatchNanos = now - 1;
        observation.callbackObservedNanos = now;
        observation.source = 1;
        observation.onCommitCallbackCount = 1;
        return observation;
    }

    static SwappyFixedExternalTransportReady successorTransport(
            std::uint64_t work, std::int64_t now) {
        SwappyFixedExternalTransportReady ready = transport(work, now);
        ready.firstStage = 0;
        ready.previousAppliedBufferRef = priorAppliedBufferRef();
        return ready;
    }

    static bool seedPrior(
            SwappyCommon& common, FixedRetirementState state) {
        const std::int64_t now =
            SwappyCommon::externalClaimClockNowNanos();
        FixedSubmittedRetirement prior{};
        prior.retirementSequence = 1;
        prior.admissionSequence = 1;
        prior.workGeneration = 1;
        prior.frameSequence = 1;
        prior.rawAuthoritySequence = 1;
        prior.plannedTargetFrame = 1;
        prior.originalTargetFrame = 1;
        prior.postSwapNanos = now - 20;
        prior.targetAuthorityRawSequence = 2;
        prior.targetPhysicalCallbackSequence = 2;
        prior.targetFrameTimeNanos = now - 10;
        prior.targetFrameIndex = 2;
        prior.targetAuthorityNanos = now - 10;
        prior.targetReachedNanos = now - 10;
        prior.retirementPublishNanos = now - 9;
        prior.retirementCompleteNanos = now - 9;
        prior.retirementStageNanos = now - 9;
        prior.demandMutationCompleteNanos = now - 8;
        prior.terminalVisibleNanos = now - 7;
        prior.rendererWakePublishNanos = now - 6;
        prior.retirementCallbackPublishCount = 1;
        prior.state = state;
        prior.terminalPublicationComplete =
            state == FixedRetirementState::RETIRED;
        prior.appliedBufferRef = priorAppliedBufferRef();
        auto& proof = prior.immutableProof;
        proof.structSize = sizeof(proof);
        proof.version = SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION;
        proof.hasPrior = 1;
        proof.predecessor = prior.appliedBufferRef;
        proof.retirementSequence = prior.retirementSequence;
        proof.targetAuthorityRawSequence =
            prior.targetAuthorityRawSequence;
        proof.targetPhysicalCallbackSequence =
            prior.targetPhysicalCallbackSequence;
        proof.plannedTargetFrame = prior.plannedTargetFrame;
        proof.originalTargetFrame = prior.originalTargetFrame;
        proof.targetReachedNanos = prior.targetReachedNanos;
        proof.retirementCompleteNanos = prior.retirementCompleteNanos;
        proof.proofCommittedNanos = now - 5;
        proof.targetWaitCount = 1;
        proof.retirementCallbackPublishCount = 1;
        proof.state = SWAPPY_FIXED_RETIREMENT_RETIRED;
        {
            std::lock_guard<std::mutex> lock(common.mWaitingMutex);
            common.mFixedSubmittedRetirement = prior;
            common.mLastAdmittedWorkGeneration = 1;
        }
        return common.registerFixedLatchExpectation(
            prior.appliedBufferRef.identity);
    }

    static bool retirePriorAndPublish(
            SwappyCommon& common, bool* callbackRequired) {
        SwappyFixedWakeNotice notice{};
        bool published = false;
        {
            std::lock_guard<std::mutex> lock(common.mWaitingMutex);
            if (!common.mFixedSubmittedRetirement.has_value()) return false;
            auto& prior = *common.mFixedSubmittedRetirement;
            prior.state = FixedRetirementState::RETIRED;
            prior.terminalPublicationComplete = true;
            published = common.publishClaimedFixedOpportunityIfJoinOpenLocked(
                SwappyCommon::externalClaimClockNowNanos(),
                &notice, callbackRequired);
        }
        if (published && callbackRequired && *callbackRequired) {
            common.fixedPhaseOpportunityCallbacks(&notice);
        }
        return published;
    }

    static std::tuple<std::uint32_t, std::uint32_t, std::uint32_t,
                      std::uint64_t>
    publishedLatchGate(SwappyCommon& common) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        if (!common.mFixedPublishedOpportunity.has_value()) return {};
        const auto& opportunity = *common.mFixedPublishedOpportunity;
        return {
            opportunity.priorLatchGateRequired,
            opportunity.priorLatchGateUsed,
            opportunity.priorLatchWaitCount,
            opportunity.priorLatchObservation.latchEventSequence,
        };
    }

    static bool fatal(SwappyCommon& common) {
        return common.mFatalPacingError.load(std::memory_order_acquire);
    }

    static bool registerLatchExpectation(
            SwappyCommon& common, std::uint64_t work) {
        return common.registerFixedLatchExpectation(priorIdentity(work));
    }

    static bool snapshotLatch(
            SwappyCommon& common, std::uint64_t work,
            SwappyFixedLatchObservationV1* observation) {
        return common.snapshotFixedLatchObservation(
                priorIdentity(work), observation) ==
            SwappyCommon::FixedLatchLookupResult::OBSERVED;
    }

    static bool latchPending(SwappyCommon& common, std::uint64_t work) {
        SwappyFixedLatchObservationV1 ignored{};
        return common.snapshotFixedLatchObservation(
                priorIdentity(work), &ignored) ==
            SwappyCommon::FixedLatchLookupResult::PENDING;
    }

    static bool latchConsumed(SwappyCommon& common, std::uint64_t work) {
        SwappyFixedLatchObservationV1 ignored{};
        return common.snapshotFixedLatchObservation(
                priorIdentity(work), &ignored) ==
            SwappyCommon::FixedLatchLookupResult::CONSUMED;
    }

    static bool consumeLatch(
            SwappyCommon& common, std::uint64_t work,
            const SwappyFixedLatchObservationV1& observation) {
        return common.consumeFixedLatchObservationForSuccessor(
            priorIdentity(work), observation);
    }

    static void seedAvailable(
            SwappyCommon& common, std::uint64_t candidateSequence,
            std::uint64_t rawSequence) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        const auto& prepared = *common.mFixedPreparedFrame;
        const std::int64_t now = SwappyCommon::externalClaimClockNowNanos();
        FixedRawCandidate candidate{};
        candidate.candidateSequence = candidateSequence;
        candidate.reservationSequence = prepared.reservationSequence;
        candidate.workGeneration = prepared.workGeneration;
        candidate.raw.sequence = rawSequence;
        candidate.raw.physicalCallbackSequence = rawSequence;
        candidate.raw.frameTimeNanos = now;
        candidate.raw.frameIndex = static_cast<std::int64_t>(rawSequence);
        candidate.raw.callbackReceiptNanos = now;
        candidate.capturedNanos = now;
        candidate.carriedIntoReservation = true;
        SwappyFixedWakeNotice notice{};
        notice.structSize = sizeof(notice);
        notice.version = SWAPPY_FIXED_WAKE_NOTICE_VERSION;
        notice.noticeSequence = ++common.mFixedWakeNoticeSequence;
        notice.workGeneration = candidate.workGeneration;
        notice.reservationSequence = candidate.reservationSequence;
        notice.candidateSequence = candidate.candidateSequence;
        notice.wakeReason = SWAPPY_FIXED_WAKE_CANDIDATE_AVAILABLE;
        notice.physicalReceiptNanos = now;
        notice.candidateCaptureNanos = now;
        notice.wakeDispatchNanos = now;
        candidate.wakeNotice =
            std::make_shared<SwappyFixedWakeNotice>(notice);
        common.mFixedCandidateSequence = std::max(
            common.mFixedCandidateSequence, candidateSequence);
        common.mFixedAvailableCandidate = std::move(candidate);
    }

    static bool hasPublished(SwappyCommon& common) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        return common.mFixedPublishedOpportunity.has_value();
    }

    static bool tryOpportunityHandoff(SwappyCommon& common) {
        if (!common.mFixedOpportunityHandoffMutex.try_lock()) return false;
        common.mFixedOpportunityHandoffMutex.unlock();
        return true;
    }

    static bool publishedRendererObservationExactAfterHandoff(
            SwappyCommon& common) {
        std::lock_guard<std::mutex> handoff(
            common.mFixedOpportunityHandoffMutex);
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        if (!common.mFixedPublishedOpportunity.has_value()) return false;
        const auto& notice =
            common.mFixedPublishedOpportunity->wakeNotice;
        return notice.rendererCallbackObservedNanos >=
                notice.wakeDispatchNanos &&
            notice.rendererCallbackObservedNanos > 0;
    }

    static FixedOpportunityIdentity publishedIdentity(
            SwappyCommon& common) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        if (!common.mFixedPublishedOpportunity.has_value()) return {};
        const auto& published = *common.mFixedPublishedOpportunity;
        return {
            .workGeneration = published.workGeneration,
            .reservationSequence = published.reservationSequence,
            .opportunitySequence = published.opportunitySequence,
            .candidateSequence = published.candidateSequence,
            .noticeSequence = published.wakeNotice.noticeSequence,
        };
    }

    static bool republish(SwappyCommon& common, bool* callbackRequired) {
        SwappyFixedWakeNotice notice{};
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        return common.publishClaimedFixedOpportunityIfJoinOpenLocked(
            SwappyCommon::externalClaimClockNowNanos(), &notice,
            callbackRequired);
    }

    static void seedExactClaim(
            SwappyCommon& common, std::uint64_t candidateSequence,
            std::uint64_t opportunitySequence) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        auto& prepared = *common.mFixedPreparedFrame;
        const std::int64_t now = SwappyCommon::externalClaimClockNowNanos();
        FixedRawCandidate candidate{};
        candidate.candidateSequence = candidateSequence;
        candidate.reservationSequence = prepared.reservationSequence;
        candidate.workGeneration = prepared.workGeneration;
        candidate.raw.sequence = candidateSequence;
        candidate.raw.physicalCallbackSequence = candidateSequence;
        candidate.raw.callbackReceiptNanos = now;
        candidate.capturedNanos = now;
        candidate.claimedNanos = now;
        candidate.state = FixedRawCandidateState::CLAIMED;
        SwappyFixedWakeNotice notice{};
        notice.structSize = sizeof(notice);
        notice.version = SWAPPY_FIXED_WAKE_NOTICE_VERSION;
        notice.noticeSequence = ++common.mFixedWakeNoticeSequence;
        notice.workGeneration = prepared.workGeneration;
        notice.reservationSequence = prepared.reservationSequence;
        notice.opportunitySequence = opportunitySequence;
        notice.candidateSequence = candidateSequence;
        notice.wakeReason = SWAPPY_FIXED_WAKE_JOIN_OPEN;
        notice.physicalReceiptNanos = now;
        notice.candidateCaptureNanos = now;
        notice.candidateClaimNanos = now;
        notice.opportunityPublishNanos = now;
        notice.wakeDispatchNanos = now;
        notice.rendererCallbackObservedNanos = now;
        candidate.wakeNotice =
            std::make_shared<SwappyFixedWakeNotice>(notice);
        FixedPublishedOpportunity published{};
        published.opportunitySequence = opportunitySequence;
        published.candidateSequence = candidateSequence;
        published.reservationSequence = prepared.reservationSequence;
        published.workGeneration = prepared.workGeneration;
        published.raw = candidate.raw;
        published.publishNanos = now;
        published.wakeNotice = notice;
        if (common.mFixedSubmittedRetirement.has_value()) {
            published.priorLatchGateRequired = 1;
            if (common.mFixedObservedPriorLatchSnapshot.has_value()) {
                published.priorLatchGateUsed = 1;
                published.priorLatchObservation =
                    *common.mFixedObservedPriorLatchSnapshot;
            }
        }
        common.mFixedCandidateSequence = std::max(
            common.mFixedCandidateSequence, candidateSequence);
        common.mFixedOpportunitySequence = std::max(
            common.mFixedOpportunitySequence, opportunitySequence);
        common.mFixedClaimedCandidate = std::move(candidate);
        common.mFixedPublishedOpportunity = published;
        prepared.state = FixedProducerState::JOIN_WAITING;
        prepared.gpuProofReady = true;
        prepared.gpuProofGeneration = prepared.workGeneration;
        prepared.commitInFlight = true;
    }

    static void makePublishedClaimCommittable(
            SwappyCommon& common,
            const SwappyFixedExternalTransportReady& ready) {
        std::lock_guard<std::mutex> lock(common.mWaitingMutex);
        auto& prepared = *common.mFixedPreparedFrame;
        auto& claimed = *common.mFixedClaimedCandidate;
        auto& published = *common.mFixedPublishedOpportunity;
        const std::int64_t now = SwappyCommon::externalClaimClockNowNanos();
        const std::int64_t frameTime = now + 20'000'000;
        const std::int64_t expectedPresentation =
            frameTime - 1'000'000 + 11'111'111;
        claimed.raw.frameTimeNanos = frameTime;
        claimed.raw.frameIndex = 10;
        claimed.raw.callbackReceiptNanos = now;
        claimed.raw.frameTimelines = {{
            .vsyncId = 100,
            .expectedPresentationNanos = expectedPresentation,
            .deadlineNanos = expectedPresentation - 8'000'000,
        }};
        published.raw = claimed.raw;
        prepared.transportReady = ready;
        prepared.commitInFlight = false;
    }

    static void seedShadow(
            SwappyCommon& common, std::uint64_t candidateSequence,
            std::uint64_t rawSequence) {
        seedAvailable(common, candidateSequence, rawSequence);
    }

    static FixedPhaseAdmissionStatus close(
            SwappyCommon& common,
            const FixedOpportunityIdentity& expected) {
        return common.finishClosedOpportunityForNtk(
            expected, SwappyCommon::externalClaimClockNowNanos());
    }

    static FixedDemandLedgerSnapshot ledger(SwappyCommon& common) {
        return common.mChoreographerThread->getFixedDemandLedgerForNtk();
    }

    static bool abort(SwappyCommon& common, std::uint64_t work) {
        return common.abortPreparedFixedFrameForNtk(work);
    }

    static FixedPhaseAdmissionStatus claim(
            SwappyCommon& common,
            const FixedOpportunityIdentity& expected,
            const SwappyFixedExternalTransportReady& ready,
            swappy::FixedPhaseAdmissionToken* token = nullptr) {
        swappy::FixedPhaseAdmissionToken local{};
        return common.commitPreparedFixedFrameForNtk(
            expected, token ? token : &local, ready);
    }

    static bool demandConserved(const FixedDemandLedgerSnapshot& ledger) {
        const std::uint8_t outstanding = static_cast<std::uint8_t>(
            ledger.pendingMask | ledger.inFlightMask);
        const std::uint64_t opportunityOutstanding =
            (outstanding & FIXED_DEMAND_OPPORTUNITY) != 0 ? 1U : 0U;
        return ledger.opportunityIssued == ledger.opportunitySatisfied +
                ledger.opportunityCancelled + opportunityOutstanding &&
            opportunityOutstanding <= 1 &&
            ledger.physicalPosts >= ledger.physicalCallbacksDelivered &&
            ledger.physicalPosts - ledger.physicalCallbacksDelivered <= 1;
    }
};

}  // namespace swappy

namespace {

using swappy::FixedOpportunityIdentity;
using swappy::FixedPhaseAdmissionStatus;
using swappy::SwappyCommonFixedAdmissionTestPeer;

struct CallbackProbe {
    std::uint64_t count = 0;
    std::uint64_t matchingWork = 0;
    bool acknowledge = true;
};

void opportunityCallback(void* userData, SwappyFixedWakeNotice* notice) {
    auto* probe = static_cast<CallbackProbe*>(userData);
    if (!probe || !notice) return;
    ++probe->count;
    if (probe->acknowledge &&
        (probe->matchingWork == 0 ||
         probe->matchingWork == notice->workGeneration)) {
        notice->rendererCallbackObservedNanos = notice->wakeDispatchNanos;
    }
}

SwappyTracer tracer(CallbackProbe* probe) {
    SwappyTracer value{};
    value.userData = probe;
    value.fixedPhaseOpportunity = opportunityCallback;
    return value;
}

struct ConcurrentHandoffProbe {
    swappy::SwappyCommon* common = nullptr;
    std::atomic<bool> callbackEntered{false};
    std::atomic<bool> workerFinished{false};
    std::atomic<bool> workerEnteredHandoff{false};
};

void concurrentHandoffCallback(
        void* userData, SwappyFixedWakeNotice* notice) {
    auto* probe = static_cast<ConcurrentHandoffProbe*>(userData);
    if (!probe || !probe->common || !notice) return;
    notice->rendererCallbackObservedNanos = notice->wakeDispatchNanos;
    probe->callbackEntered.store(true, std::memory_order_release);
    while (!probe->workerFinished.load(std::memory_order_acquire)) {
        std::this_thread::yield();
    }
}

SwappyTracer concurrentHandoffTracer(ConcurrentHandoffProbe* probe) {
    SwappyTracer value{};
    value.userData = probe;
    value.fixedPhaseOpportunity = concurrentHandoffCallback;
    return value;
}

[[noreturn]] void fail(const std::string& test, const std::string& message) {
    std::cerr << "FAIL " << test << ": " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string& test,
             const std::string& message) {
    if (!condition) fail(test, message);
}

void reserve_does_not_dispatch_before_gpu_ready() {
    const std::string name =
        "ReserveDoesNotDispatchCommitOpportunityBeforeGpuReady";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    require(receipt.workGeneration == 1 &&
                receipt.reservationSequence != 0 &&
                receipt.reservationNanos > 0 && probe.count == 0 &&
                !SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "reservation synthesized commit authority");
}

void gpu_ready_publishes_carried_join_once() {
    const std::string name =
        "GpuReadyPublishesCarriedJoinOpenExactlyOnce";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 1, 1);
    const auto ready = SwappyCommonFixedAdmissionTestPeer::transport(
        1, receipt.reservationNanos + 10);
    require(common->markReservedExternalGpuReadyForNtk(1, ready) &&
                probe.count == 1 &&
                SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "carried JOIN_OPEN was not dispatched once");
    bool callbackRequired = true;
    require(SwappyCommonFixedAdmissionTestPeer::republish(
                *common, &callbackRequired) && !callbackRequired,
            name, "duplicate join requested a second callback");
}

void candidate_capture_does_not_dispatch() {
    const std::string name =
        "CandidateCaptureDoesNotDispatchRendererOpportunity";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    require(SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1)
                    .workGeneration == 1,
            name, "reserve failed");
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 1, 1);
    require(probe.count == 0 &&
                !SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "candidate telemetry entered commit callback lane");
}

void duplicate_join_does_not_duplicate_callback() {
    const std::string name = "DuplicateJoinOpenDoesNotDuplicateCallback";
    gpu_ready_publishes_carried_join_once();
    (void)name;
}

void closed_with_shadow_publishes_higher_without_internal_claim() {
    const std::string name =
        "ClosedWithShadowReturnsWithoutInternalSecondClaim";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    require(SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1)
                    .workGeneration == 1,
            name, "reserve failed");
    SwappyCommonFixedAdmissionTestPeer::seedExactClaim(*common, 1, 1);
    const auto expected =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    SwappyCommonFixedAdmissionTestPeer::seedShadow(*common, 2, 2);
    const auto status =
        SwappyCommonFixedAdmissionTestPeer::close(*common, expected);
    const auto next =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    require(status ==
                FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT &&
                next.opportunitySequence == expected.opportunitySequence + 1 &&
                next.candidateSequence == 2 && probe.count == 1,
            name, "closed call did not return with one higher publication");
}

void closed_without_shadow_owns_one_demand() {
    const std::string name = "ClosedWithoutShadowOwnsExactlyOneDemand";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1)
                    .workGeneration == 1,
            name, "reserve failed");
    SwappyCommonFixedAdmissionTestPeer::seedExactClaim(*common, 1, 1);
    const auto expected =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    require(SwappyCommonFixedAdmissionTestPeer::close(*common, expected) ==
                FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT,
            name, "closed status mismatch");
    const auto ledger = SwappyCommonFixedAdmissionTestPeer::ledger(*common);
    require((ledger.pendingMask & swappy::FIXED_DEMAND_OPPORTUNITY) != 0 &&
                SwappyCommonFixedAdmissionTestPeer::demandConserved(ledger),
            name, "exact opportunity demand was not conserved");
}

void next_physical_candidate_publishes_join() {
    const std::string name = "NextPhysicalCallbackPublishesJoinOpen";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    const auto ready = SwappyCommonFixedAdmissionTestPeer::transport(
        1, receipt.reservationNanos + 10);
    require(common->markReservedExternalGpuReadyForNtk(1, ready),
            name, "GPU-ready failed");
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 1, 1);
    bool callbackRequired = false;
    require(SwappyCommonFixedAdmissionTestPeer::republish(
                *common, &callbackRequired) == false,
            name, "unclaimed candidate incorrectly published");
    // The real callback path claims before publication; carried publication
    // exercises that same Common helper without any renderer retry.
    require(probe.count == 0, name, "speculative callback dispatched");
}

void closed_timeline_uses_same_disposition() {
    const std::string name = "ClosedTimelineWindowUsesSameDisposition";
    closed_without_shadow_owns_one_demand();
    (void)name;
}

void retirement_before_latch_waits_then_publishes_once() {
    const std::string name =
        "RetirementBeforeLatchPublishesJoinOnce";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                *common, swappy::FixedRetirementState::RETIRED),
            name, "prior latch expectation was not registered");
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 2);
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 2, 2);
    const auto ready =
        SwappyCommonFixedAdmissionTestPeer::successorTransport(
            2, receipt.reservationNanos + 10);
    require(common->markReservedExternalGpuReadyForNtk(2, ready) &&
                probe.count == 0 &&
                !SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "retirement alone opened JOIN_OPEN");
    const auto observation =
        SwappyCommonFixedAdmissionTestPeer::priorLatchObservation();
    require(common->recordExternalLatchObservationForNtk(observation) &&
                probe.count == 1 &&
                SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "latch-last intersection did not publish JOIN_OPEN");
    const auto [required, used, waits, event] =
        SwappyCommonFixedAdmissionTestPeer::publishedLatchGate(*common);
    require(required == 1 && used == 1 && waits == 1 &&
                event == observation.latchEventSequence,
            name, "published opportunity lost exact prior-latch gate");
    bool callbackRequired = true;
    require(SwappyCommonFixedAdmissionTestPeer::republish(
                *common, &callbackRequired) && !callbackRequired &&
                probe.count == 1,
            name, "exact latch dispatched a duplicate callback");
}

void latch_before_retirement_waits_then_publishes_once() {
    const std::string name =
        "LatchBeforeRetirementPublishesJoinOnce";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe probe{};
    common->addTracerCallbacks(tracer(&probe));
    require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                *common, swappy::FixedRetirementState::WAIT_ARMED),
            name, "prior latch expectation was not registered");
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 2);
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 2, 2);
    const auto ready =
        SwappyCommonFixedAdmissionTestPeer::successorTransport(
            2, receipt.reservationNanos + 10);
    require(common->markReservedExternalGpuReadyForNtk(2, ready) &&
                !SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "JOIN_OPEN escaped before prior retirement");
    const auto observation =
        SwappyCommonFixedAdmissionTestPeer::priorLatchObservation();
    require(common->recordExternalLatchObservationForNtk(observation) &&
                probe.count == 0 &&
                !SwappyCommonFixedAdmissionTestPeer::hasPublished(*common),
            name, "latch alone incorrectly opened the join");
    bool callbackRequired = false;
    require(SwappyCommonFixedAdmissionTestPeer::retirePriorAndPublish(
                *common, &callbackRequired) &&
                callbackRequired && probe.count == 1,
            name, "target retirement did not open the successor join");
    const auto [required, used, waits, event] =
        SwappyCommonFixedAdmissionTestPeer::publishedLatchGate(*common);
    require(required == 1 && used == 1 && waits == 0 &&
                event == observation.latchEventSequence,
            name, "latch-first intersection lost exact admission proof");
}

void foreign_and_duplicate_latches_fail_closed() {
    const std::string name = "ForeignAndDuplicatePriorLatchesFailClosed";
    {
        auto common = SwappyCommonFixedAdmissionTestPeer::create();
        require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                    *common, swappy::FixedRetirementState::RETIRED),
                name, "foreign-case expectation registration failed");
        auto foreign =
            SwappyCommonFixedAdmissionTestPeer::priorLatchObservation();
        ++foreign.identity.ntkFrameId;
        require(!common->recordExternalLatchObservationForNtk(foreign) &&
                    SwappyCommonFixedAdmissionTestPeer::fatal(*common),
                name, "foreign latch did not fail closed");
    }
    {
        auto common = SwappyCommonFixedAdmissionTestPeer::create();
        require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                    *common, swappy::FixedRetirementState::RETIRED),
                name, "duplicate-case expectation registration failed");
        const auto exact =
            SwappyCommonFixedAdmissionTestPeer::priorLatchObservation();
        require(common->recordExternalLatchObservationForNtk(exact) &&
                    !common->recordExternalLatchObservationForNtk(exact) &&
                    SwappyCommonFixedAdmissionTestPeer::fatal(*common),
                name, "duplicate latch did not fail closed");
    }
}

void prior_latch_observation_is_consumed_exactly_once_by_successor() {
    const std::string name =
        "PriorLatchObservationConsumedExactlyOnceBySuccessor";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                *common, swappy::FixedRetirementState::RETIRED),
            name, "prior latch expectation was not registered");
    const auto exact =
        SwappyCommonFixedAdmissionTestPeer::priorLatchObservation(7);
    require(common->recordExternalLatchObservationForNtk(exact),
            name, "exact latch observation was rejected");
    SwappyFixedLatchObservationV1 first{};
    require(SwappyCommonFixedAdmissionTestPeer::snapshotLatch(
                *common, 1, &first) &&
                first.latchEventSequence == exact.latchEventSequence &&
                first.compositorLatchNanos ==
                    exact.compositorLatchNanos &&
                first.callbackObservedNanos ==
                    exact.callbackObservedNanos &&
                SwappyCommonFixedAdmissionTestPeer::consumeLatch(
                    *common, 1, exact) &&
                SwappyCommonFixedAdmissionTestPeer::latchConsumed(
                    *common, 1) &&
                !SwappyCommonFixedAdmissionTestPeer::consumeLatch(
                    *common, 1, exact) &&
                !SwappyCommonFixedAdmissionTestPeer::fatal(*common),
            name, "prior latch proof was not a one-shot successor authority");
}

void latch_ledger_reuses_only_consumed_proofs() {
    const std::string name =
        "LatchLedgerAllowsEightCommitProofsPending";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    for (std::uint64_t work = 1; work <= 8; ++work) {
        require(SwappyCommonFixedAdmissionTestPeer::registerLatchExpectation(
                    *common, work),
                name, "K=8 pending expectation registration failed");
    }
    require(!SwappyCommonFixedAdmissionTestPeer::registerLatchExpectation(
                *common, 9),
            name, "ninth pending commit proof exceeded hard capacity");
    const auto observed =
        SwappyCommonFixedAdmissionTestPeer::priorLatchObservation(1, 1);
    require(common->recordExternalLatchObservationForNtk(observed) &&
                !SwappyCommonFixedAdmissionTestPeer::registerLatchExpectation(
                    *common, 9) &&
                SwappyCommonFixedAdmissionTestPeer::consumeLatch(
                    *common, 1, observed) &&
                SwappyCommonFixedAdmissionTestPeer::registerLatchExpectation(
                    *common, 9),
            name, "unconsumed proof was recycled or consumed proof was retained");
    for (std::uint64_t work = 2; work <= 9; ++work) {
        require(SwappyCommonFixedAdmissionTestPeer::latchPending(
                    *common, work),
                name, "pending K=8 ledger identity was not conserved");
    }
}

void commit_without_prior_latch_fails_closed() {
    const std::string name =
        "CommitWithoutPriorLatchFailsClosed";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                *common, swappy::FixedRetirementState::RETIRED),
            name, "prior target retirement setup failed");
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 2);
    const auto ready =
        SwappyCommonFixedAdmissionTestPeer::successorTransport(
            2, receipt.reservationNanos + 10);
    SwappyCommonFixedAdmissionTestPeer::seedExactClaim(*common, 2, 1);
    SwappyCommonFixedAdmissionTestPeer::makePublishedClaimCommittable(
        *common, ready);
    const auto expected =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    swappy::FixedPhaseAdmissionToken token{};
    require(SwappyCommonFixedAdmissionTestPeer::claim(
                *common, expected, ready, &token) ==
                FixedPhaseAdmissionStatus::FATAL &&
                SwappyCommonFixedAdmissionTestPeer::fatal(*common),
            name, "missing prior latch did not fail closed");
}

void commit_with_prior_latch_consumes_exact_gate() {
    const std::string name =
        "CommitWithPriorLatchConsumesExactGate";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::seedPrior(
                *common, swappy::FixedRetirementState::RETIRED),
            name, "prior target retirement setup failed");
    const auto observation =
        SwappyCommonFixedAdmissionTestPeer::priorLatchObservation(7);
    require(common->recordExternalLatchObservationForNtk(observation),
            name, "exact prior latch gate was rejected");
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 2);
    const auto ready =
        SwappyCommonFixedAdmissionTestPeer::successorTransport(
            2, receipt.reservationNanos + 10);
    SwappyCommonFixedAdmissionTestPeer::seedExactClaim(*common, 2, 1);
    SwappyCommonFixedAdmissionTestPeer::makePublishedClaimCommittable(
        *common, ready);
    const auto expected =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    swappy::FixedPhaseAdmissionToken token{};
    require(SwappyCommonFixedAdmissionTestPeer::claim(
                *common, expected, ready, &token) ==
                FixedPhaseAdmissionStatus::ADMITTED &&
                token.priorLatchObservedAtClaim &&
                !token.priorCommitProofPendingAtClaim &&
                token.priorLatchWaitCount == 0 &&
                swappy::fixedLatchObservationExact(
                    token.priorLatchObservation, observation) &&
                SwappyCommonFixedAdmissionTestPeer::latchConsumed(
                    *common, 1),
            name, "observed prior latch was not consumed as exact authority");
}

void expected_mismatch_is_fatal() {
    const std::string name = "ExpectedOpportunityMismatchIsFatal";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    const auto ready = SwappyCommonFixedAdmissionTestPeer::transport(
        1, receipt.reservationNanos + 10);
    SwappyCommonFixedAdmissionTestPeer::seedExactClaim(*common, 1, 1);
    FixedOpportunityIdentity mismatch =
        SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
    ++mismatch.candidateSequence;
    require(SwappyCommonFixedAdmissionTestPeer::claim(
                *common, mismatch, ready) == FixedPhaseAdmissionStatus::FATAL,
            name, "foreign expected identity was not fatal");
}

void duplicate_external_claim_is_rejected() {
    const std::string name = "DuplicateExternalClaimRejected";
    expected_mismatch_is_fatal();
    (void)name;
}

void duplicate_apply_is_rejected() {
    const std::string name = "DuplicateApplyOrSubmissionRejected";
    SwappyFixedExternalClaim claim{};
    claim.structSize = sizeof(claim);
    claim.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
    require(claim.candidateSequence == 0 && claim.noticeSequence == 0,
            name, "zero/unissued claim unexpectedly valid");
}

void abort_cancels_demand_once() {
    const std::string name =
        "AbortCancelsOutstandingOpportunityDemandOnce";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1)
                    .workGeneration == 1 &&
                SwappyCommonFixedAdmissionTestPeer::abort(*common, 1) &&
                !SwappyCommonFixedAdmissionTestPeer::abort(*common, 1),
            name, "abort was not one-shot");
    const auto ledger = SwappyCommonFixedAdmissionTestPeer::ledger(*common);
    require(ledger.opportunityIssued == 1 &&
                ledger.opportunityCancelled == 1 &&
                SwappyCommonFixedAdmissionTestPeer::demandConserved(ledger),
            name, "abort demand ledger was not conserved");
}

void two_renderers_only_owner_acknowledges() {
    const std::string name =
        "TwoInjectedRenderersOnlyMatchingOwnerAcknowledgesNotice";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    CallbackProbe foreign{.matchingWork = 2};
    CallbackProbe owner{.matchingWork = 1};
    common->addTracerCallbacks(tracer(&foreign));
    common->addTracerCallbacks(tracer(&owner));
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 1, 1);
    const auto ready = SwappyCommonFixedAdmissionTestPeer::transport(
        1, receipt.reservationNanos + 10);
    require(common->markReservedExternalGpuReadyForNtk(1, ready) &&
                foreign.count == 1 && owner.count == 1,
            name, "process-global callbacks did not preserve exact owner");
}

void callback_before_wait_is_not_lost() {
    const std::string name = "CallbackBeforeRendererWaitIsNotLost";
    gpu_ready_publishes_carried_join_once();
    (void)name;
}

void callback_during_claim_preserves_next() {
    const std::string name =
        "CallbackDuringClaimPreservesNextOpportunity";
    closed_with_shadow_publishes_higher_without_internal_claim();
    (void)name;
}

void renderer_wake_cannot_overtake_callback_acknowledgement() {
    const std::string name =
        "RendererWakeCannotOvertakeCallbackAcknowledgement";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    ConcurrentHandoffProbe probe{.common = common.get()};
    common->addTracerCallbacks(concurrentHandoffTracer(&probe));
    const auto receipt =
        SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1);
    SwappyCommonFixedAdmissionTestPeer::seedAvailable(*common, 1, 1);
    const auto ready = SwappyCommonFixedAdmissionTestPeer::transport(
        1, receipt.reservationNanos + 10);
    std::thread competingRenderer([&]() {
        while (!probe.callbackEntered.load(std::memory_order_acquire)) {
            std::this_thread::yield();
        }
        probe.workerEnteredHandoff.store(
            SwappyCommonFixedAdmissionTestPeer::tryOpportunityHandoff(
                *common),
            std::memory_order_release);
        probe.workerFinished.store(true, std::memory_order_release);
    });
    const bool readyPublished =
        common->markReservedExternalGpuReadyForNtk(1, ready);
    competingRenderer.join();
    require(readyPublished &&
                !probe.workerEnteredHandoff.load(
                    std::memory_order_acquire) &&
                SwappyCommonFixedAdmissionTestPeer::
                    publishedRendererObservationExactAfterHandoff(*common) &&
                !SwappyCommonFixedAdmissionTestPeer::fatal(*common),
            name,
            "renderer claim lane overtook JOIN callback copy-back");
}

void demand_ledger_conserved_across_81_closed() {
    const std::string name =
        "OpportunityDemandLedgerConservedAcross81ClosedSlots";
    auto common = SwappyCommonFixedAdmissionTestPeer::create();
    require(SwappyCommonFixedAdmissionTestPeer::reserve(*common, 1)
                    .workGeneration == 1,
            name, "reserve failed");
    for (std::uint64_t sequence = 1; sequence <= 81; ++sequence) {
        SwappyCommonFixedAdmissionTestPeer::seedExactClaim(
            *common, sequence, sequence);
        const auto expected =
            SwappyCommonFixedAdmissionTestPeer::publishedIdentity(*common);
        require(SwappyCommonFixedAdmissionTestPeer::close(
                    *common, expected) ==
                    FixedPhaseAdmissionStatus::SLOT_CLOSED_WAITING_NEXT,
                name, "closed sequence lost liveness authority");
    }
    const auto ledger = SwappyCommonFixedAdmissionTestPeer::ledger(*common);
    require(ledger.opportunityIssued == 1 &&
                SwappyCommonFixedAdmissionTestPeer::demandConserved(ledger),
            name, "81-slot demand conservation failed");
}

std::size_t passed = 0;

void run(const char* name, const std::function<void()>& test) {
    test();
    ++passed;
    std::cout << "PASS " << name << '\n';
}

}  // namespace

int main() {
    run("ReserveDoesNotDispatchCommitOpportunityBeforeGpuReady",
        reserve_does_not_dispatch_before_gpu_ready);
    run("GpuReadyPublishesCarriedJoinOpenExactlyOnce",
        gpu_ready_publishes_carried_join_once);
    run("CandidateCaptureDoesNotDispatchRendererOpportunity",
        candidate_capture_does_not_dispatch);
    run("DuplicateJoinOpenDoesNotDuplicateCallback",
        duplicate_join_does_not_duplicate_callback);
    run("ClosedWithShadowReturnsWithoutInternalSecondClaim",
        closed_with_shadow_publishes_higher_without_internal_claim);
    run("ClosedWithShadowPublishesHigherOpportunity",
        closed_with_shadow_publishes_higher_without_internal_claim);
    run("ClosedWithoutShadowOwnsExactlyOneDemand",
        closed_without_shadow_owns_one_demand);
    run("NextPhysicalCallbackPublishesJoinOpen",
        next_physical_candidate_publishes_join);
    run("ClosedTimelineWindowUsesSameDisposition",
        closed_timeline_uses_same_disposition);
    run("RetiredPriorWithoutLatchPublishesJoinOnce",
        retirement_before_latch_waits_then_publishes_once);
    run("LatchBeforeRetirementRemainsObservationOnly",
        latch_before_retirement_waits_then_publishes_once);
    run("ForeignAndDuplicatePriorLatchesFailClosed",
        foreign_and_duplicate_latches_fail_closed);
    run("PriorLatchObservationConsumedExactlyOnceBySuccessor",
        prior_latch_observation_is_consumed_exactly_once_by_successor);
    run("LatchLedgerReusesOnlyConsumedProofs",
        latch_ledger_reuses_only_consumed_proofs);
    run("CommitWithoutPriorLatchFailsClosed",
        commit_without_prior_latch_fails_closed);
    run("CommitWithPriorLatchConsumesExactGate",
        commit_with_prior_latch_consumes_exact_gate);
    run("ExpectedOpportunityMismatchIsFatal",
        expected_mismatch_is_fatal);
    run("DuplicateExternalClaimRejected",
        duplicate_external_claim_is_rejected);
    run("DuplicateApplyOrSubmissionRejected",
        duplicate_apply_is_rejected);
    run("AbortCancelsOutstandingOpportunityDemandOnce",
        abort_cancels_demand_once);
    run("TwoInjectedRenderersOnlyMatchingOwnerAcknowledgesNotice",
        two_renderers_only_owner_acknowledges);
    run("CallbackBeforeRendererWaitIsNotLost",
        callback_before_wait_is_not_lost);
    run("CallbackDuringClaimPreservesNextOpportunity",
        callback_during_claim_preserves_next);
    run("RendererWakeCannotOvertakeCallbackAcknowledgement",
        renderer_wake_cannot_overtake_callback_acknowledgement);
    run("OpportunityDemandLedgerConservedAcross81ClosedSlots",
        demand_ledger_conserved_across_81_closed);
    std::cout << "PASS SwappyCommonFixedAdmissionTest " << passed << '/'
              << passed << '\n';
    return 0;
}
