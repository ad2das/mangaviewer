#include <android/data_space.h>
#include <android/imagedecoder.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <new>
#include <unistd.h>
#include <vector>

#include <algorithm>

#include "viewer_cpu_tile.h"

namespace {

struct CpuTile final {
    std::vector<std::uint8_t> pixels;
};

class FileDescriptor final {
public:
    explicit FileDescriptor(const char* path) noexcept
        : value_(path == nullptr ? -1 : open(path, O_RDONLY | O_CLOEXEC)) {}
    ~FileDescriptor() { if (value_ >= 0) close(value_); }
    int get() const noexcept { return value_; }

private:
    int value_;
};

class Decoder final {
public:
    explicit Decoder(int fd) noexcept {
        if (fd >= 0 && AImageDecoder_createFromFd(fd, &value_) != ANDROID_IMAGE_DECODER_SUCCESS) {
            value_ = nullptr;
        }
    }
    ~Decoder() { AImageDecoder_delete(value_); }
    AImageDecoder* get() const noexcept { return value_; }

private:
    AImageDecoder* value_ = nullptr;
};

bool sourceMatches(AImageDecoder* decoder, int width, int height) noexcept {
    const AImageDecoderHeaderInfo* header = AImageDecoder_getHeaderInfo(decoder);
    return header != nullptr && AImageDecoderHeaderInfo_getWidth(header) == width &&
        AImageDecoderHeaderInfo_getHeight(header) == height;
}

bool projectedGeometry(
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth,
    int* scaledHeight,
    int* displayTop,
    int* displayBottom) noexcept {
    if (sourceWidth <= 0 || sourceHeight <= 0 || sourceTop < 0 ||
        sourceBottom <= sourceTop || sourceBottom > sourceHeight || displayWidth <= 0) {
        return false;
    }
    const std::int64_t height =
        (static_cast<std::int64_t>(sourceHeight) * displayWidth + sourceWidth - 1LL) /
        sourceWidth;
    if (height <= 0 || height > std::numeric_limits<int>::max()) return false;
    *scaledHeight = static_cast<int>(height);
    *displayTop = static_cast<int>(static_cast<std::int64_t>(sourceTop) * height / sourceHeight);
    *displayBottom = static_cast<int>(
        (static_cast<std::int64_t>(sourceBottom) * height + sourceHeight - 1LL) / sourceHeight);
    *displayBottom = std::min(*displayBottom, *scaledHeight);
    return *displayBottom > *displayTop;
}

std::unique_ptr<CpuTile> decode(
    const char* path,
    int sourceWidth,
    int sourceHeight,
    int sourceTop,
    int sourceBottom,
    int displayWidth) noexcept {
    FileDescriptor file(path);
    Decoder ownedDecoder(file.get());
    AImageDecoder* decoder = ownedDecoder.get();
    if (decoder == nullptr || !sourceMatches(decoder, sourceWidth, sourceHeight)) return nullptr;
    int scaledHeight = 0;
    int displayTop = 0;
    int displayBottom = 0;
    if (!projectedGeometry(
            sourceWidth, sourceHeight, sourceTop, sourceBottom, displayWidth,
            &scaledHeight, &displayTop, &displayBottom)) return nullptr;
    if (AImageDecoder_setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888) !=
            ANDROID_IMAGE_DECODER_SUCCESS ||
        AImageDecoder_setDataSpace(decoder, ADATASPACE_SRGB) != ANDROID_IMAGE_DECODER_SUCCESS ||
        AImageDecoder_setTargetSize(decoder, displayWidth, scaledHeight) !=
            ANDROID_IMAGE_DECODER_SUCCESS ||
        AImageDecoder_setCrop(decoder, ARect{0, displayTop, displayWidth, displayBottom}) !=
            ANDROID_IMAGE_DECODER_SUCCESS) return nullptr;
    const std::size_t rowBytes = static_cast<std::size_t>(displayWidth) * 4U;
    const std::size_t rowCount = static_cast<std::size_t>(displayBottom - displayTop);
    if (rowCount > std::numeric_limits<std::size_t>::max() / rowBytes) return nullptr;
    auto tile = std::unique_ptr<CpuTile>(new (std::nothrow) CpuTile());
    if (tile == nullptr) return nullptr;
    tile->pixels.resize(rowBytes * rowCount);
    if (AImageDecoder_decodeImage(
            decoder, tile->pixels.data(), rowBytes, tile->pixels.size()) !=
        ANDROID_IMAGE_DECODER_SUCCESS) return nullptr;
    return tile;
}

CpuTile* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<CpuTile*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

bool viewerDescribeCpuTile(std::uint64_t handle, ViewerCpuTileView* output) noexcept {
    const CpuTile* tile = reinterpret_cast<const CpuTile*>(static_cast<std::uintptr_t>(handle));
    if (tile == nullptr || output == nullptr || tile->pixels.empty()) return false;
    output->pixels = tile->pixels.data();
    output->byteCount = tile->pixels.size();
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_runtime_NativeCpuDecodeBridge_nativeDecode(
    JNIEnv* env,
    jobject,
    jstring encodedPath,
    jint sourceWidth,
    jint sourceHeight,
    jint sourceTop,
    jint sourceBottom,
    jint displayWidth) {
    if (env == nullptr || encodedPath == nullptr) return 0;
    const char* path = env->GetStringUTFChars(encodedPath, nullptr);
    if (path == nullptr) return 0;
    auto tile = decode(
        path, sourceWidth, sourceHeight, sourceTop, sourceBottom, displayWidth);
    env->ReleaseStringUTFChars(encodedPath, path);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(tile.release()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_runtime_NativeCpuDecodeBridge_nativeByteCount(
    JNIEnv*, jobject, jlong handle) {
    const CpuTile* tile = fromHandle(handle);
    if (tile == nullptr || tile->pixels.size() >
            static_cast<std::size_t>(std::numeric_limits<jlong>::max())) return 0;
    return static_cast<jlong>(tile->pixels.size());
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_NativeCpuDecodeBridge_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}
