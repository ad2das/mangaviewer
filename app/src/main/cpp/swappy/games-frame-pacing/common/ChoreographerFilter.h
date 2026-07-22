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

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <optional>
#include <vector>

#include "Settings.h"
#include "ChoreographerThread.h"
#include "Thread.h"

namespace swappy {

class ChoreographerFilter {
   public:
    using Worker = std::function<std::chrono::nanoseconds(
        const ChoreographerFrameData&)>;

    explicit ChoreographerFilter(std::chrono::nanoseconds refreshPeriod,
                                 std::chrono::nanoseconds appToSfDelay,
                                 Worker doWork);
    ~ChoreographerFilter();

    void onChoreographer(ChoreographerFrameData frame);

    // Fixed NTK pacing consumes the accepted Choreographer callback itself as
    // the raw timing authority.  The stock predictive worker remains intact
    // for every non-fixed Swappy caller.
    void setFixedInlineDispatch(bool enabled);

   private:
    enum class WorkSource {
        LEGACY_PREDICTIVE,
        FIXED_RAW,
    };

    void launchThreadsLocked();
    void terminateThreadsLocked();

    void onSettingsChanged();

    void threadMain(bool useAffinity, int32_t thread);
    bool claimAndRun(const ChoreographerFrameData& frame, WorkSource source);

    std::mutex mThreadPoolMutex;
    bool mUseAffinity = true;
    std::vector<Thread> mThreadPool;

    std::mutex mMutex;
    std::condition_variable mCondition;
    std::atomic<bool> mIsRunning{true};
    int64_t mSequenceNumber = 0;
    std::chrono::steady_clock::time_point mLastTimestamp;
    ChoreographerFrameData mLastFrame;
    std::int64_t mLastRawFrameTimeNanos = 0;
    std::int64_t mAcceptedFrameIndex = 0;

    std::mutex mWorkMutex;
    bool mFixedInlineDispatch = false;
    std::int64_t mLastWorkFrameIndex = 0;
    std::chrono::nanoseconds mWorkDuration{std::chrono::nanoseconds::zero()};

    std::chrono::nanoseconds mRefreshPeriod;
    std::chrono::nanoseconds mAppToSfDelay;
    const Worker mDoWork;
};

}  // namespace swappy
