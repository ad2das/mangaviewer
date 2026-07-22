#pragma once

#include <cstdint>

namespace swappy {

enum class FrameCompletionResult : std::uint8_t {
    COMPLETE,
    FAILED,
};

}  // namespace swappy
