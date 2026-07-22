#include "../swappy/games-frame-pacing/common/FixedExternalTransportAdmission.h"
#include "../swappy/games-frame-pacing/common/FixedDisplayTimingAuthority.h"
#include "../swappy/games-frame-pacing/common/FixedFrameTimelineAuthority.h"

#include <cstdlib>
#include <iostream>
#include <limits>

namespace {

using swappy::FixedExternalTransportAdmissionOutcome;
using swappy::FixedPhasePlanInput;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL FixedExternalTransportAdmissionTest: " << message
              << '\n';
    std::exit(1);
}

}  // namespace

int main() {
    require(swappy::fixedFrameTimelineDeadlineFromDisplay(6'111'111) ==
                5'111'111,
            "Display deadline retained the 1 ms compositor allowance");
    constexpr std::int64_t t = 11'111'111;
    constexpr std::int64_t a = 2'000'000;
    constexpr std::int64_t d = 6'111'111;
    constexpr std::int64_t q = t / 2;
    constexpr std::int64_t r = 1'000'000'000;
    constexpr std::int64_t p1 = r + t;
    constexpr std::int64_t c1 = p1 - d;
    constexpr std::int64_t p2 = p1 + t;
    constexpr std::int64_t o2 = p2 - (t + t / 2);
    constexpr std::int64_t g2 = c1 > o2 ? c1 : o2;
    constexpr std::int64_t l = p2 - d - q;
    const auto inputAt = [](std::int64_t decision) {
        return FixedPhasePlanInput{t, a, d, r + a, 100, decision};
    };

    const auto case1 = swappy::classifyFixedExternalTransportAdmission(
        inputAt(c1 - q), q);
    require(case1.valid && case1.claimMayBeIssued &&
            case1.outcome == FixedExternalTransportAdmissionOutcome::
                CASE1_TRANSPORT_PROVABLE,
            "C1-B must be Case 1 transport-provable");

    const auto defer = swappy::classifyFixedExternalTransportAdmission(
        inputAt(c1 - q + 1), q);
    require(defer.valid && !defer.claimMayBeIssued &&
            !defer.rawOpportunityDisposed && defer.case2GateNanos == g2 &&
            defer.outcome == FixedExternalTransportAdmissionOutcome::
                DEFER_TO_CASE2_GATE,
            "C1-B+1 must retain and defer to exact Case 2 gate");

    constexpr FixedPhasePlanInput observedCliff{
        11'111'111,
        2'000'000,
        6'111'111,
        19'297'212'310'586,
        1,
        19'297'215'232'700,
    };
    const auto observed = swappy::classifyFixedExternalTransportAdmission(
        observedCliff, q);
    require(observed.valid && !observed.claimMayBeIssued &&
            !observed.rawOpportunityDisposed &&
            observed.outcome == FixedExternalTransportAdmissionOutcome::
                DEFER_TO_CASE2_GATE,
            "C1-77886ns must remain raw-open but transport-deferred");

    const auto atG2 = swappy::classifyFixedExternalTransportAdmission(
        inputAt(g2), q);
    require(atG2.valid && atG2.claimMayBeIssued &&
            atG2.outcome == FixedExternalTransportAdmissionOutcome::
                CASE2_TRANSPORT_PROVABLE,
            "G2 must admit Case 2 immediately");
    const auto atLMinusOne = swappy::classifyFixedExternalTransportAdmission(
        inputAt(l - 1), q);
    require(atLMinusOne.valid && atLMinusOne.claimMayBeIssued &&
            atLMinusOne.outcome == FixedExternalTransportAdmissionOutcome::
                CASE2_TRANSPORT_PROVABLE,
            "L-1 must remain Case 2 transport-provable");
    const auto atL = swappy::classifyFixedExternalTransportAdmission(
        inputAt(l), q);
    require(atL.valid && !atL.claimMayBeIssued &&
            atL.rawOpportunityDisposed &&
            atL.outcome == FixedExternalTransportAdmissionOutcome::
                SLOT_CLOSED_NO_ATTEMPT,
            "L must close without claim");

    const auto frozenAtGate =
        swappy::classifyFixedExternalTransportAdmissionAtDecision(
            defer, defer.case2GateNanos);
    require(frozenAtGate.valid && frozenAtGate.claimMayBeIssued &&
            frozenAtGate.case2GateNanos == defer.case2GateNanos &&
            frozenAtGate.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    CASE2_TRANSPORT_PROVABLE,
            "frozen opportunity moved its Case 2 gate at wake");
    const auto frozenClosed =
        swappy::classifyFixedExternalTransportAdmissionAtDecision(
            defer, defer.case2LatestStartExclusiveNanos);
    require(frozenClosed.valid && !frozenClosed.claimMayBeIssued &&
            frozenClosed.rawOpportunityDisposed &&
            frozenClosed.case2GateNanos == defer.case2GateNanos &&
            frozenClosed.outcome ==
                FixedExternalTransportAdmissionOutcome::
                    SLOT_CLOSED_NO_ATTEMPT,
            "late wake retargeted the prepared frame to a later refresh");

    require(!swappy::classifyFixedExternalTransportAdmission(
                inputAt(g2), q + 1).valid,
            "B=Q+1 must be invalid profile");
    FixedPhasePlanInput overflow{
        t, a, d,
        std::numeric_limits<std::int64_t>::max() - 2 * t + a,
        100,
        std::numeric_limits<std::int64_t>::max() - q + 1,
    };
    require(!swappy::classifyFixedExternalTransportAdmission(
                overflow, q).valid,
            "d+B overflow must be invalid input");

    const std::vector<swappy::FixedFrameTimelineTuple> timelines{
        {41, p1, p1 - d},
        {42, p2, p2 - d},
    };
    swappy::FixedFrameTimelineTuple selected{};
    require(swappy::selectExactFixedFrameTimeline(
                timelines, p1, d, &selected) && selected.vsyncId == 41,
            "Case 1 did not bind its exact platform timeline");
    require(swappy::selectExactFixedFrameTimeline(
                timelines, p2, d, &selected) && selected.vsyncId == 42,
            "Case 2 reused the preferred Case-1 timeline");
    const std::vector<swappy::FixedFrameTimelineTuple> duplicate{
        {42, p2, p2 - d},
        {43, p2, p2 - d},
    };
    require(!swappy::selectExactFixedFrameTimeline(
                duplicate, p2, d, &selected),
            "ambiguous target timeline was accepted");

    require(swappy::fixedFrameTimelineWindowClosedBeforePlan(
                timelines, p2 + t, d),
            "fully elapsed platform window was not classified closed");
    require(!swappy::fixedFrameTimelineWindowClosedBeforePlan(
                timelines, p2, d),
            "window containing the target was classified closed");
    require(!swappy::fixedFrameTimelineWindowClosedBeforePlan(
                duplicate, p2 + t, d),
            "ambiguous platform authority was downgraded to closed");

    std::cout << "PASS FixedExternalTransportAdmissionTest schema11 17/17\n";
    return 0;
}
