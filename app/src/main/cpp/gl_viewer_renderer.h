#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <thread>
#include <unordered_map>
#include <vector>

#include "gl_presentation_callback.h"
#include "gl_strip_readback.h"

struct GlSceneEntry final {
    std::uint64_t textureKey = 0;
    int sourceTop = 0;
    int sourceBottom = 0;
    int sourceHeight = 0;
    int destinationTop = 0;
    int destinationBottom = 0;
};

struct GlViewerFrame final {
    std::int64_t token = 0;
    int surfaceWidth = 0;
    int surfaceHeight = 0;
    int viewportTop = 0;
    float frameRate = 0.0F;
    std::int64_t sceneKey = 0;
    const std::vector<GlSceneEntry>* scene = nullptr;
    int coordinateUnitsPerPixel = 1;
};

/** Sole owner of the viewer EGL context, window surface, textures, and scene. */
class GlViewerRenderer final {
public:
    GlViewerRenderer(JNIEnv* env, jobject callback) noexcept;
    ~GlViewerRenderer();

    GlViewerRenderer(const GlViewerRenderer&) = delete;
    GlViewerRenderer& operator=(const GlViewerRenderer&) = delete;

    bool valid() const noexcept;
    bool contextLost() const noexcept { return contextLost_; }
    bool recreateContext() noexcept;
    void injectGlContextLossForVerification() noexcept { verificationGlContextLoss_ = true; }
    bool setStaticQuadForVerification(bool enabled) noexcept;
    bool setDirectTextureUploadForVerification(bool enabled) noexcept;
    bool setSwapIntervalForVerification(int interval) noexcept;
    bool rasterizationInfoForVerification(int* values) noexcept;
    bool attach(ANativeWindow* window) noexcept;
    void detach() noexcept;
    std::uint64_t upload(
        std::uint64_t cpuTileHandle,
        int width,
        int height,
        int sourceTop,
        int sourceBottom,
        int sourceHeight) noexcept;
    void release(std::uint64_t key) noexcept;
    bool setTextureBudget(std::int64_t bytes) noexcept;
    bool clearScene() noexcept;
    bool hasTexture(std::uint64_t key) const noexcept;
    std::array<std::int64_t, 5> textureCounts() const noexcept;
    int submit(const GlViewerFrame& frame) noexcept;
    void pollPresentations() noexcept;
    bool requestReadback(
        std::int64_t token,
        std::int64_t sessionId,
        std::int64_t rendererEpoch,
        std::int64_t surfaceEpoch,
        int top,
        int bottom) noexcept;
    std::optional<GlReadbackPacket> takeReadback(std::int64_t token);
    std::array<std::int64_t, 5> readbackCounts() const noexcept;

private:
    struct Texture final {
        GLuint name = 0;
        int width = 0;
        int height = 0;
        int sourceTop = 0;
        int sourceBottom = 0;
        int sourceHeight = 0;
        std::int64_t bytes = 0;
        bool retired = false;
    };

    struct PendingFrame final {
        EGLuint64KHR frameId = 0;
        std::int64_t token = 0;
        std::int64_t submittedAtNanos = 0;
    };

    struct VisibleQuad final {
        GLuint texture = 0;
        float top = 0.0F;
        float bottom = 0.0F;
    };

#ifndef NDEBUG
    struct VisibleDraw final {
        GLuint texture = 0;
        GLint firstVertex = 0;
    };
#endif

    bool initialize() noexcept;
    bool glSucceeded(const char* operation) noexcept;
    bool glFailure(const char* operation) noexcept;
#ifndef NDEBUG
    bool initializeStreamingQuadForVerification() noexcept;
    bool drawStreamingQuadForVerification(const GlViewerFrame& frame) noexcept;
    bool appendVisibleEntry(
        const GlSceneEntry& entry,
        int height,
        int viewportTop,
        std::vector<float>* vertices,
        std::vector<VisibleDraw>* draws) noexcept;
#endif
    bool makeCurrent(EGLSurface surface) noexcept;
    bool createWindowSurface(ANativeWindow* window) noexcept;
    void configurePresentationTimestamps() noexcept;
    EGLint selectPresentationTimestamp() const noexcept;
    bool installScene(const GlViewerFrame& frame) noexcept;
    int bindSubmitSurface(const GlViewerFrame& frame) noexcept;
    int swapFrame(std::int64_t token, bool timestamped, EGLuint64KHR frameId) noexcept;
    bool draw(const GlViewerFrame& frame) noexcept;
    bool initializeStaticQuad() noexcept;
    bool drawStaticQuad(const GlViewerFrame& frame) noexcept;
    bool describeVisibleQuad(
        const GlSceneEntry& entry,
        int height,
        int viewportTop,
        VisibleQuad* quad) noexcept;
    void collectRetiredTextures() noexcept;
    bool sceneReferences(std::uint64_t key) const noexcept;
    void deleteTexture(Texture* texture) noexcept;
    bool textureBudgetAllows(std::uint64_t bytes) const noexcept;
    void close() noexcept;

    std::shared_ptr<GlPresentationCallback> callback_;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface windowSurface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
#ifndef NDEBUG
    GLuint program_ = 0;
    GLuint vertexBuffer_ = 0;
    GLint positionLocation_ = -1;
    GLint textureLocation_ = -1;
    GLint samplerLocation_ = -1;
    // Preserve explicit diagnostic selection across context recreation.
    bool streamingQuadForVerification_ = false;
    // Preserve explicit diagnostic selection across context recreation.
    bool directTextureUploadForVerification_ = false;
#endif
    GLuint staticQuadProgram_ = 0;
    GLuint staticQuadBuffer_ = 0;
    GLint staticQuadSamplerLocation_ = -1;
    GLint staticQuadBoundsLocation_ = -1;
    int maximumTextureSize_ = 0;
    std::unordered_map<std::uint64_t, Texture> textures_;
    std::int64_t textureBudget_ = INT64_MAX;
    std::vector<GlSceneEntry> scene_;
    std::vector<PendingFrame> pendingFrames_;
    bool hasReadbackRequest(std::int64_t token) const noexcept;
    void issueReadback(const GlViewerFrame& frame, EGLuint64KHR frameId) noexcept;
    void failReadback(const GlViewerFrame& frame, GlReadbackStatus status) noexcept;
    void completeReadbackSwap(
        std::int64_t token,
        bool swapSucceeded,
        bool contextLost) noexcept;
    void cancelReadbacks(GlReadbackStatus status) noexcept;
    void markContextLostReadbacks() noexcept;
    void contextDestroyedReadbacks() noexcept;
    bool bindOwnerThread() noexcept;
    bool onOwnerThread() const noexcept;
    std::uint64_t nextTextureKey_ = 1;
    std::int64_t sceneKey_ = 0;
    int sceneUnitsPerPixel_ = 1;
    float frameRate_ = 0.0F;
    PFNEGLGETNEXTFRAMEIDANDROIDPROC getNextFrameId_ = nullptr;
    PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROIDPROC getFrameTimestampSupported_ = nullptr;
    PFNEGLGETFRAMETIMESTAMPSANDROIDPROC getFrameTimestamps_ = nullptr;
    EGLint presentationTimestamp_ = EGL_NONE;
    EGLint lastContextError_ = EGL_SUCCESS;
    bool contextLost_ = false;
    bool verificationGlContextLoss_ = false;
    std::int64_t lastSubmittedToken_ = 0;
    std::thread::id ownerThread_{};
    GlStripReadback readback_;
};
