#include "present/SurfaceControlPresentBackend.h"
#include "present/AhbCompositorCoordinates.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>

#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <sys/resource.h>

namespace {

constexpr char kTag[] = "NtkRollingRenderer";
// Both the Java frame-admission thread and this native EGL owner are presentation threads. The
// latter used to inherit a normal nice value from std::thread, so an otherwise sub-millisecond
// frame could wake after the emulator's compositor cut. Request Android's urgent-display class
// and retain the display fallback on kernels with a narrower RLIMIT_NICE.
constexpr int kUrgentDisplayNice = -8;
constexpr int kDisplayNiceFallback = -4;
// One applied frame plus one newest pending frame is sufficient for a demand-bound reader.
// A deeper mailbox would retain stale Bitmap global references while BufferQueue presents a newer
// downward viewport.
constexpr std::size_t kMaxQueuedFrames = 1;
// The normal producer still publishes only the current page plus two unread pages. Once every
// post-click original has been installed it may publish one full-scene snapshot; keep that queue
// intact so the worker can fill idle EGL slots during the long forward traversal.
constexpr std::size_t kMaxQueuedPrewarmTiles = 1024;
// Kotlin's normal viewport snapshot is capped at twelve tiles. Any larger immutable handoff is
// the one terminal full-scene snapshot and seals the epoch against later partial re-enqueues.
constexpr std::size_t kResidentSnapshotMaxTiles = 12;
// During a physical drag/fling, keep a bounded immutable forward runway resident. Four pages was
// enough for finger-speed reading but not repeated fast forward swipes: a 104-page cold trace
// reached the next page before its texture upload and combined that 6.6 ms allocation with a host
// swap fence. Sixteen pages covers the maximum fast-fling advance while the one-upload-per-display
// pacing below still prevents a complete-scene burst from saturating gfxstream.
constexpr int kPausedForwardPrewarmPages = 16;
constexpr std::int64_t kDefaultRefreshPeriodNanos = 11'111'111;
// UiAutomator and real repeated swipes both have a short interval after OverScroller finishes and
// before the next ACTION_DOWN.  gfxstream retains uploads issued in that interval and makes the
// next visible submission pay their fence cost.  Only a genuine reading pause may reopen the
// non-presenting upload lane.
constexpr std::int64_t kPrewarmResumeQuietNanos = 750'000'000;
// The product's continuous reader is forward-biased. Keep a small bounce margin behind the
// visible span, but do not let already-read GPU copies accumulate until the generic byte ceiling.
// The immutable Java Bitmap remains installed, so an uncommon upward gesture can upload it again
// without network or decode work. A 200-page 1280px trace otherwise retained 49 old textures
// (about 299 MiB) and made gfxstream block eglSwapBuffers for 106 ms during a forward fling.
constexpr int kRetainedBackwardTexturePages = 2;
// This is the ordinary rolling-window floor. Once Kotlin hands over one exact immutable full-scene
// snapshot, the renderer raises the epoch budget to that snapshot's checked RGBA byte count. A
// fixed 288 MiB ceiling made a 112-page episode continually evict and re-upload about sixty pages,
// so the last page could never become render-ready even on an 8 GiB host-GPU emulator.
constexpr std::uint64_t kMaxTextureBudgetBytes = 288ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaxResidentTextureCount = 1024;
// Deleting and recreating texture storage on the emulator's host GL translator serializes the
// render pipe. Keep a very small, byte-bounded storage pool so later pages reuse existing
// allocations. This is GPU storage only: it neither retains encoded bodies nor starts requests.
constexpr std::uint64_t kMaxPooledTextureBytes = 16ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaxPooledTextureCount = 12;

#define RLOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define RLOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

void requestUrgentDisplayPriority() noexcept {
    errno = 0;
    const int before = getpriority(PRIO_PROCESS, 0);
    const int beforeError = errno;
    errno = 0;
    const int urgentResult = setpriority(PRIO_PROCESS, 0, kUrgentDisplayNice);
    const int urgentError = urgentResult == 0 ? 0 : errno;
    int fallbackError = 0;
    if (urgentResult != 0 && (beforeError != 0 || before > kDisplayNiceFallback)) {
        errno = 0;
        const int fallbackResult =
            setpriority(PRIO_PROCESS, 0, kDisplayNiceFallback);
        fallbackError = fallbackResult == 0 ? 0 : errno;
    }
    errno = 0;
    const int effective = getpriority(PRIO_PROCESS, 0);
    const int effectiveError = errno;
    __android_log_print(
        effectiveError == 0 && effective <= kDisplayNiceFallback
            ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
        kTag,
        "thread-priority role=rolling-egl requested=%d before=%d effective=%d "
        "urgentErrno=%d fallbackErrno=%d getErrno=%d",
        kUrgentDisplayNice, before, effective,
        urgentError, fallbackError, effectiveError);
}

std::int64_t nowNanos() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(value.tv_sec) * 1'000'000'000LL + value.tv_nsec;
}

int setNativeWindowSwapInterval(
        ANativeWindow* window,
        int interval) noexcept {
    if (window == nullptr) return -1;
    using SetSwapInterval = int (*)(ANativeWindow*, int);
    static SetSwapInterval setSwapInterval = []() noexcept {
        // Deliberately retain the library for the process lifetime: the resolved function is used
        // for every surface attachment and dlopen/dlclose itself would add renderer-thread jitter.
        void* library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
        return reinterpret_cast<SetSwapInterval>(
            library != nullptr
                ? dlsym(library, "ANativeWindow_setSwapInterval")
                : nullptr);
    }();
    return setSwapInterval != nullptr ? setSwapInterval(window, interval) : -3;
}

struct NativeWindowBufferControls {
    using SetBufferCount = int (*)(ANativeWindow*, int);
    using TryAllocateBuffers = void (*)(ANativeWindow*);
    using SetSharedBufferMode = int (*)(ANativeWindow*, bool);
    using SetAutoRefresh = int (*)(ANativeWindow*, bool);
    using SetFrameRate = int32_t (*)(ANativeWindow*, float, int8_t);

    SetBufferCount setBufferCount = nullptr;
    TryAllocateBuffers tryAllocateBuffers = nullptr;
    SetSharedBufferMode setSharedBufferMode = nullptr;
    SetAutoRefresh setAutoRefresh = nullptr;
    SetFrameRate setFrameRate = nullptr;
};

const NativeWindowBufferControls& nativeWindowBufferControls() noexcept {
    static const NativeWindowBufferControls controls = []() noexcept {
        void* library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
        return NativeWindowBufferControls{
            reinterpret_cast<NativeWindowBufferControls::SetBufferCount>(
                library != nullptr
                    ? dlsym(library, "ANativeWindow_setBufferCount")
                    : nullptr),
            reinterpret_cast<NativeWindowBufferControls::TryAllocateBuffers>(
                library != nullptr
                    ? dlsym(library, "ANativeWindow_tryAllocateBuffers")
                    : nullptr),
            reinterpret_cast<NativeWindowBufferControls::SetSharedBufferMode>(
                library != nullptr
                    ? dlsym(library, "ANativeWindow_setSharedBufferMode")
                    : nullptr),
            reinterpret_cast<NativeWindowBufferControls::SetAutoRefresh>(
                library != nullptr
                    ? dlsym(library, "ANativeWindow_setAutoRefresh")
                    : nullptr),
            reinterpret_cast<NativeWindowBufferControls::SetFrameRate>(
                library != nullptr
                    ? dlsym(library, "ANativeWindow_setFrameRate")
                    : nullptr),
        };
    }();
    return controls;
}

struct TileKey {
    std::int64_t structureEpoch = 0;
    int page = 0;
    int slot = 0;

    bool operator==(const TileKey& other) const noexcept {
        return structureEpoch == other.structureEpoch && page == other.page && slot == other.slot;
    }
};

struct TileKeyHash {
    std::size_t operator()(const TileKey& key) const noexcept {
        std::size_t hash = static_cast<std::size_t>(key.structureEpoch);
        hash ^= static_cast<std::size_t>(key.page + 0x9e3779b9) + (hash << 6U) + (hash >> 2U);
        hash ^= static_cast<std::size_t>(key.slot + 0x85ebca6b) + (hash << 6U) + (hash >> 2U);
        return hash;
    }
};

struct FrameTile {
    TileKey key{};
    int sourceTop = 0;
    int sourceBottom = 0;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int bitmapIdentity = 0;
    float pageTop = 0.0F;
    float pageHeight = 0.0F;
    jobject bitmap = nullptr;
};

struct FrameCommand {
    std::uint64_t token = 0;
    std::int64_t structureEpoch = 0;
    int width = 0;
    int height = 0;
    std::vector<FrameTile> tiles;
};

struct AppliedFrameTileSignature {
    TileKey key{};
    int sourceTop = 0;
    int sourceBottom = 0;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int bitmapIdentity = 0;
    float pageTop = 0.0F;
    float pageHeight = 0.0F;
};

struct TextureTile {
    GLuint texture = 0;
    int bitmapIdentity = 0;
    int width = 0;
    int height = 0;
    std::uint64_t bytes = 0;
    std::uint64_t lastUsedFrame = 0;
};

struct AttachCommand {
    ANativeWindow* window = nullptr;
    int width = 0;
    int height = 0;
    std::uint64_t epoch = 0;
    std::int64_t refreshPeriodNanos = kDefaultRefreshPeriodNanos;
};

struct PrepareCommand {
    int width = 0;
    int height = 0;
};

/**
 * One GPU-complete successor may wait for the previous SurfaceControl OnCommit.
 *
 * The old rolling path waited for OnCommit before it acquired an AHB, uploaded textures and
 * rendered. Host-gpu hiccups in any of those steps then missed the next compositor cut. Keeping
 * the transaction prepared (but unapplied) overlaps that work with the predecessor's display
 * interval without allowing SurfaceControl to own two unlatched buffers.
 */
struct PreparedDirectFrame {
    FrameCommand frame{};
    ntk::present::SurfaceControlPresentBackend::PreparedSurfaceSubmission submission{};
    std::int64_t beginNanos = 0;
    std::int64_t bindEndNanos = 0;
    std::int64_t uploadBeginNanos = 0;
    std::int64_t uploadEndNanos = 0;
    std::int64_t renderBeginNanos = 0;
    std::int64_t renderEndNanos = 0;
    std::int64_t fenceBeginNanos = 0;
    std::int64_t fenceEndNanos = 0;
    std::int64_t prepareBeginNanos = 0;
    std::int64_t prepareEndNanos = 0;
    bool occupied = false;
};

class RollingRenderer final {
public:
    RollingRenderer(JNIEnv* env, jobject callback) {
        if (env == nullptr || callback == nullptr ||
            env->GetJavaVM(&vm_) != JNI_OK || vm_ == nullptr) {
            failed_.store(true, std::memory_order_release);
            return;
        }
        callback_ = env->NewGlobalRef(callback);
        jclass callbackClass = env->GetObjectClass(callback);
        if (callback_ == nullptr || callbackClass == nullptr) {
            failed_.store(true, std::memory_order_release);
            return;
        }
        latchedMethod_ = env->GetMethodID(callbackClass, "onNtkRollingFrameLatched", "(JJJI)V");
        droppedMethod_ = env->GetMethodID(callbackClass, "onNtkRollingFrameDropped", "(J)V");
        fatalMethod_ = env->GetMethodID(callbackClass, "onNtkRollingRendererFatal", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(callbackClass);
        if (latchedMethod_ == nullptr || droppedMethod_ == nullptr || fatalMethod_ == nullptr ||
            env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failed_.store(true, std::memory_order_release);
            return;
        }
        thread_ = std::thread(&RollingRenderer::run, this);
    }

    ~RollingRenderer() { destroy(); }

    bool valid() const noexcept {
        return callback_ != nullptr && !failed_.load(std::memory_order_acquire);
    }

    bool prepare(int width, int height) {
        if (width <= 0 || height <= 0 ||
            stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        pendingPrepare_ = {width, height};
        preparePending_ = true;
        condition_.notify_one();
        return true;
    }

    bool attach(ANativeWindow* window, int width, int height,
                std::uint64_t epoch, std::int64_t refreshPeriodNanos) {
        if (window == nullptr || width <= 0 || height <= 0 || epoch == 0 ||
            stopped_.load(std::memory_order_acquire)) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (pendingAttach_.window != nullptr) ANativeWindow_release(pendingAttach_.window);
        pendingAttach_ = {
            window, width, height, epoch,
            refreshPeriodNanos > 0 ? refreshPeriodNanos : kDefaultRefreshPeriodNanos
        };
        attachPending_ = true;
        detachPending_ = false;
        condition_.notify_one();
        return true;
    }

    void detach(std::uint64_t epoch) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (pendingAttach_.window != nullptr) {
            ANativeWindow_release(pendingAttach_.window);
            pendingAttach_ = {};
        }
        attachPending_ = false;
        detachPending_ = true;
        detachEpoch_ = epoch;
        condition_.notify_one();
    }

    bool submit(JNIEnv* env, std::uint64_t token, std::int64_t structureEpoch,
                int width, int height, jintArray tileData,
                jfloatArray geometryData, jobjectArray bitmaps) {
        if (env == nullptr || token == 0 || structureEpoch <= 0 || width <= 0 || height <= 0 ||
            tileData == nullptr || geometryData == nullptr || bitmaps == nullptr ||
            stopped_.load(std::memory_order_acquire) || failed_.load(std::memory_order_acquire)) {
            return false;
        }
        const jsize tileInts = env->GetArrayLength(tileData);
        const jsize geometryFloats = env->GetArrayLength(geometryData);
        const jsize bitmapCount = env->GetArrayLength(bitmaps);
        if (bitmapCount <= 0 || tileInts != bitmapCount * 7 ||
            geometryFloats != bitmapCount * 2) return false;

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!surfaceAttached_) return false;
        }

        std::vector<jint> integers(static_cast<std::size_t>(tileInts));
        std::vector<jfloat> geometry(static_cast<std::size_t>(geometryFloats));
        env->GetIntArrayRegion(tileData, 0, tileInts, integers.data());
        env->GetFloatArrayRegion(geometryData, 0, geometryFloats, geometry.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }

        FrameCommand command;
        command.token = token;
        command.structureEpoch = structureEpoch;
        command.width = width;
        command.height = height;
        command.tiles.reserve(static_cast<std::size_t>(bitmapCount));
        for (jsize index = 0; index < bitmapCount; ++index) {
            jobject local = env->GetObjectArrayElement(bitmaps, index);
            if (local == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (local != nullptr) env->DeleteLocalRef(local);
                releaseFrame(env, command);
                return false;
            }
            jobject global = env->NewGlobalRef(local);
            env->DeleteLocalRef(local);
            if (global == nullptr) {
                releaseFrame(env, command);
                return false;
            }
            const std::size_t i = static_cast<std::size_t>(index);
            const std::size_t ib = i * 7U;
            const std::size_t gb = i * 2U;
            FrameTile tile;
            tile.key = {structureEpoch, integers[ib], integers[ib + 1U]};
            tile.sourceTop = integers[ib + 2U];
            tile.sourceBottom = integers[ib + 3U];
            tile.sourceWidth = integers[ib + 4U];
            tile.sourceHeight = integers[ib + 5U];
            tile.bitmapIdentity = integers[ib + 6U];
            tile.pageTop = geometry[gb];
            tile.pageHeight = geometry[gb + 1U];
            tile.bitmap = global;
            if (tile.key.page < 0 || tile.key.slot < 0 || tile.sourceTop < 0 ||
                tile.sourceBottom <= tile.sourceTop || tile.sourceWidth <= 0 ||
                tile.sourceHeight < tile.sourceBottom || tile.pageHeight <= 0.0F) {
                command.tiles.push_back(tile);
                releaseFrame(env, command);
                return false;
            }
            command.tiles.push_back(tile);
        }

        FrameCommand superseded;
        bool hasSuperseded = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!surfaceAttached_ || stopped_.load(std::memory_order_acquire)) {
                releaseFrame(env, command);
                return false;
            }
            // Geometry changes arrive at display cadence while SurfaceControl owns at most one
            // unlatched buffer. Preserve the newest demand instead of building latency behind
            // stale scroll positions. The superseded token is explicitly retired through the
            // normal callback, so Kotlin can never mistake it for a physical commit.
            if (frames_.size() >= kMaxQueuedFrames) {
                superseded = std::move(frames_.back());
                frames_.pop_back();
                hasSuperseded = true;
            }
            frames_.push_back(std::move(command));
        }
        if (hasSuperseded) {
            callbackDropped(env, superseded.token);
            releaseFrame(env, superseded);
            ++supersededFrames_;
        }
        ++acceptedFrames_;
        condition_.notify_one();
        return true;
    }

    /**
     * Enqueues decoded pixels for texture upload only. Unlike submit(), this command carries no
     * viewport geometry or frame token and has no path to the SurfaceControl backend.
     */
    bool prewarm(JNIEnv* env, std::int64_t structureEpoch,
                 jintArray tileData, jobjectArray bitmaps) {
        if (env == nullptr || structureEpoch <= 0 || tileData == nullptr || bitmaps == nullptr ||
            stopped_.load(std::memory_order_acquire) || failed_.load(std::memory_order_acquire)) {
            return false;
        }
        const jsize tileInts = env->GetArrayLength(tileData);
        const jsize bitmapCount = env->GetArrayLength(bitmaps);
        if (bitmapCount <= 0 || tileInts != bitmapCount * 7) return false;

        std::vector<jint> integers(static_cast<std::size_t>(tileInts));
        env->GetIntArrayRegion(tileData, 0, tileInts, integers.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }

        std::vector<FrameTile> incoming;
        incoming.reserve(static_cast<std::size_t>(bitmapCount));
        for (jsize index = 0; index < bitmapCount; ++index) {
            jobject local = env->GetObjectArrayElement(bitmaps, index);
            if (local == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (local != nullptr) env->DeleteLocalRef(local);
                for (auto& tile : incoming) releaseTile(env, tile);
                return false;
            }
            jobject global = env->NewGlobalRef(local);
            env->DeleteLocalRef(local);
            if (global == nullptr) {
                for (auto& tile : incoming) releaseTile(env, tile);
                return false;
            }
            const std::size_t base = static_cast<std::size_t>(index) * 7U;
            FrameTile tile;
            tile.key = {structureEpoch, integers[base], integers[base + 1U]};
            tile.sourceTop = integers[base + 2U];
            tile.sourceBottom = integers[base + 3U];
            tile.sourceWidth = integers[base + 4U];
            tile.sourceHeight = integers[base + 5U];
            tile.bitmapIdentity = integers[base + 6U];
            tile.bitmap = global;
            if (tile.key.page < 0 || tile.key.slot < 0 || tile.sourceTop < 0 ||
                tile.sourceBottom <= tile.sourceTop || tile.sourceWidth <= 0 ||
                tile.sourceHeight < tile.sourceBottom) {
                incoming.push_back(tile);
                for (auto& owned : incoming) releaseTile(env, owned);
                return false;
            }
            incoming.push_back(tile);
        }

        bool accepted = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_.load(std::memory_order_acquire) ||
                failed_.load(std::memory_order_acquire)) {
                for (auto& tile : incoming) releaseTile(env, tile);
                return false;
            }
            if (queuedPrewarmEpoch_ != structureEpoch) {
                for (auto& tile : prewarmTiles_) releaseTile(env, tile);
                prewarmTiles_.clear();
                queuedPrewarmEpoch_ = structureEpoch;
                sealedFullScenePrewarmEpoch_ = 0;
            }
            const bool fullSceneSnapshot =
                static_cast<std::size_t>(bitmapCount) > kResidentSnapshotMaxTiles;
            const bool ignoreIncoming = sealedFullScenePrewarmEpoch_ == structureEpoch;
            if (ignoreIncoming) {
                // The authoritative full-scene snapshot already owns every immutable original in
                // forward order. Later resident-window snapshots would append pages that have
                // already passed, evict the unread tail, and restart uploads from page zero at the
                // end of the sweep. Acknowledge the redundant handoff without retaining it.
                accepted = true;
            } else if (fullSceneSnapshot) {
                for (auto& queued : prewarmTiles_) releaseTile(env, queued);
                prewarmTiles_.clear();
                sealedFullScenePrewarmEpoch_ = structureEpoch;
                // The dimensions were validated above and describe the exact source spans that
                // uploadTile() will allocate. Saturate only on arithmetic overflow; an overflowed
                // value keeps the normal floor and the GL upload will fail normally instead of
                // wrapping the budget and evicting unrelated live textures.
                std::uint64_t sceneBytes = 0;
                bool sceneBytesValid = true;
                for (const auto& candidate : incoming) {
                    const std::uint64_t width =
                        static_cast<std::uint64_t>(candidate.sourceWidth);
                    const std::uint64_t height = static_cast<std::uint64_t>(
                        candidate.sourceBottom - candidate.sourceTop);
                    if (width > UINT64_MAX / 4ULL ||
                        height > UINT64_MAX / (width * 4ULL) ||
                        sceneBytes > UINT64_MAX - width * height * 4ULL) {
                        sceneBytesValid = false;
                        break;
                    }
                    sceneBytes += width * height * 4ULL;
                }
                fullSceneTextureBudgetEpoch_ = structureEpoch;
                fullSceneTextureBudgetBytes_ = sceneBytesValid ? sceneBytes : 0;
                RLOGI(
                    "texture full-scene budget epoch=%lld tiles=%d bytes=%llu floor=%llu valid=%d",
                    static_cast<long long>(structureEpoch), static_cast<int>(bitmapCount),
                    static_cast<unsigned long long>(fullSceneTextureBudgetBytes_),
                    static_cast<unsigned long long>(kMaxTextureBudgetBytes),
                    sceneBytesValid ? 1 : 0);
            } else {
                // A resident snapshot is a complete description of the newest visible/forward
                // runway. Retaining older snapshots makes a fast fling upload pages the user has
                // already passed before serving the current viewport. Replace queued (not
                // in-flight) work; decoded originals remain owned by Kotlin and can be submitted
                // again if the viewport returns.
                for (auto& queued : prewarmTiles_) releaseTile(env, queued);
                prewarmTiles_.clear();
            }
            for (auto& tile : incoming) {
                if (ignoreIncoming) break;
                auto queued = std::find_if(
                    prewarmTiles_.begin(), prewarmTiles_.end(),
                    [&](const FrameTile& value) { return value.key == tile.key; });
                if (queued != prewarmTiles_.end()) {
                    if (queued->bitmapIdentity == tile.bitmapIdentity) {
                        releaseTile(env, tile);
                        accepted = true;
                        continue;
                    }
                    releaseTile(env, *queued);
                    prewarmTiles_.erase(queued);
                }
                while (prewarmTiles_.size() >= kMaxQueuedPrewarmTiles) {
                    releaseTile(env, prewarmTiles_.front());
                    prewarmTiles_.pop_front();
                    ++discardedPrewarmTiles_;
                }
                prewarmTiles_.push_back(tile);
                tile.bitmap = nullptr;
                accepted = true;
            }
        }
        for (auto& tile : incoming) releaseTile(env, tile);
        if (accepted) condition_.notify_one();
        return accepted;
    }

    void destroy() {
        bool expected = false;
        if (!destroyed_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) return;
        stopped_.store(true, std::memory_order_release);
        condition_.notify_all();
        if (thread_.joinable()) thread_.join();

        JNIEnv* env = attachEnv();
        if (env != nullptr && callback_ != nullptr) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
    }

    void setPrewarmPaused(bool paused) noexcept {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            prewarmPaused_ = paused;
            if (!paused) {
                // A display-period delay is insufficient on host-GPU emulators: a normal chain of
                // forward flings contains 20-200 ms quiet gaps, and uploads started there remain
                // ahead of the next visible buffer in gfxstream. Require a real UX pause.
                nextPrewarmUploadNanos_ = std::max(
                    nextPrewarmUploadNanos_,
                    nowNanos() + kPrewarmResumeQuietNanos);
            }
        }
        if (!paused) condition_.notify_one();
    }

private:
    enum class PresentResult : std::uint8_t {
        APPLIED = 0,
        TRANSIENT_BACKPRESSURE = 1,
        PREPARED_WAITING = 2,
        FAILED = 3,
    };

    static void wake(void* context) noexcept {
        auto* renderer = static_cast<RollingRenderer*>(context);
        if (renderer != nullptr) renderer->condition_.notify_one();
    }

    JNIEnv* attachEnv() const noexcept {
        if (vm_ == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        return env;
    }

    void releaseTile(JNIEnv* env, FrameTile& tile) noexcept {
        if (env != nullptr && tile.bitmap != nullptr) env->DeleteGlobalRef(tile.bitmap);
        tile.bitmap = nullptr;
    }

    void releaseFrame(JNIEnv* env, FrameCommand& command) noexcept {
        if (env == nullptr) return;
        for (auto& tile : command.tiles) {
            releaseTile(env, tile);
        }
        command.tiles.clear();
    }

    bool matchesLastAppliedFrame(const FrameCommand& frame) const noexcept {
        if (lastAppliedFrameWidth_ != frame.width || lastAppliedFrameHeight_ != frame.height ||
            lastAppliedFrameEpoch_ != frame.structureEpoch ||
            lastAppliedFrameTiles_.size() != frame.tiles.size()) return false;
        for (std::size_t index = 0; index < frame.tiles.size(); ++index) {
            const auto& tile = frame.tiles[index];
            const auto& applied = lastAppliedFrameTiles_[index];
            if (!(applied.key == tile.key) || applied.sourceTop != tile.sourceTop ||
                applied.sourceBottom != tile.sourceBottom ||
                applied.sourceWidth != tile.sourceWidth ||
                applied.sourceHeight != tile.sourceHeight ||
                applied.bitmapIdentity != tile.bitmapIdentity ||
                applied.pageTop != tile.pageTop || applied.pageHeight != tile.pageHeight) {
                return false;
            }
        }
        return !frame.tiles.empty();
    }

    /** Called only while [mutex_] is held by the renderer loop. */
    bool canUploadNextPrewarmLocked() const noexcept {
        // setPrewarmPaused(true) is the physical-input ownership boundary. The flag previously
        // existed only as telemetry/state: this predicate ignored it, so a full-scene snapshot
        // kept winning the EGL lane while 500 forward gestures accumulated. Visible submissions
        // must always preempt non-presenting uploads; the queue remains intact and resumes after
        // the real quiet-period gate in setPrewarmPaused(false).
        if (prewarmPaused_ || prewarmTiles_.empty()) return false;
        // The immutable full-scene queue is ordered from the first page to the last. During
        // physical motion it may feed only the short forward runway: this removes first-visible
        // allocation/upload at page boundaries without turning the entire episode into input-time
        // GPU work. Visible frames still own admission in [run]; prewarm is chosen only while the
        // frame mailbox is empty or contains the already-applied scene.
        //
        // Outside physical motion the same forward bound remains intentional. A repeated-swipe
        // quiet gap is not permission to saturate gfxstream with the complete episode.
        if (lastPresentedStructureEpoch_ <= 0 ||
            queuedPrewarmEpoch_ != lastPresentedStructureEpoch_ ||
            lastPresentedMaxPage_ < 0) return true;
        const FrameTile& next = prewarmTiles_.front();
        return next.key.structureEpoch == lastPresentedStructureEpoch_ &&
            next.key.page <= lastPresentedMaxPage_ + kPausedForwardPrewarmPages;
    }

    void rememberAppliedFrame(const FrameCommand& frame) {
        lastAppliedFrameWidth_ = frame.width;
        lastAppliedFrameHeight_ = frame.height;
        lastAppliedFrameEpoch_ = frame.structureEpoch;
        lastAppliedFrameTiles_.clear();
        lastAppliedFrameTiles_.reserve(frame.tiles.size());
        for (const auto& tile : frame.tiles) {
            lastAppliedFrameTiles_.push_back({
                tile.key, tile.sourceTop, tile.sourceBottom, tile.sourceWidth,
                tile.sourceHeight, tile.bitmapIdentity, tile.pageTop, tile.pageHeight});
        }
    }

    void discardQueuedPrewarmOutsideEpoch(
            JNIEnv* env,
            std::int64_t structureEpoch) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (queuedPrewarmEpoch_ != structureEpoch) {
            // A terminal full-scene seal belongs only to the immutable episode generation that
            // produced it. Never carry that authority across a new page structure.
            sealedFullScenePrewarmEpoch_ = 0;
        }
        for (auto tile = prewarmTiles_.begin(); tile != prewarmTiles_.end();) {
            if (tile->key.structureEpoch == structureEpoch) {
                ++tile;
                continue;
            }
            releaseTile(env, *tile);
            tile = prewarmTiles_.erase(tile);
            ++discardedPrewarmTiles_;
        }
        queuedPrewarmEpoch_ = structureEpoch;
    }

    void callbackDropped(JNIEnv* env, std::uint64_t token) noexcept {
        if (env == nullptr || callback_ == nullptr || droppedMethod_ == nullptr || token == 0) return;
        const std::uint64_t ordinal = ++droppedFrames_;
        if (ordinal == 1 || ordinal % 90 == 0) {
            RLOGI("frame dropped ordinal=%llu token=%llu accepted=%llu superseded=%llu",
                  static_cast<unsigned long long>(ordinal),
                  static_cast<unsigned long long>(token),
                  static_cast<unsigned long long>(acceptedFrames_.load(std::memory_order_relaxed)),
                  static_cast<unsigned long long>(supersededFrames_.load(std::memory_order_relaxed)));
        }
        env->CallVoidMethod(callback_, droppedMethod_, static_cast<jlong>(token));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void callbackLatched(JNIEnv* env, const ntk::present::FixedPresentEvent& event) noexcept {
        if (env == nullptr || callback_ == nullptr || latchedMethod_ == nullptr ||
            event.identity.ntkFrameId == 0) return;
        const std::uint64_t ordinal = ++latchedFrames_;
        if (ordinal == 1 || ordinal % 90 == 0) {
            RLOGI("frame latched ordinal=%llu token=%llu submitted=%llu callbackDelayUs=%lld",
                  static_cast<unsigned long long>(ordinal),
                  static_cast<unsigned long long>(event.identity.ntkFrameId),
                  static_cast<unsigned long long>(submittedFrames_),
                  static_cast<long long>((event.callbackObservedNanos - event.latchNanos) / 1000));
        }
        env->CallVoidMethod(
            callback_, latchedMethod_, static_cast<jlong>(event.identity.ntkFrameId),
            static_cast<jlong>(event.latchNanos),
            static_cast<jlong>(event.callbackObservedNanos),
            static_cast<jint>(1));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void callbackWindowFramePresented(
            JNIEnv* env,
            std::uint64_t token,
            std::int64_t completedNanos,
            int presentationKind) noexcept {
        if (env == nullptr || callback_ == nullptr || latchedMethod_ == nullptr || token == 0 ||
            completedNanos <= 0 || presentationKind != 2) return;
        const std::uint64_t ordinal = ++latchedFrames_;
        if (ordinal == 1 || ordinal % 90 == 0) {
            RLOGI("window frame presented ordinal=%llu token=%llu submitted=%llu kind=%d",
                  static_cast<unsigned long long>(ordinal),
                  static_cast<unsigned long long>(token),
                  static_cast<unsigned long long>(submittedFrames_), presentationKind);
        }
        // Kind 2 is an exact successful BufferQueue swap. Keep it distinct from the direct
        // SurfaceControl callback so Kotlin does not report a window queue as a transaction latch.
        env->CallVoidMethod(
            callback_, latchedMethod_, static_cast<jlong>(token),
            static_cast<jlong>(completedNanos),
            static_cast<jlong>(completedNanos),
            static_cast<jint>(presentationKind));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void fatal(JNIEnv* env, const char* reason) noexcept {
        if (failed_.exchange(true, std::memory_order_acq_rel)) return;
        RLOGE("fatal reason=%s", reason != nullptr ? reason : "unknown");
        if (env == nullptr || callback_ == nullptr || fatalMethod_ == nullptr) return;
        jstring message = env->NewStringUTF(reason != nullptr ? reason : "unknown");
        if (message != nullptr) {
            env->CallVoidMethod(callback_, fatalMethod_, message);
            env->DeleteLocalRef(message);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    GLuint compileShader(GLenum type, const char* source) noexcept {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled == GL_TRUE) return shader;
        glDeleteShader(shader);
        return 0;
    }

    bool initializeEgl() noexcept {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
            return false;
        }
        constexpr EGLint configAttributes[] = {
            // One context owns the tiny detached pbuffer and the visible SurfaceView window.
            // Rendering the visible reader through BufferQueue removes the old one-deep
            // predecessor-OnCommit serialization that capped gfxstream near 40 Hz.
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT | EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        EGLint count = 0;
        if (eglChooseConfig(display_, configAttributes, &config_, 1, &count) != EGL_TRUE ||
            count != 1) return false;
        constexpr EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
        if (context_ == EGL_NO_CONTEXT) return false;
        constexpr EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttributes);
        if (pbuffer_ == EGL_NO_SURFACE ||
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE) return false;

        constexpr char vertexSource[] =
            "#version 300 es\n"
            "layout(location=0) in vec2 aPosition;\n"
            "layout(location=1) in vec2 aTexCoord;\n"
            "uniform vec2 uYBounds;\n"
            "out vec2 vTexCoord;\n"
            "void main(){float y=mix(uYBounds.x,uYBounds.y,aPosition.y);"
            "gl_Position=vec4(aPosition.x,y,0.0,1.0);vTexCoord=aTexCoord;}\n";
        constexpr char fragmentSource[] =
            "#version 300 es\n"
            "precision mediump float;\n"
            "in vec2 vTexCoord;uniform sampler2D uTexture;out vec4 outColor;\n"
            "void main(){outColor=texture(uTexture,vTexCoord);}\n";
        GLuint vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
        GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) return false;
        program_ = glCreateProgram();
        glAttachShader(program_, vertex);
        glAttachShader(program_, fragment);
        glLinkProgram(program_);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        GLint linked = GL_FALSE;
        glGetProgramiv(program_, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) return false;
        yBoundsUniform_ = glGetUniformLocation(program_, "uYBounds");
        const GLint textureUniform = glGetUniformLocation(program_, "uTexture");
        if (yBoundsUniform_ < 0 || textureUniform < 0) return false;
        constexpr float vertices[] = {
            -1.0F, 0.0F, 0.0F, 0.0F,  1.0F, 0.0F, 1.0F, 0.0F,
            -1.0F, 1.0F, 0.0F, 1.0F, -1.0F, 1.0F, 0.0F, 1.0F,
             1.0F, 0.0F, 1.0F, 0.0F,  1.0F, 1.0F, 1.0F, 1.0F,
        };
        glGenVertexArrays(1, &vao_);
        glGenBuffers(1, &vbo_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<void*>(2 * sizeof(float)));
        glBindVertexArray(0);
        glUseProgram(program_);
        glUniform1i(textureUniform, 0);
        glUseProgram(0);
        const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        RLOGI("cold EGL ready renderer=%s", renderer != nullptr ? renderer : "unknown");
        return glGetError() == GL_NO_ERROR;
    }

    void destroyEgl() noexcept {
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            for (auto& entry : textures_) {
                if (entry.second.texture != 0) glDeleteTextures(1, &entry.second.texture);
            }
            for (auto& pooled : pooledTextures_) {
                if (pooled.texture != 0) glDeleteTextures(1, &pooled.texture);
            }
            textures_.clear();
            pooledTextures_.clear();
            residentTextureBytes_ = 0;
            pooledTextureBytes_ = 0;
            if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
            if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
            if (program_ != 0) glDeleteProgram(program_);
        }
        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (windowSurface_ != EGL_NO_SURFACE) {
                eglDestroySurface(display_, windowSurface_);
            }
            if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
            if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
            eglTerminate(display_);
        }
        display_ = EGL_NO_DISPLAY;
        context_ = EGL_NO_CONTEXT;
        pbuffer_ = EGL_NO_SURFACE;
        windowSurface_ = EGL_NO_SURFACE;
        if (nativeWindow_ != nullptr) {
            ANativeWindow_release(nativeWindow_);
            nativeWindow_ = nullptr;
        }
    }

    bool uploadTile(
            JNIEnv* env,
            const FrameTile& tile,
            std::uint64_t useFrame) noexcept {
        auto existing = textures_.find(tile.key);
        if (existing != textures_.end() &&
            existing->second.bitmapIdentity == tile.bitmapIdentity) {
            existing->second.lastUsedFrame = useFrame;
            return true;
        }

        AndroidBitmapInfo info{};
        const int sourceSpan = tile.sourceBottom - tile.sourceTop;
        if (AndroidBitmap_getInfo(env, tile.bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            static_cast<int>(info.width) != tile.sourceWidth ||
            (static_cast<int>(info.height) != sourceSpan &&
             static_cast<int>(info.height) != tile.sourceHeight)) return false;
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, tile.bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) return false;

        const int width = static_cast<int>(info.width);
        const int height = sourceSpan;
        const int bitmapSourceTop = static_cast<int>(info.height) == tile.sourceHeight
            ? tile.sourceTop
            : 0;
        auto* tilePixels = static_cast<std::uint8_t*>(pixels) +
            static_cast<std::size_t>(bitmapSourceTop) * info.stride;
        const std::uint64_t textureBytes =
            static_cast<std::uint64_t>(width) *
            static_cast<std::uint64_t>(height) * 4ULL;

        while (glGetError() != GL_NO_ERROR) {}
        GLuint texture = 0;
        bool allocatedStorage = false;
        bool generatedTexture = false;
        if (existing != textures_.end()) {
            // A Java LRU eviction may recreate an immutable Bitmap for the same logical tile.
            // Once the predecessor SurfaceControl buffer has latched this renderer is the sole
            // owner of the GL name, so replacing its pixels in place is identity-safe.
            texture = existing->second.texture;
            allocatedStorage = existing->second.width == width &&
                existing->second.height == height;
        } else {
            for (auto pooled = pooledTextures_.begin(); pooled != pooledTextures_.end(); ++pooled) {
                if (pooled->width != width || pooled->height != height || pooled->texture == 0) {
                    continue;
                }
                texture = pooled->texture;
                allocatedStorage = true;
                pooledTextureBytes_ -= std::min(pooledTextureBytes_, pooled->bytes);
                pooledTextures_.erase(pooled);
                ++reusedPooledTextures_;
                break;
            }
        }
        if (texture == 0) {
            glGenTextures(1, &texture);
            generatedTexture = true;
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        if (generatedTexture) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(info.stride / 4U));
        if (allocatedStorage) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(width),
                            static_cast<GLsizei>(height),
                            GL_RGBA, GL_UNSIGNED_BYTE, tilePixels);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                         static_cast<GLsizei>(width),
                         static_cast<GLsizei>(height),
                         0, GL_RGBA, GL_UNSIGNED_BYTE, tilePixels);
        }
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        AndroidBitmap_unlockPixels(env, tile.bitmap);
        if (texture == 0 || glGetError() != GL_NO_ERROR) {
            // An existing mapping is retried with the old logical identity on the next frame.
            // New or pooled names have no authoritative mapping yet and can be discarded.
            if (existing == textures_.end() && texture != 0) glDeleteTextures(1, &texture);
            return false;
        }
        if (existing != textures_.end()) {
            residentTextureBytes_ -= std::min(
                residentTextureBytes_, existing->second.bytes);
            existing->second = TextureTile{
                texture, tile.bitmapIdentity, width, height, textureBytes, useFrame};
        } else {
            textures_.emplace(tile.key, TextureTile{
                texture, tile.bitmapIdentity,
                width, height,
                textureBytes, useFrame});
        }
        residentTextureBytes_ += textureBytes;
        return true;
    }

    bool uploadPrewarmTile(JNIEnv* env, FrameTile& tile) noexcept {
        // Kotlin publishes a coalesced snapshot whenever another decoded page arrives. Most
        // snapshots therefore contain already-resident current/near-forward tiles as well as the
        // new tail. Do not issue glFlush for those no-op identities: on gfxstream hundreds of
        // redundant flushes fill the host command queue and make the following visible frame's
        // acquire-fence export block for an entire display interval.
        const auto resident = textures_.find(tile.key);
        if (resident != textures_.end() &&
            resident->second.bitmapIdentity == tile.bitmapIdentity) {
            ++skippedResidentPrewarmTiles_;
            releaseTile(env, tile);
            return false;
        }
        const std::int64_t begin = nowNanos();
        const bool uploaded = uploadTile(env, tile, ++textureUseSerial_);
        if (uploaded) {
            // The window producer is Choreographer-paced and owns visible work first. Flush the
            // single forward tile without synchronously waiting for gfxstream; glFinish here cost
            // about 20 ms per tile and directly blocked the next touch-owned frame.
            glFlush();
            pruneTexturesAfterPrewarm(tile.key.structureEpoch);
            const std::uint64_t ordinal = ++uploadedPrewarmTiles_;
            const std::int64_t elapsedUs = (nowNanos() - begin) / 1000;
            if (ordinal == 1 || ordinal % 16 == 0 || elapsedUs >= 16'000) {
                RLOGI(
                    "texture prewarm uploaded=%llu page=%d slot=%d elapsedUs=%lld resident=%zu bytes=%llu discarded=%llu",
                    static_cast<unsigned long long>(ordinal), tile.key.page, tile.key.slot,
                    static_cast<long long>(elapsedUs), textures_.size(),
                    static_cast<unsigned long long>(residentTextureBytes_),
                    static_cast<unsigned long long>(discardedPrewarmTiles_));
            }
        } else {
            const std::uint64_t ordinal = ++failedPrewarmTiles_;
            if (ordinal == 1 || ordinal % 16 == 0) {
                RLOGE("texture prewarm failed ordinal=%llu page=%d slot=%d",
                      static_cast<unsigned long long>(ordinal), tile.key.page, tile.key.slot);
            }
        }
        releaseTile(env, tile);
        return uploaded;
    }

    void recycleTextureStorage(TextureTile&& texture) noexcept {
        if (texture.texture == 0) return;
        texture.bitmapIdentity = 0;
        texture.lastUsedFrame = 0;
        if (texture.bytes <= kMaxPooledTextureBytes &&
            pooledTextures_.size() < kMaxPooledTextureCount &&
            pooledTextureBytes_ + texture.bytes <= kMaxPooledTextureBytes) {
            pooledTextureBytes_ += texture.bytes;
            pooledTextures_.push_back(std::move(texture));
            return;
        }
        glDeleteTextures(1, &texture.texture);
    }

    void eraseTexture(
            std::unordered_map<TileKey, TextureTile, TileKeyHash>::iterator entry) noexcept {
        if (entry == textures_.end()) return;
        residentTextureBytes_ -= std::min(residentTextureBytes_, entry->second.bytes);
        TextureTile storage = std::move(entry->second);
        textures_.erase(entry);
        recycleTextureStorage(std::move(storage));
        ++evictedTextures_;
    }

    void pruneTexturesToBudget(
            std::int64_t structureEpoch,
            const std::unordered_set<TileKey, TileKeyHash>& protectedKeys) noexcept {
        // A structure epoch change means no future frame can legally reference the old identity.
        for (auto entry = textures_.begin(); entry != textures_.end();) {
            if (entry->first.structureEpoch == structureEpoch) {
                ++entry;
                continue;
            }
            auto victim = entry++;
            eraseTexture(victim);
        }

        // Retire already-read GPU copies independently of the byte ceiling. Waiting until the
        // ceiling is crossed retains dozens of pages behind a one-way reader and makes the host
        // GL translator reclaim storage in eglSwapBuffers, where the delay is user-visible.
        if (lastPresentedStructureEpoch_ == structureEpoch &&
            lastPresentedMinPage_ >= 0
        ) {
            const int oldestRetainedPage =
                std::max(0, lastPresentedMinPage_ - kRetainedBackwardTexturePages);
            for (auto entry = textures_.begin(); entry != textures_.end();) {
                if (entry->first.structureEpoch != structureEpoch ||
                    entry->first.page >= oldestRetainedPage ||
                    protectedKeys.find(entry->first) != protectedKeys.end()
                ) {
                    ++entry;
                    continue;
                }
                auto victim = entry++;
                eraseTexture(victim);
            }
        }

        // Kotlin remains demand-bound and post-click. The bounded ceiling is large enough for the
        // production strict scene budget while remaining independent of work identity and test
        // state, so completed episodes are not evicted just before their first visibility.
        const std::uint64_t budget =
            fullSceneTextureBudgetEpoch_ == structureEpoch
                ? std::max(kMaxTextureBudgetBytes, fullSceneTextureBudgetBytes_)
                : kMaxTextureBudgetBytes;
        while ((residentTextureBytes_ > budget ||
                textures_.size() > kMaxResidentTextureCount)) {
            auto victim = textures_.end();
            for (auto entry = textures_.begin(); entry != textures_.end(); ++entry) {
                if (protectedKeys.find(entry->first) != protectedKeys.end()) continue;
                const bool hasForwardAnchor =
                    lastPresentedStructureEpoch_ == structureEpoch &&
                    lastPresentedMinPage_ >= 0 && lastPresentedMaxPage_ >= lastPresentedMinPage_;
                const auto evictionClass = [&](const TileKey& key) noexcept {
                    if (!hasForwardAnchor) return 0;
                    if (key.page < lastPresentedMinPage_) return 3;  // already read
                    if (key.page > lastPresentedMaxPage_) return 2; // forward runway
                    return 1;                                      // current span
                };
                const int candidateClass = evictionClass(entry->first);
                const int victimClass = victim == textures_.end()
                    ? -1
                    : evictionClass(victim->first);
                bool prefer = victim == textures_.end() || candidateClass > victimClass;
                if (!prefer && candidateClass == victimClass && hasForwardAnchor) {
                    if (candidateClass == 3) {
                        prefer = entry->first.page < victim->first.page;
                    } else if (candidateClass == 2) {
                        // Preserve the closest unread pages; the farthest decoded runway is the
                        // first expendable GPU copy and remains available as compressed bytes.
                        prefer = entry->first.page > victim->first.page;
                    }
                }
                if (!prefer && candidateClass == victimClass &&
                    (!hasForwardAnchor || entry->first.page == victim->first.page)) {
                    prefer = entry->second.lastUsedFrame < victim->second.lastUsedFrame;
                }
                if (prefer) {
                    victim = entry;
                }
            }
            // The visible frame itself may exceed the soft budget. It is never evicted before its
            // GPU commands complete; the next viewport will retire it normally.
            if (victim == textures_.end()) break;
            eraseTexture(victim);
        }
        if (evictedTextures_ != 0 &&
            (lastEvictionLogCount_ == 0 ||
             evictedTextures_ - lastEvictionLogCount_ >= 32)) {
            lastEvictionLogCount_ = evictedTextures_;
            RLOGI(
                "texture residency evicted=%llu resident=%zu bytes=%llu budget=%llu pool=%zu poolBytes=%llu reused=%llu",
                static_cast<unsigned long long>(evictedTextures_), textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                static_cast<unsigned long long>(budget), pooledTextures_.size(),
                static_cast<unsigned long long>(pooledTextureBytes_),
                static_cast<unsigned long long>(reusedPooledTextures_));
        }
    }

    void pruneTextures(const FrameCommand& frame) noexcept {
        lastPresentedTextureKeys_.clear();
        lastPresentedTextureKeys_.reserve(frame.tiles.size());
        lastPresentedMinPage_ = -1;
        lastPresentedMaxPage_ = -1;
        for (const auto& tile : frame.tiles) {
            lastPresentedTextureKeys_.insert(tile.key);
            if (lastPresentedMinPage_ < 0 || tile.key.page < lastPresentedMinPage_) {
                lastPresentedMinPage_ = tile.key.page;
            }
            if (tile.key.page > lastPresentedMaxPage_) lastPresentedMaxPage_ = tile.key.page;
        }
        lastPresentedStructureEpoch_ = frame.structureEpoch;
        pruneTexturesToBudget(frame.structureEpoch, lastPresentedTextureKeys_);
    }

    void pruneTexturesAfterPrewarm(std::int64_t structureEpoch) noexcept {
        static const std::unordered_set<TileKey, TileKeyHash> noProtectedKeys;
        pruneTexturesToBudget(
            structureEpoch,
            lastPresentedStructureEpoch_ == structureEpoch
                ? lastPresentedTextureKeys_
                : noProtectedKeys);
    }

    bool drawFrame(const FrameCommand& frame, bool windowCoordinates = false) noexcept {
        glViewport(0, 0, frame.width, frame.height);
        glDisable(GL_BLEND);
        // ReaderSurfaceView owns an RGBA SurfaceView above the HWUI fallback. Keep pixels outside
        // the exact native texture coverage transparent so a device-specific delayed or rejected
        // BufferQueue texture never turns the otherwise valid fallback frame permanently black.
        // Decoded page textures are opaque and still replace the fallback wherever they draw.
        glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program_);
        glBindVertexArray(vao_);
        glActiveTexture(GL_TEXTURE0);
        int draws = 0;
        for (const auto& tile : frame.tiles) {
            const auto texture = textures_.find(tile.key);
            if (texture == textures_.end() || texture->second.texture == 0) continue;
            const float pageBottom = tile.pageTop + tile.pageHeight;
            const float tileTop = tile.pageTop + tile.pageHeight *
                static_cast<float>(tile.sourceTop) / static_cast<float>(tile.sourceHeight);
            const float tileBottom = tile.pageTop + tile.pageHeight *
                static_cast<float>(tile.sourceBottom) / static_cast<float>(tile.sourceHeight);
            if (tileBottom <= 0.0F || tileTop >= static_cast<float>(frame.height) ||
                pageBottom <= 0.0F) continue;
            const float viewportHeight = static_cast<float>(frame.height);
            // Android bitmap row zero is the visual top. A window framebuffer uses normal GL
            // NDC (+1 at the visual top), while the retired off-screen AHB path used row-order
            // coordinates (-1 at the compositor's top row).
            const float topNdc = windowCoordinates
                ? 1.0F - 2.0F * tileTop / viewportHeight
                : ntk::present::ahbCompositorNdcY(tileTop, viewportHeight);
            const float bottomNdc = windowCoordinates
                ? 1.0F - 2.0F * tileBottom / viewportHeight
                : ntk::present::ahbCompositorNdcY(tileBottom, viewportHeight);
            glUniform2f(yBoundsUniform_, topNdc, bottomNdc);
            glBindTexture(GL_TEXTURE_2D, texture->second.texture);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            ++draws;
        }
        glBindVertexArray(0);
        glUseProgram(0);
        return draws > 0 && glGetError() == GL_NO_ERROR;
    }

    bool consumeEvents(JNIEnv* env) noexcept {
        ntk::present::FixedPresentEvent event{};
        bool consumedAny = false;
        while (backend_.drainEvent(&event)) {
            consumedAny = true;
            bool valid = false;
            switch (event.kind) {
                case ntk::present::FixedPresentEventKind::COMPOSITOR_LATCHED: {
                    ntk::present::SurfaceControlPresentBackend::ExactPresentLatchObservation observation{};
                    valid = backend_.consumeCompositorLatch(event, &observation);
                    if (valid) {
                        submissionAwaitingLatch_ = false;
                        callbackLatched(env, event);
                    }
                    break;
                }
                case ntk::present::FixedPresentEventKind::TRANSACTION_COMPLETED:
                    valid = backend_.consumeTransactionCompleted(event);
                    break;
                case ntk::present::FixedPresentEventKind::PREVIOUS_BUFFER_RELEASED:
                    valid = backend_.consumePreviousBufferReleased(event);
                    break;
                case ntk::present::FixedPresentEventKind::ACQUIRE_FENCE_SIGNALED:
                    valid = backend_.consumeAcquireFenceSignaled(event);
                    break;
                case ntk::present::FixedPresentEventKind::TEARDOWN_COMPLETED:
                    valid = true;
                    break;
                case ntk::present::FixedPresentEventKind::INVALID_CALLBACK:
                    valid = false;
                    break;
            }
            if (!valid) {
                const auto snapshot = backend_.conservationSnapshot();
                RLOGE(
                    "invalid event kind=%u frame=%llu tx=%llu slot=%llu generation=%llu eventSeq=%llu latchNs=%lld observedNs=%lld commitCallbacks=%u completeCallbacks=%u acquireSerial=%llu acquireSignalNs=%lld proofCloses=%u releasedSlot=%llu releasedGeneration=%llu releasedRef=%llu logical=%u maxLogical=%u outstanding=%u commitPending=%u completePending=%u releaseDepth=%u acquireDepth=%u invariantFatal=%llu",
                    static_cast<unsigned>(event.kind),
                    static_cast<unsigned long long>(event.identity.ntkFrameId),
                    static_cast<unsigned long long>(event.identity.transactionSerial),
                    static_cast<unsigned long long>(event.identity.bufferSlot),
                    static_cast<unsigned long long>(event.identity.bufferGeneration),
                    static_cast<unsigned long long>(event.eventSequence),
                    static_cast<long long>(event.latchNanos),
                    static_cast<long long>(event.callbackObservedNanos),
                    event.onCommitCallbackCount, event.onCompleteCallbackCount,
                    static_cast<unsigned long long>(event.acquireFenceSerial),
                    static_cast<long long>(event.acquireFenceSignalNanos),
                    event.proofFdCloseCount,
                    static_cast<unsigned long long>(event.releasedBufferSlot),
                    static_cast<unsigned long long>(event.releasedBufferGeneration),
                    static_cast<unsigned long long>(event.releasedAppliedBufferRefSerial),
                    snapshot.logicalUnlatchedNow, snapshot.maxLogicalUnlatched,
                    snapshot.outstandingSubmissionCount, snapshot.commitProofPendingNow,
                    snapshot.completeProofPendingNow, snapshot.previousReleaseRecordDepth,
                    snapshot.acquireFenceRecordDepth,
                    static_cast<unsigned long long>(snapshot.backendInvariantFatalCount));
                fatal(env, "surface-control-event-invalid");
                return false;
            }
        }
        return consumedAny || !backend_.eventOverflowed();
    }

    bool prepareBackend(PrepareCommand command) noexcept {
        if (command.width <= 0 || command.height <= 0) return false;
        if (backendAttached_) {
            return width_ == command.width && height_ == command.height;
        }
        preparedWidth_ = command.width;
        preparedHeight_ = command.height;
        RLOGI("cold BufferQueue geometry staged size=%dx%d", command.width, command.height);
        return true;
    }

    bool drainBackendEvidence(JNIEnv* env) noexcept {
        // SurfaceControl callbacks and acquire/release fences are the ownership proof for every
        // submitted HardwareBuffer. Neither normal detach nor parent replacement may destroy the
        // backend until that proof has been consumed on the renderer thread.
        while (true) {
            if (!consumeEvents(env) && failed_.load(std::memory_order_acquire)) return false;
            const auto snapshot = backend_.conservationSnapshot();
            if (snapshot.outstandingSubmissionCount == 0 &&
                snapshot.previousReleaseRecordDepth == 0 &&
                snapshot.acquireFenceRecordDepth == 0 &&
                snapshot.appOwnedAcquireFdCount == 0 && !backend_.hasPendingEvent()) {
                return true;
            }
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait_for(lock, std::chrono::milliseconds(4));
        }
    }

    void resetBackendAttachmentState() noexcept {
        backendAttached_ = false;
        submissionAwaitingLatch_ = false;
        lastAppliedFrameWidth_ = 0;
        lastAppliedFrameHeight_ = 0;
        lastAppliedFrameEpoch_ = 0;
        lastAppliedFrameTiles_.clear();
        surfaceEpoch_ = 0;
        preparedWidth_ = 0;
        preparedHeight_ = 0;
    }

    bool retireBackendForParentReplacement(JNIEnv* env) noexcept {
        if (!backendAttached_) return true;
        if (windowSurface_ != EGL_NO_SURFACE) {
            if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
                pbuffer_ == EGL_NO_SURFACE ||
                eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE ||
                eglDestroySurface(display_, windowSurface_) != EGL_TRUE) {
                return false;
            }
            windowSurface_ = EGL_NO_SURFACE;
            if (nativeWindow_ != nullptr) {
                ANativeWindow_release(nativeWindow_);
                nativeWindow_ = nullptr;
            }
            resetBackendAttachmentState();
            RLOGI("rolling BufferQueue retired after parent replacement");
            return true;
        }
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            !drainBackendEvidence(env)) {
            return false;
        }
        // A new SurfaceView attach means the old parent may already have left SurfaceFlinger.
        // Reparenting its child to null can then produce no completion callback. Once all exact
        // transaction/fence evidence is drained, retire the app-owned chain head locally and let
        // destroy() release the obsolete child and parent handles.
        if (!backend_.retireAfterParentLifecycleEvidenceDrained() || !backend_.destroy()) {
            return false;
        }
        resetBackendAttachmentState();
        RLOGI("pipelined SurfaceControl retired after parent replacement evidence drain");
        return true;
    }

    bool attachWasSuperseded() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return stopped_.load(std::memory_order_acquire) || detachPending_ || attachPending_;
    }

    bool attachBackend(JNIEnv* env, AttachCommand command) noexcept {
        if (command.window == nullptr) return false;
        if (backendAttached_ && !retireBackendForParentReplacement(env)) {
            ANativeWindow_release(command.window);
            return false;
        }
        if (attachWasSuperseded()) {
            ANativeWindow_release(command.window);
            RLOGI("BufferQueue attach cancelled by newer lifecycle command before allocation");
            return true;
        }
        if (eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE) {
            ANativeWindow_release(command.window);
            return false;
        }
        const std::int64_t begin = nowNanos();
        if (ANativeWindow_setBuffersGeometry(
                command.window, command.width, command.height,
                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) != 0) {
            ANativeWindow_release(command.window);
            return false;
        }
        constexpr EGLint surfaceAttributes[] = {
            EGL_RENDER_BUFFER, EGL_BACK_BUFFER,
            EGL_NONE,
        };
        EGLSurface window = eglCreateWindowSurface(
            display_, config_, command.window, surfaceAttributes);
        if (window == EGL_NO_SURFACE ||
            eglMakeCurrent(display_, window, window, context_) != EGL_TRUE) {
            if (window != EGL_NO_SURFACE) eglDestroySurface(display_, window);
            ANativeWindow_release(command.window);
            if (attachWasSuperseded()) {
                RLOGI("BufferQueue attach failure retired as superseded lifecycle work");
                return true;
            }
            return false;
        }
        EGLint minimumSwapInterval = 1;
        EGLint maximumSwapInterval = 1;
        (void)eglGetConfigAttrib(
            display_, config_, EGL_MIN_SWAP_INTERVAL, &minimumSwapInterval);
        (void)eglGetConfigAttrib(
            display_, config_, EGL_MAX_SWAP_INTERVAL, &maximumSwapInterval);
        // Choreographer is the sole 60 Hz pacing authority. Keeping EGL itself at interval one
        // made gfxstream queueBuffer block 23-30 ms per physical frame and reduced the measured
        // SurfaceFlinger cadence to 37-39 fps. Interval zero does not synthesize or interpolate a
        // viewport: Java still submits at most the latest exact MotionEvent coordinate once per
        // Choreographer callback.
        const EGLBoolean asynchronous = eglSwapInterval(display_, 0);
        const int nativeAsyncResult = setNativeWindowSwapInterval(command.window, 0);
        const auto& bufferControls = nativeWindowBufferControls();
        const float requestedFrameRate = command.refreshPeriodNanos > 0
            ? static_cast<float>(1'000'000'000.0 /
                static_cast<double>(command.refreshPeriodNanos))
            : 60.0F;
        // This is a cadence hint, not another clock: Choreographer still owns admission while the
        // vote keeps the Surface on the display's seamless 60 Hz mode.
        const int frameRateResult = bufferControls.setFrameRate != nullptr
            ? bufferControls.setFrameRate(command.window, requestedFrameRate, 0)
            : -3;
        // Shared-buffer + auto-refresh exposes the same producer buffer while GLES is updating
        // it. Several physical Samsung compositors scan that buffer out concurrently, which
        // presents old/new image rows as a cascade of horizontal tears during a fling. Keep the
        // producer, and publish only completed buffers through an ordinary multi-buffer queue so
        // SurfaceFlinger switches the whole frame atomically.
        const int sharedBufferOffResult = bufferControls.setSharedBufferMode != nullptr
            ? bufferControls.setSharedBufferMode(command.window, false)
            : -3;
        const int autoRefreshOffResult = bufferControls.setAutoRefresh != nullptr
            ? bufferControls.setAutoRefresh(command.window, false)
            : -3;
        // Front/queued/producer plus one spare is sufficient because Java and native mailboxes
        // remain depth one and Choreographer never admits a free-running producer burst.
        const int bufferCountResult = bufferControls.setBufferCount != nullptr
            ? bufferControls.setBufferCount(command.window, 4)
            : -3;
        // Allocate the finite queue before physical scrolling begins. Leaving this lazy made
        // host-GPU eglSwapBuffers repeatedly spend 55-70 ms growing/acquiring the queue during
        // motion (roughly 12 presented fps). ReaderSurfaceView now admits this attachment only
        // after a clean real-image HWUI frame has committed and released current-episode work, so
        // this one-time producer setup can no longer block or delay the first actual image proof.
        if (bufferCountResult == 0 &&
            bufferControls.tryAllocateBuffers != nullptr) {
            bufferControls.tryAllocateBuffers(command.window);
        }
        (void)eglSurfaceAttrib(
            display_, window, EGL_SWAP_BEHAVIOR,
            EGL_BUFFER_DESTROYED);
        const std::int64_t end = nowNanos();
        windowSurface_ = window;
        nativeWindow_ = command.window;
        backendAttached_ = true;
        if (attachWasSuperseded()) {
            if (!retireBackendForParentReplacement(env)) return false;
            RLOGI("BufferQueue attach retired after concurrent lifecycle replacement");
            return true;
        }
        submissionAwaitingLatch_ = false;
        lastAppliedFrameWidth_ = 0;
        lastAppliedFrameHeight_ = 0;
        lastAppliedFrameEpoch_ = 0;
        lastAppliedFrameTiles_.clear();
        frameSequence_ = 0;
        submittedFrames_ = 0;
        surfaceEpoch_ = command.epoch;
        width_ = command.width;
        height_ = command.height;
        refreshPeriodNanos_ = command.refreshPeriodNanos > 0
            ? command.refreshPeriodNanos : kDefaultRefreshPeriodNanos;
        RLOGI("cold async SurfaceView BufferQueue attached epoch=%llu size=%dx%d refreshNs=%lld prepared=%d eglSwap0=%d nativeSwap0=%d frameRate=%.3f frameRateResult=%d sharedOff=%d autoRefreshOff=%d bufferCount4=%d intervalRange=%d..%d durationMs=%.3f",
              static_cast<unsigned long long>(surfaceEpoch_), width_, height_,
              static_cast<long long>(refreshPeriodNanos_),
              preparedWidth_ == width_ && preparedHeight_ == height_ ? 1 : 0,
              asynchronous == EGL_TRUE ? 1 : 0,
              nativeAsyncResult,
              static_cast<double>(requestedFrameRate),
              frameRateResult,
              sharedBufferOffResult,
              autoRefreshOffResult,
              bufferCountResult,
              minimumSwapInterval, maximumSwapInterval,
              static_cast<double>(end - begin) / 1'000'000.0);
        return true;
    }

    bool detachBackend(JNIEnv* env) noexcept {
        if (!backendAttached_) return true;
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) return false;
        if (windowSurface_ != EGL_NO_SURFACE) {
            if (pbuffer_ == EGL_NO_SURFACE ||
                eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE ||
                eglDestroySurface(display_, windowSurface_) != EGL_TRUE) {
                return false;
            }
            windowSurface_ = EGL_NO_SURFACE;
            if (nativeWindow_ != nullptr) {
                ANativeWindow_release(nativeWindow_);
                nativeWindow_ = nullptr;
            }
            resetBackendAttachmentState();
            RLOGI("rolling BufferQueue detached after submitted swaps drained");
            return true;
        }
        // Drain exact OnCommit/OnComplete/acquire/release evidence before unparenting the layer.
        if (!drainBackendEvidence(env)) return false;
        if (!backend_.detachAfterEvidenceDrained() || !backend_.destroy()) return false;
        resetBackendAttachmentState();
        RLOGI("pipelined SurfaceControl detached after exact producer drain");
        return true;
    }

    PresentResult presentWindowFrame(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        if (failureStage != nullptr) *failureStage = "window-entry";
        if (!backendAttached_ || windowSurface_ == EGL_NO_SURFACE ||
            frame.width != width_ || frame.height != height_) {
            if (failureStage != nullptr) *failureStage = "window-surface-or-size";
            return PresentResult::FAILED;
        }
        const std::int64_t begin = nowNanos();
        if (eglGetCurrentSurface(EGL_DRAW) != windowSurface_ &&
            eglMakeCurrent(display_, windowSurface_, windowSurface_, context_) != EGL_TRUE) {
            if (failureStage != nullptr) *failureStage = "window-make-current";
            return PresentResult::FAILED;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        const std::uint64_t textureUseFrame = ++textureUseSerial_;
        const std::int64_t uploadBegin = nowNanos();
        for (const auto& tile : frame.tiles) {
            if (!uploadTile(env, tile, textureUseFrame)) {
                if (failureStage != nullptr) *failureStage = "window-texture-upload";
                return PresentResult::FAILED;
            }
        }
        const std::int64_t uploadEnd = nowNanos();
        const std::int64_t drawBegin = nowNanos();
        if (!drawFrame(frame, true)) {
            if (failureStage != nullptr) *failureStage = "window-draw";
            return PresentResult::FAILED;
        }
        const std::int64_t drawEnd = nowNanos();
        const std::int64_t presentBegin = nowNanos();
        // The display-paced interval is a Surface attachment property. Re-applying
        // ANativeWindow_setSwapInterval on every frame can itself enter BufferQueue
        // synchronization, so keep the established mode for the surface's lifetime and submit
        // only the complete buffer here.
        constexpr int nativeSwapInterval = 0;
        if (eglSwapBuffers(display_, windowSurface_) != EGL_TRUE) {
            if (failureStage != nullptr) *failureStage = "window-swap";
            return PresentResult::FAILED;
        }
        const std::int64_t presentEnd = nowNanos();
        ++frameSequence_;
        ++submittedFrames_;
        rememberAppliedFrame(frame);
        pruneTextures(frame);
        callbackWindowFramePresented(env, frame.token, presentEnd, 2);
        const std::int64_t totalUs = (presentEnd - begin) / 1000;
        const std::int64_t uploadUs = (uploadEnd - uploadBegin) / 1000;
        const std::int64_t drawUs = (drawEnd - drawBegin) / 1000;
        const std::int64_t presentUs = (presentEnd - presentBegin) / 1000;
        const bool slowLogDue = totalUs >= 16'000 &&
            (lastSlowPresentLogNanos_ == 0 ||
             presentEnd - lastSlowPresentLogNanos_ >= 1'000'000'000LL);
        if (submittedFrames_ == 1 || submittedFrames_ % 90 == 0 || slowLogDue) {
            if (slowLogDue) lastSlowPresentLogNanos_ = presentEnd;
            RLOGI(
                "window present submitted=%llu token=%llu totalUs=%lld uploadUs=%lld drawUs=%lld presentUs=%lld kind=%d nativeSwapInterval=%d textures=%zu bytes=%llu pool=%zu poolBytes=%llu reused=%llu prewarmSkipped=%llu",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(frame.token),
                static_cast<long long>(totalUs),
                static_cast<long long>(uploadUs),
                static_cast<long long>(drawUs),
                static_cast<long long>(presentUs), 2, nativeSwapInterval,
                textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                pooledTextures_.size(),
                static_cast<unsigned long long>(pooledTextureBytes_),
                static_cast<unsigned long long>(reusedPooledTextures_),
                static_cast<unsigned long long>(skippedResidentPrewarmTiles_));
        }
        if (failureStage != nullptr) *failureStage = "window-queued";
        return PresentResult::APPLIED;
    }

    PresentResult applyPreparedDirectFrame(
            PreparedDirectFrame& candidate,
            const char** failureStage) noexcept {
        const auto readiness = backend_.queryDirectApplyReadiness(candidate.submission);
        if (readiness ==
                ntk::present::SurfaceControlPresentBackend::ApplyReadiness::
                    WAITING_PRIOR_LATCH) {
            if (failureStage != nullptr) *failureStage = "prior-latch";
            return PresentResult::PREPARED_WAITING;
        }
        if (readiness !=
                ntk::present::SurfaceControlPresentBackend::ApplyReadiness::READY) {
            const bool aborted = backend_.abortPreparedBufferTransaction(candidate.submission);
            if (failureStage != nullptr) {
                *failureStage = aborted ? "readiness-fatal" : "readiness-fatal-abort";
            }
            return PresentResult::FAILED;
        }

        ntk::present::SurfaceControlPresentBackend::SubmissionReceipt receipt{};
        const std::int64_t applyBegin = nowNanos();
        const auto disposition = backend_.applyPreparedBufferTransactionDirect(
            candidate.submission, &receipt);
        if (disposition !=
                ntk::present::SurfaceControlPresentBackend::ApplyDisposition::APPLIED ||
            !receipt.submitted) {
            if (failureStage != nullptr) *failureStage = "transaction-apply";
            return PresentResult::FAILED;
        }
        const std::int64_t applyEnd = nowNanos();
        ++submittedFrames_;
        submissionAwaitingLatch_ = true;
        rememberAppliedFrame(candidate.frame);
        pruneTextures(candidate.frame);
        const std::int64_t end = nowNanos();
        const std::int64_t totalUs = (end - candidate.beginNanos) / 1000;
        const std::int64_t bindUs =
            (candidate.bindEndNanos - candidate.beginNanos) / 1000;
        const std::int64_t uploadUs =
            (candidate.uploadEndNanos - candidate.uploadBeginNanos) / 1000;
        const std::int64_t drawUs =
            (candidate.renderEndNanos - candidate.renderBeginNanos) / 1000;
        const std::int64_t fenceUs =
            (candidate.fenceEndNanos - candidate.fenceBeginNanos) / 1000;
        const std::int64_t prepareUs =
            (candidate.prepareEndNanos - candidate.prepareBeginNanos) / 1000;
        const std::int64_t waitUs = std::max<std::int64_t>(
            0, (applyBegin - candidate.prepareEndNanos) / 1000);
        const std::int64_t applyUs = (applyEnd - applyBegin) / 1000;
        const std::int64_t finalizeUs = (end - applyEnd) / 1000;
        const bool slowLogDue = totalUs >= 16'000 &&
            (lastSlowPresentLogNanos_ == 0 ||
             end - lastSlowPresentLogNanos_ >= 1'000'000'000LL);
        if (submittedFrames_ == 1 || submittedFrames_ % 90 == 0 || slowLogDue) {
            if (slowLogDue) lastSlowPresentLogNanos_ = end;
            RLOGI(
                "present timing submitted=%llu token=%llu totalUs=%lld bindUs=%lld uploadUs=%lld drawUs=%lld fenceUs=%lld prepareUs=%lld waitUs=%lld applyUs=%lld finalizeUs=%lld textures=%zu bytes=%llu pool=%zu poolBytes=%llu reused=%llu prewarmSkipped=%llu",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(candidate.frame.token),
                static_cast<long long>(totalUs),
                static_cast<long long>(bindUs),
                static_cast<long long>(uploadUs),
                static_cast<long long>(drawUs),
                static_cast<long long>(fenceUs),
                static_cast<long long>(prepareUs),
                static_cast<long long>(waitUs),
                static_cast<long long>(applyUs),
                static_cast<long long>(finalizeUs), textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                pooledTextures_.size(),
                static_cast<unsigned long long>(pooledTextureBytes_),
                static_cast<unsigned long long>(reusedPooledTextures_),
                static_cast<unsigned long long>(skippedResidentPrewarmTiles_));
        }
        if (failureStage != nullptr) *failureStage = "applied";
        return PresentResult::APPLIED;
    }

    PresentResult presentFrame(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        if (windowSurface_ != EGL_NO_SURFACE) {
            return presentWindowFrame(env, frame, failureStage);
        }
        if (failureStage != nullptr) *failureStage = "entry";
        if (!backendAttached_ || frame.width != width_ || frame.height != height_) {
            if (failureStage != nullptr) *failureStage = "surface-or-size";
            return PresentResult::FAILED;
        }
        if (!backend_.hasDirectSubmissionCapacity() ||
            !backend_.pool().hasFreeRenderTarget()) {
            if (failureStage != nullptr) *failureStage = "capacity";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }

        PreparedDirectFrame candidate{};
        candidate.beginNanos = nowNanos();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        auto* target = backend_.acquireRenderTarget();
        if (target == nullptr) {
            if (failureStage != nullptr) *failureStage = "target-acquire";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (!backend_.bindRenderTarget(*target)) {
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            if (failureStage != nullptr) *failureStage = "target-bind";
            return PresentResult::FAILED;
        }
        candidate.bindEndNanos = nowNanos();
        const std::uint64_t textureUseFrame = ++textureUseSerial_;
        candidate.uploadBeginNanos = nowNanos();
        for (const auto& tile : frame.tiles) {
            if (!uploadTile(env, tile, textureUseFrame)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                if (failureStage != nullptr) *failureStage = "texture-upload";
                return PresentResult::FAILED;
            }
        }
        candidate.uploadEndNanos = nowNanos();
        candidate.renderBeginNanos = nowNanos();
        if (!drawFrame(frame)) {
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            if (failureStage != nullptr) *failureStage = "draw";
            return PresentResult::FAILED;
        }
        candidate.renderEndNanos = nowNanos();
        ntk::present::GpuSubmissionProof gpuProof{};
        candidate.fenceBeginNanos = nowNanos();
        if (!backend_.exportAcquireFence(
                *target, candidate.renderBeginNanos,
                candidate.renderEndNanos, &gpuProof)) {
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            if (failureStage != nullptr) *failureStage = "acquire-fence";
            return PresentResult::FAILED;
        }
        candidate.fenceEndNanos = nowNanos();

        ntk::present::SurfaceControlPresentBackend::FixedPreparedFrameIdentityBase identity{};
        identity.engineGeneration = 1;
        identity.surfaceEpoch = surfaceEpoch_;
        identity.authorityGeneration = 1;
        identity.authority = 1;
        identity.workGeneration = frame.token;
        identity.ntkFrameId = frame.token;
        identity.frameSequence = ++frameSequence_;
        identity.capsuleSequence = frame.token;
        SwappyFixedExternalTransportReady unusedProof{};
        const bool first = submittedFrames_ == 0;
        candidate.prepareBeginNanos = nowNanos();
        if (!backend_.prepareBufferTransaction(
                identity, *target, first, profile_,
                &candidate.submission, &unusedProof)) {
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            if (failureStage != nullptr) *failureStage = "transaction-prepare";
            return PresentResult::FAILED;
        }
        candidate.prepareEndNanos = nowNanos();
        candidate.frame = std::move(frame);
        candidate.occupied = true;

        const PresentResult result = applyPreparedDirectFrame(candidate, failureStage);
        if (result == PresentResult::PREPARED_WAITING) {
            const bool aborted = backend_.abortPreparedBufferTransaction(
                candidate.submission);
            frame = std::move(candidate.frame);
            if (!aborted) {
                if (failureStage != nullptr) *failureStage = "prior-latch-abort";
                return PresentResult::FAILED;
            }
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        frame = std::move(candidate.frame);
        return result;
    }

    bool servicePreparedDirectFrame(JNIEnv* env, bool* applied) noexcept {
        if (applied != nullptr) *applied = false;
        if (!pendingDirectFrame_.occupied) return true;
        const char* failureStage = nullptr;
        const PresentResult result = applyPreparedDirectFrame(
            pendingDirectFrame_, &failureStage);
        if (result == PresentResult::PREPARED_WAITING) return true;

        FrameCommand completed = std::move(pendingDirectFrame_.frame);
        pendingDirectFrame_ = {};
        if (result != PresentResult::APPLIED) {
            logPresentFailure(failureStage, completed);
            callbackDropped(env, completed.token);
            releaseFrame(env, completed);
            fatal(env, "surface-control-prepared-apply");
            return false;
        }
        releaseFrame(env, completed);
        if (applied != nullptr) *applied = true;
        return true;
    }

    bool abortPreparedDirectFrame(JNIEnv* env, const char* reason) noexcept {
        if (!pendingDirectFrame_.occupied) return true;
        const bool aborted = backend_.abortPreparedBufferTransaction(
            pendingDirectFrame_.submission);
        FrameCommand abandoned = std::move(pendingDirectFrame_.frame);
        pendingDirectFrame_ = {};
        callbackDropped(env, abandoned.token);
        releaseFrame(env, abandoned);
        if (!aborted) {
            RLOGE("prepared successor abort failed reason=%s",
                  reason != nullptr ? reason : "unknown");
        }
        return aborted;
    }

    void logPresentFailure(const char* stage, const FrameCommand& frame) noexcept {
        const std::uint64_t ordinal = ++presentationFailures_;
        if (ordinal != 1 && ordinal % 30 != 0) return;
        const auto snapshot = backend_.conservationSnapshot();
        RLOGE(
            "present failed ordinal=%llu stage=%s token=%llu waitingLatch=%d outstanding=%u commitPending=%u completePending=%u releaseWait=%u free=%u invariantFatal=%llu textures=%zu bytes=%llu",
            static_cast<unsigned long long>(ordinal), stage != nullptr ? stage : "unknown",
            static_cast<unsigned long long>(frame.token), submissionAwaitingLatch_ ? 1 : 0,
            snapshot.outstandingSubmissionCount, snapshot.commitProofPendingNow,
            snapshot.completeProofPendingNow, snapshot.releaseWaitCount,
            snapshot.freeReusableCount,
            static_cast<unsigned long long>(snapshot.backendInvariantFatalCount),
            textures_.size(), static_cast<unsigned long long>(residentTextureBytes_));
    }

    void run() noexcept {
        requestUrgentDisplayPriority();
        JNIEnv* env = attachEnv();
        if (env == nullptr || !initializeEgl()) {
            fatal(env, "cold-egl-initialize");
            return;
        }
        while (!stopped_.load(std::memory_order_acquire)) {
            if (!consumeEvents(env) && failed_.load(std::memory_order_acquire)) break;
            bool lifecycleCommandPending = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                lifecycleCommandPending = detachPending_ || attachPending_;
            }
            if (pendingDirectFrame_.occupied) {
                if (lifecycleCommandPending) {
                    if (!abortPreparedDirectFrame(env, "surface-lifecycle")) {
                        fatal(env, "surface-prepared-abort");
                        break;
                    }
                } else {
                    bool appliedPrepared = false;
                    if (!servicePreparedDirectFrame(env, &appliedPrepared)) break;
                    // Start preparing the newest queued downward viewport immediately after the
                    // successor apply. This keeps the GPU lane one interval ahead without ever
                    // applying two unlatched SurfaceControl buffers.
                    if (appliedPrepared) continue;
                }
            }
            PrepareCommand prepareCommand{};
            bool doPrepare = false;
            AttachCommand attachCommand{};
            bool doAttach = false;
            bool doDetach = false;
            FrameCommand frame{};
            bool hasFrame = false;
            FrameTile prewarmTile{};
            bool hasPrewarmTile = false;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                condition_.wait_for(lock, std::chrono::milliseconds(16), [&] {
                    const bool prewarmPacingReady =
                        nextPrewarmUploadNanos_ <= 0 || nowNanos() >= nextPrewarmUploadNanos_;
                    const bool canPresentQueuedFrame = !frames_.empty() && backendAttached_ &&
                        (windowSurface_ != EGL_NO_SURFACE ||
                         (backend_.hasDirectSubmissionCapacity() &&
                           backend_.pool().hasFreeRenderTarget()));
                    // The first physical image always owns the renderer. Decoded runway pixels
                    // may be queued before attachment, but they cannot consume the EGL lane until
                    // at least one real frame has been submitted. This keeps forward preparation
                    // from extending cold first-image latency.
                    const bool canUploadPrewarm = backendAttached_ && submittedFrames_ > 0 &&
                        canUploadNextPrewarmLocked() &&
                        prewarmPacingReady &&
                        !preparePending_ && !attachPending_ && !detachPending_ &&
                        (frames_.empty() || matchesLastAppliedFrame(frames_.front()));
                    return stopped_.load(std::memory_order_acquire) || preparePending_ ||
                        attachPending_ || detachPending_ || canPresentQueuedFrame ||
                        canUploadPrewarm || backend_.hasPendingEvent();
                });
                if (stopped_.load(std::memory_order_acquire)) break;
                if (detachPending_) {
                    doDetach = true;
                    detachPending_ = false;
                }
                if (preparePending_) {
                    doPrepare = true;
                    prepareCommand = pendingPrepare_;
                    pendingPrepare_ = {};
                    preparePending_ = false;
                }
                if (attachPending_) {
                    doAttach = true;
                    attachCommand = pendingAttach_;
                    pendingAttach_ = {};
                    attachPending_ = false;
                }
                const bool prewarmPacingReady =
                    nextPrewarmUploadNanos_ <= 0 || nowNanos() >= nextPrewarmUploadNanos_;
                const bool preferPrewarm = !doDetach && !doPrepare && !doAttach &&
                    backendAttached_ && submittedFrames_ > 0 &&
                    canUploadNextPrewarmLocked() && prewarmPacingReady &&
                    (frames_.empty() || matchesLastAppliedFrame(frames_.front()));
                if (!preferPrewarm && !frames_.empty()) {
                    if (backendAttached_ &&
                        (windowSurface_ != EGL_NO_SURFACE ||
                        (backend_.hasDirectSubmissionCapacity() &&
                          backend_.pool().hasFreeRenderTarget()))) {
                        frame = std::move(frames_.front());
                        frames_.pop_front();
                        hasFrame = true;
                    }
                }
                if (preferPrewarm && !hasFrame) {
                    prewarmTile = prewarmTiles_.front();
                    prewarmTiles_.front().bitmap = nullptr;
                    prewarmTiles_.pop_front();
                    hasPrewarmTile = true;
                }
            }
            if (doDetach && !detachBackend(env)) {
                fatal(env, "surface-detach-drain");
                break;
            }
            if (doPrepare && !prepareBackend(prepareCommand)) {
                fatal(env, "surface-target-prepare");
                break;
            }
            if (doAttach && !attachBackend(env, attachCommand)) {
                fatal(env, "surface-attach");
                break;
            }
            {
                std::lock_guard<std::mutex> lock(mutex_);
                surfaceAttached_ = backendAttached_;
            }
            if (hasFrame) {
                discardQueuedPrewarmOutsideEpoch(env, frame.structureEpoch);
                const char* failureStage = nullptr;
                const PresentResult result = presentFrame(env, frame, &failureStage);
                if (result == PresentResult::TRANSIENT_BACKPRESSURE) {
                    FrameCommand superseded;
                    bool retry = false;
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        if (frames_.empty() && backendAttached_ &&
                            !stopped_.load(std::memory_order_acquire)) {
                            frames_.push_front(std::move(frame));
                            retry = true;
                        } else {
                            superseded = std::move(frame);
                        }
                    }
                    if (!retry) {
                        callbackDropped(env, superseded.token);
                        releaseFrame(env, superseded);
                        ++supersededFrames_;
                    }
                } else if (result != PresentResult::PREPARED_WAITING) {
                    if (result == PresentResult::FAILED) {
                        logPresentFailure(failureStage, frame);
                        callbackDropped(env, frame.token);
                    }
                    releaseFrame(env, frame);
                }
            } else if (hasPrewarmTile) {
                const bool issuedUpload = uploadPrewarmTile(env, prewarmTile);
                // Only an actual GL upload consumes a display-period slot. Resident identities at
                // the head of a coalesced full-scene snapshot are CPU-only queue maintenance; a
                // period of pacing for each no-op previously kept the queue permanently behind the
                // forward viewport and forced every new page onto its first visible frame.
                nextPrewarmUploadNanos_ = issuedUpload
                    ? nowNanos() + std::max<std::int64_t>(
                        1'000'000,
                        refreshPeriodNanos_ > 0
                            ? refreshPeriodNanos_
                            : kDefaultRefreshPeriodNanos)
                    : 0;
            }
        }

        std::deque<FrameCommand> remaining;
        std::deque<FrameTile> remainingPrewarm;
        AttachCommand pending{};
        {
            std::lock_guard<std::mutex> lock(mutex_);
            remaining.swap(frames_);
            remainingPrewarm.swap(prewarmTiles_);
            pending = pendingAttach_;
            pendingAttach_ = {};
            pendingPrepare_ = {};
            preparePending_ = false;
            surfaceAttached_ = false;
        }
        for (auto& frame : remaining) {
            callbackDropped(env, frame.token);
            releaseFrame(env, frame);
        }
        for (auto& tile : remainingPrewarm) releaseTile(env, tile);
        if (pending.window != nullptr) ANativeWindow_release(pending.window);
        if (!abortPreparedDirectFrame(env, "renderer-stop")) {
            RLOGE("terminal prepared successor abort failed");
        }
        if (backendAttached_ && !detachBackend(env)) {
            RLOGE("terminal backend drain failed");
        }
        if (backend_.prepared() && !backend_.destroy()) {
            RLOGE("terminal prepared backend destroy failed");
        }
        destroyEgl();
    }

    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID latchedMethod_ = nullptr;
    jmethodID droppedMethod_ = nullptr;
    jmethodID fatalMethod_ = nullptr;
    std::thread thread_;
    std::mutex mutex_;
    std::condition_variable condition_;
    std::deque<FrameCommand> frames_;
    std::deque<FrameTile> prewarmTiles_;
    std::int64_t queuedPrewarmEpoch_ = 0;
    std::int64_t sealedFullScenePrewarmEpoch_ = 0;
    std::int64_t fullSceneTextureBudgetEpoch_ = 0;
    std::uint64_t fullSceneTextureBudgetBytes_ = 0;
    PrepareCommand pendingPrepare_{};
    bool preparePending_ = false;
    AttachCommand pendingAttach_{};
    bool attachPending_ = false;
    bool detachPending_ = false;
    std::uint64_t detachEpoch_ = 0;
    bool surfaceAttached_ = false;
    std::atomic<bool> stopped_{false};
    std::atomic<bool> destroyed_{false};
    std::atomic<bool> failed_{false};

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface windowSurface_ = EGL_NO_SURFACE;
    ANativeWindow* nativeWindow_ = nullptr;
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLint yBoundsUniform_ = -1;
    std::unordered_map<TileKey, TextureTile, TileKeyHash> textures_;
    std::vector<TextureTile> pooledTextures_;
    std::uint64_t residentTextureBytes_ = 0;
    std::uint64_t pooledTextureBytes_ = 0;
    std::uint64_t evictedTextures_ = 0;
    std::uint64_t lastEvictionLogCount_ = 0;
    std::uint64_t reusedPooledTextures_ = 0;
    std::uint64_t textureUseSerial_ = 0;
    std::int64_t lastPresentedStructureEpoch_ = 0;
    int lastPresentedMinPage_ = -1;
    int lastPresentedMaxPage_ = -1;
    std::unordered_set<TileKey, TileKeyHash> lastPresentedTextureKeys_;
    int lastAppliedFrameWidth_ = 0;
    int lastAppliedFrameHeight_ = 0;
    std::int64_t lastAppliedFrameEpoch_ = 0;
    std::vector<AppliedFrameTileSignature> lastAppliedFrameTiles_;
    std::uint64_t uploadedPrewarmTiles_ = 0;
    std::uint64_t failedPrewarmTiles_ = 0;
    std::uint64_t discardedPrewarmTiles_ = 0;
    std::uint64_t skippedResidentPrewarmTiles_ = 0;
    std::int64_t nextPrewarmUploadNanos_ = 0;
    bool prewarmPaused_ = false;

    ntk::present::SurfaceControlPresentBackend backend_{};
    ntk::present::FixedTransportProfile profile_{};
    PreparedDirectFrame pendingDirectFrame_{};
    bool backendAttached_ = false;
    int preparedWidth_ = 0;
    int preparedHeight_ = 0;
    std::uint64_t surfaceEpoch_ = 0;
    int width_ = 0;
    int height_ = 0;
    std::int64_t refreshPeriodNanos_ = kDefaultRefreshPeriodNanos;
    std::uint64_t frameSequence_ = 0;
    std::uint64_t submittedFrames_ = 0;
    bool submissionAwaitingLatch_ = false;
    std::uint64_t presentationFailures_ = 0;
    std::int64_t lastSlowPresentLogNanos_ = 0;
    std::atomic<std::uint64_t> acceptedFrames_{0};
    std::atomic<std::uint64_t> supersededFrames_{0};
    std::atomic<std::uint64_t> droppedFrames_{0};
    std::atomic<std::uint64_t> latchedFrames_{0};
};

RollingRenderer* renderer(jlong handle) noexcept {
    return reinterpret_cast<RollingRenderer*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCreate(
        JNIEnv* env, jobject, jobject callback) {
    auto* value = new (std::nothrow) RollingRenderer(env, callback);
    if (value == nullptr || !value->valid()) {
        delete value;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(value));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativePrepare(
        JNIEnv*, jobject, jlong handle, jint width, jint height) {
    RollingRenderer* value = renderer(handle);
    return value != nullptr && value->prepare(width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeAttach(
        JNIEnv* env, jobject, jlong handle, jobject surface, jint width, jint height,
        jlong surfaceEpoch, jlong refreshPeriodNanos) {
    RollingRenderer* value = renderer(handle);
    if (env == nullptr || value == nullptr || surface == nullptr || surfaceEpoch <= 0) {
        return JNI_FALSE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    if (!value->attach(window, width, height, static_cast<std::uint64_t>(surfaceEpoch),
                       static_cast<std::int64_t>(refreshPeriodNanos))) {
        ANativeWindow_release(window);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDetach(
        JNIEnv*, jobject, jlong handle, jlong surfaceEpoch) {
    RollingRenderer* value = renderer(handle);
    if (value != nullptr) value->detach(static_cast<std::uint64_t>(surfaceEpoch));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSubmit(
        JNIEnv* env, jobject, jlong handle, jlong token, jlong structureEpoch,
        jint width, jint height, jintArray tileData, jfloatArray geometryData,
        jobjectArray bitmaps) {
    RollingRenderer* value = renderer(handle);
    return value != nullptr && value->submit(
        env, static_cast<std::uint64_t>(token), static_cast<std::int64_t>(structureEpoch),
        width, height, tileData, geometryData, bitmaps) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativePrewarm(
        JNIEnv* env, jobject, jlong handle, jlong structureEpoch,
        jintArray tileData, jobjectArray bitmaps) {
    RollingRenderer* value = renderer(handle);
    return value != nullptr && value->prewarm(
        env, static_cast<std::int64_t>(structureEpoch), tileData, bitmaps)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSetPrewarmPaused(
        JNIEnv*, jobject, jlong handle, jboolean paused) {
    RollingRenderer* value = renderer(handle);
    if (value != nullptr) value->setPrewarmPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    RollingRenderer* value = renderer(handle);
    if (value == nullptr) return;
    value->destroy();
    delete value;
}
