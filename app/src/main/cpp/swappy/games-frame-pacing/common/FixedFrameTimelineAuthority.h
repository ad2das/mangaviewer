#pragma once

#include <cstdint>
#include <vector>

namespace swappy {

// One tuple supplied by AChoreographerFrameCallbackData. The deadline is the
// absolute platform deadline for this exact expected-presentation/vsync ID.
struct FixedFrameTimelineTuple {
    std::int64_t vsyncId = 0;
    std::int64_t expectedPresentationNanos = 0;
    std::int64_t deadlineNanos = 0;
};

// A claim may bind only the unique platform tuple for the Oracle-selected
// presentation. Case 2 commonly selects a non-preferred tuple from the same
// physical callback, so checking only the preferred tuple rejects valid work.
inline bool selectExactFixedFrameTimeline(
        const std::vector<FixedFrameTimelineTuple>& timelines,
        std::int64_t plannedPresentationNanos,
        std::int64_t configuredPresentationDeadlineNanos,
        FixedFrameTimelineTuple* selected) noexcept {
    if (selected) *selected = {};
    if (!selected || plannedPresentationNanos <= 0 ||
        configuredPresentationDeadlineNanos <= 0) {
        return false;
    }
    std::uint32_t matches = 0;
    FixedFrameTimelineTuple exact{};
    for (const FixedFrameTimelineTuple& candidate : timelines) {
        if (candidate.vsyncId == 0 ||
            candidate.expectedPresentationNanos != plannedPresentationNanos ||
            candidate.deadlineNanos <= 0 ||
            candidate.expectedPresentationNanos <= candidate.deadlineNanos ||
            candidate.expectedPresentationNanos - candidate.deadlineNanos !=
                configuredPresentationDeadlineNanos) {
            continue;
        }
        exact = candidate;
        ++matches;
    }
    if (matches != 1) return false;
    *selected = exact;
    return true;
}

// A fully well-formed platform window can become stale while a prepared frame
// waits for the predecessor target.  That is a closed raw opportunity, not
// corrupt FrameTimeline authority.  Malformed, duplicate, or straddling
// windows deliberately return false so the caller keeps treating them as
// fatal authority failures.
inline bool fixedFrameTimelineWindowClosedBeforePlan(
        const std::vector<FixedFrameTimelineTuple>& timelines,
        std::int64_t plannedPresentationNanos,
        std::int64_t configuredPresentationDeadlineNanos) noexcept {
    if (timelines.empty() || plannedPresentationNanos <= 0 ||
        configuredPresentationDeadlineNanos <= 0) {
        return false;
    }
    std::int64_t latestExpected = 0;
    std::int64_t previousExpected = 0;
    std::int64_t previousVsyncId = 0;
    for (const FixedFrameTimelineTuple& candidate : timelines) {
        if (candidate.vsyncId == 0 ||
            candidate.expectedPresentationNanos <= 0 ||
            candidate.deadlineNanos <= 0 ||
            candidate.expectedPresentationNanos <= candidate.deadlineNanos ||
            candidate.expectedPresentationNanos - candidate.deadlineNanos !=
                configuredPresentationDeadlineNanos ||
            (previousExpected != 0 &&
             candidate.expectedPresentationNanos <= previousExpected) ||
            (previousVsyncId != 0 && candidate.vsyncId <= previousVsyncId)) {
            return false;
        }
        previousExpected = candidate.expectedPresentationNanos;
        previousVsyncId = candidate.vsyncId;
        latestExpected = candidate.expectedPresentationNanos;
    }
    return latestExpected < plannedPresentationNanos;
}

}  // namespace swappy
