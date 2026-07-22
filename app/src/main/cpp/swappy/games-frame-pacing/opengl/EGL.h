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

#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <list>
#include <memory>
#include <mutex>

#include "Thread.h"
#include "../common/FrameCompletionResult.h"

namespace swappy {

class EGL {
   private:
    // Allows construction with std::unique_ptr from a static method, but
    // disallows construction outside of the class since no one else can
    // construct a ConstructorTag
    struct ConstructorTag {};

   public:
    using eglGetProcAddress_type = void (*(*)(const char *))(void);

    explicit EGL(std::chrono::nanoseconds fenceTimeout,
                 eglGetProcAddress_type, ConstructorTag)
        : mFenceTimeout(fenceTimeout) {}
    ~EGL();
    static std::unique_ptr<EGL> create(std::chrono::nanoseconds fenceTimeout);

    bool insertSyncFence(EGLDisplay display);
    bool lastFrameIsComplete(EGLDisplay display, bool pipelineMode);
    bool setPresentationTime(EGLDisplay display, EGLSurface surface,
                             std::chrono::steady_clock::time_point time);
    std::chrono::nanoseconds getFencePendingTime() const {
        return mFencePendingTime.load();
    }

    EGLBoolean swapBuffers(EGLDisplay dpy, EGLSurface surface) {
        mSwapBuffersAttemptCount.fetch_add(1, std::memory_order_acq_rel);
        return this->eglSwapBuffers(dpy, surface);
    }
    std::uint64_t swapBuffersAttemptCountForTesting() const {
        return mSwapBuffersAttemptCount.load(std::memory_order_acquire);
    }

   private:
    void *eglLib = nullptr;
    eglGetProcAddress_type eglGetProcAddress = nullptr;
    using eglSwapBuffers_type = EGLBoolean (*)(EGLDisplay, EGLSurface);
    eglSwapBuffers_type eglSwapBuffers = nullptr;
    using eglPresentationTimeANDROID_type = EGLBoolean (*)(EGLDisplay,
                                                           EGLSurface,
                                                           EGLnsecsANDROID);
    eglPresentationTimeANDROID_type eglPresentationTimeANDROID = nullptr;
    using eglCreateSyncKHR_type = EGLSyncKHR (*)(EGLDisplay, EGLenum,
                                                 const EGLint *);
    eglCreateSyncKHR_type eglCreateSyncKHR = nullptr;
    using eglDestroySyncKHR_type = EGLBoolean (*)(EGLDisplay, EGLSyncKHR);
    eglDestroySyncKHR_type eglDestroySyncKHR = nullptr;
    using eglGetSyncAttribKHR_type = EGLBoolean (*)(EGLDisplay, EGLSyncKHR,
                                                    EGLint, EGLint *);
    eglGetSyncAttribKHR_type eglGetSyncAttribKHR = nullptr;

    using eglClientWaitSyncKHR_type = EGLint (*)(EGLDisplay, EGLSyncKHR,
                                                 EGLint, EGLTimeKHR);
    eglClientWaitSyncKHR_type eglClientWaitSyncKHR = nullptr;

    std::chrono::nanoseconds mFenceTimeout;

    struct EGLSync {
        EGLDisplay display;
        EGLSyncKHR fence;
    };
    std::list<EGLSync> mWaitPendingSyncs;
    std::atomic<std::chrono::nanoseconds> mFencePendingTime{
        std::chrono::nanoseconds::zero()};

    std::atomic<std::uint64_t> mSwapBuffersAttemptCount{0};

    // Thread Context for the thread waiting on EGL fences
    struct WaiterThreadContext {
        Thread thread;
        std::mutex lock;
        std::condition_variable_any condition;
        bool running GUARDED_BY(lock) = true;
        bool hasPendingWork GUARDED_BY(lock) = false;
    };

    WaiterThreadContext mWaiterThreadContext;
    void waitForFenceThreadMain();
};

}  // namespace swappy
