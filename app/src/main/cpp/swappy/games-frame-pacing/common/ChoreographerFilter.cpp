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

#include "ChoreographerFilter.h"

#define LOG_TAG "ChoreographerFilter"

#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <deque>
#include <string>
#include <thread>

#include "Settings.h"
#include "SwappyLog.h"
#include "Thread.h"
#include "Trace.h"

using namespace std::chrono_literals;
using time_point = std::chrono::steady_clock::time_point;

namespace {

class Timer {
   public:
    Timer(std::chrono::nanoseconds refreshPeriod,
          std::chrono::nanoseconds appToSfDelay)
        : mRefreshPeriod(refreshPeriod), mAppToSfDelay(appToSfDelay) {}

    // Returns false if we have detected that we have received the same
    // timestamp multiple times so that the caller can wait for fresh timestamps
    bool addTimestamp(time_point point) {
        // Keep track of the previous timestamp and how many times we've seen it
        // to determine if we've stopped receiving Choreographer callbacks,
        // which would indicate that we should probably stop until we see them
        // again (e.g., if the app has been moved to the background)
        if (point == mLastTimestamp) {
            if (mRepeatCount++ > 5) {
                return false;
            }
        } else {
            mRepeatCount = 0;
        }
        mLastTimestamp = point;

        point += mAppToSfDelay;

        bool moreThanOneRefreshPeriodElapsed =
            mBaseTime + mRefreshPeriod * 1.5 < point;
        if (moreThanOneRefreshPeriodElapsed) {
            do {
                mBaseTime += mRefreshPeriod;
            } while (mBaseTime + mRefreshPeriod * 1.5 < point);
            mBaseTime += mRefreshPeriod;
            // Long waits pollute the filter so don't adjust refreshPeriod.
            return true;
        }

        std::chrono::nanoseconds delta = (point - (mBaseTime + mRefreshPeriod));
        if (delta < -mRefreshPeriod / 2) {
            // Also ignore short intervals
            return true;
        }

        // Exponential smoothing factor = 0.04 avoids roughness.
        mRefreshPeriod += delta / 25;
        mBaseTime += mRefreshPeriod;

        return true;
    }

    void sleep(std::chrono::nanoseconds offset) {
        if (offset < -(mRefreshPeriod / 2) || offset > mRefreshPeriod / 2) {
            offset = 0ms;
        }

        const auto now = std::chrono::steady_clock::now();
        auto targetTime = mBaseTime + mRefreshPeriod + offset;
        while (targetTime < now) {
            targetTime += mRefreshPeriod;
        }

        std::this_thread::sleep_until(targetTime);
    }

   private:
    std::chrono::nanoseconds mRefreshPeriod;
    const std::chrono::nanoseconds mAppToSfDelay;
    time_point mBaseTime = std::chrono::steady_clock::now();

    time_point mLastTimestamp = std::chrono::steady_clock::now();
    std::optional<std::chrono::nanoseconds> mSfToVsyncDelay;
    int32_t mRepeatCount = 0;
};

}  // anonymous namespace

namespace swappy {

ChoreographerFilter::ChoreographerFilter(std::chrono::nanoseconds refreshPeriod,
                                         std::chrono::nanoseconds appToSfDelay,
                                         Worker doWork)
    : mRefreshPeriod(refreshPeriod),
      mAppToSfDelay(appToSfDelay),
      mDoWork(doWork) {
    Settings::getInstance()->addListener([this]() { onSettingsChanged(); });

    std::lock_guard<std::mutex> lock(mThreadPoolMutex);
    mUseAffinity = Settings::getInstance()->getUseAffinity();
    launchThreadsLocked();
}

ChoreographerFilter::~ChoreographerFilter() {
    std::lock_guard<std::mutex> lock(mThreadPoolMutex);
    terminateThreadsLocked();
}

void ChoreographerFilter::onChoreographer(ChoreographerFrameData frame) {
    ChoreographerFrameData accepted;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (frame.frameTimeNanos <= 0) {
            SWAPPY_LOGE("FATAL: rejected non-positive Choreographer frameTimeNanos");
            return;
        }
        const std::int64_t futureDeltaNanos =
            frame.physicalCallbackReceiptNanos > 0
            ? frame.frameTimeNanos - frame.physicalCallbackReceiptNanos
            : 0;
        if (frame.physicalCallbackReceiptNanos > 0 &&
            mRefreshPeriod.count() > 0 &&
            futureDeltaNanos > mRefreshPeriod.count()) {
            SWAPPY_LOGE(
                "diagnostic future Choreographer authority frame=%lld "
                "receipt=%lld delta=%lld period=%lld physicalSequence=%llu",
                static_cast<long long>(frame.frameTimeNanos),
                static_cast<long long>(frame.physicalCallbackReceiptNanos),
                static_cast<long long>(futureDeltaNanos),
                static_cast<long long>(mRefreshPeriod.count()),
                static_cast<unsigned long long>(
                    frame.physicalCallbackSequence));
        }
        if (mLastRawFrameTimeNanos > 0 &&
            frame.frameTimeNanos <= mLastRawFrameTimeNanos) {
            // Only strictly newer raw Choreographer authority is accepted.  A
            // duplicate callback must not manufacture a second display-frame index.
            return;
        }

        std::int64_t advance = 1;
        if (mLastRawFrameTimeNanos > 0 && mRefreshPeriod.count() > 0) {
            const std::int64_t delta =
                frame.frameTimeNanos - mLastRawFrameTimeNanos;
            // Reconstruct skipped physical periods after Choreographer's idle gap
            // while preserving the exact raw timestamp.  Nearest-period rounding
            // absorbs callback jitter but never collapses a new callback to zero.
            advance = std::max<std::int64_t>(
                1, (delta + mRefreshPeriod.count() / 2) /
                       mRefreshPeriod.count());
        }
        mAcceptedFrameIndex += advance;
        frame.frameIndex = mAcceptedFrameIndex;
        mLastRawFrameTimeNanos = frame.frameTimeNanos;
        mLastFrame = frame;
        mLastTimestamp = std::chrono::steady_clock::time_point(
            std::chrono::nanoseconds(frame.frameTimeNanos));
        accepted = frame;
        ++mSequenceNumber;
        mCondition.notify_all();
    }

    // Fixed mode dispatches inline on this exact physical callback lane.  The
    // body is deliberately only authority/state publication: no predictive
    // sleep, EGL/GL/JNI, planner, allocation, or tracing is allowed here.
    {
        std::lock_guard<std::mutex> workLock(mWorkMutex);
        if (mFixedInlineDispatch &&
            accepted.frameIndex > mLastWorkFrameIndex) {
            mLastWorkFrameIndex = accepted.frameIndex;
            mWorkDuration = mDoWork(accepted);
        }
    }
}

void ChoreographerFilter::setFixedInlineDispatch(bool enabled) {
    std::lock_guard<std::mutex> lock(mWorkMutex);
    mFixedInlineDispatch = enabled;
}

bool ChoreographerFilter::claimAndRun(
    const ChoreographerFrameData& frame, WorkSource source) {
    std::lock_guard<std::mutex> workLock(mWorkMutex);
    const bool sourceEnabled = mFixedInlineDispatch
        ? source == WorkSource::FIXED_RAW
        : source == WorkSource::LEGACY_PREDICTIVE;
    if (!sourceEnabled || frame.frameIndex <= mLastWorkFrameIndex) return false;

    // Claim before executing the callback.  Mode transitions and the other
    // source serialize on this mutex, so one accepted index has exactly one
    // handoff even when a predictive sleep crosses fixed-mode activation.
    mLastWorkFrameIndex = frame.frameIndex;
    gamesdk::ScopedTrace trace("doWork");
    mWorkDuration = mDoWork(frame);
    return true;
}

void ChoreographerFilter::launchThreadsLocked() {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mIsRunning.store(true, std::memory_order_release);
    }

    // Preserve stock Swappy's two predictive workers for non-fixed callers.
    // Fixed mode bypasses both sleeping workers and uses the exact-once raw
    // claim in onChoreographer().
    const int32_t numThreads = getNumCpus() > 2 ? 2 : 1;
    for (int32_t thread = 0; thread < numThreads; ++thread) {
        mThreadPool.push_back(
            Thread([this, thread]() { threadMain(mUseAffinity, thread); }));
    }
}

void ChoreographerFilter::terminateThreadsLocked() {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mIsRunning.store(false, std::memory_order_release);
        mCondition.notify_all();
    }

    for (auto& thread : mThreadPool) {
        thread.join();
    }
    mThreadPool.clear();
}

void ChoreographerFilter::onSettingsChanged() {
    const bool useAffinity = Settings::getInstance()->getUseAffinity();
    const Settings::DisplayTimings& displayTimings =
        Settings::getInstance()->getDisplayTimings();
    std::lock_guard<std::mutex> lock(mThreadPoolMutex);
    if (useAffinity == mUseAffinity &&
        mRefreshPeriod == displayTimings.refreshPeriod) {
        return;
    }

    terminateThreadsLocked();
    mUseAffinity = useAffinity;
    {
        // onChoreographer() reconstructs skipped raw indices from this period.
        // The pool lock alone does not synchronize a concurrent callback.
        std::lock_guard<std::mutex> lock(mMutex);
        mRefreshPeriod = displayTimings.refreshPeriod;
        mAppToSfDelay = displayTimings.sfOffset - displayTimings.appOffset;
    }
    SWAPPY_LOGV(
        "onSettingsChanged(): refreshPeriod=%lld, appOffset=%lld, "
        "sfOffset=%lld",
        (long long)displayTimings.refreshPeriod.count(),
        (long long)displayTimings.appOffset.count(),
        (long long)displayTimings.sfOffset.count());
    launchThreadsLocked();
}

void ChoreographerFilter::threadMain(bool useAffinity, int32_t thread) {
    Timer timer(mRefreshPeriod, mAppToSfDelay);
    setpriority(PRIO_PROCESS, 0, -4);

    if (useAffinity) {
        int cpu = getNumCpus() - 1 - thread;
        if (cpu >= 0) {
            setAffinity(cpu);
        }
    }

    std::string threadName = "Filter";
    threadName += swappy::to_string(thread);
    pthread_setname_np(pthread_self(), threadName.c_str());

    std::unique_lock<std::mutex> lock(mMutex);
    while (true) {
        auto timestamp = mLastTimestamp;
        auto frame = mLastFrame;
        lock.unlock();

        std::chrono::nanoseconds workDuration;
        {
            std::lock_guard<std::mutex> workLock(mWorkMutex);
            workDuration = mWorkDuration;
        }

        // If we have received the same timestamp multiple times, it probably
        // means that the app has stopped sending them to us, which could
        // indicate that it's no longer running. If we detect that, we stop
        // until we see a fresh timestamp to avoid spinning forever in the
        // background.
        if (!timer.addTimestamp(timestamp)) {
            lock.lock();
            mCondition.wait(lock, [this, timestamp]() {
                return !mIsRunning.load(std::memory_order_acquire) ||
                    (mLastTimestamp != timestamp);
            });
            timestamp = mLastTimestamp;
            frame = mLastFrame;
            lock.unlock();
            timer.addTimestamp(timestamp);
        }

        if (!mIsRunning.load(std::memory_order_acquire)) break;

        timer.sleep(-workDuration);
        if (!mIsRunning.load(std::memory_order_acquire)) break;
        claimAndRun(frame, WorkSource::LEGACY_PREDICTIVE);
        lock.lock();
    }
}

}  // namespace swappy
