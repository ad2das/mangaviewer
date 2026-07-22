#pragma once

#include <array>
#include <cstdint>

namespace ntk::detached_warm {

struct Snapshot {
    bool renderPbuffer = false;
    bool uploadPbuffer = false;
    bool program = false;
    bool warmReady = false;
    std::uint64_t nativeWindowCount = 0;
    std::uint64_t presentBackendAttachCount = 0;
    std::uint64_t authority = 0;
    std::uint64_t frameIdCount = 0;
    std::uint64_t swapCount = 0;
};

constexpr bool exactWarm(const Snapshot& value) {
    return value.renderPbuffer && value.uploadPbuffer && value.program &&
        value.warmReady && value.nativeWindowCount == 0 &&
        value.presentBackendAttachCount == 0 && value.authority == 0 &&
        value.frameIdCount == 0 && value.swapCount == 0;
}

constexpr bool attachAllowed(const Snapshot& value) {
    return exactWarm(value);
}

constexpr bool noAuthorityNoFrameOrSwap(const Snapshot& value) {
    return value.authority != 0 ||
        (value.frameIdCount == 0 && value.swapCount == 0);
}

constexpr bool startupClockOrder(const std::array<std::int64_t, 9>& clocks) {
    if (clocks[0] <= 0) return false;
    for (std::size_t index = 1; index < clocks.size(); ++index) {
        if (clocks[index] <= 0 || clocks[index] < clocks[index - 1]) return false;
    }
    return true;
}

}  // namespace ntk::detached_warm
