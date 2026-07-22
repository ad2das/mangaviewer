/*
 * Copyright 2026
 *
 * Local MangaViewer fixed-90 phase planner.  This header is intentionally
 * deterministic and side-effect free: production pacing and native fake-clock
 * tests must call the same function rather than duplicate the arithmetic.
 */

#pragma once

#include <cstdint>
#include <limits>

namespace swappy {

enum class FixedPhasePlanOutcome : std::int32_t {
    BEFORE_CUTOFF = 0,
    PROVEN_MISS = 1,
    FATAL_INVALID_INPUT = 2,
    FATAL_EMPTY_PHASE_WINDOW = 3,
    FATAL_DECISION_TOO_LATE = 4,
};

struct FixedPhasePlanInput {
    std::int64_t refreshPeriodNanos = 0;
    std::int64_t appVsyncOffsetNanos = 0;
    std::int64_t presentationDeadlineNanos = 0;
    std::int64_t acceptedFrameTimeNanos = 0;
    std::int64_t acceptedFrameIndex = 0;
    std::int64_t decisionNanos = 0;
};

// Pure geometry shared by the exact Oracle and the pre-attempt admission
// classifier.  In particular, missLatestStartExclusiveNanos is the strict
// runtime pre-swap limit L.  A caller that has not yet created a frame attempt
// must close decision >= L without invoking the Oracle; the Oracle itself keeps
// its historical decision > L fatal boundary bit-for-bit.
struct FixedPhaseOpportunityGeometry {
    bool valid = false;
    std::int64_t physicalRefreshNanos = 0;
    std::int64_t earliestPresentationNanos = 0;
    std::int64_t earliestCutoffNanos = 0;
    std::int64_t missPresentationNanos = 0;
    std::int64_t missPhaseOpenNanos = 0;
    std::int64_t missLatestStartExclusiveNanos = 0;
};

enum class FixedPhaseOpportunityAdmission : std::int32_t {
    OPEN = 0,
    SLOT_CLOSED_NO_ATTEMPT = 1,
    FATAL_INVALID_GEOMETRY = 2,
};

struct FixedPhasePlan {
    FixedPhasePlanOutcome outcome =
        FixedPhasePlanOutcome::FATAL_INVALID_INPUT;
    bool valid = false;
    bool phaseMissProven = false;
    bool absoluteWaitRequired = false;

    std::int64_t horizonNanos = 0;
    std::int64_t submitFeasibilityNanos = 0;
    std::int64_t physicalRefreshNanos = 0;
    std::int64_t earliestPresentationNanos = 0;
    std::int64_t earliestCutoffNanos = 0;
    std::int64_t missedPresentationNanos = 0;
    std::int64_t plannedPresentationNanos = 0;
    std::int64_t plannedCutoffNanos = 0;
    std::int64_t phaseOpenNanos = 0;
    std::int64_t latestSwapStartExclusiveNanos = 0;
    std::int64_t phaseWaitNanos = 0;
    std::int64_t plannedTargetFrame = 0;
};

enum class FixedPhaseRuntimeOutcome : std::int32_t {
    READY = 0,
    FATAL_LATE_WAKE = 1,
    FATAL_NON_POSITIVE_SWAP = 2,
    FATAL_SWAP_EXCEEDS_FEASIBILITY = 3,
    FATAL_SWAP_MISSED_CUTOFF = 4,
};

struct FixedPhaseRuntimeValidation {
    FixedPhaseRuntimeOutcome outcome =
        FixedPhaseRuntimeOutcome::FATAL_LATE_WAKE;
    bool valid = false;
    std::int64_t swapDurationNanos = 0;
};

namespace fixed_phase_detail {

inline bool inI64(__int128 value) noexcept {
    return value >= std::numeric_limits<std::int64_t>::min() &&
           value <= std::numeric_limits<std::int64_t>::max();
}

inline bool assignI64(__int128 value, std::int64_t* out) noexcept {
    if (!out || !inI64(value)) return false;
    *out = static_cast<std::int64_t>(value);
    return true;
}

}  // namespace fixed_phase_detail

inline FixedPhaseOpportunityGeometry computeFixedPhaseOpportunityGeometry(
    const FixedPhasePlanInput& input) noexcept {
    FixedPhaseOpportunityGeometry geometry;

    const std::int64_t tPeriod = input.refreshPeriodNanos;
    const std::int64_t appOffset = input.appVsyncOffsetNanos;
    const std::int64_t deadline = input.presentationDeadlineNanos;
    if (tPeriod <= 0 || appOffset < 0 || appOffset >= tPeriod ||
        deadline <= 0 || deadline >= tPeriod ||
        input.acceptedFrameTimeNanos <= 0 || input.acceptedFrameIndex <= 0 ||
        input.decisionNanos <= 0) {
        return geometry;
    }

    // Preserve the exact Oracle constants H=floor(3T/2), Q=floor(T/2).
    const std::int64_t horizon = tPeriod + tPeriod / 2;
    const std::int64_t feasibility = tPeriod / 2;
    if (feasibility <= 0 || deadline > horizon - feasibility) return geometry;

    const __int128 refresh =
        static_cast<__int128>(input.acceptedFrameTimeNanos) - appOffset;
    if (!fixed_phase_detail::assignI64(
            refresh, &geometry.physicalRefreshNanos)) {
        return geometry;
    }

    // P1 is the smallest R+nT, n>=1, strictly after decision.
    __int128 n = 1;
    if (static_cast<__int128>(input.decisionNanos) >= refresh) {
        n = (static_cast<__int128>(input.decisionNanos) - refresh) /
                tPeriod +
            1;
    }
    const __int128 earliest = refresh + n * tPeriod;
    const __int128 earliestCutoff = earliest - deadline;
    const __int128 missPresentation = earliest + tPeriod;
    const __int128 missPhaseOpen = missPresentation - horizon;
    const __int128 missLatestStart =
        missPresentation - deadline - feasibility;
    if (!fixed_phase_detail::assignI64(
            earliest, &geometry.earliestPresentationNanos) ||
        !fixed_phase_detail::assignI64(
            earliestCutoff, &geometry.earliestCutoffNanos) ||
        !fixed_phase_detail::assignI64(
            missPresentation, &geometry.missPresentationNanos) ||
        !fixed_phase_detail::assignI64(
            missPhaseOpen, &geometry.missPhaseOpenNanos) ||
        !fixed_phase_detail::assignI64(
            missLatestStart,
            &geometry.missLatestStartExclusiveNanos)) {
        return geometry;
    }
    geometry.valid = true;
    return geometry;
}

inline FixedPhaseOpportunityAdmission classifyFixedPhaseOpportunity(
    const FixedPhasePlanInput& input) noexcept {
    const FixedPhaseOpportunityGeometry geometry =
        computeFixedPhaseOpportunityGeometry(input);
    if (!geometry.valid ||
        geometry.missPhaseOpenNanos >=
            geometry.missLatestStartExclusiveNanos) {
        return FixedPhaseOpportunityAdmission::FATAL_INVALID_GEOMETRY;
    }
    return input.decisionNanos >= geometry.missLatestStartExclusiveNanos
        ? FixedPhaseOpportunityAdmission::SLOT_CLOSED_NO_ATTEMPT
        : FixedPhaseOpportunityAdmission::OPEN;
}

inline FixedPhasePlan planFixedNonPipelinePhase(
    const FixedPhasePlanInput& input) noexcept {
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

    // H=floor(3T/2), Q=floor(T/2).  The addition form avoids 3*T overflow.
    const std::int64_t horizon = tPeriod + tPeriod / 2;
    const std::int64_t feasibility = tPeriod / 2;
    if (feasibility <= 0 || deadline > horizon - feasibility) return plan;
    plan.horizonNanos = horizon;
    plan.submitFeasibilityNanos = feasibility;

    const FixedPhaseOpportunityGeometry geometry =
        computeFixedPhaseOpportunityGeometry(input);
    if (!geometry.valid) return plan;
    plan.physicalRefreshNanos = geometry.physicalRefreshNanos;
    plan.earliestPresentationNanos =
        geometry.earliestPresentationNanos;
    plan.earliestCutoffNanos = geometry.earliestCutoffNanos;

    const __int128 refresh = geometry.physicalRefreshNanos;
    const __int128 earliest = geometry.earliestPresentationNanos;
    const __int128 cutoff = geometry.earliestCutoffNanos;
    const __int128 n = (earliest - refresh) / tPeriod;
    const __int128 target =
        static_cast<__int128>(input.acceptedFrameIndex) + n;
    if (!fixed_phase_detail::assignI64(target, &plan.plannedTargetFrame)) {
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
    const __int128 planned = geometry.missPresentationNanos;
    const __int128 plannedCutoff = planned - deadline;
    const __int128 phaseOpen = geometry.missPhaseOpenNanos;
    const __int128 latestStart =
        geometry.missLatestStartExclusiveNanos;
    const __int128 missTarget = target + 1;
    if (!fixed_phase_detail::assignI64(
            planned, &plan.plannedPresentationNanos) ||
        !fixed_phase_detail::assignI64(
            plannedCutoff, &plan.plannedCutoffNanos) ||
        !fixed_phase_detail::assignI64(phaseOpen, &plan.phaseOpenNanos) ||
        !fixed_phase_detail::assignI64(
            latestStart, &plan.latestSwapStartExclusiveNanos) ||
        !fixed_phase_detail::assignI64(
            missTarget, &plan.plannedTargetFrame)) {
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
        if (!fixed_phase_detail::assignI64(
                phaseOpen - input.decisionNanos, &plan.phaseWaitNanos)) {
            return plan;
        }
    }
    plan.outcome = FixedPhasePlanOutcome::PROVEN_MISS;
    plan.valid = true;
    return plan;
}

inline FixedPhaseRuntimeValidation validateFixedNonPipelinePreSwap(
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

inline FixedPhaseRuntimeValidation validateFixedNonPipelinePostSwap(
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
        result.outcome =
            FixedPhaseRuntimeOutcome::FATAL_SWAP_MISSED_CUTOFF;
        return result;
    }
    result.outcome = FixedPhaseRuntimeOutcome::READY;
    result.valid = true;
    return result;
}

}  // namespace swappy
