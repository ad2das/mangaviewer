#include "SurfaceControlPresentBackend.h"
#include "../swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"

#include <android/api-level.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cerrno>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/sync_file.h>
#include <new>
#include <poll.h>
#include <sys/eventfd.h>
#include <time.h>
#include <unistd.h>
#include <utility>

namespace ntk::present {

namespace {
std::atomic<std::uint64_t> gSurfaceSerial{0};
constexpr std::uint32_t kCookiePublicationComplete = 1U << 0U;
constexpr std::uint32_t kCookieRecordConsumed = 1U << 1U;
constexpr std::uint32_t kCookiePrivateCompleteObserved = 1U << 2U;

void releaseProvidedSurfaceControl(ASurfaceControl* surface) noexcept {
    if (surface == nullptr) return;
    using ReleaseSurface = void (*)(ASurfaceControl*);
    void* library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    auto releaseSurface = reinterpret_cast<ReleaseSurface>(
        library != nullptr ? dlsym(library, "ASurfaceControl_release") : nullptr);
    if (releaseSurface != nullptr) releaseSurface(surface);
    if (library != nullptr) dlclose(library);
}

bool setCloseOnExec(int fd) noexcept {
    if (fd < 0) return false;
    const int flags = fcntl(fd, F_GETFD);
    return flags >= 0 && fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0;
}

bool exactAcquireFenceSignal(
        int fd, std::int64_t observedNanos,
        SurfaceControlPresentBackend::SyncFileInfoFn syncFileInfo,
        SurfaceControlPresentBackend::SyncFileInfoFreeFn syncFileInfoFree,
        std::int64_t* signalNanos) noexcept {
    if (fd < 0 || observedNanos <= 0 || syncFileInfo == nullptr ||
        syncFileInfoFree == nullptr || signalNanos == nullptr) return false;
    *signalNanos = 0;
    struct sync_file_info* info = syncFileInfo(fd);
    if (info == nullptr) return false;
    bool exact = info->status == 1 && info->num_fences > 0;
    std::uint64_t latest = 0;
    const auto* children = reinterpret_cast<const struct sync_fence_info*>(
        static_cast<std::uintptr_t>(info->sync_fence_info));
    if (children == nullptr) exact = false;
    for (std::uint32_t index = 0; exact && index < info->num_fences; ++index) {
        const struct sync_fence_info& child = children[index];
        if (child.status != 1 || child.timestamp_ns == 0) {
            exact = false;
            break;
        }
        latest = std::max(
            latest, static_cast<std::uint64_t>(child.timestamp_ns));
    }
    syncFileInfoFree(info);
    if (!exact || latest == 0 ||
        latest > static_cast<std::uint64_t>(observedNanos)) return false;
    *signalNanos = static_cast<std::int64_t>(latest);
    return true;
}

SwappyFixedFrameIdentityV1 toSwappyIdentity(
        const FixedFrameIdentity& identity) noexcept {
    SwappyFixedFrameIdentityV1 result{};
    result.structSize = sizeof(result);
    result.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
    result.engineGeneration = identity.engineGeneration;
    result.surfaceEpoch = identity.surfaceEpoch;
    result.authorityGeneration = identity.authorityGeneration;
    result.authority = identity.authority;
    result.workGeneration = identity.workGeneration;
    result.ntkFrameId = identity.ntkFrameId;
    result.frameSequence = identity.frameSequence;
    result.admissionSequence = identity.admissionSequence;
    result.capsuleSequence = identity.capsuleSequence;
    result.backendSurfaceSerial = identity.backendSurfaceSerial;
    result.transactionSerial = identity.transactionSerial;
    result.bufferSlot = identity.bufferSlot;
    result.bufferGeneration = identity.bufferGeneration;
    result.frameTimelineVsyncId = identity.frameTimelineVsyncId;
    return result;
}

SwappyFixedAppliedBufferRefV1 toSwappyAppliedBufferRef(
        const AppliedBufferRef& ref) noexcept {
    if (!validAppliedBufferRef(ref)) {
        return swappy::emptyFixedAppliedBufferRef();
    }
    SwappyFixedAppliedBufferRefV1 result{};
    result.structSize = sizeof(result);
    result.version = SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION;
    result.appliedBufferRefSerial = ref.serial;
    result.identity = toSwappyIdentity(ref.identity);
    return result;
}

}

bool SurfaceControlPresentBackend::SurfaceApi::complete() const noexcept {
    return createFromWindow != nullptr && create != nullptr &&
        releaseSurface != nullptr &&
        createTransaction != nullptr && deleteTransaction != nullptr &&
        applyTransaction != nullptr && setOnComplete != nullptr &&
        setOnCommit != nullptr && reparent != nullptr &&
        setVisibility != nullptr && setBuffer != nullptr &&
        setGeometry != nullptr && setBufferTransparency != nullptr &&
        setBufferAlpha != nullptr && setColor != nullptr &&
        setEnableBackPressure != nullptr &&
        setFrameTimeline != nullptr && getLatchTime != nullptr &&
        getPresentFenceFd != nullptr &&
        getPreviousReleaseFenceFd != nullptr;
}

SurfaceControlPresentBackend::~SurfaceControlPresentBackend() {
    (void)destroy();
}

std::int64_t SurfaceControlPresentBackend::monotonicNowNanos() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(value.tv_sec) * 1'000'000'000LL +
        static_cast<std::int64_t>(value.tv_nsec);
}

std::uint32_t SurfaceControlPresentBackend::callbackRecordCount() const noexcept {
    std::uint32_t count = 0;
    for (const auto& record : appliedCallbacks_) {
        if (record.has_value()) ++count;
    }
    return count;
}

std::uint32_t SurfaceControlPresentBackend::previousReleaseRecordCount() const noexcept {
    std::uint32_t count = 0;
    for (const auto& record : previousReleases_) {
        if (record.has_value()) ++count;
    }
    return count;
}

std::uint32_t SurfaceControlPresentBackend::acquireFenceRecordCount() const noexcept {
    std::lock_guard<std::mutex> lock(fenceMutex_);
    std::uint32_t count = 0;
    for (const auto& record : acquireFences_) {
        if (record.has_value()) ++count;
    }
    return count;
}

std::uint32_t SurfaceControlPresentBackend::appOwnedAcquireFdCount() const noexcept {
    std::lock_guard<std::mutex> lock(fenceMutex_);
    std::uint32_t count = 0;
    if (localAcquireFence_.has_value()) {
        if (localAcquireFence_->frameworkAcquireFd >= 0) ++count;
        if (localAcquireFence_->proofAcquireFd >= 0) ++count;
    }
    for (const auto& record : acquireFences_) {
        if (record.has_value() && record->proofFd >= 0) ++count;
    }
    return count;
}

SurfaceControlPresentBackend::AppliedCallbackRecord*
SurfaceControlPresentBackend::findAppliedCallbackRecord(
        const FixedFrameIdentity& identity) noexcept {
    for (auto& record : appliedCallbacks_) {
        if (record.has_value() && exactIdentity(record->identity, identity)) {
            return &*record;
        }
    }
    return nullptr;
}

const SurfaceControlPresentBackend::AppliedCallbackRecord*
SurfaceControlPresentBackend::findAppliedCallbackRecord(
        const FixedFrameIdentity& identity) const noexcept {
    for (const auto& record : appliedCallbacks_) {
        if (record.has_value() && exactIdentity(record->identity, identity)) {
            return &*record;
        }
    }
    return nullptr;
}

SurfaceControlPresentBackend::PreviousReleaseRecord*
SurfaceControlPresentBackend::findPreviousReleaseRecord(
        const FixedFrameIdentity& identity) noexcept {
    for (auto& record : previousReleases_) {
        if (record.has_value() && exactIdentity(
                record->replacingTransactionIdentity, identity)) {
            return &*record;
        }
    }
    return nullptr;
}

const SurfaceControlPresentBackend::PreviousReleaseRecord*
SurfaceControlPresentBackend::findPreviousReleaseRecord(
        const FixedFrameIdentity& identity) const noexcept {
    for (const auto& record : previousReleases_) {
        if (record.has_value() && exactIdentity(
                record->replacingTransactionIdentity, identity)) {
            return &*record;
        }
    }
    return nullptr;
}

std::optional<std::size_t>
SurfaceControlPresentBackend::freeAppliedCallbackRecordIndex() const noexcept {
    for (std::size_t i = 0; i < appliedCallbacks_.size(); ++i) {
        if (!appliedCallbacks_[i].has_value()) return i;
    }
    return std::nullopt;
}

std::optional<std::size_t>
SurfaceControlPresentBackend::freePreviousReleaseRecordIndex() const noexcept {
    for (std::size_t i = 0; i < previousReleases_.size(); ++i) {
        if (!previousReleases_[i].has_value()) return i;
    }
    return std::nullopt;
}

std::optional<std::size_t>
SurfaceControlPresentBackend::freeAcquireFenceRecordIndex() const noexcept {
    std::lock_guard<std::mutex> lock(fenceMutex_);
    for (std::size_t i = 0; i < acquireFences_.size(); ++i) {
        if (!acquireFences_[i].has_value()) return i;
    }
    return std::nullopt;
}

bool SurfaceControlPresentBackend::closeAndClearLocalAcquireFence() noexcept {
    std::lock_guard<std::mutex> lock(fenceMutex_);
    if (!localAcquireFence_.has_value()) return true;
    bool exact = true;
    if (localAcquireFence_->frameworkAcquireFd >= 0) {
        exact = close(localAcquireFence_->frameworkAcquireFd) == 0 && exact;
        localAcquireFence_->frameworkAcquireFd = -1;
    }
    if (localAcquireFence_->proofAcquireFd >= 0) {
        exact = close(localAcquireFence_->proofAcquireFd) == 0 && exact;
        localAcquireFence_->proofAcquireFd = -1;
    }
    localAcquireFence_.reset();
    return exact;
}

std::optional<std::size_t>
SurfaceControlPresentBackend::acquireCallbackCookie() noexcept {
    for (std::size_t i = 0; i < callbackCookies_.size(); ++i) {
        bool expected = false;
        if (!callbackCookies_[i].inUse.compare_exchange_strong(
                expected, true, std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            continue;
        }
        SubmissionCookie& cookie = callbackCookies_[i];
        cookie.backend = this;
        cookie.identity = {};
        cookie.previousAppliedBufferRef = {};
        cookie.hasPreviousAppliedBufferRef = false;
        cookie.geometryPulseUpdate = false;
        cookie.geometryPulseBufferIndex = UINT32_MAX;
        cookie.previousGeometryPulseBufferIndex = UINT32_MAX;
        cookie.teardown = false;
        cookie.slotIndex = static_cast<std::uint32_t>(i);
        cookie.onCommitCount.store(0, std::memory_order_release);
        cookie.onCompleteCount.store(0, std::memory_order_release);
        cookie.onCommitEventSequence.store(0, std::memory_order_release);
        cookie.onCompleteEventSequence.store(0, std::memory_order_release);
        cookie.onCommitLatchNanos.store(0, std::memory_order_release);
        cookie.onCommitObservedNanos.store(0, std::memory_order_release);
        cookie.onCompletePresentNanos.store(0, std::memory_order_release);
        cookie.onCompleteObservedNanos.store(0, std::memory_order_release);
        cookie.lifecycleFlags.store(0, std::memory_order_release);
        return i;
    }
    return std::nullopt;
}

bool SurfaceControlPresentBackend::hasFreeCallbackCookie() const noexcept {
    for (const auto& cookie : callbackCookies_) {
        if (!cookie.inUse.load(std::memory_order_acquire)) return true;
    }
    return false;
}

void SurfaceControlPresentBackend::releaseCallbackCookie(
        std::size_t index) noexcept {
    if (index >= callbackCookies_.size()) return;
    SubmissionCookie& cookie = callbackCookies_[index];
    cookie.backend = nullptr;
    cookie.identity = {};
    cookie.previousAppliedBufferRef = {};
    cookie.hasPreviousAppliedBufferRef = false;
    cookie.geometryPulseUpdate = false;
    cookie.geometryPulseBufferIndex = UINT32_MAX;
    cookie.previousGeometryPulseBufferIndex = UINT32_MAX;
    cookie.teardown = false;
    cookie.slotIndex = UINT32_MAX;
    cookie.onCommitCount.store(0, std::memory_order_release);
    cookie.onCompleteCount.store(0, std::memory_order_release);
    cookie.onCommitEventSequence.store(0, std::memory_order_release);
    cookie.onCompleteEventSequence.store(0, std::memory_order_release);
    cookie.onCommitLatchNanos.store(0, std::memory_order_release);
    cookie.onCommitObservedNanos.store(0, std::memory_order_release);
    cookie.onCompletePresentNanos.store(0, std::memory_order_release);
    cookie.onCompleteObservedNanos.store(0, std::memory_order_release);
    cookie.lifecycleFlags.store(0, std::memory_order_release);
    cookie.inUse.store(false, std::memory_order_release);
}

void SurfaceControlPresentBackend::completeCallbackPublication(
        SubmissionCookie& cookie) noexcept {
    const std::uint32_t previous = cookie.lifecycleFlags.fetch_or(
        kCookiePublicationComplete, std::memory_order_acq_rel);
    const bool privateCompleteSatisfied = !cookie.geometryPulseUpdate ||
        (previous & kCookiePrivateCompleteObserved) != 0U;
    if ((previous & kCookieRecordConsumed) != 0U &&
        privateCompleteSatisfied) {
        releaseCallbackCookie(cookie.slotIndex);
    }
}

void SurfaceControlPresentBackend::completeCallbackRecordConsumption(
        std::size_t index) noexcept {
    if (index >= callbackCookies_.size()) return;
    SubmissionCookie& cookie = callbackCookies_[index];
    const std::uint32_t previous = cookie.lifecycleFlags.fetch_or(
        kCookieRecordConsumed, std::memory_order_acq_rel);
    const bool privateCompleteSatisfied = !cookie.geometryPulseUpdate ||
        (previous & kCookiePrivateCompleteObserved) != 0U;
    if ((previous & kCookiePublicationComplete) != 0U &&
        privateCompleteSatisfied) {
        releaseCallbackCookie(index);
    }
}

bool SurfaceControlPresentBackend::hasFreeGeometryPulseBuffer() const noexcept {
    for (const auto& state : geometryPulseBufferStates_) {
        if (state.load(std::memory_order_acquire) ==
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE)) {
            return true;
        }
    }
    return false;
}

bool SurfaceControlPresentBackend::geometryPulseBuffersAllFree() const noexcept {
    if (currentGeometryPulseBufferIndex_.has_value()) return false;
    for (const auto& state : geometryPulseBufferStates_) {
        if (state.load(std::memory_order_acquire) !=
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE)) {
            return false;
        }
    }
    return true;
}

std::optional<std::uint32_t>
SurfaceControlPresentBackend::reserveGeometryPulseBuffer() noexcept {
    for (std::uint32_t index = 0; index < geometryPulseBufferStates_.size(); ++index) {
        std::uint8_t expected = static_cast<std::uint8_t>(
            GeometryPulseBufferState::FREE);
        if (geometryPulseBufferStates_[index].compare_exchange_strong(
                expected,
                static_cast<std::uint8_t>(GeometryPulseBufferState::RESERVED),
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            return index;
        }
    }
    return std::nullopt;
}

void SurfaceControlPresentBackend::releaseGeometryPulseResources() noexcept {
    currentGeometryPulseBufferIndex_.reset();
    for (std::size_t index = 0; index < geometryPulseBuffers_.size(); ++index) {
        if (geometryPulseBuffersOwned_ && geometryPulseBuffers_[index] != nullptr) {
            if (hardwareBufferRelease_ != nullptr) {
                hardwareBufferRelease_(geometryPulseBuffers_[index]);
            }
        }
        geometryPulseBuffers_[index] = nullptr;
        geometryPulseBufferStates_[index].store(
            static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
            std::memory_order_release);
    }
    geometryPulseBuffersOwned_ = false;
    geometryPulseFrameRate_ = 0.0F;
    geometryPulseFrameRateConfigured_ = false;
    if (geometryPulseSurface_ != nullptr && surfaceApi_.releaseSurface != nullptr) {
        surfaceApi_.releaseSurface(geometryPulseSurface_);
    }
    geometryPulseSurface_ = nullptr;
}

bool SurfaceControlPresentBackend::initializeGeometryPulse() noexcept {
    if (parentWindow_ == nullptr || surfaceApi_.createFromWindow == nullptr ||
        geometryPulseSurface_ != nullptr || geometryPulseBuffersOwned_) {
        return false;
    }
    // Keep the proof pulse in the SurfaceView's stable layer space. Making it a child of the
    // scrolling image layer translated the 1x1 buffer offscreen with the first crop; host
    // SurfaceFlinger then classified it as invisible and delivered OnCommit/OnComplete in
    // 250 ms-to-multi-second bursts. A sibling created from the parent window remains at (0,0)
    // while the Java geometry container moves, so every buffer replacement remains a real,
    // visible compositor input. The one-alpha-quantum pixel below has at most one 8-bit step of
    // contribution and every in-flight transaction owns a distinct immutable buffer identity.
    geometryPulseSurface_ = surfaceApi_.createFromWindow(
        parentWindow_, "NtkGeometryPulse");
    if (geometryPulseSurface_ == nullptr) return false;
    AHardwareBuffer_Desc descriptor{};
    descriptor.width = 1;
    descriptor.height = 1;
    descriptor.layers = 1;
    descriptor.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    descriptor.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    for (std::size_t index = 0; index < geometryPulseBuffers_.size(); ++index) {
        AHardwareBuffer* buffer = nullptr;
        if (hardwareBufferAllocate_(&descriptor, &buffer) != 0 ||
            buffer == nullptr) {
            releaseGeometryPulseResources();
            return false;
        }
        geometryPulseBuffers_[index] = buffer;
        geometryPulseBuffersOwned_ = true;
        void* pixels = nullptr;
        if (hardwareBufferLock_(
                buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                nullptr, &pixels) != 0 || pixels == nullptr) {
            releaseGeometryPulseResources();
            return false;
        }
        auto* rgba = static_cast<std::uint8_t*>(pixels);
        rgba[0] = 0U;
        rgba[1] = 0U;
        rgba[2] = 0U;
        // A fully transparent buffer is culled/content-detected by host SurfaceFlinger despite a
        // layer-alpha vote of one, so its callbacks arrive in 3-4 Hz batches. One alpha quantum
        // keeps this single corner pixel in the real composition graph while limiting its visual
        // contribution to at most one 8-bit channel step. Because the crop and pulse share one
        // transaction, its present fence is still evidence for the exact manga position rather
        // than a timer proxy.
        rgba[3] = 1U;
        int writeFence = -1;
        if (hardwareBufferUnlock_(buffer, &writeFence) != 0) {
            if (writeFence >= 0) close(writeFence);
            releaseGeometryPulseResources();
            return false;
        }
        if (writeFence >= 0) {
            pollfd descriptorFd{.fd = writeFence, .events = POLLIN, .revents = 0};
            int result = -1;
            do {
                result = poll(&descriptorFd, 1, -1);
            } while (result < 0 && errno == EINTR);
            const bool signaled = result > 0;
            close(writeFence);
            if (!signaled) {
                releaseGeometryPulseResources();
                return false;
            }
        }
    }
    for (auto& state : geometryPulseBufferStates_) {
        state.store(
            static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
            std::memory_order_release);
    }
    currentGeometryPulseBufferIndex_.reset();
    return true;
}

void SurfaceControlPresentBackend::configureGeometryPulseFrameRate(
        float frameRate) noexcept {
    geometryPulseFrameRate_ = std::isfinite(frameRate) && frameRate > 0.0F
        ? frameRate : 0.0F;
    geometryPulseFrameRateConfigured_ = false;
}

bool SurfaceControlPresentBackend::stateInvariantsHold() const noexcept {
    std::uint32_t exported = 0;
    std::uint32_t chainHead = 0;
    std::uint32_t replacedWait = 0;
    const auto states = pool_.stateSnapshot();
    std::uint32_t pulseCurrentCount = 0;
    std::uint32_t pulseReservedCount = 0;
    for (std::size_t index = 0; index < geometryPulseBufferStates_.size(); ++index) {
        const auto state = static_cast<GeometryPulseBufferState>(
            geometryPulseBufferStates_[index].load(std::memory_order_acquire));
        if (state == GeometryPulseBufferState::CURRENT) ++pulseCurrentCount;
        if (state == GeometryPulseBufferState::RESERVED) ++pulseReservedCount;
    }
    if (attached_ && geometryPulseSurface_ == nullptr) return false;
    if (pulseReservedCount != 0 || pulseCurrentCount > 1 ||
        currentGeometryPulseBufferIndex_.has_value() !=
            (pulseCurrentCount == 1) ||
        (currentGeometryPulseBufferIndex_.has_value() &&
         (*currentGeometryPulseBufferIndex_ >= geometryPulseBufferStates_.size() ||
          geometryPulseBufferStates_[*currentGeometryPulseBufferIndex_].load(
              std::memory_order_acquire) != static_cast<std::uint8_t>(
                  GeometryPulseBufferState::CURRENT)))) {
        return false;
    }
    for (const auto state : states) {
        if (state == HardwareBufferRenderTargetPool::SlotState::
                ACQUIRE_FENCE_EXPORTED) ++exported;
        if (state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_CHAIN_HEAD) ++chainHead;
        if (state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_REPLACED_WAIT_RELEASE) ++replacedWait;
    }
    if (exported > 1 || chainHead > 1 ||
        replacedWait > kMaxPreviousReleaseRecords ||
        chainHead + replacedWait > kMaxPreviousReleaseRecords ||
        previousReleaseRecordCount() != replacedWait ||
        acquireFenceRecordCount() > kMaxAcquireFenceRecords ||
        appOwnedAcquireFdCount() > kMaxAcquireFenceRecords ||
        callbackRecordCount() > kMaxAppliedCallbackRecords ||
        maxAppliedCallbackRecordCount_ > kMaxAppliedCallbackRecords) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) {
            const auto* localTarget = pool_.find(
                localAcquireFence_->buffer.slot,
                localAcquireFence_->buffer.generation);
            if (localTarget == nullptr || localTarget->state !=
                    HardwareBufferRenderTargetPool::SlotState::
                        ACQUIRE_FENCE_EXPORTED ||
                localAcquireFence_->acquireFenceSerial == 0 ||
                (localTarget->readyWithoutAcquireFence
                    ? (localAcquireFence_->frameworkAcquireFd != -1 ||
                       localAcquireFence_->proofAcquireFd != -1)
                    : (localAcquireFence_->frameworkAcquireFd < 0 ||
                       localAcquireFence_->proofAcquireFd < 0))) return false;
        } else if (exported != 0) {
            return false;
        }
    }
    if (logicalUnlatchedNow_ > kMaxGeometryLogicalUnlatched ||
        maxLogicalUnlatched_ > kMaxGeometryLogicalUnlatched ||
        logicalUnlatchedNow_ > maxLogicalUnlatched_) {
        return false;
    }
    if (backpressureDisableCount_ != 0 ||
        backpressureEnableCount_ > 1 ||
        backpressureEnabled_ != (backpressureEnableCount_ == 1)) {
        return false;
    }
    if (!latestConsumedCompositorLatchRef_.has_value()) {
        if (latestConsumedCompositorLatchEventSequence_ != 0 ||
            latestConsumedCompositorLatchNanos_ != 0 ||
            latestConsumedCompositorLatchObservedNanos_ != 0) {
            return false;
        }
    } else if (!validAppliedBufferRef(
                   *latestConsumedCompositorLatchRef_) ||
               latestConsumedCompositorLatchEventSequence_ == 0 ||
               latestConsumedCompositorLatchNanos_ <= 0 ||
               latestConsumedCompositorLatchObservedNanos_ <
                   latestConsumedCompositorLatchNanos_) {
        return false;
    }
    if (!latestAppliedBufferRef_.has_value()) {
        if (chainHead != 0 || logicalUnlatchedNow_ != 0 ||
            latestConsumedCompositorLatchRef_.has_value() ||
            backpressureEnabled_) return false;
    } else {
        if (!validAppliedBufferRef(*latestAppliedBufferRef_) ||
            chainHead != 1 || !backpressureEnabled_) return false;
        const auto* target = pool_.find(
            latestAppliedBufferRef_->identity.bufferSlot,
            latestAppliedBufferRef_->identity.bufferGeneration);
        if (target == nullptr ||
            target->state != HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_CHAIN_HEAD) {
            return false;
        }
        if (latestConsumedCompositorLatchRef_.has_value() &&
            latestConsumedCompositorLatchRef_->serial >
                latestAppliedBufferRef_->serial) {
            return false;
        }
        if (logicalUnlatchedNow_ == 0) {
            if (!latestConsumedCompositorLatchRef_.has_value() ||
                !exactAppliedBufferRef(
                    *latestConsumedCompositorLatchRef_,
                    *latestAppliedBufferRef_)) {
                return false;
            }
        }
    }
    std::uint32_t commitPending = 0;
    std::uint32_t completePending = 0;
    for (std::size_t i = 0; i < appliedCallbacks_.size(); ++i) {
        if (!appliedCallbacks_[i].has_value()) continue;
        const auto& left = *appliedCallbacks_[i];
        if (left.cookieIndex >= callbackCookies_.size()) return false;
        const SubmissionCookie& cookie = callbackCookies_[left.cookieIndex];
        const std::uint32_t commitCount = cookie.onCommitCount.load(
            std::memory_order_acquire);
        const std::uint32_t completeCount = cookie.onCompleteCount.load(
            std::memory_order_acquire);
        const std::uint64_t commitSequence =
            cookie.onCommitEventSequence.load(std::memory_order_acquire);
        const std::uint64_t completeSequence =
            cookie.onCompleteEventSequence.load(std::memory_order_acquire);
        const std::int64_t commitLatchNanos =
            cookie.onCommitLatchNanos.load(std::memory_order_acquire);
        const std::int64_t commitObservedNanos =
            cookie.onCommitObservedNanos.load(std::memory_order_acquire);
        const std::int64_t completePresentNanos =
            cookie.onCompletePresentNanos.load(std::memory_order_acquire);
        const std::int64_t completeObservedNanos =
            cookie.onCompleteObservedNanos.load(std::memory_order_acquire);
        if (!cookie.inUse.load(std::memory_order_acquire) ||
            cookie.backend != this || cookie.slotIndex != left.cookieIndex ||
            !exactIdentity(cookie.identity, left.identity) ||
            !left.applyIssued || !validAppliedBufferRef(left.producedRef) ||
            (!left.geometryOnly &&
             !exactIdentity(left.identity, left.producedRef.identity)) ||
            (left.geometryOnly &&
             (left.identity.surfaceEpoch !=
                  left.producedRef.identity.surfaceEpoch ||
              left.identity.backendSurfaceSerial !=
                  left.producedRef.identity.backendSurfaceSerial ||
              left.identity.bufferSlot !=
                  left.producedRef.identity.bufferSlot ||
              left.identity.bufferGeneration !=
                  left.producedRef.identity.bufferGeneration)) ||
            left.poisoned || commitCount > 1 || completeCount > 1 ||
            completeCount > commitCount ||
            left.consumedOnCommitCount > 1 ||
            left.consumedOnCompleteCount > 1 ||
            left.commitEventConsumed !=
                (left.consumedOnCommitCount == 1) ||
            left.completeEventConsumed !=
                (left.consumedOnCompleteCount == 1) ||
            (!left.geometryOnly && cookie.geometryPulseUpdate) ||
            (left.geometryOnly && left.requiresComplete &&
             (!cookie.geometryPulseUpdate ||
              cookie.geometryPulseBufferIndex >=
                  geometryPulseBufferStates_.size()))) return false;
        if (commitSequence != 0 &&
            (commitCount != 1 || commitLatchNanos <= 0 ||
             commitObservedNanos < commitLatchNanos)) {
            return false;
        }
        if (completeSequence != 0 &&
            (completeCount != 1 || commitSequence == 0 ||
             completePresentNanos <= 0 ||
             completeObservedNanos < completePresentNanos ||
             completeObservedNanos < commitObservedNanos)) {
            return false;
        }
        if (left.commitEventConsumed) {
            if (commitSequence == 0 ||
                left.latchEventSequence != commitSequence ||
                left.latchNanos != commitLatchNanos ||
                left.commitCallbackObservedNanos != commitObservedNanos ||
                !latestConsumedCompositorLatchRef_.has_value() ||
                latestConsumedCompositorLatchRef_->serial <
                    left.producedRef.serial) {
                return false;
            }
        } else {
            if (left.latchEventSequence != 0 || left.latchNanos != 0 ||
                left.commitCallbackObservedNanos != 0) return false;
            ++commitPending;
        }
        if (left.geometryOnly && !left.requiresComplete) {
            // A position-only device transaction can still use OnCommit as its terminal proof.
            // Pulse-backed host geometry sets requiresComplete and follows the ordinary completed
            // transaction branch below because its real buffer update owns present-fence evidence.
            if (left.completeEventConsumed ||
                left.consumedOnCompleteCount != 0 ||
                left.completeEventSequence != 0 || left.presentNanos != 0 ||
                left.completeCallbackObservedNanos != 0 ||
                cookie.hasPreviousAppliedBufferRef ||
                (!left.requiresComplete &&
                 (completeCount != 0 || completeSequence != 0 ||
                  completePresentNanos != 0 || completeObservedNanos != 0))) {
                return false;
            }
        } else if (left.completeEventConsumed) {
            if (completeSequence == 0 ||
                left.completeEventSequence != completeSequence ||
                left.presentNanos != completePresentNanos ||
                left.completeCallbackObservedNanos !=
                    completeObservedNanos) {
                return false;
            }
        } else {
            if (left.completeEventSequence != 0 ||
                left.presentNanos != 0 ||
                left.completeCallbackObservedNanos != 0) return false;
            ++completePending;
        }
        if (left.geometryOnly && left.requiresComplete &&
            cookie.hasPreviousAppliedBufferRef) return false;
        for (std::size_t j = i + 1; j < appliedCallbacks_.size(); ++j) {
            if (appliedCallbacks_[j].has_value() && exactIdentity(
                    left.identity, appliedCallbacks_[j]->identity)) return false;
        }
    }
    if (commitPending != logicalUnlatchedNow_ ||
        commitPending > kMaxAppliedCallbackRecords ||
        completePending > kMaxAppliedCallbackRecords ||
        maxCommitProofPending_ > kMaxAppliedCallbackRecords ||
        maxCompleteProofPending_ > kMaxAppliedCallbackRecords ||
        commitPending > maxCommitProofPending_ ||
        completePending > maxCompleteProofPending_ ||
        callbackRecordCount() > maxAppliedCallbackRecordCount_ ||
        maxCommitProofPending_ != maxLogicalUnlatched_) {
        return false;
    }
    for (std::size_t i = 0; i < previousReleases_.size(); ++i) {
        if (!previousReleases_[i].has_value()) continue;
        const auto& left = *previousReleases_[i];
        if (!validAppliedBufferRef(left.replacedRef) || left.released) {
            return false;
        }
        const auto* target = pool_.find(
            left.replacedRef.identity.bufferSlot,
            left.replacedRef.identity.bufferGeneration);
        if (target == nullptr || target->state !=
                HardwareBufferRenderTargetPool::SlotState::
                    FRAMEWORK_REPLACED_WAIT_RELEASE) {
            return false;
        }
        for (std::size_t j = i + 1; j < previousReleases_.size(); ++j) {
            if (!previousReleases_[j].has_value()) continue;
            const auto& right = *previousReleases_[j];
            if (exactIdentity(left.replacingTransactionIdentity,
                    right.replacingTransactionIdentity) ||
                exactAppliedBufferRef(
                    left.replacedRef, right.replacedRef)) {
                return false;
            }
        }
    }
    return true;
}

bool SurfaceControlPresentBackend::prepare(
        EGLDisplay display,
        std::uint32_t width,
        std::uint32_t height,
        bool cpuComposerOnly) {
    if (display == EGL_NO_DISPLAY || width == 0 || height == 0 ||
        android_get_device_api_level() < 33) {
        return false;
    }
    if (preparedFor(display, width, height, cpuComposerOnly)) {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        return pool_.initialized() && pool_.allFree() && fenceLooperReady_ &&
            !fenceLooperFailed_ && fenceThread_.joinable();
    }
    if (attached_) return false;
    if (prepared_ || display_ != EGL_NO_DISPLAY || androidLibrary_ != nullptr ||
        pool_.initialized() || fenceThread_.joinable() || fenceControlFd_ >= 0) {
        if (!destroy()) return false;
    }
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions == nullptr ||
        std::strstr(extensions, "EGL_ANDROID_image_native_buffer") == nullptr ||
        std::strstr(extensions, "EGL_KHR_fence_sync") == nullptr ||
        std::strstr(extensions, "EGL_ANDROID_native_fence_sync") == nullptr) {
        return false;
    }
    display_ = display;
    width_ = width;
    height_ = height;
    cpuComposerOnly_ = cpuComposerOnly;
    androidLibrary_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (androidLibrary_ == nullptr) {
        (void)destroy();
        return false;
    }
#define NTK_LOAD_SURFACE(member, symbol) \
    surfaceApi_.member = reinterpret_cast<decltype(surfaceApi_.member)>( \
        dlsym(androidLibrary_, symbol))
    NTK_LOAD_SURFACE(createFromWindow, "ASurfaceControl_createFromWindow");
    NTK_LOAD_SURFACE(create, "ASurfaceControl_create");
    NTK_LOAD_SURFACE(releaseSurface, "ASurfaceControl_release");
    NTK_LOAD_SURFACE(createTransaction, "ASurfaceTransaction_create");
    NTK_LOAD_SURFACE(deleteTransaction, "ASurfaceTransaction_delete");
    NTK_LOAD_SURFACE(applyTransaction, "ASurfaceTransaction_apply");
    NTK_LOAD_SURFACE(setOnComplete, "ASurfaceTransaction_setOnComplete");
    NTK_LOAD_SURFACE(setOnCommit, "ASurfaceTransaction_setOnCommit");
    NTK_LOAD_SURFACE(reparent, "ASurfaceTransaction_reparent");
    NTK_LOAD_SURFACE(setVisibility, "ASurfaceTransaction_setVisibility");
    NTK_LOAD_SURFACE(setBuffer, "ASurfaceTransaction_setBuffer");
    NTK_LOAD_SURFACE(setGeometry, "ASurfaceTransaction_setGeometry");
    NTK_LOAD_SURFACE(setPosition, "ASurfaceTransaction_setPosition");
    NTK_LOAD_SURFACE(setScale, "ASurfaceTransaction_setScale");
    NTK_LOAD_SURFACE(
        setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
    NTK_LOAD_SURFACE(setBufferAlpha, "ASurfaceTransaction_setBufferAlpha");
    NTK_LOAD_SURFACE(setColor, "ASurfaceTransaction_setColor");
    NTK_LOAD_SURFACE(
        setEnableBackPressure, "ASurfaceTransaction_setEnableBackPressure");
    NTK_LOAD_SURFACE(setFrameRate, "ASurfaceTransaction_setFrameRate");
    NTK_LOAD_SURFACE(setFrameTimeline, "ASurfaceTransaction_setFrameTimeline");
    NTK_LOAD_SURFACE(
        setDesiredPresentTime, "ASurfaceTransaction_setDesiredPresentTime");
    NTK_LOAD_SURFACE(
        getLatchTime, "ASurfaceTransactionStats_getLatchTime");
    NTK_LOAD_SURFACE(
        getPresentFenceFd, "ASurfaceTransactionStats_getPresentFenceFd");
    NTK_LOAD_SURFACE(
        getPreviousReleaseFenceFd,
        "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
#undef NTK_LOAD_SURFACE
    hardwareBufferAllocate_ = reinterpret_cast<HardwareBufferAllocateFn>(
        dlsym(androidLibrary_, "AHardwareBuffer_allocate"));
    hardwareBufferRelease_ = reinterpret_cast<HardwareBufferReleaseFn>(
        dlsym(androidLibrary_, "AHardwareBuffer_release"));
    hardwareBufferLock_ = reinterpret_cast<HardwareBufferLockFn>(
        dlsym(androidLibrary_, "AHardwareBuffer_lock"));
    hardwareBufferUnlock_ = reinterpret_cast<HardwareBufferUnlockFn>(
        dlsym(androidLibrary_, "AHardwareBuffer_unlock"));
    // sync_file_info belongs to libsync, not libandroid, on current Android releases. Looking it
    // up on the SurfaceControl library made every real API-35 device/emulator reject preparation
    // even though all required EGL/AHardwareBuffer capabilities were present.
    syncLibrary_ = dlopen("libsync.so", RTLD_NOW | RTLD_LOCAL);
    syncFileInfo_ = reinterpret_cast<SyncFileInfoFn>(
        syncLibrary_ != nullptr ? dlsym(syncLibrary_, "sync_file_info") : nullptr);
    syncFileInfoFree_ = reinterpret_cast<SyncFileInfoFreeFn>(
        syncLibrary_ != nullptr ? dlsym(syncLibrary_, "sync_file_info_free") : nullptr);
    createSync_ = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR"));
    destroySync_ = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR"));
    dupNativeFenceFd_ =
        reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
            eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    if (!surfaceApi_.complete() || hardwareBufferAllocate_ == nullptr ||
        hardwareBufferRelease_ == nullptr || hardwareBufferLock_ == nullptr ||
        hardwareBufferUnlock_ == nullptr || createSync_ == nullptr ||
        destroySync_ == nullptr || dupNativeFenceFd_ == nullptr ||
        syncFileInfo_ == nullptr || syncFileInfoFree_ == nullptr) {
        (void)destroy();
        return false;
    }
    if (!pool_.initialize(display_, width_, height_, cpuComposerOnly_)) {
        (void)destroy();
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        pendingFenceWatches_ = {};
        activeFenceWatches_ = {};
        fenceLooperReady_ = false;
        fenceLooperFailed_ = false;
    }
    fenceStopping_.store(false, std::memory_order_release);
    fenceControlFd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (fenceControlFd_ < 0) {
        (void)destroy();
        return false;
    }
    fenceThread_ = std::thread(
        &SurfaceControlPresentBackend::releaseFenceLoop, this);
    {
        std::unique_lock<std::mutex> lock(fenceMutex_);
        fenceReady_.wait(lock, [this] {
            return fenceLooperReady_ || fenceLooperFailed_;
        });
        if (fenceLooperFailed_) {
            lock.unlock();
            stopFenceReactor();
            (void)destroy();
            return false;
        }
    }
    prepared_ = true;
    return true;
}

bool SurfaceControlPresentBackend::attach(
        EGLDisplay display,
        ANativeWindow* parentWindow,
        ASurfaceControl* providedChildSurface,
        ASurfaceControl* providedGeometrySurface,
        std::uint32_t width,
        std::uint32_t height,
        std::uint64_t surfaceEpoch,
        WakeCallback wakeCallback,
        void* wakeContext) {
    if (attached_ || display == EGL_NO_DISPLAY || parentWindow == nullptr ||
        width == 0 || height == 0 || surfaceEpoch == 0 ||
        android_get_device_api_level() < 33 ||
        !prepare(display, width, height, cpuComposerOnly_)) {
        if (providedChildSurface != nullptr) {
            releaseProvidedSurfaceControl(providedChildSurface);
        }
        if (providedGeometrySurface != nullptr &&
            providedGeometrySurface != providedChildSurface) {
            releaseProvidedSurfaceControl(providedGeometrySurface);
        }
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(eventMutex_);
        eventRead_ = 0;
        eventWrite_ = 0;
        eventCount_ = 0;
    }
    eventOverflowed_.store(false, std::memory_order_release);
    appliedCallbacks_ = {};
    previousReleases_ = {};
    acquireFences_ = {};
    localAcquireFence_.reset();
    for (std::size_t i = 0; i < callbackCookies_.size(); ++i) {
        releaseCallbackCookie(i);
    }
    maxAppliedCallbackRecordCount_ = 0;
    maxCommitProofPending_ = 0;
    maxCompleteProofPending_ = 0;
    applyBeforePriorCompleteCount_ = 0;
    applyBeforePriorCommitConsumedCount_ = 0;
    priorOnCompletePendingAtSuccessorApply_ = 0;
    backendInvariantFatalCount_ = 0;
    applyBeforeAcquireSignalProvenCount_ = 0;
    lastLatchConsumedToSuccessorApplyNanos_ = 0;
    lastSuccessorApplyMinusPriorCompleteNanos_ = 0;
    lastSuccessorReadyMinusPriorCompleteNanos_ = 0;
    teardownReleaseEventSequence_.store(0, std::memory_order_release);
    latestAppliedBufferRef_.reset();
    latestConsumedCompositorLatchRef_.reset();
    latestConsumedCompositorLatchEventSequence_ = 0;
    latestConsumedCompositorLatchNanos_ = 0;
    latestConsumedCompositorLatchObservedNanos_ = 0;
    logicalUnlatchedNow_ = 0;
    maxLogicalUnlatched_ = 0;
    preparedTransactionState_ = PreparedTransactionState::EMPTY;
    preparedTransactionSerial_ = 0;
    acquireFenceSerial_ = 0;
    appliedBufferRefSerial_ = 0;
    directAdmissionSequence_ = 0;
    directFrameTimelineIdentity_ = 0;
    backpressureEnabled_ = false;
    backpressureEnableCount_ = 0;
    backpressureDisableCount_ = 0;
    capacityExhaustedCount_ = 0;
    capacityWaitCount_ = 0;
    maxHeldFrameworkRefCount_ = 0;
    minFreeReusableCount_ = HardwareBufferRenderTargetPool::kSlotCount;
    minAppOwnedBufferDomain_ = HardwareBufferRenderTargetPool::kSlotCount;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!fenceLooperReady_ || fenceLooperFailed_) return false;
        for (const auto& watch : pendingFenceWatches_) {
            if (watch.occupied) return false;
        }
        for (const auto& watch : activeFenceWatches_) {
            if (watch.occupied) return false;
        }
    }
    {
        std::lock_guard<std::mutex> lock(teardownMutex_);
        teardownCompleted_ = false;
    }
    parentWindow_ = parentWindow;
    ANativeWindow_acquire(parentWindow_);
    const std::int32_t parentWidth = ANativeWindow_getWidth(parentWindow_);
    const std::int32_t parentHeight = ANativeWindow_getHeight(parentWindow_);
    destinationWidth_ = parentWidth > 0
        ? static_cast<std::uint32_t>(parentWidth) : width_;
    destinationHeight_ = parentHeight > 0
        ? static_cast<std::uint32_t>(parentHeight) : height_;
    // API-34+ host emulators create this child in Java so geometry-only frames can be merged into
    // SurfaceView's next ViewRoot transaction. ASurfaceControl_fromJava transfers one native
    // reference to this backend; older/device paths retain the established native child creation.
    geometrySurface_ = providedGeometrySurface;
    childSurface_ = providedChildSurface != nullptr
        ? providedChildSurface
        : surfaceApi_.createFromWindow(parentWindow_, "NtkStripLayer");
    if (childSurface_ == nullptr) {
        (void)destroy();
        return false;
    }
    if (geometrySurface_ == nullptr) geometrySurface_ = childSurface_;
    if (!initializeGeometryPulse()) {
        (void)destroy();
        return false;
    }
    surfaceEpoch_ = surfaceEpoch;
    surfaceSerial_ = gSurfaceSerial.fetch_add(1, std::memory_order_acq_rel) + 1;
    wakeCallback_ = wakeCallback;
    wakeContext_ = wakeContext;
    attached_ = true;
    return true;
}

HardwareBufferRenderTargetPool::RenderTarget*
SurfaceControlPresentBackend::acquireRenderTarget() {
    return attached_ ? pool_.acquireForRendering() : nullptr;
}

bool SurfaceControlPresentBackend::bindRenderTarget(
        HardwareBufferRenderTargetPool::RenderTarget& target) {
    return attached_ && pool_.bindForRendering(target);
}

bool SurfaceControlPresentBackend::lockRenderTargetForCpuWrite(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels) {
    if (!attached_) return false;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
    }
    return pool_.lockForCpuWrite(target, pixels, stridePixels);
}

bool SurfaceControlPresentBackend::finishCpuWrite(
        HardwareBufferRenderTargetPool::RenderTarget& target) {
    if (!attached_) return false;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
    }
    int frameworkFd = -1;
    if (!pool_.finishCpuWrite(target, &frameworkFd)) return false;
    int proofFd = frameworkFd >= 0 ? dup(frameworkFd) : -1;
    const bool descriptorsExact = frameworkFd < 0 ||
        (proofFd >= 0 && proofFd != frameworkFd &&
         setCloseOnExec(frameworkFd) && setCloseOnExec(proofFd));
    if (!descriptorsExact) {
        if (frameworkFd >= 0) {
            pollfd descriptor{.fd = frameworkFd, .events = POLLIN, .revents = 0};
            int result = -1;
            do {
                result = poll(&descriptor, 1, -1);
            } while (result < 0 && errno == EINTR);
            close(frameworkFd);
        }
        if (proofFd >= 0) close(proofFd);
        (void)pool_.abortBeforeSubmission(target.slot, target.generation);
        return false;
    }
    std::uint64_t serial = ++acquireFenceSerial_;
    if (serial == 0) serial = ++acquireFenceSerial_;
    std::lock_guard<std::mutex> lock(fenceMutex_);
    if (localAcquireFence_.has_value()) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        (void)pool_.abortBeforeSubmission(target.slot, target.generation);
        return false;
    }
    // Preserve the same exact two-owner fence ledger used by GPU submissions. SurfaceControl gets
    // one descriptor; the reactor gets an independent descriptor for the signal proof. If gralloc
    // completed synchronously both remain -1 and the existing fence-free path is retained.
    localAcquireFence_ = LocalAcquireFenceOwner{
        .buffer = BufferIdentity{
            .slot = target.slot,
            .generation = target.generation,
        },
        .acquireFenceSerial = serial,
        .frameworkAcquireFd = frameworkFd,
        .proofAcquireFd = proofFd,
        .phase = LocalAcquirePhase::EXPORTED_UNBOUND,
    };
    return true;
}

bool SurfaceControlPresentBackend::beginCpuPrecomposition(
        HardwareBufferRenderTargetPool::RenderTarget& target) {
    if (!attached_) return false;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
    }
    return pool_.beginCpuPrecomposition(target);
}

bool SurfaceControlPresentBackend::lockCpuPrecompositionOffThread(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        void** pixels,
        std::uint32_t* stridePixels) {
    return attached_ &&
        pool_.lockCpuPrecompositionOffThread(target, pixels, stridePixels);
}

bool SurfaceControlPresentBackend::finishCpuPrecompositionOffThread(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        int* completionFenceFd) {
    return attached_ &&
        pool_.finishCpuPrecompositionOffThread(target, completionFenceFd);
}

bool SurfaceControlPresentBackend::publishFinishedCpuPrecomposition(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        int completionFenceFd) {
    int frameworkFd = completionFenceFd;
    if (!attached_ || target.state !=
            HardwareBufferRenderTargetPool::SlotState::PRECOMPOSING) {
        if (frameworkFd >= 0) close(frameworkFd);
        return false;
    }
    int proofFd = frameworkFd >= 0 ? dup(frameworkFd) : -1;
    const bool descriptorsExact = frameworkFd < 0 ||
        (proofFd >= 0 && proofFd != frameworkFd &&
         setCloseOnExec(frameworkFd) && setCloseOnExec(proofFd));
    if (!descriptorsExact) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        (void)pool_.abortBeforeSubmission(target.slot, target.generation);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) {
            if (frameworkFd >= 0) close(frameworkFd);
            if (proofFd >= 0) close(proofFd);
            (void)pool_.abortBeforeSubmission(target.slot, target.generation);
            return false;
        }
    }
    if (!pool_.publishFinishedCpuPrecomposition(target, frameworkFd < 0)) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        (void)pool_.abortBeforeSubmission(target.slot, target.generation);
        return false;
    }
    std::uint64_t serial = ++acquireFenceSerial_;
    if (serial == 0) serial = ++acquireFenceSerial_;
    std::lock_guard<std::mutex> lock(fenceMutex_);
    if (localAcquireFence_.has_value()) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        (void)pool_.abortBeforeSubmission(target.slot, target.generation);
        return false;
    }
    localAcquireFence_ = LocalAcquireFenceOwner{
        .buffer = BufferIdentity{
            .slot = target.slot,
            .generation = target.generation,
        },
        .acquireFenceSerial = serial,
        .frameworkAcquireFd = frameworkFd,
        .proofAcquireFd = proofFd,
        .phase = LocalAcquirePhase::EXPORTED_UNBOUND,
    };
    return true;
}

bool SurfaceControlPresentBackend::beginGpuFenceExport(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        std::int64_t renderBeginNanos,
        std::int64_t renderEndNanos,
        PendingGpuFenceExport* pending) {
    if (pending != nullptr) *pending = {};
    if (!attached_ || pending == nullptr || renderBeginNanos <= 0 ||
        renderEndNanos < renderBeginNanos || createSync_ == nullptr ||
        target.state != HardwareBufferRenderTargetPool::SlotState::RENDERING) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
    }
    constexpr EGLint attributes[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
        EGL_NO_NATIVE_FENCE_FD_ANDROID,
        EGL_NONE,
    };
    EGLSyncKHR fence = createSync_(
        display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (fence == EGL_NO_SYNC_KHR) return false;
    const std::int64_t fenceIssuedNanos = monotonicNowNanos();
    glFlush();
    if (fenceIssuedNanos < renderEndNanos) {
        (void)destroySync_(display_, fence);
        return false;
    }
    *pending = {
        .sync = fence,
        .renderBeginNanos = renderBeginNanos,
        .renderEndNanos = renderEndNanos,
        .fenceIssuedNanos = fenceIssuedNanos,
    };
    return true;
}

void SurfaceControlPresentBackend::finishGpuFenceExportOffThread(
        PendingGpuFenceExport* pending,
        FinishedGpuFenceExport* finished) const {
    FinishedGpuFenceExport discarded{};
    FinishedGpuFenceExport* output = finished != nullptr ? finished : &discarded;
    if (output->frameworkAcquireFd >= 0) close(output->frameworkAcquireFd);
    if (output->proofAcquireFd >= 0) close(output->proofAcquireFd);
    *output = {};
    if (pending == nullptr) return;
    const PendingGpuFenceExport input = *pending;
    *pending = {};
    if (input.sync == EGL_NO_SYNC_KHR || input.renderBeginNanos <= 0 ||
        input.renderEndNanos < input.renderBeginNanos ||
        input.fenceIssuedNanos < input.renderEndNanos ||
        display_ == EGL_NO_DISPLAY || dupNativeFenceFd_ == nullptr ||
        destroySync_ == nullptr) {
        if (input.sync != EGL_NO_SYNC_KHR && display_ != EGL_NO_DISPLAY &&
            destroySync_ != nullptr) {
            (void)destroySync_(display_, input.sync);
        }
        return;
    }
    // Export the GPU fence from EGL exactly once. A second
    // eglDupNativeFenceFDANDROID call re-enters the host GPU driver and can
    // stall the rolling reader long enough to miss the following compositor
    // cycle. dup() creates another descriptor for the same sync_file, so the
    // framework hand-off and our signal proof retain independent ownership
    // without a second driver round trip.
    int frameworkFd = dupNativeFenceFd_(display_, input.sync);
    int proofFd = frameworkFd >= 0 ? dup(frameworkFd) : -1;
    const bool destroyed = destroySync_(display_, input.sync) == EGL_TRUE;
    const std::int64_t exportReturnNanos = monotonicNowNanos();
    const bool descriptorsExact = frameworkFd >= 0 && proofFd >= 0 &&
        frameworkFd != proofFd && setCloseOnExec(frameworkFd) &&
        setCloseOnExec(proofFd);
    if (!destroyed || !descriptorsExact ||
        exportReturnNanos < input.fenceIssuedNanos) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        return;
    }
    *output = {
        .frameworkAcquireFd = frameworkFd,
        .proofAcquireFd = proofFd,
        .renderBeginNanos = input.renderBeginNanos,
        .renderEndNanos = input.renderEndNanos,
        .fenceIssuedNanos = input.fenceIssuedNanos,
        .exportReturnNanos = exportReturnNanos,
        .success = true,
    };
}

bool SurfaceControlPresentBackend::publishFinishedGpuFenceExport(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        FinishedGpuFenceExport* finished,
        GpuSubmissionProof* proof) {
    if (proof != nullptr) *proof = {};
    if (finished == nullptr) return false;
    const FinishedGpuFenceExport result = *finished;
    *finished = {};
    int frameworkFd = result.frameworkAcquireFd;
    int proofFd = result.proofAcquireFd;
    const bool valid = attached_ && proof != nullptr && result.success &&
        frameworkFd >= 0 && proofFd >= 0 && frameworkFd != proofFd &&
        result.renderBeginNanos > 0 &&
        result.renderEndNanos >= result.renderBeginNanos &&
        result.fenceIssuedNanos >= result.renderEndNanos &&
        result.exportReturnNanos >= result.fenceIssuedNanos &&
        target.state == HardwareBufferRenderTargetPool::SlotState::RENDERING;
    if (!valid) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) {
            close(frameworkFd);
            close(proofFd);
            return false;
        }
    }
    std::uint64_t serial = ++acquireFenceSerial_;
    if (serial == 0) serial = ++acquireFenceSerial_;
    if (!pool_.markAcquireFenceExported(target)) {
        close(frameworkFd);
        close(proofFd);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) {
            close(frameworkFd);
            close(proofFd);
            (void)pool_.abortBeforeSubmission(target.slot, target.generation);
            return false;
        }
        localAcquireFence_ = LocalAcquireFenceOwner{
            .buffer = BufferIdentity{
                .slot = target.slot,
                .generation = target.generation,
            },
            .acquireFenceSerial = serial,
            .frameworkAcquireFd = frameworkFd,
            .proofAcquireFd = proofFd,
            .phase = LocalAcquirePhase::EXPORTED_UNBOUND,
        };
    }
    *proof = {
        .bufferSlot = target.slot,
        .bufferGeneration = target.generation,
        .renderBeginNanos = result.renderBeginNanos,
        .renderEndNanos = result.renderEndNanos,
        .acquireFenceIssuedNanos = result.fenceIssuedNanos,
        .acquireFenceExportReturnNanos = result.exportReturnNanos,
        .acquireFenceSerial = serial,
        .acquireFenceDupCount = 2,
        .rendererGpuClientWaitCount = 0,
    };
    return validGpuSubmissionProof(*proof);
}

bool SurfaceControlPresentBackend::exportAcquireFence(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        std::int64_t renderBeginNanos,
        std::int64_t renderEndNanos,
        GpuSubmissionProof* proof) {
    PendingGpuFenceExport pending{};
    FinishedGpuFenceExport finished{};
    if (!beginGpuFenceExport(
            target, renderBeginNanos, renderEndNanos, &pending)) {
        return false;
    }
    finishGpuFenceExportOffThread(&pending, &finished);
    return publishFinishedGpuFenceExport(target, &finished, proof);
}

bool SurfaceControlPresentBackend::prepareBufferTransaction(
        const FixedPreparedFrameIdentityBase& baseIdentity,
        HardwareBufferRenderTargetPool::RenderTarget& target,
        bool firstStage,
        const FixedTransportProfile& profile,
        PreparedSurfaceSubmission* prepared,
        SwappyFixedExternalTransportReady* proof) {
    if (prepared) *prepared = {};
    if (proof) *proof = {};
    lastPreparationFailureReason_ = "none";
    if (prepared == nullptr || proof == nullptr) {
        lastPreparationFailureReason_ = "null-output";
        return false;
    }
    if (!hasPreparationCapacity()) {
        lastPreparationFailureReason_ = "prepared-capacity";
        return false;
    }
    if (target.state != HardwareBufferRenderTargetPool::SlotState::
            ACQUIRE_FENCE_EXPORTED || target.hardwareBuffer == nullptr) {
        lastPreparationFailureReason_ = "target-not-exported";
        return false;
    }
    if (baseIdentity.surfaceEpoch != surfaceEpoch_) {
        lastPreparationFailureReason_ = "surface-epoch";
        return false;
    }
    if (baseIdentity.engineGeneration == 0 ||
        baseIdentity.workGeneration == 0 || baseIdentity.ntkFrameId == 0 ||
        baseIdentity.authorityGeneration <= 0 || baseIdentity.authority <= 0 ||
        baseIdentity.frameSequence == 0 || baseIdentity.capsuleSequence == 0) {
        lastPreparationFailureReason_ = "identity";
        return false;
    }
    if (firstStage != !latestAppliedBufferRef_.has_value()) {
        lastPreparationFailureReason_ = "first-stage-chain-head";
        return false;
    }
    if (firstStage
            ? (backpressureEnabled_ || backpressureEnableCount_ != 0)
            : (!backpressureEnabled_ || backpressureEnableCount_ != 1)) {
        lastPreparationFailureReason_ = "backpressure-state";
        return false;
    }
    if (backpressureDisableCount_ != 0) {
        lastPreparationFailureReason_ = "backpressure-disabled";
        return false;
    }
    if (!validFixedTransportProfile(profile)) {
        lastPreparationFailureReason_ = "transport-profile";
        return false;
    }
    const bool readyWithoutAcquireFence = target.readyWithoutAcquireFence;
    std::uint64_t acquireFenceSerial = 0;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->phase != LocalAcquirePhase::EXPORTED_UNBOUND ||
            localAcquireFence_->buffer.slot != target.slot ||
            localAcquireFence_->buffer.generation != target.generation ||
            localAcquireFence_->acquireFenceSerial == 0 ||
            (readyWithoutAcquireFence
                ? (localAcquireFence_->frameworkAcquireFd != -1 ||
                   localAcquireFence_->proofAcquireFd != -1)
                : (localAcquireFence_->frameworkAcquireFd < 0 ||
                   localAcquireFence_->proofAcquireFd < 0))) {
            lastPreparationFailureReason_ = "local-acquire-fence";
            return false;
        }
        acquireFenceSerial = localAcquireFence_->acquireFenceSerial;
    }
    const std::int64_t prepareBegin = monotonicNowNanos();
    if (prepareBegin <= 0) {
        lastPreparationFailureReason_ = "monotonic-clock";
        return false;
    }
    FixedFrameIdentity identity{};
    identity.engineGeneration = baseIdentity.engineGeneration;
    identity.surfaceEpoch = baseIdentity.surfaceEpoch;
    identity.authorityGeneration = baseIdentity.authorityGeneration;
    identity.authority = baseIdentity.authority;
    identity.workGeneration = baseIdentity.workGeneration;
    identity.ntkFrameId = baseIdentity.ntkFrameId;
    identity.frameSequence = baseIdentity.frameSequence;
    identity.capsuleSequence = baseIdentity.capsuleSequence;
    identity.backendSurfaceSerial = surfaceSerial_;
    identity.transactionSerial = ++transactionSerial_;
    identity.bufferSlot = target.slot;
    identity.bufferGeneration = target.generation;
    std::uint64_t reservedAppliedRefSerial = ++appliedBufferRefSerial_;
    if (reservedAppliedRefSerial == 0) {
        reservedAppliedRefSerial = ++appliedBufferRefSerial_;
    }

    const auto cookieIndex = acquireCallbackCookie();
    if (!cookieIndex.has_value()) {
        ++capacityExhaustedCount_;
        lastPreparationFailureReason_ = "callback-cookie-capacity";
        return false;
    }
    auto* cookie = &callbackCookies_[*cookieIndex];
    cookie->identity = identity;
    if (latestAppliedBufferRef_.has_value()) {
        cookie->hasPreviousAppliedBufferRef = true;
        cookie->previousAppliedBufferRef = *latestAppliedBufferRef_;
    }

    ASurfaceTransaction* transaction = surfaceApi_.createTransaction();
    if (transaction == nullptr) {
        releaseCallbackCookie(*cookieIndex);
        lastPreparationFailureReason_ = "transaction-create";
        return false;
    }
    if (firstStage) {
        if (surfaceApi_.setPosition == nullptr || surfaceApi_.setScale == nullptr) {
            const ARect source{0, 0, static_cast<std::int32_t>(width_),
                static_cast<std::int32_t>(height_)};
            const ARect destination{
                0, 0,
                static_cast<std::int32_t>(destinationWidth_ > 0
                    ? destinationWidth_ : width_),
                static_cast<std::int32_t>(destinationHeight_ > 0
                    ? destinationHeight_ : height_)};
            surfaceApi_.setGeometry(
                transaction, childSurface_, source, destination, 0);
        }
        surfaceApi_.setBufferTransparency(
            transaction, childSurface_,
            ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        surfaceApi_.setBufferAlpha(transaction, childSurface_, 1.0F);
        surfaceApi_.setVisibility(
            transaction, childSurface_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        // Backpressure is a persistent SurfaceControl property, not a
        // per-buffer command. Install it atomically with the first buffer and
        // let every successor inherit the exact no-drop policy without
        // resending the same surface-state mutation in the fixed submit lane.
        surfaceApi_.setEnableBackPressure(
            transaction, childSurface_, true);
    }
    surfaceApi_.setOnCommit(transaction, cookie, &onCommitted);
    surfaceApi_.setOnComplete(transaction, cookie, &onCompleted);
    const std::int64_t prepareEnd = monotonicNowNanos();
    if (prepareEnd < prepareBegin) {
        surfaceApi_.deleteTransaction(transaction);
        releaseCallbackCookie(*cookieIndex);
        lastPreparationFailureReason_ = "monotonic-regression";
        return false;
    }
    prepared->baseIdentity = baseIdentity;
    prepared->transaction = transaction;
    prepared->cookie = cookie;
    prepared->backendSurfaceSerial = identity.backendSurfaceSerial;
    prepared->transactionSerial = identity.transactionSerial;
    prepared->bufferSlot = identity.bufferSlot;
    prepared->bufferGeneration = identity.bufferGeneration;
    prepared->acquireFenceSerial = acquireFenceSerial;
    prepared->prepareBeginNanos = prepareBegin;
    prepared->prepareEndNanos = prepareEnd;
    prepared->transportBoundNanos = profile.transportBoundNanos;
    prepared->transportProfileDigest = profile.digest;
    prepared->timingGeneration = profile.timingGeneration;
    prepared->setBufferCount = 0;
    prepared->acquireFenceDupCount = readyWithoutAcquireFence ? 0 : 2;
    prepared->setBufferPending = 1;
    prepared->callbackCookieIndex = static_cast<std::uint32_t>(*cookieIndex);
    prepared->previousAppliedBufferRef =
        latestAppliedBufferRef_.value_or(AppliedBufferRef{});
    prepared->reservedAppliedBufferRefSerial =
        reservedAppliedRefSerial;
    prepared->readyWithoutAcquireFence = readyWithoutAcquireFence;
    prepared->backpressureEnablePending = firstStage;
    prepared->firstStage = firstStage;
    prepared->state = PreparedTransactionState::PREPARED_NOT_CLAIMED;
    proof->structSize = sizeof(*proof);
    proof->version = SWAPPY_FIXED_EXTERNAL_TRANSPORT_READY_VERSION;
    proof->profile = toSwappyTransportProfile(profile);
    proof->workGeneration = baseIdentity.workGeneration;
    proof->ntkFrameId = baseIdentity.ntkFrameId;
    proof->engineGeneration = baseIdentity.engineGeneration;
    proof->surfaceEpoch = baseIdentity.surfaceEpoch;
    proof->authorityGeneration = baseIdentity.authorityGeneration;
    proof->authority = baseIdentity.authority;
    proof->frameSequence = baseIdentity.frameSequence;
    proof->capsuleSequence = baseIdentity.capsuleSequence;
    proof->backendSurfaceSerial = identity.backendSurfaceSerial;
    proof->transactionSerial = identity.transactionSerial;
    proof->bufferSlot = identity.bufferSlot;
    proof->bufferGeneration = identity.bufferGeneration;
    proof->acquireFenceSerial = acquireFenceSerial;
    proof->prepareBeginNanos = prepareBegin;
    proof->prepareEndNanos = prepareEnd;
    proof->setBufferCount = 0;
    proof->acquireFenceDupCount = prepared->acquireFenceDupCount;
    proof->setBufferPending = 1;
    proof->firstStage = firstStage ? 1U : 0U;
    proof->previousAppliedBufferRef =
        latestAppliedBufferRef_.has_value()
            ? toSwappyAppliedBufferRef(*latestAppliedBufferRef_)
            : swappy::emptyFixedAppliedBufferRef();
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->phase != LocalAcquirePhase::EXPORTED_UNBOUND ||
            localAcquireFence_->acquireFenceSerial != acquireFenceSerial) {
            surfaceApi_.deleteTransaction(transaction);
            releaseCallbackCookie(*cookieIndex);
            *prepared = {};
            *proof = {};
            lastPreparationFailureReason_ = "local-acquire-fence-race";
            return false;
        }
        localAcquireFence_->phase = LocalAcquirePhase::BOUND_TO_PREPARED;
        localAcquireFence_->preparedTransactionSerial =
            identity.transactionSerial;
    }
    preparedTransactionState_ = PreparedTransactionState::PREPARED_NOT_CLAIMED;
    preparedTransactionSerial_ = identity.transactionSerial;
    lastPreparationFailureReason_ = "none";
    return true;
}

bool SurfaceControlPresentBackend::abortRenderTargetBeforePreparation(
        std::uint64_t bufferSlot, std::uint64_t bufferGeneration) {
    if (!attached_) return false;
    auto* target = pool_.find(bufferSlot, bufferGeneration);
    if (target == nullptr) return false;
    if (target->state ==
            HardwareBufferRenderTargetPool::SlotState::RENDERING ||
        target->state ==
            HardwareBufferRenderTargetPool::SlotState::PRECOMPOSING) {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
        return pool_.abortBeforeSubmission(bufferSlot, bufferGeneration);
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->buffer.slot != bufferSlot ||
            localAcquireFence_->buffer.generation != bufferGeneration ||
            localAcquireFence_->phase != LocalAcquirePhase::EXPORTED_UNBOUND) {
            return false;
        }
    }
    const bool descriptorsClosed = closeAndClearLocalAcquireFence();
    return pool_.abortBeforeSubmission(bufferSlot, bufferGeneration) &&
        descriptorsClosed;
}

SurfaceControlPresentBackend::ApplyReadiness
SurfaceControlPresentBackend::queryApplyReadiness(
        const PreparedSurfaceSubmission& prepared) noexcept {
    return queryApplyReadinessImpl(prepared, false);
}

SurfaceControlPresentBackend::ApplyReadiness
SurfaceControlPresentBackend::queryDirectApplyReadiness(
        const PreparedSurfaceSubmission& prepared) noexcept {
    return queryApplyReadinessImpl(prepared, true);
}

SurfaceControlPresentBackend::ApplyReadiness
SurfaceControlPresentBackend::queryApplyReadinessImpl(
        const PreparedSurfaceSubmission& prepared,
        bool allowDirectPipeline) noexcept {
    const auto* target = pool_.find(
        prepared.bufferSlot, prepared.bufferGeneration);
    const auto* cookie = static_cast<const SubmissionCookie*>(prepared.cookie);
    bool localAcquireExact = false;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        localAcquireExact = localAcquireFence_.has_value() &&
            localAcquireFence_->phase ==
                LocalAcquirePhase::BOUND_TO_PREPARED &&
            localAcquireFence_->buffer.slot == prepared.bufferSlot &&
            localAcquireFence_->buffer.generation ==
                prepared.bufferGeneration &&
            localAcquireFence_->acquireFenceSerial ==
                prepared.acquireFenceSerial &&
            localAcquireFence_->preparedTransactionSerial ==
                prepared.transactionSerial &&
            (prepared.readyWithoutAcquireFence
                ? (localAcquireFence_->frameworkAcquireFd == -1 &&
                   localAcquireFence_->proofAcquireFd == -1)
                : (localAcquireFence_->frameworkAcquireFd >= 0 &&
                   localAcquireFence_->proofAcquireFd >= 0));
    }
    const bool preparedExact = attached_ && childSurface_ != nullptr &&
        prepared.transaction != nullptr && cookie != nullptr &&
        prepared.callbackCookieIndex < callbackCookies_.size() &&
        cookie == &callbackCookies_[prepared.callbackCookieIndex] &&
        cookie->inUse.load(std::memory_order_acquire) &&
        target != nullptr && target->state ==
            HardwareBufferRenderTargetPool::SlotState::
                ACQUIRE_FENCE_EXPORTED &&
        target->readyWithoutAcquireFence == prepared.readyWithoutAcquireFence &&
        localAcquireExact &&
        prepared.state == PreparedTransactionState::PREPARED_NOT_CLAIMED &&
        preparedTransactionState_ ==
            PreparedTransactionState::PREPARED_NOT_CLAIMED &&
        preparedTransactionSerial_ == prepared.transactionSerial &&
        cookie->identity.engineGeneration ==
            prepared.baseIdentity.engineGeneration &&
        cookie->identity.surfaceEpoch == prepared.baseIdentity.surfaceEpoch &&
        cookie->identity.authorityGeneration ==
            prepared.baseIdentity.authorityGeneration &&
        cookie->identity.authority == prepared.baseIdentity.authority &&
        cookie->identity.workGeneration ==
            prepared.baseIdentity.workGeneration &&
        cookie->identity.ntkFrameId == prepared.baseIdentity.ntkFrameId &&
        cookie->identity.frameSequence ==
            prepared.baseIdentity.frameSequence &&
        cookie->identity.capsuleSequence ==
            prepared.baseIdentity.capsuleSequence &&
        cookie->identity.backendSurfaceSerial == prepared.backendSurfaceSerial &&
        cookie->identity.transactionSerial == prepared.transactionSerial &&
        cookie->identity.bufferSlot == prepared.bufferSlot &&
        cookie->identity.bufferGeneration == prepared.bufferGeneration &&
        prepared.reservedAppliedBufferRefSerial != 0;
    const bool backpressureExact =
        prepared.backpressureEnablePending == prepared.firstStage &&
        (prepared.firstStage
            ? (!backpressureEnabled_ && backpressureEnableCount_ == 0)
            : (backpressureEnabled_ && backpressureEnableCount_ == 1)) &&
        backpressureDisableCount_ == 0;
    if (!preparedExact || !backpressureExact || !stateInvariantsHold()) {
        return ApplyReadiness::FATAL;
    }

    if (!latestAppliedBufferRef_.has_value()) {
        if (cookie->hasPreviousAppliedBufferRef ||
            validAppliedBufferRef(prepared.previousAppliedBufferRef) ||
            !prepared.firstStage ||
            latestConsumedCompositorLatchRef_.has_value() ||
            logicalUnlatchedNow_ != 0) return ApplyReadiness::FATAL;
    } else {
        if (!cookie->hasPreviousAppliedBufferRef ||
            !exactAppliedBufferRef(
                cookie->previousAppliedBufferRef,
                *latestAppliedBufferRef_) ||
            !exactAppliedBufferRef(
                prepared.previousAppliedBufferRef,
                *latestAppliedBufferRef_) ||
            prepared.firstStage) {
            return ApplyReadiness::FATAL;
        }
        if (allowDirectPipeline) {
            // SurfaceControl backpressure preserves transaction order and each callback record
            // keeps the exact frame/buffer identity. The next frame waits for the exact prior
            // OnCommit before its transaction is applied.
            if (logicalUnlatchedNow_ >= kMaxDirectLogicalUnlatched) {
                ++capacityWaitCount_;
                return ApplyReadiness::WAITING_PRIOR_LATCH;
            }
        } else {
            if (!latestConsumedCompositorLatchRef_.has_value() ||
                !exactAppliedBufferRef(
                    *latestConsumedCompositorLatchRef_,
                    *latestAppliedBufferRef_)) {
                return ApplyReadiness::WAITING_PRIOR_LATCH;
            }
        }
    }
    std::uint32_t heldFrameworkRefs = 0;
    for (const auto state : pool_.stateSnapshot()) {
        if (state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_CHAIN_HEAD ||
            state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_REPLACED_WAIT_RELEASE) {
            ++heldFrameworkRefs;
        }
    }
    const bool callbackCapacityUnavailable =
        !freeAppliedCallbackRecordIndex().has_value() ||
        (!prepared.readyWithoutAcquireFence &&
         !freeAcquireFenceRecordIndex().has_value()) ||
        (latestAppliedBufferRef_.has_value() &&
         !freePreviousReleaseRecordIndex().has_value()) ||
        heldFrameworkRefs > HardwareBufferRenderTargetPool::kSlotCount - 2;
    if (callbackCapacityUnavailable) {
        if (allowDirectPipeline) {
            // A host compositor can acknowledge OnCommit before delivering the corresponding
            // OnComplete. After K pipelined transactions the exact callback ledger is therefore
            // temporarily full even though every identity and ownership invariant is valid.
            // Keep the FIFO head and retry after consumeEvents() drains a callback; treating this
            // bounded state as corruption tears down a healthy Surface in a long reading session.
            ++capacityWaitCount_;
            return ApplyReadiness::WAITING_PRIOR_LATCH;
        }
        ++capacityExhaustedCount_;
        return ApplyReadiness::FATAL;
    }
    return ApplyReadiness::READY;
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyPreparedBufferTransaction(
        PreparedSurfaceSubmission& prepared,
        const SwappyFixedExternalClaim& claim,
        SubmissionReceipt* receipt) {
    return applyPreparedBufferTransactionImpl(
        prepared, claim, receipt, false, true);
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyPreparedBufferTransactionDirect(
        PreparedSurfaceSubmission& prepared,
        std::int64_t frameTimelineVsyncId,
        SubmissionReceipt* receipt) {
    const std::int64_t now = monotonicNowNanos();
    if (now <= 0 || frameTimelineVsyncId < 0 || prepared.transaction == nullptr ||
        prepared.state != PreparedTransactionState::PREPARED_NOT_CLAIMED) {
        if (receipt != nullptr) *receipt = {};
        return ApplyDisposition::NOT_APPLIED;
    }
    std::uint64_t admission = ++directAdmissionSequence_;
    if (admission == 0) admission = ++directAdmissionSequence_;
    std::uint64_t timelineIdentity = ++directFrameTimelineIdentity_;
    if (timelineIdentity == 0) timelineIdentity = ++directFrameTimelineIdentity_;

    SwappyFixedExternalClaim claim{};
    claim.structSize = sizeof(claim);
    claim.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
    claim.claimToken = admission;
    claim.workGeneration = prepared.baseIdentity.workGeneration;
    claim.admissionSequence = admission;
    claim.reservationSequence = admission;
    claim.opportunitySequence = admission;
    claim.candidateSequence = admission;
    claim.noticeSequence = admission;
    const bool hasDisplayFrameTimeline = frameTimelineVsyncId > 0;
    claim.plannedTargetFrame = hasDisplayFrameTimeline
        ? frameTimelineVsyncId
        : static_cast<std::int64_t>(timelineIdentity);
    claim.frameTimelineVsyncId = claim.plannedTargetFrame;
    claim.decisionNanos = now;
    claim.ntkFrameId = prepared.baseIdentity.ntkFrameId;
    claim.engineGeneration = prepared.baseIdentity.engineGeneration;
    claim.surfaceEpoch = prepared.baseIdentity.surfaceEpoch;
    claim.authorityGeneration = prepared.baseIdentity.authorityGeneration;
    claim.authority = prepared.baseIdentity.authority;
    claim.frameSequence = prepared.baseIdentity.frameSequence;
    claim.capsuleSequence = prepared.baseIdentity.capsuleSequence;
    claim.backendSurfaceSerial = prepared.backendSurfaceSerial;
    claim.transactionSerial = prepared.transactionSerial;
    claim.bufferSlot = prepared.bufferSlot;
    claim.bufferGeneration = prepared.bufferGeneration;
    claim.acquireFenceSerial = prepared.acquireFenceSerial;
    claim.transportProfileDigest = prepared.transportProfileDigest;
    claim.timingGeneration = prepared.timingGeneration;
    claim.transportBoundNanos = prepared.transportBoundNanos;
    claim.prepareBeginNanos = prepared.prepareBeginNanos;
    claim.prepareEndNanos = prepared.prepareEndNanos;
    claim.initialDecisionNanos = now;
    claim.claimReturnNanos = monotonicNowNanos();
    claim.transportAdmissionOutcome = 1;
    claim.setBufferCount = 0;
    claim.acquireFenceDupCount = prepared.acquireFenceDupCount;
    claim.setBufferPending = 1;
    claim.firstStage = prepared.firstStage ? 1U : 0U;
    claim.previousAppliedBufferRef = toSwappyAppliedBufferRef(
        prepared.previousAppliedBufferRef);
    return applyPreparedBufferTransactionImpl(
        prepared, claim, receipt, true, hasDisplayFrameTimeline);
}

bool SurfaceControlPresentBackend::configurePreparedSourceCrop(
        PreparedSurfaceSubmission& prepared,
        std::int32_t sourceTop,
        std::int32_t sourceHeight,
        std::int32_t geometryBaseSourceTop) {
    if (!attached_ || childSurface_ == nullptr || prepared.transaction == nullptr ||
        prepared.state != PreparedTransactionState::PREPARED_NOT_CLAIMED ||
        preparedTransactionState_ != PreparedTransactionState::PREPARED_NOT_CLAIMED ||
        preparedTransactionSerial_ != prepared.transactionSerial ||
        sourceTop < 0 || sourceHeight <= 0 ||
        sourceTop > static_cast<std::int32_t>(height_) - sourceHeight) {
        return false;
    }
    const std::uint32_t destinationWidth = destinationWidth_ > 0
        ? destinationWidth_ : width_;
    const std::uint32_t destinationHeight = destinationHeight_ > 0
        ? destinationHeight_ : static_cast<std::uint32_t>(sourceHeight);
    if (surfaceApi_.setPosition != nullptr && surfaceApi_.setScale != nullptr) {
        const bool separateGeometry = geometrySurface_ != nullptr &&
            geometrySurface_ != childSurface_;
        const float scaleX = static_cast<float>(destinationWidth) /
            static_cast<float>(width_);
        const float scaleY = static_cast<float>(destinationHeight) /
            static_cast<float>(sourceHeight);
        const std::int64_t offsetNumerator = static_cast<std::int64_t>(
            separateGeometry ? geometryBaseSourceTop : sourceTop) * destinationHeight;
        const std::int32_t positionY = -static_cast<std::int32_t>(
            offsetNumerator >= 0
                ? (offsetNumerator + sourceHeight / 2) / sourceHeight
                : (offsetNumerator - sourceHeight / 2) / sourceHeight);
        // A Java container separates geometry cadence from the large buffer layer. Full-buffer
        // replacement atomically resets that same container, while geometry-only transactions
        // move it on SurfaceFlinger's display timeline between replacements.
        surfaceApi_.setScale(prepared.transaction, childSurface_, scaleX, scaleY);
        if (separateGeometry) {
            surfaceApi_.setPosition(prepared.transaction, childSurface_, 0, 0);
            surfaceApi_.setPosition(prepared.transaction, geometrySurface_, 0, positionY);
        } else {
            surfaceApi_.setPosition(prepared.transaction, childSurface_, 0, positionY);
        }
    } else {
        const ARect source{
            0,
            sourceTop,
            static_cast<std::int32_t>(width_),
            sourceTop + sourceHeight,
        };
        const ARect destination{
            0,
            0,
            static_cast<std::int32_t>(destinationWidth),
            static_cast<std::int32_t>(destinationHeight),
        };
        surfaceApi_.setGeometry(
            prepared.transaction, childSurface_, source, destination, 0);
    }
    return true;
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyGeometryTransactionDirect(
        const FixedPreparedFrameIdentityBase& baseIdentity,
        std::int32_t sourceTop,
        std::int32_t sourceHeight,
        std::int64_t frameTimelineVsyncId,
        std::int64_t desiredPresentTimeNanos,
        SubmissionReceipt* receipt) {
    if (receipt != nullptr) *receipt = {};
    if (receipt == nullptr || !attached_ || childSurface_ == nullptr ||
        geometryPulseSurface_ == nullptr ||
        !latestAppliedBufferRef_.has_value() || frameTimelineVsyncId < 0 ||
        sourceTop < 0 || sourceHeight <= 0 ||
        sourceTop > static_cast<std::int32_t>(height_) - sourceHeight ||
        logicalUnlatchedNow_ >= kMaxGeometryLogicalUnlatched ||
        !hasFreeGeometryPulseBuffer() ||
        !stateInvariantsHold()) {
        return ApplyDisposition::NOT_APPLIED;
    }
    const auto recordIndex = freeAppliedCallbackRecordIndex();
    const auto cookieIndex = acquireCallbackCookie();
    if (!recordIndex.has_value() || !cookieIndex.has_value()) {
        if (cookieIndex.has_value()) releaseCallbackCookie(*cookieIndex);
        ++capacityExhaustedCount_;
        return ApplyDisposition::NOT_APPLIED;
    }

    FixedFrameIdentity identity{};
    identity.engineGeneration = baseIdentity.engineGeneration;
    identity.surfaceEpoch = baseIdentity.surfaceEpoch;
    identity.authorityGeneration = baseIdentity.authorityGeneration;
    identity.authority = baseIdentity.authority;
    identity.workGeneration = baseIdentity.workGeneration;
    identity.ntkFrameId = baseIdentity.ntkFrameId;
    identity.frameSequence = baseIdentity.frameSequence;
    identity.capsuleSequence = baseIdentity.capsuleSequence;
    identity.backendSurfaceSerial = surfaceSerial_;
    identity.transactionSerial = ++transactionSerial_;
    if (identity.transactionSerial == 0) identity.transactionSerial = ++transactionSerial_;
    identity.bufferSlot = latestAppliedBufferRef_->identity.bufferSlot;
    identity.bufferGeneration = latestAppliedBufferRef_->identity.bufferGeneration;

    auto* cookie = &callbackCookies_[*cookieIndex];
    cookie->identity = identity;
    ASurfaceTransaction* transaction = surfaceApi_.createTransaction();
    if (transaction == nullptr) {
        releaseCallbackCookie(*cookieIndex);
        return ApplyDisposition::NOT_APPLIED;
    }
    const auto pulseIndex = reserveGeometryPulseBuffer();
    if (!pulseIndex.has_value() ||
        geometryPulseBuffers_[*pulseIndex] == nullptr) {
        if (pulseIndex.has_value()) {
            geometryPulseBufferStates_[*pulseIndex].store(
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
                std::memory_order_release);
        }
        surfaceApi_.deleteTransaction(transaction);
        releaseCallbackCookie(*cookieIndex);
        ++capacityExhaustedCount_;
        return ApplyDisposition::NOT_APPLIED;
    }
    const std::uint32_t previousPulse = currentGeometryPulseBufferIndex_.
        value_or(UINT32_MAX);
    if (previousPulse != UINT32_MAX) {
        std::uint8_t expected = static_cast<std::uint8_t>(
            GeometryPulseBufferState::CURRENT);
        if (!geometryPulseBufferStates_[previousPulse].compare_exchange_strong(
                expected,
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            geometryPulseBufferStates_[*pulseIndex].store(
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
                std::memory_order_release);
            surfaceApi_.deleteTransaction(transaction);
            releaseCallbackCookie(*cookieIndex);
            return ApplyDisposition::NOT_APPLIED;
        }
    }
    std::uint8_t reserved = static_cast<std::uint8_t>(
        GeometryPulseBufferState::RESERVED);
    if (!geometryPulseBufferStates_[*pulseIndex].compare_exchange_strong(
            reserved,
            static_cast<std::uint8_t>(GeometryPulseBufferState::CURRENT),
            std::memory_order_acq_rel, std::memory_order_acquire)) {
        if (previousPulse != UINT32_MAX) {
            geometryPulseBufferStates_[previousPulse].store(
                static_cast<std::uint8_t>(GeometryPulseBufferState::CURRENT),
                std::memory_order_release);
        }
        geometryPulseBufferStates_[*pulseIndex].store(
            static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
            std::memory_order_release);
        surfaceApi_.deleteTransaction(transaction);
        releaseCallbackCookie(*cookieIndex);
        return ApplyDisposition::NOT_APPLIED;
    }
    currentGeometryPulseBufferIndex_ = *pulseIndex;
    cookie->geometryPulseUpdate = true;
    cookie->geometryPulseBufferIndex = *pulseIndex;
    cookie->previousGeometryPulseBufferIndex = previousPulse;
    const std::uint32_t destinationWidth = destinationWidth_ > 0
        ? destinationWidth_ : width_;
    const std::uint32_t destinationHeight = destinationHeight_ > 0
        ? destinationHeight_ : static_cast<std::uint32_t>(sourceHeight);
    if (surfaceApi_.setPosition != nullptr && surfaceApi_.setScale != nullptr) {
        const std::int64_t offsetNumerator =
            static_cast<std::int64_t>(sourceTop) * destinationHeight;
        const std::int32_t positionY = -static_cast<std::int32_t>(
            (offsetNumerator + sourceHeight / 2) / sourceHeight);
        // The scale was installed with the current full buffer. When Java supplied a separate
        // geometry container, move that container exactly as the Java transaction path did and
        // leave the scaled buffer child at zero. NDK OnComplete also supplies the actual
        // present-fence timestamp instead of an executor observation time.
        ASurfaceControl* movingSurface = geometrySurface_ != nullptr &&
                geometrySurface_ != childSurface_
            ? geometrySurface_
            : childSurface_;
        surfaceApi_.setPosition(transaction, movingSurface, 0, positionY);
    } else {
        const ARect source{
            0,
            sourceTop,
            static_cast<std::int32_t>(width_),
            sourceTop + sourceHeight,
        };
        const ARect destination{
            0,
            0,
            static_cast<std::int32_t>(destinationWidth),
            static_cast<std::int32_t>(destinationHeight),
        };
        surfaceApi_.setGeometry(transaction, childSurface_, source, destination, 0);
    }
    // A real display-owner AVsyncId is authoritative. The older desired-present fallback remains
    // only for API/runtime paths that cannot supply that ID; applying both policies to one crop
    // lets SurfaceFlinger defer an otherwise on-time transaction to the later heuristic clock.
    if (frameTimelineVsyncId > 0) {
        surfaceApi_.setFrameTimeline(transaction, frameTimelineVsyncId);
    } else if (surfaceApi_.setDesiredPresentTime != nullptr && desiredPresentTimeNanos > 0) {
        surfaceApi_.setDesiredPresentTime(transaction, desiredPresentTimeNanos);
    }
    // Position-only and fully transparent transactions are legally coalesced by the emulator's
    // SurfaceFlinger. Alternate two immutable 1x1 buffers whose black pixel has one alpha quantum.
    // That one-channel-step contribution prevents transparent-layer culling, and binding it in the
    // same transaction makes its present fence exact evidence for this crop. Both buffers remain
    // strongly owned and unwritten until teardown.
    surfaceApi_.setBuffer(
        transaction, geometryPulseSurface_,
        geometryPulseBuffers_[*pulseIndex], -1);
    surfaceApi_.setBufferTransparency(
        transaction, geometryPulseSurface_,
        ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
    surfaceApi_.setBufferAlpha(transaction, geometryPulseSurface_, 1.0F);
    surfaceApi_.setVisibility(
        transaction, geometryPulseSurface_,
        ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    const bool installsGeometryPulseFrameRate =
        !geometryPulseFrameRateConfigured_ && geometryPulseFrameRate_ > 0.0F &&
        surfaceApi_.setFrameRate != nullptr;
    if (installsGeometryPulseFrameRate) {
        // The Java image/container layers already carry this vote, but the native pulse is a
        // separate sibling. Without its own vote host SurfaceFlinger content-detects the
        // alternating 1x1 buffer near 3.7 Hz and merges otherwise on-time crop proofs.
        surfaceApi_.setFrameRate(
            transaction, geometryPulseSurface_, geometryPulseFrameRate_,
            ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    }
    const std::int64_t bufferSetEnd = monotonicNowNanos();
    surfaceApi_.setOnCommit(transaction, cookie, &onCommitted);
    surfaceApi_.setOnComplete(transaction, cookie, &onCompleted);

    appliedCallbacks_[*recordIndex] = AppliedCallbackRecord{
        .identity = identity,
        .producedRef = *latestAppliedBufferRef_,
        .cookieIndex = static_cast<std::uint32_t>(*cookieIndex),
        .applyIssued = true,
        .geometryOnly = true,
        // The visible crop is proven by OnCommit, but the transparent pulse is a real buffer
        // replacement. Its cookie and previous pulse slot therefore remain owned until the
        // private OnComplete/release-fence path finishes. Mark that ownership explicitly so an
        // unrelated image-buffer release cannot mistake the valid private completion for a
        // geometry-record invariant violation.
        .requiresComplete = true,
    };
    ++logicalUnlatchedNow_;
    maxLogicalUnlatched_ = std::max(maxLogicalUnlatched_, logicalUnlatchedNow_);
    maxAppliedCallbackRecordCount_ = std::max(
        maxAppliedCallbackRecordCount_, callbackRecordCount());
    maxCommitProofPending_ = std::max(maxCommitProofPending_, logicalUnlatchedNow_);
    std::uint32_t completePending = 0;
    for (const auto& record : appliedCallbacks_) {
        if (record.has_value() &&
            (!record->geometryOnly || record->requiresComplete) &&
            !record->completeEventConsumed) ++completePending;
    }
    maxCompleteProofPending_ = std::max(maxCompleteProofPending_, completePending);

    const std::int64_t applyBegin = monotonicNowNanos();
    surfaceApi_.applyTransaction(transaction);
    if (installsGeometryPulseFrameRate) geometryPulseFrameRateConfigured_ = true;
    const std::int64_t applyEnd = monotonicNowNanos();
    surfaceApi_.deleteTransaction(transaction);
    completeCallbackPublication(*cookie);
    receipt->identity = identity;
    receipt->transactionApplyBeginNanos = applyBegin;
    receipt->frameTimelineSetEndNanos = applyBegin;
    receipt->bufferSetEndNanos = bufferSetEnd;
    receipt->transactionApplyEndNanos = applyEnd;
    receipt->setBufferCount = 0;
    receipt->setFrameTimelineCount = frameTimelineVsyncId > 0 ? 1 : 0;
    receipt->transactionApplyCount = 1;
    receipt->applyDisposition = ApplyDisposition::APPLIED;
    receipt->previousAppliedBufferRef = *latestAppliedBufferRef_;
    receipt->appliedBufferRef = *latestAppliedBufferRef_;
    receipt->submitted = true;
    if (!stateInvariantsHold()) {
        ++backendInvariantFatalCount_;
        return ApplyDisposition::NOT_APPLIED;
    }
    return ApplyDisposition::APPLIED;
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyPreparedBufferTransactionImpl(
        PreparedSurfaceSubmission& prepared,
        const SwappyFixedExternalClaim& claim,
        SubmissionReceipt* receipt,
        bool directSubmission,
        bool applyFrameTimeline) {
    if (receipt) *receipt = {};
    auto* cookie = static_cast<SubmissionCookie*>(prepared.cookie);
    auto* target = pool_.find(prepared.bufferSlot, prepared.bufferGeneration);
    const std::int64_t applyEntryNanos = monotonicNowNanos();
    const bool exact = attached_ && receipt != nullptr && cookie != nullptr &&
        prepared.transaction != nullptr && target != nullptr &&
        target->state == HardwareBufferRenderTargetPool::SlotState::
            ACQUIRE_FENCE_EXPORTED &&
        prepared.state == PreparedTransactionState::PREPARED_NOT_CLAIMED &&
        preparedTransactionState_ ==
            PreparedTransactionState::PREPARED_NOT_CLAIMED &&
        preparedTransactionSerial_ == prepared.transactionSerial &&
        claim.structSize == sizeof(claim) &&
        claim.version == SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION &&
        claim.claimToken != 0 && claim.admissionSequence != 0 &&
        claim.frameTimelineVsyncId != 0 &&
        claim.workGeneration == prepared.baseIdentity.workGeneration &&
        claim.ntkFrameId == prepared.baseIdentity.ntkFrameId &&
        claim.engineGeneration == prepared.baseIdentity.engineGeneration &&
        claim.surfaceEpoch == prepared.baseIdentity.surfaceEpoch &&
        claim.authorityGeneration == prepared.baseIdentity.authorityGeneration &&
        claim.authority == prepared.baseIdentity.authority &&
        claim.frameSequence == prepared.baseIdentity.frameSequence &&
        claim.capsuleSequence == prepared.baseIdentity.capsuleSequence &&
        claim.backendSurfaceSerial == prepared.backendSurfaceSerial &&
        claim.transactionSerial == prepared.transactionSerial &&
        claim.bufferSlot == prepared.bufferSlot &&
        claim.bufferGeneration == prepared.bufferGeneration &&
        claim.acquireFenceSerial == prepared.acquireFenceSerial &&
        claim.transportProfileDigest == prepared.transportProfileDigest &&
        claim.timingGeneration == prepared.timingGeneration &&
        claim.transportBoundNanos == prepared.transportBoundNanos &&
        claim.prepareBeginNanos == prepared.prepareBeginNanos &&
        claim.prepareEndNanos == prepared.prepareEndNanos &&
        claim.prepareEndNanos <= claim.initialDecisionNanos &&
        claim.initialDecisionNanos <= claim.decisionNanos &&
        claim.claimReturnNanos >= claim.decisionNanos &&
        claim.claimReturnNanos <= applyEntryNanos &&
        claim.setBufferCount == 0 &&
        claim.acquireFenceDupCount == prepared.acquireFenceDupCount &&
        (prepared.readyWithoutAcquireFence
            ? claim.acquireFenceDupCount == 0
            : claim.acquireFenceDupCount == 2) &&
        claim.setBufferPending == 1 &&
        prepared.backpressureEnablePending == prepared.firstStage &&
        (prepared.firstStage
            ? (!backpressureEnabled_ && backpressureEnableCount_ == 0)
            : (backpressureEnabled_ && backpressureEnableCount_ == 1)) &&
        backpressureDisableCount_ == 0 &&
        claim.firstStage == (prepared.firstStage ? 1U : 0U) &&
        (directSubmission || (latestAppliedBufferRef_.has_value()
            ? (swappy::fixedPriorRetirementProofValid(
                   claim.priorRetirementProof) &&
               claim.priorLatchGateRequired == 1 &&
               claim.priorLatchGateUsed == 1 &&
               claim.priorCommitProofPendingAtClaim == 0 &&
               swappy::fixedLatchObservationValid(
                   claim.priorLatchObservation) &&
               latestConsumedCompositorLatchRef_.has_value() &&
               exactAppliedBufferRef(
                   *latestConsumedCompositorLatchRef_,
                   *latestAppliedBufferRef_) &&
               swappy::fixedFrameIdentityExact(
                   claim.priorLatchObservation.identity,
                   claim.previousAppliedBufferRef.identity) &&
               claim.priorLatchObservation.latchEventSequence ==
                   latestConsumedCompositorLatchEventSequence_ &&
               claim.priorLatchObservation.compositorLatchNanos ==
                   latestConsumedCompositorLatchNanos_ &&
               claim.priorLatchObservation.callbackObservedNanos ==
                   latestConsumedCompositorLatchObservedNanos_ &&
               claim.priorLatchObservation.callbackObservedNanos <=
                   claim.initialDecisionNanos &&
               swappy::fixedAppliedBufferRefExact(
                   claim.priorRetirementProof.predecessor,
                   claim.previousAppliedBufferRef) &&
               claim.priorRetirementProof.retirementCompleteNanos <=
                   claim.initialDecisionNanos)
            : (swappy::fixedPriorRetirementProofEmpty(
                   claim.priorRetirementProof) &&
               claim.priorLatchGateRequired == 0 &&
               claim.priorLatchGateUsed == 0 &&
               claim.priorCommitProofPendingAtClaim == 0 &&
               swappy::fixedLatchObservationEmpty(
                   claim.priorLatchObservation)))) &&
        swappy::fixedAppliedBufferRefExact(
            claim.previousAppliedBufferRef,
            latestAppliedBufferRef_.has_value()
                ? toSwappyAppliedBufferRef(*latestAppliedBufferRef_)
                : swappy::emptyFixedAppliedBufferRef()) &&
        (directSubmission
            ? queryDirectApplyReadiness(prepared)
            : queryApplyReadiness(prepared)) == ApplyReadiness::READY;
    if (!exact) return ApplyDisposition::NOT_APPLIED;

    const auto callbackIndex = freeAppliedCallbackRecordIndex();
    const auto acquireIndex = prepared.readyWithoutAcquireFence
        ? std::optional<std::size_t>{}
        : freeAcquireFenceRecordIndex();
    const auto releaseIndex = latestAppliedBufferRef_.has_value()
        ? freePreviousReleaseRecordIndex() : std::optional<std::size_t>{};
    if (!callbackIndex.has_value() ||
        (!prepared.readyWithoutAcquireFence && !acquireIndex.has_value()) ||
        (latestAppliedBufferRef_.has_value() && !releaseIndex.has_value())) {
        ++capacityExhaustedCount_;
        ++backendInvariantFatalCount_;
        return ApplyDisposition::NOT_APPLIED;
    }
    const std::optional<AppliedBufferRef> priorRef =
        latestAppliedBufferRef_;
    prepared.state = PreparedTransactionState::CLAIMED_NOT_APPLIED;
    preparedTransactionState_ = PreparedTransactionState::CLAIMED_NOT_APPLIED;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->phase !=
                LocalAcquirePhase::BOUND_TO_PREPARED ||
            localAcquireFence_->acquireFenceSerial !=
                prepared.acquireFenceSerial ||
            localAcquireFence_->preparedTransactionSerial !=
                prepared.transactionSerial) {
            ++backendInvariantFatalCount_;
            return ApplyDisposition::NOT_APPLIED;
        }
        localAcquireFence_->phase =
            LocalAcquirePhase::CLAIMED_NOT_TRANSFERRED;
    }
    cookie->identity.admissionSequence = claim.admissionSequence;
    cookie->identity.frameTimelineVsyncId = claim.frameTimelineVsyncId;
    const FixedFrameIdentity appliedIdentity = cookie->identity;
    const AppliedBufferRef producedRef{
        .serial = prepared.reservedAppliedBufferRefSerial,
        .identity = appliedIdentity,
    };
    if (!validAppliedBufferRef(producedRef)) {
        ++backendInvariantFatalCount_;
        return ApplyDisposition::NOT_APPLIED;
    }
    std::optional<HardwareBufferRenderTargetPool::BufferIdentity> previous;
    if (priorRef.has_value()) {
        previous = HardwareBufferRenderTargetPool::BufferIdentity{
            .slot = priorRef->identity.bufferSlot,
            .generation = priorRef->identity.bufferGeneration,
        };
    }
    if (!pool_.commitSubmissionPair(
            prepared.bufferSlot, prepared.bufferGeneration, previous)) {
        return ApplyDisposition::NOT_APPLIED;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (acquireIndex.has_value()) {
            acquireFences_[*acquireIndex] = AcquireFenceRecord{
                .identity = appliedIdentity,
                .buffer = BufferIdentity{
                    .slot = prepared.bufferSlot,
                    .generation = prepared.bufferGeneration,
                },
                .acquireFenceSerial = prepared.acquireFenceSerial,
                .phase = AcquireProofPhase::RESERVED,
            };
        }
    }
    if (priorRef.has_value()) {
        previousReleases_[*releaseIndex] = PreviousReleaseRecord{
            .replacingTransactionIdentity = appliedIdentity,
            .replacedRef = *priorRef,
            .releaseFencePending = true,
        };
    }
    appliedCallbacks_[*callbackIndex] = AppliedCallbackRecord{
        .identity = appliedIdentity,
        .producedRef = producedRef,
        .replacedRef = priorRef,
        .cookieIndex = prepared.callbackCookieIndex,
        .applyIssued = true,
    };
    latestAppliedBufferRef_ = producedRef;
    ++logicalUnlatchedNow_;
    maxLogicalUnlatched_ = std::max(
        maxLogicalUnlatched_, logicalUnlatchedNow_);
    maxAppliedCallbackRecordCount_ = std::max(
        maxAppliedCallbackRecordCount_, callbackRecordCount());
    const std::int64_t applyBegin = monotonicNowNanos();
    priorOnCompletePendingAtSuccessorApply_ = 0;
    if (priorRef.has_value()) {
        AppliedCallbackRecord* priorRecord = findAppliedCallbackRecord(
            priorRef->identity);
        if (priorRecord != nullptr) {
            priorRecord->successorReadyNanos = prepared.prepareEndNanos;
            priorRecord->successorApplyBeginNanos = applyBegin;
            if (!priorRecord->commitEventConsumed) {
                ++applyBeforePriorCommitConsumedCount_;
            } else if (priorRecord->commitCallbackObservedNanos > 0) {
                lastLatchConsumedToSuccessorApplyNanos_ =
                    applyBegin - priorRecord->commitCallbackObservedNanos;
            }
            const SubmissionCookie& priorCookie =
                callbackCookies_[priorRecord->cookieIndex];
            const std::int64_t priorCompleteObservedNanos =
                priorCookie.onCompleteObservedNanos.load(
                    std::memory_order_acquire);
            // The bounded ownership contract is about consumption of the
            // exact OnComplete proof, not callback-thread arrival. A callback
            // may already be queued while its proof remains deliberately
            // parked behind this successor's commit-priority cut.
            if (!priorRecord->completeEventConsumed) {
                ++applyBeforePriorCompleteCount_;
                priorOnCompletePendingAtSuccessorApply_ = 1;
                if (priorCompleteObservedNanos > 0) {
                    lastSuccessorApplyMinusPriorCompleteNanos_ =
                        applyBegin - priorCompleteObservedNanos;
                    lastSuccessorReadyMinusPriorCompleteNanos_ =
                        prepared.prepareEndNanos -
                            priorCompleteObservedNanos;
                }
            } else if (priorCompleteObservedNanos > 0) {
                lastSuccessorApplyMinusPriorCompleteNanos_ =
                    applyBegin - priorCompleteObservedNanos;
                lastSuccessorReadyMinusPriorCompleteNanos_ =
                    prepared.prepareEndNanos - priorCompleteObservedNanos;
            }
        } else if (latestConsumedCompositorLatchRef_.has_value() &&
            exactAppliedBufferRef(
                *latestConsumedCompositorLatchRef_, *priorRef)) {
            lastLatchConsumedToSuccessorApplyNanos_ =
                applyBegin - latestConsumedCompositorLatchObservedNanos_;
        }
    }
    int frameworkAcquireFd = -1;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        frameworkAcquireFd = std::exchange(
            localAcquireFence_->frameworkAcquireFd, -1);
    }
    if (applyFrameTimeline) {
        surfaceApi_.setFrameTimeline(
            prepared.transaction, claim.frameTimelineVsyncId);
        prepared.setFrameTimelineCount = 1;
    } else {
        prepared.setFrameTimelineCount = 0;
    }
    const std::int64_t frameTimelineSetEnd = monotonicNowNanos();
    surfaceApi_.setBuffer(
        prepared.transaction, childSurface_, target->hardwareBuffer,
        frameworkAcquireFd);
    prepared.setBufferCount = 1;
    prepared.setBufferPending = 0;
    const std::int64_t bufferSetEnd = monotonicNowNanos();
    surfaceApi_.applyTransaction(prepared.transaction);
    if (prepared.backpressureEnablePending) {
        backpressureEnabled_ = true;
        ++backpressureEnableCount_;
    }
    prepared.applyCount = 1;
    prepared.state = PreparedTransactionState::TERMINAL;
    const std::int64_t applyEnd = monotonicNowNanos();
    std::uint32_t commitPending = 0;
    std::uint32_t completePending = 0;
    for (const auto& record : appliedCallbacks_) {
        if (!record.has_value()) continue;
        if (!record->commitEventConsumed) ++commitPending;
        if (!record->geometryOnly && !record->completeEventConsumed) {
            ++completePending;
        }
    }
    maxCommitProofPending_ = std::max(
        maxCommitProofPending_, commitPending);
    maxCompleteProofPending_ = std::max(
        maxCompleteProofPending_, completePending);
    surfaceApi_.deleteTransaction(prepared.transaction);
    prepared.transaction = nullptr;
    // SurfaceControl owns the callback cookie after apply. The prepared lane
    // must not retain or abort it while the applied lane drains.
    prepared.cookie = nullptr;

    preparedTransactionState_ = PreparedTransactionState::EMPTY;
    preparedTransactionSerial_ = 0;
    int proofAcquireFd = -1;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value() &&
            localAcquireFence_->acquireFenceSerial ==
                prepared.acquireFenceSerial) {
            proofAcquireFd = std::exchange(
                localAcquireFence_->proofAcquireFd, -1);
            localAcquireFence_.reset();
        }
    }
    const std::uint64_t applyBeforeAcquireBaseline =
        applyBeforeAcquireSignalProvenCount_;
    if (!prepared.readyWithoutAcquireFence) {
        FixedPresentEvent acquireEvent{};
        acquireEvent.kind = FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED;
        acquireEvent.identity = appliedIdentity;
        acquireEvent.acquireFenceSerial = prepared.acquireFenceSerial;
        if (proofAcquireFd < 0 || !acquireIndex.has_value() ||
            !publishAcquireFenceProofAfterApply(
                *acquireIndex, proofAcquireFd, acquireEvent)) {
            ++backendInvariantFatalCount_;
        }
    }
    *receipt = {
        .identity = appliedIdentity,
        .transactionApplyBeginNanos = applyBegin,
        .frameTimelineSetEndNanos = frameTimelineSetEnd,
        .bufferSetEndNanos = bufferSetEnd,
        .transactionApplyEndNanos = applyEnd,
        .setBufferCount = prepared.setBufferCount,
        .setFrameTimelineCount = prepared.setFrameTimelineCount,
        .transactionApplyCount = prepared.applyCount,
        .applyDisposition = ApplyDisposition::APPLIED,
        .previousAppliedBufferRef =
            priorRef.value_or(AppliedBufferRef{}),
        .appliedBufferRef = producedRef,
        .applyBeforeAcquireSignalProven =
            applyBeforeAcquireSignalProvenCount_ >
                applyBeforeAcquireBaseline,
        .submitted = true,
    };
    return ApplyDisposition::APPLIED;
}

bool SurfaceControlPresentBackend::abortPreparedBufferTransaction(
        PreparedSurfaceSubmission& prepared) {
    if (!attached_ || prepared.transaction == nullptr ||
        prepared.cookie == nullptr ||
        (prepared.state != PreparedTransactionState::PREPARED_NOT_CLAIMED &&
         prepared.state != PreparedTransactionState::CLAIMED_NOT_APPLIED) ||
        prepared.applyCount != 0 ||
        preparedTransactionSerial_ != prepared.transactionSerial) {
        return false;
    }
    auto* target = pool_.find(prepared.bufferSlot, prepared.bufferGeneration);
    if (target == nullptr ||
        target->state != HardwareBufferRenderTargetPool::SlotState::
            ACQUIRE_FENCE_EXPORTED) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->buffer.slot != prepared.bufferSlot ||
            localAcquireFence_->buffer.generation !=
                prepared.bufferGeneration ||
            localAcquireFence_->acquireFenceSerial !=
                prepared.acquireFenceSerial ||
            localAcquireFence_->preparedTransactionSerial !=
                prepared.transactionSerial ||
            (localAcquireFence_->phase !=
                 LocalAcquirePhase::BOUND_TO_PREPARED &&
             localAcquireFence_->phase !=
                 LocalAcquirePhase::CLAIMED_NOT_TRANSFERRED)) return false;
    }
    surfaceApi_.deleteTransaction(prepared.transaction);
    const std::uint32_t cookieIndex = prepared.callbackCookieIndex;
    const bool descriptorsClosed = closeAndClearLocalAcquireFence();
    const bool poolAborted = pool_.abortBeforeSubmission(
        prepared.bufferSlot, prepared.bufferGeneration);
    prepared.transaction = nullptr;
    prepared.cookie = nullptr;
    releaseCallbackCookie(cookieIndex);
    prepared.state = PreparedTransactionState::TERMINAL;
    preparedTransactionState_ = PreparedTransactionState::EMPTY;
    preparedTransactionSerial_ = 0;
    return poolAborted && descriptorsClosed;
}

void SurfaceControlPresentBackend::onCommitted(
        void* context, ASurfaceTransactionStats* stats) noexcept {
    auto* cookie = static_cast<SubmissionCookie*>(context);
    if (cookie == nullptr || cookie->backend == nullptr) return;
    const std::int64_t observedNanos = monotonicNowNanos();
    std::int64_t emptyObservedNanos = 0;
    const bool firstObserved = cookie->onCommitObservedNanos.
        compare_exchange_strong(
            emptyObservedNanos, observedNanos, std::memory_order_acq_rel,
            std::memory_order_acquire);
    const std::uint32_t count =
        cookie->onCommitCount.fetch_add(1, std::memory_order_acq_rel) + 1;
    FixedPresentEvent event{};
    event.kind = FixedPresentEventKind::COMPOSITOR_LATCHED;
    event.identity = cookie->identity;
    event.latchSource = FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT;
    event.eventSequence = cookie->backend->eventSequence_.fetch_add(
        1, std::memory_order_acq_rel) + 1;
    event.callbackObservedNanos = observedNanos;
    const std::int64_t frameworkLatchNanos = stats != nullptr
        ? cookie->backend->surfaceApi_.getLatchTime(stats) : 0;
    // OnCommit is the platform's exact, identity-bound proof that this transaction was applied and
    // is ready to be presented. The host SurfaceFlinger implementation may nevertheless expose an
    // unavailable (-1) latch timestamp until OnComplete. Preserve the proof without inventing an
    // earlier time: the callback observation is a conservative upper bound for that boundary.
    event.latchNanos = frameworkLatchNanos > 0
        ? frameworkLatchNanos : event.callbackObservedNanos;
    event.onCommitCallbackCount = count;
    event.onCompleteCallbackCount =
        cookie->onCompleteCount.load(std::memory_order_acquire);
    if (count == 1 && firstObserved) {
        cookie->onCommitLatchNanos.store(
            event.latchNanos, std::memory_order_release);
        cookie->onCommitEventSequence.store(
            event.eventSequence, std::memory_order_release);
    }
    if (stats == nullptr || count != 1 || !firstObserved || observedNanos <= 0 ||
        event.latchNanos <= 0) {
        event.kind = FixedPresentEventKind::INVALID_CALLBACK;
    }
    cookie->backend->publishEvent(event);
}

bool SurfaceControlPresentBackend::retirePreviousGeometryPulse(
        SubmissionCookie& cookie, ASurfaceTransactionStats* stats) noexcept {
    if (!cookie.geometryPulseUpdate) return true;
    const std::uint32_t previous = cookie.previousGeometryPulseBufferIndex;
    if (previous == UINT32_MAX) return true;
    // Pulse buffers are written exactly once during attach, remain transparent and immutable, and
    // stay strongly owned until the detach transaction has completed. A previous-release fence is
    // required before an app writes or releases a buffer, but not before another transaction takes
    // an additional reference to the same immutable buffer. Alternating two identities therefore
    // preserves real compositor work without retaining one gralloc allocation per slow callback.
    return previous < geometryPulseBuffers_.size() &&
        geometryPulseBuffers_[previous] != nullptr && stats != nullptr &&
        geometryPulseSurface_ != nullptr;
}

void SurfaceControlPresentBackend::onCompleted(
        void* context, ASurfaceTransactionStats* stats) noexcept {
    auto* cookie = static_cast<SubmissionCookie*>(context);
    if (cookie == nullptr || cookie->backend == nullptr) return;
    SurfaceControlPresentBackend* backend = cookie->backend;
    const bool teardown = cookie->teardown;
    const std::int64_t observedNanos = monotonicNowNanos();
    std::int64_t emptyObservedNanos = 0;
    const bool firstObserved = cookie->onCompleteObservedNanos.
        compare_exchange_strong(
            emptyObservedNanos, observedNanos, std::memory_order_acq_rel,
            std::memory_order_acquire);
    const std::uint32_t count =
        cookie->onCompleteCount.fetch_add(1, std::memory_order_acq_rel) + 1;
    const std::uint32_t commitCount =
        cookie->onCommitCount.load(std::memory_order_acquire);
    std::int64_t presentNanos = observedNanos;
    bool presentEvidenceValid = teardown;
    if (!teardown && stats != nullptr) {
        const int presentFenceFd = backend->surfaceApi_.getPresentFenceFd(stats);
        if (presentFenceFd >= 0) {
            std::int64_t fenceSignalNanos = 0;
            const bool exactFenceSignal = exactAcquireFenceSignal(
                presentFenceFd, observedNanos,
                backend->syncFileInfo_, backend->syncFileInfoFree_,
                &fenceSignalNanos);
            const bool closed = close(presentFenceFd) == 0;
            // OnComplete itself guarantees that the transaction was included in a presented
            // frame. Prefer the fence's hardware signal timestamp when available; the callback
            // observation remains a conservative presentation upper bound on implementations
            // whose completed fence exposes no child timestamp.
            presentNanos = exactFenceSignal ? fenceSignalNanos : observedNanos;
            presentEvidenceValid = closed;
        } else if (presentFenceFd == -1) {
            // The NDK explicitly permits -1 on devices without present-fence support. OnComplete
            // is still the platform presentation proof in that case.
            presentEvidenceValid = true;
        }
    }

    FixedPresentEvent completed{};
    completed.kind = cookie->teardown
        ? FixedPresentEventKind::TEARDOWN_COMPLETED
        : FixedPresentEventKind::TRANSACTION_COMPLETED;
    completed.identity = cookie->identity;
    completed.eventSequence = backend->eventSequence_.fetch_add(
        1, std::memory_order_acq_rel) + 1;
    completed.latchSource = FixedLatchSource::
        ANDROID_SURFACE_CONTROL_ON_COMPLETE;
    completed.latchNanos = presentNanos;
    completed.callbackObservedNanos = observedNanos;
    completed.onCommitCallbackCount = commitCount;
    completed.onCompleteCallbackCount = count;
    if (count == 1 && firstObserved) {
        cookie->onCompletePresentNanos.store(
            presentNanos, std::memory_order_release);
        cookie->onCompleteEventSequence.store(
            completed.eventSequence, std::memory_order_release);
    }
    if ((!cookie->teardown && commitCount != 1) || count != 1 ||
        !firstObserved || observedNanos <= 0 || presentNanos <= 0 ||
        presentNanos > observedNanos || stats == nullptr ||
        !presentEvidenceValid) {
        completed.kind = FixedPresentEventKind::INVALID_CALLBACK;
    }
    const bool independentGeometryPulseCompletion =
        cookie->geometryPulseUpdate && !cookie->teardown;
    // A pulse-backed geometry transaction is real buffer work. OnCommit retires logical-unlatched
    // ownership, while OnComplete is the only platform callback carrying presentation evidence.
    // Publish both events; the renderer keeps the record across OnCommit when requiresComplete is
    // set and releases its cookie only after this private callback and event consumption converge.
    backend->publishEvent(completed);

    if (cookie->hasPreviousAppliedBufferRef) {
        FixedPresentEvent released{};
        released.kind = FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED;
        released.identity = cookie->identity;
        released.eventSequence = backend->eventSequence_.fetch_add(
            1, std::memory_order_acq_rel) + 1;
        released.callbackObservedNanos = monotonicNowNanos();
        released.releasedBufferSlot =
            cookie->previousAppliedBufferRef.identity.bufferSlot;
        released.releasedBufferGeneration =
            cookie->previousAppliedBufferRef.identity.bufferGeneration;
        released.releasedAppliedBufferRefSerial =
            cookie->previousAppliedBufferRef.serial;
        released.releasedBufferIdentity =
            cookie->previousAppliedBufferRef.identity;
        const int releaseFence = stats != nullptr
            ? backend->surfaceApi_.getPreviousReleaseFenceFd(
                stats, backend->childSurface_)
            : -2;
        if (releaseFence >= 0) {
            backend->enqueueReleaseFence(releaseFence, released);
        } else if (releaseFence == -1) {
            backend->publishEvent(released);
        } else {
            released.kind = FixedPresentEventKind::INVALID_CALLBACK;
            backend->publishEvent(released);
        }
    }
    if (!backend->retirePreviousGeometryPulse(*cookie, stats)) {
        FixedPresentEvent invalid{};
        invalid.kind = FixedPresentEventKind::INVALID_CALLBACK;
        invalid.identity = cookie->identity;
        invalid.eventSequence = backend->eventSequence_.fetch_add(
            1, std::memory_order_acq_rel) + 1;
        invalid.callbackObservedNanos = monotonicNowNanos();
        backend->publishEvent(invalid);
    }
    if (independentGeometryPulseCompletion) {
        const std::uint32_t previous = cookie->lifecycleFlags.fetch_or(
            kCookiePrivateCompleteObserved, std::memory_order_acq_rel);
        if ((previous & kCookiePublicationComplete) != 0U &&
            (previous & kCookieRecordConsumed) != 0U) {
            backend->releaseCallbackCookie(cookie->slotIndex);
        }
    } else {
        backend->completeCallbackPublication(*cookie);
    }
    if (teardown) {
        std::lock_guard<std::mutex> lock(backend->teardownMutex_);
        backend->teardownCompleted_ = true;
        backend->teardownCondition_.notify_all();
    }
}

void SurfaceControlPresentBackend::publishEvent(
        const FixedPresentEvent& event) noexcept {
    bool published = false;
    {
        std::lock_guard<std::mutex> lock(eventMutex_);
        if (eventCount_ == events_.size()) {
            eventOverflowed_.store(true, std::memory_order_release);
        } else {
            events_[eventWrite_] = event;
            eventWrite_ = (eventWrite_ + 1) % events_.size();
            ++eventCount_;
            published = true;
        }
    }
    eventCondition_.notify_all();
    if (published && wakeCallback_ != nullptr) wakeCallback_(wakeContext_);
}

bool SurfaceControlPresentBackend::drainEvent(FixedPresentEvent* event) {
    if (event == nullptr) return false;
    std::lock_guard<std::mutex> lock(eventMutex_);
    if (eventCount_ == 0) return false;
    *event = events_[eventRead_];
    eventRead_ = (eventRead_ + 1) % events_.size();
    --eventCount_;
    return true;
}

bool SurfaceControlPresentBackend::hasPendingEvent() {
    std::lock_guard<std::mutex> lock(eventMutex_);
    return eventCount_ != 0;
}

bool SurfaceControlPresentBackend::isGeometryOnlyTransaction(
        const FixedPresentEvent& event) const noexcept {
    for (const auto& optionalRecord : appliedCallbacks_) {
        if (optionalRecord.has_value() &&
            exactIdentity(optionalRecord->identity, event.identity)) {
            return optionalRecord->geometryOnly;
        }
    }
    return false;
}

bool SurfaceControlPresentBackend::consumeCompositorLatch(
        const FixedPresentEvent& event,
        ExactPresentLatchObservation* observation) noexcept {
    if (observation != nullptr) *observation = {};
    for (auto& optionalRecord : appliedCallbacks_) {
        if (!optionalRecord.has_value() ||
            !exactIdentity(optionalRecord->identity, event.identity)) {
            continue;
        }
        AppliedCallbackRecord& record = *optionalRecord;
        if (record.cookieIndex >= callbackCookies_.size()) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        const SubmissionCookie& cookie = callbackCookies_[record.cookieIndex];
        const std::uint64_t callbackSequence =
            cookie.onCommitEventSequence.load(std::memory_order_acquire);
        const std::int64_t callbackLatchNanos =
            cookie.onCommitLatchNanos.load(std::memory_order_acquire);
        const std::int64_t callbackObservedNanos =
            cookie.onCommitObservedNanos.load(std::memory_order_acquire);
        const bool exact = observation != nullptr && !record.poisoned &&
            record.applyIssued && !record.commitEventConsumed &&
            record.consumedOnCommitCount == 0 &&
            logicalUnlatchedNow_ > 0 && cookie.inUse.load(
                std::memory_order_acquire) &&
            cookie.backend == this && cookie.slotIndex == record.cookieIndex &&
            exactIdentity(cookie.identity, record.identity) &&
            cookie.onCommitCount.load(std::memory_order_acquire) == 1 &&
            callbackSequence == event.eventSequence &&
            callbackLatchNanos == event.latchNanos &&
            callbackObservedNanos == event.callbackObservedNanos &&
            event.structSize == sizeof(event) &&
            event.schemaVersion == kFixedPresentEventSchemaVersion &&
            event.kind == FixedPresentEventKind::COMPOSITOR_LATCHED &&
            event.eventSequence != 0 && event.latchNanos > 0 &&
            event.callbackObservedNanos >= event.latchNanos &&
            event.latchSource ==
                FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT &&
            event.onCommitCallbackCount == 1 &&
            event.onCompleteCallbackCount == 0;
        if (!exact) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        record.commitEventConsumed = true;
        record.consumedOnCommitCount = 1;
        record.latchEventSequence = event.eventSequence;
        record.latchNanos = event.latchNanos;
        record.commitCallbackObservedNanos = event.callbackObservedNanos;
        if (!latestConsumedCompositorLatchRef_.has_value() ||
            record.producedRef.serial >
                latestConsumedCompositorLatchRef_->serial) {
            latestConsumedCompositorLatchRef_ = record.producedRef;
            latestConsumedCompositorLatchEventSequence_ = event.eventSequence;
            latestConsumedCompositorLatchNanos_ = event.latchNanos;
            latestConsumedCompositorLatchObservedNanos_ =
                event.callbackObservedNanos;
        }
        --logicalUnlatchedNow_;
        if (record.successorApplyBeginNanos > 0) {
            lastLatchConsumedToSuccessorApplyNanos_ =
                record.successorApplyBeginNanos -
                    event.callbackObservedNanos;
        }
        *observation = ExactPresentLatchObservation{
            .identity = event.identity,
            .latchEventSequence = event.eventSequence,
            .latchNanos = event.latchNanos,
            .source = event.latchSource,
        };
        if ((record.geometryOnly && !record.requiresComplete) ||
            record.completeEventConsumed) {
            const std::uint32_t cookieIndex = record.cookieIndex;
            optionalRecord.reset();
            completeCallbackRecordConsumption(cookieIndex);
        }
        if (!stateInvariantsHold()) {
            ++backendInvariantFatalCount_;
            return false;
        }
        return true;
    }
    ++backendInvariantFatalCount_;
    return false;
}

bool SurfaceControlPresentBackend::consumeTransactionCompleted(
        const FixedPresentEvent& event) noexcept {
    for (auto& optionalRecord : appliedCallbacks_) {
        if (!optionalRecord.has_value() || !exactIdentity(
                optionalRecord->identity, event.identity)) continue;
        AppliedCallbackRecord& record = *optionalRecord;
        if (record.cookieIndex >= callbackCookies_.size()) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        const SubmissionCookie& cookie = callbackCookies_[record.cookieIndex];
        const std::uint64_t callbackSequence =
            cookie.onCompleteEventSequence.load(std::memory_order_acquire);
        const std::int64_t callbackObservedNanos =
            cookie.onCompleteObservedNanos.load(std::memory_order_acquire);
        const std::int64_t presentNanos =
            cookie.onCompletePresentNanos.load(std::memory_order_acquire);
        const bool exact = event.kind ==
                FixedPresentEventKind::TRANSACTION_COMPLETED &&
            event.structSize == sizeof(event) &&
            event.schemaVersion == kFixedPresentEventSchemaVersion &&
            event.eventSequence != 0 && event.onCommitCallbackCount == 1 &&
            event.onCompleteCallbackCount == 1 &&
            record.applyIssued &&
            (!record.geometryOnly || record.requiresComplete) &&
            !record.completeEventConsumed &&
            record.consumedOnCompleteCount == 0 && !record.poisoned &&
            cookie.inUse.load(std::memory_order_acquire) &&
            cookie.backend == this && cookie.slotIndex == record.cookieIndex &&
            exactIdentity(cookie.identity, record.identity) &&
            cookie.onCommitCount.load(std::memory_order_acquire) == 1 &&
            cookie.onCompleteCount.load(std::memory_order_acquire) == 1 &&
            callbackSequence == event.eventSequence &&
            presentNanos == event.latchNanos && presentNanos > 0 &&
            event.latchSource == FixedLatchSource::
                ANDROID_SURFACE_CONTROL_ON_COMPLETE &&
            callbackObservedNanos == event.callbackObservedNanos &&
            event.callbackObservedNanos >= event.latchNanos;
        if (exact && event.callbackObservedNanos > 0 &&
            record.successorApplyBeginNanos > 0) {
            lastSuccessorApplyMinusPriorCompleteNanos_ =
                record.successorApplyBeginNanos - event.callbackObservedNanos;
            if (record.successorReadyNanos > 0) {
                lastSuccessorReadyMinusPriorCompleteNanos_ =
                    record.successorReadyNanos - event.callbackObservedNanos;
            }
        }
        if (!exact) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        record.completeEventConsumed = true;
        record.consumedOnCompleteCount = 1;
        record.completeEventSequence = event.eventSequence;
        record.presentNanos = event.latchNanos;
        record.completeCallbackObservedNanos = event.callbackObservedNanos;
        if (record.commitEventConsumed) {
            const std::uint32_t cookieIndex = record.cookieIndex;
            optionalRecord.reset();
            completeCallbackRecordConsumption(cookieIndex);
        }
        if (!stateInvariantsHold()) {
            ++backendInvariantFatalCount_;
            return false;
        }
        return true;
    }
    ++backendInvariantFatalCount_;
    return false;
}

bool SurfaceControlPresentBackend::consumePreviousBufferReleased(
        const FixedPresentEvent& event) noexcept {
    for (auto& optionalRecord : previousReleases_) {
        if (!optionalRecord.has_value() || !exactIdentity(
                optionalRecord->replacingTransactionIdentity,
                event.identity)) continue;
        PreviousReleaseRecord& record = *optionalRecord;
        const bool exact = !record.poisoned &&
            event.kind == FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED &&
            event.eventSequence != 0 && record.releaseEventSequence == 0 &&
            record.releaseFencePending && !record.released &&
            event.releasedBufferSlot ==
                record.replacedRef.identity.bufferSlot &&
            event.releasedBufferGeneration ==
                record.replacedRef.identity.bufferGeneration &&
            event.releasedAppliedBufferRefSerial ==
                record.replacedRef.serial &&
            exactIdentity(
                event.releasedBufferIdentity,
                record.replacedRef.identity);
        if (!exact) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        record.releaseEventSequence = event.eventSequence;
        record.releaseFencePending = false;
        record.released = true;
        if (!pool_.markReleased(
                record.replacedRef.identity.bufferSlot,
                record.replacedRef.identity.bufferGeneration)) {
            record.poisoned = true;
            ++backendInvariantFatalCount_;
            return false;
        }
        optionalRecord.reset();
        if (!stateInvariantsHold()) {
            ++backendInvariantFatalCount_;
            return false;
        }
        return true;
    }
    ++backendInvariantFatalCount_;
    return false;
}

bool SurfaceControlPresentBackend::consumeAcquireFenceSignaled(
        const FixedPresentEvent& event) noexcept {
    const bool exact =
        event.schemaVersion == kFixedPresentEventSchemaVersion &&
        event.kind == FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED &&
        event.eventSequence != 0 && event.acquireFenceSerial != 0 &&
        event.acquireFenceSignalNanos > 0 &&
        event.callbackObservedNanos >= event.acquireFenceSignalNanos &&
        event.proofFdCloseCount == 1 &&
        event.identity.bufferGeneration != 0 &&
        event.identity.transactionSerial != 0;
    if (!exact) ++backendInvariantFatalCount_;
    return exact;
}

void SurfaceControlPresentBackend::enqueueReleaseFence(
        int fd, FixedPresentEvent event) noexcept {
    (void)enqueueFence(fd, event, FenceWatchKind::PREVIOUS_RELEASE);
}

bool SurfaceControlPresentBackend::enqueueFence(
        int fd, FixedPresentEvent event, FenceWatchKind kind) noexcept {
    bool queued = false;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (fenceStopping_.load(std::memory_order_acquire)) {
            close(fd);
            if (kind == FenceWatchKind::PREVIOUS_RELEASE) {
                event.kind = FixedPresentEventKind::INVALID_CALLBACK;
                publishEvent(event);
            }
            return false;
        }
        std::size_t totalWatches = 0;
        for (const auto& watch : pendingFenceWatches_) {
            if (watch.occupied) ++totalWatches;
        }
        for (const auto& watch : activeFenceWatches_) {
            if (watch.occupied) ++totalWatches;
        }
        if (totalWatches >= kMaxFenceWatches) {
            close(fd);
            if (kind == FenceWatchKind::PREVIOUS_RELEASE) {
                event.kind = FixedPresentEventKind::INVALID_CALLBACK;
                publishEvent(event);
            }
            return false;
        }
        for (auto& watch : pendingFenceWatches_) {
            if (watch.occupied) continue;
            watch.fd = fd;
            watch.event = event;
            watch.kind = kind;
            watch.occupied = true;
            queued = true;
            break;
        }
    }
    if (!queued) {
        close(fd);
        if (kind == FenceWatchKind::PREVIOUS_RELEASE) {
            event.kind = FixedPresentEventKind::INVALID_CALLBACK;
            publishEvent(event);
        }
        return false;
    }
    const std::uint64_t one = 1;
    (void)write(fenceControlFd_, &one, sizeof(one));
    return true;
}

bool SurfaceControlPresentBackend::publishAcquireFenceProofAfterApply(
        std::size_t recordIndex, int proofFd,
        FixedPresentEvent event) noexcept {
    if (recordIndex >= acquireFences_.size() || proofFd < 0 ||
        event.kind != FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED ||
        event.acquireFenceSerial == 0) {
        if (proofFd >= 0) close(proofFd);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        auto& optionalRecord = acquireFences_[recordIndex];
        if (!optionalRecord.has_value() ||
            optionalRecord->phase != AcquireProofPhase::RESERVED ||
            optionalRecord->acquireFenceSerial !=
                event.acquireFenceSerial ||
            !exactIdentity(optionalRecord->identity, event.identity)) {
            close(proofFd);
            return false;
        }
        optionalRecord->proofFd = proofFd;
    }

    pollfd descriptor{.fd = proofFd, .events = POLLIN, .revents = 0};
    const int result = poll(&descriptor, 1, 0);
    if (result > 0 &&
        (descriptor.revents & (POLLIN | POLLHUP)) != 0) {
        const std::int64_t observed = monotonicNowNanos();
        std::int64_t signal = 0;
        const bool exactSignal = exactAcquireFenceSignal(
            proofFd, observed, syncFileInfo_, syncFileInfoFree_, &signal);
        const bool closed = close(proofFd) == 0;
        {
            std::lock_guard<std::mutex> lock(fenceMutex_);
            auto& optionalRecord = acquireFences_[recordIndex];
            if (!optionalRecord.has_value() ||
                optionalRecord->proofFd != proofFd) return false;
            optionalRecord->proofFd = -1;
            optionalRecord->closeCount = closed ? 1U : 0U;
            optionalRecord->signalNanos = signal;
            optionalRecord->observedNanos = observed;
            optionalRecord.reset();
        }
        event.eventSequence = eventSequence_.fetch_add(
            1, std::memory_order_acq_rel) + 1;
        event.callbackObservedNanos = observed;
        event.acquireFenceSignalNanos = signal;
        event.proofFdCloseCount = closed ? 1U : 0U;
        if (!exactSignal || !closed) {
            event.kind = FixedPresentEventKind::INVALID_CALLBACK;
        }
        publishEvent(event);
        return exactSignal && closed;
    }
    if (result == 0) {
        {
            std::lock_guard<std::mutex> lock(fenceMutex_);
            auto& optionalRecord = acquireFences_[recordIndex];
            if (!optionalRecord.has_value() ||
                optionalRecord->proofFd != proofFd) return false;
            optionalRecord->phase = AcquireProofPhase::PENDING_REGISTER;
        }
        ++applyBeforeAcquireSignalProvenCount_;
        if (enqueueFence(
                proofFd, event, FenceWatchKind::ACQUIRE_PROOF)) return true;
    }

    const bool closed = result != 0 ? close(proofFd) == 0 : true;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        auto& optionalRecord = acquireFences_[recordIndex];
        if (optionalRecord.has_value() &&
            optionalRecord->proofFd == proofFd) optionalRecord.reset();
    }
    event.eventSequence = eventSequence_.fetch_add(
        1, std::memory_order_acq_rel) + 1;
    event.callbackObservedNanos = monotonicNowNanos();
    event.proofFdCloseCount = closed ? 1U : 0U;
    event.kind = FixedPresentEventKind::INVALID_CALLBACK;
    publishEvent(event);
    return false;
}

int SurfaceControlPresentBackend::onFenceControlFd(
        int fd, int events, void* data) noexcept {
    auto* backend = static_cast<SurfaceControlPresentBackend*>(data);
    if (backend == nullptr || (events & ALOOPER_EVENT_INPUT) == 0) return 1;
    std::uint64_t value = 0;
    while (read(fd, &value, sizeof(value)) == sizeof(value)) {
    }
    backend->registerPendingFenceWatches();
    return backend->fenceStopping_.load(std::memory_order_acquire) ? 0 : 1;
}

int SurfaceControlPresentBackend::onReleaseFenceFd(
        int fd, int events, void* data) noexcept {
    auto* watch = static_cast<ActiveFenceWatch*>(data);
    if (watch == nullptr || watch->backend == nullptr) return 0;
    FixedPresentEvent event = watch->event;
    if ((events & (ALOOPER_EVENT_INPUT | ALOOPER_EVENT_HANGUP)) == 0) {
        event.kind = FixedPresentEventKind::INVALID_CALLBACK;
    }
    watch->backend->finishFenceWatch(fd, event);
    return 0;
}

bool SurfaceControlPresentBackend::fenceReactorInitializationSucceeded(
        ALooper* looper, int controlRegistrationResult) noexcept {
    return looper != nullptr && controlRegistrationResult == 1;
}

void SurfaceControlPresentBackend::registerPendingFenceWatches() {
    for (;;) {
        ActiveFenceWatch* raw = nullptr;
        {
            std::lock_guard<std::mutex> lock(fenceMutex_);
            PendingFenceWatch* pending = nullptr;
            for (auto& candidate : pendingFenceWatches_) {
                if (candidate.occupied) {
                    pending = &candidate;
                    break;
                }
            }
            if (pending == nullptr) break;
            for (auto& active : activeFenceWatches_) {
                if (active.occupied) continue;
                active.backend = this;
                active.fd = pending->fd;
                active.event = pending->event;
                active.kind = pending->kind;
                active.occupied = true;
                raw = &active;
                if (active.kind == FenceWatchKind::ACQUIRE_PROOF) {
                    for (auto& record : acquireFences_) {
                        if (record.has_value() &&
                            record->proofFd == active.fd &&
                            record->acquireFenceSerial ==
                                active.event.acquireFenceSerial) {
                            record->phase =
                                AcquireProofPhase::ACTIVE_WAIT_SIGNAL;
                            break;
                        }
                    }
                }
                pending->fd = -1;
                pending->event = {};
                pending->occupied = false;
                break;
            }
            if (raw == nullptr) {
                FixedPresentEvent invalid = pending->event;
                const int rejectedFd = pending->fd;
                pending->fd = -1;
                pending->event = {};
                pending->occupied = false;
                close(rejectedFd);
                invalid.kind = FixedPresentEventKind::INVALID_CALLBACK;
                publishEvent(invalid);
                continue;
            }
        }
        if (ALooper_addFd(
                fenceLooper_, raw->fd, ALOOPER_POLL_CALLBACK,
                ALOOPER_EVENT_INPUT, &onReleaseFenceFd, raw) != 1) {
            FixedPresentEvent invalid = raw->event;
            invalid.kind = FixedPresentEventKind::INVALID_CALLBACK;
            finishFenceWatch(raw->fd, invalid);
        }
    }
}

void SurfaceControlPresentBackend::finishFenceWatch(
        int fd, FixedPresentEvent event) noexcept {
    bool found = false;
    FenceWatchKind kind = FenceWatchKind::PREVIOUS_RELEASE;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        for (auto& watch : activeFenceWatches_) {
            if (!watch.occupied || watch.fd != fd) continue;
            kind = watch.kind;
            watch.backend = nullptr;
            watch.fd = -1;
            watch.event = {};
            watch.occupied = false;
            found = true;
            break;
        }
    }
    if (!found) return;
    const std::int64_t observed = monotonicNowNanos();
    if (kind == FenceWatchKind::GEOMETRY_PULSE_RELEASE) {
        const bool closed = close(fd) == 0;
        const std::uint64_t rawIndex = event.releasedBufferSlot;
        bool exact = event.kind ==
                FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED &&
            closed && rawIndex < geometryPulseBufferStates_.size();
        if (exact) {
            std::uint8_t expected = static_cast<std::uint8_t>(
                GeometryPulseBufferState::WAIT_RELEASE);
            exact = geometryPulseBufferStates_[rawIndex].compare_exchange_strong(
                expected,
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
                std::memory_order_acq_rel, std::memory_order_acquire);
        }
        if (!exact) {
            event.kind = FixedPresentEventKind::INVALID_CALLBACK;
            event.eventSequence = eventSequence_.fetch_add(
                1, std::memory_order_acq_rel) + 1;
            event.callbackObservedNanos = observed;
            publishEvent(event);
        } else {
            eventCondition_.notify_all();
            if (wakeCallback_ != nullptr) wakeCallback_(wakeContext_);
        }
        return;
    }
    if (kind == FenceWatchKind::ACQUIRE_PROOF) {
        std::int64_t signal = 0;
        const bool exactSignal =
            event.kind == FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED &&
            exactAcquireFenceSignal(
                fd, observed, syncFileInfo_, syncFileInfoFree_, &signal);
        const bool closed = close(fd) == 0;
        bool exactRecord = false;
        {
            std::lock_guard<std::mutex> lock(fenceMutex_);
            for (auto& record : acquireFences_) {
                if (!record.has_value() || record->proofFd != fd) continue;
                exactRecord = record->acquireFenceSerial ==
                        event.acquireFenceSerial &&
                    exactIdentity(record->identity, event.identity) &&
                    record->phase == AcquireProofPhase::ACTIVE_WAIT_SIGNAL;
                record->proofFd = -1;
                record->closeCount = closed ? 1U : 0U;
                record->signalNanos = signal;
                record->observedNanos = observed;
                record.reset();
                break;
            }
        }
        event.eventSequence = eventSequence_.fetch_add(
            1, std::memory_order_acq_rel) + 1;
        event.acquireFenceSignalNanos = signal;
        event.proofFdCloseCount = closed ? 1U : 0U;
        if (!exactSignal || !closed || !exactRecord) {
            event.kind = FixedPresentEventKind::INVALID_CALLBACK;
        }
    } else {
        close(fd);
    }
    event.callbackObservedNanos = observed;
    publishEvent(event);
}

void SurfaceControlPresentBackend::releaseFenceLoop() {
    fenceLooper_ = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    int controlRegistrationResult = -1;
    if (fenceLooper_ != nullptr) {
        ALooper_acquire(fenceLooper_);
        controlRegistrationResult = ALooper_addFd(
            fenceLooper_, fenceControlFd_, ALOOPER_POLL_CALLBACK,
            ALOOPER_EVENT_INPUT, &onFenceControlFd, this);
    }
    const bool ready = fenceReactorInitializationSucceeded(
        fenceLooper_, controlRegistrationResult);
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        fenceLooperReady_ = ready;
        fenceLooperFailed_ = !ready;
        fenceReady_.notify_all();
    }
    while (!fenceStopping_.load(std::memory_order_acquire) &&
           fenceLooper_ != nullptr) {
        (void)ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        for (auto& watch : activeFenceWatches_) {
            if (!watch.occupied) continue;
            ALooper_removeFd(fenceLooper_, watch.fd);
            close(watch.fd);
            watch = {};
        }
        for (auto& pending : pendingFenceWatches_) {
            if (pending.occupied && pending.fd >= 0) close(pending.fd);
            pending = {};
        }
    }
    if (fenceLooper_ != nullptr) {
        ALooper_removeFd(fenceLooper_, fenceControlFd_);
        ALooper_release(fenceLooper_);
        fenceLooper_ = nullptr;
    }
}

void SurfaceControlPresentBackend::stopFenceReactor() {
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!fenceThread_.joinable()) return;
        fenceStopping_.store(true, std::memory_order_release);
    }
    const std::uint64_t one = 1;
    (void)write(fenceControlFd_, &one, sizeof(one));
    fenceThread_.join();
}

bool SurfaceControlPresentBackend::detachAfterEvidenceDrained() {
    if (!attached_ || childSurface_ == nullptr || hasOutstandingSubmission() ||
        preparedTransactionState_ != PreparedTransactionState::EMPTY ||
        previousReleaseRecordCount() != 0 ||
        acquireFenceRecordCount() != 0 ||
        appOwnedAcquireFdCount() != 0 || hasPendingEvent() ||
        !stateInvariantsHold()) return false;
    for (const auto& state : geometryPulseBufferStates_) {
        const auto value = static_cast<GeometryPulseBufferState>(
            state.load(std::memory_order_acquire));
        if (value == GeometryPulseBufferState::RESERVED ||
            value == GeometryPulseBufferState::WAIT_RELEASE) return false;
    }
    const auto cookieIndex = acquireCallbackCookie();
    if (!cookieIndex.has_value()) return false;
    auto* cookie = &callbackCookies_[*cookieIndex];
    cookie->identity.surfaceEpoch = surfaceEpoch_;
    cookie->identity.backendSurfaceSerial = surfaceSerial_;
    cookie->identity.transactionSerial = ++transactionSerial_;
    cookie->teardown = true;
    if (latestAppliedBufferRef_.has_value()) {
        cookie->hasPreviousAppliedBufferRef = true;
        cookie->previousAppliedBufferRef = *latestAppliedBufferRef_;
    }
    ASurfaceTransaction* transaction = surfaceApi_.createTransaction();
    if (transaction == nullptr) {
        releaseCallbackCookie(*cookieIndex);
        return false;
    }
    if (latestAppliedBufferRef_.has_value()) {
        if (!pool_.markReleaseWait(
                latestAppliedBufferRef_->identity.bufferSlot,
                latestAppliedBufferRef_->identity.bufferGeneration)) {
            surfaceApi_.deleteTransaction(transaction);
            releaseCallbackCookie(*cookieIndex);
            return false;
        }
        previousReleases_[0] = PreviousReleaseRecord{
            .replacingTransactionIdentity = cookie->identity,
            .replacedRef = *latestAppliedBufferRef_,
            .releaseFencePending = true,
        };
        latestAppliedBufferRef_.reset();
        latestConsumedCompositorLatchRef_.reset();
        latestConsumedCompositorLatchEventSequence_ = 0;
        latestConsumedCompositorLatchNanos_ = 0;
        latestConsumedCompositorLatchObservedNanos_ = 0;
        logicalUnlatchedNow_ = 0;
    }
    if (currentGeometryPulseBufferIndex_.has_value()) {
        const std::uint32_t pulseIndex = *currentGeometryPulseBufferIndex_;
        std::uint8_t expected = static_cast<std::uint8_t>(
            GeometryPulseBufferState::CURRENT);
        if (!geometryPulseBufferStates_[pulseIndex].compare_exchange_strong(
                expected,
                static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            surfaceApi_.deleteTransaction(transaction);
            releaseCallbackCookie(*cookieIndex);
            return false;
        }
        cookie->geometryPulseUpdate = true;
        cookie->geometryPulseBufferIndex = UINT32_MAX;
        cookie->previousGeometryPulseBufferIndex = pulseIndex;
        currentGeometryPulseBufferIndex_.reset();
        surfaceApi_.reparent(transaction, geometryPulseSurface_, nullptr);
    }
    teardownCompleted_ = false;
    surfaceApi_.reparent(transaction, childSurface_, nullptr);
    surfaceApi_.setOnComplete(transaction, cookie, &onCompleted);
    surfaceApi_.applyTransaction(transaction);
    surfaceApi_.deleteTransaction(transaction);
    std::unique_lock<std::mutex> lock(teardownMutex_);
    teardownCondition_.wait(lock, [this] { return teardownCompleted_; });
    lock.unlock();
    releaseCallbackCookie(*cookieIndex);

    bool teardownEventConsumed = false;
    while (!pool_.allFree() || !teardownEventConsumed ||
           !geometryPulseBuffersAllFree()) {
        FixedPresentEvent event{};
        {
            std::unique_lock<std::mutex> eventLock(eventMutex_);
            eventCondition_.wait(eventLock, [this] {
                return eventCount_ != 0 || eventOverflowed_.load(
                    std::memory_order_acquire) ||
                    geometryPulseBuffersAllFree();
            });
            if (eventOverflowed_.load(std::memory_order_acquire)) return false;
            if (eventCount_ == 0) continue;
            event = events_[eventRead_];
            eventRead_ = (eventRead_ + 1) % events_.size();
            --eventCount_;
        }
        if (event.kind == FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED) {
            if (!consumePreviousBufferReleased(event)) {
                return false;
            }
            teardownReleaseEventSequence_.store(
                event.eventSequence, std::memory_order_release);
        } else if (event.kind == FixedPresentEventKind::TEARDOWN_COMPLETED) {
            teardownEventConsumed = true;
        } else {
            return false;
        }
    }
    return true;
}

bool SurfaceControlPresentBackend::retireAfterParentLifecycleEvidenceDrained() {
    if (!attached_ || childSurface_ == nullptr || hasOutstandingSubmission() ||
        preparedTransactionState_ != PreparedTransactionState::EMPTY ||
        previousReleaseRecordCount() != 0 ||
        acquireFenceRecordCount() != 0 ||
        appOwnedAcquireFdCount() != 0 || hasPendingEvent()) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) return false;
        for (const auto& watch : pendingFenceWatches_) {
            if (watch.occupied) return false;
        }
        for (const auto& watch : activeFenceWatches_) {
            if (watch.occupied) return false;
        }
    }
    if (latestAppliedBufferRef_.has_value()) {
        const auto& identity = latestAppliedBufferRef_->identity;
        if (!pool_.markReleased(identity.bufferSlot, identity.bufferGeneration)) {
            return false;
        }
    }
    latestAppliedBufferRef_.reset();
    latestConsumedCompositorLatchRef_.reset();
    latestConsumedCompositorLatchEventSequence_ = 0;
    latestConsumedCompositorLatchNanos_ = 0;
    latestConsumedCompositorLatchObservedNanos_ = 0;
    logicalUnlatchedNow_ = 0;
    backpressureEnabled_ = false;
    currentGeometryPulseBufferIndex_.reset();
    for (auto& state : geometryPulseBufferStates_) {
        state.store(
            static_cast<std::uint8_t>(GeometryPulseBufferState::FREE),
            std::memory_order_release);
    }
    return pool_.allFree() && geometryPulseBuffersAllFree();
}

bool SurfaceControlPresentBackend::destroy() {
    if (attached_) {
        bool eventsEmpty = false;
        {
            std::lock_guard<std::mutex> lock(eventMutex_);
            eventsEmpty = eventCount_ == 0;
        }
        bool fencesEmpty = false;
        {
            std::lock_guard<std::mutex> lock(fenceMutex_);
            fencesEmpty = true;
            for (const auto& watch : pendingFenceWatches_) {
                fencesEmpty = fencesEmpty && !watch.occupied;
            }
            for (const auto& watch : activeFenceWatches_) {
                fencesEmpty = fencesEmpty && !watch.occupied;
            }
        }
        if (preparedTransactionState_ != PreparedTransactionState::EMPTY ||
            hasOutstandingSubmission() ||
            previousReleaseRecordCount() != 0 ||
            acquireFenceRecordCount() != 0 ||
            appOwnedAcquireFdCount() != 0 || !eventsEmpty || !fencesEmpty ||
            latestAppliedBufferRef_.has_value() ||
            latestConsumedCompositorLatchRef_.has_value() ||
            logicalUnlatchedNow_ != 0 || !pool_.allFree() ||
            !geometryPulseBuffersAllFree()) {
            return false;
        }
    }
    stopFenceReactor();
    if (fenceControlFd_ >= 0) {
        close(fenceControlFd_);
        fenceControlFd_ = -1;
    }
    pool_.destroy();
    releaseGeometryPulseResources();
    if (geometrySurface_ != nullptr && geometrySurface_ != childSurface_) {
        surfaceApi_.releaseSurface(geometrySurface_);
    }
    geometrySurface_ = nullptr;
    if (childSurface_ != nullptr) {
        surfaceApi_.releaseSurface(childSurface_);
        childSurface_ = nullptr;
    }
    if (parentWindow_ != nullptr) {
        ANativeWindow_release(parentWindow_);
        parentWindow_ = nullptr;
    }
    display_ = EGL_NO_DISPLAY;
    width_ = 0;
    height_ = 0;
    destinationWidth_ = 0;
    destinationHeight_ = 0;
    createSync_ = nullptr;
    destroySync_ = nullptr;
    dupNativeFenceFd_ = nullptr;
    syncFileInfo_ = nullptr;
    syncFileInfoFree_ = nullptr;
    hardwareBufferAllocate_ = nullptr;
    hardwareBufferRelease_ = nullptr;
    hardwareBufferLock_ = nullptr;
    hardwareBufferUnlock_ = nullptr;
    surfaceApi_ = {};
    if (androidLibrary_ != nullptr) {
        dlclose(androidLibrary_);
        androidLibrary_ = nullptr;
    }
    if (syncLibrary_ != nullptr) {
        dlclose(syncLibrary_);
        syncLibrary_ = nullptr;
    }
    latestAppliedBufferRef_.reset();
    latestConsumedCompositorLatchRef_.reset();
    latestConsumedCompositorLatchEventSequence_ = 0;
    latestConsumedCompositorLatchNanos_ = 0;
    latestConsumedCompositorLatchObservedNanos_ = 0;
    logicalUnlatchedNow_ = 0;
    maxLogicalUnlatched_ = 0;
    appliedCallbacks_ = {};
    previousReleases_ = {};
    acquireFences_ = {};
    localAcquireFence_.reset();
    for (std::size_t i = 0; i < callbackCookies_.size(); ++i) {
        releaseCallbackCookie(i);
    }
    maxAppliedCallbackRecordCount_ = 0;
    maxCommitProofPending_ = 0;
    maxCompleteProofPending_ = 0;
    applyBeforePriorCompleteCount_ = 0;
    applyBeforePriorCommitConsumedCount_ = 0;
    priorOnCompletePendingAtSuccessorApply_ = 0;
    backendInvariantFatalCount_ = 0;
    applyBeforeAcquireSignalProvenCount_ = 0;
    lastLatchConsumedToSuccessorApplyNanos_ = 0;
    lastSuccessorApplyMinusPriorCompleteNanos_ = 0;
    lastSuccessorReadyMinusPriorCompleteNanos_ = 0;
    preparedTransactionState_ = PreparedTransactionState::EMPTY;
    preparedTransactionSerial_ = 0;
    surfaceEpoch_ = 0;
    surfaceSerial_ = 0;
    transactionSerial_ = 0;
    acquireFenceSerial_ = 0;
    appliedBufferRefSerial_ = 0;
    directAdmissionSequence_ = 0;
    directFrameTimelineIdentity_ = 0;
    backpressureEnabled_ = false;
    backpressureEnableCount_ = 0;
    backpressureDisableCount_ = 0;
    capacityExhaustedCount_ = 0;
    capacityWaitCount_ = 0;
    maxHeldFrameworkRefCount_ = 0;
    minFreeReusableCount_ = HardwareBufferRenderTargetPool::kSlotCount;
    minAppOwnedBufferDomain_ = HardwareBufferRenderTargetPool::kSlotCount;
    wakeCallback_ = nullptr;
    wakeContext_ = nullptr;
    fenceLooperReady_ = false;
    fenceLooperFailed_ = false;
    fenceStopping_.store(false, std::memory_order_release);
    teardownCompleted_ = false;
    teardownReleaseEventSequence_.store(0, std::memory_order_release);
    prepared_ = false;
    cpuComposerOnly_ = false;
    attached_ = false;
    return true;
}

SurfaceControlPresentBackend::ConservationSnapshot
SurfaceControlPresentBackend::conservationSnapshot() {
    ConservationSnapshot snapshot{};
    snapshot.outstandingSubmissionCount = callbackRecordCount();
    snapshot.maxOutstandingSubmissionCount = maxAppliedCallbackRecordCount_;
    snapshot.callbackRecordDepth = snapshot.outstandingSubmissionCount;
    snapshot.maxCallbackRecordDepth = snapshot.maxOutstandingSubmissionCount;
    snapshot.previousReleaseRecordDepth = previousReleaseRecordCount();
    // The acquire-proof reactor can retire a record as soon as its fence
    // signals.  Record depth, owned descriptors, and watch ownership therefore
    // form one conservation cut and must be sampled under the same lock.
    // Sampling them through separate helpers allowed a signal transition
    // between the reads, manufacturing a transient depth=1/fd=0 mismatch.
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (localAcquireFence_.has_value()) {
            if (localAcquireFence_->frameworkAcquireFd >= 0) {
                ++snapshot.appOwnedAcquireFdCount;
            }
            if (localAcquireFence_->proofAcquireFd >= 0) {
                ++snapshot.appOwnedAcquireFdCount;
            }
        }
        for (const auto& record : acquireFences_) {
            if (!record.has_value()) continue;
            ++snapshot.acquireFenceRecordDepth;
            if (record->proofFd >= 0) ++snapshot.appOwnedAcquireFdCount;
        }
        for (const auto& watch : pendingFenceWatches_) {
            if (watch.occupied) ++snapshot.pendingFenceWatchCount;
        }
        for (const auto& watch : activeFenceWatches_) {
            if (watch.occupied) ++snapshot.activeFenceWatchCount;
        }
    }
    snapshot.logicalUnlatchedNow = logicalUnlatchedNow_;
    snapshot.maxLogicalUnlatched = maxLogicalUnlatched_;
    snapshot.latestAppliedBufferRefSerial =
        latestAppliedBufferRef_.has_value()
            ? latestAppliedBufferRef_->serial : 0;
    snapshot.latestConsumedLatchRefSerial =
        latestConsumedCompositorLatchRef_.has_value()
            ? latestConsumedCompositorLatchRef_->serial : 0;
    snapshot.applyBeforePriorCompleteCount = applyBeforePriorCompleteCount_;
    snapshot.applyBeforePriorCommitConsumedCount =
        applyBeforePriorCommitConsumedCount_;
    snapshot.priorOnCompletePendingAtSuccessorApply =
        priorOnCompletePendingAtSuccessorApply_;
    snapshot.applyBeforeAcquireSignalProvenCount =
        applyBeforeAcquireSignalProvenCount_;
    snapshot.backendInvariantFatalCount = backendInvariantFatalCount_;
    snapshot.lastLatchConsumedToSuccessorApplyNanos =
        lastLatchConsumedToSuccessorApplyNanos_;
    snapshot.lastSuccessorApplyMinusPriorCompleteNanos =
        lastSuccessorApplyMinusPriorCompleteNanos_;
    snapshot.lastSuccessorReadyMinusPriorCompleteNanos =
        lastSuccessorReadyMinusPriorCompleteNanos_;
    snapshot.preparedTransactionState = preparedTransactionState_;
    snapshot.poolStates = pool_.stateSnapshot();
    for (const auto state : snapshot.poolStates) {
        if (state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_CHAIN_HEAD) {
            ++snapshot.latchedCurrentCount;
            ++snapshot.heldFrameworkRefCount;
        }
        if (state == HardwareBufferRenderTargetPool::SlotState::
                FRAMEWORK_REPLACED_WAIT_RELEASE) {
            ++snapshot.releaseWaitCount;
            ++snapshot.heldFrameworkRefCount;
        }
        if (state == HardwareBufferRenderTargetPool::SlotState::FREE) {
            ++snapshot.freeReusableCount;
        }
    }
    snapshot.appOwnedBufferDomainNow =
        static_cast<std::uint32_t>(HardwareBufferRenderTargetPool::kSlotCount) -
        snapshot.heldFrameworkRefCount;
    maxHeldFrameworkRefCount_ = std::max(
        maxHeldFrameworkRefCount_, snapshot.heldFrameworkRefCount);
    minFreeReusableCount_ = std::min(
        minFreeReusableCount_, snapshot.freeReusableCount);
    minAppOwnedBufferDomain_ = std::min(
        minAppOwnedBufferDomain_, snapshot.appOwnedBufferDomainNow);
    snapshot.maxHeldFrameworkRefCount = maxHeldFrameworkRefCount_;
    snapshot.minFreeReusableCount = minFreeReusableCount_;
    snapshot.minAppOwnedBufferDomain = minAppOwnedBufferDomain_;
    for (const auto& record : appliedCallbacks_) {
        if (!record.has_value()) continue;
        if (!record->commitEventConsumed) ++snapshot.commitProofPendingNow;
        if ((!record->geometryOnly || record->requiresComplete) &&
            !record->completeEventConsumed) {
            ++snapshot.completeProofPendingNow;
        }
        if ((!record->geometryOnly || record->requiresComplete) &&
            record->commitEventConsumed &&
            !record->completeEventConsumed) {
            ++snapshot.retainedWaitingOnCompleteCount;
        }
    }
    // Exactly the just-submitted frame still awaits its own OnCommit. Older
    // callback records have already supplied successor authority and may wait
    // only on OnComplete/release cleanup.
    snapshot.submittedWaitLatchCount = snapshot.commitProofPendingNow;
    snapshot.maxCommitProofPending = maxCommitProofPending_;
    snapshot.maxCompleteProofPending = maxCompleteProofPending_;
    snapshot.backpressureEnableCount = backpressureEnableCount_;
    snapshot.backpressureDisableCount = backpressureDisableCount_;
    snapshot.capacityExhaustedCount = capacityExhaustedCount_;
    snapshot.capacityWaitCount = capacityWaitCount_;
    snapshot.teardownReleaseEventSequence =
        teardownReleaseEventSequence_.load(std::memory_order_acquire);
    return snapshot;
}

}  // namespace ntk::present
