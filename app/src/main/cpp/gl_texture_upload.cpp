#include "gl_texture_upload.h"

GLuint GlTextureUpload::takeTextureName() noexcept {
    if (remaining_ == 0) {
        glGenTextures(static_cast<GLsizei>(names_.size()), names_.data());
        remaining_ = names_.size();
    }
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
    // Replace the data store rather than overwriting storage an earlier upload uses.
    // The GL command stream preserves the earlier transfer before retiring its store.
    glBufferData(
        GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(byteCount), pixels, GL_STREAM_DRAW);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(
        GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
}

void GlTextureUpload::close() noexcept {
    if (unpackBuffer_ != 0) glDeleteBuffers(1, &unpackBuffer_);
    glDeleteTextures(static_cast<GLsizei>(names_.size()), names_.data());
    unpackBuffer_ = 0;
    names_.fill(0);
    remaining_ = 0;
}
