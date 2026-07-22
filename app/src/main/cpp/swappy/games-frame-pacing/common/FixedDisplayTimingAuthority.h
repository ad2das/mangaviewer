#pragma once

#include <cstdint>

namespace swappy {

// Display.getPresentationDeadlineNanos() includes the platform's historical
// 1 ms compositor allowance.  AChoreographer FrameTimeline deadlines expose
// the actual expected-presentation-to-deadline interval, which is the exact D
// consumed by fixed phase planning and SurfaceControl frame-timeline binding.
inline std::int64_t fixedFrameTimelineDeadlineFromDisplay(
        std::int64_t displayPresentationDeadlineNanos) noexcept {
    constexpr std::int64_t kPlatformCompositorAllowanceNanos = 1'000'000;
    return displayPresentationDeadlineNanos >
            kPlatformCompositorAllowanceNanos
        ? displayPresentationDeadlineNanos -
            kPlatformCompositorAllowanceNanos
        : 0;
}

}  // namespace swappy
