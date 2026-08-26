#pragma once

#include <cstddef>
#include <cstdint>

namespace ntk::release_history {

enum class TrackerState : std::uint8_t {
    ABSENT = 0,
    BOUND = 1,
    RELEASING_UNCLAIMED = 2,
    RELEASING_CLAIMED = 3,
    RELEASED = 4,
    FAILED = 5,
};

enum class ContextLossSelection : std::int8_t {
    FAIL = -1,
    EXCLUDE_HISTORICAL_RELEASED = 0,
    INCLUDE = 1,
};

inline bool terminal(TrackerState state) noexcept {
    return state == TrackerState::RELEASED || state == TrackerState::FAILED;
}

/** Admission is exact after history reclamation: an inactive absent key is never revived. */
inline bool releaseClaimAllowed(
        bool engineIdentityMatches,
        bool activeIdentityMatches,
        bool pendingClaim,
        TrackerState state,
        bool trackerHasClaim) noexcept {
    if (!engineIdentityMatches || pendingClaim) return false;
    if (state == TrackerState::ABSENT) return activeIdentityMatches;
    return state == TrackerState::RELEASING_UNCLAIMED && !trackerHasClaim;
}

/** A tracker, when present, is authoritative over the active-key fallback. */
inline bool callbackAllowed(
        bool engineIdentityMatches,
        bool activeIdentityMatches,
        bool pendingClaim,
        TrackerState state) noexcept {
    if (!engineIdentityMatches) return false;
    if (state != TrackerState::ABSENT) return !terminal(state);
    return pendingClaim || activeIdentityMatches;
}

/** RELEASED proof history remains pinned only while its exact resource worker owns it. */
inline bool reclaimable(TrackerState state, bool resourceWorkerOwns) noexcept {
    return state == TrackerState::RELEASED && !resourceWorkerOwns;
}

/** Selected handoff tokens always survive; only unselected RELEASED history is excludable. */
inline ContextLossSelection contextLossSelection(
        bool selected,
        TrackerState state) noexcept {
    if (selected) return ContextLossSelection::INCLUDE;
    return state == TrackerState::RELEASED
        ? ContextLossSelection::EXCLUDE_HISTORICAL_RELEASED
        : ContextLossSelection::FAIL;
}

/** Normal sequential churn retains unresolved trackers plus at most one deferred terminal. */
inline bool normalChurnBound(
        std::size_t trackerCount,
        std::size_t unresolvedCount) noexcept {
    return trackerCount <= unresolvedCount + 1U;
}

}  // namespace ntk::release_history
