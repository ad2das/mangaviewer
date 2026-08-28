#include "present/SurfaceControlPresentBackend.h"
#include "present/DirectTileSurfacePresenter.h"
#include "present/RollingBandPrecomposePolicy.h"
#include "present/AhbCompositorCoordinates.h"
#include "present/GeometryPresentSchedule.h"
#include "BitmapReferenceLedger.h"
#include "RollingTextureHeadroomPlanner.h"

#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/imagedecoder.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <png.h>
#include <turbojpeg.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <jni.h>

#include <dlfcn.h>
#include <fcntl.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <deque>
#include <limits>
#include <mutex>
#include <memory>
#include <new>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <sys/resource.h>
#include <sys/mman.h>
#include <pthread.h>
#include <poll.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr char kTag[] = "NtkRollingRenderer";

bool rollingTimingDiagnosticsEnabled() noexcept {
    // The app supports API 24 while __android_log_is_loggable is exported from API 30. Resolve it
    // at runtime so older devices keep the renderer and simply leave this optional stream off.
    using IsLoggable = int (*)(int, const char*, int);
    static IsLoggable isLoggable = []() noexcept -> IsLoggable {
        void* library = dlopen("liblog.so", RTLD_NOW | RTLD_LOCAL);
        return library == nullptr ? nullptr : reinterpret_cast<IsLoggable>(
            dlsym(library, "__android_log_is_loggable"));
    }();
    return isLoggable != nullptr &&
        isLoggable(ANDROID_LOG_DEBUG, kTag, ANDROID_LOG_INFO) != 0;
}
// The display owner performs only bounded geometry/apply work after the -19 producer seals a
// frame. At -10, emulator decode/GC pressure repeatedly left an already-enqueued physical frame
// runnable for 85-88 ms even though SurfaceControl::apply itself took under 3 ms. Keep the owner
// decisively below the producer (the former inherited -16 could preempt it during content
// transactions), but above ordinary UI/background pools so a sealed frame cannot miss six vsyncs.
constexpr int kRollingConsumerNice = -14;
constexpr int kRollingConsumerNiceFallback = -10;
constexpr int kCpuPrecomposeNice = 0;
constexpr std::size_t kTileIntegerStride = 12U;
// Kotlin maps view-space band origins into the 800px native target with roundToInt(). A legal
// 1080->800 transform therefore carries thirds of a source pixel even though SurfaceControl's
// crop is integral. Pixel/content identity and every page geometry still have to match exactly;
// only this final, deterministic quantization may differ by at most half a pixel.
constexpr double kNativeSourceCropRoundingTolerance = 0.5001;

constexpr std::uint64_t kExactCpuTileMagic = 0x4e544b435055544cULL;

void releaseSurfaceControlReference(ASurfaceControl* surface) noexcept {
    if (surface == nullptr) return;
    using ReleaseSurface = void (*)(ASurfaceControl*);
    void* library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    auto releaseSurface = reinterpret_cast<ReleaseSurface>(
        library != nullptr ? dlsym(library, "ASurfaceControl_release") : nullptr);
    if (releaseSurface != nullptr) releaseSurface(surface);
    if (library != nullptr) dlclose(library);
}

struct ExactCpuTileStorage {
    std::uint64_t magic = kExactCpuTileMagic;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t contentWidth = 0;
    std::uint32_t contentHeight = 0;
    std::uint32_t logicalWidth = 0;
    std::uint32_t logicalHeight = 0;
    std::size_t strideBytes = 0;
    std::size_t allocationBytes = 0;
    std::uint8_t* pixels = nullptr;
    /** Optional compositor-ready mirror used by the bounded direct-tile layer presenter. */
    AHardwareBuffer* hardwareBuffer = nullptr;
    /**
     * Fence returned by AHardwareBuffer_unlock for the most recent mirror publication.
     *
     * Decode owns this descriptor. The direct presenter borrows it long enough to duplicate it
     * into SurfaceFlinger's acquire-fence argument; storage reuse waits and consumes the original.
     */
    std::atomic<int> hardwareWriteFenceFd{-1};
    /** True once the current immutable CPU pixels have a compositor-safe mirror. */
    std::atomic<bool> hardwareMirrorReady{false};
};

struct ExactHardwareBufferSymbols {
    using IsSupported = int (*)(const AHardwareBuffer_Desc*);
    using Allocate = int (*)(const AHardwareBuffer_Desc*, AHardwareBuffer**);
    using Describe = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
    using Lock = int (*)(
        AHardwareBuffer*, std::uint64_t, std::int32_t, const ARect*, void**);
    using Unlock = int (*)(AHardwareBuffer*, std::int32_t*);
    using Acquire = void (*)(AHardwareBuffer*);
    using Release = void (*)(AHardwareBuffer*);

    void* androidLibrary = nullptr;
    IsSupported isSupported = nullptr;
    Allocate allocate = nullptr;
    Describe describe = nullptr;
    Lock lock = nullptr;
    Unlock unlock = nullptr;
    Acquire acquire = nullptr;
    Release release = nullptr;

    bool validForAllocation() const noexcept {
        return allocate != nullptr && describe != nullptr && lock != nullptr &&
            unlock != nullptr && acquire != nullptr && release != nullptr;
    }
};

const ExactHardwareBufferSymbols& exactHardwareBufferSymbols() noexcept {
    static const ExactHardwareBufferSymbols symbols = [] {
        ExactHardwareBufferSymbols result{};
        result.androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (result.androidLibrary == nullptr) return result;
        result.isSupported = reinterpret_cast<ExactHardwareBufferSymbols::IsSupported>(
            dlsym(result.androidLibrary, "AHardwareBuffer_isSupported"));
        result.allocate = reinterpret_cast<ExactHardwareBufferSymbols::Allocate>(
            dlsym(result.androidLibrary, "AHardwareBuffer_allocate"));
        result.describe = reinterpret_cast<ExactHardwareBufferSymbols::Describe>(
            dlsym(result.androidLibrary, "AHardwareBuffer_describe"));
        result.lock = reinterpret_cast<ExactHardwareBufferSymbols::Lock>(
            dlsym(result.androidLibrary, "AHardwareBuffer_lock"));
        result.unlock = reinterpret_cast<ExactHardwareBufferSymbols::Unlock>(
            dlsym(result.androidLibrary, "AHardwareBuffer_unlock"));
        result.acquire = reinterpret_cast<ExactHardwareBufferSymbols::Acquire>(
            dlsym(result.androidLibrary, "AHardwareBuffer_acquire"));
        result.release = reinterpret_cast<ExactHardwareBufferSymbols::Release>(
            dlsym(result.androidLibrary, "AHardwareBuffer_release"));
        return result;
    }();
    return symbols;
}

ExactCpuTileStorage* exactCpuTileFromHalves(jint low, jint high) noexcept {
    const auto bits = static_cast<std::uint64_t>(static_cast<std::uint32_t>(low)) |
        (static_cast<std::uint64_t>(static_cast<std::uint32_t>(high)) << 32U);
    return reinterpret_cast<ExactCpuTileStorage*>(static_cast<std::uintptr_t>(bits));
}

std::uint64_t contentIdentityFromHalves(jint low, jint high) noexcept {
    return static_cast<std::uint64_t>(static_cast<std::uint32_t>(low)) |
        (static_cast<std::uint64_t>(static_cast<std::uint32_t>(high)) << 32U);
}

bool validExactCpuTile(const ExactCpuTileStorage* storage) noexcept {
    return storage != nullptr && storage->magic == kExactCpuTileMagic &&
        storage->width > 0U && storage->height > 0U &&
        storage->strideBytes >= static_cast<std::size_t>(storage->width) * 4U &&
        storage->allocationBytes >= storage->strideBytes * storage->height &&
        (storage->pixels != nullptr || storage->hardwareBuffer != nullptr);
}

bool ensureExactCpuTilePixels(ExactCpuTileStorage* storage) noexcept {
    if (!validExactCpuTile(storage)) return false;
    if (storage->pixels != nullptr) return true;
    void* pixels = nullptr;
    if (posix_memalign(&pixels, 64U, storage->allocationBytes) != 0 || pixels == nullptr) {
        return false;
    }
    storage->pixels = static_cast<std::uint8_t*>(pixels);
    return true;
}

bool exactCpuTileHasContent(
        const ExactCpuTileStorage* storage,
        int logicalWidth,
        int logicalHeight) noexcept {
    return validExactCpuTile(storage) && logicalWidth > 0 && logicalHeight > 0 &&
        storage->logicalWidth == static_cast<std::uint32_t>(logicalWidth) &&
        storage->logicalHeight == static_cast<std::uint32_t>(logicalHeight) &&
        storage->contentWidth > 0U && storage->contentHeight > 0U &&
        storage->contentWidth <= storage->width &&
        storage->contentHeight <= storage->height;
}

bool waitAndCloseExactHardwareWriteFence(ExactCpuTileStorage* storage) noexcept {
    if (storage == nullptr) return false;
    const int fenceFd = storage->hardwareWriteFenceFd.exchange(
        -1, std::memory_order_acq_rel);
    if (fenceFd < 0) return true;
    pollfd descriptor{
        .fd = fenceFd,
        .events = POLLIN,
        .revents = 0,
    };
    int result = -1;
    do {
        result = poll(&descriptor, 1, -1);
    } while (result < 0 && errno == EINTR);
    close(fenceFd);
    return result > 0 &&
        (descriptor.revents & (POLLIN | POLLERR | POLLHUP)) != 0;
}

void closeExactHardwareWriteFence(ExactCpuTileStorage* storage) noexcept {
    if (storage == nullptr) return;
    const int fenceFd = storage->hardwareWriteFenceFd.exchange(
        -1, std::memory_order_acq_rel);
    if (fenceFd >= 0) close(fenceFd);
}

std::int64_t exactMirrorDiagnosticNanos() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool publishExactCpuTileHardwareBuffer(ExactCpuTileStorage* storage) noexcept {
    if (!validExactCpuTile(storage) || storage->hardwareBuffer == nullptr ||
        storage->pixels == nullptr ||
        storage->contentWidth == 0U || storage->contentHeight == 0U ||
        storage->contentWidth > storage->width ||
        storage->contentHeight > storage->height) return false;
    const auto& symbols = exactHardwareBufferSymbols();
    if (!symbols.validForAllocation()) return false;
    // A slot is immutable while owned by a scene. Reuse is the only point at which the prior
    // asynchronous CPU->gralloc transfer must finish before the same storage is locked again.
    const std::int64_t startedNanos = exactMirrorDiagnosticNanos();
    if (!waitAndCloseExactHardwareWriteFence(storage)) return false;
    const std::int64_t priorFenceReadyNanos = exactMirrorDiagnosticNanos();
    AHardwareBuffer_Desc descriptor{};
    symbols.describe(storage->hardwareBuffer, &descriptor);
    if (descriptor.width < storage->width || descriptor.height < storage->height ||
        descriptor.layers != 1U ||
        descriptor.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
        descriptor.stride < storage->width) return false;
    const ARect bounds{
        0,
        0,
        static_cast<std::int32_t>(storage->width),
        static_cast<std::int32_t>(storage->height),
    };
    void* mapped = nullptr;
    if (symbols.lock(
            storage->hardwareBuffer,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1,
            &bounds,
            &mapped) != 0 || mapped == nullptr) return false;
    const std::int64_t lockedNanos = exactMirrorDiagnosticNanos();
    const std::size_t destinationStride =
        static_cast<std::size_t>(descriptor.stride) * 4U;
    const std::size_t contentRowBytes =
        static_cast<std::size_t>(storage->contentWidth) * 4U;
    for (std::uint32_t row = 0; row < storage->contentHeight; ++row) {
        auto* destination = static_cast<std::uint8_t*>(mapped) +
            static_cast<std::size_t>(row) * destinationStride;
        const auto* source = storage->pixels +
            static_cast<std::size_t>(row) * storage->strideBytes;
        std::memcpy(destination, source, contentRowBytes);
        if (destinationStride > contentRowBytes) {
            std::memset(
                destination + contentRowBytes,
                0,
                destinationStride - contentRowBytes);
        }
    }
    for (std::uint32_t row = storage->contentHeight; row < storage->height; ++row) {
        std::memset(
            static_cast<std::uint8_t*>(mapped) +
                static_cast<std::size_t>(row) * destinationStride,
            0,
            destinationStride);
    }
    const std::int64_t copiedNanos = exactMirrorDiagnosticNanos();
    int completionFenceFd = -1;
    if (symbols.unlock(storage->hardwareBuffer, &completionFenceFd) != 0) {
        if (completionFenceFd >= 0) close(completionFenceFd);
        return false;
    }
    const std::int64_t unlockedNanos = exactMirrorDiagnosticNanos();
    if (completionFenceFd >= 0) {
        // Do not serialize the decoder behind gfxstream's host transfer. SurfaceFlinger receives
        // a duplicate as the layer acquire fence and therefore cannot sample partial pixels.
        storage->hardwareWriteFenceFd.store(completionFenceFd, std::memory_order_release);
    }
    static std::atomic<std::uint32_t> diagnosticOrdinal{0};
    const std::uint32_t ordinal = diagnosticOrdinal.fetch_add(1, std::memory_order_relaxed) + 1U;
    if (ordinal <= 16U) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "exact mirror phase ordinal=%u totalMs=%.3f priorFenceMs=%.3f lockMs=%.3f "
            "copyMs=%.3f unlockMs=%.3f fence=%d",
            ordinal,
            static_cast<double>(unlockedNanos - startedNanos) / 1'000'000.0,
            static_cast<double>(priorFenceReadyNanos - startedNanos) / 1'000'000.0,
            static_cast<double>(lockedNanos - priorFenceReadyNanos) / 1'000'000.0,
            static_cast<double>(copiedNanos - lockedNanos) / 1'000'000.0,
            static_cast<double>(unlockedNanos - copiedNanos) / 1'000'000.0,
            completionFenceFd);
    }
    return true;
}

// gfxstream exposes one process-wide gralloc submission lane. Independent JPEG decoders may fill
// private malloc storage in parallel; keep only lock/copy/unlock submission serial. Transfer
// completion is represented by each tile's acquire fence and never blocks unrelated decoding.
std::mutex gExactHardwareMirrorMutex;

bool refreshExactCpuTileHardwareMirror(ExactCpuTileStorage* storage) noexcept {
    const std::int64_t queuedNanos = exactMirrorDiagnosticNanos();
    std::lock_guard<std::mutex> publication(gExactHardwareMirrorMutex);
    const std::int64_t admittedNanos = exactMirrorDiagnosticNanos();
    if (storage == nullptr || storage->hardwareBuffer == nullptr) return false;
    if (publishExactCpuTileHardwareBuffer(storage)) {
        // The compositor mirror is the settled display authority. Keep malloc storage only as a
        // transient decode/copy target; retaining both copies doubles every page and repeatedly
        // crosses ART's NativeAlloc GC threshold during physical scrolling. Slot rehydration
        // recreates this backing with ensureExactCpuTilePixels() before decoding.
        std::free(storage->pixels);
        storage->pixels = nullptr;
        storage->hardwareMirrorReady.store(true, std::memory_order_release);
        static std::atomic<std::uint32_t> admissionOrdinal{0};
        const std::uint32_t ordinal =
            admissionOrdinal.fetch_add(1, std::memory_order_relaxed) + 1U;
        if (ordinal <= 16U) {
            __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "exact mirror admission ordinal=%u queueMs=%.3f publishMs=%.3f",
                ordinal,
                static_cast<double>(admittedNanos - queuedNanos) / 1'000'000.0,
                static_cast<double>(exactMirrorDiagnosticNanos() - admittedNanos) / 1'000'000.0);
        }
        return true;
    }
    // The malloc-backed exact storage remains authoritative for the established renderer. A
    // device/driver that rejects the compositor mirror simply keeps using that path.
    const auto& symbols = exactHardwareBufferSymbols();
    if (symbols.release != nullptr) symbols.release(storage->hardwareBuffer);
    storage->hardwareBuffer = nullptr;
    storage->hardwareMirrorReady.store(false, std::memory_order_release);
    return false;
}

struct RgbaHorizontalSample {
    std::uint32_t x0 = 0U;
    std::uint32_t x1 = 0U;
    std::uint32_t weight = 0U;
};

bool scaleRgba8888(
        const std::uint8_t* source,
        std::uint32_t sourceWidth,
        std::uint32_t sourceHeight,
        std::size_t sourceStride,
        std::uint8_t* destination,
        std::uint32_t destinationWidth,
        std::uint32_t destinationHeight,
        std::size_t destinationStride) noexcept {
    if (source == nullptr || destination == nullptr || sourceWidth == 0U ||
        sourceHeight == 0U || destinationWidth == 0U || destinationHeight == 0U ||
        sourceStride < static_cast<std::size_t>(sourceWidth) * 4U ||
        destinationStride < static_cast<std::size_t>(destinationWidth) * 4U) {
        return false;
    }
    if (sourceWidth == destinationWidth && sourceHeight == destinationHeight) {
        const std::size_t rowBytes = static_cast<std::size_t>(sourceWidth) * 4U;
        for (std::uint32_t row = 0; row < sourceHeight; ++row) {
            std::memcpy(
                destination + static_cast<std::size_t>(row) * destinationStride,
                source + static_cast<std::size_t>(row) * sourceStride,
                rowBytes);
        }
        return true;
    }
    // The horizontal mapping is identical for every output row. The former loop recomputed a
    // 64-bit division for every pixel and then used 64-bit arithmetic for values whose exact
    // maximum is only 255 * 65536. On host-gpu emulators that made one ordinary 1403x2048 manga
    // page consume 30-74 ms of CPU while the display-critical threads were runnable. Cache the
    // immutable mapping per calling worker and keep the mathematically identical interpolation in
    // 32 bits; complementary 16.16 weights bound every intermediate below 2^24.
    thread_local std::vector<RgbaHorizontalSample> horizontalSamples;
    thread_local std::uint32_t horizontalSourceWidth = 0U;
    thread_local std::uint32_t horizontalDestinationWidth = 0U;
    constexpr std::uint64_t kOne = 1ULL << 16U;
    constexpr std::uint64_t kHalf = kOne >> 1U;
    if (horizontalSourceWidth != sourceWidth ||
        horizontalDestinationWidth != destinationWidth ||
        horizontalSamples.size() != destinationWidth) {
        horizontalSamples.resize(destinationWidth);
        for (std::uint32_t x = 0; x < destinationWidth; ++x) {
            std::uint64_t sourceX =
                ((static_cast<std::uint64_t>(x) * 2ULL + 1ULL) * sourceWidth * kOne) /
                (static_cast<std::uint64_t>(destinationWidth) * 2ULL);
            sourceX = sourceX > kHalf ? sourceX - kHalf : 0ULL;
            std::uint32_t x0 = static_cast<std::uint32_t>(sourceX >> 16U);
            std::uint32_t wx = static_cast<std::uint32_t>(sourceX & (kOne - 1ULL));
            if (x0 >= sourceWidth - 1U) {
                x0 = sourceWidth - 1U;
                wx = 0U;
            }
            horizontalSamples[x] = RgbaHorizontalSample{
                x0,
                std::min(x0 + 1U, sourceWidth - 1U),
                wx,
            };
        }
        horizontalSourceWidth = sourceWidth;
        horizontalDestinationWidth = destinationWidth;
    }
    constexpr std::uint32_t kOne32 = 1U << 16U;
    for (std::uint32_t y = 0; y < destinationHeight; ++y) {
        std::uint64_t sourceY =
            ((static_cast<std::uint64_t>(y) * 2ULL + 1ULL) * sourceHeight * kOne) /
            (static_cast<std::uint64_t>(destinationHeight) * 2ULL);
        sourceY = sourceY > kHalf ? sourceY - kHalf : 0ULL;
        std::uint32_t y0 = static_cast<std::uint32_t>(sourceY >> 16U);
        std::uint32_t wy = static_cast<std::uint32_t>(sourceY & (kOne - 1ULL));
        if (y0 >= sourceHeight - 1U) {
            y0 = sourceHeight - 1U;
            wy = 0U;
        }
        const std::uint32_t y1 = std::min(y0 + 1U, sourceHeight - 1U);
        const auto* row0 = source + static_cast<std::size_t>(y0) * sourceStride;
        const auto* row1 = source + static_cast<std::size_t>(y1) * sourceStride;
        auto* output = destination + static_cast<std::size_t>(y) * destinationStride;
        for (std::uint32_t x = 0; x < destinationWidth; ++x) {
            const RgbaHorizontalSample sample = horizontalSamples[x];
            const std::uint32_t inverseX = kOne32 - sample.weight;
            const std::uint32_t inverseY = kOne32 - wy;
            for (std::uint32_t channel = 0; channel < 4U; ++channel) {
                const std::uint32_t top = static_cast<std::uint32_t>(
                    (static_cast<std::uint32_t>(row0[sample.x0 * 4U + channel]) *
                         inverseX +
                     static_cast<std::uint32_t>(row0[sample.x1 * 4U + channel]) *
                         sample.weight) >> 16U);
                const std::uint32_t bottom = static_cast<std::uint32_t>(
                    (static_cast<std::uint32_t>(row1[sample.x0 * 4U + channel]) *
                         inverseX +
                     static_cast<std::uint32_t>(row1[sample.x1 * 4U + channel]) *
                         sample.weight) >> 16U);
                output[x * 4U + channel] = static_cast<std::uint8_t>(
                    (top * inverseY + bottom * wy) >> 16U);
            }
        }
    }
    return true;
}

// The renderer may own one command while one newest demand waits. A scroll viewport is latest-value
// state: retaining older unclaimed crops or rolling-band snapshots only makes the visible result
// seconds stale under host-compositor backpressure. Full-scene commands remain immutable and exact;
// replacing an unclaimed one transfers its token back to Kotlin's proof owner before the newer
// command is registered, so this bound neither invents pixels nor reports a presentation failure.
constexpr std::size_t kMaxQueuedFrames = 1;
// Keep ordinary mailbox replacement distinct from a presentation failure. Kotlin can retire the
// superseded proof without arming another producer frame because the replacement command is
// already in [frames_]. Treating both outcomes as the same failure created a retry feedback loop
// whenever the host compositor was briefly backpressured.
constexpr jint kDropReasonMailboxSuperseded = 1;
constexpr jint kDropReasonPresentFailed = 2;
constexpr jint kDropReasonLifecycleRetired = 3;
// The normal producer still publishes only the current page plus two unread pages. Once every
// post-click original has been installed it may publish one full-scene snapshot; keep that queue
// intact so the worker can fill idle EGL slots during the long forward traversal.
constexpr std::size_t kMaxQueuedPrewarmTiles = 1024;
constexpr int kPausedForwardPrewarmPages = 16;
// A host-GPU r90 trace needed twelve current-tail pages, one card and p0-p4. Keep physical direct
// Wi-Fi on the established bound while admitting the complete decoded adjacent runway before the
// emulator's physical-input pause closes its non-presenting upload lane.
constexpr int kHostGpuPausedForwardPrewarmPages = 24;
constexpr std::int64_t kDefaultRefreshPeriodNanos = 11'111'111;
// Direct Wi-Fi may replenish one immutable forward tile only when the visible mailbox and
// compositor event queue are empty. Spread those uploads far enough apart that host gfxstream
// receives three page tiles over several visible frames instead of one first-visible burst.
constexpr std::int64_t kActiveDirectWifiPrewarmPeriods = 2;
// UiAutomator and real repeated swipes both have a short interval after OverScroller finishes and
// before the next ACTION_DOWN.  gfxstream retains uploads issued in that interval and makes the
// next visible submission pay their fence cost.  Only a genuine reading pause may reopen the
// non-presenting upload lane.
constexpr std::int64_t kPrewarmResumeQuietNanos = 750'000'000;
constexpr std::int64_t kTextureRetirementIdleQuietNanos = 750'000'000;
constexpr std::int64_t kTextureRetirementPausedExtraQuietNanos = 1'250'000'000;
constexpr std::int64_t kTextureRetirementErrorRetryNanos = 100'000'000;
constexpr std::size_t kMaxExactDecodeScratchBytes = 64ULL * 1024ULL * 1024ULL;

bool hasPngSignature(int fd) noexcept {
    constexpr std::uint8_t signature[] = {137U, 80U, 78U, 71U, 13U, 10U, 26U, 10U};
    std::uint8_t observed[sizeof(signature)]{};
    return fd >= 0 && pread(fd, observed, sizeof(observed), 0) ==
            static_cast<ssize_t>(sizeof(observed)) &&
        std::memcmp(observed, signature, sizeof(signature)) == 0;
}

bool hasJpegSignature(int fd) noexcept {
    constexpr std::uint8_t signature[] = {0xffU, 0xd8U, 0xffU};
    std::uint8_t observed[sizeof(signature)]{};
    return fd >= 0 && pread(fd, observed, sizeof(observed), 0) ==
            static_cast<ssize_t>(sizeof(observed)) &&
        std::memcmp(observed, signature, sizeof(signature)) == 0;
}

void logUnhandledExactFileSignature(int fd) noexcept {
    std::uint8_t observed[12]{};
    const ssize_t count = fd >= 0 ? pread(fd, observed, sizeof(observed), 0) : -1;
    const bool webp = count == static_cast<ssize_t>(sizeof(observed)) &&
        std::memcmp(observed, "RIFF", 4U) == 0 &&
        std::memcmp(observed + 8U, "WEBP", 4U) == 0;
    const bool gif = count >= 6 &&
        (std::memcmp(observed, "GIF87a", 6U) == 0 ||
         std::memcmp(observed, "GIF89a", 6U) == 0);
    const std::uint32_t category = webp ? 1U : gif ? 2U : 4U;
    static std::atomic<std::uint32_t> loggedCategories{0U};
    if ((loggedCategories.fetch_or(category, std::memory_order_relaxed) & category) != 0U) {
        return;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "exact file fallback signature category=%s bytes="
        "%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
        webp ? "webp" : gif ? "gif" : "other",
        observed[0], observed[1], observed[2], observed[3], observed[4], observed[5],
        observed[6], observed[7], observed[8], observed[9], observed[10], observed[11]);
}

bool decodeExactPngFile(
        int fd, int expectedWidth, int expectedHeight,
        std::uint8_t* destination, std::size_t destinationBytes) noexcept {
    if (fd < 0 || expectedWidth <= 0 || expectedHeight <= 0 || destination == nullptr) {
        return false;
    }
    struct stat status{};
    if (fstat(fd, &status) != 0 || status.st_size <= 0) return false;
    const std::size_t encodedBytes = static_cast<std::size_t>(status.st_size);
    void* mapping = mmap(nullptr, encodedBytes, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) return false;

    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    bool valid = png_image_begin_read_from_memory(&image, mapping, encodedBytes) != 0 &&
        image.width == static_cast<png_uint_32>(expectedWidth) &&
        image.height == static_cast<png_uint_32>(expectedHeight);
    if (valid) {
        // Android's RGBA_8888 decode contract uses associated alpha. Manga pages are normally
        // opaque, but retaining the same contract keeps uncommon transparent PNGs pixel-correct.
        image.format = PNG_FORMAT_RGBA | PNG_FORMAT_FLAG_ASSOCIATED_ALPHA;
        const png_alloc_size_t requiredBytes = PNG_IMAGE_SIZE(image);
        valid = requiredBytes <= destinationBytes &&
            png_image_finish_read(&image, nullptr, destination, 0, nullptr) != 0;
    }
    png_image_free(&image);
    munmap(mapping, encodedBytes);
    return valid;
}

struct ExactPngReadState {
    int fd = -1;
    off_t offset = 0;
};

void readExactPngBytes(
        png_structp png,
        png_bytep destination,
        png_size_t requestedBytes) noexcept {
    auto* state = static_cast<ExactPngReadState*>(png_get_io_ptr(png));
    if (state == nullptr || state->fd < 0 || destination == nullptr) {
        png_error(png, "invalid exact PNG read state");
        return;
    }
    std::size_t remaining = static_cast<std::size_t>(requestedBytes);
    while (remaining > 0U) {
        const ssize_t count = pread(state->fd, destination, remaining, state->offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            png_error(png, "short exact PNG read");
            return;
        }
        destination += count;
        remaining -= static_cast<std::size_t>(count);
        state->offset += count;
    }
}

/**
 * Decodes an ordinary non-interlaced PNG one source row at a time and writes the exact bilinear
 * display-scale result directly into the already-reserved immutable tile storage.
 *
 * The former PNG path first retained sourceWidth * sourceHeight * 4 bytes in a process-wide
 * scratch and only then scaled it. A 3970x2894 webtoon page therefore grew that persistent
 * scratch to roughly 46 MiB while the final 800-wide pixels occupied less than 8 MiB. On the
 * emulator that native growth repeatedly coincided with ART's process-wide NativeAlloc compacting
 * collection and stopped the display producer. Downscaling only needs the two source rows that
 * straddle an output row, so this path has O(sourceWidth + displayWidth) temporary storage and no
 * source-sized allocation. Interlaced PNGs retain the format-complete fallback below because
 * libpng needs prior-pass row contents to reconstruct them exactly.
 */
bool decodeScaledPngFileToExactCpuTiles(
        int fd,
        int expectedWidth,
        int expectedHeight,
        int tileCapacityHeight,
        int displayWidth,
        const std::vector<jlong>& handles,
        const std::vector<std::int32_t>& sourceSpans,
        const std::vector<std::int32_t>& displaySpans) noexcept {
    if (fd < 0 || expectedWidth <= 0 || expectedHeight <= 0 ||
        tileCapacityHeight <= 0 || displayWidth <= 0 || displayWidth > expectedWidth ||
        handles.empty() || handles.size() != sourceSpans.size() ||
        handles.size() != displaySpans.size()) {
        return false;
    }

    const std::size_t sourceStride = static_cast<std::size_t>(expectedWidth) * 4U;
    if (sourceStride / 4U != static_cast<std::size_t>(expectedWidth) ||
        sourceStride > std::numeric_limits<std::size_t>::max() / 2U) {
        return false;
    }
    std::vector<std::uint8_t> sourceRows;
    std::vector<RgbaHorizontalSample> horizontalSamples;
    std::vector<ExactCpuTileStorage*> storages(handles.size(), nullptr);
    sourceRows.resize(sourceStride * 2U);
    horizontalSamples.resize(static_cast<std::size_t>(displayWidth));

    std::int64_t verifiedSourceRows = 0;
    for (std::size_t index = 0; index < handles.size(); ++index) {
        auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
            static_cast<std::uintptr_t>(handles[index]));
        const std::int32_t sourceSpan = sourceSpans[index];
        const std::int32_t displaySpan = displaySpans[index];
        if (!validExactCpuTile(storage) || sourceSpan <= 0 ||
            sourceSpan > tileCapacityHeight || displaySpan <= 0 ||
            storage->width < static_cast<std::uint32_t>(displayWidth) ||
            storage->height < static_cast<std::uint32_t>(displaySpan) ||
            verifiedSourceRows > expectedHeight - sourceSpan) {
            return false;
        }
        storages[index] = storage;
        verifiedSourceRows += sourceSpan;
    }
    if (verifiedSourceRows != expectedHeight) return false;

    constexpr std::uint64_t kOne = 1ULL << 16U;
    constexpr std::uint64_t kHalf = kOne >> 1U;
    constexpr std::uint32_t kOne32 = 1U << 16U;
    for (std::uint32_t x = 0; x < static_cast<std::uint32_t>(displayWidth); ++x) {
        std::uint64_t sourceX =
            ((static_cast<std::uint64_t>(x) * 2ULL + 1ULL) *
                static_cast<std::uint32_t>(expectedWidth) * kOne) /
            (static_cast<std::uint64_t>(displayWidth) * 2ULL);
        sourceX = sourceX > kHalf ? sourceX - kHalf : 0ULL;
        std::uint32_t x0 = static_cast<std::uint32_t>(sourceX >> 16U);
        std::uint32_t wx = static_cast<std::uint32_t>(sourceX & (kOne - 1ULL));
        if (x0 >= static_cast<std::uint32_t>(expectedWidth - 1)) {
            x0 = static_cast<std::uint32_t>(expectedWidth - 1);
            wx = 0U;
        }
        horizontalSamples[x] = RgbaHorizontalSample{
            x0,
            std::min(x0 + 1U, static_cast<std::uint32_t>(expectedWidth - 1)),
            wx,
        };
    }

    png_structp png = png_create_read_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    if (png == nullptr) return false;
    png_infop info = png_create_info_struct(png);
    if (info == nullptr) {
        png_destroy_read_struct(&png, nullptr, nullptr);
        return false;
    }
    if (setjmp(png_jmpbuf(png)) != 0) {
        png_destroy_read_struct(&png, &info, nullptr);
        return false;
    }

    ExactPngReadState readState{fd, 0};
    png_set_read_fn(png, &readState, readExactPngBytes);
    png_read_info(png, info);
    const png_uint_32 pngWidth = png_get_image_width(png, info);
    const png_uint_32 pngHeight = png_get_image_height(png, info);
    const int colorType = png_get_color_type(png, info);
    const int bitDepth = png_get_bit_depth(png, info);
    const bool hasTransparency = (colorType & PNG_COLOR_MASK_ALPHA) != 0 ||
        png_get_valid(png, info, PNG_INFO_tRNS) != 0;
    if (pngWidth != static_cast<png_uint_32>(expectedWidth) ||
        pngHeight != static_cast<png_uint_32>(expectedHeight) ||
        png_get_interlace_type(png, info) != PNG_INTERLACE_NONE) {
        png_destroy_read_struct(&png, &info, nullptr);
        return false;
    }
    if (bitDepth == 16) png_set_strip_16(png);
    if (colorType == PNG_COLOR_TYPE_PALETTE) png_set_palette_to_rgb(png);
    if (colorType == PNG_COLOR_TYPE_GRAY && bitDepth < 8) {
        png_set_expand_gray_1_2_4_to_8(png);
    }
    if (png_get_valid(png, info, PNG_INFO_tRNS) != 0) png_set_tRNS_to_alpha(png);
    if (colorType == PNG_COLOR_TYPE_GRAY || colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
        png_set_gray_to_rgb(png);
    }
    if ((colorType & PNG_COLOR_MASK_ALPHA) == 0 &&
        png_get_valid(png, info, PNG_INFO_tRNS) == 0) {
        png_set_add_alpha(png, 0xffU, PNG_FILLER_AFTER);
    }
    png_read_update_info(png, info);
    if (png_get_bit_depth(png, info) != 8 || png_get_channels(png, info) != 4 ||
        png_get_rowbytes(png, info) != sourceStride) {
        png_destroy_read_struct(&png, &info, nullptr);
        return false;
    }

    std::uint32_t nextSourceRow = 0U;
    const auto readThrough = [&](std::uint32_t requiredRow) {
        while (nextSourceRow <= requiredRow) {
            auto* row = sourceRows.data() +
                static_cast<std::size_t>(nextSourceRow & 1U) * sourceStride;
            png_read_row(png, row, nullptr);
            if (hasTransparency) {
                for (std::uint32_t x = 0; x < static_cast<std::uint32_t>(expectedWidth); ++x) {
                    auto* pixel = row + static_cast<std::size_t>(x) * 4U;
                    const std::uint32_t alpha = pixel[3];
                    pixel[0] = static_cast<std::uint8_t>((pixel[0] * alpha + 127U) / 255U);
                    pixel[1] = static_cast<std::uint8_t>((pixel[1] * alpha + 127U) / 255U);
                    pixel[2] = static_cast<std::uint8_t>((pixel[2] * alpha + 127U) / 255U);
                }
            }
            ++nextSourceRow;
        }
    };

    std::uint32_t tileSourceTop = 0U;
    for (std::size_t tile = 0; tile < storages.size(); ++tile) {
        auto* storage = storages[tile];
        const std::uint32_t sourceSpan = static_cast<std::uint32_t>(sourceSpans[tile]);
        const std::uint32_t displaySpan = static_cast<std::uint32_t>(displaySpans[tile]);
        const std::uint32_t sourceLast = tileSourceTop + sourceSpan - 1U;
        for (std::uint32_t y = 0; y < displaySpan; ++y) {
            std::uint64_t sourceY =
                ((static_cast<std::uint64_t>(y) * 2ULL + 1ULL) * sourceSpan * kOne) /
                (static_cast<std::uint64_t>(displaySpan) * 2ULL);
            sourceY = sourceY > kHalf ? sourceY - kHalf : 0ULL;
            std::uint32_t y0 = tileSourceTop +
                static_cast<std::uint32_t>(sourceY >> 16U);
            std::uint32_t wy = static_cast<std::uint32_t>(sourceY & (kOne - 1ULL));
            if (y0 >= sourceLast) {
                y0 = sourceLast;
                wy = 0U;
            }
            const std::uint32_t y1 = std::min(y0 + 1U, sourceLast);
            readThrough(y1);
            const auto* row0 = sourceRows.data() +
                static_cast<std::size_t>(y0 & 1U) * sourceStride;
            const auto* row1 = sourceRows.data() +
                static_cast<std::size_t>(y1 & 1U) * sourceStride;
            auto* output = storage->pixels + static_cast<std::size_t>(y) * storage->strideBytes;
            const std::uint32_t inverseY = kOne32 - wy;
            for (std::uint32_t x = 0; x < static_cast<std::uint32_t>(displayWidth); ++x) {
                const RgbaHorizontalSample sample = horizontalSamples[x];
                const std::uint32_t inverseX = kOne32 - sample.weight;
                for (std::uint32_t channel = 0; channel < 4U; ++channel) {
                    const std::uint32_t top =
                        (static_cast<std::uint32_t>(row0[sample.x0 * 4U + channel]) *
                                inverseX +
                            static_cast<std::uint32_t>(row0[sample.x1 * 4U + channel]) *
                                sample.weight) >> 16U;
                    const std::uint32_t bottom =
                        (static_cast<std::uint32_t>(row1[sample.x0 * 4U + channel]) *
                                inverseX +
                            static_cast<std::uint32_t>(row1[sample.x1 * 4U + channel]) *
                                sample.weight) >> 16U;
                    output[x * 4U + channel] = static_cast<std::uint8_t>(
                        (top * inverseY + bottom * wy) >> 16U);
                }
            }
        }
        tileSourceTop += sourceSpan;
    }
    if (nextSourceRow < static_cast<std::uint32_t>(expectedHeight)) {
        readThrough(static_cast<std::uint32_t>(expectedHeight - 1));
    }
    png_read_end(png, info);
    png_destroy_read_struct(&png, &info, nullptr);

    for (std::size_t index = 0; index < storages.size(); ++index) {
        auto* storage = storages[index];
        storage->contentWidth = static_cast<std::uint32_t>(displayWidth);
        storage->contentHeight = static_cast<std::uint32_t>(displaySpans[index]);
        storage->logicalWidth = static_cast<std::uint32_t>(expectedWidth);
        storage->logicalHeight = static_cast<std::uint32_t>(sourceSpans[index]);
    }
    return true;
}

bool decodeExactJpegFile(
        int fd, int expectedWidth, int expectedHeight,
        std::uint8_t* destination, std::size_t destinationBytes) noexcept {
    if (fd < 0 || expectedWidth <= 0 || expectedHeight <= 0 || destination == nullptr) {
        return false;
    }
    struct stat status{};
    if (fstat(fd, &status) != 0 || status.st_size <= 0 ||
        static_cast<std::uint64_t>(status.st_size) >
            static_cast<std::uint64_t>(std::numeric_limits<unsigned long>::max())) {
        return false;
    }
    const std::size_t encodedBytes = static_cast<std::size_t>(status.st_size);
    void* mapping = mmap(nullptr, encodedBytes, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) return false;
    tjhandle decoder = tjInitDecompress();
    if (decoder == nullptr) {
        munmap(mapping, encodedBytes);
        return false;
    }
    int width = 0;
    int height = 0;
    int subsampling = TJSAMP_UNKNOWN;
    int colorSpace = 0;
    const auto* encoded = static_cast<const unsigned char*>(mapping);
    bool valid = tjDecompressHeader3(
            decoder,
            encoded,
            static_cast<unsigned long>(encodedBytes),
            &width,
            &height,
            &subsampling,
            &colorSpace) == 0 &&
        width == expectedWidth && height == expectedHeight;
    const std::size_t requiredBytes = static_cast<std::size_t>(expectedWidth) *
        static_cast<std::size_t>(expectedHeight) * 4U;
    valid = valid && requiredBytes <= destinationBytes;
    if (valid) {
        valid = tjDecompress2(
                decoder,
                encoded,
                static_cast<unsigned long>(encodedBytes),
                destination,
                expectedWidth,
                expectedWidth * 4,
                expectedHeight,
                TJPF_RGBA,
                TJFLAG_ACCURATEDCT) == 0;
    }
    tjDestroy(decoder);
    munmap(mapping, encodedBytes);
    return valid;
}

/**
 * One-tile/no-resize JPEG fast path. Each call owns its TurboJPEG decoder and final tile, so it
 * needs neither the process-wide image scratch nor its mutex. The destination pitch may be wider
 * than the logical source because pooled display storage is width-bucketed.
 */
bool decodeExactJpegFileToStridedTile(
        int fd, int expectedWidth, int expectedHeight,
        std::uint8_t* destination, std::size_t destinationStride,
        std::size_t destinationBytes) noexcept {
    if (fd < 0 || expectedWidth <= 0 || expectedHeight <= 0 || destination == nullptr ||
        destinationStride < static_cast<std::size_t>(expectedWidth) * 4U ||
        destinationStride > static_cast<std::size_t>(INT32_MAX) ||
        static_cast<std::size_t>(expectedHeight) > destinationBytes / destinationStride) {
        return false;
    }
    struct stat status{};
    if (fstat(fd, &status) != 0 || status.st_size <= 0 ||
        static_cast<std::uint64_t>(status.st_size) >
            static_cast<std::uint64_t>(std::numeric_limits<unsigned long>::max())) {
        return false;
    }
    const std::size_t encodedBytes = static_cast<std::size_t>(status.st_size);
    void* mapping = mmap(nullptr, encodedBytes, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) return false;
    tjhandle decoder = tjInitDecompress();
    if (decoder == nullptr) {
        munmap(mapping, encodedBytes);
        return false;
    }
    int width = 0;
    int height = 0;
    int subsampling = TJSAMP_UNKNOWN;
    int colorSpace = 0;
    const auto* encoded = static_cast<const unsigned char*>(mapping);
    bool valid = tjDecompressHeader3(
            decoder,
            encoded,
            static_cast<unsigned long>(encodedBytes),
            &width,
            &height,
            &subsampling,
            &colorSpace) == 0 &&
        width == expectedWidth && height == expectedHeight;
    if (valid) {
        valid = tjDecompress2(
                decoder,
                encoded,
                static_cast<unsigned long>(encodedBytes),
                destination,
                expectedWidth,
                static_cast<int>(destinationStride),
                expectedHeight,
                TJPF_RGBA,
                TJFLAG_ACCURATEDCT) == 0;
    }
    tjDestroy(decoder);
    munmap(mapping, encodedBytes);
    return valid;
}

/**
 * Decodes a JPEG at the smallest libjpeg-turbo DCT scale that still covers the display target,
 * then performs one exact final resample into the caller's reusable output storage.
 *
 * The host-emulator path previously opened AImageDecoder for every ordinary JPEG even though its
 * signature had already been proved. Besides taking 100-380 ms on common manga pages, that API
 * participates in ART's NativeAllocationRegistry and repeatedly starts process-wide compacting
 * GC while input/render threads are active. TurboJPEG owns only this bounded native scratch and
 * cannot request an ART collection. The final dimensions and RGBA contract remain identical to
 * the existing display-target decoder.
 *
 * The caller serializes this function with the exact-decode scratch mutex.
 */
bool decodeScaledJpegFile(
        int fd,
        int expectedWidth,
        int expectedHeight,
        int targetWidth,
        int targetHeight,
        std::uint8_t* destination,
        std::size_t destinationStride,
        std::size_t destinationBytes,
        std::uint8_t*& intermediate,
        std::size_t& intermediateCapacity) noexcept {
    if (fd < 0 || expectedWidth <= 0 || expectedHeight <= 0 || targetWidth <= 0 ||
        targetHeight <= 0 || targetWidth > expectedWidth || targetHeight > expectedHeight ||
        destination == nullptr || destinationStride < static_cast<std::size_t>(targetWidth) * 4U ||
        static_cast<std::size_t>(targetHeight) > destinationBytes / destinationStride) {
        return false;
    }
    struct stat status{};
    if (fstat(fd, &status) != 0 || status.st_size <= 0 ||
        static_cast<std::uint64_t>(status.st_size) >
            static_cast<std::uint64_t>(std::numeric_limits<unsigned long>::max())) {
        return false;
    }
    const std::size_t encodedBytes = static_cast<std::size_t>(status.st_size);
    void* mapping = mmap(nullptr, encodedBytes, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) return false;
    tjhandle decoder = tjInitDecompress();
    if (decoder == nullptr) {
        munmap(mapping, encodedBytes);
        return false;
    }
    const auto* encoded = static_cast<const unsigned char*>(mapping);
    int width = 0;
    int height = 0;
    int subsampling = TJSAMP_UNKNOWN;
    int colorSpace = 0;
    bool valid = tjDecompressHeader3(
            decoder,
            encoded,
            static_cast<unsigned long>(encodedBytes),
            &width,
            &height,
            &subsampling,
            &colorSpace) == 0 &&
        width == expectedWidth && height == expectedHeight;

    tjscalingfactor selected = TJUNSCALED;
    int selectedWidth = expectedWidth;
    int selectedHeight = expectedHeight;
    std::uint64_t selectedPixels = static_cast<std::uint64_t>(selectedWidth) *
        static_cast<std::uint64_t>(selectedHeight);
    int factorCount = 0;
    tjscalingfactor* factors = valid ? tjGetScalingFactors(&factorCount) : nullptr;
    for (int index = 0; valid && factors != nullptr && index < factorCount; ++index) {
        const int candidateWidth = TJSCALED(expectedWidth, factors[index]);
        const int candidateHeight = TJSCALED(expectedHeight, factors[index]);
        if (candidateWidth < targetWidth || candidateHeight < targetHeight) continue;
        const std::uint64_t candidatePixels = static_cast<std::uint64_t>(candidateWidth) *
            static_cast<std::uint64_t>(candidateHeight);
        if (candidatePixels < selectedPixels) {
            selected = factors[index];
            selectedWidth = candidateWidth;
            selectedHeight = candidateHeight;
            selectedPixels = candidatePixels;
        }
    }
    const std::size_t intermediateStride = static_cast<std::size_t>(selectedWidth) * 4U;
    const std::size_t intermediateBytes = intermediateStride *
        static_cast<std::size_t>(selectedHeight);
    if (valid && intermediateBytes > intermediateCapacity) {
        void* resized = std::realloc(intermediate, intermediateBytes);
        if (resized == nullptr) {
            valid = false;
        } else {
            intermediate = static_cast<std::uint8_t*>(resized);
            intermediateCapacity = intermediateBytes;
        }
    }
    if (valid) {
        valid = tjDecompress2(
                decoder,
                encoded,
                static_cast<unsigned long>(encodedBytes),
                intermediate,
                selectedWidth,
                static_cast<int>(intermediateStride),
                selectedHeight,
                TJPF_RGBA,
                TJFLAG_ACCURATEDCT) == 0;
    }
    if (valid) {
        valid = scaleRgba8888(
            intermediate,
            static_cast<std::uint32_t>(selectedWidth),
            static_cast<std::uint32_t>(selectedHeight),
            intermediateStride,
            destination,
            static_cast<std::uint32_t>(targetWidth),
            static_cast<std::uint32_t>(targetHeight),
            destinationStride);
    }
    tjDestroy(decoder);
    munmap(mapping, encodedBytes);
    return valid;
}
// The product's continuous reader is forward-biased. Keep a small bounce margin behind the
// visible span, but do not let already-read GPU copies accumulate until the generic byte ceiling.
// The immutable Java Bitmap remains installed, so an uncommon upward gesture can upload it again
// without network or decode work. A 200-page 1280px trace otherwise retained 49 old textures
// (about 299 MiB) and made gfxstream block eglSwapBuffers for 106 ms during a forward fling.
constexpr int kRetainedBackwardTexturePages = 2;
// GPU residency is a viewport cache, not episode ownership. A 288 MiB ceiling retained the unread
// forward runway while reverse scrolling re-uploaded older pages, growing to 171-198 MiB and
// causing long host/driver stalls. Immutable Java pixels remain available for lossless re-upload,
// so cap both bytes and tiny-tile cardinality and let the existing distance-aware eviction keep
// the visible span plus its nearest runway. An explicitly proven complete-scene snapshot can still
// raise this soft floor for its own epoch below.
constexpr std::uint64_t kMaxTextureBudgetBytes = 48ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaxResidentTextureCount = 12;
// Deleting and recreating texture storage on the emulator's host GL translator serializes the
// render pipe. Keep a very small, byte-bounded storage pool so later pages reuse existing
// allocations. This is GPU storage only: it neither retains encoded bodies nor starts requests.
// A source-native 1080x1440 page is split into three 6,220,800-byte RGBA upload
// tiles.  A 16 MiB pool retained only two of those equal-size allocations, so
// every forward page boundary deleted and recreated the third GL texture while
// the user was scrolling.  Keep one complete three-tile page plus a small tail;
// the count, resident-window eviction and upload pacing remain unchanged.
constexpr std::uint64_t kMaxPooledTextureBytes = 24ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaxPooledTextureCount = 12;
// gfxstream's glGenTextures command is a synchronous host-pipe round trip. Generating one name
// from the visible/prewarm upload path has been observed to park the renderer in qemu_pipe_read
// for tens of seconds while otherwise-complete pixels wait in the mailbox. Names have no backing
// storage until first bind/upload, so reserve a bounded batch with EGL initialization and refill it
// only after an exact retirement barrier has drained prior host GL work.
constexpr std::size_t kTextureNameReserveCount = 256;
constexpr std::size_t kTextureNameReserveRefillThreshold = 128;
constexpr std::size_t kHostUploadScratchInitialBytes = 8ULL * 1024ULL * 1024ULL;

#define RLOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define RLOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

void requestRollingConsumerPriority() noexcept {
    errno = 0;
    const int before = getpriority(PRIO_PROCESS, 0);
    const int beforeError = errno;
    // The std::thread inherits ReaderSurfaceProducer's urgent-display nice value. Keeping that
    // inherited -16 made a 20-60 ms SurfaceControl content transaction preempt the producer that
    // owns the next physical frame. The renderer is an asynchronous consumer now, so pin it to
    // its own display-band priority instead of preserving an accidentally more urgent parent.
    const int requestedNice = kRollingConsumerNice;
    errno = 0;
    const int requestedResult = beforeError == 0 && before == requestedNice
        ? 0
        : setpriority(PRIO_PROCESS, 0, requestedNice);
    const int requestedError = requestedResult == 0 ? 0 : errno;
    int fallbackError = 0;
    if (requestedResult != 0 &&
        (beforeError != 0 || before > kRollingConsumerNiceFallback)) {
        errno = 0;
        const int fallbackResult =
            setpriority(PRIO_PROCESS, 0, kRollingConsumerNiceFallback);
        fallbackError = fallbackResult == 0 ? 0 : errno;
    }
    errno = 0;
    const int effective = getpriority(PRIO_PROCESS, 0);
    const int effectiveError = errno;
    __android_log_print(
        effectiveError == 0 && effective <= kRollingConsumerNiceFallback
            ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
        kTag,
        "thread-priority role=rolling-egl requested=%d before=%d effective=%d "
        "urgentErrno=%d fallbackErrno=%d getErrno=%d",
        requestedNice, before, effective,
        requestedError, fallbackError, effectiveError);
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
    /** Stable immutable-pixel identity; separate from the JNI lease object's identity. */
    std::uint64_t contentIdentity = 0;
    bool hardwareBufferResource = false;
    bool cpuBufferResource = false;
    bool bitmapReferenceTracked = false;
    float pageTop = 0.0F;
    float pageHeight = 0.0F;
    jobject bitmap = nullptr;
    /** Borrowed from the pool owner while bitmapReferenceTracked fences slot retirement. */
    AHardwareBuffer* exactHardwareBuffer = nullptr;
    ExactCpuTileStorage* exactCpuBuffer = nullptr;
};

struct ProducerCpuSceneStorage {
    std::atomic<std::uint32_t> references{1};
    std::vector<FrameTile> tiles;
};

struct FrameCommand {
    FrameCommand() = default;
    FrameCommand(const FrameCommand&) = delete;
    FrameCommand& operator=(const FrameCommand&) = delete;
    FrameCommand(FrameCommand&& other) noexcept { *this = std::move(other); }
    FrameCommand& operator=(FrameCommand&& other) noexcept {
        if (this == &other) return *this;
        token = other.token;
        structureEpoch = other.structureEpoch;
        enqueuedNanos = other.enqueuedNanos;
        width = other.width;
        height = other.height;
        viewportSourceTop = other.viewportSourceTop;
        viewportSourceHeight = other.viewportSourceHeight;
        frameTimelineVsyncId = other.frameTimelineVsyncId;
        expectedPresentationTimeNanos = other.expectedPresentationTimeNanos;
        producerSceneId = other.producerSceneId;
        requiresGpuCompletionProof = other.requiresGpuCompletionProof;
        producerSceneGeometryOnly = other.producerSceneGeometryOnly;
        cpuBandPrecompositionRejected = other.cpuBandPrecompositionRejected;
        tiles = std::move(other.tiles);
        producerCpuSceneStorage = other.producerCpuSceneStorage;
        other.producerCpuSceneStorage = nullptr;
        return *this;
    }

    std::uint64_t token = 0;
    std::int64_t structureEpoch = 0;
    /** CLOCK_MONOTONIC timestamp immediately before this immutable command enters the mailbox. */
    std::int64_t enqueuedNanos = 0;
    int width = 0;
    int height = 0;
    int viewportSourceTop = 0;
    int viewportSourceHeight = 0;
    /** Real AVsyncId/expected-present pair from the display-owning Choreographer, or zero. */
    std::int64_t frameTimelineVsyncId = 0;
    std::int64_t expectedPresentationTimeNanos = 0;
    /** Collision-free Java producer-scene identity carried through band activation. */
    std::uint64_t producerSceneId = 0;
    bool requiresGpuCompletionProof = false;
    /** This command translates one already-installed immutable producer scene. */
    bool producerSceneGeometryOnly = false;
    /** Runtime CPU-lock failure routes this exact immutable scene to the GPU once. */
    bool cpuBandPrecompositionRejected = false;
    std::vector<FrameTile> tiles;
    ProducerCpuSceneStorage* producerCpuSceneStorage = nullptr;

    const std::vector<FrameTile>& tileView() const noexcept {
        // Geometry-only commands retain the immutable scene storage for resource lifetime but
        // carry a tiny borrowed tile vector when every page moved by one common Y translation.
        return !tiles.empty() || producerCpuSceneStorage == nullptr
            ? tiles
            : producerCpuSceneStorage->tiles;
    }
};

/**
 * One exact producer scene retained independently from queued frame commands.
 *
 * Host exact CPU tiles carry immutable native storage and collision-safe bitmap-ledger identities,
 * so a scroll inside the same rolling band only needs new viewport geometry. Keeping one exact
 * scene here avoids rebuilding the same JNI arrays and ownership graph on every MOVE while the
 * per-command clones below continue to fence retirement until each queued token is consumed.
 */
struct ProducerCpuScene {
    std::uint64_t id = 0;
    int width = 0;
    int height = 0;
    ProducerCpuSceneStorage* storage = nullptr;
};

struct CpuTileReadView {
    const std::uint8_t* pixels = nullptr;
    std::size_t strideBytes = 0;
    int pixelWidth = 0;
    int pageRowOrigin = 0;
    int rowCount = 0;
    int logicalRowCount = 0;
    jobject softwareBitmap = nullptr;
    AHardwareBuffer* hardwareBuffer = nullptr;
    bool hardwareBufferAcquired = false;
};

struct CpuReadHardwareBufferSymbols {
    using Get = int (*)(JNIEnv*, jobject, AHardwareBuffer**);
    using Describe = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
    using Lock = int (*)(
        AHardwareBuffer*, std::uint64_t, std::int32_t, const ARect*, void**);
    using Unlock = int (*)(AHardwareBuffer*, std::int32_t*);
    using Release = void (*)(AHardwareBuffer*);

    void* graphicsLibrary = nullptr;
    void* androidLibrary = nullptr;
    Get get = nullptr;
    Describe describe = nullptr;
    Lock lock = nullptr;
    Unlock unlock = nullptr;
    Release release = nullptr;

    bool valid() const noexcept {
        return get != nullptr && describe != nullptr && lock != nullptr &&
            unlock != nullptr && release != nullptr;
    }
};

const CpuReadHardwareBufferSymbols& cpuReadHardwareBufferSymbols() noexcept {
    static const CpuReadHardwareBufferSymbols symbols = [] {
        CpuReadHardwareBufferSymbols result{};
        result.graphicsLibrary = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
        result.androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (result.graphicsLibrary != nullptr) {
            result.get = reinterpret_cast<CpuReadHardwareBufferSymbols::Get>(
                dlsym(result.graphicsLibrary, "AndroidBitmap_getHardwareBuffer"));
        }
        if (result.androidLibrary != nullptr) {
            result.describe = reinterpret_cast<CpuReadHardwareBufferSymbols::Describe>(
                dlsym(result.androidLibrary, "AHardwareBuffer_describe"));
            result.lock = reinterpret_cast<CpuReadHardwareBufferSymbols::Lock>(
                dlsym(result.androidLibrary, "AHardwareBuffer_lock"));
            result.unlock = reinterpret_cast<CpuReadHardwareBufferSymbols::Unlock>(
                dlsym(result.androidLibrary, "AHardwareBuffer_unlock"));
            result.release = reinterpret_cast<CpuReadHardwareBufferSymbols::Release>(
                dlsym(result.androidLibrary, "AHardwareBuffer_release"));
        }
        return result;
    }();
    return symbols;
}

struct AppliedFrameTileSignature {
    TileKey key{};
    int sourceTop = 0;
    int sourceBottom = 0;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int bitmapIdentity = 0;
    std::uint64_t contentIdentity = 0;
    float pageTop = 0.0F;
    float pageHeight = 0.0F;
};

struct TextureTile {
    GLuint texture = 0;
    EGLImageKHR importedImage = EGL_NO_IMAGE_KHR;
    std::uint64_t contentIdentity = 0;
    /** True when contentIdentity is manifest/source-span based rather than a 32-bit object hash. */
    bool stableContentIdentity = false;
    int width = 0;
    int height = 0;
    int storageWidth = 0;
    int storageHeight = 0;
    float textureScaleX = 1.0F;
    float textureScaleY = 1.0F;
    std::uint64_t bytes = 0;
    std::uint64_t lastUsedFrame = 0;
};

struct AttachCommand {
    ANativeWindow* window = nullptr;
    ASurfaceControl* providedChildSurface = nullptr;
    ASurfaceControl* providedGeometrySurface = nullptr;
    int width = 0;
    int height = 0;
    std::uint64_t epoch = 0;
    std::int64_t refreshPeriodNanos = kDefaultRefreshPeriodNanos;
};

void releaseProvidedAttachSurfaces(AttachCommand& command) noexcept {
    ASurfaceControl* child = command.providedChildSurface;
    ASurfaceControl* geometry = command.providedGeometrySurface;
    command.providedChildSurface = nullptr;
    command.providedGeometrySurface = nullptr;
    if (child != nullptr) releaseSurfaceControlReference(child);
    if (geometry != nullptr && geometry != child) {
        releaseSurfaceControlReference(geometry);
    }
}

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
    int geometryBaseSourceTop = 0;
    bool cpuComposed = false;
    bool occupied = false;
};

/**
 * One off-screen band snapshot is composed on a dedicated CPU worker while the renderer owner
 * continues publishing crop-only SurfaceControl transactions from the current exact buffer.
 * The worker never mutates pool/backend ownership state; it only maps, writes and unmaps the
 * renderer-reserved PRECOMPOSING target, then hands the real completion fence back to the owner.
 */
struct CpuBandPrecomposeJob {
    FrameCommand frame{};
    ntk::present::HardwareBufferRenderTargetPool::RenderTarget* target = nullptr;
    int completionFenceFd = -1;
    std::int64_t beginNanos = 0;
    std::int64_t lockEndNanos = 0;
    std::int64_t renderEndNanos = 0;
    std::int64_t finishEndNanos = 0;
    bool occupied = false;
    bool running = false;
    bool done = false;
    bool success = false;
    bool presentOnCompletion = false;
};

struct ReadyCpuBand {
    FrameCommand composedFrame{};
    ntk::present::HardwareBufferRenderTargetPool::RenderTarget* target = nullptr;
    std::int64_t beginNanos = 0;
    std::int64_t lockEndNanos = 0;
    std::int64_t renderEndNanos = 0;
    std::int64_t finishEndNanos = 0;
    bool occupied = false;
    bool presentOnCompletion = false;
};

/**
 * One ahead-of-viewport GPU band composed in a shared EGL context. The renderer reserves the
 * target and transfers the immutable frame, then remains free to publish crop-only transactions
 * from the currently displayed band. Pool publication and SurfaceControl transactions stay on
 * the renderer owner after this worker has exported the exact acquire fence.
 */
struct GpuBandFenceJob {
    FrameCommand composedFrame{};
    ntk::present::HardwareBufferRenderTargetPool::RenderTarget* target = nullptr;
    ntk::present::SurfaceControlPresentBackend::PendingGpuFenceExport pending{};
    ntk::present::SurfaceControlPresentBackend::FinishedGpuFenceExport finished{};
    std::int64_t beginNanos = 0;
    std::int64_t bindEndNanos = 0;
    std::int64_t uploadBeginNanos = 0;
    std::int64_t uploadEndNanos = 0;
    std::int64_t renderBeginNanos = 0;
    std::int64_t renderEndNanos = 0;
    bool occupied = false;
    bool running = false;
    bool done = false;
    bool success = false;
    bool gpuSubmissionIssued = false;
    bool presentOnCompletion = false;
    const char* failureStage = nullptr;
};

struct ReadyGpuBand {
    FrameCommand composedFrame{};
    ntk::present::HardwareBufferRenderTargetPool::RenderTarget* target = nullptr;
    std::int64_t beginNanos = 0;
    std::int64_t bindEndNanos = 0;
    std::int64_t uploadBeginNanos = 0;
    std::int64_t uploadEndNanos = 0;
    std::int64_t renderBeginNanos = 0;
    std::int64_t renderEndNanos = 0;
    std::int64_t fenceBeginNanos = 0;
    std::int64_t fenceEndNanos = 0;
    bool occupied = false;
    bool presentOnCompletion = false;
};

struct HostFrontSubmission {
    std::uint64_t token = 0;
    std::int64_t submittedNanos = 0;
};

struct WindowPresentationCallback {
    std::uint64_t token = 0;
    std::int64_t completedNanos = 0;
    std::int64_t observedNanos = 0;
    int presentationKind = 0;
};

class RollingRenderer final {
public:
    RollingRenderer(
            JNIEnv* env,
            jobject callback,
            std::int64_t creationGeneration,
            bool directWifiTextureProfile,
            bool hostGpuEmulator)
        : creationGeneration_(creationGeneration) {
        if (env == nullptr || callback == nullptr || creationGeneration_ <= 0 ||
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
        geometryMethod_ = env->GetMethodID(
            callbackClass, "onNtkRollingGeometryFrameRequested", "(JIIIJJ)Z");
        bandActivatedMethod_ = env->GetMethodID(
            callbackClass, "onNtkRollingBandActivated", "(JJJ)V");
        precomposedReadyMethod_ = env->GetMethodID(
            callbackClass, "onNtkRollingPrecomposedBandReady", "()V");
        droppedMethod_ = env->GetMethodID(callbackClass, "onNtkRollingFrameDropped", "(JI)V");
        fatalMethod_ = env->GetMethodID(
            callbackClass, "onNtkRollingRendererFatal", "(JJLjava/lang/String;)V");
        env->DeleteLocalRef(callbackClass);
        if (latchedMethod_ == nullptr || geometryMethod_ == nullptr ||
            bandActivatedMethod_ == nullptr ||
            precomposedReadyMethod_ == nullptr || droppedMethod_ == nullptr ||
            fatalMethod_ == nullptr || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failed_.store(true, std::memory_order_release);
            return;
        }
        // Publish the immutable transport profile before the owner thread starts. The host
        // direct-tile presenter needs no EGL context; learning this profile through a later JNI
        // setter made the owner initialize an unused shader/texture backend and contend with HWUI
        // for roughly a second on every cold reader entry.
        directWifiTextureProfile_.store(directWifiTextureProfile, std::memory_order_release);
        hostGpuEmulatorSurfaceProfile_.store(hostGpuEmulator, std::memory_order_release);
        // Never enter ART from the EGL owner. A concurrent/NativeAlloc GC can suspend a JNI
        // callback for hundreds of milliseconds even after the buffer was already handed to the
        // compositor. Keeping that call on the renderer made Java proof bookkeeping part of the
        // next frame's critical path. This fixed SPSC lane preserves every token and timestamp in
        // order while a separate ordinary-priority thread performs the Java callback.
        presentationCallbackThread_ =
            std::thread(&RollingRenderer::presentationCallbackLoop, this);
        gpuFenceThread_ = std::thread(&RollingRenderer::gpuFenceLoop, this);
        cpuComposeThread_ = std::thread(&RollingRenderer::cpuComposeLoop, this);
        thread_ = std::thread(&RollingRenderer::run, this);
    }

    ~RollingRenderer() { (void) destroy(); }

    bool valid() const noexcept {
        return callback_ != nullptr && !failed_.load(std::memory_order_acquire);
    }

    bool hasFailed() const noexcept {
        return failed_.load(std::memory_order_acquire);
    }

    bool prepare(int width, int height) {
        if (width <= 0 || height <= 0 ||
            stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return false;
        // prepare is consumed by the same owner turn as attach/detach and retires queued frames.
        // Close producer admission before waking that owner so a new Kotlin token cannot enter the
        // queue and then be retired as if it belonged to the preceding Surface lifecycle.
        surfaceAttached_ = false;
        pendingPrepare_ = {width, height};
        preparePending_ = true;
        pipelineQuiescent_.store(false, std::memory_order_release);
        condition_.notify_one();
        return true;
    }

    bool attach(ANativeWindow* window, ASurfaceControl* providedChildSurface,
                ASurfaceControl* providedGeometrySurface,
                int width, int height,
                std::uint64_t epoch, std::int64_t refreshPeriodNanos) {
        if (window == nullptr || width <= 0 || height <= 0 || epoch == 0 ||
            stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return false;
        if (latestAcceptedAttachEpoch_ > epoch) return false;
        // nativeAttach() is an enqueue, not an attachment barrier. Fence submit() synchronously;
        // the worker republishes readiness only after this exact lifecycle turn has completed.
        surfaceAttached_ = false;
        if (pendingAttach_.window != nullptr) ANativeWindow_release(pendingAttach_.window);
        releaseProvidedAttachSurfaces(pendingAttach_);
        pendingAttach_ = {
            window, providedChildSurface, providedGeometrySurface, width, height, epoch,
            refreshPeriodNanos > 0 ? refreshPeriodNanos : kDefaultRefreshPeriodNanos
        };
        latestAcceptedAttachEpoch_ = epoch;
        attachPending_ = true;
        detachPending_ = false;
        pipelineQuiescent_.store(false, std::memory_order_release);
        condition_.notify_one();
        return true;
    }

    void detach(std::uint64_t epoch) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return;
        // Surface callbacks can be delivered after a replacement Surface has already published a
        // newer attach epoch. Detach is exact-identity retirement: an older callback must not
        // cancel that newer pending/in-flight/active attachment.
        if (epoch == 0 || epoch != latestAcceptedAttachEpoch_) return;
        surfaceAttached_ = false;
        if (pendingAttach_.window != nullptr) {
            ANativeWindow_release(pendingAttach_.window);
            releaseProvidedAttachSurfaces(pendingAttach_);
            pendingAttach_ = {};
        }
        attachPending_ = false;
        detachPending_ = true;
        detachEpoch_ = epoch;
        pipelineQuiescent_.store(false, std::memory_order_release);
        condition_.notify_one();
    }

    bool isSurfaceAttached(std::uint64_t epoch) noexcept {
        if (epoch == 0) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return surfaceAttached_ && latestAcceptedAttachEpoch_ == epoch &&
            !stopped_.load(std::memory_order_acquire) &&
            !failed_.load(std::memory_order_acquire);
    }

    bool hasFrameMailboxCapacity() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return surfaceAttached_ && frames_.size() < kMaxQueuedFrames &&
            !stopped_.load(std::memory_order_acquire) &&
            !failed_.load(std::memory_order_acquire);
    }

    std::int64_t submit(JNIEnv* env, std::uint64_t token, std::int64_t structureEpoch,
                        int width, int height, int viewportSourceTop,
                        int viewportSourceHeight, std::int64_t frameTimelineVsyncId,
                        std::int64_t expectedPresentationTimeNanos,
                        bool requiresGpuCompletionProof,
                        std::uint64_t producerSceneId,
                        int bitmapCount, jintArray tileData,
                        jfloatArray geometryData, jobjectArray bitmaps) {
        if (env == nullptr || token == 0 || structureEpoch <= 0 || width <= 0 || height <= 0 ||
            viewportSourceTop < 0 || viewportSourceHeight <= 0 ||
            viewportSourceTop > height - viewportSourceHeight ||
            frameTimelineVsyncId < 0 || expectedPresentationTimeNanos < 0 ||
            (frameTimelineVsyncId > 0 && expectedPresentationTimeNanos == 0) ||
            bitmapCount <= 0 || bitmapCount > 4096 ||
            tileData == nullptr || geometryData == nullptr || bitmaps == nullptr ||
            stopped_.load(std::memory_order_acquire) || failed_.load(std::memory_order_acquire)) {
            return -1;
        }
        const jsize tileInts = env->GetArrayLength(tileData);
        const jsize geometryFloats = env->GetArrayLength(geometryData);
        const jsize bitmapCapacity = env->GetArrayLength(bitmaps);
        const jsize requiredTileInts = static_cast<jsize>(
            static_cast<std::size_t>(bitmapCount) * kTileIntegerStride);
        const jsize requiredGeometryFloats = static_cast<jsize>(bitmapCount * 2);
        if (bitmapCapacity < bitmapCount || tileInts < requiredTileInts ||
            geometryFloats < requiredGeometryFloats) return -1;

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!surfaceAttached_) return -1;
        }

        std::vector<jint> integers(static_cast<std::size_t>(requiredTileInts));
        std::vector<jfloat> geometry(static_cast<std::size_t>(requiredGeometryFloats));
        env->GetIntArrayRegion(tileData, 0, requiredTileInts, integers.data());
        env->GetFloatArrayRegion(
            geometryData, 0, requiredGeometryFloats, geometry.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }

        FrameCommand command;
        command.token = token;
        command.structureEpoch = structureEpoch;
        command.width = width;
        command.height = height;
        command.viewportSourceTop = viewportSourceTop;
        command.viewportSourceHeight = viewportSourceHeight;
        command.frameTimelineVsyncId = frameTimelineVsyncId;
        command.expectedPresentationTimeNanos = expectedPresentationTimeNanos;
        command.producerSceneId = producerSceneId;
        command.requiresGpuCompletionProof = requiresGpuCompletionProof;
        command.tiles.reserve(static_cast<std::size_t>(bitmapCount));
        for (jsize index = 0; index < bitmapCount; ++index) {
            const std::size_t i = static_cast<std::size_t>(index);
            const std::size_t ib = i * kTileIntegerStride;
            const std::size_t gb = i * 2U;
            const jint resourceKind = integers[ib + 7U];
            jobject global = nullptr;
            // Host exact CPU storage has a stable native handle and an identity-counted
            // retirement ledger. A second ART GlobalRef to its 1x1 lease token contributes no
            // pixel lifetime authority, yet creating/deleting one for every tile on every MOVE
            // dominates producer-frame time. Other resource kinds retain their object proof.
            if (resourceKind != 2) {
                jobject local = env->GetObjectArrayElement(bitmaps, index);
                if (local == nullptr || env->ExceptionCheck()) {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    if (local != nullptr) env->DeleteLocalRef(local);
                    releaseFrame(env, command);
                    return -1;
                }
                global = env->NewGlobalRef(local);
                env->DeleteLocalRef(local);
                if (global == nullptr) {
                    releaseFrame(env, command);
                    return -1;
                }
            }
            FrameTile tile;
            tile.key = {structureEpoch, integers[ib], integers[ib + 1U]};
            tile.sourceTop = integers[ib + 2U];
            tile.sourceBottom = integers[ib + 3U];
            tile.sourceWidth = integers[ib + 4U];
            tile.sourceHeight = integers[ib + 5U];
            tile.bitmapIdentity = integers[ib + 6U];
            tile.contentIdentity = contentIdentityFromHalves(
                integers[ib + 10U], integers[ib + 11U]);
            tile.hardwareBufferResource = integers[ib + 7U] == 1;
            tile.cpuBufferResource = integers[ib + 7U] == 2;
            if (tile.hardwareBufferResource) {
                const auto bits = static_cast<std::uint64_t>(
                        static_cast<std::uint32_t>(integers[ib + 8U])) |
                    (static_cast<std::uint64_t>(
                        static_cast<std::uint32_t>(integers[ib + 9U])) << 32U);
                tile.exactHardwareBuffer = reinterpret_cast<AHardwareBuffer*>(
                    static_cast<std::uintptr_t>(bits));
                if (tile.exactHardwareBuffer == nullptr) {
                    command.tiles.push_back(tile);
                    releaseFrame(env, command);
                    return -1;
                }
            } else if (tile.cpuBufferResource) {
                tile.exactCpuBuffer = exactCpuTileFromHalves(
                    integers[ib + 8U], integers[ib + 9U]);
                if (!validExactCpuTile(tile.exactCpuBuffer)) {
                    command.tiles.push_back(tile);
                    releaseFrame(env, command);
                    return -1;
                }
                cpuExactStorageProfile_.store(true, std::memory_order_release);
            }
            tile.pageTop = geometry[gb];
            tile.pageHeight = geometry[gb + 1U];
            tile.bitmap = global;
            if (tile.key.page < 0 || tile.key.slot < 0 || tile.sourceTop < 0 ||
                tile.sourceBottom <= tile.sourceTop || tile.sourceWidth <= 0 ||
                tile.sourceHeight < tile.sourceBottom || tile.pageHeight <= 0.0F ||
                (integers[ib + 7U] < 0 || integers[ib + 7U] > 2) ||
                (!tile.hardwareBufferResource && !tile.cpuBufferResource &&
                 (integers[ib + 8U] != 0 || integers[ib + 9U] != 0))) {
                command.tiles.push_back(tile);
                releaseFrame(env, command);
                return -1;
            }
            command.tiles.push_back(tile);
        }

        // Direct-tile presentation cannot make progress until every visible exact CPU tile has
        // its compositor mirror. Do not consume the one-command native mailbox with a token whose
        // only possible result is transient backpressure. Kotlin retains the authoritative page
        // and retries after the mirror publisher wakes; accepting it here used to leave the frame
        // in frames_ forever while Surface believed the page was already drawable.
        if (hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
            directTilePresenter_.attached() &&
            frameHasVisiblePendingDirectTileMirror(command)) {
            releaseFrame(env, command);
            return -2;
        }

        // One ledger critical section per immutable scene replaces one lock/unlock pair per tile.
        // This is still exact per-identity ownership; it only removes avoidable producer jitter.
        if (!retainFrameBitmapReferences(command.tiles)) {
            RLOGE("bitmap reference ledger rejected visible frame");
            releaseFrame(env, command);
            return -1;
        }

        if (producerSceneId != 0) {
            if (!installProducerCpuScene(env, producerSceneId, command)) {
                releaseFrame(env, command);
                return -1;
            }
        }

        return enqueueFrame(env, std::move(command));
    }

    std::int64_t submitProducerGeometry(
            JNIEnv* env,
            std::uint64_t producerSceneId,
            std::uint64_t token,
            std::int64_t structureEpoch,
            int width,
            int height,
            int viewportSourceTop,
            int viewportSourceHeight,
            float producerSceneTranslationY,
            std::int64_t frameTimelineVsyncId,
            std::int64_t expectedPresentationTimeNanos,
            bool requiresGpuCompletionProof) {
        if (env == nullptr || producerSceneId == 0 || token == 0 || structureEpoch <= 0 ||
            width <= 0 || height <= 0 || viewportSourceTop < 0 ||
            viewportSourceHeight <= 0 ||
            viewportSourceTop > height - viewportSourceHeight ||
            !std::isfinite(producerSceneTranslationY) ||
            frameTimelineVsyncId < 0 || expectedPresentationTimeNanos < 0 ||
            (frameTimelineVsyncId > 0 && expectedPresentationTimeNanos == 0) ||
            stopped_.load(std::memory_order_acquire) || failed_.load(std::memory_order_acquire)) {
            return -1;
        }

        FrameCommand command;
        command.token = token;
        command.structureEpoch = structureEpoch;
        command.width = width;
        command.height = height;
        command.viewportSourceTop = viewportSourceTop;
        command.viewportSourceHeight = viewportSourceHeight;
        command.frameTimelineVsyncId = frameTimelineVsyncId;
        command.expectedPresentationTimeNanos = expectedPresentationTimeNanos;
        command.producerSceneId = producerSceneId;
        command.requiresGpuCompletionProof = requiresGpuCompletionProof;
        command.producerSceneGeometryOnly = true;
        {
            // The cached scene owns a ledger lease while this copy is made. Retain all command
            // leases before releasing the cache mutex, so replacement can never expose a raw
            // exact-storage pointer between the two ownership domains.
            std::lock_guard<std::mutex> sceneLock(producerSceneMutex_);
            if (producerCpuScene_.id != producerSceneId ||
                producerCpuScene_.width != width || producerCpuScene_.height != height ||
                producerCpuScene_.storage == nullptr ||
                producerCpuScene_.storage->tiles.empty()) {
                return -1;
            }
            retainProducerCpuSceneStorage(producerCpuScene_.storage);
            command.producerCpuSceneStorage = producerCpuScene_.storage;
            if (producerSceneTranslationY != 0.0F) {
                command.tiles = producerCpuScene_.storage->tiles;
                for (auto& tile : command.tiles) {
                    tile.pageTop += producerSceneTranslationY;
                }
            }
        }
        // SurfaceControl apply can enter Binder for an unbounded interval even for crop-only
        // geometry. Keep the producer non-blocking and let the dedicated display owner apply it.
        return enqueueFrame(env, std::move(command));
    }

private:
    std::int64_t enqueueFrame(JNIEnv* env, FrameCommand&& command) {

        command.enqueuedNanos = nowNanos();
        bool backpressured = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!surfaceAttached_ || stopped_.load(std::memory_order_acquire) ||
                failed_.load(std::memory_order_acquire)) {
                releaseFrame(env, command);
                return -1;
            }
            // Kotlin owns newest-position coalescing and checks this exact bounded capacity before
            // sealing a proof. A race must reject as transient backpressure; replacing an already
            // accepted token makes a real frame disappear from compositor accounting and turns a
            // slow SurfaceFlinger interval into an application-reported dropped frame.
            if (frames_.size() >= kMaxQueuedFrames) {
                backpressured = true;
            } else {
                frames_.push_back(std::move(command));
                pipelineQuiescent_.store(false, std::memory_order_release);
            }
        }
        if (backpressured) {
            releaseFrame(env, command);
            return -2;
        }
        ++acceptedFrames_;
        condition_.notify_one();
        return 0;
    }

public:

    /**
     * Enqueues decoded pixels for texture upload only. Unlike submit(), this command carries no
     * viewport geometry or frame token and has no path to the SurfaceControl backend.
     */
    bool prewarm(JNIEnv* env, std::int64_t structureEpoch,
                 jintArray tileData, jobjectArray bitmaps,
                 bool completeSceneSnapshot) {
        if (env == nullptr || structureEpoch <= 0 || tileData == nullptr || bitmaps == nullptr ||
            stopped_.load(std::memory_order_acquire) || failed_.load(std::memory_order_acquire)) {
            return false;
        }
        const jsize tileInts = env->GetArrayLength(tileData);
        const jsize bitmapCount = env->GetArrayLength(bitmaps);
        if (bitmapCount <= 0 || tileInts != static_cast<jsize>(
                static_cast<std::size_t>(bitmapCount) * kTileIntegerStride)) return false;

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
            const std::size_t base =
                static_cast<std::size_t>(index) * kTileIntegerStride;
            FrameTile tile;
            tile.key = {structureEpoch, integers[base], integers[base + 1U]};
            tile.sourceTop = integers[base + 2U];
            tile.sourceBottom = integers[base + 3U];
            tile.sourceWidth = integers[base + 4U];
            tile.sourceHeight = integers[base + 5U];
            tile.bitmapIdentity = integers[base + 6U];
            tile.contentIdentity = contentIdentityFromHalves(
                integers[base + 10U], integers[base + 11U]);
            tile.hardwareBufferResource = integers[base + 7U] == 1;
            tile.cpuBufferResource = integers[base + 7U] == 2;
            if (tile.hardwareBufferResource) {
                const auto bits = static_cast<std::uint64_t>(
                        static_cast<std::uint32_t>(integers[base + 8U])) |
                    (static_cast<std::uint64_t>(
                        static_cast<std::uint32_t>(integers[base + 9U])) << 32U);
                tile.exactHardwareBuffer = reinterpret_cast<AHardwareBuffer*>(
                    static_cast<std::uintptr_t>(bits));
                if (tile.exactHardwareBuffer == nullptr) {
                    incoming.push_back(tile);
                    for (auto& owned : incoming) releaseTile(env, owned);
                    return false;
                }
            } else if (tile.cpuBufferResource) {
                tile.exactCpuBuffer = exactCpuTileFromHalves(
                    integers[base + 8U], integers[base + 9U]);
                if (!validExactCpuTile(tile.exactCpuBuffer)) {
                    incoming.push_back(tile);
                    for (auto& owned : incoming) releaseTile(env, owned);
                    return false;
                }
                cpuExactStorageProfile_.store(true, std::memory_order_release);
            }
            tile.bitmap = global;
            if (tile.key.page < 0 || tile.key.slot < 0 || tile.sourceTop < 0 ||
                tile.sourceBottom <= tile.sourceTop || tile.sourceWidth <= 0 ||
                tile.sourceHeight < tile.sourceBottom ||
                (integers[base + 7U] < 0 || integers[base + 7U] > 2) ||
                (!tile.hardwareBufferResource && !tile.cpuBufferResource &&
                 (integers[base + 8U] != 0 || integers[base + 9U] != 0))) {
                incoming.push_back(tile);
                for (auto& owned : incoming) releaseTile(env, owned);
                return false;
            }
            if (!retainTileBitmapReference(tile)) {
                RLOGE("bitmap reference ledger rejected prewarm identity=%d", tile.bitmapIdentity);
                releaseTile(env, tile);
                for (auto& owned : incoming) releaseTile(env, owned);
                return false;
            }
            incoming.push_back(tile);
        }

        const bool cpuCompositorOwnsThesePixels = backend_.cpuComposerOnly();
        if (cpuCompositorOwnsThesePixels) {
            // A CPU-only presentation lane copies exact decoded RGBA pixels directly into its
            // final AHardwareBuffer. Retaining a second GL texture copy would double resident
            // memory. Drop only optional renderer-owned prewarm work;
            // Kotlin keeps the immutable decoded originals and visible commands retain exact
            // bitmap-ledger leases until their compositor transaction completes.
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (stopped_.load(std::memory_order_acquire) ||
                    failed_.load(std::memory_order_acquire)) {
                    for (auto& tile : incoming) releaseTile(env, tile);
                    return false;
                }
                for (auto& queued : prewarmTiles_) releaseTile(env, queued);
                prewarmTiles_.clear();
                queuedPrewarmEpoch_ = structureEpoch;
                sealedFullScenePrewarmEpoch_ = 0;
                ++prewarmQueueRevision_;
            }
            for (auto& tile : incoming) releaseTile(env, tile);
            return true;
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
            // Snapshot cardinality is not authority. A direct-Wi-Fi current runway can contain
            // more than the ordinary twelve tiles without being a complete immutable scene.
            // Only an explicit JNI capability may seal an epoch against later resident updates.
            const bool fullSceneSnapshot = completeSceneSnapshot;
            const bool ignoreIncoming = sealedFullScenePrewarmEpoch_ == structureEpoch;
            // A worker may have popped one entry while this producer prepares a newer same-epoch
            // snapshot. Any authoritative replacement invalidates that popped entry's restore.
            if (!ignoreIncoming) ++prewarmQueueRevision_;
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
                // Publish the pair with an invalid epoch in between. The renderer reads epoch,
                // bytes, epoch and accepts only one stable exact epoch, so a new snapshot can
                // never pair its bytes with the predecessor generation.
                fullSceneTextureBudgetEpoch_.store(0, std::memory_order_seq_cst);
                fullSceneTextureBudgetBytes_.store(
                    sceneBytesValid ? sceneBytes : 0, std::memory_order_seq_cst);
                fullSceneTextureBudgetEpoch_.store(
                    structureEpoch, std::memory_order_seq_cst);
                RLOGI(
                    "texture full-scene budget epoch=%lld tiles=%d bytes=%llu floor=%llu valid=%d",
                    static_cast<long long>(structureEpoch), static_cast<int>(bitmapCount),
                    static_cast<unsigned long long>(
                        fullSceneTextureBudgetBytes_.load(std::memory_order_seq_cst)),
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
                    if (queued->contentIdentity == tile.contentIdentity) {
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
                tile.bitmapReferenceTracked = false;
                // The pointer is borrowed under the transferred bitmap-ledger lease. Clear the
                // producer copy alongside that lease so only the queue represents ownership.
                tile.exactHardwareBuffer = nullptr;
                tile.exactCpuBuffer = nullptr;
                accepted = true;
            }
            if (accepted) pipelineQuiescent_.store(false, std::memory_order_release);
        }
        for (auto& tile : incoming) releaseTile(env, tile);
        if (accepted) condition_.notify_one();
        return accepted;
    }

    /**
     * Returns true only when the caller owns final deletion and terminal backend cleanup proved
     * that no native callback can still dereference this renderer.
     *
     * A renderer fatal and a concurrent lifecycle reset can both reach nativeDestroy for the same
     * raw handle. The former implementation let the losing caller return from destroy() and still
     * execute `delete`, so std::thread's destructor observed the winning caller's join in progress
     * and terminated the process. Exactly one caller now performs both join and deletion.
     */
    bool destroy() {
        bool expected = false;
        if (!destroyed_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
            return false;
        }
        stopped_.store(true, std::memory_order_release);
        pipelineQuiescent_.store(false, std::memory_order_release);
        condition_.notify_all();
        cpuComposeCondition_.notify_all();
        gpuFenceCondition_.notify_all();
        if (thread_.joinable()) thread_.join();
        presentationProducerStopped_.store(true, std::memory_order_release);
        presentationCallbackCondition_.notify_all();
        presentationCallbackSpaceCondition_.notify_all();
        if (presentationCallbackThread_.joinable()) presentationCallbackThread_.join();
        cpuComposeCondition_.notify_all();
        if (cpuComposeThread_.joinable()) cpuComposeThread_.join();
        gpuFenceCondition_.notify_all();
        if (gpuFenceThread_.joinable()) gpuFenceThread_.join();

        JNIEnv* env = attachEnv();
        if (env != nullptr && callback_ != nullptr) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
        return terminalCleanupComplete_.load(std::memory_order_acquire);
    }

    void setPrewarmPaused(bool paused) noexcept {
        // ACTION_DOWN/UP calls this JNI method on main while the renderer may be replacing a
        // resident prewarm snapshot under mutex_. Waiting for that owner used to hold the Android
        // input Looper for 100-200 ms and starve both MOVE delivery and physical frame production.
        // Publish one lock-free command instead. The requested bit closes prewarm admission
        // immediately; the renderer owner applies all dependent policy fields under its mutex.
        requestedPrewarmPaused_.store(paused, std::memory_order_release);
        prewarmPauseCommandPending_.store(true, std::memory_order_release);
        pipelineQuiescent_.store(false, std::memory_order_release);
        delayIdleTextureRetirementUntil(
            nowNanos() + kTextureRetirementIdleQuietNanos);
        condition_.notify_one();
    }

    /** O(1) snapshot published only by the renderer owner at an exact idle wait boundary. */
    bool isQuiescent() noexcept {
        if (stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire) ||
            prewarmPauseCommandPending_.load(std::memory_order_acquire) ||
            !pipelineQuiescent_.load(std::memory_order_acquire)) {
            return false;
        }
        // A backend callback publishes its event before invoking wake(). Recheck the event queue
        // after the owner snapshot so a wake racing the owner's true store cannot create a false
        // idle observation. This remains fixed-cost and is used only by test/diagnostic polling.
        return !backend_.hasPendingEvent() &&
            !failed_.load(std::memory_order_acquire) &&
            pipelineQuiescent_.load(std::memory_order_acquire);
    }

    /**
     * Returns an exact point-in-time ownership mask for Bitmap JNI global references.
     *
     * Kotlin serializes this query with new submit/prewarm handoffs. Native releases linearize on
     * the independent ledger mutex, so Java may recycle an outgoing identity as soon as its own
     * bit is false without waiting for unrelated frames, uploads, or compositor evidence.
     */
    bool bitmapReferenceMask(
            const jint* identities, std::size_t count, jboolean* result) noexcept {
        if (identities == nullptr || result == nullptr) return false;
        // Retirement is opportunistic maintenance. It may run while the display owner is
        // releasing the previous command, so never wait behind that owner and never put the next
        // physical frame behind a retirement query. A null JNI result tells Kotlin to preserve
        // every hold and retry at the next bounded maintenance edge.
        std::unique_lock<std::mutex> lock(bitmapReferenceMutex_, std::try_to_lock);
        if (!lock.owns_lock()) return false;
        for (std::size_t index = 0; index < count; ++index) {
            result[index] = bitmapReferenceLedger_.references(identities[index])
                ? JNI_TRUE
                : JNI_FALSE;
        }
        return true;
    }

    /**
     * Retires only cache-only work that has not crossed onto the renderer owner thread.
     *
     * Java calls this after an exact page drawable has left current/pending Surface state. A
     * paused prewarm queue can otherwise retain that Bitmap's GlobalRef indefinitely while the
     * host exact-storage pool waits for the backing slot. Frame commands are deliberately not
     * touched: their presentation/replacement callbacks remain the liveness owner for the visible
     * surface. A tile already popped by run() is also left alone and remains visible in the
     * reference ledger until releaseTile() completes.
     */
    int discardQueuedPrewarmBitmaps(
            JNIEnv* env,
            const std::vector<jobject>& bitmaps,
            const std::vector<jint>& bitmapIdentities) {
        if (env == nullptr || bitmaps.empty()) return 0;
        if (bitmapIdentities.size() != bitmaps.size()) return -1;
        std::vector<FrameTile> retired;
        std::size_t remaining = 0;
        {
            std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
            if (!lock.owns_lock()) return -2;
            auto iterator = prewarmTiles_.begin();
            while (iterator != prewarmTiles_.end()) {
                bool matches = false;
                for (std::size_t candidate = 0; candidate < bitmaps.size(); ++candidate) {
                    // identityHashCode is only a prefilter. Preserve IsSameObject as the exact
                    // collision-safe ownership proof while avoiding the former queue x cohort
                    // JNI cross-product for all ordinary nonmatching tiles.
                    if (bitmapIdentities[candidate] != iterator->bitmapIdentity) continue;
                    if (bitmaps[candidate] != nullptr &&
                        env->IsSameObject(iterator->bitmap, bitmaps[candidate]) == JNI_TRUE) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    ++iterator;
                    continue;
                }
                retired.push_back(std::move(*iterator));
                iterator = prewarmTiles_.erase(iterator);
            }
            remaining = prewarmTiles_.size();
            if (!retired.empty()) {
                // A worker may have popped an entry immediately before this transaction. It may
                // finish its current upload, but it must not restore that stale entry afterward.
                ++prewarmQueueRevision_;
                if (sealedFullScenePrewarmEpoch_ == queuedPrewarmEpoch_) {
                    sealedFullScenePrewarmEpoch_ = 0;
                    fullSceneTextureBudgetEpoch_.store(0, std::memory_order_seq_cst);
                    fullSceneTextureBudgetBytes_.store(0, std::memory_order_seq_cst);
                }
                pipelineQuiescent_.store(false, std::memory_order_release);
            }
        }
        for (auto& tile : retired) releaseTile(env, tile);
        if (!retired.empty()) {
            RLOGI(
                "queued prewarm references retired count=%zu remaining=%zu",
                retired.size(), remaining);
            condition_.notify_one();
        }
        return static_cast<int>(std::min<std::size_t>(
            retired.size(), static_cast<std::size_t>(std::numeric_limits<int>::max())));
    }

    /**
     * Removes stale producer commands that have not crossed onto the renderer owner thread.
     *
     * Surface clearing has already made every matching Bitmap unavailable to future submissions.
     * Keeping an older command in frames_ can therefore never produce a valid successor; it only
     * keeps the host exact-storage slot alive while the decode that would replace it waits for
     * that same slot. protectedToken is the sole command allowed to be in the producer's
     * submit-to-proof seam. The applied buffer, the owner's local command and pendingDirectFrame_
     * are deliberately outside this queue and remain protected by the reference ledger.
     */
    bool tryDiscardQueuedFramesWithRetiredBitmaps(
            JNIEnv* env,
            const std::vector<jobject>& bitmaps,
            const std::vector<jint>& bitmapIdentities,
            std::uint64_t protectedToken,
            std::vector<std::uint64_t>* tokens) {
        if (tokens == nullptr) return false;
        tokens->clear();
        if (env == nullptr || bitmaps.empty() || bitmapIdentities.size() != bitmaps.size()) {
            return true;
        }
        std::deque<FrameCommand> retired;
        ProducerCpuScene retiredProducerScene;
        {
            // Retirement is maintenance, never frame admission. Waiting here behind draw/present
            // made the Java retirement worker later take stateLock tens of milliseconds after its
            // intended deadline and invert the physical input/render priority. A failed try keeps
            // every Java/native reference alive and lets the existing retirement scheduler retry.
            std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
            if (!lock.owns_lock()) return false;
            auto command = frames_.begin();
            while (command != frames_.end()) {
                if (protectedToken != 0 && command->token == protectedToken) {
                    ++command;
                    continue;
                }
                bool matches = false;
                for (const auto& tile : command->tiles) {
                    for (std::size_t candidate = 0; candidate < bitmaps.size(); ++candidate) {
                        if (bitmapIdentities[candidate] != tile.bitmapIdentity) continue;
                        if ((tile.cpuBufferResource && tile.bitmap == nullptr) ||
                            (bitmaps[candidate] != nullptr && tile.bitmap != nullptr &&
                             env->IsSameObject(tile.bitmap, bitmaps[candidate]) == JNI_TRUE)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) break;
                }
                if (!matches) {
                    ++command;
                    continue;
                }
                tokens->push_back(command->token);
                retired.push_back(std::move(*command));
                command = frames_.erase(command);
            }
            if (!retired.empty()) {
                pipelineQuiescent_.store(false, std::memory_order_release);
            }
        }
        {
            std::unique_lock<std::mutex> sceneLock(producerSceneMutex_, std::try_to_lock);
            if (sceneLock.owns_lock()) {
                const bool producerSceneMatches = producerCpuScene_.storage != nullptr &&
                    std::any_of(
                        producerCpuScene_.storage->tiles.begin(),
                        producerCpuScene_.storage->tiles.end(),
                        [&](const FrameTile& tile) {
                            return std::find(
                                bitmapIdentities.begin(), bitmapIdentities.end(),
                                tile.bitmapIdentity) != bitmapIdentities.end();
                        });
                if (producerSceneMatches) {
                    retiredProducerScene = std::move(producerCpuScene_);
                    producerCpuScene_ = {};
                }
            }
        }
        for (auto& command : retired) releaseFrame(env, command);
        releaseProducerCpuScene(env, retiredProducerScene);
        if (!retired.empty()) {
            supersededFrames_.fetch_add(retired.size(), std::memory_order_relaxed);
            RLOGI("queued retired-bitmap frames discarded count=%zu", retired.size());
            condition_.notify_one();
        }
        return true;
    }

    void setDirectWifiTextureProfile(bool enabled, bool hostGpuEmulator) noexcept {
        directWifiTextureProfile_.store(enabled, std::memory_order_release);
        // Backend transport is a property of the runtime, not of one server/path profile. Tying
        // the host compositor backend to direct-WiFi content left ordinary manga and a runtime
        // connectivity change on gfxstream's blocking BufferQueue path. Texture admission keeps
        // its independent directWifiTextureProfile_ bit; only presentation uses this host bit.
        hostGpuEmulatorSurfaceProfile_.store(
            hostGpuEmulator,
            std::memory_order_release);
    }

private:
    enum class PresentResult : std::uint8_t {
        APPLIED = 0,
        TRANSIENT_BACKPRESSURE = 1,
        PREPARED_WAITING = 2,
        FAILED = 3,
    };

    enum class PrewarmUploadResult : std::uint8_t {
        CONSUMED_WITHOUT_UPLOAD = 0,
        UPLOADED = 1,
        SKIPPED_FOR_HEADROOM = 2,
        DEFERRED_FOR_RETIREMENT = 3,
    };

    static void wake(void* context) noexcept {
        auto* renderer = static_cast<RollingRenderer*>(context);
        if (renderer != nullptr) {
            renderer->pipelineQuiescent_.store(false, std::memory_order_release);
            renderer->condition_.notify_one();
        }
    }

    JNIEnv* attachEnv() const noexcept {
        if (vm_ == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        return env;
    }

    bool retainTileBitmapReference(FrameTile& tile) noexcept {
        const bool ledgerOnlyCpuResource = tile.bitmap == nullptr &&
            tile.cpuBufferResource && validExactCpuTile(tile.exactCpuBuffer);
        if (tile.bitmapReferenceTracked || tile.bitmapIdentity == 0 ||
            (tile.bitmap == nullptr && !ledgerOnlyCpuResource)) return false;
        std::lock_guard<std::mutex> lock(bitmapReferenceMutex_);
        if (!bitmapReferenceLedger_.retain(tile.bitmapIdentity)) return false;
        tile.bitmapReferenceTracked = true;
        return true;
    }

    bool retainFrameBitmapReferences(std::vector<FrameTile>& tiles) noexcept {
        if (tiles.empty()) return false;
        std::lock_guard<std::mutex> lock(bitmapReferenceMutex_);
        std::size_t retained = 0;
        for (; retained < tiles.size(); ++retained) {
            auto& tile = tiles[retained];
            const bool ledgerOnlyCpuResource = tile.bitmap == nullptr &&
                tile.cpuBufferResource && validExactCpuTile(tile.exactCpuBuffer);
            if (tile.bitmapReferenceTracked || tile.bitmapIdentity == 0 ||
                (tile.bitmap == nullptr && !ledgerOnlyCpuResource) ||
                !bitmapReferenceLedger_.retain(tile.bitmapIdentity)) {
                break;
            }
            tile.bitmapReferenceTracked = true;
        }
        if (retained == tiles.size()) return true;
        while (retained > 0) {
            auto& tile = tiles[--retained];
            if (!bitmapReferenceLedger_.release(tile.bitmapIdentity)) {
                RLOGE(
                    "bitmap reference ledger batch rollback mismatch identity=%d",
                    tile.bitmapIdentity);
            }
            tile.bitmapReferenceTracked = false;
        }
        return false;
    }

    void releaseTile(JNIEnv* env, FrameTile& tile) noexcept {
        if (tile.bitmapReferenceTracked) {
            bool released = false;
            {
                std::lock_guard<std::mutex> lock(bitmapReferenceMutex_);
                released = bitmapReferenceLedger_.release(tile.bitmapIdentity);
            }
            if (!released) {
                RLOGE("bitmap reference ledger release mismatch identity=%d", tile.bitmapIdentity);
            }
            tile.bitmapReferenceTracked = false;
        }
        if (env != nullptr && tile.bitmap != nullptr) {
            env->DeleteGlobalRef(tile.bitmap);
            tile.bitmap = nullptr;
        }
        // The pool's owner reference remains valid until this bitmap-ledger lease is gone; frame
        // commands deliberately do not mutate the backing resource lifetime on every callback.
        tile.exactHardwareBuffer = nullptr;
        tile.exactCpuBuffer = nullptr;
    }

    void releaseFrame(JNIEnv* env, FrameCommand& command) noexcept {
        if (env == nullptr) return;
        if (command.producerCpuSceneStorage != nullptr) {
            command.tiles.clear();
            releaseProducerCpuSceneStorage(env, command.producerCpuSceneStorage);
            command.producerCpuSceneStorage = nullptr;
            return;
        }
        // Frame commands normally contain several exact tiles. Retire their ledger leases under
        // one critical section so a FIFO replacement cannot spend an input frame repeatedly
        // entering the same mutex. JNI references are still deleted individually outside it.
        {
            std::lock_guard<std::mutex> lock(bitmapReferenceMutex_);
            for (auto& tile : command.tiles) {
                if (!tile.bitmapReferenceTracked) continue;
                if (!bitmapReferenceLedger_.release(tile.bitmapIdentity)) {
                    RLOGE(
                        "bitmap reference ledger frame release mismatch identity=%d",
                        tile.bitmapIdentity);
                }
                tile.bitmapReferenceTracked = false;
            }
        }
        for (auto& tile : command.tiles) {
            if (tile.bitmap != nullptr) {
                env->DeleteGlobalRef(tile.bitmap);
                tile.bitmap = nullptr;
            }
            tile.exactHardwareBuffer = nullptr;
            tile.exactCpuBuffer = nullptr;
        }
        command.tiles.clear();
    }

    static void retainProducerCpuSceneStorage(ProducerCpuSceneStorage* storage) noexcept {
        if (storage != nullptr) {
            storage->references.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void releaseProducerCpuSceneStorage(
            JNIEnv* env,
            ProducerCpuSceneStorage* storage) noexcept {
        if (storage == nullptr ||
            storage->references.fetch_sub(1, std::memory_order_acq_rel) != 1) {
            return;
        }
        {
            std::lock_guard<std::mutex> lock(bitmapReferenceMutex_);
            for (auto& tile : storage->tiles) {
                if (!tile.bitmapReferenceTracked) continue;
                if (!bitmapReferenceLedger_.release(tile.bitmapIdentity)) {
                    RLOGE(
                        "bitmap reference ledger producer-scene release mismatch identity=%d",
                        tile.bitmapIdentity);
                }
                tile.bitmapReferenceTracked = false;
            }
        }
        for (auto& tile : storage->tiles) {
            if (env != nullptr && tile.bitmap != nullptr) {
                env->DeleteGlobalRef(tile.bitmap);
                tile.bitmap = nullptr;
            }
            tile.exactHardwareBuffer = nullptr;
            tile.exactCpuBuffer = nullptr;
        }
        delete storage;
    }

    void releaseProducerCpuScene(JNIEnv* env, ProducerCpuScene& scene) noexcept {
        releaseProducerCpuSceneStorage(env, scene.storage);
        scene = {};
    }

    bool installProducerCpuScene(
            JNIEnv* env,
            std::uint64_t sceneId,
            const FrameCommand& source) noexcept {
        if (env == nullptr || sceneId == 0 || source.tiles.empty()) return false;
        auto* storage = new (std::nothrow) ProducerCpuSceneStorage();
        if (storage == nullptr) return false;
        storage->tiles = source.tiles;
        for (std::size_t index = 0; index < storage->tiles.size(); ++index) {
            auto& tile = storage->tiles[index];
            const jobject sourceBitmap = source.tiles[index].bitmap;
            tile.bitmapReferenceTracked = false;
            tile.bitmap = nullptr;
            if (sourceBitmap != nullptr) {
                tile.bitmap = env->NewGlobalRef(sourceBitmap);
                if (tile.bitmap == nullptr || env->ExceptionCheck()) {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    for (auto& installed : storage->tiles) {
                        if (installed.bitmap != nullptr) {
                            env->DeleteGlobalRef(installed.bitmap);
                            installed.bitmap = nullptr;
                        }
                    }
                    storage->tiles.clear();
                    delete storage;
                    return false;
                }
            }
        }
        if (!retainFrameBitmapReferences(storage->tiles)) {
            for (auto& tile : storage->tiles) {
                if (tile.bitmap != nullptr) {
                    env->DeleteGlobalRef(tile.bitmap);
                    tile.bitmap = nullptr;
                }
            }
            storage->tiles.clear();
            delete storage;
            return false;
        }
        ProducerCpuScene next;
        next.id = sceneId;
        next.width = source.width;
        next.height = source.height;
        next.storage = storage;
        ProducerCpuScene previous;
        {
            std::lock_guard<std::mutex> lock(producerSceneMutex_);
            previous = std::move(producerCpuScene_);
            producerCpuScene_ = std::move(next);
        }
        releaseProducerCpuScene(env, previous);
        return true;
    }

    static bool sameAppliedPixels(
            const AppliedFrameTileSignature& applied,
            const FrameTile& tile) noexcept {
        // Appending an adjacent page legitimately advances the global traversal epoch. It does
        // not mutate an immutable tile already retained in this band, so compare the actual
        // logical slot, immutable content identity, source span and destination geometry.
        return applied.key.page == tile.key.page && applied.key.slot == tile.key.slot &&
            applied.sourceTop == tile.sourceTop &&
            applied.sourceBottom == tile.sourceBottom &&
            applied.sourceWidth == tile.sourceWidth &&
            applied.sourceHeight == tile.sourceHeight &&
            applied.contentIdentity == tile.contentIdentity &&
            applied.pageTop == tile.pageTop && applied.pageHeight == tile.pageHeight;
    }

    static bool sameAppliedContentAndScale(
            const AppliedFrameTileSignature& applied,
            const FrameTile& tile) noexcept {
        return applied.key.page == tile.key.page && applied.key.slot == tile.key.slot &&
            applied.sourceTop == tile.sourceTop &&
            applied.sourceBottom == tile.sourceBottom &&
            applied.sourceWidth == tile.sourceWidth &&
            applied.sourceHeight == tile.sourceHeight &&
            applied.contentIdentity == tile.contentIdentity &&
            applied.pageHeight == tile.pageHeight;
    }

    static bool sameFramePixels(
            const FrameTile& composed,
            const FrameTile& requested) noexcept {
        return composed.key.page == requested.key.page &&
            composed.key.slot == requested.key.slot &&
            composed.sourceTop == requested.sourceTop &&
            composed.sourceBottom == requested.sourceBottom &&
            composed.sourceWidth == requested.sourceWidth &&
            composed.sourceHeight == requested.sourceHeight &&
            composed.contentIdentity == requested.contentIdentity &&
            composed.pageTop == requested.pageTop &&
            composed.pageHeight == requested.pageHeight;
    }

    static bool sameFrameContentAndScale(
            const FrameTile& composed,
            const FrameTile& requested) noexcept {
        return composed.key.page == requested.key.page &&
            composed.key.slot == requested.key.slot &&
            composed.sourceTop == requested.sourceTop &&
            composed.sourceBottom == requested.sourceBottom &&
            composed.sourceWidth == requested.sourceWidth &&
            composed.sourceHeight == requested.sourceHeight &&
            composed.contentIdentity == requested.contentIdentity &&
            composed.pageHeight == requested.pageHeight;
    }

    template <typename Tile>
    static bool tileIntersectsSourceCrop(
            const Tile& tile,
            double cropTop,
            double cropBottom) noexcept {
        if (tile.sourceHeight <= 0 || tile.sourceBottom <= tile.sourceTop ||
            !std::isfinite(tile.pageTop) || !std::isfinite(tile.pageHeight) ||
            tile.pageHeight <= 0.0F) {
            return true;
        }
        const double tileTop = static_cast<double>(tile.pageTop) +
            static_cast<double>(tile.pageHeight) *
                static_cast<double>(tile.sourceTop) /
                static_cast<double>(tile.sourceHeight);
        const double tileBottom = static_cast<double>(tile.pageTop) +
            static_cast<double>(tile.pageHeight) *
                static_cast<double>(tile.sourceBottom) /
                static_cast<double>(tile.sourceHeight);
        return tileBottom > cropTop && tileTop < cropBottom;
    }

    bool matchesLastAppliedFrame(
            const FrameCommand& frame,
            int* appliedViewportSourceTop = nullptr) const noexcept {
        if (appliedViewportSourceTop != nullptr) *appliedViewportSourceTop = -1;
        if (lastAppliedFrameWidth_ != frame.width || lastAppliedFrameHeight_ != frame.height ||
            frame.viewportSourceTop < 0 || frame.viewportSourceHeight <= 0 ||
            frame.viewportSourceTop > frame.height - frame.viewportSourceHeight ||
            frame.tileView().empty() || lastAppliedFrameTiles_.empty()) {
            return false;
        }
        const double cropTop = static_cast<double>(frame.viewportSourceTop);
        const double cropBottom = cropTop +
            static_cast<double>(frame.viewportSourceHeight);
        std::size_t currentVisibleCount = 0;
        std::size_t appliedVisibleCount = 0;
        double sourceTranslation = 0.0;
        bool sourceTranslationKnown = false;

        // The two passes are intentional. A newly decoded off-screen tile must not force an
        // 8-viewport buffer rebuild in the middle of physical input, while a tile appearing,
        // disappearing or changing anywhere inside the actual source crop must fail closed.
        for (const auto& tile : frame.tileView()) {
            if (!tileIntersectsSourceCrop(tile, cropTop, cropBottom)) continue;
            ++currentVisibleCount;
            const auto found = std::find_if(
                lastAppliedFrameTiles_.begin(), lastAppliedFrameTiles_.end(),
                [&](const AppliedFrameTileSignature& applied) {
                    return sameAppliedContentAndScale(applied, tile);
                });
            if (found == lastAppliedFrameTiles_.end()) return false;
            const double candidateTranslation =
                static_cast<double>(found->pageTop) - static_cast<double>(tile.pageTop);
            if (!sourceTranslationKnown) {
                sourceTranslation = candidateTranslation;
                sourceTranslationKnown = true;
            } else if (std::abs(candidateTranslation - sourceTranslation) > 0.01) {
                return false;
            }
        }
        if (!sourceTranslationKnown) return false;
        const double translatedCropTop = cropTop + sourceTranslation;
        const double roundedTranslatedCropTop = std::round(translatedCropTop);
        if (std::abs(translatedCropTop - roundedTranslatedCropTop) >
                kNativeSourceCropRoundingTolerance ||
            roundedTranslatedCropTop < 0.0 ||
            roundedTranslatedCropTop >
                static_cast<double>(frame.height - frame.viewportSourceHeight)) {
            return false;
        }
        const double translatedCropBottom = translatedCropTop +
            static_cast<double>(frame.viewportSourceHeight);
        for (const auto& applied : lastAppliedFrameTiles_) {
            if (!tileIntersectsSourceCrop(
                    applied, translatedCropTop, translatedCropBottom)) continue;
            ++appliedVisibleCount;
            const bool found = std::any_of(
                frame.tileView().begin(), frame.tileView().end(),
                [&](const FrameTile& tile) {
                    return tileIntersectsSourceCrop(tile, cropTop, cropBottom) &&
                        sameAppliedContentAndScale(applied, tile) &&
                        std::abs(
                            static_cast<double>(applied.pageTop) -
                                static_cast<double>(tile.pageTop) -
                                sourceTranslation) <= 0.01;
                });
            if (!found) return false;
        }
        const bool matches = currentVisibleCount > 0 &&
            currentVisibleCount == appliedVisibleCount;
        if (matches && appliedViewportSourceTop != nullptr) {
            *appliedViewportSourceTop = static_cast<int>(roundedTranslatedCropTop);
        }
        return matches;
    }

    static bool composedFrameCoversViewport(
            const FrameCommand& composed,
            const FrameCommand& requested,
            int* composedViewportSourceTop = nullptr) noexcept {
        if (composedViewportSourceTop != nullptr) *composedViewportSourceTop = -1;
        if (composed.width != requested.width || composed.height != requested.height ||
            requested.viewportSourceTop < 0 || requested.viewportSourceHeight <= 0 ||
            requested.viewportSourceTop >
                requested.height - requested.viewportSourceHeight ||
            composed.tileView().empty() || requested.tileView().empty()) {
            return false;
        }
        const double cropTop = static_cast<double>(requested.viewportSourceTop);
        const double cropBottom = cropTop +
            static_cast<double>(requested.viewportSourceHeight);
        std::size_t requestedVisibleCount = 0;
        std::size_t composedVisibleCount = 0;
        double sourceTranslation = 0.0;
        bool sourceTranslationKnown = false;
        for (const auto& tile : requested.tileView()) {
            if (!tileIntersectsSourceCrop(tile, cropTop, cropBottom)) continue;
            ++requestedVisibleCount;
            const auto existing = std::find_if(
                    composed.tileView().begin(), composed.tileView().end(),
                    [&](const FrameTile& candidate) {
                        return sameFrameContentAndScale(candidate, tile);
                    });
            if (existing == composed.tileView().end()) return false;
            const double candidateTranslation =
                static_cast<double>(existing->pageTop) -
                static_cast<double>(tile.pageTop);
            if (!sourceTranslationKnown) {
                sourceTranslation = candidateTranslation;
                sourceTranslationKnown = true;
            } else if (std::abs(candidateTranslation - sourceTranslation) > 0.01) {
                return false;
            }
        }
        if (!sourceTranslationKnown) return false;
        const double translatedCropTop = cropTop + sourceTranslation;
        const double roundedTranslatedCropTop = std::round(translatedCropTop);
        if (std::abs(translatedCropTop - roundedTranslatedCropTop) >
                kNativeSourceCropRoundingTolerance ||
            roundedTranslatedCropTop < 0.0 ||
            roundedTranslatedCropTop >
                static_cast<double>(composed.height - requested.viewportSourceHeight)) {
            return false;
        }
        const double translatedCropBottom = translatedCropTop +
            static_cast<double>(requested.viewportSourceHeight);
        for (const auto& tile : composed.tileView()) {
            if (!tileIntersectsSourceCrop(
                    tile, translatedCropTop, translatedCropBottom)) continue;
            ++composedVisibleCount;
            if (std::none_of(
                    requested.tileView().begin(), requested.tileView().end(),
                    [&](const FrameTile& current) {
                        return tileIntersectsSourceCrop(current, cropTop, cropBottom) &&
                            sameFrameContentAndScale(tile, current) &&
                            std::abs(
                                static_cast<double>(tile.pageTop) -
                                    static_cast<double>(current.pageTop) -
                                    sourceTranslation) <= 0.01;
                    })) return false;
        }
        const bool covers = requestedVisibleCount > 0 &&
            requestedVisibleCount == composedVisibleCount;
        if (covers && composedViewportSourceTop != nullptr) {
            *composedViewportSourceTop =
                static_cast<int>(roundedTranslatedCropTop);
        }
        return covers;
    }

    bool entireBandAlreadyApplied(const FrameCommand& frame) const noexcept {
        if (lastAppliedFrameWidth_ != frame.width ||
            lastAppliedFrameHeight_ != frame.height ||
            lastAppliedFrameTiles_.size() != frame.tileView().size()) return false;
        for (const auto& tile : frame.tileView()) {
            if (std::none_of(
                    lastAppliedFrameTiles_.begin(), lastAppliedFrameTiles_.end(),
                    [&](const AppliedFrameTileSignature& applied) {
                        return sameAppliedPixels(applied, tile);
                    })) return false;
        }
        return true;
    }

    static bool supportsExactCpuBandPrecomposition(
            const FrameCommand& frame) noexcept {
        if (frame.tileView().empty()) return false;
        return std::all_of(
            frame.tileView().begin(), frame.tileView().end(),
            [](const FrameTile& tile) {
                const int sourceSpan = tile.sourceBottom - tile.sourceTop;
                return sourceSpan > 0 && tile.sourceWidth > 0 &&
                    tile.cpuBufferResource && exactCpuTileHasContent(
                        tile.exactCpuBuffer, tile.sourceWidth, sourceSpan);
            });
    }

    /** Called only while [mutex_] is held by the renderer loop. */
    bool isActiveDirectWifiPrewarmLocked() const noexcept {
        return requestedPrewarmPaused_.load(std::memory_order_acquire) &&
            !activeDirectWifiPrewarmSuppressed_ &&
            directWifiTextureProfile_.load(std::memory_order_acquire) &&
            !hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
    }

    bool usesFreshTextureNames() const noexcept {
        return directWifiTextureProfile_.load(std::memory_order_acquire) &&
            !hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
    }

    /** Called only while [mutex_] is held by the renderer loop. */
    bool canUploadNextPrewarmLocked() const noexcept {
        // An unpause command must first install its quiet-period/deadline policy. A pause command
        // already blocks every ordinary lane through this same owner fence; physical direct-Wi-Fi
        // can resume its explicitly bounded active lane after the owner applies the command.
        if (prewarmPauseCommandPending_.load(std::memory_order_acquire)) return false;
        // setPrewarmPaused(true) is the physical-input ownership boundary. The flag previously
        // existed only as telemetry/state: this predicate ignored it, so a full-scene snapshot
        // kept winning the EGL lane while 500 forward gestures accumulated. Visible submissions
        // must always preempt non-presenting uploads; the queue remains intact and resumes after
        // the real quiet-period gate in setPrewarmPaused(false).
        if (prewarmTiles_.empty()) return false;
        const FrameTile& next = prewarmTiles_.front();
        const auto resident = textures_.find(next.key);
        const bool exactResident = resident != textures_.end() &&
            resident->second.contentIdentity == next.contentIdentity;
        // Exact identities are CPU-only queue maintenance and may drain while retirement waits.
        // Any optional allocation must remain queued until the owner settles deleted GL backing.
        if (textureRetirementDebt_.pending()) return exactResident;
        const int forwardPrewarmPages =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire)
                ? kHostGpuPausedForwardPrewarmPages
                : kPausedForwardPrewarmPages;
        if (requestedPrewarmPaused_.load(std::memory_order_acquire)) {
            if (!isActiveDirectWifiPrewarmLocked()) return false;
            if (exactResident) {
                // Coalesced snapshots begin with the current resident anchor. Drain those JNI
                // references without GL work or pacing so the nearest missing forward tile can
                // reach the head of the queue.
                return true;
            }
            if (lastPresentedStructureEpoch_ <= 0 ||
                queuedPrewarmEpoch_ != lastPresentedStructureEpoch_ ||
                lastPresentedMaxPage_ < 0) return false;
            return next.key.structureEpoch == lastPresentedStructureEpoch_ &&
                next.key.page >= lastPresentedMaxPage_ &&
                next.key.page <= lastPresentedMaxPage_ + forwardPrewarmPages;
        }
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
        // Keep an already-issued direct-Wi-Fi capability bounded even if connectivity changes
        // during the release gap. Dropping the profile term here would otherwise turn the
        // immediate next-page grant into an unpaced ordinary/mobile +16-page upload burst.
        const bool directWifiReleaseGap =
            directWifiFullPrewarmResumeNanos_ > nowNanos();
        if (directWifiReleaseGap &&
            (directWifiImmediateResumeMaxPage_ < 0 ||
             next.key.page > directWifiImmediateResumeMaxPage_)) {
            return false;
        }
        return next.key.structureEpoch == lastPresentedStructureEpoch_ &&
            next.key.page <= lastPresentedMaxPage_ + forwardPrewarmPages;
    }

    void rememberAppliedFrame(const FrameCommand& frame, int geometryBaseSourceTop) {
        lastAppliedFrameWidth_ = frame.width;
        lastAppliedFrameHeight_ = frame.height;
        lastAppliedFrameEpoch_ = frame.structureEpoch;
        lastAppliedGeometryBaseSourceTop_ = geometryBaseSourceTop;
        lastAppliedViewportSourceHeight_ = frame.viewportSourceHeight;
        // The preserved parent transform plus the newly installed geometry base displays this
        // exact viewport without a second transaction, so it becomes the transform's new origin.
        lastJavaGeometrySourceTop_ = frame.viewportSourceTop;
        lastAppliedProducerSceneId_ = frame.producerSceneId;
        lastAppliedFrameTiles_.clear();
        lastAppliedFrameTiles_.reserve(frame.tileView().size());
        for (const auto& tile : frame.tileView()) {
            lastAppliedFrameTiles_.push_back({
                tile.key, tile.sourceTop, tile.sourceBottom, tile.sourceWidth,
                tile.sourceHeight, tile.bitmapIdentity, tile.contentIdentity,
                tile.pageTop, tile.pageHeight});
        }
    }

    void rememberAppliedComposition(
            const FrameCommand& composed,
            const FrameCommand& presented,
            int geometryBaseSourceTop) {
        lastAppliedFrameWidth_ = composed.width;
        lastAppliedFrameHeight_ = composed.height;
        lastAppliedFrameEpoch_ = composed.structureEpoch;
        lastAppliedGeometryBaseSourceTop_ = geometryBaseSourceTop;
        lastAppliedViewportSourceHeight_ = presented.viewportSourceHeight;
        lastJavaGeometrySourceTop_ = geometryBaseSourceTop;
        lastAppliedProducerSceneId_ = composed.producerSceneId;
        lastAppliedFrameTiles_.clear();
        lastAppliedFrameTiles_.reserve(composed.tileView().size());
        for (const auto& tile : composed.tileView()) {
            lastAppliedFrameTiles_.push_back({
                tile.key, tile.sourceTop, tile.sourceBottom, tile.sourceWidth,
                tile.sourceHeight, tile.bitmapIdentity, tile.contentIdentity,
                tile.pageTop, tile.pageHeight});
        }
    }

    void discardQueuedPrewarmOutsideEpoch(
            JNIEnv* env,
            std::int64_t structureEpoch) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        bool queueChanged = queuedPrewarmEpoch_ != structureEpoch;
        if (queueChanged) {
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
            queueChanged = true;
        }
        queuedPrewarmEpoch_ = structureEpoch;
        if (queueChanged) ++prewarmQueueRevision_;
    }

    void restoreDeferredPrewarmTile(
            JNIEnv* env,
            FrameTile& tile,
            std::uint64_t expectedRevision) noexcept {
        bool restored = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!stopped_.load(std::memory_order_acquire) &&
                !failed_.load(std::memory_order_acquire) &&
                queuedPrewarmEpoch_ == tile.key.structureEpoch &&
                prewarmQueueRevision_ == expectedRevision) {
                prewarmTiles_.push_front(tile);
                tile.bitmap = nullptr;
                tile.exactHardwareBuffer = nullptr;
                tile.exactCpuBuffer = nullptr;
                ++prewarmQueueRevision_;
                pipelineQuiescent_.store(false, std::memory_order_release);
                restored = true;
            }
        }
        if (!restored) releaseTile(env, tile);
    }

    void callbackDropped(JNIEnv* env, std::uint64_t token, jint reason) noexcept {
        if (env == nullptr || callback_ == nullptr || droppedMethod_ == nullptr || token == 0) return;
        const std::uint64_t ordinal = ++droppedFrames_;
        if (ordinal == 1 || ordinal % 90 == 0) {
            RLOGI("frame dropped ordinal=%llu token=%llu reason=%d accepted=%llu superseded=%llu",
                  static_cast<unsigned long long>(ordinal),
                  static_cast<unsigned long long>(token),
                  static_cast<int>(reason),
                  static_cast<unsigned long long>(acceptedFrames_.load(std::memory_order_relaxed)),
                  static_cast<unsigned long long>(supersededFrames_.load(std::memory_order_relaxed)));
        }
        env->CallVoidMethod(
            callback_, droppedMethod_, static_cast<jlong>(token), reason);
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
        // The Java clock conversion subtracts every delay between the native evidence timestamp
        // and this JNI ingress. Use the actual hand-off instant, not the earlier Binder callback
        // instant, so time spent waiting in the renderer event queue cannot masquerade as a
        // compositor presentation interval.
        const std::int64_t javaHandoffNanos = nowNanos();
        env->CallVoidMethod(
            callback_, latchedMethod_, static_cast<jlong>(event.identity.ntkFrameId),
            static_cast<jlong>(event.latchNanos),
            static_cast<jlong>(javaHandoffNanos),
            static_cast<jint>(1));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    void callbackBandActivated(
            JNIEnv* env,
            const ntk::present::FixedPresentEvent& event) noexcept {
        if (env == nullptr || callback_ == nullptr || bandActivatedMethod_ == nullptr ||
            event.identity.ntkFrameId == 0 || lastAppliedFrameEpoch_ <= 0) {
            return;
        }
        env->CallVoidMethod(
            callback_, bandActivatedMethod_,
            static_cast<jlong>(event.identity.ntkFrameId),
            static_cast<jlong>(lastAppliedProducerSceneId_),
            static_cast<jlong>(lastAppliedFrameEpoch_));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    bool callbackGeometryFrameRequested(
            JNIEnv* env,
            std::uint64_t token,
            int sourceTop,
            int sourceHeight,
            int geometryBaseSourceTop,
            std::int64_t frameTimelineVsyncId,
            std::int64_t expectedPresentationTimeNanos) noexcept {
        if (env == nullptr || callback_ == nullptr || geometryMethod_ == nullptr || token == 0 ||
            sourceTop < 0 || sourceHeight <= 0) {
            return false;
        }
        const jboolean accepted = env->CallBooleanMethod(
            callback_, geometryMethod_, static_cast<jlong>(token),
            static_cast<jint>(sourceTop), static_cast<jint>(sourceHeight),
            static_cast<jint>(geometryBaseSourceTop),
            static_cast<jlong>(frameTimelineVsyncId),
            static_cast<jlong>(expectedPresentationTimeNanos));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return accepted == JNI_TRUE;
    }

    void callbackPrecomposedBandReady(JNIEnv* env) noexcept {
        if (env == nullptr || callback_ == nullptr || precomposedReadyMethod_ == nullptr ||
            stopped_.load(std::memory_order_acquire) ||
            failed_.load(std::memory_order_acquire)) return;
        env->CallVoidMethod(callback_, precomposedReadyMethod_);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    bool tryEnqueueWindowPresentationCallback(
            const WindowPresentationCallback& callback) noexcept {
        const std::uint64_t write = presentationCallbackWrite_.load(
            std::memory_order_relaxed);
        const std::uint64_t read = presentationCallbackRead_.load(
            std::memory_order_acquire);
        if (write - read >= kWindowPresentationCallbackCapacity) return false;
        presentationCallbacks_[static_cast<std::size_t>(
            write % kWindowPresentationCallbackCapacity)] = callback;
        presentationCallbackWrite_.store(write + 1U, std::memory_order_release);
        presentationCallbackCondition_.notify_one();
        return true;
    }

    void enqueueWindowPresentationCallback(
            const WindowPresentationCallback& callback) noexcept {
        while (!tryEnqueueWindowPresentationCallback(callback)) {
            // 4096 entries cover more than 45 seconds at 90 Hz. Reaching this branch means the
            // Java proof owner itself has stopped making progress; preserve exact token order and
            // apply bounded sleeps instead of allocating an unbounded native queue or dropping a
            // lifecycle proof. Under ordinary GC pauses the producer never enters this path.
            std::unique_lock<std::mutex> lock(presentationCallbackWaitMutex_);
            presentationCallbackSpaceCondition_.wait_for(
                lock, std::chrono::milliseconds(1), [&] {
                    return presentationCallbackWrite_.load(std::memory_order_acquire) -
                            presentationCallbackRead_.load(std::memory_order_acquire) <
                        kWindowPresentationCallbackCapacity ||
                        presentationCallbackFailed_.load(std::memory_order_acquire);
                });
            if (presentationCallbackFailed_.load(std::memory_order_acquire)) {
                failed_.store(true, std::memory_order_release);
                return;
            }
        }
    }

    void presentationCallbackLoop() noexcept {
        (void)pthread_setname_np(pthread_self(), "ReaderNativeProof");
        // Proof retirement feeds the next producer admission but owns no GL work. Keep it in the
        // display band so network/decode pools cannot build a callback backlog, while the separate
        // EGL owner remains free to submit already-sealed frames.
        (void)setpriority(PRIO_PROCESS, 0, kRollingConsumerNice);
        JNIEnv* env = attachEnv();
        if (env == nullptr) {
            presentationCallbackFailed_.store(true, std::memory_order_release);
            presentationCallbackSpaceCondition_.notify_all();
            condition_.notify_one();
            return;
        }
        while (true) {
            std::uint64_t read = presentationCallbackRead_.load(
                std::memory_order_relaxed);
            const std::uint64_t write = presentationCallbackWrite_.load(
                std::memory_order_acquire);
            if (read < write) {
                const WindowPresentationCallback callback =
                    presentationCallbacks_[static_cast<std::size_t>(
                        read % kWindowPresentationCallbackCapacity)];
                const std::int64_t callbackBeginNanos = nowNanos();
                // completedNanos is immutable physical evidence. observedNanos must describe the
                // JNI hand-off, otherwise time queued on ReaderNativeProof is added to the
                // converted presentation timestamp and creates false 25-35 ms cadence gaps.
                env->CallVoidMethod(
                    callback_, latchedMethod_, static_cast<jlong>(callback.token),
                    static_cast<jlong>(callback.completedNanos),
                    static_cast<jlong>(callbackBeginNanos),
                    static_cast<jint>(callback.presentationKind));
                if (env->ExceptionCheck()) env->ExceptionClear();
                const std::int64_t callbackElapsedUs =
                    (nowNanos() - callbackBeginNanos) / 1000;
                const std::int64_t callbackQueueUs = callback.completedNanos > 0
                    ? std::max<std::int64_t>(
                        0, (callbackBeginNanos - callback.completedNanos) / 1000)
                    : 0;
                if (callbackElapsedUs >= 16'000) {
                    const std::uint64_t slowOrdinal = ++slowWindowPresentationCallbacks_;
                    if (slowOrdinal == 1 || slowOrdinal % 90 == 0 ||
                        callbackElapsedUs >= 50'000 || callbackQueueUs >= 50'000) {
                        RLOGI(
                            "window presentation callback slow ordinal=%llu token=%llu kind=%d queueUs=%lld elapsedUs=%lld",
                            static_cast<unsigned long long>(slowOrdinal),
                            static_cast<unsigned long long>(callback.token),
                            callback.presentationKind,
                            static_cast<long long>(callbackQueueUs),
                            static_cast<long long>(callbackElapsedUs));
                    }
                } else if (callbackQueueUs >= 50'000) {
                    RLOGI(
                        "window presentation callback queued token=%llu kind=%d queueUs=%lld elapsedUs=%lld",
                        static_cast<unsigned long long>(callback.token),
                        callback.presentationKind,
                        static_cast<long long>(callbackQueueUs),
                        static_cast<long long>(callbackElapsedUs));
                }
                presentationCallbackRead_.store(read + 1U, std::memory_order_release);
                presentationCallbackSpaceCondition_.notify_one();
                if (read + 1U == presentationCallbackWrite_.load(
                        std::memory_order_acquire)) {
                    // Let the renderer republish exact quiescence after the last Java proof has
                    // completed; this is a maintenance wake, not a frame request.
                    condition_.notify_one();
                }
                continue;
            }
            if (presentationProducerStopped_.load(std::memory_order_acquire)) break;
            std::unique_lock<std::mutex> lock(presentationCallbackWaitMutex_);
            presentationCallbackCondition_.wait(lock, [&] {
                return presentationProducerStopped_.load(std::memory_order_acquire) ||
                    presentationCallbackRead_.load(std::memory_order_acquire) <
                        presentationCallbackWrite_.load(std::memory_order_acquire);
            });
        }
        vm_->DetachCurrentThread();
    }

    void callbackWindowFramePresented(
            JNIEnv* env,
            std::uint64_t token,
            std::int64_t completedNanos,
            int presentationKind,
            std::int64_t observedNanos = 0) noexcept {
        if (env == nullptr || callback_ == nullptr || latchedMethod_ == nullptr || token == 0 ||
            completedNanos <= 0 || presentationKind < 1 || presentationKind > 3) return;
        if (observedNanos <= 0) observedNanos = completedNanos;
        const std::uint64_t ordinal = ++latchedFrames_;
        if (ordinal == 1 || ordinal % 90 == 0) {
            RLOGI("window frame presented ordinal=%llu token=%llu submitted=%llu kind=%d",
                  static_cast<unsigned long long>(ordinal),
                  static_cast<unsigned long long>(token),
                  static_cast<unsigned long long>(submittedFrames_), presentationKind);
        }
        // Kind 1 is exact SurfaceControl OnComplete/present-fence evidence. Kind 2 is a successful
        // BufferQueue producer hand-off, and kind 3 is the corresponding hand-off to an
        // already-queued shared front buffer. Keeping the values distinct prevents producer
        // evidence from being reported as a transaction latch.
        enqueueWindowPresentationCallback({
            .token = token,
            .completedNanos = completedNanos,
            .observedNanos = observedNanos,
            .presentationKind = presentationKind,
        });
    }

    bool consumeDirectTileEvents(JNIEnv* env) noexcept {
        ntk::present::DirectTilePresentEvent event{};
        while (directTilePresenter_.drainEvent(&event)) {
            if (event.kind == ntk::present::DirectTilePresentEventKind::COMMITTED) continue;
            if (event.kind == ntk::present::DirectTilePresentEventKind::FAILED ||
                event.token == 0 || event.completedNanos <= 0) {
                RLOGE(
                    "direct tile event invalid kind=%u token=%llu completed=%lld observed=%lld",
                    static_cast<unsigned>(event.kind),
                    static_cast<unsigned long long>(event.token),
                    static_cast<long long>(event.completedNanos),
                    static_cast<long long>(event.observedNanos));
                fatal(env, "direct-tile-present-evidence");
                return false;
            }
            if (event.contentChanged && env != nullptr && callback_ != nullptr &&
                bandActivatedMethod_ != nullptr && event.structureEpoch > 0) {
                env->CallVoidMethod(
                    callback_, bandActivatedMethod_,
                    static_cast<jlong>(event.token),
                    static_cast<jlong>(event.producerSceneId),
                    static_cast<jlong>(event.structureEpoch));
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            const int presentationKind =
                event.kind == ntk::present::DirectTilePresentEventKind::PRODUCER_SUBMITTED
                    ? 2 : 1;
            callbackWindowFramePresented(
                env, event.token, event.completedNanos,
                presentationKind, event.observedNanos);
        }
        return true;
    }

    void fatal(JNIEnv* env, const char* reason) noexcept {
        if (failed_.exchange(true, std::memory_order_acq_rel)) return;
        pipelineQuiescent_.store(false, std::memory_order_release);
        RLOGE("fatal reason=%s", reason != nullptr ? reason : "unknown");
        if (env == nullptr || callback_ == nullptr || fatalMethod_ == nullptr) return;
        jstring message = env->NewStringUTF(reason != nullptr ? reason : "unknown");
        if (message != nullptr) {
            env->CallVoidMethod(
                callback_, fatalMethod_,
                static_cast<jlong>(reinterpret_cast<std::uintptr_t>(this)),
                static_cast<jlong>(creationGeneration_), message);
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
        constexpr EGLint mutableConfigAttributes[] = {
            EGL_SURFACE_TYPE,
                EGL_PBUFFER_BIT | EGL_WINDOW_BIT | EGL_MUTABLE_RENDER_BUFFER_BIT_KHR,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
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
        // Android's EGL platform shim implements front-buffer auto-refresh even when gfxstream's
        // driver extension string omits the platform-owned token. Query the config capability
        // directly; fall back to the ordinary window+pbuffer config when it is unavailable.
        if (eglChooseConfig(
                display_, mutableConfigAttributes, &config_, 1, &count) == EGL_TRUE &&
            count == 1) {
            mutableRenderBufferSupported_ = true;
        } else {
            count = 0;
            if (eglChooseConfig(display_, configAttributes, &config_, 1, &count) != EGL_TRUE ||
                count != 1) return false;
            mutableRenderBufferSupported_ = false;
        }
        constexpr EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
        if (context_ == EGL_NO_CONTEXT) return false;
        constexpr EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttributes);
        if (pbuffer_ == EGL_NO_SURFACE ||
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE) return false;

        getNativeClientBuffer_ =
            reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
                eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        createImage_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
        destroyImage_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
        imageTargetTexture_ =
            reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
                eglGetProcAddress("glEGLImageTargetTexture2DOES"));

        constexpr char vertexSource[] =
            "#version 300 es\n"
            "layout(location=0) in vec2 aPosition;\n"
            "layout(location=1) in vec2 aTexCoord;\n"
            "uniform vec2 uYBounds;\n"
            "uniform vec2 uTexScale;\n"
            "out vec2 vTexCoord;\n"
            "void main(){float y=mix(uYBounds.x,uYBounds.y,aPosition.y);"
            "gl_Position=vec4(aPosition.x,y,0.0,1.0);"
            "vTexCoord=aTexCoord*uTexScale;}\n";
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
        textureScaleUniform_ = glGetUniformLocation(program_, "uTexScale");
        const GLint textureUniform = glGetUniformLocation(program_, "uTexture");
        if (yBoundsUniform_ < 0 || textureScaleUniform_ < 0 || textureUniform < 0) return false;
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
        if (!refillTextureNameReserve(kTextureNameReserveCount)) return false;
        if (!ensureHostUploadScratch(kHostUploadScratchInitialBytes)) return false;
        // A second context owns only off-screen rolling-band composition. Geometry-only
        // SurfaceControl transactions use no GL and remain on the urgent renderer owner while
        // gfxstream binds, uploads, draws and exports the successor's fence on this shared lane.
        gpuCompositionContext_ = eglCreateContext(
            display_, config_, context_, contextAttributes);
        if (gpuCompositionContext_ != EGL_NO_CONTEXT) {
            gpuCompositionPbuffer_ = eglCreatePbufferSurface(
                display_, config_, pbufferAttributes);
            if (gpuCompositionPbuffer_ == EGL_NO_SURFACE) {
                eglDestroyContext(display_, gpuCompositionContext_);
                gpuCompositionContext_ = EGL_NO_CONTEXT;
            }
        }
        if (gpuCompositionContext_ == EGL_NO_CONTEXT ||
            gpuCompositionPbuffer_ == EGL_NO_SURFACE) {
            RLOGI("shared GPU band context unavailable; using renderer-owner fallback");
        }
        const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        RLOGI("cold EGL ready renderer=%s", renderer != nullptr ? renderer : "unknown");
        return glGetError() == GL_NO_ERROR;
    }

    void destroyEgl() noexcept {
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            if (hostFrontProof_ != nullptr) {
                glDeleteSync(hostFrontProof_);
                hostFrontProof_ = nullptr;
            }
            hostFrontSubmissions_.clear();
            hostFrontProofSubmissionCount_ = 0;
            hostFrontProofIssuedNanos_ = 0;
            if (textureRetirementFence_ != nullptr) {
                glDeleteSync(textureRetirementFence_);
                textureRetirementFence_ = nullptr;
            }
            textureRetirementFenceDirty_ = false;
            textureRetirementDebt_.completeBarrier(true);
            for (auto& entry : textures_) {
                if (entry.second.importedImage != EGL_NO_IMAGE_KHR && destroyImage_ != nullptr) {
                    destroyImage_(display_, entry.second.importedImage);
                    entry.second.importedImage = EGL_NO_IMAGE_KHR;
                }
                if (entry.second.texture != 0) glDeleteTextures(1, &entry.second.texture);
            }
            for (auto& pooled : pooledTextures_) {
                if (pooled.texture != 0) glDeleteTextures(1, &pooled.texture);
            }
            if (!spareTextureNames_.empty()) {
                glDeleteTextures(
                    static_cast<GLsizei>(spareTextureNames_.size()),
                    spareTextureNames_.data());
            }
            textures_.clear();
            pooledTextures_.clear();
            spareTextureNames_.clear();
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
            if (gpuCompositionPbuffer_ != EGL_NO_SURFACE) {
                eglDestroySurface(display_, gpuCompositionPbuffer_);
            }
            if (gpuCompositionContext_ != EGL_NO_CONTEXT) {
                eglDestroyContext(display_, gpuCompositionContext_);
            }
            if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
            if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
            eglTerminate(display_);
        }
        display_ = EGL_NO_DISPLAY;
        context_ = EGL_NO_CONTEXT;
        pbuffer_ = EGL_NO_SURFACE;
        gpuCompositionContext_ = EGL_NO_CONTEXT;
        gpuCompositionPbuffer_ = EGL_NO_SURFACE;
        gpuCompositionVao_ = 0;
        gpuCompositionFramebuffers_.fill(0);
        gpuCompositionFramebufferEpochs_.fill(0);
        gpuCompositionAttachedRenderbuffers_.fill(0);
        windowSurface_ = EGL_NO_SURFACE;
        hostCpuWindowAttached_ = false;
        hostUploadScratch_.reset();
        hostUploadScratchCapacity_ = 0;
        getNativeClientBuffer_ = nullptr;
        createImage_ = nullptr;
        destroyImage_ = nullptr;
        imageTargetTexture_ = nullptr;
        if (nativeWindow_ != nullptr) {
            ANativeWindow_release(nativeWindow_);
            nativeWindow_ = nullptr;
        }
    }

    std::uint64_t effectiveTextureBudget(std::int64_t structureEpoch) const noexcept {
        const std::int64_t before =
            fullSceneTextureBudgetEpoch_.load(std::memory_order_seq_cst);
        const std::uint64_t bytes =
            fullSceneTextureBudgetBytes_.load(std::memory_order_seq_cst);
        const std::int64_t after =
            fullSceneTextureBudgetEpoch_.load(std::memory_order_seq_cst);
        return before == structureEpoch && after == before
            ? std::max(kMaxTextureBudgetBytes, bytes)
            : kMaxTextureBudgetBytes;
    }

    static bool plannedTextureBytes(
            const FrameTile& tile,
            std::uint64_t* bytes) noexcept {
        if (bytes == nullptr || tile.sourceWidth <= 0 ||
            tile.sourceBottom <= tile.sourceTop || tile.sourceTop < 0) return false;
        const int logicalHeight = tile.sourceBottom - tile.sourceTop;
        const bool scaledCpuContent = tile.cpuBufferResource &&
            exactCpuTileHasContent(tile.exactCpuBuffer, tile.sourceWidth, logicalHeight);
        const std::uint64_t width = static_cast<std::uint64_t>(
            scaledCpuContent ? tile.exactCpuBuffer->contentWidth : tile.sourceWidth);
        const std::uint64_t height = static_cast<std::uint64_t>(
            scaledCpuContent ? tile.exactCpuBuffer->contentHeight : logicalHeight);
        if (width > UINT64_MAX / 4ULL || height > UINT64_MAX / (width * 4ULL)) {
            return false;
        }
        *bytes = width * height * 4ULL;
        return *bytes != 0;
    }

    std::vector<ntk::rolling::TextureHeadroomResident>
    textureHeadroomResidents() const {
        std::vector<ntk::rolling::TextureHeadroomResident> residents;
        residents.reserve(textures_.size());
        for (const auto& entry : textures_) {
            residents.push_back({
                {entry.first.structureEpoch, entry.first.page, entry.first.slot},
                entry.second.contentIdentity,
                entry.second.bytes,
                entry.second.lastUsedFrame,
            });
        }
        return residents;
    }

    void discardPooledTexturesForDirectProfile() noexcept {
        for (auto& pooled : pooledTextures_) {
            retireTextureName(pooled.texture, pooled.bytes);
        }
        pooledTextures_.clear();
        pooledTextureBytes_ = 0;
    }

    static GLenum drainGlErrors() noexcept {
        GLenum first = GL_NO_ERROR;
        for (GLenum error = glGetError(); error != GL_NO_ERROR; error = glGetError()) {
            if (first == GL_NO_ERROR) first = error;
        }
        return first;
    }

    bool refillTextureNameReserve(std::size_t targetCount) noexcept {
        if (spareTextureNames_.size() >= targetCount) return true;
        const std::size_t missing = targetCount - spareTextureNames_.size();
        if (missing > static_cast<std::size_t>(std::numeric_limits<GLsizei>::max())) return false;
        std::vector<GLuint> generated(missing, 0);
        (void)drainGlErrors();
        const std::int64_t begin = nowNanos();
        glGenTextures(static_cast<GLsizei>(generated.size()), generated.data());
        const std::int64_t elapsedUs = (nowNanos() - begin) / 1000;
        const GLenum error = drainGlErrors();
        const bool complete = error == GL_NO_ERROR &&
            std::all_of(generated.begin(), generated.end(), [](GLuint name) { return name != 0; });
        if (!complete) {
            const std::vector<GLuint> nonzero = [&] {
                std::vector<GLuint> result;
                result.reserve(generated.size());
                for (const GLuint name : generated) if (name != 0) result.push_back(name);
                return result;
            }();
            if (!nonzero.empty()) {
                glDeleteTextures(static_cast<GLsizei>(nonzero.size()), nonzero.data());
            }
            RLOGE(
                "texture name reserve failed requested=%zu existing=%zu error=0x%x elapsedUs=%lld",
                missing, spareTextureNames_.size(), error, static_cast<long long>(elapsedUs));
            return false;
        }
        spareTextureNames_.insert(
            spareTextureNames_.end(), generated.begin(), generated.end());
        const std::uint64_t ordinal = ++textureNameReserveRefills_;
        if (ordinal == 1 || ordinal % 8 == 0 || elapsedUs >= 16'000) {
            RLOGI(
                "texture name reserve filled ordinal=%llu added=%zu total=%zu elapsedUs=%lld",
                static_cast<unsigned long long>(ordinal), generated.size(),
                spareTextureNames_.size(), static_cast<long long>(elapsedUs));
        }
        return true;
    }

    GLuint takeReservedTextureName() noexcept {
        if (spareTextureNames_.empty()) return 0;
        const GLuint name = spareTextureNames_.back();
        spareTextureNames_.pop_back();
        return name;
    }

    bool ensureHostUploadScratch(std::size_t requiredBytes) noexcept {
        if (requiredBytes == 0) return false;
        if (hostUploadScratch_ != nullptr && hostUploadScratchCapacity_ >= requiredBytes) {
            return true;
        }
        std::unique_ptr<std::uint8_t[]> replacement(
            new (std::nothrow) std::uint8_t[requiredBytes]);
        if (replacement == nullptr) return false;
        hostUploadScratch_ = std::move(replacement);
        hostUploadScratchCapacity_ = requiredBytes;
        return true;
    }

    void delayIdleTextureRetirementUntil(std::int64_t deadlineNanos) noexcept {
        std::int64_t observed = nextIdleTextureRetirementNanos_.load(
            std::memory_order_acquire);
        while (observed < deadlineNanos &&
               !nextIdleTextureRetirementNanos_.compare_exchange_weak(
                   observed, deadlineNanos,
                   std::memory_order_release, std::memory_order_acquire)) {}
    }

    /** Called only while [mutex_] is held by the renderer owner. */
    bool prewarmPauseAllowsIdleTextureRetirementLocked(
            std::int64_t now,
            std::int64_t retirementQuietDeadline) const noexcept {
        if (!requestedPrewarmPaused_.load(std::memory_order_acquire)) return true;
        // A paused prewarm lane normally denotes physical input, so never put glFinish in the
        // short gaps of a real gesture.  The pause bit can outlive the final Surface callback,
        // however. Once every frame/backend lane has stayed empty for a full two seconds, the
        // remaining deletion debt must be allowed to settle or native quiescence is impossible.
        return now >= retirementQuietDeadline &&
            now - retirementQuietDeadline >= kTextureRetirementPausedExtraQuietNanos;
    }

    /** Applies the latest coalesced JNI pause command while [mutex_] is owned by the renderer. */
    void applyRequestedPrewarmPauseLocked() noexcept {
        if (!prewarmPauseCommandPending_.exchange(false, std::memory_order_acq_rel)) return;
        const bool paused = requestedPrewarmPaused_.load(std::memory_order_acquire);
        const std::int64_t now = nowNanos();
        delayIdleTextureRetirementUntil(now + kTextureRetirementIdleQuietNanos);
        if (paused) {
            // A new physical gesture revokes any bounded release-gap allowance before the first
            // MOVE can enqueue visible work. An upload already issued by the owner thread is
            // allowed to finish, but no successor can enter the EGL lane.
            directWifiImmediateResumeMaxPage_ = -1;
            directWifiFullPrewarmResumeNanos_ = 0;
            activeDirectWifiPrewarmSuppressed_ = false;
        } else if (directWifiTextureProfile_.load(std::memory_order_acquire) &&
                   (!hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) ||
                    cpuExactStorageProfile_.load(std::memory_order_acquire))) {
            // Repeated reading gestures leave roughly 0.6 s between flings. Waiting for the
            // generic 750 ms quiet gate meant that newly decoded current-episode tiles were never
            // uploaded in those gaps. Admit only the nearest unread page. An app-owned CPU tile
            // has no pending gralloc transfer, so the host renderer can start it after one real
            // display period and still revoke the lane synchronously on the next ACTION_DOWN.
            const int presentedMaxPage =
                lastPresentedMaxPageSnapshot_.load(std::memory_order_acquire);
            directWifiImmediateResumeMaxPage_ = presentedMaxPage >= 0
                ? presentedMaxPage + 1
                : -1;
            const bool hostCpuTile =
                hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
                cpuExactStorageProfile_.load(std::memory_order_acquire);
            directWifiFullPrewarmResumeNanos_ = now + kPrewarmResumeQuietNanos;
            activeDirectWifiPrewarmSuppressed_ = false;
            const std::int64_t immediateDeadline = hostCpuTile
                ? now + std::max<std::int64_t>(
                    1'000'000,
                    refreshPeriodNanos_ > 0
                        ? refreshPeriodNanos_
                        : kDefaultRefreshPeriodNanos)
                : now;
            if (nextPrewarmUploadNanos_ <= 0 ||
                nextPrewarmUploadNanos_ > immediateDeadline) {
                nextPrewarmUploadNanos_ = immediateDeadline;
            }
        } else {
            nextPrewarmUploadNanos_ = std::max(
                nextPrewarmUploadNanos_, now + kPrewarmResumeQuietNanos);
            directWifiImmediateResumeMaxPage_ = -1;
            directWifiFullPrewarmResumeNanos_ = 0;
            activeDirectWifiPrewarmSuppressed_ = false;
        }
    }

    void retireTextureName(GLuint texture, std::uint64_t bytes) noexcept {
        if (texture == 0) return;
        glDeleteTextures(1, &texture);
        textureRetirementDebt_.record(bytes);
        textureRetirementFenceDirty_ = true;
        delayIdleTextureRetirementUntil(
            nowNanos() + kTextureRetirementIdleQuietNanos);
    }

    bool armTextureRetirementFence() noexcept {
        if (!textureRetirementDebt_.pending()) return true;
        if (!textureRetirementFenceDirty_ && textureRetirementFence_ != nullptr) return true;
        const EGLContext current = eglGetCurrentContext();
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            (current != context_ && current != gpuCompositionContext_)) return false;

        (void)drainGlErrors();
        GLsync successor = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (successor != nullptr) glFlush();
        const GLenum error = drainGlErrors();
        if (successor == nullptr || error != GL_NO_ERROR) {
            if (successor != nullptr) glDeleteSync(successor);
            ++failedTextureRetirementFenceArms_;
            if (failedTextureRetirementFenceArms_ == 1 ||
                failedTextureRetirementFenceArms_ % 32 == 0) {
                RLOGE(
                    "texture retirement fence arm failed ordinal=%llu error=0x%x",
                    static_cast<unsigned long long>(failedTextureRetirementFenceArms_),
                    error);
            }
            return false;
        }
        if (textureRetirementFence_ != nullptr) {
            glDeleteSync(textureRetirementFence_);
        }
        textureRetirementFence_ = successor;
        textureRetirementFenceDirty_ = false;
        return true;
    }

    bool pollTextureRetirementFence() noexcept {
        if (!textureRetirementDebt_.pending()) return true;
        const EGLContext current = eglGetCurrentContext();
        if (textureRetirementFenceDirty_ || textureRetirementFence_ == nullptr ||
            display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            (current != context_ && current != gpuCompositionContext_)) return false;

        const GLenum wait = glClientWaitSync(textureRetirementFence_, 0, 0);
        if (wait == GL_TIMEOUT_EXPIRED) return false;
        if (wait != GL_ALREADY_SIGNALED && wait != GL_CONDITION_SATISFIED) {
            ++failedTextureRetirementFencePolls_;
            if (failedTextureRetirementFencePolls_ == 1 ||
                failedTextureRetirementFencePolls_ % 32 == 0) {
                RLOGE(
                    "texture retirement fence poll failed ordinal=%llu result=0x%x",
                    static_cast<unsigned long long>(failedTextureRetirementFencePolls_),
                    wait);
            }
            return false;
        }

        const std::size_t names = textureRetirementDebt_.names();
        const std::uint64_t bytes = textureRetirementDebt_.bytes();
        glDeleteSync(textureRetirementFence_);
        textureRetirementFence_ = nullptr;
        textureRetirementDebt_.completeBarrier(true);
        const std::uint64_t ordinal = ++completedTextureRetirementFences_;
        if (ordinal == 1 || ordinal % 32 == 0) {
            RLOGI(
                "texture retirement fence completed ordinal=%llu names=%zu bytes=%llu",
                static_cast<unsigned long long>(ordinal), names,
                static_cast<unsigned long long>(bytes));
        }
        return true;
    }

    bool settleTextureRetirementBeforeVisibleUpload() noexcept {
        if (!textureRetirementDebt_.pending()) return true;
        const EGLContext current = eglGetCurrentContext();
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            (current != context_ && current != gpuCompositionContext_ &&
             (pbuffer_ == EGL_NO_SURFACE ||
              eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE))) {
            ++failedTextureRetirementBarriers_;
            ++consecutiveTextureRetirementBarrierFailures_;
            RLOGE("texture retirement barrier failed before current-context acquisition");
            return false;
        }
        if (armTextureRetirementFence() && pollTextureRetirementFence()) return true;
        const std::size_t names = textureRetirementDebt_.names();
        const std::uint64_t bytes = textureRetirementDebt_.bytes();
        const GLenum errorBeforeBarrier = drainGlErrors();
        const std::int64_t begin = nowNanos();
        glFinish();
        const std::int64_t elapsedUs = (nowNanos() - begin) / 1000;
        const GLenum errorAfterBarrier = drainGlErrors();
        const bool succeeded =
            errorBeforeBarrier == GL_NO_ERROR && errorAfterBarrier == GL_NO_ERROR;
        textureRetirementDebt_.completeBarrier(succeeded);
        if (succeeded && textureRetirementFence_ != nullptr) {
            glDeleteSync(textureRetirementFence_);
            textureRetirementFence_ = nullptr;
        }
        if (succeeded) textureRetirementFenceDirty_ = false;
        if (!succeeded) {
            ++failedTextureRetirementBarriers_;
            ++consecutiveTextureRetirementBarrierFailures_;
            RLOGE(
                "texture retirement barrier failed names=%zu bytes=%llu before=0x%x after=0x%x elapsedUs=%lld",
                names, static_cast<unsigned long long>(bytes), errorBeforeBarrier,
                errorAfterBarrier, static_cast<long long>(elapsedUs));
            return false;
        }
        consecutiveTextureRetirementBarrierFailures_ = 0;
        if (spareTextureNames_.size() < kTextureNameReserveRefillThreshold &&
            !refillTextureNameReserve(kTextureNameReserveCount) &&
            spareTextureNames_.empty()) {
            RLOGE("texture retirement settled without a usable name reserve");
            return false;
        }
        const std::uint64_t ordinal = ++completedTextureRetirementBarriers_;
        if (ordinal == 1 || ordinal % 32 == 0 || elapsedUs >= 16'000) {
            RLOGI(
                "texture retirement barrier completed ordinal=%llu names=%zu bytes=%llu elapsedUs=%lld",
                static_cast<unsigned long long>(ordinal), names,
                static_cast<unsigned long long>(bytes), static_cast<long long>(elapsedUs));
        }
        return true;
    }

    bool prepareVisibleFrameTextureHeadroom(
            const FrameCommand& frame,
            bool directWifiFreshNames) noexcept {
        // Do not poll an optional retirement fence merely because a new visible scene arrived.
        // GLES specifies a zero timeout, but gfxstream may still synchronize its host command
        // pipe while servicing glClientWaitSync; a one-name completed fence measured 85 ms here.
        // The bounded two-generation pressure branch below polls/settles only when fresh storage
        // truly needs that debt, and the existing idle owner drains it when no frame is pending.
        // Appending an adjacent episode advances the layout epoch but does not mutate pixels that
        // were already published. Preserve an exact page/slot/Bitmap identity by re-keying its GL
        // mapping before headroom planning; otherwise the planner retires the old epoch and the
        // first boundary frame uploads every still-visible page again.
        for (const auto& tile : frame.tileView()) {
            if (textures_.find(tile.key) != textures_.end()) continue;
            auto exactPrior = textures_.end();
            for (auto candidate = textures_.begin(); candidate != textures_.end(); ++candidate) {
                const bool samePage = candidate->first.page == tile.key.page;
                const bool stablePrefixReindex =
                    candidate->first.page != tile.key.page &&
                    candidate->second.stableContentIdentity &&
                    (tile.hardwareBufferResource || tile.cpuBufferResource);
                if (candidate->first.structureEpoch != tile.key.structureEpoch &&
                    candidate->first.slot == tile.key.slot &&
                    (samePage || stablePrefixReindex) &&
                    candidate->second.contentIdentity == tile.contentIdentity &&
                    candidate->second.width == tile.sourceWidth &&
                    candidate->second.height == tile.sourceBottom - tile.sourceTop) {
                    exactPrior = candidate;
                    break;
                }
            }
            if (exactPrior == textures_.end()) continue;
            auto adopted = textures_.extract(exactPrior);
            adopted.key() = tile.key;
            adopted.mapped().lastUsedFrame = submittedFrames_ + 1;
            textures_.insert(std::move(adopted));
        }
        // A renderer normally receives this immutable profile before attachment. Still close the
        // transition case explicitly: storage pooled by an earlier ordinary profile is not a
        // legal fresh-name reserve and must leave the owner cache before planning direct uploads.
        if (directWifiFreshNames && !pooledTextures_.empty()) {
            discardPooledTexturesForDirectProfile();
        }
        std::vector<ntk::rolling::TextureHeadroomIncoming> incoming;
        incoming.reserve(frame.tileView().size());
        for (const auto& tile : frame.tileView()) {
            std::uint64_t bytes = 0;
            if (!plannedTextureBytes(tile, &bytes)) {
                RLOGE(
                    "texture headroom invalid tile epoch=%lld frameEpoch=%lld page=%d slot=%d source=%dx%d top=%d bottom=%d cpu=%d hardware=%d",
                    static_cast<long long>(tile.key.structureEpoch),
                    static_cast<long long>(frame.structureEpoch), tile.key.page, tile.key.slot,
                    tile.sourceWidth, tile.sourceHeight, tile.sourceTop, tile.sourceBottom,
                    tile.cpuBufferResource ? 1 : 0,
                    tile.hardwareBufferResource ? 1 : 0);
                return false;
            }
            incoming.push_back({
                {tile.key.structureEpoch, tile.key.page, tile.key.slot},
                tile.contentIdentity,
                bytes,
            });
        }
        const auto residents = textureHeadroomResidents();
        const std::uint64_t textureBudget = effectiveTextureBudget(frame.structureEpoch);
        const auto plan = ntk::rolling::planVisibleTextureHeadroom(
            residents,
            residentTextureBytes_,
            incoming,
            frame.structureEpoch,
            directWifiFreshNames,
            textureBudget,
            kMaxResidentTextureCount);
        if (!plan.valid) {
            RLOGE(
                "texture headroom invalid plan epoch=%lld incoming=%zu residents=%zu residentBytes=%llu budget=%llu freshNames=%zu freshBytes=%llu projectedNames=%zu projectedBytes=%llu overflow=%d",
                static_cast<long long>(frame.structureEpoch), incoming.size(), residents.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                static_cast<unsigned long long>(textureBudget), plan.freshNames,
                static_cast<unsigned long long>(plan.freshBytes), plan.projectedNames,
                static_cast<unsigned long long>(plan.projectedBytes),
                plan.arithmeticOverflow ? 1 : 0);
            return false;
        }
        for (const std::size_t index : plan.evictionIndices) {
            if (index >= residents.size()) {
                RLOGE("texture headroom invalid eviction index=%zu residents=%zu", index,
                      residents.size());
                return false;
            }
            const auto& key = residents[index].key;
            const auto victim = textures_.find({key.structureEpoch, key.page, key.slot});
            if (victim != textures_.end()) {
                eraseTexture(victim, !directWifiFreshNames);
            }
        }
        if (textureRetirementFenceDirty_) {
            // Fence creation is opportunistic here. Failure leaves the debt dirty and therefore
            // unprovable; the unchanged pressure/name-reserve paths below fall back to glFinish.
            (void)armTextureRetirementFence();
        }
        // The plan counts evicted storage as logically available. Driver backing may retire later,
        // but serializing every immutable-identity replacement made glFinish a frame-by-frame
        // 100-300 ms stall on gfxstream. Batch that debt inside one bounded two-generation window;
        // genuine idle still settles any remainder even when this pressure gate is not reached.
        if (plan.freshNames > 0 &&
            ntk::rolling::shouldSettleTextureRetirementBeforeVisibleUpload(
                textureRetirementDebt_, residentTextureBytes_, textures_.size(), plan,
                textureBudget, kMaxResidentTextureCount) &&
            !settleTextureRetirementBeforeVisibleUpload()) {
            RLOGE(
                "texture headroom retirement settle failed debtNames=%zu debtBytes=%llu freshNames=%zu freshBytes=%llu",
                textureRetirementDebt_.names(),
                static_cast<unsigned long long>(textureRetirementDebt_.bytes()),
                plan.freshNames, static_cast<unsigned long long>(plan.freshBytes));
            return false;
        }
        if (plan.freshNames > spareTextureNames_.size()) {
            // With the 24-name resident ceiling, exhausting the 256-name reserve necessarily
            // follows retired names. Settle and refill after applying the complete victim plan but
            // before the first upload. This is a fail-safe for exceptionally long uninterrupted
            // sessions; normal scrolling refills at the preceding genuine-idle boundary.
            if (textureRetirementDebt_.pending() &&
                !settleTextureRetirementBeforeVisibleUpload()) {
                RLOGE("texture headroom name-reserve settle failed need=%zu spare=%zu",
                      plan.freshNames, spareTextureNames_.size());
                return false;
            }
            if (plan.freshNames > spareTextureNames_.size() &&
                !refillTextureNameReserve(kTextureNameReserveCount)) {
                RLOGE("texture headroom name-reserve refill failed need=%zu spare=%zu",
                      plan.freshNames, spareTextureNames_.size());
                return false;
            }
            if (plan.freshNames > spareTextureNames_.size()) {
                RLOGE("texture headroom name-reserve insufficient need=%zu spare=%zu",
                      plan.freshNames, spareTextureNames_.size());
                return false;
            }
        }
        if (plan.protectedFrameOversize) {
            RLOGI(
                "texture visible protected oversize epoch=%lld tiles=%zu projectedBytes=%llu budget=%llu projectedNames=%zu limit=%zu overflow=%d",
                static_cast<long long>(frame.structureEpoch), frame.tileView().size(),
                static_cast<unsigned long long>(plan.projectedBytes),
                static_cast<unsigned long long>(textureBudget),
                plan.projectedNames, kMaxResidentTextureCount,
                plan.arithmeticOverflow ? 1 : 0);
        }
        return true;
    }

    /**
     * A producer-scene translation cannot introduce pixels. Once every exact scene texture is
     * resident, repeating headroom planning and a driver fence poll on every MOVE only adds host
     * synchronization to an otherwise geometry-only frame. Any missing/replaced identity falls
     * back to the full admission path before drawing.
     */
    bool hasResidentProducerGeometryScene(const FrameCommand& frame) const noexcept {
        if (!frame.producerSceneGeometryOnly || frame.tileView().empty()) return false;
        for (const auto& tile : frame.tileView()) {
            const auto resident = textures_.find(tile.key);
            if (resident == textures_.end() ||
                resident->second.contentIdentity != tile.contentIdentity ||
                resident->second.width != tile.sourceWidth ||
                resident->second.height != tile.sourceBottom - tile.sourceTop) {
                return false;
            }
        }
        return true;
    }

    bool hasOptionalPrewarmTextureHeadroom(
            const FrameTile& tile,
            bool directWifiFreshNames) noexcept {
        if (directWifiFreshNames && !pooledTextures_.empty()) {
            discardPooledTexturesForDirectProfile();
        }
        // Optional work never owns a synchronous driver barrier. A later visible frame settles the
        // debt after applying its complete protected-frame eviction plan.
        if (textureRetirementDebt_.pending()) return false;
        std::uint64_t bytes = 0;
        if (!plannedTextureBytes(tile, &bytes)) return false;
        const auto residents = textureHeadroomResidents();
        return ntk::rolling::canUploadOptionalTextureWithoutEviction(
            residents,
            residentTextureBytes_,
            {{tile.key.structureEpoch, tile.key.page, tile.key.slot},
             tile.contentIdentity, bytes},
            directWifiFreshNames,
            effectiveTextureBudget(tile.key.structureEpoch),
            kMaxResidentTextureCount);
    }

    /**
     * Imports pooled exact-pixel storage directly into GL.
     *
     * HostExactHardwareTilePool already allocates immutable RGBA AHardwareBuffers with
     * GPU_SAMPLED_IMAGE usage. Copying those buffers through a CPU scratch and glTexImage2D made
     * a single wide manga tile consume 60-220 ms on gfxstream. EGLImage preserves the exact same
     * storage and lifetime while removing both copies. Capacity padding is excluded in the
     * shader by the per-texture scale; unsupported drivers simply return false and retain the
     * established packed-upload fallback below.
     */
    bool importExactHardwareBufferTile(
            const FrameTile& tile,
            AHardwareBuffer* hardwareBuffer,
            const AHardwareBuffer_Desc& descriptor,
            std::uint64_t useFrame,
            bool directWifiTextureProfile) noexcept {
        if (hardwareBuffer == nullptr || getNativeClientBuffer_ == nullptr ||
            createImage_ == nullptr || destroyImage_ == nullptr ||
            imageTargetTexture_ == nullptr || display_ == EGL_NO_DISPLAY ||
            tile.sourceWidth <= 0 || tile.sourceBottom <= tile.sourceTop ||
            descriptor.width < static_cast<std::uint32_t>(tile.sourceWidth) ||
            descriptor.height < static_cast<std::uint32_t>(tile.sourceBottom - tile.sourceTop)) {
            return false;
        }
        EGLClientBuffer clientBuffer = getNativeClientBuffer_(hardwareBuffer);
        if (clientBuffer == nullptr) return false;
        constexpr EGLint attributes[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE,
        };
        EGLImageKHR image = createImage_(
            display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            clientBuffer, attributes);
        if (image == EGL_NO_IMAGE_KHR) return false;

        GLuint texture = takeReservedTextureName();
        if (texture == 0) {
            destroyImage_(display_, image);
            return false;
        }
        (void)drainGlErrors();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        imageTargetTexture_(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(image));
        const GLenum error = drainGlErrors();
        if (error != GL_NO_ERROR) {
            destroyImage_(display_, image);
            retireTextureName(texture, 0);
            return false;
        }

        const int width = tile.sourceWidth;
        const int height = tile.sourceBottom - tile.sourceTop;
        const std::uint64_t bytes = static_cast<std::uint64_t>(width) *
            static_cast<std::uint64_t>(height) * 4ULL;
        const float scaleX = static_cast<float>(width) /
            static_cast<float>(descriptor.width);
        const float scaleY = static_cast<float>(height) /
            static_cast<float>(descriptor.height);
        auto existing = textures_.find(tile.key);
        if (existing != textures_.end()) {
            residentTextureBytes_ -= std::min(residentTextureBytes_, existing->second.bytes);
            TextureTile previous = std::move(existing->second);
            existing->second = TextureTile{
                texture, image, tile.contentIdentity,
                tile.hardwareBufferResource || tile.cpuBufferResource, width, height,
                static_cast<int>(descriptor.width),
                static_cast<int>(descriptor.height),
                scaleX, scaleY, bytes, useFrame};
            recycleTextureStorage(std::move(previous), !directWifiTextureProfile);
        } else {
            textures_.emplace(tile.key, TextureTile{
                texture, image, tile.contentIdentity,
                tile.hardwareBufferResource || tile.cpuBufferResource, width, height,
                static_cast<int>(descriptor.width),
                static_cast<int>(descriptor.height),
                scaleX, scaleY, bytes, useFrame});
        }
        residentTextureBytes_ += bytes;
        const std::uint64_t ordinal = ++importedHardwareBufferTextures_;
        if (ordinal == 1 || ordinal % 256 == 0) {
            RLOGI(
                "host exact EGLImage imported ordinal=%llu page=%d slot=%d logical=%dx%d capacity=%ux%u bytes=%llu",
                static_cast<unsigned long long>(ordinal), tile.key.page, tile.key.slot,
                width, height, descriptor.width, descriptor.height,
                static_cast<unsigned long long>(bytes));
        }
        return true;
    }

    bool uploadTile(
            JNIEnv* env,
            const FrameTile& tile,
            std::uint64_t useFrame,
            bool directWifiTextureProfile,
            bool* issuedGlUpload = nullptr) noexcept {
        if (issuedGlUpload != nullptr) *issuedGlUpload = false;
        auto existing = textures_.find(tile.key);
        if (existing == textures_.end()) {
            // Appending a newly ready page advances the structural epoch even though every
            // existing page index, slot and immutable Bitmap identity is unchanged. Treating the
            // epoch as texture identity discarded and re-imported the complete visible viewport
            // for each one-page network arrival; gfxstream then blocked presentation for
            // 200-400 ms. Migrate only an exact logical/storage match. Replacements, reorderings
            // and geometry changes retain the ordinary fresh-upload path below.
            const int expectedHeight = tile.sourceBottom - tile.sourceTop;
            for (auto prior = textures_.begin(); prior != textures_.end(); ++prior) {
                const bool stablePrefixReindex =
                    prior->first.page != tile.key.page &&
                    prior->second.stableContentIdentity &&
                    (tile.hardwareBufferResource || tile.cpuBufferResource);
                if (prior->first.structureEpoch == tile.key.structureEpoch ||
                    (prior->first.page != tile.key.page && !stablePrefixReindex) ||
                    prior->first.slot != tile.key.slot ||
                    prior->second.contentIdentity != tile.contentIdentity ||
                    prior->second.width != tile.sourceWidth ||
                    prior->second.height != expectedHeight) {
                    continue;
                }
                TextureTile migrated = std::move(prior->second);
                textures_.erase(prior);
                existing = textures_.emplace(tile.key, std::move(migrated)).first;
                ++migratedAppendOnlyTextures_;
                break;
            }
        }
        if (existing != textures_.end() &&
            existing->second.contentIdentity == tile.contentIdentity) {
            existing->second.lastUsedFrame = useFrame;
            return true;
        }

        AndroidBitmapInfo info{};
        const int sourceSpan = tile.sourceBottom - tile.sourceTop;
        const bool rawHardwareBuffer = tile.hardwareBufferResource;
        const bool rawCpuBuffer = tile.cpuBufferResource;
        const bool rawNativeResource = rawHardwareBuffer || rawCpuBuffer;
        const int infoResult = rawNativeResource
            ? ANDROID_BITMAP_RESULT_BAD_PARAMETER
            : AndroidBitmap_getInfo(env, tile.bitmap, &info);
        const bool hardwareBitmap = infoResult == ANDROID_BITMAP_RESULT_SUCCESS &&
            (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0U;
        if (!rawNativeResource && (infoResult != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            (!hardwareBitmap &&
             (static_cast<int>(info.width) != tile.sourceWidth ||
              (static_cast<int>(info.height) != sourceSpan &&
               static_cast<int>(info.height) != tile.sourceHeight))) ||
            (hardwareBitmap &&
             (static_cast<int>(info.width) < tile.sourceWidth ||
              static_cast<int>(info.height) < sourceSpan)))) {
            RLOGE(
                "texture upload rejected stage=bitmap-info page=%d slot=%d result=%d format=%u flags=0x%x size=%ux%u expectedWidth=%d expectedSpan=%d expectedHeight=%d identity=%d exception=%d",
                tile.key.page, tile.key.slot, infoResult,
                static_cast<unsigned>(info.format), info.flags, info.width, info.height,
                tile.sourceWidth, sourceSpan, tile.sourceHeight, tile.bitmapIdentity,
                env->ExceptionCheck() ? 1 : 0);
            return false;
        }

        using GetHardwareBuffer = int (*)(JNIEnv*, jobject, AHardwareBuffer**);
        using DescribeHardwareBuffer = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
        using LockHardwareBuffer = int (*)(AHardwareBuffer*, std::uint64_t, std::int32_t,
                                           const ARect*, void**);
        using UnlockHardwareBuffer = int (*)(AHardwareBuffer*, std::int32_t*);
        using ReleaseHardwareBuffer = void (*)(AHardwareBuffer*);
        struct HardwareBitmapSymbols {
            void* graphics = nullptr;
            void* android = nullptr;
            GetHardwareBuffer get = nullptr;
            DescribeHardwareBuffer describe = nullptr;
            LockHardwareBuffer lock = nullptr;
            UnlockHardwareBuffer unlock = nullptr;
            ReleaseHardwareBuffer release = nullptr;

            bool valid() const noexcept {
                return get != nullptr && describe != nullptr && lock != nullptr &&
                    unlock != nullptr && release != nullptr;
            }
        };
        static const HardwareBitmapSymbols hardwareSymbols = [] {
            HardwareBitmapSymbols result{};
            result.graphics = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
            result.android = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
            if (result.graphics != nullptr) {
                result.get = reinterpret_cast<GetHardwareBuffer>(
                    dlsym(result.graphics, "AndroidBitmap_getHardwareBuffer"));
            }
            if (result.android != nullptr) {
                result.describe = reinterpret_cast<DescribeHardwareBuffer>(
                    dlsym(result.android, "AHardwareBuffer_describe"));
                result.lock = reinterpret_cast<LockHardwareBuffer>(
                    dlsym(result.android, "AHardwareBuffer_lock"));
                result.unlock = reinterpret_cast<UnlockHardwareBuffer>(
                    dlsym(result.android, "AHardwareBuffer_unlock"));
                result.release = reinterpret_cast<ReleaseHardwareBuffer>(
                    dlsym(result.android, "AHardwareBuffer_release"));
            }
            return result;
        }();

        void* pixels = nullptr;
        AHardwareBuffer* hardwareBuffer = nullptr;
        AHardwareBuffer_Desc hardwareDescriptor{};
        ExactCpuTileStorage* cpuStorage = nullptr;
        bool hardwareBufferAcquired = false;
        int lockResult = ANDROID_BITMAP_RESULT_SUCCESS;
        if (rawCpuBuffer) {
            cpuStorage = tile.exactCpuBuffer;
            if (!exactCpuTileHasContent(
                    cpuStorage, tile.sourceWidth, sourceSpan) ||
                cpuStorage->pixels == nullptr) {
                lockResult = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
            } else {
                pixels = cpuStorage->pixels;
            }
        } else if (rawHardwareBuffer || hardwareBitmap) {
            if (!hardwareSymbols.valid()) {
                RLOGE(
                    "texture upload rejected stage=hardware-symbols page=%d slot=%d identity=%d",
                    tile.key.page, tile.key.slot, tile.bitmapIdentity);
                return false;
            }
            if (rawHardwareBuffer) {
                hardwareBuffer = tile.exactHardwareBuffer;
                lockResult = hardwareBuffer != nullptr
                    ? ANDROID_BITMAP_RESULT_SUCCESS
                    : ANDROID_BITMAP_RESULT_BAD_PARAMETER;
            } else {
                lockResult = hardwareSymbols.get(env, tile.bitmap, &hardwareBuffer);
                hardwareBufferAcquired = lockResult == ANDROID_BITMAP_RESULT_SUCCESS &&
                    hardwareBuffer != nullptr;
            }
            if (lockResult == ANDROID_BITMAP_RESULT_SUCCESS && hardwareBuffer != nullptr) {
                hardwareSymbols.describe(hardwareBuffer, &hardwareDescriptor);
                if (hardwareDescriptor.layers != 1 ||
                    hardwareDescriptor.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
                    hardwareDescriptor.width < static_cast<std::uint32_t>(tile.sourceWidth) ||
                    hardwareDescriptor.height < static_cast<std::uint32_t>(sourceSpan) ||
                    hardwareDescriptor.stride < static_cast<std::uint32_t>(tile.sourceWidth)) {
                    lockResult = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
                } else if (rawHardwareBuffer && importExactHardwareBufferTile(
                        tile,
                        hardwareBuffer,
                        hardwareDescriptor,
                        useFrame,
                        directWifiTextureProfile)) {
                    // FrameTile owns an acquired native reference until releaseTile(). EGLImage
                    // takes its own storage reference, so this import remains valid even if the
                    // pool retires its owner immediately after the command completes.
                    if (issuedGlUpload != nullptr) *issuedGlUpload = true;
                    return true;
                } else {
                    lockResult = hardwareSymbols.lock(
                        hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
                        -1, nullptr, &pixels);
                }
            } else if (hardwareBuffer == nullptr) {
                lockResult = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
            }
        } else {
            lockResult = AndroidBitmap_lockPixels(env, tile.bitmap, &pixels);
        }
        if (lockResult != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
            RLOGE(
                "texture upload rejected stage=bitmap-lock page=%d slot=%d hardware=%d raw=%d result=%d pixels=%p size=%ux%u identity=%d exception=%d resident=%zu bytes=%llu debtNames=%zu debtBytes=%llu",
                tile.key.page, tile.key.slot, hardwareBitmap ? 1 : 0,
                rawHardwareBuffer ? 1 : 0,
                lockResult, pixels, info.width, info.height, tile.bitmapIdentity,
                env->ExceptionCheck() ? 1 : 0, textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                textureRetirementDebt_.names(),
                static_cast<unsigned long long>(textureRetirementDebt_.bytes()));
            if ((rawHardwareBuffer || hardwareBitmap) &&
                lockResult == ANDROID_BITMAP_RESULT_SUCCESS && hardwareBuffer != nullptr) {
                hardwareSymbols.unlock(hardwareBuffer, nullptr);
            }
            if (hardwareBufferAcquired) {
                hardwareSymbols.release(hardwareBuffer);
            } else if (!rawNativeResource && !hardwareBitmap &&
                       lockResult == ANDROID_BITMAP_RESULT_SUCCESS) {
                AndroidBitmap_unlockPixels(env, tile.bitmap);
            }
            return false;
        }

        const int logicalWidth = tile.sourceWidth;
        const int logicalHeight = sourceSpan;
        const int storageWidth = rawCpuBuffer
            ? static_cast<int>(cpuStorage->contentWidth)
            : logicalWidth;
        const int storageHeight = rawCpuBuffer
            ? static_cast<int>(cpuStorage->contentHeight)
            : logicalHeight;
        const bool hardwareStorage = rawHardwareBuffer || hardwareBitmap;
        const bool externalStorage = hardwareStorage || rawCpuBuffer;
        const int bitmapSourceTop = !externalStorage &&
            static_cast<int>(info.width) == tile.sourceWidth &&
            static_cast<int>(info.height) == tile.sourceHeight
            ? tile.sourceTop
            : 0;
        const std::size_t sourceStride = rawCpuBuffer
            ? cpuStorage->strideBytes
            : (hardwareStorage
                ? static_cast<std::size_t>(hardwareDescriptor.stride) * 4U
                : static_cast<std::size_t>(info.stride));
        auto* tilePixels = static_cast<std::uint8_t*>(pixels) +
            static_cast<std::size_t>(bitmapSourceTop) * sourceStride;
        const std::uint64_t textureBytes =
            static_cast<std::uint64_t>(storageWidth) *
            static_cast<std::uint64_t>(storageHeight) * 4ULL;
        const std::size_t tightStride = static_cast<std::size_t>(storageWidth) * 4U;
        const std::uint8_t* uploadPixels = tilePixels;
        std::size_t uploadStride = sourceStride;
        if (hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire)) {
            if (textureBytes > static_cast<std::uint64_t>(SIZE_MAX) ||
                !ensureHostUploadScratch(static_cast<std::size_t>(textureBytes))) {
                const int ignoredUnlock = rawCpuBuffer
                    ? 0
                    : (hardwareStorage
                        ? hardwareSymbols.unlock(hardwareBuffer, nullptr)
                        : AndroidBitmap_unlockPixels(env, tile.bitmap));
                (void)ignoredUnlock;
                if (hardwareBufferAcquired) hardwareSymbols.release(hardwareBuffer);
                RLOGE(
                    "texture upload rejected stage=host-pack-allocation page=%d slot=%d bytes=%llu",
                    tile.key.page, tile.key.slot,
                    static_cast<unsigned long long>(textureBytes));
                return false;
            }
            for (int row = 0; row < storageHeight; ++row) {
                std::memcpy(
                    hostUploadScratch_.get() + static_cast<std::size_t>(row) * tightStride,
                    tilePixels + static_cast<std::size_t>(row) * sourceStride,
                    tightStride);
            }
            uploadPixels = hostUploadScratch_.get();
            uploadStride = tightStride;
            const std::uint64_t ordinal = ++hostPackedUploads_;
            if (ordinal == 1 || ordinal % 256 == 0) {
                RLOGI(
                    "host texture upload packed ordinal=%llu page=%d slot=%d width=%d height=%d sourceStride=%zu bytes=%llu",
                    static_cast<unsigned long long>(ordinal), tile.key.page, tile.key.slot,
                    storageWidth, storageHeight, sourceStride,
                    static_cast<unsigned long long>(textureBytes));
            }
        }

        (void)drainGlErrors();
        GLuint texture = 0;
        bool allocatedStorage = false;
        bool generatedTexture = false;
        TextureTile previousTextureStorage{};
        const bool replaceExistingWithFreshName =
            existing != textures_.end() &&
            (directWifiTextureProfile ||
             existing->second.importedImage != EGL_NO_IMAGE_KHR);
        if (existing != textures_.end() && !replaceExistingWithFreshName) {
            // A Java LRU eviction may recreate an immutable Bitmap for the same logical tile.
            // Once the predecessor SurfaceControl buffer has latched this renderer is the sole
            // owner of the GL name, so replacing its pixels in place is identity-safe.
            texture = existing->second.texture;
            allocatedStorage = existing->second.storageWidth == storageWidth &&
                existing->second.storageHeight == storageHeight;
        } else if (!directWifiTextureProfile) {
            for (auto pooled = pooledTextures_.begin(); pooled != pooledTextures_.end(); ++pooled) {
                if (pooled->storageWidth != storageWidth ||
                    pooled->storageHeight != storageHeight || pooled->texture == 0) {
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
            texture = takeReservedTextureName();
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
        glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(uploadStride / 4U));
        if (allocatedStorage) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(storageWidth),
                            static_cast<GLsizei>(storageHeight),
                            GL_RGBA, GL_UNSIGNED_BYTE, uploadPixels);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                         static_cast<GLsizei>(storageWidth),
                         static_cast<GLsizei>(storageHeight),
                         0, GL_RGBA, GL_UNSIGNED_BYTE, uploadPixels);
        }
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        const int unlockResult = rawCpuBuffer
            ? 0
            : (hardwareStorage
                ? hardwareSymbols.unlock(hardwareBuffer, nullptr)
                : AndroidBitmap_unlockPixels(env, tile.bitmap));
        if (hardwareBufferAcquired) hardwareSymbols.release(hardwareBuffer);
        GLenum uploadError = drainGlErrors();
        if (unlockResult != 0 && uploadError == GL_NO_ERROR) {
            uploadError = GL_INVALID_OPERATION;
        }
        if (texture == 0 || uploadError != GL_NO_ERROR) {
            // An existing mapping is retried with the old logical identity on the next frame.
            // New or pooled names have no authoritative mapping yet and can be discarded.
            if ((existing == textures_.end() || replaceExistingWithFreshName) && texture != 0) {
                retireTextureName(texture, textureBytes);
            }
            RLOGE(
                "texture upload failed page=%d slot=%d error=0x%x texture=%u generated=%d replacement=%d resident=%zu bytes=%llu debtNames=%zu debtBytes=%llu",
                tile.key.page, tile.key.slot, uploadError, texture, generatedTexture ? 1 : 0,
                replaceExistingWithFreshName ? 1 : 0, textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                textureRetirementDebt_.names(),
                static_cast<unsigned long long>(textureRetirementDebt_.bytes()));
            return false;
        }
        if (existing != textures_.end()) {
            residentTextureBytes_ -= std::min(
                residentTextureBytes_, existing->second.bytes);
            if (replaceExistingWithFreshName) {
                previousTextureStorage = std::move(existing->second);
            }
            existing->second = TextureTile{
                texture, EGL_NO_IMAGE_KHR, tile.contentIdentity,
                tile.hardwareBufferResource || tile.cpuBufferResource,
                logicalWidth, logicalHeight, storageWidth, storageHeight,
                1.0F, 1.0F, textureBytes, useFrame};
        } else {
            textures_.emplace(tile.key, TextureTile{
                texture, EGL_NO_IMAGE_KHR, tile.contentIdentity,
                tile.hardwareBufferResource || tile.cpuBufferResource,
                logicalWidth, logicalHeight, storageWidth, storageHeight,
                1.0F, 1.0F,
                textureBytes, useFrame});
        }
        residentTextureBytes_ += textureBytes;
        if (replaceExistingWithFreshName) {
            // The direct-Wi-Fi profile also forbids same-key storage mutation: an immutable
            // Bitmap can be recreated after Java eviction while the old GL name is still sampled
            // by a latched gfxstream buffer. Deleting the detached name lets GL defer its actual
            // release until references retire, without a blocking glTexSubImage fence export.
            recycleTextureStorage(
                std::move(previousTextureStorage), !directWifiTextureProfile);
        }
        if (issuedGlUpload != nullptr) *issuedGlUpload = true;
        return true;
    }

    PrewarmUploadResult uploadPrewarmTile(JNIEnv* env, FrameTile& tile) noexcept {
        const bool directWifiFreshNames = usesFreshTextureNames();
        // Kotlin publishes a coalesced snapshot whenever another decoded page arrives. Most
        // snapshots therefore contain already-resident current/near-forward tiles as well as the
        // new tail. Do not issue glFlush for those no-op identities: on gfxstream hundreds of
        // redundant flushes fill the host command queue and make the following visible frame's
        // acquire-fence export block for an entire display interval.
        const auto resident = textures_.find(tile.key);
        if (resident != textures_.end() &&
            resident->second.contentIdentity == tile.contentIdentity) {
            ++skippedResidentPrewarmTiles_;
            releaseTile(env, tile);
            return PrewarmUploadResult::CONSUMED_WITHOUT_UPLOAD;
        }
        if (textureRetirementDebt_.pending()) {
            return PrewarmUploadResult::DEFERRED_FOR_RETIREMENT;
        }
        // Optional runway work cannot consume the nearest visible cache just to make a farther
        // tile resident. Drop this JNI reference; Kotlin still owns the immutable Bitmap and a
        // later resident snapshot may offer it again after visible work changes residency.
        if (!hasOptionalPrewarmTextureHeadroom(tile, directWifiFreshNames)) {
            // A direct-profile transition can purge an ordinary storage pool inside the helper.
            // Preserve this exact sealed snapshot entry until the owner settles that new debt.
            if (textureRetirementDebt_.pending()) {
                return PrewarmUploadResult::DEFERRED_FOR_RETIREMENT;
            }
            ++skippedPrewarmHeadroomTiles_;
            releaseTile(env, tile);
            return PrewarmUploadResult::SKIPPED_FOR_HEADROOM;
        }
        const std::int64_t begin = nowNanos();
        bool issuedGlUpload = false;
        const bool uploaded = uploadTile(
            env, tile, ++textureUseSerial_, directWifiFreshNames, &issuedGlUpload);
        if (uploaded && issuedGlUpload) {
            // Only a real new allocation reaches this branch; same-content token/epoch migration
            // is CPU-only. Queue that one paced upload asynchronously. A synchronous glFinish
            // costs 93-397 ms on gfxstream and holds the renderer owner away from every physical
            // frame. The bounded resident runway gives the host worker several gestures before
            // this texture can become visible, while ACTION_DOWN closes further admission.
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
        } else if (!uploaded) {
            const std::uint64_t ordinal = ++failedPrewarmTiles_;
            if (ordinal == 1 || ordinal % 16 == 0) {
                RLOGE("texture prewarm failed ordinal=%llu page=%d slot=%d",
                      static_cast<unsigned long long>(ordinal), tile.key.page, tile.key.slot);
            }
        }
        releaseTile(env, tile);
        return uploaded && issuedGlUpload
            ? PrewarmUploadResult::UPLOADED
            : PrewarmUploadResult::CONSUMED_WITHOUT_UPLOAD;
    }

    void recycleTextureStorage(TextureTile&& texture, bool allowPool) noexcept {
        if (texture.texture == 0) return;
        texture.contentIdentity = 0;
        texture.lastUsedFrame = 0;
        if (texture.importedImage != EGL_NO_IMAGE_KHR) {
            // EGLImage owns immutable pooled storage and cannot be resized/reused as ordinary GL
            // allocation. Destroying the image and deleting the name only releases this GL view;
            // the Java HardwareBuffer pool keeps its independent storage reference.
            if (destroyImage_ != nullptr && display_ != EGL_NO_DISPLAY) {
                destroyImage_(display_, texture.importedImage);
            }
            texture.importedImage = EGL_NO_IMAGE_KHR;
            retireTextureName(texture.texture, texture.bytes);
            return;
        }
        // gfxstream can keep the GL name referenced after the logical frame has latched. The
        // exact-current direct-Wi-Fi profile has enough idle runway to allocate ahead, so prefer
        // a fresh name over an unsafe glTexSubImage into storage still sampled by BufferQueue.
        if (allowPool &&
            texture.bytes <= kMaxPooledTextureBytes &&
            pooledTextures_.size() < kMaxPooledTextureCount &&
            pooledTextureBytes_ + texture.bytes <= kMaxPooledTextureBytes) {
            pooledTextureBytes_ += texture.bytes;
            pooledTextures_.push_back(std::move(texture));
            return;
        }
        retireTextureName(texture.texture, texture.bytes);
    }

    void eraseTexture(
            std::unordered_map<TileKey, TextureTile, TileKeyHash>::iterator entry,
            bool allowPool) noexcept {
        if (entry == textures_.end()) return;
        residentTextureBytes_ -= std::min(residentTextureBytes_, entry->second.bytes);
        TextureTile storage = std::move(entry->second);
        textures_.erase(entry);
        recycleTextureStorage(std::move(storage), allowPool);
        ++evictedTextures_;
    }

    void eraseTexture(
            std::unordered_map<TileKey, TextureTile, TileKeyHash>::iterator entry) noexcept {
        eraseTexture(
            entry,
            !usesFreshTextureNames());
    }

    void pruneTexturesToBudget(
            std::int64_t structureEpoch,
            const std::unordered_set<TileKey, TileKeyHash>& protectedKeys) noexcept {
        // Append-only epochs preserve immutable texture identities. Visible mappings are re-keyed
        // before this function, while old offscreen mappings remain ordinary budget/LRU
        // candidates below. Eagerly deleting every prior-epoch EGLImage made each one-page append
        // destroy 20-30 images and settle their driver fences in one physical frame.

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
        const std::uint64_t budget = effectiveTextureBudget(structureEpoch);
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
        lastPresentedTextureKeys_.reserve(frame.tileView().size());
        lastPresentedMinPage_ = -1;
        lastPresentedMaxPage_ = -1;
        for (const auto& tile : frame.tileView()) {
            lastPresentedTextureKeys_.insert(tile.key);
            if (lastPresentedMinPage_ < 0 || tile.key.page < lastPresentedMinPage_) {
                lastPresentedMinPage_ = tile.key.page;
            }
            if (tile.key.page > lastPresentedMaxPage_) lastPresentedMaxPage_ = tile.key.page;
        }
        lastPresentedStructureEpoch_ = frame.structureEpoch;
        // Physical-input pause/resume is driven from the UI/JNI thread. Publish only the page
        // scalar it needs instead of reading renderer-owned frame state across threads.
        lastPresentedMaxPageSnapshot_.store(lastPresentedMaxPage_, std::memory_order_release);
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

    bool lockCpuTileForRead(
            JNIEnv* env,
            const FrameTile& tile,
            CpuTileReadView* view) const noexcept {
        if (view != nullptr) *view = {};
        if (env == nullptr || view == nullptr || tile.sourceTop < 0 ||
            tile.sourceBottom <= tile.sourceTop || tile.sourceWidth <= 0 ||
            tile.sourceHeight < tile.sourceBottom) {
            return false;
        }
        const int sourceSpan = tile.sourceBottom - tile.sourceTop;
        if (tile.cpuBufferResource) {
            const ExactCpuTileStorage* storage = tile.exactCpuBuffer;
            if (!exactCpuTileHasContent(
                    storage, tile.sourceWidth, sourceSpan) ||
                storage->pixels == nullptr) {
                return false;
            }
            *view = {
                .pixels = storage->pixels,
                .strideBytes = storage->strideBytes,
                .pixelWidth = static_cast<int>(storage->contentWidth),
                .pageRowOrigin = tile.sourceTop,
                .rowCount = static_cast<int>(storage->contentHeight),
                .logicalRowCount = sourceSpan,
            };
            return true;
        }

        AHardwareBuffer* hardwareBuffer = nullptr;
        bool hardwareBufferAcquired = false;
        bool hardwareBacked = tile.hardwareBufferResource;
        AndroidBitmapInfo bitmapInfo{};
        if (tile.hardwareBufferResource) {
            hardwareBuffer = tile.exactHardwareBuffer;
        } else {
            if (tile.bitmap == nullptr ||
                AndroidBitmap_getInfo(env, tile.bitmap, &bitmapInfo) !=
                    ANDROID_BITMAP_RESULT_SUCCESS ||
                bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
                bitmapInfo.width < static_cast<std::uint32_t>(tile.sourceWidth)) {
                return false;
            }
            hardwareBacked =
                (bitmapInfo.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0U;
            if (!hardwareBacked) {
                const bool fullPage =
                    bitmapInfo.height >= static_cast<std::uint32_t>(tile.sourceHeight);
                const bool localTile =
                    bitmapInfo.height >= static_cast<std::uint32_t>(sourceSpan);
                if (!fullPage && !localTile) return false;
                void* pixels = nullptr;
                if (AndroidBitmap_lockPixels(env, tile.bitmap, &pixels) !=
                        ANDROID_BITMAP_RESULT_SUCCESS ||
                    pixels == nullptr ||
                    bitmapInfo.stride <
                        static_cast<std::uint32_t>(tile.sourceWidth) * 4U) {
                    return false;
                }
                *view = {
                    .pixels = static_cast<const std::uint8_t*>(pixels),
                    .strideBytes = bitmapInfo.stride,
                    .pixelWidth = tile.sourceWidth,
                    .pageRowOrigin = fullPage ? 0 : tile.sourceTop,
                    .rowCount = static_cast<int>(bitmapInfo.height),
                    .logicalRowCount = fullPage ? tile.sourceHeight : sourceSpan,
                    .softwareBitmap = tile.bitmap,
                };
                return true;
            }
        }

        const auto& symbols = cpuReadHardwareBufferSymbols();
        if (!symbols.valid()) return false;
        if (!tile.hardwareBufferResource) {
            if (symbols.get(env, tile.bitmap, &hardwareBuffer) != 0 ||
                hardwareBuffer == nullptr) {
                return false;
            }
            hardwareBufferAcquired = true;
        }
        if (hardwareBuffer == nullptr) {
            if (hardwareBufferAcquired) symbols.release(hardwareBuffer);
            return false;
        }
        AHardwareBuffer_Desc descriptor{};
        symbols.describe(hardwareBuffer, &descriptor);
        const bool fullPage = !tile.hardwareBufferResource &&
            descriptor.height >= static_cast<std::uint32_t>(tile.sourceHeight);
        const bool dimensionsValid = descriptor.layers == 1 &&
            descriptor.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM &&
            descriptor.width >= static_cast<std::uint32_t>(tile.sourceWidth) &&
            descriptor.height >= static_cast<std::uint32_t>(sourceSpan) &&
            descriptor.stride >= static_cast<std::uint32_t>(tile.sourceWidth) &&
            descriptor.height <= static_cast<std::uint32_t>(INT32_MAX);
        void* pixels = nullptr;
        const int lockResult = dimensionsValid
            ? symbols.lock(
                hardwareBuffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
                -1,
                nullptr,
                &pixels)
            : -1;
        if (lockResult != 0 || pixels == nullptr) {
            if (hardwareBufferAcquired) symbols.release(hardwareBuffer);
            return false;
        }
        *view = {
            .pixels = static_cast<const std::uint8_t*>(pixels),
            .strideBytes = static_cast<std::size_t>(descriptor.stride) * 4U,
            .pixelWidth = tile.sourceWidth,
            .pageRowOrigin = fullPage ? 0 : tile.sourceTop,
            .rowCount = static_cast<int>(descriptor.height),
            .logicalRowCount = fullPage ? tile.sourceHeight : sourceSpan,
            .hardwareBuffer = hardwareBuffer,
            .hardwareBufferAcquired = hardwareBufferAcquired,
        };
        return true;
    }

    bool unlockCpuTileRead(
            JNIEnv* env,
            CpuTileReadView& view) const noexcept {
        bool valid = true;
        if (view.softwareBitmap != nullptr) {
            valid = env != nullptr &&
                AndroidBitmap_unlockPixels(env, view.softwareBitmap) ==
                    ANDROID_BITMAP_RESULT_SUCCESS;
        }
        if (view.hardwareBuffer != nullptr) {
            const auto& symbols = cpuReadHardwareBufferSymbols();
            valid = symbols.valid() &&
                symbols.unlock(view.hardwareBuffer, nullptr) == 0 && valid;
            if (view.hardwareBufferAcquired && symbols.release != nullptr) {
                symbols.release(view.hardwareBuffer);
            }
        }
        view = {};
        return valid;
    }

    bool prepareCpuTileReadViews(
            JNIEnv* env,
            const FrameCommand& frame,
            std::vector<CpuTileReadView>* views) const noexcept {
        if (views != nullptr) views->clear();
        if (env == nullptr || views == nullptr ||
            !hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) ||
            frame.width <= 0 || frame.height <= 0 || frame.tileView().empty()) {
            return false;
        }
        views->reserve(frame.tileView().size());
        for (const auto& tile : frame.tileView()) {
            if (!std::isfinite(tile.pageTop) || !std::isfinite(tile.pageHeight) ||
                tile.pageHeight <= 0.0F) {
                for (auto& owned : *views) (void)unlockCpuTileRead(env, owned);
                views->clear();
                return false;
            }
            CpuTileReadView view{};
            if (!lockCpuTileForRead(env, tile, &view)) {
                for (auto& owned : *views) (void)unlockCpuTileRead(env, owned);
                views->clear();
                return false;
            }
            views->push_back(view);
        }
        return views->size() == frame.tileView().size();
    }

    bool releaseCpuTileReadViews(
            JNIEnv* env,
            std::vector<CpuTileReadView>& views) const noexcept {
        bool valid = true;
        for (auto& view : views) {
            valid = unlockCpuTileRead(env, view) && valid;
        }
        views.clear();
        return valid;
    }

    bool composeCpuFrameRegion(
            const FrameCommand& frame,
            const std::vector<CpuTileReadView>& sourceViews,
            void* destinationPixels,
            std::uint32_t destinationStridePixels,
            int destinationWidth,
            int destinationHeight,
            int sourceRegionTop,
            int sourceRegionHeight) const noexcept {
        if (frame.width <= 0 || frame.height <= 0 || destinationWidth <= 0 ||
            destinationHeight <= 0 || sourceRegionTop < 0 || sourceRegionHeight <= 0 ||
            sourceRegionTop > frame.height - sourceRegionHeight ||
            frame.tileView().empty() ||
            sourceViews.size() != frame.tileView().size() ||
            destinationPixels == nullptr ||
            destinationStridePixels < static_cast<std::uint32_t>(destinationWidth) ||
            static_cast<std::uint64_t>(destinationStridePixels) * 4ULL > SIZE_MAX) {
            return false;
        }
        const std::size_t destinationStrideBytes =
            static_cast<std::size_t>(destinationStridePixels) * 4U;
        const std::size_t visibleRowBytes = static_cast<std::size_t>(destinationWidth) * 4U;
        auto* destination = static_cast<std::uint8_t*>(destinationPixels);
        thread_local std::vector<std::uint8_t> coveredRows;
        coveredRows.assign(static_cast<std::size_t>(destinationHeight), 0U);
        // A band normally scales millions of pixels from one or two repeated source widths.
        // Computing a 64-bit fixed-point step, shift and clamp for every destination pixel made
        // the low-priority precompose worker miss its forward overlap on wide chapters. Cache the
        // collision-free nearest-neighbour source column once per width and keep the row loop to
        // one indexed load/store. thread_local storage also removes per-band heap churn.
        thread_local std::vector<std::uint32_t> sourceColumns;
        if (sourceColumns.size() < static_cast<std::size_t>(destinationWidth)) {
            sourceColumns.resize(static_cast<std::size_t>(destinationWidth));
        }
        int mappedSourceWidth = -1;

        bool drewPixels = false;
        for (std::size_t tileIndex = 0; tileIndex < frame.tileView().size(); ++tileIndex) {
            const auto& tile = frame.tileView()[tileIndex];
            const auto& sourceView = sourceViews[tileIndex];
            if (sourceView.pixelWidth <= 0 || sourceView.rowCount <= 0 ||
                sourceView.logicalRowCount <= 0) {
                return false;
            }
            const double pageTop = static_cast<double>(tile.pageTop);
            const double pageHeight = static_cast<double>(tile.pageHeight);
            const double tileTop = pageTop + pageHeight *
                static_cast<double>(tile.sourceTop) /
                static_cast<double>(tile.sourceHeight);
            const double tileBottom = pageTop + pageHeight *
                static_cast<double>(tile.sourceBottom) /
                static_cast<double>(tile.sourceHeight);
            const double regionTop = static_cast<double>(sourceRegionTop);
            const double regionBottom = regionTop + static_cast<double>(sourceRegionHeight);
            if (tileBottom <= regionTop || tileTop >= regionBottom ||
                tileBottom <= tileTop) {
                continue;
            }

            const int firstDestinationRow = std::max(
                0, static_cast<int>(std::floor(
                    (tileTop - regionTop) * destinationHeight / sourceRegionHeight)));
            const int destinationRowEnd = std::min(
                destinationHeight, static_cast<int>(std::ceil(
                    (tileBottom - regionTop) * destinationHeight / sourceRegionHeight)));
            const std::uint64_t xStep =
                (static_cast<std::uint64_t>(sourceView.pixelWidth) << 32U) /
                static_cast<std::uint64_t>(destinationWidth);
            if (sourceView.pixelWidth != destinationWidth &&
                mappedSourceWidth != sourceView.pixelWidth) {
                std::uint64_t sourceX = xStep / 2U;
                for (int x = 0; x < destinationWidth; ++x) {
                    sourceColumns[static_cast<std::size_t>(x)] =
                        static_cast<std::uint32_t>(std::min<std::uint64_t>(
                            sourceX >> 32U,
                            static_cast<std::uint64_t>(sourceView.pixelWidth - 1)));
                    sourceX += xStep;
                }
                mappedSourceWidth = sourceView.pixelWidth;
            }
            for (int y = firstDestinationRow; y < destinationRowEnd; ++y) {
                const double sampleY = regionTop +
                    (static_cast<double>(y) + 0.5) * sourceRegionHeight /
                        destinationHeight;
                if (sampleY < tileTop || sampleY >= tileBottom) continue;
                const double sourcePageY =
                    (sampleY - pageTop) *
                    static_cast<double>(tile.sourceHeight) / pageHeight;
                const double logicalLocalRow = sourcePageY -
                    static_cast<double>(sourceView.pageRowOrigin);
                int sourceRow = static_cast<int>(std::floor(
                    logicalLocalRow * static_cast<double>(sourceView.rowCount) /
                    static_cast<double>(sourceView.logicalRowCount)));
                sourceRow = std::clamp(sourceRow, 0, sourceView.rowCount - 1);
                const auto* source = sourceView.pixels +
                    static_cast<std::size_t>(sourceRow) * sourceView.strideBytes;
                auto* target = destination +
                    static_cast<std::size_t>(y) * destinationStrideBytes;
                if (sourceView.pixelWidth == destinationWidth) {
                    std::memcpy(target, source, visibleRowBytes);
                } else {
                    const auto* sourceWords = reinterpret_cast<const std::uint32_t*>(source);
                    auto* targetWords = reinterpret_cast<std::uint32_t*>(target);
                    for (int x = 0; x < destinationWidth; ++x) {
                        targetWords[x] = sourceWords[
                            sourceColumns[static_cast<std::size_t>(x)]];
                    }
                }
                coveredRows[static_cast<std::size_t>(y)] = 1U;
                drewPixels = true;
            }
        }
        // Pages fill complete destination rows. Clear only genuine vertical gaps instead of
        // writing the entire 16-22 MiB target once and immediately overwriting those same rows
        // with page pixels; this halves memory traffic for the normal gapless webtoon band.
        for (int y = 0; y < destinationHeight; ++y) {
            if (coveredRows[static_cast<std::size_t>(y)] != 0U) continue;
            std::memset(
                destination + static_cast<std::size_t>(y) * destinationStrideBytes,
                0,
                visibleRowBytes);
        }
        return drewPixels;
    }

    bool composeCpuFrame(
            const FrameCommand& frame,
            const std::vector<CpuTileReadView>& sourceViews,
            void* destinationPixels,
            std::uint32_t destinationStridePixels) const noexcept {
        return composeCpuFrameRegion(
            frame, sourceViews, destinationPixels, destinationStridePixels,
            frame.width, frame.height, 0, frame.height);
    }

    void cpuComposeLoop() noexcept {
        // This worker prepares pixels that will become the next visible rolling band. Background
        // nice +10 lets decode/network pools postpone AHardwareBuffer unlock beyond the two-
        // viewport overlap. Keep it at ordinary foreground priority while main, input, producer,
        // commit, and the renderer owner retain urgent-display priority; visible preparation can
        // then finish before promotion without competing at the display-critical priority.
        (void)setpriority(PRIO_PROCESS, 0, kCpuPrecomposeNice);
        JNIEnv* env = attachEnv();
        while (env != nullptr) {
            CpuBandPrecomposeJob* job = nullptr;
            {
                std::unique_lock<std::mutex> lock(cpuComposeMutex_);
                cpuComposeCondition_.wait(lock, [&] {
                    return stopped_.load(std::memory_order_acquire) ||
                        (cpuComposeJob_.occupied && !cpuComposeJob_.running &&
                         !cpuComposeJob_.done);
                });
                if (stopped_.load(std::memory_order_acquire) &&
                    !cpuComposeJob_.occupied) break;
                if (!cpuComposeJob_.occupied || cpuComposeJob_.running ||
                    cpuComposeJob_.done) continue;
                cpuComposeJob_.running = true;
                job = &cpuComposeJob_;
            }

            void* destinationPixels = nullptr;
            std::uint32_t destinationStridePixels = 0;
            std::vector<CpuTileReadView> sourceViews;
            const bool targetLocked = job->target != nullptr &&
                backend_.lockCpuPrecompositionOffThread(
                    *job->target, &destinationPixels, &destinationStridePixels);
            job->lockEndNanos = nowNanos();
            const bool sourcesPrepared = targetLocked &&
                prepareCpuTileReadViews(env, job->frame, &sourceViews);
            const bool composed = sourcesPrepared && composeCpuFrame(
                job->frame, sourceViews, destinationPixels,
                destinationStridePixels);
            job->renderEndNanos = nowNanos();
            const bool sourcesReleased = sourcesPrepared
                ? releaseCpuTileReadViews(env, sourceViews)
                : true;
            int completionFenceFd = -1;
            const bool finished = targetLocked && job->target != nullptr &&
                backend_.finishCpuPrecompositionOffThread(
                    *job->target, &completionFenceFd);
            const bool success = targetLocked && sourcesPrepared && composed &&
                sourcesReleased && finished;
            if (!success && completionFenceFd >= 0) {
                // Failure may recycle the target. Wait only on this exceptional path so the next
                // lock cannot alias an unfinished gralloc flush.
                pollfd descriptor{
                    .fd = completionFenceFd,
                    .events = POLLIN,
                    .revents = 0,
                };
                int result = -1;
                do {
                    result = poll(&descriptor, 1, -1);
                } while (result < 0 && errno == EINTR);
                close(completionFenceFd);
                completionFenceFd = -1;
            }
            job->finishEndNanos = nowNanos();
            {
                std::lock_guard<std::mutex> lock(cpuComposeMutex_);
                job->completionFenceFd = completionFenceFd;
                job->success = success;
                job->running = false;
                job->done = true;
                cpuComposeCompletionReady_.store(true, std::memory_order_release);
            }
            cpuComposeCondition_.notify_all();
            condition_.notify_one();
        }
    }

    bool startCpuBandPrecomposition(
            FrameCommand& frame,
            bool presentOnCompletion = false) noexcept {
        if (!hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) ||
            !backend_.cpuComposerOnly() ||
            frame.cpuBandPrecompositionRejected ||
            !supportsExactCpuBandPrecomposition(frame) ||
            !backendAttached_ || submittedFrames_ == 0 || frame.tileView().empty() ||
            entireBandAlreadyApplied(frame) || readyCpuBand_.occupied ||
            cpuComposeInFlight_.load(std::memory_order_acquire) ||
            readyGpuBand_.occupied ||
            gpuFenceInFlight_.load(std::memory_order_acquire) ||
            !backend_.hasDirectSubmissionCapacity() ||
            !backend_.pool().hasFreeRenderTarget()) {
            return false;
        }
        auto* target = backend_.acquireRenderTarget();
        if (target == nullptr || !backend_.beginCpuPrecomposition(*target)) {
            if (target != nullptr) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
            }
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(cpuComposeMutex_);
            if (cpuComposeJob_.occupied ||
                cpuComposeInFlight_.load(std::memory_order_acquire)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                return false;
            }
            cpuComposeJob_ = {};
            cpuComposeJob_.frame = std::move(frame);
            cpuComposeJob_.target = target;
            cpuComposeJob_.beginNanos = nowNanos();
            cpuComposeJob_.occupied = true;
            cpuComposeJob_.presentOnCompletion = presentOnCompletion;
            cpuComposeInFlight_.store(true, std::memory_order_release);
        }
        pipelineQuiescent_.store(false, std::memory_order_release);
        cpuComposeCondition_.notify_one();
        return true;
    }

    bool consumeCpuBandPrecomposition(JNIEnv* env) noexcept {
        if (!cpuComposeCompletionReady_.load(std::memory_order_acquire)) return true;
        CpuBandPrecomposeJob completed{};
        {
            std::lock_guard<std::mutex> lock(cpuComposeMutex_);
            if (!cpuComposeJob_.occupied || !cpuComposeJob_.done) return true;
            completed = std::move(cpuComposeJob_);
            cpuComposeJob_ = {};
            cpuComposeCompletionReady_.store(false, std::memory_order_release);
            cpuComposeInFlight_.store(false, std::memory_order_release);
        }
        const bool published = completed.success && completed.target != nullptr &&
            backend_.publishFinishedCpuPrecomposition(
                *completed.target, completed.completionFenceFd);
        completed.completionFenceFd = -1;
        if (!published) {
            if (completed.target != nullptr) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    completed.target->slot, completed.target->generation);
            }
            // A foreign HardwareBitmap can advertise an exact identity yet reject CPU_READ on
            // a particular driver. Preserve the command and route it once through the existing
            // GPU path; the renderer owner stays free during every CPU-readable success, while a
            // runtime rejection cannot lose the required visible token or retry CPU forever.
            completed.frame.cpuBandPrecompositionRejected = true;
            if (!backend_.cpuComposerOnly() && startGpuBandPrecomposition(
                    env, completed.frame, completed.presentOnCompletion)) {
                return true;
            }
            if (completed.presentOnCompletion) {
                FrameCommand superseded;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (frames_.size() >= kMaxQueuedFrames) {
                        superseded = std::move(frames_.back());
                        frames_.pop_back();
                    }
                    frames_.push_front(std::move(completed.frame));
                }
                if (superseded.token != 0) {
                    callbackDropped(
                        env, superseded.token, kDropReasonMailboxSuperseded);
                    releaseFrame(env, superseded);
                    ++supersededFrames_;
                }
                condition_.notify_one();
                return true;
            }
            releaseFrame(env, completed.frame);
            return true;
        }
        readyCpuBand_ = {};
        readyCpuBand_.composedFrame = std::move(completed.frame);
        readyCpuBand_.target = completed.target;
        readyCpuBand_.beginNanos = completed.beginNanos;
        readyCpuBand_.lockEndNanos = completed.lockEndNanos;
        readyCpuBand_.renderEndNanos = completed.renderEndNanos;
        readyCpuBand_.finishEndNanos = completed.finishEndNanos;
        readyCpuBand_.occupied = true;
        readyCpuBand_.presentOnCompletion = completed.presentOnCompletion;
        const std::uint64_t ordinal = ++completedCpuBandPrecompositions_;
        if (ordinal == 1 || ordinal % 30 == 0) {
            RLOGI(
                "cpu band precomposed ordinal=%llu token=%llu totalUs=%lld lockUs=%lld drawUs=%lld finishUs=%lld",
                static_cast<unsigned long long>(ordinal),
                static_cast<unsigned long long>(
                    readyCpuBand_.composedFrame.token),
                static_cast<long long>(
                    (readyCpuBand_.finishEndNanos - readyCpuBand_.beginNanos) / 1'000),
                static_cast<long long>(
                    (readyCpuBand_.lockEndNanos - readyCpuBand_.beginNanos) / 1'000),
                static_cast<long long>(
                    (readyCpuBand_.renderEndNanos - readyCpuBand_.lockEndNanos) / 1'000),
                static_cast<long long>(
                    (readyCpuBand_.finishEndNanos - readyCpuBand_.renderEndNanos) / 1'000));
        }
        // The next real product frame promotes this exact successor as soon as it covers the
        // current viewport. Buffer import then overlaps the old band's remaining runway instead
        // of becoming a mandatory stall at its final row.
        return true;
    }

    void discardReadyCpuBand(JNIEnv* env) noexcept {
        if (!readyCpuBand_.occupied) return;
        if (readyCpuBand_.target != nullptr) {
            (void)backend_.abortRenderTargetBeforePreparation(
                readyCpuBand_.target->slot,
                readyCpuBand_.target->generation);
        }
        releaseFrame(env, readyCpuBand_.composedFrame);
        readyCpuBand_ = {};
    }

    void retireCpuBandPrecomposition(JNIEnv* env) noexcept {
        if (cpuComposeInFlight_.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lock(cpuComposeMutex_);
            cpuComposeCondition_.wait(lock, [&] {
                return !cpuComposeJob_.occupied || cpuComposeJob_.done;
            });
        }
        (void)consumeCpuBandPrecomposition(env);
        discardReadyCpuBand(env);
    }

    bool ensureGpuCompositionVao() noexcept {
        if (gpuCompositionVao_ != 0) return true;
        glGenVertexArrays(1, &gpuCompositionVao_);
        glBindVertexArray(gpuCompositionVao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(
            1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
            reinterpret_cast<void*>(2 * sizeof(float)));
        glBindVertexArray(0);
        return gpuCompositionVao_ != 0 && glGetError() == GL_NO_ERROR;
    }

    bool bindGpuCompositionTarget(
            ntk::present::HardwareBufferRenderTargetPool::RenderTarget& target,
            std::uint64_t epoch) noexcept {
        if (target.slot >= gpuCompositionFramebuffers_.size() ||
            target.renderbuffer == 0 || epoch == 0) return false;
        const std::size_t index = static_cast<std::size_t>(target.slot);
        GLuint& framebuffer = gpuCompositionFramebuffers_[index];
        if (framebuffer == 0) glGenFramebuffers(1, &framebuffer);
        if (framebuffer == 0) return false;
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        if (gpuCompositionFramebufferEpochs_[index] != epoch ||
            gpuCompositionAttachedRenderbuffers_[index] != target.renderbuffer) {
            glFramebufferRenderbuffer(
                GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER,
                target.renderbuffer);
            gpuCompositionFramebufferEpochs_[index] = epoch;
            gpuCompositionAttachedRenderbuffers_[index] = target.renderbuffer;
        }
        return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE &&
            glGetError() == GL_NO_ERROR;
    }

    bool primeGpuCompositionContext(std::uint64_t epoch) noexcept {
        if (gpuCompositionContext_ == EGL_NO_CONTEXT ||
            gpuCompositionPbuffer_ == EGL_NO_SURFACE) return true;
        const std::int64_t begin = nowNanos();
        if (eglMakeCurrent(
                display_, gpuCompositionPbuffer_, gpuCompositionPbuffer_,
                gpuCompositionContext_) != EGL_TRUE) return false;
        (void)drainGlErrors();
        bool ready = ensureGpuCompositionVao();
        const std::size_t targets = backend_.pool().allocatedTargetCount();
        for (std::size_t index = 0; ready && index < targets; ++index) {
            auto* target = backend_.pool().allocatedTargetAt(index);
            ready = target != nullptr && bindGpuCompositionTarget(*target, epoch);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindVertexArray(0);
        glFlush();
        const GLenum error = drainGlErrors();
        const bool unbound = eglMakeCurrent(
            display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
        const bool restored = pbuffer_ != EGL_NO_SURFACE &&
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) == EGL_TRUE;
        const std::int64_t end = nowNanos();
        RLOGI(
            "shared GPU band context primed epoch=%llu targets=%zu durationMs=%.3f ready=%d",
            static_cast<unsigned long long>(epoch), targets,
            static_cast<double>(end - begin) / 1'000'000.0,
            ready && error == GL_NO_ERROR && unbound && restored ? 1 : 0);
        return ready && error == GL_NO_ERROR && unbound && restored;
    }

    bool composeGpuBandOnSharedContext(
            JNIEnv* env,
            GpuBandFenceJob& job) noexcept {
        if (env == nullptr || job.target == nullptr ||
            gpuCompositionContext_ == EGL_NO_CONTEXT ||
            gpuCompositionPbuffer_ == EGL_NO_SURFACE ||
            eglMakeCurrent(
                display_, gpuCompositionPbuffer_, gpuCompositionPbuffer_,
                gpuCompositionContext_) != EGL_TRUE) {
            job.failureStage = "worker-context";
            return false;
        }

        (void)drainGlErrors();
        const bool targetBound = ensureGpuCompositionVao() &&
            bindGpuCompositionTarget(*job.target, surfaceEpoch_);
        glViewport(0, 0, job.composedFrame.width, job.composedFrame.height);
        job.bindEndNanos = nowNanos();
        if (!targetBound) job.failureStage = "worker-target-bind";

        bool prepared = targetBound;
        const bool directWifiFreshNames = usesFreshTextureNames();
        if (prepared && !prepareVisibleFrameTextureHeadroom(
                job.composedFrame, directWifiFreshNames)) {
            prepared = false;
            job.failureStage = "worker-texture-headroom";
        }
        const std::uint64_t textureUseFrame = prepared ? ++textureUseSerial_ : 0;
        job.uploadBeginNanos = nowNanos();
        if (prepared) {
            for (const auto& tile : job.composedFrame.tileView()) {
                if (!uploadTile(env, tile, textureUseFrame, directWifiFreshNames)) {
                    prepared = false;
                    job.failureStage = "worker-texture-upload";
                    break;
                }
            }
        }
        job.uploadEndNanos = nowNanos();
        job.renderBeginNanos = nowNanos();
        if (prepared && !drawFrame(
                job.composedFrame, false, gpuCompositionVao_)) {
            prepared = false;
            job.failureStage = "worker-draw";
        }
        job.renderEndNanos = nowNanos();
        if (prepared && !backend_.beginGpuFenceExport(
                *job.target, job.renderBeginNanos, job.renderEndNanos,
                &job.pending)) {
            prepared = false;
            job.failureStage = "worker-acquire-fence";
        }
        job.gpuSubmissionIssued = prepared;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindVertexArray(0);
        (void)eglMakeCurrent(
            display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return prepared;
    }

    void gpuFenceLoop() noexcept {
        (void)pthread_setname_np(pthread_self(), "ReaderGpuBand");
        // This worker owns visible-deadline successor preparation, while the renderer owner keeps
        // publishing crop-only frames from the old overlap. Give both lanes display priority so
        // large background network/decode pools cannot consume the overlap before composition is
        // scheduled; neither lane performs unbounded CPU work.
        (void)setpriority(PRIO_PROCESS, 0, kRollingConsumerNice);
        JNIEnv* env = attachEnv();
        while (env != nullptr) {
            GpuBandFenceJob* job = nullptr;
            {
                std::unique_lock<std::mutex> lock(gpuFenceMutex_);
                gpuFenceCondition_.wait(lock, [&] {
                    return stopped_.load(std::memory_order_acquire) ||
                        (gpuFenceJob_.occupied && !gpuFenceJob_.running &&
                         !gpuFenceJob_.done);
                });
                if (stopped_.load(std::memory_order_acquire) &&
                    !gpuFenceJob_.occupied) break;
                if (!gpuFenceJob_.occupied || gpuFenceJob_.running ||
                    gpuFenceJob_.done) continue;
                gpuFenceJob_.running = true;
                job = &gpuFenceJob_;
            }
            const bool issued = job->gpuSubmissionIssued ||
                composeGpuBandOnSharedContext(env, *job);
            if (issued) {
                backend_.finishGpuFenceExportOffThread(
                    &job->pending, &job->finished);
            }
            {
                std::lock_guard<std::mutex> lock(gpuFenceMutex_);
                job->success = issued && job->finished.success;
                if (issued && !job->success && job->failureStage == nullptr) {
                    job->failureStage = "worker-fence-export";
                }
                job->running = false;
                job->done = true;
                gpuFenceCompletionReady_.store(true, std::memory_order_release);
            }
            gpuFenceCondition_.notify_all();
            condition_.notify_one();
        }
        if (env != nullptr) vm_->DetachCurrentThread();
    }

    bool startGpuBandPrecomposition(
            JNIEnv* env,
            FrameCommand& frame,
            bool presentOnCompletion = false) noexcept {
        if (env == nullptr ||
            !hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) ||
            backend_.cpuComposerOnly() || !backendAttached_ || submittedFrames_ == 0 ||
            frame.tileView().empty() || entireBandAlreadyApplied(frame) ||
            readyGpuBand_.occupied ||
            gpuFenceInFlight_.load(std::memory_order_acquire) ||
            !backend_.pool().hasFreeRenderTarget()) {
            return false;
        }

        GpuBandFenceJob candidate{};
        candidate.beginNanos = nowNanos();
        auto* target = backend_.acquireRenderTarget();
        if (target == nullptr) return false;
        const bool sharedWorkerAvailable =
            gpuCompositionContext_ != EGL_NO_CONTEXT &&
            gpuCompositionPbuffer_ != EGL_NO_SURFACE;
        if (!sharedWorkerAvailable) {
            // Compatibility fallback for drivers that reject a shared ES3 pbuffer context. It
            // preserves the old exact path; supported devices never put this GL work on the crop
            // owner.
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (!backend_.bindRenderTarget(*target)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                return false;
            }
            candidate.bindEndNanos = nowNanos();
            const bool directWifiFreshNames = usesFreshTextureNames();
            if (!prepareVisibleFrameTextureHeadroom(frame, directWifiFreshNames)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                return false;
            }
            const std::uint64_t textureUseFrame = ++textureUseSerial_;
            candidate.uploadBeginNanos = nowNanos();
            for (const auto& tile : frame.tileView()) {
                if (!uploadTile(env, tile, textureUseFrame, directWifiFreshNames)) {
                    (void)backend_.abortRenderTargetBeforePreparation(
                        target->slot, target->generation);
                    return false;
                }
            }
            candidate.uploadEndNanos = nowNanos();
            candidate.renderBeginNanos = nowNanos();
            if (!drawFrame(frame)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                return false;
            }
            candidate.renderEndNanos = nowNanos();
            if (!backend_.beginGpuFenceExport(
                    *target, candidate.renderBeginNanos, candidate.renderEndNanos,
                    &candidate.pending)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                return false;
            }
            candidate.gpuSubmissionIssued = true;
        }
        candidate.target = target;
        candidate.composedFrame = std::move(frame);
        candidate.occupied = true;
        candidate.presentOnCompletion = presentOnCompletion;
        {
            std::lock_guard<std::mutex> lock(gpuFenceMutex_);
            if (gpuFenceJob_.occupied ||
                gpuFenceInFlight_.load(std::memory_order_acquire)) {
                if (candidate.gpuSubmissionIssued) {
                    // The renderer is the only starter, so this is an invariant failure. Finish
                    // an already-issued sync before returning its target to the pool.
                    ntk::present::SurfaceControlPresentBackend::FinishedGpuFenceExport finished{};
                    backend_.finishGpuFenceExportOffThread(&candidate.pending, &finished);
                    ntk::present::GpuSubmissionProof unusedProof{};
                    (void)backend_.publishFinishedGpuFenceExport(
                        *target, &finished, &unusedProof);
                }
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                frame = std::move(candidate.composedFrame);
                return false;
            }
            gpuFenceJob_ = std::move(candidate);
            gpuFenceInFlight_.store(true, std::memory_order_release);
        }
        pipelineQuiescent_.store(false, std::memory_order_release);
        gpuFenceCondition_.notify_one();
        return true;
    }

    bool consumeGpuBandFenceExport(JNIEnv* env) noexcept {
        if (!gpuFenceCompletionReady_.load(std::memory_order_acquire)) return true;
        GpuBandFenceJob completed{};
        {
            std::lock_guard<std::mutex> lock(gpuFenceMutex_);
            if (!gpuFenceJob_.occupied || !gpuFenceJob_.done) return true;
            completed = std::move(gpuFenceJob_);
            gpuFenceJob_ = {};
            gpuFenceCompletionReady_.store(false, std::memory_order_release);
            gpuFenceInFlight_.store(false, std::memory_order_release);
        }
        ntk::present::GpuSubmissionProof proof{};
        const bool published = completed.success && completed.target != nullptr &&
            backend_.publishFinishedGpuFenceExport(
                *completed.target, &completed.finished, &proof);
        if (!published) {
            RLOGE(
                "GPU band worker failed stage=%s token=%llu",
                completed.failureStage != nullptr ? completed.failureStage : "publish",
                static_cast<unsigned long long>(completed.composedFrame.token));
            if (completed.target != nullptr) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    completed.target->slot, completed.target->generation);
            }
            releaseFrame(env, completed.composedFrame);
            return false;
        }
        readyGpuBand_ = {};
        readyGpuBand_.composedFrame = std::move(completed.composedFrame);
        readyGpuBand_.target = completed.target;
        readyGpuBand_.beginNanos = completed.beginNanos;
        readyGpuBand_.bindEndNanos = completed.bindEndNanos;
        readyGpuBand_.uploadBeginNanos = completed.uploadBeginNanos;
        readyGpuBand_.uploadEndNanos = completed.uploadEndNanos;
        readyGpuBand_.renderBeginNanos = completed.renderBeginNanos;
        readyGpuBand_.renderEndNanos = completed.renderEndNanos;
        readyGpuBand_.fenceBeginNanos = proof.acquireFenceIssuedNanos;
        readyGpuBand_.fenceEndNanos = proof.acquireFenceExportReturnNanos;
        readyGpuBand_.occupied = true;
        readyGpuBand_.presentOnCompletion = completed.presentOnCompletion;
        const std::uint64_t ordinal = ++completedGpuBandFenceExports_;
        const std::int64_t totalUs =
            (readyGpuBand_.fenceEndNanos - readyGpuBand_.beginNanos) / 1'000;
        if (ordinal == 1 || ordinal % 30 == 0 || totalUs >= 100'000) {
            RLOGI(
                "gpu band ready ordinal=%llu token=%llu slot=%llu targets=%zu required=%d totalUs=%lld bindUs=%lld uploadUs=%lld drawUs=%lld exportUs=%lld",
                static_cast<unsigned long long>(ordinal),
                static_cast<unsigned long long>(readyGpuBand_.composedFrame.token),
                static_cast<unsigned long long>(readyGpuBand_.target->slot),
                backend_.pool().allocatedTargetCount(),
                readyGpuBand_.presentOnCompletion ? 1 : 0,
                static_cast<long long>(totalUs),
                static_cast<long long>(
                    (readyGpuBand_.bindEndNanos - readyGpuBand_.beginNanos) / 1'000),
                static_cast<long long>(
                    (readyGpuBand_.uploadEndNanos - readyGpuBand_.uploadBeginNanos) / 1'000),
                static_cast<long long>(
                    (readyGpuBand_.renderEndNanos - readyGpuBand_.renderBeginNanos) / 1'000),
                static_cast<long long>(
                    (readyGpuBand_.fenceEndNanos - readyGpuBand_.fenceBeginNanos) / 1'000));
        }
        return true;
    }

    void discardReadyGpuBand(JNIEnv* env) noexcept {
        if (!readyGpuBand_.occupied) return;
        if (readyGpuBand_.target != nullptr) {
            (void)backend_.abortRenderTargetBeforePreparation(
                readyGpuBand_.target->slot, readyGpuBand_.target->generation);
        }
        releaseFrame(env, readyGpuBand_.composedFrame);
        readyGpuBand_ = {};
    }

    void retireGpuBandPrecomposition(JNIEnv* env) noexcept {
        if (gpuFenceInFlight_.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lock(gpuFenceMutex_);
            gpuFenceCondition_.wait(lock, [&] {
                return !gpuFenceJob_.occupied || gpuFenceJob_.done;
            });
        }
        (void)consumeGpuBandFenceExport(env);
        discardReadyGpuBand(env);
    }

    bool drawFrame(
            const FrameCommand& frame,
            bool windowCoordinates = false,
            GLuint contextVao = 0) noexcept {
        glViewport(0, 0, frame.width, frame.height);
        glDisable(GL_BLEND);
        // ReaderSurfaceView owns an RGBA SurfaceView above the HWUI fallback. Keep pixels outside
        // the exact native texture coverage transparent so a device-specific delayed or rejected
        // BufferQueue texture never turns the otherwise valid fallback frame permanently black.
        // Decoded page textures are opaque and still replace the fallback wherever they draw.
        glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program_);
        glBindVertexArray(contextVao != 0 ? contextVao : vao_);
        glActiveTexture(GL_TEXTURE0);
        int draws = 0;
        for (const auto& tile : frame.tileView()) {
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
            glUniform2f(
                textureScaleUniform_,
                texture->second.textureScaleX,
                texture->second.textureScaleY);
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
                    const bool geometryOnly =
                        backend_.isGeometryOnlyTransaction(event);
                    ntk::present::SurfaceControlPresentBackend::ExactPresentLatchObservation observation{};
                    valid = backend_.consumeCompositorLatch(event, &observation);
                    if (valid) {
                        // Geometry has its own bounded callback ledger and may have several
                        // display-timeline transactions in flight. Only a real buffer replacement
                        // releases the single direct-buffer latch gate.
                        if (!geometryOnly) submissionAwaitingLatch_ = false;
                        // A geometry pulse owns a real transparent buffer. OnCommit proves the
                        // transaction was accepted, but only OnComplete below carries its present
                        // fence; do not publish executor/callback arrival time as display cadence.
                    }
                    break;
                }
                case ntk::present::FixedPresentEventKind::TRANSACTION_COMPLETED: {
                    const bool geometryOnly =
                        backend_.isGeometryOnlyTransaction(event);
                    valid = backend_.consumeTransactionCompleted(event);
                    // Both image-buffer replacement and the transparent geometry pulse publish
                    // only from their real OnComplete/present-fence evidence.
                    if (valid && !geometryOnly) {
                        // Publish the collision-free producer scene only after the buffer's real
                        // present proof. Java may then move this immutable band independently
                        // while the EGL owner composes a successor.
                        callbackBandActivated(env, event);
                    }
                    if (valid) callbackLatched(env, event);
                    break;
                }
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
        const bool hostGpuDirectSurface =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
        if (hostGpuDirectSurface) {
            if (backend_.prepared() && !backend_.destroy()) {
                return false;
            }
            preparedWidth_ = command.width;
            preparedHeight_ = command.height;
            RLOGI(
                "cold host display-paced BufferQueue geometry staged size=%dx%d",
                command.width, command.height);
            return true;
        }
        if (backend_.prepared() && !backend_.destroy()) return false;
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
            if (!consumeDirectTileEvents(env)) return false;
            if (!consumeEvents(env) && failed_.load(std::memory_order_acquire)) return false;
            const auto snapshot = backend_.conservationSnapshot();
            if (snapshot.outstandingSubmissionCount == 0 &&
                snapshot.previousReleaseRecordDepth == 0 &&
                snapshot.acquireFenceRecordDepth == 0 &&
                snapshot.appOwnedAcquireFdCount == 0 && !backend_.hasPendingEvent() &&
                directTilePresenter_.idle()) {
                return true;
            }
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait_for(lock, std::chrono::milliseconds(4));
        }
    }

    bool detachDirectTilePresenterAfterEvidenceDrained(JNIEnv* env) noexcept {
        // Transaction callbacks run on Binder threads. One can enqueue its final completion in
        // the tiny gap between drainBackendEvidence() observing idle and detach() rechecking it.
        // No producer frame can enter while the renderer owns a lifecycle command, so simply
        // drain that late evidence and retry until the presenter atomically accepts detach.
        while (directTilePresenter_.attached()) {
            if (!drainBackendEvidence(env)) return false;
            if (directTilePresenter_.detach()) return true;
        }
        return true;
    }

    void resetBackendAttachmentState() noexcept {
        backendAttached_ = false;
        hostCpuWindowAttached_ = false;
        hostCpuWindowBufferWidth_ = 0;
        hostCpuWindowBufferHeight_ = 0;
        javaFrameSyncedGeometry_ = false;
        hostFrontBufferMode_ = false;
        hostFrontBufferPrimed_ = false;
        hostFrontTailReleaseNotBeforeNanos_ = 0;
        submissionAwaitingLatch_ = false;
        lastGeometryDesiredPresentNanos_ = 0;
        lastAppliedFrameWidth_ = 0;
        lastAppliedFrameHeight_ = 0;
        lastAppliedFrameEpoch_ = 0;
        lastAppliedProducerSceneId_ = 0;
        lastAppliedGeometryBaseSourceTop_ = 0;
        lastAppliedViewportSourceHeight_ = 0;
        lastJavaGeometrySourceTop_ = 0;
        lastAppliedFrameTiles_.clear();
        directTilePresentationActivated_ = false;
        surfaceEpoch_ = 0;
        preparedWidth_ = 0;
        preparedHeight_ = 0;
    }

    bool armHostFrontBufferProof() noexcept {
        if (hostFrontProof_ != nullptr || hostFrontSubmissions_.empty() ||
            display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            eglGetCurrentContext() != context_) {
            return false;
        }
        (void)drainGlErrors();
        GLsync proof = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (proof != nullptr) glFlush();
        const GLenum error = drainGlErrors();
        if (proof == nullptr || error != GL_NO_ERROR) {
            if (proof != nullptr) glDeleteSync(proof);
            return false;
        }
        hostFrontProof_ = proof;
        hostFrontProofSubmissionCount_ = hostFrontSubmissions_.size();
        hostFrontProofIssuedNanos_ = nowNanos();
        return true;
    }

    bool consumeHostFrontBufferProof(JNIEnv* env, bool waitForAll) noexcept {
        while (hostFrontProof_ != nullptr) {
            const GLenum result = glClientWaitSync(
                hostFrontProof_,
                waitForAll ? GL_SYNC_FLUSH_COMMANDS_BIT : 0,
                waitForAll ? 1'000'000'000ULL : 0ULL);
            if (result == GL_TIMEOUT_EXPIRED) return !waitForAll;
            if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) {
                return false;
            }
            const std::int64_t observedNanos = nowNanos();
            const std::int64_t proofElapsedUs = hostFrontProofIssuedNanos_ > 0
                ? (observedNanos - hostFrontProofIssuedNanos_) / 1000
                : -1;
            glDeleteSync(hostFrontProof_);
            hostFrontProof_ = nullptr;
            hostFrontProofIssuedNanos_ = 0;
            const std::size_t completedCount = hostFrontProofSubmissionCount_;
            hostFrontProofSubmissionCount_ = 0;
            if (completedCount == 0 || completedCount > hostFrontSubmissions_.size()) {
                return false;
            }
            for (std::size_t index = 0; index < completedCount; ++index) {
                const HostFrontSubmission completed = hostFrontSubmissions_.front();
                hostFrontSubmissions_.pop_front();
                callbackWindowFramePresented(
                    env, completed.token, completed.submittedNanos, 3, observedNanos);
            }
            // A shared buffer has no atomic buffer switch. Preserve the GPU-complete exact-tail
            // pixels through two auto-refresh opportunities before allowing any structural
            // successor to write the same allocation. Otherwise a fast append can overwrite the
            // tail between fence signal and SurfaceFlinger's first scan of those pixels.
            hostFrontTailReleaseNotBeforeNanos_ = observedNanos + 2 * std::max<std::int64_t>(
                1'000'000,
                refreshPeriodNanos_ > 0 ? refreshPeriodNanos_ : kDefaultRefreshPeriodNanos);
            const std::uint64_t ordinal = ++completedHostFrontProofs_;
            if (ordinal == 1 || ordinal % 90 == 0 || proofElapsedUs >= 500'000) {
                RLOGI(
                    "shared front batch proven ordinal=%llu frames=%zu elapsedUs=%lld pending=%zu",
                    static_cast<unsigned long long>(ordinal), completedCount,
                    static_cast<long long>(proofElapsedUs), hostFrontSubmissions_.size());
            }
            if (!hostFrontSubmissions_.empty() && !armHostFrontBufferProof()) return false;
        }
        return hostFrontSubmissions_.empty();
    }

    bool submitHostFrontBufferUpdate(
            std::uint64_t token,
            bool requiresGpuCompletionProof) noexcept {
        if (!hostFrontBufferMode_ || !hostFrontBufferPrimed_ || token == 0 ||
            display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            eglGetCurrentContext() != context_) {
            return false;
        }
        if (!requiresGpuCompletionProof) {
            glFlush();
            return true;
        }
        constexpr std::size_t kMaxHostFrontSubmissions = 32;
        if (hostFrontSubmissions_.size() >= kMaxHostFrontSubmissions) return false;
        hostFrontSubmissions_.push_back(HostFrontSubmission{
            .token = token,
            .submittedNanos = nowNanos(),
        });
        if (hostFrontProof_ == nullptr) return armHostFrontBufferProof();
        // A repeated exact-tail frame can arrive while its predecessor proof is in flight. The
        // existing proof covers only its captured prefix; flush this successor and fence the
        // remainder after that prefix signals.
        glFlush();
        return true;
    }

    bool finishHostFrontBufferUpdates(JNIEnv* env) noexcept {
        if (!hostFrontBufferMode_ || hostFrontSubmissions_.empty()) return true;
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
            eglGetCurrentContext() != context_) {
            return false;
        }
        if (hostFrontProof_ == nullptr && !armHostFrontBufferProof()) return false;
        return consumeHostFrontBufferProof(env, true);
    }

    bool retireBackendForParentReplacement(JNIEnv* env) noexcept {
        if (!backendAttached_) return true;
        if (hostCpuWindowAttached_) {
            if (nativeWindow_ != nullptr) {
                ANativeWindow_release(nativeWindow_);
                nativeWindow_ = nullptr;
            }
            resetBackendAttachmentState();
            RLOGI("host CPU BufferQueue retired after parent replacement");
            return true;
        }
        if (windowSurface_ != EGL_NO_SURFACE) {
            if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
                !finishHostFrontBufferUpdates(env) || pbuffer_ == EGL_NO_SURFACE ||
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
        // A new SurfaceView attach means the old parent may already have left SurfaceFlinger.
        // Reparenting its child to null can then produce no completion callback. Once all exact
        // transaction/fence evidence is drained, retire the app-owned chain head locally and let
        // destroy() release the obsolete child and parent handles.
        if (!detachDirectTilePresenterAfterEvidenceDrained(env)) return false;
        if (backend_.prepared() &&
            (!backend_.retireAfterParentLifecycleEvidenceDrained() || !backend_.destroy())) {
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

    bool attachHostGpuDirectBackend(JNIEnv* env, AttachCommand command) noexcept {
        const std::int64_t begin = nowNanos();
        const std::int64_t refreshPeriod = command.refreshPeriodNanos > 0
            ? command.refreshPeriodNanos
            : kDefaultRefreshPeriodNanos;
        if (refreshPeriod <= 2) {
            ANativeWindow_release(command.window);
            releaseProvidedAttachSurfaces(command);
            return false;
        }
        const std::int64_t appVsyncOffset = std::min<std::int64_t>(
            2'000'000, std::max<std::int64_t>(0, refreshPeriod / 4));
        const std::int64_t presentationDeadline = std::max<std::int64_t>(
            1, refreshPeriod / 2);
        profile_ = ntk::present::makeFixedTransportProfile(
            refreshPeriod, appVsyncOffset, presentationDeadline, command.epoch);
        const bool profileValid = ntk::present::validFixedTransportProfile(profile_);
        const int destinationWidth = ANativeWindow_getWidth(command.window);
        const int destinationHeight = ANativeWindow_getHeight(command.window);
        bool directTilesAttached = false;
        if (profileValid && destinationWidth > 0 && destinationHeight > 0) {
            directTilesAttached = directTilePresenter_.attach(
                command.window,
                static_cast<std::uint32_t>(destinationWidth),
                static_cast<std::uint32_t>(destinationHeight),
                1'000'000'000.0F / static_cast<float>(refreshPeriod),
                &RollingRenderer::wake,
                this);
        }
        bool attached = directTilesAttached;
        if (directTilesAttached) {
            // The per-tile presenter is the complete host-emulator transport. Do not allocate the
            // old four 800x6932 rolling-band targets as an always-live fallback: together with
            // exact page buffers they cross ART's native-allocation pressure line during input.
            releaseProvidedAttachSurfaces(command);
        } else if (profileValid && backend_.prepare(
                       display_, static_cast<std::uint32_t>(command.width),
                       static_cast<std::uint32_t>(command.height), false)) {
            attached = backend_.attach(
                display_, command.window, command.providedChildSurface,
                command.providedGeometrySurface,
                static_cast<std::uint32_t>(command.width),
                static_cast<std::uint32_t>(command.height), command.epoch,
                &RollingRenderer::wake, this);
        } else {
            releaseProvidedAttachSurfaces(command);
        }
        // ANativeWindow_fromSurface() transferred this reference to the command. The direct
        // backend acquires its own parent reference on success, so the command reference always
        // retires here and never aliases backend lifetime.
        ANativeWindow_release(command.window);
        if (!attached) {
            (void)backend_.destroy();
            return false;
        }
        if (!directTilesAttached) {
            RLOGI("direct exact-tile SurfaceControl presenter unavailable; retaining band fallback");
        }
        backendAttached_ = true;
        if (backend_.prepared()) {
            backend_.configureGeometryPulseFrameRate(
                1'000'000'000.0F / static_cast<float>(refreshPeriod));
        }
        // Geometry must remain on the native pulse-buffer path. A position-only Java transaction
        // is legally merged by host SurfaceFlinger for 250-500 ms and therefore does not prove an
        // individual display cut. The native path couples the same crop to an alternating,
        // transparent 1x1 AHardwareBuffer and obtains real OnCommit/OnComplete fence evidence.
        javaFrameSyncedGeometry_ = false;
        if (attachWasSuperseded()) {
            if (!retireBackendForParentReplacement(env)) return false;
            RLOGI("host SurfaceControl attach retired after concurrent lifecycle replacement");
            return true;
        }
        submissionAwaitingLatch_ = false;
        lastAppliedFrameWidth_ = 0;
        lastAppliedFrameHeight_ = 0;
        lastAppliedFrameEpoch_ = 0;
        lastAppliedProducerSceneId_ = 0;
        lastAppliedGeometryBaseSourceTop_ = 0;
        lastAppliedViewportSourceHeight_ = 0;
        lastJavaGeometrySourceTop_ = 0;
        lastAppliedFrameTiles_.clear();
        frameSequence_ = 0;
        submittedFrames_ = 0;
        lastWindowPresentEndNanos_ = 0;
        lastWindowPresentToken_ = 0;
        consecutivePresentFailures_ = 0;
        directTilePresentationActivated_ = false;
        surfaceEpoch_ = command.epoch;
        width_ = command.width;
        height_ = command.height;
        refreshPeriodNanos_ = refreshPeriod;
        if (!directTilesAttached && !primeGpuCompositionContext(surfaceEpoch_)) {
            (void)retireBackendForParentReplacement(env);
            return false;
        }
        const std::int64_t end = nowNanos();
        RLOGI(
            "cold host SurfaceControl attached epoch=%llu size=%dx%d refreshNs=%lld "
            "profile=%llu mode=%s targets=%zu durationMs=%.3f",
            static_cast<unsigned long long>(surfaceEpoch_), width_, height_,
            static_cast<long long>(refreshPeriodNanos_),
            static_cast<unsigned long long>(profile_.digest),
            directTilesAttached ? "direct-tiles" : "rolling-band-fallback",
            backend_.pool().allocatedTargetCount(),
            static_cast<double>(end - begin) / 1'000'000.0);
        return true;
    }

    bool attachHostCpuWindowBackend(JNIEnv* env, AttachCommand command) noexcept {
        const std::int64_t begin = nowNanos();
        releaseProvidedAttachSurfaces(command);
        if (backend_.prepared() && !backend_.destroy()) {
            ANativeWindow_release(command.window);
            return false;
        }
        const int parentWidth = ANativeWindow_getWidth(command.window);
        const int parentHeight = ANativeWindow_getHeight(command.window);
        if (parentWidth <= 0 || parentHeight <= 0 || command.width <= 0) {
            ANativeWindow_release(command.window);
            return false;
        }
        const int viewportBufferWidth = command.width;
        const std::int64_t scaledViewportHeight =
            static_cast<std::int64_t>(viewportBufferWidth) * parentHeight;
        const int viewportBufferHeight = static_cast<int>(std::max<std::int64_t>(
            1,
            (scaledViewportHeight + parentWidth / 2) / parentWidth));
        if (ANativeWindow_setBuffersGeometry(
                command.window,
                viewportBufferWidth,
                viewportBufferHeight,
                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) != 0) {
            ANativeWindow_release(command.window);
            return false;
        }
        const auto& bufferControls = nativeWindowBufferControls();
        const int swapIntervalResult = setNativeWindowSwapInterval(command.window, 0);
        const int sharedBufferOffResult = bufferControls.setSharedBufferMode != nullptr
            ? bufferControls.setSharedBufferMode(command.window, false)
            : -3;
        const int autoRefreshOffResult = bufferControls.setAutoRefresh != nullptr
            ? bufferControls.setAutoRefresh(command.window, false)
            : -3;
        const int bufferCountResult = bufferControls.setBufferCount != nullptr
            ? bufferControls.setBufferCount(command.window, 4)
            : -3;
        if (bufferCountResult == 0 && bufferControls.tryAllocateBuffers != nullptr) {
            bufferControls.tryAllocateBuffers(command.window);
        }
        nativeWindow_ = command.window;
        backendAttached_ = true;
        hostCpuWindowAttached_ = true;
        hostCpuWindowBufferWidth_ = viewportBufferWidth;
        hostCpuWindowBufferHeight_ = viewportBufferHeight;
        if (attachWasSuperseded()) {
            if (!retireBackendForParentReplacement(env)) return false;
            RLOGI("host CPU BufferQueue attach retired after lifecycle replacement");
            return true;
        }
        submissionAwaitingLatch_ = false;
        lastAppliedFrameWidth_ = 0;
        lastAppliedFrameHeight_ = 0;
        lastAppliedFrameEpoch_ = 0;
        lastAppliedProducerSceneId_ = 0;
        lastAppliedGeometryBaseSourceTop_ = 0;
        lastAppliedViewportSourceHeight_ = 0;
        lastJavaGeometrySourceTop_ = 0;
        lastAppliedFrameTiles_.clear();
        frameSequence_ = 0;
        submittedFrames_ = 0;
        lastWindowPresentEndNanos_ = 0;
        lastWindowPresentToken_ = 0;
        consecutivePresentFailures_ = 0;
        surfaceEpoch_ = command.epoch;
        width_ = command.width;
        height_ = command.height;
        refreshPeriodNanos_ = command.refreshPeriodNanos > 0
            ? command.refreshPeriodNanos
            : kDefaultRefreshPeriodNanos;
        const std::int64_t end = nowNanos();
        RLOGI(
            "cold host CPU BufferQueue attached epoch=%llu logical=%dx%d buffer=%dx%d refreshNs=%lld "
            "swap=%d sharedOff=%d autoOff=%d buffers=%d durationMs=%.3f",
            static_cast<unsigned long long>(surfaceEpoch_), width_, height_,
            hostCpuWindowBufferWidth_, hostCpuWindowBufferHeight_,
            static_cast<long long>(refreshPeriodNanos_), swapIntervalResult,
            sharedBufferOffResult, autoRefreshOffResult, bufferCountResult,
            static_cast<double>(end - begin) / 1'000'000.0);
        return true;
    }

    bool attachBackend(JNIEnv* env, AttachCommand command) noexcept {
        if (command.window == nullptr) return false;
        if (backendAttached_ && !retireBackendForParentReplacement(env)) {
            ANativeWindow_release(command.window);
            releaseProvidedAttachSurfaces(command);
            return false;
        }
        if (attachWasSuperseded()) {
            ANativeWindow_release(command.window);
            releaseProvidedAttachSurfaces(command);
            RLOGI("BufferQueue attach cancelled by newer lifecycle command before allocation");
            return true;
        }
        // Both GLES swaps and CPU lock/post serialize on the host SurfaceView BufferQueue after
        // sustained physical scrolling. Keep viewport motion out of that queue: publish one exact
        // rolling AHardwareBuffer through the app-owned SurfaceControl and advance ordinary input
        // with crop-only transactions. Physical devices retain the asynchronous GLES queue below.
        if (hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire)) {
            return attachHostGpuDirectBackend(env, std::move(command));
        }
        if (eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) != EGL_TRUE) {
            ANativeWindow_release(command.window);
            releaseProvidedAttachSurfaces(command);
            return false;
        }
        releaseProvidedAttachSurfaces(command);
        if (backend_.prepared() && !backend_.destroy()) {
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
        // On gfxstream, an ordinary six-buffer queue still makes eglSwapBuffers block for
        // 276-329 ms after the host compositor falls behind. Keep the host-only shared front
        // allocation: ordinary frames update it in place and exact episode tails retain their
        // separate GPU-completion proof before the allocation may be structurally overwritten.
        const bool requestHostFrontBuffer =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
        const auto& bufferControls = nativeWindowBufferControls();
        const int sharedBufferResult = requestHostFrontBuffer &&
            bufferControls.setSharedBufferMode != nullptr
            ? bufferControls.setSharedBufferMode(command.window, true)
            : -3;
        const int autoRefreshResult = requestHostFrontBuffer &&
            bufferControls.setAutoRefresh != nullptr
            ? bufferControls.setAutoRefresh(command.window, true)
            : -3;
        const bool hostFrontBuffer = requestHostFrontBuffer &&
            sharedBufferResult == 0 && autoRefreshResult == 0;
        const bool mutableFrontSurface =
            hostFrontBuffer && mutableRenderBufferSupported_;
        constexpr EGLint frontSurfaceAttributes[] = {
            EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER,
            EGL_NONE,
        };
        constexpr EGLint backSurfaceAttributes[] = {
            EGL_RENDER_BUFFER, EGL_BACK_BUFFER,
            EGL_NONE,
        };
        EGLSurface window = eglCreateWindowSurface(
            display_, config_, command.window,
            mutableFrontSurface ? frontSurfaceAttributes : backSurfaceAttributes);
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
        const EGLBoolean frontAutoRefreshResult = mutableFrontSurface
            ? eglSurfaceAttrib(
                display_, window, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL_TRUE)
            : EGL_TRUE;
        if (frontAutoRefreshResult != EGL_TRUE) {
            (void)eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            (void)eglDestroySurface(display_, window);
            if (bufferControls.setAutoRefresh != nullptr) {
                (void)bufferControls.setAutoRefresh(command.window, false);
            }
            if (bufferControls.setSharedBufferMode != nullptr) {
                (void)bufferControls.setSharedBufferMode(command.window, false);
            }
            ANativeWindow_release(command.window);
            return false;
        }
        EGLint minimumSwapInterval = 1;
        EGLint maximumSwapInterval = 1;
        (void)eglGetConfigAttrib(
            display_, config_, EGL_MIN_SWAP_INTERVAL, &minimumSwapInterval);
        (void)eglGetConfigAttrib(
            display_, config_, EGL_MAX_SWAP_INTERVAL, &maximumSwapInterval);
        const bool hostGpuEmulatorQueue =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
        // Reader motion is interactive, not fixed-rate video. The host compositor consumes this
        // Surface at a much lower cadence when swap interval one blocks the producer; keep six
        // completed buffers available but never make a new viewport wait behind stale positions.
        const int requestedSwapInterval = 0;
        const EGLBoolean swapIntervalResult =
            eglSwapInterval(display_, requestedSwapInterval);
        const int nativeSwapResult =
            setNativeWindowSwapInterval(command.window, requestedSwapInterval);
        nativeSwapInterval_ = requestedSwapInterval;
        const float requestedFrameRate = command.refreshPeriodNanos > 0
            ? static_cast<float>(1'000'000'000.0 /
                static_cast<double>(command.refreshPeriodNanos))
            : 60.0F;
        // This is a cadence hint, not another clock: Choreographer still owns admission while the
        // vote keeps the Surface on the display's seamless 60 Hz mode.
        // Surface.setFrameRate(FIXED_SOURCE) is applied before native attachment. Do not overwrite
        // that host-emulator vote with DEFAULT compatibility here; doing so turns the async queue
        // into a mailbox and drops otherwise on-time Choreographer buffers. Physical profiles keep
        // the established default compatibility.
        const int8_t frameRateCompatibility = 0;
        // Java Surface.setFrameRate owns the host vote. A second native vote creates another
        // SurfaceFlinger transaction and can leave mode selection one producer cycle behind.
        const int frameRateResult = hostGpuEmulatorQueue
            ? -4
            : (bufferControls.setFrameRate != nullptr
                ? bufferControls.setFrameRate(
                    command.window, requestedFrameRate, frameRateCompatibility)
                : -3);
        // Shared-buffer + auto-refresh exposes the same producer buffer while GLES is updating
        // it. Several physical Samsung compositors scan that buffer out concurrently, which
        // presents old/new image rows as a cascade of horizontal tears during a fling. Keep the
        // producer, and publish only completed buffers through an ordinary multi-buffer queue so
        // SurfaceFlinger switches the whole frame atomically.
        const int sharedBufferOffResult = hostFrontBuffer
            ? sharedBufferResult
            : (bufferControls.setSharedBufferMode != nullptr
                ? bufferControls.setSharedBufferMode(command.window, false)
                : -3);
        const int autoRefreshOffResult = hostFrontBuffer
            ? autoRefreshResult
            : (bufferControls.setAutoRefresh != nullptr
                ? bufferControls.setAutoRefresh(command.window, false)
                : -3);
        // The historically qualified host path needs front/queued/producer plus three transport
        // spares so gfxstream allocation/acquire never lands in a physical scroll interval.
        // Physical devices keep the smaller four-buffer queue.
        const int bufferCountResult = hostFrontBuffer
            ? -5
            : (bufferControls.setBufferCount != nullptr
                ? bufferControls.setBufferCount(command.window, hostGpuEmulatorQueue ? 6 : 4)
                : -3);
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
        hostFrontBufferMode_ = hostFrontBuffer;
        hostFrontBufferPrimed_ = false;
        hostFrontTailReleaseNotBeforeNanos_ = 0;
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
        lastAppliedProducerSceneId_ = 0;
        lastAppliedGeometryBaseSourceTop_ = 0;
        lastAppliedViewportSourceHeight_ = 0;
        lastJavaGeometrySourceTop_ = 0;
        lastAppliedFrameTiles_.clear();
        frameSequence_ = 0;
        submittedFrames_ = 0;
        lastWindowPresentEndNanos_ = 0;
        lastWindowPresentToken_ = 0;
        consecutivePresentFailures_ = 0;
        surfaceEpoch_ = command.epoch;
        width_ = command.width;
        height_ = command.height;
        refreshPeriodNanos_ = command.refreshPeriodNanos > 0
            ? command.refreshPeriodNanos : kDefaultRefreshPeriodNanos;
        RLOGI("cold SurfaceView BufferQueue attached epoch=%llu size=%dx%d refreshNs=%lld prepared=%d requestedSwap=%d eglSwapResult=%d nativeSwapResult=%d frameRate=%.3f frameRateCompat=%d frameRateResult=%d shared=%d autoRefresh=%d front=%d mutable=%d hostBuffer6=%d bufferCountResult=%d intervalRange=%d..%d durationMs=%.3f",
              static_cast<unsigned long long>(surfaceEpoch_), width_, height_,
              static_cast<long long>(refreshPeriodNanos_),
              preparedWidth_ == width_ && preparedHeight_ == height_ ? 1 : 0,
              requestedSwapInterval,
              swapIntervalResult == EGL_TRUE ? 1 : 0,
              nativeSwapResult,
              static_cast<double>(requestedFrameRate),
              static_cast<int>(frameRateCompatibility),
              frameRateResult,
              sharedBufferOffResult,
              autoRefreshOffResult,
              hostFrontBuffer ? 1 : 0,
              mutableRenderBufferSupported_ ? 1 : 0,
              hostGpuEmulatorQueue ? 1 : 0,
              bufferCountResult,
              minimumSwapInterval, maximumSwapInterval,
              static_cast<double>(end - begin) / 1'000'000.0);
        return true;
    }

    bool detachBackend(JNIEnv* env) noexcept {
        if (!backendAttached_) return true;
        if (hostCpuWindowAttached_) {
            if (nativeWindow_ != nullptr) {
                ANativeWindow_release(nativeWindow_);
                nativeWindow_ = nullptr;
            }
            resetBackendAttachmentState();
            consecutivePresentFailures_ = 0;
            RLOGI("host CPU BufferQueue detached after submitted posts drained");
            return true;
        }
        if (windowSurface_ != EGL_NO_SURFACE) {
            if (!finishHostFrontBufferUpdates(env) || pbuffer_ == EGL_NO_SURFACE ||
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
            consecutivePresentFailures_ = 0;
            RLOGI("rolling BufferQueue detached after submitted swaps drained");
            return true;
        }
        // Drain exact OnCommit/OnComplete/acquire/release evidence before unparenting the layer.
        if (!detachDirectTilePresenterAfterEvidenceDrained(env)) return false;
        if (backend_.prepared() &&
            (!backend_.detachAfterEvidenceDrained() || !backend_.destroy())) return false;
        resetBackendAttachmentState();
        consecutivePresentFailures_ = 0;
        RLOGI("pipelined SurfaceControl detached after exact producer drain");
        return true;
    }

    PresentResult presentHostCpuWindowFrame(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        if (failureStage != nullptr) *failureStage = "cpu-window-entry";
        if (!backendAttached_ || !hostCpuWindowAttached_ || nativeWindow_ == nullptr ||
            frame.width != width_ || frame.height != height_) {
            if (failureStage != nullptr) *failureStage = "cpu-window-surface-or-size";
            return PresentResult::FAILED;
        }
        const std::int64_t begin = nowNanos();
        std::vector<CpuTileReadView> sourceViews;
        if (!prepareCpuTileReadViews(env, frame, &sourceViews)) {
            if (failureStage != nullptr) *failureStage = "cpu-window-source-lock";
            return PresentResult::FAILED;
        }
        const std::int64_t sourceLockEnd = nowNanos();
        ANativeWindow_Buffer buffer{};
        const std::int64_t targetLockBegin = nowNanos();
        if (ANativeWindow_lock(nativeWindow_, &buffer, nullptr) != 0) {
            (void)releaseCpuTileReadViews(env, sourceViews);
            if (failureStage != nullptr) *failureStage = "cpu-window-target-lock";
            return PresentResult::FAILED;
        }
        const std::int64_t targetLockEnd = nowNanos();
        const bool targetValid = buffer.bits != nullptr &&
            buffer.width == hostCpuWindowBufferWidth_ &&
            buffer.height == hostCpuWindowBufferHeight_ &&
            buffer.stride >= hostCpuWindowBufferWidth_ &&
            buffer.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        const std::int64_t drawBegin = nowNanos();
        const bool composed = targetValid && composeCpuFrameRegion(
            frame,
            sourceViews,
            buffer.bits,
            static_cast<std::uint32_t>(buffer.stride),
            hostCpuWindowBufferWidth_,
            hostCpuWindowBufferHeight_,
            frame.viewportSourceTop,
            frame.viewportSourceHeight);
        const std::int64_t drawEnd = nowNanos();
        const bool sourcesReleased = releaseCpuTileReadViews(env, sourceViews);
        const std::int64_t postBegin = nowNanos();
        const int postResult = ANativeWindow_unlockAndPost(nativeWindow_);
        const std::int64_t postEnd = nowNanos();
        if (!targetValid || !composed || !sourcesReleased || postResult != 0) {
            if (failureStage != nullptr) {
                *failureStage = !targetValid
                    ? "cpu-window-target-layout"
                    : (!composed
                        ? "cpu-window-compose"
                        : (!sourcesReleased
                            ? "cpu-window-source-unlock"
                            : "cpu-window-post"));
            }
            return PresentResult::FAILED;
        }

        const std::int64_t previousPresentEnd = lastWindowPresentEndNanos_;
        lastWindowPresentEndNanos_ = postEnd;
        lastWindowPresentToken_ = frame.token;
        ++frameSequence_;
        ++submittedFrames_;
        rememberAppliedFrame(frame, frame.viewportSourceTop);
        pruneTextures(frame);
        callbackWindowFramePresented(env, frame.token, postEnd, 2);
        const std::int64_t totalUs = (postEnd - begin) / 1000;
        const std::int64_t sourceLockUs = (sourceLockEnd - begin) / 1000;
        const std::int64_t targetLockUs = (targetLockEnd - targetLockBegin) / 1000;
        const std::int64_t drawUs = (drawEnd - drawBegin) / 1000;
        const std::int64_t postUs = (postEnd - postBegin) / 1000;
        const std::int64_t presentGapUs = previousPresentEnd > 0
            ? std::max<std::int64_t>(0, (postEnd - previousPresentEnd) / 1000)
            : 0;
        const bool slowLogDue = totalUs >= 16'000 &&
            (lastSlowPresentLogNanos_ == 0 ||
             postEnd - lastSlowPresentLogNanos_ >= 1'000'000'000LL);
        if (submittedFrames_ == 1 || submittedFrames_ % 90 == 0 || slowLogDue) {
            if (slowLogDue) lastSlowPresentLogNanos_ = postEnd;
            RLOGI(
                "cpu window timing submitted=%llu token=%llu totalUs=%lld sourceLockUs=%lld "
                "targetLockUs=%lld drawUs=%lld postUs=%lld gapUs=%lld",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(frame.token),
                static_cast<long long>(totalUs),
                static_cast<long long>(sourceLockUs),
                static_cast<long long>(targetLockUs),
                static_cast<long long>(drawUs),
                static_cast<long long>(postUs),
                static_cast<long long>(presentGapUs));
        }
        if (failureStage != nullptr) *failureStage = "cpu-window-posted";
        return PresentResult::APPLIED;
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
        const std::int64_t admissionNow = nowNanos();
        if (hostFrontBufferMode_ &&
            (hostFrontProof_ != nullptr || !hostFrontSubmissions_.empty() ||
             admissionNow < hostFrontTailReleaseNotBeforeNanos_)) {
            if (failureStage != nullptr) *failureStage = "window-front-tail-proof";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        const std::int64_t begin = nowNanos();
        if (eglGetCurrentSurface(EGL_DRAW) != windowSurface_ &&
            eglMakeCurrent(display_, windowSurface_, windowSurface_, context_) != EGL_TRUE) {
            if (failureStage != nullptr) *failureStage = "window-make-current";
            return PresentResult::FAILED;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        const bool directWifiFreshNames = usesFreshTextureNames();
        const bool residentProducerGeometry = hasResidentProducerGeometryScene(frame);
        if (!residentProducerGeometry &&
            !prepareVisibleFrameTextureHeadroom(frame, directWifiFreshNames)) {
            if (failureStage != nullptr) *failureStage = "window-texture-headroom";
            return PresentResult::FAILED;
        }
        const std::uint64_t textureUseFrame = ++textureUseSerial_;
        const std::int64_t uploadBegin = nowNanos();
        if (!residentProducerGeometry) {
            for (const auto& tile : frame.tileView()) {
                if (!uploadTile(
                        env, tile, textureUseFrame, directWifiFreshNames)) {
                    if (failureStage != nullptr) *failureStage = "window-texture-upload";
                    return PresentResult::FAILED;
                }
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
        // The first shared-front frame is queued normally. Auto-refresh then keeps that exact
        // allocation attached to SurfaceFlinger, so later frames need only flush their draw.
        // Re-queueing the same allocation on every MOVE makes
        // gfxstream periodically drain 250-800 ms of qemu-pipe work even though no buffer switch
        // is needed. Ordinary multi-buffer device surfaces continue swapping every frame.
        const int nativeSwapInterval = nativeSwapInterval_;
        int presentationKind = 2;
        bool frontCallbackDeferred = false;
        if (!hostFrontBufferMode_ || !hostFrontBufferPrimed_) {
            if (eglSwapBuffers(display_, windowSurface_) != EGL_TRUE) {
                if (failureStage != nullptr) *failureStage = "window-swap";
                return PresentResult::FAILED;
            }
            if (hostFrontBufferMode_) hostFrontBufferPrimed_ = true;
        } else {
            presentationKind = 3;
            // Once an exact-tail proof is pending, preserve native callback order for any
            // successor even if a concurrent structure update no longer classifies it as tail.
            frontCallbackDeferred = frame.requiresGpuCompletionProof ||
                !hostFrontSubmissions_.empty();
            if (!submitHostFrontBufferUpdate(
                    frame.token, frontCallbackDeferred)) {
                if (failureStage != nullptr) *failureStage = "window-front-submit";
                return PresentResult::FAILED;
            }
        }
        const std::int64_t presentEnd = nowNanos();
        const std::int64_t previousPresentEnd = lastWindowPresentEndNanos_;
        const std::uint64_t previousPresentToken = lastWindowPresentToken_;
        lastWindowPresentEndNanos_ = presentEnd;
        lastWindowPresentToken_ = frame.token;
        ++frameSequence_;
        ++submittedFrames_;
        rememberAppliedFrame(frame, frame.viewportSourceTop);
        pruneTextures(frame);
        if (presentationKind == 2 || !frontCallbackDeferred) {
            callbackWindowFramePresented(env, frame.token, presentEnd, presentationKind);
        }
        const std::int64_t totalUs = (presentEnd - begin) / 1000;
        const std::int64_t uploadUs = (uploadEnd - uploadBegin) / 1000;
        const std::int64_t drawUs = (drawEnd - drawBegin) / 1000;
        const std::int64_t presentUs = (presentEnd - presentBegin) / 1000;
        const std::int64_t queueWaitUs = frame.enqueuedNanos > 0
            ? std::max<std::int64_t>(0, (begin - frame.enqueuedNanos) / 1000)
            : -1;
        const std::int64_t presentGapUs = previousPresentEnd > 0
            ? std::max<std::int64_t>(0, (presentEnd - previousPresentEnd) / 1000)
            : 0;
        if (presentGapUs >= 500'000) {
            RLOGI(
                "window cadence gap previousToken=%llu token=%llu gapUs=%lld queueWaitUs=%lld totalUs=%lld uploadUs=%lld drawUs=%lld presentUs=%lld textures=%zu bytes=%llu debtNames=%zu debtBytes=%llu",
                static_cast<unsigned long long>(previousPresentToken),
                static_cast<unsigned long long>(frame.token),
                static_cast<long long>(presentGapUs),
                static_cast<long long>(queueWaitUs),
                static_cast<long long>(totalUs),
                static_cast<long long>(uploadUs),
                static_cast<long long>(drawUs),
                static_cast<long long>(presentUs),
                textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                textureRetirementDebt_.names(),
                static_cast<unsigned long long>(textureRetirementDebt_.bytes()));
        }
        const bool slowLogDue = totalUs >= 16'000 &&
            (lastSlowPresentLogNanos_ == 0 ||
             presentEnd - lastSlowPresentLogNanos_ >= 5'000'000'000LL);
        if (submittedFrames_ == 1 || submittedFrames_ % 300 == 0 || slowLogDue) {
            if (slowLogDue) lastSlowPresentLogNanos_ = presentEnd;
            RLOGI(
                "window present submitted=%llu token=%llu totalUs=%lld uploadUs=%lld drawUs=%lld presentUs=%lld kind=%d nativeSwapInterval=%d textures=%zu bytes=%llu pool=%zu poolBytes=%llu reused=%llu prewarmSkipped=%llu",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(frame.token),
                static_cast<long long>(totalUs),
                static_cast<long long>(uploadUs),
                static_cast<long long>(drawUs),
                static_cast<long long>(presentUs), presentationKind, nativeSwapInterval,
                textures_.size(),
                static_cast<unsigned long long>(residentTextureBytes_),
                pooledTextures_.size(),
                static_cast<unsigned long long>(pooledTextureBytes_),
                static_cast<unsigned long long>(reusedPooledTextures_),
                static_cast<unsigned long long>(skippedResidentPrewarmTiles_));
        }
        if (failureStage != nullptr) {
            *failureStage = presentationKind == 2
                ? "window-queued" : "window-front-submitted";
        }
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
        // The host AChoreographer AVsyncId belongs to the producer callback and is not accepted
        // by an ASurfaceTransaction applied later on this renderer thread. Passing it produces
        // "Start time was not found" and delays the actual layer update; submit the buffer at the
        // next compositor opportunity instead.
        const std::int64_t appliedFrameTimelineVsyncId = 0;
        const auto disposition = backend_.applyPreparedBufferTransactionDirect(
            candidate.submission,
            appliedFrameTimelineVsyncId,
            &receipt);
        if (disposition !=
                ntk::present::SurfaceControlPresentBackend::ApplyDisposition::APPLIED ||
            !receipt.submitted) {
            if (failureStage != nullptr) *failureStage = "transaction-apply";
            return PresentResult::FAILED;
        }
        const std::int64_t applyEnd = nowNanos();
        ++submittedFrames_;
        submissionAwaitingLatch_ = true;
        lastGeometryDesiredPresentNanos_ = applyEnd;
        rememberAppliedFrame(candidate.frame, candidate.geometryBaseSourceTop);
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
                "present timing submitted=%llu token=%llu cpu=%d totalUs=%lld bindUs=%lld uploadUs=%lld drawUs=%lld fenceUs=%lld prepareUs=%lld waitUs=%lld applyUs=%lld finalizeUs=%lld textures=%zu bytes=%llu pool=%zu poolBytes=%llu reused=%llu prewarmSkipped=%llu",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(candidate.frame.token),
                candidate.cpuComposed ? 1 : 0,
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

    PresentResult presentGeometryOnlyFrame(
            JNIEnv* env,
            FrameCommand& frame,
            int appliedViewportSourceTop,
            const char** failureStage) noexcept {
        if (appliedViewportSourceTop < 0 ||
            appliedViewportSourceTop > frame.height - frame.viewportSourceHeight) {
            if (failureStage != nullptr) *failureStage = "geometry-source-crop";
            return PresentResult::FAILED;
        }
        if (javaFrameSyncedGeometry_) {
            if (submissionAwaitingLatch_) {
                if (failureStage != nullptr) *failureStage = "java-geometry-prior-latch";
                return PresentResult::TRANSIENT_BACKPRESSURE;
            }
            if (!callbackGeometryFrameRequested(
                    env, frame.token, appliedViewportSourceTop,
                    frame.viewportSourceHeight,
                    lastAppliedGeometryBaseSourceTop_,
                    frame.frameTimelineVsyncId,
                    frame.expectedPresentationTimeNanos)) {
                // Java admits one exact geometry transaction until SurfaceFlinger commits it.
                // Preserve this FIFO head and retry after that listener releases the slot.
                if (failureStage != nullptr) *failureStage = "java-geometry-backpressure";
                return PresentResult::TRANSIENT_BACKPRESSURE;
            }
            ++frameSequence_;
            ++submittedFrames_;
            lastJavaGeometrySourceTop_ = appliedViewportSourceTop;
            lastGeometryDesiredPresentNanos_ = 0;
            // Java owns the exact transaction until its API-35 completed listener proves that
            // SurfaceFlinger presented it. Native must not also mutate this child or manufacture
            // a latch from callback dispatch; Kotlin's pending proof remains the quiescence gate.
            if (submittedFrames_ <= 4 || submittedFrames_ % 120 == 0) {
            RLOGI(
                    "java-frame-synced geometry token=%llu source=%d+%d requested=%d target=%dx%d submitted=%llu unchanged=%d",
                    static_cast<unsigned long long>(frame.token),
                    appliedViewportSourceTop, frame.viewportSourceHeight,
                    frame.viewportSourceTop,
                    frame.width, frame.height,
                    static_cast<unsigned long long>(submittedFrames_),
                    0);
            }
            if (failureStage != nullptr) *failureStage = "java-geometry-requested";
            return PresentResult::APPLIED;
        }
        if (!backend_.hasGeometryTransactionCapacity()) {
            if (failureStage != nullptr) *failureStage = "geometry-capacity";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        ntk::present::SurfaceControlPresentBackend::FixedPreparedFrameIdentityBase identity{};
        identity.engineGeneration = 1;
        identity.surfaceEpoch = surfaceEpoch_;
        identity.authorityGeneration = 1;
        identity.authority = 1;
        identity.workGeneration = frame.token;
        identity.ntkFrameId = frame.token;
        identity.frameSequence = ++frameSequence_;
        identity.capsuleSequence = frame.token;
        // Host FrameTimeline IDs are scoped to the producer Choreographer callback and Android's
        // NDK transaction rejects them after the cross-thread handoff. Applying without that
        // invalid identity still commits this exact crop at the next SurfaceFlinger cut.
        const std::int64_t appliedFrameTimelineVsyncId = 0;
        const std::int64_t applyNowNanos = nowNanos();
        const std::int64_t geometryRefreshPeriodNanos =
            refreshPeriodNanos_ > 0
                ? refreshPeriodNanos_
                : kDefaultRefreshPeriodNanos;
        const bool hostHandlerOwnsCadence =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
        const std::int64_t desiredPresentNanos =
            ntk::present::geometryDesiredPresentNanosForRuntime(
                hostHandlerOwnsCadence,
                frame.expectedPresentationTimeNanos,
                applyNowNanos,
                lastGeometryDesiredPresentNanos_,
                geometryRefreshPeriodNanos);
        ntk::present::SurfaceControlPresentBackend::SubmissionReceipt receipt{};
        const auto disposition = backend_.applyGeometryTransactionDirect(
            identity, appliedViewportSourceTop, frame.viewportSourceHeight,
            appliedFrameTimelineVsyncId, desiredPresentNanos, &receipt);
        if (disposition != ntk::present::SurfaceControlPresentBackend::
                ApplyDisposition::APPLIED || !receipt.submitted ||
            receipt.setBufferCount != 0 || receipt.transactionApplyCount != 1 ||
            receipt.setFrameTimelineCount !=
                (appliedFrameTimelineVsyncId > 0 ? 1U : 0U)) {
            if (failureStage != nullptr) *failureStage = "geometry-apply";
            return PresentResult::FAILED;
        }
        lastGeometryDesiredPresentNanos_ = desiredPresentNanos;
        ++submittedFrames_;
        // A crop-only transaction changes no buffer pixels. Keep the signature of the buffer
        // that was actually composed; adopting newly arrived off-screen tiles here would later
        // claim pixels that this HardwareBuffer never received.
        if (submittedFrames_ <= 4 || submittedFrames_ % 120 == 0) {
            RLOGI(
                "geometry-only frame token=%llu source=%d+%d requested=%d target=%dx%d submitted=%llu",
                static_cast<unsigned long long>(frame.token),
                appliedViewportSourceTop, frame.viewportSourceHeight,
                frame.viewportSourceTop,
                frame.width, frame.height,
                static_cast<unsigned long long>(submittedFrames_));
        }
        if (failureStage != nullptr) *failureStage = "geometry-applied";
        return PresentResult::APPLIED;
    }

    PresentResult presentReadyGpuBand(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        int composedViewportSourceTop = -1;
        if (!readyGpuBand_.occupied || readyGpuBand_.target == nullptr ||
            !composedFrameCoversViewport(
                readyGpuBand_.composedFrame, frame,
                &composedViewportSourceTop)) {
            if (failureStage != nullptr) *failureStage = "gpu-band-mismatch";
            return PresentResult::FAILED;
        }
        // Keep the exported target parked while the previous crop's exact OnCommit is pending.
        // Preparing and aborting here would throw away the runway precisely at its visible edge.
        if (submissionAwaitingLatch_ || !backend_.hasDirectSubmissionCapacity()) {
            if (failureStage != nullptr) *failureStage = "gpu-band-prior-latch";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }

        auto* target = readyGpuBand_.target;
        PreparedDirectFrame candidate{};
        candidate.beginNanos = readyGpuBand_.beginNanos;
        candidate.bindEndNanos = readyGpuBand_.bindEndNanos;
        candidate.uploadBeginNanos = readyGpuBand_.uploadBeginNanos;
        candidate.uploadEndNanos = readyGpuBand_.uploadEndNanos;
        candidate.renderBeginNanos = readyGpuBand_.renderBeginNanos;
        candidate.renderEndNanos = readyGpuBand_.renderEndNanos;
        candidate.fenceBeginNanos = readyGpuBand_.fenceBeginNanos;
        candidate.fenceEndNanos = readyGpuBand_.fenceEndNanos;

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
        candidate.prepareBeginNanos = nowNanos();
        if (!backend_.prepareBufferTransaction(
                identity, *target, false, profile_,
                &candidate.submission, &unusedProof)) {
            RLOGE(
                "GPU band transaction prepare rejected reason=%s token=%llu slot=%llu generation=%llu",
                backend_.lastPreparationFailureReason(),
                static_cast<unsigned long long>(frame.token),
                static_cast<unsigned long long>(target->slot),
                static_cast<unsigned long long>(target->generation));
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            releaseFrame(env, readyGpuBand_.composedFrame);
            readyGpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "gpu-band-prepare";
            return PresentResult::FAILED;
        }
        candidate.geometryBaseSourceTop = composedViewportSourceTop;
        if (!backend_.configurePreparedSourceCrop(
                candidate.submission, composedViewportSourceTop,
                frame.viewportSourceHeight, candidate.geometryBaseSourceTop)) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, readyGpuBand_.composedFrame);
            readyGpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "gpu-band-source-crop";
            return PresentResult::FAILED;
        }
        candidate.prepareEndNanos = nowNanos();
        candidate.frame = std::move(frame);
        candidate.occupied = true;

        const PresentResult result = applyPreparedDirectFrame(candidate, failureStage);
        if (result == PresentResult::APPLIED) {
            rememberAppliedComposition(
                readyGpuBand_.composedFrame,
                candidate.frame,
                candidate.geometryBaseSourceTop);
        } else if (result == PresentResult::PREPARED_WAITING) {
            // The admission gate above and renderer ownership make this a genuine invariant
            // race, not transient backpressure. Preserve neither a transaction nor an exported
            // acquire fence after reporting failure.
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
        }
        frame = std::move(candidate.frame);
        releaseFrame(env, readyGpuBand_.composedFrame);
        readyGpuBand_ = {};
        return result == PresentResult::PREPARED_WAITING
            ? PresentResult::FAILED
            : result;
    }

    PresentResult presentRequiredGpuBand(
            JNIEnv* env,
            const char** failureStage) noexcept {
        if (!readyGpuBand_.occupied || !readyGpuBand_.presentOnCompletion ||
            readyGpuBand_.target == nullptr ||
            readyGpuBand_.composedFrame.token == 0) {
            if (failureStage != nullptr) *failureStage = "required-gpu-band-missing";
            return PresentResult::FAILED;
        }
        if (submissionAwaitingLatch_ || !backend_.hasDirectSubmissionCapacity()) {
            if (failureStage != nullptr) *failureStage = "required-gpu-band-prior-latch";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }

        auto* target = readyGpuBand_.target;
        PreparedDirectFrame candidate{};
        candidate.beginNanos = readyGpuBand_.beginNanos;
        candidate.bindEndNanos = readyGpuBand_.bindEndNanos;
        candidate.uploadBeginNanos = readyGpuBand_.uploadBeginNanos;
        candidate.uploadEndNanos = readyGpuBand_.uploadEndNanos;
        candidate.renderBeginNanos = readyGpuBand_.renderBeginNanos;
        candidate.renderEndNanos = readyGpuBand_.renderEndNanos;
        candidate.fenceBeginNanos = readyGpuBand_.fenceBeginNanos;
        candidate.fenceEndNanos = readyGpuBand_.fenceEndNanos;

        const FrameCommand& composed = readyGpuBand_.composedFrame;
        ntk::present::SurfaceControlPresentBackend::FixedPreparedFrameIdentityBase identity{};
        identity.engineGeneration = 1;
        identity.surfaceEpoch = surfaceEpoch_;
        identity.authorityGeneration = 1;
        identity.authority = 1;
        identity.workGeneration = composed.token;
        identity.ntkFrameId = composed.token;
        identity.frameSequence = ++frameSequence_;
        identity.capsuleSequence = composed.token;
        SwappyFixedExternalTransportReady unusedProof{};
        candidate.prepareBeginNanos = nowNanos();
        if (!backend_.prepareBufferTransaction(
                identity, *target, false, profile_,
                &candidate.submission, &unusedProof)) {
            RLOGE(
                "required GPU band transaction prepare rejected reason=%s token=%llu slot=%llu generation=%llu",
                backend_.lastPreparationFailureReason(),
                static_cast<unsigned long long>(composed.token),
                static_cast<unsigned long long>(target->slot),
                static_cast<unsigned long long>(target->generation));
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            releaseFrame(env, readyGpuBand_.composedFrame);
            readyGpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "required-gpu-band-prepare";
            return PresentResult::FAILED;
        }
        candidate.geometryBaseSourceTop = composed.viewportSourceTop;
        if (!backend_.configurePreparedSourceCrop(
                candidate.submission,
                composed.viewportSourceTop,
                composed.viewportSourceHeight,
                candidate.geometryBaseSourceTop)) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, readyGpuBand_.composedFrame);
            readyGpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "required-gpu-band-source-crop";
            return PresentResult::FAILED;
        }
        candidate.prepareEndNanos = nowNanos();
        candidate.frame = std::move(readyGpuBand_.composedFrame);
        candidate.occupied = true;
        readyGpuBand_ = {};

        const PresentResult result = applyPreparedDirectFrame(candidate, failureStage);
        if (result == PresentResult::PREPARED_WAITING) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, candidate.frame);
            if (failureStage != nullptr) *failureStage = "required-gpu-band-latch-race";
            return PresentResult::FAILED;
        }
        releaseFrame(env, candidate.frame);
        return result;
    }

    PresentResult presentReadyCpuBand(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        int composedViewportSourceTop = -1;
        if (!readyCpuBand_.occupied || readyCpuBand_.target == nullptr ||
            !composedFrameCoversViewport(
                readyCpuBand_.composedFrame, frame,
                &composedViewportSourceTop)) {
            if (failureStage != nullptr) *failureStage = "precomposed-band-mismatch";
            return PresentResult::FAILED;
        }
        auto* target = readyCpuBand_.target;
        PreparedDirectFrame candidate{};
        candidate.beginNanos = nowNanos();
        candidate.bindEndNanos = candidate.beginNanos;
        candidate.uploadBeginNanos = candidate.beginNanos;
        candidate.uploadEndNanos = candidate.beginNanos;
        candidate.renderBeginNanos = candidate.beginNanos;
        candidate.renderEndNanos = candidate.beginNanos;
        candidate.fenceBeginNanos = candidate.beginNanos;
        candidate.fenceEndNanos = candidate.beginNanos;
        candidate.cpuComposed = true;

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
            RLOGE(
                "direct transaction prepare rejected reason=%s token=%llu slot=%llu generation=%llu first=%d",
                backend_.lastPreparationFailureReason(),
                static_cast<unsigned long long>(frame.token),
                static_cast<unsigned long long>(target->slot),
                static_cast<unsigned long long>(target->generation),
                first ? 1 : 0);
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            releaseFrame(env, readyCpuBand_.composedFrame);
            readyCpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "precomposed-transaction-prepare";
            return PresentResult::FAILED;
        }
        candidate.geometryBaseSourceTop = composedViewportSourceTop;
        if (!backend_.configurePreparedSourceCrop(
                candidate.submission,
                composedViewportSourceTop,
                frame.viewportSourceHeight,
                candidate.geometryBaseSourceTop)) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, readyCpuBand_.composedFrame);
            readyCpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "precomposed-source-crop";
            return PresentResult::FAILED;
        }
        candidate.prepareEndNanos = nowNanos();
        candidate.frame = std::move(frame);
        candidate.occupied = true;

        const PresentResult result = applyPreparedDirectFrame(candidate, failureStage);
        if (result == PresentResult::APPLIED) {
            rememberAppliedComposition(
                readyCpuBand_.composedFrame,
                candidate.frame,
                candidate.geometryBaseSourceTop);
        } else if (result == PresentResult::PREPARED_WAITING) {
            if (!backend_.abortPreparedBufferTransaction(candidate.submission) &&
                failureStage != nullptr) {
                *failureStage = "precomposed-prior-latch-abort";
            }
        }
        frame = std::move(candidate.frame);
        releaseFrame(env, readyCpuBand_.composedFrame);
        readyCpuBand_ = {};
        return result == PresentResult::PREPARED_WAITING
            ? PresentResult::TRANSIENT_BACKPRESSURE
            : result;
    }

    PresentResult presentRequiredCpuBand(
            JNIEnv* env,
            const char** failureStage) noexcept {
        if (!readyCpuBand_.occupied || !readyCpuBand_.presentOnCompletion ||
            readyCpuBand_.target == nullptr ||
            readyCpuBand_.composedFrame.token == 0) {
            if (failureStage != nullptr) *failureStage = "required-band-missing";
            return PresentResult::FAILED;
        }
        // The old exact buffer may still have an OnCommit in flight. Do not prepare and then
        // abort the already-composed successor, because aborting returns its target to FREE.
        if (submissionAwaitingLatch_ || !backend_.hasDirectSubmissionCapacity()) {
            if (failureStage != nullptr) *failureStage = "required-band-prior-latch";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }

        auto* target = readyCpuBand_.target;
        PreparedDirectFrame candidate{};
        candidate.beginNanos = readyCpuBand_.beginNanos;
        candidate.bindEndNanos = readyCpuBand_.lockEndNanos;
        candidate.uploadBeginNanos = candidate.bindEndNanos;
        candidate.uploadEndNanos = candidate.bindEndNanos;
        candidate.renderBeginNanos = candidate.bindEndNanos;
        candidate.renderEndNanos = readyCpuBand_.renderEndNanos;
        candidate.fenceBeginNanos = candidate.renderEndNanos;
        candidate.fenceEndNanos = readyCpuBand_.finishEndNanos;
        candidate.cpuComposed = true;

        const FrameCommand& composed = readyCpuBand_.composedFrame;
        ntk::present::SurfaceControlPresentBackend::FixedPreparedFrameIdentityBase identity{};
        identity.engineGeneration = 1;
        identity.surfaceEpoch = surfaceEpoch_;
        identity.authorityGeneration = 1;
        identity.authority = 1;
        identity.workGeneration = composed.token;
        identity.ntkFrameId = composed.token;
        identity.frameSequence = ++frameSequence_;
        identity.capsuleSequence = composed.token;
        SwappyFixedExternalTransportReady unusedProof{};
        candidate.prepareBeginNanos = nowNanos();
        if (!backend_.prepareBufferTransaction(
                identity, *target, false, profile_,
                &candidate.submission, &unusedProof)) {
            (void)backend_.abortRenderTargetBeforePreparation(
                target->slot, target->generation);
            releaseFrame(env, readyCpuBand_.composedFrame);
            readyCpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "required-band-prepare";
            return PresentResult::FAILED;
        }
        candidate.geometryBaseSourceTop = composed.viewportSourceTop;
        if (!backend_.configurePreparedSourceCrop(
                candidate.submission,
                composed.viewportSourceTop,
                composed.viewportSourceHeight,
                candidate.geometryBaseSourceTop)) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, readyCpuBand_.composedFrame);
            readyCpuBand_ = {};
            if (failureStage != nullptr) *failureStage = "required-band-source-crop";
            return PresentResult::FAILED;
        }
        candidate.prepareEndNanos = nowNanos();
        candidate.frame = std::move(readyCpuBand_.composedFrame);
        candidate.occupied = true;
        readyCpuBand_ = {};

        const PresentResult result = applyPreparedDirectFrame(candidate, failureStage);
        if (result == PresentResult::PREPARED_WAITING) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            releaseFrame(env, candidate.frame);
            if (failureStage != nullptr) *failureStage = "required-band-latch-race";
            return PresentResult::FAILED;
        }
        releaseFrame(env, candidate.frame);
        return result;
    }

    bool collectDirectTileInputs(
            const FrameCommand& frame,
            std::vector<ntk::present::DirectTileLayerInput>* output,
            bool* mirrorPending) noexcept {
        if (output == nullptr || mirrorPending == nullptr) return false;
        output->clear();
        *mirrorPending = false;
        if (!directTilePresenter_.attached() || frame.tileView().empty() ||
            frame.viewportSourceTop < 0 || frame.viewportSourceHeight <= 0 ||
            frame.viewportSourceTop > frame.height - frame.viewportSourceHeight) return false;
        const double cropTop = static_cast<double>(frame.viewportSourceTop);
        const double cropBottom = cropTop + frame.viewportSourceHeight;
        for (const auto& tile : frame.tileView()) {
            const bool visible = tileIntersectsSourceCrop(tile, cropTop, cropBottom);
            const bool cpuContent = tile.cpuBufferResource &&
                exactCpuTileHasContent(
                    tile.exactCpuBuffer,
                    tile.sourceWidth,
                    tile.sourceBottom - tile.sourceTop);
            const bool mirrorReady = cpuContent &&
                tile.exactCpuBuffer->hardwareMirrorReady.load(std::memory_order_acquire);
            if (!cpuContent || tile.exactCpuBuffer->hardwareBuffer == nullptr || !mirrorReady ||
                tile.contentIdentity == 0) {
                if (!visible) continue;
                if (cpuContent && tile.exactCpuBuffer->hardwareBuffer != nullptr &&
                    !mirrorReady && tile.contentIdentity != 0) {
                    *mirrorPending = true;
                    return false;
                }
                RLOGE(
                    "direct tile source unavailable token=%llu epoch=%lld page=%d slot=%d "
                    "cpu=%d hardware=%d cpuValid=%d mirror=%d content=%llu source=%dx%d+%d..%d",
                    static_cast<unsigned long long>(frame.token),
                    static_cast<long long>(frame.structureEpoch),
                    tile.key.page, tile.key.slot,
                    tile.cpuBufferResource ? 1 : 0,
                    tile.hardwareBufferResource ? 1 : 0,
                    cpuContent ? 1 : 0,
                    mirrorReady ? 1 : 0,
                    static_cast<unsigned long long>(tile.contentIdentity),
                    tile.sourceWidth, tile.sourceHeight,
                    tile.sourceTop, tile.sourceBottom);
                return false;
            }
            output->push_back({
                .buffer = tile.exactCpuBuffer->hardwareBuffer,
                .acquireFenceFd = tile.exactCpuBuffer->hardwareWriteFenceFd.load(
                    std::memory_order_acquire),
                .contentIdentity = tile.contentIdentity,
                .structureEpoch = tile.key.structureEpoch,
                .page = tile.key.page,
                .slot = tile.key.slot,
                .sourceTop = tile.sourceTop,
                .sourceBottom = tile.sourceBottom,
                .sourceHeight = tile.sourceHeight,
                .contentWidth = tile.exactCpuBuffer->contentWidth,
                .contentHeight = tile.exactCpuBuffer->contentHeight,
                .pageTop = tile.pageTop,
                .pageHeight = tile.pageHeight,
            });
        }
        return !output->empty();
    }

    bool buildDirectTileInputs(const FrameCommand& frame) noexcept {
        return collectDirectTileInputs(
            frame, &directTileInputs_, &directTileMirrorPending_);
    }

    bool frameHasVisiblePendingDirectTileMirror(const FrameCommand& frame) const noexcept {
        if (frame.tileView().empty() || frame.viewportSourceTop < 0 ||
            frame.viewportSourceHeight <= 0 ||
            frame.viewportSourceTop > frame.height - frame.viewportSourceHeight) {
            return false;
        }
        const double cropTop = static_cast<double>(frame.viewportSourceTop);
        const double cropBottom = cropTop + frame.viewportSourceHeight;
        for (const auto& tile : frame.tileView()) {
            if (!tile.cpuBufferResource ||
                !tileIntersectsSourceCrop(tile, cropTop, cropBottom)) continue;
            const auto* storage = tile.exactCpuBuffer;
            if (exactCpuTileHasContent(
                    storage,
                    tile.sourceWidth,
                    tile.sourceBottom - tile.sourceTop) &&
                storage->hardwareBuffer != nullptr &&
                !storage->hardwareMirrorReady.load(std::memory_order_acquire)) {
                return true;
            }
        }
        return false;
    }

    bool directTileFrameEligible(const FrameCommand& frame) noexcept {
        return buildDirectTileInputs(frame);
    }

    PresentResult presentDirectTileFrame(
            FrameCommand& frame,
            const char** failureStage) noexcept {
        if (failureStage != nullptr) *failureStage = "direct-tile-entry";
        if (!buildDirectTileInputs(frame)) {
            if (failureStage != nullptr) *failureStage = "direct-tile-source";
            return directTilePresentationActivated_ && !directTileMirrorPending_
                ? PresentResult::FAILED
                : PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (!directTilePresenter_.canPresent()) {
            if (failureStage != nullptr) *failureStage = "direct-tile-prior-commit";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        const ntk::present::DirectTileFrameInput input{
            .token = frame.token,
            .producerSceneId = frame.producerSceneId,
            .structureEpoch = frame.structureEpoch,
            .bandWidth = frame.width,
            .bandHeight = frame.height,
            .viewportSourceTop = frame.viewportSourceTop,
            .viewportSourceHeight = frame.viewportSourceHeight,
            .tiles = directTileInputs_.data(),
            .tileCount = directTileInputs_.size(),
        };
        const std::int64_t begin = nowNanos();
        if (!directTilePresenter_.present(input)) {
            if (failureStage != nullptr) *failureStage = "direct-tile-apply";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        const std::int64_t end = nowNanos();
        const std::int64_t queueWaitNanos = frame.enqueuedNanos > 0
            ? std::max<std::int64_t>(0, begin - frame.enqueuedNanos)
            : 0;
        directTilePresentationActivated_ = true;
        ++frameSequence_;
        ++submittedFrames_;
        rememberAppliedFrame(frame, frame.viewportSourceTop);
        pruneTextures(frame);
        // This executes on the renderer owner immediately after SurfaceControl::apply. Emitting a
        // line for every slow host-compositor call made logd part of the following frame. Exact
        // counters and callback proofs remain unchanged; enable the timing stream explicitly.
        const bool timingDiagnosticsEnabled = rollingTimingDiagnosticsEnabled();
        if ((queueWaitNanos >= 50'000'000 || end - begin >= 50'000'000) ||
            (timingDiagnosticsEnabled &&
             (submittedFrames_ == 1 || submittedFrames_ % 120 == 0 ||
              end - begin >= 16'000'000))) {
            RLOGI(
                "direct tile present submitted=%llu token=%llu tiles=%zu queueUs=%lld applyUs=%lld geometryOnly=%d",
                static_cast<unsigned long long>(submittedFrames_),
                static_cast<unsigned long long>(frame.token),
                directTileInputs_.size(),
                static_cast<long long>(queueWaitNanos / 1000),
                static_cast<long long>((end - begin) / 1000),
                frame.producerSceneGeometryOnly ? 1 : 0);
        }
        if (failureStage != nullptr) *failureStage = "direct-tile-applied";
        return PresentResult::APPLIED;
    }

    PresentResult presentFrame(
            JNIEnv* env,
            FrameCommand& frame,
            const char** failureStage) noexcept {
        if (hostCpuWindowAttached_) {
            return presentHostCpuWindowFrame(env, frame, failureStage);
        }
        if (windowSurface_ != EGL_NO_SURFACE) {
            return presentWindowFrame(env, frame, failureStage);
        }
        if (failureStage != nullptr) *failureStage = "entry";
        if (!backendAttached_ || frame.width != width_ || frame.height != height_) {
            if (failureStage != nullptr) *failureStage = "surface-or-size";
            return PresentResult::FAILED;
        }
        if (hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
            directTilePresenter_.attached()) {
            if (directTileFrameEligible(frame) || directTilePresentationActivated_) {
                return presentDirectTileFrame(frame, failureStage);
            }
        }
        // An optional successor was composed only after a geometry frame proved that the old and
        // new bands contain the same current pixels. Promote it on the next product frame while
        // that overlap is still visible. If a preceding buffer latch temporarily blocks direct
        // admission, keep publishing geometry from the old exact band and retry on the following
        // frame; never turn early promotion into input backpressure.
        if (readyGpuBand_.occupied && !readyGpuBand_.presentOnCompletion &&
            composedFrameCoversViewport(readyGpuBand_.composedFrame, frame)) {
            const PresentResult promoted = presentReadyGpuBand(env, frame, failureStage);
            if (promoted != PresentResult::TRANSIENT_BACKPRESSURE) return promoted;
        }
        if (readyCpuBand_.occupied && !readyCpuBand_.presentOnCompletion &&
            composedFrameCoversViewport(readyCpuBand_.composedFrame, frame)) {
            const PresentResult promoted = presentReadyCpuBand(env, frame, failureStage);
            if (promoted != PresentResult::TRANSIENT_BACKPRESSURE) return promoted;
        }
        int appliedViewportSourceTop = -1;
        if (submittedFrames_ > 0 &&
            matchesLastAppliedFrame(frame, &appliedViewportSourceTop)) {
            // A prepared successor is a runway cache, not a reason to replace a buffer whose
            // exact pixels still cover the viewport. Across a band-origin change the translated
            // crop keeps using the old overlap while the successor waits for the edge.
            const PresentResult result = presentGeometryOnlyFrame(
                env, frame, appliedViewportSourceTop, failureStage);
            const bool entireBandApplied = entireBandAlreadyApplied(frame);
            const bool shouldPrepareSuccessor =
                ntk::present::shouldPrecomposeRollingBandSuccessor(
                    result == PresentResult::APPLIED,
                    entireBandApplied,
                    appliedViewportSourceTop,
                    frame.viewportSourceTop,
                    requestedPrewarmPaused_.load(std::memory_order_acquire));
            if (result == PresentResult::APPLIED && !entireBandApplied) {
                if (readyGpuBand_.occupied &&
                    !composedFrameCoversViewport(
                        readyGpuBand_.composedFrame, frame)) {
                    discardReadyGpuBand(env);
                }
                if (readyCpuBand_.occupied &&
                    !composedFrameCoversViewport(
                        readyCpuBand_.composedFrame, frame)) {
                    // A successor that cannot reproduce today's exact viewport has already been
                    // invalidated by visible content. Retire it before considering a replacement.
                    discardReadyCpuBand(env);
                }
                if (shouldPrepareSuccessor &&
                    !readyCpuBand_.occupied &&
                    !cpuComposeInFlight_.load(std::memory_order_acquire) &&
                    !readyGpuBand_.occupied &&
                    !gpuFenceInFlight_.load(std::memory_order_acquire)) {
                    (void)startGpuBandPrecomposition(env, frame);
                }
            }
            return result;
        }
        if (readyGpuBand_.occupied) {
            if (composedFrameCoversViewport(readyGpuBand_.composedFrame, frame)) {
                return presentReadyGpuBand(env, frame, failureStage);
            }
            discardReadyGpuBand(env);
        }
        if (gpuFenceInFlight_.load(std::memory_order_acquire)) {
            if (failureStage != nullptr) *failureStage = "gpu-band-exporting";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (readyCpuBand_.occupied) {
            if (composedFrameCoversViewport(readyCpuBand_.composedFrame, frame)) {
                return presentReadyCpuBand(env, frame, failureStage);
            }
            // A newer content/geometry identity can supersede an off-screen snapshot before it is
            // used. Retire that exact target and let this latest command choose a new composition;
            // never claim pixels the prepared band does not contain.
            discardReadyCpuBand(env);
        }
        if (submittedFrames_ > 0 &&
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
            !backend_.cpuComposerOnly()) {
            if (!readyGpuBand_.occupied &&
                !gpuFenceInFlight_.load(std::memory_order_acquire) &&
                startGpuBandPrecomposition(env, frame, true)) {
                // The exact token moved into the GPU fence job. Its completion is applied before
                // any later mailbox frame, exactly like the CPU-only required-band owner below.
                if (failureStage != nullptr) *failureStage = "required-gpu-band-precomposing";
                return PresentResult::PREPARED_WAITING;
            }
            // Host-GPU SurfaceControl never replaces a missed runway with a CPU-written large
            // buffer. Measured gralloc unlocks serialize gfxstream for 140-440 ms even when the
            // source is already an exact CPU tile. Keep the FIFO head and retry GPU admission
            // after the current fence/target owner wakes us.
            if (failureStage != nullptr) *failureStage = "required-gpu-band-admission";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (submittedFrames_ > 0 &&
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
            (backend_.cpuComposerOnly() || cpuComposeInFlight_.load(std::memory_order_acquire))) {
            if (!cpuComposeInFlight_.load(std::memory_order_acquire) &&
                !readyCpuBand_.occupied &&
                startCpuBandPrecomposition(frame, true)) {
                // The worker now owns this exact token and all of its immutable tile references.
                // The renderer continues draining any queued crop-only frames from the old
                // overlap; completion applies this token before later commands, without a
                // mailbox retirement.
                if (failureStage != nullptr) *failureStage = "required-band-precomposing";
                return PresentResult::PREPARED_WAITING;
            }
            // After the first image, never fall back to a 16-22 MiB CPU compose on the display
            // owner merely because the target/latch gate is briefly occupied. Preserve this
            // command at the queue head; a fence/completion wake will retry the off-thread lane.
            if (failureStage != nullptr) *failureStage = "required-band-admission";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (!backend_.hasDirectSubmissionCapacity() ||
            !backend_.pool().hasFreeRenderTarget()) {
            if (failureStage != nullptr) *failureStage = "capacity";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }

        PreparedDirectFrame candidate{};
        candidate.beginNanos = nowNanos();
        std::vector<CpuTileReadView> cpuSourceViews;
        candidate.cpuComposed = backend_.cpuComposerOnly() &&
            prepareCpuTileReadViews(env, frame, &cpuSourceViews);
        auto* target = backend_.acquireRenderTarget();
        if (target == nullptr) {
            if (candidate.cpuComposed) {
                (void)releaseCpuTileReadViews(env, cpuSourceViews);
            }
            if (failureStage != nullptr) *failureStage = "target-acquire";
            return PresentResult::TRANSIENT_BACKPRESSURE;
        }
        if (candidate.cpuComposed) {
            void* destinationPixels = nullptr;
            std::uint32_t destinationStridePixels = 0;
            if (!backend_.lockRenderTargetForCpuWrite(
                    *target, &destinationPixels, &destinationStridePixels)) {
                (void)releaseCpuTileReadViews(env, cpuSourceViews);
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                if (failureStage != nullptr) *failureStage = "target-cpu-lock";
                return PresentResult::FAILED;
            }
            candidate.bindEndNanos = nowNanos();
            candidate.uploadBeginNanos = candidate.bindEndNanos;
            candidate.uploadEndNanos = candidate.bindEndNanos;
            candidate.renderBeginNanos = nowNanos();
            const bool composed = composeCpuFrame(
                frame, cpuSourceViews, destinationPixels, destinationStridePixels);
            candidate.renderEndNanos = nowNanos();
            const bool sourcesReleased =
                releaseCpuTileReadViews(env, cpuSourceViews);
            candidate.fenceBeginNanos = candidate.renderEndNanos;
            const bool finished =
                backend_.finishCpuWrite(*target);
            candidate.fenceEndNanos = nowNanos();
            if (!composed || !sourcesReleased || !finished) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                if (failureStage != nullptr) {
                    *failureStage = !composed
                        ? "cpu-compose"
                        : (!sourcesReleased
                            ? "source-cpu-unlock"
                            : "target-cpu-unlock");
                }
                return PresentResult::FAILED;
            }
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (!backend_.bindRenderTarget(*target)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                if (failureStage != nullptr) *failureStage = "target-bind";
                return PresentResult::FAILED;
            }
            candidate.bindEndNanos = nowNanos();
            const bool directWifiFreshNames = usesFreshTextureNames();
            if (!prepareVisibleFrameTextureHeadroom(frame, directWifiFreshNames)) {
                (void)backend_.abortRenderTargetBeforePreparation(
                    target->slot, target->generation);
                if (failureStage != nullptr) *failureStage = "texture-headroom";
                return PresentResult::FAILED;
            }
            const std::uint64_t textureUseFrame = ++textureUseSerial_;
            candidate.uploadBeginNanos = nowNanos();
            for (const auto& tile : frame.tileView()) {
                if (!uploadTile(
                        env, tile, textureUseFrame, directWifiFreshNames)) {
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
        }

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
        candidate.geometryBaseSourceTop = frame.viewportSourceTop;
        if (!backend_.configurePreparedSourceCrop(
                candidate.submission,
                frame.viewportSourceTop,
                frame.viewportSourceHeight,
                candidate.geometryBaseSourceTop)) {
            (void)backend_.abortPreparedBufferTransaction(candidate.submission);
            if (failureStage != nullptr) *failureStage = "source-crop";
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
            releaseFrame(env, completed);
            fatal(env, "surface-control-prepared-apply");
            return false;
        }
        consecutivePresentFailures_ = 0;
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
        callbackDropped(env, abandoned.token, kDropReasonLifecycleRetired);
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

    /**
     * Publishes an O(1) JNI snapshot while mutex_ excludes every producer enqueue. The renderer
     * thread is the sole owner of backend_, pendingDirectFrame_, and the currently extracted work,
     * so reaching this wait boundary proves that no upload/lifecycle/presentation command is
     * active. Every producer and backend wake clears the bit before exposing newer work.
     */
    void publishPipelineQuiescenceLocked() noexcept {
        const std::int64_t now = nowNanos();
        const bool prewarmPacingReady =
            nextPrewarmUploadNanos_ <= 0 || now >= nextPrewarmUploadNanos_;
        const bool activeDirectWifiPrewarm = isActiveDirectWifiPrewarmLocked();
        // A resident snapshot can intentionally retain the unread tail beyond the bounded
        // visible/forward runway.  That queue is future scroll intent, not active GPU work.  Keep
        // quiescence false while its head is currently admissible (including a paced upload whose
        // deadline has arrived), but do not make a parked tail prevent a stable memory/cadence
        // boundary forever.  New input/viewport publication clears pipelineQuiescent_ before it
        // makes that tail admissible again.
        const bool runnablePrewarmWork = backendAttached_ &&
            !directTilePresenter_.attached() && submittedFrames_ > 0 &&
            prewarmPacingReady && canUploadNextPrewarmLocked() &&
            !gpuFenceInFlight_.load(std::memory_order_acquire) &&
            !preparePending_ && !attachPending_ && !detachPending_ &&
            (!activeDirectWifiPrewarm || !backend_.hasPendingEvent());
        bool evidenceIdle = !backend_.prepared();
        // The production BufferQueue path never prepares SurfaceControl ledgers. Avoid sampling
        // their fixed conservation arrays and fence mutex at every 16 ms idle wake; when the
        // direct backend is prepared, however, all callback/fd domains must be empty.
        if (!evidenceIdle && !backend_.hasPendingEvent()) {
            const auto snapshot = backend_.conservationSnapshot();
            evidenceIdle = snapshot.outstandingSubmissionCount == 0 &&
                snapshot.previousReleaseRecordDepth == 0 &&
                snapshot.acquireFenceRecordDepth == 0 &&
                snapshot.appOwnedAcquireFdCount == 0;
            // A completed ahead-of-viewport band intentionally owns the framework/proof dup
            // pair until it reaches the overlap edge. It is immutable parked state, not active
            // native work, and has no submitted callback/release evidence yet.
            if (!evidenceIdle && readyGpuBand_.occupied &&
                !gpuFenceInFlight_.load(std::memory_order_acquire)) {
                evidenceIdle = snapshot.outstandingSubmissionCount == 0 &&
                    snapshot.previousReleaseRecordDepth == 0 &&
                    snapshot.acquireFenceRecordDepth == 0 &&
                    snapshot.appOwnedAcquireFdCount == 2 &&
                    snapshot.pendingFenceWatchCount == 0 &&
                    snapshot.activeFenceWatchCount == 0;
            }
        }
        // A completed successor is immutable parked runway state, equivalent to the retained
        // prewarm tail above; it performs no CPU/backend work until a later product frame needs
        // it. Bitmap retirement still observes its exact native reference ledger. Only an
        // in-flight composition prevents the worker-idle boundary.
        const bool cpuBandIdle =
            !cpuComposeInFlight_.load(std::memory_order_acquire) &&
            (!readyCpuBand_.occupied || !readyCpuBand_.presentOnCompletion);
        const bool gpuBandIdle =
            !gpuFenceInFlight_.load(std::memory_order_acquire);
        const bool presentationCallbacksIdle =
            presentationCallbackRead_.load(std::memory_order_acquire) ==
            presentationCallbackWrite_.load(std::memory_order_acquire);
        const bool idle = frames_.empty() && hostFrontSubmissions_.empty() &&
            hostFrontProof_ == nullptr && !runnablePrewarmWork && cpuBandIdle && gpuBandIdle &&
            presentationCallbacksIdle && directTilePresenter_.idle() &&
            !preparePending_ && !attachPending_ && !detachPending_ &&
            !pendingDirectFrame_.occupied && !textureRetirementDebt_.pending() && evidenceIdle &&
            !stopped_.load(std::memory_order_acquire) &&
            !failed_.load(std::memory_order_acquire);
        pipelineQuiescent_.store(idle, std::memory_order_release);

        // A quiet attached renderer should converge quickly.  If exact compositor/fence evidence
        // blocks that convergence, emit the fixed-size conservation cut rather than leaving a
        // test or lifecycle barrier with only a generic `nativeWorkerIdle=false` symptom.
        const bool onlyBackendEvidenceBlocksIdle = frames_.empty() && !runnablePrewarmWork &&
            cpuBandIdle && gpuBandIdle && presentationCallbacksIdle &&
            !preparePending_ && !attachPending_ && !detachPending_ &&
            !pendingDirectFrame_.occupied && !textureRetirementDebt_.pending() &&
            !evidenceIdle && !stopped_.load(std::memory_order_acquire) &&
            !failed_.load(std::memory_order_acquire);
        if (onlyBackendEvidenceBlocksIdle) {
            if (backendEvidenceBlockedSinceNanos_ <= 0) backendEvidenceBlockedSinceNanos_ = now;
            if (now - backendEvidenceBlockedSinceNanos_ >= 2'000'000'000LL &&
                (lastBackendEvidenceBlockedLogNanos_ <= 0 ||
                 now - lastBackendEvidenceBlockedLogNanos_ >= 5'000'000'000LL)) {
                lastBackendEvidenceBlockedLogNanos_ = now;
                const auto snapshot = backend_.conservationSnapshot();
                RLOGE(
                    "quiescence blocked by backend evidence outstanding=%u commitPending=%u completePending=%u releaseDepth=%u acquireDepth=%u appFds=%u pendingWatches=%u activeWatches=%u logical=%u awaitingLatch=%d pendingEvent=%d",
                    snapshot.outstandingSubmissionCount,
                    snapshot.commitProofPendingNow,
                    snapshot.completeProofPendingNow,
                    snapshot.previousReleaseRecordDepth,
                    snapshot.acquireFenceRecordDepth,
                    snapshot.appOwnedAcquireFdCount,
                    snapshot.pendingFenceWatchCount,
                    snapshot.activeFenceWatchCount,
                    snapshot.logicalUnlatchedNow,
                    submissionAwaitingLatch_ ? 1 : 0,
                    backend_.hasPendingEvent() ? 1 : 0);
            }
        } else {
            backendEvidenceBlockedSinceNanos_ = 0;
        }

        const bool queuedVisibleWork = !frames_.empty() || pendingDirectFrame_.occupied;
        if (queuedVisibleWork) {
            if (visibleQueueBlockedSinceNanos_ <= 0) visibleQueueBlockedSinceNanos_ = now;
            if (now - visibleQueueBlockedSinceNanos_ >= 2'000'000'000LL &&
                (lastVisibleQueueBlockedLogNanos_ <= 0 ||
                 now - lastVisibleQueueBlockedLogNanos_ >= 5'000'000'000LL)) {
                lastVisibleQueueBlockedLogNanos_ = now;
                const auto snapshot = backend_.conservationSnapshot();
                RLOGE(
                    "quiescence blocked by visible queue frames=%zu prepared=%d attached=%d directCanPresent=%d directFailure=%u directEvents=%zu free=%u outstanding=%u commitPending=%u completePending=%u releaseDepth=%u acquireDepth=%u appFds=%u logical=%u awaitingLatch=%d pendingEvent=%d prewarm=%zu runnablePrewarm=%d debtNames=%zu",
                    frames_.size(), pendingDirectFrame_.occupied ? 1 : 0,
                    backendAttached_ ? 1 : 0,
                    directTilePresenter_.canPresent() ? 1 : 0,
                    directTilePresenter_.failureReason(),
                    directTilePresenter_.queuedEventCount(),
                    snapshot.freeReusableCount,
                    snapshot.outstandingSubmissionCount,
                    snapshot.commitProofPendingNow,
                    snapshot.completeProofPendingNow,
                    snapshot.previousReleaseRecordDepth,
                    snapshot.acquireFenceRecordDepth,
                    snapshot.appOwnedAcquireFdCount,
                    snapshot.logicalUnlatchedNow,
                    submissionAwaitingLatch_ ? 1 : 0,
                    backend_.hasPendingEvent() ? 1 : 0,
                    prewarmTiles_.size(), runnablePrewarmWork ? 1 : 0,
                    textureRetirementDebt_.names());
            }
        } else {
            visibleQueueBlockedSinceNanos_ = 0;
        }
    }

    void run() noexcept {
        requestRollingConsumerPriority();
        JNIEnv* env = attachEnv();
        const bool directTileOnly =
            hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire);
        const bool rendererReady = env != nullptr && (directTileOnly || initializeEgl());
        if (!rendererReady) {
            fatal(env, "cold-egl-initialize");
        } else {
            if (directTileOnly) {
                RLOGI("cold direct-tile renderer ready without EGL");
            }
            while (!stopped_.load(std::memory_order_acquire)) {
            if (!consumeHostFrontBufferProof(env, false)) {
                fatal(env, "shared-front-proof-consume");
                break;
            }
            if (!consumeDirectTileEvents(env)) break;
            if (!consumeEvents(env) && failed_.load(std::memory_order_acquire)) break;
            if (!consumeGpuBandFenceExport(env)) {
                fatal(env, "gpu-band-fence-export");
                break;
            }
            (void)consumeCpuBandPrecomposition(env);
            bool lifecycleCommandPending = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                lifecycleCommandPending = detachPending_ || attachPending_;
            }
            if (readyGpuBand_.occupied && readyGpuBand_.presentOnCompletion &&
                !lifecycleCommandPending) {
                const char* requiredFailureStage = nullptr;
                const PresentResult requiredResult = presentRequiredGpuBand(
                    env, &requiredFailureStage);
                if (requiredResult == PresentResult::FAILED) {
                    RLOGE(
                        "required GPU precomposed band failed stage=%s",
                        requiredFailureStage != nullptr
                            ? requiredFailureStage : "unknown");
                    fatal(env, "required-gpu-precomposed-band");
                    break;
                }
                if (requiredResult == PresentResult::TRANSIENT_BACKPRESSURE) {
                    std::unique_lock<std::mutex> lock(mutex_);
                    condition_.wait_for(lock, std::chrono::milliseconds(16), [&] {
                        return stopped_.load(std::memory_order_acquire) ||
                            detachPending_ || attachPending_ ||
                            backend_.hasPendingEvent();
                    });
                }
                continue;
            }
            if (readyCpuBand_.occupied && readyCpuBand_.presentOnCompletion &&
                !lifecycleCommandPending) {
                const char* requiredFailureStage = nullptr;
                const PresentResult requiredResult = presentRequiredCpuBand(
                    env, &requiredFailureStage);
                if (requiredResult == PresentResult::FAILED) {
                    RLOGE(
                        "required precomposed band failed stage=%s",
                        requiredFailureStage != nullptr
                            ? requiredFailureStage : "unknown");
                    fatal(env, "required-precomposed-band");
                    break;
                }
                if (requiredResult == PresentResult::TRANSIENT_BACKPRESSURE) {
                    std::unique_lock<std::mutex> lock(mutex_);
                    condition_.wait_for(lock, std::chrono::milliseconds(16), [&] {
                        return stopped_.load(std::memory_order_acquire) ||
                            detachPending_ || attachPending_ ||
                            backend_.hasPendingEvent();
                    });
                }
                // This exact token must precede every later mailbox entry. After either applying
                // it or waiting for its predecessor latch, restart at the event owner rather than
                // letting a successor overtake it.
                continue;
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
            std::deque<FrameCommand> lifecycleRetiredFrames;
            FrameTile prewarmTile{};
            bool hasPrewarmTile = false;
            bool activeDirectWifiPrewarm = false;
            bool settleIdleTextureRetirement = false;
            std::uint64_t prewarmPopRevision = 0;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                applyRequestedPrewarmPauseLocked();
                publishPipelineQuiescenceLocked();
                condition_.wait_for(lock, std::chrono::milliseconds(16), [&] {
                    const bool prewarmPacingReady =
                        nextPrewarmUploadNanos_ <= 0 || nowNanos() >= nextPrewarmUploadNanos_;
                    const bool queuedGeometryFrame = !frames_.empty() &&
                        matchesLastAppliedFrame(frames_.front());
                    const bool queuedPrecomposedFrame = !frames_.empty() &&
                        readyCpuBand_.occupied &&
                        composedFrameCoversViewport(
                            readyCpuBand_.composedFrame, frames_.front());
                    const bool queuedGpuBandFrame = !frames_.empty() &&
                        readyGpuBand_.occupied &&
                        composedFrameCoversViewport(
                            readyGpuBand_.composedFrame, frames_.front());
                    const bool cpuCompositionBlocksFullFrame =
                        cpuComposeInFlight_.load(std::memory_order_acquire) &&
                        !queuedGeometryFrame;
                    const bool gpuFenceBlocksFullFrame =
                        gpuFenceInFlight_.load(std::memory_order_acquire) &&
                        !queuedGeometryFrame;
                    const bool hostFrontPresentationReady =
                        windowSurface_ != EGL_NO_SURFACE &&
                        hostFrontProof_ == nullptr && hostFrontSubmissions_.empty() &&
                        nowNanos() >= hostFrontTailReleaseNotBeforeNanos_;
                    const bool directTilePresentationReady = !frames_.empty() &&
                        directTilePresenter_.attached() &&
                        (directTilePresentationActivated_ ||
                         directTileFrameEligible(frames_.front())) &&
                        directTilePresenter_.canPresent();
                    const bool canPresentQueuedFrame = !frames_.empty() && backendAttached_ &&
                        (directTilePresentationReady || hostCpuWindowAttached_ ||
                         hostFrontPresentationReady ||
                         (queuedGeometryFrame
                              ? backend_.hasGeometryTransactionCapacity()
                              : ((queuedPrecomposedFrame || queuedGpuBandFrame)
                                  ? backend_.hasDirectSubmissionCapacity()
                                  : (!cpuCompositionBlocksFullFrame &&
                                     !gpuFenceBlocksFullFrame &&
                                     backend_.hasDirectSubmissionCapacity() &&
                                     backend_.pool().hasFreeRenderTarget()))));
                    // The first physical image always owns the renderer. Decoded runway pixels
                    // may be queued before attachment, but they cannot consume the EGL lane until
                    // at least one real frame has been submitted. This keeps forward preparation
                    // from extending cold first-image latency.
                    const bool canUploadPrewarm = backendAttached_ &&
                        !directTilePresenter_.attached() && submittedFrames_ > 0 &&
                        canUploadNextPrewarmLocked() &&
                        !gpuFenceInFlight_.load(std::memory_order_acquire) &&
                        prewarmPacingReady &&
                        !preparePending_ && !attachPending_ && !detachPending_ &&
                        (frames_.empty() ||
                         (!hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
                          !isActiveDirectWifiPrewarmLocked() &&
                          matchesLastAppliedFrame(frames_.front()))) &&
                        (!isActiveDirectWifiPrewarmLocked() || !backend_.hasPendingEvent());
                    const std::int64_t retirementQuietDeadline = std::max(
                        nextPrewarmUploadNanos_,
                        nextIdleTextureRetirementNanos_.load(std::memory_order_acquire));
                    const std::int64_t retirementNow = nowNanos();
                    const bool canSettleTextureRetirement =
                        textureRetirementDebt_.pending() &&
                        retirementNow >= retirementQuietDeadline &&
                        prewarmPauseAllowsIdleTextureRetirementLocked(
                            retirementNow, retirementQuietDeadline) &&
                        !gpuFenceInFlight_.load(std::memory_order_acquire) &&
                        !preparePending_ && !attachPending_ && !detachPending_ &&
                        frames_.empty() && !submissionAwaitingLatch_ &&
                        !pendingDirectFrame_.occupied && !backend_.hasPendingEvent();
                    return stopped_.load(std::memory_order_acquire) ||
                        prewarmPauseCommandPending_.load(std::memory_order_acquire) ||
                        preparePending_ ||
                        attachPending_ || detachPending_ || canPresentQueuedFrame ||
                        canUploadPrewarm || canSettleTextureRetirement ||
                        cpuComposeCompletionReady_.load(std::memory_order_acquire) ||
                        gpuFenceCompletionReady_.load(std::memory_order_acquire) ||
                        backend_.hasPendingEvent();
                });
                pipelineQuiescent_.store(false, std::memory_order_release);
                if (stopped_.load(std::memory_order_acquire)) break;
                applyRequestedPrewarmPauseLocked();
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
                // A frame accepted before the first attach already targets that authoritative
                // size/epoch and must survive the cold lifecycle command. Retire queued frames
                // only when a real existing backend is being replaced, or on explicit detach.
                // Otherwise attach can consume the sole first-pixel token without presenting or
                // returning an outcome to Kotlin, leaving the reader permanently unrevealed.
                if (doDetach || ((doPrepare || doAttach) && backendAttached_)) {
                    lifecycleRetiredFrames.swap(frames_);
                }
                const bool prewarmPacingReady =
                    nextPrewarmUploadNanos_ <= 0 || nowNanos() >= nextPrewarmUploadNanos_;
                const bool activeDirectWifiCandidate = isActiveDirectWifiPrewarmLocked();
                const bool preferPrewarm = !doDetach && !doPrepare && !doAttach &&
                    backendAttached_ && !directTilePresenter_.attached() && submittedFrames_ > 0 &&
                    !gpuFenceInFlight_.load(std::memory_order_acquire) &&
                    canUploadNextPrewarmLocked() && prewarmPacingReady &&
                    (frames_.empty() ||
                     (!hostGpuEmulatorSurfaceProfile_.load(std::memory_order_acquire) &&
                      !activeDirectWifiCandidate && matchesLastAppliedFrame(frames_.front()))) &&
                    (!activeDirectWifiCandidate || !backend_.hasPendingEvent());
                if (!doDetach && !doPrepare && !doAttach &&
                    !preferPrewarm && !frames_.empty()) {
                    const bool geometryFrame =
                        matchesLastAppliedFrame(frames_.front());
                    const bool precomposedFrame = readyCpuBand_.occupied &&
                        composedFrameCoversViewport(
                            readyCpuBand_.composedFrame, frames_.front());
                    const bool gpuBandFrame = readyGpuBand_.occupied &&
                        composedFrameCoversViewport(
                            readyGpuBand_.composedFrame, frames_.front());
                    const bool cpuCompositionBlocksFullFrame =
                        cpuComposeInFlight_.load(std::memory_order_acquire) &&
                        !geometryFrame;
                    const bool gpuFenceBlocksFullFrame =
                        gpuFenceInFlight_.load(std::memory_order_acquire) &&
                        !geometryFrame;
                    const bool directTileFrame = directTilePresenter_.attached() &&
                        (directTilePresentationActivated_ ||
                         directTileFrameEligible(frames_.front())) &&
                        directTilePresenter_.canPresent();
                    if (backendAttached_ &&
                        (directTileFrame || hostCpuWindowAttached_ ||
                         windowSurface_ != EGL_NO_SURFACE ||
                        (geometryFrame
                             ? backend_.hasGeometryTransactionCapacity()
                             : ((precomposedFrame || gpuBandFrame)
                                  ? backend_.hasDirectSubmissionCapacity()
                                  : (!cpuCompositionBlocksFullFrame &&
                                     !gpuFenceBlocksFullFrame &&
                                     backend_.hasDirectSubmissionCapacity() &&
                                     backend_.pool().hasFreeRenderTarget()))))) {
                        frame = std::move(frames_.front());
                        frames_.pop_front();
                        hasFrame = true;
                    }
                }
                if (preferPrewarm && !hasFrame) {
                    prewarmTile = prewarmTiles_.front();
                    prewarmTiles_.front().bitmap = nullptr;
                    prewarmTiles_.pop_front();
                    prewarmPopRevision = ++prewarmQueueRevision_;
                    hasPrewarmTile = true;
                    activeDirectWifiPrewarm = activeDirectWifiCandidate;
                }
                const std::int64_t retirementQuietDeadline = std::max(
                    nextPrewarmUploadNanos_,
                    nextIdleTextureRetirementNanos_.load(std::memory_order_acquire));
                const std::int64_t retirementNow = nowNanos();
                settleIdleTextureRetirement = !doDetach && !doPrepare && !doAttach &&
                    !hasFrame && !hasPrewarmTile && frames_.empty() &&
                    !gpuFenceInFlight_.load(std::memory_order_acquire) &&
                    !submissionAwaitingLatch_ &&
                    prewarmPauseAllowsIdleTextureRetirementLocked(
                        retirementNow, retirementQuietDeadline) &&
                    !pendingDirectFrame_.occupied && !backend_.hasPendingEvent() &&
                    textureRetirementDebt_.pending() &&
                    retirementNow >= retirementQuietDeadline;
            }
            for (auto& retired : lifecycleRetiredFrames) {
                callbackDropped(env, retired.token, kDropReasonLifecycleRetired);
                releaseFrame(env, retired);
            }
            if (doDetach || doPrepare || doAttach) {
                retireGpuBandPrecomposition(env);
                retireCpuBandPrecomposition(env);
            }
            if (doDetach && !detachBackend(env)) {
                fatal(env, "surface-detach-drain");
                break;
            }
            // attach carries the authoritative target size and retires any still-live backend.
            // A delayed attach may coalesce the preceding detach before this turn is extracted;
            // never apply its redundant prepare against the old, differently-sized attachment.
            if (doPrepare && !doAttach && !prepareBackend(prepareCommand)) {
                fatal(env, "surface-target-prepare");
                break;
            }
            if (doAttach && !attachBackend(env, attachCommand)) {
                fatal(env, "surface-attach");
                break;
            }
            {
                std::lock_guard<std::mutex> lock(mutex_);
                // A newer lifecycle command can arrive while attachBackend()/detachBackend() is
                // outside mutex_. Do not let completion of the older command reopen admission in
                // front of that newer fence. Its enqueue already set surfaceAttached_ false.
                surfaceAttached_ = backendAttached_ &&
                    !preparePending_ && !attachPending_ && !detachPending_ &&
                    !stopped_.load(std::memory_order_acquire) &&
                    !failed_.load(std::memory_order_acquire);
            }
            if (settleIdleTextureRetirement) {
                if (!settleTextureRetirementBeforeVisibleUpload()) {
                    if (consecutiveTextureRetirementBarrierFailures_ >= 2) {
                        fatal(env, "texture-retirement-barrier");
                        break;
                    }
                    delayIdleTextureRetirementUntil(
                        nowNanos() + kTextureRetirementErrorRetryNanos);
                    continue;
                }
                delayIdleTextureRetirementUntil(
                    nowNanos() + kTextureRetirementIdleQuietNanos);
                continue;
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
                        if (frames_.size() < kMaxQueuedFrames && backendAttached_ &&
                            !stopped_.load(std::memory_order_acquire)) {
                            // This command was just removed from the bounded FIFO, so under
                            // ordinary admission there is always room to restore it at the head.
                            // Later physical frames remain behind it and cannot force a proof
                            // retirement merely because a predecessor latch is pending.
                            frames_.push_front(std::move(frame));
                            retry = true;
                        } else {
                            superseded = std::move(frame);
                        }
                    }
                    if (!retry) {
                        callbackDropped(env, superseded.token, kDropReasonMailboxSuperseded);
                        releaseFrame(env, superseded);
                        ++supersededFrames_;
                    } else if (directTileMirrorPending_) {
                        // The dedicated publisher cannot signal this renderer's private condition.
                        // Avoid a hot retry loop while gfxstream finishes the next visible tile.
                        std::unique_lock<std::mutex> lock(mutex_);
                        condition_.wait_for(lock, std::chrono::milliseconds(8), [&] {
                            return stopped_.load(std::memory_order_acquire) ||
                                detachPending_ || attachPending_ || backend_.hasPendingEvent();
                        });
                    }
                } else if (result != PresentResult::PREPARED_WAITING) {
                    if (result == PresentResult::FAILED) {
                        logPresentFailure(failureStage, frame);
                        ++consecutivePresentFailures_;
                        if (consecutivePresentFailures_ == 1) {
                            callbackDropped(env, frame.token, kDropReasonPresentFailed);
                        }
                    } else if (result == PresentResult::APPLIED) {
                        consecutivePresentFailures_ = 0;
                    }
                    releaseFrame(env, frame);
                    if (result == PresentResult::FAILED &&
                        consecutivePresentFailures_ >= 2) {
                        // One exact retry is enough to distinguish a transient upload/queue miss
                        // from a broken attachment. The terminal token is owned by the backend
                        // reset; do not also redrive it through the frame-drop callback.
                        fatal(env, "surface-present-retry-exhausted");
                        break;
                    }
                }
            } else if (hasPrewarmTile) {
                const bool profileStillOwnsActiveUpload = !activeDirectWifiPrewarm ||
                    directWifiTextureProfile_.load(std::memory_order_acquire);
                const std::int64_t prewarmBeginNanos = nowNanos();
                bool issuedUpload = false;
                if (profileStillOwnsActiveUpload) {
                    const PrewarmUploadResult uploadResult =
                        uploadPrewarmTile(env, prewarmTile);
                    issuedUpload = uploadResult == PrewarmUploadResult::UPLOADED;
                    if (uploadResult == PrewarmUploadResult::DEFERRED_FOR_RETIREMENT) {
                        restoreDeferredPrewarmTile(env, prewarmTile, prewarmPopRevision);
                    }
                } else {
                    // A direct-Wi-Fi capability can disappear after admission but before the GL
                    // owner consumes the tile. Preserve that sealed snapshot entry for the normal
                    // idle lane; dropping it here would make the same epoch impossible to prewarm
                    // again after a direct-to-mobile/SNI transition.
                    restoreDeferredPrewarmTile(env, prewarmTile, prewarmPopRevision);
                }
                const std::int64_t prewarmEndNanos = nowNanos();
                // Only an actual GL upload consumes a display-period slot. Resident identities at
                // the head of a coalesced full-scene snapshot are CPU-only queue maintenance; a
                // period of pacing for each no-op previously kept the queue permanently behind the
                // forward viewport and forced every new page onto its first visible frame.
                const std::int64_t nextPrewarmUploadNanos = issuedUpload
                    ? nowNanos() + std::max<std::int64_t>(
                        1'000'000,
                        (refreshPeriodNanos_ > 0
                            ? refreshPeriodNanos_
                            : kDefaultRefreshPeriodNanos) *
                            (activeDirectWifiPrewarm
                                ? kActiveDirectWifiPrewarmPeriods
                                : 1))
                    : 0;
                {
                    // setPrewarmPaused() also changes this deadline from the UI/JNI thread.
                    // Keep every read/write in the same mutex domain; an unguarded owner write
                    // here is a C++ data race. Merge rather than overwrite: the UI may have
                    // installed the longer post-input quiet deadline while this upload was in GL.
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (issuedUpload) {
                        nextPrewarmUploadNanos_ = std::max(
                            nextPrewarmUploadNanos_, nextPrewarmUploadNanos);
                    }
                }
                if (issuedUpload && activeDirectWifiPrewarm) {
                    const std::int64_t refresh = refreshPeriodNanos_ > 0
                        ? refreshPeriodNanos_
                        : kDefaultRefreshPeriodNanos;
                    if (prewarmEndNanos - prewarmBeginNanos > refresh) {
                        // A slow full-frame host upload is not allowed to repeat in the same
                        // gesture. Fast uploads retain the measured paced drip that keeps later
                        // first-visible frames below the formal jank threshold.
                        std::lock_guard<std::mutex> lock(mutex_);
                        activeDirectWifiPrewarmSuppressed_ = true;
                        RLOGI(
                            "active texture prewarm suppressed after slow upload elapsedUs=%lld",
                            static_cast<long long>(
                                (prewarmEndNanos - prewarmBeginNanos) / 1'000));
                    }
                }
            }
            }
        }

        std::deque<FrameCommand> remaining;
        std::deque<FrameTile> remainingPrewarm;
        ProducerCpuScene remainingProducerScene;
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
            pipelineQuiescent_.store(false, std::memory_order_release);
        }
        {
            std::lock_guard<std::mutex> sceneLock(producerSceneMutex_);
            remainingProducerScene = std::move(producerCpuScene_);
            producerCpuScene_ = {};
        }
        for (auto& frame : remaining) {
            callbackDropped(env, frame.token, kDropReasonLifecycleRetired);
            releaseFrame(env, frame);
        }
        releaseProducerCpuScene(env, remainingProducerScene);
        for (auto& tile : remainingPrewarm) releaseTile(env, tile);
        if (pending.window != nullptr) ANativeWindow_release(pending.window);
        releaseProvidedAttachSurfaces(pending);
        if (!abortPreparedDirectFrame(env, "renderer-stop")) {
            RLOGE("terminal prepared successor abort failed");
        }
        retireGpuBandPrecomposition(env);
        retireCpuBandPrecomposition(env);
        bool terminalCleanupComplete = true;
        if (backendAttached_ && !detachBackend(env)) {
            RLOGE("terminal backend drain failed");
            terminalCleanupComplete = false;
        }
        if (backend_.prepared() && !backend_.destroy()) {
            RLOGE("terminal prepared backend destroy failed");
            terminalCleanupComplete = false;
        }
        terminalCleanupComplete_.store(
            terminalCleanupComplete && !backendAttached_ && !backend_.prepared(),
            std::memory_order_release);
        destroyEgl();
    }

    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID latchedMethod_ = nullptr;
    jmethodID geometryMethod_ = nullptr;
    jmethodID bandActivatedMethod_ = nullptr;
    jmethodID precomposedReadyMethod_ = nullptr;
    jmethodID droppedMethod_ = nullptr;
    jmethodID fatalMethod_ = nullptr;
    const std::int64_t creationGeneration_ = 0;
    static constexpr std::size_t kWindowPresentationCallbackCapacity = 4096;
    std::thread thread_;
    std::thread presentationCallbackThread_;
    std::thread gpuFenceThread_;
    std::thread cpuComposeThread_;
    /**
     * The EGL owner is the sole producer and ReaderNativeProof is the sole consumer. Slots are
     * published by [presentationCallbackWrite_] and returned only after the corresponding Java
     * proof callback completes, so neither side allocates or owns a cross-thread object lifetime.
     */
    std::array<WindowPresentationCallback, kWindowPresentationCallbackCapacity>
        presentationCallbacks_{};
    std::atomic<std::uint64_t> presentationCallbackWrite_{0};
    std::atomic<std::uint64_t> presentationCallbackRead_{0};
    std::atomic<bool> presentationProducerStopped_{false};
    std::atomic<bool> presentationCallbackFailed_{false};
    std::uint64_t slowWindowPresentationCallbacks_ = 0;
    std::mutex presentationCallbackWaitMutex_;
    std::condition_variable presentationCallbackCondition_;
    std::condition_variable presentationCallbackSpaceCondition_;
    std::mutex mutex_;
    /** Protects only the issued-EGLSync worker handoff, never SurfaceControl/pool state. */
    std::mutex gpuFenceMutex_;
    std::condition_variable gpuFenceCondition_;
    GpuBandFenceJob gpuFenceJob_{};
    ReadyGpuBand readyGpuBand_{};
    std::atomic<bool> gpuFenceInFlight_{false};
    std::atomic<bool> gpuFenceCompletionReady_{false};
    std::uint64_t completedGpuBandFenceExports_ = 0;
    /** Worker handoff only; never held while backend/pool ownership state is mutated. */
    std::mutex cpuComposeMutex_;
    std::condition_variable cpuComposeCondition_;
    CpuBandPrecomposeJob cpuComposeJob_{};
    ReadyCpuBand readyCpuBand_{};
    std::atomic<bool> cpuComposeInFlight_{false};
    std::atomic<bool> cpuComposeCompletionReady_{false};
    std::uint64_t completedCpuBandPrecompositions_ = 0;
    /** Never held across GL, SurfaceControl, callbacks, or the renderer queue mutex. */
    std::mutex bitmapReferenceMutex_;
    ntk::rolling::BitmapReferenceLedger<> bitmapReferenceLedger_{};
    /** Exact producer-scene handoff only; never held across the renderer queue or backend. */
    std::mutex producerSceneMutex_;
    ProducerCpuScene producerCpuScene_{};
    std::condition_variable condition_;
    std::deque<FrameCommand> frames_;
    std::deque<FrameTile> prewarmTiles_;
    std::int64_t queuedPrewarmEpoch_ = 0;
    std::int64_t sealedFullScenePrewarmEpoch_ = 0;
    std::uint64_t prewarmQueueRevision_ = 0;
    std::atomic<std::int64_t> fullSceneTextureBudgetEpoch_{0};
    std::atomic<std::uint64_t> fullSceneTextureBudgetBytes_{0};
    PrepareCommand pendingPrepare_{};
    bool preparePending_ = false;
    AttachCommand pendingAttach_{};
    bool attachPending_ = false;
    bool detachPending_ = false;
    std::uint64_t detachEpoch_ = 0;
    /** Highest exact attachment identity admitted by attach(); guarded by mutex_. */
    std::uint64_t latestAcceptedAttachEpoch_ = 0;
    bool surfaceAttached_ = false;
    std::atomic<bool> stopped_{false};
    std::atomic<bool> destroyed_{false};
    std::atomic<bool> failed_{false};
    std::atomic<bool> pipelineQuiescent_{false};
    // False keeps a failed renderer alive in the process quarantine: SurfaceControl callback
    // cookies and the fence-reactor wake context are raw pointers into this object and may not be
    // destroyed merely to recover the Java surface. Normal lifecycle drain always sets this true.
    std::atomic<bool> terminalCleanupComplete_{false};

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLContext gpuCompositionContext_ = EGL_NO_CONTEXT;
    EGLSurface gpuCompositionPbuffer_ = EGL_NO_SURFACE;
    GLuint gpuCompositionVao_ = 0;
    std::array<GLuint,
        ntk::present::HardwareBufferRenderTargetPool::kSlotCount>
        gpuCompositionFramebuffers_{};
    std::array<std::uint64_t,
        ntk::present::HardwareBufferRenderTargetPool::kSlotCount>
        gpuCompositionFramebufferEpochs_{};
    std::array<GLuint,
        ntk::present::HardwareBufferRenderTargetPool::kSlotCount>
        gpuCompositionAttachedRenderbuffers_{};
    EGLSurface windowSurface_ = EGL_NO_SURFACE;
    ANativeWindow* nativeWindow_ = nullptr;
    bool mutableRenderBufferSupported_ = false;
    bool hostFrontBufferMode_ = false;
    bool hostFrontBufferPrimed_ = false;
    std::int64_t hostFrontTailReleaseNotBeforeNanos_ = 0;
    std::deque<HostFrontSubmission> hostFrontSubmissions_;
    GLsync hostFrontProof_ = nullptr;
    std::size_t hostFrontProofSubmissionCount_ = 0;
    std::int64_t hostFrontProofIssuedNanos_ = 0;
    std::uint64_t completedHostFrontProofs_ = 0;
    bool hostCpuWindowAttached_ = false;
    int hostCpuWindowBufferWidth_ = 0;
    int hostCpuWindowBufferHeight_ = 0;
    bool javaFrameSyncedGeometry_ = false;
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLint yBoundsUniform_ = -1;
    GLint textureScaleUniform_ = -1;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture_ = nullptr;
    std::unordered_map<TileKey, TextureTile, TileKeyHash> textures_;
    std::vector<TextureTile> pooledTextures_;
    std::vector<GLuint> spareTextureNames_;
    std::unique_ptr<std::uint8_t[]> hostUploadScratch_;
    std::size_t hostUploadScratchCapacity_ = 0;
    std::uint64_t residentTextureBytes_ = 0;
    std::uint64_t pooledTextureBytes_ = 0;
    std::uint64_t evictedTextures_ = 0;
    std::uint64_t lastEvictionLogCount_ = 0;
    std::uint64_t reusedPooledTextures_ = 0;
    std::uint64_t textureNameReserveRefills_ = 0;
    std::uint64_t hostPackedUploads_ = 0;
    std::uint64_t importedHardwareBufferTextures_ = 0;
    std::uint64_t textureUseSerial_ = 0;
    ntk::rolling::TextureRetirementDebt textureRetirementDebt_{};
    GLsync textureRetirementFence_ = nullptr;
    bool textureRetirementFenceDirty_ = false;
    std::uint64_t completedTextureRetirementFences_ = 0;
    std::uint64_t failedTextureRetirementFenceArms_ = 0;
    std::uint64_t failedTextureRetirementFencePolls_ = 0;
    std::uint64_t completedTextureRetirementBarriers_ = 0;
    std::uint64_t failedTextureRetirementBarriers_ = 0;
    std::uint32_t consecutiveTextureRetirementBarrierFailures_ = 0;
    std::int64_t lastWindowPresentEndNanos_ = 0;
    std::uint64_t lastWindowPresentToken_ = 0;
    std::int64_t lastPresentedStructureEpoch_ = 0;
    int lastPresentedMinPage_ = -1;
    int lastPresentedMaxPage_ = -1;
    std::atomic<int> lastPresentedMaxPageSnapshot_{-1};
    std::unordered_set<TileKey, TileKeyHash> lastPresentedTextureKeys_;
    int lastAppliedFrameWidth_ = 0;
    int lastAppliedFrameHeight_ = 0;
    std::int64_t lastAppliedFrameEpoch_ = 0;
    int lastAppliedGeometryBaseSourceTop_ = 0;
    int lastAppliedViewportSourceHeight_ = 0;
    int lastJavaGeometrySourceTop_ = 0;
    std::uint64_t lastAppliedProducerSceneId_ = 0;
    std::vector<AppliedFrameTileSignature> lastAppliedFrameTiles_;
    std::uint64_t uploadedPrewarmTiles_ = 0;
    std::uint64_t failedPrewarmTiles_ = 0;
    std::uint64_t discardedPrewarmTiles_ = 0;
    std::uint64_t skippedResidentPrewarmTiles_ = 0;
    std::uint64_t skippedPrewarmHeadroomTiles_ = 0;
    std::uint64_t migratedAppendOnlyTextures_ = 0;
    std::int64_t nextPrewarmUploadNanos_ = 0;
    std::atomic<std::int64_t> nextIdleTextureRetirementNanos_{0};
    std::atomic<bool> requestedPrewarmPaused_{false};
    std::atomic<bool> prewarmPauseCommandPending_{false};
    bool activeDirectWifiPrewarmSuppressed_ = false;
    int directWifiImmediateResumeMaxPage_ = -1;
    std::int64_t directWifiFullPrewarmResumeNanos_ = 0;
    std::atomic<bool> directWifiTextureProfile_{false};
    std::atomic<bool> hostGpuEmulatorSurfaceProfile_{false};
    std::atomic<bool> cpuExactStorageProfile_{false};

    ntk::present::DirectTileSurfacePresenter directTilePresenter_{};
    bool directTileMirrorPending_ = false;
    std::vector<ntk::present::DirectTileLayerInput> directTileInputs_;
    bool directTilePresentationActivated_ = false;
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
    std::int64_t lastGeometryDesiredPresentNanos_ = 0;
    std::uint64_t frameSequence_ = 0;
    std::atomic<std::uint64_t> submittedFrames_{0};
    bool submissionAwaitingLatch_ = false;
    std::uint64_t presentationFailures_ = 0;
    std::uint32_t consecutivePresentFailures_ = 0;
    std::int64_t backendEvidenceBlockedSinceNanos_ = 0;
    std::int64_t lastBackendEvidenceBlockedLogNanos_ = 0;
    std::int64_t visibleQueueBlockedSinceNanos_ = 0;
    std::int64_t lastVisibleQueueBlockedLogNanos_ = 0;
    int nativeSwapInterval_ = 0;
    std::int64_t lastSlowPresentLogNanos_ = 0;
    std::atomic<std::uint64_t> acceptedFrames_{0};
    std::atomic<std::uint64_t> supersededFrames_{0};
    std::atomic<std::uint64_t> droppedFrames_{0};
    std::atomic<std::uint64_t> latchedFrames_{0};
};

std::mutex gRollingRendererRegistryMutex;
std::unordered_map<RollingRenderer*, std::shared_ptr<RollingRenderer>> gRollingRendererRegistry;
// Deliberately process-lifetime storage. A terminal invariant failure with compositor evidence
// still in flight must retain its callback target instead of invoking std::thread::~thread on a
// joinable backend reactor or freeing SurfaceControl cookies that the framework can still call.
// Successful renderer lifecycles never enter this quarantine.
auto* gRollingRendererQuarantine =
    new std::vector<std::shared_ptr<RollingRenderer>>();

RollingRenderer* rawRenderer(jlong handle) noexcept {
    return reinterpret_cast<RollingRenderer*>(static_cast<std::uintptr_t>(handle));
}

std::shared_ptr<RollingRenderer> renderer(jlong handle) noexcept {
    RollingRenderer* value = rawRenderer(handle);
    if (value == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(gRollingRendererRegistryMutex);
    const auto found = gRollingRendererRegistry.find(value);
    return found == gRollingRendererRegistry.end() ? nullptr : found->second;
}

bool registerRenderer(const std::shared_ptr<RollingRenderer>& value) {
    if (!value) return false;
    std::lock_guard<std::mutex> lock(gRollingRendererRegistryMutex);
    return gRollingRendererRegistry.emplace(value.get(), value).second;
}

std::shared_ptr<RollingRenderer> takeRendererForDestroy(jlong handle) noexcept {
    RollingRenderer* value = rawRenderer(handle);
    if (value == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(gRollingRendererRegistryMutex);
    const auto found = gRollingRendererRegistry.find(value);
    if (found == gRollingRendererRegistry.end()) return nullptr;
    auto owned = found->second;
    gRollingRendererRegistry.erase(found);
    return owned;
}

void quarantineRenderer(std::shared_ptr<RollingRenderer> value) noexcept {
    if (!value || gRollingRendererQuarantine == nullptr) return;
    std::lock_guard<std::mutex> lock(gRollingRendererRegistryMutex);
    gRollingRendererQuarantine->push_back(std::move(value));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCreate(
        JNIEnv* env, jobject, jobject callback, jlong creationGeneration,
        jboolean directWifiTextureProfile, jboolean hostGpuEmulator) {
    auto value = std::shared_ptr<RollingRenderer>(new (std::nothrow) RollingRenderer(
        env,
        callback,
        static_cast<std::int64_t>(creationGeneration),
        directWifiTextureProfile == JNI_TRUE,
        hostGpuEmulator == JNI_TRUE));
    if (!value || !value->valid() || !registerRenderer(value)) {
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(value.get()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeAllocateExactHardwareBuffer(
        JNIEnv* env, jobject, jint width, jint height) {
    if (env == nullptr || width <= 0 || height <= 0) return 0;
    const std::size_t storageWidth = static_cast<std::size_t>(width);
    const std::size_t storageHeight = static_cast<std::size_t>(height);
    if (storageWidth > SIZE_MAX / 4U) return 0;
    const std::size_t strideBytes = storageWidth * 4U;
    if (storageHeight > SIZE_MAX / strideBytes) return 0;
    const std::size_t allocationBytes = strideBytes * storageHeight;
    void* pixels = nullptr;
    if (posix_memalign(&pixels, 64U, allocationBytes) != 0 || pixels == nullptr) return 0;
    AHardwareBuffer_Desc descriptor{};
    descriptor.width = static_cast<std::uint32_t>(width);
    descriptor.height = static_cast<std::uint32_t>(height);
    descriptor.layers = 1;
    descriptor.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    descriptor.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    AHardwareBuffer* hardwareBuffer = nullptr;
    const auto& hardwareSymbols = exactHardwareBufferSymbols();
    if (hardwareSymbols.validForAllocation() &&
        (hardwareSymbols.isSupported == nullptr ||
         hardwareSymbols.isSupported(&descriptor) == 1)) {
        (void)hardwareSymbols.allocate(&descriptor, &hardwareBuffer);
    }
    auto* storage = new (std::nothrow) ExactCpuTileStorage{
        kExactCpuTileMagic,
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        0U,
        0U,
        0U,
        0U,
        strideBytes,
        allocationBytes,
        static_cast<std::uint8_t*>(pixels),
        hardwareBuffer,
    };
    if (storage == nullptr) {
        if (hardwareBuffer != nullptr && hardwareSymbols.release != nullptr) {
            hardwareSymbols.release(hardwareBuffer);
        }
        std::free(pixels);
        return 0;
    }
    // This app-owned storage is copied into a GL texture only by the paced native prewarm lane.
    // It never enters ART's NativeAllocationRegistry and never invokes emulator gralloc unlock.
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(storage));
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeReleaseExactHardwareBuffer(
        JNIEnv*, jobject, jlong nativeHandle) {
    auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
        static_cast<std::uintptr_t>(nativeHandle));
    if (!validExactCpuTile(storage)) return;
    storage->magic = 0;
    closeExactHardwareWriteFence(storage);
    if (storage->hardwareBuffer != nullptr) {
        const auto& hardwareSymbols = exactHardwareBufferSymbols();
        if (hardwareSymbols.release != nullptr) {
            hardwareSymbols.release(storage->hardwareBuffer);
        }
        storage->hardwareBuffer = nullptr;
    }
    std::free(storage->pixels);
    storage->pixels = nullptr;
    delete storage;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativePublishExactHardwareTile(
        JNIEnv*, jobject, jlong nativeHandle) {
    auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
        static_cast<std::uintptr_t>(nativeHandle));
    if (!validExactCpuTile(storage) || storage->hardwareBuffer == nullptr ||
        storage->contentWidth == 0U || storage->contentHeight == 0U) {
        return JNI_FALSE;
    }
    return refreshExactCpuTileHardwareMirror(storage) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactSingleJpegFileToHardwareTile(
        JNIEnv* env, jobject, jstring encodedPath, jlong nativeHandle,
        jint sourceWidth, jint sourceHeight) {
    if (env == nullptr || encodedPath == nullptr || nativeHandle == 0 ||
        sourceWidth <= 0 || sourceHeight <= 0) {
        return JNI_FALSE;
    }
    auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
        static_cast<std::uintptr_t>(nativeHandle));
    if (!ensureExactCpuTilePixels(storage) ||
        storage->width < static_cast<std::uint32_t>(sourceWidth) ||
        storage->height < static_cast<std::uint32_t>(sourceHeight)) {
        return JNI_FALSE;
    }
    storage->hardwareMirrorReady.store(false, std::memory_order_release);
    const char* rawPath = env->GetStringUTFChars(encodedPath, nullptr);
    if (rawPath == nullptr) return JNI_FALSE;
    const int fd = open(rawPath, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(encodedPath, rawPath);
    if (fd < 0) return JNI_FALSE;

    const bool valid = hasJpegSignature(fd) &&
        decodeExactJpegFileToStridedTile(
            fd,
            sourceWidth,
            sourceHeight,
            storage->pixels,
            storage->strideBytes,
            storage->allocationBytes);
    close(fd);
    if (!valid) return JNI_FALSE;

    storage->contentWidth = static_cast<std::uint32_t>(sourceWidth);
    storage->contentHeight = static_cast<std::uint32_t>(sourceHeight);
    storage->logicalWidth = static_cast<std::uint32_t>(sourceWidth);
    storage->logicalHeight = static_cast<std::uint32_t>(sourceHeight);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactFileToHardwareTiles(
        JNIEnv* env, jobject, jstring encodedPath, jlongArray nativeHandles,
        jint sourceWidth, jint sourceHeight, jint sourceCropLeft, jint sourceRegionWidth,
        jint tileCapacityHeight,
        jint displayWidth) {
    if (env == nullptr || encodedPath == nullptr || nativeHandles == nullptr ||
        sourceWidth <= 0 || sourceHeight <= 0 || tileCapacityHeight <= 0 ||
        sourceCropLeft < 0 || sourceRegionWidth <= 0 ||
        static_cast<std::int64_t>(sourceCropLeft) + sourceRegionWidth > sourceWidth ||
        displayWidth <= 0 || displayWidth > sourceRegionWidth) {
        return JNI_FALSE;
    }
    using CreateDecoder = int (*)(int, AImageDecoder**);
    using GetHeader = const AImageDecoderHeaderInfo* (*)(const AImageDecoder*);
    using GetDimension = std::int32_t (*)(const AImageDecoderHeaderInfo*);
    using SetFormat = int (*)(AImageDecoder*, std::int32_t);
    using SetDataSpace = int (*)(AImageDecoder*, std::int32_t);
    using SetTargetSize = int (*)(AImageDecoder*, std::int32_t, std::int32_t);
    using SetCrop = int (*)(AImageDecoder*, ARect);
    using DecodeImage = int (*)(AImageDecoder*, void*, size_t, size_t);
    using DeleteDecoder = void (*)(AImageDecoder*);
    struct DecodeSymbols {
        void* graphics = nullptr;
        CreateDecoder create = nullptr;
        GetHeader getHeader = nullptr;
        GetDimension getWidth = nullptr;
        GetDimension getHeight = nullptr;
        SetFormat setFormat = nullptr;
        SetDataSpace setDataSpace = nullptr;
        SetTargetSize setTargetSize = nullptr;
        SetCrop setCrop = nullptr;
        DecodeImage decode = nullptr;
        DeleteDecoder destroy = nullptr;

        bool valid() const noexcept {
            return create != nullptr && getHeader != nullptr && getWidth != nullptr &&
                getHeight != nullptr && setFormat != nullptr && setDataSpace != nullptr &&
                setTargetSize != nullptr && setCrop != nullptr && decode != nullptr &&
                destroy != nullptr;
        }
    };
    static const DecodeSymbols symbols = [] {
        DecodeSymbols value{};
        value.graphics = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
        if (value.graphics != nullptr) {
            value.create = reinterpret_cast<CreateDecoder>(
                dlsym(value.graphics, "AImageDecoder_createFromFd"));
            value.getHeader = reinterpret_cast<GetHeader>(
                dlsym(value.graphics, "AImageDecoder_getHeaderInfo"));
            value.getWidth = reinterpret_cast<GetDimension>(
                dlsym(value.graphics, "AImageDecoderHeaderInfo_getWidth"));
            value.getHeight = reinterpret_cast<GetDimension>(
                dlsym(value.graphics, "AImageDecoderHeaderInfo_getHeight"));
            value.setFormat = reinterpret_cast<SetFormat>(
                dlsym(value.graphics, "AImageDecoder_setAndroidBitmapFormat"));
            value.setDataSpace = reinterpret_cast<SetDataSpace>(
                dlsym(value.graphics, "AImageDecoder_setDataSpace"));
            value.setTargetSize = reinterpret_cast<SetTargetSize>(
                dlsym(value.graphics, "AImageDecoder_setTargetSize"));
            value.setCrop = reinterpret_cast<SetCrop>(
                dlsym(value.graphics, "AImageDecoder_setCrop"));
            value.decode = reinterpret_cast<DecodeImage>(
                dlsym(value.graphics, "AImageDecoder_decodeImage"));
            value.destroy = reinterpret_cast<DeleteDecoder>(
                dlsym(value.graphics, "AImageDecoder_delete"));
        }
        return value;
    }();
    if (!symbols.valid()) return JNI_FALSE;

    const jsize bufferCount = env->GetArrayLength(nativeHandles);
    const std::int64_t expectedCount =
        (static_cast<std::int64_t>(sourceHeight) + tileCapacityHeight - 1) /
        tileCapacityHeight;
    if (expectedCount <= 0 || expectedCount > INT32_MAX ||
        bufferCount != static_cast<jsize>(expectedCount)) {
        return JNI_FALSE;
    }
    std::vector<jlong> handles(static_cast<std::size_t>(bufferCount));
    env->GetLongArrayRegion(nativeHandles, 0, bufferCount, handles.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    for (const jlong handle : handles) {
        auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
            static_cast<std::uintptr_t>(handle));
        if (!ensureExactCpuTilePixels(storage)) return JNI_FALSE;
        storage->hardwareMirrorReady.store(false, std::memory_order_release);
    }

    const char* rawPath = env->GetStringUTFChars(encodedPath, nullptr);
    if (rawPath == nullptr) return JNI_FALSE;
    const std::string encodedFilePath(rawPath);
    const int fd = open(encodedFilePath.c_str(), O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(encodedPath, rawPath);
    if (fd < 0) return JNI_FALSE;

    const bool directPng = hasPngSignature(fd);
    const bool directJpeg = hasJpegSignature(fd);
    AImageDecoder* decoder = nullptr;
    bool valid = true;
    if (!directPng && !directJpeg) {
        logUnhandledExactFileSignature(fd);
        const int result = symbols.create(fd, &decoder);
        if (result != ANDROID_IMAGE_DECODER_SUCCESS || decoder == nullptr) {
            close(fd);
            return JNI_FALSE;
        }
        const AImageDecoderHeaderInfo* header = symbols.getHeader(decoder);
        valid = header != nullptr &&
            symbols.getWidth(header) == sourceWidth &&
            symbols.getHeight(header) == sourceHeight &&
            symbols.setFormat(
                decoder, ANDROID_BITMAP_FORMAT_RGBA_8888) == ANDROID_IMAGE_DECODER_SUCCESS &&
            symbols.setDataSpace(
                decoder, ADATASPACE_SRGB) == ANDROID_IMAGE_DECODER_SUCCESS;
    }

    // AImageDecoder can spend 30-250 ms inside decodeImage for compressed PNG/GIF input. Doing
    // that work while an emulator HardwareBuffer is CPU-locked serializes gfxstream sampling and
    // stalls an unrelated visible frame. Decode into one process-wide reusable native scratch,
    // then hold the HardwareBuffer lock only for the final row copy. When the complete image fits
    // in the bounded scratch, decompress it once and distribute its rows to every tile. Decoding
    // the same 3288-wide PNG independently for two 4096x2048 tiles doubled both CPU contention
    // and the interval during which a newly allocated gfxstream buffer was being committed.
    // Oversize images retain the bounded per-tile fallback. The mutex also prevents one scratch
    // allocation per concurrent page and keeps this path outside ART's allocation budget.
    const std::size_t scratchStrideBytes = static_cast<std::size_t>(sourceWidth) * 4U;
    const std::size_t maximumSpan = static_cast<std::size_t>(
        std::min(tileCapacityHeight, sourceHeight));
    const bool completeImageFitsScratch = scratchStrideBytes > 0U &&
        static_cast<std::size_t>(sourceHeight) > 0U &&
        scratchStrideBytes <= kMaxExactDecodeScratchBytes /
            static_cast<std::size_t>(sourceHeight);
    const bool tileScratchSizeValid = scratchStrideBytes > 0U && maximumSpan > 0U &&
        scratchStrideBytes <= kMaxExactDecodeScratchBytes / maximumSpan;
    const std::size_t scratchRows = completeImageFitsScratch
        ? static_cast<std::size_t>(sourceHeight)
        : maximumSpan;
    static std::mutex exactDecodeScratchMutex;
    static std::uint8_t* exactDecodeScratch = nullptr;
    static std::size_t exactDecodeScratchCapacity = 0U;
    static std::uint8_t* exactJpegScaleScratch = nullptr;
    static std::size_t exactJpegScaleScratchCapacity = 0U;
    std::unique_lock<std::mutex> scratchLock(exactDecodeScratchMutex);

    // Let Android's decoder sample compressed pixels directly to the display width before they
    // enter the reusable scratch. The legacy path below decoded at source resolution and then ran
    // a second full-image bilinear pass; even after removing redundant coordinate divisions that
    // pass still occupied 25-30 ms per ordinary manga page on the emulator. A duplicated fd keeps
    // this attempt independent: any unsupported format/scale falls through to the existing exact
    // decoder and region fallback without changing its stream position or storage contract.
    std::vector<std::int32_t> scaledSourceSpans(static_cast<std::size_t>(bufferCount));
    std::vector<std::int32_t> scaledDisplaySpans(static_cast<std::size_t>(bufferCount));
    std::int64_t scaledOutputRows = 0;
    std::int32_t remainingSourceRows = sourceHeight;
    // Target-size decoders scale the complete encoded image. A deterministic auto-cut half must
    // first select its exact source rectangle, so it uses the full-decode/cropped-copy path below.
    bool scaledDecodeValid = sourceCropLeft == 0 && sourceRegionWidth == sourceWidth;
    for (jsize index = 0; index < bufferCount; ++index) {
        const std::int32_t sourceSpan = std::min(tileCapacityHeight, remainingSourceRows);
        const std::int64_t displaySpan64 =
            (static_cast<std::int64_t>(sourceSpan) * displayWidth + sourceRegionWidth - 1LL) /
            sourceRegionWidth;
        if (sourceSpan <= 0 || displaySpan64 <= 0 || displaySpan64 > INT32_MAX ||
            scaledOutputRows > INT32_MAX - displaySpan64) {
            scaledDecodeValid = false;
            break;
        }
        scaledSourceSpans[static_cast<std::size_t>(index)] = sourceSpan;
        scaledDisplaySpans[static_cast<std::size_t>(index)] =
            static_cast<std::int32_t>(displaySpan64);
        scaledOutputRows += displaySpan64;
        remainingSourceRows -= sourceSpan;
    }
    const std::size_t scaledStrideBytes = static_cast<std::size_t>(displayWidth) * 4U;
    scaledDecodeValid = scaledDecodeValid && remainingSourceRows == 0 &&
        scaledOutputRows > 0 && scaledOutputRows <= INT32_MAX &&
        scaledStrideBytes > 0U &&
        scaledStrideBytes <= kMaxExactDecodeScratchBytes /
            static_cast<std::size_t>(scaledOutputRows);
    for (jsize index = 0; scaledDecodeValid && index < bufferCount; ++index) {
        const auto* storage = reinterpret_cast<const ExactCpuTileStorage*>(
            static_cast<std::uintptr_t>(handles[static_cast<std::size_t>(index)]));
        scaledDecodeValid = validExactCpuTile(storage) &&
            storage->width >= static_cast<std::uint32_t>(displayWidth) &&
            storage->height >= static_cast<std::uint32_t>(
                scaledDisplaySpans[static_cast<std::size_t>(index)]);
    }
    const std::size_t scaledScratchBytes = scaledDecodeValid
        ? scaledStrideBytes * static_cast<std::size_t>(scaledOutputRows)
        : 0U;
    // Standard PNGs stream source rows straight into final storage below and must never grow the
    // process-wide scratch to either their original or scaled full-image size.
    if (scaledDecodeValid && !directPng &&
        scaledScratchBytes > exactDecodeScratchCapacity) {
        void* resized = std::realloc(exactDecodeScratch, scaledScratchBytes);
        if (resized == nullptr) {
            scaledDecodeValid = false;
        } else {
            exactDecodeScratch = static_cast<std::uint8_t*>(resized);
            exactDecodeScratchCapacity = scaledScratchBytes;
        }
    }
    int scaledFd = -1;
    AImageDecoder* scaledDecoder = nullptr;
    bool scaledPngDirect = false;
    const std::int64_t scaledDecodeBeginNanos = nowNanos();
    if (scaledDecodeValid && directJpeg) {
        scaledDecodeValid = decodeScaledJpegFile(
            fd,
            sourceWidth,
            sourceHeight,
            displayWidth,
            static_cast<int>(scaledOutputRows),
            exactDecodeScratch,
            scaledStrideBytes,
            scaledScratchBytes,
            exactJpegScaleScratch,
            exactJpegScaleScratchCapacity);
    } else if (scaledDecodeValid && directPng) {
        scaledDecodeValid = decodeScaledPngFileToExactCpuTiles(
            fd,
            sourceWidth,
            sourceHeight,
            tileCapacityHeight,
            displayWidth,
            handles,
            scaledSourceSpans,
            scaledDisplaySpans);
        scaledPngDirect = scaledDecodeValid;
    } else if (scaledDecodeValid) {
        // WebP/GIF/other uncommon formats retain Android's format-complete decoder.
        scaledFd = dup(fd);
        if (scaledFd < 0 || symbols.create(scaledFd, &scaledDecoder) !=
                ANDROID_IMAGE_DECODER_SUCCESS || scaledDecoder == nullptr) {
            scaledDecodeValid = false;
        }
        if (scaledDecodeValid) {
            const AImageDecoderHeaderInfo* scaledHeader = symbols.getHeader(scaledDecoder);
            scaledDecodeValid = scaledHeader != nullptr &&
                symbols.getWidth(scaledHeader) == sourceWidth &&
                symbols.getHeight(scaledHeader) == sourceHeight &&
                symbols.setFormat(scaledDecoder, ANDROID_BITMAP_FORMAT_RGBA_8888) ==
                    ANDROID_IMAGE_DECODER_SUCCESS &&
                symbols.setDataSpace(scaledDecoder, ADATASPACE_SRGB) ==
                    ANDROID_IMAGE_DECODER_SUCCESS &&
                symbols.setTargetSize(
                    scaledDecoder,
                    displayWidth,
                    static_cast<std::int32_t>(scaledOutputRows)) ==
                    ANDROID_IMAGE_DECODER_SUCCESS &&
                symbols.decode(
                    scaledDecoder,
                    exactDecodeScratch,
                    scaledStrideBytes,
                    scaledScratchBytes) == ANDROID_IMAGE_DECODER_SUCCESS;
        }
    }
    const std::int64_t scaledDecodeEndNanos = nowNanos();
    std::int64_t scaledCopyNanos = 0;
    if (scaledDecodeValid && !scaledPngDirect) {
        std::size_t scaledSourceRow = 0U;
        const std::int64_t copyBeginNanos = nowNanos();
        for (jsize index = 0; index < bufferCount; ++index) {
            auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
                static_cast<std::uintptr_t>(handles[static_cast<std::size_t>(index)]));
            const std::int32_t displaySpan =
                scaledDisplaySpans[static_cast<std::size_t>(index)];
            const std::size_t rowBytes = scaledStrideBytes;
            for (std::int32_t row = 0; row < displaySpan; ++row) {
                std::memcpy(
                    storage->pixels + static_cast<std::size_t>(row) * storage->strideBytes,
                    exactDecodeScratch + (scaledSourceRow + static_cast<std::size_t>(row)) *
                        scaledStrideBytes,
                    rowBytes);
            }
            storage->contentWidth = static_cast<std::uint32_t>(displayWidth);
            storage->contentHeight = static_cast<std::uint32_t>(displaySpan);
            storage->logicalWidth = static_cast<std::uint32_t>(sourceWidth);
            storage->logicalHeight = static_cast<std::uint32_t>(
                scaledSourceSpans[static_cast<std::size_t>(index)]);
            scaledSourceRow += static_cast<std::size_t>(displaySpan);
        }
        scaledCopyNanos = nowNanos() - copyBeginNanos;
    }
    if (scaledDecoder != nullptr) symbols.destroy(scaledDecoder);
    if (scaledFd >= 0) close(scaledFd);
    if (scaledDecodeValid) {
        static std::atomic<std::uint64_t> scaledDecodeOrdinal{0};
        const std::uint64_t ordinal = scaledDecodeOrdinal.fetch_add(
            1, std::memory_order_relaxed) + 1;
        if (ordinal == 1 || scaledDecodeEndNanos - scaledDecodeBeginNanos >= 100'000'000LL ||
            scaledCopyNanos >= 16'000'000LL) {
            RLOGI(
                "exact cpu target decode ordinal=%llu width=%d height=%d target=%dx%lld tiles=%d decodeMs=%.3f copyMs=%.3f",
                static_cast<unsigned long long>(ordinal),
                sourceWidth, sourceHeight, displayWidth,
                static_cast<long long>(scaledOutputRows), static_cast<int>(bufferCount),
                static_cast<double>(scaledDecodeEndNanos - scaledDecodeBeginNanos) /
                    1'000'000.0,
                static_cast<double>(scaledCopyNanos) / 1'000'000.0);
        }
        if (decoder != nullptr) symbols.destroy(decoder);
        close(fd);
        return JNI_TRUE;
    }

    if (!tileScratchSizeValid) valid = false;
    const std::int64_t decodeBeginNanos = nowNanos();
    if (valid) {
        const std::size_t requiredScratchBytes = scratchStrideBytes * scratchRows;
        if (requiredScratchBytes > exactDecodeScratchCapacity) {
            void* resized = std::realloc(exactDecodeScratch, requiredScratchBytes);
            if (resized == nullptr) {
                valid = false;
            } else {
                exactDecodeScratch = static_cast<std::uint8_t*>(resized);
                exactDecodeScratchCapacity = requiredScratchBytes;
            }
        }
    }
    bool completeImageDecoded = false;
    if (valid && directJpeg && completeImageFitsScratch) {
        valid = decodeExactJpegFile(
            fd,
            sourceWidth,
            sourceHeight,
            exactDecodeScratch,
            exactDecodeScratchCapacity);
        completeImageDecoded = valid;
    } else if (valid && directJpeg) {
        // The full-width source exceeds the bounded reusable scratch. Returning false preserves
        // the established region-decoder fallback without allocating an unbounded native image.
        valid = false;
    } else if (valid && directPng && completeImageFitsScratch) {
        valid = decodeExactPngFile(
            fd,
            sourceWidth,
            sourceHeight,
            exactDecodeScratch,
            exactDecodeScratchCapacity);
        completeImageDecoded = valid;
    } else if (valid && directPng) {
        // The simplified API decodes one exact image. Preserve the bounded Android decoder
        // fallback for an uncommon source larger than the process-wide scratch ceiling.
        valid = false;
    } else if (valid && completeImageFitsScratch) {
        const ARect completeCrop{0, 0, sourceWidth, sourceHeight};
        valid = symbols.setCrop(decoder, completeCrop) == ANDROID_IMAGE_DECODER_SUCCESS;
        if (valid) {
            valid = symbols.decode(
                decoder,
                exactDecodeScratch,
                scratchStrideBytes,
                scratchStrideBytes * static_cast<std::size_t>(sourceHeight)) ==
                ANDROID_IMAGE_DECODER_SUCCESS;
        }
        completeImageDecoded = valid;
    }
    const std::int64_t decodeEndNanos = nowNanos();

    jint top = 0;
    std::int64_t totalStorageCopyNanos = 0;
    std::int64_t maximumStorageCopyNanos = 0;
    for (jsize index = 0; valid && index < bufferCount; ++index) {
        auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
            static_cast<std::uintptr_t>(handles[static_cast<std::size_t>(index)]));
        const jint span = std::min(tileCapacityHeight, sourceHeight - top);
        const jint displaySpan = static_cast<jint>(
            (static_cast<std::int64_t>(span) * displayWidth + sourceRegionWidth - 1LL) /
            sourceRegionWidth);
        valid = validExactCpuTile(storage) && span > 0 &&
            displaySpan > 0 &&
            storage->width >= static_cast<std::uint32_t>(displayWidth) &&
            storage->height >= static_cast<std::uint32_t>(displaySpan);
        if (valid) {
            storage->contentWidth = 0U;
            storage->contentHeight = 0U;
            storage->logicalWidth = 0U;
            storage->logicalHeight = 0U;
        }
        if (valid && !completeImageDecoded) {
            const ARect crop{
                sourceCropLeft,
                top,
                sourceCropLeft + sourceRegionWidth,
                top + span,
            };
            valid = symbols.setCrop(decoder, crop) ==
                ANDROID_IMAGE_DECODER_SUCCESS;
            if (valid) {
                const size_t scratchBytes =
                    scratchStrideBytes * static_cast<std::size_t>(span);
                valid = symbols.decode(
                    decoder,
                    exactDecodeScratch,
                    scratchStrideBytes,
                    scratchBytes) == ANDROID_IMAGE_DECODER_SUCCESS;
            }
        }
        if (valid) {
            const auto* sourcePixels = exactDecodeScratch +
                (completeImageDecoded
                    ? static_cast<std::size_t>(top) * scratchStrideBytes +
                        static_cast<std::size_t>(sourceCropLeft) * 4U
                    : 0U);
            const std::int64_t copyBeginNanos = nowNanos();
            valid = scaleRgba8888(
                sourcePixels,
                static_cast<std::uint32_t>(sourceRegionWidth),
                static_cast<std::uint32_t>(span),
                scratchStrideBytes,
                storage->pixels,
                static_cast<std::uint32_t>(displayWidth),
                static_cast<std::uint32_t>(displaySpan),
                storage->strideBytes);
            if (valid) {
                storage->contentWidth = static_cast<std::uint32_t>(displayWidth);
                storage->contentHeight = static_cast<std::uint32_t>(displaySpan);
                storage->logicalWidth = static_cast<std::uint32_t>(sourceRegionWidth);
                storage->logicalHeight = static_cast<std::uint32_t>(span);
            }
            const std::int64_t copyNanos = nowNanos() - copyBeginNanos;
            totalStorageCopyNanos += copyNanos;
            maximumStorageCopyNanos = std::max(maximumStorageCopyNanos, copyNanos);
        }
        top += span;
    }
    valid = valid && top == sourceHeight;
    if (valid && (decodeEndNanos - decodeBeginNanos >= 100'000'000LL ||
                  maximumStorageCopyNanos >= 16'000'000LL)) {
        RLOGI(
            "exact cpu file decode width=%d height=%d crop=%d+%d displayWidth=%d tiles=%d singleDecode=%d directJpeg=%d directPng=%d decodeMs=%.3f copyMs=%.3f maxCopyMs=%.3f",
            sourceWidth, sourceHeight, sourceCropLeft, sourceRegionWidth,
            displayWidth, static_cast<int>(bufferCount),
            completeImageDecoded ? 1 : 0, directJpeg ? 1 : 0, directPng ? 1 : 0,
            static_cast<double>(decodeEndNanos - decodeBeginNanos) / 1'000'000.0,
            static_cast<double>(totalStorageCopyNanos) / 1'000'000.0,
            static_cast<double>(maximumStorageCopyNanos) / 1'000'000.0);
    }
    if (decoder != nullptr) symbols.destroy(decoder);
    close(fd);
    return valid ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCopyExactBitmapToHardwareTile(
        JNIEnv* env, jobject, jobject bitmap, jlong nativeHandle,
        jint sourceWidth, jint sourceTop, jint sourceHeight,
        jint displayWidth) {
    if (env == nullptr || bitmap == nullptr || nativeHandle == 0 ||
        sourceWidth <= 0 || sourceTop < 0 || sourceHeight <= 0 ||
        sourceTop > INT32_MAX - sourceHeight || displayWidth <= 0 ||
        displayWidth > sourceWidth) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo info{};
    const int infoResult = AndroidBitmap_getInfo(env, bitmap, &info);
    if (infoResult != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0U ||
        static_cast<jint>(info.width) != sourceWidth ||
        static_cast<jint>(info.height) < sourceTop + sourceHeight ||
        info.stride < static_cast<std::uint32_t>(sourceWidth) * 4U) {
        RLOGE(
            "exact cpu tile copy rejected stage=bitmap-info result=%d format=%d flags=0x%x size=%ux%u stride=%u expected=%dx%d+%d",
            infoResult, info.format, info.flags, info.width, info.height, info.stride,
            sourceWidth, sourceTop, sourceHeight);
        return JNI_FALSE;
    }
    void* sourcePixels = nullptr;
    const int sourceLockResult = AndroidBitmap_lockPixels(env, bitmap, &sourcePixels);
    if (sourceLockResult != ANDROID_BITMAP_RESULT_SUCCESS || sourcePixels == nullptr) {
        RLOGE(
            "exact cpu tile copy rejected stage=bitmap-lock result=%d pixels=%p expected=%dx%d",
            sourceLockResult, sourcePixels, sourceWidth, sourceHeight);
        if (sourceLockResult == ANDROID_BITMAP_RESULT_SUCCESS) {
            AndroidBitmap_unlockPixels(env, bitmap);
        }
        return JNI_FALSE;
    }

    auto* storage = reinterpret_cast<ExactCpuTileStorage*>(
        static_cast<std::uintptr_t>(nativeHandle));
    if (validExactCpuTile(storage)) {
        storage->hardwareMirrorReady.store(false, std::memory_order_release);
    }
    const jint displayHeight = static_cast<jint>(
        (static_cast<std::int64_t>(sourceHeight) * displayWidth + sourceWidth - 1LL) /
        sourceWidth);
    bool succeeded = ensureExactCpuTilePixels(storage) &&
        displayHeight > 0 &&
        storage->width >= static_cast<std::uint32_t>(displayWidth) &&
        storage->height >= static_cast<std::uint32_t>(displayHeight);
    if (succeeded) {
        const auto* source = static_cast<const std::uint8_t*>(sourcePixels);
        succeeded = scaleRgba8888(
            source + static_cast<std::size_t>(sourceTop) * info.stride,
            static_cast<std::uint32_t>(sourceWidth),
            static_cast<std::uint32_t>(sourceHeight),
            info.stride,
            storage->pixels,
            static_cast<std::uint32_t>(displayWidth),
            static_cast<std::uint32_t>(displayHeight),
            storage->strideBytes);
        if (succeeded) {
            storage->contentWidth = static_cast<std::uint32_t>(displayWidth);
            storage->contentHeight = static_cast<std::uint32_t>(displayHeight);
            storage->logicalWidth = static_cast<std::uint32_t>(sourceWidth);
            storage->logicalHeight = static_cast<std::uint32_t>(sourceHeight);
        }
    }
    if (AndroidBitmap_unlockPixels(env, bitmap) != ANDROID_BITMAP_RESULT_SUCCESS) {
        succeeded = false;
    }
    if (!succeeded) {
        if (validExactCpuTile(storage)) {
            storage->contentWidth = 0U;
            storage->contentHeight = 0U;
            storage->logicalWidth = 0U;
            storage->logicalHeight = 0U;
        }
        RLOGE(
            "exact cpu tile copy failed source=%dx%d+%d storage=%p size=%ux%u stride=%zu exception=%d",
            sourceWidth, sourceTop, sourceHeight, storage,
            storage != nullptr ? storage->width : 0U,
            storage != nullptr ? storage->height : 0U,
            storage != nullptr ? storage->strideBytes : 0U,
            env->ExceptionCheck() ? 1 : 0);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativePrepare(
        JNIEnv*, jobject, jlong handle, jint width, jint height) {
    auto value = renderer(handle);
    return value != nullptr && value->prepare(width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeAttach(
        JNIEnv* env, jobject, jlong handle, jobject surface, jobject childSurfaceControl,
        jobject geometrySurfaceControl,
        jint width, jint height,
        jlong surfaceEpoch, jlong refreshPeriodNanos) {
    auto value = renderer(handle);
    if (env == nullptr || value == nullptr || surface == nullptr || surfaceEpoch <= 0) {
        return JNI_FALSE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    ASurfaceControl* providedChild = nullptr;
    ASurfaceControl* providedGeometry = nullptr;
    if (childSurfaceControl != nullptr || geometrySurfaceControl != nullptr) {
        using FromJava = ASurfaceControl* (*)(JNIEnv*, jobject);
        void* androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        auto fromJava = reinterpret_cast<FromJava>(androidLibrary != nullptr
            ? dlsym(androidLibrary, "ASurfaceControl_fromJava") : nullptr);
        providedChild = fromJava != nullptr && childSurfaceControl != nullptr
            ? fromJava(env, childSurfaceControl) : nullptr;
        providedGeometry = fromJava != nullptr && geometrySurfaceControl != nullptr
            ? fromJava(env, geometrySurfaceControl) : nullptr;
        if (androidLibrary != nullptr) dlclose(androidLibrary);
        if (providedChild == nullptr || providedGeometry == nullptr) {
            ANativeWindow_release(window);
            if (providedChild != nullptr) releaseSurfaceControlReference(providedChild);
            if (providedGeometry != nullptr) releaseSurfaceControlReference(providedGeometry);
            return JNI_FALSE;
        }
    }
    if (!value->attach(window, providedChild, providedGeometry, width, height,
                       static_cast<std::uint64_t>(surfaceEpoch),
                       static_cast<std::int64_t>(refreshPeriodNanos))) {
        ANativeWindow_release(window);
        if (providedChild != nullptr) releaseSurfaceControlReference(providedChild);
        if (providedGeometry != nullptr) releaseSurfaceControlReference(providedGeometry);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeIsSurfaceAttached(
        JNIEnv*, jobject, jlong handle, jlong surfaceEpoch) {
    auto value = renderer(handle);
    return value != nullptr && surfaceEpoch > 0 &&
            value->isSurfaceAttached(static_cast<std::uint64_t>(surfaceEpoch))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeHasFrameMailboxCapacity(
        JNIEnv*, jobject, jlong handle) {
    auto value = renderer(handle);
    return value != nullptr && value->hasFrameMailboxCapacity() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDetach(
        JNIEnv*, jobject, jlong handle, jlong surfaceEpoch) {
    auto value = renderer(handle);
    if (value != nullptr) value->detach(static_cast<std::uint64_t>(surfaceEpoch));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSubmit(
        JNIEnv* env, jobject, jlong handle, jlong token, jlong structureEpoch,
        jint width, jint height, jint viewportSourceTop, jint viewportSourceHeight,
        jlong frameTimelineVsyncId, jlong expectedPresentationTimeNanos,
        jboolean requiresGpuCompletionProof, jlong producerSceneId,
        jint bitmapCount, jintArray tileData,
        jfloatArray geometryData, jobjectArray bitmaps) {
    auto value = renderer(handle);
    return value != nullptr
        ? static_cast<jlong>(value->submit(
            env, static_cast<std::uint64_t>(token),
            static_cast<std::int64_t>(structureEpoch), width, height,
            viewportSourceTop, viewportSourceHeight,
            static_cast<std::int64_t>(frameTimelineVsyncId),
            static_cast<std::int64_t>(expectedPresentationTimeNanos),
            requiresGpuCompletionProof == JNI_TRUE,
            producerSceneId > 0 ? static_cast<std::uint64_t>(producerSceneId) : 0,
            bitmapCount, tileData, geometryData, bitmaps))
        : static_cast<jlong>(-1);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSubmitProducerGeometry(
        JNIEnv* env, jobject, jlong handle, jlong producerSceneId, jlong token,
        jlong structureEpoch, jint width, jint height, jint viewportSourceTop,
        jint viewportSourceHeight, jfloat producerSceneTranslationY,
        jlong frameTimelineVsyncId,
        jlong expectedPresentationTimeNanos, jboolean requiresGpuCompletionProof) {
    auto value = renderer(handle);
    return value != nullptr && producerSceneId > 0
        ? static_cast<jlong>(value->submitProducerGeometry(
            env,
            static_cast<std::uint64_t>(producerSceneId),
            static_cast<std::uint64_t>(token),
            static_cast<std::int64_t>(structureEpoch),
            width,
            height,
            viewportSourceTop,
            viewportSourceHeight,
            producerSceneTranslationY,
            static_cast<std::int64_t>(frameTimelineVsyncId),
            static_cast<std::int64_t>(expectedPresentationTimeNanos),
            requiresGpuCompletionProof == JNI_TRUE))
        : static_cast<jlong>(-1);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativePrewarm(
        JNIEnv* env, jobject, jlong handle, jlong structureEpoch,
        jintArray tileData, jobjectArray bitmaps, jboolean completeSceneSnapshot) {
    auto value = renderer(handle);
    return value != nullptr && value->prewarm(
        env, static_cast<std::int64_t>(structureEpoch), tileData, bitmaps,
        completeSceneSnapshot == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSetPrewarmPaused(
        JNIEnv*, jobject, jlong handle, jboolean paused) {
    auto value = renderer(handle);
    if (value != nullptr) value->setPrewarmPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeSetDirectWifiTextureProfile(
        JNIEnv*, jobject, jlong handle, jboolean enabled, jboolean hostGpuEmulator) {
    auto value = renderer(handle);
    if (value != nullptr) {
        value->setDirectWifiTextureProfile(
            enabled == JNI_TRUE,
            hostGpuEmulator == JNI_TRUE);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeIsQuiescent(
        JNIEnv*, jobject, jlong handle) {
    auto value = renderer(handle);
    return value != nullptr && value->isQuiescent() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeBitmapReferenceMask(
        JNIEnv* env, jobject, jlong handle, jintArray bitmapIdentities) {
    if (env == nullptr || bitmapIdentities == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(bitmapIdentities);
    if (count < 0) return nullptr;
    jbooleanArray result = env->NewBooleanArray(count);
    if (result == nullptr || count == 0) return result;

    jint* identities = env->GetIntArrayElements(bitmapIdentities, nullptr);
    if (identities == nullptr) return nullptr;
    auto mask = std::unique_ptr<jboolean[]>(
        new (std::nothrow) jboolean[static_cast<std::size_t>(count)]);
    if (mask == nullptr) {
        env->ReleaseIntArrayElements(bitmapIdentities, identities, JNI_ABORT);
        return nullptr;
    }
    std::fill_n(mask.get(), static_cast<std::size_t>(count), JNI_FALSE);
    auto value = renderer(handle);
    if (value == nullptr || !value->bitmapReferenceMask(
            identities, static_cast<std::size_t>(count), mask.get())) {
        env->ReleaseIntArrayElements(bitmapIdentities, identities, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseIntArrayElements(bitmapIdentities, identities, JNI_ABORT);
    env->SetBooleanArrayRegion(result, 0, count, mask.get());
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDiscardQueuedPrewarmBitmaps(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobjectArray bitmaps,
        jintArray bitmapIdentities) {
    if (env == nullptr || bitmaps == nullptr || bitmapIdentities == nullptr) return -1;
    auto value = renderer(handle);
    if (value == nullptr) return -1;
    const jsize count = env->GetArrayLength(bitmaps);
    if (count <= 0) return 0;
    if (env->GetArrayLength(bitmapIdentities) != count) return -1;
    std::vector<jobject> candidates;
    candidates.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        jobject bitmap = env->GetObjectArrayElement(bitmaps, index);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            for (jobject candidate : candidates) env->DeleteLocalRef(candidate);
            return -1;
        }
        if (bitmap != nullptr) candidates.push_back(bitmap);
    }
    jint* identities = env->GetIntArrayElements(bitmapIdentities, nullptr);
    if (identities == nullptr) {
        for (jobject candidate : candidates) env->DeleteLocalRef(candidate);
        return -1;
    }
    std::vector<jint> identityValues(
        identities,
        identities + static_cast<std::size_t>(count));
    env->ReleaseIntArrayElements(bitmapIdentities, identities, JNI_ABORT);
    const int retired = value->discardQueuedPrewarmBitmaps(
        env, candidates, identityValues);
    for (jobject candidate : candidates) env->DeleteLocalRef(candidate);
    return static_cast<jint>(retired);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDiscardQueuedFramesWithRetiredBitmaps(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobjectArray bitmaps,
        jintArray bitmapIdentities,
        jlong protectedToken) {
    if (env == nullptr || bitmaps == nullptr || bitmapIdentities == nullptr) return nullptr;
    auto value = renderer(handle);
    if (value == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(bitmaps);
    if (count < 0 || env->GetArrayLength(bitmapIdentities) != count) return nullptr;
    std::vector<jobject> candidates;
    candidates.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        jobject bitmap = env->GetObjectArrayElement(bitmaps, index);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            for (jobject candidate : candidates) {
                if (candidate != nullptr) env->DeleteLocalRef(candidate);
            }
            return nullptr;
        }
        candidates.push_back(bitmap);
    }
    jint* identities = env->GetIntArrayElements(bitmapIdentities, nullptr);
    if (identities == nullptr) {
        for (jobject candidate : candidates) {
            if (candidate != nullptr) env->DeleteLocalRef(candidate);
        }
        return nullptr;
    }
    std::vector<jint> identityValues(
        identities,
        identities + static_cast<std::size_t>(count));
    env->ReleaseIntArrayElements(bitmapIdentities, identities, JNI_ABORT);
    std::vector<std::uint64_t> tokens;
    const bool completed = value->tryDiscardQueuedFramesWithRetiredBitmaps(
        env,
        candidates,
        identityValues,
        protectedToken > 0 ? static_cast<std::uint64_t>(protectedToken) : 0,
        &tokens);
    for (jobject candidate : candidates) {
        if (candidate != nullptr) env->DeleteLocalRef(candidate);
    }
    if (!completed) return nullptr;
    jlongArray result = env->NewLongArray(static_cast<jsize>(tokens.size()));
    if (result == nullptr || tokens.empty()) return result;
    std::vector<jlong> values;
    values.reserve(tokens.size());
    for (const std::uint64_t token : tokens) values.push_back(static_cast<jlong>(token));
    env->SetLongArrayRegion(
        result, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeHasFailed(
        JNIEnv*, jobject, jlong handle) {
    auto value = renderer(handle);
    return value == nullptr || value->hasFailed() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    auto value = takeRendererForDestroy(handle);
    if (!value) return;
    if (!value->destroy()) quarantineRenderer(std::move(value));
}
