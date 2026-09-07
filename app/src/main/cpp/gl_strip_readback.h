#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>

enum class GlReadbackStatus : std::uint64_t {
    kOk = 1,
    kCancelled = 2,
    kContextLost = 3,
    kGlError = 4,
    kSwapFailed = 5,
};

struct GlReadbackCounts final {
    std::int64_t pendingRequests = 0;
    std::int64_t pendingGpuCaptures = 0;
    std::int64_t retainedTerminalPackets = 0;
    std::int64_t livePboBytes = 0;
    std::int64_t liveFenceCount = 0;
};

struct GlReadbackPacket final {
    static constexpr std::size_t kHeaderBytes = 16U * sizeof(std::uint64_t);

    std::array<std::uint8_t, kHeaderBytes> inlineBytes{};
    std::unique_ptr<std::uint8_t[]> heapBytes;
    std::size_t size = 0;

    const std::uint8_t* data() const noexcept {
        return heapBytes == nullptr ? inlineBytes.data() : heapBytes.get();
    }
};

/** Asynchronous exact-row readback from the current default framebuffer. */
class GlStripReadback final {
public:
    static constexpr std::uint64_t kMaximumCaptureBytes = 16ULL * 1024ULL * 1024ULL;
    static constexpr std::size_t kMaximumCaptures = 2U;

    GlStripReadback() = default;
    ~GlStripReadback() = default;

    GlStripReadback(const GlStripReadback&) = delete;
    GlStripReadback& operator=(const GlStripReadback&) = delete;

    bool request(
        std::int64_t token,
        std::int64_t sessionId,
        std::int64_t rendererEpoch,
        std::int64_t surfaceEpoch,
        int top,
        int bottom) noexcept;
    bool hasRequest(std::int64_t token) const noexcept;

    void issue(
        std::int64_t token,
        int width,
        int height,
        EGLuint64KHR eglFrameId) noexcept;
    void fail(
        std::int64_t token,
        int width,
        GlReadbackStatus status) noexcept;
    void completeSwap(
        std::int64_t token,
        bool swapSucceeded,
        bool contextLost) noexcept;

    std::optional<GlReadbackPacket> take(std::int64_t token);
    GlReadbackCounts counts() const noexcept;

    // Detach keeps in-flight GPU objects owned until a later nonblocking poll.
    void cancel(GlReadbackStatus status) noexcept;
    // Context loss marks captures first; contextDestroyed settles them without old-context GL calls.
    void markContextLost() noexcept;
    void contextDestroyed() noexcept;

private:
    struct Request final {
        std::int64_t token = 0;
        std::int64_t sessionId = 0;
        std::int64_t rendererEpoch = 0;
        std::int64_t surfaceEpoch = 0;
        int top = 0;
        int bottom = 0;
    };

    struct Capture final {
        Request request{};
        int width = 0;
        int surfaceHeight = 0;
        std::uint64_t byteCount = 0;
        GLuint pbo = 0;
        GLsync fence = nullptr;
        EGLuint64KHR eglFrameId = 0;
        std::int64_t captureIssuedNanos = 0;
        std::int64_t swapCompletedNanos = 0;
        GlReadbackStatus status = GlReadbackStatus::kOk;
        bool swapKnown = false;
        bool requiresContextDestroy = false;
    };

    struct RequestSlot final {
        bool used = false;
        Request value{};
    };

    struct CaptureSlot final {
        bool used = false;
        Capture value{};
    };

    struct TerminalSlot final {
        bool used = false;
        Request request{};
        std::array<std::uint8_t, GlReadbackPacket::kHeaderBytes> packet{};
    };

    static int findRequest(
        const std::array<RequestSlot, kMaximumCaptures>& slots,
        std::int64_t token) noexcept;
    static int findCapture(
        const std::array<CaptureSlot, kMaximumCaptures>& slots,
        std::int64_t token) noexcept;
    static int findTerminal(
        const std::array<TerminalSlot, kMaximumCaptures>& slots,
        std::int64_t token) noexcept;
    static int freeRequest(
        const std::array<RequestSlot, kMaximumCaptures>& slots) noexcept;
    static int freeCapture(
        const std::array<CaptureSlot, kMaximumCaptures>& slots) noexcept;
    static int freeTerminal(
        const std::array<TerminalSlot, kMaximumCaptures>& slots) noexcept;

    void retainFailure(
        const Request& request,
        int width,
        EGLuint64KHR eglFrameId,
        GlReadbackStatus status,
        std::int64_t captureIssuedNanos,
        std::int64_t captureReadyNanos,
        std::int64_t swapCompletedNanos) noexcept;
    static void writeHeader(
        std::uint8_t* destination,
        const Request& request,
        int width,
        EGLuint64KHR eglFrameId,
        GlReadbackStatus status,
        std::int64_t captureIssuedNanos,
        std::int64_t captureReadyNanos,
        std::int64_t swapCompletedNanos,
        std::uint64_t rgbaByteCount) noexcept;
    void updateTerminalSwapTime(std::int64_t token, std::int64_t swapCompletedNanos) noexcept;
    void destroyCapture(Capture* capture) noexcept;
    bool fenceSignaled(Capture* capture) noexcept;
    bool allocateGpuCapture(Capture* capture) noexcept;
    bool mapCapturePixels(Capture* capture, std::uint8_t* rgba) noexcept;
    std::optional<GlReadbackPacket> takeCapture(Capture* capture);
    std::uint64_t livePboBytes() const noexcept;
    std::int64_t ticketCount() const noexcept;

    std::array<RequestSlot, kMaximumCaptures> requests_{};
    std::array<CaptureSlot, kMaximumCaptures> captures_{};
    std::array<TerminalSlot, kMaximumCaptures> terminalPackets_{};
};
