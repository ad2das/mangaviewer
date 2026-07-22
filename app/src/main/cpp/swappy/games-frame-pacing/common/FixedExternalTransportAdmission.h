#pragma once

#include "FixedNonPipelinePhase.h"

#include <algorithm>
#include <cstdint>

namespace swappy {

enum class FixedExternalTransportAdmissionOutcome : std::int32_t {
    CASE1_TRANSPORT_PROVABLE = 0,
    DEFER_TO_CASE2_GATE = 1,
    CASE2_TRANSPORT_PROVABLE = 2,
    SLOT_CLOSED_NO_ATTEMPT = 3,
    FATAL_INVALID_INPUT = 4,
};

struct FixedExternalTransportAdmission {
    FixedExternalTransportAdmissionOutcome outcome =
        FixedExternalTransportAdmissionOutcome::FATAL_INVALID_INPUT;
    bool valid = false;
    bool rawOpportunityDisposed = false;
    bool claimMayBeIssued = false;
    std::int64_t transportBoundNanos = 0;
    std::int64_t earliestCutoffNanos = 0;
    std::int64_t case2PhaseOpenNanos = 0;
    std::int64_t case2GateNanos = 0;
    std::int64_t case2CutoffNanos = 0;
    std::int64_t case2LatestStartExclusiveNanos = 0;
    std::int64_t case1LatestSafeDecisionNanos = 0;
};

inline FixedExternalTransportAdmission
classifyFixedExternalTransportAdmissionAtDecision(
        const FixedExternalTransportAdmission& frozenOpportunity,
        std::int64_t decisionNanos) noexcept {
    FixedExternalTransportAdmission result = frozenOpportunity;
    result.outcome =
        FixedExternalTransportAdmissionOutcome::FATAL_INVALID_INPUT;
    result.rawOpportunityDisposed = false;
    result.claimMayBeIssued = false;
    const __int128 decisionEndWide =
        static_cast<__int128>(decisionNanos) +
        frozenOpportunity.transportBoundNanos;
    if (!frozenOpportunity.valid || decisionNanos <= 0 ||
        frozenOpportunity.transportBoundNanos <= 0 ||
        frozenOpportunity.earliestCutoffNanos <= 0 ||
        frozenOpportunity.case2GateNanos <= 0 ||
        frozenOpportunity.case2LatestStartExclusiveNanos <=
            frozenOpportunity.case2GateNanos ||
        !fixed_phase_detail::inI64(decisionEndWide)) {
        result.valid = false;
        return result;
    }

    if (decisionNanos >= result.case2LatestStartExclusiveNanos) {
        result.outcome =
            FixedExternalTransportAdmissionOutcome::SLOT_CLOSED_NO_ATTEMPT;
        result.rawOpportunityDisposed = true;
        return result;
    }
    if (decisionNanos < result.earliestCutoffNanos &&
        decisionEndWide <= result.earliestCutoffNanos) {
        result.outcome = FixedExternalTransportAdmissionOutcome::
            CASE1_TRANSPORT_PROVABLE;
        result.claimMayBeIssued = true;
        return result;
    }
    if (decisionNanos < result.case2GateNanos) {
        result.outcome =
            FixedExternalTransportAdmissionOutcome::DEFER_TO_CASE2_GATE;
        return result;
    }
    result.outcome =
        FixedExternalTransportAdmissionOutcome::CASE2_TRANSPORT_PROVABLE;
    result.claimMayBeIssued = true;
    return result;
}

inline FixedExternalTransportAdmission classifyFixedExternalTransportAdmission(
        const FixedPhasePlanInput& input,
        std::int64_t transportBoundNanos) noexcept {
    FixedExternalTransportAdmission result;
    const FixedPhaseOpportunityGeometry geometry =
        computeFixedPhaseOpportunityGeometry(input);
    const std::int64_t expectedBound = input.refreshPeriodNanos / 2;
    if (!geometry.valid || expectedBound <= 0 ||
        transportBoundNanos != expectedBound ||
        geometry.missPhaseOpenNanos >=
            geometry.missLatestStartExclusiveNanos) {
        return result;
    }

    const __int128 case2CutoffWide =
        static_cast<__int128>(geometry.missPresentationNanos) -
        input.presentationDeadlineNanos;
    const __int128 case1LatestSafeWide =
        static_cast<__int128>(geometry.earliestCutoffNanos) -
        transportBoundNanos;
    if (!fixed_phase_detail::assignI64(
            case2CutoffWide, &result.case2CutoffNanos) ||
        !fixed_phase_detail::assignI64(
            case1LatestSafeWide, &result.case1LatestSafeDecisionNanos)) {
        return result;
    }

    result.valid = true;
    result.transportBoundNanos = transportBoundNanos;
    result.earliestCutoffNanos = geometry.earliestCutoffNanos;
    result.case2PhaseOpenNanos = geometry.missPhaseOpenNanos;
    result.case2GateNanos = std::max(
        geometry.earliestCutoffNanos, geometry.missPhaseOpenNanos);
    result.case2LatestStartExclusiveNanos =
        geometry.missLatestStartExclusiveNanos;
    return classifyFixedExternalTransportAdmissionAtDecision(
        result, input.decisionNanos);
}

}  // namespace swappy
