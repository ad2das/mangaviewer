#pragma once

#include <jni.h>

#include <cstdint>
#include <memory>

class GlPresentationCallback final {
public:
    GlPresentationCallback(JNIEnv* env, jobject callback) noexcept;
    ~GlPresentationCallback();

    GlPresentationCallback(const GlPresentationCallback&) = delete;
    GlPresentationCallback& operator=(const GlPresentationCallback&) = delete;

    bool valid() const noexcept;
    void presented(std::int64_t token, std::int64_t atNanos, std::int32_t kind = 0,
                   std::uint64_t frameId = 0) noexcept;
    void completionPending() noexcept;

private:
    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID presentedMethod_ = nullptr;
    jmethodID completionMethod_ = nullptr;
};
