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

#include <atomic>
#include <array>
#include <chrono>
#include <deque>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <optional>
#include <vector>

#include "CPUTracer.h"
#include "FixedExternalTransportAdmission.h"
#include "FixedExternalSubmissionContract.h"
#include "FixedNonPipelinePhase.h"
#include "FrameCompletionResult.h"
#include "ChoreographerFilter.h"
#include "ChoreographerThread.h"
#include "SwappyDisplayManager.h"
#include "Thread.h"
#include "swappy/swappyGL.h"
#include "swappy/swappyGL_extra.h"

namespace swappy {

// ANativeWindow_setFrameRate is supported from API 30. To allow compilation for
// minSDK < 30 we need runtime support to call this API.
using PFN_ANativeWindow_setFrameRate = int32_t (*)(ANativeWindow* window,
                                                   float frameRate,
                                                   int8_t compatibility);

using namespace std::chrono_literals;

struct SwappyCommonSettings {
    SdkVersion sdkVersion;

    std::chrono::nanoseconds refreshPeriod;
    std::chrono::nanoseconds appVsyncOffset;
    std::chrono::nanoseconds sfVsyncOffset;
    // Exact AChoreographer FrameTimeline expected-to-deadline interval.  This
    // excludes Display.getPresentationDeadlineNanos()'s historical 1 ms
    // compositor allowance so fixed phase and setFrameTimeline share one D.
    std::chrono::nanoseconds presentationDeadline;

    static bool getFromApp(JNIEnv* env, jobject jactivity,
                           SwappyCommonSettings* out);
    static SdkVersion getSDKVersion(JNIEnv* env);
    static bool queryDisplayTimings(JNIEnv* env, jobject jactivity,
                                    SwappyCommonSettings* out);
};

struct RawFixedFrameAuthority {
    std::uint64_t sequence = 0;
    std::uint64_t physicalCallbackSequence = 0;
    std::int64_t frameTimeNanos = 0;
    std::int64_t frameIndex = 0;
    std::int64_t frameTimelineVsyncId = 0;
    std::int64_t timelineExpectedPresentationNanos = 0;
    std::int64_t timelinePresentationDeadlineNanos = 0;
    std::int64_t callbackReceiptNanos = 0;
    bool hasTimeline = false;
    std::vector<FixedFrameTimelineTuple> frameTimelines;
};

enum class FixedPhaseAdmissionStatus : std::int32_t {
    ADMITTED = 0,
    WAITING_PRIOR_TARGET = 1,
    WAITING_CANDIDATE = 2,
    WAITING_PRIOR_LATCH = 3,
    SLOT_CLOSED_WAITING_NEXT = 4,
    FATAL = 6,
};

enum class FixedRawCandidateState : std::uint8_t {
    AVAILABLE,
    CLAIMED,
};

enum class FixedOpportunityKind : std::uint8_t {
    FIRST = 1,
    NEXT = 2,
};

enum class FixedCandidateCaptureResult : std::uint8_t {
    CAPTURED,
    NO_RAW,
    RAW_CLOSED,
    FATAL,
};

struct FixedRawCandidate {
    std::uint64_t candidateSequence = 0;
    std::uint64_t reservationSequence = 0;
    std::uint64_t workGeneration = 0;
    RawFixedFrameAuthority raw{};
    std::int64_t capturedNanos = 0;
    std::int64_t claimedNanos = 0;
    bool carriedIntoReservation = false;
    FixedRawCandidateState state = FixedRawCandidateState::AVAILABLE;
    std::shared_ptr<SwappyFixedWakeNotice> wakeNotice;
};

struct FixedPublishedOpportunity {
    std::uint64_t opportunitySequence = 0;
    std::uint64_t candidateSequence = 0;
    std::uint64_t reservationSequence = 0;
    std::uint64_t workGeneration = 0;
    RawFixedFrameAuthority raw{};
    std::int64_t publishNanos = 0;
    SwappyFixedWakeNotice wakeNotice{};
    // Frozen JOIN_OPEN evidence.  A successor opportunity exists only after
    // both prior retirement and the exact prior compositor latch are visible.
    SwappyFixedLatchObservationV1 priorLatchObservation{};
    std::uint32_t priorLatchGateRequired = 0;
    std::uint32_t priorLatchGateUsed = 0;
    std::uint32_t priorLatchWaitCount = 0;
};

struct FixedReservationReceipt {
    std::uint64_t workGeneration = 0;
    std::uint64_t reservationSequence = 0;
    std::uint64_t rawBaselineSequence = 0;
    std::int64_t reservationNanos = 0;
};

struct FixedOpportunityIdentity {
    std::uint64_t workGeneration = 0;
    std::uint64_t reservationSequence = 0;
    std::uint64_t opportunitySequence = 0;
    std::uint64_t candidateSequence = 0;
    std::uint64_t noticeSequence = 0;

    bool valid() const noexcept {
        return workGeneration != 0 && reservationSequence != 0 &&
            opportunitySequence != 0 && candidateSequence != 0 &&
            noticeSequence != 0;
    }
};

struct ClosedOpportunityResult {
    bool exact = false;
    bool nextPublished = false;
    bool nextDemandOutstanding = false;
    std::optional<SwappyFixedWakeNotice> nextNotice{};
};

struct FixedRefreshTicket {
    std::uint64_t reservationSequence = 0;
    bool issued = false;
    bool delivered = false;
    std::uint64_t physicalCallbackSequence = 0;
    std::uint64_t capturedRawSequence = 0;
};

struct FixedPhaseAdmissionToken {
    std::uint64_t sequence = 0;
    std::uint64_t workGeneration = 0;
    std::uint64_t reservationSequence = 0;
    std::uint64_t opportunitySequence = 0;
    std::uint64_t candidateSequence = 0;
    FixedOpportunityKind opportunityKind = FixedOpportunityKind::FIRST;
    std::uint64_t priorRetirementWorkGeneration = 0;
    std::uint64_t priorRetirementAdmissionSequence = 0;
    std::uint64_t priorRetirementSequence = 0;
    std::int64_t candidateCaptureNanos = 0;
    std::int64_t candidateClaimNanos = 0;
    std::uint64_t shadowRawSequence = 0;
    std::uint64_t shadowPromotionCount = 0;
    FixedRefreshTicket refresh{};
    SwappyFixedWakeNotice joinNotice{};
    SwappyFixedPriorRetirementProofV1 priorRetirementProof{};
    SwappyFixedLatchObservationV1 priorLatchObservation{};
    bool priorLatchObservedAtClaim = false;
    bool priorCommitProofPendingAtClaim = false;
    std::uint32_t priorLatchWaitCount = 0;
    RawFixedFrameAuthority raw{};
    FixedPhasePlanInput input{};
    FixedPhasePlan plan{};
    SwappyFixedExternalTransportReady transportReady{};
    FixedExternalTransportAdmission transportAdmission{};
    std::int64_t commonCommitEntryNanos = 0;
    std::int64_t initialDecisionNanos = 0;
    std::int64_t case2GateWaitTargetNanos = 0;
    std::int64_t case2GateWaitReturnNanos = 0;
    std::uint32_t phaseWaitCount = 0;
    bool consumed = false;
    bool gpuProofReady = false;
    std::uint64_t gpuProofGeneration = 0;
};

enum class FixedProducerState : std::uint8_t {
    RESERVED,
    GPU_READY_NO_CANDIDATE,
    GPU_READY_CLAIMED,
    JOIN_WAITING,
    JOIN_WAITING_PRIOR_PHYSICAL,
    PHASE_STAGED,
    TOKEN_ISSUED,
    QUEUEING,
    SUBMITTED,
    FATAL,
};

// One process-wide fixed-mode reservation whose GPU proof is owned by the
// renderer and supplied through the external submission contract.
struct FixedPreparedFrameIdentity {
    std::uint64_t workGeneration = 0;
    std::uint64_t reservationSequence = 0;
    std::int64_t reservationNanos = 0;
    std::uint64_t rawBaselineSequence = 0;
    std::uint64_t gpuProofGeneration = 0;
    SwappyFixedExternalTransportReady transportReady{};
    FixedProducerState state = FixedProducerState::RESERVED;
    bool gpuProofReady = false;
    bool commitInFlight = false;
    bool priorLatchBlocked = false;
};

enum class FixedRetirementState : std::uint8_t {
    EMPTY,
    WAIT_ARMED,
    PUBLISHING,
    RETIRED,
    FATAL,
};

struct FixedSubmittedRetirement {
    std::uint64_t retirementSequence = 0;
    std::uint64_t admissionSequence = 0;
    std::uint64_t workGeneration = 0;
    std::uint64_t frameId = 0;
    std::uint64_t engineGeneration = 0;
    std::uint64_t surfaceEpoch = 0;
    std::int64_t authorityGeneration = 0;
    std::int64_t authority = 0;
    std::uint64_t frameSequence = 0;
    std::uint64_t capsuleSequence = 0;
    std::uint64_t backendSurfaceSerial = 0;
    std::uint64_t transactionSerial = 0;
    std::uint64_t bufferSlot = 0;
    std::uint64_t bufferGeneration = 0;
    std::int64_t frameTimelineVsyncId = 0;
    std::uint64_t rawAuthoritySequence = 0;
    std::int64_t plannedTargetFrame = 0;
    std::int64_t originalTargetFrame = 0;
    std::int64_t postSwapNanos = 0;
    std::int64_t waitBeginNanos = 0;
    std::uint64_t targetAuthorityRawSequence = 0;
    std::uint64_t targetPhysicalCallbackSequence = 0;
    std::int64_t targetFrameTimeNanos = 0;
    std::int64_t targetFrameIndex = 0;
    std::int64_t targetAuthorityNanos = 0;
    std::int64_t targetReachedNanos = 0;
    std::int64_t retirementPublishNanos = 0;
    std::int64_t rendererWakePublishNanos = 0;
    std::int64_t retirementCompleteNanos = 0;
    std::int64_t retirementStageNanos = 0;
    std::int64_t demandMutationCompleteNanos = 0;
    std::int64_t terminalVisibleNanos = 0;
    std::int64_t wakeDispatchNanos = 0;
    std::uint64_t retirementDemandIssued = 0;
    std::uint64_t retirementDemandSatisfied = 0;
    std::uint64_t retirementDemandCancelled = 0;
    FixedRetirementState state = FixedRetirementState::EMPTY;
    bool terminalPublicationComplete = false;
    bool externalProofPublished = false;
    bool retirementCallbackPublished = false;
    std::uint32_t retirementCallbackPublishCount = 0;
    std::uint32_t targetWaitCount = 0;
    std::uint32_t targetRebaseCount = 0;
    std::int32_t fatalReason = 0;
    SwappyFixedAppliedBufferRefV1 appliedBufferRef{};
    SwappyFixedPriorRetirementProofV1 immutableProof{};
};

struct FixedPostSwapStamp {
    std::int64_t finalCorridorBeginNanos = 0;
    std::int64_t decisionNanos = 0;
    std::int64_t queueMarkNanos = 0;
    std::int64_t eglSwapEnterNanos = 0;
    std::int64_t postSwapNanos = 0;
    bool phaseValid = false;
    std::int32_t outcome = 0;
    std::int32_t fatalReason = 0;
};

// Common part between OpenGL and Vulkan implementations.
class SwappyCommon {
    friend class SwappyGL;
    friend class SwappyCommonFixedAdmissionTestPeer;

   public:
    enum class PipelineMode { Off, On };

    // callbacks to be called during pre/post swap
    struct SwapHandlers {
        std::function<bool()> lastFrameIsComplete;
        std::function<FrameCompletionResult()> waitForCurrentFrameCompletion;
        std::function<std::chrono::nanoseconds()> getPrevFrameGpuTime;
    };

    SwappyCommon(JNIEnv* env, jobject jactivity);

    ~SwappyCommon();

    std::chrono::nanoseconds getSwapDuration();

    void onChoreographer(int64_t frameTimeNanos);

    bool onPreSwap(const SwapHandlers& h);

    bool needToSetPresentationTime() { return mPresentationTimeNeeded; }

    bool onPostSwap(const SwapHandlers& h);

    PipelineMode getCurrentPipelineMode() {
        std::lock_guard<std::mutex> lock(mMutex);
        return mPipelineMode;
    }

    template <typename... T>
    struct Tracer {
        void (*function)(void*, T...);
        void* userData;
    };

    void addTracerCallbacks(const SwappyTracer& tracer);

    void removeTracerCallbacks(const SwappyTracer& tracer);

    void setAutoSwapInterval(bool enabled);
    void setAutoPipelineMode(bool enabled);
    void setFixedNonPipelineMode(std::chrono::nanoseconds swapDuration);
    int32_t getPipelineModeForNtk();
    bool isFixedNonPipelineModeForNtk();
    bool isBlockingWaitEnabledForNtk();
    bool isFixedPhaseConfigurationValidForNtk();
    bool getFixedPhaseTelemetryForNtk(
        std::uint64_t workGeneration, SwappyFixedPhaseTelemetry* output);
    FixedPhaseAdmissionStatus reserveFixedFrameForNtk(
        std::uint64_t workGeneration,
        FixedReservationReceipt* out);
    bool markReservedExternalGpuReadyForNtk(
        std::uint64_t workGeneration,
        const SwappyFixedExternalTransportReady& transportReady);
    FixedPhaseAdmissionStatus commitPreparedFixedFrameForNtk(
        const FixedOpportunityIdentity& expected,
        FixedPhaseAdmissionToken* out,
        const SwappyFixedExternalTransportReady& transportReady);
    bool recordExternalLatchObservationForNtk(
        const SwappyFixedLatchObservationV1& observation);
    FixedPostSwapStamp beginFixedPostSwapForNtk(
        const FixedPhaseAdmissionToken& token,
        std::int64_t finalCorridorBeginNanos,
        std::int64_t queueMarkNanos,
        std::int64_t eglSwapEnterNanos);
    bool finishFixedPostSwapForNtk(
        const FixedPhaseAdmissionToken& token,
        const FixedPostSwapStamp& stamp,
        SwappyFixedPhaseTelemetry* outPhase,
        const SwappyFixedExternalClaim& externalClaim,
        const SwappyFixedExternalSubmission& externalSubmission,
        std::uint64_t* outRetirementSequence = nullptr);
    bool abortClaimedExternalFixedFrameForNtk(
        std::uint64_t workGeneration);
    bool abortPreparedFixedFrameForNtk(std::uint64_t workGeneration);
    void markFixedPhaseSubmissionFailureForNtk();
    static bool planFixedPhaseForTesting(
        const SwappyFixedPhasePlanInput* input,
        SwappyFixedPhaseTelemetry* output);
    static std::int64_t externalClaimClockNowNanos() noexcept;
    bool hasFatalPacingErrorForNtk() const {
        return mFatalPacingError.load(std::memory_order_acquire);
    }

    void setMaxAutoSwapDuration(std::chrono::nanoseconds swapDuration) {
        mAutoSwapIntervalThreshold = swapDuration;
    }

    std::chrono::steady_clock::time_point getPresentationTime() {
        return mPresentationTime;
    }
    std::chrono::nanoseconds getRefreshPeriod() const {
        return mCommonSettings.refreshPeriod;
    }

    bool isValid() { return mValid; }

    std::chrono::nanoseconds getFenceTimeout() const { return mFenceTimeout; }
    void setFenceTimeout(std::chrono::nanoseconds t) { mFenceTimeout = t; }

    bool isDeviceUnsupported();

    void setANativeWindow(ANativeWindow* window);

    void setBufferStuffingFixWait(int32_t nFrames) {
        mBufferStuffingFixWait = std::max(0, nFrames);
    }

    int getSupportedRefreshPeriodsNS(uint64_t* out_refreshrates,
                                     int allocated_entries);

    void setLastLatencyRecordedCallback(std::function<int32_t()> callback) {
        mLastLatencyRecorded = callback;
    }

    void resetFramePacing();

    void enableFramePacing(bool enable);
    void enableBlockingWait(bool enable);

   protected:
    // Used for testing
    SwappyCommon(const SwappyCommonSettings& settings);

   private:
    class FrameDuration {
       public:
        FrameDuration() = default;

        FrameDuration(std::chrono::nanoseconds cpuTime,
                      std::chrono::nanoseconds gpuTime,
                      bool frameMissedDeadline)
            : mCpuTime(cpuTime),
              mGpuTime(gpuTime),
              mFrameMissedDeadline(frameMissedDeadline) {
            mCpuTime = std::min(mCpuTime, MAX_DURATION);
            mGpuTime = std::min(mGpuTime, MAX_DURATION);
        }

        std::chrono::nanoseconds getCpuTime() const { return mCpuTime; }
        std::chrono::nanoseconds getGpuTime() const { return mGpuTime; }

        bool frameMiss() const { return mFrameMissedDeadline; }

        std::chrono::nanoseconds getTime(PipelineMode pipeline) const {
            if (mCpuTime == 0ns && mGpuTime == 0ns) {
                return 0ns;
            }

            if (pipeline == PipelineMode::On) {
                return std::max(mCpuTime, mGpuTime) + FRAME_MARGIN;
            }

            return mCpuTime + mGpuTime + FRAME_MARGIN;
        }

        FrameDuration& operator+=(const FrameDuration& other) {
            mCpuTime += other.mCpuTime;
            mGpuTime += other.mGpuTime;
            return *this;
        }

        FrameDuration& operator-=(const FrameDuration& other) {
            mCpuTime -= other.mCpuTime;
            mGpuTime -= other.mGpuTime;
            return *this;
        }

        friend FrameDuration operator/(FrameDuration lhs, int rhs) {
            lhs.mCpuTime /= rhs;
            lhs.mGpuTime /= rhs;
            return lhs;
        }

       private:
        std::chrono::nanoseconds mCpuTime = std::chrono::nanoseconds(0);
        std::chrono::nanoseconds mGpuTime = std::chrono::nanoseconds(0);
        bool mFrameMissedDeadline = false;

        static constexpr std::chrono::nanoseconds MAX_DURATION =
            std::chrono::milliseconds(100);
    };

    void addFrameDuration(FrameDuration duration);
    std::chrono::nanoseconds wakeClient(
        const ChoreographerFrameData& frame);

    bool swapFaster(int newSwapInterval) REQUIRES(mMutex);

    bool swapSlower(const FrameDuration& averageFrameTime,
                    const std::chrono::nanoseconds& upperBound,
                    int newSwapInterval) REQUIRES(mMutex);
    bool updateSwapInterval();
    void preSwapBuffersCallbacks();
    void postSwapBuffersCallbacks();
    void fixedPhaseOpportunityCallbacks(
        const SwappyFixedWakeNotice* exactNotice);
    void fixedRetirementCompletedCallbacks(
        const FixedSubmittedRetirement& retired);
    void preWaitCallbacks();
    void postWaitCallbacks(std::chrono::nanoseconds cpuTime,
                           std::chrono::nanoseconds gpuTime);
    void startFrameCallbacks();
    void swapIntervalChangedCallbacks();
    void onSettingsChanged();
    void updateMeasuredSwapDuration(std::chrono::nanoseconds duration);
    void startFrame();
    void waitUntil(std::int64_t target);
    std::uint8_t onFixedChoreographerAuthority(
        const ChoreographerFrameData& frame);
    FixedCandidateCaptureResult captureLatestFixedRawCandidateLocked(
        bool carriedIntoReservation,
        const FixedPhasePlanInput& configuration,
        std::int64_t captureNanos,
        SwappyFixedWakeNotice* candidateNotice)
        REQUIRES(mWaitingMutex);
    bool claimAvailableFixedRawCandidateLocked(
        std::uint64_t workGeneration, std::int64_t claimNanos)
        REQUIRES(mWaitingMutex);
    bool publishClaimedFixedOpportunityIfJoinOpenLocked(
        std::int64_t publishNanos, SwappyFixedWakeNotice* joinNotice,
        bool* callbackDispatchRequired)
        REQUIRES(mWaitingMutex);
    ClosedOpportunityResult disposeClosedClaimAndPromoteShadowLocked(
        const FixedOpportunityIdentity& expected,
        std::int64_t decisionNanos)
        REQUIRES(mWaitingMutex);
    FixedPhaseAdmissionStatus finishClosedOpportunityForNtk(
        const FixedOpportunityIdentity& expected,
        std::int64_t decisionNanos);
    void finishFixedFrameStatistics(
        FixedSubmittedRetirement& retired);
    void publishFixedRetirementTerminalOnce(
        FixedSubmittedRetirement& retirement);
    static bool qualifiesFixedTargetAuthority(
        const FixedSubmittedRetirement& submitted,
        const RawFixedFrameAuthority& raw);
    void waitUntilTargetFrame();
    void waitOneFrame();
    void setPreferredDisplayModeId(int index);
    void setPreferredRefreshPeriod(std::chrono::nanoseconds frameTime)
        REQUIRES(mMutex);
    int calculateSwapInterval(std::chrono::nanoseconds frameTime,
                              std::chrono::nanoseconds refreshPeriod);
    void updateDisplayTimings();

    // Waits for the next frame, considering both Choreographer and the prior
    // frame's completion
    bool waitForNextFrame(const SwapHandlers& h);
    bool commitFixedPreSwapTimestamp();
    bool validateFixedConfigurationLocked(
        std::chrono::nanoseconds swapDuration) const REQUIRES(mMutex);

    void onRefreshRateChanged();

    inline bool swapFasterCondition() {
        return mSwapDuration <=
               mCommonSettings.refreshPeriod * (mAutoSwapInterval - 1) +
                   DURATION_ROUNDING_MARGIN;
    }

    const jobject mJactivity;
    void* mLibAndroid = nullptr;
    PFN_ANativeWindow_setFrameRate mANativeWindow_setFrameRate = nullptr;

    JavaVM* mJVM = nullptr;

    SwappyCommonSettings mCommonSettings;

    std::unique_ptr<ChoreographerFilter> mChoreographerFilter;

    bool mUsingExternalChoreographer = false;
    std::unique_ptr<ChoreographerThread> mChoreographerThread;
    std::mutex mWaitingMutex;
    std::condition_variable mWaitingCondition;
    bool mFixedLifecycleClosing GUARDED_BY(mWaitingMutex) = false;
    std::chrono::steady_clock::time_point mCurrentFrameTimestamp =
        std::chrono::steady_clock::now();
    std::int64_t mCurrentFrame = 0;
    std::int64_t mAcceptedFrameTimeNanos = 0;
    std::int64_t mAcceptedFrameIndex = 0;
    std::int64_t mFrameTimelineVsyncId = 0;
    std::int64_t mTimelinePresentationDeadlineNanos = 0;
    bool mAcceptedFrameHasTimeline = false;
    std::optional<std::chrono::nanoseconds> mSfToVsyncDelay;
    std::atomic<std::chrono::nanoseconds> mMeasuredSwapDuration;

    std::chrono::steady_clock::time_point mSwapTime;

    std::mutex mMutex;
    class FrameDurations {
       public:
        void add(FrameDuration frameDuration);
        bool hasEnoughSamples() const;
        FrameDuration getAverageFrameTime() const;
        int getMissedFramePercent() const;
        void clear();

       private:
        static constexpr std::chrono::nanoseconds
            FRAME_DURATION_SAMPLE_SECONDS = 2s;

        std::deque<std::pair<std::chrono::time_point<std::chrono::steady_clock>,
                             FrameDuration>>
            mFrames;
        FrameDuration mFrameDurationsSum = {};
        int mMissedFrameCount = 0;
    };

    FrameDurations mFrameDurations GUARDED_BY(mMutex);

    bool mAutoSwapIntervalEnabled GUARDED_BY(mMutex) = true;
    bool mPipelineModeAutoMode GUARDED_BY(mMutex) = true;
    bool mFixedNonPipelineMode GUARDED_BY(mMutex) = false;
    bool mFixedPhaseConfigurationValid GUARDED_BY(mMutex) = false;

    static constexpr std::chrono::nanoseconds FRAME_MARGIN = 1ms;
    static constexpr std::chrono::nanoseconds DURATION_ROUNDING_MARGIN = 1us;
    static constexpr int NON_PIPELINE_PERCENT = 50;  // 50%
    static constexpr int FRAME_DROP_THRESHOLD = 10;  // 10%

    std::chrono::nanoseconds mSwapDuration = 0ns;
    int32_t mAutoSwapInterval;
    std::atomic<std::chrono::nanoseconds> mAutoSwapIntervalThreshold = {
        50ms};  // 20FPS
    static constexpr std::chrono::nanoseconds REFRESH_RATE_MARGIN = 500ns;

    std::chrono::steady_clock::time_point mStartFrameTime;

    struct SwappyTracerCallbacks {
        std::list<Tracer<>> preWait;
        std::list<Tracer<int64_t, int64_t>> postWait;
        std::list<Tracer<>> preSwapBuffers;
        std::list<Tracer<int64_t>> postSwapBuffers;
        std::list<Tracer<int32_t, int64_t>> startFrame;
        std::list<Tracer<>> swapIntervalChanged;
        std::list<Tracer<const SwappyFixedRetirementTelemetryV2*>>
            fixedRetirementCompleted;
    };

    // Injected callbacks are process-global for SwappyGL while renderer instances can overlap
    // during an exact engine handoff.  The fixed opportunity observer is deliberately isolated
    // from every callback that may enter EGL (notably postSwapSlack): a raw Choreographer
    // authority must never queue behind driver timestamp work.  Registration/removal acquire
    // general then fixed (that explicit order), while execution acquires only its lane.  Successful removal
    // is therefore still the quiescence boundary for every callback carrying that userData.
    std::mutex mInjectedTracersMutex;
    std::mutex mFixedPhaseOpportunityTracersMutex;
    // JOIN_OPEN is a two-sided handoff: the callback first publishes the
    // renderer's exact opportunity and then returns its observed timestamp to
    // Common.  Serialize that callback/ack interval with the claim snapshot so
    // a renderer wake on another thread cannot observe only half the handoff.
    std::mutex mFixedOpportunityHandoffMutex;
    SwappyTracerCallbacks mInjectedTracers;
    std::list<Tracer<SwappyFixedWakeNotice*>>
        mFixedPhaseOpportunityTracers;

    std::int64_t mTargetFrame GUARDED_BY(mWaitingMutex) = 0;
    RawFixedFrameAuthority mRawFixedFrameAuthority
        GUARDED_BY(mWaitingMutex){};
    std::optional<FixedPhaseAdmissionToken> mFixedAdmissionToken
        GUARDED_BY(mWaitingMutex);
    std::optional<FixedPreparedFrameIdentity> mFixedPreparedFrame
        GUARDED_BY(mWaitingMutex);
    std::optional<FixedSubmittedRetirement> mFixedSubmittedRetirement
        GUARDED_BY(mWaitingMutex);
    std::optional<FixedRawCandidate> mFixedAvailableCandidate
        GUARDED_BY(mWaitingMutex);
    std::optional<FixedRawCandidate> mFixedClaimedCandidate
        GUARDED_BY(mWaitingMutex);
    std::optional<FixedPublishedOpportunity> mFixedPublishedOpportunity
        GUARDED_BY(mWaitingMutex);
    // Exact compositor proof for the current predecessor.  Retirement and
    // this observation form the JOIN_OPEN intersection; neither alone may
    // publish successor authority.
    std::optional<SwappyFixedLatchObservationV1>
        mFixedObservedPriorLatchSnapshot GUARDED_BY(mWaitingMutex);
    FixedRefreshTicket mFixedRefreshTicket GUARDED_BY(mWaitingMutex){};
    std::uint64_t mFixedReservationSequence GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedCandidateSequence GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedOpportunitySequence GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedSupersededBeforeClaimCount
        GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedClosedOpportunityCount
        GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedShadowPromotionCount
        GUARDED_BY(mWaitingMutex) = 0;
    // A raw authority is disposed exactly once: either it issued an admitted
    // token or its immutable phase slot was observed closed without attempt.
    // Reservation snapshots this floor; it never discards a still-open raw.
    std::uint64_t mFixedLastDisposedRawSequence
        GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedRetirementSequence GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedWakeNoticeSequence GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedSubmittedCount GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedTargetRetiredCount GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mFixedAdmissionSequence GUARDED_BY(mWaitingMutex) = 0;
    std::int64_t mFixedAdmissionOriginalTargetFrame
        GUARDED_BY(mWaitingMutex) = 0;
    std::uint64_t mLastAdmittedWorkGeneration
        GUARDED_BY(mWaitingMutex) = 0;
    // Prepared commit publishes the Oracle decision and preSwap as one exact
    // sample.  onPreSwap observes this bit and must not query the clock again.
    bool mFixedAdmissionPreSwapCommitted GUARDED_BY(mWaitingMutex) = false;
    std::chrono::steady_clock::time_point mPresentationTime =
        std::chrono::steady_clock::now();
    bool mPresentationTimeNeeded;
    PipelineMode mPipelineMode = PipelineMode::Off;

    bool mValid;

    std::chrono::nanoseconds mFenceTimeout = std::chrono::nanoseconds(50ms);

    constexpr static bool USE_DISPLAY_MANAGER = true;
    std::unique_ptr<SwappyDisplayManager> mDisplayManager;
    int mNextModeId = -1;

    std::shared_ptr<SwappyDisplayManager::RefreshPeriodMap>
        mSupportedRefreshPeriods;

    struct TimingSettings {
        std::chrono::nanoseconds refreshPeriod = {};
        std::chrono::nanoseconds swapDuration = {};

        static TimingSettings from(const Settings& settings) {
            TimingSettings timingSettings;

            timingSettings.refreshPeriod =
                settings.getDisplayTimings().refreshPeriod;
            timingSettings.swapDuration = settings.getSwapDuration();
            return timingSettings;
        }

        bool operator!=(const TimingSettings& other) const {
            return (refreshPeriod != other.refreshPeriod) ||
                   (swapDuration != other.swapDuration);
        }

        bool operator==(const TimingSettings& other) const {
            return !(*this != other);
        }
    };
    TimingSettings mNextTimingSettings GUARDED_BY(mMutex) = {};
    bool mTimingSettingsNeedUpdate GUARDED_BY(mMutex) = false;

    CPUTracer mCPUTracer;

    ANativeWindow* mWindow GUARDED_BY(mMutex) = nullptr;
    bool mWindowChanged GUARDED_BY(mMutex) = false;
    float mLatestFrameRateVote GUARDED_BY(mMutex) = 0.f;
    static constexpr float FRAME_RATE_VOTE_MARGIN = 1.f;  // 1Hz

    // Callback for last latency recorded - used for buffer stuffing fix.
    // Latency is returned in number of V-syncs
    std::function<int32_t()> mLastLatencyRecorded;

    // If zero, don't apply the double buffering fix. If non-zero, apply
    // the fix after this number of bad frames.
    int mBufferStuffingFixWait = 0;
    // When zero, buffer stuffing fixing may occur.
    // After a fix has been applied, this is non-zero and counts down to avoid
    // consecutive fixes.
    int mBufferStuffingFixCounter = 0;
    // Counts the number of consecutive missed frames (as judged by expected
    // latency).
    int mMissedFrameCounter = 0;

    bool mFramePacingResetRequested GUARDED_BY(mMutex) = false;

    std::chrono::nanoseconds mInitialRefreshPeriod;

    bool mFramePacingToggleRequested GUARDED_BY(mMutex) = false;
    bool mFramePacingEnabled GUARDED_BY(mMutex) = true;
    bool mBlockingWaitEnabled GUARDED_BY(mMutex) = true;
    std::atomic<bool> mFatalPacingError{false};

    enum class FixedLatchRecordState : std::uint8_t {
        EMPTY = 0,
        EXPECTED = 1,
        OBSERVED = 2,
        CONSUMED_BY_SUCCESSOR = 3,
        FAILED = 4,
    };
    struct FixedLatchObservationRecord {
        FixedLatchRecordState state = FixedLatchRecordState::EMPTY;
        SwappyFixedFrameIdentityV1 expected{};
        SwappyFixedLatchObservationV1 observation{};
        std::uint32_t observationCount = 0;
        std::uint64_t registrationSequence = 0;
    };
    enum class FixedLatchLookupResult : std::uint8_t {
        MISSING,
        PENDING,
        OBSERVED,
        CONSUMED,
        FAILED,
    };
    static constexpr std::size_t FIXED_LATCH_OBSERVATION_DEPTH = 8;
    std::mutex mFixedLatchObservationMutex;
    std::array<FixedLatchObservationRecord,
               FIXED_LATCH_OBSERVATION_DEPTH> mFixedLatchObservations
        GUARDED_BY(mFixedLatchObservationMutex){};
    std::uint64_t mFixedLatchRegistrationSequence
        GUARDED_BY(mFixedLatchObservationMutex) = 0;
    bool registerFixedLatchExpectation(
        const SwappyFixedFrameIdentityV1& identity);
    FixedLatchLookupResult snapshotFixedLatchObservation(
        const SwappyFixedFrameIdentityV1& identity,
        SwappyFixedLatchObservationV1* observation);
    bool consumeFixedLatchObservationForSuccessor(
        const SwappyFixedFrameIdentityV1& identity,
        const SwappyFixedLatchObservationV1& expectedObservation);
    bool discardFixedLatchExpectation(
        const SwappyFixedFrameIdentityV1& identity);

    enum class FixedPhaseFatalReason : std::int32_t {
        NONE = 0,
        INVALID_CONFIGURATION = 1,
        NO_RAW_FRAME_AUTHORITY = 2,
        PLAN_REJECTED = 3,
        ABSOLUTE_WAIT_FAILED = 4,
        LATE_WAKE = 5,
        CUTOFF_PASSED_BEFORE_PRESWAP = 6,
        SWAP_DURATION_INVALID = 7,
        SWAP_MISSED_CUTOFF = 8,
        TARGET_NOT_PUBLISHED = 9,
        FENCE_WAIT_FAILED = 10,
        ADMISSION_TOKEN_MISSING = 11,
        ADMISSION_IDENTITY_MISMATCH = 12,
        ADMISSION_ALREADY_CONSUMED = 13,
        SUBMISSION_FAILED = 14,
        PRESENTATION_TIME_FORBIDDEN = 15,
        BACKEND_NOT_READY = 16,
        RENDERING_COMPLETE_AFTER_QUEUE = 17,
        TRANSPORT_BOUND_EXCEEDED = 18,
        INVALID_FRAME_TIMELINE_AUTHORITY = 19,
        CONSERVATION_FAILURE = 20,
    };

    bool failFixedPhase(FixedPhaseFatalReason reason);

    std::mutex mFixedPhaseTelemetryMutex;
    struct FixedGenerationTelemetryRecord {
        std::uint64_t workGeneration = 0;
        SwappyFixedPhaseTelemetry phase{};
        FixedPhasePlan plan{};
    };
    static constexpr std::size_t FIXED_TELEMETRY_RING_SIZE = 16;
    std::array<FixedGenerationTelemetryRecord, FIXED_TELEMETRY_RING_SIZE>
        mFixedTelemetryRing GUARDED_BY(mFixedPhaseTelemetryMutex){};
    std::size_t mFixedActiveTelemetrySlot
        GUARDED_BY(mFixedPhaseTelemetryMutex) = 0;
    std::size_t mFixedNextTelemetrySlot
        GUARDED_BY(mFixedPhaseTelemetryMutex) = 0;
    bool mFixedActiveTelemetryValid
        GUARDED_BY(mFixedPhaseTelemetryMutex) = false;
    FixedGenerationTelemetryRecord* findFixedTelemetryLocked(
        std::uint64_t workGeneration) REQUIRES(mFixedPhaseTelemetryMutex);
    FixedGenerationTelemetryRecord& beginFixedTelemetryLocked(
        std::uint64_t workGeneration) REQUIRES(mFixedPhaseTelemetryMutex);
    FixedGenerationTelemetryRecord* activeFixedTelemetryLocked()
        REQUIRES(mFixedPhaseTelemetryMutex);
    std::uint64_t mFixedPhaseSequence
        GUARDED_BY(mFixedPhaseTelemetryMutex) = 0;
};

}  // namespace swappy
