#include "SurfaceControlPresentBackend.h"
#include "../swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"

#include <android/api-level.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cerrno>
#include <algorithm>
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
    return createFromWindow != nullptr && releaseSurface != nullptr &&
        createTransaction != nullptr && deleteTransaction != nullptr &&
        applyTransaction != nullptr && setOnComplete != nullptr &&
        setOnCommit != nullptr && reparent != nullptr &&
        setVisibility != nullptr && setBuffer != nullptr &&
        setGeometry != nullptr && setBufferTransparency != nullptr &&
        setBufferAlpha != nullptr && setEnableBackPressure != nullptr &&
        setFrameTimeline != nullptr && getLatchTime != nullptr &&
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
        cookie.teardown = false;
        cookie.slotIndex = static_cast<std::uint32_t>(i);
        cookie.onCommitCount.store(0, std::memory_order_release);
        cookie.onCompleteCount.store(0, std::memory_order_release);
        cookie.onCommitEventSequence.store(0, std::memory_order_release);
        cookie.onCompleteEventSequence.store(0, std::memory_order_release);
        cookie.onCommitLatchNanos.store(0, std::memory_order_release);
        cookie.onCommitObservedNanos.store(0, std::memory_order_release);
        cookie.onCompleteObservedNanos.store(0, std::memory_order_release);
        cookie.lifecycleFlags.store(0, std::memory_order_release);
        return i;
    }
    return std::nullopt;
}

void SurfaceControlPresentBackend::releaseCallbackCookie(
        std::size_t index) noexcept {
    if (index >= callbackCookies_.size()) return;
    SubmissionCookie& cookie = callbackCookies_[index];
    cookie.backend = nullptr;
    cookie.identity = {};
    cookie.previousAppliedBufferRef = {};
    cookie.hasPreviousAppliedBufferRef = false;
    cookie.teardown = false;
    cookie.slotIndex = UINT32_MAX;
    cookie.onCommitCount.store(0, std::memory_order_release);
    cookie.onCompleteCount.store(0, std::memory_order_release);
    cookie.onCommitEventSequence.store(0, std::memory_order_release);
    cookie.onCompleteEventSequence.store(0, std::memory_order_release);
    cookie.onCommitLatchNanos.store(0, std::memory_order_release);
    cookie.onCommitObservedNanos.store(0, std::memory_order_release);
    cookie.onCompleteObservedNanos.store(0, std::memory_order_release);
    cookie.lifecycleFlags.store(0, std::memory_order_release);
    cookie.inUse.store(false, std::memory_order_release);
}

void SurfaceControlPresentBackend::completeCallbackPublication(
        SubmissionCookie& cookie) noexcept {
    const std::uint32_t previous = cookie.lifecycleFlags.fetch_or(
        kCookiePublicationComplete, std::memory_order_acq_rel);
    if ((previous & kCookieRecordConsumed) != 0U) {
        releaseCallbackCookie(cookie.slotIndex);
    }
}

void SurfaceControlPresentBackend::completeCallbackRecordConsumption(
        std::size_t index) noexcept {
    if (index >= callbackCookies_.size()) return;
    SubmissionCookie& cookie = callbackCookies_[index];
    const std::uint32_t previous = cookie.lifecycleFlags.fetch_or(
        kCookieRecordConsumed, std::memory_order_acq_rel);
    if ((previous & kCookiePublicationComplete) != 0U) {
        releaseCallbackCookie(index);
    }
}

bool SurfaceControlPresentBackend::stateInvariantsHold() const noexcept {
    std::uint32_t exported = 0;
    std::uint32_t chainHead = 0;
    std::uint32_t replacedWait = 0;
    const auto states = pool_.stateSnapshot();
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
                localAcquireFence_->frameworkAcquireFd < 0 ||
                localAcquireFence_->proofAcquireFd < 0) return false;
        } else if (exported != 0) {
            return false;
        }
    }
    if (logicalUnlatchedNow_ > kMaxDirectLogicalUnlatched ||
        maxLogicalUnlatched_ > kMaxDirectLogicalUnlatched ||
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
        const std::int64_t completeObservedNanos =
            cookie.onCompleteObservedNanos.load(std::memory_order_acquire);
        if (!cookie.inUse.load(std::memory_order_acquire) ||
            cookie.backend != this || cookie.slotIndex != left.cookieIndex ||
            !exactIdentity(cookie.identity, left.identity) ||
            !left.applyIssued || !validAppliedBufferRef(left.producedRef) ||
            !exactIdentity(left.identity, left.producedRef.identity) ||
            left.poisoned || commitCount > 1 || completeCount > 1 ||
            completeCount > commitCount ||
            left.consumedOnCommitCount > 1 ||
            left.consumedOnCompleteCount > 1 ||
            left.commitEventConsumed !=
                (left.consumedOnCommitCount == 1) ||
            left.completeEventConsumed !=
                (left.consumedOnCompleteCount == 1)) return false;
        if (commitSequence != 0 &&
            (commitCount != 1 || commitLatchNanos <= 0 ||
             commitObservedNanos < commitLatchNanos)) {
            return false;
        }
        if (completeSequence != 0 &&
            (completeCount != 1 || commitSequence == 0 ||
             completeObservedNanos <= 0 ||
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
        if (left.completeEventConsumed) {
            if (completeSequence == 0 ||
                left.completeEventSequence != completeSequence ||
                left.completeCallbackObservedNanos !=
                    completeObservedNanos) {
                return false;
            }
        } else {
            if (left.completeEventSequence != 0 ||
                left.completeCallbackObservedNanos != 0) return false;
            ++completePending;
        }
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
        std::uint32_t height) {
    if (display == EGL_NO_DISPLAY || width == 0 || height == 0 ||
        android_get_device_api_level() < 33) {
        return false;
    }
    if (preparedFor(display, width, height)) {
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
    androidLibrary_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (androidLibrary_ == nullptr) {
        (void)destroy();
        return false;
    }
#define NTK_LOAD_SURFACE(member, symbol) \
    surfaceApi_.member = reinterpret_cast<decltype(surfaceApi_.member)>( \
        dlsym(androidLibrary_, symbol))
    NTK_LOAD_SURFACE(createFromWindow, "ASurfaceControl_createFromWindow");
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
    NTK_LOAD_SURFACE(
        setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
    NTK_LOAD_SURFACE(setBufferAlpha, "ASurfaceTransaction_setBufferAlpha");
    NTK_LOAD_SURFACE(
        setEnableBackPressure, "ASurfaceTransaction_setEnableBackPressure");
    NTK_LOAD_SURFACE(setFrameTimeline, "ASurfaceTransaction_setFrameTimeline");
    NTK_LOAD_SURFACE(
        getLatchTime, "ASurfaceTransactionStats_getLatchTime");
    NTK_LOAD_SURFACE(
        getPreviousReleaseFenceFd,
        "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
#undef NTK_LOAD_SURFACE
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
    if (!surfaceApi_.complete() || createSync_ == nullptr ||
        destroySync_ == nullptr || dupNativeFenceFd_ == nullptr ||
        syncFileInfo_ == nullptr || syncFileInfoFree_ == nullptr) {
        (void)destroy();
        return false;
    }
    if (!pool_.initialize(display_, width_, height_)) {
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
        std::uint32_t width,
        std::uint32_t height,
        std::uint64_t surfaceEpoch,
        WakeCallback wakeCallback,
        void* wakeContext) {
    if (attached_ || display == EGL_NO_DISPLAY || parentWindow == nullptr ||
        width == 0 || height == 0 || surfaceEpoch == 0 ||
        android_get_device_api_level() < 33 || !prepare(display, width, height)) {
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
    childSurface_ = surfaceApi_.createFromWindow(parentWindow_, "NtkStripLayer");
    if (childSurface_ == nullptr) {
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

bool SurfaceControlPresentBackend::exportAcquireFence(
        HardwareBufferRenderTargetPool::RenderTarget& target,
        std::int64_t renderBeginNanos,
        std::int64_t renderEndNanos,
        GpuSubmissionProof* proof) {
    if (!attached_ || proof == nullptr || renderBeginNanos <= 0 ||
        renderEndNanos < renderBeginNanos ||
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
    // Export the GPU fence from EGL exactly once. A second
    // eglDupNativeFenceFDANDROID call re-enters the host GPU driver and can
    // stall the rolling reader long enough to miss the following compositor
    // cycle. dup() creates another descriptor for the same sync_file, so the
    // framework hand-off and our signal proof retain independent ownership
    // without a second driver round trip.
    int frameworkFd = dupNativeFenceFd_(display_, fence);
    int proofFd = frameworkFd >= 0 ? dup(frameworkFd) : -1;
    const bool destroyed = destroySync_(display_, fence) == EGL_TRUE;
    const std::int64_t exportReturnNanos = monotonicNowNanos();
    const bool descriptorsExact = frameworkFd >= 0 && proofFd >= 0 &&
        frameworkFd != proofFd && setCloseOnExec(frameworkFd) &&
        setCloseOnExec(proofFd);
    if (!destroyed || !descriptorsExact ||
        fenceIssuedNanos < renderEndNanos ||
        exportReturnNanos < fenceIssuedNanos) {
        if (frameworkFd >= 0) close(frameworkFd);
        if (proofFd >= 0) close(proofFd);
        return false;
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
        .renderBeginNanos = renderBeginNanos,
        .renderEndNanos = renderEndNanos,
        .acquireFenceIssuedNanos = fenceIssuedNanos,
        .acquireFenceExportReturnNanos = exportReturnNanos,
        .acquireFenceSerial = serial,
        .acquireFenceDupCount = 2,
        .rendererGpuClientWaitCount = 0,
    };
    return validGpuSubmissionProof(*proof);
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
    if (!hasPreparationCapacity() || prepared == nullptr || proof == nullptr ||
        target.state != HardwareBufferRenderTargetPool::SlotState::
            ACQUIRE_FENCE_EXPORTED ||
        target.hardwareBuffer == nullptr ||
        baseIdentity.surfaceEpoch != surfaceEpoch_ ||
        baseIdentity.engineGeneration == 0 ||
        baseIdentity.workGeneration == 0 || baseIdentity.ntkFrameId == 0 ||
        baseIdentity.authorityGeneration <= 0 || baseIdentity.authority <= 0 ||
        baseIdentity.frameSequence == 0 || baseIdentity.capsuleSequence == 0 ||
        firstStage != !latestAppliedBufferRef_.has_value() ||
        (firstStage
            ? (backpressureEnabled_ || backpressureEnableCount_ != 0)
            : (!backpressureEnabled_ || backpressureEnableCount_ != 1)) ||
        backpressureDisableCount_ != 0 ||
        !validFixedTransportProfile(profile)) {
        return false;
    }
    std::uint64_t acquireFenceSerial = 0;
    {
        std::lock_guard<std::mutex> lock(fenceMutex_);
        if (!localAcquireFence_.has_value() ||
            localAcquireFence_->phase != LocalAcquirePhase::EXPORTED_UNBOUND ||
            localAcquireFence_->buffer.slot != target.slot ||
            localAcquireFence_->buffer.generation != target.generation ||
            localAcquireFence_->acquireFenceSerial == 0 ||
            localAcquireFence_->frameworkAcquireFd < 0 ||
            localAcquireFence_->proofAcquireFd < 0) return false;
        acquireFenceSerial = localAcquireFence_->acquireFenceSerial;
    }
    const std::int64_t prepareBegin = monotonicNowNanos();
    if (prepareBegin <= 0) return false;
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
        return false;
    }
    if (firstStage) {
        const ARect source{0, 0, static_cast<std::int32_t>(width_),
            static_cast<std::int32_t>(height_)};
        const ARect destination{
            0, 0,
            static_cast<std::int32_t>(destinationWidth_ > 0 ? destinationWidth_ : width_),
            static_cast<std::int32_t>(destinationHeight_ > 0 ? destinationHeight_ : height_)};
        surfaceApi_.setGeometry(
            transaction, childSurface_, source, destination, 0);
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
    prepared->acquireFenceDupCount = 2;
    prepared->setBufferPending = 1;
    prepared->callbackCookieIndex = static_cast<std::uint32_t>(*cookieIndex);
    prepared->previousAppliedBufferRef =
        latestAppliedBufferRef_.value_or(AppliedBufferRef{});
    prepared->reservedAppliedBufferRefSerial =
        reservedAppliedRefSerial;
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
    proof->acquireFenceDupCount = 2;
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
            return false;
        }
        localAcquireFence_->phase = LocalAcquirePhase::BOUND_TO_PREPARED;
        localAcquireFence_->preparedTransactionSerial =
            identity.transactionSerial;
    }
    preparedTransactionState_ = PreparedTransactionState::PREPARED_NOT_CLAIMED;
    preparedTransactionSerial_ = identity.transactionSerial;
    return true;
}

bool SurfaceControlPresentBackend::abortRenderTargetBeforePreparation(
        std::uint64_t bufferSlot, std::uint64_t bufferGeneration) {
    if (!attached_) return false;
    auto* target = pool_.find(bufferSlot, bufferGeneration);
    if (target == nullptr) return false;
    if (target->state ==
        HardwareBufferRenderTargetPool::SlotState::RENDERING) {
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
            localAcquireFence_->frameworkAcquireFd >= 0 &&
            localAcquireFence_->proofAcquireFd >= 0;
    }
    const bool preparedExact = attached_ && childSurface_ != nullptr &&
        prepared.transaction != nullptr && cookie != nullptr &&
        prepared.callbackCookieIndex < callbackCookies_.size() &&
        cookie == &callbackCookies_[prepared.callbackCookieIndex] &&
        cookie->inUse.load(std::memory_order_acquire) &&
        target != nullptr && target->state ==
            HardwareBufferRenderTargetPool::SlotState::
                ACQUIRE_FENCE_EXPORTED && localAcquireExact &&
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
    if (!freeAppliedCallbackRecordIndex().has_value() ||
        !freeAcquireFenceRecordIndex().has_value() ||
        (latestAppliedBufferRef_.has_value() &&
         !freePreviousReleaseRecordIndex().has_value()) ||
        heldFrameworkRefs > HardwareBufferRenderTargetPool::kSlotCount - 2) {
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
        prepared, claim, receipt, false);
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyPreparedBufferTransactionDirect(
        PreparedSurfaceSubmission& prepared,
        SubmissionReceipt* receipt) {
    const std::int64_t now = monotonicNowNanos();
    if (now <= 0 || prepared.transaction == nullptr ||
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
    claim.plannedTargetFrame = static_cast<std::int64_t>(timelineIdentity);
    claim.frameTimelineVsyncId = static_cast<std::int64_t>(timelineIdentity);
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
    claim.acquireFenceDupCount = 2;
    claim.setBufferPending = 1;
    claim.firstStage = prepared.firstStage ? 1U : 0U;
    claim.previousAppliedBufferRef = toSwappyAppliedBufferRef(
        prepared.previousAppliedBufferRef);
    return applyPreparedBufferTransactionImpl(
        prepared, claim, receipt, true);
}

SurfaceControlPresentBackend::ApplyDisposition
SurfaceControlPresentBackend::applyPreparedBufferTransactionImpl(
        PreparedSurfaceSubmission& prepared,
        const SwappyFixedExternalClaim& claim,
        SubmissionReceipt* receipt,
        bool directSubmission) {
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
        claim.setBufferCount == 0 && claim.acquireFenceDupCount == 2 &&
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
    const auto acquireIndex = freeAcquireFenceRecordIndex();
    const auto releaseIndex = latestAppliedBufferRef_.has_value()
        ? freePreviousReleaseRecordIndex() : std::optional<std::size_t>{};
    if (!callbackIndex.has_value() || !acquireIndex.has_value() ||
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
    if (!directSubmission) {
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
        if (!record->completeEventConsumed) ++completePending;
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
    FixedPresentEvent acquireEvent{};
    acquireEvent.kind = FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED;
    acquireEvent.identity = appliedIdentity;
    acquireEvent.acquireFenceSerial = prepared.acquireFenceSerial;
    const std::uint64_t applyBeforeAcquireBaseline =
        applyBeforeAcquireSignalProvenCount_;
    if (proofAcquireFd < 0 || !publishAcquireFenceProofAfterApply(
            *acquireIndex, proofAcquireFd, acquireEvent)) {
        ++backendInvariantFatalCount_;
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
    event.latchNanos = stats != nullptr
        ? cookie->backend->surfaceApi_.getLatchTime(stats) : 0;
    event.callbackObservedNanos = observedNanos;
    event.onCommitCallbackCount = count;
    event.onCompleteCallbackCount =
        cookie->onCompleteCount.load(std::memory_order_acquire);
    if (count == 1 && firstObserved) {
        cookie->onCommitLatchNanos.store(
            event.latchNanos, std::memory_order_release);
        cookie->onCommitEventSequence.store(
            event.eventSequence, std::memory_order_release);
    }
    if (count != 1 || !firstObserved || observedNanos <= 0 ||
        event.latchNanos <= 0) {
        event.kind = FixedPresentEventKind::INVALID_CALLBACK;
    }
    cookie->backend->publishEvent(event);
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

    FixedPresentEvent completed{};
    completed.kind = cookie->teardown
        ? FixedPresentEventKind::TEARDOWN_COMPLETED
        : FixedPresentEventKind::TRANSACTION_COMPLETED;
    completed.identity = cookie->identity;
    completed.eventSequence = backend->eventSequence_.fetch_add(
        1, std::memory_order_acq_rel) + 1;
    completed.callbackObservedNanos = observedNanos;
    completed.onCommitCallbackCount = commitCount;
    completed.onCompleteCallbackCount = count;
    if (count == 1 && firstObserved) {
        cookie->onCompleteEventSequence.store(
            completed.eventSequence, std::memory_order_release);
    }
    if ((!cookie->teardown && commitCount != 1) || count != 1 ||
        !firstObserved || observedNanos <= 0 || stats == nullptr) {
        completed.kind = FixedPresentEventKind::INVALID_CALLBACK;
    }
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
    backend->completeCallbackPublication(*cookie);
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
        if (record.completeEventConsumed) {
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
        const bool exact = event.kind ==
                FixedPresentEventKind::TRANSACTION_COMPLETED &&
            event.structSize == sizeof(event) &&
            event.schemaVersion == kFixedPresentEventSchemaVersion &&
            event.eventSequence != 0 && event.onCommitCallbackCount == 1 &&
            event.onCompleteCallbackCount == 1 &&
            record.applyIssued && !record.completeEventConsumed &&
            record.consumedOnCompleteCount == 0 && !record.poisoned &&
            cookie.inUse.load(std::memory_order_acquire) &&
            cookie.backend == this && cookie.slotIndex == record.cookieIndex &&
            exactIdentity(cookie.identity, record.identity) &&
            cookie.onCommitCount.load(std::memory_order_acquire) == 1 &&
            cookie.onCompleteCount.load(std::memory_order_acquire) == 1 &&
            callbackSequence == event.eventSequence &&
            callbackObservedNanos == event.callbackObservedNanos;
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
    while (!pool_.allFree() || !teardownEventConsumed) {
        FixedPresentEvent event{};
        {
            std::unique_lock<std::mutex> eventLock(eventMutex_);
            eventCondition_.wait(eventLock, [this] {
                return eventCount_ != 0 || eventOverflowed_.load(
                    std::memory_order_acquire);
            });
            if (eventOverflowed_.load(std::memory_order_acquire)) return false;
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
    return pool_.allFree();
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
            logicalUnlatchedNow_ != 0 || !pool_.allFree()) {
            return false;
        }
    }
    stopFenceReactor();
    if (fenceControlFd_ >= 0) {
        close(fenceControlFd_);
        fenceControlFd_ = -1;
    }
    pool_.destroy();
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
        if (!record->completeEventConsumed) ++snapshot.completeProofPendingNow;
        if (record->commitEventConsumed &&
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
