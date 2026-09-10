#include "gl_presentation_callback.h"

namespace {
class ScopedEnvironment final {
public:
    explicit ScopedEnvironment(JavaVM* vm) noexcept : vm_(vm) {
        if (!vm_) return;
        const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (status == JNI_OK) return;
        env_ = nullptr;
        if (status == JNI_EDETACHED && vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) attached_ = true;
    }
    ~ScopedEnvironment() { if (attached_) vm_->DetachCurrentThread(); }
    JNIEnv* get() const noexcept { return env_; }
private:
    JavaVM* vm_;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};
}

GlPresentationCallback::GlPresentationCallback(JNIEnv* env, jobject callback) noexcept {
    if (env == nullptr || callback == nullptr || env->GetJavaVM(&vm_) != JNI_OK) return;
    callback_ = env->NewGlobalRef(callback);
    jclass type = env->GetObjectClass(callback);
    if (type != nullptr) {
        presentedMethod_ = env->GetMethodID(type, "onFramePresented", "(JJIJ)V");
        completionMethod_ = env->GetMethodID(type, "onCompletionPending", "()V");
        env->DeleteLocalRef(type);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

GlPresentationCallback::~GlPresentationCallback() {
    const ScopedEnvironment environment(vm_);
    JNIEnv* env = environment.get();
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
    const ScopedEnvironment environment(vm_);
    JNIEnv* env = environment.get();
    if (env == nullptr || !valid()) return;
    env->CallVoidMethod(
        callback_, presentedMethod_, static_cast<jlong>(token), static_cast<jlong>(atNanos),
        static_cast<jint>(kind), static_cast<jlong>(frameId));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void GlPresentationCallback::completionPending() noexcept {
    const ScopedEnvironment environment(vm_);
    JNIEnv* env = environment.get();
    if (env == nullptr || callback_ == nullptr || completionMethod_ == nullptr) return;
    env->CallVoidMethod(callback_, completionMethod_);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
