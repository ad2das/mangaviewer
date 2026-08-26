#include "../present/SurfaceControlPresentBackend.h"
#include "../present/GeometryPresentSchedule.h"
#include "../present/RollingBandPrecomposePolicy.h"
#include "../swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"

#include <array>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <optional>
#include <sys/eventfd.h>
#include <unistd.h>

namespace ntk::present {

namespace {

std::int64_t fakeLatchNanos = 100;
std::int64_t fakeLatchTime(ASurfaceTransactionStats*) { return fakeLatchNanos; }
int fakeReleaseFence(ASurfaceTransactionStats*, ASurfaceControl*) { return -1; }
int fakePresentFence(ASurfaceTransactionStats*) { return -1; }
void fakeReleaseSurface(ASurfaceControl*) {}
void fakeReleaseHardwareBuffer(AHardwareBuffer*) {}

std::uint32_t fakeCreateCount = 0;
std::uint32_t fakeDeleteCount = 0;
std::uint32_t fakeApplyCount = 0;
std::uint32_t fakeSetBufferCount = 0;
std::uint32_t fakeSetTimelineCount = 0;
std::uint32_t fakeDesiredPresentCount = 0;
std::int64_t fakeDesiredPresentNanos = 0;
std::uint32_t fakePositionCount = 0;
ASurfaceControl* fakeLastPositionSurface = nullptr;
std::uint32_t fakeScaleCount = 0;
std::uint32_t fakeGeometryCount = 0;
std::uint32_t fakeBackpressureEnableCount = 0;
std::uint32_t fakeBackpressureDisableCount = 0;
std::array<AHardwareBuffer*, 128> fakeSetBufferValues{};
std::array<ASurfaceControl*, 128> fakeSetBufferSurfaces{};
std::array<void*, 128> fakeCommitContexts{};
std::array<void*, 128> fakeCompleteContexts{};
std::size_t fakeCommitContextCount = 0;
std::size_t fakeCompleteContextCount = 0;

ASurfaceTransaction* fakeCreateTransaction() {
    ++fakeCreateCount;
    return reinterpret_cast<ASurfaceTransaction*>(
        static_cast<std::uintptr_t>(fakeCreateCount + 100));
}
void fakeDeleteTransaction(ASurfaceTransaction*) { ++fakeDeleteCount; }
void fakeApplyTransaction(ASurfaceTransaction*) { ++fakeApplyCount; }
void fakeSetBuffer(
        ASurfaceTransaction*, ASurfaceControl* surface, AHardwareBuffer* buffer, int fd) {
    fakeSetBufferSurfaces[fakeSetBufferCount] = surface;
    fakeSetBufferValues[fakeSetBufferCount] = buffer;
    ++fakeSetBufferCount;
    if (fd >= 0) close(fd);
}
void fakeSetTimeline(ASurfaceTransaction*, AVsyncId) {
    ++fakeSetTimelineCount;
}
void fakeSetDesiredPresentTime(ASurfaceTransaction*, std::int64_t nanos) {
    ++fakeDesiredPresentCount;
    fakeDesiredPresentNanos = nanos;
}
void fakeSetOnCommit(
        ASurfaceTransaction*, void* context,
        void (*)(void*, ASurfaceTransactionStats*)) {
    fakeCommitContexts[fakeCommitContextCount++] = context;
}
void fakeSetOnComplete(
        ASurfaceTransaction*, void* context,
        void (*)(void*, ASurfaceTransactionStats*)) {
    fakeCompleteContexts[fakeCompleteContextCount++] = context;
}
void fakeBackPressure(
        ASurfaceTransaction*, ASurfaceControl*, bool enabled) {
    if (enabled) {
        ++fakeBackpressureEnableCount;
    } else {
        ++fakeBackpressureDisableCount;
    }
}
void fakeGeometry(
        ASurfaceTransaction*, ASurfaceControl*, const ARect&, const ARect&,
        std::int32_t) { ++fakeGeometryCount; }
void fakePosition(
        ASurfaceTransaction*, ASurfaceControl* surface, std::int32_t, std::int32_t) {
    ++fakePositionCount;
    fakeLastPositionSurface = surface;
}
void fakeScale(ASurfaceTransaction*, ASurfaceControl*, float, float) {
    ++fakeScaleCount;
}
void fakeTransparency(
        ASurfaceTransaction*, ASurfaceControl*,
        ASurfaceTransactionTransparency) {}
void fakeAlpha(ASurfaceTransaction*, ASurfaceControl*, float) {}
void fakeColor(
        ASurfaceTransaction*, ASurfaceControl*, float, float, float, float,
        ADataSpace) {}
void fakeVisibility(
        ASurfaceTransaction*, ASurfaceControl*,
        ASurfaceTransactionVisibility) {}

sync_fence_info fakeSyncFenceInfo{};
sync_file_info fakeSyncFileInfo{};

sync_file_info* fakeGetSyncFileInfo(std::int32_t) {
    fakeSyncFenceInfo = {};
    fakeSyncFenceInfo.status = 1;
    fakeSyncFenceInfo.timestamp_ns = 1;
    fakeSyncFileInfo = {};
    fakeSyncFileInfo.status = 1;
    fakeSyncFileInfo.num_fences = 1;
    fakeSyncFileInfo.sync_fence_info = reinterpret_cast<std::uintptr_t>(
        &fakeSyncFenceInfo);
    return &fakeSyncFileInfo;
}
void fakeFreeSyncFileInfo(sync_file_info*) {}

}  // namespace

struct HardwareBufferRenderTargetPoolTestAccess {
    static void initialize(HardwareBufferRenderTargetPool& pool) {
        pool.initialized_ = true;
        pool.hardwareBufferRelease_ = &fakeReleaseHardwareBuffer;
        for (std::size_t index = 0; index < pool.targets_.size(); ++index) {
            pool.targets_[index].slot = index;
            pool.targets_[index].generation = 0;
            pool.targets_[index].hardwareBuffer =
                reinterpret_cast<AHardwareBuffer*>(index + 1);
            // This is a pure ownership/backend contract test. Keep lazy GPU
            // allocation out of the seam because no EGL context or allocator
            // entry points are installed here.
            pool.targets_[index].framebuffer = static_cast<GLuint>(index + 1);
            pool.targets_[index].state =
                HardwareBufferRenderTargetPool::SlotState::FREE;
        }
    }

    static HardwareBufferRenderTargetPool::RenderTarget& gpuReady(
            HardwareBufferRenderTargetPool& pool) {
        auto* target = pool.acquireForRendering();
        if (target == nullptr || !pool.markAcquireFenceExported(*target)) {
            std::abort();
        }
        return *target;
    }
};

struct SurfaceControlPresentBackendTestAccess {
    using Backend = SurfaceControlPresentBackend;
    static constexpr std::size_t callbackCookieCount =
        Backend::kMaxCallbackCookies;
    static constexpr std::size_t callbackRecordCapacity =
        Backend::kMaxAppliedCallbackRecords;

    static void setGeometrySurface(Backend& backend, ASurfaceControl* surface) {
        backend.geometrySurface_ = surface;
    }

    static void initialize(Backend& backend) {
        fakeLatchNanos = 100;
        fakeCreateCount = 0;
        fakeDeleteCount = 0;
        fakeApplyCount = 0;
        fakeSetBufferCount = 0;
        fakeSetTimelineCount = 0;
        fakeDesiredPresentCount = 0;
        fakeDesiredPresentNanos = 0;
        fakePositionCount = 0;
        fakeLastPositionSurface = nullptr;
        fakeScaleCount = 0;
        fakeGeometryCount = 0;
        fakeBackpressureEnableCount = 0;
        fakeBackpressureDisableCount = 0;
        fakeSetBufferValues = {};
        fakeSetBufferSurfaces = {};
        fakeCommitContexts = {};
        fakeCompleteContexts = {};
        fakeCommitContextCount = 0;
        fakeCompleteContextCount = 0;
        backend.attached_ = true;
        backend.childSurface_ = reinterpret_cast<ASurfaceControl*>(1);
        backend.geometryPulseSurface_ = reinterpret_cast<ASurfaceControl*>(3);
        backend.currentGeometryPulseBufferIndex_.reset();
        backend.geometryPulseBuffersOwned_ = false;
        for (std::size_t index = 0;
             index < backend.geometryPulseBuffers_.size(); ++index) {
            backend.geometryPulseBuffers_[index] =
                reinterpret_cast<AHardwareBuffer*>(100 + index);
            backend.geometryPulseBufferStates_[index].store(
                static_cast<std::uint8_t>(
                    Backend::GeometryPulseBufferState::FREE),
                std::memory_order_release);
        }
        backend.surfaceEpoch_ = 2;
        backend.surfaceSerial_ = 14;
        backend.width_ = 1080;
        backend.height_ = 2340;
        backend.backpressureEnabled_ = false;
        backend.surfaceApi_.createTransaction = &fakeCreateTransaction;
        backend.surfaceApi_.deleteTransaction = &fakeDeleteTransaction;
        backend.surfaceApi_.applyTransaction = &fakeApplyTransaction;
        backend.surfaceApi_.setBuffer = &fakeSetBuffer;
        backend.surfaceApi_.setFrameTimeline = &fakeSetTimeline;
        backend.surfaceApi_.setDesiredPresentTime = &fakeSetDesiredPresentTime;
        backend.surfaceApi_.setEnableBackPressure = &fakeBackPressure;
        backend.surfaceApi_.setGeometry = &fakeGeometry;
        backend.surfaceApi_.setPosition = &fakePosition;
        backend.surfaceApi_.setScale = &fakeScale;
        backend.surfaceApi_.setBufferTransparency = &fakeTransparency;
        backend.surfaceApi_.setBufferAlpha = &fakeAlpha;
        backend.surfaceApi_.setColor = &fakeColor;
        backend.surfaceApi_.setVisibility = &fakeVisibility;
        backend.surfaceApi_.setOnCommit = &fakeSetOnCommit;
        backend.surfaceApi_.setOnComplete = &fakeSetOnComplete;
        backend.surfaceApi_.getLatchTime = &fakeLatchTime;
        backend.surfaceApi_.getPresentFenceFd = &fakePresentFence;
        backend.surfaceApi_.getPreviousReleaseFenceFd = &fakeReleaseFence;
        backend.surfaceApi_.releaseSurface = &fakeReleaseSurface;
        backend.syncFileInfo_ = &fakeGetSyncFileInfo;
        backend.syncFileInfoFree_ = &fakeFreeSyncFileInfo;
        HardwareBufferRenderTargetPoolTestAccess::initialize(backend.pool_);
    }

    static Backend::FixedPreparedFrameIdentityBase base(
            std::uint64_t ordinal) {
        return {
            .engineGeneration = 1,
            .surfaceEpoch = 2,
            .authorityGeneration = 3,
            .authority = 4,
            .workGeneration = 5 + ordinal,
            .ntkFrameId = 20 + ordinal,
            .frameSequence = 40 + ordinal,
            .capsuleSequence = 60 + ordinal,
        };
    }

    static FixedTransportProfile profile(std::uint64_t generation) {
        return makeFixedTransportProfile(
            11'111'111, 2'000'000, 6'111'111, generation);
    }

    static SwappyFixedPriorRetirementProofV1 priorProof(
            const SwappyFixedAppliedBufferRefV1& predecessor,
            std::int64_t boundaryNanos) {
        if (swappy::fixedAppliedBufferRefEmpty(predecessor)) {
            return swappy::emptyFixedPriorRetirementProof();
        }
        SwappyFixedPriorRetirementProofV1 proof{};
        proof.structSize = sizeof(proof);
        proof.version = SWAPPY_FIXED_PRIOR_RETIREMENT_PROOF_V1_VERSION;
        proof.hasPrior = 1;
        proof.predecessor = predecessor;
        proof.retirementSequence = predecessor.appliedBufferRefSerial + 100;
        proof.targetAuthorityRawSequence =
            predecessor.appliedBufferRefSerial + 200;
        proof.targetPhysicalCallbackSequence =
            predecessor.appliedBufferRefSerial + 300;
        proof.plannedTargetFrame = 12;
        proof.originalTargetFrame = 12;
        proof.targetReachedNanos = boundaryNanos;
        proof.retirementCompleteNanos = boundaryNanos;
        proof.proofCommittedNanos = boundaryNanos;
        proof.retirementCallbackPublishCount = 1;
        proof.state = SWAPPY_FIXED_RETIREMENT_RETIRED;
        return proof;
    }

    static SwappyFixedExternalClaim claim(
            const Backend& backend,
            const SwappyFixedExternalTransportReady& ready) {
        SwappyFixedExternalClaim value{};
        value.structSize = sizeof(value);
        value.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
        value.claimToken = ready.workGeneration + 100;
        value.workGeneration = ready.workGeneration;
        value.admissionSequence = ready.workGeneration + 200;
        value.reservationSequence = ready.workGeneration + 300;
        value.opportunitySequence = ready.workGeneration + 400;
        value.candidateSequence = ready.workGeneration + 500;
        value.noticeSequence = ready.workGeneration + 600;
        value.plannedTargetFrame = 12;
        value.frameTimelineVsyncId =
            static_cast<AVsyncId>(ready.workGeneration + 700);
        const std::int64_t decision = std::max(
            ready.prepareEndNanos,
            backend.latestConsumedCompositorLatchObservedNanos_);
        value.decisionNanos = decision;
        value.initialDecisionNanos = decision;
        value.claimReturnNanos = decision;
        value.ntkFrameId = ready.ntkFrameId;
        value.engineGeneration = ready.engineGeneration;
        value.surfaceEpoch = ready.surfaceEpoch;
        value.authorityGeneration = ready.authorityGeneration;
        value.authority = ready.authority;
        value.frameSequence = ready.frameSequence;
        value.capsuleSequence = ready.capsuleSequence;
        value.backendSurfaceSerial = ready.backendSurfaceSerial;
        value.transactionSerial = ready.transactionSerial;
        value.bufferSlot = ready.bufferSlot;
        value.bufferGeneration = ready.bufferGeneration;
        value.acquireFenceSerial = ready.acquireFenceSerial;
        value.transportProfileDigest = ready.profile.profileDigest;
        value.timingGeneration = ready.profile.timingGeneration;
        value.transportBoundNanos = ready.profile.transportBoundNanos;
        value.prepareBeginNanos = ready.prepareBeginNanos;
        value.prepareEndNanos = ready.prepareEndNanos;
        value.transportAdmissionOutcome = 2;
        value.setBufferCount = ready.setBufferCount;
        value.acquireFenceDupCount = ready.acquireFenceDupCount;
        value.setBufferPending = ready.setBufferPending;
        value.firstStage = ready.firstStage;
        value.previousAppliedBufferRef = ready.previousAppliedBufferRef;
        value.priorRetirementProof = priorProof(
            ready.previousAppliedBufferRef, ready.prepareEndNanos);
        if (ready.firstStage == 0 &&
            backend.latestConsumedCompositorLatchRef_.has_value()) {
            value.priorLatchGateRequired = 1;
            value.priorLatchGateUsed = 1;
            value.priorLatchObservation.structSize =
                sizeof(value.priorLatchObservation);
            value.priorLatchObservation.version =
                SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION;
            const FixedFrameIdentity& identity =
                backend.latestConsumedCompositorLatchRef_->identity;
            auto& latchIdentity = value.priorLatchObservation.identity;
            latchIdentity.structSize = sizeof(latchIdentity);
            latchIdentity.version =
                SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
            latchIdentity.engineGeneration = identity.engineGeneration;
            latchIdentity.surfaceEpoch = identity.surfaceEpoch;
            latchIdentity.authorityGeneration =
                identity.authorityGeneration;
            latchIdentity.authority = identity.authority;
            latchIdentity.workGeneration = identity.workGeneration;
            latchIdentity.ntkFrameId = identity.ntkFrameId;
            latchIdentity.frameSequence = identity.frameSequence;
            latchIdentity.admissionSequence = identity.admissionSequence;
            latchIdentity.capsuleSequence = identity.capsuleSequence;
            latchIdentity.backendSurfaceSerial =
                identity.backendSurfaceSerial;
            latchIdentity.transactionSerial = identity.transactionSerial;
            latchIdentity.bufferSlot = identity.bufferSlot;
            latchIdentity.bufferGeneration = identity.bufferGeneration;
            latchIdentity.frameTimelineVsyncId =
                identity.frameTimelineVsyncId;
            value.priorLatchObservation.latchEventSequence =
                backend.latestConsumedCompositorLatchEventSequence_;
            value.priorLatchObservation.compositorLatchNanos =
                backend.latestConsumedCompositorLatchNanos_;
            value.priorLatchObservation.callbackObservedNanos =
                backend.latestConsumedCompositorLatchObservedNanos_;
            value.priorLatchObservation.source = 1;
            value.priorLatchObservation.onCommitCallbackCount = 1;
        }
        return value;
    }

    static Backend::PreparedSurfaceSubmission prepare(
            Backend& backend, std::uint64_t ordinal,
            SwappyFixedExternalTransportReady* ready,
            std::uint64_t initialFenceValue = 1,
            int* signalFd = nullptr) {
        auto& target = HardwareBufferRenderTargetPoolTestAccess::gpuReady(
            backend.pool_);
        const int frameworkFd = eventfd(
            initialFenceValue, EFD_CLOEXEC | EFD_NONBLOCK);
        const int proofFd = dup(frameworkFd);
        const int retainedSignalFd = signalFd != nullptr
            ? dup(frameworkFd) : -1;
        if (frameworkFd < 0 || proofFd < 0 ||
            (signalFd != nullptr && retainedSignalFd < 0)) std::abort();
        if (signalFd != nullptr) *signalFd = retainedSignalFd;
        const std::uint64_t fenceSerial = ++backend.acquireFenceSerial_;
        backend.localAcquireFence_ = Backend::LocalAcquireFenceOwner{
            .buffer = Backend::BufferIdentity{
                .slot = target.slot,
                .generation = target.generation,
            },
            .acquireFenceSerial = fenceSerial,
            .frameworkAcquireFd = frameworkFd,
            .proofAcquireFd = proofFd,
            .phase = Backend::LocalAcquirePhase::EXPORTED_UNBOUND,
        };
        Backend::PreparedSurfaceSubmission prepared{};
        if (!backend.prepareBufferTransaction(
                base(ordinal), target,
                !backend.latestAppliedBufferRef_.has_value(),
                profile(ordinal + 2), &prepared, ready)) {
            std::abort();
        }
        return prepared;
    }

    static Backend::SubmissionReceipt applyWithoutAcquireConsumption(
            Backend& backend,
            Backend::PreparedSurfaceSubmission& prepared,
            const SwappyFixedExternalTransportReady& ready) {
        Backend::SubmissionReceipt receipt{};
        if (backend.applyPreparedBufferTransaction(
                prepared, claim(backend, ready), &receipt) !=
                Backend::ApplyDisposition::APPLIED) {
            std::abort();
        }
        return receipt;
    }

    static Backend::SubmissionReceipt apply(
            Backend& backend,
            Backend::PreparedSurfaceSubmission& prepared,
            const SwappyFixedExternalTransportReady& ready) {
        const Backend::SubmissionReceipt receipt =
            applyWithoutAcquireConsumption(backend, prepared, ready);
        FixedPresentEvent acquire{};
        if (!backend.drainEvent(&acquire) ||
            !backend.consumeAcquireFenceSignaled(acquire)) {
            std::abort();
        }
        return receipt;
    }

    static bool startFenceReactor(Backend& backend) {
        if (backend.fenceThread_.joinable() ||
            backend.fenceControlFd_ >= 0) return false;
        backend.fenceControlFd_ = eventfd(
            0, EFD_CLOEXEC | EFD_NONBLOCK);
        if (backend.fenceControlFd_ < 0) return false;
        backend.fenceThread_ = std::thread(
            &Backend::releaseFenceLoop, &backend);
        std::unique_lock<std::mutex> lock(backend.fenceMutex_);
        backend.fenceReady_.wait(lock, [&backend] {
            return backend.fenceLooperReady_ || backend.fenceLooperFailed_;
        });
        return backend.fenceLooperReady_ && !backend.fenceLooperFailed_;
    }

    static std::size_t occupyEveryFreeCallbackCookie(
            Backend& backend,
            std::array<std::size_t, callbackCookieCount>& occupied) {
        std::size_t count = 0;
        while (const auto index = backend.acquireCallbackCookie()) {
            occupied[count++] = *index;
        }
        return count;
    }

    static void releaseCallbackCookies(
            Backend& backend,
            const std::array<std::size_t, callbackCookieCount>& occupied,
            std::size_t begin,
            std::size_t end) {
        for (std::size_t index = begin; index < end; ++index) {
            backend.releaseCallbackCookie(occupied[index]);
        }
    }

    static bool waitAndConsumeAcquire(Backend& backend) {
        FixedPresentEvent event{};
        {
            std::unique_lock<std::mutex> lock(backend.eventMutex_);
            if (!backend.eventCondition_.wait_for(
                    lock, std::chrono::seconds(2), [&backend] {
                        return backend.eventCount_ != 0 ||
                            backend.eventOverflowed_.load(
                                std::memory_order_acquire);
                    }) || backend.eventOverflowed_.load(
                        std::memory_order_acquire)) return false;
            event = backend.events_[backend.eventRead_];
            backend.eventRead_ =
                (backend.eventRead_ + 1) % backend.events_.size();
            --backend.eventCount_;
        }
        return backend.consumeAcquireFenceSignaled(event);
    }

    static void publishCommit(std::size_t index) {
        Backend::onCommitted(
            fakeCommitContexts[index],
            reinterpret_cast<ASurfaceTransactionStats*>(1));
    }

    static void publishComplete(std::size_t index) {
        Backend::onCompleted(
            fakeCompleteContexts[index],
            reinterpret_cast<ASurfaceTransactionStats*>(1));
    }

    static bool consumeCommit(
            Backend& backend, std::size_t index) {
        publishCommit(index);
        FixedPresentEvent event{};
        Backend::ExactPresentLatchObservation observation{};
        return backend.drainEvent(&event) &&
            event.kind == FixedPresentEventKind::COMPOSITOR_LATCHED &&
            backend.consumeCompositorLatch(event, &observation);
    }

    static bool publishCompleteAndConsumeOnlyRelease(
            Backend& backend, std::size_t index,
            FixedPresentEvent* deferredComplete) {
        if (deferredComplete == nullptr) return false;
        publishComplete(index);
        FixedPresentEvent released{};
        return backend.drainEvent(deferredComplete) &&
            deferredComplete->kind ==
                FixedPresentEventKind::TRANSACTION_COMPLETED &&
            backend.drainEvent(&released) &&
            released.kind == FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED &&
            backend.consumePreviousBufferReleased(released);
    }

    static bool drainAllExact(Backend& backend) {
        FixedPresentEvent event{};
        while (backend.drainEvent(&event)) {
            switch (event.kind) {
                case FixedPresentEventKind::COMPOSITOR_LATCHED: {
                    Backend::ExactPresentLatchObservation observation{};
                    if (!backend.consumeCompositorLatch(event, &observation)) {
                        return false;
                    }
                    break;
                }
                case FixedPresentEventKind::TRANSACTION_COMPLETED:
                    if (!backend.consumeTransactionCompleted(event)) {
                        return false;
                    }
                    break;
                case FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED:
                    if (!backend.consumePreviousBufferReleased(event)) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    static bool releaseHeadAndDestroy(Backend& backend) {
        if (backend.latestAppliedBufferRef_.has_value()) {
            const auto ref = *backend.latestAppliedBufferRef_;
            if (!backend.pool_.markReleased(
                    ref.identity.bufferSlot,
                    ref.identity.bufferGeneration)) {
                return false;
            }
            backend.latestAppliedBufferRef_.reset();
            backend.latestConsumedCompositorLatchRef_.reset();
            backend.latestConsumedCompositorLatchEventSequence_ = 0;
            backend.latestConsumedCompositorLatchNanos_ = 0;
            backend.latestConsumedCompositorLatchObservedNanos_ = 0;
            backend.logicalUnlatchedNow_ = 0;
        }
        backend.currentGeometryPulseBufferIndex_.reset();
        for (auto& state : backend.geometryPulseBufferStates_) {
            state.store(
                static_cast<std::uint8_t>(
                    Backend::GeometryPulseBufferState::FREE),
                std::memory_order_release);
        }
        return backend.callbackRecordCount() == 0 &&
            backend.previousReleaseRecordCount() == 0 &&
            backend.acquireFenceRecordCount() == 0 &&
            backend.destroy();
    }

    static bool latestEquals(
            const Backend& backend, const AppliedBufferRef& ref) {
        return backend.latestAppliedBufferRef_.has_value() &&
            exactAppliedBufferRef(*backend.latestAppliedBufferRef_, ref);
    }

    static bool invariantsHold(const Backend& backend) {
        return backend.stateInvariantsHold();
    }

    static bool reactorAccepted(ALooper* looper, int result) {
        return Backend::fenceReactorInitializationSucceeded(looper, result);
    }
};

}  // namespace ntk::present

namespace {

using Backend = ntk::present::SurfaceControlPresentBackend;
using Access = ntk::present::SurfaceControlPresentBackendTestAccess;
using Event = ntk::present::FixedPresentEvent;
using EventKind = ntk::present::FixedPresentEventKind;
using Pool = ntk::present::HardwareBufferRenderTargetPool;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL SurfaceControlPresentBackendTest: " << message << '\n';
    std::exit(1);
}

struct Submitted {
    Backend::PreparedSurfaceSubmission prepared{};
    SwappyFixedExternalTransportReady ready{};
    Backend::SubmissionReceipt receipt{};
};

Submitted submit(Backend& backend, std::uint64_t ordinal) {
    Submitted value{};
    value.prepared = Access::prepare(backend, ordinal, &value.ready);
    require(backend.queryApplyReadiness(value.prepared) ==
                Backend::ApplyReadiness::READY,
            "prepared transaction was not immediately ready");
    value.receipt = Access::apply(backend, value.prepared, value.ready);
    return value;
}

void reserveAbortAndApplyAreExactlyOnce() {
    Backend backend;
    Access::initialize(backend);
    SwappyFixedExternalTransportReady ready{};
    auto prepared = Access::prepare(backend, 0, &ready);
    require(prepared.state ==
                Backend::PreparedTransactionState::PREPARED_NOT_CLAIMED &&
                ntk::present::fakeSetBufferCount == 0 &&
                ntk::present::fakeApplyCount == 0 &&
                backend.abortPreparedBufferTransaction(prepared) &&
                prepared.state ==
                    Backend::PreparedTransactionState::TERMINAL &&
                backend.pool().allFree() && backend.destroy(),
            "pre-apply abort did not return every reserved owner");

    Access::initialize(backend);
    auto first = submit(backend, 0);
    const auto snapshot = backend.conservationSnapshot();
    require(first.prepared.state ==
                Backend::PreparedTransactionState::TERMINAL &&
                first.prepared.cookie == nullptr &&
                ntk::present::fakeApplyCount == 1 &&
                ntk::present::fakeSetBufferCount == 1 &&
                ntk::present::fakeSetTimelineCount == 1 &&
                ntk::present::fakeBackpressureEnableCount == 1 &&
                ntk::present::fakeBackpressureDisableCount == 0 &&
                snapshot.callbackRecordDepth == 1 &&
                snapshot.heldFrameworkRefCount == 1 &&
                snapshot.freeReusableCount == Pool::kSlotCount - 1 &&
                snapshot.logicalUnlatchedNow == 1 &&
                snapshot.maxLogicalUnlatched == 1 &&
                snapshot.submittedWaitLatchCount == 1 &&
                snapshot.commitProofPendingNow == 1 &&
                snapshot.maxCommitProofPending == 1 &&
                ntk::present::postApplyLatchConjunctionDepthsExact(
                    snapshot.callbackRecordDepth,
                    snapshot.maxCallbackRecordDepth,
                    snapshot.logicalUnlatchedNow,
                    snapshot.maxLogicalUnlatched,
                    snapshot.submittedWaitLatchCount,
                    snapshot.commitProofPendingNow,
                    snapshot.completeProofPendingNow,
                    snapshot.maxCommitProofPending,
                    snapshot.maxCompleteProofPending) &&
                !ntk::present::postApplyLatchConjunctionDepthsExact(
                    snapshot.callbackRecordDepth,
                    snapshot.maxCallbackRecordDepth,
                    snapshot.logicalUnlatchedNow,
                    snapshot.maxLogicalUnlatched,
                    0,
                    snapshot.commitProofPendingNow,
                    snapshot.completeProofPendingNow,
                    snapshot.maxCommitProofPending,
                    snapshot.maxCompleteProofPending) &&
                snapshot.applyBeforePriorCommitConsumedCount == 0 &&
                snapshot.backpressureEnableCount == 1 &&
                snapshot.backpressureDisableCount == 0 &&
                snapshot.capacityWaitCount == 0 &&
                !backend.abortPreparedBufferTransaction(first.prepared),
            "successful apply was not irreversible and exactly once");
    require(Access::consumeCommit(backend, 0) &&
                backend.conservationSnapshot().logicalUnlatchedNow == 0,
            "first compositor latch did not close logical unlatched");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "single applied lifecycle did not drain");
}

void directBufferUsesRealDisplayFrameTimeline() {
    Backend backend;
    Access::initialize(backend);
    SwappyFixedExternalTransportReady ready{};
    auto prepared = Access::prepare(backend, 0, &ready);
    require(backend.configurePreparedSourceCrop(prepared, 10, 100, 10) &&
                ntk::present::fakePositionCount == 1 &&
                ntk::present::fakeScaleCount == 1 &&
                ntk::present::fakeGeometryCount == 0,
            "first retained band did not install scale plus position");
    Backend::SubmissionReceipt receipt{};
    constexpr std::int64_t kVsyncId = 7'001;
    require(backend.applyPreparedBufferTransactionDirect(
                prepared, kVsyncId, &receipt) ==
                Backend::ApplyDisposition::APPLIED &&
                receipt.submitted && receipt.setBufferCount == 1 &&
                receipt.setFrameTimelineCount == 1 &&
                receipt.identity.frameTimelineVsyncId == kVsyncId &&
                ntk::present::fakeSetTimelineCount == 1,
            "direct buffer did not bind the real display frame timeline");
    Event acquire{};
    require(backend.drainEvent(&acquire) &&
                acquire.kind == EventKind::ACQUIRE_FENCE_SIGNALED &&
                backend.consumeAcquireFenceSignaled(acquire) &&
                Access::consumeCommit(backend, 0),
            "direct frame-timeline buffer ownership did not drain");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "direct frame-timeline buffer lifecycle did not retire");
}

void geometryUsesRealDisplayFrameTimelineWithoutDesiredTimeFallback() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "geometry predecessor commit did not drain");
    const auto timelineBefore = ntk::present::fakeSetTimelineCount;
    const auto desiredBefore = ntk::present::fakeDesiredPresentCount;
    const auto positionBefore = ntk::present::fakePositionCount;
    const auto scaleBefore = ntk::present::fakeScaleCount;
    const auto bufferBefore = ntk::present::fakeSetBufferCount;
    Backend::SubmissionReceipt receipt{};
    constexpr std::int64_t kVsyncId = 8'001;
    require(backend.applyGeometryTransactionDirect(
                Access::base(1), 10, 100, kVsyncId, 9'001, &receipt) ==
                Backend::ApplyDisposition::APPLIED &&
                receipt.submitted && receipt.setBufferCount == 0 &&
                receipt.setFrameTimelineCount == 1 &&
                ntk::present::fakeSetTimelineCount == timelineBefore + 1 &&
                ntk::present::fakeDesiredPresentCount == desiredBefore &&
                ntk::present::fakePositionCount == positionBefore + 1 &&
                ntk::present::fakeScaleCount == scaleBefore &&
                ntk::present::fakeSetBufferCount == bufferBefore + 1 &&
                ntk::present::fakeCommitContextCount == 2 &&
                ntk::present::fakeCompleteContextCount == 2,
            "geometry scroll did not bind its crop to a real transparent pulse buffer");
    Event bufferProbe{};
    bufferProbe.identity = first.receipt.identity;
    Event geometryProbe{};
    geometryProbe.identity = receipt.identity;
    require(!backend.isGeometryOnlyTransaction(bufferProbe) &&
                backend.isGeometryOnlyTransaction(geometryProbe),
            "callback evidence could not distinguish buffer and geometry ownership");
    require(Access::consumeCommit(backend, 1),
            "frame-timeline geometry commit did not drain");
    Access::publishComplete(1);
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "frame-timeline geometry lifecycle did not retire");
    (void)first;
}

void geometryOnlyMovesTheSeparateContainerUsedByFullBands() {
    Backend backend;
    Access::initialize(backend);
    auto* geometry = reinterpret_cast<ASurfaceControl*>(2);
    Access::setGeometrySurface(backend, geometry);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "separate-container predecessor commit did not drain");
    ntk::present::fakeLastPositionSurface = nullptr;
    Backend::SubmissionReceipt receipt{};
    require(backend.applyGeometryTransactionDirect(
                Access::base(1), 10, 100, 0, 9'001, &receipt) ==
                Backend::ApplyDisposition::APPLIED &&
                receipt.submitted && receipt.setBufferCount == 0 &&
                ntk::present::fakeLastPositionSurface == geometry,
            "geometry-only crop moved the buffer child instead of its container");
    require(Access::consumeCommit(backend, 1),
            "separate-container geometry commit did not drain");
    Access::publishComplete(1);
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "separate-container geometry lifecycle did not retire");
    (void)first;
}

void AlreadySignaledAcquireFenceStillConserves() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    const auto snapshot = backend.conservationSnapshot();
    require(!first.receipt.applyBeforeAcquireSignalProven &&
                snapshot.applyBeforeAcquireSignalProvenCount == 0 &&
                snapshot.acquireFenceRecordDepth == 0 &&
                snapshot.backendInvariantFatalCount == 0,
            "already-signaled acquire fence was not conserved exactly");
    require(Access::consumeCommit(backend, 0),
            "already-signaled frame commit did not drain");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "already-signaled acquire lifecycle did not drain");
}

void ApplyTransfersUnsignaledAcquireFenceWithoutWaiting() {
    Backend backend;
    Access::initialize(backend);
    require(Access::startFenceReactor(backend),
            "test fence reactor did not start");
    SwappyFixedExternalTransportReady ready{};
    int signalFd = -1;
    auto prepared = Access::prepare(
        backend, 0, &ready, 0, &signalFd);
    const auto receipt = Access::applyWithoutAcquireConsumption(
        backend, prepared, ready);
    const auto pending = backend.conservationSnapshot();
    require(receipt.applyBeforeAcquireSignalProven &&
                pending.applyBeforeAcquireSignalProvenCount == 1 &&
                pending.acquireFenceRecordDepth == 1 &&
                pending.backendInvariantFatalCount == 0,
            "apply waited for or lost the unsignaled acquire fence");
    const std::uint64_t one = 1;
    require(signalFd >= 0 &&
                write(signalFd, &one, sizeof(one)) == sizeof(one) &&
                close(signalFd) == 0 &&
                Access::waitAndConsumeAcquire(backend) &&
                backend.conservationSnapshot().acquireFenceRecordDepth == 0,
            "asynchronous acquire signal proof did not conserve exactly");
    require(Access::consumeCommit(backend, 0),
            "unsignaled-transfer frame commit did not drain");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "unsignaled-transfer acquire lifecycle did not drain");
}

void successorWaitsForPriorOnCommitThenAppliesBeforeComplete() {
    Backend backend;
    Access::initialize(backend);
    auto first = submit(backend, 0);
    SwappyFixedExternalTransportReady secondReady{};
    auto secondPrepared = Access::prepare(backend, 1, &secondReady);
    const auto beforeRejectedApply = backend.conservationSnapshot();
    Backend::SubmissionReceipt rejectedReceipt{};
    require(secondReady.firstStage == 0 &&
                swappy::fixedAppliedBufferRefValid(
                    secondReady.previousAppliedBufferRef) &&
                backend.queryApplyReadiness(secondPrepared) ==
                    Backend::ApplyReadiness::WAITING_PRIOR_LATCH &&
                backend.applyPreparedBufferTransaction(
                    secondPrepared, Access::claim(backend, secondReady),
                    &rejectedReceipt) ==
                    Backend::ApplyDisposition::NOT_APPLIED &&
                secondPrepared.state == Backend::PreparedTransactionState::
                    PREPARED_NOT_CLAIMED &&
                ntk::present::fakeApplyCount == 1 &&
                ntk::present::fakeSetBufferCount == 1,
            "successor applied before exact predecessor OnCommit");
    const auto afterRejectedApply = backend.conservationSnapshot();
    require(afterRejectedApply.callbackRecordDepth ==
                beforeRejectedApply.callbackRecordDepth &&
                afterRejectedApply.previousReleaseRecordDepth ==
                    beforeRejectedApply.previousReleaseRecordDepth &&
                afterRejectedApply.logicalUnlatchedNow ==
                    beforeRejectedApply.logicalUnlatchedNow &&
                afterRejectedApply.commitProofPendingNow ==
                    beforeRejectedApply.commitProofPendingNow &&
                afterRejectedApply.completeProofPendingNow ==
                    beforeRejectedApply.completeProofPendingNow &&
                afterRejectedApply.backendInvariantFatalCount ==
                    beforeRejectedApply.backendInvariantFatalCount,
            "rejected pre-OnCommit apply mutated backend ownership");
    require(Access::consumeCommit(backend, 0) &&
                backend.queryApplyReadiness(secondPrepared) ==
                    Backend::ApplyReadiness::READY,
            "exact predecessor OnCommit did not open successor admission");
    const auto secondReceipt =
        Access::apply(backend, secondPrepared, secondReady);
    const auto overlap = backend.conservationSnapshot();
    require(ntk::present::exactAppliedBufferRef(
                secondReceipt.previousAppliedBufferRef,
                first.receipt.appliedBufferRef) &&
                Access::latestEquals(
                    backend, secondReceipt.appliedBufferRef) &&
                overlap.callbackRecordDepth == 2 &&
                overlap.previousReleaseRecordDepth == 1 &&
                overlap.heldFrameworkRefCount == 2 &&
                overlap.releaseWaitCount == 1 &&
                overlap.applyBeforePriorCompleteCount == 1 &&
                overlap.applyBeforePriorCommitConsumedCount == 0 &&
                overlap.priorOnCompletePendingAtSuccessorApply == 1 &&
                overlap.retainedWaitingOnCompleteCount == 1 &&
                ntk::present::postApplyCallbackRetentionExact(
                    overlap.retainedWaitingOnCompleteCount,
                    overlap.commitProofPendingNow,
                    overlap.completeProofPendingNow) &&
                overlap.logicalUnlatchedNow == 1 &&
                overlap.maxLogicalUnlatched == 1 &&
                overlap.submittedWaitLatchCount == 1 &&
                overlap.commitProofPendingNow == 1 &&
                overlap.completeProofPendingNow == 2 &&
                overlap.maxCommitProofPending == 1 &&
                overlap.maxCompleteProofPending == 2 &&
                ntk::present::postApplyLatchConjunctionDepthsExact(
                    overlap.callbackRecordDepth,
                    overlap.maxCallbackRecordDepth,
                    overlap.logicalUnlatchedNow,
                    overlap.maxLogicalUnlatched,
                    overlap.submittedWaitLatchCount,
                    overlap.commitProofPendingNow,
                    overlap.completeProofPendingNow,
                    overlap.maxCommitProofPending,
                    overlap.maxCompleteProofPending) &&
                overlap.capacityWaitCount == 0 &&
                ntk::present::fakeBackpressureEnableCount == 1 &&
                overlap.backpressureEnableCount == 1,
            "post-OnCommit/pre-OnComplete successor overlap was not exact");
    require(Access::consumeCommit(backend, 1) &&
                backend.conservationSnapshot().logicalUnlatchedNow == 0,
            "successor commit proof did not drain");
    Access::publishComplete(0);
    Access::publishComplete(1);
    require(Access::drainAllExact(backend) &&
                Access::latestEquals(
                    backend, secondReceipt.appliedBufferRef) &&
                Access::releaseHeadAndDestroy(backend),
            "overlapped successor lifecycle did not conserve ownership");
}

void CompleteDequeuedBeforeCommitDoesNotDestroyOverlap() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    SwappyFixedExternalTransportReady secondReady{};
    auto secondPrepared = Access::prepare(backend, 1, &secondReady);
    Access::publishCommit(0);
    Access::publishComplete(0);
    Event commit{};
    Event complete{};
    require(backend.drainEvent(&commit) && backend.drainEvent(&complete) &&
                commit.kind == EventKind::COMPOSITOR_LATCHED &&
                complete.kind == EventKind::TRANSACTION_COMPLETED &&
                ntk::present::exactIdentity(
                    commit.identity, complete.identity) &&
                commit.callbackObservedNanos > 0 &&
                complete.callbackObservedNanos >=
                    commit.callbackObservedNanos &&
                backend.consumeTransactionCompleted(complete),
            "OnComplete consumer could not run before OnCommit consumer");
    const auto retained = backend.conservationSnapshot();
    Backend::ExactPresentLatchObservation observation{};
    require(retained.callbackRecordDepth == 1 &&
                retained.commitProofPendingNow == 1 &&
                retained.completeProofPendingNow == 0 &&
                !ntk::present::postApplyCallbackRetentionExact(
                    retained.retainedWaitingOnCompleteCount,
                    retained.commitProofPendingNow,
                    retained.completeProofPendingNow) &&
                backend.consumeCompositorLatch(commit, &observation) &&
                backend.conservationSnapshot().callbackRecordDepth == 0 &&
                backend.queryApplyReadiness(secondPrepared) ==
                    Backend::ApplyReadiness::READY,
            "independent callback flags did not retain then drain the record");

    const auto secondReceipt =
        Access::apply(backend, secondPrepared, secondReady);
    const auto overlap = backend.conservationSnapshot();
    require(ntk::present::exactAppliedBufferRef(
                secondReceipt.previousAppliedBufferRef,
                first.receipt.appliedBufferRef) &&
                overlap.callbackRecordDepth == 1 &&
                overlap.previousReleaseRecordDepth == 1 &&
                overlap.heldFrameworkRefCount == 2 &&
                overlap.releaseWaitCount == 1 &&
                Access::consumeCommit(backend, 1),
            "early complete consumption destroyed physical buffer overlap");
    Access::publishComplete(1);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "complete-before-commit overlap did not drain exactly");
}

void ObservedCompleteProofCanRemainPendingAcrossSuccessorApply() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    SwappyFixedExternalTransportReady secondReady{};
    auto secondPrepared = Access::prepare(backend, 1, &secondReady);
    Access::publishCommit(0);
    Access::publishComplete(0);
    Event commit{};
    Event complete{};
    Backend::ExactPresentLatchObservation observation{};
    require(backend.drainEvent(&commit) && backend.drainEvent(&complete) &&
                commit.kind == EventKind::COMPOSITOR_LATCHED &&
                complete.kind == EventKind::TRANSACTION_COMPLETED &&
                complete.callbackObservedNanos > 0 &&
                backend.consumeCompositorLatch(commit, &observation) &&
                backend.queryApplyReadiness(secondPrepared) ==
                    Backend::ApplyReadiness::READY,
            "observed OnComplete could not remain queued after exact OnCommit");

    const auto secondReceipt =
        Access::apply(backend, secondPrepared, secondReady);
    const auto overlap = backend.conservationSnapshot();
    require(ntk::present::exactAppliedBufferRef(
                secondReceipt.previousAppliedBufferRef,
                first.receipt.appliedBufferRef) &&
                overlap.applyBeforePriorCompleteCount == 1 &&
                overlap.priorOnCompletePendingAtSuccessorApply == 1 &&
                overlap.retainedWaitingOnCompleteCount == 1 &&
                overlap.callbackRecordDepth == 2 &&
                overlap.completeProofPendingNow == 2 &&
                overlap.backendInvariantFatalCount == 0,
            "queued-but-unconsumed OnComplete proof lost successor overlap");
    require(backend.consumeTransactionCompleted(complete) &&
                Access::consumeCommit(backend, 1),
            "deferred predecessor proof did not drain after successor apply");
    Access::publishComplete(1);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "queued OnComplete overlap lifecycle did not conserve ownership");
}

void k8LedgerAllowsEightPendingCompletesWithOneUnlatched() {
    Backend backend;
    Access::initialize(backend);
    std::array<Submitted, Pool::kSlotCount> submitted{};
    for (std::size_t index = 0; index < submitted.size() - 1; ++index) {
        submitted[index] = submit(backend, index);
        require(Access::consumeCommit(backend, index),
                "predecessor commit did not open the next K=8 admission");
    }
    Event deferredComplete{};
    require(Access::publishCompleteAndConsumeOnlyRelease(
                backend, 1, &deferredComplete) &&
                backend.conservationSnapshot().heldFrameworkRefCount ==
                    Pool::kSlotCount - 2,
            "physical release did not preserve deferred complete proof");
    submitted.back() = submit(backend, submitted.size() - 1);
    const auto peak = backend.conservationSnapshot();
    require(peak.callbackRecordDepth == Pool::kSlotCount &&
                peak.maxCallbackRecordDepth == Pool::kSlotCount &&
                peak.previousReleaseRecordDepth == Pool::kSlotCount - 2 &&
                peak.heldFrameworkRefCount == Pool::kSlotCount - 1 &&
                peak.releaseWaitCount == Pool::kSlotCount - 2 &&
                peak.freeReusableCount == 1 &&
                ntk::present::fakeBackpressureEnableCount == 1 &&
                peak.backpressureEnableCount == 1 &&
                peak.backpressureDisableCount == 0 &&
                peak.logicalUnlatchedNow == 1 &&
                peak.maxLogicalUnlatched == 1 &&
                peak.submittedWaitLatchCount == 1 &&
                peak.commitProofPendingNow == 1 &&
                peak.completeProofPendingNow == Pool::kSlotCount &&
                peak.maxCommitProofPending == 1 &&
                peak.maxCompleteProofPending == Pool::kSlotCount &&
                ntk::present::postApplyLatchConjunctionDepthsExact(
                    peak.callbackRecordDepth,
                    peak.maxCallbackRecordDepth,
                    peak.logicalUnlatchedNow,
                    peak.maxLogicalUnlatched,
                    peak.submittedWaitLatchCount,
                    peak.commitProofPendingNow,
                    peak.completeProofPendingNow,
                    peak.maxCommitProofPending,
                    peak.maxCompleteProofPending) &&
                peak.applyBeforePriorCommitConsumedCount == 0 &&
                peak.applyBeforePriorCompleteCount == Pool::kSlotCount - 1 &&
                peak.capacityExhaustedCount == 0 &&
                peak.capacityWaitCount == 0 &&
                backend.pool().hasFreeRenderTarget(),
            "K=8 complete ledger violated depth-one latch admission");

    require(Access::consumeCommit(backend, submitted.size() - 1),
            "final K=8 commit proof did not drain");
    require(backend.conservationSnapshot().logicalUnlatchedNow == 0 &&
                backend.conservationSnapshot().latestConsumedLatchRefSerial ==
                    submitted.back().receipt.appliedBufferRef.serial,
            "joined commits regressed the latest consumed ref");
    require(backend.consumeTransactionCompleted(deferredComplete),
            "deferred physical OnComplete proof was not consumed exactly");
    for (std::size_t index = 0; index < submitted.size(); ++index) {
        if (index != 1) Access::publishComplete(index);
    }
    require(Access::drainAllExact(backend) &&
                backend.conservationSnapshot().freeReusableCount ==
                    Pool::kSlotCount - 1 &&
                Access::releaseHeadAndDestroy(backend),
            "bounded K=8 callback/release ledgers did not fully drain");
}

void sevenHeldRefsBackpressureDirectPipelineButRejectSerializedApply() {
    Backend backend;
    Access::initialize(backend);
    std::array<Submitted, Pool::kSlotCount - 1> submitted{};
    for (std::size_t index = 0; index < submitted.size(); ++index) {
        submitted[index] = submit(backend, index);
        require(Access::consumeCommit(backend, index),
                "capacity predecessor commit did not open next admission");
    }
    SwappyFixedExternalTransportReady blockedReady{};
    auto blocked = Access::prepare(
        backend, Pool::kSlotCount - 1, &blockedReady);
    require(backend.queryDirectApplyReadiness(blocked) ==
                Backend::ApplyReadiness::WAITING_PRIOR_LATCH &&
                backend.conservationSnapshot().capacityWaitCount == 1 &&
                backend.conservationSnapshot().capacityExhaustedCount == 0 &&
                backend.queryApplyReadiness(blocked) ==
                Backend::ApplyReadiness::FATAL &&
                backend.conservationSnapshot().heldFrameworkRefCount ==
                    Pool::kSlotCount - 1 &&
                backend.conservationSnapshot().freeReusableCount == 0 &&
                backend.conservationSnapshot().capacityExhaustedCount == 1 &&
                backend.abortPreparedBufferTransaction(blocked),
            "H=7 did not wait for direct capacity or fail-fast for serialized apply");

    for (std::size_t index = 0; index < submitted.size(); ++index) {
        Access::publishComplete(index);
    }
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "H=7 capacity boundary lifecycle did not drain");
}

void fullAppliedRefMismatchIsFatalAndNeverFrees() {
    Backend backend;
    Access::initialize(backend);
    auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "first latch was not consumed before successor");
    auto second = submit(backend, 1);
    Event wrong{};
    wrong.kind = EventKind::PREVIOUS_BUFFER_RELEASED;
    wrong.identity = second.receipt.identity;
    wrong.eventSequence = 999;
    wrong.releasedBufferSlot =
        first.receipt.appliedBufferRef.identity.bufferSlot;
    wrong.releasedBufferGeneration =
        first.receipt.appliedBufferRef.identity.bufferGeneration;
    wrong.releasedAppliedBufferRefSerial =
        first.receipt.appliedBufferRef.serial + 1;
    wrong.releasedBufferIdentity =
        first.receipt.appliedBufferRef.identity;
    require(!backend.consumePreviousBufferReleased(wrong) &&
                backend.pool().find(
                    first.receipt.appliedBufferRef.identity.bufferSlot,
                    first.receipt.appliedBufferRef.identity.bufferGeneration)
                        ->state ==
                    Pool::SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE &&
                backend.conservationSnapshot().backendInvariantFatalCount == 1,
            "mismatched full AppliedBufferRef freed a live buffer");
}

void reactorContractRemainsStrict() {
    require(!Access::reactorAccepted(nullptr, 1) &&
                !Access::reactorAccepted(
                    reinterpret_cast<ALooper*>(1), 0) &&
                Access::reactorAccepted(
                    reinterpret_cast<ALooper*>(1), 1),
            "fence reactor initialization accepted partial state");
}

void unavailableHostLatchTimestampUsesObservedOnCommitBoundary() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    ntk::present::fakeLatchNanos = -1;
    Access::publishCommit(0);
    Event commit{};
    Backend::ExactPresentLatchObservation observation{};
    require(backend.drainEvent(&commit) &&
                commit.kind == EventKind::COMPOSITOR_LATCHED &&
                commit.latchSource ==
                    ntk::present::FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT &&
                commit.latchNanos == commit.callbackObservedNanos &&
                commit.callbackObservedNanos > 0 &&
                backend.consumeCompositorLatch(commit, &observation) &&
                observation.latchNanos == commit.callbackObservedNanos,
            "unavailable host latch timestamp discarded an exact OnCommit callback");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "observed OnCommit boundary did not preserve exact retirement ownership");
    (void)first;
}

void lateGeometryDeadlinesRemainFifoAndDoNotBatch() {
    constexpr std::int64_t kRefresh = 16'666'667;
    constexpr std::int64_t kNow = 1'000'000'000;
    constexpr std::int64_t kLead = kRefresh / 4;
    require(ntk::present::scheduleGeometryDesiredPresentNanos(
                kNow + 10'000'000, kNow, 0, kRefresh) ==
                kNow + 10'000'000,
            "usable producer geometry deadline was replaced");
    const std::int64_t rebased = ntk::present::scheduleGeometryDesiredPresentNanos(
        kNow - 80'000'000, kNow, 0, kRefresh);
    require(rebased == kNow + kLead,
            "late geometry deadline was not rebased ahead of apply");
    require(ntk::present::scheduleGeometryDesiredPresentNanos(
                kNow - 60'000'000, kNow + 1'000'000, rebased, kRefresh) ==
                rebased + kRefresh,
            "queued geometry crops did not retain one-refresh FIFO spacing");
    require(ntk::present::scheduleGeometryDesiredPresentNanos(
                0, kNow, rebased, kRefresh) == 0,
            "missing device producer deadline gained a heuristic clock");
    require(ntk::present::geometryDesiredPresentNanosForRuntime(
                true, kNow + 10'000'000, kNow, rebased, kRefresh) == 0,
            "host Handler cadence was queued behind a second future clock");
    require(ntk::present::geometryDesiredPresentNanosForRuntime(
                false, kNow - 80'000'000, kNow, 0, kRefresh) == rebased,
            "device desired-present scheduling changed with the host policy");
}

void offscreenPixelsPrepareOneParkedRollingBandSuccessor() {
    require(ntk::present::shouldPrecomposeRollingBandSuccessor(
                true, false, 640, 640),
            "same-origin offscreen pixels did not prepare a parked successor");
    require(ntk::present::shouldPrecomposeRollingBandSuccessor(
                true, false, 4'106, 640),
            "translated rolling-band overlap did not prepare its successor");
    require(!ntk::present::shouldPrecomposeRollingBandSuccessor(
                true, true, 4'106, 640),
            "an already-identical band was recomposed");
    require(!ntk::present::shouldPrecomposeRollingBandSuccessor(
                false, false, 4'106, 640),
            "a failed geometry transaction started background composition");
    require(!ntk::present::shouldPrecomposeRollingBandSuccessor(
                true, false, -1, 640),
            "invalid applied crop started background composition");
    require(ntk::present::shouldPrecomposeRollingBandSuccessor(
                true, false, 4'106, 640, true),
            "physical overlap did not prepare the successor on the isolated worker");
}

void transparentPulseGeometryRetiresPrivatelyOutsideTheImageCompletionLedger() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "geometry stress predecessor commit did not drain");

    constexpr std::size_t kGeometryTransactions = 64;
    for (std::size_t index = 0; index < kGeometryTransactions; ++index) {
        Backend::SubmissionReceipt receipt{};
        require(backend.applyGeometryTransactionDirect(
                    Access::base(index + 1),
                    static_cast<std::int32_t>(index % 100), 100,
                    static_cast<std::int64_t>(10'000 + index), 0,
                    &receipt) == Backend::ApplyDisposition::APPLIED &&
                    receipt.submitted && receipt.setBufferCount == 0 &&
                    Access::consumeCommit(backend, index + 1),
                "transparent-pulse geometry exhausted its bounded callback ledger");
        Access::publishComplete(index + 1);
        require(Access::drainAllExact(backend),
                "transparent-pulse private completion leaked an image event");
        const auto snapshot = backend.conservationSnapshot();
        require(snapshot.callbackRecordDepth == 1 &&
                    snapshot.logicalUnlatchedNow == 0 &&
                    snapshot.commitProofPendingNow == 0 &&
                    snapshot.completeProofPendingNow == 1 &&
                    snapshot.retainedWaitingOnCompleteCount == 1 &&
                    snapshot.capacityExhaustedCount == 0 &&
                    backend.hasGeometryTransactionCapacity(),
                "transparent-pulse geometry retained a second completion dependency");
    }
    require(ntk::present::fakeCommitContextCount ==
                kGeometryTransactions + 1 &&
                ntk::present::fakeCompleteContextCount ==
                    kGeometryTransactions + 1 &&
                ntk::present::fakeSetBufferCount ==
                    kGeometryTransactions + 1,
            "geometry transactions did not alternate real pulse buffers");

    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "geometry stress did not preserve the buffer owner's completion lifecycle");
    (void)first;
}

void callbackCookieExhaustionBackpressuresBeforeBandPreparation() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "callback-capacity predecessor commit did not drain");

    std::array<std::size_t, Access::callbackCookieCount> occupied{};
    const std::size_t occupiedCount =
        Access::occupyEveryFreeCallbackCookie(backend, occupied);
    require(occupiedCount > 0 && !backend.hasDirectSubmissionCapacity() &&
                !backend.hasGeometryTransactionCapacity(),
            "exhausted callback cookies still admitted a band or geometry transaction");

    Access::releaseCallbackCookies(backend, occupied, 0, 1);
    require(backend.hasDirectSubmissionCapacity() &&
                backend.hasGeometryTransactionCapacity(),
            "returned callback cookie did not release bounded backpressure");
    Access::releaseCallbackCookies(backend, occupied, 1, occupiedCount);

    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "callback-capacity lifecycle did not conserve ownership");
    (void)first;
}

void twoImmutablePulseBuffersSpanTheFullCallbackWindow() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "shared-pulse predecessor commit did not drain");

    constexpr std::size_t kHeldGeometryCallbacks =
        Access::callbackRecordCapacity - 1;
    for (std::size_t index = 0; index < kHeldGeometryCallbacks; ++index) {
        Backend::SubmissionReceipt receipt{};
        require(backend.applyGeometryTransactionDirect(
                    Access::base(index + 1),
                    static_cast<std::int32_t>(index % 100), 100,
                    static_cast<std::int64_t>(20'000 + index), 0,
                    &receipt) == Backend::ApplyDisposition::APPLIED,
                "two immutable pulse buffers did not span the callback ledger");
        const std::size_t bufferCall = index + 1;
        require(ntk::present::fakeSetBufferSurfaces[bufferCall] ==
                    reinterpret_cast<ASurfaceControl*>(3) &&
                    ntk::present::fakeSetBufferValues[bufferCall] ==
                        reinterpret_cast<AHardwareBuffer*>(100 + (index % 2)),
                "geometry pulse identities did not alternate exactly");
    }
    require(!backend.hasGeometryTransactionCapacity() &&
                backend.conservationSnapshot().capacityExhaustedCount == 0,
            "held callback window was not bounded by its proof ledger");

    for (std::size_t index = 0; index < kHeldGeometryCallbacks; ++index) {
        Access::publishCommit(index + 1);
        Access::publishComplete(index + 1);
        require(Access::drainAllExact(backend),
                "shared immutable pulse callback did not drain exactly");
    }
    require(backend.hasGeometryTransactionCapacity() &&
                Access::invariantsHold(backend),
            "drained callback window did not restore geometry admission");
    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "shared immutable pulse lifecycle did not conserve ownership");
    (void)first;
}

void pulsePrivateCompleteMayPrecedeVisibleCommitConsumption() {
    Backend backend;
    Access::initialize(backend);
    const auto first = submit(backend, 0);
    require(Access::consumeCommit(backend, 0),
            "geometry-order predecessor commit did not drain");

    Backend::SubmissionReceipt receipt{};
    require(backend.applyGeometryTransactionDirect(
                Access::base(1), 10, 100, 10'001, 0, &receipt) ==
                Backend::ApplyDisposition::APPLIED,
            "geometry-order pulse did not apply");
    // SurfaceFlinger may invoke OnCommit and then OnComplete before the renderer thread gets to
    // consume the queued OnCommit event. The private completion is already stable at that point
    // and must not poison a later, otherwise unrelated ownership check.
    Access::publishCommit(1);
    Access::publishComplete(1);
    require(Access::invariantsHold(backend) &&
                backend.conservationSnapshot().backendInvariantFatalCount == 0,
            "private pulse completion poisoned pre-consumption geometry state");
    require(Access::drainAllExact(backend),
            "pre-consumption private pulse completion did not drain exactly");

    Access::publishComplete(0);
    require(Access::drainAllExact(backend) &&
                Access::releaseHeadAndDestroy(backend),
            "geometry-order lifecycle did not conserve ownership");
    (void)first;
}

}  // namespace

int main() {
    reserveAbortAndApplyAreExactlyOnce();
    directBufferUsesRealDisplayFrameTimeline();
    geometryUsesRealDisplayFrameTimelineWithoutDesiredTimeFallback();
    geometryOnlyMovesTheSeparateContainerUsedByFullBands();
    AlreadySignaledAcquireFenceStillConserves();
    ApplyTransfersUnsignaledAcquireFenceWithoutWaiting();
    successorWaitsForPriorOnCommitThenAppliesBeforeComplete();
    CompleteDequeuedBeforeCommitDoesNotDestroyOverlap();
    ObservedCompleteProofCanRemainPendingAcrossSuccessorApply();
    k8LedgerAllowsEightPendingCompletesWithOneUnlatched();
    sevenHeldRefsBackpressureDirectPipelineButRejectSerializedApply();
    fullAppliedRefMismatchIsFatalAndNeverFrees();
    reactorContractRemainsStrict();
    unavailableHostLatchTimestampUsesObservedOnCommitBoundary();
    lateGeometryDeadlinesRemainFifoAndDoNotBatch();
    offscreenPixelsPrepareOneParkedRollingBandSuccessor();
    transparentPulseGeometryRetiresPrivatelyOutsideTheImageCompletionLedger();
    callbackCookieExhaustionBackpressuresBeforeBandPreparation();
    twoImmutablePulseBuffersSpanTheFullCallbackWindow();
    pulsePrivateCompleteMayPrecedeVisibleCommitConsumption();
    std::cout << "PASS SurfaceControlPresentBackendTest schema14 19/19\n";
    return 0;
}
