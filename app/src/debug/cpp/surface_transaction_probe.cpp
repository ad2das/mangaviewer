#ifndef NDEBUG
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <jni.h>
#include <dlfcn.h>
#include <linux/sync_file.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <unistd.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace {
std::atomic<int> callbacks{0};
using BackPressure = void (*)(ASurfaceTransaction*, ASurfaceControl*, bool);
using BufferId = int (*)(const AHardwareBuffer*, uint64_t*);
int64_t now() {
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<int64_t>(time.tv_sec) * 1000000000LL + time.tv_nsec;
}
struct Completion {
    std::mutex mutex;
    std::condition_variable ready;
    bool done = false;
    int64_t received = 0, latch = 0, signal = 0;
    int fenceAvailable = 0, fenceStatus = 0;
};
void completed(void* opaque, ASurfaceTransactionStats* stats) {
    std::unique_ptr<std::shared_ptr<Completion>> holder(
        static_cast<std::shared_ptr<Completion>*>(opaque));
    auto state = *holder;
    const auto received = now();
    const auto latch = ASurfaceTransactionStats_getLatchTime(stats);
    const int fence = ASurfaceTransactionStats_getPresentFenceFd(stats);
    int status = 0;
    int64_t signal = 0;
    if (fence >= 0) {
        pollfd poller{fence, POLLIN, 0};
        status = poll(&poller, 1, 2000);
        sync_file_info info{};
        if (status > 0 && ioctl(fence, SYNC_IOC_FILE_INFO, &info) == 0 &&
            info.num_fences > 0 && info.num_fences <= 64) {
            std::vector<sync_fence_info> fences(info.num_fences);
            info.sync_fence_info = reinterpret_cast<uintptr_t>(fences.data());
            if (ioctl(fence, SYNC_IOC_FILE_INFO, &info) == 0 && info.status == 1) {
                bool valid = true;
                for (const auto& item : fences) {
                    valid = valid && item.status == 1 && item.timestamp_ns > 0;
                    signal = std::max(signal, static_cast<int64_t>(item.timestamp_ns));
                }
                if (!valid) signal = 0;
            }
        }
        close(fence);
    }
    {
        std::lock_guard lock(state->mutex);
        state->received = received;
        state->latch = latch;
        state->signal = signal;
        state->fenceAvailable = fence >= 0;
        state->fenceStatus = status;
        state->done = true;
        callbacks.fetch_sub(1);
    }
    state->ready.notify_all();
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_SurfaceTransactionProbe_run(
    JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (surface == nullptr || width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;
    auto pressure = reinterpret_cast<BackPressure>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setEnableBackPressure"));
    auto bufferId = reinterpret_cast<BufferId>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
    if (pressure == nullptr || bufferId == nullptr) return nullptr;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return nullptr;
    ASurfaceControl* control = ASurfaceControl_createFromWindow(window, "EngineSurfaceTransactionProbe");
    ANativeWindow_release(window);
    if (control == nullptr) return nullptr;
    std::vector<jlong> output;
    for (int frame = 1; frame <= 3; ++frame) {
        AHardwareBuffer_Desc desc{};
        desc.width = width; desc.height = height; desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        AHardwareBuffer* buffer = nullptr;
        if (AHardwareBuffer_allocate(&desc, &buffer) != 0) break;
        AHardwareBuffer_describe(buffer, &desc);
        void* pixels = nullptr;
        if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels) != 0) {
            AHardwareBuffer_release(buffer); break;
        }
        for (int y = 0; y < height; ++y) {
            auto row = static_cast<uint32_t*>(pixels) + static_cast<size_t>(y) * desc.stride;
            for (int x = 0; x < width; ++x) row[x] = 0xff000000U | (frame * 70U << 16) |
                (static_cast<uint32_t>(y & 255) << 8) | static_cast<uint32_t>(x & 255);
        }
        int acquire = -1;
        if (AHardwareBuffer_unlock(buffer, &acquire) != 0) {
            if (acquire >= 0) close(acquire);
            AHardwareBuffer_release(buffer); break;
        }
        uint64_t id = 0;
        if (bufferId(buffer, &id) != 0) {
            if (acquire >= 0) close(acquire);
            AHardwareBuffer_release(buffer); break;
        }
        auto state = std::make_shared<Completion>();
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        pressure(transaction, control, true);
        ASurfaceTransaction_setVisibility(transaction, control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setZOrder(transaction, control, 1);
        ASurfaceTransaction_setBuffer(transaction, control, buffer, acquire);
        callbacks.fetch_add(1);
        ASurfaceTransaction_setOnComplete(transaction, new std::shared_ptr<Completion>(state), completed);
        const auto applied = now();
        ASurfaceTransaction_apply(transaction);
        const auto returned = now();
        ASurfaceTransaction_delete(transaction);
        AHardwareBuffer_release(buffer);
        std::unique_lock lock(state->mutex);
        const bool done = state->ready.wait_for(lock, std::chrono::seconds(5), [&] { return state->done; });
        output.insert(output.end(), {frame, static_cast<jlong>(id), applied, returned,
            state->received, state->latch, state->fenceAvailable, state->fenceStatus, state->signal, done ? 1 : 0});
        if (!done) break;
    }
    ASurfaceControl_release(control);
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_SurfaceTransactionProbe_pendingCallbacks(JNIEnv*, jobject) {
    return callbacks.load();
}
#endif
