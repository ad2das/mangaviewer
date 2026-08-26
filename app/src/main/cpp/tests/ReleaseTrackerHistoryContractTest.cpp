#include "../ReleaseTrackerHistoryContract.h"

#include <cstdio>
#include <cstdint>
#include <map>
#include <optional>

namespace {

using ntk::release_history::ContextLossSelection;
using ntk::release_history::TrackerState;

struct Tracker {
    TrackerState state = TrackerState::RELEASING_UNCLAIMED;
    bool hasClaim = false;
};

bool expect(bool value, const char* message) {
    if (value) return true;
    std::fprintf(stderr, "ReleaseTrackerHistoryContractTest: %s\n", message);
    return false;
}

std::size_t unresolvedCount(const std::map<std::int64_t, Tracker>& trackers) {
    std::size_t unresolved = 0;
    for (const auto& entry : trackers) {
        if (entry.second.state == TrackerState::RELEASING_UNCLAIMED ||
            entry.second.state == TrackerState::RELEASING_CLAIMED) {
            ++unresolved;
        }
    }
    return unresolved;
}

std::size_t purgeReleased(
        std::map<std::int64_t, Tracker>& trackers,
        std::optional<std::int64_t> workerOwner) {
    std::size_t purged = 0;
    for (auto tracker = trackers.begin(); tracker != trackers.end();) {
        if (ntk::release_history::reclaimable(
                tracker->second.state,
                workerOwner.has_value() && *workerOwner == tracker->first)) {
            tracker = trackers.erase(tracker);
            ++purged;
        } else {
            ++tracker;
        }
    }
    return purged;
}

}  // namespace

int main() {
    std::map<std::int64_t, Tracker> trackers;
    std::optional<std::int64_t> workerOwner;
    std::size_t maxTrackerCount = 0;

    // Normal production order: claim, physical completion/ACK, then successor commit. The
    // deferred terminal is retained until that commit, so history is unresolved + one at most.
    for (std::int64_t generation = 1; generation <= 100; ++generation) {
        const bool claimAllowed = ntk::release_history::releaseClaimAllowed(
            true, true, false, TrackerState::ABSENT, false);
        if (!expect(claimAllowed, "active authority claim was rejected")) return 1;
        trackers.emplace(generation, Tracker{TrackerState::RELEASING_CLAIMED, true});
        if (!expect(ntk::release_history::callbackAllowed(
                        true, false, false, TrackerState::RELEASING_CLAIMED),
                    "claimed predecessor callback was rejected") ||
            !expect(ntk::release_history::normalChurnBound(
                        trackers.size(), unresolvedCount(trackers)),
                    "claimed churn exceeded unresolved plus one")) {
            return 2;
        }

        workerOwner = generation;
        trackers.at(generation).state = TrackerState::RELEASED;
        maxTrackerCount = trackers.size() > maxTrackerCount
            ? trackers.size() : maxTrackerCount;
        if (!expect(!ntk::release_history::releaseClaimAllowed(
                        true, false, false, TrackerState::RELEASED, true),
                    "duplicate released claim was accepted") ||
            !expect(!ntk::release_history::callbackAllowed(
                        true, false, false, TrackerState::RELEASED),
                    "late released callback was accepted") ||
            !expect(ntk::release_history::normalChurnBound(
                        trackers.size(), unresolvedCount(trackers)),
                    "terminal churn exceeded unresolved plus one") ||
            !expect(purgeReleased(trackers, workerOwner) == 0,
                    "live resource-worker owner was purged")) {
            return 3;
        }

        if (generation < 100) {
            // prepare_resource_worker_for_bind retires the predecessor before native commit;
            // the successor worker is therefore the only owner when production purges history.
            workerOwner = generation + 1;
            if (!expect(purgeReleased(trackers, workerOwner) == 1,
                        "successor commit did not purge terminal predecessor") ||
                !expect(trackers.empty(), "successor commit retained history")) {
                return 4;
            }
        }
    }

    if (!expect(maxTrackerCount == 1, "100-authority churn was not constant") ||
        !expect(trackers.size() == 1, "last terminal was not deferred") ||
        !expect(!ntk::release_history::releaseClaimAllowed(
                    true, false, false, TrackerState::ABSENT, false),
                "purged inactive authority was revived")) {
        return 5;
    }

    // Reusable detach may reclaim only after the exact worker owner has gone away.
    if (!expect(purgeReleased(trackers, workerOwner) == 0,
                "reusable detach purged a live worker owner")) {
        return 6;
    }
    workerOwner.reset();
    if (!expect(purgeReleased(trackers, workerOwner) == 1 && trackers.empty(),
                "reusable detach did not purge unowned terminal history")) {
        return 7;
    }

    // A late claim for an older physical-complete UNCLAIMED tracker remains valid regardless of
    // a newer active authority; no scalar generation watermark may reject this exact key.
    if (!expect(ntk::release_history::releaseClaimAllowed(
                    true, false, false, TrackerState::RELEASING_UNCLAIMED, false),
                "out-of-order old unclaimed release was rejected") ||
        !expect(!ntk::release_history::releaseClaimAllowed(
                    true, false, true, TrackerState::RELEASING_UNCLAIMED, false),
                "duplicate pending old release was accepted")) {
        return 8;
    }

    // Context loss is a distinct safe barrier: an exact selected RELEASED proof is included,
    // while only an unselected historical RELEASED proof may be excluded.
    if (!expect(ntk::release_history::contextLossSelection(
                    true, TrackerState::RELEASED) == ContextLossSelection::INCLUDE,
                "selected released context-loss proof was discarded") ||
        !expect(ntk::release_history::contextLossSelection(
                    false, TrackerState::RELEASED) ==
                    ContextLossSelection::EXCLUDE_HISTORICAL_RELEASED,
                "unselected released history was not excludable") ||
        !expect(ntk::release_history::contextLossSelection(
                    false, TrackerState::RELEASING_UNCLAIMED) ==
                    ContextLossSelection::FAIL,
                "unrepresented unresolved context-loss tracker was ignored")) {
        return 9;
    }

    std::puts("ReleaseTrackerHistoryContractTest PASS");
    return 0;
}
