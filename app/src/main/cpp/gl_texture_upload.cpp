#include "gl_texture_upload.h"

void GlTextureUpload::reserveTextureNames() noexcept {
    if (remaining_ == 0) {
        glGenTextures(static_cast<GLsizei>(names_.size()), names_.data());
        remaining_ = names_.size();
    }
}

void GlTextureUpload::prepareNames() noexcept {
    reserveTextureNames();
    if (unpackBuffer_ == 0) glGenBuffers(1, &unpackBuffer_);
}

GLuint GlTextureUpload::takeTextureName() noexcept {
    reserveTextureNames();
    const GLuint name = names_[--remaining_];
    names_[remaining_] = 0;
    return name;
}

void GlTextureUpload::pixels(
    bool direct,
    int width,
    int height,
    const std::uint8_t* pixels,
    std::size_t byteCount) noexcept {
    if (direct) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return;
    }

    if (unpackBuffer_ == 0) glGenBuffers(1, &unpackBuffer_);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer_);
    // Normal buffer updates are ordered after preceding texture transfers. Reuse the
    // store instead of making every tile allocate and synchronize a replacement.
    if (byteCount > unpackCapacity_) {
        glBufferData(
            GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(byteCount), pixels, GL_STREAM_DRAW);
        unpackCapacity_ = byteCount;
    } else {
        glBufferSubData(GL_PIXEL_UNPACK_BUFFER, 0, static_cast<GLsizeiptr>(byteCount), pixels);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(
        GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
}

void GlTextureUpload::close() noexcept {
    if (unpackBuffer_ != 0) glDeleteBuffers(1, &unpackBuffer_);
    glDeleteTextures(static_cast<GLsizei>(names_.size()), names_.data());
    unpackBuffer_ = 0;
    unpackCapacity_ = 0;
    names_.fill(0);
    remaining_ = 0;
}
