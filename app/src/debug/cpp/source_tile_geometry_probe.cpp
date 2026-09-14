// language: C++, file: source_tile_geometry_probe.cpp, target: Android NDK 27.2, C++20
// Debug-only source-tile geometry probe: one ASurfaceControl root composites immutable
// AHardwareBuffer tiles that are set once; later transactions are geometry-only.
#ifndef NDEBUG

#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <jni.h>
#include <dlfcn.h>
#include <cerrno>
#include <cmath>
#include <poll.h>
#include <unistd.h>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <vector>

#include "viewer_cpu_tile.h"

namespace {

constexpr int32_t kRootZOrder = 10000;
constexpr float kParentScale = 1.0F / 1024.0F;
constexpr int kDrainMillis = 4000;
constexpr int kReleaseFenceMillis = 2000;

using SetScale = void (*)(ASurfaceTransaction*, ASurfaceControl*, float, float);
using SetPosition = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t, int32_t);
using SetCrop = void (*)(ASurfaceTransaction*, ASurfaceControl*, const ARect&);

struct SourceTile {
    ASurfaceControl* control = nullptr;
    AHardwareBuffer* buffer = nullptr;
    int width = 0;
    int height = 0;
};

struct ProbeState {
    std::mutex mutex;
    std::condition_variable drained;
    ASurfaceControl* root = nullptr;
    ASurfaceControl* background = nullptr;
    AHardwareBuffer* backgroundBuffer = nullptr;
    std::vector<SourceTile> tiles;
    std::vector<int> presentFds;
    std::vector<int> releaseFds;
    SetScale setScale = nullptr;
    SetPosition setPosition = nullptr;
    int32_t viewportWidth = 0;
    int32_t viewportHeight = 0;
    bool removalStarted = false;
    bool removed = false;
    int pending = 0;
    int uploads = 0;
    int presents = 0;
    int presentFenceAvailable = 0;
};

struct ProbeTicket {
    std::shared_ptr<ProbeState> state;
    std::vector<ASurfaceControl*> surfaces;
    bool queryRelease = false;
};

void probeCompleted(void* opaque, ASurfaceTransactionStats* stats) {
    std::unique_ptr<ProbeTicket> ticket(static_cast<ProbeTicket*>(opaque));
    const std::shared_ptr<ProbeState>& state = ticket->state;
    const int present = ASurfaceTransactionStats_getPresentFenceFd(stats);
    std::vector<int> releases;
    if (ticket->queryRelease) {
        for (ASurfaceControl* surface : ticket->surfaces) {
            const int release = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, surface);
            if (release >= 0) releases.push_back(release);
        }
    }
    std::lock_guard lock(state->mutex);
    if (present >= 0) {
        state->presentFds.push_back(present);
        state->presentFenceAvailable++;
    }
    for (int fd : releases) state->releaseFds.push_back(fd);
    state->pending--;
    state->drained.notify_all();
}

void applyTransaction(ASurfaceTransaction* transaction, const std::shared_ptr<ProbeState>& state,
                      std::vector<ASurfaceControl*> surfaces, bool queryRelease) {
    {
        std::lock_guard lock(state->mutex);
        state->pending++;
    }
    ASurfaceTransaction_setOnComplete(transaction,
        new ProbeTicket{state, std::move(surfaces), queryRelease}, probeCompleted);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

bool waitDrained(const std::shared_ptr<ProbeState>& state, int millis) {
    std::unique_lock lock(state->mutex);
    return state->drained.wait_for(lock, std::chrono::milliseconds(millis),
        [&] { return state->pending == 0; });
}

AHardwareBuffer* allocateBuffer(int width, int height) {
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    AHardwareBuffer* buffer = nullptr;
    return AHardwareBuffer_allocate(&desc, &buffer) == 0 ? buffer : nullptr;
}

bool fillBlack(AHardwareBuffer* buffer) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    void* pixels = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels) != 0)
        return false;
    auto* dst = static_cast<uint32_t*>(pixels);
    for (uint32_t y = 0; y < desc.height; ++y)
        for (uint32_t x = 0; x < desc.width; ++x) dst[y * desc.stride + x] = 0xFF000000U;
    return AHardwareBuffer_unlock(buffer, nullptr) == 0;
}

bool fillTile(AHardwareBuffer* buffer, const ViewerCpuTileView& cpu, int width) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    void* pixels = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels) != 0)
        return false;
    auto* dst = static_cast<uint8_t*>(pixels);
    const std::size_t rowBytes = static_cast<std::size_t>(width) * 4U;
    for (uint32_t y = 0; y < desc.height; ++y) {
        std::memcpy(dst + static_cast<std::size_t>(y) * desc.stride * 4U,
            cpu.pixels + static_cast<std::size_t>(y) * rowBytes, rowBytes);
    }
    return AHardwareBuffer_unlock(buffer, nullptr) == 0;
}

std::shared_ptr<ProbeState> stateOf(jlong handle) {
    auto* holder = reinterpret_cast<std::shared_ptr<ProbeState>*>(static_cast<std::uintptr_t>(handle));
    return holder == nullptr ? nullptr : *holder;
}

bool beginOrAwaitRemoval(const std::shared_ptr<ProbeState>& state) {
    bool issue = false;
    {
        std::lock_guard lock(state->mutex);
        if (state->removed) return true;
        if (!state->removalStarted) {
            state->removalStarted = true;
            issue = true;
        }
    }
    if (issue) {
        std::vector<ASurfaceControl*> surfaces;
        surfaces.reserve(state->tiles.size() + 1);
        for (const SourceTile& tile : state->tiles) surfaces.push_back(tile.control);
        if (state->background != nullptr) surfaces.push_back(state->background);
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        if (transaction == nullptr) {
            std::lock_guard lock(state->mutex);
            state->removalStarted = false;
            return false;
        }
        for (const SourceTile& tile : state->tiles)
            ASurfaceTransaction_reparent(transaction, tile.control, nullptr);
        if (state->background != nullptr)
            ASurfaceTransaction_reparent(transaction, state->background, nullptr);
        if (state->root != nullptr) ASurfaceTransaction_reparent(transaction, state->root, nullptr);
        applyTransaction(transaction, state, std::move(surfaces), true);
    }
    if (!waitDrained(state, kDrainMillis)) return false;
    {
        std::lock_guard lock(state->mutex);
        state->removed = true;
    }
    return true;
}

bool awaitReleaseFences(const std::shared_ptr<ProbeState>& state) {
    std::vector<int> fds;
    {
        std::lock_guard lock(state->mutex);
        fds.swap(state->releaseFds);
    }
    bool ok = true;
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kReleaseFenceMillis);
    for (int fd : fds) {
        int status = 0;
        for (;;) {
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now()).count();
            if (remaining <= 0) {
                status = 0;
                break;
            }
            pollfd poller{fd, POLLIN, 0};
            status = poll(&poller, 1, static_cast<int>(remaining));
            if (status >= 0) {
                if (status > 0 && (poller.revents & POLLIN) == 0) status = 0;
                break;
            }
            if (errno != EINTR) {
                status = -1;
                break;
            }
        }
        if (status > 0) {
            close(fd);
        } else {
            ok = false;
            std::lock_guard lock(state->mutex);
            state->releaseFds.push_back(fd);
        }
    }
    return ok;
}

void closeStoredFds(const std::shared_ptr<ProbeState>& state) {
    std::vector<int> fds;
    {
        std::lock_guard lock(state->mutex);
        fds.swap(state->presentFds);
        fds.insert(fds.end(), state->releaseFds.begin(), state->releaseFds.end());
        state->releaseFds.clear();
    }
    for (int fd : fds) close(fd);
}

void releaseControls(const std::shared_ptr<ProbeState>& state) {
    for (SourceTile& tile : state->tiles) {
        ASurfaceControl_release(tile.control);
        AHardwareBuffer_release(tile.buffer);
    }
    state->tiles.clear();
    if (state->background != nullptr) ASurfaceControl_release(state->background);
    if (state->backgroundBuffer != nullptr) AHardwareBuffer_release(state->backgroundBuffer);
    if (state->root != nullptr) ASurfaceControl_release(state->root);
}

bool validatePresent(const ProbeState& state, jsize count, const jint* indices, const jint* tops,
                     const jint* bottoms, jint viewportTopUnits, jint viewportWidth) {
    const auto tileCount = static_cast<jsize>(state.tiles.size());
    for (jsize i = 0; i < count; ++i) {
        const jint index = indices[i];
        if (index < 0 || index >= tileCount || tops[i] >= bottoms[i]) return false;
        for (jsize j = 0; j < i; ++j) {
            if (indices[j] == index) return false;
        }
        const long long y = static_cast<long long>(tops[i]) - static_cast<long long>(viewportTopUnits);
        if (y < std::numeric_limits<int32_t>::min() || y > std::numeric_limits<int32_t>::max())
            return false;
        const long long span = static_cast<long long>(bottoms[i]) - static_cast<long long>(tops[i]);
        if (span > (1LL << 24)) return false;
        const SourceTile& tile = state.tiles[static_cast<std::size_t>(index)];
        if (tile.width <= 0 || tile.height <= 0) return false;
        const float scaleX = 1024.0F * static_cast<float>(viewportWidth) /
            static_cast<float>(tile.width);
        const float scaleY = static_cast<float>(span) / static_cast<float>(tile.height);
        if (!std::isfinite(scaleX) || !std::isfinite(scaleY) || scaleX <= 0.0F || scaleY <= 0.0F)
            return false;
    }
    return true;
}

void buildPresent(ASurfaceTransaction* transaction, const std::shared_ptr<ProbeState>& state,
                  jsize count, const jint* indices, const jint* tops, const jint* bottoms,
                  jint viewportTopUnits, jint viewportWidth) {
    if (state->background != nullptr) {
        ASurfaceTransaction_setVisibility(transaction, state->background,
            ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    }
    for (std::size_t t = 0; t < state->tiles.size(); ++t) {
        bool listed = false;
        for (jsize i = 0; i < count; ++i) {
            if (indices[i] == static_cast<jint>(t)) {
                listed = true;
                break;
            }
        }
        if (!listed) {
            ASurfaceTransaction_setVisibility(transaction, state->tiles[t].control,
                ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        }
    }
    for (jsize i = 0; i < count; ++i) {
        const SourceTile& tile = state->tiles[static_cast<std::size_t>(indices[i])];
        const long long span = static_cast<long long>(bottoms[i]) - static_cast<long long>(tops[i]);
        const int32_t y = static_cast<int32_t>(
            static_cast<long long>(tops[i]) - static_cast<long long>(viewportTopUnits));
        const float scaleX = 1024.0F * static_cast<float>(viewportWidth) /
            static_cast<float>(tile.width);
        const float scaleY = static_cast<float>(span) / static_cast<float>(tile.height);
        ASurfaceTransaction_setVisibility(transaction, tile.control,
            ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setZOrder(transaction, tile.control, static_cast<int32_t>(i) + 1);
        state->setPosition(transaction, tile.control, 0, y);
        state->setScale(transaction, tile.control, scaleX, scaleY);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_create(
    JNIEnv* env, jobject, jobject surface, jint viewportWidth, jint viewportHeight) {
    if (env == nullptr || surface == nullptr || viewportWidth <= 0 || viewportHeight <= 0 ||
        viewportWidth > 4096 || viewportHeight > 4096) return 0;
    auto setScale = reinterpret_cast<SetScale>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setScale"));
    auto setPosition = reinterpret_cast<SetPosition>(
        dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setPosition"));
    auto setCrop = reinterpret_cast<SetCrop>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setCrop"));
    if (setScale == nullptr || setPosition == nullptr || setCrop == nullptr) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    ASurfaceControl* root = ASurfaceControl_createFromWindow(window, "EngineSourceTileGeometryProbe");
    ANativeWindow_release(window);
    if (root == nullptr) return 0;
    auto state = std::make_shared<ProbeState>();
    state->root = root;
    state->setScale = setScale;
    state->setPosition = setPosition;
    state->viewportWidth = viewportWidth;
    state->viewportHeight = viewportHeight;
    state->background = ASurfaceControl_create(root, "EngineSourceTileBackground");
    state->backgroundBuffer = state->background == nullptr ? nullptr : allocateBuffer(1, 1);
    if (state->backgroundBuffer == nullptr || !fillBlack(state->backgroundBuffer)) {
        releaseControls(state);
        return 0;
    }
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (transaction == nullptr) {
        releaseControls(state);
        return 0;
    }
    ASurfaceTransaction_setZOrder(transaction, root, kRootZOrder);
    ASurfaceTransaction_setVisibility(transaction, root, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    setScale(transaction, root, kParentScale, kParentScale);
    setCrop(transaction, root, ARect{0, 0, viewportWidth * 1024, viewportHeight * 1024});
    ASurfaceTransaction_setBufferTransparency(transaction, state->background,
        ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ASurfaceTransaction_setBufferDataSpace(transaction, state->background, ADATASPACE_SRGB);
    ASurfaceTransaction_setBuffer(transaction, state->background, state->backgroundBuffer, -1);
    ASurfaceTransaction_setZOrder(transaction, state->background, 0);
    setScale(transaction, state->background, 1024.0F * static_cast<float>(viewportWidth),
        1024.0F * static_cast<float>(viewportHeight));
    setPosition(transaction, state->background, 0, 0);
    ASurfaceTransaction_setVisibility(transaction, state->background,
        ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    applyTransaction(transaction, state, {state->background}, true);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(
        new std::shared_ptr<ProbeState>(state)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_upload(
    JNIEnv*, jobject, jlong handle, jlong cpuTile, jint width, jint height) {
    auto state = stateOf(handle);
    if (state == nullptr || width <= 0 || height <= 0 || width > 8192 || height > 8192)
        return JNI_FALSE;
    ViewerCpuTileView cpu{};
    if (!viewerDescribeCpuTile(static_cast<std::uint64_t>(cpuTile), &cpu)) return JNI_FALSE;
    const std::uint64_t expected = static_cast<std::uint64_t>(width) *
        static_cast<std::uint64_t>(height) * 4ULL;
    if (cpu.byteCount != expected) return JNI_FALSE;
    AHardwareBuffer* buffer = allocateBuffer(width, height);
    if (buffer == nullptr) return JNI_FALSE;
    ASurfaceControl* control = nullptr;
    if (!fillTile(buffer, cpu, width) ||
        (control = ASurfaceControl_create(state->root, "EngineSourceTile")) == nullptr) {
        if (control != nullptr) ASurfaceControl_release(control);
        AHardwareBuffer_release(buffer);
        return JNI_FALSE;
    }
    int32_t z = 0;
    {
        std::lock_guard lock(state->mutex);
        state->uploads++;
        state->tiles.push_back(SourceTile{control, buffer, width, height});
        z = static_cast<int32_t>(state->tiles.size());
    }
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (transaction == nullptr) {
        {
            std::lock_guard lock(state->mutex);
            state->tiles.pop_back();
            state->uploads--;
        }
        ASurfaceControl_release(control);
        AHardwareBuffer_release(buffer);
        return JNI_FALSE;
    }
    ASurfaceTransaction_setBufferTransparency(transaction, control,
        ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ASurfaceTransaction_setBufferDataSpace(transaction, control, ADATASPACE_SRGB);
    ASurfaceTransaction_setBuffer(transaction, control, buffer, -1);
    ASurfaceTransaction_setZOrder(transaction, control, z);
    ASurfaceTransaction_setVisibility(transaction, control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    applyTransaction(transaction, state, {control}, true);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_present(
    JNIEnv* env, jobject, jlong handle, jintArray indices, jintArray topUnits, jintArray bottomUnits,
    jint viewportTopUnits, jint viewportWidth, jint viewportHeight) {
    auto state = stateOf(handle);
    if (state == nullptr || env == nullptr || indices == nullptr || topUnits == nullptr ||
        bottomUnits == nullptr || viewportWidth <= 0 || viewportHeight <= 0 ||
        viewportWidth > 4096 || viewportHeight > 4096) return JNI_FALSE;
    const jsize count = env->GetArrayLength(indices);
    if (count <= 0 || env->GetArrayLength(topUnits) != count ||
        env->GetArrayLength(bottomUnits) != count) return JNI_FALSE;
    jint* indexValues = env->GetIntArrayElements(indices, nullptr);
    jint* topValues = env->GetIntArrayElements(topUnits, nullptr);
    jint* bottomValues = env->GetIntArrayElements(bottomUnits, nullptr);
    bool ok = indexValues != nullptr && topValues != nullptr && bottomValues != nullptr;
    if (ok) {
        std::lock_guard lock(state->mutex);
        ok = viewportWidth == state->viewportWidth && viewportHeight == state->viewportHeight &&
            validatePresent(*state, count, indexValues, topValues, bottomValues, viewportTopUnits,
                viewportWidth);
    }
    if (ok) {
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        if (transaction == nullptr) ok = false;
        else {
            buildPresent(transaction, state, count, indexValues, topValues, bottomValues,
                viewportTopUnits, viewportWidth);
            {
                std::lock_guard lock(state->mutex);
                state->presents++;
            }
            applyTransaction(transaction, state, {}, false);
        }
    }
    if (indexValues != nullptr) env->ReleaseIntArrayElements(indices, indexValues, JNI_ABORT);
    if (topValues != nullptr) env->ReleaseIntArrayElements(topUnits, topValues, JNI_ABORT);
    if (bottomValues != nullptr) env->ReleaseIntArrayElements(bottomUnits, bottomValues, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_await(
    JNIEnv*, jobject, jlong handle, jlong timeoutMillis) {
    auto state = stateOf(handle);
    if (state == nullptr || timeoutMillis < 0 || timeoutMillis > 60000) return JNI_FALSE;
    return waitDrained(state, static_cast<int>(timeoutMillis)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_status(
    JNIEnv* env, jobject, jlong handle) {
    auto state = stateOf(handle);
    if (state == nullptr || env == nullptr) return nullptr;
    jlong values[6] = {};
    {
        std::lock_guard lock(state->mutex);
        values[0] = state->uploads;
        values[1] = state->presents;
        values[2] = state->pending;
        values[3] = state->presentFenceAvailable;
        values[4] = state->presentFds.empty() ? -1 : state->presentFds.back();
        values[5] = static_cast<jlong>(state->releaseFds.size());
    }
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_SourceTileGeometryProbe_close(
    JNIEnv*, jobject, jlong handle) {
    auto* holder = reinterpret_cast<std::shared_ptr<ProbeState>*>(static_cast<std::uintptr_t>(handle));
    if (holder == nullptr) return JNI_FALSE;
    const std::shared_ptr<ProbeState> state = *holder;
    if (!waitDrained(state, kDrainMillis)) return JNI_FALSE;
    if (!beginOrAwaitRemoval(state)) return JNI_FALSE;
    if (!awaitReleaseFences(state)) return JNI_FALSE;
    closeStoredFds(state);
    releaseControls(state);
    delete holder;
    return JNI_TRUE;
}
#endif
