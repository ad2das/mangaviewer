#include "gl_viewer_renderer.h"

bool GlViewerRenderer::rasterizationInfoForVerification(int* values) noexcept {
#ifdef NDEBUG
    static_cast<void>(values);
    return false;
#else
    if (values == nullptr || !onOwnerThread() || windowSurface_ == EGL_NO_SURFACE ||
        !makeCurrent(windowSurface_)) return false;
    glGetIntegerv(GL_SUBPIXEL_BITS, &values[0]);
    glGetIntegerv(GL_SAMPLE_BUFFERS, &values[1]);
    glGetIntegerv(GL_SAMPLES, &values[2]);
    return glGetError() == GL_NO_ERROR;
#endif
}

bool GlViewerRenderer::setSwapIntervalForVerification(int interval) noexcept {
#ifdef NDEBUG
    static_cast<void>(interval);
    return false;
#else
    if (!onOwnerThread() || windowSurface_ == EGL_NO_SURFACE ||
        (interval != 0 && interval != 1) || !makeCurrent(windowSurface_)) return false;
    return eglSwapInterval(display_, interval) == EGL_TRUE;
#endif
}

bool GlViewerRenderer::setStaticQuadForVerification(bool enabled) noexcept {
#ifdef NDEBUG
    return enabled;
#else
    streamingQuadForVerification_ = !enabled;
    return true;
#endif
}

bool GlViewerRenderer::setDirectTextureUploadForVerification(bool enabled) noexcept {
#ifdef NDEBUG
    // Release builds retain the default PBO upload path and reject direct mode.
    return !enabled;
#else
    directTextureUploadForVerification_ = enabled;
    return true;
#endif
}
