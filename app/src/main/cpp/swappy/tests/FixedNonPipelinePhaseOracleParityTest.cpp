#include "../games-frame-pacing/common/FixedNonPipelinePhase.h"

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>

namespace {

using swappy::FixedPhasePlan;
using swappy::FixedPhasePlanInput;
using swappy::FixedPhasePlanOutcome;
using swappy::FixedPhaseRuntimeOutcome;
using swappy::FixedPhaseRuntimeValidation;

[[noreturn]] void fail(const char* message) {
    std::fprintf(stderr, "FixedNonPipelinePhaseOracleParityTest: %s\n",
                 message);
    std::abort();
}

void require(bool condition, const char* message) {
    if (!condition) fail(message);
}

// Keep checked conversion local to the frozen reference.  Calling production
// fixed_phase_detail helpers here would let a shared arithmetic regression
// change the implementation and its alleged Oracle in lockstep.
bool referenceInI64(__int128 value) noexcept {
    return value >= std::numeric_limits<std::int64_t>::min() &&
        value <= std::numeric_limits<std::int64_t>::max();
}

bool referenceAssignI64(__int128 value, std::int64_t* out) noexcept {
    if (out == nullptr || !referenceInI64(value)) return false;
    *out = static_cast<std::int64_t>(value);
    return true;
}

// Frozen copy of the pre-admission exact Oracle.  This is deliberately kept in
// the test so extraction of shared opportunity geometry cannot silently alter
// any output bit or fatal boundary.
FixedPhasePlan referencePlan(const FixedPhasePlanInput& input) noexcept {
    FixedPhasePlan plan;
    const std::int64_t tPeriod = input.refreshPeriodNanos;
    const std::int64_t appOffset = input.appVsyncOffsetNanos;
    const std::int64_t deadline = input.presentationDeadlineNanos;
    if (tPeriod <= 0 || appOffset < 0 || appOffset >= tPeriod ||
        deadline <= 0 || deadline >= tPeriod ||
        input.acceptedFrameTimeNanos <= 0 || input.acceptedFrameIndex <= 0 ||
        input.decisionNanos <= 0) {
        return plan;
    }
    const std::int64_t horizon = tPeriod + tPeriod / 2;
    const std::int64_t feasibility = tPeriod / 2;
    if (feasibility <= 0 || deadline > horizon - feasibility) return plan;
    plan.horizonNanos = horizon;
    plan.submitFeasibilityNanos = feasibility;

    const __int128 refresh =
        static_cast<__int128>(input.acceptedFrameTimeNanos) - appOffset;
    if (!referenceInI64(refresh)) return plan;
    plan.physicalRefreshNanos = static_cast<std::int64_t>(refresh);
    __int128 n = 1;
    if (static_cast<__int128>(input.decisionNanos) >= refresh) {
        n = (static_cast<__int128>(input.decisionNanos) - refresh) /
                tPeriod +
            1;
    }
    const __int128 earliest = refresh + n * tPeriod;
    const __int128 cutoff = earliest - deadline;
    const __int128 target =
        static_cast<__int128>(input.acceptedFrameIndex) + n;
    if (!referenceAssignI64(earliest, &plan.earliestPresentationNanos) ||
        !referenceAssignI64(cutoff, &plan.earliestCutoffNanos) ||
        !referenceAssignI64(target, &plan.plannedTargetFrame)) {
        return plan;
    }
    if (static_cast<__int128>(input.decisionNanos) < cutoff) {
        plan.outcome = FixedPhasePlanOutcome::BEFORE_CUTOFF;
        plan.valid = true;
        plan.plannedPresentationNanos = plan.earliestPresentationNanos;
        plan.plannedCutoffNanos = plan.earliestCutoffNanos;
        return plan;
    }

    plan.phaseMissProven = true;
    plan.missedPresentationNanos = plan.earliestPresentationNanos;
    const __int128 planned = earliest + tPeriod;
    const __int128 plannedCutoff = planned - deadline;
    const __int128 phaseOpen = planned - horizon;
    const __int128 latestStart = planned - deadline - feasibility;
    const __int128 missTarget = target + 1;
    if (!referenceAssignI64(planned, &plan.plannedPresentationNanos) ||
        !referenceAssignI64(plannedCutoff, &plan.plannedCutoffNanos) ||
        !referenceAssignI64(phaseOpen, &plan.phaseOpenNanos) ||
        !referenceAssignI64(
            latestStart, &plan.latestSwapStartExclusiveNanos) ||
        !referenceAssignI64(missTarget, &plan.plannedTargetFrame)) {
        return plan;
    }
    if (phaseOpen >= latestStart) {
        plan.outcome = FixedPhasePlanOutcome::FATAL_EMPTY_PHASE_WINDOW;
        return plan;
    }
    if (static_cast<__int128>(input.decisionNanos) > latestStart) {
        plan.outcome = FixedPhasePlanOutcome::FATAL_DECISION_TOO_LATE;
        return plan;
    }
    if (static_cast<__int128>(input.decisionNanos) < phaseOpen) {
        plan.absoluteWaitRequired = true;
        if (!referenceAssignI64(
                phaseOpen - input.decisionNanos, &plan.phaseWaitNanos)) {
            return plan;
        }
    }
    plan.outcome = FixedPhasePlanOutcome::PROVEN_MISS;
    plan.valid = true;
    return plan;
}

bool equalPlan(const FixedPhasePlan& a, const FixedPhasePlan& b) {
    return a.outcome == b.outcome && a.valid == b.valid &&
        a.phaseMissProven == b.phaseMissProven &&
        a.absoluteWaitRequired == b.absoluteWaitRequired &&
        a.horizonNanos == b.horizonNanos &&
        a.submitFeasibilityNanos == b.submitFeasibilityNanos &&
        a.physicalRefreshNanos == b.physicalRefreshNanos &&
        a.earliestPresentationNanos == b.earliestPresentationNanos &&
        a.earliestCutoffNanos == b.earliestCutoffNanos &&
        a.missedPresentationNanos == b.missedPresentationNanos &&
        a.plannedPresentationNanos == b.plannedPresentationNanos &&
        a.plannedCutoffNanos == b.plannedCutoffNanos &&
        a.phaseOpenNanos == b.phaseOpenNanos &&
        a.latestSwapStartExclusiveNanos ==
            b.latestSwapStartExclusiveNanos &&
        a.phaseWaitNanos == b.phaseWaitNanos &&
        a.plannedTargetFrame == b.plannedTargetFrame;
}

// Frozen runtime validators are intentionally independent of the production
// inline functions.  Every observable result field is compared below.
FixedPhaseRuntimeValidation referencePreSwap(
    const FixedPhasePlan& plan, std::int64_t preSwapNanos) noexcept {
    FixedPhaseRuntimeValidation result;
    if (!plan.valid || preSwapNanos <= 0) return result;
    const std::int64_t exclusiveLimit = plan.phaseMissProven
        ? plan.latestSwapStartExclusiveNanos
        : plan.plannedCutoffNanos;
    if (preSwapNanos >= exclusiveLimit) return result;
    result.outcome = FixedPhaseRuntimeOutcome::READY;
    result.valid = true;
    return result;
}

FixedPhaseRuntimeValidation referencePostSwap(
    const FixedPhasePlan& plan, std::int64_t preSwapNanos,
    std::int64_t postSwapNanos) noexcept {
    FixedPhaseRuntimeValidation result;
    if (!plan.valid || preSwapNanos <= 0 || postSwapNanos <= preSwapNanos) {
        result.outcome = FixedPhaseRuntimeOutcome::FATAL_NON_POSITIVE_SWAP;
        return result;
    }
    result.swapDurationNanos = postSwapNanos - preSwapNanos;
    if (plan.phaseMissProven &&
        result.swapDurationNanos > plan.submitFeasibilityNanos) {
        result.outcome =
            FixedPhaseRuntimeOutcome::FATAL_SWAP_EXCEEDS_FEASIBILITY;
        return result;
    }
    const bool cutoffMissed = plan.phaseMissProven
        ? postSwapNanos >= plan.plannedCutoffNanos
        : postSwapNanos > plan.plannedCutoffNanos;
    if (cutoffMissed) {
        result.outcome = FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF;
        return result;
    }
    result.outcome = FixedPhaseRuntimeOutcome::READY;
    result.valid = true;
    return result;
}

bool equalRuntime(const FixedPhaseRuntimeValidation& a,
                  const FixedPhaseRuntimeValidation& b) {
    return a.outcome == b.outcome && a.valid == b.valid &&
        a.swapDurationNanos == b.swapDurationNanos;
}

void checkParity(const FixedPhasePlanInput& input) {
    const FixedPhasePlan expected = referencePlan(input);
    const FixedPhasePlan actual =
        swappy::planFixedNonPipelinePhase(input);
    require(equalPlan(expected, actual), "Oracle output changed");
}

void checkPreSwapParity(const FixedPhasePlan& plan,
                        std::int64_t preSwapNanos) {
    const FixedPhaseRuntimeValidation expected =
        referencePreSwap(plan, preSwapNanos);
    const FixedPhaseRuntimeValidation actual =
        swappy::validateFixedNonPipelinePreSwap(plan, preSwapNanos);
    require(equalRuntime(expected, actual),
            "runtime preSwap output bits changed");
}

void checkPostSwapParity(const FixedPhasePlan& plan,
                         std::int64_t preSwapNanos,
                         std::int64_t postSwapNanos) {
    const FixedPhaseRuntimeValidation expected =
        referencePostSwap(plan, preSwapNanos, postSwapNanos);
    const FixedPhaseRuntimeValidation actual =
        swappy::validateFixedNonPipelinePostSwap(
            plan, preSwapNanos, postSwapNanos);
    require(equalRuntime(expected, actual),
            "runtime postSwap output bits changed");
}

void checkGoldenRuntimeBoundaries(const FixedPhasePlan& plan) {
    if (!plan.valid) {
        checkPreSwapParity(plan, 1);
        checkPostSwapParity(plan, 1, 2);
        return;
    }
    const std::int64_t preLimit = plan.phaseMissProven
        ? plan.latestSwapStartExclusiveNanos
        : plan.plannedCutoffNanos;
    if (preLimit > 1) {
        checkPreSwapParity(plan, preLimit - 1);
        checkPreSwapParity(plan, preLimit);
    }
    if (plan.plannedCutoffNanos > 2) {
        checkPostSwapParity(
            plan, plan.plannedCutoffNanos - 2,
            plan.plannedCutoffNanos - 1);
        checkPostSwapParity(
            plan, plan.plannedCutoffNanos - 1,
            plan.plannedCutoffNanos);
    }
}

}  // namespace

int main() {
    constexpr std::int64_t t = 11'111'111;
    constexpr std::int64_t a = 2'000'000;
    constexpr std::int64_t d = 6'111'111;
    constexpr std::int64_t r = 1'000'000'000;
    constexpr std::int64_t p1 = r + t;
    constexpr std::int64_t c1 = p1 - d;
    constexpr std::int64_t p2 = p1 + t;
    constexpr std::int64_t phaseOpen = p2 - (t + t / 2);
    constexpr std::int64_t latestStart = p2 - d - t / 2;

    const auto inputAt = [](std::int64_t decision) {
        return FixedPhasePlanInput{t, a, d, r + a, 100, decision};
    };
    const std::array<std::int64_t, 10> decisions{
        r - 1, c1 - 1, c1, phaseOpen - 1, phaseOpen,
        latestStart - 1, latestStart, latestStart + 1,
        latestStart + 217'617, p1 + 1,
    };
    for (const std::int64_t decision : decisions) {
        const FixedPhasePlanInput input = inputAt(decision);
        checkParity(input);
        checkGoldenRuntimeBoundaries(referencePlan(input));
    }

    std::array<FixedPhasePlanInput, 8> invalid{
        inputAt(c1), inputAt(c1), inputAt(c1), inputAt(c1),
        inputAt(c1), inputAt(c1), inputAt(c1), inputAt(c1),
    };
    invalid[0].refreshPeriodNanos = 0;
    invalid[1].appVsyncOffsetNanos = -1;
    invalid[2].appVsyncOffsetNanos = t;
    invalid[3].presentationDeadlineNanos = 0;
    invalid[4].presentationDeadlineNanos = t;
    invalid[5].acceptedFrameTimeNanos = 0;
    invalid[6].acceptedFrameIndex = 0;
    invalid[7].decisionNanos = 0;
    for (const auto& input : invalid) {
        checkParity(input);
        checkGoldenRuntimeBoundaries(referencePlan(input));
    }

    // Broad deterministic parity sweep across periods, offsets, deadlines,
    // accepted authorities, indices, and decisions.  Values stay far from
    // signed overflow so every comparison has defined C++ behavior.
    std::uint64_t seed = 0x5a17d3c4b29eULL;
    const auto nextRandom = [&seed]() {
        seed = seed * 6364136223846793005ULL + 1442695040888963407ULL;
        return seed;
    };
    for (int i = 0; i < 50'000; ++i) {
        const std::int64_t period =
            1'000'001 + static_cast<std::int64_t>(nextRandom() % 30'000'000);
        const std::int64_t offset =
            static_cast<std::int64_t>(nextRandom() % period);
        const std::int64_t deadline =
            1 + static_cast<std::int64_t>(nextRandom() % (period - 1));
        const std::int64_t refresh =
            500'000'000 + static_cast<std::int64_t>(nextRandom() % 5'000'000'000ULL);
        const std::int64_t decision = refresh - period +
            static_cast<std::int64_t>(nextRandom() %
                static_cast<std::uint64_t>(period * 4));
        const FixedPhasePlanInput randomInput{
            period,
            offset,
            deadline,
            refresh + offset,
            1 + static_cast<std::int64_t>(nextRandom() % 1'000'000),
            decision > 0 ? decision : 1,
        };
        checkParity(randomInput);
        checkGoldenRuntimeBoundaries(referencePlan(randomInput));
    }

    const FixedPhasePlan case1 =
        swappy::planFixedNonPipelinePhase(inputAt(c1 - 1));
    require(case1.outcome == FixedPhasePlanOutcome::BEFORE_CUTOFF,
            "C1-1 must be Case 1");
    const FixedPhasePlan atC1 =
        swappy::planFixedNonPipelinePhase(inputAt(c1));
    require(atC1.outcome == FixedPhasePlanOutcome::PROVEN_MISS,
            "C1 must use Case 2 geometry");
    require(swappy::classifyFixedPhaseOpportunity(inputAt(latestStart - 1)) ==
                swappy::FixedPhaseOpportunityAdmission::OPEN,
            "L-1 must be admissible");
    require(swappy::classifyFixedPhaseOpportunity(inputAt(latestStart)) ==
                swappy::FixedPhaseOpportunityAdmission::
                    SLOT_CLOSED_NO_ATTEMPT,
            "L must close before an attempt");
    require(swappy::classifyFixedPhaseOpportunity(inputAt(latestStart + 1)) ==
                swappy::FixedPhaseOpportunityAdmission::
                    SLOT_CLOSED_NO_ATTEMPT,
            "L+1 must remain closed without an attempt");
    require(swappy::classifyFixedPhaseOpportunity(
                inputAt(latestStart + 217'617)) ==
                swappy::FixedPhaseOpportunityAdmission::
                    SLOT_CLOSED_NO_ATTEMPT,
            "observed 217617ns late decision must close without attempt");

    const FixedPhasePlan directAtL =
        swappy::planFixedNonPipelinePhase(inputAt(latestStart));
    require(directAtL.valid &&
                directAtL.outcome == FixedPhasePlanOutcome::PROVEN_MISS,
            "direct Oracle L boundary must remain bit-compatible");
    const FixedPhasePlan directAfterL =
        swappy::planFixedNonPipelinePhase(inputAt(latestStart + 1));
    require(!directAfterL.valid &&
                directAfterL.outcome ==
                    FixedPhasePlanOutcome::FATAL_DECISION_TOO_LATE,
            "direct Oracle L+1 must remain fatal");

    const auto preAtL = swappy::validateFixedNonPipelinePreSwap(
        directAtL, latestStart);
    checkPreSwapParity(directAtL, latestStart - 1);
    checkPreSwapParity(directAtL, latestStart);
    require(!preAtL.valid &&
                preAtL.outcome == FixedPhaseRuntimeOutcome::FATAL_LATE_WAKE,
            "runtime preSwap must keep strict pre<L");

    // Case 1 has an exclusive pre-swap cutoff but permits a swap completing
    // exactly at that cutoff.  Compare every result bit against the frozen
    // runtime reference on both sides of each boundary.
    checkPreSwapParity(case1, c1 - 1);
    checkPreSwapParity(case1, c1);
    const auto case1PostAtCutoff =
        swappy::validateFixedNonPipelinePostSwap(case1, c1 - 1, c1);
    checkPostSwapParity(case1, c1 - 1, c1);
    require(case1PostAtCutoff.valid &&
                case1PostAtCutoff.outcome == FixedPhaseRuntimeOutcome::READY &&
                case1PostAtCutoff.swapDurationNanos == 1,
            "Case 1 completion at cutoff must remain READY");
    const auto case1PostAfterCutoff =
        swappy::validateFixedNonPipelinePostSwap(case1, c1 - 1, c1 + 1);
    checkPostSwapParity(case1, c1 - 1, c1 + 1);
    require(!case1PostAfterCutoff.valid &&
                case1PostAfterCutoff.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF &&
                case1PostAfterCutoff.swapDurationNanos == 2,
            "Case 1 completion after cutoff must remain fatal");

    const auto zeroSwap = swappy::validateFixedNonPipelinePostSwap(
        atC1, phaseOpen, phaseOpen);
    checkPostSwapParity(atC1, phaseOpen, phaseOpen);
    require(!zeroSwap.valid &&
                zeroSwap.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_NON_POSITIVE_SWAP &&
                zeroSwap.swapDurationNanos == 0,
            "zero swap duration must remain fatal with zero duration output");
    const auto negativeSwap = swappy::validateFixedNonPipelinePostSwap(
        atC1, phaseOpen, phaseOpen - 1);
    checkPostSwapParity(atC1, phaseOpen, phaseOpen - 1);
    require(!negativeSwap.valid &&
                negativeSwap.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_NON_POSITIVE_SWAP &&
                negativeSwap.swapDurationNanos == 0,
            "negative swap duration must remain fatal with zero duration output");

    const auto postAtQ = swappy::validateFixedNonPipelinePostSwap(
        atC1, phaseOpen, phaseOpen + t / 2);
    checkPostSwapParity(atC1, phaseOpen, phaseOpen + t / 2);
    require(postAtQ.valid &&
                postAtQ.outcome == FixedPhaseRuntimeOutcome::READY &&
                postAtQ.swapDurationNanos == t / 2,
            "Case 2 swap duration Q must remain valid");
    const auto postAfterQ = swappy::validateFixedNonPipelinePostSwap(
        atC1, phaseOpen, phaseOpen + t / 2 + 1);
    checkPostSwapParity(atC1, phaseOpen, phaseOpen + t / 2 + 1);
    require(!postAfterQ.valid &&
                postAfterQ.outcome ==
                    FixedPhaseRuntimeOutcome::
                        FATAL_SWAP_EXCEEDS_FEASIBILITY &&
                postAfterQ.swapDurationNanos == t / 2 + 1,
            "Case 2 swap duration Q+1 must remain fatal");

    const auto postAtMissedCutoff =
        swappy::validateFixedNonPipelinePostSwap(
            atC1, atC1.plannedCutoffNanos - 1,
            atC1.plannedCutoffNanos);
    checkPostSwapParity(
        atC1, atC1.plannedCutoffNanos - 1,
        atC1.plannedCutoffNanos);
    require(!postAtMissedCutoff.valid &&
                postAtMissedCutoff.outcome ==
                    FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF &&
                postAtMissedCutoff.swapDurationNanos == 1,
            "Case 2 completion at cutoff must remain fatal");

    // Exact observed Case-1 cliff. This freezes the Oracle boundary that exposed the tracer
    // convoy: no padding or early rejection may turn a decision 77,886ns before C1 into a miss.
    constexpr FixedPhasePlanInput observedCliff{
        11'111'111,
        2'000'000,
        6'111'111,
        19'297'212'310'586,
        1,
        19'297'215'232'700,
    };
    constexpr std::int64_t observedCutoff = 19'297'215'310'586;
    const FixedPhasePlan observedPlan =
        swappy::planFixedNonPipelinePhase(observedCliff);
    checkParity(observedCliff);
    require(observedPlan.valid &&
                observedPlan.outcome == FixedPhasePlanOutcome::BEFORE_CUTOFF &&
                observedPlan.plannedCutoffNanos == observedCutoff &&
                observedCutoff - observedCliff.decisionNanos == 77'886,
            "observed C1-77886ns vector changed or gained padding");
    const auto observedReady = swappy::validateFixedNonPipelinePreSwap(
        observedPlan, observedCutoff - 1);
    const auto observedLate = swappy::validateFixedNonPipelinePreSwap(
        observedPlan, observedCutoff);
    checkPreSwapParity(observedPlan, observedCutoff - 1);
    checkPreSwapParity(observedPlan, observedCutoff);
    require(observedReady.valid &&
                observedReady.outcome == FixedPhaseRuntimeOutcome::READY &&
                !observedLate.valid &&
                observedLate.outcome == FixedPhaseRuntimeOutcome::FATAL_LATE_WAKE,
            "observed Case-1 strict preSwap boundary changed");

    std::puts("FixedNonPipelinePhaseOracleParityTest PASS");
    return 0;
}
