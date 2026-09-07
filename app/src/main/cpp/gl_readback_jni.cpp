#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>

#include "gl_viewer_renderer.h"

namespace {

GlViewerRenderer* renderer(jlong handle) noexcept {
    return reinterpret_cast<GlViewerRenderer*>(static_cast<std::uintptr_t>(handle));
}

jlongArray unavailableCounts(JNIEnv* env) {
    if (env == nullptr) return nullptr;
    jlongArray result = env->NewLongArray(5);
    if (result == nullptr) return nullptr;
    const std::array<jlong, 5> values{-1, -1, -1, -1, -1};
    env->SetLongArrayRegion(result, 0, 5, values.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeRequestReadback(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong token,
    jlong sessionId,
    jlong rendererEpoch,
    jlong surfaceEpoch,
    jint top,
    jint bottom) {
    GlViewerRenderer* value = renderer(handle);
    return value != nullptr && value->requestReadback(
        static_cast<std::int64_t>(token), static_cast<std::int64_t>(sessionId),
        static_cast<std::int64_t>(rendererEpoch), static_cast<std::int64_t>(surfaceEpoch),
        top, bottom) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeTakeReadback(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong token) {
    if (env == nullptr) return nullptr;
    GlViewerRenderer* value = renderer(handle);
    if (value == nullptr) return nullptr;
    const std::optional<GlReadbackPacket> packet = value->takeReadback(
        static_cast<std::int64_t>(token));
    if (!packet.has_value() || packet->size > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    const jsize length = static_cast<jsize>(packet->size);
    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) return nullptr;
    if (length > 0) {
        env->SetByteArrayRegion(
            result, 0, length, reinterpret_cast<const jbyte*>(packet->data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_viewer_runtime_OwnedRendererBridge_nativeReadbackCounts(
    JNIEnv* env,
    jobject,
    jlong handle) {
    if (env == nullptr) return nullptr;
    GlViewerRenderer* value = renderer(handle);
    if (value == nullptr) return unavailableCounts(env);
    const std::array<std::int64_t, 5> counts = value->readbackCounts();
    jlongArray result = env->NewLongArray(static_cast<jsize>(counts.size()));
    if (result == nullptr) return nullptr;
    std::array<jlong, 5> values{};
    for (std::size_t index = 0; index < values.size(); ++index) {
        values[index] = static_cast<jlong>(counts[index]);
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}
