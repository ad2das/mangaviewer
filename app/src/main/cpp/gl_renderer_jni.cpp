#include <android/native_window_jni.h>
#include <jni.h>

#include <cstdint>
#include <memory>
#include <new>
#include <vector>

#include "gl_viewer_renderer.h"

namespace {

constexpr int kEntryStride = 7;
constexpr int kMaximumEntries = 128;

std::uint64_t combine(jint low, jint high) noexcept {
    return static_cast<std::uint64_t>(static_cast<std::uint32_t>(low)) |
        (static_cast<std::uint64_t>(static_cast<std::uint32_t>(high)) << 32U);
}

GlViewerRenderer* renderer(jlong handle) noexcept {
    return reinterpret_cast<GlViewerRenderer*>(static_cast<std::uintptr_t>(handle));
}

bool parseEntries(
    JNIEnv* env,
    jint count,
    jintArray packed,
    std::vector<GlSceneEntry>* output) noexcept {
    if (env == nullptr || packed == nullptr || output == nullptr || count < 0 ||
        count > kMaximumEntries || env->GetArrayLength(packed) < count * kEntryStride) return false;
    jint* values = count == 0 ? nullptr : env->GetIntArrayElements(packed, nullptr);
    if (count > 0 && values == nullptr) return false;
    output->clear();
    output->reserve(static_cast<std::size_t>(count));
    for (int index = 0; index < count; ++index) {
        const int at = index * kEntryStride;
        output->push_back(GlSceneEntry{
            combine(values[at], values[at + 1]),
            values[at + 2], values[at + 3], values[at + 4],
            values[at + 5], values[at + 6],
        });
    }
    if (values != nullptr) env->ReleaseIntArrayElements(packed, values, JNI_ABORT);
    return true;
}

int submitScene(JNIEnv* env, jlong handle, jlong token, jint width, jint height, jint viewportTop,
                jfloat rate, jlong sceneKey, jint count, jintArray packed, jint units) {
    GlViewerRenderer* value = renderer(handle);
    if (value == nullptr || env == nullptr) return -1;
    std::vector<GlSceneEntry> entries;
    const bool installs = packed != nullptr;
    if (installs && !parseEntries(env, count, packed, &entries)) return -1;
    const GlViewerFrame frame{token, width, height, viewportTop, rate, sceneKey,
                              installs ? &entries : nullptr, units};
    return value->submit(frame);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject callback) {
    auto value = std::unique_ptr<GlViewerRenderer>(
        new (std::nothrow) GlViewerRenderer(env, callback));
    if (value == nullptr || !value->valid()) return 0;
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(value.release()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeAttach(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface) {
    GlViewerRenderer* value = renderer(handle);
    if (value == nullptr || env == nullptr || surface == nullptr) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    const bool attached = value->attach(window);
    ANativeWindow_release(window);
    return attached ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeDetach(
    JNIEnv*,
    jobject,
    jlong handle) {
    GlViewerRenderer* value = renderer(handle);
    if (value != nullptr) value->detach();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeContextLost(
    JNIEnv*, jobject, jlong handle) {
    const auto* value = renderer(handle);
    return value != nullptr && value->contextLost() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeRecreateContext(
    JNIEnv*, jobject, jlong handle) {
    auto* value = renderer(handle);
    return value != nullptr && value->recreateContext() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeInjectGlContextLossForVerification(
    JNIEnv*, jobject, jlong handle) {
    auto* value = renderer(handle);
    if (value != nullptr) value->injectGlContextLossForVerification();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSetStaticQuadForVerification(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* value = renderer(handle);
    return value != nullptr && value->setStaticQuadForVerification(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSetDirectTextureUploadForVerification(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* value = renderer(handle);
    return value != nullptr &&
            value->setDirectTextureUploadForVerification(enabled == JNI_TRUE)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeUpload(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong cpuTile,
    jint width,
    jint height,
    jint sourceTop,
    jint sourceBottom,
    jint sourceHeight) {
    GlViewerRenderer* value = renderer(handle);
    return value == nullptr ? 0 : static_cast<jlong>(value->upload(
        static_cast<std::uint64_t>(cpuTile), width, height,
        sourceTop, sourceBottom, sourceHeight));
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeReleaseTexture(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong textureKey) {
    GlViewerRenderer* value = renderer(handle);
    if (value != nullptr) value->release(static_cast<std::uint64_t>(textureKey));
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSubmit(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong token,
    jint surfaceWidth,
    jint surfaceHeight,
    jint,
    jint viewportTop,
    jlong,
    jfloat frameRate,
    jlong sceneKey,
    jint count,
    jintArray packedScene) {
    return submitScene(env, handle, token, surfaceWidth, surfaceHeight, viewportTop,
                       frameRate, sceneKey, count, packedScene, 1);
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSubmitEngine(
    JNIEnv* env, jobject, jlong handle, jlong token, jint width, jint height, jfloat rate,
    jlong sceneKey, jint count, jintArray packed, jint units) {
    return submitScene(env, handle, token, width, height, 0, rate, sceneKey, count, packed, units);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativePollPresentations(
    JNIEnv*,
    jobject,
    jlong handle) {
    GlViewerRenderer* value = renderer(handle);
    if (value != nullptr) value->pollPresentations();
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    delete renderer(handle);
}
