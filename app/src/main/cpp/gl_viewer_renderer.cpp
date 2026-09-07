#include "gl_viewer_renderer.h"

#include <android/log.h>
#include <android/trace.h>
#include <dlfcn.h>
#include <GLES2/gl2ext.h>
#include <time.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <cstdio>
#include <limits>
#include <new>
#include <string>

#include "viewer_cpu_tile.h"
#include "gl_texture_upload.h"

namespace {

#ifndef NDEBUG
constexpr char kVertexShader[] = R"(#version 300 es
layout(location = 0) in vec2 position;
layout(location = 1) in vec2 textureCoordinate;
out vec2 sampledCoordinate;
void main() {
    sampledCoordinate = textureCoordinate;
    gl_Position = vec4(position, 0.0, 1.0);
}
)";

#endif

constexpr char kFragmentShader[] = R"(#version 300 es
precision mediump float;
in vec2 sampledCoordinate;
uniform sampler2D pageTexture;
out vec4 color;
void main() {
    color = texture(pageTexture, sampledCoordinate);
}
)";

constexpr char kStaticQuadVertexShader[] = R"(#version 300 es
layout(location = 0) in vec2 position;
layout(location = 1) in vec2 textureCoordinate;
uniform vec2 verticalBounds;
out vec2 sampledCoordinate;
void main() {
    sampledCoordinate = textureCoordinate;
    float y = position.y < 0.5 ? verticalBounds.x : verticalBounds.y;
    gl_Position = vec4(position.x, y, 0.0, 1.0);
}
)";

struct Vertex final {
    float x;
    float y;
    float u;
    float v;
};

std::int64_t monotonicNanos() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(value.tv_sec) * 1000000000LL + value.tv_nsec;
}

bool extensionPresent(const char* extensions, const char* expected) noexcept {
    if (extensions == nullptr || expected == nullptr || *expected == '\0' || std::strchr(expected, ' ')) {
        return false;
    }
    const std::size_t length = std::strlen(expected);
    for (const char* found = std::strstr(extensions, expected); found != nullptr;
         found = std::strstr(found + length, expected)) {
        const bool begins = found == extensions || found[-1] == ' ';
        const bool ends = found[length] == '\0' || found[length] == ' ';
        if (begins && ends) return true;
    }
    return false;
}

GLuint compileShader(GLenum kind, const char* source) noexcept {
    const GLuint shader = glCreateShader(kind);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) return shader;
    std::array<GLchar, 1024> log{};
    GLsizei length = 0;
    glGetShaderInfoLog(shader, static_cast<GLsizei>(log.size()), &length, log.data());
    __android_log_print(
        ANDROID_LOG_ERROR, "GlViewerRenderer", "shader kind=%u: %.*s",
        kind, static_cast<int>(length), log.data());
    glDeleteShader(shader);
    return 0;
}

bool eglFailure(const char* operation) noexcept {
    __android_log_print(
        ANDROID_LOG_ERROR, "GlViewerRenderer", "%s egl=0x%x", operation, eglGetError());
    return false;
}

bool validUploadGeometry(
    int width,
    int height,
    int sourceTop,
    int sourceBottom,
    int sourceHeight) noexcept {
    return width > 0 && height > 0 && sourceTop >= 0 &&
        sourceBottom > sourceTop && sourceBottom <= sourceHeight;
}

void setWindowFrameRate(ANativeWindow* window, float frameRate) noexcept {
    using SetFrameRate = std::int32_t (*)(ANativeWindow*, float, std::int8_t);
    static const auto function = reinterpret_cast<SetFrameRate>(
        dlsym(RTLD_DEFAULT, "ANativeWindow_setFrameRate"));
    if (function != nullptr && window != nullptr) {
        function(window, frameRate, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    }
}

class ScopedTraceSection final {
public:
    explicit ScopedTraceSection(const char* name) noexcept : enabled_(ATrace_isEnabled()) {
        if (enabled_) ATrace_beginSection(name);
    }

    ~ScopedTraceSection() {
        if (enabled_) ATrace_endSection();
    }

    ScopedTraceSection(const ScopedTraceSection&) = delete;
    ScopedTraceSection& operator=(const ScopedTraceSection&) = delete;

private:
    bool enabled_ = false;
};

}  // namespace

GlViewerRenderer::GlViewerRenderer(JNIEnv* env, jobject callback) noexcept
    : callback_(std::make_shared<GlPresentationCallback>(env, callback)) {}

GlViewerRenderer::~GlViewerRenderer() { close(); }

bool GlViewerRenderer::glSucceeded(const char* operation) noexcept {
    GLenum error = glGetError();
    if (verificationGlContextLoss_) {
        verificationGlContextLoss_ = false;
        error = GL_CONTEXT_LOST_KHR;
    }
    if (error == GL_NO_ERROR) return true;
    if (error == GL_CONTEXT_LOST_KHR) {
        contextLost_ = true;
        lastContextError_ = EGL_CONTEXT_LOST;
    }
    __android_log_print(ANDROID_LOG_ERROR, "GlViewerRenderer", "%s gl=0x%x", operation, error);
    return false;
}

bool GlViewerRenderer::glFailure(const char* operation) noexcept {
    glSucceeded(operation);
    return false;
}

bool GlViewerRenderer::valid() const noexcept {
    return callback_ != nullptr && callback_->valid();
}

bool GlViewerRenderer::initialize() noexcept {
    if (!bindOwnerThread()) return false;
    if (context_ != EGL_NO_CONTEXT) return true;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return eglFailure("get display");
    if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) return eglFailure("initialize");
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) return eglFailure("bind GLES");
    constexpr EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint count = 0;
    if (eglChooseConfig(display_, attributes, &config_, 1, &count) != EGL_TRUE || count != 1) {
        return eglFailure("choose config");
    }
    constexpr EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    constexpr EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttributes);
    if (context_ == EGL_NO_CONTEXT || pbuffer_ == EGL_NO_SURFACE || !makeCurrent(pbuffer_)) {
        return eglFailure("create context or pbuffer");
    }
    const char* extensions = eglQueryString(display_, EGL_EXTENSIONS);
    if (extensionPresent(extensions, "EGL_ANDROID_get_frame_timestamps")) {
        getNextFrameId_ = reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC>(
            eglGetProcAddress("eglGetNextFrameIdANDROID"));
        getFrameTimestampSupported_ =
            reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROIDPROC>(
                eglGetProcAddress("eglGetFrameTimestampSupportedANDROID"));
        getFrameTimestamps_ = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC>(
            eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    }
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximumTextureSize_);
    if (maximumTextureSize_ <= 0) return glFailure("initialize objects");
    return true;
}

#ifndef NDEBUG
bool GlViewerRenderer::initializeStreamingQuadForVerification() noexcept {
    if (program_ != 0 && vertexBuffer_ != 0) return true;
    glGenBuffers(1, &vertexBuffer_);
    if (vertexBuffer_ == 0) return glFailure("initialize streaming verification buffer");
    const GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        std::array<GLchar, 1024> log{};
        GLsizei length = 0;
        glGetProgramInfoLog(program_, static_cast<GLsizei>(log.size()), &length, log.data());
        __android_log_print(
            ANDROID_LOG_ERROR, "GlViewerRenderer", "program link: %.*s",
            static_cast<int>(length), log.data());
        return false;
    }
    positionLocation_ = glGetAttribLocation(program_, "position");
    textureLocation_ = glGetAttribLocation(program_, "textureCoordinate");
    samplerLocation_ = glGetUniformLocation(program_, "pageTexture");
    const bool locations = positionLocation_ >= 0 && textureLocation_ >= 0 && samplerLocation_ >= 0;
    if (!locations) {
        __android_log_print(
            ANDROID_LOG_ERROR, "GlViewerRenderer", "locations position=%d texture=%d sampler=%d",
            positionLocation_, textureLocation_, samplerLocation_);
    }
    return locations && glSucceeded("initialize streaming quad verification");
}
#endif

bool GlViewerRenderer::makeCurrent(EGLSurface surface) noexcept {
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE) return false;
    if (eglMakeCurrent(display_, surface, surface, context_) == EGL_TRUE) return true;
    lastContextError_ = eglGetError();
    contextLost_ = contextLost_ || lastContextError_ == EGL_CONTEXT_LOST;
    return false;
}

bool GlViewerRenderer::recreateContext() noexcept {
    ANativeWindow* retainedWindow = window_;
    if (retainedWindow != nullptr) ANativeWindow_acquire(retainedWindow);
    for (const auto& frame : pendingFrames_) callback_->presented(frame.token, 0, -4, frame.frameId);
    pendingFrames_.clear();
    close();
    const bool restored = retainedWindow != nullptr && initialize() && attach(retainedWindow);
    if (retainedWindow != nullptr) ANativeWindow_release(retainedWindow);
    return restored;
}

bool GlViewerRenderer::attach(ANativeWindow* window) noexcept {
    if (window == nullptr) return eglFailure("attach window missing");
    if (!initialize()) return false;
    detach();
    if (!createWindowSurface(window)) return false;
    configurePresentationTimestamps();
    // Interval zero enables Android's asynchronous queue and replacement of unacquired buffers.
    if (eglSwapInterval(display_, 1) != EGL_TRUE) return eglFailure("set swap interval");
    return true;
}

bool GlViewerRenderer::createWindowSurface(ANativeWindow* window) noexcept {
    EGLint visual = 0;
    if (eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &visual) == EGL_TRUE) {
        ANativeWindow_setBuffersGeometry(window, 0, 0, visual);
    }
    windowSurface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (windowSurface_ == EGL_NO_SURFACE || !makeCurrent(windowSurface_)) {
        if (windowSurface_ != EGL_NO_SURFACE) eglDestroySurface(display_, windowSurface_);
        windowSurface_ = EGL_NO_SURFACE;
        makeCurrent(pbuffer_);
        return eglFailure("create or bind window surface");
    }
    ANativeWindow_acquire(window);
    window_ = window;
    return true;
}

void GlViewerRenderer::configurePresentationTimestamps() noexcept {
    const bool available = getNextFrameId_ != nullptr && getFrameTimestampSupported_ != nullptr &&
        getFrameTimestamps_ != nullptr &&
        eglSurfaceAttrib(display_, windowSurface_, EGL_TIMESTAMPS_ANDROID, EGL_TRUE) == EGL_TRUE;
    if (!available) {
        getNextFrameId_ = nullptr;
        getFrameTimestampSupported_ = nullptr;
        getFrameTimestamps_ = nullptr;
        return;
    }
    presentationTimestamp_ = selectPresentationTimestamp();
    __android_log_print(
        ANDROID_LOG_INFO, "GlViewerRenderer", "presentation timestamp kind=0x%x",
        presentationTimestamp_);
    if (presentationTimestamp_ == EGL_NONE) {
        getNextFrameId_ = nullptr;
        getFrameTimestamps_ = nullptr;
    }
}

EGLint GlViewerRenderer::selectPresentationTimestamp() const noexcept {
    const std::array<EGLint, 3> candidates{
        EGL_DISPLAY_PRESENT_TIME_ANDROID,
        EGL_COMPOSITION_LATCH_TIME_ANDROID,
        EGL_RENDERING_COMPLETE_TIME_ANDROID,
    };
    for (const EGLint candidate : candidates) {
        if (getFrameTimestampSupported_(display_, windowSurface_, candidate) == EGL_TRUE) {
            return candidate;
        }
    }
    return EGL_NONE;
}

void GlViewerRenderer::detach() noexcept {
    if (!onOwnerThread()) return;
    cancelReadbacks(GlReadbackStatus::kCancelled);
    if (display_ == EGL_NO_DISPLAY) return;
    for (const auto& frame : pendingFrames_) {
        callback_->presented(frame.token, 0, -2, frame.frameId);
    }
    pendingFrames_.clear();
    makeCurrent(pbuffer_);
    if (windowSurface_ != EGL_NO_SURFACE) eglDestroySurface(display_, windowSurface_);
    windowSurface_ = EGL_NO_SURFACE;
    if (window_ != nullptr) ANativeWindow_release(window_);
    window_ = nullptr;
    frameRate_ = 0.0F;
    presentationTimestamp_ = EGL_NONE;
}

std::uint64_t GlViewerRenderer::upload(
    std::uint64_t cpuTileHandle,
    int width,
    int height,
    int sourceTop,
    int sourceBottom,
    int sourceHeight) noexcept {
    ViewerCpuTileView cpu{};
    if (!validUploadGeometry(width, height, sourceTop, sourceBottom, sourceHeight)) return 0;
    const std::uint64_t expected =
        static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) * 4ULL;
    if (!initialize()) return 0;
    if (!textureBudgetAllows(expected)) return 0;
    if (width > maximumTextureSize_ || height > maximumTextureSize_) return 0;
    if (!viewerDescribeCpuTile(cpuTileHandle, &cpu)) return 0;
    if (cpu.byteCount != expected) return 0;
#ifndef NDEBUG
    const bool directTextureUpload = directTextureUploadForVerification_;
#else
    constexpr bool directTextureUpload = false;
#endif
    const char* transferMode = directTextureUpload ? "direct" : "pbo";
    char uploadTraceLabel[128]{};
    std::snprintf(
        uploadTraceLabel, sizeof(uploadTraceLabel), "viewer_upload_total:%s:%llu",
        transferMode, static_cast<unsigned long long>(cpu.byteCount));
    ScopedTraceSection uploadTrace(uploadTraceLabel);
    const EGLSurface target = windowSurface_ != EGL_NO_SURFACE ? windowSurface_ : pbuffer_;
    if (!makeCurrent(target)) return 0;
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    char transferTraceLabel[128]{};
    std::snprintf(
        transferTraceLabel, sizeof(transferTraceLabel), "viewer_upload_transfer:%s:%llu",
        transferMode, static_cast<unsigned long long>(cpu.byteCount));
    ScopedTraceSection transferTrace(transferTraceLabel);
    uploadTexturePixels(directTextureUpload, width, height, cpu.pixels, cpu.byteCount);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (!glSucceeded("texture upload")) {
        if (texture != 0) glDeleteTextures(1, &texture);
        return 0;
    }
    if (nextTextureKey_ == 0) {
        glDeleteTextures(1, &texture);
        return 0;
    }
    const std::uint64_t key = nextTextureKey_++;
    textures_.emplace(key, Texture{
        texture, width, height, sourceTop, sourceBottom, sourceHeight,
        static_cast<std::int64_t>(expected), false,
    });
    return key;
}

void GlViewerRenderer::release(std::uint64_t key) noexcept {
    const auto found = textures_.find(key);
    if (found == textures_.end()) return;
    found->second.retired = true;
    collectRetiredTextures();
}

bool GlViewerRenderer::installScene(const GlViewerFrame& frame) noexcept {
    if (frame.coordinateUnitsPerPixel != 1 && frame.coordinateUnitsPerPixel != 1024) return false;
    if (frame.scene == nullptr) {
        if (frame.sceneKey == sceneKey_ && frame.coordinateUnitsPerPixel == sceneUnitsPerPixel_) return true;
        __android_log_print(
            ANDROID_LOG_ERROR, "GlViewerRenderer", "scene key mismatch incoming=%lld active=%lld",
            static_cast<long long>(frame.sceneKey), static_cast<long long>(sceneKey_));
        return false;
    }
    for (const GlSceneEntry& entry : *frame.scene) {
        if (entry.textureKey == 0 || entry.sourceTop < 0 ||
            entry.sourceBottom <= entry.sourceTop || entry.sourceBottom > entry.sourceHeight ||
            entry.destinationBottom <= entry.destinationTop) return false;
    }
    scene_ = *frame.scene;
    sceneKey_ = frame.sceneKey;
    sceneUnitsPerPixel_ = frame.coordinateUnitsPerPixel;
    collectRetiredTextures();
    return true;
}

int GlViewerRenderer::submit(const GlViewerFrame& frame) noexcept {
    const int bound = bindSubmitSurface(frame);
    if (bound <= 0) {
        failReadback(frame, bound == -2 ? GlReadbackStatus::kContextLost
                                       : GlReadbackStatus::kGlError);
        return bound;
    }
    lastSubmittedToken_ = std::max(lastSubmittedToken_, frame.token);
    if (!installScene(frame)) {
        failReadback(frame, GlReadbackStatus::kGlError);
        return -1;
    }
    if (window_ != nullptr && frame.frameRate > 0.0F && frame.frameRate != frameRate_) {
        setWindowFrameRate(window_, frame.frameRate);
        frameRate_ = frame.frameRate;
    }
    if (!draw(frame)) {
        failReadback(frame, contextLost_ ? GlReadbackStatus::kContextLost
                                         : GlReadbackStatus::kGlError);
        return contextLost_ ? -2 : -1;
    }
    EGLuint64KHR frameId = 0;
    const bool timestamped = getNextFrameId_ != nullptr && getFrameTimestamps_ != nullptr &&
        getNextFrameId_(display_, windowSurface_, &frameId) == EGL_TRUE;
    if (hasReadbackRequest(frame.token)) issueReadback(frame, timestamped ? frameId : 0);
    return swapFrame(frame.token, timestamped, frameId);
}

int GlViewerRenderer::bindSubmitSurface(const GlViewerFrame& frame) noexcept {
    if (windowSurface_ == EGL_NO_SURFACE || frame.token <= 0 || frame.surfaceWidth <= 0 ||
        frame.surfaceHeight <= 0) return 0;
    if (makeCurrent(windowSurface_)) return 1;
    const EGLint error = lastContextError_;
    __android_log_print(
        ANDROID_LOG_ERROR, "GlViewerRenderer", "bind submit surface egl=0x%x", error);
    if (error == EGL_CONTEXT_LOST) return -2;
    if (error != EGL_BAD_SURFACE && error != EGL_BAD_NATIVE_WINDOW) return -1;
    detach();
    return 0;
}

int GlViewerRenderer::swapFrame(
    std::int64_t token,
    bool timestamped,
    EGLuint64KHR frameId) noexcept {
    char traceLabel[128]{};
    const bool traced = ATrace_isEnabled();
    if (traced) {
        ATrace_beginSection("viewer_clock");
        std::snprintf(traceLabel, sizeof(traceLabel), "viewer_swap:%lld:%llu:%lld",
                      static_cast<long long>(token), static_cast<unsigned long long>(frameId),
                      static_cast<long long>(monotonicNanos()));
        ATrace_beginSection(traceLabel);
    }
    const EGLBoolean swapped = eglSwapBuffers(display_, windowSurface_);
    if (traced) {
        ATrace_endSection();
        ATrace_endSection();
    }
    if (swapped != EGL_TRUE) {
        const EGLint error = eglGetError();
        completeReadbackSwap(token, false, error == EGL_CONTEXT_LOST);
        __android_log_print(
            ANDROID_LOG_ERROR, "GlViewerRenderer", "swap buffers egl=0x%x", error);
        if (error == EGL_CONTEXT_LOST) {
            contextLost_ = true;
            return -2;
        }
        if (error != EGL_BAD_SURFACE && error != EGL_BAD_NATIVE_WINDOW) return -1;
        detach();
        return 0;
    }
    completeReadbackSwap(token, true, false);
    const std::int64_t submitted = monotonicNanos();
    if (timestamped) pendingFrames_.push_back(PendingFrame{frameId, token, submitted});
    else callback_->presented(token, submitted);
    return 1;
}

bool GlViewerRenderer::draw(const GlViewerFrame& frame) noexcept {
#ifndef NDEBUG
    if (streamingQuadForVerification_) return drawStreamingQuadForVerification(frame);
#endif
    return drawStaticQuad(frame);
}

#ifndef NDEBUG
bool GlViewerRenderer::drawStreamingQuadForVerification(const GlViewerFrame& frame) noexcept {
    if (!initializeStreamingQuadForVerification()) return false;
    glViewport(0, 0, frame.surfaceWidth, frame.surfaceHeight);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(program_);
    glUniform1i(samplerLocation_, 0);
    std::vector<float> vertices;
    std::vector<VisibleDraw> draws;
    vertices.reserve(scene_.size() * 24U);
    draws.reserve(scene_.size());
    for (const GlSceneEntry& entry : scene_) {
        if (!appendVisibleEntry(
                entry, frame.surfaceHeight, frame.viewportTop, &vertices, &draws)) return false;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
    glBufferData(
        GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(vertices.size() * sizeof(float)),
        vertices.empty() ? nullptr : vertices.data(),
        GL_STREAM_DRAW);
    glEnableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glEnableVertexAttribArray(static_cast<GLuint>(textureLocation_));
    glVertexAttribPointer(
        static_cast<GLuint>(positionLocation_), 2, GL_FLOAT, GL_FALSE,
        sizeof(Vertex), nullptr);
    glVertexAttribPointer(
        static_cast<GLuint>(textureLocation_), 2, GL_FLOAT, GL_FALSE,
        sizeof(Vertex), reinterpret_cast<const void*>(sizeof(float) * 2U));
    glActiveTexture(GL_TEXTURE0);
    for (const VisibleDraw& draw : draws) {
        glBindTexture(GL_TEXTURE_2D, draw.texture);
        glDrawArrays(GL_TRIANGLES, draw.firstVertex, 6);
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
    return glSucceeded("draw scene");
}
#endif

bool GlViewerRenderer::initializeStaticQuad() noexcept {
    if (staticQuadProgram_ != 0 && staticQuadBuffer_ != 0) return true;
    const GLuint vertex = compileShader(GL_VERTEX_SHADER, kStaticQuadVertexShader);
    const GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return false;
    }
    staticQuadProgram_ = glCreateProgram();
    glAttachShader(staticQuadProgram_, vertex);
    glAttachShader(staticQuadProgram_, fragment);
    glLinkProgram(staticQuadProgram_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(staticQuadProgram_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) return false;
    staticQuadSamplerLocation_ = glGetUniformLocation(staticQuadProgram_, "pageTexture");
    staticQuadBoundsLocation_ = glGetUniformLocation(staticQuadProgram_, "verticalBounds");
    if (staticQuadSamplerLocation_ < 0 || staticQuadBoundsLocation_ < 0) return false;
    constexpr std::array<Vertex, 6> quad{{
        {-1.0F, 0.0F, 0.0F, 0.0F}, {1.0F, 0.0F, 1.0F, 0.0F},
        {1.0F, 1.0F, 1.0F, 1.0F}, {-1.0F, 0.0F, 0.0F, 0.0F},
        {1.0F, 1.0F, 1.0F, 1.0F}, {-1.0F, 1.0F, 0.0F, 1.0F},
    }};
    glGenBuffers(1, &staticQuadBuffer_);
    if (staticQuadBuffer_ == 0) return false;
    glBindBuffer(GL_ARRAY_BUFFER, staticQuadBuffer_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(sizeof(quad)), quad.data(), GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return glSucceeded("initialize static quad");
}

bool GlViewerRenderer::drawStaticQuad(const GlViewerFrame& frame) noexcept {
    if (!initializeStaticQuad()) return false;
    glViewport(0, 0, frame.surfaceWidth, frame.surfaceHeight);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(staticQuadProgram_);
    glUniform1i(staticQuadSamplerLocation_, 0);
    glBindBuffer(GL_ARRAY_BUFFER, staticQuadBuffer_);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), nullptr);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex),
                          reinterpret_cast<const void*>(sizeof(float) * 2U));
    glActiveTexture(GL_TEXTURE0);
    for (const GlSceneEntry& entry : scene_) {
        VisibleQuad quad{};
        if (!describeVisibleQuad(entry, frame.surfaceHeight, frame.viewportTop, &quad)) return false;
        if (quad.texture == 0) continue;
        glUniform2f(staticQuadBoundsLocation_, quad.top, quad.bottom);
        glBindTexture(GL_TEXTURE_2D, quad.texture);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
    return glSucceeded("draw static quad");
}

#ifndef NDEBUG
bool GlViewerRenderer::appendVisibleEntry(
    const GlSceneEntry& entry,
    int height,
    int viewportTop,
    std::vector<float>* vertices,
    std::vector<VisibleDraw>* draws) noexcept {
    VisibleQuad visible{};
    if (!describeVisibleQuad(entry, height, viewportTop, &visible)) return false;
    if (visible.texture == 0) return true;
    const float top = visible.top;
    const float bottom = visible.bottom;
    const std::array<Vertex, 6> quad{{
        {-1.0F, top, 0.0F, 0.0F}, {1.0F, top, 1.0F, 0.0F},
        {1.0F, bottom, 1.0F, 1.0F}, {-1.0F, top, 0.0F, 0.0F},
        {1.0F, bottom, 1.0F, 1.0F}, {-1.0F, bottom, 0.0F, 1.0F},
    }};
    draws->push_back(VisibleDraw{
        visible.texture,
        static_cast<GLint>(vertices->size() / 4U),
    });
    for (const Vertex& vertex : quad) {
        vertices->insert(vertices->end(), {vertex.x, vertex.y, vertex.u, vertex.v});
    }
    return true;
}
#endif

void GlViewerRenderer::pollPresentations() noexcept {
    if (windowSurface_ == EGL_NO_SURFACE || getFrameTimestamps_ == nullptr) {
        for (const auto& frame : pendingFrames_) callback_->presented(frame.token, 0, -1, frame.frameId);
        pendingFrames_.clear();
        return;
    }
    auto current = pendingFrames_.begin();
    while (current != pendingFrames_.end()) {
        EGLnsecsANDROID presented = EGL_TIMESTAMP_PENDING_ANDROID;
        const EGLint requested[] = {presentationTimestamp_};
        const EGLBoolean result = getFrameTimestamps_(
            display_, windowSurface_, current->frameId, 1, requested, &presented);
        if (result != EGL_TRUE) {
            eglGetError();
            callback_->presented(current->token, 0, -1, current->frameId);
            current = pendingFrames_.erase(current);
        } else if (presented > 0) {
            callback_->presented(current->token, static_cast<std::int64_t>(presented),
                                 presentationTimestamp_, current->frameId);
            current = pendingFrames_.erase(current);
        } else if (presented == EGL_TIMESTAMP_INVALID_ANDROID ||
                   monotonicNanos() - current->submittedAtNanos >= 1000000000LL) {
            // Terminal missing evidence, not a synthetic successful presentation.
            const std::int32_t kind = presented == EGL_TIMESTAMP_INVALID_ANDROID ? -3 : -1;
            callback_->presented(current->token, 0, kind, current->frameId);
            current = pendingFrames_.erase(current);
        } else {
            ++current;
        }
    }
}

bool GlViewerRenderer::sceneReferences(std::uint64_t key) const noexcept {
    return std::any_of(scene_.begin(), scene_.end(), [key](const GlSceneEntry& entry) {
        return entry.textureKey == key;
    });
}

void GlViewerRenderer::collectRetiredTextures() noexcept {
    for (auto current = textures_.begin(); current != textures_.end();) {
        if (!current->second.retired || sceneReferences(current->first)) {
            ++current;
            continue;
        }
        deleteTexture(&current->second);
        current = textures_.erase(current);
    }
}

void GlViewerRenderer::deleteTexture(Texture* texture) noexcept {
    if (texture != nullptr && texture->name != 0) glDeleteTextures(1, &texture->name);
    if (texture != nullptr) texture->name = 0;
}

void GlViewerRenderer::close() noexcept {
    if (!onOwnerThread()) return;
    markContextLostReadbacks();
    detach();
    if (display_ == EGL_NO_DISPLAY) {
        contextDestroyedReadbacks();
        return;
    }
    makeCurrent(pbuffer_);
    scene_.clear();
    for (auto& item : textures_) deleteTexture(&item.second);
    textures_.clear();
#ifndef NDEBUG
    if (vertexBuffer_ != 0) glDeleteBuffers(1, &vertexBuffer_);
    if (program_ != 0) glDeleteProgram(program_);
#endif
    if (staticQuadBuffer_ != 0) glDeleteBuffers(1, &staticQuadBuffer_);
    if (staticQuadProgram_ != 0) glDeleteProgram(staticQuadProgram_);
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    eglTerminate(display_);
    contextDestroyedReadbacks();
    pbuffer_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    display_ = EGL_NO_DISPLAY;
    config_ = nullptr;
#ifndef NDEBUG
    program_ = 0;
    vertexBuffer_ = 0;
    positionLocation_ = -1;
    textureLocation_ = -1;
    samplerLocation_ = -1;
#endif
    staticQuadProgram_ = 0;
    staticQuadBuffer_ = 0;
    staticQuadSamplerLocation_ = -1;
    staticQuadBoundsLocation_ = -1;
    maximumTextureSize_ = 0;
    sceneKey_ = 0;
    getNextFrameId_ = nullptr;
    getFrameTimestampSupported_ = nullptr;
    getFrameTimestamps_ = nullptr;
    lastContextError_ = EGL_SUCCESS;
    contextLost_ = false;
    verificationGlContextLoss_ = false;
    lastSubmittedToken_ = 0;
}
