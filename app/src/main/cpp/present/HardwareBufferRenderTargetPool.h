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
    // A tall rolling buffer is roughly 32 MiB on the emulator. Crop-only motion never replaces its
    // buffer; only a sealed band change does. Current + one fully rendered successor therefore
    // cover the exact pipeline, and the successor waits for the current release before another
    // replacement. Four eager bands pushed ART across native-allocation pressure and caused GC
    // pauses during otherwise O(1) geometry frames.
    static constexpr std::size_t kColdEagerSlotCount = 4;
    static constexpr std::size_t kCpuBandEagerSlotCount = 2;
    static constexpr std::size_t kTallBandEagerSlotCount = 2;
    static constexpr std::uint32_t kTallBandHeightThreshold = 8192;

    enum class SlotState : std::uint8_t {
        FREE = 0,
        RENDERING = 1,
        ACQUIRE_FENCE_EXPORTED = 2,
        FRAMEWORK_CHAIN_HEAD = 3,
        FRAMEWORK_REPLACED_WAIT_RELEASE = 4,
        /** CPU pixels are being produced by the dedicated band-composition worker. */
        PRECOMPOSING = 5,
    };

    struct RenderTarget {
        std::uint64_t slot = 0;
        std::uint64_t generation = 0;
        AHardwareBuffer* hardwareBuffer = nullptr;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint renderbuffer = 0;
        GLuint framebuffer = 0;
        SlotState state = SlotState::FREE;
        /** CPU unlock returned no asynchronous completion fence. */
        bool readyWithoutAcquireFence = false;
    };

    struct BufferIdentity {
        std::uint64_t slot = 0;
        std::uint64_t generation = 0;
    };

    HardwareBufferRenderTargetPool() = default;
    ~HardwareBufferRenderTargetPool();

    HardwareBufferRenderTargetPool(const HardwareBufferRenderTargetPool&) = delete;
    HardwareBufferRenderTargetPool& operator=(const HardwareBufferRenderTargetPool&) = delete;

    bool initialize(
        EGLDisplay display,
        std::uint32_t width,
        std::uint32_t height,
        bool cpuComposerOnly = false);
    void destroy();

    RenderTarget* acquireForRendering();
    bool bindForRendering(RenderTarget& target);
    bool lockForCpuWrite(
        RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels);
    /**
     * Unmaps a CPU-written target and transfers the real completion sync_file, when supplied by
     * gralloc, to the caller. A non-negative descriptor must be passed to SurfaceControl as the
     * acquire fence (or waited and closed before the target is reused).
     */
    bool finishCpuWrite(RenderTarget& target, int* completionFenceFd);
    bool beginCpuPrecomposition(RenderTarget& target);
    bool lockCpuPrecompositionOffThread(
        RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels);
    bool finishCpuPrecompositionOffThread(
        RenderTarget& target,
        int* completionFenceFd);
    bool publishFinishedCpuPrecomposition(
        RenderTarget& target,
        bool readyWithoutAcquireFence);
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
    bool cpuComposerOnly() const noexcept { return cpuComposerOnly_; }
    std::size_t allocatedTargetCount() const noexcept;
    /**
     * Renderer-lifecycle-only access for priming context-local framebuffer containers. This does
     * not acquire or change the slot; callers must not retain it across pool destruction.
     */
    RenderTarget* allocatedTargetAt(std::size_t index) noexcept;
    bool hasFreeRenderTarget() const noexcept;
    bool allFree() const noexcept;
    std::array<SlotState, kSlotCount> stateSnapshot() const noexcept;

private:
    friend struct HardwareBufferRenderTargetPoolTestAccess;
    using HardwareBufferIsSupported = int (*)(const AHardwareBuffer_Desc*);
    using HardwareBufferAllocate = int (*)(
        const AHardwareBuffer_Desc*, AHardwareBuffer**);
    using HardwareBufferRelease = void (*)(AHardwareBuffer*);
    using HardwareBufferDescribe = void (*)(
        const AHardwareBuffer*, AHardwareBuffer_Desc*);
    using HardwareBufferLock = int (*)(
        AHardwareBuffer*, std::uint64_t, std::int32_t, const ARect*, void**);
    using HardwareBufferUnlock = int (*)(AHardwareBuffer*, std::int32_t*);

    bool createTarget(RenderTarget& target, std::uint64_t slot);

    void* androidLibrary_ = nullptr;
    HardwareBufferIsSupported hardwareBufferIsSupported_ = nullptr;
    HardwareBufferAllocate hardwareBufferAllocate_ = nullptr;
    HardwareBufferRelease hardwareBufferRelease_ = nullptr;
    HardwareBufferDescribe hardwareBufferDescribe_ = nullptr;
    HardwareBufferLock hardwareBufferLock_ = nullptr;
    HardwareBufferUnlock hardwareBufferUnlock_ = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
    PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC imageRenderbufferStorage_ = nullptr;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    bool cpuComposerOnly_ = false;
    bool initialized_ = false;
    std::size_t allocationLimit_ = kSlotCount;
    std::array<RenderTarget, kSlotCount> targets_{};
};

}  // namespace ntk::present
