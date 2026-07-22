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

#include <jni.h>

#include <chrono>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <optional>
#include <vector>

#include "FixedFrameTimelineAuthority.h"
#include "SwappyDisplayManager.h"
#include "Thread.h"

namespace swappy {

enum FixedCallbackDemand : std::uint8_t {
    FIXED_DEMAND_NONE = 0,
    FIXED_DEMAND_RETIREMENT = 1U << 0,
    FIXED_DEMAND_OPPORTUNITY = 1U << 1,
};

struct ChoreographerFrameData {
    // Sampled at the first native/Java callback-boundary instruction.  These
    // fields are physical delivery authority and must survive every filter
    // handoff unchanged.
    std::int64_t physicalCallbackReceiptNanos = 0;
    std::uint64_t physicalCallbackSequence = 0;
    std::uint8_t deliveredFixedDemandMask = FIXED_DEMAND_NONE;
    // CLOCK_MONOTONIC timestamp delivered by Choreographer.  It must not be
    // replaced with callback-arrival time when fixed phase planning is active.
    std::int64_t frameTimeNanos = 0;
    // Logical display-frame index assigned by ChoreographerFilter while keeping
    // the raw frameTimeNanos/index pair intact across its worker handoff.
    std::int64_t frameIndex = 0;
    // API-33 FrameTimeline metadata.  VsyncId is a token, not arithmetic frame
    // authority; frameIndex remains the wait-domain index.
    std::int64_t frameTimelineVsyncId = 0;
    std::int64_t expectedPresentationTimeNanos = 0;
    std::int64_t frameTimelineDeadlineNanos = 0;
    bool hasFrameTimeline = false;
    // Preserve every real option from this callback. The fixed Oracle may
    // select a later Case-2 presentation rather than the preferred option.
    std::vector<FixedFrameTimelineTuple> frameTimelines;
    std::optional<std::chrono::nanoseconds> sfToVsyncDelay;
};

struct FixedDemandLedgerSnapshot {
    std::uint64_t retirementIssued = 0;
    std::uint64_t retirementSatisfied = 0;
    std::uint64_t retirementCancelled = 0;
    std::uint64_t opportunityIssued = 0;
    std::uint64_t opportunitySatisfied = 0;
    std::uint64_t opportunityCancelled = 0;
    std::uint8_t pendingMask = FIXED_DEMAND_NONE;
    std::uint8_t inFlightMask = FIXED_DEMAND_NONE;
    std::uint64_t physicalPosts = 0;
    std::uint64_t physicalCallbacksDelivered = 0;
};

struct FixedDemandMutationResult {
    bool accepted = false;
    std::uint8_t deliveredMask = FIXED_DEMAND_NONE;
    std::uint8_t satisfiedMask = FIXED_DEMAND_NONE;
    std::uint8_t outstandingMask = FIXED_DEMAND_NONE;
    FixedDemandLedgerSnapshot ledgerAfter{};
    // Sampled while mWaitingMutex still owns the exact ledgerAfter mutation.
    std::int64_t mutationCompleteNanos = 0;
};

class ChoreographerThread {
   public:
    enum class Type {
        // choreographer ticks are provided by application
        App,

        // register internally with choreographer
        Swappy,
    };

    static const char* CT_CLASS;
    static const JNINativeMethod CTNativeMethods[];
    static constexpr int CTNativeMethodsSize = 1;

    using RefreshRateChangedCallback = std::function<void()>;
    using ChoreographerCallback =
        std::function<void(const ChoreographerFrameData& frame)>;

    static std::unique_ptr<ChoreographerThread> createChoreographerThread(
        Type type, JavaVM* vm, jobject jactivity,
        ChoreographerCallback onChoreographer,
        RefreshRateChangedCallback onRefreshRateChanged, SdkVersion sdkVersion);

    virtual ~ChoreographerThread() = 0;

    virtual void postFrameCallbacks();

    // Fixed non-pipeline mode is reason-demand driven. It must not inherit Swappy's adaptive
    // ten-callback keepalive train, because reposting that train is part of the callback
    // transaction and can consume the complete Case-1 admission window.
    void enterFixedDemandModeForNtk();

    // Retirement and producer opportunity demands share one physical callback.
    // The reason mask prevents duplicate posts for the same display frame.
    FixedDemandMutationResult requestFixedFrameCallbackForNtk(
        FixedCallbackDemand demand);
    FixedDemandMutationResult completeFixedFrameCallbackForNtk(
        std::uint64_t callbackSequence, std::uint8_t deliveredMask,
        std::uint8_t satisfiedMask);
    FixedDemandMutationResult cancelFixedFrameDemandForNtk(std::uint8_t mask);
    FixedDemandLedgerSnapshot getFixedDemandLedgerForNtk();

    bool isInitialized() { return mInitialized; }

   protected:
    ChoreographerThread(ChoreographerCallback onChoreographer);
    virtual void scheduleNextFrameCallback() REQUIRES(mWaitingMutex) = 0;
    virtual void onChoreographer(ChoreographerFrameData frame);
    void stampPhysicalCallbackBoundary(ChoreographerFrameData* frame) noexcept;

    std::mutex mWaitingMutex;
    int mCallbacksBeforeIdle GUARDED_BY(mWaitingMutex) = 0;
    bool mFixedDemandMode GUARDED_BY(mWaitingMutex) = false;
    std::uint8_t mFixedPendingDemandMask GUARDED_BY(mWaitingMutex) =
        FIXED_DEMAND_NONE;
    std::uint8_t mFixedInFlightDemandMask GUARDED_BY(mWaitingMutex) =
        FIXED_DEMAND_NONE;
    std::uint64_t mActivePhysicalCallbackSequence GUARDED_BY(mWaitingMutex) = 0;
    bool mPhysicalFrameCallbackPosted GUARDED_BY(mWaitingMutex) = false;
    FixedDemandLedgerSnapshot mFixedDemandLedger GUARDED_BY(mWaitingMutex){};
    std::atomic<std::uint64_t> mNextPhysicalCallbackSequence{0};
    ChoreographerCallback mCallback;
    bool mInitialized = false;

    static constexpr int MAX_CALLBACKS_BEFORE_IDLE = 10;

   private:
    FixedDemandLedgerSnapshot fixedDemandLedgerSnapshotLocked() const
        REQUIRES(mWaitingMutex);
    static bool fixedDemandLedgerConserved(
        const FixedDemandLedgerSnapshot& snapshot);
};

}  // namespace swappy
