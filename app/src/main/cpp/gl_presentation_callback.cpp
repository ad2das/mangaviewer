#include "gl_presentation_callback.h"

GlPresentationCallback::GlPresentationCallback(JNIEnv* env, jobject callback) noexcept {
    if (env == nullptr || callback == nullptr || env->GetJavaVM(&vm_) != JNI_OK) return;
    callback_ = env->NewGlobalRef(callback);
    jclass type = env->GetObjectClass(callback);
    if (type != nullptr) {
        presentedMethod_ = env->GetMethodID(type, "onFramePresented", "(JJIJ)V");
        env->DeleteLocalRef(type);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

GlPresentationCallback::~GlPresentationCallback() {
    JNIEnv* env = environment();
    if (env != nullptr && callback_ != nullptr) env->DeleteGlobalRef(callback_);
}

bool GlPresentationCallback::valid() const noexcept {
    return vm_ != nullptr && callback_ != nullptr && presentedMethod_ != nullptr;
}

void GlPresentationCallback::presented(
    std::int64_t token,
    std::int64_t atNanos,
    std::int32_t kind,
    std::uint64_t frameId) noexcept {
    JNIEnv* env = environment();
    if (env == nullptr || !valid()) return;
    env->CallVoidMethod(
        callback_, presentedMethod_, static_cast<jlong>(token), static_cast<jlong>(atNanos),
        static_cast<jint>(kind), static_cast<jlong>(frameId));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

JNIEnv* GlPresentationCallback::environment() const noexcept {
    if (vm_ == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status != JNI_EDETACHED || vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    return env;
}
