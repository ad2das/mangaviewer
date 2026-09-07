#pragma once

#include <GLES3/gl3.h>

#include <cstddef>
#include <cstdint>

void uploadTexturePixels(
    bool direct,
    int width,
    int height,
    const std::uint8_t* pixels,
    std::size_t byteCount) noexcept;
