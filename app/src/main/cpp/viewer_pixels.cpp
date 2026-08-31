#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/imagedecoder.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <poll.h>
#include <unistd.h>

namespace {

struct PixelTile {
    std::uint64_t id = 0;
    std::uint32_t references = 0;
    bool registered = false;
    PixelTile* registryNext = nullptr;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint64_t allocationBytes = 0;
    std::uint32_t contentWidth = 0;
    std::uint32_t contentHeight = 0;
    std::uint32_t sourceWidth = 0;
    std::uint32_t sourceHeight = 0;
    std::uint32_t sourceTop = 0;
    std::uint32_t sourceBottom = 0;
    std::uint64_t contentVersion = 0;
    AHardwareBuffer* buffer = nullptr;
    std::atomic<int> writeFence{-1};
    std::atomic<bool> published{false};
    std::mutex operationMutex;
};

void destroyTile(PixelTile* value) noexcept;
void releaseTileReference(PixelTile* value) noexcept;

class TileLease final {
public:
    TileLease() = default;
    explicit TileLease(PixelTile* value) noexcept : value_(value) {}
    TileLease(const TileLease&) = delete;
    TileLease& operator=(const TileLease&) = delete;
    TileLease(TileLease&& other) noexcept : value_(other.value_) { other.value_ = nullptr; }
    TileLease& operator=(TileLease&& other) noexcept {
        if (this == &other) return *this;
        if (value_ != nullptr) releaseTileReference(value_);
        value_ = other.value_;
        other.value_ = nullptr;
        return *this;
    }
    ~TileLease() { if (value_ != nullptr) releaseTileReference(value_); }

    explicit operator bool() const noexcept { return value_ != nullptr; }
    PixelTile* get() const noexcept { return value_; }
    PixelTile* operator->() const noexcept { return value_; }

private:
    PixelTile* value_ = nullptr;
};

struct HardwareApi {
    using IsSupported = int (*)(const AHardwareBuffer_Desc*);
    using Allocate = int (*)(const AHardwareBuffer_Desc*, AHardwareBuffer**);
    using Acquire = void (*)(AHardwareBuffer*);
    using Release = void (*)(AHardwareBuffer*);
    using Describe = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
    using Lock = int (*)(AHardwareBuffer*, std::uint64_t, std::int32_t, const ARect*, void**);
    using Unlock = int (*)(AHardwareBuffer*, std::int32_t*);

    void* library = nullptr;
    IsSupported isSupported = nullptr;
    Allocate allocate = nullptr;
    Acquire acquire = nullptr;
    Release release = nullptr;
    Describe describe = nullptr;
    Lock lock = nullptr;
    Unlock unlock = nullptr;

    bool valid() const noexcept {
        return allocate != nullptr && acquire != nullptr && release != nullptr && describe != nullptr &&
            lock != nullptr && unlock != nullptr;
    }
};

struct DecoderApi {
    using Create = int (*)(int, AImageDecoder**);
    using Header = const AImageDecoderHeaderInfo* (*)(const AImageDecoder*);
    using Dimension = std::int32_t (*)(const AImageDecoderHeaderInfo*);
    using SetFormat = int (*)(AImageDecoder*, std::int32_t);
    using SetDataSpace = int (*)(AImageDecoder*, std::int32_t);
    using SetTargetSize = int (*)(AImageDecoder*, std::int32_t, std::int32_t);
    using SetCrop = int (*)(AImageDecoder*, ARect);
    using Decode = int (*)(AImageDecoder*, void*, std::size_t, std::size_t);
    using Destroy = void (*)(AImageDecoder*);

    void* library = nullptr;
    Create create = nullptr;
    Header header = nullptr;
    Dimension width = nullptr;
    Dimension height = nullptr;
    SetFormat setFormat = nullptr;
    SetDataSpace setDataSpace = nullptr;
    SetTargetSize setTargetSize = nullptr;
    SetCrop setCrop = nullptr;
    Decode decode = nullptr;
    Destroy destroy = nullptr;

    bool valid() const noexcept {
        return create != nullptr && header != nullptr && width != nullptr && height != nullptr &&
            setFormat != nullptr && setDataSpace != nullptr && setCrop != nullptr &&
            setTargetSize != nullptr && decode != nullptr && destroy != nullptr;
    }
};

template <typename T>
T symbol(void* library, const char* name) noexcept {
    return reinterpret_cast<T>(library != nullptr ? dlsym(library, name) : nullptr);
}

const HardwareApi& hardwareApi() noexcept {
    static const HardwareApi api = [] {
        HardwareApi value{};
        value.library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        value.isSupported = symbol<HardwareApi::IsSupported>(
            value.library, "AHardwareBuffer_isSupported");
        value.allocate = symbol<HardwareApi::Allocate>(value.library, "AHardwareBuffer_allocate");
        value.acquire = symbol<HardwareApi::Acquire>(value.library, "AHardwareBuffer_acquire");
        value.release = symbol<HardwareApi::Release>(value.library, "AHardwareBuffer_release");
        value.describe = symbol<HardwareApi::Describe>(value.library, "AHardwareBuffer_describe");
        value.lock = symbol<HardwareApi::Lock>(value.library, "AHardwareBuffer_lock");
        value.unlock = symbol<HardwareApi::Unlock>(value.library, "AHardwareBuffer_unlock");
        return value;
    }();
    return api;
}

const DecoderApi& decoderApi() noexcept {
    static const DecoderApi api = [] {
        DecoderApi value{};
        value.library = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
        value.create = symbol<DecoderApi::Create>(value.library, "AImageDecoder_createFromFd");
        value.header = symbol<DecoderApi::Header>(value.library, "AImageDecoder_getHeaderInfo");
        value.width = symbol<DecoderApi::Dimension>(
            value.library, "AImageDecoderHeaderInfo_getWidth");
        value.height = symbol<DecoderApi::Dimension>(
            value.library, "AImageDecoderHeaderInfo_getHeight");
        value.setFormat = symbol<DecoderApi::SetFormat>(
            value.library, "AImageDecoder_setAndroidBitmapFormat");
        value.setDataSpace = symbol<DecoderApi::SetDataSpace>(
            value.library, "AImageDecoder_setDataSpace");
        value.setTargetSize = symbol<DecoderApi::SetTargetSize>(
            value.library, "AImageDecoder_setTargetSize");
        value.setCrop = symbol<DecoderApi::SetCrop>(value.library, "AImageDecoder_setCrop");
        value.decode = symbol<DecoderApi::Decode>(value.library, "AImageDecoder_decodeImage");
        value.destroy = symbol<DecoderApi::Destroy>(value.library, "AImageDecoder_delete");
        return value;
    }();
    return api;
}

std::mutex& tileRegistryMutex() noexcept {
    static std::mutex value;
    return value;
}

PixelTile*& tileRegistryHead() noexcept {
    static PixelTile* value = nullptr;
    return value;
}

std::uint64_t& nextTileId() noexcept {
    static std::uint64_t value = 1;
    return value;
}

TileLease retainTile(std::uint64_t handle) noexcept {
    if (handle == 0) return {};
    std::lock_guard<std::mutex> lock(tileRegistryMutex());
    for (auto* value = tileRegistryHead(); value != nullptr; value = value->registryNext) {
        if (value->registered && value->id == handle &&
            value->references < std::numeric_limits<std::uint32_t>::max()) {
            ++value->references;
            return TileLease(value);
        }
    }
    return {};
}

std::uint64_t registerTile(PixelTile* value) noexcept {
    if (value == nullptr) return 0;
    std::lock_guard<std::mutex> lock(tileRegistryMutex());
    auto& next = nextTileId();
    if (next == 0 || next > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        return 0;
    }
    const std::uint64_t id = next++;
    value->id = id;
    value->references = 1;
    value->registered = true;
    value->registryNext = tileRegistryHead();
    tileRegistryHead() = value;
    return id;
}

void unregisterTile(std::uint64_t handle) noexcept {
    if (handle == 0) return;
    PixelTile* destroy = nullptr;
    {
        std::lock_guard<std::mutex> lock(tileRegistryMutex());
        PixelTile** link = &tileRegistryHead();
        while (*link != nullptr && (*link)->id != handle) link = &(*link)->registryNext;
        if (*link == nullptr || !(*link)->registered) return;
        PixelTile* value = *link;
        *link = value->registryNext;
        value->registryNext = nullptr;
        value->registered = false;
        if (--value->references == 0) destroy = value;
    }
    if (destroy != nullptr) destroyTile(destroy);
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

bool consumeFence(PixelTile* value) noexcept {
    const int fd = value->writeFence.exchange(-1, std::memory_order_acq_rel);
    if (fd < 0) return true;
    const bool signaled = waitFence(fd);
    close(fd);
    return signaled;
}

void destroyTile(PixelTile* value) noexcept {
    if (value == nullptr) return;
    (void)consumeFence(value);
    if (value->buffer != nullptr) hardwareApi().release(value->buffer);
    delete value;
}

void releaseTileReference(PixelTile* value) noexcept {
    if (value == nullptr) return;
    bool destroy = false;
    {
        std::lock_guard<std::mutex> lock(tileRegistryMutex());
        if (value->references == 0) return;
        destroy = --value->references == 0;
    }
    if (destroy) destroyTile(value);
}

bool prepareDecoder(
    int fd,
    int expectedWidth,
    int expectedHeight,
    AImageDecoder** decoder) noexcept {
    const auto& api = decoderApi();
    if (!api.valid() || api.create(fd, decoder) != ANDROID_IMAGE_DECODER_SUCCESS ||
        *decoder == nullptr) return false;
    const auto* header = api.header(*decoder);
    return header != nullptr && api.width(header) == expectedWidth &&
        api.height(header) == expectedHeight &&
        api.setFormat(*decoder, ANDROID_BITMAP_FORMAT_RGBA_8888) ==
            ANDROID_IMAGE_DECODER_SUCCESS &&
        api.setDataSpace(*decoder, ADATASPACE_SRGB) == ANDROID_IMAGE_DECODER_SUCCESS;
}

struct DecodeGeometry {
    int scaledFullHeight;
    int scaledTop;
    int scaledBottom;
    int displayHeight;
};

bool projectDecodeGeometry(
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth,
    DecodeGeometry* output) noexcept {
    if (sourceWidth <= 0 || sourceHeight <= 0 || sourceTop < 0 ||
        sourceBottom <= sourceTop || sourceBottom > sourceHeight || displayWidth <= 0 ||
        output == nullptr) return false;
    const std::int64_t fullHeight =
        (static_cast<std::int64_t>(sourceHeight) * displayWidth + sourceWidth - 1LL) /
        sourceWidth;
    if (fullHeight <= 0 || fullHeight > std::numeric_limits<int>::max()) return false;
    output->scaledFullHeight = static_cast<int>(fullHeight);
    output->scaledTop = static_cast<int>(
        static_cast<std::int64_t>(sourceTop) * fullHeight / sourceHeight);
    output->scaledBottom = static_cast<int>(
        static_cast<std::int64_t>(sourceBottom) * fullHeight / sourceHeight);
    output->displayHeight = output->scaledBottom - output->scaledTop;
    return output->displayHeight > 0;
}

bool decodeMappedTarget(
    AImageDecoder* decoder,
    PixelTile* target,
    int displayWidth,
    const DecodeGeometry& geometry,
    int* outputFence) noexcept {
    const auto& api = hardwareApi();
    AHardwareBuffer_Desc description{};
    api.describe(target->buffer, &description);
    if (description.stride < static_cast<std::uint32_t>(displayWidth)) return false;
    const std::size_t rowBytes = static_cast<std::size_t>(description.stride) * 4U;
    if (static_cast<std::size_t>(geometry.displayHeight) >
        std::numeric_limits<std::size_t>::max() / rowBytes) return false;
    void* mapped = nullptr;
    const ARect bounds{0, 0, displayWidth, geometry.displayHeight};
    if (api.lock(target->buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1, &bounds, &mapped) != 0 || mapped == nullptr) return false;
    const std::size_t outputBytes = rowBytes * static_cast<std::size_t>(geometry.displayHeight);
    const bool decoded = decoderApi().decode(decoder, mapped, rowBytes, outputBytes) ==
        ANDROID_IMAGE_DECODER_SUCCESS;
    int fence = -1;
    const bool unlocked = api.unlock(target->buffer, &fence) == 0;
    if (!decoded || !unlocked) {
        if (fence >= 0) close(fence);
        return false;
    }
    *outputFence = fence;
    return true;
}

void publishDecodedTile(
    PixelTile* target,
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth,
    int displayHeight,
    int fence,
    std::uint64_t contentVersion) noexcept {
    target->writeFence.store(fence, std::memory_order_release);
    target->contentWidth = static_cast<std::uint32_t>(displayWidth);
    target->contentHeight = static_cast<std::uint32_t>(displayHeight);
    target->sourceWidth = static_cast<std::uint32_t>(sourceWidth);
    target->sourceHeight = static_cast<std::uint32_t>(sourceHeight);
    target->sourceTop = static_cast<std::uint32_t>(sourceTop);
    target->sourceBottom = static_cast<std::uint32_t>(sourceBottom);
    target->contentVersion = contentVersion;
    target->published.store(true, std::memory_order_release);
}

bool decodeIntoTile(
    AImageDecoder* decoder,
    PixelTile* target,
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth,
    std::uint64_t contentVersion) noexcept {
    const auto& decoderApi = ::decoderApi();
    if (decoder == nullptr || target == nullptr || !hardwareApi().valid() ||
        !decoderApi.valid() || target->buffer == nullptr) return false;
    if (!consumeFence(target)) return false;
    DecodeGeometry geometry{};
    if (!projectDecodeGeometry(
            sourceWidth, sourceHeight, sourceTop, sourceBottom, displayWidth, &geometry) ||
        target->width < static_cast<std::uint32_t>(displayWidth) ||
        target->height < static_cast<std::uint32_t>(geometry.displayHeight)) {
        return false;
    }
    if (decoderApi.setTargetSize(decoder, displayWidth, geometry.scaledFullHeight) !=
            ANDROID_IMAGE_DECODER_SUCCESS ||
        decoderApi.setCrop(decoder, ARect{
            0, geometry.scaledTop, displayWidth, geometry.scaledBottom}) !=
            ANDROID_IMAGE_DECODER_SUCCESS) {
        return false;
    }
    int fence = -1;
    if (!decodeMappedTarget(decoder, target, displayWidth, geometry, &fence)) return false;
    publishDecodedTile(
        target, sourceWidth, sourceHeight, sourceTop, sourceBottom, displayWidth,
        geometry.displayHeight, fence, contentVersion);
    return true;
}

bool decodeBand(
    const char* path,
    const TileLease& target,
    std::uint64_t contentVersion,
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth) noexcept {
    if (!target || contentVersion == 0) return false;
    std::lock_guard<std::mutex> operationLock(target->operationMutex);
    target->published.store(false, std::memory_order_release);
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    AImageDecoder* decoder = nullptr;
    bool valid = prepareDecoder(fd, sourceWidth, sourceHeight, &decoder);
    const auto& api = decoderApi();
    valid = valid && sourceBottom > sourceTop &&
        decodeIntoTile(
            decoder, target.get(), sourceWidth, sourceHeight, sourceTop, sourceBottom,
            displayWidth, contentVersion);
    if (decoder != nullptr) api.destroy(decoder);
    close(fd);
    return valid;
}

bool validDescriptionRequest(
    const TileLease& value,
    std::uint64_t expectedContentVersion,
    int expectedSourceWidth,
    int expectedSourceHeight,
    int expectedSourceTop,
    int expectedSourceBottom,
    AHardwareBuffer** buffer,
    int* acquireFenceFd,
    std::uint32_t* contentWidth,
    std::uint32_t* contentHeight) noexcept {
    return value && expectedContentVersion != 0 && buffer != nullptr &&
        acquireFenceFd != nullptr && contentWidth != nullptr && contentHeight != nullptr &&
        expectedSourceWidth > 0 && expectedSourceHeight > 0 && expectedSourceTop >= 0 &&
        expectedSourceBottom > expectedSourceTop && expectedSourceBottom <= expectedSourceHeight;
}

bool matchesPublishedTile(
    const PixelTile& value,
    std::uint64_t expectedContentVersion,
    int expectedSourceWidth,
    int expectedSourceHeight,
    int expectedSourceTop,
    int expectedSourceBottom) noexcept {
    return value.published.load(std::memory_order_acquire) && value.buffer != nullptr &&
        value.contentVersion == expectedContentVersion &&
        value.sourceWidth == static_cast<std::uint32_t>(expectedSourceWidth) &&
        value.sourceHeight == static_cast<std::uint32_t>(expectedSourceHeight) &&
        value.sourceTop == static_cast<std::uint32_t>(expectedSourceTop) &&
        value.sourceBottom == static_cast<std::uint32_t>(expectedSourceBottom);
}

bool validDecodeRequest(
    JNIEnv* env,
    jstring encodedPath,
    const TileLease& value,
    jlong contentVersion,
    jint sourceWidth,
    jint sourceHeight,
    jint sourceTop,
    jint sourceBottom,
    jint displayWidth) noexcept {
    return env != nullptr && encodedPath != nullptr && value && contentVersion > 0 &&
        sourceWidth > 0 && sourceHeight > 0 && sourceTop >= 0 &&
        sourceBottom > sourceTop && sourceBottom <= sourceHeight && displayWidth > 0;
}

std::int64_t readPublishedPixelArgb(
    const TileLease& value,
    std::uint64_t expectedContentVersion,
    int x,
    int y) noexcept {
    if (!value || expectedContentVersion == 0 || x < 0 || y < 0) return -1;
    std::lock_guard<std::mutex> operationLock(value->operationMutex);
    if (!value->published.load(std::memory_order_acquire) ||
        value->contentVersion != expectedContentVersion ||
        x >= static_cast<int>(value->contentWidth) ||
        y >= static_cast<int>(value->contentHeight) ||
        !consumeFence(value.get())) return -1;
    const auto& api = hardwareApi();
    AHardwareBuffer_Desc description{};
    api.describe(value->buffer, &description);
    void* mapped = nullptr;
    if (api.lock(value->buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
            -1, nullptr, &mapped) != 0 || mapped == nullptr) return -1;
    const auto* pixel = static_cast<const std::uint8_t*>(mapped) +
        (static_cast<std::size_t>(y) * description.stride + x) * 4U;
    const std::uint64_t argb = (static_cast<std::uint64_t>(pixel[3]) << 24U) |
        (static_cast<std::uint64_t>(pixel[0]) << 16U) |
        (static_cast<std::uint64_t>(pixel[1]) << 8U) | pixel[2];
    int fence = -1;
    if (api.unlock(value->buffer, &fence) != 0) {
        if (fence >= 0) close(fence);
        return -1;
    }
    if (fence >= 0) close(fence);
    return static_cast<std::int64_t>(argb);
}

}  // namespace

extern "C" bool viewerDescribePublishedTile(
    std::uint64_t handle,
    std::uint64_t expectedContentVersion,
    int expectedSourceWidth,
    int expectedSourceHeight,
    int expectedSourceTop,
    int expectedSourceBottom,
    AHardwareBuffer** buffer,
    int* acquireFenceFd,
    std::uint32_t* contentWidth,
    std::uint32_t* contentHeight) noexcept {
    const auto value = retainTile(handle);
    if (!validDescriptionRequest(
            value, expectedContentVersion, expectedSourceWidth, expectedSourceHeight,
            expectedSourceTop, expectedSourceBottom, buffer, acquireFenceFd,
            contentWidth, contentHeight)) {
        return false;
    }
    std::lock_guard<std::mutex> operationLock(value->operationMutex);
    if (!matchesPublishedTile(
            *value.get(), expectedContentVersion, expectedSourceWidth, expectedSourceHeight,
            expectedSourceTop, expectedSourceBottom)) return false;
    const int fence = value->writeFence.load(std::memory_order_acquire);
    const int retainedFence = fence >= 0 ? dup(fence) : -1;
    if (fence >= 0 && retainedFence < 0) return false;
    hardwareApi().acquire(value->buffer);
    *buffer = value->buffer;
    *acquireFenceFd = retainedFence;
    *contentWidth = value->contentWidth;
    *contentHeight = value->contentHeight;
    return true;
}

extern "C" void viewerReleaseDescribedTile(
    AHardwareBuffer* buffer, int acquireFenceFd) noexcept {
    if (acquireFenceFd >= 0) close(acquireFenceFd);
    if (buffer != nullptr) hardwareApi().release(buffer);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativeAllocateTile(
    JNIEnv*, jobject, jint width, jint height) {
    if (width <= 0 || height <= 0) return 0;
    const auto& api = hardwareApi();
    if (!api.valid()) return 0;
    AHardwareBuffer_Desc description{};
    description.width = static_cast<std::uint32_t>(width);
    description.height = static_cast<std::uint32_t>(height);
    description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    description.usage = AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
        AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    if (api.isSupported != nullptr && api.isSupported(&description) != 1) return 0;
    AHardwareBuffer* buffer = nullptr;
    if (api.allocate(&description, &buffer) != 0 || buffer == nullptr) return 0;
    AHardwareBuffer_Desc allocated{};
    api.describe(buffer, &allocated);
    if (allocated.stride == 0 || allocated.height == 0 ||
        allocated.height > std::numeric_limits<std::uint64_t>::max() /
            (static_cast<std::uint64_t>(allocated.stride) * 4ULL)) {
        api.release(buffer);
        return 0;
    }
    auto* value = new (std::nothrow) PixelTile();
    if (value == nullptr) {
        api.release(buffer);
        return 0;
    }
    value->width = allocated.width;
    value->height = allocated.height;
    value->allocationBytes = static_cast<std::uint64_t>(allocated.stride) *
        allocated.height * 4ULL;
    value->buffer = buffer;
    const std::uint64_t id = registerTile(value);
    if (id == 0) destroyTile(value);
    return static_cast<jlong>(id);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativeReleaseTile(
    JNIEnv*, jobject, jlong handle) {
    if (handle > 0) unregisterTile(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativeTileAllocationBytes(
    JNIEnv*, jobject, jlong handle) {
    const auto value = handle > 0
        ? retainTile(static_cast<std::uint64_t>(handle)) : TileLease{};
    if (!value || value->allocationBytes >
            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) return 0;
    return static_cast<jlong>(value->allocationBytes);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativePublishTile(
    JNIEnv*, jobject, jlong handle, jlong contentVersion) {
    const auto value = handle > 0
        ? retainTile(static_cast<std::uint64_t>(handle)) : TileLease{};
    if (!value || contentVersion <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> operationLock(value->operationMutex);
    return value->published.load(std::memory_order_acquire) &&
        value->contentVersion == static_cast<std::uint64_t>(contentVersion)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativeDecodeFileBand(
    JNIEnv* env, jobject, jstring encodedPath, jlong handle,
    jlong contentVersion,
    jint sourceWidth, jint sourceHeight, jint sourceTop, jint sourceBottom,
    jint displayWidth) {
    const auto value = handle > 0
        ? retainTile(static_cast<std::uint64_t>(handle)) : TileLease{};
    if (!validDecodeRequest(
            env, encodedPath, value, contentVersion, sourceWidth, sourceHeight,
            sourceTop, sourceBottom, displayWidth)) {
        return JNI_FALSE;
    }
    const char* rawPath = env->GetStringUTFChars(encodedPath, nullptr);
    if (env->ExceptionCheck() || rawPath == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    const bool decoded = decodeBand(
        rawPath, value, static_cast<std::uint64_t>(contentVersion),
        sourceWidth, sourceHeight, sourceTop, sourceBottom, displayWidth);
    env->ReleaseStringUTFChars(encodedPath, rawPath);
    return decoded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativePublishedTileHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jlong contentVersion) {
    const auto value = handle > 0
        ? retainTile(static_cast<std::uint64_t>(handle)) : TileLease{};
    if (env == nullptr || !value || contentVersion <= 0) return nullptr;
    std::lock_guard<std::mutex> operationLock(value->operationMutex);
    if (!value->published.load(std::memory_order_acquire) ||
        value->contentVersion != static_cast<std::uint64_t>(contentVersion) ||
        value->buffer == nullptr || !consumeFence(value.get())) return nullptr;
    return AHardwareBuffer_toHardwareBuffer(env, value->buffer);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_nativebridge_ViewerNativeBridge_nativeReadPublishedTilePixelArgb(
    JNIEnv*, jobject, jlong handle, jlong contentVersion, jint x, jint y) {
    const auto value = handle > 0
        ? retainTile(static_cast<std::uint64_t>(handle)) : TileLease{};
    if (contentVersion <= 0) return -1;
    return static_cast<jlong>(readPublishedPixelArgb(
        value, static_cast<std::uint64_t>(contentVersion), x, y));
}
