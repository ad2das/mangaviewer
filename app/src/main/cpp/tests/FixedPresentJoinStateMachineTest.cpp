#include "../present/FixedPresentJoinStateMachine.h"

#include <cstdlib>
#include <iostream>

namespace {

using ntk::present::FixedFrameIdentity;
using ntk::present::FixedPresentEvent;
using ntk::present::FixedPresentEventKind;
using ntk::present::FixedPresentJoinResult;
using ntk::present::FixedPresentJoinState;
using ntk::present::FixedLatchSource;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL FixedPresentJoinStateMachineTest: " << message << '\n';
    std::exit(1);
}

FixedFrameIdentity identity(std::uint64_t frame = 6) {
    return {
        .engineGeneration = 1,
        .surfaceEpoch = 2,
        .authorityGeneration = 3,
        .authority = 4,
        .workGeneration = 5,
        .ntkFrameId = frame,
        .frameSequence = 7,
        .admissionSequence = 8,
        .capsuleSequence = 9,
        .backendSurfaceSerial = 10,
        .transactionSerial = 11,
        .bufferSlot = 1,
        .bufferGeneration = 12,
        .frameTimelineVsyncId = 13,
    };
}

FixedPresentEvent latch(const FixedFrameIdentity& id) {
    FixedPresentEvent event{};
    event.kind = FixedPresentEventKind::COMPOSITOR_LATCHED;
    event.identity = id;
    event.latchSource = FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT;
    event.eventSequence = 14;
    event.latchNanos = 100;
    event.callbackObservedNanos = 101;
    event.onCommitCallbackCount = 1;
    return event;
}

SwappyFixedRetirementTelemetryV2 retirement(const FixedFrameIdentity& id) {
    SwappyFixedRetirementTelemetryV2 event{};
    event.structSize = sizeof(event);
    event.version = SWAPPY_FIXED_RETIREMENT_TELEMETRY_V2_VERSION;
    event.state = SWAPPY_FIXED_RETIREMENT_RETIRED;
    event.engineGeneration = id.engineGeneration;
    event.surfaceEpoch = id.surfaceEpoch;
    event.authorityGeneration = id.authorityGeneration;
    event.authority = id.authority;
    event.workGeneration = id.workGeneration;
    event.ntkFrameId = id.ntkFrameId;
    event.frameSequence = id.frameSequence;
    event.admissionSequence = id.admissionSequence;
    event.capsuleSequence = id.capsuleSequence;
    event.backendSurfaceSerial = id.backendSurfaceSerial;
    event.transactionSerial = id.transactionSerial;
    event.bufferSlot = id.bufferSlot;
    event.bufferGeneration = id.bufferGeneration;
    event.frameTimelineVsyncId = id.frameTimelineVsyncId;
    event.retirementSequence = 15;
    event.targetReachedNanos = 90;
    event.callbackPublishedNanos = 102;
    event.targetWaitCount = 1;
    return event;
}

void exactLatchThenRetirementQualifies() {
    FixedPresentJoinState state;
    const auto id = identity();
    require(state.commit(id), "exact identity commit rejected");
    require(state.acceptLatch(latch(id)), "exact latch rejected");
    require(state.result() == FixedPresentJoinResult::WAITING,
            "latch alone qualified");
    require(state.acceptRetirement(retirement(id)), "exact retirement rejected");
    require(state.result() == FixedPresentJoinResult::QUALIFIED,
            "two-event exact join did not qualify");
}

void exactRetirementThenLatchQualifies() {
    FixedPresentJoinState state;
    const auto id = identity();
    require(state.commit(id) && state.acceptRetirement(retirement(id)) &&
            state.acceptLatch(latch(id)) &&
            state.result() == FixedPresentJoinResult::QUALIFIED,
            "reverse callback order failed");
}

void duplicateLatchIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    require(state.commit(id) && state.acceptLatch(latch(id)), "setup failed");
    require(!state.acceptLatch(latch(id)) &&
            state.result() == FixedPresentJoinResult::FATAL,
            "duplicate latch was accepted");
}

void duplicateRetirementIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    require(state.commit(id) && state.acceptRetirement(retirement(id)),
            "setup failed");
    require(!state.acceptRetirement(retirement(id)) &&
            state.result() == FixedPresentJoinResult::FATAL,
            "duplicate retirement was accepted");
}

void wrongLatchIdentityIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = latch(identity(99));
    require(state.commit(id) && !state.acceptLatch(event) &&
            state.result() == FixedPresentJoinResult::FATAL,
            "cross-frame latch joined");
}

void wrongRetirementIdentityIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = retirement(id);
    ++event.transactionSerial;
    require(state.commit(id) && !state.acceptRetirement(event) &&
            state.result() == FixedPresentJoinResult::FATAL,
            "cross-transaction retirement joined");
}

void nonSurfaceControlLatchIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = latch(id);
    event.latchSource = FixedLatchSource::NONE;
    require(state.commit(id) && !state.acceptLatch(event),
            "non-SurfaceControl latch accepted");
}

void zeroLatchTimeIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = latch(id);
    event.latchNanos = 0;
    require(state.commit(id) && !state.acceptLatch(event),
            "zero latch time accepted");
}

void retirementRebaseIsFatal() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = retirement(id);
    event.targetRebaseCount = 1;
    require(state.commit(id) && !state.acceptRetirement(event),
            "retirement rebase accepted");
}

void callbackCountMustBeOne() {
    FixedPresentJoinState state;
    const auto id = identity();
    auto event = latch(id);
    event.onCommitCallbackCount = 2;
    require(state.commit(id) && !state.acceptLatch(event),
            "duplicate callback count accepted");
}

void secondOutstandingCommitIsFatal() {
    FixedPresentJoinState state;
    require(state.commit(identity()) && !state.commit(identity(99)) &&
            state.result() == FixedPresentJoinResult::FATAL,
            "second outstanding commit accepted");
}

}  // namespace

int main() {
    exactLatchThenRetirementQualifies();
    exactRetirementThenLatchQualifies();
    duplicateLatchIsFatal();
    duplicateRetirementIsFatal();
    wrongLatchIdentityIsFatal();
    wrongRetirementIdentityIsFatal();
    nonSurfaceControlLatchIsFatal();
    zeroLatchTimeIsFatal();
    retirementRebaseIsFatal();
    callbackCountMustBeOne();
    secondOutstandingCommitIsFatal();
    std::cout << "PASS FixedPresentJoinStateMachineTest schema11 11/11\n";
    return 0;
}
