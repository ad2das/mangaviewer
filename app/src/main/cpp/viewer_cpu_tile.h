#pragma once

#include <cstddef>
#include <cstdint>

struct ViewerCpuTileView final {
    const std::uint8_t* pixels;
    std::size_t byteCount;
};

bool viewerDescribeCpuTile(std::uint64_t handle, ViewerCpuTileView* output) noexcept;
