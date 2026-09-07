#include <jni.h>
#include <cstdint>
#include "gl_viewer_renderer.h"

namespace {
GlViewerRenderer* renderer(jlong handle) noexcept {
    return reinterpret_cast<GlViewerRenderer*>(static_cast<std::uintptr_t>(handle));
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSetSwapIntervalForVerification(
    JNIEnv*, jobject, jlong handle, jint interval) {
    auto* value = renderer(handle);
    return value != nullptr && value->setSwapIntervalForVerification(interval) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeRasterizationInfoForVerification(
    JNIEnv* env, jobject, jlong handle) {
    auto* value = renderer(handle);
    int values[3]{};
    if (value == nullptr || !value->rasterizationInfoForVerification(values)) return nullptr;
    auto result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeSetTextureBudget(
    JNIEnv*, jobject, jlong handle, jlong bytes) {
    auto* value = renderer(handle);
    return value != nullptr && value->setTextureBudget(bytes) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeClearScene(
    JNIEnv*, jobject, jlong handle) {
    auto* value = renderer(handle);
    return value != nullptr && value->clearScene() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeHasTexture(
    JNIEnv*, jobject, jlong handle, jlong texture) {
    auto* value = renderer(handle);
    return value != nullptr && value->hasTexture(static_cast<std::uint64_t>(texture)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeTextureCounts(
    JNIEnv* env, jobject, jlong handle) {
    auto* value = renderer(handle);
    if (value == nullptr || env == nullptr) return nullptr;
    const auto counts = value->textureCounts();
    auto result = env->NewLongArray(static_cast<jsize>(counts.size()));
    if (result == nullptr) return nullptr;
    std::array<jlong, 5> values{};
    for (std::size_t index = 0; index < values.size(); ++index) values[index] = counts[index];
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}
