#include "buffered_frame_compositor.h"
#include "release_fence_watcher.h"
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/looper.h>
#include <android/surface_control.h>
#include <android/trace.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <ctime>
#include <deque>
#include <dlfcn.h>
#include <mutex>
#include <linux/sync_file.h>
#include <poll.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/ioctl.h>
#include <limits>
#include <thread>
#include <unistd.h>
#include <unordered_set>
#include <vector>

namespace {
/** Exclusive FIFO handoff of finished transactions; this thread never owns a GL context. */
class TransactionSubmitter final {
public:
    TransactionSubmitter() : thread_([this] { run(); }) {}
    ~TransactionSubmitter() {
        { std::lock_guard lock(mutex_); closing_ = true; }
        changed_.notify_one();
        thread_.join();
    }
    void submit(ASurfaceTransaction* transaction) {
        { std::lock_guard lock(mutex_); queue_.push_back(transaction); }
        changed_.notify_one();
    }
    void flush() {
        std::unique_lock lock(mutex_);
        drained_.wait(lock, [this] { return queue_.empty() && !applying_; });
    }
private:
    void run() {
        pthread_setname_np(pthread_self(), "engine-present");
        setpriority(PRIO_PROCESS, 0, -4);
        for (;;) {
            ASurfaceTransaction* transaction = nullptr;
            {
                std::unique_lock lock(mutex_);
                changed_.wait(lock, [this] { return closing_ || !queue_.empty(); });
                if (queue_.empty()) return;
                transaction = queue_.front(); queue_.pop_front(); applying_ = true;
            }
            ASurfaceTransaction_apply(transaction);
            ASurfaceTransaction_delete(transaction);
            { std::lock_guard lock(mutex_); applying_ = false; }
            drained_.notify_all();
        }
    }
    std::mutex mutex_;
    std::condition_variable changed_, drained_;
    std::deque<ASurfaceTransaction*> queue_;
    bool closing_ = false, applying_ = false;
    std::thread thread_;
};

struct Functions {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    PFNEGLCREATEIMAGEKHRPROC image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    PFNEGLCREATESYNCKHRPROC sync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    PFNEGLDESTROYSYNCKHRPROC destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC fence = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    using Pressure = void (*)(ASurfaceTransaction*, ASurfaceControl*, bool);
    using Transform = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
    using Acquire = void (*)(ASurfaceControl*);
    using BufferId = int (*)(const AHardwareBuffer*, std::uint64_t*);
    Pressure pressure = reinterpret_cast<Pressure>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setEnableBackPressure"));
    Transform transform = reinterpret_cast<Transform>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setBufferTransform"));
    Acquire acquire = reinterpret_cast<Acquire>(dlsym(RTLD_DEFAULT, "ASurfaceControl_acquire"));
    BufferId bufferId = reinterpret_cast<BufferId>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
    using CommitCallback = void (*)(void*, ASurfaceTransactionStats*);
    using SetOnCommit = void (*)(ASurfaceTransaction*, void*, CommitCallback);
    SetOnCommit setOnCommit = reinterpret_cast<SetOnCommit>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setOnCommit"));
    bool valid() const { return buffer && image && destroyImage && target && sync && destroySync && fence && pressure && transform && acquire; }
};
struct Completion { std::int64_t token, latch; std::uint64_t bufferId; std::int32_t generation; int previous, fence, presentFence; };
struct CompletionQueue {
    std::mutex mutex;
    std::vector<Completion> items;
    ~CompletionQueue() {
        for (const auto& item : items) {
            if (item.fence >= 0) close(item.fence);
            if (item.presentFence >= 0) close(item.presentFence);
        }
    }
};
struct Ticket {
    std::shared_ptr<CompletionQueue> queue;
    std::shared_ptr<GlPresentationCallback> callback;
    ASurfaceControl* layer;
    std::int64_t token;
    int previous;
    std::uint64_t bufferId;
    std::int32_t generation;
    ~Ticket() { ASurfaceControl_release(layer); }
};
void completed(void* opaque, ASurfaceTransactionStats* stats) {
    const std::unique_ptr<Ticket> ticket(static_cast<Ticket*>(opaque));
    const auto latch = ASurfaceTransactionStats_getLatchTime(stats);
    const int fence = ticket->previous < 0 ? -1 : ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, ticket->layer);
    const int presentFence = ASurfaceTransactionStats_getPresentFenceFd(stats);
    {
        std::lock_guard lock(ticket->queue->mutex);
        ticket->queue->items.push_back({ticket->token, latch, ticket->bufferId, ticket->generation, ticket->previous, fence, presentFence});
    }
    ticket->callback->completionPending();
}
struct CommitGate { std::atomic<bool> outstanding{false}; };
struct CommitTicket {
    std::shared_ptr<CommitGate> gate;
    std::shared_ptr<GlPresentationCallback> callback;
};
void committed(void* opaque, ASurfaceTransactionStats*) {
    const std::unique_ptr<CommitTicket> ticket(static_cast<CommitTicket*>(opaque));
    ticket->gate->outstanding.store(false, std::memory_order_release);
    ticket->callback->completionPending();
}
// Arm one outstanding uncommitted transaction; absent setOnCommit keeps the pending fallback.
void armCommitGate(const Functions& functions, ASurfaceTransaction* transaction,
    const std::shared_ptr<CommitGate>& gate, const std::shared_ptr<GlPresentationCallback>& callback) noexcept {
    if (!functions.setOnCommit) return;
    gate->outstanding.store(true, std::memory_order_release);
    functions.setOnCommit(transaction, new CommitTicket{gate, callback}, committed);
}

enum class FenceState { Signaled, Pending, Invalid };
enum class FenceKind : std::uint8_t { Present, Gpu };

// Classify a fence without blocking. The caller owns the fd in every case.
FenceState readFenceState(int fd, sync_file_info& info, std::array<sync_fence_info, 64>& fences) noexcept {
    if (ioctl(fd, SYNC_IOC_FILE_INFO, &info) != 0) return FenceState::Invalid;
    if (info.status == 0) return FenceState::Pending;
    if (info.status != 1) return FenceState::Invalid;
    if (info.num_fences == 0 || info.num_fences > fences.size()) return FenceState::Invalid;
    info.sync_fence_info = reinterpret_cast<std::uintptr_t>(fences.data());
    if (ioctl(fd, SYNC_IOC_FILE_INFO, &info) != 0) return FenceState::Invalid;
    if (info.status == 0) return FenceState::Pending;
    if (info.status != 1 || info.num_fences == 0 || info.num_fences > fences.size()) return FenceState::Invalid;
    return FenceState::Signaled;
}

// Latest kernel timestamp of a fully signaled fence; deliberately no latch bound:
// a GPU acquire fence may signal after the SurfaceFlinger latch.
FenceState readSignalTimestamp(int fd, std::int64_t& signal) noexcept {
    signal = 0;
    if (fd < 0) return FenceState::Invalid;
    sync_file_info info{};
    std::array<sync_fence_info, 64> fences{};
    const FenceState state = readFenceState(fd, info, fences);
    if (state != FenceState::Signaled) return state;
    std::int64_t latest = 0;
    for (unsigned int i = 0; i < info.num_fences; ++i) {
        const auto& fence = fences[i];
        if (fence.status != 1 || fence.timestamp_ns == 0 ||
            fence.timestamp_ns > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) return FenceState::Invalid;
        const auto at = static_cast<std::int64_t>(fence.timestamp_ns);
        if (at > latest) latest = at;
    }
    if (latest <= 0) return FenceState::Invalid;
    signal = latest;
    return FenceState::Signaled;
}

// Present fences additionally require the signal to be at or after the latch.
FenceState observePresentFence(int fd, std::int64_t latch, std::int64_t& signal) noexcept {
    const FenceState state = readSignalTimestamp(fd, signal);
    if (state != FenceState::Signaled) return state;
    if (signal < latch) { signal = 0; return FenceState::Invalid; }
    return FenceState::Signaled;
}

FenceState observeFence(FenceKind kind, int fd, std::int64_t latch, std::int64_t& signal) noexcept {
    if (kind == FenceKind::Gpu) return readSignalTimestamp(fd, signal);
    return observePresentFence(fd, latch, signal);
}

// An unresolved present fence waits at most this long for a real display-present signal before it is
// reported on the composition latch, so no token can remain unresolved.
constexpr std::int64_t kRetainedFenceDeadlineNanos = 100'000'000;

std::int64_t steadyNowNanos() noexcept {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<std::int64_t>(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

struct RetainedFenceList {
    static constexpr std::size_t limit = 4;
    struct Entry { std::int64_t token, latch, observed; std::uint64_t bufferId; std::int32_t generation; int fd; };
    std::vector<Entry> entries;
    std::uint32_t overflow = 0, unresolved = 0, invalid = 0;
    std::int32_t generation = 0;
};

// Unique diagnostic schemas; never collide with Kotlin engine_present/engine_present_fence.
void traceFenceSignal(FenceKind kind, const char* state, std::int32_t generation, std::int64_t token,
    std::uint64_t bufferId, std::int64_t latch, std::int64_t signal) noexcept {
    if (!ATrace_isEnabled()) return;
    char label[128]{};
    if (kind == FenceKind::Gpu) {
        std::snprintf(label, sizeof(label), "engine_gpu_fence_signal:%d:%lld:%llu:%lld:%s",
            generation, static_cast<long long>(token), static_cast<unsigned long long>(bufferId),
            static_cast<long long>(signal), state);
    } else {
        std::snprintf(label, sizeof(label), "engine_fence_signal:%d:%lld:%llu:%lld:%lld:%s",
            generation, static_cast<long long>(token), static_cast<unsigned long long>(bufferId),
            static_cast<long long>(latch), static_cast<long long>(signal), state);
    }
    ATrace_beginSection(label); ATrace_endSection();
}

void traceFenceFinal(FenceKind kind, std::int32_t generation, std::uint32_t unresolved,
    std::uint32_t overflow, std::uint32_t invalid) noexcept {
    if (!ATrace_isEnabled()) return;
    char label[80]{};
    if (kind == FenceKind::Gpu) {
        std::snprintf(label, sizeof(label), "engine_gpu_fence_final:%d:%u:%u:%u", generation, unresolved, overflow, invalid);
    } else {
        std::snprintf(label, sizeof(label), "engine_fence_final:%d:%u:%u:%u", generation, unresolved, overflow, invalid);
    }
    ATrace_beginSection(label); ATrace_endSection();
}

void traceGpuFenceMissing(std::int32_t generation, std::int64_t token, std::uint64_t bufferId, const char* reason) noexcept {
    if (!ATrace_isEnabled()) return;
    char label[128]{};
    std::snprintf(label, sizeof(label), "engine_gpu_fence_missing:%d:%lld:%llu:%s",
        generation, static_cast<long long>(token), static_cast<unsigned long long>(bufferId), reason);
    ATrace_beginSection(label); ATrace_endSection();
}

// Trace-enabled only: unresolved fds are retained for later nonblocking sweeps, never waited on.
// Every retained fd ends in exactly one of late / overflow / invalid / unresolved.
void retainFence(RetainedFenceList& list, FenceKind kind, std::int32_t generation, std::int64_t token,
    std::int64_t latch, std::uint64_t bufferId, int fd) noexcept {
    if (!ATrace_isEnabled() || fd < 0) {
        if (fd >= 0) close(fd);
        return;
    }
    if (list.entries.size() >= RetainedFenceList::limit) {
        const auto oldest = list.entries.front();
        traceFenceSignal(kind, "overflow", oldest.generation, oldest.token, oldest.bufferId, oldest.latch, 0);
        close(oldest.fd);
        ++list.overflow;
        list.entries.erase(list.entries.begin());
    }
    list.entries.push_back({token, latch, steadyNowNanos(), bufferId, generation, fd});
}

// Present fences are retained unconditionally (not trace-gated): reporting the real display-present
// instead of the composition latch is a correctness property the presented() contract depends on.
// The list is bounded and every entry resolves within kRetainedFenceDeadlineNanos, so an unresolved
// fence can never accumulate in the list or keep its token pending forever.
bool retainPresentFence(RetainedFenceList& list, std::int32_t generation, std::int64_t token,
    std::int64_t latch, std::uint64_t bufferId, int fd, std::unordered_set<std::int64_t>& pending,
    const std::shared_ptr<GlPresentationCallback>& callback) noexcept {
    if (fd < 0) return false;
    if (list.entries.size() >= RetainedFenceList::limit) {
        const auto oldest = list.entries.front();
        traceFenceSignal(FenceKind::Present, "overflow", oldest.generation, oldest.token, oldest.bufferId, oldest.latch, 0);
        close(oldest.fd);
        ++list.overflow;
        list.entries.erase(list.entries.begin());
        // The evicted fence can never be swept, so its token is reported on the latch here; every
        // token in pending must leave it exactly once.
        if (pending.erase(oldest.token))
            callback->presented(oldest.token, oldest.latch, EGL_COMPOSITION_LATCH_TIME_ANDROID, 0);
    }
    list.entries.push_back({token, latch, steadyNowNanos(), bufferId, generation, fd});
    return true;
}

// GPU-only diagnostics: these fds never gate a presented() callback, so they stay trace-gated and
// are resolved at the next sweep without any deadline.
void sweepGpuRetainedFences(RetainedFenceList& list) noexcept {
    for (auto current = list.entries.begin(); current != list.entries.end();) {
        std::int64_t signal = 0;
        const FenceState state = observeFence(FenceKind::Gpu, current->fd, current->latch, signal);
        if (state == FenceState::Pending) { ++current; continue; }
        if (state == FenceState::Signaled) {
            traceFenceSignal(FenceKind::Gpu, "late", current->generation, current->token, current->bufferId, current->latch, signal);
        } else {
            ++list.invalid;
            traceFenceSignal(FenceKind::Gpu, "invalid", current->generation, current->token, current->bufferId, current->latch, 0);
        }
        close(current->fd);
        current = list.entries.erase(current);
    }
}

// Promote a retained present fence: a signaled fence reports the display-present timestamp, an
// unusable or deadline-expired one falls back to the composition latch exactly like the immediate
// path. Either way the token leaves pending exactly once.
void sweepPresentRetainedFences(RetainedFenceList& list, std::unordered_set<std::int64_t>& pending,
    const std::shared_ptr<GlPresentationCallback>& callback) noexcept {
    const std::int64_t now = steadyNowNanos();
    for (auto current = list.entries.begin(); current != list.entries.end();) {
        std::int64_t signal = 0;
        const FenceState state = observeFence(FenceKind::Present, current->fd, current->latch, signal);
        if (state == FenceState::Pending && now - current->observed < kRetainedFenceDeadlineNanos) {
            ++current; continue;
        }
        std::int64_t timestamp = 0;
        int reported = -1;
        if (state == FenceState::Signaled) {
            timestamp = signal;
            reported = EGL_DISPLAY_PRESENT_TIME_ANDROID;
            traceFenceSignal(FenceKind::Present, "late", current->generation, current->token, current->bufferId, current->latch, signal);
        } else if (state == FenceState::Invalid) {
            ++list.invalid;
            traceFenceSignal(FenceKind::Present, "invalid", current->generation, current->token, current->bufferId, current->latch, 0);
        } else {
            ++list.unresolved;
            traceFenceSignal(FenceKind::Present, "expired", current->generation, current->token, current->bufferId, current->latch, 0);
        }
        if (reported != EGL_DISPLAY_PRESENT_TIME_ANDROID && current->latch > 0) {
            timestamp = current->latch;
            reported = EGL_COMPOSITION_LATCH_TIME_ANDROID;
        }
        if (pending.erase(current->token)) callback->presented(current->token, timestamp, reported, 0);
        close(current->fd);
        current = list.entries.erase(current);
    }
}

// Every retained fd is closed here. A retained present fence resolves its token on the latch so the
// pending set cannot outlive the attachment; a dead session still reports any completion-less token
// as CANCELLED afterwards because such a token was never retained.
void finalizeRetainedFences(RetainedFenceList& list, FenceKind kind,
    std::unordered_set<std::int64_t>* pending,
    const std::shared_ptr<GlPresentationCallback>& callback) noexcept {
    for (const auto& entry : list.entries) {
        std::int64_t signal = 0;
        const FenceState state = observeFence(kind, entry.fd, entry.latch, signal);
        if (state == FenceState::Signaled) {
            traceFenceSignal(kind, "late", entry.generation, entry.token, entry.bufferId, entry.latch, signal);
        } else if (state == FenceState::Invalid) {
            ++list.invalid;
            traceFenceSignal(kind, "invalid", entry.generation, entry.token, entry.bufferId, entry.latch, 0);
        } else {
            ++list.unresolved;
            traceFenceSignal(kind, "unresolved", entry.generation, entry.token, entry.bufferId, entry.latch, 0);
        }
        if (kind == FenceKind::Present && pending != nullptr) {
            std::int64_t timestamp = 0;
            int reported = -1;
            if (state == FenceState::Signaled && signal > 0) {
                timestamp = signal;
                reported = EGL_DISPLAY_PRESENT_TIME_ANDROID;
            } else if (entry.latch > 0) {
                timestamp = entry.latch;
                reported = EGL_COMPOSITION_LATCH_TIME_ANDROID;
            }
            if (pending->erase(entry.token)) callback->presented(entry.token, timestamp, reported, 0);
        }
        close(entry.fd);
    }
    list.entries.clear();
    traceFenceFinal(kind, list.generation, list.unresolved, list.overflow, list.invalid);
}

void reportCompletion(const Completion& item, std::unordered_set<std::int64_t>& pending,
    const std::shared_ptr<GlPresentationCallback>& callback, RetainedFenceList& retained) noexcept {
    std::int64_t signal = 0;
    const FenceState state = observePresentFence(item.presentFence, item.latch, signal);
    if (state == FenceState::Pending) {
        // The present fence signals just after the latch. Retain it and keep the token pending so a
        // later sweep can report the real display-present; finalizing on the latch here is what left
        // most frames without a physical timestamp.
        if (retainPresentFence(retained, item.generation, item.token, item.latch, item.bufferId,
                item.presentFence, pending, callback)) return;
    } else {
        if (item.presentFence >= 0) close(item.presentFence);
        if (state == FenceState::Signaled)
            traceFenceSignal(FenceKind::Present, "immediate", item.generation, item.token, item.bufferId, item.latch, signal);
    }
    std::int64_t timestamp = 0;
    int kind = -1;
    if (signal > 0) { timestamp = signal; kind = EGL_DISPLAY_PRESENT_TIME_ANDROID; }
    else if (item.latch > 0) { timestamp = item.latch; kind = EGL_COMPOSITION_LATCH_TIME_ANDROID; }
    if (pending.erase(item.token)) callback->presented(item.token, timestamp, kind, 0);
}
struct FrameBuffer {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0, framebuffer = 0;
    bool busy = false;
    int releaseFence = -1;
    // One looper registration attempt per assigned fence; failures leave the tick poll as fallback.
    bool wakeAttempted = false;
};
bool allocateFrame(FrameBuffer& frame, const Functions& functions, int width, int height) {
    AHardwareBuffer_Desc description{};
    description.width = width; description.height = height; description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    description.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    if (AHardwareBuffer_allocate(&description, &frame.buffer) != 0) return false;
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    frame.image = functions.image(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
        functions.buffer(frame.buffer), attributes);
    if (frame.image == EGL_NO_IMAGE_KHR) return false;
    glGenTextures(1, &frame.texture);
    glBindTexture(GL_TEXTURE_2D, frame.texture);
    functions.target(GL_TEXTURE_2D, frame.image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &frame.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, frame.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, frame.texture, 0);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;
}
void releaseFrame(FrameBuffer& frame, const Functions& functions) {
    if (frame.releaseFence >= 0) close(frame.releaseFence);
    if (frame.framebuffer) glDeleteFramebuffers(1, &frame.framebuffer);
    if (frame.texture) glDeleteTextures(1, &frame.texture);
    if (frame.image != EGL_NO_IMAGE_KHR) functions.destroyImage(eglGetCurrentDisplay(), frame.image);
    if (frame.buffer) AHardwareBuffer_release(frame.buffer);
    frame = {};
}
// Enabled-on-entry / end-on-destroy ATrace wrapper, same semantics as the renderer's
// ScopedTraceSection: one enable decision at construction, end at destruction. Diagnostic only;
// never steers control flow. Sections must stay isolated so existing ledger markers remain direct
// children of engine_frame.
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

// GPU completion fence export: the acquired fence is attached to the buffer transaction so
// SurfaceFlinger cannot latch or present before the GPU work completes (render-start <= GPU signal
// <= display); a trace-gated dup feeds the existing sweep/finalize diagnostics without owning the
// submitted fd.
int acquireFence(const Functions& functions) {
    const auto display = eglGetCurrentDisplay();
    const EGLint attributes[] = {EGL_NONE};
    EGLSyncKHR sync = functions.sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync == EGL_NO_SYNC_KHR) return -1;
    glFlush();
    const int fence = functions.fence(display, sync);
    functions.destroySync(display, sync);
    return fence;
}

// Release-fence wake diagnostics: whether any unsignaled release fence used the one-shot looper
// path, and how. Counters move only while ATrace is enabled; wake delivery never depends on it.
struct ReleaseWakeCounters { std::uint32_t watch = 0, input = 0, fault = 0, clear = 0; };

struct OwnerWake {
    GlPresentationCallback* callback = nullptr;
    std::int32_t generation = 0;
    ReleaseWakeCounters counters;
    bool open = false;
    // One-shot notification report: input/fault classification of the ALooper delivery, never buffer
    // readiness; the owner poll remains the sole authority on fence state.
    void notify(int events) noexcept {
        if (ATrace_isEnabled()) {
            const bool input = (events & ALOOPER_EVENT_INPUT) != 0;
            const bool fault = (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP | ALOOPER_EVENT_INVALID)) != 0;
            if (input) ++counters.input;
            if (fault) ++counters.fault;
            char label[64]{};
            std::snprintf(label, sizeof(label), "engine_release_wake:%d:%s", generation,
                input ? (fault ? "input+fault" : "input") : (fault ? "fault" : "none"));
            ATrace_beginSection(label); ATrace_endSection();
        }
        callback->completionPending();
    }
};
void wakeOwner(void* context, int events) noexcept { static_cast<OwnerWake*>(context)->notify(events); }

void traceReleaseWatch(OwnerWake& wake) noexcept {
    if (!ATrace_isEnabled()) return;
    ++wake.counters.watch;
    char label[64]{};
    std::snprintf(label, sizeof(label), "engine_release_watch:%d", wake.generation);
    ATrace_beginSection(label); ATrace_endSection();
}

// Explicit removal of an armed registration before any one-shot wake (tick-readiness free, slot
// reassignment, detach). A wake clears itself, so wake-followed frees emit no clear: these markers
// track registration lifecycle only and do not measure wake-to-ready timing.
void traceReleaseClear(OwnerWake& wake) noexcept {
    if (!ATrace_isEnabled()) return;
    ++wake.counters.clear;
    char label[64]{};
    std::snprintf(label, sizeof(label), "engine_release_clear:%d", wake.generation);
    ATrace_beginSection(label); ATrace_endSection();
}

// Window total; emitted even when zero so "no unsignaled release fence used this path" is reportable.
void traceReleaseWatchFinal(const OwnerWake& wake) noexcept {
    if (!ATrace_isEnabled() || !wake.open) return;
    char label[96]{};
    std::snprintf(label, sizeof(label), "engine_release_watch_final:%d:%u:%u:%u:%u", wake.generation,
        wake.counters.watch, wake.counters.input, wake.counters.fault, wake.counters.clear);
    ATrace_beginSection(label); ATrace_endSection();
}
}

struct BufferedFrameCompositor::State {
    explicit State(std::shared_ptr<GlPresentationCallback> value) : callback(std::move(value)) {}
    Functions functions;
    // At most three frame transactions can be outstanding: each owns one busy viewport.
    TransactionSubmitter transactions;
    std::shared_ptr<GlPresentationCallback> callback;
    std::shared_ptr<CompletionQueue> completions = std::make_shared<CompletionQueue>();
    std::shared_ptr<CommitGate> commitGate = std::make_shared<CommitGate>();
    std::array<FrameBuffer, 3> frames;
    // One-shot looper registrations, one stable slot per frame buffer.
    std::array<ReleaseFenceWatcher, 3> watchers;
    OwnerWake releaseWake;
    std::unordered_set<std::int64_t> pending;
    RetainedFenceList retained;
    RetainedFenceList gpuRetained;
    ASurfaceControl* layer = nullptr;
    int width = 0, height = 0, current = -1, drawing = -1;
    std::int32_t generation = 0;
};

BufferedFrameCompositor::BufferedFrameCompositor(std::shared_ptr<GlPresentationCallback> callback)
    : state_(std::make_unique<State>(std::move(callback))) {}
BufferedFrameCompositor::~BufferedFrameCompositor() { detach(); }
bool BufferedFrameCompositor::supported() const noexcept { return state_->functions.valid(); }

bool BufferedFrameCompositor::attach(ANativeWindow* window, int width, int height) noexcept {
    detach();
    if (!window || !supported()) return false;
    auto& state = *state_;
    state.retained = RetainedFenceList{};
    state.gpuRetained = RetainedFenceList{};
    ++state.generation;
    state.retained.generation = state.generation;
    state.gpuRetained.generation = state.generation;
    state.width = width; state.height = height;
    // Three RGBA8888 infrastructure frames replace the window BufferQueue. The caller's layout
    // viewport is the allocation authority: in the frame a resize lands, the window still
    // reports the previous geometry. Cover every panel up to 8 MP (32 MiB per frame, 3 * 32 MiB
    // total) while refusing an absurd window.
    constexpr std::int64_t kMaximumFramePixels = 8 * 1024 * 1024;
    if (state.width <= 0 || state.height <= 0 ||
        static_cast<std::int64_t>(state.width) * state.height > kMaximumFramePixels) return false;
    state.layer = ASurfaceControl_createFromWindow(window, "EngineBufferedViewport");
    if (!state.layer) return false;
    for (auto& frame : state.frames) {
        if (!allocateFrame(frame, state.functions, state.width, state.height)) { detach(); return false; }
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    state.releaseWake.callback = state.callback.get();
    state.releaseWake.generation = state.generation;
    state.releaseWake.counters = {};
    state.releaseWake.open = true;
    return true;
}

void BufferedFrameCompositor::detach() noexcept {
    auto& state = *state_;
    state.transactions.flush();
    finalizeRetainedFences(state.retained, FenceKind::Present, &state.pending, state.callback);
    finalizeRetainedFences(state.gpuRetained, FenceKind::Gpu, nullptr, state.callback);
    if (state.layer) {
        auto* transaction = ASurfaceTransaction_create();
        ASurfaceTransaction_setVisibility(transaction, state.layer, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        ASurfaceTransaction_reparent(transaction, state.layer, nullptr);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
        ASurfaceControl_release(state.layer);
        state.layer = nullptr;
    }
    for (const auto token : state.pending) state.callback->presented(token, 0, -2, 0);
    state.pending.clear();
    // Callbacks retain the old queue; its fences cannot release buffers of a new attachment.
    state.completions = std::make_shared<CompletionQueue>();
    state.commitGate = std::make_shared<CommitGate>();
    // Every registration is removed before any release fence fd is closed below.
    for (std::size_t slot = 0; slot < state.watchers.size(); ++slot) {
        if (state.watchers[slot].disarm()) traceReleaseClear(state.releaseWake);
    }
    traceReleaseWatchFinal(state.releaseWake);
    state.releaseWake.open = false;
    for (auto& frame : state.frames) releaseFrame(frame, state.functions);
    state.current = -1; state.drawing = -1; state.width = 0; state.height = 0;
}

void BufferedFrameCompositor::poll() noexcept {
    auto& state = *state_;
    std::vector<Completion> items;
    { std::lock_guard lock(state.completions->mutex); items.swap(state.completions->items); }
    sweepPresentRetainedFences(state.retained, state.pending, state.callback);
    sweepGpuRetainedFences(state.gpuRetained);
    for (const auto& item : items) {
        if (item.previous >= 0) {
            auto& frame = state.frames[item.previous];
            // A slot only receives a new previous fence after the old one was freed; disarm
            // defensively so a stale registration can never outlive its fd.
            if (state.watchers[item.previous].disarm()) traceReleaseClear(state.releaseWake);
            frame.wakeAttempted = false;
            frame.releaseFence = item.fence;
            if (item.fence < 0) frame.busy = false;
        }
        reportCompletion(item, state.pending, state.callback, state.retained);
    }
    for (std::size_t slot = 0; slot < state.frames.size(); ++slot) {
        auto& frame = state.frames[slot];
        if (frame.releaseFence < 0) continue;
        pollfd descriptor{frame.releaseFence, POLLIN, 0};
        if (::poll(&descriptor, 1, 0) > 0 && (descriptor.revents & POLLIN)) {
            if (state.watchers[slot].disarm()) traceReleaseClear(state.releaseWake);
            close(frame.releaseFence); frame.releaseFence = -1; frame.busy = false;
            continue;
        }
        // Still pending after this pass, so a later signal cannot be missed: readiness is
        // level-triggered and registration happens from the same pending state. Arm once per
        // assigned fd; on failure the nonblocking tick poll above remains the fallback.
        if (frame.wakeAttempted) continue;
        frame.wakeAttempted = true;
        if (state.watchers[slot].arm(frame.releaseFence, &wakeOwner, &state.releaseWake)) {
            traceReleaseWatch(state.releaseWake);
        }
    }
}

bool BufferedFrameCompositor::ready() noexcept {
    poll();
    if (!state_->layer) return false;
    if (state_->functions.setOnCommit) {
        if (state_->commitGate->outstanding.load(std::memory_order_acquire)) return false;
    } else if (!state_->pending.empty()) {
        // Fallback without setOnCommit: keep latest scene replaceable until the previous transaction
        // completes; completion is not an exact display fence.
        return false;
    }
    for (const auto& frame : state_->frames) if (!frame.busy) return true;
    return false;
}

bool BufferedFrameCompositor::bind(int width, int height) noexcept {
    if (!ready() || width != state_->width || height != state_->height) return false;
    for (int i = 0; i < static_cast<int>(state_->frames.size()); ++i) {
        if (state_->frames[i].busy) continue;
        state_->drawing = i;
        glBindFramebuffer(GL_FRAMEBUFFER, state_->frames[i].framebuffer);
        return glGetError() == GL_NO_ERROR;
    }
    return false;
}

bool BufferedFrameCompositor::presentReady(std::int64_t token) noexcept {
    auto& state = *state_;
    if (state.drawing < 0 || token <= 0) return false;
    auto& frame = state.frames[state.drawing];
    std::uint64_t bufferId = 0;
    if (state.functions.bufferId) state.functions.bufferId(frame.buffer, &bufferId);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const int gpuFence = acquireFence(state.functions);
    if (gpuFence < 0) {
        if (ATrace_isEnabled()) traceGpuFenceMissing(state.generation, token, bufferId, "acquire");
        return false;
    }
    if (ATrace_isEnabled()) {
        const int diagnosticFence = dup(gpuFence);
        if (diagnosticFence >= 0) {
            retainFence(state.gpuRetained, FenceKind::Gpu, state.generation, token, 0, bufferId, diagnosticFence);
        } else {
            traceGpuFenceMissing(state.generation, token, bufferId, "dup");
        }
    }
    ASurfaceTransaction* transaction = nullptr;
    {
        ScopedTraceSection phase("engine_transaction_create");
        transaction = ASurfaceTransaction_create();
    }
    if (ATrace_isEnabled()) {
        char label[128]{};
        std::snprintf(label, sizeof(label), "engine_buffer:%lld:%llu", static_cast<long long>(token),
                      static_cast<unsigned long long>(bufferId));
        ATrace_beginSection(label); ATrace_endSection();
    }
    {
        ScopedTraceSection phase("engine_transaction_configure");
        ASurfaceTransaction_setBuffer(transaction, state.layer, frame.buffer, gpuFence);
        state.functions.transform(transaction, state.layer, ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL);
        ASurfaceTransaction_setBufferDataSpace(transaction, state.layer, ADATASPACE_SRGB);
        ASurfaceTransaction_setBufferTransparency(transaction, state.layer, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setVisibility(transaction, state.layer, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        state.functions.pressure(transaction, state.layer, true);
        state.functions.acquire(state.layer);
        ASurfaceTransaction_setOnComplete(transaction,
            new Ticket{state.completions, state.callback, state.layer, token, state.current, bufferId, state.generation}, completed);
    }
    frame.busy = true;
    state.current = state.drawing; state.drawing = -1;
    state.pending.insert(token);
    armCommitGate(state.functions, transaction, state.commitGate, state.callback);
    {
        ScopedTraceSection phase("engine_transaction_enqueue");
        state.transactions.submit(transaction);
    }
    return true;
}

unsigned int BufferedFrameCompositor::drawingFramebuffer() const noexcept {
    return state_->drawing < 0 ? 0 : state_->frames[state_->drawing].framebuffer;
}

void BufferedFrameCompositor::hide() noexcept {
    if (!state_->layer) return;
    state_->transactions.flush();
    finalizeRetainedFences(state_->retained, FenceKind::Present, &state_->pending, state_->callback);
    finalizeRetainedFences(state_->gpuRetained, FenceKind::Gpu, nullptr, state_->callback);
    auto* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(transaction, state_->layer, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}
