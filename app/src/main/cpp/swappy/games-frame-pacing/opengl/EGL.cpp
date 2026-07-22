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

#include "EGL.h"

#include <Trace.h>
#include <dlfcn.h>

#include <vector>

#define LOG_TAG "Swappy::EGL"

#include "SwappyLog.h"

using namespace std::chrono_literals;

namespace swappy {

std::unique_ptr<EGL> EGL::create(std::chrono::nanoseconds fenceTimeout) {
    auto eglLib = dlopen("libEGL.so", RTLD_LAZY | RTLD_LOCAL);
    if (eglLib == nullptr) {
        SWAPPY_LOGE("Can't load libEGL");
        return nullptr;
    }
    auto eglGetProcAddress = reinterpret_cast<eglGetProcAddress_type>(
        dlsym(eglLib, "eglGetProcAddress"));
    if (eglGetProcAddress == nullptr) {
        SWAPPY_LOGE("Failed to load eglGetProcAddress");
        return nullptr;
    }

    auto eglSwapBuffers =
        reinterpret_cast<eglSwapBuffers_type>(dlsym(eglLib, "eglSwapBuffers"));
    if (eglSwapBuffers == nullptr) {
        SWAPPY_LOGE("Failed to load eglSwapBuffers");
        return nullptr;
    }

    auto eglPresentationTimeANDROID =
        reinterpret_cast<eglPresentationTimeANDROID_type>(
            eglGetProcAddress("eglPresentationTimeANDROID"));
    if (eglPresentationTimeANDROID == nullptr) {
        SWAPPY_LOGE("Failed to load eglPresentationTimeANDROID");
        return nullptr;
    }

    auto eglCreateSyncKHR = reinterpret_cast<eglCreateSyncKHR_type>(
        eglGetProcAddress("eglCreateSyncKHR"));
    if (eglCreateSyncKHR == nullptr) {
        SWAPPY_LOGE("Failed to load eglCreateSyncKHR");
        return nullptr;
    }

    auto eglDestroySyncKHR = reinterpret_cast<eglDestroySyncKHR_type>(
        eglGetProcAddress("eglDestroySyncKHR"));
    if (eglDestroySyncKHR == nullptr) {
        SWAPPY_LOGE("Failed to load eglDestroySyncKHR");
        return nullptr;
    }

    auto eglGetSyncAttribKHR = reinterpret_cast<eglGetSyncAttribKHR_type>(
        eglGetProcAddress("eglGetSyncAttribKHR"));
    if (eglGetSyncAttribKHR == nullptr) {
        SWAPPY_LOGE("Failed to load eglGetSyncAttribKHR");
        return nullptr;
    }

    auto eglClientWaitSyncKHR = reinterpret_cast<eglClientWaitSyncKHR_type>(
        eglGetProcAddress("eglClientWaitSyncKHR"));
    if (eglClientWaitSyncKHR == nullptr) {
        SWAPPY_LOGE("Failed to load eglClientWaitSyncKHR");
        return nullptr;
    }

    auto egl = std::make_unique<EGL>(fenceTimeout, eglGetProcAddress,
                                     ConstructorTag{});
    egl->eglLib = eglLib;
    egl->eglSwapBuffers = eglSwapBuffers;
    egl->eglGetProcAddress = eglGetProcAddress;
    egl->eglPresentationTimeANDROID = eglPresentationTimeANDROID;
    egl->eglCreateSyncKHR = eglCreateSyncKHR;
    egl->eglClientWaitSyncKHR = eglClientWaitSyncKHR;
    egl->eglDestroySyncKHR = eglDestroySyncKHR;
    egl->eglGetSyncAttribKHR = eglGetSyncAttribKHR;

    std::lock_guard<std::mutex> lock(egl->mWaiterThreadContext.lock);
    egl->mWaiterThreadContext.thread =
        Thread([egl = egl.get()]() { egl->waitForFenceThreadMain(); });

    return egl;
}

EGL::~EGL() {
    // Stop the fence waiter thread
    {
        std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);
        mWaiterThreadContext.running = false;
        mWaiterThreadContext.condition.notify_one();
    }

    mWaiterThreadContext.thread.join();

    while (mWaitPendingSyncs.size() > 0) {
        auto sync = mWaitPendingSyncs.front();
        mWaitPendingSyncs.pop_front();
        // There is no need to wait here as the API allows for queueing pending
        // sync for deleting.
        EGLBoolean result = eglDestroySyncKHR(sync.display, sync.fence);
        if (result == EGL_FALSE) {
            SWAPPY_LOGE("Failed to destroy sync fence");
        }
    }
    if (eglLib) {
        dlclose(eglLib);
    }
}

bool EGL::setPresentationTime(EGLDisplay display, EGLSurface surface,
                              std::chrono::steady_clock::time_point time) {
    eglPresentationTimeANDROID(display, surface,
                               time.time_since_epoch().count());
    return EGL_TRUE;
}

bool EGL::insertSyncFence(EGLDisplay display) {
    EGLSyncKHR sync_fence =
        eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, nullptr);

    if (sync_fence != EGL_NO_SYNC_KHR) {
        EGLSync sync = {display, sync_fence};
        // kick off the thread work to wait for the fence and measure its time
        std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);
        mWaitPendingSyncs.push_back(sync);
        mWaiterThreadContext.hasPendingWork = true;
        mWaiterThreadContext.condition.notify_all();
        return true;
    } else {
        SWAPPY_LOGE("Failed to create sync fence");
        return false;
    }
}

bool EGL::lastFrameIsComplete(EGLDisplay display, bool pipelineMode) {
    std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);
    if (pipelineMode) {
        // We are in pipeline mode so we need to check the fence of frame N-1
        return mWaitPendingSyncs.size() < 2;
    }
    // We are not in pipeline mode so we need to check the fence of the current
    // frame. i.e. there are not unsignaled frames
    return mWaitPendingSyncs.empty();
}

void EGL::waitForFenceThreadMain() {
    while (true) {
        bool waitingSyncsEmpty;
        {
            std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);

            mWaiterThreadContext.condition.wait(
                mWaiterThreadContext.lock,
                [&]() REQUIRES(mWaiterThreadContext.lock) {
                    return mWaiterThreadContext.hasPendingWork ||
                           !mWaiterThreadContext.running;
                });

            mWaiterThreadContext.hasPendingWork = false;

            if (!mWaiterThreadContext.running) {
                break;
            }

            waitingSyncsEmpty = mWaitPendingSyncs.empty();
        }

        // No other consumers can empty the syncs while this thread is running,
        // the destructor of EGL waits for this thread to finish before emptying
        // the pending syncs.
        while (!waitingSyncsEmpty) {
            EGLSync sync;
            {
                // Get the latest fence to wait on.
                std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);
                sync = mWaitPendingSyncs.front();
            }

            gamesdk::ScopedTrace tracer("Swappy: GPU frame time");
            const auto startTime = std::chrono::steady_clock::now();

            EGLBoolean result = eglClientWaitSyncKHR(sync.display, sync.fence,
                                                     0, mFenceTimeout.count());
            switch (result) {
                case EGL_FALSE:
                    SWAPPY_LOGE("Failed to wait sync");
                    break;
                case EGL_TIMEOUT_EXPIRED_KHR:
                    SWAPPY_LOGE("Timeout waiting for fence");
                    break;
            }

            mFencePendingTime = std::chrono::steady_clock::now() - startTime;

            {
                std::lock_guard<std::mutex> lock(mWaiterThreadContext.lock);
                mWaitPendingSyncs.pop_front();

                // Once the wait has timed out/succeeded, we can submit it for
                // deletion as the API allows for pending syncs to be queued for
                // deletion.
                result = eglDestroySyncKHR(sync.display, sync.fence);
                if (result == EGL_FALSE) {
                    SWAPPY_LOGE("Failed to destroy sync fence");
                }
                waitingSyncsEmpty = mWaitPendingSyncs.empty();
            }
        }
    }
}

}  // namespace swappy
