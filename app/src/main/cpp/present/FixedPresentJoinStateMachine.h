#pragma once

#include "FixedPresentEventContract.h"
#include "swappy/swappyGL_extra.h"

#include <cstdint>

namespace ntk::present {

enum class FixedPresentJoinResult : std::uint8_t {
    WAITING = 0,
    QUALIFIED = 1,
    FATAL = 2,
};

class FixedPresentJoinState final {
public:
    bool commit(const FixedFrameIdentity& identity) noexcept {
        if (committed_ || identity.engineGeneration == 0 ||
            identity.surfaceEpoch == 0 || identity.authorityGeneration <= 0 ||
            identity.authority <= 0 || identity.workGeneration == 0 ||
            identity.ntkFrameId == 0 || identity.frameSequence == 0 ||
            identity.admissionSequence == 0 || identity.capsuleSequence == 0 ||
            identity.backendSurfaceSerial == 0 || identity.transactionSerial == 0 ||
            identity.bufferGeneration == 0 || identity.frameTimelineVsyncId == 0) {
            fatal_ = true;
            return false;
        }
        identity_ = identity;
        committed_ = true;
        return true;
    }

    bool acceptLatch(const FixedPresentEvent& event) noexcept {
        if (!committed_ || latchSeen_ || !isExactLatch(identity_, event)) {
            fatal_ = true;
            return false;
        }
        latchSeen_ = true;
        latchEvent_ = event;
        return true;
    }

    bool acceptRetirement(
            const SwappyFixedRetirementTelemetryV2& event) noexcept {
        if (!committed_ || retirementSeen_ ||
            !isExactRetirement(identity_, event)) {
            fatal_ = true;
            return false;
        }
        retirementSeen_ = true;
        retirementEvent_ = event;
        return true;
    }

    FixedPresentJoinResult result() const noexcept {
        if (fatal_) return FixedPresentJoinResult::FATAL;
        return latchSeen_ && retirementSeen_
            ? FixedPresentJoinResult::QUALIFIED
            : FixedPresentJoinResult::WAITING;
    }

    static bool isExactLatch(
            const FixedFrameIdentity& identity,
            const FixedPresentEvent& event) noexcept {
        return exactIdentity(identity, event.identity) &&
            event.schemaVersion == kFixedPresentEventSchemaVersion &&
            event.kind == FixedPresentEventKind::COMPOSITOR_LATCHED &&
            event.latchSource ==
                FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT &&
            event.eventSequence != 0 && event.latchNanos > 0 &&
            event.callbackObservedNanos >= event.latchNanos &&
            event.onCommitCallbackCount == 1;
    }

    static bool isExactRetirement(
            const FixedFrameIdentity& identity,
            const SwappyFixedRetirementTelemetryV2& event) noexcept {
        return isExactRetirementIdentity(identity, event) &&
            event.state == SWAPPY_FIXED_RETIREMENT_RETIRED &&
            event.fatalReason == 0 && event.targetReachedNanos > 0 &&
            event.callbackPublishedNanos >= event.targetReachedNanos;
    }

    static bool isExactRetirementIdentity(
            const FixedFrameIdentity& identity,
            const SwappyFixedRetirementTelemetryV2& event) noexcept {
        const bool terminalExact =
            (event.state == SWAPPY_FIXED_RETIREMENT_RETIRED &&
             event.fatalReason == 0) ||
            (event.state == SWAPPY_FIXED_RETIREMENT_FATAL &&
             event.fatalReason != 0);
        return event.structSize == sizeof(event) &&
            event.version == SWAPPY_FIXED_RETIREMENT_TELEMETRY_V2_VERSION &&
            terminalExact &&
            event.engineGeneration == identity.engineGeneration &&
            event.surfaceEpoch == identity.surfaceEpoch &&
            event.authorityGeneration == identity.authorityGeneration &&
            event.authority == identity.authority &&
            event.workGeneration == identity.workGeneration &&
            event.ntkFrameId == identity.ntkFrameId &&
            event.frameSequence == identity.frameSequence &&
            event.admissionSequence == identity.admissionSequence &&
            event.capsuleSequence == identity.capsuleSequence &&
            event.backendSurfaceSerial == identity.backendSurfaceSerial &&
            event.transactionSerial == identity.transactionSerial &&
            event.bufferSlot == identity.bufferSlot &&
            event.bufferGeneration == identity.bufferGeneration &&
            event.frameTimelineVsyncId == identity.frameTimelineVsyncId &&
            event.retirementSequence != 0 &&
            event.callbackPublishedNanos > 0 &&
            event.targetWaitCount == 1 && event.targetRebaseCount == 0;
    }

private:
    FixedFrameIdentity identity_{};
    FixedPresentEvent latchEvent_{};
    SwappyFixedRetirementTelemetryV2 retirementEvent_{};
    bool committed_ = false;
    bool latchSeen_ = false;
    bool retirementSeen_ = false;
    bool fatal_ = false;
};

}  // namespace ntk::present
