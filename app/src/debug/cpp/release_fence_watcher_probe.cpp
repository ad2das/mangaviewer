#ifndef NDEBUG
#include "release_fence_watcher.h"
#include <android/looper.h>
#include <jni.h>
#include <unistd.h>
#include <atomic>

// Exercises the production ReleaseFenceWatcher on the caller thread. The test drives it from a
// HandlerThread so registration/dispatch ride a real Java Looper (ALooper_forThread non-null)
// rather than a native ALooper_prepare loop; the wake itself is delivered by that Looper's own
// poll, with no nested ALooper_poll* inside production or probe code.
namespace {
ReleaseFenceWatcher watcher;
std::atomic<int> wakes{0}, inputs{0}, faults{0}, wakeTid{0};

void probeWake(void*, int events) noexcept {
    if ((events & ALOOPER_EVENT_INPUT) != 0) inputs.fetch_add(1);
    if ((events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP | ALOOPER_EVENT_INVALID)) != 0) faults.fetch_add(1);
    wakeTid.store(static_cast<int>(::gettid()));
    wakes.fetch_add(1);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_hasLooper(JNIEnv*, jobject) {
    return ALooper_forThread() != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_currentTid(JNIEnv*, jobject) {
    return static_cast<jint>(::gettid());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_arm(JNIEnv*, jobject, jint fd) {
    wakes.store(0); inputs.store(0); faults.store(0); wakeTid.store(0);
    return watcher.arm(fd, &probeWake, nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_disarm(JNIEnv*, jobject) {
    return watcher.disarm() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_armed(JNIEnv*, jobject) {
    return watcher.armed() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_wakeCount(JNIEnv*, jobject) {
    return wakes.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_wakeInputCount(JNIEnv*, jobject) {
    return inputs.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_wakeFaultCount(JNIEnv*, jobject) {
    return faults.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_viewer_runtime_ReleaseFenceWatcherProbe_wakeTid(JNIEnv*, jobject) {
    return wakeTid.load();
}
#endif
