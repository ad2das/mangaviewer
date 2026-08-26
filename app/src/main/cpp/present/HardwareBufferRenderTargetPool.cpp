#include "HardwareBufferRenderTargetPool.h"

#include <GLES2/gl2ext.h>

#include <dlfcn.h>
#include <unistd.h>

namespace ntk::present {

HardwareBufferRenderTargetPool::~HardwareBufferRenderTargetPool() {
    destroy();
}

bool HardwareBufferRenderTargetPool::allFree() const noexcept {
    for (const auto& target : targets_) {
        if (target.state != SlotState::FREE) return false;
    }
    return true;
}

bool HardwareBufferRenderTargetPool::hasFreeRenderTarget() const noexcept {
    if (!initialized_) return false;
    for (const auto& target : targets_) {
        if (target.state == SlotState::FREE) return true;
    }
    return false;
}

std::size_t HardwareBufferRenderTargetPool::allocatedTargetCount() const noexcept {
    std::size_t count = 0;
    for (const auto& target : targets_) {
        if (target.hardwareBuffer != nullptr || target.framebuffer != 0) ++count;
    }
    return count;
}

HardwareBufferRenderTargetPool::RenderTarget*
HardwareBufferRenderTargetPool::allocatedTargetAt(std::size_t index) noexcept {
    if (!initialized_ || index >= targets_.size()) return nullptr;
    auto& target = targets_[index];
    return target.hardwareBuffer != nullptr && target.renderbuffer != 0
        ? &target
        : nullptr;
}

std::array<HardwareBufferRenderTargetPool::SlotState,
           HardwareBufferRenderTargetPool::kSlotCount>
HardwareBufferRenderTargetPool::stateSnapshot() const noexcept {
    std::array<SlotState, kSlotCount> states{};
    for (std::size_t index = 0; index < targets_.size(); ++index) {
        states[index] = targets_[index].state;
    }
    return states;
}

bool HardwareBufferRenderTargetPool::initialize(
        EGLDisplay display,
        std::uint32_t width,
        std::uint32_t height,
        bool cpuComposerOnly) {
    if (initialized_ || display == EGL_NO_DISPLAY || width == 0 || height == 0) {
        return false;
    }
    display_ = display;
    width_ = width;
    height_ = height;
    cpuComposerOnly_ = cpuComposerOnly;
    androidLibrary_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    hardwareBufferIsSupported_ = reinterpret_cast<HardwareBufferIsSupported>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_isSupported") : nullptr);
    hardwareBufferAllocate_ = reinterpret_cast<HardwareBufferAllocate>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_allocate") : nullptr);
    hardwareBufferRelease_ = reinterpret_cast<HardwareBufferRelease>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_release") : nullptr);
    hardwareBufferDescribe_ = reinterpret_cast<HardwareBufferDescribe>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_describe") : nullptr);
    hardwareBufferLock_ = reinterpret_cast<HardwareBufferLock>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_lock") : nullptr);
    hardwareBufferUnlock_ = reinterpret_cast<HardwareBufferUnlock>(
        androidLibrary_ != nullptr
            ? dlsym(androidLibrary_, "AHardwareBuffer_unlock") : nullptr);
    getNativeClientBuffer_ =
        reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    createImage_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
        eglGetProcAddress("eglCreateImageKHR"));
    destroyImage_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
        eglGetProcAddress("eglDestroyImageKHR"));
    imageRenderbufferStorage_ =
        reinterpret_cast<PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC>(
            eglGetProcAddress("glEGLImageTargetRenderbufferStorageOES"));
    if (hardwareBufferIsSupported_ == nullptr ||
        hardwareBufferAllocate_ == nullptr ||
        hardwareBufferRelease_ == nullptr) {
        destroy();
        return false;
    }
    if (!cpuComposerOnly_ &&
        (getNativeClientBuffer_ == nullptr || createImage_ == nullptr ||
         destroyImage_ == nullptr || imageRenderbufferStorage_ == nullptr)) {
        destroy();
        return false;
    }
    if (hardwareBufferDescribe_ == nullptr || hardwareBufferLock_ == nullptr ||
        hardwareBufferUnlock_ == nullptr) {
        destroy();
        return false;
    }
    for (std::size_t index = 0; index < targets_.size(); ++index) {
        targets_[index].slot = static_cast<std::uint64_t>(index);
        targets_[index].generation = 0;
        targets_[index].state = SlotState::FREE;
    }
    // A tall rolling band is tens of MiB per slot. Finish its bounded allocation before the
    // interactive surface is published: a lazy slot creation from acquireForRendering() can spend
    // more than a second in emulator gralloc/EGLImage creation and blocks the same owner that must
    // drain crop transactions and release callbacks.
    const std::size_t eagerSlotCount = cpuComposerOnly_
        ? kCpuBandEagerSlotCount
        : (height_ >= kTallBandHeightThreshold
            ? kTallBandEagerSlotCount
            : kColdEagerSlotCount);
    allocationLimit_ = !cpuComposerOnly_ && height_ >= kTallBandHeightThreshold
        ? kTallBandEagerSlotCount
        : kSlotCount;
    for (std::size_t index = 0; index < eagerSlotCount; ++index) {
        if (!createTarget(targets_[index], static_cast<std::uint64_t>(index))) {
            destroy();
            return false;
        }
    }
    initialized_ = true;
    return true;
}

bool HardwareBufferRenderTargetPool::createTarget(
        RenderTarget& target, std::uint64_t slot) {
    AHardwareBuffer_Desc descriptor{};
    descriptor.width = width_;
    descriptor.height = height_;
    descriptor.layers = 1;
    descriptor.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    descriptor.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    if (!cpuComposerOnly_) {
        descriptor.usage |= AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    }
    AHardwareBuffer* hardwareBuffer = nullptr;
    if (hardwareBufferIsSupported_(&descriptor) != 1 ||
        hardwareBufferAllocate_(&descriptor, &hardwareBuffer) != 0 ||
        hardwareBuffer == nullptr) {
        return false;
    }
    if (cpuComposerOnly_) {
        target.hardwareBuffer = hardwareBuffer;
        target.image = EGL_NO_IMAGE_KHR;
        target.renderbuffer = 0;
        target.framebuffer = 0;
        target.slot = slot;
        target.generation = 0;
        target.state = SlotState::FREE;
        target.readyWithoutAcquireFence = false;
        return true;
    }
    EGLClientBuffer clientBuffer =
        getNativeClientBuffer_(hardwareBuffer);
    if (clientBuffer == nullptr) {
        hardwareBufferRelease_(hardwareBuffer);
        return false;
    }
    constexpr EGLint imageAttributes[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE,
    };
    EGLImageKHR image = createImage_(
        display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer, imageAttributes);
    if (image == EGL_NO_IMAGE_KHR) {
        hardwareBufferRelease_(hardwareBuffer);
        return false;
    }

    GLuint renderbuffer = 0;
    GLuint framebuffer = 0;
    glGenRenderbuffers(1, &renderbuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
    imageRenderbufferStorage_(
        GL_RENDERBUFFER, reinterpret_cast<GLeglImageOES>(image));
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferRenderbuffer(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
        GL_RENDERBUFFER, renderbuffer);
    const bool complete =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE &&
        glGetError() == GL_NO_ERROR;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    if (!complete) {
        if (framebuffer != 0) glDeleteFramebuffers(1, &framebuffer);
        if (renderbuffer != 0) glDeleteRenderbuffers(1, &renderbuffer);
        destroyImage_(display_, image);
        hardwareBufferRelease_(hardwareBuffer);
        return false;
    }
    target.hardwareBuffer = hardwareBuffer;
    target.image = image;
    target.renderbuffer = renderbuffer;
    target.framebuffer = framebuffer;
    target.slot = slot;
    target.generation = 0;
    target.state = SlotState::FREE;
    target.readyWithoutAcquireFence = false;
    return true;
}

void HardwareBufferRenderTargetPool::destroy() {
    for (auto& target : targets_) {
        if (target.framebuffer != 0) {
            glDeleteFramebuffers(1, &target.framebuffer);
            target.framebuffer = 0;
        }
        if (target.renderbuffer != 0) {
            glDeleteRenderbuffers(1, &target.renderbuffer);
            target.renderbuffer = 0;
        }
        if (target.image != EGL_NO_IMAGE_KHR && display_ != EGL_NO_DISPLAY) {
            destroyImage_(display_, target.image);
            target.image = EGL_NO_IMAGE_KHR;
        }
        if (target.hardwareBuffer != nullptr) {
            hardwareBufferRelease_(target.hardwareBuffer);
            target.hardwareBuffer = nullptr;
        }
        target.state = SlotState::FREE;
        target.readyWithoutAcquireFence = false;
    }
    initialized_ = false;
    display_ = EGL_NO_DISPLAY;
    width_ = 0;
    height_ = 0;
    cpuComposerOnly_ = false;
    allocationLimit_ = kSlotCount;
    hardwareBufferIsSupported_ = nullptr;
    hardwareBufferAllocate_ = nullptr;
    hardwareBufferRelease_ = nullptr;
    hardwareBufferDescribe_ = nullptr;
    hardwareBufferLock_ = nullptr;
    hardwareBufferUnlock_ = nullptr;
    getNativeClientBuffer_ = nullptr;
    createImage_ = nullptr;
    destroyImage_ = nullptr;
    imageRenderbufferStorage_ = nullptr;
    if (androidLibrary_ != nullptr) {
        dlclose(androidLibrary_);
        androidLibrary_ = nullptr;
    }
}

HardwareBufferRenderTargetPool::RenderTarget*
HardwareBufferRenderTargetPool::acquireForRendering() {
    if (!initialized_) return nullptr;
    for (std::size_t index = 0; index < allocationLimit_; ++index) {
        auto& target = targets_[index];
        if (target.state != SlotState::FREE) continue;
        if (target.hardwareBuffer == nullptr && target.framebuffer == 0 &&
            !createTarget(target, target.slot)) {
            return nullptr;
        }
        ++target.generation;
        if (target.generation == 0) ++target.generation;
        target.state = SlotState::RENDERING;
        target.readyWithoutAcquireFence = false;
        return &target;
    }
    return nullptr;
}

bool HardwareBufferRenderTargetPool::bindForRendering(RenderTarget& target) {
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || cpuComposerOnly_ || owned != &target ||
        target.state != SlotState::RENDERING ||
        target.framebuffer == 0) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
    glViewport(0, 0, static_cast<GLsizei>(width_), static_cast<GLsizei>(height_));
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE &&
        glGetError() == GL_NO_ERROR;
}

bool HardwareBufferRenderTargetPool::lockForCpuWrite(
        RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels) {
    if (pixels) *pixels = nullptr;
    if (stridePixels) *stridePixels = 0;
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || owned != &target || pixels == nullptr ||
        stridePixels == nullptr || target.state != SlotState::RENDERING ||
        target.readyWithoutAcquireFence || target.hardwareBuffer == nullptr ||
        hardwareBufferDescribe_ == nullptr || hardwareBufferLock_ == nullptr) {
        return false;
    }
    AHardwareBuffer_Desc descriptor{};
    hardwareBufferDescribe_(target.hardwareBuffer, &descriptor);
    if (descriptor.width != width_ || descriptor.height != height_ ||
        descriptor.layers != 1 ||
        descriptor.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
        descriptor.stride < width_) {
        return false;
    }
    const ARect bounds{
        0, 0,
        static_cast<std::int32_t>(width_),
        static_cast<std::int32_t>(height_),
    };
    void* mapped = nullptr;
    if (hardwareBufferLock_(
            target.hardwareBuffer,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1,
            &bounds,
            &mapped) != 0 || mapped == nullptr) {
        return false;
    }
    *pixels = mapped;
    *stridePixels = descriptor.stride;
    return true;
}

bool HardwareBufferRenderTargetPool::finishCpuWrite(
        RenderTarget& target,
        int* completionFenceFd) {
    if (completionFenceFd) *completionFenceFd = -1;
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || owned != &target ||
        target.state != SlotState::RENDERING ||
        target.readyWithoutAcquireFence || hardwareBufferUnlock_ == nullptr ||
        completionFenceFd == nullptr) {
        return false;
    }
    int completionFd = -1;
    if (hardwareBufferUnlock_(target.hardwareBuffer, &completionFd) != 0) {
        if (completionFd >= 0) close(completionFd);
        return false;
    }
    // AHardwareBuffer_unlock may finish synchronously (-1) or transfer a real sync_file. Do not
    // wait here: SurfaceControl is the consumer and its acquire-fence contract safely overlaps the
    // host gralloc flush with transaction preparation and the compositor pipeline.
    target.readyWithoutAcquireFence = completionFd < 0;
    target.state = SlotState::ACQUIRE_FENCE_EXPORTED;
    *completionFenceFd = completionFd;
    return true;
}

bool HardwareBufferRenderTargetPool::beginCpuPrecomposition(RenderTarget& target) {
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || owned != &target ||
        target.state != SlotState::RENDERING ||
        target.readyWithoutAcquireFence || target.hardwareBuffer == nullptr ||
        hardwareBufferUnlock_ == nullptr) {
        return false;
    }
    target.state = SlotState::PRECOMPOSING;
    return true;
}

bool HardwareBufferRenderTargetPool::lockCpuPrecompositionOffThread(
        RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels) {
    if (pixels) *pixels = nullptr;
    if (stridePixels) *stridePixels = 0;
    if (!initialized_ || target.state != SlotState::PRECOMPOSING ||
        target.readyWithoutAcquireFence || target.hardwareBuffer == nullptr ||
        pixels == nullptr || stridePixels == nullptr ||
        hardwareBufferDescribe_ == nullptr || hardwareBufferLock_ == nullptr) {
        return false;
    }
    AHardwareBuffer_Desc descriptor{};
    hardwareBufferDescribe_(target.hardwareBuffer, &descriptor);
    if (descriptor.width != width_ || descriptor.height != height_ ||
        descriptor.layers != 1 ||
        descriptor.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
        descriptor.stride < width_) {
        return false;
    }
    const ARect bounds{
        0, 0,
        static_cast<std::int32_t>(width_),
        static_cast<std::int32_t>(height_),
    };
    void* mapped = nullptr;
    if (hardwareBufferLock_(
            target.hardwareBuffer,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1,
            &bounds,
            &mapped) != 0 || mapped == nullptr) {
        return false;
    }
    *pixels = mapped;
    *stridePixels = descriptor.stride;
    return true;
}

bool HardwareBufferRenderTargetPool::finishCpuPrecompositionOffThread(
        RenderTarget& target,
        int* completionFenceFd) {
    if (completionFenceFd) *completionFenceFd = -1;
    // PRECOMPOSING is installed by the renderer owner before the worker is published and remains
    // immutable until that worker reports completion. The worker touches only mapped pixels and
    // AHardwareBuffer_unlock; every ownership-state transition stays on the renderer owner.
    if (!initialized_ || target.state != SlotState::PRECOMPOSING ||
        target.readyWithoutAcquireFence || target.hardwareBuffer == nullptr ||
        hardwareBufferUnlock_ == nullptr || completionFenceFd == nullptr) {
        return false;
    }
    int completionFd = -1;
    if (hardwareBufferUnlock_(target.hardwareBuffer, &completionFd) != 0) {
        if (completionFd >= 0) close(completionFd);
        return false;
    }
    *completionFenceFd = completionFd;
    return true;
}

bool HardwareBufferRenderTargetPool::publishFinishedCpuPrecomposition(
        RenderTarget& target,
        bool readyWithoutAcquireFence) {
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || owned != &target ||
        target.state != SlotState::PRECOMPOSING ||
        target.readyWithoutAcquireFence) {
        return false;
    }
    target.readyWithoutAcquireFence = readyWithoutAcquireFence;
    target.state = SlotState::ACQUIRE_FENCE_EXPORTED;
    return true;
}

bool HardwareBufferRenderTargetPool::markAcquireFenceExported(
        RenderTarget& target) {
    RenderTarget* owned = find(target.slot, target.generation);
    if (!initialized_ || owned != &target ||
        target.state != SlotState::RENDERING) return false;
    target.readyWithoutAcquireFence = false;
    target.state = SlotState::ACQUIRE_FENCE_EXPORTED;
    return true;
}

HardwareBufferRenderTargetPool::RenderTarget*
HardwareBufferRenderTargetPool::find(std::uint64_t slot, std::uint64_t generation) {
    if (slot >= targets_.size()) return nullptr;
    RenderTarget& target = targets_[static_cast<std::size_t>(slot)];
    return target.generation == generation ? &target : nullptr;
}

const HardwareBufferRenderTargetPool::RenderTarget*
HardwareBufferRenderTargetPool::find(
        std::uint64_t slot, std::uint64_t generation) const {
    if (slot >= targets_.size()) return nullptr;
    const RenderTarget& target = targets_[static_cast<std::size_t>(slot)];
    return target.generation == generation ? &target : nullptr;
}

bool HardwareBufferRenderTargetPool::commitSubmissionPair(
        std::uint64_t newSlot,
        std::uint64_t newGeneration,
        std::optional<BufferIdentity> previous) {
    if (!initialized_) return false;
    RenderTarget* next = find(newSlot, newGeneration);
    if (next == nullptr ||
        next->state != SlotState::ACQUIRE_FENCE_EXPORTED) return false;
    RenderTarget* prior = nullptr;
    if (previous.has_value()) {
        if (previous->slot == newSlot &&
            previous->generation == newGeneration) {
            return false;
        }
        prior = find(previous->slot, previous->generation);
        if (prior == nullptr ||
            prior->state != SlotState::FRAMEWORK_CHAIN_HEAD) {
            return false;
        }
    }
    next->state = SlotState::FRAMEWORK_CHAIN_HEAD;
    if (prior != nullptr) {
        prior->state = SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE;
    }
    return true;
}

bool HardwareBufferRenderTargetPool::rollbackSubmissionPairBeforeApply(
        std::uint64_t newSlot,
        std::uint64_t newGeneration,
        std::optional<BufferIdentity> previous) {
    if (!initialized_) return false;
    RenderTarget* next = find(newSlot, newGeneration);
    if (next == nullptr ||
        next->state != SlotState::FRAMEWORK_CHAIN_HEAD) {
        return false;
    }
    RenderTarget* prior = nullptr;
    if (previous.has_value()) {
        prior = find(previous->slot, previous->generation);
        if (prior == nullptr ||
            prior->state !=
                SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE) {
            return false;
        }
    }
    next->state = SlotState::ACQUIRE_FENCE_EXPORTED;
    if (prior != nullptr) prior->state = SlotState::FRAMEWORK_CHAIN_HEAD;
    return true;
}

bool HardwareBufferRenderTargetPool::markReleaseWait(
        std::uint64_t slot, std::uint64_t generation) {
    if (!initialized_) return false;
    RenderTarget* target = find(slot, generation);
    if (!target ||
        target->state != SlotState::FRAMEWORK_CHAIN_HEAD) return false;
    target->state = SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE;
    return true;
}

bool HardwareBufferRenderTargetPool::markReleased(
        std::uint64_t slot, std::uint64_t generation) {
    if (!initialized_) return false;
    RenderTarget* target = find(slot, generation);
    if (!target ||
        (target->state != SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE &&
         target->state != SlotState::FRAMEWORK_CHAIN_HEAD)) return false;
    target->state = SlotState::FREE;
    target->readyWithoutAcquireFence = false;
    return true;
}

bool HardwareBufferRenderTargetPool::abortBeforeSubmission(
        std::uint64_t slot, std::uint64_t generation) {
    if (!initialized_) return false;
    RenderTarget* target = find(slot, generation);
    if (!target || (target->state != SlotState::RENDERING &&
                    target->state != SlotState::PRECOMPOSING &&
                    target->state != SlotState::ACQUIRE_FENCE_EXPORTED)) {
        return false;
    }
    target->state = SlotState::FREE;
    target->readyWithoutAcquireFence = false;
    return true;
}

}  // namespace ntk::present
