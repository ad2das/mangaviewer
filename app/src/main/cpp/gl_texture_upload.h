#pragma once

#include <GLES3/gl3.h>

#include <cstddef>
#include <cstdint>
#include <array>

/** Context-owned names; reserving texture names allocates no image storage. */
class GlTextureUpload final {
public:
    void prepareNames() noexcept;
    GLuint takeTextureName() noexcept;
    void pixels(bool direct, int width, int height, const std::uint8_t* pixels,
                std::size_t byteCount) noexcept;
    void close() noexcept;
    void invalidateCapacity() noexcept { unpackCapacity_ = 0; }

private:
    void reserveTextureNames() noexcept;
    std::array<GLuint, 32> names_{};
    std::size_t remaining_ = 0;
    GLuint unpackBuffer_ = 0;
    std::size_t unpackCapacity_ = 0;
};
