/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "ChoreographerThread"

#include "ChoreographerThread.h"

#include <android/log.h>
#include <android/looper.h>
#include <cerrno>
#include <jni.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <thread>

#include "ChoreographerShim.h"
#include "CpuInfo.h"
#include "JNIUtil.h"
#include "Settings.h"
#include "SwappyLog.h"
#include "Thread.h"
#include "Trace.h"

namespace swappy {

using namespace std::chrono_literals;

namespace {

// Android's main/input lane is already urgent-display for an active NTK gesture.  The
// NDK Choreographer publishes the retirement/JOIN opportunity that wakes the render
// owner, so leaving it at display priority can add scheduler tail between two otherwise
// ready halves of the presentation conjunction.
constexpr int kUrgentDisplayNice = -8;
constexpr int kDisplayNiceFallback = -4;

void requestUrgentDisplayPriority(const char *role) {
    errno = 0;
    const int beforeNice = getpriority(PRIO_PROCESS, 0);
    const int beforeError = errno;
    if (beforeError == 0 && beforeNice <= kUrgentDisplayNice) {
        __android_log_print(
            ANDROID_LOG_INFO, LOG_TAG,
            "thread-priority role=%s requested=%d before=%d effective=%d "
            "setErrno=0 fallbackErrno=0 getErrno=0",
            role, kUrgentDisplayNice, beforeNice, beforeNice);
        return;
    }

    errno = 0;
    const int setResult = setpriority(PRIO_PROCESS, 0, kUrgentDisplayNice);
    const int setError = setResult == 0 ? 0 : errno;
    int fallbackError = 0;
    if (setResult != 0 &&
        (beforeError != 0 || beforeNice > kDisplayNiceFallback)) {
        errno = 0;
        const int fallbackResult =
            setpriority(PRIO_PROCESS, 0, kDisplayNiceFallback);
        fallbackError = fallbackResult == 0 ? 0 : errno;
    }
    errno = 0;
    const int effectiveNice = getpriority(PRIO_PROCESS, 0);
    const int getError = errno;
    const int logPriority = getError == 0 && effectiveNice <= kUrgentDisplayNice
        ? ANDROID_LOG_INFO
        : ANDROID_LOG_WARN;
    // ENABLE_SWAPPY_LOGGING is intentionally off in production; this single startup
    // readback remains available without enabling per-frame Swappy logging.
    __android_log_print(
        logPriority, LOG_TAG,
        "thread-priority role=%s requested=%d effective=%d setResult=%d "
        "before=%d setErrno=%d fallbackErrno=%d getErrno=%d",
        role, kUrgentDisplayNice, effectiveNice, setResult, beforeNice,
        setError, fallbackError, getError);
}

}  // namespace

// AChoreographer is supported from API 24. To allow compilation for minSDK < 24
// and still use AChoreographer for SDK >= 24 we need runtime support to call
// AChoreographer APIs.

using PFN_AChoreographer_getInstance = AChoreographer *(*)();

using PFN_AChoreographer_postFrameCallbackDelayed = void (*)(
    AChoreographer *choreographer, AChoreographer_frameCallback callback,
    void *data, long delayMillis);

using PFN_AChoreographer_postVsyncCallback =
    void (*)(AChoreographer *choreographer,
             AChoreographer_vsyncCallback callback, void *data);

using PFN_AChoreographerFrameCallbackData_getFrameTimeNanos =
    int64_t (*)(const AChoreographerFrameCallbackData *data);

using PFN_AChoreographerFrameCallbackData_getFrameTimelinesLength =
    size_t (*)(const AChoreographerFrameCallbackData *data);

using PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex =
    size_t (*)(const AChoreographerFrameCallbackData *data);

using PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId =
    AVsyncId (*)(const AChoreographerFrameCallbackData *data, size_t index);

using PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos =
    int64_t (*)(const AChoreographerFrameCallbackData *data, size_t index);

using PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos =
    int64_t (*)(const AChoreographerFrameCallbackData *data, size_t index);

using PFN_AChoreographer_registerRefreshRateCallback =
    void (*)(AChoreographer *choreographer,
             AChoreographer_refreshRateCallback callback, void *data);

using PFN_AChoreographer_unregisterRefreshRateCallback =
    void (*)(AChoreographer *choreographer,
             AChoreographer_refreshRateCallback callback, void *data);

// Forward declaration of the native method of Java Choreographer class
extern "C" {

JNIEXPORT void JNICALL
Java_com_google_androidgamesdk_ChoreographerCallback_nOnChoreographer(
    JNIEnv * /*env*/, jobject /*this*/, jlong cookie, jlong /*frameTimeNanos*/);
}

class NDKChoreographerThread : public ChoreographerThread {
   public:
    static constexpr int MIN_SDK_VERSION = 24;

    NDKChoreographerThread(ChoreographerCallback onChoreographer,
                           RefreshRateChangedCallback onRefreshRateChanged);
    ~NDKChoreographerThread() override;

   private:
    void looperThread();
    void scheduleNextFrameCallback() override REQUIRES(mWaitingMutex);

    PFN_AChoreographer_getInstance mAChoreographer_getInstance = nullptr;
    PFN_AChoreographer_postFrameCallbackDelayed
        mAChoreographer_postFrameCallbackDelayed = nullptr;
    PFN_AChoreographer_postVsyncCallback mAChoreographer_postVsyncCallback =
        nullptr;
    PFN_AChoreographerFrameCallbackData_getFrameTimeNanos
        mAChoreographerFrameCallbackData_getFrameTimeNanos = nullptr;
    PFN_AChoreographerFrameCallbackData_getFrameTimelinesLength
        mAChoreographerFrameCallbackData_getFrameTimelinesLength = nullptr;
    PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex
        mAChoreographerFrameCallbackData_getPreferredFrameTimelineIndex =
            nullptr;
    PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId
        mAChoreographerFrameCallbackData_getFrameTimelineVsyncId = nullptr;
    PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos
        mAChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos =
            nullptr;
    PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos
        mAChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos =
            nullptr;
    PFN_AChoreographer_registerRefreshRateCallback
        mAChoreographer_registerRefreshRateCallback = nullptr;
    PFN_AChoreographer_unregisterRefreshRateCallback
        mAChoreographer_unregisterRefreshRateCallback = nullptr;
    void *mLibAndroid = nullptr;
    Thread mThread;
    std::condition_variable mWaitingCondition;
    ALooper *mLooper GUARDED_BY(mWaitingMutex) = nullptr;
    bool mThreadRunning GUARDED_BY(mWaitingMutex) = false;
    AChoreographer *mChoreographer GUARDED_BY(mWaitingMutex) = nullptr;
    RefreshRateChangedCallback mOnRefreshRateChanged;
};

NDKChoreographerThread::NDKChoreographerThread(
    ChoreographerCallback onChoreographer,
    RefreshRateChangedCallback onRefreshRateChanged)
    : ChoreographerThread(onChoreographer),
      mOnRefreshRateChanged(onRefreshRateChanged) {
    mLibAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (mLibAndroid == nullptr) {
        SWAPPY_LOGE("FATAL: cannot open libandroid.so: %s", strerror(errno));
        return;
    }

    mAChoreographer_getInstance =
        reinterpret_cast<PFN_AChoreographer_getInstance>(
            dlsym(mLibAndroid, "AChoreographer_getInstance"));

    mAChoreographer_postFrameCallbackDelayed =
        reinterpret_cast<PFN_AChoreographer_postFrameCallbackDelayed>(
            dlsym(mLibAndroid, "AChoreographer_postFrameCallbackDelayed"));

    mAChoreographer_registerRefreshRateCallback =
        reinterpret_cast<PFN_AChoreographer_registerRefreshRateCallback>(
            dlsym(mLibAndroid, "AChoreographer_registerRefreshRateCallback"));

    mAChoreographer_unregisterRefreshRateCallback =
        reinterpret_cast<PFN_AChoreographer_unregisterRefreshRateCallback>(
            dlsym(mLibAndroid, "AChoreographer_unregisterRefreshRateCallback"));

    mAChoreographer_postVsyncCallback =
        reinterpret_cast<PFN_AChoreographer_postVsyncCallback>(
            dlsym(mLibAndroid, "AChoreographer_postVsyncCallback"));

    mAChoreographerFrameCallbackData_getFrameTimeNanos =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getFrameTimeNanos>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_getFrameTimeNanos"));

    mAChoreographerFrameCallbackData_getFrameTimelinesLength =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getFrameTimelinesLength>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_getFrameTimelinesLength"));

    mAChoreographerFrameCallbackData_getPreferredFrameTimelineIndex =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_"
                  "getPreferredFrameTimelineIndex"));

    mAChoreographerFrameCallbackData_getFrameTimelineVsyncId =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_getFrameTimelineVsyncId"));

    mAChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_"
                  "getFrameTimelineExpectedPresentationTimeNanos"));

    mAChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos =
        reinterpret_cast<
            PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos>(
            dlsym(mLibAndroid,
                  "AChoreographerFrameCallbackData_"
                  "getFrameTimelineDeadlineNanos"));

    if (!mAChoreographer_getInstance ||
        !mAChoreographer_postFrameCallbackDelayed) {
        SWAPPY_LOGE("FATAL: cannot get AChoreographer symbols");
        return;
    }

    if (mAChoreographer_postVsyncCallback) {
        if (!mAChoreographerFrameCallbackData_getFrameTimeNanos ||
            !mAChoreographerFrameCallbackData_getFrameTimelinesLength ||
            !mAChoreographerFrameCallbackData_getPreferredFrameTimelineIndex ||
            !mAChoreographerFrameCallbackData_getFrameTimelineVsyncId ||
            !mAChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos ||
            !mAChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos) {
            SWAPPY_LOGE(
                "FATAL: cannot get mAChoreographer_postVsyncCallback helper "
                "symbols");
            return;
        }
    }

    std::unique_lock<std::mutex> lock(mWaitingMutex);
    // create a new ALooper thread to get Choreographer events
    mThreadRunning = true;
    mThread = Thread([this]() { looperThread(); });

    // Wait for the choreographer to be initialized with 1 second timeout.
    mWaitingCondition.wait_for(lock, 1s, [&]() REQUIRES(mWaitingMutex) {
        return mChoreographer != nullptr;
    });

    if (mChoreographer != nullptr) {
        mInitialized = true;
    }
}

NDKChoreographerThread::~NDKChoreographerThread() {
    SWAPPY_LOGI("Destroying NDKChoreographerThread");
    if (mLibAndroid != nullptr) dlclose(mLibAndroid);
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        if (!mLooper) {
            return;
        }
        ALooper_acquire(mLooper);
        mThreadRunning = false;
        ALooper_wake(mLooper);
    }
    mThread.join();
    ALooper_release(mLooper);
}

void NDKChoreographerThread::looperThread() {
    requestUrgentDisplayPriority("ndk-choreographer");
    int outFd, outEvents;
    void *outData;
    std::lock_guard<std::mutex> lock(mWaitingMutex);

    mLooper = ALooper_prepare(0);
    if (!mLooper) {
        SWAPPY_LOGE("ALooper_prepare failed");
        return;
    }

    mChoreographer = mAChoreographer_getInstance();
    if (!mChoreographer) {
        SWAPPY_LOGE("AChoreographer_getInstance failed");
        return;
    }

    AChoreographer_refreshRateCallback callback = [](int64_t vsyncPeriodNanos,
                                                     void *data) {
        reinterpret_cast<NDKChoreographerThread *>(data)
            ->mOnRefreshRateChanged();
    };

    if (mAChoreographer_registerRefreshRateCallback && mOnRefreshRateChanged) {
        mAChoreographer_registerRefreshRateCallback(mChoreographer, callback,
                                                    this);
    }
    mWaitingCondition.notify_all();

    const char *name = "SwappyChoreographer";

    CpuInfo cpu;
    cpu_set_t cpu_set;
    CPU_ZERO(&cpu_set);
    CPU_SET(0, &cpu_set);

    if (cpu.getNumberOfCpus() > 0) {
        SWAPPY_LOGI("Swappy found %d CPUs [%s].", cpu.getNumberOfCpus(),
                    cpu.getHardware().c_str());
        if (cpu.getNumberOfLittleCores() > 0) {
            cpu_set = cpu.getLittleCoresMask();
        }
    }

    const auto tid = gettid();
    if (Settings::getInstance()->getUseAffinity()) {
        SWAPPY_LOGI("Setting '%s' thread [%d-0x%x] affinity mask to 0x%x.", name,
                    tid, tid, to_mask(cpu_set));
        sched_setaffinity(tid, sizeof(cpu_set), &cpu_set);
    }

    pthread_setname_np(pthread_self(), name);

    while (mThreadRunning) {
        // mutex should be unlocked before sleeping on pollOnce
        mWaitingMutex.unlock();
        ALooper_pollOnce(-1, &outFd, &outEvents, &outData);
        mWaitingMutex.lock();
    }
    if (mAChoreographer_unregisterRefreshRateCallback &&
        mOnRefreshRateChanged) {
        mAChoreographer_unregisterRefreshRateCallback(mChoreographer, callback,
                                                      this);
    }
    SWAPPY_LOGI("Terminating Looper thread");

    return;
}

void NDKChoreographerThread::scheduleNextFrameCallback() {
    if (mAChoreographer_postVsyncCallback) {
        AChoreographer_vsyncCallback frameCallback = [](const AChoreographerFrameCallbackData
                                                            *frameData,
                                                        void *data) {
            auto *me = reinterpret_cast<NDKChoreographerThread *>(data);

            ChoreographerFrameData frame;
            me->stampPhysicalCallbackBoundary(&frame);
            frame.frameTimeNanos =
                me->mAChoreographerFrameCallbackData_getFrameTimeNanos(
                    frameData);
            const auto length =
                me->mAChoreographerFrameCallbackData_getFrameTimelinesLength(
                    frameData);
            const auto idx =
                me->mAChoreographerFrameCallbackData_getPreferredFrameTimelineIndex(
                    frameData);
            if (length == 0 || idx >= length) {
                SWAPPY_LOGE("FATAL: invalid preferred FrameTimeline index");
                frame.frameTimeNanos = 0;
                me->onChoreographer(frame);
                return;
            }
            frame.frameTimelines.reserve(length);
            for (std::uint32_t timelineIndex = 0;
                 timelineIndex < length; ++timelineIndex) {
                FixedFrameTimelineTuple timeline{};
                timeline.vsyncId = static_cast<std::int64_t>(
                    me->mAChoreographerFrameCallbackData_getFrameTimelineVsyncId(
                        frameData, timelineIndex));
                timeline.expectedPresentationNanos =
                    me->mAChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos(
                        frameData, timelineIndex);
                timeline.deadlineNanos =
                    me->mAChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos(
                        frameData, timelineIndex);
                frame.frameTimelines.push_back(timeline);
            }
            frame.frameTimelineVsyncId = static_cast<std::int64_t>(
                me->mAChoreographerFrameCallbackData_getFrameTimelineVsyncId(
                    frameData, idx));
            const auto expectedVsync =
                me->mAChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos(
                    frameData, idx);
            const auto expectedDeadline =
                me->mAChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos(
                    frameData, idx);
            const auto sfToVsyncDelay =
                std::chrono::nanoseconds(expectedVsync - expectedDeadline);
            frame.expectedPresentationTimeNanos = expectedVsync;
            frame.frameTimelineDeadlineNanos = expectedDeadline;
            frame.hasFrameTimeline = expectedVsync > expectedDeadline;
            frame.sfToVsyncDelay = sfToVsyncDelay;

            me->onChoreographer(frame);
        };

        mAChoreographer_postVsyncCallback(mChoreographer, frameCallback, this);
    } else {
        AChoreographer_frameCallback frameCallback = [](long frameTimeNanos,
                                                         void *data) {
            auto *me = reinterpret_cast<NDKChoreographerThread *>(data);
            ChoreographerFrameData frame;
            me->stampPhysicalCallbackBoundary(&frame);
            frame.frameTimeNanos = frameTimeNanos;
            me->onChoreographer(frame);
        };

        mAChoreographer_postFrameCallbackDelayed(mChoreographer, frameCallback,
                                                 this, 1);
    }
}

class JavaChoreographerThread : public ChoreographerThread {
   public:
    JavaChoreographerThread(JavaVM *vm, jobject jactivity,
                            ChoreographerCallback onChoreographer);
    ~JavaChoreographerThread() override;
    static void onChoreographer(jlong cookie, jlong frameTimeNanos);
    void onChoreographer(ChoreographerFrameData frame) override {
        ChoreographerThread::onChoreographer(frame);
    };

   private:
    void scheduleNextFrameCallback() override REQUIRES(mWaitingMutex);

    JavaVM *mJVM;
    jobject mJobj = nullptr;
    jmethodID mJpostFrameCallback = nullptr;
    jmethodID mJterminate = nullptr;
};

JavaChoreographerThread::JavaChoreographerThread(
    JavaVM *vm, jobject jactivity, ChoreographerCallback onChoreographer)
    : ChoreographerThread(onChoreographer), mJVM(vm) {
    if (!vm || !jactivity) {
        return;
    }
    JNIEnv *env;
    mJVM->AttachCurrentThread(&env, nullptr);

    jclass choreographerCallbackClass = gamesdk::loadClass(
        env, jactivity, ChoreographerThread::CT_CLASS,
        (JNINativeMethod *)ChoreographerThread::CTNativeMethods,
        ChoreographerThread::CTNativeMethodsSize);

    if (!choreographerCallbackClass) return;

    jmethodID constructor =
        env->GetMethodID(choreographerCallbackClass, "<init>", "(J)V");

    mJpostFrameCallback = env->GetMethodID(choreographerCallbackClass,
                                           "postFrameCallback", "()V");

    mJterminate =
        env->GetMethodID(choreographerCallbackClass, "terminate", "()V");

    jobject choreographerCallback = env->NewObject(
        choreographerCallbackClass, constructor, reinterpret_cast<jlong>(this));

    mJobj = env->NewGlobalRef(choreographerCallback);

    mInitialized = true;
}

JavaChoreographerThread::~JavaChoreographerThread() {
    SWAPPY_LOGI("Destroying JavaChoreographerThread");

    if (!mJobj) {
        return;
    }

    JNIEnv *env;
    // Check if we need to attach and only detach if we do.
    jint result =
        mJVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_2);
    if (result != JNI_OK) {
        if (result == JNI_EVERSION) {
            result =
                mJVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_1);
        }
        if (result == JNI_EDETACHED) {
            mJVM->AttachCurrentThread(&env, nullptr);
        }
    }
    env->CallVoidMethod(mJobj, mJterminate);
    env->DeleteGlobalRef(mJobj);
    if (result == JNI_EDETACHED) {
        mJVM->DetachCurrentThread();
    }
}

void JavaChoreographerThread::scheduleNextFrameCallback() {
    JNIEnv *env;
    mJVM->AttachCurrentThread(&env, nullptr);
    env->CallVoidMethod(mJobj, mJpostFrameCallback);
}

void JavaChoreographerThread::onChoreographer(jlong cookie,
                                              jlong frameTimeNanos) {
    JavaChoreographerThread *me =
        reinterpret_cast<JavaChoreographerThread *>(cookie);
    ChoreographerFrameData frame;
    me->stampPhysicalCallbackBoundary(&frame);
    frame.frameTimeNanos = frameTimeNanos;
    me->onChoreographer(frame);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_google_androidgamesdk_ChoreographerCallback_nOnChoreographer(
    JNIEnv * /*env*/, jobject /*this*/, jlong cookie,
    jlong frameTimeNanos) {
    JavaChoreographerThread::onChoreographer(cookie, frameTimeNanos);
}

}  // extern "C"

class NoChoreographerThread : public ChoreographerThread {
   public:
    NoChoreographerThread(ChoreographerCallback onChoreographer);
    ~NoChoreographerThread();

   private:
    void postFrameCallbacks() override;
    void scheduleNextFrameCallback() override REQUIRES(mWaitingMutex);
    void looperThread();
    void onSettingsChanged();

    Thread mThread;
    bool mThreadRunning GUARDED_BY(mWaitingMutex);
    std::condition_variable_any mWaitingCondition GUARDED_BY(mWaitingMutex);
    std::chrono::nanoseconds mRefreshPeriod GUARDED_BY(mWaitingMutex);
};

NoChoreographerThread::NoChoreographerThread(
    ChoreographerCallback onChoreographer)
    : ChoreographerThread(onChoreographer) {
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    Settings::getInstance()->addListener([this]() { onSettingsChanged(); });
    mThreadRunning = true;
    mThread = Thread([this]() { looperThread(); });
    mInitialized = true;
}

NoChoreographerThread::~NoChoreographerThread() {
    SWAPPY_LOGI("Destroying NoChoreographerThread");
    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        mThreadRunning = false;
    }
    mWaitingCondition.notify_all();
    mThread.join();
}

void NoChoreographerThread::onSettingsChanged() {
    const Settings::DisplayTimings &displayTimings =
        Settings::getInstance()->getDisplayTimings();
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    mRefreshPeriod = displayTimings.refreshPeriod;
    SWAPPY_LOGV("onSettingsChanged(): refreshPeriod=%lld",
                (long long)displayTimings.refreshPeriod.count());
}

void NoChoreographerThread::looperThread() {
    setpriority(PRIO_PROCESS, 0, -4);
    const char *name = "SwappyChoreographer";

    CpuInfo cpu;
    cpu_set_t cpu_set;
    CPU_ZERO(&cpu_set);
    CPU_SET(0, &cpu_set);

    if (cpu.getNumberOfCpus() > 0) {
        SWAPPY_LOGI("Swappy found %d CPUs [%s].", cpu.getNumberOfCpus(),
                    cpu.getHardware().c_str());
        if (cpu.getNumberOfLittleCores() > 0) {
            cpu_set = cpu.getLittleCoresMask();
        }
    }

    const auto tid = gettid();
    if (Settings::getInstance()->getUseAffinity()) {
        SWAPPY_LOGI("Setting '%s' thread [%d-0x%x] affinity mask to 0x%x.", name,
                    tid, tid, to_mask(cpu_set));
        sched_setaffinity(tid, sizeof(cpu_set), &cpu_set);
    }

    pthread_setname_np(pthread_self(), name);

    auto wakeTime = std::chrono::steady_clock::now();

    while (true) {
        {
            // mutex should be unlocked before sleeping
            std::lock_guard<std::mutex> lock(mWaitingMutex);
            if (!mThreadRunning) {
                break;
            }
            mWaitingCondition.wait(mWaitingMutex);
            if (!mThreadRunning) {
                break;
            }

            const auto timePassed = std::chrono::steady_clock::now() - wakeTime;
            const int intervals = std::floor(timePassed / mRefreshPeriod);
            wakeTime += (intervals + 1) * mRefreshPeriod;
        }

        std::this_thread::sleep_until(wakeTime);
        ChoreographerFrameData frame;
        stampPhysicalCallbackBoundary(&frame);
        frame.frameTimeNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
            wakeTime.time_since_epoch()).count();
        mCallback(frame);
    }
    SWAPPY_LOGI("Terminating choreographer thread");
}

void NoChoreographerThread::postFrameCallbacks() {
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    mWaitingCondition.notify_one();
}

void NoChoreographerThread::scheduleNextFrameCallback() {}

const char *ChoreographerThread::CT_CLASS =
    "com/google/androidgamesdk/ChoreographerCallback";

const JNINativeMethod ChoreographerThread::CTNativeMethods[] = {
    {"nOnChoreographer", "(JJ)V",
     (void
          *)&Java_com_google_androidgamesdk_ChoreographerCallback_nOnChoreographer}};

ChoreographerThread::ChoreographerThread(ChoreographerCallback onChoreographer)
    : mCallback(onChoreographer) {}

ChoreographerThread::~ChoreographerThread() = default;

void ChoreographerThread::postFrameCallbacks() {
    TRACE_CALL();

    // This method is called before calling to swap buffers
    // It registers to get MAX_CALLBACKS_BEFORE_IDLE frame callbacks before
    // going idle so if app goes to idle the thread will not get further frame
    // callbacks
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    if (mFixedDemandMode) return;
    if (mCallbacksBeforeIdle == 0 && !mPhysicalFrameCallbackPosted) {
        scheduleNextFrameCallback();
        mPhysicalFrameCallbackPosted = true;
        ++mFixedDemandLedger.physicalPosts;
    }
    mCallbacksBeforeIdle = MAX_CALLBACKS_BEFORE_IDLE;
}

void ChoreographerThread::enterFixedDemandModeForNtk() {
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    mFixedDemandMode = true;
    mCallbacksBeforeIdle = 0;
}

FixedDemandLedgerSnapshot
ChoreographerThread::fixedDemandLedgerSnapshotLocked() const {
    FixedDemandLedgerSnapshot result = mFixedDemandLedger;
    result.pendingMask = mFixedPendingDemandMask;
    result.inFlightMask = mFixedInFlightDemandMask;
    return result;
}

bool ChoreographerThread::fixedDemandLedgerConserved(
    const FixedDemandLedgerSnapshot& snapshot) {
    const std::uint8_t outstanding = static_cast<std::uint8_t>(
        snapshot.pendingMask | snapshot.inFlightMask);
    const std::uint64_t retirementOutstanding =
        (outstanding & FIXED_DEMAND_RETIREMENT) != 0 ? 1U : 0U;
    const std::uint64_t opportunityOutstanding =
        (outstanding & FIXED_DEMAND_OPPORTUNITY) != 0 ? 1U : 0U;
    return snapshot.retirementIssued == snapshot.retirementSatisfied +
               snapshot.retirementCancelled + retirementOutstanding &&
        snapshot.opportunityIssued == snapshot.opportunitySatisfied +
               snapshot.opportunityCancelled + opportunityOutstanding &&
        snapshot.physicalPosts >= snapshot.physicalCallbacksDelivered &&
        snapshot.physicalPosts - snapshot.physicalCallbacksDelivered <= 1;
}

FixedDemandMutationResult ChoreographerThread::requestFixedFrameCallbackForNtk(
    FixedCallbackDemand demand) {
    TRACE_CALL();

    FixedDemandMutationResult result{};
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    if (demand == FIXED_DEMAND_NONE) {
        result.ledgerAfter = fixedDemandLedgerSnapshotLocked();
        result.outstandingMask = static_cast<std::uint8_t>(
            result.ledgerAfter.pendingMask | result.ledgerAfter.inFlightMask);
        result.mutationCompleteNanos = std::chrono::duration_cast<
            std::chrono::nanoseconds>(std::chrono::steady_clock::now()
                .time_since_epoch()).count();
        result.accepted = fixedDemandLedgerConserved(result.ledgerAfter);
        return result;
    }
    const std::uint8_t bit = static_cast<std::uint8_t>(demand);
    if ((mFixedPendingDemandMask & bit) == 0 &&
        (mFixedInFlightDemandMask & bit) == 0) {
        mFixedPendingDemandMask = static_cast<std::uint8_t>(
            mFixedPendingDemandMask | bit);
        if (bit & FIXED_DEMAND_RETIREMENT) {
            ++mFixedDemandLedger.retirementIssued;
        }
        if (bit & FIXED_DEMAND_OPPORTUNITY) {
            ++mFixedDemandLedger.opportunityIssued;
        }
    }
    if (!mPhysicalFrameCallbackPosted) {
        scheduleNextFrameCallback();
        mPhysicalFrameCallbackPosted = true;
        ++mFixedDemandLedger.physicalPosts;
    }
    result.ledgerAfter = fixedDemandLedgerSnapshotLocked();
    result.outstandingMask = static_cast<std::uint8_t>(
        result.ledgerAfter.pendingMask | result.ledgerAfter.inFlightMask);
    result.mutationCompleteNanos = std::chrono::duration_cast<
        std::chrono::nanoseconds>(std::chrono::steady_clock::now()
            .time_since_epoch()).count();
    result.accepted = fixedDemandLedgerConserved(result.ledgerAfter);
    return result;
}

void ChoreographerThread::onChoreographer(ChoreographerFrameData frame) {
    TRACE_CALL();

    {
        std::lock_guard<std::mutex> lock(mWaitingMutex);
        ++mFixedDemandLedger.physicalCallbacksDelivered;
        mActivePhysicalCallbackSequence = frame.physicalCallbackSequence;
        mFixedInFlightDemandMask = mFixedPendingDemandMask;
        mFixedPendingDemandMask = FIXED_DEMAND_NONE;
        frame.deliveredFixedDemandMask = mFixedInFlightDemandMask;
    }
    mCallback(frame);
    // Fixed inline Common completes before publishing the renderer wake.  This
    // fallback only handles stock/adaptive callbacks or a rejected authority;
    // it preserves every unsatisfied reason instead of clearing the mask.
    completeFixedFrameCallbackForNtk(
        frame.physicalCallbackSequence, frame.deliveredFixedDemandMask,
        FIXED_DEMAND_NONE);
}

void ChoreographerThread::stampPhysicalCallbackBoundary(
    ChoreographerFrameData* frame) noexcept {
    if (!frame) return;
    frame->physicalCallbackReceiptNanos =
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    frame->physicalCallbackSequence =
        mNextPhysicalCallbackSequence.fetch_add(1, std::memory_order_relaxed) + 1;
}

FixedDemandMutationResult ChoreographerThread::completeFixedFrameCallbackForNtk(
    std::uint64_t callbackSequence, std::uint8_t deliveredMask,
    std::uint8_t satisfiedMask) {
    FixedDemandMutationResult result{};
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    result.deliveredMask = deliveredMask;
    if (callbackSequence == 0 ||
        callbackSequence != mActivePhysicalCallbackSequence ||
        deliveredMask != mFixedInFlightDemandMask) {
        result.ledgerAfter = fixedDemandLedgerSnapshotLocked();
        result.outstandingMask = static_cast<std::uint8_t>(
            result.ledgerAfter.pendingMask | result.ledgerAfter.inFlightMask);
        result.mutationCompleteNanos = std::chrono::duration_cast<
            std::chrono::nanoseconds>(std::chrono::steady_clock::now()
                .time_since_epoch()).count();
        return result;
    }
    satisfiedMask = static_cast<std::uint8_t>(satisfiedMask & deliveredMask);
    result.satisfiedMask = satisfiedMask;
    const std::uint8_t unsatisfied = static_cast<std::uint8_t>(
        deliveredMask & static_cast<std::uint8_t>(~satisfiedMask));
    if (satisfiedMask & FIXED_DEMAND_RETIREMENT) {
        ++mFixedDemandLedger.retirementSatisfied;
    }
    if (satisfiedMask & FIXED_DEMAND_OPPORTUNITY) {
        ++mFixedDemandLedger.opportunitySatisfied;
    }
    mFixedPendingDemandMask = static_cast<std::uint8_t>(
        mFixedPendingDemandMask | unsatisfied);
    mFixedInFlightDemandMask = FIXED_DEMAND_NONE;
    mActivePhysicalCallbackSequence = 0;
    mPhysicalFrameCallbackPosted = false;
    if (mCallbacksBeforeIdle > 0) --mCallbacksBeforeIdle;
    if ((mCallbacksBeforeIdle > 0 ||
         mFixedPendingDemandMask != FIXED_DEMAND_NONE) &&
        !mPhysicalFrameCallbackPosted) {
        scheduleNextFrameCallback();
        mPhysicalFrameCallbackPosted = true;
        ++mFixedDemandLedger.physicalPosts;
    }
    result.ledgerAfter = fixedDemandLedgerSnapshotLocked();
    result.outstandingMask = static_cast<std::uint8_t>(
        result.ledgerAfter.pendingMask | result.ledgerAfter.inFlightMask);
    result.mutationCompleteNanos = std::chrono::duration_cast<
        std::chrono::nanoseconds>(std::chrono::steady_clock::now()
            .time_since_epoch()).count();
    result.accepted = fixedDemandLedgerConserved(result.ledgerAfter);
    return result;
}

FixedDemandMutationResult ChoreographerThread::cancelFixedFrameDemandForNtk(
    std::uint8_t mask) {
    FixedDemandMutationResult result{};
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    const std::uint8_t outstanding = static_cast<std::uint8_t>(
        mask & static_cast<std::uint8_t>(mFixedPendingDemandMask |
                                        mFixedInFlightDemandMask));
    mFixedPendingDemandMask = static_cast<std::uint8_t>(
        mFixedPendingDemandMask & static_cast<std::uint8_t>(~outstanding));
    mFixedInFlightDemandMask = static_cast<std::uint8_t>(
        mFixedInFlightDemandMask & static_cast<std::uint8_t>(~outstanding));
    if (outstanding & FIXED_DEMAND_RETIREMENT) {
        ++mFixedDemandLedger.retirementCancelled;
    }
    if (outstanding & FIXED_DEMAND_OPPORTUNITY) {
        ++mFixedDemandLedger.opportunityCancelled;
    }
    result.ledgerAfter = fixedDemandLedgerSnapshotLocked();
    result.outstandingMask = static_cast<std::uint8_t>(
        result.ledgerAfter.pendingMask | result.ledgerAfter.inFlightMask);
    result.mutationCompleteNanos = std::chrono::duration_cast<
        std::chrono::nanoseconds>(std::chrono::steady_clock::now()
            .time_since_epoch()).count();
    result.accepted = fixedDemandLedgerConserved(result.ledgerAfter);
    return result;
}

FixedDemandLedgerSnapshot
ChoreographerThread::getFixedDemandLedgerForNtk() {
    std::lock_guard<std::mutex> lock(mWaitingMutex);
    return fixedDemandLedgerSnapshotLocked();
}

std::unique_ptr<ChoreographerThread>
ChoreographerThread::createChoreographerThread(
    Type type, JavaVM *vm, jobject jactivity,
    ChoreographerCallback onChoreographer,
    RefreshRateChangedCallback onRefreshRateChanged, SdkVersion sdkVersion) {
    if (type == Type::App) {
        SWAPPY_LOGI("Using Application's Choreographer");
        return std::make_unique<NoChoreographerThread>(onChoreographer);
    }

    if (vm == nullptr ||
        sdkVersion.sdkInt >= NDKChoreographerThread::MIN_SDK_VERSION) {
        SWAPPY_LOGI("Using NDK Choreographer");
        const auto usingDisplayManager =
            SwappyDisplayManager::useSwappyDisplayManager(sdkVersion);
        const auto refreshRateCallback = usingDisplayManager
                                             ? RefreshRateChangedCallback()
                                             : onRefreshRateChanged;
        return std::make_unique<NDKChoreographerThread>(onChoreographer,
                                                        refreshRateCallback);
    }

    if (vm != nullptr && jactivity != nullptr) {
        std::unique_ptr<ChoreographerThread> javaChoreographerThread =
            std::make_unique<JavaChoreographerThread>(vm, jactivity,
                                                      onChoreographer);
        if (javaChoreographerThread->isInitialized()) {
            SWAPPY_LOGI("Using Java Choreographer");
            return javaChoreographerThread;
        }
    }

    SWAPPY_LOGI("Using no Choreographer (Best Effort)");
    return std::make_unique<NoChoreographerThread>(onChoreographer);
}

}  // namespace swappy
