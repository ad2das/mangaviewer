#include "DirectTileSurfacePresenter.h"

#include <android/data_space.h>
#include <android/surface_control.h>
#include <linux/sync_file.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <new>
#include <optional>
#include <poll.h>
#include <pthread.h>
#include <sys/resource.h>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <utility>

namespace ntk::present {

namespace {

constexpr std::size_t kLayerCount = 16;
constexpr std::size_t kCookieCount = 32;
constexpr std::size_t kPulseBufferCount = 8;
constexpr std::uint32_t kMaxLogicalUncommitted = 30;
constexpr std::size_t kMaxQueuedEvents = 512;

std::int64_t monotonicNowNanos() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(value.tv_sec) * 1'000'000'000LL + value.tv_nsec;
}

bool setCloseOnExec(int fd) noexcept {
    if (fd < 0) return false;
    const int flags = fcntl(fd, F_GETFD);
    return flags >= 0 && fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0;
}

bool waitFence(int fd) noexcept {
    if (fd < 0) return true;
    pollfd descriptor{.fd = fd, .events = POLLIN, .revents = 0};
    int result = -1;
    do {
        result = poll(&descriptor, 1, -1);
    } while (result < 0 && errno == EINTR);
    return result > 0 && (descriptor.revents & (POLLIN | POLLERR | POLLHUP)) != 0;
}

int duplicateFence(int fd) noexcept {
    if (fd < 0) return -1;
    int duplicate = -1;
    do {
        duplicate = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    } while (duplicate < 0 && errno == EINTR);
    return duplicate;
}

}  // namespace

struct DirectTileSurfacePresenter::Impl {
    using TransactionCallback = void (*)(void*, ASurfaceTransactionStats*);
    using SyncFileInfoFn = ::sync_file_info* (*)(std::int32_t);
    using SyncFileInfoFreeFn = void (*)(::sync_file_info*);

    struct Api {
        ASurfaceControl* (*createFromWindow)(ANativeWindow*, const char*) = nullptr;
        ASurfaceControl* (*create)(ASurfaceControl*, const char*) = nullptr;
        void (*releaseSurface)(ASurfaceControl*) = nullptr;
        ASurfaceTransaction* (*createTransaction)() = nullptr;
        void (*deleteTransaction)(ASurfaceTransaction*) = nullptr;
        void (*applyTransaction)(ASurfaceTransaction*) = nullptr;
        void (*setOnCommit)(ASurfaceTransaction*, void*, TransactionCallback) = nullptr;
        void (*setOnComplete)(ASurfaceTransaction*, void*, TransactionCallback) = nullptr;
        void (*setVisibility)(ASurfaceTransaction*, ASurfaceControl*,
                              ASurfaceTransactionVisibility) = nullptr;
        void (*setBuffer)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int) = nullptr;
        void (*setGeometry)(ASurfaceTransaction*, ASurfaceControl*, const ARect&, const ARect&,
                            std::int32_t) = nullptr;
        void (*setPosition)(ASurfaceTransaction*, ASurfaceControl*, std::int32_t,
                            std::int32_t) = nullptr;
        void (*setScale)(ASurfaceTransaction*, ASurfaceControl*, float, float) = nullptr;
        void (*setBufferTransparency)(ASurfaceTransaction*, ASurfaceControl*,
                                      ASurfaceTransactionTransparency) = nullptr;
        void (*setBufferAlpha)(ASurfaceTransaction*, ASurfaceControl*, float) = nullptr;
        void (*setZOrder)(ASurfaceTransaction*, ASurfaceControl*, std::int32_t) = nullptr;
        void (*setFrameRate)(ASurfaceTransaction*, ASurfaceControl*, float, std::int8_t) = nullptr;
        std::int64_t (*getLatchTime)(ASurfaceTransactionStats*) = nullptr;
        int (*getPresentFenceFd)(ASurfaceTransactionStats*) = nullptr;
        int (*getPreviousReleaseFenceFd)(ASurfaceTransactionStats*, ASurfaceControl*) = nullptr;

        bool complete() const noexcept {
            return createFromWindow != nullptr && create != nullptr && releaseSurface != nullptr &&
                createTransaction != nullptr && deleteTransaction != nullptr &&
                applyTransaction != nullptr && setOnCommit != nullptr &&
                setOnComplete != nullptr && setVisibility != nullptr &&
                setBuffer != nullptr && setGeometry != nullptr &&
                setPosition != nullptr && setScale != nullptr &&
                setBufferTransparency != nullptr && setBufferAlpha != nullptr &&
                setZOrder != nullptr && getLatchTime != nullptr &&
                getPresentFenceFd != nullptr && getPreviousReleaseFenceFd != nullptr;
        }
    };

    using HardwareBufferIsSupportedFn = int (*)(const AHardwareBuffer_Desc*);
    using HardwareBufferAllocateFn = int (*)(const AHardwareBuffer_Desc*, AHardwareBuffer**);
    using HardwareBufferAcquireFn = void (*)(AHardwareBuffer*);
    using HardwareBufferReleaseFn = void (*)(AHardwareBuffer*);
    using HardwareBufferLockFn = int (*)(
        AHardwareBuffer*, std::uint64_t, std::int32_t, const ARect*, void**);
    using HardwareBufferUnlockFn = int (*)(AHardwareBuffer*, std::int32_t*);

    struct LayerState {
        ASurfaceControl* surface = nullptr;
        AHardwareBuffer* buffer = nullptr;
        std::uint64_t contentIdentity = 0;
        std::int64_t structureEpoch = 0;
        std::int32_t page = -1;
        std::int32_t slot = -1;
        std::uint32_t contentWidth = 0;
        std::uint32_t contentHeight = 0;
        std::int32_t geometryTop = 0;
        std::int32_t geometryBottom = 0;
        bool visible = false;
    };

    struct ReplacedBuffer {
        ASurfaceControl* surface = nullptr;
        AHardwareBuffer* buffer = nullptr;
    };

    struct Cookie {
        Impl* owner = nullptr;
        std::size_t index = 0;
        bool occupied = false;
        bool commitObserved = false;
        bool completeObserved = false;
        std::int64_t commitLatchNanos = 0;
        std::uint64_t token = 0;
        std::uint64_t producerSceneId = 0;
        std::int64_t structureEpoch = 0;
        bool contentChanged = false;
        std::size_t previousPulseIndex = kPulseBufferCount;
        std::array<ReplacedBuffer, kLayerCount> replaced{};
        std::size_t replacedCount = 0;
    };

    struct ReleaseWait {
        AHardwareBuffer* buffer = nullptr;
        int fd = -1;
        std::size_t pulseIndex = kPulseBufferCount;
    };

    struct CompletionJob {
        std::uint64_t token = 0;
        std::uint64_t producerSceneId = 0;
        std::int64_t structureEpoch = 0;
        std::int64_t latchNanos = 0;
        std::int64_t callbackObservedNanos = 0;
        bool contentChanged = false;
        int presentFenceFd = -1;
        std::array<ReleaseWait, kLayerCount + 1> releases{};
        std::size_t releaseCount = 0;
    };

    struct ReleaseJob {
        std::array<ReleaseWait, kLayerCount + 1> releases{};
        std::size_t releaseCount = 0;
    };

    void* androidLibrary = nullptr;
    void* syncLibrary = nullptr;
    Api api{};
    HardwareBufferIsSupportedFn hardwareBufferIsSupported = nullptr;
    HardwareBufferAllocateFn hardwareBufferAllocate = nullptr;
    HardwareBufferAcquireFn hardwareBufferAcquire = nullptr;
    HardwareBufferReleaseFn hardwareBufferRelease = nullptr;
    HardwareBufferLockFn hardwareBufferLock = nullptr;
    HardwareBufferUnlockFn hardwareBufferUnlock = nullptr;
    SyncFileInfoFn syncFileInfo = nullptr;
    SyncFileInfoFreeFn syncFileInfoFree = nullptr;

    std::array<LayerState, kLayerCount> layers{};
    ASurfaceControl* containerSurface = nullptr;
    // SurfaceComposerClient::Transaction::apply() atomically moves the accumulated state to
    // SurfaceFlinger and clears the transaction for reuse.  Geometry-only scrolling used to
    // allocate and destroy this native transaction on every display callback.  On gfxstream that
    // allocator/destructor traffic regularly descheduled the otherwise O(1) position update and
    // also kept the renderer mailbox occupied for another frame.  present() has one serial owner,
    // so retain one transaction for the whole attachment and refill it after each apply.
    ASurfaceTransaction* presentTransaction = nullptr;
    ASurfaceControl* pulseSurface = nullptr;
    std::array<AHardwareBuffer*, kPulseBufferCount> pulseBuffers{};
    enum class PulseState : std::uint8_t { FREE = 0, CURRENT = 1, WAIT_RELEASE = 2 };
    std::array<PulseState, kPulseBufferCount> pulseStates{};
    std::size_t currentPulseIndex = kPulseBufferCount;
    float installedContainerScaleX = 0.0F;
    float installedContainerScaleY = 0.0F;
    bool containerConfigured = false;
    bool pulseConfigured = false;
    std::array<Cookie, kCookieCount> cookies{};

    std::uint32_t destinationWidth = 0;
    std::uint32_t destinationHeight = 0;
    float frameRate = 0.0F;
    WakeCallback wakeCallback = nullptr;
    void* wakeContext = nullptr;
    std::atomic<std::uint32_t> commitPendingCount{0};
    std::atomic<bool> attached{false};
    std::atomic<bool> stopping{false};
    std::atomic<std::uint32_t> completionJobsInFlight{0};
    std::atomic<std::uint32_t> releaseJobsInFlight{0};
    std::thread completionThread;
    std::thread releaseThread;
    mutable std::mutex mutex;
    std::condition_variable completionCondition;
    std::condition_variable releaseCondition;
    std::deque<CompletionJob> completionJobs;
    std::deque<ReleaseJob> releaseJobs;
    std::deque<DirectTilePresentEvent> events;
    bool callbackFailure = false;
    std::uint32_t callbackFailureReason = 0;

    ~Impl() {
        stopping.store(true, std::memory_order_release);
        completionCondition.notify_all();
        releaseCondition.notify_all();
        if (completionThread.joinable()) completionThread.join();
        if (releaseThread.joinable()) releaseThread.join();
        releaseResources();
    }

    void wake() noexcept {
        if (wakeCallback != nullptr) wakeCallback(wakeContext);
    }

    void pushEvent(const DirectTilePresentEvent& event) noexcept {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (events.size() >= kMaxQueuedEvents) {
                callbackFailure = true;
                if (callbackFailureReason == 0) callbackFailureReason = 1;
            } else {
                events.push_back(event);
            }
        }
        wake();
    }

    std::optional<std::size_t> freeCookie() noexcept {
        std::lock_guard<std::mutex> lock(mutex);
        for (std::size_t index = 0; index < cookies.size(); ++index) {
            if (!cookies[index].occupied) return index;
        }
        return std::nullopt;
    }

    std::optional<std::pair<std::size_t, std::size_t>> reservePulse() noexcept {
        std::lock_guard<std::mutex> lock(mutex);
        std::size_t selected = kPulseBufferCount;
        for (std::size_t index = 0; index < pulseStates.size(); ++index) {
            if (pulseStates[index] == PulseState::FREE) {
                selected = index;
                break;
            }
        }
        if (selected == kPulseBufferCount) return std::nullopt;
        const std::size_t previous = currentPulseIndex;
        if (previous < pulseStates.size()) {
            if (pulseStates[previous] != PulseState::CURRENT) return std::nullopt;
            pulseStates[previous] = PulseState::WAIT_RELEASE;
        }
        pulseStates[selected] = PulseState::CURRENT;
        currentPulseIndex = selected;
        return std::pair<std::size_t, std::size_t>{selected, previous};
    }

    static void onCommitted(void* context, ASurfaceTransactionStats* stats) noexcept {
        auto* cookie = static_cast<Cookie*>(context);
        if (cookie == nullptr || cookie->owner == nullptr) return;
        cookie->owner->handleCommit(*cookie, stats);
    }

    static void onCompleted(void* context, ASurfaceTransactionStats* stats) noexcept {
        auto* cookie = static_cast<Cookie*>(context);
        if (cookie == nullptr || cookie->owner == nullptr || stats == nullptr) return;
        cookie->owner->handleComplete(*cookie, stats);
    }

    void maybeReleaseCookieLocked(Cookie& cookie) noexcept {
        if (!cookie.commitObserved || !cookie.completeObserved) return;
        cookie.occupied = false;
        cookie.replacedCount = 0;
    }

    void handleCommit(Cookie& cookie, ASurfaceTransactionStats* stats) noexcept {
        DirectTilePresentEvent event{};
        bool valid = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            valid = cookie.occupied && !cookie.commitObserved;
            if (valid) {
                cookie.commitObserved = true;
                cookie.commitLatchNanos = stats != nullptr ? api.getLatchTime(stats) : 0;
                event = {
                    .kind = DirectTilePresentEventKind::COMMITTED,
                    .token = cookie.token,
                    .producerSceneId = cookie.producerSceneId,
                    .structureEpoch = cookie.structureEpoch,
                    .completedNanos = monotonicNowNanos(),
                    .observedNanos = monotonicNowNanos(),
                    .contentChanged = cookie.contentChanged,
                };
                if (events.size() < kMaxQueuedEvents) {
                    events.push_back(event);
                } else {
                    callbackFailure = true;
                    if (callbackFailureReason == 0) callbackFailureReason = 2;
                }
                maybeReleaseCookieLocked(cookie);
            } else {
                // SurfaceFlinger can redeliver an OnCommit callback when the parent Surface is
                // detached/reattached repeatedly while transactions are being coalesced. Commit
                // owns no acquire/release-fence resources, and commitPendingCount was already
                // retired by the first valid callback. Treat this late duplicate as idempotent.
                // Poisoning the whole presenter here left canPresent() false without a FAILED
                // event, so one visible frame remained queued forever despite every slot/fence
                // ledger being empty. Event overflow and invalid OnComplete evidence remain
                // fail-closed through their existing callbackFailure/FAILED paths.
            }
        }
        if (valid) commitPendingCount.fetch_sub(1, std::memory_order_acq_rel);
        wake();
    }

    void handleComplete(Cookie& cookie, ASurfaceTransactionStats* stats) noexcept {
        CompletionJob job{};
        job.token = cookie.token;
        job.producerSceneId = cookie.producerSceneId;
        job.structureEpoch = cookie.structureEpoch;
        job.contentChanged = cookie.contentChanged;
        job.callbackObservedNanos = monotonicNowNanos();
        job.latchNanos = api.getLatchTime(stats);
        const int borrowedPresent = api.getPresentFenceFd(stats);
        if (borrowedPresent >= 0) {
            job.presentFenceFd = dup(borrowedPresent);
            if (job.presentFenceFd >= 0) (void)setCloseOnExec(job.presentFenceFd);
        }
        for (std::size_t index = 0; index < cookie.replacedCount; ++index) {
            const auto& replaced = cookie.replaced[index];
            int releaseFd = -1;
            if (replaced.surface != nullptr) {
                const int borrowed = api.getPreviousReleaseFenceFd(stats, replaced.surface);
                if (borrowed >= 0) {
                    releaseFd = dup(borrowed);
                    if (releaseFd >= 0) (void)setCloseOnExec(releaseFd);
                }
            }
            job.releases[job.releaseCount++] = {
                .buffer = replaced.buffer,
                .fd = releaseFd,
            };
        }
        if (cookie.previousPulseIndex < pulseBuffers.size()) {
            int releaseFd = -1;
            const int borrowed = api.getPreviousReleaseFenceFd(stats, pulseSurface);
            if (borrowed >= 0) {
                releaseFd = dup(borrowed);
                if (releaseFd >= 0) (void)setCloseOnExec(releaseFd);
            }
            job.releases[job.releaseCount++] = {
                .buffer = nullptr,
                .fd = releaseFd,
                .pulseIndex = cookie.previousPulseIndex,
            };
        }

        bool valid = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (job.latchNanos <= 0) job.latchNanos = cookie.commitLatchNanos;
            // NDK OnComplete is also the resource-lifecycle callback for a transaction that
            // SurfaceFlinger coalesced before a distinct display cut. Such a completion legally
            // has neither a present fence nor a latch timestamp; it still owns every previous-
            // release fence below and must not be classified as renderer corruption.
            valid = cookie.occupied && !cookie.completeObserved;
            if (valid) {
                cookie.completeObserved = true;
                completionJobs.push_back(std::move(job));
                completionJobsInFlight.fetch_add(1, std::memory_order_acq_rel);
                maybeReleaseCookieLocked(cookie);
            } else {
                callbackFailure = true;
                if (callbackFailureReason == 0) callbackFailureReason = 3;
            }
        }
        if (!valid) {
            if (job.presentFenceFd >= 0) close(job.presentFenceFd);
            for (std::size_t index = 0; index < job.releaseCount; ++index) {
                if (job.releases[index].fd >= 0) close(job.releases[index].fd);
                if (job.releases[index].buffer != nullptr && hardwareBufferRelease != nullptr) {
                    hardwareBufferRelease(job.releases[index].buffer);
                }
                if (job.releases[index].pulseIndex < pulseStates.size()) {
                    std::lock_guard<std::mutex> lock(mutex);
                    pulseStates[job.releases[index].pulseIndex] = PulseState::FREE;
                }
            }
            pushEvent({
                .kind = DirectTilePresentEventKind::FAILED,
                .token = cookie.token,
                .producerSceneId = cookie.producerSceneId,
                .structureEpoch = cookie.structureEpoch,
            });
        } else {
            completionCondition.notify_one();
            wake();
        }
    }

    bool exactFenceSignalNanos(
            int fd, std::int64_t observedNanos,
            std::int64_t* signalNanos) const noexcept {
        if (fd < 0 || observedNanos <= 0 || signalNanos == nullptr ||
            syncFileInfo == nullptr || syncFileInfoFree == nullptr) return false;
        *signalNanos = 0;
        sync_file_info* info = syncFileInfo(fd);
        if (info == nullptr) return false;
        bool exact = info->status == 1 && info->num_fences > 0;
        std::uint64_t latest = 0;
        const auto* children = reinterpret_cast<const sync_fence_info*>(
            static_cast<std::uintptr_t>(info->sync_fence_info));
        if (children == nullptr) exact = false;
        for (std::uint32_t index = 0; exact && index < info->num_fences; ++index) {
            const auto& child = children[index];
            if (child.status != 1 || child.timestamp_ns == 0) {
                exact = false;
                break;
            }
            latest = std::max(latest, static_cast<std::uint64_t>(child.timestamp_ns));
        }
        syncFileInfoFree(info);
        if (!exact || latest == 0 ||
            latest > static_cast<std::uint64_t>(observedNanos)) return false;
        *signalNanos = static_cast<std::int64_t>(latest);
        return true;
    }

    void completionLoop() noexcept {
        (void)pthread_setname_np(pthread_self(), "ReaderTileFence");
        // Commit/fence evidence releases the next exact content transaction. Match the bounded
        // display owner instead of competing with ordinary decode threads at -10.
        (void)setpriority(PRIO_PROCESS, 0, -14);
        while (true) {
            CompletionJob job{};
            {
                std::unique_lock<std::mutex> lock(mutex);
                completionCondition.wait(lock, [&] {
                    return stopping.load(std::memory_order_acquire) || !completionJobs.empty();
                });
                if (completionJobs.empty()) {
                    if (stopping.load(std::memory_order_acquire)) break;
                    continue;
                }
                job = std::move(completionJobs.front());
                completionJobs.pop_front();
            }

            bool valid = true;
            if (job.presentFenceFd >= 0) {
                valid = waitFence(job.presentFenceFd);
                close(job.presentFenceFd);
                job.presentFenceFd = -1;
            }
            if (!valid) {
                pushEvent({
                    .kind = DirectTilePresentEventKind::FAILED,
                    .token = job.token,
                    .producerSceneId = job.producerSceneId,
                    .structureEpoch = job.structureEpoch,
                });
            }
            if (job.releaseCount > 0) {
                ReleaseJob releaseJob{};
                releaseJob.releases = job.releases;
                releaseJob.releaseCount = job.releaseCount;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    releaseJobs.push_back(std::move(releaseJob));
                    releaseJobsInFlight.fetch_add(1, std::memory_order_acq_rel);
                }
                releaseCondition.notify_one();
            }
            completionJobsInFlight.fetch_sub(1, std::memory_order_acq_rel);
            wake();
        }
    }

    void releaseLoop() noexcept {
        (void)pthread_setname_np(pthread_self(), "ReaderTileRelease");
        (void)setpriority(PRIO_PROCESS, 0, 0);
        while (true) {
            ReleaseJob job{};
            {
                std::unique_lock<std::mutex> lock(mutex);
                releaseCondition.wait(lock, [&] {
                    return stopping.load(std::memory_order_acquire) || !releaseJobs.empty();
                });
                if (releaseJobs.empty()) {
                    if (stopping.load(std::memory_order_acquire)) break;
                    continue;
                }
                job = std::move(releaseJobs.front());
                releaseJobs.pop_front();
            }
            for (std::size_t index = 0; index < job.releaseCount; ++index) {
                auto& release = job.releases[index];
                if (release.fd >= 0) {
                    (void)waitFence(release.fd);
                    close(release.fd);
                }
                if (release.buffer != nullptr && hardwareBufferRelease != nullptr) {
                    hardwareBufferRelease(release.buffer);
                }
                if (release.pulseIndex < pulseStates.size()) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (pulseStates[release.pulseIndex] == PulseState::WAIT_RELEASE) {
                        pulseStates[release.pulseIndex] = PulseState::FREE;
                    }
                }
            }
            releaseJobsInFlight.fetch_sub(1, std::memory_order_acq_rel);
            wake();
        }
    }

    bool loadApi() noexcept {
        androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        syncLibrary = dlopen("libsync.so", RTLD_NOW | RTLD_LOCAL);
        if (androidLibrary == nullptr || syncLibrary == nullptr) return false;
#define NTK_LOAD(member, symbol) \
        api.member = reinterpret_cast<decltype(api.member)>(dlsym(androidLibrary, symbol))
        NTK_LOAD(createFromWindow, "ASurfaceControl_createFromWindow");
        NTK_LOAD(create, "ASurfaceControl_create");
        NTK_LOAD(releaseSurface, "ASurfaceControl_release");
        NTK_LOAD(createTransaction, "ASurfaceTransaction_create");
        NTK_LOAD(deleteTransaction, "ASurfaceTransaction_delete");
        NTK_LOAD(applyTransaction, "ASurfaceTransaction_apply");
        NTK_LOAD(setOnCommit, "ASurfaceTransaction_setOnCommit");
        NTK_LOAD(setOnComplete, "ASurfaceTransaction_setOnComplete");
        NTK_LOAD(setVisibility, "ASurfaceTransaction_setVisibility");
        NTK_LOAD(setBuffer, "ASurfaceTransaction_setBuffer");
        NTK_LOAD(setGeometry, "ASurfaceTransaction_setGeometry");
        NTK_LOAD(setPosition, "ASurfaceTransaction_setPosition");
        NTK_LOAD(setScale, "ASurfaceTransaction_setScale");
        NTK_LOAD(setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
        NTK_LOAD(setBufferAlpha, "ASurfaceTransaction_setBufferAlpha");
        NTK_LOAD(setZOrder, "ASurfaceTransaction_setZOrder");
        NTK_LOAD(setFrameRate, "ASurfaceTransaction_setFrameRate");
        NTK_LOAD(getLatchTime, "ASurfaceTransactionStats_getLatchTime");
        NTK_LOAD(getPresentFenceFd, "ASurfaceTransactionStats_getPresentFenceFd");
        NTK_LOAD(getPreviousReleaseFenceFd,
                 "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
#undef NTK_LOAD
        hardwareBufferIsSupported = reinterpret_cast<HardwareBufferIsSupportedFn>(
            dlsym(androidLibrary, "AHardwareBuffer_isSupported"));
        hardwareBufferAllocate = reinterpret_cast<HardwareBufferAllocateFn>(
            dlsym(androidLibrary, "AHardwareBuffer_allocate"));
        hardwareBufferAcquire = reinterpret_cast<HardwareBufferAcquireFn>(
            dlsym(androidLibrary, "AHardwareBuffer_acquire"));
        hardwareBufferRelease = reinterpret_cast<HardwareBufferReleaseFn>(
            dlsym(androidLibrary, "AHardwareBuffer_release"));
        hardwareBufferLock = reinterpret_cast<HardwareBufferLockFn>(
            dlsym(androidLibrary, "AHardwareBuffer_lock"));
        hardwareBufferUnlock = reinterpret_cast<HardwareBufferUnlockFn>(
            dlsym(androidLibrary, "AHardwareBuffer_unlock"));
        syncFileInfo = reinterpret_cast<SyncFileInfoFn>(dlsym(syncLibrary, "sync_file_info"));
        syncFileInfoFree = reinterpret_cast<SyncFileInfoFreeFn>(
            dlsym(syncLibrary, "sync_file_info_free"));
        return api.complete() && hardwareBufferAllocate != nullptr &&
            hardwareBufferAcquire != nullptr && hardwareBufferRelease != nullptr &&
            hardwareBufferLock != nullptr && hardwareBufferUnlock != nullptr &&
            syncFileInfo != nullptr && syncFileInfoFree != nullptr;
    }

    bool allocatePulseBuffers() noexcept {
        AHardwareBuffer_Desc descriptor{};
        descriptor.width = 1;
        descriptor.height = 1;
        descriptor.layers = 1;
        descriptor.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        descriptor.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
            AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (hardwareBufferIsSupported != nullptr &&
            hardwareBufferIsSupported(&descriptor) != 1) return false;
        for (std::size_t index = 0; index < pulseBuffers.size(); ++index) {
            if (hardwareBufferAllocate(&descriptor, &pulseBuffers[index]) != 0 ||
                pulseBuffers[index] == nullptr) return false;
            void* pixels = nullptr;
            if (hardwareBufferLock(
                    pulseBuffers[index], AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                    -1, nullptr, &pixels) != 0 || pixels == nullptr) return false;
            auto* rgba = static_cast<std::uint8_t*>(pixels);
            rgba[0] = 0;
            rgba[1] = 0;
            rgba[2] = 0;
            rgba[3] = 1;
            int fd = -1;
            if (hardwareBufferUnlock(pulseBuffers[index], &fd) != 0) {
                if (fd >= 0) close(fd);
                return false;
            }
            if (fd >= 0) {
                const bool signaled = waitFence(fd);
                close(fd);
                if (!signaled) return false;
            }
        }
        return true;
    }

    void releaseResources() noexcept {
        if (presentTransaction != nullptr && api.deleteTransaction != nullptr) {
            api.deleteTransaction(presentTransaction);
            presentTransaction = nullptr;
        }
        for (auto& layer : layers) {
            if (layer.buffer != nullptr && hardwareBufferRelease != nullptr) {
                hardwareBufferRelease(layer.buffer);
            }
            layer.buffer = nullptr;
            if (layer.surface != nullptr && api.releaseSurface != nullptr) {
                api.releaseSurface(layer.surface);
            }
            layer.surface = nullptr;
        }
        if (containerSurface != nullptr && api.releaseSurface != nullptr) {
            api.releaseSurface(containerSurface);
            containerSurface = nullptr;
        }
        if (pulseSurface != nullptr && api.releaseSurface != nullptr) {
            api.releaseSurface(pulseSurface);
            pulseSurface = nullptr;
        }
        for (auto*& buffer : pulseBuffers) {
            if (buffer != nullptr && hardwareBufferRelease != nullptr) {
                hardwareBufferRelease(buffer);
            }
            buffer = nullptr;
        }
        if (androidLibrary != nullptr) {
            dlclose(androidLibrary);
            androidLibrary = nullptr;
        }
        if (syncLibrary != nullptr) {
            dlclose(syncLibrary);
            syncLibrary = nullptr;
        }
    }

    bool sameLayer(const LayerState& layer, const DirectTileLayerInput& tile) const noexcept {
        return layer.buffer == tile.buffer &&
            layer.contentIdentity == tile.contentIdentity &&
            layer.page == tile.page && layer.slot == tile.slot &&
            layer.contentWidth == tile.contentWidth &&
            layer.contentHeight == tile.contentHeight;
    }

    bool present(const DirectTileFrameInput& frame) noexcept {
        if (!attached.load(std::memory_order_acquire) ||
            frame.token == 0 ||
            frame.bandWidth <= 0 || frame.bandHeight <= 0 || frame.viewportSourceTop < 0 ||
            frame.viewportSourceHeight <= 0 ||
            frame.viewportSourceTop > frame.bandHeight - frame.viewportSourceHeight ||
            frame.tiles == nullptr || frame.tileCount == 0) return false;

        std::array<const DirectTileLayerInput*, kLayerCount> visible{};
        std::array<std::int32_t, kLayerCount> destinationTops{};
        std::array<std::int32_t, kLayerCount> destinationBottoms{};
        std::size_t visibleCount = 0;
        for (std::size_t index = 0; index < frame.tileCount; ++index) {
            const auto& tile = frame.tiles[index];
            if (tile.buffer == nullptr || tile.contentIdentity == 0 ||
                tile.sourceTop < 0 || tile.sourceBottom <= tile.sourceTop ||
                tile.sourceHeight < tile.sourceBottom || tile.contentWidth == 0 ||
                tile.contentHeight == 0 || !std::isfinite(tile.pageTop) ||
                !std::isfinite(tile.pageHeight) || tile.pageHeight <= 0.0F) return false;
            if (visibleCount >= visible.size()) return false;
            const double tileTop = static_cast<double>(tile.pageTop) +
                static_cast<double>(tile.pageHeight) * tile.sourceTop / tile.sourceHeight;
            const double tileBottom = static_cast<double>(tile.pageTop) +
                static_cast<double>(tile.pageHeight) * tile.sourceBottom / tile.sourceHeight;
            const auto destinationTop = static_cast<std::int32_t>(std::llround(tileTop));
            const auto destinationBottom = static_cast<std::int32_t>(std::llround(tileBottom));
            if (destinationBottom <= destinationTop) return false;
            visible[visibleCount++] = &tile;
            destinationTops[visibleCount - 1] = destinationTop;
            destinationBottoms[visibleCount - 1] = destinationBottom;
        }
        if (visibleCount == 0) return false;
        std::array<int, kLayerCount> assignment{};
        assignment.fill(-1);
        std::array<bool, kLayerCount> used{};
        for (std::size_t tileIndex = 0; tileIndex < visibleCount; ++tileIndex) {
            for (std::size_t layerIndex = 0; layerIndex < layers.size(); ++layerIndex) {
                if (!used[layerIndex] && sameLayer(layers[layerIndex], *visible[tileIndex])) {
                    assignment[tileIndex] = static_cast<int>(layerIndex);
                    used[layerIndex] = true;
                    break;
                }
            }
        }
        for (std::size_t tileIndex = 0; tileIndex < visibleCount; ++tileIndex) {
            if (assignment[tileIndex] >= 0) continue;
            std::size_t selected = layers.size();
            for (std::size_t layerIndex = 0; layerIndex < layers.size(); ++layerIndex) {
                if (!used[layerIndex] && layers[layerIndex].buffer == nullptr) {
                    selected = layerIndex;
                    break;
                }
            }
            if (selected == layers.size()) {
                for (std::size_t layerIndex = 0; layerIndex < layers.size(); ++layerIndex) {
                    if (!used[layerIndex]) {
                        selected = layerIndex;
                        break;
                    }
                }
            }
            if (selected == layers.size()) return false;
            assignment[tileIndex] = static_cast<int>(selected);
            used[selected] = true;
        }

        bool contentChanged = false;
        for (std::size_t tileIndex = 0; tileIndex < visibleCount; ++tileIndex) {
            const auto layerIndex = static_cast<std::size_t>(assignment[tileIndex]);
            if (!sameLayer(layers[layerIndex], *visible[tileIndex])) {
                contentChanged = true;
                break;
            }
        }
        if (!contentChanged) {
            for (std::size_t layerIndex = 0; layerIndex < layers.size(); ++layerIndex) {
                if (!used[layerIndex] && layers[layerIndex].buffer != nullptr) {
                    contentChanged = true;
                    break;
                }
            }
        }
        std::optional<std::size_t> cookieIndex{};
        if (contentChanged) {
            if (commitPendingCount.load(std::memory_order_acquire) >=
                    kMaxLogicalUncommitted) return false;
            cookieIndex = freeCookie();
            if (!cookieIndex.has_value()) return false;
        }

        ASurfaceTransaction* transaction = presentTransaction;
        if (transaction == nullptr) return false;
        // Duplicate every pending CPU-write fence before mutating the reusable transaction. The
        // framework consumes these descriptors in setBuffer(). If descriptor duplication is ever
        // exhausted, synchronously proving the borrowed fence is the safe rare fallback.
        std::array<int, kLayerCount> acquireFences{};
        acquireFences.fill(-1);
        for (std::size_t tileIndex = 0; tileIndex < visibleCount; ++tileIndex) {
            const auto layerIndex = static_cast<std::size_t>(assignment[tileIndex]);
            const auto& tile = *visible[tileIndex];
            if (sameLayer(layers[layerIndex], tile) || tile.acquireFenceFd < 0) continue;
            acquireFences[tileIndex] = duplicateFence(tile.acquireFenceFd);
            if (acquireFences[tileIndex] >= 0 || waitFence(tile.acquireFenceFd)) continue;
            for (int fenceFd : acquireFences) {
                if (fenceFd >= 0) close(fenceFd);
            }
            return false;
        }
        Cookie* cookie = nullptr;
        if (cookieIndex.has_value()) {
            std::lock_guard<std::mutex> lock(mutex);
            cookie = &cookies[*cookieIndex];
            *cookie = {};
            cookie->owner = this;
            cookie->index = *cookieIndex;
            cookie->occupied = true;
            cookie->token = frame.token;
            cookie->producerSceneId = frame.producerSceneId;
            cookie->structureEpoch = frame.structureEpoch;
        }

        const float scaleX = static_cast<float>(destinationWidth) /
            static_cast<float>(frame.bandWidth);
        const float scaleY = static_cast<float>(destinationHeight) /
            static_cast<float>(frame.viewportSourceHeight);
        const auto positionY = -static_cast<std::int32_t>(std::llround(
            static_cast<double>(frame.viewportSourceTop) * scaleY));
        api.setPosition(transaction, containerSurface, 0, positionY);
        if (!containerConfigured ||
            std::abs(installedContainerScaleX - scaleX) > 0.000001F ||
            std::abs(installedContainerScaleY - scaleY) > 0.000001F) {
            api.setScale(transaction, containerSurface, scaleX, scaleY);
            installedContainerScaleX = scaleX;
            installedContainerScaleY = scaleY;
        }
        if (!containerConfigured) {
            api.setZOrder(transaction, containerSurface, 100);
            api.setVisibility(
                transaction, containerSurface, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
            if (api.setFrameRate != nullptr && frameRate > 0.0F) {
                api.setFrameRate(
                    transaction, containerSurface, frameRate,
                    ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            containerConfigured = true;
        }

        for (std::size_t tileIndex = 0; tileIndex < visibleCount; ++tileIndex) {
            const auto& tile = *visible[tileIndex];
            const std::size_t layerIndex = static_cast<std::size_t>(assignment[tileIndex]);
            auto& layer = layers[layerIndex];
            if (!sameLayer(layer, tile)) {
                cookie->contentChanged = true;
                hardwareBufferAcquire(tile.buffer);
                if (layer.buffer != nullptr) {
                    cookie->replaced[cookie->replacedCount++] = {
                        .surface = layer.surface,
                        .buffer = layer.buffer,
                    };
                }
                // ASurfaceTransaction_setBuffer consumes the duplicated acquire-fence fd.
                api.setBuffer(
                    transaction, layer.surface, tile.buffer, acquireFences[tileIndex]);
                acquireFences[tileIndex] = -1;
                layer.buffer = tile.buffer;
                layer.contentIdentity = tile.contentIdentity;
                layer.structureEpoch = tile.structureEpoch;
                layer.page = tile.page;
                layer.slot = tile.slot;
                layer.contentWidth = tile.contentWidth;
                layer.contentHeight = tile.contentHeight;
                api.setBufferTransparency(
                    transaction, layer.surface, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
                api.setBufferAlpha(transaction, layer.surface, 1.0F);
                api.setZOrder(
                    transaction, layer.surface, static_cast<std::int32_t>(layerIndex));
                api.setVisibility(
                    transaction, layer.surface, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
                layer.visible = true;
            }
            const auto destinationTop = destinationTops[tileIndex];
            const auto destinationBottom = destinationBottoms[tileIndex];
            const ARect source{
                0, 0,
                static_cast<std::int32_t>(tile.contentWidth),
                static_cast<std::int32_t>(tile.contentHeight),
            };
            const ARect destination{
                0, destinationTop,
                static_cast<std::int32_t>(destinationWidth), destinationBottom,
            };
            if (layer.geometryTop != destinationTop ||
                layer.geometryBottom != destinationBottom ||
                layer.contentWidth != tile.contentWidth ||
                layer.contentHeight != tile.contentHeight) {
                api.setGeometry(transaction, layer.surface, source, destination, 0);
                layer.geometryTop = destinationTop;
                layer.geometryBottom = destinationBottom;
            }
        }
        for (std::size_t layerIndex = 0; layerIndex < layers.size(); ++layerIndex) {
            auto& layer = layers[layerIndex];
            if (used[layerIndex] || layer.buffer == nullptr) continue;
            cookie->replaced[cookie->replacedCount++] = {
                .surface = layer.surface,
                .buffer = layer.buffer,
            };
            cookie->contentChanged = true;
            api.setBuffer(transaction, layer.surface, nullptr, -1);
            api.setVisibility(
                transaction, layer.surface, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            layer.buffer = nullptr;
            layer.contentIdentity = 0;
            layer.structureEpoch = 0;
            layer.page = -1;
            layer.slot = -1;
            layer.contentWidth = 0;
            layer.contentHeight = 0;
            layer.geometryTop = 0;
            layer.geometryBottom = 0;
            layer.visible = false;
        }

        if (cookie != nullptr) {
            api.setOnCommit(transaction, cookie, &onCommitted);
            api.setOnComplete(transaction, cookie, &onCompleted);
            commitPendingCount.fetch_add(1, std::memory_order_acq_rel);
        }
        api.applyTransaction(transaction);
        const std::int64_t submittedNanos = monotonicNowNanos();
        pushEvent({
            .kind = DirectTilePresentEventKind::PRODUCER_SUBMITTED,
            .token = frame.token,
            .producerSceneId = frame.producerSceneId,
            .structureEpoch = frame.structureEpoch,
            .completedNanos = submittedNanos,
            .observedNanos = submittedNanos,
            .contentChanged = contentChanged,
        });
        return true;
    }
};

DirectTileSurfacePresenter::DirectTileSurfacePresenter() = default;
DirectTileSurfacePresenter::~DirectTileSurfacePresenter() = default;

bool DirectTileSurfacePresenter::attach(
        ANativeWindow* parentWindow,
        std::uint32_t destinationWidth,
        std::uint32_t destinationHeight,
        float frameRate,
        WakeCallback wakeCallback,
        void* wakeContext) noexcept {
    if (impl_ != nullptr || parentWindow == nullptr || destinationWidth == 0 ||
        destinationHeight == 0) return false;
    auto candidate = std::unique_ptr<Impl>(new (std::nothrow) Impl());
    if (candidate == nullptr || !candidate->loadApi()) return false;
    candidate->presentTransaction = candidate->api.createTransaction();
    if (candidate->presentTransaction == nullptr) return false;
    candidate->destinationWidth = destinationWidth;
    candidate->destinationHeight = destinationHeight;
    candidate->frameRate = std::isfinite(frameRate) && frameRate > 0.0F ? frameRate : 0.0F;
    candidate->wakeCallback = wakeCallback;
    candidate->wakeContext = wakeContext;
    candidate->containerSurface = candidate->api.createFromWindow(
        parentWindow, "NtkExactTileContainer");
    if (candidate->containerSurface == nullptr) return false;
    for (std::size_t index = 0; index < candidate->layers.size(); ++index) {
        candidate->layers[index].surface = candidate->api.create(
            candidate->containerSurface, "NtkExactTileLayer");
        if (candidate->layers[index].surface == nullptr) return false;
    }
    for (std::size_t index = 0; index < candidate->cookies.size(); ++index) {
        candidate->cookies[index].owner = candidate.get();
        candidate->cookies[index].index = index;
    }
    candidate->completionThread = std::thread(&Impl::completionLoop, candidate.get());
    candidate->releaseThread = std::thread(&Impl::releaseLoop, candidate.get());
    candidate->attached.store(true, std::memory_order_release);
    impl_ = std::move(candidate);
    return true;
}

bool DirectTileSurfacePresenter::attached() const noexcept {
    return impl_ != nullptr && impl_->attached.load(std::memory_order_acquire);
}

bool DirectTileSurfacePresenter::canPresent() const noexcept {
    if (!attached()) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return !impl_->callbackFailure;
}

std::uint32_t DirectTileSurfacePresenter::failureReason() const noexcept {
    if (impl_ == nullptr) return 0;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->callbackFailureReason;
}

std::size_t DirectTileSurfacePresenter::queuedEventCount() const noexcept {
    if (impl_ == nullptr) return 0;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->events.size();
}

bool DirectTileSurfacePresenter::present(const DirectTileFrameInput& frame) noexcept {
    return impl_ != nullptr && impl_->present(frame);
}

bool DirectTileSurfacePresenter::drainEvent(DirectTilePresentEvent* event) noexcept {
    if (impl_ == nullptr || event == nullptr) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->events.empty()) return false;
    *event = impl_->events.front();
    impl_->events.pop_front();
    return true;
}

bool DirectTileSurfacePresenter::idle() const noexcept {
    if (impl_ == nullptr) return true;
    if (impl_->commitPendingCount.load(std::memory_order_acquire) != 0 ||
        impl_->completionJobsInFlight.load(std::memory_order_acquire) != 0 ||
        impl_->releaseJobsInFlight.load(std::memory_order_acquire) != 0) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return !impl_->callbackFailure && impl_->completionJobs.empty() &&
        impl_->releaseJobs.empty() && impl_->events.empty() &&
        std::none_of(impl_->cookies.begin(), impl_->cookies.end(),
                     [](const Impl::Cookie& cookie) { return cookie.occupied; });
}

bool DirectTileSurfacePresenter::detach() noexcept {
    if (impl_ == nullptr) return true;
    if (!idle()) return false;
    impl_->attached.store(false, std::memory_order_release);
    impl_->stopping.store(true, std::memory_order_release);
    impl_->completionCondition.notify_all();
    impl_->releaseCondition.notify_all();
    if (impl_->completionThread.joinable()) impl_->completionThread.join();
    if (impl_->releaseThread.joinable()) impl_->releaseThread.join();
    impl_.reset();
    return true;
}

}  // namespace ntk::present
