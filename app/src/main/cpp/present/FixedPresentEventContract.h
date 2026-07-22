#pragma once

#include <android/choreographer.h>

#include <cstdint>

namespace ntk::present {

constexpr std::uint32_t kFixedPresentEventSchemaVersion = 11;

struct FixedFrameIdentity {
    std::uint64_t engineGeneration = 0;
    std::uint64_t surfaceEpoch = 0;
    std::int64_t authorityGeneration = 0;
    std::int64_t authority = 0;
    std::uint64_t workGeneration = 0;
    std::uint64_t ntkFrameId = 0;
    std::uint64_t frameSequence = 0;
    std::uint64_t admissionSequence = 0;
    std::uint64_t capsuleSequence = 0;
    std::uint64_t backendSurfaceSerial = 0;
    std::uint64_t transactionSerial = 0;
    std::uint64_t bufferSlot = 0;
    std::uint64_t bufferGeneration = 0;
    AVsyncId frameTimelineVsyncId = 0;
};

inline bool exactIdentity(
        const FixedFrameIdentity& left,
        const FixedFrameIdentity& right) noexcept {
    return left.engineGeneration == right.engineGeneration &&
        left.surfaceEpoch == right.surfaceEpoch &&
        left.authorityGeneration == right.authorityGeneration &&
        left.authority == right.authority &&
        left.workGeneration == right.workGeneration &&
        left.ntkFrameId == right.ntkFrameId &&
        left.frameSequence == right.frameSequence &&
        left.admissionSequence == right.admissionSequence &&
        left.capsuleSequence == right.capsuleSequence &&
        left.backendSurfaceSerial == right.backendSurfaceSerial &&
        left.transactionSerial == right.transactionSerial &&
        left.bufferSlot == right.bufferSlot &&
        left.bufferGeneration == right.bufferGeneration &&
        left.frameTimelineVsyncId == right.frameTimelineVsyncId;
}

struct AppliedBufferRef {
    std::uint64_t serial = 0;
    FixedFrameIdentity identity{};
};

inline bool validAppliedBufferRef(
        const AppliedBufferRef& ref) noexcept {
    return ref.serial != 0 && ref.identity.engineGeneration != 0 &&
        ref.identity.surfaceEpoch != 0 &&
        ref.identity.authorityGeneration > 0 &&
        ref.identity.authority > 0 && ref.identity.workGeneration != 0 &&
        ref.identity.ntkFrameId != 0 && ref.identity.frameSequence != 0 &&
        ref.identity.admissionSequence != 0 &&
        ref.identity.capsuleSequence != 0 &&
        ref.identity.backendSurfaceSerial != 0 &&
        ref.identity.transactionSerial != 0 &&
        ref.identity.bufferGeneration != 0 &&
        ref.identity.frameTimelineVsyncId != 0;
}

inline bool exactAppliedBufferRef(
        const AppliedBufferRef& left,
        const AppliedBufferRef& right) noexcept {
    return left.serial == right.serial &&
        exactIdentity(left.identity, right.identity);
}

enum class FixedPresentEventKind : std::uint8_t {
    COMPOSITOR_LATCHED = 1,
    TRANSACTION_COMPLETED = 2,
    PREVIOUS_BUFFER_RELEASED = 3,
    INVALID_CALLBACK = 4,
    TEARDOWN_COMPLETED = 5,
    ACQUIRE_FENCE_SIGNALED = 6,
};

enum class FixedLatchSource : std::uint32_t {
    NONE = 0,
    ANDROID_SURFACE_CONTROL_ON_COMMIT = 1,
};

struct FixedPresentEvent {
    std::uint32_t structSize = sizeof(FixedPresentEvent);
    std::uint32_t schemaVersion = kFixedPresentEventSchemaVersion;
    FixedPresentEventKind kind = FixedPresentEventKind::INVALID_CALLBACK;
    FixedFrameIdentity identity{};
    FixedLatchSource latchSource = FixedLatchSource::NONE;
    std::uint64_t eventSequence = 0;
    std::int64_t latchNanos = 0;
    std::int64_t callbackObservedNanos = 0;
    std::uint64_t releasedBufferSlot = 0;
    std::uint64_t releasedBufferGeneration = 0;
    std::uint64_t releasedAppliedBufferRefSerial = 0;
    FixedFrameIdentity releasedBufferIdentity{};
    std::uint32_t onCommitCallbackCount = 0;
    std::uint32_t onCompleteCallbackCount = 0;
    std::uint64_t acquireFenceSerial = 0;
    std::int64_t acquireFenceSignalNanos = 0;
    std::uint32_t proofFdCloseCount = 0;
};

enum class LatchTerminalState : std::uint8_t {
    WAITING_EVENT = 0,
    LATCHED = 1,
    EXPLICITLY_NOT_LATCHED = 2,
    INVALID_EVENT = 3,
};

enum class RetirementTerminalState : std::uint8_t {
    WAITING_EVENT = 0,
    RETIRED = 1,
    INVALID_EVENT = 2,
};

struct GpuSubmissionProof {
    std::uint64_t bufferSlot = 0;
    std::uint64_t bufferGeneration = 0;
    std::int64_t renderBeginNanos = 0;
    std::int64_t renderEndNanos = 0;
    std::int64_t acquireFenceIssuedNanos = 0;
    std::int64_t acquireFenceExportReturnNanos = 0;
    std::uint64_t acquireFenceSerial = 0;
    std::uint32_t acquireFenceDupCount = 0;
    std::uint32_t rendererGpuClientWaitCount = 0;
};

inline bool validGpuSubmissionProof(const GpuSubmissionProof& proof) noexcept {
    return proof.bufferGeneration != 0 &&
        proof.renderBeginNanos > 0 &&
        proof.renderEndNanos >= proof.renderBeginNanos &&
        proof.acquireFenceIssuedNanos >= proof.renderEndNanos &&
        proof.acquireFenceExportReturnNanos >=
            proof.acquireFenceIssuedNanos &&
        proof.acquireFenceSerial != 0 && proof.acquireFenceDupCount == 2 &&
        proof.rendererGpuClientWaitCount == 0;
}

}  // namespace ntk::present
