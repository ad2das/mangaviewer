#include "gl_texture_upload.h"

void uploadTexturePixels(
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

    GLuint unpackBuffer = 0;
    glGenBuffers(1, &unpackBuffer);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
    glBufferData(
        GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(byteCount), pixels, GL_STREAM_DRAW);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(
        GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glDeleteBuffers(1, &unpackBuffer);
}
