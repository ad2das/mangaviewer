#pragma once

#include "FixedPresentEventContract.h"

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <array>
#include <cstdint>
#include <optional>

namespace ntk::present {

struct HardwareBufferRenderTargetPoolTestAccess;

class HardwareBufferRenderTargetPool final {
public:
    static constexpr std::size_t kSlotCount = 8;
    // Two directly submitted frames plus a chain head and one release-waiting
    // predecessor are sufficient for the rolling reader's steady state. Keep
    // the eight-slot ownership ledger, but avoid allocating every full-screen
    // AHardwareBuffer on the cold viewer-open path.
    static constexpr std::size_t kColdEagerSlotCount = 4;

    enum class SlotState : std::uint8_t {
        FREE = 0,
        RENDERING = 1,
        ACQUIRE_FENCE_EXPORTED = 2,
        FRAMEWORK_CHAIN_HEAD = 3,
        FRAMEWORK_REPLACED_WAIT_RELEASE = 4,
    };

    struct RenderTarget {
        std::uint64_t slot = 0;
        std::uint64_t generation = 0;
        AHardwareBuffer* hardwareBuffer = nullptr;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint renderbuffer = 0;
        GLuint framebuffer = 0;
        SlotState state = SlotState::FREE;
    };

    struct BufferIdentity {
        std::uint64_t slot = 0;
        std::uint64_t generation = 0;
    };

    HardwareBufferRenderTargetPool() = default;
    ~HardwareBufferRenderTargetPool();

    HardwareBufferRenderTargetPool(const HardwareBufferRenderTargetPool&) = delete;
    HardwareBufferRenderTargetPool& operator=(const HardwareBufferRenderTargetPool&) = delete;

    bool initialize(EGLDisplay display, std::uint32_t width, std::uint32_t height);
    void destroy();

    RenderTarget* acquireForRendering();
    bool bindForRendering(RenderTarget& target);
    bool markAcquireFenceExported(RenderTarget& target);
    bool commitSubmissionPair(
        std::uint64_t newSlot,
        std::uint64_t newGeneration,
        std::optional<BufferIdentity> previous);
    bool rollbackSubmissionPairBeforeApply(
        std::uint64_t newSlot,
        std::uint64_t newGeneration,
        std::optional<BufferIdentity> previous);
    bool markReleaseWait(std::uint64_t slot, std::uint64_t generation);
    bool markReleased(std::uint64_t slot, std::uint64_t generation);
    bool abortBeforeSubmission(std::uint64_t slot, std::uint64_t generation);
    RenderTarget* find(std::uint64_t slot, std::uint64_t generation);
    const RenderTarget* find(std::uint64_t slot, std::uint64_t generation) const;

    std::uint32_t width() const noexcept { return width_; }
    std::uint32_t height() const noexcept { return height_; }
    bool initialized() const noexcept { return initialized_; }
    std::size_t allocatedTargetCount() const noexcept;
    bool hasFreeRenderTarget() const noexcept;
    bool allFree() const noexcept;
    std::array<SlotState, kSlotCount> stateSnapshot() const noexcept;

private:
    friend struct HardwareBufferRenderTargetPoolTestAccess;
    using HardwareBufferIsSupported = int (*)(const AHardwareBuffer_Desc*);
    using HardwareBufferAllocate = int (*)(
        const AHardwareBuffer_Desc*, AHardwareBuffer**);
    using HardwareBufferRelease = void (*)(AHardwareBuffer*);

    bool createTarget(RenderTarget& target, std::uint64_t slot);

    void* androidLibrary_ = nullptr;
    HardwareBufferIsSupported hardwareBufferIsSupported_ = nullptr;
    HardwareBufferAllocate hardwareBufferAllocate_ = nullptr;
    HardwareBufferRelease hardwareBufferRelease_ = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
    PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC imageRenderbufferStorage_ = nullptr;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    bool initialized_ = false;
    std::array<RenderTarget, kSlotCount> targets_{};
};

}  // namespace ntk::present
